/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Bounded production receipt decode against a test-owned PRIVATE_AGGREGATE function contract. */
public class ProductionDecodedPlanSpaceCompletenessTest {
	private static final PlacementState PROTECTED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final String A_GEOMETRY =
		"ROW:localhost:4234/A1|0,0:4,2,localhost:4235/A2|4,0:8,2";
	private static final String B_GEOMETRY =
		"ROW:localhost:4234/B1|0,0:4,2,localhost:4235/B2|4,0:8,2";
	private static final String SCRIPT =
		"f=function(matrix[double] X,matrix[double] Y) return (matrix[double] D){"
			+ "U=X*2;V=Y*3;D=U*V;}"
			+ "A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "B=federated(addresses=list(\"localhost:4234/B1\",\"localhost:4235/B2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "D=f(A,B);print(sum(D));";

	@Test
	public void productionFunctionReceiptProductMatchesLiteralContract() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = rawAvailableReceipts(analysis);
		Map<String,CompiledHopKey> roles = fixtureRoles(analysis, raw);
		Set<CompiledHopKey> seeds = new LinkedHashSet<>(List.of(
			roles.get("U"), roles.get("V"), roles.get("D")));
		raw.forEach((owner, receipts) -> {
			if("compiled".equals(owner.recompileContext()) && receipts.stream().anyMatch(receipt ->
				"NATIVE_LINEAGE".equals(receipt.realization().key().layoutKind().name())))
				seeds.add(owner);
		});
		Set<CompiledHopKey> closure = bindingClosure(raw, seeds);
		Assert.assertTrue("compiled native component must include X, Y, U, V and D",
			closure.containsAll(roles.values()));

		Map<String,List<CandidateSelectionReceipt>> rawDomains = new LinkedHashMap<>();
		Map<String,List<CandidateSelectionReceipt>> supportDomains = new LinkedHashMap<>();
		Map<String,List<CandidateSelectionReceipt>> declaredDomains = new LinkedHashMap<>();
		Map<String,Map<ReceiptDisposition,List<String>>> receiptClassifications = new LinkedHashMap<>();
		Map<String,Integer> rawDomainSizes = new LinkedHashMap<>();
		Map<String,Integer> supportDomainSizes = new LinkedHashMap<>();
		for(var role : roles.entrySet()) {
			List<CandidateSelectionReceipt> all = raw.getOrDefault(role.getValue(), List.of());
			Assert.assertFalse("raw production receipt domain is empty for " + role.getKey(), all.isEmpty());
			rawDomains.put(role.getKey(), all);
			rawDomainSizes.put(role.getKey(), all.size());
			List<CandidateSelectionReceipt> physical = all.stream()
				.filter(receipt -> literalPhysicalDomainContains(role.getKey(), receipt)).toList();
			Assert.assertFalse("finite physical support is empty for " + role.getKey(), physical.isEmpty());
			supportDomains.put(role.getKey(), physical);
			supportDomainSizes.put(role.getKey(), physical.size());
			List<CandidateSelectionReceipt> declared = physical.stream()
				.filter(receipt -> literalBindingDomainContains(role.getKey(), receipt, roles)).toList();
			Assert.assertFalse("declared receipt domain is empty for " + role.getKey(), declared.isEmpty());
			declaredDomains.put(role.getKey(), declared);
			Map<ReceiptDisposition,List<String>> classified = new LinkedHashMap<>();
			for(ReceiptDisposition disposition : ReceiptDisposition.values())
				classified.put(disposition, all.stream()
					.filter(receipt -> receiptDisposition(role.getKey(), receipt, roles) == disposition)
					.map(receipt -> receiptShapeIdentity(receipt, roles)).sorted().toList());
			receiptClassifications.put(role.getKey(), classified);
		}
		long rawCartesianSize = cartesianSize(rawDomainSizes);
		long supportCartesianSize = cartesianSize(supportDomainSizes);
		Assert.assertEquals("finite physical-support Cartesian size " + supportDomainSizes,
			1344L, supportCartesianSize);
		Assert.assertTrue("raw receipt universe must expose explicitly classified out-of-support assignments; raw="
			+ rawDomainSizes + ", support=" + supportDomainSizes,
			rawCartesianSize > supportCartesianSize);
		Assert.assertEquals("every raw receipt must have exactly one disposition: " + receiptClassifications,
			rawDomains.values().stream().mapToLong(List::size).sum(), receiptClassifications.values().stream()
				.flatMap(classified -> classified.values().stream()).mapToLong(List::size).sum());
		Assert.assertTrue("fixture must expose physical or action/binding out-of-domain receipts: "
			+ receiptClassifications, receiptClassifications.values().stream().anyMatch(classified ->
				classified.entrySet().stream().anyMatch(entry -> entry.getKey() != ReceiptDisposition.DECLARED
					&& !entry.getValue().isEmpty())));
		Map<String,Integer> distinctDeclaredIdentities = declaredDomains.entrySet().stream().collect(
			java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> (int)entry.getValue().stream()
				.map(receipt -> receiptShapeIdentity(receipt, roles)).distinct().count()));
		Assert.assertEquals("literal role domains must retain each distinct receipt relation identity",
			Map.of("X", 2, "Y", 2, "U", 2, "V", 2, "D", 2), distinctDeclaredIdentities);
		Assert.assertTrue("declared finite contract must be action-free DIRECT support",
			declaredDomains.values().stream().flatMap(List::stream)
				.flatMap(receipt -> receipt.supportClause().inputBindings().stream())
				.allMatch(binding -> "DIRECT".equals(binding.kind().name())
					&& binding.relocationAction() == null));

		DecodeAudit audit = decodeFullSupport(roles, supportDomains);
		Assert.assertEquals("every finite-support assignment must have exactly one disposition: "
			+ audit.assignmentCounts(), supportCartesianSize, audit.classifiedAssignmentCount());
		Assert.assertEquals("raw assignments outside finite support must be classified arithmetically",
			rawCartesianSize - supportCartesianSize, audit.outsidePhysicalSupportAssignments(rawCartesianSize));
		Set<String> expected = expectedPlans();
		assertSamePlans("literal expected versus independently legal production relation",
			expected, audit.legalPlans());
		assertSamePlans("legal versus unfiltered decoded finite-support relation",
			audit.legalPlans(), audit.decodedPlans());
		Assert.assertEquals("literal contract must retain all eight correlated plans", 8,
			audit.decodedPlans().size());
		Assert.assertTrue("fixture must exercise a two-input AND clause", declaredDomains.get("D").stream()
			.anyMatch(receipt -> bindingPositions(receipt).equals(Set.of(0, 1))));
		Assert.assertTrue("D must retain the exact selected U and V occurrences", declaredDomains.get("D").stream()
			.anyMatch(receipt -> hasSources(receipt, roles.get("U"), roles.get("V"))));
	}

	private static long cartesianSize(Map<String,Integer> domainSizes) {
		long result = 1;
		for(int domainSize : domainSizes.values())
			result = Math.multiplyExact(result, domainSize);
		return result;
	}

	private static ReceiptDisposition receiptDisposition(String role,
		CandidateSelectionReceipt receipt, Map<String,CompiledHopKey> roles) {
		if(!literalPhysicalDomainContains(role, receipt))
			return ReceiptDisposition.OUTSIDE_PHYSICAL_SUPPORT;
		if(receipt.supportClause().inputBindings().stream().anyMatch(binding ->
			binding.relocationAction() != null || !("DIRECT".equals(binding.kind().name())
				|| "LOGICAL_TRANSIENT".equals(binding.kind().name()))))
			return ReceiptDisposition.OUTSIDE_ACTION_SUPPORT;
		return literalBindingDomainContains(role, receipt, roles)
			? ReceiptDisposition.DECLARED : ReceiptDisposition.OUTSIDE_BINDING_SUPPORT;
	}

	private static DMLProgram compileFixture() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		program.getNamedNSFunctionStatementBlocks().values().forEach(function ->
			function.setRecompileOnce(true));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}

	private static Map<String,CompiledHopKey> fixtureRoles(PlacementAnalysis analysis,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw) {
		Set<CompiledHopKey> compiledOwners = raw.keySet().stream()
			.filter(owner -> "compiled".equals(owner.recompileContext())).collect(
				java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		CompiledHopKey u = uniqueScalarMultiply(analysis, compiledOwners, 2);
		CompiledHopKey v = uniqueScalarMultiply(analysis, compiledOwners, 3);
		CompiledHopKey d = compiledOwners.stream()
			.filter(owner -> isMatrixMultiply(analysis, owner))
			.filter(owner -> hasInputs(analysis, owner, u, v))
			.findFirst().orElseThrow();
		CompiledHopKey xInput = analysis.compiledInputEdge(u, 0).orElseThrow().producer();
		CompiledHopKey yInput = analysis.compiledInputEdge(v, 0).orElseThrow().producer();
		Map<String,CompiledHopKey> roles = new LinkedHashMap<>();
		roles.put("X", xInput);
		roles.put("Y", yInput);
		roles.put("U", u);
		roles.put("V", v);
		roles.put("D", d);
		return roles;
	}

	private static CompiledHopKey uniqueScalarMultiply(PlacementAnalysis analysis,
		Set<CompiledHopKey> nativeOwners, long literal) {
		List<CompiledHopKey> matches = nativeOwners.stream()
			.filter(owner -> isMatrixMultiply(analysis, owner))
			.filter(owner -> analysis.hop(owner).orElseThrow().getInput().stream().anyMatch(input ->
				input instanceof LiteralOp value && value.getLongValue() == literal))
			.toList();
		Assert.assertEquals("one compiled multiply by " + literal, 1, matches.size());
		return matches.get(0);
	}

	private static boolean isMatrixMultiply(PlacementAnalysis analysis, CompiledHopKey owner) {
		return analysis.hop(owner).orElse(null) instanceof BinaryOp binary && binary.getOp() == OpOp2.MULT
			&& binary.getDataType().isMatrix();
	}

	private static boolean hasInputs(PlacementAnalysis analysis, CompiledHopKey consumer,
		CompiledHopKey first, CompiledHopKey second) {
		return analysis.compiledInputEdge(consumer, 0).map(edge -> edge.producer().equals(first)).orElse(false)
			&& analysis.compiledInputEdge(consumer, 1).map(edge -> edge.producer().equals(second)).orElse(false);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> rawAvailableReceipts(
		PlacementAnalysis analysis) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = new LinkedHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(var emission : fact.allowedEmissionFacts())
				for(CandidateSelectionReceipt receipt : analysis.canonicalCandidateReceipts(fact.key(), emission))
					raw.computeIfAbsent(receipt.rule().parentOccurrence(), ignored -> new ArrayList<>()).add(receipt);
		}
		raw.replaceAll((ignored, receipts) -> List.copyOf(receipts));
		return raw;
	}

	private static Set<CompiledHopKey> bindingClosure(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw, Set<CompiledHopKey> seeds) {
		Set<CompiledHopKey> closure = new LinkedHashSet<>();
		closure.addAll(seeds);
		boolean changed;
		do {
			changed = false;
			for(var entry : raw.entrySet()) {
				CompiledHopKey owner = entry.getKey();
				if(!"compiled".equals(owner.recompileContext()))
					continue;
				for(CandidateSelectionReceipt receipt : entry.getValue())
					for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
						CompiledHopKey source = binding.source().rule().parentOccurrence();
						if(closure.contains(owner) && "compiled".equals(source.recompileContext())
							&& raw.containsKey(source) && closure.add(source))
							changed = true;
						if(closure.contains(source) && closure.add(owner))
							changed = true;
					}
			}
		}
		while(changed);
		return closure;
	}

	private static boolean literalPhysicalDomainContains(String role, CandidateSelectionReceipt receipt) {
		if(!receipt.emission().emissionState().placementState().equals(PROTECTED_ROW))
			return false;
		String layout = receipt.realization().key().layoutKind().name();
		String geometry = geometry(receipt);
		int arity = receipt.supportClause().inputBindings().size();
		return switch(role) {
			case "X" -> ("DURABLE_MAP".equals(layout) || "NATIVE_LINEAGE".equals(layout))
				&& A_GEOMETRY.equals(geometry) && arity == 0;
			case "Y" -> ("DURABLE_MAP".equals(layout) || "NATIVE_LINEAGE".equals(layout))
				&& B_GEOMETRY.equals(geometry) && arity == 0;
			case "U" -> "DURABLE_MAP".equals(layout) && A_GEOMETRY.equals(geometry) && arity == 1;
			case "V" -> "DURABLE_MAP".equals(layout) && B_GEOMETRY.equals(geometry) && arity == 1;
			case "D" -> "DURABLE_MAP".equals(layout)
				&& (A_GEOMETRY.equals(geometry) || B_GEOMETRY.equals(geometry)) && arity == 2;
			default -> throw new AssertionError("unknown literal fixture role " + role);
		};
	}

	private static boolean literalBindingDomainContains(String role, CandidateSelectionReceipt receipt,
		Map<String,CompiledHopKey> roles) {
		List<CandidateRealizationInputBinding> bindings = receipt.supportClause().inputBindings();
		return switch(role) {
			case "X", "Y" -> bindings.isEmpty();
			case "U" -> hasLiteralBinding(bindings, 0, roles.get("X"), Set.of("DURABLE_MAP", "NATIVE_LINEAGE"));
			case "V" -> hasLiteralBinding(bindings, 0, roles.get("Y"), Set.of("DURABLE_MAP", "NATIVE_LINEAGE"));
			case "D" -> bindings.size() == 2
				&& hasLiteralBinding(bindings, 0, roles.get("U"), Set.of("DURABLE_MAP"))
				&& hasLiteralBinding(bindings, 1, roles.get("V"), Set.of("DURABLE_MAP"));
			default -> throw new AssertionError("unknown literal fixture role " + role);
		};
	}

	private static boolean hasLiteralBinding(List<CandidateRealizationInputBinding> bindings,
		int position, CompiledHopKey source, Set<String> sourceLayouts) {
		return bindings.stream().anyMatch(binding -> binding.inputPosition() == position
			&& binding.source().rule().parentOccurrence().equals(source)
			&& sourceLayouts.contains(binding.source().realization().layoutKind().name())
			&& "DIRECT".equals(binding.kind().name()) && binding.relocationAction() == null);
	}

	private static Set<String> expectedPlans() {
		String state = state(PROTECTED_ROW);
		Set<String> expected = new LinkedHashSet<>();
		for(String xLayout : List.of("DURABLE_MAP", "NATIVE_LINEAGE"))
			for(String yLayout : List.of("DURABLE_MAP", "NATIVE_LINEAGE"))
				for(String dGeometry : List.of(A_GEOMETRY, B_GEOMETRY)) {
					String x = "X|state=" + state + "|layout=" + xLayout + "|geometry=" + A_GEOMETRY
						+ "|bindings=[]";
					String y = "Y|state=" + state + "|layout=" + yLayout + "|geometry=" + B_GEOMETRY
						+ "|bindings=[]";
					String u = "U|state=" + state + "|layout=DURABLE_MAP|geometry=" + A_GEOMETRY
						+ "|bindings=[0:DIRECT:X@" + state + ':' + xLayout + ':' + A_GEOMETRY + ":action=-]";
					String v = "V|state=" + state + "|layout=DURABLE_MAP|geometry=" + B_GEOMETRY
						+ "|bindings=[0:DIRECT:Y@" + state + ':' + yLayout + ':' + B_GEOMETRY + ":action=-]";
					String d = "D|state=" + state + "|layout=DURABLE_MAP|geometry=" + dGeometry
						+ "|bindings=[0:DIRECT:U@" + state + ":DURABLE_MAP:" + A_GEOMETRY
						+ ":action=-,1:DIRECT:V@" + state + ":DURABLE_MAP:" + B_GEOMETRY + ":action=-]";
					expected.add(String.join("\n", x, y, u, v, d));
				}
		return Set.copyOf(expected);
	}

	private static DecodeAudit decodeFullSupport(Map<String,CompiledHopKey> roles,
		Map<String,List<CandidateSelectionReceipt>> domains) {
		Set<String> decodedPlans = new LinkedHashSet<>();
		Set<String> legalPlans = new LinkedHashSet<>();
		Map<AssignmentDisposition,Long> assignmentCounts = new LinkedHashMap<>();
		for(AssignmentDisposition disposition : AssignmentDisposition.values())
			assignmentCounts.put(disposition, 0L);
		enumerate(roles, domains, List.copyOf(domains.keySet()), 0, new LinkedHashMap<>(), selected -> {
			AssignmentDisposition disposition = assignmentDisposition(roles, selected);
			assignmentCounts.compute(disposition, (ignored, count) -> count + 1);
			if(disposition == AssignmentDisposition.LEGAL
				|| disposition == AssignmentDisposition.OUTSIDE_BINDING_SUPPORT) {
				String plan = roles.keySet().stream().map(role ->
					receiptIdentity(role, selected.get(roles.get(role)), roles, selected))
					.reduce((left, right) -> left + '\n' + right).orElseThrow();
				decodedPlans.add(plan);
				if(disposition == AssignmentDisposition.LEGAL)
					legalPlans.add(plan);
			}
		});
		return new DecodeAudit(Set.copyOf(legalPlans), Set.copyOf(decodedPlans),
			Map.copyOf(assignmentCounts), assignmentCounts.values().stream().mapToLong(Long::longValue).sum());
	}

	private static void enumerate(Map<String,CompiledHopKey> roles,
		Map<String,List<CandidateSelectionReceipt>> domains, List<String> orderedRoles, int position,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		java.util.function.Consumer<Map<CompiledHopKey,CandidateSelectionReceipt>> leaf) {
		if(position == orderedRoles.size()) {
			leaf.accept(selected);
			return;
		}
		String role = orderedRoles.get(position);
		CompiledHopKey owner = roles.get(role);
		for(CandidateSelectionReceipt receipt : domains.get(role)) {
			selected.put(owner, receipt);
			enumerate(roles, domains, orderedRoles, position + 1, selected, leaf);
			selected.remove(owner);
		}
	}

	private static AssignmentDisposition assignmentDisposition(Map<String,CompiledHopKey> roles,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		for(CandidateSelectionReceipt receipt : selected.values())
			for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
				CandidateSelectionReceipt source = selected.get(binding.source().rule().parentOccurrence());
				if(source == null || !binding.source().equals(
					CandidateRealizationReference.of(source.rule(), source.realization())))
					return AssignmentDisposition.SOURCE_INCONSISTENT;
				if(binding.relocationAction() != null || !("DIRECT".equals(binding.kind().name())
					|| "LOGICAL_TRANSIENT".equals(binding.kind().name())))
					return AssignmentDisposition.OUTSIDE_ACTION_SUPPORT;
			}
		for(var role : roles.entrySet())
			if(!literalBindingDomainContains(role.getKey(), selected.get(role.getValue()), roles))
				return AssignmentDisposition.OUTSIDE_BINDING_SUPPORT;
		return AssignmentDisposition.LEGAL;
	}

	private static String receiptShapeIdentity(CandidateSelectionReceipt receipt,
		Map<String,CompiledHopKey> roleKeys) {
		Map<CompiledHopKey,String> roles = new LinkedHashMap<>();
		roleKeys.forEach((name, key) -> roles.put(key, name));
		String bindings = receipt.supportClause().inputBindings().stream()
			.sorted(Comparator.comparingInt(CandidateRealizationInputBinding::inputPosition))
			.map(binding -> {
				String sourceRole = roles.get(binding.source().rule().parentOccurrence());
				String printableRole = sourceRole == null ? "OUTSIDE_ROLE" : sourceRole;
				return binding.inputPosition() + ":" + binding.kind().name() + ':' + printableRole
					+ '@' + state(binding.source().realization().emissionState().placementState()) + ':'
					+ binding.source().realization().layoutKind().name() + ':'
					+ literalSourceGeometry(printableRole) + ":action="
					+ (binding.relocationAction() == null ? "-" : "relocation");
			}).reduce((left, right) -> left + ',' + right).orElse("");
		return state(receipt.emission().emissionState().placementState()) + "|layout="
			+ receipt.realization().key().layoutKind().name() + "|geometry=" + geometry(receipt)
			+ "|bindings=[" + bindings + ']';
	}

	private static String literalSourceGeometry(String role) {
		return switch(role) {
			case "X", "U" -> A_GEOMETRY;
			case "Y", "V" -> B_GEOMETRY;
			default -> "unscoped";
		};
	}

	private static String receiptIdentity(String role, CandidateSelectionReceipt receipt,
		Map<String,CompiledHopKey> roleKeys, Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		Map<CompiledHopKey,String> roles = new LinkedHashMap<>();
		roleKeys.forEach((name, key) -> roles.put(key, name));
		String bindings = receipt.supportClause().inputBindings().stream()
			.sorted(Comparator.comparingInt(CandidateRealizationInputBinding::inputPosition))
			.map(binding -> {
				CompiledHopKey sourceKey = binding.source().rule().parentOccurrence();
				CandidateSelectionReceipt source = selected.get(sourceKey);
				return binding.inputPosition() + ":" + binding.kind().name() + ':' + roles.get(sourceKey)
					+ '@' + state(source.emission().emissionState().placementState()) + ':'
					+ source.realization().key().layoutKind().name() + ':' + geometry(source)
					+ ":action=" + (binding.relocationAction() == null ? "-" : "unexpected-relocation");
			}).reduce((left, right) -> left + ',' + right).orElse("");
		return role + "|state=" + state(receipt.emission().emissionState().placementState())
			+ "|layout=" + receipt.realization().key().layoutKind().name()
			+ "|geometry=" + geometry(receipt) + "|bindings=[" + bindings + ']';
	}

	private static Set<Integer> bindingPositions(CandidateSelectionReceipt receipt) {
		Set<Integer> result = new LinkedHashSet<>();
		receipt.supportClause().inputBindings().forEach(binding -> result.add(binding.inputPosition()));
		return Set.copyOf(result);
	}

	private static boolean hasSources(CandidateSelectionReceipt receipt,
		CompiledHopKey first, CompiledHopKey second) {
		List<CandidateRealizationInputBinding> bindings = receipt.supportClause().inputBindings();
		return bindings.size() == 2
			&& bindings.get(0).source().rule().parentOccurrence().equals(first)
			&& bindings.get(1).source().rule().parentOccurrence().equals(second);
	}

	private static String state(PlacementState state) {
		return state.execType().name() + '/' + state.output().name() + '/' + state.fType().name()
			+ "/shapeDependent=" + state.shapeDependent();
	}

	private static String geometry(CandidateSelectionReceipt receipt) {
		DurableAnchorKey anchor = receipt.realization().nativeWorkerPoolResidencyWitness(receipt.supportClause());
		return anchor == null ? "none" : anchor.fType().name() + ':' + anchor.partitions().stream()
			.map(ProductionDecodedPlanSpaceCompletenessTest::partition).sorted()
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static String partition(AnchorPartition partition) {
		return partition.workerId() + '|' + coordinates(partition.begin()) + ':' + coordinates(partition.end());
	}

	private static String coordinates(List<Long> coordinates) {
		return coordinates.stream().map(String::valueOf)
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static void assertSamePlans(String label, Set<String> expected, Set<String> actual) {
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(actual);
		Set<String> extra = new TreeSet<>(actual);
		extra.removeAll(expected);
		Assert.assertTrue(label + " differs"
			+ "\nmissing=" + missing + "\nextra=" + extra, missing.isEmpty() && extra.isEmpty());
	}

	private enum ReceiptDisposition {
		DECLARED,
		OUTSIDE_PHYSICAL_SUPPORT,
		OUTSIDE_ACTION_SUPPORT,
		OUTSIDE_BINDING_SUPPORT
	}

	private enum AssignmentDisposition {
		LEGAL,
		SOURCE_INCONSISTENT,
		OUTSIDE_ACTION_SUPPORT,
		OUTSIDE_BINDING_SUPPORT
	}

	private record DecodeAudit(Set<String> legalPlans, Set<String> decodedPlans,
		Map<AssignmentDisposition,Long> assignmentCounts, long classifiedAssignmentCount) {
		long outsidePhysicalSupportAssignments(long rawCartesianSize) {
			return rawCartesianSize - classifiedAssignmentCount;
		}
	}
}

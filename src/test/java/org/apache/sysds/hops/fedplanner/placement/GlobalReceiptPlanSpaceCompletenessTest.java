/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
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

/**
 * A bounded, generation-independent oracle for the global candidate relation.
 *
 * Placement domains are declared without consulting planner selection. The production-integrated
 * slice projects builder receipts into that domain; the synthetic mutation fixtures declare receipts
 * directly. The decoders visit every placement assignment, receipt choice, and action subset. A plan
 * is retained only when every AND input of the selected OR receipt agrees on the exact source receipt,
 * every physical action agrees on source, target, and geometry, and the selected relation is grounded
 * by a least fixed point. Neither planner reachability nor production signatures participate.
 */
public class GlobalReceiptPlanSpaceCompletenessTest {
	private static final PlacementState PROTECTED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final Geometry LOCAL = new Geometry("CP", "LOUT", "NONE", "local", "full");
	private static final Geometry CP_FOUT = new Geometry("CP", "FOUT", "ROW", "w1,w2", "r0-4;r4-8");
	private static final Geometry ROW_A = new Geometry("FED", "FOUT", "ROW", "w1,w2", "r0-4;r4-8");
	private static final Geometry ROW_B = new Geometry("FED", "FOUT", "ROW", "w1,w2", "r0-3;r3-8");
	private static final String PROTECTED_REPEATED_INPUT_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "U=A*2;V=A*3;C=U*V;print(sum(C));";

	@Test
	public void productionRepeatedInputRelationMatchesIndependentGlobalContract() throws Exception {
		DMLProgram program = compileProtected(PROTECTED_REPEATED_INPUT_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Map<String,CompiledHopKey> nodes = productionFixtureRoles(analysis);
		Map<String,List<CandidateSelectionReceipt>> receiptDomains = new LinkedHashMap<>();
		for(var entry : nodes.entrySet()) {
			List<CandidateSelectionReceipt> receipts = productionReceipts(
				analysis, entry.getValue(), entry.getKey());
			Assert.assertFalse("production domain must be non-empty for " + entry.getKey(), receipts.isEmpty());
			receiptDomains.put(entry.getKey(), receipts);
		}

		Set<String> expected = expectedRepeatedInputPlans();
		Set<String> actual = decodeProductionRelation(nodes, receiptDomains, List.of("A", "U", "V", "C"));
		assertSamePlans("production PRIVATE_AGGREGATE repeated-input relation", expected, actual);

		Assert.assertTrue("integrated fixture must exercise a two-input AND clause",
			receiptDomains.get("C").stream().anyMatch(receipt -> bindingPositions(receipt).equals(Set.of(0, 1))));
		Assert.assertTrue("both inputs must retain their exact source occurrence",
			receiptDomains.get("C").stream().anyMatch(receipt -> hasExactSources(
				receipt, nodes.get("U"), nodes.get("V"))));
		Assert.assertTrue("U must bind input position 0 to A",
			receiptDomains.get("U").stream().anyMatch(receipt ->
				hasExactSource(receipt, 0, nodes.get("A"))));
		Assert.assertTrue("V must bind input position 0 to A",
			receiptDomains.get("V").stream().anyMatch(receipt ->
				hasExactSource(receipt, 0, nodes.get("A"))));
		Assert.assertTrue("exact anchored A/U/V/C rows must use DURABLE_MAP",
			receiptDomains.values().stream().flatMap(List::stream).allMatch(receipt ->
				"DURABLE_MAP".equals(receipt.realization().key().layoutKind().name())));
		Assert.assertTrue("the integrated identity must retain exact worker/range geometry",
			actual.stream().allMatch(identity -> identity.contains("localhost:4234/A1|0,0:4,2")
				&& identity.contains("localhost:4235/A2|4,0:8,2")));
		Assert.assertEquals("literal contract contains one complete receipt relation", 1, actual.size());
	}

	@Test
	public void nativeHigherArityRepeatedProducerPreservesEveryExactGeometryPlan() {
		Fixture fixture = nativeFixture();
		DecodeResult decoded = decode(fixture);
		Set<String> expected = Set.of(
			plan(fixture, choices("A", "a-row-a", "B", "b-row-a", "C", "c-row-a")),
			plan(fixture, choices("A", "a-row-b", "B", "b-row-b", "C", "c-row-b")));

		assertSamePlans("native higher-arity/repeated-producer", expected, decoded.plans());
		Assert.assertEquals("the independent domain must enumerate every placement assignment", 8,
			decoded.assignmentCount());
		Assert.assertTrue("fixture must contain rejected same-endpoint/different-range assignments",
			decoded.receiptChoiceCount() > decoded.plans().size());
		Assert.assertEquals("fixture geometries intentionally share endpoints", ROW_A.workerPool(),
			ROW_B.workerPool());
		Assert.assertNotEquals("range identity must remain physically distinct", ROW_A.ranges(),
			ROW_B.ranges());
		Receipt consumer = fixture.receipt("c-row-a");
		Assert.assertEquals("native clause must be higher arity", 3, consumer.bindings().size());
		Assert.assertEquals("the producer must occur at two distinct input positions", "A",
			consumer.bindings().get(0).sourceOwner());
		Assert.assertEquals("the producer must occur at two distinct input positions", "A",
			consumer.bindings().get(1).sourceOwner());
	}

	@Test
	public void identitySccRequiresAGroundedSeed() {
		Fixture fixture = identityLoopFixture();
		DecodeResult decoded = decode(fixture);
		Set<String> expected = Set.of(plan(fixture,
			choices("Seed", "seed", "X", "x-from-seed", "Y", "y-from-x-seed")));

		assertSamePlans("grounded/ungrounded identity SCC", expected, decoded.plans());
		Assert.assertEquals("fixture has one placement assignment", 1, decoded.assignmentCount());
		Assert.assertEquals("fixture must expose both grounded and cyclic X alternatives", 2,
			fixture.receiptsFor("X", ROW_A).size());
		Assert.assertTrue("at least one complete receipt choice must be rejected as ungrounded",
			decoded.ungroundedChoiceCount() > 0);
		Assert.assertFalse("a self-supporting SCC must not become a proof",
			decoded.plans().contains(plan(fixture,
				choices("Seed", "seed", "X", "x-from-y", "Y", "y-from-x-loop"))));
	}

	@Test
	public void functionTransientAndLatePublicationRemainGloballyCoupled() {
		Fixture fixture = transientFunctionFixture();
		DecodeResult decoded = decode(fixture);
		Set<String> expected = Set.of(
			plan(fixture, choices("Actual", "actual-a", "Formal", "formal-a", "Tmp", "tmp-a",
				"Consumer", "consumer-a"), Set.of("publish-a")),
			plan(fixture, choices("Actual", "actual-b", "Formal", "formal-b", "Tmp", "tmp-b",
				"Consumer", "consumer-b"), Set.of("publish-b")));

		assertSamePlans("function/transient/late-publication", expected, decoded.plans());
		Assert.assertEquals("four binary domains must all be visited", 16, decoded.assignmentCount());
		Assert.assertTrue("action subsets must be enumerated rather than inferred from one row",
			decoded.actionSubsetCount() > decoded.receiptChoiceCount());
		Assert.assertTrue("fixture must publish after the dependent receipts are declared",
			fixture.actions().stream().allMatch(action -> action.declarationOrder() > 100));
		Assert.assertTrue("cross-publication or irrelevant-action choices must be rejected",
			decoded.actionRejectedChoiceCount() > 0);
	}

	@Test
	public void setComparisonReportsBothMissingAndExtraPlans() {
		Fixture fixture = nativeFixture();
		Set<String> expected = decode(fixture).plans();
		String removed = expected.iterator().next();
		Set<String> mutated = new LinkedHashSet<>(expected);
		mutated.remove(removed);
		mutated.add("synthetic-extra-plan");

		PlanDiff diff = difference(expected, mutated);
		Assert.assertEquals("the mutation sentinel must expose the removed legal plan", Set.of(removed),
			diff.missing());
		Assert.assertEquals("the mutation sentinel must expose the injected illegal plan",
			Set.of("synthetic-extra-plan"), diff.extra());
	}

	@Test
	public void deletingALegalOrAlternativeIsDetectedAsAMissingPlan() {
		Fixture baseline = nativeFixture();
		Set<String> expected = decode(baseline).plans();
		Fixture mutated = withReceipts(baseline, baseline.receipts().stream()
			.filter(receipt -> !"c-row-b".equals(receipt.id())).toList());

		PlanDiff diff = difference(expected, decode(mutated).plans());
		Assert.assertEquals("deleting one legal OR alternative must remove exactly its plan",
			Set.of(plan(baseline, choices("A", "a-row-b", "B", "b-row-b", "C", "c-row-b"))),
			diff.missing());
		Assert.assertTrue("deleting an OR alternative must not synthesize plans", diff.extra().isEmpty());
	}

	@Test
	public void mergingDistinctRangeMapsIsDetectedAsMissingAndExtraPlans() {
		Fixture baseline = nativeFixture();
		Fixture mutated = replaceGeometry(baseline, ROW_B, ROW_A);
		PlanDiff diff = difference(decode(baseline).plans(), decode(mutated).plans());

		Assert.assertFalse("collapsing distinct range maps must lose the exact ROW_B plan",
			diff.missing().isEmpty());
		Assert.assertFalse("collapsing distinct range maps must introduce a conflated ROW_A plan",
			diff.extra().isEmpty());
		Assert.assertTrue("the missing counterexample must retain ROW_B range identity",
			diff.missing().stream().allMatch(value -> value.contains(ROW_B.ranges())));
		Assert.assertTrue("the extra counterexample must expose collapsed ROW_A range identity",
			diff.extra().stream().allMatch(value -> value.contains(ROW_A.ranges())));
	}

	@Test
	public void removingAHigherArityIncomingDependencyIsDetected() {
		Fixture baseline = nativeFixture();
		List<Receipt> receipts = baseline.receipts().stream().map(receipt ->
			"c-row-a".equals(receipt.id())
				? new Receipt(receipt.id(), receipt.owner(), receipt.relationKind(), receipt.geometry(),
					receipt.bindings().stream().filter(binding -> binding.inputPosition() != 2).toList())
				: receipt).toList();
		PlanDiff diff = difference(decode(baseline).plans(), decode(withReceipts(baseline, receipts)).plans());

		Assert.assertFalse("removing an incoming conjunct must lose the complete original tuple",
			diff.missing().isEmpty());
		Assert.assertFalse("removing an incoming conjunct must admit an incomplete higher-arity tuple",
			diff.extra().isEmpty());
		Assert.assertTrue("the extra counterexample must show that input position 2 disappeared",
			diff.extra().stream().noneMatch(value -> value.contains("2:DIRECT:B=>b-row-a")));
	}

	@Test
	public void admittingASeedlessSccIsDetectedAsAnExtraPlan() {
		Fixture fixture = identityLoopFixture();
		Set<String> expected = decode(fixture).plans();
		Set<String> mutated = decode(fixture, new DecoderRules(false, true)).plans();
		PlanDiff diff = difference(expected, mutated);

		Assert.assertTrue("disabling grounding must not remove the seeded plan", diff.missing().isEmpty());
		Assert.assertEquals("disabling grounding must admit exactly the seedless SCC",
			Set.of(plan(fixture,
				choices("Seed", "seed", "X", "x-from-y", "Y", "y-from-x-loop"))), diff.extra());
	}

	@Test
	public void admittingProtectedRecompileCpFoutIsDetectedAsAnExtraPlan() {
		Fixture fixture = protectedRecompileFixture();
		DecodeResult baseline = decode(fixture);
		DecodeResult mutated = decode(fixture, new DecoderRules(true, false));
		PlanDiff diff = difference(baseline.plans(), mutated.plans());

		Assert.assertEquals("the independent domain must visit allowed and forbidden assignments", 2,
			baseline.assignmentCount());
		Assert.assertTrue("disabling the protected/recompile rule must not remove a legal plan",
			diff.missing().isEmpty());
		Assert.assertEquals("disabling the rule must admit exactly the forbidden CP/FOUT tuple",
			Set.of(plan(fixture, choices("Protected", "protected-cp-fout"))), diff.extra());
	}

	private static List<CandidateSelectionReceipt> productionReceipts(PlacementAnalysis analysis,
		CompiledHopKey owner, String role) {
		List<CandidateSelectionReceipt> result = new ArrayList<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFactsForParent(owner)) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			fact.allowedEmissionFacts().stream()
				.flatMap(emission -> analysis.canonicalCandidateReceipts(fact.key(), emission).stream())
				.filter(receipt -> literalDomainContains(role, receipt)).forEach(result::add);
		}
		return List.copyOf(result);
	}

	private static boolean literalDomainContains(String role, CandidateSelectionReceipt receipt) {
		if(!receipt.emission().emissionState().placementState().equals(PROTECTED_ROW)
			|| !"DURABLE_MAP".equals(receipt.realization().key().layoutKind().name())
			|| !expectedAGeometry().equals(exactGeometry(receipt)))
			return false;
		int expectedArity = switch(role) {
			case "A" -> 0;
			case "U", "V" -> 1;
			case "C" -> 2;
			default -> throw new AssertionError("unknown literal fixture role " + role);
		};
		return receipt.supportClause().inputBindings().size() == expectedArity;
	}

	private static Map<String,CompiledHopKey> productionFixtureRoles(PlacementAnalysis analysis) {
		CompiledHopKey a = uniqueNode(analysis, "A", "Fed A").key();
		CompiledHopKey u = uniqueScalarMultiply(analysis, 2);
		CompiledHopKey v = uniqueScalarMultiply(analysis, 3);
		CompiledHopKey c = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> isMatrixMultiply(occurrence))
			.filter(occurrence -> hasCompiledInputs(analysis, occurrence.key(), u, v))
			.map(HopOccurrenceProjection::key).findFirst().orElseThrow();
		Assert.assertSame("A must feed U", a, analysis.compiledInputEdge(u, 0).orElseThrow().producer());
		Assert.assertSame("A must feed V", a, analysis.compiledInputEdge(v, 0).orElseThrow().producer());
		Map<String,CompiledHopKey> roles = new LinkedHashMap<>();
		roles.put("A", a);
		roles.put("U", u);
		roles.put("V", v);
		roles.put("C", c);
		return roles;
	}

	private static CompiledHopKey uniqueScalarMultiply(PlacementAnalysis analysis, long literal) {
		List<CompiledHopKey> matches = analysis.compiledHopOccurrences().stream()
			.filter(GlobalReceiptPlanSpaceCompletenessTest::isMatrixMultiply)
			.filter(occurrence -> occurrence.hop().getInput().stream().anyMatch(input ->
				input instanceof LiteralOp value && value.getLongValue() == literal))
			.map(HopOccurrenceProjection::key).toList();
		Assert.assertEquals("fixture requires one matrix multiply by " + literal, 1, matches.size());
		return matches.get(0);
	}

	private static boolean isMatrixMultiply(HopOccurrenceProjection occurrence) {
		return occurrence.hop() instanceof BinaryOp binary && binary.getOp() == OpOp2.MULT
			&& binary.getDataType().isMatrix();
	}

	private static boolean hasCompiledInputs(PlacementAnalysis analysis, CompiledHopKey consumer,
		CompiledHopKey first, CompiledHopKey second) {
		return analysis.compiledInputEdge(consumer, 0).map(edge -> edge.producer() == first).orElse(false)
			&& analysis.compiledInputEdge(consumer, 1).map(edge -> edge.producer() == second).orElse(false);
	}

	private static Set<String> expectedRepeatedInputPlans() {
		String state = stateIdentity(PROTECTED_ROW);
		String geometry = expectedAGeometry();
		String a = "A|state=" + state + "|layout=DURABLE_MAP|geometry=" + geometry
			+ "|bindings=[]";
		String u = "U|state=" + state + "|layout=DURABLE_MAP|geometry=" + geometry
			+ "|bindings=[0:DIRECT:A@" + state + ":DURABLE_MAP:" + geometry + ":action=-]";
		String v = "V|state=" + state + "|layout=DURABLE_MAP|geometry=" + geometry
			+ "|bindings=[0:DIRECT:A@" + state + ":DURABLE_MAP:" + geometry + ":action=-]";
		String c = "C|state=" + state + "|layout=DURABLE_MAP|geometry=" + geometry
			+ "|bindings=[0:DIRECT:U@" + state + ":DURABLE_MAP:" + geometry
			+ ":action=-,1:DIRECT:V@" + state + ":DURABLE_MAP:" + geometry + ":action=-]";
		return Set.of(String.join("\n", a, u, v, c));
	}

	private static String expectedAGeometry() {
		return "ROW:localhost:4234/A1|0,0:4,2,localhost:4235/A2|4,0:8,2";
	}

	private static Set<String> decodeProductionRelation(Map<String,CompiledHopKey> nodes,
		Map<String,List<CandidateSelectionReceipt>> domains, List<String> projectedRoles) {
		Set<String> actual = new LinkedHashSet<>();
		enumerateProductionSelections(nodes, domains, List.copyOf(domains.keySet()), 0,
			new IdentityHashMap<>(), selected -> {
				if(globallyConsistentProductionSelection(selected))
					actual.add(productionPlanIdentity(nodes, projectedRoles, selected));
			});
		return Set.copyOf(actual);
	}

	private static void enumerateProductionSelections(Map<String,CompiledHopKey> nodes,
		Map<String,List<CandidateSelectionReceipt>> domains, List<String> roles, int position,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		java.util.function.Consumer<Map<CompiledHopKey,CandidateSelectionReceipt>> leaf) {
		if(position == roles.size()) {
			leaf.accept(selected);
			return;
		}
		String role = roles.get(position);
		CompiledHopKey owner = nodes.get(role);
		for(CandidateSelectionReceipt receipt : domains.get(role)) {
			selected.put(owner, receipt);
			enumerateProductionSelections(nodes, domains, roles, position + 1, selected, leaf);
			selected.remove(owner);
		}
	}

	private static boolean globallyConsistentProductionSelection(
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		for(CandidateSelectionReceipt receipt : selected.values())
			for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
				CandidateSelectionReceipt source = selected.get(binding.source().rule().parentOccurrence());
				if(source == null || !binding.source().equals(
					CandidateRealizationReference.of(source.rule(), source.realization())))
					return false;
				if(!"DIRECT".equals(binding.kind().name()) || binding.relocationAction() != null
					|| !sameFixtureWorkerPool(source, receipt))
					return false;
			}
		return true;
	}

	private static Set<Integer> bindingPositions(CandidateSelectionReceipt receipt) {
		Set<Integer> result = new LinkedHashSet<>();
		receipt.supportClause().inputBindings().forEach(binding -> result.add(binding.inputPosition()));
		return Set.copyOf(result);
	}

	private static boolean hasExactSources(CandidateSelectionReceipt receipt,
		CompiledHopKey first, CompiledHopKey second) {
		return receipt.supportClause().inputBindings().size() == 2
			&& hasExactSource(receipt, 0, first) && hasExactSource(receipt, 1, second);
	}

	private static boolean hasExactSource(CandidateSelectionReceipt receipt, int inputPosition,
		CompiledHopKey source) {
		return receipt.supportClause().inputBindings().stream().anyMatch(binding ->
			binding.inputPosition() == inputPosition
				&& binding.source().rule().parentOccurrence().equals(source));
	}

	private static boolean sameFixtureWorkerPool(CandidateSelectionReceipt source,
		CandidateSelectionReceipt consumer) {
		DurableAnchorKey left = source.realization().nativeWorkerPoolResidencyWitness(source.supportClause());
		DurableAnchorKey right = consumer.realization().nativeWorkerPoolResidencyWitness(consumer.supportClause());
		if(left == null || right == null || left.fType() != right.fType()
			|| left.partitions().size() != right.partitions().size())
			return false;
		List<String> leftPool = left.partitions().stream().map(GlobalReceiptPlanSpaceCompletenessTest::poolPartition)
			.sorted().toList();
		List<String> rightPool = right.partitions().stream().map(GlobalReceiptPlanSpaceCompletenessTest::poolPartition)
			.sorted().toList();
		return leftPool.equals(rightPool);
	}

	private static String poolPartition(AnchorPartition partition) {
		String worker = partition.workerId();
		int path = worker.indexOf('/', worker.indexOf("//") + 2);
		return (path < 0 ? worker : worker.substring(0, path)) + '|'
			+ coordinates(partition.begin()) + ':' + coordinates(partition.end());
	}

	private static String productionPlanIdentity(Map<String,CompiledHopKey> nodes,
		List<String> projectedRoles, Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		Map<CompiledHopKey,String> roles = new IdentityHashMap<>();
		nodes.forEach((role, node) -> roles.put(node, role));
		return projectedRoles.stream().map(role -> productionReceiptIdentity(role,
			selected.get(nodes.get(role)), roles, selected))
			.reduce((left, right) -> left + "\n" + right).orElse("");
	}

	private static String productionReceiptIdentity(String role, CandidateSelectionReceipt receipt,
		Map<CompiledHopKey,String> roles, Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		String bindings = receipt.supportClause().inputBindings().stream()
			.sorted(Comparator.comparingInt(CandidateRealizationInputBinding::inputPosition))
			.map(binding -> {
				CompiledHopKey sourceKey = binding.source().rule().parentOccurrence();
				CandidateSelectionReceipt source = selected.get(sourceKey);
				return binding.inputPosition() + ":" + binding.kind().name() + ":"
					+ roles.get(sourceKey) + "@" + stateIdentity(source.emission().emissionState().placementState())
					+ ':' + source.realization().key().layoutKind().name() + ':' + exactGeometry(source)
					+ ":action=" + (binding.relocationAction() == null ? "-" : "unexpected-relocation");
			})
			.reduce((left, right) -> left + ',' + right).orElse("");
		return role + "|state="
			+ stateIdentity(receipt.emission().emissionState().placementState()) + "|layout="
			+ receipt.realization().key().layoutKind().name() + "|geometry=" + exactGeometry(receipt)
			+ "|bindings=[" + bindings + ']';
	}

	private static String stateIdentity(PlacementState state) {
		return state.execType().name() + '/' + state.output().name() + '/' + state.fType().name()
			+ "/shapeDependent=" + state.shapeDependent();
	}

	private static String exactGeometry(CandidateSelectionReceipt receipt) {
		DurableAnchorKey anchor = receipt.provenWorkerPool();
		return anchor == null ? "none" : anchor.fType().name() + ':' + anchor.partitions().stream()
			.map(GlobalReceiptPlanSpaceCompletenessTest::partitionIdentity).sorted()
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static String partitionIdentity(AnchorPartition partition) {
		return partition.workerId() + '|' + coordinates(partition.begin()) + ':'
			+ coordinates(partition.end());
	}

	private static String coordinates(List<Long> coordinates) {
		return coordinates.stream().map(String::valueOf)
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name, String opcode) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one " + name + '/' + opcode, 1, matches.size());
		return matches.get(0);
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one occurrence named " + name, 1, matches.size());
		return matches.get(0);
	}

	private static DMLProgram compileProtected(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
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

	private static Fixture nativeFixture() {
		Map<String,List<Geometry>> domains = domains(
			"A", List.of(ROW_A, ROW_B), "B", List.of(ROW_A, ROW_B), "C", List.of(ROW_A, ROW_B));
		List<Receipt> receipts = List.of(
			seed("a-row-a", "A", ROW_A), seed("a-row-b", "A", ROW_B),
			seed("b-row-a", "B", ROW_A), seed("b-row-b", "B", ROW_B),
			nativeReceipt("c-row-a", "C", ROW_A,
				direct(0, "A", "a-row-a"), direct(1, "A", "a-row-a"),
				direct(2, "B", "b-row-a")),
			nativeReceipt("c-row-b", "C", ROW_B,
				direct(0, "A", "a-row-b"), direct(1, "A", "a-row-b"),
				direct(2, "B", "b-row-b")));
		return new Fixture(domains, receipts, List.of());
	}

	private static Fixture identityLoopFixture() {
		Map<String,List<Geometry>> domains = domains(
			"Seed", List.of(ROW_A), "X", List.of(ROW_A), "Y", List.of(ROW_A));
		List<Receipt> receipts = List.of(
			seed("seed", "Seed", ROW_A),
			receipt("x-from-seed", "X", ROW_A, identity(0, "Seed", "seed")),
			receipt("x-from-y", "X", ROW_A, identity(0, "Y", "y-from-x-loop")),
			receipt("y-from-x-seed", "Y", ROW_A, identity(0, "X", "x-from-seed")),
			receipt("y-from-x-loop", "Y", ROW_A, identity(0, "X", "x-from-y")));
		return new Fixture(domains, receipts, List.of());
	}

	private static Fixture transientFunctionFixture() {
		Map<String,List<Geometry>> domains = domains(
			"Actual", List.of(ROW_A, ROW_B), "Formal", List.of(ROW_A, ROW_B),
			"Tmp", List.of(ROW_A, ROW_B), "Consumer", List.of(ROW_A, ROW_B));
		List<Receipt> receipts = List.of(
			seed("actual-a", "Actual", ROW_A), seed("actual-b", "Actual", ROW_B),
			receipt("formal-a", "Formal", ROW_A, function(0, "Actual", "actual-a")),
			receipt("formal-b", "Formal", ROW_B, function(0, "Actual", "actual-b")),
			receipt("tmp-a", "Tmp", ROW_A, transientInput(0, "Formal", "formal-a", "publish-a")),
			receipt("tmp-b", "Tmp", ROW_B, transientInput(0, "Formal", "formal-b", "publish-b")),
			receipt("consumer-a", "Consumer", ROW_A, direct(0, "Tmp", "tmp-a")),
			receipt("consumer-b", "Consumer", ROW_B, direct(0, "Tmp", "tmp-b")));
		List<Action> actions = List.of(
			new Action("publish-a", "formal-a", "Tmp", ROW_A, 101),
			new Action("publish-b", "formal-b", "Tmp", ROW_B, 102),
			new Action("irrelevant-local-copy", "actual-a", "Consumer", LOCAL, 103));
		return new Fixture(domains, receipts, actions);
	}

	private static Fixture protectedRecompileFixture() {
		return new Fixture(domains("Protected", List.of(ROW_A, CP_FOUT)), List.of(
			seed("protected-fed-fout", "Protected", ROW_A),
			seed("protected-cp-fout", "Protected", CP_FOUT)), List.of(), Set.of(CP_FOUT));
	}

	private static DecodeResult decode(Fixture fixture) {
		return decode(fixture, new DecoderRules(true, true));
	}

	private static DecodeResult decode(Fixture fixture, DecoderRules rules) {
		List<Map<String,Geometry>> assignments = enumerateAssignments(fixture.domains());
		Set<String> plans = new LinkedHashSet<>();
		Counter counter = new Counter();
		for(Map<String,Geometry> assignment : assignments) {
			if(rules.enforceForbiddenGeometry()
				&& assignment.values().stream().anyMatch(fixture.forbiddenGeometries()::contains))
				continue;
			List<String> owners = List.copyOf(fixture.domains().keySet());
			enumerateReceipts(fixture, assignment, owners, 0, new LinkedHashMap<>(), selected -> {
				counter.receiptChoices++;
				enumerateActionSubsets(fixture.actions(), 0, new LinkedHashSet<>(), selectedActions -> {
					counter.actionSubsets++;
					if(!actionsAreExactAndConsistent(fixture, selected, selectedActions)) {
						counter.actionRejected++;
						return;
					}
					if(rules.enforceGrounding() && !isGrounded(fixture, selected)) {
						counter.ungrounded++;
						return;
					}
					plans.add(plan(fixture, selected, selectedActions));
				});
			});
		}
		return new DecodeResult(Set.copyOf(plans), assignments.size(), counter.receiptChoices,
			counter.actionSubsets, counter.ungrounded, counter.actionRejected);
	}

	private static Fixture withReceipts(Fixture fixture, List<Receipt> receipts) {
		return new Fixture(fixture.domains(), receipts, fixture.actions(), fixture.forbiddenGeometries());
	}

	private static Fixture replaceGeometry(Fixture fixture, Geometry from, Geometry to) {
		Map<String,List<Geometry>> domains = new LinkedHashMap<>();
		fixture.domains().forEach((owner, values) -> domains.put(owner,
			values.stream().map(value -> value.equals(from) ? to : value).toList()));
		List<Receipt> receipts = fixture.receipts().stream().map(receipt -> new Receipt(
			receipt.id(), receipt.owner(), receipt.relationKind(),
			receipt.geometry().equals(from) ? to : receipt.geometry(), receipt.bindings())).toList();
		List<Action> actions = fixture.actions().stream().map(action -> new Action(
			action.id(), action.sourceReceipt(), action.targetOwner(),
			action.targetGeometry().equals(from) ? to : action.targetGeometry(),
			action.declarationOrder())).toList();
		Set<Geometry> forbidden = fixture.forbiddenGeometries().stream()
			.map(value -> value.equals(from) ? to : value)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return new Fixture(domains, receipts, actions, forbidden);
	}

	private static boolean actionsAreExactAndConsistent(Fixture fixture,
		Map<String,String> selected, Set<String> selectedActions) {
		Set<String> required = new LinkedHashSet<>();
		for(String receiptId : selected.values()) {
			Receipt receipt = fixture.receipt(receiptId);
			for(Binding binding : receipt.bindings()) {
				if(!binding.sourceReceipt().equals(selected.get(binding.sourceOwner())))
					return false;
				if(binding.actionId() == null)
					continue;
				required.add(binding.actionId());
				Action action = fixture.action(binding.actionId());
				if(action == null || !action.sourceReceipt().equals(binding.sourceReceipt())
					|| !action.targetOwner().equals(receipt.owner())
					|| !action.targetGeometry().equals(receipt.geometry()))
					return false;
			}
		}
		return required.equals(selectedActions);
	}

	private static boolean isGrounded(Fixture fixture, Map<String,String> selected) {
		Map<String,Receipt> byId = new LinkedHashMap<>();
		selected.values().forEach(id -> byId.put(id, fixture.receipt(id)));
		Set<String> grounded = new LinkedHashSet<>();
		byId.values().stream().filter(receipt -> receipt.bindings().isEmpty())
			.map(Receipt::id).forEach(grounded::add);
		boolean changed;
		do {
			changed = false;
			for(Receipt receipt : byId.values())
				if(receipt.bindings().stream().allMatch(binding -> grounded.contains(binding.sourceReceipt())))
					changed |= grounded.add(receipt.id());
		}
		while(changed);
		return grounded.containsAll(byId.keySet());
	}

	private static List<Map<String,Geometry>> enumerateAssignments(Map<String,List<Geometry>> domains) {
		List<Map<String,Geometry>> result = new ArrayList<>();
		List<String> owners = List.copyOf(domains.keySet());
		enumerateAssignments(domains, owners, 0, new LinkedHashMap<>(), result);
		return result;
	}

	private static void enumerateAssignments(Map<String,List<Geometry>> domains, List<String> owners,
		int position, Map<String,Geometry> selected, List<Map<String,Geometry>> result) {
		if(position == owners.size()) {
			result.add(Map.copyOf(selected));
			return;
		}
		String owner = owners.get(position);
		for(Geometry geometry : domains.get(owner)) {
			selected.put(owner, geometry);
			enumerateAssignments(domains, owners, position + 1, selected, result);
		}
		selected.remove(owner);
	}

	private static void enumerateReceipts(Fixture fixture, Map<String,Geometry> assignment,
		List<String> owners, int position, Map<String,String> selected,
		java.util.function.Consumer<Map<String,String>> leaf) {
		if(position == owners.size()) {
			leaf.accept(Map.copyOf(selected));
			return;
		}
		String owner = owners.get(position);
		for(Receipt receipt : fixture.receiptsFor(owner, assignment.get(owner))) {
			selected.put(owner, receipt.id());
			enumerateReceipts(fixture, assignment, owners, position + 1, selected, leaf);
		}
		selected.remove(owner);
	}

	private static void enumerateActionSubsets(List<Action> actions, int position, Set<String> selected,
		java.util.function.Consumer<Set<String>> leaf) {
		if(position == actions.size()) {
			leaf.accept(Set.copyOf(selected));
			return;
		}
		enumerateActionSubsets(actions, position + 1, selected, leaf);
		selected.add(actions.get(position).id());
		enumerateActionSubsets(actions, position + 1, selected, leaf);
		selected.remove(actions.get(position).id());
	}

	private static void assertSamePlans(String fixture, Set<String> expected, Set<String> actual) {
		PlanDiff diff = difference(expected, actual);
		Assert.assertFalse(fixture + " oracle must be non-vacuous", expected.isEmpty());
		Assert.assertTrue(fixture + " missing plans:\n" + String.join("\n---\n", diff.missing())
			+ "\nextra plans:\n" + String.join("\n---\n", diff.extra()), diff.missing().isEmpty()
				&& diff.extra().isEmpty());
	}

	private static PlanDiff difference(Set<String> expected, Set<String> actual) {
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(actual);
		Set<String> extra = new TreeSet<>(actual);
		extra.removeAll(expected);
		return new PlanDiff(Set.copyOf(missing), Set.copyOf(extra));
	}

	private static String plan(Fixture fixture, Map<String,String> selected) {
		return plan(fixture, selected, Set.of());
	}

	private static String plan(Fixture fixture, Map<String,String> selected, Set<String> actions) {
		List<String> rows = selected.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> fixture.receipt(entry.getValue()).identity()).toList();
		List<String> actionRows = actions.stream().sorted().map(id -> fixture.action(id).identity()).toList();
		return "receipts=[" + String.join(";", rows) + "]|actions=["
			+ String.join(";", actionRows) + "]";
	}

	private static Map<String,String> choices(String... pairs) {
		Map<String,String> result = new LinkedHashMap<>();
		for(int i = 0; i < pairs.length; i += 2)
			result.put(pairs[i], pairs[i + 1]);
		return Map.copyOf(result);
	}

	private static Map<String,List<Geometry>> domains(Object... pairs) {
		Map<String,List<Geometry>> result = new LinkedHashMap<>();
		for(int i = 0; i < pairs.length; i += 2) {
			@SuppressWarnings("unchecked")
			List<Geometry> values = (List<Geometry>)pairs[i + 1];
			result.put((String)pairs[i], List.copyOf(values));
		}
		return result;
	}

	private static Receipt seed(String id, String owner, Geometry geometry) {
		return new Receipt(id, owner, "SOURCE", geometry, List.of());
	}

	private static Receipt receipt(String id, String owner, Geometry geometry, Binding... bindings) {
		return new Receipt(id, owner, "RELATION", geometry, List.of(bindings));
	}

	private static Receipt nativeReceipt(String id, String owner, Geometry geometry,
		Binding... bindings) {
		return new Receipt(id, owner, "NATIVE_LINEAGE", geometry, List.of(bindings));
	}

	private static Binding direct(int position, String owner, String receipt) {
		return new Binding(position, "DIRECT", owner, receipt, null);
	}

	private static Binding identity(int position, String owner, String receipt) {
		return new Binding(position, "IDENTITY", owner, receipt, null);
	}

	private static Binding function(int position, String owner, String receipt) {
		return new Binding(position, "FUNCTION", owner, receipt, null);
	}

	private static Binding transientInput(int position, String owner, String receipt, String action) {
		return new Binding(position, "LOGICAL_TRANSIENT", owner, receipt, action);
	}

	private record Geometry(String exec, String output, String fType, String workerPool, String ranges) {
		String identity() {
			return exec + '/' + output + '/' + fType + "/pool=" + workerPool + "/ranges=" + ranges;
		}
	}

	private record Binding(int inputPosition, String kind, String sourceOwner, String sourceReceipt,
		String actionId) {
		String identity() {
			return inputPosition + ":" + kind + ":" + sourceOwner + "=>" + sourceReceipt
				+ (actionId == null ? "" : "@" + actionId);
		}
	}

	private record Receipt(String id, String owner, String relationKind, Geometry geometry,
		List<Binding> bindings) {
		String identity() {
			return owner + "#" + id + ':' + relationKind + "@" + geometry.identity() + "<"
				+ bindings.stream().sorted(Comparator.comparingInt(Binding::inputPosition))
					.map(Binding::identity).reduce((left, right) -> left + "," + right).orElse("")
				+ ">";
		}
	}

	private record Action(String id, String sourceReceipt, String targetOwner,
		Geometry targetGeometry, int declarationOrder) {
		String identity() {
			return id + ":" + sourceReceipt + "=>" + targetOwner + '@' + targetGeometry.identity();
		}
	}

	private record Fixture(Map<String,List<Geometry>> domains, List<Receipt> receipts,
		List<Action> actions, Set<Geometry> forbiddenGeometries) {
		Fixture(Map<String,List<Geometry>> domains, List<Receipt> receipts, List<Action> actions) {
			this(domains, receipts, actions, Set.of());
		}

		Fixture {
			domains = copyDomains(domains);
			receipts = List.copyOf(receipts);
			actions = List.copyOf(actions);
			forbiddenGeometries = Set.copyOf(forbiddenGeometries);
		}

		Receipt receipt(String id) {
			return receipts.stream().filter(receipt -> receipt.id().equals(id)).findFirst()
				.orElseThrow(() -> new AssertionError("unknown receipt " + id));
		}

		Action action(String id) {
			return actions.stream().filter(action -> action.id().equals(id)).findFirst().orElse(null);
		}

		List<Receipt> receiptsFor(String owner, Geometry geometry) {
			return receipts.stream().filter(receipt -> receipt.owner().equals(owner)
				&& receipt.geometry().equals(geometry)).toList();
		}

		private static Map<String,List<Geometry>> copyDomains(Map<String,List<Geometry>> source) {
			Map<String,List<Geometry>> result = new LinkedHashMap<>();
			source.forEach((owner, values) -> result.put(owner, List.copyOf(values)));
			return java.util.Collections.unmodifiableMap(result);
		}
	}

	private record DecodeResult(Set<String> plans, int assignmentCount, int receiptChoiceCount,
		int actionSubsetCount, int ungroundedChoiceCount, int actionRejectedChoiceCount) { }

	private record PlanDiff(Set<String> missing, Set<String> extra) { }

	private record DecoderRules(boolean enforceGrounding, boolean enforceForbiddenGeometry) { }

	private static final class Counter {
		private int receiptChoices;
		private int actionSubsets;
		private int ungrounded;
		private int actionRejected;
	}
}

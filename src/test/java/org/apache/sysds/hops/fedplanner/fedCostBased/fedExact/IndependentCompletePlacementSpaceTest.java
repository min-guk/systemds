/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Bounded complete-space comparison for one protected loop/branch/function fixture.
 *
 * <p>The expected relation is generated from literal fixture placements, receipt layouts, and
 * action-presence choices. The production relation is independently obtained by evaluating every
 * raw assignment of the actual {@link ExactPhysicalModel} hard factors and passing every admitted
 * row through {@link ExactPhysicalSelection#create}. Graph action availability is never treated as
 * action selection. This is a bounded fixture proof, not universal model-domain completeness.</p>
 */
public class IndependentCompletePlacementSpaceTest {
	private static final PlacementState REMOTE_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final String ROW_GEOMETRY =
		"ROW:localhost:5334/A1|0,0:4,1,localhost:5335/A2|4,0:8,1";
	private static final String NATIVE_ROW_GEOMETRY =
		"ROW:localhost:5334|0,0:4,1,localhost:5335|4,0:8,1";
	private static final List<String> ROLES = List.of(
		"branch-if-operation", "branch-if-read", "branch-else-operation",
		"branch-else-read", "function-operation", "joined-read");
	// A column vector keeps literal and native output geometry equal without shape rewrites
	// that would change the branch/loop topology of this bounded fixture.
	private static final String SCRIPT =
		"f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}"
			+ "A=federated(addresses=list(\"localhost:5334/A1\",\"localhost:5335/A2\"),"
			+ "ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));"
			+ "D=A;i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);E=C+1;";

	@Test
	public void privateExactHardModelMatchesIndependentCompleteUniverse() throws Exception {
		assertComplete(Privacy.PRIVATE);
	}

	@Test
	public void privateAggregateExactHardModelMatchesIndependentCompleteUniverse() throws Exception {
		assertComplete(Privacy.PRIVATE_AGGREGATE);
	}

	@Test
	public void deletingOneAdmittedModelRowFailsCompleteness() throws Exception {
		Fixture fixture = fixture(Privacy.PRIVATE_AGGREGATE);
		ProductionSpace production = productionSpace(fixture);
		Set<String> expected = expectedSpace(Privacy.PRIVATE_AGGREGATE);
		Assert.assertEquals(expected, production.signatures());

		List<AdmittedWitness> remaining = new ArrayList<>(production.witnesses());
		AdmittedWitness removed = remaining.remove(0);
		Set<String> actual = signatures(remaining);
		Assert.assertEquals("deleting an admitted exact row must remove its complete witness",
			Set.of(removed.signature()), difference(expected, actual));
		Assert.assertThrows(AssertionError.class, () -> assertSameSpace(expected, actual));
	}

	@Test
	public void injectingActualRejectedModelRowFailsClosedBeforeProjection() throws Exception {
		Fixture fixture = fixture(Privacy.PRIVATE_AGGREGATE);
		ExactPhysicalRawSpaceExporter.Row[] rejectedRows = new ExactPhysicalRawSpaceExporter.Row[1];
		ExactPhysicalRawSpaceExporter.visit(fixture.model(), BigInteger.ZERO,
			ExactPhysicalRawSpaceExporter.size(fixture.model()), row -> {
				if(rejectedRows[0] == null && row.status() == ExactPhysicalRawSpaceExporter.Status.REJECTED)
					rejectedRows[0] = row;
			});
		ExactPhysicalRawSpaceExporter.Row rejected = rejectedRows[0];
		Assert.assertNotNull("fixture must contain a rejected raw assignment", rejected);

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> validatedSelection(fixture.model(), rejected));
		Assert.assertTrue("an injected hard-rejected raw row must also fail production selection validation: "
			+ failure.getMessage(), failure.getMessage().contains("violates exact transient realization support")
				|| failure.getMessage().contains("foreign, inactive, or unreachable"));
	}

	@Test
	public void valueMapProjectionRejectsMissingOrDifferentSelectedInput() throws Exception {
		Fixture fixture = fixture(Privacy.PRIVATE_AGGREGATE);
		CompiledHopKey operation = fixture.domains().get("branch-if-operation").node().key();
		CompiledHopKey read = fixture.domains().get("branch-if-read").node().key();
		CompiledHopKey initialA = fixture.valueInputs().get(read).iterator().next();
		CandidateSelectionReceipt output = candidate(fixture, operation, PlacementLayoutKind.VALUE_MAP);
		CandidateSelectionReceipt input = candidate(fixture, read, PlacementLayoutKind.VALUE_MAP);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(read, input);
		selected.put(initialA, candidate(fixture, initialA, PlacementLayoutKind.DURABLE_MAP));
		Assert.assertEquals(Set.of(ROW_GEOMETRY),
			selectedGeometries(fixture, output, selected, new LinkedHashSet<>()));

		selected.remove(read);
		Assert.assertThrows(AssertionError.class,
			() -> selectedGeometries(fixture, output, selected, new LinkedHashSet<>()));
		// Same owner and geometry still cannot replace the receipt actually required by the clause.
		selected.put(read, candidate(fixture, read, PlacementLayoutKind.DURABLE_MAP));
		Assert.assertThrows(AssertionError.class,
			() -> selectedGeometries(fixture, output, selected, new LinkedHashSet<>()));
		selected.put(read, input);
		Assert.assertEquals(Set.of(ROW_GEOMETRY),
			selectedGeometries(fixture, output, selected, new LinkedHashSet<>()));
	}

	@Test
	public void valueMapJoinProjectionRequiresEveryEntryAndBranchReceipt() throws Exception {
		Fixture fixture = fixture(Privacy.PRIVATE_AGGREGATE);
		CandidateSelectionReceipt joined = fixture.domains().get("joined-read").alternatives().stream()
			.filter(alternative -> alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP
				&& alternative.supportClause().requiredInputSupport().stream()
					.filter(input -> input.realization().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
					.count() == 2)
			.map(IndependentCompletePlacementSpaceTest::receipt).findFirst().orElseThrow();
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateRealizationReference input : joined.supportClause().requiredInputSupport()) {
			ExactPhysicalModel.Alternative alternative = fixture.model().domains().stream()
				.filter(domain -> domain.node().key() == input.rule().parentOccurrence())
				.flatMap(domain -> domain.alternatives().stream())
				.filter(value -> input.equals(CandidateRealizationReference.of(
					value.candidateRule().key(), value.realization())))
				.findFirst().orElseThrow();
			selected.put(input.rule().parentOccurrence(), receipt(alternative));
		}
		// This selected clause uses native branch outputs, so its three inputs are concrete leaves.
		Assert.assertEquals(Set.of(ROW_GEOMETRY, NATIVE_ROW_GEOMETRY),
			selectedGeometries(fixture, joined, selected, new LinkedHashSet<>()));
		for(CompiledHopKey owner : List.copyOf(selected.keySet())) {
			CandidateSelectionReceipt removed = selected.remove(owner);
			Assert.assertThrows("a duplicate map does not make its entry/branch receipt optional",
				AssertionError.class,
				() -> selectedGeometries(fixture, joined, selected, new LinkedHashSet<>()));
			selected.put(owner, removed);

			// Also delete the binding itself: a map union alone would miss a same-map branch loss.
			CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
				joined.supportClause().proofDependencies(), joined.supportClause().inputBindings().stream()
					.filter(binding -> binding.source().rule().parentOccurrence() != owner).toList());
			CandidateEmissionRealization realization = new CandidateEmissionRealization(
				joined.realization().key(), List.of(clause));
			CandidateEmissionFact emission = new CandidateEmissionFact(joined.emission().emissionState(),
				joined.emission().executionFType(), joined.emission().derivedFoutAction(), List.of(realization));
			CandidateSelectionReceipt incomplete = new CandidateSelectionReceipt(joined.rule(),
				emission, realization, realization.supportClauses().get(0), List.of());
			Assert.assertThrows("an omitted input binding must not disappear into a geometry union",
				AssertionError.class,
				() -> selectedGeometries(fixture, incomplete, selected, new LinkedHashSet<>()));
		}
	}

	private static CandidateSelectionReceipt candidate(Fixture fixture, CompiledHopKey owner,
		PlacementLayoutKind layout) {
		return fixture.model().domains().stream().filter(domain -> domain.node().key() == owner)
			.flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.realization().key().layoutKind() == layout)
			.map(IndependentCompletePlacementSpaceTest::receipt).findFirst().orElseThrow();
	}

	private static CandidateSelectionReceipt receipt(ExactPhysicalModel.Alternative alternative) {
		return new CandidateSelectionReceipt(alternative.candidateRule().key(),
			alternative.candidateEmission(), alternative.realization(), alternative.supportClause(), List.of());
	}

	private static void assertComplete(Privacy privacy) throws Exception {
		Fixture fixture = fixture(privacy);
		ProductionSpace production = productionSpace(fixture);
		Set<String> expected = expectedSpace(privacy);

		// Each branch has four input/operation routes. The join has one native map,
		// two input-derived output routes, and a durable route for both durable branches.
		Assert.assertEquals("literal universe must preserve each compatible receipt route",
			4 * 4 * (1 + 2) + 2 * 2, expected.size());
		Assert.assertEquals("every admitted raw row must remain separately identifiable",
			production.witnesses().size(), production.signatures().size());
		Assert.assertEquals("raw ordinals must identify every admitted production row",
			production.witnesses().size(), production.witnesses().stream()
				.map(AdmittedWitness::rawOrdinal).distinct().count());
		Assert.assertEquals("bounded alternative tuples must identify every admitted production row",
			production.witnesses().size(), production.witnesses().stream().map(witness ->
				ROLES.stream().map(role -> witness.roles().get(role).alternativeIndex()).toList())
				.distinct().count());
		Assert.assertEquals("exact hard factors must admit precisely the independent witness universe",
			expected.size(), production.witnesses().size());
		Assert.assertEquals("every other raw assignment must be rejected",
			ExactPhysicalRawSpaceExporter.size(fixture.model()).intValueExact() - expected.size(),
			production.rejectedRows());
		Assert.assertEquals("hard-factor callback errors/non-boolean costs must fail closed", 0,
			production.unknownRows());
		assertSameSpace(expected, production.signatures());
	}

	private static ProductionSpace productionSpace(Fixture fixture) {
		List<AdmittedWitness> witnesses = new ArrayList<>();
		int[] rejectedAndUnknown = new int[2];
		// Stream the unquotiented Cartesian product: VALUE_MAP adds support-clause choices.
		ExactPhysicalRawSpaceExporter.visit(fixture.model(), BigInteger.ZERO,
			ExactPhysicalRawSpaceExporter.size(fixture.model()), row -> {
				if(row.status() == ExactPhysicalRawSpaceExporter.Status.REJECTED) {
					rejectedAndUnknown[0]++;
					return;
				}
				if(row.status() == ExactPhysicalRawSpaceExporter.Status.UNKNOWN) {
					rejectedAndUnknown[1]++;
					Assert.fail("exact hard-model row classification is UNKNOWN|ordinal="
						+ row.ordinal() + "|reason=" + row.reason());
				}
				ExactPhysicalSelection selection = validatedSelection(fixture.model(), row);
				witnesses.add(project(fixture, row, selection));
			});
		Assert.assertEquals("every raw row must be classified exactly once",
			ExactPhysicalRawSpaceExporter.size(fixture.model()).intValueExact(),
			witnesses.size() + rejectedAndUnknown[0] + rejectedAndUnknown[1]);
		return new ProductionSpace(List.copyOf(witnesses), signatures(witnesses),
			rejectedAndUnknown[0], rejectedAndUnknown[1]);
	}

	/** Production validation derives exact receipts and selected relocation choices from the row. */
	private static ExactPhysicalSelection validatedSelection(ExactPhysicalModel model,
		ExactPhysicalRawSpaceExporter.Row row) {
		ExactCategoricalSolver.Statistics statistics = new ExactCategoricalSolver.Statistics(
			List.of(), 0, 1, 1, 1, 1);
		ExactCategoricalSolver.Result result = new ExactCategoricalSolver.Result(
			0.0, row.values(), statistics);
		ExactPhysicalOptimizer.Result optimized = new ExactPhysicalOptimizer.Result(result,
			Double.doubleToRawLongBits(0.0), "independent-complete-space-fixture");
		return ExactPhysicalSelection.create(model, optimized);
	}

	private static AdmittedWitness project(Fixture fixture,
		ExactPhysicalRawSpaceExporter.Row row, ExactPhysicalSelection selection) {
		Map<CompiledHopKey,CandidateSelectionReceipt> receipts = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : selection.candidateReceipts())
			Assert.assertNull("validated selection must not contain duplicate receipt owners",
				receipts.put(receipt.rule().parentOccurrence(), receipt));
		Map<String,RoleChoice> choices = new LinkedHashMap<>();
		for(String role : ROLES) {
			ExactPhysicalModel.DecisionDomain domain = fixture.domains().get(role);
			int domainIndex = fixture.model().domains().indexOf(domain);
			int alternativeIndex = row.values().get(domainIndex);
			ExactPhysicalModel.Alternative alternative = domain.alternatives().get(alternativeIndex);
			CandidateSelectionReceipt receipt = receipts.get(domain.node().key());
			Assert.assertNotNull("admitted role must own a validated exact receipt: " + role, receipt);
			Assert.assertSame("validated receipt realization must be the selected model realization",
				alternative.realization(), receipt.realization());
			Assert.assertSame("validated receipt must retain precisely the selected support clause",
				alternative.supportClause(), receipt.supportClause());
			choices.put(role, new RoleChoice(alternative.state(),
				receipt.realization().key().layoutKind(), selectedGeometries(fixture, receipt, receipts, new LinkedHashSet<>()),
				alternativeIndex));
		}
		return new AdmittedWitness(row.ordinal(), fixture.privacy(), choices,
			selection.relocationChoices().stream().map(choice -> choice.normalizedSignature()).sorted().toList(),
			selection.emittedRelocations().stream().map(action -> action.normalizedSignature()).sorted().toList());
	}

	/** Exhaust literal placement/action choices and receipt routes, without reading the model. */
	private static Set<String> expectedSpace(Privacy privacy) {
		Set<String> expected = new LinkedHashSet<>();
		for(int placements = 0; placements < 1 << ROLES.size(); placements++) {
			Map<String,PlacementState> states = new LinkedHashMap<>();
			for(int index = 0; index < ROLES.size(); index++)
				states.put(ROLES.get(index), (placements & 1 << index) == 0 ? REMOTE_ROW : LOCAL);
			for(int actionPresence = 0; actionPresence < 4; actionPresence++) {
				if(!independentlyLegal(privacy, states, actionPresence))
					continue;
				for(BranchRoute left : branchRoutes())
					for(BranchRoute right : branchRoutes()) {
						if(left.operation().layout() == PlacementLayoutKind.DURABLE_MAP
							&& right.operation().layout() == PlacementLayoutKind.DURABLE_MAP)
							addExpected(expected, privacy, left, right,
								choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY),
								choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY));
						addExpected(expected, privacy, left, right,
							choice(PlacementLayoutKind.NATIVE_LINEAGE, NATIVE_ROW_GEOMETRY),
							choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY));
						// Zero iterations uses the initial D map; either branch supplies later values.
						Set<String> incomingMaps = new LinkedHashSet<>(List.of(ROW_GEOMETRY));
						incomingMaps.addAll(left.operation().geometries());
						incomingMaps.addAll(right.operation().geometries());
						RoleChoice valueJoin = new RoleChoice(REMOTE_ROW, PlacementLayoutKind.VALUE_MAP,
							incomingMaps, -1);
						addExpected(expected, privacy, left, right, valueJoin,
							choice(PlacementLayoutKind.NATIVE_LINEAGE, NATIVE_ROW_GEOMETRY));
						addExpected(expected, privacy, left, right, valueJoin, valueJoin);
					}
			}
		}
		return Set.copyOf(expected);
	}

	private static List<BranchRoute> branchRoutes() {
		return List.of(
			new BranchRoute(choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY),
				choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY)),
			new BranchRoute(choice(PlacementLayoutKind.NATIVE_LINEAGE, NATIVE_ROW_GEOMETRY),
				choice(PlacementLayoutKind.DURABLE_MAP, ROW_GEOMETRY)),
			new BranchRoute(choice(PlacementLayoutKind.VALUE_MAP, ROW_GEOMETRY),
				choice(PlacementLayoutKind.NATIVE_LINEAGE, NATIVE_ROW_GEOMETRY)),
			new BranchRoute(choice(PlacementLayoutKind.VALUE_MAP, ROW_GEOMETRY),
				choice(PlacementLayoutKind.VALUE_MAP, ROW_GEOMETRY)));
	}

	private static RoleChoice choice(PlacementLayoutKind layout, String geometry) {
		return new RoleChoice(REMOTE_ROW, layout, Set.of(geometry), -1);
	}

	private static boolean independentlyLegal(Privacy privacy,
		Map<String,PlacementState> states, int actionPresence) {
		return (privacy == Privacy.PRIVATE || privacy == Privacy.PRIVATE_AGGREGATE)
			&& states.values().stream().allMatch(REMOTE_ROW::equals)
			// All inputs already reside on the fixture workers; neither relocation is needed.
			&& actionPresence == 0;
	}

	private static void addExpected(Set<String> expected, Privacy privacy,
		BranchRoute left, BranchRoute right, RoleChoice joined, RoleChoice function) {
		Map<String,RoleChoice> choices = Map.of(
			"branch-if-read", left.read(), "branch-if-operation", left.operation(),
			"branch-else-read", right.read(), "branch-else-operation", right.operation(),
			"joined-read", joined, "function-operation", function);
		Assert.assertTrue("literal routes must remain separately identifiable",
			expected.add(new AdmittedWitness(BigInteger.valueOf(-1), privacy,
				choices, List.of(), List.of()).signature()));
	}

	/** Test-owned evaluation of the selected clause, never all clauses or an expected-map default. */
	private static Set<String> selectedGeometries(Fixture fixture, CandidateSelectionReceipt receipt,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected, Set<CandidateRealizationReference> visiting) {
		CandidateRealizationReference reference = CandidateRealizationReference.of(
			receipt.rule(), receipt.realization());
		Assert.assertTrue("cyclic selected VALUE_MAP support in this acyclic fixture", visiting.add(reference));
		try {
			if(receipt.realization().key().layoutKind() != PlacementLayoutKind.VALUE_MAP)
				return Set.of(geometry(receipt.provenWorkerPool()));
			List<CandidateRealizationReference> inputs = receipt.supportClause().requiredInputSupport();
			Set<CompiledHopKey> expectedOwners = fixture.valueInputs().get(receipt.rule().parentOccurrence());
			Assert.assertNotNull("fixture must declare VALUE_MAP input owners", expectedOwners);
			Assert.assertEquals("VALUE_MAP must preserve every fixture input, including equal-map branches",
				expectedOwners, inputs.stream().map(input -> input.rule().parentOccurrence())
					.collect(java.util.stream.Collectors.toSet()));
			Assert.assertEquals(expectedOwners.size(), receipt.supportClause().inputBindings().size());
			boolean transientRead = fixture.analysis().hop(receipt.rule().parentOccurrence())
				.orElseThrow() instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD;
			for(var binding : receipt.supportClause().inputBindings()) {
				Assert.assertEquals("fixture matrix input occupies position zero", 0, binding.inputPosition());
				Assert.assertEquals(transientRead ? CandidateInputBindingKind.LOGICAL_TRANSIENT
					: CandidateInputBindingKind.DIRECT, binding.kind());
			}
			Set<String> maps = new LinkedHashSet<>();
			for(CandidateRealizationReference input : inputs) {
				CandidateSelectionReceipt source = selected.get(input.rule().parentOccurrence());
				Assert.assertNotNull("VALUE_MAP input must have a selected receipt", source);
				Assert.assertEquals("VALUE_MAP must follow the selected realization, not another alternative",
					input, CandidateRealizationReference.of(source.rule(), source.realization()));
				maps.addAll(selectedGeometries(fixture, source, selected, visiting));
			}
			return Set.copyOf(maps);
		}
		finally {
			visiting.remove(reference);
		}
	}

	private static Fixture fixture(Privacy privacy) throws Exception {
		PlacementAnalysis analysis = analysis(privacy);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Map<String,ExactPhysicalModel.DecisionDomain> domains = new LinkedHashMap<>();
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			String role = role(analysis, domain.node());
			if(role != null)
				Assert.assertNull("fixture role must map to one exact domain: " + role,
					domains.put(role, domain));
		}
		Assert.assertEquals("fixture must expose every bounded role", new LinkedHashSet<>(ROLES),
			domains.keySet());
		Map<CompiledHopKey,Set<CompiledHopKey>> inputs = new IdentityHashMap<>();
		CompiledHopKey initialA = writer(analysis, model, "A", "");
		CompiledHopKey initialD = writer(analysis, model, "D", "");
		CompiledHopKey ifWriter = writer(analysis, model, "D", "branch-if");
		CompiledHopKey elseWriter = writer(analysis, model, "D", "branch-else");
		for(String branch : List.of("branch-if", "branch-else")) {
			CompiledHopKey read = domains.get(branch + "-read").node().key();
			CompiledHopKey operation = domains.get(branch + "-operation").node().key();
			inputs.put(read, Set.of(initialA));
			inputs.put(operation, Set.of(read));
			inputs.put(branch.equals("branch-if") ? ifWriter : elseWriter, Set.of(operation));
		}
		CompiledHopKey joined = domains.get("joined-read").node().key();
		inputs.put(joined, Set.of(initialD, ifWriter, elseWriter));
		inputs.put(domains.get("function-operation").node().key(), Set.of(joined));
		return new Fixture(privacy, analysis, model, Map.copyOf(domains), Map.copyOf(inputs));
	}

	private static CompiledHopKey writer(PlacementAnalysis analysis, ExactPhysicalModel model,
		String name, String branch) {
		List<CompiledHopKey> matches = model.domains().stream().map(domain -> domain.node())
			.filter(node -> node.kind() == NodeKind.TRANSIENT_WRITE
				&& analysis.hop(node.key()).orElseThrow().getName().equals(name))
			.filter(node -> branch.isEmpty()
				? node.key().controlRegion().regionPath().stream().noneMatch(part -> part.contains("branch-"))
				: node.key().controlRegion().regionPath().stream().anyMatch(part -> part.contains(branch)))
			.map(Node::key).toList();
		Assert.assertEquals("fixture writer must be unique: " + name + '/' + branch, 1, matches.size());
		return matches.get(0);
	}

	private static String role(PlacementAnalysis analysis, Node node) {
		String name = analysis.hop(node.key()).map(hop -> hop.getName()).orElse("");
		List<String> path = node.key().controlRegion().regionPath();
		boolean branchIf = path.stream().anyMatch(part -> part.contains("branch-if"));
		boolean branchElse = path.stream().anyMatch(part -> part.contains("branch-else"));
		if(branchIf && node.kind() == NodeKind.OPERATION && "D".equals(name))
			return "branch-if-operation";
		if(branchIf && node.kind() == NodeKind.TRANSIENT_READ && "A".equals(name))
			return "branch-if-read";
		if(branchElse && node.kind() == NodeKind.OPERATION && "D".equals(name))
			return "branch-else-operation";
		if(branchElse && node.kind() == NodeKind.TRANSIENT_READ && "A".equals(name))
			return "branch-else-read";
		if(path.equals(List.of("main/2")) && node.kind() == NodeKind.TRANSIENT_READ
			&& "D".equals(name))
			return "joined-read";
		if(path.equals(List.of("main/2")) && node.kind() == NodeKind.OPERATION
			&& analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
				edge.consumer() == node.key() && edge.inputPosition() == 0
					&& analysis.hop(edge.producer()).orElse(null) instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD && "D".equals(data.getName())))
			return "function-operation";
		return null;
	}

	private static PlacementAnalysis analysis(Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		program.getNamedNSFunctionStatementBlocks().values().forEach(function ->
			function.setRecompileOnce(true));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static String geometry(DurableAnchorKey anchor) {
		Assert.assertNotNull("bounded protected receipt needs an exact worker map", anchor);
		return anchor.fType() + ":" + anchor.partitions().stream()
			.map(IndependentCompletePlacementSpaceTest::partition)
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static String partition(AnchorPartition partition) {
		return partition.workerId() + '|' + coordinates(partition.begin()) + ':'
			+ coordinates(partition.end());
	}

	private static String coordinates(List<Long> values) {
		return values.stream().map(String::valueOf).reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static String state(PlacementState state) {
		return state.execType() + "/" + state.output() + "/"
			+ (state.fType() == null ? "NONE" : state.fType());
	}

	private static Set<String> signatures(List<AdmittedWitness> witnesses) {
		Set<String> result = new LinkedHashSet<>();
		witnesses.forEach(witness -> result.add(witness.signature()));
		return Set.copyOf(result);
	}

	private static Set<String> difference(Set<String> left, Set<String> right) {
		Set<String> result = new LinkedHashSet<>(left);
		result.removeAll(right);
		return Set.copyOf(result);
	}

	private static void assertSameSpace(Set<String> expected, Set<String> actual) {
		Assert.assertEquals("missing=" + difference(expected, actual)
			+ "; extra=" + difference(actual, expected), expected, actual);
	}

	private record RoleChoice(PlacementState state, PlacementLayoutKind layout,
		Set<String> geometries, int alternativeIndex) {
		private RoleChoice {
			geometries = Set.copyOf(geometries);
		}
		private String signature() {
			return IndependentCompletePlacementSpaceTest.state(state) + '|' + layout + '|'
				+ geometries.stream().sorted().toList();
		}
	}

	private record AdmittedWitness(BigInteger rawOrdinal, Privacy privacy,
		Map<String,RoleChoice> roles, List<String> relocationChoices, List<String> emittedActions) {
		private AdmittedWitness {
			roles = Map.copyOf(roles);
			relocationChoices = List.copyOf(relocationChoices);
			emittedActions = List.copyOf(emittedActions);
		}

		private String signature() {
			return privacy + "|roles=" + ROLES.stream()
				.map(role -> role + ':' + roles.get(role).signature()).toList()
				+ "|relocationChoices=" + relocationChoices + "|emittedActions=" + emittedActions;
		}
	}

	private record BranchRoute(RoleChoice read, RoleChoice operation) { }

	private record Fixture(Privacy privacy, PlacementAnalysis analysis,
		ExactPhysicalModel model, Map<String,ExactPhysicalModel.DecisionDomain> domains,
		Map<CompiledHopKey,Set<CompiledHopKey>> valueInputs) { }

	private record ProductionSpace(List<AdmittedWitness> witnesses, Set<String> signatures,
		int rejectedRows, int unknownRows) { }
}

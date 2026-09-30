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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
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
		"ROW:localhost:5334/A1|0,0:4,2,localhost:5335/A2|4,0:8,2";
	private static final List<String> ROLES = List.of(
		"branch-if-operation", "branch-if-read", "branch-else-operation",
		"branch-else-read", "function-operation", "joined-read");
	private static final String SCRIPT =
		"f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}"
			+ "A=federated(addresses=list(\"localhost:5334/A1\",\"localhost:5335/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
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
		List<ExactPhysicalRawSpaceExporter.Row> rows = rawRows(fixture.model());
		ExactPhysicalRawSpaceExporter.Row rejected = rows.stream()
			.filter(row -> row.status() == ExactPhysicalRawSpaceExporter.Status.REJECTED)
			.findFirst().orElseThrow();

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> validatedSelection(fixture.model(), rejected));
		Assert.assertTrue("an injected hard-rejected raw row must also fail production selection validation: "
			+ failure.getMessage(), failure.getMessage().contains("violates exact transient realization support")
				|| failure.getMessage().contains("foreign, inactive, or unreachable"));
	}

	private static void assertComplete(Privacy privacy) throws Exception {
		Fixture fixture = fixture(privacy);
		ProductionSpace production = productionSpace(fixture);
		Set<String> expected = expectedSpace(privacy);

		Assert.assertEquals("bounded raw model size changed", BigInteger.valueOf(64),
			ExactPhysicalRawSpaceExporter.size(fixture.model()));
		Assert.assertEquals("bounded exact hard-factor count changed", 108,
			fixture.model().hardFactors().size());
		Assert.assertEquals("literal universe has three independent binary receipt choices", 8,
			expected.size());
		Assert.assertEquals("every admitted raw row must remain separately identifiable",
			production.witnesses().size(), production.signatures().size());
		Assert.assertEquals("raw ordinals must identify every admitted production row",
			production.witnesses().size(), production.witnesses().stream()
				.map(AdmittedWitness::rawOrdinal).distinct().count());
		Assert.assertEquals("bounded alternative tuples must identify every admitted production row",
			production.witnesses().size(), production.witnesses().stream().map(witness ->
				ROLES.stream().map(role -> witness.roles().get(role).alternativeIndex()).toList())
				.distinct().count());
		Assert.assertEquals("exact hard factors must admit eight of 64 raw rows", 8,
			production.witnesses().size());
		Assert.assertEquals("bounded model must reject the other 56 raw rows", 56,
			production.rejectedRows());
		Assert.assertEquals("hard-factor callback errors/non-boolean costs must fail closed", 0,
			production.unknownRows());
		assertSameSpace(expected, production.signatures());
	}

	private static ProductionSpace productionSpace(Fixture fixture) {
		List<AdmittedWitness> witnesses = new ArrayList<>();
		int rejected = 0;
		int unknown = 0;
		for(ExactPhysicalRawSpaceExporter.Row row : rawRows(fixture.model())) {
			if(row.status() == ExactPhysicalRawSpaceExporter.Status.REJECTED) {
				rejected++;
				continue;
			}
			if(row.status() == ExactPhysicalRawSpaceExporter.Status.UNKNOWN) {
				unknown++;
				Assert.fail("exact hard-model row classification is UNKNOWN|ordinal="
					+ row.ordinal() + "|reason=" + row.reason());
			}
			ExactPhysicalSelection selection = validatedSelection(fixture.model(), row);
			witnesses.add(project(fixture, row, selection));
		}
		Assert.assertEquals("every raw row must be classified exactly once", 64,
			witnesses.size() + rejected + unknown);
		return new ProductionSpace(List.copyOf(witnesses), signatures(witnesses), rejected, unknown);
	}

	private static List<ExactPhysicalRawSpaceExporter.Row> rawRows(ExactPhysicalModel model) {
		List<ExactPhysicalRawSpaceExporter.Row> rows = new ArrayList<>();
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO,
			ExactPhysicalRawSpaceExporter.size(model), rows::add);
		return List.copyOf(rows);
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
			choices.put(role, new RoleChoice(alternative.state(),
				receipt.realization().key().layoutKind(), geometry(receipt.provenWorkerPool()),
				alternativeIndex));
		}
		return new AdmittedWitness(row.ordinal(), fixture.privacy(), choices,
			selection.relocationChoices().stream().map(choice -> choice.normalizedSignature()).sorted().toList(),
			selection.emittedRelocations().stream().map(action -> action.normalizedSignature()).sorted().toList());
	}

	/** Exhaust the literal original fixture universe without reading model domains or survivors. */
	private static Set<String> expectedSpace(Privacy privacy) {
		Set<String> expected = new LinkedHashSet<>();
		for(int placements = 0; placements < 1 << ROLES.size(); placements++) {
			Map<String,PlacementState> states = new LinkedHashMap<>();
			for(int index = 0; index < ROLES.size(); index++)
				states.put(ROLES.get(index), (placements & 1 << index) == 0 ? REMOTE_ROW : LOCAL);
			for(int receiptBits = 0; receiptBits < 8; receiptBits++)
				for(int actionPresence = 0; actionPresence < 4; actionPresence++)
					if(independentlyLegal(privacy, states, actionPresence))
						expected.add(expectedWitness(privacy, states, receiptBits).signature());
		}
		return Set.copyOf(expected);
	}

	private static boolean independentlyLegal(Privacy privacy,
		Map<String,PlacementState> states, int actionPresence) {
		return (privacy == Privacy.PRIVATE || privacy == Privacy.PRIVATE_AGGREGATE)
			&& states.values().stream().allMatch(REMOTE_ROW::equals)
			// Every input already has exact same-map direct authority. Selecting either
			// bounded relocation action would be an extra physical movement.
			&& actionPresence == 0;
	}

	private static AdmittedWitness expectedWitness(Privacy privacy,
		Map<String,PlacementState> states, int receiptBits) {
		Map<String,RoleChoice> choices = new LinkedHashMap<>();
		for(String role : ROLES) {
			boolean binaryReceipt = role.equals("branch-if-read")
				|| role.equals("branch-else-read") || role.equals("joined-read");
			int bit = role.equals("branch-if-read") ? 0 : role.equals("branch-else-read") ? 1 : 2;
			PlacementLayoutKind layout = binaryReceipt && (receiptBits & 1 << bit) != 0
				? PlacementLayoutKind.NATIVE_LINEAGE : PlacementLayoutKind.DURABLE_MAP;
			choices.put(role, new RoleChoice(states.get(role), layout, ROW_GEOMETRY,
				layout == PlacementLayoutKind.NATIVE_LINEAGE ? 1 : 0));
		}
		return new AdmittedWitness(BigInteger.valueOf(-1), privacy, choices, List.of(), List.of());
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
		return new Fixture(privacy, analysis, model, Map.copyOf(domains));
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
		String geometry, int alternativeIndex) {
		private String signature() {
			return IndependentCompletePlacementSpaceTest.state(state) + '|' + layout + '|'
				+ geometry;
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

	private record Fixture(Privacy privacy, PlacementAnalysis analysis,
		ExactPhysicalModel model, Map<String,ExactPhysicalModel.DecisionDomain> domains) { }

	private record ProductionSpace(List<AdmittedWitness> witnesses, Set<String> signatures,
		int rejectedRows, int unknownRows) { }
}

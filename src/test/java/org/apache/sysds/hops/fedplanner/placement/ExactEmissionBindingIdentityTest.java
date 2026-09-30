package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactEmissionBindingIdentityTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"binding", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = new CompiledHopKey(
		"binding", "main", "root", "compiled", REGION, "owner", "owner");
	private static final CandidateRuleKey RULE = new CandidateRuleKey(OWNER, List.of());

	@Test
	public void stateBinderRetainsCompletelyCurrentImmutableRow() {
		PlacementState graphState = rowState();
		CandidateRuleFact original = fact(graphState);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		List<CandidateRuleFact> result = PlacementSupportRelations.bindExactCandidateEmissionStates(
			List.of(original), List.of(node(graphState)));
		Assert.assertSame(original, result.get(0));
		Assert.assertSame(emission, result.get(0).allowedEmissionFacts().get(0));
		Assert.assertSame(realization,
			result.get(0).allowedEmissionFacts().get(0).realizations().get(0));
		Assert.assertSame(emission.emissionState(), realization.key().emissionState());
	}

	@Test
	public void closureBinderRetainsCurrentRowWhenNoLiteralBindingApplies() throws Exception {
		PlacementState graphState = rowState();
		CandidateRuleFact original = fact(graphState);
		LiteralOp origin = new LiteralOp(1L);
		List<CandidateRuleFact> result = bindRealizations(
			List.of(original), List.of(node(graphState)), Map.of(OWNER, origin),
			Map.of(origin, new NodeShapeFact(DataType.SCALAR, -1, -1)));
		Assert.assertSame(original, result.get(0));
	}

	@Test
	public void nonliteralOuterBinderRetainsMixedLocalAndFederatedEmissions() throws Exception {
		PlacementState local = new PlacementState(
			ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState row = rowState();
		PlacementEmissionState localEmission = new PlacementEmissionState(local, false);
		CandidateEmissionFact localFact = new CandidateEmissionFact(localEmission, null, null,
			List.of(CandidateEmissionRealization.local(localEmission)));
		CandidateEmissionFact rowFact = emission(row, "row-mixed-outer");
		CandidateRuleFact original = fact(List.of(localFact, rowFact));
		LiteralOp origin = new LiteralOp(2L);
		CandidateRuleFact rebound = bindRealizations(List.of(original),
			List.of(node(List.of(local, row), List.of())), Map.of(OWNER, origin),
			Map.of(origin, new NodeShapeFact(DataType.SCALAR, -1, -1))).get(0);
		Assert.assertSame(original, rebound);
		Assert.assertSame(localFact, rebound.allowedEmissionFacts().stream()
			.filter(candidate -> candidate.emissionState().placementState().execType() == ExecType.CP)
			.findFirst().orElseThrow());
		Assert.assertSame(rowFact, rebound.allowedEmissionFacts().stream()
			.filter(candidate -> candidate.emissionState().placementState().execType() == ExecType.FED)
			.findFirst().orElseThrow());
	}

	@Test
	public void equalRealizationEmissionWrapperIsColdNormalized() {
		PlacementState graphState = rowState();
		PlacementEmissionState emissionState = new PlacementEmissionState(graphState, false);
		PlacementEmissionState foreignWrapper = new PlacementEmissionState(graphState, false);
		Assert.assertEquals(emissionState, foreignWrapper);
		Assert.assertNotSame(emissionState, foreignWrapper);
		CandidateEmissionRealization realization =
			CandidateEmissionRealization.sourceLineage(foreignWrapper, "source-lineage");
		CandidateEmissionFact emission = new CandidateEmissionFact(
			emissionState, FType.ROW, null, List.of(realization));
		CandidateRuleFact original = fact(List.of(emission));
		CandidateRuleFact rebound = PlacementSupportRelations.bindExactCandidateEmissionStates(
			List.of(original), List.of(node(graphState))).get(0);
		Assert.assertNotSame(original, rebound);
		CandidateEmissionFact reboundEmission = rebound.allowedEmissionFacts().get(0);
		Assert.assertSame(emissionState, reboundEmission.emissionState());
		Assert.assertSame(emissionState,
			reboundEmission.realizations().get(0).key().emissionState());
	}

	@Test
	public void exactDerivedSourceTargetAndActionRetainIdentity() {
		PlacementState source = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, null, false);
		PlacementState target = rowState();
		CandidateRuleFact original = derivedFact(source, target);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		CandidateRuleFact rebound = PlacementSupportRelations.bindExactCandidateEmissionStates(
			List.of(original), List.of(node(List.of(source, target), List.of(anchor())))).get(0);
		Assert.assertSame(original, rebound);
		Assert.assertSame(emission.derivedFoutAction(),
			rebound.allowedEmissionFacts().get(0).derivedFoutAction());
	}

	@Test
	public void missingDerivedSourceStillFailsClosed() {
		PlacementState source = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, null, false);
		PlacementState target = rowState();
		CandidateRuleFact original = derivedFact(source, target);
		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> PlacementSupportRelations.bindExactCandidateEmissionStates(
				List.of(original), List.of(node(List.of(target), List.of(anchor())))));
		Assert.assertEquals("Candidate materialization source is absent from final graph node",
			failure.getMessage());
	}

	@Test
	public void mixedSiblingPassRetainsOnlyAlreadyExactEmission() {
		PlacementState row = rowState();
		PlacementState graphCol = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
		PlacementState foreignCol = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
		CandidateEmissionFact exact = emission(row, "row");
		CandidateEmissionFact cold = emission(foreignCol, "col");
		CandidateRuleFact original = fact(List.of(exact, cold));
		CandidateRuleFact rebound = PlacementSupportRelations.bindExactCandidateEmissionStates(
			List.of(original), List.of(node(List.of(row, graphCol), List.of()))).get(0);
		Assert.assertNotSame(original, rebound);
		CandidateEmissionFact retained = rebound.allowedEmissionFacts().stream()
			.filter(candidate -> candidate.emissionState().placementState().fType() == FType.ROW)
			.findFirst().orElseThrow();
		CandidateEmissionFact replaced = rebound.allowedEmissionFacts().stream()
			.filter(candidate -> candidate.emissionState().placementState().fType() == FType.COL)
			.findFirst().orElseThrow();
		Assert.assertSame(exact, retained);
		Assert.assertNotSame(cold, replaced);
		Assert.assertSame(graphCol, replaced.emissionState().placementState());
	}

	@Test
	public void literalFederatedAnchorBindingRemainsColdAndRemovesNativePlaceholder()
		throws Exception {
		PlacementState state = rowState();
		PlacementEmissionState emissionState = new PlacementEmissionState(state, false);
		CandidateEmissionRealization placeholder = CandidateEmissionRealization.nativeLineage(
			emissionState, "native", List.of(), List.of());
		CandidateRuleFact original = fact(List.of(new CandidateEmissionFact(
			emissionState, FType.ROW, null, List.of(placeholder))));
		DataOp origin = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", 4, 2, 8, 1000);
		CandidateRuleFact rebound = bindRealizations(List.of(original),
			List.of(node(List.of(state), List.of(anchor()))), Map.of(OWNER, origin),
			Map.of(origin, new NodeShapeFact(DataType.MATRIX, 4, 2))).get(0);
		Assert.assertNotSame(original, rebound);
		List<CandidateEmissionRealization> realizations =
			rebound.allowedEmissionFacts().get(0).realizations();
		Assert.assertTrue(realizations.stream().anyMatch(realization ->
			realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
				&& realization.anchor().equals(anchor())));
		Assert.assertFalse(realizations.stream().anyMatch(realization ->
			realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE
				&& realization.supportClauses().stream()
					.allMatch(clause -> clause.inputBindings().isEmpty())));
	}

	@Test
	public void equalButDistinctGraphStateStillRebindsToGraphIdentity() {
		PlacementState prior = rowState();
		PlacementState graphState = rowState();
		Assert.assertEquals(prior, graphState);
		Assert.assertNotSame(prior, graphState);
		CandidateRuleFact original = fact(prior);
		List<CandidateRuleFact> result = PlacementSupportRelations.bindExactCandidateEmissionStates(
			List.of(original), List.of(node(graphState)));
		CandidateEmissionFact rebound = result.get(0).allowedEmissionFacts().get(0);
		Assert.assertNotSame(original, result.get(0));
		Assert.assertSame(graphState, rebound.emissionState().placementState());
		Assert.assertSame(rebound.emissionState(),
			rebound.realizations().get(0).key().emissionState());
	}

	@Test
	public void missingGraphTargetStillFailsClosed() {
		CandidateRuleFact original = fact(rowState());
		PlacementState incompatible = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> PlacementSupportRelations.bindExactCandidateEmissionStates(
				List.of(original), List.of(node(incompatible))));
		Assert.assertTrue(failure.getMessage().startsWith(
			"Candidate emission is absent from final graph node|key="));
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bindRealizations(List<CandidateRuleFact> facts,
		List<Node> nodes, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindExactCandidateEmissionRealizations", List.class, List.class, Map.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts, nodes, origins, shapes);
	}

	private static CandidateRuleFact fact(PlacementState state) {
		PlacementEmissionState emissionState = new PlacementEmissionState(state, false);
		CandidateEmissionRealization realization =
			CandidateEmissionRealization.sourceLineage(emissionState, "source-lineage");
		CandidateEmissionFact emission = new CandidateEmissionFact(
			emissionState, FType.ROW, null, List.of(realization));
		return fact(List.of(emission));
	}

	private static CandidateRuleFact fact(List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(RULE, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), emissions, "");
	}

	private static CandidateEmissionFact emission(PlacementState state, String lineage) {
		PlacementEmissionState emission = new PlacementEmissionState(state, false);
		return new CandidateEmissionFact(emission, state.fType(), null,
			List.of(CandidateEmissionRealization.sourceLineage(emission, lineage)));
	}

	private static CandidateRuleFact derivedFact(PlacementState source, PlacementState target) {
		PlacementEmissionState emission = new PlacementEmissionState(target, true);
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			OWNER, version(), RULE, source, target, anchor(), OWNER, FType.ROW, FType.ROW, "root");
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
			emission, anchor(), List.of(), List.of());
		return fact(List.of(new CandidateEmissionFact(
			emission, FType.ROW, action, List.of(realization))));
	}

	private static Node node(PlacementState state) {
		return node(List.of(state), List.of());
	}

	private static Node node(List<PlacementState> states, List<DurableAnchorKey> anchors) {
		return new Node(OWNER, NodeKind.OPERATION, version(), true,
			states, List.of(), anchors);
	}

	private static PlacementState rowState() {
		return new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	}

	private static ValueVersionKey version() {
		return new ValueVersionKey("binding", "owner", REGION, 0,
			VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey anchor() {
		return new DurableAnchorKey("anchor", FType.ROW,
			List.of(new AnchorPartition("worker-a:1234", List.of(0L, 0L), List.of(2L, 2L)),
				new AnchorPartition("worker-b:1234", List.of(2L, 0L), List.of(4L, 2L))));
	}
}

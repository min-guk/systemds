/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.InvocationCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileState;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.MaterializeSpec;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Test-only RED for the single exact shared-analysis owner at the typed DP root. */
public class CampaignBDpSharedAnalysisOwnerContractTest {
	private static final Path DP_ROOT = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");
	private static final Path ARCHITECTURE_GUARD = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBArchitectureGuardTest.java");
	private static final Path OWNERSHIP_CLOSURE = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBPlannerOwnershipClosure.java");
	private static final Path TOKEN_SCANNER = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/JavaSourceTokenScanner.java");
	private static final String ARCHITECTURE_GUARD_SHA =
		"8263e06a82f9f17823a1d281a5ab5f2932887ef17b950a7f5bcccaec8ea6fa90";
	private static final String OWNERSHIP_CLOSURE_SHA =
		"a6286fe39edad061225405023d707c676429bb6707fa84c852628db1185c57ab";
	private static final String TOKEN_SCANNER_SHA =
		"a80bb1b061b07743fa283631097a2966a9cf946f54bf53468ead9bfab5ac33c3";
	private static final String DIRECT_DP_CLOSURE_EVIDENCE_SHA =
		"8dcaf54c5993865315cc0c2e565ab766adffc159fb0c74eb911ee8bc07c0ac27";
	private static final int FROZEN_DP_UNITS = 34;
	private static final int FROZEN_DP_VIOLATIONS = 140;
	private static final int PREDICTED_H1_DP_VIOLATIONS = 139;
	private static final String SENTINEL_VAR = "__campaign_b_dp_owner_sentinel";
	private static final String SENTINEL_SIGNATURE = "fed://campaign-b/dp-owner";
	private static final String SENTINEL_ANCHOR = SENTINEL_SIGNATURE + "|ROW";
	private static final long SENTINEL_SCOPE = -911L;
	private static final long SENTINEL_HOP = -912L;
	private static final long SENTINEL_ANCHOR_HOP = -913L;

	@Test
	public void exactBuilderOwnerFlowsThroughMemoAdapterAndInvocationReceipt() {
		Fixture fixture = fixture("B-05");
		AnalysisSnapshot analysisBefore = snapshotAnalysis(fixture.analysis());

		DpInvocationReceipt receipt = new FederatedPlannerDpFedCostBased().rewriteProgram(fixture.program(),
			new FunctionCallGraph(fixture.program()), null, fixture.analysis());

		Assert.assertSame(fixture.analysis(), receipt.analysis());
		Assert.assertSame(fixture.analysis(), receipt.memo().analysis());
		Assert.assertSame(fixture.analysis(), receipt.exactSelection().analysis());
		Assert.assertSame(receipt.memo(), receipt.exactSelection().memo());
		Assert.assertSame(receipt.legacyOptimalPlan(), receipt.exactSelection().legacyOptimalPlan());
		Assert.assertEquals(receipt.analysisFingerprintBefore(), receipt.analysisFingerprintAfter());
		Assert.assertEquals(fixture.analysis().analysisFingerprint(), receipt.analysisFingerprintAfter());
		assertAnalysisSame(analysisBefore, snapshotAnalysis(fixture.analysis()));

		assertIdentityList(receipt.legacyOptimalPlan().getChildFedPlans(),
			receipt.exactSelection().aggregateChildEdges(), "aggregate edges");
		Assert.assertEquals(receipt.exactSelection().selectedRootPlans().size(),
			receipt.exactSelection().selectedRootHops().size());
		for(int i = 0; i < receipt.exactSelection().selectedRootPlans().size(); i++) {
			FedPlan selected = receipt.exactSelection().selectedRootPlans().get(i);
			AppliedPlanReceipt applied = receipt.appliedPlans().get(i);
			Assert.assertSame(selected, applied.plan());
			Assert.assertSame(selected.getHopRef(), applied.planningHop());
			Assert.assertSame(receipt.exactSelection().selectedRootHops().get(i), applied.planningHop());
			Assert.assertEquals(Double.doubleToRawLongBits(selected.getCumulativeCost()),
				Double.doubleToRawLongBits(applied.plan().getCumulativeCost()));
		}

		InvocationCounters counters = receipt.counters();
		Assert.assertEquals(1, counters.enumerationCount());
		Assert.assertEquals(1, counters.exactSelectionCount());
		Assert.assertEquals(1, counters.applicationPhaseCount());
		Assert.assertEquals(receipt.appliedPlans().size(), counters.appliedPlanCount());
		Assert.assertEquals(receipt.additionalRootInvocations().size(), counters.additionalRootInvocationCount());
		Assert.assertEquals(0, counters.internalAnalysisBuildCount());
		Assert.assertEquals(0, counters.oldOverloadCount());
		Assert.assertEquals(0, counters.reenumerationCount());
		Assert.assertEquals(0, counters.repairCount());
		Assert.assertEquals(0, counters.fallbackCount());
		Assert.assertEquals(0, counters.doubleApplicationCount());
		assertImmutable(receipt.appliedPlans(), "applied plans");
		assertImmutable(receipt.additionalRootInvocations(), "additional-root invocations");
		assertImmutable(receipt.exactSelection().selectedRootPlans(), "selected root plans");
		assertImmutable(receipt.exactSelection().selectedRootHops(), "selected root hops");
	}

	@Test
	public void secondAnalysisBuildForSameProgramRejectsBeforeEveryObservableMutation() {
		Fixture owner = fixture("B-05");
		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		Assert.assertNotSame(owner.analysis(), copied);
		Assert.assertSame(owner.program(), owner.analysis().programOwner());
		Assert.assertSame(owner.program(), copied.programOwner());
		assertSameHopOrigins(owner.analysis(), copied);
		ProgramSnapshot ownerBefore = snapshotProgram(owner.program(), owner.analysis());
		AnalysisSnapshot copiedBefore = snapshotAnalysis(copied);
		Hop recompileSentinel = owner.analysis().occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(hop -> FederatedPlannerUtils.plannerRecompileSignature(hop) != null).findFirst()
			.orElseThrow(() -> new AssertionError("fixture has no source-owned recompile sentinel Hop"));

		seedRunState(recompileSentinel);
		RunStateSnapshot runStateBefore = snapshotRunState(recompileSentinel);
		try {
			DpInvocationReceipt accepted = new FederatedPlannerDpFedCostBased().rewriteProgram(owner.program(),
				new FunctionCallGraph(owner.program()), null, copied);
			RunStateSnapshot afterAcceptance = snapshotRunState(recompileSentinel);
			Assert.fail("same-program second analysis was accepted as a DP owner; counters=" + accepted.counters()
				+ " runStatePreserved=" + runStateBefore.sameIdentities(afterAcceptance));
		}
		catch(IllegalArgumentException expected) {
			assertProgramSame(ownerBefore, snapshotProgram(owner.program(), owner.analysis()));
			assertAnalysisSame(copiedBefore, snapshotAnalysis(copied));
			assertRunStateSame(runStateBefore, snapshotRunState(recompileSentinel));
		}
		finally {
			clearRunState();
		}
	}

	@Test
	public void typedRootUsesExactOwnerBoundaryWithoutDuplicateProgramWalk() throws Exception {
		Assert.assertEquals("architecture guard authority changed", ARCHITECTURE_GUARD_SHA,
			sha256(ARCHITECTURE_GUARD));
		Assert.assertEquals("complete ownership-closure authority changed", OWNERSHIP_CLOSURE_SHA,
			sha256(OWNERSHIP_CLOSURE));
		Assert.assertEquals("Java source token scanner authority changed", TOKEN_SCANNER_SHA,
			sha256(TOKEN_SCANNER));
		Assert.assertEquals("H1 prediction must remove exactly one violation", FROZEN_DP_VIOLATIONS - 1,
			PREDICTED_H1_DP_VIOLATIONS);
		Assert.assertEquals("H1 must preserve the complete DP closure unit count", 34, FROZEN_DP_UNITS);
		Assert.assertEquals("frozen direct-closure evidence SHA", 64, DIRECT_DP_CLOSURE_EVIDENCE_SHA.length());
		String source = Files.readString(DP_ROOT);
		String typedSignature = "public DpInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,";
		int typedStart = source.indexOf(typedSignature);
		int dynamicStart = source.indexOf("public void rewriteFunctionDynamic", typedStart);
		Assert.assertTrue("typed DP root source boundary missing", typedStart >= 0 && dynamicStart > typedStart);
		String typedRoot = source.substring(typedStart, dynamicStart);
		int ownerCheck = typedRoot.indexOf("analysis.assertProgramOwner(prog);");
		int reset = typedRoot.indexOf("FederatedPlannerUtils.resetFederatedPlannerRunState();");

		Assert.assertTrue("exact PlacementAnalysis owner must reject before planner reset", ownerCheck >= 0
			&& reset > ownerCheck);
		Assert.assertFalse("H1 duplicate owner walk remains; sole verifier must classify the predicted "
			+ "direct DP closure delta 34/140 -> 34/139", typedRoot.contains("validateSuppliedAnalysis"));
		Assert.assertFalse("H1 must not rebuild membership from program statement blocks",
			typedRoot.contains("getStatementBlocks"));
		Assert.assertFalse("H1 helper relocation is not an owner repair",
			source.contains("private static void validateSuppliedAnalysis"));
		Assert.assertFalse("H1 recursive program-root reconstruction is not an owner repair",
			source.contains("private static void collectProgramRoots"));
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			return new Fixture(program, new NeutralPlacementGraphBuilder().buildAnalysis(program));
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile DP shared-analysis fixture " + id, e);
		}
	}

	private static AnalysisSnapshot snapshotAnalysis(PlacementAnalysis analysis) {
		return new AnalysisSnapshot(analysis, analysis.graph(), analysis.analysisFingerprint(),
			List.copyOf(analysis.occurrences()), analysis.occurrences().stream()
				.map(OccurrenceSnapshot::new).toList());
	}

	private static void assertSameHopOrigins(PlacementAnalysis owner, PlacementAnalysis copied) {
		Assert.assertEquals(owner.occurrences().size(), copied.occurrences().size());
		for(int i = 0; i < owner.occurrences().size(); i++) {
			HopOccurrenceProjection left = owner.occurrences().get(i), right = copied.occurrences().get(i);
			Assert.assertSame("same-program Hop origin " + i, left.hop(), right.hop());
			Assert.assertEquals("same-program compiled key " + i, left.key(), right.key());
			Assert.assertEquals("same-program normalized ordinal " + i,
				left.normalizedOrdinal(), right.normalizedOrdinal());
			Assert.assertEquals("same-program normalized signature " + i,
				left.normalizedSignature(), right.normalizedSignature());
		}
	}

	private static void seedRunState(Hop recompileSentinel) {
		clearRunState();
		FederatedPlannerUtils.registerFedInitVar(SENTINEL_VAR, FType.ROW, SENTINEL_SIGNATURE);
		FederatedPlannerUtils.registerFedAnchorKey(SENTINEL_VAR, SENTINEL_ANCHOR);
		FederatedPlannerUtils.registerPlannerRecompileState(recompileSentinel, ExecType.CP, FederatedOutput.LOUT);
		FederatedRefedRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_ANCHOR_HOP, SENTINEL_ANCHOR);
		FederatedFoutMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_ANCHOR_HOP,
			FType.ROW.name(), SENTINEL_VAR, SENTINEL_ANCHOR);
		FederatedLocalMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP,
			List.of(SENTINEL_ANCHOR_HOP), FType.ROW.name(), "campaign-b-dp-owner-sentinel");
	}

	private static RunStateSnapshot snapshotRunState(Hop recompileSentinel) {
		String recompileSignature = FederatedPlannerUtils.plannerRecompileSignature(recompileSentinel);
		return new RunStateSnapshot(FederatedPlannerUtils.isFedInitVar(SENTINEL_VAR),
			FederatedPlannerUtils.getFedInitFType(SENTINEL_VAR),
			FederatedPlannerUtils.getFedInitSignature(SENTINEL_VAR),
			FederatedPlannerUtils.getFedAnchorKey(SENTINEL_VAR), recompileSignature,
			FederatedPlannerUtils.getPlannerRecompileState(recompileSignature),
			FederatedRefedRegistry.snapshot(SENTINEL_SCOPE),
			FederatedFoutMaterializeRegistry.snapshot(SENTINEL_SCOPE),
			FederatedLocalMaterializeRegistry.snapshotScopes(SENTINEL_SCOPE));
	}

	private static void clearRunState() {
		FederatedPlannerUtils.removeFedAnchorKey(SENTINEL_VAR);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private static void assertRunStateSame(RunStateSnapshot expected, RunStateSnapshot actual) {
		Assert.assertEquals("fed-init sentinel changed", expected.fedInit(), actual.fedInit());
		Assert.assertSame("fed-init FType changed", expected.fedInitType(), actual.fedInitType());
		Assert.assertEquals("fed-init signature changed", expected.fedInitSignature(), actual.fedInitSignature());
		Assert.assertEquals("fed anchor changed", expected.anchorKey(), actual.anchorKey());
		Assert.assertEquals("recompile signature changed", expected.recompileSignature(), actual.recompileSignature());
		Assert.assertSame("recompile state changed", expected.recompileState(), actual.recompileState());
		assertRegistrySame(expected.refed(), actual.refed(), "refed registry");
		assertRegistrySame(expected.fout(), actual.fout(), "FOUT registry");
		Assert.assertEquals("local-materialize scopes changed", expected.local().keySet(), actual.local().keySet());
		for(long scope : expected.local().keySet())
			assertRegistrySame(expected.local().get(scope), actual.local().get(scope),
				"local-materialize registry scope " + scope);
	}

	private static void assertRegistrySame(Map<?, ?> expected, Map<?, ?> actual, String label) {
		Assert.assertEquals(label + " keys", expected.keySet(), actual.keySet());
		for(Object key : expected.keySet())
			Assert.assertSame(label + " value " + key, expected.get(key), actual.get(key));
	}

	private static String sha256(Path path) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
		StringBuilder value = new StringBuilder(digest.length * 2);
		for(byte item : digest)
			value.append(String.format("%02x", item & 0xff));
		return value.toString();
	}

	private static ProgramSnapshot snapshotProgram(DMLProgram program, PlacementAnalysis analysis) {
		return new ProgramSnapshot(program, PlacementGraphFingerprint.capture(program), snapshotAnalysis(analysis),
			analysis.occurrences().stream().map(HopOccurrenceProjection::hop).distinct()
				.map(HopSnapshot::new).toList());
	}

	private static void assertProgramSame(ProgramSnapshot expected, ProgramSnapshot actual) {
		Assert.assertSame(expected.program(), actual.program());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		assertAnalysisSame(expected.analysis(), actual.analysis());
		Assert.assertEquals(expected.hops().size(), actual.hops().size());
		for(int i = 0; i < expected.hops().size(); i++)
			assertHopSame(expected.hops().get(i), actual.hops().get(i));
	}

	private static void assertAnalysisSame(AnalysisSnapshot expected, AnalysisSnapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertSame(expected.graph(), actual.graph());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences(), "analysis occurrences");
		Assert.assertEquals(expected.states().size(), actual.states().size());
		for(int i = 0; i < expected.states().size(); i++) {
			OccurrenceSnapshot left = expected.states().get(i), right = actual.states().get(i);
			Assert.assertSame(left.occurrence(), right.occurrence());
			Assert.assertSame(left.key(), right.key());
			Assert.assertSame(left.hop(), right.hop());
			Assert.assertEquals(left.ordinal(), right.ordinal());
			Assert.assertEquals(left.signature(), right.signature());
		}
	}

	private static void assertHopSame(HopSnapshot expected, HopSnapshot actual) {
		Assert.assertSame(expected.hop(), actual.hop());
		Assert.assertEquals(expected.hopId(), actual.hopId());
		Assert.assertSame(expected.execType(), actual.execType());
		Assert.assertSame(expected.output(), actual.output());
		Assert.assertEquals(expected.requiresRecompile(), actual.requiresRecompile());
		assertIdentityList(expected.inputs(), actual.inputs(), "hop inputs");
		assertIdentityList(expected.parents(), actual.parents(), "hop parents");
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values, String label) {
		try {
			((List) values).add(null);
			Assert.fail("mutable " + label);
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis) { }
	private record AnalysisSnapshot(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph graph, String fingerprint,
		List<HopOccurrenceProjection> occurrences, List<OccurrenceSnapshot> states) { }
	private record OccurrenceSnapshot(HopOccurrenceProjection occurrence,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key, Hop hop, int ordinal,
		String signature) {
		private OccurrenceSnapshot(HopOccurrenceProjection occurrence) {
			this(occurrence, occurrence.key(), occurrence.hop(), occurrence.normalizedOrdinal(),
				occurrence.normalizedSignature());
		}
	}
	private record HopSnapshot(Hop hop, long hopId, org.apache.sysds.common.Types.ExecType execType,
		FederatedOutput output, boolean requiresRecompile, List<Hop> inputs, List<Hop> parents) {
		private HopSnapshot(Hop hop) {
			this(hop, hop.getHopID(), hop.getForcedExecType(), hop.getFederatedOutput(), hop.requiresRecompile(),
				List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
		}
	}
	private record ProgramSnapshot(DMLProgram program, String fingerprint, AnalysisSnapshot analysis,
		List<HopSnapshot> hops) { }
	private record RunStateSnapshot(boolean fedInit, FType fedInitType, String fedInitSignature, String anchorKey,
		String recompileSignature, PlannerRecompileState recompileState, Map<Long, AnchorSpec> refed,
		Map<Long, MaterializeSpec> fout, Map<Long, Map<Long, LocalMaterializeSpec>> local) {
		private boolean sameIdentities(RunStateSnapshot that) {
			return fedInit == that.fedInit && fedInitType == that.fedInitType
				&& java.util.Objects.equals(fedInitSignature, that.fedInitSignature)
				&& java.util.Objects.equals(anchorKey, that.anchorKey)
				&& recompileState == that.recompileState && refed.equals(that.refed)
				&& fout.equals(that.fout) && local.equals(that.local);
		}
	}
}

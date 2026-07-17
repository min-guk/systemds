/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.InvocationCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Test-only RED for the single exact shared-analysis owner at the typed DP root. */
public class CampaignBDpSharedAnalysisOwnerContractTest {
	private static final Path DP_ROOT = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");

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
	public void independentlyCompiledSameSourceOwnerRejectsBeforeResetEnumerationOrApplication() {
		Fixture owner = fixture("B-05");
		Fixture copied = fixture("B-05");
		Assert.assertNotSame(owner.program(), copied.program());
		Assert.assertNotSame(owner.analysis(), copied.analysis());
		ProgramSnapshot ownerBefore = snapshotProgram(owner.program(), owner.analysis());
		ProgramSnapshot copiedBefore = snapshotProgram(copied.program(), copied.analysis());

		try {
			new FederatedPlannerDpFedCostBased().rewriteProgram(owner.program(),
				new FunctionCallGraph(owner.program()), null, copied.analysis());
			Assert.fail("independently compiled same-source analysis became a second DP owner");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("Placement analysis is foreign to the supplied program", expected.getMessage());
		}

		assertProgramSame(ownerBefore, snapshotProgram(owner.program(), owner.analysis()));
		assertProgramSame(copiedBefore, snapshotProgram(copied.program(), copied.analysis()));
	}

	@Test
	public void typedRootUsesExactOwnerBoundaryWithoutDuplicateProgramWalk() throws Exception {
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
}

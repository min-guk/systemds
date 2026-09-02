/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for WAN-light ALS inner-CG partitioned compute being priced as serial work. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014AlsPartitionedComputeCostRedTest {
	@Test
	public void singleWorkerFullAlsHasCandidateReachableFedAllPlan() throws Exception {
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			DMLProgram program = als(1);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			var invocation = new FederatedPlannerFedAll().rewriteProgram(
				program, null, null, analysis);
			var selected = invocation.result();
			Assert.assertEquals("FedAll must assign every ALS occurrence from the shared legal domain",
				analysis.graph().decisionNodes().size(), selected.selectedStates().size());
			Assert.assertTrue("FedAll must retain at least one selected candidate for worker=1 FULL ALS",
				!selected.selectedCandidateSelections().isEmpty());
			List<CompiledHopKey> directOwners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> PlacementCostSemantics.directWdivmmRuntimeFact(analysis, key) != null)
				.toList();
			Assert.assertFalse("Worker=1 ALS must expose the direct line-125 WDivMM owner",
				directOwners.isEmpty());
			for(CompiledHopKey owner : directOwners) {
				var runtime = PlacementCostSemantics.directWdivmmRuntimeFact(analysis, owner);
				var ownerState = selected.selectedStates().get(owner);
				var weightsState = selected.selectedStates().get(runtime.weights());
				Assert.assertTrue("FedAll's selected FULL owner must satisfy the same runtime contract"
					+ " used by every selector", PlacementCostSemantics
						.directWdivmmRuntimeAssignmentCompatible(runtime, ownerState, weightsState));
				Assert.assertTrue("Atomic emission must explicitly authorize only the modeled Pattern-2"
					+ " substitution for runtime recompilation",
					FederatedPlannerUtils.hasPlannerModeledRewrite(
						analysis.hop(owner).orElseThrow(),
						FederatedPlannerUtils.REWRITE_DIRECT_WDIVMM_PATTERN_2));
			}
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void wanLightAlsDpRetainsDerivedFoutAlternativeWithoutPretendingToBeGlobal() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(4);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			var dpSelection = new FederatedPlannerDpFedCostBased()
				.selectProgram(program, null, null, analysis);
			NormalizedPlannerResult dp = dpSelection.normalizedResult();
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			NormalizedPlannerResult exact = ExactPhysicalPlacementProjector.project(
				ExactPhysicalSelection.create(model, optimized)).normalizedResult();
			List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> analysis.hop(key).orElse(null) instanceof ReorgOp reorg
					&& reorg.getOp() == ReOrgOp.TRANS && reorg.getBeginLine() == 130)
				.filter(key -> PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, key) != null)
				.toList();
			Assert.assertFalse("ALS fixture did not expose the latent line-130 WDivMM owner", owners.isEmpty());
			for(CompiledHopKey owner : owners) {
				var alternatives = analysis.graph().node(owner).orElseThrow().legalAlternatives();
				Assert.assertTrue("Common analysis must retain the runtime-supported FED/LOUT/ROW WDivMM owner"
					+ "|owner=" + describe(analysis, owner) + "|alternatives=" + alternatives,
					alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED
						&& state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
							.FederatedOutput.LOUT
						&& state.fType() == FType.ROW));
				var occurrence = analysis.compiledHopOccurrences().stream()
					.filter(candidate -> candidate.key() == owner).findFirst().orElseThrow();
				var retained = dpSelection.memo().getAllExactPlanVariantsForOccurrence(occurrence).stream()
					.filter(arm -> arm.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
						.FederatedOutput.LOUT)
					.toList();
				var local = retained.stream().map(arm -> arm.plan())
					.filter(plan -> plan.getExecType() == ExecType.CP).findFirst().orElseThrow();
				var federated = retained.stream().map(arm -> arm.plan())
					.filter(plan -> plan.getExecType() == ExecType.FED && plan.getFType() == FType.ROW)
					.findFirst().orElseThrow();
				Assert.assertTrue("The WDivMM owner self cost must favor FED; the inversion must come from its"
					+ " input frontier rather than a mispriced owner HOP",
					federated.getCumulativeCost() - federated.getEmbeddedChildRecurrenceCost()
						< local.getCumulativeCost() - local.getEmbeddedChildRecurrenceCost());
				var localMultiply = findPlan(local, plan -> plan.getHopRef().getBeginLine() == 130
					&& "b(*)".equals(plan.getHopRef().getOpString()) && plan.getExecType() == ExecType.CP);
				var fedMultiply = findPlan(federated, plan -> plan.getHopRef().getBeginLine() == 130
					&& "b(*)".equals(plan.getHopRef().getOpString()) && plan.getExecType() == ExecType.FED);
				Assert.assertNotNull(localMultiply);
				Assert.assertNotNull(fedMultiply);
				Assert.assertTrue("The b(*) self cost must also favor FED",
					fedMultiply.getCumulativeCost() - fedMultiply.getEmbeddedChildRecurrenceCost()
						< localMultiply.getCumulativeCost() - localMultiply.getEmbeddedChildRecurrenceCost());
				var localW = findPlan(localMultiply, plan -> plan.getHopRef().getBeginLine() == 130
					&& "TRead W".equals(plan.getHopRef().getOpString()));
				var fedW = findPlan(fedMultiply, plan -> plan.getHopRef().getBeginLine() == 130
					&& "TRead W".equals(plan.getHopRef().getOpString()));
				var exactW = dpSelection.memo().requirePlanCarrierOccurrence(localW.getHopRef()).key();
				Assert.assertSame("Both alternatives must inherit the same exact TRead recurrence; multi-parent"
					+ " cumulative sharing must not create the CP/FED ordering", localW, fedW);
				Assert.assertEquals("The local recurrence sees only one direct consumer for this TRead occurrence",
					1, localW.getNumOfParents());
				var localWWrite = localW.getExactChildPlanEdges().get(0).selectedPlan();
				Assert.assertEquals("The defining W write is shared by two local recurrence parents",
					2, localWWrite.getNumOfParents());
				Assert.assertEquals("The TRead must inherit the ordinary half-share of that two-parent write",
					localWWrite.getCumulativeCost() / 2.0, localW.getCumulativeCost(), 1e-9);
				Assert.assertEquals(0.0, localMultiply.getPhysicalChildBoundaryCost(), 0.0);
				Assert.assertTrue("The retained FED arm must expose the exact CP-to-FOUT upload as a physical"
					+ " boundary, not hide it in HOP self cost", fedMultiply.getPhysicalChildBoundaryCost() > 0.0);
				Assert.assertEquals("The physical boundary must equal the graph-owned relocation-action receipt",
					fedMultiply.getPhysicalChildBoundaryCost(), fedMultiply.getDirectRelocationActionCosts().values()
						.stream().mapToDouble(Double::doubleValue).sum(), 0.0);
				Assert.assertTrue("This exact ALS boundary has one compatible consumer per selected action;"
					+ " cross-parent relocation reuse cannot explain this local arm's cost",
					fedMultiply.getDirectRelocationActionCosts().keySet().stream()
						.allMatch(action -> action.compatibleConsumers().size() == 1));
				Assert.assertTrue("The relocation receipt must materialize the exact W value version",
					fedMultiply.getDirectRelocationActionCosts().keySet().stream().allMatch(action ->
						"W".equals(action.sourceValueVersion().lexicalVariable())
							&& action.targetPlacement().execType() == ExecType.FED
							&& action.targetPlacement().output()
								== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
							&& action.materializationFType() == FType.ROW));
				Assert.assertTrue("That one locally charged upload must dominate the local CP/FED recurrence gap",
					fedMultiply.getPhysicalChildBoundaryCost()
						> fedMultiply.getCumulativeCost() - localMultiply.getCumulativeCost());
				Assert.assertEquals("DP's exact local W read remains constrained to its selected CP write",
					ExecType.CP, dp.selectedStates().get(exactW).execType());
				Assert.assertEquals("Exact's global solution must instead keep the same W value federated",
					ExecType.FED, exact.selectedStates().get(exactW).execType());
				Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT,
					exact.selectedStates().get(exactW).output());
				Assert.assertTrue("The local DP recurrence should explain, rather than hide, its ALS choice"
					+ "|owner=" + describe(analysis, owner) + "|cp=" + local.getCumulativeCost()
					+ "|fed=" + federated.getCumulativeCost(),
					local.getCumulativeCost() < federated.getCumulativeCost());
				Assert.assertEquals("DP is allowed to remain locally suboptimal after retaining the legal FED arm",
					ExecType.CP, dp.selectedStates().get(owner).execType());
				Assert.assertEquals("Exact must expose the global distinction on the same immutable analysis",
					ExecType.FED, exact.selectedStates().get(owner).execType());
			}
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan
		findPlan(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan root,
			Predicate<org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan>
				predicate) {
		if(predicate.test(root))
			return root;
		for(var child : root.getExactChildPlanEdges()) {
			var match = findPlan(child.selectedPlan(), predicate);
			if(match != null)
				return match;
		}
		return null;
	}

	@Test
	public void wanLightAlsTransposePairUsesRuntimeWdivmmOwnerAndLocalOutputContract() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(3);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> analysis.hop(key).orElse(null) instanceof ReorgOp reorg
					&& reorg.getOp() == ReOrgOp.TRANS && reorg.getBeginLine() == 130)
				.toList();
			Assert.assertFalse("ALS fixture did not expose line-130 outer transpose", owners.isEmpty());
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			NormalizedPlannerResult result = ExactPhysicalPlacementProjector.project(
				ExactPhysicalSelection.create(model, optimized)).normalizedResult();
			for(CompiledHopKey owner : owners) {
				var input = analysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == owner && edge.inputPosition() == 0)
					.findFirst().orElseThrow();
				if(!(analysis.hop(input.producer()).orElseThrow() instanceof AggBinaryOp))
					continue;
				var weighted = analysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == input.producer() && edge.inputPosition() == 1)
					.findFirst().orElseThrow();
				var weights = analysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == weighted.producer() && edge.inputPosition() == 0)
					.findFirst().orElseThrow();
				PlacementCostSemantics.LatentWdivmmTransposePairFact runtime =
					PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
				Assert.assertNotNull("ALS line-130 transpose pair must expose its runtime WDivMM fact",
					runtime);
				Assert.assertSame(input.producer(), runtime.inner());
				Assert.assertSame(weights.producer(), runtime.weights());
				Assert.assertEquals(FType.ROW, runtime.partitionedInputFType());
				Assert.assertTrue("LEFT WDivMM over ROW weights must aggregate overlapping partials locally",
					runtime.nativeOutputMustBeLocal());
				Assert.assertTrue(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(
					analysis, input.producer(), owner, 0));
				Assert.assertEquals("Removed inner-MM shell must not own runtime compute", 0.0,
					PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, input.producer()), 0.0);
				Assert.assertTrue("Runtime WDivMM compute must move to the surviving transpose owner",
					PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, owner) > 1000.0);

				var nativeFedEmissions = analysis.candidateRuleFacts().orderedFacts().stream()
					.filter(fact -> fact.key().parentOccurrence() == owner)
					.flatMap(fact -> fact.allowedEmissionFacts().stream())
					.filter(emission -> !emission.emissionState().derivedFedFout())
					.filter(emission -> emission.emissionState().placementState().execType() == ExecType.FED)
					.toList();
				Assert.assertFalse("Runtime WDivMM owner must retain its FED alternative",
					nativeFedEmissions.isEmpty());
				Assert.assertTrue("Native LEFT/ROW WDivMM may emit only FED/LOUT/ROW",
					nativeFedEmissions.stream().allMatch(emission -> {
						var state = emission.emissionState().placementState();
						return state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
							.FederatedOutput.LOUT && state.fType() == FType.ROW;
					}));
				Assert.assertTrue("Removed inner-MM boundary must not create a relocation obligation",
					analysis.graph().relocationActions().stream().flatMap(action -> action.obligations().stream())
						.noneMatch(obligation -> obligation.consumer() == owner
							&& obligation.inputPosition() == 0));

				var ownerState = result.selectedStates().get(owner);
				var weightsState = result.selectedStates().get(weights.producer());
				Assert.assertNotNull(ownerState);
				Assert.assertEquals(ExecType.FED, ownerState.execType());
				Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction
					.FederatedOutput.LOUT, ownerState.output());
				Assert.assertEquals(FType.ROW, ownerState.fType());
				Assert.assertNotNull(weightsState);
				Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction
					.FederatedOutput.FOUT, weightsState.output());
				Assert.assertEquals(FType.ROW, weightsState.fType());

				double outputBytes = 2100D * 10D * 8D;
				double genericDownload = FederatedCostModel.computeDownloadNetworkCost(outputBytes);
				double runtimeFanIn = PlacementCostSemantics
					.analysisAwareNativeFederatedLoutResultCost(
						analysis, owner, outputBytes, 3, genericDownload);
				double expectedFanIn = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
					analysis.hop(input.producer()).orElseThrow(), FType.ROW,
					outputBytes, 3, genericDownload);
				Assert.assertEquals("The transpose shell must use the runtime WDivMM partial-result fan-in",
					expectedFanIn, runtimeFanIn, 0.0);
			}
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void wanLightAlsUsesCapturedSmallInnerShapeForNativeLocalInputCost() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(als(3));
			List<CompiledHopKey> targets = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> analysis.hop(key).orElse(null) instanceof BinaryOp hop
					&& "b(*)".equals(hop.getOpString()) && hop.getBeginLine() == 126)
				.filter(key -> analysis.shapeFact(key).map(shape ->
					shape.rows() == 50000 && shape.cols() == 10).orElse(false))
				.toList();
			Assert.assertFalse("ALS regression fixture did not expose S*HS at line 126", targets.isEmpty());

			Method estimatedBytes = ExactPhysicalCostModel.class.getDeclaredMethod(
				"estimatedBytes", PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
				CompiledHopKey.class,
				org.apache.sysds.hops.Hop.class);
			estimatedBytes.setAccessible(true);
			ExpectedSparseAssignmentEstimates sparseAssignments =
				PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
			for(CompiledHopKey key : targets) {
				double actual = (double)estimatedBytes.invoke(null, analysis, sparseAssignments, key,
					analysis.hop(key).orElseThrow());
				Assert.assertEquals("Exact must price the immutable 50000x10 occurrence shape (allowing"
					+ " only MatrixBlock metadata) rather than"
					+ " a stale pre-recompile HOP memory estimate|target=" + describe(analysis, key),
					50000D * 10D * 8D, actual, 1024D);
				var input = analysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == key && edge.inputPosition() == 0)
					.findFirst().orElseThrow();
				PlacementCostSemantics.NativeLocalInputTransferEstimate bounded =
					PlacementCostSemantics.boundedElementwiseNativeLocalInputTransfer(
						analysis, input.producer(), key, 0, FType.ROW, 3);
				Assert.assertNotNull("The exact non-outer elementwise shape must bound its local input",
					bounded);
				Assert.assertEquals("The local S input is bounded by the compiled 50000x10 payload",
					50000D * 10D * 8D, bounded.logicalBytesUpperBound(), 1024D);
				Assert.assertTrue("The bounded transfer must replace the unknown-shape sentinel cost",
					bounded.uploadPayloadCostUpperBound() < 10000.0);
			}
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void alsWorkerCountChangesThePhysicalInputTopology() throws Exception {
		try {
			Assert.assertEquals("The one-worker campaign input must expose its exact FULL topology",
				FType.FULL, sourceFType(1));
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			Assert.assertEquals("The multi-worker campaign input must expose its exact ROW topology",
				FType.ROW, sourceFType(2));
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void alsLine125DirectWdivmmUsesSharedFullAndRowRuntimeFacts() throws Exception {
		try {
			assertDirectWdivmmRuntimeFact(1, FType.FULL);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			assertDirectWdivmmRuntimeFact(4, FType.ROW);
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void wanLightAlsExactKeepsLargeInnerElementwiseWorkFederated() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(4);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			List<CompiledHopKey> innerMaskReads = innerMaskReads(analysis);
			Assert.assertFalse("ALS regression fixture did not expose inner-CG TRead W", innerMaskReads.isEmpty());
			Assert.assertTrue("Every inner-CG TRead W must retain its unique logical TWrite source across"
					+ " nested recompile contexts|reads=" + innerMaskReads.stream()
						.map(key -> key.normalizedSignature()).toList()
					+ "|facts=" + analysis.logicalTransientInputsInCanonicalOrder(),
				innerMaskReads.stream().allMatch(read -> analysis.logicalTransientInputsInCanonicalOrder().stream()
					.anyMatch(fact -> fact.targetRead() == read)));

			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			NormalizedPlannerResult exact = ExactPhysicalPlacementProjector.project(
				ExactPhysicalSelection.create(model, optimized)).normalizedResult();
			NormalizedPlannerResult dp = new FederatedPlannerDpFedCostBased()
				.selectProgram(program, null, null, analysis).normalizedResult();

			List<CompiledHopKey> targets = largeInnerElementwiseHops(analysis, exact);
			Assert.assertFalse("ALS regression fixture did not expose the 50000x2100 inner-CG b(*) stage",
				targets.isEmpty());
			assertFederated("Exact", analysis, exact, targets);
			assertDpSelectionUsesOpenLegalCandidateSpace(analysis, dp, targets);
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static FType sourceFType(int workers) throws Exception {
		DMLProgram program = als(workers);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(program);
		var source = analysis.graph().decisionNodes().stream()
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.findFirst().orElseThrow();
		List<FType> sourceTypes = source.anchors().stream().map(anchor -> anchor.fType())
			.distinct().toList();
		Assert.assertEquals("ALS source must publish one exact durable layout", 1, sourceTypes.size());
		return sourceTypes.get(0);
	}

	private static void assertDirectWdivmmRuntimeFact(int workers, FType expectedInput)
		throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(als(workers));
		List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).orElse(null) instanceof AggBinaryOp mm
				&& mm.isMatrixMultiply() && mm.getBeginLine() == 125)
			.filter(key -> PlacementCostSemantics.directWdivmmRuntimeFact(analysis, key) != null)
			.toList();
		Assert.assertFalse("ALS line 125 must expose direct Pattern-2 runtime facts", owners.isEmpty());
		for(CompiledHopKey owner : owners) {
			PlacementCostSemantics.DirectWdivmmRuntimeFact runtime =
				PlacementCostSemantics.directWdivmmRuntimeFact(analysis, owner);
			Assert.assertSame(owner, runtime.root());
			Assert.assertEquals(expectedInput, runtime.runtimeInputFType());
			Assert.assertFalse("RIGHT WDivMM over FULL/ROW has non-overlapping output",
				runtime.nativeOutputMustBeLocal());
			Assert.assertTrue("The common privacy-filtered owner domain must retain its executable FED state",
				analysis.graph().node(owner).orElseThrow().legalAlternatives().stream()
					.anyMatch(state -> state.execType() == ExecType.FED
						&& state.fType() == expectedInput));
			Assert.assertTrue("The exact W occurrence must own the runtime FederationMap",
				analysis.graph().node(runtime.weights()).orElseThrow().legalAlternatives().stream()
					.anyMatch(state -> state.execType() == ExecType.FED
						&& state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
							.FederatedOutput.FOUT
						&& state.fType() == expectedInput));
			Assert.assertEquals("The fused weighted intermediate must not retain source-level compute",
				0.0, PlacementCostSemantics.analysisAwareUnitLocalCost(
					analysis, runtime.weighted()), 0.0);
			Assert.assertEquals("The fused outer product must not retain source-level compute",
				0.0, PlacementCostSemantics.analysisAwareUnitLocalCost(
					analysis, runtime.outer()), 0.0);
			Assert.assertTrue("The surviving root must own the rank-aware WDivMM compute",
				PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, owner) > 0.0);
		}
	}

	private static List<CompiledHopKey> innerMaskReads(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).orElse(null) instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "W".equals(data.getName()))
			.filter(key -> key.controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body")))
			.toList();
	}

	private static List<CompiledHopKey> largeInnerElementwiseHops(PlacementAnalysis analysis,
			NormalizedPlannerResult result) {
		List<CompiledHopKey> targets = new ArrayList<>();
		for(CompiledHopKey key : result.selectedStates().keySet()) {
			var hop = analysis.hop(key).orElse(null);
			var shape = analysis.shapeFact(key).orElse(null);
			if(hop instanceof BinaryOp && "b(*)".equals(hop.getOpString())
				&& shape != null && shape.rows() == 50000 && shape.cols() == 2100
				&& key.controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body")))
				targets.add(key);
		}
		return targets;
	}

	private static void assertFederated(String planner, PlacementAnalysis analysis,
			NormalizedPlannerResult result, List<CompiledHopKey> targets) {
		List<String> states = targets.stream().map(key -> describe(analysis, key) + '='
			+ result.selectedStates().get(key)).toList();
		Assert.assertTrue(planner + " must price the partition-preserving ALS inner-CG elementwise stage"
			+ " as parallel worker work|states=" + states,
			targets.stream().allMatch(key -> result.selectedStates().get(key) != null
				&& result.selectedStates().get(key).execType() == ExecType.FED));
	}

	private static void assertDpSelectionUsesOpenLegalCandidateSpace(PlacementAnalysis analysis,
			NormalizedPlannerResult dp, List<CompiledHopKey> targets) {
		for(CompiledHopKey key : targets) {
			var alternatives = analysis.graph().node(key).orElseThrow().legalAlternatives();
			Assert.assertTrue("DP candidate space must retain the legal FED alternative for "
				+ describe(analysis, key) + "|alternatives=" + alternatives,
				alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED));
			var selected = dp.selectedStates().get(key);
			Assert.assertNotNull("DP must emit a selection for " + describe(analysis, key), selected);
			Assert.assertTrue("DP selection must be one of the common-analysis alternatives for "
				+ describe(analysis, key) + "|selected=" + selected + "|alternatives=" + alternatives,
				alternatives.contains(selected));
		}
	}

	private static String describe(PlacementAnalysis analysis, CompiledHopKey key) {
		var hop = analysis.hop(key).orElseThrow();
		return hop.getHopID() + ":" + hop.getName() + ':' + hop.getOpString()
			+ analysis.shapeFact(key).map(shape -> "[" + shape.rows() + 'x' + shape.cols() + "]")
				.orElse("[unknown]")
			+ '|' + key.controlRegion().regionPath();
	}

	static DMLProgram als(int workers) throws Exception {
		return als(workers, 2);
	}

	static DMLProgram als(int workers, int maxi) throws Exception {
		String script = federatedFeatures(workers) + String.join("\n",
			"[U,V]=als(X=X,rank=10,regType=\"L2\",reg=0.000001,maxi=" + maxi + ","
				+ "check=FALSE,thr=0.0001,seed=1389632218,verbose=FALSE);",
			"write(V,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String federatedFeatures(int workers) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = 50000L * worker / workers;
			long end = 50000L * (worker + 1) / workers;
			Path data = Files.createTempFile("g014-als-cost-w" + workers + "-p" + worker + '-', ".data");
			Path metadata = Path.of(data + ".mtd");
			Files.writeString(data, "");
			Files.writeString(metadata, "{\"data_type\":\"matrix\","
				+ "\"value_type\":\"double\",\"format\":\"binary\","
				+ "\"rows\":" + (end - begin) + ",\"cols\":2100,"
				+ "\"rows_in_block\":1000,\"cols_in_block\":1000,"
				+ "\"nnz\":" + ((end - begin) * 2001) + ','
				+ "\"privacy\":\"private-aggregate\"}");
			data.toFile().deleteOnExit();
			metadata.toFile().deleteOnExit();
			String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
			addresses.add("\"localhost:" + (12340 + worker) + '/' + path + "\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ",2100)");
		}
		return "X=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));\n";
	}

	private static Map<String,String> installWanLightCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "125"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.020"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"));
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	static Map<String,String> installWanMidCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "25"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "25"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "25"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.080"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"));
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}

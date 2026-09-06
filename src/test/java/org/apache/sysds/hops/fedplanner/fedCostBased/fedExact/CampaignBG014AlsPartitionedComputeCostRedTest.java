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
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
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
	public void reusableRuntimeMaterializationUsesControlFlowEventUnion() {
		var branchAIf = new ExactPhysicalCostModel.BranchLiteral("main/0", true);
		var branchAElse = new ExactPhysicalCostModel.BranchLiteral("main/0", false);
		var branchBIf = new ExactPhysicalCostModel.BranchLiteral("main/1", true);
		Assert.assertEquals("Duplicate consumers in one branch event share one transfer",
			0.5, ExactPhysicalCostModel.reusableActivationUnion(
				List.of(List.of(branchAIf), List.of(branchAIf)),
				List.of(0.5, 0.25), 1.0), 0.0);
		Assert.assertEquals("Mutually exclusive if/else arms cover the whole production",
			1.0, ExactPhysicalCostModel.reusableActivationUnion(
				List.of(List.of(branchAIf), List.of(branchAElse)),
				List.of(0.5, 0.5), 1.0), 0.0);
		Assert.assertEquals("Distinct sequential branch paths do not prove independence and"
			+ " therefore use the conservative union bound",
			1.0, ExactPhysicalCostModel.reusableActivationUnion(
				List.of(List.of(branchAIf), List.of(branchBIf)),
				List.of(0.5, 0.5), 1.0), 0.0);
	}

	@Test
	public void functionFormalLatentWdivmmKeepsOnlyTheRealRuntimeTransfer()
		throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			String script = String.join("\n",
				"latent=function(matrix[double] W)"
					+ " return(matrix[double] H){",
				"U=rand(rows=nrow(W),cols=10,seed=7);",
				"V=rand(rows=ncol(W),cols=10,seed=8);", "S=U;",
				"is_U=TRUE;", "i=1;", "while(i<=2){", "if(is_U){",
				"H=(W*(S%*%t(V)))%*%V;", "U=U+H;", "S=V;", "}", "else{",
				"H=t(t(U)%*%(W*(U%*%t(S))));", "V=V+H;", "S=U;", "}",
				"is_U=!is_U;", "i=i+1;", "}",
				"}", federatedFeatures(1),
				"H=latent(X);", "write(H,\"out\",format=\"csv\");") + "\n";
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(parseProgram(script));
			List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> PlacementCostSemantics
					.latentWdivmmTransposePairFact(analysis, key) != null)
				.toList();
			Assert.assertFalse("Function fixture must expose the latent WDivMM owner",
				owners.isEmpty());
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			for(CompiledHopKey owner : owners) {
				var runtime = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
				var functionInput = analysis.logicalFunctionInputsInCanonicalOrder().stream()
					.filter(fact -> analysis.hop(fact.sourceArgument()).orElse(null)
						instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
					.findFirst().orElseThrow();
				Assert.assertTrue("The caller-to-formal boundary remains a real transfer",
					surface.transferKeys().stream()
						.filter(key -> key.boundaryMode()
							== ExactPhysicalCostModel.BoundaryMode.ANCHOR_TRANSFER)
						.flatMap(key -> key.endpoints().stream())
						.anyMatch(endpoint -> endpoint.producer() == functionInput.sourceArgument()
							&& endpoint.consumer() == functionInput.targetRead()));
				Assert.assertTrue("The formal-to-lowered-subtree edge must not be charged again",
					surface.transferKeys().stream()
						.filter(key -> key.boundaryMode()
							== ExactPhysicalCostModel.BoundaryMode.ANCHOR_TRANSFER)
						.flatMap(key -> key.endpoints().stream())
						.noneMatch(endpoint -> endpoint.producer() == runtime.weights()
							&& endpoint.consumer() == runtime.weighted()
							&& endpoint.inputPosition() == 0));
			}
			Assert.assertEquals("One function argument value owns one reusable fused transfer",
				1L, surface.transferKeys().stream().filter(key -> key.boundaryMode()
					== ExactPhysicalCostModel.BoundaryMode.RUNTIME_FUSED_INPUT).count());
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

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
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(als(4));
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalSelection local = ExactPhysicalSelection.create(model,
				LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
			ExactPhysicalSelection exact = ExactPhysicalSelection.create(model,
				ExactPhysicalOptimizer.optimize(model, surface,
					ExactPhysicalOptimizer.PRODUCTION_LIMITS));

			Assert.assertEquals("Production local and exact optimizers must consume one shared"
				+ " physical cost surface", exact.costSurfaceFingerprint(),
				local.costSurfaceFingerprint());
			double tolerance = 1e-9 * Math.max(1.0, Math.abs(local.solverObjective()));
			Assert.assertTrue("Global exact cannot exceed the feasible production-local objective"
				+ "|exact=" + exact.solverObjective() + "|local=" + local.solverObjective(),
				exact.solverObjective() <= local.solverObjective() + tolerance);

			List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> analysis.hop(key).orElse(null) instanceof ReorgOp reorg
					&& reorg.getOp() == ReOrgOp.TRANS && reorg.getBeginLine() == 130)
				.filter(key -> PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, key) != null)
				.toList();
			Assert.assertFalse("ALS fixture did not expose the latent line-130 WDivMM owner",
				owners.isEmpty());
			for(CompiledHopKey owner : owners) {
				var runtime = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
				var alternatives = analysis.graph().node(owner).orElseThrow().legalAlternatives();
				Assert.assertTrue("Shared analysis must retain the runtime-native FED/LOUT/ROW arm"
					+ "|owner=" + describe(analysis, owner) + "|alternatives=" + alternatives,
					alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED
						&& state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
							.FederatedOutput.LOUT
						&& state.fType() == runtime.partitionedInputFType()));
				for(ExactPhysicalSelection selection : List.of(local, exact)) {
					var ownerState = selection.selectedStates().get(owner);
					var weightsState = selection.selectedStates().get(runtime.weights());
					Assert.assertNotNull("Physical optimizer must select the latent owner", ownerState);
					Assert.assertNotNull("Physical optimizer must select the exact runtime weights",
						weightsState);
					if(ownerState.execType() == ExecType.FED) {
						Assert.assertEquals("The selected native latent owner must keep its local result",
							org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
							ownerState.output());
						Assert.assertEquals("The selected native latent owner must use the proven"
							+ " partitioned runtime input layout", runtime.partitionedInputFType(),
							ownerState.fType());
						Assert.assertTrue("A selected FED latent owner requires its exact weights as"
							+ " matching FOUT runtime input|owner=" + ownerState
							+ "|weights=" + weightsState,
							weightsState.execType() == ExecType.FED
								&& weightsState.output()
									== org.apache.sysds.runtime.instructions.fed.FEDInstruction
										.FederatedOutput.FOUT
								&& weightsState.fType() == runtime.partitionedInputFType());
					}
				}
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
				for(CompiledHopKey shapeKey : List.of(owner, input.producer(), weighted.producer(), weights.producer())) {
					var conservative = analysis.shapeFact(shapeKey).orElseThrow();
					var sourceCompiled = analysis.sourceCompiledShapeFact(shapeKey).orElseThrow();
					Assert.assertTrue("Latent WDivMM source dimensions must be concrete",
						sourceCompiled.knownPositiveMatrix());
					Assert.assertTrue(conservative.rows() <= 0 || conservative.rows() == sourceCompiled.rows());
					Assert.assertTrue(conservative.cols() <= 0 || conservative.cols() == sourceCompiled.cols());
				}
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
	public void latentWdivmmSecondInnerParentDoesNotSynthesizeRuntimeClosure() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(3);
			List<Hop> hops = new ArrayList<>();
			var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Hop,Boolean>());
			for(var function : program.getFunctionStatementBlocks())
				walkBlock(function, hops, seen);
			List<ReorgOp> owners = hops.stream()
				.filter(hop -> hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS
					&& reorg.getBeginLine() == 130)
				.map(hop -> (ReorgOp) hop).toList();
			Assert.assertFalse("ALS fixture did not expose line-130 outer transpose", owners.isEmpty());
			int mutated = 0;
			for(ReorgOp owner : owners) {
				Hop inner = owner.getInput().get(0);
				if(inner instanceof AggBinaryOp) {
					inner.getParent().add(inner);
					mutated++;
				}
			}
			Assert.assertTrue("ALS fixture did not expose line-130 transpose-over-MM", mutated > 0);
			PlacementAnalysis hostile = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			for(CompiledHopKey owner : hostile.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> hostile.hop(key).orElse(null) instanceof ReorgOp reorg
					&& reorg.getOp() == ReOrgOp.TRANS && reorg.getBeginLine() == 130)
				.toList()) {
				var edge = hostile.compiledInputEdgesInCanonicalOrder().stream()
					.filter(input -> input.consumer() == owner && input.inputPosition() == 0)
					.findFirst().orElseThrow();
				if(!(hostile.hop(edge.producer()).orElseThrow() instanceof AggBinaryOp))
					continue;
				Assert.assertNull("A second inner-MM parent must break the exact latent rewrite proof",
					PlacementCostSemantics.latentWdivmmTransposePairFact(hostile, owner));
				Assert.assertTrue("An unproved latent owner must not receive the runtime-output closure",
					hostile.candidateRuleFacts().orderedFacts().stream()
						.filter(fact -> fact.key().parentOccurrence() == owner)
						.noneMatch(fact -> fact.capability().reasonCode()
							== org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode
								.FOUT_NOT_SUPPORTED_BY_RUNTIME
							&& fact.capability().detail().contains("dynamic transpose-pair")));
			}
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void singleWorkerAlsPricesOneReusableCpRuntimeWeightMaterialization() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(1);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			List<CompiledHopKey> owners = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.filter(key -> analysis.hop(key).orElse(null) instanceof ReorgOp reorg
					&& reorg.getOp() == ReOrgOp.TRANS && reorg.getBeginLine() == 130)
				.filter(key -> PlacementCostSemantics
					.latentWdivmmTransposePairFact(analysis, key) != null)
				.toList();
			Assert.assertFalse("ALS fixture did not expose the line-130 runtime owners", owners.isEmpty());
			double campaignMaterialization = 0.0;
			for(CompiledHopKey owner : owners) {
				var runtime = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
				var ownerStates = analysis.graph().node(owner).orElseThrow().legalAlternatives();
				var weightStates = analysis.graph().node(runtime.weights()).orElseThrow()
					.legalAlternatives();
				var cp = ownerStates.stream().filter(state -> state.execType() == ExecType.CP)
					.findFirst().orElseThrow();
				var fed = ownerStates.stream().filter(state -> state.execType() == ExecType.FED)
					.findFirst().orElseThrow();
				var federatedWeights = weightStates.stream().filter(state ->
					state.execType() == ExecType.FED
						&& state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
							.FederatedOutput.FOUT)
					.findFirst().orElseThrow();
				double weightBytes = 50000D * 2100D * 8D;
				double materialization = PlacementCostSemantics
					.latentWdivmmCpRuntimeInputMaterializationCost(
						weightBytes, cp, federatedWeights, 1);
				double expected = FederatedCostModel.computeReusableMaterializationDownloadCost(
					weightBytes, FType.FULL, 1);
				Assert.assertEquals("The runtime factor must charge the exact dense W payload through"
					+ " the shared reusable W2C materialization model",
					expected, materialization, 2.0);
				Assert.assertTrue("The fused CP runtime input must not remain a zero-cost boundary",
					materialization > 0.0);
				campaignMaterialization = Math.max(campaignMaterialization, materialization);
				Assert.assertEquals("A FED runtime owner consumes the fused FederationMap directly",
					0.0, PlacementCostSemantics.latentWdivmmCpRuntimeInputMaterializationCost(
						weightBytes, fed, federatedWeights, 1), 0.0);
				var boundaries = PlacementCostSemantics
					.latentWdivmmRuntimeTransferBoundaries(analysis);
				Assert.assertTrue("The removed inner-to-owner shell edge must not be generically costed",
					boundaries.contains(new PlacementCostSemantics.LatentWdivmmRuntimeTransferBoundary(
						runtime.inner(), owner, 0)));
				Assert.assertTrue("The removed weighted-to-inner edge must not be generically costed",
					boundaries.contains(new PlacementCostSemantics.LatentWdivmmRuntimeTransferBoundary(
						runtime.weighted(), runtime.inner(), 1)));
				Assert.assertTrue("The weights-to-weighted edge is replaced by the real runtime input",
					boundaries.contains(new PlacementCostSemantics.LatentWdivmmRuntimeTransferBoundary(
						runtime.weights(), runtime.weighted(), 0)));
			}

			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			long fusedTransfers = surface.transferKeys().stream().filter(key ->
				key.boundaryMode() == ExactPhysicalCostModel.BoundaryMode.RUNTIME_FUSED_INPUT)
				.count();
			Assert.assertEquals("All branch/loop owners of the same W value must share one"
				+ " reusable FULL materialization", 1L, fusedTransfers);
			List<ExactPhysicalCostModel.PhysicalContribution> fusedContributions =
				surface.contributions().stream()
					.filter(contribution -> contribution.id().contains("|RUNTIME_FUSED_INPUT|"))
					.toList();
			Assert.assertEquals("The reusable runtime transfer must be represented by exactly"
				+ " one objective contribution", 1, fusedContributions.size());
			List<Integer> forced = new ArrayList<>();
			for(int index = 0; index < model.domains().size(); index++)
				forced.add(0);
			CompiledHopKey source = surface.transferKeys().stream()
				.filter(key -> key.boundaryMode()
					== ExactPhysicalCostModel.BoundaryMode.RUNTIME_FUSED_INPUT)
				.findFirst().orElseThrow().endpoints().get(0).producer();
			setAlternative(model, forced, source, state ->
				state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
					.FederatedOutput.FOUT && state.fType() == FType.FULL);
			for(CompiledHopKey owner : owners) {
				var runtime = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
				setAlternative(model, forced, runtime.weights(), state ->
					state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction
						.FederatedOutput.FOUT && state.fType() == FType.FULL);
				setAlternative(model, forced, owner, state -> state.execType() == ExecType.CP);
			}
			double forcedContribution = surface.evaluateContributionCanonical(
				fusedContributions.get(0), forced);
			Assert.assertEquals("All runtime owners must reuse one W-to-coordinator"
				+ " materialization rather than multiplying it by loop/branch occurrences",
				campaignMaterialization, forcedContribution, 2.0);
			for(var boundary : PlacementCostSemantics
				.latentWdivmmRuntimeTransferBoundaries(analysis))
				Assert.assertTrue("A lowered WDivMM source edge must not reappear as a generic"
					+ " anchor transfer|boundary=" + boundary,
					surface.transferKeys().stream()
						.filter(key -> key.boundaryMode()
							== ExactPhysicalCostModel.BoundaryMode.ANCHOR_TRANSFER)
						.flatMap(key -> key.endpoints().stream())
						.noneMatch(endpoint -> endpoint.producer() == boundary.producer()
							&& endpoint.consumer() == boundary.consumer()
							&& endpoint.inputPosition() == boundary.inputPosition()));

			ExactPhysicalSelection exact = ExactPhysicalSelection.create(model,
				ExactPhysicalOptimizer.optimize(model, surface,
					ExactPhysicalOptimizer.PRODUCTION_LIMITS));
			ExactPhysicalSelection local = ExactPhysicalSelection.create(model,
				LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
			Assert.assertEquals("Exact and local-conflict DP must bind the identical physical"
				+ " cost surface, including the runtime-input materialization factor",
				exact.costSurfaceFingerprint(), local.costSurfaceFingerprint());
			for(CompiledHopKey owner : owners) {
				Assert.assertEquals("Exact must not select the CP owner after charging W2C",
					ExecType.FED, exact.selectedStates().get(owner).execType());
				Assert.assertNotNull("Local-conflict DP must retain an executable owner decision",
					local.selectedStates().get(owner));
				if(campaignMaterialization > 50_000.0)
					Assert.assertEquals("With the campaign's measured W2C codec calibration, local-conflict"
						+ " DP must reject the 54-second CP materialization",
						ExecType.FED, local.selectedStates().get(owner).execType());
			}
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static void setAlternative(ExactPhysicalModel model, List<Integer> assignment,
		CompiledHopKey key, Predicate<org.apache.sysds.hops.fedplanner.placement.PlacementState>
			predicate) {
		for(int domainIndex = 0; domainIndex < model.domains().size(); domainIndex++) {
			ExactPhysicalModel.DecisionDomain domain = model.domains().get(domainIndex);
			if(domain.node().key() != key)
				continue;
			for(int value = 0; value < domain.alternatives().size(); value++)
				if(predicate.test(domain.alternatives().get(value).state())) {
					assignment.set(domainIndex, value);
					return;
				}
			throw new AssertionError("No requested placement alternative for "
				+ key.normalizedSignature());
		}
		throw new AssertionError("No decision domain for " + key.normalizedSignature());
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
				.filter(key -> analysis.sourceCompiledShapeFact(key).map(shape ->
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
			var shape = analysis.sourceCompiledShapeFact(key).orElse(null);
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
		return parseProgram(script);
	}


	private static void walkBlock(org.apache.sysds.parser.StatementBlock block,
		List<Hop> result, java.util.Set<Hop> seen) {
		List<Hop> roots = new ArrayList<>();
		if(block.getHops() != null)
			roots.addAll(block.getHops());
		if(block instanceof org.apache.sysds.parser.IfStatementBlock conditional)
			roots.add(conditional.getPredicateHops());
		if(block instanceof org.apache.sysds.parser.WhileStatementBlock loop)
			roots.add(loop.getPredicateHops());
		if(block instanceof org.apache.sysds.parser.ForStatementBlock loop) {
			roots.add(loop.getFromHops()); roots.add(loop.getToHops()); roots.add(loop.getIncrementHops());
		}
		for(Hop root : roots)
			walkHop(root, result, seen);
		if(block instanceof org.apache.sysds.parser.FunctionStatementBlock)
			walkBlocks(((org.apache.sysds.parser.FunctionStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof org.apache.sysds.parser.WhileStatementBlock)
			walkBlocks(((org.apache.sysds.parser.WhileStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof org.apache.sysds.parser.ForStatementBlock)
			walkBlocks(((org.apache.sysds.parser.ForStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof org.apache.sysds.parser.IfStatementBlock) {
			var statement = (org.apache.sysds.parser.IfStatement) block.getStatement(0);
			walkBlocks(statement.getIfBody(), result, seen); walkBlocks(statement.getElseBody(), result, seen);
		}
	}

	private static void walkBlocks(List<org.apache.sysds.parser.StatementBlock> blocks,
		List<Hop> result, java.util.Set<Hop> seen) {
		if(blocks != null)
			for(var block : blocks)
				walkBlock(block, result, seen);
	}

	private static void walkHop(Hop hop, List<Hop> result, java.util.Set<Hop> seen) {
		if(hop == null || !seen.add(hop)) return;
		result.add(hop);
		for(Hop input : hop.getInput()) walkHop(input, result, seen);
		if(hop instanceof FunctionOp function && function.getOutputs() != null)
			for(Hop output : function.getOutputs()) walkHop(output, result, seen);
	}

	private static DMLProgram parseProgram(String script) throws Exception {
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

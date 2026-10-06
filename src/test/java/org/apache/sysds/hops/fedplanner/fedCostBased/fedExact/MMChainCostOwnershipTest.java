/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.apache.sysds.lops.MapMultChain;
import org.apache.sysds.lops.MapMultChain.ChainType;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.cp.MMChainCPInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class MMChainCostOwnershipTest {
	private static final String LOCAL =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "inner=X%*%v;\nout=t(X)%*%inner;\nprint(sum(out));\n";
	private static final String SHARED =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "inner=X%*%v;\nout=t(X)%*%inner;\nprint(sum(out)+sum(inner));\n";
	private static final String WEIGHTED =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "w=matrix(1,rows=10,cols=1);\ninner=X%*%v;\nweighted=w*inner;\n"
			+ "out=t(X)%*%weighted;\nprint(sum(out));\n";
	private static final String SUBTRACT_MATRIX =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "y=matrix(1,rows=10,cols=1);\ninner=X%*%v;\nresidual=inner-y;\n"
			+ "out=t(X)%*%residual;\nprint(sum(out));\n";
	private static final String SHARED_WEIGHTED =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "w=matrix(1,rows=10,cols=1);\ninner=X%*%v;\nweighted=w*inner;\n"
			+ "out=t(X)%*%weighted;\nprint(sum(out)+sum(weighted));\n";
	private static final String SUBTRACT_SCALAR =
		"X=matrix(1,rows=10,cols=4);\nv=matrix(1,rows=4,cols=1);\n"
			+ "inner=X%*%v;\nout=t(X)%*%(inner-1);\nprint(sum(out));\n";
	private static final String FEDERATED =
		"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(5,4),list(5,0),list(10,4)));\n"
			+ "v=matrix(1,rows=4,cols=1);\ninner=X%*%v;\nout=t(X)%*%inner;\nprint(sum(out));\n";

	@After
	public void resetPlannerState() {
		System.clearProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.SEMANTIC_OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.DIRECTORY_PROPERTY);
		PlacementEmissionTransaction.resetForTesting();
	}

	@Test
	public void exactLocalAssignmentTransfersExclusiveSourceKernelsToMMChain() throws Exception {
		Fixture fixture = fixture(LOCAL, false);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Assert.assertTrue(costs.get(fixture.key(fixture.owner())).localCost() > 0.0);
		Assert.assertTrue(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertTrue(costs.get(fixture.key(fixture.transpose())).removedKernel());
		Assert.assertEquals(0.0, costs.get(fixture.key(fixture.inner())).localCost(), 0.0);
		Assert.assertEquals(0.0, costs.get(fixture.key(fixture.transpose())).localCost(), 0.0);

		var selected = solveAndEmit(fixture.program(), fixture.analysis());
		Assert.assertEquals(ExecType.CP,
			selected.exactSelectedStates().get(fixture.key(fixture.owner())).execType());
		Assert.assertEquals(FederatedOutput.LOUT,
			selected.exactSelectedStates().get(fixture.key(fixture.owner())).output());
		Assert.assertEquals(ChainType.XtXv, fixture.owner().checkMapMultChain());
		Assert.assertTrue(fixture.owner().constructLops() instanceof MapMultChain);
		String instruction = fixture.owner().getLops().getInstructions("X", "v", "out");
		Assert.assertNotNull(MMChainCPInstruction.parseInstruction(instruction));
	}

	@Test
	public void sharedInnerKernelRetainsItsIndependentExecutionCost() throws Exception {
		Fixture fixture = fixture(SHARED, false);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Assert.assertFalse(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertTrue(costs.get(fixture.key(fixture.inner())).localCost() > 0.0);
		Assert.assertTrue("The unshared transpose remains owned by the fused outer kernel",
			costs.get(fixture.key(fixture.transpose())).removedKernel());
	}

	@Test
	public void weightedLocalChainTransfersAllExclusiveIntermediates() throws Exception {
		assertFusedLocalChain(WEIGHTED, ChainType.XtwXv);
	}

	@Test
	public void subtractMatrixLocalChainTransfersAllExclusiveIntermediates() throws Exception {
		assertFusedLocalChain(SUBTRACT_MATRIX, ChainType.XtXvy);
	}

	@Test
	public void sharedWeightedWrapperRetainsWrapperAndItsInnerDependency() throws Exception {
		Fixture fixture = fixture(SHARED_WEIGHTED, false);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Hop wrapper = fixture.owner().getInput(1);
		Assert.assertFalse(costs.get(fixture.key(wrapper)).removedKernel());
		Assert.assertFalse(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertTrue(costs.get(fixture.key(fixture.transpose())).removedKernel());
	}

	@Test
	public void scalarSubtractionDoesNotAcquireMMChainOwnership() throws Exception {
		Fixture fixture = fixture(SUBTRACT_SCALAR, false);
		Assert.assertEquals(ChainType.NONE, fixture.owner().checkMapMultChain());
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Assert.assertFalse(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertFalse(costs.get(fixture.key(fixture.transpose())).removedKernel());
	}

	@Test
	public void disabledSumProductRewriteKeepsSourceKernelCosts() throws Exception {
		boolean old = OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES;
		try {
			OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES = false;
			Fixture fixture = fixture(LOCAL, false);
			var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
			Assert.assertFalse(costs.get(fixture.key(fixture.inner())).removedKernel());
			Assert.assertFalse(costs.get(fixture.key(fixture.transpose())).removedKernel());
		}
		finally {
			OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES = old;
		}
	}

	@Test
	public void feasibleFederatedPlanWithExplicitFoutInputsKeepsAllSourceKernels() throws Exception {
		Fixture fixture = fixture(FEDERATED, true);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Assert.assertFalse(costs.get(fixture.key(fixture.owner())).removedKernel());
		Assert.assertFalse(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertFalse(costs.get(fixture.key(fixture.transpose())).removedKernel());

		System.setProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY,
			fixture.analysis().analysisFingerprint());
		System.setProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY,
			PlannerCandidateSpaceAudit.replayOccurrenceHash(fixture.key(fixture.owner())));
		System.setProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY,
			"PRESENT:COL,PRESENT:ROW");
		System.setProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY, "FED/LOUT/ROW");
		var selected = solveAndEmit(fixture.program(), fixture.analysis());
		Assert.assertEquals(ExecType.FED,
			selected.exactSelectedStates().get(fixture.key(fixture.owner())).execType());
		Assert.assertEquals(ChainType.NONE, fixture.owner().checkMapMultChain());
		Assert.assertFalse(fixture.owner().constructLops() instanceof MapMultChain);
		Assert.assertTrue(fixture.owner().getLops().getInstructions("Xt", "inner", "out")
			.startsWith("FED°ba+*°"));
	}

	private static org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput
			solveAndEmit(DMLProgram program, PlacementAnalysis analysis) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var optimized = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var projected = ExactPhysicalPlacementProjector.project(
			ExactPhysicalSelection.create(model, optimized));
		PlacementEmissionTransaction.emit(program, projected.normalizedResult(),
			PlacementEmissionTransaction.FailureInjector.none());
		return projected;
	}

	private static void assertFusedLocalChain(String script, ChainType expected) throws Exception {
		Fixture fixture = fixture(script, false);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		Assert.assertTrue(costs.get(fixture.key(fixture.owner())).localCost() > 0.0);
		Assert.assertTrue(costs.get(fixture.key(fixture.inner())).removedKernel());
		Assert.assertTrue(costs.get(fixture.key(fixture.transpose())).removedKernel());
		Hop wrapper = fixture.owner().getInput(1);
		Assert.assertTrue(costs.get(fixture.key(wrapper)).removedKernel());
		solveAndEmit(fixture.program(), fixture.analysis());
		Assert.assertEquals(expected, fixture.owner().checkMapMultChain());
		Assert.assertTrue(fixture.owner().constructLops() instanceof MapMultChain);
		Assert.assertNotNull(MMChainCPInstruction.parseInstruction(
			fixture.owner().getLops().getInstructions("X", "v", "w", "out")));
	}

	private static Fixture fixture(String script, boolean federated) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(null, script, null);
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(federated)
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(program);
		AggBinaryOp owner = analysis.occurrences().stream().map(o -> o.hop())
			.filter(hop -> hop instanceof AggBinaryOp && "out".equals(hop.getName()))
			.map(hop -> (AggBinaryOp)hop).findFirst().orElseThrow();
		AggBinaryOp inner = analysis.occurrences().stream().map(o -> o.hop())
			.filter(hop -> hop instanceof AggBinaryOp && "inner".equals(hop.getName()))
			.map(hop -> (AggBinaryOp)hop).findFirst().orElseThrow();
		ReorgOp transpose = (ReorgOp)owner.getInput(0);
		return new Fixture(program, analysis, owner, inner, transpose);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		AggBinaryOp owner, AggBinaryOp inner, ReorgOp transpose) {
		CompiledHopKey key(Hop hop) {
			return analysis.occurrences().stream().filter(o -> o.hop() == hop)
				.map(o -> o.key()).findFirst().orElseThrow();
		}
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class JointBoundaryPhysicalModelProofTest {
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void correlatedAaBbValueMapPlanIsAdmitted() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var consumerKey = binaryConsumer(analysis);
		Assert.assertTrue("joint consumer must publish VALUE_MAP|" + diagnostics(analysis, consumerKey),
			analysis.candidateRuleFacts().orderedFactsForParent(consumerKey).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.flatMap(emission -> emission.realizations().stream())
				.anyMatch(realization -> realization.key().layoutKind() == PlacementLayoutKind.VALUE_MAP));
		ExactCategoricalSolver.Result solved =
			model.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		ExactPhysicalModel.DecisionDomain consumer = model.domains().stream()
			.filter(domain -> domain.node().key() == consumerKey).findFirst().orElseThrow();
		int domainIndex = model.domains().indexOf(consumer);
		var selected = consumer.alternatives().get(solved.assignmentInVariableOrder().get(domainIndex));
		Assert.assertNotNull(selected.realization());
		Assert.assertEquals(PlacementLayoutKind.VALUE_MAP, selected.realization().key().layoutKind());
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model,
			new ExactPhysicalOptimizer.Result(solved, Double.doubleToRawLongBits(0.0),
				"joint-boundary-proof"));
		Assert.assertTrue(selection.candidateReceipts().stream().anyMatch(receipt ->
			receipt.rule().parentOccurrence() == consumerKey
				&& receipt.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP));
	}

	private static String diagnostics(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey consumer) {
		return analysis.candidateRuleFacts().orderedFactsForParent(consumer).stream().map(fact ->
			fact.status() + ":cap=" + fact.capability() + ":profile=" + fact.profile() + ":"
				+ fact.key().orderedInputs() + ":" + fact.allowedEmissionFacts().stream()
				.flatMap(emission -> emission.realizations().stream())
				.map(realization -> realization.key().layoutKind().name()).toList()).toList().toString();
	}

	@Test
	public void independentAbBaRowsAreRejected() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		assertPhysicalInfeasible(() ->
			model.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	@Test
	public void twoFunctionCallsProjectCorrelatedValueMapsThroughBoundaries() throws Exception {
		PlacementAnalysis analysis = analysis(
			"f=function(matrix[double] A,matrix[double] B) return (matrix[double] C){"
				+ "i=1;while(i<1){i=i+1;}C=A+B;}" + SOURCES
				+ "C1=f(X,X);C2=f(Y,Y);print(sum(C1)+sum(C2));");
		var consumerKey = binaryConsumer(analysis, "A", "B");
		Assert.assertTrue("shared function body must retain call-correlated input maps|"
			+ diagnostics(analysis, consumerKey) + "|relations="
			+ org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.from(analysis),
			hasValueMap(analysis, consumerKey));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactCategoricalSolver.Result solved =
			model.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertNotNull(solved);
		var mappedResults = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& ("C1".equals(data.getName()) || "C2".equals(data.getName())))
			.filter(occurrence -> hasValueMap(analysis, occurrence.key()))
			.map(occurrence -> ((DataOp) occurrence.hop()).getName())
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertEquals("both call-result aliases must retain their selected VALUE_MAP",
			java.util.Set.of("C1", "C2"), mappedResults);
	}

	@Test
	public void twoCallsMayReuseTheSameArgumentOccurrencesAtDistinctBoundaries() throws Exception {
		PlacementAnalysis analysis = analysis(
			"poolJoin=function(matrix[double] PA1,matrix[double] PA2,"
				+ "matrix[double] PB1,matrix[double] PB2,boolean chooseA)"
				+ " return (matrix[double] R){if(chooseA){FU=PA1;FV=PA2;}"
				+ "else{FU=PB1;FV=PB2;}R=FU+FV;}" + SOURCES
				+ "C=poolJoin(X,X,Y,Y,TRUE);D=poolJoin(X,X,Y,Y,FALSE);"
				+ "print(sum(C)+sum(D));");
		for(var fact : analysis.logicalFunctionInputsInCanonicalOrder())
			Assert.assertSame(fact, analysis.requireExactLogicalFunctionInput(fact));
		var owned = analysis.logicalFunctionInputsInCanonicalOrder().get(0);
		var copied = new PlacementAnalysis.LogicalFunctionInputFact(owned.sourceArgument(),
			owned.boundary(), owned.targetRead(), owned.callInputPosition(), owned.logicalPosition(),
			owned.sourceValueVersion(), owned.boundaryValueVersion(), owned.readValueVersion());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> analysis.requireExactLogicalFunctionInput(copied));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertNotNull(LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
	}

	@Test
	public void rewrittenFullFunctionCallsRetainExactFormalAuthority() throws Exception {
		String fullSources =
			"A1=federated(addresses=list(\"localhost:23334/A1\"),ranges=list(list(0,0),list(8,3)));"
				+ "A2=federated(addresses=list(\"localhost:23334/A2\"),ranges=list(list(0,0),list(8,3)));"
				+ "B1=federated(addresses=list(\"localhost:23335/B1\"),ranges=list(list(0,0),list(8,3)));"
				+ "B2=federated(addresses=list(\"localhost:23335/B2\"),ranges=list(list(0,0),list(8,3)));";
		PlacementAnalysis analysis = analysis(
			"poolJoin=function(matrix[double] PA1,matrix[double] PA2,"
				+ "matrix[double] PB1,matrix[double] PB2,boolean chooseA)"
				+ " return (matrix[double] R){if(chooseA){FU=PA1;FV=PA2;}"
				+ "else{FU=PB1;FV=PB2;}R=FU+FV;}" + fullSources
				+ "C=poolJoin(A1,A2,B1,B2,TRUE);D=poolJoin(A1,A2,B1,B2,FALSE);"
				+ "print(sum(C)+sum(D));", true, Privacy.PRIVATE_AGGREGATE, true);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertNotNull(LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
	}

	@Test
	public void functionLoopWithLocalOperandReachesFiniteValueMapClosure() throws Exception {
		PlacementAnalysis analysis = analysis(
			"f=function(matrix[double] A) return (matrix[double] C){"
				+ "C=A;i=1;while(i<3){D=matrix(1,rows=8,cols=2);C=C+D;i=i+1;}}"
				+ SOURCES + "C1=f(X);C2=f(Y);print(sum(C1)+sum(C2));");
		var mappedResults = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& ("C1".equals(data.getName()) || "C2".equals(data.getName())))
			.filter(occurrence -> hasValueMap(analysis, occurrence.key()))
			.map(occurrence -> ((DataOp) occurrence.hop()).getName())
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertEquals(java.util.Set.of("C1", "C2"), mappedResults);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertNotNull(LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
	}

	@Test
	public void loopBranchRowsRemainGroundedWithoutCyclicSelfProof() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "A=X;B=X;i=1;while(i<=2){if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}i=i+1;}"
			+ "C=A+B;print(sum(C));");
		var consumerKey = binaryConsumer(analysis);
		Assert.assertTrue("loop exit consumer must retain grounded VALUE_MAP|"
			+ diagnostics(analysis, consumerKey), hasValueMap(analysis, consumerKey));
		Assert.assertNotNull(ExactPhysicalModel.build(analysis)
			.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	@Test
	public void normalizedNestedBranchPreservesOriginCorrelation() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "if(p>0.5){if(q>0.5){A=X;B=X;}else{A=Y;B=Y;}}else{A=X;B=X;}"
			+ "C=A+B;print(sum(C));", true);
		var consumerKey = binaryConsumer(analysis);
		Assert.assertTrue("normalized nested consumer must retain VALUE_MAP|"
			+ diagnostics(analysis, consumerKey), hasValueMap(analysis, consumerKey));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertNotNull(LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
	}

	@Test
	public void normalizedIndependentBranchesCannotBorrowCorrelatedOrigins() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));", true);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		assertPhysicalInfeasible(() ->
			ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	private static void assertPhysicalInfeasible(Runnable solve) {
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class, solve::run);
		String message = String.valueOf(failure.getMessage());
		Assert.assertFalse("resource overflow is not proof of an illegal joint assignment: " + message,
			message.contains("OVERFLOW") || message.contains("LIMIT_EXCEEDED"));
		Assert.assertTrue("expected a physical infeasibility diagnosis: " + message,
			message.contains("INFEASIBLE") || message.contains("NO_FEASIBLE_ASSIGNMENT"));
	}

	@Test
	public void oneDynamicFederatedInputAndLocalOperandPreserveValueMap() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "if(p>0.5){U=X;}else{U=Y;}V=matrix(1,rows=8,cols=2);"
			+ "Z=U+V;print(sum(Z));");
		var consumerKey = binaryConsumerWithInput(analysis, "U");
		Assert.assertTrue("one FED input plus a local operand must retain the selected map|"
			+ diagnostics(analysis, consumerKey), hasValueMap(analysis, consumerKey));
		Assert.assertNotNull(ExactPhysicalModel.build(analysis)
			.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	private static boolean hasValueMap(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey owner) {
		return analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.anyMatch(realization -> realization.key().layoutKind() == PlacementLayoutKind.VALUE_MAP);
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		return analysis(script, false);
	}

	private static PlacementAnalysis analysis(String script, boolean normalizeBranches) throws Exception {
		return analysis(script, normalizeBranches, Privacy.PRIVATE_AGGREGATE);
	}

	private static PlacementAnalysis analysis(String script, boolean normalizeBranches,
		Privacy privacy) throws Exception {
		return analysis(script, normalizeBranches, privacy, false);
	}

	private static PlacementAnalysis analysis(String script, boolean normalizeBranches,
		Privacy privacy, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		if(normalizeBranches)
			BranchPlacementNormalization.prepare(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey
		binaryConsumer(PlacementAnalysis analysis) {
		return binaryConsumer(analysis, "A", "B");
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey
		binaryConsumer(PlacementAnalysis analysis, String left, String right) {
		return analysis.compiledHopOccurrences().stream().filter(occurrence ->
			occurrence.hop() instanceof BinaryOp && occurrence.hop().getInput().size() == 2
				&& occurrence.hop().getInput().stream().allMatch(input -> input instanceof DataOp data
					&& (left.equals(data.getName()) || right.equals(data.getName()))))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow();
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey
		binaryConsumerWithInput(PlacementAnalysis analysis, String inputName) {
		return analysis.compiledHopOccurrences().stream().filter(occurrence ->
			occurrence.hop() instanceof BinaryOp && occurrence.hop().getInput().size() == 2
				&& occurrence.hop().getInput().stream().anyMatch(input ->
					input instanceof DataOp data && inputName.equals(data.getName())))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow();
	}
}

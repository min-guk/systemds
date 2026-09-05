/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for a single-input FED consumer of a function-returned transient value. */
public class CampaignBG014ExactSingleInputDirectFoutTest {
	@Test
	public void aggregateOverFunctionOutputRetainsDirectFedLoutAlternative() throws Exception {
		// Test direct unary consumption independently of recode release authority.
		// All numeric sources remain PRIVATE_AGGREGATE; no experimental DML changes.
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(false));
		var aggregateEdges = analysis.compiledInputEdgesInCanonicalOrder().stream().filter(candidate -> {
			var producer = analysis.hop(candidate.producer()).orElse(null);
			var consumer = analysis.hop(candidate.consumer()).orElse(null);
			return producer instanceof DataOp && consumer instanceof AggUnaryOp;
		}).toList();
		var federatedAggregateEdges = aggregateEdges.stream().filter(candidate ->
			// Rewrites rename the split results to _sbcvar*. Identify label
			// vectors by shared shape facts, not an unstable lexical variable name.
			analysis.shapeFact(candidate.producer()).map(shape -> shape.cols() == 1).orElse(false)
				&& analysis.graph().node(candidate.producer()).orElseThrow().legalAlternatives().stream()
				.anyMatch(state -> state.output() == FederatedOutput.FOUT)).toList();
		Assert.assertEquals("split must expose both federated label outputs; aggregate inputs="
			+ aggregateEdges.stream().map(edge -> analysis.hop(edge.producer()).orElseThrow().getOpString()
				+ "/" + analysis.hop(edge.producer()).orElseThrow().getName()
				+ " -> " + analysis.hop(edge.consumer()).orElseThrow().getOpString()
				+ " privacy=" + analysis.requirePrivacy(edge.producer())
				+ " domain=" + analysis.graph().node(edge.producer()).orElseThrow().legalAlternatives()).toList(), 2,
			federatedAggregateEdges.size());

		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var selected = ExactPhysicalSelection.create(model, ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		for(var edge : federatedAggregateEdges) {
			var domain = model.domains().stream()
				.filter(candidate -> candidate.node().key() == edge.consumer())
				.findFirst().orElseThrow();
			var fed = domain.alternatives().stream().filter(alternative ->
				alternative.state().execType() == ExecType.FED
					&& alternative.state().output() == FederatedOutput.LOUT).toList();

			Assert.assertFalse("a unary FED operation executes directly on its sole FOUT input", fed.isEmpty());
			Assert.assertTrue("the sole matrix input must retain exact direct-FOUT authority",
				fed.stream().anyMatch(alternative -> alternative.inputAuthorities().stream().anyMatch(authority ->
					authority.inputPosition() == edge.inputPosition()
						&& authority.kind() == InputAuthorityKind.DIRECT_FOUT
						&& authority.sourceDecision() == edge.producer())));
			Assert.assertEquals("the shared cost model must avoid collecting a full label split before sum",
				ExecType.FED, selected.selectedStates().get(edge.consumer()).execType());
			Assert.assertEquals(FederatedOutput.LOUT,
				selected.selectedStates().get(edge.consumer()).output());
		}
	}

	@Test
	public void privateAggregateRecodeCannotBorrowNativeDirectFoutReleaseAuthority() throws Exception {
		// Keep the original recode pipeline as a negative privacy regression:
		// native encoding still exchanges distinct keys even if M is not printed.
		DMLProgram program = compile(true);
		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("No privacy-safe physical placement"));
		Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("privacy=PRIVATE_AGGREGATE"));
		Assert.assertTrue("The rejected authority must be the recode metadata, not an unrelated operation: "
			+ failure.getMessage(), failure.getMessage().contains("FunOut M"));
	}

	private static DMLProgram compile(boolean recode) throws Exception {
		String script = String.join("\n",
			"Xraw=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(50000,1001),list(50000,0),list(100000,1001)));",
			recode ? "Fall=as.frame(Xraw);"
				+ "[X0,M]=transformencode(target=Fall,spec=\"{ ids:true, dummycode:[1] }\");" : "X0=Xraw;",
			"Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
				+ "ranges=list(list(0,0),list(50000,1),list(50000,0),list(100000,1)));",
			"colSD=colSds(X0);",
			"colMean=colMeans(X0);",
			"upperBound=colMean+1.5*colSD;",
			"lowerBound=colMean-1.5*colSD;",
			"outFilter=(X0<lowerBound)|(X0>upperBound);",
			"X=X0-outFilter*X0+outFilter*colMean;",
			"X=scale(X=X,center=TRUE,scale=TRUE);",
			"[Xtrain,Xtest,ytrain,ytest]=split(X=X,Y=Y,f=0.7,cont=FALSE,seed=2026072701);",
			"print(sum(Xtrain)+sum(Xtest)+sum(ytrain)+sum(ytest));") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program,
			Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for a single-input FED consumer of a function-returned transient value. */
public class CampaignBG014ExactSingleInputDirectFoutTest {
	@Test
	public void aggregateOverFunctionOutputRetainsDirectFedLoutAlternative() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile());
		var aggregateEdges = analysis.compiledInputEdgesInCanonicalOrder().stream().filter(candidate -> {
			var producer = analysis.hop(candidate.producer()).orElse(null);
			var consumer = analysis.hop(candidate.consumer()).orElse(null);
			return producer instanceof DataOp && consumer instanceof AggUnaryOp;
		}).toList();
		var federatedAggregateEdges = aggregateEdges.stream().filter(candidate ->
			analysis.hop(candidate.producer()).orElseThrow().getName().toLowerCase().startsWith("y")
				&& analysis.graph().node(candidate.producer()).orElseThrow().legalAlternatives().stream()
				.anyMatch(state -> state.output() == FederatedOutput.FOUT)).toList();
		Assert.assertEquals("split must expose both federated label outputs", 2,
			federatedAggregateEdges.size());

		var model = ExactPhysicalModel.build(analysis);
		var transform = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof FunctionOp function
				&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				&& "transformencode".equalsIgnoreCase(function.getFunctionName()))
			.findFirst().orElseThrow();
		Assert.assertEquals(NodeKind.OPERATION,
			analysis.graph().node(transform.key()).orElseThrow().kind());
		var transformDomain = model.domains().stream()
			.filter(domain -> domain.node().key() == transform.key()).findFirst().orElseThrow();
		Assert.assertTrue("Exact must expose runtime-native transformencode on its direct frame FederationMap",
			transformDomain.alternatives().stream().anyMatch(alternative ->
				alternative.state().execType() == ExecType.FED
					&& alternative.state().output() == FederatedOutput.FOUT
					&& alternative.state().fType() == FType.ROW
					&& alternative.inputAuthorities().stream().anyMatch(authority ->
						authority.inputPosition() == 0
							&& authority.kind() == InputAuthorityKind.DIRECT_FOUT
							&& authority.sourceDecision() != null
							&& analysis.hop(authority.sourceDecision()).orElseThrow()
								.getDataType().isFrame())));
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

	private static DMLProgram compile() throws Exception {
		String script = String.join("\n",
			"Xraw=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(50000,1001),list(50000,0),list(100000,1001)));",
			"Fall=as.frame(Xraw);",
			"[X0,M]=transformencode(target=Fall,spec=\"{ ids:true, dummycode:[1] }\");",
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

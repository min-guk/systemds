/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicNativeContinuationFact;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact candidate contract for a local operand consumed by a native federated instruction. */
public class HeuristicNativeContinuationContractTest {
	@Test
	public void absentLocalCandidateNeedsNoSyntheticRelocationFact() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(script()));
		var reusable = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).findFirst().orElseThrow();

		HeuristicNativeContinuationFact nativeContinuation =
			NeutralPlacementGraphBuilder.exactHeuristicNativeContinuation(analysis.graph(),
				analysis.compiledInputEdgesInCanonicalOrder(),
				analysis.candidateRuleFacts().orderedFacts(), reusable.localProducer(),
				reusable.consumer(), reusable.inputPosition());

		Assert.assertNotNull("the exact ABSENT_LOCAL runtime row must be independently provable",
			nativeContinuation);
		Assert.assertEquals(CandidateInputState.absentLocal(),
			nativeContinuation.runtimeCandidate().key().orderedInputs()
				.get(nativeContinuation.localInputPosition()));
		Assert.assertEquals(1, nativeContinuation.runtimeCandidate().key().orderedInputs().stream()
			.filter(CandidateInputState::present).count());
		Assert.assertEquals(FederatedOutput.FOUT, nativeContinuation.siblingFoutState().output());
		Assert.assertEquals(FederatedOutput.FOUT, nativeContinuation.consumerFoutState().output());
		Assert.assertNotEquals("an ABSENT_LOCAL continuation is not the reusable upload row",
			reusable.runtimeCandidate(), nativeContinuation.runtimeCandidate());
		Assert.assertSame(analysis.requireExactCompiledInputEdge(nativeContinuation.localProducer(),
			nativeContinuation.consumer(), nativeContinuation.localInputPosition()).consumer(),
			nativeContinuation.consumer());
		Assert.assertSame(analysis.requireExactCompiledInputEdge(nativeContinuation.siblingProducer(),
			nativeContinuation.consumer(), nativeContinuation.siblingInputPosition()).consumer(),
			nativeContinuation.consumer());
	}

	@Test
	public void derivedTableResidencyCanContinueWithoutSourceAnchor() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compile(slicefinderPrefixScript()));
		HeuristicNativeContinuationFact continuation = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.nativeContinuations().stream())
			.filter(fact -> fact.consumer().canonicalSourceOrigin().contains(":RMEMPTY:X2"))
			.findFirst().orElseThrow();
		var sibling = analysis.graph().node(continuation.siblingProducer()).orElseThrow();

		Assert.assertTrue("table changes geometry and therefore has no source-data durable anchor",
			sibling.anchors().isEmpty());
		Assert.assertEquals(FederatedOutput.FOUT, continuation.siblingFoutState().output());
		Assert.assertEquals(continuation.siblingFoutState().fType(),
			continuation.consumerFoutState().fType());
		Assert.assertEquals(CandidateInputState.absentLocal(),
			continuation.runtimeCandidate().key().orderedInputs()
				.get(continuation.localInputPosition()));
	}

	private static String script() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "keep=z>0;",
			"Y=removeEmpty(target=X,margin=\"rows\",select=keep);", "print(sum(Y));") + "\n";
	}

	private static String slicefinderPrefixScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"e=federated(addresses=list(\"localhost:1234/e1\",\"localhost:1235/e2\"),"
				+ "ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"m=nrow(X);", "n=ncol(X);", "fdom=colMaxs(X);",
			"foffb=t(cumsum(t(fdom)))-fdom;", "foffe=t(cumsum(t(fdom)));",
			"rix=matrix(seq(1,m)%*%matrix(1,1,n),m*n,1);",
			"cix=matrix(X+foffb,m*n,1);",
			"X2=table(rix,cix,1,m,as.scalar(foffe[,n]),FALSE);",
			"cCnts=t(colSums(X2));", "err=t(t(e)%*%X2);",
			"selCols=(cCnts>=1 & err>0);",
			"X2=removeEmpty(target=X2,margin=\"cols\",select=t(selCols));",
			"print(sum(X2));") + "\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}

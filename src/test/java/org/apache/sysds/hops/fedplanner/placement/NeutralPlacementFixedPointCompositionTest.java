/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class NeutralPlacementFixedPointCompositionTest {
	private static final String SOURCE = "X=federated(addresses=list(\"localhost:18101/X1\","
		+ "\"localhost:18102/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n";
	private static final String TRANSIENT_CFG = SOURCE
		+ "a=X+1; gate=matrix(1,rows=1,cols=1);\n"
		+ "if(sum(gate)>0) { z=a+2; } else { z=a+3; }\n"
		+ "print(sum(z));\n";
	private static final String FUNCTION = "f=function(matrix[double] A) return (matrix[double] B) {\n"
		+ " B=t(A)%*%A;\n}\n"
		+ SOURCE + "Y=f(X); print(sum(Y));\n";
	private static final String ACTIONS = SOURCE
		+ "p=matrix(1,rows=2,cols=1); pred=X%*%p; grad=t(X)%*%pred; print(sum(grad));\n";

	@Test
	public void representativeCompositionsTerminateAtStablePassWithinDeclaredBound() throws Exception {
		for(String script : List.of(TRANSIENT_CFG, FUNCTION, ACTIONS)) {
			List<NeutralPlacementGraphBuilder.FixedPointPass> trace = new ArrayList<>();
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder(trace::add)
				.buildAnalysis(compileProtected(script));
			Assert.assertFalse(analysis.graph().nodes().isEmpty());
			if(ACTIONS.equals(script))
				Assert.assertTrue("action fixture must exercise relocation or derived-FOUT publication",
					!analysis.graph().relocationActions().isEmpty()
						|| !analysis.graph().derivedFoutMaterializationActions().isEmpty());
			assertAllPhasesConverged(trace);
		}
	}

	@Test
	public void rebuildingTheSameCompiledProgramIsIdempotent() throws Exception {
		DMLProgram program = compileProtected(FUNCTION);
		List<NeutralPlacementGraphBuilder.FixedPointPass> firstTrace = new ArrayList<>();
		List<NeutralPlacementGraphBuilder.FixedPointPass> secondTrace = new ArrayList<>();
		PlacementAnalysis first = new NeutralPlacementGraphBuilder(firstTrace::add).buildAnalysis(program);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder(secondTrace::add).buildAnalysis(program);

		Assert.assertEquals(first.analysisFingerprint(), second.analysisFingerprint());
		Assert.assertEquals(first.graph().normalizedSignatureWithLegalAssignments(),
			second.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(first.candidateRuleFacts().orderedFacts(), second.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(first.logicalTransientInputsInCanonicalOrder(),
			second.logicalTransientInputsInCanonicalOrder());
		Assert.assertEquals(firstTrace, secondTrace);
		assertAllPhasesConverged(firstTrace);
	}

	@Test
	public void independentStatementOrderPreservesSemanticFixedPointSnapshot() throws Exception {
		String firstOrder = SOURCE + "a=X+1; b=X+2; print(sum(a)+sum(b));\n";
		String reverseOrder = SOURCE + "b=X+2; a=X+1; print(sum(a)+sum(b));\n";
		List<NeutralPlacementGraphBuilder.FixedPointPass> firstTrace = new ArrayList<>();
		List<NeutralPlacementGraphBuilder.FixedPointPass> reverseTrace = new ArrayList<>();
		PlacementAnalysis first = new NeutralPlacementGraphBuilder(firstTrace::add)
			.buildAnalysis(compileProtected(firstOrder));
		PlacementAnalysis reverse = new NeutralPlacementGraphBuilder(reverseTrace::add)
			.buildAnalysis(compileProtected(reverseOrder));

		Assert.assertEquals(semanticSnapshot(first), semanticSnapshot(reverse));
		assertAllPhasesConverged(firstTrace);
		assertAllPhasesConverged(reverseTrace);
	}

	private static void assertAllPhasesConverged(List<NeutralPlacementGraphBuilder.FixedPointPass> trace) {
		for(String phase : List.of("cfg-refinement", "function-boundary", "semantic", "publication")) {
			List<NeutralPlacementGraphBuilder.FixedPointPass> passes = trace.stream()
				.filter(pass -> phase.equals(pass.phase())).toList();
			Assert.assertFalse("missing fixed-point trace for " + phase, passes.isEmpty());
			NeutralPlacementGraphBuilder.FixedPointPass last = passes.get(passes.size() - 1);
			Assert.assertTrue("last pass must be an idempotent replay for " + phase, last.stable());
			Assert.assertTrue("phase exceeded its declared termination bound: " + last,
				last.pass() >= 0 && last.pass() < last.passLimit());
		}
	}

	private static List<String> semanticSnapshot(PlacementAnalysis analysis) {
		List<String> rows = new ArrayList<>();
		for(var node : analysis.graph().nodes()) {
			String op = analysis.hop(node.key()).map(hop -> hop.getClass().getSimpleName() + ':' + hop.getOpString())
				.orElse(node.kind().name());
			rows.add("NODE|" + op + '|' + node.emittedWork() + '|'
				+ node.legalAlternatives().stream().map(PlacementState::normalizedSignature).sorted().toList());
		}
		rows.add("COUNTS|candidates=" + analysis.candidateRuleFacts().orderedFacts().size()
			+ "|logical=" + analysis.logicalTransientInputsInCanonicalOrder().size()
			+ "|relocations=" + analysis.graph().relocationActions().size()
			+ "|derived=" + analysis.graph().derivedFoutMaterializationActions().size());
		return rows.stream().sorted().toList();
	}

	private static DMLProgram compileProtected(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}

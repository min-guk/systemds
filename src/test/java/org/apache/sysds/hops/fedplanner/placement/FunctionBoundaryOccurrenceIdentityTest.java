/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.FunctionBoundaryOccurrenceFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for repeated calls that bind the same immutable actual to one formal. */
public class FunctionBoundaryOccurrenceIdentityTest {
	@Test
	public void repeatedSameSourceAndFormalRetainDistinctCallOccurrences() throws Exception {
		PlacementAnalysis analysis = analyze("""
			guarded=function(matrix[double] M, boolean flag) return (double out) {
				if(flag) { B=M+1; } else { B=M-1; }
				i=1; while(i<=2) { B=B+1; i=i+1; }
				out=sum(B);
			}
			P=rand(rows=4,cols=2,seed=7);
			out=guarded(P,TRUE)+guarded(P,FALSE);
			print(out);
			""");

		List<LogicalFunctionInputFact> matrixFacts = analysis.logicalFunctionInputsInCanonicalOrder()
			.stream().filter(fact -> analysis.hop(fact.sourceArgument()).orElseThrow()
				.getDataType().isMatrix()).toList();
		Assert.assertEquals("two calls bind the formal read in each branch", 4, matrixFacts.size());
		Assert.assertEquals("fixture passes one compiled actual to every binding", 1,
			matrixFacts.stream().map(LogicalFunctionInputFact::sourceArgument).distinct().count());
		Map<CompiledHopKey,List<LogicalFunctionInputFact>> byBoundary = matrixFacts.stream()
			.collect(Collectors.groupingBy(LogicalFunctionInputFact::boundary,
				LinkedHashMap::new, Collectors.toList()));
		Assert.assertEquals("each physical call must retain its own boundary", 2, byBoundary.size());
		Assert.assertTrue("both branch reads belong to each call boundary",
			byBoundary.values().stream().allMatch(facts -> facts.size() == 2));

		Map<CompiledHopKey,Long> contextByCall = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey,List<LogicalFunctionInputFact>> entry : byBoundary.entrySet()) {
			CompiledHopKey call = null;
			Long context = null;
			for(LogicalFunctionInputFact fact : entry.getValue()) {
				CompiledHopKey resolvedCall = analysis.requireExactPhysicalFunctionInputConsumer(fact);
				List<FunctionBoundaryOccurrenceFact> occurrences = analysis.executionFrequencyFacts()
					.exactFunctionBoundaryOccurrences(fact);
				Assert.assertEquals(1, occurrences.size());
				Assert.assertEquals("each logical call executes once", 1.0,
					analysis.executionFrequencyFacts().logicalFunctionCallWeight(fact), 0.0);
				if(call == null) {
					call = resolvedCall;
					context = occurrences.get(0).calleeProfile().contextOrdinal();
				}
				else {
					Assert.assertEquals("one boundary resolves every formal read to one call", call,
						resolvedCall);
					Assert.assertEquals("one call shares one callee context across its formal reads",
						context.longValue(), occurrences.get(0).calleeProfile().contextOrdinal());
				}
			}
			contextByCall.put(call, context);
		}
		Assert.assertEquals("two boundary identities resolve to two physical calls", 2,
			contextByCall.size());
		Assert.assertEquals("two physical calls retain two callee contexts", 2,
			contextByCall.values().stream().distinct().count());
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

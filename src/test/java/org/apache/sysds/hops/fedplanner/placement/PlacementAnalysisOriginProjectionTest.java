/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Independent statement-block/Hop traversal oracle for the production analysis projection. */
public class PlacementAnalysisOriginProjectionTest {
	@Test
	public void everyGraphKeyProjectsToItsExactIndependentlyTraversedCompiledOrigin() throws Exception {
		for(String fixture : List.of("B-07", "B-17", "B-21")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			List<Hop> independentlyTraversed = allHops(program);
			Set<Hop> identities = Collections.newSetFromMap(new IdentityHashMap<>());
			identities.addAll(independentlyTraversed);
			Map<Hop,Integer> expectedMultiplicity = new IdentityHashMap<>();
			for(Hop hop : independentlyTraversed)
				expectedMultiplicity.put(hop, hop instanceof FunctionOp
					? 1 + ((FunctionOp) hop).getInputVariableNames().length
						+ ((FunctionOp) hop).getOutputVariableNames().length : 1);

			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Map<Hop,Integer> actualMultiplicity = new IdentityHashMap<>();
			for(HopOccurrenceProjection projection : analysis.occurrences()) {
				Assert.assertTrue(fixture + " projected an identity absent from independent traversal",
					identities.contains(projection.hop()));
				actualMultiplicity.merge(projection.hop(), 1, Integer::sum);
				if(projection.key().canonicalSourceOrigin().startsWith("function-boundary:"))
					assertIndependentFunctionBoundary(fixture, projection);
			}
			Assert.assertEquals(fixture + " concrete Hop/context multiplicity", expectedMultiplicity,
				actualMultiplicity);
			Assert.assertEquals(fixture, analysis.graph().nodes().size(), analysis.occurrences().size());
			Assert.assertTrue(fixture + " did not retain synthetic multi-key contexts",
				actualMultiplicity.values().stream().anyMatch(count -> count > 1));
		}
	}

	private static void assertIndependentFunctionBoundary(String fixture, HopOccurrenceProjection projection) {
		Assert.assertTrue(fixture + " boundary key did not map to a FunctionOp",
			projection.hop() instanceof FunctionOp);
		FunctionOp call = (FunctionOp) projection.hop();
		Set<String> expectedOrigins = new java.util.LinkedHashSet<>();
		for(String input : call.getInputVariableNames())
			expectedOrigins.add("function-boundary:" + call.getFunctionKey() + ":input:" + input);
		for(String output : call.getOutputVariableNames())
			expectedOrigins.add("function-boundary:" + call.getFunctionKey() + ":output:" + output);
		Assert.assertTrue(fixture + " unknown independently derived function boundary",
			expectedOrigins.contains(projection.key().canonicalSourceOrigin()));
	}

	private static List<Hop> allHops(DMLProgram program) {
		List<Hop> result = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		walkBlocks(program.getStatementBlocks(), result, seen);
		program.getNamedNSFunctionStatementBlocks().entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> walkBlock(entry.getValue(), result, seen));
		return result;
	}

	private static void walkBlocks(List<StatementBlock> blocks, List<Hop> result, Set<Hop> seen) {
		if(blocks != null)
			for(StatementBlock block : blocks)
				walkBlock(block, result, seen);
	}

	private static void walkBlock(StatementBlock block, List<Hop> result, Set<Hop> seen) {
		List<Hop> roots = new ArrayList<>();
		if(block.getHops() != null)
			roots.addAll(block.getHops());
		if(block instanceof IfStatementBlock)
			roots.add(((IfStatementBlock) block).getPredicateHops());
		if(block instanceof WhileStatementBlock)
			roots.add(((WhileStatementBlock) block).getPredicateHops());
		if(block instanceof ForStatementBlock) {
			roots.add(((ForStatementBlock) block).getFromHops());
			roots.add(((ForStatementBlock) block).getToHops());
			roots.add(((ForStatementBlock) block).getIncrementHops());
		}
		for(Hop root : roots)
			walkHop(root, result, seen);
		if(block instanceof FunctionStatementBlock)
			walkBlocks(((FunctionStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof WhileStatementBlock)
			walkBlocks(((WhileStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof ForStatementBlock)
			walkBlocks(((ForStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof IfStatementBlock) {
			IfStatement statement = (IfStatement) block.getStatement(0);
			walkBlocks(statement.getIfBody(), result, seen);
			walkBlocks(statement.getElseBody(), result, seen);
		}
	}

	private static void walkHop(Hop hop, List<Hop> result, Set<Hop> seen) {
		if(hop == null || !seen.add(hop))
			return;
		result.add(hop);
		for(Hop input : hop.getInput())
			walkHop(input, result, seen);
	}
}

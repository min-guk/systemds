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
import org.apache.sysds.hops.LiteralOp;
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
			Map<Hop,List<Long>> expectedScopes = new IdentityHashMap<>();
			for(PlacementGraphFingerprint.HopOccurrence occurrence
				: PlacementGraphFingerprint.orderedOccurrences(program)) {
				int multiplicity = occurrence.hop() instanceof FunctionOp
					? 1 + ((FunctionOp) occurrence.hop()).getInputVariableNames().length
						+ ((FunctionOp) occurrence.hop()).getOutputVariableNames().length : 1;
				for(int i = 0; i < multiplicity; i++)
					expectedScopes.computeIfAbsent(occurrence.hop(), ignored -> new ArrayList<>())
						.add(occurrence.block().getSBID());
			}
			Set<Hop> identities = Collections.newSetFromMap(new IdentityHashMap<>());
			identities.addAll(independentlyTraversed);
			Map<Hop,Integer> expectedMultiplicity = new IdentityHashMap<>();
			for(Hop hop : independentlyTraversed)
				expectedMultiplicity.put(hop, hop instanceof FunctionOp
					? 1 + ((FunctionOp) hop).getInputVariableNames().length
						+ ((FunctionOp) hop).getOutputVariableNames().length : 1);

			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Map<Hop,Integer> actualMultiplicity = new IdentityHashMap<>();
			Map<Hop,List<Long>> actualScopes = new IdentityHashMap<>();
			for(HopOccurrenceProjection projection : analysis.occurrences()) {
				Assert.assertTrue(fixture + " projected an identity absent from independent traversal",
					identities.contains(projection.hop()));
				actualMultiplicity.merge(projection.hop(), 1, Integer::sum);
				actualScopes.computeIfAbsent(projection.hop(), ignored -> new ArrayList<>()).add(projection.scopeId());
				if(projection.key().canonicalSourceOrigin().startsWith("function-boundary:"))
					assertIndependentFunctionBoundary(fixture, projection);
			}
			Assert.assertEquals(fixture + " concrete Hop/context multiplicity", expectedMultiplicity,
				actualMultiplicity);
			Assert.assertEquals(fixture + " exact scope owner count", expectedScopes.size(), actualScopes.size());
			for(Map.Entry<Hop,List<Long>> expectedScope : expectedScopes.entrySet())
				Assert.assertEquals(fixture + " exact main/nested/function/call-boundary statement-block scopes",
					expectedScope.getValue(), actualScopes.get(expectedScope.getKey()));
			Assert.assertEquals(fixture, analysis.graph().nodes().size(), analysis.occurrences().size());
		}
	}

	@Test
	public void oneConcreteHopReusedAcrossTwoIndependentContextsProjectsToTwoKeys() {
		LiteralOp shared = new LiteralOp(7L);
		DMLProgram program = sharedHopAcrossStatementBlocks(shared);
		List<Hop> independentOccurrences = plainTopLevelOccurrences(program);
		Assert.assertEquals("fixture must reuse the exact same Hop identity in two statement-block contexts", 2,
			independentOccurrences.stream().filter(hop -> hop == shared).count());
		Assert.assertNotSame("fixture contexts must be distinct statement blocks",
			program.getStatementBlocks().get(0), program.getStatementBlocks().get(1));

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Map<Hop,Integer> expected = new IdentityHashMap<>();
		independentOccurrences.forEach(hop -> expected.merge(hop, 1, Integer::sum));
		Map<Hop,Integer> actual = new IdentityHashMap<>();
		analysis.occurrences().forEach(projection -> actual.merge(projection.hop(), 1, Integer::sum));
		Assert.assertEquals("exact independent occurrence identity/multiplicity", expected, actual);
		Assert.assertEquals("shared Hop must project through two distinct compiled keys", 2,
			analysis.occurrences().stream().filter(value -> value.hop() == shared)
				.map(HopOccurrenceProjection::key).distinct().count());
		Assert.assertEquals("shared Hop projections must retain both exact owning statement-block scopes",
			Set.of(program.getStatementBlocks().get(0).getSBID(), program.getStatementBlocks().get(1).getSBID()),
			analysis.occurrences().stream().filter(value -> value.hop() == shared)
				.map(HopOccurrenceProjection::scopeId).collect(java.util.stream.Collectors.toSet()));
	}

	private static DMLProgram sharedHopAcrossStatementBlocks(Hop shared) {
		StatementBlock first = new StatementBlock();
		first.setHops(new ArrayList<>(List.of(shared)));
		StatementBlock second = new StatementBlock();
		second.setHops(new ArrayList<>(List.of(shared)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(first, second)));
		return program;
	}

	private static List<Hop> plainTopLevelOccurrences(DMLProgram program) {
		List<Hop> result = new ArrayList<>();
		for(StatementBlock block : program.getStatementBlocks()) {
			Set<Hop> contextSeen = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop root : block.getHops())
				walkHop(root, result, contextSeen);
		}
		return result;
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

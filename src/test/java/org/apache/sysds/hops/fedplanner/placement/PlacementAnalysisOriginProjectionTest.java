/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
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
import org.apache.sysds.parser.StatementBlock.InlinedFunctionCallBoundary;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionInputBoundary;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionOutputBoundary;
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
			List<PlacementGraphFingerprint.HopOccurrence> occurrences =
				PlacementGraphFingerprint.orderedOccurrences(program);
			List<BoundaryExpectation> inlinedBoundaries = inlinedBoundaryExpectations(occurrences);
			Map<Hop,List<Long>> expectedScopes = new IdentityHashMap<>();
			Map<Hop,Integer> expectedMultiplicity = new IdentityHashMap<>();
			for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
				addExpectedOccurrence(expectedMultiplicity, expectedScopes, occurrence.hop(), occurrence.block().getSBID());
				if(occurrence.hop() instanceof FunctionOp) {
					FunctionOp call = (FunctionOp) occurrence.hop();
					for(int i = 0; i < call.getInputVariableNames().length + call.getOutputVariableNames().length; i++)
						addExpectedOccurrence(expectedMultiplicity, expectedScopes, occurrence.hop(), occurrence.block().getSBID());
				}
			}
			for(BoundaryExpectation boundary : inlinedBoundaries)
				addExpectedOccurrence(expectedMultiplicity, expectedScopes, boundary.authority(), boundary.scopeId());
			Set<Hop> identities = Collections.newSetFromMap(new IdentityHashMap<>());
			identities.addAll(independentlyTraversed);

			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Map<Hop,Integer> actualMultiplicity = new IdentityHashMap<>();
			Map<Hop,List<Long>> actualScopes = new IdentityHashMap<>();
			List<BoundaryExpectation> remainingInlinedBoundaries = new ArrayList<>(inlinedBoundaries);
			for(HopOccurrenceProjection projection : analysis.occurrences()) {
				Assert.assertTrue(fixture + " projected an identity absent from independent traversal",
					identities.contains(projection.hop()));
				actualMultiplicity.merge(projection.hop(), 1, Integer::sum);
				actualScopes.computeIfAbsent(projection.hop(), ignored -> new ArrayList<>()).add(projection.scopeId());
				if(projection.key().canonicalSourceOrigin().startsWith("function-boundary:"))
					assertIndependentFunctionBoundary(fixture, projection, remainingInlinedBoundaries);
			}
			Assert.assertTrue(fixture + " did not project every independently derived inlined boundary: "
				+ remainingInlinedBoundaries, remainingInlinedBoundaries.isEmpty());
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

	private static void addExpectedOccurrence(Map<Hop,Integer> multiplicity, Map<Hop,List<Long>> scopes,
		Hop hop, long scopeId) {
		multiplicity.merge(hop, 1, Integer::sum);
		scopes.computeIfAbsent(hop, ignored -> new ArrayList<>()).add(scopeId);
	}

	private static void assertIndependentFunctionBoundary(String fixture, HopOccurrenceProjection projection,
		List<BoundaryExpectation> remainingInlinedBoundaries) {
		if(projection.hop() instanceof FunctionOp) {
			FunctionOp call = (FunctionOp) projection.hop();
			Set<String> expectedOrigins = new java.util.LinkedHashSet<>();
			for(String input : call.getInputVariableNames())
				expectedOrigins.add("function-boundary:" + call.getFunctionKey() + ":input:" + input);
			for(String output : call.getOutputVariableNames())
				expectedOrigins.add("function-boundary:" + call.getFunctionKey() + ":output:" + output);
			Assert.assertTrue(fixture + " unknown independently derived FunctionOp boundary",
				expectedOrigins.contains(projection.key().canonicalSourceOrigin()));
			return;
		}

		for(int i = 0; i < remainingInlinedBoundaries.size(); i++) {
			BoundaryExpectation boundary = remainingInlinedBoundaries.get(i);
			if(boundary.matches(projection)) {
				remainingInlinedBoundaries.remove(i);
				return;
			}
		}
		Assert.fail(fixture + " boundary key did not map to an independently derived exact FunctionOp "
			+ "or compiler-inlined authority Hop: " + projection.key().canonicalSourceOrigin()
			+ " hop=" + projection.hop().getClass().getSimpleName() + ':' + projection.hop().getName()
			+ " scope=" + projection.scopeId());
	}

	private record BoundaryExpectation(String origin, Hop authority, long scopeId) {
		private boolean matches(HopOccurrenceProjection projection) {
			return origin.equals(projection.key().canonicalSourceOrigin())
				&& authority == projection.hop() && scopeId == projection.scopeId();
		}
	}

	private static List<BoundaryExpectation> inlinedBoundaryExpectations(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences) {
		List<BoundaryExpectation> result = new ArrayList<>();
		Set<StatementBlock> expandedBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			StatementBlock block = occurrence.block();
			if(!expandedBlocks.add(block) || block.getInlinedFunctionCallBoundaries().isEmpty())
				continue;
			List<Hop> blockHops = occurrences.stream().filter(candidate -> candidate.block() == block)
				.map(PlacementGraphFingerprint.HopOccurrence::hop).toList();
			Map<String,InlinedFunctionInputBoundary> inputBindings = new LinkedHashMap<>();
			Map<String,InlinedFunctionOutputBoundary> outputBindings = new LinkedHashMap<>();
			for(InlinedFunctionCallBoundary boundary : block.getInlinedFunctionCallBoundaries())
				for(InlinedFunctionInputBoundary input : boundary.inputs())
					Assert.assertNull("duplicate independently derived inlined input binding "
						+ input.boundVariable(), inputBindings.put(input.boundVariable(), input));
			for(InlinedFunctionCallBoundary boundary : block.getInlinedFunctionCallBoundaries())
				for(InlinedFunctionOutputBoundary output : boundary.outputs())
					Assert.assertNull("duplicate independently derived inlined output binding "
						+ output.targetVariable(), outputBindings.put(output.targetVariable(), output));
			for(InlinedFunctionCallBoundary call : block.getInlinedFunctionCallBoundaries()) {
				List<Hop> arguments = new ArrayList<>();
				for(InlinedFunctionInputBoundary input : call.inputs()) {
					ResolvedInlinedInput exactInput = resolveInlinedInput(input, inputBindings);
					arguments.add(exactInput.transientRead()
						? requireExactDataHop(blockHops, OpOpData.TRANSIENTREAD, exactInput.variable(), call,
							"input", input.position())
						: requireExactNamedHop(blockHops, exactInput.variable(), call, "input", input.position()));
				}
				List<Hop> results = new ArrayList<>();
				for(InlinedFunctionOutputBoundary output : call.outputs())
					results.add(requireExactNamedHop(blockHops, resolveInlinedOutput(output, outputBindings),
						call, "output", output.position()));
				Hop authority = results.stream().findFirst()
					.orElseGet(() -> arguments.stream().findFirst().orElse(null));
				if(authority == null)
					continue;
				for(InlinedFunctionInputBoundary input : call.inputs())
					result.add(new BoundaryExpectation("function-boundary:" + call.functionKey() + ":input:"
						+ input.formalVariable(), authority, block.getSBID()));
				for(InlinedFunctionOutputBoundary output : call.outputs())
					result.add(new BoundaryExpectation("function-boundary:" + call.functionKey() + ":output:"
						+ output.formalVariable(), authority, block.getSBID()));
			}
		}
		return result;
	}

	private record ResolvedInlinedInput(String variable, boolean transientRead) { }

	private static ResolvedInlinedInput resolveInlinedInput(InlinedFunctionInputBoundary input,
		Map<String,InlinedFunctionInputBoundary> bindings) {
		String actual = input.actualVariable();
		if(actual == null)
			return new ResolvedInlinedInput(input.boundVariable(), false);
		Set<String> visited = new java.util.LinkedHashSet<>();
		while(true) {
			Assert.assertTrue("cyclic independently derived inlined input binding " + visited, visited.add(actual));
			InlinedFunctionInputBoundary binding = bindings.get(actual);
			if(binding == null)
				return new ResolvedInlinedInput(actual, true);
			if(binding.actualVariable() == null)
				return new ResolvedInlinedInput(binding.boundVariable(), false);
			actual = binding.actualVariable();
		}
	}

	private static String resolveInlinedOutput(InlinedFunctionOutputBoundary output,
		Map<String,InlinedFunctionOutputBoundary> bindings) {
		String variable = output.boundVariable();
		Set<String> visited = new java.util.LinkedHashSet<>();
		while(true) {
			Assert.assertTrue("cyclic independently derived inlined output binding " + visited, visited.add(variable));
			InlinedFunctionOutputBoundary binding = bindings.get(variable);
			if(binding == null)
				return variable;
			variable = binding.boundVariable();
		}
	}

	private static Hop requireExactDataHop(List<Hop> blockHops, OpOpData operation, String name,
		InlinedFunctionCallBoundary call, String boundary, int position) {
		List<Hop> matches = blockHops.stream().filter(hop -> hop instanceof DataOp)
			.filter(hop -> ((DataOp) hop).getOp() == operation)
			.filter(hop -> name.equals(hop.getName())).toList();
		return requireOneMatch(matches, call, boundary, position, name + " operation=" + operation);
	}

	private static Hop requireExactNamedHop(List<Hop> blockHops, String name, InlinedFunctionCallBoundary call,
		String boundary, int position) {
		List<Hop> matches = blockHops.stream().filter(hop -> name.equals(hop.getName())).toList();
		return requireOneMatch(matches, call, boundary, position, name);
	}

	private static Hop requireOneMatch(List<Hop> matches, InlinedFunctionCallBoundary call, String boundary,
		int position, String detail) {
		Assert.assertEquals("inlined function boundary requires one exact independently traversed compiler-owned "
			+ "authority: " + call.functionKey() + " callStatement=" + call.callStatementPosition()
			+ ' ' + boundary + '=' + position + " variable=" + detail, 1, matches.size());
		return matches.get(0);
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
		if(hop instanceof FunctionOp) {
			List<Hop> outputs = ((FunctionOp) hop).getOutputs();
			if(outputs != null)
				for(Hop output : outputs)
					walkHop(output, result, seen);
		}
	}
}

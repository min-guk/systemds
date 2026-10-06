/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class SharedRegionalPreparationTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);

	@Test
	public void sameBoundaryReusesConditionedTableAndChangedBoundaryRebuilds() {
		Variable block = variable("block", 2);
		Variable boundary = variable("boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			5d, 1d,
			2d, 7d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {0, 0}, 0);
		Assert.assertEquals(1L, preparation.tableBuilds());
		Assert.assertEquals(0L, preparation.cacheHits());

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {1, 0}, 0);
		Assert.assertEquals(1L, preparation.tableBuilds());
		Assert.assertEquals(1L, preparation.cacheHits());

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {1, 1}, 0);
		Assert.assertEquals(2L, preparation.tableBuilds());
		Assert.assertEquals(1L, preparation.cacheHits());
		Assert.assertEquals(3L, preparation.blocks());
		Assert.assertEquals(0L, preparation.fallbacks());
	}

	@Test
	public void reducedBlockSolutionReturnsOriginalSourceValue() {
		Variable block = variable("reduced-block", 3);
		Variable boundary = variable("reduced-boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			4d, 0d,
			1d, 3d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(new int[] {1, 0}, new int[] {0});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();

		Assert.assertEquals(List.of(2), result.assignmentInVariableOrder());
		Assert.assertEquals(1d, result.objective(), 0d);
		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {2, 1}, 0);
		Assert.assertEquals(0L, preparation.fallbacks());
	}

	@Test
	public void removedFixedBoundaryRequestsRepairInsteadOfCanonicalFallback() {
		Variable block = variable("fallback-block", 2);
		Variable boundary = variable("fallback-boundary", 3);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(block, boundary),
				1d, 2d, 3d,
				3d, 2d, 1d),
			Factor.dense(List.of(boundary), Double.POSITIVE_INFINITY, 0d, 0d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		LocalCategoricalOptimizer.UnsupportedBoundaryException boundaryFailure = Assert.assertThrows(
			LocalCategoricalOptimizer.UnsupportedBoundaryException.class,
			() -> preparation.prepare(new int[] {0, 0}, new int[] {0}));
		Assert.assertArrayEquals(new int[] {1}, boundaryFailure.variables);
		Assert.assertEquals(0L, preparation.fallbacks());
		Assert.assertEquals(0L, preparation.blocks());
	}

	@Test
	public void repairExpansionIncludesRemovedBoundaryAndDoesNotKeepItsIncumbent() {
		Variable block = variable("repair-block", 2);
		Variable boundary = variable("repair-boundary", 3);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(block, boundary),
				1d, 2d, 3d,
				3d, 2d, 1d),
			Factor.dense(List.of(boundary), Double.POSITIVE_INFINITY, 0d, 0d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		int[] expanded = preparation.expandRepairBlock(new int[] {0, 0}, new int[] {0});
		Assert.assertArrayEquals(new int[] {0, 1}, expanded);
		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(new int[] {0, 0}, expanded);
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();

		Assert.assertEquals(2, result.assignmentInVariableOrder().size());
		Assert.assertNotEquals(0, result.assignmentInVariableOrder().get(1).intValue());
		Assert.assertTrue(Double.isFinite(result.objective()));
		Assert.assertEquals(0L, preparation.fallbacks());
	}

	@Test
	public void repairExpansionFindsTransitiveRemovedBoundaries() {
		Variable first = variable("repair-first", 2);
		Variable second = variable("repair-second", 2);
		Variable third = variable("repair-third", 2);
		List<Variable> variables = List.of(first, second, third);
		List<Factor> factors = List.of(
			Factor.dense(List.of(first, second), 0d, 0d, 0d, 0d),
			Factor.dense(List.of(second), Double.POSITIVE_INFINITY, 0d),
			Factor.dense(List.of(second, third), 0d, 0d, 0d, 0d),
			Factor.dense(List.of(third), Double.POSITIVE_INFINITY, 0d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		Assert.assertArrayEquals(new int[] {0, 1, 2},
			preparation.expandRepairBlock(new int[] {0, 0, 0}, new int[] {0}));
	}

	@Test
	public void fastBlockOrderIsOptInAndUsedForNonCompactBlock() {
		String previous = System.getProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY);
		String previousAssignments =
			System.getProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY);
		try {
			System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY);
			System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY);
			Assert.assertFalse(SharedRegionalPreparation.configuredFastBlockOrder());
			Assert.assertEquals(100_000L,
				SharedRegionalPreparation.configuredFastBlockAssignments());
			System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY, "true");
			System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY, "1000000");

			Variable block = variable("fast-block", 2);
			Variable boundary = variable("fast-boundary", 2);
			List<Variable> variables = List.of(block, boundary);
			List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
				3d, Double.POSITIVE_INFINITY,
				1d, 4d));
			SharedRegionalPreparation preparation = shared(variables, factors);

			assertMatchesIndependentOracle(preparation, variables, factors, new int[] {0, 0}, 0);
			Assert.assertEquals(1L, preparation.fastBlockOrderAccepted());
			Assert.assertEquals(0L, preparation.fastBlockOrderFallbacks());
			Assert.assertTrue(preparation.maximumFastBlockOrderAssignments() > 0L);
			Assert.assertTrue(preparation.maximumFastBlockOrderAssignments() <= 1_000_000L);
		}
		finally {
			if(previous == null)
				System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY);
			else
				System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY, previous);
			if(previousAssignments == null)
				System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY);
			else
				System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY,
					previousAssignments);
		}
	}

	@Test
	public void commonFastOrderIsAppliedToCompactAndPlainSharedBlocks() {
		String previous = System.getProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY);
		String previousAssignments =
			System.getProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY);
		try {
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, "true");
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY,
				"1000000");
			for(boolean compact : new boolean[] {false, true}) {
				Variable block = variable("common-block-" + compact, 2);
				Variable boundary = variable("common-boundary-" + compact, 2);
				List<Variable> variables = List.of(block, boundary);
				List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
					3d, Double.POSITIVE_INFINITY, 1d, 4d));
				SharedRegionalPreparation preparation = new SharedRegionalPreparation(
					RegionalSearchProblem.generic(variables, factors), GENEROUS, compact);

				assertMatchesIndependentOracle(preparation, variables, factors,
					new int[] {0, 0}, 0);
				Assert.assertEquals(1L, preparation.fastBlockOrderAccepted());
				Assert.assertEquals(0L, preparation.fastBlockOrderFallbacks());
			}
		}
		finally {
			if(previous == null)
				System.clearProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY);
			else
				System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, previous);
			if(previousAssignments == null)
				System.clearProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY);
			else
				System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY,
					previousAssignments);
		}
	}

	@Test
	public void malformedFastBlockOrderPropertyFailsClosed() {
		String previous = System.getProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY);
		try {
			System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY, "yes");
			IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
				SharedRegionalPreparation::configuredFastBlockOrder);
			Assert.assertEquals("REGIONAL_FAST_BLOCK_ORDER_INVALID|value=yes", error.getMessage());
		}
		finally {
			if(previous == null)
				System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY);
			else
				System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ORDER_PROPERTY, previous);
		}
	}

	@Test
	public void nonPositiveFastBlockAssignmentsPropertyFailsClosed() {
		String previous =
			System.getProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY);
		try {
			System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY, "0");
			IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
				SharedRegionalPreparation::configuredFastBlockAssignments);
			Assert.assertEquals("REGIONAL_FAST_BLOCK_ASSIGNMENTS_INVALID|value=0",
				error.getMessage());
		}
		finally {
			if(previous == null)
				System.clearProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY);
			else
				System.setProperty(SharedRegionalPreparation.FAST_BLOCK_ASSIGNMENTS_PROPERTY, previous);
		}
	}

	@Test
	public void preSolveWorkTraceRespectsExplicitTraceOff() throws Exception {
		String output = runTraceProbe(false);
		Assert.assertFalse(output, output.contains("[PlannerTrace][Exact-PreSolveWork]"));
		Assert.assertFalse(output, output.contains("[PlannerTrace][Exact-OrderPortfolio]"));
		Assert.assertTrue(output, output.contains("TRACE_PROBE_COMPLETE"));
	}

	@Test
	public void preSolveWorkTraceReportsBothBranchesBeforeSolveExactlyOnce() throws Exception {
		String output = runTraceProbe(true);
		Assert.assertEquals(output, 2, occurrences(output, "[PlannerTrace][Exact-PreSolveWork]"));
		List<String> portfolios = output.lines().filter(line ->
			line.contains("[PlannerTrace][Exact-OrderPortfolio]")).toList();
		Assert.assertEquals(output, 4, portfolios.size());
		for(String portfolio : portfolios) {
			Assert.assertTrue(portfolio, portfolio.contains("variables=2 inputFactors=1 inputCells=6 maximumInputCells=6"));
			for(String ordering : List.of("MIN_FILL", "MIN_SEPARATOR_CELLS",
				"MIN_ELIMINATION_ASSIGNMENTS", "MIN_DEGREE"))
				Assert.assertTrue(portfolio, portfolio.contains(ordering + ":PlanMetrics["));
			Assert.assertTrue(portfolio, portfolio.contains("MIN_SEPARATOR_CELLS:PlanMetrics[maximumFactorCells=2, "
				+ "materializedFactorCells=3, maximumEliminationAssignments=6, eliminationAssignments=8]"));
			Assert.assertTrue(portfolio, portfolio.contains("selectedOrdering=MIN_FILL selectedPriority=0"));
		}
		for(boolean compact : new boolean[] {false, true}) {
			String caller = compact ? "local-shared-compact" : "local-shared";
			String line = output.lines().filter(value ->
				value.contains("[PlannerTrace][Exact-PreSolveWork]")
					&& value.contains("caller=" + caller + " ")).findFirst().orElseThrow();
			Assert.assertTrue(line, line.contains("compact=" + compact));
			Assert.assertTrue(line, line.contains("rootDecisionCount=2"));
			Assert.assertTrue(line, line.contains("rootVariableCount=2"));
			Assert.assertTrue(line, line.contains("rootFactorCount=1"));
			Assert.assertTrue(line, line.contains("blockOriginalIndices=[0, 1]"));
			Assert.assertTrue(line, line.contains("blockOriginalCount=2"));
			Assert.assertTrue(line, line.contains("inputVariableKeys=[exact-reduced|0|trace-a-"
				+ compact + ", exact-reduced|1|trace-b-" + compact + "]"));
			Assert.assertTrue(line, line.contains("inputVariableDomains=[2, 3]"));
			Assert.assertTrue(line, line.contains("inputFactorCount=1"));
			Assert.assertTrue(line, line.contains("compiledVariableCount=2"));
			String compiledPrefix = compact ? "exact-reduced|1|" : "";
			String compiledSecondPrefix = compact ? "exact-reduced|0|" : "";
			Assert.assertTrue(line, line.contains("eliminationOrder=[" + compiledPrefix
				+ "exact-reduced|1|trace-b-" + compact + ", " + compiledSecondPrefix
				+ "exact-reduced|0|trace-a-" + compact + "]"));
			Assert.assertTrue(line, line.contains("inducedWidth=1"));
			Assert.assertTrue(line, line.contains("maximumFactorCells=6"));
			Assert.assertTrue(line, line.contains("materializedFactorCells=9"));
			Assert.assertTrue(line, line.contains("maximumEliminationAssignments=6"));
			Assert.assertTrue(line, line.contains("eliminationAssignments=8"));
			Assert.assertTrue(line, line.contains("maximumFactorCellsLimit=1000000"));
			Assert.assertTrue(line, line.contains("maximumMaterializedCellsLimit=10000000"));
			Assert.assertTrue(line, line.contains("fastOrderConfigured=false"));
			Assert.assertTrue(line, line.contains("fastOrderSource=legacy-regional"));
			Assert.assertTrue(line, line.matches(".*plannerElapsedNanos=-?\\d+"));
			int trace = output.indexOf(line);
			int beforeSolve = output.indexOf("TRACE_BEFORE_SOLVE compact=" + compact);
			Assert.assertTrue(output, trace >= 0 && trace < beforeSolve);
			Assert.assertEquals(output, 1, occurrences(output,
				"TRACE_BEFORE_SOLVE compact=" + compact));
			Assert.assertEquals(output, 1, occurrences(output,
				"TRACE_AFTER_SECOND_PREPARATION compact=" + compact));
		}
	}

	private static String runTraceProbe(boolean trace) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command = new ArrayList<>();
		command.add(java);
		command.add("-Dsysds.fedplanner.trace=" + trace);
		command.add("-Dsysds.fedplanner.trace.details=false");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(SharedRegionalPreparationTest.class.getName());
		command.add("trace-probe");
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output;
		try(BufferedReader reader = new BufferedReader(new InputStreamReader(
			process.getInputStream(), StandardCharsets.UTF_8))) {
			output = reader.lines().collect(Collectors.joining("\n"));
		}
		Assert.assertEquals(output, 0, process.waitFor());
		return output;
	}

	private static int occurrences(String value, String token) {
		int count = 0;
		for(int offset = 0; (offset = value.indexOf(token, offset)) >= 0; offset += token.length())
			count++;
		return count;
	}

	public static void main(String[] args) throws Exception {
		if(args.length != 1 || !"trace-probe".equals(args[0]))
			throw new IllegalArgumentException("TRACE_PROBE_ARGUMENT_INVALID");
		String previousTrace = System.getProperty("sysds.fedplanner.trace");
		PrintStream previousOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try(PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
			System.setOut(output);
			for(boolean compact : new boolean[] {false, true}) {
				Variable a = variable("trace-a-" + compact, 2);
				Variable b = variable("trace-b-" + compact, 3);
				List<Factor> factors = List.of(Factor.dense(List.of(a, b),
					0d, 1d, 2d, 3d, 4d, 5d));
				SharedRegionalPreparation preparation = new SharedRegionalPreparation(
					RegionalSearchProblem.generic(List.of(a, b), factors), GENEROUS, compact);
				LocalCategoricalOptimizer.PreparedBlockSolver first =
					preparation.prepare(new int[] {0, 0}, new int[] {0, 1});
				System.out.println("TRACE_BEFORE_SOLVE compact=" + compact);
				first.solve();
				LocalCategoricalOptimizer.PreparedBlockSolver second =
					preparation.prepare(new int[] {1, 2}, new int[] {0, 1});
				System.out.println("TRACE_AFTER_SECOND_PREPARATION compact=" + compact);
				second.solve();
			}
			System.out.println("TRACE_PROBE_COMPLETE");
		}
		finally {
			System.setOut(previousOut);
			if(previousTrace == null)
				System.clearProperty("sysds.fedplanner.trace");
			else
				System.setProperty("sysds.fedplanner.trace", previousTrace);
		}
		previousOut.print(captured.toString(StandardCharsets.UTF_8));
	}

	private static SharedRegionalPreparation shared(List<Variable> variables, List<Factor> factors) {
		return new SharedRegionalPreparation(RegionalSearchProblem.generic(variables, factors), GENEROUS, false);
	}

	private static void assertMatchesIndependentOracle(SharedRegionalPreparation preparation,
		List<Variable> variables, List<Factor> factors, int[] assignment, int block) {
		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(assignment, new int[] {block});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();
		Oracle oracle = oracle(variables, factors, assignment, block);
		Assert.assertEquals(oracle.objective, result.objective(), 0d);
		Assert.assertEquals(List.of(oracle.value), result.assignmentInVariableOrder());
	}

	private static Oracle oracle(List<Variable> variables, List<Factor> factors,
		int[] assignment, int block) {
		double optimum = Double.POSITIVE_INFINITY;
		int value = -1;
		for(int candidate = 0; candidate < variables.get(block).domainSize(); candidate++) {
			List<Integer> current = new ArrayList<>(assignment.length);
			for(int index = 0; index < assignment.length; index++)
				current.add(index == block ? candidate : assignment[index]);
			double objective = ExactCategoricalSolver.evaluate(variables, factors, GENEROUS, current);
			if(objective < optimum) {
				optimum = objective;
				value = candidate;
			}
		}
		return new Oracle(optimum, value);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Oracle(double objective, int value) { }
}

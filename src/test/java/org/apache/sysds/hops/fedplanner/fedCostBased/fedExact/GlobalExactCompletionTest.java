/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

/** Full-model exactness, including components outside a locally improving region. */
public class GlobalExactCompletionTest {
	@Test
	public void everyOrderSolvesAllCoupledAndDisconnectedFactors() {
		var variables = List.of(variable("a", 2), variable("b", 2), variable("c", 2),
			variable("d", 2), variable("disconnected", 3));
		var factors = new ArrayList<ExactCategoricalSolver.Factor>();
		for(int i = 0; i < 4; i++) {
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(variables.get(i)), 5d, 0d));
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(variables.get(i), variables.get((i + 1) % 4)),
				v -> v[0] == v[1] ? 0d : 100d));
		}
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(variables.get(4)), 13d, 7d, 2d));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(), 3d));
		double exhaustive = Double.POSITIVE_INFINITY;
		for(int bits = 0; bits < 16; bits++)
			for(int last = 0; last < 3; last++) {
				List<Integer> assignment = new ArrayList<>();
				for(int i = 0; i < 4; i++) assignment.add((bits >> i) & 1);
				assignment.add(last);
				exhaustive = Math.min(exhaustive,
					RegionalSearchProblem.evaluateFactors(variables, factors, assignment));
			}
		Assert.assertEquals(5d, exhaustive, 0d);
		var order = new ArrayList<>(variables.stream().map(ExactCategoricalSolver.Variable::key).toList());
		for(int rotation = 0; rotation < order.size(); rotation++) {
			var solved = ExactCategoricalSolver.solve(ExactCategoricalSolver.compilePreferred(
				variables, factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS, order));
			Assert.assertEquals(exhaustive, solved.objective(), 0d);
			Assert.assertEquals(List.of(1, 1, 1, 1, 2), solved.assignmentInVariableOrder());
			Assert.assertEquals(variables.size(), solved.statistics().eliminationOrder().size());
			Collections.rotate(order, 1);
		}
	}

	@Test
	public void infeasibleDisconnectedComponentCannotReturnPartialSuccess() {
		var good = variable("good", 2);
		var bad = variable("bad", 2);
		var failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(good, bad), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(good), 0d, 1d),
				ExactCategoricalSolver.Factor.dense(List.of(bad),
					Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)),
				ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", failure.getMessage());
	}

	@Test
	public void oversizedPackedAllocationFailsBeforeEvaluationAndNeverReturnsAPlan() throws Exception {
		runHeapProbe("oversized-packed");
	}

	@Test
	public void oversizedNumericPromotionFailsAtFirstNumericValueAndNeverReturnsAPlan() throws Exception {
		runHeapProbe("oversized-numeric");
	}

	@Test
	public void retainedArraysFailOnlyAfterActualJvmAllocationFailure() throws Exception {
		runHeapProbe("live-arrays");
	}

	private void runHeapProbe(String mode) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process process = new ProcessBuilder(java, "-Xmx64m", "-cp",
			System.getProperty("java.class.path"), getClass().getName(), mode)
			.redirectErrorStream(true).start();
		try {
			Assert.assertTrue("Heap protection probe hung", process.waitFor(60, TimeUnit.SECONDS));
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			Assert.assertEquals(output, 0, process.exitValue());
			Assert.assertTrue(output, output.contains("GLOBAL_RESOURCE_FAILURE_WITHOUT_PLAN"));
		}
		finally { process.destroyForcibly(); }
	}

	public static void main(String[] args) {
		if(args.length > 0 && args[0].equals("live-arrays")) {
			var retained = new ArrayList<double[]>();
			try {
				for(int i = 0; i < 5; i++)
					retained.add(PlannerResourceGuard.allocateDoubles(2_000_000, "live-probe"));
				throw new AssertionError("80MB cannot fit in the 64MiB JVM heap");
			}
			catch(PlannerResourceGuard.ResourceExhaustedException expected) {
				if(!(expected.getCause() instanceof OutOfMemoryError) || retained.isEmpty())
					throw new AssertionError("Only actual VM exhaustion may reject this allocation", expected);
				System.out.println("GLOBAL_RESOURCE_FAILURE_WITHOUT_PLAN held=" + retained.size());
			}
			return;
		}
		boolean packed = args[0].equals("oversized-packed");
		// The packed 16-million-cell table fits in this heap; only a numeric
		// value requires its 128MB double array. A 1.6-billion-cell bitset does not fit.
		var a = variable("a", packed ? 40000 : 4000);
		var b = variable("b", packed ? 40000 : 4000);
		int[] evaluations = {0};
		try {
			ExactCategoricalSolver.solve(List.of(a, b), List.of(
				ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
					if(packed || ++evaluations[0] > 1)
						throw new AssertionError("Evaluation continued past the impossible allocation");
					return 1d;
				})), ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			throw new AssertionError("A resource failure must not produce a partial plan");
		}
		catch(PlannerResourceGuard.ResourceExhaustedException expected) {
			String phase = packed ? "exact-hard-table" : "freeze-lazy-factor";
			if(!expected.getMessage().contains("phase=" + phase)
				|| evaluations[0] != (packed ? 0 : 1))
				throw new AssertionError(expected);
			System.out.println("GLOBAL_RESOURCE_FAILURE_WITHOUT_PLAN");
		}
	}

	private static ExactCategoricalSolver.Variable variable(String key, int size) {
		return new ExactCategoricalSolver.Variable(key, size);
	}
}

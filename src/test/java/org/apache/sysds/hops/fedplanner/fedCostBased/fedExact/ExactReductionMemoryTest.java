/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class ExactReductionMemoryTest {
	@Test
	public void reductionFitsWhenCompletedSourceTablesCanBeReleased() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process process = new ProcessBuilder(java, "-Xmx64m", "-cp",
			System.getProperty("java.class.path"), getClass().getName())
			.redirectErrorStream(true).start();
		try {
			Assert.assertTrue("Bounded reduction timed out", process.waitFor(60, TimeUnit.SECONDS));
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			Assert.assertEquals(output, 0, process.exitValue());
			Assert.assertTrue(output, output.contains("REDUCTION_MEMORY_PASS"));
		}
		finally {
			process.destroyForcibly();
		}
	}

	public static void main(String[] args) {
		var a = new ExactCategoricalSolver.Variable("a", 64);
		var b = new ExactCategoricalSolver.Variable("b", 64);
		var variables = List.of(a, b);
		var factors = new ArrayList<ExactCategoricalSolver.Factor>();
		for(int i = 0; i < 1500; i++)
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(a, b), x -> x[0] + 2d * x[1]));
		factors.add(ExactCategoricalSolver.Factor.lazy(List.of(a),
			x -> x[0] < 32 ? 0d : Double.POSITIVE_INFINITY));
		var root = ExactPhysicalReducedSolver.reducedModel(2, variables, factors,
			new ExactCategoricalSolver.Limits(10_000, 7_000_000));
		if(root.variables().get(0).domainSize() != 32 || root.variables().get(1).domainSize() != 64
			|| root.factors().size() != 1501)
			throw new AssertionError("Supported assignments/factors must be preserved");
		double cost = root.factors().stream()
			.mapToDouble(f -> f.cost(f.scope().size() == 2 ? new int[] {31, 63} : new int[] {31})).sum();
		if(cost != 1500d * (31 + 2 * 63))
			throw new AssertionError("Reduced factor costs changed: " + cost);
		System.out.println("REDUCTION_MEMORY_PASS");
	}
}

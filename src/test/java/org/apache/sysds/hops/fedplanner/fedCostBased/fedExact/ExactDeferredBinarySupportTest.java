/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialHardCostFunction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialTruth;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class ExactDeferredBinarySupportTest {
	private static final Limits GENEROUS = new Limits(20_000_000,100_000_000);

	@Test
	public void partialHardBinaryReductionFitsWithoutSourceAndDestinationTables() throws Exception {
		String java = Path.of(System.getProperty("java.home"),"bin","java").toString();
		Process process = new ProcessBuilder(java,"-Xmx96m","-cp",
			System.getProperty("java.class.path"),getClass().getName(),"memory")
			.redirectErrorStream(true).start();
		try {
			Assert.assertTrue("Deferred binary support probe timed out",
				process.waitFor(60,TimeUnit.SECONDS));
			String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
			Assert.assertEquals(output,0,process.exitValue());
			Assert.assertTrue(output,output.contains("DEFERRED_BINARY_MEMORY_PASS"));
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	public void deferredAndDenseHardSupportPreserveMappingsCostsAndCanonicalTie() {
		Variable a = new Variable("deferred-parity-a",4);
		Variable b = new Variable("deferred-parity-b",4);
		double[] hardValues = new double[16];
		Arrays.fill(hardValues,Double.POSITIVE_INFINITY);
		for(int av=0; av<3; av++) {
			hardValues[av*4+av] = 0d;
			hardValues[av*4+(2-av)] = 0d;
		}
		Factor denseHard = Factor.dense(List.of(a,b),hardValues);
		Factor partialHard = Factor.lazy(List.of(a,b),new TablePartialHard(hardValues,4));
		Factor numeric = Factor.dense(List.of(a,b),
			3d,8d,4d,9d, 7d,2d,6d,5d, 4d,6d,3d,8d, 9d,9d,9d,9d);
		List<Variable> variables = List.of(a,b);
		var dense = ExactPhysicalReducedSolver.reducedModel(
			2,variables,List.of(numeric,denseHard),GENEROUS);
		var deferred = ExactPhysicalReducedSolver.reducedModel(
			2,variables,List.of(numeric,partialHard),GENEROUS);

		Assert.assertEquals(dense.variables(),deferred.variables());
		for(int variable=0; variable<variables.size(); variable++)
			for(int value=0; value<variables.get(variable).domainSize(); value++)
				Assert.assertEquals(dense.reducedValue(variable,value),
					deferred.reducedValue(variable,value));
		for(int av=0; av<deferred.variables().get(0).domainSize(); av++)
			for(int bv=0; bv<deferred.variables().get(1).domainSize(); bv++)
				Assert.assertEquals(
					Double.doubleToRawLongBits(cost(dense,List.of(av,bv))),
					Double.doubleToRawLongBits(cost(deferred,List.of(av,bv))));

		var denseResult = ExactPhysicalReducedSolver.solve(
			2,variables,List.of(numeric,denseHard),GENEROUS,(variable,value) -> value);
		var deferredResult = ExactPhysicalReducedSolver.solve(
			2,variables,List.of(numeric,partialHard),GENEROUS,(variable,value) -> value);
		Assert.assertEquals(Double.doubleToRawLongBits(denseResult.objective()),
			Double.doubleToRawLongBits(deferredResult.objective()));
		Assert.assertEquals(denseResult.assignmentInVariableOrder(),
			deferredResult.assignmentInVariableOrder());
	}

	@Test
	public void unknownPartialLeafStillRejectsInvalidCost() {
		Variable a = new Variable("deferred-invalid-a",2);
		Variable b = new Variable("deferred-invalid-b",2);
		Factor invalid = Factor.lazy(List.of(a,b),new PartialHardCostFunction() {
			@Override public PartialTruth partialTruth(int[] values) { return PartialTruth.UNKNOWN; }
			@Override public double cost(int[] values) {
				return values[0] == 1 && values[1] == 0 ? Double.NaN : 0d;
			}
		});
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.reducedModel(2,List.of(a,b),List.of(invalid),GENEROUS));
		Assert.assertEquals("EXACT_VE_FACTOR_COST_INVALID|value=NaN",failure.getMessage());
	}

	@Test
	public void denseAndDeferredSupportsReachOneCombinedFixedPoint() {
		Variable a = new Variable("deferred-fixed-a",3);
		Variable b = new Variable("deferred-fixed-b",3);
		Variable c = new Variable("deferred-fixed-c",3);
		Variable d = new Variable("deferred-fixed-d",1);
		double inf = Double.POSITIVE_INFINITY;
		Factor equalAB = Factor.dense(List.of(a,b),
			0d,inf,inf, inf,0d,inf, inf,inf,0d);
		Factor equalBC = Factor.lazy(List.of(b,c),new EqualitySupport());
		Factor pinC = Factor.dense(List.of(c,d),0d,inf,inf);

		var reduced = ExactPhysicalReducedSolver.reducedModel(
			4,List.of(a,b,c,d),List.of(equalAB,equalBC,pinC),GENEROUS);
		for(int variable=0; variable<3; variable++) {
			Assert.assertEquals(0,reduced.reducedValue(variable,0));
			Assert.assertEquals(-1,reduced.reducedValue(variable,1));
			Assert.assertEquals(-1,reduced.reducedValue(variable,2));
		}
	}

	@Test
	public void deferredScopeUsesCanonicalVariableEqualityAndValidPrefixesOnly() {
		Variable a = new Variable("deferred-equal-a",3);
		Variable b = new Variable("deferred-equal-b",3);
		Variable equalA = new Variable(a.key(),a.domainSize());
		Variable equalB = new Variable(b.key(),b.domainSize());
		Factor partial = Factor.lazy(List.of(equalA,equalB),new EqualitySupport());

		var reduced = ExactPhysicalReducedSolver.reducedModel(
			2,List.of(a,b),List.of(partial),GENEROUS);
		Assert.assertEquals(0,reduced.reducedValue(0,0));
		Assert.assertEquals(0,reduced.reducedValue(1,0));
	}

	public static void main(String[] args) {
		if(args.length != 1 || !args[0].equals("memory"))
			throw new IllegalArgumentException("memory mode required");
		int domain = 2_800;
		int retained = 2_200;
		Variable a = new Variable("deferred-memory-a",domain);
		Variable b = new Variable("deferred-memory-b",domain);
		Factor hard = Factor.lazy(List.of(a,b),new PrefixBoxSupport(retained));
		var reduced = ExactPhysicalReducedSolver.reducedModel(
			2,List.of(a,b),List.of(hard),new Limits(10_000_000,20_000_000));
		if(reduced.reducedValue(0,retained-1) < 0 || reduced.reducedValue(1,retained-1) < 0
			|| reduced.reducedValue(0,retained) >= 0 || reduced.reducedValue(1,retained) >= 0)
			throw new AssertionError("support mapping changed");
		System.out.println("DEFERRED_BINARY_MEMORY_PASS");
	}

	private static double cost(ExactPhysicalReducedSolver.CompactModel model,
		List<Integer> assignment) {
		return RegionalSearchProblem.evaluateFactors(
			model.variables(),model.factors(),assignment);
	}

	private static final class PrefixBoxSupport implements PartialHardCostFunction {
		private final int retained;
		private PrefixBoxSupport(int retained) { this.retained = retained; }
		@Override public PartialTruth partialTruth(int[] values) {
			if(values[0] >= retained || values[1] >= retained)
				return PartialTruth.ALL_FORBIDDEN;
			return values[0] >= 0 && values[1] >= 0
				? PartialTruth.ALL_ZERO : PartialTruth.UNKNOWN;
		}
		@Override public double cost(int[] values) {
			return values[0] < retained && values[1] < retained
				? 0d : Double.POSITIVE_INFINITY;
		}
	}

	private static final class TablePartialHard implements PartialHardCostFunction {
		private final double[] values;
		private final int width;
		private TablePartialHard(double[] values, int width) {
			this.values = values;
			this.width = width;
		}
		@Override public PartialTruth partialTruth(int[] selected) {
			if(selected[0] < 0 || selected[1] < 0)
				return PartialTruth.UNKNOWN;
			return Double.isFinite(cost(selected))
				? PartialTruth.ALL_ZERO : PartialTruth.ALL_FORBIDDEN;
		}
		@Override public double cost(int[] selected) {
			return values[selected[0]*width+selected[1]];
		}
	}

	private static final class EqualitySupport implements PartialHardCostFunction {
		@Override public PartialTruth partialTruth(int[] values) {
			boolean unassigned = false;
			for(int value : values) {
				if(value < 0)
					unassigned = true;
				else if(unassigned)
					throw new AssertionError("partial truth received a non-prefix assignment");
			}
			if(values[0] < 0 || values[1] < 0)
				return PartialTruth.UNKNOWN;
			return values[0] == values[1]
				? PartialTruth.ALL_ZERO : PartialTruth.ALL_FORBIDDEN;
		}
		@Override public double cost(int[] values) {
			return values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY;
		}
	}
}

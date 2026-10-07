/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactHardTableRepresentationTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 20_000_000);

	@Test
	public void hardFreezePreservesCanonicalCallsAndPromotesNumericTables() {
		var a = new ExactCategoricalSolver.Variable("a", 2);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		List<String> visits = new ArrayList<>();
		var hard = ExactCategoricalSolver.freezeValidatedFactor(ExactCategoricalSolver.Factor.lazy(
			List.of(a,b), values -> {
				visits.add(Arrays.toString(values));
				return values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY;
			}));
		Assert.assertTrue(hard.isHardTable());
		for(int cell=0; cell<6; cell++) {
			Assert.assertEquals(Arrays.toString(new int[] {cell/3,cell%3}),visits.get(cell));
			Assert.assertEquals(cell/3 == cell%3 ? 0d : Double.POSITIVE_INFINITY,
				hard.denseCostAt(cell),0d);
		}

		var numeric = ExactCategoricalSolver.freezeValidatedFactor(ExactCategoricalSolver.Factor.lazy(
			List.of(a,b), values -> values[0] == 0 && values[1] == 2 ? 0.25d
				: values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
		Assert.assertFalse(numeric.isHardTable());
		Assert.assertEquals(0.25d,numeric.denseCostAt(2),0d);
	}

	@Test
	public void hardWordsPreserveCellsAcrossWordBoundaries() {
		var constant = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(),v -> Double.POSITIVE_INFINITY));
		Assert.assertTrue(constant.isHardTable());
		Assert.assertEquals(Double.POSITIVE_INFINITY,constant.denseCostAt(0),0d);
		for(int size : new int[] {1,63,64,65}) {
			var value = new ExactCategoricalSolver.Variable("word-" + size,size);
			var frozen = ExactCategoricalSolver.freezeValidatedFactor(
				ExactCategoricalSolver.Factor.lazy(List.of(value),v ->
					v[0] == size - 1 ? Double.POSITIVE_INFINITY : 0d));
			Assert.assertTrue(frozen.isHardTable());
			for(int cell=0; cell<size; cell++)
				Assert.assertEquals(cell == size-1 ? Double.POSITIVE_INFINITY : 0d,
					frozen.denseCostAt(cell),0d);
		}
	}

	@Test
	public void compiledLazyHardFactorIsRematerializedForEverySolve() {
		var value = new ExactCategoricalSolver.Variable("changing",2);
		AtomicInteger allowed = new AtomicInteger(0);
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(value),v ->
			v[0] == allowed.get() ? 0d : Double.POSITIVE_INFINITY);
		var compiled = ExactCategoricalSolver.compile(List.of(value),List.of(factor),LIMITS);
		Assert.assertEquals(List.of(0),ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
		allowed.set(1);
		Assert.assertEquals(List.of(1),ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
	}

	@Test
	public void partialHardPromotionRestoresEarlierForbiddenAndZeroCells() {
		var value = new ExactCategoricalSolver.Variable("partial-promotion",4);
		AtomicInteger leaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction evaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					return ExactCategoricalSolver.PartialTruth.UNKNOWN;
				}
				@Override public double cost(int[] values) {
					leaves.incrementAndGet();
					return values[0] == 0 ? Double.POSITIVE_INFINITY
						: values[0] == 2 ? 0.25d : 0d;
				}
			};
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(value),evaluator));
		Assert.assertFalse(frozen.isHardTable());
		Assert.assertEquals(4,leaves.get());
		for(int cell=0; cell<4; cell++)
			Assert.assertEquals(Double.doubleToRawLongBits(
				cell == 0 ? Double.POSITIVE_INFINITY : cell == 2 ? 0.25d : 0d),
				Double.doubleToRawLongBits(frozen.denseCostAt(cell)));
	}

	@Test
	public void partialRangesCrossWordsBeforeNumericPromotion() {
		var row = new ExactCategoricalSolver.Variable("partial-row",3);
		var column = new ExactCategoricalSolver.Variable("partial-column",65);
		ExactCategoricalSolver.PartialHardCostFunction evaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					if(values[0] == 0 && values[1] < 0)
						return ExactCategoricalSolver.PartialTruth.ALL_ZERO;
					if(values[0] == 1 && values[1] < 0)
						return ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN;
					return ExactCategoricalSolver.PartialTruth.UNKNOWN;
				}
				@Override public double cost(int[] values) {
					return values[0] == 0 ? 0d : values[0] == 1 ? Double.POSITIVE_INFINITY
						: values[1] == 64 ? 0.25d : 0d;
				}
			};
		var partial = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(row,column),evaluator));
		var generic = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(row,column),v ->
				v[0] == 0 ? 0d : v[0] == 1 ? Double.POSITIVE_INFINITY
					: v[1] == 64 ? 0.25d : 0d));
		for(int cell=0; cell<195; cell++)
			Assert.assertEquals(Double.doubleToRawLongBits(generic.denseCostAt(cell)),
				Double.doubleToRawLongBits(partial.denseCostAt(cell)));
	}

	@Test
	public void hardBackingSurvivesDyadicSolveAndSingletonCompaction() {
		var singleton = new ExactCategoricalSolver.Variable("singleton",1);
		var value = new ExactCategoricalSolver.Variable("dyadic-hard",2);
		var denseHard = ExactCategoricalSolver.Factor.dense(List.of(singleton,value),0d,
			Double.POSITIVE_INFINITY);
		var lazyHard = ExactCategoricalSolver.Factor.lazy(List.of(singleton,value),v ->
			v[1] == 0 ? 0d : Double.POSITIVE_INFINITY);
		var numeric = ExactCategoricalSolver.Factor.dense(List.of(value),0.25d,0.5d);
		var certificate = ExactDyadicCosts.certify(-2,4,2,true);
		Assert.assertTrue(certificate.reason(),certificate.supported());
		var dense = ExactCategoricalSolver.solveDyadic(ExactCategoricalSolver.compile(
			List.of(singleton,value),List.of(numeric,denseHard),LIMITS),certificate);
		var hard = ExactCategoricalSolver.solveDyadic(ExactCategoricalSolver.compile(
			List.of(singleton,value),List.of(numeric,lazyHard),LIMITS),certificate);
		Assert.assertEquals(Double.doubleToRawLongBits(dense.objective()),
			Double.doubleToRawLongBits(hard.objective()));
		Assert.assertEquals(dense.assignmentInVariableOrder(),hard.assignmentInVariableOrder());
		var compact = ExactPhysicalReducedSolver.compactModel(2,List.of(singleton,value),
			List.of(lazyHard,numeric),LIMITS);
		Assert.assertTrue(compact.factors().stream().anyMatch(
			ExactCategoricalSolver.Factor::isHardTable));
		var denseProjected = ExactCategoricalSolver.projectSingletons(
			ExactCategoricalSolver.boundaryLeaf(List.of(singleton,value),denseHard,LIMITS));
		var hardProjected = ExactCategoricalSolver.projectSingletons(
			ExactCategoricalSolver.boundaryLeaf(List.of(singleton,value),lazyHard,LIMITS));
		assertMessageSummaries(denseProjected,hardProjected,List.of(value));
	}

	@Test
	public void denseAndHardRepresentationsHaveIdenticalSolveAndBoundaryResults() {
		var a = new ExactCategoricalSolver.Variable("a", 3);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		double[] hardValues = {
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, 0d, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d};
		var denseHard = ExactCategoricalSolver.Factor.dense(List.of(a,b),hardValues);
		var lazyHard = ExactCategoricalSolver.Factor.lazy(List.of(a,b),
			values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
		var numeric = ExactCategoricalSolver.Factor.dense(List.of(a),3d,2d,1d);
		var dense = ExactCategoricalSolver.solve(List.of(a,b),List.of(denseHard,numeric),LIMITS,
			(variable,value) -> value);
		var hard = ExactCategoricalSolver.solve(List.of(a,b),List.of(lazyHard,numeric),LIMITS,
			(variable,value) -> value);
		Assert.assertEquals(Double.doubleToRawLongBits(dense.objective()),
			Double.doubleToRawLongBits(hard.objective()));
		Assert.assertEquals(dense.assignmentInVariableOrder(),hard.assignmentInVariableOrder());

		var denseLeaf = ExactCategoricalSolver.boundaryLeaf(List.of(a,b),denseHard,LIMITS);
		var hardLeaf = ExactCategoricalSolver.boundaryLeaf(List.of(a,b),lazyHard,LIMITS);
		assertMessageSummaries(denseLeaf,hardLeaf,List.of(a,b));
		for(int av=0; av<3; av++) for(int bv=0; bv<3; bv++) {
			int[] assignment = {av,bv};
			Assert.assertEquals(Double.doubleToRawLongBits(
				denseLeaf.valueForAssignment(assignment,List.of(a,b))),Double.doubleToRawLongBits(
				hardLeaf.valueForAssignment(assignment,List.of(a,b))));
		}

		assertMergedBoundaryParity(a,b,hardValues,(av,bv) -> av == bv);
		double[] majorityFinite = {
			Double.POSITIVE_INFINITY,0d,0d, 0d,0d,0d, 0d,0d,0d};
		assertMergedBoundaryParity(a,b,majorityFinite,(av,bv) -> av != 0 || bv != 0);
	}

	@Test
	public void hardCompressionDoesNotHideEarlierOverflow() {
		var a = new ExactCategoricalSolver.Variable("overflow", 1);
		var huge = ExactCategoricalSolver.Factor.dense(List.of(a),Double.MAX_VALUE);
		var denseHard = ExactCategoricalSolver.Factor.dense(List.of(a),Double.POSITIVE_INFINITY);
		var lazyHard = ExactCategoricalSolver.Factor.lazy(List.of(a),
			values -> Double.POSITIVE_INFINITY);
		IllegalArgumentException dense = failure(
			() -> ExactCategoricalSolver.solve(List.of(a),List.of(huge,huge,denseHard),LIMITS));
		IllegalArgumentException hard = failure(
			() -> ExactCategoricalSolver.solve(List.of(a),List.of(huge,huge,lazyHard),LIMITS));
		Assert.assertEquals(dense.getMessage(),hard.getMessage());
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW",hard.getMessage());
	}

	@Test
	public void reducedHardTablesFitWithoutDenseCartesianStorage() throws Exception {
		String java = Path.of(System.getProperty("java.home"),"bin","java").toString();
		Process process = new ProcessBuilder(java,"-Xmx48m","-cp",System.getProperty("java.class.path"),
			getClass().getName()).redirectErrorStream(true).start();
		try {
			Assert.assertTrue("hard-table child timed out",process.waitFor(60,TimeUnit.SECONDS));
			String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
			Assert.assertEquals(output,0,process.exitValue());
			Assert.assertTrue(output,output.contains("HARD_TABLE_MEMORY_PASS"));
		}
		finally { process.destroyForcibly(); }
	}

	public static void main(String[] args) {
		var a = new ExactCategoricalSolver.Variable("large-a",400);
		var b = new ExactCategoricalSolver.Variable("large-b",400);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int factor=0; factor<40; factor++)
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(a,b),values ->
				values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
		var reduced = ExactPhysicalReducedSolver.reducedModel(2,List.of(a,b),factors,
			new ExactCategoricalSolver.Limits(200_000,8_000_000));
		if(reduced.factors().size() != 40 || reduced.factors().stream().anyMatch(
			factor -> !factor.isHardTable()))
			throw new AssertionError("hard tables were reinflated");
		System.out.println("HARD_TABLE_MEMORY_PASS");
	}

	private static IllegalArgumentException failure(Runnable action) {
		try { action.run(); }
		catch(IllegalArgumentException failure) { return failure; }
		throw new AssertionError("expected IllegalArgumentException");
	}

	private static void assertMergedBoundaryParity(ExactCategoricalSolver.Variable a,
		ExactCategoricalSolver.Variable b, double[] denseValues, CellPredicate allowed) {
		var denseHard = ExactCategoricalSolver.Factor.dense(List.of(a,b),denseValues);
		var lazyHard = ExactCategoricalSolver.Factor.lazy(List.of(a,b),v ->
			allowed.test(v[0],v[1]) ? 0d : Double.POSITIVE_INFINITY);
		var bCost = ExactCategoricalSolver.Factor.dense(List.of(b),2d,1d,0d);
		var dense = ExactCategoricalSolver.mergeBoundary(List.of(
			ExactCategoricalSolver.boundaryLeaf(List.of(a,b),denseHard,LIMITS),
			ExactCategoricalSolver.boundaryLeaf(List.of(a,b),bCost,LIMITS)),List.of(a),LIMITS,100L);
		var hard = ExactCategoricalSolver.mergeBoundary(List.of(
			ExactCategoricalSolver.boundaryLeaf(List.of(a,b),lazyHard,LIMITS),
			ExactCategoricalSolver.boundaryLeaf(List.of(a,b),bCost,LIMITS)),List.of(a),LIMITS,100L);
		for(int av=0; av<3; av++) {
			int[] denseAssignment = {av,0};
			int[] hardAssignment = {av,0};
			Assert.assertEquals(Double.doubleToRawLongBits(
				dense.valueForAssignment(denseAssignment,List.of(a,b))),Double.doubleToRawLongBits(
				hard.valueForAssignment(hardAssignment,List.of(a,b))));
			dense.decodeInto(denseAssignment,List.of(a,b));
			hard.decodeInto(hardAssignment,List.of(a,b));
			Assert.assertArrayEquals(denseAssignment,hardAssignment);
		}
	}

	private static void assertMessageSummaries(ExactCategoricalSolver.BoundaryMessage dense,
		ExactCategoricalSolver.BoundaryMessage hard,
		List<ExactCategoricalSolver.Variable> variables) {
		Assert.assertEquals(Double.doubleToRawLongBits(dense.minimum()),
			Double.doubleToRawLongBits(hard.minimum()));
		Assert.assertEquals(Double.doubleToRawLongBits(dense.lowerBound()),
			Double.doubleToRawLongBits(hard.lowerBound()));
		for(var variable : variables) {
			for(int value=0; value<variable.domainSize(); value++)
				Assert.assertEquals(Double.doubleToRawLongBits(dense.minMarginal(variable,value)),
					Double.doubleToRawLongBits(hard.minMarginal(variable,value)));
			Assert.assertArrayEquals(dense.minMarginals(variable),hard.minMarginals(variable),0d);
			Assert.assertArrayEquals(dense.lowerMinMarginals(variable),
				hard.lowerMinMarginals(variable),0d);
		}
	}

	@FunctionalInterface
	private interface CellPredicate { boolean test(int left, int right); }
}

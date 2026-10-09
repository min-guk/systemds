/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalFactorwiseCertificateIntegrationTest {
	private static final Limits LIMITS = new Limits(100_000,1_000_000);

	@Test
	public void hardNeighborhoodCertificatePreservesIncumbentAndReplaysWithoutPreparation()
		throws Exception {
		Variable left = new Variable("integration-hard-left",2);
		Variable right = new Variable("integration-hard-right",2);
		List<Variable> variables = List.of(left,right);
		List<Factor> factors = List.of(Factor.finiteSupport(variables,0,3));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,factors);
		IncrementalRegionalOptimizer optimizer = optimizer(problem,List.of(1,1));
		installNeighborhood(optimizer,new int[] {0,1});

		refine(optimizer);
		Assert.assertEquals("the assignment cap excludes ordinary two-value elimination",1,
			conditionalAttempts(optimizer));
		Assert.assertEquals(1,replaySize(optimizer));
		Assert.assertEquals(List.of(1,1),expandedIncumbent(optimizer));
		Assert.assertEquals(Double.doubleToRawLongBits(0d),Double.doubleToRawLongBits(upper(optimizer)));
		Assert.assertEquals(Double.doubleToRawLongBits(
			ExactCategoricalSolver.solve(variables,factors,LIMITS).objective()),
			Double.doubleToRawLongBits(upper(optimizer)));
		assertLastConditionalCheckpoint(optimizer,1);

		refine(optimizer);
		Assert.assertEquals("the second visit must replay the certified block",1,
			conditionalAttempts(optimizer));
		Assert.assertEquals(1,replaySize(optimizer));
		Assert.assertEquals(List.of(1,1),expandedIncumbent(optimizer));
		assertLastConditionalCheckpoint(optimizer,1);
	}

	@Test
	public void numericNeighborhoodCannotBorrowTheHardFactorCertificate() throws Exception {
		Variable left = new Variable("integration-numeric-left",2);
		Variable right = new Variable("integration-numeric-right",2);
		List<Variable> variables = List.of(left,right);
		List<Factor> factors = List.of(Factor.dense(variables,
			Double.MIN_VALUE,Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY,Double.MIN_VALUE));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,factors);
		IncrementalRegionalOptimizer optimizer = optimizer(problem,List.of(1,1));
		installNeighborhood(optimizer,new int[] {0,1});

		refine(optimizer);
		Assert.assertEquals("numeric factors must take the capped legacy preparation path",0,
			conditionalAttempts(optimizer));
		Assert.assertEquals(0,replaySize(optimizer));
		Assert.assertEquals(List.of(1,1),expandedIncumbent(optimizer));
		Assert.assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE),
			Double.doubleToRawLongBits(upper(optimizer)));
	}

	private static IncrementalRegionalOptimizer optimizer(RegionalSearchProblem problem,
		List<Integer> seed) throws Exception {
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(LIMITS);
		Field merger = IncrementalRegionalOptimizer.class.getDeclaredField("DEFAULT_BOUNDARY_MERGER");
		merger.setAccessible(true);
		Constructor<IncrementalRegionalOptimizer> constructor =
			IncrementalRegionalOptimizer.class.getDeclaredConstructor(RegionalSearchProblem.class,
				ExactPhysicalReducedSolver.CompactModel.class,List.class,Limits.class,
				IncrementalRegionalOptimizer.Options.class,Consumer.class,
				ExactCategoricalSolver.BoundaryMergeCounters.class,
				IncrementalRegionalOptimizer.BoundaryMerger.class);
		constructor.setAccessible(true);
		return constructor.newInstance(problem,root,seed,LIMITS,
			new IncrementalRegionalOptimizer.Options(0d,1L,100L,0L,4,false),
			(Consumer<IncrementalRegionalOptimizer.Checkpoint>)ignored -> { },null,
			(IncrementalRegionalOptimizer.BoundaryMerger)merger.get(null));
	}

	@SuppressWarnings("unchecked")
	private static void installNeighborhood(IncrementalRegionalOptimizer optimizer, int[] block)
		throws Exception {
		Class<?> type = Class.forName(IncrementalRegionalOptimizer.class.getName() + "$Neighborhood");
		Constructor<?> constructor = type.getDeclaredConstructor(
			int[].class,double.class,int.class,long.class,long.class,int.class);
		constructor.setAccessible(true);
		Object neighborhood = constructor.newInstance(block,0d,0,4L,4L,0);
		Field rejected = IncrementalRegionalOptimizer.class.getDeclaredField("rejectedNeighborhoods");
		rejected.setAccessible(true);
		((Map<String,Object>)rejected.get(optimizer)).put(Arrays.toString(block),neighborhood);
	}

	private static void refine(IncrementalRegionalOptimizer optimizer) throws Exception {
		Method method = IncrementalRegionalOptimizer.class.getDeclaredMethod(
			"refineNeighborhoodsImpl",String.class);
		method.setAccessible(true);
		method.invoke(optimizer,"CONDITIONAL");
	}

	private static int conditionalAttempts(IncrementalRegionalOptimizer optimizer) throws Exception {
		return (int)field(optimizer,"conditionalAttempts");
	}

	private static int replaySize(IncrementalRegionalOptimizer optimizer) throws Exception {
		IncrementalRegionalOptimizer.ConditionalReplayCache cache =
			(IncrementalRegionalOptimizer.ConditionalReplayCache)field(optimizer,"conditionalReplayCache");
		return cache.size();
	}

	private static List<Integer> expandedIncumbent(IncrementalRegionalOptimizer optimizer)
		throws Exception {
		int[] compact = ((int[])field(optimizer,"incumbent")).clone();
		ExactPhysicalReducedSolver.CompactModel root =
			(ExactPhysicalReducedSolver.CompactModel)field(optimizer,"root");
		return root.expandAssignment(Arrays.stream(compact).boxed().toList());
	}

	private static double upper(IncrementalRegionalOptimizer optimizer) throws Exception {
		return (double)field(optimizer,"upper");
	}

	@SuppressWarnings("unchecked")
	private static void assertLastConditionalCheckpoint(IncrementalRegionalOptimizer optimizer,
		int attempts) throws Exception {
		List<IncrementalRegionalOptimizer.Checkpoint> checkpoints =
			(List<IncrementalRegionalOptimizer.Checkpoint>)field(optimizer,"checkpoints");
		IncrementalRegionalOptimizer.Checkpoint checkpoint = checkpoints.get(checkpoints.size()-1);
		Assert.assertEquals("CONDITIONAL",checkpoint.phase());
		Assert.assertEquals(attempts,checkpoint.conditionalAttempts());
		Assert.assertEquals(0,checkpoint.conditionalImprovements());
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.HardTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactReducedHardProfileHashTest {
	private static final Limits LIMITS = new Limits(5_000_000,20_000_000);

	@Test
	public void functionalBothAxesActiveHolesAndMissingTargetsMatchFullEquality() throws Exception {
		Variable source = new Variable("functional-source",9);
		Variable target = new Variable("functional-target",7);
		int[] mapping = {4,2,-1,4,0,6,2,-1,0};
		FrozenInputs frozen = freeze(List.of(source,target),
			List.of(Factor.functionalMap(source,target,mapping)));
		boolean[][] active = {{true,false,true,true,true,false,true,true,true},
			{true,true,true,false,true,false,true}};
		assertClassesMatchForcedCollision(frozen,active,0,List.of(0));
		assertClassesMatchForcedCollision(frozen,active,1,List.of(0));
		Assert.assertEquals(0L,compileStats(frozen,active,1).cellReads);
		CompileStats stats = compileStats(frozen,active,2);
		Assert.assertEquals(0L,stats.cellReads);
		Assert.assertEquals(7L,stats.functionalRows);
	}

	@Test
	public void functionalTargetOnlyPrefixUsesActiveSourceRowsAndGroupsEmptyPreimages() throws Exception {
		Variable target = new Variable("target-prefix",7);
		Variable source = new Variable("source-suffix",9);
		int[] mapping = {4,2,-1,4,0,6,2,-1,0};
		FrozenInputs frozen = freeze(List.of(target,source),
			List.of(Factor.functionalMap(source,target,mapping)));
		boolean[][] active = {{true,true,true,true,true,true,true},
			{true,false,true,true,true,false,true,true,true}};
		int[][] classes = assertClassesMatchForcedCollision(frozen,active,0,List.of(0));
		Assert.assertTrue("empty target preimages with equal ties must share one class",
			Arrays.stream(classes).anyMatch(values -> Arrays.equals(values,new int[] {3,6})));
		CompileStats stats = compileStats(frozen,active,1);
		Assert.assertEquals(0L,stats.cellReads);
		Assert.assertEquals(7L,stats.functionalRows);
	}

	@Test
	public void packedPolarityBoundariesPermutedScopesAndActiveHolesStayExact() throws Exception {
		for(int[] shape : List.of(new int[] {7,9},new int[] {8,8},new int[] {5,13})) {
			for(int mode = 0; mode < 4; mode++) {
				Variable left = new Variable("left-" + shape[0] + '-' + mode,shape[0]);
				Variable right = new Variable("right-" + shape[1] + '-' + mode,shape[1]);
				List<Variable> scope = mode % 2 == 0 ? List.of(left,right) : List.of(right,left);
				int cells = shape[0] * shape[1];
				HardTable table = HardTable.allocate(cells);
				for(int cell = 0; cell < cells; cell++)
					if(forbidden(mode,cell,cells)) table.forbid(cell);
				FrozenInputs frozen = freeze(scope,List.of(Factor.hardOwned(scope,table.compactAllFeasible())));
				boolean[][] active = new boolean[2][];
				for(int variable = 0; variable < 2; variable++) {
					active[variable] = new boolean[scope.get(variable).domainSize()];
					for(int value = 0; value < active[variable].length; value++)
						active[variable][value] = value % 4 != 1;
				}
				assertClassesMatchForcedCollision(frozen,active,0,List.of(0));
				assertClassesMatchForcedCollision(frozen,active,1,List.of(0));
				CompileStats stats = compileStats(frozen,active,2);
				int forbidden = 0;
				for(int cell = 0; cell < cells; cell++)
					if(forbidden(mode,cell,cells)) forbidden++;
				int exceptions = Math.min(forbidden,cells - forbidden);
				long activeCartesian = Arrays.stream(active)
					.mapToLong(axis -> countActive(axis)).reduce(1L,Math::multiplyExact);
				long sparseWork = exceptions == 0 ? 0L : (cells + 63L) / 64L + 2L * exceptions;
				boolean sparse = sparseWork < activeCartesian;
				Assert.assertEquals(sparse ? 0L : activeCartesian,stats.cellReads);
				Assert.assertEquals(sparse && exceptions != 0 ? (cells + 63L) / 64L : 0L,
					stats.hardWords);
				Assert.assertEquals(sparse ? exceptions : 0L,stats.hardExceptions);
			}
		}
	}

	@Test
	public void unarySingletonAndNullaryHardFactorsKeepExactClasses() throws Exception {
		Variable value = new Variable("unary-value",7);
		HardTable unary = HardTable.allocate(7);
		unary.forbid(1);
		unary.forbid(5);
		HardTable nullary = HardTable.allocate(1);
		FrozenInputs frozen = freeze(List.of(value),List.of(
			Factor.hardOwned(List.of(),nullary.compactAllFeasible()),
			Factor.hardOwned(List.of(value),unary)));
		boolean[][] active = {{true,false,true,true,true,true,false}};
		assertClassesMatchForcedCollision(frozen,active,0,List.of(1));
		Assert.assertEquals(0L,compileReads(frozen,active,1));
	}

	@Test
	public void reboundFunctionalBackingUsesExactDenseTraversal() throws Exception {
		Variable source = new Variable("rebound-source",7), target = new Variable("rebound-target",3);
		Factor original = freeze(List.of(source,target),List.of(
			Factor.functionalMap(source,target,new int[] {2,-1,0,2,1,2,0}))).factor(0);
		Variable singleton = new Variable("rebound-singleton",1);
		Factor projected = original.projectFunctionalMap(List.of(source,singleton),
			new int[] {0,1,2,3,4,5,6},new int[] {2});
		Factor unary = projected.rebindOwned(List.of(source));
		FrozenInputs frozen = freeze(List.of(source),List.of(unary));
		boolean[][] active = {{true,false,true,true,true,true,false}};
		assertClassesMatchForcedCollision(frozen,active,0,List.of(0));
		Assert.assertEquals(5L,compileReads(frozen,active,1));
	}

	@Test
	public void repeatedHardAndMixedNumericPreserveSolveAndCanonicalRepresentatives() throws Exception {
		Variable a = new Variable("mixed-a",6), b = new Variable("mixed-b",5), c = new Variable("mixed-c",3);
		HardTable hard = HardTable.allocate(30);
		for(int cell = 0; cell < 30; cell++) if(cell % 7 == 1 || cell % 11 == 0) hard.forbid(cell);
		Factor relation = Factor.hardOwned(List.of(a,b),hard);
		Factor numeric = Factor.dense(List.of(b,c),0d,1d,2d, 0d,1d,2d, 3d,1d,0d,
			3d,1d,0d, 2d,2d,1d);
		List<Variable> variables = List.of(a,b,c);
		List<Factor> factors = List.of(relation,numeric,relation,
			Factor.dense(List.of(a),4d,3d,2d,1d,0.5d,0.25d));
		FrozenInputs frozen = freeze(variables,factors);
		boolean[][] active = fullActive(variables);
		for(int variable = 0; variable < variables.size(); variable++)
			assertClassesMatchForcedCollision(frozen,active,variable,incident(frozen,variable));
		Assert.assertEquals("only the 5x3 numeric table and unary table are read",21L,
			compileReads(frozen,active,3));
		var expected = ExactCategoricalSolver.solve(variables,factors,LIMITS);
		var actual = ExactPhysicalReducedSolver.solve(3,variables,factors,LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(),actual.assignmentInVariableOrder());
	}

	@Test(timeout = 20_000)
	public void largeClosedRepresentationsNeedNoDenseHashReads() throws Exception {
		int size = 40_000;
		Variable source = new Variable("large-source",size), target = new Variable("large-target",size);
		int[] mapping = new int[size];
		for(int row = 0; row < size; row++) mapping[row] = row % 97 == 0 ? -1 : (row * 31 + 7) % size;
		FrozenInputs functional = ExactCategoricalSolver.freezeInputs(List.of(source,target),
			List.of(Factor.functionalMap(source,target,mapping)),
			new Limits(Integer.MAX_VALUE,Integer.MAX_VALUE));
		CompileStats functionalStats = compileStats(functional,fullActive(List.of(source,target)),2);
		Assert.assertEquals(0L,functionalStats.cellReads);
		Assert.assertEquals(size,functionalStats.functionalRows);

		Variable x = new Variable("sparse-x",1000), y = new Variable("sparse-y",1000);
		HardTable sparse = HardTable.allocate(1_000_000);
		for(int cell = 3; cell < sparse.cells(); cell += 7919) sparse.forbid(cell);
		FrozenInputs packed = freeze(List.of(x,y),List.of(Factor.hardOwned(List.of(x,y),sparse)));
		CompileStats packedStats = compileStats(packed,fullActive(List.of(x,y)),2);
		Assert.assertEquals(0L,packedStats.cellReads);
		Assert.assertEquals(15_625L,packedStats.hardWords);
		Assert.assertEquals(127L,packedStats.hardExceptions);
	}

	@Test
	public void thinAndEmptyActiveDomainsUseWholeFactorCartesianFallback() throws Exception {
		Variable x = new Variable("thin-x",1000), y = new Variable("thin-y",1000);
		HardTable half = HardTable.allocate(1_000_000);
		for(int cell = 0; cell < half.cells(); cell += 2) half.forbid(cell);
		FrozenInputs frozen = freeze(List.of(x,y),List.of(Factor.hardOwned(List.of(x,y),half)));
		boolean[][] thin = {new boolean[1000],new boolean[1000]};
		thin[0][17] = thin[0][731] = true;
		thin[1][23] = thin[1][911] = true;
		CompileStats thinStats = compileStats(frozen,thin,2);
		Assert.assertEquals(4L,thinStats.cellReads);
		Assert.assertEquals(0L,thinStats.hardWords);
		Assert.assertEquals(0L,thinStats.hardExceptions);
		assertClassesMatchForcedCollision(frozen,thin,0,List.of(0));
		assertClassesMatchForcedCollision(frozen,thin,1,List.of(0));

		boolean[][] empty = {thin[0].clone(),new boolean[1000]};
		CompileStats emptyStats = compileStats(frozen,empty,2);
		Assert.assertEquals(0L,emptyStats.cellReads);
		Assert.assertEquals(0L,emptyStats.hardWords);
		Assert.assertEquals(0L,emptyStats.hardExceptions);
	}

	private static boolean forbidden(int mode, int cell, int cells) {
		return switch(mode) {
			case 0 -> false;
			case 1 -> true;
			case 2 -> (cell & 1) == 0;
			default -> cell % 5 != 0 && cell != cells - 1;
		};
	}

	private static FrozenInputs freeze(List<Variable> variables,List<Factor> factors) {
		return ExactCategoricalSolver.freezeInputs(variables,factors,LIMITS);
	}

	private static boolean[][] fullActive(List<Variable> variables) {
		boolean[][] active = new boolean[variables.size()][];
		for(int variable = 0; variable < variables.size(); variable++) {
			active[variable] = new boolean[variables.get(variable).domainSize()];
			Arrays.fill(active[variable],true);
		}
		return active;
	}

	private static int countActive(boolean[] values) {
		int count = 0;
		for(boolean value : values)
			if(value) count++;
		return count;
	}

	private static List<Integer> incident(FrozenInputs frozen,int variable) {
		java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
		for(int factor = 0; factor < frozen.factorCount(); factor++)
			for(int scoped : frozen.scope(factor)) if(scoped == variable) { result.add(factor); break; }
		return result;
	}

	private static int[][] assertClassesMatchForcedCollision(FrozenInputs frozen,boolean[][] active,
		int variable,List<Integer> incident) throws Exception {
		long[] ties = new long[active[variable].length];
		for(int value = 0; value < ties.length; value++) ties[value] = value % 3;
		Method quotient = ExactPhysicalReducedSolver.class.getDeclaredMethod("quotientClasses",
			FrozenInputs.class,int.class,boolean[][].class,List.class,long[].class,boolean.class);
		quotient.setAccessible(true);
		int[][] expected = (int[][])quotient.invoke(null,frozen,variable,active,incident,ties,true);
		int[][] actual = (int[][])quotient.invoke(null,frozen,variable,active,incident,ties,false);
		Assert.assertTrue("variable=" + variable + " expected=" + Arrays.deepToString(expected)
			+ " actual=" + Arrays.deepToString(actual),Arrays.deepEquals(expected,actual));
		return actual;
	}

	private static long compileReads(FrozenInputs frozen,boolean[][] active,int prefix) throws Exception {
		return compileStats(frozen,active,prefix).cellReads;
	}

	private static CompileStats compileStats(FrozenInputs frozen,boolean[][] active,int prefix)
		throws Exception {
		long[][] ties = new long[active.length][];
		for(int variable = 0; variable < active.length; variable++) ties[variable] = new long[active[variable].length];
		Method compile = ExactPhysicalReducedSolver.class.getDeclaredMethod("compileObservationHashes",
			FrozenInputs.class,boolean[][].class,long[][].class,int.class,int[].class);
		compile.setAccessible(true);
		Object hashes = compile.invoke(null,frozen,active,ties,prefix,null);
		Field reads = hashes.getClass().getDeclaredField("cellReads");
		Field words = hashes.getClass().getDeclaredField("hardWords");
		Field exceptions = hashes.getClass().getDeclaredField("hardExceptions");
		Field functionalRows = hashes.getClass().getDeclaredField("functionalRows");
		reads.setAccessible(true);
		words.setAccessible(true);
		exceptions.setAccessible(true);
		functionalRows.setAccessible(true);
		return new CompileStats(reads.getLong(hashes),words.getLong(hashes),
			exceptions.getLong(hashes),functionalRows.getLong(hashes));
	}

	private record CompileStats(long cellReads,long hardWords,long hardExceptions,
		long functionalRows) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class BoundaryProjectionOdometerTest {
	private static final Limits LIMITS = new Limits(2_000_000,20_000_000);
	private static final Field VALUES = field("values");
	private static final Field LOW = field("lowValues");
	private static final Field LOWER = field("lowerValues");
	private static final Field CHOICES = field("unionChoices");

	@After public void clearAblation() { System.clearProperty(PruningAblation.PROPERTY); }

	@Test
	public void everyProjectedChildCellMatchesIndependentEncodingAndReducesWork() throws Exception {
		List<Variable> variables = variables(2,3,2,4);
		List<List<Variable>> scopes = List.of(
			List.of(variables.get(3),variables.get(0),variables.get(1)),
			List.of(variables.get(2),variables.get(1)),
			List.of(variables.get(0),variables.get(3)),
			List.of(variables.get(1)));
		List<Factor> factors = new ArrayList<>();
		for(int index=0; index<scopes.size(); index++)
			factors.add(Factor.dense(scopes.get(index),pattern(cells(scopes.get(index)),index)));
		List<BoundaryMessage> messages = ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS);
		int[] domains = {2,3,2,4};
		int[] outputScope = {2,0};
		int[] internalScope = {1,3};
		int[] assignment = new int[variables.size()];
		Odometer odometer = odometer(messages,internalScope,domains,assignment);
		long legacyTerms = 0;
		long odometerTerms = 0;
		int outputCells = domains[2]*domains[0];
		int internalCells = domains[1]*domains[3];
		for(int outputCell=0; outputCell<outputCells; outputCell++) {
			decode(outputCell,outputScope,domains,assignment);
			odometer.initialize.invoke(odometer.instance);
			odometerTerms += scopes.stream().mapToInt(List::size).sum();
			int[] expected = assignment.clone();
			for(int internalCell=0; internalCell<internalCells; internalCell++) {
				if(internalCell>0) {
					int[] before=expected.clone();
					odometer.advance.invoke(odometer.instance);
					decode(internalCell,internalScope,domains,expected);
					for(int axis=internalScope.length-1; axis>=0; axis--) {
						int variable=internalScope[axis];
						if(before[variable]!=expected[variable])
							odometerTerms += scopes.stream().filter(scope -> scope.contains(variables.get(variable))).count();
						if(expected[variable]!=0)
							break;
					}
				}
				Assert.assertArrayEquals(expected,assignment);
				for(int message=0; message<messages.size(); message++)
					Assert.assertEquals(encode(expected,scopes.get(message),variables,domains),
						((Integer)odometer.childCell.invoke(odometer.instance,message)).intValue());
				legacyTerms += scopes.stream().mapToInt(List::size).sum();
			}
		}
		Assert.assertEquals(384L,legacyTerms);
		Assert.assertEquals(144L,odometerTerms);
		Assert.assertTrue(odometerTerms < legacyTerms);
		System.out.println("BOUNDARY_PROJECTION_TERMS="+legacyTerms+':'+odometerTerms);
	}

	@Test
	public void randomizedPermutedScopesMatchIndependentLegacyEnumeration() throws Exception {
		for(String pruning : List.of("baseline","local_only")) {
			System.setProperty(PruningAblation.PROPERTY,pruning);
			for(int seed=0; seed<50; seed++) {
				Random random=new Random(0x51deL+seed);
				int[] domains={2+random.nextInt(2),2+random.nextInt(3),2+random.nextInt(2),2+random.nextInt(2)};
				List<Variable> variables=variables(domains);
				List<List<Variable>> scopes=new ArrayList<>();
				scopes.add(List.of(variables.get(2),variables.get(0),variables.get(1)));
				scopes.add(List.of(variables.get(3),variables.get(1)));
				scopes.add(List.of(variables.get(0),variables.get(3),variables.get(2)));
				List<double[]> tables=new ArrayList<>();
				List<Factor> factors=new ArrayList<>();
				for(int factor=0; factor<scopes.size(); factor++) {
					double[] table=randomTable(cells(scopes.get(factor)),random,factor);
					tables.add(table);
					factors.add(Factor.dense(scopes.get(factor),table));
				}
				List<Variable> boundary=seed%2==0
					? List.of(variables.get(2),variables.get(0)) : List.of(variables.get(3));
				BoundaryMessage actual=ExactCategoricalSolver.mergeBoundary(
					ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS),boundary,LIMITS);
				Snapshot observed=snapshot(actual);
				Snapshot expected=legacy(variables,scopes,tables,boundary,domains);
				assertRaw(expected.high,observed.high);
				assertRaw(expected.low,observed.low);
				assertRaw(expected.lower,observed.lower);
				Assert.assertArrayEquals(expected.choices,observed.choices);
				for(int output=0; output<observed.choices.length; output++) {
					if(observed.choices[output] < 0)
						continue;
					int[] decoded=new int[variables.size()];
					Arrays.fill(decoded,-1);
					decode(output,indexes(boundary,variables),domains,decoded);
					actual.decodeInto(decoded,variables);
					Assert.assertEquals(observed.choices[output],encode(decoded,range(variables.size()),domains));
				}
			}
		}
	}

	@Test
	public void zeroAndSingletonInternalScopesPreserveNullaryChild() throws Exception {
		Variable a=new Variable("edge-a",2);
		Variable singleton=new Variable("edge-singleton",1);
		List<Variable> variables=List.of(a,singleton);
		List<Factor> factors=List.of(Factor.dense(List.of(),0.5d),
			Factor.dense(List.of(a),1d,2d),Factor.dense(List.of(singleton),0.25d));
		List<BoundaryMessage> leaves=ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS);
		ExactCategoricalSolver.BoundaryMergeCounters zeroCounters=
			new ExactCategoricalSolver.BoundaryMergeCounters();
		Snapshot zero=snapshot(ExactCategoricalSolver.mergeBoundary(
			leaves,List.of(singleton,a),LIMITS,zeroCounters));
		ExactCategoricalSolver.BoundaryMergeCounters singletonCounters=
			new ExactCategoricalSolver.BoundaryMergeCounters();
		Snapshot one=snapshot(ExactCategoricalSolver.mergeBoundary(
			leaves,List.of(a),LIMITS,singletonCounters));
		assertRaw(new double[]{1.75d,2.75d},zero.high);
		assertRaw(zero.high,one.high);
		assertRaw(zero.low,one.low);
		assertRaw(zero.lower,one.lower);
		Assert.assertArrayEquals(new int[]{0,1},zero.choices);
		Assert.assertArrayEquals(zero.choices,one.choices);
		Assert.assertEquals(6L,zeroCounters.childEvaluations());
		Assert.assertEquals(zeroCounters.childEvaluations(),singletonCounters.childEvaluations());
	}

	private static Snapshot legacy(List<Variable> variables,List<List<Variable>> scopes,
		List<double[]> tables,List<Variable> boundary,int[] domains) {
		int[] outputScope=indexes(boundary,variables);
		boolean[] output=new boolean[variables.size()];
		for(int variable:outputScope) output[variable]=true;
		int[] internal=range(variables.size());
		internal=Arrays.stream(internal).filter(variable -> !output[variable]).toArray();
		int outputCells=product(outputScope,domains);
		int internalCells=product(internal,domains);
		double[] high=new double[outputCells],low=new double[outputCells],lower=new double[outputCells];
		int[] choices=new int[outputCells];
		int[] assignment=new int[variables.size()];
		for(int outputCell=0; outputCell<outputCells; outputCell++) {
			decode(outputCell,outputScope,domains,assignment);
			Cost best=Cost.infinity();
			double bestLower=Double.POSITIVE_INFINITY;
			int choice=-1;
			for(int internalCell=0; internalCell<internalCells; internalCell++) {
				decode(internalCell,internal,domains,assignment);
				Cost candidate=Cost.zero();
				double candidateLower=0d;
				for(int factor=0; factor<tables.size(); factor++) {
					double value=tables.get(factor)[encode(assignment,scopes.get(factor),variables,domains)];
					candidate=candidate.plus(value,0d);
					candidateLower=addLower(candidateLower,value);
				}
				if(candidate.compareTo(best)<0) {
					best=candidate;
					choice=encode(assignment,range(variables.size()),domains);
				}
				bestLower=Math.min(bestLower,candidateLower);
			}
			high[outputCell]=best.high;
			low[outputCell]=best.low;
			lower[outputCell]=bestLower;
			choices[outputCell]=choice;
		}
		return new Snapshot(high,low,lower,choices);
	}

	private static Odometer odometer(List<BoundaryMessage> messages,int[] internalScope,
		int[] domains,int[] assignment) throws Exception {
		Class<?> type=Class.forName(ExactCategoricalSolver.class.getName()+"$BoundaryProjectionOdometer");
		Constructor<?> constructor=type.getDeclaredConstructor(List.class,int[].class,int[].class,int[].class);
		constructor.setAccessible(true);
		Method initialize=type.getDeclaredMethod("initialize");
		Method advance=type.getDeclaredMethod("advance");
		Method childCell=type.getDeclaredMethod("childCell",int.class);
		initialize.setAccessible(true); advance.setAccessible(true); childCell.setAccessible(true);
		return new Odometer(constructor.newInstance(messages,internalScope,domains,assignment),
			initialize,advance,childCell);
	}

	private static Snapshot snapshot(BoundaryMessage message) throws Exception {
		return new Snapshot(((double[])VALUES.get(message)).clone(),((double[])LOW.get(message)).clone(),
			((double[])LOWER.get(message)).clone(),((int[])CHOICES.get(message)).clone());
	}
	private static Field field(String name) {
		try { Field field=BoundaryMessage.class.getDeclaredField(name); field.setAccessible(true); return field; }
		catch(ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
	}
	private static List<Variable> variables(int... domains) {
		List<Variable> result=new ArrayList<>();
		for(int index=0; index<domains.length; index++) result.add(new Variable("v"+index,domains[index]));
		return List.copyOf(result);
	}
	private static double[] pattern(int cells,int salt) {
		double[] result=new double[cells];
		for(int cell=0; cell<cells; cell++) result[cell]=0.125d*(1+Math.floorMod(cell*17+salt,11));
		return result;
	}
	private static double[] randomTable(int cells,Random random,int factor) {
		double[] result=new double[cells];
		for(int cell=0; cell<cells; cell++) {
			int selector=random.nextInt(19);
			result[cell]=selector==0 ? Double.POSITIVE_INFINITY
				: selector==1 ? 1.0e16 : selector==2 ? Math.nextUp(0.125d)
				: (1+random.nextInt(17))*0.125d + factor*Double.MIN_VALUE;
		}
		return result;
	}
	private static int cells(List<Variable> scope) { return scope.stream().mapToInt(Variable::domainSize).reduce(1,Math::multiplyExact); }
	private static int[] indexes(List<Variable> scope,List<Variable> variables) { return scope.stream().mapToInt(variables::indexOf).toArray(); }
	private static int[] range(int size) { int[] result=new int[size]; for(int i=0;i<size;i++)result[i]=i; return result; }
	private static int product(int[] scope,int[] domains) { int result=1; for(int variable:scope)result*=domains[variable]; return result; }
	private static void decode(int cell,int[] scope,int[] domains,int[] assignment) {
		for(int position=scope.length-1; position>=0; position--) { int variable=scope[position]; assignment[variable]=cell%domains[variable]; cell/=domains[variable]; }
	}
	private static int encode(int[] assignment,List<Variable> scope,List<Variable> variables,int[] domains) { return encode(assignment,indexes(scope,variables),domains); }
	private static int encode(int[] assignment,int[] scope,int[] domains) { int cell=0; for(int variable:scope)cell=cell*domains[variable]+assignment[variable]; return cell; }
	private static double addLower(double left,double right) {
		if(left==Double.POSITIVE_INFINITY||right==Double.POSITIVE_INFINITY)return Double.POSITIVE_INFINITY;
		double sum=left+right; if(sum==Double.POSITIVE_INFINITY)return Double.MAX_VALUE;
		double virtual=sum-left; double error=(left-(sum-virtual))+(right-virtual);
		return error<0d?Math.nextDown(sum):sum;
	}
	private static void assertRaw(double[] expected,double[] actual) {
		Assert.assertEquals(expected.length,actual.length);
		for(int i=0;i<expected.length;i++)Assert.assertEquals("cell="+i,
			Long.toHexString(Double.doubleToRawLongBits(expected[i])),Long.toHexString(Double.doubleToRawLongBits(actual[i])));
	}
	private record Odometer(Object instance,Method initialize,Method advance,Method childCell) {}
	private record Snapshot(double[] high,double[] low,double[] lower,int[] choices) {}
	private record Cost(double high,double low) implements Comparable<Cost> {
		static Cost zero(){return new Cost(0d,0d);} static Cost infinity(){return new Cost(Double.POSITIVE_INFINITY,0d);}
		Cost plus(double thatHigh,double thatLow){
			if(high==Double.POSITIVE_INFINITY||thatHigh==Double.POSITIVE_INFINITY)return infinity();
			double sum=high+thatHigh;if(!Double.isFinite(sum))throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual=sum-high;double error=(high-(sum-virtual))+(thatHigh-virtual)+low+thatLow;
			if(!Double.isFinite(error))throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalized=sum+error;if(!Double.isFinite(normalized))throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			return new Cost(normalized,error-(normalized-sum));
		}
		double rounded(){return high==Double.POSITIVE_INFINITY?high:high+low;}
		@Override public int compareTo(Cost that){return Double.compare(rounded(),that.rounded());}
	}
}

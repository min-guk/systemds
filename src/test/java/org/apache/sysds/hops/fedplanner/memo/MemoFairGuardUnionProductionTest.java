/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.junit.Assert;
import org.junit.Test;

public class MemoFairGuardUnionProductionTest {
	@Test public void sampledThreeGuardUnionsEqualDirectAssignmentCount() {
		List<Map<GuardDecision,Boolean>> cubes=new ArrayList<>();
		for(int guard=0;guard<3;guard++) for(boolean value:new boolean[] {false,true})
			cubes.add(Map.of(guard(guard),value));
		for(int a=0;a<3;a++) for(int b=a+1;b<3;b++)
			for(boolean av:new boolean[] {false,true}) for(boolean bv:new boolean[] {false,true})
				cubes.add(Map.of(guard(a),av,guard(b),bv));
		for(int mask=1;mask<(1<<cubes.size());mask+=37) {
			List<Map<GuardDecision,Boolean>> selected=new ArrayList<>();
			List<MemoProgramModel.Activation> events=new ArrayList<>();
			for(int i=0;i<cubes.size();i++) if((mask&(1<<i))!=0) {
				selected.add(cubes.get(i));
				events.add(new MemoProgramModel.Activation(cubes.get(i),Math.scalb(1,-cubes.get(i).size())));
			}
			double expected=satisfyingAssignments(selected,3)/8d;
			double actual=MemoProgramModel.activationUnion(events,Double.POSITIVE_INFINITY,0.5);
			Assert.assertEquals("mask="+mask,Double.doubleToRawLongBits(expected),
				Double.doubleToRawLongBits(actual));
		}
	}

	@Test public void allSixteenBitsAndLegacyFallbacksRemainExact() {
		for(int mode=0;mode<3;mode++) {
			Map<GuardDecision,Boolean> cube=new LinkedHashMap<>();
			for(int i=0;i<16;i++) cube.put(guard(i),mode==0 || mode==2 && (i&1)==0);
			Assert.assertEquals(Double.doubleToRawLongBits(Math.scalb(1,-16)),Double.doubleToRawLongBits(
				MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(cube,Math.scalb(1,-16))),
					Double.POSITIVE_INFINITY,0.5)));
		}
		Map<GuardDecision,Boolean> wide=new LinkedHashMap<>();
		for(int i=0;i<17;i++) wide.put(guard(i),true);
		Assert.assertEquals(Double.doubleToRawLongBits(Math.scalb(1,-17)),Double.doubleToRawLongBits(
			MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(wide,Math.scalb(1,-17))),
				Double.POSITIVE_INFINITY,0.5)));
		Assert.assertEquals(Double.doubleToRawLongBits(0.25),Double.doubleToRawLongBits(
			MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(
				Map.of(guard(0),true),0.25)),Double.POSITIVE_INFINITY,0.25)));
	}

	@Test public void canonicalAliasAndFrameControlsUseExactDecisionEquality() {
		GuardDecision a=new GuardDecision(3,List.of(1),17,List.of(9));
		GuardDecision alias=new GuardDecision(8,List.of(2),17,List.of(9));
		GuardDecision otherValueFrame=new GuardDecision(3,List.of(1),17,List.of(10));
		Assert.assertEquals(Double.doubleToRawLongBits(1),Double.doubleToRawLongBits(
			MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(Map.of(a,true),0.5),
				new MemoProgramModel.Activation(Map.of(alias,false),0.5)),Double.POSITIVE_INFINITY,0.5)));
		Assert.assertEquals(Double.doubleToRawLongBits(0.75),Double.doubleToRawLongBits(
			MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(Map.of(a,true),0.5),
				new MemoProgramModel.Activation(Map.of(otherValueFrame,true),0.5)),
				Double.POSITIVE_INFINITY,0.5)));
	}

	private static int satisfyingAssignments(List<Map<GuardDecision,Boolean>> cubes,int guardCount) {
		int count=0;
		for(int assignment=0;assignment<(1<<guardCount);assignment++) {
			boolean matched=false;
			for(var cube:cubes) {
				boolean cubeMatched=true;
				for(var entry:cube.entrySet())
					if(((assignment>>entry.getKey().guardId())&1)!=(entry.getValue()?1:0)) {
						cubeMatched=false; break;
					}
				if(cubeMatched) { matched=true; break; }
			}
			if(matched) count++;
		}
		return count;
	}
	private static GuardDecision guard(int id) { return new GuardDecision(id,List.of()); }
}

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

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.junit.Assert;
import org.junit.Test;

public class MemoFairGuardTerminalTest {
	@Test public void halfProbabilityPreservesNormalSubnormalAndUnderflowBits() throws Exception {
		Method probability=MemoProgramModel.class.getDeclaredMethod("guardCubeProbability",Map.class,double.class);
		probability.setAccessible(true);
		for(int count:new int[] {0,1,2,16,17,1022,1023,1073,1074,1075,2000}) {
			Map<GuardDecision,Boolean> cube=new LinkedHashMap<>();
			for(int i=0;i<count;i++) cube.put(new GuardDecision(i,List.of()),(i&1)==0);
			for(double branch:new double[] {0.5,0.3,0.75}) {
				double expected=1;
				for(boolean value:cube.values()) expected*=value?branch:1-branch;
				double actual=(double)probability.invoke(null,cube,branch);
				Assert.assertEquals(count+" @ "+branch,Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(actual));
			}
		}
	}

	@Test public void emptyCubeCoversUnionAndSingletonWeightsRemainExact() {
		for(double branch:new double[] {0.5,0.3,0.75}) {
			var empty=new MemoProgramModel.Activation(Map.of(),3d);
			var guarded=new MemoProgramModel.Activation(Map.of(new GuardDecision(1,List.of()),true),3d*branch);
			Assert.assertEquals(Double.doubleToRawLongBits(3d),Double.doubleToRawLongBits(
				MemoProgramModel.activationUnion(List.of(empty,guarded),Double.POSITIVE_INFINITY,branch)));
		}
		for(int count=0;count<=17;count++) {
			Map<GuardDecision,Boolean> cube=new LinkedHashMap<>();
			for(int i=0;i<count;i++) cube.put(new GuardDecision(i,List.of()),(i&1)==0);
			double weight=Math.scalb(3d,-count);
			Assert.assertEquals(Double.doubleToRawLongBits(weight),Double.doubleToRawLongBits(
				MemoProgramModel.activationUnion(List.of(new MemoProgramModel.Activation(cube,weight)),
					Double.POSITIVE_INFINITY,0.5)));
		}
	}
}

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
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class MemoIndexedFrameEqualityTest {
	@Test public void indexedComparisonMatchesListEquality() throws Exception {
		Method compare=MemoShapes.class.getDeclaredMethod("framesEqual",List.class,List.class);
		compare.setAccessible(true);
		List<List<Integer>> frames=List.of(List.of(),List.of(0),List.of(-1),List.of(1000),
			List.of(Integer.MIN_VALUE,Integer.MAX_VALUE),List.of(1,2),List.of(2,1),List.of(1,2,3),
			List.of(1000,2000),List.of(1000,2001));
		for(List<Integer> left:frames) for(List<Integer> right:frames) {
			Assert.assertEquals(left+" vs "+right,left.equals(right),compare.invoke(null,left,right));
			List<Integer> copy=new ArrayList<>();
			for(Integer value:right) copy.add(Integer.valueOf(value.toString()));
			List<Integer> frozen=List.copyOf(copy);
			Assert.assertEquals(left+" vs copied "+right,left.equals(frozen),compare.invoke(null,left,frozen));
		}
	}
	@Test public void carriedLookupPreservesFirstMatchAndAbsentSlots() throws Exception {
		String script="A=matrix(1,2,3);B=A;for(i in 1:3){A=A+1;B=B+A;}print(sum(A)+sum(B));";
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		Method lookup=MemoShapes.class.getDeclaredMethod("carriedValue",int.class,int.class);
		lookup.setAccessible(true);
		Assert.assertFalse(facts.loops().isEmpty());
		for(var loop:facts.loops()) {
			Assert.assertTrue(loop.carriedValues().size()>=2);
			int maximum=loop.carriedValues().stream().mapToInt(MemoProgramFacts.CarriedValue::slot).max().orElse(0);
			for(int slot=-2;slot<=maximum+1;slot++) {
				int requested=slot;
				Object expected=loop.carriedValues().stream().filter(v -> v.slot()==requested).findFirst().orElse(null);
				Assert.assertSame(expected,lookup.invoke(shapes,loop.id(),slot));
			}
		}
		for(int loop:new int[] {-1,facts.loops().size()}) {
			Class<?> expected=null;
			try { facts.loop(loop); } catch(RuntimeException error) { expected=error.getClass(); }
			Assert.assertNotNull(expected);
			try { lookup.invoke(shapes,loop,0); Assert.fail("invalid loop accepted"); }
			catch(InvocationTargetException error) { Assert.assertEquals(expected,error.getCause().getClass()); }
		}
	}

}

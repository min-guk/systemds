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
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoProgramFactsIndexTest {
	@Test
	public void guardPhaseQueriesPreserveRegistrationOrderFirstFactAndUnknown() throws Exception {
		MemoProgramFacts facts=analyze("X=0;for(i in 1:5){if(i<3){X=3;}else{X=4;}"
			+ "if(i==5){X=X+1;}}print(X);");
		facts.requireComplete();
		Assert.assertFalse(facts.guardPhases().isEmpty());

		for(MemoProgramFacts.LoopRecord loop : facts.loops()) {
			List<MemoProgramFacts.GuardPhase> expected=facts.guardPhases().stream()
				.filter(fact -> fact.loopId()==loop.id()).toList();
			Assert.assertEquals("per-loop lookup must preserve global registration order",expected,
				facts.guardPhases(loop.id()));
			Assert.assertSame("the frozen per-loop list is reused",facts.guardPhases(loop.id()),
				facts.guardPhases(loop.id()));
		}

		Map<Long,MemoProgramFacts.GuardPhase> first=new LinkedHashMap<>();
		for(MemoProgramFacts.GuardPhase fact : facts.guardPhases())
			first.putIfAbsent(pairKey(fact.loopId(),fact.guardId()),fact);
		for(MemoProgramFacts.GuardPhase fact : first.values())
			for(MemoProgramFacts.IterationPhase phase : MemoProgramFacts.IterationPhase.values())
				Assert.assertEquals("guardValue must retain findFirst semantics",fact.value(phase),
					facts.guardValue(fact.loopId(),fact.guardId(),phase));
		Assert.assertNull(facts.guardValue(facts.loops().get(0).id(),Integer.MAX_VALUE,
			MemoProgramFacts.IterationPhase.FIRST));
		Assert.assertTrue(facts.guardPhases(Integer.MAX_VALUE).isEmpty());
	}

	@Test
	public void canonicalGuardQueriesPreserveAliasInversionAndExactFrames() throws Exception {
		MemoProgramFacts facts=analyze("f=function(boolean q) return(matrix[double] Y){"
			+ "if(!q){Y=matrix(1,1,1);}else{Y=matrix(2,1,1);}}"
			+ "p=as.scalar(rand(rows=1,cols=1))>0.5;r=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "if(p){A=f(p);}if(p){B=f(p);}if(r){C=f(r);}");
		facts.requireComplete();
		int functionGuard=facts.guards().stream().filter(guard ->
			facts.region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow().id();
		Assert.assertEquals(3,facts.calls().size());

		var first=facts.guardDecision(functionGuard,List.of(facts.calls().get(0).id()));
		var firstAgain=facts.guardDecision(functionGuard,List.of(facts.calls().get(0).id()));
		var sameActual=facts.guardDecision(functionGuard,List.of(facts.calls().get(1).id()));
		var differentActual=facts.guardDecision(functionGuard,List.of(facts.calls().get(2).id()));
		Assert.assertEquals(first,firstAgain);
		Assert.assertSame("an exact syntactic guard/frame query reuses its canonical decision",first,firstAgain);
		Assert.assertEquals("separate frames reading the same actual retain one canonical value",first,sameActual);
		Assert.assertNotSame("canonical equality must not erase the queried syntactic frame",first,sameActual);
		Assert.assertEquals(List.of(facts.calls().get(0).id()),first.callFrame());
		Assert.assertEquals(List.of(facts.calls().get(1).id()),sameActual.callFrame());
		Assert.assertNotEquals("a different actual remains a distinct value",first,differentActual);
		Assert.assertFalse("the syntactic !q true arm maps to canonical q=false",
			facts.canonicalGuardValue(functionGuard,List.of(facts.calls().get(0).id()),true));
		Assert.assertTrue(facts.branchGuardValue(functionGuard,List.of(facts.calls().get(0).id()),false));
	}

	@Test
	public void occurrenceContextRequeryPreservesEveryFrozenContext() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X) return(matrix[double] Y){Y=X;"
			+ "for(j in 1:3){if(j==3){Y=abs(Y);}else{Y=Y+1;}}}"
			+ "A=matrix(1,2,2);for(i in 1:3){A=f(A);}print(sum(A));");
		facts.requireComplete();
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences()) {
			List<MemoProgramFacts.OccurrenceContext> first=facts.occurrenceContexts(occurrence.id());
			List<MemoProgramFacts.OccurrenceContext> second=facts.occurrenceContexts(occurrence.id());
			Assert.assertEquals(first,second);
			Assert.assertSame("the immutable occurrence context list is reused",first,second);
		}
	}

	private static long pairKey(int left,int right) {
		return ((long)left << 32) | (right & 0xffffffffL);
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

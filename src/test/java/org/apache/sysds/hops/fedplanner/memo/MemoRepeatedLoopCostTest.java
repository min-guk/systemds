/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Cost semantics for a loop-invariant value demanded by a repeated, guarded loop body. */
public class MemoRepeatedLoopCostTest {
	@Test
	public void parsedOppositeArmsShareOneLoopInvariantMaterialization() throws Exception {
		MemoProgramFacts facts = facts("X=matrix(1,rows=8,cols=4);A=matrix(2,rows=8,cols=4);"
			+ "for(i in 1:10){p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){X=X+A;}else{X=X-A;}}print(sum(X));");
		MemoProgramFacts.Guard guard=loopGuard(facts);
		Assert.assertEquals(10,facts.executionFrequency(facts.loop(0).regionId()).multiplier(),0);
		var decision=new MemoBindingResolver.GuardDecision(guard.id(),List.of());
		var thenDemand=new MemoProgramModel.Activation(Map.of(decision,true),0.5);
		var elseDemand=new MemoProgramModel.Activation(Map.of(decision,false),0.5);

		Assert.assertEquals("opposite arms are the exact union of one retained materialization lifetime",
			1,MemoProgramModel.activationUnion(List.of(thenDemand,elseDemand),
				Double.POSITIVE_INFINITY),0);
		Assert.assertEquals("duplicate demands in one arm reuse the same retained copy",
			0.5,MemoProgramModel.activationUnion(List.of(thenDemand,thenDemand),
				Double.POSITIVE_INFINITY),0);
	}

	@Test
	public void parsedSingleArmDocumentsFrozenGuardEstimatorRatherThanLegacyEverTakenProbability()
		throws Exception {
		MemoProgramFacts facts = facts("X=matrix(1,rows=8,cols=4);A=matrix(2,rows=8,cols=4);"
			+ "for(i in 1:10){p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){X=X+A;}else{X=abs(X);}}print(sum(X));");
		MemoProgramFacts.Guard guard=loopGuard(facts);
		Assert.assertEquals(10,facts.executionFrequency(facts.loop(0).regionId()).multiplier(),0);
		var decision=new MemoBindingResolver.GuardDecision(guard.id(),List.of());
		var halfDemand=new MemoProgramModel.Activation(Map.of(decision,true),0.5);
		var quarterDemand=new MemoProgramModel.Activation(Map.of(decision,true),0.25);

		Assert.assertEquals("the frozen estimator does not turn ten evaluations into an ever-taken probability",
			0.5,MemoProgramModel.activationUnion(List.of(halfDemand),Double.POSITIVE_INFINITY,0.5),0);
		Assert.assertEquals("the configured guard probability scales the one retained activation class",
			0.25,MemoProgramModel.activationUnion(List.of(quarterDemand),Double.POSITIVE_INFINITY,0.25),0);
		Assert.assertNotEquals("this deliberately differs from legacy's conservative one-copy activation",
			1,MemoProgramModel.activationUnion(List.of(halfDemand),Double.POSITIVE_INFINITY,0.5),0);
	}

	private static MemoProgramFacts facts(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		facts.requireComplete();
		return facts;
	}

	private static MemoProgramFacts.Guard loopGuard(MemoProgramFacts facts) {
		Assert.assertEquals(1,facts.loops().size());
		List<MemoProgramFacts.Guard> nested=facts.guards().stream().filter(guard -> {
			MemoProgramFacts.Region region=facts.region(guard.parentRegionId());
			while(region.id()>=0) {
				if(region.loopId()==facts.loop(0).id()) return true;
				if(region.parentId()<0) return false;
				region=facts.region(region.parentId());
			}
			return false;
		}).toList();
		List<MemoProgramFacts.Guard> bodyGuards=nested.stream()
			.filter(guard -> guard.id()!=facts.loop(0).guardId())
			.toList();
		Assert.assertEquals("the parsed body must expose one shared static if-guard identity: "+nested,
			1,bodyGuards.size());
		return bodyGuards.get(0);
	}
}

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.Context;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

public class MemoContextAnalysisReuseTest {
	@Test public void callerFramesAndCanonicalGuardAliasesMatchUncachedAnalysis() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,2,2);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);B=f(p);}",false);
		MemoProgramModel cached=model(fixture,true),reference=model(fixture,false);
		Method context=contextMethod();
		int[] selected=new int[((int[])field(MemoProgramModel.class,"sizes").get(cached)).length];
		Arrays.fill(selected,-1);
		for(var region:fixture.facts.regions()) for(List<Integer> frame:fixture.facts.callFramesForRegion(region.id())) {
			Context expected=(Context)context.invoke(reference,selected,region.id(),frame);
			Context actual=(Context)context.invoke(cached,selected,region.id(),frame);
			assertAnalysisSame(expected,actual);
			Context repeated=(Context)context.invoke(cached,selected,region.id(),new ArrayList<>(frame));
			assertAnalysisSame(actual,repeated);
		}
		Assert.assertTrue(cached.contextAnalysisReuseStatistics().hits()>0);
	}

	@Test public void eachContextKeepsItsLiveSelectionAndHeadLookup() throws Exception {
		Fixture fixture=fixture("A=P;for(i in 1:2){A=abs(A);}B=abs(A);print(sum(B));",true);
		MemoProgramModel model=model(fixture,true);
		int[] sizes=(int[])field(MemoProgramModel.class,"sizes").get(model);
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		Method context=contextMethod();
		Context first=(Context)context.invoke(model,selected,0,List.of());
		int owner=-1;
		for(int candidate=0;candidate<fixture.facts.compiledOwnerCount();candidate++)
			if(sizes[candidate]>1) { owner=candidate; break; }
		Assert.assertTrue(owner>=0);
		selected[owner]=0;
		Assert.assertEquals(0,first.evaluation().selections().selectedRule(owner));
		Assert.assertEquals(model.memo().rule(owner,0).output(),
			first.evaluation().selections().selectedLayout(owner));
		if(sizes[owner]>1) {
			selected[owner]=1;
			Assert.assertEquals(1,first.evaluation().selections().selectedRule(owner));
			Assert.assertEquals(model.memo().rule(owner,1).output(),
				first.evaluation().selections().selectedLayout(owner));
		}
		MemoProgramModel.Head head=model.heads().stream().filter(value -> sizes[value.variable()]>1)
			.findFirst().orElseThrow();
		selected[head.variable()]=0;
		MemoPartition zero=first.evaluation().selections().selectedHeadLayout(head.slot().loopId(),head.slot().slot());
		selected[head.variable()]=1;
		MemoPartition one=first.evaluation().selections().selectedHeadLayout(head.slot().loopId(),head.slot().slot());
		Assert.assertNotEquals("head layout must follow the live selected array",zero,one);
	}

	private static void assertAnalysisSame(Context expected,Context actual) {
		Assert.assertEquals(expected.reachable(),actual.reachable());
		Assert.assertEquals(new ArrayList<>(expected.evaluation().guards().entrySet()),
			new ArrayList<>(actual.evaluation().guards().entrySet()));
		Assert.assertEquals(expected.evaluation().callFrame(),actual.evaluation().callFrame());
		Assert.assertEquals(expected.evaluation().maxDepth(),actual.evaluation().maxDepth());
	}
	private static MemoProgramModel model(Fixture fixture,boolean reuse) {
		return new MemoProgramModel(fixture.facts,fixture.sources,fixture.rules,
			MemoProgramModel.Profile.defaults(),true,4096,reuse);
	}
	private static Method contextMethod() throws Exception {
		Method method=MemoProgramModel.class.getDeclaredMethod("context",int[].class,int.class,List.class);
		method.setAccessible(true); return method;
	}
	private static Field field(Class<?> type,String name) throws Exception {
		Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static Fixture fixture(String body,boolean federated) throws Exception {
		String script=(federated ? "P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"
			+"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));" : "")+body;
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=federated ? MemoSources.acquire(facts,null,MemoContextAnalysisReuseTest::metadata)
			: MemoSources.acquire(facts,null);
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}
	private static FederatedSourceMetadata metadata(DataOp data) {
		return new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),data.getName()+"1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),data.getName()+"2"))));
	}
	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result rules) { }
}

/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

public class MemoMovementCostSignatureTest {
	@Test
	public void metadataIsDisabledWithMovementReuse() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=P;}else{A=matrix(2,rows=4,cols=2);}B=abs(A);print(sum(B));");
		MemoProgramModel cached=model(fixture,true),reference=model(fixture,false);
		Assert.assertTrue("the enabled fixture must publish at least one proven movement signature",
			terms(cached).stream().anyMatch(MemoMovementCostSignatureTest::annotated));
		Assert.assertTrue("the reference path must retain full ordinal identity",
			terms(reference).stream().noneMatch(MemoMovementCostSignatureTest::annotated));
	}

	@Test
	public void oneWorkerHeadKeepsLocalAndRepAsDistinctStates() throws Exception {
		Fixture fixture=oneWorkerFixture("A=P;for(i in 1:2){A=abs(A);}B=abs(A);print(sum(B));");
		MemoProgramModel model=model(fixture,true);
		Assert.assertFalse(model.heads().isEmpty());
		int head=model.heads().get(0).variable();
		int[] sizes=sizes(model);
		Assert.assertEquals("W1 head domain is LOCAL/REP",2,sizes[head]);
		boolean observed=false;
		for(MemoOptimizer.CostTerm term:terms(model)) {
			int position=position(term.owners(),head);
			int[][] classes=classes(term);
			if(position<0 || classes==null) continue;
			observed=true;
			Assert.assertNotEquals("W1 ordinal 1 is REP and must not alias LOCAL",
				classes[position][0],classes[position][1]);
		}
		Assert.assertTrue("fixture must expose a cached movement factor reading the loop head",observed);
	}

	@Test
	public void multiReturnSourceSlotsProduceDifferentCostClasses() throws Exception {
		Fixture original=fixture("X=matrix(1,rows=4,cols=2);[Q,R]=qr(X);"
			+"A=abs(Q);B=abs(R);print(sum(A)+sum(B));");
		int owner=original.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof FunctionOp function
				&& function.getFunctionName().equalsIgnoreCase("qr"))
			.findFirst().orElseThrow().compiledOwnerId();
		RuleMemo originalMemo=original.universe().memo();
		RuleMemo.Rule base=originalMemo.rules(owner).stream()
			.filter(rule -> rule.outputs().size()==2).findFirst().orElseThrow();
		List<List<RuleMemo.Rule>> domains=new ArrayList<>();
		boolean[] complete=new boolean[originalMemo.ownerCount()];
		for(int current=0;current<originalMemo.ownerCount();current++) {
			domains.add(current==owner ? List.of(
				new RuleMemo.Rule(base.exec(),base.inputs(),List.of(MemoPartition.LOCAL,MemoPartition.LOCAL),"probe/ll"),
				new RuleMemo.Rule(base.exec(),base.inputs(),List.of(MemoPartition.ROW,MemoPartition.LOCAL),"probe/rl"),
				new RuleMemo.Rule(base.exec(),base.inputs(),List.of(MemoPartition.LOCAL,MemoPartition.ROW),"probe/lr"))
				: originalMemo.rules(current));
			complete[current]=originalMemo.complete(current);
		}
		MemoRuleBuilder.Result forced=new MemoRuleBuilder.Result(new RuleMemo(domains,complete),
			original.universe().inputRoles(),original.universe().diagnostics(),original.universe().shapes(),
			original.universe().privacy(),original.universe().requests());
		MemoProgramModel model=new MemoProgramModel(original.facts(),original.sources(),forced,
			MemoProgramModel.Profile.defaults(),true);
		boolean slot0=false,slot1=false;
		for(MemoOptimizer.CostTerm term:terms(model)) {
			int position=position(term.owners(),owner); int[][] classes=classes(term);
			if(position<0 || classes==null || classes[position].length!=3) continue;
			int[] mapping=classes[position];
			slot0 |= mapping[0]==mapping[2] && mapping[0]!=mapping[1];
			slot1 |= mapping[0]==mapping[1] && mapping[0]!=mapping[2];
		}
		Assert.assertTrue("slot 0 must observe LL=LR distinct from RL",slot0);
		Assert.assertTrue("slot 1 must observe LL=RL distinct from LR",slot1);
	}

	private static MemoProgramModel model(Fixture fixture,boolean reuse) {
		return new MemoProgramModel(fixture.facts(),fixture.sources(),fixture.universe(),
			MemoProgramModel.Profile.defaults(),reuse);
	}

	@SuppressWarnings("unchecked") private static List<MemoOptimizer.CostTerm> terms(MemoProgramModel model)
		throws Exception {
		return (List<MemoOptimizer.CostTerm>)field(MemoProgramModel.class,"costs").get(model);
	}
	private static int[] sizes(MemoProgramModel model) throws Exception {
		return (int[])field(MemoProgramModel.class,"sizes").get(model);
	}
	private static boolean annotated(MemoOptimizer.CostTerm term) {
		try { return classes(term)!=null; }
		catch(Exception failure) { throw new AssertionError(failure); }
	}
	private static int[][] classes(MemoOptimizer.CostTerm term) throws Exception {
		return (int[][])field(MemoOptimizer.CostTerm.class,"costClasses").get(term);
	}
	private static int position(int[] values,int target) {
		for(int index=0;index<values.length;index++) if(values[index]==target) return index;
		return -1;
	}
	private static Fixture fixture(String body) throws Exception {
		Method method=MemoMovementReuseTest.class.getDeclaredMethod("fixture",String.class);
		method.setAccessible(true); Object value=method.invoke(null,body);
		return new Fixture((MemoProgramFacts)access(value,"facts"),(MemoSources)access(value,"sources"),
			(MemoRuleBuilder.Result)access(value,"universe"));
	}
	private static Fixture oneWorkerFixture(String body) throws Exception {
		Fixture original=fixture(body);
		MemoProgramFacts facts=original.facts();
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(Privacy.PUBLIC,
			List.of(new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")))));
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}
	private static Object access(Object value,String name) throws Exception {
		Method method=value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
	}
	private static Field field(Class<?> type,String name) throws Exception {
		Field value=type.getDeclaredField(name); value.setAccessible(true); return value;
	}
	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class MemoReachableContextPrefixCleanTest {
	@Test public void lateFailureDoesNotPublishAndRetryKeepsOrder() throws Exception {
		MemoProgramFacts facts=facts();
		for(var occurrence:facts.occurrences()) for(var occurrenceContext:facts.occurrenceContexts(occurrence.id())) {
			MemoReachableContexts reachable=reachable(facts);
			var prefix=new ArrayList<MemoBindingResolver.EvaluationContext>();
			try {
				reachable.visitRuleContext(occurrence,occurrenceContext,context -> {
					prefix.add(context);
					if(prefix.size()==2) throw new IllegalStateException("late failure");
				});
			}
			catch(IllegalStateException error) { Assert.assertEquals("late failure",error.getMessage()); }
			if(prefix.size()<2) continue;
			Assert.assertEquals(0,cacheSize(reachable));
			var retry=new ArrayList<MemoBindingResolver.EvaluationContext>();
			var result=reachable.visitRuleContext(occurrence,occurrenceContext,retry::add);
			Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
			Assert.assertEquals(prefix,retry.subList(0,prefix.size()));
			Assert.assertEquals(1,cacheSize(reachable));
			return;
		}
		Assert.fail("fixture must contain a prefix with at least two contexts");
	}

	@Test public void partialCaptureOverflowStreamsOnceWithoutPublishing() throws Exception {
		MemoProgramFacts facts=facts();
		for(var occurrence:facts.occurrences()) for(var occurrenceContext:facts.occurrenceContexts(occurrence.id())) {
			MemoReachableContexts reachable=reachable(facts);
			setInt(reachable,"rulePrefixContextCount",4095);
			var seen=new ArrayList<MemoBindingResolver.EvaluationContext>();
			var result=reachable.visitRuleContext(occurrence,occurrenceContext,seen::add);
			if(seen.size()<2) continue;
			Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
			Assert.assertEquals(0,cacheSize(reachable));
			var retry=new ArrayList<MemoBindingResolver.EvaluationContext>();
			reachable.visitRuleContext(occurrence,occurrenceContext,retry::add);
			Assert.assertEquals(seen,retry);
			Assert.assertEquals(0,cacheSize(reachable));
			return;
		}
		Assert.fail("fixture must contain a prefix with at least two contexts");
	}

	@Test public void sameKeyReentryPublishesOneEntry() throws Exception {
		MemoProgramFacts facts=facts();
		for(var occurrence:facts.occurrences()) for(var occurrenceContext:facts.occurrenceContexts(occurrence.id())) {
			MemoReachableContexts reachable=reachable(facts); boolean[] nested={false}; int[] seen={0};
			reachable.visitRuleContext(occurrence,occurrenceContext,context -> {
				seen[0]++;
				if(!nested[0]) {
					nested[0]=true;
					reachable.visitRuleContext(occurrence,occurrenceContext,ignored -> { });
				}
			});
			if(seen[0]==0) continue;
			Assert.assertEquals(1,cacheSize(reachable));
			return;
		}
		Assert.fail("fixture must contain a reachable prefix");
	}

	@Test public void unresolvedActivationDoesNotPublish() throws Exception {
		MemoProgramFacts facts=facts();
		int missing=facts.bindings().stream()
			.filter(binding -> binding.kind()==MemoProgramFacts.BindingKind.MISSING
				|| binding.kind()==MemoProgramFacts.BindingKind.UNBOUND)
			.mapToInt(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		var original=facts.occurrences().get(0);
		var occurrence=new MemoProgramFacts.Occurrence(original.id(),original.compiledOwnerId(),original.hop(),
			original.statementBlockId(),original.regionId(),original.context(),missing);
		MemoReachableContexts reachable=reachable(facts);
		var result=reachable.visitRuleContext(occurrence,facts.occurrenceContexts(original.id()).get(0),
			ignored -> Assert.fail());
		Assert.assertEquals(MemoBindingResolver.VisitStatus.UNRESOLVED,result.status());
		Assert.assertEquals(0,cacheSize(reachable));
	}

	private static MemoProgramFacts facts() throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(parsed()); facts.requireComplete(); return facts;
	}
	private static MemoReachableContexts reachable(MemoProgramFacts facts) {
		return new MemoReachableContexts(facts,new MemoBindingResolver(facts));
	}
	private static int cacheSize(MemoReachableContexts reachable) throws Exception {
		Field field=MemoReachableContexts.class.getDeclaredField("rulePrefixes"); field.setAccessible(true);
		return ((Map<?,?>)field.get(reachable)).size();
	}
	private static void setInt(Object target,String name,int value) throws Exception {
		Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.setInt(target,value);
	}
	private static DMLProgram parsed() throws Exception {
		Method method=MemoFederatedModelTest.class.getDeclaredMethod(
			"parsedRewrittenFixture",String.class,long.class,long.class);
		method.setAccessible(true); return (DMLProgram)method.invoke(null,"logreg",192L,8L);
	}
}

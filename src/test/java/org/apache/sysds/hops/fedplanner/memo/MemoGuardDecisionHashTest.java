/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.List;
import java.util.Objects;

import org.junit.Assert;
import org.junit.Test;

public class MemoGuardDecisionHashTest {
	@Test
	public void manualHashMustRemainExactlyObjectsHashForEveryDecisionForm() {
		List<List<Integer>> frames=List.of(List.of(),List.of(0),
			List.of(Integer.MIN_VALUE,-1,0,31,Integer.MAX_VALUE));
		for(int id:new int[] {Integer.MIN_VALUE,-1,0,1,31,Integer.MAX_VALUE})
			for(List<Integer> frame:frames) {
				var syntactic=new MemoBindingResolver.GuardDecision(id,frame);
				Assert.assertEquals(Objects.hash(id,frame),syntactic.hashCode());
				int binding=id<0 ? ~id : id;
				var canonical=new MemoBindingResolver.GuardDecision(id,List.of(99),binding,frame);
				Assert.assertEquals(Objects.hash(binding,frame),canonical.hashCode());
			}
	}

	@Test
	public void canonicalAliasesKeepEqualityAndHashIndependentOfSyntacticIdentity() {
		var caller=new MemoBindingResolver.GuardDecision(7,List.of(1),31,List.of(1,2));
		var formal=new MemoBindingResolver.GuardDecision(19,List.of(8,9),31,List.of(1,2));
		Assert.assertEquals(caller,formal);
		Assert.assertEquals(caller.hashCode(),formal.hashCode());
		Assert.assertNotEquals(caller,new MemoBindingResolver.GuardDecision(19,List.of(8,9),31,List.of(2,1)));
	}
}

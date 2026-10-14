/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.junit.Assert;
import org.junit.Test;

public class MemoShapeAliasJumpParityTest {
	@Test
	public void malformedAndCyclicChainsRetainTheirStartingBinding() throws Exception {
		List<MemoProgramFacts.BindingNode> nodes=List.of(
			node(0,BindingKind.DEFINITION,1),
			node(1,BindingKind.CALL_ARG,0),
			node(2,BindingKind.CALL_ARG),
			node(3,BindingKind.DEFINITION,99),
			node(4,BindingKind.UNBOUND));
		Assert.assertArrayEquals(new int[] {0,1,2,3,4},representatives(nodes));
	}

	@Test
	public void aliasesStopBeforeContextSensitiveBindings() throws Exception {
		List<MemoProgramFacts.BindingNode> nodes=List.of(
			node(0,BindingKind.CALL_ARG,1),
			node(1,BindingKind.DEFINITION,2),
			node(2,BindingKind.SELECT,3,4),
			node(3,BindingKind.FORMAL),
			node(4,BindingKind.CALL_RETURN,5),
			node(5,BindingKind.LOOP_HEAD,6,7),
			node(6,BindingKind.LAST,7,8),
			node(7,BindingKind.DEFINITION),
			node(8,BindingKind.MISSING));
		Assert.assertArrayEquals(new int[] {2,2,2,3,4,5,6,7,8},representatives(nodes));
	}

	private static int[] representatives(List<MemoProgramFacts.BindingNode> nodes) throws Exception {
		Method method=MemoShapes.class.getDeclaredMethod("shapeAliasRepresentatives",List.class);
		method.setAccessible(true);
		return (int[])method.invoke(null,nodes);
	}

	private static MemoProgramFacts.BindingNode node(int id,BindingKind kind,Integer... children) {
		return new MemoProgramFacts.BindingNode(id,kind,List.of(children),-1,-1,kind+"-"+id);
	}
}

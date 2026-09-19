/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.junit.Assert;
import org.junit.Test;

/** Known-bottom matrix inputs must not be reinterpreted as coordinator-local inputs. */
public class CandidateInputBottomDomainTest {
	@Test
	public void emptyKnownPredecessorDoesNotBecomeAbsentLocal() throws Exception {
		UnaryOp predecessorHop = matrixUnary("predecessor", new LiteralOp(1L));
		UnaryOp consumer = matrixUnary("consumer", predecessorHop);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			PlacementIdentityKnownEqualityContractTest.program(consumer));
		Node predecessor = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == predecessorHop)
			.map(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow())
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertFalse("fixture predecessor must start with a real local placement",
			predecessor.legalAlternatives().isEmpty());

		Map<Hop,Node> localNodes = new IdentityHashMap<>();
		localNodes.put(predecessorHop, predecessor);
		Assert.assertEquals("a real CP/LOUT predecessor remains ABSENT_LOCAL in the candidate language",
			List.of(java.util.Collections.singletonList((FType) null)), inputDomains(consumer, localNodes));

		Node bottom = new Node(predecessor.key(), predecessor.kind(), predecessor.valueVersion(), false,
			List.of(), predecessor.exclusions(), predecessor.anchors());
		Map<Hop,Node> bottomNodes = new IdentityHashMap<>();
		bottomNodes.put(predecessorHop, bottom);
		List<List<FType>> bottomDomains = inputDomains(consumer, bottomNodes);
		Assert.assertEquals("known-bottom predecessor still owns one input position", 1, bottomDomains.size());
		Assert.assertTrue("known-bottom matrix input must have an empty candidate domain, not ABSENT_LOCAL",
			bottomDomains.get(0).isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static List<List<FType>> inputDomains(Hop consumer, Map<Hop,Node> nodes) throws Exception {
		Method method = java.util.Arrays.stream(NeutralPlacementGraphBuilder.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals("inputDomains")
				&& candidate.getParameterCount() == 6)
			.findFirst().orElseThrow();
		method.setAccessible(true);
		return (List<List<FType>>) method.invoke(null, consumer, nodes, null, List.of(), false, null);
	}

	private static UnaryOp matrixUnary(String name, Hop input) {
		return new UnaryOp(name, DataType.MATRIX, ValueType.FP64, OpOp1.EXP, input);
	}
}

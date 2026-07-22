/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;

/** Runtime-independent cost semantics shared by planner-specific models. */
public final class PlacementCostSemantics {
	private PlacementCostSemantics() {
		// utility class
	}

	public static double forwardingWeight(double networkWeight,
		List<Pair<Long,Double>> parentLoopContext, List<Pair<Long,Double>> childLoopContext) {
		return forwardingWeight(networkWeight, parentLoopContext, childLoopContext, 1.0);
	}

	public static double forwardingWeight(double networkWeight,
		List<Pair<Long,Double>> parentLoopContext, List<Pair<Long,Double>> childLoopContext,
		double consumerMultiplicity) {
		double base = networkWeight != 0.0 ? networkWeight : 1.0;
		if(parentLoopContext == null || parentLoopContext.isEmpty())
			return base * Math.max(consumerMultiplicity, 0.0);

		Map<Long,Double> childLoops = new HashMap<>();
		if(childLoopContext != null)
			for(Pair<Long,Double> loop : childLoopContext)
				childLoops.put(loop.getLeft(), loop.getRight());

		double weight = base;
		for(Pair<Long,Double> loop : parentLoopContext)
			if(!childLoops.containsKey(loop.getLeft()) && loop.getRight() > 0.0)
				weight /= loop.getRight();
		return weight * Math.max(consumerMultiplicity, 0.0);
	}

	public static boolean isMultiReturnFunctionOutput(Hop hop) {
		if(!(hop instanceof DataOp) || ((DataOp)hop).getOp() != OpOpData.FUNCTIONOUTPUT)
			return false;
		List<Hop> inputs = hop.getInput();
		if(inputs == null || inputs.isEmpty() || inputs.get(0) == null)
			return false;
		List<Hop> parents = inputs.get(0).getParent();
		if(parents == null || parents.isEmpty())
			return false;
		for(Hop parent : parents)
			if(parent instanceof FunctionOp
				&& ((FunctionOp)parent).getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				&& ((FunctionOp)parent).getOutputs() != null
				&& ((FunctionOp)parent).getOutputs().contains(hop))
				return true;
		return false;
	}
}

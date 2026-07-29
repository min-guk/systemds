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
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

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

	/**
	 * Exact runtime layout used when a known local matrix is materialized onto an existing
	 * durable worker pool. Matching geometry preserves the anchor layout; a different known
	 * geometry is broadcast to that same pool.
	 */
	public static FType exactMaterializationFType(NodeShapeFact shape, DurableAnchorKey anchor) {
		if(anchor == null || anchor.fType() == null || anchor.fType() == FType.PART
			|| anchor.fType() == FType.OTHER || shape == null || !shape.knownPositiveMatrix())
			return null;
		return outputGeometryCompatible(shape, anchor) ? anchor.fType() : FType.BROADCAST;
	}

	private static boolean outputGeometryCompatible(NodeShapeFact shape, DurableAnchorKey anchor) {
		if(anchor.partitions().isEmpty() || deriveAnchorFType(anchor.partitions()) != anchor.fType())
			return false;
		long maxRow = -1, maxCol = -1;
		for(AnchorPartition partition : anchor.partitions()) {
			if(partition.begin().size() != 2 || partition.end().size() != 2)
				return false;
			long beginRow = partition.begin().get(0), beginCol = partition.begin().get(1);
			long endRow = partition.end().get(0), endCol = partition.end().get(1);
			if(beginRow < 0 || beginCol < 0 || endRow <= beginRow || endCol <= beginCol
				|| endRow > shape.rows() || endCol > shape.cols())
				return false;
			maxRow = Math.max(maxRow, endRow);
			maxCol = Math.max(maxCol, endCol);
		}
		return shape.rows() == maxRow && shape.cols() == maxCol;
	}

	private static FType deriveAnchorFType(List<AnchorPartition> partitions) {
		if(partitions.isEmpty()) return null;
		long maxRow = partitions.stream().mapToLong(p -> p.end().get(0)).max().orElse(-1);
		long maxCol = partitions.stream().mapToLong(p -> p.end().get(1)).max().orElse(-1);
		boolean spansRows = partitions.stream().allMatch(p ->
			p.begin().get(0) == 0 && p.end().get(0) == maxRow);
		boolean spansCols = partitions.stream().allMatch(p ->
			p.begin().get(1) == 0 && p.end().get(1) == maxCol);
		if(spansRows && spansCols) return partitions.size() == 1 ? FType.FULL : FType.BROADCAST;
		if(spansCols) return FType.ROW;
		if(spansRows) return FType.COL;
		return FType.OTHER;
	}
}

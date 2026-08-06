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
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

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
	 * Occurrence-exact local operation cost shared by DP and MinST.
	 *
	 * <p>The ordinary HOP cost remains authoritative.  The only supplemental term
	 * modeled here is a runtime WDivMM kernel that the dynamic algebraic rewrite can
	 * create after loop/function dimensions become concrete.  This uses immutable
	 * compiled-input and shape facts rather than mutating HOP dimensions or matching
	 * workload names, source lines, or hop ids.</p>
	 */
	public static double analysisAwareUnitLocalCost(PlacementAnalysis analysis,
			CompiledHopKey key) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		double dynamicKernelFloor = latentWdivmmComputeTimeFloor(analysis, key, hop);
		double operation = FederatedCostModel.computeOpCostWithFallback(hop, dynamicKernelFloor);
		if(hop instanceof DataOp) {
			OpOpData op = ((DataOp)hop).getOp();
			return op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE
				? 0.0 : operation;
		}
		return FederatedCostModel.computeLocalIndexingCostWithFallback(hop, operation);
	}

	/**
	 * Replacement payload for the local outer-product intermediate consumed by a
	 * predicted dynamic WDivMM rewrite.
	 *
	 * <p>The pre-rewrite DAG exposes a dense {@code U %*% t(V)} matrix as the
	 * elementwise operation's local input.  The runtime rewrite never materializes or
	 * uploads that matrix: it sends only the one factor not already present as the
	 * root matrix-multiply input.  Returning that factor's dense payload prevents the
	 * exact selectors from charging a phantom full-matrix upload while retaining the
	 * real input-preparation cost.  Shared intermediates are deliberately excluded
	 * because they cannot be proven dead after fusion.</p>
	 *
	 * @return replacement bytes, or {@code -1} when the edge is not proven fused
	 */
	public static double latentWdivmmFusedInputPreparationBytes(PlacementAnalysis analysis,
			CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(producer, "producer");
		Objects.requireNonNull(consumer, "consumer");
		if(inputPosition != 1)
			return -1.0;
		Hop weightedHop = analysis.hop(consumer).orElse(null);
		Hop outerHop = analysis.hop(producer).orElse(null);
		if(weightedHop == null || outerHop == null || weightedHop.getParent().size() != 1
			|| outerHop.getParent().size() != 1)
			return -1.0;
		List<PlacementState> outerStates = analysis.graph().node(producer).orElseThrow()
			.legalAlternatives();
		if(outerStates.size() != 1 || outerStates.get(0).execType() != ExecType.CP
			|| outerStates.get(0).output() != FederatedOutput.LOUT
			|| outerStates.get(0).fType() != null)
			return -1.0;
		WeightedOuter weighted = weightedOuter(analysis,
			new ExactInput(consumer, weightedHop,
				analysis.shapeFact(consumer).orElse(null)));
		if(weighted == null || weighted.outer().key() != producer)
			return -1.0;

		PlacementAnalysis.CompiledInputEdgeFact rootEdge = null;
		for(PlacementAnalysis.CompiledInputEdgeFact edge
				: analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != consumer)
				continue;
			if(rootEdge != null)
				return -1.0;
			rootEdge = edge;
		}
		if(rootEdge == null)
			return -1.0;
		Hop root = analysis.hop(rootEdge.consumer()).orElse(null);
		if(root == null || !(root instanceof AggBinaryOp)
			|| !((AggBinaryOp)root).isMatrixMultiply())
			return -1.0;
		ExactInput left = findExactInput(analysis, rootEdge.consumer(), 0);
		ExactInput right = findExactInput(analysis, rootEdge.consumer(), 1);
		if(left == null || right == null)
			return -1.0;
		NodeShapeFact weights = weighted.weights().shape();
		if(rootEdge.inputPosition() == 0
			&& latentLeftWeightedWdivmmFloor(analysis, rootEdge.consumer(), left, right) > 0.0) {
			long rank = right.shape().cols();
			return denseMatrixBytes(weights.rows(), rank);
		}
		if(rootEdge.inputPosition() == 1
			&& latentRightWeightedWdivmmFloor(analysis, rootEdge.consumer(), left, right) > 0.0) {
			long rank = left.shape().rows();
			return denseMatrixBytes(weights.cols(), rank);
		}
		return -1.0;
	}

	private static double latentWdivmmComputeTimeFloor(PlacementAnalysis analysis,
			CompiledHopKey rootKey, Hop root) {
		if(!(root instanceof AggBinaryOp) || !((AggBinaryOp)root).isMatrixMultiply()
			|| root.getInput() == null || root.getInput().size() != 2)
			return 0.0;
		ExactInput left = findExactInput(analysis, rootKey, 0);
		ExactInput right = findExactInput(analysis, rootKey, 1);
		if(left == null || right == null)
			return 0.0;
		double rightWeighted = latentRightWeightedWdivmmFloor(analysis, rootKey,
			left, right);
		if(rightWeighted > 0.0)
			return rightWeighted;
		return latentLeftWeightedWdivmmFloor(analysis, rootKey, left, right);
	}

	/** Pattern 1: {@code t(U) %*% (W op (U %*% t(V)))}. */
	private static double latentRightWeightedWdivmmFloor(PlacementAnalysis analysis,
			CompiledHopKey rootKey, ExactInput left, ExactInput weightedInput) {
		WeightedOuter weighted = weightedOuter(analysis, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			left.hop(), weighted.outerLeft().hop()))
			return 0.0;
		NodeShapeFact weights = weighted.weights().shape();
		NodeShapeFact weightedShape = weightedInput.shape();
		NodeShapeFact root = analysis.shapeFact(rootKey).orElse(null);
		NodeShapeFact transposeU = left.shape();
		NodeShapeFact u = weighted.outerLeft().shape();
		NodeShapeFact transposedV = weighted.outerRight().shape();
		if(!sameKnownMatrixShape(weights, weightedShape) || !knownMatrix(root)
			|| !knownMatrix(transposeU) || !knownMatrix(u)
			|| !knownPositiveOrDeferredMatrix(transposedV))
			return 0.0;
		long rank = transposeU.rows();
		if(rank <= 1 || transposeU.cols() != weights.rows()
			|| root.rows() != rank || root.cols() != weights.cols()
			|| u.rows() != weights.rows() || u.cols() != rank
			|| (transposedV.rows() > 0 && transposedV.rows() != rank)
			|| (transposedV.cols() > 0 && transposedV.cols() != weights.cols())
			|| !singleColumnBlock(u, weighted.outerLeft().hop()))
			return 0.0;
		return FederatedCostModel.computeWdivmmRankAwareComputeTimeFloor(
			weights.rows(), weights.cols(), rank);
	}

	/** Pattern 2: {@code (W op (U %*% t(V))) %*% V}. */
	private static double latentLeftWeightedWdivmmFloor(PlacementAnalysis analysis,
			CompiledHopKey rootKey, ExactInput weightedInput, ExactInput right) {
		WeightedOuter weighted = weightedOuter(analysis, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			right.hop(), weighted.outerRight().hop()))
			return 0.0;
		NodeShapeFact weights = weighted.weights().shape();
		NodeShapeFact weightedShape = weightedInput.shape();
		NodeShapeFact root = analysis.shapeFact(rootKey).orElse(null);
		NodeShapeFact v = right.shape();
		NodeShapeFact u = weighted.outerLeft().shape();
		NodeShapeFact transposedV = weighted.outerRight().shape();
		if(!sameKnownMatrixShape(weights, weightedShape) || !knownMatrix(root)
			|| !knownMatrix(v) || !knownPositiveOrDeferredMatrix(u)
			|| !knownMatrix(transposedV))
			return 0.0;
		long rank = v.cols();
		if(rank <= 1 || v.rows() != weights.cols()
			|| root.rows() != weights.rows() || root.cols() != rank
			|| transposedV.rows() != rank || transposedV.cols() != weights.cols()
			|| (u.rows() > 0 && u.rows() != weights.rows())
			|| (u.cols() > 0 && u.cols() != rank)
			|| !singleColumnBlock(u, weighted.outerLeft().hop()))
			return 0.0;
		return FederatedCostModel.computeWdivmmRankAwareComputeTimeFloor(
			weights.rows(), weights.cols(), rank);
	}

	private static WeightedOuter weightedOuter(PlacementAnalysis analysis,
			ExactInput weightedInput) {
		if(!(weightedInput.hop() instanceof BinaryOp binary)
			|| (binary.getOp() != OpOp2.MULT && binary.getOp() != OpOp2.DIV)
			|| binary.getInput() == null || binary.getInput().size() != 2)
			return null;
		ExactInput weights = findExactInput(analysis, weightedInput.key(), 0);
		ExactInput outer = findExactInput(analysis, weightedInput.key(), 1);
		if(weights == null || outer == null)
			return null;
		if(!(outer.hop() instanceof AggBinaryOp)
			|| !((AggBinaryOp)outer.hop()).isMatrixMultiply()
			|| outer.hop().getInput() == null || outer.hop().getInput().size() != 2)
			return null;
		ExactInput outerLeft = findExactInput(analysis, outer.key(), 0);
		ExactInput outerRight = findExactInput(analysis, outer.key(), 1);
		return outerLeft == null || outerRight == null ? null
			: new WeightedOuter(weights, outer, outerLeft, outerRight);
	}

	private static ExactInput findExactInput(PlacementAnalysis analysis,
			CompiledHopKey consumer, int position) {
		PlacementAnalysis.CompiledInputEdgeFact match = null;
		for(PlacementAnalysis.CompiledInputEdgeFact edge
				: analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.consumer() != consumer || edge.inputPosition() != position)
				continue;
			if(match != null)
				throw new IllegalArgumentException("Placement cost input is ambiguous: "
					+ consumer.normalizedSignature() + '@' + position);
			match = edge;
		}
		if(match == null)
			return null;
		Hop hop = analysis.hop(match.producer()).orElseThrow(() ->
			new IllegalArgumentException("Placement cost input has no owned Hop"));
		NodeShapeFact shape = analysis.shapeFact(match.producer()).orElseThrow(() ->
			new IllegalArgumentException("Placement cost input has no shape fact"));
		return new ExactInput(match.producer(), hop, shape);
	}

	private static boolean knownMatrix(NodeShapeFact shape) {
		return shape != null && shape.knownPositiveMatrix();
	}

	private static boolean knownPositiveOrDeferredMatrix(NodeShapeFact shape) {
		return shape != null && shape.dataType().isMatrix()
			&& (shape.rows() > 0 || shape.cols() > 0);
	}

	private static boolean sameKnownMatrixShape(NodeShapeFact left, NodeShapeFact right) {
		return knownMatrix(left) && knownMatrix(right)
			&& left.rows() == right.rows() && left.cols() == right.cols();
	}

	private static boolean singleColumnBlock(NodeShapeFact shape, Hop hop) {
		return shape != null && shape.cols() > 0 && hop != null && hop.getBlocksize() > 0
			&& shape.cols() <= hop.getBlocksize();
	}

	private static double denseMatrixBytes(long rows, long cols) {
		if(rows <= 0 || cols <= 0)
			return -1.0;
		return OptimizerUtils.estimateSizeExactSparsity(rows, cols, 1.0, DataType.MATRIX);
	}

	private record ExactInput(CompiledHopKey key, Hop hop, NodeShapeFact shape) { }
	private record WeightedOuter(ExactInput weights, ExactInput outer,
		ExactInput outerLeft, ExactInput outerRight) { }

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

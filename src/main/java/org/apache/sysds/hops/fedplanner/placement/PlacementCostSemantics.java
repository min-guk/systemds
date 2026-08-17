/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ReorgOp;
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

	/**
	 * Whether one selected REFED action starts from a value that is still physically federated.
	 *
	 * <p>The selected placement is the physical authority at the REFED source. A selected FOUT
	 * source needs the explicitly costed FED-to-local pre-stage before upload to a different worker
	 * pool. A selected LOUT source does not. In particular, a CP/LOUT function formal is local for
	 * every invocation: any FED/FOUT actual that reaches that formal owns a separate, exact
	 * function-call input local-materialization action selected by
	 * {@link LocalMaterializationSelections}. Recursing back to the caller actual here would count
	 * that already-planned transfer a second time and would make one shared formal depend on a
	 * mixture of unrelated call sites.</p>
	 */
	public static boolean requiresRefedLocalMaterialization(PlacementAnalysis analysis,
		NeutralPlacementGraph.Node source,
		Map<CompiledHopKey,PlacementEmissionState> selected) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(selected, "selected");
		PlacementState selectedSource = exactSelectedState(selected, source.key());
		if(selectedSource.output() == FederatedOutput.FOUT)
			return true;
		if(selectedSource.output() == FederatedOutput.LOUT)
			return false;
		throw new IllegalArgumentException("REFED source must be LOUT or FOUT");
	}

	private static PlacementState exactSelectedState(
		Map<CompiledHopKey,PlacementEmissionState> selected, CompiledHopKey key) {
		for(Map.Entry<CompiledHopKey,PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == key)
				return Objects.requireNonNull(entry.getValue(), "selected emission state").placementState();
		throw new IllegalStateException("Logical placement provenance has no exact selected state: "
			+ key.normalizedSignature());
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
		if(isLatentWdivmmTransposePairInner(analysis, key, hop))
			return 0.0;
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
	 * Whether a FED alternative for a collapsed transpose-pair WDivMM owns real
	 * partitioned runtime work even though the pre-rewrite outer transpose exposes
	 * only the soon-to-be-removed inner matrix result as its immediate input.
	 */
	public static boolean hasPartitionedLatentWdivmmRuntimeInput(
			PlacementAnalysis analysis, CompiledHopKey key) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		Hop hop = analysis.hop(key).orElse(null);
		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePair(analysis, key, hop);
		return pair != null && pair.partitionedInputFType() != null;
	}

	public static LatentWdivmmTransposePairFact latentWdivmmTransposePairFact(
			PlacementAnalysis analysis, CompiledHopKey key) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		return latentWdivmmTransposePair(analysis, key, analysis.hop(key).orElse(null));
	}

	/**
	 * Exact runtime output contract of a source-level transpose pair that recompiles
	 * to one WDivMM instruction.
	 *
	 * <p>This overload is used by the common graph builder before a complete
	 * {@link PlacementAnalysis} exists. It consumes the same immutable occurrence,
	 * edge, shape, and legal-state facts as the post-build cost model; consequently
	 * candidate legality and cost recognition cannot drift apart.</p>
	 */
	static LatentWdivmmTransposePairFact latentWdivmmTransposePairFact(
			Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop,
			List<PlacementAnalysis.CompiledInputEdgeFact> compiledInputEdges,
			List<NeutralPlacementGraph.Node> nodes, CompiledHopKey ownerKey) {
		Objects.requireNonNull(origins, "origins");
		Objects.requireNonNull(factsByHop, "factsByHop");
		Objects.requireNonNull(compiledInputEdges, "compiledInputEdges");
		Objects.requireNonNull(nodes, "nodes");
		Objects.requireNonNull(ownerKey, "ownerKey");
		Map<CompiledHopKey,NeutralPlacementGraph.Node> nodesByKey = new java.util.IdentityHashMap<>();
		for(NeutralPlacementGraph.Node node : nodes)
			nodesByKey.put(node.key(), node);
		ExactPlacementFacts facts = new ExactPlacementFacts() {
			@Override public Hop hop(CompiledHopKey key) { return origins.get(key); }
			@Override public NodeShapeFact shape(CompiledHopKey key) {
				Hop hop = origins.get(key);
				return hop == null ? null : factsByHop.get(hop);
			}
			@Override public List<PlacementAnalysis.CompiledInputEdgeFact> edges() {
				return compiledInputEdges;
			}
			@Override public List<PlacementState> legalAlternatives(CompiledHopKey key) {
				NeutralPlacementGraph.Node node = nodesByKey.get(key);
				return node == null ? List.of() : node.legalAlternatives();
			}
		};
		return latentWdivmmTransposePair(facts, ownerKey, facts.hop(ownerKey));
	}

	/** FED compute cost after replacing a source-level shell with its runtime kernel. */
	public static double analysisAwareFederatedComputeCost(PlacementAnalysis analysis,
			CompiledHopKey key, double baseSelfCost, int workers,
			boolean broadcastOnlyFedCompute) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		if(hasPartitionedLatentWdivmmRuntimeInput(analysis, key))
			return baseSelfCost / Math.max(1, workers);
		return FederatedCostModel.computeFederatedComputeCost(
			hop, baseSelfCost, workers, broadcastOnlyFedCompute);
	}

	/**
	 * Runtime-stage result fan-in for a source shell that recompiles to an
	 * overlapping-partial WDivMM.
	 *
	 * <p>The source owner is a transpose, so opcode-only cost dispatch would charge
	 * one generic matrix download. The runtime instruction is instead a LEFT WDivMM:
	 * each ROW-partitioned worker returns an overlapping partial which is aggregated
	 * at the coordinator. Reuse the aggregate-binary fan-in contract with the exact
	 * runtime output shape while keeping ordinary source HOPs unchanged.</p>
	 */
	public static double analysisAwareNativeFederatedLoutResultCost(
		PlacementAnalysis analysis, CompiledHopKey key, double outputMemEstimate,
		int workers, double genericResultDownloadCost) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePairFact(analysis, key);
		if(pair == null || !pair.nativeOutputMustBeLocal()
			|| pair.partitionedInputFType() == null)
			return genericResultDownloadCost;
		Hop runtimeKernel = analysis.hop(pair.inner()).orElseThrow(() ->
			new IllegalStateException("Latent WDivMM runtime kernel has no owned Hop"));
		return FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			runtimeKernel, pair.partitionedInputFType(), outputMemEstimate, workers,
			genericResultDownloadCost);
	}

	/** Whether this source edge is removed when the transpose-pair WDivMM is formed. */
	public static boolean isLatentWdivmmTransposePairBoundary(PlacementAnalysis analysis,
			CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(producer, "producer");
		Objects.requireNonNull(consumer, "consumer");
		if(inputPosition != 0)
			return false;
		Hop owner = analysis.hop(consumer).orElse(null);
		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePair(
			analysis, consumer, owner);
		return pair != null && pair.inner() == producer;
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

	/**
	 * Conservative physical payload for a coordinator-local matrix input embedded in a
	 * federated elementwise instruction.
	 *
	 * <p>Pre-recompile transient reads can retain one unknown dimension even when the
	 * exact compiled consumer occurrence has a concrete output shape.  The generic HOP
	 * memory estimate then carries the multi-gigabyte unknown-size sentinel.  Charging
	 * that sentinel as a broadcast on every loop execution is neither a runtime bound
	 * nor an estimate of the value that the instruction can consume.</p>
	 *
	 * <p>For a non-outer elementwise binary operation, a matrix input compatible with a
	 * known {@code r x c} output is limited to the full {@code r x c} shape, a row
	 * vector, or a column vector.  This method enumerates every such shape consistent
	 * with the immutable input shape fact and returns the maximum wire cost and logical
	 * bytes.  The result is therefore conservative without closing any legal planner
	 * candidate.  When the operation or shape relation is not proven, callers must use
	 * their ordinary cost path.</p>
	 *
	 * @return a conservative estimate, or {@code null} when the exact relation is not proven
	 */
	public static NativeLocalInputTransferEstimate boundedElementwiseNativeLocalInputTransfer(
		PlacementAnalysis analysis, CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition, FType executionFType, int workers) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(producer, "producer");
		Objects.requireNonNull(consumer, "consumer");
		if(executionFType == null || inputPosition < 0)
			return null;
		Hop consumerHop = analysis.hop(consumer).orElse(null);
		if(!(consumerHop instanceof BinaryOp binary) || binary.isOuter()
			|| !binary.getOp().isValidOuter() || binary.getInput() == null
			|| inputPosition >= binary.getInput().size()
			|| binary.getInput().get(inputPosition).getDataType() == null
			|| !binary.getInput().get(inputPosition).getDataType().isMatrix())
			return null;
		NodeShapeFact input = analysis.shapeFact(producer).orElse(null);
		NodeShapeFact output = analysis.shapeFact(consumer).orElse(null);
		if(input == null || input.dataType() == null || !input.dataType().isMatrix()
			|| output == null || !output.knownPositiveMatrix())
			return null;

		Set<MatrixShape> compatible = new LinkedHashSet<>();
		addCompatibleShape(compatible, input, output.rows(), output.cols());
		addCompatibleShape(compatible, input, 1L, output.cols());
		addCompatibleShape(compatible, input, output.rows(), 1L);
		if(compatible.isEmpty())
			return null;

		double maximumBytes = 0.0;
		double maximumUpload = 0.0;
		for(MatrixShape shape : compatible) {
			double bytes = denseMatrixBytes(shape.rows(), shape.cols());
			if(bytes <= 0.0)
				return null;
			boolean sameShape = shape.rows() == output.rows() && shape.cols() == output.cols();
			FType transferType = sameShape
				&& (executionFType == FType.ROW || executionFType == FType.COL)
					? executionFType : FType.BROADCAST;
			maximumBytes = Math.max(maximumBytes, bytes);
			maximumUpload = Math.max(maximumUpload,
				FederatedCostModel.computeInBandUploadPayloadCost(bytes, transferType, workers));
		}
		return new NativeLocalInputTransferEstimate(maximumBytes, maximumUpload);
	}

	private static void addCompatibleShape(Set<MatrixShape> candidates, NodeShapeFact input,
		long rows, long cols) {
		if(rows > 0 && cols > 0 && dimensionCompatible(input.rows(), rows)
			&& dimensionCompatible(input.cols(), cols))
			candidates.add(new MatrixShape(rows, cols));
	}

	private static boolean dimensionCompatible(long known, long candidate) {
		return known <= 0 || known == candidate;
	}

	public record NativeLocalInputTransferEstimate(double logicalBytesUpperBound,
		double uploadPayloadCostUpperBound) {
		public NativeLocalInputTransferEstimate {
			if(!Double.isFinite(logicalBytesUpperBound) || logicalBytesUpperBound <= 0.0
				|| !Double.isFinite(uploadPayloadCostUpperBound)
				|| uploadPayloadCostUpperBound < 0.0)
				throw new IllegalArgumentException("Invalid native-local input transfer estimate");
		}
	}

	private record MatrixShape(long rows, long cols) { }

	private static double latentWdivmmComputeTimeFloor(PlacementAnalysis analysis,
			CompiledHopKey rootKey, Hop root) {
		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePair(analysis, rootKey, root);
		if(pair != null)
			return pair.computeTimeFloor();
		return directLatentWdivmmComputeTimeFloor(analysis, rootKey, root);
	}

	private static double directLatentWdivmmComputeTimeFloor(PlacementAnalysis analysis,
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

	/**
	 * Exact source-level owner of a dynamic {@code t(WDivMM)} wrapper that is removed
	 * together with an enclosing transpose.
	 *
	 * <p>The runtime rewrite transfers placement authority from the removed inner
	 * matrix-multiply root to this outer transpose. Cost ownership must follow the
	 * same transfer: otherwise the selector can pay the expensive kernel on a FED
	 * inner node but choose CP for the actual runtime WDivMM owner. This recognizes
	 * only the right-weighted WDivMM form that creates the transpose wrapper and only
	 * when the inner root has this outer transpose as its sole consumer.</p>
	 */
	private static LatentWdivmmTransposePairFact latentWdivmmTransposePair(
			PlacementAnalysis analysis, CompiledHopKey ownerKey, Hop owner) {
		return latentWdivmmTransposePair(exactPlacementFacts(analysis), ownerKey, owner);
	}

	private static LatentWdivmmTransposePairFact latentWdivmmTransposePair(
			ExactPlacementFacts facts, CompiledHopKey ownerKey, Hop owner) {
		if(!(owner instanceof ReorgOp reorg) || reorg.getOp() != ReOrgOp.TRANS
			|| owner.getInput() == null || owner.getInput().size() != 1)
			return null;
		ExactInput inner = findExactInput(facts, ownerKey, 0);
		if(inner == null || !(inner.hop() instanceof AggBinaryOp)
			|| !((AggBinaryOp)inner.hop()).isMatrixMultiply()
			|| inner.hop().getParent() == null || inner.hop().getParent().size() != 1
			|| inner.hop().getParent().get(0) != owner)
			return null;
		ExactInput left = findExactInput(facts, inner.key(), 0);
		ExactInput right = findExactInput(facts, inner.key(), 1);
		if(left == null || right == null)
			return null;
		double floor = latentRightWeightedWdivmmFloor(facts, inner.key(), left, right);
		if(floor <= 0.0)
			return null;
		WeightedOuter weighted = weightedOuter(facts, right);
		FType partitionedInputFType = weighted == null ? null
			: uniquePartitionedFoutType(facts, weighted.weights().key());
		// Pattern 1 lowers to a LEFT WDivMM. ROW-partitioned X yields overlapping
		// worker partials, so QuaternaryWDivMMFEDInstruction always aggregates them
		// locally even when an FOUT flag is serialized. Publishing native FOUT here
		// would therefore be a planner/runtime contract violation.
		boolean nativeOutputMustBeLocal = partitionedInputFType == FType.ROW;
		return new LatentWdivmmTransposePairFact(inner.key(), weighted.weights().key(),
			floor, partitionedInputFType, nativeOutputMustBeLocal);
	}

	private static FType uniquePartitionedFoutType(PlacementAnalysis analysis,
			CompiledHopKey key) {
		return uniquePartitionedFoutType(exactPlacementFacts(analysis), key);
	}

	private static FType uniquePartitionedFoutType(ExactPlacementFacts facts,
			CompiledHopKey key) {
		List<FType> types = facts.legalAlternatives(key).stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT
				&& (state.fType() == FType.ROW || state.fType() == FType.COL))
			.map(PlacementState::fType).distinct().toList();
		return types.size() == 1 ? types.get(0) : null;
	}

	private static boolean isLatentWdivmmTransposePairInner(PlacementAnalysis analysis,
			CompiledHopKey innerKey, Hop inner) {
		if(!(inner instanceof AggBinaryOp) || !((AggBinaryOp)inner).isMatrixMultiply())
			return false;
		PlacementAnalysis.CompiledInputEdgeFact soleConsumer = null;
		for(PlacementAnalysis.CompiledInputEdgeFact edge
				: analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != innerKey)
				continue;
			if(soleConsumer != null)
				return false;
			soleConsumer = edge;
		}
		if(soleConsumer == null || soleConsumer.inputPosition() != 0)
			return false;
		Hop owner = analysis.hop(soleConsumer.consumer()).orElse(null);
		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePair(
			analysis, soleConsumer.consumer(), owner);
		return pair != null && pair.inner() == innerKey;
	}

	/** Pattern 1: {@code t(U) %*% (W op (U %*% t(V)))}. */
	private static double latentRightWeightedWdivmmFloor(PlacementAnalysis analysis,
			CompiledHopKey rootKey, ExactInput left, ExactInput weightedInput) {
		return latentRightWeightedWdivmmFloor(exactPlacementFacts(analysis), rootKey,
			left, weightedInput);
	}

	private static double latentRightWeightedWdivmmFloor(ExactPlacementFacts facts,
			CompiledHopKey rootKey, ExactInput left, ExactInput weightedInput) {
		WeightedOuter weighted = weightedOuter(facts, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			left.hop(), weighted.outerLeft().hop()))
			return 0.0;
		NodeShapeFact weights = weighted.weights().shape();
		NodeShapeFact weightedShape = weightedInput.shape();
		NodeShapeFact root = facts.shape(rootKey);
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
		return latentLeftWeightedWdivmmFloor(exactPlacementFacts(analysis), rootKey,
			weightedInput, right);
	}

	private static double latentLeftWeightedWdivmmFloor(ExactPlacementFacts facts,
			CompiledHopKey rootKey, ExactInput weightedInput, ExactInput right) {
		WeightedOuter weighted = weightedOuter(facts, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			right.hop(), weighted.outerRight().hop()))
			return 0.0;
		NodeShapeFact weights = weighted.weights().shape();
		NodeShapeFact weightedShape = weightedInput.shape();
		NodeShapeFact root = facts.shape(rootKey);
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
		return weightedOuter(exactPlacementFacts(analysis), weightedInput);
	}

	private static WeightedOuter weightedOuter(ExactPlacementFacts facts,
			ExactInput weightedInput) {
		if(!(weightedInput.hop() instanceof BinaryOp binary)
			|| (binary.getOp() != OpOp2.MULT && binary.getOp() != OpOp2.DIV)
			|| binary.getInput() == null || binary.getInput().size() != 2)
			return null;
		ExactInput weights = findExactInput(facts, weightedInput.key(), 0);
		ExactInput outer = findExactInput(facts, weightedInput.key(), 1);
		if(weights == null || outer == null)
			return null;
		if(!(outer.hop() instanceof AggBinaryOp)
			|| !((AggBinaryOp)outer.hop()).isMatrixMultiply()
			|| outer.hop().getInput() == null || outer.hop().getInput().size() != 2)
			return null;
		ExactInput outerLeft = findExactInput(facts, outer.key(), 0);
		ExactInput outerRight = findExactInput(facts, outer.key(), 1);
		return outerLeft == null || outerRight == null ? null
			: new WeightedOuter(weights, outer, outerLeft, outerRight);
	}

	private static ExactInput findExactInput(PlacementAnalysis analysis,
			CompiledHopKey consumer, int position) {
		return findExactInput(exactPlacementFacts(analysis), consumer, position);
	}

	private static ExactInput findExactInput(ExactPlacementFacts facts,
			CompiledHopKey consumer, int position) {
		PlacementAnalysis.CompiledInputEdgeFact match = null;
		for(PlacementAnalysis.CompiledInputEdgeFact edge
				: facts.edges()) {
			if(edge.consumer() != consumer || edge.inputPosition() != position)
				continue;
			if(match != null)
				throw new IllegalArgumentException("Placement cost input is ambiguous: "
					+ consumer.normalizedSignature() + '@' + position);
			match = edge;
		}
		if(match == null)
			return null;
		Hop hop = facts.hop(match.producer());
		if(hop == null)
			throw new IllegalArgumentException("Placement cost input has no owned Hop");
		NodeShapeFact shape = facts.shape(match.producer());
		if(shape == null)
			throw new IllegalArgumentException("Placement cost input has no shape fact");
		return new ExactInput(match.producer(), hop, shape);
	}

	private static ExactPlacementFacts exactPlacementFacts(PlacementAnalysis analysis) {
		return new ExactPlacementFacts() {
			@Override public Hop hop(CompiledHopKey key) { return analysis.hop(key).orElse(null); }
			@Override public NodeShapeFact shape(CompiledHopKey key) {
				return analysis.shapeFact(key).orElse(null);
			}
			@Override public List<PlacementAnalysis.CompiledInputEdgeFact> edges() {
				return analysis.compiledInputEdgesInCanonicalOrder();
			}
			@Override public List<PlacementState> legalAlternatives(CompiledHopKey key) {
				return analysis.graph().node(key).map(NeutralPlacementGraph.Node::legalAlternatives)
					.orElse(List.of());
			}
		};
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
	public record LatentWdivmmTransposePairFact(CompiledHopKey inner,
		CompiledHopKey weights, double computeTimeFloor, FType partitionedInputFType,
		boolean nativeOutputMustBeLocal) { }
	private interface ExactPlacementFacts {
		Hop hop(CompiledHopKey key);
		NodeShapeFact shape(CompiledHopKey key);
		List<PlacementAnalysis.CompiledInputEdgeFact> edges();
		List<PlacementState> legalAlternatives(CompiledHopKey key);
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

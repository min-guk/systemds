/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Runtime-independent cost semantics shared by planner-specific models. */
public final class PlacementCostSemantics {
	private PlacementCostSemantics() {
		// utility class
	}

	/**
	 * Precomputed, occurrence-exact expected cardinalities for row-arg-min assignment values.
	 *
	 * <p>The estimate recognizes the common {@code D <= rowMins(D)} idiom through
	 * compiled input edges, transient values, and CFG reaching definitions in the shared
	 * {@link PlacementAnalysis}. It assumes one selected minimum per row for planning;
	 * tied minima can produce more nonzeros at runtime. Consequently, a concrete HOP NNZ
	 * always takes precedence and ambiguous value flow fails closed. The constructor indexes
	 * whole-program relations once and every occurrence result is memoized, so a cost-surface
	 * build does not repeatedly scan the program graph.</p>
	 */
	public static final class ExpectedSparseAssignmentEstimates {
		private final PlacementAnalysis analysis;
		private final IdentityHashMap<CompiledHopKey,Map<Integer,CompiledHopKey>> inputs =
			new IdentityHashMap<>();
		private final IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> logicalWrites =
			new IdentityHashMap<>();
		private final IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> cfgDefinitions =
			new IdentityHashMap<>();
		private final IdentityHashMap<CompiledHopKey,Set<String>> logicalVersions =
			new IdentityHashMap<>();
		private final IdentityHashMap<CompiledHopKey,Optional<ExpectedSparseAssignmentShape>> memo =
			new IdentityHashMap<>();

		private ExpectedSparseAssignmentEstimates(PlacementAnalysis analysis) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			for(PlacementAnalysis.CompiledInputEdgeFact edge :
				analysis.compiledInputEdgesInCanonicalOrder())
				inputs.computeIfAbsent(edge.consumer(), ignored -> new HashMap<>())
					.put(edge.inputPosition(), edge.producer());
			for(PlacementAnalysis.LogicalTransientInputFact fact :
				analysis.logicalTransientInputsInCanonicalOrder()) {
				addIdentityUnique(logicalWrites.computeIfAbsent(fact.targetRead(),
					ignored -> new ArrayList<>()), fact.sourceWrite());
				logicalVersions.computeIfAbsent(fact.targetRead(),
					ignored -> new java.util.TreeSet<>())
					.add(fact.sourceValueVersion().normalizedSignature());
			}
			for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
				cfgDefinitions.put(node.key(),
					analysis.cfgDefinitionSourcesInCanonicalOrder(node.key()));
		}

		/** Expected in-memory bytes, or zero when no safe estimate is available. */
		public double memEstimate(CompiledHopKey key) {
			ExpectedSparseAssignmentShape shape = shape(Objects.requireNonNull(key, "key"),
				Collections.newSetFromMap(new IdentityHashMap<>()));
			if(shape == null)
				return 0.0;
			double sparsity = Math.min(1.0,
				shape.nnz() / (double)shape.rows() / (double)shape.cols());
			return OptimizerUtils.estimateSizeExactSparsity(
				shape.rows(), shape.cols(), sparsity, DataType.MATRIX);
		}

		/** Expected serialized bytes, or zero when no safe estimate is available. */
		public double serializedEstimate(CompiledHopKey key) {
			ExpectedSparseAssignmentShape shape = shape(Objects.requireNonNull(key, "key"),
				Collections.newSetFromMap(new IdentityHashMap<>()));
			return shape == null ? 0.0
				: MatrixBlock.estimateSizeOnDisk(shape.rows(), shape.cols(), shape.nnz());
		}

		private ExpectedSparseAssignmentShape shape(CompiledHopKey key,
				Set<CompiledHopKey> visiting) {
			Optional<ExpectedSparseAssignmentShape> cached = memo.get(key);
			if(cached != null)
				return cached.orElse(null);
			if(!visiting.add(key))
				return null;
			ExpectedSparseAssignmentShape result;
			try {
				result = derive(key, visiting);
			}
			finally {
				visiting.remove(key);
			}
			memo.put(key, Optional.ofNullable(result));
			return result;
		}

		private ExpectedSparseAssignmentShape derive(CompiledHopKey key,
				Set<CompiledHopKey> visiting) {
			Hop hop = analysis.hop(key).orElse(null);
			if(hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix()
				|| hop.getNnz() >= 0)
				return null;

			if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD) {
				ExactInput definition = exactTransientDefinitionInput(key);
				return definition == null ? null : shape(definition.key(), visiting);
			}
			if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS) {
				ExactInput input = input(key, 0);
				ExpectedSparseAssignmentShape inputShape = input == null ? null
					: shape(input.key(), visiting);
				return reshapeLike(key, inputShape);
			}
			if(hop instanceof BinaryOp binary && binary.getOp() == OpOp2.DIV) {
				ExactInput numerator = input(key, 0);
				ExactInput denominator = input(key, 1);
				if(numerator == null || denominator == null
					|| !isExactRowAggregateOf(denominator.key(), numerator.key(), AggOp.SUM))
					return null;
				return reshapeLike(key, shape(numerator.key(), visiting));
			}

			if(!(hop instanceof BinaryOp binary))
				return null;
			ExactInput left = input(key, 0);
			ExactInput right = input(key, 1);
			if(left == null || right == null)
				return null;
			ExactInput source;
			ExactInput minimum;
			switch(binary.getOp()) {
				case LESSEQUAL -> { source = left; minimum = right; }
				case GREATEREQUAL -> { source = right; minimum = left; }
				case EQUAL -> {
					if(isExactRowAggregateOf(right.key(), left.key(), AggOp.MIN)) {
						source = left;
						minimum = right;
					}
					else {
						source = right;
						minimum = left;
					}
				}
				default -> { return null; }
			}
			if(!isExactRowAggregateOf(minimum.key(), source.key(), AggOp.MIN))
				return null;
			NodeShapeFact sourceShape = source.shape();
			NodeShapeFact outputShape = analysis.shapeFact(key).orElse(null);
			long rows = outputShape != null && outputShape.rows() > 0 ? outputShape.rows()
				: sourceShape == null ? -1 : sourceShape.rows();
			long cols = outputShape != null && outputShape.cols() > 0 ? outputShape.cols()
				: sourceShape == null ? -1 : sourceShape.cols();
			if(rows <= 0 || cols <= 0)
				return null;
			long cells = matrixCells(rows, cols);
			return new ExpectedSparseAssignmentShape(rows, cols,
				Math.min(cells, Math.max(1L, rows)));
		}

		private ExpectedSparseAssignmentShape reshapeLike(CompiledHopKey owner,
				ExpectedSparseAssignmentShape inputShape) {
			if(inputShape == null)
				return null;
			NodeShapeFact output = analysis.shapeFact(owner).orElse(null);
			long rows = output != null && output.rows() > 0 ? output.rows() : inputShape.rows();
			long cols = output != null && output.cols() > 0 ? output.cols() : inputShape.cols();
			if(rows <= 0 || cols <= 0)
				return null;
			return new ExpectedSparseAssignmentShape(rows, cols,
				Math.min(matrixCells(rows, cols), inputShape.nnz()));
		}

		private boolean isExactRowAggregateOf(CompiledHopKey aggregateOwner,
				CompiledHopKey expectedInput, AggOp operation) {
			ExactInput aggregate = resolveTransientValue(aggregateOwner);
			if(aggregate == null || !(aggregate.hop() instanceof AggUnaryOp unary)
				|| unary.getOp() != operation || unary.getDirection() != Direction.Row)
				return false;
			ExactInput aggregateInput = input(aggregate.key(), 0);
			return aggregateInput != null
				&& sameExactLogicalValue(aggregateInput.key(), expectedInput);
		}

		private ExactInput resolveTransientValue(CompiledHopKey key) {
			Hop hop = analysis.hop(key).orElse(null);
			if(!(hop instanceof DataOp data) || data.getOp() != OpOpData.TRANSIENTREAD)
				return hop == null ? null : new ExactInput(key, hop,
					analysis.shapeFact(key).orElse(null));
			return exactTransientDefinitionInput(key);
		}

		private ExactInput exactTransientDefinitionInput(CompiledHopKey read) {
			List<CompiledHopKey> writes = logicalWrites.getOrDefault(read, List.of());
			if(writes.isEmpty()) {
				List<CompiledHopKey> cfgWrites = new ArrayList<>();
				for(CompiledHopKey source : cfgDefinitions.getOrDefault(read, List.of()))
					if(analysis.hop(source).map(candidate -> candidate instanceof DataOp data
						&& data.getOp() == OpOpData.TRANSIENTWRITE).orElse(false))
						addIdentityUnique(cfgWrites, source);
				writes = cfgWrites;
			}
			return writes.size() == 1 ? input(writes.get(0), 0) : null;
		}

		private boolean sameExactLogicalValue(CompiledHopKey left, CompiledHopKey right) {
			if(left == right || left.equals(right))
				return true;
			Set<String> leftDefinitions = exactLogicalDefinitionSignatures(left);
			Set<String> rightDefinitions = exactLogicalDefinitionSignatures(right);
			return !leftDefinitions.isEmpty() && leftDefinitions.equals(rightDefinitions);
		}

		private Set<String> exactLogicalDefinitionSignatures(CompiledHopKey key) {
			Set<String> logical = logicalVersions.get(key);
			if(logical != null && !logical.isEmpty())
				return logical;
			Set<String> result = new java.util.TreeSet<>();
			for(CompiledHopKey source : cfgDefinitions.getOrDefault(key, List.of()))
				analysis.graph().node(source).map(node -> node.valueVersion().normalizedSignature())
					.ifPresent(result::add);
			if(result.isEmpty())
				analysis.graph().node(key).map(node -> node.valueVersion().normalizedSignature())
					.ifPresent(result::add);
			return result;
		}

		private ExactInput input(CompiledHopKey consumer, int position) {
			CompiledHopKey producer = inputs.getOrDefault(consumer, Map.of()).get(position);
			if(producer == null)
				return null;
			Hop hop = analysis.hop(producer).orElse(null);
			return hop == null ? null : new ExactInput(producer, hop,
				analysis.shapeFact(producer).orElse(null));
		}
	}

	public static ExpectedSparseAssignmentEstimates expectedSparseAssignmentEstimates(
			PlacementAnalysis analysis) {
		return new ExpectedSparseAssignmentEstimates(analysis);
	}

	/** Convenience wrapper for one expected in-memory estimate. */
	public static double semanticSparseAssignmentMemEstimate(PlacementAnalysis analysis,
			CompiledHopKey key) {
		return expectedSparseAssignmentEstimates(analysis).memEstimate(key);
	}

	/** Convenience wrapper for one expected serialized estimate. */
	public static double semanticSparseAssignmentSerializedMemEstimate(
			PlacementAnalysis analysis, CompiledHopKey key) {
		return expectedSparseAssignmentEstimates(analysis).serializedEstimate(key);
	}

	private static void addIdentityUnique(List<CompiledHopKey> keys, CompiledHopKey candidate) {
		if(keys.stream().noneMatch(existing -> existing == candidate))
			keys.add(candidate);
	}

	private static long matrixCells(long rows, long cols) {
		return rows > Long.MAX_VALUE / cols ? Long.MAX_VALUE : rows * cols;
	}

	private record ExpectedSparseAssignmentShape(long rows, long cols, long nnz) { }

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
	 * Occurrence-exact local operation cost shared by DP and Exact.
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
		if(isLatentWdivmmTransposePairInner(analysis, key, hop)
			|| isDirectWdivmmRemovedIntermediate(analysis, key))
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
	 * Shared mixed FED/local runtime-stage cost using occurrence-exact input sizes.
	 * Compiler HOPs with deferred dimensions retain a large sentinel memory estimate;
	 * when whole-program shape closure proves the exact matrix geometry, that immutable
	 * fact must price the actual broadcast/refederation payload instead.
	 */
	public static FederatedCostModel.MixedFedLocalCost analysisAwareMixedFedLocalCost(
			PlacementAnalysis analysis, CompiledHopKey key, List<Hop> inputHops,
			List<FType> inputFTypes, FType logicalFType, double baseSelfCost,
			double outputMemEstimate, int workers) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		List<Hop> exactInputs = inputHops == null ? new ArrayList<>(hop.getInput()) : inputHops;
		List<Double> inputMemEstimates = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop compiledInput = hop.getInput(position);
			double estimate = Double.NaN;
			if(compiledInput != null && compiledInput.getDataType() != null
				&& compiledInput.getDataType().isMatrix()
				&& (!compiledInput.dimsKnown() || compiledInput.getDim1() <= 0
					|| compiledInput.getDim2() <= 0)) {
				estimate = analysis.compiledInputEdge(key, position)
					.map(edge -> analysisAwareDenseOutputBytes(analysis, edge.producer()))
					.orElse(Double.NaN);
			}
			inputMemEstimates.add(estimate);
		}
		return FederatedCostModel.computeMixedFedLocalCost(hop, exactInputs,
			inputMemEstimates, inputFTypes, logicalFType, baseSelfCost,
			outputMemEstimate, workers);
	}

	/** Dense in-memory bytes from an exact occurrence-scoped abstract shape, or NaN. */
	public static double analysisAwareDenseOutputBytes(PlacementAnalysis analysis,
			CompiledHopKey key) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		AbstractShapeFact shape = analysis.abstractShapeFact(key).orElse(null);
		if(shape == null || shape.dataType() == null || !shape.dataType().isMatrix()
			|| shape.rows().knowledge() != DimensionKnowledge.EXACT
			|| shape.cols().knowledge() != DimensionKnowledge.EXACT
			|| shape.rows().value() <= 0 || shape.cols().value() <= 0)
			return Double.NaN;
		return denseMatrixBytes(shape.rows().value(), shape.cols().value());
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
	 * Exact source-level Pattern-2 substitution owned by the surviving root matrix
	 * multiply: {@code (W op (U %*% t(V))) %*% V}.  The fact is derived only from
	 * occurrence-exact graph, shape, and privacy-filtered legal-state facts.
	 */
	public static DirectWdivmmRuntimeFact directWdivmmRuntimeFact(
			PlacementAnalysis analysis, CompiledHopKey key) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		return directWdivmmRuntimeFact(exactPlacementFacts(analysis), key,
			analysis.hop(key).orElse(null));
	}

	/** Whether a selected direct Pattern-2 owner and its exact W occurrence form an executable runtime state. */
	public static boolean directWdivmmRuntimeAssignmentCompatible(
		DirectWdivmmRuntimeFact runtime, PlacementState owner, PlacementState weights) {
		return directWdivmmRuntimeAssignmentCompatible(runtime, owner,
			owner.execType() == ExecType.FED ? owner.fType() : null, false, weights);
	}

	/**
	 * Runtime compatibility for one exact candidate emission.  A derived FOUT
	 * candidate has two layouts: the native FED/LOUT execution layout and the
	 * final post-execution materialization layout.  WDivMM consumes the former;
	 * comparing its input FederationMap with the latter incorrectly removes legal
	 * ROW/COL execution followed by a BROADCAST/FULL materialization.
	 */
	public static boolean directWdivmmRuntimeAssignmentCompatible(
		DirectWdivmmRuntimeFact runtime, PlacementState owner, FType executionFType,
		boolean derivedFedFout, PlacementState weights) {
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(owner, "owner");
		if(owner.execType() != ExecType.FED)
			return owner.execType() == ExecType.CP;
		FType nativeFType = executionFType == null ? owner.fType() : executionFType;
		if(weights == null || runtime.runtimeInputFType() == null
			|| nativeFType != runtime.runtimeInputFType()
			|| weights.execType() != ExecType.FED
			|| weights.output() != FederatedOutput.FOUT
			|| weights.fType() != runtime.runtimeInputFType())
			return false;
		if(derivedFedFout && owner.output() != FederatedOutput.FOUT)
			return false;
		FederatedOutput nativeOutput = derivedFedFout ? FederatedOutput.LOUT : owner.output();
		return !runtime.nativeOutputMustBeLocal() || nativeOutput == FederatedOutput.LOUT;
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
		DirectWdivmmRuntimeFact direct = directWdivmmRuntimeFact(analysis, rootKey);
		if(direct != null)
			return direct.computeTimeFloor();
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

	private static DirectWdivmmRuntimeFact directWdivmmRuntimeFact(
			ExactPlacementFacts facts, CompiledHopKey rootKey, Hop root) {
		if(!OptimizerUtils.ALLOW_OPERATOR_FUSION
			|| !(root instanceof AggBinaryOp) || !((AggBinaryOp)root).isMatrixMultiply()
			|| root.getInput() == null || root.getInput().size() != 2
			|| root.getParent() == null || root.getParent().size() != 1)
			return null;
		ExactInput weighted = findExactInput(facts, rootKey, 0);
		ExactInput right = findExactInput(facts, rootKey, 1);
		if(weighted == null || right == null || weighted.hop().getParent() == null
			|| weighted.hop().getParent().size() != 1
			|| weighted.hop().getParent().get(0) != root)
			return null;
		double floor = latentLeftWeightedWdivmmFloor(facts, rootKey, weighted, right);
		if(floor <= 0.0)
			return null;
		WeightedOuter structure = weightedOuter(facts, weighted);
		if(structure == null || structure.outer().hop().getParent() == null
			|| structure.outer().hop().getParent().size() != 1
			|| structure.outer().hop().getParent().get(0) != weighted.hop())
			return null;
		FType runtimeInputFType = uniquePartitionedFoutType(facts, structure.weights().key());
		return new DirectWdivmmRuntimeFact(rootKey, weighted.key(), structure.outer().key(),
			structure.weights().key(), floor, runtimeInputFType,
			runtimeInputFType == FType.COL);
	}

	private static boolean isDirectWdivmmRemovedIntermediate(PlacementAnalysis analysis,
			CompiledHopKey key) {
		for(PlacementAnalysis.CompiledInputEdgeFact edge
				: analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != key)
				continue;
			DirectWdivmmRuntimeFact direct = directWdivmmRuntimeFact(
				analysis, edge.consumer());
			if(direct != null && direct.weighted() == key)
				return true;
			for(PlacementAnalysis.CompiledInputEdgeFact parentEdge
					: analysis.compiledInputEdgesInCanonicalOrder()) {
				if(parentEdge.producer() != edge.consumer())
					continue;
				direct = directWdivmmRuntimeFact(analysis, parentEdge.consumer());
				if(direct != null && direct.outer() == key
					&& direct.weighted() == edge.consumer())
					return true;
			}
		}
		return false;
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
		if(!OptimizerUtils.ALLOW_OPERATOR_FUSION
			|| !(owner instanceof ReorgOp reorg) || reorg.getOp() != ReOrgOp.TRANS
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
				&& (state.fType() == FType.ROW || state.fType() == FType.COL
					|| state.fType() == FType.FULL))
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
	public record DirectWdivmmRuntimeFact(CompiledHopKey root, CompiledHopKey weighted,
		CompiledHopKey outer, CompiledHopKey weights, double computeTimeFloor,
		FType runtimeInputFType, boolean nativeOutputMustBeLocal) { }
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

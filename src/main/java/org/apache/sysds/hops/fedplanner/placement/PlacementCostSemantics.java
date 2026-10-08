/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.MapMultChain.ChainType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Runtime-independent cost semantics shared by planner-specific models. */
public final class PlacementCostSemantics {
	private PlacementCostSemantics() {
		// utility class
	}

	/**
	 * Exact layout produced by a local-to-federated materialization on a proven worker pool.
	 * This mirrors the range construction in {@code FEDLocalMaterializeUtil}: an exact
	 * same-shaped ROW/COL anchor keeps its ranges, while every other partitioned output
	 * is split evenly in the order encoded by the selected runtime anchor key.
	 */
	public static DurableAnchorKey materializedOutputAnchor(DurableAnchorKey seed, FType outputType,
		NodeShapeFact shape, CompiledHopKey owner) {
		if(seed == null || seed.fType() == FType.PART || seed.fType() == FType.OTHER
			|| owner == null || outputType == null || shape == null
			|| !shape.knownPositiveMatrix() || outputType == FType.PART || outputType == FType.OTHER)
			return null;
		List<AnchorPartition> source = seed.partitions();
		if(source.isEmpty() || source.stream().anyMatch(partition ->
			partition.begin().size() != 2 || partition.end().size() != 2))
			return null;
		source = materializationPartitions(seed);
		long rows = shape.rows();
		long cols = shape.cols();
		if(outputType == FType.FULL && source.size() != 1)
			return null;
		if(outputType == FType.ROW && (rows < source.size()
			|| rows > Integer.MAX_VALUE || cols > Integer.MAX_VALUE)
			|| outputType == FType.COL && (cols < source.size()
				|| rows > Integer.MAX_VALUE || cols > Integer.MAX_VALUE))
			return null;

		List<AnchorPartition> partitions = new ArrayList<>(source.size());
		if(outputType == FType.BROADCAST || outputType == FType.FULL) {
			for(AnchorPartition partition : source)
				partitions.add(new AnchorPartition(partition.workerId(), List.of(0L, 0L),
					List.of(rows, cols)));
		}
		else if(seed.fType() == outputType
			&& source.stream().mapToLong(partition -> partition.end().get(0)).max().orElse(0) == rows
			&& source.stream().mapToLong(partition -> partition.end().get(1)).max().orElse(0) == cols) {
			// Runtime preserves target-sized anchor ranges; it cannot repair gaps or overlaps.
			if(!exactPartition(source, outputType, rows, cols))
				return null;
			partitions.addAll(source);
		}
		else {
			long length = outputType == FType.ROW ? rows : cols;
			long base = length / source.size();
			long remainder = length % source.size();
			for(int i = 0; i < source.size(); i++) {
				long begin = i * base + Math.min(i, remainder);
				long end = begin + base + (i < remainder ? 1 : 0);
				partitions.add(outputType == FType.ROW
					? new AnchorPartition(source.get(i).workerId(), List.of(begin, 0L), List.of(end, cols))
					: new AnchorPartition(source.get(i).workerId(), List.of(0L, begin), List.of(rows, end)));
			}
		}
		// Value/producer authority belongs to the action and source reference. The
		// upload target itself depends only on its actual endpoints and geometry.
		return PlacementIdentity.canonicalPhysicalLayout(new DurableAnchorKey(
			"materialized-output", outputType, partitions));
	}

	/** Keep worker/range pairs together in the order used by fed_fout and FED result binding. */
	static List<AnchorPartition> materializationPartitions(DurableAnchorKey anchor) {
		if(anchor.fType() != FType.ROW && anchor.fType() != FType.COL)
			return anchor.partitions();
		int axis = anchor.fType() == FType.ROW ? 0 : 1;
		return anchor.partitions().stream().sorted(Comparator
			.comparingLong((AnchorPartition partition) -> partition.begin().get(axis))
			.thenComparingLong(partition -> partition.end().get(axis))
			.thenComparing(AnchorPartition::workerId)).toList();
	}

	private static boolean exactPartition(List<AnchorPartition> partitions, FType type,
		long rows, long cols) {
		long length = type == FType.ROW ? rows : cols;
		long previousEnd = 0;
		for(AnchorPartition partition : partitions) {
			long begin = partition.begin().get(type == FType.ROW ? 0 : 1);
			long end = partition.end().get(type == FType.ROW ? 0 : 1);
			boolean fullOtherDimension = type == FType.ROW
				? partition.begin().get(1) == 0 && partition.end().get(1) == cols
				: partition.begin().get(0) == 0 && partition.end().get(0) == rows;
			if(!fullOtherDimension || begin != previousEnd || end <= begin || end > length)
				return false;
			previousEnd = end;
		}
		return previousEnd == length;
	}


	/** Cost-only operand layout. Exact ranges are never placement-legality evidence. */
	public record InputLayout(FType fType, List<AnchorPartition> ranges, boolean exactRanges) {
		public InputLayout {
			ranges = ranges == null ? List.of() : List.copyOf(ranges);
			if(exactRanges && ranges.isEmpty())
				throw new IllegalArgumentException("Exact input layout requires ranges");
		}
	}

	/** Immutable execution layout supplied by the selected realization. */
	public record FederatedExecutionLayout(FType executionType, int workers,
		List<InputLayout> inputs) {
		public FederatedExecutionLayout {
			if(workers <= 0)
				throw new IllegalArgumentException("Federated execution requires workers");
			inputs = inputs == null ? List.of() : List.copyOf(inputs);
		}
	}

	public record RuntimeFactorInput(CompiledHopKey source, int position, boolean generatedTranspose) { }

	/** Mirrors QuaternaryWDivMMFEDInstruction / FederationMap.isAligned, not FType similarity. */
	public static boolean reusesWdivmmFactor(InputLayout weights, InputLayout factor,
			int position, boolean generatedTranspose) {
		if(generatedTranspose || weights == null || factor == null || factor.fType() == null
			|| !weights.exactRanges() || !factor.exactRanges())
			return false;
		if(position == 1 && (weights.fType() == FType.ROW || weights.fType() == FType.FULL))
			return factor.fType().isType(FType.ROW) && alignedAxis(weights, factor, 0, 0);
		if(position == 2 && weights.fType() == FType.COL)
			return alignedAxis(weights, factor, 1, 1) || alignedAxis(weights, factor, 1, 0);
		if(position == 3 && (weights.fType() == FType.ROW || weights.fType() == FType.COL || weights.fType() == FType.FULL))
			return alignedFull(weights, factor);
		return false;
	}

	/** Matches QuaternaryOp.checkWDivMMType: only base LEFT/RIGHT plus a matrix fourth input is MX. */
	public static boolean isWdivmmMatrixOperand(Hop hop, int position) {
		if(!(hop instanceof QuaternaryOp q) || q.getOp() != OpOp4.WDIVMM
			|| position <= 0 || position >= hop.getInput().size()
			|| !hop.getInput(position).getDataType().isMatrix()) return false;
		return position <= 2 || position == 3 && (q.getBaseType() == 1 || q.getBaseType() == 2);
	}

	public static boolean hasWdivmmEpsilon(Hop hop) {
		return hop instanceof QuaternaryOp q && q.getOp() == OpOp4.WDIVMM
			&& (q.getBaseType() == 3 || q.getBaseType() == 4) && hop.getInput().size() > 3;
	}

	/** Runtime coordinator read, distinct from the producer's placement or a relocation pre-stage. */
	public static boolean wdivmmInputNeedsCollection(Hop hop, FederatedExecutionLayout layout, int position) {
		if(position == 3 && hasWdivmmEpsilon(hop))
			return hop.getInput(position).getDataType().isMatrix();
		if(!isWdivmmMatrixOperand(hop, position)) return false;
		InputLayout weights = layout == null || layout.inputs().isEmpty() ? null : layout.inputs().get(0);
		InputLayout factor = layout == null || position >= layout.inputs().size() ? null : layout.inputs().get(position);
		return !reusesWdivmmFactor(weights, factor, position, false);
	}

	/** Ordinary CTABLE operands; ctableexpand's sequence is a compiler marker, not a matrix read. */
	public static boolean isCtableMatrixInput(Hop hop, int position) {
		return hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE
			&& !ternary.isSequenceRewriteApplicable(true) && position >= 0 && position < 3
			&& position < hop.getInput().size() && hop.getInput(position).getDataType().isMatrix();
	}

	/**
	 * Mirrors CTABLE's acquireRead/broadcastSliced path. The secondary matrix is
	 * always read locally; only fully aligned remote weights can be reused in place.
	 * This is a runtime read demand, not permission to change the input authority.
	 */
	public static boolean ctableInputNeedsCollection(Hop hop, FederatedExecutionLayout layout, int position) {
		if(!isCtableMatrixInput(hop, position) || layout == null || layout.inputs().size() < 2)
			return false;
		int primary = layout.inputs().get(0).fType() != null ? 0
			: layout.inputs().get(1).fType() != null ? 1 : -1;
		if(primary < 0)
			return false;
		if(position < 2)
			return position != primary;
		InputLayout anchor = layout.inputs().get(primary);
		InputLayout weights = layout.inputs().size() > 2 ? layout.inputs().get(2) : null;
		return weights == null || weights.fType() == null || anchor.fType() == FType.BROADCAST
			|| !anchor.exactRanges() || !weights.exactRanges() || !alignedFull(anchor, weights);
	}

	private static boolean alignedFull(InputLayout anchor, InputLayout input) {
		return anchor.ranges().stream().allMatch(a -> {
			var matches = input.ranges().stream().filter(i ->
				a.begin().equals(i.begin()) && a.end().equals(i.end())).toList();
			return !matches.isEmpty() && matches.stream().allMatch(i -> a.workerId().equals(i.workerId()));
		});
	}

	private static boolean alignedAxis(InputLayout weights, InputLayout factor, int axis, int factorAxis) {
		for(AnchorPartition w : weights.ranges()) {
			boolean found = false;
			for(AnchorPartition f : factor.ranges()) {
				if(w.begin().size() <= axis || w.end().size() <= axis
					|| f.begin().size() <= factorAxis || f.end().size() <= factorAxis)
					return false;
				if(w.begin().get(axis).equals(f.begin().get(factorAxis))
					&& w.end().get(axis).equals(f.end().get(factorAxis))) {
					found = true;
					// Runtime requires EVERY axis-matching range to be at the same address.
					if(!w.workerId().equals(f.workerId()))
						return false;
				}
			}
			if(!found) return false;
		}
		return true;
	}

	/** Returned worker payloads, separate from the full execution pool. */
	public record WorkerResponseSummary(double totalBytes, double largestBytes, int responses) { }

	/** Prepared once per occurrence; alternative pricing only projects worker quantities. */
	public static final class PreparedExecutionCost {
		private final Hop hop;
		private final List<OperandQuantity> inputs;
		private final OperandQuantity output;
		private final double flops;
		private final RuntimeWdivmmKernel runtimeWdivmm;
		private final boolean zeroExecution;
		private final boolean removedKernel;
		private final IndexSlice indexSlice;
		private final double localCost;

		private PreparedExecutionCost(Hop hop, List<OperandQuantity> inputs,
				OperandQuantity output, double flops, RuntimeWdivmmKernel runtimeWdivmm,
				boolean zeroExecution, boolean removedKernel, IndexSlice indexSlice, double localCost) {
			this.hop = hop;
			this.inputs = List.copyOf(inputs);
			this.output = output;
			this.flops = positive(flops);
			this.runtimeWdivmm = runtimeWdivmm;
			this.zeroExecution = zeroExecution;
			this.removedKernel = removedKernel;
			this.indexSlice = indexSlice;
			this.localCost = positive(localCost);
		}

		public double localCost() {
			return localCost;
		}

		public boolean removedKernel() { return removedKernel; }

		/** Actual factor reads, including the source of a runtime-generated transpose. */
		public List<RuntimeFactorInput> fusedFactorInputs() {
			return fusedWeightsOccurrence() == null ? List.of() : runtimeWdivmm.factorInputs();
		}

		public double fusedFactorTransposeCost() {
			return fusedWeightsOccurrence() == null ? 0.0 : runtimeWdivmm.factorTransposeCost();
		}

		/** PUT payload only. Reusable GET and its lifetime belong to the materialization collector. */
		public double fusedFactorUploadCost(int position, FType weightsType, int workers) {
			boolean sliced = position == 1 && (weightsType == FType.ROW || weightsType == FType.FULL)
				|| position == 2 && weightsType == FType.COL;
			return FederatedCostModel.computeInBandUploadPayloadCost(
				runtimeWdivmm.factors().get(position - 1).bytes(),
				sliced ? weightsType : FType.BROADCAST, workers);
		}

		/** Compatibility quantity for callers without selected layouts: no alignment is assumed. */
		public double fusedInputPreparationCost(FType weightsType, int workers) {
			return fusedFactorInputs().stream().mapToDouble(input ->
				fusedFactorUploadCost(input.position(), weightsType, workers)).sum()
				+ fusedFactorTransposeCost();
		}

		public double fusedResultCost(FType weightsType, int workers) {
			if(fusedWeightsOccurrence() == null)
				return 0.0;
			return FederatedCostModel.computeWdivmmLoutResultCost(
				runtimeWdivmm.baseType(), weightsType, output.bytes(), workers);
		}

		/** Fused owners consume this occurrence, not the removed source-shell input. */
		public CompiledHopKey fusedWeightsOccurrence() {
			return runtimeWdivmm == null ? null : runtimeWdivmm.fusedWeights();
		}

		public double federatedCost(FederatedExecutionLayout layout) {
			Objects.requireNonNull(layout, "layout");
			if(zeroExecution)
				return 0.0;
			List<String> workerIds = workerIds(layout);
			double maximum = 0.0;
			for(String workerId : workerIds) {
				KernelQuantity quantity = workerQuantity(layout, workerId);
				double cost = FederatedCostModel.computeExecutionCost(
					runtimeWdivmm == null ? hop : null,
					quantity.flops(), quantity.readBytes(), quantity.writeBytes());
				maximum = Math.max(maximum, cost);
			}
			return maximum;
		}

		public WorkerResponseSummary outputResponses(FederatedExecutionLayout layout, double serializedBytes) {
			if(zeroExecution || output.bytes() <= 0 || hop instanceof IndexingOp && indexSlice == null
				|| layout.inputs().stream().noneMatch(InputLayout::exactRanges))
				return null; // No exact response geometry: retain the explicit balanced cost approximation.
			double total = 0.0, largest = 0.0;
			int responses = 0;
			for(String worker : workerIds(layout)) {
				double bytes = serializedBytes * workerQuantity(layout, worker).writeBytes() / output.bytes();
				if(bytes <= 0) continue;
				total += bytes;
				largest = Math.max(largest, bytes);
				responses++;
			}
			return new WorkerResponseSummary(total, largest, responses);
		}

		private KernelQuantity workerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			if(runtimeWdivmm != null)
				return wdivmmWorkerQuantity(layout, workerId);
			if(hop instanceof IndexingOp)
				return indexingWorkerQuantity(layout, workerId);
			if(hop instanceof AggUnaryOp)
				return aggregateWorkerQuantity(layout, workerId);
			if(hop instanceof AggBinaryOp && ((AggBinaryOp)hop).isMatrixMultiply())
				return matmulWorkerQuantity(layout, workerId);
			return genericWorkerQuantity(layout, workerId);
		}

		private KernelQuantity genericWorkerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			double share = executionShare(layout, workerId);
			double read = 0.0;
			for(int i = 0; i < inputs.size(); i++)
				read += inputBytes(layout, i, workerId);
			return new KernelQuantity(flops * share, read, output.bytes() * share);
		}

		private KernelQuantity aggregateWorkerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			double inputShare = inputShare(layout, 0, workerId);
			double read = inputs.isEmpty() ? 0.0 : inputs.get(0).bytes() * inputShare;
			InputLayout inputLayout = inputLayout(layout, 0);
			FType inputType = inputLayout == null ? null : inputLayout.fType();
			Direction direction = ((AggUnaryOp)hop).getDirection();
			boolean aligned = inputType == FType.ROW && direction == Direction.Row
				|| inputType == FType.COL && direction == Direction.Col;
			double writeShare = aligned ? inputShare : 1.0;
			return new KernelQuantity(flops * inputShare, read, output.bytes() * writeShare);
		}

		private KernelQuantity indexingWorkerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			InputLayout inputLayout = inputLayout(layout, 0);
			if(indexSlice == null || inputLayout == null || !inputLayout.exactRanges()) {
				double share = inputShare(layout, 0, workerId);
				return new KernelQuantity(flops * share, output.bytes() * share,
					output.bytes() * share);
			}
			double overlap = 0.0;
			for(AnchorPartition range : inputLayout.ranges())
				if(range.workerId().equals(workerId))
					overlap += indexSlice.overlapCells(range);
			double sliceCells = indexSlice.cells();
			double share = sliceCells > 0.0 ? Math.min(1.0, overlap / sliceCells) : 0.0;
			return new KernelQuantity(flops * share, output.bytes() * share,
				output.bytes() * share);
		}

		private KernelQuantity matmulWorkerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			if(inputs.size() < 2)
				return genericWorkerQuantity(layout, workerId);
			InputLayout leftLayout = inputLayout(layout, 0);
			InputLayout rightLayout = inputLayout(layout, 1);
			FType leftType = leftLayout == null ? null : leftLayout.fType();
			FType rightType = rightLayout == null ? null : rightLayout.fType();
			if(leftType == FType.ROW) {
				double share = inputShare(layout, 0, workerId);
				return new KernelQuantity(flops * share,
					inputs.get(0).bytes() * share + inputs.get(1).bytes(),
					output.bytes() * share);
			}
			if(leftType == FType.COL || rightType == FType.ROW) {
				int partitioned = leftType == FType.COL ? 0 : 1;
				double share = inputShare(layout, partitioned, workerId);
				return new KernelQuantity(flops * share,
					inputs.get(0).bytes() * share + inputs.get(1).bytes() * share,
					output.bytes());
			}
			return genericWorkerQuantity(layout, workerId);
		}

		private KernelQuantity wdivmmWorkerQuantity(FederatedExecutionLayout layout,
				String workerId) {
			// Explicit and fused callers both project the actual weights as input 0.
			// A fused source shell (e.g. rank x columns) is not an operand of this kernel.
			InputLayout weights = inputLayout(layout, 0);
			double weightShare = operandShare(weights, runtimeWdivmm.weights(),
				layout.workers(), workerId);
			FType weightType = weights == null ? layout.executionType() : weights.fType();
			double read = runtimeWdivmm.weights().bytes() * weightShare;
			// QuaternaryWDivMMFEDInstruction slices U with ROW X, V with COL X,
			// and the optional matrix operand with X. The other factor is broadcast.
			for(int i = 0; i < runtimeWdivmm.factors().size(); i++) {
				OperandQuantity operand = runtimeWdivmm.factors().get(i);
				boolean sliced = i == 0 && weightType == FType.ROW
					|| i == 1 && weightType == FType.COL
					|| i >= 2 && operand.cells() > 1;
				read += operand.bytes() * (sliced ? weightShare : 1.0);
			}
			boolean left = runtimeWdivmm.baseType() == 1 || runtimeWdivmm.baseType() == 3;
			boolean right = runtimeWdivmm.baseType() == 2 || runtimeWdivmm.baseType() == 4;
			boolean fullPartial = left && weightType == FType.ROW
				|| right && weightType == FType.COL;
			return new KernelQuantity(runtimeWdivmm.flops() * weightShare, read,
				output.bytes() * (fullPartial ? 1.0 : weightShare));
		}

		private double executionShare(FederatedExecutionLayout layout, String workerId) {
			for(int i = 0; i < inputs.size(); i++) {
				InputLayout input = inputLayout(layout, i);
				if(input != null && isPartitioned(input.fType()))
					return inputShare(layout, i, workerId);
			}
			return 1.0;
		}

		private double inputBytes(FederatedExecutionLayout layout, int position,
				String workerId) {
			OperandQuantity operand = inputs.get(position);
			InputLayout input = inputLayout(layout, position);
			if(input != null && isPartitioned(input.fType()))
				return operand.bytes() * inputShare(layout, position, workerId);
			if(input == null || input.fType() == null) {
				double share = localOperandShare(layout, operand, workerId);
				return operand.bytes() * share;
			}
			return operand.bytes();
		}

		private double localOperandShare(FederatedExecutionLayout layout,
				OperandQuantity operand, String workerId) {
			int partitionedPosition = firstPartitionedInput(layout);
			if(partitionedPosition < 0 || operand.rows() <= 0 || operand.cols() <= 0)
				return 1.0;
			InputLayout partitioned = inputLayout(layout, partitionedPosition);
			FType type = partitioned.fType();
			boolean sliced = (type == FType.ROW || type == FType.PART)
				? output.rows() > 1 && operand.rows() == output.rows()
				: type == FType.COL && output.cols() > 1 && operand.cols() == output.cols();
			return sliced ? inputShare(layout, partitionedPosition, workerId) : 1.0;
		}

		private int firstPartitionedInput(FederatedExecutionLayout layout) {
			for(int i = 0; i < inputs.size(); i++) {
				InputLayout input = inputLayout(layout, i);
				if(input != null && isPartitioned(input.fType()))
					return i;
			}
			return -1;
		}

		private double inputShare(FederatedExecutionLayout layout, int position,
				String workerId) {
			if(position < 0 || position >= inputs.size())
				return 0.0;
			return operandShare(inputLayout(layout, position), inputs.get(position),
				layout.workers(), workerId);
		}

		private static double operandShare(InputLayout input, OperandQuantity operand,
				int workers, String workerId) {
			if(input == null || !isPartitioned(input.fType()))
				return 1.0;
			// PART can denote overlapping full partials. Only proven ranges authorize
			// a geometric share; an unknown PART map conservatively prices full work.
			if(!input.exactRanges() && input.fType() == FType.PART)
				return 1.0;
			if(!input.exactRanges())
				return 1.0 / workers;
			double totalCells = operand.cells();
			if(totalCells <= 0.0)
				return 1.0 / workers;
			double workerCells = 0.0;
			for(AnchorPartition range : input.ranges())
				if(range.workerId().equals(workerId))
					workerCells += operand.clippedCells(range);
			return Math.min(1.0, workerCells / totalCells);
		}

		private static InputLayout inputLayout(FederatedExecutionLayout layout, int position) {
			return position >= 0 && position < layout.inputs().size()
				? layout.inputs().get(position) : null;
		}

		private List<String> workerIds(FederatedExecutionLayout layout) {
			LinkedHashSet<String> ids = new LinkedHashSet<>();
			List<InputLayout> executionInputs = runtimeWdivmm == null || layout.inputs().isEmpty()
				? layout.inputs() : layout.inputs().subList(0, 1);
			for(InputLayout input : executionInputs)
				if(input != null && input.exactRanges())
					for(AnchorPartition range : input.ranges())
						ids.add(range.workerId());
			for(int i = ids.size(); i < layout.workers(); i++)
				ids.add("cost-worker-" + i);
			return List.copyOf(ids);
		}
	}

	private record OperandQuantity(long rows, long cols, long nnz, double bytes) {
		private double cells() {
			return rows > 0 && cols > 0 ? rows * (double)cols : 0.0;
		}

		private double clippedCells(AnchorPartition range) {
			if(rows <= 0 || cols <= 0 || range.begin().size() < 2 || range.end().size() < 2)
				return 0.0;
			long rowBegin = Math.max(0L, Math.min(rows, range.begin().get(0)));
			long rowEnd = Math.max(rowBegin, Math.min(rows, range.end().get(0)));
			long colBegin = Math.max(0L, Math.min(cols, range.begin().get(1)));
			long colEnd = Math.max(colBegin, Math.min(cols, range.end().get(1)));
			return (rowEnd - rowBegin) * (double)(colEnd - colBegin);
		}
	}

	private record KernelQuantity(double flops, double readBytes, double writeBytes) { }
	private record RuntimeWdivmmKernel(OperandQuantity weights,
		List<OperandQuantity> factors, double flops, int baseType,
		CompiledHopKey fusedWeights, double factorTransposeCost, List<RuntimeFactorInput> factorInputs) {
		private RuntimeWdivmmKernel {
			factors = List.copyOf(factors);
			factorInputs = List.copyOf(factorInputs);
		}

		private double readBytes() {
			return weights.bytes() + factors.stream()
				.mapToDouble(OperandQuantity::bytes).sum();
		}
	}

	private record IndexSlice(long rowBegin, long rowEnd, long colBegin, long colEnd) {
		private double cells() {
			return Math.max(0L, rowEnd - rowBegin) * (double)Math.max(0L, colEnd - colBegin);
		}

		private double overlapCells(AnchorPartition range) {
			if(range.begin().size() < 2 || range.end().size() < 2)
				return 0.0;
			long rows = Math.max(0L, Math.min(rowEnd, range.end().get(0))
				- Math.max(rowBegin, range.begin().get(0)));
			long cols = Math.max(0L, Math.min(colEnd, range.end().get(1))
				- Math.max(colBegin, range.begin().get(1)));
			return rows * (double)cols;
		}
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
					analysis.shapeFact(key).orElse(null),
					analysis.sourceCompiledShapeFact(key).orElse(null));
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
				analysis.shapeFact(producer).orElse(null),
				analysis.sourceCompiledShapeFact(producer).orElse(null));
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
	 * <p>Known HOP dimensions remain authoritative; unknown dimensions use immutable
	 * occurrence-exact shape facts for both ordinary FLOPs and bytes. Cost-size upper
	 * bounds are not exact dimensions. A runtime WDivMM kernel that the dynamic
	 * algebraic rewrite can create after loop/function dimensions become concrete
	 * replaces the source shell with its actual weights, factors, FLOPs and output.
	 * This uses immutable
	 * compiled-input and shape facts rather than mutating HOP dimensions or matching
	 * workload names, source lines, or hop ids.</p>
	 */
	public static PreparedExecutionCost prepareExecutionCost(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key) {
		return prepareExecutionCost(analysis, sparseAssignments, key,
			runtimeOwnership(analysis));
	}

	/** Prepare all occurrence kernels with one shared runtime-ownership analysis. */
	public static Map<CompiledHopKey,PreparedExecutionCost> prepareExecutionCosts(
			PlacementAnalysis analysis, ExpectedSparseAssignmentEstimates sparseAssignments) {
		RuntimeOwnership ownership = runtimeOwnership(analysis);
		Map<CompiledHopKey,PreparedExecutionCost> costs = new IdentityHashMap<>();
		for(var node : analysis.graph().nodes())
			costs.put(node.key(), prepareExecutionCost(analysis, sparseAssignments, node.key(), ownership));
		return Collections.unmodifiableMap(costs);
	}

	private static PreparedExecutionCost prepareExecutionCost(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key,
			RuntimeOwnership ownership) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		if(sparseAssignments != null && sparseAssignments.analysis != analysis)
			throw new IllegalArgumentException("Sparse estimates are not owned by placement analysis");
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		List<OperandQuantity> inputs = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop input = hop.getInput(position);
			CompiledHopKey producer = analysis.compiledInputEdge(key, position)
				.map(PlacementAnalysis.CompiledInputEdgeFact::producer).orElse(null);
			inputs.add(operandQuantity(analysis, sparseAssignments, producer, input));
		}
		OperandQuantity output = operandQuantity(analysis, sparseAssignments, key, hop);
		boolean removedKernel = ownership.removed().contains(key);
		boolean metadata = hop instanceof DataOp && (((DataOp)hop).getOp() == OpOpData.TRANSIENTREAD
			|| ((DataOp)hop).getOp() == OpOpData.TRANSIENTWRITE);
		boolean zeroExecution = analysis.isDmlFunctionCallBoundary(key) || removedKernel || metadata
			|| BranchPlacementNormalization.isPlacementAlias(hop);
		RuntimeWdivmmKernel runtimeWdivmm = zeroExecution ? null
			: runtimeWdivmmKernel(analysis, sparseAssignments, key, hop, inputs);
		LocalMMChainKernel runtimeMMChain = zeroExecution ? null : ownership.mmChains().get(key);
		if(runtimeWdivmm != null && runtimeWdivmm.fusedWeights() != null)
			output = withKernelDimensions(output, hop,
				runtimeWdivmm.baseType() == 1 ? runtimeWdivmm.weights().cols() : runtimeWdivmm.weights().rows(),
				runtimeWdivmm.factors().get(0).cols());
		double flops = zeroExecution ? 0.0 : runtimeWdivmm != null
			? runtimeWdivmm.flops() : runtimeMMChain != null
				? runtimeMMChain.computeNodes().stream().mapToDouble(node ->
					analysisAwareComputeFlops(analysis, node,
						analysis.hop(node).orElseThrow())).sum()
				: analysisAwareComputeFlops(analysis, key, hop);
		double inputBytes = runtimeWdivmm != null ? runtimeWdivmm.readBytes()
			: runtimeMMChain != null ? runtimeMMChain.runtimeInputs().stream().mapToDouble(input ->
				operandQuantity(analysis, sparseAssignments, input,
					analysis.hop(input).orElseThrow()).bytes()).sum()
			: inputs.stream().mapToDouble(OperandQuantity::bytes).sum();
		double local = zeroExecution ? 0.0 : FederatedCostModel.computeExecutionCost(
			runtimeWdivmm == null ? hop : null, flops, inputBytes, output.bytes());
		if(runtimeWdivmm != null)
			local += runtimeWdivmm.factorTransposeCost();
		if(!zeroExecution && hop instanceof IndexingOp)
			local = FederatedCostModel.computeExecutionCost(hop, flops,
				output.bytes(), output.bytes());
		return new PreparedExecutionCost(hop, inputs, output, flops, runtimeWdivmm,
			zeroExecution, removedKernel, indexSlice(hop), local);
	}

	public static PreparedExecutionCost prepareExecutionCost(PlacementAnalysis analysis,
			CompiledHopKey key) {
		return prepareExecutionCost(analysis, null, key);
	}

	public static double analysisAwareUnitLocalCost(PlacementAnalysis analysis,
			CompiledHopKey key) {
		return prepareExecutionCost(analysis, null, key).localCost();
	}

	public static double analysisAwareUnitLocalCost(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key) {
		return prepareExecutionCost(analysis, sparseAssignments, key).localCost();
	}

	private static OperandQuantity operandQuantity(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		if(hop == null)
			return new OperandQuantity(-1, -1, -1, 0.0);
		long rows = analysisAwareComputeDimension(analysis, key, hop, true);
		long cols = analysisAwareComputeDimension(analysis, key, hop, false);
		double bytes = key == null ? FederatedCostModel.getEffectiveOutputMemEstimate(hop)
			: analysisAwareOutputBytes(analysis, sparseAssignments, key, hop);
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			bytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		return new OperandQuantity(rows, cols, hop.getNnz(), positive(bytes));
	}


	private static RuntimeWdivmmKernel runtimeWdivmmKernel(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key,
			Hop hop, List<OperandQuantity> sourceInputs) {
		if(hop instanceof QuaternaryOp quaternary && quaternary.getOp() == OpOp4.WDIVMM
			&& sourceInputs.size() >= 3) {
			List<OperandQuantity> replicated = new ArrayList<>(sourceInputs.subList(1, 3));
			if(isWdivmmMatrixOperand(hop, 3)) replicated.add(sourceInputs.get(3));
			else if(hasWdivmmEpsilon(hop)) replicated.add(new OperandQuantity(1, 1, 1, 8.0));
			return new RuntimeWdivmmKernel(sourceInputs.get(0), replicated,
				analysisAwareComputeFlops(analysis, key, hop), quaternary.getBaseType(), null, 0.0, List.of());
		}

		LatentWdivmmTransposePairFact pair = latentWdivmmTransposePair(analysis, key, hop);
		if(pair != null) {
			ExactInput inner = findExactInput(analysis, key, 0);
			ExactInput weightedInput = inner == null ? null
				: findExactInput(analysis, inner.key(), 1);
			WeightedOuter weighted = weightedInput == null ? null
				: weightedOuter(analysis, weightedInput);
			if(weighted != null)
				return runtimeWdivmmKernel(analysis, sparseAssignments, weighted,
					pair.computeFlops(), 1, provenLatentShape(findExactInput(analysis, inner.key(), 0)).rows(), null);
		}

		DirectWdivmmRuntimeFact direct = directWdivmmRuntimeFact(analysis, key);
		if(direct != null) {
			ExactInput weightedInput = findExactInput(analysis, key, 0);
			WeightedOuter weighted = weightedInput == null ? null
				: weightedOuter(analysis, weightedInput);
			if(weighted != null)
				return runtimeWdivmmKernel(analysis, sparseAssignments, weighted,
					direct.computeFlops(), 2, provenLatentShape(findExactInput(analysis, key, 1)).cols(),
					findExactInput(analysis, key, 1));
		}
		return null;
	}

	private static RuntimeWdivmmKernel runtimeWdivmmKernel(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, WeightedOuter weighted,
			double flops, int baseType, long rank, ExactInput directRight) {
		NodeShapeFact shape = provenLatentShape(weighted.weights());
		boolean explicitTranspose = weighted.outerRight().hop() instanceof ReorgOp reorg
			&& reorg.getOp() == ReOrgOp.TRANS;
		ExactInput vInput = directRight != null ? directRight
			: explicitTranspose ? findExactInput(analysis, weighted.outerRight().key(), 0) : weighted.outerRight();
		OperandQuantity weights = withKernelDimensions(operandQuantity(analysis, sparseAssignments,
			weighted.weights().key(), weighted.weights().hop()), weighted.weights().hop(), shape.rows(), shape.cols());
		OperandQuantity u = withKernelDimensions(operandQuantity(analysis, sparseAssignments,
			weighted.outerLeft().key(), weighted.outerLeft().hop()), weighted.outerLeft().hop(), shape.rows(), rank);
		OperandQuantity v = withKernelDimensions(operandQuantity(analysis, sparseAssignments,
			vInput.key(), vInput.hop()), vInput.hop(), shape.cols(), rank);
		// Pattern 1 also accepts U %*% B; the rewrite then creates V = t(B).
		double transposeCost = directRight != null || explicitTranspose ? 0.0
			: FederatedCostModel.computeExecutionCost(null, v.cells(), v.bytes(), v.bytes());
		return new RuntimeWdivmmKernel(weights, List.of(u, v), flops, baseType,
			weighted.weights().key(), transposeCost, List.of(
				new RuntimeFactorInput(weighted.outerLeft().key(), 1, false),
				new RuntimeFactorInput(vInput.key(), 2, directRight == null && !explicitTranspose)));
	}

	/** Use the same matched kernel dimensions for bytes and FLOPs, not an unknown-size sentinel.
	 * These are cost-only quantities; they do not change abstract shapes or placement authority. */
	private static OperandQuantity withKernelDimensions(OperandQuantity operand, Hop hop, long rows, long cols) {
		if(operand.rows() == rows && operand.cols() == cols)
			return operand;
		double sparsity = hop.getNnz() >= 0 ? Math.min(1.0, hop.getNnz() / (double)rows / cols) : 1.0;
		return new OperandQuantity(rows, cols, hop.getNnz(),
			OptimizerUtils.estimateSizeExactSparsity(rows, cols, sparsity, DataType.MATRIX));
	}

	private static IndexSlice indexSlice(Hop hop) {
		if(!(hop instanceof IndexingOp) || hop.getInput().size() < 5)
			return null;
		Long rowLower = literalLong(hop.getInput(1));
		Long rowUpper = literalLong(hop.getInput(2));
		Long colLower = literalLong(hop.getInput(3));
		Long colUpper = literalLong(hop.getInput(4));
		if(rowLower == null || rowUpper == null || colLower == null || colUpper == null)
			return null;
		return new IndexSlice(Math.max(0L, rowLower - 1), Math.max(0L, rowUpper),
			Math.max(0L, colLower - 1), Math.max(0L, colUpper));
	}

	private static Long literalLong(Hop hop) {
		if(!(hop instanceof LiteralOp))
			return null;
		try {
			return HopRewriteUtils.getIntValueSafe((LiteralOp)hop);
		}
		catch(Exception exception) {
			return null;
		}
	}

	private static boolean isPartitioned(FType type) {
		return type == FType.ROW || type == FType.COL || type == FType.PART;
	}

	private static double positive(double value) {
		return Double.isFinite(value) && value > 0.0 ? value : 0.0;
	}

	static double analysisAwareComputeFlops(PlacementAnalysis analysis, CompiledHopKey key, Hop hop) {
		return ComputeCost.getHOPComputeCost(hop,
			analysisAwareComputeDimension(analysis, key, hop, true),
			analysisAwareComputeDimension(analysis, key, hop, false),
			position -> analysisAwareComputeDimension(analysis,
				analysis.compiledInputEdge(key, position).map(PlacementAnalysis.CompiledInputEdgeFact::producer)
					.orElse(null), hop.getInput(position), true),
			position -> analysisAwareComputeDimension(analysis,
				analysis.compiledInputEdge(key, position).map(PlacementAnalysis.CompiledInputEdgeFact::producer)
					.orElse(null), hop.getInput(position), false));
	}

	private static long analysisAwareComputeDimension(PlacementAnalysis analysis,
			CompiledHopKey key, Hop hop, boolean rows) {
		long ordinary = rows ? hop.getDim1() : hop.getDim2();
		if(ordinary > 0 || key == null || !hop.getDataType().isMatrix())
			return ordinary;
		AbstractShapeFact shape = analysis.abstractShapeFact(key).orElse(null);
		if(shape == null) return ordinary;
		var dimension = rows ? shape.rows() : shape.cols();
		return dimension.knowledge() == DimensionKnowledge.EXACT && dimension.value() > 0
			? dimension.value() : ordinary;
	}


	private static double analysisAwareOutputBytes(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		if(hop.getDataType() == null || !hop.getDataType().isMatrix()
			|| (hop.dimsKnown() && hop.getDim1() > 0 && hop.getDim2() > 0))
			return Double.NaN;
		double semanticSparse = FederatedCostModel.getSemanticSparseAssignmentMemEstimate(hop);
		if(semanticSparse <= 0.0 && sparseAssignments != null)
			semanticSparse = sparseAssignments.memEstimate(key);
		if(semanticSparse > 0.0)
			return semanticSparse;
		AbstractShapeFact shape = analysis.abstractShapeFact(key).orElse(null);
		if(shape == null || shape.dataType() == null || !shape.dataType().isMatrix()
			|| shape.rows().knowledge() != DimensionKnowledge.EXACT
			|| shape.cols().knowledge() != DimensionKnowledge.EXACT
			|| shape.rows().value() <= 0 || shape.cols().value() <= 0)
			return boundedDenseOutputBytes(analysis, key);
		long rows = shape.rows().value();
		long cols = shape.cols().value();
		if(hop.getNnz() >= 0) {
			double sparsity = Math.min(1.0, hop.getNnz() / (double)rows / (double)cols);
			return OptimizerUtils.estimateSizeExactSparsity(rows, cols, sparsity,
				org.apache.sysds.common.Types.DataType.MATRIX);
		}
		return denseMatrixBytes(rows, cols);
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
		return analysisAwareMixedFedLocalCost(analysis, key, inputHops, inputFTypes,
			logicalFType, baseSelfCost, outputMemEstimate, workers, null);
	}

	public static FederatedCostModel.MixedFedLocalCost analysisAwareMixedFedLocalCost(
			PlacementAnalysis analysis, CompiledHopKey key, List<Hop> inputHops,
			List<FType> inputFTypes, FType logicalFType, double baseSelfCost,
			double outputMemEstimate, int workers, FederatedExecutionLayout layout) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(key, "key");
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		List<Hop> exactInputs = inputHops == null ? new ArrayList<>(hop.getInput()) : inputHops;
		return FederatedCostModel.computeMixedFedLocalCost(hop, exactInputs,
			analysisAwareInputMemEstimates(analysis, key), inputFTypes, logicalFType, baseSelfCost,
			outputMemEstimate, workers, layout);
	}

	/** Deferred HOP sizes must use the same occurrence facts for all runtime stages. */
	private static List<Double> analysisAwareInputMemEstimates(PlacementAnalysis analysis, CompiledHopKey key) {
		Hop hop = analysis.hop(key).orElseThrow(() ->
			new IllegalArgumentException("Placement cost key has no owned Hop"));
		List<Double> inputMemEstimates = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop compiledInput = hop.getInput(position);
			double estimate = Double.NaN;
			if(compiledInput != null && compiledInput.getDataType() != null
				&& compiledInput.getDataType().isMatrix()
				&& (!compiledInput.dimsKnown() || compiledInput.getDim1() <= 0
					|| compiledInput.getDim2() <= 0)) {
				estimate = analysis.compiledInputEdge(key, position)
					.map(edge -> {
						double exact = analysisAwareDenseOutputBytes(analysis, edge.producer());
						return Double.isFinite(exact) ? exact : boundedDenseOutputBytes(analysis, edge.producer());
					})
					.orElse(Double.NaN);
			}
			inputMemEstimates.add(estimate);
		}
		return inputMemEstimates;
	}

	public static double analysisAwareAuxiliaryNetworkCost(PlacementAnalysis analysis, CompiledHopKey key,
		List<FType> inputFTypes, double outputBytes, int workers) {
		Hop hop = analysis.hop(key).orElseThrow();
		List<Long> rows = new ArrayList<>(hop.getInput().size());
		List<Long> cols = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			CompiledHopKey producer = analysis.compiledInputEdge(key, position)
				.map(PlacementAnalysis.CompiledInputEdgeFact::producer).orElse(null);
			rows.add(analysisAwareComputeDimension(analysis, producer, hop.getInput(position), true));
			cols.add(analysisAwareComputeDimension(analysis, producer, hop.getInput(position), false));
		}
		return FederatedCostModel.computeFederatedAuxiliaryNetworkCost(hop, hop.getInput(),
			analysisAwareInputMemEstimates(analysis, key), rows, cols, inputFTypes, outputBytes, workers);
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

	/** Dense in-memory cost envelope, without promoting upper bounds to exact shapes. */
	public static double boundedDenseOutputBytes(PlacementAnalysis analysis, CompiledHopKey key) {
		Hop hop = analysis.hop(key).orElse(null);
		if(hop == null || !hop.getDataType().isMatrix()) return Double.NaN;
		var bound = analysis.costSizeBound(key).orElse(null);
		if(bound == null || bound.rowsUpperBound() <= 0 || bound.colsUpperBound() <= 0)
			return Double.NaN;
		try {
			Math.multiplyExact(bound.rowsUpperBound(), bound.colsUpperBound());
			return denseMatrixBytes(bound.rowsUpperBound(), bound.colsUpperBound());
		}
		catch(ArithmeticException exception) { return Double.NaN; }
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
			Map<Hop,NodeShapeFact> sourceCompiledFactsByHop,
			List<PlacementAnalysis.CompiledInputEdgeFact> compiledInputEdges,
			List<NeutralPlacementGraph.Node> nodes, CompiledHopKey ownerKey) {
		Objects.requireNonNull(origins, "origins");
		Objects.requireNonNull(factsByHop, "factsByHop");
		Objects.requireNonNull(sourceCompiledFactsByHop, "sourceCompiledFactsByHop");
		Objects.requireNonNull(compiledInputEdges, "compiledInputEdges");
		Objects.requireNonNull(nodes, "nodes");
		Objects.requireNonNull(ownerKey, "ownerKey");
		// The exact matcher below rejects every non-transpose owner before reading
		// the node index. Avoid rebuilding that whole-program index for those owners.
		Hop owner = origins.get(ownerKey);
		if(!OptimizerUtils.ALLOW_OPERATOR_FUSION
			|| !(owner instanceof ReorgOp reorg) || reorg.getOp() != ReOrgOp.TRANS
			|| owner.getInput() == null || owner.getInput().size() != 1)
			return null;
		Hop input = owner.getInput().get(0);
		if(!(input instanceof AggBinaryOp inner) || !inner.isMatrixMultiply()
			|| inner.getParent() == null || inner.getParent().size() != 1
			|| inner.getParent().get(0) != owner)
			return null;
		Map<CompiledHopKey,NeutralPlacementGraph.Node> nodesByKey = new java.util.IdentityHashMap<>();
		for(NeutralPlacementGraph.Node node : nodes)
			nodesByKey.put(node.key(), node);
		ExactPlacementFacts facts = new ExactPlacementFacts() {
			@Override public Hop hop(CompiledHopKey key) { return origins.get(key); }
			@Override public NodeShapeFact shape(CompiledHopKey key) {
				Hop hop = origins.get(key);
				return hop == null ? null : factsByHop.get(hop);
			}
			@Override public NodeShapeFact sourceShape(CompiledHopKey key) {
				Hop hop = origins.get(key);
				return hop == null ? null : sourceCompiledFactsByHop.get(hop);
			}
			@Override public List<PlacementAnalysis.CompiledInputEdgeFact> edges() {
				return compiledInputEdges;
			}
			@Override public List<PlacementState> legalAlternatives(CompiledHopKey key) {
				NeutralPlacementGraph.Node node = nodesByKey.get(key);
				return node == null ? List.of() : node.legalAlternatives();
			}
		};
		return latentWdivmmTransposePair(facts, ownerKey, owner);
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
		if(pair != null && pair.partitionedInputFType() != null)
			return FederatedCostModel.computeWdivmmLoutResultCost(
				1, pair.partitionedInputFType(), outputMemEstimate, workers);
		DirectWdivmmRuntimeFact direct = directWdivmmRuntimeFact(analysis, key);
		if(direct != null && direct.runtimeInputFType() != null)
			return FederatedCostModel.computeWdivmmLoutResultCost(
				2, direct.runtimeInputFType(), outputMemEstimate, workers);
		return genericResultDownloadCost;
	}

	/** One reusable worker-to-coordinator materialization of a latent WDivMM input. */
	public static double latentWdivmmCpRuntimeInputMaterializationCost(double bytes,
		PlacementState ownerState, PlacementState sourceState, int workers) {
		Objects.requireNonNull(ownerState, "ownerState");
		Objects.requireNonNull(sourceState, "sourceState");
		if(ownerState.execType() != ExecType.CP
			|| sourceState.output() != FederatedOutput.FOUT)
			return 0.0;
		FType fType = sourceState.fType();
		if(fType == null)
			throw new IllegalArgumentException(
				"LATENT_WDIVMM_CP_RUNTIME_INPUT_LAYOUT_UNPROVEN");
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			throw new IllegalArgumentException(
				"LATENT_WDIVMM_CP_RUNTIME_INPUT_BYTES_UNPROVEN");
		return FederatedCostModel.computeReusableMaterializationDownloadCost(
			bytes, fType, workers);
	}

	/**
	 * Whether a source-level transfer belongs to the subtree replaced by one latent
	 * transpose-pair WDivMM runtime instruction.  The weights-to-weighted edge is
	 * replaced by the real weights-to-owner runtime input; weighted-to-inner and
	 * inner-to-owner are removed intermediates.  Physical costing must therefore let
	 * one explicit runtime-input factor own these transfers.
	 */
	public static List<LatentWdivmmRuntimeTransferBoundary>
			latentWdivmmRuntimeTransferBoundaries(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		Set<LatentWdivmmRuntimeTransferBoundary> boundaries = new java.util.TreeSet<>();
		for(var occurrence : analysis.compiledHopOccurrences()) {
			CompiledHopKey owner = occurrence.key();
			var pair = latentWdivmmTransposePairFact(analysis, owner);
			var direct = pair == null ? directWdivmmRuntimeFact(analysis, owner) : null;
			if(pair == null && direct == null)
				continue;
			CompiledHopKey root = pair == null ? owner : pair.inner();
			CompiledHopKey weightedKey = pair == null ? direct.weighted() : pair.weighted();
			CompiledHopKey weightsKey = pair == null ? direct.weights() : pair.weights();
			if(pair != null) {
				boundaries.add(new LatentWdivmmRuntimeTransferBoundary(root, owner, 0));
				analysis.compiledInputEdge(root, 0).ifPresent(edge -> boundaries.add(
					new LatentWdivmmRuntimeTransferBoundary(edge.producer(), root, 0)));
			}
			else
				analysis.compiledInputEdge(root, 1).ifPresent(edge -> boundaries.add(
					new LatentWdivmmRuntimeTransferBoundary(edge.producer(), root, 1)));
			boundaries.add(new LatentWdivmmRuntimeTransferBoundary(weightedKey, root, pair == null ? 0 : 1));
			Hop weighted = analysis.hop(weightedKey).orElseThrow();
			if(weighted.getParent().size() == 1
				&& weighted.getParent().get(0) == analysis.hop(root).orElseThrow()) {
				boundaries.add(new LatentWdivmmRuntimeTransferBoundary(weightsKey, weightedKey, 0));
				analysis.compiledInputEdge(weightedKey, 1).ifPresent(edge -> boundaries.add(
					new LatentWdivmmRuntimeTransferBoundary(edge.producer(), weightedKey, 1)));
			}
		}
		Set<CompiledHopKey> removed = wdivmmRuntimeRemovedIntermediates(analysis);
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
			if(removed.contains(edge.consumer()))
				boundaries.add(new LatentWdivmmRuntimeTransferBoundary(
					edge.producer(), edge.consumer(), edge.inputPosition()));

		return List.copyOf(boundaries);
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
				analysis.shapeFact(consumer).orElse(null),
				analysis.sourceCompiledShapeFact(consumer).orElse(null)));
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
		NodeShapeFact weights = provenLatentShape(weighted.weights());
		if(rootEdge.inputPosition() == 0
			&& latentLeftWeightedWdivmmFlops(exactPlacementFacts(analysis),
				rootEdge.consumer(), left, right) >= 0.0) {
			long rank = provenLatentShape(right).cols();
			return denseMatrixBytes(weights.rows(), rank);
		}
		if(rootEdge.inputPosition() == 1
			&& latentRightWeightedWdivmmFlops(exactPlacementFacts(analysis),
				rootEdge.consumer(), left, right) >= 0.0) {
			long rank = provenLatentShape(left).rows();
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
		NodeShapeFact output = concreteCostShape(analysis.shapeFact(consumer).orElse(null),
			analysis.sourceCompiledShapeFact(consumer).orElse(null));
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

	/**
	 * Resolves a concrete shape for cost estimation only.  The source-compiled snapshot
	 * may fill axes lost by conservative abstract propagation, but it cannot contradict
	 * any known conservative axis.  This result is not placement or legality authority.
	 */
	static NodeShapeFact concreteCostShape(NodeShapeFact conservative, NodeShapeFact source) {
		if(conservative == null || conservative.dataType() == null
			|| !conservative.dataType().isMatrix() || source == null
			|| source.dataType() != conservative.dataType()
			|| conservative.rows() >= 0 && source.rows() >= 0
				&& conservative.rows() != source.rows()
			|| conservative.cols() >= 0 && source.cols() >= 0
				&& conservative.cols() != source.cols())
			return null;
		if(conservative.knownPositiveMatrix())
			return conservative;
		NodeShapeFact compatibleSource = provenLatentShape(conservative, source);
		return compatibleSource != null && compatibleSource.knownPositiveMatrix()
			? compatibleSource : null;
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
		double runtimeFlops = latentLeftWeightedWdivmmFlops(facts, rootKey, weighted, right);
		if(runtimeFlops < 0.0)
			return null;
		WeightedOuter structure = weightedOuter(facts, weighted);
		if(structure == null || structure.outer().hop().getParent() == null
			|| structure.outer().hop().getParent().size() != 1
			|| structure.outer().hop().getParent().get(0) != weighted.hop())
			return null;
		FType runtimeInputFType = uniquePartitionedFoutType(facts, structure.weights().key());
		return new DirectWdivmmRuntimeFact(rootKey, weighted.key(), structure.outer().key(),
			structure.weights().key(), runtimeFlops, runtimeInputFType,
			runtimeInputFType == FType.COL);
	}

	private record LocalMMChainKernel(List<CompiledHopKey> runtimeInputs,
		List<CompiledHopKey> computeNodes, List<CompiledHopKey> fusedIntermediates) {
		private LocalMMChainKernel {
			runtimeInputs = List.copyOf(runtimeInputs);
			computeNodes = List.copyOf(computeNodes);
			fusedIntermediates = List.copyOf(fusedIntermediates);
		}
	}

	private record RuntimeOwnership(Set<CompiledHopKey> removed,
		Map<CompiledHopKey,LocalMMChainKernel> mmChains) { }

	private static RuntimeOwnership runtimeOwnership(PlacementAnalysis analysis) {
		Set<CompiledHopKey> removed = wdivmmRuntimeRemovedIntermediates(analysis);
		Map<CompiledHopKey,LocalMMChainKernel> mmChains = new IdentityHashMap<>();
		for(var node : analysis.graph().nodes()) {
			LocalMMChainKernel kernel = localMMChainKernel(analysis, node.key());
			if(kernel == null)
				continue;
			mmChains.put(node.key(), kernel);
			removed.addAll(exclusivelyFusedMMChainIntermediates(analysis, node.key(), kernel));
		}
		return new RuntimeOwnership(removed, mmChains);
	}

	/**
	 * A local MMChain is assignment invariant only when every participating planner node
	 * is CP/LOUT and the runtime inputs have no federated source.  This deliberately
	 * excludes FED, direct-FOUT, relocation and local-materialization plans: those plans
	 * retain explicit Lop boundaries and therefore retain their source-kernel costs.
	 */
	private static LocalMMChainKernel localMMChainKernel(PlacementAnalysis analysis,
		CompiledHopKey ownerKey) {
		Hop hop = analysis.hop(ownerKey).orElse(null);
		if(!OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES || !(hop instanceof AggBinaryOp owner)
			|| !owner.isMatrixMultiply() || owner.getInput().size() != 2
			|| owner.getInput(1).getDim2() != 1)
			return null;
		ChainType runtimeChain = owner.checkMapMultChain();
		if(runtimeChain == ChainType.NONE)
			return null;
		ExactInput transpose = findExactInput(analysis, ownerKey, 0);
		ExactInput right = findExactInput(analysis, ownerKey, 1);
		if(transpose == null || right == null || !(transpose.hop() instanceof ReorgOp reorg)
			|| reorg.getOp() != ReOrgOp.TRANS)
			return null;
		ExactInput x = findExactInput(analysis, transpose.key(), 0);
		if(x == null)
			return null;

		ChainType chain;
		ExactInput inner;
		ExactInput extra = null;
		List<CompiledHopKey> fused = new ArrayList<>();
		fused.add(transpose.key());
		if(right.hop() instanceof AggBinaryOp) {
			chain = ChainType.XtXv;
			inner = right;
		}
		else if(right.hop() instanceof BinaryOp binary && binary.getOp() == OpOp2.MULT) {
			chain = ChainType.XtwXv;
			extra = findExactInput(analysis, right.key(), 0);
			inner = findExactInput(analysis, right.key(), 1);
			fused.add(right.key());
		}
		else if(right.hop() instanceof BinaryOp binary && binary.getOp() == OpOp2.MINUS) {
			chain = ChainType.XtXvy;
			inner = findExactInput(analysis, right.key(), 0);
			extra = findExactInput(analysis, right.key(), 1);
			fused.add(right.key());
		}
		else
			return null;
		if(chain != runtimeChain || inner == null || chain != ChainType.XtXv && extra == null
			|| !(inner.hop() instanceof AggBinaryOp innerMM)
			|| !innerMM.isMatrixMultiply())
			return null;
		ExactInput innerX = findExactInput(analysis, inner.key(), 0);
		ExactInput v = findExactInput(analysis, inner.key(), 1);
		if(innerX == null || v == null || innerX.hop() != x.hop())
			return null;
		fused.add(inner.key());

		List<CompiledHopKey> runtimeInputs = new ArrayList<>(List.of(x.key(), x.key(), v.key()));
		if(extra != null)
			runtimeInputs.add(extra.key());
		List<CompiledHopKey> participants = new ArrayList<>(fused);
		participants.add(ownerKey);
		if(participants.stream().anyMatch(key -> !onlyLocalLout(analysis, key))
			|| runtimeInputs.stream().anyMatch(key -> !onlyLocalLout(analysis, key))
			|| containsFederatedSource(owner))
			return null;
		List<CompiledHopKey> computeNodes = new ArrayList<>();
		computeNodes.add(inner.key());
		if(chain != ChainType.XtXv)
			computeNodes.add(right.key());
		computeNodes.add(ownerKey);
		return new LocalMMChainKernel(runtimeInputs, computeNodes, fused);
	}

	private static boolean onlyLocalLout(PlacementAnalysis analysis, CompiledHopKey key) {
		var node = analysis.graph().node(key).orElse(null);
		return node != null && !node.legalAlternatives().isEmpty()
			&& node.legalAlternatives().stream().allMatch(state -> state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.LOUT);
	}

	private static boolean containsFederatedSource(Hop root) {
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Hop> pending = new ArrayList<>(List.of(root));
		for(int index = 0; index < pending.size(); index++) {
			Hop current = pending.get(index);
			if(!visited.add(current))
				continue;
			if(current instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				return true;
			pending.addAll(current.getInput());
		}
		return false;
	}

	private static Set<CompiledHopKey> exclusivelyFusedMMChainIntermediates(
		PlacementAnalysis analysis, CompiledHopKey owner, LocalMMChainKernel kernel) {
		Set<CompiledHopKey> fused = Collections.newSetFromMap(new IdentityHashMap<>());
		fused.addAll(kernel.fusedIntermediates());
		Map<CompiledHopKey,List<CompiledHopKey>> consumers = new IdentityHashMap<>();
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
			consumers.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge.consumer());
		Set<CompiledHopKey> retained = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey intermediate : fused)
			if(consumers.getOrDefault(intermediate, List.of()).stream()
				.anyMatch(consumer -> consumer != owner && !fused.contains(consumer)))
				retained.add(intermediate);
		List<CompiledHopKey> pending = new ArrayList<>(retained);
		for(int index = 0; index < pending.size(); index++) {
			CompiledHopKey retainedNode = pending.get(index);
			Hop retainedHop = analysis.hop(retainedNode).orElseThrow();
			for(int position = 0; position < retainedHop.getInput().size(); position++) {
				CompiledHopKey input = analysis.compiledInputEdge(retainedNode, position)
					.map(PlacementAnalysis.CompiledInputEdgeFact::producer).orElse(null);
				if(input != null && fused.contains(input) && retained.add(input))
					pending.add(input);
			}
		}
		fused.removeAll(retained);
		return fused;
	}

	private static Set<CompiledHopKey> wdivmmRuntimeRemovedIntermediates(PlacementAnalysis analysis) {
		Set<CompiledHopKey> removed = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<CompiledHopKey,List<CompiledHopKey>> consumers = new IdentityHashMap<>();
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
			consumers.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge.consumer());
		for(var node : analysis.graph().nodes()) {
			CompiledHopKey owner = node.key();
			var latent = latentWdivmmTransposePairFact(analysis, owner);
			var direct = latent == null ? directWdivmmRuntimeFact(analysis, owner) : null;
			CompiledHopKey weightedKey = latent != null ? latent.weighted()
				: direct != null ? direct.weighted() : null;
			if(weightedKey == null)
				continue;
			Hop weightedHop = analysis.hop(weightedKey).orElseThrow();
			WeightedOuter weighted = weightedOuter(analysis, new ExactInput(weightedKey, weightedHop,
				analysis.shapeFact(weightedKey).orElse(null), analysis.sourceCompiledShapeFact(weightedKey).orElse(null)));
			if(weighted == null)
				continue;
			Set<CompiledHopKey> retained = Collections.newSetFromMap(new IdentityHashMap<>());
			retained.add(weighted.weights().key());
			retained.add(weighted.outerLeft().key());
			ExactInput v = direct != null ? findExactInput(analysis, owner, 1) : weighted.outerRight();
			if(direct == null && v.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS) {
				ExactInput original = findExactInput(analysis, v.key(), 0);
				if(original != null) v = original;
			}
			retained.add(v.key());
			List<CompiledHopKey> pending = new ArrayList<>();
			pending.add(owner);
			for(int index = 0; index < pending.size(); index++) {
				CompiledHopKey parent = pending.get(index);
				Hop parentHop = analysis.hop(parent).orElseThrow();
				for(int position = 0; position < parentHop.getInput().size(); position++) {
					var edge = analysis.compiledInputEdge(parent, position).orElse(null);
					if(edge == null || retained.contains(edge.producer()) || removed.contains(edge.producer()))
						continue;
					CompiledHopKey child = edge.producer();
					if(consumers.getOrDefault(child, List.of()).stream()
						.allMatch(consumer -> consumer == owner || removed.contains(consumer))) {
						removed.add(child);
						pending.add(child);
					}
				}
			}
		}
		return removed;
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
		double runtimeFlops = latentRightWeightedWdivmmFlops(facts, inner.key(), left, right);
		if(runtimeFlops < 0.0)
			return null;
		WeightedOuter weighted = weightedOuter(facts, right);
		FType partitionedInputFType = weighted == null ? null
			: uniquePartitionedFoutType(facts, weighted.weights().key());
		// Pattern 1 lowers to a LEFT WDivMM. ROW-partitioned X yields overlapping
		// worker partials, so QuaternaryWDivMMFEDInstruction always aggregates them
		// locally even when an FOUT flag is serialized. Publishing native FOUT here
		// would therefore be a planner/runtime contract violation.
		boolean nativeOutputMustBeLocal = partitionedInputFType == FType.ROW;
		return new LatentWdivmmTransposePairFact(inner.key(), right.key(),
			weighted.weights().key(),
			runtimeFlops, partitionedInputFType, nativeOutputMustBeLocal);
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


	/** Returns -1 when the pattern does not match; zero FLOPs is a valid empty kernel. Pattern 1: {@code t(U) %*% (W op (U %*% t(V)))}. */
	private static double latentRightWeightedWdivmmFlops(ExactPlacementFacts facts,
			CompiledHopKey rootKey, ExactInput left, ExactInput weightedInput) {
		WeightedOuter weighted = weightedOuter(facts, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			left.hop(), weighted.outerLeft().hop()))
			return -1.0;
		NodeShapeFact weights = provenLatentShape(weighted.weights());
		NodeShapeFact weightedShape = provenLatentShape(weightedInput);
		NodeShapeFact root = provenLatentShape(facts.shape(rootKey), facts.sourceShape(rootKey));
		NodeShapeFact transposeU = provenLatentShape(left);
		NodeShapeFact u = provenLatentShape(weighted.outerLeft());
		NodeShapeFact transposedV = provenLatentShape(weighted.outerRight());
		if(!knownMatrix(weights))
			return -1.0;
		long rank = transposeU == null ? -1 : transposeU.rows();
		if(rank <= 1 || !matchesMatrix(weightedShape, weights.rows(), weights.cols())
			|| !matchesMatrix(root, rank, weights.cols())
			|| !matchesMatrix(transposeU, rank, weights.rows())
			|| !matchesMatrix(u, weights.rows(), rank)
			|| !matchesMatrix(transposedV, rank, weights.cols())
			|| weighted.outerLeft().hop().getBlocksize() <= 0
			|| rank > weighted.outerLeft().hop().getBlocksize())
			return -1.0;
		return ComputeCost.getWdivmmComputeCost(weights.rows(), weights.cols(),
			weighted.weights().hop().getNnz(), rank);
	}

	/** Returns -1 when the pattern does not match; zero FLOPs is a valid empty kernel. Pattern 2: {@code (W op (U %*% t(V))) %*% V}. */
	private static double latentLeftWeightedWdivmmFlops(ExactPlacementFacts facts,
			CompiledHopKey rootKey, ExactInput weightedInput, ExactInput right) {
		WeightedOuter weighted = weightedOuter(facts, weightedInput);
		if(weighted == null || !HopRewriteUtils.isTransposeOfItself(
			right.hop(), weighted.outerRight().hop()))
			return -1.0;
		NodeShapeFact weights = provenLatentShape(weighted.weights());
		NodeShapeFact weightedShape = provenLatentShape(weightedInput);
		NodeShapeFact root = provenLatentShape(facts.shape(rootKey), facts.sourceShape(rootKey));
		NodeShapeFact v = provenLatentShape(right);
		NodeShapeFact u = provenLatentShape(weighted.outerLeft());
		NodeShapeFact transposedV = provenLatentShape(weighted.outerRight());
		if(!knownMatrix(weights))
			return -1.0;
		long rank = v == null ? -1 : v.cols();
		if(rank <= 1 || !matchesMatrix(weightedShape, weights.rows(), weights.cols())
			|| !matchesMatrix(root, weights.rows(), rank)
			|| !matchesMatrix(v, weights.cols(), rank)
			|| !matchesMatrix(u, weights.rows(), rank)
			|| !matchesMatrix(transposedV, rank, weights.cols())
			|| weighted.outerLeft().hop().getBlocksize() <= 0
			|| rank > weighted.outerLeft().hop().getBlocksize())
			return -1.0;
		return ComputeCost.getWdivmmComputeCost(weights.rows(), weights.cols(),
			weighted.weights().hop().getNnz(), rank);
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
		NodeShapeFact sourceShape = facts.sourceShape(match.producer());
		if(shape == null || sourceShape == null)
			throw new IllegalArgumentException("Placement cost input has no shape fact");
		return new ExactInput(match.producer(), hop, shape, sourceShape);
	}

	private static ExactPlacementFacts exactPlacementFacts(PlacementAnalysis analysis) {
		return new ExactPlacementFacts() {
			@Override public Hop hop(CompiledHopKey key) { return analysis.hop(key).orElse(null); }
			@Override public NodeShapeFact shape(CompiledHopKey key) {
				return analysis.shapeFact(key).orElse(null);
			}
			@Override public NodeShapeFact sourceShape(CompiledHopKey key) {
				return analysis.sourceCompiledShapeFact(key).orElse(null);
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

	private static NodeShapeFact provenLatentShape(ExactInput input) {
		return input == null ? null : provenLatentShape(input.shape(), input.sourceShape());
	}

	static NodeShapeFact provenLatentShape(NodeShapeFact conservative, NodeShapeFact source) {
		if(source == null || !source.dataType().isMatrix() || conservative == null
			|| conservative.dataType() != source.dataType()
			|| conservative.rows() > 0 && conservative.rows() != source.rows()
			|| conservative.cols() > 0 && conservative.cols() != source.cols())
			return null;
		return source;
	}

	private static boolean matchesMatrix(NodeShapeFact shape, long rows, long cols) {
		return shape != null && shape.dataType().isMatrix()
			&& (shape.rows() <= 0 || shape.rows() == rows)
			&& (shape.cols() <= 0 || shape.cols() == cols);
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

	private record ExactInput(CompiledHopKey key, Hop hop, NodeShapeFact shape, NodeShapeFact sourceShape) { }
	private record WeightedOuter(ExactInput weights, ExactInput outer,
		ExactInput outerLeft, ExactInput outerRight) { }
	public record LatentWdivmmTransposePairFact(CompiledHopKey inner,
		CompiledHopKey weighted, CompiledHopKey weights, double computeFlops,
		FType partitionedInputFType, boolean nativeOutputMustBeLocal) { }
	public record LatentWdivmmRuntimeTransferBoundary(CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition)
		implements Comparable<LatentWdivmmRuntimeTransferBoundary> {
		public LatentWdivmmRuntimeTransferBoundary {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException(
					"LATENT_WDIVMM_RUNTIME_TRANSFER_POSITION_INVALID");
		}

		@Override
		public int compareTo(LatentWdivmmRuntimeTransferBoundary that) {
			int producerOrder = producer.compareTo(that.producer);
			if(producerOrder != 0)
				return producerOrder;
			int consumerOrder = consumer.compareTo(that.consumer);
			return consumerOrder != 0 ? consumerOrder
				: Integer.compare(inputPosition, that.inputPosition);
		}
	}
	public record DirectWdivmmRuntimeFact(CompiledHopKey root, CompiledHopKey weighted,
		CompiledHopKey outer, CompiledHopKey weights, double computeFlops,
		FType runtimeInputFType, boolean nativeOutputMustBeLocal) { }
	private interface ExactPlacementFacts {
		Hop hop(CompiledHopKey key);
		NodeShapeFact shape(CompiledHopKey key);
		NodeShapeFact sourceShape(CompiledHopKey key);
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

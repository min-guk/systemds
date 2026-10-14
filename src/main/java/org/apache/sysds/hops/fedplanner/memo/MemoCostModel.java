/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DnnOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.codegen.SpoofFusedOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.wink.json4j.JSONObject;

/**
 * Pure unit-cost adapter for immutable memo rules.
 *
 * <p>The adapter owns one native instruction execution, including temporary
 * local-input broadcasts and a native FED/LOCAL result fan-in. Reusable supply
 * movements are priced separately by {@link #movementCost}; callers activate a
 * shared movement once, independently of its consumer count.</p>
 */
public final class MemoCostModel {
	record MovementMeasurement(long bytesBits,boolean uploadEstimate) {
		double bytes() { return Double.longBitsToDouble(bytesBits); }
	}
	public enum DiagnosticCode {
		INVALID_WORKER_COUNT,
		INVALID_FREQUENCY,
		RULE_ARITY_MISMATCH,
		UNKNOWN_SIZE,
		UNSUPPORTED_OPERATOR_FAMILY,
		AMBIGUOUS_FED_EXECUTION_LAYOUT,
		ILLEGAL_MOVEMENT,
		NONFINITE_COST
	}

	/** A cost-domain rejection whose code is stable for planner diagnostics/tests. */
	public static final class CostException extends IllegalArgumentException {
		private static final long serialVersionUID = -7534454975152904414L;
		private final DiagnosticCode code;

		private CostException(DiagnosticCode code, String message) {
			super(code + ": " + message);
			this.code = code;
		}

		public DiagnosticCode code() {
			return code;
		}
	}

	private MemoCostModel() {
		// utility class
	}

	/** Cost of executing one selected native rule at the explicit occurrence frequency. */
	public static double nativeCost(Hop hop, RuleMemo.Rule rule, int workers, double frequency) {
		Objects.requireNonNull(hop, "hop");
		Objects.requireNonNull(rule, "rule");
		validateWorkersAndFrequency(workers, frequency);
		if(rule.inputs().size() != hop.getInput().size())
			throw failure(DiagnosticCode.RULE_ARITY_MISMATCH,
				"rule inputs=" + rule.inputs().size() + " hop inputs=" + hop.getInput().size());
		if(frequency == 0.0 || isCarrier(hop))
			return 0.0;
		if(hop instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
			return multiReturnBuiltinCost(function,rule,workers,frequency);
		if(!supportedKernel(hop, rule.exec()))
			throw failure(DiagnosticCode.UNSUPPORTED_OPERATOR_FAMILY,
				hop.getClass().getName() + " / " + rule.exec());
		ensureKernelSizesKnown(hop);

		double base = requireFiniteCost(FederatedCostModel.computeOpCostWithFallback(hop),
			"local primitive for " + hop.getClass().getSimpleName());
		if(rule.exec() == ExecType.CP)
			return scale(base, frequency, "CP native cost");
		if(appendUsesCoordinatorLocalPath(hop, rule, workers))
			return scale(coordinatorLocalAppendCost(hop, rule, workers, base), frequency,
				"FED coordinator-local append cost");

		FType executionType = executionType(hop, rule, workers);
		List<FType> inputTypes = runtimeInputTypes(rule, workers);
		boolean broadcastOnly = FederatedCostModel.hasOnlyBroadcastMatrixInputs(hop.getInput(), inputTypes);
		double compute = FederatedCostModel.computeFederatedComputeCost(
			hop, base, workers, broadcastOnly);
		compute = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(hop, executionType, compute);
		compute = FederatedCostModel.computeNativeFederatedIndexingCost(hop, executionType, compute);

		double outputBytes = rule.output() == MemoPartition.LOCAL
			? knownOutputBytes(hop, false) : effectiveOutputBytesIfKnown(hop);
		FederatedCostModel.MixedFedLocalCost mixed = FederatedCostModel.computeMixedFedLocalCost(
			hop, hop.getInput(), inputTypes, executionType, base, outputBytes, workers);
		double temporaryInputCost = mixed.getInputPreparationCost()
			+ implicitLocalInputBroadcastCost(hop, rule, inputTypes, workers);
		double unit = compute
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(hop, 1.0)
			+ temporaryInputCost;

		if(rule.output() == MemoPartition.LOCAL) {
			double resultBytes = knownOutputBytes(hop, true);
			double result = FederatedCostModel.computeNativeFederatedLoutResultCost(
				hop, inputTypes, executionType, resultBytes, workers);
			result = FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				hop, executionType, outputBytes, workers, result);
			result = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				hop, executionType, outputBytes, workers, result);
			unit += mixed.hasCoordinatorPhase() ? mixed.getCoordinatorPhaseCost() : result;
		}
		return scale(requireFiniteCost(unit, "FED native cost"), frequency, "FED frequency");
	}

	private static double multiReturnBuiltinCost(FunctionOp hop,RuleMemo.Rule rule,
		int workers,double frequency) {
		if(hop.getOutputs()==null || hop.getOutputs().isEmpty()
			|| rule.outputs().size()!=hop.getOutputs().size())
			throw failure(DiagnosticCode.RULE_ARITY_MISMATCH,
				"multi-return outputs="+(hop.getOutputs()==null ? 0 : hop.getOutputs().size())
					+" rule outputs="+rule.outputs().size());
		ensureKernelSizesKnown(hop);
		boolean transform=hop.getFunctionName().equalsIgnoreCase(
			org.apache.sysds.common.Opcodes.TRANSFORMENCODE.toString());
		if(transform) {
			if(!shapePreservingTransformEncode(hop))
				throw failure(DiagnosticCode.UNKNOWN_SIZE,
					"data-dependent transformencode output for "+describe(hop));
		}
		else for(Hop output : hop.getOutputs())
			if(output==null || isMatrixPayload(output) && (output.getDim1()<=0 || output.getDim2()<=0))
				throw failure(DiagnosticCode.UNKNOWN_SIZE,"unknown multi-return output for "+describe(hop));
		double base=requireFiniteCost(FederatedCostModel.computeOpCostWithFallback(hop),
			"multi-return primitive for "+hop.getFunctionName());
		if(rule.exec()==ExecType.CP) return scale(base,frequency,"CP multi-return native cost");
		if(!transform)
			throw failure(DiagnosticCode.UNSUPPORTED_OPERATOR_FAMILY,
				"no verified FED multi-return runtime for "+hop.getFunctionName());
		FType executionType=executionType(hop,rule,workers);
		List<FType> inputTypes=runtimeInputTypes(rule,workers);
		double compute=FederatedCostModel.computeFederatedComputeCost(hop,base,workers,false);
		double unit=compute+FederatedCostModel.computeFederatedInstructionNetworkCost(hop,1.0)
			+implicitLocalInputBroadcastCost(hop,rule,inputTypes,workers);
		for(int slot=0;slot<rule.outputs().size();slot++) {
			if(rule.outputs().get(slot)==MemoPartition.LOCAL) {
				// FED transformencode constructs its metadata frame at the coordinator;
				// it is not a federated output that must be downloaded.
				if(!(transform && slot==1))
					unit+=FederatedCostModel.computeReusableMaterializationDownloadCost(
						knownOutputBytes(hop.getOutputs().get(slot),false),executionType,workers);
			}
		}
		return scale(requireFiniteCost(unit,"FED multi-return native cost"),frequency,
			"FED multi-return frequency");
	}

	private static boolean shapePreservingTransformEncode(FunctionOp hop) {
		if(hop.getInput().size()!=2 || !(hop.getInput(1) instanceof LiteralOp literal)
			|| hop.getInput(0).getDim1()<=0 || hop.getInput(0).getDim2()<=0) return false;
		try {
			JSONObject spec=new JSONObject(literal.getStringValue());
			return spec.containsKey("recode") && !spec.containsKey("dummycode")
				&& !spec.containsKey("omit") && !spec.containsKey("impute")
				&& !spec.containsKey("bin") && !spec.containsKey("hash");
		}
		catch(Exception ignored) { return false; }
	}

	/**
	 * Cost of one selected persistent supply action. Equal layouts are DIRECT;
	 * LOCAL-to-FED is an explicit upload and FED-to-LOCAL is one reusable collect.
	 */
	public static double movementCost(Hop source, MemoPartition nativeOutput,
		MemoPartition target, int workers, double frequency) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(nativeOutput, "native output");
		Objects.requireNonNull(target, "target");
		validateWorkersAndFrequency(workers, frequency);
		MemoPartition nativeNormalized = nativeOutput.normalize(workers);
		MemoPartition targetNormalized = target.normalize(workers);
		if(frequency == 0.0 || nativeNormalized == targetNormalized)
			return 0.0;
		MovementMeasurement measurement=movementMeasurement(source,nativeOutput,target,workers);
		return movementCost(measurement,nativeOutput,target,workers,frequency);
	}

	static MovementMeasurement movementMeasurement(Hop source,MemoPartition nativeOutput,
		MemoPartition target,int workers) {
		Objects.requireNonNull(source,"source");
		Objects.requireNonNull(nativeOutput,"native output");
		Objects.requireNonNull(target,"target");
		validateWorkersAndFrequency(workers,1);
		MemoPartition nativeNormalized=nativeOutput.normalize(workers);
		if(source.getDataType() != DataType.MATRIX && source.getDataType() != DataType.FRAME)
			throw failure(DiagnosticCode.ILLEGAL_MOVEMENT,
				"persistent placement movement requires matrix/frame payload: " + source.getDataType());
		boolean upload=nativeNormalized==MemoPartition.LOCAL;
		double bytes=knownOutputBytes(source,upload);
		return new MovementMeasurement(Double.doubleToRawLongBits(bytes),upload);
	}

	static double movementCost(MovementMeasurement measurement,MemoPartition nativeOutput,
		MemoPartition target,int workers,double frequency) {
		Objects.requireNonNull(measurement,"movement measurement");
		Objects.requireNonNull(nativeOutput,"native output");
		Objects.requireNonNull(target,"target");
		validateWorkersAndFrequency(workers,frequency);
		MemoPartition nativeNormalized=nativeOutput.normalize(workers);
		MemoPartition targetNormalized=target.normalize(workers);
		if(frequency==0.0 || nativeNormalized==targetNormalized) return 0.0;
		double bytes=measurement.bytes();
		double unit;
		if(nativeNormalized == MemoPartition.LOCAL && targetNormalized.federated()) {
			if(!measurement.uploadEstimate()) throw measurementCategoryFailure(nativeNormalized,targetNormalized);
			unit = FederatedCostModel.computeUploadNetworkCost(
				bytes, targetNormalized.runtimeType(workers), workers);
		}
		else if(nativeNormalized.federated() && targetNormalized == MemoPartition.LOCAL) {
			if(measurement.uploadEstimate()) throw measurementCategoryFailure(nativeNormalized,targetNormalized);
			unit = FederatedCostModel.computeReusableMaterializationDownloadCost(
				bytes, nativeNormalized.runtimeType(workers), workers);
		}
		else
			throw failure(DiagnosticCode.ILLEGAL_MOVEMENT,
				"direct FED layout conversion is outside the no-RESHARD contract: "
					+ nativeNormalized + " -> " + targetNormalized);
		return scale(requireFiniteCost(unit, "movement cost"), frequency, "movement frequency");
	}

	private static CostException measurementCategoryFailure(MemoPartition nativeOutput,MemoPartition target) {
		return failure(DiagnosticCode.ILLEGAL_MOVEMENT,
			"movement payload category does not match " + nativeOutput + " -> " + target);
	}

	private static double implicitLocalInputBroadcastCost(Hop hop, RuleMemo.Rule rule,
		List<FType> inputTypes, int workers) {
		double cost = 0.0;
		for(int i = 0; i < rule.inputs().size(); i++) {
			Hop input = hop.getInput(i);
			if(rule.inputs().get(i) != MemoPartition.LOCAL
				|| MemoNativeRules.metadataInput(hop, i)
				|| FederatedCostModel.modelsNativeInputUpload(hop, inputTypes, i)
				|| input.getDataType() != DataType.MATRIX && input.getDataType() != DataType.FRAME)
				continue;
			double bytes = knownOutputBytes(input, true);
			cost += FederatedCostModel.computeInBandUploadPayloadCost(
				bytes, FType.BROADCAST, workers);
		}
		return requireFiniteCost(cost, "implicit local-input broadcast");
	}

	private static List<FType> runtimeInputTypes(RuleMemo.Rule rule, int workers) {
		List<FType> result = new ArrayList<>(rule.inputs().size());
		for(MemoPartition input : rule.inputs())
			result.add(input.runtimeType(workers));
		return result;
	}

	private static FType executionType(Hop hop, RuleMemo.Rule rule, int workers) {
		if(hop instanceof TernaryOp ternary && (ternary.getOp() == OpOp3.PLUS_MULT
			|| ternary.getOp() == OpOp3.MINUS_MULT || ternary.getOp() == OpOp3.IFELSE))
			for(MemoPartition input : rule.inputs())
				if(input.normalize(workers).federated())
					// TernaryFEDInstruction dispatches on the first federated matrix
					// operand and slices/broadcasts the remaining operands to that map.
					return input.normalize(workers).runtimeType(workers);
		if(hop instanceof BinaryOp && rule.inputs().size() >= 2) {
			MemoPartition left = rule.inputs().get(0).normalize(workers);
			MemoPartition right = rule.inputs().get(1).normalize(workers);
			// BinaryMatrixMatrixFEDInstruction dispatches on a partitioned RHS
			// first and sliced-broadcasts the LHS; a replicated RHS instead follows
			// the partitioned LHS map.
			if(right == MemoPartition.ROW || right == MemoPartition.COL)
				return right.runtimeType(workers);
			if(left == MemoPartition.ROW || left == MemoPartition.COL)
				return left.runtimeType(workers);
			if(right == MemoPartition.REP) return right.runtimeType(workers);
			if(left == MemoPartition.REP) return left.runtimeType(workers);
		}
		Set<MemoPartition> partitioned = new LinkedHashSet<>();
		boolean replicated = false;
		for(MemoPartition input : rule.inputs()) {
			MemoPartition normalized = input.normalize(workers);
			if(normalized == MemoPartition.ROW || normalized == MemoPartition.COL)
				partitioned.add(normalized);
			else if(normalized == MemoPartition.REP)
				replicated = true;
		}
		if(partitioned.size() == 1)
			return partitioned.iterator().next().runtimeType(workers);
		if(partitioned.size() > 1 && hop instanceof AggBinaryOp multiply && multiply.isMatrixMultiply()) {
			MemoPartition left = rule.inputs().get(0).normalize(workers);
			MemoPartition right = rule.inputs().get(1).normalize(workers);
			// MMFED cases A/B/D are anchored by the left ROW/COL operand; case C
			// reaches this branch only when the right ROW operand is the anchor.
			if(left == MemoPartition.ROW || left == MemoPartition.COL)
				return left.runtimeType(workers);
			if(right == MemoPartition.ROW)
				return right.runtimeType(workers);
		}
		if(partitioned.isEmpty() && replicated)
			return MemoPartition.REP.runtimeType(workers);
		if(partitioned.isEmpty() && rule.output().federated())
			return rule.output().runtimeType(workers);
		throw failure(DiagnosticCode.AMBIGUOUS_FED_EXECUTION_LAYOUT,
			"FED/LOCAL rule does not prove one execution layout for " + describe(hop)
				+ ": " + rule.inputs() + " variant=" + rule.variant());
	}

	private static boolean appendUsesCoordinatorLocalPath(Hop hop, RuleMemo.Rule rule, int workers) {
		if(!(hop instanceof BinaryOp binary) || rule.output() != MemoPartition.LOCAL)
			return false;
		boolean cbind = binary.getOp() == org.apache.sysds.common.Types.OpOp2.CBIND;
		boolean rbind = binary.getOp() == org.apache.sysds.common.Types.OpOp2.RBIND;
		if(!cbind && !rbind) return false;
		return rule.inputs().stream().map(input -> input.normalize(workers))
			.anyMatch(input -> cbind ? input == MemoPartition.COL : input == MemoPartition.ROW);
	}

	private static double coordinatorLocalAppendCost(Hop hop, RuleMemo.Rule rule, int workers,
		double localAppendCost) {
		double cost = localAppendCost;
		for(int position = 0; position < rule.inputs().size(); position++) {
			MemoPartition input = rule.inputs().get(position).normalize(workers);
			if(!input.federated()) continue;
			if(position > 0 && hop.getInput(position) == hop.getInput(0)
				&& input == rule.inputs().get(0).normalize(workers))
				continue; // AppendFEDInstruction reuses the first collected MatrixBlock.
			cost += FederatedCostModel.computeReusableMaterializationDownloadCost(
				knownOutputBytes(hop.getInput(position), false), input.runtimeType(workers), workers);
		}
		return requireFiniteCost(cost, "coordinator-local append");
	}

	private static double knownOutputBytes(Hop hop, boolean uploadEstimate) {
		if(hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME) {
			if(hop.getDim1() <= 0 || hop.getDim2() <= 0)
				throw failure(DiagnosticCode.UNKNOWN_SIZE,
					"unknown output dimensions for hop " + hop.getHopID() + ": "
						+ hop.getDim1() + "x" + hop.getDim2());
		}
		double bytes = uploadEstimate
			? FederatedCostModel.getEffectiveUploadMemEstimate(hop)
			: FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			throw failure(DiagnosticCode.UNKNOWN_SIZE,
				"no positive finite output-size estimate for hop " + hop.getHopID() + ": " + bytes);
		return bytes;
	}

	private static double effectiveOutputBytesIfKnown(Hop hop) {
		if((hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME)
			&& (hop.getDim1() <= 0 || hop.getDim2() <= 0))
			return 0.0;
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		return Double.isFinite(bytes) && bytes > 0.0 ? bytes : 0.0;
	}

	private static void ensureKernelSizesKnown(Hop hop) {
		if(isMatrixPayload(hop) && (hop.getDim1() <= 0 || hop.getDim2() <= 0))
			throw failure(DiagnosticCode.UNKNOWN_SIZE,
				"unknown kernel output dimensions for " + describe(hop));
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop input = hop.getInput(position);
			if(isMatrixPayload(input) && (input.getDim1() <= 0 || input.getDim2() <= 0))
				throw failure(DiagnosticCode.UNKNOWN_SIZE,
					"unknown kernel input " + position + " for " + describe(hop)
						+ ": " + describe(input));
		}
	}

	private static String describe(Hop hop) {
		return "hop " + hop.getHopID() + " " + hop.getClass().getSimpleName() + '/' + hop.getOpString()
			+ " " + hop.getDataType() + ' ' + hop.getDim1() + 'x' + hop.getDim2();
	}

	private static boolean isMatrixPayload(Hop hop) {
		return hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME;
	}

	private static boolean isCarrier(Hop hop) {
		return hop instanceof DataOp || hop instanceof LiteralOp
			|| hop instanceof FunctionOp function
				&& function.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN;
	}

	private static boolean supportedKernel(Hop hop, ExecType exec) {
		if(hop instanceof FunctionOp function)
			return function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				&& exec == ExecType.CP;
		if(hop instanceof DnnOp dnn)
			return exec == ExecType.CP && (dnn.getOp() == org.apache.sysds.common.Types.OpOpDnn.BIASADD
				|| dnn.getOp() == org.apache.sysds.common.Types.OpOpDnn.BIASMULT);
		if(hop instanceof DataGenOp)
			return exec == ExecType.CP;
		return hop instanceof AggBinaryOp || hop instanceof AggUnaryOp || hop instanceof BinaryOp
			|| hop instanceof IndexingOp || hop instanceof LeftIndexingOp || hop instanceof NaryOp
			|| hop instanceof ParameterizedBuiltinOp || hop instanceof QuaternaryOp
			|| hop instanceof ReorgOp || hop instanceof SpoofFusedOp || hop instanceof TernaryOp
			|| hop instanceof UnaryOp;
	}

	private static void validateWorkersAndFrequency(int workers, double frequency) {
		if(workers < 1)
			throw failure(DiagnosticCode.INVALID_WORKER_COUNT, "workers=" + workers);
		if(!Double.isFinite(frequency) || frequency < 0.0)
			throw failure(DiagnosticCode.INVALID_FREQUENCY, "frequency=" + frequency);
	}

	private static double scale(double unit, double frequency, String label) {
		return requireFiniteCost(unit * frequency, label);
	}

	private static double requireFiniteCost(double cost, String label) {
		if(!Double.isFinite(cost) || cost < 0.0)
			throw failure(DiagnosticCode.NONFINITE_COST, label + "=" + cost);
		return cost;
	}

	private static CostException failure(DiagnosticCode code, String message) {
		return new CostException(code, message);
	}
}

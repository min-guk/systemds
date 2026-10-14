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
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

/**
 * Planner-neutral runtime cost geometry and operator predicates.
 *
 * <p>These views deliberately carry no placement candidate, anchor, proof, or
 * closure identity. Legacy placement DTOs implement the views as adapters while
 * memo and future planners can use the neutral immutable records directly.</p>
 */
public final class FederatedCostSemantics {
	private FederatedCostSemantics() { }

	public interface RangeView {
		String workerId();
		List<Long> begin();
		List<Long> end();
	}

	public interface InputLayoutView {
		FType fType();
		boolean exactRanges();
		List<? extends RangeView> costRanges();
	}

	public interface ExecutionLayoutView {
		FType executionType();
		int workers();
		List<? extends InputLayoutView> inputs();
	}

	public interface WorkerResponseView {
		double totalBytes();
		double largestBytes();
		int responses();
	}

	public record CostRange(String workerId,List<Long> begin,List<Long> end) implements RangeView {
		public CostRange {
			begin=List.copyOf(begin);
			end=List.copyOf(end);
		}
	}

	public record CostInputLayout(FType fType,List<CostRange> costRanges,boolean exactRanges)
		implements InputLayoutView {
		public CostInputLayout {
			costRanges=costRanges==null ? List.of() : List.copyOf(costRanges);
			if(exactRanges && costRanges.isEmpty())
				throw new IllegalArgumentException("Exact input layout requires ranges");
		}
	}

	public record CostExecutionLayout(FType executionType,int workers,List<CostInputLayout> inputs)
		implements ExecutionLayoutView {
		public CostExecutionLayout {
			if(workers<=0) throw new IllegalArgumentException("Federated execution requires workers");
			inputs=inputs==null ? List.of() : List.copyOf(inputs);
		}
	}

	public record WorkerResponses(double totalBytes,double largestBytes,int responses)
		implements WorkerResponseView { }

	/** Mirrors QuaternaryWDivMMFEDInstruction / FederationMap.isAligned, not FType similarity. */
	public static boolean reusesWdivmmFactor(InputLayoutView weights,InputLayoutView factor,
		int position,boolean generatedTranspose) {
		if(generatedTranspose || weights==null || factor==null || factor.fType()==null
			|| !weights.exactRanges() || !factor.exactRanges()) return false;
		if(position==1 && (weights.fType()==FType.ROW || weights.fType()==FType.FULL))
			return factor.fType().isType(FType.ROW) && alignedAxis(weights,factor,0,0);
		if(position==2 && weights.fType()==FType.COL)
			return alignedAxis(weights,factor,1,1) || alignedAxis(weights,factor,1,0);
		if(position==3 && (weights.fType()==FType.ROW || weights.fType()==FType.COL
			|| weights.fType()==FType.FULL)) return alignedFull(weights,factor);
		return false;
	}

	/** Matches QuaternaryOp.checkWDivMMType. */
	public static boolean isWdivmmMatrixOperand(Hop hop,int position) {
		if(!(hop instanceof QuaternaryOp q) || q.getOp()!=OpOp4.WDIVMM
			|| position<=0 || position>=hop.getInput().size()
			|| !hop.getInput(position).getDataType().isMatrix()) return false;
		return position<=2 || position==3 && (q.getBaseType()==1 || q.getBaseType()==2);
	}

	public static boolean hasWdivmmEpsilon(Hop hop) {
		return hop instanceof QuaternaryOp q && q.getOp()==OpOp4.WDIVMM
			&& (q.getBaseType()==3 || q.getBaseType()==4) && hop.getInput().size()>3;
	}

	public static boolean wdivmmInputNeedsCollection(Hop hop,ExecutionLayoutView layout,int position) {
		if(position==3 && hasWdivmmEpsilon(hop)) return hop.getInput(position).getDataType().isMatrix();
		if(!isWdivmmMatrixOperand(hop,position)) return false;
		InputLayoutView weights=layout==null || layout.inputs().isEmpty() ? null : layout.inputs().get(0);
		InputLayoutView factor=layout==null || position>=layout.inputs().size() ? null : layout.inputs().get(position);
		return !reusesWdivmmFactor(weights,factor,position,false);
	}

	public static boolean isCtableMatrixInput(Hop hop,int position) {
		return hop instanceof TernaryOp ternary && ternary.getOp()==OpOp3.CTABLE
			&& !ternary.isSequenceRewriteApplicable(true) && position>=0 && position<3
			&& position<hop.getInput().size() && hop.getInput(position).getDataType().isMatrix();
	}

	public static boolean ctableInputNeedsCollection(Hop hop,ExecutionLayoutView layout,int position) {
		if(!isCtableMatrixInput(hop,position) || layout==null || layout.inputs().size()<2) return false;
		int primary=layout.inputs().get(0).fType()!=null ? 0 : layout.inputs().get(1).fType()!=null ? 1 : -1;
		if(primary<0) return false;
		if(position<2) return position!=primary;
		InputLayoutView anchor=layout.inputs().get(primary);
		InputLayoutView weights=layout.inputs().size()>2 ? layout.inputs().get(2) : null;
		return weights==null || weights.fType()==null || anchor.fType()==FType.BROADCAST
			|| !anchor.exactRanges() || !weights.exactRanges() || !alignedFull(anchor,weights);
	}

	private static boolean alignedFull(InputLayoutView anchor,InputLayoutView input) {
		return anchor.costRanges().stream().allMatch(a -> {
			var matches=input.costRanges().stream().filter(i ->
				a.begin().equals(i.begin()) && a.end().equals(i.end())).toList();
			return !matches.isEmpty() && matches.stream().allMatch(i -> a.workerId().equals(i.workerId()));
		});
	}

	private static boolean alignedAxis(InputLayoutView weights,InputLayoutView factor,int axis,int factorAxis) {
		for(RangeView w:weights.costRanges()) {
			boolean found=false;
			for(RangeView f:factor.costRanges()) {
				if(w.begin().size()<=axis || w.end().size()<=axis
					|| f.begin().size()<=factorAxis || f.end().size()<=factorAxis) return false;
				if(w.begin().get(axis).equals(f.begin().get(factorAxis))
					&& w.end().get(axis).equals(f.end().get(factorAxis))) {
					found=true;
					if(!w.workerId().equals(f.workerId())) return false;
				}
			}
			if(!found) return false;
		}
		return true;
	}

	public static double forwardingWeight(double networkWeight,List<Pair<Long,Double>> parentLoopContext,
		List<Pair<Long,Double>> childLoopContext,double consumerMultiplicity) {
		double base=networkWeight!=0.0 ? networkWeight : 1.0;
		if(parentLoopContext==null || parentLoopContext.isEmpty())
			return base*Math.max(consumerMultiplicity,0.0);
		Map<Long,Double> childLoops=new HashMap<>();
		if(childLoopContext!=null) for(Pair<Long,Double> loop:childLoopContext)
			childLoops.put(loop.getLeft(),loop.getRight());
		double weight=base;
		for(Pair<Long,Double> loop:parentLoopContext)
			if(!childLoops.containsKey(loop.getLeft()) && loop.getRight()>0.0) weight/=loop.getRight();
		return weight*Math.max(consumerMultiplicity,0.0);
	}

	public static boolean isMultiReturnFunctionOutput(Hop hop) {
		if(!(hop instanceof DataOp data) || data.getOp()!=OpOpData.FUNCTIONOUTPUT) return false;
		List<Hop> inputs=hop.getInput();
		if(inputs==null || inputs.isEmpty() || inputs.get(0)==null) return false;
		List<Hop> parents=inputs.get(0).getParent();
		if(parents==null || parents.isEmpty()) return false;
		for(Hop parent:parents)
			if(parent instanceof FunctionOp function
				&& function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				&& function.getOutputs()!=null && function.getOutputs().contains(hop)) return true;
		return false;
	}

	/** True only for the compiler's explicit branch-exit placement identity. */
	public static boolean isPlacementAlias(Hop hop) {
		return hop instanceof UnaryOp unary && unary.getOp()==OpOp1._PLACEMENT;
	}
}

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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolution;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class ExecPlacementPolicy {
	public record CapturedPlacementRequest(Hop hop, Privacy privacy, FType logicalFType,
		CandidateCapabilityFact capabilityFact, Map<Long, FType> effectiveFTypes,
		PlacementAnalysis analysis, String analysisFingerprint, CompiledHopKey parentOccurrence,
		List<CandidateInputState> orderedInputs, CandidateRuleFact exactFact,
		CapturedInvocationEvidence invocationEvidence, long variantOrdinal) {
		public CapturedPlacementRequest(Hop hop, Privacy privacy, FType logicalFType,
			CandidateCapabilityFact capabilityFact, Map<Long, FType> effectiveFTypes) {
			this(hop, privacy, logicalFType, capabilityFact, effectiveFTypes,
				null, null, null, List.of(), null, null, -1L);
		}

		public CapturedPlacementRequest {
			if(hop == null || privacy == null || capabilityFact == null)
				throw new IllegalArgumentException("Captured placement request must be complete");
			effectiveFTypes = Map.copyOf(Objects.requireNonNull(effectiveFTypes, "effectiveFTypes"));
			orderedInputs = List.copyOf(Objects.requireNonNull(orderedInputs, "orderedInputs"));
		}

		public boolean hasExactAuthority() {
			return analysis != null && analysisFingerprint != null && parentOccurrence != null
				&& exactFact != null && invocationEvidence != null && variantOrdinal >= 0;
		}
	}

	public static final class Decision {
		public boolean allowCP_LOUT;
		public boolean allowCP_FOUT;
		public boolean allowFED_LOUT;
		public boolean allowFED_FOUT;

		public boolean hasAny() {
			return allowCP_LOUT || allowCP_FOUT || allowFED_LOUT || allowFED_FOUT;
		}
	}

	private ExecPlacementPolicy() {
		// utility class
	}

	/**
	 * Some HOPs do not expose FED lops directly even though runtime can still execute them via
	 * CP/SP instructions that get recompiled into FED instructions at runtime. Keep the planner
	 * state as-is for costing, but normalize the forced exec type during rewrite so LOP
	 * construction stays valid.
	 */
	public static ExecType normalizeRewriteExecType(Hop hop, ExecType plannedExec) {
		if (plannedExec != ExecType.FED || hop == null)
			return plannedExec;
		if (hop instanceof QuaternaryOp)
			return ExecType.CP;
		return plannedExec;
	}

	public static Decision decide(Hop hop, Privacy privacy, FType fType, OpCaps caps) {
		ExecType oracleExec = (caps != null) ? caps.exec() : ExecType.CP;
		FederatedOutput placement = (caps != null) ? caps.placement() : FederatedOutput.LOUT;
		return decide(hop, privacy, fType, oracleExec, placement);
	}

	public static Decision decideCaptured(CapturedPlacementRequest request) {
		Objects.requireNonNull(request, "request");
		CapturedResolution exactResolution = requireExactAuthority(request);
		CandidateCapabilityFact capability = request.capabilityFact();
		Decision decision = decide(request.hop(), request.privacy(), request.logicalFType(),
			capability.nativeExec(), capability.nativeOutput());
		Hop hop = request.hop();
		FType exactProjectedType = PlacementCandidateRuleResolver.projectConsumerSafeType(
			exactResolution.logicalFType(), request.invocationEvidence().projection());
		boolean distinctCallContext = request.analysis().graph().constraints().stream()
			.anyMatch(constraint -> constraint.kind() == ConstraintKind.DISTINCT_CONTEXT
				&& (constraint.left() == request.parentOccurrence()
					|| constraint.right() == request.parentOccurrence()));
		if(capability.reasonCode() == ReasonCode.NO_FED_INPUT && hasExactFedLoutAlternative(request))
			decision.allowFED_LOUT = true;
		boolean derivedFedFout = !decision.allowFED_FOUT && decision.allowFED_LOUT
			&& hop.getDataType() != null && hop.getDataType().isMatrix()
			&& (request.privacy() == Privacy.PUBLIC
				|| request.privacy() == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)
			&& exactProjectedType != null && exactProjectedType != FType.PART
			&& exactProjectedType != FType.OTHER;
		if(derivedFedFout)
			decision.allowFED_FOUT = true;
		decision.allowCP_FOUT = decision.allowCP_FOUT
			&& exactProjectedType != null && exactProjectedType != FType.PART
			&& exactProjectedType != FType.OTHER;
		if(distinctCallContext) {
			decision.allowCP_FOUT = false;
			decision.allowFED_LOUT = false;
			decision.allowFED_FOUT = false;
			decision.allowCP_LOUT = true;
		}
		if(hop instanceof org.apache.sysds.hops.NaryOp) {
			Types.OpOpN op = ((org.apache.sysds.hops.NaryOp)hop).getOp();
			if(op == Types.OpOpN.CBIND || op == Types.OpOpN.RBIND) {
				decision.allowFED_LOUT = false;
				decision.allowFED_FOUT = false;
				decision.allowCP_FOUT = false;
			}
		}
		if(capability.reasonCode() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME
			&& hop instanceof org.apache.sysds.hops.ParameterizedBuiltinOp
			&& ((org.apache.sysds.hops.ParameterizedBuiltinOp)hop).getOp() == Types.ParamBuiltinOp.REXPAND) {
			decision.allowFED_LOUT = false;
			decision.allowFED_FOUT = false;
			decision.allowCP_LOUT = true;
		}
		if(isRecompileRegion(hop))
			decision.allowCP_FOUT = false;
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == Types.OpOpData.TRANSIENTREAD
			&& request.effectiveFTypes().isEmpty()) {
			decision.allowFED_LOUT = false;
			decision.allowFED_FOUT = false;
			decision.allowCP_FOUT = false;
			decision.allowCP_LOUT = true;
		}
		return decision;
	}

	private static CapturedResolution requireExactAuthority(CapturedPlacementRequest request) {
		if(!request.hasExactAuthority())
			throw new IllegalArgumentException("Captured placement request is not bound to exact analysis authority");
		PlacementAnalysis analysis = request.analysis();
		if(!analysis.analysisFingerprint().equals(request.analysisFingerprint())
			|| analysis.hop(request.parentOccurrence()).orElse(null) != request.hop())
			throw new IllegalArgumentException("Captured placement request belongs to a foreign analysis context");
		CandidateRuleFact exact = analysis.candidateRuleFacts()
			.requireExact(request.parentOccurrence(), request.orderedInputs());
		if(exact != request.exactFact() || exact.capability() != request.capabilityFact())
			throw new IllegalArgumentException("Captured placement request detached its exact rule fact");
		CapturedResolution resolved = PlacementCandidateRuleResolver.resolveCaptured(new CapturedResolutionRequest(
			analysis, request.analysisFingerprint(), request.parentOccurrence(), request.orderedInputs(),
			request.invocationEvidence()));
		if(resolved.fact() != exact || resolved.logicalFType() != request.logicalFType())
			throw new IllegalArgumentException("Captured placement request differs from exact retained resolution");
		return resolved;
	}

	private static boolean hasExactFedLoutAlternative(CapturedPlacementRequest request) {
		Hop hop = request.hop();
		Privacy privacy = request.privacy();
		if(hop == null || privacy == Privacy.PRIVATE)
			return false;
		if(hop instanceof DataOp && isTransientDataOp(hop))
			return false;
		CandidateRuleFact exact = request.exactFact();
		CandidateCapabilityFact capability = exact.capability();
		return exact.status() == CandidateEvaluationStatus.AVAILABLE
			&& capability != null && capability.reasonCode() == ReasonCode.NO_FED_INPUT
			&& capability.nativeExec() == ExecType.CP
			&& capability.nativeOutput() == FederatedOutput.LOUT
			&& request.logicalFType() == null
			&& !request.orderedInputs().isEmpty()
			&& request.orderedInputs().stream().noneMatch(CandidateInputState::present);
	}

	private static boolean isRecompileRegion(Hop hop) {
		if(hop.requiresRecompile()) return true;
		List<Hop> inputs = hop.getInput();
		if(inputs != null)
			for(Hop input : inputs)
				if(input != null && input.requiresRecompile()) return true;
		return false;
	}

	private static Decision decide(Hop hop, Privacy privacy, FType fType, ExecType oracleExec,
		FederatedOutput placement) {
		boolean dmlFunctionPlaceholder = isDmlFunctionPlaceholder(hop);

		Decision decision = new Decision();

		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			decision.allowFED_FOUT = true;
			return decision;
		}

		if (HopUtils.isPrintOrPWrite(hop)) {
			decision.allowCP_LOUT = true;
			return decision;
		}

		if (isMultiReturnBuiltinHop(hop) || isFunctionOutputFromMultiReturn(hop)) {
			// Multi-return builtins (e.g., eigen) have no runtime FED instruction, and their
			// FunctionOutput hops must stay local as well. Allowing CP->FOUT on outputs can
			// still push FED inputs into the builtin call and trigger invalid FED instructions.
			decision.allowCP_LOUT = true;
			return decision;
		}

		switch (privacy) {
			case PRIVATE:
				// FED/FOUT only (oracleExec == FED && placement == FOUT)
				if (oracleExec == ExecType.FED && placement == FederatedOutput.FOUT) {
					decision.allowFED_FOUT = true;
				}
				break;
			case PRIVATE_AGGREGATE:
				// PRIVATE_AGGREGATE results are still allowed to materialize locally on the
				// coordinator; only public release is disallowed. Keeping the CP/LOUT competitor
				// open is important for cost-based planners because some private-aggregate hops
				// (e.g., ALS masks, steplm rightIndex chains) can otherwise be forced into FED-only
				// regimes even though a legal local/private plan exists. This mirrors MinST's
				// alternative-cap merge, which already compares the local/private competitor on
				// the same workloads.
				//
				// Likewise, if a concrete FType is known and the hop can be materialized safely
				// onto an existing federated anchor, keep CP->FOUT open as a common competitor
				// for both DP and MinST. The planners' downstream safety checks still close the
				// candidate when no realizable anchor/materialization path exists, so this gate
				// should model "materializable" rather than a narrow subset of oracle reasons.
				decision.allowCP_LOUT = true;
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT)
						decision.allowFED_FOUT = true;
					else
						decision.allowFED_LOUT = true;
					if (supportsForcedLocalFederatedOutput(hop))
						decision.allowFED_LOUT = true;
				}
				if (allowCpFout(hop, fType))
					decision.allowCP_FOUT = true;
				// A DML FunctionOp is only a coordinator-side call placeholder; the concrete
				// execution happens inside the callee. Even if the oracle exposes a federated
				// placeholder path here, local execution of the call boundary remains legal
				// whenever privacy permits aggregate release.
				if (dmlFunctionPlaceholder)
					decision.allowCP_LOUT = true;
				if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
					decision.allowCP_LOUT = true;
				break;
			case PRIVATE_AGGREGATE_TO_PUBLIC:
				// Aggregate-to-public release must keep a local/public competitor available even when
				// the oracle exposes a FED path. DP enumerates parent transitions off this decision,
				// and closing CP/LOUT here can force hot aggregate/quaternary operators into FED-only
				// regimes (e.g., ALS wdivmm, steplm ba(+*)) although a legal local/public plan exists.
				decision.allowCP_LOUT = true;
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						decision.allowFED_FOUT = true;
					}
					decision.allowFED_LOUT = true;
				}
				if (dmlFunctionPlaceholder)
					decision.allowCP_LOUT = true;
				if (allowCpFout(hop, fType)) {
					decision.allowCP_FOUT = true;
				}
				break;
			case PUBLIC:
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						decision.allowFED_FOUT = true;
					}
					decision.allowFED_LOUT = true;
				}

				if (allowCpFout(hop, fType)) {
					decision.allowCP_FOUT = true;
				}
				decision.allowCP_LOUT = true;
				break;
			default:
				// Keep the decision empty for unsupported privacy levels.
				break;
		}

		if (hop instanceof DataOp) {
			Types.OpOpData op = ((DataOp) hop).getOp();
			if (op == Types.OpOpData.TRANSIENTREAD) {
				// TRANSIENTREAD placement is resolved via its corresponding TRANSIENTWRITE(s) in the
				// DP rewire table. Do not allow standalone CP->FOUT / FED->LOUT candidates here as they
				// can create inconsistent transient read/write pairings.
				decision.allowCP_FOUT = false;
				decision.allowFED_LOUT = false;
			}
			else if (op == Types.OpOpData.TRANSIENTWRITE) {
				// Do NOT close the candidate space for TRANSIENTWRITE:
				// - CP->FOUT is a valid, runtime-supported materialization (fed_fout/refed) used to
				//   persist a local transient as federated for downstream consumers (e.g., X_samples in kmeans).
				// - FED->LOUT does not represent a meaningful transient-write placement; downloads belong at
				//   parent boundaries, not at the write itself.
				decision.allowFED_LOUT = false;
			}
		}

		return decision;
	}

	private static boolean supportsForcedLocalFederatedOutput(Hop hop) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		if (hop instanceof IndexingOp)
			return true;
		if (hop instanceof ReorgOp && ((ReorgOp) hop).getOp() == Types.ReOrgOp.TRANS)
			return true;
		return hop instanceof BinaryOp;
	}

	private static boolean isMultiReturnBuiltinHop(Hop hop) {
		return hop instanceof FunctionOp
				&& ((FunctionOp) hop).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN;
	}

	private static boolean isDmlFunctionPlaceholder(Hop hop) {
		return hop instanceof FunctionOp
				&& ((FunctionOp) hop).getFunctionType() == FunctionType.DML;
	}

	private static boolean isFunctionOutputFromMultiReturn(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.FUNCTIONOUTPUT)
			return false;
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty() || inputs.get(0) == null)
			return false;
		List<Hop> parents = inputs.get(0).getParent();
		if (parents == null || parents.isEmpty())
			return false;
		for (Hop parent : parents) {
			if (parent instanceof FunctionOp
					&& ((FunctionOp) parent).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN)
				return true;
		}
		return false;
	}

	private static boolean isTransientDataOp(Hop hop) {
		if (!(hop instanceof DataOp)) {
			return false;
		}
		Types.OpOpData op = ((DataOp) hop).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	private static boolean allowCpFout(Hop hop, FType fType) {
		if (hop == null || !hop.getDataType().isMatrix()) {
			return false;
		}
		return fType != null && fType != FType.PART && fType != FType.OTHER;
	}
}

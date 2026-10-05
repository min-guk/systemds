/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.deriveNodeShapeFact;
import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.exactFederatedSourceFType;
import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.isFunctionOutput;
import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.isTransientRead;
import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.isTransientWrite;
import static org.apache.sysds.hops.fedplanner.placement.PlacementProgramFacts.nodeKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleNote;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.DecisionEvidence;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.PreparedProfile;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.ProfileInference;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Generates local placement alternatives from one exact input-domain snapshot. */
final class PlacementCandidateGenerator {
	private final OracleFacade oracle;
	private final SearchSpaceMetrics complexityMetrics;
	private final Map<Hop,OracleFacade.PreparedDecision> preparedDecisions = new java.util.IdentityHashMap<>();
	private final Map<Hop,PreparedProfile> preparedProfiles = new java.util.IdentityHashMap<>();

	PlacementCandidateGenerator(OracleFacade oracle, SearchSpaceMetrics complexityMetrics) {
		this.oracle = Objects.requireNonNull(oracle, "oracle");
		this.complexityMetrics = complexityMetrics;
	}

	/** Build/snapshot boundary: prepared signatures and exact queries never survive it. */
	void resetPreparedOracleState() {
		preparedProfiles.clear();
		preparedDecisions.clear();
	}

	private OracleFacade.PreparedDecision preparedDecision(Hop hop) {
		return preparedDecisions.computeIfAbsent(hop, oracle::prepareDecision);
	}

	private PreparedProfile preparedProfile(Hop hop) {
		return preparedProfiles.computeIfAbsent(hop,
			key -> preparedDecision(key).prepareProfile());
	}

	/** UNKNOWN is represented by no gate, never by a provisional PUBLIC seed. */
	record GenerationPrivacy(Privacy outputPrivacy, Set<Integer> protectedPayloadPositions) {
		GenerationPrivacy {
			Objects.requireNonNull(outputPrivacy, "outputPrivacy");
			protectedPayloadPositions = Set.copyOf(protectedPayloadPositions);
		}
	}

	private boolean allowsEmission(GenerationPrivacy privacy, Hop hop, OpCaps caps,
		List<FType> inputs, PlacementState state, boolean derived, FType executionType) {
		if(privacy == null)
			return true;
		boolean allowed = privacy.protectedPayloadPositions().isEmpty()
			|| state.execType() == ExecType.FED && privacy.protectedPayloadPositions().stream()
				.allMatch(position -> position < inputs.size() && inputs.get(position) != null);
		if(allowed) {
			FType logical = state.fType() != null ? state.fType() : executionType;
			allowed = ExecPlacementPolicy.allowsCandidateEmission(
				ExecPlacementPolicy.decide(hop, privacy.outputPrivacy(), logical, caps),
				state.execType(), state.output(), derived);
		}
		if(!allowed && complexityMetrics != null)
			complexityMetrics.recordPrivacyEmissionAllocationAvoided();
		return allowed;
	}

	Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value, List<DurableAnchorKey> anchors,
		List<DurableAnchorKey> inputAnchors, List<CompiledHopKey> inputAnchorOwners,
		NodeShapeFact shape, AbstractShapeFact abstractShape, SinglePartitionFacts singlePartitions,
		List<NodeShapeFact> inputShapeFacts, List<List<FType>> inputDomains,
		List<CandidateRuleKey> ruleKeys, List<CandidateRuleFact> ruleFacts) {
		return buildNode(hop, key, value, anchors, inputAnchors, inputAnchorOwners, shape,
			abstractShape, singlePartitions, inputShapeFacts, inputDomains, ruleKeys, ruleFacts, null);
	}

	Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value, List<DurableAnchorKey> anchors,
		List<DurableAnchorKey> inputAnchors, List<CompiledHopKey> inputAnchorOwners,
		NodeShapeFact shape, AbstractShapeFact abstractShape, SinglePartitionFacts singlePartitions,
		List<NodeShapeFact> inputShapeFacts, List<List<FType>> inputDomains,
		List<CandidateRuleKey> ruleKeys, List<CandidateRuleFact> ruleFacts, GenerationPrivacy privacy) {
		int candidateFactStart = ruleFacts.size();
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState,Exclusion> excluded = new java.util.TreeMap<>();
		PlacementState cp = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		// An empty domain is known bottom, not coordinator-local input. Propagate
		// that impossibility before candidate enumeration instead of fabricating a
		// CP/LOUT row with ABSENT_LOCAL. Downstream physical closure can then prove
		// the shrink against the exact empty input domain.
		if(inputDomains.stream().anyMatch(List::isEmpty)) {
			excluded.put(cp, new Exclusion(cp, ReasonCode.MISSING_ANCHOR,
				"known-bottom-input-domain"));
			return new Node(key, nodeKind(hop, value), value, false, List.of(),
				new ArrayList<>(excluded.values()), List.of());
		}
		legal.add(cp);
		if(key.recompileContext().equals("recompile")) {
			PlacementState forbidden = new PlacementState(ExecType.CP, FederatedOutput.FOUT, null, false);
			excluded.putIfAbsent(forbidden, new Exclusion(forbidden, ReasonCode.RECOMPILE_CP_FOUT,
				"recompile-context forbids CP/FOUT"));
		}
		boolean transientAccess = isTransientRead(hop) || isTransientWrite(hop);
		OracleFacade.PreparedDecision preparedOracle;
		PreparedProfile preparedProfile;
		try {
			preparedOracle = preparedDecision(hop);
			preparedProfile = preparedProfile(hop);
		}
		catch(RuntimeException e) {
			throw oracleRuntimeFailure("oracle preparation", key.normalizedSignature(), hop, List.of(), e);
		}
		forEachInputCombination(inputDomains, inputs -> {
			CandidateRuleKey candidateKey = new CandidateRuleKey(key, candidateInputStates(inputs));
			ruleKeys.add(candidateKey);
			Set<CandidateEmissionFact> exactEmissionFacts = new LinkedHashSet<>();
			OpCaps caps;
			DecisionEvidence evidence;
			boolean shapeDependent;
			try {
				if(complexityMetrics != null)
					complexityMetrics.recordCandidateOracleCall();
				evidence = preparedOracle.decideWithEvidence(inputs,
					exactShapeHint(hop, shape, inputShapeFacts,
						singlePartitions.fullInputHint(hop, inputAnchorOwners, inputs)));
				caps = evidence.caps();
				shapeDependent = evidence.shapeDependent();
			}
			catch(RuntimeException e) {
				throw oracleRuntimeFailure("oracle decision", key.normalizedSignature(), hop, inputs, e);
			}
			if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR)
				throw oracleReportedRuleError(key.normalizedSignature(), hop, inputs, caps);
			if(legal.contains(cp) && allowsEmission(privacy, hop, caps, inputs, cp, false, null))
				exactEmissionFacts.add(candidateEmissionFact(exactLegalState(legal, cp), false, null));
			ExactRightIndexRuntimeFact exactRightIndex = exactRightIndexRuntimeFact(
				hop, inputs, inputAnchors, caps);
			FType exactVectorLocalType = exactAggregateBinaryVectorLocalType(hop, abstractShape, inputs);
			FType outType = exactRightIndex == null
				? caps.foutFType().orElse(firstFType(inputs)) : exactRightIndex.outputFType();
			if(caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
				&& exactVectorLocalType != null)
				outType = exactVectorLocalType;
			boolean hasExactVectorLocalEmission = exactVectorLocalType != null;
			boolean exactShapeDependent = shapeDependent || exactRightIndex != null
				|| caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
					&& hasExactVectorLocalEmission;
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType, exactShapeDependent);
			String detail = "inputs=" + inputEvidence(inputs) + "|proof=" + evidence.shapeProof()
				+ '|' + caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				addGlobalExclusion(legal, excluded,
					new Exclusion(state, ReasonCode.RECOMPILE_CP_FOUT, detail));
			else if(transientAccess && !isLegalTransient(state))
				addGlobalExclusion(legal, excluded,
					new Exclusion(state, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, detail));
			else if(!evidence.shapeProof().missingRequiredFacts().isEmpty())
				addUnknownMetadataExclusionUnlessProvenLegal(legal, excluded, state, detail);
			else if(caps.exec() == ExecType.FED) {
				PlacementState exactNative = addLegalCandidate(legal, excluded, state);
				if(exactNative != null && allowsEmission(privacy, hop, caps, inputs, exactNative, false, outType))
					exactEmissionFacts.add(candidateEmissionFact(exactNative, false, outType));
				if(caps.placement() == FederatedOutput.FOUT
					&& ExecPlacementPolicy.supportsForcedLocalFederatedOutput(hop)
					&& !hasExactVectorLocalEmission) {
					PlacementState exactLout = addLegalCandidate(legal, excluded,
						new PlacementState(ExecType.FED, FederatedOutput.LOUT, outType, exactShapeDependent));
					if(exactLout != null && allowsEmission(privacy, hop, caps, inputs, exactLout, false, outType))
						exactEmissionFacts.add(candidateEmissionFact(exactLout, false, outType));
				}
				MaterializationAnchor materialization = exactCandidateMaterializationAnchor(
					anchors, inputAnchors, inputAnchorOwners, inputs);
				DurableAnchorKey materializationAnchor = materialization == null ? null : materialization.anchor();
				FType materializationFType = exactMaterializationFType(shape, materializationAnchor);
				if(materializationFType != null && !key.recompileContext().equals("recompile")
					&& !transientAccess && outType != null && outType != FType.PART && outType != FType.OTHER) {
					PlacementState cpFout = addLegalCandidate(legal, excluded,
						new PlacementState(ExecType.CP, FederatedOutput.FOUT, materializationFType,
							exactShapeDependent));
					if(cpFout != null && allowsEmission(privacy, hop, caps, inputs, cpFout, false, null)
						&& exactEmissionFacts.stream().anyMatch(emission ->
							emission.emissionState().placementState().equals(cp))) {
						DerivedFoutMaterializationActionKey action = derivedFoutAction(key, value, candidateKey,
							exactLegalState(legal, cp), cpFout, materializationAnchor,
							materialization.owner(), materialization.ownerFType(), materializationFType);
						exactEmissionFacts.add(candidateEmissionFact(cpFout, false, null, action));
					}
					if(caps.placement() == FederatedOutput.LOUT) {
						PlacementState derivedFout = addLegalCandidate(legal, excluded,
							new PlacementState(ExecType.FED, FederatedOutput.FOUT, materializationFType,
								exactShapeDependent));
						if(derivedFout != null && allowsEmission(privacy, hop, caps, inputs, derivedFout, true, outType)
							&& exactEmissionFacts.stream().anyMatch(emission ->
								emission.emissionState().placementState().equals(exactNative))) {
							DerivedFoutMaterializationActionKey action = derivedFoutAction(key, value, candidateKey,
								exactNative, derivedFout, materializationAnchor,
								materialization.owner(), materialization.ownerFType(), materializationFType);
							exactEmissionFacts.add(candidateEmissionFact(derivedFout, true, outType, action));
						}
					}
				}
				for(FType inputType : inputs)
					if(isAggregateBinaryVectorInput(hop, abstractShape, inputType)) {
						PlacementState supplemental = addLegalCandidate(legal, excluded,
							new PlacementState(ExecType.FED, FederatedOutput.LOUT, inputType, true));
						if(supplemental != null && allowsEmission(privacy, hop, caps, inputs, supplemental, false, inputType))
							exactEmissionFacts.add(candidateEmissionFact(supplemental, false, inputType));
					}
			}
			ruleFacts.add(candidateRuleFact(hop, candidateKey, inputShapeFacts, inputs, caps,
				evidence, exactRightIndex, exactEmissionFacts, privacy, preparedProfile));
		}, complexityMetrics);
		if(transientAccess)
			legal.removeIf(s -> !isLegalTransient(s));
		FType exactFederatedSourceType = exactFederatedSourceFType(hop, anchors);
		if(exactFederatedSourceType != null) {
			// Existing source availability is not relocation authority: a literal fed-init already has its exact
			// runtime FederationMap, while PART/OTHER remain closed for durable refed/FOUT/local materialization
			// anchors because the runtime lacks a stable worker/range relocation contract for them.
			PlacementState exactSource = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
				exactFederatedSourceType, false);
			if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED) {
				// A federated DataOp denotes an already materialized FederationMap. Local
				// consumption is a priced downstream FED->LOUT boundary, never CP execution
				// of the source itself.
				legal.removeIf(state -> !state.equals(exactSource));
				replaceFederatedSourceCandidateFacts(ruleFacts, candidateFactStart, exactSource, hop);
			}
			legal.add(exactSource);
		}
		if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED && anchors.isEmpty()
			&& exactFederatedSourceType == null) {
			PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.OTHER, true);
			addGlobalExclusion(legal, excluded, new Exclusion(state, ReasonCode.UNSUPPORTED_ANCHOR,
				"Federated source has no exact literal source FType; relocation anchor remains unavailable"));
		}
		return new Node(key, nodeKind(hop, value), value, true, new ArrayList<>(legal),
			new ArrayList<>(excluded.values()), anchors);
	}

	boolean oracleConfirmsAnchorDomain(Hop hop, String occurrence, List<List<FType>> domains,
		DurableAnchorKey anchor) {
		return oracleConfirmsAnchorDomain(hop, occurrence, domains, anchor, null);
	}

	boolean oracleConfirmsAnchorDomain(Hop hop, String occurrence, List<List<FType>> domains,
		DurableAnchorKey anchor, ShapeHint hint) {
		try {
			FTypeProfile profile = inferProfile("anchor profile", preparedProfile(hop), domains, hint);
			return profile != null && profile.outputs() != null && profile.outputs().contains(anchor.fType());
		}
		catch(RuntimeException e) {
			throw oracleRuntimeFailure("anchor profile", occurrence, hop, domains, e);
		}
	}

	void captureConsumerProfileFacts(Hop consumer, CompiledHopKey consumerKey,
		List<NodeShapeFact> inputShapeFacts, List<CandidateConsumerProfileKey> domainKeys,
		List<CandidateConsumerProfileFact> facts) {
		PreparedProfile preparedProfile = preparedProfile(consumer);
		for(int inputPosition = 0; inputPosition < inputShapeFacts.size(); inputPosition++) {
			CandidateConsumerProfileKey key = new CandidateConsumerProfileKey(consumerKey, inputPosition);
			domainKeys.add(key);
			ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(consumer,
				consumerKey.normalizedSignature(), inputShapeFacts, List.of(inputPosition), preparedProfile);
			facts.add(new CandidateConsumerProfileFact(key, evaluation.status(), evaluation.allowedTargetTypes(),
				evaluation.failureCode()));
		}
	}

	void captureDetachedConsumerProfileFacts(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, Set<Hop> ownedHops, Map<Hop,NodeShapeFact> shapeFactsByHop,
		List<DetachedConsumerProfileFact> facts) {
		for(int occurrenceOrdinal = 0; occurrenceOrdinal < occurrences.size(); occurrenceOrdinal++) {
			PlacementGraphFingerprint.HopOccurrence producerOccurrence = occurrences.get(occurrenceOrdinal);
			Hop producer = producerOccurrence.hop();
			CompiledHopKey producerKey = nodes.get(occurrenceOrdinal).key();
			List<Hop> parents = producer.getParent();
			for(int parentOrdinal = 0; parentOrdinal < parents.size(); parentOrdinal++) {
				Hop parent = parents.get(parentOrdinal);
				if(ownedHops.contains(parent) || isTransientRead(parent) || isTransientWrite(parent)
					|| isFunctionOutput(parent))
					continue;
				List<Integer> producerInputPositions = new ArrayList<>();
				List<NodeShapeFact> inputShapeFacts = new ArrayList<>(parent.getInput().size());
				for(int inputPosition = 0; inputPosition < parent.getInput().size(); inputPosition++) {
					Hop input = parent.getInput(inputPosition);
					if(input == producer)
						producerInputPositions.add(inputPosition);
					NodeShapeFact shapeFact = shapeFactsByHop.get(input);
					if(shapeFact == null) {
						shapeFact = deriveNodeShapeFact(input);
						shapeFactsByHop.put(input, shapeFact);
					}
					inputShapeFacts.add(shapeFact);
				}
				if(producerInputPositions.isEmpty())
					continue;
				DetachedConsumerProfileKey key = new DetachedConsumerProfileKey(producerKey, parentOrdinal,
					PlacementGraphFingerprint.semanticStructuralKey(parent), producerInputPositions);
				PreparedProfile preparedProfile = preparedProfile(parent);
				ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(parent, key.toString(), inputShapeFacts,
					producerInputPositions, preparedProfile);
				facts.add(new DetachedConsumerProfileFact(key, evaluation.status(), evaluation.allowedTargetTypes(),
					evaluation.failureCode()));
			}
		}
	}

	private ConsumerProfileEvaluation evaluateConsumerProfile(Hop consumer, String occurrence,
		List<NodeShapeFact> inputShapeFacts, List<Integer> targetPositions, PreparedProfile preparedProfile) {
		List<FType> allowed = new ArrayList<>();
		for(FType candidate : PlacementCandidateRuleResolver.matrixFTypeCandidates()) {
			List<List<FType>> inputLayouts =
				consumerProfileInputDomains(inputShapeFacts, targetPositions, candidate);
			try {
				FTypeProfile profile = inferProfile("consumer profile", preparedProfile, inputLayouts, null);
				if(profile != null && profile.outputs() != null && !profile.outputs().isEmpty())
					allowed.add(candidate);
			}
			catch(RuntimeException e) {
				throw oracleRuntimeFailure("consumer profile", occurrence, consumer, inputLayouts, e);
			}
		}
		return new ConsumerProfileEvaluation(CandidateEvaluationStatus.AVAILABLE, List.copyOf(allowed), "");
	}

	private CandidateRuleFact candidateRuleFact(Hop hop, CandidateRuleKey key,
		List<NodeShapeFact> inputShapeFacts, List<FType> inputs, OpCaps caps, DecisionEvidence evidence,
		ExactRightIndexRuntimeFact exactRightIndex, Set<CandidateEmissionFact> exactEmissionFacts,
		GenerationPrivacy privacy, PreparedProfile preparedProfile) {
		List<CandidateRuleNote> notes = caps.notes().stream()
			.map(note -> new CandidateRuleNote(note.code(), note.message())).toList();
		FType nativeFoutFType = exactRightIndex == null
			? caps.foutFType().orElse(null) : exactRightIndex.outputFType();
		CandidateCapabilityFact capability = new CandidateCapabilityFact(caps.category(), caps.opcode(), caps.exec(),
			caps.placement(), nativeFoutFType, caps.reason(), caps.detail().orElse(""), notes);
		var proof = evidence.shapeProof();
		Map<String,String> consultedFacts = new LinkedHashMap<>(proof.consultedFacts());
		List<String> requiredFacts = new ArrayList<>(proof.requiredFacts());
		if(exactRightIndex != null) {
			consultedFacts.put("rightIndex.literalBounds", exactRightIndex.literalBounds());
			consultedFacts.put("rightIndex.inputAnchor", exactRightIndex.inputAnchor());
			consultedFacts.put("rightIndex.filteredPartitions",
				Integer.toString(exactRightIndex.filteredPartitions()));
			consultedFacts.put("rightIndex.runtimeOutputFType", exactRightIndex.outputFType().name());
			requiredFacts.add("rightIndex.literalBounds");
			requiredFacts.add("rightIndex.inputAnchor");
			requiredFacts.add("rightIndex.filteredPartitions");
		}
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(consultedFacts,
			requiredFacts, new ArrayList<>(proof.missingRequiredFacts()));
		List<List<FType>> profileInputs = profileInputDomains(inputShapeFacts, inputs);
		// This native replica matmul requires an actually local LHS. Expanding that
		// exact absence into every matrix FType loses its BROADCAST output profile.
		if(hop instanceof AggBinaryOp && inputs.size() == 2
			&& inputs.get(0) == null && inputs.get(1) == FType.BROADCAST)
			profileInputs = List.of(Collections.singletonList(null), List.of(FType.BROADCAST));
		CandidateProfileFact profile;
		try {
			if(exactRightIndex != null)
				profile = new CandidateProfileFact(List.of(exactRightIndex.outputFType()), "");
			else {
				FTypeProfile inferred = inferProfile("candidate profile", preparedProfile, profileInputs, null);
				Set<FType> outputs = new LinkedHashSet<>(inferred == null ? List.of() : inferred.outputs());
				if(caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.FOUT
					&& caps.foutFType().orElse(null) == FType.FULL
					&& "true".equals(proof.consultedFacts().get("fullSinglePartition"))) {
					// The occurrence proof belongs to this exact tuple, not the broad profile domain
					// (which can replace an actually local operand with unproved federated layouts).
					List<List<FType>> exactInputs = inputs.stream()
						.map(Collections::singletonList).collect(java.util.stream.Collectors.toList());
					FTypeProfile exact = inferProfile("candidate FULL profile", preparedProfile, exactInputs,
						new ShapeHint(hop.getDim1(), hop.getDim2(), hop.getBlocksize(), true));
					if(exact != null && exact.outputs().contains(FType.FULL))
						outputs.add(FType.FULL);
				}
				profile = new CandidateProfileFact(List.copyOf(outputs), "");
			}
		}
		catch(RuntimeException e) {
			throw oracleRuntimeFailure("candidate profile", key.parentOccurrence().normalizedSignature(), hop,
				profileInputs, e);
		}
		CandidateEvaluationStatus status = profile.available() ? CandidateEvaluationStatus.AVAILABLE
			: CandidateEvaluationStatus.PROFILE_ERROR;
		if(status == CandidateEvaluationStatus.AVAILABLE && exactEmissionFacts.isEmpty() && privacy != null)
			return new CandidateRuleFact(key, CandidateEvaluationStatus.PRIVACY_EXCLUDED,
				capability, shapeProof, profile, List.of(), "PRIVACY:" + privacy.outputPrivacy().name());
		return new CandidateRuleFact(key, status, capability, shapeProof, profile,
			status == CandidateEvaluationStatus.AVAILABLE ? List.copyOf(exactEmissionFacts) : List.of(),
			profile.evaluationFailure());
	}

	private FTypeProfile inferProfile(String queryScope, PreparedProfile preparedProfile,
		List<List<FType>> inputDomains, ShapeHint hint) {
		ProfileInference inference = preparedProfile.inferWithEvidence(queryScope, inputDomains, hint);
		if(complexityMetrics != null)
			complexityMetrics.recordPreparedProfileQuery(inference.reused());
		return inference.profile();
	}

	private static ExactRightIndexRuntimeFact exactRightIndexRuntimeFact(Hop hop, List<FType> inputs,
		List<DurableAnchorKey> inputAnchors, OpCaps caps) {
		if(!(hop instanceof IndexingOp) || caps.exec() != ExecType.FED
			|| caps.placement() != FederatedOutput.FOUT || hop.getInput().size() < 5
			|| inputs.isEmpty() || inputAnchors.isEmpty())
			return null;
		FType inputType = inputs.get(0);
		DurableAnchorKey anchor = inputAnchors.get(0);
		if(inputType == null || anchor == null || anchor.fType() != inputType)
			return null;
		Long rowLower = exactLiteralLong(hop.getInput(1));
		Long rowUpper = exactLiteralLong(hop.getInput(2));
		Long colLower = exactLiteralLong(hop.getInput(3));
		Long colUpper = exactLiteralLong(hop.getInput(4));
		if(rowLower == null || rowUpper == null || colLower == null || colUpper == null
			|| rowLower < 1 || colLower < 1 || rowUpper < rowLower || colUpper < colLower)
			return null;

		long rowStart = rowLower - 1, rowEnd = rowUpper;
		long colStart = colLower - 1, colEnd = colUpper;
		List<AnchorPartition> filtered = new ArrayList<>();
		for(AnchorPartition partition : anchor.partitions()) {
			if(partition.begin().size() != 2 || partition.end().size() != 2)
				return null;
			long beginRow = partition.begin().get(0), beginCol = partition.begin().get(1);
			long endRow = partition.end().get(0), endCol = partition.end().get(1);
			if(beginRow < 0 || beginCol < 0 || endRow <= beginRow || endCol <= beginCol)
				return null;
			if(rowStart < endRow && rowEnd > beginRow && colStart < endCol && colEnd > beginCol)
				filtered.add(partition);
		}
		if(filtered.isEmpty())
			return null;
		long maxRow = filtered.stream().mapToLong(partition -> partition.end().get(0)).max().orElse(-1);
		long maxCol = filtered.stream().mapToLong(partition -> partition.end().get(1)).max().orElse(-1);
		boolean rowPartitioned = inputType.isType(FType.ROW) || filtered.stream().allMatch(partition ->
			partition.end().get(1) - partition.begin().get(1) == maxCol);
		boolean colPartitioned = inputType.isType(FType.COL) || filtered.stream().allMatch(partition ->
			partition.end().get(0) - partition.begin().get(0) == maxRow);
		FType outputType = rowPartitioned && colPartitioned
			? filtered.size() == 1 ? FType.FULL : FType.BROADCAST : inputType;
		String bounds = rowLower + ":" + rowUpper + ',' + colLower + ":" + colUpper;
		return new ExactRightIndexRuntimeFact(outputType, bounds,
			anchor.normalizedSignature(), filtered.size());
	}

	private static IllegalStateException oracleRuntimeFailure(String phase, String occurrence, Hop hop,
		Object inputLayouts, RuntimeException cause) {
		return new IllegalStateException("Federated " + phase + " failed: occurrence=" + occurrence
			+ ", hop=" + hop.getHopID() + ", op=" + hop.getOpString()
			+ ", inputLayouts=" + inputLayouts, cause);
	}

	private static IllegalStateException oracleReportedRuleError(String occurrence, Hop hop,
		List<FType> inputLayouts, OpCaps caps) {
		return new IllegalStateException("Federated oracle decision reported RULE_ERROR: occurrence=" + occurrence
			+ ", hop=" + hop.getHopID() + ", op=" + hop.getOpString()
			+ ", inputLayouts=" + inputLayouts + ", detail=" + caps.detail().orElse(""));
	}

	private static ShapeHint exactShapeHint(Hop hop, NodeShapeFact output,
		List<NodeShapeFact> inputs, Optional<Boolean> fullSinglePartition) {
		NodeShapeFact left = inputs.isEmpty() ? null : inputs.get(0);
		NodeShapeFact right = inputs.size() < 2 ? null : inputs.get(1);
		// An inherited anchor describes a placement pool, not this value's shape.
		// Only exact value-shape facts may be supplied as oracle decision evidence.
		long rowsA = left == null ? -1 : left.rows();
		long colsA = left == null ? -1 : left.cols();
		long rowsB = right == null ? -1 : right.rows();
		long colsB = right == null ? -1 : right.cols();
		long rows = output.rows(), cols = output.cols();
		if(hop instanceof BinaryOp && rows < 0 && rowsA > 0 && rowsA == rowsB)
			rows = rowsA;
		if(hop instanceof BinaryOp && cols < 0 && colsA > 0 && colsA == colsB)
			cols = colsA;
		return new ShapeHint(rows, cols, hop.getBlocksize(), fullSinglePartition,
			rowsA, colsA, rowsB, colsB);
	}

	private static void replaceFederatedSourceCandidateFacts(List<CandidateRuleFact> ruleFacts,
		int candidateFactStart, PlacementState exactSource, Hop hop) {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(
			org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory.OTHER,
			hop.getOpString(), ExecType.FED, FederatedOutput.FOUT, exactSource.fType(),
			org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.INFO,
			"literal-federated-source", List.of());
		CandidateShapeProofFact proof = new CandidateShapeProofFact(
			Map.of("literalFederatedSourceFType", exactSource.fType().name()),
			List.of("literalFederatedSourceFType"), List.of());
		CandidateProfileFact profile = new CandidateProfileFact(List.of(exactSource.fType()), "");
		PlacementEmissionState sourceEmission = new PlacementEmissionState(exactSource, false);
		CandidateEmissionFact emission = exactSource.fType() == FType.PART || exactSource.fType() == FType.OTHER
			? new CandidateEmissionFact(sourceEmission, exactSource.fType(), null,
				List.of(CandidateEmissionRealization.sourceLineage(sourceEmission,
					"literal-federated-source:" + hop.getName())))
			: candidateEmissionFact(exactSource, false, exactSource.fType());
		for(int index = candidateFactStart; index < ruleFacts.size(); index++) {
			CandidateRuleFact prior = ruleFacts.get(index);
			ruleFacts.set(index, new CandidateRuleFact(prior.key(), CandidateEvaluationStatus.AVAILABLE,
				capability, proof, profile, List.of(emission), ""));
		}
	}

	static void addGlobalExclusion(Set<PlacementState> legal, Map<PlacementState,Exclusion> excluded,
		Exclusion exclusion) {
		legal.remove(exclusion.state());
		excluded.compute(exclusion.state(), (state, prior) ->
			prior == null || prior.reasonCode() == ReasonCode.UNKNOWN_METADATA ? exclusion : prior);
	}

	static PlacementState addLegalCandidate(Set<PlacementState> legal,
		Map<PlacementState,Exclusion> excluded, PlacementState state) {
		Exclusion prior = excluded.get(state);
		if(prior != null) {
			if(prior.reasonCode() == ReasonCode.UNKNOWN_METADATA)
				excluded.remove(state);
			else
				return null;
		}
		legal.add(state);
		return exactLegalState(legal, state);
	}

	static void addUnknownMetadataExclusionUnlessProvenLegal(Set<PlacementState> legal,
		Map<PlacementState,Exclusion> excluded, PlacementState state, String detail) {
		if(!legal.contains(state))
			excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail));
	}

	private static PlacementState exactLegalState(Set<PlacementState> legal, PlacementState state) {
		for(PlacementState candidate : legal)
			if(candidate.equals(state))
				return candidate;
		throw new IllegalStateException("Exact legal state is missing from graph-owned set");
	}

	static CandidateEmissionFact candidateEmissionFact(PlacementState state, boolean derivedFedFout,
		FType executionFType) {
		return new CandidateEmissionFact(new PlacementEmissionState(state, derivedFedFout), executionFType);
	}

	static CandidateEmissionFact candidateEmissionFact(PlacementState state, boolean derivedFedFout,
		FType executionFType, DerivedFoutMaterializationActionKey action) {
		return new CandidateEmissionFact(new PlacementEmissionState(state, derivedFedFout), executionFType, action);
	}

	static DerivedFoutMaterializationActionKey derivedFoutAction(CompiledHopKey producer,
		ValueVersionKey producerValueVersion, CandidateRuleKey candidateRule, PlacementState source,
		PlacementState target, DurableAnchorKey anchor, CompiledHopKey anchorOwner, FType anchorOwnerFType,
		FType materializationFType) {
		return new DerivedFoutMaterializationActionKey(producer, producerValueVersion, candidateRule,
			source, target, anchor, anchorOwner, anchorOwnerFType,
			materializationFType, producer.controlRegion().normalizedSignature());
	}

	static boolean isAggregateBinaryVectorInput(Hop hop, AbstractShapeFact shape, FType inputType) {
		if(!(hop instanceof AggBinaryOp) || shape == null || !shape.isMatrix())
			return false;
		return inputType == FType.ROW && shape.provablyColumnVector()
			|| inputType == FType.COL && shape.provablyRowVector()
			// A one-worker FULL map owns the complete matrix, so the vector result is
			// orientation-independent but still follows the same forced-LOUT policy.
			|| inputType == FType.FULL && isVector(shape);
	}

	static boolean isVector(AbstractShapeFact shape) {
		return shape != null && shape.provablyVector();
	}

	private static FType exactAggregateBinaryVectorLocalType(Hop hop, AbstractShapeFact shape,
		List<FType> inputTypes) {
		List<FType> matches = inputTypes.stream()
			.filter(inputType -> isAggregateBinaryVectorInput(hop, shape, inputType))
			.distinct().toList();
		return matches.size() == 1 ? matches.get(0) : null;
	}

	private static MaterializationAnchor exactCandidateMaterializationAnchor(
		List<DurableAnchorKey> outputAnchors, List<DurableAnchorKey> inputAnchors,
		List<CompiledHopKey> inputAnchorOwners, List<FType> inputs) {
		Set<DurableAnchorKey> candidates = new java.util.TreeSet<>();
		if(outputAnchors.size() == 1)
			candidates.add(outputAnchors.get(0));
		Map<DurableAnchorKey,MaterializationAnchor> owners = new java.util.TreeMap<>();
		for(int i = 0; i < inputs.size() && i < inputAnchors.size(); i++) {
			DurableAnchorKey anchor = inputAnchors.get(i);
			CompiledHopKey owner = i < inputAnchorOwners.size() ? inputAnchorOwners.get(i) : null;
			if(inputs.get(i) != null && anchor != null && owner != null && anchor.fType() == inputs.get(i)) {
				candidates.add(anchor);
				owners.putIfAbsent(anchor, new MaterializationAnchor(anchor, owner, inputs.get(i)));
			}
		}
		if(candidates.size() != 1)
			return null;
		DurableAnchorKey anchor = candidates.iterator().next();
		return owners.get(anchor);
	}

	static FType exactMaterializationFType(NodeShapeFact shape, DurableAnchorKey anchor) {
		return PlacementCostSemantics.exactMaterializationFType(shape, anchor);
	}

	private static List<List<FType>> consumerProfileInputDomains(List<NodeShapeFact> inputShapeFacts,
		int targetPosition, FType targetCandidate) {
		List<List<FType>> domains = new ArrayList<>(inputShapeFacts.size());
		for(int i = 0; i < inputShapeFacts.size(); i++) {
			if(i == targetPosition)
				domains.add(List.of(targetCandidate));
			else if(inputShapeFacts.get(i).dataType().isMatrix())
				domains.add(PlacementCandidateRuleResolver.matrixFTypeCandidates());
			else
				domains.add(Collections.singletonList(null));
		}
		return Collections.unmodifiableList(domains);
	}

	private static List<List<FType>> consumerProfileInputDomains(List<NodeShapeFact> inputShapeFacts,
		List<Integer> targetPositions, FType targetCandidate) {
		List<List<FType>> domains = new ArrayList<>(inputShapeFacts.size());
		for(int i = 0; i < inputShapeFacts.size(); i++) {
			if(targetPositions.contains(i))
				domains.add(List.of(targetCandidate));
			else if(inputShapeFacts.get(i).dataType().isMatrix())
				domains.add(PlacementCandidateRuleResolver.matrixFTypeCandidates());
			else
				domains.add(Collections.singletonList(null));
		}
		return Collections.unmodifiableList(domains);
	}

	private static List<List<FType>> profileInputDomains(List<NodeShapeFact> inputShapeFacts,
		List<FType> inputs) {
		List<List<FType>> domains = new ArrayList<>(inputShapeFacts.size());
		for(int i = 0; i < inputShapeFacts.size(); i++) {
			FType known = i < inputs.size() ? inputs.get(i) : null;
			if(known != null)
				domains.add(List.of(known));
			else if(inputShapeFacts.get(i).dataType().isMatrix())
				domains.add(PlacementCandidateRuleResolver.matrixFTypeCandidates());
			else
				domains.add(Collections.singletonList(null));
		}
		return Collections.unmodifiableList(domains);
	}

	private static List<CandidateInputState> candidateInputStates(List<FType> inputs) {
		List<CandidateInputState> states = new ArrayList<>(inputs.size());
		for(FType input : inputs)
			states.add(input == null ? CandidateInputState.absentLocal() : CandidateInputState.present(input));
		return Collections.unmodifiableList(states);
	}

	static void forEachInputCombination(List<List<FType>> domains,
		java.util.function.Consumer<List<FType>> consumer, SearchSpaceMetrics metrics) {
		enumerateInputCombinations(domains, new ArrayList<>(), consumer, metrics);
	}

	private static void enumerateInputCombinations(List<List<FType>> domains, List<FType> prefix,
		java.util.function.Consumer<List<FType>> consumer, SearchSpaceMetrics metrics) {
		if(metrics != null)
			metrics.recordInputPrefix(prefix.size());
		if(prefix.size() == domains.size()) {
			if(metrics != null)
				metrics.recordInputLeaf();
			// ABSENT_LOCAL is represented by null, so List.copyOf is intentionally invalid here.
			consumer.accept(Collections.unmodifiableList(new ArrayList<>(prefix)));
			return;
		}
		for(FType type : domains.get(prefix.size())) {
			prefix.add(type);
			try {
				enumerateInputCombinations(domains, prefix, consumer, metrics);
			}
			finally {
				prefix.remove(prefix.size() - 1);
			}
		}
	}

	private static String inputEvidence(List<FType> inputs) {
		List<String> evidence = new ArrayList<>();
		for(int i = 0; i < inputs.size(); i++)
			evidence.add(i + ":" + String.valueOf(inputs.get(i)));
		return String.join(",", evidence);
	}

	private static FType firstFType(List<FType> values) {
		return values.stream().filter(Objects::nonNull).findFirst().orElse(null);
	}

	static boolean isLegalTransient(PlacementState s) {
		return (s.execType() == ExecType.CP && s.output() == FederatedOutput.LOUT)
			|| (s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT);
	}

	private static Long exactLiteralLong(Hop hop) {
		return hop instanceof LiteralOp ? ((LiteralOp) hop).getLongValue() : null;
	}

	record MaterializationAnchor(DurableAnchorKey anchor, CompiledHopKey owner,
		FType ownerFType) { }

	private record ConsumerProfileEvaluation(CandidateEvaluationStatus status,
		List<FType> allowedTargetTypes, String failureCode) { }

	private record ExactRightIndexRuntimeFact(FType outputFType, String literalBounds,
		String inputAnchor, int filteredPartitions) { }
}

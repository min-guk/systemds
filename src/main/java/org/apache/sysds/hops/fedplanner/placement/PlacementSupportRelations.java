/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Pure support-clause pruning, executable projection, and final identity binding. */
final class PlacementSupportRelations {
	private PlacementSupportRelations() {
		// static utility owner
	}

	static List<LogicalTransientInputFact> bindExactLogicalTransientSourceStates(
		List<LogicalTransientInputFact> facts, List<CandidateRuleFact> candidateFacts) {
		Map<String,CandidateRealizationReference> current = new java.util.TreeMap<>();
		for(CandidateRuleFact candidate : candidateFacts) {
			if(candidate.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : candidate.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations()) {
					CandidateRealizationReference reference = CandidateRealizationReference.of(
						candidate.key(), realization);
					if(current.put(reference.normalizedSignature(), reference) != null)
						throw new IllegalStateException("Duplicate candidate realization authority");
				}
		}
		List<LogicalTransientInputFact> rebound = new ArrayList<>(facts.size());
		for(LogicalTransientInputFact fact : facts) {
			List<TransientPlacementCompatibility> compatibility = new ArrayList<>();
			for(TransientPlacementCompatibility edge : fact.compatibility()) {
				CandidateRealizationReference source = current.get(
					edge.sourceRealization().normalizedSignature());
				CandidateRealizationReference reader = current.get(
					edge.readerRealization().normalizedSignature());
				if(source != null && reader != null)
					compatibility.add(new TransientPlacementCompatibility(source, reader,
						edge.sourceInput(), edge.readerInput(), edge.proof()));
			}
			if(!compatibility.isEmpty())
				rebound.add(new LogicalTransientInputFact(fact.sourceWrite(), fact.targetRead(),
					fact.logicalPosition(), fact.sourceValueVersion(), fact.readValueVersion(), compatibility));
		}
		return rebound.stream().sorted().toList();
	}

	static List<CandidateRuleFact> pruneUnsupportedRealizations(List<CandidateRuleFact> facts) {
		return pruneUnsupportedRealizations(facts, null, null);
	}

	static List<CandidateRuleFact> pruneUnsupportedRealizations(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions) {
		return pruneUnsupportedRealizations(facts, actions, null);
	}

	static List<CandidateRuleFact> pruneUnsupportedRealizations(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs) {
		return pruneUnsupportedRealizations(facts, actions, completeLogicalInputs, null);
	}

	static List<CandidateRuleFact> pruneUnsupportedRealizations(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
		SearchSpaceMetrics.PhaseToken started = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.SOURCE_PRUNING);
		try {
			return pruneUnsupportedRealizationsMeasured(
				facts, actions, completeLogicalInputs, requiredWriters);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.SOURCE_PRUNING, started);
		}
	}

	private static List<CandidateRuleFact> pruneUnsupportedRealizationsMeasured(
		List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		Set<CandidateRealizationReference> currentReferences = new HashSet<>();
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(executableSourceRealization(fact.key(), realization))
							currentReferences.add(CandidateRealizationReference.of(fact.key(), realization));
		Map<CompiledHopKey,List<LogicalTransientInputFact>> logicalInputsByReader = new IdentityHashMap<>();
		if(completeLogicalInputs != null)
			for(LogicalTransientInputFact logicalInput : completeLogicalInputs)
				logicalInputsByReader.computeIfAbsent(logicalInput.targetRead(), ignored -> new ArrayList<>())
					.add(logicalInput);
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(!executableSourceRealization(fact.key(), realization))
						continue;
					CandidateRealizationReference realizationReference =
						CandidateRealizationReference.of(fact.key(), realization);
					if(!hasCompleteLogicalTransientSupport(realizationReference, currentReferences,
						logicalInputsByReader.get(fact.key().parentOccurrence()),
						requiredWriters == null ? null : requiredWriters.get(fact.key().parentOccurrence())))
						continue;
					// CFG/direct rebuilding can replace exact source identities while an
					// old OR clause remains merged into the same output layout. That clause
					// has no authority in the current domain; keep every supported alternative
					// and let the surrounding fixed point regenerate bindings, never remap
					// an expired reference merely because another map has equal geometry.
					List<CandidateRealizationSupportClause> clauses = realization.supportClauses().stream()
						.filter(clause -> clause.inputBindings().stream()
							.allMatch(binding -> currentReferences.contains(binding.source())
								&& (actions == null || binding.kind() != CandidateInputBindingKind.RELOCATION
									|| supportsRelocationBinding(actions.get(binding.relocationAction()),
										fact.key(), emission.emissionState(), binding))))
						.toList();
					if(!clauses.isEmpty())
						realizations.add(clauses.size() == realization.supportClauses().size()
							? realization : CandidateEmissionRealization
								.fromAlreadyCanonicalSupportClauses(realization.key(), clauses));
				}
				if(!realizations.isEmpty()) {
					boolean unchanged = realizations.size() == emission.realizations().size();
					for(int index = 0; unchanged && index < realizations.size(); index++)
						unchanged = realizations.get(index) == emission.realizations().get(index);
					emissions.add(unchanged ? emission : new CandidateEmissionFact(emission.emissionState(),
						emission.executionFType(), emission.derivedFoutAction(), realizations));
				}
			}
			if(emissions.isEmpty())
				result.add(new CandidateRuleFact(fact.key(), CandidateEvaluationStatus.PROFILE_ERROR,
					fact.capability(), fact.shapeProof(),
					new CandidateProfileFact(List.of(), "NO_EXECUTABLE_REALIZATION"),
					List.of(), "NO_EXECUTABLE_REALIZATION"));
			else
				result.add(retainUnchangedPrivacyFact(fact, emissions));
		}
		return List.copyOf(result);
	}

	/** Every reaching writer must retain at least one live exact source for this reader alternative. */
	static boolean hasCompleteLogicalTransientSupport(CandidateRealizationReference reader,
		Set<CandidateRealizationReference> currentReferences,
		List<LogicalTransientInputFact> completeLogicalInputs, List<CompiledHopKey> requiredWriters) {
		// CFG, not a filtered compatibility list, owns the reaching-writer inventory.
		if(requiredWriters != null)
			for(CompiledHopKey writer : requiredWriters)
				if(completeLogicalInputs == null || completeLogicalInputs.stream().noneMatch(input ->
					input.sourceWrite() == writer && input.compatibilityForReader(reader).stream()
						.anyMatch(edge -> currentReferences.contains(edge.sourceRealization()))))
					return false;
		if(completeLogicalInputs != null)
			for(LogicalTransientInputFact input : completeLogicalInputs)
				if(input.compatibilityForReader(reader).stream()
					.noneMatch(edge -> currentReferences.contains(edge.sourceRealization())))
					return false;
		return true;
	}

	static boolean supportsRelocationBinding(NeutralPlacementGraph.RelocationAction action,
		CandidateRuleKey rule, PlacementEmissionState emission, CandidateRealizationInputBinding binding) {
		return action != null && action.obligations().stream().anyMatch(obligation ->
			obligation.consumer() == rule.parentOccurrence()
				&& obligation.inputPosition() == binding.inputPosition()
				&& obligation.requiredPlacement().equals(emission.placementState()));
	}

	/** Checks action/realization identity without mutating the converged publication. */
	static void verifyPublishedRelocationRealizations(List<CandidateRuleFact> facts,
		List<NeutralPlacementGraph.RelocationAction> actions) {
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> byKey = new LinkedHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : actions)
			if(byKey.putIfAbsent(action.key(), action) != null)
				throw new IllegalStateException("Final publication has duplicate relocation action keys");
		Set<CandidateRealizationReference> liveSources = new HashSet<>();
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						liveSources.add(CandidateRealizationReference.of(fact.key(), realization));
		Set<RelocationActionKey> usedActions = new HashSet<>();
		for(CandidateRuleFact fact : facts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations())
					for(CandidateRealizationSupportClause clause : realization.supportClauses())
						for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
							if(binding.kind() != CandidateInputBindingKind.RELOCATION)
								continue;
							NeutralPlacementGraph.RelocationAction action = byKey.get(binding.relocationAction());
							if(action == null || !liveSources.contains(binding.source())
								|| action.obligations().stream().noneMatch(obligation ->
									obligation.consumer() == fact.key().parentOccurrence()
										&& obligation.inputPosition() == binding.inputPosition()
										&& obligation.requiredPlacement().equals(
											emission.emissionState().placementState())))
								throw new IllegalStateException("Final publication has an ungrounded relocation realization: actionPresent="
									+ (action != null) + "|sourceLive=" + liveSources.contains(binding.source())
									+ "|owner=" + fact.key().parentOccurrence().normalizedSignature());
							usedActions.add(action.key());
						}
		}
		for(NeutralPlacementGraph.RelocationAction action : actions)
			if(action.directSourcePlacements().isEmpty() && !usedActions.contains(action.key()))
				throw new IllegalStateException("Final publication has an unbound relocation action");
	}

	/** Removes selectable FOUT states that have no final executable candidate realization authority. */
	static ExecutableNodeProjection projectCandidateNodesToExecutableStates(List<Node> nodes,
		List<CandidateRuleFact> facts, int compiledOccurrenceCount) {
		Map<CompiledHopKey,Set<PlacementState>> executableByParent = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> hasInputlessCandidate = new IdentityHashMap<>();
		Set<CompiledHopKey> candidateParents = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRuleFact fact : facts) {
			candidateParents.add(fact.key().parentOccurrence());
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			hasInputlessCandidate.merge(fact.key().parentOccurrence(),
				fact.key().orderedInputs().stream().noneMatch(CandidateInputState::present), Boolean::logicalOr);
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(!emission.realizations().isEmpty())
					executableByParent.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>())
						.add(emission.emissionState().placementState());
		}
		List<Node> projected = new ArrayList<>(nodes.size());
		List<Integer> changed = new ArrayList<>();
		for(int ordinal = 0; ordinal < nodes.size(); ordinal++) {
			Node node = nodes.get(ordinal);
			if(!candidateParents.contains(node.key())) {
				projected.add(node);
				continue;
			}
			Set<PlacementState> executable = executableByParent.getOrDefault(node.key(), Set.of());
			List<PlacementState> legal = node.legalAlternatives().stream()
				.filter(state -> executable.contains(state)
					|| state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
					|| state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT
						&& hasInputlessCandidate.getOrDefault(node.key(), false))
				.toList();
			Map<PlacementState,Exclusion> exclusions = new LinkedHashMap<>();
			for(Exclusion exclusion : node.exclusions())
				if(!legal.contains(exclusion.state()))
					exclusions.put(exclusion.state(), exclusion);
			for(PlacementState removed : node.legalAlternatives())
				if(!legal.contains(removed))
					exclusions.putIfAbsent(removed, new Exclusion(removed, ReasonCode.MISSING_ANCHOR,
						"no-executable-candidate-realization"));
			List<Exclusion> exactExclusions = new ArrayList<>(exclusions.values());
			Node replacement = legal.equals(node.legalAlternatives()) && exactExclusions.equals(node.exclusions())
				? node : new Node(node.key(), node.kind(), node.valueVersion(), !legal.isEmpty(), legal,
					exactExclusions, node.anchors());
			projected.add(replacement);
			if(replacement != node && ordinal < compiledOccurrenceCount)
				changed.add(ordinal);
		}
		return new ExecutableNodeProjection(List.copyOf(projected), List.copyOf(changed));
	}

	static List<Integer> changedCompiledNodeOrdinals(List<Node> before, List<Node> after,
		int compiledOccurrenceCount) {
		if(before.size() != after.size())
			throw new IllegalStateException("Placement closure changed node cardinality");
		List<Integer> changed = new ArrayList<>();
		for(int ordinal = 0; ordinal < Math.min(compiledOccurrenceCount, before.size()); ordinal++)
			if(!before.get(ordinal).equals(after.get(ordinal)))
				changed.add(ordinal);
		return List.copyOf(changed);
	}

	/** Rebind value-equal replay states to the final graph-owned state identities. */
	static List<CandidateRuleFact> bindExactCandidateEmissionStates(
		List<CandidateRuleFact> facts, List<Node> nodes) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		List<CandidateRuleFact> bound = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				bound.add(fact);
				continue;
			}
			Node node = nodesByKey.get(fact.key().parentOccurrence());
			if(node == null)
				throw new IllegalStateException("Candidate fact has no final graph node");
			if(node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED) {
				if(node.emittedWork() || !node.legalAlternatives().isEmpty())
					throw new IllegalStateException("Non-emitted function trace node retained executable placement");
				bound.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>(fact.allowedEmissionFacts().size());
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState priorTarget = emission.emissionState().placementState();
				PlacementState target = node.legalAlternatives().stream()
					.filter(priorTarget::equals).findFirst().orElseThrow(() ->
						new IllegalStateException("Candidate emission is absent from final graph node|key="
							+ fact.key().normalizedSignature() + "|state="
							+ priorTarget.normalizedSignature() + "|nodeKind=" + node.kind()
							+ "|nodeAlternatives="
							+ node.legalAlternatives().stream()
								.map(PlacementState::normalizedSignature).toList()));
				DerivedFoutMaterializationActionKey priorAction = emission.derivedFoutAction();
				PlacementEmissionState reboundEmission = new PlacementEmissionState(target,
					emission.emissionState().derivedFedFout());
				List<CandidateEmissionRealization> reboundRealizations = emission.realizations().stream()
					.map(realization -> rebindRealization(realization, reboundEmission)).toList();
				if(priorAction == null) {
					emissions.add(new CandidateEmissionFact(reboundEmission,
						emission.executionFType(), null, reboundRealizations));
					continue;
				}
				PlacementState source = node.legalAlternatives().stream()
					.filter(priorAction.sourcePlacement()::equals).findFirst().orElseThrow(() ->
						new IllegalStateException("Candidate materialization source is absent from final graph node"));
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					priorAction.producer(), priorAction.producerValueVersion(), priorAction.candidateRule(),
					source, target, priorAction.durableAnchor(), priorAction.durableAnchorOwner(),
					priorAction.durableAnchorOwnerFType(), priorAction.materializationFType(),
					priorAction.statementBlockScope());
				emissions.add(new CandidateEmissionFact(reboundEmission,
					emission.executionFType(), action, reboundRealizations));
			}
			bound.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(bound);
	}

	static CandidateEmissionRealization rebindRealization(CandidateEmissionRealization realization,
		PlacementEmissionState emission) {
		var key = switch(realization.key().layoutKind()) {
			case LOCAL -> PlacementIdentity.PlacementRealizationKey.local(emission);
			case SOURCE_LINEAGE -> PlacementIdentity.PlacementRealizationKey.sourceLineage(
				emission, realization.key().nativeLineage());
			case DURABLE_MAP -> PlacementIdentity.PlacementRealizationKey.durable(emission, realization.anchor());
			case NATIVE_LINEAGE -> PlacementIdentity.PlacementRealizationKey.nativeLineage(
				emission, realization.key().nativeLineage());
		};
		return CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(
			key, realization.supportClauses());
	}

	static boolean executableSourceRealization(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return realization.key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
			|| realization.supportClauses().stream()
				.allMatch(clause -> clause.nativeWorkerPoolWitness() != null);
	}

	/** All privacy and source-support checks have run; reuse only an identical immutable row. */
	static CandidateRuleFact retainUnchangedPrivacyFact(CandidateRuleFact fact,
		List<CandidateEmissionFact> emissions) {
		List<CandidateEmissionFact> original = fact.allowedEmissionFacts();
		if(original.size() == emissions.size()) {
			boolean unchanged = true;
			for(int i = 0; i < original.size(); i++)
				if(original.get(i) != emissions.get(i)) {
					unchanged = false;
					break;
				}
			if(unchanged)
				return fact;
		}
		return new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
			fact.shapeProof(), fact.profile(), emissions, fact.failureCode());
	}

	record ExecutableNodeProjection(List<Node> nodes, List<Integer> changedOrdinals) { }
}

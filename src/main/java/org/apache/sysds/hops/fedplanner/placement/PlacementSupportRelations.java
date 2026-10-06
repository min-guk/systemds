/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
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
		return projectInitialSupport(facts, actions, completeLogicalInputs, requiredWriters, false).facts();
	}

	private static InitialSupportProjection projectInitialSupport(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters, boolean collectWork) {
		Set<CandidateRealizationReference> currentReferences = new HashSet<>();
		long indexedRealizations = 0;
		long indexedClauses = 0;
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations()) {
						if(collectWork) {
							indexedRealizations++;
							indexedClauses += realization.supportClauses().size();
						}
						if(executableSourceRealization(fact.key(), realization))
							currentReferences.add(CandidateRealizationReference.of(fact.key(), realization));
					}
		Map<CompiledHopKey,List<LogicalTransientInputFact>> logicalInputsByReader = new IdentityHashMap<>();
		if(completeLogicalInputs != null)
			for(LogicalTransientInputFact logicalInput : completeLogicalInputs)
				logicalInputsByReader.computeIfAbsent(logicalInput.targetRead(), ignored -> new ArrayList<>())
					.add(logicalInput);
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		Set<CandidateRealizationReference> distinctSources = collectWork ? new HashSet<>() : null;
		boolean removedInitiallyExecutable = false;
		long reverseIncidences = 0;
		long logicalRequirements = 0;
		long deletedRealizations = 0;
		for(CandidateRuleFact fact : facts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(!executableSourceRealization(fact.key(), realization)) {
						if(collectWork)
							deletedRealizations++;
						continue;
					}
					CandidateRealizationReference realizationReference =
						CandidateRealizationReference.of(fact.key(), realization);
					List<LogicalTransientInputFact> logicalInputs =
						logicalInputsByReader.get(fact.key().parentOccurrence());
					List<CompiledHopKey> writers = requiredWriters == null ? null
						: requiredWriters.get(fact.key().parentOccurrence());
					boolean logicalSupported;
					if(collectWork) {
						LogicalSupportInventory inventory = logicalSupportInventory(realizationReference,
							currentReferences, logicalInputs, writers, distinctSources);
						logicalSupported = inventory.supported();
						reverseIncidences += inventory.reverseIncidences();
						logicalRequirements += inventory.requirements();
					}
					else
						logicalSupported = hasCompleteLogicalTransientSupport(realizationReference,
							currentReferences, logicalInputs, writers);
					if(!collectWork && !logicalSupported)
						continue;
					// CFG/direct rebuilding can replace exact source identities while an
					// old OR clause remains merged into the same output layout. That clause
					// has no authority in the current domain; keep every supported alternative
					// and let the surrounding fixed point regenerate bindings, never remap
					// an expired reference merely because another map has equal geometry.
					List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						boolean supported = true;
						if(collectWork)
							distinctSources.clear();
						for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
							if(collectWork)
								distinctSources.add(binding.source());
							if(supported && (!currentReferences.contains(binding.source())
								|| (actions != null && binding.kind() == CandidateInputBindingKind.RELOCATION
									&& !supportsRelocationBinding(actions.get(binding.relocationAction()),
										fact.key(), emission.emissionState(), binding))))
								supported = false;
						}
						if(supported) {
							clauses.add(clause);
							if(collectWork)
								reverseIncidences += distinctSources.size();
						}
					}
					if(logicalSupported && !clauses.isEmpty())
						realizations.add(clauses.size() == realization.supportClauses().size()
							? realization : CandidateEmissionRealization
								.fromAlreadyCanonicalSupportClauses(realization.key(), clauses));
					else {
						removedInitiallyExecutable = true;
						if(collectWork)
							deletedRealizations++;
					}
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
		WorklistWork work = collectWork ? new WorklistWork(indexedRealizations, indexedClauses,
			reverseIncidences, logicalRequirements, 0, deletedRealizations, 0, 0) : null;
		return new InitialSupportProjection(List.copyOf(result), removedInitiallyExecutable, work);
	}

	private static LogicalSupportInventory logicalSupportInventory(CandidateRealizationReference reader,
		Set<CandidateRealizationReference> currentReferences,
		List<LogicalTransientInputFact> completeLogicalInputs, List<CompiledHopKey> requiredWriters,
		Set<CandidateRealizationReference> distinctSources) {
		boolean supported = true;
		long incidences = 0;
		long requirements = 0;
		if(requiredWriters != null)
			for(CompiledHopKey writer : requiredWriters) {
				distinctSources.clear();
				if(completeLogicalInputs != null)
					for(LogicalTransientInputFact input : completeLogicalInputs)
						if(input.sourceWrite() == writer)
							for(TransientPlacementCompatibility edge : input.compatibilityForReader(reader))
								if(currentReferences.contains(edge.sourceRealization()))
									distinctSources.add(edge.sourceRealization());
				requirements++;
				incidences += distinctSources.size();
				if(distinctSources.isEmpty())
					supported = false;
			}
		if(completeLogicalInputs != null)
			for(LogicalTransientInputFact input : completeLogicalInputs) {
				distinctSources.clear();
				for(TransientPlacementCompatibility edge : input.compatibilityForReader(reader))
					if(currentReferences.contains(edge.sourceRealization()))
						distinctSources.add(edge.sourceRealization());
				requirements++;
				incidences += distinctSources.size();
				if(distinctSources.isEmpty())
					supported = false;
			}
		return new LogicalSupportInventory(supported, incidences, requirements);
	}

	private record LogicalSupportInventory(boolean supported, long reverseIncidences,
		long requirements) { }
	private record InitialSupportProjection(List<CandidateRuleFact> facts,
		boolean removedInitiallyExecutable, WorklistWork work) { }

	/**
	 * Computes the same greatest deletion fixed point as repeated invocations of
	 * {@link #pruneUnsupportedRealizations}, but indexes exact support incidences
	 * once for this immutable source/action epoch.
	 */
	static List<CandidateRuleFact> pruneUnsupportedRealizationsToFixedPoint(
		List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		return pruneUnsupportedRealizationsToFixedPointWithWork(
			facts, actions, completeLogicalInputs, requiredWriters).facts();
	}

	static WorklistResult pruneUnsupportedRealizationsToFixedPointWithWork(
		List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
		SearchSpaceMetrics.PhaseToken started = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.SOURCE_PRUNING);
		try {
			return pruneUnsupportedRealizationsToFixedPointMeasured(
				facts, actions, completeLogicalInputs, requiredWriters);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.SOURCE_PRUNING, started);
		}
	}

	private static WorklistResult pruneUnsupportedRealizationsToFixedPointMeasured(
		List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> completeLogicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		InitialSupportProjection initial = projectInitialSupport(facts, actions,
			completeLogicalInputs, requiredWriters, true);
		// With no executable deletion the exact reference set is unchanged. Every
		// remaining predicate depends only on that set and the fixed inventories, so
		// the one-pass projection is already the greatest deletion fixed point.
		if(!initial.removedInitiallyExecutable())
			return new WorklistResult(initial.facts(), initial.work());

		List<FixedPointFact> indexedFacts = new ArrayList<>(facts.size());
		Map<CandidateRealizationReference,FixedPointReference> references = new LinkedHashMap<>();
		List<FixedPointRealization> realizations = new ArrayList<>();
		long indexedClauses = 0;
		for(CandidateRuleFact fact : facts) {
			List<FixedPointEmission> emissions = new ArrayList<>();
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					List<FixedPointRealization> emissionRealizations = new ArrayList<>();
					for(CandidateEmissionRealization realization : emission.realizations()) {
						CandidateRealizationReference reference =
							CandidateRealizationReference.of(fact.key(), realization);
						FixedPointReference referenceState = references.computeIfAbsent(
							reference, FixedPointReference::new);
						FixedPointRealization slot = new FixedPointRealization(
							fact, emission, realization, referenceState,
							executableSourceRealization(fact.key(), realization));
						if(slot.live)
							referenceState.liveSlots++;
						realizations.add(slot);
						emissionRealizations.add(slot);
						indexedClauses += realization.supportClauses().size();
					}
					emissions.add(new FixedPointEmission(emission, emissionRealizations));
				}
			indexedFacts.add(new FixedPointFact(fact, emissions));
		}

		Map<CompiledHopKey,List<LogicalTransientInputFact>> logicalInputsByReader = new IdentityHashMap<>();
		if(completeLogicalInputs != null)
			for(LogicalTransientInputFact logicalInput : completeLogicalInputs)
				logicalInputsByReader.computeIfAbsent(logicalInput.targetRead(), ignored -> new ArrayList<>())
					.add(logicalInput);

		long reverseIncidences = 0;
		long logicalRequirements = 0;
		for(FixedPointRealization slot : realizations) {
			if(!slot.live)
				continue;
			for(CandidateRealizationSupportClause clause : slot.realization.supportClauses()) {
				boolean actionSupported = clause.inputBindings().stream().allMatch(binding ->
					actions == null || binding.kind() != CandidateInputBindingKind.RELOCATION
						|| supportsRelocationBinding(actions.get(binding.relocationAction()),
							slot.fact.key(), slot.emission.emissionState(), binding));
				List<FixedPointReference> sources = new ArrayList<>();
				if(actionSupported)
					for(CandidateRealizationReference source : clause.requiredInputSupport()) {
						FixedPointReference sourceState = references.get(source);
						if(sourceState == null || sourceState.liveSlots == 0) {
							actionSupported = false;
							break;
						}
						sources.add(sourceState);
					}
				FixedPointClause clauseState = new FixedPointClause(slot, clause, actionSupported);
				slot.clauses.add(clauseState);
				if(actionSupported) {
					slot.liveClauses++;
					for(FixedPointReference source : sources) {
						source.dependentClauses.add(clauseState);
						reverseIncidences++;
					}
				}
			}

			List<LogicalTransientInputFact> logicalInputs =
				logicalInputsByReader.get(slot.fact.key().parentOccurrence());
			List<CompiledHopKey> writers = requiredWriters == null ? null
				: requiredWriters.get(slot.fact.key().parentOccurrence());
			if(writers != null)
				for(CompiledHopKey writer : writers) {
					LinkedHashSet<CandidateRealizationReference> sources = new LinkedHashSet<>();
					if(logicalInputs != null)
						for(LogicalTransientInputFact input : logicalInputs)
							if(input.sourceWrite() == writer)
								for(TransientPlacementCompatibility edge :
									input.compatibilityForReader(slot.reference.reference))
									sources.add(edge.sourceRealization());
					reverseIncidences += addLogicalRequirement(slot, sources, references);
					logicalRequirements++;
				}
			if(logicalInputs != null)
				for(LogicalTransientInputFact input : logicalInputs) {
					LinkedHashSet<CandidateRealizationReference> sources = new LinkedHashSet<>();
					for(TransientPlacementCompatibility edge :
						input.compatibilityForReader(slot.reference.reference))
						sources.add(edge.sourceRealization());
					reverseIncidences += addLogicalRequirement(slot, sources, references);
					logicalRequirements++;
				}
		}

		ArrayDeque<FixedPointRealization> deletions = new ArrayDeque<>();
		for(FixedPointRealization slot : realizations)
			if(slot.live && (slot.liveClauses == 0 || slot.unsupportedLogicalRequirements > 0))
				scheduleDeletion(slot, deletions);
		long queueVisits = 0;
		long invalidatedClauses = 0;
		while(!deletions.isEmpty()) {
			FixedPointRealization removed = deletions.removeFirst();
			queueVisits++;
			if(!removed.live)
				continue;
			removed.live = false;
			FixedPointReference reference = removed.reference;
			if(--reference.liveSlots != 0)
				continue;
			for(FixedPointClause dependent : reference.dependentClauses)
				if(dependent.live) {
					dependent.live = false;
					invalidatedClauses++;
					if(--dependent.owner.liveClauses == 0)
						scheduleDeletion(dependent.owner, deletions);
				}
			for(FixedPointLogicalRequirement requirement : reference.logicalRequirements)
				if(requirement.liveSources > 0 && --requirement.liveSources == 0) {
					requirement.owner.unsupportedLogicalRequirements++;
					scheduleDeletion(requirement.owner, deletions);
				}
		}

		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(FixedPointFact indexedFact : indexedFacts) {
			CandidateRuleFact fact = indexedFact.fact;
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(FixedPointEmission indexedEmission : indexedFact.emissions) {
				List<CandidateEmissionRealization> survivors = new ArrayList<>();
				for(FixedPointRealization slot : indexedEmission.realizations) {
					if(!slot.live)
						continue;
					List<CandidateRealizationSupportClause> clauses = slot.clauses.stream()
						.filter(candidate -> candidate.live).map(candidate -> candidate.clause).toList();
					survivors.add(clauses.size() == slot.realization.supportClauses().size()
						? slot.realization : CandidateEmissionRealization
							.fromAlreadyCanonicalSupportClauses(slot.realization.key(), clauses));
				}
				if(!survivors.isEmpty()) {
					boolean unchanged = survivors.size() == indexedEmission.emission.realizations().size();
					for(int index = 0; unchanged && index < survivors.size(); index++)
						unchanged = survivors.get(index) == indexedEmission.emission.realizations().get(index);
					emissions.add(unchanged ? indexedEmission.emission : new CandidateEmissionFact(
						indexedEmission.emission.emissionState(), indexedEmission.emission.executionFType(),
						indexedEmission.emission.derivedFoutAction(), survivors));
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

		long deletedRealizations = realizations.stream().filter(slot -> !slot.live).count();
		return new WorklistResult(List.copyOf(result), new WorklistWork(
			realizations.size(), indexedClauses, reverseIncidences, logicalRequirements,
			queueVisits, deletedRealizations, invalidatedClauses, reverseIncidences));
	}

	private static long addLogicalRequirement(FixedPointRealization owner,
		Set<CandidateRealizationReference> sourceReferences,
		Map<CandidateRealizationReference,FixedPointReference> references) {
		FixedPointLogicalRequirement requirement = new FixedPointLogicalRequirement(owner);
		long incidences = 0;
		for(CandidateRealizationReference sourceReference : sourceReferences) {
			FixedPointReference source = references.get(sourceReference);
			if(source == null || source.liveSlots == 0)
				continue;
			requirement.liveSources++;
			source.logicalRequirements.add(requirement);
			incidences++;
		}
		if(requirement.liveSources == 0)
			owner.unsupportedLogicalRequirements++;
		return incidences;
	}

	private static void scheduleDeletion(FixedPointRealization realization,
		ArrayDeque<FixedPointRealization> deletions) {
		if(realization.live && !realization.deletionScheduled) {
			realization.deletionScheduled = true;
			deletions.addLast(realization);
		}
	}

	/** Logical inventory/outcome plus the subset physically linked into the reverse index. */
	record WorklistWork(long indexedRealizations, long indexedClauses,
		long reverseIncidences, long logicalRequirements, long queueVisits,
		long deletedRealizations, long invalidatedClauses,
		long materializedReverseIncidences) { }

	record WorklistResult(List<CandidateRuleFact> facts, WorklistWork work) {
		WorklistResult {
			facts = List.copyOf(facts);
		}
	}

	private static final class FixedPointFact {
		private final CandidateRuleFact fact;
		private final List<FixedPointEmission> emissions;
		private FixedPointFact(CandidateRuleFact fact, List<FixedPointEmission> emissions) {
			this.fact = fact;
			this.emissions = emissions;
		}
	}

	private static final class FixedPointEmission {
		private final CandidateEmissionFact emission;
		private final List<FixedPointRealization> realizations;
		private FixedPointEmission(CandidateEmissionFact emission,
			List<FixedPointRealization> realizations) {
			this.emission = emission;
			this.realizations = realizations;
		}
	}

	private static final class FixedPointRealization {
		private final CandidateRuleFact fact;
		private final CandidateEmissionFact emission;
		private final CandidateEmissionRealization realization;
		private final FixedPointReference reference;
		private final List<FixedPointClause> clauses = new ArrayList<>();
		private boolean live;
		private boolean deletionScheduled;
		private int liveClauses;
		private int unsupportedLogicalRequirements;
		private FixedPointRealization(CandidateRuleFact fact, CandidateEmissionFact emission,
			CandidateEmissionRealization realization, FixedPointReference reference, boolean live) {
			this.fact = fact;
			this.emission = emission;
			this.realization = realization;
			this.reference = reference;
			this.live = live;
		}
	}

	private static final class FixedPointReference {
		private final CandidateRealizationReference reference;
		private final List<FixedPointClause> dependentClauses = new ArrayList<>();
		private final List<FixedPointLogicalRequirement> logicalRequirements = new ArrayList<>();
		private int liveSlots;
		private FixedPointReference(CandidateRealizationReference reference) {
			this.reference = reference;
		}
	}

	private static final class FixedPointClause {
		private final FixedPointRealization owner;
		private final CandidateRealizationSupportClause clause;
		private boolean live;
		private FixedPointClause(FixedPointRealization owner,
			CandidateRealizationSupportClause clause, boolean live) {
			this.owner = owner;
			this.clause = clause;
			this.live = live;
		}
	}

	private static final class FixedPointLogicalRequirement {
		private final FixedPointRealization owner;
		private int liveSources;
		private FixedPointLogicalRequirement(FixedPointRealization owner) {
			this.owner = owner;
		}
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
				PlacementState source = priorAction == null ? null : node.legalAlternatives().stream()
					.filter(priorAction.sourcePlacement()::equals).findFirst().orElseThrow(() ->
						new IllegalStateException("Candidate materialization source is absent from final graph node"));
				// Equality locates the graph authority; identity proves that this immutable
				// emission and every realization already own that exact authority wrapper.
				boolean exactEmissionAuthority = target == priorTarget
					&& emission.realizations().stream().allMatch(realization ->
						realization.key().emissionState() == emission.emissionState())
					&& (priorAction == null || source == priorAction.sourcePlacement()
						&& target == priorAction.targetPlacement());
				if(exactEmissionAuthority) {
					emissions.add(emission);
					continue;
				}
				PlacementEmissionState reboundEmission = target == priorTarget
					? emission.emissionState() : new PlacementEmissionState(target,
						emission.emissionState().derivedFedFout());
				List<CandidateEmissionRealization> reboundRealizations = emission.realizations().stream()
					.map(realization -> rebindRealization(realization, reboundEmission)).toList();
				if(priorAction == null) {
					emissions.add(new CandidateEmissionFact(reboundEmission,
						emission.executionFType(), null, reboundRealizations));
					continue;
				}
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					priorAction.producer(), priorAction.producerValueVersion(), priorAction.candidateRule(),
					source, target, priorAction.durableAnchor(), priorAction.durableAnchorOwner(),
					priorAction.durableAnchorOwnerFType(), priorAction.materializationFType(),
					priorAction.statementBlockScope());
				emissions.add(new CandidateEmissionFact(reboundEmission,
					emission.executionFType(), action, reboundRealizations));
			}
			bound.add(retainUnchangedPrivacyFact(fact, emissions));
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
			case VALUE_MAP -> PlacementIdentity.PlacementRealizationKey.valueMap(
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

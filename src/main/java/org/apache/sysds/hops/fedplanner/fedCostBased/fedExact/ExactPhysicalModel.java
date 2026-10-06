/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.LogicalBoundaryRealizations;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextContext;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Baseline-free categorical domain and hard-factor model for exact physical alternatives.
 *
 * <p>This class owns legality and authority identity only. It intentionally does not copy
 * the canonical exact cost factors: {@link #costSurfaceComplete()} remains false until the
 * producer supplies canonical cost factors.</p>
 */
final class ExactPhysicalModel {
	enum AuthorityKind {
		LEGAL_SINGLETON, DURABLE_ANCHOR, CAPTURED_RULE, RELOCATION_SOURCE, SYNTHETIC_BOUNDARY
	}
	enum InputAuthorityKind { NATIVE_LOCAL, DIRECT_FOUT, RELOCATION }
	private enum LinkKind { COMPILED, LOGICAL_TRANSIENT, LOGICAL_FUNCTION }

	record InputAuthority(int inputPosition, InputAuthorityKind kind, FType expectedFType,
		CompiledHopKey sourceDecision, RelocationAction relocationAction) {
		InputAuthority {
			if(inputPosition < 0 || kind == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_INPUT_AUTHORITY_INVALID");
			if(kind == InputAuthorityKind.NATIVE_LOCAL
					&& (expectedFType != null || sourceDecision != null || relocationAction != null)
				|| kind == InputAuthorityKind.DIRECT_FOUT && expectedFType == null
				|| kind == InputAuthorityKind.RELOCATION
					&& (expectedFType == null || sourceDecision == null || relocationAction == null)
				|| relocationAction != null && sourceDecision == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_INPUT_AUTHORITY_MIXED");
		}
		String signature() {
			return inputPosition + ":" + kind + ':' + (expectedFType == null ? "-" : expectedFType)
				+ ':' + (sourceDecision == null ? "-" : sourceDecision.normalizedSignature())
				+ ':' + (relocationAction == null ? "-" : relocationAction.normalizedSignature());
		}
	}

	record Alternative(CompiledHopKey decision, PlacementState state, AuthorityKind authorityKind,
		CandidateRuleFact candidateRule, CandidateEmissionFact candidateEmission,
		CandidateRuleFact executionRule, CandidateEmissionFact executionEmission,
		DurableAnchorKey durableAnchor, RelocationAction relocationAction,
		DerivedFoutMaterializationAction derivedFoutAction,
		List<CandidateInputState> orderedInputs, List<InputAuthority> inputAuthorities,
		CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause supportClause, NormalizedText normalizedSignature) {
		Alternative {
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(authorityKind, "authorityKind");
			orderedInputs = List.copyOf(orderedInputs);
			inputAuthorities = List.copyOf(inputAuthorities);
			if((realization == null) != (supportClause == null)
				|| realization != null && !realization.ownsSupportClauseIdentity(supportClause))
				throw new IllegalArgumentException("EXACT_PHYSICAL_SUPPORT_CLAUSE_IDENTITY_INVALID");
			boolean materializedOutputCandidate = candidateEmission != null
				&& candidateEmission.derivedFoutAction() != null;
			if(materializedOutputCandidate != (derivedFoutAction != null)
				|| derivedFoutAction != null && relocationAction != null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_FOUT_OUTPUT_AUTHORITY_INVALID");
			if(normalizedSignature == null || normalizedSignature.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_ALTERNATIVE_SIGNATURE_INVALID");
		}
		Alternative(CompiledHopKey decision, PlacementState state, AuthorityKind authorityKind,
			CandidateRuleFact candidateRule, CandidateEmissionFact candidateEmission,
			CandidateRuleFact executionRule, CandidateEmissionFact executionEmission,
			DurableAnchorKey durableAnchor, RelocationAction relocationAction,
			DerivedFoutMaterializationAction derivedFoutAction,
			List<CandidateInputState> orderedInputs, List<InputAuthority> inputAuthorities,
			CandidateEmissionRealization realization,
			PlacementAnalysis.CandidateRealizationSupportClause supportClause, String signature) {
			this(decision, state, authorityKind, candidateRule, candidateEmission, executionRule,
				executionEmission, durableAnchor, relocationAction, derivedFoutAction, orderedInputs,
				inputAuthorities, realization, supportClause, normalizedSignature(signature));
		}
		private static NormalizedText normalizedSignature(String signature) {
			// Keep the legacy canonical-constructor validation order: a null signature
			// is rejected last, after invalid decision/state/list arguments.
			return NormalizedText.literal(signature == null ? "" : signature);
		}
		String signature() { return normalizedSignature.materialize(); }
		void appendSignature(java.util.function.Consumer<String> consumer) {
			normalizedSignature.appendTo(consumer);
		}
		boolean captured() { return authorityKind == AuthorityKind.CAPTURED_RULE; }
		@Override public String toString() {
			return "Alternative[decision=" + decision + ", state=" + state + ", authorityKind="
				+ authorityKind + ", candidateRule=" + candidateRule + ", candidateEmission="
				+ candidateEmission + ", executionRule=" + executionRule + ", executionEmission="
				+ executionEmission + ", durableAnchor=" + durableAnchor + ", relocationAction="
				+ relocationAction + ", derivedFoutAction=" + derivedFoutAction + ", orderedInputs="
				+ orderedInputs + ", inputAuthorities=" + inputAuthorities + ", realization="
				+ realization + ", supportClause=" + supportClause + ", signature=" + signature() + "]";
		}
	}

	record DecisionDomain(Node node, ExactCategoricalSolver.Variable variable,
		List<Alternative> alternatives) {
		DecisionDomain {
			alternatives = List.copyOf(alternatives);
			if(alternatives.isEmpty() || variable.domainSize() != alternatives.size())
				throw new IllegalArgumentException("EXACT_PHYSICAL_DOMAIN_INVALID");
			if(alternatives.stream().anyMatch(alternative -> alternative.decision() != node.key()
				|| node.legalAlternatives().stream().noneMatch(state -> state == alternative.state())))
				throw new IllegalArgumentException("EXACT_PHYSICAL_DOMAIN_STATE_IDENTITY");
		}
	}

	record SelectedCandidate(CompiledHopKey decision, CandidateRuleFact rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause supportClause, List<InputAuthority> inputAuthorities) {
		SelectedCandidate { inputAuthorities = List.copyOf(inputAuthorities); }
	}

	record PhysicalSelection(List<Alternative> alternativesInDecisionOrder,
		List<SelectedCandidate> candidates, List<RelocationAction> relocationActions) {
		PhysicalSelection {
			alternativesInDecisionOrder = List.copyOf(alternativesInDecisionOrder);
			candidates = List.copyOf(candidates);
			relocationActions = List.copyOf(relocationActions);
		}
	}

	record CandidateRuleLookupStatistics(long ownerLookups, long candidateFactsExamined) { }
	record RealizationSupportPreparationStatistics(int requiredSupportDerivations,
		int canonicalReferenceDerivations, int structuralReferenceHandles) { }
	record InputAuthorityPreparationStatistics(int factorCount, int consumerAlternativeRows,
		int indexedDirectFoutRows, int canonicalReceiptDerivations) { }
	record RelocationObligationIndexStatistics(int actionCount, int obligationCount,
		int lookupKeyCount, long lookups, long returnedActionReferences,
		long indexConstructionEntriesVisited, long legacyFullScanActionVisits) { }
	record HardFactorizationStatistics(int canonicalFactors, int factorizedFactors,
		long canonicalCells, long encodedCells, int auxiliaryVariables,
		boolean canonicalCellsOverflow, boolean encodedCellsOverflow,
		int realizationSupportFactors, long realizationSupportCanonicalCells,
		long realizationSupportEncodedCells, int inputAuthorityFactors,
		long inputAuthorityCanonicalCells, long inputAuthorityEncodedCells) { }
	record HardFactorEncoding(int canonicalOrdinal, ExactCategoricalSolver.Factor canonicalFactor,
		ExactHardFactorObservationDecomposition.Result decomposition) { }
	private record InputAuthorityFactorRow(InputAuthority authority,
		List<RelocationAction> directFoutActions, boolean exactDirectFoutActionMatched,
		boolean relocationInvariantSatisfied) {
		private InputAuthorityFactorRow {
			directFoutActions = List.copyOf(directFoutActions);
		}
	}
	private static final class IdentityObservation {
		private final Object value;
		private IdentityObservation(Object value) { this.value = value; }
		@Override public boolean equals(Object other) {
			return other instanceof IdentityObservation observation && value == observation.value;
		}
		@Override public int hashCode() { return System.identityHashCode(value); }
	}
	private record BindingObservation(PlacementIdentity.CandidateInputBindingKind kind,
		int inputPosition, CandidateRealizationReference source,
		PlacementIdentity.RelocationActionKey relocationAction) { }
	private record ReceiptObservation(IdentityObservation owner,
		CandidateRealizationReference realization, List<BindingObservation> bindings,
		DurableAnchorKey provenWorkerPool, DurableAnchorKey nativeResidency,
		IdentityObservation derivedAction) { }
	private record StateObservation(PlacementState state,
		List<Integer> derivedTargetIdentities) { }
	private record InputAuthorityObservation(StateObservation state,
		ReceiptObservation receipt, InputAuthorityKind kind, FType expectedFType,
		IdentityObservation sourceDecision, IdentityObservation relocationAction,
		List<IdentityObservation> directFoutActions, boolean exactDirectFoutActionMatched,
		boolean relocationInvariantSatisfied, boolean unconstrained) { }
	private interface CandidateRuleLookup {
		List<CandidateRuleFact> rulesFor(Node node);
		long factsExamined(Node node, List<CandidateRuleFact> ownerRules);
	}

	private static final class RelocationObligationLookupKey {
		private final ValueVersionKey sourceValueVersion;
		private final FType materializationFType;
		private final CompiledHopKey consumer;
		private final int inputPosition;
		private final PlacementState requiredPlacement;
		private final int hash;

		private RelocationObligationLookupKey(ValueVersionKey sourceValueVersion,
			FType materializationFType, CompiledHopKey consumer, int inputPosition,
			PlacementState requiredPlacement) {
			this.sourceValueVersion = Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			this.materializationFType = Objects.requireNonNull(materializationFType,
				"materializationFType");
			this.consumer = Objects.requireNonNull(consumer, "consumer");
			this.inputPosition = inputPosition;
			this.requiredPlacement = Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			hash = 31 * (31 * (31 * (31 * sourceValueVersion.hashCode()
				+ materializationFType.hashCode()) + System.identityHashCode(consumer))
				+ inputPosition) + requiredPlacement.hashCode();
		}

		@Override public int hashCode() { return hash; }

		@Override public boolean equals(Object other) {
			return this == other || other instanceof RelocationObligationLookupKey that
				&& sourceValueVersion.equals(that.sourceValueVersion)
				&& materializationFType == that.materializationFType
				&& consumer == that.consumer && inputPosition == that.inputPosition
				&& requiredPlacement.equals(that.requiredPlacement);
		}
	}

	/** Exact build-local projection of immutable relocation obligations. */
	static final class RelocationObligationIndex {
		private final Map<RelocationObligationLookupKey,List<RelocationAction>> actionsByObligation;
		private final int actionCount;
		private final int obligationCount;
		private long lookups;
		private long returnedActionReferences;

		private RelocationObligationIndex(
			Map<RelocationObligationLookupKey,List<RelocationAction>> actionsByObligation,
			int actionCount, int obligationCount) {
			this.actionsByObligation = actionsByObligation;
			this.actionCount = actionCount;
			this.obligationCount = obligationCount;
		}

		static RelocationObligationIndex build(List<RelocationAction> canonicalActions) {
			Objects.requireNonNull(canonicalActions, "canonicalActions");
			Map<RelocationObligationLookupKey,List<RelocationAction>> mutable = new HashMap<>();
			int obligations = 0;
			for(RelocationAction action : canonicalActions) {
				Objects.requireNonNull(action, "canonicalActions entry");
				obligations += action.obligations().size();
				HashSet<RelocationObligationLookupKey> actionKeys = new HashSet<>();
				for(var obligation : action.obligations()) {
					RelocationObligationLookupKey key = new RelocationObligationLookupKey(
						action.key().sourceValueVersion(), action.key().materializationFType(),
						obligation.consumer(), obligation.inputPosition(), obligation.requiredPlacement());
					if(actionKeys.add(key))
						mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(action);
				}
			}
			Map<RelocationObligationLookupKey,List<RelocationAction>> frozen = new HashMap<>();
			mutable.forEach((key, actions) -> frozen.put(key, List.copyOf(actions)));
			return new RelocationObligationIndex(Map.copyOf(frozen), canonicalActions.size(), obligations);
		}

		List<RelocationAction> actions(ValueVersionKey sourceValueVersion,
			FType materializationFType, CompiledHopKey consumer, int inputPosition,
			PlacementState requiredPlacement) {
			lookups++;
			// CandidateInputState permits a present, untyped operand. The legacy enum
			// comparison simply matched no relocation action for a null input FType.
			if(actionCount == 0 || materializationFType == null)
				return List.of();
			List<RelocationAction> actions = actionsByObligation.getOrDefault(
				new RelocationObligationLookupKey(sourceValueVersion, materializationFType,
					consumer, inputPosition, requiredPlacement), List.of());
			returnedActionReferences += actions.size();
			return actions;
		}

		RelocationObligationIndexStatistics statistics() {
			return new RelocationObligationIndexStatistics(actionCount, obligationCount,
				actionsByObligation.size(), lookups, returnedActionReferences,
				(long) actionCount + obligationCount,
				saturatedMultiply(lookups, actionCount));
		}
	}

	private static final class AlternativeSignatureContext {
		private final NormalizedTextContext canonical = new NormalizedTextContext();
		private final Map<Object,NormalizedText> literals = new IdentityHashMap<>();
		private NormalizedText literal(Object owner, java.util.function.Supplier<String> value) {
			return literals.computeIfAbsent(owner, ignored -> NormalizedText.literal(value.get()));
		}
	}

	private static final class MutableCandidateRuleLookupStatistics {
		private long ownerLookups;
		private long candidateFactsExamined;

		void record(long factsExamined) {
			ownerLookups++;
			candidateFactsExamined += factsExamined;
		}

		CandidateRuleLookupStatistics snapshot() {
			return new CandidateRuleLookupStatistics(ownerLookups, candidateFactsExamined);
		}
	}

	private final PlacementAnalysis analysis;
	private final List<DecisionDomain> domains;
	private final List<ExactCategoricalSolver.Factor> hardFactors;
	private final Map<CompiledHopKey,DecisionDomain> byDecision;
	private final CandidateRuleLookupStatistics candidateRuleLookupStatistics;
	private final RealizationSupportPreparationStatistics realizationSupportPreparationStatistics;
	private final InputAuthorityPreparationStatistics inputAuthorityPreparationStatistics;
	private final RelocationObligationIndexStatistics relocationObligationIndexStatistics;
	private final List<ExactCategoricalSolver.Variable> exactSolverAuxiliaryVariables;
	private final List<ExactCategoricalSolver.Factor> exactSolverHardFactors;
	private final HardFactorizationStatistics hardFactorizationStatistics;
	private final List<HardFactorEncoding> hardFactorEncodings;
	private final ExactPhysicalNativeSupplyRepresentation nativeSupplyRepresentation;

	private ExactPhysicalModel(PlacementAnalysis analysis, List<DecisionDomain> domains,
		List<ExactCategoricalSolver.Factor> hardFactors,
		CandidateRuleLookupStatistics candidateRuleLookupStatistics,
		RealizationSupportPreparationStatistics realizationSupportPreparationStatistics,
		InputAuthorityPreparationStatistics inputAuthorityPreparationStatistics,
		RelocationObligationIndexStatistics relocationObligationIndexStatistics,
		List<ExactCategoricalSolver.Variable> exactSolverAuxiliaryVariables,
		List<ExactCategoricalSolver.Factor> exactSolverHardFactors,
		HardFactorizationStatistics hardFactorizationStatistics,
		List<HardFactorEncoding> hardFactorEncodings) {
		this.analysis = analysis;
		this.domains = List.copyOf(domains);
		this.hardFactors = List.copyOf(hardFactors);
		this.candidateRuleLookupStatistics = candidateRuleLookupStatistics;
		this.realizationSupportPreparationStatistics = realizationSupportPreparationStatistics;
		this.inputAuthorityPreparationStatistics = inputAuthorityPreparationStatistics;
		this.relocationObligationIndexStatistics = relocationObligationIndexStatistics;
		this.exactSolverAuxiliaryVariables = List.copyOf(exactSolverAuxiliaryVariables);
		this.exactSolverHardFactors = List.copyOf(exactSolverHardFactors);
		this.hardFactorizationStatistics = hardFactorizationStatistics;
		this.hardFactorEncodings = List.copyOf(hardFactorEncodings);
		Map<CompiledHopKey,DecisionDomain> indexed = new IdentityHashMap<>();
		for(DecisionDomain domain : domains)
			indexed.put(domain.node().key(), domain);
		this.byDecision = indexed;
		this.nativeSupplyRepresentation = ExactPhysicalNativeSupplyRepresentation.build(this);
	}

	static ExactPhysicalModel build(PlacementAnalysis analysis) {
		return build(analysis, indexedCandidateRuleLookup(analysis), true, true, true, true);
	}

	/** Legacy whole-universe lookup retained only as a focused parity oracle for R1-A. */
	static ExactPhysicalModel buildWithLegacyCandidateRuleScanForTest(PlacementAnalysis analysis) {
		return build(analysis, new CandidateRuleLookup() {
			@Override
			public List<CandidateRuleFact> rulesFor(Node node) {
				return analysis.candidateRuleFacts().orderedFacts().stream()
					.filter(rule -> rule.key().parentOccurrence() == node.key()).toList();
			}

			@Override
			public long factsExamined(Node node, List<CandidateRuleFact> ownerRules) {
				return analysis.candidateRuleFacts().orderedFacts().size();
			}
		}, true, true, true, true);
	}

	/** Allocation-heavy evaluator retained only as a deterministic R1 parity oracle. */
	static ExactPhysicalModel buildWithAllocatingInputAuthorityEvaluationForTest(PlacementAnalysis analysis) {
		return build(analysis, indexedCandidateRuleLookup(analysis), true, false, true, true);
	}

	/** Full authority products retained only as a structural R1 legality oracle. */
	static ExactPhysicalModel buildWithLegacyInputAuthorityProductsForTest(PlacementAnalysis analysis) {
		return build(analysis, indexedCandidateRuleLookup(analysis), false, true, true, true);
	}

	/** Eager flat signatures retained only as the exact R5 compatibility oracle. */
	static ExactPhysicalModel buildWithEagerAlternativeSignaturesForTest(PlacementAnalysis analysis) {
		return build(analysis, indexedCandidateRuleLookup(analysis), true, true, false, true);
	}

	/** Full relocation inventory scan retained only as an exact R10 parity oracle. */
	static ExactPhysicalModel buildWithLegacyRelocationActionScanForTest(PlacementAnalysis analysis) {
		return buildWithLegacyRelocationActionScanForTest(analysis, true);
	}

	static ExactPhysicalModel buildWithLegacyRelocationActionScanForTest(PlacementAnalysis analysis,
		boolean prunePrivacyIllegalRelocations) {
		return build(analysis, indexedCandidateRuleLookup(analysis),
			prunePrivacyIllegalRelocations, true, true, false);
	}

	private static CandidateRuleLookup indexedCandidateRuleLookup(PlacementAnalysis analysis) {
		return new CandidateRuleLookup() {
			@Override
			public List<CandidateRuleFact> rulesFor(Node node) {
				return analysis.candidateRuleFacts().orderedFactsForParent(node.key());
			}

			@Override
			public long factsExamined(Node node, List<CandidateRuleFact> ownerRules) {
				return ownerRules.size();
			}
		};
	}

	private static ExactPhysicalModel build(PlacementAnalysis analysis, CandidateRuleLookup ruleLookup,
		boolean prunePrivacyIllegalRelocations, boolean allocationFreeInputAuthorityEvaluation,
		boolean lazyAlternativeSignatures, boolean indexedRelocationObligations) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(ruleLookup, "ruleLookup");
		analysis.assertProgramStructureUnchanged();
		// Synthetic function boundaries are planner-visible legality variables even
		// though they have no concrete Hop mutation. Omitting them dropped the
		// source->boundary->formal constraints and let the optimizer accept assignments
		// that no shared planner could project (notably StepLM's shared y formal).
		List<Node> nodes = analysis.graph().decisionNodes();
		Map<CompiledHopKey,List<Link>> incoming = incomingLinks(analysis);
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy =
			RelocationSelections.relocationPrivacyIndex(analysis, analysis.graph(),
				analysis.graph().relocationActions());
		RelocationObligationIndex relocationObligations = indexedRelocationObligations
			? RelocationObligationIndex.build(analysis.graph().relocationActions()) : null;
		List<DecisionDomain> domains = new ArrayList<>(nodes.size());
		MutableCandidateRuleLookupStatistics lookupStatistics =
			new MutableCandidateRuleLookupStatistics();
		for(Node node : nodes) {
			List<CandidateRuleFact> ownerRules = isSyntheticFunctionBoundary(node) ? List.of()
				: ruleLookup.rulesFor(node);
			if(!isSyntheticFunctionBoundary(node))
				lookupStatistics.record(ruleLookup.factsExamined(node, ownerRules));
			List<Alternative> alternatives = alternatives(analysis, relocationPrivacy,
				relocationObligations,
				prunePrivacyIllegalRelocations, node,
				incoming.getOrDefault(node.key(), List.of()), ownerRules, lazyAlternativeSignatures);
			ExactCategoricalSolver.Variable variable = new ExactCategoricalSolver.Variable(
				node.key().normalizedSignature(), alternatives.size());
			domains.add(new DecisionDomain(node, variable, alternatives));
		}
		Map<CompiledHopKey,DecisionDomain> byDecision = new IdentityHashMap<>();
		for(DecisionDomain domain : domains)
			byDecision.put(domain.node().key(), domain);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result>
			hardFactorizations = new IdentityHashMap<>();
		addNeutralConstraintFactors(analysis.graph(), byDecision, factors);
		addStrictTransientFactors(analysis, byDecision, factors);
		addJointFactors(analysis, byDecision, factors, hardFactorizations);
		addLogicalBoundaryFactors(analysis, byDecision, factors);
		RealizationSupportPreparationStatistics realizationSupportStatistics =
			addRealizationSupportFactors(analysis, byDecision, factors, hardFactorizations);
		addDerivedFoutAnchorFactors(analysis.graph(), byDecision, factors);
		InputAuthorityPreparationStatistics inputAuthorityStatistics =
			addInputAuthorityFactors(analysis, relocationPrivacy, incoming, byDecision, factors,
				allocationFreeInputAuthorityEvaluation, hardFactorizations);
		addLatentWdivmmRuntimeInputFactors(analysis, byDecision, factors);
		List<ExactCategoricalSolver.Variable> exactAuxiliaries = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> exactHardFactors = new ArrayList<>();
		long canonicalFactorCells = 0;
		long encodedFactorCells = 0;
		boolean canonicalCellsOverflow = false;
		boolean encodedCellsOverflow = false;
		int realizationSupportFactors = 0;
		long realizationSupportCanonicalCells = 0;
		long realizationSupportEncodedCells = 0;
		int inputAuthorityFactors = 0;
		long inputAuthorityCanonicalCells = 0;
		long inputAuthorityEncodedCells = 0;
		List<HardFactorEncoding> hardFactorEncodings = new ArrayList<>();
		for(int factorOrdinal = 0; factorOrdinal < factors.size(); factorOrdinal++) {
			ExactCategoricalSolver.Factor factor = factors.get(factorOrdinal);
			long cells = factor.scope().stream().mapToLong(
				ExactCategoricalSolver.Variable::domainSize).reduce(1L, ExactPhysicalModel::saturatedMultiply);
			canonicalCellsOverflow |= cells == Long.MAX_VALUE
				|| canonicalFactorCells > Long.MAX_VALUE - cells;
			canonicalFactorCells = saturatedAdd(canonicalFactorCells, cells);
			var factorization = hardFactorizations.get(factor);
			if(factorization == null) {
				exactHardFactors.add(factor);
				encodedCellsOverflow |= cells == Long.MAX_VALUE
					|| encodedFactorCells > Long.MAX_VALUE - cells;
				encodedFactorCells = saturatedAdd(encodedFactorCells, cells);
			}
			else {
				hardFactorEncodings.add(new HardFactorEncoding(factorOrdinal, factor, factorization));
				if(factorization.descriptor().startsWith("realization-support|")) {
					realizationSupportFactors++;
					realizationSupportCanonicalCells = saturatedAdd(
						realizationSupportCanonicalCells, cells);
					realizationSupportEncodedCells = saturatedAdd(
						realizationSupportEncodedCells, factorization.encodedCells());
				}
				else if(factorization.descriptor().startsWith("input-authority|")) {
					inputAuthorityFactors++;
					inputAuthorityCanonicalCells = saturatedAdd(inputAuthorityCanonicalCells, cells);
					inputAuthorityEncodedCells = saturatedAdd(
						inputAuthorityEncodedCells, factorization.encodedCells());
				}
				exactAuxiliaries.addAll(factorization.auxiliaryVariables());
				exactHardFactors.addAll(factorization.solverFactors());
				encodedCellsOverflow |= factorization.encodedCells() == Long.MAX_VALUE
					|| encodedFactorCells > Long.MAX_VALUE - factorization.encodedCells();
				encodedFactorCells = saturatedAdd(encodedFactorCells, factorization.encodedCells());
			}
		}
		HardFactorizationStatistics hardFactorizationStatistics = new HardFactorizationStatistics(
			factors.size(), hardFactorizations.size(), canonicalFactorCells, encodedFactorCells,
			exactAuxiliaries.size(), canonicalCellsOverflow, encodedCellsOverflow,
			realizationSupportFactors, realizationSupportCanonicalCells,
			realizationSupportEncodedCells, inputAuthorityFactors,
			inputAuthorityCanonicalCells, inputAuthorityEncodedCells);
		return new ExactPhysicalModel(analysis, domains, factors, lookupStatistics.snapshot(),
			realizationSupportStatistics, inputAuthorityStatistics,
			relocationObligations == null
				? new RelocationObligationIndexStatistics(analysis.graph().relocationActions().size(),
					analysis.graph().relocationActions().stream()
						.mapToInt(action -> action.obligations().size()).sum(), 0, 0, 0, 0, 0)
				: relocationObligations.statistics(), exactAuxiliaries,
			exactHardFactors, hardFactorizationStatistics, hardFactorEncodings);
	}

	private static long saturatedMultiply(long left, long right) {
		return left == 0 || right == 0 ? 0 : left > Long.MAX_VALUE / right
			? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static boolean isSyntheticFunctionBoundary(Node node) {
		return node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
			|| node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT;
	}

	List<DecisionDomain> domains() { return domains; }
	ExactPhysicalNativeSupplyRepresentation nativeSupplyRepresentation() {
		return nativeSupplyRepresentation;
	}
	PlacementAnalysis analysis() { return analysis; }
	CandidateRuleLookupStatistics candidateRuleLookupStatistics() {
		return candidateRuleLookupStatistics;
	}
	RealizationSupportPreparationStatistics realizationSupportPreparationStatistics() {
		return realizationSupportPreparationStatistics;
	}
	InputAuthorityPreparationStatistics inputAuthorityPreparationStatistics() {
		return inputAuthorityPreparationStatistics;
	}
	RelocationObligationIndexStatistics relocationObligationIndexStatistics() {
		return relocationObligationIndexStatistics;
	}
	List<ExactCategoricalSolver.Variable> exactSolverAuxiliaryVariables() {
		return exactSolverAuxiliaryVariables;
	}
	List<ExactCategoricalSolver.Factor> exactSolverHardFactors() { return exactSolverHardFactors; }
	HardFactorizationStatistics hardFactorizationStatistics() { return hardFactorizationStatistics; }
	List<HardFactorEncoding> hardFactorEncodings() { return hardFactorEncodings; }
	List<String> exactSolverHardFactorDescriptors() {
		return hardFactorEncodings.stream().map(encoding -> "ordinal="
			+ encoding.canonicalOrdinal() + '|' + encoding.decomposition().descriptor()).toList();
	}
	List<ExactCategoricalSolver.Variable> variables() {
		return domains.stream().map(DecisionDomain::variable).toList();
	}
	List<ExactCategoricalSolver.Factor> hardFactors() { return hardFactors; }
	boolean costSurfaceComplete() { return false; }
	String missingCostSurface() {
		return "EXACT_PHYSICAL_CANONICAL_COST_FACTORS_NOT_SUPPLIED_BY_PRODUCER";
	}
	Alternative alternative(CompiledHopKey decision, int value) {
		DecisionDomain domain = byDecision.get(decision);
		if(domain == null || value < 0 || value >= domain.alternatives().size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_ALTERNATIVE_UNKNOWN");
		return domain.alternatives().get(value);
	}
	ExactCategoricalSolver.Statistics analyze(ExactCategoricalSolver.Limits limits) {
		return ExactCategoricalSolver.analyze(variables(), hardFactors, limits);
	}
	ExactCategoricalSolver.Result solveLegalityOnly(ExactCategoricalSolver.Limits limits) {
		return ExactCategoricalSolver.solve(variables(), hardFactors, limits);
	}
	PhysicalSelection physicalSelection(ExactCategoricalSolver.Result result) {
		Objects.requireNonNull(result, "result");
		if(result.assignmentInVariableOrder().size() != domains.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_SELECTION_SIZE_MISMATCH");
		List<Alternative> selected = new ArrayList<>(domains.size());
		List<SelectedCandidate> candidates = new ArrayList<>();
		Map<String,RelocationAction> relocations = new LinkedHashMap<>();
		for(int index = 0; index < domains.size(); index++) {
			Alternative alternative = domains.get(index).alternatives()
				.get(result.assignmentInVariableOrder().get(index));
			selected.add(alternative);
			CandidateRuleFact rule = alternative.captured()
				? alternative.candidateRule() : alternative.executionRule();
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			if(rule != null && emission != null)
				candidates.add(new SelectedCandidate(alternative.decision(), rule, emission,
					alternative.realization(), alternative.supportClause(), alternative.inputAuthorities()));
			if(alternative.relocationAction() != null)
				relocations.put(alternative.relocationAction().normalizedSignature(),
					alternative.relocationAction());
			for(InputAuthority authority : alternative.inputAuthorities())
				if(authority.relocationAction() != null)
					relocations.put(authority.relocationAction().normalizedSignature(),
						authority.relocationAction());
		}
		return new PhysicalSelection(selected, candidates,
			relocations.values().stream().sorted().toList());
	}

	private static List<Alternative> alternatives(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		RelocationObligationIndex relocationObligations,
		boolean prunePrivacyIllegalRelocations, Node node, List<Link> incoming,
		List<CandidateRuleFact> ownerRules, boolean lazyAlternativeSignatures) {
		List<Alternative> alternatives = new ArrayList<>();
		AlternativeSignatureContext signatureContext = lazyAlternativeSignatures
			? new AlternativeSignatureContext() : null;
		if(isSyntheticFunctionBoundary(node)) {
			for(PlacementState state : node.legalAlternatives())
				alternatives.add(nonCandidate(node, state, AuthorityKind.SYNTHETIC_BOUNDARY,
					null, null));
			return alternatives;
		}
		for(CandidateRuleFact rule : ownerRules) {
			if(rule.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : rule.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
			for(var supportClause : realization.supportClauses()) {
				PlacementState state = emission.emissionState().placementState();
				if(node.legalAlternatives().stream().noneMatch(legal -> legal == state))
					continue;
				List<DerivedFoutMaterializationAction> outputActions =
					foutMaterializationActions(analysis, node, rule, emission);
				if(emission.derivedFoutAction() != null) {
					for(DerivedFoutMaterializationAction outputAction : outputActions)
						for(List<InputAuthority> bindings : inputAuthorityProducts(
							analysis, relocationPrivacy, relocationObligations,
							prunePrivacyIllegalRelocations,
							rule, state, incoming, supportClause))
							alternatives.add(candidate(node, state, rule, emission, realization,
								supportClause, outputAction, bindings, signatureContext));
				}
				else
					for(List<InputAuthority> bindings : inputAuthorityProducts(analysis, relocationPrivacy,
						relocationObligations,
						prunePrivacyIllegalRelocations, rule, state, incoming, supportClause))
						alternatives.add(candidate(node, state, rule, emission, realization,
								supportClause, null, bindings, signatureContext));
			}
		}

		for(PlacementState state : node.legalAlternatives()) {
			if(isFederatedSource(analysis, node))
				for(DurableAnchorKey anchor : node.anchors())
					if(state.output() == FederatedOutput.FOUT && state.fType() == anchor.fType()) {
						boolean capturedState = alternatives.stream().anyMatch(alternative ->
							alternative.captured() && alternative.state().equals(state));
						if(capturedState) {
							// Candidate realizations already represent this exact source map.
							// A second receipt-free choice would lose its identity after solve.
							if(alternatives.stream().noneMatch(alternative -> alternative.captured()
								&& alternative.state().equals(state) && alternative.realization().anchor() != null
								&& PlacementIdentity.samePhysicalLayout(alternative.realization().anchor(), anchor)))
								throw new IllegalArgumentException("EXACT_SOURCE_MAP_REALIZATION_MISSING");
						}
						else
							alternatives.add(nonCandidate(node, state, AuthorityKind.DURABLE_ANCHOR,
								anchor, null));
					}
			for(RelocationAction action : analysis.graph().relocationActions()) {
				if(!action.key().sourceValueVersion().equals(node.valueVersion())
					|| !action.key().targetPlacement().equals(state))
					continue;
				if(alternatives.stream().anyMatch(candidate -> candidate.state().equals(state)
					&& candidate.captured() && candidate.candidateEmission().emissionState().derivedFedFout()))
					continue;
				List<Alternative> executions = alternatives.stream().filter(candidate -> candidate.captured()
					&& candidate.state().execType() == ExecType.FED
					&& candidate.state().output() == FederatedOutput.LOUT).toList();
				if(state.execType() == ExecType.FED) {
					for(Alternative execution : executions)
						// A relocation cannot change the selected execution emission: the
						// final selection requires its placement state to be identical.
						// Otherwise the hard-factor model admits a tuple that create() rejects.
						if(execution.candidateEmission().emissionState().placementState() == state)
							alternatives.add(nonCandidate(node, state, AuthorityKind.RELOCATION_SOURCE,
								action.key().durableAnchor(), action, execution.candidateRule(),
								execution.candidateEmission(), execution.realization(), execution.supportClause(), execution.inputAuthorities()));
				}
				else
					alternatives.add(nonCandidate(node, state, AuthorityKind.RELOCATION_SOURCE,
						action.key().durableAnchor(), action));
			}
			long membershipStates = node.legalAlternatives().stream().filter(candidate ->
				candidate.execType() == state.execType() && candidate.output() == state.output()).count();
			boolean legalSingleton = membershipStates == 1 && state.output() == FederatedOutput.LOUT
				&& (state.execType() != ExecType.FED || !hasAuthorityBearingInputs(analysis, node.key()));
			if(legalSingleton && alternatives.stream().noneMatch(candidate -> candidate.state().equals(state)))
				alternatives.add(nonCandidate(node, state, AuthorityKind.LEGAL_SINGLETON,
					null, null));
		}
		List<Alternative> unique;
		if(lazyAlternativeSignatures) {
			unique = sortAndDeduplicateAlternatives(alternatives);
		}
		else {
			Map<String,Alternative> eagerUnique = new LinkedHashMap<>();
			for(Alternative alternative : alternatives)
				eagerUnique.putIfAbsent(alternative.signature(), alternative);
			unique = eagerUnique.values().stream()
				.sorted(Comparator.comparing(Alternative::signature)).toList();
		}
		if(unique.isEmpty())
			throw new IllegalArgumentException("EXACT_PHYSICAL_DOMAIN_EMPTY|key="
				+ node.key().normalizedSignature() + "|legal=" + node.legalAlternatives()
				+ "|candidates=" + ownerRules.stream()
					.map(fact -> fact.key().orderedInputs() + ":" + fact.status() + ":"
						+ fact.allowedEmissionFacts().stream().map(emission -> emission.selectionSignature()
							+ " realizations=" + emission.realizations().size()).toList()).toList());
		return List.copyOf(unique);
	}

	static List<Alternative> sortAndDeduplicateAlternatives(List<Alternative> generated) {
		generated.sort(Comparator.comparing(Alternative::normalizedSignature));
		List<Alternative> unique = new ArrayList<>(generated.size());
		NormalizedText previous = null;
		for(Alternative alternative : generated)
			if(previous == null || !previous.equals(alternative.normalizedSignature())) {
				unique.add(alternative);
				previous = alternative.normalizedSignature();
			}
		return List.copyOf(unique);
	}

	private static List<DerivedFoutMaterializationAction> foutMaterializationActions(
		PlacementAnalysis analysis, Node node, CandidateRuleFact rule, CandidateEmissionFact emission) {
		if(emission.derivedFoutAction() == null)
			return List.of();
		return analysis.graph().derivedFoutMaterializationActions().stream()
			.filter(action -> action.key().equals(emission.derivedFoutAction()))
			.filter(action -> action.key().producer() == node.key()
				&& action.key().candidateRule() == rule.key()
				&& action.key().targetPlacement() == emission.emissionState().placementState())
			.sorted().toList();
	}

	private static boolean isFederatedSource(PlacementAnalysis analysis, Node node) {
		return analysis.hop(node.key()).orElseThrow() instanceof DataOp data
			&& data.getOp() == OpOpData.FEDERATED;
	}

	private static boolean hasAuthorityBearingInputs(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge -> edge.consumer() == key)
			|| analysis.logicalTransientInputsInCanonicalOrder().stream().anyMatch(input -> input.targetRead() == key)
			|| analysis.logicalFunctionInputsInCanonicalOrder().stream().anyMatch(input -> input.targetRead() == key);
	}

	private static Alternative candidate(Node node, PlacementState state, CandidateRuleFact rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause supportClause,
		DerivedFoutMaterializationAction outputAction,
		List<InputAuthority> bindings, AlternativeSignatureContext context) {
		if(context == null) {
			String signature = "CAPTURED|" + state.normalizedSignature() + "|rule="
				+ rule.key().normalizedSignature() + "|emission=" + emission.selectionSignature()
				+ "|realization=" + realization.key().normalizedSignature() + "|clause="
				+ supportClause.normalizedSignature() + "|foutMaterializationAction="
				+ (outputAction == null ? "-" : outputAction.normalizedSignature())
				+ "|inputs=" + bindings.stream().map(InputAuthority::signature).toList();
			return new Alternative(node.key(), state, AuthorityKind.CAPTURED_RULE, rule, emission,
				null, null, null, null, outputAction, rule.key().orderedInputs(), bindings,
				realization, supportClause, signature);
		}
		NormalizedTextBuilder signature = new NormalizedTextBuilder().append("CAPTURED|")
			.append(context.literal(state, state::normalizedSignature)).append("|rule=")
			.append(context.canonical.candidateRule(rule.key())).append("|emission=")
			.append(context.literal(emission, emission::selectionSignature)).append("|realization=")
			.append(context.canonical.realizationKey(realization.key())).append("|clause=")
			.append(context.canonical.supportClause(supportClause))
			.append("|foutMaterializationAction=");
		if(outputAction == null)
			signature.append("-");
		else
			signature.append(context.literal(outputAction, outputAction::normalizedSignature));
		signature.append("|inputs=[");
		for(int index = 0; index < bindings.size(); index++) {
			if(index > 0)
				signature.append(", ");
			InputAuthority authority = bindings.get(index);
			signature.append(context.literal(authority, authority::signature));
		}
		signature.append("]");
		return new Alternative(node.key(), state, AuthorityKind.CAPTURED_RULE, rule, emission,
			null, null, null, null, outputAction, rule.key().orderedInputs(), bindings, realization,
			supportClause, signature.build());
	}

	private static Alternative nonCandidate(Node node, PlacementState state, AuthorityKind kind,
		DurableAnchorKey anchor, RelocationAction action) {
		return nonCandidate(node, state, kind, anchor, action, null, null, null, null, List.of());
	}

	private static Alternative nonCandidate(Node node, PlacementState state, AuthorityKind kind,
		DurableAnchorKey anchor, RelocationAction action, CandidateRuleFact executionRule,
		CandidateEmissionFact executionEmission, CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause supportClause,
		List<InputAuthority> bindings) {
		String signature = kind + "|" + state.normalizedSignature()
			+ "|anchor=" + (anchor == null ? "-" : anchor.normalizedSignature())
			+ "|action=" + (action == null ? "-" : action.normalizedSignature())
			+ "|executionRule=" + (executionRule == null ? "-" : executionRule.key().normalizedSignature())
			+ "|executionEmission=" + (executionEmission == null ? "-" : executionEmission.selectionSignature())
			+ "|realization=" + (realization == null ? "-" : realization.key().normalizedSignature())
			+ "|clause=" + (supportClause == null ? "-" : supportClause.normalizedSignature())
			+ "|inputs=" + bindings.stream().map(InputAuthority::signature).toList();
		return new Alternative(node.key(), state, kind, null, null, executionRule, executionEmission,
			anchor, action, null, executionRule == null ? List.of() : executionRule.key().orderedInputs(), bindings,
			realization, supportClause, signature);
	}

	private static List<List<InputAuthority>> inputAuthorityProducts(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		RelocationObligationIndex relocationObligations,
		boolean prunePrivacyIllegalRelocations, CandidateRuleFact rule,
		PlacementState targetState, List<Link> incoming,
		PlacementAnalysis.CandidateRealizationSupportClause supportClause) {
		List<List<List<InputAuthority>>> choices = new ArrayList<>();
		boolean consumesFederationMap = targetState.execType() == ExecType.FED
			&& !analysis.isDmlFunctionCallBoundary(rule.key().parentOccurrence());
		long presentPhysicalInputs = java.util.stream.IntStream.range(0,
			rule.key().orderedInputs().size()).filter(position ->
				rule.key().orderedInputs().get(position).present()
					&& incoming.stream().anyMatch(link -> link.position == position)).count();
		for(int position = 0; position < rule.key().orderedInputs().size(); position++) {
			final int inputPosition = position;
			CandidateInputState input = rule.key().orderedInputs().get(position);
			if(!input.present() || !consumesFederationMap) {
				// A CP instruction consumes a coordinator-local value, not a FederationMap.
				// Likewise a DML FunctionOp is only a call-site forwarding placeholder;
				// its actual/formal movement is owned by the logical function-boundary
				// factors.  In both cases source placement and transfer cost are still
				// modeled by their canonical boundary factors, but inventing a physical
				// input receipt here would incorrectly close otherwise legal CP rows or
				// duplicate the function transfer authority.
				choices.add(List.of(List.of(new InputAuthority(position,
					InputAuthorityKind.NATIVE_LOCAL, null, null, null))));
				continue;
			}
			List<Link> links = incoming.stream().filter(link -> link.position == inputPosition).toList();
			if(links.isEmpty()) {
				// Scalar/instruction operands do not own a matrix FederationMap receipt.
				choices.add(List.of(List.of(new InputAuthority(position,
					InputAuthorityKind.DIRECT_FOUT, input.fType(), null, null))));
				continue;
			}
			List<List<InputAuthority>> perLinkProducts = List.of(List.of());
			for(Link link : links) {
				List<RelocationAction> actions = link.kind == LinkKind.LOGICAL_TRANSIENT ? List.of()
					: relocationObligations != null ? relocationObligations.actions(
						link.sourceNode.valueVersion(), input.fType(), rule.key().parentOccurrence(),
						inputPosition, targetState)
					: analysis.graph().relocationActions().stream()
					.filter(action -> action.key().sourceValueVersion().equals(link.sourceNode.valueVersion()))
					.filter(action -> action.key().materializationFType() == input.fType())
					.filter(action -> action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == rule.key().parentOccurrence()
							&& obligation.inputPosition() == inputPosition
							&& obligation.requiredPlacement().equals(targetState)))
					.sorted().toList();
				List<InputAuthority> authorities = new ArrayList<>();
				for(RelocationAction action : actions) {
					authorities.add(new InputAuthority(position, InputAuthorityKind.DIRECT_FOUT,
						input.fType(), link.sourceNode.key(), action));
					// An active relocation from an origin-bound value is always rejected by
					// the hard factor. Its paired DIRECT_FOUT authority must remain: when the
					// exact source already resides on this pool the action is inactive and legal.
					if(!prunePrivacyIllegalRelocations || relocationPrivacy.isPrivacySafe(action, true))
						authorities.add(new InputAuthority(position, InputAuthorityKind.RELOCATION,
							input.fType(), link.sourceNode.key(), action));
				}
				if(authorities.isEmpty() && link.kind != LinkKind.COMPILED)
					authorities.add(new InputAuthority(position, InputAuthorityKind.DIRECT_FOUT,
						input.fType(), link.sourceNode.key(), null));
				if(authorities.isEmpty() && hasCompatibleFoutState(link.sourceNode, input.fType())
					&& supportClause.inputBindings().stream().anyMatch(binding ->
						binding.inputPosition() == inputPosition
							&& binding.source().rule().parentOccurrence() == link.sourceNode.key()
							&& binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT))
					// The selected realization is the exact authority for this compiled
					// FOUT edge. This is required for a multi-input VALUE_MAP consumer:
					// no single fixed relocation anchor exists across control-flow rows.
					authorities.add(new InputAuthority(position, InputAuthorityKind.DIRECT_FOUT,
						input.fType(), link.sourceNode.key(), null));
				if(authorities.isEmpty() && presentPhysicalInputs == 1
					&& hasCompatibleFoutState(link.sourceNode, input.fType()))
					// A unary FED instruction executes on its sole matrix input's selected
					// FederationMap. This applies to function formals and to transient reads
					// returned by a function. No separate relocation anchor is required: the
					// exact input factor below still requires this source occurrence to select
					// the compatible FOUT state. Requiring a precomputed relocation action here
					// silently removed legal FED/LOUT aggregates from dynamic pipelines.
					authorities.add(new InputAuthority(position, InputAuthorityKind.DIRECT_FOUT,
						input.fType(), link.sourceNode.key(), null));
				// Apply this clause before taking input products; do not multiply all
				// relocation routes and discard contradictory routes only after expansion.
				authorities.removeIf(authority -> supportClause.inputBindings().stream()
					.filter(binding -> binding.inputPosition() == inputPosition
						&& binding.source().rule().parentOccurrence() == link.sourceNode.key()
						&& binding.kind() != PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT)
					.anyMatch(binding -> !supportsInputBinding(authority, binding)));
				if(authorities.isEmpty()) {
					perLinkProducts = List.of();
					break;
				}
				List<List<InputAuthority>> expanded = new ArrayList<>();
				for(List<InputAuthority> prefix : perLinkProducts)
					for(InputAuthority authority : authorities) {
						List<InputAuthority> binding = new ArrayList<>(prefix);
						binding.add(authority);
						expanded.add(List.copyOf(binding));
					}
				perLinkProducts = List.copyOf(expanded);
			}
			choices.add(perLinkProducts);
		}
		List<List<InputAuthority>> products = new ArrayList<>();
		expandAuthorityGroups(choices, 0, new ArrayList<>(), products);
		return products;
	}

	private static boolean supportsInputBinding(InputAuthority input,
		PlacementIdentity.CandidateRealizationInputBinding binding) {
		return input.inputPosition() == binding.inputPosition()
			&& (input.sourceDecision() == binding.source().rule().parentOccurrence()
				|| input.kind() == InputAuthorityKind.NATIVE_LOCAL)
			&& (binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
				? input.kind() == InputAuthorityKind.RELOCATION
					&& input.relocationAction().key().equals(binding.relocationAction())
				: input.kind() != InputAuthorityKind.RELOCATION);
	}

	private static boolean hasCompatibleFoutState(Node source, FType expectedFType) {
		return source.legalAlternatives().stream().anyMatch(state ->
			state.output() == FederatedOutput.FOUT && state.fType() == expectedFType);
	}

	private static void expandAuthorityGroups(List<List<List<InputAuthority>>> choices, int index,
		List<InputAuthority> selected, List<List<InputAuthority>> result) {
		if(index == choices.size()) {
			if(hasOneExactConsumerAnchor(selected))
				result.add(List.copyOf(selected));
			return;
		}
		for(List<InputAuthority> group : choices.get(index)) {
			selected.addAll(group);
			expandAuthorityGroups(choices, index + 1, selected, result);
			for(int remove = 0; remove < group.size(); remove++)
				selected.remove(selected.size() - 1);
		}
	}

	static boolean hasOneExactConsumerAnchor(List<InputAuthority> authorities) {
		DurableAnchorKey anchor = null;
		for(InputAuthority authority : authorities) {
			if(authority.relocationAction() == null)
				continue;
			DurableAnchorKey current = authority.relocationAction().key().durableAnchor();
			if(anchor == null)
				anchor = current;
			else if(!PlacementIdentity.samePhysicalWorkerPool(anchor, current))
				return false;
		}
		return true;
	}

	private static Map<CompiledHopKey,List<Link>> incomingLinks(PlacementAnalysis analysis) {
		Map<CompiledHopKey,List<Link>> incoming = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
				.isLatentWdivmmTransposePairBoundary(analysis, edge.producer(), edge.consumer(),
					edge.inputPosition()))
				continue;
			// FunctionOp candidate rows describe the actual call-site argument layout and
			// therefore need the same exact source authority as every other compiled
			// consumer.  Logical function-input links below additionally constrain the
			// callee formals; they do not replace these physical call-site edges.
			Node source = analysis.graph().node(edge.producer()).orElseThrow();
			incoming.computeIfAbsent(edge.consumer(), ignored -> new ArrayList<>())
				.add(new Link(source, edge.consumer(), edge.inputPosition(), LinkKind.COMPILED));
		}
		for(var input : analysis.logicalTransientInputsInCanonicalOrder()) {
			Node source = analysis.graph().node(input.sourceWrite()).orElseThrow();
			incoming.computeIfAbsent(input.targetRead(), ignored -> new ArrayList<>())
				.add(new Link(source, input.targetRead(), input.logicalPosition(),
					LinkKind.LOGICAL_TRANSIENT));
		}
		for(var input : analysis.logicalFunctionInputsInCanonicalOrder()) {
			Node source = analysis.graph().node(input.sourceArgument()).orElseThrow();
			List<Link> links = incoming.computeIfAbsent(input.targetRead(), ignored -> new ArrayList<>());
			// One compiled formal is shared by every invocation. Repeated calls with the
			// same immutable actual therefore prove the same static placement authority;
			// call frequency and lifetime remain occurrence-specific in the boundary facts.
			boolean alreadyLinked = links.stream().anyMatch(link ->
				link.kind == LinkKind.LOGICAL_FUNCTION && link.sourceNode == source
					&& link.position == input.logicalPosition());
			if(!alreadyLinked)
				links.add(new Link(source, input.targetRead(), input.logicalPosition(),
					LinkKind.LOGICAL_FUNCTION));
		}
		return incoming;
	}

	/**
	 * The source inner-MM edge disappears when the transpose pair becomes WDivMM.
	 * Its exact runtime FederationMap input is the fused weight matrix instead, so a
	 * FED owner is legal only when that occurrence selects the proven ROW/COL FOUT.
	 */
	private static void addLatentWdivmmRuntimeInputFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains,
		List<ExactCategoricalSolver.Factor> factors) {
		for(Node ownerNode : analysis.graph().decisionNodes()) {
			DecisionDomain owner = domains.get(ownerNode.key());
			if(owner == null)
				continue;
			org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
				.LatentWdivmmTransposePairFact runtime =
				org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
					.latentWdivmmTransposePairFact(analysis, owner.node().key());
			if(runtime == null || runtime.partitionedInputFType() == null)
				continue;
			DecisionDomain weights = domains.get(runtime.weights());
			if(weights == null)
				throw new IllegalArgumentException(
					"EXACT_LATENT_WDIVMM_RUNTIME_INPUT_DOMAIN_MISSING|owner="
						+ owner.node().key().normalizedSignature());
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(owner.variable(), weights.variable()), values -> {
					PlacementState selectedOwner = owner.alternatives().get(values[0]).state();
					if(selectedOwner.execType() != ExecType.FED)
						return 0.0;
					PlacementState selectedWeights = weights.alternatives().get(values[1]).state();
					return selectedWeights.output() == FederatedOutput.FOUT
						&& selectedWeights.fType() == runtime.partitionedInputFType()
						? 0.0 : Double.POSITIVE_INFINITY;
					}));
		}
		for(Node ownerNode : analysis.graph().decisionNodes()) {
			DecisionDomain owner = domains.get(ownerNode.key());
			if(owner == null)
				continue;
			org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
				.DirectWdivmmRuntimeFact runtime =
				org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
					.directWdivmmRuntimeFact(analysis, owner.node().key());
			if(runtime == null || runtime.runtimeInputFType() == null)
				continue;
			DecisionDomain weights = domains.get(runtime.weights());
			if(weights == null)
				throw new IllegalArgumentException(
					"EXACT_DIRECT_WDIVMM_RUNTIME_INPUT_DOMAIN_MISSING|owner="
						+ owner.node().key().normalizedSignature());
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(owner.variable(), weights.variable()), values -> {
					Alternative selectedOwner = owner.alternatives().get(values[0]);
					CandidateEmissionFact emission = selectedOwner.captured()
						? selectedOwner.candidateEmission() : selectedOwner.executionEmission();
					return org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
						.directWdivmmRuntimeAssignmentCompatible(runtime,
							selectedOwner.state(), emission == null ? selectedOwner.state().fType()
								: emission.executionFType(), emission != null
									&& emission.emissionState().derivedFedFout(),
							weights.alternatives().get(values[1]).state())
							? 0.0 : Double.POSITIVE_INFINITY;
				}));
		}
	}

	private static void addNeutralConstraintFactors(NeutralPlacementGraph graph,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors) {
		for(Constraint constraint : graph.constraints()) {
			DecisionDomain left = domains.get(constraint.left());
			DecisionDomain right = domains.get(constraint.right());
			if(left == null || right == null || left == right)
				continue;
			if(constraint.kind() != ConstraintKind.SAME_PLACEMENT
				&& constraint.kind() != ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.kind() != ConstraintKind.FUNCTION_INPUT_TRANSFER
				&& constraint.kind() != ConstraintKind.SAME_FTYPE
				&& constraint.kind() != ConstraintKind.CONJUNCTIVE)
				continue;
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(left.variable(), right.variable()), values ->
					constraintSatisfied(constraint, left.alternatives().get(values[0]).state(),
						right.alternatives().get(values[1]).state()) ? 0.0 : Double.POSITIVE_INFINITY));
		}
	}

	static boolean constraintSatisfied(Constraint constraint, PlacementState left,
		PlacementState right) {
		return NeutralPlacementGraph.constraintSatisfied(constraint, left, right);
	}

	private static void addStrictTransientFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors) {
		for(var input : analysis.logicalTransientInputsInCanonicalOrder()) {
			DecisionDomain write = domains.get(input.sourceWrite());
			DecisionDomain read = domains.get(input.targetRead());
			if(write == null || read == null)
				throw new IllegalArgumentException("Transient compatibility decision domain missing");
			List<DecisionDomain> scope = write == read ? List.of(write) : List.of(write, read);
			factors.add(ExactCategoricalSolver.Factor.lazy(scope.stream().map(DecisionDomain::variable).toList(), values -> {
				CandidateSelectionReceipt source = candidateReceipt(analysis,
					write.alternatives().get(values[0]));
				CandidateSelectionReceipt target = candidateReceipt(analysis,
					read.alternatives().get(values[write == read ? 0 : 1]));
				return input.compatibility().stream().anyMatch(edge ->
					CandidateSelections.matchesRealization(edge.sourceRealization(), source)
						&& CandidateSelections.matchesRealization(edge.readerRealization(), target))
					? 0.0 : Double.POSITIVE_INFINITY;
			}));
		}
	}

	/** Every correlated execution row must be physically executable under one static selection. */
	private static void addJointFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> factorizations) {
		for(JointValueMapRelations.Relation relation : JointValueMapRelations.from(analysis)) {
			// Fixed-map relations are already owned by the ordinary input authorities.
			// An always-zero high-arity factor would still create a solver clique.
			if(relation.readers().stream().noneMatch(reader -> domains.get(reader).alternatives().stream()
				.anyMatch(alternative -> alternative.realization() != null
					&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP)))
				continue;
			DecisionDomain consumer = domains.get(relation.consumer());
			if(consumer == null)
				throw new IllegalArgumentException("Joint consumer decision domain missing");
			List<DecisionDomain> scope = new ArrayList<>();
			scope.add(consumer);
			var grounding = new JointValueMapRelations.Grounding(analysis, relation);
			List<CompiledHopKey> supportOwners = grounding.supportOwners();
			for(CompiledHopKey owner : supportOwners) {
				DecisionDomain domain = domains.get(owner);
				if(domain == null)
					throw new IllegalArgumentException("Joint support decision domain missing");
				if(!scope.contains(domain))
					scope.add(domain);
			}
			List<List<CandidateSelectionReceipt>> receipts = new ArrayList<>();
			List<?>[] observations = new List<?>[scope.size()];
			for(int position = 0; position < scope.size(); position++) {
				DecisionDomain domain = scope.get(position);
				List<CandidateSelectionReceipt> selected = new ArrayList<>();
				List<Object> keys = new ArrayList<>();
				for(Alternative alternative : domain.alternatives()) {
					CandidateSelectionReceipt receipt = candidateReceipt(analysis, alternative);
					selected.add(receipt);
					List<Object> key = new ArrayList<>();
					if(supportOwners.contains(domain.node().key())) {
						key.add(receipt == null ? null : CandidateRealizationReference.of(
							receipt.rule(), receipt.realization()));
						key.add(receipt == null ? null : receipt.supportClause().inputBindings());
						key.add(receipt == null ? null : receipt.provenWorkerPool());
						key.add(receipt != null && receipt.realization()
							.nativeWorkerPoolLayoutExact(receipt.supportClause()));
					}
					if(domain == consumer) {
						key.add(alternative.state().execType());
						key.add(alternative.state().output());
						key.add(alternative.orderedInputs());
						key.add(alternative.inputAuthorities().stream().map(authority ->
							java.util.Arrays.asList(authority.inputPosition(), authority.kind(),
								authority.relocationAction() == null ? null
									: authority.relocationAction().key().durableAnchor())).toList());
						key.add(alternative.realization() == null ? null
							: alternative.realization().key().layoutKind());
					}
					keys.add(key);
				}
				receipts.add(selected);
				observations[position] = keys;
			}
			ExactCategoricalSolver.Factor factor = ExactCategoricalSolver.Factor.lazy(
				scope.stream().map(DecisionDomain::variable).toList(), values -> {
					Map<CompiledHopKey,CandidateSelectionReceipt> selectedReceipts = new IdentityHashMap<>();
					for(int position = 0; position < scope.size(); position++) {
						CandidateSelectionReceipt receipt = receipts.get(position).get(values[position]);
						if(receipt != null)
							selectedReceipts.put(scope.get(position).node().key(), receipt);
					}
					boolean valueMapped = false;
					for(CompiledHopKey reader : relation.readers()) {
						CandidateSelectionReceipt receipt = selectedReceipts.get(reader);
						if(receipt != null && receipt.realization().key().layoutKind()
							== PlacementLayoutKind.VALUE_MAP) {
							valueMapped = true;
						}
					}
					if(!valueMapped)
						return 0.0;
					Alternative selectedConsumer = consumer.alternatives().get(values[0]);
					if(selectedConsumer.state().execType() == ExecType.CP
						&& selectedConsumer.state().output() == FederatedOutput.LOUT)
						return 0.0; // Explicit LOCAL materialization authority owns public collection.
					if(selectedConsumer.realization() == null)
						return Double.POSITIVE_INFINITY;
					Set<Integer> directPositions = new java.util.TreeSet<>();
					List<DurableAnchorKey> relocatedPools = new ArrayList<>();
					for(InputAuthority authority : selectedConsumer.inputAuthorities()) {
						if(authority.kind() == InputAuthorityKind.RELOCATION)
							relocatedPools.add(authority.relocationAction().key().durableAnchor());
						else if(authority.kind() == InputAuthorityKind.DIRECT_FOUT)
							directPositions.add(authority.inputPosition());
					}
					// LOCAL/broadcast and REFED are explicit, separately costed authorities.
					// Their source map need not equal the map used by the FED kernel.
					List<JointValueMapRelations.GroundedLayoutRow> rows =
						grounding.rows(selectedReceipts, -1, directPositions);
					if(rows.size() != relation.rows().size())
						return Double.POSITIVE_INFINITY;
					for(JointValueMapRelations.GroundedLayoutRow row : rows) {
						DurableAnchorKey first = null;
						List<DurableAnchorKey> pools = new ArrayList<>(relocatedPools);
						row.inputs().forEach(input -> pools.add(input.pool()));
						for(DurableAnchorKey pool : pools) {
							if(first == null)
								first = pool;
							else if(!PlacementIdentity.samePhysicalWorkerPool(first, pool))
								return Double.POSITIVE_INFINITY;
						}
					}
					return 0.0;
				});
			factors.add(factor);
			var encoded = ExactHardFactorObservationDecomposition.create(
				"joint-value-map|consumer=" + relation.consumer().normalizedSignature(), factor, observations);
			if(encoded != null)
				factorizations.put(factor, encoded);
		}
	}

	/** Function value aliases preserve the selected pool; source categories are conjunctive. */
	private static void addLogicalBoundaryFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors) {
		for(var relation : analysis.logicalBoundaryRealizations().relations()) {
			DecisionDomain source = domains.get(relation.source());
			DecisionDomain target = domains.get(relation.target());
			if(source == null || target == null)
				throw new IllegalArgumentException("Function value compatibility decision domain missing");
			List<DecisionDomain> scope = source == target ? List.of(source) : List.of(source, target);
			factors.add(ExactCategoricalSolver.Factor.lazy(scope.stream().map(DecisionDomain::variable).toList(), values -> {
				Alternative read = target.alternatives().get(values[source == target ? 0 : 1]);
				if(read.state().output() == FederatedOutput.LOUT)
					return 0.0;
				Alternative write = source.alternatives().get(values[0]);
				return LogicalBoundaryRealizations.compatible(read.realization(), read.supportClause(),
					write.realization(), write.supportClause()) ? 0.0 : Double.POSITIVE_INFINITY;
			}));
		}
	}

	/** Selected native-map proofs must select their actual supporting input rows too. */
	private static RealizationSupportPreparationStatistics addRealizationSupportFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> factorizations) {
		final int conflictingRequiredReferences = -3;
		Map<CandidateRealizationSupportClause,List<CandidateRealizationReference>> supportsByClause =
			new IdentityHashMap<>();
		Map<Alternative,Integer> referenceHandleByAlternative = new IdentityHashMap<>();
		Map<CandidateRealizationReference,Integer> referenceHandles = new java.util.HashMap<>();
		Map<DecisionDomain,int[]> sourceHandlesByDomain = new IdentityHashMap<>();
		for(DecisionDomain consumer : domains.values().stream()
			.sorted(Comparator.comparing(domain -> domain.node().key().normalizedSignature())).toList()) {
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(consumer.variable()), values -> {
				Alternative selected = consumer.alternatives().get(values[0]);
				if(selected.realization() == null)
					return 0.0;
				for(var binding : selected.supportClause().inputBindings()) {
					if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT)
						continue; // The all-reaching-writers relation owns alias inputs.
					boolean supported = selected.inputAuthorities().stream()
						.anyMatch(input -> supportsInputBinding(input, binding));
					if(!supported)
						return Double.POSITIVE_INFINITY;
				}
				return 0.0;
			}));
			LinkedHashSet<CompiledHopKey> dependencies = new LinkedHashSet<>();
			List<Map<CompiledHopKey,Integer>> requirementsByOwnerValue = new ArrayList<>(
				consumer.alternatives().size());
			for(Alternative alternative : consumer.alternatives()) {
				if(alternative.realization() == null) {
					requirementsByOwnerValue.add(Map.of());
					continue;
				}
				Map<CompiledHopKey,Integer> requirements = new IdentityHashMap<>();
				for(CandidateRealizationReference reference :
					requiredInputSupport(alternative.supportClause(), supportsByClause)) {
					CompiledHopKey dependency = reference.rule().parentOccurrence();
					dependencies.add(dependency);
					int handle = referenceHandle(reference, referenceHandles);
					Integer previous = requirements.putIfAbsent(dependency, handle);
					if(previous != null && previous != handle)
						requirements.put(dependency, conflictingRequiredReferences);
				}
				requirementsByOwnerValue.add(requirements);
			}
			for(CompiledHopKey dependency : dependencies) {
				DecisionDomain source = domains.get(dependency);
				if(source == null)
					throw new IllegalArgumentException("Realization proof has no physical decision owner");
				int[] sourceHandles = sourceHandlesByDomain.computeIfAbsent(source, ignored -> {
					int[] handles = new int[source.alternatives().size()];
					for(int sourceValue = 0; sourceValue < source.alternatives().size(); sourceValue++)
						handles[sourceValue] = candidateReferenceHandle(analysis,
							source.alternatives().get(sourceValue), referenceHandleByAlternative, referenceHandles);
					return handles;
				});
				List<DecisionDomain> scope = consumer == source ? List.of(consumer) : List.of(consumer, source);
				ExactCategoricalSolver.Factor factor = ExactCategoricalSolver.Factor.lazy(
					scope.stream().map(DecisionDomain::variable).toList(), values -> {
					int ownerValue = values[0];
					if(consumer.alternatives().get(ownerValue).realization() == null)
						return 0.0;
					Integer required = requirementsByOwnerValue.get(ownerValue).get(dependency);
					if(required == null)
						return 0.0;
					if(required == conflictingRequiredReferences)
						return Double.POSITIVE_INFINITY;
					int selectedSource = sourceHandles[values[consumer == source ? 0 : 1]];
					return required == selectedSource ? 0.0 : Double.POSITIVE_INFINITY;
				});
				factors.add(factor);
				if(consumer != source) {
					List<Integer> consumerObservations = new ArrayList<>(consumer.alternatives().size());
					for(int ownerValue = 0; ownerValue < consumer.alternatives().size(); ownerValue++)
						consumerObservations.add(consumer.alternatives().get(ownerValue).realization() == null
							? null : requirementsByOwnerValue.get(ownerValue).get(dependency));
					List<?>[] observations = new List<?>[] {
						consumerObservations,
						ExactHardFactorObservationDecomposition.keys(sourceHandles)
					};
					var encoded = ExactHardFactorObservationDecomposition.create(
						"realization-support|consumer=" + consumer.node().key().normalizedSignature()
							+ "|source=" + dependency.normalizedSignature(), factor, observations);
					if(encoded != null)
						factorizations.put(factor, encoded);
				}
			}
		}
		return new RealizationSupportPreparationStatistics(supportsByClause.size(),
			referenceHandleByAlternative.size(), referenceHandles.size());
	}

	private static List<CandidateRealizationReference> requiredInputSupport(
		CandidateRealizationSupportClause clause,
		Map<CandidateRealizationSupportClause,List<CandidateRealizationReference>> supportsByClause) {
		return supportsByClause.computeIfAbsent(clause,
			ignored -> clause.requiredInputSupport());
	}

	private static int candidateReferenceHandle(PlacementAnalysis analysis, Alternative alternative,
		Map<Alternative,Integer> referenceHandleByAlternative,
		Map<CandidateRealizationReference,Integer> referenceHandles) {
		Integer cached = referenceHandleByAlternative.get(alternative);
		if(cached != null)
			return cached;
		CandidateSelectionReceipt receipt = candidateReceipt(analysis, alternative);
		int handle = receipt == null ? -1 : referenceHandle(
			CandidateRealizationReference.of(receipt.rule(), receipt.realization()), referenceHandles);
		referenceHandleByAlternative.put(alternative, handle);
		return handle;
	}

	private static int referenceHandle(CandidateRealizationReference reference,
		Map<CandidateRealizationReference,Integer> referenceHandles) {
		return referenceHandles.computeIfAbsent(reference, ignored -> referenceHandles.size());
	}

	private static CandidateSelectionReceipt candidateReceipt(PlacementAnalysis analysis, Alternative alternative) {
		CandidateRuleFact rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		CandidateEmissionFact emission = alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
		return rule == null || emission == null || alternative.realization() == null ? null
			: analysis.canonicalCandidateReceipt(rule.key(), emission, alternative.realization(), alternative.supportClause());
	}

	/**
	 * A selected planner-created FOUT producer is executable only with the exact compiled
	 * durable-anchor owner named by its graph-owned action. FType equality on some
	 * other node is not residency authority. This factor mirrors emission
	 * prevalidation so the exact optimizer cannot select a plan that the runtime
	 * transaction must reject later.
	 */
	private static void addDerivedFoutAnchorFactors(NeutralPlacementGraph graph,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors) {
		for(DerivedFoutMaterializationAction action : graph.derivedFoutMaterializationActions()) {
			DecisionDomain producer = domains.get(action.key().producer());
			DecisionDomain owner = domains.get(action.key().durableAnchorOwner());
			if(producer == null || owner == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_FOUT_OWNER_DOMAIN_MISSING|action="
					+ action.normalizedSignature());
			if(producer == owner) {
				factors.add(ExactCategoricalSolver.Factor.lazy(List.of(producer.variable()), values -> {
					Alternative selected = producer.alternatives().get(values[0]);
					return selected.derivedFoutAction() != action || derivedFoutOwnerSatisfied(action, selected)
						? 0.0 : Double.POSITIVE_INFINITY;
				}));
			}
			else {
				factors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(producer.variable(), owner.variable()), values -> {
						Alternative selectedProducer = producer.alternatives().get(values[0]);
						Alternative selectedOwner = owner.alternatives().get(values[1]);
						return selectedProducer.derivedFoutAction() != action
							|| derivedFoutOwnerSatisfied(action, selectedOwner)
							? 0.0 : Double.POSITIVE_INFINITY;
					}));
			}
		}
	}

	private static boolean derivedFoutOwnerSatisfied(DerivedFoutMaterializationAction action,
		Alternative owner) {
		return owner.decision() == action.key().durableAnchorOwner()
			&& owner.state().output() == FederatedOutput.FOUT
			&& owner.state().fType() == action.key().durableAnchorOwnerFType();
	}

	private static InputAuthorityPreparationStatistics addInputAuthorityFactors(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		Map<CompiledHopKey,List<Link>> incoming, Map<CompiledHopKey,DecisionDomain> domains,
		List<ExactCategoricalSolver.Factor> factors,
		boolean allocationFreeInputAuthorityEvaluation,
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> factorizations) {
		Map<ValueVersionKey,List<RelocationAction>> actionsBySourceVersion = new java.util.HashMap<>();
		for(RelocationAction action : analysis.graph().relocationActions())
			actionsBySourceVersion.computeIfAbsent(action.key().sourceValueVersion(),
				ignored -> new ArrayList<>()).add(action);
		actionsBySourceVersion.replaceAll((ignored, actions) -> List.copyOf(actions));
		Map<ValueVersionKey,Set<CompiledHopKey>> sourceOwnersByVersion =
			relocationSourceOwnersByVersion(analysis.graph());
		Map<ValueVersionKey,Map<CompiledHopKey,Set<Integer>>> directBindingPositionsBySourceVersion =
			new java.util.HashMap<>();
		Map<Alternative,CandidateSelectionReceipt> receiptByAlternative = new IdentityHashMap<>();
		Map<DecisionDomain,CandidateSelectionReceipt[]> receiptTableByDomain = new IdentityHashMap<>();
		int factorCount = 0;
		int consumerAlternativeRows = 0;
		int indexedDirectFoutRows = 0;
		// Identity lookup is required for authority, but its iteration order is not
		// stable across JVMs. A stable factor order also fixes mini-bucket partitions.
		for(Map.Entry<CompiledHopKey,List<Link>> entry : incoming.entrySet().stream()
			.sorted(Comparator.comparing(item -> item.getKey().normalizedSignature())).toList()) {
			DecisionDomain consumer = domains.get(entry.getKey());
			if(consumer == null)
				continue;
			for(Link link : entry.getValue()) {
				DecisionDomain directSource = domains.get(link.sourceNode.key());
				if(directSource == null)
					continue;
				List<DecisionDomain> scopeDomains = new ArrayList<>();
				addIdentityOrderedScopeDomain(scopeDomains, consumer);
				addIdentityOrderedScopeDomain(scopeDomains, directSource);
				for(Node node : analysis.graph().decisionNodes())
					if(node.valueVersion().equals(link.sourceNode.valueVersion())) {
						DecisionDomain source = domains.get(node.key());
						if(source != null)
							addIdentityOrderedScopeDomain(scopeDomains, source);
					}
				List<DecisionDomain> scope = List.copyOf(scopeDomains);
				List<InputAuthorityFactorRow> preparedRows = new ArrayList<>(consumer.alternatives().size());
				List<RelocationAction> sourceActions = actionsBySourceVersion.getOrDefault(
					link.sourceNode.valueVersion(), List.of());
				Set<CompiledHopKey> sourceOwners = sourceOwnersByVersion.getOrDefault(
					link.sourceNode.valueVersion(), Set.of());
				Map<CompiledHopKey,Set<Integer>> directBindingPositions =
					directBindingPositionsBySourceVersion.computeIfAbsent(link.sourceNode.valueVersion(),
						ignored -> relevantDirectBindingPositions(sourceActions));
				for(Alternative selectedConsumer : consumer.alternatives()) {
					InputAuthorityFactorRow row = prepareInputAuthorityRow(sourceActions, link,
						consumer, selectedConsumer);
					preparedRows.add(row);
					consumerAlternativeRows++;
					if(row.authority() != null && row.authority().kind() == InputAuthorityKind.DIRECT_FOUT)
						indexedDirectFoutRows++;
				}
				CandidateSelectionReceipt[][] receiptTable = new CandidateSelectionReceipt[scope.size()][];
				for(int scopeIndex = 0; scopeIndex < scope.size(); scopeIndex++) {
					DecisionDomain domain = scope.get(scopeIndex);
					receiptTable[scopeIndex] = receiptTableByDomain.computeIfAbsent(domain, ignored -> {
						CandidateSelectionReceipt[] receipts =
							new CandidateSelectionReceipt[domain.alternatives().size()];
						for(int value = 0; value < receipts.length; value++)
							receipts[value] = cachedCandidateReceipt(analysis,
								domain.alternatives().get(value), receiptByAlternative);
						return receipts;
					});
				}
				PreparedInputAuthorityFactor evaluator = new PreparedInputAuthorityFactor(
					analysis, relocationPrivacy, link, consumer, directSource, scope,
					preparedRows, receiptTable);
				ExactCategoricalSolver.Factor factor = ExactCategoricalSolver.Factor.lazy(
					scope.stream().map(DecisionDomain::variable).toList(),
					allocationFreeInputAuthorityEvaluation ? evaluator::cost : evaluator::allocatingCost);
				factors.add(factor);
				List<PlacementState> derivedTargets = derivedTargetIdentities(receiptTable);
				@SuppressWarnings("unchecked")
				List<?>[] observations = new List<?>[scope.size()];
				for(int scopeIndex = 0; scopeIndex < scope.size(); scopeIndex++) {
					DecisionDomain domain = scope.get(scopeIndex);
					List<Object> keys = new ArrayList<>(domain.alternatives().size());
					for(int value = 0; value < domain.alternatives().size(); value++) {
						Alternative alternative = domain.alternatives().get(value);
						InputAuthorityFactorRow row = scopeIndex == 0 ? preparedRows.get(value) : null;
						InputAuthorityObservation observation = inputAuthorityObservation(alternative,
							receiptTable[scopeIndex][value],
							row, scopeIndex == 0 && (alternative.orderedInputs().isEmpty()
								|| link.position >= alternative.orderedInputs().size()),
							sourceActions, directBindingPositions, sourceOwners, derivedTargets);
						keys.add(link.kind() == LinkKind.LOGICAL_FUNCTION
							? java.util.Arrays.asList(observation,
								logicalBoundaryObservation(receiptTable[scopeIndex][value])) : observation);
					}
					observations[scopeIndex] = keys;
				}
				var encoded = ExactHardFactorObservationDecomposition.create(
					"input-authority|consumer=" + consumer.node().key().normalizedSignature()
						+ "|position=" + link.position + "|kind=" + link.kind + "|source="
						+ link.sourceNode.key().normalizedSignature(), factor, observations);
				if(encoded != null)
					factorizations.put(factor, encoded);
				factorCount++;
			}
		}
		return new InputAuthorityPreparationStatistics(factorCount, consumerAlternativeRows,
			indexedDirectFoutRows, receiptByAlternative.size());
	}

	private static void addIdentityOrderedScopeDomain(List<DecisionDomain> scope,
		DecisionDomain candidate) {
		for(DecisionDomain existing : scope) {
			if(existing == candidate)
				return;
			// Record equality is impossible unless the cheap variable component is
			// equal. Retain the old LinkedHashSet behavior for synthetic/foreign rows
			// without hashing every alternative signature on the canonical identity path.
			if(existing.variable().equals(candidate.variable()) && existing.equals(candidate))
				return;
		}
		scope.add(candidate);
	}

	static List<DecisionDomain> identityOrderedScopeForTest(List<DecisionDomain> candidates) {
		List<DecisionDomain> scope = new ArrayList<>();
		for(DecisionDomain candidate : candidates)
			addIdentityOrderedScopeDomain(scope, Objects.requireNonNull(candidate, "candidate"));
		return List.copyOf(scope);
	}

	private static InputAuthorityObservation inputAuthorityObservation(Alternative alternative,
		CandidateSelectionReceipt receipt, InputAuthorityFactorRow row, boolean unconstrained,
		List<RelocationAction> relevantActions, Map<CompiledHopKey,Set<Integer>> directBindingPositions,
		Set<CompiledHopKey> sourceOwners, List<PlacementState> derivedTargets) {
		InputAuthority authority = row == null ? null : row.authority();
		return new InputAuthorityObservation(stateObservation(alternative.state(), derivedTargets),
			receiptObservation(receipt, relevantActions, directBindingPositions, sourceOwners),
			authority == null ? null : authority.kind(),
			authority == null ? null : authority.expectedFType(),
			identity(authority == null ? null : authority.sourceDecision()),
			identity(authority == null ? null : authority.relocationAction()),
			row == null ? List.of()
				: row.directFoutActions().stream().map(ExactPhysicalModel::identity).toList(),
			row != null && row.exactDirectFoutActionMatched(),
			row != null && row.relocationInvariantSatisfied(), unconstrained);
	}

	/** The logical boundary predicate additionally observes its selected map proof. */
	private static List<?> logicalBoundaryObservation(CandidateSelectionReceipt receipt) {
		if(receipt == null) return List.of();
		return java.util.Arrays.asList(receipt.realization().key(),
			receipt.nativeWorkerPoolResidencyWitness(),
			receipt.realization().nativeWorkerPoolLayoutExact(receipt.supportClause()),
			receipt.supportClause().inputBindings().stream()
				.map(binding -> binding.source().realization()).toList());
	}

	private static Map<CompiledHopKey,Set<Integer>> relevantDirectBindingPositions(
		List<RelocationAction> relevantActions) {
		Map<CompiledHopKey,Set<Integer>> positions = new IdentityHashMap<>();
		for(RelocationAction action : relevantActions)
			for(var obligation : action.obligations())
				positions.computeIfAbsent(obligation.consumer(), ignored -> new java.util.HashSet<>())
					.add(obligation.inputPosition());
		return positions;
	}

	private static Map<ValueVersionKey,Set<CompiledHopKey>> relocationSourceOwnersByVersion(
		NeutralPlacementGraph graph) {
		Map<ValueVersionKey,Set<CompiledHopKey>> owners = new java.util.HashMap<>();
		// isRelocationActive selects source receipts from all same-version graph
		// nodes, including aliases and self-owners, using exact owner identity.
		for(Node node : graph.nodes())
			owners.computeIfAbsent(node.valueVersion(), ignored ->
				Collections.newSetFromMap(new IdentityHashMap<>())).add(node.key());
		owners.replaceAll((ignored, keys) -> Collections.unmodifiableSet(keys));
		return Collections.unmodifiableMap(owners);
	}

	private static ReceiptObservation receiptObservation(CandidateSelectionReceipt receipt,
		List<RelocationAction> relevantActions, Map<CompiledHopKey,Set<Integer>> directBindingPositions,
		Set<CompiledHopKey> sourceOwners) {
		if(receipt == null)
			return null;
		// Relocation activity reads DIRECT bindings only at its obligation consumers'
		// input positions. Keep every alias/self-owner obligation, not just this Link.
		Set<Integer> directPositions = directBindingPositions.getOrDefault(
			receipt.rule().parentOccurrence(), Set.of());
		List<BindingObservation> bindings = new ArrayList<>();
		for(var binding : receipt.supportClause().inputBindings()) {
			if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT
				&& directPositions.contains(binding.inputPosition()))
				bindings.add(new BindingObservation(binding.kind(), binding.inputPosition(),
					binding.source(), null));
			else if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
				&& relevantActions.stream().anyMatch(action ->
					action.key().equals(binding.relocationAction())))
				bindings.add(new BindingObservation(binding.kind(), binding.inputPosition(),
					null, binding.relocationAction()));
		}
		// A receipt's own realization reference is read only as selectedSource.
		// Do not infer source membership from its current consumer role. Keep the
		// empty-action case unchanged; no new authority-completeness assumption.
		return new ReceiptObservation(identity(receipt.rule().parentOccurrence()),
			relevantActions.isEmpty() || sourceOwners.contains(receipt.rule().parentOccurrence())
				? CandidateRealizationReference.of(receipt.rule(), receipt.realization()) : null,
			List.copyOf(bindings), receipt.provenWorkerPool(),
			receipt.nativeWorkerPoolResidencyWitness(),
			identity(receipt.emission().derivedFoutAction()));
	}

	private static StateObservation stateObservation(PlacementState state,
		List<PlacementState> derivedTargets) {
		List<Integer> identities = new ArrayList<>();
		for(int index = 0; index < derivedTargets.size(); index++)
			if(state == derivedTargets.get(index))
				identities.add(index);
		return new StateObservation(state, List.copyOf(identities));
	}

	private static List<PlacementState> derivedTargetIdentities(
		CandidateSelectionReceipt[][] receiptTable) {
		List<PlacementState> result = new ArrayList<>();
		Map<PlacementState,Boolean> seen = new IdentityHashMap<>();
		for(CandidateSelectionReceipt[] receipts : receiptTable)
			for(CandidateSelectionReceipt receipt : receipts)
				if(receipt != null && receipt.emission().derivedFoutAction() != null) {
					PlacementState target = receipt.emission().derivedFoutAction().targetPlacement();
					if(seen.put(target, Boolean.TRUE) == null)
						result.add(target);
				}
		return List.copyOf(result);
	}

	private static IdentityObservation identity(Object value) {
		return value == null ? null : new IdentityObservation(value);
	}

	private static InputAuthorityFactorRow prepareInputAuthorityRow(List<RelocationAction> sourceActions,
		Link link, DecisionDomain consumer, Alternative selectedConsumer) {
		if(selectedConsumer.orderedInputs().isEmpty()
			|| link.position >= selectedConsumer.orderedInputs().size())
			return new InputAuthorityFactorRow(null, List.of(), false, true);
		List<InputAuthority> matching = selectedConsumer.inputAuthorities().stream()
			.filter(candidate -> candidate.inputPosition() == link.position)
			.filter(candidate -> candidate.kind() == InputAuthorityKind.NATIVE_LOCAL
				|| candidate.sourceDecision() == link.sourceNode.key()).distinct().toList();
		if(matching.size() != 1)
			return new InputAuthorityFactorRow(null, List.of(), false, false);
		InputAuthority authority = matching.get(0);
		if(authority.kind() == InputAuthorityKind.DIRECT_FOUT) {
			List<RelocationAction> directFoutActions = matchingDirectFoutActions(sourceActions,
				link.sourceNode, consumer.node().key(), link.position, selectedConsumer.state(),
				authority.expectedFType());
			boolean exactActionMatched = authority.relocationAction() == null
				|| directFoutActions.stream().anyMatch(action -> action == authority.relocationAction());
			return new InputAuthorityFactorRow(authority, directFoutActions, exactActionMatched, true);
		}
		if(authority.kind() == InputAuthorityKind.RELOCATION) {
			RelocationAction action = authority.relocationAction();
			boolean valid = action.key().sourceValueVersion().equals(link.sourceNode.valueVersion())
				&& action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == consumer.node().key()
						&& obligation.inputPosition() == link.position
						&& obligation.requiredPlacement().equals(selectedConsumer.state()));
			return new InputAuthorityFactorRow(authority, List.of(), false, valid);
		}
		return new InputAuthorityFactorRow(authority, List.of(), false, true);
	}

	private static final class PreparedInputAuthorityFactor {
		private final PlacementAnalysis analysis;
		private final RelocationSelections.RelocationPrivacyIndex relocationPrivacy;
		private final Link link;
		private final DecisionDomain consumer;
		private final DecisionDomain directSource;
		private final List<DecisionDomain> scope;
		private final List<InputAuthorityFactorRow> preparedRows;
		private final CandidateSelectionReceipt[][] receiptTable;
		private final int directSourceIndex;
		private final ThreadLocal<IndexedRelocationEvaluationView> evaluationView;

		private PreparedInputAuthorityFactor(PlacementAnalysis analysis,
			RelocationSelections.RelocationPrivacyIndex relocationPrivacy, Link link,
			DecisionDomain consumer, DecisionDomain directSource, List<DecisionDomain> scope,
			List<InputAuthorityFactorRow> preparedRows,
			CandidateSelectionReceipt[][] receiptTable) {
			this.analysis = analysis;
			this.relocationPrivacy = relocationPrivacy;
			this.link = link;
			this.consumer = consumer;
			this.directSource = directSource;
			this.scope = scope;
			this.preparedRows = preparedRows;
			this.receiptTable = receiptTable;
			directSourceIndex = scope.indexOf(directSource);
			if(directSourceIndex < 0)
				throw new IllegalArgumentException("EXACT_PHYSICAL_DIRECT_SOURCE_SCOPE_MISSING");
			evaluationView = ThreadLocal.withInitial(() ->
				new IndexedRelocationEvaluationView(scope, receiptTable));
		}

		private double cost(int[] values) {
			IndexedRelocationEvaluationView view = evaluationView.get();
			view.bind(values);
			try {
				return inputSatisfied(analysis, relocationPrivacy, link, consumer, directSource,
					directSourceIndex, preparedRows, values, view);
			}
			finally {
				view.clear();
			}
		}

		private double allocatingCost(int[] values) {
			return inputSatisfiedAllocating(analysis, relocationPrivacy, link, consumer,
				directSource, directSourceIndex, scope, preparedRows, receiptTable, values);
		}
	}

	private static final class IndexedRelocationEvaluationView
		implements NeutralPlacementGraph.RelocationEvaluationView {
		private final List<DecisionDomain> scope;
		private final CandidateSelectionReceipt[][] receiptTable;
		private final Map<CompiledHopKey,Integer> scopeIndex;
		private int[] values;

		private IndexedRelocationEvaluationView(List<DecisionDomain> scope,
			CandidateSelectionReceipt[][] receiptTable) {
			this.scope = scope;
			this.receiptTable = receiptTable;
			Map<CompiledHopKey,Integer> index = new IdentityHashMap<>();
			for(int position = 0; position < scope.size(); position++)
				index.put(scope.get(position).node().key(), position);
			scopeIndex = index;
		}

		private void bind(int[] values) {
			if(this.values != null)
				throw new IllegalStateException("EXACT_PHYSICAL_RELOCATION_VIEW_REENTRANT");
			this.values = values;
		}

		private void clear() {
			values = null;
		}

		@Override
		public PlacementState state(CompiledHopKey decision) {
			Integer position = scopeIndex.get(decision);
			return position == null ? null
				: scope.get(position).alternatives().get(values[position]).state();
		}

		@Override
		public int candidateSlotCount() {
			return receiptTable.length;
		}

		@Override
		public CandidateSelectionReceipt candidateAt(int slot) {
			return receiptTable[slot][values[slot]];
		}
	}

	private static double inputSatisfied(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		Link link, DecisionDomain consumer, DecisionDomain directSource,
		int directSourceIndex, List<InputAuthorityFactorRow> preparedRows,
		int[] values, NeutralPlacementGraph.RelocationEvaluationView view) {
		NeutralPlacementGraph graph = analysis.graph();
		Alternative selectedConsumer = consumer.alternatives().get(values[0]);
		if(selectedConsumer.orderedInputs().isEmpty()
			|| link.position >= selectedConsumer.orderedInputs().size())
			return 0.0;
		InputAuthorityFactorRow prepared = preparedRows.get(values[0]);
		if(prepared.authority() == null)
			return Double.POSITIVE_INFINITY;
		InputAuthority authority = prepared.authority();
		Alternative source = directSource.alternatives().get(values[directSourceIndex]);
		if(authority.kind() == InputAuthorityKind.NATIVE_LOCAL)
			// ABSENT_LOCAL describes the FED instruction's native coordinator-local input
			// mode, not the producer's selected output placement. A FOUT producer remains
			// legal; its boundary preparation belongs to the canonical cost factors.
			return inputAuthorityPlacementSatisfied(authority, source.state()) ? 0.0
				: Double.POSITIVE_INFINITY;
		if(authority.kind() == InputAuthorityKind.DIRECT_FOUT) {
			if(exactDirectReceiptBinding(selectedConsumer, source, link.position,
				authority.expectedFType()) || exactLogicalFunctionReceiptBinding(link,
					selectedConsumer, source, authority.expectedFType()))
				return 0.0;
			return directFoutSatisfied(graph, link.sourceNode, authority.expectedFType(),
				authority.relocationAction(), view, prepared.directFoutActions(),
				prepared.exactDirectFoutActionMatched()) ? 0.0
				: Double.POSITIVE_INFINITY;
		}
		RelocationAction action = authority.relocationAction();
		if(!prepared.relocationInvariantSatisfied())
			return Double.POSITIVE_INFINITY;
		if(!graph.isRelocationActive(action, view))
			return Double.POSITIVE_INFINITY;
		if(!relocationPrivacy.isPrivacySafe(action, true))
			return Double.POSITIVE_INFINITY;
		return source.state().output() == FederatedOutput.LOUT
			|| source.state().output() == FederatedOutput.FOUT ? 0.0 : Double.POSITIVE_INFINITY;
	}

	private static double inputSatisfiedAllocating(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		Link link, DecisionDomain consumer, DecisionDomain directSource,
		int directSourceIndex, List<DecisionDomain> scope,
		List<InputAuthorityFactorRow> preparedRows,
		CandidateSelectionReceipt[][] receiptTable, int[] values) {
		Alternative selectedConsumer = consumer.alternatives().get(values[0]);
		if(selectedConsumer.orderedInputs().isEmpty()
			|| link.position >= selectedConsumer.orderedInputs().size())
			return 0.0;
		InputAuthorityFactorRow prepared = preparedRows.get(values[0]);
		if(prepared.authority() == null)
			return Double.POSITIVE_INFINITY;
		InputAuthority authority = prepared.authority();
		Alternative source = directSource.alternatives().get(values[directSourceIndex]);
		if(authority.kind() == InputAuthorityKind.NATIVE_LOCAL)
			return inputAuthorityPlacementSatisfied(authority, source.state()) ? 0.0
				: Double.POSITIVE_INFINITY;
		Map<CompiledHopKey,PlacementState> assignment = selectedStates(scope, values);
		List<CandidateSelectionReceipt> selectedCandidates = selectedCandidateReceipts(receiptTable, values);
		if(authority.kind() == InputAuthorityKind.DIRECT_FOUT) {
			if(exactDirectReceiptBinding(selectedConsumer, source, link.position,
				authority.expectedFType()) || exactLogicalFunctionReceiptBinding(link,
					selectedConsumer, source, authority.expectedFType()))
				return 0.0;
			return directFoutSatisfied(analysis.graph(), link.sourceNode, authority.expectedFType(),
				authority.relocationAction(), assignment, selectedCandidates,
				prepared.directFoutActions(), prepared.exactDirectFoutActionMatched()) ? 0.0
				: Double.POSITIVE_INFINITY;
		}
		RelocationAction action = authority.relocationAction();
		if(!prepared.relocationInvariantSatisfied())
			return Double.POSITIVE_INFINITY;
		if(!analysis.graph().isRelocationActive(action, assignment, selectedCandidates))
			return Double.POSITIVE_INFINITY;
		if(!relocationPrivacy.isPrivacySafe(action, true))
			return Double.POSITIVE_INFINITY;
		return source.state().output() == FederatedOutput.LOUT
			|| source.state().output() == FederatedOutput.FOUT ? 0.0 : Double.POSITIVE_INFINITY;
	}

	/**
	 * Formal reads carry a logical-boundary proof, not a compiled HOP input binding.
	 * Use that exact selected proof for a pure FOUT alias before inferring relocation.
	 */
	private static boolean exactLogicalFunctionReceiptBinding(Link link, Alternative consumer,
		Alternative source, FType expectedFType) {
		return link.kind() == LinkKind.LOGICAL_FUNCTION
			&& consumer.state().execType() == ExecType.FED
			&& consumer.state().output() == FederatedOutput.FOUT
			&& consumer.state().fType() == expectedFType
			&& source.state().output() == FederatedOutput.FOUT
			&& source.state().fType() == expectedFType
			&& consumer.realization() != null && source.realization() != null
			&& !consumer.realization().key().emissionState().derivedFedFout()
			&& LogicalBoundaryRealizations.compatible(consumer.realization(), consumer.supportClause(),
				source.realization(), source.supportClause());
	}

	/** Exact selected receipt authority precedes coarse relocation-activity inference. */
	private static boolean exactDirectReceiptBinding(Alternative consumer, Alternative source,
		int inputPosition, FType expectedFType) {
		if(consumer.supportClause() == null || source.realization() == null
			|| source.state().output() != FederatedOutput.FOUT || source.state().fType() != expectedFType)
			return false;
		CandidateRuleFact sourceRule = source.captured() ? source.candidateRule() : source.executionRule();
		if(sourceRule == null)
			return false;
		CandidateRealizationReference selected = CandidateRealizationReference.of(
			sourceRule.key(), source.realization());
		return consumer.supportClause().inputBindings().stream().anyMatch(binding ->
			binding.inputPosition() == inputPosition
				&& binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT
				&& binding.source().equals(selected));
	}

	/**
	 * A PRESENT input is direct only when the selected logical value is already resident on
	 * the exact durable target anchor. FType equality alone is insufficient because two ROW
	 * FederationMaps may name different workers/ranges. When the neutral graph owns a matching
	 * relocation action, its inactivity is the exact proof that no upload/refed is required.
	 */
	static boolean directFoutSatisfied(NeutralPlacementGraph graph, Node source,
		CompiledHopKey consumer, int inputPosition, PlacementState consumerState,
		FType expectedFType, Map<CompiledHopKey,PlacementState> assignment) {
		return directFoutSatisfied(graph, source, consumer, inputPosition, consumerState,
			expectedFType, null, assignment, List.of());
	}

	private static boolean directFoutSatisfied(NeutralPlacementGraph graph, Node source,
		CompiledHopKey consumer, int inputPosition, PlacementState consumerState,
		FType expectedFType, RelocationAction exactAction,
		Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(consumer, "consumer");
		Objects.requireNonNull(consumerState, "consumerState");
		Objects.requireNonNull(expectedFType, "expectedFType");
		Objects.requireNonNull(assignment, "assignment");
		List<RelocationAction> matching = matchingDirectFoutActions(graph.relocationActions(),
			source, consumer, inputPosition, consumerState, expectedFType);
		boolean exactActionMatched = exactAction == null
			|| matching.stream().anyMatch(action -> action == exactAction);
		return directFoutSatisfied(graph, source, expectedFType, exactAction, assignment,
			selectedCandidates, matching, exactActionMatched);
	}

	private static boolean directFoutSatisfied(NeutralPlacementGraph graph, Node source,
		FType expectedFType, RelocationAction exactAction,
		Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> selectedCandidates, List<RelocationAction> matching,
		boolean exactActionMatched) {
		PlacementState sourceState = assignment.get(source.key());
		if(sourceState == null || sourceState.output() != FederatedOutput.FOUT
			|| sourceState.fType() != expectedFType)
			return false;
		if(exactAction != null)
			return exactActionMatched
				&& !graph.isRelocationActive(exactAction, assignment, selectedCandidates);
		return matching.isEmpty() || matching.stream().anyMatch(action ->
			!graph.isRelocationActive(action, assignment, selectedCandidates));
	}

	private static boolean directFoutSatisfied(NeutralPlacementGraph graph, Node source,
		FType expectedFType, RelocationAction exactAction,
		NeutralPlacementGraph.RelocationEvaluationView view,
		List<RelocationAction> matching, boolean exactActionMatched) {
		PlacementState sourceState = view.state(source.key());
		if(sourceState == null || sourceState.output() != FederatedOutput.FOUT
			|| sourceState.fType() != expectedFType)
			return false;
		if(exactAction != null)
			return exactActionMatched && !graph.isRelocationActive(exactAction, view);
		if(matching.isEmpty())
			return true;
		for(RelocationAction action : matching)
			if(!graph.isRelocationActive(action, view))
				return true;
		return false;
	}

	private static List<RelocationAction> matchingDirectFoutActions(
		List<RelocationAction> actions, Node source, CompiledHopKey consumer,
		int inputPosition, PlacementState consumerState, FType expectedFType) {
		return actions.stream()
			.filter(action -> action.key().sourceValueVersion().equals(source.valueVersion()))
			.filter(action -> action.key().materializationFType() == expectedFType)
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == consumer && obligation.inputPosition() == inputPosition
					&& obligation.requiredPlacement().equals(consumerState)))
			.toList();
	}

	private static Map<CompiledHopKey,PlacementState> selectedStates(
		List<DecisionDomain> scope, int[] values) {
		Map<CompiledHopKey,PlacementState> result = new IdentityHashMap<>();
		for(int index = 0; index < scope.size(); index++)
			result.put(scope.get(index).node().key(),
				scope.get(index).alternatives().get(values[index]).state());
		return result;
	}

	private static CandidateSelectionReceipt cachedCandidateReceipt(PlacementAnalysis analysis,
		Alternative alternative, Map<Alternative,CandidateSelectionReceipt> receiptByAlternative) {
		if(receiptByAlternative.containsKey(alternative))
			return receiptByAlternative.get(alternative);
		CandidateSelectionReceipt receipt = candidateReceipt(analysis, alternative);
		receiptByAlternative.put(alternative, receipt);
		return receipt;
	}

	private static List<CandidateSelectionReceipt> selectedCandidateReceipts(
		CandidateSelectionReceipt[][] receiptTable, int[] values) {
		List<CandidateSelectionReceipt> result = new ArrayList<>();
		for(int index = 0; index < receiptTable.length; index++) {
			CandidateSelectionReceipt receipt = receiptTable[index][values[index]];
			if(receipt != null)
				result.add(receipt);
		}
		return List.copyOf(result);
	}

	static boolean inputAuthorityPlacementSatisfied(InputAuthority authority, PlacementState source) {
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(source, "source");
		if(authority.kind() == InputAuthorityKind.NATIVE_LOCAL)
			return true;
		if(authority.kind() == InputAuthorityKind.DIRECT_FOUT)
			return source.output() == FederatedOutput.FOUT && source.fType() == authority.expectedFType();
		throw new IllegalArgumentException("EXACT_PHYSICAL_RELOCATION_REQUIRES_ASSIGNMENT_CONTEXT");
	}

	private record Link(Node sourceNode, CompiledHopKey consumer, int position, LinkKind kind) { }
}

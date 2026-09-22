/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
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
		PlacementAnalysis.CandidateRealizationSupportClause supportClause, String signature) {
		Alternative {
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(authorityKind, "authorityKind");
			orderedInputs = List.copyOf(orderedInputs);
			inputAuthorities = List.copyOf(inputAuthorities);
			if((realization == null) != (supportClause == null)
				|| realization != null && realization.supportClauses().stream().noneMatch(clause -> clause == supportClause))
				throw new IllegalArgumentException("EXACT_PHYSICAL_SUPPORT_CLAUSE_IDENTITY_INVALID");
			boolean materializedOutputCandidate = candidateEmission != null
				&& candidateEmission.derivedFoutAction() != null;
			if(materializedOutputCandidate != (derivedFoutAction != null)
				|| derivedFoutAction != null && relocationAction != null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_FOUT_OUTPUT_AUTHORITY_INVALID");
			if(signature == null || signature.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_ALTERNATIVE_SIGNATURE_INVALID");
		}
		boolean captured() { return authorityKind == AuthorityKind.CAPTURED_RULE; }
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

	private interface CandidateRuleLookup {
		List<CandidateRuleFact> rulesFor(Node node);
		long factsExamined(Node node, List<CandidateRuleFact> ownerRules);
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

	private ExactPhysicalModel(PlacementAnalysis analysis, List<DecisionDomain> domains,
		List<ExactCategoricalSolver.Factor> hardFactors,
		CandidateRuleLookupStatistics candidateRuleLookupStatistics) {
		this.analysis = analysis;
		this.domains = List.copyOf(domains);
		this.hardFactors = List.copyOf(hardFactors);
		this.candidateRuleLookupStatistics = candidateRuleLookupStatistics;
		Map<CompiledHopKey,DecisionDomain> indexed = new IdentityHashMap<>();
		for(DecisionDomain domain : domains)
			indexed.put(domain.node().key(), domain);
		this.byDecision = indexed;
	}

	static ExactPhysicalModel build(PlacementAnalysis analysis) {
		return build(analysis, new CandidateRuleLookup() {
			@Override
			public List<CandidateRuleFact> rulesFor(Node node) {
				return analysis.candidateRuleFacts().orderedFactsForParent(node.key());
			}

			@Override
			public long factsExamined(Node node, List<CandidateRuleFact> ownerRules) {
				return ownerRules.size();
			}
		});
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
		});
	}

	private static ExactPhysicalModel build(PlacementAnalysis analysis, CandidateRuleLookup ruleLookup) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(ruleLookup, "ruleLookup");
		analysis.assertProgramStructureUnchanged();
		// Synthetic function boundaries are planner-visible legality variables even
		// though they have no concrete Hop mutation. Omitting them dropped the
		// source->boundary->formal constraints and let the optimizer accept assignments
		// that no shared planner could project (notably StepLM's shared y formal).
		List<Node> nodes = analysis.graph().decisionNodes();
		Map<CompiledHopKey,List<Link>> incoming = incomingLinks(analysis);
		List<DecisionDomain> domains = new ArrayList<>(nodes.size());
		MutableCandidateRuleLookupStatistics lookupStatistics =
			new MutableCandidateRuleLookupStatistics();
		for(Node node : nodes) {
			List<CandidateRuleFact> ownerRules = isSyntheticFunctionBoundary(node) ? List.of()
				: ruleLookup.rulesFor(node);
			if(!isSyntheticFunctionBoundary(node))
				lookupStatistics.record(ruleLookup.factsExamined(node, ownerRules));
			List<Alternative> alternatives = alternatives(analysis, node,
				incoming.getOrDefault(node.key(), List.of()), ownerRules);
			ExactCategoricalSolver.Variable variable = new ExactCategoricalSolver.Variable(
				node.key().normalizedSignature(), alternatives.size());
			domains.add(new DecisionDomain(node, variable, alternatives));
		}
		Map<CompiledHopKey,DecisionDomain> byDecision = new IdentityHashMap<>();
		for(DecisionDomain domain : domains)
			byDecision.put(domain.node().key(), domain);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		addNeutralConstraintFactors(analysis.graph(), byDecision, factors);
		addStrictTransientFactors(analysis, byDecision, factors);
		addLogicalBoundaryFactors(analysis, byDecision, factors);
		addRealizationSupportFactors(analysis, byDecision, factors);
		addDerivedFoutAnchorFactors(analysis.graph(), byDecision, factors);
		addInputAuthorityFactors(analysis, incoming, byDecision, factors);
		addLatentWdivmmRuntimeInputFactors(analysis, byDecision, factors);
		return new ExactPhysicalModel(analysis, domains, factors, lookupStatistics.snapshot());
	}

	private static boolean isSyntheticFunctionBoundary(Node node) {
		return node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
			|| node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT;
	}

	List<DecisionDomain> domains() { return domains; }
	PlacementAnalysis analysis() { return analysis; }
	CandidateRuleLookupStatistics candidateRuleLookupStatistics() {
		return candidateRuleLookupStatistics;
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

	private static List<Alternative> alternatives(PlacementAnalysis analysis, Node node, List<Link> incoming,
		List<CandidateRuleFact> ownerRules) {
		List<Alternative> alternatives = new ArrayList<>();
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
							analysis, rule, state, incoming, supportClause))
							alternatives.add(candidate(node, state, rule, emission, realization, supportClause, outputAction, bindings));
				}
				else
					for(List<InputAuthority> bindings : inputAuthorityProducts(analysis, rule, state, incoming, supportClause))
						alternatives.add(candidate(node, state, rule, emission, realization, supportClause, null, bindings));
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
		Map<String,Alternative> unique = new LinkedHashMap<>();
		for(Alternative alternative : alternatives)
			unique.putIfAbsent(alternative.signature(), alternative);
		if(unique.isEmpty())
			throw new IllegalArgumentException("EXACT_PHYSICAL_DOMAIN_EMPTY|key="
				+ node.key().normalizedSignature() + "|legal=" + node.legalAlternatives()
				+ "|candidates=" + ownerRules.stream()
					.map(fact -> fact.key().orderedInputs() + ":" + fact.status() + ":"
						+ fact.allowedEmissionFacts().stream().map(emission -> emission.selectionSignature()
							+ " realizations=" + emission.realizations().size()).toList()).toList());
		return unique.values().stream().sorted(Comparator.comparing(Alternative::signature)).toList();
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
		List<InputAuthority> bindings) {
		String signature = "CAPTURED|" + state.normalizedSignature() + "|rule="
			+ rule.key().normalizedSignature() + "|emission=" + emission.selectionSignature()
			+ "|realization=" + realization.key().normalizedSignature() + "|clause=" + supportClause.normalizedSignature()
			+ "|foutMaterializationAction="
			+ (outputAction == null ? "-" : outputAction.normalizedSignature())
			+ "|inputs=" + bindings.stream().map(InputAuthority::signature).toList();
		return new Alternative(node.key(), state, AuthorityKind.CAPTURED_RULE, rule, emission,
			null, null, null, null, outputAction, rule.key().orderedInputs(), bindings, realization, supportClause, signature);
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
		CandidateRuleFact rule, PlacementState targetState, List<Link> incoming,
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
					authorities.add(new InputAuthority(position, InputAuthorityKind.RELOCATION,
						input.fType(), link.sourceNode.key(), action));
				}
				if(authorities.isEmpty() && link.kind != LinkKind.COMPILED)
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
			incoming.computeIfAbsent(input.targetRead(), ignored -> new ArrayList<>())
				.add(new Link(source, input.targetRead(), input.logicalPosition(),
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
				CandidateSelectionReceipt source = candidateReceipt(analysis, selected(write, scope, values));
				CandidateSelectionReceipt target = candidateReceipt(analysis, selected(read, scope, values));
				return input.compatibility().stream().anyMatch(edge ->
					CandidateSelections.matchesRealization(edge.sourceRealization(), source)
						&& CandidateSelections.matchesRealization(edge.readerRealization(), target))
					? 0.0 : Double.POSITIVE_INFINITY;
			}));
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
				Alternative read = selected(target, scope, values);
				if(read.state().output() == FederatedOutput.LOUT)
					return 0.0;
				Alternative write = selected(source, scope, values);
				return LogicalBoundaryRealizations.compatible(read.realization(), read.supportClause(),
					write.realization(), write.supportClause()) ? 0.0 : Double.POSITIVE_INFINITY;
			}));
		}
	}

	/** Selected native-map proofs must select their actual supporting input rows too. */
	private static void addRealizationSupportFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,DecisionDomain> domains, List<ExactCategoricalSolver.Factor> factors) {
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
			for(Alternative alternative : consumer.alternatives())
				if(alternative.realization() != null)
					for(var support : alternative.supportClause().requiredInputSupport())
						dependencies.add(support.rule().parentOccurrence());
			for(CompiledHopKey dependency : dependencies) {
				DecisionDomain source = domains.get(dependency);
				if(source == null)
					throw new IllegalArgumentException("Realization proof has no physical decision owner");
				List<DecisionDomain> scope = consumer == source ? List.of(consumer) : List.of(consumer, source);
				factors.add(ExactCategoricalSolver.Factor.lazy(scope.stream().map(DecisionDomain::variable).toList(), values -> {
					Alternative owner = selected(consumer, scope, values);
					if(owner.realization() == null)
						return 0.0;
					CandidateSelectionReceipt selectedSource = candidateReceipt(analysis, selected(source, scope, values));
					return owner.supportClause().requiredInputSupport().stream()
						.filter(reference -> reference.rule().parentOccurrence() == dependency)
						.allMatch(reference -> CandidateSelections.matchesRealization(reference, selectedSource))
						? 0.0 : Double.POSITIVE_INFINITY;
				}));
			}
		}
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

	private static void addInputAuthorityFactors(PlacementAnalysis analysis,
		Map<CompiledHopKey,List<Link>> incoming, Map<CompiledHopKey,DecisionDomain> domains,
		List<ExactCategoricalSolver.Factor> factors) {
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy =
			RelocationSelections.relocationPrivacyIndex(analysis, analysis.graph(),
				analysis.graph().relocationActions());
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
				LinkedHashSet<DecisionDomain> scopeDomains = new LinkedHashSet<>();
				scopeDomains.add(consumer);
				scopeDomains.add(directSource);
				for(Node node : analysis.graph().decisionNodes())
					if(node.valueVersion().equals(link.sourceNode.valueVersion())) {
						DecisionDomain source = domains.get(node.key());
						if(source != null) scopeDomains.add(source);
					}
				List<DecisionDomain> scope = List.copyOf(scopeDomains);
				factors.add(ExactCategoricalSolver.Factor.lazy(
					scope.stream().map(DecisionDomain::variable).toList(), values ->
						inputSatisfied(analysis, relocationPrivacy, link, consumer,
							directSource, scope, values)));
			}
		}
	}

	private static double inputSatisfied(PlacementAnalysis analysis,
		RelocationSelections.RelocationPrivacyIndex relocationPrivacy,
		Link link, DecisionDomain consumer,
		DecisionDomain directSource, List<DecisionDomain> scope, int[] values) {
		NeutralPlacementGraph graph = analysis.graph();
		Alternative selectedConsumer = selected(consumer, scope, values);
		if(selectedConsumer.orderedInputs().isEmpty()
			|| link.position >= selectedConsumer.orderedInputs().size())
			return 0.0;
		List<InputAuthority> matching = selectedConsumer.inputAuthorities().stream()
			.filter(candidate -> candidate.inputPosition() == link.position)
			.filter(candidate -> candidate.kind() == InputAuthorityKind.NATIVE_LOCAL
				|| candidate.sourceDecision() == link.sourceNode.key()).toList();
		if(matching.size() != 1)
			return Double.POSITIVE_INFINITY;
		InputAuthority authority = matching.get(0);
		Alternative source = selected(directSource, scope, values);
		if(authority.kind() == InputAuthorityKind.NATIVE_LOCAL)
			// ABSENT_LOCAL describes the FED instruction's native coordinator-local input
			// mode, not the producer's selected output placement. A FOUT producer remains
			// legal; its boundary preparation belongs to the canonical cost factors.
			return inputAuthorityPlacementSatisfied(authority, source.state()) ? 0.0
				: Double.POSITIVE_INFINITY;
		Map<CompiledHopKey,PlacementState> assignment = selectedStates(scope, values);
		List<CandidateSelectionReceipt> selectedCandidates =
			selectedCandidateReceipts(analysis, scope, values);
		if(authority.kind() == InputAuthorityKind.DIRECT_FOUT)
			return directFoutSatisfied(graph, link.sourceNode, consumer.node().key(), link.position,
				selectedConsumer.state(), authority.expectedFType(), authority.relocationAction(), assignment,
				selectedCandidates) ? 0.0
				: Double.POSITIVE_INFINITY;
		RelocationAction action = authority.relocationAction();
		if(!action.key().sourceValueVersion().equals(link.sourceNode.valueVersion()))
			return Double.POSITIVE_INFINITY;
		boolean required = action.obligations().stream().anyMatch(obligation ->
			obligation.consumer() == consumer.node().key() && obligation.inputPosition() == link.position
				&& obligation.requiredPlacement().equals(selectedConsumer.state()));
		if(!required)
			return Double.POSITIVE_INFINITY;
		if(!graph.isRelocationActive(action, assignment, selectedCandidates))
			return Double.POSITIVE_INFINITY;
		if(!relocationPrivacy.isPrivacySafe(action, true))
			return Double.POSITIVE_INFINITY;
		return source.state().output() == FederatedOutput.LOUT
			|| source.state().output() == FederatedOutput.FOUT ? 0.0 : Double.POSITIVE_INFINITY;
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
		PlacementState sourceState = assignment.get(source.key());
		if(sourceState == null || sourceState.output() != FederatedOutput.FOUT
			|| sourceState.fType() != expectedFType)
			return false;
		List<RelocationAction> matching = graph.relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(source.valueVersion()))
			.filter(action -> action.key().materializationFType() == expectedFType)
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == consumer && obligation.inputPosition() == inputPosition
					&& obligation.requiredPlacement().equals(consumerState)))
			.toList();
		if(exactAction != null)
			return matching.stream().anyMatch(action -> action == exactAction)
				&& !graph.isRelocationActive(exactAction, assignment, selectedCandidates);
		return matching.isEmpty() || matching.stream().anyMatch(action ->
			!graph.isRelocationActive(action, assignment, selectedCandidates));
	}

	private static Map<CompiledHopKey,PlacementState> selectedStates(
		List<DecisionDomain> scope, int[] values) {
		Map<CompiledHopKey,PlacementState> result = new IdentityHashMap<>();
		for(int index = 0; index < scope.size(); index++)
			result.put(scope.get(index).node().key(),
				scope.get(index).alternatives().get(values[index]).state());
		return result;
	}

	private static List<CandidateSelectionReceipt> selectedCandidateReceipts(
		PlacementAnalysis analysis, List<DecisionDomain> scope, int[] values) {
		List<CandidateSelectionReceipt> result = new ArrayList<>();
		for(int index = 0; index < scope.size(); index++) {
			Alternative alternative = scope.get(index).alternatives().get(values[index]);
			CandidateRuleFact rule = alternative.captured()
				? alternative.candidateRule() : alternative.executionRule();
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			if(rule != null && emission != null)
				result.add(analysis.canonicalCandidateReceipt(rule.key(), emission, alternative.realization(), alternative.supportClause()));
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

	private static Alternative selected(DecisionDomain domain, List<DecisionDomain> scope, int[] values) {
		int index = scope.indexOf(domain);
		return domain.alternatives().get(values[index]);
	}

	private record Link(Node sourceNode, CompiledHopKey consumer, int position, LinkKind kind) { }
}

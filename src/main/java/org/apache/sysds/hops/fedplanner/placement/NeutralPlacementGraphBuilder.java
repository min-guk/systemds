/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
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
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.BoundaryName;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathwiseReentryFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleNote;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.DecisionEvidence;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionCallBoundary;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionInputBoundary;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionOutputBoundary;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;

/** Finite mutation-free construction of the planner-neutral shadow graph. */
public final class NeutralPlacementGraphBuilder {
	private static final int CFG_FUNCTION_INPUT_DEFINITION = -1;
	private static final String CFG_FUNCTION_INPUT_PREFIX = "cfg-function-input:";
	private final OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

	/**
	 * Canonical constraint accumulator that materializes each structural identity once.
	 * A TreeSet repeatedly rebuilt the nested occurrence/value signatures at every
	 * comparison (O(log n) serializations per insertion), even though constraints are
	 * immutable after construction.
	 */
	private static final class CanonicalConstraintSet extends java.util.AbstractSet<Constraint> {
		private final java.util.NavigableMap<String,Constraint> constraints = new java.util.TreeMap<>();

		@Override
		public boolean add(Constraint constraint) {
			Objects.requireNonNull(constraint, "constraint");
			return constraints.putIfAbsent(constraint.normalizedSignature(), constraint) == null;
		}

		@Override
		public java.util.Iterator<Constraint> iterator() {
			return constraints.values().iterator();
		}

		@Override
		public int size() {
			return constraints.size();
		}
	}

	/**
	 * Builder-owned program-structure guard. It admits only the construction baseline and exact
	 * placement structures committed by PlacementEmissionTransaction. The analysis receives this
	 * as an opaque authority and therefore cannot traverse or reconstruct the program universe.
	 */
	private static final class ProgramStructureGuard
		implements PlacementAnalysis.ProgramStructureAuthority {
		private final DMLProgram program;
		private final Set<String> authorizedFingerprints = new LinkedHashSet<>();
		private final Map<String,List<String>> authorizedRows = new LinkedHashMap<>();

		private ProgramStructureGuard(DMLProgram program, String baselineFingerprint) {
			this.program = Objects.requireNonNull(program, "program");
			if(baselineFingerprint == null || baselineFingerprint.isBlank())
				throw new IllegalArgumentException("Program structure baseline must not be blank");
			authorize(baselineFingerprint);
		}

		@Override
		public synchronized void run() {
			String current = PlacementGraphFingerprint.captureProgramAuthority(program);
			if(!authorizedFingerprints.contains(current))
				throw new IllegalStateException("PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED"
					+ "|current=" + current + "|authorized=" + authorizedFingerprints
					+ "|diff="
					+ describeDifference(PlacementGraphFingerprint.canonicalProgramAuthorityRows(program)));
		}

		@Override
		public synchronized void authorizeCommittedEmission() {
			authorize(PlacementGraphFingerprint.captureProgramAuthority(program));
		}

		private void authorize(String fingerprint) {
			authorizedFingerprints.add(fingerprint);
			authorizedRows.put(fingerprint, PlacementGraphFingerprint.canonicalProgramAuthorityRows(program));
		}

		private String describeDifference(List<String> current) {
			List<String> nearest = authorizedRows.values().stream().min(java.util.Comparator.comparingInt(rows -> {
				Set<String> symmetric = new LinkedHashSet<>(rows);
				for(String row : current)
					if(!symmetric.add(row)) symmetric.remove(row);
				return symmetric.size();
			})).orElse(List.of());
			Set<String> removed = new LinkedHashSet<>(nearest);
			removed.removeAll(current);
			Set<String> added = new LinkedHashSet<>(current);
			added.removeAll(nearest);
			return "removed=" + removed.stream().limit(8).toList()
				+ ",added=" + added.stream().limit(8).toList();
		}
	}

	public List<String> selectedProjection(DMLProgram program) {
		PlacementAnalysis analysis = buildAnalysis(program);
		List<String> selected = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences()) {
			Hop hop = occurrence.hop();
			ExecType selectedExec = selectedExecType(hop);
			selected.add(occurrence.key().functionNamespace() + '|' + occurrence.key().callSitePath() + '|'
				+ occurrence.key().emittedHopInstance() + '|' + occurrence.key().canonicalSourceOrigin() + '|'
				+ String.valueOf(selectedExec) + '/' + String.valueOf(hop.getFederatedOutput()));
		}
		Collections.sort(selected);
		return Collections.unmodifiableList(selected);
	}

	public List<String> selectedMembershipViolations(DMLProgram program, NeutralPlacementGraph graph) {
		PlacementAnalysis analysis = buildAnalysis(program);
		List<String> violations = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences()) {
			Hop hop = occurrence.hop();
			ExecType selectedExec = selectedExecType(hop);
			if(selectedExec == null || hop.getFederatedOutput() == FederatedOutput.NONE) continue;
			Node node = graph.node(occurrence.key()).orElse(null);
			boolean member = node != null && node.legalAlternatives().stream().anyMatch(s ->
				s.execType() == selectedExec && s.output() == hop.getFederatedOutput());
			if(!member) violations.add(occurrence.key().functionNamespace() + '|' + occurrence.key().callSitePath() + '|'
				+ occurrence.key().emittedHopInstance() + '|' + occurrence.key().canonicalSourceOrigin() + '|'
				+ selectedExec + '/' + hop.getFederatedOutput());
		}
		Collections.sort(violations);
		return Collections.unmodifiableList(violations);
	}

	private static ExecType selectedExecType(Hop hop) {
		return hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
	}

	public NeutralPlacementGraph build(DMLProgram program) {
		return buildAnalysis(program).graph();
	}

	public PlacementAnalysis buildAnalysis(DMLProgram program) {
		return buildDetachedAnalysis(program);
	}

	public PlacementAnalysis requireAuthoritativeAnalysis(DMLProgram program) {
		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		analysis.assertCanonicalProgramAuthority(program);
		return analysis;
	}

	public PlacementAnalysis buildDetachedAnalysis(DMLProgram program) {
		String before = PlacementGraphFingerprint.capture(program);
		String registryBefore = registrySentinel(program);
		List<StatementBlock> topLevelStatementBlocks = List.copyOf(program.getStatementBlocks());
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = PlacementGraphFingerprint.orderedOccurrences(program);
		String programId = structuralFingerprint(occurrences);
		CfgAnalysis cfg = analyzeCfg(program, topLevelStatementBlocks, occurrences);
		List<Node> nodes = new ArrayList<>();
		Map<Hop,ValueVersionKey> values = new IdentityHashMap<>();
		Map<StatementBlock,Map<Hop,CompiledHopKey>> keysByBlock = new IdentityHashMap<>();
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock = new IdentityHashMap<>();
		Set<Hop> ownedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<Hop,Node> nodesByHop = new IdentityHashMap<>();
		Map<Hop,DurableAnchorKey> anchorProvenance = new IdentityHashMap<>();
		List<DurableAnchorKey> occurrenceAnchorProvenance = new ArrayList<>();
		Map<CompiledHopKey,Hop> origins = new java.util.LinkedHashMap<>();
		Map<CompiledHopKey,Long> scopes = new java.util.LinkedHashMap<>();
		Map<Hop,NodeShapeFact> factsByHop = new IdentityHashMap<>();
		List<CandidateRuleKey> candidateRuleDomainKeys = new ArrayList<>();
		List<CandidateRuleFact> candidateRuleFacts = new ArrayList<>();
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys = new ArrayList<>();
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts = new ArrayList<>();
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts = new ArrayList<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			Hop hop = occurrence.hop();
			String context = requiresRecompileMetadata(hop) || occurrence.dynamicRecompileRegion()
				? "recompile" : "compiled";
			ControlRegionKey region = new ControlRegionKey(programId, occurrence.namespace(),
				occurrence.regionPath(), occurrence.path(), context);
			CompiledHopKey key = new CompiledHopKey(programId, occurrence.namespace(), occurrence.path(), context, region,
				occurrence.topology(), PlacementGraphFingerprint.semanticStructuralKey(hop));
			origins.put(key, hop);
			scopes.put(key, occurrence.block().getSBID());
			NodeShapeFact shapeFact = deriveNodeShapeFact(hop);
			factsByHop.put(hop, shapeFact);
			String variable = lexicalVariable(hop, ordinal);
			int version = cfg.definitionOrdinals().get(ordinal);
			// CLONE_RECOMPILE identifies a concrete Hop whose own metadata requires a
			// clone. An inherited function/loop recompile region still retains its exact
			// CFG value kind (notably FUNCTION_INPUT), while its CompiledHopKey context
			// independently closes CP/FOUT for runtime recompilation.
			VersionKind versionKind = requiresRecompileMetadata(hop) ? VersionKind.CLONE_RECOMPILE
				: cfg.versionKinds().get(ordinal);
			List<String> predecessorEdges = new ArrayList<>();
			for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
				Hop input = hop.getInput(inputPosition);
				if(values.containsKey(input)) predecessorEdges.add("input-" + inputPosition + ':'
					+ values.get(input).cfgReferenceSignature());
			}
			ValueVersionKey value = new ValueVersionKey(programId, variable, region, version, versionKind,
				predecessorEdges);
			values.put(hop, value);
			Map<Hop,CompiledHopKey> blockKeys = keysByBlock.computeIfAbsent(occurrence.block(),
				ignored -> new IdentityHashMap<>());
			if(blockKeys.put(hop, key) != null)
				throw new IllegalStateException("Duplicate physical Hop within one exact statement-block occurrence");
			ordinalsByBlock.computeIfAbsent(occurrence.block(), ignored -> new IdentityHashMap<>())
				.put(hop, ordinal);
			ownedHops.add(hop);
			List<DurableAnchorKey> anchors = durableAnchor(hop);
			if(anchors.isEmpty() && cfg.reachingFunctionInputs().get(ordinal))
				anchors = functionInputAnchors(hop, occurrence, occurrences, nodesByHop, cfg);
			List<NodeShapeFact> inputShapeFacts = new ArrayList<>(hop.getInput().size());
			for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
				NodeShapeFact inputShapeFact = factsByHop.get(hop.getInput(inputPosition));
				if(inputShapeFact == null)
					throw new IllegalStateException("Candidate input has no builder-owned shape fact: "
						+ key + " input " + inputPosition);
				inputShapeFacts.add(inputShapeFact);
			}
			inputShapeFacts = List.copyOf(inputShapeFacts);
			List<DurableAnchorKey> inputAnchors = new ArrayList<>(hop.getInput().size());
			List<CompiledHopKey> inputAnchorOwners = new ArrayList<>(hop.getInput().size());
			for(Hop input : hop.getInput()) {
				inputAnchors.add(anchorProvenance.get(input));
				Node inputNode = nodesByHop.get(input);
				inputAnchorOwners.add(inputNode == null ? null : inputNode.key());
			}
			DurableAnchorKey occurrenceAnchor = !anchors.isEmpty() ? anchors.get(0)
				: inheritableDurableAnchor(hop, shapeFact, inputShapeFacts, inputAnchors);
			if(occurrenceAnchor == null)
				anchorProvenance.remove(hop);
			else
				anchorProvenance.put(hop, occurrenceAnchor);
			occurrenceAnchorProvenance.add(occurrenceAnchor);
			captureConsumerProfileFacts(hop, key, inputShapeFacts,
				candidateConsumerDomainKeys, candidateConsumerProfileFacts);
			List<DurableAnchorKey> exactAnchors = occurrenceAnchor == null ? List.of() : List.of(occurrenceAnchor);
			Node node = buildNode(hop, key, value, exactAnchors,
				Collections.unmodifiableList(new ArrayList<>(inputAnchors)),
				Collections.unmodifiableList(new ArrayList<>(inputAnchorOwners)), shapeFact, inputShapeFacts,
				inputDomains(hop, nodesByHop, occurrence, occurrences,
					cfg.reachingFunctionInputs().get(ordinal), cfg),
				candidateRuleDomainKeys, candidateRuleFacts);
			nodes.add(node);
			nodesByHop.put(hop, node);
		}
		captureDetachedConsumerProfileFacts(occurrences, nodes, ownedHops, factsByHop,
			detachedConsumerProfileFacts);
		if(nodes.size() != occurrences.size())
			throw new IllegalStateException("occurrence/node mismatch before CFG closure: "
				+ occurrences.size() + '/' + nodes.size());
		nodes = closeCfgValueVersions(occurrences, nodes, values, cfg);
		AnchorClosure anchorClosure = closeCfgDurableAnchors(occurrences, nodes, occurrenceAnchorProvenance, cfg, factsByHop);
		nodes = anchorClosure.nodes();
		occurrenceAnchorProvenance = anchorClosure.anchors();
		CandidateReplay candidateReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
			factsByHop, ordinalsByBlock, candidateRuleDomainKeys, candidateRuleFacts);
		nodes = candidateReplay.nodes();
		candidateRuleDomainKeys = candidateReplay.domainKeys();
		candidateRuleFacts = candidateReplay.facts();
		List<LogicalTransientInputFact> logicalTransientInputs = candidateReplay.logicalInputs();
		nodes = reclassifyStandaloneRecompileOccurrences(occurrences, nodes);
		nodes = classifyOrphanFunctionBodies(occurrences, nodes);
		if(nodes.size() != occurrences.size())
			throw new IllegalStateException("occurrence/node mismatch after CFG closure: "
				+ occurrences.size() + '/' + nodes.size());
		FunctionExpansion functionExpansion = expandFunctionBoundaryContexts(program, cfg, occurrences, nodes,
			origins, scopes);
		nodes = functionExpansion.nodes();
		origins = functionExpansion.origins();
		scopes = functionExpansion.scopes();
		nodesByHop.clear();
		for(int i = 0; i < occurrences.size(); i++) nodesByHop.put(occurrences.get(i).hop(), nodes.get(i));
		Set<Constraint> constraints = new CanonicalConstraintSet();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			CompiledHopKey consumer = nodes.get(ordinal).key();
			Map<Hop,CompiledHopKey> blockKeys = keysByBlock.get(occurrence.block());
			for(int inputPosition = 0; inputPosition < occurrence.hop().getInput().size(); inputPosition++) {
				Hop input = occurrence.hop().getInput(inputPosition);
				CompiledHopKey inputKey = blockKeys == null ? null : blockKeys.get(input);
				if(inputKey != null) {
					Node inputNode = nodesByHop.get(input);
					Node consumerNode = nodes.get(ordinal);
					boolean formalBinding = isTransparentFunctionInputBinding(input, occurrence.hop(),
						inputPosition, inputNode, consumerNode);
					boolean multiReturnOutputValue = isMultiReturnBuiltinOutputCarrier(occurrence.hop());
					constraints.add(new Constraint(formalBinding ? ConstraintKind.SAME_PLACEMENT
						: ConstraintKind.DOMINATES, inputKey, consumer, inputPosition,
						formalBinding ? "function-input-binding"
							: multiReturnOutputValue ? "multi-return-output-value" : "data-input"));
				}
			}
		}
		addCfgConstraints(occurrences, nodes, constraints, cfg);
		constraints.addAll(functionExpansion.constraints());
		addCfgFunctionOutputConstraints(occurrences, nodes, constraints, cfg, functionExpansion);
		addStableOriginConstraints(nodes, constraints);
		List<CompiledInputEdgeFact> compiledInputEdges = deriveCompiledInputEdges(occurrences, nodes,
			ordinalsByBlock, factsByHop);
		CandidateReplay materializationReplay = closeWorkerPoolMaterializationDependencies(
			occurrences, nodes, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
			compiledInputEdges, constraints, origins, factsByHop, ordinalsByBlock, cfg);
		nodes = materializationReplay.nodes();
		candidateRuleDomainKeys = materializationReplay.domainKeys();
		candidateRuleFacts = materializationReplay.facts();
		boolean functionClosureConverged = false;
		int maxFunctionClosurePasses = Math.max(1,
			occurrences.size() * (FType.values().length + 1));
		for(int pass = 0; pass < maxFunctionClosurePasses; pass++) {
			List<Node> passNodes = nodes;
			List<CandidateRuleKey> passDomainKeys = candidateRuleDomainKeys;
			List<CandidateRuleFact> passFacts = candidateRuleFacts;
			FunctionInputCandidateClosure functionInputClosure = closeLogicalFunctionInputCandidates(
				nodes, candidateRuleDomainKeys, candidateRuleFacts, functionExpansion.constraints(), origins,
				factsByHop, occurrences.size());
			nodes = functionInputClosure.nodes();
			candidateRuleDomainKeys = functionInputClosure.domainKeys();
			candidateRuleFacts = functionInputClosure.facts();
			if(!functionInputClosure.changedOrdinals().isEmpty()) {
				CandidateReplay functionReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
						logicalTransientInputs, functionInputClosure.changedOrdinals()),
					factsByHop, ordinalsByBlock, cfg);
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, functionReplay.nodes(), functionReplay.domainKeys(), functionReplay.facts(),
					functionReplay.logicalInputs(), compiledInputEdges, constraints, origins, factsByHop,
					ordinalsByBlock, cfg);
				nodes = materializationReplay.nodes();
				candidateRuleDomainKeys = materializationReplay.domainKeys();
				candidateRuleFacts = materializationReplay.facts();
				logicalTransientInputs = materializationReplay.logicalInputs();
			}

			// FunctionCallCP aliases the exact returned Data object. Its synthetic output
			// boundary is constructed after the original CFG candidate pass, so refresh the
			// boundary from the latest formal-exit states and replay any caller TRead whose
			// reaching definition is that output. Otherwise a legal FED/FOUT return can remain
			// spuriously CP-only solely because fingerprint order visited the read first.
			nodes = refreshFunctionOutputBoundaryAlternatives(nodes, functionExpansion.constraints());
			FunctionOutputCandidateClosure functionOutputClosure = closeCfgFunctionOutputCandidates(
				occurrences, nodes, candidateRuleDomainKeys, candidateRuleFacts, cfg, functionExpansion,
				origins, factsByHop);
			nodes = functionOutputClosure.nodes();
			candidateRuleDomainKeys = functionOutputClosure.domainKeys();
			candidateRuleFacts = functionOutputClosure.facts();
			if(!functionOutputClosure.changedOrdinals().isEmpty()) {
				CandidateReplay functionReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
						logicalTransientInputs, functionOutputClosure.changedOrdinals()),
					factsByHop, ordinalsByBlock, cfg);
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, functionReplay.nodes(), functionReplay.domainKeys(), functionReplay.facts(),
					functionReplay.logicalInputs(), compiledInputEdges, constraints, origins, factsByHop,
					ordinalsByBlock, cfg);
				nodes = materializationReplay.nodes();
				candidateRuleDomainKeys = materializationReplay.domainKeys();
				candidateRuleFacts = materializationReplay.facts();
				logicalTransientInputs = materializationReplay.logicalInputs();
			}
			nodes = refreshFunctionOutputBoundaryAlternatives(nodes, functionExpansion.constraints());
			if(nodes.equals(passNodes) && candidateRuleDomainKeys.equals(passDomainKeys)
				&& candidateRuleFacts.equals(passFacts)) {
				functionClosureConverged = true;
				break;
			}
		}
		if(!functionClosureConverged)
			throw new IllegalStateException("Logical function boundary candidate closure did not converge");
		PrivacyClosure privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
			constraints, origins);
		nodes = privacyClosure.nodes();
		candidateRuleFacts = privacyClosure.candidateRuleFacts();
		PlacementPrivacyFacts privacyFacts = privacyClosure.privacyFacts();
		candidateRuleFacts = bindExactCandidateEmissionStates(candidateRuleFacts, nodes);
		candidateRuleFacts = bindExactDerivedFoutAuthorities(candidateRuleFacts, scopes, nodes, origins);
		logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, nodes);
		// TRead/TWrite is a planner-wide legality boundary, not an Exact-only factor:
		// the runtime accepts only the exact CP/LOUT or FED/FOUT tuple carried by
		// the unique logical source. Publishing it in the neutral graph prevents
		// FedAll/Heuristic from selecting a mismatched read/write pair.
		for(LogicalTransientInputFact input : logicalTransientInputs)
			constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
				input.sourceWrite(), input.targetRead(), input.logicalPosition(),
				"logical-transient-input"));
		List<NeutralPlacementGraph.RelocationAction> relocations = relocations(compiledInputEdges, candidateRuleFacts,
			nodes, logicalTransientInputs, constraints, origins, scopes, factsByHop);
		List<NeutralPlacementGraph.DerivedFoutMaterializationAction> derivedFoutActions = candidateRuleFacts.stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.map(CandidateEmissionFact::derivedFoutAction).filter(Objects::nonNull).distinct()
			.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::new).sorted().toList();
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, constraints, relocations, derivedFoutActions);
		List<HopOccurrenceProjection> projections = new ArrayList<>(graph.nodes().size());
		for(int ordinal = 0; ordinal < graph.nodes().size(); ordinal++) {
			CompiledHopKey key = graph.nodes().get(ordinal).key();
			Hop hop = origins.get(key);
			if(hop == null)
				throw new IllegalStateException("Neutral placement node has no compiled Hop origin: " + key);
			Long scopeId = scopes.get(key);
			if(scopeId == null)
				throw new IllegalStateException("Neutral placement node has no statement-block scope: " + key);
			projections.add(new HopOccurrenceProjection(key, hop, scopeId, ordinal, key.normalizedSignature()));
		}
		Set<CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		var factsByKey = new LinkedHashMap<CompiledHopKey, NodeShapeFact>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			NodeShapeFact shapeFact = factsByHop.get(projection.hop());
			if(shapeFact == null)
				throw new IllegalStateException("Placement projection has no builder-owned shape fact: " + projection.key());
			factsByKey.put(projection.key(), shapeFact);
		}
		PlacementShapeFacts shapeFacts = new PlacementShapeFacts(factsByKey, expectedKeys);
		String analysisFingerprint = analysisFingerprint(graph, projections);
		HeuristicPolicyFacts heuristicPolicyFacts = heuristicPolicyFacts(graph, projections, shapeFacts,
			compiledInputEdges, candidateRuleFacts, occurrences, cfg);
		ProgramStructureGuard programStructureGuard =
			new ProgramStructureGuard(program,
				PlacementGraphFingerprint.captureProgramAuthority(program));
		PlacementAnalysis analysis = new PlacementAnalysis(graph, projections, topLevelStatementBlocks, program, shapeFacts,
			analysisFingerprint, heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts, programStructureGuard);
		String after = PlacementGraphFingerprint.capture(program);
		if(!before.equals(after))
			throw new IllegalStateException("Neutral placement analysis mutated the compiled Hop graph");
		if(!registryBefore.equals(registrySentinel(program)))
			throw new IllegalStateException("Neutral placement analysis mutated federated refed state");
		return analysis;
	}

	private record PrivacyClosure(List<Node> nodes, List<CandidateRuleFact> candidateRuleFacts,
		PlacementPrivacyFacts privacyFacts) { }

	/**
	 * Resolve one occurrence-scoped privacy lattice before any planner selector is
	 * created, then remove candidate emissions that violate that common authority.
	 */
	private static PrivacyClosure closePrivacyDomains(List<Node> nodes,
		List<CandidateRuleFact> candidateRuleFacts, Set<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins) {
		Map<CompiledHopKey,List<CompiledHopKey>> predecessors = new IdentityHashMap<>();
		for(Node node : nodes) {
			predecessors.put(node.key(), new ArrayList<>());
		}
		for(Constraint constraint : constraints)
			if(carriesPrivacyValue(constraint))
				addPrivacyPredecessor(predecessors, constraint.right(), constraint.left());

		Map<String,List<CompiledHopKey>> cfgSources = new java.util.HashMap<>();
		for(Node node : nodes)
			cfgSources.computeIfAbsent(node.valueVersion().cfgReferenceSignature(),
				ignored -> new ArrayList<>()).add(node.key());
		for(Node node : nodes)
			for(String predecessor : node.valueVersion().predecessorVersions())
				if(predecessor.startsWith("cfg-definition:")) {
					String reference = predecessor.substring("cfg-definition:".length());
					List<CompiledHopKey> sources = cfgSources.get(reference);
					if(sources == null || sources.isEmpty())
						throw new IllegalStateException("Privacy CFG definition has no occurrence owner: "
							+ reference);
					for(CompiledHopKey source : sources)
						addPrivacyPredecessor(predecessors, node.key(), source);
				}
		for(List<CompiledHopKey> inputs : predecessors.values())
			inputs.sort(null);

		Map<DataOp,FederatedSourceMetadata> sourceMetadata = new IdentityHashMap<>();
		List<Pair<FederatedRange,FederatedData>> allPartitions = new ArrayList<>();
		Map<CompiledHopKey,Privacy> effective = new IdentityHashMap<>();
		for(Node node : nodes) {
			Hop hop = origins.get(node.key());
			if(hop == null)
				throw new IllegalStateException("Privacy occurrence has no compiled Hop origin");
			if(isFederatedSource(hop)) {
				DataOp source = (DataOp) hop;
				FederatedSourceMetadata metadata = sourceMetadata.get(source);
				if(metadata == null) {
					metadata = FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
					sourceMetadata.put(source, metadata);
					allPartitions.addAll(metadata.partitions());
				}
				effective.put(node.key(), metadata.privacy());
			}
			else
				effective.put(node.key(), Privacy.PUBLIC);
		}

		boolean changed;
		int pass = 0;
		int maxPasses = Math.max(1, nodes.size() * (Privacy.values().length + 1));
		do {
			changed = false;
			for(Node node : nodes) {
				Hop hop = origins.get(node.key());
				Privacy derived;
				if(isFederatedSource(hop))
					derived = sourceMetadata.get((DataOp) hop).privacy();
				else {
					List<Privacy> inputPrivacy = predecessors.get(node.key()).stream()
						.map(effective::get).toList();
					derived = node.kind() == NodeKind.FUNCTION_INPUT
						|| node.kind() == NodeKind.FUNCTION_OUTPUT
						? strongestPrivacy(inputPrivacy)
						: FederatedPlannerUtils.derivePrivacyConstraint(hop, inputPrivacy);
				}
				Privacy prior = effective.get(node.key());
				Privacy next = FederatedPlannerUtils.joinPrivacy(prior, derived);
				if(next != prior) {
					effective.put(node.key(), next);
					changed = true;
				}
			}
			pass++;
		}
		while(changed && pass < maxPasses);
		if(changed)
			throw new IllegalStateException("Whole-program privacy propagation did not converge");

		Map<CompiledHopKey,Set<PlacementState>> candidateStates = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<PlacementState>> retainedStates = new IdentityHashMap<>();
		List<CandidateRuleFact> filteredFacts = new ArrayList<>(candidateRuleFacts.size());
		for(CandidateRuleFact fact : candidateRuleFacts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				filteredFacts.add(fact);
				continue;
			}
			Hop hop = origins.get(fact.key().parentOccurrence());
			Privacy privacy = effective.get(fact.key().parentOccurrence());
			if(hop == null || privacy == null)
				throw new IllegalStateException("Candidate privacy authority is incomplete");
			Set<PlacementState> seen = candidateStates.computeIfAbsent(
				fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>());
			Set<PlacementState> retained = retainedStates.computeIfAbsent(
				fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>());
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				seen.add(state);
				if(ExecPlacementPolicy.allowsCandidateEmission(hop, privacy, fact, emission)) {
					emissions.add(emission);
					retained.add(state);
				}
			}
			if(emissions.isEmpty()) {
				String failure = "PRIVACY:" + privacy.name();
				filteredFacts.add(new CandidateRuleFact(fact.key(),
					CandidateEvaluationStatus.PRIVACY_EXCLUDED, fact.capability(), fact.shapeProof(),
					fact.profile(), List.of(), failure));
			}
			else
				filteredFacts.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
					fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}

		List<Node> filteredNodes = new ArrayList<>(nodes.size());
		for(Node node : nodes) {
			Privacy privacy = effective.get(node.key());
			Set<PlacementState> seen = candidateStates.getOrDefault(node.key(), Set.of());
			Set<PlacementState> retained = retainedStates.getOrDefault(node.key(), Set.of());
			List<PlacementState> legal = new ArrayList<>();
			Map<PlacementState,Exclusion> exclusions = new java.util.TreeMap<>();
			for(Exclusion exclusion : node.exclusions())
				exclusions.put(exclusion.state(), exclusion);
			for(PlacementState state : node.legalAlternatives()) {
				boolean denied = privacy == Privacy.PRIVATE
					&& !(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT);
				denied |= seen.contains(state) && !retained.contains(state);
				if(denied)
					exclusions.putIfAbsent(state, new Exclusion(state, ReasonCode.PRIVACY,
						"effectivePrivacy=" + privacy.name()));
				else
					legal.add(state);
			}
			if(node.emittedWork() && legal.isEmpty())
				throw new DMLRuntimeException("No privacy-safe physical placement for occurrence "
					+ node.key().normalizedSignature() + " (privacy=" + privacy + ")");
			filteredNodes.add(new Node(node.key(), node.kind(), node.valueVersion(),
				!legal.isEmpty(), legal, new ArrayList<>(exclusions.values()), node.anchors()));
		}

		List<PlacementPrivacyFacts.PrivacyFact> privacyFacts = new ArrayList<>(filteredNodes.size());
		for(Node node : filteredNodes)
			privacyFacts.add(new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				effective.get(node.key()), predecessors.get(node.key())));
		PlacementPrivacyFacts authority = new PlacementPrivacyFacts(filteredNodes, privacyFacts,
			FederatedWorkerUtils.countDistinctWorkers(allPartitions));
		return new PrivacyClosure(List.copyOf(filteredNodes), List.copyOf(filteredFacts), authority);
	}

	private static boolean isFederatedSource(Hop hop) {
		return hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED;
	}

	private static Privacy strongestPrivacy(List<Privacy> inputPrivacy) {
		Privacy result = Privacy.PUBLIC;
		for(Privacy privacy : inputPrivacy)
			result = FederatedPlannerUtils.joinPrivacy(result, privacy);
		return result;
	}

	private static void addPrivacyPredecessor(
		Map<CompiledHopKey,List<CompiledHopKey>> predecessors,
		CompiledHopKey target, CompiledHopKey source) {
		List<CompiledHopKey> inputs = predecessors.get(target);
		if(inputs == null || !predecessors.containsKey(source))
			throw new IllegalStateException("Privacy flow references a foreign occurrence");
		if(inputs.stream().noneMatch(existing -> existing == source))
			inputs.add(source);
	}

	private static boolean carriesPrivacyValue(Constraint constraint) {
		String evidence = constraint.evidence();
		return "data-input".equals(evidence) || "function-input-binding".equals(evidence)
			|| "multi-return-output-value".equals(evidence)
			|| evidence.startsWith("cfg-transient-value:")
			|| evidence.startsWith("cfg-function-output-value:")
			|| evidence.startsWith("function-argument:")
			|| evidence.startsWith("inlined-function-argument:")
			|| "function-formal-input".equals(evidence)
			|| evidence.startsWith("function-result:")
			|| evidence.startsWith("inlined-function-result:");
	}

	private static List<LogicalTransientInputFact> bindExactLogicalTransientSourceStates(
		List<LogicalTransientInputFact> facts, List<Node> nodes) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		List<LogicalTransientInputFact> rebound = new ArrayList<>(facts.size());
		for(LogicalTransientInputFact fact : facts) {
			Node source = nodesByKey.get(fact.sourceWrite());
			if(source == null)
				throw new IllegalStateException("Logical transient source disappeared before final binding");
			PlacementState local = source.legalAlternatives().stream()
				.filter(fact.localSourceState()::equals).findFirst().orElse(null);
			PlacementState federated = source.legalAlternatives().stream()
				.filter(fact.federatedSourceState()::equals).findFirst().orElseThrow(() ->
					new IllegalStateException(
						"Logical transient federated source state disappeared before final binding"));
			rebound.add(new LogicalTransientInputFact(fact.sourceWrite(), fact.targetRead(),
				fact.logicalPosition(), fact.sourceValueVersion(), fact.readValueVersion(), fact.anchor(),
				fact.federatedFType(), local, federated, fact.localInput(), fact.federatedInput()));
		}
		return List.copyOf(rebound);
	}


	private static List<CompiledInputEdgeFact> deriveCompiledInputEdges(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, Map<Hop,NodeShapeFact> factsByHop) {
		List<CompiledInputEdgeFact> edges = new ArrayList<>();
			for(int consumerOrdinal = 0; consumerOrdinal < occurrences.size(); consumerOrdinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
			Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
			Hop consumer = occurrence.hop();
			Node consumerNode = nodes.get(consumerOrdinal);
			if(!PlacementAnalysis.isCompiledHopOccurrenceKey(consumerNode.key(), consumerNode.kind()))
				continue;
			// A MULTIRETURN_BUILTIN output carrier is not a runtime consumer of the
			// placeholder Hop input stored on the DataOp. The owning FunctionOp emits the
			// value; synthetic result constraints carry identity and privacy instead.
			if(isMultiReturnBuiltinOutputCarrier(consumer))
				continue;
			for(int inputPosition = 0; inputPosition < consumer.getInput().size(); inputPosition++) {
				Hop producer = consumer.getInput(inputPosition);
				if(!isPlacementDataShape(factsByHop, producer))
					continue;
				Integer producerOrdinal = blockOrdinals == null ? null : blockOrdinals.get(producer);
				if(producerOrdinal == null)
					throw new IllegalStateException("Placement-data producer input lacks exact compiled owner key");
				Node producerNode = nodes.get(producerOrdinal);
				CompiledHopKey producerKey = producerNode.key();
				if(PlacementAnalysis.isCompiledHopOccurrenceKey(producerKey, producerNode.kind()))
					edges.add(new CompiledInputEdgeFact(producerKey,
						consumerNode.key(), inputPosition));
			}
		}
		edges.sort(java.util.Comparator.comparing(CompiledInputEdgeFact::consumer)
			.thenComparingInt(CompiledInputEdgeFact::inputPosition));
		return List.copyOf(edges);
	}

	private static HeuristicPolicyFacts heuristicPolicyFacts(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CfgAnalysis cfg) {
		List<HeuristicPolicyFact> demotions = new ArrayList<>();
		for(HopOccurrenceProjection projection : projections) {
			Hop hop = projection.hop();
			Node node = graph.node(projection.key()).orElseThrow();
			NodeShapeFact shape = shapeFacts.shapeFact(projection.key()).orElseThrow();
			boolean exactLocalAlternative = node.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT && state.shapeDependent()
					&& isAggregateBinaryVectorInput(hop, shape, state.fType()));
			if(exactLocalAlternative)
				demotions.add(new HeuristicPolicyFact(projection.key(), node.valueVersion()));
		}
		return new HeuristicPolicyFacts(demotions, heuristicPaths(graph, projections, shapeFacts, demotions,
			compiledInputEdges, candidateRuleFacts, occurrences, cfg));
	}

	private static List<HeuristicPathFact> heuristicPaths(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts,
		List<HeuristicPolicyFact> demotions,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CfgAnalysis cfg) {
		Map<CompiledHopKey,List<HeuristicPathEdgeFact>> outgoing = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledInputEdges) {
			Node producer = graph.node(edge.producer()).orElseThrow();
			Node consumer = graph.node(edge.consumer()).orElseThrow();
			outgoing.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(
				new HeuristicPathEdgeFact(edge.producer(), edge.consumer(), edge.inputPosition(),
					producer.valueVersion(), consumer.valueVersion(), HeuristicPathEdgeKind.COMPILED_INPUT));
		}
		for(HeuristicPathEdgeFact edge : exactCfgHeuristicPathEdges(graph, projections, shapeFacts,
			occurrences, cfg, Set.of()))
			outgoing.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge);
		outgoing.values().forEach(edges -> edges.sort(null));

		List<HeuristicPathFact> paths = traceHeuristicPaths(graph, shapeFacts, demotions,
			compiledInputEdges, candidateRuleFacts, outgoing);
		for(int pass = 0; pass < Math.max(1, occurrences.size()); pass++) {
			Set<CompiledHopKey> provenLocal = paths.stream().flatMap(path -> path.localPrefix().stream())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			boolean changed = false;
			for(HeuristicPathEdgeFact edge : exactCfgHeuristicPathEdges(graph, projections, shapeFacts,
				occurrences, cfg, provenLocal)) {
				List<HeuristicPathEdgeFact> producerEdges = outgoing.computeIfAbsent(edge.producer(),
					ignored -> new ArrayList<>());
				if(!producerEdges.contains(edge)) {
					producerEdges.add(edge);
					producerEdges.sort(null);
					changed = true;
				}
			}
			if(!changed)
				return paths;
			paths = traceHeuristicPaths(graph, shapeFacts, demotions, compiledInputEdges,
				candidateRuleFacts, outgoing);
		}
		throw new IllegalStateException("Heuristic CFG local-phi closure did not converge");
	}

	private static List<HeuristicPathFact> traceHeuristicPaths(NeutralPlacementGraph graph,
		PlacementShapeFacts shapeFacts, List<HeuristicPolicyFact> demotions,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		Map<CompiledHopKey,List<HeuristicPathEdgeFact>> outgoing) {
		List<HeuristicPathFact> paths = new ArrayList<>();
		for(HeuristicPolicyFact demotion : demotions) {
			Set<CompiledHopKey> localPrefix = new java.util.TreeSet<>();
			Set<HeuristicPathEdgeFact> usedEdges = new java.util.TreeSet<>();
			Set<HeuristicPathwiseReentryFact> reentries = new java.util.TreeSet<>();
			java.util.ArrayDeque<CompiledHopKey> pending = new java.util.ArrayDeque<>();
			localPrefix.add(demotion.producer());
			pending.add(demotion.producer());
			while(!pending.isEmpty()) {
				CompiledHopKey producerKey = pending.removeFirst();
				for(HeuristicPathEdgeFact edge : outgoing.getOrDefault(producerKey, List.of())) {
					if(edge.kind() == HeuristicPathEdgeKind.COMPILED_INPUT) {
						HeuristicPathwiseReentryFact reentry = exactHeuristicReentry(graph, compiledInputEdges,
							candidateRuleFacts, edge.producer(), edge.consumer(), edge.inputPosition());
						if(reentry != null) {
							reentries.add(reentry);
							continue;
						}
					}
					if(!supportedLocalPathNode(graph, shapeFacts, edge.consumer())) {
						// A dependent consumer that cannot participate in the vector
						// re-entry analysis is still a local terminal when no exact
						// frontier was proven. Otherwise FedAll could synthesize an
						// unapproved upload at that very edge (for example local vector
						// -> scalar aggregate). Compiler/function boundaries remain
						// excluded because they require their own explicit path contract.
						if(supportedLocalTerminalNode(graph, edge.consumer())) {
							usedEdges.add(edge);
							localPrefix.add(edge.consumer());
						}
						continue;
					}
					usedEdges.add(edge);
					if(localPrefix.add(edge.consumer()))
						pending.addLast(edge.consumer());
				}
			}
			paths.add(new HeuristicPathFact(demotion, new ArrayList<>(localPrefix),
				new ArrayList<>(usedEdges), new ArrayList<>(reentries)));
		}
		return paths.stream().sorted().toList();
	}

	private static List<HeuristicPathEdgeFact> exactCfgHeuristicPathEdges(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		CfgAnalysis cfg, Set<CompiledHopKey> provenLocal) {
		Map<Hop,HopOccurrenceProjection> projectionsByHop = new IdentityHashMap<>();
		for(HopOccurrenceProjection projection : projections)
			projectionsByHop.put(projection.hop(), projection);
		List<HeuristicPathEdgeFact> edges = new ArrayList<>();
		for(int readOrdinal = 0; readOrdinal < occurrences.size(); readOrdinal++) {
			HopOccurrenceProjection readProjection = projectionsByHop.get(occurrences.get(readOrdinal).hop());
			Node read = readProjection == null ? null : graph.node(readProjection.key()).orElse(null);
			if(readProjection == null || read == null || !isTransientRead(readProjection.hop())
				|| read.kind() != NodeKind.TRANSIENT_READ
				|| cfg.reachingFunctionInputs().get(readOrdinal)
				|| cfg.reachingDefinitions().get(readOrdinal).isEmpty()
				|| !supportedLocalCfgForwardNode(read))
				continue;
			if(!isVector(shapeFacts.shapeFact(readProjection.key()).orElse(null)))
				continue;
			List<Node> sources = new ArrayList<>();
			boolean exact = true;
			for(int sourceOrdinal : cfg.reachingDefinitions().get(readOrdinal)) {
				if(sourceOrdinal < 0 || sourceOrdinal >= occurrences.size()) {
					exact = false;
					break;
				}
				HopOccurrenceProjection sourceProjection =
					projectionsByHop.get(occurrences.get(sourceOrdinal).hop());
				Node source = sourceProjection == null ? null : graph.node(sourceProjection.key()).orElse(null);
				if(sourceProjection == null || !isTransientWrite(sourceProjection.hop())
					|| source == null || source.kind() != NodeKind.TRANSIENT_WRITE
					|| !supportedLocalCfgForwardNode(source)
					|| !sameTransientForwardContext(source, read)
					|| !isVector(shapeFacts.shapeFact(sourceProjection.key()).orElse(null))) {
					exact = false;
					break;
				}
				sources.add(source);
			}
			if(!exact || sources.isEmpty())
				continue;
			// A phi-like TRead is local only when every reaching definition has already
			// been proven local by a Heuristic demotion path. This fixed-point rule preserves
			// loop-carried locality without treating one local predecessor as authority for
			// a potentially federated alternative.
			if(sources.size() > 1 && sources.stream().anyMatch(source -> !provenLocal.contains(source.key())))
				continue;
			for(Node source : sources)
				edges.add(new HeuristicPathEdgeFact(source.key(), read.key(), 0, source.valueVersion(),
					read.valueVersion(), HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD));
		}
		return edges.stream().sorted().toList();
	}

	private static HeuristicPathwiseReentryFact exactHeuristicReentry(NeutralPlacementGraph graph,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		CompiledHopKey localProducer,
		CompiledHopKey consumer, int inputPosition) {
		if(!supportedPathOccurrence(localProducer) || !supportedPathOccurrence(consumer))
			return null;
		Node local = graph.node(localProducer).orElseThrow();
		Node consumerNode = graph.node(consumer).orElseThrow();
		// A FunctionOp candidate describes the call instruction, not an exact placement transfer into
		// the callee CFG. Function-boundary nodes remain the common authority, and pathwise upload is
		// withheld until a compiler-owned cross-boundary path/relocation contract exists.
		if(consumerNode.kind() == NodeKind.FUNCTION_CALL)
			return null;
		List<HeuristicPathwiseReentryFact> matches = new ArrayList<>();
		for(NeutralPlacementGraph.RelocationAction action : graph.relocationActions()) {
			if(action.key().sourceValueVersion() != local.valueVersion())
				continue;
			for(ObligationKey obligation : action.obligations()) {
				if(obligation.consumer() != consumer || obligation.inputPosition() != inputPosition
					|| obligation.sourceValueVersion() != local.valueVersion()
					|| obligation.relocationAction() != action.key())
					continue;
				for(CandidateRuleFact candidate : candidateRuleFacts)
					addExactHeuristicReentryMatch(graph, compiledInputEdges, localProducer, consumer, inputPosition,
						action, obligation, candidate, matches);
			}
		}
		List<HeuristicPathwiseReentryFact> exactMatches = matches.stream().distinct().sorted().toList();
		if(exactMatches.size() == 1)
			return exactMatches.get(0);
		// Fixed-point candidate closure can prove more than one runtime-supported
		// materialization layout for the same path frontier.  For example, a local
		// column vector can be uploaded either ROW-partitioned to an existing ROW
		// anchor or BROADCAST to the same workers.  The anchor-aligned action strictly
		// avoids replicated payload/range reshaping while reaching the identical
		// consumer placement, so it is the Heuristic policy's unique dominant choice.
		// This is a policy projection over already legal actions, not a candidate-space
		// guard: DP and Exact retain and cost every exact alternative.
		List<HeuristicPathwiseReentryFact> anchorAligned = exactMatches.stream()
			.filter(match -> match.relocationAction().materializationFType()
				== match.durableAnchor().fType())
			.toList();
		return anchorAligned.size() == 1 ? anchorAligned.get(0) : null;
	}

	private static void addExactHeuristicReentryMatch(NeutralPlacementGraph graph,
		List<CompiledInputEdgeFact> compiledInputEdges, CompiledHopKey localProducer,
		CompiledHopKey consumer, int inputPosition,
		NeutralPlacementGraph.RelocationAction action, ObligationKey obligation,
		CandidateRuleFact candidate, List<HeuristicPathwiseReentryFact> matches) {
		if(candidate.key().parentOccurrence() != consumer
			|| candidate.status() != CandidateEvaluationStatus.AVAILABLE || candidate.capability() == null
			|| candidate.capability().nativeExec() != ExecType.FED
			|| candidate.capability().nativeOutput() != FederatedOutput.FOUT
			|| candidate.capability().nativeFoutFType() != action.key().durableAnchor().fType()
			|| !candidate.profile().available()
			|| !candidate.profile().producerOutputs().contains(action.key().durableAnchor().fType())
			|| inputPosition >= candidate.key().orderedInputs().size()
			// Re-entry is an explicit planner-owned LOUT->FOUT relocation.  The
			// selected runtime row must therefore consume the relocated federated
			// value as PRESENT; ABSENT_LOCAL would describe native coordinator-local
			// execution and cannot justify emitting this relocation obligation.
			|| !candidate.key().orderedInputs().get(inputPosition).equals(
				CandidateInputState.present(action.key().materializationFType())))
			return;
		PlacementState consumerState = action.key().targetPlacement();
		Node consumerNode = graph.node(consumer).orElseThrow();
		if(!consumerNode.legalAlternatives().contains(consumerState)
			|| consumerState.execType() != ExecType.FED || consumerState.output() != FederatedOutput.FOUT
			|| consumerState.fType() != action.key().durableAnchor().fType())
			return;
		List<CompiledInputEdgeFact> siblingEdges = new ArrayList<>();
		for(int siblingPosition = 0; siblingPosition < candidate.key().orderedInputs().size(); siblingPosition++) {
			if(siblingPosition == inputPosition
				|| !candidate.key().orderedInputs().get(siblingPosition)
					.equals(CandidateInputState.present(action.key().durableAnchor().fType())))
				continue;
			for(CompiledInputEdgeFact edge : compiledInputEdges)
				if(edge.consumer() == consumer && edge.inputPosition() == siblingPosition
					&& edge.producer() != localProducer && supportedPathOccurrence(edge.producer()))
					siblingEdges.add(edge);
		}
		if(siblingEdges.size() != 1)
			return;
		CompiledInputEdgeFact siblingEdge = siblingEdges.get(0);
		Node sibling = graph.node(siblingEdge.producer()).orElseThrow();
		List<PlacementState> siblingStates = sibling.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() == action.key().durableAnchor().fType())
			.filter(state -> sibling.anchors().contains(action.key().durableAnchor())).toList();
		if(siblingStates.size() != 1)
			return;
		matches.add(new HeuristicPathwiseReentryFact(localProducer,
			graph.node(localProducer).orElseThrow().valueVersion(), consumer, inputPosition,
			sibling.key(), sibling.valueVersion(), siblingEdge.inputPosition(), siblingStates.get(0),
			action.key().durableAnchor(), consumerState, candidate, action.key(), obligation, 1));
	}

	private static boolean supportedLocalPathNode(NeutralPlacementGraph graph,
		PlacementShapeFacts shapeFacts, CompiledHopKey key) {
		Node node = graph.node(key).orElseThrow();
		return supportedLocalPathOccurrence(node) && node.kind() != NodeKind.FUNCTION_CALL
			&& node.kind() != NodeKind.FUNCTION_INPUT && node.kind() != NodeKind.FUNCTION_OUTPUT
			&& node.kind() != NodeKind.FUNCTION_BODY_NON_EMITTED && isVector(shapeFacts.shapeFact(key).orElse(null));
	}

	private static boolean supportedLocalTerminalNode(NeutralPlacementGraph graph,
		CompiledHopKey key) {
		Node node = graph.node(key).orElseThrow();
		return supportedLocalPathOccurrence(node) && node.emittedWork()
			&& node.kind() != NodeKind.FUNCTION_CALL && node.kind() != NodeKind.FUNCTION_INPUT
			&& node.kind() != NodeKind.FUNCTION_OUTPUT
			&& node.kind() != NodeKind.FUNCTION_BODY_NON_EMITTED;
	}

	private static boolean supportedLocalCfgForwardNode(Node node) {
		// A unique reaching TWrite/TRead pair in the same function and recompile
		// context is an exact value-flow edge even when it lives in a named function
		// or loop body.  Keep this broader than supportedPathOccurrence(): it only
		// propagates an already-local vector and never authorizes a pathwise upload.
		return supportedLocalPathOccurrence(node)
			&& node.kind() != NodeKind.FUNCTION_CALL && node.kind() != NodeKind.FUNCTION_INPUT
			&& node.kind() != NodeKind.FUNCTION_OUTPUT
			&& node.kind() != NodeKind.FUNCTION_BODY_NON_EMITTED;
	}

	private static boolean supportedPathOccurrence(CompiledHopKey key) {
		return "main".equals(key.functionNamespace()) && "compiled".equals(key.recompileContext())
			&& !key.callSitePath().contains("/loop-body/");
	}

	private static boolean supportedLocalPathOccurrence(Node node) {
		// Exact compiled-input edges are owned by one concrete Hop DAG even inside a
		// function, loop, or whole-body recompile region. They are sufficient to
		// propagate coordinator-local placement. A concrete CLONE_RECOMPILE occurrence
		// remains excluded because its topology may change. Exact TWrite/TRead CFG
		// forwarding uses this node-aware locality condition, while REFED frontier
		// inference remains deliberately restricted by supportedPathOccurrence().
		return node.valueVersion().versionKind() != VersionKind.CLONE_RECOMPILE
			&& ("compiled".equals(node.key().recompileContext())
				|| "recompile".equals(node.key().recompileContext()));
	}

	private static boolean isVector(NodeShapeFact shape) {
		return shape != null && shape.knownPositiveMatrix() && (shape.rows() == 1 || shape.cols() == 1);
	}

	private static boolean isAggregateBinaryVectorInput(Hop hop, NodeShapeFact shape, FType inputType) {
		if(!(hop instanceof AggBinaryOp) || !shape.knownPositiveMatrix())
			return false;
		return inputType == FType.ROW && shape.cols() == 1
			|| inputType == FType.COL && shape.rows() == 1
			// A one-worker FULL map owns the complete matrix, so the vector result is
			// orientation-independent but still follows the same forced-LOUT policy.
			|| inputType == FType.FULL && isVector(shape);
	}

	private static FType exactAggregateBinaryVectorLocalType(Hop hop, NodeShapeFact shape,
		List<FType> inputTypes) {
		List<FType> matches = inputTypes.stream()
			.filter(inputType -> isAggregateBinaryVectorInput(hop, shape, inputType))
			.distinct().toList();
		return matches.size() == 1 ? matches.get(0) : null;
	}

	static String analysisFingerprint(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences) {
		String graphSignature = graph.normalizedSignature();
		List<String> projectionSignatures = occurrences.stream()
			.map(occurrence -> stableFingerprintSignature(occurrence.normalizedSignature())).sorted().toList();
		return PlacementGraphFingerprint.sha256(stableFingerprintSignature(graphSignature) + '\n'
			+ String.join("\n", projectionSignatures));
	}

	private static String stableFingerprintSignature(String signature) {
		return signature.replaceAll("[0-9a-f]{64}", "<program>");
	}

	private static CfgAnalysis analyzeCfg(DMLProgram program, List<StatementBlock> topLevelStatementBlocks,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences) {
		Map<Integer,CfgFunctionOutputDefinition> functionOutputDefinitionsByToken = new java.util.TreeMap<>();
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall = new java.util.TreeMap<>();
		int nextFunctionOutputToken = CFG_FUNCTION_INPUT_DEFINITION - 1;
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			if(!(occurrences.get(ordinal).hop() instanceof FunctionOp call))
				continue;
			int outputCount = boundaryCount(call.getOutputVariableNames(),
				call.getOutputs() == null ? 0 : call.getOutputs().size());
			List<CfgFunctionOutputDefinition> definitions = new ArrayList<>(outputCount);
			for(int outputPosition = 0; outputPosition < outputCount; outputPosition++) {
				String variable = functionOutputVariableName(call, outputPosition);
				if(variable == null)
					continue;
				CfgFunctionOutputDefinition definition = new CfgFunctionOutputDefinition(
					ordinal, outputPosition, variable, nextFunctionOutputToken--);
				definitions.add(definition);
				functionOutputDefinitionsByToken.put(definition.token(), definition);
			}
			functionOutputDefinitionsByCall.put(ordinal, List.copyOf(definitions));
		}
		Map<StatementBlock,Set<StatementBlock>> predecessors = new IdentityHashMap<>();
		Set<StatementBlock> loopHeaders = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<StatementBlock> loopLatches = Collections.newSetFromMap(new IdentityHashMap<>());
		connectSequence(topLevelStatementBlocks, Set.of(), predecessors, loopHeaders, loopLatches);
		Map<StatementBlock,Map<String,Set<Integer>>> functionInputSeeds = new IdentityHashMap<>();
		Map<String,Set<StatementBlock>> functionExits = new java.util.TreeMap<>();
		for(Map.Entry<String,FunctionStatementBlock> entry :
			program.getNamedNSFunctionStatementBlocks().entrySet()) {
			FunctionStatementBlock function = entry.getValue();
			Set<StatementBlock> exits = connectSequence(List.of(function), Set.of(), predecessors,
				loopHeaders, loopLatches);
			functionExits.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(exits)));
			FunctionStatement statement = (FunctionStatement) function.getStatement(0);
			Map<String,Set<Integer>> seeds = new java.util.TreeMap<>();
			for(var input : statement.getInputParams())
				seeds.put(entry.getKey() + '\u0000' + input.getName(),
					Set.of(CFG_FUNCTION_INPUT_DEFINITION));
			functionInputSeeds.put(function, Collections.unmodifiableMap(seeds));
		}
		Map<StatementBlock,List<Integer>> byBlock = new IdentityHashMap<>();
		for(int i = 0; i < occurrences.size(); i++)
			byBlock.computeIfAbsent(occurrences.get(i).block(), k -> new ArrayList<>()).add(i);
		Map<String,Integer> counters = new java.util.TreeMap<>();
		List<Integer> ordinals = new ArrayList<>(occurrences.size());
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), ordinals.size());
			ordinals.add(isDefinition(occurrence.hop()) ? counters.merge(variable, 1, Integer::sum)
				: counters.getOrDefault(variable, 0));
		}
		Map<StatementBlock,Map<String,Set<Integer>>> out = new IdentityHashMap<>();
		boolean changed;
		do {
			changed = false;
			for(StatementBlock block : predecessors.keySet()) {
				Map<String,Set<Integer>> state = new java.util.TreeMap<>();
				mergeDefinitions(state, functionInputSeeds.get(block));
				for(StatementBlock predecessor : predecessors.get(block))
					mergeDefinitions(state, out.get(predecessor));
				transfer(state, byBlock.getOrDefault(block, List.of()), occurrences,
					functionOutputDefinitionsByCall);
				if(!state.equals(out.get(block))) {
					out.put(block, state);
					changed = true;
				}
			}
		} while(changed);
		List<Set<Integer>> reaching = new ArrayList<>(occurrences.size());
		List<Set<CfgFunctionOutputDefinition>> reachingFunctionOutputs = new ArrayList<>(occurrences.size());
		List<Boolean> reachingFunctionInputs = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) {
			reaching.add(Set.of());
			reachingFunctionOutputs.add(Set.of());
			reachingFunctionInputs.add(false);
		}
		for(Map.Entry<StatementBlock,List<Integer>> entry : byBlock.entrySet()) {
			Map<String,Set<Integer>> state = new java.util.TreeMap<>();
			mergeDefinitions(state, functionInputSeeds.get(entry.getKey()));
			for(StatementBlock predecessor : predecessors.getOrDefault(entry.getKey(), Set.of()))
				mergeDefinitions(state, out.get(predecessor));
			for(int index : entry.getValue()) {
				PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(index);
				String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), index);
				if(isTransientRead(occurrence.hop())) {
					Set<Integer> raw = state.getOrDefault(variable, Set.of());
					reachingFunctionInputs.set(index, raw.contains(CFG_FUNCTION_INPUT_DEFINITION));
					Set<Integer> definitions = raw.stream().filter(token -> token >= 0)
						.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
					Set<CfgFunctionOutputDefinition> functionOutputs = raw.stream()
						.map(functionOutputDefinitionsByToken::get).filter(Objects::nonNull)
						.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
					reaching.set(index, Collections.unmodifiableSet(definitions));
					reachingFunctionOutputs.set(index, Collections.unmodifiableSet(functionOutputs));
				}
				transferDefinition(state, index, occurrence, functionOutputDefinitionsByCall);
			}
		}
		List<VersionKind> kinds = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(i);
			VersionKind kind = VersionKind.ORDINARY;
			int sourceCount = reaching.get(i).size() + reachingFunctionOutputs.get(i).size()
				+ (reachingFunctionInputs.get(i) ? 1 : 0);
			if(isFormalFunctionInputRead(program, occurrence, reachingFunctionInputs.get(i), reaching.get(i)))
				kind = VersionKind.FUNCTION_INPUT;
			else if(isTransientRead(occurrence.hop()) && sourceCount > 1)
				kind = loopHeaders.contains(occurrence.block()) ? VersionKind.LOOP_HEAD_PHI
					: branchDefinitionsDiffer(occurrence, predecessors, out)
						? VersionKind.BRANCH_JOIN_PHI : VersionKind.ORDINARY;
			else if(isDefinition(occurrence.hop()) && loopLatches.contains(occurrence.block()))
				kind = VersionKind.LOOP_BACKEDGE;
			kinds.add(kind);
		}
		Map<String,Map<String,FunctionExitValue>> functionExitValues = new java.util.TreeMap<>();
		for(Map.Entry<String,FunctionStatementBlock> entry :
			program.getNamedNSFunctionStatementBlocks().entrySet()) {
			FunctionStatement statement = (FunctionStatement) entry.getValue().getStatement(0);
			Map<String,FunctionExitValue> outputs = new java.util.TreeMap<>();
			for(var output : statement.getOutputParams()) {
				String variable = entry.getKey() + '\u0000' + output.getName();
				Set<Integer> raw = new java.util.TreeSet<>();
				for(StatementBlock exit : functionExits.getOrDefault(entry.getKey(), Set.of()))
					raw.addAll(out.getOrDefault(exit, Map.of()).getOrDefault(variable, Set.of()));
				boolean reachesFunctionInput = raw.remove(CFG_FUNCTION_INPUT_DEFINITION);
				Set<CfgFunctionOutputDefinition> functionOutputs = raw.stream()
					.map(functionOutputDefinitionsByToken::get).filter(Objects::nonNull)
					.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
				raw.removeIf(token -> token < 0);
				outputs.put(output.getName(), new FunctionExitValue(
					Collections.unmodifiableSet(raw), Collections.unmodifiableSet(functionOutputs),
					reachesFunctionInput));
			}
			functionExitValues.put(entry.getKey(), Collections.unmodifiableMap(outputs));
		}
		return new CfgAnalysis(Collections.unmodifiableList(ordinals), Collections.unmodifiableList(kinds),
			Collections.unmodifiableList(reaching), Collections.unmodifiableList(reachingFunctionOutputs),
			Collections.unmodifiableList(reachingFunctionInputs),
			Collections.unmodifiableMap(functionExitValues));
	}

	private static boolean isFormalFunctionInputRead(DMLProgram program,
		PlacementGraphFingerprint.HopOccurrence occurrence, boolean reachesFunctionInput,
		Set<Integer> reachingDefinitions) {
		if(!isTransientRead(occurrence.hop()) || !reachesFunctionInput || !reachingDefinitions.isEmpty()
			|| occurrence.namespace() == null || "main".equals(occurrence.namespace()))
			return false;
		FunctionStatementBlock function = program.getNamedNSFunctionStatementBlocks().get(occurrence.namespace());
		if(function == null || function.getNumStatements() != 1
			|| !(function.getStatement(0) instanceof FunctionStatement statement))
			return false;
		String variable = lexicalVariable(occurrence.hop(), -1);
		return statement.getInputParams().stream().anyMatch(input -> variable.equals(input.getName()));
	}

	private static Set<StatementBlock> connectSequence(List<StatementBlock> blocks, Set<StatementBlock> incoming,
		Map<StatementBlock,Set<StatementBlock>> predecessors, Set<StatementBlock> loopHeaders,
		Set<StatementBlock> loopLatches) {
		Set<StatementBlock> exits = new LinkedHashSet<>(incoming);
		for(StatementBlock block : blocks == null ? List.<StatementBlock>of() : blocks) {
			predecessors.computeIfAbsent(block, k -> Collections.newSetFromMap(new IdentityHashMap<>())).addAll(exits);
			if(block instanceof IfStatementBlock) {
				IfStatement statement = (IfStatement) block.getStatement(0);
				Set<StatementBlock> thenExits = connectSequence(statement.getIfBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches);
				Set<StatementBlock> elseExits = connectSequence(statement.getElseBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches);
				exits = new LinkedHashSet<>();
				exits.addAll(thenExits.isEmpty() ? Set.of(block) : thenExits);
				exits.addAll(elseExits.isEmpty() ? Set.of(block) : elseExits);
			}
			else if(block instanceof WhileStatementBlock) {
				loopHeaders.add(block);
				WhileStatement statement = (WhileStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof ForStatementBlock) {
				loopHeaders.add(block);
				ForStatement statement = (ForStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof FunctionStatementBlock) {
				FunctionStatement statement = (FunctionStatement) block.getStatement(0);
				exits = connectSequence(statement.getBody(), Set.of(block), predecessors, loopHeaders, loopLatches);
			}
			else exits = new LinkedHashSet<>(Set.of(block));
		}
		return exits;
	}

	private static void transfer(Map<String,Set<Integer>> state, List<Integer> indices,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall) {
		for(int index : indices) {
			transferDefinition(state, index, occurrences.get(index), functionOutputDefinitionsByCall);
		}
	}

	private static void transferDefinition(Map<String,Set<Integer>> state, int index,
		PlacementGraphFingerprint.HopOccurrence occurrence,
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall) {
		if(isDefinition(occurrence.hop()))
			state.put(occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), index), Set.of(index));
		for(CfgFunctionOutputDefinition definition :
			functionOutputDefinitionsByCall.getOrDefault(index, List.of()))
			state.put(occurrence.namespace() + '\u0000' + definition.variable(), Set.of(definition.token()));
	}

	private static void mergeDefinitions(Map<String,Set<Integer>> target, Map<String,Set<Integer>> source) {
		if(source == null) return;
		for(Map.Entry<String,Set<Integer>> entry : source.entrySet()) {
			Set<Integer> merged = new java.util.TreeSet<>(target.getOrDefault(entry.getKey(), Set.of()));
			merged.addAll(entry.getValue());
			target.put(entry.getKey(), Collections.unmodifiableSet(merged));
		}
	}

	private static boolean branchDefinitionsDiffer(PlacementGraphFingerprint.HopOccurrence occurrence,
		Map<StatementBlock,Set<StatementBlock>> predecessors,
		Map<StatementBlock,Map<String,Set<Integer>>> out) {
		Set<StatementBlock> incoming = predecessors.getOrDefault(occurrence.block(), Set.of());
		if(incoming.size() < 2) return false;
		String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), -1);
		Set<Set<Integer>> branchOut = new HashSet<>();
		for(StatementBlock predecessor : incoming)
			branchOut.add(out.getOrDefault(predecessor, Map.of()).getOrDefault(variable, Set.of()));
		return branchOut.size() > 1;
	}

	private static boolean isDefinition(Hop hop) { return isTransientWrite(hop) || isFunctionOutput(hop); }

	private record CfgFunctionOutputDefinition(int callOrdinal, int outputPosition,
		String variable, int token) implements Comparable<CfgFunctionOutputDefinition> {
		@Override public int compareTo(CfgFunctionOutputDefinition that) {
			int callOrder = Integer.compare(callOrdinal, that.callOrdinal);
			return callOrder != 0 ? callOrder : Integer.compare(outputPosition, that.outputPosition);
		}
	}

	private record FunctionOutputBoundaryKey(int callOrdinal, int outputPosition)
		implements Comparable<FunctionOutputBoundaryKey> {
		@Override public int compareTo(FunctionOutputBoundaryKey that) {
			int callOrder = Integer.compare(callOrdinal, that.callOrdinal);
			return callOrder != 0 ? callOrder : Integer.compare(outputPosition, that.outputPosition);
		}
	}

	private record FunctionExitValue(Set<Integer> definitionOrdinals,
		Set<CfgFunctionOutputDefinition> functionOutputDefinitions, boolean reachesFunctionInput) { }

	private record CfgAnalysis(List<Integer> definitionOrdinals, List<VersionKind> versionKinds,
		List<Set<Integer>> reachingDefinitions,
		List<Set<CfgFunctionOutputDefinition>> reachingFunctionOutputDefinitions,
		List<Boolean> reachingFunctionInputs,
		Map<String,Map<String,FunctionExitValue>> functionExitValues) { }

	private static String registrySentinel(DMLProgram program) {
		List<String> rows = new ArrayList<>();
		for(long sbId : PlacementGraphFingerprint.statementBlockIds(program)) {
			FederatedRefedRegistry.snapshot(sbId).forEach((hop, spec) -> spec.getAuthorities().forEach(authority ->
				rows.add("R|" + sbId + '|' + hop + '|' + authority.getAnchorHopId() + '|'
					+ authority.getAnchorKey() + '|' + authority.getMaterializationFType() + '|'
					+ authority.getConsumerInputs())));
			FederatedFoutMaterializeRegistry.snapshot(sbId).forEach((hop, spec) -> rows.add("F|" + sbId + '|' + hop
				+ '|' + spec.getAnchorHopId() + '|' + spec.getFTypeHint() + '|' + spec.getAnchorLabel() + '|' + spec.getAnchorKey()));
			FederatedLocalMaterializeRegistry.snapshotScopes(sbId).forEach((scope, entries) -> entries.forEach((hop, spec) ->
				rows.add("L|" + scope + '|' + hop + '|' + spec.getConsumerHopIds() + '|' + spec.getFTypeHint() + '|' + spec.getReason())));
		}
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}

	private static List<Node> closeCfgValueVersions(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, Map<Hop,ValueVersionKey> values, CfgAnalysis cfg) {
		List<Node> closed = new ArrayList<>(nodes.size());
		for(int i = 0; i < occurrences.size(); i++) {
			Node node = nodes.get(i);
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(i);
			ValueVersionKey value = node.valueVersion();
			Set<String> predecessors = new java.util.TreeSet<>(value.predecessorVersions());
			if(cfg.reachingFunctionInputs().get(i))
				predecessors.add(cfgFunctionInputReference(occurrence.namespace(),
					value.lexicalVariable()));
			for(int definition : cfg.reachingDefinitions().get(i))
				predecessors.add("cfg-definition:" + valueReference(nodes.get(definition).valueVersion()));
			for(CfgFunctionOutputDefinition definition : cfg.reachingFunctionOutputDefinitions().get(i))
				predecessors.add("cfg-function-output:" + definition.callOrdinal() + ':'
					+ definition.outputPosition() + ':' + definition.variable());
			ValueVersionKey closedValue = new ValueVersionKey(value.programFingerprint(), value.lexicalVariable(),
				value.definingControlRegion(), value.definitionOrdinal(), value.versionKind(),
				new ArrayList<>(predecessors));
			Node closedNode = new Node(node.key(), node.kind(), closedValue, node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), node.anchors());
			closed.add(closedNode);
			values.put(occurrence.hop(), closedValue);
		}
		return closed;
	}

	private AnchorClosure closeCfgDurableAnchors(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, List<DurableAnchorKey> occurrenceAnchors, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop) {
		List<Node> closedNodes = new ArrayList<>(nodes.size());
		List<DurableAnchorKey> closedAnchors = new ArrayList<>(nodes.size());
		for(int i = 0; i < nodes.size(); i++) {
			DurableAnchorKey anchor = occurrenceAnchors.get(i);
			if(isTransientRead(occurrences.get(i).hop()) && (cfg.reachingFunctionInputs().get(i)
				|| !cfg.reachingDefinitions().get(i).isEmpty()))
				anchor = cfgTransientReadAnchor(occurrences.get(i).hop(), factsByHop.get(occurrences.get(i).hop()),
					cfg.reachingDefinitions().get(i), cfg.reachingFunctionInputs().get(i), anchor,
					occurrenceAnchors);
			Node node = nodes.get(i);
			closedNodes.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), anchor == null ? List.of() : List.of(anchor)));
			closedAnchors.add(anchor);
		}
		return new AnchorClosure(List.copyOf(closedNodes),
			Collections.unmodifiableList(new ArrayList<>(closedAnchors)));
	}

	private record AnchorClosure(List<Node> nodes, List<DurableAnchorKey> anchors) { }


	// Durable-anchor propagation preserves an existing FederationMap identity only when the matrix inputs,
	// output geometry, and Oracle profile all prove the same FType domain; it is not a runtime-capability closure.
	private DurableAnchorKey cfgTransientReadAnchor(Hop hop, NodeShapeFact outputShape,
		Set<Integer> reachingDefinitions, boolean reachesFunctionInput,
		DurableAnchorKey functionInputAnchor, List<DurableAnchorKey> occurrenceAnchors) {
		if(!isTransientRead(hop) || !hop.getInput().isEmpty()
			|| !reachesFunctionInput && reachingDefinitions.isEmpty())
			return null;
		DurableAnchorKey anchor = reachesFunctionInput ? functionInputAnchor : null;
		if(reachesFunctionInput && anchor == null)
			return null;
		for(int definition : reachingDefinitions) {
			DurableAnchorKey definitionAnchor = occurrenceAnchors.get(definition);
			if(definitionAnchor == null)
				return null;
			if(anchor == null)
				anchor = definitionAnchor;
			else if(!anchor.equals(definitionAnchor))
				return null;
		}
		return anchor != null && outputShape != null && outputShape.dataType().isMatrix()
			&& outputGeometryCompatible(outputShape, anchor) && oracleConfirmsAnchorDomain(hop,
				Collections.singletonList(Collections.singletonList(anchor.fType())), anchor) ? anchor : null;
	}

	private DurableAnchorKey inheritableDurableAnchor(Hop hop, NodeShapeFact outputShape,
		List<NodeShapeFact> inputShapeFacts, List<DurableAnchorKey> inputAnchors) {
		if(outputShape == null || !outputShape.dataType().isMatrix())
			return null;
		Set<DurableAnchorKey> candidates = new java.util.TreeSet<>();
		for(int i = 0; i < inputShapeFacts.size(); i++)
			if(inputShapeFacts.get(i).dataType().isMatrix() && inputAnchors.get(i) != null)
				candidates.add(inputAnchors.get(i));
		if(candidates.size() != 1)
			return null;
		DurableAnchorKey anchor = candidates.iterator().next();
		List<List<FType>> domains = new ArrayList<>(inputShapeFacts.size());
		for(int i = 0; i < inputShapeFacts.size(); i++) {
			NodeShapeFact inputShape = inputShapeFacts.get(i);
			DurableAnchorKey inputAnchor = inputAnchors.get(i);
			if(!inputShape.dataType().isMatrix())
				domains.add(Collections.singletonList(null));
			else if(anchor.equals(inputAnchor))
				domains.add(Collections.singletonList(anchor.fType()));
			else if(inputAnchor != null || !knownBroadcastableLocalMatrix(inputShape))
				return null;
			else
				domains.add(Collections.singletonList(null));
		}
		return outputGeometryCompatible(outputShape, anchor) && oracleConfirmsAnchorDomain(hop, domains, anchor)
			? anchor : null;
	}

	private boolean oracleConfirmsAnchorDomain(Hop hop, List<List<FType>> domains, DurableAnchorKey anchor) {
		FTypeProfile profile = oracle.inferProfile(hop, domains, null);
		return profile != null && profile.outputs() != null && profile.outputs().contains(anchor.fType());
	}

	private static boolean knownBroadcastableLocalMatrix(NodeShapeFact shape) {
		return shape.knownPositiveMatrix() && (shape.rows() == 1 || shape.cols() == 1);
	}

	private static boolean outputGeometryCompatible(NodeShapeFact outputShape, DurableAnchorKey anchor) {
		if(!outputShape.knownPositiveMatrix())
			return true;
		if(anchor.partitions().isEmpty() || deriveAnchorFType(anchor.partitions()) != anchor.fType())
			return false;
		long maxRow = -1, maxCol = -1;
		for(AnchorPartition partition : anchor.partitions()) {
			if(partition.begin().size() != 2 || partition.end().size() != 2)
				return false;
			long beginRow = partition.begin().get(0), beginCol = partition.begin().get(1);
			long endRow = partition.end().get(0), endCol = partition.end().get(1);
			if(beginRow < 0 || beginCol < 0 || endRow <= beginRow || endCol <= beginCol
				|| endRow > outputShape.rows() || endCol > outputShape.cols())
				return false;
			maxRow = Math.max(maxRow, endRow);
			maxCol = Math.max(maxCol, endCol);
		}
		// Fed-init anchors are constructed from exact literal half-open ranges; matching derived FType plus
		// bounded partitions and max extents proves the output geometry is the same logical matrix.
		return outputShape.rows() == maxRow && outputShape.cols() == maxCol;
	}

	private CandidateReplay closeCfgTransientCandidateDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts) {
		CandidateReplay current = new CandidateReplay(List.copyOf(nodes), List.copyOf(domainKeys),
			List.copyOf(facts), List.of(), List.of());
		int maxPasses = Math.max(1, occurrences.size());
		for(int pass = 0; pass < maxPasses; pass++) {
			CandidateReplay replayed = replayUniqueCfgTransientForwards(occurrences, current.nodes(), cfg,
				factsByHop, current.domainKeys(), current.facts(), current.logicalInputs());
			if(replayed.changedOrdinals().isEmpty())
				return replayed;
			current = closePostCfgPhysicalCandidateDependencies(occurrences, replayed,
				factsByHop, ordinalsByBlock, cfg);
		}
		throw new IllegalStateException("CFG transient candidate closure did not converge");
	}

	private CandidateReplay replayUniqueCfgTransientForwards(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> existingLogicalInputs) {
		if(domainKeys.size() != facts.size())
			throw new IllegalStateException("Candidate rule fact/domain count differs before CFG replay");
		Map<CompiledHopKey,List<Integer>> candidateSlots = new IdentityHashMap<>();
		for(int i = 0; i < domainKeys.size(); i++) {
			CandidateRuleKey key = domainKeys.get(i);
			CandidateRuleFact fact = facts.get(i);
			if(key.parentOccurrence() != fact.key().parentOccurrence()
				|| !key.orderedInputs().equals(fact.key().orderedInputs()))
				throw new IllegalStateException("Candidate rule fact/domain order differs before CFG replay");
			candidateSlots.computeIfAbsent(key.parentOccurrence(), ignored -> new ArrayList<>()).add(i);
		}
		List<Node> replayedNodes = new ArrayList<>(nodes.size());
		List<CandidateRuleKey> replayedKeys = new ArrayList<>();
		List<CandidateRuleFact> replayedFacts = new ArrayList<>();
		List<LogicalTransientInputFact> logicalInputs = new ArrayList<>(existingLogicalInputs);
		Set<CompiledHopKey> replayedReads = Collections.newSetFromMap(new IdentityHashMap<>());
		existingLogicalInputs.forEach(input -> replayedReads.add(input.targetRead()));
		Set<Integer> copiedSlots = new HashSet<>();
		Set<CompiledHopKey> replacedParents = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Integer> changedOrdinals = new ArrayList<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			Node node = nodes.get(ordinal);
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			Node replayed = replayedReads.contains(node.key()) ? node
				: replayUniqueCfgTransientForward(ordinal, occurrence, node, occurrences, nodes, cfg,
					factsByHop, replayedKeys, replayedFacts, logicalInputs);
			replayedNodes.add(replayed);
			if(replayed != node) {
				replacedParents.add(node.key());
				changedOrdinals.add(ordinal);
				continue;
			}
			for(int slot : candidateSlots.getOrDefault(node.key(), List.of())) {
				replayedKeys.add(domainKeys.get(slot));
				replayedFacts.add(facts.get(slot));
				copiedSlots.add(slot);
			}
		}
		for(int slot = 0; slot < domainKeys.size(); slot++) {
			CompiledHopKey parent = domainKeys.get(slot).parentOccurrence();
			if(!copiedSlots.contains(slot) && !replacedParents.contains(parent))
				throw new IllegalStateException("Candidate rule slot has no exact CFG replay owner");
		}
		return new CandidateReplay(List.copyOf(replayedNodes), List.copyOf(replayedKeys),
			List.copyOf(replayedFacts), logicalInputs.stream().sorted().toList(), List.copyOf(changedOrdinals));
	}

	private Node replayUniqueCfgTransientForward(int ordinal,
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Node read,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, List<CandidateRuleKey> replayedKeys,
		List<CandidateRuleFact> replayedFacts, List<LogicalTransientInputFact> logicalInputs) {
		if(read.kind() != NodeKind.TRANSIENT_READ || !isTransientRead(readOccurrence.hop()))
			return read;
		Set<Integer> definitions = cfg.reachingDefinitions().get(ordinal);
		if(cfg.reachingFunctionInputs().get(ordinal)) {
			traceTransientReplay(readOccurrence,
				"non-replayable-function-input-plus-definitions=" + definitions);
			return read;
		}
		Integer loopPassThroughSource = definitions.size() > 1
			? exactLoopPassThroughSource(ordinal, readOccurrence, read, definitions,
				occurrences, nodes, factsByHop)
			: null;
		if(definitions.isEmpty()) {
			traceTransientReplay(readOccurrence, "non-replayable-reaching-definitions=" + definitions
				+ "|details=" + describeTransientDefinitions(readOccurrence, definitions, occurrences, nodes));
			return read;
		}
		List<Integer> exactDefinitions = loopPassThroughSource == null
			? definitions.stream().sorted().toList() : List.of(loopPassThroughSource);
		ExactTransientReplay exact = exactTransientReplay(readOccurrence, read, exactDefinitions,
			occurrences, nodes, factsByHop);
		if(exact == null) {
			traceTransientReplay(readOccurrence, "non-replayable-common-source-domain=" + exactDefinitions
				+ "|details=" + describeTransientDefinitions(readOccurrence, definitions, occurrences, nodes));
			return read;
		}
		Node replayed = buildExactLogicalTransientRead(readOccurrence.hop(), read, exact.sources().get(0),
			exact.anchor(), exact.federatedState().fType(), exact.localState(), exact.federatedState(),
			replayedKeys, replayedFacts);
		for(Node source : exact.sources()) {
			DurableAnchorKey sourceAnchor = source.anchors().isEmpty() ? null : source.anchors().get(0);
			logicalInputs.add(new LogicalTransientInputFact(source.key(), read.key(), 0,
				source.valueVersion(), read.valueVersion(), sourceAnchor, exact.federatedState().fType(),
				exact.localState(), exact.federatedState(), CandidateInputState.absentLocal(),
				CandidateInputState.present(exact.federatedState().fType())));
		}
		return replayed;
	}

	private record ExactTransientReplay(List<Node> sources, DurableAnchorKey anchor,
		PlacementState localState, PlacementState federatedState) { }

	/**
	 * Intersects the exact placement domains of every reaching TWrite. A branch/post-loop TRead is
	 * one runtime variable binding, so a tuple is legal only when every possible definition can
	 * provide that same tuple. Identity-loop backedges are collapsed to their proven external seed
	 * by {@link #exactLoopPassThroughSource}; all other joins retain every source as a logical input.
	 */
	private static ExactTransientReplay exactTransientReplay(
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Node read, List<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<Hop,NodeShapeFact> factsByHop) {
		if(definitions.isEmpty())
			return null;
		List<Node> sources = new ArrayList<>(definitions.size());
		Set<PlacementState> commonLocal = null;
		Set<PlacementState> commonFederated = null;
		DurableAnchorKey commonAnchor = null;
		boolean sawAnchor = false;
		NodeShapeFact readShape = factsByHop.get(readOccurrence.hop());
		if(readShape == null)
			return null;
		for(int definition : definitions) {
			if(definition < 0 || definition >= occurrences.size())
				return null;
			PlacementGraphFingerprint.HopOccurrence sourceOccurrence = occurrences.get(definition);
			Node source = nodes.get(definition);
			if(source.kind() != NodeKind.TRANSIENT_WRITE || !isTransientWrite(sourceOccurrence.hop())
				|| !sameTransientForwardContext(source, read)
				|| source.legalAlternatives().stream().anyMatch(state -> !isLegalTransient(state))
				|| !sameLogicalValueShape(factsByHop.get(sourceOccurrence.hop()), readShape))
				return null;
			if(source.anchors().size() > 1)
				return null;
			if(source.anchors().size() == 1) {
				DurableAnchorKey anchor = source.anchors().get(0);
				if(sawAnchor && !commonAnchor.equals(anchor))
					return null;
				commonAnchor = anchor;
				sawAnchor = true;
			}
			else if(sawAnchor)
				return null;
			Set<PlacementState> local = source.legalAlternatives().stream().filter(state ->
				state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
					&& state.fType() == null && !state.shapeDependent())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			Set<PlacementState> federated = source.legalAlternatives().stream().filter(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() != null && state.fType() != FType.PART && state.fType() != FType.OTHER)
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			if(commonLocal == null) {
				commonLocal = local;
				commonFederated = federated;
			}
			else {
				commonLocal.retainAll(local);
				commonFederated.retainAll(federated);
			}
			sources.add(source);
		}
		if(sawAnchor && sources.stream().anyMatch(source -> source.anchors().isEmpty()))
			return null;
		if(!read.anchors().isEmpty()
			&& (read.anchors().size() != 1 || !read.anchors().get(0).equals(commonAnchor)))
			return null;
		if(commonAnchor != null) {
			FType anchoredFType = commonAnchor.fType();
			commonFederated.removeIf(state -> state.fType() != anchoredFType);
		}
		if(commonLocal.size() != 1 || commonFederated.size() != 1)
			return null;
		return new ExactTransientReplay(List.copyOf(sources), commonAnchor,
			commonLocal.iterator().next(), commonFederated.iterator().next());
	}

	/**
	 * Finds the one external placement seed of a compiler-generated loop carry. The remaining
	 * reaching definitions must be exact {@code TWrite(v) <- TRead(v)} identity backedges in the
	 * same statement block. This does not collapse a real loop update: any arithmetic, rename,
	 * shape change, cross-context value, or second external definition keeps the phi unreplayed.
	 */
	private static Integer exactLoopPassThroughSource(int readOrdinal,
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Node read, Set<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<Hop,NodeShapeFact> factsByHop) {
		List<Integer> seeds = new ArrayList<>();
		for(int definition : definitions) {
			if(definition < 0 || definition >= occurrences.size())
				return null;
			PlacementGraphFingerprint.HopOccurrence sourceOccurrence = occurrences.get(definition);
			Node source = nodes.get(definition);
			if(exactIdentityLoopBackedge(readOrdinal, readOccurrence, sourceOccurrence, source,
				factsByHop))
				continue;
			if(source.kind() != NodeKind.TRANSIENT_WRITE || !isTransientWrite(sourceOccurrence.hop())
				|| !sameTransientForwardContext(source, read)
				|| source.legalAlternatives().stream().anyMatch(state -> !isLegalTransient(state)))
				return null;
			List<PlacementState> local = source.legalAlternatives().stream().filter(state ->
				state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
					&& state.fType() == null && !state.shapeDependent()).toList();
			List<PlacementState> federated = source.legalAlternatives().stream().filter(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() != null && state.fType() != FType.PART && state.fType() != FType.OTHER)
				.toList();
			if(local.size() != 1 || federated.size() != 1
				|| !sameLogicalValueShape(factsByHop.get(sourceOccurrence.hop()),
					factsByHop.get(readOccurrence.hop())))
				return null;
			seeds.add(definition);
		}
		return seeds.size() == 1 ? seeds.get(0) : null;
	}

	private static boolean exactIdentityLoopBackedge(int readOrdinal,
		PlacementGraphFingerprint.HopOccurrence readOccurrence,
		PlacementGraphFingerprint.HopOccurrence sourceOccurrence, Node source,
		Map<Hop,NodeShapeFact> factsByHop) {
		Hop sourceHop = sourceOccurrence.hop();
		return sourceOccurrence.block() == readOccurrence.block()
			&& sourceOccurrence.path().equals(readOccurrence.path())
			&& isTransientWrite(sourceHop) && sourceHop.getInput().size() == 1
			&& sourceHop.getInput(0) == readOccurrence.hop()
			&& Objects.equals(sourceHop.getName(), readOccurrence.hop().getName())
			&& source.kind() == NodeKind.TRANSIENT_WRITE
			&& sourceOccurrence.hop() != readOccurrence.hop()
			&& sameLogicalValueShape(factsByHop.get(sourceHop), factsByHop.get(readOccurrence.hop()));
	}

	private static List<String> describeTransientDefinitions(
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Set<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes) {
		List<String> details = new ArrayList<>();
		for(int definition : definitions) {
			if(definition < 0 || definition >= occurrences.size()) {
				details.add(definition + ":invalid");
				continue;
			}
			PlacementGraphFingerprint.HopOccurrence source = occurrences.get(definition);
			details.add(definition + ":hop=" + source.hop().getHopID() + ':' + source.hop().getOpString()
				+ "|path=" + source.path() + "|states=" + nodes.get(definition).legalAlternatives()
				+ "|inputs=" + source.hop().getInput().stream().map(Hop::getHopID).toList()
				+ "|dependsOnRead=" + dependsOnExactHop(source.hop(), readOccurrence.hop(),
					Collections.newSetFromMap(new IdentityHashMap<>())));
		}
		return List.copyOf(details);
	}

	private static boolean dependsOnExactHop(Hop current, Hop expected, Set<Hop> visited) {
		if(current == expected)
			return true;
		if(current == null || !visited.add(current))
			return false;
		for(Hop input : current.getInput())
			if(dependsOnExactHop(input, expected, visited))
				return true;
		return false;
	}

	private static void traceTransientReplay(PlacementGraphFingerprint.HopOccurrence readOccurrence,
		String rejection) {
		FederatedPlannerTrace.log(readOccurrence.hop(), "Neutral-TransientReplay",
			"rejected=" + rejection + "|path=" + readOccurrence.path()
				+ "|namespace=" + readOccurrence.namespace() + "|topology=" + readOccurrence.topology());
	}

	private Node buildExactLogicalTransientRead(Hop readHop, Node read, Node source, DurableAnchorKey anchor,
		FType federatedFType, PlacementState localState, PlacementState federatedState,
		List<CandidateRuleKey> replayedKeys,
		List<CandidateRuleFact> replayedFacts) {
		List<CandidateInputState> localInput = List.of(CandidateInputState.absentLocal());
		List<CandidateInputState> federatedInput = List.of(CandidateInputState.present(federatedFType));
		CandidateRuleKey localKey = new CandidateRuleKey(read.key(), localInput);
		CandidateRuleKey federatedKey = new CandidateRuleKey(read.key(), federatedInput);
		replayedKeys.add(localKey);
		replayedFacts.add(logicalTransientReplayFact(readHop, localKey, localState, source, read,
			anchor, federatedFType));
		replayedKeys.add(federatedKey);
		replayedFacts.add(logicalTransientReplayFact(readHop, federatedKey, federatedState, source, read,
			anchor, federatedFType));
		return new Node(read.key(), read.kind(), read.valueVersion(), read.emittedWork(),
			List.of(localState, federatedState), read.exclusions(),
			anchor == null ? read.anchors() : List.of(anchor));
	}

	private CandidateRuleFact logicalTransientReplayFact(Hop readHop, CandidateRuleKey key, PlacementState state,
		Node source, Node read, DurableAnchorKey anchor, FType federatedFType) {
		String detail = "logical-transient-replay|source=" + source.key().normalizedSignature()
			+ "|read=" + read.key().normalizedSignature() + "|authority="
			+ (anchor == null ? "source-plan:" + federatedFType : anchor.normalizedSignature());
		CandidateCapabilityFact capability = new CandidateCapabilityFact(
			org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory.OTHER, readHop.getOpString(),
			state.execType(), state.output(), state.fType(),
			org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK, detail,
			List.of(new CandidateRuleNote(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.INFO,
				"builder-local logical transient replay from exact source state")));
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(
			Map.of("logicalTransientReplay", "builder-local", "federatedFType", String.valueOf(federatedFType),
				"sourceAuthority", anchor == null ? "plan-carried" : "durable-anchor",
				"source", source.key().normalizedSignature(), "read", read.key().normalizedSignature()),
			List.of("source-state", "read-anchor"), List.of());
		List<FType> outputs = state.output() == FederatedOutput.FOUT && state.fType() != null
			? List.of(state.fType()) : List.of();
		PlacementEmissionState emissionState = new PlacementEmissionState(state, false);
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE, capability, shapeProof,
			new CandidateProfileFact(outputs, ""), List.of(new CandidateEmissionFact(emissionState,
				state.execType() == ExecType.FED ? state.fType() : null)), "");
	}

	private static boolean sameLogicalValueShape(NodeShapeFact source, NodeShapeFact read) {
		if(source.dataType() != read.dataType())
			return false;
		return (source.rows() <= 0 || read.rows() <= 0 || source.rows() == read.rows())
			&& (source.cols() <= 0 || read.cols() <= 0 || source.cols() == read.cols());
	}

	private static boolean sameTransientForwardContext(Node source, Node read) {
		return source.key().programFingerprint().equals(read.key().programFingerprint())
			&& source.valueVersion().programFingerprint().equals(read.valueVersion().programFingerprint())
			&& source.valueVersion().lexicalVariable().equals(read.valueVersion().lexicalVariable())
			&& source.key().functionNamespace().equals(read.key().functionNamespace())
			&& source.key().recompileContext().equals(read.key().recompileContext());
	}

	private CandidateReplay closePostCfgPhysicalCandidateDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CandidateReplay replay,
		Map<Hop,NodeShapeFact> factsByHop, Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock,
		CfgAnalysis cfg) {
		if(replay.changedOrdinals().isEmpty())
			return replay;
		if(replay.domainKeys().size() != replay.facts().size())
			throw new IllegalStateException("Candidate rule fact/domain count differs before physical closure");

		List<Node> nodes = new ArrayList<>(replay.nodes());
		Map<CompiledHopKey,Integer> ordinalsByKey = new IdentityHashMap<>();
		List<List<CandidateRuleKey>> keysByOrdinal = new ArrayList<>(occurrences.size());
		List<List<CandidateRuleFact>> factsByOrdinal = new ArrayList<>(occurrences.size());
		List<Set<Integer>> consumersByProducer = new ArrayList<>(occurrences.size());
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			ordinalsByKey.put(nodes.get(ordinal).key(), ordinal);
			keysByOrdinal.add(new ArrayList<>());
			factsByOrdinal.add(new ArrayList<>());
			consumersByProducer.add(new java.util.TreeSet<>());
		}
		for(int slot = 0; slot < replay.domainKeys().size(); slot++) {
			CandidateRuleKey key = replay.domainKeys().get(slot);
			CandidateRuleFact fact = replay.facts().get(slot);
			Integer ordinal = ordinalsByKey.get(key.parentOccurrence());
			if(ordinal == null || fact.key().parentOccurrence() != key.parentOccurrence()
				|| !fact.key().orderedInputs().equals(key.orderedInputs()))
				throw new IllegalStateException("Candidate rule slot has no exact physical-closure owner");
			keysByOrdinal.get(ordinal).add(key);
			factsByOrdinal.get(ordinal).add(fact);
		}
		for(int consumerOrdinal = 0; consumerOrdinal < occurrences.size(); consumerOrdinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
			Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
			for(Hop input : occurrence.hop().getInput()) {
				if(!isPlacementDataShape(factsByHop, input))
					continue;
				Integer producerOrdinal = blockOrdinals == null ? null : blockOrdinals.get(input);
				if(producerOrdinal == null)
					throw new IllegalStateException("Placement-data producer lacks exact post-CFG closure owner");
				consumersByProducer.get(producerOrdinal).add(consumerOrdinal);
			}
		}

		java.util.TreeSet<Integer> worklist = new java.util.TreeSet<>(replay.changedOrdinals());
		Set<Integer> refinedOrdinals = new HashSet<>(replay.changedOrdinals());
		while(!worklist.isEmpty()) {
			int producerOrdinal = worklist.pollFirst();
			for(int consumerOrdinal : consumersByProducer.get(producerOrdinal)) {
				PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
				Hop hop = occurrence.hop();
				Node current = nodes.get(consumerOrdinal);
				Map<Hop,Node> exactBlockNodes = new IdentityHashMap<>();
				Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
				if(blockOrdinals != null)
					for(Map.Entry<Hop,Integer> entry : blockOrdinals.entrySet())
						exactBlockNodes.put(entry.getKey(), nodes.get(entry.getValue()));
				List<NodeShapeFact> inputShapes = new ArrayList<>(hop.getInput().size());
				for(Hop input : hop.getInput()) {
					NodeShapeFact inputShape = factsByHop.get(input);
					if(inputShape == null)
						throw new IllegalStateException("Physical closure input has no builder-owned shape fact");
					inputShapes.add(inputShape);
				}
				List<CandidateRuleKey> replacementKeys = new ArrayList<>();
				List<CandidateRuleFact> replacementFacts = new ArrayList<>();
				List<DurableAnchorKey> inputAnchors = hop.getInput().stream()
					.map(input -> {
						Node inputNode = exactBlockNodes.get(input);
						return inputNode != null && inputNode.anchors().size() == 1
							? inputNode.anchors().get(0) : null;
					}).toList();
				List<CompiledHopKey> inputAnchorOwners = hop.getInput().stream()
					.map(input -> {
						Node inputNode = exactBlockNodes.get(input);
						return inputNode == null ? null : inputNode.key();
					}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
				List<List<FType>> exactInputDomains = inputDomains(hop, exactBlockNodes, occurrence, occurrences,
					cfg.reachingFunctionInputs().get(consumerOrdinal), cfg);
				Node replacement = buildNode(hop, current.key(), current.valueVersion(), current.anchors(),
					inputAnchors, Collections.unmodifiableList(inputAnchorOwners),
					factsByHop.get(hop), List.copyOf(inputShapes),
					exactInputDomains, replacementKeys, replacementFacts);
				if(FederatedPlannerTrace.shouldTrace(hop))
					FederatedPlannerTrace.log(hop, "Neutral-PhysicalClosure",
						"producerOrdinal=" + producerOrdinal + "|inputDomains=" + exactInputDomains
							+ "|current=" + current.legalAlternatives()
							+ "|replacement=" + replacement.legalAlternatives()
							+ "|inputStates=" + hop.getInput().stream().map(input -> {
								Node inputNode = exactBlockNodes.get(input);
								return input.getHopID() + ":" + (inputNode == null ? "missing"
									: inputNode.legalAlternatives().toString());
							}).toList());
				List<CandidateRuleKey> priorKeys = keysByOrdinal.get(consumerOrdinal);
				List<CandidateRuleFact> priorFacts = factsByOrdinal.get(consumerOrdinal);
				boolean changed = !replacement.equals(current) || !replacementKeys.equals(priorKeys)
					|| !replacementFacts.equals(priorFacts);
				if(!changed)
					continue;
				if(!replacement.legalAlternatives().containsAll(current.legalAlternatives())
					|| !replacementKeys.containsAll(priorKeys)) {
					// A multi-input consumer can require more than one exact refinement in the
					// same closure pass: each predecessor is processed independently by the
					// worklist. Rejecting the second refinement left stale Cartesian-product
					// rows (for example FULL x BROADCAST) after another input had already been
					// narrowed. Every shrink must still be proven by an exact refined
					// predecessor; no candidate is removed merely because the consumer was
					// refined before.
					boolean exactRefinement = isExactAffectedDescendantRefinement(
						consumerOrdinal, current, replacement, priorKeys,
							priorFacts, replacementKeys, replacementFacts, occurrences, ordinalsByBlock, nodes,
							refinedOrdinals, factsByHop);
					if(!exactRefinement)
						throw new IllegalStateException("Post-CFG physical candidate closure is not monotone"
							+ "|consumerOrdinal=" + consumerOrdinal
							+ "|producerOrdinal=" + producerOrdinal
							+ "|hop=" + hop.getHopID() + ':' + hop.getOpString()
							+ "|refinedOrdinals=" + refinedOrdinals
							+ "|removedLegal=" + current.legalAlternatives().stream()
								.filter(state -> !replacement.legalAlternatives().contains(state)).toList()
							+ "|removedKeys=" + priorKeys.stream()
								.filter(key -> !replacementKeys.contains(key)).toList()
							+ "|priorFacts=" + priorFacts
							+ "|replacementFacts=" + replacementFacts);
				}
				nodes.set(consumerOrdinal, replacement);
				keysByOrdinal.set(consumerOrdinal, List.copyOf(replacementKeys));
				factsByOrdinal.set(consumerOrdinal, List.copyOf(replacementFacts));
				refinedOrdinals.add(consumerOrdinal);
				worklist.add(consumerOrdinal);
			}
		}
		List<CandidateRuleKey> closedKeys = new ArrayList<>();
		List<CandidateRuleFact> closedFacts = new ArrayList<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			closedKeys.addAll(keysByOrdinal.get(ordinal));
			closedFacts.addAll(factsByOrdinal.get(ordinal));
		}
		return new CandidateReplay(List.copyOf(nodes), List.copyOf(closedKeys), List.copyOf(closedFacts),
			replay.logicalInputs(), replay.changedOrdinals());
	}


	private static boolean isExactAffectedDescendantRefinement(int consumerOrdinal, Node current, Node replacement,
		List<CandidateRuleKey> priorKeys, List<CandidateRuleFact> priorFacts,
		List<CandidateRuleKey> replacementKeys, List<CandidateRuleFact> replacementFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, List<Node> currentNodes,
		Set<Integer> refinedOrdinals, Map<Hop,NodeShapeFact> factsByHop) {
		Map<CandidateRuleKey,CandidateRuleFact> priorByKey = factsByKey(priorKeys, priorFacts);
		Map<CandidateRuleKey,CandidateRuleFact> replacementByKey = factsByKey(replacementKeys, replacementFacts);
		List<CandidateRuleKey> removedKeys = priorKeys.stream()
			.filter(key -> !replacementByKey.containsKey(key)).toList();
		List<PlacementState> removedLegal = current.legalAlternatives().stream()
			.filter(state -> !replacement.legalAlternatives().contains(state)).toList();
		if(removedKeys.isEmpty() && removedLegal.isEmpty())
			return replacement.legalAlternatives().containsAll(current.legalAlternatives())
				&& replacementKeys.containsAll(priorKeys);
		PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
		Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
		if(blockOrdinals == null)
			return false;
		boolean refinedPhysicalInput = occurrence.hop().getInput().stream()
			.filter(input -> isPlacementDataShape(factsByHop, input))
			.map(blockOrdinals::get).filter(Objects::nonNull).anyMatch(refinedOrdinals::contains);
		List<CandidateEmissionFact> removedMaterializations = new ArrayList<>();
		for(CandidateRuleKey key : priorKeys) {
			CandidateRuleFact priorFact = priorByKey.get(key);
			CandidateRuleFact replacementFact = replacementByKey.get(key);
			if(replacementFact == null) {
				if(!hasRefinedPredecessorInvalidation(key, occurrence, blockOrdinals, currentNodes,
					refinedOrdinals, factsByHop))
					return false;
			}
			else if(!replacementFact.equals(priorFact)) {
				List<CandidateEmissionFact> removed = removedProvisionalMaterializations(priorFact, replacementFact);
				if(!refinedPhysicalInput || removed == null)
					return false;
				removedMaterializations.addAll(removed);
			}
		}
		for(PlacementState state : removedLegal)
			if(removedKeys.stream().map(priorByKey::get).noneMatch(fact -> exactFactPublishesState(fact, state))
				&& removedMaterializations.stream().noneMatch(emission ->
					emission.emissionState().placementState().equals(state)))
				return false;
		return true;
	}

	/**
	 * A prior worker-pool closure may have augmented an otherwise unchanged candidate row with
	 * CP/FOUT or derived FED/FOUT materialization actions. Physical dependency closure deliberately
	 * rebuilds the oracle-owned base row first and reruns worker-pool closure afterwards. Treat only
	 * that exact action-bearing delta as recomputable; native emissions and all other rule evidence
	 * must remain byte-for-byte equal.
	 */
	private static List<CandidateEmissionFact> removedProvisionalMaterializations(
		CandidateRuleFact prior, CandidateRuleFact replacement) {
		if(!prior.key().equals(replacement.key()) || prior.status() != replacement.status()
			|| !Objects.equals(prior.capability(), replacement.capability())
			|| !prior.shapeProof().equals(replacement.shapeProof())
			|| !prior.profile().equals(replacement.profile())
			|| !prior.failureCode().equals(replacement.failureCode()))
			return null;
		List<CandidateEmissionFact> priorNative = prior.allowedEmissionFacts().stream()
			.filter(emission -> emission.derivedFoutAction() == null).toList();
		List<CandidateEmissionFact> replacementNative = replacement.allowedEmissionFacts().stream()
			.filter(emission -> emission.derivedFoutAction() == null).toList();
		if(!priorNative.equals(replacementNative))
			return null;
		return prior.allowedEmissionFacts().stream()
			.filter(emission -> emission.derivedFoutAction() != null)
			.filter(emission -> !replacement.allowedEmissionFacts().contains(emission)).toList();
	}

	private static Map<CandidateRuleKey,CandidateRuleFact> factsByKey(List<CandidateRuleKey> keys,
		List<CandidateRuleFact> facts) {
		if(keys.size() != facts.size())
			throw new IllegalStateException("Candidate rule key/fact count differs during refinement proof");
		Map<CandidateRuleKey,CandidateRuleFact> indexed = new LinkedHashMap<>();
		for(int i = 0; i < keys.size(); i++) {
			CandidateRuleKey key = keys.get(i);
			CandidateRuleFact fact = facts.get(i);
			if(fact.key().parentOccurrence() != key.parentOccurrence()
				|| !fact.key().orderedInputs().equals(key.orderedInputs()))
				throw new IllegalStateException("Candidate rule key/fact order differs during refinement proof");
			if(indexed.putIfAbsent(key, fact) != null)
				throw new IllegalStateException("Duplicate candidate rule key during refinement proof");
		}
		return indexed;
	}

	private static boolean hasRefinedPredecessorInvalidation(CandidateRuleKey key,
		PlacementGraphFingerprint.HopOccurrence occurrence, Map<Hop,Integer> blockOrdinals, List<Node> currentNodes,
		Set<Integer> refinedOrdinals, Map<Hop,NodeShapeFact> factsByHop) {
		List<CandidateInputState> inputs = key.orderedInputs();
		for(int inputPosition = 0; inputPosition < inputs.size() && inputPosition < occurrence.hop().getInput().size();
			inputPosition++) {
			Hop input = occurrence.hop().getInput(inputPosition);
			if(!isPlacementDataShape(factsByHop, input))
				continue;
			Integer predecessorOrdinal = blockOrdinals.get(input);
			if(predecessorOrdinal == null || !refinedOrdinals.contains(predecessorOrdinal))
				continue;
			if(!candidateInputDomain(currentNodes.get(predecessorOrdinal)).contains(inputs.get(inputPosition)))
				return true;
		}
		return false;
	}

	private static Set<CandidateInputState> candidateInputDomain(Node predecessor) {
		Set<CandidateInputState> domain = new LinkedHashSet<>();
		for(PlacementState state : predecessor.legalAlternatives()) {
			if(state.output() != FederatedOutput.FOUT || state.fType() == null)
				domain.add(CandidateInputState.absentLocal());
			else
				domain.add(CandidateInputState.present(state.fType()));
		}
		return domain;
	}

	private static boolean exactFactPublishesState(CandidateRuleFact fact, PlacementState state) {
		if(fact == null || fact.status() != CandidateEvaluationStatus.AVAILABLE || fact.capability() == null)
			return false;
		return fact.capability().nativeExec() == state.execType()
			&& fact.capability().nativeOutput() == state.output()
			&& fact.capability().nativeFoutFType() == state.fType();
	}

	private record CandidateReplay(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logicalInputs,
		List<Integer> changedOrdinals) { }

	private record FunctionInputBinding(CompiledHopKey source, CompiledHopKey boundary,
		CompiledHopKey target) implements Comparable<FunctionInputBinding> {
		@Override public int compareTo(FunctionInputBinding that) {
			int targetOrder = target.compareTo(that.target);
			if(targetOrder != 0) return targetOrder;
			int sourceOrder = source.compareTo(that.source);
			return sourceOrder != 0 ? sourceOrder : boundary.compareTo(that.boundary);
		}
	}

	private record FunctionInputCandidateClosure(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<Integer> changedOrdinals) { }

	private record FunctionOutputCandidateClosure(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<Integer> changedOrdinals) { }

	/**
	 * Replays formal TRead candidates from the final exact caller-argument domains. Function bodies
	 * are fingerprinted before some call sites, so their first candidate pass cannot see caller states
	 * discovered by CFG and worker-pool closure. The synthetic argument boundary is the exact graph
	 * authority; this pass widens neither the oracle nor the runtime and keeps TRead states restricted
	 * to CP/LOUT and FED/FOUT.
	 */
	private FunctionInputCandidateClosure closeLogicalFunctionInputCandidates(List<Node> nodes,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts,
		List<Constraint> functionConstraints, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> factsByHop, int originalOccurrenceCount) {
		if(domainKeys.size() != facts.size())
			throw new IllegalStateException("Candidate rule fact/domain count differs before function replay");
		Map<CompiledHopKey,Integer> nodeIndexes = new IdentityHashMap<>();
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(int index = 0; index < nodes.size(); index++) {
			Node node = nodes.get(index);
			nodeIndexes.put(node.key(), index);
			nodesByKey.put(node.key(), node);
		}
		Map<CompiledHopKey,List<Constraint>> argumentsByBoundary = new IdentityHashMap<>();
		for(Constraint constraint : functionConstraints)
			if(constraint.kind() == ConstraintKind.CONJUNCTIVE
				&& (constraint.evidence().startsWith("function-argument:")
					|| constraint.evidence().startsWith("inlined-function-argument:")))
				argumentsByBoundary.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.add(constraint);
		List<FunctionInputBinding> bindings = new ArrayList<>();
		for(Constraint formal : functionConstraints) {
			if(formal.kind() != ConstraintKind.SAME_PLACEMENT
				|| !"function-formal-input".equals(formal.evidence()))
				continue;
			List<Constraint> arguments = argumentsByBoundary.getOrDefault(formal.left(), List.of());
			if(arguments.size() != 1)
				throw new IllegalStateException("Function input boundary has no unique exact caller argument");
			Constraint argument = arguments.get(0);
			if(!nodesByKey.containsKey(argument.left()) || !nodesByKey.containsKey(formal.left())
				|| !nodesByKey.containsKey(formal.right()))
				throw new IllegalStateException("Function input replay references a foreign graph node");
			bindings.add(new FunctionInputBinding(argument.left(), formal.left(), formal.right()));
		}
		bindings.sort(null);
		if(bindings.isEmpty())
			return new FunctionInputCandidateClosure(List.copyOf(nodes), List.copyOf(domainKeys),
				List.copyOf(facts), List.of());

		Map<CompiledHopKey,List<Node>> sourcesByTarget = new IdentityHashMap<>();
		for(FunctionInputBinding binding : bindings)
			sourcesByTarget.computeIfAbsent(binding.target(), ignored -> new ArrayList<>())
				.add(nodesByKey.get(binding.source()));
		Map<CompiledHopKey,List<Integer>> candidateSlots = new IdentityHashMap<>();
		for(int slot = 0; slot < domainKeys.size(); slot++) {
			CandidateRuleKey key = domainKeys.get(slot);
			CandidateRuleFact fact = facts.get(slot);
			if(key.parentOccurrence() != fact.key().parentOccurrence()
				|| !key.orderedInputs().equals(fact.key().orderedInputs()))
				throw new IllegalStateException("Candidate rule fact/domain order differs before function replay");
			candidateSlots.computeIfAbsent(key.parentOccurrence(), ignored -> new ArrayList<>()).add(slot);
		}

		List<Node> closedNodes = new ArrayList<>(nodes);
		Map<CompiledHopKey,List<CandidateRuleKey>> replacementKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateRuleFact>> replacementFacts = new IdentityHashMap<>();
		List<Integer> changedOrdinals = new ArrayList<>();
		for(int ordinal = 0; ordinal < originalOccurrenceCount; ordinal++) {
			Node current = closedNodes.get(ordinal);
			List<Node> sources = sourcesByTarget.get(current.key());
			if(sources == null)
				continue;
			Hop readHop = origins.get(current.key());
			NodeShapeFact readShape = readHop == null ? null : factsByHop.get(readHop);
			if(!isFunctionInputReplayTarget(current)
				|| readHop == null || readShape == null || !readHop.getInput().isEmpty())
				throw new IllegalStateException("Function input replay target is not an exact formal TRead");
			List<FType> exactDomain = logicalFunctionInputDomain(sources);
			List<CandidateRuleKey> exactKeys = new ArrayList<>();
			List<CandidateRuleFact> exactFacts = new ArrayList<>();
			Node replacement = buildNode(readHop, current.key(), current.valueVersion(), current.anchors(),
				List.of(), List.of(), readShape, List.of(), List.of(exactDomain), exactKeys, exactFacts);
			List<Integer> priorSlots = candidateSlots.getOrDefault(current.key(), List.of());
			if(priorSlots.isEmpty())
				throw new IllegalStateException("Function input replay target has no original candidate domain");
			List<CandidateRuleKey> priorKeys = priorSlots.stream().map(domainKeys::get).toList();
			List<CandidateRuleFact> priorFacts = priorSlots.stream().map(facts::get).toList();
			closedNodes.set(ordinal, replacement);
			nodesByKey.put(current.key(), replacement);
			replacementKeys.put(current.key(), List.copyOf(exactKeys));
			replacementFacts.put(current.key(), List.copyOf(exactFacts));
			if(!replacement.equals(current) || !exactKeys.equals(priorKeys) || !exactFacts.equals(priorFacts))
				changedOrdinals.add(ordinal);
		}

		for(FunctionInputBinding binding : bindings) {
			Node source = nodesByKey.get(binding.source());
			Node target = nodesByKey.get(binding.target());
			Integer boundaryIndex = nodeIndexes.get(binding.boundary());
			if(source == null || target == null || boundaryIndex == null)
				throw new IllegalStateException("Function input replay lost an exact boundary endpoint");
			Node boundary = closedNodes.get(boundaryIndex);
			List<PlacementState> alternatives = logicalFunctionBoundaryAlternatives(source, target);
			Node replacement = new Node(boundary.key(), boundary.kind(), boundary.valueVersion(),
				boundary.emittedWork(), alternatives, boundary.exclusions(), boundary.anchors());
			closedNodes.set(boundaryIndex, replacement);
			nodesByKey.put(binding.boundary(), replacement);
		}

		List<CandidateRuleKey> closedKeys = new ArrayList<>();
		List<CandidateRuleFact> closedFacts = new ArrayList<>();
		Set<CompiledHopKey> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : closedNodes) {
			List<CandidateRuleKey> exactKeys = replacementKeys.get(node.key());
			if(exactKeys != null) {
				closedKeys.addAll(exactKeys);
				closedFacts.addAll(replacementFacts.get(node.key()));
				replaced.add(node.key());
			}
			else
				for(int slot : candidateSlots.getOrDefault(node.key(), List.of())) {
					closedKeys.add(domainKeys.get(slot));
					closedFacts.add(facts.get(slot));
				}
		}
		int expectedSize = domainKeys.size() + replacementKeys.entrySet().stream()
			.mapToInt(entry -> entry.getValue().size()
				- candidateSlots.getOrDefault(entry.getKey(), List.of()).size()).sum();
		if(replaced.size() != replacementKeys.size() || closedKeys.size() != expectedSize
			|| closedFacts.size() != expectedSize)
			throw new IllegalStateException("Function input replay did not preserve exact candidate ownership");
		return new FunctionInputCandidateClosure(List.copyOf(closedNodes), List.copyOf(closedKeys),
			List.copyOf(closedFacts), List.copyOf(changedOrdinals));
	}

	private static List<FType> logicalFunctionInputDomain(List<Node> sources) {
		Set<FType> federated = new java.util.TreeSet<>(java.util.Comparator.comparing(Enum::name));
		boolean local = false;
		for(Node source : sources)
			for(PlacementState state : source.legalAlternatives()) {
				if(state.output() == FederatedOutput.FOUT && state.fType() != null)
					federated.add(state.fType());
				else if(state.output() == FederatedOutput.LOUT)
					local = true;
			}
		if(!local && federated.isEmpty())
			throw new IllegalStateException("Function input caller domain has no representable output placement");
		List<FType> domain = new ArrayList<>(federated);
		if(local)
			domain.add(0, null);
		return Collections.unmodifiableList(domain);
	}

	/**
	 * Replays a caller TRead from every exact CFG value that can reach it when at least one reaching
	 * definition is a function return. FunctionCallCP only aliases the returned Data object, so this
	 * pass intersects (rather than unions) ordinary writes, formal-input pass-throughs, and synthetic
	 * function-output boundaries. It changes no runtime capability and prevents construction order
	 * from silently deleting a legal FED/FOUT value placement.
	 */
	private FunctionOutputCandidateClosure closeCfgFunctionOutputCandidates(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts, CfgAnalysis cfg,
		FunctionExpansion expansion, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> factsByHop) {
		if(domainKeys.size() != facts.size())
			throw new IllegalStateException("Candidate rule fact/domain count differs before function-output replay");
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,List<CompiledHopKey>> formalBoundariesByTarget = new IdentityHashMap<>();
		for(Constraint constraint : expansion.constraints())
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& "function-formal-input".equals(constraint.evidence()))
				formalBoundariesByTarget.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.add(constraint.left());
		Map<CompiledHopKey,List<Integer>> candidateSlots = new IdentityHashMap<>();
		for(int slot = 0; slot < domainKeys.size(); slot++) {
			CandidateRuleKey key = domainKeys.get(slot);
			CandidateRuleFact fact = facts.get(slot);
			if(key.parentOccurrence() != fact.key().parentOccurrence()
				|| !key.orderedInputs().equals(fact.key().orderedInputs()))
				throw new IllegalStateException("Candidate rule fact/domain order differs before function-output replay");
			candidateSlots.computeIfAbsent(key.parentOccurrence(), ignored -> new ArrayList<>()).add(slot);
		}

		List<Node> closedNodes = new ArrayList<>(nodes);
		Map<CompiledHopKey,List<CandidateRuleKey>> replacementKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateRuleFact>> replacementFacts = new IdentityHashMap<>();
		List<Integer> changedOrdinals = new ArrayList<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			Set<CfgFunctionOutputDefinition> outputDefinitions =
				cfg.reachingFunctionOutputDefinitions().get(ordinal);
			if(outputDefinitions.isEmpty())
				continue;
			Node current = closedNodes.get(ordinal);
			Hop readHop = origins.get(current.key());
			NodeShapeFact readShape = readHop == null ? null : factsByHop.get(readHop);
			if((current.kind() != NodeKind.TRANSIENT_READ && current.kind() != NodeKind.BRANCH_JOIN
				&& current.kind() != NodeKind.LOOP_PHI)
				|| readHop == null || readShape == null || !isTransientRead(readHop)
				|| !readHop.getInput().isEmpty())
				throw new IllegalStateException("CFG function-output replay target is not an exact emitted TRead");

			Map<CompiledHopKey,Node> exactSources = new java.util.TreeMap<>();
			for(int definition : cfg.reachingDefinitions().get(ordinal)) {
				if(definition < 0 || definition >= occurrences.size())
					throw new IllegalStateException("CFG function-output replay has an invalid ordinary definition");
				Node source = closedNodes.get(definition);
				exactSources.put(source.key(), source);
			}
			for(CfgFunctionOutputDefinition definition : outputDefinitions) {
				CompiledHopKey sourceKey = expansion.outputBoundaryKeys().get(new FunctionOutputBoundaryKey(
					definition.callOrdinal(), definition.outputPosition()));
				Node source = sourceKey == null ? null : nodesByKey.get(sourceKey);
				if(source == null)
					throw new IllegalStateException("CFG function-output replay has no exact synthetic boundary: "
						+ definition);
				exactSources.put(source.key(), source);
			}
			if(cfg.reachingFunctionInputs().get(ordinal)) {
				List<CompiledHopKey> formalBoundaries = formalBoundariesByTarget.getOrDefault(
					current.key(), List.of());
				if(formalBoundaries.isEmpty())
					throw new IllegalStateException("CFG function-output replay cannot resolve its formal-input path");
				for(CompiledHopKey boundaryKey : formalBoundaries) {
					Node source = nodesByKey.get(boundaryKey);
					if(source == null)
						throw new IllegalStateException("CFG function-output replay references a foreign formal boundary");
					exactSources.put(source.key(), source);
				}
			}
			List<Node> sources = List.copyOf(exactSources.values());
			List<PlacementState> exactValues = exactValueBoundaryAlternatives(sources);
			List<FType> exactDomain = exactValueInputDomain(exactValues);
			List<DurableAnchorKey> exactAnchors = commonBoundaryAnchors(sources);
			List<CandidateRuleKey> oracleKeys = new ArrayList<>();
			List<CandidateRuleFact> oracleFacts = new ArrayList<>();
			Node oraclePrototype = buildNode(readHop, current.key(), current.valueVersion(), exactAnchors,
				List.of(), List.of(), readShape, List.of(), List.of(exactDomain), oracleKeys, oracleFacts);
			if(!oraclePrototype.legalAlternatives().containsAll(exactValues))
				throw new IllegalStateException("Function-output TRead oracle does not support the exact alias domain"
					+ "|target=" + current.key() + "|sources=" + exactSources.keySet()
					+ "|expected=" + exactValues + "|actual=" + oraclePrototype.legalAlternatives());
			Map<PlacementState,Exclusion> exactExclusions = new LinkedHashMap<>();
			for(Exclusion exclusion : oraclePrototype.exclusions())
				exactExclusions.put(exclusion.state(), exclusion);
			for(PlacementState alternative : oraclePrototype.legalAlternatives())
				if(!exactValues.contains(alternative))
					exactExclusions.put(alternative, new Exclusion(alternative,
						ReasonCode.CONSTRAINT_CONFLICT, "cfg-function-output-value-alias"));
			Node replacement = new Node(current.key(), current.kind(), current.valueVersion(), current.emittedWork(),
				exactValues, List.copyOf(exactExclusions.values()), exactAnchors);
			CandidateRuleKey aliasKey = new CandidateRuleKey(current.key(), List.of());
			CandidateRuleFact aliasFact = functionOutputAliasFact(readHop, aliasKey, replacement,
				exactSources.keySet());
			List<CandidateRuleKey> exactKeys = List.of(aliasKey);
			List<CandidateRuleFact> exactFacts = List.of(aliasFact);
			List<Integer> priorSlots = candidateSlots.getOrDefault(current.key(), List.of());
			if(priorSlots.isEmpty())
				throw new IllegalStateException("Function-output replay target has no original candidate domain");
			List<CandidateRuleKey> priorKeys = priorSlots.stream().map(domainKeys::get).toList();
			List<CandidateRuleFact> priorFacts = priorSlots.stream().map(facts::get).toList();
			closedNodes.set(ordinal, replacement);
			nodesByKey.put(current.key(), replacement);
			replacementKeys.put(current.key(), List.copyOf(exactKeys));
			replacementFacts.put(current.key(), List.copyOf(exactFacts));
			if(!replacement.equals(current) || !exactKeys.equals(priorKeys) || !exactFacts.equals(priorFacts))
				changedOrdinals.add(ordinal);
		}

		List<CandidateRuleKey> closedKeys = new ArrayList<>();
		List<CandidateRuleFact> closedFacts = new ArrayList<>();
		Set<CompiledHopKey> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : closedNodes) {
			List<CandidateRuleKey> exactKeys = replacementKeys.get(node.key());
			if(exactKeys != null) {
				closedKeys.addAll(exactKeys);
				closedFacts.addAll(replacementFacts.get(node.key()));
				replaced.add(node.key());
			}
			else
				for(int slot : candidateSlots.getOrDefault(node.key(), List.of())) {
					closedKeys.add(domainKeys.get(slot));
					closedFacts.add(facts.get(slot));
				}
		}
		int expectedSize = domainKeys.size() + replacementKeys.entrySet().stream()
			.mapToInt(entry -> entry.getValue().size()
				- candidateSlots.getOrDefault(entry.getKey(), List.of()).size()).sum();
		if(replaced.size() != replacementKeys.size() || closedKeys.size() != expectedSize
			|| closedFacts.size() != expectedSize)
			throw new IllegalStateException("Function-output replay did not preserve exact candidate ownership");
		return new FunctionOutputCandidateClosure(List.copyOf(closedNodes), List.copyOf(closedKeys),
			List.copyOf(closedFacts), List.copyOf(changedOrdinals));
	}

	private static CandidateRuleFact functionOutputAliasFact(Hop readHop, CandidateRuleKey key,
		Node alias, Set<CompiledHopKey> sources) {
		PlacementState representative = alias.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() != null)
			.findFirst().orElseGet(() -> alias.legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
					&& state.fType() == null)
				.findFirst().orElseThrow(() -> new IllegalStateException(
					"Function-output alias has no runtime transient placement")));
		CandidateCapabilityFact capability = new CandidateCapabilityFact(
			org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory.OTHER, readHop.getOpString(),
			representative.execType(), representative.output(), representative.fType(),
			org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK,
			"function-output-runtime-alias", List.of(new CandidateRuleNote(
				org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.INFO,
				"FunctionCallCP returns the exact Data object; no materialization input is introduced")));
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(
			Map.of("functionOutputAlias", "exact-data-object", "sourceCount", String.valueOf(sources.size()),
				"sources", sources.stream().map(CompiledHopKey::normalizedSignature).sorted()
					.collect(java.util.stream.Collectors.joining(","))),
			List.of("exact-function-output-authority", "runtime-data-alias"), List.of());
		List<FType> outputs = alias.legalAlternatives().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT && state.fType() != null)
			.map(PlacementState::fType).distinct().sorted(java.util.Comparator.comparing(Enum::name)).toList();
		List<CandidateEmissionFact> emissions = alias.legalAlternatives().stream()
			.map(state -> candidateEmissionFact(state, false,
				state.execType() == ExecType.FED ? state.fType() : null)).toList();
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE, capability, shapeProof,
			new CandidateProfileFact(outputs, ""), emissions, "");
	}

	private static List<FType> exactValueInputDomain(List<PlacementState> values) {
		Set<FType> federated = new java.util.TreeSet<>(java.util.Comparator.comparing(Enum::name));
		boolean local = false;
		for(PlacementState value : values) {
			if(value.output() == FederatedOutput.LOUT)
				local = true;
			else if(value.output() == FederatedOutput.FOUT && value.fType() != null)
				federated.add(value.fType());
		}
		if(!local && federated.isEmpty())
			throw new IllegalStateException("Exact value domain has no transient placement");
		List<FType> domain = new ArrayList<>(federated);
		if(local)
			domain.add(0, null);
		return Collections.unmodifiableList(domain);
	}

	private static List<PlacementState> logicalFunctionBoundaryAlternatives(Node source, Node target) {
		List<PlacementState> alternatives = new ArrayList<>();
		for(PlacementState targetState : target.legalAlternatives()) {
			if(!isLegalTransient(targetState))
				continue;
			boolean sourceCanSupply = source.legalAlternatives().stream().anyMatch(sourceState ->
				targetState.output() == FederatedOutput.LOUT
					? sourceState.output() == FederatedOutput.LOUT
						|| sourceState.output() == FederatedOutput.FOUT
					: sourceState.output() == FederatedOutput.FOUT
						&& sourceState.fType() != null
						&& sourceState.fType() == targetState.fType());
			if(sourceCanSupply)
				// The formal input owns the boundary-facing placement. In particular,
				// retain its shape-proof provenance instead of copying the caller opcode's
				// provenance into a SAME_PLACEMENT constraint that can never be satisfied.
				alternatives.add(targetState);
		}
		if(alternatives.isEmpty())
			throw new IllegalStateException("Function input boundary has no exact source state legal at its formal read");
		return List.copyOf(alternatives);
	}

	/** Retains the final exact source-state objects after post-CFG candidate replay replaces source nodes. */
	private static List<Node> refreshFunctionOutputBoundaryAlternatives(List<Node> nodes,
		List<Constraint> functionConstraints) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,List<CompiledHopKey>> sourcesByBoundary = new IdentityHashMap<>();
		for(Constraint constraint : functionConstraints)
			if(constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& (constraint.evidence().startsWith("function-result:")
					|| constraint.evidence().startsWith("inlined-function-result:")))
				sourcesByBoundary.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.add(constraint.left());
		List<Node> refreshed = new ArrayList<>(nodes.size());
		for(Node node : nodes) {
			if(node.kind() != NodeKind.FUNCTION_OUTPUT) {
				refreshed.add(node);
				continue;
			}
			List<CompiledHopKey> sourceKeys = sourcesByBoundary.getOrDefault(node.key(), List.of());
			if(sourceKeys.isEmpty() && !node.emittedWork() && node.legalAlternatives().isEmpty()
				&& node.exclusions().stream().allMatch(exclusion ->
					exclusion.reasonCode() == ReasonCode.UNKNOWN_METADATA)) {
				refreshed.add(node);
				continue;
			}
			if(sourceKeys.isEmpty())
				throw new IllegalStateException("Function output boundary has no exact returned-value source");
			List<Node> sources = new ArrayList<>(sourceKeys.size());
			for(CompiledHopKey sourceKey : sourceKeys) {
				Node source = nodesByKey.get(sourceKey);
				if(source == null)
					throw new IllegalStateException("Function output boundary references a foreign source");
				sources.add(source);
			}
			refreshed.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				exactValueBoundaryAlternatives(sources), node.exclusions(), commonBoundaryAnchors(sources)));
		}
		return List.copyOf(refreshed);
	}

	private static FunctionExpansion expandFunctionBoundaryContexts(
		DMLProgram program, CfgAnalysis cfg,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes) {
		List<Node> expanded = new ArrayList<>(nodes);
		List<Constraint> constraints = new ArrayList<>();
		Map<CompiledHopKey,Hop> expandedOrigins = new java.util.LinkedHashMap<>(origins);
		Map<CompiledHopKey,Long> expandedScopes = new java.util.LinkedHashMap<>(scopes);
		Map<StatementBlock,Map<Hop,Node>> nodesByBlock = new IdentityHashMap<>();
		Map<String,List<Node>> inlinedContextBoundariesByFunction = new LinkedHashMap<>();
		Set<Node> claimedInlinedPhysicalAuthorities = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int i = 0; i < occurrences.size(); i++) {
			nodesByBlock.computeIfAbsent(occurrences.get(i).block(), ignored -> new IdentityHashMap<>())
				.put(occurrences.get(i).hop(), nodes.get(i));
		}
		Map<Integer,CallBoundaryContext> callContexts = new java.util.TreeMap<>();
		for(int callIndex = 0; callIndex < occurrences.size(); callIndex++) {
			Hop hop = occurrences.get(callIndex).hop();
			if(!(hop instanceof FunctionOp)) continue;
			FunctionOp callOp = (FunctionOp) hop;
			Node call = nodes.get(callIndex);
			Long callScope = scopes.get(call.key());
			if(callScope == null)
				throw new IllegalStateException("Function call has no statement-block scope: " + call.key());
			String functionKey = callOp.getFunctionKey();
			String[] inputNames = callOp.getInputVariableNames();
			Map<String,Node> inputBoundariesByFormalName = new LinkedHashMap<>();
			for(int inputPosition = 0; inputPosition < boundaryCount(inputNames, callOp.getInput().size()); inputPosition++) {
				BoundaryName inputName = boundaryName(inputNames, inputPosition);
				Node argument = inputPosition < callOp.getInput().size()
					? nodesByBlock.get(occurrences.get(callIndex).block()).get(callOp.getInput(inputPosition)) : null;
				List<PlacementState> alternatives = argument == null ? List.of(
					new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false))
					: transientAlternatives(argument.legalAlternatives());
				Node input = functionBoundaryNode(call, functionKey, inputName, callIndex,
					inputPosition, VersionKind.FUNCTION_INPUT, NodeKind.FUNCTION_INPUT, alternatives,
					argument == null ? List.of() : argument.anchors());
				expanded.add(input);
				expandedOrigins.put(input.key(), callOp);
				expandedScopes.put(input.key(), callScope);
				if(inputName.isKnown() && inputBoundariesByFormalName.put(inputName.name(), input) != null)
					throw new IllegalStateException("Function call repeats one formal input name: "
						+ functionKey + ':' + inputName.name());
				constraints.add(new Constraint(ConstraintKind.DOMINATES, call.key(), input.key(), inputPosition,
					"function-callsite-control"));
				if(argument != null)
					constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, argument.key(), input.key(), inputPosition,
						"function-argument:" + inputName.canonicalSourceOriginToken()));
				if(inputName.isKnown())
					for(Node formalInput : nodes)
						if(isFunctionInputReplayTarget(formalInput)
							&& functionMatches(callOp, formalInput.key().functionNamespace())
							&& inputName.name().equals(formalInput.valueVersion().lexicalVariable()))
							constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT, input.key(),
								formalInput.key(), inputPosition, "function-formal-input"));
			}
			callContexts.put(callIndex, new CallBoundaryContext(callIndex, callOp, call, callScope,
				nodesByBlock.get(occurrences.get(callIndex).block()),
				Collections.unmodifiableMap(inputBoundariesByFormalName)));
		}
		Map<FunctionOutputBoundaryKey,Node> outputBoundariesByDefinition = new java.util.TreeMap<>();
		Set<Integer> unresolvedCalls = new java.util.TreeSet<>(callContexts.keySet());
		while(!unresolvedCalls.isEmpty()) {
			boolean progressed = false;
			for(int callIndex : new ArrayList<>(unresolvedCalls)) {
				CallBoundaryContext context = callContexts.get(callIndex);
				FunctionOp callOp = context.callOp();
				String[] outputNames = callOp.getOutputVariableNames();
				int outputArity = callOp.getOutputs() == null ? 0 : callOp.getOutputs().size();
				int outputCount = boundaryCount(outputNames, outputArity);
				List<List<Node>> outputAuthorities = new ArrayList<>(outputCount);
				boolean ready = true;
				for(int outputPosition = 0; outputPosition < outputCount; outputPosition++) {
					List<Node> authorities = exactFunctionOutputAuthorities(program, cfg, callOp,
						outputPosition, nodes, context.callBlockNodes(),
						context.inputBoundariesByFormalName(), context.callNode(),
						outputBoundariesByDefinition);
					if(authorities == null) {
						ready = false;
						break;
					}
					outputAuthorities.add(authorities);
				}
				if(!ready)
					continue;
				for(int outputPosition = 0; outputPosition < outputCount; outputPosition++) {
					BoundaryName outputName = boundaryName(outputNames, outputPosition);
					List<Node> authorities = outputAuthorities.get(outputPosition);
					Node output;
					if(authorities.isEmpty()) {
						Node unresolved = functionBoundaryNode(context.callNode(), callOp.getFunctionKey(), outputName,
							callIndex, outputPosition, VersionKind.FUNCTION_OUTPUT, NodeKind.FUNCTION_OUTPUT,
							transientAlternatives(context.callNode().legalAlternatives()), List.of());
						output = unresolvedFunctionOutputBoundary(unresolved,
							"missing-dml-function-body:" + callOp.getFunctionKey());
					}
					else
						output = functionBoundaryNode(context.callNode(), callOp.getFunctionKey(), outputName,
							callIndex, outputPosition, VersionKind.FUNCTION_OUTPUT, NodeKind.FUNCTION_OUTPUT,
							exactValueBoundaryAlternatives(authorities), commonBoundaryAnchors(authorities));
					expanded.add(output);
					expandedOrigins.put(output.key(), callOp);
					expandedScopes.put(output.key(), context.callScope());
					constraints.add(new Constraint(ConstraintKind.DOMINATES, context.callNode().key(),
						output.key(), outputPosition, "function-callsite-output-control"));
					if(isRuntimeCoupledMultiReturnPrimary(callOp, outputPosition))
						constraints.add(new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT,
							context.callNode().key(), output.key(), outputPosition,
							"multi-return-primary-result:" + outputName.canonicalSourceOriginToken()));
					for(Node authority : authorities)
						constraints.add(new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT,
							authority.key(), output.key(), outputPosition,
							"function-result:" + outputName.canonicalSourceOriginToken()));
					outputBoundariesByDefinition.put(new FunctionOutputBoundaryKey(callIndex,
						outputPosition), output);
				}
				unresolvedCalls.remove(callIndex);
				progressed = true;
			}
			if(!progressed) {
				// Recursive (including mutually recursive) aliases have no topological
				// expansion order. Seed every member of the unresolved SCC simultaneously
				// with the call node's runtime transient domain, then attach the exact CFG
				// value-authority equations. The ordinary fixed-point refresh below only
				// intersects these finite domains, so this neither truncates call depth nor
				// invents a placement absent from the runtime call carrier. Exact still
				// rejects recursive occurrence weighting with its typed applicability reason.
				Map<FunctionOutputBoundaryKey,Integer> provisionalOrdinals = new java.util.TreeMap<>();
				for(int callIndex : new ArrayList<>(unresolvedCalls)) {
					CallBoundaryContext context = callContexts.get(callIndex);
					FunctionOp callOp = context.callOp();
					String[] outputNames = callOp.getOutputVariableNames();
					int outputArity = callOp.getOutputs() == null ? 0 : callOp.getOutputs().size();
					for(int outputPosition = 0;
						outputPosition < boundaryCount(outputNames, outputArity); outputPosition++) {
						BoundaryName outputName = boundaryName(outputNames, outputPosition);
						Node output = functionBoundaryNode(context.callNode(), callOp.getFunctionKey(),
							outputName, callIndex, outputPosition, VersionKind.FUNCTION_OUTPUT,
							NodeKind.FUNCTION_OUTPUT,
							transientAlternatives(context.callNode().legalAlternatives()),
							context.callNode().anchors());
						FunctionOutputBoundaryKey boundaryKey = new FunctionOutputBoundaryKey(callIndex,
							outputPosition);
						provisionalOrdinals.put(boundaryKey, expanded.size());
						expanded.add(output);
						expandedOrigins.put(output.key(), callOp);
						expandedScopes.put(output.key(), context.callScope());
						constraints.add(new Constraint(ConstraintKind.DOMINATES,
							context.callNode().key(), output.key(), outputPosition,
							"function-callsite-output-control"));
						outputBoundariesByDefinition.put(boundaryKey, output);
					}
				}
				for(int callIndex : new ArrayList<>(unresolvedCalls)) {
					CallBoundaryContext context = callContexts.get(callIndex);
					FunctionOp callOp = context.callOp();
					String[] outputNames = callOp.getOutputVariableNames();
					int outputArity = callOp.getOutputs() == null ? 0 : callOp.getOutputs().size();
					for(int outputPosition = 0;
						outputPosition < boundaryCount(outputNames, outputArity); outputPosition++) {
						FunctionOutputBoundaryKey boundaryKey = new FunctionOutputBoundaryKey(callIndex,
							outputPosition);
						Node provisional = outputBoundariesByDefinition.get(boundaryKey);
						List<Node> authorities = exactFunctionOutputAuthorities(program, cfg, callOp,
							outputPosition, nodes, context.callBlockNodes(),
							context.inputBoundariesByFormalName(), context.callNode(),
							outputBoundariesByDefinition);
						if(authorities == null || authorities.isEmpty())
							throw new IllegalStateException(
								"Recursive DML function output has no finite CFG authority equation: "
									+ callOp.getFunctionKey() + " output=" + outputPosition);
						Node output = new Node(provisional.key(), provisional.kind(),
							provisional.valueVersion(), provisional.emittedWork(),
							exactValueBoundaryAlternatives(authorities), provisional.exclusions(),
							commonBoundaryAnchors(authorities));
						expanded.set(provisionalOrdinals.get(boundaryKey), output);
						expandedOrigins.remove(provisional.key());
						expandedScopes.remove(provisional.key());
						expandedOrigins.put(output.key(), callOp);
						expandedScopes.put(output.key(), context.callScope());
						outputBoundariesByDefinition.put(boundaryKey, output);
						BoundaryName outputName = boundaryName(outputNames, outputPosition);
						for(Node authority : authorities)
							constraints.add(new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT,
								authority.key(), output.key(), outputPosition,
								"function-result:" + outputName.canonicalSourceOriginToken()));
					}
				}
				unresolvedCalls.clear();
			}
		}
		Set<StatementBlock> expandedBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			StatementBlock block = occurrence.block();
			if(!expandedBlocks.add(block))
				continue;
			Map<Hop,Node> blockNodes = nodesByBlock.get(block);
			if(blockNodes.values().stream().allMatch(node -> node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED))
				continue;
			Map<String,InlinedFunctionInputBoundary> inputBindings = new LinkedHashMap<>();
			Map<String,InlinedFunctionOutputBoundary> outputBindings = new LinkedHashMap<>();
			for(InlinedFunctionCallBoundary boundary : block.getInlinedFunctionCallBoundaries())
				for(InlinedFunctionInputBoundary input : boundary.inputs()) {
					if(inputBindings.put(input.boundVariable(), input) != null)
						throw new IllegalStateException("Duplicate compiler-owned inlined input binding: "
							+ input.boundVariable());
				}
			for(InlinedFunctionCallBoundary boundary : block.getInlinedFunctionCallBoundaries())
				for(InlinedFunctionOutputBoundary output : boundary.outputs())
					if(outputBindings.put(output.targetVariable(), output) != null)
						throw new IllegalStateException("Duplicate compiler-owned inlined output binding: "
							+ output.targetVariable());
			for(InlinedFunctionCallBoundary inlinedCall : block.getInlinedFunctionCallBoundaries()) {
				List<Node> arguments = new ArrayList<>(inlinedCall.inputs().size());
				for(InlinedFunctionInputBoundary inlinedInput : inlinedCall.inputs()) {
					ResolvedInlinedInput exactInput = resolveInlinedInput(inlinedInput, inputBindings);
					arguments.add(exactInput.transientRead()
						? requireExactDataNode(blockNodes, OpOpData.TRANSIENTREAD,
							exactInput.variable(), inlinedCall, "input", inlinedInput.position())
						: requireExactNamedNode(blockNodes, exactInput.variable(), inlinedCall,
							"input", inlinedInput.position()));
				}
				List<Node> results = new ArrayList<>(inlinedCall.outputs().size());
				for(InlinedFunctionOutputBoundary inlinedOutput : inlinedCall.outputs())
					results.add(requireExactNamedNode(blockNodes, resolveInlinedOutput(inlinedOutput, outputBindings), inlinedCall,
						"output", inlinedOutput.position()));
				Node callAuthority = results.stream().findFirst()
					.orElseGet(() -> arguments.stream().filter(Objects::nonNull).findFirst().orElse(null));
				if(callAuthority == null)
					continue;
				Long callScope = scopes.get(callAuthority.key());
				int callIndex = inlinedCall.callStatementPosition();
				if(callScope == null)
					throw new IllegalStateException("Inlined function call has no exact occurrence authority");
				if(!claimedInlinedPhysicalAuthorities.add(callAuthority))
					throw new IllegalStateException("Inlined function calls share one emitted authority: "
						+ inlinedCall.functionKey() + " callStatement=" + callIndex);
				// An inlined DML call has no FunctionCallCPInstruction.  Its result/argument Hop is
				// still a real physical operation and must retain its original node kind so every
				// planner costs, selects, lowers, and audits that operation.  Call-site identity is
				// carried by the synthetic function boundary below, never by reclassifying the
				// physical Hop as a FUNCTION_CALL placeholder.
				Node contextBoundary = null;
				for(int inputOrdinal = 0; inputOrdinal < inlinedCall.inputs().size(); inputOrdinal++) {
					InlinedFunctionInputBoundary inlinedInput = inlinedCall.inputs().get(inputOrdinal);
					int inputPosition = inlinedInput.position();
					BoundaryName inputName = BoundaryName.known(inlinedInput.formalVariable());
					Node argument = arguments.get(inputOrdinal);
					List<PlacementState> alternatives = argument == null ? List.of(
						new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false))
						: transientAlternatives(argument.legalAlternatives());
					Node input = functionBoundaryNode(callAuthority, inlinedCall.functionKey(), inputName, callIndex,
						inputPosition,
						VersionKind.FUNCTION_INPUT, NodeKind.FUNCTION_INPUT, alternatives,
						argument == null ? List.of() : argument.anchors());
					expanded.add(input);
					if(contextBoundary == null)
						contextBoundary = input;
					expandedOrigins.put(input.key(), origins.get(callAuthority.key()));
					expandedScopes.put(input.key(), callScope);
					if(argument != null)
						constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, argument.key(), input.key(),
							inputPosition, "inlined-function-argument:"
								+ inputName.canonicalSourceOriginToken()));
				}
				for(int outputOrdinal = 0; outputOrdinal < inlinedCall.outputs().size(); outputOrdinal++) {
					InlinedFunctionOutputBoundary inlinedOutput = inlinedCall.outputs().get(outputOrdinal);
					int outputPosition = inlinedOutput.position();
					BoundaryName outputName = BoundaryName.known(inlinedOutput.formalVariable());
					Node result = results.get(outputOrdinal);
					Node output = functionBoundaryNode(callAuthority, inlinedCall.functionKey(), outputName, callIndex,
						outputPosition,
						VersionKind.FUNCTION_OUTPUT, NodeKind.FUNCTION_OUTPUT,
						transientAlternatives(result.legalAlternatives()), result.anchors());
					expanded.add(output);
					if(contextBoundary == null)
						contextBoundary = output;
					expandedOrigins.put(output.key(), origins.get(callAuthority.key()));
					expandedScopes.put(output.key(), callScope);
					constraints.add(new Constraint(ConstraintKind.DOMINATES, callAuthority.key(), output.key(),
						outputPosition, "function-callsite-output-control"));
					constraints.add(new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, result.key(), output.key(),
						outputPosition, "inlined-function-result:"
							+ outputName.canonicalSourceOriginToken()));
				}
				if(contextBoundary != null)
					inlinedContextBoundariesByFunction
						.computeIfAbsent(inlinedCall.functionKey(), ignored -> new ArrayList<>())
						.add(contextBoundary);
			}
		}
		for(Map.Entry<String,List<Node>> entry : inlinedContextBoundariesByFunction.entrySet()) {
			List<Node> authorities = entry.getValue();
			for(int left = 0; left < authorities.size(); left++)
				for(int right = left + 1; right < authorities.size(); right++)
					constraints.add(new Constraint(ConstraintKind.DISTINCT_CONTEXT,
						authorities.get(left).key(), authorities.get(right).key(), -1, entry.getKey()));
		}
		Map<FunctionOutputBoundaryKey,CompiledHopKey> outputBoundaryKeys = new java.util.TreeMap<>();
		outputBoundariesByDefinition.forEach((key, node) -> outputBoundaryKeys.put(key, node.key()));
		return new FunctionExpansion(Collections.unmodifiableList(expanded),
			Collections.unmodifiableList(constraints), Collections.unmodifiableMap(expandedOrigins),
			Collections.unmodifiableMap(expandedScopes), Collections.unmodifiableMap(outputBoundaryKeys));
	}

	private static boolean isRuntimeCoupledMultiReturnPrimary(FunctionOp call, int outputPosition) {
		return call != null && outputPosition == 0
			&& call.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& "transformencode".equalsIgnoreCase(call.getFunctionName());
	}

	private static List<Node> exactFunctionOutputAuthorities(DMLProgram program, CfgAnalysis cfg,
		FunctionOp call, int outputPosition, List<Node> occurrenceNodes, Map<Hop,Node> callBlockNodes,
		Map<String,Node> inputBoundariesByFormalName, Node callNode,
		Map<FunctionOutputBoundaryKey,Node> outputBoundariesByDefinition) {
		if(call.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN) {
			List<Hop> outputs = call.getOutputs();
			if(outputs == null || outputPosition >= outputs.size() || outputs.get(outputPosition) == null)
				throw new IllegalStateException("Multi-return builtin has no exact output carrier: "
					+ call.getFunctionKey() + " output=" + outputPosition);
			Node authority = callBlockNodes == null ? null : callBlockNodes.get(outputs.get(outputPosition));
			if(authority == null)
				throw new IllegalStateException("Multi-return builtin output carrier is outside its call block: "
					+ call.getFunctionKey() + " output=" + outputPosition);
			return List.of(authority);
		}
		if(call.getFunctionType() != FunctionOp.FunctionType.DML) {
			int outputCount = boundaryCount(call.getOutputVariableNames(),
				call.getOutputs() == null ? 0 : call.getOutputs().size());
			if(outputCount != 1)
				throw new IllegalStateException("External function with heterogeneous outputs has no exact value carriers: "
					+ call.getFunctionKey() + " outputs=" + outputCount);
			return List.of(callNode);
		}

		FunctionStatementBlock function = program.getFunctionStatementBlock(
			call.getFunctionNamespace(), call.getFunctionName());
		if(function == null)
			// Synthetic/unit fixtures and unresolved external program fragments can carry a
			// DML-typed FunctionOp without the corresponding body. Publish a typed but
			// non-emitted output boundary rather than reviving the unsound aggregate-call
			// authority. Any actual consumer then fails closed because no value placement exists.
			return List.of();
		if(function.getNumStatements() != 1
			|| !(function.getStatement(0) instanceof FunctionStatement statement)
			|| outputPosition >= statement.getOutputParams().size())
			throw new IllegalStateException("DML function output has no exact formal declaration: "
				+ call.getFunctionKey() + " output=" + outputPosition);
		String formalName = statement.getOutputParams().get(outputPosition).getName();
		String cfgFunctionKey = DMLProgram.DEFAULT_NAMESPACE.equals(call.getFunctionNamespace())
			? call.getFunctionName()
			: DMLProgram.constructFunctionKey(call.getFunctionNamespace(), call.getFunctionName());
		Map<String,FunctionExitValue> functionExits = cfg.functionExitValues().get(cfgFunctionKey);
		if(functionExits == null)
			throw new IllegalStateException("DML function has no exact qualified CFG exit authority: "
				+ call.getFunctionKey() + " expected=" + cfgFunctionKey);
		FunctionExitValue exits = functionExits.get(formalName);
		if(exits == null)
			throw new IllegalStateException("DML function CFG has no formal output value: "
				+ call.getFunctionKey() + ':' + formalName);
		Set<Node> exact = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int ordinal : exits.definitionOrdinals()) {
			if(ordinal < 0 || ordinal >= occurrenceNodes.size())
				throw new IllegalStateException("DML function output definition ordinal is outside analysis: "
					+ call.getFunctionKey() + ':' + formalName + " ordinal=" + ordinal);
			exact.add(occurrenceNodes.get(ordinal));
		}
		for(CfgFunctionOutputDefinition definition : exits.functionOutputDefinitions()) {
			Node nestedOutput = outputBoundariesByDefinition.get(new FunctionOutputBoundaryKey(
				definition.callOrdinal(), definition.outputPosition()));
			if(nestedOutput == null)
				return null;
			exact.add(nestedOutput);
		}
		if(exits.reachesFunctionInput()) {
			Node input = inputBoundariesByFormalName.get(formalName);
			if(input == null)
				throw new IllegalStateException("DML function returns an unbound formal input: "
					+ call.getFunctionKey() + ':' + formalName);
			exact.add(input);
		}
		if(exact.isEmpty())
			throw new IllegalStateException("DML function output has no reaching definition at any exit: "
				+ call.getFunctionKey() + ':' + formalName);
		List<Node> result = new ArrayList<>(exact);
		result.sort((left, right) -> left.key().compareTo(right.key()));
		return List.copyOf(result);
	}

	private static Node unresolvedFunctionOutputBoundary(Node boundary, String detail) {
		List<Exclusion> exclusions = boundary.legalAlternatives().stream()
			.map(state -> new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail)).toList();
		return new Node(boundary.key(), boundary.kind(), boundary.valueVersion(), false,
			List.of(), exclusions, List.of());
	}

	private static List<PlacementState> exactValueBoundaryAlternatives(List<Node> authorities) {
		if(authorities.isEmpty())
			throw new IllegalArgumentException("Function output requires at least one exact value authority");
		Set<PlacementState> common = null;
		for(Node authority : authorities) {
			Set<PlacementState> values = new java.util.TreeSet<>();
			for(PlacementState state : authority.legalAlternatives()) {
				if(state.output() == FederatedOutput.LOUT)
					values.add(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false));
				else if(state.output() == FederatedOutput.FOUT && state.fType() != null)
					values.add(new PlacementState(ExecType.FED, FederatedOutput.FOUT, state.fType(), false));
			}
			if(common == null)
				common = values;
			else
				common.retainAll(values);
		}
		if(common == null || common.isEmpty())
			throw new IllegalStateException("Function output exits have no common exact value placement");
		return List.copyOf(common);
	}

	private static List<DurableAnchorKey> commonBoundaryAnchors(List<Node> authorities) {
		if(authorities.isEmpty())
			return List.of();
		Set<DurableAnchorKey> common = new java.util.TreeSet<>(authorities.get(0).anchors());
		for(int index = 1; index < authorities.size(); index++)
			common.retainAll(authorities.get(index).anchors());
		return List.copyOf(common);
	}

	private static Node requireExactDataNode(Map<Hop,Node> blockNodes, OpOpData operation, String name,
		InlinedFunctionCallBoundary call, String boundary, int position) {
		if(name == null || name.isBlank())
			throw new IllegalStateException("Inlined function " + boundary + " has no compiler-owned variable identity");
		List<Node> matches = blockNodes.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof DataOp)
			.filter(entry -> ((DataOp) entry.getKey()).getOp() == operation)
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
		if(matches.size() != 1)
			throw new IllegalStateException("Inlined function boundary requires one exact compiler-owned occurrence: "
				+ call.functionKey() + " callStatement=" + call.callStatementPosition() + ' ' + boundary + '='
				+ position + " variable=" + name + " operation=" + operation + " matches=" + matches.size());
		return matches.get(0);
	}

	private static Node requireExactNamedNode(Map<Hop,Node> blockNodes, String name,
		InlinedFunctionCallBoundary call, String boundary, int position) {
		if(name == null || name.isBlank())
			throw new IllegalStateException("Inlined function " + boundary + " has no compiler-owned variable identity");
		List<Node> matches = blockNodes.entrySet().stream()
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
		if(matches.size() != 1)
			throw new IllegalStateException("Inlined function boundary requires one exact compiler-owned occurrence: "
				+ call.functionKey() + " callStatement=" + call.callStatementPosition() + ' ' + boundary + '='
				+ position + " variable=" + name + " matches=" + matches.size());
		return matches.get(0);
	}

	private record ResolvedInlinedInput(String variable, boolean transientRead) { }

	private static ResolvedInlinedInput resolveInlinedInput(InlinedFunctionInputBoundary input,
		Map<String,InlinedFunctionInputBoundary> bindings) {
		String actual = input.actualVariable();
		if(actual == null)
			return new ResolvedInlinedInput(input.boundVariable(), false);
		Set<String> visited = new LinkedHashSet<>();
		while(true) {
			if(!visited.add(actual))
				throw new IllegalStateException("Cyclic compiler-owned inlined input binding: " + visited);
			InlinedFunctionInputBoundary binding = bindings.get(actual);
			if(binding == null)
				return new ResolvedInlinedInput(actual, true);
			if(binding.actualVariable() == null)
				return new ResolvedInlinedInput(binding.boundVariable(), false);
			actual = binding.actualVariable();
		}
	}

	private static String resolveInlinedOutput(InlinedFunctionOutputBoundary output,
		Map<String,InlinedFunctionOutputBoundary> bindings) {
		String variable = output.boundVariable();
		Set<String> visited = new LinkedHashSet<>();
		while(true) {
			if(!visited.add(variable))
				throw new IllegalStateException("Cyclic compiler-owned inlined output binding: " + visited);
			InlinedFunctionOutputBoundary binding = bindings.get(variable);
			if(binding == null)
				return variable;
			variable = binding.boundVariable();
		}
	}

	private static int boundaryCount(String[] names, int structuralArity) {
		return names == null ? structuralArity : Math.max(names.length, structuralArity);
	}

	private static String functionOutputVariableName(FunctionOp call, int position) {
		String[] names = call.getOutputVariableNames();
		if(names != null && position < names.length && names[position] != null
			&& !names[position].isBlank())
			return names[position];
		List<Hop> outputs = call.getOutputs();
		if(outputs != null && position < outputs.size() && outputs.get(position) != null
			&& outputs.get(position).getName() != null && !outputs.get(position).getName().isBlank())
			return outputs.get(position).getName();
		return null;
	}

	private static BoundaryName boundaryName(String[] names, int position) {
		if(names == null || position >= names.length)
			return BoundaryName.absent();
		String name = names[position];
		return name == null || name.isBlank() ? BoundaryName.unnamed() : BoundaryName.known(name);
	}

	private static List<Node> classifyOrphanFunctionBodies(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes) {
		List<Node> result = new ArrayList<>(nodes);
		for(int i = 0; i < occurrences.size(); i++) {
			var o = occurrences.get(i);
			if(o.namespace() == null || o.namespace().isBlank() || o.namespace().equals("main") || !o.path().startsWith("function/")
				|| occurrences.stream().anyMatch(x -> x.hop() instanceof FunctionOp && functionMatches((FunctionOp)x.hop(), o.namespace()))) continue;
			Node n = nodes.get(i);
			result.set(i, new Node(n.key(), NodeKind.FUNCTION_BODY_NON_EMITTED, n.valueVersion(), false, List.of(),
				List.of(new Exclusion(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), ReasonCode.NON_EMITTED_FUNCTION_BODY_CONTEXT, "orphan-function-body")), List.of()));
		}
		return result;
	}

	private static Node functionBoundaryNode(Node call, String functionKey, BoundaryName variable, int callIndex,
		int position, VersionKind versionKind, NodeKind nodeKind, List<PlacementState> alternatives,
		List<DurableAnchorKey> anchors) {
		String boundary = versionKind == VersionKind.FUNCTION_INPUT ? "input" : "output";
		String callPath = call.key().callSitePath() + "->" + functionKey + '/' + boundary + '-' + position;
		String context = "callsite:" + call.key().normalizedSignature();
		ControlRegionKey region = new ControlRegionKey(call.key().programFingerprint(), functionKey,
			List.of(call.key().callSitePath(), boundary + '-' + position), callPath, context);
		CompiledHopKey key = new CompiledHopKey(call.key().programFingerprint(), functionKey, callPath, context,
			region, boundary + '-' + callIndex + '-' + position,
			"function-boundary:" + functionKey + ':' + boundary + ':' + variable.canonicalSourceOriginToken());
		ValueVersionKey value = new ValueVersionKey(call.key().programFingerprint(), variable.identityToken(), region, position,
			versionKind, List.of("callsite:" + call.key().normalizedSignature()));
		return variable.isKnown() ? new Node(key, nodeKind, value, true, alternatives, List.of(), anchors)
			: new Node(key, nodeKind, value, false, List.of(), unknownBoundaryExclusions(alternatives, variable), List.of());
	}

	private static List<Exclusion> unknownBoundaryExclusions(List<PlacementState> alternatives, BoundaryName variable) {
		List<Exclusion> exclusions = new ArrayList<>();
		for(PlacementState alternative : alternatives)
			exclusions.add(new Exclusion(alternative, ReasonCode.UNKNOWN_METADATA,
				"function-boundary:" + variable.kind().name()));
		return Collections.unmodifiableList(exclusions);
	}

	private static List<PlacementState> transientAlternatives(List<PlacementState> alternatives) {
		Set<PlacementState> result = new java.util.TreeSet<>();
		for(PlacementState state : alternatives)
			if(isLegalTransient(state)) result.add(state);
		result.add(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false));
		return Collections.unmodifiableList(new ArrayList<>(result));
	}

	private record CallBoundaryContext(int callIndex, FunctionOp callOp, Node callNode,
		long callScope, Map<Hop,Node> callBlockNodes,
		Map<String,Node> inputBoundariesByFormalName) { }

	private record FunctionExpansion(List<Node> nodes, List<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes,
		Map<FunctionOutputBoundaryKey,CompiledHopKey> outputBoundaryKeys) { }

	private static void addCfgFunctionOutputConstraints(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Set<Constraint> constraints, CfgAnalysis cfg, FunctionExpansion expansion) {
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			if(!isTransientRead(occurrences.get(ordinal).hop()))
				continue;
			for(CfgFunctionOutputDefinition definition :
				cfg.reachingFunctionOutputDefinitions().get(ordinal)) {
				CompiledHopKey source = expansion.outputBoundaryKeys().get(new FunctionOutputBoundaryKey(
					definition.callOrdinal(), definition.outputPosition()));
				if(source == null)
					throw new IllegalStateException("CFG function-output definition has no synthetic boundary: "
						+ definition);
				constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT, source,
					nodes.get(ordinal).key(), -1, "cfg-function-output-value:" + definition.variable()));
			}
		}
	}

	private static void addCfgConstraints(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, Set<Constraint> constraints, CfgAnalysis cfg) {
		for(int i = 0; i < occurrences.size(); i++) {
			Node target = nodes.get(i);
			int sourceCount = cfg.reachingDefinitions().get(i).size()
				+ cfg.reachingFunctionOutputDefinitions().get(i).size()
				+ (cfg.reachingFunctionInputs().get(i) ? 1 : 0);
			if(isTransientRead(occurrences.get(i).hop()) && sourceCount > 1) {
				for(int definition : cfg.reachingDefinitions().get(i))
					// A CFG definition/read edge is one runtime variable binding, not a
					// materializing function boundary.  In particular, cpvar preserves a
					// FederationMap; it does not turn FED/FOUT into CP/LOUT.  Therefore every
					// reaching TWrite must select the exact placement consumed by the TRead.
					// Using CONJUNCTIVE here was unsound because its LOUT arm deliberately
					// accepts a FOUT source for boundaries that can charge a download.  No such
					// action exists on this CFG-only edge, so that combination reached runtime
					// as a planner-local TRead backed by a federated symbol.
					constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
						nodes.get(definition).key(), target.key(), -1,
						"cfg-transient-value:" + target.valueVersion().versionKind().name()));
			}
		}
		for(int i = 0; i < occurrences.size(); i++) {
			if(!(occurrences.get(i).hop() instanceof FunctionOp)) continue;
			FunctionOp left = (FunctionOp) occurrences.get(i).hop();
			for(int j = i + 1; j < occurrences.size(); j++) {
				if(occurrences.get(j).hop() instanceof FunctionOp) {
					FunctionOp right = (FunctionOp) occurrences.get(j).hop();
					if(left.getFunctionKey().equals(right.getFunctionKey()))
						constraints.add(new Constraint(ConstraintKind.DISTINCT_CONTEXT,
							nodes.get(i).key(), nodes.get(j).key(),
							-1, left.getFunctionKey()));
				}
			}
		}
	}

	private static List<Node> reclassifyStandaloneRecompileOccurrences(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes) {
		List<Node> result = new ArrayList<>(nodes.size());
		for(int ordinal = 0; ordinal < nodes.size(); ordinal++) {
			Node node = nodes.get(ordinal);
			if(node.kind() != NodeKind.CLONE || node.valueVersion().versionKind() != VersionKind.CLONE_RECOMPILE) {
				result.add(node);
				continue;
			}
			long originCount = nodes.stream()
				.filter(candidate -> candidate.valueVersion().versionKind() != VersionKind.CLONE_RECOMPILE)
				.filter(candidate -> candidate.key().canonicalSourceOrigin()
					.equals(node.key().canonicalSourceOrigin()))
				.count();
			if(originCount > 0) {
				result.add(node);
				continue;
			}
			NodeKind physicalKind = physicalNodeKind(occurrences.get(ordinal).hop());
			result.add(new Node(node.key(), physicalKind, node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), node.anchors()));
		}
		return List.copyOf(result);
	}

	private static void addStableOriginConstraints(List<Node> nodes, Set<Constraint> constraints) {
		for(Node clone : nodes) {
			if(clone.kind() != NodeKind.CLONE) continue;
			List<Node> origins = new ArrayList<>();
			for(Node candidate : nodes)
				if(candidate.kind() != NodeKind.CLONE && candidate.key().canonicalSourceOrigin()
					.equals(clone.key().canonicalSourceOrigin())) origins.add(candidate);
			if(origins.size() == 1)
				constraints.add(new Constraint(ConstraintKind.SAME_ORIGIN, origins.get(0).key(), clone.key(),
					-1, "stable-origin"));
		}
	}

	private static boolean functionMatches(FunctionOp call, String namespace) {
		return namespace.equals(call.getFunctionName()) || namespace.endsWith("::" + call.getFunctionName())
			|| namespace.endsWith("/" + call.getFunctionName());
	}

	/**
	 * A compiler-generated formal {@code TRead X -> TWrite X} pair binds the caller value into
	 * the function CFG. It is an identity edge, not an executable download/upload boundary.
	 * Treating it as an ordinary data dependency lets a planner invent a one-time local
	 * materialization that the pre-unification DP/Exact graphs never exposed and then incorrectly
	 * amortize all loop work over that synthetic transfer.
	 */
	private static boolean isTransparentFunctionInputBinding(Hop input, Hop consumer,
		int inputPosition, Node inputNode, Node consumerNode) {
		if(inputPosition != 0 || inputNode == null || consumerNode == null
			|| inputNode.valueVersion().versionKind() != VersionKind.FUNCTION_INPUT
			|| inputNode.kind() != NodeKind.TRANSIENT_READ
			|| consumerNode.kind() != NodeKind.TRANSIENT_WRITE
			|| !isTransientRead(input) || !isTransientWrite(consumer)
			|| consumer.getInput().size() != 1
			|| !Objects.equals(input.getName(), consumer.getName())
			|| !inputNode.key().functionNamespace().equals(consumerNode.key().functionNamespace())
			|| !inputNode.key().recompileContext().equals(consumerNode.key().recompileContext()))
			return false;
		return inputNode.legalAlternatives().equals(consumerNode.legalAlternatives())
			&& inputNode.anchors().equals(consumerNode.anchors())
			&& inputNode.legalAlternatives().stream().allMatch(NeutralPlacementGraphBuilder::isLegalTransient);
	}

	private static String valueReference(ValueVersionKey value) {
		return value.cfgReferenceSignature();
	}

	private static String cfgFunctionInputReference(String namespace, String variable) {
		return CFG_FUNCTION_INPUT_PREFIX + namespace + ':' + variable;
	}

	private static boolean hasCfgFunctionInputPredecessor(Node node) {
		return node.valueVersion().predecessorVersions().stream()
			.anyMatch(value -> value.startsWith(CFG_FUNCTION_INPUT_PREFIX));
	}

	private static boolean isFunctionInputReplayTarget(Node node) {
		return (node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.BRANCH_JOIN
			|| node.kind() == NodeKind.LOOP_PHI)
			&& (node.valueVersion().versionKind() == VersionKind.FUNCTION_INPUT
				|| hasCfgFunctionInputPredecessor(node));
	}

	private Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value, List<DurableAnchorKey> anchors,
		List<DurableAnchorKey> inputAnchors, List<CompiledHopKey> inputAnchorOwners,
		NodeShapeFact shape, List<NodeShapeFact> inputShapeFacts,
		List<List<FType>> inputDomains,
		List<CandidateRuleKey> candidateRuleDomainKeys, List<CandidateRuleFact> candidateRuleFacts) {
		int candidateFactStart = candidateRuleFacts.size();
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState,Exclusion> excluded = new java.util.TreeMap<>();
		PlacementState cp = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		legal.add(cp);
		if(key.recompileContext().equals("recompile")) {
			PlacementState forbidden = new PlacementState(ExecType.CP, FederatedOutput.FOUT, null, false);
			excluded.putIfAbsent(forbidden, new Exclusion(forbidden, ReasonCode.RECOMPILE_CP_FOUT, "recompile-context forbids CP/FOUT"));
		}
		boolean transientAccess = isTransientRead(hop) || isTransientWrite(hop);
		for(List<FType> inputs : inputCombinations(inputDomains)) {
			CandidateRuleKey candidateKey = new CandidateRuleKey(key, candidateInputStates(inputs));
			candidateRuleDomainKeys.add(candidateKey);
			Set<CandidateEmissionFact> exactEmissionFacts = new LinkedHashSet<>();
			if(legal.contains(cp))
				exactEmissionFacts.add(candidateEmissionFact(exactLegalState(legal, cp), false, null));
			OpCaps caps;
			DecisionEvidence evidence;
			boolean shapeDependent;
			try {
				evidence = oracle.decideWithEvidence(hop, inputs, null);
				caps = evidence.caps();
				shapeDependent = evidence.shapeDependent();
			}
			catch(Throwable t) {
				candidateRuleFacts.add(candidateRuleFailureFact(key, inputs, t));
				PlacementState failure = new PlacementState(ExecType.FED, FederatedOutput.LOUT, firstFType(inputs), false);
				addGlobalExclusion(legal, excluded, new Exclusion(failure, ReasonCode.RULE_ERROR,
					"RULE_ERROR:" + t.getClass().getSimpleName()));
				continue;
			}
			FType exactVectorLocalType = exactAggregateBinaryVectorLocalType(hop, shape, inputs);
			FType outType = caps.foutFType().orElse(firstFType(inputs));
			if(caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
				&& exactVectorLocalType != null)
				outType = exactVectorLocalType;
			boolean hasExactVectorLocalEmission = exactVectorLocalType != null;
			boolean exactShapeDependent = shapeDependent
				|| caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
					&& hasExactVectorLocalEmission;
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType, exactShapeDependent);
			String detail = "inputs=" + inputEvidence(inputs) + "|proof=" + evidence.shapeProof()
				+ '|' + caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR)
				addGlobalExclusion(legal, excluded, new Exclusion(state, ReasonCode.RULE_ERROR, detail));
			else if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				addGlobalExclusion(legal, excluded, new Exclusion(state, ReasonCode.RECOMPILE_CP_FOUT, detail));
			else if(transientAccess && !isLegalTransient(state))
				addGlobalExclusion(legal, excluded, new Exclusion(state, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, detail));
			else if(!evidence.shapeProof().missingRequiredFacts().isEmpty())
				addUnknownMetadataExclusionUnlessProvenLegal(legal, excluded, state, detail);
			else if(caps.exec() == ExecType.FED) {
				PlacementState exactNative = addLegalCandidate(legal, excluded, state);
				if(exactNative != null)
					exactEmissionFacts.add(candidateEmissionFact(exactNative, false, outType));
				if(caps.placement() == FederatedOutput.FOUT
					&& ExecPlacementPolicy.supportsForcedLocalFederatedOutput(hop)
					&& !hasExactVectorLocalEmission) {
					PlacementState exactLout = addLegalCandidate(legal, excluded,
						new PlacementState(ExecType.FED, FederatedOutput.LOUT, outType, shapeDependent));
					if(exactLout != null)
						exactEmissionFacts.add(candidateEmissionFact(exactLout, false, outType));
				}
				MaterializationAnchor materialization = exactCandidateMaterializationAnchor(
					anchors, inputAnchors, inputAnchorOwners, inputs);
				DurableAnchorKey materializationAnchor = materialization == null ? null : materialization.anchor();
				FType materializationFType = exactMaterializationFType(shape, materializationAnchor);
				if(materializationFType != null && !key.recompileContext().equals("recompile")
					&& !transientAccess && outType != null && outType != FType.PART && outType != FType.OTHER) {
					PlacementState cpFout = addLegalCandidate(legal, excluded,
						new PlacementState(ExecType.CP, FederatedOutput.FOUT, materializationFType, shapeDependent));
					if(cpFout != null) {
						DerivedFoutMaterializationActionKey action = derivedFoutAction(key, value, candidateKey,
							exactLegalState(legal, cp), cpFout, materializationAnchor,
							materialization.owner(), materialization.ownerFType(), materializationFType);
						exactEmissionFacts.add(candidateEmissionFact(cpFout, false, null, action));
					}
					if(caps.placement() == FederatedOutput.LOUT) {
						PlacementState derivedFout = addLegalCandidate(legal, excluded,
							new PlacementState(ExecType.FED, FederatedOutput.FOUT, materializationFType, shapeDependent));
						if(derivedFout != null) {
							DerivedFoutMaterializationActionKey action = derivedFoutAction(key, value, candidateKey,
								exactNative, derivedFout, materializationAnchor,
								materialization.owner(), materialization.ownerFType(), materializationFType);
							exactEmissionFacts.add(candidateEmissionFact(derivedFout, true, outType, action));
						}
					}
				}
				for(FType inputType : inputs)
					if(isAggregateBinaryVectorInput(hop, shape, inputType)) {
						PlacementState supplemental = addLegalCandidate(legal, excluded,
							new PlacementState(ExecType.FED, FederatedOutput.LOUT, inputType, true));
						if(supplemental != null)
							exactEmissionFacts.add(candidateEmissionFact(supplemental, false, inputType));
					}
			}
			candidateRuleFacts.add(candidateRuleFact(hop, candidateKey, inputShapeFacts, inputs, caps,
				evidence, exactEmissionFacts));
		}
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
				replaceFederatedSourceCandidateFacts(candidateRuleFacts, candidateFactStart, exactSource, hop);
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

	/**
	 * Literal federated sources are fixed runtime values, not executable CP candidates. Keep their
	 * enumeration receipts, but bind every captured input variant to the one graph-owned source
	 * state so DP/Exact cannot observe an Oracle placeholder tuple that the node itself forbids.
	 */
	private static void replaceFederatedSourceCandidateFacts(List<CandidateRuleFact> candidateRuleFacts,
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
		CandidateEmissionFact emission = candidateEmissionFact(exactSource, false, exactSource.fType());
		for(int index = candidateFactStart; index < candidateRuleFacts.size(); index++) {
			CandidateRuleFact prior = candidateRuleFacts.get(index);
			candidateRuleFacts.set(index, new CandidateRuleFact(prior.key(), CandidateEvaluationStatus.AVAILABLE,
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

	private static PlacementState exactLegalState(Set<PlacementState> legal, PlacementState state) {
		for(PlacementState candidate : legal)
			if(candidate.equals(state))
				return candidate;
		throw new IllegalStateException("Exact legal state is missing from graph-owned set");
	}

	private static CandidateEmissionFact candidateEmissionFact(PlacementState state, boolean derivedFedFout,
		FType executionFType) {
		return new CandidateEmissionFact(new PlacementEmissionState(state, derivedFedFout), executionFType);
	}

	private static CandidateEmissionFact candidateEmissionFact(PlacementState state, boolean derivedFedFout,
		FType executionFType, DerivedFoutMaterializationActionKey action) {
		return new CandidateEmissionFact(new PlacementEmissionState(state, derivedFedFout), executionFType, action);
	}

	private static DerivedFoutMaterializationActionKey derivedFoutAction(CompiledHopKey producer,
		ValueVersionKey producerValueVersion,
		CandidateRuleKey candidateRule, PlacementState source, PlacementState target,
		DurableAnchorKey anchor, CompiledHopKey anchorOwner, FType anchorOwnerFType,
		FType materializationFType) {
		return new DerivedFoutMaterializationActionKey(producer, producerValueVersion, candidateRule,
			source, target, anchor, anchorOwner, anchorOwnerFType,
			materializationFType, producer.controlRegion().normalizedSignature());
	}

	private static List<CandidateRuleFact> bindExactDerivedFoutAuthorities(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Long> scopes, List<Node> nodes, Map<CompiledHopKey,Hop> origins) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			if(nodesByKey.put(node.key(), node) != null)
				throw new IllegalStateException("Duplicate final node identity while binding derived FOUT authority");
		List<CandidateRuleFact> bound = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			Node factNode = nodesByKey.get(fact.key().parentOccurrence());
			if(factNode != null && factNode.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED) {
				bound.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>(fact.allowedEmissionFacts().size());
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				DerivedFoutMaterializationActionKey provisional = emission.derivedFoutAction();
				if(provisional == null) {
					emissions.add(emission);
					continue;
				}
				if(!scopes.containsKey(fact.key().parentOccurrence()))
					throw new IllegalStateException("Derived FOUT candidate has no exact statement-block scope");
				Node producer = nodesByKey.get(fact.key().parentOccurrence());
				if(producer == null || provisional.producer() != producer.key()
					|| !provisional.producerValueVersion().equals(producer.valueVersion()))
					throw new IllegalStateException(
						"Derived FOUT candidate has no structurally matching final producer authority");
				MaterializationAnchor anchorOwner = canonicalFederatedAnchorOwner(
					provisional, nodes, nodesByKey, origins);
				String exactScope = producer.key().controlRegion().normalizedSignature();
				DerivedFoutMaterializationActionKey exact = new DerivedFoutMaterializationActionKey(
					producer.key(), producer.valueVersion(), fact.key(),
					provisional.sourcePlacement(), provisional.targetPlacement(),
					provisional.durableAnchor(), anchorOwner.owner(),
					anchorOwner.ownerFType(),
					provisional.materializationFType(), exactScope);
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(), exact));
			}
			bound.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(bound);
	}

	/** Rebind value-equal replay states to the final graph-owned state identities. */
	private static List<CandidateRuleFact> bindExactCandidateEmissionStates(
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
				if(priorAction == null) {
					emissions.add(candidateEmissionFact(target,
						emission.emissionState().derivedFedFout(), emission.executionFType()));
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
				emissions.add(candidateEmissionFact(target,
					emission.emissionState().derivedFedFout(), emission.executionFType(), action));
			}
			bound.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(bound);
	}

	/**
	 * Binds an output upload to the compiled node that owns the original runtime
	 * FederationMap whenever that source is present in the whole-program graph.
	 * Propagated anchors prove physical compatibility, but an intermediate hop is
	 * not durable placement authority: a policy projection may legally demote it to
	 * LOUT while the original federated source remains available.  Falling back to
	 * the provisional owner preserves exact non-literal authorities; the projected
	 * policy graph will subsequently remove the action if that owner is unavailable.
	 */
	private static MaterializationAnchor canonicalFederatedAnchorOwner(
		DerivedFoutMaterializationActionKey action, List<Node> nodes,
		Map<CompiledHopKey,Node> nodesByKey, Map<CompiledHopKey,Hop> origins) {
		List<Node> nativeOwners = nodes.stream()
			.filter(node -> {
				Hop origin = origins.get(node.key());
				return origin instanceof DataOp && ((DataOp) origin).getOp() == OpOpData.FEDERATED;
			})
			.filter(node -> node.anchors().stream().anyMatch(anchor ->
				PlacementIdentity.samePhysicalWorkerPool(anchor, action.durableAnchor())
					&& hasSelectableFoutType(node, anchor.fType())))
			.sorted().toList();
		List<Node> exactNativeOwners = nativeOwners.stream()
			.filter(node -> node.anchors().contains(action.durableAnchor())).toList();
		if(!exactNativeOwners.isEmpty())
			return new MaterializationAnchor(action.durableAnchor(),
				exactNativeOwners.get(0).key(), action.durableAnchor().fType());
		if(!nativeOwners.isEmpty()) {
			Node owner = nativeOwners.get(0);
			DurableAnchorKey ownerAnchor = owner.anchors().stream()
				.filter(anchor -> PlacementIdentity.samePhysicalWorkerPool(anchor, action.durableAnchor()))
				.filter(anchor -> hasSelectableFoutType(owner, anchor.fType()))
				.sorted().findFirst().orElseThrow();
			return new MaterializationAnchor(action.durableAnchor(), owner.key(), ownerAnchor.fType());
		}
		Node provisional = nodesByKey.get(action.durableAnchorOwner());
		if(provisional == null || !isSelectableFoutAnchorOwner(provisional, action))
			throw new IllegalStateException(
				"Output materialization has no exact graph-owned FOUT anchor owner");
		return new MaterializationAnchor(action.durableAnchor(), provisional.key(),
			action.durableAnchorOwnerFType());
	}

	private static boolean hasSelectableFoutType(Node node, FType fType) {
		return node.legalAlternatives().stream().anyMatch(state ->
			state.output() == FederatedOutput.FOUT && state.fType() == fType);
	}

	private static boolean isSelectableFoutAnchorOwner(Node node,
		DerivedFoutMaterializationActionKey action) {
		return node.legalAlternatives().stream().anyMatch(state ->
			state.output() == FederatedOutput.FOUT
				&& state.fType() == action.durableAnchorOwnerFType());
	}

	private record MaterializationAnchor(DurableAnchorKey anchor, CompiledHopKey owner,
		FType ownerFType) { }

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

	private static FType exactMaterializationFType(NodeShapeFact shape, DurableAnchorKey anchor) {
		return PlacementCostSemantics.exactMaterializationFType(shape, anchor);
	}

	static void addUnknownMetadataExclusionUnlessProvenLegal(Set<PlacementState> legal,
		Map<PlacementState,Exclusion> excluded, PlacementState state, String detail) {
		if(!legal.contains(state))
			excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail));
	}

	private void captureConsumerProfileFacts(Hop consumer, CompiledHopKey consumerKey,
		List<NodeShapeFact> inputShapeFacts,
		List<CandidateConsumerProfileKey> domainKeys, List<CandidateConsumerProfileFact> facts) {
		for(int inputPosition = 0; inputPosition < inputShapeFacts.size(); inputPosition++) {
			CandidateConsumerProfileKey key = new CandidateConsumerProfileKey(consumerKey, inputPosition);
			domainKeys.add(key);
			ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(consumer, inputShapeFacts,
				List.of(inputPosition));
			facts.add(new CandidateConsumerProfileFact(key, evaluation.status(), evaluation.allowedTargetTypes(),
				evaluation.failureCode()));
		}
	}

	private void captureDetachedConsumerProfileFacts(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, Set<Hop> ownedHops,
		Map<Hop,NodeShapeFact> factsByHop, List<DetachedConsumerProfileFact> facts) {
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
					NodeShapeFact shapeFact = factsByHop.get(input);
					if(shapeFact == null) {
						shapeFact = deriveNodeShapeFact(input);
						factsByHop.put(input, shapeFact);
					}
					inputShapeFacts.add(shapeFact);
				}
				if(producerInputPositions.isEmpty())
					continue;
				ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(parent, inputShapeFacts,
					producerInputPositions);
				DetachedConsumerProfileKey key = new DetachedConsumerProfileKey(producerKey, parentOrdinal,
					PlacementGraphFingerprint.semanticStructuralKey(parent), producerInputPositions);
				facts.add(new DetachedConsumerProfileFact(key, evaluation.status(), evaluation.allowedTargetTypes(),
					evaluation.failureCode()));
			}
		}
	}

	private record ConsumerProfileEvaluation(CandidateEvaluationStatus status,
		List<FType> allowedTargetTypes, String failureCode) { }

	private static NodeShapeFact deriveNodeShapeFact(Hop hop) {
		var shape = OracleFacade.nodeShape(Objects.requireNonNull(hop, "hop"));
		return new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
	}

	private static boolean isMatrixShape(Map<Hop,NodeShapeFact> factsByHop, Hop hop) {
		NodeShapeFact shape = factsByHop.get(Objects.requireNonNull(hop, "hop"));
		if(shape == null)
			throw new IllegalStateException("Hop has no builder-owned shape fact: " + hop.getHopID());
		return shape.dataType().isMatrix();
	}

	/** Matrix and frame values can carry a runtime FederationMap and therefore own a physical data edge. */
	private static boolean isPlacementDataShape(Map<Hop,NodeShapeFact> factsByHop, Hop hop) {
		NodeShapeFact shape = factsByHop.get(Objects.requireNonNull(hop, "hop"));
		if(shape == null)
			throw new IllegalStateException("Hop has no builder-owned shape fact: " + hop.getHopID());
		return shape.dataType().isMatrix() || shape.dataType().isFrame();
	}

	private ConsumerProfileEvaluation evaluateConsumerProfile(Hop consumer, List<NodeShapeFact> inputShapeFacts,
		List<Integer> targetPositions) {
		List<FType> allowed = new ArrayList<>();
		String failure = "";
		for(FType candidate : PlacementCandidateRuleResolver.matrixFTypeCandidates()) {
			try {
				FTypeProfile profile = oracle.inferProfile(consumer,
					consumerProfileInputDomains(inputShapeFacts, targetPositions, candidate), null);
				if(profile != null && profile.outputs() != null && !profile.outputs().isEmpty())
					allowed.add(candidate);
			}
			catch(Throwable t) {
				failure = "PROFILE_ERROR:" + t.getClass().getSimpleName();
				allowed.clear();
				break;
			}
		}
		return new ConsumerProfileEvaluation(failure.isEmpty() ? CandidateEvaluationStatus.AVAILABLE
			: CandidateEvaluationStatus.PROFILE_ERROR, List.copyOf(allowed), failure);
	}

	private static List<List<FType>> consumerProfileInputDomains(List<NodeShapeFact> inputShapeFacts,
		int targetPosition,
		FType targetCandidate) {
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

	private CandidateRuleFact candidateRuleFact(Hop hop, CandidateRuleKey key,
		List<NodeShapeFact> inputShapeFacts, List<FType> inputs, OpCaps caps, DecisionEvidence evidence,
		Set<CandidateEmissionFact> exactEmissionFacts) {
		List<CandidateRuleNote> notes = caps.notes().stream()
			.map(note -> new CandidateRuleNote(note.code(), note.message())).toList();
		CandidateCapabilityFact capability = new CandidateCapabilityFact(caps.category(), caps.opcode(), caps.exec(),
			caps.placement(), caps.foutFType().orElse(null), caps.reason(), caps.detail().orElse(""), notes);
		var proof = evidence.shapeProof();
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(proof.consultedFacts(),
			new ArrayList<>(proof.requiredFacts()), new ArrayList<>(proof.missingRequiredFacts()));
		if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR) {
			String failure = "RULE_ERROR" + caps.detail().map(detail -> ":" + detail).orElse("");
			return new CandidateRuleFact(key, CandidateEvaluationStatus.RULE_ERROR, capability, shapeProof,
				new CandidateProfileFact(List.of(), failure), List.of(), failure);
		}
		CandidateProfileFact profile;
		try {
			FTypeProfile inferred = oracle.inferProfile(hop, profileInputDomains(inputShapeFacts, inputs), null);
			profile = new CandidateProfileFact(inferred == null ? List.of() : inferred.outputs(), "");
		}
		catch(Throwable t) {
			profile = new CandidateProfileFact(List.of(), "PROFILE_ERROR:" + t.getClass().getSimpleName());
		}
		CandidateEvaluationStatus status = profile.available() ? CandidateEvaluationStatus.AVAILABLE
			: CandidateEvaluationStatus.PROFILE_ERROR;
		return new CandidateRuleFact(key, status, capability, shapeProof, profile,
			status == CandidateEvaluationStatus.AVAILABLE ? List.copyOf(exactEmissionFacts) : List.of(),
			profile.evaluationFailure());
	}

	private static CandidateRuleFact candidateRuleFailureFact(CompiledHopKey key, List<FType> inputs, Throwable t) {
		String failure = "RULE_ERROR:" + t.getClass().getSimpleName();
		return new CandidateRuleFact(new CandidateRuleKey(key, candidateInputStates(inputs)),
			CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), failure), List.of(), failure);
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

	private static List<DurableAnchorKey> durableAnchor(Hop hop) {
		if(!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FEDERATED) return List.of();
		DataOp data = (DataOp) hop;
		List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
		if(partitions.isEmpty()) return List.of();
		FType type = durableFedInitAnchorFType(data, partitions);
		if(type == null || type == FType.PART || type == FType.OTHER) return List.of();
		return List.of(new DurableAnchorKey("fed-init:" + data.getName(), type, partitions));
	}

	private static FType exactFederatedSourceFType(Hop hop, List<DurableAnchorKey> anchors) {
		if(!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FEDERATED)
			return null;
		if(anchors.size() == 1)
			return anchors.get(0).fType();
		if(!anchors.isEmpty())
			return null;
		DataOp data = (DataOp) hop;
		List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
		return partitions.isEmpty() ? null : exactFedInitSourceFType(data, partitions);
	}

	private static FType exactFedInitSourceFType(DataOp data, List<AnchorPartition> partitions) {
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		return type == null ? deriveAnchorFType(partitions) : type;
	}

	private static FType durableFedInitAnchorFType(DataOp data, List<AnchorPartition> partitions) {
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		return type == null || type == FType.PART || type == FType.OTHER ? deriveAnchorFType(partitions) : type;
	}

	private static List<AnchorPartition> fedInitLiteralPartitions(DataOp data) {
		int addressIndex = data.getParameterIndex(DataExpression.FED_ADDRESSES);
		int rangeIndex = data.getParameterIndex(DataExpression.FED_RANGES);
		if(addressIndex < 0 || rangeIndex < 0) return List.of();
		List<Hop> addresses = data.getInput(addressIndex).getInput();
		List<Hop> ranges = data.getInput(rangeIndex).getInput();
		if(addresses.isEmpty() || ranges.size() != addresses.size() * 2) return List.of();
		List<AnchorPartition> partitions = new ArrayList<>();
		for(int i = 0; i < addresses.size(); i++) {
			if(!(addresses.get(i) instanceof LiteralOp)) return List.of();
			Hop begin = ranges.get(2 * i), end = ranges.get(2 * i + 1);
			if(begin.getInput().size() < 2 || end.getInput().size() < 2
				|| !(begin.getInput(0) instanceof LiteralOp) || !(begin.getInput(1) instanceof LiteralOp)
				|| !(end.getInput(0) instanceof LiteralOp) || !(end.getInput(1) instanceof LiteralOp)) return List.of();
			partitions.add(new AnchorPartition(((LiteralOp) addresses.get(i)).getStringValue(),
				List.of(((LiteralOp) begin.getInput(0)).getLongValue(), ((LiteralOp) begin.getInput(1)).getLongValue()),
				List.of(((LiteralOp) end.getInput(0)).getLongValue(), ((LiteralOp) end.getInput(1)).getLongValue())));
		}
		return List.copyOf(partitions);
	}

	private static FType deriveAnchorFType(List<AnchorPartition> partitions) {
		if(partitions.isEmpty()) return null;
		long maxRow = partitions.stream().mapToLong(p -> p.end().get(0)).max().orElse(-1);
		long maxCol = partitions.stream().mapToLong(p -> p.end().get(1)).max().orElse(-1);
		boolean spansRows = partitions.stream().allMatch(p -> p.begin().get(0) == 0 && p.end().get(0) == maxRow);
		boolean spansCols = partitions.stream().allMatch(p -> p.begin().get(1) == 0 && p.end().get(1) == maxCol);
		if(spansRows && spansCols) return partitions.size() == 1 ? FType.FULL : FType.BROADCAST;
		if(spansCols) return FType.ROW;
		if(spansRows) return FType.COL;
		return FType.OTHER;
	}

	private record CandidateMaterializationClosure(List<Node> nodes,
		List<CandidateRuleFact> candidateRuleFacts, List<Integer> changedOrdinals) { }

	/**
	 * Closes derived worker-pool materializations and then replays every exact
	 * immediate downstream candidate domain affected by those new FOUT layouts. A derived
	 * FED/LOUT -&gt; FED/FOUT/BROADCAST state is a real producer output domain; if it
	 * is not propagated, the downstream oracle row and its relocation obligation
	 * silently disappear. A replay can itself expose a new exact FED/LOUT row at a
	 * descendant, so closure and replay must reach a fixed point; stopping after one
	 * pass closes legal multi-hop REFED chains based on traversal order. The state
	 * space is finite ({@code node x placement tuple}) and every step uses existing
	 * exact compiled edges and durable anchors; no fallback candidate is synthesized.
	 */
	private CandidateReplay closeWorkerPoolMaterializationDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> candidateRuleFacts,
		List<LogicalTransientInputFact> logicalTransientInputs,
		List<CompiledInputEdgeFact> compiledInputEdges, java.util.Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, CfgAnalysis cfg) {
		CandidateReplay current = closeLatentWdivmmRuntimeOutputContracts(
			new CandidateReplay(List.copyOf(nodes), List.copyOf(domainKeys),
				List.copyOf(candidateRuleFacts), List.copyOf(logicalTransientInputs), List.of()),
			compiledInputEdges, origins, factsByHop);
		java.util.TreeSet<Integer> changedOrdinals = new java.util.TreeSet<>();
		changedOrdinals.addAll(current.changedOrdinals());
		int maxPasses = Math.max(1, nodes.size() * (FType.values().length + 1) * 2);
		for(int pass = 0; pass < maxPasses; pass++) {
			CandidateMaterializationClosure materialization =
				closeDerivedWorkerPoolMaterializationCandidates(current.nodes(), current.facts(),
					compiledInputEdges, current.logicalInputs(), constraints, origins, factsByHop);
			if(materialization.changedOrdinals().isEmpty())
				return new CandidateReplay(materialization.nodes(), current.domainKeys(),
					materialization.candidateRuleFacts(), current.logicalInputs(),
					List.copyOf(changedOrdinals));
			changedOrdinals.addAll(materialization.changedOrdinals());
			CandidateReplay replayed = closePostCfgPhysicalCandidateDependencies(occurrences,
				new CandidateReplay(materialization.nodes(), current.domainKeys(),
					materialization.candidateRuleFacts(), current.logicalInputs(),
					materialization.changedOrdinals()), factsByHop, ordinalsByBlock, cfg);
			Set<Integer> recompileDescendants = exactAffectedDescendants(occurrences,
				materialization.changedOrdinals(), ordinalsByBlock, factsByHop).stream()
				.filter(ordinal -> replayed.nodes().get(ordinal).key().recompileContext().equals("recompile"))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			current = recompileDescendants.isEmpty() ? replayed
				: retainRecompileMaterializationLayoutChanges(replayed, current,
					recompileDescendants);
			current = closeLatentWdivmmRuntimeOutputContracts(current,
				compiledInputEdges, origins, factsByHop);
			changedOrdinals.addAll(current.changedOrdinals());
		}
		throw new IllegalStateException("Worker-pool materialization candidate closure did not converge");
	}

	/**
	 * Replaces the source-transpose Oracle shell with the exact output contract of
	 * the WDivMM instruction produced by dynamic recompilation.
	 *
	 * <p>Pattern 1 lowers to LEFT WDivMM. With a ROW-partitioned weight matrix every
	 * worker produces an overlapping partial result and the runtime unconditionally
	 * aggregates those partials at the coordinator. Native FED/FOUT is therefore not
	 * an executable candidate, even if the pre-rewrite transpose Oracle row published
	 * it. This is an explicit runtime-capability closure, not a performance guard:
	 * FED/LOUT remains open and the ordinary materialization closure may subsequently
	 * add a costed FED/LOUT-&gt;FOUT action when a durable worker-pool anchor exists.</p>
	 */
	private static CandidateReplay closeLatentWdivmmRuntimeOutputContracts(
		CandidateReplay replay, List<CompiledInputEdgeFact> compiledInputEdges,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop) {
		List<Node> nodes = new ArrayList<>(replay.nodes());
		List<CandidateRuleFact> facts = new ArrayList<>(replay.facts());
		Map<CompiledHopKey,Integer> nodeIndexes = new IdentityHashMap<>();
		Map<CompiledHopKey,List<Integer>> factIndexes = new IdentityHashMap<>();
		for(int index = 0; index < nodes.size(); index++)
			nodeIndexes.put(nodes.get(index).key(), index);
		for(int index = 0; index < facts.size(); index++)
			factIndexes.computeIfAbsent(facts.get(index).key().parentOccurrence(),
				ignored -> new ArrayList<>()).add(index);

		java.util.TreeSet<Integer> changed = new java.util.TreeSet<>(replay.changedOrdinals());
		for(Node node : List.copyOf(nodes)) {
			PlacementCostSemantics.LatentWdivmmTransposePairFact runtime =
				PlacementCostSemantics.latentWdivmmTransposePairFact(origins, factsByHop,
					compiledInputEdges, nodes, node.key());
			if(runtime == null || !runtime.nativeOutputMustBeLocal()
				|| runtime.partitionedInputFType() == null)
				continue;
			List<Integer> indexes = factIndexes.getOrDefault(node.key(), List.of());
			if(indexes.isEmpty())
				continue;
			boolean factChanged = false;
			for(int factIndex : indexes) {
				CandidateRuleFact prior = facts.get(factIndex);
				if(prior.status() != CandidateEvaluationStatus.AVAILABLE
					|| prior.allowedEmissionFacts().stream().noneMatch(emission ->
						emission.emissionState().placementState().execType() == ExecType.FED))
					continue;
				CandidateRuleFact corrected = latentWdivmmLocalOutputFact(prior,
					runtime.partitionedInputFType());
				facts.set(factIndex, corrected);
				factChanged |= !corrected.equals(prior);
			}
			if(!factChanged)
				continue;

			LinkedHashSet<PlacementState> legal = node.legalAlternatives().stream()
				.filter(state -> state.execType() != ExecType.FED)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			for(int factIndex : indexes)
				for(CandidateEmissionFact emission : facts.get(factIndex).allowedEmissionFacts())
					legal.add(emission.emissionState().placementState());
			List<Exclusion> exclusions = new ArrayList<>(node.exclusions());
			exclusions.removeIf(exclusion -> legal.contains(exclusion.state()));
			for(PlacementState old : node.legalAlternatives())
				if(old.execType() == ExecType.FED && !legal.contains(old)
					&& exclusions.stream().noneMatch(exclusion -> exclusion.state().equals(old)))
					exclusions.add(new Exclusion(old, ReasonCode.RUNTIME_UNSUPPORTED,
						"latent LEFT WDivMM with ROW input always aggregates to LOUT"));
			int ordinal = nodeIndexes.get(node.key());
			nodes.set(ordinal, new Node(node.key(), node.kind(), node.valueVersion(),
				node.emittedWork(), new ArrayList<>(legal), exclusions, node.anchors()));
			changed.add(ordinal);
		}
		return new CandidateReplay(List.copyOf(nodes), replay.domainKeys(), List.copyOf(facts),
			replay.logicalInputs(), List.copyOf(changed));
	}

	private static CandidateRuleFact latentWdivmmLocalOutputFact(CandidateRuleFact prior,
		FType executionFType) {
		Map<PlacementEmissionState,CandidateEmissionFact> emissions = new LinkedHashMap<>();
		for(CandidateEmissionFact emission : prior.allowedEmissionFacts()) {
			PlacementState state = emission.emissionState().placementState();
			if(state.execType() != ExecType.FED) {
				emissions.put(emission.emissionState(), emission);
				continue;
			}
			if(emission.emissionState().derivedFedFout()) {
				CandidateEmissionFact exact = rebindLatentWdivmmDerivedSource(
					emission, executionFType);
				emissions.put(exact.emissionState(), exact);
				continue;
			}
			PlacementState local = new PlacementState(ExecType.FED, FederatedOutput.LOUT,
				executionFType, state.shapeDependent());
			CandidateEmissionFact exact = candidateEmissionFact(local, false, executionFType);
			emissions.putIfAbsent(exact.emissionState(), exact);
		}
		CandidateCapabilityFact capability = prior.capability();
		List<CandidateRuleNote> notes = new ArrayList<>(capability.notes());
		org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode reason =
			org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME;
		CandidateRuleNote note = new CandidateRuleNote(reason,
			"latent LEFT WDivMM with ROW input requires coordinator aggregation");
		if(!notes.contains(note))
			notes.add(note);
		CandidateCapabilityFact correctedCapability = new CandidateCapabilityFact(
			capability.category(), capability.opcode(), ExecType.FED, FederatedOutput.LOUT,
			executionFType, reason,
			"dynamic transpose-pair lowers to LEFT WDivMM; ROW partials overlap", notes);
		Map<String,String> consulted = new LinkedHashMap<>(prior.shapeProof().consultedFacts());
		consulted.put("latentWdivmmRuntimeInputFType", executionFType.name());
		CandidateShapeProofFact proof = new CandidateShapeProofFact(consulted,
			prior.shapeProof().requiredFacts(), prior.shapeProof().missingRequiredFacts());
		return new CandidateRuleFact(prior.key(), prior.status(), correctedCapability, proof,
			new CandidateProfileFact(List.of(executionFType), ""),
			new ArrayList<>(emissions.values()), prior.failureCode());
	}

	private static CandidateEmissionFact rebindLatentWdivmmDerivedSource(
		CandidateEmissionFact emission, FType executionFType) {
		PlacementState target = emission.emissionState().placementState();
		PlacementState source = new PlacementState(ExecType.FED, FederatedOutput.LOUT,
			executionFType, target.shapeDependent());
		DerivedFoutMaterializationActionKey prior = emission.derivedFoutAction();
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			prior.producer(), prior.producerValueVersion(), prior.candidateRule(), source,
			prior.targetPlacement(), prior.durableAnchor(), prior.durableAnchorOwner(),
			prior.durableAnchorOwnerFType(), prior.materializationFType(),
			prior.statementBlockScope());
		return candidateEmissionFact(target, true, executionFType, action);
	}

	private static Set<Integer> exactAffectedDescendants(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Integer> changedOrdinals,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, Map<Hop,NodeShapeFact> factsByHop) {
		List<Set<Integer>> consumersByProducer = new ArrayList<>(occurrences.size());
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++)
			consumersByProducer.add(new java.util.TreeSet<>());
		for(int consumerOrdinal = 0; consumerOrdinal < occurrences.size(); consumerOrdinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
			Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
			for(Hop input : occurrence.hop().getInput()) {
				if(!isPlacementDataShape(factsByHop, input))
					continue;
				Integer producerOrdinal = blockOrdinals == null ? null : blockOrdinals.get(input);
				if(producerOrdinal == null)
					throw new IllegalStateException("Placement-data producer lacks exact affected-descendant owner");
				consumersByProducer.get(producerOrdinal).add(consumerOrdinal);
			}
		}
		java.util.TreeSet<Integer> affected = new java.util.TreeSet<>(changedOrdinals);
		java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>(changedOrdinals);
		while(!pending.isEmpty())
			for(int consumer : consumersByProducer.get(pending.removeFirst()))
				if(affected.add(consumer))
					pending.addLast(consumer);
		return Set.copyOf(affected);
	}

	private static CandidateReplay retainRecompileMaterializationLayoutChanges(CandidateReplay replayed,
		CandidateReplay prior, Set<Integer> recompileDescendants) {
		Map<CompiledHopKey,Node> priorNodes = new IdentityHashMap<>();
		for(Node node : prior.nodes())
			priorNodes.put(node.key(), node);
		Set<CompiledHopKey> affectedKeys = recompileDescendants.stream()
			.map(ordinal -> replayed.nodes().get(ordinal).key())
			.collect(java.util.stream.Collectors.toCollection(
				() -> Collections.newSetFromMap(new IdentityHashMap<>())));
		List<CandidateRuleKey> keys = new ArrayList<>();
		List<CandidateRuleFact> facts = new ArrayList<>();
		for(int index = 0; index < replayed.domainKeys().size(); index++) {
			CandidateRuleKey key = replayed.domainKeys().get(index);
			CandidateRuleFact fact = replayed.facts().get(index);
			// Recompile forbids CP/FOUT output, not consumption of an exact materialized
			// predecessor FOUT. Retain every replayed input row; filtering rows by whether
			// they publish a new layout closes legal multi-hop FED chains whose intermediate
			// consumer emits an already-known FED/LOUT layout.
			keys.add(key);
			facts.add(fact);
		}
		Map<CompiledHopKey,Set<PlacementState>> exactRetainedEmissions = new IdentityHashMap<>();
		for(CandidateRuleFact fact : facts)
			if(affectedKeys.contains(fact.key().parentOccurrence())
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					exactRetainedEmissions.computeIfAbsent(fact.key().parentOccurrence(),
						ignored -> new LinkedHashSet<>())
						.add(emission.emissionState().placementState());
		List<Node> nodes = new ArrayList<>(replayed.nodes());
		for(int ordinal : recompileDescendants) {
			Node current = nodes.get(ordinal);
			Node before = priorNodes.get(current.key());
			if(before == null)
				throw new IllegalStateException("Recompile materialization replay has no prior node");
			Set<PlacementState> retained = exactRetainedEmissions.getOrDefault(current.key(), Set.of());
			List<PlacementState> legal = current.legalAlternatives().stream()
				.filter(state -> before.legalAlternatives().contains(state)
					|| isDistinctRecompileMaterializationLayout(state, before)
					// Recompile forbids CP/FOUT, not an oracle-proven FED/LOUT source
					// emission retained by the exact row that publishes the new FOUT layout.
					|| retained.contains(state) && !(state.execType() == ExecType.CP
						&& state.output() == FederatedOutput.FOUT))
				.toList();
			nodes.set(ordinal, new Node(current.key(), current.kind(), current.valueVersion(),
				current.emittedWork(), legal, current.exclusions(), current.anchors()));
		}
		return new CandidateReplay(List.copyOf(nodes), List.copyOf(keys), List.copyOf(facts),
			replayed.logicalInputs(), replayed.changedOrdinals());
	}

	private static boolean isDistinctRecompileMaterializationLayout(PlacementState state,
		Node prior) {
		return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
			&& state.fType() != null && prior.legalAlternatives().stream().noneMatch(existing ->
				existing.execType() == ExecType.FED && existing.output() == FederatedOutput.FOUT
					&& existing.fType() == state.fType());
	}

	/**
	 * Completes CP/FOUT and derived FED/FOUT candidate facts after the exact
	 * compiled-input graph is known.
	 *
	 * <p>The first candidate pass can only see direct {@code Hop -> anchor} provenance. A legal
	 * federated chain may instead carry the same durable worker pool through one or more exact
	 * FED/FOUT candidates without making every intermediate value own the input's FederationMap.
	 * Earlier cost-based planner graphs could still compare local computation followed by an upload
	 * to that proven worker pool. Preserve that candidate here, without inventing an anchor or
	 * mutating the Hop.
	 * A native FED/LOUT candidate using that same pool must also retain the explicit
	 * FED-&gt;LOUT-&gt;FOUT alternative; otherwise a two-bit execution/output graph would expose CP/FOUT
	 * and FED/LOUT independently while omitting their legal composed state. Every contributing
	 * exact candidate must recursively resolve to one existing durable anchor.
	 * Recompile and transient-access nodes remain closed by their global legality rules.</p>
	 */
	private static CandidateMaterializationClosure closeDerivedWorkerPoolMaterializationCandidates(
		List<Node> nodes, List<CandidateRuleFact> candidateRuleFacts,
		List<CompiledInputEdgeFact> compiledInputEdges, List<LogicalTransientInputFact> logicalTransientInputs,
		java.util.Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> factsByHop) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer =
			matrixEdgesByConsumer(compiledInputEdges, nodesByKey);
		WorkerPoolAnchorResolver resolver = new WorkerPoolAnchorResolver(nodesByKey,
			matrixEdgesByConsumer, candidateRuleFacts, logicalTransientInputs, constraints, origins, factsByHop);
		Map<CompiledHopKey,List<Integer>> factIndexesByNode = new IdentityHashMap<>();
		for(int index = 0; index < candidateRuleFacts.size(); index++)
			factIndexesByNode.computeIfAbsent(candidateRuleFacts.get(index).key().parentOccurrence(),
				ignored -> new ArrayList<>()).add(index);

		List<Node> closedNodes = new ArrayList<>(nodes);
		List<CandidateRuleFact> closedFacts = new ArrayList<>(candidateRuleFacts);
		List<Integer> changedOrdinals = new ArrayList<>();
		for(int nodeIndex = 0; nodeIndex < closedNodes.size(); nodeIndex++) {
			Node node = closedNodes.get(nodeIndex);
			Hop hop = origins.get(node.key());
			if(hop == null || !node.emittedWork() || !isMatrixShape(factsByHop, hop)
				|| node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.TRANSIENT_WRITE
				|| node.legalAlternatives().stream().anyMatch(state ->
					state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT))
				continue;
			List<Integer> indexes = factIndexesByNode.getOrDefault(node.key(), List.of());
			Map<Integer,MaterializationAnchor> exactAnchors = new LinkedHashMap<>();
			Map<Integer,CandidateEmissionFact> exactNativeFedLout = new LinkedHashMap<>();
			Map<Integer,PlacementState> cpFoutByFact = new LinkedHashMap<>();
			Map<Integer,PlacementState> derivedFoutByFact = new LinkedHashMap<>();
			List<Exclusion> exclusions = new ArrayList<>(node.exclusions());
			List<PlacementState> legal = new ArrayList<>(node.legalAlternatives());
			NodeShapeFact shape = factsByHop.get(hop);
			boolean allowCpFout = !node.key().recompileContext().equals("recompile");
			for(int factIndex : indexes) {
				CandidateRuleFact fact = closedFacts.get(factIndex);
				if(fact.status() != CandidateEvaluationStatus.AVAILABLE
					|| fact.allowedEmissionFacts().stream().noneMatch(emission ->
						emission.emissionState().placementState().execType() == ExecType.FED))
					continue;
				List<DurableAnchorKey> anchors = resolver
					.resolveSameFullWorkerPoolCandidateInputs(fact).stream().toList();
				if(anchors.size() != 1)
					continue;
				DurableAnchorKey anchor = anchors.get(0);
				MaterializationAnchor materialization = exactCandidateAnchorOwner(fact, anchor,
					matrixEdgesByConsumer, resolver);
				if(materialization == null)
					continue;
				exactAnchors.put(factIndex, materialization);
				FType materializationFType = exactMaterializationFType(shape, anchor);
				if(materializationFType == null)
					continue;
				// The shape-dependency bit belongs to this exact oracle row. Combining the bits
				// from every row of a node made a vector-MM's shape-dependent FED/LOUT row close
				// an otherwise legal shape-independent materialization row for all candidates.
				boolean shapeDependent = !fact.shapeProof().requiredFacts().isEmpty();
				if(allowCpFout) {
					PlacementState cpFout = new PlacementState(ExecType.CP, FederatedOutput.FOUT,
						materializationFType, shapeDependent);
					if(admitMaterializationClosureState(legal, exclusions, cpFout))
						cpFoutByFact.put(factIndex, cpFout);
				}
				List<CandidateEmissionFact> nativeFedLout = fact.allowedEmissionFacts().stream()
					.filter(emission -> !emission.emissionState().derivedFedFout())
					.filter(emission -> emission.emissionState().placementState().execType() == ExecType.FED
						&& emission.emissionState().placementState().output() == FederatedOutput.LOUT)
					.toList();
				// A native FOUT with one layout does not subsume a post-execution
				// materialization with another layout. In particular, a FULL result and a
				// BROADCAST result are different runtime FederationMaps even on one worker.
				// Retain the exact FED/LOUT emission here and let the type-specific check
				// below decide whether FED/LOUT -> FED/FOUT adds a distinct alternative.
				// This is required in recompile regions, where CP -> FOUT is correctly
				// forbidden but the runtime-supported FED -> LOUT -> FOUT path remains legal.
				if(nativeFedLout.size() == 1
					&& nativeFedLout.get(0).executionFType() != null) {
					exactNativeFedLout.put(factIndex, nativeFedLout.get(0));
					PlacementState derivedFout = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
						materializationFType, shapeDependent);
					if(admitMaterializationClosureState(legal, exclusions, derivedFout))
						derivedFoutByFact.put(factIndex, derivedFout);
				}
			}
			if(cpFoutByFact.isEmpty() && derivedFoutByFact.isEmpty())
				continue;
			Node closedNode = new Node(node.key(), node.kind(), node.valueVersion(), true,
				legal, exclusions, node.anchors());
			closedNodes.set(nodeIndex, closedNode);
			for(Map.Entry<Integer,MaterializationAnchor> entry : exactAnchors.entrySet()) {
				CandidateRuleFact fact = closedFacts.get(entry.getKey());
				List<CandidateEmissionFact> emissions = new ArrayList<>(fact.allowedEmissionFacts());
				PlacementState cpFout = cpFoutByFact.get(entry.getKey());
				PlacementState exactCpFout = cpFout == null ? null : closedNode.legalAlternatives().stream()
					.filter(cpFout::equals).findFirst().orElseThrow();
				if(exactCpFout != null && emissions.stream().noneMatch(emission ->
					emission.emissionState().placementState().equals(exactCpFout))) {
					PlacementState exactCpLout = closedNode.legalAlternatives().stream()
						.filter(state -> state.execType() == ExecType.CP
						&& state.output() == FederatedOutput.LOUT)
						.findFirst().orElseThrow();
					DerivedFoutMaterializationActionKey action = derivedFoutAction(node.key(), node.valueVersion(),
						fact.key(), exactCpLout, exactCpFout, entry.getValue().anchor(),
						entry.getValue().owner(), entry.getValue().ownerFType(), exactCpFout.fType());
					emissions.add(candidateEmissionFact(exactCpFout, false, null, action));
				}
				CandidateEmissionFact nativeFedLout = exactNativeFedLout.get(entry.getKey());
				PlacementState derivedFout = derivedFoutByFact.get(entry.getKey());
				PlacementState exactDerivedFout = derivedFout == null ? null
					: closedNode.legalAlternatives().stream()
						.filter(derivedFout::equals).findFirst().orElseThrow();
					if(nativeFedLout != null && exactDerivedFout != null
						&& emissions.stream().noneMatch(emission ->
							emission.emissionState().derivedFedFout()
								&& emission.emissionState().placementState().equals(exactDerivedFout))) {
						// A native FED/FOUT and FED/LOUT->FOUT may share the same final
						// placement tuple but have different physical effects. Preserve both
						// exact emissions so the selector can price an output upload against
						// downstream FOUT-to-local materialization rather than silently forcing
						// the native path.
						DerivedFoutMaterializationActionKey action = derivedFoutAction(node.key(), node.valueVersion(), fact.key(),
							nativeFedLout.emissionState().placementState(), exactDerivedFout,
							entry.getValue().anchor(), entry.getValue().owner(), entry.getValue().ownerFType(),
							exactDerivedFout.fType());
						emissions.add(candidateEmissionFact(exactDerivedFout, true,
							nativeFedLout.executionFType(), action));
					}
				closedFacts.set(entry.getKey(), new CandidateRuleFact(fact.key(), fact.status(),
					fact.capability(), fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
			}
			if(!closedNode.equals(node) || indexes.stream().anyMatch(index ->
				!closedFacts.get(index).equals(candidateRuleFacts.get(index))))
				changedOrdinals.add(nodeIndex);
		}
		return new CandidateMaterializationClosure(List.copyOf(closedNodes), List.copyOf(closedFacts),
			List.copyOf(changedOrdinals));
	}

	private static boolean admitMaterializationClosureState(List<PlacementState> legal,
		List<Exclusion> exclusions, PlacementState state) {
		Exclusion blocking = exclusions.stream().filter(exclusion -> exclusion.state().equals(state))
			.findFirst().orElse(null);
		if(blocking != null && blocking.reasonCode() != ReasonCode.UNKNOWN_METADATA)
			return false;
		exclusions.removeIf(exclusion -> exclusion.state().equals(state));
		if(legal.stream().noneMatch(state::equals))
			legal.add(state);
		return true;
	}

	private static MaterializationAnchor exactCandidateAnchorOwner(CandidateRuleFact fact,
		DurableAnchorKey anchor,
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer,
		WorkerPoolAnchorResolver resolver) {
		Map<Integer,CompiledInputEdgeFact> edges = matrixEdgesByConsumer
			.getOrDefault(fact.key().parentOccurrence(), Map.of());
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			CandidateInputState input = fact.key().orderedInputs().get(position);
			CompiledInputEdgeFact edge = edges.get(position);
			if(!input.present() || edge == null)
				continue;
			if(resolver.resolve(edge.producer(), input.fType()).stream()
				.anyMatch(candidate -> PlacementIdentity.samePhysicalWorkerPool(candidate, anchor)))
				return new MaterializationAnchor(anchor, edge.producer(), input.fType());
		}
		return null;
	}

	private static Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer(
		List<CompiledInputEdgeFact> compiledInputEdges, Map<CompiledHopKey,Node> nodesByKey) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> result = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledInputEdges) {
			if(!nodesByKey.containsKey(edge.producer()) || !nodesByKey.containsKey(edge.consumer()))
				throw new IllegalStateException("Compiled matrix edge is outside the neutral graph");
			CompiledInputEdgeFact prior = result
				.computeIfAbsent(edge.consumer(), ignored -> new java.util.TreeMap<>())
				.put(edge.inputPosition(), edge);
			if(prior != null)
				throw new IllegalStateException("Duplicate compiled matrix edge for consumer input position");
		}
		return result;
	}

	private static List<NeutralPlacementGraph.RelocationAction> relocations(
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		List<Node> nodes, List<LogicalTransientInputFact> logicalTransientInputs,
		java.util.Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes,
		Map<Hop,NodeShapeFact> factsByHop) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer =
			matrixEdgesByConsumer(compiledInputEdges, nodesByKey);
		WorkerPoolAnchorResolver workerPoolAnchors = new WorkerPoolAnchorResolver(nodesByKey,
			matrixEdgesByConsumer, candidateRuleFacts, logicalTransientInputs, constraints, origins, factsByHop);
		Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByConsumer = new IdentityHashMap<>();
		for(CandidateRuleFact fact : candidateRuleFacts)
			candidateFactsByConsumer.computeIfAbsent(fact.key().parentOccurrence(),
				ignored -> new ArrayList<>()).add(fact);
		Map<CompiledHopKey,PlacementCostSemantics.LatentWdivmmTransposePairFact>
			latentWdivmmPairsByOwner = new IdentityHashMap<>();
		for(Node node : nodes) {
			PlacementCostSemantics.LatentWdivmmTransposePairFact pair =
				PlacementCostSemantics.latentWdivmmTransposePairFact(origins, factsByHop,
					compiledInputEdges, nodes, node.key());
			if(pair != null)
				latentWdivmmPairsByOwner.put(node.key(), pair);
		}
		Map<RelocationGroup,Set<InputUse>> uses = new java.util.TreeMap<>();
		Map<RelocationGroup,Set<InputUse>> directUses = new java.util.TreeMap<>();
		for(CandidateRuleFact fact : candidateRuleFacts)
			addRelocationUsesFromExactCandidateFact(fact, nodesByKey, matrixEdgesByConsumer,
				workerPoolAnchors, candidateFactsByConsumer, latentWdivmmPairsByOwner,
				origins, scopes, factsByHop, uses, directUses);
		List<NeutralPlacementGraph.RelocationAction> result = new ArrayList<>();
		for(Map.Entry<RelocationGroup,Set<InputUse>> entry : uses.entrySet()) {
			RelocationGroup group = entry.getKey();
			Set<CompiledHopKey> consumerSet = new java.util.TreeSet<>();
			for(InputUse use : entry.getValue()) consumerSet.add(use.consumer());
			List<CompiledHopKey> consumers = new ArrayList<>(consumerSet);
			boolean everyUseHasDirectFoutProof = directUses.getOrDefault(group, Set.of())
				.containsAll(entry.getValue());
			List<PlacementState> directSourcePlacements = everyUseHasDirectFoutProof
				? directSourcePlacements(group, nodes, workerPoolAnchors) : List.of();
			RelocationActionKey key = new RelocationActionKey(group.source(), group.target(),
				group.materializationFType(), group.anchor(), group.scope(), consumers);
			List<ObligationKey> obligations = new ArrayList<>();
			for(InputUse use : entry.getValue()) obligations.add(new ObligationKey(use.consumer(), use.position(),
				group.source(), group.target(), key, use.scope()));
			result.add(new NeutralPlacementGraph.RelocationAction(key, obligations,
				directSourcePlacements));
		}
		return result;
	}

	private static void addRelocationUsesFromExactCandidateFact(CandidateRuleFact fact,
		Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer,
		WorkerPoolAnchorResolver workerPoolAnchors,
		Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByConsumer,
		Map<CompiledHopKey,PlacementCostSemantics.LatentWdivmmTransposePairFact>
			latentWdivmmPairsByOwner,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes, Map<Hop,NodeShapeFact> factsByHop,
		Map<RelocationGroup,Set<InputUse>> uses,
		Map<RelocationGroup,Set<InputUse>> directUses) {
		// Relocations are planner feasibility edges proven by an exact AVAILABLE candidate-rule fact:
		// one existing PRESENT input FederationMap supplies the anchor domain, while ABSENT_LOCAL
		// matrix inputs become upload obligations for that same consumer target. This deliberately
		// does not make the consumer own output anchor provenance and is not a runtime fallback.
		if(fact.status() != CandidateEvaluationStatus.AVAILABLE || fact.capability() == null
			|| fact.capability().nativeExec() != ExecType.FED || !fact.profile().available())
			return;
		Node consumer = nodesByKey.get(fact.key().parentOccurrence());
		if(consumer == null)
			throw new IllegalStateException("Relocation candidate has no consumer node: "
				+ fact.key().parentOccurrence());
		// A FunctionOp is a non-executing call-site placeholder, not a runtime consumer that can own
		// caller-side uploads. Exact transfer authority belongs to the synthetic FUNCTION_INPUT nodes
		// and the callee CFG. Publishing a relocation here would create reciprocal cross-anchor uploads
		// for already-federated arguments and lower them as an illegal single-leg fed_refed operation.
		if(consumer.kind() == NodeKind.FUNCTION_CALL)
			return;
		Hop consumerHop = origins.get(consumer.key());
		if(consumerHop == null)
			throw new IllegalStateException("Relocation candidate has no compiled Hop origin: "
				+ consumer.key());
		Map<Integer,CompiledInputEdgeFact> matrixEdges = matrixEdgesByConsumer.getOrDefault(consumer.key(), Map.of());
		Set<DurableAnchorKey> anchors = new java.util.TreeSet<>();
		List<InputUseSeed> absentMatrixInputs = new ArrayList<>();
		List<PresentInputUseSeed> presentMatrixInputs = new ArrayList<>();
		List<CandidateInputState> inputs = fact.key().orderedInputs();
		for(int inputPosition = 0; inputPosition < inputs.size(); inputPosition++) {
			CandidateInputState input = inputs.get(inputPosition);
			CompiledInputEdgeFact edge = matrixEdges.get(inputPosition);
			PlacementCostSemantics.LatentWdivmmTransposePairFact latentPair =
				latentWdivmmPairsByOwner.get(consumer.key());
			// Dynamic WDivMM lowering removes the inner-matrix -> outer-transpose edge.
			// It is therefore neither a runtime input receipt nor a relocation demand;
			// retaining it here creates an impossible materialization alternative for a
			// value that the compiled instruction never consumes. The fused weights input
			// is coupled separately by the exact physical planner model.
			if(edge != null && inputPosition == 0 && latentPair != null
				&& latentPair.inner() == edge.producer())
				continue;
			// A frame FederationMap can be consumed directly by runtime-native FED
			// instructions (currently transformencode), but FEDRefedInstruction and the
			// CP->FOUT materialization registry are MatrixObject-only. Keep the compiled
			// frame edge for direct feasibility/cost and never manufacture a relocation.
			if(edge != null) {
				Hop sourceHop = origins.get(edge.producer());
				if(sourceHop == null)
					throw new IllegalStateException("Relocation input has no compiled Hop origin: "
						+ edge.producer());
				if(!isMatrixShape(factsByHop, sourceHop))
					continue;
			}
			if(input.present()) {
				// Candidate rules include scalar/broadcast inputs as PRESENT, but scalar
				// values are shipped as instruction operands and do not own a matrix
				// FederationMap receipt. They must not suppress receipts for the other
				// physical matrix inputs of the same exact row.
				if(edge == null && inputPosition < consumerHop.getInput().size()
					&& !isMatrixShape(factsByHop, consumerHop.getInput(inputPosition)))
					continue;
				if(edge == null)
					return;
			List<DurableAnchorKey> matching = workerPoolAnchors.resolve(edge.producer(), input.fType())
				.stream().toList();
			// A PRESENT input without its own durable anchor is still legal when a
			// sibling PRESENT input supplies an exact worker-pool anchor: this input
			// can be CP->FOUT or FED->LOUT->FOUT relocated to that pool.  Requiring
			// every input to resolve an anchor here incorrectly closed mixed-layout
			// rows such as t(X) %*% X.  Retain every real anchor we can prove and
			// publish an exact action for every PRESENT input below; if none of the
			// inputs proves an anchor, the existing anchors.isEmpty() gate still
			// rejects the row without inventing placement metadata.
			anchors.addAll(matching);
			Node source = nodesByKey.get(edge.producer());
				// Every physical PRESENT input needs an explicit receipt, including an exact
				// direct-only federated source.  Otherwise equal FType alone could make inputs
				// from different worker pools look jointly executable with no relocation.
				presentMatrixInputs.add(new PresentInputUseSeed(source.valueVersion(), consumer.key(),
					edge.inputPosition(), input.fType()));
			}
			else if(edge != null) {
				Node source = nodesByKey.get(edge.producer());
				absentMatrixInputs.add(new InputUseSeed(source.valueVersion(), consumer.key(),
					edge.inputPosition()));
			}
		}
		// A PRESENT input still needs an exact relocation alternative when its producer is
		// selected LOUT.  Requiring an unrelated ABSENT_LOCAL sibling here dropped that
		// legal FED->LOUT->FOUT path for single-input forwarding nodes such as TWrite and
		// made the Exact physical domain strictly smaller than the runtime-supported space.
		if(anchors.isEmpty() || absentMatrixInputs.isEmpty() && presentMatrixInputs.isEmpty())
			return;
		List<DurableAnchorKey> targetAnchors = new ArrayList<>();
		for(DurableAnchorKey anchor : anchors)
			if(targetAnchors.stream().noneMatch(existing ->
				PlacementIdentity.samePhysicalWorkerPool(existing, anchor)))
				targetAnchors.add(anchor);
		Long scopeId = scopes.get(consumer.key());
		if(scopeId == null)
			throw new IllegalStateException("Relocation consumer has no statement-block scope: " + consumer.key());
		// The map lookup above validates that this is a builder-owned statement-block occurrence.
		// The action scope itself uses the deterministic control-region identity rather than raw SBID,
		// so equivalent fresh compilations hash the same while distinct CFG/function regions do not coalesce.
		String scope = consumer.key().controlRegion().normalizedSignature();
		// PRESENT is the consumer's required input representation, not a guarantee that the
		// source decision remains FOUT. If the cut selects that source LOUT, an existing exact
		// anchor permits the documented FED->LOUT->FOUT rematerialization. Publish that obligation
		// explicitly so the solver can price and emit it instead of accepting an unauthorised edge.
		for(DurableAnchorKey anchor : targetAnchors) {
			for(PresentInputUseSeed seed : presentMatrixInputs)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					PlacementState target = emission.emissionState().placementState();
					if(target.execType() != ExecType.FED || !consumer.legalAlternatives().contains(target)
						|| seed.materializationFType() == FType.PART
						|| seed.materializationFType() == FType.OTHER)
						continue;
					RelocationGroup group = new RelocationGroup(seed.source(), target,
						seed.materializationFType(), anchor, scope);
					InputUse use = new InputUse(seed.consumer(), seed.position(), scope);
					uses.computeIfAbsent(group, ignored -> new java.util.TreeSet<>()).add(use);
					directUses.computeIfAbsent(group, ignored -> new java.util.TreeSet<>()).add(use);
				}
			for(InputUseSeed seed : absentMatrixInputs) {
				List<PostMaterializationCandidate> materializedCandidates = exactPostMaterializationCandidates(fact,
					seed.position(), candidateFactsByConsumer.getOrDefault(consumer.key(), List.of()), consumer);
				for(PostMaterializationCandidate materialized : materializedCandidates) {
					RelocationGroup group = new RelocationGroup(seed.source(), materialized.target(),
						materialized.materializationFType(), anchor, scope);
					InputUse use = new InputUse(seed.consumer(), seed.position(), scope);
					uses.computeIfAbsent(group, ignored -> new java.util.TreeSet<>()).add(use);
					directUses.computeIfAbsent(group, ignored -> new java.util.TreeSet<>()).add(use);
				}
			}
		}
	}

	private static List<PlacementState> directSourcePlacements(RelocationGroup group,
		List<Node> nodes, WorkerPoolAnchorResolver workerPoolAnchors) {
		List<Node> sources = nodes.stream()
			.filter(node -> node.valueVersion().equals(group.source()))
			.filter(Node::emittedWork).toList();
		if(sources.size() != 1)
			return List.of();
		Node source = sources.get(0);
		Set<DurableAnchorKey> provenPools = workerPoolAnchors.resolve(source.key(),
			group.materializationFType());
		if(provenPools.isEmpty() || provenPools.stream().noneMatch(anchor ->
			PlacementIdentity.samePhysicalWorkerPool(anchor, group.anchor())))
			return List.of();
		return source.legalAlternatives().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT)
			.filter(state -> state.fType() == group.materializationFType())
			.sorted().toList();
	}

	/**
	 * Resolves the state after one exact local matrix input has been uploaded. The seed fact describes
	 * the pre-upload runtime candidate ({@code ABSENT_LOCAL}); the relocation target must instead be
	 * emitted by the otherwise-identical candidate whose uploaded input is {@code PRESENT}. Keeping
	 * these identities separate is essential for mixed-layout operations such as {@code t(P) %*% X}:
	 * the durable worker pool is ROW, the uploaded left input is COL, and the legal FED/LOUT consumer
	 * state is COL. Conflating any two of those facts changes Exact/DP feasibility and cost semantics.
	 */
	private static List<PostMaterializationCandidate> exactPostMaterializationCandidates(
		CandidateRuleFact seed, int inputPosition, List<CandidateRuleFact> candidates,
		Node consumer) {
		List<PostMaterializationCandidate> result = new ArrayList<>();
		List<CandidateInputState> seedInputs = seed.key().orderedInputs();
		if(inputPosition < 0 || inputPosition >= seedInputs.size()
			|| seedInputs.get(inputPosition).present())
			return List.of();
		for(CandidateRuleFact candidate : candidates) {
			if(candidate.key().parentOccurrence() != seed.key().parentOccurrence()
				|| candidate.status() != CandidateEvaluationStatus.AVAILABLE
				|| candidate.capability() == null
				|| candidate.capability().nativeExec() != ExecType.FED
				|| !candidate.profile().available())
				continue;
			List<CandidateInputState> materializedInputs = candidate.key().orderedInputs();
			if(materializedInputs.size() != seedInputs.size()
				|| !materializedInputs.get(inputPosition).present()
				|| !sameInputsExcept(seedInputs, materializedInputs, inputPosition))
				continue;
			FType materializationFType = materializedInputs.get(inputPosition).fType();
			if(materializationFType == FType.PART || materializationFType == FType.OTHER)
				continue;
			// The seed's other PRESENT operands already proved the unique target
			// anchor.  The excluded operand is precisely the value being relocated,
			// so requiring its pre-relocation worker pool to equal that target would
			// reject the legal cross-anchor REFED path we are constructing.
			for(CandidateEmissionFact emission : candidate.allowedEmissionFacts()) {
				PlacementState target = emission.emissionState().placementState();
				if(target.execType() == ExecType.FED && consumer.legalAlternatives().contains(target))
					result.add(new PostMaterializationCandidate(target, materializationFType));
			}
		}
		return result.stream().distinct().sorted().toList();
	}

	private static boolean sameInputsExcept(List<CandidateInputState> left,
		List<CandidateInputState> right, int excludedPosition) {
		for(int inputPosition = 0; inputPosition < left.size(); inputPosition++)
			if(inputPosition != excludedPosition && !left.get(inputPosition).equals(right.get(inputPosition)))
				return false;
		return true;
	}

	/**
	 * Proves the unique durable worker pool behind a derived FOUT without claiming that the derived value has
	 * the source value's exact range identity. A direct {@link Node#anchors()} entry remains the stronger exact
	 * value/FederationMap authority. Otherwise, an AVAILABLE exact candidate may carry worker-pool authority
	 * through its PRESENT matrix inputs. Multiple PRESENT inputs must agree on the same durable anchor, and an
	 * ambiguous union of exact candidate proofs is deliberately rejected by the relocation caller.
	 */
	private static final class WorkerPoolAnchorResolver {
		private final Map<CompiledHopKey,Node> nodesByKey;
		private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer;
		private final Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByProducer = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Map<FType,List<LogicalTransientInputFact>>> logicalTransientInputsByRead =
			new IdentityHashMap<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> functionInputsByRead = new IdentityHashMap<>();
		private final Map<String,List<CompiledHopKey>> cfgDefinitionSourcesByReference = new LinkedHashMap<>();
		private final Map<CompiledHopKey,Hop> origins;
		private final Map<Hop,NodeShapeFact> factsByHop;
		private final Map<CompiledHopKey,Map<FType,Set<DurableAnchorKey>>> memo = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Set<FType>> active = new IdentityHashMap<>();

		private WorkerPoolAnchorResolver(Map<CompiledHopKey,Node> nodesByKey,
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer,
			List<CandidateRuleFact> candidateRuleFacts,
			List<LogicalTransientInputFact> logicalTransientInputs,
			java.util.Collection<Constraint> constraints,
			Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop) {
			this.nodesByKey = nodesByKey;
			this.matrixEdgesByConsumer = matrixEdgesByConsumer;
			this.origins = origins;
			this.factsByHop = factsByHop;
			for(Node node : nodesByKey.values())
				cfgDefinitionSourcesByReference.computeIfAbsent(
					node.valueVersion().cfgReferenceSignature(), ignored -> new ArrayList<>()).add(node.key());
			cfgDefinitionSourcesByReference.values().forEach(keys -> keys.sort(null));
			for(CandidateRuleFact fact : candidateRuleFacts)
				candidateFactsByProducer.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>())
					.add(fact);
			for(LogicalTransientInputFact fact : logicalTransientInputs)
				logicalTransientInputsByRead.computeIfAbsent(fact.targetRead(),
					ignored -> new java.util.EnumMap<>(FType.class))
					.computeIfAbsent(fact.federatedFType(), ignored -> new ArrayList<>()).add(fact);
			logicalTransientInputsByRead.values().forEach(byType ->
				byType.values().forEach(facts -> facts.sort(null)));
			Map<CompiledHopKey,List<CompiledHopKey>> argumentsByBoundary = new IdentityHashMap<>();
			for(Constraint constraint : constraints)
				if(constraint.kind() == ConstraintKind.CONJUNCTIVE
					&& (constraint.evidence().startsWith("function-argument:")
						|| constraint.evidence().startsWith("inlined-function-argument:")))
					argumentsByBoundary.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
						.add(constraint.left());
			for(Constraint constraint : constraints) {
				if(constraint.kind() != ConstraintKind.SAME_PLACEMENT
					|| !"function-formal-input".equals(constraint.evidence()))
					continue;
				List<CompiledHopKey> arguments = argumentsByBoundary.getOrDefault(constraint.left(), List.of());
				if(arguments.isEmpty())
					continue;
				functionInputsByRead.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.addAll(arguments);
			}
			functionInputsByRead.values().forEach(keys -> {
				keys.sort(null);
				for(int index = keys.size() - 1; index > 0; index--)
					if(keys.get(index).equals(keys.get(index - 1)))
						keys.remove(index);
			});
		}

		private Set<DurableAnchorKey> resolve(CompiledHopKey producer, FType fType) {
			Map<FType,Set<DurableAnchorKey>> byType = memo.computeIfAbsent(producer,
				ignored -> new java.util.EnumMap<>(FType.class));
			Set<DurableAnchorKey> cached = byType.get(fType);
			if(cached != null)
				return cached;
			Set<FType> activeTypes = active.computeIfAbsent(producer,
				ignored -> java.util.EnumSet.noneOf(FType.class));
			if(!activeTypes.add(fType))
				return Set.of();
			try {
				Set<DurableAnchorKey> resolved = directAnchors(producer, fType);
				boolean mixedCfgFunctionSource = hasMixedCfgFunctionSources(producer);
				if(resolved.isEmpty() && mixedCfgFunctionSource)
					resolved = resolveMixedCfgFunctionInputs(producer, fType);
				if(resolved.isEmpty() && !mixedCfgFunctionSource) {
					resolved = derivedAnchors(producer, fType);
					if(resolved.isEmpty())
						resolved = resolveLogicalTransientInput(producer, fType);
					if(resolved.isEmpty())
						resolved = resolveFunctionInput(producer, fType);
					if(resolved.isEmpty())
						resolved = resolveCfgDefinitionInputs(producer, fType);
				}
				Set<DurableAnchorKey> immutable = Collections.unmodifiableSet(
					canonicalWorkerPools(resolved));
				byType.put(fType, immutable);
				return immutable;
			}
			finally {
				activeTypes.remove(fType);
				if(activeTypes.isEmpty())
					active.remove(producer);
			}
		}

		private Set<DurableAnchorKey> directAnchors(CompiledHopKey producer, FType fType) {
			Node node = nodesByKey.get(producer);
			if(node == null)
				return Set.of();
			Set<DurableAnchorKey> matching = new java.util.TreeSet<>();
			for(DurableAnchorKey anchor : node.anchors())
				if(anchor.fType() == fType)
					matching.add(anchor);
			return matching;
		}

		private Set<DurableAnchorKey> derivedAnchors(CompiledHopKey producer, FType fType) {
			Set<DurableAnchorKey> result = new java.util.TreeSet<>();
			for(CandidateRuleFact fact : candidateFactsByProducer.getOrDefault(producer, List.of())) {
				if(fact.status() != CandidateEvaluationStatus.AVAILABLE || !emitsFout(fact, fType))
					continue;
				result.addAll(resolveCandidateInputs(fact));
			}
			return result;
		}

		private Set<DurableAnchorKey> resolveCandidateInputs(CandidateRuleFact fact) {
			Map<Integer,CompiledInputEdgeFact> inputsByPosition = matrixEdgesByConsumer
				.getOrDefault(fact.key().parentOccurrence(), Map.of());
			Hop owner = origins.get(fact.key().parentOccurrence());
			if(owner == null)
				return Set.of();
			Set<DurableAnchorKey> candidate = null;
			boolean hasPresentMatrixInput = false;
			List<CandidateInputState> inputs = fact.key().orderedInputs();
			for(int inputPosition = 0; inputPosition < inputs.size(); inputPosition++) {
				CandidateInputState input = inputs.get(inputPosition);
				if(!input.present())
					continue;
				CompiledInputEdgeFact edge = inputsByPosition.get(inputPosition);
				Set<DurableAnchorKey> inputAnchors;
				if(edge == null) {
					// Exact CFG TWrite->TRead forwarding is a logical matrix input,
					// not a fabricated physical Hop edge. Reuse its recorded anchor
					// authority when resolving a downstream direct receipt.
					inputAnchors = resolveLogicalTransientInput(
						fact.key().parentOccurrence(), input.fType());
					if(inputAnchors.isEmpty() && inputPosition < owner.getInput().size()
						&& !isMatrixShape(factsByHop, owner.getInput(inputPosition)))
						continue;
				}
				else {
					Hop sourceHop = origins.get(edge.producer());
					if(sourceHop == null)
						return Set.of();
					// This resolver proves MatrixObject relocation/materialization anchors.
					// Frame FederationMaps are direct-only because fed_refed cannot carry them.
					if(!isMatrixShape(factsByHop, sourceHop))
						continue;
					inputAnchors = resolve(edge.producer(), input.fType());
				}
				if(inputAnchors.isEmpty())
					return Set.of();
				hasPresentMatrixInput = true;
				if(candidate == null)
					candidate = canonicalWorkerPools(inputAnchors);
				else {
					Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
					for(DurableAnchorKey current : candidate)
						if(inputAnchors.stream().anyMatch(inputAnchor -> sameWorkerPool(current, inputAnchor)))
							compatible.add(current);
					candidate = compatible;
				}
				if(candidate.isEmpty())
					return Set.of();
			}
			return hasPresentMatrixInput && candidate != null ? candidate : Set.of();
		}

		/**
		 * Resolves the shared runtime worker pool for exact multi-FULL candidates whose inputs have
		 * different value/range anchors. The proof is deliberately narrower than value-anchor
		 * propagation: it requires an exact transient TWrite-to-TRead fact, one durable anchor per
		 * input, and identical canonical worker endpoints. ROW/COL and different endpoints remain
		 * unresolved, so this cannot invent a placement or act as a runtime fallback.
		 */
		private Set<DurableAnchorKey> resolveSameFullWorkerPoolCandidateInputs(CandidateRuleFact fact) {
			Set<DurableAnchorKey> exact = resolveCandidateInputs(fact);
			if(!exact.isEmpty())
				return exact;
			Map<Integer,CompiledInputEdgeFact> inputsByPosition = matrixEdgesByConsumer
				.getOrDefault(fact.key().parentOccurrence(), Map.of());
			boolean usedLogicalTransientInput = false;
			boolean usedDistinctAnchor = false;
			int presentMatrixInputs = 0;
			DurableAnchorKey workerPoolAnchor = null;
			List<CandidateInputState> inputs = fact.key().orderedInputs();
			for(int inputPosition = 0; inputPosition < inputs.size(); inputPosition++) {
				CandidateInputState input = inputs.get(inputPosition);
				if(!input.present())
					continue;
				if(input.fType() != FType.FULL)
					return exact;
				presentMatrixInputs++;
				CompiledInputEdgeFact edge = inputsByPosition.get(inputPosition);
				if(edge == null)
					return exact;
				Set<DurableAnchorKey> inputAnchors = resolve(edge.producer(), input.fType());
				if(inputAnchors.isEmpty()) {
					inputAnchors = resolveLogicalTransientInput(edge.producer(), input.fType());
					usedLogicalTransientInput |= !inputAnchors.isEmpty();
				}
				if(inputAnchors.size() != 1)
					return exact;
				DurableAnchorKey inputAnchor = inputAnchors.iterator().next();
				if(workerPoolAnchor == null)
					workerPoolAnchor = inputAnchor;
				else {
					if(!sameWorkerPool(workerPoolAnchor, inputAnchor))
						return exact;
					usedDistinctAnchor |= !workerPoolAnchor.equals(inputAnchor);
				}
			}
			return presentMatrixInputs > 1 && usedLogicalTransientInput && usedDistinctAnchor
				? Set.of(workerPoolAnchor) : exact;
		}

		private Set<DurableAnchorKey> resolveLogicalTransientInput(CompiledHopKey producer,
			FType fType) {
			Set<DurableAnchorKey> result = new java.util.TreeSet<>();
			for(LogicalTransientInputFact fact : logicalTransientInputsByRead.getOrDefault(producer, Map.of())
				.getOrDefault(fType, List.of())) {
				if(fact.anchor() != null)
					result.add(fact.anchor());
				result.addAll(resolve(fact.sourceWrite(), fType));
			}
			return result;
		}

		private Set<DurableAnchorKey> resolveFunctionInput(CompiledHopKey producer, FType fType) {
			List<CompiledHopKey> arguments = functionInputsByRead.getOrDefault(producer, List.of());
			if(arguments.isEmpty())
				return Set.of();
			Set<DurableAnchorKey> common = null;
			for(CompiledHopKey argument : arguments) {
				Set<DurableAnchorKey> argumentPools = canonicalWorkerPools(resolve(argument, fType));
				if(argumentPools.isEmpty())
					return Set.of();
				if(common == null)
					common = argumentPools;
				else {
					Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
					for(DurableAnchorKey current : common)
						if(argumentPools.stream().anyMatch(candidate -> sameWorkerPool(current, candidate)))
							compatible.add(current);
					common = compatible;
				}
				if(common.isEmpty())
					return Set.of();
			}
			return common == null ? Set.of() : common;
		}

		private boolean hasMixedCfgFunctionSources(CompiledHopKey producer) {
			Node node = nodesByKey.get(producer);
			return node != null && hasCfgFunctionInputPredecessor(node)
				&& node.valueVersion().predecessorVersions().stream()
					.anyMatch(value -> value.startsWith("cfg-definition:"));
		}

		private Set<DurableAnchorKey> resolveMixedCfgFunctionInputs(CompiledHopKey producer,
			FType fType) {
			Set<DurableAnchorKey> functionPools = canonicalWorkerPools(
				resolveFunctionInput(producer, fType));
			Set<DurableAnchorKey> cfgPools = canonicalWorkerPools(
				resolveCfgDefinitionInputs(producer, fType));
			if(functionPools.isEmpty() || cfgPools.isEmpty())
				return Set.of();
			Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
			for(DurableAnchorKey functionPool : functionPools)
				if(cfgPools.stream().anyMatch(cfgPool -> sameWorkerPool(functionPool, cfgPool)))
					compatible.add(functionPool);
			return compatible;
		}

		/**
		 * Resolves a branch/loop transient read only when every explicit CFG reaching
		 * definition proves the same physical worker-pool layout.  The returned key is
		 * merely a deterministic representative for that pool; it does not collapse the
		 * distinct value/range identities of the reaching definitions.
		 */
		private Set<DurableAnchorKey> resolveCfgDefinitionInputs(CompiledHopKey producer,
			FType fType) {
			Node node = nodesByKey.get(producer);
			if(node == null)
				return Set.of();
			List<String> references = node.valueVersion().predecessorVersions().stream()
				.filter(value -> value.startsWith("cfg-definition:"))
				.map(value -> value.substring("cfg-definition:".length())).sorted().toList();
			if(references.isEmpty())
				return Set.of();
			Set<DurableAnchorKey> common = null;
			for(String reference : references) {
				List<CompiledHopKey> sources = cfgDefinitionSourcesByReference.getOrDefault(reference, List.of());
				if(sources.isEmpty())
					return Set.of();
				Set<DurableAnchorKey> referencePools = new java.util.TreeSet<>();
				for(CompiledHopKey source : sources)
					referencePools.addAll(resolve(source, fType));
				referencePools = canonicalWorkerPools(referencePools);
				if(referencePools.isEmpty())
					return Set.of();
				if(common == null)
					common = referencePools;
				else {
					Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
					for(DurableAnchorKey current : common)
						if(referencePools.stream().anyMatch(candidate -> sameWorkerPool(current, candidate)))
							compatible.add(current);
					common = compatible;
				}
				if(common.isEmpty())
					return Set.of();
			}
			return common == null ? Set.of() : common;
		}

		private static Set<DurableAnchorKey> canonicalWorkerPools(
			java.util.Collection<DurableAnchorKey> anchors) {
			Set<DurableAnchorKey> result = new java.util.TreeSet<>();
			for(DurableAnchorKey anchor : new java.util.TreeSet<>(anchors))
				if(result.stream().noneMatch(existing -> sameWorkerPool(existing, anchor)))
					result.add(anchor);
			return result;
		}

		private static boolean sameWorkerPool(DurableAnchorKey left, DurableAnchorKey right) {
			return PlacementIdentity.samePhysicalWorkerPool(left, right);
		}

		private static boolean emitsFout(CandidateRuleFact fact, FType fType) {
			return fact.allowedEmissionFacts().stream().map(CandidateEmissionFact::emissionState)
				.map(PlacementEmissionState::placementState)
				.anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == fType);
		}

	}

	private record PostMaterializationCandidate(PlacementState target, FType materializationFType)
		implements Comparable<PostMaterializationCandidate> {
		@Override public int compareTo(PostMaterializationCandidate that) {
			int targetOrder = target.compareTo(that.target);
			return targetOrder != 0 ? targetOrder : materializationFType.compareTo(that.materializationFType);
		}
	}

	private static final class RelocationGroup implements Comparable<RelocationGroup> {
		private final ValueVersionKey source;
		private final PlacementState target;
		private final FType materializationFType;
		private final DurableAnchorKey anchor;
		private final String scope;
		private final String normalizedSignature;
		private final int hashCode;

		private RelocationGroup(ValueVersionKey source, PlacementState target,
			FType materializationFType, DurableAnchorKey anchor, String scope) {
			this.source = Objects.requireNonNull(source, "source");
			this.target = Objects.requireNonNull(target, "target");
			this.materializationFType = Objects.requireNonNull(materializationFType,
				"materializationFType");
			this.anchor = Objects.requireNonNull(anchor, "anchor");
			this.scope = Objects.requireNonNull(scope, "scope");
			this.normalizedSignature = source.normalizedSignature() + '|'
				+ target.normalizedSignature() + '|' + materializationFType.name() + '|'
				+ anchor.normalizedSignature() + '|' + scope;
			this.hashCode = Objects.hash(source, target, materializationFType, anchor, scope);
		}

		private ValueVersionKey source() { return source; }
		private PlacementState target() { return target; }
		private FType materializationFType() { return materializationFType; }
		private DurableAnchorKey anchor() { return anchor; }
		private String scope() { return scope; }

		@Override
		public int compareTo(RelocationGroup that) {
			return normalizedSignature.compareTo(that.normalizedSignature);
		}

		@Override
		public boolean equals(Object value) {
			if(this == value)
				return true;
			if(!(value instanceof RelocationGroup that))
				return false;
			return source.equals(that.source) && target.equals(that.target)
				&& materializationFType == that.materializationFType
				&& anchor.equals(that.anchor) && scope.equals(that.scope);
		}

		@Override public int hashCode() { return hashCode; }
	}

	private record InputUseSeed(ValueVersionKey source, CompiledHopKey consumer, int position) { }
	private record PresentInputUseSeed(ValueVersionKey source, CompiledHopKey consumer,
		int position, FType materializationFType) { }

	private record InputUse(CompiledHopKey consumer, int position, String scope) implements Comparable<InputUse> {
		@Override
		public int compareTo(InputUse that) {
			int byConsumer = consumer.compareTo(that.consumer);
			if(byConsumer != 0)
				return byConsumer;
			int byPosition = Integer.compare(position, that.position);
			return byPosition != 0 ? byPosition : scope.compareTo(that.scope);
		}
	}

	private static List<List<FType>> inputDomains(Hop hop, Map<Hop,Node> nodesByHop,
		PlacementGraphFingerprint.HopOccurrence occurrence,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, boolean reachesFunctionInput,
		CfgAnalysis cfg) {
		if(reachesFunctionInput) {
			Set<FType> callTypes = new LinkedHashSet<>();
			boolean local = false;
			for(PlacementGraphFingerprint.HopOccurrence candidate : occurrences) {
				if(!(candidate.hop() instanceof FunctionOp)
					|| !functionMatches((FunctionOp) candidate.hop(), occurrence.namespace()))
					continue;
				FunctionOp call = (FunctionOp) candidate.hop();
				String[] names = call.getInputVariableNames();
				for(int i = 0; i < names.length && i < call.getInput().size(); i++) {
					if(!names[i].equals(lexicalVariable(hop, -1))) continue;
					Hop actualArgument = call.getInput(i);
					Node argument = nodesByHop.get(actualArgument);
					if(argument != null) {
						for(PlacementState state : argument.legalAlternatives()) {
							if(state.output() != FederatedOutput.FOUT || state.fType() == null) local = true;
							else callTypes.add(state.fType());
						}
					}
					else
						local = true;
					for(DurableAnchorKey anchor : exactFunctionArgumentAnchors(actualArgument,
						candidate, occurrences, cfg))
						callTypes.add(anchor.fType());
				}
			}
			List<FType> domain = new ArrayList<>(callTypes);
			domain.sort(java.util.Comparator.comparing(Enum::name));
			if(local || domain.isEmpty()) domain.add(0, null);
			return List.of(Collections.unmodifiableList(domain));
		}
		List<List<FType>> domains = new ArrayList<>();
		for(Hop input : hop.getInput()) {
			Set<FType> types = new LinkedHashSet<>();
			boolean local = false;
			Node predecessor = nodesByHop.get(input);
			if(predecessor != null) {
				for(PlacementState state : predecessor.legalAlternatives()) {
					if(state.output() != FederatedOutput.FOUT || state.fType() == null) local = true;
					else types.add(state.fType());
				}
			}
			if(types.isEmpty()) domains.add(Collections.singletonList(null));
			else {
				List<FType> sorted = new ArrayList<>(types);
				sorted.sort(java.util.Comparator.comparing(Enum::name));
				if(local) sorted.add(0, null);
				domains.add(Collections.unmodifiableList(sorted));
			}
		}
		return domains;
	}

	private static List<DurableAnchorKey> functionInputAnchors(Hop hop,
		PlacementGraphFingerprint.HopOccurrence occurrence,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, Map<Hop,Node> nodesByHop,
		CfgAnalysis cfg) {
		Set<DurableAnchorKey> anchors = new java.util.TreeSet<>();
		String formalName = lexicalVariable(hop, -1);
		for(PlacementGraphFingerprint.HopOccurrence candidate : occurrences) {
			if(!(candidate.hop() instanceof FunctionOp call)
				|| !functionMatches(call, occurrence.namespace()))
				continue;
			String[] names = call.getInputVariableNames();
			for(int inputPosition = 0; names != null && inputPosition < names.length
				&& inputPosition < call.getInput().size(); inputPosition++) {
				if(!formalName.equals(names[inputPosition]))
					continue;
				Hop actualArgument = call.getInput(inputPosition);
				Node argument = nodesByHop.get(actualArgument);
				if(argument != null)
					anchors.addAll(argument.anchors());
				// Function bodies can precede their callers, and a call argument is often a
				// TRead whose anchor is attached only during CFG closure. Resolve only the
				// exact TRead/TWrite lineage back to literal fed-init geometry here so the
				// formal domain is independent of fingerprint construction order.
				anchors.addAll(exactFunctionArgumentAnchors(actualArgument, candidate,
					occurrences, cfg));
			}
		}
		return anchors.size() == 1 ? List.of(anchors.iterator().next()) : List.of();
	}

	private static List<DurableAnchorKey> exactFunctionArgumentAnchors(Hop actualArgument,
		PlacementGraphFingerprint.HopOccurrence callOccurrence,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CfgAnalysis cfg) {
		Set<DurableAnchorKey> anchors = new java.util.TreeSet<>();
		Set<Hop> visitedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Integer> visitedOccurrences = new HashSet<>();
		collectExactFunctionArgumentAnchors(actualArgument, callOccurrence.block(), occurrences,
			cfg, visitedHops, visitedOccurrences, anchors);
		return List.copyOf(anchors);
	}

	private static void collectExactFunctionArgumentAnchors(Hop hop, StatementBlock block,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CfgAnalysis cfg,
		Set<Hop> visitedHops, Set<Integer> visitedOccurrences, Set<DurableAnchorKey> anchors) {
		if(hop == null || !visitedHops.add(hop))
			return;
		List<DurableAnchorKey> direct = durableAnchor(hop);
		if(!direct.isEmpty()) {
			anchors.addAll(direct);
			return;
		}
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			if(occurrence.hop() != hop || occurrence.block() != block
				|| !visitedOccurrences.add(ordinal))
				continue;
			if(isTransientRead(hop))
				for(int definition : cfg.reachingDefinitions().get(ordinal)) {
					if(definition < 0 || definition >= occurrences.size())
						continue;
					PlacementGraphFingerprint.HopOccurrence definitionOccurrence = occurrences.get(definition);
					collectExactFunctionArgumentAnchors(definitionOccurrence.hop(),
						definitionOccurrence.block(), occurrences, cfg, visitedHops,
						visitedOccurrences, anchors);
				}
		}
		if(isTransientWrite(hop) && hop.getInput().size() == 1)
			collectExactFunctionArgumentAnchors(hop.getInput().get(0), block, occurrences,
				cfg, visitedHops, visitedOccurrences, anchors);
	}

	private static List<List<FType>> inputCombinations(List<List<FType>> domains) {
		if(domains.isEmpty()) return List.of(List.of());
		List<List<FType>> result = new ArrayList<>();
		enumerateInputCombinations(domains, new ArrayList<>(), result);
		return result;
	}
	private static void enumerateInputCombinations(List<List<FType>> domains, List<FType> prefix,
		List<List<FType>> result) {
		if(prefix.size() == domains.size()) {
			result.add(Collections.unmodifiableList(new ArrayList<>(prefix)));
			return;
		}
		for(FType type : domains.get(prefix.size())) {
			prefix.add(type);
			enumerateInputCombinations(domains, prefix, result);
			prefix.remove(prefix.size() - 1);
		}
	}
	private static String inputEvidence(List<FType> inputs) {
		List<String> evidence = new ArrayList<>();
		for(int i = 0; i < inputs.size(); i++) evidence.add(i + ":" + String.valueOf(inputs.get(i)));
		return String.join(",", evidence);
	}

	private static FType firstFType(List<FType> values) {
		return values.stream().filter(Objects::nonNull).findFirst().orElse(null);
	}
	private static boolean requiresRecompileMetadata(Hop h) { return h.requiresRecompile(); }
	private static boolean isLegalTransient(PlacementState s) {
		return (s.execType() == ExecType.CP && s.output() == FederatedOutput.LOUT)
			|| (s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT);
	}
	private static boolean isTransientRead(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTREAD; }
	private static boolean isTransientWrite(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTWRITE; }
	private static boolean isFunctionOutput(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.FUNCTIONOUTPUT; }
	private static boolean isMultiReturnBuiltinOutputCarrier(Hop hop) {
		if(!isFunctionOutput(hop) || hop.getInput() == null || hop.getInput().isEmpty())
			return false;
		for(Hop parent : hop.getInput(0).getParent()) {
			if(!(parent instanceof FunctionOp function)
				|| function.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				|| function.getOutputs() == null)
				continue;
			if(function.getOutputs().stream().anyMatch(output -> output == hop))
				return true;
		}
		return false;
	}
	private static String lexicalVariable(Hop h, int ordinal) {
		return h instanceof DataOp && h.getName() != null && !h.getName().isBlank() ? h.getName() : "value-" + ordinal;
	}
	private static NodeKind nodeKind(Hop h, ValueVersionKey value) {
		if(value.versionKind() == VersionKind.CLONE_RECOMPILE) return NodeKind.CLONE;
		return physicalNodeKind(h, value);
	}

	private static NodeKind physicalNodeKind(Hop h) {
		return physicalNodeKind(h, null);
	}

	private static NodeKind physicalNodeKind(Hop h, ValueVersionKey value) {
		// FUNCTION_INPUT is a value-version property for a concrete formal read. Keep the
		// physical node classified as a compiled TRANSIENT_READ; only synthetic call-site
		// boundary nodes use NodeKind.FUNCTION_INPUT.
		if(value != null && (value.versionKind() == VersionKind.LOOP_HEAD_PHI
			|| value.versionKind() == VersionKind.LOOP_BACKEDGE))
			return NodeKind.LOOP_PHI;
		if(value != null && value.versionKind() == VersionKind.BRANCH_JOIN_PHI) return NodeKind.BRANCH_JOIN;
		if(isTransientRead(h)) return NodeKind.TRANSIENT_READ;
		if(isTransientWrite(h)) return NodeKind.TRANSIENT_WRITE;
		if(isFunctionOutput(h)) return NodeKind.TRANSIENT_WRITE;
		// Only a DML FunctionOp is a non-executing call-site placeholder. Multi-return
		// builtins such as transformencode lower to a concrete CP/FED instruction at this
		// occurrence and must participate in ordinary physical input feasibility and cost.
		if(h instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.DML)
			return NodeKind.FUNCTION_CALL;
		return NodeKind.OPERATION;
	}
	private static String structuralFingerprint(List<PlacementGraphFingerprint.HopOccurrence> hops) {
		List<String> rows = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence h : hops)
			rows.add(h.namespace() + '|' + h.path() + '|' + h.topology() + '|'
				+ PlacementGraphFingerprint.semanticStructuralKey(h.hop()));
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}
}

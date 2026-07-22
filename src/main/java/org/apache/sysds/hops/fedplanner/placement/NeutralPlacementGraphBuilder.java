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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleNote;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
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
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;

/** Finite mutation-free construction of the planner-neutral shadow graph. */
public final class NeutralPlacementGraphBuilder {
	private final OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

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
			String context = requiresRecompileMetadata(hop) ? "recompile" : "compiled";
			ControlRegionKey region = new ControlRegionKey(programId, occurrence.namespace(),
				occurrence.regionPath(), occurrence.path(), context);
			CompiledHopKey key = new CompiledHopKey(programId, occurrence.namespace(), occurrence.path(), context, region,
				occurrence.topology(), PlacementGraphFingerprint.semanticStructuralKey(hop));
			origins.put(key, hop);
			scopes.put(key, occurrence.block().getSBID());
			var shape = OracleFacade.nodeShape(hop);
			NodeShapeFact shapeFact = new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
			factsByHop.put(hop, shapeFact);
			String variable = lexicalVariable(hop, ordinal);
			int version = cfg.definitionOrdinals().get(ordinal);
			VersionKind versionKind = context.equals("recompile") ? VersionKind.CLONE_RECOMPILE
				: cfg.versionKinds().get(ordinal);
			List<String> predecessorEdges = new ArrayList<>();
			for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
				Hop input = hop.getInput(inputPosition);
				if(values.containsKey(input)) predecessorEdges.add("input-" + inputPosition + ':'
					+ values.get(input).normalizedSignature());
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
			DurableAnchorKey occurrenceAnchor = null;
			if(!anchors.isEmpty()) occurrenceAnchor = anchors.get(0);
			else {
				Set<DurableAnchorKey> inherited = new java.util.TreeSet<>();
				for(Hop input : hop.getInput())
					if(anchorProvenance.containsKey(input)) inherited.add(anchorProvenance.get(input));
				if(inherited.size() == 1) occurrenceAnchor = inherited.iterator().next();
			}
			if(occurrenceAnchor == null)
				anchorProvenance.remove(hop);
			else
				anchorProvenance.put(hop, occurrenceAnchor);
			occurrenceAnchorProvenance.add(occurrenceAnchor);
			List<NodeShapeFact> inputShapeFacts = new ArrayList<>(hop.getInput().size());
			for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
				NodeShapeFact inputShapeFact = factsByHop.get(hop.getInput(inputPosition));
				if(inputShapeFact == null)
					throw new IllegalStateException("Candidate input has no builder-owned shape fact: "
						+ key + " input " + inputPosition);
				inputShapeFacts.add(inputShapeFact);
			}
			inputShapeFacts = List.copyOf(inputShapeFacts);
			captureConsumerProfileFacts(hop, key, inputShapeFacts,
				candidateConsumerDomainKeys, candidateConsumerProfileFacts);
			List<DurableAnchorKey> exactAnchors = occurrenceAnchor == null ? List.of() : List.of(occurrenceAnchor);
			Node node = buildNode(hop, key, value, exactAnchors, shapeFact, inputShapeFacts,
				inputDomains(hop, nodesByHop, occurrence, occurrences, versionKind),
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
		AnchorClosure anchorClosure = closeCfgDurableAnchors(nodes, occurrenceAnchorProvenance, cfg);
		nodes = anchorClosure.nodes();
		occurrenceAnchorProvenance = anchorClosure.anchors();
		nodes = classifyOrphanFunctionBodies(occurrences, nodes);
		if(nodes.size() != occurrences.size())
			throw new IllegalStateException("occurrence/node mismatch after CFG closure: "
				+ occurrences.size() + '/' + nodes.size());
		FunctionExpansion functionExpansion = expandFunctionBoundaryContexts(occurrences, nodes,
			origins, scopes);
		nodes = functionExpansion.nodes();
		origins = functionExpansion.origins();
		scopes = functionExpansion.scopes();
		nodesByHop.clear();
		for(int i = 0; i < occurrences.size(); i++) nodesByHop.put(occurrences.get(i).hop(), nodes.get(i));
		Set<Constraint> constraints = new java.util.TreeSet<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			CompiledHopKey consumer = nodes.get(ordinal).key();
			Map<Hop,CompiledHopKey> blockKeys = keysByBlock.get(occurrence.block());
			for(int inputPosition = 0; inputPosition < occurrence.hop().getInput().size(); inputPosition++) {
				Hop input = occurrence.hop().getInput(inputPosition);
				CompiledHopKey inputKey = blockKeys == null ? null : blockKeys.get(input);
				if(inputKey != null) constraints.add(new Constraint(ConstraintKind.DOMINATES,
					inputKey, consumer, inputPosition, "data-input"));
			}
		}
		addCfgConstraints(occurrences, nodes, constraints, cfg);
		constraints.addAll(functionExpansion.constraints());
		addStableOriginConstraints(nodes, constraints);
		List<CompiledInputEdgeFact> compiledInputEdges = deriveCompiledInputEdges(occurrences, nodes,
			ordinalsByBlock);
		List<NeutralPlacementGraph.RelocationAction> relocations = relocations(compiledInputEdges, nodes,
			scopes);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, constraints, relocations);
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
		HeuristicPolicyFacts heuristicPolicyFacts = heuristicPolicyFacts(graph, projections, shapeFacts);
		PlacementAnalysis analysis = new PlacementAnalysis(graph, projections, topLevelStatementBlocks, program, shapeFacts,
			analysisFingerprint, heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, () -> {
				if(!before.equals(PlacementGraphFingerprint.capture(program)))
					throw new IllegalStateException("PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED");
			});
		String after = PlacementGraphFingerprint.capture(program);
		if(!before.equals(after))
			throw new IllegalStateException("Neutral placement analysis mutated the compiled Hop graph");
		if(!registryBefore.equals(registrySentinel(program)))
			throw new IllegalStateException("Neutral placement analysis mutated federated refed state");
		return analysis;
	}


	private static List<CompiledInputEdgeFact> deriveCompiledInputEdges(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock) {
		List<CompiledInputEdgeFact> edges = new ArrayList<>();
		for(int consumerOrdinal = 0; consumerOrdinal < occurrences.size(); consumerOrdinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
			Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
			Hop consumer = occurrence.hop();
			if(!isCompiledHopOccurrenceKey(nodes.get(consumerOrdinal).key()))
				continue;
			for(int inputPosition = 0; inputPosition < consumer.getInput().size(); inputPosition++) {
				Hop producer = consumer.getInput(inputPosition);
				if(!producer.getDataType().isMatrix())
					continue;
				Integer producerOrdinal = blockOrdinals == null ? null : blockOrdinals.get(producer);
				if(producerOrdinal == null)
					throw new IllegalStateException("Matrix producer input lacks exact compiled owner key");
				CompiledHopKey producerKey = nodes.get(producerOrdinal).key();
				if(isCompiledHopOccurrenceKey(producerKey))
					edges.add(new CompiledInputEdgeFact(producerKey,
						nodes.get(consumerOrdinal).key(), inputPosition));
			}
		}
		edges.sort(java.util.Comparator.comparing(CompiledInputEdgeFact::consumer)
			.thenComparingInt(CompiledInputEdgeFact::inputPosition));
		return List.copyOf(edges);
	}

	private static boolean isCompiledHopOccurrenceKey(CompiledHopKey key) {
		return key.controlRegion().regionPath().stream()
			.noneMatch(part -> part.startsWith("function-boundary:"));
	}

	private static HeuristicPolicyFacts heuristicPolicyFacts(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts) {
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
		return new HeuristicPolicyFacts(demotions);
	}

	private static boolean isAggregateBinaryVectorInput(Hop hop, NodeShapeFact shape, FType inputType) {
		if(!(hop instanceof AggBinaryOp) || !shape.knownPositiveMatrix())
			return false;
		return inputType == FType.ROW && shape.cols() == 1
			|| inputType == FType.COL && shape.rows() == 1;
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
		Map<StatementBlock,Set<StatementBlock>> predecessors = new IdentityHashMap<>();
		Set<StatementBlock> loopHeaders = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<StatementBlock> loopLatches = Collections.newSetFromMap(new IdentityHashMap<>());
		connectSequence(topLevelStatementBlocks, Set.of(), predecessors, loopHeaders, loopLatches);
		for(FunctionStatementBlock function : program.getNamedNSFunctionStatementBlocks().values())
			connectSequence(List.of(function), Set.of(), predecessors, loopHeaders, loopLatches);
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
				for(StatementBlock predecessor : predecessors.get(block))
					mergeDefinitions(state, out.get(predecessor));
				transfer(state, byBlock.getOrDefault(block, List.of()), occurrences);
				if(!state.equals(out.get(block))) {
					out.put(block, state);
					changed = true;
				}
			}
		} while(changed);
		List<Set<Integer>> reaching = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) reaching.add(Set.of());
		for(Map.Entry<StatementBlock,List<Integer>> entry : byBlock.entrySet()) {
			Map<String,Set<Integer>> state = new java.util.TreeMap<>();
			for(StatementBlock predecessor : predecessors.getOrDefault(entry.getKey(), Set.of()))
				mergeDefinitions(state, out.get(predecessor));
			for(int index : entry.getValue()) {
				PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(index);
				String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), index);
				if(isTransientRead(occurrence.hop()))
					reaching.set(index, Collections.unmodifiableSet(new java.util.TreeSet<>(
						state.getOrDefault(variable, Set.of()))));
				if(isDefinition(occurrence.hop())) state.put(variable, Set.of(index));
			}
		}
		List<VersionKind> kinds = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(i);
			VersionKind kind = VersionKind.ORDINARY;
			if(isTransientRead(occurrence.hop()) && reaching.get(i).size() > 1)
				kind = loopHeaders.contains(occurrence.block()) ? VersionKind.LOOP_HEAD_PHI
					: branchDefinitionsDiffer(occurrence, predecessors, out)
						? VersionKind.BRANCH_JOIN_PHI : VersionKind.ORDINARY;
			else if(isDefinition(occurrence.hop()) && loopLatches.contains(occurrence.block()))
				kind = VersionKind.LOOP_BACKEDGE;
			kinds.add(kind);
		}
		return new CfgAnalysis(Collections.unmodifiableList(ordinals), Collections.unmodifiableList(kinds),
			Collections.unmodifiableList(reaching));
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
		List<PlacementGraphFingerprint.HopOccurrence> occurrences) {
		for(int index : indices) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(index);
			if(isDefinition(occurrence.hop())) state.put(occurrence.namespace() + '\u0000'
				+ lexicalVariable(occurrence.hop(), index), Set.of(index));
		}
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

	private record CfgAnalysis(List<Integer> definitionOrdinals, List<VersionKind> versionKinds,
		List<Set<Integer>> reachingDefinitions) { }

	private static String registrySentinel(DMLProgram program) {
		List<String> rows = new ArrayList<>();
		for(long sbId : PlacementGraphFingerprint.statementBlockIds(program)) {
			FederatedRefedRegistry.snapshot(sbId).forEach((hop, spec) -> rows.add("R|" + sbId + '|' + hop + '|'
				+ spec.getAnchorHopId() + '|' + spec.getAnchorKey()));
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
			for(int definition : cfg.reachingDefinitions().get(i))
				predecessors.add("cfg-definition:" + valueReference(nodes.get(definition).valueVersion()));
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

	private static AnchorClosure closeCfgDurableAnchors(List<Node> nodes,
		List<DurableAnchorKey> occurrenceAnchors, CfgAnalysis cfg) {
		List<Node> closedNodes = new ArrayList<>(nodes.size());
		List<DurableAnchorKey> closedAnchors = new ArrayList<>(nodes.size());
		for(int i = 0; i < nodes.size(); i++) {
			DurableAnchorKey anchor = occurrenceAnchors.get(i);
			if(anchor == null) {
				Set<DurableAnchorKey> reaching = new java.util.TreeSet<>();
				for(int definition : cfg.reachingDefinitions().get(i)) {
					DurableAnchorKey definitionAnchor = occurrenceAnchors.get(definition);
					if(definitionAnchor != null)
						reaching.add(definitionAnchor);
				}
				if(reaching.size() == 1)
					anchor = reaching.iterator().next();
			}
			Node node = nodes.get(i);
			closedNodes.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), anchor == null ? List.of() : List.of(anchor)));
			closedAnchors.add(anchor);
		}
		return new AnchorClosure(List.copyOf(closedNodes),
			Collections.unmodifiableList(new ArrayList<>(closedAnchors)));
	}

	private record AnchorClosure(List<Node> nodes, List<DurableAnchorKey> anchors) { }

	private static FunctionExpansion expandFunctionBoundaryContexts(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes) {
		List<Node> expanded = new ArrayList<>(nodes);
		List<Constraint> constraints = new ArrayList<>();
		Map<CompiledHopKey,Hop> expandedOrigins = new java.util.LinkedHashMap<>(origins);
		Map<CompiledHopKey,Long> expandedScopes = new java.util.LinkedHashMap<>(scopes);
		Map<StatementBlock,Map<Hop,Node>> nodesByBlock = new IdentityHashMap<>();
		Map<Node,Integer> ordinalsByNode = new IdentityHashMap<>();
		Map<String,List<Node>> inlinedAuthoritiesByFunction = new LinkedHashMap<>();
		Set<Node> claimedInlinedAuthorities = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int i = 0; i < occurrences.size(); i++) {
			nodesByBlock.computeIfAbsent(occurrences.get(i).block(), ignored -> new IdentityHashMap<>())
				.put(occurrences.get(i).hop(), nodes.get(i));
			ordinalsByNode.put(nodes.get(i), i);
		}
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
				constraints.add(new Constraint(ConstraintKind.DOMINATES, call.key(), input.key(), inputPosition,
					"function-callsite-control"));
				if(argument != null)
					constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, argument.key(), input.key(), inputPosition,
						"function-argument:" + inputName.canonicalSourceOriginToken()));
			}
			String[] outputNames = callOp.getOutputVariableNames();
			int outputArity = callOp.getOutputs() == null ? 0 : callOp.getOutputs().size();
			for(int outputPosition = 0; outputPosition < boundaryCount(outputNames, outputArity); outputPosition++) {
				BoundaryName outputName = boundaryName(outputNames, outputPosition);
				Node output = functionBoundaryNode(call, functionKey, outputName, callIndex,
					outputPosition, VersionKind.FUNCTION_OUTPUT, NodeKind.FUNCTION_OUTPUT,
					transientAlternatives(call.legalAlternatives()), call.anchors());
				expanded.add(output);
				expandedOrigins.put(output.key(), callOp);
				expandedScopes.put(output.key(), callScope);
				constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, call.key(), output.key(), outputPosition,
					"function-result:" + outputName.canonicalSourceOriginToken()));
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
				Integer authorityOrdinal = ordinalsByNode.get(callAuthority);
				int callIndex = inlinedCall.callStatementPosition();
				if(callScope == null || authorityOrdinal == null)
					throw new IllegalStateException("Inlined function call has no exact occurrence authority");
				Node originalCallAuthority = callAuthority;
				if(!claimedInlinedAuthorities.add(originalCallAuthority))
					throw new IllegalStateException("Inlined function calls share one emitted authority: "
						+ inlinedCall.functionKey() + " callStatement=" + callIndex);
				Node exactCallAuthority = withNodeKind(originalCallAuthority, NodeKind.FUNCTION_CALL);
				expanded.set(authorityOrdinal, exactCallAuthority);
				ordinalsByNode.remove(originalCallAuthority);
				ordinalsByNode.put(exactCallAuthority, authorityOrdinal);
				nodesByBlock.get(block).replaceAll((hop, node) -> node == originalCallAuthority
					? exactCallAuthority : node);
				for(int i = 0; i < arguments.size(); i++)
					if(arguments.get(i) == originalCallAuthority)
						arguments.set(i, exactCallAuthority);
				for(int i = 0; i < results.size(); i++)
					if(results.get(i) == originalCallAuthority)
						results.set(i, exactCallAuthority);
				callAuthority = exactCallAuthority;
				inlinedAuthoritiesByFunction.computeIfAbsent(inlinedCall.functionKey(), ignored -> new ArrayList<>())
					.add(callAuthority);
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
					expandedOrigins.put(output.key(), origins.get(callAuthority.key()));
					expandedScopes.put(output.key(), callScope);
					constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, result.key(), output.key(),
						outputPosition, "inlined-function-result:"
							+ outputName.canonicalSourceOriginToken()));
				}
			}
		}
		for(Map.Entry<String,List<Node>> entry : inlinedAuthoritiesByFunction.entrySet()) {
			List<Node> authorities = entry.getValue();
			for(int left = 0; left < authorities.size(); left++)
				for(int right = left + 1; right < authorities.size(); right++)
					constraints.add(new Constraint(ConstraintKind.DISTINCT_CONTEXT,
						authorities.get(left).key(), authorities.get(right).key(), -1, entry.getKey()));
		}
		return new FunctionExpansion(Collections.unmodifiableList(expanded),
			Collections.unmodifiableList(constraints), Collections.unmodifiableMap(expandedOrigins),
			Collections.unmodifiableMap(expandedScopes));
	}

	private static Node withNodeKind(Node node, NodeKind kind) {
		return node.kind() == kind ? node : new Node(node.key(), kind, node.valueVersion(), node.emittedWork(),
			node.legalAlternatives(), node.exclusions(), node.anchors());
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

	private record FunctionExpansion(List<Node> nodes, List<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, Map<CompiledHopKey,Long> scopes) { }

	private static void addCfgConstraints(List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, Set<Constraint> constraints, CfgAnalysis cfg) {
		for(int i = 0; i < occurrences.size(); i++) {
			Node target = nodes.get(i);
			if(isTransientRead(occurrences.get(i).hop()) && cfg.reachingDefinitions().get(i).size() > 1) {
				for(int definition : cfg.reachingDefinitions().get(i))
					constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, nodes.get(definition).key(), target.key(),
						-1, target.valueVersion().versionKind().name()));
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

	private static String valueReference(ValueVersionKey value) {
		return value.lexicalVariable() + '#' + value.definitionOrdinal() + '@'
			+ value.definingControlRegion().callSitePath() + ':' + value.versionKind();
	}

	private Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value, List<DurableAnchorKey> anchors,
		NodeShapeFact shape, List<NodeShapeFact> inputShapeFacts, List<List<FType>> inputDomains,
		List<CandidateRuleKey> candidateRuleDomainKeys, List<CandidateRuleFact> candidateRuleFacts) {
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
			candidateRuleDomainKeys.add(new CandidateRuleKey(key, candidateInputStates(inputs)));
			OpCaps caps;
			DecisionEvidence evidence;
			boolean shapeDependent;
			try {
				evidence = oracle.decideWithEvidence(hop, inputs, null);
				caps = evidence.caps();
				shapeDependent = evidence.shapeDependent();
				candidateRuleFacts.add(candidateRuleFact(hop, key, inputShapeFacts, inputs, caps, evidence));
			}
			catch(Throwable t) {
				candidateRuleFacts.add(candidateRuleFailureFact(key, inputs, t));
				PlacementState failure = new PlacementState(ExecType.FED, FederatedOutput.LOUT, firstFType(inputs), false);
				excluded.putIfAbsent(failure, new Exclusion(failure, ReasonCode.RULE_ERROR,
					"RULE_ERROR:" + t.getClass().getSimpleName()));
				continue;
			}
			FType outType = caps.foutFType().orElse(firstFType(inputs));
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType, shapeDependent);
			String detail = "inputs=" + inputEvidence(inputs) + "|proof=" + evidence.shapeProof()
				+ '|' + caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RULE_ERROR, detail));
			else if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RECOMPILE_CP_FOUT, detail));
			else if(transientAccess && !isLegalTransient(state))
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, detail));
			else if(!evidence.shapeProof().missingRequiredFacts().isEmpty())
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail));
			else if(caps.exec() == ExecType.FED) {
				legal.add(state);
				for(FType inputType : inputs)
					if(isAggregateBinaryVectorInput(hop, shape, inputType))
						legal.add(new PlacementState(ExecType.FED, FederatedOutput.LOUT, inputType, true));
			}
		}
		if(transientAccess)
			legal.removeIf(s -> !isLegalTransient(s));
		if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED && anchors.isEmpty()) {
			PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.OTHER, true);
			excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNSUPPORTED_ANCHOR,
				"Federated source lacks literal durable worker/range provenance"));
		}
		else if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED) {
			DurableAnchorKey anchor = anchors.get(0);
			legal.add(new PlacementState(ExecType.FED, FederatedOutput.FOUT, anchor.fType(), false));
		}
		return new Node(key, nodeKind(hop, value), value, true, new ArrayList<>(legal),
			new ArrayList<>(excluded.values()), anchors);
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
						var shape = OracleFacade.nodeShape(input);
						shapeFact = new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
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

	private CandidateRuleFact candidateRuleFact(Hop hop, CompiledHopKey key,
		List<NodeShapeFact> inputShapeFacts, List<FType> inputs, OpCaps caps, DecisionEvidence evidence) {
		List<CandidateRuleNote> notes = caps.notes().stream()
			.map(note -> new CandidateRuleNote(note.code(), note.message())).toList();
		CandidateCapabilityFact capability = new CandidateCapabilityFact(caps.category(), caps.opcode(), caps.exec(),
			caps.placement(), caps.foutFType().orElse(null), caps.reason(), caps.detail().orElse(""), notes);
		var proof = evidence.shapeProof();
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(proof.consultedFacts(),
			new ArrayList<>(proof.requiredFacts()), new ArrayList<>(proof.missingRequiredFacts()));
		if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR) {
			String failure = "RULE_ERROR" + caps.detail().map(detail -> ":" + detail).orElse("");
			return new CandidateRuleFact(new CandidateRuleKey(key, candidateInputStates(inputs)),
				CandidateEvaluationStatus.RULE_ERROR, capability, shapeProof,
				new CandidateProfileFact(List.of(), failure), failure);
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
		return new CandidateRuleFact(new CandidateRuleKey(key, candidateInputStates(inputs)),
			status, capability, shapeProof, profile, profile.evaluationFailure());
	}

	private static CandidateRuleFact candidateRuleFailureFact(CompiledHopKey key, List<FType> inputs, Throwable t) {
		String failure = "RULE_ERROR:" + t.getClass().getSimpleName();
		return new CandidateRuleFact(new CandidateRuleKey(key, candidateInputStates(inputs)),
			CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), failure), failure);
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
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		if(type == null || type == FType.PART || type == FType.OTHER)
			type = deriveAnchorFType(partitions);
		if(type == null || type == FType.PART || type == FType.OTHER) return List.of();
		return List.of(new DurableAnchorKey("fed-init:" + data.getName(), type, partitions));
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

	private static List<NeutralPlacementGraph.RelocationAction> relocations(
		List<CompiledInputEdgeFact> compiledInputEdges, List<Node> nodes,
		Map<CompiledHopKey,Long> scopes) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<RelocationGroup,List<InputUse>> uses = new java.util.TreeMap<>();
		for(CompiledInputEdgeFact edge : compiledInputEdges) {
			Node source = nodesByKey.get(edge.producer());
			Node consumer = nodesByKey.get(edge.consumer());
			if(source == null || consumer == null)
				throw new IllegalStateException("Compiled matrix edge is outside the neutral graph");
			Long scopeId = scopes.get(consumer.key());
			if(scopeId == null)
				throw new IllegalStateException("Relocation consumer has no statement-block scope: " + consumer.key());
			String scope = scopeId + ":" + consumer.key().recompileContext();
			for(DurableAnchorKey anchor : consumer.anchors()) {
				// An input already carrying this durable placement is not an upload source.
				if(source.anchors().contains(anchor))
					continue;
				for(PlacementState target : consumer.legalAlternatives()) {
					if(target.execType() != ExecType.FED || target.output() != FederatedOutput.FOUT
						|| target.fType() != anchor.fType())
						continue;
					RelocationGroup group = new RelocationGroup(source.valueVersion(), target, anchor, scope);
					uses.computeIfAbsent(group, ignored -> new ArrayList<>())
						.add(new InputUse(consumer.key(), edge.inputPosition(), scope));
				}
			}
		}
		List<NeutralPlacementGraph.RelocationAction> result = new ArrayList<>();
		for(Map.Entry<RelocationGroup,List<InputUse>> entry : uses.entrySet()) {
			RelocationGroup group = entry.getKey();
			Set<CompiledHopKey> consumerSet = new java.util.TreeSet<>();
			for(InputUse use : entry.getValue()) consumerSet.add(use.consumer());
			List<CompiledHopKey> consumers = new ArrayList<>(consumerSet);
			RelocationActionKey key = new RelocationActionKey(group.source(), group.target(), group.anchor(),
				group.scope(), consumers);
			List<ObligationKey> obligations = new ArrayList<>();
			for(InputUse use : entry.getValue()) obligations.add(new ObligationKey(use.consumer(), use.position(),
				group.source(), group.target(), key, use.scope()));
			result.add(new NeutralPlacementGraph.RelocationAction(key, obligations));
		}
		return result;
	}

	private record RelocationGroup(ValueVersionKey source, PlacementState target,
		DurableAnchorKey anchor, String scope) implements Comparable<RelocationGroup> {
		@Override
		public int compareTo(RelocationGroup that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}

		private String normalizedSignature() {
			return source.normalizedSignature() + '|' + target.normalizedSignature() + '|'
				+ anchor.normalizedSignature() + '|' + scope;
		}
	}

	private record InputUse(CompiledHopKey consumer, int position, String scope) { }

	private static List<List<FType>> inputDomains(Hop hop, Map<Hop,Node> nodesByHop,
		PlacementGraphFingerprint.HopOccurrence occurrence,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, VersionKind versionKind) {
		if(versionKind == VersionKind.FUNCTION_INPUT) {
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
					Node argument = nodesByHop.get(call.getInput(i));
					if(argument == null) continue;
					for(PlacementState state : argument.legalAlternatives()) {
						if(state.fType() == null) local = true;
						else callTypes.add(state.fType());
					}
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
					if(state.fType() == null) local = true;
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

	private static FType firstFType(List<FType> values) { return values.isEmpty() ? null : values.get(0); }
	private static boolean requiresRecompileMetadata(Hop h) { return h.requiresRecompile(); }
	private static boolean isLegalTransient(PlacementState s) {
		return (s.execType() == ExecType.CP && s.output() == FederatedOutput.LOUT)
			|| (s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT);
	}
	private static boolean isTransientRead(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTREAD; }
	private static boolean isTransientWrite(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTWRITE; }
	private static boolean isFunctionOutput(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.FUNCTIONOUTPUT; }
	private static String lexicalVariable(Hop h, int ordinal) {
		return h instanceof DataOp && h.getName() != null && !h.getName().isBlank() ? h.getName() : "value-" + ordinal;
	}
	private static NodeKind nodeKind(Hop h, ValueVersionKey value) {
		if(value.versionKind() == VersionKind.CLONE_RECOMPILE) return NodeKind.CLONE;
		if(value.versionKind() == VersionKind.FUNCTION_INPUT) return NodeKind.FUNCTION_INPUT;
		if(value.versionKind() == VersionKind.LOOP_HEAD_PHI || value.versionKind() == VersionKind.LOOP_BACKEDGE)
			return NodeKind.LOOP_PHI;
		if(value.versionKind() == VersionKind.BRANCH_JOIN_PHI) return NodeKind.BRANCH_JOIN;
		if(isTransientRead(h)) return NodeKind.TRANSIENT_READ;
		if(isTransientWrite(h)) return NodeKind.TRANSIENT_WRITE;
		if(isFunctionOutput(h)) return NodeKind.TRANSIENT_WRITE;
		if(h instanceof FunctionOp) return NodeKind.FUNCTION_CALL;
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

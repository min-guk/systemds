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
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicNativeContinuationFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathwiseReentryFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientCompatibilityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
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
	private final FunctionCallGraph suppliedFunctionCallGraph;
	private final FunctionCallSizeInfo suppliedFunctionCallSizes;
	private final FixedPointObserver fixedPointObserver;
	private final SearchSpaceMetrics complexityMetrics;
	private final boolean incrementalDirectClosure;
	private final List<CandidatePrivacyClosureEvidence.Pass> candidatePrivacyEvidence = new ArrayList<>();

	interface FixedPointObserver {
		void accept(FixedPointPass pass);
	}

	record FixedPointPass(String phase, int pass, int passLimit, boolean stable,
		int nodeCount, int candidateCount, int logicalInputCount, int actionCount) { }

	public NeutralPlacementGraphBuilder() {
		this(null, null, null, null, true);
	}

	public NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		this(fgraph, fcallSizes, null, null, true);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver) {
		this(null, null, fixedPointObserver, null, true);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver,
		SearchSpaceMetrics complexityMetrics) {
		this(null, null, fixedPointObserver, complexityMetrics, true);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver,
		SearchSpaceMetrics complexityMetrics, boolean incrementalDirectClosure) {
		this(null, null, fixedPointObserver, complexityMetrics, incrementalDirectClosure);
	}

	private NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes,
		FixedPointObserver fixedPointObserver, SearchSpaceMetrics complexityMetrics,
		boolean incrementalDirectClosure) {
		if((fgraph == null) != (fcallSizes == null))
			throw new IllegalArgumentException("Function graph and call-size summary must be supplied together");
		suppliedFunctionCallGraph = fgraph;
		suppliedFunctionCallSizes = fcallSizes;
		this.fixedPointObserver = fixedPointObserver;
		this.complexityMetrics = complexityMetrics;
		this.incrementalDirectClosure = incrementalDirectClosure;
	}

	private void recordFixedPointPass(String phase, int pass, int passLimit, boolean stable,
		List<Node> nodes, List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logicalInputs,
		List<NeutralPlacementGraph.RelocationAction> actions) {
		if(fixedPointObserver != null)
			fixedPointObserver.accept(new FixedPointPass(phase, pass, passLimit, stable,
				nodes.size(), facts.size(), logicalInputs.size(), actions.size()));
		if(complexityMetrics != null)
			complexityMetrics.recordFixedPointPass(phase);
	}

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
		candidatePrivacyEvidence.clear();
		if(complexityMetrics != null)
			complexityMetrics.reset();
		SearchSpaceMetrics.PhaseToken analysisStarted = complexityMetrics == null ? null
			: complexityMetrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.beginAnalysisScope(complexityMetrics);
		try {
			return buildDetachedAnalysisScoped(program);
		}
		finally {
			try {
				PlacementIdentity.endAnalysisScope();
			}
			finally {
				if(complexityMetrics != null)
					complexityMetrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, analysisStarted);
			}
		}
	}

	private PlacementAnalysis buildDetachedAnalysisScoped(DMLProgram program) {
		FunctionCallGraph fgraph = suppliedFunctionCallGraph != null
			? suppliedFunctionCallGraph : new FunctionCallGraph(program);
		FunctionCallSizeInfo fcallSizes = suppliedFunctionCallSizes != null
			? suppliedFunctionCallSizes : new FunctionCallSizeInfo(fgraph);
		String before = PlacementGraphFingerprint.capture(program);
		String registryBefore = registrySentinel(program);
		List<StatementBlock> topLevelStatementBlocks = List.copyOf(program.getStatementBlocks());
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = PlacementGraphFingerprint.orderedOccurrences(program);
		Map<Hop,NodeShapeFact> concreteShapes = new IdentityHashMap<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences)
			concreteShapes.put(occurrence.hop(), deriveNodeShapeFact(occurrence.hop()));
		String programId = structuralFingerprint(occurrences);
		CfgAnalysis conservativeCfg = analyzeCfg(program, topLevelStatementBlocks, occurrences, Map.of());
		CfgAnalysis cfg = conservativeCfg;
		PlacementAbstractShapeAnalysis.HopFacts preliminaryAbstractFacts = null;
		int maxCfgRefinementPasses = Math.max(8, occurrences.size() + 1);
		boolean cfgRefinementConverged = false;
		for(int pass = 0; pass < maxCfgRefinementPasses; pass++) {
			preliminaryAbstractFacts = PlacementAbstractShapeAnalysis.inferOriginalOccurrences(
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::namespace).toList(),
				cfg.reachingDefinitions(), cfg.reachingFunctionInputs(), fgraph, fcallSizes, concreteShapes);
			CfgAnalysis refined = analyzeCfg(program, topLevelStatementBlocks, occurrences,
				preliminaryAbstractFacts.scalars());
			boolean stable = refined.equals(cfg);
			if(fixedPointObserver != null)
				fixedPointObserver.accept(new FixedPointPass("cfg-refinement", pass, maxCfgRefinementPasses,
					stable, occurrences.size(), 0, 0, 0));
			if(complexityMetrics != null)
				complexityMetrics.recordFixedPointPass("cfg-refinement");
			if(stable) {
				cfgRefinementConverged = true;
				break;
			}
			cfg = refined;
		}
		if(!cfgRefinementConverged) {
			// Branch refinement is an optional precision improvement. Planning must remain
			// fail-closed if a future transfer function breaks monotonicity: retain the
			// conservative all-branches CFG instead of rejecting a legal program or pruning
			// a reachable branch.
			cfg = conservativeCfg;
			preliminaryAbstractFacts = PlacementAbstractShapeAnalysis.inferOriginalOccurrences(
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::namespace).toList(),
				cfg.reachingDefinitions(), cfg.reachingFunctionInputs(), fgraph, fcallSizes, concreteShapes);
		}
		Set<Hop> unresolvedValueSources = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++)
			if(cfg.reachingFunctionInputs().get(ordinal)
				|| !cfg.reachingFunctionOutputDefinitions().get(ordinal).isEmpty())
				unresolvedValueSources.add(occurrences.get(ordinal).hop());
		// The Hop-keyed abstract source relation cannot distinguish the expanded
		// function-boundary occurrences. A partial union is not a FULL cardinality
		// certificate; those reads remain UNKNOWN until the occurrence closure below.
		SinglePartitionFacts singlePartitions = new SinglePartitionFacts(
			occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
			preliminaryAbstractFacts.valueSources(), unresolvedValueSources);
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
			AbstractShapeFact abstractShapeFact = preliminaryAbstractFacts.shapes().get(hop);
			if(abstractShapeFact == null)
				throw new IllegalStateException("Placement occurrence has no preliminary abstract shape: " + key);
			// Publish only dimensions proven at the shared fixed point. A loop's initial
			// concrete extent is not the extent of every later version of that value.
			NodeShapeFact shapeFact = new NodeShapeFact(hop.getDataType(),
				abstractShapeFact.rows().isExact() ? abstractShapeFact.rows().value() : -1,
				abstractShapeFact.cols().isExact() ? abstractShapeFact.cols().value() : -1);
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
				: inheritableDurableAnchor(hop, key.normalizedSignature(), shapeFact, inputShapeFacts, inputAnchors);
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
				Collections.unmodifiableList(new ArrayList<>(inputAnchorOwners)), shapeFact, abstractShapeFact, singlePartitions,
				inputShapeFacts,
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
		CfgReplayBaseline cfgReplayBaseline = cfgReplayBaseline(nodes,
			candidateRuleDomainKeys, candidateRuleFacts);
		LoopSeedLedger loopSeedLedger = new LoopSeedLedger(new LinkedHashMap<>());
		CandidateReplay candidateReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
			factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
			candidateRuleDomainKeys, candidateRuleFacts, List.of(), cfgReplayBaseline, origins, List.of(),
			loopSeedLedger);
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
		singlePartitions = singlePartitions.closeOccurrences(nodes, origins, compiledInputEdges, constraints);
		List<Integer> cardinalityReplayOrdinals =
			singlePartitions.changedOccurrenceOrdinals(nodes, occurrences.size(), origins);
		CandidateReplay cardinalityReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
			new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
				logicalTransientInputs, cardinalityReplayOrdinals), factsByHop,
			preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg,
			compiledInputEdges, origins, concreteShapes);
		nodes = cardinalityReplay.nodes();
		candidateRuleDomainKeys = cardinalityReplay.domainKeys();
		candidateRuleFacts = cardinalityReplay.facts();
		logicalTransientInputs = cardinalityReplay.logicalInputs();
		CandidateReplay materializationReplay = closeWorkerPoolMaterializationDependencies(
			occurrences, nodes, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
			compiledInputEdges, constraints, origins, factsByHop, concreteShapes, preliminaryAbstractFacts.shapes(), singlePartitions,
			ordinalsByBlock, cfg);
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
			List<LogicalTransientInputFact> passLogicalInputs = logicalTransientInputs;
			FunctionInputCandidateClosure functionInputClosure = closeLogicalFunctionInputCandidates(
				nodes, candidateRuleDomainKeys, candidateRuleFacts, functionExpansion.constraints(), origins,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, occurrences.size());
			nodes = functionInputClosure.nodes();
			candidateRuleDomainKeys = functionInputClosure.domainKeys();
			candidateRuleFacts = functionInputClosure.facts();
			if(!functionInputClosure.changedOrdinals().isEmpty()) {
				CandidateReplay functionReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
						logicalTransientInputs, functionInputClosure.changedOrdinals()),
					factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg,
					compiledInputEdges, origins, concreteShapes);
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, functionReplay.nodes(), functionReplay.domainKeys(), functionReplay.facts(),
					functionReplay.logicalInputs(), compiledInputEdges, constraints, origins, factsByHop, concreteShapes,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg);
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
				origins, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions);
			nodes = functionOutputClosure.nodes();
			candidateRuleDomainKeys = functionOutputClosure.domainKeys();
			candidateRuleFacts = functionOutputClosure.facts();
			if(!functionOutputClosure.changedOrdinals().isEmpty()) {
				CandidateReplay functionReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
						logicalTransientInputs, functionOutputClosure.changedOrdinals()),
					factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg,
					compiledInputEdges, origins, concreteShapes);
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, functionReplay.nodes(), functionReplay.domainKeys(), functionReplay.facts(),
					functionReplay.logicalInputs(), compiledInputEdges, constraints, origins, factsByHop, concreteShapes,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg);
				nodes = materializationReplay.nodes();
				candidateRuleDomainKeys = materializationReplay.domainKeys();
				candidateRuleFacts = materializationReplay.facts();
				logicalTransientInputs = materializationReplay.logicalInputs();
			}
			nodes = refreshFunctionOutputBoundaryAlternatives(nodes, functionExpansion.constraints());
			// Function-boundary closure can widen a TWrite only after the initial CFG
			// replay has visited a downstream branch/loop TRead. Re-run the exact CFG
			// transfer inside the same fixed point so newly common FED/FOUT tuples reach
			// those reads before privacy filtering. Replay replaces each prior read fact
			// and row, so later source-domain widening cannot leave stale authority.
			CandidateReplay cfgReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
			candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
			origins, constraints, compiledInputEdges, concreteShapes, List.of(), loopSeedLedger);
			nodes = cfgReplay.nodes();
			candidateRuleDomainKeys = cfgReplay.domainKeys();
			candidateRuleFacts = cfgReplay.facts();
			logicalTransientInputs = cfgReplay.logicalInputs();
			if(!cfgReplay.changedOrdinals().isEmpty()) {
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, nodes, candidateRuleDomainKeys, candidateRuleFacts,
					logicalTransientInputs, compiledInputEdges, constraints, origins, factsByHop, concreteShapes,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg);
				nodes = materializationReplay.nodes();
				candidateRuleDomainKeys = materializationReplay.domainKeys();
				candidateRuleFacts = materializationReplay.facts();
				logicalTransientInputs = materializationReplay.logicalInputs();
			}
			boolean stable = nodes.equals(passNodes) && candidateRuleDomainKeys.equals(passDomainKeys)
				&& candidateRuleFacts.equals(passFacts)
				&& logicalTransientInputs.equals(passLogicalInputs);
			recordFixedPointPass("function-boundary", pass, maxFunctionClosurePasses, stable,
				nodes, candidateRuleFacts, logicalTransientInputs, List.of());
			if(stable) {
				functionClosureConverged = true;
				break;
			}
		}
		if(!functionClosureConverged)
			throw new IllegalStateException("Logical function boundary candidate closure did not converge");
		CandidateReplay finalLatentWdivmmClosure = closeLatentWdivmmRuntimeOutputContracts(
			new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
				logicalTransientInputs, List.of()), compiledInputEdges, origins, factsByHop, concreteShapes);
		nodes = finalLatentWdivmmClosure.nodes();
		candidateRuleFacts = finalLatentWdivmmClosure.facts();
		List<Node> prePrivacyNodes = PlannerCandidateSpaceAudit.isEnabled()
			? List.copyOf(nodes) : List.of();
		List<CandidateRuleFact> prePrivacyCandidateRuleFacts = PlannerCandidateSpaceAudit.isEnabled()
			? List.copyOf(candidateRuleFacts) : List.of();
		PrivacyClosure privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
			constraints, origins, compiledInputEdges);
		nodes = privacyClosure.nodes();
		candidateRuleFacts = privacyClosure.candidateRuleFacts();
		candidateRuleFacts = bindExactCandidateEmissionRealizations(candidateRuleFacts, nodes, origins, factsByHop);
		logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
		// Privacy can remove one realization without changing its coarse FType. Re-run
		// the same shared CFG/physical closure over the proof-carrying relation so stale
		// compatibility edges and downstream candidates cannot survive filtering.
		CandidateReplay postPrivacyReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
			factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
			candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline, origins, constraints,
			compiledInputEdges, concreteShapes, List.of(), loopSeedLedger);
		nodes = postPrivacyReplay.nodes();
		candidateRuleDomainKeys = postPrivacyReplay.domainKeys();
		candidateRuleFacts = postPrivacyReplay.facts();
		logicalTransientInputs = postPrivacyReplay.logicalInputs();
		if(!postPrivacyReplay.changedOrdinals().isEmpty()) {
			CandidateReplay postPrivacyPhysical = closePostCfgPhysicalCandidateDependencies(occurrences,
				postPrivacyReplay, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
				ordinalsByBlock, cfg, compiledInputEdges, origins, concreteShapes);
			nodes = postPrivacyPhysical.nodes();
			candidateRuleDomainKeys = postPrivacyPhysical.domainKeys();
			candidateRuleFacts = postPrivacyPhysical.facts();
			logicalTransientInputs = postPrivacyPhysical.logicalInputs();
		}
		privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
			constraints, origins, compiledInputEdges, privacyClosure.privacyFacts());
		nodes = privacyClosure.nodes();
		candidateRuleFacts = privacyClosure.candidateRuleFacts();
		PlacementPrivacyFacts privacyFacts = privacyClosure.privacyFacts();
		candidateRuleFacts = bindExactCandidateEmissionRealizations(candidateRuleFacts, nodes, origins, factsByHop);
		candidateRuleFacts = bindExactDerivedFoutAuthorities(candidateRuleFacts, scopes, nodes, origins);
		candidateRuleFacts = bindDerivedFoutRealizations(candidateRuleFacts, origins, factsByHop);
		logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
		// TRead/TWrite is a planner-wide legality boundary, not an Exact-only factor:
		// the runtime accepts only the exact CP/LOUT or FED/FOUT tuple carried by
		// the unique logical source. Publishing it in the neutral graph prevents
		// FedAll/Heuristic from selecting a mismatched read/write pair.
		for(LogicalTransientInputFact input : logicalTransientInputs)
			constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
				input.sourceWrite(), input.targetRead(), input.logicalPosition(),
				"logical-transient-input"));
		List<NeutralPlacementGraph.RelocationAction> relocations = List.of();
		boolean semanticClosureConverged = false;
		int semanticPassLimit = Math.max(2, candidateRuleFacts.size() + nodes.size() + 1);
		for(int pass = 0; pass < semanticPassLimit; pass++) {
			List<Node> priorNodes = nodes;
			List<CandidateRuleKey> priorDomain = candidateRuleDomainKeys;
			List<CandidateRuleFact> priorFacts = candidateRuleFacts;
			List<LogicalTransientInputFact> priorLogical = logicalTransientInputs;
			List<NeutralPlacementGraph.RelocationAction> priorActions = relocations;
			candidateRuleFacts = bindExactCandidateEmissionRealizations(candidateRuleFacts, nodes, origins, factsByHop);
			candidateRuleFacts = bindExactDerivedFoutAuthorities(candidateRuleFacts, scopes, nodes, origins);
			candidateRuleFacts = bindDerivedFoutRealizations(candidateRuleFacts, origins, factsByHop);
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
			relocations = canonicalRelocationActions(relocations(compiledInputEdges, candidateRuleFacts, nodes,
				logicalTransientInputs, constraints, origins, scopes, factsByHop, concreteShapes,
				privacyFacts.asMap()), priorActions);
			candidateRuleFacts = bindRelocationCandidateRealizations(candidateRuleFacts, nodes,
				compiledInputEdges, relocations, origins, factsByHop);
			CandidateReplay semanticReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
				candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
				origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
			nodes = semanticReplay.nodes();
			candidateRuleDomainKeys = semanticReplay.domainKeys();
			candidateRuleFacts = semanticReplay.facts();
			logicalTransientInputs = semanticReplay.logicalInputs();
			privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
				constraints, origins, compiledInputEdges, privacyFacts);
			nodes = privacyClosure.nodes();
			candidateRuleFacts = privacyClosure.candidateRuleFacts();
			candidateRuleFacts = bindExactCandidateEmissionRealizations(candidateRuleFacts, nodes, origins, factsByHop);
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
			List<NeutralPlacementGraph.RelocationAction> closedActions = canonicalRelocationActions(
				relocations(compiledInputEdges,
				candidateRuleFacts, nodes, logicalTransientInputs, constraints, origins, scopes, factsByHop,
				concreteShapes, privacyFacts.asMap()), relocations);
			boolean stable = nodes.equals(priorNodes) && candidateRuleDomainKeys.equals(priorDomain)
				&& candidateRuleFacts.equals(priorFacts) && logicalTransientInputs.equals(priorLogical)
				&& closedActions.equals(priorActions);
			recordFixedPointPass("semantic", pass, semanticPassLimit, stable,
				nodes, candidateRuleFacts, logicalTransientInputs, closedActions);
			if(stable) {
				relocations = closedActions;
				semanticClosureConverged = true;
				break;
			}
			relocations = closedActions;
		}
		if(!semanticClosureConverged)
			throw new IllegalStateException("Placement proof/layout/action closure did not converge");
		Set<CompiledHopKey> requiredEmittedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : nodes)
			if(node.emittedWork())
				requiredEmittedNodes.add(node.key());
		boolean publicationConverged = false;
		for(int pass = 0; pass < semanticPassLimit; pass++) {
			List<Node> priorNodes = nodes;
			List<CandidateRuleKey> priorDomain = candidateRuleDomainKeys;
			List<CandidateRuleFact> priorFacts = candidateRuleFacts;
			List<LogicalTransientInputFact> priorLogical = logicalTransientInputs;
			List<NeutralPlacementGraph.RelocationAction> priorActions = relocations;
			// A prior physical rebuild intentionally restores oracle-owned base emissions,
			// including staging native lineages. Re-run the exact CFG/direct-realization
			// closure before pruning staging authority; otherwise a rebuilt TWrite or
			// function result can be marked PROFILE_ERROR before its input witness is rebound.
			CandidateReplay groundedPublication = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
				candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
				origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
			nodes = groundedPublication.nodes();
			candidateRuleDomainKeys = groundedPublication.domainKeys();
			candidateRuleFacts = groundedPublication.facts();
			logicalTransientInputs = groundedPublication.logicalInputs();
			// A formal TRead has no physical Hop input and CFG replay intentionally
			// leaves function inputs untouched. Recompose the function-input transfer
			// before pruning executable realizations, then ground the rebuilt rows in
			// the same publication pass.
			FunctionInputCandidateClosure publicationFunctionInputs =
				closeLogicalFunctionInputCandidates(nodes, candidateRuleDomainKeys,
					candidateRuleFacts, functionExpansion.constraints(), origins, factsByHop,
					preliminaryAbstractFacts.shapes(), singlePartitions, occurrences.size());
			nodes = publicationFunctionInputs.nodes();
			candidateRuleDomainKeys = publicationFunctionInputs.domainKeys();
			candidateRuleFacts = publicationFunctionInputs.facts();
			if(!publicationFunctionInputs.changedOrdinals().isEmpty()) {
				CandidateReplay functionPhysical = closePostCfgPhysicalCandidateDependencies(
					occurrences, new CandidateReplay(nodes, candidateRuleDomainKeys,
						candidateRuleFacts, logicalTransientInputs,
						publicationFunctionInputs.changedOrdinals()), factsByHop,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg,
					compiledInputEdges, origins, concreteShapes);
				materializationReplay = closeWorkerPoolMaterializationDependencies(
					occurrences, functionPhysical.nodes(), functionPhysical.domainKeys(),
					functionPhysical.facts(), functionPhysical.logicalInputs(), compiledInputEdges,
					constraints, origins, factsByHop, concreteShapes,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg);
				nodes = materializationReplay.nodes();
				candidateRuleDomainKeys = materializationReplay.domainKeys();
				candidateRuleFacts = materializationReplay.facts();
				logicalTransientInputs = materializationReplay.logicalInputs();
				groundedPublication = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
					factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
					candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
					cfgReplayBaseline, origins, constraints, compiledInputEdges, concreteShapes,
					relocations, loopSeedLedger);
				nodes = groundedPublication.nodes();
				candidateRuleDomainKeys = groundedPublication.domainKeys();
				candidateRuleFacts = groundedPublication.facts();
				logicalTransientInputs = groundedPublication.logicalInputs();
			}
			candidateRuleFacts = removeUngroundedStagingRealizations(candidateRuleFacts);
			ExecutableNodeProjection projected = projectCandidateNodesToExecutableStates(
				nodes, candidateRuleFacts, occurrences.size());
			nodes = projected.nodes();
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
			if(!projected.changedOrdinals().isEmpty()) {
				CandidateReplay projectedReplay = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
						projected.changedOrdinals()), factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
					ordinalsByBlock, cfg, compiledInputEdges, origins, concreteShapes);
				nodes = projectedReplay.nodes();
				candidateRuleDomainKeys = projectedReplay.domainKeys();
				candidateRuleFacts = projectedReplay.facts();
				logicalTransientInputs = projectedReplay.logicalInputs();
			}
			CandidateReplay publicationReplay = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
				candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
				origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
			nodes = publicationReplay.nodes();
			candidateRuleDomainKeys = publicationReplay.domainKeys();
			candidateRuleFacts = publicationReplay.facts();
			logicalTransientInputs = publicationReplay.logicalInputs();
			List<Node> prePublicationPrivacyNodes = nodes;
			privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
				constraints, origins, compiledInputEdges, privacyFacts);
			nodes = privacyClosure.nodes();
			candidateRuleFacts = privacyClosure.candidateRuleFacts();
			List<Integer> privacyChanged = changedCompiledNodeOrdinals(
				prePublicationPrivacyNodes, nodes, occurrences.size());
			if(!privacyChanged.isEmpty()) {
				CandidateReplay privacyPhysical = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
						privacyChanged), factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
					ordinalsByBlock, cfg, compiledInputEdges, origins, concreteShapes);
				nodes = privacyPhysical.nodes();
				candidateRuleDomainKeys = privacyPhysical.domainKeys();
				candidateRuleFacts = privacyPhysical.facts();
				logicalTransientInputs = privacyPhysical.logicalInputs();

				// Physical rebuilding restores oracle-owned base emissions. Ground those
				// emissions against the exact CFG/native witnesses before publication
				// pruning, then reapply the already-fixed privacy authority.
				CandidateReplay privacyGrounded = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
					factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
					candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
					origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
				nodes = privacyGrounded.nodes();
				candidateRuleDomainKeys = privacyGrounded.domainKeys();
				candidateRuleFacts = privacyGrounded.facts();
				logicalTransientInputs = privacyGrounded.logicalInputs();
				privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
					constraints, origins, compiledInputEdges, privacyFacts);
				nodes = privacyClosure.nodes();
				candidateRuleFacts = privacyClosure.candidateRuleFacts();
			}
			candidateRuleFacts = bindExactCandidateEmissionRealizations(candidateRuleFacts, nodes, origins, factsByHop);
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
			relocations = canonicalRelocationActions(relocations(compiledInputEdges, candidateRuleFacts, nodes,
				logicalTransientInputs, constraints, origins, scopes, factsByHop, concreteShapes,
				privacyFacts.asMap()), priorActions);
			candidateRuleFacts = bindRelocationCandidateRealizations(candidateRuleFacts, nodes,
				compiledInputEdges, relocations, origins, factsByHop);
			// Relocation binding can replace realization identities after the preceding
			// transient relation was built. Reclose the same exact CFG/native authority
			// before pruning so logical transient edges reference the realizations that
			// are actually being published.
			CandidateReplay relocationGrounded = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
				factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
				candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
				origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
			nodes = relocationGrounded.nodes();
			candidateRuleDomainKeys = relocationGrounded.domainKeys();
			candidateRuleFacts = relocationGrounded.facts();
			logicalTransientInputs = relocationGrounded.logicalInputs();
			List<Node> beforePrivacyNodes = nodes;
			List<CandidateRuleFact> beforePrivacyFacts = candidateRuleFacts;
			privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
				constraints, origins, compiledInputEdges, privacyFacts);
			nodes = privacyClosure.nodes();
			candidateRuleFacts = privacyClosure.candidateRuleFacts();
			// Privacy may withdraw a row after CFG/direct closure bound a consumer
			// to that exact identity. Reprove from the surviving rows until the
			// privacy-filtered relation stabilizes before pruning expired support.
			// Never remap an expired reference or restore withdrawn authority.
			List<CandidateReplay> privacySeen = new ArrayList<>();
			int maxPrivacyPasses = Math.max(1,
				nodes.size() + candidateRuleDomainKeys.size() + logicalTransientInputs.size() + 1);
			// relocationGrounded is already the completed composed CFG/direct/physical
			// fixed point. If privacy removed neither a node nor an exact fact, repeating
			// that entire transfer cannot repair any withdrawn authority.
			boolean privacyGroundedConverged = nodes.equals(beforePrivacyNodes)
				&& candidateRuleFacts.equals(beforePrivacyFacts);
			for(int privacyPass = 0; !privacyGroundedConverged && privacyPass < maxPrivacyPasses; privacyPass++) {
				CandidateReplay beforePrivacyGrounding = new CandidateReplay(nodes, candidateRuleDomainKeys,
					candidateRuleFacts, logicalTransientInputs, List.of());
				if(privacySeen.contains(beforePrivacyGrounding))
					throw new IllegalStateException("Privacy-filtered candidate proof closure cycled");
				privacySeen.add(beforePrivacyGrounding);
				CandidateReplay privacyGrounded = closeCfgTransientCandidateDependencies(occurrences,
					nodes, cfg, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
					candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
					origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
				nodes = privacyGrounded.nodes();
				candidateRuleDomainKeys = privacyGrounded.domainKeys();
				candidateRuleFacts = privacyGrounded.facts();
				logicalTransientInputs = privacyGrounded.logicalInputs();
				privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
					constraints, origins, compiledInputEdges, privacyFacts);
				nodes = privacyClosure.nodes();
				candidateRuleFacts = privacyClosure.candidateRuleFacts();
				if(beforePrivacyGrounding.equals(new CandidateReplay(nodes, candidateRuleDomainKeys,
					candidateRuleFacts, logicalTransientInputs, List.of()))) {
					privacyGroundedConverged = true;
					break;
				}
			}
			if(!privacyGroundedConverged)
				throw new IllegalStateException("Privacy-filtered candidate proof closure did not converge");
			candidateRuleFacts = removeUngroundedStagingRealizations(candidateRuleFacts);
			ExecutableNodeProjection finalProjection = projectCandidateNodesToExecutableStates(
				nodes, candidateRuleFacts, occurrences.size());
			nodes = finalProjection.nodes();
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
			if(!finalProjection.changedOrdinals().isEmpty()) {
				CandidateReplay finalPhysical = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
						finalProjection.changedOrdinals()), factsByHop, preliminaryAbstractFacts.shapes(),
					singlePartitions, ordinalsByBlock, cfg, compiledInputEdges, origins, concreteShapes);
				nodes = finalPhysical.nodes();
				candidateRuleDomainKeys = finalPhysical.domainKeys();
				candidateRuleFacts = finalPhysical.facts();
				logicalTransientInputs = finalPhysical.logicalInputs();
				// Do not compare publication convergence at the raw physical midpoint.
				// Rebuild restores base emissions and can invalidate exact realization
				// identities; complete the same grounding/privacy/publication transfer
				// before the outer fixed point is allowed to converge.
				CandidateReplay finalGrounded = closeCfgTransientCandidateDependencies(occurrences, nodes, cfg,
					factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock,
					candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs, cfgReplayBaseline,
					origins, constraints, compiledInputEdges, concreteShapes, relocations, loopSeedLedger);
				nodes = finalGrounded.nodes();
				candidateRuleDomainKeys = finalGrounded.domainKeys();
				candidateRuleFacts = finalGrounded.facts();
				logicalTransientInputs = finalGrounded.logicalInputs();
				privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
					constraints, origins, compiledInputEdges, privacyFacts);
				nodes = privacyClosure.nodes();
				candidateRuleFacts = privacyClosure.candidateRuleFacts();
				candidateRuleFacts = removeUngroundedStagingRealizations(candidateRuleFacts);
				logicalTransientInputs = bindExactLogicalTransientSourceStates(
					logicalTransientInputs, candidateRuleFacts);
				ExecutableNodeProjection groundedProjection = projectCandidateNodesToExecutableStates(
					nodes, candidateRuleFacts, occurrences.size());
				nodes = groundedProjection.nodes();
				logicalTransientInputs = bindExactLogicalTransientSourceStates(
					logicalTransientInputs, candidateRuleFacts);
			}
			List<NeutralPlacementGraph.RelocationAction> reboundActions = canonicalRelocationActions(
				relocations(compiledInputEdges,
				candidateRuleFacts, nodes, logicalTransientInputs, constraints, origins, scopes, factsByHop,
				concreteShapes, privacyFacts.asMap()), relocations);
			// A final physical/CFG replay may withdraw an action without withdrawing its
			// earlier realization binding. Rebuild those clauses from the newly closed
			// action domain before testing convergence; publishing the old action would
			// grant authority that the final graph no longer owns.
			if(candidateRuleFacts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.flatMap(emission -> emission.realizations().stream())
				.flatMap(realization -> realization.supportClauses().stream())
				.flatMap(clause -> clause.inputBindings().stream())
				.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.RELOCATION
					&& reboundActions.stream().noneMatch(action -> action.key() == binding.relocationAction()))) {
				candidateRuleFacts = bindRelocationCandidateRealizations(candidateRuleFacts, nodes,
					compiledInputEdges, reboundActions, origins, factsByHop);
				logicalTransientInputs = bindExactLogicalTransientSourceStates(
					logicalTransientInputs, candidateRuleFacts);
			}
			// Final pruning and action rebinding can replace exact source realizations
			// without changing a node's coarse placement projection. Recompute the
			// reader relation from the realizations that this pass will publish.
			CandidateReplay exactPublication = closeCfgTransientCandidateDependencies(occurrences,
				nodes, cfg, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
				ordinalsByBlock, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
				cfgReplayBaseline, origins, constraints, compiledInputEdges, concreteShapes,
				reboundActions, loopSeedLedger);
			nodes = exactPublication.nodes();
			candidateRuleDomainKeys = exactPublication.domainKeys();
			candidateRuleFacts = exactPublication.facts();
			logicalTransientInputs = exactPublication.logicalInputs();
			privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
				constraints, origins, compiledInputEdges, privacyFacts);
			nodes = privacyClosure.nodes();
			candidateRuleFacts = privacyClosure.candidateRuleFacts();
			candidateRuleFacts = removeUngroundedStagingRealizations(candidateRuleFacts);
			logicalTransientInputs = bindExactLogicalTransientSourceStates(
				logicalTransientInputs, candidateRuleFacts);
			List<NeutralPlacementGraph.RelocationAction> boundActions = reboundActions;
			List<NeutralPlacementGraph.RelocationAction> publishedActions = canonicalRelocationActions(
				relocations(compiledInputEdges, candidateRuleFacts, nodes, logicalTransientInputs,
					constraints, origins, scopes, factsByHop, concreteShapes, privacyFacts.asMap()),
				boundActions);
			// Rebinding a changed action can itself change the exact source/reader
			// relation and therefore the set of relocation obligations. Close the
			// composed transfer before accepting graph-owned action references.
			List<List<?>> actionSeen = new ArrayList<>();
			for(int actionPass = 0; !publishedActions.equals(boundActions)
				&& actionPass < semanticPassLimit; actionPass++) {
				List<?> actionState = List.of(nodes, candidateRuleDomainKeys, candidateRuleFacts,
					logicalTransientInputs, publishedActions);
				if(actionSeen.contains(actionState))
					throw new IllegalStateException("Final publication action/realization closure cycled");
				actionSeen.add(actionState);
				boundActions = publishedActions;
				candidateRuleFacts = bindRelocationCandidateRealizations(candidateRuleFacts, nodes,
					compiledInputEdges, boundActions, origins, factsByHop);
				CandidateReplay actionGrounded = closeCfgTransientCandidateDependencies(occurrences,
					nodes, cfg, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
					ordinalsByBlock, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
					cfgReplayBaseline, origins, constraints, compiledInputEdges, concreteShapes,
					boundActions, loopSeedLedger);
				nodes = actionGrounded.nodes();
				candidateRuleDomainKeys = actionGrounded.domainKeys();
				candidateRuleFacts = actionGrounded.facts();
				logicalTransientInputs = actionGrounded.logicalInputs();
				privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
					constraints, origins, compiledInputEdges, privacyFacts);
				nodes = privacyClosure.nodes();
				candidateRuleFacts = removeUngroundedStagingRealizations(privacyClosure.candidateRuleFacts());
				logicalTransientInputs = bindExactLogicalTransientSourceStates(
					logicalTransientInputs, candidateRuleFacts);
				publishedActions = canonicalRelocationActions(
					relocations(compiledInputEdges, candidateRuleFacts, nodes, logicalTransientInputs,
						constraints, origins, scopes, factsByHop, concreteShapes, privacyFacts.asMap()),
					boundActions);
			}
			if(!publishedActions.equals(boundActions))
				throw new IllegalStateException("Final publication action/realization closure did not converge");
			// Action rebinding replays oracle-owned node domains. It can restore a
			// placement after its last executable candidate realization disappeared
			// (notably FED/FOUT on a logical function call). Project once more from
			// the exact rows that will be published and reclose affected consumers.
			ExecutableNodeProjection actionProjection = projectCandidateNodesToExecutableStates(
				nodes, candidateRuleFacts, occurrences.size());
			nodes = actionProjection.nodes();
			if(!actionProjection.changedOrdinals().isEmpty()) {
				CandidateReplay projectedPhysical = closePostCfgPhysicalCandidateDependencies(occurrences,
					new CandidateReplay(nodes, candidateRuleDomainKeys, candidateRuleFacts,
						logicalTransientInputs, actionProjection.changedOrdinals()), factsByHop,
					preliminaryAbstractFacts.shapes(), singlePartitions, ordinalsByBlock, cfg,
					compiledInputEdges, origins, concreteShapes);
				nodes = projectedPhysical.nodes();
				candidateRuleDomainKeys = projectedPhysical.domainKeys();
				candidateRuleFacts = projectedPhysical.facts();
				logicalTransientInputs = projectedPhysical.logicalInputs();
				// A physical rebuild may restore base emissions, so finish its exact
				// grounding and privacy transfer before comparing whole-pass states.
				CandidateReplay projectedGrounded = closeCfgTransientCandidateDependencies(occurrences,
					nodes, cfg, factsByHop, preliminaryAbstractFacts.shapes(), singlePartitions,
					ordinalsByBlock, candidateRuleDomainKeys, candidateRuleFacts, logicalTransientInputs,
					cfgReplayBaseline, origins, constraints, compiledInputEdges, concreteShapes,
					boundActions, loopSeedLedger);
				nodes = projectedGrounded.nodes();
				candidateRuleDomainKeys = projectedGrounded.domainKeys();
				candidateRuleFacts = projectedGrounded.facts();
				logicalTransientInputs = projectedGrounded.logicalInputs();
				privacyClosure = closePrivacyDomains(nodes, candidateRuleFacts,
					constraints, origins, compiledInputEdges, privacyFacts);
				nodes = privacyClosure.nodes();
				candidateRuleFacts = removeUngroundedStagingRealizations(privacyClosure.candidateRuleFacts());
				ExecutableNodeProjection groundedProjection = projectCandidateNodesToExecutableStates(
					nodes, candidateRuleFacts, occurrences.size());
				if(!actionProjection.changedOrdinals().containsAll(groundedProjection.changedOrdinals()))
					throw new IllegalStateException(
						"Final publication projection has an unclosed compiled dependency");
				for(int ordinal : groundedProjection.changedOrdinals())
					if(!groundedProjection.nodes().get(ordinal).equals(actionProjection.nodes().get(ordinal)))
						throw new IllegalStateException(
							"Final publication projection changed an already closed compiled dependency");
				nodes = groundedProjection.nodes();
				logicalTransientInputs = bindExactLogicalTransientSourceStates(
					logicalTransientInputs, candidateRuleFacts);
				publishedActions = canonicalRelocationActions(
					relocations(compiledInputEdges, candidateRuleFacts, nodes, logicalTransientInputs,
						constraints, origins, scopes, factsByHop, concreteShapes, privacyFacts.asMap()),
					publishedActions);
			}
			// The final projection can change the action domain after rebinding.
			// Such an action set has not yet passed the composed realization transfer.
			if(!publishedActions.equals(boundActions)) {
				relocations = publishedActions;
				continue;
			}
			boolean stable = nodes.equals(priorNodes) && candidateRuleDomainKeys.equals(priorDomain)
				&& candidateRuleFacts.equals(priorFacts) && logicalTransientInputs.equals(priorLogical)
				&& publishedActions.equals(priorActions);
			recordFixedPointPass("publication", pass, semanticPassLimit, stable,
				nodes, candidateRuleFacts, logicalTransientInputs, publishedActions);
			if(stable) {
				verifyPublishedRelocationRealizations(candidateRuleFacts, publishedActions);
				relocations = publishedActions;
				publicationConverged = true;
				break;
			}
			relocations = publishedActions;
		}
		if(!publicationConverged)
			throw new IllegalStateException("Executable realization/action publication did not converge");
		// The fixed point compares values, while published receipts require the
		// exact graph-owned state objects. Canonicalize only those equal states;
		// do not regenerate layouts or alter the converged candidate domain here.
			candidateRuleFacts = bindExactCandidateEmissionStates(candidateRuleFacts, nodes);
			candidateRuleFacts = factorizeCandidateSupportRelations(candidateRuleFacts);
			logicalTransientInputs = bindExactLogicalTransientSourceStates(logicalTransientInputs, candidateRuleFacts);
		SearchSpaceMetrics.PhaseToken publicationValidationStarted =
			complexityMetrics == null ? null : complexityMetrics.startPhase(
				SearchSpaceMetrics.Phase.PUBLICATION_VALIDATION);
		try {
			for(Node node : nodes)
				if(requiredEmittedNodes.contains(node.key())
					&& (!node.emittedWork() || node.legalAlternatives().isEmpty())) {
					Privacy privacy = privacyFacts.requirePrivacy(node.key());
					// Exact bottom propagation can expose a privacy-induced loss only after
					// downstream publication has consumed the protected predecessor. Preserve
					// the original fail-closed privacy contract instead of reclassifying that
					// terminal state as an internal executable-realization error.
					if(ExecPlacementPolicy.requiresOriginResidency(privacy))
						throw new DMLRuntimeException("No privacy-safe physical placement for occurrence "
							+ node.key().normalizedSignature() + " (privacy=" + privacy + ")");
					throw new IllegalStateException("NO_EXECUTABLE_REALIZATION: "
						+ node.key().normalizedSignature());
				}
		}
		finally {
			if(complexityMetrics != null)
				complexityMetrics.finishPhase(SearchSpaceMetrics.Phase.PUBLICATION_VALIDATION,
					publicationValidationStarted);
		}
		PlacementAnalysis analysis;
		SearchSpaceMetrics.PhaseToken receiptPreparationStarted =
			complexityMetrics == null ? null : complexityMetrics.startPhase(
				SearchSpaceMetrics.Phase.RECEIPT_RANK_CONSUMER_PREPARATION);
		try {
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
		var sourceCompiledFactsByKey = new LinkedHashMap<CompiledHopKey, NodeShapeFact>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			NodeShapeFact shapeFact = factsByHop.get(projection.hop());
			if(shapeFact == null)
				throw new IllegalStateException("Placement projection has no builder-owned shape fact: " + projection.key());
			factsByKey.put(projection.key(), shapeFact);
			NodeShapeFact sourceCompiledShape = concreteShapes.get(projection.hop());
			if(sourceCompiledShape == null)
				throw new IllegalStateException("Placement projection has no source-compiled shape fact: " + projection.key());
			sourceCompiledFactsByKey.put(projection.key(), sourceCompiledShape);
		}
		PlacementAbstractShapeAnalysis.KeyFacts abstractFacts =
			PlacementAbstractShapeAnalysis.closeCompiledOccurrences(graph, projections, fcallSizes, factsByKey);
		PlacementShapeFacts shapeFacts = new PlacementShapeFacts(factsByKey, sourceCompiledFactsByKey,
			abstractFacts.shapes(), abstractFacts.scalarLiterals(), expectedKeys);
		String analysisFingerprint = analysisFingerprint(graph, projections, shapeFacts);
		HeuristicPolicyFacts heuristicPolicyFacts = heuristicPolicyFacts(graph, projections, shapeFacts,
			compiledInputEdges, candidateRuleFacts, occurrences, cfg);
		ProgramStructureGuard programStructureGuard =
			new ProgramStructureGuard(program,
				PlacementGraphFingerprint.captureProgramAuthority(program));
		analysis = new PlacementAnalysis(graph, projections, topLevelStatementBlocks, program, shapeFacts,
			analysisFingerprint, heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts,
			new CandidatePrivacyClosureEvidence(candidatePrivacyEvidence),
			functionExpansion.logicalInlinedFunctionInputs(), programStructureGuard);
		PlannerCandidateSpaceAudit.record(analysis, prePrivacyNodes, prePrivacyCandidateRuleFacts);
		}
		finally {
			if(complexityMetrics != null)
				complexityMetrics.finishPhase(
					SearchSpaceMetrics.Phase.RECEIPT_RANK_CONSUMER_PREPARATION,
					receiptPreparationStarted);
		}
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
	private PrivacyClosure closePrivacyDomains(List<Node> nodes,
		List<CandidateRuleFact> candidateRuleFacts, Set<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CompiledInputEdgeFact> compiledInputEdges) {
		return closePrivacyDomains(nodes, candidateRuleFacts, constraints, origins,
			compiledInputEdges, null);
	}

	private PrivacyClosure closePrivacyDomains(List<Node> nodes,
		List<CandidateRuleFact> candidateRuleFacts, Set<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CompiledInputEdgeFact> compiledInputEdges,
		PlacementPrivacyFacts fixedPrivacy) {
		Map<CompiledHopKey,List<CompiledHopKey>> predecessors = new IdentityHashMap<>();
		for(Node node : nodes) {
			predecessors.put(node.key(), new ArrayList<>());
		}
		for(Constraint constraint : constraints)
			if(carriesPrivacyValue(constraint))
				addPrivacyPredecessor(predecessors, constraint.right(), constraint.left());
		for(CompiledInputEdgeFact edge : compiledInputEdges)
			addPrivacyPredecessor(predecessors, edge.consumer(), edge.producer());

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
		if(fixedPrivacy != null)
			effective.putAll(fixedPrivacy.asMap());
		else for(Node node : nodes) {
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

		// Authorization is output- and spec-specific, captured once for this snapshot.
		if(fixedPrivacy == null) {
			Set<Hop> publicRecodeMetadata = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop hop : origins.values())
				if(isAuthorizedRecodeMetadataOutput(hop))
					publicRecodeMetadata.add(hop);

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
					// Released aggregate values are coordinator-readable, but the
					// provenance remains part of the shared legality proof downstream.
					if(derived == Privacy.PUBLIC
						&& inputPrivacy.contains(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC))
						derived = Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
				}
				// A declared dictionary release is not a release of the primary encoded
				// matrix. Strict PRIVATE inputs still dominate this limited authorization.
				if(derived == Privacy.PRIVATE_AGGREGATE && publicRecodeMetadata.contains(hop))
					derived = Privacy.PUBLIC;
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
		}

		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> protectedPayloadInputs = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledInputEdges) {
			Privacy sourcePrivacy = effective.get(edge.producer());
			Node consumer = nodesByKey.get(edge.consumer());
			if(sourcePrivacy == null || consumer == null)
				throw new IllegalStateException("Compiled input privacy authority is incomplete");
			if(ExecPlacementPolicy.requiresOriginResidency(sourcePrivacy)
				&& !PlacementAnalysis.isDmlFunctionCallBoundary(consumer, origins.get(edge.consumer()))
				&& PlacementAnalysis.coordinatorInputAccess(origins.get(edge.producer()),
					origins.get(edge.consumer()), edge.inputPosition())
					== PlacementAnalysis.CoordinatorInputAccess.PAYLOAD)
				protectedPayloadInputs.computeIfAbsent(edge.consumer(), ignored -> new LinkedHashMap<>())
					.put(edge.inputPosition(), edge.producer());
		}

		Map<CompiledHopKey,Set<PlacementState>> candidateStates = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<PlacementState>> retainedStates = new IdentityHashMap<>();
		List<CandidateRuleFact> filteredFacts = new ArrayList<>(candidateRuleFacts.size());
		List<CandidatePrivacyClosureEvidence.Rule> evidenceRules = new ArrayList<>(candidateRuleFacts.size());
		for(CandidateRuleFact fact : candidateRuleFacts) {
			Hop hop = origins.get(fact.key().parentOccurrence());
			Privacy privacy = effective.get(fact.key().parentOccurrence());
			if(hop == null || privacy == null)
				throw new IllegalStateException("Candidate privacy authority is incomplete");
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				filteredFacts.add(fact);
				evidenceRules.add(candidatePrivacyEvidence(fact, fact, hop, privacy,
					protectedPayloadInputs.get(fact.key().parentOccurrence())));
				continue;
			}
			Set<PlacementState> seen = candidateStates.computeIfAbsent(
				fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>());
			Set<PlacementState> retained = retainedStates.computeIfAbsent(
				fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>());
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				seen.add(state);
				if(ExecPlacementPolicy.allowsCandidateEmission(hop, privacy, fact, emission)
					&& allowsProtectedCandidateInputs(fact, state, protectedPayloadInputs)) {
					emissions.add(emission);
				}
			}
			emissions = sourceClosedCandidateEmissions(emissions);
			for(CandidateEmissionFact emission : emissions)
				retained.add(emission.emissionState().placementState());
			CandidateRuleFact published;
			if(emissions.isEmpty()) {
				String failure = "PRIVACY:" + privacy.name();
				published = new CandidateRuleFact(fact.key(),
					CandidateEvaluationStatus.PRIVACY_EXCLUDED, fact.capability(), fact.shapeProof(),
					fact.profile(), List.of(), failure);
			}
			else
				published = retainUnchangedPrivacyFact(fact, emissions);
			filteredFacts.add(published);
			evidenceRules.add(candidatePrivacyEvidence(fact, published, hop, privacy,
				protectedPayloadInputs.get(fact.key().parentOccurrence())));
		}
		candidatePrivacyEvidence.add(new CandidatePrivacyClosureEvidence.Pass(
			candidatePrivacyEvidence.size(), evidenceRules));

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
				boolean denied = ExecPlacementPolicy.requiresOriginResidency(privacy)
					&& !PlacementAnalysis.isDmlFunctionCallBoundary(node, origins.get(node.key()))
					&& !(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT);
				// A public aggregate result does not authorize downloading its protected
				// inputs first. CP/FOUT also computes from a coordinator payload.
				denied |= state.execType() == ExecType.CP && protectedPayloadInputs.containsKey(node.key());
				denied |= seen.contains(state) && !retained.contains(state);
				if(denied)
					exclusions.putIfAbsent(state, new Exclusion(state, ReasonCode.PRIVACY,
						"effectivePrivacy=" + privacy.name()));
				else {
					// A fresh allowed result supersedes only an old privacy diagnostic.
					Exclusion stale = exclusions.get(state);
					if(stale != null && stale.reasonCode() == ReasonCode.PRIVACY)
						exclusions.remove(state);
					legal.add(state);
				}
			}
			Node filteredNode = new Node(node.key(), node.kind(), node.valueVersion(),
				!legal.isEmpty(), legal, new ArrayList<>(exclusions.values()), node.anchors());
			if(node.emittedWork() && legal.isEmpty()) {
				PlannerCandidateSpaceAudit.recordPrivacyFailure(node, filteredNode, privacy,
					origins.get(node.key()), candidateRuleFacts, filteredFacts,
					"NO_PRIVACY_SAFE_PHYSICAL_PLACEMENT");
				throw new DMLRuntimeException("No privacy-safe physical placement for occurrence "
					+ node.key().normalizedSignature() + " (privacy=" + privacy + ")");
			}
			filteredNodes.add(filteredNode);
		}

		List<PlacementPrivacyFacts.PrivacyFact> privacyFacts = new ArrayList<>(filteredNodes.size());
		for(Node node : filteredNodes)
			privacyFacts.add(new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				effective.get(node.key()), predecessors.get(node.key())));
		PlacementPrivacyFacts authority = new PlacementPrivacyFacts(filteredNodes, privacyFacts,
			fixedPrivacy == null ? FederatedWorkerUtils.countDistinctWorkers(allPartitions)
				: fixedPrivacy.numWorkers());
		return new PrivacyClosure(List.copyOf(filteredNodes), List.copyOf(filteredFacts), authority);
	}

	private static boolean isAuthorizedRecodeMetadataOutput(Hop hop) {
		FunctionOp call = FederatedPlannerUtils.getMultiReturnFunctionOutputParent(hop);
		if(call == null || call.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !"transformencode".equalsIgnoreCase(call.getFunctionName())
			|| call.getInput().size() != 2 || call.getOutputs().size() != 2 || call.getOutputs().get(1) != hop)
			return false;
		// Multi-return builtins store target and specification positionally.
		Hop spec = call.getInput().get(1);
		// An unresolved spec is not permission to publish an unknown encoder.
		return spec instanceof LiteralOp literal
			&& org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy
				.allowsPublicRecodeMetadata(literal.getStringValue());
	}

	static List<CandidateEmissionFact> sourceClosedCandidateEmissions(List<CandidateEmissionFact> emissions) {
		Set<PlacementState> nativeSources = new HashSet<>();
		for(CandidateEmissionFact emission : emissions)
			if(emission.derivedFoutAction() == null)
				nativeSources.add(emission.emissionState().placementState());
		// A derived FED/LOUT -> FOUT action executes its native source first. If privacy
		// excludes that source, retaining only the remote target would license an illegal
		// collect-then-upload. The witness must survive in this exact candidate row, not
		// merely in another input signature or in the union of the node's legal states.
		return emissions.stream().filter(emission -> emission.derivedFoutAction() == null
			|| nativeSources.contains(emission.derivedFoutAction().sourcePlacement())).toList();
	}

	private static CandidatePrivacyClosureEvidence.Rule candidatePrivacyEvidence(
		CandidateRuleFact input, CandidateRuleFact output, Hop hop, Privacy privacy,
		Map<Integer,CompiledHopKey> protectedPayloadInputs) {
		CandidateCapabilityFact capability = input.capability();
		boolean dmlFunction = hop instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.DML;
		boolean multiReturn = hop instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| isMultiReturnBuiltinOutputCarrier(hop);
		String dataOp = hop instanceof DataOp data ? data.getOp().name() : "-";
		List<Integer> protectedPositions = protectedPayloadInputs == null ? List.of()
			: protectedPayloadInputs.keySet().stream().sorted().toList();
		return new CandidatePrivacyClosureEvidence.Rule(
			PlacementGraphFingerprint.sha256(input.key().normalizedSignature()),
			input.status().name(), input.failureCode(), capability != null, input.profile().available(),
			capability == null ? "-" : capability.nativeExec().name(),
			capability == null ? "-" : capability.nativeOutput().name(), privacy.name(),
			hop.getDataType() != null && hop.getDataType().isMatrix(), isFederatedSource(hop),
			HopUtils.isPrintOrPWrite(hop), dmlFunction, multiReturn, dataOp,
			input.key().orderedInputs().stream().map(CandidateInputState::present).toList(),
			protectedPositions, input.allowedEmissionFacts().stream()
				.map(NeutralPlacementGraphBuilder::privacyEmissionEvidence).toList(),
			output.status().name(), output.failureCode(), output.allowedEmissionFacts().stream()
				.map(emission -> PlacementGraphFingerprint.sha256(emission.normalizedSignature())).toList());
	}

	private static CandidatePrivacyClosureEvidence.Emission privacyEmissionEvidence(
		CandidateEmissionFact emission) {
		PlacementState state = emission.emissionState().placementState();
		return new CandidatePrivacyClosureEvidence.Emission(
			PlacementGraphFingerprint.sha256(emission.normalizedSignature()),
			state.normalizedSignature(),
			state.execType().name(), state.output().name(),
			state.fType() == null ? "-" : state.fType().name(),
			emission.executionFType() == null ? "-" : emission.executionFType().name(),
			emission.emissionState().derivedFedFout(), emission.derivedFoutAction() == null ? "-"
				: emission.derivedFoutAction().sourcePlacement().normalizedSignature());
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

	private static boolean allowsProtectedCandidateInputs(CandidateRuleFact fact, PlacementState state,
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> protectedPayloadInputs) {
		Map<Integer,CompiledHopKey> sources = protectedPayloadInputs.get(fact.key().parentOccurrence());
		if(sources == null)
			return true;
		if(state.execType() != ExecType.FED)
			return false;
		for(Map.Entry<Integer,CompiledHopKey> sourceEntry : sources.entrySet()) {
			int position = sourceEntry.getKey();
			if(position >= fact.key().orderedInputs().size())
				throw new IllegalStateException("Candidate input privacy authority is incomplete");
			// ABSENT_LOCAL would upload an already collected protected input. PRESENT
			// is necessary, not sufficient: the exact relocation model below must prove
			// privacy-safe input availability at a common post-action worker/range pool.
			if(!fact.key().orderedInputs().get(position).present())
				return false;
		}
		// Exact relocation obligations/common-anchor reachability certify whether
		// PRESENT inputs are direct or require an explicit (privacy-filtered) move.
		return true;
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
		Map<CompiledHopKey,List<PlacementState>> supported = constraintSupportedPolicyStates(graph);
		Map<CompiledHopKey,Set<Integer>> localContinuations = exactHeuristicLocalContinuations(
			graph, shapeFacts, compiledInputEdges, candidateRuleFacts, supported);
		List<HeuristicPolicyFact> demotions = new ArrayList<>();
		for(HopOccurrenceProjection projection : projections) {
			Hop hop = projection.hop();
			Node node = graph.node(projection.key()).orElseThrow();
			AbstractShapeFact shape = shapeFacts.abstractShapeFact(projection.key()).orElseThrow();
			boolean exactLocalAlternative = supported.getOrDefault(node.key(), List.of()).stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT && state.shapeDependent()
					&& isAggregateBinaryVectorInput(hop, shape, state.fType()));
			if(exactLocalAlternative)
				demotions.add(new HeuristicPolicyFact(projection.key(), node.valueVersion()));
		}
		while(true) {
			List<HeuristicPathFact> paths = heuristicPaths(graph, projections, shapeFacts, demotions,
				compiledInputEdges, candidateRuleFacts, occurrences, cfg, localContinuations);
			Set<CompiledHopKey> incompatible = Collections.newSetFromMap(new IdentityHashMap<>());
			for(HeuristicPathFact path : paths)
				if(path.localPrefix().stream().anyMatch(key -> key != path.demotion().producer()
					&& supported.getOrDefault(key, List.of()).stream().noneMatch(state ->
						state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)))
					incompatible.add(path.demotion().producer());
			if(incompatible.isEmpty())
				return new HeuristicPolicyFacts(demotions, paths);
			// A base-legal local result may require an upload at a shared formal/TWrite.
			// The heuristic's no-upload local prefix cannot promise that demotion. Decline
			// the preference, not the base candidate, and retrace after strict marker removal.
			demotions.removeIf(demotion -> incompatible.contains(demotion.producer()));
		}
	}

	/**
	 * Necessary hard-constraint support, not a complete assignment or a new candidate domain.
	 * A demotion cannot override a shared function/CFG value that must remain FOUT. Use only
	 * the common legality semantics; selectors still certify runtime candidates and whole plans.
	 */
	static Map<CompiledHopKey,List<PlacementState>> constraintSupportedPolicyStates(NeutralPlacementGraph graph) {
		Map<CompiledHopKey,List<PlacementState>> supported = new IdentityHashMap<>();
		Map<CompiledHopKey,Map<CompiledHopKey,List<Constraint>>> incident = new IdentityHashMap<>();
		java.util.ArrayDeque<CompiledHopKey> pending = new java.util.ArrayDeque<>();
		Set<CompiledHopKey> queued = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : graph.decisionNodes()) {
			supported.put(node.key(), node.legalAlternatives());
			incident.put(node.key(), new LinkedHashMap<>());
			pending.addLast(node.key());
			queued.add(node.key());
		}
		for(Constraint constraint : graph.constraints())
			if(supported.containsKey(constraint.left()) && supported.containsKey(constraint.right())
				&& (constraint.kind() == ConstraintKind.SAME_PLACEMENT
					|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
					|| constraint.kind() == ConstraintKind.SAME_FTYPE
					|| constraint.kind() == ConstraintKind.CONJUNCTIVE)) {
				incident.get(constraint.left()).computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.add(constraint);
				if(constraint.right() != constraint.left())
					incident.get(constraint.right()).computeIfAbsent(constraint.left(), ignored -> new ArrayList<>())
						.add(constraint);
			}
		while(!pending.isEmpty()) {
			CompiledHopKey key = pending.removeFirst();
			queued.remove(key);
			List<PlacementState> prior = supported.get(key);
			List<PlacementState> retained = prior.stream().filter(state -> incident.get(key).entrySet().stream()
				.allMatch(relation -> (relation.getKey() == key ? List.of(state) : supported.get(relation.getKey()))
					.stream().anyMatch(otherState -> relation.getValue().stream().allMatch(constraint ->
						constraint.left() == key
							? NeutralPlacementGraph.constraintSatisfied(constraint, state, otherState)
							: NeutralPlacementGraph.constraintSatisfied(constraint, otherState, state))))).toList();
			if(retained.size() == prior.size())
				continue;
			supported.put(key, retained);
			for(CompiledHopKey other : incident.get(key).keySet()) {
				if(queued.add(other))
					pending.addLast(other);
			}
		}
		return Collections.unmodifiableMap(supported);
	}

	/** Analysis-owned local preference; full assignments still require shared certification. */
	private static Map<CompiledHopKey,Set<Integer>> exactHeuristicLocalContinuations(
		NeutralPlacementGraph graph, PlacementShapeFacts shapes,
		List<CompiledInputEdgeFact> inputEdges, List<CandidateRuleFact> candidates,
		Map<CompiledHopKey,List<PlacementState>> supported) {
		Map<CompiledHopKey,List<CompiledInputEdgeFact>> byConsumer = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : inputEdges)
			byConsumer.computeIfAbsent(edge.consumer(), ignored -> new ArrayList<>()).add(edge);
		Map<CompiledHopKey,Set<Integer>> local = new IdentityHashMap<>();
		for(CandidateRuleFact candidate : candidates) {
			CompiledHopKey consumer = candidate.key().parentOccurrence();
			if(candidate.status() != CandidateEvaluationStatus.AVAILABLE
				|| !exactHeuristicReentryOccurrence(graph.node(consumer).orElseThrow())
				|| !isScalarOrVector(shapes.abstractShapeFact(consumer).orElse(null))
				|| candidate.allowedEmissionFacts().stream().noneMatch(emission -> {
					PlacementState state = emission.emissionState().placementState();
					return state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
						&& supported.getOrDefault(consumer, List.of()).contains(state);
				}))
				continue;
			List<CompiledInputEdgeFact> edges = byConsumer.getOrDefault(consumer, List.of());
			// Unknown shapes and large matrix siblings are not vector-only work.
			if(edges.stream().anyMatch(edge -> !isScalarOrVector(
				shapes.abstractShapeFact(edge.producer()).orElse(null)))
				|| edges.stream().map(CompiledInputEdgeFact::inputPosition).distinct().count() != edges.size())
				continue;
			for(CompiledInputEdgeFact active : edges) {
				List<CandidateInputState> inputs = candidate.key().orderedInputs();
				// CP consumes the path-local payload independently of the oracle
				// tuple's input FType; PRESENT is an execution requirement only for FED.
				if(active.inputPosition() >= inputs.size())
					continue;
				boolean supplied = true;
				for(CompiledInputEdgeFact sibling : edges) {
					if(sibling == active)
						continue;
					if(sibling.inputPosition() >= inputs.size()) {
						supplied = false;
						break;
					}
					CandidateInputState input = inputs.get(sibling.inputPosition());
					if(supported.getOrDefault(sibling.producer(), List.of()).stream().noneMatch(state ->
						input.present() ? state.execType() == ExecType.FED
							&& state.output() == FederatedOutput.FOUT && state.fType() == input.fType()
							: state.output() == FederatedOutput.LOUT)) {
						supplied = false;
						break;
					}
				}
				if(supplied)
					local.computeIfAbsent(consumer, ignored -> new HashSet<>()).add(active.inputPosition());
			}
		}
		return local;
	}

	private static boolean isScalarOrVector(AbstractShapeFact shape) {
		return shape != null && (shape.dataType().isScalar() || shape.provablyVector());
	}

	private static List<HeuristicPathFact> heuristicPaths(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts,
		List<HeuristicPolicyFact> demotions,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CfgAnalysis cfg,
		Map<CompiledHopKey,Set<Integer>> localContinuations) {
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
			compiledInputEdges, candidateRuleFacts, outgoing, localContinuations);
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
				candidateRuleFacts, outgoing, localContinuations);
		}
		throw new IllegalStateException("Heuristic CFG local-phi closure did not converge");
	}

	private static List<HeuristicPathFact> traceHeuristicPaths(NeutralPlacementGraph graph,
		PlacementShapeFacts shapeFacts, List<HeuristicPolicyFact> demotions,
		List<CompiledInputEdgeFact> compiledInputEdges, List<CandidateRuleFact> candidateRuleFacts,
		Map<CompiledHopKey,List<HeuristicPathEdgeFact>> outgoing,
		Map<CompiledHopKey,Set<Integer>> localContinuations) {
		Set<CompiledHopKey> demotionProducers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(HeuristicPolicyFact demotion : demotions)
			demotionProducers.add(demotion.producer());
		List<HeuristicPathFact> paths = new ArrayList<>();
		for(HeuristicPolicyFact demotion : demotions) {
			Set<CompiledHopKey> localPrefix = new java.util.TreeSet<>();
			Set<HeuristicPathEdgeFact> usedEdges = new java.util.TreeSet<>();
			Set<HeuristicPathwiseReentryFact> reentries = new java.util.TreeSet<>();
			Set<HeuristicNativeContinuationFact> nativeContinuations = new java.util.TreeSet<>();
			java.util.ArrayDeque<CompiledHopKey> pending = new java.util.ArrayDeque<>();
			localPrefix.add(demotion.producer());
			pending.add(demotion.producer());
			while(!pending.isEmpty()) {
				CompiledHopKey producerKey = pending.removeFirst();
				for(HeuristicPathEdgeFact edge : outgoing.getOrDefault(producerKey, List.of())) {
					if(edge.kind() == HeuristicPathEdgeKind.COMPILED_INPUT) {
						boolean nestedDemotion = demotionProducers.contains(edge.consumer());
						// A downstream aggregate/vector demotion is an output-placement boundary,
						// not proof that the operation itself should run in CP. If the exact runtime
						// candidate can consume this local operand while keeping a federated sibling
						// resident, prefer that FED/LOUT continuation before extending the CP prefix.
						if(nestedDemotion) {
							HeuristicNativeContinuationFact nativeContinuation =
								exactHeuristicNativeContinuation(graph, compiledInputEdges,
									candidateRuleFacts, edge.producer(), edge.consumer(), edge.inputPosition(),
									FederatedOutput.LOUT);
							if(nativeContinuation != null) {
								nativeContinuations.add(nativeContinuation);
								continue;
							}
						}
						// A legal scalar/vector continuation remains local. Only this consumer joins
						// the prefix; its sibling remains independently placed.
						if(localContinuations.getOrDefault(edge.consumer(), Set.of()).contains(edge.inputPosition())) {
							usedEdges.add(edge);
							if(localPrefix.add(edge.consumer())
								&& supportedLocalPathNode(graph, shapeFacts, edge.consumer()))
								pending.addLast(edge.consumer());
							continue;
						}
						HeuristicPathwiseReentryFact reentry = exactHeuristicReentry(graph, compiledInputEdges,
							candidateRuleFacts, edge.producer(), edge.consumer(), edge.inputPosition());
						if(reentry != null) {
							reentries.add(reentry);
							continue;
						}
						HeuristicNativeContinuationFact nativeContinuation =
							exactHeuristicNativeContinuation(graph, compiledInputEdges,
								candidateRuleFacts, edge.producer(), edge.consumer(), edge.inputPosition(),
								FederatedOutput.FOUT);
						if(nativeContinuation != null) {
							nativeContinuations.add(nativeContinuation);
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
				new ArrayList<>(usedEdges), new ArrayList<>(reentries),
				new ArrayList<>(nativeContinuations)));
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
				|| !PlacementAnalysis.isCompiledAcyclicTransientForwardAccess(
					readProjection.hop(), read, OpOpData.TRANSIENTREAD)
				|| cfg.reachingFunctionInputs().get(readOrdinal)
				|| cfg.reachingDefinitions().get(readOrdinal).isEmpty()
				|| !supportedLocalCfgForwardNode(read))
				continue;
			if(!isVector(shapeFacts.abstractShapeFact(readProjection.key()).orElse(null)))
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
					|| source == null || !PlacementAnalysis.isCompiledAcyclicTransientForwardAccess(
						sourceProjection.hop(), source, OpOpData.TRANSIENTWRITE)
					|| !supportedLocalCfgForwardNode(source)
					|| !sameTransientForwardContext(source, read)
					|| !isVector(shapeFacts.abstractShapeFact(sourceProjection.key()).orElse(null))) {
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
		if(!exactHeuristicReentryOccurrence(graph.node(localProducer).orElseThrow())
			|| !exactHeuristicReentryOccurrence(graph.node(consumer).orElseThrow()))
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
					&& edge.producer() != localProducer
					&& exactHeuristicReentryOccurrence(graph.node(edge.producer()).orElseThrow()))
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

	/**
	 * Proves the narrow mixed-input case in which the runtime candidate consumes the path-local
	 * operand directly from the coordinator while preserving one exact resident FOUT sibling. This
	 * is not a relocation fallback: the candidate row must say ABSENT_LOCAL at the exact path edge,
	 * and every federated input position must be accounted for by one compiled sibling edge.
	 */
	static HeuristicNativeContinuationFact exactHeuristicNativeContinuation(
		NeutralPlacementGraph graph, List<CompiledInputEdgeFact> compiledInputEdges,
		List<CandidateRuleFact> candidateRuleFacts, CompiledHopKey localProducer,
		CompiledHopKey consumer, int localInputPosition) {
		return exactHeuristicNativeContinuation(graph, compiledInputEdges, candidateRuleFacts,
			localProducer, consumer, localInputPosition, FederatedOutput.FOUT);
	}

	static HeuristicNativeContinuationFact exactHeuristicNativeContinuation(
		NeutralPlacementGraph graph, List<CompiledInputEdgeFact> compiledInputEdges,
		List<CandidateRuleFact> candidateRuleFacts, CompiledHopKey localProducer,
		CompiledHopKey consumer, int localInputPosition, FederatedOutput output) {
		if(!exactHeuristicReentryOccurrence(graph.node(localProducer).orElseThrow())
			|| !exactHeuristicReentryOccurrence(graph.node(consumer).orElseThrow()))
			return null;
		Node consumerNode = graph.node(consumer).orElseThrow();
		if(consumerNode.kind() == NodeKind.FUNCTION_CALL)
			return null;
		List<HeuristicNativeContinuationFact> matches = new ArrayList<>();
		for(CandidateRuleFact candidate : candidateRuleFacts)
			addExactHeuristicNativeContinuationMatch(graph, compiledInputEdges, localProducer,
				consumer, localInputPosition, candidate, output, matches);
		List<HeuristicNativeContinuationFact> exactMatches = matches.stream()
			.distinct().sorted().toList();
		return exactMatches.size() == 1 ? exactMatches.get(0) : null;
	}

	private static void addExactHeuristicNativeContinuationMatch(NeutralPlacementGraph graph,
		List<CompiledInputEdgeFact> compiledInputEdges, CompiledHopKey localProducer,
		CompiledHopKey consumer, int localInputPosition, CandidateRuleFact candidate,
		FederatedOutput output, List<HeuristicNativeContinuationFact> matches) {
		if(candidate.key().parentOccurrence() != consumer
			|| candidate.status() != CandidateEvaluationStatus.AVAILABLE
			|| candidate.capability() == null
			|| candidate.capability().nativeExec() != ExecType.FED
			|| !candidate.profile().available()
			|| localInputPosition >= candidate.key().orderedInputs().size()
			|| !candidate.key().orderedInputs().get(localInputPosition)
				.equals(CandidateInputState.absentLocal()))
			return;
		List<Integer> presentPositions = new ArrayList<>();
		for(int position = 0; position < candidate.key().orderedInputs().size(); position++)
			if(candidate.key().orderedInputs().get(position).present())
				presentPositions.add(position);
		// Keep this proof deliberately narrow and auditable. Multi-federated-input
		// candidates require a tuple-valued sibling contract rather than an arbitrary
		// first matching edge.
		if(presentPositions.size() != 1)
			return;
		int siblingInputPosition = presentPositions.get(0);
		FType layout = candidate.key().orderedInputs().get(siblingInputPosition).fType();
		boolean nativeOutputCompatible = output == FederatedOutput.FOUT
			? candidate.capability().nativeOutput() == FederatedOutput.FOUT
				&& candidate.capability().nativeFoutFType() == layout
			: output == FederatedOutput.LOUT;
		if(!nativeOutputCompatible)
			return;
		if(!candidate.key().orderedInputs().get(siblingInputPosition)
			.equals(CandidateInputState.present(layout)))
			return;
		Node consumerNode = graph.node(consumer).orElseThrow();
		List<PlacementState> consumerStates = consumerNode.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == output && state.fType() == layout)
			.filter(state -> candidate.allowedEmissionFacts().stream()
				.anyMatch(emission -> emission.emissionState().placementState().equals(state)
					&& emission.executionFType() == layout))
			.toList();
		if(consumerStates.size() != 1)
			return;
		List<CompiledInputEdgeFact> siblingEdges = compiledInputEdges.stream()
			.filter(edge -> edge.consumer() == consumer
				&& edge.inputPosition() == siblingInputPosition
				&& edge.producer() != localProducer
				&& exactHeuristicReentryOccurrence(graph.node(edge.producer()).orElseThrow()))
			.toList();
		if(siblingEdges.size() != 1)
			return;
		CompiledInputEdgeFact siblingEdge = siblingEdges.get(0);
		Node sibling = graph.node(siblingEdge.producer()).orElseThrow();
		List<PlacementState> siblingStates = sibling.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == layout)
			.toList();
		if(siblingStates.size() != 1)
			return;
		matches.add(new HeuristicNativeContinuationFact(localProducer,
			graph.node(localProducer).orElseThrow().valueVersion(), consumer, localInputPosition,
			sibling.key(), sibling.valueVersion(), siblingInputPosition, siblingStates.get(0),
			consumerStates.get(0), candidate));
	}

	private static boolean supportedLocalPathNode(NeutralPlacementGraph graph,
		PlacementShapeFacts shapeFacts, CompiledHopKey key) {
		Node node = graph.node(key).orElseThrow();
		return supportedLocalPathOccurrence(node) && node.kind() != NodeKind.FUNCTION_CALL
			&& node.kind() != NodeKind.FUNCTION_INPUT && node.kind() != NodeKind.FUNCTION_OUTPUT
			&& node.kind() != NodeKind.FUNCTION_BODY_NON_EMITTED
			&& isVector(shapeFacts.abstractShapeFact(key).orElse(null));
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

	static boolean exactHeuristicReentryOccurrence(Node node) {
		Objects.requireNonNull(node, "node");
		// Pathwise re-entry is authorized only for one concrete emitted Hop occurrence.
		// Exact compiled-input edges, candidate rows, relocation actions, and obligations
		// already distinguish named-function, loop-body, and whole-body recompile
		// occurrences.  Those structural regions therefore need no blanket exclusion.
		// Synthetic call/boundary nodes and compiler recompile clones remain excluded
		// because they do not denote the exact runtime consumer owned by the action.
		return supportedLocalPathOccurrence(node) && node.emittedWork()
			&& node.kind() != NodeKind.FUNCTION_CALL
			&& node.kind() != NodeKind.FUNCTION_INPUT
			&& node.kind() != NodeKind.FUNCTION_OUTPUT
			&& node.kind() != NodeKind.FUNCTION_BODY_NON_EMITTED
			&& node.kind() != NodeKind.CLONE;
	}

	private static boolean supportedLocalPathOccurrence(Node node) {
		// Exact compiled-input edges are owned by one concrete Hop DAG even inside a
		// function, loop, or whole-body recompile region. They are sufficient to
		// propagate coordinator-local placement. A concrete CLONE_RECOMPILE occurrence
		// remains excluded because its topology may change. Exact TWrite/TRead CFG
		// forwarding and REFED frontier inference both use this node-aware occurrence
		// condition; re-entry additionally requires the exact analysis-owned candidate,
		// sibling anchor, relocation action, and obligation.
		return node.valueVersion().versionKind() != VersionKind.CLONE_RECOMPILE
			&& ("compiled".equals(node.key().recompileContext())
				|| "recompile".equals(node.key().recompileContext()));
	}

	private static boolean isVector(AbstractShapeFact shape) {
		return shape != null && shape.provablyVector();
	}

	private static boolean isAggregateBinaryVectorInput(Hop hop, AbstractShapeFact shape, FType inputType) {
		if(!(hop instanceof AggBinaryOp) || shape == null || !shape.isMatrix())
			return false;
		return inputType == FType.ROW && shape.provablyColumnVector()
			|| inputType == FType.COL && shape.provablyRowVector()
			// A one-worker FULL map owns the complete matrix, so the vector result is
			// orientation-independent but still follows the same forced-LOUT policy.
			|| inputType == FType.FULL && isVector(shape);
	}

	private static FType exactAggregateBinaryVectorLocalType(Hop hop, AbstractShapeFact shape,
		List<FType> inputTypes) {
		List<FType> matches = inputTypes.stream()
			.filter(inputType -> isAggregateBinaryVectorInput(hop, shape, inputType))
			.distinct().toList();
		return matches.size() == 1 ? matches.get(0) : null;
	}

	static String analysisFingerprint(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		PlacementShapeFacts shapeFacts) {
		String graphSignature = graph.normalizedSignature();
		List<String> projectionSignatures = occurrences.stream()
			.map(occurrence -> stableFingerprintSignature(occurrence.normalizedSignature())).sorted().toList();
		return PlacementGraphFingerprint.sha256(stableFingerprintSignature(graphSignature) + '\n'
			+ String.join("\n", projectionSignatures) + '\n'
			+ stableFingerprintSignature(shapeFacts.normalizedSignature()));
	}

	private static String stableFingerprintSignature(String signature) {
		return signature.replaceAll("[0-9a-f]{64}", "<program>");
	}

	private static CfgAnalysis analyzeCfg(DMLProgram program, List<StatementBlock> topLevelStatementBlocks,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
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
		connectSequence(topLevelStatementBlocks, Set.of(), predecessors, loopHeaders, loopLatches,
			scalarFacts);
		Map<StatementBlock,Map<String,Set<Integer>>> functionInputSeeds = new IdentityHashMap<>();
		Map<String,Set<StatementBlock>> functionExits = new java.util.TreeMap<>();
		for(Map.Entry<String,FunctionStatementBlock> entry :
			program.getNamedNSFunctionStatementBlocks().entrySet()) {
			FunctionStatementBlock function = entry.getValue();
			Set<StatementBlock> exits = connectSequence(List.of(function), Set.of(), predecessors,
				loopHeaders, loopLatches, scalarFacts);
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
		Set<StatementBlock> loopLatches,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
		Set<StatementBlock> exits = new LinkedHashSet<>(incoming);
		for(StatementBlock block : blocks == null ? List.<StatementBlock>of() : blocks) {
			predecessors.computeIfAbsent(block, k -> Collections.newSetFromMap(new IdentityHashMap<>())).addAll(exits);
			if(block instanceof IfStatementBlock) {
				IfStatement statement = (IfStatement) block.getStatement(0);
				Boolean predicate = exactPredicate(isbPredicate((IfStatementBlock)block), scalarFacts);
				if(predicate == null || predicate) {
					Set<StatementBlock> thenExits = connectSequence(statement.getIfBody(), Set.of(block), predecessors,
						loopHeaders, loopLatches, scalarFacts);
					exits = new LinkedHashSet<>(thenExits.isEmpty() ? Set.of(block) : thenExits);
				}
				if(predicate == null || !predicate) {
					Set<StatementBlock> elseExits = connectSequence(statement.getElseBody(), Set.of(block), predecessors,
						loopHeaders, loopLatches, scalarFacts);
					if(predicate == null)
						exits.addAll(elseExits.isEmpty() ? Set.of(block) : elseExits);
					else
						exits = new LinkedHashSet<>(elseExits.isEmpty() ? Set.of(block) : elseExits);
				}
			}
			else if(block instanceof WhileStatementBlock) {
				loopHeaders.add(block);
				WhileStatement statement = (WhileStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches, scalarFacts);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof ForStatementBlock) {
				loopHeaders.add(block);
				ForStatement statement = (ForStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches, scalarFacts);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof FunctionStatementBlock) {
				FunctionStatement statement = (FunctionStatement) block.getStatement(0);
				exits = connectSequence(statement.getBody(), Set.of(block), predecessors, loopHeaders, loopLatches,
					scalarFacts);
			}
			else exits = new LinkedHashSet<>(Set.of(block));
		}
		return exits;
	}

	private static Hop isbPredicate(IfStatementBlock block) {
		Hop predicateRoot = block.getPredicateHops();
		return predicateRoot != null && predicateRoot.getInput() != null
			&& predicateRoot.getInput().size() == 1 ? predicateRoot.getInput(0) : predicateRoot;
	}

	private static Boolean exactPredicate(Hop predicate,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
		if(predicate == null || scalarFacts == null)
			return null;
		PlacementAbstractShapeAnalysis.ScalarState state = scalarFacts.get(predicate);
		if(state == null || !state.isExact())
			return null;
		String value = state.literal().canonicalValue();
		if("true".equalsIgnoreCase(value) || "1".equals(value))
			return true;
		if("false".equalsIgnoreCase(value) || "0".equals(value))
			return false;
		return null;
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
	private record LoopSeedRevision(String read, List<Node> proofNodes,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> proofFacts,
		List<LogicalTransientInputFact> logicalInputs,
		List<String> compiledEdges, List<Constraint> constraints,
		List<NeutralPlacementGraph.RelocationAction> actionAuthority, boolean privacyClosed) { }
	private record LoopSeedLedger(Map<LoopSeedRevision,CandidateReplay> completedTransfers) {
		private LoopSeedLedger {
			Objects.requireNonNull(completedTransfers, "completedTransfers");
		}
	}

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
				anchor = cfgTransientReadAnchor(occurrences.get(i).hop(), nodes.get(i).key().normalizedSignature(),
					factsByHop.get(occurrences.get(i).hop()),
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
	private DurableAnchorKey cfgTransientReadAnchor(Hop hop, String occurrence, NodeShapeFact outputShape,
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
			else if(!sameExactAnchorGeometry(anchor, definitionAnchor))
				return null;
		}
		return anchor != null && outputShape != null && outputShape.dataType().isMatrix()
			&& outputGeometryCompatible(outputShape, anchor) && oracleConfirmsAnchorDomain(hop, occurrence,
				Collections.singletonList(Collections.singletonList(anchor.fType())), anchor) ? anchor : null;
	}

	private DurableAnchorKey inheritableDurableAnchor(Hop hop, String occurrence, NodeShapeFact outputShape,
		List<NodeShapeFact> inputShapeFacts, List<DurableAnchorKey> inputAnchors) {
		if(outputShape == null || !outputShape.dataType().isMatrix())
			return null;
		List<DurableAnchorKey> candidates = new ArrayList<>();
		for(int i = 0; i < inputShapeFacts.size(); i++)
			if(inputShapeFacts.get(i).dataType().isMatrix() && inputAnchors.get(i) != null)
				candidates.add(inputAnchors.get(i));
		if(candidates.isEmpty())
			return null;
		DurableAnchorKey anchor = candidates.stream().sorted().findFirst().orElseThrow();
		if(candidates.stream().anyMatch(candidate ->
			!PlacementIdentity.samePhysicalWorkerPool(anchor, candidate)))
			return null;
		List<List<FType>> domains = new ArrayList<>(inputShapeFacts.size());
		for(int i = 0; i < inputShapeFacts.size(); i++) {
			NodeShapeFact inputShape = inputShapeFacts.get(i);
			DurableAnchorKey inputAnchor = inputAnchors.get(i);
			if(!inputShape.dataType().isMatrix())
				domains.add(Collections.singletonList(null));
			else if(inputAnchor != null
				&& PlacementIdentity.samePhysicalWorkerPool(anchor, inputAnchor))
				domains.add(Collections.singletonList(anchor.fType()));
			else if(inputAnchor != null || !knownBroadcastableLocalMatrix(inputShape))
				return null;
			else
				domains.add(Collections.singletonList(null));
		}
		// An actual TWrite aliases the complete input value and may keep its exact
		// map even when the compiler has no concrete dimensions. Computations must
		// instead prove their current output extent; a stable pool alone is weaker.
		boolean exactAlias = hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE
			&& hop.getInput().size() == 1 && inputAnchors.get(0) != null;
		return (exactAlias || outputGeometryCompatible(outputShape, anchor))
			&& oracleConfirmsAnchorDomain(hop, occurrence, domains, anchor)
			? anchor : null;
	}

	private boolean oracleConfirmsAnchorDomain(Hop hop, String occurrence, List<List<FType>> domains,
		DurableAnchorKey anchor) {
		try {
			FTypeProfile profile = oracle.inferProfile(hop, domains, null);
			return profile != null && profile.outputs() != null && profile.outputs().contains(anchor.fType());
		}
		catch(RuntimeException e) {
			throw oracleRuntimeFailure("anchor profile", occurrence, hop, domains, e);
		}
	}

	private static boolean knownBroadcastableLocalMatrix(NodeShapeFact shape) {
		return shape.knownPositiveMatrix() && (shape.rows() == 1 || shape.cols() == 1);
	}

	private static boolean outputGeometryCompatible(NodeShapeFact outputShape, DurableAnchorKey anchor) {
		if(!outputShape.knownPositiveMatrix())
			return false;
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

	private static boolean sameExactAnchorGeometry(DurableAnchorKey left, DurableAnchorKey right) {
		// Unlike samePhysicalWorkerPool, compare both axes, including FULL extents.
		// Placement ids identify metadata provenance, not different physical ranges.
		return left.fType() == right.fType() && left.partitions().equals(right.partitions());
	}

	/**
	 * A provisional loop seed may run once for each complete proof-input revision.
	 * Later function, privacy, or relocation closure creates a new revision and may be
	 * seeded, while both sides of a completed seed transfer are consumed together so an
	 * enclosing fixed point cannot restart the provisional two-state cycle.
	 */
	private static Map<CompiledHopKey,LoopSeedRevision> loopSeedEligibleReads(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs,
		List<CompiledInputEdgeFact> compiledEdges, java.util.Collection<Constraint> constraints,
		List<NeutralPlacementGraph.RelocationAction> actionAuthority, boolean privacyClosed) {
		List<Node> proofNodes = List.copyOf(nodes);
		List<CandidateRuleFact> proofFacts = List.copyOf(facts);
		List<LogicalTransientInputFact> proofLogicalInputs = logicalInputs.stream().sorted().toList();
		List<String> proofCompiledEdges = loopSeedCompiledEdges(compiledEdges);
		List<Constraint> proofConstraints = constraints.stream().sorted().toList();
		Map<CompiledHopKey,LoopSeedRevision> eligible = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			if(!isTransientRead(occurrences.get(ordinal).hop())
				|| cfg.reachingFunctionInputs().get(ordinal)
				|| cfg.reachingDefinitions().get(ordinal).size() <= 1)
				continue;
			LoopSeedRevision revision = new LoopSeedRevision(
				nodes.get(ordinal).key().normalizedSignature(), proofNodes, List.copyOf(domainKeys), proofFacts,
				proofLogicalInputs, proofCompiledEdges, proofConstraints,
				List.copyOf(actionAuthority), privacyClosed);
			eligible.put(nodes.get(ordinal).key(), revision);
		}
		return Collections.unmodifiableMap(eligible);
	}

	private static List<String> loopSeedCompiledEdges(List<CompiledInputEdgeFact> compiledEdges) {
		return compiledEdges.stream().map(edge -> edge.producer().normalizedSignature() + '\u0000'
			+ edge.consumer().normalizedSignature() + '\u0000' + edge.inputPosition()).sorted().toList();
	}

	private CandidateReplay completedLoopSeedTransfer(
		Map<CompiledHopKey,LoopSeedRevision> revisions, CandidateReplay current,
		int compiledOccurrenceCount, List<CompiledInputEdgeFact> compiledEdges,
		List<NeutralPlacementGraph.RelocationAction> actionAuthority,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes,
		LoopSeedLedger ledger) {
		CandidateReplay completed = null;
		for(LoopSeedRevision revision : revisions.values()) {
			CandidateReplay candidate = ledger.completedTransfers().get(revision);
			if(candidate == null)
				continue;
			if(completed != null && !completed.equals(candidate))
				throw new IllegalStateException("Loop seed revision has conflicting memoized exits");
			completed = candidate;
		}
		if(completed == null)
			return null;
		List<CandidateRuleFact> reboundFacts = bindRelocationCandidateRealizations(
			completed.facts(), completed.nodes(), compiledEdges, actionAuthority, origins, shapes);
		List<LogicalTransientInputFact> reboundLogical = bindExactLogicalTransientSourceStates(
			completed.logicalInputs(), reboundFacts);
		completed = new CandidateReplay(completed.nodes(), completed.domainKeys(), reboundFacts,
			reboundLogical, List.of());
		boolean unchanged = completed.nodes().equals(current.nodes())
			&& completed.domainKeys().equals(current.domainKeys())
			&& completed.facts().equals(current.facts())
			&& completed.logicalInputs().equals(current.logicalInputs());
		List<Integer> changed = new ArrayList<>();
		if(!unchanged)
			for(int ordinal = 0; ordinal < compiledOccurrenceCount; ordinal++)
				changed.add(ordinal);
		return new CandidateReplay(completed.nodes(), completed.domainKeys(), completed.facts(),
			completed.logicalInputs(), List.copyOf(changed));
	}

	static <K,R> void recordInstalledLoopSeedRevisions(
		Map<K,R> eligibleLoopSeeds, Set<K> installedLoopSeeds, Set<R> seenLoopSeedRevisions) {
		for(K read : installedLoopSeeds) {
			R revision = eligibleLoopSeeds.get(read);
			if(revision != null)
				seenLoopSeedRevisions.add(revision);
		}
	}

	static <R,T> void recordCompletedLoopSeedTransfer(
		R entryRevision, T completedReplay, boolean retained, Map<R,T> completedTransfers) {
		if(!retained)
			throw new IllegalStateException("Installed loop seed was not retained by composed closure");
		T prior = completedTransfers.putIfAbsent(
			Objects.requireNonNull(entryRevision, "entryRevision"),
			Objects.requireNonNull(completedReplay, "completedReplay"));
		if(prior != null && !prior.equals(completedReplay))
			throw new IllegalStateException("Loop seed entry has conflicting completed transfers");
	}

	private static void recordCompletedLoopSeedRevisions(
		Map<CompiledHopKey,LoopSeedRevision> eligibleLoopSeeds,
		Set<CompiledHopKey> installedLoopSeeds, CfgAnalysis cfg,
		List<Node> nodes, List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs, List<CompiledInputEdgeFact> compiledEdges,
		java.util.Collection<Constraint> constraints, boolean privacyClosed,
		LoopSeedLedger loopSeedLedger) {
		for(CompiledHopKey read : installedLoopSeeds) {
			int readOrdinal = -1;
			for(int ordinal = 0; ordinal < nodes.size(); ordinal++)
				if(nodes.get(ordinal).key() == read) {
					readOrdinal = ordinal;
					break;
				}
			if(readOrdinal < 0 || readOrdinal >= cfg.reachingDefinitions().size())
				throw new IllegalStateException("Installed loop seed has no retained CFG read");
			Set<CompiledHopKey> expectedSources = Collections.newSetFromMap(new IdentityHashMap<>());
			for(int definition : cfg.reachingDefinitions().get(readOrdinal))
				expectedSources.add(nodes.get(definition).key());
			Set<CompiledHopKey> retainedSources = Collections.newSetFromMap(new IdentityHashMap<>());
			for(LogicalTransientInputFact input : logicalInputs)
				if(input.targetRead() == read)
					retainedSources.add(input.sourceWrite());
			if(retainedSources.size() != expectedSources.size()
				|| !retainedSources.containsAll(expectedSources))
				throw new IllegalStateException(
					"Installed loop seed did not retain every reaching definition");
		}
		CandidateReplay completed = new CandidateReplay(List.copyOf(nodes), facts.stream()
			.map(CandidateRuleFact::key).toList(), List.copyOf(facts),
			logicalInputs.stream().sorted().toList(), List.of());
		for(CompiledHopKey read : installedLoopSeeds) {
			recordCompletedLoopSeedTransfer(eligibleLoopSeeds.get(read), completed,
				true, loopSeedLedger.completedTransfers());
		}
	}

	private CandidateReplay closeCfgTransientCandidateDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs, CfgReplayBaseline baseline,
		Map<CompiledHopKey,Hop> origins, java.util.Collection<Constraint> constraints,
		LoopSeedLedger loopSeedLedger) {
		return closeCfgTransientCandidateDependencies(occurrences, nodes, cfg, factsByHop,
			abstractFactsByHop, singlePartitions, ordinalsByBlock, domainKeys, facts,
			logicalInputs, baseline, origins, constraints, null, null, List.of(), loopSeedLedger);
	}

	private CandidateReplay closeCfgTransientCandidateDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs, CfgReplayBaseline baseline,
		Map<CompiledHopKey,Hop> origins, java.util.Collection<Constraint> constraints,
		List<CompiledInputEdgeFact> compiledInputEdges,
		Map<Hop,NodeShapeFact> sourceCompiledFactsByHop,
		List<NeutralPlacementGraph.RelocationAction> actionAuthority,
		LoopSeedLedger loopSeedLedger) {
		Objects.requireNonNull(loopSeedLedger, "loopSeedLedger");
		SearchSpaceMetrics.PhaseToken phaseStarted =
			complexityMetrics == null ? null : complexityMetrics.startPhase(
				SearchSpaceMetrics.Phase.CLOSURE_REPLAY);
		try {
			return closeCfgTransientCandidateDependenciesMeasured(occurrences, nodes, cfg,
				factsByHop, abstractFactsByHop, singlePartitions, ordinalsByBlock, domainKeys,
				facts, logicalInputs, baseline, origins, constraints, compiledInputEdges,
				sourceCompiledFactsByHop, actionAuthority, loopSeedLedger);
		}
		finally {
			if(complexityMetrics != null)
				complexityMetrics.finishPhase(SearchSpaceMetrics.Phase.CLOSURE_REPLAY, phaseStarted);
		}
	}

	private CandidateReplay closeCfgTransientCandidateDependenciesMeasured(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop,
		SinglePartitionFacts singlePartitions,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs, CfgReplayBaseline baseline,
		Map<CompiledHopKey,Hop> origins, java.util.Collection<Constraint> constraints,
		List<CompiledInputEdgeFact> compiledInputEdges,
		Map<Hop,NodeShapeFact> sourceCompiledFactsByHop,
		List<NeutralPlacementGraph.RelocationAction> actionAuthority,
		LoopSeedLedger loopSeedLedger) {
		List<CandidateRuleFact> boundFacts = bindExactCandidateEmissionRealizations(
			facts, nodes, origins, factsByHop);
		List<CandidateRuleFact> directTemplates = boundFacts;
		CandidateReplay current = new CandidateReplay(List.copyOf(nodes), List.copyOf(domainKeys),
			boundFacts, List.copyOf(logicalInputs), List.of());
		boolean privacyAlreadyClosed = current.facts().stream()
			.anyMatch(fact -> fact.status() == CandidateEvaluationStatus.PRIVACY_EXCLUDED);
		PlacementPrivacyFacts fixedClosurePrivacy = null;
		Set<Constraint> closurePrivacyConstraints = privacyAlreadyClosed
			? new LinkedHashSet<>(constraints) : Set.of();
		List<CompiledInputEdgeFact> loopSeedEntryEdges = deriveCompiledInputEdges(
			occurrences, current.nodes(), ordinalsByBlock, factsByHop);
		Map<CompiledHopKey,LoopSeedRevision> eligibleLoopSeedRevisions = loopSeedEligibleReads(
			occurrences, current.nodes(), cfg, current.domainKeys(), current.facts(),
			current.logicalInputs(), loopSeedEntryEdges, constraints, actionAuthority, privacyAlreadyClosed);
		CandidateReplay memoized = completedLoopSeedTransfer(eligibleLoopSeedRevisions,
			current, occurrences.size(), loopSeedEntryEdges, actionAuthority,
			origins, factsByHop, loopSeedLedger);
		if(memoized != null)
			return memoized;
		Set<CompiledHopKey> eligibleLoopSeeds = eligibleLoopSeedRevisions.keySet();
		Set<CompiledHopKey> installedLoopSeeds = Collections.newSetFromMap(new IdentityHashMap<>());
		int maxPasses = Math.max(1, occurrences.size() + domainKeys.size() + logicalInputs.size() + 1);
		List<String> closureTrace = new ArrayList<>();
		for(int pass = 0; pass < maxPasses; pass++) {
			CandidateReplay passStart = current;
			String beforeSignature = "n=" + current.nodes().hashCode() + ",f=" + current.facts().hashCode()
				+ ",l=" + current.logicalInputs().hashCode();
			List<Node> passNodes = current.nodes();
			Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
			for(Node node : passNodes)
				nodesByKey.put(node.key(), node);
			Map<CompiledHopKey,List<CompiledHopKey>> reachingSources = new IdentityHashMap<>();
			for(int ordinal = 0; ordinal < occurrences.size(); ordinal++)
				if(!cfg.reachingFunctionInputs().get(ordinal) && isTransientRead(occurrences.get(ordinal).hop()))
					reachingSources.put(passNodes.get(ordinal).key(), cfg.reachingDefinitions().get(ordinal)
						.stream().sorted().map(source -> passNodes.get(source).key()).toList());
			List<CompiledInputEdgeFact> compiledEdges = deriveCompiledInputEdges(
				occurrences, current.nodes(), ordinalsByBlock, factsByHop);
			NativePlacementContinuity nativePools = new NativePlacementContinuity(nodesByKey, origins,
				current.facts(), compiledEdges, reachingSources, complexityMetrics);
			DirectBindingIndex directIndex = directBindingIndex(directTemplates, current.nodes(), compiledEdges,
				current.facts());
			boolean directConverged = false;
			Set<CompiledHopKey> directDirty = null;
			for(int directPass = 0; directPass <= current.facts().size(); directPass++) {
				List<CandidateRuleFact> directFacts = bindDirectNativeCandidateRealizations(
						directIndex, current.facts(), origins, factsByHop, nativePools,
						incrementalDirectClosure ? directDirty : null);
				directFacts = LogicalBoundaryRealizations.close(current.nodes(), constraints, origins, directFacts);
				boolean directStable = directFacts.equals(current.facts());
				if(complexityMetrics != null)
					complexityMetrics.recordDirectClosurePass(directStable, directDirty == null);
				if(directStable) {
					directConverged = true;
					break;
				}
				Set<CompiledHopKey> changedRows = changedCandidateOccurrences(current.facts(), directFacts);
				directDirty = affectedDirectClosureOccurrences(changedRows, current.nodes(), compiledEdges,
					reachingSources, current.facts(), directFacts);
				current = new CandidateReplay(current.nodes(), current.domainKeys(), directFacts,
					current.logicalInputs(), current.changedOrdinals());
				directIndex.sources().nextRevision(current.facts(), changedRows);
				nativePools = nativePools.nextRevision(current.facts(), changedRows);
			}
			if(!directConverged)
				throw new IllegalStateException("Candidate-specific direct realization closure did not converge");
			Set<CompiledHopKey> priorLoopSeeds = Collections.newSetFromMap(new IdentityHashMap<>());
			priorLoopSeeds.addAll(installedLoopSeeds);
			CandidateReplay replayed = replayUniqueCfgTransientForwards(occurrences, current.nodes(), cfg,
				factsByHop, current.domainKeys(), current.facts(), current.logicalInputs(), baseline, nativePools,
				eligibleLoopSeeds, installedLoopSeeds);
			Map<CompiledHopKey,CompiledHopKey> newlyInstalledLoopSeeds = new IdentityHashMap<>();
			for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
				CompiledHopKey readKey = current.nodes().get(ordinal).key();
				if(!installedLoopSeeds.contains(readKey) || priorLoopSeeds.contains(readKey))
					continue;
				Set<Integer> definitions = cfg.reachingDefinitions().get(ordinal);
				Integer sourceOrdinal = exactLoopPassThroughSource(ordinal, occurrences.get(ordinal),
					current.nodes().get(ordinal), definitions, occurrences, current.nodes(), factsByHop);
				if(sourceOrdinal == null)
					sourceOrdinal = exactLoopPlacementSeed(occurrences.get(ordinal), current.nodes().get(ordinal),
						definitions, occurrences, current.nodes(), factsByHop);
				if(sourceOrdinal == null)
					throw new IllegalStateException("Installed loop seed has no exact source");
				newlyInstalledLoopSeeds.put(readKey, current.nodes().get(sourceOrdinal).key());
			}
			closureTrace.add(pass + ":" + beforeSignature + "->n=" + replayed.nodes().hashCode()
				+ ",f=" + replayed.facts().hashCode() + ",l=" + replayed.logicalInputs().hashCode()
				+ ",changed=" + replayed.changedOrdinals());
			// The final relation replay of the preceding pass can change a reader
			// after its consumers were rebuilt. That delta is still pending even if
			// replay itself is now idempotent; consume it before declaring closure.
			Set<Integer> pendingPhysical = new java.util.TreeSet<>(current.changedOrdinals());
			pendingPhysical.addAll(replayed.changedOrdinals());
			if(pendingPhysical.isEmpty()) {
				List<CompiledInputEdgeFact> replayedEdges = deriveCompiledInputEdges(
					occurrences, replayed.nodes(), ordinalsByBlock, factsByHop);
				recordCompletedLoopSeedRevisions(eligibleLoopSeedRevisions,
					installedLoopSeeds, cfg, replayed.nodes(), replayed.facts(),
					replayed.logicalInputs(), replayedEdges, constraints, privacyAlreadyClosed,
					loopSeedLedger);
				return replayed;
			}
			replayed = new CandidateReplay(replayed.nodes(), replayed.domainKeys(), replayed.facts(),
				replayed.logicalInputs(), List.copyOf(pendingPhysical));
			CandidateReplay physicallyClosed = closePostCfgPhysicalCandidateDependencies(occurrences, replayed,
				factsByHop, abstractFactsByHop, singlePartitions, ordinalsByBlock, cfg,
				compiledInputEdges, origins, sourceCompiledFactsByHop);
			// Physical rebuilding deliberately restores oracle-owned base emissions and
			// therefore drops candidate-specific native bindings. Re-ground those base
			// rows before deciding whether the composed CFG transfer has converged.
			// Otherwise each pass can prove a TWrite/function result, rebuild it back to
			// staging authority, and incorrectly converge on the ungrounded midpoint.
			List<Node> physicalNodes = physicallyClosed.nodes();
			Map<CompiledHopKey,Node> physicalNodesByKey = new IdentityHashMap<>();
			for(Node node : physicalNodes)
				physicalNodesByKey.put(node.key(), node);
			List<CompiledInputEdgeFact> physicalEdges = deriveCompiledInputEdges(
				occurrences, physicalNodes, ordinalsByBlock, factsByHop);
			if(privacyAlreadyClosed) {
				PrivacyClosure filtered = closePrivacyDomains(physicallyClosed.nodes(), physicallyClosed.facts(),
					closurePrivacyConstraints, origins, physicalEdges, fixedClosurePrivacy);
				fixedClosurePrivacy = filtered.privacyFacts();
				physicallyClosed = new CandidateReplay(filtered.nodes(), physicallyClosed.domainKeys(),
					filtered.candidateRuleFacts(), bindExactLogicalTransientSourceStates(
						physicallyClosed.logicalInputs(), filtered.candidateRuleFacts()),
					physicallyClosed.changedOrdinals());
				physicalNodesByKey.clear();
				for(Node node : physicallyClosed.nodes())
					physicalNodesByKey.put(node.key(), node);
			}
			Map<CompiledHopKey,List<CompiledHopKey>> physicalReachingSources = new IdentityHashMap<>();
			for(int ordinal = 0; ordinal < occurrences.size(); ordinal++)
				if(!cfg.reachingFunctionInputs().get(ordinal) && isTransientRead(occurrences.get(ordinal).hop()))
					physicalReachingSources.put(physicalNodes.get(ordinal).key(),
						cfg.reachingDefinitions().get(ordinal).stream().sorted()
							.map(source -> physicalNodes.get(source).key()).toList());
			Map<CompiledHopKey,List<CompiledHopKey>> seedReachingSources = new IdentityHashMap<>(physicalReachingSources);
			newlyInstalledLoopSeeds.forEach((read, source) -> seedReachingSources.put(read, List.of(source)));
			NativePlacementContinuity physicalPools = new NativePlacementContinuity(physicalNodesByKey, origins,
				physicallyClosed.facts(), physicalEdges, seedReachingSources, complexityMetrics);
			DirectBindingIndex physicalDirectIndex = directBindingIndex(directTemplates, physicalNodes,
				physicalEdges, physicallyClosed.facts());
			boolean physicalDirectConverged = false;
			Set<CompiledHopKey> physicalDirectDirty = null;
			for(int directPass = 0; directPass <= physicallyClosed.facts().size(); directPass++) {
				List<CandidateRuleFact> directFacts = bindDirectNativeCandidateRealizations(
						physicalDirectIndex, physicallyClosed.facts(), origins, factsByHop, physicalPools,
						incrementalDirectClosure ? physicalDirectDirty : null);
				directFacts = LogicalBoundaryRealizations.close(physicallyClosed.nodes(), constraints, origins, directFacts);
				boolean directStable = directFacts.equals(physicallyClosed.facts());
				if(complexityMetrics != null)
					complexityMetrics.recordDirectClosurePass(directStable, physicalDirectDirty == null);
				if(directStable) {
					physicalDirectConverged = true;
					break;
				}
				Set<CompiledHopKey> changedRows = changedCandidateOccurrences(physicallyClosed.facts(), directFacts);
				physicalDirectDirty = affectedDirectClosureOccurrences(changedRows,
					physicallyClosed.nodes(), physicalEdges, physicalReachingSources,
					physicallyClosed.facts(), directFacts);
				physicallyClosed = new CandidateReplay(physicallyClosed.nodes(), physicallyClosed.domainKeys(),
					directFacts, physicallyClosed.logicalInputs(), physicallyClosed.changedOrdinals());
				physicalDirectIndex.sources().nextRevision(physicallyClosed.facts(), changedRows);
				physicalPools = physicalPools.nextRevision(physicallyClosed.facts(), changedRows);
			}
			if(!physicalDirectConverged)
				throw new IllegalStateException("Post-physical direct realization closure did not converge");
			// Direct grounding can replace normalized realization identities. Re-run the
			// exact CFG replay authority so transient compatibility edges name the current
			// source/reader realizations instead of merely filtering stale signatures.
			NativePlacementContinuity allDefinitionPools = new NativePlacementContinuity(physicalNodesByKey,
				origins, physicallyClosed.facts(), physicalEdges, physicalReachingSources, complexityMetrics);
			CandidateReplay relationClosed = replayUniqueCfgTransientForwards(occurrences,
				physicallyClosed.nodes(), cfg, factsByHop, physicallyClosed.domainKeys(),
				physicallyClosed.facts(), physicallyClosed.logicalInputs(), baseline, allDefinitionPools,
				eligibleLoopSeeds, installedLoopSeeds);
			if(privacyAlreadyClosed) {
				PrivacyClosure filtered = closePrivacyDomains(relationClosed.nodes(), relationClosed.facts(),
					closurePrivacyConstraints, origins, physicalEdges, fixedClosurePrivacy);
				relationClosed = new CandidateReplay(filtered.nodes(), relationClosed.domainKeys(),
					filtered.candidateRuleFacts(), bindExactLogicalTransientSourceStates(
							relationClosed.logicalInputs(), filtered.candidateRuleFacts()),
						relationClosed.changedOrdinals());
			}
			// Replay, physical rebuilding, direct proof grounding, and exact relation
			// regeneration are one composed transfer. Compare only that completed state.
			if(relationClosed.nodes().equals(passStart.nodes())
				&& relationClosed.domainKeys().equals(passStart.domainKeys())
				&& relationClosed.facts().equals(passStart.facts())
				&& relationClosed.logicalInputs().equals(passStart.logicalInputs())) {
				List<CompiledInputEdgeFact> relationEdges = deriveCompiledInputEdges(
					occurrences, relationClosed.nodes(), ordinalsByBlock, factsByHop);
				recordCompletedLoopSeedRevisions(eligibleLoopSeedRevisions,
					installedLoopSeeds, cfg, relationClosed.nodes(), relationClosed.facts(),
					relationClosed.logicalInputs(), relationEdges, constraints, privacyAlreadyClosed,
					loopSeedLedger);
				return relationClosed;
			}
			current = relationClosed;
		}
		throw new IllegalStateException("CFG transient candidate closure did not converge: " + closureTrace);
	}

	private record DirectBindingIndex(Map<CompiledHopKey,Node> nodesByKey,
		Map<FType,List<DurableAnchorKey>> durableAnchorsByType,
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> inputs,
		Map<DirectTemplateKey,CandidateEmissionFact> templateByKey,
		DirectSourceIndex sources) { }

	/** The per-owner source rows are private to one synchronous direct-closure loop. */
	private static final class DirectSourceIndex {
		private record SourceRow(List<CandidateRealizationReference> nativeReferences,
			Map<CandidateRealizationReference,CandidateEmissionRealization> nativeRealizations,
			Map<String,Integer> executableSignatures) { }

		private final Map<CompiledHopKey,List<Integer>> positions = new IdentityHashMap<>();
		private final Map<CompiledHopKey,SourceRow> rows = new IdentityHashMap<>();
		private final Map<CandidateRealizationReference,CandidateEmissionRealization> nativeRealizations =
			new LinkedHashMap<>();
		private final Map<CandidateRealizationReference,CompiledHopKey> nativeOwners = new LinkedHashMap<>();
		private final Map<String,Integer> executableSignatures = new LinkedHashMap<>();
		private int factCount;
		private boolean duplicateNativeOwners;

		private DirectSourceIndex(List<CandidateRuleFact> facts) { rebuild(facts); }

		private List<CandidateRealizationReference> nativeByParent(CompiledHopKey parent) {
			SourceRow row = rows.get(parent);
			return row == null ? List.of() : row.nativeReferences();
		}

		private CandidateEmissionRealization nativeRealization(CandidateRealizationReference reference) {
			return nativeRealizations.get(reference);
		}

		private boolean executable(String signature) {
			return executableSignatures.containsKey(signature);
		}

		private void nextRevision(List<CandidateRuleFact> facts, Set<CompiledHopKey> changed) {
			if(changed.isEmpty())
				return;
			if(duplicateNativeOwners || facts.size() != factCount) {
				rebuild(facts);
				return;
			}
			Map<CompiledHopKey,SourceRow> staged = new IdentityHashMap<>();
			Map<CandidateRealizationReference,CompiledHopKey> stagedOwners = new LinkedHashMap<>();
			for(CompiledHopKey owner : changed) {
				List<Integer> ownerPositions = positions.get(owner);
				if(ownerPositions == null || ownerPositions.stream().anyMatch(position ->
					facts.get(position).key().parentOccurrence() != owner)) {
					rebuild(facts);
					return;
				}
				SourceRow row = sourceRow(facts, ownerPositions);
				staged.put(owner, row);
				for(CandidateRealizationReference reference : row.nativeRealizations().keySet()) {
					CompiledHopKey retained = nativeOwners.get(reference);
					CompiledHopKey inserted = stagedOwners.putIfAbsent(reference, owner);
					if(retained != null && retained != owner && !changed.contains(retained)
						|| inserted != null && inserted != owner) {
						rebuild(facts); // Preserve the original global last-wins rule on duplicate keys.
						return;
					}
				}
			}
			// All replacement rows are complete before publication; no binder can see a mixed revision.
			for(CompiledHopKey owner : changed) {
				SourceRow old = rows.get(owner);
				for(CandidateRealizationReference reference : old.nativeRealizations().keySet()) {
					nativeRealizations.remove(reference);
					nativeOwners.remove(reference);
				}
				old.executableSignatures().forEach((signature, count) ->
					executableSignatures.computeIfPresent(signature, (ignored, prior) ->
						prior.intValue() == count.intValue() ? null : prior - count));
			}
			for(CompiledHopKey owner : changed) {
				SourceRow row = staged.get(owner);
				rows.put(owner, row);
				row.nativeRealizations().forEach((reference, realization) -> {
					nativeRealizations.put(reference, realization);
					nativeOwners.put(reference, owner);
				});
				row.executableSignatures().forEach((signature, count) ->
					executableSignatures.merge(signature, count, Integer::sum));
			}
		}

		private void rebuild(List<CandidateRuleFact> facts) {
			positions.clear();
			rows.clear();
			nativeRealizations.clear();
			nativeOwners.clear();
			executableSignatures.clear();
			factCount = facts.size();
			duplicateNativeOwners = false;
			for(int index = 0; index < facts.size(); index++)
				positions.computeIfAbsent(facts.get(index).key().parentOccurrence(),
					ignored -> new ArrayList<>()).add(index);
			for(var entry : positions.entrySet())
				rows.put(entry.getKey(), sourceRow(facts, entry.getValue()));
			// The original map was filled in global fact order, including any duplicate
			// realization key. Retain that last-wins behavior in the conservative path.
			for(CandidateRuleFact fact : facts)
				if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
						for(CandidateEmissionRealization realization : emission.realizations()) {
							CandidateRealizationReference reference = CandidateRealizationReference.of(
								fact.key(), realization);
							if(executableSourceRealization(fact.key(), realization))
								executableSignatures.merge(reference.normalizedSignature(), 1, Integer::sum);
							if(realization.key().emissionState().placementState().output() == FederatedOutput.FOUT) {
								CompiledHopKey owner = fact.key().parentOccurrence();
								CompiledHopKey previous = nativeOwners.put(reference, owner);
								duplicateNativeOwners |= previous != null && previous != owner;
								nativeRealizations.put(reference, realization);
							}
						}
		}

		private static SourceRow sourceRow(List<CandidateRuleFact> facts, List<Integer> ownerPositions) {
			List<CandidateRealizationReference> nativeReferences = new ArrayList<>();
			Map<CandidateRealizationReference,CandidateEmissionRealization> nativeRealizations =
				new LinkedHashMap<>();
			Map<String,Integer> executableSignatures = new LinkedHashMap<>();
			for(int position : ownerPositions) {
				CandidateRuleFact fact = facts.get(position);
				if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations()) {
						CandidateRealizationReference reference = CandidateRealizationReference.of(
							fact.key(), realization);
						if(executableSourceRealization(fact.key(), realization))
							executableSignatures.merge(reference.normalizedSignature(), 1, Integer::sum);
						if(realization.key().emissionState().placementState().output() == FederatedOutput.FOUT) {
							nativeReferences.add(reference);
							nativeRealizations.put(reference, realization);
						}
					}
			}
			return new SourceRow(List.copyOf(nativeReferences), Map.copyOf(nativeRealizations),
				Map.copyOf(executableSignatures));
		}
	}

	/** Node topology, edges and templates are invariant during each direct closure loop. */
	private static DirectBindingIndex directBindingIndex(List<CandidateRuleFact> templates,
		List<Node> nodes, List<CompiledInputEdgeFact> compiledEdges, List<CandidateRuleFact> facts) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<FType,List<DurableAnchorKey>> mutableDurableAnchorsByType = new java.util.EnumMap<>(FType.class);
		for(Node node : nodes)
			for(DurableAnchorKey anchor : node.anchors())
				mutableDurableAnchorsByType.computeIfAbsent(anchor.fType(), ignored -> new ArrayList<>()).add(anchor);
		Map<FType,List<DurableAnchorKey>> durableAnchorsByType = new java.util.EnumMap<>(FType.class);
		for(Map.Entry<FType,List<DurableAnchorKey>> entry : mutableDurableAnchorsByType.entrySet())
			durableAnchorsByType.put(entry.getKey(), List.copyOf(entry.getValue()));
		durableAnchorsByType = Collections.unmodifiableMap(durableAnchorsByType);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> inputs = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledEdges)
			inputs.computeIfAbsent(edge.consumer(), ignored -> new java.util.TreeMap<>())
				.put(edge.inputPosition(), edge.producer());
		Map<DirectTemplateKey,CandidateEmissionFact> templateByKey = new LinkedHashMap<>();
		for(CandidateRuleFact template : templates)
			for(CandidateEmissionFact templateEmission : template.allowedEmissionFacts())
				templateByKey.putIfAbsent(new DirectTemplateKey(template.key(),
					templateEmission.emissionState(), templateEmission.derivedFoutAction()), templateEmission);
		return new DirectBindingIndex(nodesByKey, durableAnchorsByType, inputs, templateByKey,
			new DirectSourceIndex(facts));
	}

	/** Materializes candidate-specific direct native input authority before CFG replay. */
	private List<CandidateRuleFact> bindDirectNativeCandidateRealizations(
		DirectBindingIndex index, List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes,
		NativePlacementContinuity continuity, Set<CompiledHopKey> dirtyOccurrences) {
		Map<CompiledHopKey,Node> nodesByKey = index.nodesByKey();
		Map<FType,List<DurableAnchorKey>> durableAnchorsByType = index.durableAnchorsByType();
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> inputs = index.inputs();
		Map<DirectTemplateKey,CandidateEmissionFact> templateByKey = index.templateByKey();
		DirectSourceIndex sources = index.sources();
		List<CandidateRuleFact> rebound = new ArrayList<>(facts.size());
		if(dirtyOccurrences != null && complexityMetrics != null)
			complexityMetrics.recordIncrementalPass();
		for(CandidateRuleFact fact : facts) {
			if(dirtyOccurrences != null && !dirtyOccurrences.contains(fact.key().parentOccurrence())) {
				if(complexityMetrics != null)
					complexityMetrics.recordIncrementalFact(false);
				rebound.add(fact);
				continue;
			}
			if(dirtyOccurrences != null && complexityMetrics != null)
				complexityMetrics.recordIncrementalFact(true);
			// A compiled TRead aliases the exact CFG relation, not a physical Hop
			// input. Its native lineages and durable identities are owned by replay;
			// grounding it as a computation invents a reader-local native-output map
			// that the next replay replaces, leaving downstream bindings stale.
			Hop factOwner = origins.get(fact.key().parentOccurrence());
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| factOwner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD
				|| fact.key().orderedInputs().stream().noneMatch(CandidateInputState::present)) {
				rebound.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			boolean unchangedEmissions = true;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				CandidateEmissionFact templateEmission = templateByKey.get(new DirectTemplateKey(
					fact.key(), emission.emissionState(), emission.derivedFoutAction()));
				if(complexityMetrics != null)
					complexityMetrics.recordTemplateLookup(templateEmission == null ? 0 : 1);
				if(templateEmission == null)
					templateEmission = emission;
				Hop owner = origins.get(fact.key().parentOccurrence());
				PlacementState outputState = emission.emissionState().placementState();
				boolean recomputeNative = outputState.execType() == ExecType.FED
					&& outputState.output() == FederatedOutput.FOUT && emission.derivedFoutAction() == null
					&& !(owner instanceof DataOp data && (data.getOp() == OpOpData.FEDERATED
						|| data.getOp() == OpOpData.TRANSIENTREAD));
				// These exact objects would only be copied into a new emission and
				// rule. Reuse them when there is no native template to ground; the
				// identity-preserving path cannot remove or merge an alternative.
				if(templateEmission == emission && !recomputeNative
					&& (emission.derivedFoutAction() != null
						|| templateEmission.realizations().stream().noneMatch(realization ->
							realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
								&& realization.supportClauses().stream().noneMatch(
									clause -> !clause.inputBindings().isEmpty())))) {
					emissions.add(emission);
					continue;
				}
				unchangedEmissions = false;
				List<CandidateEmissionRealization> templatesForEmission = recomputeNative
					? List.of(CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"direct-template:" + emission.emissionState().normalizedSignature(), List.of(), List.of()))
					: templateEmission.realizations();
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				if(recomputeNative)
					for(CandidateEmissionRealization candidate : emission.realizations()) {
						List<CandidateRealizationSupportClause> relocationClauses = candidate.supportClauses().stream()
							.filter(clause -> clause.inputBindings().stream()
								.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.RELOCATION))
							.toList();
						if(!relocationClauses.isEmpty())
							realizations.add(CandidateEmissionRealization
								.fromAlreadyCanonicalSupportClauses(candidate.key(), relocationClauses));
					}
				for(CandidateEmissionRealization realization : templatesForEmission) {
					if(realization.key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
						|| realization.supportClauses().stream().anyMatch(clause -> !clause.inputBindings().isEmpty())
						|| emission.derivedFoutAction() != null) {
						realizations.add(realization);
						continue;
					}
					List<CandidateEmissionRealization> bound = new ArrayList<>();
					if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE
						&& fact.key().orderedInputs().size() == 1 && fact.key().orderedInputs().get(0).present()) {
						CompiledHopKey sourceKey = inputs.getOrDefault(fact.key().parentOccurrence(), Map.of()).get(0);
						FType inputType = fact.key().orderedInputs().get(0).fType();
						// A write aliases its selected input Data object. It does not create a
						// new native-output map, nor copy the input's transitive proof clauses.
						for(CandidateRealizationReference source : sources.nativeByParent(sourceKey)) {
							CandidateEmissionRealization sourceRealization = sources.nativeRealization(source);
							for(CandidateRealizationSupportClause sourceClause : sourceRealization.supportClauses()) {
								DurableAnchorKey pool = sourceRealization
									.nativeWorkerPoolResidencyForOwnedClause(sourceClause);
								if(pool == null || pool.fType() != inputType || outputState.fType() != inputType)
									continue;
								DurableAnchorKey anchor = sourceRealization.anchor();
								PlacementProofKey proof = new PlacementProofKey(anchor == null
									? PlacementProofKind.NATIVE_CONTINUITY : PlacementProofKind.DURABLE_ANCHOR,
									fact.key().parentOccurrence(), "transient-alias:" + pool.normalizedSignature());
								List<CandidateRealizationInputBinding> binding = List.of(CandidateRealizationInputBinding.direct(0, source));
								if(anchor != null)
									bound.add(CandidateEmissionRealization.durable(emission.emissionState(), anchor,
										List.of(proof), binding));
								else if(sourceRealization.nativeWorkerPoolLayoutExactForOwnedClause(sourceClause))
									bound.add(CandidateEmissionRealization.nativeLineage(emission.emissionState(),
										"transient-alias:" + fact.key().parentOccurrence().normalizedSignature()
											+ "|pool=" + pool.normalizedSignature(), pool, List.of(proof), binding));
								else
									bound.add(CandidateEmissionRealization.nativeLineageDynamicLayout(emission.emissionState(),
										"transient-alias:" + fact.key().parentOccurrence().normalizedSignature()
											+ "|pool=" + pool.normalizedSignature(), pool, List.of(proof), binding));
							}
						}
						realizations.addAll(bound.isEmpty() ? List.of(realization) : bound);
						continue;
					}
					List<DurableAnchorKey> seeds = new ArrayList<>();
					Set<FType> presentTypes = fact.key().orderedInputs().stream()
						.filter(CandidateInputState::present).map(CandidateInputState::fType)
						.collect(java.util.stream.Collectors.toSet());
					// PART/OTHER are deliberately not durable/native seeds. A supported unary
					// operation can still execute on the exact literal-source route, so retain
					// that direct lineage without publishing worker-pool authority.
					List<Integer> presentPositions = java.util.stream.IntStream
						.range(0, fact.key().orderedInputs().size()).boxed()
						.filter(position -> fact.key().orderedInputs().get(position).present()).toList();
					if((outputState.fType() == FType.PART || outputState.fType() == FType.OTHER)
						&& presentPositions.size() == 1) {
						int position = presentPositions.get(0);
						CompiledHopKey sourceKey = inputs.getOrDefault(fact.key().parentOccurrence(), Map.of())
							.get(position);
						for(CandidateRealizationReference source : sources.nativeByParent(sourceKey))
							if(source.realization().layoutKind() == PlacementLayoutKind.SOURCE_LINEAGE
								&& source.realization().emissionState().placementState().fType()
									== fact.key().orderedInputs().get(position).fType())
								bound.add(CandidateEmissionRealization.sourceLineage(emission.emissionState(),
									"direct-source:" + fact.key().parentOccurrence().normalizedSignature()
										+ "|input=" + source.normalizedSignature(),
									List.of(CandidateRealizationInputBinding.direct(position, source))));
					}
					// The immediate producer of a TWrite or a map-changing native
					// operation need not itself own a durable map. Try every exact
					// analysis anchor of a compatible input type; candidate continuity
					// proves (or rejects) the complete path back to that seed.
					for(FType presentType : presentTypes)
						seeds.addAll(durableAnchorsByType.getOrDefault(presentType, List.of()));
					for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
						CandidateInputState input = fact.key().orderedInputs().get(position);
						CompiledHopKey sourceKey = inputs.getOrDefault(fact.key().parentOccurrence(), Map.of())
							.get(position);
						Node source = sourceKey == null ? null : nodesByKey.get(sourceKey);
						if(input.present() && source != null) {
							source.anchors().stream().filter(anchor -> anchor.fType() == input.fType())
								.forEach(seeds::add);
							// A native producer such as t(X) owns a new exact map, although
							// its graph node has no literal-source anchor. Seed its immediate
							// consumer from that exact executable realization rather than
							// looking only at original ROW source anchors.
							for(CandidateRealizationReference reference : sources.nativeByParent(sourceKey)) {
								CandidateEmissionRealization sourceRealization = sources.nativeRealization(reference);
								if(reference.realization().emissionState().placementState().fType() == input.fType()
									&& sourceRealization != null && sourceRealization.anchor() != null
									&& sources.executable(reference.normalizedSignature()))
									seeds.add(sourceRealization.anchor());
							}
						}
					}
					for(DurableAnchorKey seed : seeds.stream().distinct().sorted().toList()) {
						DurableAnchorKey outputAnchor = transposeChangesPartitionAxis(
							owner, seed.fType(), outputState.fType())
							? transposedNativeOutputAnchor(seed, outputState.fType(),
								fact.key().parentOccurrence())
							: nativeOutputAnchor(seed, outputState.fType(), shapes.get(owner),
								fact.key().parentOccurrence());
						boolean dynamicOutputLayout = NativePlacementContinuity.recomputesNativePartitionRanges(
							owner, outputState.fType());
						if(dynamicOutputLayout)
							outputAnchor = null;
						if(outputAnchor == null && seed.fType() != outputState.fType() && !dynamicOutputLayout)
							continue;
						String nativeLineage = "direct-native:" + fact.key().parentOccurrence().normalizedSignature()
							+ "|seed=" + seed.normalizedSignature();
						CandidateRealizationReference output = new CandidateRealizationReference(fact.key(),
							outputAnchor != null
								? PlacementIdentity.PlacementRealizationKey.durable(
									emission.emissionState(), outputAnchor)
								: PlacementIdentity.PlacementRealizationKey.nativeLineage(
									emission.emissionState(), nativeLineage));
						// Derive direct publication from the primitive candidate rule. A prior
						// publication of this same output is a result, never a proof premise.
						for(NativePlacementContinuity.NativeContinuityProof proof :
							continuity.provePrimitiveCandidateAlternatives(output, seed)) {
							List<CandidateRealizationInputBinding> bindings =
								new ArrayList<>(proof.immediateBindings());
							boolean complete = true;
							for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
								CandidateInputState input = fact.key().orderedInputs().get(position);
								if(!input.present())
									continue;
								CompiledHopKey sourceKey = inputs.getOrDefault(fact.key().parentOccurrence(), Map.of())
									.get(position);
								Hop sourceHop = sourceKey == null ? null : origins.get(sourceKey);
								if(sourceHop == null || !isPlacementDataShape(shapes, sourceHop))
									continue;
								int inputPosition = position;
								CompiledHopKey sourceOccurrence = sourceKey;
								CandidateRealizationInputBinding binding = bindings.stream()
									.filter(candidate -> candidate.inputPosition() == inputPosition
										&& candidate.source().rule().parentOccurrence() == sourceOccurrence
										&& candidate.source().realization().emissionState().placementState().fType()
											== input.fType()
										&& sources.executable(candidate.source().normalizedSignature()))
									.findFirst().orElse(null);
								if(binding == null) {
									complete = false;
									break;
								}
							}
							if(complete) {
								PlacementProofKey continuityProof = new PlacementProofKey(
									PlacementProofKind.NATIVE_CONTINUITY, fact.key().parentOccurrence(),
									proof.normalizedSignature());
								boolean directInputsExact = bindings.stream().allMatch(binding -> {
									if(binding.kind() != CandidateInputBindingKind.DIRECT)
										return true;
									CandidateEmissionRealization source = sources.nativeRealization(binding.source());
									return source != null
										&& source.allOwnedSupportClausesHaveExactNativeLayout();
								});
								if(outputAnchor != null && proof.exactPartitionRanges() && directInputsExact)
									for(CandidateRealizationSupportClause clause : realization.supportClauses())
										bound.add(CandidateEmissionRealization.durable(emission.emissionState(), outputAnchor,
											appendProof(clause.proofDependencies(), continuityProof), bindings));
								else {
									DurableAnchorKey outputPool = proof.outputWorkerPoolWitness();
									// Keep the proved output FType and worker endpoints even when the
									// runtime recomputes partition extents or changes the partition axis.
									bound.add(proof.exactPartitionRanges()
										? CandidateEmissionRealization.nativeLineage(emission.emissionState(),
											nativeLineage, outputPool, List.of(continuityProof), bindings)
										: CandidateEmissionRealization.nativeLineageDynamicLayout(emission.emissionState(),
											nativeLineage, outputPool, List.of(continuityProof), bindings));
								}
							}
						}
					}
					// Keep a generic lineage only as staging authority when no exact direct
					// realization is currently provable. It is excluded from transient replay.
					if(bound.isEmpty() && FederatedPlannerTrace.shouldTrace(owner))
						FederatedPlannerTrace.log(owner, "Neutral-UnboundNative", "inputs=" + fact.key().orderedInputs()
							+ "|state=" + outputState + "|shape=" + shapes.get(owner)
							+ "|seeds=" + seeds.stream().distinct().toList());
					if(bound.isEmpty())
						realizations.add(realization);
					else
						realizations.addAll(bound);
				}
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
					emission.derivedFoutAction(), realizations));
			}
			rebound.add(unchangedEmissions ? fact : new CandidateRuleFact(fact.key(), fact.status(),
				fact.capability(), fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(rebound);
	}

	private static Set<CompiledHopKey> changedCandidateOccurrences(List<CandidateRuleFact> before,
		List<CandidateRuleFact> after) {
		Map<CompiledHopKey,List<CandidateRuleFact>> beforeByOwner = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateRuleFact>> afterByOwner = new IdentityHashMap<>();
		for(CandidateRuleFact fact : before)
			beforeByOwner.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		for(CandidateRuleFact fact : after)
			afterByOwner.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
		owners.addAll(beforeByOwner.keySet());
		owners.addAll(afterByOwner.keySet());
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey owner : owners)
			if(!beforeByOwner.getOrDefault(owner, List.of()).equals(
				afterByOwner.getOrDefault(owner, List.of())))
				changed.add(owner);
		return changed;
	}

	/**
	 * Revision-local fact-only dirty cone for direct binding. In this loop nodes,
	 * compiled inputs and CFG reaching sources are fixed; a candidate row reads
	 * its producers, never the other way round. Logical function carriers are
	 * closed over all facts separately before the changed rows are measured.
	 * Include both old and new support edges so removing a source invalidates
	 * its former consumers. Value-version aliases still share one value and are
	 * deliberately expanded in both directions.
	 */
	private static Set<CompiledHopKey> affectedDirectClosureOccurrences(Set<CompiledHopKey> changed,
		List<Node> nodes, List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingSources,
		List<CandidateRuleFact> beforeFacts, List<CandidateRuleFact> afterFacts) {
		if(changed.isEmpty())
			return Set.of();
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacent = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledEdges)
			addIdentityDependency(adjacent, edge.producer(), edge.consumer());
		for(var entry : reachingSources.entrySet())
			for(CompiledHopKey source : entry.getValue())
				addIdentityDependency(adjacent, source, entry.getKey());
		addDirectSupportDependencies(adjacent, beforeFacts);
		addDirectSupportDependencies(adjacent, afterFacts);
		Map<ValueVersionKey,List<CompiledHopKey>> aliases = new LinkedHashMap<>();
		for(Node node : nodes)
			aliases.computeIfAbsent(node.valueVersion(), ignored -> new ArrayList<>()).add(node.key());
		Map<CompiledHopKey,List<CompiledHopKey>> aliasMembers = new IdentityHashMap<>();
		for(List<CompiledHopKey> members : aliases.values())
			for(CompiledHopKey member : members)
				aliasMembers.put(member, members);

		Set<CompiledHopKey> affected = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.ArrayDeque<CompiledHopKey> pending = new java.util.ArrayDeque<>();
		for(CompiledHopKey occurrence : changed)
			if(affected.add(occurrence))
				pending.addLast(occurrence);
		while(!pending.isEmpty()) {
			CompiledHopKey occurrence = pending.removeFirst();
			for(CompiledHopKey neighbor : adjacent.getOrDefault(occurrence, Set.of()))
				if(affected.add(neighbor))
					pending.addLast(neighbor);
			for(CompiledHopKey alias : aliasMembers.getOrDefault(occurrence, List.of()))
				if(affected.add(alias))
					pending.addLast(alias);
		}
		return affected;
	}

	private static void addDirectSupportDependencies(Map<CompiledHopKey,Set<CompiledHopKey>> adjacent,
		List<CandidateRuleFact> facts) {
		for(CandidateRuleFact fact : facts)
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations())
					for(CandidateRealizationSupportClause clause : realization.supportClauses())
						for(CandidateRealizationInputBinding binding : clause.inputBindings())
							addIdentityDependency(adjacent, binding.source().rule().parentOccurrence(),
								fact.key().parentOccurrence());
	}

	private static void addIdentityDependency(Map<CompiledHopKey,Set<CompiledHopKey>> dependents,
		CompiledHopKey producer, CompiledHopKey consumer) {
		dependents.computeIfAbsent(producer,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(consumer);
	}

	/**
	 * Final analysis-scoped factorization of immutable AND support rows. Clause objects remain
	 * owner-unique because receipts validate them by identity; only identity-compatible proof,
	 * binding, and whole-list substructures are shared.
	 */
	private List<CandidateRuleFact> factorizeCandidateSupportRelations(List<CandidateRuleFact> facts) {
		SearchSpaceMetrics.PhaseToken phaseStarted =
			complexityMetrics == null ? null : complexityMetrics.startPhase(
				SearchSpaceMetrics.Phase.CLAUSE_MERGE_CANONICALIZATION);
		try {
			return factorizeCandidateSupportRelationsMeasured(facts);
		}
		finally {
			if(complexityMetrics != null)
				complexityMetrics.finishPhase(
					SearchSpaceMetrics.Phase.CLAUSE_MERGE_CANONICALIZATION, phaseStarted);
		}
	}

	private List<CandidateRuleFact> factorizeCandidateSupportRelationsMeasured(
		List<CandidateRuleFact> facts) {
		Map<FactorizedProofKey,PlacementProofKey> proofPool = new java.util.HashMap<>();
		Map<FactorizedBindingKey,CandidateRealizationInputBinding> bindingPool = new java.util.HashMap<>();
		Map<IdentityListKey<PlacementProofKey>,List<PlacementProofKey>> proofListPool = new java.util.HashMap<>();
		Map<IdentityListKey<CandidateRealizationInputBinding>,List<CandidateRealizationInputBinding>>
			bindingListPool = new java.util.HashMap<>();
		List<CandidateRuleFact> factorized = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						List<PlacementProofKey> proofs = new ArrayList<>(clause.proofDependencies().size());
						boolean proofsChanged = false;
						for(PlacementProofKey proof : clause.proofDependencies()) {
							FactorizedProofKey key = new FactorizedProofKey(proof);
							PlacementProofKey shared = proofPool.putIfAbsent(key, proof);
							if(shared == null)
								shared = proof;
							else {
								proofsChanged |= shared != proof;
								if(complexityMetrics != null)
									complexityMetrics.recordFactorizedProofObjectReuse();
							}
							proofs.add(shared);
						}
						List<CandidateRealizationInputBinding> bindings =
							new ArrayList<>(clause.inputBindings().size());
						boolean bindingsChanged = false;
						for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
							FactorizedBindingKey key = new FactorizedBindingKey(binding);
							CandidateRealizationInputBinding shared = bindingPool.putIfAbsent(key, binding);
							if(shared == null)
								shared = binding;
							else {
								bindingsChanged |= shared != binding;
								if(complexityMetrics != null)
									complexityMetrics.recordFactorizedBindingObjectReuse();
							}
							bindings.add(shared);
						}
						List<PlacementProofKey> sharedProofs = proofsChanged
							? PlacementAnalysis.sharedAlreadyCanonicalComparableList(
								proofs, "realization proof dependency") : clause.proofDependencies();
						IdentityListKey<PlacementProofKey> proofListKey = new IdentityListKey<>(sharedProofs);
						List<PlacementProofKey> existingProofs = proofListPool.putIfAbsent(proofListKey, sharedProofs);
						if(existingProofs != null) {
							sharedProofs = existingProofs;
							if(complexityMetrics != null)
								complexityMetrics.recordFactorizedProofListReuse();
						}
						List<CandidateRealizationInputBinding> sharedBindings = bindingsChanged
							? PlacementAnalysis.sharedAlreadyCanonicalComparableList(
								bindings, "realization input binding") : clause.inputBindings();
						IdentityListKey<CandidateRealizationInputBinding> bindingListKey =
							new IdentityListKey<>(sharedBindings);
						List<CandidateRealizationInputBinding> existingBindings =
							bindingListPool.putIfAbsent(bindingListKey, sharedBindings);
						if(existingBindings != null) {
							sharedBindings = existingBindings;
							if(complexityMetrics != null)
								complexityMetrics.recordFactorizedBindingListReuse();
						}
						clauses.add(sharedProofs == clause.proofDependencies()
								&& sharedBindings == clause.inputBindings() ? clause
							: new CandidateRealizationSupportClause(sharedProofs, sharedBindings,
								clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact()));
						if(complexityMetrics != null)
							complexityMetrics.recordFactorizedClause();
					}
					boolean unchanged = clauses.size() == realization.supportClauses().size();
					for(int index = 0; unchanged && index < clauses.size(); index++)
						unchanged = clauses.get(index) == realization.supportClauses().get(index);
					realizations.add(unchanged ? realization : CandidateEmissionRealization
						.fromAlreadyCanonicalSupportClauses(realization.key(), clauses));
				}
				boolean unchanged = realizations.size() == emission.realizations().size();
				for(int index = 0; unchanged && index < realizations.size(); index++)
					unchanged = realizations.get(index) == emission.realizations().get(index);
				emissions.add(unchanged ? emission : new CandidateEmissionFact(
					emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(), realizations));
			}
			boolean unchanged = emissions.size() == fact.allowedEmissionFacts().size();
			for(int index = 0; unchanged && index < emissions.size(); index++)
				unchanged = emissions.get(index) == fact.allowedEmissionFacts().get(index);
			factorized.add(unchanged ? fact : new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(factorized);
	}

	private static final class FactorizedProofKey {
		private final PlacementProofKey proof;
		private final int hashCode;
		private FactorizedProofKey(PlacementProofKey proof) {
			this.proof = proof;
			hashCode = 31 * proof.hashCode() + System.identityHashCode(proof.owner());
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof FactorizedProofKey that
				&& proof.owner() == that.proof.owner() && proof.equals(that.proof);
		}
	}

	private static final class FactorizedBindingKey {
		private final CandidateRealizationInputBinding binding;
		private final int hashCode;
		private FactorizedBindingKey(CandidateRealizationInputBinding binding) {
			this.binding = binding;
			hashCode = Objects.hash(binding, System.identityHashCode(
				binding.source().rule().parentOccurrence()),
				System.identityHashCode(binding.relocationAction()));
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof FactorizedBindingKey that
				&& binding.source().rule().parentOccurrence()
					== that.binding.source().rule().parentOccurrence()
				&& binding.relocationAction() == that.binding.relocationAction()
				&& binding.equals(that.binding);
		}
	}

	private static final class IdentityListKey<T> {
		private final List<T> values;
		private final int hashCode;
		private IdentityListKey(List<T> values) {
			this.values = values;
			int hash = 1;
			for(T value : values)
				hash = 31 * hash + System.identityHashCode(value);
			hashCode = hash;
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof IdentityListKey<?> that) || values.size() != that.values.size())
				return false;
			for(int index = 0; index < values.size(); index++)
				if(values.get(index) != that.values.get(index))
					return false;
			return true;
		}
	}

	private static DurableAnchorKey nativeOutputAnchor(DurableAnchorKey seed, FType outputType,
		NodeShapeFact outputShape, CompiledHopKey owner) {
		if(outputShape == null || !(outputShape.dataType().isMatrix() || outputShape.dataType().isFrame())
			|| outputShape.rows() <= 0 || outputShape.cols() <= 0 || outputType == null
			|| outputType == FType.PART || outputType == FType.OTHER)
			return null;
		List<AnchorPartition> partitions = new ArrayList<>();
		for(AnchorPartition source : seed.partitions()) {
			if(source.begin().size() != 2 || source.end().size() != 2)
				return null;
			long begin, end;
			if(outputType == FType.ROW) {
				int sourceAxis = seed.fType() == FType.COL ? 1 : 0;
				begin = source.begin().get(sourceAxis);
				end = source.end().get(sourceAxis);
				if(end > outputShape.rows())
					return null;
				partitions.add(new AnchorPartition(source.workerId(), List.of(begin, 0L),
					List.of(end, outputShape.cols())));
			}
			else if(outputType == FType.COL) {
				int sourceAxis = seed.fType() == FType.ROW ? 0 : 1;
				begin = source.begin().get(sourceAxis);
				end = source.end().get(sourceAxis);
				if(end > outputShape.cols())
					return null;
				partitions.add(new AnchorPartition(source.workerId(), List.of(0L, begin),
					List.of(outputShape.rows(), end)));
			}
			else if(outputType == FType.BROADCAST) {
				partitions.add(new AnchorPartition(source.workerId(), List.of(0L, 0L),
					List.of(outputShape.rows(), outputShape.cols())));
			}
			else {
				if(seed.partitions().size() != 1)
					return null;
				partitions.add(new AnchorPartition(source.workerId(), List.of(0L, 0L),
					List.of(outputShape.rows(), outputShape.cols())));
			}
		}
		return new DurableAnchorKey("native-output:" + owner.normalizedSignature(), outputType, partitions);
	}

	private static boolean transposeChangesPartitionAxis(Hop owner, FType inputType, FType outputType) {
		return owner instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS
			&& (inputType == FType.ROW && outputType == FType.COL
				|| inputType == FType.COL && outputType == FType.ROW);
	}

	/**
	 * The FED transpose runtime publishes {@code FederationMap.transpose()}, so its
	 * output ranges are the exact coordinate-wise transpose of the selected input
	 * map even when the function-local Hop dimensions are still unknown.
	 */
	private static DurableAnchorKey transposedNativeOutputAnchor(DurableAnchorKey seed,
		FType outputType, CompiledHopKey owner) {
		if(seed == null || !((seed.fType() == FType.ROW && outputType == FType.COL)
			|| (seed.fType() == FType.COL && outputType == FType.ROW)))
			return null;
		List<AnchorPartition> partitions = new ArrayList<>(seed.partitions().size());
		for(AnchorPartition source : seed.partitions()) {
			if(source.begin().size() != 2 || source.end().size() != 2)
				return null;
			partitions.add(new AnchorPartition(source.workerId(),
				List.of(source.begin().get(1), source.begin().get(0)),
				List.of(source.end().get(1), source.end().get(0))));
		}
		return new DurableAnchorKey("native-transpose:" + owner.normalizedSignature(),
			outputType, partitions);
	}

	private static DurableAnchorKey nativeResidencyWitness(DurableAnchorKey seed, FType outputType,
		CompiledHopKey owner) {
		if(seed == null || outputType == null || outputType == FType.PART || outputType == FType.OTHER
			|| outputType == FType.FULL && seed.partitions().size() != 1)
			return null;
		return new DurableAnchorKey("native-residency:" + owner.normalizedSignature(), outputType,
			seed.partitions());
	}

	private CandidateReplay replayUniqueCfgTransientForwards(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes, CfgAnalysis cfg,
		Map<Hop,NodeShapeFact> factsByHop, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> existingLogicalInputs,
		CfgReplayBaseline baseline, NativePlacementContinuity nativePools,
		Set<CompiledHopKey> eligibleLoopSeeds, Set<CompiledHopKey> installedLoopSeeds) {
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
		Set<Integer> copiedSlots = new HashSet<>();
		Set<CompiledHopKey> replacedParents = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Integer> changedOrdinals = new ArrayList<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			Node node = nodes.get(ordinal);
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			boolean cfgRead = PlacementAnalysis.isCompiledTransientAccess(
				occurrence.hop(), node, OpOpData.TRANSIENTREAD)
				&& !cfg.reachingFunctionInputs().get(ordinal)
				&& !cfg.reachingDefinitions().get(ordinal).isEmpty();
			boolean hadPriorReplay = cfgRead && logicalInputs.stream()
				.anyMatch(input -> input.targetRead() == node.key());
			List<LogicalTransientInputFact> priorInputs = logicalInputs.stream()
				.filter(input -> input.targetRead() == node.key()).sorted().toList();
			List<LogicalTransientInputFact> replacementInputs = new ArrayList<>();
			int replacementStart = replayedKeys.size();
			Node replayed = replayUniqueCfgTransientForward(ordinal, occurrence, node, occurrences, nodes, cfg,
				factsByHop, facts, replayedKeys, replayedFacts, replacementInputs,
				// Prior replay may have seen only the local source. A later function-boundary
				// widening can expose the native source, so seed once per read in this
				// closure invocation and then publish the all-definition replay below.
				eligibleLoopSeeds.contains(node.key()) && !installedLoopSeeds.contains(node.key()),
				nativePools, installedLoopSeeds);
			boolean replayedParent = replayed != node;
			if(hadPriorReplay && !replayedParent) {
				Node original = baseline.nodes().get(node.key());
				List<CandidateRuleKey> originalKeys = baseline.domainKeys().get(node.key());
				List<CandidateRuleFact> originalFacts = baseline.facts().get(node.key());
				if(original == null || originalKeys == null || originalFacts == null)
					throw new IllegalStateException("CFG replay baseline is incomplete");
				replayed = original;
				replayedKeys.addAll(originalKeys);
				replayedFacts.addAll(originalFacts);
				replayedParent = true;
			}
			if(replayedParent) {
				logicalInputs.removeIf(input -> input.targetRead() == node.key());
				logicalInputs.addAll(replacementInputs);
			}
			List<Integer> priorSlots = candidateSlots.getOrDefault(node.key(), List.of());
			List<CandidateRuleKey> priorKeys = priorSlots.stream().map(domainKeys::get).toList();
			List<CandidateRuleFact> priorFacts = priorSlots.stream().map(facts::get).toList();
			List<CandidateRuleKey> replacementKeys = replayedParent
				? List.copyOf(replayedKeys.subList(replacementStart, replayedKeys.size())) : List.of();
			List<CandidateRuleFact> replacementFacts = replayedParent
				? List.copyOf(replayedFacts.subList(replacementStart, replayedFacts.size())) : List.of();
			replayedNodes.add(replayedParent && replayed.equals(node) ? node : replayed);
			if(replayedParent) {
				replacedParents.add(node.key());
				if(!replayed.equals(node) || !replacementKeys.equals(priorKeys)
					|| !replacementFacts.equals(priorFacts)
					|| !replacementInputs.stream().sorted().toList().equals(priorInputs))
					changedOrdinals.add(ordinal);
				continue;
			}
			for(int slot : candidateSlots.getOrDefault(node.key(), List.of())) {
				replayedKeys.add(domainKeys.get(slot));
				replayedFacts.add(facts.get(slot));
				copiedSlots.add(slot);
			}
		}
		// This replay also runs after function-boundary expansion. Preserve every
		// synthetic boundary and its candidate slots; only compiled CFG occurrences
		// participate in transient reaching-definition transfer.
		for(int ordinal = occurrences.size(); ordinal < nodes.size(); ordinal++) {
			Node node = nodes.get(ordinal);
			replayedNodes.add(node);
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
		Map<Hop,NodeShapeFact> factsByHop, List<CandidateRuleFact> currentFacts,
		List<CandidateRuleKey> replayedKeys, List<CandidateRuleFact> replayedFacts,
		List<LogicalTransientInputFact> logicalInputs,
		boolean allowLoopPlacementSeed, NativePlacementContinuity nativePools,
		Set<CompiledHopKey> installedLoopSeeds) {
		if(!PlacementAnalysis.isCompiledTransientAccess(readOccurrence.hop(), read, OpOpData.TRANSIENTREAD))
			return read;
		Set<Integer> definitions = cfg.reachingDefinitions().get(ordinal);
		if(cfg.reachingFunctionInputs().get(ordinal)) {
			traceTransientReplay(readOccurrence,
				"function-input-plus-definitions=" + definitions);
			return read;
		}
		Integer loopPassThroughSource = allowLoopPlacementSeed && definitions.size() > 1
			? exactLoopPassThroughSource(ordinal, readOccurrence, read, definitions,
				occurrences, nodes, factsByHop)
			: null;
		if(definitions.isEmpty()) {
			traceTransientReplay(readOccurrence, "missing-reaching-definitions=" + definitions
				+ "|details=" + describeTransientDefinitions(readOccurrence, definitions, occurrences, nodes));
			return read;
		}
		Integer loopPlacementSeed = allowLoopPlacementSeed
			&& loopPassThroughSource == null && definitions.size() > 1
			? exactLoopPlacementSeed(readOccurrence, read, definitions, occurrences, nodes, factsByHop)
			: null;
		List<Integer> exactDefinitions = loopPassThroughSource != null
			? List.of(loopPassThroughSource) : loopPlacementSeed != null
				? List.of(loopPlacementSeed) : definitions.stream().sorted().toList();

		ExactTransientReplayResult result = exactTransientReplay(readOccurrence, read, exactDefinitions,
			occurrences, nodes, factsByHop, currentFacts, nativePools, loopPlacementSeed == null);
		if(result.replay() == null) {
			traceTransientReplay(readOccurrence, result.rejection() + "|definitions=" + exactDefinitions
				+ "|details=" + describeTransientDefinitions(readOccurrence, definitions, occurrences, nodes));
			return read;
		}
		Node replayed = buildExactLogicalTransientRead(readOccurrence.hop(), read, result.replay(),
			replayedKeys, replayedFacts, logicalInputs);
		// A loop pass-through or placement seed is provisional authority for the
		// first closure pass only. Physical closure must ground the carried writer;
		// every later pass then replays all reaching definitions so the published
		// compatibility relation cannot permanently omit the loop backedge.
		if(loopPassThroughSource != null || loopPlacementSeed != null)
			installedLoopSeeds.add(read.key());
		return replayed;
	}

	private record ExactTransientReplayResult(ExactTransientReplay replay, String rejection) {
		private static ExactTransientReplayResult accepted(ExactTransientReplay replay) {
			return new ExactTransientReplayResult(Objects.requireNonNull(replay), "");
		}
		private static ExactTransientReplayResult rejected(String reason) {
			return new ExactTransientReplayResult(null, reason);
		}
	}

	private record ExactTransientReplay(List<Node> sources, List<ReplayPlacementAlternative> alternatives) { }

	private record ReplayPlacementAlternative(PlacementState state, CandidateInputState input,
		List<CandidateEmissionRealization> readerRealizations,
		Map<CompiledHopKey,List<ReplayCompatibilitySupport>> supportBySource) { }

	private record ReplayCompatibilitySupport(CandidateRealizationReference sourceRealization,
		CandidateEmissionRealization readerRealization, TransientCompatibilityProof proof) { }
	private record ReplayCompatibilityOption(Node source, CandidateRealizationReference sourceRealization,
		TransientCompatibilityProof proof) { }

	private static ReplayPlacementAlternative buildReplayAlternative(Node read, List<Node> sources,
		PlacementState state, CandidateInputState input, PlacementLayoutKind layoutKind,
		DurableAnchorKey durableAnchor, String nativeLineage, boolean nativeLayoutExact,
		Map<CompiledHopKey,List<ReplayCompatibilityOption>> optionsBySource,
		List<PlacementProofKey> commonProofs) {
		PlacementEmissionState emission = new PlacementEmissionState(state, false);
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		Map<CompiledHopKey,List<ReplayCompatibilitySupport>> supportBySource = new IdentityHashMap<>();
		List<PlacementProofKey> readerProofs = layoutKind == PlacementLayoutKind.NATIVE_LINEAGE
			? appendProof(commonProofs, new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				read.key(), "transient-reader:" + durableAnchor.normalizedSignature())) : commonProofs;
		CandidateEmissionRealization readerRealization = switch(layoutKind) {
			case LOCAL -> CandidateEmissionRealization.local(emission, readerProofs, List.of());
			case SOURCE_LINEAGE -> throw new IllegalArgumentException(
				"A transient replay cannot manufacture a literal source realization");
			case DURABLE_MAP -> CandidateEmissionRealization.durable(
				emission, durableAnchor, readerProofs, List.of());
			case NATIVE_LINEAGE -> nativeLayoutExact
				? CandidateEmissionRealization.nativeLineage(
					emission, nativeLineage, durableAnchor, readerProofs, List.of())
				: CandidateEmissionRealization.nativeLineageDynamicLayout(
					emission, nativeLineage, durableAnchor, readerProofs, List.of());
		};
		realizations.add(readerRealization);
		for(Node source : sources)
			for(ReplayCompatibilityOption option : optionsBySource.getOrDefault(source.key(), List.of())) {
				if(option.source() != source)
					throw new IllegalStateException("Transient replay option belongs to a different source");
				supportBySource.computeIfAbsent(source.key(), ignored -> new ArrayList<>())
					.add(new ReplayCompatibilitySupport(option.sourceRealization(), readerRealization,
						option.proof()));
			}
		if(realizations.isEmpty() || supportBySource.size() != sources.size())
			throw new IllegalStateException("All-definition replay assignment unexpectedly disappeared");
		supportBySource.replaceAll((ignored, support) -> support.stream().distinct().toList());
		return new ReplayPlacementAlternative(state, input,
			realizations.stream().distinct()
				.sorted(PlacementAnalysis.<CandidateEmissionRealization>canonicalComparator()).toList(), supportBySource);
	}


	/**
	 * Builds the executable compatibility relation of every reaching TWrite. A
	 * reader realization is retained only when every reaching definition has at
	 * least one exact candidate realization that supports it. PlacementState/FType
	 * is only the coarse projection; exact durable geometry or a candidate-specific
	 * native-continuity proof owns every federated compatibility edge.
	 */
	private static ExactTransientReplayResult exactTransientReplay(
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Node read, List<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<Hop,NodeShapeFact> factsByHop, List<CandidateRuleFact> candidateFacts,
		NativePlacementContinuity nativePools, boolean mayRetainExactGeometry) {
		if(definitions.isEmpty())
			return ExactTransientReplayResult.rejected("missing-reaching-definitions");
		List<Node> sources = new ArrayList<>(definitions.size());
		NodeShapeFact readShape = factsByHop.get(readOccurrence.hop());
		if(readShape == null)
			return ExactTransientReplayResult.rejected("missing-reader-shape-proof");
		for(int definition : definitions) {
			if(definition < 0 || definition >= occurrences.size())
				return ExactTransientReplayResult.rejected("invalid-reaching-definition=" + definition);
			PlacementGraphFingerprint.HopOccurrence sourceOccurrence = occurrences.get(definition);
			Node source = nodes.get(definition);
			if(!isCompiledTransientWrite(sourceOccurrence.hop(), source))
				return ExactTransientReplayResult.rejected("source-not-compiled-transient-write=" + definition);
			if(!sameTransientForwardContext(source, read))
				return ExactTransientReplayResult.rejected("incompatible-source-context=" + definition);
			if(source.legalAlternatives().stream().anyMatch(state -> !isLegalTransient(state)))
				return ExactTransientReplayResult.rejected("illegal-source-transient-state=" + definition);
			if(!sameLogicalValueShape(factsByHop.get(sourceOccurrence.hop()), readShape))
				return ExactTransientReplayResult.rejected("incompatible-source-shape=" + definition);
			sources.add(source);
		}

		List<PlacementProofKey> commonProofs = transientCommonProofs(sources, read);
		List<ReplayPlacementAlternative> alternatives = new ArrayList<>();
		PlacementState localState = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		Map<CompiledHopKey,List<ReplayCompatibilityOption>> localOptions = new IdentityHashMap<>();
		for(Node source : sources) {
			List<ReplayCompatibilityOption> options = sourceRealizations(source.key(), localState, candidateFacts)
				.stream().filter(reference -> reference.realization().layoutKind() == PlacementLayoutKind.LOCAL)
				.map(reference -> new ReplayCompatibilityOption(source, reference,
					new TransientCompatibilityProof(null, null,
						edgeProofs(commonProofs, source, read)))).toList();
			if(!options.isEmpty())
				localOptions.put(source.key(), options);
		}
		if(localOptions.size() == sources.size())
			alternatives.add(buildReplayAlternative(read, sources, localState,
				CandidateInputState.absentLocal(), PlacementLayoutKind.LOCAL, null, null,
				true, localOptions, commonProofs));

		List<DurableAnchorKey> seedCandidates = sources.stream().flatMap(source -> java.util.stream.Stream.concat(
			source.anchors().stream(), sourceFederatedRealizations(source.key(), candidateFacts).stream()
				.flatMap(reference -> candidateRealization(candidateFacts, reference).stream()
					.flatMap(realization -> realization.supportClauses().stream()
						.map(realization::nativeWorkerPoolResidencyWitness).filter(Objects::nonNull)))))
			.sorted().toList();
		// Placement ids identify the value that supplied a map, not different worker
		// authority. Include dynamic-layout endpoint witnesses as native replay seeds;
		// exact replay remains guarded below by nativeWorkerPoolLayoutExact. Replaying
		// every value-specific id creates duplicate reader layouts and a combinatorial
		// number of downstream support clauses, so keep one canonical seed per witness.
		List<DurableAnchorKey> seeds = new ArrayList<>();
		for(DurableAnchorKey candidate : seedCandidates)
			if(seeds.stream().noneMatch(seed -> PlacementIdentity.samePhysicalLayout(seed, candidate)))
				seeds.add(candidate);
		for(DurableAnchorKey seed : seeds) {
			FType type = seed.fType();
			if(type == FType.PART || type == FType.OTHER)
				continue;
			PlacementState fedState = new PlacementState(ExecType.FED, FederatedOutput.FOUT, type, false);

			if(mayRetainExactGeometry) {
				Map<CompiledHopKey,List<ReplayCompatibilityOption>> durableOptions = new IdentityHashMap<>();
				for(Node source : sources) {
					List<ReplayCompatibilityOption> options = sourceRealizations(source.key(), fedState, candidateFacts)
						.stream().filter(reference -> reference.realization().layoutKind()
							== PlacementLayoutKind.DURABLE_MAP)
						.filter(reference -> PlacementIdentity.samePhysicalLayout(
							reference.realization().durableAnchor(), seed))
						.map(reference -> new ReplayCompatibilityOption(source, reference,
							new TransientCompatibilityProof(reference.realization().durableAnchor(), seed,
								appendProof(edgeProofs(commonProofs, source, read), new PlacementProofKey(
									PlacementProofKind.DURABLE_ANCHOR, source.key(), seed.normalizedSignature())))))
						.toList();
					if(!options.isEmpty())
						durableOptions.put(source.key(), options);
				}
				if(durableOptions.size() == sources.size())
					alternatives.add(buildReplayAlternative(read, sources, fedState,
						CandidateInputState.present(type), PlacementLayoutKind.DURABLE_MAP, seed, null,
						true, durableOptions, commonProofs));
			}

			Map<CompiledHopKey,List<ReplayCompatibilityOption>> nativeOptions = new IdentityHashMap<>();
			for(Node source : sources) {
				List<ReplayCompatibilityOption> options = new ArrayList<>();
				for(CandidateRealizationReference reference : sourceRealizations(
					source.key(), fedState, candidateFacts)) {
					List<TransientCompatibilityProof> proofs = nativeTransientCompatibilityProofs(
						source.key(), reference, seed, nativePools,
							edgeProofs(commonProofs, source, read));
					for(TransientCompatibilityProof proof : proofs)
						options.add(new ReplayCompatibilityOption(source, reference, proof));
				}
				if(!options.isEmpty())
					nativeOptions.put(source.key(), List.copyOf(options));
			}
			if(nativeOptions.size() == sources.size()) {
				String lineage = "cfg-transient:" + read.key().normalizedSignature()
					+ "|seed=" + seed.normalizedSignature();
				Map<CompiledHopKey,List<ReplayCompatibilityOption>> exactOptions = new IdentityHashMap<>();
				for(Node source : sources) {
					List<ReplayCompatibilityOption> options = nativeOptions.get(source.key()).stream()
						.filter(option -> option.proof().nativeWorkerPoolLayoutExact()).toList();
					if(!options.isEmpty())
						exactOptions.put(source.key(), options);
				}
				if(exactOptions.size() == sources.size()) {
					DurableAnchorKey exactWitness = replayNativeWitness(exactOptions, true);
					alternatives.add(buildReplayAlternative(read, sources, fedState,
						CandidateInputState.present(type), PlacementLayoutKind.NATIVE_LINEAGE, exactWitness,
						lineage + "|layout=exact", true, exactOptions, commonProofs));
				}
				boolean hasDynamicProof = nativeOptions.values().stream().flatMap(List::stream)
					.anyMatch(option -> !option.proof().nativeWorkerPoolLayoutExact());
				if(hasDynamicProof) {
					DurableAnchorKey endpointWitness = replayNativeWitness(nativeOptions, false);
					alternatives.add(buildReplayAlternative(read, sources, fedState,
						CandidateInputState.present(type), PlacementLayoutKind.NATIVE_LINEAGE, endpointWitness,
						lineage + "|layout=dynamic", false, nativeOptions, commonProofs));
				}
			}
		}
		if(alternatives.isEmpty())
			return ExactTransientReplayResult.rejected("no-all-definition-compatible-realization");
		return ExactTransientReplayResult.accepted(new ExactTransientReplay(List.copyOf(sources),
			mergeReplayAlternatives(alternatives)));
	}

	static List<TransientCompatibilityProof> nativeTransientCompatibilityProofs(
		CompiledHopKey source, CandidateRealizationReference reference, DurableAnchorKey seed,
		NativePlacementContinuity nativePools, List<PlacementProofKey> commonProofs) {
		return nativePools.proveCandidateAlternatives(reference, seed).stream()
			.map(continuity -> new TransientCompatibilityProof(null, null,
				continuity.exactPartitionRanges() ? seed : continuity.outputWorkerPoolWitness(),
				continuity.exactPartitionRanges(),
				appendProof(commonProofs, new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
					source, continuity.normalizedSignature()))))
			.distinct().sorted(PlacementAnalysis.<TransientCompatibilityProof>canonicalComparator()).toList();
	}

	private static DurableAnchorKey replayNativeWitness(
		Map<CompiledHopKey,List<ReplayCompatibilityOption>> optionsBySource, boolean exactLayout) {
		List<DurableAnchorKey> witnesses = optionsBySource.values().stream().flatMap(List::stream)
			.map(ReplayCompatibilityOption::proof)
			.filter(proof -> !exactLayout || proof.nativeWorkerPoolLayoutExact())
			.map(TransientCompatibilityProof::nativeWorkerPoolWitness)
			.filter(Objects::nonNull).distinct().sorted().toList();
		if(witnesses.isEmpty())
			throw new IllegalStateException("Native transient replay lacks a typed worker-pool witness");
		DurableAnchorKey witness = witnesses.get(0);
		boolean common = witnesses.stream().allMatch(candidate -> exactLayout
			? PlacementIdentity.samePhysicalLayout(witness, candidate)
			: PlacementIdentity.samePhysicalWorkerEndpoints(witness, candidate));
		if(!common)
			throw new IllegalStateException("Native transient replay mixes incompatible worker pools");
		return witness;
	}

	private static List<ReplayPlacementAlternative> mergeReplayAlternatives(
		List<ReplayPlacementAlternative> alternatives) {
		Map<CandidateInputState,List<ReplayPlacementAlternative>> grouped = alternatives.stream()
			.collect(java.util.stream.Collectors.groupingBy(ReplayPlacementAlternative::input,
				() -> new java.util.TreeMap<>(java.util.Comparator.comparing(
					CandidateInputState::normalizedSignature)), java.util.stream.Collectors.toList()));
		List<ReplayPlacementAlternative> merged = new ArrayList<>();
		for(var entry : grouped.entrySet()) {
			List<ReplayPlacementAlternative> group = entry.getValue();
			Map<CompiledHopKey,List<ReplayCompatibilitySupport>> support = new IdentityHashMap<>();
			for(ReplayPlacementAlternative alternative : group)
				alternative.supportBySource().forEach((source, values) -> support.computeIfAbsent(source,
					ignored -> new ArrayList<>()).addAll(values));
			List<CandidateEmissionRealization> realizations = group.stream()
				.flatMap(alternative -> alternative.readerRealizations().stream()).distinct()
				.sorted(PlacementAnalysis.<CandidateEmissionRealization>canonicalComparator()).toList();
			support.replaceAll((ignored, values) -> values.stream().distinct().toList());
			merged.add(new ReplayPlacementAlternative(group.get(0).state(), entry.getKey(), realizations, support));
		}
		return List.copyOf(merged);
	}

	private static List<CandidateRealizationReference> sourceFederatedRealizations(CompiledHopKey source,
		List<CandidateRuleFact> candidateFacts) {
		return candidateFacts.stream().filter(fact -> fact.key().parentOccurrence() == source
			&& fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream()
				.filter(realization -> realization.placementState().output() == FederatedOutput.FOUT)
				.filter(realization -> executableSourceRealization(fact.key(), realization))
				.map(realization -> CandidateRealizationReference.of(fact.key(), realization))))
			.distinct().sorted(PlacementAnalysis.<CandidateRealizationReference>canonicalComparator()).toList();
	}

	private static Optional<CandidateEmissionRealization> candidateRealization(
		List<CandidateRuleFact> candidateFacts, CandidateRealizationReference reference) {
		return candidateFacts.stream().filter(fact -> fact.key().equals(reference.rule()))
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().equals(reference.realization())).findFirst();
	}

	private static List<CandidateRealizationReference> sourceRealizations(CompiledHopKey source,
		PlacementState state, List<CandidateRuleFact> candidateFacts) {
		return candidateFacts.stream().filter(fact -> fact.key().parentOccurrence() == source
			&& fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream().filter(emission ->
				emission.emissionState().placementState().equals(state)).flatMap(emission ->
					emission.realizations().stream()
						.filter(realization -> executableSourceRealization(fact.key(), realization))
						.map(realization -> CandidateRealizationReference.of(fact.key(), realization))))
			.distinct().sorted(PlacementAnalysis.<CandidateRealizationReference>canonicalComparator()).toList();
	}

	private static boolean executableSourceRealization(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return realization.key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
			|| realization.supportClauses().stream()
				.allMatch(clause -> clause.nativeWorkerPoolWitness() != null);
	}

	private static List<PlacementProofKey> transientCommonProofs(List<Node> sources, Node read) {
		List<PlacementProofKey> proofs = new ArrayList<>();
		proofs.add(new PlacementProofKey(PlacementProofKind.SHAPE, read.key(),
			"same-logical-value-shape"));
		proofs.add(new PlacementProofKey(PlacementProofKind.CONTROL_FLOW, read.key(),
			"reaching-definitions=" + sources.stream().map(source -> source.key().normalizedSignature()).sorted().toList()));
		return proofs.stream().distinct()
			.sorted(PlacementAnalysis.<PlacementProofKey>canonicalComparator()).toList();
	}

	private static List<PlacementProofKey> edgeProofs(List<PlacementProofKey> common,
		Node source, Node read) {
		return appendProof(common, PlacementAnalysis.transientValueIdentityProof(source.key(),
			source.valueVersion(), read.valueVersion()));
	}

	private static List<PlacementProofKey> appendProof(List<PlacementProofKey> proofs,
		PlacementProofKey addition) {
		return java.util.stream.Stream.concat(proofs.stream(), java.util.stream.Stream.of(addition))
			.distinct().sorted(PlacementAnalysis.<PlacementProofKey>canonicalComparator()).toList();
	}

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
			if(!isCompiledTransientWrite(sourceOccurrence.hop(), source)
				|| !sameTransientForwardContext(source, read)
				|| source.legalAlternatives().stream().anyMatch(state -> !isLegalTransient(state)))
				return null;
			if(source.legalAlternatives().isEmpty()
				|| !sameLogicalValueShape(factsByHop.get(sourceOccurrence.hop()),
					factsByHop.get(readOccurrence.hop())))
				return null;
			seeds.add(definition);
		}
		return seeds.size() == 1 ? seeds.get(0) : null;
	}

	/**
	 * Seeds the greatest placement fixed point of a real loop update. Every backedge
	 * must derive the same transient variable from some loop read of that variable;
	 * the sole non-recursive reaching definition supplies the provisional exact
	 * CP/LOUT + FED/FOUT tuple. Physical closure then proves each update supports the
	 * tuple, after which ordinary all-definition replay replaces this seed authority.
	 */
	private static Integer exactLoopPlacementSeed(
		PlacementGraphFingerprint.HopOccurrence readOccurrence, Node read, Set<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<Hop,NodeShapeFact> factsByHop) {
		List<Integer> seeds = new ArrayList<>();
		for(int definition : definitions) {
			if(definition < 0 || definition >= occurrences.size())
				return null;
			PlacementGraphFingerprint.HopOccurrence sourceOccurrence = occurrences.get(definition);
			Node source = nodes.get(definition);
			if(!isCompiledTransientWrite(sourceOccurrence.hop(), source)
				|| !sameTransientForwardContext(source, read)
				|| source.legalAlternatives().stream().anyMatch(state -> !isLegalTransient(state))
				|| !sameLogicalValueShape(factsByHop.get(sourceOccurrence.hop()),
					factsByHop.get(readOccurrence.hop())))
				return null;
			if(dependsOnTransientVariable(sourceOccurrence.hop(), readOccurrence.hop().getName(),
				Collections.newSetFromMap(new IdentityHashMap<>())))
				continue;
			if(source.legalAlternatives().isEmpty())
				return null;
			seeds.add(definition);
		}
		return seeds.size() == 1 ? seeds.get(0) : null;
	}

	private static boolean dependsOnTransientVariable(Hop current, String variable, Set<Hop> visited) {
		if(current == null || !visited.add(current))
			return false;
		if(isTransientRead(current) && Objects.equals(variable, current.getName()))
			return true;
		for(Hop input : current.getInput())
			if(dependsOnTransientVariable(input, variable, visited))
				return true;
		return false;
	}

	private static boolean exactIdentityLoopBackedge(int readOrdinal,
		PlacementGraphFingerprint.HopOccurrence readOccurrence,
		PlacementGraphFingerprint.HopOccurrence sourceOccurrence, Node source,
		Map<Hop,NodeShapeFact> factsByHop) {
		Hop sourceHop = sourceOccurrence.hop();
		return sourceOccurrence.block() == readOccurrence.block()
			&& sourceOccurrence.path().equals(readOccurrence.path())
			&& isCompiledTransientWrite(sourceHop, source) && sourceHop.getInput().size() == 1
			&& sourceHop.getInput(0) == readOccurrence.hop()
			&& Objects.equals(sourceHop.getName(), readOccurrence.hop().getName())
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

	private Node buildExactLogicalTransientRead(Hop readHop, Node read, ExactTransientReplay replay,
		List<CandidateRuleKey> replayedKeys, List<CandidateRuleFact> replayedFacts,
		List<LogicalTransientInputFact> logicalInputs) {
		List<PlacementState> states = new ArrayList<>();
		List<DurableAnchorKey> anchors = new ArrayList<>();
		Map<CompiledHopKey,List<TransientPlacementCompatibility>> compatibilityBySource =
			new IdentityHashMap<>();
		for(ReplayPlacementAlternative alternative : replay.alternatives()) {
			// An exact writer proof cannot release a reader value that the shared
			// privacy closure already denied. Do not publish its facts or edges either.
			if(read.exclusions().stream().anyMatch(exclusion ->
				exclusion.reasonCode() == ReasonCode.PRIVACY && exclusion.state().equals(alternative.state()))) {
				continue;
			}
			CandidateRuleKey key = new CandidateRuleKey(read.key(), List.of(alternative.input()));
			replayedKeys.add(key);
			replayedFacts.add(logicalTransientReplayFact(readHop, key, alternative));
			states.add(alternative.state());
			alternative.readerRealizations().stream().map(CandidateEmissionRealization::anchor)
				.filter(Objects::nonNull).forEach(anchors::add);
			for(Node source : replay.sources())
				for(ReplayCompatibilitySupport support : alternative.supportBySource()
					.getOrDefault(source.key(), List.of())) {
					CandidateRealizationReference readerReference = CandidateRealizationReference.of(
						key, support.readerRealization());
					PlacementState sourceState = support.sourceRealization().realization()
						.emissionState().placementState();
					CandidateInputState sourceInput = sourceState.output() == FederatedOutput.FOUT
						? CandidateInputState.present(sourceState.fType())
						: CandidateInputState.absentLocal();
					compatibilityBySource.computeIfAbsent(source.key(), ignored -> new ArrayList<>())
						.add(new TransientPlacementCompatibility(support.sourceRealization(), readerReference,
							sourceInput, alternative.input(), support.proof()));
				}
		}
		for(Node source : replay.sources()) {
			List<TransientPlacementCompatibility> compatibility = compatibilityBySource
				.getOrDefault(source.key(), List.of()).stream().distinct()
					.sorted(PlacementAnalysis.<TransientPlacementCompatibility>canonicalComparator()).toList();
			if(!compatibility.isEmpty())
				logicalInputs.add(new LogicalTransientInputFact(source.key(), read.key(), 0,
					source.valueVersion(), read.valueVersion(), compatibility));
		}
		if(states.isEmpty() && read.emittedWork())
			throw new DMLRuntimeException("No privacy-safe physical placement for transient replay "
				+ read.key().normalizedSignature());
		return new Node(read.key(), read.kind(), read.valueVersion(), !states.isEmpty(),
			states.stream().distinct().sorted().toList(), read.exclusions().stream()
				.filter(exclusion -> !states.contains(exclusion.state())).toList(),
			anchors.stream().distinct().sorted().toList());
	}

	private CandidateRuleFact logicalTransientReplayFact(Hop readHop, CandidateRuleKey key,
		ReplayPlacementAlternative alternative) {
		PlacementState state = alternative.state();
		String detail = "logical-transient-replay|read=" + key.parentOccurrence().normalizedSignature()
			+ "|input=" + alternative.input().normalizedSignature() + "|realizations="
			+ alternative.readerRealizations().stream().map(CandidateEmissionRealization::normalizedSignature).toList();
		CandidateCapabilityFact capability = new CandidateCapabilityFact(
			org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory.OTHER, readHop.getOpString(),
			state.execType(), state.output(), state.fType(),
			org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK, detail,
			List.of(new CandidateRuleNote(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.INFO,
				"builder-local logical transient replay from exact compatible realizations")));
		CandidateShapeProofFact shapeProof = new CandidateShapeProofFact(
			Map.of("logicalTransientReplay", "builder-local",
				"read", key.parentOccurrence().normalizedSignature(),
				"realizationCount", String.valueOf(alternative.readerRealizations().size())),
			List.of("source-realization", "reader-layout", "cfg-reaching-definitions"), List.of());
		List<FType> outputs = state.output() == FederatedOutput.FOUT && state.fType() != null
			? List.of(state.fType()) : List.of();
		PlacementEmissionState emissionState = new PlacementEmissionState(state, false);
		CandidateEmissionFact emission = new CandidateEmissionFact(emissionState,
			state.execType() == ExecType.FED ? state.fType() : null, null,
			alternative.readerRealizations());
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE, capability, shapeProof,
			new CandidateProfileFact(outputs, ""), List.of(emission), "");
	}

	private static boolean isCompiledTransientWrite(Hop hop, Node node) {
		return PlacementAnalysis.isCompiledTransientAccess(hop, node, OpOpData.TRANSIENTWRITE);
	}

	private static boolean sameLogicalValueShape(NodeShapeFact source, NodeShapeFact read) {
		if(source.dataType() != read.dataType())
			return false;
		return (source.rows() <= 0 || read.rows() <= 0 || source.rows() == read.rows())
			&& (source.cols() <= 0 || read.cols() <= 0 || source.cols() == read.cols());
	}

	private static boolean sameTransientForwardContext(Node source, Node read) {
		// The exact CFG reaching-definition edge owns the logical transient value;
		// compiled versus dynamic-recompile is a physical compilation mode. Admit only
		// the two supported concrete occurrence modes and retain every logical identity
		// check. Exact shape, placement-domain, and anchor checks follow at the caller.
		return source.key().programFingerprint().equals(read.key().programFingerprint())
			&& source.valueVersion().programFingerprint().equals(read.valueVersion().programFingerprint())
			&& source.valueVersion().lexicalVariable().equals(read.valueVersion().lexicalVariable())
			&& source.key().functionNamespace().equals(read.key().functionNamespace())
			&& supportedLocalPathOccurrence(source) && supportedLocalPathOccurrence(read);
	}

	private CandidateReplay closePostCfgPhysicalCandidateDependencies(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, CandidateReplay replay,
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, CfgAnalysis cfg,
		List<CompiledInputEdgeFact> compiledInputEdges, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> sourceCompiledFactsByHop) {
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
		// A physical revision changes only affected rows. Keep each statement block's
		// current Hop lookup across its consumer rebuilds instead of rescanning every
		// Hop in the block for each edge in the dirty cone.
		Map<StatementBlock,Map<Hop,Node>> blockNodes = new IdentityHashMap<>();
		while(!worklist.isEmpty()) {
			int producerOrdinal = worklist.pollFirst();
			for(int consumerOrdinal : consumersByProducer.get(producerOrdinal)) {
				PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(consumerOrdinal);
				Hop hop = occurrence.hop();
				Node current = nodes.get(consumerOrdinal);
				Map<Hop,Integer> blockOrdinals = ordinalsByBlock.get(occurrence.block());
				Map<Hop,Node> exactBlockNodes = blockNodes.computeIfAbsent(occurrence.block(), block -> {
					Map<Hop,Node> currentBlockNodes = new IdentityHashMap<>();
					if(blockOrdinals != null)
						for(Map.Entry<Hop,Integer> entry : blockOrdinals.entrySet())
							currentBlockNodes.put(entry.getKey(), nodes.get(entry.getValue()));
					return currentBlockNodes;
				});
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
						Integer inputOrdinal = blockOrdinals == null ? null : blockOrdinals.get(input);
						return exactCandidateInputAnchor(inputNode, input,
							inputOrdinal == null ? List.of() : factsByOrdinal.get(inputOrdinal));
					}).toList();
				List<CompiledHopKey> inputAnchorOwners = hop.getInput().stream()
					.map(input -> {
						Node inputNode = exactBlockNodes.get(input);
						return inputNode == null ? null : inputNode.key();
					}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
				List<List<FType>> exactInputDomains = inputDomains(hop, exactBlockNodes, occurrence, occurrences,
					cfg.reachingFunctionInputs().get(consumerOrdinal), cfg);
				NodeShapeFact outputShape = factsByHop.get(hop);
				// A derived anchor is certified by the current exact inputs, not by a
				// prior replay. Recompute it so provisional loop anchors cannot survive
				// a later input-domain or anchor change. Inputless sources retain their
				// independently established intrinsic/function-boundary authority.
				DurableAnchorKey outputAnchor = hop.getInput().isEmpty() && current.anchors().size() == 1
					? current.anchors().get(0)
					: inheritableDurableAnchor(hop, current.key().normalizedSignature(), outputShape,
						inputShapes, inputAnchors);
				List<DurableAnchorKey> outputAnchors = outputAnchor == null ? List.of() : List.of(outputAnchor);
				Node replacement = buildNode(hop, current.key(), current.valueVersion(), outputAnchors,
					inputAnchors, Collections.unmodifiableList(inputAnchorOwners),
					outputShape, abstractFactsByHop.get(hop), singlePartitions, List.copyOf(inputShapes),
					exactInputDomains, replacementKeys, replacementFacts);
				if(compiledInputEdges != null && sourceCompiledFactsByHop != null
					&& isPotentialLatentWdivmmOwner(hop)) {
					List<Node> proofNodes = new ArrayList<>(nodes);
					proofNodes.set(consumerOrdinal, replacement);
					CandidateReplay normalized = closeLatentWdivmmRuntimeOutputContracts(
						new CandidateReplay(proofNodes, replacementKeys, replacementFacts,
							replay.logicalInputs(), List.of()), compiledInputEdges, origins,
							factsByHop, sourceCompiledFactsByHop);
					replacement = normalized.nodes().get(consumerOrdinal);
					replacementFacts = new ArrayList<>(normalized.facts());
				}
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
				// Rebuild from the current input authority, not the prior publication
				// projection. Intersecting with old emissions would permanently lose
				// realizations whose input support is being regenerated. The enclosing
				// closure reapplies the same privacy authority before publication.
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
							priorFacts, replacementKeys, replacementFacts, occurrences, ordinalsByBlock, exactInputDomains,
							refinedOrdinals, factsByHop);
					if(!exactRefinement) {
						Set<PlacementState> replacementStates = new HashSet<>(replacement.legalAlternatives());
						throw new IllegalStateException("Post-CFG physical candidate closure is not monotone"
							+ "|consumerOrdinal=" + consumerOrdinal
							+ "|producerOrdinal=" + producerOrdinal
							+ "|hop=" + hop.getHopID() + ':' + hop.getOpString()
							+ "|refinedOrdinals=" + refinedOrdinals
							+ "|removedLegal=" + current.legalAlternatives().stream()
								.filter(state -> !replacementStates.contains(state)).toList()
							+ "|removedKeys=" + priorKeys.stream()
								.filter(key -> !replacementKeys.contains(key)).toList()
							+ "|priorFacts=" + priorFacts
							+ "|replacementFacts=" + replacementFacts);
					}
				}
				nodes.set(consumerOrdinal, replacement);
				exactBlockNodes.put(hop, replacement);
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

	private static boolean isPotentialLatentWdivmmOwner(Hop hop) {
		return hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS
			&& hop.getInput().size() == 1 && hop.getInput(0) instanceof AggBinaryOp matrix
			&& matrix.isMatrixMultiply();
	}

	/**
	 * A node anchor is provenance for a possible placement, not necessarily the exact map of every
	 * selectable upstream receipt. Runtime-layout-sensitive consumers (notably right indexing) may
	 * use it as value geometry only when the current receipt relation proves one exact layout.
	 */
	private static DurableAnchorKey exactCandidateInputAnchor(Node inputNode, Hop input,
		List<CandidateRuleFact> sourceFacts) {
		if(inputNode == null || inputNode.anchors().size() != 1)
			return null;
		DurableAnchorKey anchor = inputNode.anchors().get(0);
		if(input instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			return anchor;
		boolean transientIdentity = isTransientRead(input);

		boolean foundExecutableFout = false;
		for(CandidateRuleFact fact : sourceFacts) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.output() != FederatedOutput.FOUT || state.fType() != anchor.fType())
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(!executableSourceRealization(fact.key(), realization))
						continue;
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						foundExecutableFout = true;
						DurableAnchorKey exact = realization.provenWorkerPoolForOwnedClause(clause);
						if(exact == null || !PlacementIdentity.samePhysicalLayout(anchor, exact))
							return null;
						if(transientIdentity && !exactTransientIdentitySupport(anchor, clause))
							return null;
					}
				}
			}
		}
		return foundExecutableFout ? anchor : null;
	}

	/**
	 * A transient read is a physical identity of its selected receipt.  The reader's
	 * outer realization key is therefore not sufficient exact-layout authority when
	 * alternative support clauses can select different upstream receipts.  DIRECT and
	 * logical-transient support must name the same exact layout; RELOCATION is exact only
	 * when its action materializes that same layout.  Native-lineage references do not
	 * carry their range witness in the reference key, so this proof deliberately fails
	 * closed for them instead of guessing from the reader's representative anchor.
	 */
	static boolean exactTransientIdentitySupport(DurableAnchorKey readerAnchor,
		CandidateRealizationSupportClause clause) {
		if(clause.inputBindings().isEmpty())
			return false;
		for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
			DurableAnchorKey selected = binding.kind() == CandidateInputBindingKind.RELOCATION
				? binding.relocationAction().durableAnchor()
				: binding.source().realization().durableAnchor();
			if(selected == null || !PlacementIdentity.samePhysicalLayout(readerAnchor, selected))
				return false;
		}
		return true;
	}


	private static boolean isExactAffectedDescendantRefinement(int consumerOrdinal, Node current, Node replacement,
		List<CandidateRuleKey> priorKeys, List<CandidateRuleFact> priorFacts,
		List<CandidateRuleKey> replacementKeys, List<CandidateRuleFact> replacementFacts,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, List<List<FType>> exactInputDomains,
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
		Set<Integer> exactRefinedInputPositions = CandidateDomainRefinement.refinedInputPositions(
			priorKeys.stream().map(CandidateRuleKey::orderedInputs).toList(), exactInputDomains);
		List<CandidateEmissionFact> removedMaterializations = new ArrayList<>();
		for(CandidateRuleKey key : priorKeys) {
			CandidateRuleFact priorFact = priorByKey.get(key);
			CandidateRuleFact replacementFact = replacementByKey.get(key);
			if(replacementFact == null) {
				// Privacy closure may retain an unavailable diagnostic row until an
				// exact predecessor-domain refinement removes that input tuple. Such a
				// row is not executable authority and its removal cannot shrink the
				// candidate space.
				if(priorFact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				if(!hasRefinedPredecessorInvalidation(key, occurrence, blockOrdinals, exactInputDomains,
					exactRefinedInputPositions, refinedOrdinals, factsByHop)) {
					return false;
				}
			}
			else if(!replacementFact.equals(priorFact)) {
				// A diagnostic-only row becoming executable (or changing diagnostics)
				// cannot remove executable authority. Only a previously AVAILABLE row
				// needs a monotonicity proof when its publication changes.
				if(priorFact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				List<CandidateEmissionFact> removed = removedProvisionalMaterializations(priorFact, replacementFact);
				if(removed == null) {
					return false;
				}
				removedMaterializations.addAll(removed);
			}
		}
		for(PlacementState state : removedLegal)
			if(removedKeys.stream().map(priorByKey::get).noneMatch(fact -> exactFactPublishesState(fact, state))
				&& removedMaterializations.stream().noneMatch(emission ->
					emission.emissionState().placementState().equals(state))) {
				return false;
			}
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
		// Candidate-specific realizations are closure evidence derived from the
		// exact input layouts. A refined predecessor may legitimately invalidate
		// and later regenerate those proofs without changing the oracle-owned
		// native emission domain. Compare that stable domain here rather than
		// treating proof/binding refresh as a non-monotone policy change.
		if(!containsCoarseEmissionDomain(replacementNative, priorNative))
			return null;
		return prior.allowedEmissionFacts().stream()
			.filter(emission -> emission.derivedFoutAction() != null)
			.filter(emission -> !replacement.allowedEmissionFacts().contains(emission)).toList();
	}

	private static boolean containsCoarseEmissionDomain(List<CandidateEmissionFact> domain,
		List<CandidateEmissionFact> required) {
		// A freshly rebuilt emission may later be removed by the same privacy
		// authority. Its provisional addition is monotone; prior native
		// emissions must still be retained.
		return domain.stream().map(NeutralPlacementGraphBuilder::coarseEmissionIdentity).toList()
			.containsAll(required.stream().map(NeutralPlacementGraphBuilder::coarseEmissionIdentity).toList());
	}

	private static String coarseEmissionIdentity(CandidateEmissionFact emission) {
		return emission.emissionState().normalizedSignature() + '|' + emission.executionFType()
			+ '|' + (emission.derivedFoutAction() == null ? "native" : "derived");
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
		PlacementGraphFingerprint.HopOccurrence occurrence, Map<Hop,Integer> blockOrdinals,
		List<List<FType>> exactInputDomains, Set<Integer> exactRefinedInputPositions,
		Set<Integer> refinedOrdinals, Map<Hop,NodeShapeFact> factsByHop) {
		if(key.orderedInputs().size() != occurrence.hop().getInput().size())
			return false;
		Set<Integer> refinedInputPositions = new HashSet<>(exactRefinedInputPositions);
		for(int inputPosition = 0; inputPosition < occurrence.hop().getInput().size(); inputPosition++) {
			Hop input = occurrence.hop().getInput(inputPosition);
			if(!isPlacementDataShape(factsByHop, input))
				continue;
			Integer predecessorOrdinal = blockOrdinals.get(input);
			if(predecessorOrdinal != null && refinedOrdinals.contains(predecessorOrdinal))
				refinedInputPositions.add(inputPosition);
		}
		// This is the domain passed to buildNode in this exact rebuild, not a
		// second projection of predecessor states. Only refined inputs can prove removal.
		return CandidateDomainRefinement.removedByRefinedInputDomain(
			key.orderedInputs(), exactInputDomains, refinedInputPositions);
	}

	private static boolean exactFactPublishesState(CandidateRuleFact fact, PlacementState state) {
		if(fact == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
			return false;
		return fact.allowedEmissionFacts().stream()
			.anyMatch(emission -> emission.emissionState().placementState().equals(state));
	}

	private record CandidateReplay(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logicalInputs,
		List<Integer> changedOrdinals) { }

	private record CfgReplayBaseline(Map<CompiledHopKey,Node> nodes,
		Map<CompiledHopKey,List<CandidateRuleKey>> domainKeys,
		Map<CompiledHopKey,List<CandidateRuleFact>> facts) { }

	private static CfgReplayBaseline cfgReplayBaseline(List<Node> nodes,
		List<CandidateRuleKey> domainKeys, List<CandidateRuleFact> facts) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateRuleKey>> keysByParent = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateRuleFact>> factsByParent = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		for(int slot = 0; slot < domainKeys.size(); slot++) {
			CandidateRuleKey key = domainKeys.get(slot);
			keysByParent.computeIfAbsent(key.parentOccurrence(), ignored -> new ArrayList<>()).add(key);
			factsByParent.computeIfAbsent(key.parentOccurrence(), ignored -> new ArrayList<>()).add(facts.get(slot));
		}
		keysByParent.replaceAll((ignored, values) -> List.copyOf(values));
		factsByParent.replaceAll((ignored, values) -> List.copyOf(values));
		return new CfgReplayBaseline(Collections.unmodifiableMap(nodesByKey),
			Collections.unmodifiableMap(keysByParent), Collections.unmodifiableMap(factsByParent));
	}

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
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions,
		int originalOccurrenceCount) {
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
				List.of(), List.of(), readShape, abstractFactsByHop.get(readHop), singlePartitions, List.of(),
				List.of(exactDomain), exactKeys, exactFacts);
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
			List<Exclusion> exclusions = boundary.exclusions().stream()
				.filter(exclusion -> !alternatives.contains(exclusion.state())).toList();
			Node replacement = new Node(boundary.key(), boundary.kind(), boundary.valueVersion(),
				boundary.emittedWork(), alternatives, exclusions, boundary.anchors());
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
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions) {
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
				List.of(), List.of(), readShape, abstractFactsByHop.get(readHop), singlePartitions, List.of(),
				List.of(exactDomain), oracleKeys, oracleFacts);
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
		List<PlacementAnalysis.LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs =
			new ArrayList<>();
		Map<CompiledHopKey,Hop> expandedOrigins = new java.util.LinkedHashMap<>(origins);
		Map<CompiledHopKey,Long> expandedScopes = new java.util.LinkedHashMap<>(scopes);
		Map<StatementBlock,Map<Hop,Node>> nodesByBlock = new IdentityHashMap<>();
		Map<String,List<Node>> inlinedContextBoundariesByFunction = new LinkedHashMap<>();
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
						? optionalExactDataNode(blockNodes, OpOpData.TRANSIENTREAD,
							exactInput.variable(), inlinedCall, "input", inlinedInput.position())
						: optionalExactNamedNode(blockNodes, exactInput.variable(), inlinedCall,
							"input", inlinedInput.position()));
				}
				List<Node> results = new ArrayList<>(inlinedCall.outputs().size());
				for(InlinedFunctionOutputBoundary inlinedOutput : inlinedCall.outputs())
					results.add(requireExactInlinedOutputNode(blockNodes, inlinedOutput, outputBindings,
						inlinedCall));
				Node callAuthority = results.stream().findFirst()
					.orElseGet(() -> arguments.stream().filter(Objects::nonNull).findFirst().orElse(null));
				if(callAuthority == null)
					continue;
				Long callScope = scopes.get(callAuthority.key());
				int callIndex = inlinedCall.callStatementPosition();
				if(callScope == null)
					throw new IllegalStateException("Inlined function call has no exact occurrence authority");
				// Nested inlining and CSE may map several lexical calls to one physical RHS.
				// Their function key and statement position distinguish the boundary markers;
				// the real HOP remains a single operation, not an exclusive call placeholder.
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
					input = traceOnlyInlinedFunctionInput(input);
					expanded.add(input);
					logicalInlinedFunctionInputs.add(new PlacementAnalysis.LogicalInlinedFunctionInputFact(
						Optional.ofNullable(argument == null ? null : argument.key()), input.key(), inputPosition,
						Optional.ofNullable(argument == null ? null : argument.valueVersion()), input.valueVersion()));
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
			Collections.unmodifiableMap(expandedScopes), Collections.unmodifiableMap(outputBoundaryKeys),
			List.copyOf(logicalInlinedFunctionInputs));
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
		if(authorities.stream().anyMatch(authority -> authority.anchors().size() != 1))
			return List.of();
		DurableAnchorKey representative = authorities.stream().map(authority -> authority.anchors().get(0))
			.sorted().findFirst().orElseThrow();
		return authorities.stream().allMatch(authority -> sameExactAnchorGeometry(
			representative, authority.anchors().get(0))) ? List.of(representative) : List.of();
	}

	private static Node optionalExactDataNode(Map<Hop,Node> blockNodes, OpOpData operation, String name,
		InlinedFunctionCallBoundary call, String boundary, int position) {
		if(name == null || name.isBlank())
			throw new IllegalStateException("Inlined function " + boundary + " has no compiler-owned variable identity");
		List<Node> matches = blockNodes.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof DataOp)
			.filter(entry -> ((DataOp) entry.getKey()).getOp() == operation)
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
		if(matches.size() > 1)
			throw new IllegalStateException("Inlined function boundary requires one exact compiler-owned occurrence: "
				+ call.functionKey() + " callStatement=" + call.callStatementPosition() + ' ' + boundary + '='
					+ position + " variable=" + name + " operation=" + operation + " matches=" + matches.size());
		// HOP rewrites may substitute a transient read directly into an inlined body.  In
		// that case the ordinary HOP input edge is the physical authority and there is no
		// emitted call-boundary operation to attach here.
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static Node optionalExactNamedNode(Map<Hop,Node> blockNodes, String name,
		InlinedFunctionCallBoundary call, String boundary, int position) {
		if(name == null || name.isBlank())
			throw new IllegalStateException("Inlined function " + boundary + " has no compiler-owned variable identity");
		List<Node> matches = blockNodes.entrySet().stream()
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
		if(matches.size() > 1)
			throw new IllegalStateException("Inlined function boundary requires one exact compiler-owned occurrence: "
				+ call.functionKey() + " callStatement=" + call.callStatementPosition() + ' ' + boundary + '='
					+ position + " variable=" + name + " matches=" + matches.size());
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static Node requireExactInlinedOutputNode(Map<Hop,Node> blockNodes,
		InlinedFunctionOutputBoundary output, Map<String,InlinedFunctionOutputBoundary> bindings,
		InlinedFunctionCallBoundary call) {
		String resolvedBound = resolveInlinedOutput(output, bindings);
		List<Node> boundMatches = exactNamedNodes(blockNodes, resolvedBound);
		if(boundMatches.size() == 1)
			return boundMatches.get(0);
		if(boundMatches.size() > 1)
			throw inlinedOutputResolutionFailure(call, output, resolvedBound, boundMatches.size(), List.of());

		List<String> targetAliases = compilerOwnedInlinedOutputTargets(output, bindings);
		for(String target : targetAliases) {
			List<Node> targetWrites = exactDataNodes(blockNodes, OpOpData.TRANSIENTWRITE, target);
			if(targetWrites.size() == 1)
				return targetWrites.get(0);
			if(targetWrites.size() > 1)
				throw inlinedOutputResolutionFailure(call, output, resolvedBound, 0, targetAliases);
		}
		throw inlinedOutputResolutionFailure(call, output, resolvedBound, 0, targetAliases);
	}

	private static List<Node> exactNamedNodes(Map<Hop,Node> blockNodes, String name) {
		return blockNodes.entrySet().stream()
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
	}

	private static List<Node> exactDataNodes(Map<Hop,Node> blockNodes, OpOpData operation, String name) {
		return blockNodes.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof DataOp)
			.filter(entry -> ((DataOp) entry.getKey()).getOp() == operation)
			.filter(entry -> name.equals(entry.getKey().getName()))
			.map(Map.Entry::getValue).toList();
	}

	private static List<String> compilerOwnedInlinedOutputTargets(InlinedFunctionOutputBoundary output,
		Map<String,InlinedFunctionOutputBoundary> bindings) {
		List<String> targets = new ArrayList<>();
		String target = output.targetVariable();
		Set<String> visited = new LinkedHashSet<>();
		while(true) {
			if(!visited.add(target))
				throw new IllegalStateException("Cyclic compiler-owned inlined output target chain: " + visited);
			targets.add(target);
			String currentTarget = target;
			List<InlinedFunctionOutputBoundary> enclosing = bindings.values().stream()
				.filter(binding -> currentTarget.equals(binding.boundVariable())).toList();
			if(enclosing.isEmpty())
				break;
			if(enclosing.size() != 1)
				throw new IllegalStateException("Ambiguous compiler-owned inlined output target chain: "
					+ target + " matches=" + enclosing.size());
			target = enclosing.get(0).targetVariable();
		}
		return List.copyOf(targets);
	}

	private static IllegalStateException inlinedOutputResolutionFailure(InlinedFunctionCallBoundary call,
		InlinedFunctionOutputBoundary output, String resolvedBound, int boundMatches, List<String> targets) {
		return new IllegalStateException("Inlined function boundary requires one exact compiler-owned occurrence: "
			+ call.functionKey() + " callStatement=" + call.callStatementPosition() + " output="
			+ output.position() + " variable=" + resolvedBound + " matches=" + boundMatches
			+ " compilerTargets=" + targets);
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

	/**
	 * AST inlining binds the actual RHS directly in DMLTranslator's ids map: no FunctionOp or
	 * runtime call-input carrier is emitted, even when the actual's lexical name survives.
	 * Every physical expression and data-input edge is already represented by the compiled HOP
	 * occurrences. Retain this marker's call-site identity and optional argument constraint, but
	 * no placement anchors: anchors are executable authority, not passive trace metadata. Never
	 * create a duplicate physical decision. Ordinary (non-inlined) function inputs still
	 * require their exact runtime boundary authority.
	 */
	private static Node traceOnlyInlinedFunctionInput(Node input) {
		if(input.kind() != NodeKind.FUNCTION_INPUT)
			throw new IllegalArgumentException("Trace-only inlined input requires a function input marker");
		List<Exclusion> exclusions = input.legalAlternatives().stream()
			.map(state -> new Exclusion(state, ReasonCode.NON_EMITTED_INLINED_FUNCTION_INPUT,
				"ast-inlined-input-has-no-runtime-call-carrier"))
			.toList();
		return new Node(input.key(), input.kind(), input.valueVersion(), false,
			List.of(), exclusions, List.of());
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
		Map<FunctionOutputBoundaryKey,CompiledHopKey> outputBoundaryKeys,
		List<PlacementAnalysis.LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs) { }

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
			if(isTransientRead(occurrences.get(i).hop()) && sourceCount > 0) {
				for(int definition : cfg.reachingDefinitions().get(i))
					// A CFG definition/read edge is one runtime variable binding, not a
					// materializing function boundary.  In particular, cpvar preserves a
					// FederationMap; it does not turn FED/FOUT into CP/LOUT.  Therefore every
					// reaching TWrite must select the exact placement consumed by the TRead.
					// Using CONJUNCTIVE here was unsound because its LOUT arm deliberately
					// accepts a FOUT source for boundaries that can charge a download.  No such
					// action exists on this CFG-only edge, so that combination reached runtime
					// as a planner-local TRead backed by a federated symbol. A unique source uses
					// SAME_VALUE_PLACEMENT because execution type is not part of the runtime value;
					// a phi keeps the stricter tuple equality required by every reaching source.
					constraints.add(new Constraint(sourceCount > 1
						? ConstraintKind.SAME_PLACEMENT : ConstraintKind.SAME_VALUE_PLACEMENT,
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
		NodeShapeFact shape, AbstractShapeFact abstractShape, SinglePartitionFacts singlePartitions, List<NodeShapeFact> inputShapeFacts,
		List<List<FType>> inputDomains,
		List<CandidateRuleKey> candidateRuleDomainKeys, List<CandidateRuleFact> candidateRuleFacts) {
		int candidateFactStart = candidateRuleFacts.size();
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
			excluded.putIfAbsent(forbidden, new Exclusion(forbidden, ReasonCode.RECOMPILE_CP_FOUT, "recompile-context forbids CP/FOUT"));
		}
		boolean transientAccess = isTransientRead(hop) || isTransientWrite(hop);
		forEachInputCombination(inputDomains, inputs -> {
			CandidateRuleKey candidateKey = new CandidateRuleKey(key, candidateInputStates(inputs));
			candidateRuleDomainKeys.add(candidateKey);
			Set<CandidateEmissionFact> exactEmissionFacts = new LinkedHashSet<>();
			if(legal.contains(cp))
				exactEmissionFacts.add(candidateEmissionFact(exactLegalState(legal, cp), false, null));
			OpCaps caps;
			DecisionEvidence evidence;
			boolean shapeDependent;
			try {
				evidence = oracle.decideWithEvidence(hop, inputs,
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
			ExactRightIndexRuntimeFact exactRightIndex = exactRightIndexRuntimeFact(
				hop, inputs, inputAnchors, caps);
			FType exactVectorLocalType = exactAggregateBinaryVectorLocalType(hop, abstractShape, inputs);
			FType outType = exactRightIndex == null
				? caps.foutFType().orElse(firstFType(inputs)) : exactRightIndex.outputFType();
			if(caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
				&& exactVectorLocalType != null)
				outType = exactVectorLocalType;
			boolean hasExactVectorLocalEmission = exactVectorLocalType != null;
			boolean exactShapeDependent = shapeDependent
				|| exactRightIndex != null
				|| caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.LOUT
					&& hasExactVectorLocalEmission;
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType, exactShapeDependent);
			String detail = "inputs=" + inputEvidence(inputs) + "|proof=" + evidence.shapeProof()
				+ '|' + caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
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
						new PlacementState(ExecType.FED, FederatedOutput.LOUT, outType, exactShapeDependent));
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
						new PlacementState(ExecType.CP, FederatedOutput.FOUT, materializationFType,
							exactShapeDependent));
					if(cpFout != null) {
						DerivedFoutMaterializationActionKey action = derivedFoutAction(key, value, candidateKey,
							exactLegalState(legal, cp), cpFout, materializationAnchor,
							materialization.owner(), materialization.ownerFType(), materializationFType);
						exactEmissionFacts.add(candidateEmissionFact(cpFout, false, null, action));
					}
					if(caps.placement() == FederatedOutput.LOUT) {
						PlacementState derivedFout = addLegalCandidate(legal, excluded,
							new PlacementState(ExecType.FED, FederatedOutput.FOUT, materializationFType,
								exactShapeDependent));
						if(derivedFout != null) {
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
						if(supplemental != null)
							exactEmissionFacts.add(candidateEmissionFact(supplemental, false, inputType));
					}
			}
			candidateRuleFacts.add(candidateRuleFact(hop, candidateKey, inputShapeFacts, inputs, caps,
				evidence, exactRightIndex, exactEmissionFacts));
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
		PlacementEmissionState sourceEmission = new PlacementEmissionState(exactSource, false);
		CandidateEmissionFact emission = exactSource.fType() == FType.PART || exactSource.fType() == FType.OTHER
			? new CandidateEmissionFact(sourceEmission, exactSource.fType(), null,
				List.of(CandidateEmissionRealization.sourceLineage(sourceEmission,
					"literal-federated-source:" + hop.getName())))
			: candidateEmissionFact(exactSource, false, exactSource.fType());
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
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(), exact,
					emission.realizations()));
			}
			bound.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(bound);
	}

	/** Gives every explicit output materialization an exact current-value layout. */
	private static List<CandidateRuleFact> bindDerivedFoutRealizations(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) {
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			List<CandidateEmissionFact> emissions = new ArrayList<>(fact.allowedEmissionFacts().size());
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				DerivedFoutMaterializationActionKey action = emission.derivedFoutAction();
				if(action == null) {
					emissions.add(emission);
					continue;
				}
				DurableAnchorKey outputAnchor = nativeOutputAnchor(action.durableAnchor(),
					emission.emissionState().placementState().fType(),
					shapes.get(origins.get(fact.key().parentOccurrence())), fact.key().parentOccurrence());
				if(outputAnchor == null) {
					emissions.add(emission);
					continue;
				}
				PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
					fact.key().parentOccurrence(), "derived-fout:" + action.normalizedSignature());
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(), action,
					List.of(CandidateEmissionRealization.durable(emission.emissionState(), outputAnchor,
						List.of(proof), List.of()))));
			}
			result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
	}

	private record ExactRealizationOption(CandidateRealizationReference reference,
		CandidateRealizationSupportClause clause, ValueVersionKey valueVersion) { }
	private record DirectTemplateKey(CandidateRuleKey rule, PlacementEmissionState emission,
		DerivedFoutMaterializationActionKey action) { }

	/** Reuses equal graph action objects so realization bindings retain exact action identity authority. */
	private static List<NeutralPlacementGraph.RelocationAction> canonicalRelocationActions(
		List<NeutralPlacementGraph.RelocationAction> derived,
		List<NeutralPlacementGraph.RelocationAction> canonical) {
		List<NeutralPlacementGraph.RelocationAction> result = new ArrayList<>(derived.size());
		for(NeutralPlacementGraph.RelocationAction action : derived)
			result.add(canonical.stream().filter(action::equals).findFirst().orElse(action));
		return List.copyOf(result);
	}

	/** Adds exact action-backed native clauses without expanding ancestor support choices. */
	private List<CandidateRuleFact> bindRelocationCandidateRealizations(List<CandidateRuleFact> facts,
		List<Node> nodes, List<CompiledInputEdgeFact> compiledEdges,
		List<NeutralPlacementGraph.RelocationAction> relocations, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> inputs = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : compiledEdges)
			inputs.computeIfAbsent(edge.consumer(), ignored -> new java.util.TreeMap<>())
				.put(edge.inputPosition(), edge.producer());
		Map<ValueVersionKey,List<ExactRealizationOption>> optionsByValue = new LinkedHashMap<>();
		for(CandidateRuleFact fact : facts) {
			Node node = nodesByKey.get(fact.key().parentOccurrence());
			if(node == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations())
					if(executableSourceRealization(fact.key(), realization))
						for(CandidateRealizationSupportClause clause : realization.supportClauses())
							optionsByValue.computeIfAbsent(node.valueVersion(), ignored -> new ArrayList<>())
								.add(new ExactRealizationOption(CandidateRealizationReference.of(fact.key(), realization),
									clause, node.valueVersion()));
		}
		Map<CompiledHopKey,List<NeutralPlacementGraph.RelocationAction>> actionsByConsumer = new IdentityHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : relocations)
			for(ObligationKey obligation : action.obligations())
				if(!actionsByConsumer.computeIfAbsent(obligation.consumer(), ignored -> new ArrayList<>()).contains(action))
					actionsByConsumer.get(obligation.consumer()).add(action);
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			Hop owner = origins.get(fact.key().parentOccurrence());
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> exact = new ArrayList<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					List<CandidateRealizationSupportClause> nonActionClauses = realization.supportClauses().stream()
						.filter(clause -> clause.inputBindings().stream().noneMatch(binding ->
							binding.kind() == CandidateInputBindingKind.RELOCATION))
						.toList();
					if(!nonActionClauses.isEmpty() && (realization.key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
						|| nonActionClauses.stream().anyMatch(clause -> clause.nativeWorkerPoolWitness() != null
							|| !clause.inputBindings().isEmpty())
						|| fact.key().orderedInputs().stream().noneMatch(CandidateInputState::present)))
						exact.add(CandidateEmissionRealization
							.fromAlreadyCanonicalSupportClauses(realization.key(), nonActionClauses));
				}
				List<NeutralPlacementGraph.RelocationAction> consumerActions = actionsByConsumer
					.getOrDefault(fact.key().parentOccurrence(), List.of()).stream()
					.filter(action -> action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == fact.key().parentOccurrence()
							&& obligation.requiredPlacement().equals(emission.emissionState().placementState())))
					.sorted().toList();
				List<DurableAnchorKey> targetPools = consumerActions.stream().map(action -> action.key().durableAnchor())
					.distinct().sorted().toList();
				for(DurableAnchorKey targetPool : targetPools) {
					List<List<CandidateRealizationInputBinding>> choices = new ArrayList<>();
					boolean complete = true;
					for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
						CandidateInputState input = fact.key().orderedInputs().get(position);
						if(!input.present())
							continue;
						CompiledHopKey producer = inputs.getOrDefault(fact.key().parentOccurrence(), Map.of()).get(position);
						Hop producerHop = producer == null ? null : origins.get(producer);
						if(producerHop == null || !isMatrixShape(shapes, producerHop))
							continue;
						Node producerNode = nodesByKey.get(producer);
						if(producerNode == null) {
							complete = false;
							break;
						}
						List<ExactRealizationOption> sourceOptions = optionsByValue
							.getOrDefault(producerNode.valueVersion(), List.of()).stream()
								.filter(option -> option.reference().rule().parentOccurrence() == producer)
								.toList();
						int inputPosition = position;
						List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
						bindings.addAll(sourceOptions.stream().filter(option -> {
								DurableAnchorKey pool = candidatePool(option);
								if(option.reference().realization().emissionState().placementState().fType()
									!= input.fType())
									return false;
								if(pool != null) {
									if(PlacementIdentity.samePhysicalWorkerPool(pool, targetPool))
										return true;
									if(isAggregateBinaryColTRow(fact)) {
										DurableAnchorKey col = pool.fType() == FType.COL ? pool : targetPool;
										DurableAnchorKey row = pool.fType() == FType.ROW ? pool : targetPool;
										return PlacementIdentity.samePhysicalColTransposeAlignment(col, row);
									}
									return false;
								}
								DurableAnchorKey residency = option.clause().nativeWorkerPoolWitness();
								DurableAnchorKey targetResidency = nativeResidencyWitness(
									targetPool, input.fType(), fact.key().parentOccurrence());
								boolean endpointDirect = endpointOnlyDirectLoutInput(owner, fact, emission)
									&& residency != null && targetResidency != null
									&& PlacementIdentity.samePhysicalWorkerEndpoints(
										residency, targetResidency);
								return endpointDirect;
							}).map(option -> CandidateRealizationInputBinding.direct(inputPosition, option.reference()))
							.distinct().toList());
						for(NeutralPlacementGraph.RelocationAction action : consumerActions) {
							// The action output, not the consumer output, must satisfy this exact oracle input row.
							if(action.key().materializationFType() != input.fType()
								|| !PlacementIdentity.samePhysicalWorkerPool(action.key().durableAnchor(), targetPool)
								|| !action.key().sourceValueVersion().equals(producerNode.valueVersion())
								|| action.obligations().stream().noneMatch(obligation ->
									obligation.consumer() == fact.key().parentOccurrence()
										&& obligation.inputPosition() == inputPosition
										&& obligation.requiredPlacement().equals(
											emission.emissionState().placementState())))
								continue;
							bindings.addAll(sourceOptions.stream().filter(option -> {
								DurableAnchorKey pool = candidatePool(option);
								return pool == null || !PlacementIdentity.samePhysicalLayout(
									pool, action.key().durableAnchor());
							}).map(option -> CandidateRealizationInputBinding.relocation(
								inputPosition, option.reference(), action.key())).toList());
						}
						bindings = bindings.stream().distinct()
							.sorted(PlacementAnalysis.<CandidateRealizationInputBinding>canonicalComparator()).toList();
						if(bindings.isEmpty()) {
							complete = false;
							break;
						}
						choices.add(bindings);
					}
					if(!complete)
						continue;
					DurableAnchorKey outputAnchor = NativePlacementContinuity.recomputesNativePartitionRanges(
						owner, emission.emissionState().placementState().fType())
						? null : nativeOutputAnchor(targetPool,
						emission.emissionState().placementState().fType(),
						shapes.get(owner), fact.key().parentOccurrence());
					enumerateBindingAssignments(choices, 0, new ArrayList<>(), assignment -> {
						List<PlacementProofKey> proofs = assignment.stream()
							.filter(binding -> binding.kind() == CandidateInputBindingKind.RELOCATION)
							.map(CandidateRealizationInputBinding::relocationAction).distinct().sorted()
							.map(action -> new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
								fact.key().parentOccurrence(), "relocation:" + action.normalizedSignature()))
							.toList();
						// A native FED/LOUT aggregate still depends on the exact selected
						// input realization. Retain direct-only alternatives as well as uploads.
						if(emission.derivedFoutAction() != null && !assignment.isEmpty()) {
							// A derived FOUT executes the action's exact LOUT source first, then
							// materializes that result at the already-bound durable output anchor.
							// Preserve the output-action proof while attaching the exact direct or
							// relocated input receipts required by the source computation.
							for(CandidateEmissionRealization output : emission.realizations())
								if(output.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP)
									for(CandidateRealizationSupportClause outputClause : output.supportClauses())
										exact.add(new CandidateEmissionRealization(output.key(),
											mergeProofs(outputClause.proofDependencies(), proofs), assignment));
						}
						else if(!proofs.isEmpty() || emission.emissionState().placementState().execType() == ExecType.FED
							&& emission.emissionState().placementState().output() == FederatedOutput.LOUT
							&& !assignment.isEmpty()) {
							if(emission.emissionState().placementState().output() == FederatedOutput.LOUT)
								exact.add(CandidateEmissionRealization.local(emission.emissionState(), proofs, assignment));
							else if(outputAnchor != null)
								exact.add(CandidateEmissionRealization.durable(emission.emissionState(), outputAnchor,
									proofs, assignment));
							else if(!proofs.isEmpty()
								&& NativePlacementContinuity.recomputesNativePartitionRanges(
									owner, emission.emissionState().placementState().fType())) {
								DurableAnchorKey outputPool = nativeResidencyWitness(targetPool,
									emission.emissionState().placementState().fType(), fact.key().parentOccurrence());
								if(outputPool != null)
									exact.add(CandidateEmissionRealization.nativeLineageDynamicLayout(
										emission.emissionState(), "relocation-native:"
											+ fact.key().parentOccurrence().normalizedSignature()
											+ "|pool=" + outputPool.normalizedSignature(),
										outputPool, proofs, assignment));
							}
						}
					}, complexityMetrics);
				}
				if(!exact.isEmpty())
					emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
						emission.derivedFoutAction(), exact));
				else
					emissions.add(emission);
			}
			result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
	}

	private static List<PlacementProofKey> mergeProofs(List<PlacementProofKey> left,
		List<PlacementProofKey> right) {
		return java.util.stream.Stream.concat(left.stream(), right.stream()).distinct()
			.sorted(PlacementAnalysis.<PlacementProofKey>canonicalComparator()).toList();
	}

	private static DurableAnchorKey candidatePool(ExactRealizationOption option) {
		DurableAnchorKey anchor = option.reference().realization().durableAnchor();
		return anchor != null ? anchor
			: option.clause().nativeWorkerPoolLayoutExact() ? option.clause().nativeWorkerPoolWitness() : null;
	}

	/**
	 * A local-output FED aggregate does not publish the dynamic input ranges as a
	 * new map. Unary aggregates need only the selected input's worker residency.
	 * COL x ROW aggregate-binary consumes co-resident transpose-aligned shards and
	 * reduces their partial products locally; its exact source receipts preserve
	 * that lineage while the endpoint witness identifies the runtime worker pool.
	 */
	private static boolean endpointOnlyDirectLoutInput(Hop owner, CandidateRuleFact fact,
		CandidateEmissionFact emission) {
		PlacementState state = emission.emissionState().placementState();
		if(state.execType() != ExecType.FED || state.output() != FederatedOutput.LOUT
			&& !emission.emissionState().derivedFedFout())
			return false;
		List<FType> present = fact.key().orderedInputs().stream()
			.filter(CandidateInputState::present).map(CandidateInputState::fType).toList();
		if(present.size() == 1 || owner instanceof AggUnaryOp)
			return true;
		return owner instanceof AggBinaryOp && isAggregateBinaryColTRow(fact);
	}

	private static boolean isAggregateBinaryColTRow(CandidateRuleFact fact) {
		List<FType> present = fact.key().orderedInputs().stream()
			.filter(CandidateInputState::present).map(CandidateInputState::fType).toList();
		return present.size() == 2 && present.get(0) == FType.COL && present.get(1) == FType.ROW;
	}

	static void enumerateBindingAssignments(List<List<CandidateRealizationInputBinding>> choices,
		int ordinal, List<CandidateRealizationInputBinding> current,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer,
		SearchSpaceMetrics metrics) {
		if(metrics != null)
			metrics.recordRelocationPrefix(ordinal);
		if(ordinal == choices.size()) {
			if(metrics != null)
				metrics.recordRelocationLeaf();
			consumer.accept(List.copyOf(current));
			return;
		}
		for(CandidateRealizationInputBinding binding : choices.get(ordinal)) {
			current.add(binding);
			try {
				enumerateBindingAssignments(choices, ordinal + 1, current, consumer, metrics);
			}
			finally {
				current.remove(current.size() - 1);
			}
		}
	}

	private static List<CandidateRuleFact> removeUngroundedStagingRealizations(List<CandidateRuleFact> facts) {
		Set<CandidateRealizationReference> currentReferences = new HashSet<>();
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(executableSourceRealization(fact.key(), realization))
							currentReferences.add(CandidateRealizationReference.of(fact.key(), realization));
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
					// CFG/direct rebuilding can replace exact source identities while an
					// old OR clause remains merged into the same output layout. That clause
					// has no authority in the current domain; keep every supported alternative
					// and let the surrounding fixed point regenerate bindings, never remap
					// an expired reference merely because another map has equal geometry.
					List<CandidateRealizationSupportClause> clauses = realization.supportClauses().stream()
						.filter(clause -> clause.inputBindings().stream()
							.allMatch(binding -> currentReferences.contains(binding.source())))
						.toList();
					if(!clauses.isEmpty())
						realizations.add(CandidateEmissionRealization
							.fromAlreadyCanonicalSupportClauses(realization.key(), clauses));
				}
				if(!realizations.isEmpty())
					emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
						emission.derivedFoutAction(), realizations));
			}
			if(emissions.isEmpty())
				result.add(new CandidateRuleFact(fact.key(), CandidateEvaluationStatus.PROFILE_ERROR,
					fact.capability(), fact.shapeProof(),
					new CandidateProfileFact(List.of(), "NO_EXECUTABLE_REALIZATION"),
					List.of(), "NO_EXECUTABLE_REALIZATION"));
			else
				result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
					fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
	}

	private record ExecutableNodeProjection(List<Node> nodes, List<Integer> changedOrdinals) { }

	/** Checks action/realization identity without mutating the converged publication. */
	private static void verifyPublishedRelocationRealizations(List<CandidateRuleFact> facts,
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
								throw new IllegalStateException("Final publication has an ungrounded relocation realization");
							usedActions.add(action.key());
						}
		}
		for(NeutralPlacementGraph.RelocationAction action : actions)
			if(action.directSourcePlacements().isEmpty() && !usedActions.contains(action.key()))
				throw new IllegalStateException("Final publication has an unbound relocation action");
	}

	/** Removes selectable FOUT states that have no final executable candidate realization authority. */
	private static ExecutableNodeProjection projectCandidateNodesToExecutableStates(List<Node> nodes,
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

	private static List<Integer> changedCompiledNodeOrdinals(List<Node> before, List<Node> after,
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

	private static CandidateEmissionRealization rebindRealization(CandidateEmissionRealization realization,
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

	private static List<CandidateRuleFact> bindExactCandidateEmissionRealizations(
		List<CandidateRuleFact> facts, List<Node> nodes, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) {
		List<CandidateRuleFact> rebound = bindExactCandidateEmissionStates(facts, nodes);
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		List<CandidateRuleFact> result = new ArrayList<>(rebound.size());
		for(CandidateRuleFact fact : rebound) {
			Node node = nodesByKey.get(fact.key().parentOccurrence());
			Hop origin = origins.get(fact.key().parentOccurrence());
			NodeShapeFact shape = origin == null ? null : shapes.get(origin);
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE || node == null || shape == null) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = new ArrayList<>(emission.realizations());
				PlacementState state = emission.emissionState().placementState();
				boolean literalFederatedSource = origin instanceof DataOp data
					&& data.getOp() == OpOpData.FEDERATED;
				if(literalFederatedSource && state.output() == FederatedOutput.FOUT && state.fType() != null) {
					boolean boundLiteralAnchor = false;
					for(DurableAnchorKey anchor : node.anchors()) {
						if(anchor.fType() != state.fType() || !outputGeometryCompatible(shape, anchor))
							continue;
						boundLiteralAnchor = true;
						PlacementProofKey anchorProof = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
							node.key(), anchor.normalizedSignature());
						for(CandidateEmissionRealization prior : emission.realizations())
							for(CandidateRealizationSupportClause clause : prior.supportClauses())
								realizations.add(CandidateEmissionRealization.durable(emission.emissionState(), anchor,
									appendProof(clause.proofDependencies(), anchorProof), clause.inputBindings()));
					}
					if(boundLiteralAnchor)
					realizations.removeIf(realization -> realization.key().layoutKind()
							== PlacementLayoutKind.NATIVE_LINEAGE && realization.supportClauses().stream()
								.allMatch(clause -> clause.inputBindings().isEmpty()));
				}
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
					emission.derivedFoutAction(), realizations));
			}
			result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
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
			ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(consumer,
				consumerKey.normalizedSignature(), inputShapeFacts, List.of(inputPosition));
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
				DetachedConsumerProfileKey key = new DetachedConsumerProfileKey(producerKey, parentOrdinal,
					PlacementGraphFingerprint.semanticStructuralKey(parent), producerInputPositions);
				ConsumerProfileEvaluation evaluation = evaluateConsumerProfile(parent, key.toString(), inputShapeFacts,
					producerInputPositions);
				facts.add(new DetachedConsumerProfileFact(key, evaluation.status(), evaluation.allowedTargetTypes(),
					evaluation.failureCode()));
			}
		}
	}

	private record ConsumerProfileEvaluation(CandidateEvaluationStatus status,
		List<FType> allowedTargetTypes, String failureCode) { }

	private static NodeShapeFact deriveNodeShapeFact(Hop hop) {
		var shape = OracleFacade.nodeShape(Objects.requireNonNull(hop, "hop"));
		// InitFEDInstruction establishes this literal source's dimensions from its own
		// range endpoints. Do not transfer these dimensions through inherited anchors:
		// transpose, indexing and other derived values can have different geometry.
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& (shape.rows() < 0 || shape.cols() < 0)) {
			List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
			long rows = -1, cols = -1;
			for(AnchorPartition partition : partitions) {
				if(partition.begin().get(0) < 0 || partition.begin().get(1) < 0
					|| partition.end().get(0) <= partition.begin().get(0)
					|| partition.end().get(1) <= partition.begin().get(1))
					return new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
				rows = Math.max(rows, partition.end().get(0));
				cols = Math.max(cols, partition.end().get(1));
			}
			return new NodeShapeFact(shape.dataType(), shape.rows() < 0 ? rows : shape.rows(),
				shape.cols() < 0 ? cols : shape.cols());
		}
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

	private ConsumerProfileEvaluation evaluateConsumerProfile(Hop consumer, String occurrence,
		List<NodeShapeFact> inputShapeFacts, List<Integer> targetPositions) {
		List<FType> allowed = new ArrayList<>();
		for(FType candidate : PlacementCandidateRuleResolver.matrixFTypeCandidates()) {
			List<List<FType>> inputLayouts =
				consumerProfileInputDomains(inputShapeFacts, targetPositions, candidate);
			try {
				FTypeProfile profile = oracle.inferProfile(consumer, inputLayouts, null);
				if(profile != null && profile.outputs() != null && !profile.outputs().isEmpty())
					allowed.add(candidate);
			}
			catch(RuntimeException e) {
				throw oracleRuntimeFailure("consumer profile", occurrence, consumer, inputLayouts, e);
			}
		}
		return new ConsumerProfileEvaluation(CandidateEvaluationStatus.AVAILABLE, List.copyOf(allowed), "");
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
		ExactRightIndexRuntimeFact exactRightIndex, Set<CandidateEmissionFact> exactEmissionFacts) {
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
				FTypeProfile inferred = oracle.inferProfile(hop, profileInputs, null);
				profile = new CandidateProfileFact(inferred == null ? List.of() : inferred.outputs(), "");
			}
		}
		catch(RuntimeException e) {
			throw oracleRuntimeFailure("candidate profile", key.parentOccurrence().normalizedSignature(), hop,
				profileInputs, e);
		}
		CandidateEvaluationStatus status = profile.available() ? CandidateEvaluationStatus.AVAILABLE
			: CandidateEvaluationStatus.PROFILE_ERROR;
		return new CandidateRuleFact(key, status, capability, shapeProof, profile,
			status == CandidateEvaluationStatus.AVAILABLE ? List.copyOf(exactEmissionFacts) : List.of(),
			profile.evaluationFailure());
	}

	/**
	 * Replays the runtime {@code FederationMap.filter} layout transition for a right-index operation when
	 * both the literal bounds and the exact input FederationMap anchor are available.  This belongs to the
	 * shared placement analysis rather than an individual selector: all selectors must see the same physical
	 * result layout, including the ROW/COL -&gt; FULL transition for a slice contained in one partition.
	 */
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

	private static Long exactLiteralLong(Hop hop) {
		return hop instanceof LiteralOp ? ((LiteralOp) hop).getLongValue() : null;
	}

	private record ExactRightIndexRuntimeFact(FType outputFType, String literalBounds,
		String inputAnchor, int filteredPartitions) { }

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

	static List<AnchorPartition> fedInitLiteralPartitions(DataOp data) {
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
		Map<Hop,NodeShapeFact> sourceCompiledFactsByHop,
		Map<Hop,AbstractShapeFact> abstractFactsByHop, SinglePartitionFacts singlePartitions,
		Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock, CfgAnalysis cfg) {
		CandidateReplay current = closeLatentWdivmmRuntimeOutputContracts(
			new CandidateReplay(List.copyOf(nodes), List.copyOf(domainKeys),
				List.copyOf(candidateRuleFacts), List.copyOf(logicalTransientInputs), List.of()),
			compiledInputEdges, origins, factsByHop, sourceCompiledFactsByHop);
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
					materialization.changedOrdinals()), factsByHop, abstractFactsByHop, singlePartitions,
				ordinalsByBlock, cfg, compiledInputEdges, origins, sourceCompiledFactsByHop);
			Set<Integer> recompileDescendants = exactAffectedDescendants(occurrences,
				materialization.changedOrdinals(), ordinalsByBlock, factsByHop).stream()
				.filter(ordinal -> replayed.nodes().get(ordinal).key().recompileContext().equals("recompile"))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			current = recompileDescendants.isEmpty() ? replayed
				: retainRecompileMaterializationLayoutChanges(replayed, current,
					recompileDescendants);
			current = closeLatentWdivmmRuntimeOutputContracts(current,
				compiledInputEdges, origins, factsByHop, sourceCompiledFactsByHop);
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
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop,
		Map<Hop,NodeShapeFact> sourceCompiledFactsByHop) {
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
			List<Integer> indexes = factIndexes.getOrDefault(node.key(), List.of());
			if(indexes.isEmpty())
				continue;
			PlacementCostSemantics.LatentWdivmmTransposePairFact runtime =
				PlacementCostSemantics.latentWdivmmTransposePairFact(origins, factsByHop, sourceCompiledFactsByHop,
					compiledInputEdges, nodes, node.key());
			if(runtime == null || !runtime.nativeOutputMustBeLocal()
				|| runtime.partitionedInputFType() == null)
				continue;
			boolean factChanged = false;
			for(int factIndex : indexes) {
				CandidateRuleFact prior = facts.get(factIndex);
				if(prior.status() != CandidateEvaluationStatus.AVAILABLE)
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
		PlacementState runtimeLocal = new PlacementState(ExecType.FED, FederatedOutput.LOUT,
			executionFType, false);
		CandidateEmissionFact runtimeEmission = candidateEmissionFact(runtimeLocal, false, executionFType);
		emissions.putIfAbsent(runtimeEmission.emissionState(), runtimeEmission);
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
				// CP -> FOUT is a materialization of an executable CP/LOUT result.
				// Do not invent the target state when the exact node domain has no such
				// source execution (for example, privacy can make a node FED-only).
				boolean hasCpLoutSource = legal.stream().anyMatch(state ->
					state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT);
				if(allowCpFout && hasCpLoutSource) {
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
		Map<Hop,NodeShapeFact> factsByHop, Map<Hop,NodeShapeFact> sourceCompiledFactsByHop,
		Map<CompiledHopKey,Privacy> privacyByKey) {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer =
			matrixEdgesByConsumer(compiledInputEdges, nodesByKey);
		WorkerPoolAnchorResolver workerPoolAnchors = new WorkerPoolAnchorResolver(nodesByKey,
			matrixEdgesByConsumer, candidateRuleFacts, logicalTransientInputs, constraints, origins, factsByHop,
			privacyByKey);
		Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByConsumer = new IdentityHashMap<>();
		for(CandidateRuleFact fact : candidateRuleFacts)
			candidateFactsByConsumer.computeIfAbsent(fact.key().parentOccurrence(),
				ignored -> new ArrayList<>()).add(fact);
		Map<CompiledHopKey,PlacementCostSemantics.LatentWdivmmTransposePairFact>
			latentWdivmmPairsByOwner = new IdentityHashMap<>();
		for(Node node : nodes) {
			PlacementCostSemantics.LatentWdivmmTransposePairFact pair =
				PlacementCostSemantics.latentWdivmmTransposePairFact(origins, factsByHop, sourceCompiledFactsByHop,
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
		private final Map<String,CandidateEmissionRealization> realizationsByReference = new java.util.TreeMap<>();
		private final Map<CompiledHopKey,Map<FType,List<LogicalTransientInputFact>>> logicalTransientInputsByRead =
			new IdentityHashMap<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> functionInputsByRead = new IdentityHashMap<>();
		private final Set<CompiledHopKey> declaredFunctionInputs =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CompiledHopKey,List<CompiledHopKey>> functionOutputSourcesByAlias =
			new IdentityHashMap<>();
		private final Map<String,List<CompiledHopKey>> cfgDefinitionSourcesByReference = new LinkedHashMap<>();
		private final NativePlacementContinuity nativeContinuity;
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
			this(nodesByKey, matrixEdgesByConsumer, candidateRuleFacts, logicalTransientInputs,
				constraints, origins, factsByHop, Map.of());
		}

		private WorkerPoolAnchorResolver(Map<CompiledHopKey,Node> nodesByKey,
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> matrixEdgesByConsumer,
			List<CandidateRuleFact> candidateRuleFacts,
			List<LogicalTransientInputFact> logicalTransientInputs,
			java.util.Collection<Constraint> constraints,
			Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> factsByHop,
			Map<CompiledHopKey,Privacy> privacyByKey) {
			this.nodesByKey = nodesByKey;
			this.matrixEdgesByConsumer = matrixEdgesByConsumer;
			this.origins = origins;
			this.factsByHop = factsByHop;
			for(Node node : nodesByKey.values())
				cfgDefinitionSourcesByReference.computeIfAbsent(
					node.valueVersion().cfgReferenceSignature(), ignored -> new ArrayList<>()).add(node.key());
			cfgDefinitionSourcesByReference.values().forEach(keys -> keys.sort(null));
			for(CandidateRuleFact fact : candidateRuleFacts) {
				candidateFactsByProducer.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>())
					.add(fact);
				if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
						for(CandidateEmissionRealization realization : emission.realizations()) {
							CandidateRealizationReference reference = CandidateRealizationReference.of(
								fact.key(), realization);
							realizationsByReference.put(reference.normalizedSignature(), realization);
						}
			}
			for(LogicalTransientInputFact fact : logicalTransientInputs)
				for(FType type : fact.compatibility().stream()
					.map(TransientPlacementCompatibility::readerInput).filter(CandidateInputState::present)
					.map(CandidateInputState::fType).distinct().toList())
					logicalTransientInputsByRead.computeIfAbsent(fact.targetRead(),
						ignored -> new java.util.EnumMap<>(FType.class))
						.computeIfAbsent(type, ignored -> new ArrayList<>()).add(fact);
			logicalTransientInputsByRead.values().forEach(byType ->
				byType.values().forEach(facts -> facts.sort(null)));
			Map<CompiledHopKey,List<CompiledHopKey>> argumentsByBoundary = new IdentityHashMap<>();
			for(Constraint constraint : constraints)
				if(constraint.kind() == ConstraintKind.CONJUNCTIVE
					&& (constraint.evidence().startsWith("function-argument:")
						|| constraint.evidence().startsWith("inlined-function-argument:")))
					argumentsByBoundary.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
						.add(constraint.left());
			for(Constraint constraint : constraints)
				if(constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
						&& (constraint.evidence().startsWith("function-result:")
							|| constraint.evidence().startsWith("inlined-function-result:"))
					|| constraint.kind() == ConstraintKind.SAME_PLACEMENT
						&& constraint.evidence().startsWith("cfg-function-output-value:"))
					functionOutputSourcesByAlias
						.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
						.add(constraint.left());
			// A native encoded-primary carrier has a logical FRAME input, not a
			// compiled matrix edge. Transfer ROW pool/axis or single-FULL endpoint evidence; do not
			// promote a generic multi-return control coupling or raw column ranges.
			for(Constraint constraint : constraints)
				if(constraint.kind() == ConstraintKind.DOMINATES && constraint.inputPosition() == 0
					&& "multi-return-output-value".equals(constraint.evidence())
					&& NativePlacementContinuity.transformEncodePreservesPool(
						origins.get(constraint.right()), FType.ROW)
					&& origins.get(constraint.left()) == origins.get(constraint.right()).getInput(0))
					functionOutputSourcesByAlias.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
						.add(constraint.left());
			for(Constraint constraint : constraints) {
				if(constraint.kind() != ConstraintKind.SAME_PLACEMENT
					|| !"function-formal-input".equals(constraint.evidence()))
					continue;
				declaredFunctionInputs.add(constraint.right());
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
			functionOutputSourcesByAlias.values().forEach(keys -> {
				keys.sort(null);
				for(int index = keys.size() - 1; index > 0; index--)
					if(keys.get(index).equals(keys.get(index - 1)))
						keys.remove(index);
			});
			Map<CompiledHopKey,List<CompiledHopKey>> continuitySources = new IdentityHashMap<>();
			Set<CompiledHopKey> incompleteContinuitySources =
				Collections.newSetFromMap(new IdentityHashMap<>());
			for(Node node : nodesByKey.values()) {
				List<CompiledHopKey> sources = new ArrayList<>();
				List<String> cfgReferences = node.valueVersion().predecessorVersions().stream()
					.filter(value -> value.startsWith("cfg-definition:"))
					.map(value -> value.substring("cfg-definition:".length()))
					.distinct().sorted().toList();
				for(String reference : cfgReferences) {
					List<CompiledHopKey> definitions = cfgDefinitionSourcesByReference
						.getOrDefault(reference, List.of());
					if(definitions.isEmpty())
						incompleteContinuitySources.add(node.key());
					else
						sources.addAll(definitions);
				}
				List<CompiledHopKey> functionInputs = functionInputsByRead
					.getOrDefault(node.key(), List.of());
				if((hasCfgFunctionInputPredecessor(node) || declaredFunctionInputs.contains(node.key()))
					&& functionInputs.isEmpty())
					incompleteContinuitySources.add(node.key());
				sources.addAll(functionInputs);
				sources.addAll(functionOutputSourcesByAlias.getOrDefault(node.key(), List.of()));
				List<CompiledHopKey> canonicalSources = sources.stream().distinct().sorted().toList();
				if(!canonicalSources.isEmpty())
					continuitySources.put(node.key(), canonicalSources);
			}
			List<CompiledInputEdgeFact> compiledEdges = matrixEdgesByConsumer.values().stream()
				.flatMap(edges -> edges.values().stream()).toList();
			nativeContinuity = new NativePlacementContinuity(nodesByKey, origins,
				candidateRuleFacts, compiledEdges, continuitySources, incompleteContinuitySources, privacyByKey);
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
				boolean hasFunctionOutputAlias = functionOutputSourcesByAlias.containsKey(producer);
				boolean mixedCfgFunctionSource = hasMixedCfgFunctionSources(producer);
				if(resolved.isEmpty() && hasFunctionOutputAlias)
					resolved = resolveFunctionOutputInputs(producer, fType);
				else if(resolved.isEmpty() && mixedCfgFunctionSource)
					resolved = resolveMixedCfgFunctionInputs(producer, fType);
				if(resolved.isEmpty() && !hasFunctionOutputAlias && !mixedCfgFunctionSource) {
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
			Set<String> visitedReferences = new java.util.HashSet<>();
			for(LogicalTransientInputFact fact : logicalTransientInputsByRead.getOrDefault(producer, Map.of())
				.getOrDefault(fType, List.of()))
				for(TransientPlacementCompatibility edge : fact.compatibility()) {
					if(!edge.readerInput().present() || edge.readerInput().fType() != fType)
						continue;
					if(edge.proof().readerAnchor() != null)
						result.add(edge.proof().readerAnchor());
					DurableAnchorKey readerAnchor = edge.readerRealization().realization().durableAnchor();
					if(readerAnchor != null)
						result.add(readerAnchor);
					result.addAll(resolveCandidateRealization(edge.sourceRealization(), fType,
						visitedReferences));
				}
			return result;
		}

		private Set<DurableAnchorKey> resolveCandidateRealization(CandidateRealizationReference reference,
			FType fType, Set<String> visitedReferences) {
			// This query collects a union over reachable references, not path-specific
			// proofs. Keep visited nodes for the whole query so shared DAGs and cycles
			// are traversed once; do not cache a partial result across separate roots.
			String signature = reference.normalizedSignature();
			if(!visitedReferences.add(signature))
				return Set.of();
			Set<DurableAnchorKey> result = new java.util.TreeSet<>();
			CandidateEmissionRealization realization = realizationsByReference.get(signature);
			if(realization == null)
				return Set.of();
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				DurableAnchorKey provenPool = realization.provenWorkerPoolForOwnedClause(clause);
				if(provenPool != null && provenPool.fType() == fType)
					result.add(provenPool);
				for(var binding : clause.inputBindings())
					result.addAll(resolveCandidateRealization(binding.source(), fType, visitedReferences));
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

		/**
		 * Follows only compiler-declared function-return value aliases. Every reaching
		 * returned value must prove the same physical worker pool; an unknown or
		 * different endpoint closes the proof instead of inventing an anchor.
		 */
		private Set<DurableAnchorKey> resolveFunctionOutputAlias(CompiledHopKey producer,
			FType fType) {
			List<CompiledHopKey> sources = functionOutputSourcesByAlias.getOrDefault(
				producer, List.of());
			if(sources.isEmpty())
				return Set.of();
			boolean encodedPrimary = NativePlacementContinuity.transformEncodePreservesPool(
				origins.get(producer), FType.ROW);
			if(encodedPrimary && fType != FType.ROW && fType != FType.FULL)
				return Set.of(); // Encoding does not preserve column partition intervals.
			Set<DurableAnchorKey> common = null;
			for(CompiledHopKey source : sources) {
				Set<DurableAnchorKey> sourcePools = new java.util.TreeSet<>(canonicalWorkerPools(resolve(source, fType)));
				if(encodedPrimary && fType == FType.FULL)
					sourcePools.removeIf(pool -> pool.partitions().size() != 1);
				if(sourcePools.isEmpty())
					return Set.of();
				if(common == null)
					common = sourcePools;
				else {
					Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
					for(DurableAnchorKey current : common)
						if(sourcePools.stream().anyMatch(candidate -> sameWorkerPool(current, candidate)))
							compatible.add(current);
					common = compatible;
				}
				if(common.isEmpty())
					return Set.of();
			}
			return common == null ? Set.of() : common;
		}

		/**
		 * A function-output read can simultaneously carry ordinary CFG and formal-input
		 * reaching definitions. Every present source category must prove the same worker
		 * pool; resolving only the first non-empty category would mint relocation authority
		 * from an incomplete branch/function join.
		 */
		private Set<DurableAnchorKey> resolveFunctionOutputInputs(CompiledHopKey producer,
			FType fType) {
			Set<DurableAnchorKey> common = canonicalWorkerPools(
				resolveFunctionOutputAlias(producer, fType));
			if(common.isEmpty())
				return Set.of();
			Node node = nodesByKey.get(producer);
			boolean hasFunctionInput = hasFunctionInputSource(producer);
			if(hasFunctionInput) {
				common = intersectWorkerPools(common, resolveFunctionInput(producer, fType));
				if(common.isEmpty())
					return Set.of();
			}
			boolean hasCfgDefinition = node != null && node.valueVersion().predecessorVersions().stream()
				.anyMatch(value -> value.startsWith("cfg-definition:"));
			if(hasCfgDefinition)
				common = intersectWorkerPools(common, resolveCfgDefinitionInputs(producer, fType));
			return common;
		}

		private boolean hasMixedCfgFunctionSources(CompiledHopKey producer) {
			Node node = nodesByKey.get(producer);
			return node != null && hasFunctionInputSource(producer)
				&& node.valueVersion().predecessorVersions().stream()
					.anyMatch(value -> value.startsWith("cfg-definition:"));
		}

		private boolean hasFunctionInputSource(CompiledHopKey producer) {
			Node node = nodesByKey.get(producer);
			return functionInputsByRead.containsKey(producer) || declaredFunctionInputs.contains(producer)
				|| node != null && hasCfgFunctionInputPredecessor(node);
		}

		private Set<DurableAnchorKey> resolveMixedCfgFunctionInputs(CompiledHopKey producer,
			FType fType) {
			return intersectWorkerPools(resolveFunctionInput(producer, fType),
				resolveCfgDefinitionInputs(producer, fType));
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
			boolean unresolvedReference = false;
			for(String reference : references) {
				List<CompiledHopKey> sources = cfgDefinitionSourcesByReference.getOrDefault(reference, List.of());
				if(sources.isEmpty())
					return Set.of();
				Set<DurableAnchorKey> referencePools = new java.util.TreeSet<>();
				for(CompiledHopKey source : sources)
					referencePools.addAll(resolve(source, fType));
				referencePools = canonicalWorkerPools(referencePools);
				if(referencePools.isEmpty()) {
					unresolvedReference = true;
					continue;
				}
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
			if(unresolvedReference) {
				Set<DurableAnchorKey> seeds = common == null ? directSeedAnchors(fType) : common;
				Set<DurableAnchorKey> proven = new java.util.TreeSet<>();
				for(DurableAnchorKey seed : seeds)
					if(nativeContinuity.proves(List.of(producer), seed))
						proven.add(seed);
				common = canonicalWorkerPools(proven);
			}
			return common == null ? Set.of() : common;
		}

		private Set<DurableAnchorKey> directSeedAnchors(FType fType) {
			Set<DurableAnchorKey> seeds = new java.util.TreeSet<>();
			for(Node node : nodesByKey.values())
				for(DurableAnchorKey anchor : node.anchors())
					if(anchor.fType() == fType)
						seeds.add(anchor);
			return canonicalWorkerPools(seeds);
		}

		private static Set<DurableAnchorKey> canonicalWorkerPools(
			java.util.Collection<DurableAnchorKey> anchors) {
			Set<DurableAnchorKey> result = new java.util.TreeSet<>();
			for(DurableAnchorKey anchor : new java.util.TreeSet<>(anchors))
				if(result.stream().noneMatch(existing -> sameWorkerPool(existing, anchor)))
					result.add(anchor);
			return result;
		}

		private static Set<DurableAnchorKey> intersectWorkerPools(
			java.util.Collection<DurableAnchorKey> left,
			java.util.Collection<DurableAnchorKey> right) {
			Set<DurableAnchorKey> leftPools = canonicalWorkerPools(left);
			Set<DurableAnchorKey> rightPools = canonicalWorkerPools(right);
			if(leftPools.isEmpty() || rightPools.isEmpty())
				return Set.of();
			Set<DurableAnchorKey> compatible = new java.util.TreeSet<>();
			for(DurableAnchorKey leftPool : leftPools)
				if(rightPools.stream().anyMatch(rightPool -> sameWorkerPool(leftPool, rightPool)))
					compatible.add(leftPool);
			return compatible;
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
				if(predecessor.legalAlternatives().isEmpty()) {
					domains.add(List.of());
					continue;
				}
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

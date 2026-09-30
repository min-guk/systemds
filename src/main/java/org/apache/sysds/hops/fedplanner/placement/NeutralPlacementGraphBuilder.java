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
import java.util.List;
import java.util.Objects;


import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Public assembly and analysis lifetime for program facts, alternatives and feasible relations. */
public final class NeutralPlacementGraphBuilder {
	private final FunctionCallGraph suppliedFunctionCallGraph;
	private final FunctionCallSizeInfo suppliedFunctionCallSizes;
	private final FixedPointObserver fixedPointObserver;
	private final SearchSpaceMetrics complexityMetrics;

	/** Optional audit payload; it never changes candidate privacy filtering. */
	public enum PrivacyEvidenceMode {
		NONE,
		CAPTURE
	}

	interface FixedPointObserver {
		void accept(FixedPointPass pass);
	}

	record FixedPointPass(String phase, int pass, int passLimit, boolean stable,
		int nodeCount, int candidateCount, int logicalInputCount, int actionCount) { }

	public NeutralPlacementGraphBuilder() {
		this(null, null, null, null, true, PrivacyEvidenceMode.NONE);
	}

	public NeutralPlacementGraphBuilder(PrivacyEvidenceMode privacyEvidenceMode) {
		this(null, null, null, null, true, privacyEvidenceMode);
	}

	public NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		this(fgraph, fcallSizes, null, null, true, PrivacyEvidenceMode.NONE);
	}

	/** Opt-in diagnostics for the production final-Hop authority binding path. */
	public NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes,
		SearchSpaceMetrics complexityMetrics) {
		this(fgraph, fcallSizes, null, complexityMetrics, true, PrivacyEvidenceMode.NONE);
	}

	public NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes,
		SearchSpaceMetrics complexityMetrics, PrivacyEvidenceMode privacyEvidenceMode) {
		this(fgraph, fcallSizes, null, complexityMetrics, true, privacyEvidenceMode);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver) {
		this(null, null, fixedPointObserver, null, true, PrivacyEvidenceMode.NONE);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver,
		SearchSpaceMetrics complexityMetrics) {
		this(null, null, fixedPointObserver, complexityMetrics, true, PrivacyEvidenceMode.NONE);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver fixedPointObserver,
		SearchSpaceMetrics complexityMetrics, boolean incrementalDirectClosure) {
		this(null, null, fixedPointObserver, complexityMetrics, incrementalDirectClosure,
			PrivacyEvidenceMode.NONE);
	}

	NeutralPlacementGraphBuilder(FixedPointObserver observer, SearchSpaceMetrics metrics,
		boolean incrementalDirectClosure, boolean earlyPrivacyPruning) {
		this(null, null, observer, metrics, incrementalDirectClosure, PrivacyEvidenceMode.NONE,
			earlyPrivacyPruning);
	}

	private NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes,
		FixedPointObserver fixedPointObserver, SearchSpaceMetrics complexityMetrics,
		boolean incrementalDirectClosure, PrivacyEvidenceMode privacyEvidenceMode) {
		this(fgraph, fcallSizes, fixedPointObserver, complexityMetrics, incrementalDirectClosure,
			privacyEvidenceMode, true);
	}

	private NeutralPlacementGraphBuilder(FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes,
		FixedPointObserver fixedPointObserver, SearchSpaceMetrics complexityMetrics,
		boolean incrementalDirectClosure, PrivacyEvidenceMode privacyEvidenceMode, boolean earlyPrivacyPruning) {
		if((fgraph == null) != (fcallSizes == null))
			throw new IllegalArgumentException("Function graph and call-size summary must be supplied together");
		suppliedFunctionCallGraph = fgraph;
		suppliedFunctionCallSizes = fcallSizes;
		this.fixedPointObserver = fixedPointObserver;
		this.complexityMetrics = complexityMetrics;
		candidateGenerator = new PlacementCandidateGenerator(
			new OracleFacade(RulesCore.RulesModule.createDefaultRegistry()), complexityMetrics);
		relationClosure = new PlacementRelationClosure(candidateGenerator, fixedPointObserver,
			complexityMetrics, incrementalDirectClosure,
			Objects.requireNonNull(privacyEvidenceMode, "privacyEvidenceMode"), earlyPrivacyPruning);
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

	private final PlacementCandidateGenerator candidateGenerator;
	private final PlacementRelationClosure relationClosure;

	public PlacementAnalysis buildDetachedAnalysis(DMLProgram program) {
		relationClosure.resetBuildState();
		if(complexityMetrics != null)
			complexityMetrics.reset();
		SearchSpaceMetrics.PhaseToken analysisStarted = complexityMetrics == null ? null
			: complexityMetrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.beginAnalysisScope(complexityMetrics);
		PlacementAnalysis.CanonicalTextScope canonicalTextScope = PlacementAnalysis.beginCanonicalTextScope();
		try {
			FunctionCallGraph fgraph = suppliedFunctionCallGraph != null
				? suppliedFunctionCallGraph : new FunctionCallGraph(program);
			FunctionCallSizeInfo fcallSizes = suppliedFunctionCallSizes != null
				? suppliedFunctionCallSizes : new FunctionCallSizeInfo(fgraph);
			PlacementProgramFacts facts = PlacementProgramFacts.analyze(program, fgraph, fcallSizes,
				fixedPointObserver, complexityMetrics);
			return relationClosure.close(program, facts, fcallSizes);
		}
		finally {
			try {
				relationClosure.clearBuildState();
				PlacementIdentity.endAnalysisScope();
			}
			finally {
				try {
					canonicalTextScope.close();
				}
				finally {
					if(complexityMetrics != null)
						complexityMetrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, analysisStarted);
				}
			}
		}
	}

}

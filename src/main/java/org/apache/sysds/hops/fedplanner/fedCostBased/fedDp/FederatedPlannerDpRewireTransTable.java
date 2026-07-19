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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.HopsException;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataIdentifier;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.runtime.DMLRuntimeException;

public class FederatedPlannerDpRewireTransTable {
	private static final String FUNCTION_HIDDEN_ROOTS_KEY = "\u0000fedplanner_hidden_roots";
	private static final int MAX_UNROLL_DEPTH = 1;

	public record RewireRequest(PlacementAnalysis analysis, DMLProgram program,
		List<HopOccurrenceProjection> occurrences, List<CloneReceipt> cloneAssociations,
		List<HopOccurrenceProjection> additionalRoots) { }

	public record CloneReceipt(HopOccurrenceProjection originOccurrence,
		HopOccurrenceProjection cloneOccurrence, Node originNode, Node cloneNode,
		Constraint sameOrigin, Exclusion recompileCpFoutExclusion) { }

	public record RewireConsumerEdge(CompiledHopKey parentOccurrence,
		CompiledHopKey childOccurrence, int inputPosition) {
		public RewireConsumerEdge {
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			Objects.requireNonNull(childOccurrence, "childOccurrence");
			if(inputPosition < 0)
				throw new IllegalArgumentException("Rewire input position must be non-negative");
		}
	}

	public record RewireOccurrenceSnapshot(PlacementAnalysis analysis, DMLProgram program,
		String analysisFingerprint, List<HopOccurrenceProjection> occurrences,
		List<CloneReceipt> cloneReceipts, List<HopOccurrenceProjection> additionalRoots,
		List<RewireConsumerEdge> consumerEdges, Map<Long, Long> cloneToOriginal,
		String enumerationScopeKey) {
		public RewireOccurrenceSnapshot {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			Objects.requireNonNull(occurrences, "occurrences");
			Objects.requireNonNull(cloneReceipts, "cloneReceipts");
			Objects.requireNonNull(additionalRoots, "additionalRoots");
			Objects.requireNonNull(consumerEdges, "consumerEdges");
			Objects.requireNonNull(cloneToOriginal, "cloneToOriginal");
			if(enumerationScopeKey == null || enumerationScopeKey.isBlank())
				throw new IllegalArgumentException("Enumeration scope key must not be blank");
			analysis.assertCanonicalProgramAuthority(program);
			if(!analysis.analysisFingerprint().equals(analysisFingerprint))
				throw new IllegalArgumentException("Rewire analysis fingerprint differs");

			List<HopOccurrenceProjection> ownedOccurrences = analysis.occurrences();
			if(occurrences.size() != ownedOccurrences.size())
				throw new IllegalArgumentException("Rewire occurrence multiplicity differs");
			Set<HopOccurrenceProjection> ownedByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<CompiledHopKey> ownedKeysByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
			for(int i = 0; i < occurrences.size(); i++) {
				HopOccurrenceProjection occurrence = occurrences.get(i);
				if(occurrence != ownedOccurrences.get(i) || occurrence.normalizedOrdinal() != i
					|| analysis.hop(occurrence.key()).orElse(null) != occurrence.hop())
					throw new IllegalArgumentException("Rewire occurrence ownership or order differs");
				ownedByIdentity.add(occurrence);
				ownedKeysByIdentity.add(occurrence.key());
			}
			for(HopOccurrenceProjection root : additionalRoots)
				if(!ownedByIdentity.contains(root))
					throw new IllegalArgumentException("Additional root is not analysis-owned");
			for(RewireConsumerEdge edge : consumerEdges)
				if(!ownedKeysByIdentity.contains(edge.parentOccurrence())
					|| !ownedKeysByIdentity.contains(edge.childOccurrence()))
					throw new IllegalArgumentException("Consumer edge endpoint is not analysis-owned");

			Map<Long, Long> exactCloneMap = new LinkedHashMap<>();
			for(CloneReceipt receipt : cloneReceipts) {
				if(receipt == null || !ownedByIdentity.contains(receipt.originOccurrence())
					|| !ownedByIdentity.contains(receipt.cloneOccurrence()))
					throw new IllegalArgumentException("Clone receipt occurrence is not analysis-owned");
				Long previous = exactCloneMap.put(receipt.cloneOccurrence().hop().getHopID(),
					receipt.originOccurrence().hop().getHopID());
				if(previous != null)
					throw new IllegalArgumentException("Duplicate clone receipt");
			}
			if(!exactCloneMap.equals(cloneToOriginal))
				throw new IllegalArgumentException("Clone receipt mapping differs");

			occurrences = List.copyOf(occurrences);
			cloneReceipts = List.copyOf(cloneReceipts);
			additionalRoots = List.copyOf(additionalRoots);
			consumerEdges = List.copyOf(consumerEdges);
			cloneToOriginal = Collections.unmodifiableMap(new LinkedHashMap<>(cloneToOriginal));
		}
	}

	public record RewireReceipt(PlacementAnalysis analysis, DMLProgram program,
		String analysisFingerprint, List<HopOccurrenceProjection> occurrences,
		List<CloneReceipt> cloneReceipts, List<HopOccurrenceProjection> orderedAdditionalRoots,
		Map<Long, Long> cloneToOrig, List<String> orderedNormalizedIdentities) {
		public RewireReceipt {
			occurrences = List.copyOf(occurrences);
			cloneReceipts = List.copyOf(cloneReceipts);
			orderedAdditionalRoots = List.copyOf(orderedAdditionalRoots);
			cloneToOrig = Collections.unmodifiableMap(new LinkedHashMap<>(cloneToOrig));
			orderedNormalizedIdentities = List.copyOf(orderedNormalizedIdentities);
		}
	}

	public static RewireReceipt inspectExact(RewireRequest request) {
		if (request == null || request.analysis() == null || request.program() == null
			|| request.occurrences() == null || request.cloneAssociations() == null
			|| request.additionalRoots() == null)
			throw new IllegalArgumentException("Rewire request fields must not be null");

		PlacementAnalysis analysis = request.analysis();
		analysis.assertCanonicalProgramAuthority(request.program());
		List<HopOccurrenceProjection> ownedOccurrences = analysis.occurrences();
		if (request.occurrences() != ownedOccurrences)
			throw new IllegalArgumentException("Rewire occurrences are not the analysis-owned carrier");
		for (int i = 0; i < ownedOccurrences.size(); i++) {
			HopOccurrenceProjection occurrence = request.occurrences().get(i);
			if (occurrence == null || occurrence != ownedOccurrences.get(i)
				|| occurrence.normalizedOrdinal() != i
				|| analysis.hop(occurrence.key()).orElse(null) != occurrence.hop()
				|| analysis.graph().node(occurrence.key()).isEmpty())
				throw new IllegalArgumentException("Rewire occurrence ownership or order differs");
		}
		if (!request.additionalRoots().isEmpty())
			throw new IllegalArgumentException("Neutral rewire inspection accepts no additional-root claims");

		List<Node> ownedClones = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.CLONE).toList();
		if (request.cloneAssociations().size() != ownedClones.size())
			throw new IllegalArgumentException("Rewire clone association multiplicity differs");

		List<CloneReceipt> cloneReceipts = new ArrayList<>(ownedClones.size());
		Map<Long, Long> cloneToOrig = new LinkedHashMap<>();
		for (int i = 0; i < ownedClones.size(); i++) {
			Node clone = ownedClones.get(i);
			CloneReceipt claim = request.cloneAssociations().get(i);
			if (claim == null || claim.originOccurrence() == null || claim.cloneOccurrence() == null
				|| claim.originNode() == null || claim.cloneNode() == null || claim.sameOrigin() == null
				|| claim.recompileCpFoutExclusion() == null)
				throw new IllegalArgumentException("Rewire clone association fields must not be null");
			if (claim.cloneNode() != clone
				|| analysis.graph().node(clone.key()).orElse(null) != clone
				|| exactOccurrence(analysis, clone) != claim.cloneOccurrence())
				throw new IllegalArgumentException("Rewire clone is not owned by the analysis");

			List<Constraint> associations = analysis.graph().constraints().stream()
				.filter(candidate -> candidate.kind() == ConstraintKind.SAME_ORIGIN)
				.filter(candidate -> candidate.right().equals(clone.key())).toList();
			if (associations.size() != 1 || associations.get(0) != claim.sameOrigin())
				throw new IllegalArgumentException("Rewire SAME_ORIGIN association identity or multiplicity differs");
			Constraint association = associations.get(0);
			Node origin = analysis.graph().node(association.left()).orElse(null);
			if (origin == null || origin.kind() == NodeKind.CLONE || claim.originNode() != origin
				|| exactOccurrence(analysis, origin) != claim.originOccurrence()
				|| !association.left().equals(origin.key()) || !association.right().equals(clone.key())
				|| !origin.key().canonicalSourceOrigin().equals(clone.key().canonicalSourceOrigin()))
				throw new IllegalArgumentException("Rewire clone/original association differs");

			List<Exclusion> exclusions = clone.exclusions().stream()
				.filter(candidate -> candidate.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).toList();
			if (exclusions.size() != 1 || exclusions.get(0) != claim.recompileCpFoutExclusion())
				throw new IllegalArgumentException("Rewire recompile exclusion identity or multiplicity differs");
			cloneReceipts.add(claim);
			if (cloneToOrig.put(claim.cloneOccurrence().hop().getHopID(),
				claim.originOccurrence().hop().getHopID()) != null)
				throw new IllegalArgumentException("Duplicate rewire clone association");
		}

		return new RewireReceipt(analysis, request.program(), analysis.analysisFingerprint(),
			ownedOccurrences, cloneReceipts, List.of(), cloneToOrig,
			analysis.graph().normalizedIdentities());
	}

	public static RewireOccurrenceSnapshot snapshotProductionRewire(PlacementAnalysis analysis,
		DMLProgram program, Map<Long, List<Hop>> rewireTable,
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		Map<Long, Set<Long>> parentChildUploadHints, Set<Hop> progRootHopSet,
		UnrollContext unrollContext, String enumerationScopeKey) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(rewireTable, "rewireTable");
		Objects.requireNonNull(hopCommonTable, "hopCommonTable");
		Objects.requireNonNull(parentChildUploadHints, "parentChildUploadHints");
		Objects.requireNonNull(progRootHopSet, "progRootHopSet");
		Objects.requireNonNull(unrollContext, "unrollContext");
		try {
			analysis.assertCanonicalProgramAuthority(program);
		}
		catch(IllegalArgumentException ex) {
			throw semanticFailure(analysis, failureAnchor(analysis), ConstructionDisposition.FOREIGN_CONTEXT,
				"REWIRE_PROGRAM_FOREIGN");
		}

		List<HopOccurrenceProjection> occurrences = analysis.occurrences();
		Map<Hop, HopOccurrenceProjection> exactByHop = new IdentityHashMap<>();
		Map<Long, HopOccurrenceProjection> exactByHopId = new LinkedHashMap<>();
		Map<HopOccurrenceProjection, Hop> resolvedRewiredHops = new IdentityHashMap<>();
		Map<Hop, HopOccurrenceProjection> occurrenceByResolvedHop = new IdentityHashMap<>();
		for(int i = 0; i < occurrences.size(); i++) {
			HopOccurrenceProjection occurrence = occurrences.get(i);
			if(occurrence.normalizedOrdinal() != i)
				throw semanticFailure(analysis, occurrence, ConstructionDisposition.REORDERED_EDGE,
					"REWIRE_OCCURRENCE_ORDER_DIFFERS");
			if(analysis.hop(occurrence.key()).orElse(null) != occurrence.hop())
				throw semanticFailure(analysis, occurrence, ConstructionDisposition.STALE_CONTEXT,
					"REWIRE_OCCURRENCE_DETACHED");
			if(exactByHop.put(occurrence.hop(), occurrence) != null
				|| exactByHopId.put(occurrence.hop().getHopID(), occurrence) != null)
				throw new IllegalArgumentException("Production rewire occurrence is duplicated or detached");
			Hop resolved = resolveExactRewiredHop(occurrence, rewireTable, hopCommonTable,
				unrollContext.getCloneToOrig());
			resolvedRewiredHops.put(occurrence, resolved);
			if(occurrenceByResolvedHop.put(resolved, occurrence) != null)
				throw new IllegalArgumentException("Concrete production rewire carrier maps to multiple occurrences");
		}

		List<RewireConsumerEdge> consumerEdges = new ArrayList<>();
		for(HopOccurrenceProjection parent : occurrences) {
			Hop resolvedParent = resolvedRewiredHops.get(parent);
			if(resolvedParent == null)
				throw new IllegalArgumentException("Production rewire parent has no exact resolved carrier");
			List<Hop> inputs = resolvedParent.getInput();
			for(int inputPosition = 0; inputPosition < inputs.size(); inputPosition++) {
				HopOccurrenceProjection child = occurrenceByResolvedHop.get(inputs.get(inputPosition));
				if(child == null)
					throw new IllegalArgumentException("Production rewire child has no exact resolved occurrence");
				consumerEdges.add(new RewireConsumerEdge(parent.key(), child.key(), inputPosition));
			}
		}

		for(Map.Entry<Long, Set<Long>> entry : parentChildUploadHints.entrySet()) {
			HopOccurrenceProjection parent = exactByHopId.get(entry.getKey());
			if(parent == null)
				throw semanticFailure(analysis, failureAnchor(analysis), ConstructionDisposition.FOREIGN_CONTEXT,
					"REWIRE_UPLOAD_HINT_PARENT_FOREIGN");
			if(entry.getValue() == null)
				throw semanticFailure(analysis, parent, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"REWIRE_UPLOAD_HINTS_MISSING");
			for(Long childId : entry.getValue())
				if(childId == null || !exactByHopId.containsKey(childId))
					throw semanticFailure(analysis, parent, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
						"REWIRE_UPLOAD_HINT_CHILD_UNMAPPABLE");
		}

		List<CloneReceipt> cloneReceipts = exactCloneReceipts(analysis);
		Map<Long, Long> cloneToOriginal = new LinkedHashMap<>();
		for(CloneReceipt receipt : cloneReceipts)
			if(cloneToOriginal.put(receipt.cloneOccurrence().hop().getHopID(),
				receipt.originOccurrence().hop().getHopID()) != null)
				throw semanticFailure(analysis, receipt.cloneOccurrence(),
					ConstructionDisposition.DUPLICATE_OCCURRENCE, "REWIRE_CLONE_MAPPING_DUPLICATED");
		if(!cloneToOriginal.equals(unrollContext.getCloneToOrig()))
			throw semanticFailure(analysis, cloneReceipts.isEmpty() ? failureAnchor(analysis)
				: cloneReceipts.get(0).cloneOccurrence(), ConstructionDisposition.STALE_CONTEXT,
				"REWIRE_CLONE_MAPPING_DIFFERS");

		List<HopOccurrenceProjection> additionalRoots = new ArrayList<>();
		Set<HopOccurrenceProjection> seenRoots = Collections.newSetFromMap(new IdentityHashMap<>());
		appendExactRoots(unrollContext.getIter1Roots(), occurrenceByResolvedHop, additionalRoots, seenRoots, analysis);
		appendExactRoots(unrollContext.getAdditionalRoots(), occurrenceByResolvedHop, additionalRoots, seenRoots,
			analysis);
		for(HopOccurrenceProjection occurrence : occurrences)
			if(progRootHopSet.contains(resolvedRewiredHops.get(occurrence)) && seenRoots.add(occurrence))
				additionalRoots.add(occurrence);

		if(enumerationScopeKey == null || enumerationScopeKey.isBlank())
			throw semanticFailure(analysis, failureAnchor(analysis), ConstructionDisposition.STALE_CONTEXT,
				"REWIRE_ENUMERATION_SCOPE_MISSING");
		try {
			return new RewireOccurrenceSnapshot(analysis, program, analysis.analysisFingerprint(), occurrences,
				cloneReceipts, additionalRoots, consumerEdges, cloneToOriginal, enumerationScopeKey);
		}
		catch(IllegalArgumentException ex) {
			throw semanticFailureWithCause(analysis, failureAnchor(analysis), ConstructionDisposition.STALE_CONTEXT,
				"REWIRE_SNAPSHOT_INVARIANT_DIFFERS", ex);
		}
	}

	private static Hop resolveExactRewiredHop(HopOccurrenceProjection occurrence,
		Map<Long, List<Hop>> rewireTable,
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		Map<Long, Long> cloneToOriginal) {
		Set<Hop> carrierHops = Collections.newSetFromMap(new IdentityHashMap<>());
		for(FederatedPlannerDpMemoTable.HopCommon common : hopCommonTable.values())
			if(common != null && common.getHopRef() != null)
				carrierHops.add(common.getHopRef());
		for(List<Hop> rewired : rewireTable.values())
			if(rewired != null)
				for(Hop candidate : rewired)
					if(candidate != null)
						carrierHops.add(candidate);

		for(Hop candidate : carrierHops)
			if(candidate == occurrence.hop())
				return candidate;

		Set<Hop> cloneMatches = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop candidate : carrierHops) {
			Long originalHopId = cloneToOriginal.get(candidate.getHopID());
			if(originalHopId != null && originalHopId == occurrence.hop().getHopID())
				cloneMatches.add(candidate);
		}
		if(cloneMatches.size() != 1)
			throw semanticFailure(analysis, occurrence, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
				"REWIRE_CONCRETE_CARRIER_MULTIPLICITY_" + cloneMatches.size());
		return cloneMatches.iterator().next();
	}

	private static DpSemanticConstructionException semanticFailure(PlacementAnalysis analysis,
		HopOccurrenceProjection parent, ConstructionDisposition disposition, String reasonCode) {
		return new DpSemanticConstructionException(disposition, analysis.analysisFingerprint(), parent.key(), reasonCode);
	}

	private static DpSemanticConstructionException semanticFailureWithCause(PlacementAnalysis analysis,
		HopOccurrenceProjection parent, ConstructionDisposition disposition, String reasonCode,
		IllegalArgumentException cause) {
		DpSemanticConstructionException failure = semanticFailure(analysis, parent, disposition, reasonCode);
		failure.initCause(cause);
		return failure;
	}

	private static HopOccurrenceProjection failureAnchor(PlacementAnalysis analysis) {
		if(analysis.occurrences().isEmpty())
			throw new IllegalStateException("Placement analysis has no occurrence for semantic failure evidence");
		return analysis.occurrences().get(0);
	}

	private static void appendExactRoots(List<Hop> roots,
		Map<Hop, HopOccurrenceProjection> occurrenceByResolvedHop, List<HopOccurrenceProjection> target,
		Set<HopOccurrenceProjection> seen, PlacementAnalysis analysis) {
		for(Hop root : roots) {
			if(root == null)
				continue;
			HopOccurrenceProjection occurrence = occurrenceByResolvedHop.get(root);
			if(occurrence == null)
				throw semanticFailure(analysis, failureAnchor(analysis),
					ConstructionDisposition.UNMAPPABLE_OCCURRENCE, "REWIRE_ADDITIONAL_ROOT_UNMAPPABLE");
			if(seen.add(occurrence))
				target.add(occurrence);
		}
	}

	private static List<CloneReceipt> exactCloneReceipts(PlacementAnalysis analysis) {
		List<Node> clones = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.CLONE).toList();
		List<CloneReceipt> receipts = new ArrayList<>(clones.size());
		for(Node clone : clones) {
			HopOccurrenceProjection cloneOccurrence;
			try {
				cloneOccurrence = exactOccurrence(analysis, clone);
			}
			catch(IllegalArgumentException ex) {
				throw semanticFailure(analysis, failureAnchor(analysis),
					ConstructionDisposition.UNMAPPABLE_OCCURRENCE, "REWIRE_CLONE_OCCURRENCE_UNMAPPABLE");
			}
			List<Constraint> associations = analysis.graph().constraints().stream()
				.filter(candidate -> candidate.kind() == ConstraintKind.SAME_ORIGIN)
				.filter(candidate -> candidate.right().equals(clone.key())).toList();
			if(associations.size() != 1)
				throw semanticFailure(analysis, cloneOccurrence, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"REWIRE_CLONE_SAME_ORIGIN_MULTIPLICITY_" + associations.size());
			Constraint association = associations.get(0);
			Node origin = analysis.graph().node(association.left()).orElse(null);
			List<Exclusion> exclusions = clone.exclusions().stream()
				.filter(candidate -> candidate.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).toList();
			if(origin == null || origin.kind() == NodeKind.CLONE || exclusions.size() != 1
				|| !origin.key().canonicalSourceOrigin().equals(clone.key().canonicalSourceOrigin()))
				throw semanticFailure(analysis, cloneOccurrence, ConstructionDisposition.STALE_CONTEXT,
					"REWIRE_CLONE_OWNERSHIP_OR_EXCLUSION_DIFFERS");
			HopOccurrenceProjection originOccurrence;
			try {
				originOccurrence = exactOccurrence(analysis, origin);
			}
			catch(IllegalArgumentException ex) {
				throw semanticFailure(analysis, cloneOccurrence, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"REWIRE_ORIGIN_OCCURRENCE_UNMAPPABLE");
			}
			receipts.add(new CloneReceipt(originOccurrence, cloneOccurrence, origin, clone, association,
				exclusions.get(0)));
		}
		return List.copyOf(receipts);
	}

	private static HopOccurrenceProjection exactOccurrence(PlacementAnalysis analysis, Node node) {
		HopOccurrenceProjection match = null;
		for (HopOccurrenceProjection occurrence : analysis.occurrences()) {
			if (!occurrence.key().equals(node.key()))
				continue;
			if (match != null || analysis.hop(node.key()).orElse(null) != occurrence.hop())
				throw new IllegalArgumentException("Rewire node occurrence multiplicity differs");
			match = occurrence;
		}
		if (match == null)
			throw new IllegalArgumentException("Rewire node has no analysis-owned occurrence");
		return match;
	}

	public static class UnrollContext {
		private final Map<Long, Long> cloneToOrig = new HashMap<>();
		private final List<Hop> iter1Roots = new ArrayList<>();
		private final List<Hop> additionalRoots = new ArrayList<>();
		private final LinkedHashSet<Long> deadFunctionOutputHopIDs = new LinkedHashSet<>();
		private final Deque<Set<String>> statementLiveOutStack = new ArrayDeque<>();

		public Map<Long, Long> getCloneToOrig() {
			return cloneToOrig;
		}

		public List<Hop> getIter1Roots() {
			return iter1Roots;
		}

		public List<Hop> getAdditionalRoots() {
			return additionalRoots;
		}

		public Set<Long> getDeadFunctionOutputHopIDs() {
			return Collections.unmodifiableSet(deadFunctionOutputHopIDs);
		}

		public boolean isDeadFunctionOutputHop(long hopID) {
			return hopID >= 0 && deadFunctionOutputHopIDs.contains(hopID);
		}

		public Set<String> getCurrentStatementLiveOuts() {
			Set<String> current = statementLiveOutStack.peek();
			return current != null ? current : Collections.emptySet();
		}

		private void addIter1Roots(List<Hop> roots) {
			if (roots == null || roots.isEmpty())
				return;
			iter1Roots.addAll(roots);
		}

		private void addAdditionalRoots(List<Hop> roots) {
			if (roots == null || roots.isEmpty())
				return;
			additionalRoots.addAll(roots);
		}

		private void addDeadFunctionOutputHop(Hop hop) {
			if (hop != null)
				deadFunctionOutputHopIDs.add(hop.getHopID());
		}

		private void pushStatementLiveOuts(Set<String> liveOuts) {
			statementLiveOutStack.push((liveOuts == null || liveOuts.isEmpty())
				? Collections.emptySet()
				: new LinkedHashSet<>(liveOuts));
		}

		private void popStatementLiveOuts() {
			if (!statementLiveOutStack.isEmpty())
				statementLiveOutStack.pop();
		}
	}

	private static class LoopAnalysisContext {
		private final Map<String, Boolean> readFromOutside = new HashMap<>();
		private final Map<String, List<Hop>> headerReads = new HashMap<>();
		private final Set<String> writtenVars = new HashSet<>();
		private final boolean trackReadFromOutside;
		private final boolean trackHeaderReads;
		private final boolean includeTransReadChildren;

		private LoopAnalysisContext(boolean trackReadFromOutside, boolean trackHeaderReads,
				boolean includeTransReadChildren) {
			this.trackReadFromOutside = trackReadFromOutside;
			this.trackHeaderReads = trackHeaderReads;
			this.includeTransReadChildren = includeTransReadChildren;
		}

		private void markReadFromOutside(String var) {
			if (!trackReadFromOutside || var == null)
				return;
			readFromOutside.put(var, true);
		}

		private void markWritten(String var) {
			if (var == null)
				return;
			writtenVars.add(var);
		}

		private boolean hasWritten(String var) {
			return var != null && writtenVars.contains(var);
		}

		private Set<String> snapshotWritten() {
			return new HashSet<>(writtenVars);
		}

		private void restoreWritten(Set<String> snapshot) {
			writtenVars.clear();
			if (snapshot != null && !snapshot.isEmpty())
				writtenVars.addAll(snapshot);
		}

		private void retainWritten(Set<String> other) {
			if (other == null)
				writtenVars.clear();
			else
				writtenVars.retainAll(other);
		}

		private void recordHeaderRead(String var, Hop treadHop) {
			if (!trackHeaderReads || var == null || treadHop == null)
				return;
			headerReads.computeIfAbsent(var, k -> new ArrayList<>()).add(treadHop);
		}

		private Map<String, Boolean> getReadFromOutside() {
			return readFromOutside;
		}

		private Map<String, List<Hop>> getHeaderReads() {
			return headerReads;
		}

		private boolean includeTransReadChildren() {
			return includeTransReadChildren;
		}
	}

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx) {
		rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet,
				progRootHopSet, null, unrollCtx, MAX_UNROLL_DEPTH);
	}

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Map<Long, Set<Long>> parentChildUploadHints, UnrollContext unrollCtx) {
		rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet,
				progRootHopSet, parentChildUploadHints, unrollCtx, MAX_UNROLL_DEPTH);
	}

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx, int maxUnrollDepth) {
		rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet,
				progRootHopSet, null, unrollCtx, maxUnrollDepth);
	}

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Map<Long, Set<Long>> parentChildUploadHints, UnrollContext unrollCtx,
			int maxUnrollDepth) {
		// Maps Hop ID and fedOutType pairs to their plan variants
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();

		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);

		for (StatementBlock sb : prog.getStatementBlocks()) {
			Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
					hopCommonTable, outerTransTableList, null, privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds,
					functionTransTableCache, 1, 1, 1, loopStack, 0, maxUnrollDepth, null, null, unrollCtx);
			outerTransTableList.get(0).putAll(innerTransTable);
		}
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx) {
		rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet, null, unrollCtx, MAX_UNROLL_DEPTH);
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Map<Long, Set<Long>> parentChildUploadHints, UnrollContext unrollCtx) {
		rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx, MAX_UNROLL_DEPTH);
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx, int maxUnrollDepth) {
		rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet, null, unrollCtx, maxUnrollDepth);
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Map<Long, Set<Long>> parentChildUploadHints, UnrollContext unrollCtx,
			int maxUnrollDepth) {
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();
		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);
		// Todo (Future): not tested & not used
		rewireStatementBlock(function, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList, null,
				privacyConstraintMap,
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds,
				functionTransTableCache, 1, 1, 1, loopStack, 0, maxUnrollDepth, null, null, unrollCtx);
	}

	public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
			Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable, Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
			Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Set<String> fnStack, Set<Long> injectedIds,
			Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, double multiplicity, List<Pair<Long, Double>> parentLoopStack,
			int unrollDepth, int maxUnrollDepth, Map<Long, Hop> hopCloneMap, LoopAnalysisContext loopCtx,
			UnrollContext unrollCtx) {
		List<Map<String, List<Hop>>> newOuterTransTableList = new ArrayList<>();
		if (outerTransTableList != null) {
			for (Map<String, List<Hop>> outerTable : outerTransTableList) {
				if (outerTable != null && !outerTable.isEmpty()) {
					newOuterTransTableList.add(outerTable);
				}
			}
		}
		if (formerTransTable != null && !formerTransTable.isEmpty()) {
			newOuterTransTableList.add(formerTransTable);
		}

		Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
		Map<String, List<Hop>> innerTransTable = new HashMap<>();
		if (unrollCtx != null)
			unrollCtx.pushStatementLiveOuts(extractStatementLiveOutNames(sb));

		try {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			Set<String> writtenBeforeIf = null;
			if (loopCtx != null) {
				writtenBeforeIf = loopCtx.snapshotWritten();
			}

			for (Hop controlRoot : collectControlExecutionRoots(selectHop(isb.getPredicateHops(), hopCloneMap)))
				rewireHopDAG(controlRoot, prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds,
						functionTransTableCache, computeWeight, networkWeight, multiplicity, parentLoopStack,
						unrollDepth, maxUnrollDepth, loopCtx, unrollCtx);

				newFormerTransTable.putAll(innerTransTable);
				Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
				elseFormerTransTable.putAll(innerTransTable);
				computeWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;
				networkWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;
				// If/else bodies are mutually exclusive. Keeping networkWeight unscaled here causes
				// transient-write/read forwarding costs for branch-local variables (e.g., steplm's X_global)
				// to accumulate as if both branches executed together, which over-penalizes FED/FOUT
				// alternatives and diverges from MinST parity.

				for (StatementBlock innerIsb : istmt.getIfBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap, loopCtx,
						unrollCtx));

			Set<String> writtenAfterIf = null;
			if (loopCtx != null) {
				writtenAfterIf = loopCtx.snapshotWritten();
				loopCtx.restoreWritten(writtenBeforeIf);
			}

			for (StatementBlock innerIsb : istmt.getElseBody())
				elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, elseFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap, loopCtx,
						unrollCtx));

			if (loopCtx != null) {
				Set<String> writtenAfterElse = loopCtx.snapshotWritten();
				loopCtx.restoreWritten(writtenBeforeIf);
				if (writtenAfterIf == null)
					writtenAfterIf = writtenBeforeIf;
				loopCtx.restoreWritten(writtenAfterIf);
				loopCtx.retainWritten(writtenAfterElse);
			}

			// If there are common keys: merge elseValue list into ifValue list
			elseFormerTransTable.forEach((key, elseValue) -> {
				newFormerTransTable.merge(key, elseValue, (ifValue, newValue) -> {
					ifValue.addAll(newValue);
					return ifValue;
				});
			});
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			// Calculate for-loop iteration count if possible
			double loopWeight = RewireConstants.DEFAULT_LOOP_WEIGHT;
			Hop from = fsb.getFromHops().getInput().get(0);
			Hop to = fsb.getToHops().getInput().get(0);
				Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0)
						: new LiteralOp(1);

				// Calculate for-loop iteration count (weight) if possible.
				//
				// Builtin workloads often pass constant arguments to functions (e.g., kmeans(k=50)),
				// but loop bounds inside the builtin body are expressed via transient variables
				// (e.g., num_centroids). If we only accept LiteralOp bounds, we systematically
				// fall back to DEFAULT_LOOP_WEIGHT and under-estimate repeated forwarding costs.
				//
				// Best-effort: resolve scalar loop bounds through trans tables and simple scalar
				// expressions. This is common to DP/MinST and improves planning accuracy without
				// closing candidates ad-hoc.
				Double dfromConst = RewireConstants.tryEvaluateScalarConstant(from, newOuterTransTableList);
				Double dtoConst = RewireConstants.tryEvaluateScalarConstant(to, newOuterTransTableList);
				Double dincrConst = RewireConstants.tryEvaluateScalarConstant(incr, newOuterTransTableList);
				if (dfromConst != null && dtoConst != null && dincrConst != null && dincrConst != 0.0) {
					double dfrom = dfromConst.doubleValue();
					double dto = dtoConst.doubleValue();
					double dincr = dincrConst.doubleValue();
					if (dfrom > dto && dincr == 1)
						dincr = -1;
					double est = UtilFunctions.getSeqLength(dfrom, dto, dincr, false);
					if (est > 0.0)
						loopWeight = est;
				}
				double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
			boolean allowUnroll = unrollCtx != null && loopCtx == null
					&& unrollDepth < maxUnrollDepth && iter1Factor > 0.0;
			Set<String> loopCarriedVars = Collections.emptySet();

			if (allowUnroll) {
				LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false, false);
				Set<Long> probeVisited = new HashSet<>();
				Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> probeHopCommon = new HashMap<>();
				Map<Long, Privacy> probePrivacy = new HashMap<>();
				Set<Long> probeUnRefTwriteSet = new HashSet<>();
				Set<Long> probeUnRefSet = new HashSet<>();
				Set<Hop> probeRootSet = new HashSet<>();
				Set<Long> probeInjectedIds = new HashSet<>();
				Map<String, Map<String, List<Hop>>> probeFnCache = new HashMap<>();
				Set<String> probeFnStack = new HashSet<>(fnStack);
				List<Pair<FederatedRange, FederatedData>> probeFedMap = new ArrayList<>(fedMap);

				Map<String, List<Hop>> probeInner = new HashMap<>();
				rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, probeVisited, probeRewireTable,
						probeHopCommon, newOuterTransTableList, null, probeInner,
						probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
						probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
						parentLoopStack, unrollDepth, maxUnrollDepth, probeCtx, null);
				rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, probeVisited, probeRewireTable,
						probeHopCommon, newOuterTransTableList, null, probeInner,
						probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
						probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
						parentLoopStack, unrollDepth, maxUnrollDepth, probeCtx, null);
				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, probeVisited,
							probeRewireTable, probeHopCommon, newOuterTransTableList, null, probeInner,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
							parentLoopStack, unrollDepth, maxUnrollDepth, probeCtx, null);
				}
				Map<String, List<Hop>> probeFormer = new HashMap<>();
				probeFormer.putAll(probeInner);
				for (StatementBlock innerFsb : fstmt.getBody())
					probeFormer.putAll(rewireStatementBlock(innerFsb, prog, probeVisited, probeRewireTable,
							probeHopCommon, newOuterTransTableList, probeFormer,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight,
							networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap,
							probeCtx, null));
				loopCarriedVars = computeLoopCarriedVars(probeCtx, probeFormer);
				if (loopCarriedVars.isEmpty())
					allowUnroll = false;
			}

			if (!allowUnroll) {
				computeWeight *= loopWeight;
				networkWeight *= loopWeight;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						currentLoopStack, unrollDepth, maxUnrollDepth, loopCtx, unrollCtx);
				rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList,
						null,
						innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						currentLoopStack, unrollDepth, maxUnrollDepth, loopCtx, unrollCtx);

				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, visitedHops, rewireTable,
							hopCommonTable,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, maxUnrollDepth, loopCtx, unrollCtx);
				}
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap, loopCtx,
							unrollCtx));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
						injectedIds);
				return newFormerTransTable;
			}

			List<Pair<Long, Double>> iter0LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter0LoopStack.addAll(parentLoopStack);
			iter0LoopStack.add(Pair.of(sb.getSBID(), 1.0));

			LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false, false);
			rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null, innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
					iter0LoopStack, unrollDepth, maxUnrollDepth, iter0Analysis, unrollCtx);
			rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null,
					innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
					iter0LoopStack, unrollDepth, maxUnrollDepth, iter0Analysis, unrollCtx);
			if (fsb.getIncrementHops() != null) {
				rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList, null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth, maxUnrollDepth, iter0Analysis, unrollCtx);
			}
			newFormerTransTable.putAll(innerTransTable);
			for (StatementBlock innerFsb : fstmt.getBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth + 1, maxUnrollDepth, hopCloneMap,
						iter0Analysis, unrollCtx));

			Map<String, List<Hop>> iter0End = newFormerTransTable;
			loopCarriedVars = computeLoopCarriedVars(iter0Analysis, iter0End);
			double iter1Multiplicity = multiplicity * iter1Factor;
			if (loopCarriedVars.isEmpty() || iter1Multiplicity <= 0.0) {
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, iter0End, injectedIds);
				return iter0End;
			}

			List<Pair<Long, Double>> iter1LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter1LoopStack.addAll(parentLoopStack);
			iter1LoopStack.add(Pair.of(sb.getSBID(), iter1Factor));

			Map<Long, Hop> iter1HopMap = cloneStatementBlockHops(fsb, hopCloneMap, unrollCtx);
			unrollCtx.addIter1Roots(collectStatementBlockRoots(fsb, iter1HopMap));

			LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true, false);
			Map<String, List<Hop>> iter1Inner = new HashMap<>();
			Map<String, List<Hop>> iter1Former = new HashMap<>();

			rewireHopDAG(selectHop(fsb.getFromHops(), iter1HopMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null, iter1Inner,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
					iter1LoopStack, unrollDepth, maxUnrollDepth, iter1Analysis, unrollCtx);
			rewireHopDAG(selectHop(fsb.getToHops(), iter1HopMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null,
					iter1Inner,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
					iter1LoopStack, unrollDepth, maxUnrollDepth, iter1Analysis, unrollCtx);
			if (fsb.getIncrementHops() != null) {
				rewireHopDAG(selectHop(fsb.getIncrementHops(), iter1HopMap), prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList, null, iter1Inner,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth, maxUnrollDepth, iter1Analysis, unrollCtx);
			}
			iter1Former.putAll(iter1Inner);
			for (StatementBlock innerFsb : fstmt.getBody())
				iter1Former.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, iter1Former,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth + 1, maxUnrollDepth, iter1HopMap,
						iter1Analysis, unrollCtx));

			addCrossIterEdges(loopCarriedVars, iter0End, iter1Analysis, rewireTable, unRefTwriteSet);
			mergeIter0EndIntoIter1Former(iter1Former, iter0End);
			wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, iter1Former, injectedIds);
			return iter1Former;
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			double loopWeight = RewireConstants.estimateWhileLoopWeight(wsb, newOuterTransTableList);
			double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
			boolean allowUnroll = unrollCtx != null && loopCtx == null
					&& unrollDepth < maxUnrollDepth && iter1Factor > 0.0;
			Set<String> loopCarriedVars = Collections.emptySet();

			if (allowUnroll) {
				LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false, false);
				Set<Long> probeVisited = new HashSet<>();
				Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> probeHopCommon = new HashMap<>();
				Map<Long, Privacy> probePrivacy = new HashMap<>();
				Set<Long> probeUnRefTwriteSet = new HashSet<>();
				Set<Long> probeUnRefSet = new HashSet<>();
				Set<Hop> probeRootSet = new HashSet<>();
				Set<Long> probeInjectedIds = new HashSet<>();
				Map<String, Map<String, List<Hop>>> probeFnCache = new HashMap<>();
				Set<String> probeFnStack = new HashSet<>(fnStack);
				List<Pair<FederatedRange, FederatedData>> probeFedMap = new ArrayList<>(fedMap);

				Map<String, List<Hop>> probeInner = new HashMap<>();
				for (Hop controlRoot : collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), hopCloneMap)))
					rewireHopDAG(controlRoot, prog, probeVisited, probeRewireTable,
							probeHopCommon, newOuterTransTableList, null, probeInner,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
							parentLoopStack, unrollDepth, maxUnrollDepth, probeCtx, null);
				Map<String, List<Hop>> probeFormer = new HashMap<>();
				probeFormer.putAll(probeInner);
				for (StatementBlock innerWsb : wstmt.getBody())
					probeFormer.putAll(rewireStatementBlock(innerWsb, prog, probeVisited, probeRewireTable,
							probeHopCommon, newOuterTransTableList, probeFormer,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight,
							networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap,
							probeCtx, null));
				loopCarriedVars = computeLoopCarriedVars(probeCtx, probeFormer);
				if (loopCarriedVars.isEmpty())
					allowUnroll = false;
			}

			if (!allowUnroll) {
				computeWeight *= loopWeight;
				networkWeight *= loopWeight;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				for (Hop controlRoot : collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), hopCloneMap)))
					rewireHopDAG(controlRoot, prog, visitedHops, rewireTable,
							hopCommonTable,
							newOuterTransTableList,
							null, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
							currentLoopStack, unrollDepth, maxUnrollDepth, loopCtx, unrollCtx);
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap, loopCtx,
							unrollCtx));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
						injectedIds);
				return newFormerTransTable;
			}

			List<Pair<Long, Double>> iter0LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter0LoopStack.addAll(parentLoopStack);
			iter0LoopStack.add(Pair.of(sb.getSBID(), 1.0));

			LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false, false);
			for (Hop controlRoot : collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), hopCloneMap)))
				rewireHopDAG(controlRoot, prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						iter0LoopStack, unrollDepth, maxUnrollDepth, iter0Analysis, unrollCtx);
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerWsb : wstmt.getBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth + 1, maxUnrollDepth, hopCloneMap,
						iter0Analysis, unrollCtx));

			Map<String, List<Hop>> iter0End = newFormerTransTable;
			loopCarriedVars = computeLoopCarriedVars(iter0Analysis, iter0End);
			double iter1Multiplicity = multiplicity * iter1Factor;
			if (loopCarriedVars.isEmpty() || iter1Multiplicity <= 0.0) {
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, iter0End, injectedIds);
				return iter0End;
			}

			List<Pair<Long, Double>> iter1LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter1LoopStack.addAll(parentLoopStack);
			iter1LoopStack.add(Pair.of(sb.getSBID(), iter1Factor));

			Map<Long, Hop> iter1HopMap = cloneStatementBlockHops(wsb, hopCloneMap, unrollCtx);
			unrollCtx.addIter1Roots(collectStatementBlockRoots(wsb, iter1HopMap));

			LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true, false);
			Map<String, List<Hop>> iter1Inner = new HashMap<>();
			Map<String, List<Hop>> iter1Former = new HashMap<>();

			for (Hop controlRoot : collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), iter1HopMap)))
				rewireHopDAG(controlRoot, prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList,
						null, iter1Inner,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
						iter1LoopStack, unrollDepth, maxUnrollDepth, iter1Analysis, unrollCtx);
			iter1Former.putAll(iter1Inner);

			for (StatementBlock innerWsb : wstmt.getBody())
				iter1Former.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, iter1Former,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth + 1, maxUnrollDepth, iter1HopMap,
						iter1Analysis, unrollCtx));

			addCrossIterEdges(loopCarriedVars, iter0End, iter1Analysis, rewireTable, unRefTwriteSet);
			mergeIter0EndIntoIter1Former(iter1Former, iter0End);
			wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, iter1Former, injectedIds);
			return iter1Former;
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, hopCloneMap, loopCtx,
							unrollCtx));

			// Wire fcall operation to liveOutHops
			wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
					injectedIds);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
						rewireHopDAG(selectHop(c, hopCloneMap), prog, visitedHops, rewireTable, hopCommonTable,
								newOuterTransTableList, formerTransTable,
								innerTransTable,
								privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
								injectedIds, functionTransTableCache,
								computeWeight, networkWeight, multiplicity, parentLoopStack, unrollDepth, maxUnrollDepth, loopCtx,
								unrollCtx);
			}

			return innerTransTable;
		}
		return newFormerTransTable;
		}
		finally {
			if (unrollCtx != null)
				unrollCtx.popStatementLiveOuts();
		}
	}

	private static Set<String> extractStatementLiveOutNames(StatementBlock sb) {
		if (sb == null || sb.liveOut() == null || sb.liveOut().getVariableNames() == null)
			return Collections.emptySet();
		return new LinkedHashSet<>(sb.liveOut().getVariableNames());
	}

	private static Hop selectHop(Hop hop, Map<Long, Hop> hopCloneMap) {
		if (hop == null || hopCloneMap == null)
			return hop;
		Hop clone = hopCloneMap.get(hop.getHopID());
		return clone != null ? clone : hop;
	}

	private static List<Hop> collectStatementBlockRoots(StatementBlock sb, Map<Long, Hop> hopCloneMap) {
		List<Hop> roots = new ArrayList<>();
		collectStatementBlockRoots(sb, hopCloneMap, roots);
		return roots;
	}

	private static List<Hop> collectControlExecutionRoots(Hop predicateHop) {
		if (predicateHop == null)
			return Collections.emptyList();
		List<Hop> roots = new ArrayList<>();
		LinkedHashSet<Long> seenRootIds = new LinkedHashSet<>();
		addRootIfNew(predicateHop, roots, seenRootIds);
		for (Hop parentHop : predicateHop.getParent()) {
			if (HopUtils.isPredTWrite(parentHop))
				addRootIfNew(parentHop, roots, seenRootIds);
		}
		return roots;
	}

	private static void addRootIfNew(Hop rootHop, List<Hop> roots, Set<Long> seenRootIds) {
		if (rootHop == null || roots == null || seenRootIds == null)
			return;
		if (seenRootIds.add(rootHop.getHopID()))
			roots.add(rootHop);
	}

	static List<Hop> collectExecutableStatementRoots(StatementBlock sb) {
		List<Hop> executableRoots = new ArrayList<>();
		LinkedHashSet<Long> seenRootIds = new LinkedHashSet<>();
		for (Hop rootHop : collectStatementBlockRoots(sb, null)) {
			if (rootHop == null || rootHop instanceof LiteralOp || !seenRootIds.add(rootHop.getHopID()))
				continue;
			executableRoots.add(rootHop);
		}
		return executableRoots;
	}

	private static void collectStatementBlockRoots(StatementBlock sb, Map<Long, Hop> hopCloneMap,
			List<Hop> roots) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			roots.addAll(collectControlExecutionRoots(selectHop(isb.getPredicateHops(), hopCloneMap)));
			for (StatementBlock inner : istmt.getIfBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
			for (StatementBlock inner : istmt.getElseBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			roots.add(selectHop(fsb.getFromHops(), hopCloneMap));
			roots.add(selectHop(fsb.getToHops(), hopCloneMap));
			if (fsb.getIncrementHops() != null)
				roots.add(selectHop(fsb.getIncrementHops(), hopCloneMap));
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			roots.addAll(collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), hopCloneMap)));
			for (StatementBlock inner : wstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else {
			if (sb.getHops() != null) {
				for (Hop hop : sb.getHops())
					roots.add(selectHop(hop, hopCloneMap));
			}
		}
	}

	private static Hop deepCopyHop(Hop hop, Map<Long, Hop> memo) {
		Hop cached = memo.get(hop.getHopID());
		if (cached != null)
			return cached;

		try {
			Hop copy = (Hop) hop.clone();
			copy.getInput().clear();
			copy.getParent().clear();
			memo.put(hop.getHopID(), copy);
			if (hop.getInput() != null) {
				for (Hop in : hop.getInput()) {
					Hop inCopy = deepCopyHop(in, memo);
					copy.getInput().add(inCopy);
					inCopy.getParent().add(copy);
				}
			}
			return copy;
		} catch (CloneNotSupportedException ex) {
			throw new HopsException(ex);
		}
	}

	private static Map<Long, Hop> cloneStatementBlockHops(StatementBlock sb, Map<Long, Hop> baseHopMap,
			UnrollContext unrollCtx) {
		Map<Long, Hop> memo = new HashMap<>();
		cloneStatementBlockHops(sb, baseHopMap, memo);
		if (unrollCtx != null) {
			for (Map.Entry<Long, Hop> entry : memo.entrySet()) {
				long baseId = entry.getKey();
				Hop clone = entry.getValue();
				long origId = resolveOriginalHopId(baseId, unrollCtx.cloneToOrig);
				unrollCtx.cloneToOrig.put(clone.getHopID(), origId);
			}
		}
		return memo;
	}

	private static void cloneStatementBlockHops(StatementBlock sb, Map<Long, Hop> baseHopMap,
			Map<Long, Hop> memo) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			for (Hop rootHop : collectControlExecutionRoots(selectHop(isb.getPredicateHops(), baseHopMap)))
				deepCopyHop(rootHop, memo);
			for (StatementBlock inner : istmt.getIfBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
			for (StatementBlock inner : istmt.getElseBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			deepCopyHop(selectHop(fsb.getFromHops(), baseHopMap), memo);
			deepCopyHop(selectHop(fsb.getToHops(), baseHopMap), memo);
			if (fsb.getIncrementHops() != null)
				deepCopyHop(selectHop(fsb.getIncrementHops(), baseHopMap), memo);
			for (StatementBlock inner : fstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			for (Hop rootHop : collectControlExecutionRoots(selectHop(wsb.getPredicateHops(), baseHopMap)))
				deepCopyHop(rootHop, memo);
			for (StatementBlock inner : wstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else {
			if (sb.getHops() != null) {
				for (Hop hop : sb.getHops())
					deepCopyHop(selectHop(hop, baseHopMap), memo);
			}
		}
	}

	private static long resolveOriginalHopId(long baseHopId, Map<Long, Long> cloneToOrig) {
		Long origId = cloneToOrig.get(baseHopId);
		return origId != null ? origId : baseHopId;
	}

	private static Set<String> computeLoopCarriedVars(LoopAnalysisContext ctx,
			Map<String, List<Hop>> endTransTable) {
		Set<String> loopCarried = new HashSet<>();
		if (ctx == null || endTransTable == null || endTransTable.isEmpty())
			return loopCarried;
		for (Map.Entry<String, Boolean> entry : ctx.getReadFromOutside().entrySet()) {
			if (!Boolean.TRUE.equals(entry.getValue()))
				continue;
			List<Hop> writes = endTransTable.get(entry.getKey());
			if (writes != null && !writes.isEmpty())
				loopCarried.add(entry.getKey());
		}
		return loopCarried;
	}

	private static void addCrossIterEdges(Set<String> loopCarriedVars, Map<String, List<Hop>> iter0End,
			LoopAnalysisContext iter1Ctx, Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet) {
		if (loopCarriedVars == null || loopCarriedVars.isEmpty() || iter1Ctx == null || iter0End == null)
			return;
		Map<String, List<Hop>> iter1Reads = iter1Ctx.getHeaderReads();
		for (String var : loopCarriedVars) {
			List<Hop> writes = iter0End.get(var);
			List<Hop> reads = iter1Reads.get(var);
			if (writes == null || writes.isEmpty() || reads == null || reads.isEmpty())
				continue;
			List<Hop> uniqueWrites = new ArrayList<>();
			for (Hop writeHop : writes) {
				if (writeHop != null && !uniqueWrites.contains(writeHop))
					uniqueWrites.add(writeHop);
			}
			if (uniqueWrites.isEmpty())
				continue;
			for (Hop readHop : reads) {
				if (readHop == null)
					continue;
				List<Hop> prevParents = rewireTable.get(readHop.getHopID());
				if (prevParents != null && !prevParents.isEmpty()) {
					for (Hop prevParent : prevParents) {
						if (prevParent == null)
							continue;
						List<Hop> siblings = rewireTable.get(prevParent.getHopID());
						if (siblings != null)
							siblings.removeIf(hop -> hop == readHop);
					}
				}
				rewireTable.put(readHop.getHopID(), new ArrayList<>(uniqueWrites));
				for (Hop writeHop : uniqueWrites) {
					List<Hop> children = rewireTable.computeIfAbsent(writeHop.getHopID(), k -> new ArrayList<>());
					if (!children.contains(readHop))
						children.add(readHop);
					unRefTwriteSet.remove(writeHop.getHopID());
				}
			}
		}
	}

	private static void mergeIter0EndIntoIter1Former(Map<String, List<Hop>> iter1Former,
			Map<String, List<Hop>> iter0End) {
		if (iter1Former == null || iter0End == null || iter0End.isEmpty())
			return;
		for (Map.Entry<String, List<Hop>> entry : iter0End.entrySet()) {
			List<Hop> existing = iter1Former.get(entry.getKey());
			if (existing == null || existing.isEmpty())
				iter1Former.put(entry.getKey(), entry.getValue());
		}
	}

	private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			List<Map<String, List<Hop>>> outerTransTableList,
			Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
			Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet,
			Set<String> fnStack, Set<Long> injectedIds,
			Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, double multiplicity, List<Pair<Long, Double>> loopStack,
			int unrollDepth, int maxUnrollDepth, LoopAnalysisContext loopCtx, UnrollContext unrollCtx) {
		boolean includeTransReadChildren = loopCtx == null || loopCtx.includeTransReadChildren();
		RewireDagWalker.Context ctx = new RewireDagWalker.Context(
				visitedHops, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
				includeTransReadChildren);
		RewireDagWalker.walk(hop, ctx, new RewireDagWalker.Visitor() {
			@Override
			public void afterChildren(Hop hop, RewireDagWalker.Context ctx) {
				double hopComputeWeight = computeWeight;
				double hopNetworkWeight = networkWeight;
				double hopMultiplicity = multiplicity;
				List<Pair<Long, Double>> hopLoopStack = loopStack;

				FederatedPlannerDpMemoTable.HopCommon passThroughCommon = resolvePassThroughSourceCommon(hop,
						hopCommonTable, ctx.rewireTable());
				if (passThroughCommon == null) {
					passThroughCommon = resolvePassThroughInputCommon(hop, hopCommonTable);
				}
				if (passThroughCommon != null) {
					hopComputeWeight = passThroughCommon.getComputeWeight();
					hopNetworkWeight = passThroughCommon.getNetworkWeight();
					hopMultiplicity = passThroughCommon.getMultiplicity();
					hopLoopStack = passThroughCommon.getLoopContext();
				}

				hopCommonTable.put(hop.getHopID(),
						new FederatedPlannerDpMemoTable.HopCommon(hop, hopComputeWeight, hopNetworkWeight,
								hopMultiplicity, 0, hopLoopStack));
				FederatedPlannerLogger.logBasicHopInfo(hop, "RewireHopDAG:addCommon");

				// Identify hops to connect to the root dummy node
				// Connect TWrite pred and u(print) to the root dummy node
				if (HopUtils.isPredTWrite(hop) || HopUtils.isPrintOrPWrite(hop)) {
					progRootHopSet.add(hop);
				} else if (!(hop instanceof DataOp
						&& ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
						&& hop.getParent().size() == 0) {
					unRefSet.add(hop.getHopID());
				}

				if (hop instanceof FunctionOp) {
					// maintain counters and investigate functions if not seen so far
					FunctionOp fop = (FunctionOp) hop;
					unRefTwriteSet.add(fop.getHopID());
					FunctionType functionType = fop.getFunctionType();
					if (functionType == FunctionType.DML || functionType == FunctionType.MULTIRETURN_BUILTIN) {
						String fkey = fop.getFunctionKey();
						FunctionStatementBlock fsb = (prog != null)
							? prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName())
							: null;
						Map<String, List<Hop>> functionTransTable = functionTransTableCache.get(fkey);
						List<Hop> hiddenFunctionBoundaryHops = getHiddenFunctionBoundaryHops(functionTransTable);
						Set<Long> functionOutputIds = new LinkedHashSet<>();
						List<Hop> functionOutputHops = new ArrayList<>();
						boolean pushed = false;

						// DML-backed multi-return builtins (e.g., builtin pca) execute the full
						// function body even when the caller only consumes a subset of outputs.
						// Therefore, when a concrete FunctionStatementBlock exists, rewire/map the
						// actual function outputs rather than relying only on fop.getOutputs(),
						// which can omit caller-unused outputs and leave their executable subgraphs
						// unrevised.
						if (functionTransTable == null && fsb != null && !fnStack.contains(fkey)) {
							fnStack.add(fkey);
							pushed = true;
							try {
								Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
								if (formerTransTable != null)
									newFormerTransTable.putAll(formerTransTable);
								newFormerTransTable.putAll(innerTransTable);
								Set<Long> nestedUnRefTwriteSet = new HashSet<>();
								Set<Long> nestedUnRefSet = new HashSet<>();
								Set<Hop> nestedProgRootHopSet = new LinkedHashSet<>();

								String[] inputArgs = fop.getInputVariableNames();
								List<Hop> inputHops = fop.getInput();

								TransTableRewireUtils.mapFunctionInputsToFormerTransTable(
									inputArgs, inputHops, rewireTable, newFormerTransTable);

								try (FederatedPlannerUtils.ScopedFedVarOverride ignored =
										FederatedPlannerUtils.scopedFedVarsForFunctionCall(inputArgs, inputHops)) {
									functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
										rewireTable, hopCommonTable, outerTransTableList, newFormerTransTable,
										privacyConstraintMap, fedMap, nestedUnRefTwriteSet, nestedUnRefSet,
										nestedProgRootHopSet, fnStack, injectedIds, functionTransTableCache,
										computeWeight, networkWeight, multiplicity, loopStack, unrollDepth,
										maxUnrollDepth, null, loopCtx, unrollCtx);
								}
									if (functionTransTable != null) {
										attachHiddenFunctionBoundaryHops(functionTransTable,
											nestedUnRefTwriteSet, hopCommonTable);
										functionTransTableCache.put(fkey, functionTransTable);
										hiddenFunctionBoundaryHops = getHiddenFunctionBoundaryHops(functionTransTable);
									}
							}
							finally {
								if (pushed)
									fnStack.remove(fkey);
							}
						}

						boolean mappedFromFunctionBody = false;
						if (fsb != null && functionTransTable != null) {
							List<Hop> callerUnconsumedOutputHops = collectCallerUnconsumedFunctionOutputHops(
								fop, fsb, functionTransTable);
							Set<Long> callerUnconsumedOutputHopIds = toHopIdSet(callerUnconsumedOutputHops);
							TransTableRewireUtils.mapFunctionOutputsWithNames(
								fop, fsb, functionTransTable, innerTransTable,
								(outputHop, callerOutputName) -> registerFunctionBoundaryHop(
									outputHop, hopCommonTable, unRefTwriteSet,
									functionOutputIds, functionOutputHops, computeWeight,
									networkWeight, multiplicity, loopStack, false));
							if (unrollCtx != null && !callerUnconsumedOutputHops.isEmpty())
								unrollCtx.addAdditionalRoots(callerUnconsumedOutputHops);
							appendHiddenFunctionBoundaryHops(filterHiddenFunctionBoundaryHops(
									hiddenFunctionBoundaryHops, callerUnconsumedOutputHopIds), hopCommonTable,
								unRefTwriteSet, functionOutputIds, functionOutputHops,
								computeWeight, networkWeight, multiplicity, loopStack);
							mappedFromFunctionBody = !functionOutputHops.isEmpty();
						}
						appendAdditionalFunctionExecutionRoots(fsb, functionOutputIds, unrollCtx);

						if (!mappedFromFunctionBody && functionType == FunctionType.MULTIRETURN_BUILTIN) {
							TransTableRewireUtils.mapFunctionOutputsWithNames(
								fop, null, null, innerTransTable,
								(outputHop, callerOutputName) -> registerFunctionBoundaryHop(
									outputHop, hopCommonTable, unRefTwriteSet,
									functionOutputIds, functionOutputHops, computeWeight,
									networkWeight, multiplicity, loopStack, true));
						}

						if (!functionOutputHops.isEmpty())
							ctx.rewireTable().put(fop.getHopID(), functionOutputHops);
						else
							ctx.rewireTable().remove(fop.getHopID());
					}
				}

				// Propagate Privacy Constraint
				if (!(hop instanceof DataOp) || hop.getName().equals("__pred")
						|| (((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE)) {
					privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
							hop, hop.getInput(), privacyConstraintMap));
					return;
				}

				rewireTransHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
						privacyConstraintMap,
						fedMap, unRefTwriteSet, injectedIds, loopCtx);
			}
		});
	}

	private static void rewireTransHop(Hop hop, Map<Long, List<Hop>> rewireTable,
			List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
			Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> injectedIds,
			LoopAnalysisContext loopCtx) {
		DataOp dataOp = (DataOp) hop;
		Types.OpOpData opType = dataOp.getOp();
		String hopName = dataOp.getName();

		if (opType == Types.OpOpData.FEDERATED) {
			Privacy privacy = FederatedPlannerUtils.getFedWorkerMetaData(fedMap, dataOp);
			privacyConstraintMap.put(hop.getHopID(), privacy);
			FederatedPlannerLogger.logInfoMessage("[RewireTransHop] FED init detected: var="
					+ hopName + ", hopID=" + hop.getHopID() + ", privacy=" + privacy);
		} else if (opType == Types.OpOpData.TRANSIENTWRITE) {
			// Rewire TransWrite
			innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
			unRefTwriteSet.add(hop.getHopID());
			if (loopCtx != null)
				loopCtx.markWritten(hopName);
			// Propagate Privacy Constraint
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, hop.getInput(), privacyConstraintMap));
		} else if (opType == Types.OpOpData.TRANSIENTREAD) {
			// Rewire TransRead
			List<Hop> childHops = TransTableRewireUtils.resolveTransReadChildren(
					dataOp, rewireTable,
					innerTransTable, formerTransTable, outerTransTableList);
			boolean hasInner = false;
			if (innerTransTable != null) {
				List<Hop> innerHops = innerTransTable.get(hopName);
				hasInner = innerHops != null && !innerHops.isEmpty();
			}
			boolean hasFormer = false;
			if (formerTransTable != null) {
				List<Hop> formerHops = formerTransTable.get(hopName);
				hasFormer = formerHops != null && !formerHops.isEmpty();
			}
			boolean fromOutside = !hasInner && !hasFormer && childHops != null && !childHops.isEmpty();
			if (fromOutside && loopCtx != null && !loopCtx.hasWritten(hopName)) {
				loopCtx.markReadFromOutside(hopName);
				loopCtx.recordHeaderRead(hopName, hop);
			}

			// Todo: Handle exception when TRead has no Child (check why it's missing)
			if (childHops == null || childHops.isEmpty()) {
				FederatedPlannerLogger.logTransReadRewireDebug(hopName, hop.getHopID(), childHops, true,
						"RewireTransHop");
				privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
				return;
			}

			List<Hop> filteredChildHops = TransTableRewireUtils.filterTransReadChildren(
					hopName, childHops, injectedIds, true, false);

			FederatedPlannerLogger.logRewireHierarchy(hop, childHops, filteredChildHops, "RewireTransHop");

			// Todo: Handle exception when TRead has no Filtered Child (check why it's
			// missing)
			if (filteredChildHops.isEmpty()) {
				rewireTable.remove(hop.getHopID());
				FederatedPlannerLogger.logFilteredChildHopsDebug(hopName, hop.getHopID(), filteredChildHops, true,
						"RewireTransHop");
				privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
				return;
			}

			TransTableRewireUtils.registerTransReadMapping(hop.getHopID(), filteredChildHops, rewireTable);
			TransTableRewireUtils.registerTransWriteLinks(hop, filteredChildHops, rewireTable, unRefTwriteSet);
			// Propagate Privacy Constraint
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, filteredChildHops, privacyConstraintMap));
		} else {
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, hop.getInput(), privacyConstraintMap));
		}
	}

	private static FederatedPlannerDpMemoTable.HopCommon resolvePassThroughSourceCommon(Hop hop,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable) {
		Hop sourceHop = TransTableRewireUtils.resolvePassThroughSourceHop(hop, rewireTable);
		if (sourceHop == null || sourceHop == hop) {
			return null;
		}
		return hopCommonTable.get(sourceHop.getHopID());
	}

	private static void attachHiddenFunctionBoundaryHops(Map<String, List<Hop>> functionTransTable,
			Set<Long> nestedUnRefTwriteSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (functionTransTable == null)
			return;
		if (nestedUnRefTwriteSet == null || nestedUnRefTwriteSet.isEmpty()) {
			functionTransTable.remove(FUNCTION_HIDDEN_ROOTS_KEY);
			return;
		}
		List<Hop> hiddenRoots = new ArrayList<>();
		Set<Long> seenHopIds = new LinkedHashSet<>();
		for (Long hopId : nestedUnRefTwriteSet) {
			if (hopId == null || !seenHopIds.add(hopId))
				continue;
			FederatedPlannerDpMemoTable.HopCommon hopCommon =
					(hopCommonTable != null) ? hopCommonTable.get(hopId) : null;
			Hop hopRef = (hopCommon != null) ? hopCommon.getHopRef() : null;
			if (hopRef == null || hopRef.getDataType() == null || !hopRef.getDataType().isMatrix())
				continue;
			hiddenRoots.add(hopRef);
		}
		if (hiddenRoots.isEmpty())
			functionTransTable.remove(FUNCTION_HIDDEN_ROOTS_KEY);
		else
			functionTransTable.put(FUNCTION_HIDDEN_ROOTS_KEY, hiddenRoots);
	}

	private static void appendAdditionalFunctionExecutionRoots(FunctionStatementBlock fsb,
			Set<Long> functionOutputIds, UnrollContext unrollCtx) {
		if (fsb == null || unrollCtx == null)
			return;
		List<Hop> additionalRoots = new ArrayList<>();
		LinkedHashSet<Long> seenRootIds = new LinkedHashSet<>();
		if (functionOutputIds != null)
			seenRootIds.addAll(functionOutputIds);
		for (Hop rootHop : collectStatementBlockRoots(fsb, null)) {
			if (rootHop == null || rootHop instanceof LiteralOp)
				continue;
			if (seenRootIds.add(rootHop.getHopID()))
				additionalRoots.add(rootHop);
		}
		unrollCtx.addAdditionalRoots(additionalRoots);
	}

	private static List<Hop> getHiddenFunctionBoundaryHops(Map<String, List<Hop>> functionTransTable) {
		if (functionTransTable == null)
			return Collections.emptyList();
		List<Hop> hiddenRoots = functionTransTable.get(FUNCTION_HIDDEN_ROOTS_KEY);
		return (hiddenRoots == null || hiddenRoots.isEmpty()) ? Collections.emptyList() : hiddenRoots;
	}

	private static List<Hop> collectCallerUnconsumedFunctionOutputHops(FunctionOp fop, FunctionStatementBlock fsb,
			Map<String, List<Hop>> functionTransTable) {
		if (fop == null || fsb == null || functionTransTable == null)
			return Collections.emptyList();
		String[] callerOutputNames = fop.getOutputVariableNames();
		int callerOutputCount = (callerOutputNames != null) ? callerOutputNames.length : 0;
		List<DataIdentifier> functionOutputs = resolveFunctionOutputParams(fsb);
		if (functionOutputs == null || functionOutputs.isEmpty() || callerOutputCount >= functionOutputs.size())
			return Collections.emptyList();
		List<Hop> additionalRoots = new ArrayList<>();
		LinkedHashSet<Long> seenHopIds = new LinkedHashSet<>();
		for (int i = callerOutputCount; i < functionOutputs.size(); i++) {
			DataIdentifier output = functionOutputs.get(i);
			if (output == null)
				continue;
			List<Hop> mappedHops = functionTransTable.get(output.getName());
			if (mappedHops == null || mappedHops.isEmpty())
				continue;
			for (Hop mappedHop : mappedHops) {
				if (mappedHop == null || mappedHop instanceof LiteralOp || !seenHopIds.add(mappedHop.getHopID()))
					continue;
				additionalRoots.add(mappedHop);
			}
		}
		return additionalRoots;
	}

	private static List<DataIdentifier> resolveFunctionOutputParams(FunctionStatementBlock fsb) {
		if (fsb == null)
			return Collections.emptyList();
		List<DataIdentifier> orderedFunctionOutputs = null;
		if (fsb.getStatements() != null && !fsb.getStatements().isEmpty()
			&& fsb.getStatement(0) instanceof FunctionStatement) {
			orderedFunctionOutputs = ((FunctionStatement) fsb.getStatement(0)).getOutputParams();
		}
		List<DataIdentifier> functionOutputs = (orderedFunctionOutputs != null && !orderedFunctionOutputs.isEmpty())
			? orderedFunctionOutputs
			: fsb.getOutputsofSB();
		return (functionOutputs == null) ? Collections.emptyList() : functionOutputs;
	}

	private static Set<Long> toHopIdSet(List<Hop> hops) {
		if (hops == null || hops.isEmpty())
			return Collections.emptySet();
		LinkedHashSet<Long> hopIds = new LinkedHashSet<>();
		for (Hop hop : hops) {
			if (hop != null)
				hopIds.add(hop.getHopID());
		}
		return hopIds;
	}

	private static List<Hop> filterHiddenFunctionBoundaryHops(List<Hop> hiddenFunctionBoundaryHops,
			Set<Long> excludedHopIds) {
		if (hiddenFunctionBoundaryHops == null || hiddenFunctionBoundaryHops.isEmpty())
			return Collections.emptyList();
		if (excludedHopIds == null || excludedHopIds.isEmpty())
			return hiddenFunctionBoundaryHops;
		List<Hop> filtered = new ArrayList<>();
		for (Hop hiddenBoundaryHop : hiddenFunctionBoundaryHops) {
			if (hiddenBoundaryHop == null || excludedHopIds.contains(hiddenBoundaryHop.getHopID()))
				continue;
			filtered.add(hiddenBoundaryHop);
		}
		return filtered;
	}

	private static void appendHiddenFunctionBoundaryHops(List<Hop> hiddenFunctionBoundaryHops,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Set<Long> unRefTwriteSet,
			Set<Long> functionOutputIds, List<Hop> functionOutputHops,
			double computeWeight, double networkWeight, double multiplicity,
			List<Pair<Long, Double>> loopStack) {
		if (hiddenFunctionBoundaryHops == null || hiddenFunctionBoundaryHops.isEmpty())
			return;
		for (Hop hiddenBoundaryHop : hiddenFunctionBoundaryHops) {
			registerFunctionBoundaryHop(hiddenBoundaryHop, hopCommonTable, unRefTwriteSet,
				functionOutputIds, functionOutputHops, computeWeight, networkWeight,
				multiplicity, loopStack, true);
		}
	}

	private static void registerFunctionBoundaryHop(Hop boundaryHop,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Set<Long> unRefTwriteSet,
			Set<Long> functionOutputIds, List<Hop> functionOutputHops,
			double computeWeight, double networkWeight, double multiplicity,
			List<Pair<Long, Double>> loopStack, boolean markUnreferenced) {
		if (boundaryHop == null)
			return;
		FederatedPlannerDpMemoTable.HopCommon hopCommon =
				(hopCommonTable != null) ? hopCommonTable.get(boundaryHop.getHopID()) : null;
		if (hopCommon == null && hopCommonTable != null) {
			hopCommon = new FederatedPlannerDpMemoTable.HopCommon(boundaryHop, computeWeight,
					networkWeight, multiplicity, 0, loopStack);
			hopCommonTable.put(boundaryHop.getHopID(), hopCommon);
		}
		if (markUnreferenced && unRefTwriteSet != null)
			unRefTwriteSet.add(boundaryHop.getHopID());
		if (boundaryHop instanceof LiteralOp || functionOutputIds == null || functionOutputHops == null)
			return;
		if (functionOutputIds.add(boundaryHop.getHopID()))
			functionOutputHops.add(boundaryHop);
	}

	private static FederatedPlannerDpMemoTable.HopCommon resolvePassThroughInputCommon(Hop hop,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (!TransTableRewireUtils.isPassThroughTWrite(hop)) {
			return null;
		}
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty()) {
			return null;
		}
		return hopCommonTable.get(inputs.get(0).getHopID());
	}

	private static void wireUnRefTwriteToLiveOut(
			StatementBlock sb, Set<Long> unRefTwriteSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<String, List<Hop>> newFormerTransTable) {

		Function<Long, Hop> hopLookup = id -> {
			FederatedPlannerDpMemoTable.HopCommon hc = hopCommonTable.get(id);
			return (hc != null) ? hc.getHopRef() : null;
		};

		FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
				sb,
				unRefTwriteSet,
				hopLookup,
				newFormerTransTable,
				// compatFn: unRefTwriteHop vs 대표 liveOutHop
				(unRefTwriteHop, liveOutHop) -> TransTableRewireUtils.calculateCompatibilityScore(
						unRefTwriteHop, liveOutHop, hopLookup),
				"[DP]");
	}

	private static void wireUnRefTwriteToLiveOutWithTracking(
			StatementBlock sb, Set<Long> unRefTwriteSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<String, List<Hop>> newFormerTransTable, Set<Long> injectedIds) {
		if (injectedIds == null) {
			wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			return;
		}
		Set<Long> before = new HashSet<>(unRefTwriteSet);
		wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
		for (Long hopId : before) {
			if (!unRefTwriteSet.contains(hopId)) {
				injectedIds.add(hopId);
			}
		}
	}

}

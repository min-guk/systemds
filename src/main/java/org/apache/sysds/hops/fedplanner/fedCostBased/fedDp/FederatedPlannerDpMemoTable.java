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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
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
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
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

public class FederatedPlannerDpMemoTable {
	// Maps Hop ID and fedOutType pairs to their plan variants
	private final Map<Pair<Long, FederatedOutput>, FedPlanVariants> hopMemoTable = new HashMap<>();
	private final Map<Hop, Map<FederatedOutput, FedPlanVariants>> exactMemoByCarrier = new IdentityHashMap<>();
	private final Map<Pair<Long, FederatedOutput>, Set<Hop>> carriersByLegacyCoordinate = new HashMap<>();
	private final PlacementAnalysis analysis;
	private final Set<HopOccurrenceProjection> ownedOccurrences;
	private final Map<CompiledHopKey, HopOccurrenceProjection> occurrenceByKey;
	private final List<TransientConflictRelation> transientConflictRelations;
	private final Map<Hop, HopOccurrenceProjection> occurrenceByPlanCarrier = new IdentityHashMap<>();
	private final Map<Long, Hop> hopRefMap = new HashMap<>();
	private final Map<Long, Long> cloneToOrig = new HashMap<>();
	private final LinkedHashSet<Long> deadFunctionOutputHopIDs = new LinkedHashSet<>();
	/**
	 * Additional root hops that are executed (e.g., loop-unrolled "iter1" roots)
	 * but might not be reachable from the dummy root through Hop parent links.
	 *
	 * <p>We store the <em>clone</em> hop IDs (not original IDs) so downstream
	 * rewrite/conflict resolution can observe the correct loop multiplicity and
	 * forwarding weights.</p>
	 */
	private final LinkedHashSet<Long> additionalRootHopIDs = new LinkedHashSet<>();
	private int _numWorkers = 1;

	public FederatedPlannerDpMemoTable() {
		analysis = null;
		ownedOccurrences = Collections.emptySet();
		occurrenceByKey = Collections.emptyMap();
		transientConflictRelations = List.of();
	}

	public FederatedPlannerDpMemoTable(PlacementAnalysis analysis) {
		this.analysis = java.util.Objects.requireNonNull(analysis, "analysis");
		Set<HopOccurrenceProjection> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		occurrences.addAll(analysis.occurrences());
		ownedOccurrences = Collections.unmodifiableSet(occurrences);
		Map<CompiledHopKey,HopOccurrenceProjection> indexed = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			if(indexed.put(occurrence.key(), occurrence) != null)
				throw new IllegalArgumentException("Duplicate exact DP occurrence key");
		occurrenceByKey = Collections.unmodifiableMap(indexed);
		transientConflictRelations = buildTransientConflictRelations(analysis, occurrenceByKey);
	}

	/** Immutable source/write to target/read topology reused by every decision-dependent BFS. */
	static record TransientConflictRelation(HopOccurrenceProjection source,
		HopOccurrenceProjection target) {
		TransientConflictRelation {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(target, "target");
		}
	}

	List<TransientConflictRelation> transientConflictRelations() {
		return transientConflictRelations;
	}

	HopOccurrenceProjection requireAnalysisOccurrence(CompiledHopKey key) {
		HopOccurrenceProjection occurrence = occurrenceByKey.get(Objects.requireNonNull(key, "key"));
		if(occurrence == null)
			throw new IllegalStateException("Exact DP key has no analysis-owned occurrence: " + key);
		return occurrence;
	}

	private static List<TransientConflictRelation> buildTransientConflictRelations(
		PlacementAnalysis analysis, Map<CompiledHopKey,HopOccurrenceProjection> occurrences) {
		List<TransientConflictRelation> relations = new ArrayList<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> targetsBySource = new IdentityHashMap<>();

		for(PlacementAnalysis.LogicalTransientInputFact fact :
			analysis.logicalTransientInputsInCanonicalOrder())
			addTransientConflictRelation(relations, targetsBySource, occurrences,
				fact.sourceWrite(), fact.targetRead());

		Map<CompiledHopKey,List<CompiledHopKey>> formalsByBoundary = new IdentityHashMap<>();
		Map<CompiledHopKey,CompiledHopKey> bindingWriteByFormal = new IdentityHashMap<>();
		for(NeutralPlacementGraph.Constraint constraint : analysis.graph().constraints()) {
			if(constraint.kind() != NeutralPlacementGraph.ConstraintKind.SAME_PLACEMENT)
				continue;
			if("function-formal-input".equals(constraint.evidence()))
				formalsByBoundary.computeIfAbsent(constraint.left(), ignored -> new ArrayList<>())
					.add(constraint.right());
			else if("function-input-binding".equals(constraint.evidence())) {
				Hop inputHop = analysis.hop(constraint.left()).orElseThrow();
				Hop bindingHop = analysis.hop(constraint.right()).orElseThrow();
				if(!(inputHop instanceof DataOp) || !(bindingHop instanceof DataOp)
					|| ((DataOp) inputHop).getOp() != Types.OpOpData.TRANSIENTREAD
					|| ((DataOp) bindingHop).getOp() != Types.OpOpData.TRANSIENTWRITE)
					throw new IllegalStateException(
						"Transparent function-input binding is not TRead->TWrite: "
							+ constraint.normalizedSignature());
				CompiledHopKey previous = bindingWriteByFormal.put(constraint.left(), constraint.right());
				if(previous != null && previous != constraint.right())
					throw new IllegalStateException("Function formal input has multiple binding writes");
				addTransientConflictRelation(relations, targetsBySource, occurrences,
					constraint.right(), constraint.left());
			}
		}
		for(List<CompiledHopKey> formalReads : formalsByBoundary.values())
			for(CompiledHopKey boundFormal : formalReads) {
				CompiledHopKey bindingWrite = bindingWriteByFormal.get(boundFormal);
				if(bindingWrite == null)
					continue;
				for(CompiledHopKey formalRead : formalReads)
					addTransientConflictRelation(relations, targetsBySource, occurrences,
						bindingWrite, formalRead);
			}

		for(HopOccurrenceProjection targetOccurrence : analysis.compiledHopOccurrences()) {
			Hop targetHop = targetOccurrence.hop();
			if(!(targetHop instanceof DataOp)
				|| ((DataOp) targetHop).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;
			for(CompiledHopKey sourceKey :
				analysis.cfgDefinitionSourcesInCanonicalOrder(targetOccurrence.key())) {
				Hop sourceHop = analysis.hop(sourceKey).orElseThrow();
				if(sourceHop instanceof DataOp
					&& ((DataOp) sourceHop).getOp() == Types.OpOpData.TRANSIENTWRITE)
					addTransientConflictRelation(relations, targetsBySource, occurrences,
						sourceKey, targetOccurrence.key());
			}
		}
		return List.copyOf(relations);
	}

	private static void addTransientConflictRelation(List<TransientConflictRelation> relations,
		Map<CompiledHopKey,Set<CompiledHopKey>> targetsBySource,
		Map<CompiledHopKey,HopOccurrenceProjection> occurrences,
		CompiledHopKey sourceKey, CompiledHopKey targetKey) {
		HopOccurrenceProjection source = occurrences.get(sourceKey);
		HopOccurrenceProjection target = occurrences.get(targetKey);
		if(source == null || target == null)
			throw new IllegalStateException("Transient conflict relation has no analysis-owned occurrence");
		Set<CompiledHopKey> targets = targetsBySource.computeIfAbsent(sourceKey,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
		if(targets.add(targetKey))
			relations.add(new TransientConflictRelation(source, target));
	}

	public PlacementAnalysis analysis() {
		return analysis;
	}

	public void addFedPlanVariants(HopOccurrenceProjection occurrence, FederatedOutput fedOutType,
		FedPlanVariants fedPlanVariants) {
		assertOwnedOccurrence(occurrence);
		Hop carrier = fedPlanVariants == null || fedPlanVariants.hopCommon == null
			? null : fedPlanVariants.hopCommon.getHopRef();
		if(carrier == null || requirePlanCarrierOccurrence(carrier) != occurrence
			|| fedPlanVariants.getFedOutType() != fedOutType)
			throw new IllegalArgumentException("Plan variants do not bind the supplied occurrence");
		occurrenceByPlanCarrier.put(carrier, occurrence);
		validateExactPlacementStates(occurrence, fedPlanVariants);
		addExactFedPlanVariants(carrier, fedOutType, fedPlanVariants);
	}

	public void addFedPlanVariants(RewireOccurrenceSnapshot snapshot, HopOccurrenceProjection occurrence,
		FederatedOutput fedOutType, FedPlanVariants fedPlanVariants) {
		assertOwnedSnapshot(snapshot);
		assertOwnedOccurrence(occurrence);
		Hop carrier = fedPlanVariants == null || fedPlanVariants.hopCommon == null
			? null : fedPlanVariants.hopCommon.getHopRef();
		if(carrier == null || snapshot.projectExactCarrier(carrier) != occurrence
			|| fedPlanVariants.getFedOutType() != fedOutType)
			throw new IllegalArgumentException("Plan variants do not bind the supplied rewire occurrence");
		occurrenceByPlanCarrier.put(carrier, occurrence);
		validateExactPlacementStates(occurrence, fedPlanVariants);
		addExactFedPlanVariants(carrier, fedOutType, fedPlanVariants);
	}

	private void addExactFedPlanVariants(Hop carrier, FederatedOutput output, FedPlanVariants variants) {
		Map<FederatedOutput,FedPlanVariants> byOutput = exactMemoByCarrier.computeIfAbsent(
			carrier, ignored -> new java.util.EnumMap<>(FederatedOutput.class));
		byOutput.put(output, variants);
		Pair<Long,FederatedOutput> legacy = Pair.of(carrier.getHopID(), output);
		carriersByLegacyCoordinate.computeIfAbsent(legacy,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(carrier);
		// Preserve a legacy representative for old ID-based callers, but never overwrite
		// a distinct occurrence-owned plan. Ambiguous legacy reads fail closed below.
		FedPlanVariants representative = hopMemoTable.get(legacy);
		if(representative == null || representative.hopCommon != null
			&& representative.hopCommon.getHopRef() == carrier)
			hopMemoTable.put(legacy, variants);
	}

	private void validateExactPlacementStates(HopOccurrenceProjection occurrence, FedPlanVariants variants) {
		NeutralPlacementGraph.Node node = analysis.graph().node(occurrence.key()).orElseThrow();
		for(FedPlan plan : variants.getFedPlanVariants()) {
			PlacementState exact = Objects.requireNonNull(plan.getSelectedPlacementState(),
				"DP plan lacks an exact analysis-owned placement carrier for " + occurrence.key());
			boolean exactOwner = node.legalAlternatives().stream().anyMatch(state -> state == exact);
			boolean execMatches = exact.execType() == plan.getExecType();
			boolean outputMatches = exact.output() == plan.getFedOutType();
			boolean fTypeMatches = exact.execType() != ExecType.FED || exact.output() != FederatedOutput.FOUT
				|| exact.fType() == plan.getFType();
			if(!exactOwner || !execMatches || !outputMatches || !fTypeMatches)
				throw new IllegalStateException("DP plan has an invalid exact placement carrier for " + occurrence.key()
					+ ": exactOwner=" + exactOwner + ", execMatches=" + execMatches
					+ ", outputMatches=" + outputMatches + ", fTypeMatches=" + fTypeMatches
					+ ", plan=[hop=" + plan.getHopID() + ",exec=" + plan.getExecType()
					+ ",output=" + plan.getFedOutType() + ",fType=" + plan.getFType() + "]"
					+ ", selected=" + exact.normalizedSignature() + "@"
					+ Integer.toHexString(System.identityHashCode(exact))
					+ ", legal=" + node.legalAlternatives().stream()
						.map(state -> state.normalizedSignature() + "@"
							+ Integer.toHexString(System.identityHashCode(state))).toList());
		}
	}

	public void addFedPlanVariants(long hopID, FederatedOutput fedOutType, FedPlanVariants fedPlanVariants) {
		hopMemoTable.put(new ImmutablePair<>(hopID, fedOutType), fedPlanVariants);
	}

	void removeFedPlanVariantsForCarrier(Hop carrier) {
		Objects.requireNonNull(carrier, "carrier");
		for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
			Pair<Long,FederatedOutput> coordinate = Pair.of(carrier.getHopID(), output);
			FedPlanVariants existing = hopMemoTable.get(coordinate);
			Map<FederatedOutput,FedPlanVariants> exact = exactMemoByCarrier.get(carrier);
			if(exact != null) {
				exact.remove(output);
				if(exact.isEmpty())
					exactMemoByCarrier.remove(carrier);
			}
			Set<Hop> carriers = carriersByLegacyCoordinate.get(coordinate);
			if(carriers != null) {
				carriers.remove(carrier);
				if(carriers.isEmpty()) {
					carriersByLegacyCoordinate.remove(coordinate);
					hopMemoTable.remove(coordinate);
				}
				else if(existing != null && existing.hopCommon != null
					&& existing.hopCommon.getHopRef() == carrier) {
					Hop replacement = carriers.iterator().next();
					hopMemoTable.put(coordinate, getFedPlanVariants(replacement, output));
				}
			}
			else if(existing != null && existing.hopCommon != null
				&& existing.hopCommon.getHopRef() == carrier)
				hopMemoTable.remove(coordinate);
		}
	}

	public FedPlanVariants getFedPlanVariants(Pair<Long, FederatedOutput> fedPlanPair) {
		Set<Hop> carriers = carriersByLegacyCoordinate.get(fedPlanPair);
		if(carriers != null && carriers.size() > 1)
			throw new IllegalStateException("Ambiguous legacy DP memo coordinate " + fedPlanPair
				+ " spans " + carriers.size() + " occurrence carriers");
		return hopMemoTable.get(fedPlanPair);
	}

	public FedPlanVariants getFedPlanVariants(Hop carrier, FederatedOutput output) {
		Map<FederatedOutput,FedPlanVariants> byOutput = exactMemoByCarrier.get(carrier);
		return byOutput == null ? null : byOutput.get(output);
	}

	public FedPlan getFedPlanAfterPrune(long hopID, FederatedOutput federatedOutput) {
		FedPlanVariants fedPlanVariantList = getFedPlanVariants(
			new ImmutablePair<>(hopID, federatedOutput));
		if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
			return null;
		}
		return selectPrimaryVariantAfterPrune(fedPlanVariantList);
	}

	public FedPlan getFedPlanAfterPrune(Pair<Long, FederatedOutput> fedPlanPair) {
		FedPlanVariants fedPlanVariantList = getFedPlanVariants(fedPlanPair);
		if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
			return null;
		}
		return selectPrimaryVariantAfterPrune(fedPlanVariantList);
	}

	public FedPlan getFedPlanAfterPrune(Hop carrier, FederatedOutput output) {
		FedPlanVariants variants = getFedPlanVariants(carrier, output);
		return variants == null || variants.isEmpty() ? null : selectPrimaryVariantAfterPrune(variants);
	}

	/**
	 * Resolve a child arm from its exact analysis occurrence when the concrete compiled
	 * carrier itself has no retained arm. Recompile/unrolled carriers are alternative
	 * physical representations of that same occurrence and must not be lost through a
	 * legacy Hop-ID lookup. Legacy unbound memo tables retain their original ID behavior.
	 */
	public FedPlan getFedPlanAfterPruneForOccurrence(Hop carrier, FederatedOutput output) {
		Objects.requireNonNull(carrier, "carrier");
		Objects.requireNonNull(output, "output");
		FedPlan exact = getFedPlanAfterPrune(carrier, output);
		if(exact != null)
			return exact;
		if(analysis == null)
			return getFedPlanAfterPrune(carrier.getHopID(), output);
		HopOccurrenceProjection occurrence = occurrenceByPlanCarrier.get(carrier);
		if(occurrence == null)
			occurrence = requireOccurrence(carrier);
		return getFedPlanAfterPrune(occurrence, output);
	}

	public FedPlan getFedPlanAfterPrune(HopOccurrenceProjection occurrence, FederatedOutput federatedOutput) {
		assertOwnedOccurrence(occurrence);
		FedPlan best = null;
		boolean hasExplicitCarrier = false;
		for(Map.Entry<Hop, HopOccurrenceProjection> entry : occurrenceByPlanCarrier.entrySet()) {
			if(entry.getValue() != occurrence)
				continue;
			hasExplicitCarrier = true;
			FedPlan candidate = getFedPlanAfterPrune(entry.getKey(), federatedOutput);
			if(candidate != null && (best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
				|| candidate.getCumulativeCost() == best.getCumulativeCost()
					&& candidate.getHopID() < best.getHopID()))
				best = candidate;
		}
		return hasExplicitCarrier ? best : getFedPlanAfterPrune(occurrence.hop().getHopID(), federatedOutput);
	}

	public List<FedPlan> getExactPlansAfterPrune(HopOccurrenceProjection occurrence,
		FederatedOutput federatedOutput) {
		assertOwnedOccurrence(occurrence);
		List<FedPlan> plans = new ArrayList<>();
		Set<FedPlan> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(OccurrencePlanArm arm : getAllExactPlanVariantsForOccurrence(occurrence))
			if(arm.output() == federatedOutput && seen.add(arm.plan()))
				plans.add(arm.plan());
		plans.sort(Comparator.comparingDouble(FedPlan::getCumulativeCost)
			.thenComparingLong(FedPlan::getHopID));
		return List.copyOf(plans);
	}

	public Hop resolveExecutableHop(HopOccurrenceProjection occurrence) {
		assertOwnedOccurrence(occurrence);
		return occurrence.hop();
	}

	public HopOccurrenceProjection requireOccurrence(Hop hop) {
		if(hop == null)
			throw new IllegalArgumentException("Hop must not be null");
		HopOccurrenceProjection match = null;
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			if(occurrence.hop() != hop)
				continue;
			if(match != null)
				throw new IllegalArgumentException("Hop has multiple analysis occurrences");
			match = occurrence;
		}
		if(match == null)
			throw new IllegalArgumentException("Hop is not owned by the memo analysis");
		return match;
	}

	public HopOccurrenceProjection requirePlanCarrierOccurrence(Hop hop) {
		HopOccurrenceProjection occurrence = occurrenceByPlanCarrier.get(hop);
		return occurrence != null ? occurrence : requireOccurrence(hop);
	}

	/** Immutable exact plan arm retained for one analysis-owned occurrence. */
	public record OccurrencePlanArm(HopOccurrenceProjection occurrence, Hop carrier,
		FederatedOutput output, FedPlan plan) {
		public OccurrencePlanArm {
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(carrier, "carrier");
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(plan, "plan");
			if(plan.getHopRef() != carrier || plan.getHopID() != carrier.getHopID()
				|| plan.getFedOutType() != output)
				throw new IllegalArgumentException("Occurrence plan arm identity differs");
		}
	}

	/** Returns the canonically ordered pruned LOUT/FOUT arms for one exact occurrence. */
	public List<OccurrencePlanArm> getExactPlanArmsForOccurrence(HopOccurrenceProjection occurrence) {
		assertOwnedOccurrence(occurrence);
		List<Hop> carriers = occurrenceByPlanCarrier.entrySet().stream()
			.filter(entry -> entry.getValue() == occurrence).map(Map.Entry::getKey)
			.sorted(Comparator.comparingLong(Hop::getHopID)).toList();
		List<OccurrencePlanArm> arms = new ArrayList<>(Math.max(2, carriers.size() * 2));
		Set<FedPlan> seenPlans = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop carrier : carriers)
			for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
				FedPlan plan = getFedPlanAfterPrune(carrier, output);
				if(plan == null || !seenPlans.add(plan))
					continue;
				if(plan.getHopRef() != carrier || requirePlanCarrierOccurrence(plan.getHopRef()) != occurrence)
					throw new IllegalStateException("Foreign exact memo carrier for " + occurrence.key());
				arms.add(new OccurrencePlanArm(occurrence, carrier, output, plan));
			}
		return List.copyOf(arms);
	}

	/** Returns every retained plan variant for one exact occurrence in canonical carrier/output order. */
	public List<OccurrencePlanArm> getAllExactPlanVariantsForOccurrence(HopOccurrenceProjection occurrence) {
		assertOwnedOccurrence(occurrence);
		List<Hop> carriers = occurrenceByPlanCarrier.entrySet().stream()
			.filter(entry -> entry.getValue() == occurrence).map(Map.Entry::getKey)
			.sorted(Comparator.comparingLong(Hop::getHopID)).toList();
		List<OccurrencePlanArm> arms = new ArrayList<>();
		Set<FedPlan> seenPlans = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop carrier : carriers)
			for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
				FedPlanVariants variants = getFedPlanVariants(carrier, output);
				if(variants == null || variants.hopCommon == null
					|| variants.hopCommon.getHopRef() != carrier)
					continue;
				for(FedPlan plan : variants.getFedPlanVariants()) {
					if(plan == null || !seenPlans.add(plan))
						continue;
					if(plan.getHopRef() != carrier || requirePlanCarrierOccurrence(plan.getHopRef()) != occurrence)
						throw new IllegalStateException("Foreign exact memo variant for " + occurrence.key());
					arms.add(new OccurrencePlanArm(occurrence, carrier, output, plan));
				}
			}
		return List.copyOf(arms);
	}

	/**
	 * Canonical semantic snapshot of the complete exact DP frontier.
	 *
	 * <p>The production rewire graph can contain loop-carried TRead/TWrite cycles.
	 * A single depth-first enumeration pass is then only a seed: an early TRead can
	 * observe the previous pass' TWrite frontier while a later TWrite already observes
	 * the new TRead frontier.  Comparing object identities cannot detect convergence
	 * because every closure pass allocates fresh {@link FedPlan} objects.  This method
	 * therefore records every execution-relevant field using exact occurrence authority,
	 * placement/candidate/relocation receipts, ordered child boundaries, and exact cost
	 * bits.  It deliberately excludes Java object identity while retaining carrier IDs,
	 * so semantically identical recomputation is stable but a changed executable carrier
	 * or cost is not.</p>
	 */
	String exactSemanticFrontierFingerprint() {
		if(analysis == null)
			throw new IllegalStateException("Memo is not bound to a placement analysis");
		StringBuilder fingerprint = new StringBuilder();
		for(HopOccurrenceProjection occurrence : analysis.occurrences().stream()
			.sorted(Comparator.comparing(HopOccurrenceProjection::key)).toList()) {
			fingerprint.append("occ=").append(occurrence.key().normalizedSignature()).append('{');
			List<String> arms = getAllExactPlanVariantsForOccurrence(occurrence).stream()
				.map(FederatedPlannerDpMemoTable::exactSemanticArmSignature)
				.distinct().sorted().toList();
			for(String arm : arms)
				fingerprint.append(arm).append(';');
			fingerprint.append('}');
		}
		return fingerprint.toString();
	}

	void assertNoExactFrontierSeeds() {
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			for(OccurrencePlanArm arm : getAllExactPlanVariantsForOccurrence(occurrence))
				if(arm.plan().isExactFrontierSeed())
					throw new IllegalStateException("Unresolved exact DP frontier seed for "
						+ occurrence.key().normalizedSignature() + " carrier="
						+ arm.carrier().getHopID() + " output=" + arm.output());
	}

	boolean pruneExactFedPlanVariants(HopOccurrenceProjection occurrence,
		FedPlanVariants variants) {
		assertOwnedOccurrence(occurrence);
		Objects.requireNonNull(variants, "variants");
		return variants.pruneFedPlans();
	}

	private static String exactSemanticArmSignature(OccurrencePlanArm arm) {
		FedPlan plan = arm.plan();
		PlacementState state = plan.getSelectedPlacementState();
		StringBuilder signature = new StringBuilder()
			.append("carrier=").append(arm.carrier().getHopID())
			.append("|output=").append(arm.output())
			.append("|seed=").append(plan.isExactFrontierSeed())
			.append("|exec=").append(plan.getExecType())
			.append("|ftype=").append(plan.getFType())
			.append("|cpFout=").append(plan.getCpFoutType())
			.append("|state=").append(state == null ? "null" : state.normalizedSignature())
			.append("|derived=").append(plan.isDerivedFedFout())
			.append("|materialized=").append(plan.isFoutMaterializationAccounted())
			.append("|cumulative=").append(Double.toHexString(plan.getCumulativeCost()))
			.append("|self=").append(Double.toHexString(plan.getSelfCost()))
			.append("|forwarding=").append(Double.toHexString(plan.getForwardingCost()));
		if(plan.hasExactRecurrenceCosts())
			signature.append("|recurrence=")
				.append(Double.toHexString(plan.getEmbeddedChildRecurrenceCost())).append('/')
				.append(Double.toHexString(plan.getPhysicalChildBoundaryCost()));
		else
			signature.append("|recurrence=absent");
		CandidateSelectionReceipt candidate = plan.getDirectCandidateSelection();
		signature.append("|candidate=")
			.append(candidate == null ? "null" : candidate.normalizedSignature());
		for(RelocationChoiceReceipt relocation : plan.getDirectRelocationChoices())
			signature.append("|relocation=").append(relocation.normalizedSignature());
		for(Map.Entry<RelocationActionKey,Double> cost : plan.getDirectRelocationActionCosts().entrySet())
			signature.append("|relocationCost=").append(cost.getKey().normalizedSignature())
				.append('@').append(Double.toHexString(cost.getValue()));
		for(FedPlan.ExactChildPlanEdge edge : plan.getExactChildPlanEdges()) {
			FedPlan child = edge.selectedPlan();
			PlacementState childState = child.getSelectedPlacementState();
			CandidateSelectionReceipt childCandidate = child.getDirectCandidateSelection();
			signature.append("|child=").append(edge.occurrence().normalizedSignature())
				.append('@').append(edge.carrier().getHopID())
				.append('/').append(edge.output())
				.append('/').append(childState == null ? "null" : childState.normalizedSignature())
				.append("/derived=").append(child.isDerivedFedFout())
				.append("/cost=").append(Double.toHexString(child.getCumulativeCost()))
				.append("/candidate=")
				.append(childCandidate == null ? "null" : childCandidate.normalizedSignature());
		}
		return signature.toString();
	}

	/** Selects the minimum-cost memo arm for an exact neutral occurrence, including disconnected regions. */
	public FedPlan getCheapestPlanForOccurrence(HopOccurrenceProjection occurrence) {
		FedPlan best = null;
		for(OccurrencePlanArm arm : getExactPlanArmsForOccurrence(occurrence)) {
			FedPlan candidate = arm.plan();
			if(best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
				|| candidate.getCumulativeCost() == best.getCumulativeCost()
					&& candidate.getFedOutType() == FederatedOutput.LOUT
					&& best.getFedOutType() == FederatedOutput.FOUT)
				best = candidate;
		}
		return best;
	}

	public String describePlanCarriers(HopOccurrenceProjection occurrence) {
		List<String> descriptions = new ArrayList<>();
		for(Map.Entry<Hop, HopOccurrenceProjection> entry : occurrenceByPlanCarrier.entrySet())
			if(entry.getValue() == occurrence)
				descriptions.add(entry.getKey().getHopID() + ":" + entry.getKey().getOpString());
		return descriptions.toString();
	}

	/**
	 * Returns every legacy output-decision coordinate that can select a carrier for
	 * one exact compiled occurrence. Recompile/unrolled carriers can resolve to
	 * different original Hop IDs even though rewrite emits exactly one runtime value
	 * for the occurrence, so exact-occurrence closure must update these coordinates
	 * atomically rather than moving the conflict from one carrier to another.
	 */
	public List<Long> getOriginalDecisionHopIdsForOccurrence(HopOccurrenceProjection occurrence) {
		assertOwnedOccurrence(occurrence);
		java.util.SortedSet<Long> decisionHopIDs = new java.util.TreeSet<>();
		decisionHopIDs.add(resolveOriginalHopId(occurrence.hop().getHopID()));
		for(Map.Entry<Hop, HopOccurrenceProjection> entry : occurrenceByPlanCarrier.entrySet())
			if(entry.getValue() == occurrence)
				decisionHopIDs.add(resolveOriginalHopId(entry.getKey().getHopID()));
		return List.copyOf(decisionHopIDs);
	}

	/** Exact retained DP dependency between two analysis-owned occurrences. */
	public record OccurrencePlanEdge(HopOccurrenceProjection producer,
		HopOccurrenceProjection consumer) {
		public OccurrencePlanEdge {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
		}
	}

	/**
	 * Canonical intersection of the exact child edges present in every retained DP
	 * plan arm for an occurrence.
	 * The compiled Hop graph can contain construction-only inputs which deliberately
	 * are not part of a planner arm (for example the local-matrix argument of a
	 * literal FEDERATED source whose runtime FederationMap is represented as a fixed
	 * leaf).  Conversely, rewire/loop plans can contain exact logical child edges not
	 * present in the raw compiled-input graph.  Component closure must therefore use
	 * this executable plan graph rather than approximating it with raw Hop edges.
	 * Optional child edges must not merge traversal-owner components: one selected
	 * arm can omit them, in which case no component root could cover the merged node.
	 */
	public List<OccurrencePlanEdge> mandatoryExactPlanChildEdges() {
		List<OccurrencePlanEdge> edges = new ArrayList<>();
		List<HopOccurrenceProjection> consumers = occurrenceByPlanCarrier.values().stream()
			.distinct().sorted(Comparator.comparing(HopOccurrenceProjection::key)).toList();
		for(HopOccurrenceProjection consumer : consumers) {
			Set<HopOccurrenceProjection> mandatory = null;
			for(OccurrencePlanArm arm : getAllExactPlanVariantsForOccurrence(consumer)) {
				Set<HopOccurrenceProjection> producers =
					Collections.newSetFromMap(new IdentityHashMap<>());
				for(Pair<Long,FederatedOutput> child : arm.plan().getChildFedPlans()) {
					FedPlanVariants childVariants = hopMemoTable.get(child);
					Hop childCarrier = childVariants == null || childVariants.hopCommon == null
						? null : childVariants.hopCommon.getHopRef();
					HopOccurrenceProjection producer = childCarrier == null
						? null : occurrenceByPlanCarrier.get(childCarrier);
					if(producer == null)
						throw new IllegalStateException("Retained DP plan has no exact child occurrence: consumer="
							+ consumer.key() + " child=" + child);
					producers.add(producer);
				}
				if(mandatory == null) {
					mandatory = Collections.newSetFromMap(new IdentityHashMap<>());
					mandatory.addAll(producers);
				}
				else
					mandatory.retainAll(producers);
			}
			if(mandatory != null)
				for(HopOccurrenceProjection producer : mandatory)
					edges.add(new OccurrencePlanEdge(producer, consumer));
		}
		edges.sort(Comparator.comparing((OccurrencePlanEdge edge) -> edge.producer().key())
			.thenComparing(edge -> edge.consumer().key()));
		return List.copyOf(edges);
	}

	private void assertOwnedOccurrence(HopOccurrenceProjection occurrence) {
		if(analysis == null)
			throw new IllegalStateException("Memo is not bound to a placement analysis");
		if(occurrence == null || !ownedOccurrences.contains(occurrence)
			|| analysis.hop(occurrence.key()).orElse(null) != occurrence.hop())
			throw new IllegalArgumentException("Occurrence is not owned by the memo analysis");
	}

	private FedPlan selectPrimaryVariantAfterPrune(FedPlanVariants fedPlanVariantList) {
		if (fedPlanVariantList == null || fedPlanVariantList._fedPlanVariants == null
				|| fedPlanVariantList._fedPlanVariants.isEmpty()) {
			return null;
		}

		FedPlan preferredConcreteSourceTransientReadPlan =
			selectConcreteSourceTransientReadFoutPrimary(fedPlanVariantList);
		return preferredConcreteSourceTransientReadPlan != null
			? preferredConcreteSourceTransientReadPlan
			: fedPlanVariantList._fedPlanVariants.get(0);
	}

	private FedPlan selectConcreteSourceTransientReadFoutPrimary(FedPlanVariants fedPlanVariantList) {
		if (fedPlanVariantList == null || fedPlanVariantList.getFedOutType() != FederatedOutput.FOUT
				|| fedPlanVariantList.hopCommon == null
				|| !(fedPlanVariantList.hopCommon.getHopRef() instanceof DataOp)) {
			return null;
		}

		DataOp transientRead = (DataOp) fedPlanVariantList.hopCommon.getHopRef();
		if (transientRead.getOp() != Types.OpOpData.TRANSIENTREAD)
			return null;

		List<Hop> sourceHops = collectSourceHopsForPrimarySelection(fedPlanVariantList);
		if (!FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(transientRead, sourceHops))
			return null;

		for (FedPlan candidate : fedPlanVariantList._fedPlanVariants) {
			if (candidate == null || candidate.getExecType() != ExecType.FED)
				continue;
			FType candidateFType = candidate.getFType();
			if (candidateFType == null || candidateFType == FType.BROADCAST)
				continue;
			if (!preservesConcreteSourceOnChildEdge(candidate))
				continue;
			return candidate;
		}
		return null;
	}

	private List<Hop> collectSourceHopsForPrimarySelection(FedPlanVariants fedPlanVariantList) {
		if (fedPlanVariantList == null || fedPlanVariantList._fedPlanVariants == null
				|| fedPlanVariantList._fedPlanVariants.isEmpty()) {
			return Collections.emptyList();
		}

		LinkedHashSet<Long> seenSourceIds = new LinkedHashSet<>();
		List<Hop> sourceHops = new ArrayList<>();
		for (FedPlan candidate : fedPlanVariantList._fedPlanVariants) {
			if (candidate == null || candidate.getChildFedPlans() == null)
				continue;
			for (Pair<Long, FederatedOutput> childEdge : candidate.getChildFedPlans()) {
				if (childEdge == null)
					continue;
				long sourceOrigId = resolveOriginalHopId(childEdge.getKey());
				if (!seenSourceIds.add(sourceOrigId))
					continue;
				Hop sourceHop = resolveOriginalHop(sourceOrigId);
				if (sourceHop != null)
					sourceHops.add(sourceHop);
			}
		}
		return sourceHops;
	}

	private static boolean preservesConcreteSourceOnChildEdge(FedPlan candidate) {
		if (candidate == null)
			return false;
		List<Pair<Long, FederatedOutput>> childEdges = candidate.getChildFedPlans();
		if (childEdges == null || childEdges.isEmpty())
			return true;
		for (Pair<Long, FederatedOutput> childEdge : childEdges) {
			if (childEdge != null && childEdge.getRight() == FederatedOutput.FOUT)
				return true;
		}
		return false;
	}

	public boolean contains(long hopID, FederatedOutput fedOutType) {
		return hopMemoTable.containsKey(new ImmutablePair<>(hopID, fedOutType));
	}

	public boolean containsPlanForCarrier(Hop carrier, FederatedOutput fedOutType) {
		return carrier != null && getFedPlanVariants(carrier, fedOutType) != null;
	}

	public void registerHopRefs(Map<Long, HopCommon> hopCommonTable) {
		if (hopCommonTable == null)
			return;
		for (Map.Entry<Long, HopCommon> entry : hopCommonTable.entrySet()) {
			HopCommon hc = entry.getValue();
			if (hc != null && hc.getHopRef() != null)
				hopRefMap.put(entry.getKey(), hc.getHopRef());
		}
	}

	public void registerHopRefs(RewireOccurrenceSnapshot snapshot, Map<Long, HopCommon> hopCommonTable) {
		assertOwnedSnapshot(snapshot);
		if(hopCommonTable == null)
			return;
		for(Map.Entry<Long, HopCommon> entry : hopCommonTable.entrySet()) {
			HopCommon common = entry.getValue();
			Hop carrier = common == null ? null : common.getHopRef();
			HopOccurrenceProjection occurrence = snapshot.projectExactCarrier(carrier);
			if(carrier == null || occurrence == null || entry.getKey() != carrier.getHopID())
				throw new IllegalArgumentException("Rewire Hop reference is not owned by the supplied snapshot");
			hopRefMap.put(entry.getKey(), carrier);
			occurrenceByPlanCarrier.put(carrier, occurrence);
		}
	}

	public void registerCloneMapping(Map<Long, Long> cloneToOrigMap) {
		if (cloneToOrigMap == null || cloneToOrigMap.isEmpty())
			return;
		cloneToOrig.putAll(cloneToOrigMap);
	}

	public void registerCloneMapping(RewireOccurrenceSnapshot snapshot) {
		assertOwnedSnapshot(snapshot);
		cloneToOrig.putAll(snapshot.cloneToOriginal());
	}

	public void registerAdditionalRootHopIDs(List<Hop> roots) {
		if (roots == null || roots.isEmpty())
			return;
		for (Hop root : roots) {
			if (root != null)
				additionalRootHopIDs.add(root.getHopID());
		}
	}

	public void registerAdditionalRootHopIDs(RewireOccurrenceSnapshot snapshot, List<Hop> roots) {
		assertOwnedSnapshot(snapshot);
		if(roots == null || roots.isEmpty())
			return;
		for(Hop root : roots) {
			if(snapshot.projectExactCarrier(root) == null)
				throw new IllegalArgumentException("Additional root is not owned by the supplied snapshot");
			additionalRootHopIDs.add(root.getHopID());
		}
	}

	public Set<Long> getAdditionalRootHopIDs() {
		return Collections.unmodifiableSet(additionalRootHopIDs);
	}

	public void registerDeadFunctionOutputHopIDs(Set<Long> deadOutputHopIDs) {
		if (deadOutputHopIDs == null || deadOutputHopIDs.isEmpty())
			return;
		for (Long hopID : deadOutputHopIDs) {
			if (hopID != null && hopID >= 0)
				deadFunctionOutputHopIDs.add(hopID);
		}
	}

	public void registerDeadFunctionOutputHopIDs(RewireOccurrenceSnapshot snapshot, Set<Long> deadOutputHopIDs) {
		assertOwnedSnapshot(snapshot);
		if(deadOutputHopIDs == null || deadOutputHopIDs.isEmpty())
			return;
		for(Long hopID : deadOutputHopIDs)
			if(hopID != null && hopID >= 0)
				deadFunctionOutputHopIDs.add(hopID);
	}

	private void assertOwnedSnapshot(RewireOccurrenceSnapshot snapshot) {
		if(analysis == null)
			throw new IllegalStateException("Memo is not bound to a placement analysis");
		if(snapshot == null || snapshot.analysis() != analysis
			|| !analysis.analysisFingerprint().equals(snapshot.analysisFingerprint()))
			throw new IllegalArgumentException("Rewire snapshot is not owned by the memo analysis");
	}

	public boolean isDeadFunctionOutputHop(long hopID) {
		return hopID >= 0 && deadFunctionOutputHopIDs.contains(hopID);
	}

	public List<Long> collectTransientReadSiblingHopIDs(long producerOrigHopId, String transientVarName) {
		if (producerOrigHopId < 0)
			return Collections.emptyList();
		LinkedHashMap<Long, Long> siblingHopIds = new LinkedHashMap<>();
		for (Map.Entry<Pair<Long, FederatedOutput>, FedPlanVariants> entry : hopMemoTable.entrySet()) {
			if (entry == null || entry.getKey() == null)
				continue;
			long hopID = entry.getKey().getLeft();
			long hopOrigID = resolveOriginalHopId(hopID);
			if (siblingHopIds.containsKey(hopOrigID))
				continue;
			Hop hopRef = resolveOriginalHop(hopID);
			if (!(hopRef instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hopRef;
			if (dataOp.getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;
			if (transientVarName != null && dataOp.getName() != null && !transientVarName.equals(dataOp.getName()))
				continue;
			FedPlanVariants variants = entry.getValue();
			if (variants == null || variants.getFedPlanVariants() == null)
				continue;
			boolean readsFromProducer = false;
			for (FedPlan candidate : variants.getFedPlanVariants()) {
				if (candidate == null || candidate.getChildFedPlans() == null)
					continue;
				for (Pair<Long, FederatedOutput> childEdge : candidate.getChildFedPlans()) {
					if (childEdge == null)
						continue;
					if (resolveOriginalHopId(childEdge.getKey()) == producerOrigHopId) {
						readsFromProducer = true;
						break;
					}
				}
				if (readsFromProducer)
					break;
			}
			if (readsFromProducer)
				siblingHopIds.put(hopOrigID, hopID);
		}
		return new ArrayList<>(siblingHopIds.values());
	}

	public void setNumWorkers(int numWorkers) {
		_numWorkers = Math.max(1, numWorkers);
	}

	public int getNumWorkers() {
		return Math.max(1, _numWorkers);
	}

	public long resolveOriginalHopId(long hopId) {
		Long orig = cloneToOrig.get(hopId);
		return orig != null ? orig : hopId;
	}

	/**
	 * @return true if this hop id belongs to a planning-time virtual clone
	 * (e.g., loop-unrolled iter1 clone) rather than an original executable hop.
	 */
	public boolean isVirtualClone(long hopId) {
		return cloneToOrig.containsKey(hopId);
	}

	public Hop resolveOriginalHop(long hopId) {
		long origId = resolveOriginalHopId(hopId);
		Hop hop = hopRefMap.get(origId);
		if (hop != null)
			return hop;
		return hopRefMap.get(hopId);
	}

	/**
	 * Represents a single federated execution plan with its associated costs and
	 * dependencies.
	 * This class contains:
	 * 1. selfCost: Cost of the current hop (computation + input/output memory
	 * access).
	 * 2. cumulativeCost: Total cost including this plan's selfCost and all child
	 * plans' cumulativeCost.
	 * 3. forwardingCost: Network transfer cost for this plan to the parent plan.
	 * 
	 * FedPlan is linked to FedPlanVariants, which in turn uses HopCommon to manage
	 * common properties and costs.
	 */
		public static class FedPlan {
			public record ExactChildPlanEdge(CompiledHopKey occurrence, Hop carrier,
				FederatedOutput output, FedPlan selectedPlan) {
				public ExactChildPlanEdge {
					Objects.requireNonNull(occurrence, "occurrence");
					Objects.requireNonNull(carrier, "carrier");
					Objects.requireNonNull(output, "output");
					Objects.requireNonNull(selectedPlan, "selectedPlan");
				}
			}

			private double cumulativeCost; // Total cost = sum of selfCost + cumulativeCost of child plans
			private final FedPlanVariants fedPlanVariants; // Reference to variant list
			private final List<Pair<Long, FederatedOutput>> childFedPlans; // Child plan references
			private List<ExactChildPlanEdge> exactChildPlanEdges = List.of();
				private ExecType execType;
				private FType fType;
				private FType cpFoutType;
				private boolean derivedFedFout;
				private PlacementState selectedPlacementState;
				private boolean foutMaterializationAccounted;
				private CandidateSelectionReceipt directCandidateSelection;
				private List<RelocationChoiceReceipt> directRelocationChoices = List.of();
				private Map<RelocationActionKey,Double> directRelocationActionCosts = Map.of();
				private double embeddedChildRecurrenceCost = Double.NaN;
				private double physicalChildBoundaryCost = Double.NaN;
				private boolean exactFrontierSeed;

		public FedPlan(double cumulativeCost, FedPlanVariants fedPlanVariants,
				List<Pair<Long, FederatedOutput>> childFedPlans) {
			this.cumulativeCost = cumulativeCost;
			this.fedPlanVariants = fedPlanVariants;
			this.childFedPlans = childFedPlans;
		}

		public Hop getHopRef() {
			return fedPlanVariants.hopCommon.getHopRef();
		}

		public long getHopID() {
			return fedPlanVariants.hopCommon.getHopRef().getHopID();
		}

		public FederatedOutput getFedOutType() {
			return fedPlanVariants.getFedOutType();
		}

		public double getCumulativeCost() {
			return cumulativeCost;
		}

		void markExactFrontierSeed() {
			exactFrontierSeed = true;
		}

		boolean isExactFrontierSeed() {
			return exactFrontierSeed;
		}

		public double getCumulativeCostPerParents() {
			double cumulativeCostPerParents = cumulativeCost;
			int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
			if (numOfParents >= 2) {
				cumulativeCostPerParents /= numOfParents;
			}
			return cumulativeCostPerParents;
		}

		public double getSelfCost() {
			return fedPlanVariants.hopCommon.getSelfCost();
		}

		public double getForwardingCost() {
			return fedPlanVariants.hopCommon.getForwardingCost();
		}

		public double getForwardingCostPerParents() {
			double forwardingCostPerParents = fedPlanVariants.hopCommon.getForwardingCost();
			int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
			if (numOfParents >= 2) {
				forwardingCostPerParents /= numOfParents;
			}
			return forwardingCostPerParents;
		}

		public void setExactRecurrenceCosts(double embeddedChildRecurrenceCost,
			double physicalChildBoundaryCost) {
			if(!Double.isFinite(embeddedChildRecurrenceCost) || embeddedChildRecurrenceCost < 0d
				|| !Double.isFinite(physicalChildBoundaryCost) || physicalChildBoundaryCost < 0d
				|| physicalChildBoundaryCost > embeddedChildRecurrenceCost + 1e-9)
				throw new IllegalArgumentException("Invalid exact DP recurrence costs: embedded="
					+ embeddedChildRecurrenceCost + " boundary=" + physicalChildBoundaryCost);
			this.embeddedChildRecurrenceCost = embeddedChildRecurrenceCost;
			this.physicalChildBoundaryCost = physicalChildBoundaryCost;
		}

		public boolean hasExactRecurrenceCosts() {
			return Double.isFinite(embeddedChildRecurrenceCost)
				&& Double.isFinite(physicalChildBoundaryCost);
		}

		public double getEmbeddedChildRecurrenceCost() {
			if(!hasExactRecurrenceCosts())
				throw new IllegalStateException("FedPlan has no captured exact recurrence costs");
			return embeddedChildRecurrenceCost;
		}

		public double getPhysicalChildBoundaryCost() {
			if(!hasExactRecurrenceCosts())
				throw new IllegalStateException("FedPlan has no captured exact recurrence costs");
			return physicalChildBoundaryCost;
		}

		public double getComputeWeight() {
			return fedPlanVariants.hopCommon.getComputeWeight();
		}

		public double getNetworkWeight() {
			return fedPlanVariants.hopCommon.getNetworkWeight();
		}

		public double getMultiplicity() {
			return fedPlanVariants.hopCommon.getMultiplicity();
		}

		public int getNumOfParents() {
			return fedPlanVariants.hopCommon.getNumOfParents();
		}

		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext,
				double childMultiplicity) {
			return fedPlanVariants.hopCommon.computeForwardingWeightOfChild(childLoopContext, childMultiplicity);
		}

		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
			return fedPlanVariants.hopCommon.computeForwardingWeightOfChild(childLoopContext);
		}

		public List<Pair<Long, Double>> getLoopContext() {
			return fedPlanVariants.hopCommon.getLoopContext();
		}

		public List<Pair<Long, FederatedOutput>> getChildFedPlans() {
			return childFedPlans;
		}

		public void bindExactChildPlanEdges(List<Hop> childCarriers,
			FederatedPlannerDpMemoTable memo) {
			Objects.requireNonNull(childCarriers, "childCarriers");
			Objects.requireNonNull(memo, "memo");
			if(childCarriers.size() != childFedPlans.size())
				throw new IllegalArgumentException("Exact DP child carrier count differs from child edge count");
			List<FedPlan> selectedPlans = new ArrayList<>(childFedPlans.size());
			for(int i = 0; i < childFedPlans.size(); i++) {
				Pair<Long,FederatedOutput> legacy = childFedPlans.get(i);
				Hop carrier = Objects.requireNonNull(childCarriers.get(i), "exact child carrier");
				FedPlan selected = memo.getFedPlanAfterPrune(carrier, legacy.getRight());
				if(selected == null)
					throw new IllegalArgumentException("Exact DP child carrier has no selected memo arm " + legacy);
				selectedPlans.add(selected);
			}
			bindExactChildPlanEdges(childCarriers, selectedPlans, memo);
		}

		public void bindExactChildPlanEdges(List<Hop> childCarriers,
			List<FedPlan> selectedPlans, FederatedPlannerDpMemoTable memo) {
			Objects.requireNonNull(childCarriers, "childCarriers");
			Objects.requireNonNull(selectedPlans, "selectedPlans");
			Objects.requireNonNull(memo, "memo");
			if(childCarriers.size() != childFedPlans.size()
				|| selectedPlans.size() != childFedPlans.size())
				throw new IllegalArgumentException("Exact DP child carrier count differs from child edge count");
			List<ExactChildPlanEdge> exact = new ArrayList<>(childFedPlans.size());
			for(int i = 0; i < childFedPlans.size(); i++) {
				Pair<Long,FederatedOutput> legacy = childFedPlans.get(i);
				Hop carrier = Objects.requireNonNull(childCarriers.get(i), "exact child carrier");
				FedPlan selected = Objects.requireNonNull(selectedPlans.get(i), "exact selected child plan");
				if(carrier.getHopID() != legacy.getLeft())
					throw new IllegalArgumentException("Exact DP child carrier differs from legacy edge " + legacy);
				HopOccurrenceProjection occurrence = memo.requirePlanCarrierOccurrence(carrier);
				FedPlanVariants retained = memo.getFedPlanVariants(carrier, legacy.getRight());
				if(selected.getHopRef() != carrier || selected.getFedOutType() != legacy.getRight()
					|| retained == null || retained.getFedPlanVariants().stream().noneMatch(plan -> plan == selected))
					throw new IllegalArgumentException("Exact DP child carrier has no memo arm " + legacy);
				exact.add(new ExactChildPlanEdge(occurrence.key(), carrier, legacy.getRight(), selected));
			}
			exactChildPlanEdges = List.copyOf(exact);
		}

		public List<ExactChildPlanEdge> getExactChildPlanEdges() {
			if(!childFedPlans.isEmpty() && exactChildPlanEdges.size() != childFedPlans.size())
				throw new IllegalStateException("DP plan lacks exact occurrence-aware child coordinates");
			return exactChildPlanEdges;
		}

			public void setFederatedOutput(FederatedOutput fedOutType) {
				fedPlanVariants.hopCommon.hopRef.setFederatedOutput(fedOutType);
			}

			public void setFederatedOutputDerived(boolean derived) {
				fedPlanVariants.hopCommon.hopRef.setFederatedOutputDerived(derived);
			}

		public void setForcedExecType(ExecType execType) {
			fedPlanVariants.hopCommon.hopRef.setForcedExecType(execType);
		}

		public ExecType getExecType() {
			return execType;
		}

		public void setExecType(ExecType execType) {
			this.execType = execType;
		}

			public FType getFType() {
				return fType;
			}

				public void setFType(FType fType) {
				this.fType = fType;
			}

			public FType getCpFoutType() {
				return cpFoutType;
			}

			public void setCpFoutType(FType cpFoutType) {
				this.cpFoutType = cpFoutType;
			}

			public FType getCpFoutTypeOrFType() {
				return cpFoutType != null ? cpFoutType : fType;
			}

					public boolean isDerivedFedFout() {
					return derivedFedFout;
				}

				public void setDerivedFedFout(boolean derivedFedFout) {
					this.derivedFedFout = derivedFedFout;
				}

				public PlacementState getSelectedPlacementState() {
					return selectedPlacementState;
				}

				public void setSelectedPlacementState(PlacementState selectedPlacementState) {
					this.selectedPlacementState = Objects.requireNonNull(selectedPlacementState,
						"selectedPlacementState");
				}

				public boolean isFoutMaterializationAccounted() {
					return foutMaterializationAccounted;
				}

				public void setFoutMaterializationAccounted(boolean foutMaterializationAccounted) {
					this.foutMaterializationAccounted = foutMaterializationAccounted;
				}

				public CandidateSelectionReceipt getDirectCandidateSelection() {
					return directCandidateSelection;
				}

				public void setDirectCandidateSelection(CandidateSelectionReceipt candidateSelection) {
					directCandidateSelection = Objects.requireNonNull(candidateSelection, "candidateSelection");
					if(selectedPlacementState != null && !candidateSelection.emission().emissionState()
						.placementState().equals(selectedPlacementState))
						throw new IllegalArgumentException("DP candidate selection placement differs from FedPlan state");
				}

				public List<RelocationChoiceReceipt> getDirectRelocationChoices() {
					return directRelocationChoices;
				}

				public void setDirectRelocationChoices(Collection<RelocationChoiceReceipt> choices) {
					List<RelocationChoiceReceipt> ordered = Objects.requireNonNull(choices, "choices")
						.stream().map(choice -> Objects.requireNonNull(choice, "choice")).sorted().toList();
					Set<RelocationDemandKey> demands = new HashSet<>();
					for(RelocationChoiceReceipt choice : ordered)
						if(!demands.add(choice.demand()))
							throw new IllegalArgumentException("DP FedPlan has duplicate exact relocation demand: "
								+ choice.demand().normalizedSignature());
					directRelocationChoices = List.copyOf(ordered);
				}

				public Map<RelocationActionKey,Double> getDirectRelocationActionCosts() {
					return directRelocationActionCosts;
				}

				public void setDirectRelocationActionCosts(Map<RelocationActionKey,Double> costs) {
					Set<RelocationActionKey> selectedActions = directRelocationChoices.stream()
						.map(RelocationChoiceReceipt::action).collect(java.util.stream.Collectors.toSet());
					Map<RelocationActionKey,Double> ordered = new LinkedHashMap<>();
					Objects.requireNonNull(costs, "costs").entrySet().stream()
						.sorted(Map.Entry.comparingByKey()).forEach(entry -> {
							RelocationActionKey action = Objects.requireNonNull(entry.getKey(), "action");
							Double cost = Objects.requireNonNull(entry.getValue(), "cost");
							if(!selectedActions.contains(action))
								throw new IllegalArgumentException("DP relocation action cost is not selected: "
									+ action.normalizedSignature());
							if(!Double.isFinite(cost) || cost < 0.0)
								throw new IllegalArgumentException("DP relocation action cost must be finite and non-negative");
							ordered.put(action, cost);
						});
					directRelocationActionCosts = Collections.unmodifiableMap(ordered);
				}
			}

	/**
	 * Represents a collection of federated execution plan variants for a specific
	 * Hop and FederatedOutput.
	 * This class contains cost information and references to the associated plans.
	 * It uses HopCommon to store common properties and costs related to the Hop.
	 */
		public static class FedPlanVariants {
		/**
		 * Cost-independent authority exposed by one retained plan at a value boundary.
		 * Relocation costs themselves are deliberately absent: selected action ownership
		 * belongs to the signature, while cumulative cost chooses the representative.
		 */
		private record PlanBoundaryAuthority(PlacementState state, ExecType execType,
			FederatedOutput output, FType fType, FType cpFoutType, boolean derivedFedFout,
			boolean foutMaterializationAccounted, boolean exactFrontierSeed,
			boolean exactRecurrenceCaptured, CandidateSelectionReceipt candidate,
			List<RelocationChoiceReceipt> relocations,
			List<RelocationActionKey> costedRelocationActions) {
			private PlanBoundaryAuthority {
				relocations = List.copyOf(relocations);
				costedRelocationActions = List.copyOf(costedRelocationActions);
			}
		}

		/** Ordered child occurrence/value boundary consumed by the current plan. */
		private record ChildBoundaryAuthority(CompiledHopKey occurrence, Long legacyHopId,
			FederatedOutput output, PlanBoundaryAuthority selectedAuthority) {
			private ChildBoundaryAuthority {
				Objects.requireNonNull(output, "output");
				if((occurrence == null) == (legacyHopId == null))
					throw new IllegalArgumentException(
						"A DP child boundary requires exactly one exact or legacy occurrence identity");
				if(occurrence != null)
					Objects.requireNonNull(selectedAuthority, "selectedAuthority");
				else if(selectedAuthority != null)
					throw new IllegalArgumentException("A legacy DP child boundary has no exact authority");
			}
		}

		private record CompleteBoundarySignature(PlanBoundaryAuthority result,
			List<ChildBoundaryAuthority> orderedChildren) {
			private CompleteBoundarySignature {
				Objects.requireNonNull(result, "result");
				orderedChildren = List.copyOf(orderedChildren);
			}
		}

		protected HopCommon hopCommon; // Common properties and costs for the Hop
		private final FederatedOutput fedOutType; // Output type (FOUT/LOUT)
		protected List<FedPlan> _fedPlanVariants; // List of plan variants

		public FedPlanVariants(HopCommon hopCommon, FederatedOutput fedOutType) {
			this.hopCommon = hopCommon;
			this.fedOutType = fedOutType;
			this._fedPlanVariants = new ArrayList<>();
		}

		public boolean isEmpty() {
			return _fedPlanVariants.isEmpty();
		}

		public void addFedPlan(FedPlan fedPlan) {
			if (fedPlan.getExecType() == null) {
				throw new DMLRuntimeException("FedPlan missing execType for hop "
						+ fedPlan.getHopID() + " (" + fedPlan.getHopRef().getOpString() + "), fedOutType="
						+ fedPlan.getFedOutType());
			}
			_fedPlanVariants.add(fedPlan);
		}

		public List<FedPlan> getFedPlanVariants() {
			return _fedPlanVariants;
		}

		public FederatedOutput getFedOutType() {
			return fedOutType;
		}

		/**
		 * Retains the minimum cumulative-cost representative for every complete
		 * future-observable boundary signature. Plans with different signatures are
		 * incomparable and are all retained; there is no cardinality cap. Within one
		 * signature, later planning and lowering observe identical placement, child,
		 * candidate, and movement authority, so the higher-cost plan is dominated.
		 */
		public boolean pruneFedPlans() {
			if (_fedPlanVariants.isEmpty())
				return false;

			_fedPlanVariants.removeIf(p -> p == null || p.getExecType() == null);
			if (_fedPlanVariants.isEmpty())
				return false;

			_fedPlanVariants.sort(Comparator.comparingDouble(FedPlan::getCumulativeCost));
			Map<CompleteBoundarySignature,FedPlan> kept = new LinkedHashMap<>();
			for(FedPlan plan : _fedPlanVariants)
				kept.putIfAbsent(completeBoundarySignature(plan), plan);
			_fedPlanVariants.clear();
			_fedPlanVariants.addAll(kept.values());
			return true;
		}

		/**
		 * Applies the same lossless dominance rule to an already-factorized transient
		 * or shared-function frontier. There is intentionally no separate cap or
		 * coarser signature for these program structures.
		 */
		public boolean pruneExactBoundaryRepresentatives() {
			return pruneFedPlans();
		}

		private static CompleteBoundarySignature completeBoundarySignature(FedPlan plan) {
			List<ChildBoundaryAuthority> children = new ArrayList<>(plan.childFedPlans.size());
			if(!plan.exactChildPlanEdges.isEmpty()
				&& plan.exactChildPlanEdges.size() != plan.childFedPlans.size())
				throw new IllegalStateException("DP plan has a partial exact child boundary");
			if(plan.exactChildPlanEdges.size() == plan.childFedPlans.size()) {
				for(FedPlan.ExactChildPlanEdge edge : plan.exactChildPlanEdges)
					children.add(new ChildBoundaryAuthority(edge.occurrence(), null, edge.output(),
						planBoundaryAuthority(edge.selectedPlan())));
			}
			else {
				for(Pair<Long,FederatedOutput> edge : plan.childFedPlans) {
					Objects.requireNonNull(edge, "legacy child edge");
					children.add(new ChildBoundaryAuthority(null, edge.getLeft(), edge.getRight(), null));
				}
			}
			return new CompleteBoundarySignature(planBoundaryAuthority(plan), children);
		}

		private static PlanBoundaryAuthority planBoundaryAuthority(FedPlan plan) {
			List<RelocationActionKey> costedActions = plan.getDirectRelocationActionCosts().keySet()
				.stream().sorted().toList();
			return new PlanBoundaryAuthority(plan.getSelectedPlacementState(), plan.getExecType(),
				plan.getFedOutType(), plan.getFType(), plan.getCpFoutType(), plan.isDerivedFedFout(),
				plan.isFoutMaterializationAccounted(), plan.isExactFrontierSeed(),
				plan.hasExactRecurrenceCosts(), plan.getDirectCandidateSelection(),
				plan.getDirectRelocationChoices(), costedActions);
		}
	}

	/**
	 * Represents common properties and costs associated with a Hop.
	 * This class holds a reference to the Hop and tracks its execution and network
	 * forwarding (transfer) costs.
	 * It also maintains the loop context information to properly calculate
	 * forwarding costs within loops.
	 */
	public static class HopCommon {
		protected final Hop hopRef; // Reference to the associated Hop
		protected double selfCost; // Cost of the hop's computation and memory access
		protected double forwardingCost; // Cost of forwarding the hop's output to its parent
		protected int numOfParents;
		protected double computeWeight; // Weight used to calculate cost based on hop execution frequency
		protected double networkWeight; // Weight used to calculate cost based on hop execution frequency
		protected double multiplicity; // Execution multiplicity for unrolled loops
		protected List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists

		public HopCommon(Hop hopRef, double computeWeight, double networkWeight, double multiplicity, int numOfParents,
				List<Pair<Long, Double>> loopContext) {
			this.hopRef = hopRef;
			this.selfCost = 0;
			this.forwardingCost = 0;
			this.numOfParents = numOfParents;
			this.computeWeight = computeWeight;
			this.networkWeight = networkWeight;
			this.multiplicity = multiplicity;
			this.loopContext = loopContext != null ? new ArrayList<>(loopContext) : new ArrayList<>();
		}

		public Hop getHopRef() {
			return hopRef;
		}

		public double getSelfCost() {
			return selfCost;
		}

		public double getForwardingCost() {
			return forwardingCost;
		}

		public double getComputeWeight() {
			return computeWeight;
		}

		public double getNetworkWeight() {
			return networkWeight;
		}

		public double getMultiplicity() {
			return multiplicity;
		}

		public int getNumOfParents() {
			return numOfParents;
		}

		public List<Pair<Long, Double>> getLoopContext() {
			return loopContext;
		}

		protected void setSelfCost(double selfCost) {
			this.selfCost = selfCost;
		}

		protected void setForwardingCost(double forwardingCost) {
			this.forwardingCost = forwardingCost;
		}

		protected void setNumOfParentHops(int numOfParentHops) {
			this.numOfParents = numOfParentHops;
		}

		/**
		 * Estimates how many times this parent's output is forwarded to a child by
		 * amortizing the parent's networkWeight over loops the child does not execute.
		 *
		 * Example:
		 * parent loopContext = [(for1, 100), (while2, 10)]
		 * childLoopContext = [(for1, 100)]
		 * => forwardingWeight = networkWeight / 10 (child result reused across while2
		 * iterations)
		 */
		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext,
				double childMultiplicity) {
			return FederatedPlannerUtils.computeForwardingWeightOfChild(
					networkWeight, loopContext, childLoopContext, childMultiplicity);
		}

		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
			return computeForwardingWeightOfChild(childLoopContext, 1.0);
		}
	}
}

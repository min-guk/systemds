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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
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
	private final PlacementAnalysis analysis;
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
	}

	public FederatedPlannerDpMemoTable(PlacementAnalysis analysis) {
		this.analysis = java.util.Objects.requireNonNull(analysis, "analysis");
	}

	public PlacementAnalysis analysis() {
		return analysis;
	}

	public void addFedPlanVariants(HopOccurrenceProjection occurrence, FederatedOutput fedOutType,
		FedPlanVariants fedPlanVariants) {
		assertOwnedOccurrence(occurrence);
		if(fedPlanVariants == null || fedPlanVariants.hopCommon == null
			|| fedPlanVariants.hopCommon.getHopRef() != occurrence.hop()
			|| fedPlanVariants.getFedOutType() != fedOutType)
			throw new IllegalArgumentException("Plan variants do not bind the supplied occurrence");
		addFedPlanVariants(occurrence.hop().getHopID(), fedOutType, fedPlanVariants);
	}

	public void addFedPlanVariants(long hopID, FederatedOutput fedOutType, FedPlanVariants fedPlanVariants) {
		hopMemoTable.put(new ImmutablePair<>(hopID, fedOutType), fedPlanVariants);
	}

	public FedPlanVariants getFedPlanVariants(Pair<Long, FederatedOutput> fedPlanPair) {
		return hopMemoTable.get(fedPlanPair);
	}

	public FedPlan getFedPlanAfterPrune(long hopID, FederatedOutput federatedOutput) {
		FedPlanVariants fedPlanVariantList = hopMemoTable.get(new ImmutablePair<>(hopID, federatedOutput));
		if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
			return null;
		}
		return selectPrimaryVariantAfterPrune(fedPlanVariantList);
	}

	public FedPlan getFedPlanAfterPrune(Pair<Long, FederatedOutput> fedPlanPair) {
		FedPlanVariants fedPlanVariantList = hopMemoTable.get(fedPlanPair);
		if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
			return null;
		}
		return selectPrimaryVariantAfterPrune(fedPlanVariantList);
	}

	public FedPlan getFedPlanAfterPrune(HopOccurrenceProjection occurrence, FederatedOutput federatedOutput) {
		assertOwnedOccurrence(occurrence);
		return getFedPlanAfterPrune(occurrence.hop().getHopID(), federatedOutput);
	}

	public Hop resolveExecutableHop(HopOccurrenceProjection occurrence) {
		assertOwnedOccurrence(occurrence);
		return occurrence.hop();
	}

	private void assertOwnedOccurrence(HopOccurrenceProjection occurrence) {
		if(analysis == null)
			throw new IllegalStateException("Memo is not bound to a placement analysis");
		if(occurrence == null || analysis.occurrences().stream().noneMatch(candidate -> candidate == occurrence)
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

	public void registerHopRefs(Map<Long, HopCommon> hopCommonTable) {
		if (hopCommonTable == null)
			return;
		for (Map.Entry<Long, HopCommon> entry : hopCommonTable.entrySet()) {
			HopCommon hc = entry.getValue();
			if (hc != null && hc.getHopRef() != null)
				hopRefMap.put(entry.getKey(), hc.getHopRef());
		}
	}

	public void registerCloneMapping(Map<Long, Long> cloneToOrigMap) {
		if (cloneToOrigMap == null || cloneToOrigMap.isEmpty())
			return;
		cloneToOrig.putAll(cloneToOrigMap);
	}

	public void registerAdditionalRootHopIDs(List<Hop> roots) {
		if (roots == null || roots.isEmpty())
			return;
		for (Hop root : roots) {
			if (root != null)
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
			private double cumulativeCost; // Total cost = sum of selfCost + cumulativeCost of child plans
			private final FedPlanVariants fedPlanVariants; // Reference to variant list
			private final List<Pair<Long, FederatedOutput>> childFedPlans; // Child plan references
				private ExecType execType;
				private FType fType;
				private FType cpFoutType;
				private boolean derivedFedFout;
				private boolean foutMaterializationAccounted;

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

				public boolean isFoutMaterializationAccounted() {
					return foutMaterializationAccounted;
				}

				public void setFoutMaterializationAccounted(boolean foutMaterializationAccounted) {
					this.foutMaterializationAccounted = foutMaterializationAccounted;
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
		 * Maximum number of plan variants to retain per output type (LOUT/FOUT) after pruning.
		 *
		 * <p>DP enumerates multiple variants that differ in (a) exec type (CP vs FED) and
		 * (b) child output signatures (which children are LOUT vs FOUT). Downstream rewrite
		 * stages (e.g., clone-set output conflict resolution) may later select a different
		 * child-output decision than the cheapest variant. If pruning retains only a single
		 * CP and single FED plan, the cheapest CP plan can become incompatible with the
		 * chosen child decisions, forcing selection of a more expensive FED plan and
		 * causing performance regressions (observed in kmeans WAN-mid at hop 364).</p>
		 *
		 * <p>We therefore retain the top-K cheapest variants (bounded), while still ensuring
		 * at least one CP and one FED variant remain available for conflict resolution.</p>
		 */
		private static final int MAX_PRUNED_VARIANTS_PER_OUTPUT = 8;
		private static final int MAX_MATERIALIZATION_SENSITIVE_CP_VARIANTS = 4;

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

		public boolean pruneFedPlans() {
			if (_fedPlanVariants.isEmpty())
				return false;

			_fedPlanVariants.removeIf(p -> p == null || p.getExecType() == null);
			if (_fedPlanVariants.isEmpty())
				return false;

			// Sort once by cumulative cost (stable in Java's TimSort implementation).
			_fedPlanVariants.sort(Comparator.comparingDouble(FedPlan::getCumulativeCost));

			FedPlan bestCP = null;
			FedPlan bestFED = null;
			for (FedPlan plan : _fedPlanVariants) {
				if (bestCP == null && plan.getExecType() == ExecType.CP)
					bestCP = plan;
				if (bestFED == null && plan.getExecType() == ExecType.FED)
					bestFED = plan;
				if (bestCP != null && bestFED != null)
					break;
			}

			// Keep the top-K cheapest variants, plus ensure one CP and one FED remain.
			// Also retain a small bounded set of CP variants with the broadest FED/FOUT
			// child signatures. These variants can look expensive before global output
			// decisions are known because their FOUT->CP materialization edges are charged
			// locally; selected-plan rewrite may later prove that the same stable
			// federated-origin value was already materialized by another CP consumer.
			// Dropping these variants here makes that cost/state correction impossible.
			LinkedHashSet<FedPlan> kept = new LinkedHashSet<>();
			int cap = Math.max(2, MAX_PRUNED_VARIANTS_PER_OUTPUT);
			for (FedPlan plan : _fedPlanVariants) {
				if (kept.size() >= cap)
					break;
				kept.add(plan);
			}
			if (bestCP != null)
				kept.add(bestCP);
			if (bestFED != null)
				kept.add(bestFED);
			for (FedPlan plan : selectMaterializationSensitiveCpVariants(_fedPlanVariants))
				kept.add(plan);

			_fedPlanVariants.clear();
			_fedPlanVariants.addAll(kept);
			_fedPlanVariants.sort(Comparator.comparingDouble(FedPlan::getCumulativeCost));
			return true;
		}

		private static List<FedPlan> selectMaterializationSensitiveCpVariants(List<FedPlan> variants) {
			if (variants == null || variants.isEmpty())
				return Collections.emptyList();
			List<FedPlan> candidates = new ArrayList<>();
			Set<String> seenSignatures = new LinkedHashSet<>();
			for (FedPlan plan : variants) {
				if (plan == null || plan.getExecType() != ExecType.CP)
					continue;
				int foutChildCount = countFoutChildren(plan);
				if (foutChildCount <= 0)
					continue;
				String signature = buildFoutChildSignature(plan);
				if (!seenSignatures.add(signature))
					continue;
				candidates.add(plan);
			}
			if (candidates.isEmpty())
				return Collections.emptyList();
			candidates.sort((a, b) -> {
				int cmp = Double.compare(estimateFoutChildMemEstimate(b), estimateFoutChildMemEstimate(a));
				if (cmp != 0)
					return cmp;
				cmp = Integer.compare(countFoutChildren(b), countFoutChildren(a));
				if (cmp != 0)
					return cmp;
				return Double.compare(a.getCumulativeCost(), b.getCumulativeCost());
			});
			if (candidates.size() <= MAX_MATERIALIZATION_SENSITIVE_CP_VARIANTS)
				return candidates;
			return new ArrayList<>(candidates.subList(0, MAX_MATERIALIZATION_SENSITIVE_CP_VARIANTS));
		}

		private static int countFoutChildren(FedPlan plan) {
			if (plan == null || plan.getChildFedPlans() == null)
				return 0;
			int count = 0;
			for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
				if (childEdge != null && childEdge.getValue() == FederatedOutput.FOUT)
					count++;
			}
			return count;
		}

		private static double estimateFoutChildMemEstimate(FedPlan plan) {
			if (plan == null || plan.getChildFedPlans() == null)
				return 0.0;
			double total = 0.0;
			Hop parentHop = plan.getHopRef();
			for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
					continue;
				Hop childHop = findInputByHopID(parentHop, childEdge.getKey());
				if (childHop == null)
					continue;
				double mem = FederatedCostModel.getEffectiveOutputMemEstimate(childHop);
				if (Double.isFinite(mem) && mem > 0.0)
					total += mem;
			}
			return total;
		}

		private static Hop findInputByHopID(Hop parentHop, long childHopID) {
			if (parentHop == null || parentHop.getInput() == null)
				return null;
			for (Hop input : parentHop.getInput()) {
				if (input != null && input.getHopID() == childHopID)
					return input;
			}
			return null;
		}

		private static String buildFoutChildSignature(FedPlan plan) {
			if (plan == null || plan.getChildFedPlans() == null)
				return "";
			StringBuilder sb = new StringBuilder();
			for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
					continue;
				if (sb.length() > 0)
					sb.append(',');
				sb.append(childEdge.getKey());
			}
			return sb.toString();
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

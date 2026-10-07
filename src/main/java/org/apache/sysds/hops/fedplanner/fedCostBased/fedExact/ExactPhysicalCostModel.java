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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.PreparedExecutionCost;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.LatentWdivmmRuntimeTransferBoundary;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Shared exact physical objective over the canonical placement analysis. */
public final class ExactPhysicalCostModel {
	enum Direction { UPLOAD, DOWNLOAD }
	enum BoundaryMode { ANCHOR_TRANSFER, TWRITE_METADATA, RUNTIME_FUSED_INPUT, RUNTIME_RELOCATED_INPUT }

	private ExactPhysicalCostModel() {
		// utility class
	}

	private static final Object MAIN_OCCURRENCE_CONTEXT = new Object();

	static record PhysicalTransferEndpoint(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition) {
		PhysicalTransferEndpoint {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("EXACT_PHYSICAL_TRANSFER_POSITION_INVALID");
		}
	}

	static record PhysicalTransferKey(ValueVersionKey sourceValueVersion,
		List<PhysicalTransferEndpoint> endpoints, Direction direction, FType fType,
		BoundaryMode boundaryMode, String physicalEmissionIdentity) {
		PhysicalTransferKey(ValueVersionKey sourceValueVersion,
			List<PhysicalTransferEndpoint> endpoints, Direction direction, FType fType,
			BoundaryMode boundaryMode) {
			this(sourceValueVersion, endpoints, direction, fType, boundaryMode, "-");
		}
		PhysicalTransferKey {
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			endpoints = List.copyOf(endpoints);
			if(endpoints.isEmpty() || direction == null || fType == null || boundaryMode == null
				|| physicalEmissionIdentity == null || physicalEmissionIdentity.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_TRANSFER_KEY_INVALID");
		}
	}

	static record PhysicalContribution(String id, ExactCategoricalSolver.Factor factor) {
		PhysicalContribution {
			if(id == null || id.isBlank() || factor == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_CONTRIBUTION_INVALID");
		}
	}

	/**
	 * A runtime lifetime requirement derived from the same source and demand masks
	 * that own the canonical materialization cost.  It is not a solver choice.
	 */
	static record SupplySharingGroup(String physicalEmissionIdentity,
		ExactCategoricalSolver.Variable source, boolean[] activeSource,
		List<ActivationDemand> demands) {
		SupplySharingGroup {
			if(physicalEmissionIdentity == null || physicalEmissionIdentity.isBlank())
				throw new IllegalArgumentException("EXACT_SUPPLY_SHARING_IDENTITY_INVALID");
			Objects.requireNonNull(source, "source");
			activeSource = activeSource.clone();
			demands = List.copyOf(demands);
			if(activeSource.length != source.domainSize() || demands.isEmpty())
				throw new IllegalArgumentException("EXACT_SUPPLY_SHARING_GROUP_INVALID");
		}

		@Override public boolean[] activeSource() { return activeSource.clone(); }

		boolean selectedRequiresCrossExecutionRetention(List<Integer> assignment,
			IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions) {
			Integer sourcePosition = positions.get(source);
			if(sourcePosition == null || !activeSource[assignment.get(sourcePosition)])
				return false;
			int[] values = assignment.stream().mapToInt(Integer::intValue).toArray();
			return demands.stream().anyMatch(demand -> demand.crossExecutionReuse()
				&& demand.active(values, positions));
		}

		void appendSemanticDescriptor(FingerprintWriter result) {
			result.append(physicalEmissionIdentity)
				.append("|source=").append(source.key())
				.append("|active=").appendBooleanArray(activeSource);
			for(ActivationDemand demand : demands) {
				result.append("|demand=").append(demand.variables().stream()
					.map(ExactCategoricalSolver.Variable::key).toList())
					.append(":").append('[');
				for(int index = 0; index < demand.observations().size(); index++) {
					if(index > 0)
						result.append(", ");
					result.appendBooleanArray(demand.observations().get(index));
				}
				result.append(']').append(":event=").append(demand.event())
					.append(":crossExecution=").append(demand.crossExecutionReuse());
			}
		}
	}

	enum CostTransportKind { IDENTITY, ONE_MONETARY_TABLE, ZERO }

	sealed interface CostTransportDeclaration {
		record Identity(ExactCategoricalSolver.Factor factor) implements CostTransportDeclaration {
			public Identity { Objects.requireNonNull(factor, "factor"); }
		}
		record OneMonetaryTable(ExactCategoricalSolver.Factor factor)
			implements CostTransportDeclaration {
			public OneMonetaryTable { Objects.requireNonNull(factor, "factor"); }
		}
	}
	private static final class ZeroCostTransport implements CostTransportDeclaration {
		private static final ZeroCostTransport INSTANCE = new ZeroCostTransport();
		private ZeroCostTransport() { }
	}

	static record SolverFactorization(
		List<ExactCategoricalSolver.Variable> auxiliaryVariables,
		List<ExactCategoricalSolver.Factor> factors, String semanticDescriptor,
		List<ExactCategoricalSolver.Factor> ordinaryFactors, int[] nativeLocalSourceClasses,
		CostTransportDeclaration costTransport) {

		SolverFactorization {
			auxiliaryVariables = List.copyOf(auxiliaryVariables);
			factors = List.copyOf(factors);
			ordinaryFactors = List.copyOf(ordinaryFactors);
			nativeLocalSourceClasses = nativeLocalSourceClasses == null ? null : nativeLocalSourceClasses.clone();
			Objects.requireNonNull(costTransport, "costTransport");
			if(semanticDescriptor == null || semanticDescriptor.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_FACTOR_DESCRIPTOR_INVALID");
		}

		@Override
		public int[] nativeLocalSourceClasses() {
			return nativeLocalSourceClasses == null ? null : nativeLocalSourceClasses.clone();
		}

		CostTransportKind costTransportKind() {
			if(costTransport instanceof CostTransportDeclaration.Identity)
				return CostTransportKind.IDENTITY;
			if(costTransport instanceof CostTransportDeclaration.OneMonetaryTable)
				return CostTransportKind.ONE_MONETARY_TABLE;
			return CostTransportKind.ZERO;
		}

		ExactCategoricalSolver.Factor canonicalFactorAfterFreeze(ExactCategoricalSolver.Factor original,
			IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor> frozenByOriginal) {
			if(nativeLocalSourceClasses == null)
				return frozenByOriginal.getOrDefault(original, original);
			var prices = Objects.requireNonNull(frozenByOriginal.get(ordinaryFactors.get(0)),
				"projected prices must be frozen after preflight");
			int targets = original.scope().get(1).domainSize();
			// Preserve the cost snapshot, not the live cost-estimator callback. Otherwise
			// changing global network parameters after construction could change policy
			// costs while the exact kernel still reads its frozen numeric table.
			return ExactCategoricalSolver.Factor.lazy(original.scope(), values ->
				prices.denseCostAt(nativeLocalSourceClasses[values[0]] * targets + values[1]));
		}
	}

	sealed interface FrozenCostTransport {
		record Identity(ExactCategoricalSolver.Factor factor, int solverFactorOrdinal)
			implements FrozenCostTransport { }
		record OneMonetaryTable(ExactCategoricalSolver.Factor factor, int solverFactorOrdinal)
			implements FrozenCostTransport { }
		enum Zero implements FrozenCostTransport { INSTANCE }
	}

	static record ContributionEncoding(int canonicalOrdinal, PhysicalContribution contribution,
		FrozenCostTransport transport) { }

	static final class FrozenDyadicCostTransport {
		private final PlacementAnalysis owner;
		private final String ownerFingerprint;
		private final String contributionFingerprint;
		private final List<ExactCategoricalSolver.Factor> solverFactorsInOrder;
		private final List<ContributionEncoding> canonical;
		private PhysicalCostSurface boundSurface;

		private FrozenDyadicCostTransport(PlacementAnalysis owner, String ownerFingerprint,
			String contributionFingerprint,
			List<ExactCategoricalSolver.Factor> solverFactorsInOrder,
			List<ContributionEncoding> canonical) {
			this.owner = Objects.requireNonNull(owner, "owner");
			this.ownerFingerprint = Objects.requireNonNull(ownerFingerprint, "ownerFingerprint");
			this.contributionFingerprint = Objects.requireNonNull(
				contributionFingerprint, "contributionFingerprint");
			this.solverFactorsInOrder = List.copyOf(solverFactorsInOrder);
			this.canonical = List.copyOf(canonical);
		}

		void validate(PhysicalCostSurface surface) {
			if(surface != boundSurface || surface.owner() != owner
				|| !surface.ownerFingerprint().equals(ownerFingerprint)
				|| !owner.analysisFingerprint().equals(ownerFingerprint)
				|| !surface.contributionFingerprint().equals(contributionFingerprint)
				|| surface.contributions().size() != canonical.size()
				|| surface.exactSolverFactors().size() != solverFactorsInOrder.size())
				throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_SURFACE_MISMATCH");
			owner.assertProgramStructureUnchanged();
			for(int ordinal = 0; ordinal < canonical.size(); ordinal++) {
				ContributionEncoding encoding = canonical.get(ordinal);
				if(encoding.canonicalOrdinal() != ordinal
					|| encoding.contribution() != surface.contributions().get(ordinal))
					throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_ORDINAL_MISMATCH");
				if(encoding.transport() instanceof FrozenCostTransport.Identity identity
					&& (identity.factor() != encoding.contribution().factor()
						|| solverFactorsInOrder.get(identity.solverFactorOrdinal()) != identity.factor()))
					throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_IDENTITY_MISMATCH");
				if(encoding.transport() instanceof FrozenCostTransport.OneMonetaryTable one
					&& solverFactorsInOrder.get(one.solverFactorOrdinal()) != one.factor())
					throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_MONETARY_MISMATCH");
			}
			validateSolverFactors(surface.exactSolverFactors());
		}

		void validateSolverFactors(List<ExactCategoricalSolver.Factor> factors) {
			if(factors.size() != solverFactorsInOrder.size())
				throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_FACTOR_COUNT_MISMATCH");
			for(int ordinal = 0; ordinal < factors.size(); ordinal++)
				if(factors.get(ordinal) != solverFactorsInOrder.get(ordinal))
					throw new IllegalArgumentException("EXACT_DYADIC_TRANSPORT_FACTOR_IDENTITY_MISMATCH");
		}

		List<ContributionEncoding> canonical() { return canonical; }

		private void bind(PhysicalCostSurface surface) {
			if(boundSurface != null)
				throw new IllegalStateException("EXACT_DYADIC_TRANSPORT_ALREADY_BOUND");
			boundSurface = Objects.requireNonNull(surface, "surface");
		}

	}

	static record PhysicalCostSurface(PlacementAnalysis owner, String ownerFingerprint,
		List<ExactCategoricalSolver.Variable> variables,
		List<PhysicalContribution> contributions, List<PhysicalTransferKey> transferKeys,
		List<SupplySharingGroup> supplySharingGroups,
		String contributionFingerprint,
		List<ExactCategoricalSolver.Variable> exactSolverVariables,
		List<ExactCategoricalSolver.Factor> exactSolverFactors,
		FrozenDyadicCostTransport dyadicCostTransport) {
		PhysicalCostSurface(PlacementAnalysis owner, String ownerFingerprint,
			List<ExactCategoricalSolver.Variable> variables,
			List<PhysicalContribution> contributions, List<PhysicalTransferKey> transferKeys,
			String contributionFingerprint,
			List<ExactCategoricalSolver.Variable> exactSolverVariables,
			List<ExactCategoricalSolver.Factor> exactSolverFactors,
			FrozenDyadicCostTransport dyadicCostTransport) {
			this(owner, ownerFingerprint, variables, contributions, transferKeys, List.of(),
				contributionFingerprint, exactSolverVariables, exactSolverFactors, dyadicCostTransport);
		}

		PhysicalCostSurface {
			Objects.requireNonNull(owner, "owner");
			if(ownerFingerprint == null || ownerFingerprint.isBlank()
				|| !owner.analysisFingerprint().equals(ownerFingerprint))
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_OWNER_INVALID");
			variables = List.copyOf(variables);
			contributions = List.copyOf(contributions);
			transferKeys = List.copyOf(transferKeys);
			supplySharingGroups = List.copyOf(supplySharingGroups);
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_FINGERPRINT_INVALID");
			exactSolverVariables = List.copyOf(exactSolverVariables);
			exactSolverFactors = List.copyOf(exactSolverFactors);
			Objects.requireNonNull(dyadicCostTransport, "dyadicCostTransport");
			if(exactSolverVariables.size() < variables.size())
				throw new IllegalArgumentException("EXACT_PHYSICAL_SOLVER_VARIABLE_PREFIX_INVALID");
			for(int index = 0; index < variables.size(); index++)
				if(exactSolverVariables.get(index) != variables.get(index))
					throw new IllegalArgumentException(
						"EXACT_PHYSICAL_SOLVER_VARIABLE_PREFIX_INVALID");
		}
		List<ExactCategoricalSolver.Factor> factors() {
			return contributions.stream().map(PhysicalContribution::factor).toList();
		}
		double evaluateContributionCanonical(PhysicalContribution contribution,
			List<Integer> assignment) {
			Objects.requireNonNull(contribution, "contribution");
			if(!contributions.contains(contribution))
				throw new IllegalArgumentException(
					"EXACT_PHYSICAL_COST_FOREIGN_CONTRIBUTION");
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException(
					"EXACT_PHYSICAL_COST_ASSIGNMENT_SIZE_MISMATCH");
			IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions =
				new IdentityHashMap<>();
			for(int index = 0; index < variables.size(); index++)
				positions.put(variables.get(index), index);
			int[] local = new int[contribution.factor().scope().size()];
			for(int index = 0; index < local.length; index++) {
				Integer global = positions.get(contribution.factor().scope().get(index));
				if(global == null)
					throw new IllegalArgumentException(
						"EXACT_PHYSICAL_COST_FOREIGN_VARIABLE");
				local[index] = assignment.get(global);
			}
			return contribution.factor().cost(local);
		}
		long evaluateCanonical(List<Integer> assignment) {
			owner.assertProgramStructureUnchanged();
			if(!owner.analysisFingerprint().equals(ownerFingerprint))
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_OWNER_CHANGED");
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_ASSIGNMENT_SIZE_MISMATCH");
			IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
			for(int index = 0; index < variables.size(); index++)
				positions.put(variables.get(index), index);
			ExactCompensatedCostSum total = new ExactCompensatedCostSum();
			for(PhysicalContribution contribution : contributions) {
				int[] local = new int[contribution.factor().scope().size()];
				for(int index = 0; index < local.length; index++) {
					Integer global = positions.get(contribution.factor().scope().get(index));
					if(global == null)
						throw new IllegalArgumentException("EXACT_PHYSICAL_COST_FOREIGN_VARIABLE");
					local[index] = assignment.get(global);
				}
				total.addBits(bits(contribution.factor().cost(local)),
					"EXACT_PHYSICAL_CONTRIBUTION_COST_UNPROVEN",
					"EXACT_PHYSICAL_OBJECTIVE_UNPROVEN");
			}
			return total.totalBits("EXACT_PHYSICAL_OBJECTIVE_UNPROVEN");
		}

		Set<String> selectedSharedSupplyLifetimes(List<Integer> assignment) {
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_SUPPLY_SHARING_ASSIGNMENT_SIZE_MISMATCH");
			IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
			for(int index = 0; index < variables.size(); index++)
				positions.put(variables.get(index), index);
			Set<String> selected = new java.util.TreeSet<>();
			for(SupplySharingGroup group : supplySharingGroups)
				if(group.selectedRequiresCrossExecutionRetention(assignment, positions))
					selected.add(group.physicalEmissionIdentity());
			return Collections.unmodifiableSet(selected);
		}
	}

	/** Trace original physical contributions once; incident costs and solver auxiliaries are not additive. */
	static void traceCanonicalContributions(String planner,
		List<ExactCategoricalSolver.Variable> variables, List<PhysicalContribution> contributions,
		List<Integer> assignment, long expectedObjectiveBits, BiConsumer<String,String> sink) {
		traceCanonicalContributions(planner, variables, contributions, assignment,
			expectedObjectiveBits, FederatedPlannerTrace.isDetailEnabled(), sink);
	}

	static void traceCanonicalContributions(String planner,
		List<ExactCategoricalSolver.Variable> variables, List<PhysicalContribution> contributions,
		List<Integer> assignment, long expectedObjectiveBits, boolean traceDetails,
		BiConsumer<String,String> sink) {
		long auditStartedNanos = System.nanoTime();
		long detailFormattingOutputNanos = 0L;
		if(assignment == null || assignment.size() != variables.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_ASSIGNMENT_SIZE_MISMATCH");
		Objects.requireNonNull(sink, "sink");
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++) {
			if(assignment.get(index) == null || assignment.get(index) < 0
				|| assignment.get(index) >= variables.get(index).domainSize())
				throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_ASSIGNMENT_VALUE_INVALID");
			positions.put(variables.get(index), index);
		}
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(int ordinal = 0; ordinal < contributions.size(); ordinal++) {
			PhysicalContribution contribution = contributions.get(ordinal);
			int[] local = new int[contribution.factor().scope().size()];
			for(int index = 0; index < local.length; index++) {
				Integer global = positions.get(contribution.factor().scope().get(index));
				if(global == null)
					throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_FOREIGN_VARIABLE");
				local[index] = assignment.get(global);
			}
			double value = contribution.factor().cost(local);
			long valueBits = bits(value);
			sum.addBits(valueBits, "EXACT_PHYSICAL_TRACE_COST_INVALID",
				"EXACT_PHYSICAL_TRACE_SUM_INVALID");
			if(traceDetails) {
				long detailStartedNanos = System.nanoTime();
				StringBuilder scope = new StringBuilder();
				for(int index = 0; index < contribution.factor().scope().size(); index++) {
					if(index > 0)
						scope.append(',');
					scope.append(positions.get(contribution.factor().scope().get(index)));
				}
				String id = Base64.getUrlEncoder().withoutPadding()
					.encodeToString(contribution.id().getBytes(StandardCharsets.UTF_8));
				sink.accept("Physical-CostContribution", "planner=" + planner + " ordinal=" + ordinal
					+ " unit=ms value=" + Double.toString(value)
					+ " valueBits=" + Long.toUnsignedString(valueBits) + " idBase64=" + id
					+ " scope=" + (scope.length() == 0 ? "-" : scope.toString()));
				detailFormattingOutputNanos = saturatingAdd(detailFormattingOutputNanos,
					nonNegativeElapsed(detailStartedNanos, System.nanoTime()));
			}
		}
		long sumBits = sum.totalBits("EXACT_PHYSICAL_TRACE_SUM_INVALID");
		if(sumBits != expectedObjectiveBits)
			throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_OBJECTIVE_MISMATCH");
		long auditElapsedNanos = nonNegativeElapsed(auditStartedNanos, System.nanoTime());
		long evaluationValidationNanos = Math.max(0L,
			auditElapsedNanos - detailFormattingOutputNanos);
		sink.accept("Physical-CostContributionAuditTiming", "planner=" + planner
			+ " contributions=" + contributions.size()
			+ " evaluationValidationNanos=" + evaluationValidationNanos
			+ " detailFormattingOutputNanos=" + detailFormattingOutputNanos
			+ " details=" + traceDetails);
		sink.accept("Physical-CostContributionComplete", "planner=" + planner
			+ " contributions=" + contributions.size() + " unit=ms objective="
			+ Double.toString(Double.longBitsToDouble(sumBits))
			+ " objectiveBits=" + Long.toUnsignedString(sumBits));
	}

	private static long nonNegativeElapsed(long startedNanos, long completedNanos) {
		return Math.max(0L, completedNanos - startedNanos);
	}

	private static long saturatingAdd(long left, long right) {
		if(right > Long.MAX_VALUE - left)
			return Long.MAX_VALUE;
		return left + right;
	}

	static PhysicalCostSurface physicalCostSurface(PlacementAnalysis analysis,
		ExactPhysicalModel model) {
		return physicalCostSurface(analysis, model, ExactPhysicalOptimizer.PRODUCTION_LIMITS,
			factor -> { });
	}

	static PhysicalCostSurface physicalCostSurface(PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactCategoricalSolver.Limits limits,
		Consumer<ExactCategoricalSolver.Factor> ordinaryEvaluationObserver) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(ordinaryEvaluationObserver, "ordinaryEvaluationObserver");
		analysis.assertProgramStructureUnchanged();
		OccurrenceExecutionFrequencyFacts frequencies = analysis.executionFrequencyFacts();
		if(!frequencies.exactFunctionContextsProven())
			throw new IllegalArgumentException("EXACT_GUARDED_FUNCTION_ROOTS_REQUIRED");
		int workers = workerCount(analysis.graph());
		PhysicalWorkerCounts physicalWorkerCounts = new PhysicalWorkerCounts();
		ExpectedSparseAssignmentEstimates sparseAssignments =
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds =
			new IdentityHashMap<>();
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations =
			new IdentityHashMap<>();
		List<PhysicalTransferKey> transferKeys = new ArrayList<>();
		List<SupplySharingGroup> supplySharingGroups = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains =
			new IdentityHashMap<>();
		Map<CompiledHopKey,PreparedExecutionCost> preparedCosts =
			PlacementCostSemantics.prepareExecutionCosts(analysis, sparseAssignments);
		ExactPhysicalNativeSupplyRepresentation nativeSupply = model.nativeSupplyRepresentation();
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			domains.put(domain.node().key(), domain);
			addPhysicalUnaryFactor(analysis, sparseAssignments, domain, workers,
				frequencies, factors, factorKinds, preparedCosts.get(domain.node().key()),
				nativeSupply.domain(domain.node().key()));
		}
		addJointPhysicalExecutionFactors(analysis, sparseAssignments, model.domains(), domains, frequencies,
			preparedCosts, factors, factorKinds);
		addPhysicalFusedKernelFactors(analysis, model.domains(), domains, workers,
			physicalWorkerCounts, frequencies, preparedCosts, factors, factorKinds);
		List<FusedFactorUse> fusedFactors = fusedFactorUses(analysis, model.domains(), domains,
			preparedCosts, workers, physicalWorkerCounts);
		addPhysicalFusedFactorUploads(fusedFactors, frequencies, factors, factorKinds);
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries =
			new LinkedHashSet<>(PlacementCostSemantics
				.latentWdivmmRuntimeTransferBoundaries(analysis));
		Set<RetainedFunctionDownload> retainedFunctionDownloads = new LinkedHashSet<>();
		addPhysicalCompiledTransferFactors(analysis, sparseAssignments, model.domains(),
			domains, workers, physicalWorkerCounts, frequencies, latentRuntimeBoundaries,
			fusedFactors, factors, factorizations, transferKeys, supplySharingGroups,
			retainedFunctionDownloads, nativeSupply);
		addPhysicalLatentWdivmmRuntimeInputFactors(analysis, sparseAssignments,
			model.domains(), domains, workers, frequencies, factors, factorKinds,
			factorizations, transferKeys);
		addPhysicalNativeLocalInputTransferFactors(analysis, sparseAssignments, domains,
			workers, physicalWorkerCounts, frequencies, latentRuntimeBoundaries, factors, factorizations);
		addPhysicalLogicalFunctionFactors(analysis, sparseAssignments, domains, workers,
			physicalWorkerCounts, frequencies, latentRuntimeBoundaries, factors, transferKeys,
			retainedFunctionDownloads);
		List<PhysicalContribution> contributions = new ArrayList<>(factors.size());
		List<ExactCategoricalSolver.Variable> exactSolverVariables =
			new ArrayList<>(model.variables());
		exactSolverVariables.addAll(model.exactSolverAuxiliaryVariables());
		List<ExactCategoricalSolver.Factor> provisionalSolverFactors = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> ordinaryFactors = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : factors) {
			SolverFactorization factorization = factorizations.get(factor);
			if(factorization == null) {
				ordinaryFactors.add(factor);
				provisionalSolverFactors.add(factor);
			}
			else {
				exactSolverVariables.addAll(factorization.auxiliaryVariables());
				provisionalSolverFactors.addAll(factorization.factors());
				ordinaryFactors.addAll(factorization.ordinaryFactors());
			}
		}
		List<ExactCategoricalSolver.Factor> frozenOrdinary =
			freezeOrdinaryFactorsAfterPreflight(exactSolverVariables, model.exactSolverHardFactors(),
				provisionalSolverFactors, ordinaryFactors, limits, ordinaryEvaluationObserver);
		IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor> frozenByOriginal =
			new IdentityHashMap<>();
		for(int index = 0; index < ordinaryFactors.size(); index++)
			frozenByOriginal.put(ordinaryFactors.get(index), frozenOrdinary.get(index));

		FingerprintWriter normalized = new FingerprintWriter();
		var textDiagnostics = Boolean.getBoolean("sysds.fedplanner.liveMetrics")
			? new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics() : null;
		PhysicalSemanticDagFingerprint semanticDag = new PhysicalSemanticDagFingerprint(textDiagnostics);
		normalized.append(analysis.analysisFingerprint());
		semanticDag.appendSchema(normalized);
		PhysicalSemanticDagFingerprint.NormalizedTextSharingSnapshot textSharing = null;
		try {
			// The in-process optimization receipt binds published candidate facts and the
			// physical factor universe, not merely factor scopes. Compact omission proofs
			// are validated by the owning analysis; this is not a standalone legality proof.
			// A changed candidate capability/emission or
			// a changed numeric factor table must therefore produce a different certificate
			// even when a reconstructed analysis reuses the old structural fingerprint.
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
				semanticDag.appendCandidateOccurrence(normalized, fact);
			semanticDag.appendModelAlternativeOccurrences(normalized, model);
		}
		finally {
			try {
				if(textDiagnostics != null)
					textSharing = textDiagnostics.snapshotAndClear();
			}
			finally {
				semanticDag.clearLiteralByteMemo();
			}
		}
		for(String descriptor : model.exactSolverHardFactorDescriptors())
			normalized.append("|hard-encoding:").append(descriptor);
		List<ExactCategoricalSolver.Factor> exactSolverFactors = new ArrayList<>();
		List<ContributionEncoding> dyadicEncodings = new ArrayList<>();
		for(int index = 0; index < factors.size(); index++) {
			ExactCategoricalSolver.Factor factor = factors.get(index);
			String id = String.format("%08d", index) + '|'
				+ factorKinds.getOrDefault(factor, "GENERIC") + '|'
				+ factor.scope().stream().map(ExactCategoricalSolver.Variable::key).toList();
			normalized.append('|').append(id);
			SolverFactorization factorization = factorizations.get(factor);
			if(factorization == null) {
				ExactCategoricalSolver.Factor frozen = frozenByOriginal.get(factor);
				PhysicalContribution contribution = new PhysicalContribution(id, frozen);
				contributions.add(contribution);
				normalized.append("|values=");
				appendPhysicalFactorValues(normalized, frozen);
				exactSolverFactors.add(frozen);
				dyadicEncodings.add(new ContributionEncoding(index, contribution,
					new FrozenCostTransport.Identity(frozen, exactSolverFactors.size() - 1)));
			}
			else {
				PhysicalContribution contribution = new PhysicalContribution(id,
					factorization.canonicalFactorAfterFreeze(factor, frozenByOriginal));
				contributions.add(contribution);
				normalized.append("|structured=").append(factorization.semanticDescriptor());
				for(var encoded : factorization.factors()) {
					var frozen = frozenByOriginal.get(encoded);
					exactSolverFactors.add(frozen == null ? encoded : frozen);
					if(frozen != null) {
						normalized.append("|encodedScope=").append(encoded.scope())
							.append("|values=");
						appendPhysicalFactorValues(normalized, frozen);
					}
				}
				dyadicEncodings.add(new ContributionEncoding(index, contribution,
					freezeCostTransport(factorization.costTransport(), frozenByOriginal,
						exactSolverFactors)));
			}
		}
		for(PhysicalTransferKey key : transferKeys)
			normalized.append("|transfer:").append(key);
		for(SupplySharingGroup group : supplySharingGroups) {
			normalized.append("|sharing-group:");
			group.appendSemanticDescriptor(normalized);
		}
		String contributionFingerprint = PhysicalSemanticDagFingerprint.SCHEMA + ':' + normalized.finish();
		FrozenDyadicCostTransport dyadicTransport = new FrozenDyadicCostTransport(analysis,
			analysis.analysisFingerprint(), contributionFingerprint, exactSolverFactors, dyadicEncodings);
		PhysicalCostSurface surface = new PhysicalCostSurface(analysis, analysis.analysisFingerprint(),
			model.variables(), contributions, transferKeys, supplySharingGroups, contributionFingerprint,
			exactSolverVariables, exactSolverFactors, dyadicTransport);
		dyadicTransport.bind(surface);
		if(textSharing != null)
			System.err.println("SEARCH_SPACE_TEXT|analysis=" + analysis.analysisFingerprint()
				+ "|schema=" + PhysicalSemanticDagFingerprint.SCHEMA + '|' + textSharing);
		return surface;
	}

	private static FrozenCostTransport freezeCostTransport(CostTransportDeclaration declaration,
		IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor> frozenByOriginal,
		List<ExactCategoricalSolver.Factor> solverFactors) {
		if(declaration instanceof CostTransportDeclaration.Identity identity) {
			ExactCategoricalSolver.Factor frozen = frozenByOriginal.get(identity.factor());
			if(frozen == null)
				throw new IllegalArgumentException("EXACT_DYADIC_IDENTITY_FACTOR_NOT_FROZEN");
			return new FrozenCostTransport.Identity(frozen, uniqueIdentityOrdinal(solverFactors, frozen));
		}
		if(declaration instanceof CostTransportDeclaration.OneMonetaryTable one) {
			ExactCategoricalSolver.Factor frozen = frozenByOriginal.get(one.factor());
			if(frozen == null)
				throw new IllegalArgumentException("EXACT_DYADIC_MONETARY_FACTOR_NOT_FROZEN");
			return new FrozenCostTransport.OneMonetaryTable(frozen,
				uniqueIdentityOrdinal(solverFactors, frozen));
		}
		if(declaration == ZeroCostTransport.INSTANCE)
			return FrozenCostTransport.Zero.INSTANCE;
		throw new IllegalArgumentException("EXACT_DYADIC_COST_TRANSPORT_MISSING");
	}

	private static int uniqueIdentityOrdinal(List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Factor target) {
		int found = -1;
		for(int ordinal = 0; ordinal < factors.size(); ordinal++)
			if(factors.get(ordinal) == target) {
				if(found >= 0)
					throw new IllegalArgumentException("EXACT_DYADIC_MONETARY_FACTOR_DUPLICATED");
				found = ordinal;
			}
		if(found < 0)
			throw new IllegalArgumentException("EXACT_DYADIC_MONETARY_FACTOR_MISSING");
		return found;
	}

	static List<ExactCategoricalSolver.Factor> freezeOrdinaryFactorsAfterPreflight(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> hardFactors,
		List<ExactCategoricalSolver.Factor> solverCostFactors,
		List<ExactCategoricalSolver.Factor> ordinaryFactors,
		ExactCategoricalSolver.Limits limits) {
		return freezeOrdinaryFactorsAfterPreflight(variables, hardFactors,
			solverCostFactors, ordinaryFactors, limits, factor -> { });
	}

	private static List<ExactCategoricalSolver.Factor> freezeOrdinaryFactorsAfterPreflight(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> hardFactors,
		List<ExactCategoricalSolver.Factor> solverCostFactors,
		List<ExactCategoricalSolver.Factor> ordinaryFactors,
		ExactCategoricalSolver.Limits limits,
		Consumer<ExactCategoricalSolver.Factor> ordinaryEvaluationObserver) {
		List<ExactCategoricalSolver.Factor> complete = new ArrayList<>(hardFactors);
		complete.addAll(solverCostFactors);
		ExactCategoricalSolver.validateInputStructure(variables, complete, limits);
		List<ExactCategoricalSolver.Factor> frozen = new ArrayList<>(ordinaryFactors.size());
		for(ExactCategoricalSolver.Factor factor : ordinaryFactors) {
			ordinaryEvaluationObserver.accept(factor);
			frozen.add(ExactCategoricalSolver.freezeValidatedFactor(factor));
		}
		return List.copyOf(frozen);
	}

	public static String physicalAuthorityFingerprint(PlacementAnalysis analysis) {
		ExactPhysicalModel model = ExactPhysicalModel.build(
			Objects.requireNonNull(analysis, "analysis"));
		return physicalCostSurface(analysis, model).contributionFingerprint();
	}

	private static void appendPhysicalCandidateFact(FingerprintWriter normalized,
		CandidateRuleFact fact) {
		normalized.append("|candidate:").append(fact.key().normalizedSignature())
			.append("|status=").append(fact.status())
			.append("|capability=").append(fact.capability())
			.append("|shape=").append(fact.shapeProof())
			.append("|profile=").append(fact.profile())
			.append("|emissions=[");
		for(int index = 0; index < fact.allowedEmissionFacts().size(); index++) {
			if(index > 0)
				normalized.append(", ");
			appendPhysicalCandidateEmission(normalized, fact.allowedEmissionFacts().get(index));
		}
		normalized.append("]|failure=").append(fact.failureCode());
	}

	private static void appendPhysicalCandidateEmission(FingerprintWriter normalized,
		CandidateEmissionFact emission) {
		normalized.append(emission.selectionSignature()).append("|realizations=[");
		for(int index = 0; index < emission.realizations().size(); index++) {
			if(index > 0)
				normalized.append(", ");
			normalized.appendSignature(normalized.candidateText.emissionRealization(
				emission.realizations().get(index)));
		}
		normalized.append(']');
	}

	static String physicalCandidateFactsFingerprintForTest(
		List<CandidateRuleFact> facts) {
		FingerprintWriter normalized = new FingerprintWriter();
		for(CandidateRuleFact fact : facts)
			appendPhysicalCandidateFact(normalized, fact);
		return normalized.finish();
	}

	static String fingerprintTextChunksForTest(List<?> chunks) {
		FingerprintWriter normalized = new FingerprintWriter();
		for(Object chunk : chunks)
			normalized.append(chunk);
		return normalized.finish();
	}

	static String physicalAlternativeSignatureFingerprintForTest(
		ExactPhysicalModel.Alternative alternative) {
		FingerprintWriter normalized = new FingerprintWriter();
		normalized.appendSignature(alternative.normalizedSignature());
		return normalized.finish();
	}

	static String physicalCandidateFactsDagFingerprintForTest(List<CandidateRuleFact> facts) {
		return new PhysicalSemanticDagFingerprint().candidateFactsForTest(facts);
	}

	static String physicalAlternativeDagFingerprintForTest(
		ExactPhysicalModel.Alternative alternative) {
		return new PhysicalSemanticDagFingerprint().alternativeForTest(alternative);
	}

	static String physicalModelAlternativeDagFingerprintForTest(ExactPhysicalModel model) {
		return new PhysicalSemanticDagFingerprint().modelAlternativesForTest(model);
	}

	private static void appendPhysicalFactorValues(FingerprintWriter normalized,
		ExactCategoricalSolver.Factor factor) {
		long cells = 1L;
		for(ExactCategoricalSolver.Variable variable : factor.scope()) {
			if(cells > ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells()
				/ variable.domainSize())
				throw new IllegalArgumentException(
					"EXACT_PHYSICAL_FINGERPRINT_FACTOR_LIMIT_EXCEEDED|scope="
						+ factor.scope().stream().map(ExactCategoricalSolver.Variable::key).toList()
						+ "|limit=" + ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells());
			cells *= variable.domainSize();
		}
		int denseCells = Math.toIntExact(cells);
		for(int cell = 0; cell < denseCells; cell++)
			normalized.appendUnsignedHexWithComma(
				Double.doubleToRawLongBits(factor.denseCostAt(cell)));
	}

	static String physicalFactorValuesFingerprint(ExactCategoricalSolver.Factor factor) {
		FingerprintWriter normalized = new FingerprintWriter();
		appendPhysicalFactorValues(normalized, factor);
		return normalized.finish();
	}

	static final class FingerprintWriter {
		private static final byte[] LOWER_HEX =
			"0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] TRUE_TEXT = "true".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] FALSE_TEXT = "false".getBytes(StandardCharsets.US_ASCII);
		private final MessageDigest digest;
		// Share immutable nested signature segments only within this fingerprint.
		private final PlacementAnalysis.NormalizedTextContext candidateText =
			new PlacementAnalysis.NormalizedTextContext();
		private final PlacementAnalysis.NormalizedTextAppendSession signatureReplay =
			new PlacementAnalysis.NormalizedTextAppendSession(4096, 32768, 64L * 1024 * 1024);
		private final byte[] unsignedHexBuffer = new byte[17];
		private final byte[] digestBuffer = new byte[8192];
		private int buffered;
		private int hexOffset = unsignedHexBuffer.length;
		private long lastBits;
		private char pendingHighSurrogate;

		FingerprintWriter() {
			try {
				digest = MessageDigest.getInstance("SHA-256");
			}
			catch(NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 is unavailable", ex);
			}
		}

		FingerprintWriter append(Object value) {
			appendUtf8(String.valueOf(value));
			return this;
		}

		private void appendSignatureChunk(String value) {
			append(value);
		}

		private void appendSignature(PlacementAnalysis.NormalizedText value) {
			signatureReplay.appendTo(value, this::appendSignatureChunk);
		}

		private void appendUtf8(String value) {
			if(value.isEmpty())
				return;
			int index = 0;
			if(pendingHighSurrogate != 0) {
				if(Character.isLowSurrogate(value.charAt(0))) {
					int codePoint = Character.toCodePoint(pendingHighSurrogate, value.charAt(0));
					appendUtf8CodePoint(codePoint);
					index = 1;
				}
				else
					appendMalformedSurrogate();
				pendingHighSurrogate = 0;
			}
			int end = value.length();
			if(index < end && Character.isHighSurrogate(value.charAt(end - 1)))
				pendingHighSurrogate = value.charAt(--end);
			if(index < end) {
				String complete = index == 0 && end == value.length() ? value : value.substring(index, end);
				// Whole segments use the JVM's bulk encoder. A scalar code-point loop
				// loses its optimized Latin-1/ASCII path for long repeated signatures.
				byte[] encoded = complete.getBytes(StandardCharsets.UTF_8);
				if(encoded.length > digestBuffer.length - buffered)
					flush();
				if(encoded.length >= digestBuffer.length)
					digest.update(encoded);
				else {
					System.arraycopy(encoded, 0, digestBuffer, buffered, encoded.length);
					buffered += encoded.length;
				}
			}
		}

		private void appendUtf8CodePoint(int codePoint) {
			int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
			if(buffered + length > digestBuffer.length)
				flush();
			if(length == 1)
				digestBuffer[buffered++] = (byte)codePoint;
			else if(length == 2) {
				digestBuffer[buffered++] = (byte)(0xc0 | (codePoint >>> 6));
				digestBuffer[buffered++] = (byte)(0x80 | (codePoint & 0x3f));
			}
			else if(length == 3) {
				digestBuffer[buffered++] = (byte)(0xe0 | (codePoint >>> 12));
				digestBuffer[buffered++] = (byte)(0x80 | ((codePoint >>> 6) & 0x3f));
				digestBuffer[buffered++] = (byte)(0x80 | (codePoint & 0x3f));
			}
			else {
				digestBuffer[buffered++] = (byte)(0xf0 | (codePoint >>> 18));
				digestBuffer[buffered++] = (byte)(0x80 | ((codePoint >>> 12) & 0x3f));
				digestBuffer[buffered++] = (byte)(0x80 | ((codePoint >>> 6) & 0x3f));
				digestBuffer[buffered++] = (byte)(0x80 | (codePoint & 0x3f));
			}
		}

		private void appendMalformedSurrogate() {
			if(buffered == digestBuffer.length)
				flush();
			digestBuffer[buffered++] = '?';
		}

		FingerprintWriter appendUnsignedHexWithComma(long value) {
			flushPendingHighSurrogate();
			if(hexOffset == unsignedHexBuffer.length || lastBits != value) {
				lastBits = value;
				hexOffset = unsignedHexBuffer.length - 1;
				unsignedHexBuffer[hexOffset] = ',';
				do {
					unsignedHexBuffer[--hexOffset] = LOWER_HEX[(int)(value & 0xfL)];
					value >>>= 4;
				}
				while(value != 0L);
			}
			int length = unsignedHexBuffer.length - hexOffset;
			if(buffered + length > digestBuffer.length)
				flush();
			System.arraycopy(unsignedHexBuffer, hexOffset, digestBuffer, buffered, length);
			buffered += length;
			return this;
		}

		FingerprintWriter appendBooleanArray(boolean[] values) {
			append('[');
			for(int index = 0; index < values.length; index++) {
				byte[] token = values[index] ? TRUE_TEXT : FALSE_TEXT;
				if(buffered + token.length + 2 > digestBuffer.length)
					flush();
				if(index > 0) {
					digestBuffer[buffered++] = ',';
					digestBuffer[buffered++] = ' ';
				}
				System.arraycopy(token, 0, digestBuffer, buffered, token.length);
				buffered += token.length;
			}
			return append(']');
		}

		private void flush() {
			if(buffered == 0)
				return;
			digest.update(digestBuffer, 0, buffered);
			buffered = 0;
		}

		private void flushPendingHighSurrogate() {
			if(pendingHighSurrogate == 0)
				return;
			appendMalformedSurrogate();
			pendingHighSurrogate = 0;
		}

		String finish() {
			flushPendingHighSurrogate();
			flush();
			StringBuilder hex = new StringBuilder(64);
			for(byte octet : digest.digest())
				hex.append(String.format("%02x", octet));
			return hex.toString();
		}
	}

	private static void addPhysicalUnaryFactor(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		ExactPhysicalModel.DecisionDomain domain, int workers,
		OccurrenceExecutionFrequencyFacts frequencies,
		List<ExactCategoricalSolver.Factor> factors) {
		addPhysicalUnaryFactor(analysis, sparseAssignments, domain, workers, frequencies,
			factors, new IdentityHashMap<>(),
			PlacementCostSemantics.prepareExecutionCost(analysis, sparseAssignments, domain.node().key()), null);
	}

	private static void addPhysicalUnaryFactor(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		ExactPhysicalModel.DecisionDomain domain, int workers,
		OccurrenceExecutionFrequencyFacts frequencies,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds,
		PreparedExecutionCost prepared,
		ExactPhysicalNativeSupplyRepresentation.Domain nativeSupplyDomain) {
		if(domain.node().kind() == NodeKind.FUNCTION_INPUT
			|| domain.node().kind() == NodeKind.FUNCTION_OUTPUT
			|| analysis.isDmlFunctionCallBoundary(domain.node().key())) {
			double[] zero = new double[domain.alternatives().size()];
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), zero));
			return;
		}
		Hop hop = analysis.hop(domain.node().key()).orElseThrow();
		double weight = frequencies.exactExecutionWeight(domain.node().key());
		double[] execution = new double[domain.alternatives().size()];
		double[] outputMaterialization = new double[execution.length];
		double[] nativeFedDownload = new double[execution.length];
		double[] nativeCpUpload = new double[execution.length];
		// Receipt/support variants do not change this unary observation. The owning
		// analysis, kernel, estimates and weight are fixed within this loop. Actual
		// operand ranges (not proof identities or only W) distinguish skew and slicing;
		// retain only successfully computed projections, never a cross-build result.
		Map<FederatedExecutionLayout,FedCostProjection> projections = new LinkedHashMap<>();
		PhysicalWorkerCounts physicalWorkerCounts = new PhysicalWorkerCounts();
		ExecutionWorkerCounts executionWorkerCounts = new ExecutionWorkerCounts();
		InputLayoutCache inputLayouts = new InputLayoutCache();
		for(int value = 0; value < execution.length; value++) {
			if(prepared.removedKernel())
				continue;
			ExactPhysicalModel.Alternative alternative = domain.alternatives().get(value);
			PlacementState state = alternative.state();
			var nativeCandidate = nativeSupplyDomain == null ? null
				: nativeSupplyDomain.nativeCandidate(value);
			PlacementState nativeState = nativeSupplyDomain == null
				? alternative.derivedFoutAction() == null ? state
					: alternative.derivedFoutAction().key().sourcePlacement()
				: nativeCandidate.nativeOutputState();
			ExecType nativeExecutionType = nativeCandidate == null
				? nativeState.execType() : nativeCandidate.executionType();
			var outputMovement = nativeSupplyDomain == null ? null
				: nativeSupplyDomain.supplies(value).stream()
					.filter(supply -> supply.direction()
						== ExactPhysicalNativeSupplyRepresentation.SupplyDirection.OUTPUT)
					.filter(supply -> supply.actionKind()
						== ExactPhysicalNativeSupplyRepresentation.SupplyActionKind.OUTPUT_MATERIALIZATION)
					.findFirst().orElse(null);
			// SUPPLY_ONLY is a legality row for an already-owned source value.  Its
			// relocation is priced by b_e transfer factors; it does not execute a
			// second producer kernel or own a native return.
			if(nativeCandidate != null && nativeCandidate.executionKind()
				== ExactPhysicalNativeSupplyRepresentation.NativeExecutionKind.SUPPLY_ONLY)
				continue;
			// Dynamic input maps are priced row by row in a separate factor, with
			// the selected reader clauses in scope. Alias nodes have no kernel.
			if(nativeExecutionType == ExecType.FED && JointPhysicalCostRows.dynamic(alternative))
				continue;
			if(nativeExecutionType == ExecType.CP) {
				execution[value] = requireCost(weight * prepared.localCost(), "EXACT_CP_COST_UNPROVEN");
				if(outputMovement != null || nativeSupplyDomain == null && state.output() == FederatedOutput.FOUT)
					nativeCpUpload[value] = physicalResultUploadCost(analysis, sparseAssignments,
						domain.node().key(), hop,
						outputMovement == null ? state.fType() : outputMovement.targetState().fType(),
						realizationWorkerCount(analysis, alternative, workers, physicalWorkerCounts), weight);
				continue;
			}
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			if(prepared.fusedWeightsOccurrence() != null) {
				if(outputMovement != null || nativeSupplyDomain == null
					&& state.output() == FederatedOutput.FOUT && emission != null
					&& emission.emissionState().derivedFedFout())
					outputMaterialization[value] = physicalResultUploadCost(analysis, sparseAssignments,
						domain.node().key(), hop,
						outputMovement == null ? state.fType() : outputMovement.targetState().fType(),
						realizationWorkerCount(analysis, alternative, workers, physicalWorkerCounts), weight);
				continue;
			}
			FType executionFType = emission == null ? state.fType() : emission.executionFType();
			int executionWorkers = executionWorkerCount(
				analysis, alternative, workers, physicalWorkerCounts, executionWorkerCounts);
			FederatedExecutionLayout observation = executionLayout(
				analysis, alternative, executionFType, executionWorkers, inputLayouts);
			FedCostProjection projection = projections.get(observation);
			if(projection == null) {
				boolean federatedSource = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
				List<FType> inputFTypes = federatedSource ? List.of() : alternative.orderedInputs().stream()
					.map(input -> input.present() ? input.fType() : null).toList();
				projection = fedCostProjection(analysis, domain.node().key(), hop, inputFTypes,
					executionFType, executionWorkers, weight, weight * prepared.localCost(),
					effectiveOutputBytes(analysis, sparseAssignments, domain.node().key(), hop),
					effectiveUploadBytes(analysis, sparseAssignments, domain.node().key(), hop),
					weight * prepared.federatedCost(observation), prepared.outputResponses(observation,
						effectiveUploadBytes(analysis, sparseAssignments, domain.node().key(), hop)), observation);
				projections.put(observation, projection);
			}
			boolean derivedFout = outputMovement != null || nativeSupplyDomain == null
				&& state.output() == FederatedOutput.FOUT && emission != null
				&& emission.emissionState().derivedFedFout();
			execution[value] = projection.fedUnaryCost();
			if(derivedFout)
				outputMaterialization[value] = physicalResultUploadCost(analysis, sparseAssignments,
					domain.node().key(), hop,
					outputMovement == null ? state.fType() : outputMovement.targetState().fType(),
					realizationWorkerCount(analysis, alternative, workers, physicalWorkerCounts), weight);
			if(nativeState.output() == FederatedOutput.LOUT)
				nativeFedDownload[value] = projection.resultDownloadCost();
		}
		// Native execution and native LOUT return remain operator-owned contributions.
		// Only the explicit post-output upload is owned by the projected b_e supply.
		var operator = ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), execution);
		var explicitOutputMovement = ExactCategoricalSolver.Factor.dense(
			List.of(domain.variable()), outputMaterialization);
		var nativeReturn = ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeFedDownload);
		var explicitCpUpload = ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeCpUpload);
		factors.add(operator);
		factors.add(explicitOutputMovement);
		factors.add(nativeReturn);
		factors.add(explicitCpUpload);
		factorKinds.put(operator, "OPERATOR_EXECUTION");
		factorKinds.put(explicitOutputMovement, "SUPPLY_MOVEMENT|POST_OUTPUT");
		factorKinds.put(nativeReturn, "OPERATOR_NATIVE_RETURN");
		factorKinds.put(explicitCpUpload, "SUPPLY_MOVEMENT|CP_UPLOAD");
	}

	private static void addJointPhysicalExecutionFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		OccurrenceExecutionFrequencyFacts frequencies, Map<CompiledHopKey,PreparedExecutionCost> preparedCosts,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> kinds) {
		JointPhysicalCostRows projections = null;
		// Factor ordinals are part of the cost certificate and solver input order.
		// Keep identity-based domain lookup separate from canonical factor emission.
		for(var domain : orderedDomains) {
			Hop hop = analysis.hop(domain.node().key()).orElseThrow();
			if(hop instanceof DataOp || analysis.isDmlFunctionCallBoundary(domain.node().key())
				|| org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization.isPlacementAlias(hop)
				|| domain.alternatives().stream().noneMatch(JointPhysicalCostRows::dynamic))
				continue;
			if(projections == null) projections = new JointPhysicalCostRows(analysis);
			JointPhysicalCostRows rows = projections;
			PreparedExecutionCost prepared = preparedCosts.get(domain.node().key());
			double weight = frequencies.exactExecutionWeight(domain.node().key());
			double[] invariantPrices = new double[domain.alternatives().size()];
			boolean needsRelation = false;
			for(int index = 0; index < invariantPrices.length; index++) {
				var alternative = domain.alternatives().get(index);
				if(alternative.state().execType() != ExecType.FED || !JointPhysicalCostRows.dynamic(alternative))
					continue;
				var possible = rows.possibleExecutionRows(alternative);
				double price = Double.NaN;
				for(var row : possible) {
					double candidate = jointExecutionUnit(analysis, sparseAssignments, alternative, hop, prepared, row);
					if(Double.isNaN(price)) price = candidate;
					else if(Double.doubleToRawLongBits(price) != Double.doubleToRawLongBits(candidate)) {
						price = Double.NaN;
						break;
					}
				}
				invariantPrices[index] = Double.isNaN(price) ? Double.NaN : weight * price;
				needsRelation |= Double.isNaN(price);
			}
			// Worker identity can vary while every represented map has the same
			// physical price. Prove that equality and retain a unary cost instead of
			// coupling unrelated producer decisions into a large solver table.
			var invariant = ExactCategoricalSolver.Factor.dense(List.of(domain.variable()),
				java.util.Arrays.stream(invariantPrices).map(price -> Double.isNaN(price) ? 0 : price).toArray());
			factors.add(invariant);
			kinds.put(invariant, "JOINT_VALUE_MAP_EXECUTION_INVARIANT");
			if(!needsRelation) continue;
			List<CompiledHopKey> dependencies = rows.dependencies(domain, domains);
			List<ExactCategoricalSolver.Variable> scope = dependencies.stream()
				.map(key -> domains.get(key).variable()).toList();
			int ownerPosition = dependencies.indexOf(domain.node().key());
			var factor = ExactCategoricalSolver.Factor.lazy(scope, values -> {
				if(!Double.isNaN(invariantPrices[values[ownerPosition]])) return 0;
				Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected = new IdentityHashMap<>();
				for(int position = 0; position < dependencies.size(); position++) {
					CompiledHopKey key = dependencies.get(position);
					selected.put(key, domains.get(key).alternatives().get(values[position]));
				}
				var alternative = selected.get(domain.node().key());
				if(alternative.state().execType() != ExecType.FED || !JointPhysicalCostRows.dynamic(alternative))
					return 0;
				var physicalRows = rows.executionRows(alternative, selected);
				// Inconsistent reader/producer clause combinations are owned by hard
				// support factors; they do not receive a fabricated physical price.
				if(physicalRows.isEmpty()) return 0;
				return weight * JointPhysicalCostRows.expectedUnit(frequencies, alternative.decision(),
					physicalRows, row -> jointExecutionUnit(analysis, sparseAssignments,
						alternative, hop, prepared, row));
			});
			factors.add(factor);
			kinds.put(factor, "JOINT_VALUE_MAP_EXECUTION");
		}
	}

	private static double jointExecutionUnit(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, ExactPhysicalModel.Alternative alternative,
		Hop hop, PreparedExecutionCost prepared, JointPhysicalCostRows.Row row) {
		FType type = executionFType(alternative);
		var first = row.inputs().stream().filter(Objects::nonNull).findFirst().orElseThrow();
		int count = (int)first.pool().partitions().stream().map(AnchorPartition::workerId)
			.map(FederationUtils::canonicalFederatedWorkerAddress).distinct().count();
		List<InputLayout> inputs = new ArrayList<>(row.inputs().size());
		for(int position = 0; position < row.inputs().size(); position++) {
			var value = row.inputs().get(position);
			InputLayout input = value == null ? new InputLayout(null, List.of(), false)
				: new InputLayout(value.pool().fType(), value.pool().partitions(), true);
			// A replay witness can preserve the partitioned axis while normalizing
			// another extent. Apply the same operand-shape check as fixed-map costs;
			// such a witness is not evidence that an 8x2 operand became 8x1.
			if(value != null && !operandRangesFit(analysis, alternative.decision(), position, input))
				input = new InputLayout(value.pool().fType(), List.of(), false);
			inputs.add(input);
		}
		var layout = new FederatedExecutionLayout(type, count, inputs);
		var projection = fedCostProjection(analysis, alternative.decision(), hop,
			inputs.stream().map(InputLayout::fType).toList(), type, count, 1,
			prepared.localCost(), effectiveOutputBytes(analysis, sparseAssignments, alternative.decision(), hop),
			effectiveUploadBytes(analysis, sparseAssignments, alternative.decision(), hop),
			prepared.federatedCost(layout), prepared.outputResponses(layout,
				effectiveUploadBytes(analysis, sparseAssignments, alternative.decision(), hop)), layout);
		return alternative.state().output() == FederatedOutput.LOUT
			? projection.fedLoutCost() : projection.fedUnaryCost();
	}

	/** The existing runtime owner/weights legality relation also owns fused worker compute. */
	private static void addPhysicalFusedKernelFactors(PlacementAnalysis analysis,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, PhysicalWorkerCounts workerCounts, OccurrenceExecutionFrequencyFacts frequencies,
		Map<CompiledHopKey,PreparedExecutionCost> preparedCosts,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds) {
		InputLayoutCache layouts = new InputLayoutCache();
		for(var owner : orderedDomains) {
			PreparedExecutionCost prepared = preparedCosts.get(owner.node().key());
			CompiledHopKey weightsKey = prepared.fusedWeightsOccurrence();
			if(weightsKey == null)
				continue;
			var weights = domains.get(weightsKey);
			if(weights == null)
				throw new IllegalArgumentException("EXACT_FUSED_WEIGHTS_DOMAIN_MISSING");
			double frequency = frequencies.exactExecutionWeight(owner.node().key());
			double[] prices = new double[weights.alternatives().size()];
			double[] results = new double[prices.length];
			Map<FederatedExecutionLayout,Double> observations = new LinkedHashMap<>();
			for(int index = 0; index < prices.length; index++) {
				var selected = weights.alternatives().get(index);
				if(selected.state().output() != FederatedOutput.FOUT)
					continue; // infeasible FED combinations remain owned by the legality factor
				int sourceWorkers = realizationWorkerCount(analysis, selected, workers, workerCounts);
				InputLayout input = physicalValueLayout(analysis, selected, sourceWorkers, layouts);
				var layout = new FederatedExecutionLayout(selected.state().fType(), sourceWorkers, List.of(input));
				prices[index] = observations.computeIfAbsent(layout,
					ignored -> requireCost(frequency * (prepared.federatedCost(layout)
						+ prepared.fusedFactorTransposeCost()
						+ FederatedCostModel.computeRequestResponseLatency()), "EXACT_FUSED_KERNEL_COST_UNPROVEN"));
				results[index] = frequency * prepared.fusedResultCost(layout.executionType(), sourceWorkers);
			}
			var factor = ExactCategoricalSolver.Factor.lazy(List.of(owner.variable(), weights.variable()), values -> {
				var selected = owner.alternatives().get(values[0]);
				if(selected.state().execType() != ExecType.FED)
					return 0.0;
				var emission = selected.captured() ? selected.candidateEmission() : selected.executionEmission();
				boolean localResult = selected.state().output() == FederatedOutput.LOUT
					|| emission != null && emission.emissionState().derivedFedFout();
				return prices[values[1]] + (localResult ? results[values[1]] : 0.0);
			});
			factors.add(factor);
			factorKinds.put(factor, "RUNTIME_FUSED_KERNEL");
		}
	}

	/** One bounded owner/W/factor dependency; U and V never form a joint tensor. */
	private record FusedFactorUse(ExactPhysicalModel.DecisionDomain owner,
		ExactPhysicalModel.DecisionDomain weights, ExactPhysicalModel.DecisionDomain source,
		PlacementCostSemantics.RuntimeFactorInput input, PreparedExecutionCost prepared,
		List<InputLayout> weightLayouts, List<InputLayout> factorLayouts, List<Integer> workers) {
		boolean reused(InputLayout weight, int sourceValue) {
			return PlacementCostSemantics.reusesWdivmmFactor(weight, factorLayouts.get(sourceValue),
				input.position(), input.generatedTranspose());
		}
	}

	private static List<FusedFactorUse> fusedFactorUses(PlacementAnalysis analysis,
		List<ExactPhysicalModel.DecisionDomain> ordered,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		Map<CompiledHopKey,PreparedExecutionCost> preparedCosts, int workers, PhysicalWorkerCounts counts) {
		List<FusedFactorUse> result = new ArrayList<>();
		InputLayoutCache cache = new InputLayoutCache();
		for(var owner : ordered) {
			var prepared = preparedCosts.get(owner.node().key());
			if(prepared.fusedWeightsOccurrence() == null) continue;
			var weights = Objects.requireNonNull(domains.get(prepared.fusedWeightsOccurrence()));
			List<Integer> poolSizes = weights.alternatives().stream()
				.map(a -> realizationWorkerCount(analysis, a, workers, counts)).toList();
			List<InputLayout> weightLayouts = selectedValueLayouts(analysis, weights, workers, counts, cache);
			for(var input : prepared.fusedFactorInputs()) {
				var source = Objects.requireNonNull(domains.get(input.source()), "Fused factor domain");
				result.add(new FusedFactorUse(owner, weights, source, input, prepared, weightLayouts,
					selectedValueLayouts(analysis, source, workers, counts, cache), poolSizes));
			}
		}
		return List.copyOf(result);
	}

	private static List<InputLayout> selectedValueLayouts(PlacementAnalysis analysis,
		ExactPhysicalModel.DecisionDomain domain, int workers, PhysicalWorkerCounts counts, InputLayoutCache cache) {
		return domain.alternatives().stream().map(a -> a.state().output() == FederatedOutput.FOUT
			? physicalValueLayout(analysis, a, realizationWorkerCount(analysis, a, workers, counts), cache)
			: new InputLayout(null, List.of(), false)).toList();
	}

	private static void addPhysicalFusedFactorUploads(List<FusedFactorUse> uses,
		OccurrenceExecutionFrequencyFacts frequencies, List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> kinds) {
		for(var use : uses) {
			List<ExactCategoricalSolver.Variable> scope = java.util.stream.Stream.of(use.owner().variable(),
				use.weights().variable(), use.source().variable()).distinct().toList();
			int o = scope.indexOf(use.owner().variable()), w = scope.indexOf(use.weights().variable()),
				f = scope.indexOf(use.source().variable());
			double frequency = frequencies.exactExecutionWeight(use.owner().node().key());
			var factor = ExactCategoricalSolver.Factor.lazy(scope, values -> {
				InputLayout layout = use.weightLayouts().get(values[w]);
				if(use.owner().alternatives().get(values[o]).state().execType() != ExecType.FED
					|| layout.fType() == null || use.reused(layout, values[f])) return 0.0;
				return frequency * use.prepared().fusedFactorUploadCost(use.input().position(),
					layout.fType(), use.workers().get(values[w]));
			});
			factors.add(factor);
			kinds.put(factor, "RUNTIME_FUSED_FACTOR_PUT_" + use.input().position());
		}
	}

	private static InputLayout physicalValueLayout(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative selected, int workers, InputLayoutCache cache) {
		FType type = selected.state().fType();
		InputLayout input = selected.durableAnchor() != null
			? cache.anchor(type, selected.durableAnchor())
			: selected.realization() == null ? new InputLayout(type, List.of(), false)
				: cache.realization(selected.realization());
		Hop hop = analysis.hop(selected.decision()).orElseThrow();
		if(!input.exactRanges() && hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			var anchors = analysis.graph().node(selected.decision()).orElseThrow().anchors();
			if(anchors.size() == 1 && anchors.get(0).fType() == type)
				input = cache.anchor(type, anchors.get(0));
		}
		if(input.fType() != type || input.exactRanges()
			&& (!occurrenceRangesFit(analysis, selected.decision(), hop, input)
				|| input.ranges().stream().map(AnchorPartition::workerId).distinct().count() != workers))
			return new InputLayout(type, List.of(), false);
		return input;
	}

	private static double physicalResultUploadCost(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop,
		FType fType, int workers, double weight) {
		return requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(
			effectiveUploadBytes(analysis, sparseAssignments, key, hop), fType, workers)),
			"EXACT_RESULT_UPLOAD_COST_UNPROVEN");
	}

	static FederatedExecutionLayout executionLayout(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, FType executionType, int workers) {
		return executionLayout(analysis, alternative, executionType, workers, new InputLayoutCache());
	}

	private static FederatedExecutionLayout executionLayout(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, FType executionType, int workers,
		InputLayoutCache cache) {
		Hop owner = analysis.hop(alternative.decision()).orElseThrow();
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			return new FederatedExecutionLayout(executionType, Math.max(1, workers), List.of());
		List<InputLayout> inputs = new ArrayList<>(alternative.orderedInputs().size());
		for(int position = 0; position < alternative.orderedInputs().size(); position++) {
			CandidateInputState state = alternative.orderedInputs().get(position);
			FType type = state.present() ? state.fType() : null;
			InputLayout input = new InputLayout(type, List.of(), false);
			if(type != null && alternative.supportClause() != null) {
				for(var binding : alternative.supportClause().inputBindings()) {
					if(binding.inputPosition() != position)
						continue;
					if(binding.relocationAction() != null)
						input = cache.anchor(type, binding.relocationAction().durableAnchor());
					else {
						var source = analysis.requireExactCandidateRealization(binding.source());
						input = cache.realization(source);
						// A literal FED source may carry source-lineage rather than a durable
						// realization. Only its own declared map supplies operand geometry.
						var sourceKey = binding.source().rule().parentOccurrence();
						if(!input.exactRanges() && analysis.hop(sourceKey).orElse(null)
							instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
							var anchors = analysis.graph().node(sourceKey).orElseThrow().anchors();
							if(anchors.size() == 1 && anchors.get(0).fType() == type)
								input = cache.anchor(type, anchors.get(0));
						}
					}
					break;
				}
			}
			if(type != null && !input.exactRanges())
				for(var authority : alternative.inputAuthorities())
					if(authority.inputPosition() == position
						&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION) {
						input = cache.anchor(type, authority.relocationAction().key().durableAnchor());
						break;
					}
			if(type != null && !input.exactRanges()) {
				var edge = analysis.compiledInputEdge(alternative.decision(), position).orElse(null);
				int inputPosition = position;
				boolean direct = alternative.inputAuthorities().stream().anyMatch(a -> a.inputPosition() == inputPosition
					&& a.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT);
				if(direct && edge != null && analysis.hop(edge.producer()).orElse(null)
					instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
					var anchors = analysis.graph().node(edge.producer()).orElseThrow().anchors();
					if(anchors.size() == 1 && anchors.get(0).fType() == type) input = cache.anchor(type, anchors.get(0));
				}
			}
			// Relocation maps can be rebuilt to fit a new shape. Do not reuse stale
			// template ranges, a different input type, or another execution pool.
			if(input.fType() != type || input.exactRanges()
				&& (!operandRangesFit(analysis, alternative.decision(), position, input)
					|| input.ranges().stream().map(AnchorPartition::workerId).distinct().count() != workers))
				input = new InputLayout(type, List.of(), false);
			inputs.add(input);
		}
		return new FederatedExecutionLayout(executionType, Math.max(1, workers), inputs);
	}

	private static boolean operandRangesFit(PlacementAnalysis analysis, CompiledHopKey owner,
		int position, InputLayout input) {
		Hop parent = analysis.hop(owner).orElseThrow();
		if(position >= parent.getInput().size())
			return false;
		var edge = analysis.compiledInputEdge(owner, position).orElse(null);
		return occurrenceRangesFit(analysis, edge == null ? null : edge.producer(),
			parent.getInput(position), input);
	}

	private static boolean occurrenceRangesFit(PlacementAnalysis analysis, CompiledHopKey key,
		Hop operand, InputLayout input) {
		var estimate = key != null && analysis.hop(key).orElse(null) == operand
			? analysis.physicalCostEstimateFact(key) : null;
		long rows = estimate == null ? operand.getDim1() : estimate.rows();
		long cols = estimate == null ? operand.getDim2() : estimate.cols();
		var shape = key == null ? null : analysis.abstractShapeFact(key).orElse(null);
		if(shape != null) {
			if(rows <= 0 && shape.rows().knowledge()
				== org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge.EXACT)
				rows = shape.rows().value();
			if(cols <= 0 && shape.cols().knowledge()
				== org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge.EXACT)
				cols = shape.cols().value();
		}
		long maxRow = 0L, maxCol = 0L;
		for(var range : input.ranges()) {
			if(range.begin().size() < 2 || range.begin().get(0) < 0 || range.begin().get(1) < 0)
				return false;
			maxRow = Math.max(maxRow, range.end().get(0));
			maxCol = Math.max(maxCol, range.end().get(1));
		}
		return (rows <= 0 || rows == maxRow) && (cols <= 0 || cols == maxCol);
	}

	/** A native endpoint witness alone is not evidence of an operand's ranges. */
	static InputLayout realizationInputLayout(PlacementAnalysis.CandidateEmissionRealization source) {
		return new InputLayoutCache().realization(source);
	}

	private static final class InputLayoutCache {
		private final IdentityHashMap<DurableAnchorKey,Map<FType,InputLayout>> anchors = new IdentityHashMap<>();
		private final IdentityHashMap<PlacementAnalysis.CandidateEmissionRealization,InputLayout>
			realizations = new IdentityHashMap<>();

		private InputLayout anchor(FType type, DurableAnchorKey anchor) {
			if(type != anchor.fType())
				return new InputLayout(type, List.of(), false);
			return anchors.computeIfAbsent(anchor, ignored -> new java.util.EnumMap<>(FType.class))
				.computeIfAbsent(type, ignored -> new InputLayout(type, anchor.partitions().stream()
					.map(range -> new AnchorPartition(
						FederationUtils.canonicalFederatedWorkerAddress(range.workerId()), range.begin(), range.end()))
					.sorted().toList(), true));
		}

		private InputLayout realization(PlacementAnalysis.CandidateEmissionRealization source) {
			return realizations.computeIfAbsent(source, ignored -> {
				FType type = source.placementState().fType();
				if(type == null)
					return new InputLayout(null, List.of(), false);
				if(source.anchor() != null)
					return anchor(type, source.anchor());
				// The realization constructor already proves all clauses agree on the
				// witness layout/exactness. Dynamic witnesses authorize endpoints only.
				var clause = source.supportClauses().get(0);
				return clause.nativeWorkerPoolWitness() != null && clause.nativeWorkerPoolLayoutExact()
					? anchor(type, clause.nativeWorkerPoolWitness()) : new InputLayout(type, List.of(), false);
			});
		}
	}

	private static void addPhysicalCompiledTransferFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, PhysicalWorkerCounts physicalWorkerCounts,
		OccurrenceExecutionFrequencyFacts frequencies,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<FusedFactorUse> fusedFactors, List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations,
		List<PhysicalTransferKey> transferKeys,
		List<SupplySharingGroup> supplySharingGroups,
		Set<RetainedFunctionDownload> retainedFunctionDownloads,
		ExactPhysicalNativeSupplyRepresentation nativeSupply) {
		record Demand(PhysicalTransferEndpoint edge, ExactPhysicalModel.DecisionDomain consumer,
			FusedFactorUse runtime, boolean[] active, EffectiveLogicalFunctionInput functionInput) { }
		record Key(Direction direction, FType type, BoundaryMode boundary,
			String physicalEmissionIdentity, int targetWorkers, double bytes) { }
		record UnitPriceKey(Direction direction, FType type, int targetWorkers, double bytes) { }
		record CreationScope(CompiledHopKey origin,
			OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact profile, long contextOrdinal) { }
		record AliasCreation(CreationScope creation,
			OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact activationProfile) { }
		record DownloadKey(CreationScope creation, InputLayout layout, String privateIdentity, double unit) { }
		record DownloadGroup(ExactPhysicalModel.DecisionDomain representative,
			List<ActivationDemand> demands, List<PhysicalTransferEndpoint> endpoints) { }
		Map<DownloadKey,DownloadGroup> downloads = new LinkedHashMap<>();
		InputLayoutCache inputLayouts = new InputLayoutCache();
		ExecutionWorkerCounts executionCounts = new ExecutionWorkerCounts();
		JointPhysicalCostRows jointPrices = orderedDomains.stream().flatMap(domain -> domain.alternatives().stream())
			.anyMatch(JointPhysicalCostRows::dynamic) ? new JointPhysicalCostRows(analysis) : null;
		var executionLayouts = new IdentityHashMap<ExactPhysicalModel.Alternative,FederatedExecutionLayout>();
		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead = new IdentityHashMap<>();
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
			transientByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
		IdentityHashMap<CompiledHopKey,List<LogicalFunctionInputFact>> functionByRead = new IdentityHashMap<>();
		Map<Long,Long> callerContexts = new LinkedHashMap<>();
		for(var fact : analysis.logicalFunctionInputsInCanonicalOrder()) {
			functionByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
			for(var occurrence : frequencies.exactFunctionBoundaryOccurrences(fact)) {
				long callee = occurrence.calleeProfile().contextOrdinal();
				long caller = occurrence.callerContextOrdinal();
				Long previous = callerContexts.putIfAbsent(callee, caller);
				if(previous != null && previous != caller)
					throw new IllegalArgumentException("EXACT_MATERIALIZATION_CALLER_CONTEXT_AMBIGUOUS");
			}
		}
		FunctionOutputAliases functionOutputs = functionOutputAliases(analysis, frequencies);
		IdentityHashMap<CompiledHopKey,List<EffectiveLogicalFunctionInput>> functionInputsBySource =
			new IdentityHashMap<>();
		for(EffectiveLogicalFunctionInput input : effectiveLogicalFunctionInputs(analysis))
			functionInputsBySource.computeIfAbsent(input.authority().sourceArgument(),
				ignored -> new ArrayList<>()).add(input);
		for(ExactPhysicalModel.DecisionDomain producer : orderedDomains) {
			if(producer.node().kind() == NodeKind.FUNCTION_INPUT
				|| producer.node().kind() == NodeKind.FUNCTION_OUTPUT)
				continue;
			Hop producerHop = analysis.hop(producer.node().key()).orElseThrow();
			if(producerHop.getDataType() == null
				|| (!producerHop.getDataType().isMatrix() && !producerHop.getDataType().isFrame()))
				continue;
			double bytes = estimatedBytes(analysis, sparseAssignments, producer.node().key(), producerHop);
			Map<Key,List<Demand>> grouped = new LinkedHashMap<>();
			// Every consumer and sharing group reads the same immutable emitted map.
			// Keep its full identity once for this producer, including after the
			// analysis-wide serialization cache has reached its retention limit.
			Map<ExactPhysicalModel.Alternative,String> outputLayouts = new IdentityHashMap<>();
			// Keep immutable action text and its cached String hash for this producer.
			// Sharing groups still compare the full physical emission value below.
			Map<RelocationActionKey,String> relocationEmissions = new IdentityHashMap<>();
			int[] outputWorkerCounts = new int[producer.alternatives().size()];
			// Group ownership and activation remain distinct. Only their unit prices
			// share this producer-local observation of immutable alternatives and maps.
			Map<UnitPriceKey,double[]> unitPriceRows = new LinkedHashMap<>();
			for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.producer() != producer.node().key()
					|| analysis.graph().node(edge.consumer()).orElseThrow().kind() == NodeKind.FUNCTION_CALL
					|| latentRuntimeBoundaries.contains(new LatentWdivmmRuntimeTransferBoundary(
						edge.producer(), edge.consumer(), edge.inputPosition())))
					continue;
				var consumer = domains.get(edge.consumer());
				if(consumer == null) continue;
				Hop consumerHop = analysis.hop(edge.consumer()).orElseThrow();
				var endpoint = new PhysicalTransferEndpoint(edge.producer(), edge.consumer(), edge.inputPosition());
				Map<Double,boolean[]> collectByBytes = new LinkedHashMap<>();
				for(int value = 0; value < consumer.alternatives().size(); value++) {
					var selected = consumer.alternatives().get(value);
					boolean cp = selected.state().execType() == ExecType.CP;
					List<ExactPhysicalNativeSupplyRepresentation.SupplyCandidate> rowSupplies =
						nativeSupply.domain(consumer.node().key()).supplies(value);
					var relocationSupply = rowSupplies.stream().filter(supply ->
						supply.direction() == ExactPhysicalNativeSupplyRepresentation.SupplyDirection.INPUT
							&& supply.inputPosition() == edge.inputPosition()
							&& supply.actionKind()
								== ExactPhysicalNativeSupplyRepresentation.SupplyActionKind.RELOCATION)
						.findFirst().orElse(null);
					boolean relocation = relocationSupply != null;
					boolean nativeLocal = !cp && rowSupplies.stream().anyMatch(supply ->
						supply.direction() == ExactPhysicalNativeSupplyRepresentation.SupplyDirection.INPUT
							&& supply.inputPosition() == edge.inputPosition()
							&& supply.actionKind()
								== ExactPhysicalNativeSupplyRepresentation.SupplyActionKind.NATIVE_LOCAL);
					boolean runtimeCollect = false;
					if(!cp && (PlacementCostSemantics.isWdivmmMatrixOperand(consumerHop, edge.inputPosition())
						|| edge.inputPosition() == 3 && PlacementCostSemantics.hasWdivmmEpsilon(consumerHop)
						|| PlacementCostSemantics.isCtableMatrixInput(consumerHop, edge.inputPosition()))) {
						var layout = executionLayouts.computeIfAbsent(selected, a -> executionLayout(analysis, a,
							executionFType(a), executionWorkerCount(analysis, a, workers,
								physicalWorkerCounts, executionCounts), inputLayouts));
						runtimeCollect = PlacementCostSemantics.wdivmmInputNeedsCollection(
							consumerHop, layout, edge.inputPosition())
							|| PlacementCostSemantics.ctableInputNeedsCollection(consumerHop, layout, edge.inputPosition());
						if(relocation && runtimeCollect) {
							var action = (RelocationAction)relocationSupply.action();
							var key = new Key(Direction.DOWNLOAD, action.key().materializationFType(),
								BoundaryMode.RUNTIME_RELOCATED_INPUT,
								relocationEmissions.computeIfAbsent(action.key(), RelocationSelections::physicalEmissionIdentity),
								physicalWorkerCounts.count(action.key().durableAnchor()), bytes);
							boolean[] active = new boolean[consumer.alternatives().size()];
							active[value] = true;
							grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
								.add(new Demand(endpoint, consumer, null, active, null));
						}
					}
					if(!analysis.isCoordinatorMetadataOnlyInput(edge)
						&& (cp || relocation || nativeLocal || runtimeCollect && !relocation)) {
						double sourceBytes = bytes;
						if(nativeLocal) {
							var bounded = PlacementCostSemantics.boundedElementwiseNativeLocalInputTransfer(
								analysis, edge.producer(), edge.consumer(), edge.inputPosition(), executionFType(selected),
								executionWorkerCount(analysis, selected, workers, physicalWorkerCounts, executionCounts));
							if(bounded != null) sourceBytes = bounded.logicalBytesUpperBound();
						}
						collectByBytes.computeIfAbsent(sourceBytes,
							ignored -> new boolean[consumer.alternatives().size()])[value] = true;
					}
					if(relocation && ((RelocationAction)relocationSupply.action()).key().sourceValueVersion()
						.equals(producer.node().valueVersion())) {
						var action = (RelocationAction)relocationSupply.action();
						var key = new Key(Direction.UPLOAD, action.key().materializationFType(),
								uploadBoundaryMode(analysis, edge),
								relocationEmissions.computeIfAbsent(action.key(), RelocationSelections::physicalEmissionIdentity),
							physicalWorkerCounts.count(action.key().durableAnchor()), bytes);
						List<Demand> demands = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
						Demand demand = demands.stream().filter(d -> d.edge().equals(endpoint)).findFirst().orElse(null);
						if(demand == null) {
							demand = new Demand(endpoint, consumer, null, new boolean[consumer.alternatives().size()], null);
							demands.add(demand);
						}
						demand.active()[value] = true;
					}
				}
				for(var collect : collectByBytes.entrySet())
					for(Key key : producer.alternatives().stream()
						.filter(a -> a.state().output() == FederatedOutput.FOUT)
						.map(a -> new Key(Direction.DOWNLOAD, a.state().fType(), BoundaryMode.ANCHOR_TRANSFER,
							outputLayouts.computeIfAbsent(a, ExactPhysicalCostModel::outputLayoutIdentity),
							0, collect.getKey())).distinct().toList())
						grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
							.add(new Demand(endpoint, consumer, null, collect.getValue(), null));
			}
			for(EffectiveLogicalFunctionInput input : functionInputsBySource.getOrDefault(
				producer.node().key(), List.of())) {
				if(input.authority().sourceArgument() != producer.node().key()
					|| !input.authority().sourceValueVersion().equals(producer.node().valueVersion())
					|| !retainedFunctionCoverageProven(frequencies, input, producer.node().key()))
					continue;
				ExactPhysicalModel.DecisionDomain formal = domains.get(input.targetRead());
				if(formal == null)
					continue;
				boolean[] collect = new boolean[formal.alternatives().size()];
				for(int value = 0; value < collect.length; value++)
					collect[value] = formal.alternatives().get(value).state().execType() == ExecType.CP;
				var endpoint = new PhysicalTransferEndpoint(producer.node().key(),
					formal.node().key(), input.logicalPosition());
				double functionBytes = physicalLogicalFunctionInputBytes(analysis, sparseAssignments, input,
					producer, formal);
				Map<FType,Boolean> retainedTypeEligibility = new LinkedHashMap<>();
				for(var alternative : producer.alternatives())
					if(alternative.state().output() == FederatedOutput.FOUT
						&& alternative.state().fType() != null)
						retainedTypeEligibility.merge(alternative.state().fType(),
							alternative.relocationAction() == null
								&& alternative.derivedFoutAction() == null
								&& alternative.inputAuthorities().stream().noneMatch(authority ->
									authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION),
							Boolean::logicalAnd);
				for(Key key : producer.alternatives().stream()
					.filter(a -> a.state().output() == FederatedOutput.FOUT
						&& retainedTypeEligibility.getOrDefault(a.state().fType(), false))
					.map(a -> new Key(Direction.DOWNLOAD, a.state().fType(), BoundaryMode.ANCHOR_TRANSFER,
						outputLayouts.computeIfAbsent(a, ExactPhysicalCostModel::outputLayoutIdentity),
						0, functionBytes)).distinct().toList()) {
					grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(new Demand(endpoint, formal, null, collect, input));
					retainedFunctionDownloads.add(new RetainedFunctionDownload(input, key.type()));
				}
			}
			for(var use : fusedFactors) {
				if(use.source() != producer) continue;
				for(Key key : producer.alternatives().stream()
					.filter(a -> a.state().output() == FederatedOutput.FOUT)
					.map(a -> new Key(Direction.DOWNLOAD, a.state().fType(), BoundaryMode.ANCHOR_TRANSFER,
						outputLayouts.computeIfAbsent(a, ExactPhysicalCostModel::outputLayoutIdentity),
						0, bytes)).distinct().toList())
					grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Demand(
						new PhysicalTransferEndpoint(producer.node().key(), use.owner().node().key(), use.input().position()),
						use.owner(), use, null, null));
			}
			List<RuntimeMaterializationSource> creationSources = grouped.isEmpty() ? List.of()
				: runtimeMaterializationSources(analysis, producer.node().key(), transientByRead,
					functionByRead, functionOutputs.sourcesByTarget(),
					functionOutputs.sameContextBoundaries(),
					Collections.newSetFromMap(new IdentityHashMap<>()));
			for(var entry : grouped.entrySet()) {
				List<Demand> demands = entry.getValue().stream().sorted(Comparator
					.comparing((Demand d) -> d.edge().consumer().normalizedSignature())
					.thenComparingInt(d -> d.edge().inputPosition())).toList();
				Key key = entry.getKey();
				boolean sourceDownload = key.direction() == Direction.DOWNLOAD && key.targetWorkers() == 0;
				boolean[] activeSource = new boolean[producer.alternatives().size()];
				boolean[] uploadSource = new boolean[activeSource.length];
				UnitPriceKey priceKey = new UnitPriceKey(key.direction(), key.type(), key.targetWorkers(), key.bytes());
				double[] cachedPrices = unitPriceRows.get(priceKey);
				double[] unitPrices = cachedPrices == null ? new double[activeSource.length] : cachedPrices;
				for(int value = 0; value < activeSource.length; value++) {
					var alternative = producer.alternatives().get(value);
					var state = alternative.state();
					int sourceWorkers = outputWorkerCounts[value];
					if(sourceWorkers == 0)
						outputWorkerCounts[value] = sourceWorkers =
							realizationWorkerCount(analysis, alternative, workers, physicalWorkerCounts);
					activeSource[value] = !sourceDownload || state.output() == FederatedOutput.FOUT
						&& state.fType() == key.type() && outputLayouts.computeIfAbsent(alternative,
							ExactPhysicalCostModel::outputLayoutIdentity).equals(key.physicalEmissionIdentity());
					// Planned FOUT staging is part of the REFED supply action. On a cache miss it
					// collects and uploads once; subsequent executions reuse the resulting copy
					// under the original source/value-version lifetime proven below.
					uploadSource[value] = key.direction() == Direction.UPLOAD
						&& (state.output() == FederatedOutput.LOUT
							|| state.output() == FederatedOutput.FOUT);
					if(cachedPrices != null)
						continue;
					double unit = key.direction() == Direction.DOWNLOAD
						? FederatedCostModel.computeReusableMaterializationDownloadCost(key.bytes(), key.type(),
							sourceDownload ? sourceWorkers : key.targetWorkers())
						: FederatedCostModel.computeUploadNetworkCost(key.bytes(), key.type(), key.targetWorkers());
					if(sourceDownload && alternative.realization() != null
						&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP) {
						var selected = new IdentityHashMap<CompiledHopKey,ExactPhysicalModel.Alternative>();
						selected.put(alternative.decision(), alternative);
						var rows = jointPrices.valueRows(alternative, selected);
						if(!rows.isEmpty())
							unit = JointPhysicalCostRows.expectedUnit(frequencies, alternative.decision(), rows,
								row -> FederatedCostModel.computeReusableMaterializationDownloadCost(key.bytes(), key.type(),
									physicalWorkerCounts.count(row.inputs().get(0).pool())));
						else {
							// A downstream mapped computation can depend on several reader
							// choices. This retained-cache factor uses a bound over all their
							// proven maps, rather than pretending the first pool is universal.
							unit = JointPhysicalCostRows.possiblePools(analysis, alternative.realization(), alternative.supportClause())
								.stream().mapToDouble(pool -> FederatedCostModel.computeReusableMaterializationDownloadCost(
									key.bytes(), key.type(), physicalWorkerCounts.count(pool))).max()
								.orElseThrow(() -> new IllegalArgumentException("JOINT_VALUE_MAP_DOWNLOAD_LAYOUT_UNPROVEN"));
						}
					}
					unitPrices[value] = requireCost(unit, "EXACT_PHYSICAL_MATERIALIZATION_UNIT_UNPROVEN");
				}
				if(cachedPrices == null)
					unitPriceRows.put(priceKey, unitPrices);
				for(var readProfile : exactOccurrenceProfiles(frequencies, producer.node().key())) {
					CompiledHopKey origin = producer.node().key();
					var sourceProfile = readProfile;
					// REFED's cached worker payload is published through a fresh MatrixObject
					// alias in this read's block. Its subsequent GET cannot inherit the
					// original value's longer lifetime; same-emission consumers still union.
					var aliasCreations = new ArrayList<AliasCreation>();
					for(RuntimeMaterializationSource source : creationSources) {
						var profiles = exactOccurrenceProfiles(frequencies, source.occurrence());
						var aliasProfile = materializationCreationProfile(profiles,
							readProfile.contextOrdinal(), callerContexts, source,
							functionOutputs.calleeContextsByBoundary());
						if(aliasProfile != null) {
							var creationScope = new CreationScope(source.occurrence(), aliasProfile,
								aliasProfile.contextOrdinal());
							var activationProfile = guardedCreationProfile(frequencies, source,
								aliasProfile, readProfile.contextOrdinal());
							if(activationProfile != null) {
								aliasCreations.add(new AliasCreation(creationScope, activationProfile));
							}
						}
					}
					if(creationSources.size() == 1 && key.boundary() != BoundaryMode.RUNTIME_RELOCATED_INPUT) {
						CompiledHopKey candidate = creationSources.get(0).occurrence();
						var profiles = exactOccurrenceProfiles(frequencies, candidate);
						var matched = profiles.stream().filter(profile -> profile.contextOrdinal()
							== readProfile.contextOrdinal()).findFirst().orElse(null);
						if(matched == null && creationSources.get(0).returnBoundaries().isEmpty()
							&& profiles.size() == 1)
							matched = profiles.get(0);
						if(matched != null) { origin = candidate; sourceProfile = matched; }
					}
					var loopEpoch = loopPhiSnapshotEpoch(analysis, frequencies,
						producer.node().key(), readProfile, transientByRead, creationSources);
					var creation = new CreationScope(origin, sourceProfile, readProfile.contextOrdinal());
					var epochCreation = loopEpoch == null ? null : new CreationScope(loopEpoch.origin(),
						loopEpoch.profile(), readProfile.contextOrdinal());
					// Fresh outputs retain the existing lifetime calculation. Only proven
					// retained aliases use the ancestor object's activation and creation cap.
					boolean transparentAliasSources = creationSources.size() == 1
						|| creationSources.size() > 1 && creationSources.stream()
							.allMatch(source -> !source.reachabilityGuards().isEmpty());
					// Every upload is owned by its original source/value version. FOUT's local
					// stage is fused into that action rather than treated as a new value lifetime.
					java.util.function.Function<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact,
						List<ActivationDemand>> activationsForCreation = profile -> {
						List<ActivationDemand> activations = new ArrayList<>();
						for(Demand demand : demands) {
							if(demand.functionInput() != null) {
								for(var occurrence : frequencies.exactFunctionBoundaryOccurrences(
									demand.functionInput().authority())) {
									if(occurrence.callerContextOrdinal() != readProfile.contextOrdinal())
										continue;
									var consumerProfile = requireContextProfile(frequencies,
										demand.edge().consumer(), occurrence.calleeProfile().contextOrdinal());
									activations.add(new ActivationDemand(List.of(demand.consumer().variable()),
										List.of(demand.active()), materializationActivation(profile, consumerProfile),
										requiresCrossExecutionReuse(profile, consumerProfile)));
								}
								continue;
							}
							var consumerProfile = requireContextProfile(frequencies,
								demand.edge().consumer(), readProfile.contextOrdinal());
							var event = materializationActivation(profile, consumerProfile);
							boolean crossExecution = requiresCrossExecutionReuse(profile, consumerProfile);
							if(demand.runtime() != null)
								addFusedFactorGetDemands(demand.runtime(), event, crossExecution, activations);
							else activations.add(new ActivationDemand(List.of(demand.consumer().variable()),
								List.of(demand.active()), event, crossExecution));
						}
						return activations;
					};
					if(sourceDownload) {
						Map<DownloadKey,Map<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact,
							boolean[]>> observations = new LinkedHashMap<>();
						for(int value = 0; value < activeSource.length; value++) {
							if(!activeSource[value]) continue;
								var selected = producer.alternatives().get(value);
								var layout = physicalValueLayout(analysis, selected,
									outputWorkerCounts[value], inputLayouts);
							// A unique canonical creation origin and exact direct FED layout prove
							// the same retained MatrixObject across its initializer, TWrite/TRead
							// aliases and function formals. Fresh relocation/derived outputs and
							// runtime-relocated boundaries retain a private identity.
							boolean directOwner = selected.relocationAction() == null
								&& selected.derivedFoutAction() == null && selected.inputAuthorities().stream()
									.noneMatch(a -> a.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION);
							boolean alias = transparentAliasSources
								&& aliasCreations.size() == creationSources.size()
								&& key.boundary() != BoundaryMode.RUNTIME_RELOCATED_INPUT
								&& layout.exactRanges() && !layout.ranges().isEmpty()
								&& selected.state().execType() == ExecType.FED && directOwner;
							boolean epochAlias = epochCreation != null && directOwner
								&& selected.state().execType() == ExecType.FED
								&& layout.exactRanges() && !layout.ranges().isEmpty()
								&& key.boundary() != BoundaryMode.RUNTIME_RELOCATED_INPUT;
							String privateIdentity = alias || epochAlias ? "" : producer.node().key().normalizedSignature()
								+ '|' + key.physicalEmissionIdentity();
							// Function arguments retain the caller's MatrixObject and its local
							// cache. Charge a proven alias in that object's creation context,
							// while fresh/relocated outputs keep their read-context discriminator.
							List<AliasCreation> downloadCreations = epochAlias
								? List.of(new AliasCreation(epochCreation, epochCreation.profile()))
								: alias ? aliasCreations : List.of(new AliasCreation(creation, creation.profile()));
							for(AliasCreation downloadCreation : downloadCreations) {
								var observation = new DownloadKey(downloadCreation.creation(), layout,
									privateIdentity, unitPrices[value]);
								observations.computeIfAbsent(observation, ignored -> new LinkedHashMap<>())
									.computeIfAbsent(downloadCreation.activationProfile(),
										ignored -> new boolean[activeSource.length])[value] = true;
							}
						}
						for(var observation : observations.entrySet()) {
							var group = downloads.computeIfAbsent(observation.getKey(), ignored ->
								new DownloadGroup(producer, new ArrayList<>(), new ArrayList<>()));
							for(var activationObservation : observation.getValue().entrySet()) {
								for(var activation : activationsForCreation.apply(activationObservation.getKey())) {
									List<ExactCategoricalSolver.Variable> vars = new ArrayList<>(activation.variables());
									List<boolean[]> masks = new ArrayList<>(activation.observations());
									vars.add(producer.variable()); masks.add(activationObservation.getValue());
									group.demands().add(new ActivationDemand(vars, masks, activation.event(),
										activation.crossExecutionReuse()));
								}
							}
							for(var demand : demands)
								if(!group.endpoints().contains(demand.edge())) group.endpoints().add(demand.edge());
						}
					}
					else {
						String id = "exact-materialization|" + producer.node().key().normalizedSignature() + '|' + key
							+ "|creation=" + origin.normalizedSignature() + "|context=" + readProfile.contextOrdinal();
						List<ActivationDemand> activations = activationsForCreation.apply(sourceProfile);
						if(key.direction() != Direction.UPLOAD) {
							addMaterializationActivationFactors(id, producer.variable(), activeSource, unitPrices,
								activations, sourceProfile.expectedExecutions(), factors, factorizations, null);
						}
						else {
							boolean[] epochUploadSource = new boolean[uploadSource.length];
							if(epochCreation != null && key.boundary() != BoundaryMode.RUNTIME_RELOCATED_INPUT)
								for(int value = 0; value < uploadSource.length; value++) {
									var selected = producer.alternatives().get(value);
									epochUploadSource[value] = uploadSource[value]
										&& selected.relocationAction() == null && selected.derivedFoutAction() == null
										&& selected.inputAuthorities().stream().noneMatch(authority -> authority.kind()
											== ExactPhysicalModel.InputAuthorityKind.RELOCATION);
								}
							boolean[] ordinaryUploadSource = uploadSource.clone();
							for(int value = 0; value < ordinaryUploadSource.length; value++)
								ordinaryUploadSource[value] &= !epochUploadSource[value];
							if(anyTrue(ordinaryUploadSource))
								addMaterializationActivationFactors(id + "|source=ORIGINAL", producer.variable(),
									ordinaryUploadSource, unitPrices, activations,
									sourceProfile.expectedExecutions(), factors, factorizations, null);
							if(anyTrue(ordinaryUploadSource) && activations.stream()
								.anyMatch(ActivationDemand::crossExecutionReuse))
								supplySharingGroups.add(new SupplySharingGroup(key.physicalEmissionIdentity(),
									producer.variable(), ordinaryUploadSource, activations));
							List<ActivationDemand> epochActivations = epochCreation == null ? List.of()
								: activationsForCreation.apply(epochCreation.profile());
							if(anyTrue(epochUploadSource))
								addMaterializationActivationFactors(id + "|source=LOOP_EPOCH", producer.variable(),
									epochUploadSource, unitPrices, epochActivations,
									epochCreation.profile().expectedExecutions(), factors, factorizations, null);
							if(anyTrue(epochUploadSource) && epochActivations.stream()
								.anyMatch(ActivationDemand::crossExecutionReuse))
								supplySharingGroups.add(new SupplySharingGroup(key.physicalEmissionIdentity(),
									producer.variable(), epochUploadSource, epochActivations));
						}
					}
				}
				if(!sourceDownload) transferKeys.add(new PhysicalTransferKey(producer.node().valueVersion(),
					demands.stream().map(Demand::edge).distinct().toList(), key.direction(), key.type(), key.boundary(),
					key.physicalEmissionIdentity()));
			}
		}
		for(var entry : downloads.entrySet()) {
			var key = entry.getKey(); var group = entry.getValue();
			double[] prices = new double[group.representative().alternatives().size()];
			java.util.Arrays.fill(prices, key.unit());
			String identity = "exact-get|" + key.creation().origin().normalizedSignature()
				+ "|context=" + key.creation().contextOrdinal() + "|layout=" + key.layout()
				+ "|private=" + key.privateIdentity() + "|unit=" + key.unit() + "|group=" + transferKeys.size();
			addMaterializationActivationFactors(identity, group.representative().variable(), allTrue(prices.length), prices,
				group.demands(), key.creation().profile().expectedExecutions(), factors, factorizations, null);
			transferKeys.add(new PhysicalTransferKey(group.representative().node().valueVersion(), group.endpoints(),
				Direction.DOWNLOAD, key.layout().fType(), BoundaryMode.ANCHOR_TRANSFER, identity));
		}
	}

	/** Resolve the actual creating invocation, never a sibling call of the same source HOP. */
	private static OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact materializationCreationProfile(
		List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> profiles, long readContext,
		Map<Long,Long> callerContexts, RuntimeMaterializationSource source,
		IdentityHashMap<CompiledHopKey,Map<Long,Long>> calleeContextsByBoundary) {
		Long context = readContext;
		for(int depth = 0; context != null && depth <= callerContexts.size(); depth++) {
			for(var profile : profiles)
				if(profile.contextOrdinal() == context)
					return profile;
			context = callerContexts.get(context);
		}
		context = readContext;
		for(CompiledHopKey boundary : source.returnBoundaries()) {
			Map<Long,Long> callees = calleeContextsByBoundary.get(boundary);
			if(callees == null || (context = callees.get(context)) == null)
				return null;
		}
		for(var profile : profiles)
			if(profile.contextOrdinal() == context)
				return profile;
		return null;
	}

	/**
	 * Keep the lifetime of the object that owns the cache, while retaining the branch
	 * predicates under which a transparent carrier selected that object at the join.
	 */
	private static OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact guardedCreationProfile(
		OccurrenceExecutionFrequencyFacts frequencies, RuntimeMaterializationSource source,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact creation, long readContext) {
		double expected = creation.expectedExecutions();
		Set<Long> creationLoops = creation.loopContext().stream().map(Pair::getLeft)
			.collect(java.util.stream.Collectors.toSet());
		Map<String,OccurrenceExecutionFrequencyFacts.BranchActivationFact> conditions =
			new LinkedHashMap<>();
		for(var condition : creation.activationConditions())
			conditions.put(condition.decisionKey(), condition);
		for(CompiledHopKey guard : source.reachabilityGuards()) {
			List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> profiles =
				exactOccurrenceProfiles(frequencies, guard);
			var profile = profiles.stream().filter(candidate ->
				candidate.contextOrdinal() == readContext).findFirst().orElse(null);
			if(profile == null && profiles.size() == 1)
				profile = profiles.get(0);
			if(profile == null)
				return null;
			for(var condition : profile.activationConditions()) {
				var previous = conditions.putIfAbsent(condition.decisionKey(), condition);
				if(previous != null && previous.ifArm() != condition.ifArm())
					return null;
				if(previous == null && creationLoops.containsAll(condition.enclosingLoopIds()))
					expected *= condition.probability();
			}
		}
		return new OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact(expected,
			creation.loopContext(), creation.contextOrdinal(), List.copyOf(conditions.values()));
	}

	private static FType executionFType(ExactPhysicalModel.Alternative selected) {
		CandidateEmissionFact emission = selected.captured() ? selected.candidateEmission() : selected.executionEmission();
		return emission == null ? selected.state().fType() : emission.executionFType();
	}

	/** Union these runtime reads with ordinary CP consumers of the same selected value/layout. */
	private static void addFusedFactorGetDemands(FusedFactorUse use,
		ExactMaterializationActivation.Event event, boolean crossExecutionReuse,
		List<ActivationDemand> demands) {
		boolean[] cp = new boolean[use.owner().alternatives().size()];
		boolean[] fed = new boolean[cp.length];
		for(int i = 0; i < cp.length; i++) {
			cp[i] = use.owner().alternatives().get(i).state().execType() == ExecType.CP;
			fed[i] = use.owner().alternatives().get(i).state().execType() == ExecType.FED;
		}
		demands.add(new ActivationDemand(List.of(use.owner().variable()), List.of(cp), event,
			crossExecutionReuse));
		Map<InputLayout,boolean[]> weightClasses = new LinkedHashMap<>();
		for(int w = 0; w < use.weightLayouts().size(); w++) {
			InputLayout layout = use.weightLayouts().get(w);
			if(layout.fType() != null)
				weightClasses.computeIfAbsent(layout, ignored -> new boolean[use.weightLayouts().size()])[w] = true;
		}
		for(var entry : weightClasses.entrySet()) {
			boolean[] collect = new boolean[use.source().alternatives().size()];
			for(int f = 0; f < collect.length; f++)
				collect[f] = !use.reused(entry.getKey(), f);
			demands.add(new ActivationDemand(List.of(use.owner().variable(), use.weights().variable(),
				use.source().variable()), List.of(fed, entry.getValue(), collect), event,
				crossExecutionReuse));
		}
	}

	/** A selected plan demands a copy when every observation in this demand is true. */
	record ActivationDemand(List<ExactCategoricalSolver.Variable> variables,
		List<boolean[]> observations, ExactMaterializationActivation.Event event,
		boolean crossExecutionReuse) {
		ActivationDemand(List<ExactCategoricalSolver.Variable> variables,
			List<boolean[]> observations, ExactMaterializationActivation.Event event) {
			this(variables, observations, event, false);
		}

		ActivationDemand {
			variables = List.copyOf(variables);
			observations = observations.stream().map(boolean[]::clone).toList();
			if(variables.isEmpty() || variables.size() != observations.size())
				throw new IllegalArgumentException("EXACT_ACTIVATION_OBSERVATION_INVALID");
			for(int index = 0; index < variables.size(); index++)
				if(variables.get(index).domainSize() != observations.get(index).length)
					throw new IllegalArgumentException("EXACT_ACTIVATION_OBSERVATION_DOMAIN_INVALID");
		}

		boolean active(int[] values, IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions) {
			for(int index = 0; index < variables.size(); index++)
				if(!observations.get(index)[values[positions.get(variables.get(index))]])
					return false;
			return true;
		}
	}

	/** Raw profile relation retained separately from the source-capped cost event. */
	static boolean requiresCrossExecutionReuse(
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact source,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact consumer) {
		Set<Long> sourceLoops = source.loopContext().stream().map(Pair::getLeft)
			.collect(java.util.stream.Collectors.toSet());
		for(var loop : consumer.loopContext())
			if(!sourceLoops.contains(loop.getLeft()) && loop.getRight() > 1d)
				return true;
		double sourceExecutions = source.expectedExecutions();
		double consumerExecutions = consumer.expectedExecutions();
		if(!Double.isFinite(sourceExecutions) || !Double.isFinite(consumerExecutions)
			|| sourceExecutions <= 0d || consumerExecutions <= 0d)
			return false;
		double tolerance = Math.max(Math.ulp(sourceExecutions) * 4d, 1e-12d);
		return consumerExecutions > sourceExecutions + tolerance;
	}

	/**
	 * Project a demand onto one source creation lifetime. Source conditions include
	 * reaching-path guards from transparent aliases; retaining them lets mutually
	 * exclusive branch origins form an exact union. Conditions inside loops that do not
	 * create the source can occur on both arms during one cache lifetime, so they receive
	 * distinct repeated-event identities instead of a false exclusivity proof.
	 */
	static ExactMaterializationActivation.Event materializationActivation(
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact source,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact consumer) {
		Set<Long> sourceLoops = source.loopContext().stream().map(Pair::getLeft)
			.collect(java.util.stream.Collectors.toSet());
		double cap = source.expectedExecutions();
		List<BranchLiteral> conditions = new ArrayList<>();
		for(var condition : source.activationConditions()) {
			boolean repeated = !sourceLoops.containsAll(condition.enclosingLoopIds());
			conditions.add(repeated
				? new BranchLiteral(condition.decisionKey()
					+ "|repeated-arm=" + condition.ifArm(), true)
				: new BranchLiteral(condition.decisionKey(), condition.ifArm()));
		}
		for(var condition : consumer.activationConditions()) {
			var sourceCondition = source.activationConditions().stream()
				.filter(candidate -> candidate.decisionKey().equals(condition.decisionKey()))
				.findFirst().orElse(null);
			if(sourceCondition != null) {
				boolean repeated = !sourceLoops.containsAll(condition.enclosingLoopIds());
				if(sourceCondition.ifArm() != condition.ifArm() && !repeated)
					return new ExactMaterializationActivation.Event(0d, List.of());
				if(sourceCondition.ifArm() != condition.ifArm())
					conditions.add(new BranchLiteral(condition.decisionKey()
						+ "|repeated-arm=" + condition.ifArm(), true));
				continue;
			}
			boolean repeated = !sourceLoops.containsAll(condition.enclosingLoopIds());
			if(repeated)
				conditions.add(new BranchLiteral(condition.decisionKey()
					+ "|repeated-arm=" + condition.ifArm(), true));
			else {
				cap *= condition.probability();
				conditions.add(new BranchLiteral(condition.decisionKey(), condition.ifArm()));
			}
		}
		return new ExactMaterializationActivation.Event(
			Math.min(cap, consumer.expectedExecutions()), conditions);
	}

	static void addMaterializationActivationFactors(String key,
		ExactCategoricalSolver.Variable source, boolean[] activeSource, double[] unitPrices,
		List<ActivationDemand> demands, double scopeWeight,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds) {
		List<ExactMaterializationActivation.Event> events = demands.stream().map(ActivationDemand::event).toList();
		var partition = ExactMaterializationActivation.partition(events, scopeWeight);
		// This digest is byte-for-byte the SHA-256 of the former V1 descriptor. Stream
		// observation masks because one GLM source can own millions of Boolean entries.
		String descriptor = "MATERIALIZATION_ACTIVATION_V2|semanticSha256="
			+ materializationActivationSemanticDigest(key, source, activeSource, unitPrices,
				demands, partition.semanticDescriptor());
		if(!partition.resolved()) {
			List<ExactCategoricalSolver.Variable> scope = activationScope(source, demands);
			var positions = variablePositions(scope);
			var conservative = ExactMaterializationActivation.prepareConservativeUnion(
				events, scopeWeight);
			ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(scope, values -> {
				int sourceValue = values[positions.get(source)];
				if(!activeSource[sourceValue])
					return 0d;
				boolean[] active = new boolean[demands.size()];
				for(int index = 0; index < active.length; index++)
					active[index] = demands.get(index).active(values, positions);
				return requireCost(unitPrices[sourceValue]
					* conservative.evaluateOwned(active),
					"EXACT_ACTIVATION_UNION_COST_UNPROVEN");
			});
			factors.add(canonical);
			Map<ExactMaterializationActivation.Event,List<ActivationDemand>> byEvent = new LinkedHashMap<>();
			for(var demand : demands)
				byEvent.computeIfAbsent(demand.event(), ignored -> new ArrayList<>()).add(demand);
			List<ExactCategoricalSolver.Variable> auxiliaries = new ArrayList<>();
			List<ExactCategoricalSolver.Factor> encoded = new ArrayList<>();
			List<ExactCategoricalSolver.Variable> monetaryScope = new ArrayList<>(List.of(source));
			int eventOrdinal = 0;
			for(var members : byEvent.values()) {
				String eventKey = key + "|event=" + eventOrdinal++;
				List<ExactCategoricalSolver.Variable> activeDemands = new ArrayList<>();
				for(int d = 0; d < members.size(); d++) {
					var demand = members.get(d);
					activeDemands.add(activationIndicator(eventKey + "|demand=" + d,
						demand.variables(), demand.observations(), true, auxiliaries, encoded));
				}
				monetaryScope.add(activationIndicator(eventKey, activeDemands,
					activeDemands.stream().map(ignored -> new boolean[] {false, true}).toList(),
					false, auxiliaries, encoded));
			}
			List<ExactMaterializationActivation.Event> uniqueEvents = List.copyOf(byEvent.keySet());
			var uniqueConservative = ExactMaterializationActivation.prepareConservativeUnion(
				uniqueEvents, scopeWeight);
			var monetary = ExactCategoricalSolver.Factor.lazy(monetaryScope, values -> {
				if(!activeSource[values[0]]) return 0d;
				boolean[] active = new boolean[uniqueEvents.size()];
				for(int e = 0; e < active.length; e++) active[e] = values[e + 1] != 0;
				return requireCost(unitPrices[values[0]]
					* uniqueConservative.evaluateOwned(active),
					"EXACT_ACTIVATION_UNION_COST_UNPROVEN");
			});
			encoded.add(monetary);
			factorizations.put(canonical, new SolverFactorization(auxiliaries, encoded,
				descriptor + "|encoding=CONSERVATIVE_EVENT_QUOTIENT_V1|events=" + uniqueEvents,
				List.of(monetary), null, new CostTransportDeclaration.OneMonetaryTable(monetary)));
			if(factorKinds != null)
				factorKinds.put(canonical, "RUNTIME_FUSED_INPUT|CONSERVATIVE_UNION");
			return;
		}
		int ordinal = 0;
		for(var activationClass : partition.classes()) {
			String classKey = key + "|activation-class=" + ordinal++;
			List<ActivationDemand> members = activationClass.demandIndexes().stream().map(demands::get).toList();
			double[] prices = new double[unitPrices.length];
			for(int index = 0; index < prices.length; index++)
				prices[index] = requireCost(activationClass.multiplicity() * unitPrices[index],
					"EXACT_ACTIVATION_CLASS_PRICE_UNPROVEN");
			List<ExactCategoricalSolver.Variable> scope = activationScope(source, members);
			var positions = variablePositions(scope);
			ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(scope, values -> {
				int sourceValue = values[positions.get(source)];
				return activeSource[sourceValue] && members.stream().anyMatch(demand -> demand.active(values, positions))
					? prices[sourceValue] : 0d;
			});
			List<ExactCategoricalSolver.Variable> auxiliaries = new ArrayList<>();
			List<ExactCategoricalSolver.Factor> solverFactors = new ArrayList<>();
			List<ExactActivationClassFactorDecomposition.Demand> observations = new ArrayList<>();
			for(int index = 0; index < members.size(); index++) {
				ActivationDemand demand = members.get(index);
				if(demand.variables().size() == 1)
					observations.add(new ExactActivationClassFactorDecomposition.Demand(
						demand.variables().get(0), demand.observations().get(0)));
				else {
					ExactCategoricalSolver.Variable active = activationIndicator(classKey + "|demand=" + index,
						demand.variables(), demand.observations(), true, auxiliaries, solverFactors);
					observations.add(new ExactActivationClassFactorDecomposition.Demand(active,
						new boolean[] {false, true}));
				}
			}
			var decomposition = ExactActivationClassFactorDecomposition.create(classKey, source,
				activeSource, prices, observations);
			auxiliaries.addAll(decomposition.auxiliaryVariables());
			solverFactors.addAll(decomposition.solverFactors());
			factors.add(canonical);
			ExactCategoricalSolver.Factor monetary = decomposition.monetaryFactor();
			factorizations.put(canonical, new SolverFactorization(auxiliaries, solverFactors,
				descriptor + "|class=" + activationClass + '|' + decomposition.semanticDescriptor(),
				monetary == null ? List.of() : List.of(monetary), null,
				monetary == null ? ZeroCostTransport.INSTANCE
					: new CostTransportDeclaration.OneMonetaryTable(monetary)));
			if(factorKinds != null)
				factorKinds.put(canonical, "RUNTIME_FUSED_INPUT|ACTIVATION_CLASS");
		}
		// Even a zero-cost group binds its scope, event facts and prices in the receipt.
		if(partition.classes().isEmpty()) {
			var canonical = ExactCategoricalSolver.Factor.lazy(activationScope(source, demands), values -> 0d);
			factors.add(canonical);
			factorizations.put(canonical, new SolverFactorization(List.of(), List.of(),
				descriptor, List.of(), null, ZeroCostTransport.INSTANCE));
			if(factorKinds != null)
				factorKinds.put(canonical, "RUNTIME_FUSED_INPUT|ACTIVATION_CLASS");
		}
	}

	static String materializationActivationSemanticDigestForTest(String key,
		ExactCategoricalSolver.Variable source, boolean[] activeSource, double[] unitPrices,
		List<ActivationDemand> demands, double scopeWeight) {
		List<ExactMaterializationActivation.Event> events = demands.stream()
			.map(ActivationDemand::event).toList();
		return materializationActivationSemanticDigest(key, source, activeSource, unitPrices,
			demands, ExactMaterializationActivation.partition(events, scopeWeight).semanticDescriptor());
	}

	private static String materializationActivationSemanticDigest(String key,
		ExactCategoricalSolver.Variable source, boolean[] activeSource, double[] unitPrices,
		List<ActivationDemand> demands, String partitionDescriptor) {
		FingerprintWriter descriptor = new FingerprintWriter();
		descriptor.append("MATERIALIZATION_ACTIVATION_V1|").append(key).append('|')
			.append(partitionDescriptor).append("|source=").append(source.key())
			.append("|sourceActive=").appendBooleanArray(activeSource);
		for(double unit : unitPrices)
			descriptor.append("|unitBits=")
				.append(Long.toUnsignedString(Double.doubleToRawLongBits(unit), 16));
		for(ActivationDemand demand : demands)
			for(int index = 0; index < demand.variables().size(); index++)
				descriptor.append("|observation=").append(demand.variables().get(index).key())
					.append(':').appendBooleanArray(demand.observations().get(index));
		return descriptor.finish();
	}

	/**
	 * Compatibility entry point for the bounded function-alias encoding introduced on main.
	 * The unified cost model now uses the bounded event quotient for every unresolved union,
	 * so the former call-site switch no longer changes the encoding.
	 */
	private static void addMaterializationActivationFactors(String key,
		ExactCategoricalSolver.Variable source, boolean[] activeSource, double[] unitPrices,
		List<ActivationDemand> demands, double scopeWeight,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds,
		boolean boundedConservativeUnion) {
		addMaterializationActivationFactors(key, source, activeSource, unitPrices, demands,
			scopeWeight, factors, factorizations, factorKinds);
	}

	/** Deterministic Boolean observation chain; no categorical Cartesian product. */
	private static ExactCategoricalSolver.Variable activationIndicator(String key,
		List<ExactCategoricalSolver.Variable> variables, List<boolean[]> masks, boolean conjunction,
		List<ExactCategoricalSolver.Variable> auxiliaries, List<ExactCategoricalSolver.Factor> factors) {
		ExactCategoricalSolver.Variable previous = null;
		for(int i = 0; i < variables.size(); i++) {
			var observed = variables.get(i);
			boolean[] mask = masks.get(i);
			var next = new ExactCategoricalSolver.Variable(key + "|observed=" + i, 2);
			auxiliaries.add(next);
			if(previous == null)
				factors.add(ExactCategoricalSolver.Factor.lazy(List.of(observed, next), values ->
					values[1] == (mask[values[0]] ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY));
			else {
				var prior = previous;
				factors.add(ExactCategoricalSolver.Factor.lazy(List.of(observed, prior, next), values -> {
					boolean active = conjunction ? mask[values[0]] && values[1] != 0 : mask[values[0]] || values[1] != 0;
					return values[2] == (active ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY;
				}));
			}
			previous = next;
		}
		return Objects.requireNonNull(previous);
	}

	private static List<ExactCategoricalSolver.Variable> activationScope(
		ExactCategoricalSolver.Variable source, List<ActivationDemand> demands) {
		List<ExactCategoricalSolver.Variable> scope = new ArrayList<>();
		scope.add(source);
		for(ActivationDemand demand : demands)
			for(var variable : demand.variables())
				if(scope.stream().noneMatch(existing -> existing == variable))
					scope.add(variable);
		return List.copyOf(scope);
	}

	private static IdentityHashMap<ExactCategoricalSolver.Variable,Integer> variablePositions(
		List<ExactCategoricalSolver.Variable> scope) {
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		for(int index = 0; index < scope.size(); index++)
			positions.put(scope.get(index), index);
		return positions;
	}

	/**
	 * Price the real weights input of a transpose-pair WDivMM after suppressing
	 * the three source-level edges removed by dynamic lowering. Different TRead
	 * occurrences of one logical value share the source MatrixObject and therefore
	 * share one reusable coordinator materialization per source production.
	 */
	private static void addPhysicalLatentWdivmmRuntimeInputFactors(
		PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations,
		List<PhysicalTransferKey> transferKeys) {
		record Source(CompiledHopKey occurrence, ValueVersionKey valueVersion,
			long contextOrdinal, double productionWeight) { }

		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead =
			new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : analysis.logicalTransientInputsInCanonicalOrder())
			transientByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>())
				.add(fact);
		IdentityHashMap<CompiledHopKey,List<LogicalFunctionInputFact>> functionByRead =
			new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : analysis.logicalFunctionInputsInCanonicalOrder())
			functionByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>())
				.add(fact);

		Map<Source,List<RuntimeMaterializationDemand>> demandsBySource = new LinkedHashMap<>();
		for(ExactPhysicalModel.DecisionDomain owner : orderedDomains) {
			PlacementCostSemantics.LatentWdivmmTransposePairFact runtime =
				PlacementCostSemantics.latentWdivmmTransposePairFact(
					analysis, owner.node().key());
			var direct = runtime == null ? PlacementCostSemantics.directWdivmmRuntimeFact(analysis, owner.node().key()) : null;
			if(runtime == null && direct == null)
				continue;
			CompiledHopKey weightsKey = runtime == null ? direct.weights() : runtime.weights();
			ExactPhysicalModel.DecisionDomain read = domains.get(weightsKey);
			if(read == null)
				throw new IllegalArgumentException(
					"EXACT_LATENT_WDIVMM_RUNTIME_INPUT_DOMAIN_MISSING|owner="
						+ owner.node().key().normalizedSignature());
			List<RuntimeMaterializationSource> sources = runtimeMaterializationSources(
				analysis, weightsKey, transientByRead, functionByRead,
				new IdentityHashMap<>(), Collections.newSetFromMap(new IdentityHashMap<>()),
				Collections.newSetFromMap(new IdentityHashMap<>()));
			if(sources.isEmpty())
				throw new IllegalArgumentException("EXACT_LATENT_WDIVMM_RUNTIME_SOURCE_CYCLE|read="
					+ weightsKey.normalizedSignature());
			for(RuntimeMaterializationSource sourceFact : sources) {
				if(!domains.containsKey(sourceFact.occurrence()))
					throw new IllegalArgumentException(
						"EXACT_LATENT_WDIVMM_RUNTIME_SOURCE_DOMAIN_MISSING|source="
							+ sourceFact.occurrence().normalizedSignature());
				List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> sourceProfiles =
					exactOccurrenceProfiles(frequencies, sourceFact.occurrence());
				for(OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact ownerProfile
						: exactOccurrenceProfiles(frequencies, owner.node().key())) {
					OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact sourceProfile =
						sourceProfiles.stream().filter(profile -> profile.contextOrdinal()
							== ownerProfile.contextOrdinal()).findFirst().orElse(null);
					if(sourceProfile == null && sourceProfiles.size() == 1)
						sourceProfile = sourceProfiles.get(0);
					if(sourceProfile == null)
						throw new IllegalArgumentException(
							"EXACT_LATENT_WDIVMM_SOURCE_CONTEXT_UNMATCHED|source="
								+ sourceFact.occurrence().normalizedSignature()
								+ "|owner=" + owner.node().key().normalizedSignature()
								+ "|context=" + ownerProfile.contextOrdinal());
					var activation = materializationActivation(sourceProfile, ownerProfile);
					Source key = new Source(sourceFact.occurrence(), sourceFact.valueVersion(),
						sourceProfile.contextOrdinal(), sourceProfile.expectedExecutions());
					demandsBySource.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(new RuntimeMaterializationDemand(owner, read, activation.weight(),
							activation.conditions()));
				}
			}
		}

		List<Map.Entry<Source,List<RuntimeMaterializationDemand>>> groups =
			new ArrayList<>(demandsBySource.entrySet());
		groups.sort(Comparator
			.comparing((Map.Entry<Source,List<RuntimeMaterializationDemand>> entry) ->
				entry.getKey().occurrence().normalizedSignature())
			.thenComparing(entry -> entry.getKey().valueVersion().normalizedSignature())
			.thenComparingLong(entry -> entry.getKey().contextOrdinal()));
		for(Map.Entry<Source,List<RuntimeMaterializationDemand>> entry : groups) {
			Source sourceKey = entry.getKey();
			ExactPhysicalModel.DecisionDomain source = domains.get(sourceKey.occurrence());
			List<RuntimeMaterializationDemand> demands = entry.getValue().stream()
				.sorted(Comparator
					.comparing((RuntimeMaterializationDemand demand) ->
						demand.owner().node().key().normalizedSignature())
					.thenComparing(demand ->
						demand.read().node().key().normalizedSignature()))
				.toList();
			Hop sourceHop = analysis.hop(sourceKey.occurrence()).orElseThrow(() ->
				new IllegalArgumentException(
					"EXACT_LATENT_WDIVMM_RUNTIME_SOURCE_HOP_MISSING|source="
						+ sourceKey.occurrence().normalizedSignature()));
			double bytes = effectiveOutputBytes(analysis, sparseAssignments,
				sourceKey.occurrence(), sourceHop);
			double productionWeight = sourceKey.productionWeight();

			List<ActivationDemand> activations = new ArrayList<>();
			Set<ExactCategoricalSolver.Variable> compatibleReads =
				Collections.newSetFromMap(new IdentityHashMap<>());
			PlacementState cpOwner = null;
			for(RuntimeMaterializationDemand demand : demands) {
				if(compatibleReads.add(demand.read().variable())) {
					// Feasibility survives zero-frequency costing and every class split.
					var compatibility = runtimeReadCompatibilityFactor(source, demand.read());
					factors.add(compatibility);
					factorKinds.put(compatibility, "RUNTIME_FUSED_INPUT_COMPATIBILITY");
				}
				boolean[] owners = new boolean[demand.owner().alternatives().size()];
				for(int index = 0; index < owners.length; index++) {
					PlacementState state = demand.owner().alternatives().get(index).state();
					owners[index] = state.execType() == ExecType.CP;
					if(owners[index])
						cpOwner = state;
				}
				boolean[] reads = new boolean[demand.read().alternatives().size()];
				for(int index = 0; index < reads.length; index++)
					reads[index] = demand.read().alternatives().get(index).state().output() == FederatedOutput.FOUT;
				activations.add(new ActivationDemand(List.of(demand.owner().variable(), demand.read().variable()),
					List.of(owners, reads), new ExactMaterializationActivation.Event(
						demand.activationWeight(), demand.branchLiterals())));
			}
			double[] unitPrices = new double[source.alternatives().size()];
			PhysicalWorkerCounts sourceCounts = new PhysicalWorkerCounts();
			for(int index = 0; index < unitPrices.length; index++)
				unitPrices[index] = cpOwner == null ? 0d : requireCost(PlacementCostSemantics
					.latentWdivmmCpRuntimeInputMaterializationCost(bytes, cpOwner,
						source.alternatives().get(index).state(), realizationWorkerCount(
							analysis, source.alternatives().get(index), workers, sourceCounts)),
					"EXACT_LATENT_WDIVMM_RUNTIME_INPUT_UNIT_UNPROVEN");
			addMaterializationActivationFactors("exact-runtime-input|"
				+ sourceKey.occurrence().normalizedSignature() + '|' + sourceKey.valueVersion().normalizedSignature()
				+ "|context=" + sourceKey.contextOrdinal(), source.variable(), allTrue(unitPrices.length),
				unitPrices, activations, productionWeight, factors, factorizations, factorKinds);

			List<PhysicalTransferEndpoint> endpoints = demands.stream()
				.map(demand -> new PhysicalTransferEndpoint(sourceKey.occurrence(),
					demand.owner().node().key(), 0)).distinct().toList();
			for(FType type : source.alternatives().stream()
				.map(ExactPhysicalModel.Alternative::state)
				.filter(state -> state.output() == FederatedOutput.FOUT
					&& state.fType() != null)
				.map(PlacementState::fType).distinct().sorted().toList())
				transferKeys.add(new PhysicalTransferKey(sourceKey.valueVersion(), endpoints,
					Direction.DOWNLOAD, type, BoundaryMode.RUNTIME_FUSED_INPUT));
		}
	}

	private static ExactCategoricalSolver.Factor runtimeReadCompatibilityFactor(
		ExactPhysicalModel.DecisionDomain source, ExactPhysicalModel.DecisionDomain read) {
		boolean same = source.variable() == read.variable();
		List<ExactCategoricalSolver.Variable> scope = same
			? List.of(source.variable()) : List.of(source.variable(), read.variable());
		return ExactCategoricalSolver.Factor.lazy(scope, values -> {
			PlacementState sourceState = source.alternatives().get(values[0]).state();
			PlacementState readState = read.alternatives().get(same ? values[0] : values[1]).state();
			return readState.output() != FederatedOutput.FOUT
				|| sourceState.output() == FederatedOutput.FOUT
					&& sourceState.fType() == readState.fType()
				? 0d : Double.POSITIVE_INFINITY;
		});
	}

	private static boolean[] allTrue(int size) {
		boolean[] values = new boolean[size];
		java.util.Arrays.fill(values, true);
		return values;
	}

	private static boolean anyTrue(boolean[] values) {
		for(boolean value : values)
			if(value)
				return true;
		return false;
	}

	private static List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact>
			exactOccurrenceProfiles(OccurrenceExecutionFrequencyFacts frequencies,
			CompiledHopKey key) {
		return frequencies.exactProfiles(key);
	}

	private static OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact
			requireContextProfile(OccurrenceExecutionFrequencyFacts frequencies,
			CompiledHopKey key, long contextOrdinal) {
		return exactOccurrenceProfiles(frequencies, key).stream()
			.filter(profile -> profile.contextOrdinal() == contextOrdinal)
			.findFirst().orElseThrow(() -> new IllegalArgumentException(
				"EXACT_LATENT_WDIVMM_CONTEXT_UNMATCHED|key="
					+ key.normalizedSignature() + "|context=" + contextOrdinal));
	}

	private record RuntimeMaterializationSource(CompiledHopKey occurrence,
		ValueVersionKey valueVersion, List<CompiledHopKey> returnBoundaries,
		List<CompiledHopKey> reachabilityGuards, List<CompiledHopKey> loopPhiCarriers) {
		private RuntimeMaterializationSource {
			returnBoundaries = List.copyOf(returnBoundaries);
			reachabilityGuards = List.copyOf(reachabilityGuards);
			loopPhiCarriers = List.copyOf(loopPhiCarriers);
		}
	}
	private record LoopPhiSnapshotEpoch(CompiledHopKey origin,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact profile) { }

	/**
	 * A transparent snapshot assignment inside an outer recurrence publishes exactly
	 * one MatrixObject value per recurrence epoch. Its consumers may execute in a
	 * nested loop, but the REFED cache key follows that object/version and therefore
	 * creates one remote copy per snapshot assignment, not one per nested use.
	 *
	 * <p>This proof intentionally accepts only the canonical entry-plus-one-backedge
	 * loop PHI shape. Branch-selected backedges, function boundaries, ambiguous
	 * contexts, and direct PHI reads retain the conservative read profile.</p>
	 */
	private static LoopPhiSnapshotEpoch loopPhiSnapshotEpoch(PlacementAnalysis analysis,
		OccurrenceExecutionFrequencyFacts frequencies, CompiledHopKey read,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact readProfile,
		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead,
		List<RuntimeMaterializationSource> creationSources) {
		if(creationSources.size() != 2 || creationSources.stream().anyMatch(source ->
			!source.returnBoundaries().isEmpty() || !source.reachabilityGuards().isEmpty()))
			return null;
		List<LogicalTransientInputFact> snapshotInputs = transientByRead.getOrDefault(read, List.of());
		if(snapshotInputs.size() != 1)
			return null;
		CompiledHopKey snapshotWrite = snapshotInputs.get(0).sourceWrite();
		List<CompiledHopKey> carrierReads = creationSources.get(0).loopPhiCarriers();
		if(carrierReads.size() != 1 || creationSources.stream()
			.anyMatch(source -> !source.loopPhiCarriers().equals(carrierReads)))
			return null;
		List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> snapshotProfiles =
			exactOccurrenceProfiles(frequencies, snapshotWrite).stream()
				.filter(profile -> profile.contextOrdinal() == readProfile.contextOrdinal()).toList();
		if(snapshotProfiles.size() != 1)
			return null;
		var snapshot = snapshotProfiles.get(0);
		boolean carrierProfilesMatch = carrierReads.stream().allMatch(carrierRead -> {
			List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> profiles =
				exactOccurrenceProfiles(frequencies, carrierRead).stream()
					.filter(profile -> profile.contextOrdinal() == readProfile.contextOrdinal()).toList();
			return profiles.size() == 1
				&& snapshot.loopContext().equals(profiles.get(0).loopContext())
				&& snapshot.activationConditions().equals(profiles.get(0).activationConditions())
				&& Double.doubleToLongBits(snapshot.expectedExecutions())
					== Double.doubleToLongBits(profiles.get(0).expectedExecutions());
		});
		if(!carrierProfilesMatch || snapshot.loopContext().size() >= readProfile.loopContext().size()
			|| !readProfile.loopContext().subList(0, snapshot.loopContext().size())
				.equals(snapshot.loopContext())
			|| snapshot.expectedExecutions() <= 0d
			|| readProfile.expectedExecutions() < snapshot.expectedExecutions())
			return null;
		return new LoopPhiSnapshotEpoch(snapshotWrite, snapshot);
	}

	private record FunctionOutputAliases(
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> sourcesByTarget,
		IdentityHashMap<CompiledHopKey,Map<Long,Long>> calleeContextsByBoundary,
		Set<CompiledHopKey> sameContextBoundaries) { }

	private static FunctionOutputAliases functionOutputAliases(PlacementAnalysis analysis,
		OccurrenceExecutionFrequencyFacts frequencies) {
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> sourcesByTarget = new IdentityHashMap<>();
		Set<CompiledHopKey> sameContextBoundaries = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints()) {
			boolean functionResult = constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.evidence().startsWith("function-result:");
			boolean inlinedFunctionResult = constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.evidence().startsWith("inlined-function-result:");
			boolean callerBinding = constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& constraint.evidence().startsWith("cfg-function-output-value:");
			if(!functionResult && !inlinedFunctionResult && !callerBinding)
				continue;
			sourcesByTarget.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
				.add(constraint.left());
			if(inlinedFunctionResult)
				sameContextBoundaries.add(constraint.right());
		}
		for(var entry : sourcesByTarget.entrySet())
			entry.setValue(entry.getValue().stream().distinct().sorted().toList());

		IdentityHashMap<CompiledHopKey,Map<Long,Long>> calleeContexts = new IdentityHashMap<>();
		for(CompiledHopKey boundary : sourcesByTarget.keySet()) {
			if(analysis.graph().node(boundary).orElseThrow().kind() != NodeKind.FUNCTION_OUTPUT)
				continue;
			Hop call = analysis.hop(boundary).orElse(null);
			Map<Long,Long> byCaller = new LinkedHashMap<>();
			boolean ambiguous = false;
			for(LogicalFunctionInputFact input : analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(analysis.hop(input.boundary()).orElse(null) != call)
					continue;
				for(var occurrence : frequencies.exactFunctionBoundaryOccurrences(input)) {
					Long previous = byCaller.putIfAbsent(occurrence.callerContextOrdinal(),
						occurrence.calleeProfile().contextOrdinal());
					if(previous != null && previous != occurrence.calleeProfile().contextOrdinal())
						ambiguous = true;
				}
			}
			if(!ambiguous && !byCaller.isEmpty())
				calleeContexts.put(boundary, Map.copyOf(byCaller));
		}
		return new FunctionOutputAliases(sourcesByTarget, calleeContexts,
			Collections.unmodifiableSet(sameContextBoundaries));
	}
	private record RuntimeMaterializationDemand(
		ExactPhysicalModel.DecisionDomain owner,
		ExactPhysicalModel.DecisionDomain read, double activationWeight,
		List<BranchLiteral> branchLiterals) { }
	static record BranchLiteral(String decisionPath, boolean ifArm)
		implements Comparable<BranchLiteral> {
		BranchLiteral {
			if(decisionPath == null || decisionPath.isBlank())
				throw new IllegalArgumentException(
					"EXACT_LATENT_WDIVMM_BRANCH_DECISION_INVALID");
		}

		@Override
		public int compareTo(BranchLiteral that) {
			int pathOrder = decisionPath.compareTo(that.decisionPath);
			return pathOrder != 0 ? pathOrder : Boolean.compare(ifArm, that.ifArm);
		}
	}

	private static List<RuntimeMaterializationSource> runtimeMaterializationSources(
		PlacementAnalysis analysis, CompiledHopKey read,
		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead,
		IdentityHashMap<CompiledHopKey,List<LogicalFunctionInputFact>> functionByRead,
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> functionOutputsByTarget,
		Set<CompiledHopKey> sameContextFunctionOutputs,
		Set<CompiledHopKey> visiting) {
		// Loop-backedge alias cycles do not prove a unique payload origin. Ordinary
		// costing retains the read's creation scope; latent source authority requires
		// a complete resolution and rejects this empty result at its call site.
		if(!visiting.add(read))
			return List.of();
		try {
			List<CompiledHopKey> direct = new ArrayList<>();
			for(LogicalTransientInputFact fact : transientByRead.getOrDefault(read, List.of()))
				direct.add(fact.sourceWrite());
			for(LogicalFunctionInputFact fact : functionByRead.getOrDefault(read, List.of()))
				direct.add(fact.sourceArgument());
			direct.addAll(functionOutputsByTarget.getOrDefault(read, List.of()));
			CompiledHopKey passThroughRead = runtimeMaterializationPassThroughRead(
				analysis, read);
			if(direct.isEmpty() && passThroughRead != null)
				return runtimeMaterializationSources(analysis, passThroughRead,
					transientByRead, functionByRead, functionOutputsByTarget,
					sameContextFunctionOutputs, visiting);
			if(direct.isEmpty()) {
				ValueVersionKey value = analysis.graph().node(read).orElseThrow().valueVersion();
				return List.of(new RuntimeMaterializationSource(read, value, List.of(), List.of(), List.of()));
			}
			List<LogicalTransientInputFact> transientInputs = transientByRead.getOrDefault(read, List.of());
			boolean loopPhiCarrier = transientInputs.size() == 2
				&& transientInputs.stream().filter(input -> input.sourceValueVersion()
					.versionKind() == VersionKind.LOOP_BACKEDGE).count() == 1;
			Map<String,RuntimeMaterializationSource> resolved = new LinkedHashMap<>();
			for(CompiledHopKey source : direct.stream().distinct().sorted().toList()) {
				List<RuntimeMaterializationSource> authorities = runtimeMaterializationSources(
					analysis, source, transientByRead, functionByRead, functionOutputsByTarget,
					sameContextFunctionOutputs, visiting);
				if(authorities.isEmpty()) {
					CompiledHopKey passThrough = runtimeMaterializationPassThroughRead(analysis, source);
					// A compiler-inserted W=W loop carrier may point back to the very
					// phi/read currently being resolved. It introduces no new payload;
					// ignore only this exact cyclic carrier and retain independently
					// resolved entry authorities. A pure cycle still resolves to empty.
					if(passThrough != null && visiting.contains(passThrough))
						continue;
					return List.of();
				}
				for(RuntimeMaterializationSource authority : authorities) {
					List<CompiledHopKey> returnBoundaries = authority.returnBoundaries();
					List<CompiledHopKey> reachabilityGuards = authority.reachabilityGuards();
					List<CompiledHopKey> loopPhiCarriers = authority.loopPhiCarriers();
					if(analysis.graph().node(read).orElseThrow().kind() == NodeKind.FUNCTION_OUTPUT
						&& !sameContextFunctionOutputs.contains(read)) {
						returnBoundaries = new ArrayList<>(returnBoundaries.size() + 1);
						returnBoundaries.add(read);
						returnBoundaries.addAll(authority.returnBoundaries());
					}
					if(analysis.hop(read).orElse(null) instanceof DataOp data
						&& data.getOp() == OpOpData.TRANSIENTREAD
						&& data.isPlannerBranchNormalization()) {
						reachabilityGuards = new ArrayList<>(reachabilityGuards.size() + 1);
						reachabilityGuards.add(read);
						reachabilityGuards.addAll(authority.reachabilityGuards());
					}
					if(loopPhiCarrier && !loopPhiCarriers.contains(read)) {
						loopPhiCarriers = new ArrayList<>(loopPhiCarriers.size() + 1);
						loopPhiCarriers.add(read);
						loopPhiCarriers.addAll(authority.loopPhiCarriers());
					}
					var resolvedAuthority = new RuntimeMaterializationSource(authority.occurrence(),
						authority.valueVersion(), returnBoundaries, reachabilityGuards, loopPhiCarriers);
					String returnPath = returnBoundaries.stream()
						.map(CompiledHopKey::normalizedSignature).collect(java.util.stream.Collectors.joining("->"));
					String guardPath = reachabilityGuards.stream()
						.map(CompiledHopKey::normalizedSignature).collect(java.util.stream.Collectors.joining("->"));
					String loopPhiPath = loopPhiCarriers.stream()
						.map(CompiledHopKey::normalizedSignature).collect(java.util.stream.Collectors.joining("->"));
					resolved.putIfAbsent(authority.occurrence().normalizedSignature() + '|'
						+ authority.valueVersion().normalizedSignature() + "|returns=" + returnPath
						+ "|guards=" + guardPath + "|loop-phis=" + loopPhiPath,
						resolvedAuthority);
				}
			}
			return resolved.values().stream().sorted(Comparator
				.comparing((RuntimeMaterializationSource source) ->
					source.occurrence().normalizedSignature())
				.thenComparing(source -> source.valueVersion().normalizedSignature())).toList();
		}
		finally {
			visiting.remove(read);
		}
	}

	/**
	 * Follow a transient assignment to the compiled value whose Data object it
	 * publishes. Variable assignment rebinds that object and does not itself create
	 * another MatrixObject payload. Loop-carrier {@code W = W} is the degenerate
	 * instance of the same runtime contract.
	 */
	private static CompiledHopKey runtimeMaterializationPassThroughRead(
		PlacementAnalysis analysis, CompiledHopKey occurrence) {
		Hop hop = analysis.hop(occurrence).orElseThrow();
		if(!(hop instanceof DataOp write && write.getOp() == OpOpData.TRANSIENTWRITE)
			&& !org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization.isPlacementAlias(hop))
			return null;
		List<CompiledInputEdgeFact> inputs = analysis.compiledInputEdgesInCanonicalOrder()
			.stream().filter(edge -> edge.consumer() == occurrence).toList();
		if(inputs.size() != 1 || inputs.get(0).inputPosition() != 0)
			return null;
		CompiledHopKey producer = inputs.get(0).producer();
		// TRANSIENTWRITE publishes its input Data object under the target
		// variable name. The assignment creates a variable version, not a new
		// MatrixObject payload; the compiled producer remains the copy origin.
		return producer;
	}

	static double reusableActivationUnion(List<List<BranchLiteral>> events,
		List<Double> activationWeights, double productionWeight) {
		if(events == null || activationWeights == null || events.isEmpty()
			|| events.size() != activationWeights.size())
			throw new IllegalArgumentException("EXACT_ACTIVATION_UNION_INPUT_INVALID");
		List<ExactMaterializationActivation.Event> demands = new ArrayList<>();
		for(int index = 0; index < events.size(); index++)
			demands.add(new ExactMaterializationActivation.Event(activationWeights.get(index), events.get(index)));
		return ExactMaterializationActivation.conservativeUnion(demands, productionWeight, allTrue(demands.size()));
	}

	private static void addPhysicalNativeLocalInputTransferFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, PhysicalWorkerCounts physicalWorkerCounts,
		OccurrenceExecutionFrequencyFacts frequencies,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations) {
		long logicalCells = 0L;
		long encodedCells = 0L;
		int projections = 0;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(latentRuntimeBoundaries.contains(new LatentWdivmmRuntimeTransferBoundary(
				edge.producer(), edge.consumer(), edge.inputPosition())))
				continue;
			ExactPhysicalModel.DecisionDomain producer = domains.get(edge.producer());
			ExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
			if(producer == null || consumer == null
				|| analysis.graph().node(edge.consumer()).orElseThrow().kind() == NodeKind.FUNCTION_CALL)
				continue;
			Hop producerHop = analysis.hop(edge.producer()).orElseThrow();
			Hop consumerHop = analysis.hop(edge.consumer()).orElseThrow();
			if(producerHop.getDataType() == null || !producerHop.getDataType().isMatrix())
				continue;
			boolean hasNativeLocalFedAlternative = consumer.alternatives().stream().anyMatch(alternative ->
				alternative.state().execType() == ExecType.FED
					&& alternative.inputAuthorities().stream().anyMatch(authority ->
						authority.inputPosition() == edge.inputPosition()
							&& authority.kind()
								== ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL));
			if(!hasNativeLocalFedAlternative)
				continue;
			double bytes = estimatedBytes(analysis, sparseAssignments,
				edge.producer(), producerHop);
			double fusedInputPreparationBytes =
				PlacementCostSemantics.latentWdivmmFusedInputPreparationBytes(
					analysis, edge.producer(), edge.consumer(), edge.inputPosition());
			double weight = frequencies.exactForwardingWeight(edge.consumer(), edge.producer());
			ExecutionWorkerCounts executionCounts = new ExecutionWorkerCounts();
			int[] targetWorkerCounts = consumer.alternatives().stream()
				.mapToInt(target -> executionWorkerCount(analysis, target, workers,
					physicalWorkerCounts, executionCounts)).toArray();
			// GETs, including bounded native-local payloads, share the materialization
			// collector. This factor owns only per-instruction PUT preparation.
			NativeLocalTargetCost[] targetCosts = new NativeLocalTargetCost[consumer.alternatives().size()];
			var canonical = ExactCategoricalSolver.Factor.lazy(
				List.of(producer.variable(), consumer.variable()), values -> {
					int targetIndex = values[1];
					NativeLocalTargetCost targetCost = targetCosts[targetIndex];
					if(targetCost == null) {
						targetCost = nativeLocalTargetCost(analysis, sparseAssignments, edge,
							consumer, consumerHop, producerHop, bytes, fusedInputPreparationBytes,
							targetWorkerCounts[targetIndex], targetIndex);
						targetCosts[targetIndex] = targetCost;
					}
					if(!targetCost.applicable())
						return 0.0;
					return requireCost(weight * targetCost.baseCost(),
						"EXACT_PHYSICAL_NATIVE_LOCAL_INPUT_COST_UNPROVEN");
				});
			factors.add(canonical);
			int[] sourceClass = new int[producer.alternatives().size()];
			long originalCells = (long) sourceClass.length * consumer.alternatives().size();
			long projectedCells = sourceClass.length + (long) consumer.alternatives().size();
			logicalCells += originalCells;
			if(projectedCells < originalCells) {
				String key = "exact-native-local|" + edge.producer().normalizedSignature() + '|'
					+ edge.consumer().normalizedSignature() + '|' + edge.inputPosition();
				factorizations.put(canonical, projectNativeLocalSource(key, canonical, sourceClass));
				encodedCells += projectedCells;
				projections++;
			}
			else
				encodedCells += originalCells;
		}
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Planner-CostProjection", "kind=native-local-source"
				+ " projections=" + projections + " logicalCells=" + logicalCells
				+ " encodedCells=" + encodedCells);
	}

	/**
	 * Exact, total and single-valued source observation, not a candidate-space quotient.
	 * The caller proves identical cost rows within each class. Class numbers follow
	 * first occurrence so first-invalid numeric evaluation keeps its original order.
	 */
	static SolverFactorization projectNativeLocalSource(String key,
		ExactCategoricalSolver.Factor canonical, int[] sourceClass) {
		if(key == null || key.isBlank() || canonical.scope().size() != 2)
			throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_PROJECTION_INVALID");
		var source = canonical.scope().get(0);
		var target = canonical.scope().get(1);
		int[] classes = sourceClass.clone();
		if(classes.length != source.domainSize())
			throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_PROJECTION_DOMAIN_INVALID");
		List<Integer> representatives = new ArrayList<>();
		for(int value = 0; value < classes.length; value++) {
			int category = classes[value];
			if(category < 0 || category > representatives.size())
				throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_PROJECTION_CLASS_INVALID");
			if(category == representatives.size())
				representatives.add(value);
		}
		var observation = new ExactCategoricalSolver.Variable(key + "|source-class", representatives.size());
		var binding = ExactCategoricalSolver.Factor.lazy(List.of(source, observation), values ->
			classes[values[0]] == values[1] ? 0d : Double.POSITIVE_INFINITY);
		var prices = ExactCategoricalSolver.Factor.lazy(List.of(observation, target), values ->
			canonical.cost(new int[] {representatives.get(values[0]), values[1]}));
		long logical = (long) source.domainSize() * target.domainSize();
		long encoded = (long) observation.domainSize() * (source.domainSize() + (long) target.domainSize());
		String descriptor = "NATIVE_LOCAL_SOURCE_PROJECTION_V1|" + key
			+ "|originalScope=" + canonical.scope() + "|sourceClasses=" + java.util.Arrays.toString(classes)
			+ "|representatives=" + representatives + "|bindingScope=" + binding.scope()
			+ "|priceScope=" + prices.scope() + "|logicalCells=" + logical + "|encodedCells=" + encoded;
		return new SolverFactorization(List.of(observation), List.of(binding, prices), descriptor,
			List.of(prices), classes, new CostTransportDeclaration.OneMonetaryTable(prices));
	}

	/** The unfrozen production callbacks are an independent numeric reference for projection tests. */
	static IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization>
		nativeLocalSourceProjectionsForTest(ExactPhysicalModel model) {
		var analysis = model.analysis();
		var domains = new IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain>();
		for(var domain : model.domains())
			domains.put(domain.node().key(), domain);
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization>();
		addPhysicalNativeLocalInputTransferFactors(analysis,
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis), domains,
			workerCount(analysis.graph()), new PhysicalWorkerCounts(),
			analysis.executionFrequencyFacts(),
			new LinkedHashSet<>(PlacementCostSemantics.latentWdivmmRuntimeTransferBoundaries(analysis)),
			new ArrayList<>(), factorizations);
		return factorizations;
	}

	private static NativeLocalTargetCost nativeLocalTargetCost(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledInputEdgeFact edge,
		ExactPhysicalModel.DecisionDomain consumer, Hop consumerHop, Hop producerHop,
		double bytes, double fusedInputPreparationBytes, int targetWorkers, int targetIndex) {
		ExactPhysicalModel.Alternative target = consumer.alternatives().get(targetIndex);
		if(target.state().execType() != ExecType.FED
			|| target.inputAuthorities().stream().noneMatch(authority ->
				authority.inputPosition() == edge.inputPosition()
					&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL))
			return NativeLocalTargetCost.NOT_APPLICABLE;
		CandidateEmissionFact emission = target.captured()
			? target.candidateEmission() : target.executionEmission();
		FType executionFType = emission == null ? target.state().fType()
			: emission.executionFType();
		PlacementCostSemantics.NativeLocalInputTransferEstimate boundedElementwise =
			PlacementCostSemantics.boundedElementwiseNativeLocalInputTransfer(
				analysis, edge.producer(), edge.consumer(), edge.inputPosition(),
				executionFType, targetWorkers);
		List<FType> inputFTypes = target.orderedInputs().stream()
			.map(input -> input.present() ? input.fType() : null).toList();
		double cost;
		if(FederatedCostModel.modelsNativeInputUpload(consumerHop, inputFTypes, edge.inputPosition()))
			cost = 0.0;
		else if(fusedInputPreparationBytes >= 0.0)
			cost = FederatedCostModel.computeInBandUploadPayloadCost(
				fusedInputPreparationBytes, FType.BROADCAST, targetWorkers);
		else if(boundedElementwise != null)
			cost = boundedElementwise.uploadPayloadCostUpperBound();
		else
			cost = nativeLocalInputUploadCost(analysis, edge.consumer(), consumerHop,
				edge.producer(), bytes,
				executionFType, targetWorkers);
		return new NativeLocalTargetCost(true, cost);
	}

	private record NativeLocalTargetCost(boolean applicable, double baseCost) {
		private static final NativeLocalTargetCost NOT_APPLICABLE =
			new NativeLocalTargetCost(false, 0.0);
	}

	private static String outputLayoutIdentity(ExactPhysicalModel.Alternative alternative) {
		// A relocation-backed alternative carries its execution realization as well
		// as a different emitted map. Boundary costs follow the emitted map.
		if(alternative.durableAnchor() != null)
			return alternative.durableAnchor().normalizedSignature();
		return alternative.realization() == null ? "-" : alternative.realization().key().normalizedSignature();
	}

	static int realizationWorkerCount(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, int fallbackWorkers) {
		return realizationWorkerCount(analysis, alternative, fallbackWorkers,
			new PhysicalWorkerCounts());
	}

	static int executionWorkerCount(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, int fallbackWorkers) {
		return executionWorkerCount(analysis, alternative, fallbackWorkers,
			new PhysicalWorkerCounts(), new ExecutionWorkerCounts());
	}

	private static int executionWorkerCount(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, int fallbackWorkers,
		PhysicalWorkerCounts physicalWorkerCounts,
		ExecutionWorkerCounts executionWorkerCounts) {
		// WDivMM dispatches exclusively through W's FederationMap. Remote U/V/MX
		// are preparation sources, not extra execution workers. A union of their
		// endpoints would shrink kernel work and inflate broadcasts simultaneously.
		Hop owner = analysis.hop(alternative.decision()).orElse(null);
		if(owner instanceof org.apache.sysds.hops.QuaternaryOp q
			&& q.getOp() == org.apache.sysds.common.Types.OpOp4.WDIVMM && alternative.supportClause() != null) {
			for(var binding : alternative.supportClause().inputBindings()) {
				if(binding.inputPosition() != 0) continue;
				if(binding.relocationAction() != null)
					return physicalWorkerCounts.count(binding.relocationAction().durableAnchor());
				var sourceKey = binding.source().rule().parentOccurrence();
				if(analysis.hop(sourceKey).orElse(null) instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
					var anchors = analysis.graph().node(sourceKey).orElseThrow().anchors();
					if(anchors.size() == 1) return physicalWorkerCounts.count(anchors.get(0));
				}
				var weights = analysis.requireExactCandidateRealization(binding.source());
				Set<Integer> counts = new LinkedHashSet<>();
				for(var clause : weights.supportClauses())
					counts.add(realizationWorkerCount(analysis, weights, clause, new LinkedHashSet<>(), physicalWorkerCounts));
				if(counts.size() == 1 && counts.iterator().next() > 0) return counts.iterator().next();
			}
			var edge = analysis.compiledInputEdge(alternative.decision(), 0).orElse(null);
			boolean direct = alternative.inputAuthorities().stream().anyMatch(a -> a.inputPosition() == 0
				&& a.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT);
			if(direct && edge != null && analysis.hop(edge.producer()).orElse(null)
				instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
				var anchors = analysis.graph().node(edge.producer()).orElseThrow().anchors();
				if(anchors.size() == 1) return physicalWorkerCounts.count(anchors.get(0));
			}
		}
		// Relocation-backed alternatives carry the execution realization and a
		// different emitted durable anchor. Captured derived-FOUT realizations also
		// carry their materialized output anchor, so their execution pool must come
		// exclusively from the support clause.
		if(alternative.realization() != null) {
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			boolean derivedFout = emission != null && emission.emissionState().derivedFedFout();
			Integer cached = executionWorkerCounts.get(
				alternative.realization(), alternative.supportClause(), derivedFout);
			int exact = cached == null ? (derivedFout
				? realizationSupportWorkerCount(analysis, alternative.supportClause(),
					new LinkedHashSet<>(), physicalWorkerCounts)
				: realizationWorkerCount(analysis, alternative.realization(),
					alternative.supportClause(), new LinkedHashSet<>(), physicalWorkerCounts)) : cached;
			// Cache only proven top-level support. A zero may be contextual to cycle
			// detection and must not poison another alternative.
			if(cached == null && exact > 0)
				executionWorkerCounts.put(
					alternative.realization(), alternative.supportClause(), derivedFout, exact);
			return exact > 0 ? exact : Math.max(1, fallbackWorkers);
		}
		// A federated source has no separate execution realization: its durable map
		// is both the source and execution pool.
		if(alternative.durableAnchor() != null)
			return physicalWorkerCounts.count(alternative.durableAnchor());
		return Math.max(1, fallbackWorkers);
	}

	private static int realizationWorkerCount(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, int fallbackWorkers,
		PhysicalWorkerCounts physicalWorkerCounts) {
		if(alternative.durableAnchor() != null)
			return physicalWorkerCounts.count(alternative.durableAnchor());
		Integer cached = physicalWorkerCounts.rootExact(alternative);
		int exact;
		if(cached != null)
			exact = cached;
		else {
			exact = alternative.realization() == null ? 0
					: realizationWorkerCount(analysis, alternative.realization(),
						alternative.supportClause(), new LinkedHashSet<>(), physicalWorkerCounts);
			physicalWorkerCounts.rememberRootExact(alternative, exact);
		}
		return exact > 0 ? exact : Math.max(1, fallbackWorkers);
	}

	private static int realizationWorkerCount(PlacementAnalysis analysis,
		PlacementAnalysis.CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause selectedClause,
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference> visiting,
		PhysicalWorkerCounts physicalWorkerCounts) {
		if(realization.anchor() != null)
			return physicalWorkerCounts.count(realization.anchor());
		return realizationSupportWorkerCount(
			analysis, selectedClause, visiting, physicalWorkerCounts);
	}

	private static int realizationSupportWorkerCount(PlacementAnalysis analysis,
		PlacementAnalysis.CandidateRealizationSupportClause selectedClause,
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference> visiting,
		PhysicalWorkerCounts physicalWorkerCounts) {
		if(selectedClause.nativeWorkerPoolWitness() != null)
			return physicalWorkerCounts.count(selectedClause.nativeWorkerPoolWitness());
		Set<Integer> counts = new LinkedHashSet<>();
		for(var binding : selectedClause.inputBindings()) {
			if(binding.relocationAction() != null) {
				counts.add(physicalWorkerCounts.count(binding.relocationAction().durableAnchor()));
				continue;
			}
			if(binding.source().realization().emissionState().placementState().output() != FederatedOutput.FOUT
				|| !visiting.add(binding.source()))
				continue;
			var source = analysis.requireExactCandidateRealization(binding.source());
			Set<Integer> sourceCounts = new LinkedHashSet<>();
			for(var clause : source.supportClauses())
				sourceCounts.add(realizationWorkerCount(
					analysis, source, clause, visiting, physicalWorkerCounts));
			int count = sourceCounts.size() == 1 ? sourceCounts.iterator().next() : 0;
			visiting.remove(binding.source());
			if(count > 0)
				counts.add(count);
		}
		return counts.size() == 1 ? counts.iterator().next() : 0;
	}

	static int nativeLocalInputWorkerCount(List<ExactPhysicalModel.InputAuthority> authorities,
		int fallbackWorkers) {
		return nativeLocalInputWorkerCount(authorities, fallbackWorkers,
			new PhysicalWorkerCounts());
	}

	private static int nativeLocalInputWorkerCount(List<ExactPhysicalModel.InputAuthority> authorities,
		int fallbackWorkers, PhysicalWorkerCounts physicalWorkerCounts) {
		DurableAnchorKey anchor = null;
		for(var authority : authorities) {
			if(authority.relocationAction() == null)
				continue;
			DurableAnchorKey current = authority.relocationAction().key().durableAnchor();
			if(anchor != null && !org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
				.samePhysicalWorkerPool(anchor, current))
				throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_CONSUMER_ANCHOR_CONFLICT");
			anchor = current;
		}
		// The selected consumer's exact input authority determines its runtime map.
		// A graph-wide worker union overcharges FULL uploads and smaller worker pools.
		// Without such authority retain the prior conservative estimate; neither a
		// worker count nor an output FType invents an input FederationMap.
		return anchor == null ? Math.max(1, fallbackWorkers) : physicalWorkerCounts.count(anchor);
	}

	private static final class PhysicalWorkerCounts {
		private final IdentityHashMap<DurableAnchorKey,Integer> byAnchor = new IdentityHashMap<>();
		// Only completed root proofs are reusable: recursive states depend on the
		// visiting set, while fallback is applied after this exact-result cache.
		private final IdentityHashMap<ExactPhysicalModel.Alternative,Integer> exactByRoot =
			new IdentityHashMap<>();

		private Integer rootExact(ExactPhysicalModel.Alternative alternative) {
			return exactByRoot.get(Objects.requireNonNull(alternative, "alternative"));
		}

		private void rememberRootExact(ExactPhysicalModel.Alternative alternative, int exact) {
			exactByRoot.put(Objects.requireNonNull(alternative, "alternative"), exact);
		}

		private int count(DurableAnchorKey anchor) {
			Integer cached = byAnchor.get(Objects.requireNonNull(anchor, "anchor"));
			if(cached != null)
				return cached;
			Set<String> workers = new LinkedHashSet<>();
			for(var partition : anchor.partitions())
				workers.add(FederationUtils.canonicalFederatedWorkerAddress(partition.workerId()));
			int count = workers.size();
			byAnchor.put(anchor, count);
			return count;
		}
	}

	private static final class ExecutionWorkerCounts {
		private final IdentityHashMap<PlacementAnalysis.CandidateRealizationSupportClause,Integer>
			derivedBySupport = new IdentityHashMap<>();
		private final IdentityHashMap<PlacementAnalysis.CandidateEmissionRealization,
			IdentityHashMap<PlacementAnalysis.CandidateRealizationSupportClause,Integer>>
			ordinaryByRealization = new IdentityHashMap<>();

		private Integer get(PlacementAnalysis.CandidateEmissionRealization realization,
			PlacementAnalysis.CandidateRealizationSupportClause supportClause, boolean derivedFout) {
			if(derivedFout)
				return derivedBySupport.get(supportClause);
			var bySupport = ordinaryByRealization.get(realization);
			return bySupport == null ? null : bySupport.get(supportClause);
		}

		private void put(PlacementAnalysis.CandidateEmissionRealization realization,
			PlacementAnalysis.CandidateRealizationSupportClause supportClause,
			boolean derivedFout, int workers) {
			if(derivedFout)
				derivedBySupport.put(supportClause, workers);
			else
				ordinaryByRealization.computeIfAbsent(realization,
					ignored -> new IdentityHashMap<>()).put(supportClause, workers);
		}
	}

	static record PhysicalWorkerCountCacheProbe(List<Integer> counts, int computations) {
		PhysicalWorkerCountCacheProbe { counts = List.copyOf(counts); }
	}

	static PhysicalWorkerCountCacheProbe physicalWorkerCountCacheProbeForTest(
		List<DurableAnchorKey> anchors) {
		PhysicalWorkerCounts cache = new PhysicalWorkerCounts();
		List<Integer> counts = new ArrayList<>(anchors.size());
		for(DurableAnchorKey anchor : anchors)
			counts.add(cache.count(anchor));
		return new PhysicalWorkerCountCacheProbe(counts, cache.byAnchor.size());
	}

	private static double nativeLocalInputUploadCost(PlacementAnalysis analysis,
		CompiledHopKey consumerKey, Hop consumer, CompiledHopKey inputKey,
		double bytes, FType executionFType, int workers) {
		if(executionFType == null)
			throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_EXECUTION_LAYOUT_UNPROVEN");
		FType transferType = nativeLocalInputTransferType(analysis, consumerKey, consumer,
			inputKey, executionFType);
		return FederatedCostModel.computeInBandUploadPayloadCost(bytes, transferType, workers);
	}

	private static FType nativeLocalInputTransferType(PlacementAnalysis analysis,
		CompiledHopKey consumerKey, Hop consumer, CompiledHopKey inputKey,
		FType executionFType) {
		// Covariance's scalar result shape does not describe the rows sent as local
		// counterparts/weights. ROW execution slices these matrix inputs by worker.
		if(executionFType == FType.ROW
			&& (consumer instanceof BinaryOp binary
				&& binary.getOp() == OpOp2.COV
				|| consumer instanceof TernaryOp ternary
					&& ternary.getOp() == OpOp3.COV))
			return FType.ROW;
		// ROW/COL runtime instructions can sliced-broadcast an equally shaped matrix, so
		// total payload is one logical input. Shape-broadcast operands and FULL/PART worker
		// branches use a replicated broadcast to every participating worker.
		var consumerEstimate = analysis.physicalCostEstimateFact(consumerKey);
		var inputEstimate = analysis.physicalCostEstimateFact(inputKey);
		boolean sameShape = inputEstimate.rows() > 0 && inputEstimate.cols() > 0
			&& inputEstimate.rows() == consumerEstimate.rows()
			&& inputEstimate.cols() == consumerEstimate.cols();
		return sameShape && (executionFType == FType.ROW || executionFType == FType.COL)
			? executionFType : FType.BROADCAST;
	}

	private static boolean retainedFunctionCoverageProven(
		OccurrenceExecutionFrequencyFacts frequencies, EffectiveLogicalFunctionInput input,
		CompiledHopKey source) {
		List<OccurrenceExecutionFrequencyFacts.FunctionBoundaryOccurrenceFact> occurrences =
			frequencies.exactFunctionBoundaryOccurrences(input.authority());
		if(occurrences.isEmpty())
			return false;
		List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> sourceProfiles;
		List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> targetProfiles;
		try {
			sourceProfiles = frequencies.exactProfiles(source);
			targetProfiles = frequencies.exactProfiles(input.targetRead());
		}
		catch(IllegalArgumentException unproven) {
			return false;
		}
		for(var occurrence : occurrences) {
			boolean callerKnown = sourceProfiles.stream().anyMatch(profile ->
				profile.contextOrdinal() == occurrence.callerContextOrdinal());
			boolean calleeKnown = targetProfiles.stream().anyMatch(profile ->
				profile.contextOrdinal() == occurrence.calleeProfile().contextOrdinal());
			if(!callerKnown || !calleeKnown)
				return false;
		}
		for(var sourceProfile : sourceProfiles)
			if(sourceProfile.expectedExecutions() > 0.0 && occurrences.stream().noneMatch(occurrence ->
				occurrence.callerContextOrdinal() == sourceProfile.contextOrdinal()))
				return false;
		return true;
	}

	private static double physicalLogicalFunctionInputBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, EffectiveLogicalFunctionInput input,
		ExactPhysicalModel.DecisionDomain source, ExactPhysicalModel.DecisionDomain formal) {
		var sourceEstimate = analysis.physicalCostEstimateFact(source.node().key());
		double bytes = sparseAssignments.serializedEstimate(source.node().key());
		boolean unresolvedMatrixShape = sourceEstimate.dataType() != null
			&& sourceEstimate.dataType().isMatrix()
			&& (!sourceEstimate.dimensionsKnown() || sourceEstimate.rows() <= 0
				|| sourceEstimate.cols() <= 0);
		if((!Double.isFinite(bytes) || bytes <= 0.0) && unresolvedMatrixShape) {
			bytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(
				analysis, source.node().key());
			if(Double.isFinite(bytes) && bytes > 0.0 && sourceEstimate.nnz() >= 0) {
				var shape = analysis.abstractShapeFact(source.node().key()).orElseThrow();
				bytes = MatrixBlock.estimateSizeOnDisk(
					shape.rows().value(), shape.cols().value(), sourceEstimate.nnz());
			}
		}
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			bytes = PlacementCostSemantics.boundedDenseOutputBytes(analysis, source.node().key());
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			bytes = analysis.physicalFunctionInputEstimate(
				source.node().key(), formal.node().key());
		return bytes;
	}

	private static void addPhysicalLogicalFunctionFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, PhysicalWorkerCounts physicalWorkerCounts,
		OccurrenceExecutionFrequencyFacts frequencies,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<ExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys,
		Set<RetainedFunctionDownload> retainedFunctionDownloads) {
		for(EffectiveLogicalFunctionInput input : effectiveLogicalFunctionInputs(analysis)) {
			ExactPhysicalModel.DecisionDomain source = domains.get(input.authority().sourceArgument());
			ExactPhysicalModel.DecisionDomain formal = domains.get(input.targetRead());
			if(source == null || formal == null)
				continue;
			double bytes = physicalLogicalFunctionInputBytes(analysis, sparseAssignments, input,
				source, formal);
			double callWeight = frequencies.logicalFunctionCallWeight(input.authority());
			List<FType> sourceTypes = source.alternatives().stream().map(a -> a.state().fType())
				.filter(Objects::nonNull).distinct().toList();
			for(FType type : sourceTypes) {
				if(retainedFunctionDownloads.contains(new RetainedFunctionDownload(input, type)))
					continue;
				double[] costs = new double[source.alternatives().size()];
				for(int index = 0; index < costs.length; index++) {
					var alternative = source.alternatives().get(index);
					if(alternative.state().output() == FederatedOutput.FOUT && alternative.state().fType() == type)
						costs[index] = requireCost(callWeight
							* FederatedCostModel.computeReusableMaterializationDownloadCost(bytes, type,
								realizationWorkerCount(
									analysis, alternative, workers, physicalWorkerCounts)),
							"EXACT_PHYSICAL_LOGICAL_FUNCTION_DOWNLOAD_COST_UNPROVEN");
				}
				factors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(source.variable(), formal.variable()), values ->
						formal.alternatives().get(values[1]).state().execType() == ExecType.CP
							? costs[values[0]] : 0.0));
				transferKeys.add(new PhysicalTransferKey(source.node().valueVersion(),
					List.of(new PhysicalTransferEndpoint(source.node().key(), formal.node().key(),
						input.logicalPosition())), Direction.DOWNLOAD, type, BoundaryMode.ANCHOR_TRANSFER));
				}
				Map<String,RelocationAction> uploadActions = new LinkedHashMap<>();
				formal.alternatives().stream().flatMap(alternative -> alternative.inputAuthorities().stream())
					.filter(authority -> authority.inputPosition() == input.logicalPosition()
						&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
						&& authority.relocationAction().key().sourceValueVersion()
							.equals(source.node().valueVersion()))
					.map(ExactPhysicalModel.InputAuthority::relocationAction).sorted()
					.forEach(action -> uploadActions.putIfAbsent(
						RelocationSelections.physicalEmissionIdentity(action.key()), action));
				for(Map.Entry<String,RelocationAction> uploadEntry : uploadActions.entrySet()) {
					RelocationAction action = uploadEntry.getValue();
					FType type = action.key().materializationFType();
					int targetWorkers = physicalWorkerCounts.count(action.key().durableAnchor());
					double upload = requireCost(callWeight * FederatedCostModel.computeUploadNetworkCost(bytes,
						type, targetWorkers),
						"EXACT_PHYSICAL_LOGICAL_FUNCTION_UPLOAD_COST_UNPROVEN");
					double[] refedDownloads = new double[source.alternatives().size()];
					for(int index = 0; index < refedDownloads.length; index++) {
						var sourceAlternative = source.alternatives().get(index);
						if(sourceAlternative.state().output() == FederatedOutput.FOUT)
							refedDownloads[index] = requireCost(callWeight
								* FederatedCostModel.computeReusableMaterializationDownloadCost(bytes,
									sourceAlternative.state().fType(), realizationWorkerCount(
										analysis, sourceAlternative, workers, physicalWorkerCounts)),
								"EXACT_PHYSICAL_LOGICAL_FUNCTION_REFED_DOWNLOAD_COST_UNPROVEN");
					}
					String emissionIdentity = uploadEntry.getKey();
					factors.add(ExactCategoricalSolver.Factor.lazy(
						List.of(source.variable(), formal.variable()), values -> {
							boolean active = formal.alternatives().get(values[1]).inputAuthorities().stream()
								.anyMatch(authority -> authority.inputPosition() == input.logicalPosition()
									&& authority.kind()
										== ExactPhysicalModel.InputAuthorityKind.RELOCATION
									&& RelocationSelections.physicalEmissionIdentity(
										authority.relocationAction().key()).equals(emissionIdentity));
							return active ? upload + refedDownloads[values[0]] : 0.0;
						}));
					transferKeys.add(new PhysicalTransferKey(source.node().valueVersion(),
						List.of(new PhysicalTransferEndpoint(source.node().key(), formal.node().key(),
							input.logicalPosition())), Direction.UPLOAD, type, BoundaryMode.ANCHOR_TRANSFER,
						emissionIdentity));
			}
		}
	}

	private static FType exactInputAuthorityType(PlacementAnalysis analysis, CompiledHopKey producer) {
		NeutralPlacementGraph.Node node = analysis.graph().node(producer).orElseThrow();
		List<FType> anchors = node.anchors().stream().map(DurableAnchorKey::fType).distinct().toList();
		if(anchors.size() == 1) return anchors.get(0);
		// A relocation is a conditional future representation if the source is selected LOUT;
		// it is not current FOUT authority for the producer. Treating it as direct authority
		// erased the producer membership proof and made consumer rows appear stronger than they
		// were. Derived producers are resolved recursively through exact membership facts instead.
		return null;
	}

	private static FedCostProjection fedCostProjection(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop,
		List<FType> inputFTypes, FType executionFType, int workers, double executionWeight) {
		return fedCostProjection(analysis, sparseAssignments, key, hop, inputFTypes,
			executionFType, workers, executionWeight, new FederatedExecutionLayout(executionFType,
				Math.max(1, workers), inputFTypes.stream()
					.map(type -> new InputLayout(type, List.of(), false)).toList()));
	}

	private static FedCostProjection fedCostProjection(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop,
		List<FType> inputFTypes, FType executionFType, int workers, double executionWeight,
		FederatedExecutionLayout layout) {
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis, sparseAssignments, key);
		if(prepared.removedKernel() || prepared.fusedWeightsOccurrence() != null
			|| org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization.isPlacementAlias(hop))
			return FedCostProjection.none();
		double base = executionWeight * prepared.localCost();
		return fedCostProjection(analysis, key, hop, inputFTypes, executionFType, workers,
			executionWeight, base, effectiveOutputBytes(analysis, sparseAssignments, key, hop),
			effectiveUploadBytes(analysis, sparseAssignments, key, hop),
			executionWeight * prepared.federatedCost(layout), prepared.outputResponses(layout,
				effectiveUploadBytes(analysis, sparseAssignments, key, hop)), layout);
	}

	private static FedCostProjection fedCostProjection(PlacementAnalysis analysis,
		CompiledHopKey key, Hop hop, List<FType> inputFTypes, FType executionFType,
		int workers, double executionWeight, double base, double outputBytes,
		double uploadBytes, double fedCompute, PlacementCostSemantics.WorkerResponseSummary responses,
		FederatedExecutionLayout layout) {
		if(org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization.isPlacementAlias(hop))
			return FedCostProjection.none();
		if(executionFType == null)
			throw new IllegalArgumentException("EXACT_FED_EXECUTION_LAYOUT_UNPROVEN");
		double fedInstructionLatency = FederatedCostModel
			.computeFederatedInstructionNetworkCost(hop, executionWeight);
		FederatedCostModel.MixedFedLocalCost mixed = hop instanceof DataOp
			? FederatedCostModel.MixedFedLocalCost.none()
			: PlacementCostSemantics.analysisAwareMixedFedLocalCost(analysis, key,
				new ArrayList<>(hop.getInput()), inputFTypes, executionFType,
				executionWeight > 0.0 ? base / executionWeight : 0.0, outputBytes, workers, layout);
		double fedInputPreparation = executionWeight * mixed.getInputPreparationCost();
		double fedCost = requireCost(fedCompute + fedInstructionLatency
			+ fedInputPreparation, "EXACT_FED_COST_UNPROVEN");

		double resultDownloadUnit = responses != null
			&& !(hop instanceof org.apache.sysds.hops.AggUnaryOp)
			&& !(hop instanceof org.apache.sysds.hops.AggBinaryOp)
			&& !(hop instanceof org.apache.sysds.hops.QuaternaryOp)
			? FederatedCostModel.computeNativeFederatedLoutResultCost(hop, inputFTypes, responses)
			: FederatedCostModel.computeNativeFederatedLoutResultCost(
				hop, inputFTypes, executionFType, uploadBytes, workers);
		if(!(hop instanceof DataOp)) {
			resultDownloadUnit = FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				hop, executionFType, outputBytes, workers, resultDownloadUnit);
			resultDownloadUnit = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				hop, executionFType, outputBytes, workers, resultDownloadUnit);
			resultDownloadUnit = PlacementCostSemantics.analysisAwareNativeFederatedLoutResultCost(
				analysis, key, outputBytes, workers, resultDownloadUnit);
			if(mixed.hasCoordinatorPhase())
				resultDownloadUnit = mixed.getCoordinatorPhaseCost();
		}
		else if(((DataOp)hop).getOp() == OpOpData.TRANSIENTWRITE)
			resultDownloadUnit = 0.0;
		double resultDownload = requireCost(executionWeight * resultDownloadUnit,
			"EXACT_RESULT_DOWNLOAD_COST_UNPROVEN");
		return new FedCostProjection(fedCost, resultDownload);
	}

	private record FedCostProjection(double fedUnaryCost, double resultDownloadCost) {
		private static FedCostProjection none() {
			return new FedCostProjection(0.0, 0.0);
		}

		private double fedLoutCost() {
			return requireCost(fedUnaryCost + resultDownloadCost,
				"EXACT_FED_LOUT_COST_UNPROVEN");
		}
	}

	private static double unitLocalCost(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		if(analysis.hop(key).orElse(null) != hop)
			throw new IllegalArgumentException("EXACT_COST_HOP_OCCURRENCE_MISMATCH");
		return PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, sparseAssignments, key);
	}

	private static double effectiveOutputBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		double semantic = sparseAssignments.memEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		var estimate = analysis.physicalCostEstimateFact(key);
		boolean unresolvedMatrixShape = estimate.dataType() != null && estimate.dataType().isMatrix()
			&& (!estimate.dimensionsKnown() || estimate.rows() <= 0 || estimate.cols() <= 0);
		double bytes = estimate.effectiveOutputMemEstimate();
		return !unresolvedMatrixShape && Double.isFinite(bytes) && bytes > 0.0 ? bytes
			: estimatedBytes(analysis, sparseAssignments, key, hop);
	}

	private static double effectiveUploadBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		double semantic = sparseAssignments.serializedEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		var estimate = analysis.physicalCostEstimateFact(key);
		boolean unresolvedMatrixShape = estimate.dataType() != null && estimate.dataType().isMatrix()
			&& (!estimate.dimensionsKnown() || estimate.rows() <= 0 || estimate.cols() <= 0);
		double bytes = estimate.effectiveUploadMemEstimate();
		return !unresolvedMatrixShape && Double.isFinite(bytes) && bytes > 0.0 ? bytes
			: estimatedBytes(analysis, sparseAssignments, key, hop);
	}

	private static List<EffectiveLogicalFunctionInput> effectiveLogicalFunctionInputs(
		PlacementAnalysis analysis) {
		List<EffectiveLogicalFunctionInput> result = new ArrayList<>();
		List<LogicalFunctionInputFact> direct = analysis.logicalFunctionInputsInCanonicalOrder();
		for(LogicalFunctionInputFact fact : direct) {
			if(analysis.requireExactLogicalFunctionInput(fact) != fact)
				throw new IllegalArgumentException("EXACT_LOGICAL_FUNCTION_INPUT_FOREIGN");
			result.add(new EffectiveLogicalFunctionInput(fact, null, fact.targetRead()));
		}
		for(LogicalTransientInputFact transientFact : analysis.logicalTransientInputsInCanonicalOrder()) {
			List<Constraint> bindings = analysis.graph().constraints().stream()
				.filter(constraint -> constraint.kind() == ConstraintKind.SAME_PLACEMENT
					&& "function-input-binding".equals(constraint.evidence())
					&& constraint.right() == transientFact.sourceWrite())
				.toList();
			if(bindings.isEmpty())
				continue;
			if(bindings.size() != 1)
				throw new IllegalArgumentException("EXACT_FUNCTION_INPUT_BINDING_AMBIGUOUS|source="
					+ transientFact.sourceWrite().normalizedSignature());
			Constraint binding = bindings.get(0);
			List<LogicalFunctionInputFact> authorities = direct.stream()
				.filter(fact -> fact.targetRead() == binding.left()).toList();
			if(authorities.isEmpty())
				throw new IllegalArgumentException("EXACT_FUNCTION_INPUT_FORWARD_AUTHORITY_MISSING|source="
					+ transientFact.sourceWrite().normalizedSignature() + "|read="
					+ transientFact.targetRead().normalizedSignature());
			for(LogicalFunctionInputFact authority : authorities) {
				// Shared analysis owns the complete writer/read realization relation. Do not
				// collapse it to one FType (or reject legal LOCAL-only forwarding) while
				// constructing the cost topology. Physical compatibility factors bind the
				// selected realization; this edge is a single logical payload, not one
				// payload per candidate alternative.
				if(analysis.requireExactLogicalTransientInput(transientFact.sourceWrite(),
					transientFact.targetRead(), transientFact.logicalPosition()) != transientFact)
					throw new IllegalArgumentException("EXACT_LOGICAL_TRANSIENT_INPUT_FOREIGN");
				result.add(new EffectiveLogicalFunctionInput(authority, transientFact,
					transientFact.targetRead()));
			}
		}
		return result.stream().sorted(Comparator
			.comparing((EffectiveLogicalFunctionInput input) -> input.targetRead().normalizedSignature())
			.thenComparing(input -> input.authority().sourceArgument().normalizedSignature()))
			.toList();
	}

	private record RetainedFunctionDownload(EffectiveLogicalFunctionInput input, FType type) {
		private RetainedFunctionDownload {
			Objects.requireNonNull(input, "input");
			Objects.requireNonNull(type, "type");
		}
	}

	private record EffectiveLogicalFunctionInput(LogicalFunctionInputFact authority,
		LogicalTransientInputFact forwardedAuthority, CompiledHopKey targetRead) {
		private EffectiveLogicalFunctionInput {
			Objects.requireNonNull(authority, "authority");
			Objects.requireNonNull(targetRead, "targetRead");
			if(forwardedAuthority != null && forwardedAuthority.targetRead() != targetRead)
				throw new IllegalArgumentException("Forwarded function input target differs");
		}

		private int logicalPosition() {
			return forwardedAuthority == null ? authority.logicalPosition()
				: forwardedAuthority.logicalPosition();
		}
	}

	private static BoundaryMode uploadBoundaryMode(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge) {
		NeutralPlacementGraph.Node consumerNode = analysis.graph().node(edge.consumer()).orElseThrow();
		if(consumerNode.kind() != NodeKind.TRANSIENT_WRITE)
			return BoundaryMode.ANCHOR_TRANSFER;
		Hop consumer = analysis.hop(edge.consumer()).orElseThrow(() ->
			new IllegalArgumentException("EXACT_TWRITE_HOP_UNPROVEN"));
		Hop producer = analysis.hop(edge.producer()).orElseThrow(() ->
			new IllegalArgumentException("EXACT_TWRITE_PRODUCER_UNPROVEN"));
		if(!(consumer instanceof DataOp) || ((DataOp)consumer).getOp() != OpOpData.TRANSIENTWRITE
			|| edge.inputPosition() != 0 || consumer.getInput().size() != 1
			|| consumer.getInput().get(0) != producer)
			throw new IllegalArgumentException("EXACT_TWRITE_EDGE_IDENTITY_UNPROVEN|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition());
		return BoundaryMode.TWRITE_METADATA;
	}

	private static double cpUnaryCost(PlacementAnalysis analysis,
			ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key,
			Hop hop, double executionWeight) {
		double unit = unitLocalCost(analysis, sparseAssignments, key, hop);
		return requireCost(executionWeight * unit, "EXACT_CP_COST_UNPROVEN");
	}

	/** Legacy reflection seam backed by the shared pre-selector frequency authority. */
	private static Map<String,List<OccurrenceProfile>> occurrenceProfiles(PlacementAnalysis analysis) {
		Map<String,List<OccurrenceProfile>> result = new LinkedHashMap<>();
		analysis.executionFrequencyFacts().profilesByPath().forEach((path, facts) ->
			result.put(path, facts.stream().map(fact -> new OccurrenceProfile(
				fact.expectedExecutions(), fact.loopContext(), fact.contextOrdinal())).toList()));
		return Collections.unmodifiableMap(result);
	}

	private static double forwardingWeight(Map<String,List<OccurrenceProfile>> profiles,
		CompiledHopKey consumer, CompiledHopKey producer) {
		List<OccurrenceProfile> consumerProfiles = requireOccurrenceProfiles(profiles, consumer);
		List<OccurrenceProfile> producerProfiles = requireOccurrenceProfiles(profiles, producer);
		double total = 0.0;
		for(OccurrenceProfile consumerProfile : consumerProfiles) {
			OccurrenceProfile producerProfile = producerProfiles.stream()
				.filter(candidate -> candidate.contextOrdinal == consumerProfile.contextOrdinal)
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"EXACT_OCCURRENCE_CONTEXT_UNMATCHED|consumer=" + consumer.normalizedSignature()
						+ "|producer=" + producer.normalizedSignature()));
			total += requireCost(consumerProfile.networkWeight == 0.0 ? 0.0
				: PlacementCostSemantics.forwardingWeight(consumerProfile.networkWeight,
					consumerProfile.loopContext, producerProfile.loopContext),
				"EXACT_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requireCost(total, "EXACT_FORWARDING_WEIGHT_UNPROVEN");
	}

	private static List<OccurrenceProfile> requireOccurrenceProfiles(
		Map<String,List<OccurrenceProfile>> profiles, CompiledHopKey key) {
		List<String> regionPath = key.controlRegion().regionPath();
		if(regionPath.size() != 1)
			throw new IllegalArgumentException("EXACT_OCCURRENCE_PATH_UNPROVEN|key="
				+ key.normalizedSignature() + "|paths=" + regionPath);
		List<OccurrenceProfile> pathProfiles = profiles.get(regionPath.get(0));
		if(pathProfiles == null || pathProfiles.isEmpty())
			throw new IllegalArgumentException("EXACT_OCCURRENCE_PATH_UNPROVEN|path=" + regionPath.get(0));
		return pathProfiles;
	}

	static int workerCount(NeutralPlacementGraph graph) {
		Set<String> workers = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : graph.nodes())
			for(var anchor : node.anchors())
				for(var partition : anchor.partitions())
					workers.add(FederationUtils.canonicalFederatedWorkerAddress(partition.workerId()));
		return workers.size();
	}

	private static double estimatedBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		var estimateFact = analysis.physicalCostEstimateFact(key);
		if(estimateFact.dataType() != null && estimateFact.dataType().isScalar())
			return 8.0;
		double semantic = sparseAssignments.serializedEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		double multiReturnEstimate = estimateFact.multiReturnOutputMemEstimate();
		if(Double.isFinite(multiReturnEstimate) && multiReturnEstimate > 0.0)
			return multiReturnEstimate;
		double estimate = estimateFact.outputMemEstimate();
		boolean unresolvedMatrixShape = estimateFact.dataType() != null && estimateFact.dataType().isMatrix()
			&& (!estimateFact.dimensionsKnown() || estimateFact.rows() <= 0 || estimateFact.cols() <= 0);
		// A positive raw estimate is not necessarily concrete: unknown-dimension HOPs carry
		// a large sentinel-sized envelope.  Returning it here bypassed the shared effective
		// estimate and made Exact price small recompiled inputs (for example PCA Components)
		// as multi-gigabyte broadcasts.  Prefer exact immutable shape evidence below and,
		// when that is unavailable, the same bounded estimate used by DP.
		if(Double.isFinite(estimate) && estimate > 0.0 && !unresolvedMatrixShape)
			return estimate;
		ExactMatrixShape exactShape = exactMatrixShape(analysis, key,
			Collections.newSetFromMap(new IdentityHashMap<>()));
		double derived = exactShape == null ? Double.NaN : exactShape.bytes();
		if(!Double.isFinite(derived) || derived <= 0.0)
			derived = PlacementCostSemantics.boundedDenseOutputBytes(analysis, key);
		if((!Double.isFinite(derived) || derived <= 0.0) && hop instanceof DataOp data
			&& (data.getOp() == OpOpData.TRANSIENTWRITE || data.getOp() == OpOpData.PERSISTENTWRITE)) {
			CompiledHopKey input = exactCompiledInput(analysis, key, 0);
			if(input != null) {
				double inputEstimate = analysis.physicalCostEstimateFact(input).outputMemEstimate();
				if(Double.isFinite(inputEstimate) && inputEstimate > 0.0)
					derived = inputEstimate;
				else {
					ExactMatrixShape inputShape = exactMatrixShape(analysis, input,
						Collections.newSetFromMap(new IdentityHashMap<>()));
					if(inputShape != null)
						derived = inputShape.bytes();
				}
			}
		}
		if(!Double.isFinite(derived) || derived <= 0.0)
			derived = estimateFact.effectiveOutputMemEstimate();
		if(!Double.isFinite(derived) || derived <= 0.0)
			derived = estimate;
		if(!Double.isFinite(derived) || derived <= 0.0)
			derived = anchorBytes(analysis, key);
		if(!Double.isFinite(derived) || derived <= 0.0)
			throw new IllegalArgumentException("EXACT_OUTPUT_BYTES_UNPROVEN|key="
				+ key.normalizedSignature());
		return derived;
	}

	private static ExactMatrixShape exactMatrixShape(PlacementAnalysis analysis,
		CompiledHopKey key, Set<CompiledHopKey> visiting) {
		if(!visiting.add(key))
			return null;
		try {
			double abstractBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key);
			if(Double.isFinite(abstractBytes) && abstractBytes > 0.0) {
				var abstractShape = analysis.abstractShapeFact(key).orElseThrow();
				return new ExactMatrixShape(abstractShape.rows().value(), abstractShape.cols().value());
			}
			ExactMatrixShape captured = analysis.shapeFact(key)
				.filter(shape -> shape.rows() > 0 && shape.cols() > 0)
				.map(shape -> new ExactMatrixShape(shape.rows(), shape.cols())).orElse(null);
			if(captured != null)
				return captured;
			ExactMatrixShape anchored = anchorShape(analysis, key);
			if(anchored != null)
				return anchored;

			ExactMatrixShape logicalFunctionShape = null;
			for(LogicalFunctionInputFact fact : analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(fact.targetRead() != key)
					continue;
				ExactMatrixShape source = exactMatrixShape(analysis, fact.sourceArgument(), visiting);
				if(source == null)
					continue;
				if(logicalFunctionShape != null && !logicalFunctionShape.equals(source))
					return null;
				logicalFunctionShape = source;
			}
			if(logicalFunctionShape != null)
				return logicalFunctionShape;

			ExactMatrixShape logicalTransientShape = null;
			for(LogicalTransientInputFact fact : analysis.logicalTransientInputsInCanonicalOrder()) {
				if(fact.targetRead() != key)
					continue;
				ExactMatrixShape source = exactMatrixShape(analysis, fact.sourceWrite(), visiting);
				if(source == null)
					continue;
				if(logicalTransientShape != null && !logicalTransientShape.equals(source))
					return null;
				logicalTransientShape = source;
			}
			if(logicalTransientShape != null)
				return logicalTransientShape;

			ExactMatrixShape cfgDefinitionShape = null;
			for(CompiledHopKey sourceKey : analysis.cfgDefinitionSourcesInCanonicalOrder(key)) {
				ExactMatrixShape source = exactMatrixShape(analysis, sourceKey, visiting);
				if(source == null)
					continue;
				if(cfgDefinitionShape != null && !cfgDefinitionShape.equals(source))
					return null;
				cfgDefinitionShape = source;
			}
			if(cfgDefinitionShape != null)
				return cfgDefinitionShape;

			Hop hop = analysis.hop(key).orElse(null);
			var estimate = hop == null ? null : analysis.physicalCostEstimateFact(key);
			if(estimate != null && estimate.multiReturnRows() > 0L && estimate.multiReturnCols() > 0L)
				return new ExactMatrixShape(estimate.multiReturnRows(), estimate.multiReturnCols());
			// Only these operations can derive a shape from input zero. Other
			// operations already exhausted their own immutable shape authorities.
			if(hop instanceof AggUnaryOp aggregate) {
				if(aggregate.getDirection() != org.apache.sysds.common.Types.Direction.Row
					&& aggregate.getDirection() != org.apache.sysds.common.Types.Direction.Col)
					return null;
			}
			else if(!(hop instanceof DataOp data
				&& (data.getOp() == OpOpData.TRANSIENTWRITE || data.getOp() == OpOpData.PERSISTENTWRITE)))
				return null;
			CompiledHopKey input = exactCompiledInput(analysis, key, 0);
			ExactMatrixShape inputShape = input == null ? null
				: exactMatrixShape(analysis, input, visiting);
			if(inputShape == null)
				return null;
			if(hop instanceof AggUnaryOp aggregate)
				return aggregate.getDirection() == org.apache.sysds.common.Types.Direction.Row
					? new ExactMatrixShape(inputShape.rows(), 1L)
					: new ExactMatrixShape(1L, inputShape.cols());
			return inputShape;
		}
		finally {
			visiting.remove(key);
		}
	}

	private static CompiledHopKey exactCompiledInput(PlacementAnalysis analysis,
		CompiledHopKey consumer, int inputPosition) {
		if(consumer == null || inputPosition < 0)
			return null;
		var node = analysis.graph().node(consumer).orElse(null);
		if(node == null || node.key() != consumer)
			return null;
		// Construction rejects duplicate consumer/position edges. Retain the old
		// identity-only lookup while using the analysis-owned constant-time index.
		return analysis.compiledInputEdge(consumer, inputPosition)
			.map(CompiledInputEdgeFact::producer).orElse(null);
	}

	private static ExactMatrixShape anchorShape(PlacementAnalysis analysis, CompiledHopKey key) {
		NeutralPlacementGraph.Node node = analysis.graph().node(key).orElseThrow();
		if(node.anchors().size() != 1)
			return null;
		DurableAnchorKey anchor = node.anchors().get(0);
		long rows = 0L;
		long cols = 0L;
		for(AnchorPartition partition : anchor.partitions()) {
			if(partition.end().size() < 2)
				return null;
			rows = Math.max(rows, partition.end().get(0));
			cols = Math.max(cols, partition.end().get(1));
		}
		return rows > 0L && cols > 0L ? new ExactMatrixShape(rows, cols) : null;
	}

	private record ExactMatrixShape(long rows, long cols) {
		private ExactMatrixShape {
			if(rows <= 0L || cols <= 0L)
				throw new IllegalArgumentException("EXACT_MATRIX_SHAPE_INVALID");
		}

		private double bytes() {
			return (double)rows * cols * 8.0;
		}
	}

	private static double anchorBytes(PlacementAnalysis analysis, CompiledHopKey key) {
		ExactMatrixShape shape = anchorShape(analysis, key);
		return shape == null ? Double.NaN : shape.bytes();
	}

	private static double requireCost(double value, String reason) {
		if(!Double.isFinite(value) || value < 0.0
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(requireCost(value, "EXACT_COST_BITS_UNPROVEN"));
	}

	private static final class OccurrenceProfile {
		private final double networkWeight;
		private final List<Pair<Long,Double>> loopContext;
		private final long contextOrdinal;
		OccurrenceProfile(double networkWeight, List<Pair<Long,Double>> loopContext,
			long contextOrdinal) {
			this.networkWeight = requireCost(networkWeight,
				"EXACT_OCCURRENCE_WEIGHT_UNPROVEN");
			this.loopContext = List.copyOf(loopContext);
			this.contextOrdinal = contextOrdinal;
		}
	}
}

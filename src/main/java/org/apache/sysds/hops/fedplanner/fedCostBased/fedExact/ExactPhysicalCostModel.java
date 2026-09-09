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
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.LatentWdivmmRuntimeTransferBoundary;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Shared exact physical objective over the canonical placement analysis. */
public final class ExactPhysicalCostModel {
	enum Direction { UPLOAD, DOWNLOAD }
	enum BoundaryMode { ANCHOR_TRANSFER, TWRITE_METADATA, RUNTIME_FUSED_INPUT }

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

	static record SolverFactorization(
		List<ExactCategoricalSolver.Variable> auxiliaryVariables,
		List<ExactCategoricalSolver.Factor> factors, String semanticDescriptor) {
		SolverFactorization {
			auxiliaryVariables = List.copyOf(auxiliaryVariables);
			factors = List.copyOf(factors);
			if(semanticDescriptor == null || semanticDescriptor.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_FACTOR_DESCRIPTOR_INVALID");
		}
	}

	static record PhysicalCostSurface(PlacementAnalysis owner, String ownerFingerprint,
		List<ExactCategoricalSolver.Variable> variables,
		List<PhysicalContribution> contributions, List<PhysicalTransferKey> transferKeys,
		String contributionFingerprint,
		List<ExactCategoricalSolver.Variable> exactSolverVariables,
		List<ExactCategoricalSolver.Factor> exactSolverFactors) {
		PhysicalCostSurface {
			Objects.requireNonNull(owner, "owner");
			if(ownerFingerprint == null || ownerFingerprint.isBlank()
				|| !owner.analysisFingerprint().equals(ownerFingerprint))
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_OWNER_INVALID");
			variables = List.copyOf(variables);
			contributions = List.copyOf(contributions);
			transferKeys = List.copyOf(transferKeys);
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_FINGERPRINT_INVALID");
			exactSolverVariables = List.copyOf(exactSolverVariables);
			exactSolverFactors = List.copyOf(exactSolverFactors);
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
	}

	/** Trace original physical contributions once; incident costs and solver auxiliaries are not additive. */
	static void traceCanonicalContributions(String planner,
		List<ExactCategoricalSolver.Variable> variables, List<PhysicalContribution> contributions,
		List<Integer> assignment, long expectedObjectiveBits, BiConsumer<String,String> sink) {
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
			StringBuilder scope = new StringBuilder();
			for(int index = 0; index < local.length; index++) {
				Integer global = positions.get(contribution.factor().scope().get(index));
				if(global == null)
					throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_FOREIGN_VARIABLE");
				local[index] = assignment.get(global);
				if(index > 0)
					scope.append(',');
				scope.append(global);
			}
			double value = contribution.factor().cost(local);
			long valueBits = bits(value);
			sum.addBits(valueBits, "EXACT_PHYSICAL_TRACE_COST_INVALID",
				"EXACT_PHYSICAL_TRACE_SUM_INVALID");
			String id = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(contribution.id().getBytes(StandardCharsets.UTF_8));
			sink.accept("Physical-CostContribution", "planner=" + planner + " ordinal=" + ordinal
				+ " unit=ms value=" + Double.toString(value)
				+ " valueBits=" + Long.toUnsignedString(valueBits) + " idBase64=" + id
				+ " scope=" + (scope.length() == 0 ? "-" : scope.toString()));
		}
		long sumBits = sum.totalBits("EXACT_PHYSICAL_TRACE_SUM_INVALID");
		if(sumBits != expectedObjectiveBits)
			throw new IllegalArgumentException("EXACT_PHYSICAL_TRACE_OBJECTIVE_MISMATCH");
		sink.accept("Physical-CostContributionComplete", "planner=" + planner
			+ " contributions=" + contributions.size() + " unit=ms objective="
			+ Double.toString(Double.longBitsToDouble(sumBits))
			+ " objectiveBits=" + Long.toUnsignedString(sumBits));
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
		ExpectedSparseAssignmentEstimates sparseAssignments =
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		IdentityHashMap<ExactCategoricalSolver.Factor,String> factorKinds =
			new IdentityHashMap<>();
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations =
			new IdentityHashMap<>();
		List<PhysicalTransferKey> transferKeys = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains =
			new IdentityHashMap<>();
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			domains.put(domain.node().key(), domain);
			addPhysicalUnaryFactor(analysis, sparseAssignments, domain, workers,
				frequencies, factors);
		}
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries =
			new LinkedHashSet<>(PlacementCostSemantics
				.latentWdivmmRuntimeTransferBoundaries(analysis));
		List<EffectiveLogicalFunctionInput> logicalInputs = effectiveLogicalFunctionInputs(analysis);
		addPhysicalCompiledTransferFactors(analysis, sparseAssignments, model.domains(),
			domains, workers, frequencies, logicalInputs, latentRuntimeBoundaries,
			factors, factorizations, transferKeys);
		addPhysicalLatentWdivmmRuntimeInputFactors(analysis, sparseAssignments,
			model.domains(), domains, workers, frequencies, factors, factorKinds,
			factorizations, transferKeys);
		addPhysicalNativeLocalInputTransferFactors(analysis, sparseAssignments, domains,
			workers, frequencies, latentRuntimeBoundaries, factors);
		addPhysicalLogicalFunctionFactors(analysis, sparseAssignments, domains, workers,
			frequencies, latentRuntimeBoundaries, factors, transferKeys);
		List<PhysicalContribution> contributions = new ArrayList<>(factors.size());
		List<ExactCategoricalSolver.Variable> exactSolverVariables =
			new ArrayList<>(model.variables());
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
			}
		}
		List<ExactCategoricalSolver.Factor> frozenOrdinary =
			freezeOrdinaryFactorsAfterPreflight(exactSolverVariables, model.hardFactors(),
				provisionalSolverFactors, ordinaryFactors, limits, ordinaryEvaluationObserver);
		IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor> frozenByOriginal =
			new IdentityHashMap<>();
		for(int index = 0; index < ordinaryFactors.size(); index++)
			frozenByOriginal.put(ordinaryFactors.get(index), frozenOrdinary.get(index));

		FingerprintWriter normalized = new FingerprintWriter();
		normalized.append(analysis.analysisFingerprint());
		// The optimization receipt must bind the complete authority-bearing physical
		// universe, not merely factor scopes. A changed candidate capability/emission or
		// a changed numeric factor table must therefore produce a different certificate
		// even when a reconstructed analysis reuses the old structural fingerprint.
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
			normalized.append("|candidate:").append(physicalCandidateFactSignature(fact));
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			normalized.append("|domain:").append(domain.node().key().normalizedSignature());
			for(ExactPhysicalModel.Alternative alternative : domain.alternatives())
				normalized.append("|alternative:").append(alternative.signature());
		}
		List<ExactCategoricalSolver.Factor> exactSolverFactors = new ArrayList<>();
		for(int index = 0; index < factors.size(); index++) {
			ExactCategoricalSolver.Factor factor = factors.get(index);
			String id = String.format("%08d", index) + '|'
				+ factorKinds.getOrDefault(factor, "GENERIC") + '|'
				+ factor.scope().stream().map(ExactCategoricalSolver.Variable::key).toList();
			normalized.append('|').append(id);
			SolverFactorization factorization = factorizations.get(factor);
			if(factorization == null) {
				ExactCategoricalSolver.Factor frozen = frozenByOriginal.get(factor);
				contributions.add(new PhysicalContribution(id, frozen));
				normalized.append("|values=");
				appendPhysicalFactorValues(normalized, frozen);
				exactSolverFactors.add(frozen);
			}
			else {
				contributions.add(new PhysicalContribution(id, factor));
				normalized.append("|structured=").append(factorization.semanticDescriptor());
				exactSolverFactors.addAll(factorization.factors());
			}
		}
		for(PhysicalTransferKey key : transferKeys)
			normalized.append("|transfer:").append(key);
		return new PhysicalCostSurface(analysis, analysis.analysisFingerprint(), model.variables(), contributions,
			transferKeys, normalized.finish(), exactSolverVariables, exactSolverFactors);
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

	private static String physicalCandidateFactSignature(CandidateRuleFact fact) {
		return fact.key().normalizedSignature() + "|status=" + fact.status()
			+ "|capability=" + fact.capability() + "|shape=" + fact.shapeProof()
			+ "|profile=" + fact.profile() + "|emissions="
			+ fact.allowedEmissionFacts().stream().map(CandidateEmissionFact::normalizedSignature).toList()
			+ "|failure=" + fact.failureCode();
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
		appendPhysicalFactorValues(normalized, factor, 0, new int[factor.scope().size()]);
	}

	private static void appendPhysicalFactorValues(FingerprintWriter normalized,
		ExactCategoricalSolver.Factor factor, int position, int[] values) {
		if(position == values.length) {
			normalized.append(Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values)), 16))
				.append(',');
			return;
		}
		for(int value = 0; value < factor.scope().get(position).domainSize(); value++) {
			values[position] = value;
			appendPhysicalFactorValues(normalized, factor, position + 1, values);
		}
	}

	private static final class FingerprintWriter {
		private final MessageDigest digest;

		private FingerprintWriter() {
			try {
				digest = MessageDigest.getInstance("SHA-256");
			}
			catch(NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 is unavailable", ex);
			}
		}

		private FingerprintWriter append(Object value) {
			digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
			return this;
		}

		private String finish() {
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
		if(domain.node().kind() == NodeKind.FUNCTION_INPUT
			|| domain.node().kind() == NodeKind.FUNCTION_OUTPUT) {
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
		for(int value = 0; value < execution.length; value++) {
			ExactPhysicalModel.Alternative alternative = domain.alternatives().get(value);
			PlacementState state = alternative.state();
			if(state.execType() == ExecType.CP) {
				execution[value] = cpUnaryCost(analysis, domain.node().key(), hop, weight);
				if(state.output() == FederatedOutput.FOUT)
					nativeCpUpload[value] = physicalResultUploadCost(analysis, sparseAssignments,
						domain.node().key(), hop, state.fType(), workers, weight);
				continue;
			}
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			FType executionFType = emission == null ? state.fType() : emission.executionFType();
			boolean federatedSource = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			List<FType> inputFTypes = federatedSource ? List.of() : alternative.orderedInputs().stream()
				.map(input -> input.present() ? input.fType() : null).toList();
			FedCostProjection projection = fedCostProjection(analysis, sparseAssignments,
				domain.node().key(), hop, inputFTypes, executionFType, workers, weight);
			boolean derivedFout = state.output() == FederatedOutput.FOUT && emission != null
				&& emission.emissionState().derivedFedFout();
			execution[value] = derivedFout
				? requireCost(projection.fedUnaryCost() + projection.resultDownloadCost(),
					"EXACT_PHYSICAL_FED_DOWNLOAD_COST_UNPROVEN")
				: projection.fedUnaryCost();
			if(derivedFout)
				outputMaterialization[value] = physicalResultUploadCost(analysis, sparseAssignments,
					domain.node().key(), hop, state.fType(), workers, weight);
			else if(state.output() == FederatedOutput.LOUT)
				nativeFedDownload[value] = projection.resultDownloadCost();
		}
		// Preserve the established edge-level arithmetic grouping, including the one case
		// where FED unary and derived-FOUT execution download share compute->sink.
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), execution));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), outputMaterialization));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeFedDownload));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeCpUpload));
	}

	private static double physicalResultUploadCost(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop,
		FType fType, int workers, double weight) {
		return requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(
			effectiveUploadBytes(analysis, sparseAssignments, key, hop), fType, workers)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(fType, workers)),
			"EXACT_RESULT_UPLOAD_COST_UNPROVEN");
	}

	private static void addPhysicalCompiledTransferFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Factor,SolverFactorization> factorizations,
		List<PhysicalTransferKey> transferKeys) {
		record Demand(CompiledInputEdgeFact edge, ExactPhysicalModel.DecisionDomain consumer) { }
		record Key(Direction direction, FType type, BoundaryMode boundary,
			String physicalEmissionIdentity) { }
		record CreationScope(CompiledHopKey origin,
			OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact profile) { }
		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead = new IdentityHashMap<>();
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
			transientByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
		IdentityHashMap<CompiledHopKey,List<LogicalFunctionInputFact>> functionByRead = new IdentityHashMap<>();
		for(var fact : analysis.logicalFunctionInputsInCanonicalOrder())
			functionByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
		for(ExactPhysicalModel.DecisionDomain producer : orderedDomains) {
			if(producer.node().kind() == NodeKind.FUNCTION_INPUT
				|| producer.node().kind() == NodeKind.FUNCTION_OUTPUT)
				continue;
			Hop producerHop = analysis.hop(producer.node().key()).orElseThrow();
			if(producerHop.getDataType() == null
				|| (!producerHop.getDataType().isMatrix() && !producerHop.getDataType().isFrame()))
				continue;
			double bytes = estimatedBytes(analysis, sparseAssignments, producer.node().key(), producerHop);
			boolean forwarded = functionInputsForTarget(effectiveFunctionInputs, producer.node().key())
				.stream().anyMatch(input -> input.forwardedAuthority() != null);
			Map<Key,List<Demand>> grouped = new LinkedHashMap<>();
			for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.producer() != producer.node().key()
					|| analysis.graph().node(edge.consumer()).orElseThrow().kind() == NodeKind.FUNCTION_CALL
					|| latentRuntimeBoundaries.contains(new LatentWdivmmRuntimeTransferBoundary(
						edge.producer(), edge.consumer(), edge.inputPosition())))
					continue;
				ExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
				if(consumer == null)
					continue;
				if(!analysis.isCoordinatorMetadataOnlyInput(edge))
					for(FType type : producer.alternatives().stream().map(a -> a.state().fType())
						.filter(Objects::nonNull).distinct().toList())
						grouped.computeIfAbsent(new Key(Direction.DOWNLOAD, type,
							BoundaryMode.ANCHOR_TRANSFER, "-"), ignored -> new ArrayList<>())
							.add(new Demand(edge, consumer));
				for(RelocationAction action : consumer.alternatives().stream()
					.flatMap(a -> a.inputAuthorities().stream())
					.filter(a -> a.inputPosition() == edge.inputPosition()
						&& a.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
						&& a.relocationAction().key().sourceValueVersion().equals(producer.node().valueVersion()))
					.map(ExactPhysicalModel.InputAuthority::relocationAction)
					.distinct().sorted().toList()) {
					Key key = new Key(Direction.UPLOAD, action.key().materializationFType(),
						uploadBoundaryMode(analysis, edge), RelocationSelections.physicalEmissionIdentity(action.key()));
					List<Demand> demands = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
					if(demands.stream().noneMatch(d -> d.edge().consumer() == edge.consumer()
						&& d.edge().inputPosition() == edge.inputPosition()))
						demands.add(new Demand(edge, consumer));
				}
			}
			// Transient/formal reads do not create a new payload. Resolve the same
			// compiler-owned alias provenance used by latent reusable input costing.
			List<RuntimeMaterializationSource> creationSources = grouped.isEmpty() ? List.of()
				: runtimeMaterializationSources(analysis, producer.node().key(), transientByRead,
					functionByRead, Collections.newSetFromMap(new IdentityHashMap<>()));
			for(Map.Entry<Key,List<Demand>> entry : grouped.entrySet()) {
				List<Demand> demands = entry.getValue().stream().sorted(Comparator
					.comparing((Demand d) -> d.edge().consumer().normalizedSignature())
					.thenComparingInt(d -> d.edge().inputPosition())).toList();
				Key key = entry.getKey();
				boolean[] activeSource = new boolean[producer.alternatives().size()];
				double[] unitPrices = new double[activeSource.length];
				for(int value = 0; value < activeSource.length; value++) {
					PlacementState state = producer.alternatives().get(value).state();
					activeSource[value] = key.direction() == Direction.UPLOAD
						|| state.output() == FederatedOutput.FOUT && state.fType() == key.type();
					double unit = key.direction() == Direction.DOWNLOAD
						? FederatedCostModel.computeReusableMaterializationDownloadCost(bytes, key.type(), workers)
						: FederatedCostModel.computeUploadNetworkCost(bytes, key.type(), workers)
							+ FederatedCostModel.computeLocalToFedForwardingPenalty(key.type(), workers);
					if(key.direction() == Direction.UPLOAD && state.output() == FederatedOutput.FOUT)
						unit += forwarded ? FederatedCostModel.computeDownloadNetworkCost(bytes)
							: FederatedCostModel.computeReusableMaterializationDownloadCost(bytes,
								Objects.requireNonNull(state.fType(), "FOUT source layout"), workers);
					unitPrices[value] = requireCost(unit, "EXACT_PHYSICAL_MATERIALIZATION_UNIT_UNPROVEN");
				}
				String groupKey = "exact-materialization|" + producer.node().key().normalizedSignature()
					+ '|' + key.direction() + '|' + key.type() + '|' + key.boundary()
					+ '|' + key.physicalEmissionIdentity() + '|' + transferKeys.size();
				Map<CreationScope,List<ActivationDemand>> demandsByCreation = new LinkedHashMap<>();
				for(var readProfile : exactOccurrenceProfiles(frequencies, producer.node().key())) {
					CompiledHopKey origin = producer.node().key();
					var sourceProfile = readProfile;
					if(creationSources.size() == 1) {
						CompiledHopKey candidate = creationSources.get(0).occurrence();
						var profiles = exactOccurrenceProfiles(frequencies, candidate);
						var matched = profiles.stream().filter(profile -> profile.contextOrdinal()
							== readProfile.contextOrdinal()).findFirst().orElse(null);
						if(matched == null && profiles.size() == 1)
							matched = profiles.get(0);
						if(matched != null) {
							origin = candidate;
							sourceProfile = matched;
						}
					}
					// Ambiguous reaching definitions/context mappings retain the read scope;
					// they never authorize reuse of an earlier version or a different call.
					List<ActivationDemand> activations = demandsByCreation.computeIfAbsent(
						new CreationScope(origin, sourceProfile), ignored -> new ArrayList<>());
					for(Demand demand : demands) {
						boolean[] activeConsumers = new boolean[demand.consumer().alternatives().size()];
						for(int value = 0; value < activeConsumers.length; value++) {
							var consumer = demand.consumer().alternatives().get(value);
							activeConsumers[value] = key.direction() == Direction.DOWNLOAD
								? consumer.state().execType() == ExecType.CP
								: consumer.inputAuthorities().stream().anyMatch(authority ->
									authority.inputPosition() == demand.edge().inputPosition()
										&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
										&& authority.expectedFType() == key.type()
										&& authority.relocationAction().key().sourceValueVersion()
											.equals(producer.node().valueVersion())
										&& RelocationSelections.physicalEmissionIdentity(authority.relocationAction().key())
											.equals(key.physicalEmissionIdentity()));
						}
						var consumerProfile = requireContextProfile(frequencies, demand.edge().consumer(),
							readProfile.contextOrdinal());
						activations.add(new ActivationDemand(List.of(demand.consumer().variable()),
							List.of(activeConsumers), materializationActivation(sourceProfile, consumerProfile)));
					}
				}
				for(var creation : demandsByCreation.entrySet())
					addMaterializationActivationFactors(groupKey + "|creation="
						+ creation.getKey().origin().normalizedSignature()
						+ "|context=" + creation.getKey().profile().contextOrdinal(), producer.variable(),
						activeSource, unitPrices, creation.getValue(), creation.getKey().profile().expectedExecutions(),
						factors, factorizations, null);

				transferKeys.add(new PhysicalTransferKey(producer.node().valueVersion(), demands.stream()
					.map(d -> new PhysicalTransferEndpoint(d.edge().producer(), d.edge().consumer(),
						d.edge().inputPosition())).toList(), key.direction(), key.type(), key.boundary(),
					key.physicalEmissionIdentity()));
			}
		}
	}

	/** A selected plan demands a copy when every observation in this demand is true. */
	record ActivationDemand(List<ExactCategoricalSolver.Variable> variables,
		List<boolean[]> observations, ExactMaterializationActivation.Event event) {
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

	/**
	 * Project a demand onto one source creation lifetime. Conditions outside repeated
	 * consumer-only loops retain their conditional compiler weight. Inside such loops,
	 * opposite arms can both occur during one copy lifetime: their existence events are
	 * unresolved, not mutually exclusive. Raw occurrence counts give a union upper bound.
	 */
	static ExactMaterializationActivation.Event materializationActivation(
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact source,
		OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact consumer) {
		Set<Long> sourceLoops = source.loopContext().stream().map(Pair::getLeft)
			.collect(java.util.stream.Collectors.toSet());
		double cap = source.expectedExecutions();
		List<BranchLiteral> conditions = new ArrayList<>();
		for(var condition : consumer.activationConditions()) {
			var sourceCondition = source.activationConditions().stream()
				.filter(candidate -> candidate.decisionKey().equals(condition.decisionKey()))
				.findFirst().orElse(null);
			if(sourceCondition != null) {
				if(sourceCondition.ifArm() != condition.ifArm())
					return new ExactMaterializationActivation.Event(0d, List.of());
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
		StringBuilder descriptor = new StringBuilder("MATERIALIZATION_ACTIVATION_V1|")
			.append(key).append('|').append(partition.semanticDescriptor())
			.append("|source=").append(source.key()).append("|sourceActive=")
			.append(java.util.Arrays.toString(activeSource));
		for(double unit : unitPrices)
			descriptor.append("|unitBits=").append(Long.toUnsignedString(Double.doubleToRawLongBits(unit), 16));
		for(ActivationDemand demand : demands)
			for(int index = 0; index < demand.variables().size(); index++)
				descriptor.append("|observation=").append(demand.variables().get(index).key())
					.append(':').append(java.util.Arrays.toString(demand.observations().get(index)));
		if(!partition.resolved()) {
			List<ExactCategoricalSolver.Variable> scope = activationScope(source, demands);
			var positions = variablePositions(scope);
			ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(scope, values -> {
				int sourceValue = values[positions.get(source)];
				if(!activeSource[sourceValue])
					return 0d;
				boolean[] active = new boolean[demands.size()];
				for(int index = 0; index < active.length; index++)
					active[index] = demands.get(index).active(values, positions);
				return requireCost(unitPrices[sourceValue]
					* ExactMaterializationActivation.conservativeUnion(events, scopeWeight, active),
					"EXACT_ACTIVATION_UNION_COST_UNPROVEN");
			});
			factors.add(canonical);
			factorizations.put(canonical, new SolverFactorization(List.of(), List.of(canonical),
				descriptor + "|encoding=CONSERVATIVE_CAPPED_ACTIVATION_UNION"));
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
					ExactCategoricalSolver.Variable active = new ExactCategoricalSolver.Variable(
						classKey + "|demand=" + index, 2);
					auxiliaries.add(active);
					List<ExactCategoricalSolver.Variable> demandScope = new ArrayList<>();
					for(var variable : demand.variables())
						if(demandScope.stream().noneMatch(existing -> existing == variable))
							demandScope.add(variable);
					var demandPositions = variablePositions(demandScope);
					demandScope.add(active);
					int activePosition = demandScope.size() - 1;
					solverFactors.add(ExactCategoricalSolver.Factor.lazy(demandScope, values ->
						values[activePosition] == (demand.active(values, demandPositions) ? 1 : 0)
							? 0d : Double.POSITIVE_INFINITY));
					observations.add(new ExactActivationClassFactorDecomposition.Demand(active,
						new boolean[] {false, true}));
				}
			}
			var decomposition = ExactActivationClassFactorDecomposition.create(classKey, source,
				activeSource, prices, observations);
			auxiliaries.addAll(decomposition.auxiliaryVariables());
			solverFactors.addAll(decomposition.solverFactors());
			factors.add(canonical);
			factorizations.put(canonical, new SolverFactorization(auxiliaries, solverFactors,
				descriptor + "|class=" + activationClass + '|' + decomposition.semanticDescriptor()));
			if(factorKinds != null)
				factorKinds.put(canonical, "RUNTIME_FUSED_INPUT|ACTIVATION_CLASS");
		}
		// Even a zero-cost group binds its scope, event facts and prices in the receipt.
		if(partition.classes().isEmpty()) {
			var canonical = ExactCategoricalSolver.Factor.lazy(activationScope(source, demands), values -> 0d);
			factors.add(canonical);
			factorizations.put(canonical, new SolverFactorization(List.of(), List.of(), descriptor.toString()));
			if(factorKinds != null)
				factorKinds.put(canonical, "RUNTIME_FUSED_INPUT|ACTIVATION_CLASS");
		}
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
			if(runtime == null)
				continue;
			ExactPhysicalModel.DecisionDomain read = domains.get(runtime.weights());
			if(read == null)
				throw new IllegalArgumentException(
					"EXACT_LATENT_WDIVMM_RUNTIME_INPUT_DOMAIN_MISSING|owner="
						+ owner.node().key().normalizedSignature());
			List<RuntimeMaterializationSource> sources = runtimeMaterializationSources(
				analysis, runtime.weights(), transientByRead, functionByRead,
				Collections.newSetFromMap(new IdentityHashMap<>()));
			if(sources.isEmpty())
				throw new IllegalArgumentException("EXACT_LATENT_WDIVMM_RUNTIME_SOURCE_CYCLE|read="
					+ runtime.weights().normalizedSignature());
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
			for(int index = 0; index < unitPrices.length; index++)
				unitPrices[index] = cpOwner == null ? 0d : requireCost(PlacementCostSemantics
					.latentWdivmmCpRuntimeInputMaterializationCost(bytes, cpOwner,
						source.alternatives().get(index).state(), workers),
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
		ValueVersionKey valueVersion) { }
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
			CompiledHopKey passThroughRead = runtimeMaterializationPassThroughRead(
				analysis, read);
			if(direct.isEmpty() && passThroughRead != null)
				return runtimeMaterializationSources(analysis, passThroughRead,
					transientByRead, functionByRead, visiting);
			if(direct.isEmpty()) {
				ValueVersionKey value = analysis.graph().node(read).orElseThrow().valueVersion();
				return List.of(new RuntimeMaterializationSource(read, value));
			}
			Map<String,RuntimeMaterializationSource> resolved = new LinkedHashMap<>();
			for(CompiledHopKey source : direct.stream().distinct().sorted().toList()) {
				List<RuntimeMaterializationSource> authorities = runtimeMaterializationSources(
					analysis, source, transientByRead, functionByRead, visiting);
				if(authorities.isEmpty())
					return List.of();
				for(RuntimeMaterializationSource authority : authorities)
					resolved.putIfAbsent(authority.occurrence().normalizedSignature() + '|'
						+ authority.valueVersion().normalizedSignature(), authority);
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
	 * Collapse compiler-inserted {@code W = W} transient carriers.  These writes
	 * execute at statement-block/loop boundaries but do not create a new MatrixObject
	 * payload or invalidate an already materialized local block.  Charging their
	 * execution frequency would multiply a one-time runtime materialization by the
	 * enclosing loop count.
	 */
	private static CompiledHopKey runtimeMaterializationPassThroughRead(
		PlacementAnalysis analysis, CompiledHopKey occurrence) {
		Hop hop = analysis.hop(occurrence).orElseThrow();
		if(!(hop instanceof DataOp write) || write.getOp() != OpOpData.TRANSIENTWRITE)
			return null;
		List<CompiledInputEdgeFact> inputs = analysis.compiledInputEdgesInCanonicalOrder()
			.stream().filter(edge -> edge.consumer() == occurrence).toList();
		if(inputs.size() != 1 || inputs.get(0).inputPosition() != 0)
			return null;
		CompiledHopKey producer = inputs.get(0).producer();
		Hop producerHop = analysis.hop(producer).orElseThrow();
		return producerHop instanceof DataOp read
			&& read.getOp() == OpOpData.TRANSIENTREAD
			&& Objects.equals(write.getName(), read.getName()) ? producer : null;
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
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<ExactCategoricalSolver.Factor> factors) {
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
			int[] targetWorkerCounts = consumer.alternatives().stream()
				.mapToInt(target -> nativeLocalInputWorkerCount(target.inputAuthorities(), workers)).toArray();
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(producer.variable(), consumer.variable()), values -> {
					ExactPhysicalModel.Alternative source = producer.alternatives().get(values[0]);
					ExactPhysicalModel.Alternative target = consumer.alternatives().get(values[1]);
					if(target.state().execType() != ExecType.FED
						|| target.inputAuthorities().stream().noneMatch(authority ->
							authority.inputPosition() == edge.inputPosition()
								&& authority.kind()
									== ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL))
						return 0.0;
					int targetWorkers = targetWorkerCounts[values[1]];
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
					FederatedCostModel.MixedFedLocalCost mixed =
						PlacementCostSemantics.analysisAwareMixedFedLocalCost(analysis,
							edge.consumer(), new ArrayList<>(consumerHop.getInput()), inputFTypes, executionFType,
							unitLocalCost(analysis, edge.consumer(), consumerHop),
							effectiveOutputBytes(analysis, sparseAssignments,
								edge.consumer(), consumerHop), targetWorkers);
					double cost;
					if(mixed.hasInputPreparation())
						cost = 0.0;
					else if(fusedInputPreparationBytes >= 0.0)
						cost = FederatedCostModel.computeInBandUploadPayloadCost(
							fusedInputPreparationBytes, FType.BROADCAST, targetWorkers);
					else if(boundedElementwise != null)
						cost = boundedElementwise.uploadPayloadCostUpperBound();
					else
						cost = nativeLocalInputUploadCost(consumerHop, producerHop, bytes,
							executionFType, targetWorkers);
					if(source.state().output() == FederatedOutput.FOUT) {
						FType sourceType = Objects.requireNonNull(source.state().fType(),
							"FOUT native-local source has no exact FType");
						double sourceBytes = boundedElementwise == null ? bytes
							: boundedElementwise.logicalBytesUpperBound();
						cost += FederatedCostModel.computeReusableMaterializationDownloadCost(
							sourceBytes, sourceType, workers);
					}
					return requireCost(weight * cost,
						"EXACT_PHYSICAL_NATIVE_LOCAL_INPUT_COST_UNPROVEN");
				}));
		}
	}

	static int nativeLocalInputWorkerCount(List<ExactPhysicalModel.InputAuthority> authorities,
		int fallbackWorkers) {
		DurableAnchorKey anchor = null;
		for(var authority : authorities) {
			if(authority.relocationAction() == null)
				continue;
			DurableAnchorKey current = authority.relocationAction().key().durableAnchor();
			if(anchor != null && !anchor.equals(current))
				throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_CONSUMER_ANCHOR_CONFLICT");
			anchor = current;
		}
		// The selected consumer's exact input authority determines its runtime map.
		// A graph-wide worker union overcharges FULL uploads and smaller worker pools.
		// Without such authority retain the prior conservative estimate; neither a
		// worker count nor an output FType invents an input FederationMap.
		return anchor == null ? Math.max(1, fallbackWorkers) : anchor.partitions().size();
	}

	private static double nativeLocalInputUploadCost(Hop consumer, Hop input, double bytes,
		FType executionFType, int workers) {
		if(executionFType == null)
			throw new IllegalArgumentException("EXACT_NATIVE_LOCAL_EXECUTION_LAYOUT_UNPROVEN");
		FType transferType = nativeLocalInputTransferType(consumer, input, executionFType);
		return FederatedCostModel.computeInBandUploadPayloadCost(bytes, transferType, workers);
	}

	private static FType nativeLocalInputTransferType(Hop consumer, Hop input,
		FType executionFType) {
		// ROW/COL runtime instructions can sliced-broadcast an equally shaped matrix, so
		// total payload is one logical input. Shape-broadcast operands and FULL/PART worker
		// branches use a replicated broadcast to every participating worker.
		boolean sameShape = input.getDim1() > 0 && input.getDim2() > 0
			&& input.getDim1() == consumer.getDim1() && input.getDim2() == consumer.getDim2();
		return sameShape && (executionFType == FType.ROW || executionFType == FType.COL)
			? executionFType : FType.BROADCAST;
	}

	private static void addPhysicalLogicalFunctionFactors(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		Set<LatentWdivmmRuntimeTransferBoundary> latentRuntimeBoundaries,
		List<ExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys) {
		for(EffectiveLogicalFunctionInput input : effectiveLogicalFunctionInputs(analysis)) {
			ExactPhysicalModel.DecisionDomain source = domains.get(input.authority().sourceArgument());
			ExactPhysicalModel.DecisionDomain formal = domains.get(input.targetRead());
			if(source == null || formal == null)
				continue;
			double bytes = sparseAssignments.serializedEstimate(source.node().key());
			if(!Double.isFinite(bytes) || bytes <= 0.0)
				bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
					analysis.hop(formal.node().key()).orElseThrow(),
					analysis.hop(source.node().key()).orElseThrow());
			double callWeight = frequencies.logicalFunctionCallWeight(input.authority());
			List<FType> sourceTypes = source.alternatives().stream().map(a -> a.state().fType())
				.filter(Objects::nonNull).distinct().toList();
			for(FType type : sourceTypes) {
				double cost = requireCost(callWeight
					* FederatedCostModel.computeReusableMaterializationDownloadCost(
						bytes, type, workers),
					"EXACT_PHYSICAL_LOGICAL_FUNCTION_DOWNLOAD_COST_UNPROVEN");
				factors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(source.variable(), formal.variable()), values -> {
						PlacementState sourceState = source.alternatives().get(values[0]).state();
						return sourceState.output() == FederatedOutput.FOUT && sourceState.fType() == type
							&& formal.alternatives().get(values[1]).state().execType() == ExecType.CP ? cost : 0.0;
					}));
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
					double upload = requireCost(callWeight * (FederatedCostModel.computeUploadNetworkCost(bytes,
						type, workers) + FederatedCostModel.computeLocalToFedForwardingPenalty(type, workers)),
						"EXACT_PHYSICAL_LOGICAL_FUNCTION_UPLOAD_COST_UNPROVEN");
					double refedDownload = requireCost(callWeight
						* FederatedCostModel.computeDownloadNetworkCost(bytes),
						"EXACT_PHYSICAL_LOGICAL_FUNCTION_REFED_DOWNLOAD_COST_UNPROVEN");
					String emissionIdentity = uploadEntry.getKey();
					factors.add(ExactCategoricalSolver.Factor.lazy(
						List.of(source.variable(), formal.variable()), values -> {
							PlacementState sourceState = source.alternatives().get(values[0]).state();
							boolean active = formal.alternatives().get(values[1]).inputAuthorities().stream()
								.anyMatch(authority -> authority.inputPosition() == input.logicalPosition()
									&& authority.kind()
										== ExactPhysicalModel.InputAuthorityKind.RELOCATION
									&& RelocationSelections.physicalEmissionIdentity(
										authority.relocationAction().key()).equals(emissionIdentity));
							return active ? upload + (sourceState.output() == FederatedOutput.FOUT
								? refedDownload : 0.0) : 0.0;
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
		double base = cpUnaryCost(analysis, key, hop, executionWeight);
		return fedCostProjection(analysis, key, hop, inputFTypes, executionFType, workers,
			executionWeight, base, effectiveOutputBytes(analysis, sparseAssignments, key, hop),
			effectiveUploadBytes(analysis, sparseAssignments, key, hop));
	}

	private static FedCostProjection fedCostProjection(PlacementAnalysis analysis,
		CompiledHopKey key, Hop hop, List<FType> inputFTypes, FType executionFType,
		int workers, double executionWeight, double base, double outputBytes,
		double uploadBytes) {
		if(executionFType == null)
			throw new IllegalArgumentException("EXACT_FED_EXECUTION_LAYOUT_UNPROVEN");
		boolean federatedSource = hop instanceof DataOp
			&& ((DataOp)hop).getOp() == OpOpData.FEDERATED;
		boolean broadcastOnlyFedCompute = !federatedSource
			&& broadcastOnlyMatrixInputs(hop, inputFTypes)
			&& !PlacementCostSemantics.hasPartitionedLatentWdivmmRuntimeInput(
				analysis, key);
		double fedCompute = PlacementCostSemantics.analysisAwareFederatedComputeCost(
			analysis, key, base, workers, broadcastOnlyFedCompute);
		fedCompute = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
			hop, executionFType, fedCompute);
		fedCompute = FederatedCostModel.computeNativeFederatedIndexingCost(
			hop, executionFType, fedCompute);
		double fedCoordination = hop instanceof DataOp ? 0.0
			: FederatedCostModel.adjustFedCoordinationCost(hop, executionFType,
				executionWeight * FederatedCostModel.computeFedCoordinationCost(workers));
		double fedInstructionLatency = FederatedCostModel
			.computeControlDominatedFederatedInstructionCost(hop, executionFType,
				executionWeight, workers, broadcastOnlyFedCompute);
		FederatedCostModel.MixedFedLocalCost mixed = hop instanceof DataOp
			? FederatedCostModel.MixedFedLocalCost.none()
			: PlacementCostSemantics.analysisAwareMixedFedLocalCost(analysis, key,
				new ArrayList<>(hop.getInput()), inputFTypes, executionFType,
				executionWeight > 0.0 ? base / executionWeight : 0.0, outputBytes, workers);
		double fedInputPreparation = executionWeight * mixed.getInputPreparationCost();
		// The legacy control-plane heuristic floors a positive call count at one.
		// A compiler-proven unreachable occurrence is not a one-shot invocation.
		double singleWorkerPenalty = executionWeight == 0.0 ? 0.0
			: FederatedCostModel.computeSingleWorkerFedExecPenalty(hop, executionWeight, workers);
		double fedCost = requireCost(fedCompute + fedCoordination + fedInstructionLatency
			+ fedInputPreparation + singleWorkerPenalty, "EXACT_FED_COST_UNPROVEN");

		double resultDownloadUnit = FederatedCostModel.computeDownloadNetworkCost(uploadBytes);
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

	private static boolean broadcastOnlyMatrixInputs(Hop hop, List<FType> inputFTypes) {
		boolean hasMatrix = false;
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop input = hop.getInput(position);
			if(input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			hasMatrix = true;
			FType inputType = position < inputFTypes.size() ? inputFTypes.get(position) : null;
			if(inputType != null && inputType != FType.BROADCAST)
				return false;
		}
		return hasMatrix;
	}

	private static double unitLocalCost(PlacementAnalysis analysis, CompiledHopKey key,
			Hop hop) {
		if(analysis.hop(key).orElse(null) != hop)
			throw new IllegalArgumentException("EXACT_COST_HOP_OCCURRENCE_MISMATCH");
		return PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, key);
	}

	private static double effectiveOutputBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		double semantic = sparseAssignments.memEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		boolean unresolvedMatrixShape = hop.getDataType() != null && hop.getDataType().isMatrix()
			&& (!hop.dimsKnown() || hop.getDim1() <= 0 || hop.getDim2() <= 0);
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		return !unresolvedMatrixShape && Double.isFinite(bytes) && bytes > 0.0 ? bytes
			: estimatedBytes(analysis, sparseAssignments, key, hop);
	}

	private static double effectiveUploadBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		double semantic = sparseAssignments.serializedEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		boolean unresolvedMatrixShape = hop.getDataType() != null && hop.getDataType().isMatrix()
			&& (!hop.dimsKnown() || hop.getDim1() <= 0 || hop.getDim2() <= 0);
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		return !unresolvedMatrixShape && Double.isFinite(bytes) && bytes > 0.0 ? bytes
			: estimatedBytes(analysis, sparseAssignments, key, hop);
	}

	private static List<EffectiveLogicalFunctionInput> effectiveLogicalFunctionInputs(
		PlacementAnalysis analysis) {
		List<EffectiveLogicalFunctionInput> result = new ArrayList<>();
		List<LogicalFunctionInputFact> direct = analysis.logicalFunctionInputsInCanonicalOrder();
		for(LogicalFunctionInputFact fact : direct) {
			if(analysis.requireExactLogicalFunctionInput(fact.sourceArgument(), fact.targetRead(),
				fact.logicalPosition()) != fact)
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
				FType sourceType = exactInputAuthorityType(analysis, authority.sourceArgument());
				List<FType> sourcePlanTypes = analysis.graph().node(authority.sourceArgument()).orElseThrow()
					.legalAlternatives().stream()
					.filter(state -> state.execType() == ExecType.FED
						&& state.output() == FederatedOutput.FOUT && state.fType() != null)
					.map(PlacementState::fType).distinct().toList();
				// A nested function actual can itself be a formal TRead. Such an alias has no
				// durable anchor on the Hop, but its exact FOUT authority is carried by the
				// selected function-boundary state. Requiring a static anchor here rejected the
				// valid m_lm(X)->m_lmCG(X) forwarding chain. A durable source must still match
				// exactly; a parametric source must publish the forwarded layout in its plan domain.
				boolean compatible = sourceType == null
					? sourcePlanTypes.contains(transientFact.federatedFType())
					: sourceType == transientFact.federatedFType();
				if(!compatible)
					throw new IllegalArgumentException("EXACT_FUNCTION_INPUT_FORWARD_LAYOUT_MISMATCH|source="
						+ authority.sourceArgument().normalizedSignature() + "|read="
						+ transientFact.targetRead().normalizedSignature() + "|sourceType=" + sourceType
						+ "|sourcePlanTypes=" + sourcePlanTypes + "|forwardedType="
						+ transientFact.federatedFType() + "|binding="
						+ binding.normalizedSignature() + "|sourceStates="
						+ analysis.graph().node(authority.sourceArgument()).orElseThrow()
							.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList()
						+ "|forwardedWriteStates="
						+ analysis.graph().node(transientFact.sourceWrite()).orElseThrow()
							.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList()
						+ "|targetReadStates="
						+ analysis.graph().node(transientFact.targetRead()).orElseThrow()
							.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList());
				result.add(new EffectiveLogicalFunctionInput(authority, transientFact,
					transientFact.targetRead()));
			}
		}
		return result.stream().sorted(Comparator
			.comparing((EffectiveLogicalFunctionInput input) -> input.targetRead().normalizedSignature())
			.thenComparing(input -> input.authority().sourceArgument().normalizedSignature()))
			.toList();
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

	private static List<EffectiveLogicalFunctionInput> functionInputsForTarget(
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs, CompiledHopKey target) {
		return effectiveFunctionInputs.stream()
			.filter(input -> input.targetRead() == target)
			.toList();
	}

	private static double cpUnaryCost(PlacementAnalysis analysis, CompiledHopKey key,
			Hop hop, double executionWeight) {
		double unit = unitLocalCost(analysis, key, hop);
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

	private static int workerCount(NeutralPlacementGraph graph) {
		Set<String> workers = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : graph.nodes())
			for(var anchor : node.anchors())
				for(var partition : anchor.partitions())
					workers.add(partition.workerId());
		return workers.size();
	}

	private static double estimatedBytes(PlacementAnalysis analysis,
		ExpectedSparseAssignmentEstimates sparseAssignments, CompiledHopKey key, Hop hop) {
		if(hop.getDataType() != null && hop.getDataType().isScalar())
			return 8.0;
		double semantic = sparseAssignments.serializedEstimate(key);
		if(Double.isFinite(semantic) && semantic > 0.0)
			return semantic;
		FunctionOp multiReturnParent = exactMultiReturnBuiltinParent(hop);
		if(multiReturnParent != null) {
			double multiReturnEstimate = multiReturnParent.getMultiReturnBuiltinOutputMemEstimate(hop);
			if(Double.isFinite(multiReturnEstimate) && multiReturnEstimate > 0.0)
				return multiReturnEstimate;
		}
		double estimate = hop.getOutputMemEstimate();
		boolean unresolvedMatrixShape = hop.getDataType() != null && hop.getDataType().isMatrix()
			&& (!hop.dimsKnown() || hop.getDim1() <= 0 || hop.getDim2() <= 0);
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
		if((!Double.isFinite(derived) || derived <= 0.0) && hop instanceof DataOp data
			&& (data.getOp() == OpOpData.TRANSIENTWRITE || data.getOp() == OpOpData.PERSISTENTWRITE)) {
			CompiledHopKey input = exactCompiledInput(analysis, key, 0);
			if(input != null) {
				Hop inputHop = analysis.hop(input).orElse(null);
				double inputEstimate = inputHop == null ? Double.NaN : inputHop.getOutputMemEstimate();
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
			derived = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
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
			FunctionOp multiReturnParent = exactMultiReturnBuiltinParent(hop);
			if(multiReturnParent != null) {
				long[] dims = multiReturnParent.getMultiReturnBuiltinOutputDims(hop);
				if(dims[0] > 0L && dims[1] > 0L)
					return new ExactMatrixShape(dims[0], dims[1]);
			}
			CompiledHopKey input = exactCompiledInput(analysis, key, 0);
			ExactMatrixShape inputShape = input == null ? null
				: exactMatrixShape(analysis, input, visiting);
			if(hop instanceof AggUnaryOp && inputShape != null) {
				org.apache.sysds.common.Types.Direction direction = ((AggUnaryOp)hop).getDirection();
				if(direction == org.apache.sysds.common.Types.Direction.Row)
					return new ExactMatrixShape(inputShape.rows(), 1L);
				if(direction == org.apache.sysds.common.Types.Direction.Col)
					return new ExactMatrixShape(1L, inputShape.cols());
			}
			if(hop instanceof DataOp && inputShape != null) {
				OpOpData op = ((DataOp)hop).getOp();
				if(op == OpOpData.TRANSIENTWRITE || op == OpOpData.PERSISTENTWRITE)
					return inputShape;
			}
			return null;
		}
		finally {
			visiting.remove(key);
		}
	}

	private static FunctionOp exactMultiReturnBuiltinParent(Hop hop) {
		if(!(hop instanceof DataOp data) || data.getOp() != OpOpData.FUNCTIONOUTPUT
			|| hop.getInput() == null || hop.getInput().isEmpty() || hop.getInput().get(0) == null)
			return null;
		FunctionOp resolved = null;
		for(Hop parent : hop.getInput().get(0).getParent()) {
			if(!(parent instanceof FunctionOp functionOp)
				|| functionOp.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				|| functionOp.getOutputs() == null
				|| functionOp.getOutputs().stream().noneMatch(output -> output == hop))
				continue;
			if(resolved != null && resolved != functionOp)
				return null;
			resolved = functionOp;
		}
		return resolved;
	}

	private static CompiledHopKey exactCompiledInput(PlacementAnalysis analysis,
		CompiledHopKey consumer, int inputPosition) {
		List<CompiledHopKey> inputs = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer && edge.inputPosition() == inputPosition)
			.map(CompiledInputEdgeFact::producer).toList();
		return inputs.size() == 1 ? inputs.get(0) : null;
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

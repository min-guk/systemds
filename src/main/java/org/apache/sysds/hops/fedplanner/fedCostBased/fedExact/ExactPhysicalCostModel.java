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
import java.util.Collections;
import java.util.Comparator;
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
	enum BoundaryMode { ANCHOR_TRANSFER, TWRITE_METADATA }

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

	static record PhysicalCostSurface(PlacementAnalysis owner, String ownerFingerprint,
		List<ExactCategoricalSolver.Variable> variables,
		List<PhysicalContribution> contributions, List<PhysicalTransferKey> transferKeys,
		String contributionFingerprint) {
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
		}
		List<ExactCategoricalSolver.Factor> factors() {
			return contributions.stream().map(PhysicalContribution::factor).toList();
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

	static PhysicalCostSurface physicalCostSurface(PlacementAnalysis analysis,
		ExactPhysicalModel model) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(model, "model");
		analysis.assertProgramStructureUnchanged();
		OccurrenceExecutionFrequencyFacts frequencies = analysis.executionFrequencyFacts();
		if(!frequencies.exactFunctionContextsProven())
			throw new IllegalArgumentException("EXACT_GUARDED_FUNCTION_ROOTS_REQUIRED");
		int workers = workerCount(analysis.graph());
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		List<PhysicalTransferKey> transferKeys = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains =
			new IdentityHashMap<>();
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			domains.put(domain.node().key(), domain);
			addPhysicalUnaryFactor(analysis, domain, workers, frequencies, factors);
		}
		List<EffectiveLogicalFunctionInput> logicalInputs = effectiveLogicalFunctionInputs(analysis);
		addPhysicalCompiledTransferFactors(analysis, model.domains(), domains, workers, frequencies,
			logicalInputs, factors, transferKeys);
		addPhysicalNativeLocalInputTransferFactors(analysis, domains, workers,
			frequencies, factors);
		addPhysicalLogicalFunctionFactors(analysis, domains, workers, frequencies, factors, transferKeys);
		List<PhysicalContribution> contributions = new ArrayList<>(factors.size());
		StringBuilder normalized = new StringBuilder(analysis.analysisFingerprint());
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
		for(int index = 0; index < factors.size(); index++) {
			String id = String.format("%08d", index) + '|'
				+ factors.get(index).scope().stream().map(ExactCategoricalSolver.Variable::key).toList();
			contributions.add(new PhysicalContribution(id, factors.get(index)));
			normalized.append('|').append(id).append("|values=");
			appendPhysicalFactorValues(normalized, factors.get(index), 0,
				new int[factors.get(index).scope().size()]);
		}
		for(PhysicalTransferKey key : transferKeys)
			normalized.append("|transfer:").append(key);
		return new PhysicalCostSurface(analysis, analysis.analysisFingerprint(), model.variables(), contributions,
			transferKeys, sha256(normalized.toString()));
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

	private static void appendPhysicalFactorValues(StringBuilder normalized,
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

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for(byte octet : digest)
				hex.append(String.format("%02x", octet));
			return hex.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static void addPhysicalUnaryFactor(PlacementAnalysis analysis,
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
					nativeCpUpload[value] = physicalResultUploadCost(analysis, domain.node().key(),
						hop, state.fType(), workers, weight);
				continue;
			}
			CandidateEmissionFact emission = alternative.captured()
				? alternative.candidateEmission() : alternative.executionEmission();
			FType executionFType = emission == null ? state.fType() : emission.executionFType();
			boolean federatedSource = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			List<FType> inputFTypes = federatedSource ? List.of() : alternative.orderedInputs().stream()
				.map(input -> input.present() ? input.fType() : null).toList();
			FedCostProjection projection = fedCostProjection(analysis, domain.node().key(), hop,
				inputFTypes, executionFType, workers, weight);
			boolean derivedFout = state.output() == FederatedOutput.FOUT && emission != null
				&& emission.emissionState().derivedFedFout();
			execution[value] = derivedFout
				? requireCost(projection.fedUnaryCost() + projection.resultDownloadCost(),
					"EXACT_PHYSICAL_FED_DOWNLOAD_COST_UNPROVEN")
				: projection.fedUnaryCost();
			if(derivedFout)
				outputMaterialization[value] = physicalResultUploadCost(analysis, domain.node().key(),
					hop, state.fType(), workers, weight);
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

	private static double physicalResultUploadCost(PlacementAnalysis analysis, CompiledHopKey key,
		Hop hop, FType fType, int workers, double weight) {
		return requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(
			effectiveUploadBytes(analysis, key, hop), fType, workers)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(fType, workers)),
			"EXACT_RESULT_UPLOAD_COST_UNPROVEN");
	}

	private static void addPhysicalCompiledTransferFactors(PlacementAnalysis analysis,
		List<ExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs,
		List<ExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys) {
		record Demand(CompiledInputEdgeFact edge,
			ExactPhysicalModel.DecisionDomain consumer, double cost,
			double weight, boolean forwarded) { }
		record Key(Direction direction, FType type, BoundaryMode boundary,
			String physicalEmissionIdentity) { }
		record IndexedDemand(int consumerIndex, boolean[] activeConsumerAlternatives,
			double[] sourcePrices) { }
		for(ExactPhysicalModel.DecisionDomain producer : orderedDomains) {
			if(producer.node().kind() == NodeKind.FUNCTION_INPUT
				|| producer.node().kind() == NodeKind.FUNCTION_OUTPUT)
				continue;
			Hop producerHop = analysis.hop(producer.node().key()).orElseThrow();
			if(producerHop.getDataType() == null || !producerHop.getDataType().isMatrix())
				continue;
			double bytes = estimatedBytes(analysis, producer.node().key(), producerHop);
			Map<Key,List<Demand>> grouped = new LinkedHashMap<>();
			for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.producer() != producer.node().key()
					|| analysis.graph().node(edge.consumer()).orElseThrow().kind() == NodeKind.FUNCTION_CALL)
					continue;
				if(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(
					analysis, edge.producer(), edge.consumer(), edge.inputPosition()))
					continue;
				ExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
				if(consumer == null)
					continue;
				EffectiveLogicalFunctionInput forwarded = forwardedFunctionInputForTarget(
					effectiveFunctionInputs, producer.node().key());
				double weight = forwarded == null
					? frequencies.exactForwardingWeight(edge.consumer(), edge.producer())
					: frequencies.logicalFunctionCallWeight(forwarded.authority());
				for(FType type : producer.alternatives().stream().map(a -> a.state().fType())
					.filter(Objects::nonNull).distinct().toList()) {
					double download = requireCost(weight * (forwarded == null
						? FederatedCostModel.computeDownloadNetworkCost(bytes, type, workers)
						: FederatedCostModel.computeDownloadNetworkCost(bytes)),
						"EXACT_PHYSICAL_DOWNLOAD_COST_UNPROVEN");
					grouped.computeIfAbsent(new Key(Direction.DOWNLOAD, type,
						BoundaryMode.ANCHOR_TRANSFER, "-"), ignored -> new ArrayList<>())
						.add(new Demand(edge, consumer, download, weight, forwarded != null));
				}
				for(RelocationAction action : consumer.alternatives().stream()
					.flatMap(a -> a.inputAuthorities().stream())
					.filter(a -> a.inputPosition() == edge.inputPosition()
						&& a.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
						&& a.relocationAction().key().sourceValueVersion().equals(producer.node().valueVersion()))
					.map(ExactPhysicalModel.InputAuthority::relocationAction)
					.distinct().sorted().toList()) {
					FType type = action.key().materializationFType();
					double upload = requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(bytes,
						type, workers) + FederatedCostModel.computeLocalToFedForwardingPenalty(type, workers)),
						"EXACT_PHYSICAL_UPLOAD_COST_UNPROVEN");
					Key key = new Key(Direction.UPLOAD, type, uploadBoundaryMode(analysis, edge),
						RelocationSelections.physicalEmissionIdentity(action.key()));
					List<Demand> demands = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
					if(demands.stream().noneMatch(demand -> demand.edge().producer() == edge.producer()
						&& demand.edge().consumer() == edge.consumer()
						&& demand.edge().inputPosition() == edge.inputPosition()))
						demands.add(new Demand(edge, consumer, upload, weight, forwarded != null));
				}
			}
			for(Map.Entry<Key,List<Demand>> entry : grouped.entrySet()) {
				List<Demand> demands = entry.getValue().stream().sorted(Comparator
					.comparing((Demand d) -> d.edge().consumer().normalizedSignature())
					.thenComparingInt(d -> d.edge().inputPosition())).toList();
				LinkedHashSet<ExactPhysicalModel.DecisionDomain> scopeSet = new LinkedHashSet<>();
				scopeSet.add(producer);
				demands.forEach(d -> scopeSet.add(d.consumer()));
				List<ExactPhysicalModel.DecisionDomain> scope = List.copyOf(scopeSet);
				Key key = entry.getKey();
				IdentityHashMap<ExactPhysicalModel.DecisionDomain,Integer> scopeIndexes =
					new IdentityHashMap<>();
				for(int index = 0; index < scope.size(); index++)
					scopeIndexes.put(scope.get(index), index);
				boolean[] activeSourceAlternatives = new boolean[producer.alternatives().size()];
				for(int value = 0; value < activeSourceAlternatives.length; value++) {
					PlacementState state = producer.alternatives().get(value).state();
					activeSourceAlternatives[value] = key.direction() == Direction.UPLOAD
						|| state.output() == FederatedOutput.FOUT && state.fType() == key.type();
				}
				List<IndexedDemand> indexedDemands = new ArrayList<>(demands.size());
				for(Demand demand : demands) {
					boolean[] activeConsumers = new boolean[demand.consumer().alternatives().size()];
					for(int value = 0; value < activeConsumers.length; value++) {
						ExactPhysicalModel.Alternative consumer =
							demand.consumer().alternatives().get(value);
						if(key.direction() == Direction.DOWNLOAD)
							activeConsumers[value] = consumer.state().execType() == ExecType.CP;
						else
							activeConsumers[value] = consumer.inputAuthorities().stream().anyMatch(authority ->
								authority.inputPosition() == demand.edge().inputPosition()
									&& authority.kind()
										== ExactPhysicalModel.InputAuthorityKind.RELOCATION
									&& authority.expectedFType() == key.type()
									&& authority.relocationAction().key().sourceValueVersion()
										.equals(producer.node().valueVersion())
									&& RelocationSelections.physicalEmissionIdentity(
										authority.relocationAction().key())
										.equals(key.physicalEmissionIdentity()));
					}
					double[] sourcePrices = new double[producer.alternatives().size()];
					for(int value = 0; value < sourcePrices.length; value++) {
						double price = demand.cost();
						PlacementState source = producer.alternatives().get(value).state();
						if(key.direction() == Direction.UPLOAD
							&& source.output() == FederatedOutput.FOUT) {
							FType sourceType = Objects.requireNonNull(source.fType(),
								"FOUT relocation source has no exact FType");
							price = requireCost(price + demand.weight()
								* (demand.forwarded()
									? FederatedCostModel.computeDownloadNetworkCost(bytes)
									: FederatedCostModel.computeDownloadNetworkCost(
										bytes, sourceType, workers)),
								"EXACT_PHYSICAL_REFED_DOWNLOAD_COST_UNPROVEN");
						}
						sourcePrices[value] = price;
					}
					indexedDemands.add(new IndexedDemand(scopeIndexes.get(demand.consumer()),
						activeConsumers, sourcePrices));
				}
				factors.add(ExactCategoricalSolver.Factor.lazy(
					scope.stream().map(ExactPhysicalModel.DecisionDomain::variable).toList(), values -> {
						int sourceValue = values[0];
						if(!activeSourceAlternatives[sourceValue])
							return 0.0;
						double activePrice = 0.0;
						for(IndexedDemand demand : indexedDemands) {
							if(!demand.activeConsumerAlternatives()[values[demand.consumerIndex()]])
								continue;
							activePrice = Math.max(activePrice, demand.sourcePrices()[sourceValue]);
						}
						return activePrice;
					}));
				transferKeys.add(new PhysicalTransferKey(producer.node().valueVersion(), demands.stream()
					.map(d -> new PhysicalTransferEndpoint(d.edge().producer(), d.edge().consumer(),
						d.edge().inputPosition())).toList(), key.direction(), key.type(), key.boundary(),
					key.physicalEmissionIdentity()));
			}
		}
	}

	private static void addPhysicalNativeLocalInputTransferFactors(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		List<ExactCategoricalSolver.Factor> factors) {
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
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
			double bytes = estimatedBytes(analysis, edge.producer(), producerHop);
			double fusedInputPreparationBytes =
				PlacementCostSemantics.latentWdivmmFusedInputPreparationBytes(
					analysis, edge.producer(), edge.consumer(), edge.inputPosition());
			double weight = frequencies.exactForwardingWeight(edge.consumer(), edge.producer());
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
					if(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(
						analysis, edge.producer(), edge.consumer(), edge.inputPosition()))
						return 0.0;
					CandidateEmissionFact emission = target.captured()
						? target.candidateEmission() : target.executionEmission();
					FType executionFType = emission == null ? target.state().fType()
						: emission.executionFType();
					PlacementCostSemantics.NativeLocalInputTransferEstimate boundedElementwise =
						PlacementCostSemantics.boundedElementwiseNativeLocalInputTransfer(
							analysis, edge.producer(), edge.consumer(), edge.inputPosition(),
							executionFType, workers);
					List<FType> inputFTypes = target.orderedInputs().stream()
						.map(input -> input.present() ? input.fType() : null).toList();
					FederatedCostModel.MixedFedLocalCost mixed =
						FederatedCostModel.computeMixedFedLocalCost(consumerHop,
							new ArrayList<>(consumerHop.getInput()), inputFTypes, executionFType,
							unitLocalCost(analysis, edge.consumer(), consumerHop),
							effectiveOutputBytes(analysis, edge.consumer(), consumerHop), workers);
					double cost;
					if(mixed.hasInputPreparation())
						cost = 0.0;
					else if(fusedInputPreparationBytes >= 0.0)
						cost = FederatedCostModel.computeInBandUploadPayloadCost(
							fusedInputPreparationBytes, FType.BROADCAST, workers);
					else if(boundedElementwise != null)
						cost = boundedElementwise.uploadPayloadCostUpperBound();
					else
						cost = nativeLocalInputUploadCost(consumerHop, producerHop, bytes,
							executionFType, workers);
					if(source.state().output() == FederatedOutput.FOUT) {
						FType sourceType = Objects.requireNonNull(source.state().fType(),
							"FOUT native-local source has no exact FType");
						double sourceBytes = boundedElementwise == null ? bytes
							: boundedElementwise.logicalBytesUpperBound();
						cost += FederatedCostModel.computeDownloadNetworkCost(
							sourceBytes, sourceType, workers);
					}
					return requireCost(weight * cost,
						"EXACT_PHYSICAL_NATIVE_LOCAL_INPUT_COST_UNPROVEN");
				}));
		}
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
		IdentityHashMap<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		int workers, OccurrenceExecutionFrequencyFacts frequencies,
		List<ExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys) {
		for(EffectiveLogicalFunctionInput input : effectiveLogicalFunctionInputs(analysis)) {
			ExactPhysicalModel.DecisionDomain source = domains.get(input.authority().sourceArgument());
			ExactPhysicalModel.DecisionDomain formal = domains.get(input.targetRead());
			if(source == null || formal == null)
				continue;
			double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
				analysis.hop(formal.node().key()).orElseThrow(),
				analysis.hop(source.node().key()).orElseThrow());
			double callWeight = frequencies.logicalFunctionCallWeight(input.authority());
			List<FType> sourceTypes = source.alternatives().stream().map(a -> a.state().fType())
				.filter(Objects::nonNull).distinct().toList();
			for(FType type : sourceTypes) {
				double cost = requireCost(callWeight * FederatedCostModel.computeDownloadNetworkCost(bytes),
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
			for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.producer() != formal.node().key())
					continue;
				ExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
				if(consumer == null)
					continue;
				double downstream = requireCost(frequencies.exactForwardingWeight(edge.consumer(), edge.producer())
					* FederatedCostModel.computeDownloadNetworkCost(bytes),
					"EXACT_PHYSICAL_LOGICAL_FUNCTION_CONSUMER_DOWNLOAD_COST_UNPROVEN");
				for(FType type : sourceTypes) {
					factors.add(ExactCategoricalSolver.Factor.lazy(
						List.of(source.variable(), consumer.variable()), values -> {
							PlacementState sourceState = source.alternatives().get(values[0]).state();
							return sourceState.output() == FederatedOutput.FOUT && sourceState.fType() == type
								&& consumer.alternatives().get(values[1]).state().execType() == ExecType.CP
								? downstream : 0.0;
						}));
					transferKeys.add(new PhysicalTransferKey(source.node().valueVersion(),
						List.of(new PhysicalTransferEndpoint(source.node().key(), consumer.node().key(),
							edge.inputPosition())), Direction.DOWNLOAD, type,
						BoundaryMode.ANCHOR_TRANSFER));
				}
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
		CompiledHopKey key, Hop hop, List<FType> inputFTypes, FType executionFType,
		int workers, double executionWeight) {
		double base = cpUnaryCost(analysis, key, hop, executionWeight);
		return fedCostProjection(analysis, key, hop, inputFTypes, executionFType, workers,
			executionWeight, base, effectiveOutputBytes(analysis, key, hop),
			effectiveUploadBytes(analysis, key, hop));
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
			: FederatedCostModel.computeMixedFedLocalCost(hop,
				new ArrayList<>(hop.getInput()), inputFTypes, executionFType,
				executionWeight > 0.0 ? base / executionWeight : 0.0, outputBytes, workers);
		double fedInputPreparation = executionWeight * mixed.getInputPreparationCost();
		double singleWorkerPenalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(
			hop, executionWeight, workers);
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
		CompiledHopKey key, Hop hop) {
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		return Double.isFinite(bytes) && bytes > 0.0 ? bytes : estimatedBytes(analysis, key, hop);
	}

	private static double effectiveUploadBytes(PlacementAnalysis analysis,
		CompiledHopKey key, Hop hop) {
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		return Double.isFinite(bytes) && bytes > 0.0 ? bytes : estimatedBytes(analysis, key, hop);
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

	private static EffectiveLogicalFunctionInput forwardedFunctionInputForTarget(
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs, CompiledHopKey target) {
		List<EffectiveLogicalFunctionInput> matches = effectiveFunctionInputs.stream()
			.filter(input -> input.forwardedAuthority() != null && input.targetRead() == target)
			.toList();
		if(matches.size() > 1)
			throw new IllegalArgumentException("EXACT_FUNCTION_INPUT_FORWARD_TARGET_AMBIGUOUS|target="
				+ target.normalizedSignature());
		return matches.isEmpty() ? null : matches.get(0);
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
			total += requirePositiveWeight(PlacementCostSemantics.forwardingWeight(
				consumerProfile.networkWeight, consumerProfile.loopContext, producerProfile.loopContext),
				"EXACT_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requirePositiveWeight(total, "EXACT_FORWARDING_WEIGHT_UNPROVEN");
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

	private static double requirePositiveWeight(double value, String reason) {
		if(!Double.isFinite(value) || value <= 0.0)
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private static int workerCount(NeutralPlacementGraph graph) {
		Set<String> workers = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : graph.nodes())
			for(var anchor : node.anchors())
				for(var partition : anchor.partitions())
					workers.add(partition.workerId());
		return workers.size();
	}

	private static double estimatedBytes(PlacementAnalysis analysis, CompiledHopKey key, Hop hop) {
		if(hop.getDataType() != null && hop.getDataType().isScalar())
			return 8.0;
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
			this.networkWeight = requirePositiveWeight(networkWeight,
				"EXACT_OCCURRENCE_WEIGHT_UNPROVEN");
			this.loopContext = List.copyOf(loopContext);
			this.contextOrdinal = contextOrdinal;
		}
	}
}

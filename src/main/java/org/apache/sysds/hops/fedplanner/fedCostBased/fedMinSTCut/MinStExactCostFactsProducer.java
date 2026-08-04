/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.math.BigDecimal;
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
import java.util.TreeSet;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.BoundaryMode;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EdgeContribution;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipInputAuthorityFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.UploadPriceTarget;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ValidationException;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ValidationReason;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolution;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerEdgeEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerNodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.InvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.TransientForwardEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;

/** Deterministic projection from neutral placement semantics to exact MinST cut facts. */
public final class MinStExactCostFactsProducer {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;
	/** Internal marker replaced by an instance-specific, certified hard capacity in freeze(). */
	private static final double HARD_LEGALITY = Double.NEGATIVE_INFINITY;
	static final long MAX_EXACT_ROW_VARIANT_COMBINATIONS = 4096L;
	private static final Object MAIN_OCCURRENCE_CONTEXT = new Object();

	/** Immutable exact-row choice used by a separately costed MinST projection variant. */
	static record RepresentativePreference(CompiledHopKey decisionKey, ExecType execType,
		FederatedOutput output, List<CandidateInputState> orderedInputs, PlacementState state,
		CandidateRuleFact candidateRuleFact, CandidateEmissionFact candidateEmissionFact) {
		RepresentativePreference {
			Objects.requireNonNull(decisionKey, "decisionKey");
			Objects.requireNonNull(execType, "execType");
			Objects.requireNonNull(output, "output");
			orderedInputs = List.copyOf(orderedInputs);
			Objects.requireNonNull(state, "state");
			if(state.execType() != execType || state.output() != output)
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_STATE_MISMATCH");
			Objects.requireNonNull(candidateRuleFact, "candidateRuleFact");
			Objects.requireNonNull(candidateEmissionFact, "candidateEmissionFact");
			if(candidateRuleFact.key().parentOccurrence() != decisionKey
				|| !candidateRuleFact.key().orderedInputs().equals(orderedInputs)
				|| candidateEmissionFact.emissionState().placementState() != state
				|| candidateRuleFact.allowedEmissionFacts().stream()
					.noneMatch(emission -> emission == candidateEmissionFact))
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_EMISSION_MISMATCH");
		}
	}

	private MinStExactCostFactsProducer() {
		// utility class
	}

	/** Auditable identity of one endpoint in an exact physical price-once factor. */
	static record PhysicalTransferEndpoint(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition) {
		PhysicalTransferEndpoint {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("MINST_PHYSICAL_TRANSFER_POSITION_INVALID");
		}
	}

	/**
	 * Exact reuse identity.  The source value/version is deliberately distinct from the
	 * occurrence endpoint: aliases of one value may share a transfer only when all remaining
	 * physical dimensions (direction, FType, boundary and endpoint set) are identical.
	 */
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
				throw new IllegalArgumentException("MINST_PHYSICAL_TRANSFER_KEY_INVALID");
		}
	}

	static record PhysicalContribution(String id, MinStExactCategoricalSolver.Factor factor) {
		PhysicalContribution {
			if(id == null || id.isBlank() || factor == null)
				throw new IllegalArgumentException("MINST_PHYSICAL_CONTRIBUTION_INVALID");
		}
	}

	static record PhysicalCostSurface(PlacementAnalysis owner, String ownerFingerprint,
		List<MinStExactCategoricalSolver.Variable> variables,
		List<PhysicalContribution> contributions, List<PhysicalTransferKey> transferKeys,
		String contributionFingerprint) {
		PhysicalCostSurface {
			Objects.requireNonNull(owner, "owner");
			if(ownerFingerprint == null || ownerFingerprint.isBlank()
				|| !owner.analysisFingerprint().equals(ownerFingerprint))
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_OWNER_INVALID");
			variables = List.copyOf(variables);
			contributions = List.copyOf(contributions);
			transferKeys = List.copyOf(transferKeys);
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_FINGERPRINT_INVALID");
		}
		List<MinStExactCategoricalSolver.Factor> factors() {
			return contributions.stream().map(PhysicalContribution::factor).toList();
		}
		long evaluateCanonical(List<Integer> assignment) {
			owner.assertProgramStructureUnchanged();
			if(!owner.analysisFingerprint().equals(ownerFingerprint))
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_OWNER_CHANGED");
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_ASSIGNMENT_SIZE_MISMATCH");
			IdentityHashMap<MinStExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
			for(int index = 0; index < variables.size(); index++)
				positions.put(variables.get(index), index);
			MinStCompensatedCostSum total = new MinStCompensatedCostSum();
			for(PhysicalContribution contribution : contributions) {
				int[] local = new int[contribution.factor().scope().size()];
				for(int index = 0; index < local.length; index++) {
					Integer global = positions.get(contribution.factor().scope().get(index));
					if(global == null)
						throw new IllegalArgumentException("MINST_PHYSICAL_COST_FOREIGN_VARIABLE");
					local[index] = assignment.get(global);
				}
				total.addBits(bits(contribution.factor().cost(local)),
					"MINST_PHYSICAL_CONTRIBUTION_COST_UNPROVEN",
					"MINST_PHYSICAL_OBJECTIVE_UNPROVEN");
			}
			return total.totalBits("MINST_PHYSICAL_OBJECTIVE_UNPROVEN");
		}
	}

	/**
	 * Supplies the categorical model with the same private cost formulae used by the legacy
	 * cut projection.  This method does not enumerate combinations and does not close any
	 * alternative; legality remains exclusively in {@link MinStExactPhysicalModel}.
	 */
	static PhysicalCostSurface physicalCostSurface(PlacementAnalysis analysis,
		MinStExactPhysicalModel model) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(model, "model");
		analysis.assertProgramStructureUnchanged();
		Map<String,List<OccurrenceProfile>> profiles = occurrenceProfiles(analysis);
		int workers = workerCount(analysis.graph());
		List<MinStExactCategoricalSolver.Factor> factors = new ArrayList<>();
		List<PhysicalTransferKey> transferKeys = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,MinStExactPhysicalModel.DecisionDomain> domains =
			new IdentityHashMap<>();
		for(MinStExactPhysicalModel.DecisionDomain domain : model.domains()) {
			domains.put(domain.node().key(), domain);
			addPhysicalUnaryFactor(analysis, domain, workers, profiles, factors);
		}
		List<EffectiveLogicalFunctionInput> logicalInputs = effectiveLogicalFunctionInputs(analysis);
		addPhysicalCompiledTransferFactors(analysis, model.domains(), domains, workers, profiles,
			logicalInputs, factors, transferKeys);
		addPhysicalNativeLocalInputTransferFactors(analysis, domains, workers,
			profiles, factors);
		addPhysicalLogicalFunctionFactors(analysis, domains, workers, profiles, factors, transferKeys);
		List<PhysicalContribution> contributions = new ArrayList<>(factors.size());
		StringBuilder normalized = new StringBuilder(analysis.analysisFingerprint());
		// The optimization receipt must bind the complete authority-bearing physical
		// universe, not merely factor scopes. A changed candidate capability/emission or
		// a changed numeric factor table must therefore produce a different certificate
		// even when a reconstructed analysis reuses the old structural fingerprint.
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
			normalized.append("|candidate:").append(physicalCandidateFactSignature(fact));
		for(MinStExactPhysicalModel.DecisionDomain domain : model.domains()) {
			normalized.append("|domain:").append(domain.node().key().normalizedSignature());
			for(MinStExactPhysicalModel.Alternative alternative : domain.alternatives())
				normalized.append("|alternative:").append(alternative.signature());
		}
		for(int index = 0; index < factors.size(); index++) {
			String id = String.format("%08d", index) + '|'
				+ factors.get(index).scope().stream().map(MinStExactCategoricalSolver.Variable::key).toList();
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

	/** Deterministic audit fingerprint for the exact production physical universe and objective. */
	public static String physicalAuthorityFingerprint(PlacementAnalysis analysis) {
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(
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
		MinStExactCategoricalSolver.Factor factor, int position, int[] values) {
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
		MinStExactPhysicalModel.DecisionDomain domain, int workers,
		Map<String,List<OccurrenceProfile>> profiles,
		List<MinStExactCategoricalSolver.Factor> factors) {
		if(domain.node().kind() == NodeKind.FUNCTION_INPUT
			|| domain.node().kind() == NodeKind.FUNCTION_OUTPUT) {
			double[] zero = new double[domain.alternatives().size()];
			factors.add(MinStExactCategoricalSolver.Factor.dense(List.of(domain.variable()), zero));
			return;
		}
		Hop hop = analysis.hop(domain.node().key()).orElseThrow();
		double weight = executionWeight(profiles, domain.node().key());
		double[] execution = new double[domain.alternatives().size()];
		double[] outputMaterialization = new double[execution.length];
		double[] nativeFedDownload = new double[execution.length];
		double[] nativeCpUpload = new double[execution.length];
		for(int value = 0; value < execution.length; value++) {
			MinStExactPhysicalModel.Alternative alternative = domain.alternatives().get(value);
			PlacementState state = alternative.state();
			if(state.execType() == ExecType.CP) {
				execution[value] = cpUnaryCost(hop, weight);
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
					"MINST_PHYSICAL_FED_DOWNLOAD_COST_UNPROVEN")
				: projection.fedUnaryCost();
			if(derivedFout)
				outputMaterialization[value] = physicalResultUploadCost(analysis, domain.node().key(),
					hop, state.fType(), workers, weight);
			else if(state.output() == FederatedOutput.LOUT)
				nativeFedDownload[value] = projection.resultDownloadCost();
		}
		// Preserve the legacy cut's edge-level arithmetic grouping, including the one case
		// where FED unary and derived-FOUT execution download share compute->sink.
		factors.add(MinStExactCategoricalSolver.Factor.dense(List.of(domain.variable()), execution));
		factors.add(MinStExactCategoricalSolver.Factor.dense(List.of(domain.variable()), outputMaterialization));
		factors.add(MinStExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeFedDownload));
		factors.add(MinStExactCategoricalSolver.Factor.dense(List.of(domain.variable()), nativeCpUpload));
	}

	private static double physicalResultUploadCost(PlacementAnalysis analysis, CompiledHopKey key,
		Hop hop, FType fType, int workers, double weight) {
		return requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(
			effectiveUploadBytes(analysis, key, hop), fType, workers)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(fType, workers)),
			"MINST_RESULT_UPLOAD_COST_UNPROVEN");
	}

	private static void addPhysicalCompiledTransferFactors(PlacementAnalysis analysis,
		List<MinStExactPhysicalModel.DecisionDomain> orderedDomains,
		IdentityHashMap<CompiledHopKey,MinStExactPhysicalModel.DecisionDomain> domains,
		int workers, Map<String,List<OccurrenceProfile>> profiles,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs,
		List<MinStExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys) {
		record Demand(CompiledInputEdgeFact edge,
			MinStExactPhysicalModel.DecisionDomain consumer, double cost,
			double weight, boolean forwarded) { }
		record Key(Direction direction, FType type, BoundaryMode boundary,
			String physicalEmissionIdentity) { }
		for(MinStExactPhysicalModel.DecisionDomain producer : orderedDomains) {
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
				MinStExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
				if(consumer == null)
					continue;
				EffectiveLogicalFunctionInput forwarded = forwardedFunctionInputForTarget(
					effectiveFunctionInputs, producer.node().key());
				double weight = forwarded == null
					? forwardingWeight(profiles, edge.consumer(), edge.producer())
					: logicalFunctionCallWeight(profiles, forwarded.authority());
				for(FType type : producer.alternatives().stream().map(a -> a.state().fType())
					.filter(Objects::nonNull).distinct().toList()) {
					double download = requireCost(weight * (forwarded == null
						? FederatedCostModel.computeDownloadNetworkCost(bytes, type, workers)
						: FederatedCostModel.computeDownloadNetworkCost(bytes)),
						"MINST_PHYSICAL_DOWNLOAD_COST_UNPROVEN");
					grouped.computeIfAbsent(new Key(Direction.DOWNLOAD, type,
						BoundaryMode.ANCHOR_TRANSFER, "-"), ignored -> new ArrayList<>())
						.add(new Demand(edge, consumer, download, weight, forwarded != null));
				}
				for(RelocationAction action : consumer.alternatives().stream()
					.flatMap(a -> a.inputAuthorities().stream())
					.filter(a -> a.inputPosition() == edge.inputPosition()
						&& a.kind() == MinStExactPhysicalModel.InputAuthorityKind.RELOCATION
						&& a.relocationAction().key().sourceValueVersion().equals(producer.node().valueVersion()))
					.map(MinStExactPhysicalModel.InputAuthority::relocationAction)
					.distinct().sorted().toList()) {
					FType type = action.key().materializationFType();
					double upload = requireCost(weight * (FederatedCostModel.computeUploadNetworkCost(bytes,
						type, workers) + FederatedCostModel.computeLocalToFedForwardingPenalty(type, workers)),
						"MINST_PHYSICAL_UPLOAD_COST_UNPROVEN");
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
				LinkedHashSet<MinStExactPhysicalModel.DecisionDomain> scopeSet = new LinkedHashSet<>();
				scopeSet.add(producer);
				demands.forEach(d -> scopeSet.add(d.consumer()));
				List<MinStExactPhysicalModel.DecisionDomain> scope = List.copyOf(scopeSet);
				Key key = entry.getKey();
				factors.add(MinStExactCategoricalSolver.Factor.lazy(
					scope.stream().map(MinStExactPhysicalModel.DecisionDomain::variable).toList(), values -> {
						MinStExactPhysicalModel.Alternative source = scope.get(0).alternatives().get(values[0]);
						double activePrice = 0.0;
						for(Demand demand : demands) {
							int consumerIndex = scope.indexOf(demand.consumer());
							MinStExactPhysicalModel.Alternative consumer = demand.consumer().alternatives()
								.get(values[consumerIndex]);
							boolean active = key.direction() == Direction.DOWNLOAD
								? source.state().output() == FederatedOutput.FOUT
									&& source.state().fType() == key.type()
									&& consumer.state().execType() == ExecType.CP
								: consumer.inputAuthorities().stream().anyMatch(authority ->
									authority.inputPosition() == demand.edge().inputPosition()
										&& authority.kind() == MinStExactPhysicalModel.InputAuthorityKind.RELOCATION
										&& authority.expectedFType() == key.type()
										&& authority.relocationAction().key().sourceValueVersion()
											.equals(producer.node().valueVersion())
										&& RelocationSelections.physicalEmissionIdentity(
											authority.relocationAction().key())
											.equals(key.physicalEmissionIdentity()));
							if(!active)
								continue;
							double demandPrice = demand.cost();
							if(key.direction() == Direction.UPLOAD
								&& source.state().output() == FederatedOutput.FOUT) {
								FType sourceType = Objects.requireNonNull(source.state().fType(),
									"FOUT relocation source has no exact FType");
								demandPrice = requireCost(demandPrice + demand.weight()
									* (demand.forwarded()
										? FederatedCostModel.computeDownloadNetworkCost(bytes)
										: FederatedCostModel.computeDownloadNetworkCost(bytes, sourceType, workers)),
									"MINST_PHYSICAL_REFED_DOWNLOAD_COST_UNPROVEN");
							}
							activePrice = Math.max(activePrice, demandPrice);
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

	/**
	 * Prices runtime-owned coordinator-local matrix inputs of FED instructions.
	 *
	 * <p>An exact {@code ABSENT_LOCAL} candidate is deliberately not a planner relocation: lowering
	 * retains a local operand and the selected FED instruction broadcasts (or sliced-broadcasts)
	 * that operand when it executes.  Consequently it must not create a relocation receipt or an
	 * auxiliary upload group.  It does, however, perform a physical C2W transfer on every dynamic
	 * execution.  The categorical objective therefore owns one edge-local factor whose activation
	 * is the exact {@link MinStExactPhysicalModel.InputAuthorityKind#NATIVE_LOCAL} authority.</p>
	 *
	 * <p>Special mixed FED/local runtime stages (for example aggregate-binary and WDivMM) already
	 * publish their complete input-preparation cost through {@link FederatedCostModel.MixedFedLocalCost};
	 * this factor only supplies the generic transfer when that canonical stage has no preparation
	 * charge.  A FOUT producer additionally pays the required FED-to-local materialization before
	 * the runtime-owned local input transfer.</p>
	 */
	private static void addPhysicalNativeLocalInputTransferFactors(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,MinStExactPhysicalModel.DecisionDomain> domains,
		int workers, Map<String,List<OccurrenceProfile>> profiles,
		List<MinStExactCategoricalSolver.Factor> factors) {
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			MinStExactPhysicalModel.DecisionDomain producer = domains.get(edge.producer());
			MinStExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
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
								== MinStExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL));
			if(!hasNativeLocalFedAlternative)
				continue;
			double bytes = estimatedBytes(analysis, edge.producer(), producerHop);
			double weight = forwardingWeight(profiles, edge.consumer(), edge.producer());
			factors.add(MinStExactCategoricalSolver.Factor.lazy(
				List.of(producer.variable(), consumer.variable()), values -> {
					MinStExactPhysicalModel.Alternative source = producer.alternatives().get(values[0]);
					MinStExactPhysicalModel.Alternative target = consumer.alternatives().get(values[1]);
					if(target.state().execType() != ExecType.FED
						|| target.inputAuthorities().stream().noneMatch(authority ->
							authority.inputPosition() == edge.inputPosition()
								&& authority.kind()
									== MinStExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL))
						return 0.0;
					CandidateEmissionFact emission = target.captured()
						? target.candidateEmission() : target.executionEmission();
					FType executionFType = emission == null ? target.state().fType()
						: emission.executionFType();
					List<FType> inputFTypes = target.orderedInputs().stream()
						.map(input -> input.present() ? input.fType() : null).toList();
					FederatedCostModel.MixedFedLocalCost mixed =
						FederatedCostModel.computeMixedFedLocalCost(consumerHop,
							new ArrayList<>(consumerHop.getInput()), inputFTypes, executionFType,
							unitLocalCost(consumerHop),
							effectiveOutputBytes(analysis, edge.consumer(), consumerHop), workers);
					double cost = mixed.hasInputPreparation() ? 0.0
						: nativeLocalInputUploadCost(consumerHop, producerHop, bytes,
							executionFType, workers);
					if(source.state().output() == FederatedOutput.FOUT) {
						FType sourceType = Objects.requireNonNull(source.state().fType(),
							"FOUT native-local source has no exact FType");
						cost += FederatedCostModel.computeDownloadNetworkCost(bytes, sourceType, workers);
					}
					return requireCost(weight * cost,
						"MINST_PHYSICAL_NATIVE_LOCAL_INPUT_COST_UNPROVEN");
				}));
		}
	}

	private static double nativeLocalInputUploadCost(Hop consumer, Hop input, double bytes,
		FType executionFType, int workers) {
		if(executionFType == null)
			throw new IllegalArgumentException("MINST_NATIVE_LOCAL_EXECUTION_LAYOUT_UNPROVEN");
		FType transferType = nativeLocalInputTransferType(consumer, input, executionFType);
		return FederatedCostModel.computeUploadNetworkCost(bytes, transferType, workers)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(transferType, workers);
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
		IdentityHashMap<CompiledHopKey,MinStExactPhysicalModel.DecisionDomain> domains,
		int workers, Map<String,List<OccurrenceProfile>> profiles,
		List<MinStExactCategoricalSolver.Factor> factors,
		List<PhysicalTransferKey> transferKeys) {
		for(EffectiveLogicalFunctionInput input : effectiveLogicalFunctionInputs(analysis)) {
			MinStExactPhysicalModel.DecisionDomain source = domains.get(input.authority().sourceArgument());
			MinStExactPhysicalModel.DecisionDomain formal = domains.get(input.targetRead());
			if(source == null || formal == null)
				continue;
			double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
				analysis.hop(formal.node().key()).orElseThrow(),
				analysis.hop(source.node().key()).orElseThrow());
			double callWeight = logicalFunctionCallWeight(profiles, input.authority());
			List<FType> sourceTypes = source.alternatives().stream().map(a -> a.state().fType())
				.filter(Objects::nonNull).distinct().toList();
			for(FType type : sourceTypes) {
				double cost = requireCost(callWeight * FederatedCostModel.computeDownloadNetworkCost(bytes),
					"MINST_PHYSICAL_LOGICAL_FUNCTION_DOWNLOAD_COST_UNPROVEN");
				factors.add(MinStExactCategoricalSolver.Factor.lazy(
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
						&& authority.kind() == MinStExactPhysicalModel.InputAuthorityKind.RELOCATION
						&& authority.relocationAction().key().sourceValueVersion()
							.equals(source.node().valueVersion()))
					.map(MinStExactPhysicalModel.InputAuthority::relocationAction).sorted()
					.forEach(action -> uploadActions.putIfAbsent(
						RelocationSelections.physicalEmissionIdentity(action.key()), action));
				for(Map.Entry<String,RelocationAction> uploadEntry : uploadActions.entrySet()) {
					RelocationAction action = uploadEntry.getValue();
					FType type = action.key().materializationFType();
					double upload = requireCost(callWeight * (FederatedCostModel.computeUploadNetworkCost(bytes,
						type, workers) + FederatedCostModel.computeLocalToFedForwardingPenalty(type, workers)),
						"MINST_PHYSICAL_LOGICAL_FUNCTION_UPLOAD_COST_UNPROVEN");
					double refedDownload = requireCost(callWeight
						* FederatedCostModel.computeDownloadNetworkCost(bytes),
						"MINST_PHYSICAL_LOGICAL_FUNCTION_REFED_DOWNLOAD_COST_UNPROVEN");
					String emissionIdentity = uploadEntry.getKey();
					factors.add(MinStExactCategoricalSolver.Factor.lazy(
						List.of(source.variable(), formal.variable()), values -> {
							PlacementState sourceState = source.alternatives().get(values[0]).state();
							boolean active = formal.alternatives().get(values[1]).inputAuthorities().stream()
								.anyMatch(authority -> authority.inputPosition() == input.logicalPosition()
									&& authority.kind()
										== MinStExactPhysicalModel.InputAuthorityKind.RELOCATION
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
				MinStExactPhysicalModel.DecisionDomain consumer = domains.get(edge.consumer());
				if(consumer == null)
					continue;
				double downstream = requireCost(forwardingWeight(profiles, edge.consumer(), edge.producer())
					* FederatedCostModel.computeDownloadNetworkCost(bytes),
					"MINST_PHYSICAL_LOGICAL_FUNCTION_CONSUMER_DOWNLOAD_COST_UNPROVEN");
				for(FType type : sourceTypes) {
					factors.add(MinStExactCategoricalSolver.Factor.lazy(
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

	public static MinStExactCostFacts derive(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope) {
		return derive(analysis, orderedScope, List.of());
	}

	static PlannedSelection deriveAndSelectBest(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope) {
		MinStExactCostFacts baselineFacts = derive(analysis, orderedScope);
		List<List<RepresentativePreference>> groups = exactCandidateRowPreferenceGroups(
			analysis, orderedScope, baselineFacts);
		PlannedSelection best = MinStExactVariantSearch.select(groups,
			MAX_EXACT_ROW_VARIANT_COMBINATIONS,
			preferences -> evaluatePreferenceVariant(analysis, orderedScope, preferences),
			MinStExactCostFactsProducer::comparePlans);
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("MinST-ExactRowVariantGlobalSearch", "groups="
				+ groups.size() + ", combinations=" + exactCombinationCount(groups)
				+ ", selected=" + best.facts().representativePreferences().stream()
					.map(MinStExactCostFactsProducer::preferenceSignature).toList()
				+ ", objective=" + Double.longBitsToDouble(best.selection().objectiveBits()));
		return best;
	}

	static record PlannedSelection(MinStExactCostFacts facts, MinStExactSelection selection) {
		PlannedSelection {
			Objects.requireNonNull(facts, "facts");
			Objects.requireNonNull(selection, "selection");
		}
	}

	static MinStExactCostFacts derive(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope, List<RepresentativePreference> preferences) {
		Objects.requireNonNull(analysis, "analysis");
		validateScope(analysis, orderedScope);
		Derivation derivation = deriveUnchecked(analysis, orderedScope, preferences);
		return new MinStExactCostFacts(analysis, analysis.analysisFingerprint(), orderedScope,
			preferences,
			derivation.decisions, derivation.edges, derivation.groups,
			derivation.transferAuthorities, derivation.obligations, derivation.fingerprint);
	}

	private static int comparePlans(PlannedSelection candidate, PlannedSelection current) {
		double candidateObjective = Double.longBitsToDouble(candidate.selection().objectiveBits());
		double currentObjective = Double.longBitsToDouble(current.selection().objectiveBits());
		int objectiveOrder = Double.compare(candidateObjective, currentObjective);
		if(objectiveOrder != 0)
			return objectiveOrder;
		return Integer.compare(candidate.selection().obligationReceiptsInOrder().size(),
			current.selection().obligationReceiptsInOrder().size());
	}

	static java.util.Optional<PlannedSelection> evaluatePreferenceVariant(
		PlacementAnalysis analysis, List<CompiledHopKey> orderedScope,
		List<RepresentativePreference> preferences) {
		try {
			MinStExactCostFacts facts = derive(analysis, orderedScope, preferences);
			MinStExactSelection selection = MinStExactSelector.select(facts);
			if(preferences.stream().allMatch(preference -> preferenceSatisfied(facts, selection, preference))) {
				// A variant is a candidate plan, not merely a lower cut value.  Validate the
				// exact downstream projection before it can win the global comparison.
				MinStExactPlacementProjector.project(facts, selection);
				return java.util.Optional.of(new PlannedSelection(facts, selection));
			}
			return java.util.Optional.empty();
		}
		catch(IllegalArgumentException ex) {
			String message = ex.getMessage();
			if(message != null && (message.startsWith("MINST_EXACT_REPRESENTATIVE_PREFERENCE_")
				|| message.startsWith("MINST_EXACT_DECISION_AUTHORITY_EMPTY")
				|| message.startsWith("MINST_EXACT_MEMBERSHIP_AUTHORITY_UNPROVEN")
				|| message.startsWith("MINST_EXACT_OBLIGATION_AUTHORITY_MISSING")
				|| message.startsWith("MINST_CONSUMER_LAYOUT_UNPROVEN")))
				return java.util.Optional.empty();
			throw ex;
		}
	}

	static List<List<RepresentativePreference>> exactCandidateRowPreferenceGroups(
		PlacementAnalysis analysis, List<CompiledHopKey> orderedScope,
		MinStExactCostFacts baselineFacts) {
		// Scope: exact CandidateRuleFact/CandidateEmissionFact rows for each emitted
		// decision. Transfer/durable authorities are not variant rows here; derivation
		// separately proves their required-placement domain complete and globally unique.
		Map<String,List<RepresentativePreference>> groups = new java.util.TreeMap<>();
		for(DecisionFact decision : baselineFacts.decisionFactsInScopeOrder()) {
			List<MembershipRepresentative> baseline = baselineFacts
				.membershipRepresentativesInCanonicalOrder().stream()
				.filter(representative -> representative.decisionKey() == decision.key()).toList();
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
				if(fact.key().parentOccurrence() != decision.key()
					|| fact.status() != CandidateEvaluationStatus.AVAILABLE
					|| fact.capability() == null || !fact.profile().available())
					continue;
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					PlacementState state = emission.emissionState().placementState();
					if(decision.legalStatesInCanonicalOrder().stream().noneMatch(candidate -> candidate == state))
						continue;
					RepresentativePreference preference = new RepresentativePreference(
						decision.key(), state.execType(), state.output(),
						fact.key().orderedInputs(), state, fact, emission);
					boolean canonical = baseline.stream().anyMatch(representative ->
						physicalRepresentativeSignature(representative)
							.equals(physicalRepresentativeSignature(preference)));
					if(!canonical)
						groups.computeIfAbsent(decision.key().normalizedSignature(),
							ignored -> new ArrayList<>()).add(preference);
				}
			}
		}
		return groups.values().stream().map(group -> group.stream()
			.collect(java.util.stream.Collectors.toMap(
				MinStExactCostFactsProducer::physicalRepresentativeSignature,
				preference -> preference,
				(left, right) -> preferenceSignature(left).compareTo(preferenceSignature(right)) <= 0
					? left : right, java.util.TreeMap::new))
			.values().stream().toList()).toList();
	}

	static String physicalRepresentativeSignature(RepresentativePreference preference) {
		return preference.state().normalizedSignature() + "|emission="
			+ preference.candidateEmissionFact().normalizedSignature()
			+ "|inputs=" + preference.orderedInputs();
	}

	private static String physicalRepresentativeSignature(MembershipRepresentative representative) {
		String emission = representative.candidateEmissionFactOrNull() == null
			? representative.state().normalizedSignature()
			: representative.candidateEmissionFactOrNull().normalizedSignature();
		return representative.state().normalizedSignature() + "|emission=" + emission
			+ "|inputs=" + representative.orderedInputs();
	}

	private static long exactCombinationCount(List<List<RepresentativePreference>> groups) {
		long count = 1L;
		for(List<RepresentativePreference> group : groups)
			count = Math.multiplyExact(count, group.size() + 1L);
		return count;
	}

	private static boolean preferenceSatisfied(MinStExactCostFacts facts,
		MinStExactSelection selection, RepresentativePreference preference) {
		IdentityHashMap<CompiledHopKey,PlacementState> selected = new IdentityHashMap<>();
		for(int index = 0; index < facts.decisionFactsInScopeOrder().size(); index++)
			selected.put(facts.decisionFactsInScopeOrder().get(index).key(),
				selection.selectedStatesInScopeOrder().get(index));
		PlacementState consumer = selected.get(preference.decisionKey());
		if(consumer == null || consumer.execType() != preference.execType()
			|| consumer.output() != preference.output())
			return false;
		List<MembershipRepresentative> representatives = facts.membershipRepresentativesInCanonicalOrder().stream()
			.filter(representative -> representative.decisionKey() == preference.decisionKey()
				&& representative.execType() == preference.execType()
				&& representative.output() == preference.output()).toList();
		if(representatives.size() != 1 || representatives.get(0).state() != preference.state()
			|| !representatives.get(0).orderedInputs().equals(preference.orderedInputs())
			|| representatives.get(0).candidateRuleFactOrNull() != preference.candidateRuleFact()
			|| representatives.get(0).candidateEmissionFactOrNull() != preference.candidateEmissionFact())
			return false;
		// Producer placement is not part of the row identity. PRESENT may be supplied by
		// an already-FOUT producer or by the exact selected relocation receipt; ABSENT_LOCAL
		// is constrained to a legal local source by the variant edges below. The projector
		// validates the resulting exact receipts before a variant can win.
		return true;
	}

	static String preferenceSignature(RepresentativePreference preference) {
		return preference.decisionKey().normalizedSignature() + '|'
			+ membership(preference.execType(), preference.output()) + '|'
			+ preference.orderedInputs() + '|' + preference.state().normalizedSignature()
			+ '|' + preference.candidateEmissionFact().normalizedSignature();
	}

	static void validate(PlacementAnalysis analysis, String analysisFingerprint,
		List<CompiledHopKey> orderedScope, List<RepresentativePreference> preferences,
		List<DecisionFact> decisions,
		List<DirectedEdgeFact> edges, List<AuxiliaryGroupFact> groups,
		List<TransferAuthorityFact> transferAuthorities,
		List<ObligationFact> obligations, String derivationFingerprint) {
		Objects.requireNonNull(analysis, "analysis");
		if(!analysis.analysisFingerprint().equals(analysisFingerprint))
			fail(ValidationReason.FOREIGN_OWNER, "Analysis fingerprint is foreign to its owner");
		validateScope(analysis, orderedScope);
		validateCapacitySums(edges);
		Derivation expected = deriveUnchecked(analysis, orderedScope, preferences);
		if(!sameDecisions(expected.decisions, decisions))
			fail(ValidationReason.RAW_STATE_RECEIPT_MISMATCH,
				"Decision states differ from the pre-solve legality projection");
		validateGroups(expected.groups, groups);
		validateTransferAuthorityOwnership(analysis, groups, transferAuthorities, expected.representatives);
		if(!sameTransferAuthorities(expected.transferAuthorities, transferAuthorities))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Transfer authority facts differ from the neutral graph");
		if(!sameObligations(expected.obligations, obligations))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Obligation facts differ from the neutral graph");
		if(!sameEdges(expected.edges, edges))
			fail(ValidationReason.CAPACITY_SUM_MISMATCH,
				"Directed edge facts differ from their canonical derivation");
		if(!expected.fingerprint.equals(derivationFingerprint))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Derivation fingerprint is stale or forged");
	}

	private static Derivation deriveUnchecked(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope, List<RepresentativePreference> preferences) {
		List<DecisionFact> initialDecisions = new ArrayList<>(orderedScope.size());
		for(int index = 0; index < orderedScope.size(); index++) {
			CompiledHopKey key = orderedScope.get(index);
			NeutralPlacementGraph.Node node = analysis.graph().node(key).orElseThrow();
			if(!node.emittedWork())
				continue;
			List<PlacementState> states = legalStates(analysis, key, node);
			DecisionFact decision = new DecisionFact(key, computeNodeId(index),
				placementNodeId(index), states);
			initialDecisions.add(decision);
		}
		List<DecisionFact> decisions = authorityClosedDecisions(analysis, initialDecisions, preferences);
		IdentityHashMap<CompiledHopKey, DecisionFact> decisionsByKey = decisionsByKey(decisions);

		int workers = workerCount(analysis.graph());
		Map<String,List<OccurrenceProfile>> occurrenceProfiles = occurrenceProfiles(analysis);
		List<MembershipRepresentative> representatives = membershipRepresentatives(
			analysis, decisions, preferences);
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs =
			effectiveLogicalFunctionInputs(analysis);
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("MinST-ExactFunctionFacts", "logicalFunctionInputs="
				+ analysis.logicalFunctionInputsInCanonicalOrder().size() + ", logicalTransientInputs="
				+ analysis.logicalTransientInputsInCanonicalOrder().size() + ", effectiveFunctionInputs="
				+ effectiveFunctionInputs.size());
		EdgeAccumulator accumulator = new EdgeAccumulator();
		for(DecisionFact decision : decisions)
			addDecisionEdges(analysis, decision, representatives, preferences,
				workers, occurrenceProfiles, accumulator);
		addRepresentativePreferenceEdges(analysis, decisionsByKey, representatives, preferences, accumulator);
		addPresentInputAuthorityEdges(analysis, decisionsByKey, representatives, preferences, accumulator);
		addNeutralConstraintEdges(analysis, decisionsByKey, representatives, accumulator);
		addLogicalTransientInputEdges(analysis, decisionsByKey, representatives,
			effectiveFunctionInputs, accumulator);
		addLogicalFunctionInputEdges(analysis, decisionsByKey, representatives,
			effectiveFunctionInputs, occurrenceProfiles, accumulator);
		List<AuxiliaryGroupFact> groups = deriveGroups(analysis, orderedScope,
			decisionsByKey, representatives, preferences, effectiveFunctionInputs, workers,
			occurrenceProfiles, accumulator);
		List<ObligationFact> obligations = deriveObligations(analysis, decisionsByKey);
		List<DirectedEdgeFact> edges = accumulator.freeze();
		List<TransferAuthorityFact> transferAuthorities = transferAuthorities(analysis, groups, representatives,
			decisionsByKey, preferences);
		String fingerprint = fingerprint(analysis, orderedScope, decisions, representatives,
			edges, groups, transferAuthorities, obligations);
		return new Derivation(List.copyOf(decisions), representatives, edges, groups,
			transferAuthorities, obligations, fingerprint);
	}

	static List<MembershipRepresentative> membershipRepresentatives(PlacementAnalysis analysis,
		List<DecisionFact> decisions) {
		return membershipRepresentatives(analysis, decisions, List.of());
	}

	static List<MembershipRepresentative> membershipRepresentatives(PlacementAnalysis analysis,
		List<DecisionFact> decisions, List<RepresentativePreference> preferences) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(decisions, "decisions");
		IdentityHashMap<CompiledHopKey,DecisionFact> decisionsByKey = new IdentityHashMap<>();
		for(DecisionFact decision : decisions)
			decisionsByKey.put(decision.key(), decision);
		MembershipMaterialization materialization = new MembershipMaterialization(
			analysis, decisionsByKey, preferences);
		List<MembershipRepresentative> result = new ArrayList<>();
		for(DecisionFact decision : decisions) {
			NeutralPlacementGraph.Node node = analysis.graph().node(decision.key()).orElseThrow(() ->
				new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_NODE_MISSING"));
			for(List<PlacementState> states : statesByMembership(decision).values())
				result.add(materialization.materialize(decision, node, states));
		}
		return List.copyOf(result);
	}

	/**
	 * Removes only memberships that cannot be materialized from graph-owned authority.  This
	 * closure runs before the cut is built, so an impossible downstream FED row cannot survive
	 * merely because an upstream neutral node advertised an ungrounded FOUT alternative.
	 */
	private static List<DecisionFact> authorityClosedDecisions(PlacementAnalysis analysis,
		List<DecisionFact> initialDecisions, List<RepresentativePreference> preferences) {
		List<DecisionFact> current = List.copyOf(initialDecisions);
		int remainingStates = current.stream()
			.mapToInt(decision -> decision.legalStatesInCanonicalOrder().size()).sum();
		for(int iteration = 0; iteration <= remainingStates; iteration++) {
			IdentityHashMap<CompiledHopKey,DecisionFact> byKey = decisionsByKey(current);
			MembershipMaterialization materialization = new MembershipMaterialization(
				analysis, byKey, preferences);
			List<DecisionFact> next = new ArrayList<>(current.size());
			boolean changed = false;
			for(DecisionFact decision : current) {
				NeutralPlacementGraph.Node node = analysis.graph().node(decision.key()).orElseThrow(() ->
					new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_NODE_MISSING"));
				Set<PlacementState> retained = new TreeSet<>();
				for(List<PlacementState> states : statesByMembership(decision).values()) {
					MembershipRepresentative representative =
						materialization.materializeOrNull(decision, node, states);
					if(representative != null)
						retained.add(representative.state());
				}
				if(retained.isEmpty())
					throw new IllegalArgumentException("MINST_EXACT_DECISION_AUTHORITY_EMPTY|key="
						+ diagnosticKey(decision.key()) + "|facts="
						+ materialization.describeProducer(decision.key()));
				List<PlacementState> retainedStates = List.copyOf(retained);
				boolean decisionChanged = !retainedStates.equals(
					decision.legalStatesInCanonicalOrder());
				changed |= decisionChanged;
				if(decisionChanged && FederatedPlannerTrace.isEnabled())
					FederatedPlannerTrace.logGlobal("MinST-AuthorityClosure", "iteration="
						+ iteration + ", key=" + decision.key().normalizedSignature()
						+ ", before=" + decision.legalStatesInCanonicalOrder().stream()
							.map(PlacementState::normalizedSignature).toList()
						+ ", after=" + retainedStates.stream()
							.map(PlacementState::normalizedSignature).toList());
				next.add(new DecisionFact(decision.key(), decision.computeNodeId(),
					decision.placementNodeId(), retainedStates));
			}
			current = List.copyOf(next);
			if(!changed) {
				// Replay through the strict path so the published fixed point cannot contain a
				// missing, cyclic or ambiguous proof hidden by the exploratory closure.
				membershipRepresentatives(analysis, current, preferences);
				return current;
			}
		}
		throw new IllegalArgumentException("MINST_EXACT_DECISION_AUTHORITY_NON_CONVERGENT");
	}

	private static IdentityHashMap<CompiledHopKey,DecisionFact> decisionsByKey(
		List<DecisionFact> decisions) {
		IdentityHashMap<CompiledHopKey,DecisionFact> result = new IdentityHashMap<>();
		for(DecisionFact decision : decisions)
			result.put(decision.key(), decision);
		return result;
	}

	static void validateMembershipRepresentatives(MinStExactCostFacts facts) {
		List<MembershipRepresentative> actual = facts.membershipRepresentativesInCanonicalOrder();
		List<MembershipRepresentative> expected = membershipRepresentatives(facts.analysis(),
			facts.decisionFactsInScopeOrder(), facts.representativePreferences());
		if(!sameRepresentatives(expected, actual))
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_STALE_OR_FORGED");
	}

	private static Map<String,List<PlacementState>> statesByMembership(DecisionFact decision) {
		Map<String,List<PlacementState>> byMembership = new java.util.TreeMap<>();
		for(PlacementState state : decision.legalStatesInCanonicalOrder())
			byMembership.computeIfAbsent(membership(state.execType(), state.output()), ignored ->
				new ArrayList<>()).add(state);
		return byMembership;
	}

	private static final class MembershipMaterialization {
		private final PlacementAnalysis analysis;
		private final IdentityHashMap<CompiledHopKey,DecisionFact> decisionsByKey;
		private final IdentityHashMap<CompiledHopKey,Map<String,MembershipRepresentative>> cache =
			new IdentityHashMap<>();
		private final IdentityHashMap<CompiledHopKey,Set<String>> visiting = new IdentityHashMap<>();
		private final List<RepresentativePreference> preferences;

		private MembershipMaterialization(PlacementAnalysis analysis,
			IdentityHashMap<CompiledHopKey,DecisionFact> decisionsByKey,
			List<RepresentativePreference> preferences) {
			this.analysis = analysis;
			this.decisionsByKey = decisionsByKey;
			this.preferences = List.copyOf(preferences);
		}

		private RepresentativePreference preferenceFor(DecisionFact decision,
			ExecType execType, FederatedOutput output) {
			List<RepresentativePreference> matching = preferences.stream()
				.filter(preference -> preference.decisionKey() == decision.key()
					&& preference.execType() == execType && preference.output() == output)
				.toList();
			if(matching.size() > 1)
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_DUPLICATE|key="
					+ diagnosticKey(decision.key()));
			return matching.isEmpty() ? null : matching.get(0);
		}

		private MembershipRepresentative materialize(DecisionFact decision, NeutralPlacementGraph.Node node,
			List<PlacementState> states) {
			MembershipRepresentative representative = materializeOrNull(decision, node, states);
			if(representative == null)
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_UNPROVEN|key="
					+ diagnosticKey(decision.key()) + "|membership=" + membershipKey(states)
					+ "|facts=" + describeProducer(decision.key()));
			return representative;
		}

		private MembershipRepresentative materializeOrNull(DecisionFact decision,
			NeutralPlacementGraph.Node node, List<PlacementState> states) {
			String membership = authorityDomainKey(states);
			Map<String,MembershipRepresentative> byMembership = cache.computeIfAbsent(decision.key(),
				ignored -> new LinkedHashMap<>());
			MembershipRepresentative cached = byMembership.get(membership);
			if(cached != null)
				return cached;
			Set<String> active = visiting.computeIfAbsent(decision.key(),
				ignored -> new LinkedHashSet<>());
			if(!active.add(membership))
				return null;
			try {
				MembershipRepresentative representative = representativeOrNull(
					analysis, decision, node, states, this);
				if(representative != null)
					byMembership.put(membership, representative);
				return representative;
			}
			finally {
				active.remove(membership);
				if(active.isEmpty())
					visiting.remove(decision.key());
			}
		}

		private MembershipRepresentative exactProducer(CompiledHopKey producerKey, FType expectedType) {
			DecisionFact producerDecision = decisionsByKey.get(producerKey);
			if(producerDecision == null)
				return null;
			NeutralPlacementGraph.Node producerNode = analysis.graph().node(producerKey).orElseThrow(() ->
				new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_NODE_MISSING"));
			Map<String,List<PlacementState>> memberships = statesByMembership(producerDecision);
			List<MembershipRepresentative> matches = new ArrayList<>();
			for(ExecType execType : List.of(ExecType.CP, ExecType.FED)) {
				List<PlacementState> states = memberships.get(membership(execType, FederatedOutput.FOUT));
				if(states == null || states.isEmpty())
					continue;
				states = states.stream().filter(state -> state.fType() == expectedType).toList();
				if(states.isEmpty())
					continue;
				MembershipRepresentative representative =
					materializeOrNull(producerDecision, producerNode, states);
				if(representative == null)
					continue;
				if(representative.authorityKind() != MembershipAuthorityKind.LEGAL_SINGLETON
					&& representative.output() == FederatedOutput.FOUT
					&& representative.state().fType() == expectedType)
					matches.add(representative);
			}
			if(matches.size() > 1) {
				List<MembershipRepresentative> federated = matches.stream()
					.filter(match -> match.execType() == ExecType.FED).toList();
				// The dependency is on the producer's FOUT placement membership, shared by
				// CP/FOUT and FED/FOUT in the two-bit cut. When both execution alternatives
				// publish the same exact layout, retain the legacy FED/FOUT authority as the
				// canonical proof; the cut still chooses CP versus FED independently.
				if(federated.size() == 1)
					return federated.get(0);
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_INPUT_OUTPUT_AUTHORITY_AMBIGUOUS|producer="
					+ producerKey.normalizedSignature() + "|ftype=" + expectedType + "|states="
					+ matches.stream().map(match -> match.state().normalizedSignature()).toList());
			}
			return matches.size() == 1 ? matches.get(0) : null;
		}

		private String describeProducer(CompiledHopKey producerKey) {
			DecisionFact decision = decisionsByKey.get(producerKey);
			if(decision == null)
				return "UNSCOPED";
			NeutralPlacementGraph.Node node = analysis.graph().node(producerKey).orElse(null);
			List<String> rules = analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == producerKey)
				.map(fact -> fact.key().orderedInputs() + "=>" + fact.allowedEmissionFacts().stream()
					.map(emission -> emission.emissionState().placementState().normalizedSignature()
						+ "/execFType=" + emission.executionFType()
						+ "/derived=" + emission.emissionState().derivedFedFout())
					.toList())
				.toList();
			List<String> inputs = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == producerKey)
				.map(edge -> {
					NeutralPlacementGraph.Node input = analysis.graph().node(edge.producer()).orElseThrow();
					DecisionFact inputDecision = decisionsByKey.get(edge.producer());
					return edge.inputPosition() + ":" + analysis.hop(edge.producer()).orElseThrow().getOpString()
						+ "/decision=" + (inputDecision == null ? List.of()
							: inputDecision.legalStatesInCanonicalOrder().stream()
								.map(PlacementState::normalizedSignature).toList())
						+ "/node=" + input.legalAlternatives().stream()
							.map(PlacementState::normalizedSignature).toList()
						+ "/anchors=" + input.anchors().stream()
							.map(DurableAnchorKey::normalizedSignature).toList()
						+ "/relocations=" + analysis.graph().relocationActions().stream()
							.filter(action -> action.key().sourceValueVersion().equals(input.valueVersion()))
							.map(action -> action.key().targetPlacement().normalizedSignature()).toList();
				})
				.toList();
			String details = "legal=" + decision.legalStatesInCanonicalOrder().stream()
				.map(PlacementState::normalizedSignature).toList()
				+ "|nodeLegal=" + (node == null ? List.of() : node.legalAlternatives().stream()
					.map(PlacementState::normalizedSignature).toList())
				+ "|anchors=" + (node == null ? List.of() : node.anchors().stream()
					.map(DurableAnchorKey::normalizedSignature).toList())
				+ "|relocations=" + analysis.graph().relocationActions().stream()
					.filter(action -> node != null
						&& action.key().sourceValueVersion().equals(node.valueVersion()))
					.map(action -> action.key().targetPlacement().normalizedSignature()).toList()
				+ "|rules=" + rules + "|inputs=" + inputs;
			return "key=" + diagnosticKey(producerKey) + "|legal="
				+ decision.legalStatesInCanonicalOrder().stream()
					.map(PlacementState::normalizedSignature).toList()
				+ "|ruleCount=" + rules.size() + "|inputCount=" + inputs.size()
				+ "|detailHash=" + Integer.toUnsignedString(details.hashCode(), 16)
				+ "|detailChars=" + details.length();
		}

		private static String membershipKey(List<PlacementState> states) {
			if(states.isEmpty())
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_MISSING");
			PlacementState first = states.get(0);
			return membership(first.execType(), first.output());
		}

		private static String authorityDomainKey(List<PlacementState> states) {
			return membershipKey(states) + '|' + states.stream()
				.map(PlacementState::normalizedSignature).sorted().toList();
		}
	}

	private static MembershipRepresentative representativeOrNull(PlacementAnalysis analysis,
		DecisionFact decision, NeutralPlacementGraph.Node node, List<PlacementState> states,
		MembershipMaterialization materialization) {
		if(states.isEmpty())
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_MISSING");
		PlacementState first = states.get(0);
		for(PlacementState state : states)
			if(state.execType() != first.execType() || state.output() != first.output())
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_KEY_MISMATCH");

		if(states.size() == 1 && first.output() == FederatedOutput.LOUT
			&& (first.execType() != ExecType.FED
				|| !hasAuthorityBearingInputs(analysis, decision.key())))
			return new MembershipRepresentative(decision.key(), first.execType(), first.output(), first,
				MembershipAuthorityKind.LEGAL_SINGLETON, null, null, null, List.of(), List.of(), null, null, null);
		MembershipRepresentative anchored = durableRepresentative(analysis, decision, node, states);
		if(anchored != null)
			return anchored;
		MembershipRepresentative captured = capturedRuleRepresentative(analysis, decision, states, materialization);
		if(captured != null && (captured.execType() != ExecType.FED
			|| captured.candidateEmissionFactOrNull() != null))
			return captured;
		MembershipRepresentative relocation = relocationRepresentative(analysis, decision, states);
		if(relocation != null && relocation.execType() == ExecType.FED) {
			List<PlacementState> executionStates = decision.legalStatesInCanonicalOrder().stream()
				.filter(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.LOUT).toList();
			MembershipRepresentative execution = executionStates.isEmpty() ? null
				: capturedRuleRepresentative(analysis, decision, executionStates, materialization);
			if(execution == null || execution.candidateEmissionFactOrNull() == null)
				return null;
		}
		if(relocation != null)
			return relocation;
		return null;
	}

	private static boolean hasAuthorityBearingInputs(PlacementAnalysis analysis,
		CompiledHopKey decisionKey) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream()
			.anyMatch(edge -> edge.consumer() == decisionKey)
			|| analysis.logicalTransientInputsInCanonicalOrder().stream()
				.anyMatch(input -> input.targetRead() == decisionKey)
			|| analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.anyMatch(input -> input.targetRead() == decisionKey);
	}

	private static MembershipRepresentative durableRepresentative(PlacementAnalysis analysis,
		DecisionFact decision, NeutralPlacementGraph.Node node, List<PlacementState> states) {
		List<DurableAnchorKey> authorities = new ArrayList<>();
		Hop hop = analysis.hop(decision.key()).orElseThrow(() ->
			new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_HOP_MISSING"));
		boolean federatedSource = hop instanceof DataOp
			&& ((DataOp)hop).getOp() == OpOpData.FEDERATED;
		if(federatedSource)
			authorities.addAll(node.anchors());
		List<DurableAnchorKey> unique = identityDistinct(authorities);
		if(unique.isEmpty()) return null;
		if(unique.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_ANCHOR_AMBIGUOUS|key="
				+ decision.key().normalizedSignature());
		DurableAnchorKey anchor = unique.get(0);
		List<PlacementState> matching = states.stream().filter(state -> state.fType() == anchor.fType()).toList();
		if(matching.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_ANCHOR_STATE_"
				+ (matching.isEmpty() ? "MISSING" : "AMBIGUOUS") + "|key="
				+ decision.key().normalizedSignature());
		PlacementState state = matching.get(0);
		return new MembershipRepresentative(decision.key(), state.execType(), state.output(), state,
			MembershipAuthorityKind.DURABLE_ANCHOR, anchor, null, null, List.of(), List.of(), null, null, null);
	}

	private static MembershipRepresentative relocationRepresentative(PlacementAnalysis analysis,
		DecisionFact decision, List<PlacementState> states) {
		NeutralPlacementGraph.Node node = analysis.graph().node(decision.key()).orElseThrow();
		List<NeutralPlacementGraph.RelocationAction> actions = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(node.valueVersion())
				&& states.stream().anyMatch(state -> state.equals(action.key().targetPlacement())))
			.toList();
		if(actions.isEmpty()) return null;
		List<PlacementState> retained = states.stream().filter(state -> actions.stream()
			.anyMatch(action -> state.equals(action.key().targetPlacement()))).toList();
		if(retained.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RELOCATION_STATE_AMBIGUOUS|key="
				+ decision.key().normalizedSignature());
		PlacementState state = retained.get(0);
		List<NeutralPlacementGraph.RelocationAction> matchingActions = actions.stream()
			.filter(action -> action.key().targetPlacement().equals(state)).toList();
		if(matchingActions.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RELOCATION_ACTION_"
				+ (matchingActions.isEmpty() ? "MISSING" : "AMBIGUOUS") + "|key="
				+ decision.key().normalizedSignature());
		NeutralPlacementGraph.RelocationAction retainedAction = matchingActions.get(0);
		List<DurableAnchorKey> anchors = identityDistinct(List.of(retainedAction.key().durableAnchor()));
		if(anchors.size() != 1 || anchors.get(0).fType() != state.fType())
			return null;
		return new MembershipRepresentative(decision.key(), state.execType(), state.output(), state,
			MembershipAuthorityKind.RELOCATION_SOURCE, anchors.get(0), null, null, List.of(), List.of(), null, retainedAction, null);
	}

	private static MembershipRepresentative capturedRuleRepresentative(PlacementAnalysis analysis,
		DecisionFact decision, List<PlacementState> states,
		MembershipMaterialization materialization) {
		CapturedInvocationEvidence invocation = capturedInvocationEvidence(analysis, decision.key());
		List<MembershipRepresentative> matches = new ArrayList<>();
		ExecType membershipExec = states.get(0).execType();
		FederatedOutput membershipOutput = states.get(0).output();
		RepresentativePreference preference = materialization.preferenceFor(
			decision, membershipExec, membershipOutput);
		boolean exactFedFoutMembership =
			membershipExec == ExecType.FED && membershipOutput == FederatedOutput.FOUT;
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.key().parentOccurrence() != decision.key()
				|| fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.capability() == null
				|| !fact.profile().available())
				continue;
			CapturedResolution resolution;
			try {
				resolution = PlacementCandidateRuleResolver.resolveCaptured(new CapturedResolutionRequest(
					analysis, analysis.analysisFingerprint(), decision.key(), fact.key().orderedInputs(), invocation));
			}
			catch(IllegalArgumentException ex) {
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RULE_RESOLUTION_FAILED|key="
					+ decision.key().normalizedSignature() + "|inputs=" + fact.key().orderedInputs(), ex);
			}
			if(resolution.fact() != fact)
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RULE_IDENTITY_MISMATCH");
			PlacementState state;
			CandidateEmissionFact exactEmission = null;
			List<CandidateEmissionFact> exactEmissions = fact.allowedEmissionFacts().stream()
				.filter(emission -> emission.emissionState().placementState().execType() == membershipExec
					&& emission.emissionState().placementState().output() == membershipOutput)
				.filter(emission -> states.stream()
					.anyMatch(candidate -> candidate == emission.emissionState().placementState()))
				.toList();
			if(!exactEmissions.isEmpty()) {
				if(preference != null && preference.candidateRuleFact() == fact) {
					exactEmission = exactEmissions.stream()
						.filter(emission -> emission == preference.candidateEmissionFact())
						.findFirst().orElse(null);
					if(exactEmission == null)
						continue;
				}
				else
					if(exactEmissions.size() == 1)
						exactEmission = exactEmissions.get(0);
					else if(exactEmissions.stream().allMatch(emission ->
						emission.emissionState().placementState()
							== exactEmissions.get(0).emissionState().placementState()))
						exactEmission = exactEmissions.stream()
							.sorted(Comparator.comparing(CandidateEmissionFact::normalizedSignature))
							.findFirst().orElseThrow();
					else
						exactEmission = selectCostDominatedInternalEmission(
							analysis, decision, fact, exactEmissions);
				// The resolver's logical FType is a downstream consumer-safe projection. It may be
				// BROADCAST while the exact runtime emission remains ROW/COL (notably for a formal
				// function TRead). The builder-owned emission fact is the execution-layout authority;
				// equating these two different facts rejected legal MinST memberships before solving.
				state = exactEmission.emissionState().placementState();
			}
			else {
				if(exactFedFoutMembership)
					continue;
				FType fType = resolution.logicalFType();
				if(fType == null || fType == FType.OTHER || fType == FType.PART)
					continue;
				boolean shapeDependent = !fact.shapeProof().requiredFacts().isEmpty();
				Hop decisionHop = analysis.hop(decision.key()).orElseThrow();
				boolean transientData = decisionHop instanceof DataOp
					&& (((DataOp) decisionHop).getOp() == OpOpData.TRANSIENTREAD
						|| ((DataOp) decisionHop).getOp() == OpOpData.TRANSIENTWRITE);
				List<PlacementState> retained = states.stream().filter(candidate -> candidate.fType() == fType
					&& (transientData || candidate.shapeDependent() == shapeDependent)).toList();
				if(retained.size() > 1)
					throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RULE_STATE_AMBIGUOUS|key="
						+ decision.key().normalizedSignature());
				if(retained.isEmpty())
					continue;
				state = retained.get(0);
			}
			List<MembershipInputAuthorityFact> inputAuthorities = retainedInputAuthorities(analysis,
				decision.key(), fact, exactEmission, state, materialization);
			if(inputAuthorities == null)
				continue;
			matches.add(new MembershipRepresentative(decision.key(), state.execType(), state.output(), state,
				MembershipAuthorityKind.CAPTURED_RULE, null, fact, exactEmission,
				fact.key().orderedInputs(), inputAuthorities,
				invocation, null, null));
		}
		if(matches.isEmpty()) return null;
		if(preference != null) {
			List<MembershipRepresentative> preferred = matches.stream()
				.filter(match -> match.state() == preference.state()
					&& match.orderedInputs().equals(preference.orderedInputs())
					&& match.candidateRuleFactOrNull() == preference.candidateRuleFact()
					&& match.candidateEmissionFactOrNull() == preference.candidateEmissionFact())
				.toList();
			if(preferred.size() == 1)
				return preferred.get(0);
			if(preferred.size() > 1)
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_AMBIGUOUS|key="
					+ decision.key().normalizedSignature());
			return null;
		}
		if(matches.size() > 1) {
			int strongestCoverage = matches.stream()
				.mapToInt(representative -> exactInputAuthorityCoverage(analysis, decision.key(), representative))
				.max().orElse(-1);
			List<MembershipRepresentative> strongest = matches.stream()
				.filter(representative -> exactInputAuthorityCoverage(analysis, decision.key(), representative)
					== strongestCoverage)
				.toList();
			// Multiple candidate-domain rows may produce the same MinST membership. Prefer the
			// unique row proven by the largest exact producer domain: direct anchor/relocation
			// authority plus recursively materialized producer membership. This changes neither
			// the legal membership nor its cost; it only prevents an ABSENT_LOCAL hypothesis from
			// erasing a stronger graph-owned input proof. A true equal-strength tie still fails.
			if(strongestCoverage > 0 && strongest.size() == 1)
				return strongest.get(0);
			if(strongestCoverage > 0) {
				int strongestNativeFout = strongest.stream()
					.mapToInt(MinStExactCostFactsProducer::nativeFederatedInputAuthorityCount)
					.max().orElse(-1);
				List<MembershipRepresentative> nativeStrongest = strongest.stream()
					.filter(representative -> nativeFederatedInputAuthorityCount(representative)
						== strongestNativeFout)
					.toList();
				// When two rows cover the same exact inputs, retain the row backed by more
				// native FED/FOUT producer memberships instead of a CP/FOUT rematerialization.
				// This is the legacy MinST domain preference and avoids charging/planning a
				// synthetic refed path when an exact federated producer already exists.
			if(strongestNativeFout > 0 && nativeStrongest.size() == 1)
					return nativeStrongest.get(0);
				if(strongestNativeFout > 0)
					strongest = nativeStrongest;
			}
			// Baseline derivation only needs one stable representative for the coarse
			// MinST membership.  Every other exact candidate/emission row is retained by
			// exactCandidateRowPreferenceGroups and evaluated as its own variant.  Hence an
			// equal-strength tie here is not an authority ambiguity: choose the canonical
			// physical row deterministically, rather than making production derivation
			// depend on CandidateRuleFact iteration order.
			return strongest.stream()
				.min(Comparator.comparing(MinStExactCostFactsProducer::physicalRepresentativeSignature))
				.orElseThrow();
		}
		if(matches.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RULE_AUTHORITY_AMBIGUOUS|key="
				+ decision.key().normalizedSignature() + "|membership="
				+ membership(states.get(0).execType(), states.get(0).output()) + "|matches="
				+ matches.stream().map(match -> "state=" + match.state().normalizedSignature()
					+ ",inputs=" + match.orderedInputs()
					+ ",authorities=" + match.inputAuthorityFacts().stream()
						.map(MembershipInputAuthorityFact::authoritySignature).toList()).toList());
		return matches.get(0);
	}

	/**
	 * Projects runtime-supported execution arms that have the same externally visible
	 * {@code FED/LOUT} membership onto MinST's two-bit decision.
	 *
	 * <p>A local output erases the worker layout before any downstream consumer observes
	 * the value.  If the exact candidate row also fixes every matrix input to an existing
	 * federated representation, the arms differ only in the internal execution layout.
	 * Their producer/consumer cut obligations are therefore identical and the arm with
	 * the lowest complete FED-unary plus local-result cost is a dominated internal choice.
	 * Neutral analysis and DP keep every arm; this projection only chooses the exact arm
	 * represented by MinST's coarser membership.  A cost tie is resolved by the least
	 * shape-dependent exact proof and then by the canonical emission signature.</p>
	 *
	 * <p>Do not extend this reduction to FOUT or to rows with coordinator-local matrix
	 * inputs.  Those alternatives can change downstream placement or shared relocation
	 * costs and require an expanded cut state rather than a local tie-break.</p>
	 */
	private static CandidateEmissionFact selectCostDominatedInternalEmission(
		PlacementAnalysis analysis, DecisionFact decision, CandidateRuleFact fact,
		List<CandidateEmissionFact> exactEmissions) {
		boolean fedLout = exactEmissions.stream().allMatch(emission -> {
			PlacementState state = emission.emissionState().placementState();
			return state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT
				&& !emission.emissionState().derivedFedFout() && emission.executionFType() != null;
		});
		if(!fedLout)
			throw internalEmissionAmbiguity(decision, fact, exactEmissions,
				"non-local-or-derived-membership-requires-expanded-cut-state");
		Hop hop = analysis.hop(decision.key()).orElseThrow(() ->
			new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_HOP_MISSING"));
		List<FType> inputFTypes = exactPresentFedInputTypes(hop, fact);
		if(inputFTypes == null)
			throw internalEmissionAmbiguity(decision, fact, exactEmissions,
				"local-matrix-input-requires-global-relocation-cost");
		int workers = workerCount(analysis.graph());
		if(workers <= 0)
			throw internalEmissionAmbiguity(decision, fact, exactEmissions,
				"worker-count-unproven");
		List<CostedInternalEmission> costed = exactEmissions.stream()
			.map(emission -> new CostedInternalEmission(emission,
				fedCostProjection(analysis, decision.key(), hop, inputFTypes,
					emission.executionFType(), workers, 1.0).fedLoutCost()))
			.sorted(Comparator.comparingDouble(CostedInternalEmission::cost)
				.thenComparing(cost -> cost.emission().emissionState().placementState().shapeDependent())
				.thenComparing(cost -> cost.emission().normalizedSignature()))
			.toList();
		CandidateEmissionFact selected = costed.get(0).emission();
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("MinST-InternalEmissionReduction", "key="
				+ decision.key().normalizedSignature() + ", inputs=" + fact.key().orderedInputs()
				+ ", costs=" + costed.stream().map(cost -> cost.emission().normalizedSignature()
					+ '@' + cost.cost()).toList() + ", selected=" + selected.normalizedSignature());
		return selected;
	}

	private static List<FType> exactPresentFedInputTypes(Hop hop, CandidateRuleFact fact) {
		List<CandidateInputState> inputs = fact.key().orderedInputs();
		List<FType> result = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			Hop input = hop.getInput(position);
			if(input == null || input.getDataType() == null || !input.getDataType().isMatrix()) {
				result.add(null);
				continue;
			}
			if(position >= inputs.size() || !inputs.get(position).present()
				|| inputs.get(position).fType() == null)
				return null;
			result.add(inputs.get(position).fType());
		}
		return List.copyOf(result);
	}

	private static IllegalArgumentException internalEmissionAmbiguity(DecisionFact decision,
		CandidateRuleFact fact, List<CandidateEmissionFact> exactEmissions, String reason) {
		return new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_RULE_EMISSION_AMBIGUOUS|key="
			+ decision.key().normalizedSignature() + "|membership="
			+ membership(exactEmissions.get(0).emissionState().placementState().execType(),
				exactEmissions.get(0).emissionState().placementState().output())
			+ "|inputs=" + fact.key().orderedInputs() + "|projection=" + reason
			+ "|emissions=" + exactEmissions.stream()
				.map(CandidateEmissionFact::normalizedSignature).toList());
	}

	private record CostedInternalEmission(CandidateEmissionFact emission, double cost) { }

	private static int exactInputAuthorityCoverage(PlacementAnalysis analysis,
		CompiledHopKey consumer, MembershipRepresentative representative) {
		List<CandidateInputState> inputs = representative.orderedInputs();
		int coverage = 0;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.consumer() != consumer)
				continue;
			if(edge.inputPosition() < 0 || edge.inputPosition() >= inputs.size())
				return -1;
			CandidateInputState expected = inputs.get(edge.inputPosition());
			if(!expected.present()) {
				if(representative.execType() == ExecType.FED
					&& !isNativeLocalInput(representative, edge.inputPosition()))
					return -1;
				continue;
			}
			FType direct = exactInputAuthorityType(analysis, edge.producer());
			if(direct != null) {
				if(expected.fType() != direct)
					return -1;
				coverage++;
				continue;
			}
			List<MembershipInputAuthorityFact> materialized = representative.inputAuthorityFacts().stream()
				.filter(authority -> authority.inputEdge() == edge
					&& authority.inputPosition() == edge.inputPosition())
				.toList();
			if(materialized.size() > 1)
				return -1;
			if(materialized.size() == 1) {
				MembershipRepresentative producer = materialized.get(0).producerRepresentative();
				if(!expected.present() || producer.output() != FederatedOutput.FOUT
					|| producer.state().fType() != expected.fType())
					return -1;
				coverage++;
			}
			else if(!relocationAuthorityForPresentInput(
				analysis, edge, representative.state(), expected.fType()))
				return -1;
		}
		return coverage;
	}

	private static int nativeFederatedInputAuthorityCount(MembershipRepresentative representative) {
		return (int)representative.inputAuthorityFacts().stream()
			.map(MembershipInputAuthorityFact::producerRepresentative)
			.filter(producer -> producer.execType() == ExecType.FED
				&& producer.output() == FederatedOutput.FOUT)
			.count();
	}

	private static List<MembershipInputAuthorityFact> retainedInputAuthorities(PlacementAnalysis analysis,
		CompiledHopKey consumer, CandidateRuleFact fact, CandidateEmissionFact exactEmission,
		PlacementState retainedState,
		MembershipMaterialization materialization) {
		List<CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer).toList();
		List<CandidateInputState> inputs = fact.key().orderedInputs();
		List<MembershipInputAuthorityFact> inputAuthorities = new ArrayList<>();
		for(CompiledInputEdgeFact edge : edges) {
			if(edge.inputPosition() < 0 || edge.inputPosition() >= inputs.size()) return null;
			if(analysis.requireExactCompiledInputEdge(edge.producer(), edge.consumer(), edge.inputPosition()) != edge)
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_INPUT_EDGE_FOREIGN");
			CandidateInputState expected = inputs.get(edge.inputPosition());
			if(expected.present()) {
				FType direct = exactInputAuthorityType(analysis, edge.producer());
				if(direct != null) {
					if(direct != expected.fType())
						return null;
				}
				else {
					MembershipRepresentative producer = materialization.exactProducer(
						edge.producer(), expected.fType());
					if(producer != null)
						inputAuthorities.add(new MembershipInputAuthorityFact(edge, edge.inputPosition(), producer,
							membershipInputAuthoritySignature(edge, producer)));
					else if(!relocationAuthorityForPresentInput(
						analysis, edge, retainedState, expected.fType()))
						return null;
				}
			}
			else if(retainedState.execType() == ExecType.FED
				&& !isNativeLocalInput(fact, exactEmission, retainedState, edge.inputPosition()))
				return null;
		}
		for(int position = 0; position < inputs.size(); position++) {
			final int inputPosition = position;
			boolean matrixEdge = edges.stream().anyMatch(edge -> edge.inputPosition() == inputPosition);
			if(matrixEdge)
				continue;
			CandidateInputState expected = inputs.get(position);
			if(expected.present()) {
				if(!logicalTransientInputMatches(analysis, consumer, inputPosition, expected,
					retainedState, materialization)
					&& !logicalFunctionInputMatches(analysis, consumer, inputPosition, expected,
						retainedState, materialization))
					return null;
				continue;
			}
			if(!expected.equals(CandidateInputState.absentLocal()))
				return null;
		}
		return List.copyOf(inputAuthorities);
	}

	private static boolean isNativeLocalInput(MembershipRepresentative representative,
		int inputPosition) {
		return representative.authorityKind() == MembershipAuthorityKind.CAPTURED_RULE
			&& isNativeLocalInput(representative.candidateRuleFactOrNull(),
				representative.candidateEmissionFactOrNull(),
				representative.state(), inputPosition);
	}

	private static boolean isNativeLocalInput(CandidateRuleFact fact,
		CandidateEmissionFact exactEmission, PlacementState state,
		int inputPosition) {
		if(fact == null || exactEmission == null || state.execType() != ExecType.FED
			|| inputPosition < 0 || inputPosition >= fact.key().orderedInputs().size()
			|| fact.key().orderedInputs().get(inputPosition).present())
			return false;
		if(exactEmission.emissionState().placementState() != state
			|| fact.allowedEmissionFacts().stream().noneMatch(emission -> emission == exactEmission))
			throw new IllegalArgumentException("MINST_EXACT_NATIVE_LOCAL_EMISSION_IDENTITY_MISMATCH");
		// ABSENT_LOCAL describes the execution input, independently of whether the
		// execution result is subsequently materialized as a derived FOUT.  Rejecting
		// derivedFedFout here conflates output authority with input reachability and
		// drops exact mixed-local rows accepted by CandidateSelections.
		return exactEmission.executionFType() != null;
	}

	private static String membershipInputAuthoritySignature(CompiledInputEdgeFact edge,
		MembershipRepresentative producer) {
		return "MEMBERSHIP_INPUT|" + edge.producer().normalizedSignature() + '|'
			+ edge.consumer().normalizedSignature() + '|' + edge.inputPosition() + '|'
			+ producer.authorityKind() + '|' + producer.state().normalizedSignature() + '|'
			+ representativeProofSignature(producer);
	}

	private static String representativeProofSignature(MembershipRepresentative representative) {
		StringBuilder signature = new StringBuilder("MEMBERSHIP_REP|")
			.append(representative.decisionKey().normalizedSignature()).append('|')
			.append(representative.execType()).append('|')
			.append(representative.output()).append('|')
			.append(representative.state().normalizedSignature()).append('|')
			.append(representative.authorityKind());
		if(representative.durableAnchorOrNull() != null)
			signature.append("|A=").append(representative.durableAnchorOrNull().normalizedSignature());
		if(representative.candidateRuleFactOrNull() != null) {
			CandidateRuleFact fact = representative.candidateRuleFactOrNull();
			signature.append("|R=")
				.append(fact.key().parentOccurrence().normalizedSignature()).append('/')
				.append(fact.key().orderedInputs()).append('/')
				.append(fact.status());
			CandidateCapabilityFact capability = fact.capability();
			if(capability != null)
				signature.append("/C=").append(capability.nativeExec()).append('/')
					.append(capability.nativeOutput()).append('/')
					.append(capability.nativeFoutFType());
			signature.append("|E=").append(candidateEmissionSignatureOrDash(representative));
			signature.append("|I=").append(representative.invocationEvidenceOrNull());
		}
		for(MembershipInputAuthorityFact inputAuthority : representative.inputAuthorityFacts())
			signature.append("|D=").append(inputAuthority.inputEdge().producer().normalizedSignature())
				.append('/').append(inputAuthority.inputEdge().consumer().normalizedSignature())
				.append('/').append(inputAuthority.inputPosition())
				.append('/').append(inputAuthority.authoritySignature());
		if(representative.relocationActionOrNull() != null)
			signature.append("|L=").append(representative.relocationActionOrNull()
				.key().normalizedSignature());
		if(representative.authoritySignatureOrNull() != null)
			signature.append("|S=").append(representative.authoritySignatureOrNull());
		return signature.toString();
	}

	private static boolean relocationAuthorityForPresentInput(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge, PlacementState retainedState, FType requiredType) {
		NeutralPlacementGraph.Node producer = analysis.graph().node(edge.producer()).orElseThrow();
		return analysis.graph().relocationActions().stream().anyMatch(action ->
			action.key().sourceValueVersion().equals(producer.valueVersion())
				&& action.key().targetPlacement().equals(retainedState)
				&& action.key().materializationFType() == requiredType
				&& action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == edge.consumer()
						&& obligation.inputPosition() == edge.inputPosition()
						&& obligation.sourceValueVersion().equals(producer.valueVersion())
						&& obligation.requiredPlacement().equals(retainedState)));
	}

	private static boolean logicalTransientInputMatches(PlacementAnalysis analysis, CompiledHopKey consumer,
		int inputPosition, CandidateInputState expected, PlacementState retainedState,
		MembershipMaterialization materialization) {
		List<LogicalTransientInputFact> matches = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == consumer && fact.logicalPosition() == inputPosition)
			.toList();
		if(matches.size() != 1)
			return false;
		LogicalTransientInputFact fact = matches.get(0);
		if(analysis.requireExactLogicalTransientInput(fact.sourceWrite(), consumer, inputPosition) != fact)
			return false;
		if(!expected.equals(fact.federatedInput())
			|| !fact.federatedSourceState().equals(retainedState))
			return false;
		MembershipRepresentative source = materialization.exactProducer(
			fact.sourceWrite(), expected.fType());
		return source != null && source.state().equals(fact.federatedSourceState());
	}

	private static boolean logicalFunctionInputMatches(PlacementAnalysis analysis, CompiledHopKey consumer,
		int inputPosition, CandidateInputState expected, PlacementState retainedState,
		MembershipMaterialization materialization) {
		List<LogicalFunctionInputFact> matches = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == consumer && fact.logicalPosition() == inputPosition)
			.toList();
		if(matches.size() != 1)
			return false;
		LogicalFunctionInputFact fact = matches.get(0);
		if(analysis.requireExactLogicalFunctionInput(fact.sourceArgument(), consumer, inputPosition) != fact
			|| retainedState.execType() != ExecType.FED
			|| retainedState.output() != FederatedOutput.FOUT)
			return false;
		FType sourceType = exactInputAuthorityType(analysis, fact.sourceArgument());
		if(sourceType != null)
			return expected.equals(CandidateInputState.present(sourceType))
				&& retainedState.fType() == sourceType;
		MembershipRepresentative source = materialization.exactProducer(
			fact.sourceArgument(), expected.fType());
		return source != null && source.output() == FederatedOutput.FOUT
			&& source.state().fType() == expected.fType()
			&& retainedState.fType() == expected.fType();
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

	private static List<DurableAnchorKey> identityDistinct(List<DurableAnchorKey> values) {
		List<DurableAnchorKey> result = new ArrayList<>();
		for(DurableAnchorKey value : values) {
			boolean retained = false;
			for(DurableAnchorKey existing : result)
				if(existing == value) {
					retained = true;
					break;
				}
			if(!retained) result.add(value);
		}
		return List.copyOf(result);
	}

	private static CapturedInvocationEvidence capturedInvocationEvidence(PlacementAnalysis analysis,
		CompiledHopKey parent) {
		Hop hop = analysis.hop(parent).orElseThrow(() ->
			new IllegalArgumentException("MINST_EXACT_INVOCATION_HOP_MISSING"));
		PlacementAnalysis.NodeShapeFact shape = analysis.shapeFact(parent).orElseThrow(() ->
			new IllegalArgumentException("MINST_EXACT_INVOCATION_SHAPE_MISSING"));
		NeutralPlacementGraph.Node node = analysis.graph().node(parent).orElseThrow(() ->
			new IllegalArgumentException("MINST_EXACT_INVOCATION_NODE_MISSING"));
		FType fedInitType = null;
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == OpOpData.FEDERATED) {
			List<FType> types = node.anchors().stream().map(DurableAnchorKey::fType).distinct().toList();
			if(types.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_INVOCATION_ANCHOR_AMBIGUOUS");
			fedInitType = types.get(0);
		}
		long rows = shape.rows(), cols = shape.cols();
		InvocationEvidence projection = new InvocationEvidence(
			hop instanceof FunctionOp
				&& ((FunctionOp)hop).getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN,
			shape.dataType().isMatrix(), rows == 1 && cols == 1,
			shape.dataType().isMatrix() && (rows == 1 || cols == 1), rows, cols, fedInitType,
			node.kind() == NodeKind.TRANSIENT_READ, vectorAxisMismatch(analysis, parent),
			axisLengthMismatch(analysis, parent, true), axisLengthMismatch(analysis, parent, false),
			null, workerCount(analysis.graph()));

		List<TransientForwardEvidence> availableForwards = transientForwards(analysis);
		Set<CompiledInputEdgeFact> retainedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<TransientForwardEvidence> retainedForwards = Collections.newSetFromMap(new IdentityHashMap<>());
		collectConsumerEvidence(analysis, parent, availableForwards, retainedEdges, retainedForwards,
			Collections.newSetFromMap(new IdentityHashMap<>()));
		Set<CompiledHopKey> forwardedWrites = Collections.newSetFromMap(new IdentityHashMap<>());
		for(TransientForwardEvidence forward : availableForwards)
			forwardedWrites.add(forward.writeOccurrence());
		List<ConsumerEdgeEvidence> edges = new ArrayList<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(!retainedEdges.contains(edge)) continue;
			NeutralPlacementGraph.Node consumer = analysis.graph().node(edge.consumer()).orElseThrow();
			ConsumerNodeKind kind = consumer.kind() == NodeKind.TRANSIENT_READ
				? ConsumerNodeKind.TRANSIENT_READ
				: consumer.kind() == NodeKind.TRANSIENT_WRITE
					? (forwardedWrites.contains(edge.consumer()) ? ConsumerNodeKind.TRANSIENT_WRITE
						: ConsumerNodeKind.TERMINAL_TRANSIENT_WRITE)
					: ConsumerNodeKind.NORMAL;
			edges.add(new ConsumerEdgeEvidence(edges.size(), edge.consumer(), edge.producer(),
				edge.inputPosition(), kind));
		}
		List<TransientForwardEvidence> forwards = new ArrayList<>();
		for(TransientForwardEvidence forward : availableForwards)
			if(retainedForwards.contains(forward))
				forwards.add(new TransientForwardEvidence(forwards.size(), forward.writeOccurrence(),
					forward.readOccurrence()));
		return new CapturedInvocationEvidence(projection, edges, forwards);
	}

	private static void collectConsumerEvidence(PlacementAnalysis analysis, CompiledHopKey producer,
		List<TransientForwardEvidence> availableForwards, Set<CompiledInputEdgeFact> retainedEdges,
		Set<TransientForwardEvidence> retainedForwards, Set<CompiledHopKey> visited) {
		if(!visited.add(producer)) return;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != producer) continue;
			retainedEdges.add(edge);
			NodeKind kind = analysis.graph().node(edge.consumer()).orElseThrow().kind();
			if(kind == NodeKind.TRANSIENT_READ) {
				collectConsumerEvidence(analysis, edge.consumer(), availableForwards, retainedEdges,
					retainedForwards, visited);
				continue;
			}
			if(kind != NodeKind.TRANSIENT_WRITE) continue;
			for(TransientForwardEvidence forward : availableForwards)
				if(forward.writeOccurrence() == edge.consumer()) {
					retainedForwards.add(forward);
					collectConsumerEvidence(analysis, forward.readOccurrence(), availableForwards,
						retainedEdges, retainedForwards, visited);
				}
		}
	}

	private static List<TransientForwardEvidence> transientForwards(PlacementAnalysis analysis) {
		List<NeutralPlacementGraph.Node> writes = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.TRANSIENT_WRITE).toList();
		List<NeutralPlacementGraph.Node> reads = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.TRANSIENT_READ).toList();
		List<TransientForwardEvidence> result = new ArrayList<>();
		for(NeutralPlacementGraph.Node write : writes) {
			String reference = "cfg-definition:" + valueReference(write);
			for(NeutralPlacementGraph.Node read : reads)
				if(read.valueVersion().predecessorVersions().contains(reference))
					result.add(new TransientForwardEvidence(result.size(), write.key(), read.key()));
		}
		return List.copyOf(result);
	}

	private static String valueReference(NeutralPlacementGraph.Node node) {
		var value = node.valueVersion();
		return value.lexicalVariable() + '#' + value.definitionOrdinal() + '@'
			+ value.definingControlRegion().callSitePath() + ':' + value.versionKind();
	}

	private static boolean vectorAxisMismatch(PlacementAnalysis analysis, CompiledHopKey producer) {
		FType producerAxis = vectorAxis(analysis.shapeFact(producer).orElseThrow());
		if(producerAxis == null) return false;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder())
			if(edge.producer() == producer) {
				FType consumerAxis = vectorAxis(analysis.shapeFact(edge.consumer()).orElseThrow());
				if(consumerAxis != null && consumerAxis != producerAxis) return true;
			}
		return false;
	}

	private static FType vectorAxis(PlacementAnalysis.NodeShapeFact shape) {
		if(!shape.dataType().isMatrix()) return null;
		if(shape.cols() == 1 && shape.rows() != 1) return FType.ROW;
		if(shape.rows() == 1 && shape.cols() != 1) return FType.COL;
		return null;
	}

	private static boolean axisLengthMismatch(PlacementAnalysis analysis, CompiledHopKey producer,
		boolean row) {
		PlacementAnalysis.NodeShapeFact source = analysis.shapeFact(producer).orElseThrow();
		long length = row ? source.rows() : source.cols();
		if(length <= 0) return false;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder())
			if(edge.producer() == producer) {
				PlacementAnalysis.NodeShapeFact target = analysis.shapeFact(edge.consumer()).orElseThrow();
				long targetLength = row ? target.rows() : target.cols();
				if(targetLength > 0 && targetLength != length) return true;
			}
		return false;
	}

	private static String membership(ExecType execType, FederatedOutput output) {
		return execType.name() + '/' + output.name();
	}

	private static List<PlacementState> legalStates(PlacementAnalysis analysis, CompiledHopKey key,
		NeutralPlacementGraph.Node node) {
		Set<PlacementState> legal = new TreeSet<>();
		for(PlacementState state : node.legalAlternatives())
			if(preSolveLegal(analysis, key, state))
				legal.add(state);
		// A relocation target is the required placement of its consumer, not an additional
		// placement alternative of the source value. Importing the target here conflated the
		// consumer execution layout, the uploaded-input layout and the worker-pool anchor, and
		// admitted states that the source node/oracle never published (notably FED/LOUT COL on
		// a forwarded ROW TRead). Relocations remain explicit auxiliary obligations below.
		if(legal.isEmpty())
			throw new IllegalArgumentException("Neutral decision has no pre-solve legal state: " + key);
		return List.copyOf(legal);
	}

	private static boolean preSolveLegal(PlacementAnalysis analysis, CompiledHopKey consumerKey,
		PlacementState state) {
		if(state.execType() != ExecType.FED)
			return true;
		Hop consumer = analysis.hop(consumerKey).orElseThrow(() ->
			new IllegalArgumentException("MINST_CONSUMER_HOP_UNPROVEN"));
		List<CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumerKey).toList();
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == consumerKey
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
				emission.emissionState().placementState() == state))
			.anyMatch(fact -> edges.stream().allMatch(edge -> {
				if(analysis.requireExactCompiledInputEdge(edge.producer(), edge.consumer(),
					edge.inputPosition()) != edge)
					throw new IllegalArgumentException("MINST_COMPILED_EDGE_IDENTITY_UNPROVEN");
				if(edge.inputPosition() >= consumer.getInput().size()
					|| edge.inputPosition() >= fact.key().orderedInputs().size())
					throw new IllegalArgumentException("MINST_INPUT_POSITION_UNPROVEN");
				CandidateInputState required = fact.key().orderedInputs().get(edge.inputPosition());
				if(!required.present())
					return true;
				Hop input = consumer.getInput().get(edge.inputPosition());
				NeutralPlacementGraph.Node inputNode = analysis.graph().node(edge.producer()).orElseThrow();
				boolean direct = inputNode.legalAlternatives().stream().anyMatch(candidate ->
					candidate.output() == FederatedOutput.FOUT
						&& candidate.fType() == required.fType()) || isPersistentRead(input);
				boolean relocated = analysis.graph().relocationActions().stream().anyMatch(action ->
					action.key().sourceValueVersion() == inputNode.valueVersion()
						&& action.key().targetPlacement() == state
						&& action.key().materializationFType() == required.fType()
						&& action.obligations().stream().anyMatch(obligation ->
							obligation.consumer() == consumerKey
								&& obligation.inputPosition() == edge.inputPosition()));
				return direct || relocated;
			}));
	}

	private static void addDecisionEdges(PlacementAnalysis analysis, DecisionFact decision,
		List<MembershipRepresentative> representatives,
		List<RepresentativePreference> preferences, int workers,
		Map<String,List<OccurrenceProfile>> occurrenceProfiles, EdgeAccumulator edges) {
		Hop hop = analysis.hop(decision.key()).orElseThrow();
		boolean cp = hasExec(decision, ExecType.CP);
		boolean fed = hasExec(decision, ExecType.FED);
		boolean lout = hasOutput(decision, FederatedOutput.LOUT);
		boolean fout = hasOutput(decision, FederatedOutput.FOUT);
		boolean fedLout = hasState(decision, ExecType.FED, FederatedOutput.LOUT);
		boolean cpFout = hasState(decision, ExecType.CP, FederatedOutput.FOUT);
		SelectedFedAuthority selectedFed = fed
			? selectedFedAuthority(analysis, decision, representatives, preferences) : null;
		MembershipRepresentative executionRepresentative = selectedFed == null
			? null : selectedFed.executionRepresentative();
		CandidateEmissionFact selectedFedEmission = selectedFed == null
			? null : selectedFed.normalizedEmissionOrNull();
		boolean derivedFedFout = selectedFed != null && selectedFed.derivedFedFout();

		double execWeight = executionWeight(occurrenceProfiles, decision.key());
		double base = cpUnaryCost(hop, execWeight);
		FType executionFType = !fed ? null : selectedFedEmission == null
			? executionRepresentative.state().fType() : selectedFedEmission.executionFType();
		if(fed && executionFType == null)
			executionFType = requireExactExecLayoutType(decision, representatives, ExecType.FED);
		FType materializationFType = derivedFedFout
			? selectedFed.outputRepresentative().state().fType()
			: !cpFout ? null : requireExactMembershipLayoutType(
				decision, representatives, ExecType.CP, FederatedOutput.FOUT);
		if(derivedFedFout && fedLout && requireExactMembershipLayoutType(decision, representatives,
			ExecType.FED, FederatedOutput.LOUT) != executionFType)
			throw new IllegalArgumentException("MINST_DERIVED_EXECUTION_LAYOUT_MISMATCH|key="
				+ decision.key().normalizedSignature());
		if(derivedFedFout && cpFout && requireExactMembershipLayoutType(decision, representatives,
			ExecType.CP, FederatedOutput.FOUT) != materializationFType)
			throw new IllegalArgumentException("MINST_DERIVED_MATERIALIZATION_LAYOUT_MISMATCH|key="
				+ decision.key().normalizedSignature());
		boolean federatedSource = hop instanceof DataOp
			&& ((DataOp)hop).getOp() == OpOpData.FEDERATED;
		// A federated DataOp defines a FederationMap. Its local_matrix/address/range
		// operands are construction parameters, not ordinary FED instruction inputs
		// that require planner-generated CP->FOUT relocation.
		List<FType> inputFTypes = fed && !federatedSource
			? exactFedInputTypes(analysis, hop, decision.key(), executionRepresentative)
			: List.of();
		double outputBytes = effectiveOutputBytes(analysis, decision.key(), hop);
		double uploadBytes = effectiveUploadBytes(analysis, decision.key(), hop);
		FedCostProjection fedProjection = fed
			? fedCostProjection(hop, inputFTypes, executionFType, workers, execWeight,
				base, outputBytes, uploadBytes)
			: FedCostProjection.none();
		double fedCost = fedProjection.fedUnaryCost();
		double resultDownload = fedProjection.resultDownloadCost();
		double resultUpload = !derivedFedFout && !cpFout ? 0.0 : requireCost(execWeight
			* (FederatedCostModel.computeUploadNetworkCost(uploadBytes, materializationFType, workers)
				+ FederatedCostModel.computeLocalToFedForwardingPenalty(materializationFType, workers)),
			"MINST_RESULT_UPLOAD_COST_UNPROVEN");
		edges.add(SOURCE, decision.computeNodeId(), cp ? base : HARD_LEGALITY,
			cp ? ContributionKind.CP_UNARY : ContributionKind.HARD_EXEC,
			decision.key(), null, -1, cp ? "neutral-cp-unary" : "pre-solve-cp-illegal");
		edges.add(decision.computeNodeId(), SINK, fed ? fedCost : HARD_LEGALITY,
			fed ? ContributionKind.FED_UNARY : ContributionKind.HARD_EXEC,
			decision.key(), null, -1, fed ? "neutral-fed-unary" : "pre-solve-fed-illegal");
		if(derivedFedFout)
			edges.add(decision.computeNodeId(), SINK, resultDownload,
				ContributionKind.DOWNLOAD, decision.key(), null, -1,
				"derived-fed-fout-execution-download");
		if(!lout)
			edges.add(SOURCE, decision.placementNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1, "pre-solve-lout-illegal");
		if(!fout)
			edges.add(decision.placementNodeId(), SINK, HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1, "pre-solve-fout-illegal");
		else if(derivedFedFout)
			edges.add(decision.placementNodeId(), SINK, resultUpload,
				ContributionKind.UPLOAD, decision.key(), null, -1,
				"derived-fed-fout-materialization-upload");

		if(!fedLout)
			edges.add(decision.computeNodeId(), decision.placementNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1,
				"pre-solve-fed-lout-illegal");
		else if(!derivedFedFout) {
			edges.add(decision.computeNodeId(), decision.placementNodeId(), resultDownload,
				ContributionKind.DOWNLOAD, decision.key(), null, -1, "native-fed-lout-download");
		}
		if(!cpFout)
			edges.add(decision.placementNodeId(), decision.computeNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1,
				"pre-solve-cp-fout-illegal");
		else if(!derivedFedFout) {
			edges.add(decision.placementNodeId(), decision.computeNodeId(), resultUpload,
				ContributionKind.UPLOAD, decision.key(), null, -1, "native-cp-fout-upload");
		}
	}

	private static FedCostProjection fedCostProjection(PlacementAnalysis analysis,
		CompiledHopKey key, Hop hop, List<FType> inputFTypes, FType executionFType,
		int workers, double executionWeight) {
		double base = cpUnaryCost(hop, executionWeight);
		return fedCostProjection(hop, inputFTypes, executionFType, workers, executionWeight,
			base, effectiveOutputBytes(analysis, key, hop), effectiveUploadBytes(analysis, key, hop));
	}

	/** Shared exact FED arm cost used both by cut edges and by safe internal-arm reduction. */
	private static FedCostProjection fedCostProjection(Hop hop, List<FType> inputFTypes,
		FType executionFType, int workers, double executionWeight, double base,
		double outputBytes, double uploadBytes) {
		if(executionFType == null)
			throw new IllegalArgumentException("MINST_FED_EXECUTION_LAYOUT_UNPROVEN");
		boolean federatedSource = hop instanceof DataOp
			&& ((DataOp)hop).getOp() == OpOpData.FEDERATED;
		boolean broadcastOnlyFedCompute = !federatedSource
			&& broadcastOnlyMatrixInputs(hop, inputFTypes);
		double fedCompute = FederatedCostModel.computeFederatedComputeCost(
			hop, base, workers, broadcastOnlyFedCompute);
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
				unitLocalCost(hop), outputBytes, workers);
		double fedInputPreparation = executionWeight * mixed.getInputPreparationCost();
		double singleWorkerPenalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(
			hop, executionWeight, workers);
		double fedCost = requireCost(fedCompute + fedCoordination + fedInstructionLatency
			+ fedInputPreparation + singleWorkerPenalty, "MINST_FED_COST_UNPROVEN");

		double resultDownloadUnit = FederatedCostModel.computeDownloadNetworkCost(uploadBytes);
		if(!(hop instanceof DataOp)) {
			resultDownloadUnit = FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				hop, executionFType, outputBytes, workers, resultDownloadUnit);
			resultDownloadUnit = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				hop, executionFType, outputBytes, workers, resultDownloadUnit);
			if(mixed.hasCoordinatorPhase())
				resultDownloadUnit = mixed.getCoordinatorPhaseCost();
		}
		else if(((DataOp)hop).getOp() == OpOpData.TRANSIENTWRITE)
			resultDownloadUnit = 0.0;
		double resultDownload = requireCost(executionWeight * resultDownloadUnit,
			"MINST_RESULT_DOWNLOAD_COST_UNPROVEN");
		return new FedCostProjection(fedCost, resultDownload);
	}

	private record FedCostProjection(double fedUnaryCost, double resultDownloadCost) {
		private static FedCostProjection none() {
			return new FedCostProjection(0.0, 0.0);
		}

		private double fedLoutCost() {
			return requireCost(fedUnaryCost + resultDownloadCost,
				"MINST_FED_LOUT_COST_UNPROVEN");
		}
	}

	static SelectedFedAuthority selectedFedAuthority(PlacementAnalysis analysis,
		DecisionFact decision,
		List<MembershipRepresentative> representatives,
		List<RepresentativePreference> preferences) {
		RepresentativePreference preferred = preferences.stream()
			.filter(preference -> preference.decisionKey() == decision.key()
				&& preference.execType() == ExecType.FED)
			.findFirst().orElse(null);
		if(preferred != null) {
			MembershipRepresentative exact = exactMembershipRepresentative(decision,
				representatives, preferred.execType(), preferred.output());
			if(exact == null || exact.state() != preferred.state()
				|| !exact.orderedInputs().equals(preferred.orderedInputs())
				|| exact.candidateRuleFactOrNull() != preferred.candidateRuleFact()
				|| exact.candidateEmissionFactOrNull() != preferred.candidateEmissionFact())
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_NOT_RETAINED|key="
					+ decision.key().normalizedSignature());
			CandidateEmissionFact emission = exactCandidateEmissionFact(exact);
			return new SelectedFedAuthority(exact, exact,
				emission.emissionState().derivedFedFout(), emission, null);
		}
		MembershipRepresentative fout = exactMembershipRepresentative(decision, representatives,
			ExecType.FED, FederatedOutput.FOUT);
		if(fout == null) {
			MembershipRepresentative lout = Objects.requireNonNull(exactMembershipRepresentative(decision,
				representatives, ExecType.FED, FederatedOutput.LOUT),
				"MINST_FED_MEMBERSHIP_REPRESENTATIVE_MISSING");
			return new SelectedFedAuthority(lout, lout, false,
				lout.candidateRuleFactOrNull() == null ? null : exactCandidateEmissionFact(lout), null);
		}
		if(fout.authorityKind() == MembershipAuthorityKind.CAPTURED_RULE) {
			CandidateEmissionFact emission = exactCandidateEmissionFact(fout);
			return new SelectedFedAuthority(fout, fout,
				emission.emissionState().derivedFedFout(), emission, null);
		}
		if(fout.authorityKind() == MembershipAuthorityKind.RELOCATION_SOURCE) {
			SelectedFedAuthority captured = capturedDerivedFoutAuthority(
				analysis, decision, representatives, fout);
			if(captured != null)
				return captured;
			MembershipRepresentative lout = exactMembershipRepresentative(decision, representatives,
				ExecType.FED, FederatedOutput.LOUT);
			if(lout == null || lout.authorityKind() != MembershipAuthorityKind.CAPTURED_RULE)
				throw new IllegalArgumentException("MINST_EXACT_DERIVED_FOUT_EXECUTION_AUTHORITY_MISSING|key="
					+ decision.key().normalizedSignature() + "|representatives=" + representatives.stream()
						.filter(representative -> representative.decisionKey() == decision.key())
						.map(representative -> representative.authorityKind() + "/"
							+ representative.state().normalizedSignature() + "/inputs="
							+ representative.orderedInputs()).toList() + "|emissions="
					+ analysis.candidateRuleFacts().orderedFacts().stream()
						.filter(fact -> fact.key().parentOccurrence() == decision.key())
						.flatMap(fact -> fact.allowedEmissionFacts().stream().map(emission ->
							fact.key().orderedInputs() + "/" + emission.normalizedSignature()))
						.toList());
			CandidateEmissionFact execution = exactCandidateEmissionFact(lout);
			NeutralPlacementGraph.RelocationAction action = fout.relocationActionOrNull();
			NeutralPlacementGraph.Node node = analysis.graph().node(decision.key()).orElseThrow();
			List<CandidateEmissionFact> normalized = lout.candidateRuleFactOrNull().allowedEmissionFacts().stream()
				.filter(emission -> emission.emissionState().placementState() == fout.state()
					&& emission.emissionState().derivedFedFout()
					&& emission.executionFType() == execution.executionFType())
				.toList();
			if(action == null || action.key().sourceValueVersion() != node.valueVersion()
				|| action.key().targetPlacement() != fout.state()
				|| execution.emissionState().placementState() != lout.state()
				|| execution.emissionState().derivedFedFout() || normalized.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_DERIVED_FOUT_AUTHORITY_IDENTITY_MISMATCH|key="
					+ decision.key().normalizedSignature());
			return new SelectedFedAuthority(lout, fout, true, normalized.get(0), action);
		}
		return new SelectedFedAuthority(fout, fout, false, null, null);
	}

	private static SelectedFedAuthority capturedDerivedFoutAuthority(PlacementAnalysis analysis,
		DecisionFact decision, List<MembershipRepresentative> representatives,
		MembershipRepresentative outputRepresentative) {
		NeutralPlacementGraph.RelocationAction action = outputRepresentative.relocationActionOrNull();
		NeutralPlacementGraph.Node node = analysis.graph().node(decision.key()).orElseThrow();
		if(action == null || action.key().sourceValueVersion() != node.valueVersion()
			|| action.key().targetPlacement() != outputRepresentative.state())
			return null;
		record DerivedCandidate(CandidateRuleFact rule, CandidateEmissionFact emission) { }
		List<DerivedCandidate> candidates = new ArrayList<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.key().parentOccurrence() != decision.key()
				|| fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(emission.emissionState().placementState() == outputRepresentative.state()
					&& emission.executionFType() != null)
					candidates.add(new DerivedCandidate(fact, emission));
		}
		if(candidates.size() != 1)
			return null;
		DerivedCandidate selected = candidates.get(0);
		List<MembershipInputAuthorityFact> inputAuthorities = new ArrayList<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.consumer() != decision.key())
				continue;
			if(edge.inputPosition() < 0 || edge.inputPosition() >= selected.rule().key().orderedInputs().size())
				return null;
			CandidateInputState expected = selected.rule().key().orderedInputs().get(edge.inputPosition());
			if(expected.present()) {
				FType direct = exactInputAuthorityType(analysis, edge.producer());
				if(direct != null) {
					if(direct != expected.fType())
						return null;
					continue;
				}
				List<MembershipRepresentative> producers = representatives.stream()
					.filter(representative -> representative.decisionKey() == edge.producer()
						&& representative.output() == FederatedOutput.FOUT
						&& representative.state().fType() == expected.fType())
					.toList();
				if(producers.size() != 1)
					return null;
				MembershipRepresentative producer = producers.get(0);
				inputAuthorities.add(new MembershipInputAuthorityFact(edge, edge.inputPosition(), producer,
					membershipInputAuthoritySignature(edge, producer)));
			}
			else if(!isNativeLocalInput(selected.rule(), selected.emission(),
				outputRepresentative.state(), edge.inputPosition()))
				return null;
		}
		MembershipRepresentative captured = new MembershipRepresentative(decision.key(), ExecType.FED,
			FederatedOutput.FOUT, outputRepresentative.state(), MembershipAuthorityKind.CAPTURED_RULE,
			null, selected.rule(), selected.emission(), selected.rule().key().orderedInputs(), inputAuthorities,
			capturedInvocationEvidence(analysis, decision.key()), null, null);
		boolean derived = selected.emission().emissionState().derivedFedFout();
		return new SelectedFedAuthority(captured, captured, derived, selected.emission(),
			derived ? action : null);
	}

	static record SelectedFedAuthority(MembershipRepresentative executionRepresentative,
		MembershipRepresentative outputRepresentative, boolean derivedFedFout,
		CandidateEmissionFact normalizedEmissionOrNull,
		NeutralPlacementGraph.RelocationAction outputMaterializationActionOrNull) {
		SelectedFedAuthority {
			Objects.requireNonNull(executionRepresentative, "executionRepresentative");
			Objects.requireNonNull(outputRepresentative, "outputRepresentative");
			if(executionRepresentative.decisionKey() != outputRepresentative.decisionKey()
				|| executionRepresentative.execType() != ExecType.FED
				|| outputRepresentative.execType() != ExecType.FED
				|| derivedFedFout && (outputRepresentative.output() != FederatedOutput.FOUT
					|| normalizedEmissionOrNull == null
					|| normalizedEmissionOrNull.emissionState().placementState() != outputRepresentative.state()
					|| !normalizedEmissionOrNull.emissionState().derivedFedFout())
				|| outputMaterializationActionOrNull != null
					&& outputMaterializationActionOrNull.key().targetPlacement() != outputRepresentative.state())
				throw new IllegalArgumentException("MINST_EXACT_SELECTED_FED_AUTHORITY_MISMATCH");
		}
	}

	private static List<FType> exactFedInputTypes(PlacementAnalysis analysis, Hop hop,
		CompiledHopKey consumer, MembershipRepresentative representative) {
		List<FType> result = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			CandidateInputState state = position < representative.orderedInputs().size()
				? representative.orderedInputs().get(position) : null;
			if(state != null && state.present()) {
				result.add(state.fType());
				continue;
			}
			Hop input = hop.getInput(position);
			if(input == null || input.getDataType() == null || !input.getDataType().isMatrix()) {
				result.add(null);
				continue;
			}
			if(isNativeLocalInput(representative, position)) {
				// ABSENT_LOCAL is an exact runtime input mode for a native FED arm.
				// Passing null preserves the mixed FED/local preparation cost instead of
				// inventing a planner relocation and a materialization boundary.
				result.add(null);
				continue;
			}
			final int inputPosition = position;
			CompiledInputEdgeFact edge = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(candidate -> candidate.consumer() == consumer
					&& candidate.inputPosition() == inputPosition)
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"MINST_EXACT_LOCAL_INPUT_EDGE_MISSING|consumer="
						+ consumer.normalizedSignature() + "|input=" + inputPosition));
			result.add(exactRelocationMaterializationType(analysis, edge, representative.state()));
		}
		return Collections.unmodifiableList(result);
	}

	private static void addRepresentativePreferenceEdges(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<MembershipRepresentative> representatives,
		List<RepresentativePreference> preferences, EdgeAccumulator edges) {
		for(RepresentativePreference preference : preferences) {
			DecisionFact consumer = decisions.get(preference.decisionKey());
			if(consumer == null)
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_CONSUMER_MISSING");
			MembershipRepresentative representative = exactMembershipRepresentative(consumer,
				representatives, preference.execType(), preference.output());
			if(representative == null || representative.state() != preference.state()
				|| !representative.orderedInputs().equals(preference.orderedInputs())
				|| representative.candidateRuleFactOrNull() != preference.candidateRuleFact()
				|| representative.candidateEmissionFactOrNull() != preference.candidateEmissionFact())
				throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_NOT_RETAINED|key="
					+ diagnosticKey(preference.decisionKey()) + "|state="
					+ preference.state().normalizedSignature() + "|inputs=" + preference.orderedInputs()
					+ "|emission=" + preference.candidateEmissionFact().normalizedSignature()
					+ "|retained=" + (representative == null ? "null"
						: representative.authorityKind() + "/" + representative.state().normalizedSignature()
							+ "/" + representative.orderedInputs() + "/"
							+ candidateEmissionSignatureOrDash(representative)));
			if(preference.execType() == ExecType.FED)
				edges.add(SOURCE, consumer.computeNodeId(), HARD_LEGALITY,
					ContributionKind.HARD_EXEC, preference.decisionKey(), null, -1,
					"exact-row-requires-fed-consumer");
			else
				edges.add(consumer.computeNodeId(), SINK, HARD_LEGALITY,
					ContributionKind.HARD_EXEC, preference.decisionKey(), null, -1,
					"exact-row-requires-cp-consumer");
			if(preference.output() == FederatedOutput.FOUT)
				edges.add(SOURCE, consumer.placementNodeId(), HARD_LEGALITY,
					ContributionKind.HARD_OUTPUT, preference.decisionKey(), null, -1,
					"exact-row-requires-fout-consumer");
			else
				edges.add(consumer.placementNodeId(), SINK, HARD_LEGALITY,
					ContributionKind.HARD_OUTPUT, preference.decisionKey(), null, -1,
					"exact-row-requires-lout-consumer");
			for(CompiledInputEdgeFact inputEdge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(inputEdge.consumer() != preference.decisionKey()
					|| inputEdge.inputPosition() < 0
					|| inputEdge.inputPosition() >= preference.orderedInputs().size())
					continue;
				DecisionFact producer = decisions.get(inputEdge.producer());
				if(producer == null)
					throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_PRODUCER_MISSING");
				CandidateInputState expected = preference.orderedInputs().get(inputEdge.inputPosition());
				if(expected.present()) {
					// PRESENT is satisfied either by a compatible direct FOUT or by the exact
					// relocation receipt modeled in the upload auxiliary group. Do not force
					// the producer placement/execution and silently discard the relocation arm.
					continue;
				}
				if(isNativeLocalInput(representative, inputEdge.inputPosition()))
					edges.add(producer.placementNodeId(), SINK, HARD_LEGALITY,
						ContributionKind.HARD_OUTPUT, inputEdge.producer(), inputEdge.consumer(),
						inputEdge.inputPosition(), "exact-native-local-input-requires-lout");
				else
					throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_ABSENT_NOT_NATIVE");
			}
		}
	}

	private static void addPresentInputAuthorityEdges(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<MembershipRepresentative> representatives,
		List<RepresentativePreference> preferences, EdgeAccumulator edges) {
		for(DecisionFact consumer : decisions.values().stream()
			.sorted(Comparator.comparing(decision -> decision.key().normalizedSignature())).toList()) {
			if(!hasExec(consumer, ExecType.FED))
				continue;
			MembershipRepresentative representative = exactFedRepresentative(analysis, consumer,
				representatives, preferenceFor(preferences, consumer.key()));
			for(MembershipInputAuthorityFact authority : representative.inputAuthorityFacts()) {
				CandidateInputState expected = exactCandidateInput(representative, authority.inputEdge());
				if(expected == null || !expected.present()
					|| authority.producerRepresentative().output() != FederatedOutput.FOUT
					|| authority.producerRepresentative().state().fType() != expected.fType())
					throw new IllegalArgumentException("MINST_EXACT_PRESENT_INPUT_AUTHORITY_MISMATCH|consumer="
						+ consumer.key().normalizedSignature() + "|input=" + authority.inputPosition());
				DecisionFact producer = decisions.get(authority.inputEdge().producer());
				if(producer == null)
					throw new IllegalArgumentException("MINST_EXACT_PRESENT_INPUT_PRODUCER_MISSING");
				// PRESENT may be supplied by this exact producer FOUT or by the graph-owned
				// relocation action emitted for the same consumer/input/target.  Only force the
				// direct FOUT arm when no such alternative exists; deriveGroups prices the exact
				// relocation alternative and transferAuthorities records its obligation.
				if(!relocationAuthorityForPresentInput(analysis, authority.inputEdge(),
					representative.state(), expected.fType()))
					edges.add(consumer.computeNodeId(), producer.placementNodeId(), HARD_LEGALITY,
						ContributionKind.HARD_UPLOAD_REUSE, consumer.key(), producer.key(),
						authority.inputPosition(), "exact-present-input-requires-producer-fout");
			}
		}
	}

	private static FType exactRelocationMaterializationType(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge, PlacementState consumerState) {
		NeutralPlacementGraph.Node producer = analysis.graph().node(edge.producer()).orElseThrow();
		List<NeutralPlacementGraph.RelocationAction> actions = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion() == producer.valueVersion()
				&& action.key().targetPlacement().equals(consumerState))
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == edge.consumer()
					&& obligation.inputPosition() == edge.inputPosition()
					&& obligation.sourceValueVersion() == producer.valueVersion()
					&& obligation.requiredPlacement().equals(consumerState)))
			.toList();
		if(actions.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_LOCAL_INPUT_RELOCATION_"
				+ (actions.isEmpty() ? "MISSING" : "AMBIGUOUS") + "|producer="
				+ edge.producer().normalizedSignature() + "|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition()
				+ "|state=" + consumerState.normalizedSignature());
		return exactRelocationMaterializationType(analysis, edge, actions.get(0));
	}

	private static FType exactRelocationMaterializationType(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge, NeutralPlacementGraph.RelocationAction action) {
		FType type = action.key().materializationFType();
		boolean exactCandidateProof = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == edge.consumer()
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE
				&& edge.inputPosition() < fact.key().orderedInputs().size())
			.filter(fact -> fact.key().orderedInputs().get(edge.inputPosition())
				.equals(CandidateInputState.present(type)))
			.anyMatch(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
				emission.emissionState().placementState().equals(action.key().targetPlacement())));
		if(!exactCandidateProof) {
			FType geometryType = PlacementCostSemantics.exactMaterializationFType(
				analysis.shapeFact(edge.producer()).orElseThrow(() -> new IllegalArgumentException(
					"MINST_EXACT_LOCAL_INPUT_SHAPE_MISSING|producer="
						+ edge.producer().normalizedSignature())), action.key().durableAnchor());
			exactCandidateProof = geometryType == type
				&& analysis.candidateRuleFacts().orderedFacts().stream()
					.filter(fact -> fact.key().parentOccurrence() == edge.consumer()
						&& fact.status() == CandidateEvaluationStatus.AVAILABLE
						&& edge.inputPosition() < fact.key().orderedInputs().size())
					.filter(fact -> fact.key().orderedInputs().get(edge.inputPosition())
						.equals(CandidateInputState.absentLocal()))
					.anyMatch(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
						emission.emissionState().placementState().equals(action.key().targetPlacement())));
		}
		if(!exactCandidateProof)
			throw new IllegalArgumentException("MINST_EXACT_LOCAL_INPUT_MATERIALIZATION_UNPROVEN|producer="
				+ edge.producer().normalizedSignature() + "|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition()
				+ "|type=" + type + "|target=" + action.key().targetPlacement().normalizedSignature());
		return type;
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

	private static double unitLocalCost(Hop hop) {
		if(hop instanceof DataOp) {
			OpOpData op = ((DataOp)hop).getOp();
			return op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE
				? 0.0 : FederatedCostModel.computeOpCostWithFallback(hop);
		}
		return FederatedCostModel.computeLocalIndexingCostWithFallback(hop,
			FederatedCostModel.computeOpCostWithFallback(hop));
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

	private static void addNeutralConstraintEdges(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<MembershipRepresentative> representatives, EdgeAccumulator edges) {
		for(Constraint constraint : analysis.graph().constraints()) {
			DecisionFact left = decisions.get(constraint.left());
			DecisionFact right = decisions.get(constraint.right());
			if(left == null || right == null)
				continue;
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT) {
				validateSamePlacementMemberships(left, right, representatives, constraint);
				addHardEquality(edges, left.computeNodeId(), right.computeNodeId(),
					ContributionKind.HARD_EXEC, left.key(), right.key(), constraint.inputPosition(),
					"neutral-same-placement-exec");
				addHardEquality(edges, left.placementNodeId(), right.placementNodeId(),
					ContributionKind.HARD_OUTPUT, left.key(), right.key(), constraint.inputPosition(),
					"neutral-same-placement-output");
			}
			else if(constraint.kind() == ConstraintKind.SAME_FTYPE) {
				validateFoutTypeCompatibility(left, right, representatives, constraint);
				addHardEquality(edges, left.placementNodeId(), right.placementNodeId(),
					ContributionKind.HARD_OUTPUT, left.key(), right.key(), constraint.inputPosition(),
					"neutral-same-ftype-output");
			}
			else if(constraint.kind() == ConstraintKind.CONJUNCTIVE) {
				if(isMultiDefinitionTransientConstraint(analysis, constraint)) {
					addStrictTransientTupleEquality(left, right, representatives, constraint, edges);
					continue;
				}
				List<FType> rightTypes = exactFoutLayoutTypes(right, representatives);
				List<FType> leftTypes = exactFoutLayoutTypes(left, representatives);
				if(rightTypes.size() > 1 || leftTypes.size() > 1)
					throw new IllegalArgumentException("MINST_CONJUNCTIVE_FTYPE_AMBIGUOUS|constraint="
						+ constraint.normalizedSignature());
				if(!rightTypes.isEmpty() && !leftTypes.contains(rightTypes.get(0)))
					edges.add(right.placementNodeId(), SINK, HARD_LEGALITY,
						ContributionKind.HARD_OUTPUT, right.key(), left.key(), constraint.inputPosition(),
						"neutral-conjunctive-fout-layout-illegal");
				else
					edges.add(right.placementNodeId(), left.placementNodeId(), HARD_LEGALITY,
						ContributionKind.HARD_OUTPUT, right.key(), left.key(), constraint.inputPosition(),
						"neutral-conjunctive-fout-requires-left");
			}
		}
	}

	private static boolean isMultiDefinitionTransientConstraint(PlacementAnalysis analysis,
		Constraint constraint) {
		return analysis.graph().node(constraint.left()).orElseThrow().kind() == NodeKind.TRANSIENT_WRITE
			&& analysis.graph().node(constraint.right()).orElseThrow().kind() == NodeKind.TRANSIENT_READ;
	}

	/**
	 * A reaching TWrite/TRead pair is one logical value.  It therefore has the
	 * top-level transient domain {CP/LOUT,FED/FOUT}, not merely the implication
	 * "read FOUT requires writer FOUT" used by ordinary conjunctive boundaries.
	 * The four equality edges preserve the complete candidate space while making
	 * every cross execution/output pair pay the certified hard capacity.
	 */
	private static void addStrictTransientTupleEquality(DecisionFact write, DecisionFact read,
		List<MembershipRepresentative> representatives, Constraint constraint, EdgeAccumulator edges) {
		validateTransientTupleDomain(write);
		validateTransientTupleDomain(read);
		List<FType> writeTypes = exactFoutLayoutTypes(write, representatives);
		List<FType> readTypes = exactFoutLayoutTypes(read, representatives);
		if(writeTypes.size() > 1 || readTypes.size() > 1)
			throw new IllegalArgumentException("MINST_TRANSIENT_FTYPE_AMBIGUOUS|constraint="
				+ constraint.normalizedSignature());
		addHardEquality(edges, write.computeNodeId(), read.computeNodeId(),
			ContributionKind.HARD_EXEC, write.key(), read.key(), constraint.inputPosition(),
			"neutral-transient-tuple-exec-equality");
		addHardEquality(edges, write.placementNodeId(), read.placementNodeId(),
			ContributionKind.HARD_OUTPUT, write.key(), read.key(), constraint.inputPosition(),
			"neutral-transient-tuple-placement-equality");
		if(!writeTypes.isEmpty() && !readTypes.isEmpty() && writeTypes.get(0) != readTypes.get(0)) {
			// Exact FType mismatch leaves CP/LOUT as the only common transient tuple.
			edges.add(write.computeNodeId(), SINK, HARD_LEGALITY, ContributionKind.HARD_EXEC,
				write.key(), read.key(), constraint.inputPosition(),
				"neutral-transient-tuple-write-ftype-mismatch");
			edges.add(read.computeNodeId(), SINK, HARD_LEGALITY, ContributionKind.HARD_EXEC,
				read.key(), write.key(), constraint.inputPosition(),
				"neutral-transient-tuple-read-ftype-mismatch");
		}
	}

	private static void validateTransientTupleDomain(DecisionFact decision) {
		for(PlacementState state : decision.legalStatesInCanonicalOrder())
			if(!((state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
				|| (state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)))
				throw new IllegalArgumentException("MINST_TRANSIENT_TUPLE_DOMAIN_ILLEGAL|decision="
					+ diagnosticKey(decision.key()) + "|state=" + state.normalizedSignature());
	}

	private static void addLogicalTransientInputEdges(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<MembershipRepresentative> representatives,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs, EdgeAccumulator edges) {
		for(LogicalTransientInputFact fact : analysis.logicalTransientInputsInCanonicalOrder()) {
			if(analysis.requireExactLogicalTransientInput(fact.sourceWrite(), fact.targetRead(),
				fact.logicalPosition()) != fact)
				throw new IllegalArgumentException("MINST_LOGICAL_TRANSIENT_INPUT_FOREIGN");
			// Legacy MinST deliberately resolved function parameters against the caller's
			// concrete argument before applying ordinary TWrite/TRead consistency.  The
			// compiler-generated formal binding is transparent, so tying its TWrite to all
			// downstream reads would invent one amortized materialization point and collapse
			// the loop-wide FED alternatives that existed before shared preprocessing.
			if(effectiveFunctionInputs.stream().anyMatch(input -> input.forwardedAuthority() == fact))
				continue;
			DecisionFact source = decisions.get(fact.sourceWrite());
			DecisionFact read = decisions.get(fact.targetRead());
			if(source == null || read == null)
				continue;
			validateSamePlacementMemberships(source, read, representatives, null);
			addHardEquality(edges, source.computeNodeId(), read.computeNodeId(),
				ContributionKind.HARD_EXEC, source.key(), read.key(), fact.logicalPosition(),
				"logical-transient-exec-equality");
			addHardEquality(edges, source.placementNodeId(), read.placementNodeId(),
				ContributionKind.HARD_OUTPUT, source.key(), read.key(), fact.logicalPosition(),
				"logical-transient-placement-equality");
		}
	}

	private static List<EffectiveLogicalFunctionInput> effectiveLogicalFunctionInputs(
		PlacementAnalysis analysis) {
		List<EffectiveLogicalFunctionInput> result = new ArrayList<>();
		List<LogicalFunctionInputFact> direct = analysis.logicalFunctionInputsInCanonicalOrder();
		for(LogicalFunctionInputFact fact : direct) {
			if(analysis.requireExactLogicalFunctionInput(fact.sourceArgument(), fact.targetRead(),
				fact.logicalPosition()) != fact)
				throw new IllegalArgumentException("MINST_LOGICAL_FUNCTION_INPUT_FOREIGN");
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
				throw new IllegalArgumentException("MINST_FUNCTION_INPUT_BINDING_AMBIGUOUS|source="
					+ transientFact.sourceWrite().normalizedSignature());
			Constraint binding = bindings.get(0);
			List<LogicalFunctionInputFact> authorities = direct.stream()
				.filter(fact -> fact.targetRead() == binding.left()).toList();
			if(authorities.isEmpty())
				throw new IllegalArgumentException("MINST_FUNCTION_INPUT_FORWARD_AUTHORITY_MISSING|source="
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
					throw new IllegalArgumentException("MINST_FUNCTION_INPUT_FORWARD_LAYOUT_MISMATCH|source="
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

	private static void addHardEquality(EdgeAccumulator edges, long left, long right,
		ContributionKind kind, CompiledHopKey leftKey, CompiledHopKey rightKey,
		int inputPosition, String provenance) {
		edges.add(left, right, HARD_LEGALITY, kind, leftKey, rightKey, inputPosition, provenance);
		edges.add(right, left, HARD_LEGALITY, kind, rightKey, leftKey, inputPosition, provenance);
	}

	private static void validateSamePlacementMemberships(DecisionFact left, DecisionFact right,
		List<MembershipRepresentative> representatives, Constraint constraint) {
		for(ExecType exec : List.of(ExecType.CP, ExecType.FED))
			for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
				MembershipRepresentative leftRepresentative = exactMembershipRepresentative(
					left, representatives, exec, output);
				MembershipRepresentative rightRepresentative = exactMembershipRepresentative(
					right, representatives, exec, output);
				if((leftRepresentative == null) != (rightRepresentative == null)
					|| leftRepresentative != null && !leftRepresentative.state().equals(rightRepresentative.state()))
					throw new IllegalArgumentException("MINST_SAME_PLACEMENT_MEMBERSHIP_MISMATCH|left="
						+ left.key().normalizedSignature() + "|right=" + right.key().normalizedSignature()
						+ "|membership=" + membership(exec, output) + "|constraint="
						+ (constraint == null ? "logical-transient" : constraint.normalizedSignature())
						+ "|leftState=" + (leftRepresentative == null ? "-"
							: leftRepresentative.state().normalizedSignature())
						+ "|rightState=" + (rightRepresentative == null ? "-"
							: rightRepresentative.state().normalizedSignature()));
			}
	}

	private static void validateFoutTypeCompatibility(DecisionFact left, DecisionFact right,
		List<MembershipRepresentative> representatives, Constraint constraint) {
		List<FType> leftTypes = exactFoutLayoutTypes(left, representatives);
		List<FType> rightTypes = exactFoutLayoutTypes(right, representatives);
		if(leftTypes.size() > 1 || rightTypes.size() > 1 || !leftTypes.equals(rightTypes))
			throw new IllegalArgumentException("MINST_SAME_FTYPE_MEMBERSHIP_MISMATCH|constraint="
				+ constraint.normalizedSignature() + "|left=" + leftTypes + "|right=" + rightTypes);
	}

	/**
	 * Restore the exact caller-argument edge that legacy MinST received as a temporary physical
	 * child of a formal TRead. Shared analysis deliberately keeps that relation logical, so MinST
	 * must encode the same semantics explicitly: a federated formal requires a same-layout FOUT
	 * caller value, while a local formal downloads a selected FOUT caller value once per call.
	 */
	private static void addLogicalFunctionInputEdges(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey, DecisionFact> decisions,
		List<MembershipRepresentative> representatives,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs,
		Map<String,List<OccurrenceProfile>> occurrenceProfiles, EdgeAccumulator edges) {
		for(EffectiveLogicalFunctionInput input : effectiveFunctionInputs) {
			LogicalFunctionInputFact fact = input.authority();
			DecisionFact source = decisions.get(fact.sourceArgument());
			DecisionFact formal = decisions.get(input.targetRead());
			if(source == null || formal == null)
				continue;
			Hop formalHopForTrace = analysis.hop(formal.key()).orElseThrow();
			if(FederatedPlannerTrace.shouldTrace(formalHopForTrace))
				FederatedPlannerTrace.log(formalHopForTrace, "MinST-ExactFunctionInput",
					"source=" + source.key().normalizedSignature() + ", forwarded="
						+ (input.forwardedAuthority() != null));
			String prefix = input.forwardedAuthority() == null
				? "logical-function" : "logical-function-forwarded";
			List<FType> formalFoutTypes = exactFoutLayoutTypes(formal, representatives);
			FType formalFoutType = formalFoutTypes.size() == 1 ? formalFoutTypes.get(0) : null;
			if(!formalFoutTypes.isEmpty()) {
				if(formalFoutType == null)
					throw new IllegalArgumentException("MINST_LOGICAL_FUNCTION_FORMAL_LAYOUT_AMBIGUOUS|key="
						+ formal.key().normalizedSignature() + "|types=" + formalFoutTypes);
				FType required = formalFoutType;
				boolean compatibleSource = source.legalStatesInCanonicalOrder().stream().anyMatch(state ->
					state.output() == FederatedOutput.FOUT && state.fType() == required);
				if(!compatibleSource)
					edges.add(formal.placementNodeId(), SINK, HARD_LEGALITY,
						ContributionKind.HARD_OUTPUT, formal.key(), source.key(), input.logicalPosition(),
						prefix + "-fout-source-layout-illegal");
				else
					edges.add(formal.placementNodeId(), source.placementNodeId(), HARD_LEGALITY,
						ContributionKind.HARD_OUTPUT, formal.key(), source.key(), input.logicalPosition(),
						prefix + "-fout-requires-source-fout");
			}
			List<FType> sourceFoutTypes = exactFoutLayoutTypes(source, representatives);
			if(hasExec(formal, ExecType.CP) && !sourceFoutTypes.isEmpty()) {
				Hop sourceHop = analysis.hop(source.key()).orElseThrow();
				Hop formalHop = analysis.hop(formal.key()).orElseThrow();
				// A local formal TRead materializes the complete logical matrix at the
				// coordinator, which erases the caller's federated layout. BROADCAST and
				// partitioned FULL sources are therefore equally legal here and both pay
				// the same full-payload download. Do not collapse their exact memberships
				// to an invented single FType merely to price this layout-independent edge.
				double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
					formalHop, sourceHop);
				double callWeight = logicalFunctionCallWeight(occurrenceProfiles, fact);
				double download = requireCost(callWeight
					* FederatedCostModel.computeDownloadNetworkCost(bytes),
					"MINST_LOGICAL_FUNCTION_DOWNLOAD_COST_UNPROVEN");
				edges.add(source.placementNodeId(), formal.placementNodeId(), download,
					ContributionKind.DOWNLOAD, source.key(), formal.key(), input.logicalPosition(),
					prefix + "-fout-to-local-download");
				// A function-formal TRead is an alias, not a reusable local materialization.
				// Depending on compiler normalization, the same alias is represented either
				// directly by LogicalFunctionInputFact or through a generated transient binding.
				// Legacy MinST charged every CP consumer at that consumer's loop frequency in
				// both cases, directly against the caller's concrete FOUT authority.
				for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
					if(edge.producer() != formal.key())
						continue;
					DecisionFact consumer = decisions.get(edge.consumer());
					if(consumer == null || !hasExec(consumer, ExecType.CP))
						continue;
					double consumerWeight = forwardingWeight(occurrenceProfiles,
						edge.consumer(), edge.producer());
					double consumerDownload = requireCost(consumerWeight
						* FederatedCostModel.computeDownloadNetworkCost(bytes),
						"MINST_LOGICAL_FUNCTION_CONSUMER_DOWNLOAD_COST_UNPROVEN");
					Hop consumerHopForTrace = analysis.hop(consumer.key()).orElseThrow();
					if(FederatedPlannerTrace.shouldTrace(consumerHopForTrace))
						FederatedPlannerTrace.log(consumerHopForTrace,
							"MinST-ExactFunctionConsumer", "source="
								+ source.key().normalizedSignature() + ", formal="
								+ formal.key().normalizedSignature() + ", weight="
								+ consumerWeight + ", download=" + consumerDownload);
					edges.add(source.placementNodeId(), consumer.computeNodeId(),
						consumerDownload, ContributionKind.DOWNLOAD, source.key(),
						consumer.key(), edge.inputPosition(),
						prefix + "-local-consumer-download");
				}
			}
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

	private static List<FType> exactFoutLayoutTypes(DecisionFact decision,
		List<MembershipRepresentative> representatives) {
		return representatives.stream()
			.filter(representative -> representative.decisionKey() == decision.key()
				&& representative.output() == FederatedOutput.FOUT)
			.map(representative -> representative.state().fType())
			.filter(Objects::nonNull).distinct().sorted(Comparator.comparing(Enum::name)).toList();
	}

	private static double logicalFunctionCallWeight(Map<String,List<OccurrenceProfile>> profiles,
		LogicalFunctionInputFact fact) {
		List<String> paths = fact.boundary().controlRegion().regionPath();
		String expectedBoundary = "input-" + fact.callInputPosition();
		if(paths.size() != 2 || !expectedBoundary.equals(paths.get(1)))
			throw new IllegalArgumentException("MINST_LOGICAL_FUNCTION_BOUNDARY_PATH_UNPROVEN|boundary="
				+ fact.boundary().normalizedSignature() + "|paths=" + paths);
		List<OccurrenceProfile> callProfiles = profiles.get(paths.get(0));
		if(callProfiles == null || callProfiles.isEmpty())
			throw new IllegalArgumentException("MINST_LOGICAL_FUNCTION_CALL_PATH_UNPROVEN|path=" + paths.get(0));
		double total = 0.0;
		for(OccurrenceProfile profile : callProfiles)
			total += profile.networkWeight;
		return requirePositiveWeight(total, "MINST_LOGICAL_FUNCTION_CALL_WEIGHT_UNPROVEN");
	}

	private static List<AuxiliaryGroupFact> deriveGroups(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope, IdentityHashMap<CompiledHopKey, DecisionFact> decisions,
		List<MembershipRepresentative> representatives,
		List<RepresentativePreference> preferences,
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs, int workers,
		Map<String,List<OccurrenceProfile>> occurrenceProfiles, EdgeAccumulator edges) {
		List<AuxiliaryGroupFact> result = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,List<CompiledInputEdgeFact>> edgesByProducer = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(analysis.requireExactCompiledInputEdge(edge.producer(), edge.consumer(),
				edge.inputPosition()) != edge)
				throw new IllegalArgumentException("MINST_COMPILED_EDGE_IDENTITY_UNPROVEN");
			// FunctionOp is a logical call boundary. Its actual/formal transfer is priced
			// by LogicalFunctionInputFact; treating the call node itself as a CP matrix
			// consumer invents an extra download that never exists at runtime.
			if(analysis.graph().node(edge.consumer()).orElseThrow().kind() == NodeKind.FUNCTION_CALL)
				continue;
			if(decisions.containsKey(edge.producer()) && decisions.containsKey(edge.consumer()))
				edgesByProducer.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge);
		}
		long nextAux = -3L;
		for(CompiledHopKey producerKey : orderedScope) {
			DecisionFact producerDecision = decisions.get(producerKey);
			if(producerDecision == null)
				continue;
			Hop producer = analysis.hop(producerKey).orElseThrow();
			if(producer.getDataType() == null || !producer.getDataType().isMatrix())
				continue;
			Map<GroupDemandKey,List<Use>> demands = new LinkedHashMap<>();
			for(CompiledInputEdgeFact edge : edgesByProducer.getOrDefault(producerKey, List.of())) {
				CompiledHopKey consumerKey = edge.consumer();
				DecisionFact consumerDecision = decisions.get(consumerKey);
				RepresentativePreference preferred = preferenceFor(preferences, consumerKey);
				MembershipRepresentative consumerRepresentative = hasExec(consumerDecision, ExecType.FED)
					? exactFedRepresentative(analysis, consumerDecision, representatives, preferred) : null;
				CandidateInputState selectedInput = exactCandidateInput(consumerRepresentative, edge);
				if(hasExec(consumerDecision, ExecType.FED) && canUpload(producer)
					&& selectedInput != null && selectedInput.present()
					&& relocationAuthorityForPresentInput(analysis, edge,
						consumerRepresentative.state(), selectedInput.fType())) {
					FType type = exactRelocationMaterializationType(
						analysis, edge, consumerRepresentative.state());
					BoundaryMode mode = uploadBoundaryMode(analysis, edge);
					demands.computeIfAbsent(new GroupDemandKey(Direction.UPLOAD, type, mode), ignored ->
						new ArrayList<>()).add(new Use(edge, consumerDecision));
				}
				if(hasExec(consumerDecision, ExecType.CP)
					&& hasState(producerDecision, ExecType.FED, FederatedOutput.FOUT)) {
					FType type = requireExactMembershipLayoutType(producerDecision, representatives,
						ExecType.FED, FederatedOutput.FOUT);
					demands.computeIfAbsent(new GroupDemandKey(Direction.DOWNLOAD, type,
						BoundaryMode.ANCHOR_TRANSFER), ignored ->
						new ArrayList<>()).add(new Use(edge, consumerDecision));
				}
			}
			for(Map.Entry<GroupDemandKey,List<Use>> entry : demands.entrySet()) {
				List<Use> uses = entry.getValue().stream()
					.sorted(Comparator.comparing((Use use) -> use.edge.consumer().normalizedSignature())
						.thenComparingInt(use -> use.edge.inputPosition()))
					.toList();
				List<EndpointFact> endpoints = new ArrayList<>(uses.size());
				double bytes = estimatedBytes(analysis, producerKey, producer);
				EffectiveLogicalFunctionInput forwardedInput = entry.getKey().direction == Direction.DOWNLOAD
					? forwardedFunctionInputForTarget(effectiveFunctionInputs, producerKey) : null;
				double transferCost = entry.getKey().direction == Direction.UPLOAD
					? FederatedCostModel.computeUploadNetworkCost(bytes, entry.getKey().type, workers)
						+ FederatedCostModel.computeLocalToFedForwardingPenalty(entry.getKey().type, workers)
					: forwardedInput != null
						? FederatedCostModel.computeDownloadNetworkCost(bytes)
						: FederatedCostModel.computeDownloadNetworkCost(bytes, entry.getKey().type, workers);
				transferCost = requireCost(transferCost, "MINST_GROUP_TRANSFER_COST_UNPROVEN");
				double price = Double.NEGATIVE_INFINITY;
				for(Use use : uses) {
					double demandWeight = forwardedInput == null
						? forwardingWeight(occurrenceProfiles,
							use.edge.consumer(), use.edge.producer())
						: logicalFunctionCallWeight(occurrenceProfiles, forwardedInput.authority());
					double demand = requireCost(demandWeight * transferCost,
						"MINST_GROUP_DEMAND_COST_UNPROVEN");
					endpoints.add(new EndpointFact(use.edge.producer(), use.edge.consumer(), use.edge.inputPosition(),
						use.consumerDecision.computeNodeId(), bits(demand)));
					price = Math.max(price, demand);
				}
				price = requireCost(price, "MINST_GROUP_PRICE_UNPROVEN");
				long aux = nextAux--;
				UploadPriceTarget uploadPriceTarget = entry.getKey().direction == Direction.UPLOAD
					? exactUploadPriceTarget(analysis, producerDecision, representatives,
						entry.getKey().boundaryMode, entry.getKey().type, endpoints)
					: UploadPriceTarget.NOT_APPLICABLE;
				if(uploadPriceTarget == UploadPriceTarget.PRODUCER_FED_FOUT)
					nextAux--;
				AuxiliaryGroupFact group = new AuxiliaryGroupFact(aux, entry.getKey().direction,
					entry.getKey().boundaryMode, producerKey, producerDecision.computeNodeId(),
					producerDecision.placementNodeId(), uploadPriceTarget, entry.getKey().type,
					bits(price), endpoints);
				result.add(group);
				addGroupEdges(analysis, group, edges);
			}
		}
		return List.copyOf(result);
	}

	private static RepresentativePreference preferenceFor(
		List<RepresentativePreference> preferences, CompiledHopKey decisionKey) {
		List<RepresentativePreference> matching = preferences.stream()
			.filter(preference -> preference.decisionKey() == decisionKey).toList();
		if(matching.size() > 1)
			throw new IllegalArgumentException("MINST_EXACT_REPRESENTATIVE_PREFERENCE_DUPLICATE|key="
				+ decisionKey.normalizedSignature());
		return matching.isEmpty() ? null : matching.get(0);
	}

	private static MembershipRepresentative exactFedRepresentative(PlacementAnalysis analysis,
		DecisionFact decision, List<MembershipRepresentative> representatives,
		RepresentativePreference preference) {
		if(preference != null && preference.execType() == ExecType.FED) {
			MembershipRepresentative exact = exactMembershipRepresentative(decision, representatives,
				preference.execType(), preference.output());
			if(exact == null || exact.state() != preference.state()
				|| exact.candidateRuleFactOrNull() != preference.candidateRuleFact()
				|| exact.candidateEmissionFactOrNull() != preference.candidateEmissionFact())
				throw new IllegalArgumentException("MINST_EXACT_PREFERRED_UPLOAD_ROW_NOT_RETAINED");
			return exact;
		}
		return selectedFedAuthority(analysis, decision, representatives, List.of())
			.executionRepresentative();
	}

	private static CandidateInputState exactCandidateInput(MembershipRepresentative representative,
		CompiledInputEdgeFact edge) {
		if(representative == null || representative.execType() != ExecType.FED)
			return null;
		if(edge.inputPosition() < 0 || edge.inputPosition() >= representative.orderedInputs().size())
			throw new IllegalArgumentException("MINST_EXACT_FED_INPUT_ROW_POSITION_MISSING|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition());
		return representative.orderedInputs().get(edge.inputPosition());
	}

	private static BoundaryMode uploadBoundaryMode(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge) {
		NeutralPlacementGraph.Node consumerNode = analysis.graph().node(edge.consumer()).orElseThrow();
		if(consumerNode.kind() != NodeKind.TRANSIENT_WRITE)
			return BoundaryMode.ANCHOR_TRANSFER;
		Hop consumer = analysis.hop(edge.consumer()).orElseThrow(() ->
			new IllegalArgumentException("MINST_TWRITE_HOP_UNPROVEN"));
		Hop producer = analysis.hop(edge.producer()).orElseThrow(() ->
			new IllegalArgumentException("MINST_TWRITE_PRODUCER_UNPROVEN"));
		if(!(consumer instanceof DataOp) || ((DataOp)consumer).getOp() != OpOpData.TRANSIENTWRITE
			|| edge.inputPosition() != 0 || consumer.getInput().size() != 1
			|| consumer.getInput().get(0) != producer)
			throw new IllegalArgumentException("MINST_TWRITE_EDGE_IDENTITY_UNPROVEN|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition());
		return BoundaryMode.TWRITE_METADATA;
	}

	private static EffectiveLogicalFunctionInput forwardedFunctionInputForTarget(
		List<EffectiveLogicalFunctionInput> effectiveFunctionInputs, CompiledHopKey target) {
		List<EffectiveLogicalFunctionInput> matches = effectiveFunctionInputs.stream()
			.filter(input -> input.forwardedAuthority() != null && input.targetRead() == target)
			.toList();
		if(matches.size() > 1)
			throw new IllegalArgumentException("MINST_FUNCTION_INPUT_FORWARD_TARGET_AMBIGUOUS|target="
				+ target.normalizedSignature());
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static double cpUnaryCost(Hop hop, double executionWeight) {
		if(hop instanceof DataOp) {
			OpOpData op = ((DataOp)hop).getOp();
			if(op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE)
				return 0.0;
			return requireCost(executionWeight * FederatedCostModel.computeOpCostWithFallback(hop),
				"MINST_CP_COST_UNPROVEN");
		}
		double unit = FederatedCostModel.computeLocalIndexingCostWithFallback(hop,
			FederatedCostModel.computeOpCostWithFallback(hop));
		return requireCost(executionWeight * unit, "MINST_CP_COST_UNPROVEN");
	}

	private static double executionWeight(Map<String,List<OccurrenceProfile>> profiles,
		CompiledHopKey key) {
		double total = 0.0;
		for(OccurrenceProfile profile : requireOccurrenceProfiles(profiles, key))
			total += profile.networkWeight;
		return requirePositiveWeight(total, "MINST_EXECUTION_WEIGHT_UNPROVEN");
	}

	private static Map<String,List<OccurrenceProfile>> occurrenceProfiles(PlacementAnalysis analysis) {
		analysis.assertProgramStructureUnchanged();
		if(!analysis.hasGuardedFunctionRoots() && analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.anyMatch(hop -> hop instanceof FunctionOp
				&& ((FunctionOp)hop).getFunctionType() == FunctionOp.FunctionType.DML))
			throw new IllegalArgumentException("MINST_GUARDED_FUNCTION_ROOTS_REQUIRED");
		Map<String,List<OccurrenceProfile>> profiles = new LinkedHashMap<>();
		Map<String,List<FunctionCallContext>> functionCalls = new LinkedHashMap<>();
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts = new IdentityHashMap<>();
		indexBlocks(analysis.topLevelStatementBlocks(), "main", 1.0, List.of(), List.of(), List.of(),
			MAIN_OCCURRENCE_CONTEXT,
			profiles, functionCalls, indexedFunctionCallContexts);
		Map<String,Integer> processedCalls = new LinkedHashMap<>();
		boolean advanced;
		do {
			advanced = false;
			for(String functionKey : new ArrayList<>(functionCalls.keySet())) {
				List<FunctionCallContext> calls = functionCalls.get(functionKey);
				int processed = processedCalls.getOrDefault(functionKey, 0);
				FunctionStatementBlock function = analysis.namedFunctionStatementBlocks().get(functionKey);
				if(function == null)
					throw new IllegalArgumentException("MINST_FUNCTION_ROOT_UNPROVEN|function=" + functionKey);
				while(processed < calls.size()) {
					FunctionCallContext call = calls.get(processed++);
					indexBlock(function, "function/" + functionKey, call.networkWeight,
						call.loopContext, call.transTables, Map.of(), call.callStack, call, profiles,
						functionCalls, indexedFunctionCallContexts);
					advanced = true;
				}
				processedCalls.put(functionKey, processed);
			}
		}
		while(advanced);
		if(profiles.isEmpty())
			indexDetachedStraightLineProfiles(analysis, profiles);
		analysis.assertProgramStructureUnchanged();
		Map<String,List<OccurrenceProfile>> frozen = new LinkedHashMap<>();
		profiles.forEach((path, occurrences) -> frozen.put(path, List.copyOf(occurrences)));
		return Collections.unmodifiableMap(frozen);
	}

	private static void indexDetachedStraightLineProfiles(PlacementAnalysis analysis,
		Map<String,List<OccurrenceProfile>> profiles) {
		if(!analysis.topLevelStatementBlocks().isEmpty()
			|| !analysis.namedFunctionStatementBlocks().isEmpty())
			return;
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences()) {
			List<String> paths = occurrence.key().controlRegion().regionPath();
			if(paths.size() != 1 || !paths.get(0).matches("main/\\d+"))
				continue;
			putOccurrenceProfile(profiles, paths.get(0),
				new OccurrenceProfile(1.0, List.of(), MAIN_OCCURRENCE_CONTEXT));
		}
	}

	private static Map<String,List<Hop>> indexBlocks(List<StatementBlock> blocks, String path,
		double networkWeight, List<Pair<Long,Double>> loopContext,
		List<Map<String,List<Hop>>> outerTransTables, List<String> callStack,
		Object occurrenceContext, Map<String,List<OccurrenceProfile>> profiles,
		Map<String,List<FunctionCallContext>> functionCalls,
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts) {
		Map<String,List<Hop>> former = new LinkedHashMap<>();
		for(int index = 0; blocks != null && index < blocks.size(); index++) {
			Map<String,List<Hop>> writes = indexBlock(blocks.get(index), path + '/' + index,
				networkWeight, loopContext, outerTransTables, former, callStack, occurrenceContext, profiles,
				functionCalls, indexedFunctionCallContexts);
			replaceMappings(former, writes);
		}
		return former;
	}

	private static Map<String,List<Hop>> indexBlock(StatementBlock block, String path,
		double networkWeight, List<Pair<Long,Double>> loopContext,
		List<Map<String,List<Hop>>> outerTransTables, Map<String,List<Hop>> formerTransTable,
		List<String> callStack, Object occurrenceContext,
		Map<String,List<OccurrenceProfile>> profiles,
		Map<String,List<FunctionCallContext>> functionCalls,
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts) {
		List<Map<String,List<Hop>>> visibleTransTables = visibleTransTables(
			outerTransTables, formerTransTable);
		Map<String,List<Hop>> headerWrites;
		if(block instanceof ForStatementBlock) {
			double loopWeight = forLoopWeight((ForStatementBlock)block, visibleTransTables);
			OccurrenceProfile nested = nestedLoopProfile(block, networkWeight, loopContext, loopWeight,
				occurrenceContext);
			headerWrites = scanBlockRoots(blockRoots(block), nested.networkWeight,
				nested.loopContext, visibleTransTables, callStack, functionCalls,
				indexedFunctionCallContexts);
			putOccurrenceProfile(profiles, path, nested);
			ForStatement statement = (ForStatement)block.getStatement(0);
			Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
				nested.networkWeight, nested.loopContext, appendTransTable(visibleTransTables, headerWrites),
				callStack, occurrenceContext, profiles, functionCalls, indexedFunctionCallContexts);
			replaceMappings(headerWrites, bodyWrites);
		}
		else if(block instanceof WhileStatementBlock) {
			double loopWeight = requirePositiveWeight(
				RewireConstants.estimateWhileLoopWeight((WhileStatementBlock)block, visibleTransTables),
				"MINST_WHILE_OCCURRENCE_WEIGHT_UNPROVEN");
			OccurrenceProfile nested = nestedLoopProfile(block, networkWeight, loopContext, loopWeight,
				occurrenceContext);
			headerWrites = scanBlockRoots(blockRoots(block), nested.networkWeight,
				nested.loopContext, visibleTransTables, callStack, functionCalls,
				indexedFunctionCallContexts);
			putOccurrenceProfile(profiles, path, nested);
			WhileStatement statement = (WhileStatement)block.getStatement(0);
			Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
				nested.networkWeight, nested.loopContext, appendTransTable(visibleTransTables, headerWrites),
				callStack, occurrenceContext, profiles, functionCalls, indexedFunctionCallContexts);
			replaceMappings(headerWrites, bodyWrites);
		}
		else if(block instanceof IfStatementBlock) {
			headerWrites = scanBlockRoots(blockRoots(block), networkWeight,
				loopContext, visibleTransTables, callStack, functionCalls, indexedFunctionCallContexts);
			putOccurrenceProfile(profiles, path,
				new OccurrenceProfile(requirePositiveWeight(networkWeight,
					"MINST_BRANCH_PARENT_WEIGHT_UNPROVEN"), loopContext, occurrenceContext));
			double branchWeight = requirePositiveWeight(networkWeight
				* RewireConstants.DEFAULT_IF_ELSE_WEIGHT, "MINST_BRANCH_WEIGHT_UNPROVEN");
			IfStatement statement = (IfStatement)block.getStatement(0);
			List<Map<String,List<Hop>>> branchOuter = appendTransTable(visibleTransTables, headerWrites);
			Map<String,List<Hop>> ifWrites = indexBlocks(statement.getIfBody(), path + "/branch-if",
				branchWeight, loopContext, branchOuter, callStack, occurrenceContext, profiles, functionCalls,
				indexedFunctionCallContexts);
			Map<String,List<Hop>> elseWrites = indexBlocks(statement.getElseBody(), path + "/branch-else",
				branchWeight, loopContext, branchOuter, callStack, occurrenceContext, profiles, functionCalls,
				indexedFunctionCallContexts);
			mergeMappings(headerWrites, ifWrites);
			mergeMappings(headerWrites, elseWrites);
		}
		else if(block instanceof FunctionStatementBlock) {
			headerWrites = scanBlockRoots(blockRoots(block), networkWeight,
				loopContext, visibleTransTables, callStack, functionCalls, indexedFunctionCallContexts);
			putOccurrenceProfile(profiles, path,
				new OccurrenceProfile(requirePositiveWeight(networkWeight,
					"MINST_FUNCTION_WEIGHT_UNPROVEN"), loopContext, occurrenceContext));
			FunctionStatement statement = (FunctionStatement)block.getStatement(0);
			Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/body",
				networkWeight, loopContext, appendTransTable(visibleTransTables, headerWrites), callStack,
				occurrenceContext,
				profiles, functionCalls, indexedFunctionCallContexts);
			replaceMappings(headerWrites, bodyWrites);
		}
		else {
			headerWrites = scanBlockRoots(blockRoots(block), networkWeight,
				loopContext, visibleTransTables, callStack, functionCalls, indexedFunctionCallContexts);
			putOccurrenceProfile(profiles, path,
				new OccurrenceProfile(requirePositiveWeight(networkWeight,
					"MINST_OCCURRENCE_WEIGHT_UNPROVEN"), loopContext, occurrenceContext));
		}
		return headerWrites;
	}

	private static OccurrenceProfile nestedLoopProfile(StatementBlock block, double networkWeight,
		List<Pair<Long,Double>> loopContext, double loopWeight, Object occurrenceContext) {
		List<Pair<Long,Double>> nestedContext = new ArrayList<>(loopContext);
		nestedContext.add(Pair.of(block.getSBID(), loopWeight));
		return new OccurrenceProfile(requirePositiveWeight(networkWeight * loopWeight,
			"MINST_NESTED_LOOP_WEIGHT_UNPROVEN"), nestedContext, occurrenceContext);
	}

	private static double forLoopWeight(ForStatementBlock block,
		List<Map<String,List<Hop>>> transTables) {
		double defaultWeight = requirePositiveWeight(RewireConstants.DEFAULT_LOOP_WEIGHT,
			"MINST_DEFAULT_FOR_OCCURRENCE_WEIGHT_UNPROVEN");
		Double from = scalarConstant(block.getFromHops(), transTables);
		Double to = scalarConstant(block.getToHops(), transTables);
		Double increment = block.getIncrementHops() == null ? 1.0
			: scalarConstant(block.getIncrementHops(), transTables);
		if(from == null || to == null || increment == null || increment == 0.0)
			return defaultWeight;
		double step = increment;
		if(from > to && step == 1.0)
			step = -1.0;
		double iterations = UtilFunctions.getSeqLength(from, to, step, false);
		return iterations > 0.0 ? requirePositiveWeight(iterations,
			"MINST_FOR_OCCURRENCE_WEIGHT_UNPROVEN") : defaultWeight;
	}

	private static Double scalarConstant(Hop boundRoot, List<Map<String,List<Hop>>> transTables) {
		if(boundRoot == null || boundRoot.getInput() == null || boundRoot.getInput().isEmpty())
			return null;
		return RewireConstants.tryEvaluateScalarConstant(boundRoot.getInput().get(0), transTables);
	}

	private static void putOccurrenceProfile(Map<String,List<OccurrenceProfile>> profiles,
		String path, OccurrenceProfile profile) {
		List<OccurrenceProfile> occurrences = profiles.computeIfAbsent(path, ignored -> new ArrayList<>());
		for(OccurrenceProfile existing : occurrences)
			if(existing.contextIdentity == profile.contextIdentity) {
				if(!existing.sameAs(profile))
					throw new IllegalArgumentException("MINST_OCCURRENCE_CONTEXT_CONFLICT|path=" + path);
				return;
			}
		occurrences.add(profile);
	}

	private static double forwardingWeight(Map<String,List<OccurrenceProfile>> profiles,
		CompiledHopKey consumer, CompiledHopKey producer) {
		List<OccurrenceProfile> consumerProfiles = requireOccurrenceProfiles(profiles, consumer);
		List<OccurrenceProfile> producerProfiles = requireOccurrenceProfiles(profiles, producer);
		double total = 0.0;
		for(OccurrenceProfile consumerProfile : consumerProfiles) {
			OccurrenceProfile producerProfile = producerProfiles.stream()
				.filter(candidate -> candidate.contextIdentity == consumerProfile.contextIdentity)
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"MINST_OCCURRENCE_CONTEXT_UNMATCHED|consumer=" + consumer.normalizedSignature()
						+ "|producer=" + producer.normalizedSignature()));
			total += requirePositiveWeight(PlacementCostSemantics.forwardingWeight(
				consumerProfile.networkWeight, consumerProfile.loopContext, producerProfile.loopContext),
				"MINST_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requirePositiveWeight(total, "MINST_FORWARDING_WEIGHT_UNPROVEN");
	}

	private static List<OccurrenceProfile> requireOccurrenceProfiles(
		Map<String,List<OccurrenceProfile>> profiles,
		CompiledHopKey key) {
		List<String> regionPath = key.controlRegion().regionPath();
		if(regionPath.size() != 1)
			throw new IllegalArgumentException("MINST_OCCURRENCE_PATH_UNPROVEN|key="
				+ key.normalizedSignature() + "|paths=" + regionPath);
		String path = regionPath.get(0);
		List<OccurrenceProfile> pathProfiles = profiles.get(path);
		if(pathProfiles == null || pathProfiles.isEmpty())
			throw new IllegalArgumentException("MINST_OCCURRENCE_PATH_UNPROVEN|path=" + path);
		return pathProfiles;
	}

	private static List<Hop> blockRoots(StatementBlock block) {
		List<Hop> roots = new ArrayList<>();
		if(block.getHops() != null)
			roots.addAll(block.getHops());
		if(block instanceof IfStatementBlock)
			roots.add(((IfStatementBlock)block).getPredicateHops());
		else if(block instanceof WhileStatementBlock)
			roots.add(((WhileStatementBlock)block).getPredicateHops());
		else if(block instanceof ForStatementBlock) {
			roots.add(((ForStatementBlock)block).getFromHops());
			roots.add(((ForStatementBlock)block).getToHops());
			roots.add(((ForStatementBlock)block).getIncrementHops());
		}
		roots.removeIf(Objects::isNull);
		return roots;
	}

	private static Map<String,List<Hop>> scanBlockRoots(List<Hop> roots, double networkWeight,
		List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
		List<String> callStack,
		Map<String,List<FunctionCallContext>> functionCalls,
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts) {
		Map<String,List<Hop>> writes = new LinkedHashMap<>();
		for(Hop root : roots) {
			List<Map<String,List<Hop>>> current = appendTransTable(visibleTransTables, writes);
			collectFunctionCalls(List.of(root), networkWeight, loopContext, current,
				callStack, functionCalls, indexedFunctionCallContexts);
			mergeMappings(writes, transientWrites(List.of(root)));
		}
		return writes;
	}

	private static Map<String,List<Hop>> transientWrites(List<Hop> roots) {
		Map<String,List<Hop>> writes = new LinkedHashMap<>();
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop root : roots)
			collectTransientWrites(root, visited, writes);
		return writes;
	}

	private static void collectTransientWrites(Hop hop, Set<Hop> visited,
		Map<String,List<Hop>> writes) {
		if(hop == null || !visited.add(hop))
			return;
		for(Hop input : hop.getInput())
			collectTransientWrites(input, visited, writes);
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == OpOpData.TRANSIENTWRITE) {
			String name = hop.getName();
			if(name != null && !name.isBlank())
				writes.computeIfAbsent(name, ignored -> new ArrayList<>()).add(hop);
		}
	}

	private static void collectFunctionCalls(List<Hop> roots, double networkWeight,
		List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
		List<String> callStack,
		Map<String,List<FunctionCallContext>> functionCalls,
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts) {
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop root : roots)
			collectFunctionCalls(root, networkWeight, loopContext, visibleTransTables,
				callStack, functionCalls, indexedFunctionCallContexts, visited);
	}

	private static void collectFunctionCalls(Hop hop, double networkWeight,
		List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
		List<String> callStack,
		Map<String,List<FunctionCallContext>> functionCalls,
		Map<Hop,List<FunctionCallContext>> indexedFunctionCallContexts,
		Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		for(Hop input : hop.getInput())
			collectFunctionCalls(input, networkWeight, loopContext, visibleTransTables,
				callStack, functionCalls, indexedFunctionCallContexts, visited);
		if(!(hop instanceof FunctionOp))
			return;
		FunctionOp function = (FunctionOp)hop;
		if(function.getFunctionType() != FunctionOp.FunctionType.DML)
			return;
		String functionIdentity = function.getFunctionKey();
		if(functionIdentity == null || functionIdentity.isBlank())
			throw new IllegalArgumentException("MINST_FUNCTION_IDENTITY_UNPROVEN");
		if(callStack.contains(functionIdentity))
			throw new IllegalArgumentException("MINST_RECURSIVE_FUNCTION_CONTEXT_UNSUPPORTED|function="
				+ functionIdentity);
		String functionRootKey = DMLProgram.DEFAULT_NAMESPACE.equals(function.getFunctionNamespace())
			? function.getFunctionName() : functionIdentity;
		Map<String,List<Hop>> inputs = new LinkedHashMap<>();
		String[] names = function.getInputVariableNames();
		int limit = Math.min(names == null ? 0 : names.length, function.getInput().size());
		for(int index = 0; index < limit; index++) {
			String name = Objects.requireNonNull(names[index], "function input name");
			if(name.isBlank())
				throw new IllegalArgumentException("MINST_FUNCTION_INPUT_NAME_UNPROVEN");
			Hop input = resolveTransientSource(function.getInput(index), visibleTransTables);
			inputs.computeIfAbsent(name, ignored -> new ArrayList<>()).add(input);
		}
		List<Map<String,List<Hop>>> functionTransTables = appendTransTable(
			visibleTransTables, inputs);
		FunctionCallContext context = new FunctionCallContext(networkWeight, loopContext,
			functionTransTables, appendCallStack(callStack, functionIdentity));
		List<FunctionCallContext> indexedContexts = indexedFunctionCallContexts.computeIfAbsent(
			hop, ignored -> new ArrayList<>());
		if(indexedContexts.stream().anyMatch(existing -> existing.sameAs(context)))
			return;
		indexedContexts.add(context);
		functionCalls.computeIfAbsent(functionRootKey, ignored -> new ArrayList<>()).add(context);
	}

	private static List<String> appendCallStack(List<String> callStack, String functionIdentity) {
		List<String> nested = new ArrayList<>(callStack);
		nested.add(functionIdentity);
		return List.copyOf(nested);
	}

	private static Hop resolveTransientSource(Hop hop,
		List<Map<String,List<Hop>>> visibleTransTables) {
		if(!(hop instanceof DataOp) || ((DataOp)hop).getOp() != OpOpData.TRANSIENTREAD)
			return hop;
		String name = hop.getName();
		for(int index = visibleTransTables.size() - 1; index >= 0; index--) {
			List<Hop> candidates = visibleTransTables.get(index).get(name);
			if(candidates != null && !candidates.isEmpty()) {
				Hop candidate = candidates.get(candidates.size() - 1);
				if(candidate != hop)
					return candidate;
			}
		}
		return hop;
	}

	private static List<Map<String,List<Hop>>> visibleTransTables(
		List<Map<String,List<Hop>>> outer, Map<String,List<Hop>> former) {
		return appendTransTable(outer, former);
	}

	private static List<Map<String,List<Hop>>> appendTransTable(
		List<Map<String,List<Hop>>> tables, Map<String,List<Hop>> table) {
		List<Map<String,List<Hop>>> result = new ArrayList<>();
		if(tables != null)
			for(Map<String,List<Hop>> candidate : tables)
				if(candidate != null && !candidate.isEmpty())
					result.add(candidate);
		if(table != null && !table.isEmpty())
			result.add(table);
		return List.copyOf(result);
	}

	private static void replaceMappings(Map<String,List<Hop>> target,
		Map<String,List<Hop>> source) {
		for(Map.Entry<String,List<Hop>> entry : source.entrySet())
			target.put(entry.getKey(), new ArrayList<>(entry.getValue()));
	}

	private static void mergeMappings(Map<String,List<Hop>> target,
		Map<String,List<Hop>> source) {
		for(Map.Entry<String,List<Hop>> entry : source.entrySet())
			target.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
	}

	private static double requirePositiveWeight(double value, String reason) {
		if(!Double.isFinite(value) || value <= 0.0)
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private static void addGroupEdges(PlacementAnalysis analysis, AuxiliaryGroupFact group,
		EdgeAccumulator edges) {
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			if(group.direction() == Direction.UPLOAD)
				edges.add(endpoint.consumerComputeNodeId(), group.auxiliaryNodeId(),
					HARD_LEGALITY, ContributionKind.HARD_UPLOAD_OR,
					endpoint.consumerKey(), group.producerKey(), endpoint.inputPosition(),
					"upload-or-hard");
			else
				edges.add(group.auxiliaryNodeId(), endpoint.consumerComputeNodeId(),
					HARD_LEGALITY, ContributionKind.HARD_DOWNLOAD_OR,
					endpoint.consumerKey(), group.producerKey(), endpoint.inputPosition(),
					"download-or-hard");
		}
		EndpointFact priceOwner = group.endpointsInCanonicalOrder().stream()
			.max(Comparator.comparingLong(EndpointFact::demandCostBits)
				.thenComparing(endpoint -> endpoint.consumerKey().normalizedSignature()))
			.orElseThrow();
		if(group.direction() == Direction.UPLOAD)
			edges.add(group.auxiliaryNodeId(), uploadPriceTargetNodeId(group),
				Double.longBitsToDouble(group.priceBits()), ContributionKind.PRICE_UPLOAD_OR,
				group.producerKey(), priceOwner.consumerKey(), priceOwner.inputPosition(),
				"upload-or-price-max");
		else
			edges.add(group.producerPlacementNodeId(), group.auxiliaryNodeId(),
				Double.longBitsToDouble(group.priceBits()), ContributionKind.PRICE_DOWNLOAD_OR,
				group.producerKey(), priceOwner.consumerKey(), priceOwner.inputPosition(),
				"download-or-price-max");
		if(group.direction() == Direction.UPLOAD
			&& group.uploadPriceTarget() == UploadPriceTarget.PRODUCER_FED_FOUT) {
			long conjunction = uploadConjunctionNodeId(group);
			edges.add(conjunction, group.producerComputeNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_UPLOAD_REUSE, group.producerKey(),
				priceOwner.consumerKey(), priceOwner.inputPosition(), "upload-reuse-requires-fed-exec");
			edges.add(conjunction, group.producerPlacementNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_UPLOAD_REUSE, group.producerKey(),
				priceOwner.consumerKey(), priceOwner.inputPosition(), "upload-reuse-requires-fout");
		}
	}

	static boolean hasExactCompatibleDurableSource(PlacementAnalysis analysis, AuxiliaryGroupFact group) {
		return exactUploadAnchorCompatibility(analysis, group.producerKey(), group.conversionType(),
			group.endpointsInCanonicalOrder(), analysis.graph().node(group.producerKey()).orElseThrow().anchors())
			== ExactAnchorCompatibility.COMPATIBLE;
	}

	private enum ExactAnchorCompatibility { UNCONSTRAINED, COMPATIBLE, INCOMPATIBLE }

	private static UploadPriceTarget exactUploadPriceTarget(PlacementAnalysis analysis,
		DecisionFact producerDecision, List<MembershipRepresentative> representatives,
		BoundaryMode boundaryMode, FType conversionType, List<EndpointFact> endpoints) {
		List<MembershipRepresentative> producerRepresentatives = representatives.stream()
			.filter(representative -> representative.decisionKey() == producerDecision.key()).toList();
		if(producerRepresentatives.isEmpty())
			throw new IllegalArgumentException("MINST_UPLOAD_REUSE_MEMBERSHIP_MISSING|producer="
				+ producerDecision.key().normalizedSignature());
		List<ExactAnchorCompatibility> anchorCompatibility = producerRepresentatives.stream()
			.map(representative -> boundaryMode == BoundaryMode.TWRITE_METADATA
				? ExactAnchorCompatibility.UNCONSTRAINED
				: exactUploadAnchorCompatibility(analysis, producerDecision.key(), conversionType,
					endpoints, exactRepresentativeAnchors(analysis, representatives, representative)))
			.toList();
		List<Boolean> compatible = new ArrayList<>(producerRepresentatives.size());
		for(int index = 0; index < producerRepresentatives.size(); index++) {
			MembershipRepresentative representative = producerRepresentatives.get(index);
			compatible.add(representative.output() == FederatedOutput.FOUT
				&& representative.state().fType() == conversionType
				&& anchorCompatibility.get(index) != ExactAnchorCompatibility.INCOMPATIBLE);
		}
		if(compatible.stream().noneMatch(Boolean::booleanValue))
			return UploadPriceTarget.SINK;
		boolean matchesPlacement = true;
		boolean matchesCompute = true;
		boolean matchesFedFout = true;
		for(int index = 0; index < producerRepresentatives.size(); index++) {
			MembershipRepresentative representative = producerRepresentatives.get(index);
			boolean reusable = compatible.get(index);
			matchesPlacement &= reusable == (representative.output() == FederatedOutput.FOUT);
			matchesCompute &= reusable == (representative.execType() == ExecType.FED);
			matchesFedFout &= reusable == (representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT);
		}
		if(matchesPlacement)
			return UploadPriceTarget.PRODUCER_PLACEMENT;
		if(matchesCompute)
			return UploadPriceTarget.PRODUCER_COMPUTE;
		if(matchesFedFout)
			return UploadPriceTarget.PRODUCER_FED_FOUT;
		throw new IllegalArgumentException("MINST_UPLOAD_REUSE_PREDICATE_NOT_CUT_REPRESENTABLE|producer="
			+ producerDecision.key().normalizedSignature() + "|type=" + conversionType
			+ "|anchor=" + anchorCompatibility + "|memberships=" + producerRepresentatives.stream()
				.map(representative -> representative.state().normalizedSignature()).toList()
			+ "|compatible=" + compatible);
	}

	private static long uploadPriceTargetNodeId(AuxiliaryGroupFact group) {
		if(group.direction() != Direction.UPLOAD)
			throw new IllegalArgumentException("MINST_UPLOAD_PRICE_DIRECTION_MISMATCH");
		return switch(group.uploadPriceTarget()) {
			case SINK -> SINK;
			case PRODUCER_COMPUTE -> group.producerComputeNodeId();
			case PRODUCER_PLACEMENT -> group.producerPlacementNodeId();
			case PRODUCER_FED_FOUT -> uploadConjunctionNodeId(group);
			case NOT_APPLICABLE -> throw new IllegalArgumentException(
				"MINST_UPLOAD_PRICE_TARGET_NOT_APPLICABLE");
		};
	}

	private static long uploadConjunctionNodeId(AuxiliaryGroupFact group) {
		return group.auxiliaryNodeId() - 1L;
	}

	static boolean isUploadReuseSelected(AuxiliaryGroupFact group, Set<Long> sourceNodeIds) {
		Objects.requireNonNull(sourceNodeIds, "sourceNodeIds");
		if(group.direction() != Direction.UPLOAD)
			throw new IllegalArgumentException("MINST_UPLOAD_REUSE_DIRECTION_MISMATCH");
		return switch(group.uploadPriceTarget()) {
			case SINK -> false;
			case PRODUCER_COMPUTE -> sourceNodeIds.contains(group.producerComputeNodeId());
			case PRODUCER_PLACEMENT -> sourceNodeIds.contains(group.producerPlacementNodeId());
			case PRODUCER_FED_FOUT -> sourceNodeIds.contains(group.producerComputeNodeId())
				&& sourceNodeIds.contains(group.producerPlacementNodeId());
			case NOT_APPLICABLE -> throw new IllegalArgumentException(
				"MINST_UPLOAD_PRICE_TARGET_NOT_APPLICABLE");
		};
	}

	private static ExactAnchorCompatibility exactUploadAnchorCompatibility(PlacementAnalysis analysis,
		CompiledHopKey producerKey, FType conversionType, List<EndpointFact> endpoints,
		List<DurableAnchorKey> representativeAnchors) {
		NeutralPlacementGraph.Node producer = analysis.graph().node(producerKey).orElseThrow();
		Set<DurableAnchorKey> available = new LinkedHashSet<>(representativeAnchors);
		// A formal function TRead is a transparent alias of its caller argument.  Shared
		// preprocessing keeps that relation logical (there is intentionally no physical
		// Hop child and no copied anchor on the formal node), while legacy MinST saw the
		// concrete caller argument directly.  If the cut selects the formal as FOUT,
		// addLogicalFunctionInputEdges has already made the caller FOUT a hard prerequisite;
		// therefore the caller's exact durable layout is also the transfer authority for
		// FED consumers of the formal alias.
		List<DurableAnchorKey> logicalFunctionAnchors = effectiveLogicalFunctionInputs(analysis).stream()
			.filter(input -> input.targetRead() == producerKey)
			.map(input -> analysis.graph().node(input.authority().sourceArgument()).orElseThrow())
			.flatMap(source -> source.anchors().stream())
			.filter(anchor -> anchor.fType() == conversionType).toList();
		available.addAll(logicalFunctionAnchors);
		List<Set<DurableAnchorKey>> endpointRequirements = new ArrayList<>();
		Set<DurableAnchorKey> required = new LinkedHashSet<>();
		for(EndpointFact endpoint : endpoints) {
			Set<DurableAnchorKey> endpointAnchors = new LinkedHashSet<>();
			for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions())
				if(action.key().sourceValueVersion().equals(producer.valueVersion())
					&& action.key().materializationFType() == conversionType
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == endpoint.consumerKey()
							&& obligation.inputPosition() == endpoint.inputPosition()))
					endpointAnchors.add(action.key().durableAnchor());
			if(endpointAnchors.isEmpty())
				for(CompiledInputEdgeFact sibling : analysis.compiledInputEdgesInCanonicalOrder()) {
					if(sibling.consumer() != endpoint.consumerKey()
						|| sibling.inputPosition() == endpoint.inputPosition())
						continue;
					NeutralPlacementGraph.Node siblingNode = analysis.graph().node(sibling.producer()).orElseThrow();
					siblingNode.anchors().stream()
						.filter(anchor -> anchor.fType() == conversionType)
						.forEach(endpointAnchors::add);
					analysis.graph().relocationActions().stream()
						.filter(action -> action.key().sourceValueVersion().equals(siblingNode.valueVersion())
							&& action.key().materializationFType() == conversionType
							&& action.obligations().stream().anyMatch(obligation ->
								obligation.consumer() == endpoint.consumerKey()
									&& obligation.inputPosition() == sibling.inputPosition()))
						.map(action -> action.key().durableAnchor()).forEach(endpointAnchors::add);
				}
			endpointRequirements.add(Set.copyOf(endpointAnchors));
			required.addAll(endpointAnchors);
		}
		if(endpointRequirements.stream().allMatch(Set::isEmpty))
			return ExactAnchorCompatibility.UNCONSTRAINED;
		// An endpoint without an exact anchor requirement is a wildcard: it can consume
		// the materialization selected for a constrained sibling in this shared upload
		// group. Treating a wildcard as a conflicting anchor made a derived FED/FOUT
		// producer pay a second upload and left the wildcard endpoint without any
		// relocation authority (KMeans D -> {D<=rowMins(D), rowMins(D)}).
		return available.containsAll(required) ? ExactAnchorCompatibility.COMPATIBLE
			: ExactAnchorCompatibility.INCOMPATIBLE;
	}

	/**
	 * Recover the exact durable placement carried by one already-materialized FOUT
	 * membership. Captured FED rules preserve the worker pool of their exact FOUT
	 * inputs; the membership authority graph already proves those inputs recursively.
	 * Keeping this proof here avoids both a redundant re-federation and a type-only
	 * same-FType assumption. A true multi-anchor or cyclic proof remains unproven.
	 */
	private static List<DurableAnchorKey> exactRepresentativeAnchors(PlacementAnalysis analysis,
		List<MembershipRepresentative> representatives, MembershipRepresentative representative) {
		return exactRepresentativeAnchors(analysis, representatives, representative,
			new IdentityHashMap<>());
	}

	private static List<DurableAnchorKey> exactRepresentativeAnchors(PlacementAnalysis analysis,
		List<MembershipRepresentative> representatives, MembershipRepresentative representative,
		IdentityHashMap<MembershipRepresentative,Boolean> visiting) {
		if(representative.output() != FederatedOutput.FOUT)
			return List.of();
		if(representative.durableAnchorOrNull() != null)
			return List.of(representative.durableAnchorOrNull());
		if(representative.authorityKind() != MembershipAuthorityKind.CAPTURED_RULE
			|| visiting.put(representative, Boolean.TRUE) != null)
			return List.of();
		try {
			List<DurableAnchorKey> anchors = new ArrayList<>();
			Set<Integer> resolvedPositions = new LinkedHashSet<>();
			for(MembershipInputAuthorityFact authority : representative.inputAuthorityFacts()) {
				List<DurableAnchorKey> inherited = exactRepresentativeAnchors(analysis, representatives,
					authority.producerRepresentative(), visiting);
				if(inherited.isEmpty())
					return List.of();
				anchors.addAll(inherited);
				resolvedPositions.add(authority.inputPosition());
			}
			for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.consumer() != representative.decisionKey()
					|| edge.inputPosition() >= representative.orderedInputs().size()
					|| !representative.orderedInputs().get(edge.inputPosition()).present()
					|| resolvedPositions.contains(edge.inputPosition()))
					continue;
				FType expected = representative.orderedInputs().get(edge.inputPosition()).fType();
				List<DurableAnchorKey> direct = analysis.graph().node(edge.producer()).orElseThrow().anchors()
					.stream().filter(anchor -> anchor.fType() == expected).toList();
				if(direct.size() != 1)
					return List.of();
				anchors.add(direct.get(0));
				resolvedPositions.add(edge.inputPosition());
			}
			for(LogicalTransientInputFact input : analysis.logicalTransientInputsInCanonicalOrder()) {
				if(input.targetRead() != representative.decisionKey()
					|| input.logicalPosition() >= representative.orderedInputs().size()
					|| !representative.orderedInputs().get(input.logicalPosition()).present()
					|| resolvedPositions.contains(input.logicalPosition()))
					continue;
				List<DurableAnchorKey> inherited = exactSourceAnchors(analysis, representatives,
					input.sourceWrite(), input.federatedFType(), visiting);
				if(inherited.isEmpty())
					return List.of();
				anchors.addAll(inherited);
				resolvedPositions.add(input.logicalPosition());
			}
			for(LogicalFunctionInputFact input : analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(input.targetRead() != representative.decisionKey()
					|| input.logicalPosition() >= representative.orderedInputs().size()
					|| !representative.orderedInputs().get(input.logicalPosition()).present()
					|| resolvedPositions.contains(input.logicalPosition()))
					continue;
				FType expected = representative.orderedInputs().get(input.logicalPosition()).fType();
				List<DurableAnchorKey> inherited = exactSourceAnchors(analysis, representatives,
					input.sourceArgument(), expected, visiting);
				if(inherited.isEmpty())
					return List.of();
				anchors.addAll(inherited);
				resolvedPositions.add(input.logicalPosition());
			}
			for(int position = 0; position < representative.orderedInputs().size(); position++)
				if(representative.orderedInputs().get(position).present()
					&& !resolvedPositions.contains(position))
					return List.of();
			List<DurableAnchorKey> unique = identityDistinct(anchors);
			return unique.size() == 1 ? unique : List.of();
		}
		finally {
			visiting.remove(representative);
		}
	}

	private static List<DurableAnchorKey> exactSourceAnchors(PlacementAnalysis analysis,
		List<MembershipRepresentative> representatives, CompiledHopKey source, FType type,
		IdentityHashMap<MembershipRepresentative,Boolean> visiting) {
		List<DurableAnchorKey> direct = analysis.graph().node(source).orElseThrow().anchors().stream()
			.filter(anchor -> anchor.fType() == type).toList();
		if(direct.size() == 1)
			return direct;
		List<MembershipRepresentative> matching = representatives.stream()
			.filter(candidate -> candidate.decisionKey() == source
				&& candidate.output() == FederatedOutput.FOUT
				&& candidate.state().fType() == type)
			.toList();
		if(matching.size() != 1)
			return List.of();
		return exactRepresentativeAnchors(analysis, representatives, matching.get(0), visiting);
	}

	private static List<ObligationFact> deriveObligations(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey, DecisionFact> decisions) {
		List<ObligationFact> result = new ArrayList<>();
		for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions()) {
			List<ObligationEndpointFact> endpoints = new ArrayList<>();
			for(ObligationKey obligation : action.obligations())
				if(decisions.containsKey(obligation.consumer()))
					endpoints.add(new ObligationEndpointFact(obligation.consumer(),
						obligation.inputPosition(), obligation.requiredPlacement()));
			if(!endpoints.isEmpty())
				result.add(new ObligationFact(action.normalizedSignature(), endpoints));
		}
		return List.copyOf(result);
	}

	private static List<TransferAuthorityFact> transferAuthorities(PlacementAnalysis analysis,
		List<AuxiliaryGroupFact> groups, List<MembershipRepresentative> representatives,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<RepresentativePreference> preferences) {
		List<TransferAuthorityFact> result = new ArrayList<>();
		for(AuxiliaryGroupFact group : groups) {
			NeutralPlacementGraph.Node producer = analysis.graph().node(group.producerKey())
				.orElseThrow(() -> new IllegalArgumentException("MINST_EXACT_TRANSFER_PRODUCER_MISSING"));
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
				CompiledInputEdgeFact inputEdge = analysis.requireExactCompiledInputEdge(
					endpoint.producerKey(), endpoint.consumerKey(), endpoint.inputPosition());
				int authorityCount = result.size();
				if(group.direction() == Direction.UPLOAD) {
					DecisionFact consumer = Objects.requireNonNull(decisions.get(endpoint.consumerKey()),
						"MINST_EXACT_TRANSFER_CONSUMER_MISSING");
					PlacementState target = exactFedRepresentative(analysis, consumer, representatives,
						preferenceFor(preferences, consumer.key())).state();
					addRelocationAuthorities(analysis, result, group, endpoint, inputEdge, producer, target);
				}
				else {
					addDurableSourceAuthorities(result, group, endpoint, inputEdge, producer);
					if(result.size() == authorityCount)
						addSelectedSourceLocalMaterializationAuthorities(analysis, result, group, endpoint,
							inputEdge, producer, representatives);
				}
				if(result.size() == authorityCount)
					throw new IllegalArgumentException("MINST_EXACT_TRANSFER_AUTHORITY_DOMAIN_MISSING|direction="
						+ group.direction() + "|producer=" + group.producerKey().normalizedSignature()
						+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
						+ "|input=" + endpoint.inputPosition() + "|conversion=" + group.conversionType()
						+ "|sourceActions=" + analysis.graph().relocationActions().stream()
							.filter(action -> action.key().sourceValueVersion() == producer.valueVersion())
							.map(action -> action.key().targetPlacement().normalizedSignature() + ':'
								+ action.key().materializationFType() + ':' + action.obligations().stream()
									.map(obligation -> obligation.consumer().normalizedSignature() + '@'
										+ obligation.inputPosition()).toList()).toList());
			}
		}
		List<TransferAuthorityFact> immutable = List.copyOf(result);
		validateTransferAuthorityDomain(groups, immutable);
		validateTransferAuthorityCompleteness(analysis, groups, immutable, representatives,
			decisions, preferences);
		return immutable;
	}

	/**
	 * Every executable auxiliary demand must have one, and only one, physical authority
	 * for each required placement. Graph-owned relocation/durable actions are enumerated
	 * completely. Independent-anchor and selected-local facts are proof fallbacks only
	 * when no stronger graph-owned action exists; they do not encode a second normalized
	 * executable action. Two authorities surviving for one placement are therefore a real
	 * ambiguity and fail closed instead of being chosen canonically.
	 */
	static void validateTransferAuthorityDomain(List<AuxiliaryGroupFact> groups,
		List<TransferAuthorityFact> authorities) {
		for(AuxiliaryGroupFact group : groups)
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
				List<TransferAuthorityFact> owned = authorities.stream()
					.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
					.toList();
				if(owned.isEmpty())
					throw new IllegalArgumentException("MINST_EXACT_TRANSFER_AUTHORITY_DOMAIN_MISSING|direction="
						+ group.direction() + "|producer=" + group.producerKey().normalizedSignature()
						+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
						+ "|input=" + endpoint.inputPosition());
				Map<String,List<TransferAuthorityFact>> byRequiredPlacement = owned.stream()
					.collect(java.util.stream.Collectors.groupingBy(authority ->
						authority.requiredPlacement().normalizedSignature(), java.util.TreeMap::new,
						java.util.stream.Collectors.toList()));
				for(Map.Entry<String,List<TransferAuthorityFact>> entry : byRequiredPlacement.entrySet())
					if(entry.getValue().size() != 1)
						throw new IllegalArgumentException("MINST_EXACT_TRANSFER_AUTHORITY_DOMAIN_AMBIGUOUS"
							+ "|direction=" + group.direction() + "|producer="
							+ group.producerKey().normalizedSignature() + "|consumer="
							+ endpoint.consumerKey().normalizedSignature() + "|input="
							+ endpoint.inputPosition() + "|required=" + entry.getKey()
							+ "|authorities=" + entry.getValue().stream()
								.map(TransferAuthorityFact::authoritySignature).sorted().toList());
			}
	}

	private static void validateTransferAuthorityCompleteness(PlacementAnalysis analysis,
		List<AuxiliaryGroupFact> groups, List<TransferAuthorityFact> authorities,
		List<MembershipRepresentative> representatives,
		IdentityHashMap<CompiledHopKey,DecisionFact> decisions,
		List<RepresentativePreference> preferences) {
		for(AuxiliaryGroupFact group : groups)
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
				Set<String> expected = new TreeSet<>();
				if(group.direction() == Direction.UPLOAD) {
					NeutralPlacementGraph.Node producer = analysis.graph().node(group.producerKey()).orElseThrow();
					DecisionFact consumer = Objects.requireNonNull(decisions.get(endpoint.consumerKey()),
						"MINST_EXACT_TRANSFER_CONSUMER_MISSING");
					PlacementState target = exactFedRepresentative(analysis, consumer, representatives,
						preferenceFor(preferences, consumer.key())).state();
					for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions())
						if(action.key().sourceValueVersion() == producer.valueVersion()
							&& action.key().materializationFType() == group.conversionType()
							&& action.key().targetPlacement().equals(target)
							&& action.obligations().stream().anyMatch(obligation ->
								obligation.consumer() == endpoint.consumerKey()
									&& obligation.inputPosition() == endpoint.inputPosition()))
							expected.add(action.key().targetPlacement().normalizedSignature());
				}
				else {
					NeutralPlacementGraph.Node producer = analysis.graph().node(group.producerKey()).orElseThrow();
					producer.legalAlternatives().stream()
						.filter(state -> state.output() == FederatedOutput.FOUT
							&& state.fType() == group.conversionType())
						.filter(state -> producer.anchors().stream().anyMatch(anchor ->
							anchor.fType() == state.fType())
							|| representatives.stream().anyMatch(representative ->
								representative.decisionKey() == group.producerKey()
									&& representative.state() == state
									&& representative.execType() == ExecType.FED
									&& selectedSourceMembershipAuthorityKind(representative.authorityKind())))
						.map(PlacementState::normalizedSignature).forEach(expected::add);
				}
				Set<String> actual = authorities.stream()
					.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
					.map(authority -> authority.requiredPlacement().normalizedSignature())
					.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
				// When no exact graph action owns an upload, the independently proven anchor
				// domain is itself the complete source. Otherwise every graph-owned placement
				// must be represented exactly once; proof fallback categories do not create a
				// second normalized executable action.
				if(!expected.isEmpty() && !actual.equals(expected))
					throw new IllegalArgumentException("MINST_EXACT_TRANSFER_AUTHORITY_DOMAIN_INCOMPLETE"
						+ "|direction=" + group.direction() + "|producer="
						+ group.producerKey().normalizedSignature() + "|consumer="
						+ endpoint.consumerKey().normalizedSignature() + "|input="
						+ endpoint.inputPosition() + "|expected=" + expected + "|actual=" + actual);
			}
	}

	private static void addDurableSourceAuthorities(List<TransferAuthorityFact> result,
		AuxiliaryGroupFact group, EndpointFact endpoint, CompiledInputEdgeFact inputEdge,
		NeutralPlacementGraph.Node producer) {
		for(DurableAnchorKey anchor : producer.anchors()) {
			if(anchor.fType() != group.conversionType())
				continue;
			for(PlacementState required : producer.legalAlternatives()) {
				if(required.output() != FederatedOutput.FOUT || required.fType() != anchor.fType())
					continue;
				String signature = "DURABLE_SOURCE|" + group.direction() + '|'
					+ producer.valueVersion().normalizedSignature() + '|'
					+ inputEdge.producer().normalizedSignature() + '|'
					+ inputEdge.consumer().normalizedSignature() + '|' + inputEdge.inputPosition() + '|'
					+ anchor.normalizedSignature() + '|' + required.normalizedSignature() + '|'
					+ endpoint.demandCostBits();
				result.add(TransferAuthorityFact.durableSource(group, endpoint, inputEdge,
					producer.valueVersion(), anchor, required, signature));
			}
		}
	}

	private static void addSelectedSourceLocalMaterializationAuthorities(PlacementAnalysis analysis,
		List<TransferAuthorityFact> result, AuxiliaryGroupFact group, EndpointFact endpoint,
		CompiledInputEdgeFact inputEdge, NeutralPlacementGraph.Node producer,
		List<MembershipRepresentative> representatives) {
		if(group.direction() != Direction.DOWNLOAD)
			return;
		List<MembershipRepresentative> matching = representatives.stream()
			.filter(representative -> representative.decisionKey() == group.producerKey()
				&& representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT
				&& representative.state().fType() == group.conversionType()
				&& selectedSourceMembershipAuthorityKind(representative.authorityKind()))
			.toList();
		if(matching.isEmpty())
			return;
		if(matching.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_SELECTED_LOCAL_MEMBERSHIP_AMBIGUOUS|key="
				+ group.producerKey().normalizedSignature());
		MembershipRepresentative representative = matching.get(0);
		PlacementState required = representative.state();
		String provenance = NormalizedPlannerResults.durableLocalProvenance(producer, required);
		String proof = representativeProofSignature(representative);
		String signature = selectedSourceLocalMaterializationSignature(group, endpoint, inputEdge,
			producer.valueVersion(), required, provenance, proof);
		result.add(TransferAuthorityFact.selectedSourceLocalMaterialization(group, endpoint, inputEdge,
			producer.valueVersion(), required, signature, proof));
	}

	private static String selectedSourceLocalMaterializationSignature(AuxiliaryGroupFact group,
		EndpointFact endpoint, CompiledInputEdgeFact inputEdge, ValueVersionKey source,
		PlacementState required, String provenance, String producerMembershipProof) {
		return "SELECTED_SOURCE_LOCAL_MATERIALIZATION|" + group.direction() + '|'
			+ source.normalizedSignature() + '|'
			+ inputEdge.producer().normalizedSignature() + '|'
			+ inputEdge.consumer().normalizedSignature() + '|' + inputEdge.inputPosition() + '|'
			+ required.normalizedSignature() + '|' + provenance + '|'
			+ producerMembershipProof + '|' + endpoint.demandCostBits();
	}

	private static boolean selectedSourceMembershipAuthorityKind(MembershipAuthorityKind kind) {
		return kind == MembershipAuthorityKind.CAPTURED_RULE
			|| kind == MembershipAuthorityKind.RELOCATION_SOURCE
			|| kind == MembershipAuthorityKind.DURABLE_ANCHOR;
	}

	private static void addRelocationAuthorities(PlacementAnalysis analysis,
		List<TransferAuthorityFact> result, AuxiliaryGroupFact group, EndpointFact endpoint,
		CompiledInputEdgeFact inputEdge, NeutralPlacementGraph.Node producer,
		PlacementState target) {
		for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions()) {
			if(action.key().sourceValueVersion() != producer.valueVersion())
				continue;
			if(!action.key().targetPlacement().equals(target))
				continue;
			for(ObligationKey obligation : action.obligations())
				if(obligation.relocationAction() == action.key()
					&& obligation.sourceValueVersion() == producer.valueVersion()
					&& obligation.consumer() == endpoint.consumerKey()
					&& obligation.inputPosition() == endpoint.inputPosition()
					&& obligation.requiredPlacement() == action.key().targetPlacement()
					&& exactRelocationMaterializationType(analysis, inputEdge, action)
						== group.conversionType())
					result.add(TransferAuthorityFact.relocation(group, endpoint, inputEdge,
						producer.valueVersion(), action, obligation));
		}
	}

	private static void addIndependentAnchorAuthorities(PlacementAnalysis analysis,
		List<TransferAuthorityFact> result, AuxiliaryGroupFact group, EndpointFact endpoint,
		CompiledInputEdgeFact inputEdge) {
		CandidateConsumerProfileFact profile;
		try {
			profile = analysis.candidateConsumerProfileFacts().requireExact(
				endpoint.consumerKey(), endpoint.inputPosition());
		}
		catch(PlacementAnalysis.CandidateRuleLookupException missingProfile) {
			return;
		}
		if(profile.status() != CandidateEvaluationStatus.AVAILABLE
			|| !profile.allowedTargetTypes().isEmpty()
				&& !profile.allowedTargetTypes().contains(group.conversionType()))
			return;
		for(CompiledInputEdgeFact sibling : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(sibling.consumer() != endpoint.consumerKey()
				|| sibling.inputPosition() == endpoint.inputPosition())
				continue;
			if(analysis.requireExactCompiledInputEdge(sibling.producer(), sibling.consumer(),
				sibling.inputPosition()) != sibling)
				throw new IllegalArgumentException("MINST_EXACT_ANCHOR_EDGE_IDENTITY_UNPROVEN");
			NeutralPlacementGraph.Node siblingNode = analysis.graph().node(sibling.producer()).orElseThrow();
			for(DurableAnchorKey anchor : siblingNode.anchors()) {
				FType materializationType = PlacementCostSemantics.exactMaterializationFType(
					analysis.shapeFact(group.producerKey()).orElseThrow(() ->
						new IllegalArgumentException("MINST_EXACT_UPLOAD_SHAPE_MISSING")), anchor);
				if(materializationType != group.conversionType())
					continue;
				PlacementState required = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
					anchor.fType(), false);
				NeutralPlacementGraph.Node producer = analysis.graph().node(group.producerKey()).orElseThrow();
				String signature = independentAnchorSignature(group, endpoint, inputEdge,
					producer.valueVersion(), sibling, anchor, profile, required);
				result.add(TransferAuthorityFact.independentAnchor(group, endpoint, inputEdge,
					producer.valueVersion(), sibling, anchor, profile, required, signature));
			}
		}
	}

	private static String independentAnchorSignature(AuxiliaryGroupFact group, EndpointFact endpoint,
		CompiledInputEdgeFact inputEdge, ValueVersionKey source,
		CompiledInputEdgeFact anchorInputEdge,
		DurableAnchorKey anchor, CandidateConsumerProfileFact profile, PlacementState required) {
		return "INDEPENDENT_ANCHOR|" + group.direction() + '|'
			+ source.normalizedSignature() + '|'
			+ inputEdge.producer().normalizedSignature() + '|'
			+ inputEdge.consumer().normalizedSignature() + '|' + inputEdge.inputPosition() + '|'
			+ anchorInputEdge.producer().normalizedSignature() + '|'
			+ anchorInputEdge.consumer().normalizedSignature() + '|' + anchorInputEdge.inputPosition() + '|'
			+ anchor.normalizedSignature() + '|'
			+ profile.key().consumerOccurrence().normalizedSignature() + '|'
			+ profile.key().inputPosition() + '|' + profile.status() + '|'
			+ profile.allowedTargetTypes() + '|' + profile.failureCode() + '|'
			+ required.normalizedSignature() + '|' + endpoint.demandCostBits();
	}

	private static FType exactConsumerInputType(PlacementAnalysis analysis, CompiledInputEdgeFact edge,
		List<MembershipRepresentative> representatives) {
		List<MembershipRepresentative> exactRepresentatives = representatives.stream()
			.filter(representative -> representative.decisionKey() == edge.consumer()
				&& representative.authorityKind() == MembershipAuthorityKind.CAPTURED_RULE
				&& representative.execType() == ExecType.FED)
			.filter(representative -> edge.inputPosition() >= 0
				&& edge.inputPosition() < representative.orderedInputs().size())
			.toList();
		Set<FType> representativeTypes = new LinkedHashSet<>();
		for(MembershipRepresentative representative : exactRepresentatives) {
			CandidateInputState input = representative.orderedInputs().get(edge.inputPosition());
			if(input.present())
				representativeTypes.add(input.fType());
			else if(!isNativeLocalInput(representative, edge.inputPosition()))
				representativeTypes.add(exactRelocationMaterializationType(
					analysis, edge, representative.state()));
		}
		if(representativeTypes.size() > 1)
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN"
				+ "|ambiguous-exact-membership-representatives|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition()
				+ "|types=" + representativeTypes + "|representatives="
				+ representatives.stream()
					.filter(representative -> representative.decisionKey() == edge.consumer()
						&& representative.execType() == ExecType.FED)
					.map(representative -> representative.state().normalizedSignature()
						+ "/inputs=" + representative.orderedInputs()
						+ "/emission=" + candidateEmissionSignatureOrDash(representative))
					.toList());
		if(representativeTypes.size() == 1)
			return representativeTypes.iterator().next();
		if(!exactRepresentatives.isEmpty())
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN"
				+ "|exact-membership-input-type-missing|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition());

		// The exact membership materializer has already resolved competing candidate-domain
		// rows (for example ROW versus BROADCAST) using graph-owned producer authorities.
		// Re-introducing every available row here discards that proof and can reject a legal
		// legacy MinST input after its representative was made unique. Consult raw candidate
		// facts only when this consumer has no exact FED representative.
		Set<FType> types = new LinkedHashSet<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.key().parentOccurrence() != edge.consumer()
				|| fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| edge.inputPosition() >= fact.key().orderedInputs().size()
				|| !fact.key().orderedInputs().get(edge.inputPosition()).present()
				|| fact.allowedEmissionFacts().stream().noneMatch(emission ->
					emission.emissionState().placementState().execType() == ExecType.FED))
				continue;
			types.add(fact.key().orderedInputs().get(edge.inputPosition()).fType());
		}
		if(types.size() > 1)
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN"
				+ "|ambiguous-exact-membership-authority|consumer="
				+ edge.consumer().normalizedSignature() + "|input=" + edge.inputPosition()
				+ "|types=" + types + "|representatives=" + representatives.stream()
					.filter(representative -> representative.decisionKey() == edge.consumer())
					.map(representative -> representative.authorityKind() + "/"
						+ representative.state().normalizedSignature() + "/inputs="
						+ representative.orderedInputs()).toList());
		return types.isEmpty() ? null : types.iterator().next();
	}

	private static FType requiredType(PlacementAnalysis analysis, CompiledInputEdgeFact edge,
		List<MembershipRepresentative> representatives, RepresentativePreference preference) {
		if(preference != null && preference.execType() == ExecType.FED) {
			if(edge.inputPosition() < 0 || edge.inputPosition() >= preference.orderedInputs().size())
				throw new IllegalArgumentException("MINST_EXACT_PREFERRED_INPUT_POSITION_INVALID");
			CandidateInputState input = preference.orderedInputs().get(edge.inputPosition());
			if(!input.present())
				throw new IllegalArgumentException("MINST_EXACT_NATIVE_LOCAL_INPUT_HAS_UPLOAD_DEMAND");
			return input.fType();
		}
		FType exact = exactConsumerInputType(analysis, edge, representatives);
		if(exact != null)
			return exact;
		Set<FType> structuralLayouts = new LinkedHashSet<>();
		addPublishedLayouts(analysis.graph().node(edge.producer()).orElseThrow(), structuralLayouts);
		for(CompiledInputEdgeFact sibling : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(sibling.consumer() != edge.consumer() || sibling.inputPosition() == edge.inputPosition())
				continue;
			addPublishedLayouts(analysis.graph().node(sibling.producer()).orElseThrow(), structuralLayouts);
		}
		try {
			PlacementAnalysis.CandidateConsumerProfileFact profile = analysis.candidateConsumerProfileFacts()
				.requireExact(edge.consumer(), edge.inputPosition());
			if(profile.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE)
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|profile-status="
					+ profile.status() + "|failure=" + profile.failureCode());
			List<FType> allowed = profile.allowedTargetTypes().stream()
				.distinct().sorted(Comparator.comparing(Enum::name)).toList();
			if(allowed.isEmpty()) {
				if(structuralLayouts.size() == 1)
					return structuralLayouts.iterator().next();
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|unconstrained-profile");
			}
			List<FType> compatibleLayouts = allowed.stream().filter(structuralLayouts::contains).toList();
			if(compatibleLayouts.size() == 1)
				return compatibleLayouts.get(0);
			if(compatibleLayouts.size() > 1)
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|ambiguous-sibling-layout");
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|no-structural-intersection"
				+ "|producer=" + edge.producer().normalizedSignature()
				+ "|consumer=" + edge.consumer().normalizedSignature()
				+ "|input=" + edge.inputPosition() + "|allowed=" + allowed
				+ "|structural=" + structuralLayouts);
		}
		catch(PlacementAnalysis.CandidateRuleLookupException missingProfile) {
			if(structuralLayouts.size() == 1)
				return structuralLayouts.iterator().next();
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|missing-profile", missingProfile);
		}
	}

	private static void addPublishedLayouts(NeutralPlacementGraph.Node node, Set<FType> layouts) {
		node.legalAlternatives().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT && state.fType() != null)
			.map(PlacementState::fType).forEach(layouts::add);
		node.anchors().stream().map(anchor -> anchor.fType()).forEach(layouts::add);
	}

	private static boolean canUpload(Hop producer) {
		return isPersistentRead(producer) || !(producer instanceof DataGenOp);
	}

	private static boolean isPersistentRead(Hop hop) {
		return hop instanceof DataOp && ((DataOp)hop).getOp() == OpOpData.PERSISTENTREAD;
	}

	private static boolean hasExec(DecisionFact decision, ExecType exec) {
		return decision.legalStatesInCanonicalOrder().stream().anyMatch(state -> state.execType() == exec);
	}

	private static boolean hasOutput(DecisionFact decision, FederatedOutput output) {
		return decision.legalStatesInCanonicalOrder().stream().anyMatch(state -> state.output() == output);
	}

	private static boolean hasState(DecisionFact decision, ExecType exec, FederatedOutput output) {
		return decision.legalStatesInCanonicalOrder().stream()
			.anyMatch(state -> state.execType() == exec && state.output() == output);
	}

	private static FType requireExactExecLayoutType(DecisionFact decision,
		List<MembershipRepresentative> representatives, ExecType execType) {
		for(FederatedOutput output : List.of(FederatedOutput.FOUT, FederatedOutput.LOUT)) {
			MembershipRepresentative representative = exactMembershipRepresentative(
				decision, representatives, execType, output);
			if(representative == null)
				continue;
			CandidateEmissionFact emission = representative.candidateRuleFactOrNull() == null
				? null : exactCandidateEmissionFact(representative);
			FType type = emission == null ? representative.state().fType() : emission.executionFType();
			if(type != null)
				return type;
		}
		throw new IllegalArgumentException("MINST_DECISION_EXEC_LAYOUT_UNPROVEN|key="
			+ decision.key().normalizedSignature() + "|exec=" + execType);
	}

	private static FType requireExactMembershipLayoutType(DecisionFact decision,
		List<MembershipRepresentative> representatives, ExecType execType, FederatedOutput output) {
		MembershipRepresentative representative = exactMembershipRepresentative(
			decision, representatives, execType, output);
		if(representative == null || representative.state().fType() == null)
			throw new IllegalArgumentException("MINST_DECISION_MEMBERSHIP_LAYOUT_UNPROVEN|key="
				+ decision.key().normalizedSignature() + "|membership=" + membership(execType, output));
		return representative.state().fType();
	}

	private static MembershipRepresentative exactMembershipRepresentative(DecisionFact decision,
		List<MembershipRepresentative> representatives, ExecType execType, FederatedOutput output) {
		List<MembershipRepresentative> matching = representatives.stream()
			.filter(representative -> representative.decisionKey() == decision.key()
				&& representative.execType() == execType && representative.output() == output)
			.toList();
		if(matching.size() > 1)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_REPRESENTATIVE_AMBIGUOUS|key="
				+ decision.key().normalizedSignature() + "|membership=" + membership(execType, output));
		return matching.isEmpty() ? null : matching.get(0);
	}

	private static CandidateEmissionFact exactCandidateEmissionFact(DecisionFact decision,
		List<MembershipRepresentative> representatives,
		ExecType execType, FederatedOutput output) {
		MembershipRepresentative representative = exactMembershipRepresentative(
			decision, representatives, execType, output);
		if(representative == null)
			return null;
		return representative.candidateRuleFactOrNull() == null
			? null : exactCandidateEmissionFact(representative);
	}

	private static CandidateEmissionFact exactCandidateEmissionFact(
		MembershipRepresentative representative) {
		CandidateRuleFact fact = representative.candidateRuleFactOrNull();
		if(fact == null)
			throw new IllegalArgumentException("MINST_EXACT_CANDIDATE_EMISSION_FACT_MISSING");
		CandidateEmissionFact exact = representative.candidateEmissionFactOrNull();
		if(exact == null)
			throw new IllegalArgumentException("MINST_EXACT_CANDIDATE_EMISSION_FACT_MISSING|key="
				+ representative.decisionKey().normalizedSignature());
		return exact;
	}

	private static String candidateEmissionSignatureOrDash(
		MembershipRepresentative representative) {
		CandidateRuleFact fact = representative.candidateRuleFactOrNull();
		if(fact == null)
			return "-";
		CandidateEmissionFact exact = representative.candidateEmissionFactOrNull();
		if(exact != null)
			return exact.normalizedSignature();
		if(fact.allowedEmissionFacts().stream().noneMatch(emission ->
			emission.emissionState().placementState() == representative.state())
			&& !(representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT))
			return "-";
		throw new IllegalArgumentException("MINST_EXACT_CANDIDATE_EMISSION_FACT_MISSING|key="
			+ representative.decisionKey().normalizedSignature());
	}

	private static long computeNodeId(int scopeIndex) { return 2L * scopeIndex; }
	private static long placementNodeId(int scopeIndex) { return 2L * scopeIndex + 1L; }

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
		// estimate and made MinST price small recompiled inputs (for example PCA Components)
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
			throw new IllegalArgumentException("MINST_OUTPUT_BYTES_UNPROVEN|key="
				+ key.normalizedSignature());
		return derived;
	}

	/**
	 * Recovers only shapes that are provable from immutable placement facts.  In
	 * particular, a function-formal TRead aliases its exact caller argument; this
	 * lets row/column aggregates inside the function retain their real output size
	 * without mutating compiled Hop statistics or installing an unknown-size
	 * fallback.
	 */
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

	/**
	 * Resolves the unique operator that owns a multi-return FUNCTIONOUTPUT.  This is
	 * operator semantics, not a size fallback: builtins such as EIGEN already expose
	 * exact per-output dimensions even when the generated DataOp itself remains
	 * unshaped after HOP rewrites.
	 */
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
				throw new IllegalArgumentException("MINST_EXACT_MATRIX_SHAPE_INVALID");
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
		return Double.doubleToRawLongBits(requireCost(value, "MINST_COST_BITS_UNPROVEN"));
	}

	private static String diagnosticKey(CompiledHopKey key) {
		String signature = key.normalizedSignature();
		return "key#" + Integer.toUnsignedString(signature.hashCode(), 16)
			+ "[chars=" + signature.length() + ']';
	}

	private static void validateScope(PlacementAnalysis analysis, List<CompiledHopKey> scope) {
		Objects.requireNonNull(scope, "orderedScope");
		List<CompiledHopKey> expected = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey key : scope)
			if(!seen.add(key))
				fail(ValidationReason.SCOPE_DUPLICATE, "Scope contains a duplicate key identity");
		Set<CompiledHopKey> expectedIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
		expectedIdentities.addAll(expected);
		for(CompiledHopKey key : scope)
			if(!expectedIdentities.contains(key))
				fail(ValidationReason.SCOPE_FOREIGN, "Scope contains a copied or foreign key");
		if(scope.size() != expected.size())
			fail(ValidationReason.SCOPE_REORDERED, "Scope does not exactly cover compiled occurrences");
		for(int index = 0; index < expected.size(); index++)
			if(scope.get(index) != expected.get(index))
				fail(ValidationReason.SCOPE_REORDERED, "Scope order differs at index " + index);
	}

	private static void validateCapacitySums(List<DirectedEdgeFact> edges) {
		Objects.requireNonNull(edges, "edges");
		for(DirectedEdgeFact edge : edges) {
			double sum = 0.0;
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder()) {
				validateBits(contribution.costBits());
				sum += Double.longBitsToDouble(contribution.costBits());
			}
			validateBits(edge.capacityBits());
			if(edge.capacityBits() != Double.doubleToRawLongBits(sum))
				fail(ValidationReason.CAPACITY_SUM_MISMATCH,
					"Edge capacity differs from its ordered contribution sum");
		}
	}

	private static void validateBits(long value) {
		double cost = Double.longBitsToDouble(value);
		if(!Double.isFinite(cost) || cost < 0.0
			|| value == Double.doubleToRawLongBits(-0.0))
			fail(ValidationReason.CAPACITY_SUM_MISMATCH, "Cost bits are not canonical");
	}

	private static boolean sameDecisions(List<DecisionFact> expected, List<DecisionFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			DecisionFact left = expected.get(i), right = actual.get(i);
			if(left.key() != right.key() || left.computeNodeId() != right.computeNodeId()
				|| left.placementNodeId() != right.placementNodeId()
				|| !left.legalStatesInCanonicalOrder().equals(right.legalStatesInCanonicalOrder()))
				return false;
		}
		return true;
	}

	private static boolean sameRepresentatives(List<MembershipRepresentative> expected,
		List<MembershipRepresentative> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			MembershipRepresentative left = expected.get(i), right = actual.get(i);
			if(left.decisionKey() != right.decisionKey() || left.execType() != right.execType()
				|| left.output() != right.output() || left.state() != right.state()
				|| left.authorityKind() != right.authorityKind()
				|| left.durableAnchorOrNull() != right.durableAnchorOrNull()
				|| left.candidateRuleFactOrNull() != right.candidateRuleFactOrNull()
				|| left.candidateEmissionFactOrNull() != right.candidateEmissionFactOrNull()
				|| !left.orderedInputs().equals(right.orderedInputs())
				|| !sameMembershipInputAuthorities(left.inputAuthorityFacts(), right.inputAuthorityFacts())
				|| !Objects.equals(left.invocationEvidenceOrNull(), right.invocationEvidenceOrNull())
				|| left.relocationActionOrNull() != right.relocationActionOrNull()
				|| !Objects.equals(left.authoritySignatureOrNull(), right.authoritySignatureOrNull()))
				return false;
		}
		return true;
	}

	private static boolean sameMembershipInputAuthorities(List<MembershipInputAuthorityFact> expected,
		List<MembershipInputAuthorityFact> actual) {
		if(expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			MembershipInputAuthorityFact left = expected.get(i), right = actual.get(i);
			if(left.inputEdge() != right.inputEdge()
				|| left.inputPosition() != right.inputPosition()
				|| left.producerRepresentative().decisionKey() != right.producerRepresentative().decisionKey()
				|| left.producerRepresentative().state() != right.producerRepresentative().state()
				|| left.producerRepresentative().authorityKind() != right.producerRepresentative().authorityKind()
				|| left.producerRepresentative().candidateEmissionFactOrNull()
					!= right.producerRepresentative().candidateEmissionFactOrNull()
				|| !left.authoritySignature().equals(right.authoritySignature()))
				return false;
		}
		return true;
	}

	private static void validateGroups(List<AuxiliaryGroupFact> expected,
		List<AuxiliaryGroupFact> actual) {
		if(actual == null || expected.size() != actual.size())
			fail(ValidationReason.OR_GROUP_ENDPOINT_MISMATCH, "Auxiliary group count differs");
		for(int i = 0; i < expected.size(); i++) {
			AuxiliaryGroupFact left = expected.get(i), right = actual.get(i);
			if(left.direction() != right.direction())
				fail(ValidationReason.OR_GROUP_DIRECTION_MISMATCH, "Auxiliary group direction differs");
			if(left.priceBits() != right.priceBits())
				fail(ValidationReason.OR_GROUP_PRICE_MISMATCH, "Auxiliary group price differs");
			if(left.auxiliaryNodeId() != right.auxiliaryNodeId()
				|| left.boundaryMode() != right.boundaryMode()
				|| left.producerKey() != right.producerKey()
				|| left.producerComputeNodeId() != right.producerComputeNodeId()
				|| left.producerPlacementNodeId() != right.producerPlacementNodeId()
				|| left.uploadPriceTarget() != right.uploadPriceTarget()
				|| left.conversionType() != right.conversionType()
				|| !sameEndpoints(left.endpointsInCanonicalOrder(), right.endpointsInCanonicalOrder()))
				fail(ValidationReason.OR_GROUP_ENDPOINT_MISMATCH, "Auxiliary group endpoints differ");
		}
	}

	private static boolean sameEndpoints(List<EndpointFact> expected, List<EndpointFact> actual) {
		if(expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			EndpointFact left = expected.get(i), right = actual.get(i);
			if(left.producerKey() != right.producerKey()
				|| left.inputPosition() != right.inputPosition()
				|| left.consumerKey() != right.consumerKey()
				|| left.consumerComputeNodeId() != right.consumerComputeNodeId()
				|| left.demandCostBits() != right.demandCostBits())
				return false;
		}
		return true;
	}

	private static void validateTransferAuthorityOwnership(PlacementAnalysis analysis,
		List<AuxiliaryGroupFact> groups, List<TransferAuthorityFact> authorities,
		List<MembershipRepresentative> representatives) {
		if(authorities == null)
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Transfer authority facts are missing");
		for(TransferAuthorityFact authority : authorities) {
			if(groups.stream().noneMatch(group -> group == authority.group())
				|| authority.group().endpointsInCanonicalOrder().stream()
					.noneMatch(endpoint -> endpoint == authority.endpoint())
				|| analysis.requireExactCompiledInputEdge(authority.endpoint().producerKey(),
					authority.endpoint().consumerKey(), authority.endpoint().inputPosition())
					!= authority.inputEdge())
				fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
					"Transfer authority is not retained by its exact owner");
			NeutralPlacementGraph.Node producer = analysis.graph().node(
				authority.group().producerKey()).orElseThrow();
			if(authority.sourceValueVersion() != producer.valueVersion())
				fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
					"Transfer authority source version differs from its exact producer");
			switch(authority.authorityKind()) {
				case RELOCATION_OBLIGATION -> validateRelocationAuthority(analysis, authority);
				case INDEPENDENT_ANCHOR -> validateIndependentAnchorAuthority(analysis, authority);
				case DURABLE_SOURCE -> validateDurableSourceAuthority(analysis, authority);
				case SELECTED_SOURCE_LOCAL_MATERIALIZATION ->
					validateSelectedSourceLocalMaterializationAuthority(analysis, authority, representatives);
			}
		}
	}

	private static void validateDurableSourceAuthority(PlacementAnalysis analysis,
		TransferAuthorityFact authority) {
		NeutralPlacementGraph.Node producer = analysis.graph().node(
			authority.group().producerKey()).orElseThrow();
		DurableAnchorKey anchor = authority.independentAnchorOrNull();
		String signature = "DURABLE_SOURCE|" + authority.group().direction() + '|'
			+ producer.valueVersion().normalizedSignature() + '|'
			+ authority.inputEdge().producer().normalizedSignature() + '|'
			+ authority.inputEdge().consumer().normalizedSignature() + '|'
			+ authority.inputEdge().inputPosition() + '|' + anchor.normalizedSignature() + '|'
			+ authority.requiredPlacement().normalizedSignature() + '|'
			+ authority.endpoint().demandCostBits();
		if(authority.group().direction() != Direction.DOWNLOAD || anchor == null
			|| producer.anchors().stream().noneMatch(candidate -> candidate == anchor)
			|| !producer.legalAlternatives().contains(authority.requiredPlacement())
			|| authority.requiredPlacement().output() != FederatedOutput.FOUT
			|| authority.requiredPlacement().fType() != anchor.fType()
			|| !authority.authoritySignature().equals(signature))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Durable-source transfer authority differs from its exact owner");
	}

	private static void validateSelectedSourceLocalMaterializationAuthority(PlacementAnalysis analysis,
		TransferAuthorityFact authority, List<MembershipRepresentative> representatives) {
		NeutralPlacementGraph.Node producer = analysis.graph().node(
			authority.group().producerKey()).orElseThrow();
		String proof = authority.producerMembershipProofOrNull();
		List<MembershipRepresentative> matching = representatives.stream()
			.filter(representative -> representative.decisionKey() == authority.group().producerKey()
				&& representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT
				&& representative.state().equals(authority.requiredPlacement())
				&& selectedSourceMembershipAuthorityKind(representative.authorityKind())
				&& Objects.equals(representativeProofSignature(representative), proof))
			.toList();
		String provenance = NormalizedPlannerResults.durableLocalProvenance(producer,
			authority.requiredPlacement());
		String signature = selectedSourceLocalMaterializationSignature(authority.group(),
			authority.endpoint(), authority.inputEdge(), producer.valueVersion(),
			authority.requiredPlacement(), provenance, proof);
		if(authority.group().direction() != Direction.DOWNLOAD
			|| authority.actionOrNull() != null || authority.obligationOrNull() != null
			|| authority.anchorInputEdgeOrNull() != null
			|| authority.independentAnchorOrNull() != null
			|| authority.consumerProfileOrNull() != null
			|| proof == null || proof.isBlank()
			|| matching.size() != 1
			|| authority.requiredPlacement().execType() != ExecType.FED
			|| authority.requiredPlacement().output() != FederatedOutput.FOUT
			|| authority.requiredPlacement().fType() != authority.group().conversionType()
			|| !authority.authoritySignature().equals(signature))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Selected-source local-materialization transfer authority differs from its exact owner");
	}

	private static void validateRelocationAuthority(PlacementAnalysis analysis,
		TransferAuthorityFact authority) {
		NeutralPlacementGraph.RelocationAction action = authority.actionOrNull();
		ObligationKey obligation = authority.obligationOrNull();
		NeutralPlacementGraph.Node producer = analysis.graph().node(
			authority.group().producerKey()).orElseThrow();
		if(action == null || obligation == null
			|| analysis.graph().relocationActions().stream().noneMatch(candidate -> candidate == action)
			|| action.obligations().stream().noneMatch(candidate -> candidate == obligation)
			|| action.key().sourceValueVersion() != producer.valueVersion()
			|| obligation.sourceValueVersion() != producer.valueVersion()
			|| obligation.relocationAction() != action.key()
			|| obligation.consumer() != authority.endpoint().consumerKey()
			|| obligation.inputPosition() != authority.endpoint().inputPosition()
			|| obligation.requiredPlacement() != action.key().targetPlacement()
			|| authority.requiredPlacement() != obligation.requiredPlacement()
			|| exactRelocationMaterializationType(analysis, authority.inputEdge(), action)
				!= authority.group().conversionType()
			|| !authority.authoritySignature().equals(action.normalizedSignature()))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Relocation transfer authority differs from its exact owner");
	}

	private static void validateIndependentAnchorAuthority(PlacementAnalysis analysis,
		TransferAuthorityFact authority) {
		CompiledInputEdgeFact anchorEdge = authority.anchorInputEdgeOrNull();
		DurableAnchorKey anchor = authority.independentAnchorOrNull();
		CandidateConsumerProfileFact profile = authority.consumerProfileOrNull();
		if(anchorEdge == null || anchor == null || profile == null
			|| authority.group().direction() != Direction.UPLOAD
			|| analysis.requireExactCompiledInputEdge(anchorEdge.producer(), anchorEdge.consumer(),
				anchorEdge.inputPosition()) != anchorEdge
			|| anchorEdge.consumer() != authority.endpoint().consumerKey()
			|| anchorEdge.inputPosition() == authority.endpoint().inputPosition()
			|| analysis.graph().node(anchorEdge.producer()).orElseThrow().anchors().stream()
				.noneMatch(candidate -> candidate == anchor)
			|| analysis.candidateConsumerProfileFacts().requireExact(
				authority.endpoint().consumerKey(), authority.endpoint().inputPosition()) != profile
			|| profile.status() != CandidateEvaluationStatus.AVAILABLE
			|| !profile.allowedTargetTypes().isEmpty()
				&& !profile.allowedTargetTypes().contains(authority.group().conversionType())
			|| PlacementCostSemantics.exactMaterializationFType(
				analysis.shapeFact(authority.group().producerKey()).orElseThrow(), anchor)
				!= authority.group().conversionType()
			|| authority.requiredPlacement().execType() != ExecType.FED
			|| authority.requiredPlacement().output() != FederatedOutput.FOUT
			|| authority.requiredPlacement().fType() != anchor.fType()
			|| authority.requiredPlacement().shapeDependent()
			|| !authority.authoritySignature().equals(independentAnchorSignature(authority.group(),
				authority.endpoint(), authority.inputEdge(), authority.sourceValueVersion(), anchorEdge, anchor, profile,
				authority.requiredPlacement())))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Independent-anchor transfer authority differs from its exact owner");
	}

	private static boolean sameTransferAuthorities(List<TransferAuthorityFact> expected,
		List<TransferAuthorityFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			TransferAuthorityFact left = expected.get(i), right = actual.get(i);
			if(left.group().auxiliaryNodeId() != right.group().auxiliaryNodeId()
				|| left.direction() != right.direction()
				|| left.authorityKind() != right.authorityKind()
				|| left.group().producerKey() != right.group().producerKey()
				|| left.endpoint().producerKey() != right.endpoint().producerKey()
				|| left.endpoint().consumerKey() != right.endpoint().consumerKey()
				|| left.endpoint().inputPosition() != right.endpoint().inputPosition()
				|| left.inputEdge() != right.inputEdge()
				|| left.sourceValueVersion() != right.sourceValueVersion()
				|| !left.requiredPlacement().equals(right.requiredPlacement())
				|| !left.authoritySignature().equals(right.authoritySignature())
				|| left.actionOrNull() != right.actionOrNull()
				|| left.obligationOrNull() != right.obligationOrNull()
				|| left.anchorInputEdgeOrNull() != right.anchorInputEdgeOrNull()
				|| left.independentAnchorOrNull() != right.independentAnchorOrNull()
				|| left.consumerProfileOrNull() != right.consumerProfileOrNull()
				|| !Objects.equals(left.producerMembershipProofOrNull(),
					right.producerMembershipProofOrNull()))
				return false;
		}
		return true;
	}

	private static boolean sameEdges(List<DirectedEdgeFact> expected, List<DirectedEdgeFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			DirectedEdgeFact left = expected.get(i), right = actual.get(i);
			if(left.fromNodeId() != right.fromNodeId() || left.toNodeId() != right.toNodeId()
				|| left.capacityBits() != right.capacityBits()
				|| !sameContributions(left.contributionsInDerivationOrder(),
					right.contributionsInDerivationOrder()))
				return false;
		}
		return true;
	}

	private static boolean sameContributions(List<EdgeContribution> expected,
		List<EdgeContribution> actual) {
		if(expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			EdgeContribution left = expected.get(i), right = actual.get(i);
			if(left.kind() != right.kind() || left.ownerKey() != right.ownerKey()
				|| left.peerKeyOrNull() != right.peerKeyOrNull()
				|| left.inputPosition() != right.inputPosition()
				|| left.costBits() != right.costBits()
				|| !left.provenance().equals(right.provenance()))
				return false;
		}
		return true;
	}

	private static boolean sameObligations(List<ObligationFact> expected,
		List<ObligationFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			ObligationFact left = expected.get(i), right = actual.get(i);
			if(!left.actionSignature().equals(right.actionSignature())
				|| left.endpointsInCanonicalOrder().size() != right.endpointsInCanonicalOrder().size())
				return false;
			for(int j = 0; j < left.endpointsInCanonicalOrder().size(); j++) {
				ObligationEndpointFact a = left.endpointsInCanonicalOrder().get(j);
				ObligationEndpointFact b = right.endpointsInCanonicalOrder().get(j);
				if(a.consumerKey() != b.consumerKey() || a.inputPosition() != b.inputPosition()
					|| a.requiredPlacement() != b.requiredPlacement())
					return false;
			}
		}
		return true;
	}

	private static String fingerprint(PlacementAnalysis analysis, List<CompiledHopKey> scope,
		List<DecisionFact> decisions, List<MembershipRepresentative> representatives,
		List<DirectedEdgeFact> edges,
		List<AuxiliaryGroupFact> groups, List<TransferAuthorityFact> transferAuthorities,
		List<ObligationFact> obligations) {
		StringBuilder normalized = new StringBuilder(analysis.analysisFingerprint());
		for(CompiledHopKey key : scope) normalized.append("|S:").append(key.normalizedSignature());
		for(DecisionFact decision : decisions) {
			normalized.append("|D:").append(decision.key().normalizedSignature()).append(':')
				.append(decision.computeNodeId()).append(':').append(decision.placementNodeId());
			for(PlacementState state : decision.legalStatesInCanonicalOrder())
				normalized.append(':').append(state.normalizedSignature());
		}
		appendCandidateAuthorityFacts(normalized, analysis);
		for(MembershipRepresentative representative : representatives) {
			normalized.append("|M:").append(representative.decisionKey().normalizedSignature())
				.append(':').append(representative.execType()).append(':').append(representative.output())
				.append(':').append(representative.state().normalizedSignature()).append(':')
				.append(representative.authorityKind());
			if(representative.durableAnchorOrNull() != null)
				normalized.append(":A:").append(representative.durableAnchorOrNull().normalizedSignature());
			if(representative.candidateRuleFactOrNull() != null)
				normalized.append(":R:").append(representative.candidateRuleFactOrNull().key()
					.parentOccurrence().normalizedSignature()).append(':')
					.append(representative.orderedInputs()).append(':')
					.append(representative.invocationEvidenceOrNull()).append(':')
					.append(candidateEmissionSignatureOrDash(representative));
			for(MembershipInputAuthorityFact inputAuthority : representative.inputAuthorityFacts())
				normalized.append(":I:")
					.append(inputAuthority.inputEdge().producer().normalizedSignature()).append('/')
					.append(inputAuthority.inputEdge().consumer().normalizedSignature()).append('/')
					.append(inputAuthority.inputPosition()).append('/')
					.append(inputAuthority.producerRepresentative().decisionKey().normalizedSignature()).append('/')
					.append(inputAuthority.producerRepresentative().state().normalizedSignature()).append('/')
					.append(inputAuthority.producerRepresentative().authorityKind()).append('/')
					.append(inputAuthority.authoritySignature());
			if(representative.relocationActionOrNull() != null)
				normalized.append(":L:").append(representative.relocationActionOrNull()
					.key().normalizedSignature());
			if(representative.authoritySignatureOrNull() != null)
				normalized.append(":S:").append(representative.authoritySignatureOrNull());
		}
		for(DirectedEdgeFact edge : edges) {
			normalized.append("|E:").append(edge.fromNodeId()).append('>').append(edge.toNodeId())
				.append(':').append(edge.capacityBits());
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder())
				normalized.append(':').append(contribution.kind()).append(':')
					.append(contribution.ownerKey().normalizedSignature()).append(':')
					.append(contribution.peerKeyOrNull() == null ? "-"
						: contribution.peerKeyOrNull().normalizedSignature()).append(':')
					.append(contribution.inputPosition()).append(':').append(contribution.costBits())
					.append(':').append(contribution.provenance());
		}
		for(AuxiliaryGroupFact group : groups) {
			normalized.append("|G:").append(group.auxiliaryNodeId()).append(':')
				.append(group.direction()).append(':').append(group.boundaryMode()).append(':')
				.append(group.producerKey().normalizedSignature())
				.append(':').append(group.producerComputeNodeId())
				.append(':').append(group.producerPlacementNodeId()).append(':')
				.append(group.uploadPriceTarget()).append(':')
				.append(group.conversionType()).append(':').append(group.priceBits());
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder())
				normalized.append(':').append(endpoint.producerKey().normalizedSignature()).append(':')
					.append(endpoint.inputPosition()).append(':')
					.append(endpoint.consumerKey().normalizedSignature()).append(':')
					.append(endpoint.consumerComputeNodeId()).append(':').append(endpoint.demandCostBits());
		}
		for(TransferAuthorityFact authority : transferAuthorities)
			normalized.append("|T:").append(authority.group().auxiliaryNodeId()).append(':')
				.append(authority.direction()).append(':')
				.append(authority.authorityKind()).append(':')
				.append(authority.endpoint().producerKey().normalizedSignature()).append(':')
				.append(authority.endpoint().consumerKey().normalizedSignature()).append(':')
				.append(authority.endpoint().inputPosition()).append(':')
				.append(authority.sourceValueVersion().normalizedSignature()).append(':')
				.append(authority.requiredPlacement().normalizedSignature()).append(':')
				.append(authority.authoritySignature()).append(':')
				.append(authority.actionOrNull() == null ? "-"
					: authority.actionOrNull().key().normalizedSignature()).append(':')
				.append(authority.obligationOrNull() == null ? "-"
					: authority.obligationOrNull().normalizedSignature()).append(':')
				.append(authority.anchorInputEdgeOrNull() == null ? "-"
					: authority.anchorInputEdgeOrNull().producer().normalizedSignature() + '/'
						+ authority.anchorInputEdgeOrNull().consumer().normalizedSignature() + '/'
						+ authority.anchorInputEdgeOrNull().inputPosition()).append(':')
				.append(authority.independentAnchorOrNull() == null ? "-"
					: authority.independentAnchorOrNull().normalizedSignature()).append(':')
				.append(authority.consumerProfileOrNull() == null ? "-"
					: authority.consumerProfileOrNull().key().consumerOccurrence().normalizedSignature() + "/"
						+ authority.consumerProfileOrNull().key().inputPosition() + "/"
						+ authority.consumerProfileOrNull().status() + "/"
						+ authority.consumerProfileOrNull().allowedTargetTypes() + "/"
						+ authority.consumerProfileOrNull().failureCode()).append(':')
				.append(authority.producerMembershipProofOrNull() == null ? "-"
					: authority.producerMembershipProofOrNull());
		for(ObligationFact obligation : obligations) {
			normalized.append("|O:").append(obligation.actionSignature());
			for(ObligationEndpointFact endpoint : obligation.endpointsInCanonicalOrder())
				normalized.append(':').append(endpoint.consumerKey().normalizedSignature()).append(':')
					.append(endpoint.inputPosition()).append(':')
					.append(endpoint.requiredPlacement().normalizedSignature());
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for(byte value : digest) hex.append(String.format("%02x", value));
			return hex.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static void appendCandidateAuthorityFacts(StringBuilder normalized,
		PlacementAnalysis analysis) {
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			normalized.append("|CR:").append(fact.key().parentOccurrence().normalizedSignature())
				.append(':').append(fact.key().orderedInputs()).append(':').append(fact.status());
			CandidateCapabilityFact capability = fact.capability();
			if(capability == null)
				normalized.append(":C:-");
			else
				normalized.append(":C:").append(capability.category()).append(':')
					.append(capability.opcode()).append(':').append(capability.nativeExec()).append(':')
					.append(capability.nativeOutput()).append(':').append(capability.nativeFoutFType())
					.append(':').append(capability.reasonCode()).append(':').append(capability.detail())
					.append(':').append(capability.notes());
			normalized.append(":S:").append(fact.shapeProof().consultedFacts()).append(':')
				.append(fact.shapeProof().requiredFacts()).append(':')
				.append(fact.shapeProof().missingRequiredFacts())
				.append(":P:").append(fact.profile().producerOutputs()).append(':')
				.append(fact.profile().evaluationFailure()).append(":E:");
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				normalized.append('[').append(emission.normalizedSignature()).append(']');
			normalized.append(":F:").append(fact.failureCode());
		}
		for(CandidateConsumerProfileFact fact : analysis.candidateConsumerProfileFacts().orderedFacts())
			normalized.append("|CC:").append(fact.key().consumerOccurrence().normalizedSignature())
				.append(':').append(fact.key().inputPosition()).append(':').append(fact.status())
				.append(':').append(fact.allowedTargetTypes()).append(':').append(fact.failureCode());
		for(DetachedConsumerProfileFact fact : analysis.detachedConsumerProfileFacts().orderedFacts())
			normalized.append("|DC:").append(fact.key().producerOccurrence().normalizedSignature())
				.append(':').append(fact.key().parentOrdinal()).append(':')
				.append(fact.key().normalizedConsumerSignature()).append(':')
				.append(fact.key().producerInputPositions()).append(':').append(fact.status())
				.append(':').append(fact.allowedTargetTypes()).append(':').append(fact.failureCode());
	}

	private static void fail(ValidationReason reason, String message) {
		throw new ValidationException(reason, message);
	}

	private static final class EdgeAccumulator {
		private final Map<EdgeKey,List<PendingEdgeContribution>> contributions = new LinkedHashMap<>();

		void add(long from, long to, double cost, ContributionKind kind,
			CompiledHopKey owner, CompiledHopKey peer, int inputPosition, String provenance) {
			boolean hard = Double.doubleToRawLongBits(cost)
				== Double.doubleToRawLongBits(HARD_LEGALITY);
			double canonical = hard ? 0.0 : requireCost(cost, "MINST_EDGE_COST_UNPROVEN");
			contributions.computeIfAbsent(new EdgeKey(from, to), ignored -> new ArrayList<>())
				.add(new PendingEdgeContribution(kind, owner, peer, inputPosition, canonical, provenance, hard));
		}

		List<DirectedEdgeFact> freeze() {
			double hardCapacity = certifiedHardCapacity();
			List<DirectedEdgeFact> result = new ArrayList<>(contributions.size());
			for(Map.Entry<EdgeKey,List<PendingEdgeContribution>> entry : contributions.entrySet()) {
				boolean hardEdge = entry.getValue().stream().anyMatch(PendingEdgeContribution::hard);
				boolean emittedHardReceipt = false;
				List<EdgeContribution> receipts = new ArrayList<>(entry.getValue().size());
				MinStCompensatedCostSum sum = new MinStCompensatedCostSum();
				for(PendingEdgeContribution pending : entry.getValue()) {
					double receiptCost;
					if(hardEdge) {
						receiptCost = pending.hard() && !emittedHardReceipt ? hardCapacity : 0.0;
						emittedHardReceipt |= pending.hard();
					}
					else
						receiptCost = pending.cost();
					EdgeContribution contribution = pending.freeze(receiptCost);
					receipts.add(contribution);
					sum.addBits(contribution.costBits(), "MINST_EDGE_COST_UNPROVEN",
						"MINST_EDGE_SUM_UNPROVEN");
				}
				result.add(new DirectedEdgeFact(entry.getKey().from, entry.getKey().to,
					sum.totalBits("MINST_EDGE_SUM_UNPROVEN"), receipts));
			}
			return List.copyOf(result);
		}

		/** Returns a finite H greater than the sum of all non-hard edge capacities. */
		private double certifiedHardCapacity() {
			BigDecimal finiteUpperBound = BigDecimal.ZERO;
			for(List<PendingEdgeContribution> edge : contributions.values()) {
				if(edge.stream().anyMatch(PendingEdgeContribution::hard))
					continue;
				MinStCompensatedCostSum edgeSum = new MinStCompensatedCostSum();
				for(PendingEdgeContribution contribution : edge)
					edgeSum.addBits(bits(contribution.cost()), "MINST_EDGE_COST_UNPROVEN",
						"MINST_EDGE_SUM_UNPROVEN");
				finiteUpperBound = finiteUpperBound.add(BigDecimal.valueOf(Double.longBitsToDouble(
					edgeSum.totalBits("MINST_EDGE_SUM_UNPROVEN"))));
			}
			if(finiteUpperBound.signum() == 0)
				return 1.0;
			double hard = finiteUpperBound.doubleValue();
			if(!Double.isFinite(hard))
				throw new IllegalArgumentException("MINST_EXACT_HARD_CAPACITY_UNREPRESENTABLE|finiteUpperBound="
					+ finiteUpperBound.toEngineeringString());
			while(BigDecimal.valueOf(hard).compareTo(finiteUpperBound) <= 0) {
				hard = Math.nextUp(hard);
				if(!Double.isFinite(hard))
					throw new IllegalArgumentException("MINST_EXACT_HARD_CAPACITY_UNREPRESENTABLE|finiteUpperBound="
						+ finiteUpperBound.toEngineeringString());
			}
			return hard;
		}
	}

	private record PendingEdgeContribution(ContributionKind kind, CompiledHopKey owner,
		CompiledHopKey peer, int inputPosition, double cost, String provenance, boolean hard) {
		private PendingEdgeContribution {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(provenance, "provenance");
		}

		private EdgeContribution freeze(double finalCost) {
			return new EdgeContribution(kind, owner, peer, inputPosition, bits(finalCost), provenance);
		}
	}

	private static final class EdgeKey {
		private final long from;
		private final long to;
		EdgeKey(long from, long to) { this.from = from; this.to = to; }
		@Override public boolean equals(Object other) {
			return other instanceof EdgeKey && from == ((EdgeKey)other).from && to == ((EdgeKey)other).to;
		}
		@Override public int hashCode() { return Long.hashCode(from) * 31 + Long.hashCode(to); }
	}

	private static final class GroupDemandKey {
		private final Direction direction;
		private final FType type;
		private final BoundaryMode boundaryMode;
		GroupDemandKey(Direction direction, FType type, BoundaryMode boundaryMode) {
			this.direction = direction;
			this.type = type;
			this.boundaryMode = boundaryMode;
		}
		@Override public boolean equals(Object other) {
			return other instanceof GroupDemandKey && direction == ((GroupDemandKey)other).direction
				&& type == ((GroupDemandKey)other).type
				&& boundaryMode == ((GroupDemandKey)other).boundaryMode;
		}
		@Override public int hashCode() {
			return (direction.hashCode() * 31 + type.hashCode()) * 31 + boundaryMode.hashCode();
		}
	}

	private static final class OccurrenceProfile {
		private final double networkWeight;
		private final List<Pair<Long,Double>> loopContext;
		private final Object contextIdentity;
		OccurrenceProfile(double networkWeight, List<Pair<Long,Double>> loopContext,
			Object contextIdentity) {
			this.networkWeight = requirePositiveWeight(networkWeight,
				"MINST_OCCURRENCE_WEIGHT_UNPROVEN");
			this.loopContext = List.copyOf(loopContext);
			this.contextIdentity = Objects.requireNonNull(contextIdentity, "contextIdentity");
		}
		boolean sameAs(OccurrenceProfile that) {
			return Double.doubleToRawLongBits(networkWeight)
					== Double.doubleToRawLongBits(that.networkWeight)
				&& loopContext.equals(that.loopContext);
		}
	}

	private static final class FunctionCallContext {
		private final double networkWeight;
		private final List<Pair<Long,Double>> loopContext;
		private final List<Map<String,List<Hop>>> transTables;
		private final List<String> callStack;
		FunctionCallContext(double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> transTables, List<String> callStack) {
			this.networkWeight = requirePositiveWeight(networkWeight,
				"MINST_FUNCTION_CALL_WEIGHT_UNPROVEN");
			this.loopContext = List.copyOf(loopContext);
			List<Map<String,List<Hop>>> copied = new ArrayList<>();
			for(Map<String,List<Hop>> table : transTables) {
				Map<String,List<Hop>> copiedTable = new LinkedHashMap<>();
				for(Map.Entry<String,List<Hop>> entry : table.entrySet())
					copiedTable.put(entry.getKey(), List.copyOf(entry.getValue()));
				copied.add(Collections.unmodifiableMap(copiedTable));
			}
			this.transTables = List.copyOf(copied);
			this.callStack = List.copyOf(callStack);
		}
		boolean sameAs(FunctionCallContext that) {
			if(Double.doubleToRawLongBits(networkWeight)
				!= Double.doubleToRawLongBits(that.networkWeight)
				|| !loopContext.equals(that.loopContext) || !callStack.equals(that.callStack)
				|| transTables.size() != that.transTables.size())
				return false;
			for(int tableIndex = 0; tableIndex < transTables.size(); tableIndex++) {
				Map<String,List<Hop>> left = transTables.get(tableIndex);
				Map<String,List<Hop>> right = that.transTables.get(tableIndex);
				if(!left.keySet().equals(right.keySet()))
					return false;
				for(String name : left.keySet()) {
					List<Hop> leftHops = left.get(name);
					List<Hop> rightHops = right.get(name);
					if(rightHops == null || leftHops.size() != rightHops.size())
						return false;
					for(int hopIndex = 0; hopIndex < leftHops.size(); hopIndex++)
						if(leftHops.get(hopIndex) != rightHops.get(hopIndex))
							return false;
				}
			}
			return true;
		}
	}

	private static final class Use {
		private final CompiledInputEdgeFact edge;
		private final DecisionFact consumerDecision;
		Use(CompiledInputEdgeFact edge, DecisionFact consumerDecision) {
			this.edge = Objects.requireNonNull(edge, "edge");
			this.consumerDecision = consumerDecision;
		}
	}

	private static final class Derivation {
		private final List<DecisionFact> decisions;
		private final List<MembershipRepresentative> representatives;
		private final List<DirectedEdgeFact> edges;
		private final List<AuxiliaryGroupFact> groups;
		private final List<TransferAuthorityFact> transferAuthorities;
		private final List<ObligationFact> obligations;
		private final String fingerprint;
		Derivation(List<DecisionFact> decisions, List<MembershipRepresentative> representatives,
			List<DirectedEdgeFact> edges,
			List<AuxiliaryGroupFact> groups, List<TransferAuthorityFact> transferAuthorities,
			List<ObligationFact> obligations, String fingerprint) {
			this.decisions = decisions;
			this.representatives = representatives;
			this.edges = edges;
			this.groups = groups;
			this.transferAuthorities = transferAuthorities;
			this.obligations = obligations;
			this.fingerprint = fingerprint;
		}
	}
}

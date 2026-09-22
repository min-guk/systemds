/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;

/**
 * Test-owned physical identity decoder for every raw assignment in an exact model.
 * The domain index and alternative index are retained: signatures alone need not
 * prove that two alternatives have the same provenance. This visitor makes no
 * claim that model construction included every possible physical alternative.
 */
final class ExactPhysicalPlanSpaceExporter {
	static final String MISSING_COVERAGE = "MODEL_DOMAIN_COMPLETENESS_UNPROVED";

	record Choice(int domainIndex, int alternativeIndex, String occurrence, String state,
		String authorityKind, String candidateRule, String candidateEmission,
		String executionRule, String executionEmission, String durableAnchor,
		String relocationAction, String derivedFoutAction, List<String> orderedInputs,
		List<String> inputAuthorities, String realization, String supportClause,
		String alternativeSignature, List<Map<String,Object>> orderedInputDetails,
		List<Map<String,Object>> inputAuthorityDetails, Map<String,Object> durableAnchorDetails,
		Map<String,Object> relocationDetails, Map<String,Object> derivedFoutDetails,
		Map<String,Object> occurrenceDetails, Map<String,Object> realizationDetails,
		Map<String,Object> supportDetails, String nodeKind, Map<String,Object> valueVersionDetails,
		String opcode, List<Map<String,Object>> compiledInputEdges) {
		Choice {
			orderedInputs = List.copyOf(orderedInputs);
			inputAuthorities = List.copyOf(inputAuthorities);
			orderedInputDetails = List.copyOf(orderedInputDetails);
			inputAuthorityDetails = List.copyOf(inputAuthorityDetails);
			compiledInputEdges = List.copyOf(compiledInputEdges);
		}
	}

	record Row(BigInteger ordinal, List<Integer> assignment, List<Choice> choices,
		ExactPhysicalRawSpaceExporter.Status modelStatus, String modelReason,
		String globalCoverageStatus, String globalCoverageReason) {
		Row {
			assignment = List.copyOf(assignment);
			choices = List.copyOf(choices);
		}
	}

	private ExactPhysicalPlanSpaceExporter() { }

	static BigInteger size(ExactPhysicalModel model) {
		return ExactPhysicalRawSpaceExporter.size(model);
	}

	/** Streams exactly [start, end) without holding the Cartesian product. */
	static void visit(ExactPhysicalModel model, BigInteger start, BigInteger end,
		Consumer<Row> sink) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(sink, "sink");
		ExactPhysicalRawSpaceExporter.visit(model, start, end, raw -> {
			List<Choice> choices = new ArrayList<>(raw.values().size());
			for(int index = 0; index < raw.values().size(); index++) {
				int value = raw.values().get(index);
				ExactPhysicalModel.DecisionDomain domain = model.domains().get(index);
				ExactPhysicalModel.Alternative alternative = domain.alternatives().get(value);
				if(!alternative.signature().equals(raw.alternativeSignatures().get(index))
					|| alternative.decision() != domain.node().key())
					throw new IllegalStateException("EXACT_PHYSICAL_RAW_IDENTITY_DRIFT");
				choices.add(new Choice(index, value,
					alternative.decision().normalizedSignature(),
					alternative.state().normalizedSignature(), alternative.authorityKind().name(),
					alternative.candidateRule() == null ? null
						: alternative.candidateRule().key().normalizedSignature(),
					alternative.candidateEmission() == null ? null
						: alternative.candidateEmission().selectionSignature(),
					alternative.executionRule() == null ? null
						: alternative.executionRule().key().normalizedSignature(),
					alternative.executionEmission() == null ? null
						: alternative.executionEmission().selectionSignature(),
					alternative.durableAnchor() == null ? null
						: alternative.durableAnchor().normalizedSignature(),
					alternative.relocationAction() == null ? null
						: alternative.relocationAction().normalizedSignature(),
					alternative.derivedFoutAction() == null ? null
						: alternative.derivedFoutAction().normalizedSignature(),
					alternative.orderedInputs().stream().map(input -> input.normalizedSignature()).toList(),
					alternative.inputAuthorities().stream()
						.map(ExactPhysicalModel.InputAuthority::signature).toList(),
					alternative.realization() == null ? null
						: alternative.realization().key().normalizedSignature(),
					alternative.supportClause() == null ? null
						: alternative.supportClause().normalizedSignature(),
					alternative.signature(),
					inputDetails(alternative), authorityDetails(alternative),
					anchorDetails(alternative.durableAnchor()),
					relocationDetails(alternative.relocationAction()),
					derivedFoutDetails(alternative.derivedFoutAction()),
					occurrenceDetails(alternative.decision()),
					realizationDetails(alternative), supportDetails(alternative),
					domain.node().kind().name(), valueVersionDetails(domain.node().valueVersion()),
					alternative.candidateRule() == null ? null
						: alternative.candidateRule().capability().opcode(),
					compiledInputEdges(model, domain)));
			}
			sink.accept(new Row(raw.ordinal(), raw.values(), choices, raw.status(), raw.reason(),
				"UNKNOWN", MISSING_COVERAGE));
		});
	}

	private static List<Map<String,Object>> inputDetails(ExactPhysicalModel.Alternative alternative) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(int position = 0; position < alternative.orderedInputs().size(); position++) {
			var input = alternative.orderedInputs().get(position);
			result.add(fields("position", position, "presence", input.presence().name(),
				"fType", input.fType() == null ? null : input.fType().name()));
		}
		return List.copyOf(result);
	}

	private static List<Map<String,Object>> authorityDetails(ExactPhysicalModel.Alternative alternative) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(var authority : alternative.inputAuthorities())
			result.add(fields("position", authority.inputPosition(), "kind", authority.kind().name(),
				"expectedFType", authority.expectedFType() == null ? null : authority.expectedFType().name(),
				"sourceDecision", authority.sourceDecision() == null ? null
					: occurrenceDetails(authority.sourceDecision()),
				"relocationAction", authority.relocationAction() == null ? null
					: authority.relocationAction().normalizedSignature(),
				"relocationActionKey", authority.relocationAction() == null ? null
					: authority.relocationAction().key().normalizedSignature()));
		return List.copyOf(result);
	}

	private static Map<String,Object> anchorDetails(DurableAnchorKey anchor) {
		if(anchor == null)
			return null;
		List<Map<String,Object>> partitions = new ArrayList<>();
		for(AnchorPartition partition : anchor.partitions())
			partitions.add(fields("worker", partition.workerId(), "begin", partition.begin(),
				"end", partition.end()));
		return fields("placementId", anchor.placementId(), "fType", anchor.fType().name(),
			"partitions", List.copyOf(partitions));
	}

	private static Map<String,Object> relocationDetails(RelocationAction action) {
		if(action == null)
			return null;
		var key = action.key();
		List<Map<String,Object>> obligations = new ArrayList<>();
		for(var obligation : action.obligations())
			obligations.add(fields("consumer", occurrenceDetails(obligation.consumer()),
				"inputPosition", obligation.inputPosition(),
				"sourceValueVersion", valueVersionDetails(obligation.sourceValueVersion()),
				"requiredPlacement", obligation.requiredPlacement().normalizedSignature(),
				"callRecompileContext", obligation.callRecompileContext()));
		return fields("sourceValueVersion", valueVersionDetails(key.sourceValueVersion()),
			"targetPlacement", key.targetPlacement().normalizedSignature(),
			"materializationFType", key.materializationFType().name(),
			"anchor", anchorDetails(key.durableAnchor()), "statementBlockScope", key.statementBlockScope(),
			"compatibleConsumers", key.compatibleConsumers().stream()
				.map(ExactPhysicalPlanSpaceExporter::occurrenceDetails).toList(),
			"obligations", List.copyOf(obligations), "directSourcePlacements",
				action.directSourcePlacements().stream().map(state -> state.normalizedSignature()).toList());
	}

	private static Map<String,Object> derivedFoutDetails(DerivedFoutMaterializationAction action) {
		if(action == null)
			return null;
		var key = action.key();
		return fields("producer", occurrenceDetails(key.producer()),
			"producerValueVersion", valueVersionDetails(key.producerValueVersion()),
			"candidateRule", key.candidateRule().normalizedSignature(),
			"sourcePlacement", key.sourcePlacement().normalizedSignature(),
			"targetPlacement", key.targetPlacement().normalizedSignature(),
			"anchor", anchorDetails(key.durableAnchor()),
			"anchorOwner", occurrenceDetails(key.durableAnchorOwner()),
			"anchorOwnerFType", key.durableAnchorOwnerFType().name(),
			"materializationFType", key.materializationFType().name(),
			"statementBlockScope", key.statementBlockScope());
	}

	private static Map<String,Object> occurrenceDetails(CompiledHopKey key) {
		var region = key.controlRegion();
		return fields("programFingerprint", key.programFingerprint(),
			"functionNamespace", key.functionNamespace(), "callSitePath", key.callSitePath(),
			"recompileContext", key.recompileContext(), "emittedHopInstance", key.emittedHopInstance(),
			"sourceOrigin", key.canonicalSourceOrigin(),
			"controlRegion", fields("functionNamespace", region.functionNamespace(),
				"regionPath", region.regionPath(), "callSitePath", region.callSitePath(),
				"recompileContext", region.recompileContext()));
	}

	private static Map<String,Object> valueVersionDetails(ValueVersionKey key) {
		return fields("lexicalVariable", key.lexicalVariable(),
			"definingControlRegion", fields(
				"functionNamespace", key.definingControlRegion().functionNamespace(),
				"regionPath", key.definingControlRegion().regionPath(),
				"callSitePath", key.definingControlRegion().callSitePath(),
				"recompileContext", key.definingControlRegion().recompileContext()),
			"definitionOrdinal", key.definitionOrdinal(), "versionKind", key.versionKind().name(),
			"predecessorVersions", key.predecessorVersions());
	}

	private static List<Map<String,Object>> compiledInputEdges(ExactPhysicalModel model,
		ExactPhysicalModel.DecisionDomain domain) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(var edge : model.analysis().compiledInputEdgesInCanonicalOrder())
			if(edge.consumer() == domain.node().key())
				result.add(fields("producer", occurrenceDetails(edge.producer()),
					"consumer", occurrenceDetails(edge.consumer()), "inputPosition", edge.inputPosition()));
		return List.copyOf(result);
	}

	private static Map<String,Object> realizationDetails(ExactPhysicalModel.Alternative alternative) {
		if(alternative.realization() == null)
			return null;
		var key = alternative.realization().key();
		return fields("layoutKind", key.layoutKind().name(),
			"placement", key.emissionState().placementState().normalizedSignature(),
			"derivedFedFout", key.emissionState().derivedFedFout(),
			"anchor", anchorDetails(key.durableAnchor()), "nativeLineage", key.nativeLineage());
	}

	private static Map<String,Object> supportDetails(ExactPhysicalModel.Alternative alternative) {
		if(alternative.supportClause() == null)
			return null;
		var clause = alternative.supportClause();
		List<Map<String,Object>> proofs = new ArrayList<>();
		for(var proof : clause.proofDependencies())
			proofs.add(fields("kind", proof.kind().name(), "owner",
				proof.owner() == null ? null : occurrenceDetails(proof.owner()),
				"authoritySignature", proof.authoritySignature()));
		List<Map<String,Object>> bindings = new ArrayList<>();
		for(var binding : clause.inputBindings())
			bindings.add(fields("inputPosition", binding.inputPosition(), "kind", binding.kind().name(),
				"sourceRuleOwner", occurrenceDetails(binding.source().rule().parentOccurrence()),
				"sourceRuleInputs", binding.source().rule().orderedInputs().stream()
					.map(input -> input.normalizedSignature()).toList(),
				"sourceRealizationLayout", binding.source().realization().layoutKind().name(),
				"sourceRealizationAnchor", anchorDetails(binding.source().realization().durableAnchor()),
				"sourceNativeLineage", binding.source().realization().nativeLineage(),
				"relocationAction", binding.relocationAction() == null ? null
					: binding.relocationAction().normalizedSignature()));
		return fields("proofs", List.copyOf(proofs), "inputBindings", List.copyOf(bindings),
			"nativeWorkerPoolWitness", anchorDetails(clause.nativeWorkerPoolWitness()),
			"nativeWorkerPoolLayoutExact", clause.nativeWorkerPoolLayoutExact());
	}

	private static Map<String,Object> fields(Object... pairs) {
		Map<String,Object> result = new LinkedHashMap<>();
		for(int index = 0; index < pairs.length; index += 2)
			result.put((String) pairs[index], pairs[index + 1]);
		return Collections.unmodifiableMap(result);
	}
}

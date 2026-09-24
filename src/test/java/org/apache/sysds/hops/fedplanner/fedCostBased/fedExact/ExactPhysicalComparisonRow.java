/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.test.component.federated.placement.shadow.CurrentPPhysicalPlanRows;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PrebuilderSnapshot;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.parser.DMLProgram;

/** Structural, provenance-free physical identity for one accepted E assignment. */
public final class ExactPhysicalComparisonRow {
	private ExactPhysicalComparisonRow() { }

	/** Builds E independently and streams each accepted structural physical plan. */
	public static long streamFixture(String fixture, Consumer<Map<String,Object>> sink) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, fixture, sources);
		var snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var model = ExactPhysicalModel.build(analysis);
		AtomicLong count = new AtomicLong();
		ExactPhysicalPlanSpaceExporter.visit(model, java.math.BigInteger.ZERO,
			ExactPhysicalPlanSpaceExporter.size(model), raw -> {
				if(raw.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED) return;
				sink.accept(project(model, catalog, raw, fixture));
				count.incrementAndGet();
			});
		return count.get();
	}

	private static void registerSources(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerSources(child, fixture, sources);
	}

	static Map<String,Object> project(ExactPhysicalModel model, PlanSpaceComparisonIdentity catalog,
		ExactPhysicalPlanSpaceExporter.Row row, String logicalProgram) {
		if(row.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED)
			throw new IllegalArgumentException("Only accepted E assignments have a physical identity");
		if(row.choices().size() != model.domains().size())
			throw new IllegalArgumentException("Incomplete E assignment or logical program identity");
		for(int i = 0; i < row.choices().size(); i++) {
			var choice = row.choices().get(i);
			if(choice.domainIndex() != i || choice.alternativeIndex() != row.assignment().get(i))
				throw new IllegalArgumentException("E domain/assignment drift");
		}
		int[] assignment = new int[row.assignment().size()];
		for(int i = 0; i < assignment.length; i++) assignment[i] = row.assignment().get(i);
		return project(model, catalog, assignment, logicalProgram);
	}

	/** Projects a complete accepted assignment without rebuilding exporter-only choice metadata. */
	static Map<String,Object> project(ExactPhysicalModel model, PlanSpaceComparisonIdentity catalog,
		int[] assignment, String logicalProgram) {
		if(logicalProgram == null || logicalProgram.isBlank() || assignment == null
			|| assignment.length != model.domains().size())
			throw new IllegalArgumentException("Incomplete E assignment or logical program identity");
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected = new HashMap<>();
		Map<String,CompiledHopKey> keysByPath = new HashMap<>();
		Map<ValueVersionKey,Map<String,Object>> versions = new HashMap<>();
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners = new HashMap<>();
		for(int i = 0; i < assignment.length; i++) {
			var domain = model.domains().get(i);
			int alternativeIndex = assignment[i];
			if(alternativeIndex < 0 || alternativeIndex >= domain.alternatives().size())
				throw new IllegalArgumentException("E assignment outside domain at " + i);
			var alternative = domain.alternatives().get(alternativeIndex);
			selected.put(domain.node().key(), alternative);
			if(keysByPath.putIfAbsent(catalog.occurrence(domain.node().key()), domain.node().key()) != null)
				throw new IllegalArgumentException("Ambiguous E source occurrence");
			versions.put(domain.node().valueVersion(), version(catalog, domain.node().key(),
				domain.node().valueVersion()));
			versionOwners.computeIfAbsent(domain.node().valueVersion(), ignored -> new ArrayList<>())
				.add(domain.node().key());
		}
		List<Map<String,Object>> nodes = new ArrayList<>();
		List<Map<String,Object>> authorities = new ArrayList<>();
		Set<Map<String,Object>> actions = new LinkedHashSet<>();
		Set<Map<String,Object>> geometry = new LinkedHashSet<>();
		Map<CompiledHopKey,Map<String,Object>> authorityByKey = new HashMap<>();
		for(var domain : model.domains()) {
			var alternative = required(selected, domain.node().key());
			Map<String,Object> owner = occurrence(catalog, domain.node().key());
			String kind = executionRule(alternative) == null ? "SYNTHETIC_BOUNDARY" : "CANDIDATE";
			String layout = alternative.realization() == null ? "BOUNDARY"
				: alternative.realization().key().layoutKind().name();
			DurableAnchorKey physicalAnchor = alternative.realization() == null ? null
				: alternative.realization().provenWorkerPool(alternative.supportClause());
			DurableAnchorKey nativeResidency = alternative.realization() == null
				|| !layout.equals("NATIVE_LINEAGE") ? null
				: alternative.realization().nativeWorkerPoolResidencyWitness(alternative.supportClause());
			Map<String,Object> authorityId = physicalAnchor == null
				? fields("owner", owner, "kind", kind, "layout", layout)
				: fields("owner", owner, "kind", kind, "layout", layout,
					"anchor", anchor(physicalAnchor));
			if(layout.equals("NATIVE_LINEAGE") && physicalAnchor == null) {
				if(nativeResidency == null)
					throw new IllegalArgumentException("E native layout lacks structural worker authority");
				Map<String,Object> extended = new LinkedHashMap<>(authorityId);
				extended.put("workerResidency", workerResidency(nativeResidency));
				authorityId = Map.copyOf(extended);
			}
			Map<String,Object> source = catalog.nodeFor(domain.node().key());
			if(layout.equals("SOURCE_LINEAGE")) {
				if(!(source.get("externalSource") instanceof Map<?,?> external))
					throw new IllegalArgumentException("E source lineage lacks frozen external source");
				Map<String,Object> extended = new LinkedHashMap<>(authorityId);
				extended.put("externalSource", external);
				authorityId = Map.copyOf(extended);
			}
			authorities.add(fields("id", authorityId, "source", kind, "owner", owner, "kind", kind));
			authorityByKey.put(domain.node().key(), authorityId);
			nodes.add(fields("occurrence", owner, "opcode", source.get("operation"),
				"exec", alternative.state().execType().name(), "output", alternative.state().output().name(),
				"ftype", alternative.state().fType() == null ? "NONE" : alternative.state().fType().name(),
				"shapeDependent", alternative.state().shapeDependent(), "executionFType",
				executionEmission(alternative) == null || executionEmission(alternative).executionFType() == null
					? "NONE" : executionEmission(alternative).executionFType().name(),
				"valueVersion", version(catalog, domain.node().key(), domain.node().valueVersion()),
				"authorityRef", authorityId));
			addGeometry(geometry, owner, physicalAnchor);
			if(alternative.realization() != null) {
				if(layout.equals("DURABLE_MAP") && physicalAnchor == null)
					throw new IllegalArgumentException("E durable layout lacks exact geometry");
			}
			if(alternative.derivedFoutAction() != null)
				addDerivedFout(actions, geometry, catalog, versions, alternative.derivedFoutAction());
			for(var input : alternative.inputAuthorities())
				if(input.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
					addRelocation(actions, geometry, catalog, versions, versionOwners,
						input.relocationAction());
		}
		List<Map<String,Object>> bindings = new ArrayList<>(bindings(model, catalog, selected, keysByPath,
			authorityByKey, versions, versionOwners));
		nodes.sort(Comparator.comparing(node -> PlanSpaceComparisonIdentity.structuralSortKey(node.get("occurrence"))));
		bindings.sort(Comparator.comparing((Map<String,Object> binding) ->
			PlanSpaceComparisonIdentity.structuralSortKey(binding.get("consumer")))
			.thenComparingInt(binding -> (Integer) binding.get("inputPosition")));
		return fields("schema", "physical-plan-v1", "context", fields("logical", logicalProgram),
			"nodes", List.copyOf(nodes),
			"authority", List.copyOf(authorities), "actions", List.copyOf(actions),
			"bindings", List.copyOf(bindings), "logicalInputs", catalog.physicalLogicalInputs(),
			"geometry", List.copyOf(geometry));
	}

	/**
	 * Captures a typed, factorized program for the same physical projection as {@link #project}.
	 * Every alternative owns its local rows. Input bindings additionally name the exact producer
	 * domains whose selected authority/state completes the row. No source name is resolved by the
	 * artifact consumer.
	 */
	static Map<String,Object> compositionalProjection(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity catalog, String logicalProgram) {
		if(logicalProgram == null || logicalProgram.isBlank())
			throw new IllegalArgumentException("Missing logical program identity");
		Map<CompiledHopKey,Integer> positions = new HashMap<>();
		Map<String,CompiledHopKey> keysByPath = new HashMap<>();
		Map<ValueVersionKey,Map<String,Object>> versions = new HashMap<>();
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners = new HashMap<>();
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			positions.put(domain.node().key(), index);
			if(keysByPath.putIfAbsent(catalog.occurrence(domain.node().key()), domain.node().key()) != null)
				throw new IllegalArgumentException("Ambiguous E source occurrence");
			versions.put(domain.node().valueVersion(), version(catalog, domain.node().key(),
				domain.node().valueVersion()));
			versionOwners.computeIfAbsent(domain.node().valueVersion(), ignored -> new ArrayList<>())
				.add(domain.node().key());
		}
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> orderedProducers = orderedProducers(
			catalog, keysByPath);
		List<Map<String,Object>> variables = new ArrayList<>();
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			List<Map<String,Object>> alternatives = new ArrayList<>();
			for(var alternative : domain.alternatives())
				alternatives.add(projectionFragment(model, catalog, domain.node().key(), alternative,
					positions, keysByPath, versions, versionOwners, orderedProducers));
			variables.add(fields("domain", index, "occurrence", occurrence(catalog, domain.node().key()),
				"alternatives", List.copyOf(alternatives)));
		}
		List<Integer> nodeOrder = java.util.stream.IntStream.range(0, model.domains().size()).boxed()
			.sorted(Comparator.comparing(index -> PlanSpaceComparisonIdentity.structuralSortKey(
				occurrence(catalog, model.domains().get(index).node().key())))).toList();
		List<Integer> bindingOrder = nodeOrder.stream().filter(index ->
			model.domains().get(index).alternatives().stream().anyMatch(alternative ->
				!projectionBindings(model, catalog, model.domains().get(index).node().key(),
					alternative, positions, keysByPath, versions, versionOwners,
					orderedProducers).isEmpty())).toList();
		return fields("schema", "exact-physical-compositional-projection-v1",
			"logicalProgram", logicalProgram, "variables", List.copyOf(variables),
			"nodeOrder", nodeOrder, "bindingDomainOrder", bindingOrder,
			"logicalInputs", catalog.physicalLogicalInputs());
	}

	private static Map<CompiledHopKey,Map<Integer,CompiledHopKey>> orderedProducers(
		PlanSpaceComparisonIdentity catalog, Map<String,CompiledHopKey> keysByPath) {
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> result = new HashMap<>();
		for(Map<String,Object> edge : catalog.orderedInputs()) {
			CompiledHopKey consumer = keysByPath.get(edge.get("consumer"));
			if(consumer == null) continue;
			CompiledHopKey producer = keysByPath.get(edge.get("producer"));
			if(producer == null)
				throw new IllegalArgumentException("Emitted E consumer has unresolved source input");
			int position = (Integer) edge.get("inputPosition");
			if(result.computeIfAbsent(consumer, ignored -> new HashMap<>())
				.putIfAbsent(position, producer) != null)
				throw new IllegalArgumentException("Duplicate E source input slot");
		}
		return result;
	}

	private static Map<String,Object> projectionFragment(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity catalog, CompiledHopKey key, ExactPhysicalModel.Alternative alternative,
		Map<CompiledHopKey,Integer> positions, Map<String,CompiledHopKey> keysByPath,
		Map<ValueVersionKey,Map<String,Object>> versions,
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners,
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> orderedProducers) {
		Map<String,Object> owner = occurrence(catalog, key);
		String kind = executionRule(alternative) == null ? "SYNTHETIC_BOUNDARY" : "CANDIDATE";
		String layout = alternative.realization() == null ? "BOUNDARY"
			: alternative.realization().key().layoutKind().name();
		DurableAnchorKey physicalAnchor = alternative.realization() == null ? null
			: alternative.realization().provenWorkerPool(alternative.supportClause());
		DurableAnchorKey nativeResidency = alternative.realization() == null
			|| !layout.equals("NATIVE_LINEAGE") ? null
			: alternative.realization().nativeWorkerPoolResidencyWitness(alternative.supportClause());
		Map<String,Object> authorityId = physicalAnchor == null
			? fields("owner", owner, "kind", kind, "layout", layout)
			: fields("owner", owner, "kind", kind, "layout", layout,
				"anchor", anchor(physicalAnchor));
		if(layout.equals("NATIVE_LINEAGE") && physicalAnchor == null) {
			if(nativeResidency == null)
				throw new IllegalArgumentException("E native layout lacks structural worker authority");
			Map<String,Object> extended = new LinkedHashMap<>(authorityId);
			extended.put("workerResidency", workerResidency(nativeResidency));
			authorityId = Map.copyOf(extended);
		}
		Map<String,Object> source = catalog.nodeFor(key);
		if(layout.equals("SOURCE_LINEAGE")) {
			if(!(source.get("externalSource") instanceof Map<?,?> external))
				throw new IllegalArgumentException("E source lineage lacks frozen external source");
			Map<String,Object> extended = new LinkedHashMap<>(authorityId);
			extended.put("externalSource", external);
			authorityId = Map.copyOf(extended);
		}
		Map<String,Object> authority = fields("id", authorityId, "source", kind,
			"owner", owner, "kind", kind);
		Map<String,Object> node = fields("occurrence", owner, "opcode", source.get("operation"),
			"exec", alternative.state().execType().name(), "output", alternative.state().output().name(),
			"ftype", alternative.state().fType() == null ? "NONE" : alternative.state().fType().name(),
			"shapeDependent", alternative.state().shapeDependent(), "executionFType",
			executionEmission(alternative) == null || executionEmission(alternative).executionFType() == null
				? "NONE" : executionEmission(alternative).executionFType().name(),
			"valueVersion", version(catalog, key, model.analysis().graph().node(key).orElseThrow()
				.valueVersion()), "authorityRef", authorityId);
		Set<Map<String,Object>> actions = new LinkedHashSet<>();
		Set<Map<String,Object>> geometry = new LinkedHashSet<>();
		addGeometry(geometry, owner, physicalAnchor);
		if(alternative.realization() != null) {
			if(layout.equals("DURABLE_MAP") && physicalAnchor == null)
				throw new IllegalArgumentException("E durable layout lacks exact geometry");
		}
		if(alternative.derivedFoutAction() != null)
			addDerivedFout(actions, geometry, catalog, versions, alternative.derivedFoutAction());
		for(var input : alternative.inputAuthorities())
			if(input.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
				addRelocation(actions, geometry, catalog, versions, versionOwners,
					input.relocationAction());
		return fields("node", node, "authority", authority, "actions", List.copyOf(actions),
			"geometry", List.copyOf(geometry), "bindings",
			projectionBindings(model, catalog, key, alternative, positions, keysByPath,
				versions, versionOwners, orderedProducers));
	}

	private static List<Map<String,Object>> projectionBindings(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity catalog, CompiledHopKey consumer,
		ExactPhysicalModel.Alternative alternative, Map<CompiledHopKey,Integer> positions,
		Map<String,CompiledHopKey> keysByPath, Map<ValueVersionKey,Map<String,Object>> versions,
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners,
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> orderedProducers) {
		Map<Integer,CompiledHopKey> producers = new HashMap<>(orderedProducers.getOrDefault(
			consumer, Map.of()));
		Map<Integer,CandidateRealizationInputBinding> support = new HashMap<>();
		Map<Integer,List<PhiSource>> phiInputs = new HashMap<>();
		if(executionRule(alternative) != null) {
			for(CandidateRealizationInputBinding binding : alternative.supportClause().inputBindings()) {
				int position = binding.inputPosition();
				if(position < 0 || position >= alternative.orderedInputs().size())
					throw new IllegalArgumentException("E support input position outside selected rule");
				CompiledHopKey producer = binding.source().rule().parentOccurrence();
				CompiledHopKey prior = producers.putIfAbsent(position, producer);
				if(prior != null && !prior.equals(producer))
					throw new IllegalArgumentException("E support source disagrees with ordered source edge");
				if(support.putIfAbsent(position, binding) != null)
					throw new IllegalArgumentException("Two E support bindings share one input slot");
			}
			for(int position = 0; position < alternative.orderedInputs().size(); position++)
				if(!producers.containsKey(position)) {
					CfgInput resolved = cfgPredecessor(model, catalog, positions.keySet(), keysByPath,
						consumer, position);
					producers.put(position, resolved.direct() == null ? consumer : resolved.direct());
					if(!resolved.alternatives().isEmpty()) phiInputs.put(position, resolved.alternatives());
				}
			if(producers.size() != alternative.orderedInputs().size())
				throw new IllegalArgumentException("E selected candidate inputs are not fully represented");
		}
		List<Map<String,Object>> result = new ArrayList<>();
		for(var edge : producers.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			int position = edge.getKey();
			CompiledHopKey producer = edge.getValue();
			List<PhiSource> phi = phiInputs.get(position);
			String presence;
			String ftype = null;
			if(executionRule(alternative) != null) {
				var input = alternative.orderedInputs().get(position);
				presence = input.presence().name();
				ftype = input.fType() == null ? "NONE" : input.fType().name();
			}
			else presence = "PRESENT";
			CandidateRealizationInputBinding binding = support.get(position);
			if(phi != null && binding != null)
				throw new IllegalArgumentException("E PHI input also has exact support binding");
			List<ExactPhysicalModel.InputAuthority> choices = phi == null
				? alternative.inputAuthorities().stream()
					.filter(authority -> authority.inputPosition() == position)
					.filter(authority -> authority.sourceDecision() == null
						|| authority.sourceDecision().equals(producer)).toList()
				: List.of();
			if(phi == null && executionRule(alternative) != null && choices.isEmpty())
				throw new IllegalArgumentException("E selected input authority unavailable: consumer="
					+ catalog.occurrence(consumer) + " position=" + position);
			if(phi == null && choices.size() > 1)
				throw new IllegalArgumentException("E selected input authority ambiguous");
			var inputAuthority = choices.isEmpty() ? null : choices.get(0);
			String mode;
			if(phi != null) mode = "PHI";
			else if(binding != null && binding.kind().name().equals("LOGICAL_TRANSIENT"))
				mode = "LOGICAL_TRANSIENT";
			else if(inputAuthority != null
				&& inputAuthority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
				mode = "RELOCATION";
			else if(presence.equals("ABSENT_LOCAL")) mode = "ABSENT_LOCAL";
			else mode = "DIRECT_OR_FOUT";
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("consumer", occurrence(catalog, consumer));
			row.put("inputPosition", position);
			row.put("presence", presence);
			if(ftype == null) row.put("ftypeFromProducer", true);
			else row.put("ftype", ftype);
			row.put("mode", mode);
			if(phi == null) {
				Integer producerDomain = positions.get(producer);
				if(producerDomain == null)
					throw new IllegalArgumentException("E binding producer is absent from domains");
				row.put("producerDomain", producerDomain);
			}
			else {
				List<Map<String,Object>> alternatives = new ArrayList<>();
				for(PhiSource source : phi) {
					Integer producerDomain = positions.get(source.producer());
					if(producerDomain == null)
						throw new IllegalArgumentException("E PHI producer is absent from domains");
					alternatives.add(fields("producerDomain", producerDomain,
						"controlArm", source.controlArm()));
				}
				alternatives.sort(Comparator.comparing(source -> (String) source.get("controlArm")));
				row.put("producerAlternatives", List.copyOf(alternatives));
			}
			if(mode.equals("RELOCATION")) {
				if(inputAuthority == null || inputAuthority.relocationAction() == null)
					throw new IllegalArgumentException("E relocation authority lacks action");
				row.put("actionRef", relocationId(catalog, versions, versionOwners,
					inputAuthority.relocationAction()));
			}
			result.add(Map.copyOf(row));
		}
		return List.copyOf(result);
	}

	private static ExactPhysicalModel.Alternative required(
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected, CompiledHopKey key) {
		var result = selected.get(key);
		if(result == null) throw new IllegalArgumentException("Unresolved E decision");
		return result;
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact executionRule(
		ExactPhysicalModel.Alternative alternative) {
		return alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact executionEmission(
		ExactPhysicalModel.Alternative alternative) {
		return alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
	}

	private static Map<String,Object> occurrence(PlanSpaceComparisonIdentity catalog, CompiledHopKey key) {
		var source = catalog.occurrenceDetails(key);
		String path = (String) source.get("sourcePath");
		String namespace = (String) source.get("functionNamespace");
		String callSite = (String) source.get("callSitePath");
		String context = (String) source.get("recompileContext");
		return fields("sourceOrigin", path, "functionNamespace", namespace,
			"callSitePath", callSite, "recompileContext", context, "emittedInstance", path,
			"controlRegion", fields("functionNamespace", namespace,
				"regionPath", source.get("controlRegion"), "callSitePath", callSite,
				"recompileContext", context));
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> version(PlanSpaceComparisonIdentity catalog, CompiledHopKey key,
		ValueVersionKey nativeValue) {
		return CurrentPPhysicalPlanRows.valueVersion(catalog, key, nativeValue);
	}

	private static Map<String,Object> value(Map<ValueVersionKey,Map<String,Object>> versions,
		ValueVersionKey key) {
		var result = versions.get(key);
		if(result == null) throw new IllegalArgumentException("Unresolved E action value version");
		return result;
	}

	private record PhiSource(CompiledHopKey producer, String controlArm) { }
	private record CfgInput(CompiledHopKey direct, List<PhiSource> alternatives) { }
	private record CfgReference(String signature, boolean definition) { }

	private static List<Map<String,Object>> bindings(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity catalog, Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected,
		Map<String,CompiledHopKey> keysByPath, Map<CompiledHopKey,Map<String,Object>> authorityByKey,
		Map<ValueVersionKey,Map<String,Object>> versions,
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners) {
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> producers = new HashMap<>();
		Map<CompiledHopKey,Map<Integer,CandidateRealizationInputBinding>> support = new HashMap<>();
		Map<CompiledHopKey,Map<Integer,List<PhiSource>>> phiInputs = new HashMap<>();
		for(Map<String,Object> edge : catalog.orderedInputs()) {
			CompiledHopKey consumer = keysByPath.get(edge.get("consumer"));
			if(consumer == null) continue;
			CompiledHopKey producer = keysByPath.get(edge.get("producer"));
			if(producer == null)
				throw new IllegalArgumentException("Emitted E consumer has unresolved source input");
			int position = (Integer) edge.get("inputPosition");
			if(producers.computeIfAbsent(consumer, ignored -> new HashMap<>())
				.putIfAbsent(position, producer) != null)
				throw new IllegalArgumentException("Duplicate E source input slot");
		}
		for(var entry : selected.entrySet()) {
			CompiledHopKey consumer = entry.getKey();
			var alternative = entry.getValue();
			if(executionRule(alternative) == null) continue;
			for(CandidateRealizationInputBinding binding : alternative.supportClause().inputBindings()) {
				int position = binding.inputPosition();
				if(position < 0 || position >= alternative.orderedInputs().size())
					throw new IllegalArgumentException("E support input position outside selected rule");
				CompiledHopKey producer = binding.source().rule().parentOccurrence();
				var sourceAlternative = selected.get(producer);
				if(sourceAlternative == null)
					throw new IllegalArgumentException("E support source is not emitted");
				CompiledHopKey prior = producers.computeIfAbsent(consumer, ignored -> new HashMap<>())
					.putIfAbsent(position, producer);
				if(prior != null && !prior.equals(producer))
					throw new IllegalArgumentException("E support source disagrees with ordered source edge");
				if(support.computeIfAbsent(consumer, ignored -> new HashMap<>())
					.putIfAbsent(position, binding) != null)
					throw new IllegalArgumentException("Two E support bindings share one input slot");
				if(sourceAlternative.realization() != null && !sourceAlternative.realization().key()
					.equals(binding.source().realization()))
					throw new IllegalArgumentException("E support source realization differs from emitted source");
			}
		}
		for(var entry : selected.entrySet()) {
			CompiledHopKey consumer = entry.getKey();
			var alternative = entry.getValue();
			if(executionRule(alternative) == null) continue;
			Map<Integer,CompiledHopKey> slots = producers.computeIfAbsent(consumer,
				ignored -> new HashMap<>());
			for(int position = 0; position < alternative.orderedInputs().size(); position++)
				if(!slots.containsKey(position)) {
					CfgInput resolved = cfgPredecessor(model, catalog, selected.keySet(), keysByPath,
						consumer, position);
					slots.put(position, resolved.direct() == null ? consumer : resolved.direct());
					if(!resolved.alternatives().isEmpty())
						phiInputs.computeIfAbsent(consumer, ignored -> new HashMap<>())
							.put(position, resolved.alternatives());
				}
		}
		List<Map<String,Object>> result = new ArrayList<>();
		for(var consumerRow : producers.entrySet()) {
			CompiledHopKey consumer = consumerRow.getKey();
			var alternative = required(selected, consumer);
			if(executionRule(alternative) != null
				&& consumerRow.getValue().size() != alternative.orderedInputs().size())
				throw new IllegalArgumentException("E selected candidate inputs are not fully represented");
			for(var edge : consumerRow.getValue().entrySet()) {
				int position = edge.getKey();
				CompiledHopKey producer = edge.getValue();
				List<PhiSource> phi = phiInputs.getOrDefault(consumer, Map.of()).get(position);
				String presence, ftype;
				if(executionRule(alternative) != null) {
					if(position < 0 || position >= alternative.orderedInputs().size())
						throw new IllegalArgumentException("E candidate input slot missing");
					var input = alternative.orderedInputs().get(position);
					presence = input.presence().name();
					ftype = input.fType() == null ? "NONE" : input.fType().name();
				}
				else {
					presence = "PRESENT";
					var state = required(selected, producer).state();
					ftype = state.fType() == null ? "NONE" : state.fType().name();
				}
				CandidateRealizationInputBinding binding = support.getOrDefault(consumer, Map.of())
					.get(position);
				if(phi != null && binding != null)
					throw new IllegalArgumentException("E PHI input also has exact support binding");
				List<ExactPhysicalModel.InputAuthority> choices = phi == null
					? alternative.inputAuthorities().stream()
						.filter(authority -> authority.inputPosition() == position)
						.filter(authority -> authority.sourceDecision() == null
							|| authority.sourceDecision().equals(producer)).toList()
					: List.of();
			if(phi == null && executionRule(alternative) != null && choices.isEmpty())
					throw new IllegalArgumentException("E selected input authority unavailable: consumer="
						+ catalog.occurrence(consumer) + " position=" + position
						+ " producer=" + catalog.occurrence(producer)
						+ " possible=" + alternative.inputAuthorities().stream()
							.map(ExactPhysicalModel.InputAuthority::signature).toList());
				if(phi == null && choices.size() > 1)
					throw new IllegalArgumentException("E selected input authority ambiguous");
				var inputAuthority = choices.isEmpty() ? null : choices.get(0);
				String mode;
				if(phi != null) mode = "PHI";
				else if(binding != null && binding.kind().name().equals("LOGICAL_TRANSIENT"))
					mode = "LOGICAL_TRANSIENT";
				else if(inputAuthority != null
					&& inputAuthority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
					mode = "RELOCATION";
				else if(presence.equals("ABSENT_LOCAL")) mode = "ABSENT_LOCAL";
				else if(required(selected, producer).state().output().name().equals("FOUT"))
					mode = "DIRECT_FOUT";
				else mode = "DIRECT";
				Map<String,Object> row = new LinkedHashMap<>();
				row.put("consumer", occurrence(catalog, consumer));
				row.put("inputPosition", position);
				row.put("producer", phi == null ? occurrence(catalog, producer)
					: fields("kind", "PHI_JOIN_PORT", "owner", occurrence(catalog, consumer)));
				row.put("presence", presence);
				row.put("ftype", ftype);
				row.put("inputAuthority", mode);
				if(phi == null) row.put("sourceAuthorityRef", authorityByKey.get(producer));
				else {
					List<Map<String,Object>> alternatives = phi.stream().map(source ->
						fields("producer", occurrence(catalog, source.producer()),
							"controlArm", source.controlArm(), "sourceAuthorityRef",
							authorityByKey.get(source.producer())))
						.sorted(Comparator.comparing(source -> (String) source.get("controlArm"))).toList();
					row.put("producerAlternatives", alternatives);
				}
				if(mode.equals("RELOCATION")) {
					if(inputAuthority == null || inputAuthority.relocationAction() == null)
						throw new IllegalArgumentException("E relocation authority lacks action");
					row.put("actionRef", relocationId(catalog, versions, versionOwners,
						inputAuthority.relocationAction()));
				}
				result.add(Map.copyOf(row));
			}
		}
		for(var entry : selected.entrySet())
			if(executionRule(entry.getValue()) != null && !entry.getValue().orderedInputs().isEmpty()
				&& !producers.containsKey(entry.getKey()))
				throw new IllegalArgumentException("E candidate has no represented ordered input authority");
		return List.copyOf(result);
	}

	private static CfgInput cfgPredecessor(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity catalog, Set<CompiledHopKey> available,
		Map<String,CompiledHopKey> keysByPath, CompiledHopKey consumer, int inputPosition) {
		ValueVersionKey version = model.analysis().graph().node(consumer).orElseThrow().valueVersion();
		List<CfgReference> references = new ArrayList<>();
		List<PhiSource> matched = new ArrayList<>();
		for(String predecessor : version.predecessorVersions()) {
			if(inputPosition == 0 && predecessor.startsWith("cfg-definition:"))
				references.add(new CfgReference(
					predecessor.substring("cfg-definition:".length()), true));
			else if(predecessor.startsWith("input-" + inputPosition + ":"))
				references.add(new CfgReference(
					predecessor.substring(("input-" + inputPosition + ":").length()), false));
			else if(inputPosition == 0 && predecessor.startsWith("cfg-function-output:")) {
				CompiledHopKey producer = PlanSpaceComparisonIdentity.cfgFunctionOutput(
					model.analysis(), consumer, predecessor);
				if(!available.contains(producer))
					throw new IllegalArgumentException("CFG function output is absent from E assignment");
				String arm = (String) catalog.occurrenceDetails(producer).get("callSitePath");
				if(arm == null || arm.isBlank())
					throw new IllegalArgumentException("E CFG function output arm unavailable");
				matched.add(new PhiSource(producer, arm));
			}
			else if(inputPosition == 0 && predecessor.startsWith("cfg-function-input:")) {
				List<Map<String,Object>> inputs = catalog.logicalInputs().stream()
					.filter(fact -> "FUNCTION_INPUT".equals(fact.get("kind"))
						&& catalog.occurrence(consumer).equals(fact.get("target")))
					.toList();
				if(inputs.isEmpty())
					throw new IllegalArgumentException("Unresolved E CFG function input source");
				for(Map<String,Object> input : inputs) {
					CompiledHopKey producer = keysByPath.get(input.get("source"));
					if(producer == null || !available.contains(producer))
						throw new IllegalArgumentException("CFG function input is absent from E assignment");
					String boundary = (String) input.get("boundary");
					if(boundary == null || boundary.isBlank())
						throw new IllegalArgumentException("E function input boundary unavailable");
					matched.add(new PhiSource(producer, boundary));
				}
			}
		}
		if(references.isEmpty() && matched.isEmpty())
			throw new IllegalArgumentException("Unresolved E CFG input authority: " + catalog.occurrence(consumer));
		for(CfgReference reference : references) {
			List<CompiledHopKey> matches = model.analysis().graph().nodes().stream()
				.filter(node -> available.contains(node.key()))
				.filter(node -> node.valueVersion().cfgReferenceSignature().equals(reference.signature()))
				// A TRead may share its writer's ValueVersionKey. Only a real TWrite or
				// function output is the owner of a cfg-definition reference.
				.filter(node -> !reference.definition() || model.analysis().hop(node.key())
					.filter(hop -> hop instanceof DataOp data &&
						(data.getOp() == OpOpData.TRANSIENTWRITE ||
							data.getOp() == OpOpData.FUNCTIONOUTPUT)).isPresent())
				.map(NeutralPlacementGraph.Node::key).toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("Ambiguous E CFG input authority: " + catalog.occurrence(consumer)
					+ " reference=" + reference + " matches=" + matches.stream()
						.map(key -> catalog.occurrence(key) + ":"
							+ model.analysis().graph().node(key).orElseThrow().kind()).toList());
			CompiledHopKey producer = matches.get(0);
			String arm = (String) catalog.occurrenceDetails(producer).get("callSitePath");
			if(arm == null || arm.isBlank()) throw new IllegalArgumentException("E CFG control arm unavailable");
			matched.add(new PhiSource(producer, arm));
		}
		if(matched.size() == 1) return new CfgInput(matched.get(0).producer(), List.of());
		if(matched.stream().map(PhiSource::controlArm).distinct().count() != matched.size())
			throw new IllegalArgumentException("E CFG alternatives share one control arm");
		return new CfgInput(null, List.copyOf(matched));
	}

	private static Map<String,Object> state(PlacementState state) {
		return fields("exec", state.execType().name(), "output", state.output().name(),
			"ftype", state.fType() == null ? "NONE" : state.fType().name(),
			"shapeDependent", state.shapeDependent());
	}

	private static Map<String,Object> anchor(DurableAnchorKey key) {
		if(key == null) throw new IllegalArgumentException("Missing E action anchor");
		List<Map<String,Object>> ranges = new ArrayList<>();
		for(var part : key.partitions())
			ranges.add(fields("worker", part.workerId(), "begin", part.begin(), "end", part.end()));
		return fields("ftype", key.fType().name(), "partitions", List.copyOf(ranges));
	}

	private static Map<String,Object> workerResidency(DurableAnchorKey witness) {
		List<String> raw = new ArrayList<>();
		for(var partition : witness.partitions()) {
			String endpoint = FederationUtils.canonicalFederatedWorkerAddress(partition.workerId());
			if(endpoint == null || endpoint.isBlank())
				throw new IllegalArgumentException("E native layout has invalid worker endpoint authority");
			raw.add(endpoint);
		}
		List<String> endpoints = raw.stream().distinct().sorted().toList();
		if(endpoints.isEmpty())
			throw new IllegalArgumentException("E native layout has no worker endpoint authority");
		return fields("ftype", witness.fType().name(), "endpoints", endpoints,
			"layoutExact", false);
	}

	private static void addGeometry(Set<Map<String,Object>> rows, Map<String,Object> owner,
		DurableAnchorKey key) {
		if(key == null) return;
		for(var partition : key.partitions())
			rows.add(fields("owner", owner, "worker", partition.workerId(),
				"ranges", List.of(partition.begin(), partition.end()), "ftype", key.fType().name()));
	}

	private static void addRelocation(Set<Map<String,Object>> rows, Set<Map<String,Object>> geometry,
		PlanSpaceComparisonIdentity catalog, Map<ValueVersionKey,Map<String,Object>> versions,
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners,
		RelocationAction action) {
		Map<String,Object> id = relocationId(catalog, versions, versionOwners, action);
		@SuppressWarnings("unchecked")
		Map<String,Object> owner = (Map<String,Object>) id.get("source");
		rows.add(fields("id", id, "kind", "RELOCATION", "owner", owner));
		addGeometry(geometry, owner, action.key().durableAnchor());
	}

	private static Map<String,Object> relocationId(PlanSpaceComparisonIdentity catalog,
		Map<ValueVersionKey,Map<String,Object>> versions,
		Map<ValueVersionKey,List<CompiledHopKey>> versionOwners, RelocationAction action) {
		var key = action.key();
		List<Map<String,Object>> demands = new ArrayList<>();
		for(var obligation : action.obligations())
			demands.add(fields("consumer", occurrence(catalog, obligation.consumer()),
				"inputPosition", obligation.inputPosition(), "requiredState",
				state(obligation.requiredPlacement())));
		demands.sort(Comparator.comparing(PlanSpaceComparisonIdentity::structuralSortKey));
		List<CompiledHopKey> owners = versionOwners.get(key.sourceValueVersion());
		if(owners == null || owners.size() != 1)
			throw new IllegalArgumentException("E relocation source owner unavailable or ambiguous");
		CompiledHopKey sourceOwner = owners.get(0);
		Map<String,Object> owner = occurrence(catalog, sourceOwner);
		Map<String,Object> id = fields("kind", "RELOCATION", "source", owner,
			"sourceValueVersion", value(versions, key.sourceValueVersion()),
			"target", state(key.targetPlacement()), "anchor", anchor(key.durableAnchor()),
			"obligations", List.copyOf(demands));
		return id;
	}

	private static void addDerivedFout(Set<Map<String,Object>> rows, Set<Map<String,Object>> geometry,
		PlanSpaceComparisonIdentity catalog, Map<ValueVersionKey,Map<String,Object>> versions,
		DerivedFoutMaterializationAction action) {
		var key = action.key();
		var owner = occurrence(catalog, key.producer());
		Map<String,Object> id = fields("kind", "DERIVED_FOUT", "producer", owner,
			"sourceValueVersion", value(versions, key.producerValueVersion()),
			"source", state(key.sourcePlacement()), "target", state(key.targetPlacement()),
			"anchor", anchor(key.durableAnchor()));
		rows.add(fields("id", id, "kind", "DERIVED_FOUT", "owner", owner));
		addGeometry(geometry, owner, key.durableAnchor());
	}

	private static Map<String,Object> fields(Object... pairs) {
		Map<String,Object> result = new LinkedHashMap<>();
		for(int i = 0; i < pairs.length; i += 2) {
			if(pairs[i + 1] == null)
				throw new IllegalArgumentException("Missing structural E coordinate: " + pairs[i]);
			result.put((String) pairs[i], pairs[i + 1]);
		}
		return Map.copyOf(result);
	}
}

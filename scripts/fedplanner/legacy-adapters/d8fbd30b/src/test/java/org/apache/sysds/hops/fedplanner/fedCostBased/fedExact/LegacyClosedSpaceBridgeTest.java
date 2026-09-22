/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assume;
import org.junit.Test;

/** Test-only observer of the unmodified d8fbd30b native production and exact domains. */
public final class LegacyClosedSpaceBridgeTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String VERSION = "d8fbd30b5476a1ceef460c9f3886381a369ac619";

	@Test public void exportRequestedRawRange() throws Exception {
		Assume.assumeTrue("legacy export must be explicitly requested", System.getProperty("legacy.fixture") != null);
		String fixture = required("legacy.fixture");
		String source = required("legacy.source");
		Path output = Path.of(required("legacy.output"));
		var program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		Path catalog = Path.of(required("legacy.catalog.output"));
		Files.createDirectories(catalog.toAbsolutePath().getParent());
		Files.writeString(catalog, JSON.writeValueAsString(sourceCatalog(analysis, fixture)) + "\n",
			StandardCharsets.UTF_8);
		Files.createDirectories(output.toAbsolutePath().getParent());
		try(BufferedWriter writer = output.toString().endsWith(".gz")
			? new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
				Files.newOutputStream(output), 1 << 16), StandardCharsets.UTF_8))
			: Files.newBufferedWriter(output)) {
			if(source.equals("E")) exportExact(analysis, fixture, writer);
			else if(source.equals("P")) exportProduction(analysis, fixture, writer);
			else throw new IllegalArgumentException("LEGACY_SOURCE_INVALID: " + source);
		}
	}

	/** Captures original compiler and builder coordinates before native rows are projected. */
	private static Map<String,Object> sourceCatalog(PlacementAnalysis analysis, String fixture)
		throws Exception {
		List<Map<String,Object>> nodes = new ArrayList<>();
		for(var node : analysis.graph().nodes()) {
			Hop hop = analysis.hop(node.key()).orElse(null);
			nodes.add(row("occurrence", node.key(), "valueVersion", node.valueVersion(),
				"kind", node.kind().name(), "emittedWork", node.emittedWork(),
				"sourceHop", hop == null ? null : row("class", hop.getClass().getName(),
					"operation", hop.getOpString(), "name", hop.getName(),
					"file", hop.getFilename(), "beginLine", hop.getBeginLine(),
					"beginColumn", hop.getBeginColumn(), "endLine", hop.getEndLine(),
					"endColumn", hop.getEndColumn()),
				"legalAlternatives", node.legalAlternatives(), "anchors", node.anchors()));
		}
		List<Map<String,Object>> inputs = new ArrayList<>();
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
			inputs.add(row("producer", edge.producer(), "consumer", edge.consumer(),
				"inputPosition", edge.inputPosition()));
		List<Map<String,Object>> logicalInputs = new ArrayList<>();
		for(var edge : analysis.logicalTransientInputsInCanonicalOrder())
			logicalInputs.add(row("kind", "TRANSIENT", "source", edge.sourceWrite(),
				"target", edge.targetRead(), "position", edge.logicalPosition(),
				"sourceValueVersion", edge.sourceValueVersion(),
				"readValueVersion", edge.readValueVersion()));
		for(var edge : analysis.logicalFunctionInputsInCanonicalOrder())
			logicalInputs.add(row("kind", "FUNCTION_INPUT", "source", edge.sourceArgument(),
				"boundary", edge.boundary(), "target", edge.targetRead(),
				"position", edge.logicalPosition(), "callInputPosition", edge.callInputPosition(),
				"sourceValueVersion", edge.sourceValueVersion(),
				"boundaryValueVersion", edge.boundaryValueVersion(),
				"readValueVersion", edge.readValueVersion()));
		return row("contract", "legacy-source-catalog-v1", "version", VERSION,
			"fixture", fixture, "sourcePrivacy", Privacy.PRIVATE_AGGREGATE.name(),
			"inputDmlSha256", inputDmlSha256(fixture), "nodes", nodes,
			"orderedInputs", inputs, "logicalInputs", logicalInputs,
			"constraints", analysis.graph().constraints());
	}

	private static void exportExact(PlacementAnalysis analysis, String fixture, BufferedWriter writer)
		throws Exception {
		String inputDmlSha256 = inputDmlSha256(fixture);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		BigInteger size = BigInteger.ONE;
		for(var domain : model.domains()) size = size.multiply(BigInteger.valueOf(domain.alternatives().size()));
		BigInteger[] range = range(size);
		Map<ExactCategoricalSolver.Variable,Integer> indexes = new IdentityHashMap<>();
		for(int i = 0; i < model.variables().size(); i++) indexes.put(model.variables().get(i), i);
		for(BigInteger ordinal = range[0]; ordinal.compareTo(range[1]) < 0; ordinal = ordinal.add(BigInteger.ONE)) {
			int[] values = decode(ordinal, model.domains().stream().mapToInt(d -> d.alternatives().size()).toArray());
			String status = "EMITTED", reason = "";
			for(int f = 0; f < model.hardFactors().size(); f++) {
				var factor = model.hardFactors().get(f);
				int[] local = factor.scope().stream().mapToInt(v -> values[indexes.get(v)]).toArray();
				try {
					double cost = factor.cost(local);
					if(cost == Double.POSITIVE_INFINITY) { status = "REJECTED"; reason = "hard-factor:" + f; break; }
					if(cost != 0.0 || Double.doubleToRawLongBits(cost) == Double.doubleToRawLongBits(-0.0d)) {
						status = "ERROR"; reason = "non-boolean-hard-factor:" + f; break;
					}
				}
				catch(RuntimeException ex) { status = "ERROR"; reason = "hard-factor-error:" + f + ':' + ex; break; }
			}
			List<Map<String,Object>> choices = new ArrayList<>();
			for(int i = 0; i < values.length; i++) {
				var a = model.domains().get(i).alternatives().get(values[i]);
				Map<String,Object> choice = row("position", i, "value", values[i],
					"occurrence", a.decision(), "state", a.state(),
					"authority", a.authorityKind().name(), "signature", a.signature(),
					"candidateRule", a.candidateRule() == null ? null : a.candidateRule().key(),
					"candidateEmission", a.candidateEmission(),
					"executionRule", a.executionRule() == null ? null : a.executionRule().key(),
					"executionEmission", a.executionEmission(),
					"anchor", a.durableAnchor(),
					"relocation", a.relocationAction() == null ? null : a.relocationAction().key(),
					"derivedFout", a.derivedFoutAction() == null ? null : a.derivedFoutAction().key(),
					"orderedInputs", a.orderedInputs(),
					"inputAuthorities", a.inputAuthorities(),
					"realization", a.realization() == null ? null : a.realization().key(),
					"supportClause", a.supportClause());
				choices.add(choice);
			}
			write(writer, row("contract", "legacy-native-audit-v1", "version", VERSION, "fixture", fixture,
				"sourcePrivacy", Privacy.PRIVATE_AGGREGATE.name(),
				"inputDmlSha256", inputDmlSha256,
				"source", "E", "ordinal", ordinal.toString(), "rawCount", size.toString(),
				"status", status, "reason", reason, "choices", choices,
				"comparisonLevel", "NATIVE_AUDIT_ONLY"));
		}
	}

	private static void exportProduction(PlacementAnalysis analysis, String fixture, BufferedWriter writer)
		throws Exception {
		String inputDmlSha256 = inputDmlSha256(fixture);
		NeutralPlacementGraph graph = analysis.graph();
		var nodes = graph.decisionNodes();
		Map<CompiledHopKey,Set<CandidateSelectionReceipt>> byOwner = new LinkedHashMap<>();
		for(var node : nodes) byOwner.put(node.key(), new LinkedHashSet<>());
		boolean[] nonDecisionOwner = {false};
		analysis.candidateRuleFacts().orderedFacts().forEach(fact -> {
			if(fact.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE) return;
			Set<CandidateSelectionReceipt> rows = byOwner.get(fact.key().parentOccurrence());
			if(rows == null) { nonDecisionOwner[0] = true; rows = byOwner.computeIfAbsent(
				fact.key().parentOccurrence(), ignored -> new LinkedHashSet<>()); }
			Set<CandidateSelectionReceipt> destination = rows;
			fact.allowedEmissionFacts().forEach(emission -> destination.addAll(
				analysis.canonicalCandidateReceipts(fact.key(), emission)));
		});
		List<List<CandidateSelectionReceipt>> candidates = byOwner.values().stream()
			.map(rows -> rows.stream().sorted().toList()).toList();
		Map<RelocationDemandKey,Set<RelocationChoiceReceipt>> byDemand = new LinkedHashMap<>();
		for(var action : graph.relocationActions()) for(var obligation : action.obligations()) {
			var demand = RelocationDemandKey.from(obligation);
			byDemand.computeIfAbsent(demand, ignored -> new LinkedHashSet<>())
				.add(new RelocationChoiceReceipt(demand, action.key()));
		}
		List<List<RelocationChoiceReceipt>> relocations = byDemand.keySet().stream().sorted()
			.map(key -> byDemand.get(key).stream().sorted().toList()).toList();
		int[] radix = new int[nodes.size() + candidates.size() + relocations.size()];
		int k = 0;
		for(var node : nodes) radix[k++] = node.legalAlternatives().size();
		for(var domain : candidates) radix[k++] = domain.size() + 1;
		for(var domain : relocations) radix[k++] = domain.size() + 1;
		BigInteger size = BigInteger.ONE;
		for(int r : radix) size = size.multiply(BigInteger.valueOf(r));
		BigInteger[] range = range(size);
		boolean compact = Boolean.getBoolean("legacy.compact.rows");
		for(BigInteger ordinal = range[0]; ordinal.compareTo(range[1]) < 0; ordinal = ordinal.add(BigInteger.ONE)) {
			int[] value = decodeLowEndian(ordinal, radix);
			Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
			List<Map<String,Object>> choices = new ArrayList<>();
			for(int i = 0; i < nodes.size(); i++) {
				var node = nodes.get(i);
				var state = node.legalAlternatives().get(value[i]);
				assignment.put(node.key(), state);
				if(!compact)
					choices.add(row("position", i, "value", value[i], "occurrence",
						node.key(), "state", state));
			}
			List<CandidateSelectionReceipt> selectedCandidates = new ArrayList<>();
			for(int i = 0; i < candidates.size(); i++)
				if(value[nodes.size()+i] != 0) selectedCandidates.add(candidates.get(i).get(value[nodes.size()+i]-1));
			List<RelocationChoiceReceipt> selectedRelocations = new ArrayList<>();
			for(int i = 0; i < relocations.size(); i++)
				if(value[nodes.size()+candidates.size()+i] != 0)
					selectedRelocations.add(relocations.get(i).get(value[nodes.size()+candidates.size()+i]-1));
			String status = "EMITTED", reason = "";
			if(nonDecisionOwner[0]) { status = "ERROR"; reason = "NON_DECISION_CANDIDATE_OWNER"; }
			else {
				for(var constraint : graph.constraints())
					if(assignment.containsKey(constraint.left()) && assignment.containsKey(constraint.right())
						&& !NeutralPlacementGraph.constraintSatisfied(constraint,
							assignment.get(constraint.left()), assignment.get(constraint.right()))) {
						status = "REJECTED"; reason = "graph-constraint"; break;
					}
				if(status.equals("EMITTED")) try {
					CandidateSelections.resolveAndValidate(analysis, graph.relocationActions(), assignment, selectedCandidates);
					RelocationSelections.resolveAndValidate(analysis, assignment, selectedCandidates, selectedRelocations);
					CandidateSelections.validateRealizationSelections(analysis, assignment, selectedCandidates, selectedRelocations);
				}
				catch(IllegalArgumentException ex) { status = "REJECTED"; reason = ex.getMessage(); }
				catch(IllegalStateException ex) {
					status = ex.getMessage() != null && ex.getMessage().startsWith(
						"Active exact candidate has no source-reachable row:") ? "REJECTED" : "ERROR";
					reason = ex.getMessage();
				}
				if(status.equals("EMITTED") && !graph.derivedFoutMaterializationActions().isEmpty()) {
					status = "ERROR"; reason = "DERIVED_FOUT_AUTHORITY_UNEXPOSED";
				}
			}
			if(compact && !status.equals("EMITTED")) {
				write(writer, row("contract", "legacy-native-audit-v1", "version", VERSION,
					"fixture", fixture, "sourcePrivacy", Privacy.PRIVATE_AGGREGATE.name(),
					"inputDmlSha256", inputDmlSha256, "source", "P", "ordinal", ordinal.toString(),
					"rawCount", size.toString(), "status", status, "reason", reason,
					"comparisonLevel", "NATIVE_AUDIT_ONLY"));
				continue;
			}
			if(compact)
				for(int i = 0; i < nodes.size(); i++)
					choices.add(row("position", i, "value", value[i], "occurrence", nodes.get(i).key(),
						"state", nodes.get(i).legalAlternatives().get(value[i])));
			List<?> emittedRelocations = status.equals("EMITTED")
				? RelocationSelections.emittedActions(analysis, graph.relocationActions(), assignment,
					selectedCandidates, selectedRelocations) : List.of();
			write(writer, row("contract", "legacy-native-audit-v1", "version", VERSION, "fixture", fixture,
				"sourcePrivacy", Privacy.PRIVATE_AGGREGATE.name(),
				"inputDmlSha256", inputDmlSha256,
				"source", "P", "ordinal", ordinal.toString(), "rawCount", size.toString(),
				"status", status, "reason", reason, "choices", choices,
				"candidateReceipts", selectedCandidates,
				"relocationReceipts", selectedRelocations,
				"emittedRelocationActions", emittedRelocations,
				"comparisonLevel", "NATIVE_AUDIT_ONLY"));
		}
	}

	private static BigInteger[] range(BigInteger size) {
		BigInteger begin = new BigInteger(System.getProperty("legacy.begin", "0"));
		BigInteger end = new BigInteger(System.getProperty("legacy.end", size.toString()));
		if(begin.signum() < 0 || end.compareTo(begin) < 0 || end.compareTo(size) > 0)
			throw new IllegalArgumentException("LEGACY_RANGE_INVALID: " + begin + ":" + end + "/" + size);
		return new BigInteger[] {begin, end};
	}

	private static int[] decode(BigInteger ordinal, int[] radix) {
		int[] values = new int[radix.length];
		BigInteger remainder = ordinal;
		for(int i = radix.length - 1; i >= 0; i--) {
			BigInteger[] qr = remainder.divideAndRemainder(BigInteger.valueOf(radix[i]));
			values[i] = qr[1].intValueExact(); remainder = qr[0];
		}
		if(remainder.signum() != 0) throw new IllegalStateException("LEGACY_DECODE_OVERFLOW");
		return values;
	}

	private static int[] decodeLowEndian(BigInteger ordinal, int[] radix) {
		int[] values = new int[radix.length];
		BigInteger remainder = ordinal;
		for(int i = 0; i < radix.length; i++) {
			BigInteger[] qr = remainder.divideAndRemainder(BigInteger.valueOf(radix[i]));
			values[i] = qr[1].intValueExact(); remainder = qr[0];
		}
		if(remainder.signum() != 0) throw new IllegalStateException("LEGACY_DECODE_OVERFLOW");
		return values;
	}

	private static Map<String,Object> row(Object... fields) {
		Map<String,Object> result = new LinkedHashMap<>();
		for(int i = 0; i < fields.length; i += 2) result.put((String)fields[i], fields[i+1]);
		return result;
	}

	private static void write(BufferedWriter writer, Map<String,Object> row) throws Exception {
		writer.write(JSON.writeValueAsString(row)); writer.newLine();
	}

	private static String required(String key) {
		String value = System.getProperty(key);
		if(value == null || value.isBlank()) throw new IllegalArgumentException("LEGACY_PROPERTY_REQUIRED: " + key);
		return value;
	}

	private static String inputDmlSha256(String fixture) throws Exception {
		String script = ProductionShadowFixtureFactory.scripts().get(fixture);
		if(script == null) throw new IllegalArgumentException("LEGACY_FIXTURE_UNKNOWN: " + fixture);
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(script.getBytes(StandardCharsets.UTF_8)));
	}
}

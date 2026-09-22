/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;

/** Read-only native P model capture for one frozen planning cell; it does not certify runtime legality. */
public final class PlanningNativeModelCapture {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final Pattern SOURCE = Pattern.compile("source\\(\"([^\"]+)\"\\)");
	private static final Pattern FEDERATED = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*"
		+ "federated\\(addresses=list\\(([^\\n]+?)\\),\\s*ranges=");
	private static final Pattern ADDRESS = Pattern.compile("\"([^\"]+)\"");

	private PlanningNativeModelCapture() { }

	/** Shared, pinned compiler input only. P and E must build their own native acceptance models. */
	public record PreparedInput(DMLProgram program, String cellId, String conditionSha256,
		String programPath, String programSha256, Map<String,String> sourceFiles,
		Map<String,String> networkEnvironment, Map<String,String> workloadJvmProperties,
		boolean networkEnvironmentChecked,
		PrebuilderSnapshot preRewriteGraph, PrebuilderSnapshot finalGraph,
		Map<Long,PrebuilderSnapshot.ExternalSource> finalSources) { }

	private record Captured(Map<String,Object> summary, Map<String,Object> artifact) { }

	/** Usage: catalog.json evaluation-root cell-id. Environment must match the pinned network profile. */
	public static void main(String[] args) throws Exception {
		if(args.length == 3 && "--verify".equals(args[0])) {
			System.out.println(JSON.writeValueAsString(verifyArtifact(Path.of(args[1]), args[2])));
			return;
		}
		if(args.length != 3 && args.length != 4)
			throw new IllegalArgumentException("Expected: catalog.json evaluation-root cell-id [artifact.json.gz]");
		Map<String,Object> result = args.length == 4
			? captureWithArtifact(Path.of(args[0]), Path.of(args[1]), args[2], true, Path.of(args[3]))
			: captureResult(Path.of(args[0]), Path.of(args[1]), args[2], true);
		System.out.println(JSON.writeValueAsString(result));
		if("ERROR".equals(result.get("status")))
			throw new IllegalStateException((String) result.get("error"));
	}

	/** Offline structural check; it does not re-execute the production acceptance predicate. */
	public static Map<String,Object> verifyArtifact(Path artifactPath, String expectedSha256) throws Exception {
		byte[] plain;
		try(GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(artifactPath))) {
			plain = stream.readAllBytes();
		}
		if(!sha(plain).equals(expectedSha256))
			throw new IllegalArgumentException("Native model artifact digest differs");
		JsonNode artifact = JSON.readTree(plain);
		if(!"closed-native-model-artifact-v1".equals(artifact.path("schema").asText())
			|| !"PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED".equals(artifact.path("acceptance").asText()))
			throw new IllegalArgumentException("Unsupported native model artifact contract");
		JsonNode summary = artifact.path("summary");
		JsonNode domain = artifact.path("nativeDomain");
		if(!"COMPLETE".equals(summary.path("status").asText())
			|| !sha(JSON.writeValueAsBytes(domain)).equals(summary.path("nativeDomainSha256").asText()))
			throw new IllegalArgumentException("Native domain digest differs");
		for(String side : List.of("preRewriteGraph", "finalHopGraph")) {
			JsonNode graph = artifact.path(side);
			String expected = side.equals("preRewriteGraph") ? "preRewriteGraphSha256" : "finalHopGraphSha256";
			if(!sha(JSON.writeValueAsBytes(List.of(graph.path("blocks"), graph.path("roots"),
				graph.path("nodes"), graph.path("edges"), graph.path("functions"),
				graph.path("calls"), graph.path("inlinedCalls")))).equals(summary.path(expected).asText()))
				throw new IllegalArgumentException(side + " digest differs");
		}
		JsonNode placements = domain.path("placementDomains");
		JsonNode candidates = domain.path("candidateDomains");
		JsonNode relocations = domain.path("relocationDomains");
		JsonNode radices = domain.path("radices");
		if(!placements.isArray() || !candidates.isArray() || !relocations.isArray()
			|| !radices.isArray() || placements.size() != candidates.size()
			|| radices.size() != placements.size() + candidates.size() + relocations.size()
			|| placements.size() != summary.path("placementCoordinates").asInt(-1)
			|| candidates.size() != summary.path("candidateCoordinates").asInt(-1)
			|| relocations.size() != summary.path("relocationCoordinates").asInt(-1))
			throw new IllegalArgumentException("Native coordinate count differs");
		Set<String> owners = new LinkedHashSet<>();
		BigInteger product = BigInteger.ONE;
		for(int index = 0; index < radices.size(); index++) {
			JsonNode row = index < placements.size() ? placements.get(index)
				: index < placements.size() + candidates.size()
					? candidates.get(index - placements.size())
					: relocations.get(index - placements.size() - candidates.size());
			int expectedRadix = row.path(1).size() + (index < placements.size() ? 0 : 1);
			if(!row.isArray() || row.size() != 2 || !row.get(0).isTextual()
				|| !row.get(1).isArray() || expectedRadix < 1
				|| expectedRadix != radices.get(index).asInt(-1))
				throw new IllegalArgumentException("Native coordinate domain differs at " + index);
			if(index < placements.size() && !owners.add(row.get(0).asText()))
				throw new IllegalArgumentException("Duplicate placement coordinate");
			if(index >= placements.size() && index < placements.size() + candidates.size()
				&& !placements.get(index - placements.size()).get(0).equals(row.get(0)))
				throw new IllegalArgumentException("Candidate owner order differs from placements");
			product = product.multiply(BigInteger.valueOf(expectedRadix));
		}
		if(!product.toString().equals(summary.path("rawCount").asText()))
			throw new IllegalArgumentException("Native raw product differs");
		return Map.of("status", "STRUCTURE_VERIFIED", "cell", summary.path("cell").asText(),
			"artifactSha256", expectedSha256, "nativeDomainSha256",
			summary.path("nativeDomainSha256").asText(), "acceptance",
			"PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED");
	}

	static Map<String,Object> captureResult(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment) {
		try {
			return capture(catalogPath, evaluationRoot, cellId, requireEnvironment);
		}
		catch(Exception error) {
			return Map.of("schema", "closed-native-model-capture-v1", "cell", cellId, "source", "P_C0",
				"status", "ERROR", "errorClass", error.getClass().getName(),
				"error", String.valueOf(error.getMessage()), "stackTrace",
				java.util.Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString).toList());
		}
	}

	static Map<String,Object> capture(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment) throws Exception {
		return captureModel(catalogPath, evaluationRoot, cellId, requireEnvironment).summary();
	}

	/** Writes a complete descriptor and both compiler IR snapshots before reporting COMPLETE. */
	public static Map<String,Object> captureWithArtifact(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment, Path artifactPath) {
		try {
			Captured captured = captureModel(catalogPath, evaluationRoot, cellId, requireEnvironment);
			byte[] plain = JSON.writeValueAsBytes(captured.artifact());
			Path destination = artifactPath.toAbsolutePath().normalize();
			Files.createDirectories(destination.getParent());
			Path temporary = Files.createTempFile(destination.getParent(), ".native-model-", ".json.gz.tmp");
			try {
				try(GZIPOutputStream stream = new GZIPOutputStream(Files.newOutputStream(temporary))) {
					stream.write(plain);
				}
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			finally {
				Files.deleteIfExists(temporary);
			}
			Map<String,Object> summary = new LinkedHashMap<>(captured.summary());
			summary.put("artifactPath", destination.toString());
			summary.put("artifactSha256", sha(plain));
			summary.put("artifactBytes", plain.length);
			return summary;
		}
		catch(Exception error) {
			return Map.of("schema", "closed-native-model-capture-v1", "cell", cellId, "source", "P_C0",
				"status", "ERROR", "errorClass", error.getClass().getName(),
				"error", String.valueOf(error.getMessage()), "stackTrace",
				java.util.Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString).toList());
		}
	}

	private static Captured captureModel(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment) throws Exception {
		PreparedInput input = prepareInput(catalogPath, evaluationRoot, cellId, requireEnvironment);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(input.program());
		FullProductionJointPlanExport exporter = new FullProductionJointPlanExport(analysis);
		Map<String,Object> nativeDomain = domain(analysis, exporter);
		String domainSha = sha(JSON.writeValueAsBytes(nativeDomain));
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("schema", "closed-native-model-capture-v1");
		result.put("cell", cellId);
		result.put("source", "P_C0");
		result.put("status", "COMPLETE");
		result.put("inputStatus", "STATICALLY_VALIDATED");
		result.put("networkEnvironmentChecked", requireEnvironment);
		result.put("conditionSha256", input.conditionSha256());
		result.put("programPath", input.programPath());
		result.put("programSha256", input.programSha256());
		result.put("sourceFiles", input.sourceFiles());
		result.put("networkEnvironment", input.networkEnvironment());
		result.put("workloadJvmProperties", input.workloadJvmProperties());
		result.put("prebuilderNodes", input.preRewriteGraph().nodes().size());
		result.put("prebuilderEdges", input.preRewriteGraph().edges().size());
		result.put("preRewriteGraphSha256", graphSha(input.preRewriteGraph()));
		result.put("finalHopGraphSha256", graphSha(input.finalGraph()));
		result.put("finalHopNodes", input.finalGraph().nodes().size());
		result.put("finalHopEdges", input.finalGraph().edges().size());
		result.put("finalSourceHops", input.finalSources().size());
		result.put("compilerBoundary", "POST_REWRITE_HOPS_DAG_PRE_PLANNER");
		result.put("nativeDomainSha256", domainSha);
		result.put("radices", nativeDomain.get("radices"));
		result.put("rawCount", exporter.rawCount().toString());
		result.put("placementCoordinates", exporter.graph().decisionNodes().size());
		result.put("candidateCoordinates", exporter.graph().decisionNodes().size());
		result.put("relocationCoordinates", ((List<?>) nativeDomain.get("relocationDomains")).size());
		result.put("derivedFoutActions", exporter.graph().derivedFoutMaterializationActions().size());
		result.put("runtimeSemanticCoverage", "NOT_ASSESSED_BY_THIS_CONTRACT");
		result.put("physicalDecode", "UNRESOLVED");
		Map<String,Object> artifact = new LinkedHashMap<>();
		artifact.put("schema", "closed-native-model-artifact-v1");
		artifact.put("summary", result);
		artifact.put("nativeDomain", nativeDomain);
		artifact.put("preRewriteGraph", graphRows(input.preRewriteGraph()));
		artifact.put("finalHopGraph", graphRows(input.finalGraph()));
		artifact.put("finalSources", input.finalSources());
		artifact.put("acceptance", "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED");
		return new Captured(result, artifact);
	}

	/** The E bridge uses only this pinned post-rewrite program, never P's graph or validator. */
	public static PreparedInput prepareInput(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment) throws Exception {
		Path root = evaluationRoot.toAbsolutePath().normalize();
		JsonNode catalog = JSON.readTree(catalogPath.toFile());
		JsonNode cell = null;
		for(JsonNode item : catalog.path("cells"))
			if(cellId.equals(item.path("id").asText())) {
				cell = item;
				break;
			}
		if(cell == null || !"IN_SCOPE".equals(cell.path("inventoryStatus").asText())
			|| !"planning-snapshot".equals(cell.path("sourceBinding").path("kind").asText())
			|| !cell.path("sourceBinding").has("plannedCondition"))
			throw new IllegalArgumentException("Cell has no frozen planning condition: " + cellId);
		Map<String,String> pinned = new LinkedHashMap<>();
		cell.path("sourceFiles").fields().forEachRemaining(entry -> pinned.put(entry.getKey(), entry.getValue().asText()));
		if(pinned.isEmpty()) throw new IllegalArgumentException("Cell has no pinned source files");
		for(var entry : pinned.entrySet()) {
			Path path = root.resolve(entry.getKey()).normalize();
			if(!path.startsWith(root) || !Files.isRegularFile(path) || !sha(Files.readAllBytes(path)).equals(entry.getValue()))
				throw new IllegalArgumentException("Pinned planning input changed: " + entry.getKey());
		}
		JsonNode condition = cell.path("sourceBinding").path("plannedCondition");
		JsonNode testCase = condition.path("case");
		int workers = condition.path("workers").asInt(-1);
		if(workers < 1 || workers != testCase.path("workers").asInt(-1))
			throw new IllegalArgumentException("Worker condition differs from test case");
		JsonNode costEnvironment = condition.path("network").path("cost_environment");
		if(!costEnvironment.isObject()) throw new IllegalArgumentException("Missing network environment");
		Map<String,String> networkEnvironment = new LinkedHashMap<>();
		costEnvironment.fields().forEachRemaining(entry ->
			networkEnvironment.put(entry.getKey(), entry.getValue().asText()));
		if(requireEnvironment)
			costEnvironment.fields().forEachRemaining(entry -> {
				if(!entry.getValue().asText().equals(System.getenv(entry.getKey())))
					throw new IllegalArgumentException("Frozen network environment differs: " + entry.getKey());
			});
		Path templates = root.resolve("planning_study/native/input_templates");
		String relative = "w" + workers + "/" + testCase.path("program").asText();
		Path programPath = templates.resolve(relative).normalize();
		if(!programPath.startsWith(templates) || !Files.isRegularFile(programPath))
			throw new IllegalArgumentException("Missing frozen planning program: " + relative);
		String script = Files.readString(programPath, StandardCharsets.UTF_8);
		String scriptHash = sha(script.getBytes(StandardCharsets.UTF_8));
		if(!scriptHash.equals(testCase.path("program_sha256").asText()))
			throw new IllegalArgumentException("Planning program differs from condition");
		JsonNode context = JSON.readTree(root.resolve("planning_study/native/context-w" + workers + ".json").toFile());
		Path protocolPath = root.resolve("planning_study/native/protocol-w" + workers + ".json");
		if(!pinned.containsKey(root.relativize(protocolPath).toString()))
			throw new IllegalArgumentException("Planning protocol is not pinned");
		JsonNode protocol = JSON.readTree(protocolPath.toFile());
		if(!protocol.path("workload_jvm_options").isObject())
			throw new IllegalArgumentException("Frozen planning protocol has no workload JVM options");
		String programName = Path.of(testCase.path("program").asText()).getFileName().toString();
		if(!programName.endsWith(".dml"))
			throw new IllegalArgumentException("Planning program has no DML suffix");
		String workload = programName.substring(0, programName.length() - 4);
		JsonNode workloadOptions = protocol.path("workload_jvm_options").path(workload);
		if(!workloadOptions.isMissingNode() && !workloadOptions.isArray())
			throw new IllegalArgumentException("Malformed frozen workload JVM options: " + workload);
		Map<String,String> workloadJvmProperties = new LinkedHashMap<>();
		for(JsonNode option : workloadOptions) {
			String raw = option.asText();
			if(!raw.startsWith("-D") || raw.indexOf('=', 2) < 3)
				throw new IllegalArgumentException("Unsupported frozen workload JVM option: " + raw);
			String property = raw.substring(2, raw.indexOf('=', 2));
			String value = raw.substring(raw.indexOf('=', 2) + 1);
			if(workloadJvmProperties.putIfAbsent(property, value) != null)
				throw new IllegalArgumentException("Duplicate frozen workload JVM property: " + property);
			if(!value.equals(System.getProperty(property)))
				throw new IllegalArgumentException("Frozen workload JVM property differs: " + property);
		}
		if("P2_PREP".equals(workload)
			&& (workloadOptions.size() != 1 || !workloadOptions.get(0).asText().equals(
				context.path("p2_metadata_release").path("jvm_option").asText())))
			throw new IllegalArgumentException("P2 metadata release differs from protocol option");
		boolean contextMatch = false;
		for(JsonNode contextCase : context.path("cases"))
			if(testCase.equals(contextCase)) contextMatch = true;
		if(!contextMatch) throw new IllegalArgumentException("Planning case differs from context manifest");
		Map<String,PrebuilderSnapshot.ExternalSource> sourceFacts = sourceFacts(
			script, testCase, workers, root, pinned);
		String resolved = resolveImports(script, templates, root, pinned);
		DMLProgram program = ParserFactory.createParser().parse(programPath.toString(), resolved, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		Map<Long,PrebuilderSnapshot.ExternalSource> sourceIds = registerSources(program, sourceFacts);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sourceIds);
		PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
		// Production compilation analyzes the final rewritten HOP graph. Keep the
		// independent pre-builder snapshot, then acquire the source facts again:
		// rewrites may replace or clone source HOP objects and their test metadata.
		translator.rewriteHopsDAG(program);
		Map<Long,PrebuilderSnapshot.ExternalSource> finalSourceIds = registerSources(program, sourceFacts);
		PrebuilderSnapshot finalGraph = PrebuilderSnapshot.capture(program, finalSourceIds);
		PrebuilderSnapshotVerifier.assertMatches(program, finalGraph);
		return new PreparedInput(program, cellId,
			cell.path("sourceBinding").path("conditionSha256").asText(),
			root.relativize(programPath).toString(), scriptHash, Map.copyOf(pinned),
			Map.copyOf(networkEnvironment), Map.copyOf(workloadJvmProperties), requireEnvironment,
			snapshot, finalGraph,
			Map.copyOf(finalSourceIds));
	}

	private static Map<String,Object> graphRows(PrebuilderSnapshot graph) {
		Map<String,Object> rows = new LinkedHashMap<>();
		rows.put("blocks", graph.blocks());
		rows.put("roots", graph.roots());
		rows.put("nodes", graph.nodes());
		rows.put("edges", graph.edges());
		rows.put("functions", graph.functions());
		rows.put("calls", graph.calls());
		rows.put("inlinedCalls", graph.inlinedCalls());
		return rows;
	}

	private static Map<String,Object> domain(PlacementAnalysis analysis, FullProductionJointPlanExport exporter) {
		NeutralPlacementGraph graph = exporter.graph();
		List<Integer> radices = new ArrayList<>();
		List<Object> placementRows = new ArrayList<>();
		List<Object> nodes = new ArrayList<>();
		Map<CompiledHopKey,Set<CandidateSelectionReceipt>> candidates = new LinkedHashMap<>();
		for(var node : graph.decisionNodes()) {
			if(node.legalAlternatives().isEmpty())
				throw new IllegalStateException("Decision coordinate has empty placement domain: " + node.key());
			radices.add(node.legalAlternatives().size());
			placementRows.add(List.of(node.key().normalizedSignature(),
				node.legalAlternatives().stream().map(state -> state.normalizedSignature()).toList()));
			if(candidates.put(node.key(), new LinkedHashSet<>()) != null)
				throw new IllegalStateException("Duplicate decision coordinate: " + node.key());
		}
		for(var node : graph.nodes())
			nodes.add(List.of(node.key().normalizedSignature(), node.kind().name(),
				node.valueVersion().normalizedSignature(), node.legalAlternatives().stream()
					.map(state -> state.normalizedSignature()).toList(),
				node.anchors().stream().map(anchor -> anchor.normalizedSignature()).toList()));
		for(var fact : analysis.candidateRuleFacts().orderedFacts())
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE
				&& candidates.containsKey(fact.key().parentOccurrence()))
				for(var emission : fact.allowedEmissionFacts())
					candidates.get(fact.key().parentOccurrence()).addAll(
						analysis.canonicalCandidateReceipts(fact.key(), emission));
		List<Object> candidateRows = new ArrayList<>();
		for(var entry : candidates.entrySet()) {
			List<String> receipts = entry.getValue().stream()
				.map(CandidateSelectionReceipt::normalizedSignature).sorted().toList();
			candidateRows.add(List.of(entry.getKey().normalizedSignature(), receipts));
			radices.add(receipts.size() + 1);
		}
		Map<RelocationDemandKey,Set<RelocationChoiceReceipt>> relocations = new LinkedHashMap<>();
		for(var action : graph.relocationActions())
			for(var obligation : action.obligations()) {
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				relocations.computeIfAbsent(demand, ignored -> new LinkedHashSet<>())
					.add(new RelocationChoiceReceipt(demand, action.key()));
			}
		List<Object> relocationRows = new ArrayList<>();
		for(var demand : relocations.keySet().stream().sorted().toList()) {
			List<String> actions = relocations.get(demand).stream()
				.map(RelocationChoiceReceipt::normalizedSignature).sorted().toList();
			relocationRows.add(List.of(demand.normalizedSignature(), actions));
			radices.add(actions.size() + 1);
		}
		BigInteger product = BigInteger.ONE;
		for(int radix : radices) product = product.multiply(BigInteger.valueOf(radix));
		if(!product.equals(exporter.rawCount()))
			throw new IllegalStateException("Captured native radices differ from exporter raw domain");
		Map<String,Object> descriptor = new LinkedHashMap<>();
		descriptor.put("nodes", nodes);
		descriptor.put("placementDomains", placementRows);
		descriptor.put("candidateDomains", candidateRows);
		descriptor.put("relocationDomains", relocationRows);
		descriptor.put("constraints", graph.constraints().stream().map(c -> Map.of(
			"kind", c.kind().name(), "left", c.left().normalizedSignature(),
			"right", c.right().normalizedSignature(), "inputPosition", c.inputPosition(),
			"evidence", c.evidence(), "signature", c.normalizedSignature())).toList());
		descriptor.put("derivedFoutActions", graph.derivedFoutMaterializationActions().stream()
			.map(a -> a.normalizedSignature()).toList());
		descriptor.put("nonDecisionCandidateOwners", exporter.nonDecisionCandidateOwners().stream()
			.map(owner -> List.of(owner.key().normalizedSignature(), owner.role().name())).toList());
		descriptor.put("radices", radices);
		return descriptor;
	}

	private static String resolveImports(String script, Path templates, Path root,
		Map<String,String> pinned) throws Exception {
		Matcher matcher = SOURCE.matcher(script);
		StringBuffer result = new StringBuffer();
		while(matcher.find()) {
			Path imported = templates.resolve("common").resolve(matcher.group(1)).normalize();
			if(!imported.startsWith(templates.resolve("common")) || !pinned.containsKey(root.relativize(imported).toString()))
				throw new IllegalArgumentException("Unpinned planning import: " + matcher.group(1));
			matcher.appendReplacement(result, Matcher.quoteReplacement("source(\"" + imported + "\")"));
		}
		matcher.appendTail(result);
		if(Pattern.compile("\\bsource\\s*\\(").matcher(result).results().count()
			!= SOURCE.matcher(result).results().count())
			throw new IllegalArgumentException("Unresolved nonliteral planning import");
		return result.toString();
	}

	private static Map<String,PrebuilderSnapshot.ExternalSource> sourceFacts(String script,
		JsonNode testCase, int workers, Path root, Map<String,String> pinned) throws Exception {
		Map<String,PrebuilderSnapshot.ExternalSource> result = new LinkedHashMap<>();
		Matcher statements = FEDERATED.matcher(script);
		while(statements.find()) {
			String variable = statements.group(1);
			String metadataKey = variable.equals("X") || variable.equals("Xraw") ? "X" :
				variable.equals("Y") || variable.equals("y") || variable.equals("e") ? "Y" : "";
			JsonNode metadata = testCase.path("metadata").path(metadataKey);
			if(metadata.isMissingNode()) throw new IllegalArgumentException("No source metadata: " + variable);
			Matcher addresses = ADDRESS.matcher(statements.group(2));
			List<String> origins = new ArrayList<>();
			while(addresses.find()) origins.add(addresses.group(1));
			if(origins.size() != workers) throw new IllegalArgumentException("Source worker count differs: " + variable);
			String privacyName = metadata.path("privacy").asText().toUpperCase().replace('-', '_');
			if(!privacyName.equals("PUBLIC") && !privacyName.equals("PRIVATE_AGGREGATE"))
				throw new IllegalArgumentException("Unsupported source privacy: " + privacyName);
			for(String address : origins) {
				String file = address.substring(address.lastIndexOf('/') + 1);
				Path sidecar = root.resolve("planning_study/native/input_templates/w" + workers
					+ "/metadata/matrix_metadata/" + file + ".mtd").normalize();
				if(!pinned.containsKey(root.relativize(sidecar).toString()))
					throw new IllegalArgumentException("Unpinned source sidecar: " + sidecar);
				JsonNode part = JSON.readTree(sidecar.toFile());
				if(part.path("cols").asLong(-1) != metadata.path("cols").asLong(-2)
					|| !part.path("privacy").asText().equals(metadata.path("privacy").asText()))
					throw new IllegalArgumentException("Source sidecar differs from case metadata: " + sidecar);
			}
			if(result.put(variable, new PrebuilderSnapshot.ExternalSource(
				String.join(",", origins), privacyName, "ROW")) != null)
				throw new IllegalArgumentException("Duplicate source: " + variable);
		}
		if(result.isEmpty()) throw new IllegalArgumentException("No literal federated planning sources");
		return result;
	}

	private static Map<Long,PrebuilderSnapshot.ExternalSource> registerSources(DMLProgram program,
		Map<String,PrebuilderSnapshot.ExternalSource> facts) {
		Map<Long,PrebuilderSnapshot.ExternalSource> result = new LinkedHashMap<>();
		Set<String> seenNames = new LinkedHashSet<>();
		Set<Hop> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		registerBlocks(program.getStatementBlocks(), facts, result, seenNames, visited);
		for(FunctionStatementBlock function : program.getNamedNSFunctionStatementBlocks().values())
			registerBlocks(List.of(function), facts, result, seenNames, visited);
		if(!seenNames.equals(facts.keySet()))
			throw new IllegalArgumentException("Federated source HOP names differ from metadata: " + seenNames);
		return result;
	}

	private static void registerBlocks(List<StatementBlock> blocks,
		Map<String,PrebuilderSnapshot.ExternalSource> facts,
		Map<Long,PrebuilderSnapshot.ExternalSource> result, Set<String> seenNames, Set<Hop> visited) {
		for(StatementBlock block : blocks) {
			registerRoots(block.getHops(), facts, result, seenNames, visited);
			if(block instanceof IfStatementBlock conditional) {
				registerSource(conditional.getPredicateHops(), facts, result, seenNames, visited);
				IfStatement statement = (IfStatement) conditional.getStatement(0);
				registerBlocks(statement.getIfBody(), facts, result, seenNames, visited);
				registerBlocks(statement.getElseBody(), facts, result, seenNames, visited);
			}
			else if(block instanceof WhileStatementBlock loop) {
				registerSource(loop.getPredicateHops(), facts, result, seenNames, visited);
				registerBlocks(((WhileStatement) loop.getStatement(0)).getBody(), facts, result, seenNames, visited);
			}
			else if(block instanceof ForStatementBlock loop) {
				registerSource(loop.getFromHops(), facts, result, seenNames, visited);
				registerSource(loop.getToHops(), facts, result, seenNames, visited);
				registerSource(loop.getIncrementHops(), facts, result, seenNames, visited);
				registerBlocks(((ForStatement) loop.getStatement(0)).getBody(), facts, result, seenNames, visited);
			}
			else if(block instanceof FunctionStatementBlock function)
				registerBlocks(((FunctionStatement) function.getStatement(0)).getBody(),
					facts, result, seenNames, visited);
		}
	}

	private static void registerRoots(List<Hop> roots, Map<String,PrebuilderSnapshot.ExternalSource> facts,
		Map<Long,PrebuilderSnapshot.ExternalSource> result, Set<String> seenNames, Set<Hop> visited) {
		if(roots != null)
			for(Hop root : roots) registerSource(root, facts, result, seenNames, visited);
	}

	static void registerSource(Hop hop, Map<String,PrebuilderSnapshot.ExternalSource> facts,
		Map<Long,PrebuilderSnapshot.ExternalSource> result, Set<String> seenNames, Set<Hop> visited) {
		if(hop == null) return;
		if(!visited.add(hop)) return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			PrebuilderSnapshot.ExternalSource fact = facts.get(data.getName());
			if(fact == null) throw new IllegalArgumentException("Unrecognized source HOP: " + data.getName());
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.valueOf(fact.privacy()));
			if(result.putIfAbsent(data.getHopID(), fact) != null)
				throw new IllegalArgumentException("Federated source HOP ID repeated: " + data.getHopID());
			seenNames.add(data.getName());
		}
		for(Hop input : hop.getInput()) registerSource(input, facts, result, seenNames, visited);
	}

	private static String graphSha(PrebuilderSnapshot graph) throws Exception {
		return sha(JSON.writeValueAsBytes(List.of(graph.blocks(), graph.roots(), graph.nodes(),
			graph.edges(), graph.functions(), graph.calls(), graph.inlinedCalls())));
	}

	private static String sha(byte[] bytes) throws Exception {
		return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}

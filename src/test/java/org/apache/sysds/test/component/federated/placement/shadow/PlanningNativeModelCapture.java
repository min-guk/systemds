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

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.apache.sysds.api.DMLOptions;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
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
import org.apache.sysds.runtime.instructions.fed.FEDInstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;

/** Read-only native P model capture for one frozen planning cell; it does not certify runtime legality. */
public final class PlanningNativeModelCapture {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final Pattern SOURCE = Pattern.compile("source\\(\"([^\"]+)\"\\)");
	private static final Pattern FEDERATED = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*"
		+ "federated\\(addresses=list\\(([^\\n]+?)\\),\\s*ranges=");
	private static final Pattern ADDRESS = Pattern.compile("\"([^\"]+)\"");
	private static final Pattern FROZEN_FEDERATED = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*"
		+ "=\\s*federated\\(addresses=list\\((.*)\\),\\s*ranges=list\\((.*)\\)\\)\\s*;?\\s*$");
	private static final Pattern FROZEN_ADDRESS = Pattern.compile("\\G\\s*(?:,\\s*)?\"([^\"]+)\"");
	private static final Pattern FROZEN_RANGE = Pattern.compile(
		"\\G\\s*(?:,\\s*)?list\\(\\s*(-?[0-9]+)\\s*,\\s*(-?[0-9]+)\\s*\\)");

	private PlanningNativeModelCapture() { }

	/** Shared, pinned compiler input only. P and E must build their own native acceptance models. */
	public record PreparedInput(DMLProgram program, String cellId, String conditionSha256,
		String programPath, String programSha256, Map<String,String> sourceFiles,
		Map<String,String> networkEnvironment, Map<String,String> workloadJvmProperties,
		List<String> compilerArgv, Map<String,Object> compilerConfiguration,
		boolean networkEnvironmentChecked,
		PrebuilderSnapshot preRewriteGraph, PrebuilderSnapshot finalGraph,
		Map<Long,PrebuilderSnapshot.ExternalSource> finalSources) { }

	private record Captured(Map<String,Object> summary, Map<String,Object> artifact) { }
	private record CompilerConfiguration(List<String> argv, Map<String,Object> attestation) { }

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
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		JsonNode artifact;
		try(GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(artifactPath));
			DigestInputStream stream = new DigestInputStream(gzip, digest)) {
			artifact = JSON.readTree(stream);
		}
		if(!hex(digest.digest()).equals(expectedSha256))
			throw new IllegalArgumentException("Native model artifact digest differs");
		if(!"closed-native-model-artifact-v1".equals(artifact.path("schema").asText())
			|| !"PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED".equals(artifact.path("acceptance").asText()))
			throw new IllegalArgumentException("Unsupported native model artifact contract");
		JsonNode summary = artifact.path("summary");
		JsonNode domain = artifact.path("nativeDomain");
		if(!"COMPLETE".equals(summary.path("status").asText())
			|| !shaJson(domain).equals(summary.path("nativeDomainSha256").asText()))
			throw new IllegalArgumentException("Native domain digest differs");
		for(String side : List.of("preRewriteGraph", "finalHopGraph")) {
			JsonNode graph = artifact.path(side);
			String expected = side.equals("preRewriteGraph") ? "preRewriteGraphSha256" : "finalHopGraphSha256";
			if(!shaJson(List.of(graph.path("blocks"), graph.path("roots"),
				graph.path("nodes"), graph.path("edges"), graph.path("functions"),
				graph.path("calls"), graph.path("inlinedCalls"))).equals(summary.path(expected).asText()))
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
		verifyCandidateActivationFacts(domain, placements, candidates);
		return Map.of("status", "STRUCTURE_VERIFIED", "cell", summary.path("cell").asText(),
			"artifactSha256", expectedSha256, "nativeDomainSha256",
			summary.path("nativeDomainSha256").asText(), "acceptance",
			"PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED");
	}

	static void verifyCandidateActivationFacts(JsonNode domain, JsonNode placements,
		JsonNode candidates) {
		JsonNode facts = domain.path("candidateReceiptActivationFacts");
		JsonNode nodes = domain.path("nodes");
		JsonNode references = domain.path("candidateRealizationReferenceFacts");
		JsonNode supportAuthorities = domain.path("candidateRealizationSupportAuthorities");
		JsonNode relocationActions = domain.path("relocationActionFacts");
		JsonNode relocationWorkerPools = domain.path("relocationWorkerPoolAuthorities");
		JsonNode derivedActions = domain.path("derivedFoutActions");
		JsonNode semanticFacts = domain.path("candidateReceiptSemanticFacts");
		int expected = 0;
		for(JsonNode coordinate : candidates)
			expected += coordinate.path(1).size();
		if(!facts.isArray() || facts.size() != expected || !semanticFacts.isArray()
			|| semanticFacts.size() != expected || !supportAuthorities.isArray()
			|| supportAuthorities.size() != references.size())
			throw new IllegalArgumentException("Candidate activation fact coverage differs");
		for(int index = 0; index < supportAuthorities.size(); index++)
			if(supportAuthorities.get(index).path("referenceIndex").asInt(-1) != index
				|| !supportAuthorities.get(index).path("supportAuthorities").isArray()
				|| supportAuthorities.get(index).path("supportAuthorities").isEmpty())
				throw new IllegalArgumentException("Candidate realization support authority differs");
		if(!relocationWorkerPools.isArray()
			|| relocationWorkerPools.size() != relocationActions.size())
			throw new IllegalArgumentException("Relocation worker-pool authority coverage differs");
		for(JsonNode pool : relocationWorkerPools)
			if(!"EXACT_LAYOUT".equals(pool.path("kind").asText())
				|| !pool.path("partitions").isArray() || pool.path("partitions").isEmpty())
				throw new IllegalArgumentException("Relocation worker-pool authority malformed");
		verifyLogicalAuthority(domain.path("logicalCandidateCoordinateAuthority"), nodes, placements,
			references);
		Set<String> seen = new LinkedHashSet<>();
		int factIndex = 0;
		for(JsonNode fact : facts) {
			int coordinate = fact.path("coordinateIndex").asInt(-1);
			int candidate = fact.path("candidateIndex").asInt(-1);
			int placementCoordinate = fact.path("ownerPlacementCoordinateIndex").asInt(-1);
			int alternative = fact.path("activePlacementAlternativeIndex").asInt(-1);
			int ownReference = fact.path("realizationReferenceIndex").asInt(-1);
			int supportAuthority = fact.path("supportAuthorityIndex").asInt(-1);
			JsonNode semantic = semanticFacts.get(factIndex++);
			if(coordinate < 0 || coordinate >= candidates.size() || placementCoordinate != coordinate
				|| candidate < 0 || candidate >= candidates.get(coordinate).path(1).size()
				|| alternative < 0 || alternative >= placements.get(coordinate).path(1).size()
				|| coordinate != semantic.path("coordinateIndex").asInt(-1)
				|| candidate != semantic.path("candidateIndex").asInt(-1)
				|| !semantic.path("placement").equals(placements.get(coordinate).path(1).get(alternative))
				|| !fact.path("receiptSha256").asText().equals(
					shaText(candidates.get(coordinate).path(1).get(candidate).asText()))
				|| ownReference < 0 || ownReference >= references.size()
				|| supportAuthority < 0
				|| supportAuthority >= supportAuthorities.get(ownReference)
					.path("supportAuthorities").size()
				|| !references.get(ownReference).path("owner")
					.equals(candidates.get(coordinate).path(0))
				|| !references.get(ownReference).path("placement")
					.equals(placements.get(coordinate).path(1).get(alternative))
				|| !seen.add(coordinate + ":" + candidate))
				throw new IllegalArgumentException("Candidate activation coordinate differs: fact="
					+ factIndex + " coordinate=" + coordinate + " candidate=" + candidate
					+ " placementCoordinate=" + placementCoordinate + " alternative=" + alternative
					+ " semanticCoordinate=" + semantic.path("coordinateIndex").asInt(-1)
					+ " semanticCandidate=" + semantic.path("candidateIndex").asInt(-1)
					+ " semanticPlacement=" + semantic.path("placement").asText()
					+ " placement=" + (coordinate >= 0 && coordinate < placements.size()
						&& alternative >= 0 && alternative < placements.get(coordinate).path(1).size()
						? placements.get(coordinate).path(1).get(alternative).asText() : "-")
					+ " receiptHash=" + fact.path("receiptSha256").asText()
					+ " expectedReceiptHash=" + (coordinate >= 0 && coordinate < candidates.size()
						&& candidate >= 0 && candidate < candidates.get(coordinate).path(1).size()
						? shaText(candidates.get(coordinate).path(1).get(candidate).asText()) : "-"));
			if(!fact.path("inputs").isArray() || !fact.path("unsupportedReasons").isArray()
				|| !"NOT_ASSESSED".equals(fact.path("candidateFeasibilityVerdict").asText())
				|| !Set.of("ASSIGNMENT_PRIMITIVES", "PARTIAL_RAW_FACTS")
					.contains(fact.path("interpretation").asText()))
				throw new IllegalArgumentException("Candidate activation primitive facts malformed");
			for(JsonNode input : fact.path("inputs")) {
				if(input.path("inputPosition").asInt(-1) < 0
					|| !input.path("compiledSources").isArray()
					|| !input.path("exactBindings").isArray()
					|| !input.path("matchingRelocationActionIndices").isArray()
					|| !input.path("functionForwardingSources").isArray())
					throw new IllegalArgumentException("Candidate source reachability facts malformed");
				for(JsonNode source : input.path("compiledSources"))
					verifyNodeCoordinateReference(nodes, placements,
						source.path("sourceNodeIndex").asInt(-1),
						source.path("placementCoordinateIndex").asInt(-2));
				for(JsonNode binding : input.path("exactBindings")) {
					int reference = binding.path("sourceReferenceIndex").asInt(-1);
					int action = binding.path("relocationActionIndex").asInt(-2);
					if(reference < 0 || reference >= references.size()
						|| action < -1 || action >= relocationActions.size())
						throw new IllegalArgumentException("Candidate binding authority index differs");
					verifyNodeCoordinateReference(nodes, placements,
						binding.path("sourceNodeIndex").asInt(-1),
						binding.path("sourcePlacementCoordinateIndex").asInt(-2));
					JsonNode sourceNode = nodes.get(binding.path("sourceNodeIndex").asInt());
					int sourceAlternative = binding.path("sourcePlacementAlternativeIndex").asInt(-1);
					if(sourceAlternative < 0 || sourceAlternative >= sourceNode.path(3).size()
						|| !sourceNode.path(0).equals(references.get(reference).path("owner"))
						|| !sourceNode.path(3).get(sourceAlternative)
							.equals(references.get(reference).path("placement")))
						throw new IllegalArgumentException("Candidate binding placement authority differs");
				}
				for(JsonNode action : input.path("matchingRelocationActionIndices"))
					if(!action.canConvertToInt() || action.asInt() < 0
						|| action.asInt() >= relocationActions.size())
						throw new IllegalArgumentException("Candidate relocation authority index differs");
				for(JsonNode source : input.path("functionForwardingSources")) {
					verifyNodeCoordinateReference(nodes, placements,
						source.path("sourceNodeIndex").asInt(-1),
						source.path("sourcePlacementCoordinateIndex").asInt(-2));
					verifyNodeCoordinateReference(nodes, placements,
						source.path("boundaryNodeIndex").asInt(-1),
						source.path("boundaryPlacementCoordinateIndex").asInt(-2));
					verifyNodeCoordinateReference(nodes, placements,
						source.path("targetNodeIndex").asInt(-1),
						source.path("targetPlacementCoordinateIndex").asInt(-2));
				}
			}
			JsonNode derived = fact.path("derivedFoutAuthority");
			int derivedAction = derived.path("graphActionIndex").asInt(-2);
			if(derivedAction < -1 || derivedAction >= derivedActions.size())
				throw new IllegalArgumentException("Candidate derived-FOUT authority index differs");
			if(derivedAction >= 0) {
				verifyNodeCoordinateReference(nodes, placements,
					derived.path("producerNodeIndex").asInt(-1),
					derived.path("producerPlacementCoordinateIndex").asInt(-2));
				verifyNodeCoordinateReference(nodes, placements,
					derived.path("anchorOwnerNodeIndex").asInt(-1),
					derived.path("anchorOwnerPlacementCoordinateIndex").asInt(-2));
			}
			String workerKind = supportAuthorities.get(ownReference).path("supportAuthorities")
				.get(supportAuthority).path("kind").asText();
			if(!Set.of("EXACT_LAYOUT", "DYNAMIC_RESIDENCY", "ABSENT").contains(workerKind))
				throw new IllegalArgumentException("Candidate worker-pool authority malformed");
		}
	}

	private static void verifyLogicalAuthority(JsonNode logical, JsonNode nodes,
		JsonNode placements, JsonNode references) {
		if(!logical.path("transient").isArray() || !logical.path("function").isArray()
			|| !logical.path("boundaries").isArray())
			throw new IllegalArgumentException("Logical candidate reachability authority malformed");
		Set<String> referenceUniverse = new LinkedHashSet<>();
		for(JsonNode reference : references)
			referenceUniverse.add(reference.path("reference").asText());
		for(String kind : List.of("transient", "function", "boundaries"))
			for(JsonNode row : logical.path(kind)) {
				verifyNodeCoordinateReference(nodes, placements,
					row.path("sourceNodeIndex").asInt(-1),
					row.path("sourcePlacementCoordinateIndex").asInt(-2));
				verifyNodeCoordinateReference(nodes, placements,
					row.path("targetNodeIndex").asInt(-1),
					row.path("targetPlacementCoordinateIndex").asInt(-2));
				if("function".equals(kind))
					verifyNodeCoordinateReference(nodes, placements,
						row.path("boundaryNodeIndex").asInt(-1),
						row.path("boundaryPlacementCoordinateIndex").asInt(-2));
				if("transient".equals(kind))
					for(JsonNode compatibility : row.path("compatibility"))
						if(!referenceUniverse.contains(compatibility.path("sourceRealization").asText())
							|| !referenceUniverse.contains(
								compatibility.path("readerRealization").asText()))
							throw new IllegalArgumentException(
								"Logical candidate realization authority differs");
			}
	}

	private static void verifyNodeCoordinateReference(JsonNode nodes, JsonNode placements,
		int nodeIndex, int coordinate) {
		if(nodeIndex < 0 || nodeIndex >= nodes.size() || coordinate < -1
			|| coordinate >= placements.size())
			throw new IllegalArgumentException("Candidate placement coordinate reference differs");
		if(coordinate >= 0 && !nodes.get(nodeIndex).path(0).equals(placements.get(coordinate).path(0)))
			throw new IllegalArgumentException("Candidate placement coordinate reference differs");
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
			Path destination = artifactPath.toAbsolutePath().normalize();
			ArtifactWrite written = writeArtifact(destination, captured.artifact());
			Map<String,Object> summary = new LinkedHashMap<>(captured.summary());
			summary.put("artifactPath", destination.toString());
			summary.put("artifactSha256", written.sha256());
			summary.put("artifactBytes", written.bytes());
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
		String domainSha = shaJson(nativeDomain);
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
		result.put("compilerArgv", input.compilerArgv());
		result.put("compilerConfiguration", input.compilerConfiguration());
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
			|| !cell.path("sourceBinding").has("plannedCondition"))
			throw new IllegalArgumentException("Cell has no frozen planning condition: " + cellId);
		JsonNode binding = cell.path("sourceBinding");
		String kind = binding.path("kind").asText();
		if(!Set.of("planning-snapshot", "campaign-compile-condition", "generated-microbench").contains(kind))
			throw new IllegalArgumentException("Unsupported frozen planning condition kind: " + kind);
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
		if(workers < 1 || workers != testCase.path("workers").asInt(workers))
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
		String relative = "planning-snapshot".equals(kind)
			? "planning_study/native/input_templates/w" + workers + "/" + testCase.path("program").asText()
			: testCase.path("program").asText();
		Path programPath = root.resolve(relative).normalize();
		if(!programPath.startsWith(root) || !Files.isRegularFile(programPath)
			|| !pinned.containsKey(root.relativize(programPath).toString()))
			throw new IllegalArgumentException("Missing or unpinned frozen planning program: " + relative);
		String script = Files.readString(programPath, StandardCharsets.UTF_8);
		String scriptHash = sha(script.getBytes(StandardCharsets.UTF_8));
		String expectedProgramSha = testCase.path("program_sha256").asText();
		if(!scriptHash.equals(expectedProgramSha))
			throw new IllegalArgumentException("Planning program differs from condition");
		JsonNode workloadOptions;
		JsonNode context = null;
		String workload = "";
		if("planning-snapshot".equals(kind)) {
			context = JSON.readTree(root.resolve("planning_study/native/context-w" + workers + ".json").toFile());
			Path protocolPath = root.resolve("planning_study/native/protocol-w" + workers + ".json");
			if(!pinned.containsKey(root.relativize(protocolPath).toString()))
				throw new IllegalArgumentException("Planning protocol is not pinned");
			JsonNode protocol = JSON.readTree(protocolPath.toFile());
			if(!protocol.path("workload_jvm_options").isObject())
				throw new IllegalArgumentException("Frozen planning protocol has no workload JVM options");
			String programName = Path.of(testCase.path("program").asText()).getFileName().toString();
			if(!programName.endsWith(".dml"))
				throw new IllegalArgumentException("Planning program has no DML suffix");
			workload = programName.substring(0, programName.length() - 4);
			workloadOptions = protocol.path("workload_jvm_options").path(workload);
		}
		else workloadOptions = binding.path("workloadJvmOptions");
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
		if("planning-snapshot".equals(kind) && "P2_PREP".equals(workload)
			&& (workloadOptions.size() != 1 || !workloadOptions.get(0).asText().equals(
				context.path("p2_metadata_release").path("jvm_option").asText())))
			throw new IllegalArgumentException("P2 metadata release differs from protocol option");
		Map<String,PrebuilderSnapshot.ExternalSource> sourceFacts;
		String resolved;
		Map<String,String> arguments = new HashMap<>();
		if("planning-snapshot".equals(kind)) {
			boolean contextMatch = false;
			for(JsonNode contextCase : context.path("cases"))
				if(testCase.equals(contextCase)) contextMatch = true;
			if(!contextMatch) throw new IllegalArgumentException("Planning case differs from context manifest");
			sourceFacts = sourceFacts(script, testCase, workers, root, pinned);
			resolved = resolveImports(script, templates, root, pinned);
		}
		else {
			sourceFacts = frozenSourceFacts(testCase.path("federatedSources"), workers);
			if("campaign-compile-condition".equals(kind))
				validateFrozenSourceLiterals(script, testCase.path("federatedSources"));
			resolved = resolveFrozenImports(script, testCase.path("imports"), root, pinned);
			JsonNode frozenArguments = testCase.path("arguments");
			if(!frozenArguments.isObject())
				throw new IllegalArgumentException("Frozen program arguments are missing");
			frozenArguments.fields().forEachRemaining(entry -> arguments.put(
				entry.getKey().startsWith("$") ? entry.getKey() : "$" + entry.getKey(),
				entry.getValue().asText()));
			JsonNode localArguments = testCase.path("compileLocalArguments");
			if(!localArguments.isObject())
				throw new IllegalArgumentException("Frozen compile-local argument map is missing");
			localArguments.fields().forEachRemaining(entry -> {
				Path local = root.resolve(entry.getValue().asText()).normalize();
				String relativeLocal = root.relativize(local).toString();
				Path metadata = Path.of(local + ".mtd");
				String relativeMetadata = root.relativize(metadata).toString();
				if(!local.startsWith(root) || !Files.isRegularFile(local) || !Files.isRegularFile(metadata)
					|| !pinned.containsKey(relativeLocal) || !pinned.containsKey(relativeMetadata))
					throw new IllegalArgumentException("Frozen compile-local input is absent or unpinned: "
						+ entry.getKey());
				arguments.put(entry.getKey().startsWith("$") ? entry.getKey() : "$" + entry.getKey(),
					local.toString());
			});
		}
		CompilerConfiguration compiler = compilerConfiguration(binding, kind);
		DMLProgram program = ParserFactory.createParser().parse(programPath.toString(), resolved, arguments);
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
			Map.copyOf(networkEnvironment), Map.copyOf(workloadJvmProperties),
			compiler.argv(), compiler.attestation(), requireEnvironment,
			snapshot, finalGraph,
			Map.copyOf(finalSourceIds));
	}

	private static CompilerConfiguration compilerConfiguration(JsonNode binding, String kind) throws Exception {
		JsonNode node = binding.path("compilerArgv");
		if(!"campaign-compile-condition".equals(kind)) {
			if(!node.isMissingNode() && (!node.isArray() || !node.isEmpty()))
				throw new IllegalArgumentException("Compiler argv is unsupported for frozen condition kind: " + kind);
			return new CompilerConfiguration(List.of(), Map.of("source", "CAPTURE_PROCESS_DEFAULTS"));
		}
		if(!node.isArray())
			throw new IllegalArgumentException("Campaign compiler argv is missing");
		List<String> argv = new ArrayList<>();
		for(JsonNode value : node) {
			if(!value.isTextual())
				throw new IllegalArgumentException("Campaign compiler argv contains a non-string value");
			argv.add(value.asText());
		}
		validateSupportedCompilerArgv(argv);
		List<String> parserArgv = new ArrayList<>(argv);
		// The public CLI parser requires an execution target even though this
		// capture already has a separately pinned program file. Add a synthetic
		// script target for parsing only; it is excluded from the frozen argv and
		// its digest, and no DMLScript execution entry point is invoked.
		parserArgv.add("-s");
		parserArgv.add("capture-contract");
		DMLOptions parsed = DMLOptions.parseCLArguments(parserArgv.toArray(String[]::new));
		if(parsed.execMode != ExecMode.SINGLE_NODE)
			throw new IllegalArgumentException("Campaign compiler argv did not parse to its frozen semantics");
		DMLScript.setGlobalExecMode(parsed.execMode);
		DMLScript.SEED = parsed.seed;
		DMLScript.STATISTICS = parsed.stats;
		DMLScript.STATISTICS_COUNT = parsed.statsCount;
		FEDInstructionUtils.noFedRuntimeConversion = parsed.noFedRuntimeConversion;
		if(DMLScript.getGlobalExecMode() != ExecMode.SINGLE_NODE || DMLScript.SEED != parsed.seed
			|| !DMLScript.STATISTICS || DMLScript.STATISTICS_COUNT != parsed.statsCount
			|| !FEDInstructionUtils.noFedRuntimeConversion)
			throw new IllegalStateException("Campaign compiler argv was not applied to the capture process");
		Map<String,Object> attestation = new LinkedHashMap<>();
		attestation.put("argvSha256", sha(JSON.writeValueAsBytes(argv)));
		attestation.put("execMode", parsed.execMode.name());
		attestation.put("seed", parsed.seed);
		attestation.put("noFedRuntimeConversion", parsed.noFedRuntimeConversion);
		attestation.put("statistics", parsed.stats);
		attestation.put("statisticsCount", parsed.statsCount);
		attestation.put("appliedBefore", "PARSE_VALIDATE_CONSTRUCT_REWRITE");
		return new CompilerConfiguration(List.copyOf(argv), Map.copyOf(attestation));
	}

	private static void validateSupportedCompilerArgv(List<String> argv) {
		Set<String> seen = new LinkedHashSet<>();
		for(int index = 0; index < argv.size();) {
			String option = argv.get(index++);
			if(!seen.add(option))
				throw new IllegalArgumentException("Duplicate campaign compiler option: " + option);
			switch(option) {
				case "-exec" -> {
					if(index >= argv.size() || !"singlenode".equals(argv.get(index++)))
						throw new IllegalArgumentException("Campaign capture supports only -exec singlenode");
				}
				case "-seed" -> {
					if(index >= argv.size() || !argv.get(index++).matches("-?[0-9]+"))
						throw new IllegalArgumentException("Campaign compiler seed is missing or malformed");
				}
				case "-noFedRuntimeConversion" -> { }
				case "-stats" -> {
					if(index < argv.size() && argv.get(index).matches("[0-9]+")) index++;
				}
				default -> throw new IllegalArgumentException("Unsupported campaign compiler option: " + option);
			}
		}
		if(!seen.contains("-exec"))
			throw new IllegalArgumentException("Campaign compiler argv lacks an explicit execution mode");
	}

	private static void validateFrozenSourceLiterals(String script, JsonNode frozenSources) {
		if(!frozenSources.isArray())
			throw new IllegalArgumentException("Frozen federated source facts are missing");
		Map<String,JsonNode> expected = new LinkedHashMap<>();
		for(JsonNode source : frozenSources) {
			String variable = source.path("variable").asText();
			if(variable.isBlank() || expected.put(variable, source) != null)
				throw new IllegalArgumentException("Duplicate frozen source fact: " + variable);
		}
		Set<String> seen = new LinkedHashSet<>();
		Matcher statement = FROZEN_FEDERATED.matcher(script);
		while(statement.find()) {
			String variable = statement.group(1);
			JsonNode source = expected.get(variable);
			if(source == null || !seen.add(variable))
				throw new IllegalArgumentException("Program federated source differs: " + variable);
			List<String> addresses = parseFrozenAddresses(statement.group(2), variable);
			List<List<Integer>> ranges = parseFrozenRanges(statement.group(3), variable);
			if(!JSON.valueToTree(addresses).equals(source.path("origins"))
				|| !JSON.valueToTree(ranges).equals(source.path("ranges")))
				throw new IllegalArgumentException("Program federated literal differs from frozen source: " + variable);
		}
		if(!seen.equals(expected.keySet())
			|| Pattern.compile("\\bfederated\\s*\\(").matcher(script).results().count() != seen.size())
			throw new IllegalArgumentException("Program/frozen federated source set differs");
	}

	private static List<String> parseFrozenAddresses(String text, String variable) {
		List<String> result = new ArrayList<>();
		Matcher matcher = FROZEN_ADDRESS.matcher(text);
		int end = 0;
		while(matcher.find()) {
			result.add(matcher.group(1));
			end = matcher.end();
		}
		if(result.isEmpty() || end != text.length())
			throw new IllegalArgumentException("Malformed federated address literal: " + variable);
		return result;
	}

	private static List<List<Integer>> parseFrozenRanges(String text, String variable) {
		List<List<Integer>> result = new ArrayList<>();
		Matcher matcher = FROZEN_RANGE.matcher(text);
		int end = 0;
		while(matcher.find()) {
			result.add(List.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
			end = matcher.end();
		}
		if(result.isEmpty() || end != text.length())
			throw new IllegalArgumentException("Malformed federated range literal: " + variable);
		return result;
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

	static Map<String,Object> domain(PlacementAnalysis analysis, FullProductionJointPlanExport exporter) {
		NeutralPlacementGraph graph = exporter.graph();
		List<Integer> radices = new ArrayList<>();
		List<Object> placementRows = new ArrayList<>();
		List<Object> nodes = new ArrayList<>();
		List<String> derivedFoutActions = graph.derivedFoutMaterializationActions().stream()
			.map(action -> action.key().normalizedSignature()).toList();
		Map<Object,Integer> derivedFoutActionIdentity = new IdentityHashMap<>();
		for(int i = 0; i < graph.derivedFoutMaterializationActions().size(); i++)
			derivedFoutActionIdentity.put(graph.derivedFoutMaterializationActions().get(i).key(), i);
		Map<CompiledHopKey,Integer> placementCoordinateIdentity = new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> nodeIdentity = new IdentityHashMap<>();
		for(int i = 0; i < graph.nodes().size(); i++)
			nodeIdentity.put(graph.nodes().get(i).key(), i);
		Map<String,Integer> relocationActionIndex = new LinkedHashMap<>();
		for(int i = 0; i < graph.relocationActions().size(); i++)
			if(relocationActionIndex.put(graph.relocationActions().get(i).key().normalizedSignature(), i) != null)
				throw new IllegalStateException("Duplicate relocation action authority");
		Map<CompiledHopKey,Set<CandidateSelectionReceipt>> candidates = new LinkedHashMap<>();
		for(var node : graph.decisionNodes()) {
			if(node.legalAlternatives().isEmpty())
				throw new IllegalStateException("Decision coordinate has empty placement domain: " + node.key());
			radices.add(node.legalAlternatives().size());
			placementRows.add(List.of(node.key().normalizedSignature(),
				node.legalAlternatives().stream().map(state -> state.normalizedSignature()).toList()));
			placementCoordinateIdentity.put(node.key(), placementCoordinateIdentity.size());
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
		List<Object> candidateSemanticRows = new ArrayList<>();
		Map<String,Object> candidateRealizationReferences = new LinkedHashMap<>();
		Map<String,Integer> candidateRealizationReferenceIndex = new LinkedHashMap<>();
		Map<String,List<Map<String,Object>>> candidateRealizationSupportAuthorities =
			new LinkedHashMap<>();
		for(var fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(var emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations()) {
					String reference = org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
						.CandidateRealizationReference.of(fact.key(), realization).normalizedSignature();
					Map<String,Object> referenceFact = Map.of(
						"reference", reference,
						"rule", fact.key().normalizedSignature(),
						"owner", fact.key().parentOccurrence().normalizedSignature(),
						"placement", realization.key().emissionState().placementState().normalizedSignature(),
						"realization", realization.key().normalizedSignature());
					Object prior = candidateRealizationReferences.putIfAbsent(reference, referenceFact);
					if(prior != null && !prior.equals(referenceFact))
						throw new IllegalStateException("Candidate realization reference facts conflict");
					List<Map<String,Object>> supportAuthorities = realization.supportClauses().stream()
						.map(clause -> workerPoolAuthority(realization, clause)).toList();
					List<Map<String,Object>> priorAuthorities = candidateRealizationSupportAuthorities
						.putIfAbsent(reference, supportAuthorities);
					if(priorAuthorities != null && !priorAuthorities.equals(supportAuthorities))
						throw new IllegalStateException("Candidate realization support authorities conflict");
					candidateRealizationReferenceIndex.computeIfAbsent(reference,
						ignored -> candidateRealizationReferenceIndex.size());
				}
		}
		List<Object> derivedFoutOwnershipBindings = new ArrayList<>();
		List<Object> candidateActivationFacts = new ArrayList<>();
		int candidateCoordinate = 0;
		for(var entry : candidates.entrySet()) {
			List<CandidateSelectionReceipt> ordered = entry.getValue().stream()
				.sorted(Comparator.comparing(CandidateSelectionReceipt::normalizedSignature)).toList();
			List<String> receipts = ordered.stream().map(CandidateSelectionReceipt::normalizedSignature).toList();
			for(int candidateIndex = 0; candidateIndex < ordered.size(); candidateIndex++) {
				candidateSemanticRows.add(candidateSemanticRow(
					ordered.get(candidateIndex), candidateCoordinate, candidateIndex));
				candidateActivationFacts.add(candidateActivationFact(analysis, graph,
					placementCoordinateIdentity, nodeIdentity, derivedFoutActionIdentity,
					candidateRealizationReferenceIndex, relocationActionIndex,
					ordered.get(candidateIndex), candidateCoordinate, candidateIndex));
			}
			for(int candidateIndex = 0; candidateIndex < ordered.size(); candidateIndex++) {
				var action = ordered.get(candidateIndex).emission().derivedFoutAction();
				if(action == null)
					continue;
				Integer actionIndex = derivedFoutActionIdentity.get(action);
				if(actionIndex == null)
					throw new IllegalStateException("Candidate derived-FOUT action is not graph-owned by identity");
				derivedFoutOwnershipBindings.add(List.of(candidateCoordinate, candidateIndex, actionIndex));
			}
			candidateRows.add(List.of(entry.getKey().normalizedSignature(), receipts));
			radices.add(receipts.size() + 1);
			candidateCoordinate++;
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
		descriptor.put("candidateReceiptSemanticFacts", candidateSemanticRows);
		descriptor.put("candidateReceiptActivationFacts", candidateActivationFacts);
		descriptor.put("candidateRealizationReferenceFacts",
			List.copyOf(candidateRealizationReferences.values()));
		descriptor.put("candidateRealizationSupportAuthorities",
			candidateRealizationReferences.keySet().stream().map(reference -> Map.of(
				"referenceIndex", candidateRealizationReferenceIndex.get(reference),
				"supportAuthorities", candidateRealizationSupportAuthorities.get(reference))).toList());
		descriptor.put("compiledCandidateInputEdges", analysis.compiledInputEdgesInCanonicalOrder().stream()
			.map(edge -> Map.of("producer", edge.producer().normalizedSignature(),
				"consumer", edge.consumer().normalizedSignature(),
				"inputPosition", edge.inputPosition())).toList());
		descriptor.put("logicalCandidateReachability", Map.of(
			"transient", analysis.logicalTransientInputsInCanonicalOrder().stream().map(input -> Map.of(
				"source", input.sourceWrite().normalizedSignature(),
				"target", input.targetRead().normalizedSignature(),
				"inputPosition", input.logicalPosition(),
				"compatibility", input.compatibility().stream().map(edge -> Map.of(
					"sourceRealization", edge.sourceRealization().normalizedSignature(),
					"readerRealization", edge.readerRealization().normalizedSignature())).toList())).toList(),
			"function", analysis.logicalFunctionInputsInCanonicalOrder().stream().map(input -> Map.of(
				"source", input.sourceArgument().normalizedSignature(),
				"boundary", input.boundary().normalizedSignature(),
				"target", input.targetRead().normalizedSignature(),
				"inputPosition", input.logicalPosition())).toList(),
			"boundaries", analysis.logicalBoundaryRealizations().relations().stream().map(relation -> Map.of(
				"source", relation.source().normalizedSignature(),
				"target", relation.target().normalizedSignature())).toList()));
		descriptor.put("logicalCandidateCoordinateAuthority", Map.of(
			"transient", analysis.logicalTransientInputsInCanonicalOrder().stream()
				.map(input -> logicalTransientAuthority(input, nodeIdentity,
					placementCoordinateIdentity)).toList(),
			"function", analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.map(input -> logicalFunctionAuthority(input, nodeIdentity,
					placementCoordinateIdentity)).toList(),
			"boundaries", analysis.logicalBoundaryRealizations().relations().stream()
				.map(relation -> logicalBoundaryAuthority(relation, nodeIdentity,
					placementCoordinateIdentity)).toList()));
		descriptor.put("relocationDomains", relocationRows);
		descriptor.put("relocationActionFacts", graph.relocationActions().stream().map(action -> Map.of(
			"action", action.key().normalizedSignature(),
			"sourceValueVersion", action.key().sourceValueVersion().normalizedSignature(),
			"targetPlacement", action.key().targetPlacement().normalizedSignature(),
			"materializationFType", action.key().materializationFType().name(),
			"durableAnchor", action.key().durableAnchor().normalizedSignature(),
			"statementBlockScope", action.key().statementBlockScope(),
			"compatibleConsumers", action.key().compatibleConsumers().stream()
				.map(CompiledHopKey::normalizedSignature).toList(),
			"directSourcePlacements", action.directSourcePlacements().stream()
				.map(state -> state.normalizedSignature()).toList(),
			"obligations", action.obligations().stream().map(obligation -> Map.of(
				"consumer", obligation.consumer().normalizedSignature(),
				"inputPosition", obligation.inputPosition(),
				"sourceValueVersion", obligation.sourceValueVersion().normalizedSignature(),
				"requiredPlacement", obligation.requiredPlacement().normalizedSignature())).toList()
		)).toList());
		descriptor.put("relocationWorkerPoolAuthorities", graph.relocationActions().stream()
			.map(action -> exactWorkerPoolAuthority(action.key().durableAnchor())).toList());
		descriptor.put("constraints", graph.constraints().stream().map(c -> Map.of(
			"kind", c.kind().name(), "left", c.left().normalizedSignature(),
			"right", c.right().normalizedSignature(), "inputPosition", c.inputPosition(),
			"evidence", c.evidence(), "signature", c.normalizedSignature())).toList());
		descriptor.put("derivedFoutActions", derivedFoutActions);
		descriptor.put("derivedFoutOwnershipBindings", derivedFoutOwnershipBindings);
		descriptor.put("nonDecisionCandidateOwners", exporter.nonDecisionCandidateOwners().stream()
			.map(owner -> List.of(owner.key().normalizedSignature(), owner.role().name())).toList());
		descriptor.put("candidatePrivacyClosurePasses",
			analysis.candidatePrivacyClosureEvidence().passes().stream()
				.map(pass -> JSON.convertValue(pass, Map.class)).toList());
		descriptor.put("candidateRuleFactInventory", analysis.candidateRuleFacts().orderedFacts().stream()
			.map(fact -> Map.of("ruleSignature", shaText(fact.key().normalizedSignature()),
				"status", fact.status().name(), "failure", fact.failureCode(),
				"capabilityPresent", fact.capability() != null,
				"profileAvailable", fact.profile().available(), "emissions",
				fact.allowedEmissionFacts().stream()
					.map(PlanningNativeModelCapture::candidateInventoryEmission).toList())).toList());
		descriptor.put("radices", radices);
		return descriptor;
	}

	private static Map<String,Object> logicalTransientAuthority(
		PlacementAnalysis.LogicalTransientInputFact input,
		Map<CompiledHopKey,Integer> nodeByKey, Map<CompiledHopKey,Integer> coordinateByKey) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("source", input.sourceWrite().normalizedSignature());
		row.put("sourceNodeIndex", nodeIndex(nodeByKey, input.sourceWrite()));
		row.put("sourcePlacementCoordinateIndex", coordinate(coordinateByKey, input.sourceWrite()));
		row.put("target", input.targetRead().normalizedSignature());
		row.put("targetNodeIndex", nodeIndex(nodeByKey, input.targetRead()));
		row.put("targetPlacementCoordinateIndex", coordinate(coordinateByKey, input.targetRead()));
		row.put("inputPosition", input.logicalPosition());
		row.put("compatibility", input.compatibility().stream().map(edge -> Map.of(
			"sourceRealization", edge.sourceRealization().normalizedSignature(),
			"readerRealization", edge.readerRealization().normalizedSignature())).toList());
		return Map.copyOf(row);
	}

	private static Map<String,Object> logicalFunctionAuthority(
		PlacementAnalysis.LogicalFunctionInputFact input,
		Map<CompiledHopKey,Integer> nodeByKey, Map<CompiledHopKey,Integer> coordinateByKey) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("source", input.sourceArgument().normalizedSignature());
		row.put("sourceNodeIndex", nodeIndex(nodeByKey, input.sourceArgument()));
		row.put("sourcePlacementCoordinateIndex", coordinate(coordinateByKey, input.sourceArgument()));
		row.put("boundary", input.boundary().normalizedSignature());
		row.put("boundaryNodeIndex", nodeIndex(nodeByKey, input.boundary()));
		row.put("boundaryPlacementCoordinateIndex", coordinate(coordinateByKey, input.boundary()));
		row.put("target", input.targetRead().normalizedSignature());
		row.put("targetNodeIndex", nodeIndex(nodeByKey, input.targetRead()));
		row.put("targetPlacementCoordinateIndex", coordinate(coordinateByKey, input.targetRead()));
		row.put("callInputPosition", input.callInputPosition());
		row.put("logicalPosition", input.logicalPosition());
		return Map.copyOf(row);
	}

	private static Map<String,Object> logicalBoundaryAuthority(
		org.apache.sysds.hops.fedplanner.placement.LogicalBoundaryRealizations.Relation relation,
		Map<CompiledHopKey,Integer> nodeByKey, Map<CompiledHopKey,Integer> coordinateByKey) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("source", relation.source().normalizedSignature());
		row.put("sourceNodeIndex", nodeIndex(nodeByKey, relation.source()));
		row.put("sourcePlacementCoordinateIndex", coordinate(coordinateByKey, relation.source()));
		row.put("target", relation.target().normalizedSignature());
		row.put("targetNodeIndex", nodeIndex(nodeByKey, relation.target()));
		row.put("targetPlacementCoordinateIndex", coordinate(coordinateByKey, relation.target()));
		return Map.copyOf(row);
	}

	private static Map<String,Object> candidateSemanticRow(CandidateSelectionReceipt receipt,
		int coordinateIndex, int candidateIndex) {
		List<Object> bindings = receipt.supportClause().inputBindings().stream().map(binding -> Map.of(
			"signature", binding.normalizedSignature(),
			"inputPosition", binding.inputPosition(),
			"source", binding.source().normalizedSignature(),
			"sourceOwner", binding.source().rule().parentOccurrence().normalizedSignature(),
			"sourcePlacement", binding.source().realization().emissionState()
				.placementState().normalizedSignature(),
			"kind", binding.kind().name(),
			"relocationAction", binding.relocationAction() == null ? "-"
				: binding.relocationAction().normalizedSignature())).map(value -> (Object) value).toList();
		String state = receipt.emission().emissionState().placementState().normalizedSignature();
		boolean simpleLocal = state.startsWith("CP/LOUT/")
			&& receipt.supportClause().inputBindings().isEmpty()
			&& receipt.supportClause().requiredInputSupport().isEmpty()
			&& receipt.emission().derivedFoutAction() == null;
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("coordinateIndex", coordinateIndex);
		row.put("candidateIndex", candidateIndex);
		row.put("rule", receipt.rule().normalizedSignature());
		row.put("owner", receipt.rule().parentOccurrence().normalizedSignature());
		row.put("orderedInputs", receipt.rule().orderedInputs().stream()
			.map(input -> input.normalizedSignature()).toList());
		row.put("emission", receipt.emission().selectionSignature());
		row.put("placement", state);
		row.put("realization", receipt.realization().key().normalizedSignature());
		row.put("reference", org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
			.CandidateRealizationReference.of(receipt.rule(), receipt.realization()).normalizedSignature());
		row.put("proofDependencies", receipt.supportClause().proofDependencies().stream()
			.map(proof -> proof.normalizedSignature()).toList());
		row.put("requiredInputSupport", receipt.supportClause().requiredInputSupport().stream()
			.map(reference -> reference.normalizedSignature()).toList());
		row.put("inputBindings", bindings);
		row.put("nativeWorkerPoolWitness", receipt.supportClause().nativeWorkerPoolWitness() == null ? "-"
			: receipt.supportClause().nativeWorkerPoolWitness().normalizedSignature());
		row.put("nativeWorkerPoolLayoutExact", receipt.supportClause().nativeWorkerPoolLayoutExact());
		row.put("derivedFoutAction", receipt.emission().derivedFoutAction() == null ? "-"
			: receipt.emission().derivedFoutAction().normalizedSignature());
		row.put("independentReachabilityClass", simpleLocal ? "SIMPLE_LOCAL" : "UNSUPPORTED_COMPLEX");
		return row;
	}

	/** Raw assignment predicates consumed by candidate feasibility; no feasibility verdict is serialized. */
	private static Map<String,Object> candidateActivationFact(PlacementAnalysis analysis,
		NeutralPlacementGraph graph, Map<CompiledHopKey,Integer> coordinateByKey,
		Map<CompiledHopKey,Integer> nodeByKey, Map<Object,Integer> derivedActionByIdentity,
		Map<String,Integer> realizationReferenceIndex, Map<String,Integer> relocationActionIndex,
		CandidateSelectionReceipt receipt,
		int coordinateIndex, int candidateIndex) {
		CompiledHopKey owner = receipt.rule().parentOccurrence();
		PlacementState active = receipt.emission().emissionState().placementState();
		Integer ownReferenceIndex = realizationReferenceIndex.get(
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
				.CandidateRealizationReference.of(receipt.rule(), receipt.realization())
				.normalizedSignature());
		int supportAuthorityIndex = java.util.stream.IntStream.range(0,
			receipt.realization().supportClauses().size()).filter(index ->
				receipt.realization().supportClauses().get(index) == receipt.supportClause())
			.findFirst().orElse(-1);
		if(ownReferenceIndex == null || supportAuthorityIndex < 0)
			throw new IllegalStateException("Candidate activation support authority is not graph-owned");
		NeutralPlacementGraph.Node ownerNode = graph.node(owner).orElseThrow();
		int activeAlternative = alternativeIndex(ownerNode, active);
		if(activeAlternative < 0)
			throw new IllegalStateException("Candidate activation placement is outside its owner domain");
		long presentInputs = receipt.rule().orderedInputs().stream()
			.filter(CandidateInputState::present).count();
		long presentPhysicalInputs = java.util.stream.IntStream.range(0,
			receipt.rule().orderedInputs().size()).filter(position ->
				receipt.rule().orderedInputs().get(position).present()
					&& analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
						edge.consumer() == owner && edge.inputPosition() == position)).count();
		List<Object> inputs = new ArrayList<>();
		List<String> unsupported = new ArrayList<>();
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream().filter(edge ->
					edge.consumer() == owner && edge.inputPosition() == inputPosition).toList();
			List<Object> compiledSources = edges.stream().map(edge -> {
				Map<String,Object> source = new LinkedHashMap<>();
				source.put("sourceNodeIndex", nodeIndex(nodeByKey, edge.producer()));
				source.put("placementCoordinateIndex", coordinate(coordinateByKey, edge.producer()));
				source.put("compatibleFoutAlternativeIndices", input.present()
					? compatibleFoutAlternatives(graph, edge.producer(), input.fType()) : List.of());
				source.put("latentWdivmmBoundary", PlacementCostSemantics
					.isLatentWdivmmTransposePairBoundary(analysis, edge.producer(), owner, inputPosition));
				return (Object) Map.copyOf(source);
			}).toList();
			List<Object> exactBindings = receipt.supportClause().inputBindings().stream()
				.filter(binding -> binding.inputPosition() == inputPosition).map(binding -> {
					CompiledHopKey sourceOwner = binding.source().rule().parentOccurrence();
					Map<String,Object> row = new LinkedHashMap<>();
					row.put("kind", binding.kind().name());
					Integer referenceIndex = realizationReferenceIndex.get(
						binding.source().normalizedSignature());
					if(referenceIndex == null)
						throw new IllegalStateException("Candidate binding reference is outside its authority universe");
					row.put("sourceReferenceIndex", referenceIndex);
					row.put("sourceNodeIndex", nodeIndex(nodeByKey, sourceOwner));
					row.put("sourcePlacementCoordinateIndex", coordinate(coordinateByKey, sourceOwner));
					row.put("sourcePlacementAlternativeIndex", alternativeIndex(
						graph.node(sourceOwner).orElseThrow(), binding.source().realization()
							.emissionState().placementState()));
					row.put("compatibleFoutAlternativeIndices", input.present()
						? compatibleFoutAlternatives(graph, sourceOwner, input.fType()) : List.of());
					Integer actionIndex = binding.relocationAction() == null ? -1
						: relocationActionIndex.get(binding.relocationAction().normalizedSignature());
					if(actionIndex == null)
						throw new IllegalStateException("Candidate binding action is outside its authority universe");
					row.put("relocationActionIndex", actionIndex);
					return (Object) Map.copyOf(row);
				}).toList();
			List<Integer> matchingActions = graph.relocationActions().stream().filter(action ->
				input.present() && action.key().materializationFType() == input.fType()
					&& action.key().targetPlacement().equals(active)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == owner && obligation.inputPosition() == inputPosition))
				.map(action -> relocationActionIndex.get(action.key().normalizedSignature())).toList();
			List<Object> functionSources = edges.stream().flatMap(edge -> analysis
				.logicalFunctionInputsInCanonicalOrder().stream().filter(fact ->
					fact.targetRead() == edge.producer())).map(fact -> Map.of(
					"sourceNodeIndex", nodeIndex(nodeByKey, fact.sourceArgument()),
					"sourcePlacementCoordinateIndex", coordinate(coordinateByKey, fact.sourceArgument()),
					"boundaryNodeIndex", nodeIndex(nodeByKey, fact.boundary()),
					"boundaryPlacementCoordinateIndex", coordinate(coordinateByKey, fact.boundary()),
					"targetNodeIndex", nodeIndex(nodeByKey, fact.targetRead()),
					"targetPlacementCoordinateIndex", coordinate(coordinateByKey, fact.targetRead()),
					"callInputPosition", fact.callInputPosition(),
					"logicalPosition", fact.logicalPosition())).map(value -> (Object) value).toList();
			Map<String,Object> inputFact = new LinkedHashMap<>();
			inputFact.put("inputPosition", position);
			inputFact.put("present", input.present());
			inputFact.put("requiredFType", input.fType() == null ? "-" : input.fType().name());
			inputFact.put("compiledSources", compiledSources);
			inputFact.put("exactBindings", exactBindings);
			inputFact.put("matchingRelocationActionIndices", matchingActions);
			inputFact.put("functionForwardingSources", functionSources);
			inputs.add(Map.copyOf(inputFact));
			if(input.present() && edges.size() > 1)
				unsupported.add("AMBIGUOUS_COMPILED_INPUT:" + position);
			boolean relocationBound = receipt.supportClause().inputBindings().stream()
				.anyMatch(binding -> binding.inputPosition() == inputPosition
					&& binding.kind()
						== org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
							.CandidateInputBindingKind.RELOCATION);
			if(relocationBound)
				unsupported.add("RELOCATION_PRIVACY_NOT_SERIALIZED:" + position);
		}
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("coordinateIndex", coordinateIndex);
		row.put("candidateIndex", candidateIndex);
		row.put("receiptSha256", shaText(receipt.normalizedSignature()));
		row.put("ownerPlacementCoordinateIndex", coordinate(coordinateByKey, owner));
		row.put("activePlacementAlternativeIndex", activeAlternative);
		row.put("realizationReferenceIndex", ownReferenceIndex);
		row.put("supportAuthorityIndex", supportAuthorityIndex);
		row.put("functionCallBoundary", analysis.isDmlFunctionCallBoundary(owner));
		row.put("consumerExec", active.execType().name());
		row.put("executionFType", receipt.emission().executionFType() == null ? "-"
			: receipt.emission().executionFType().name());
		row.put("derivedFedFout", receipt.emission().emissionState().derivedFedFout());
		row.put("presentInputCount", presentInputs);
		row.put("presentPhysicalInputCount", presentPhysicalInputs);
		row.put("inputs", List.copyOf(inputs));
		row.put("derivedFoutAuthority", derivedFoutAuthority(graph, coordinateByKey, nodeByKey,
			derivedActionByIdentity, receipt));
		var latent = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner);
		var direct = PlacementCostSemantics.directWdivmmRuntimeFact(analysis, owner);
		if(!receipt.supportClause().requiredInputSupport().isEmpty())
			unsupported.add("CROSS_RECEIPT_REALIZATION_COMPATIBILITY_NOT_INTERPRETED");
		row.put("specialRuntimeAuthority", specialRuntimeAuthority(
			coordinateByKey, nodeByKey, latent, direct));
		row.put("candidateFeasibilityVerdict", "NOT_ASSESSED");
		row.put("interpretation", unsupported.isEmpty()
			? "ASSIGNMENT_PRIMITIVES" : "PARTIAL_RAW_FACTS");
		row.put("unsupportedReasons", List.copyOf(unsupported));
		return Map.copyOf(row);
	}

	private static Map<String,Object> derivedFoutAuthority(NeutralPlacementGraph graph,
		Map<CompiledHopKey,Integer> coordinateByKey, Map<CompiledHopKey,Integer> nodeByKey,
		Map<Object,Integer> actionByIdentity,
		CandidateSelectionReceipt receipt) {
		PlacementState selected = receipt.emission().emissionState().placementState();
		boolean required = receipt.emission().emissionState().derivedFedFout()
			|| selected.execType() == ExecType.CP && selected.output() == FederatedOutput.FOUT;
		var action = receipt.emission().derivedFoutAction();
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("required", required);
		row.put("graphActionIndex", action == null ? -1
			: actionByIdentity.getOrDefault(action, -1));
		row.put("producerNodeIndex", action == null ? -1 : nodeIndex(nodeByKey, action.producer()));
		row.put("producerPlacementCoordinateIndex", action == null ? -1
			: coordinate(coordinateByKey, action.producer()));
		row.put("sourcePlacementAlternativeIndex", action == null ? -1 : alternativeIndex(
			graph.node(action.producer()).orElseThrow(), action.sourcePlacement()));
		row.put("targetPlacementAlternativeIndex", action == null ? -1 : alternativeIndex(
			graph.node(action.producer()).orElseThrow(), action.targetPlacement()));
		row.put("anchorOwnerNodeIndex", action == null ? -1
			: nodeIndex(nodeByKey, action.durableAnchorOwner()));
		row.put("anchorOwnerPlacementCoordinateIndex", action == null ? -1
			: coordinate(coordinateByKey, action.durableAnchorOwner()));
		row.put("anchorOwnerRequiredFType", action == null ? "-"
			: action.durableAnchorOwnerFType().name());
		row.put("anchorOwnerCompatibleFoutAlternativeIndices", action == null ? List.of()
			: compatibleFoutAlternatives(graph, action.durableAnchorOwner(),
				action.durableAnchorOwnerFType()));
		return Map.copyOf(row);
	}

	private static Map<String,Object> workerPoolAuthority(
		PlacementAnalysis.CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause clause) {
		var exact = realization.provenWorkerPool(clause);
		var residency = realization.nativeWorkerPoolResidencyWitness(clause);
		if(exact != null)
			return exactWorkerPoolAuthority(exact);
		if(residency != null) {
			List<String> endpoints = residency.partitions().stream().map(partition ->
				FederationUtils.canonicalFederatedWorkerAddress(partition.workerId()))
				.filter(endpoint -> endpoint != null && !endpoint.isBlank()).distinct().sorted().toList();
			if(endpoints.isEmpty())
				throw new IllegalStateException("Native worker residency lacks canonical endpoints");
			return Map.of("kind", "DYNAMIC_RESIDENCY", "ftype", residency.fType().name(),
				"layoutExact", false, "endpoints", endpoints);
		}
		return Map.of("kind", "ABSENT", "ftype", "-", "layoutExact", false);
	}

	private static Map<String,Object> exactWorkerPoolAuthority(
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey anchor) {
		return Map.of("kind", "EXACT_LAYOUT", "ftype", anchor.fType().name(),
			"layoutExact", true, "partitions", anchor.partitions().stream().map(partition -> Map.of(
				"worker", partition.workerId(), "begin", partition.begin(), "end", partition.end()))
				.toList());
	}

	private static Map<String,Object> specialRuntimeAuthority(
		Map<CompiledHopKey,Integer> coordinateByKey, Map<CompiledHopKey,Integer> nodeByKey,
		PlacementCostSemantics.LatentWdivmmTransposePairFact latent,
		PlacementCostSemantics.DirectWdivmmRuntimeFact direct) {
		if(latent != null)
			return Map.of("kind", "LATENT_WDIVMM", "weightsNodeIndex", nodeIndex(nodeByKey, latent.weights()),
				"weightsPlacementCoordinateIndex", coordinate(coordinateByKey, latent.weights()),
				"requiredFType", latent.partitionedInputFType() == null ? "-"
					: latent.partitionedInputFType().name(),
				"nativeOutputMustBeLocal", latent.nativeOutputMustBeLocal());
		if(direct != null)
			return Map.of("kind", "DIRECT_WDIVMM", "weightsNodeIndex", nodeIndex(nodeByKey, direct.weights()),
				"weightsPlacementCoordinateIndex", coordinate(coordinateByKey, direct.weights()),
				"requiredFType", direct.runtimeInputFType() == null ? "-"
					: direct.runtimeInputFType().name(),
				"nativeOutputMustBeLocal", direct.nativeOutputMustBeLocal());
		return Map.of("kind", "NONE");
	}

	private static int coordinate(Map<CompiledHopKey,Integer> coordinateByKey, CompiledHopKey key) {
		return coordinateByKey.getOrDefault(key, -1);
	}

	private static int nodeIndex(Map<CompiledHopKey,Integer> nodeByKey, CompiledHopKey key) {
		Integer index = nodeByKey.get(key);
		if(index == null)
			throw new IllegalStateException("Candidate activation authority is outside the graph node universe");
		return index;
	}

	private static int alternativeIndex(NeutralPlacementGraph.Node node, PlacementState state) {
		for(int index = 0; index < node.legalAlternatives().size(); index++)
			if(node.legalAlternatives().get(index).equals(state))
				return index;
		return -1;
	}

	private static List<Integer> compatibleFoutAlternatives(NeutralPlacementGraph graph,
		CompiledHopKey owner, FType required) {
		NeutralPlacementGraph.Node node = graph.node(owner).orElse(null);
		if(node == null || required == null)
			return List.of();
		List<Integer> result = new ArrayList<>();
		for(int index = 0; index < node.legalAlternatives().size(); index++) {
			PlacementState state = node.legalAlternatives().get(index);
			if(state.output() == FederatedOutput.FOUT && state.fType() == required)
				result.add(index);
		}
		return List.copyOf(result);
	}

	/** Bounded final-emission authority; support clauses are serialized once per receipt. */
	private static Map<String,Object> candidateInventoryEmission(
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact emission) {
		return Map.of("selection", emission.selectionSignature(), "realizations", emission.realizations().stream()
			.map(realization -> realization.key().normalizedSignature()).toList());
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

	private static String resolveFrozenImports(String script, JsonNode imports, Path root,
		Map<String,String> pinned) {
		if(!imports.isObject())
			throw new IllegalArgumentException("Frozen import map is missing");
		Set<String> seen = new LinkedHashSet<>();
		Matcher matcher = SOURCE.matcher(script);
		StringBuffer result = new StringBuffer();
		while(matcher.find()) {
			String literal = matcher.group(1);
			JsonNode mapped = imports.path(literal);
			if(!mapped.isTextual() || !seen.add(literal))
				throw new IllegalArgumentException("Unpinned or duplicate frozen import: " + literal);
			Path imported = root.resolve(mapped.asText()).normalize();
			String relative = root.relativize(imported).toString();
			if(!imported.startsWith(root) || !Files.isRegularFile(imported) || !pinned.containsKey(relative))
				throw new IllegalArgumentException("Frozen import source is absent or unpinned: " + literal);
			matcher.appendReplacement(result, Matcher.quoteReplacement("source(\"" + imported + "\")"));
		}
		matcher.appendTail(result);
		Set<String> declared = new LinkedHashSet<>();
		imports.fieldNames().forEachRemaining(declared::add);
		if(!seen.equals(declared) || Pattern.compile("\\bsource\\s*\\(").matcher(result).results().count()
			!= SOURCE.matcher(result).results().count())
			throw new IllegalArgumentException("Frozen import map differs from program imports");
		return result.toString();
	}

	private static Map<String,PrebuilderSnapshot.ExternalSource> frozenSourceFacts(JsonNode sources,
		int workers) {
		if(!sources.isArray() || sources.isEmpty())
			throw new IllegalArgumentException("Frozen federated source facts are missing");
		Map<String,PrebuilderSnapshot.ExternalSource> result = new LinkedHashMap<>();
		for(JsonNode source : sources) {
			String variable = source.path("variable").asText();
			JsonNode originsNode = source.path("origins");
			String privacy = source.path("privacy").asText();
			String federationType = source.path("federationType").asText();
			if(variable.isEmpty() || !originsNode.isArray() || originsNode.size() != workers
				|| !(privacy.equals("PUBLIC") || privacy.equals("PRIVATE_AGGREGATE"))
				|| !Set.of("ROW", "COL", "FULL").contains(federationType))
				throw new IllegalArgumentException("Malformed frozen federated source fact: " + variable);
			List<String> origins = new ArrayList<>();
			for(JsonNode origin : originsNode) {
				if(!origin.isTextual() || origin.asText().isBlank())
					throw new IllegalArgumentException("Frozen source origin is missing: " + variable);
				origins.add(origin.asText());
			}
			if(result.put(variable, new PrebuilderSnapshot.ExternalSource(
				String.join(",", origins), privacy, federationType)) != null)
				throw new IllegalArgumentException("Duplicate frozen source fact: " + variable);
		}
		return result;
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
		return shaJson(List.of(graph.blocks(), graph.roots(), graph.nodes(),
			graph.edges(), graph.functions(), graph.calls(), graph.inlinedCalls()));
	}

	private static ArtifactWrite writeArtifact(Path destination, Object artifact) throws Exception {
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), ".native-model-", ".json.gz.tmp");
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		CountingOutputStream plain = null;
		try {
			try(GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(temporary));
				DigestOutputStream hashing = new DigestOutputStream(gzip, digest);
				CountingOutputStream counting = new CountingOutputStream(hashing)) {
				plain = counting;
				JSON.writeValue(counting, artifact);
			}
			ArtifactWrite result = new ArtifactWrite(hex(digest.digest()), plain.count());
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			return result;
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String shaJson(Object value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try(DigestOutputStream stream = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
			JSON.writeValue(stream, value);
		}
		return hex(digest.digest());
	}

	private static String sha(byte[] bytes) throws Exception {
		return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String hex(byte[] digest) {
		return java.util.HexFormat.of().formatHex(digest);
	}

	private record ArtifactWrite(String sha256, long bytes) { }

	private static final class CountingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long count;
		private CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }
		@Override public void write(int value) throws java.io.IOException {
			delegate.write(value);
			count++;
		}
		@Override public void write(byte[] values, int offset, int length) throws java.io.IOException {
			delegate.write(values, offset, length);
			count += length;
		}
		@Override public void flush() throws java.io.IOException { delegate.flush(); }
		@Override public void close() throws java.io.IOException { delegate.close(); }
		private long count() { return count; }
	}

	private static String shaText(String text) {
		try {
			return sha(text.getBytes(StandardCharsets.UTF_8));
		}
		catch(Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}
}

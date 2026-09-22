/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PlanningNativeModelCapture;

/** Independent E model capture for a frozen, post-rewrite planning workload. */
public final class ExactPlanningModelCapture {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final BigInteger MAX_FACTOR_TABLE_CELLS = BigInteger.valueOf(250_000);

	private ExactPlanningModelCapture() { }

	/** Usage: catalog.json evaluation-root cell-id artifact.json.gz */
	public static void main(String[] args) throws Exception {
		if(args.length != 4)
			throw new IllegalArgumentException("Expected: catalog.json evaluation-root cell-id artifact.json.gz");
		Map<String,Object> result = capture(Path.of(args[0]), Path.of(args[1]), args[2],
			true, Path.of(args[3]));
		System.out.println(JSON.writeValueAsString(result));
		if("ERROR".equals(result.get("status")))
			throw new IllegalStateException(String.valueOf(result.get("error")));
	}

	public static Map<String,Object> capture(Path catalogPath, Path evaluationRoot, String cellId,
		boolean requireEnvironment, Path artifactPath) {
		try {
			var input = PlanningNativeModelCapture.prepareInput(catalogPath, evaluationRoot,
				cellId, requireEnvironment);
			// E constructs its own detached analysis and legality factors. The P capture's
			// graph, native domains and acceptance predicate are not imported here.
			var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(input.program());
			var identity = PlanSpaceComparisonIdentity.from(analysis, input.finalGraph());
			var model = ExactPhysicalModel.build(analysis);
			List<Map<String,Object>> domains = new ArrayList<>();
			List<Integer> radices = new ArrayList<>();
			Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
			for(int index = 0; index < model.domains().size(); index++) {
				var domain = model.domains().get(index);
				positions.put(domain.variable(), index);
				radices.add(domain.alternatives().size());
				List<Map<String,Object>> alternatives = new ArrayList<>();
				for(var alternative : domain.alternatives()) {
					Map<String,Object> item = new LinkedHashMap<>();
					item.put("signature", alternative.signature());
					item.put("state", alternative.state().normalizedSignature());
					item.put("authorityKind", alternative.authorityKind().name());
					item.put("candidateRule", alternative.candidateRule() == null ? null
						: alternative.candidateRule().key().normalizedSignature());
					item.put("candidateEmission", alternative.candidateEmission() == null ? null
						: alternative.candidateEmission().selectionSignature());
					item.put("executionRule", alternative.executionRule() == null ? null
						: alternative.executionRule().key().normalizedSignature());
					item.put("executionEmission", alternative.executionEmission() == null ? null
						: alternative.executionEmission().selectionSignature());
					item.put("realization", alternative.realization() == null ? null
						: alternative.realization().key().normalizedSignature());
					item.put("supportClause", alternative.supportClause() == null ? null
						: alternative.supportClause().normalizedSignature());
					item.put("relocation", alternative.relocationAction() == null ? null
						: alternative.relocationAction().normalizedSignature());
					item.put("derivedFout", alternative.derivedFoutAction() == null ? null
						: alternative.derivedFoutAction().normalizedSignature());
					item.put("inputAuthorities", alternative.inputAuthorities().stream()
						.map(ExactPhysicalModel.InputAuthority::signature).toList());
					alternatives.add(item);
				}
				domains.add(Map.of("index", index, "occurrence", identity.occurrence(domain.node().key()),
					"nodeKind", domain.node().kind().name(), "alternatives", alternatives));
			}
			List<Map<String,Object>> factors = new ArrayList<>();
			BigInteger tableBudget = MAX_FACTOR_TABLE_CELLS;
			int opaque = 0;
			for(var factor : model.hardFactors()) {
				List<Integer> scope = new ArrayList<>();
				BigInteger cells = BigInteger.ONE;
				for(var variable : factor.scope()) {
					Integer position = positions.get(variable);
					if(position == null) throw new IllegalStateException("E factor references unknown domain");
					scope.add(position);
					cells = cells.multiply(BigInteger.valueOf(variable.domainSize()));
				}
				Map<String,Object> entry = new LinkedHashMap<>();
				entry.put("scope", scope);
				entry.put("cells", cells.toString());
				if(cells.compareTo(tableBudget) <= 0 && cells.compareTo(BigInteger.valueOf(100_000)) <= 0) {
					List<String> truth = new ArrayList<>(cells.intValueExact());
					int[] values = new int[scope.size()];
					for(int row = 0; row < cells.intValueExact(); row++) {
						int remainder = row;
						for(int j = scope.size() - 1; j >= 0; j--) {
							int radix = radices.get(scope.get(j));
							values[j] = remainder % radix;
							remainder /= radix;
						}
						try {
							double cost = factor.cost(values);
							truth.add(cost == 0.0 && Double.doubleToRawLongBits(cost) !=
								Double.doubleToRawLongBits(-0.0d) ? "ALLOW"
								: cost == Double.POSITIVE_INFINITY ? "REJECT" : "UNKNOWN");
						}
						catch(RuntimeException error) { truth.add("UNKNOWN"); }
					}
					entry.put("truth", truth);
					tableBudget = tableBudget.subtract(cells);
				}
				else {
					entry.put("truth", "OPAQUE_JAVA_PREDICATE");
					opaque++;
				}
				factors.add(entry);
			}
			Map<String,Object> artifact = new LinkedHashMap<>();
			artifact.put("schema", "closed-e-native-model-artifact-v1");
			artifact.put("cell", cellId);
			artifact.put("source", "E_C0");
			artifact.put("conditionSha256", input.conditionSha256());
			artifact.put("programSha256", input.programSha256());
			artifact.put("sourceFiles", input.sourceFiles());
			artifact.put("compilerBoundary", "POST_REWRITE_HOPS_DAG_PRE_PLANNER");
			artifact.put("sourceIdentity", Map.of("nodes", identity.nodes(),
				"orderedInputs", identity.orderedInputs(), "logicalInputs", identity.logicalInputs()));
			artifact.put("domains", domains);
			artifact.put("factors", factors);
			artifact.put("acceptance", opaque == 0 ? "MATERIALIZED_FACTOR_TABLES"
				: "PARTLY_OPAQUE_JAVA_PREDICATES");
			byte[] plain = JSON.writeValueAsBytes(artifact);
			Path destination = artifactPath.toAbsolutePath().normalize();
			Files.createDirectories(destination.getParent());
			Path temporary = Files.createTempFile(destination.getParent(), ".e-model-", ".json.gz.tmp");
			try {
				try(GZIPOutputStream stream = new GZIPOutputStream(Files.newOutputStream(temporary))) {
					stream.write(plain);
				}
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			finally { Files.deleteIfExists(temporary); }
			Map<String,Object> result = new LinkedHashMap<>();
			result.put("schema", "closed-native-model-capture-v1");
			result.put("cell", cellId);
			result.put("source", "E_C0");
			result.put("status", "COMPLETE");
			result.put("radices", radices);
			result.put("rawCount", ExactPhysicalRawSpaceExporter.size(model).toString());
			result.put("domainCount", domains.size());
			result.put("factorCount", factors.size());
			result.put("opaqueFactors", opaque);
			result.put("artifactPath", destination.toString());
			result.put("artifactSha256", sha(plain));
			result.put("artifactBytes", plain.length);
			result.put("compilerBoundary", "POST_REWRITE_HOPS_DAG_PRE_PLANNER");
			result.put("physicalDecode", "AVAILABLE_IN_JAVA_NOT_EXHAUSTED");
			result.put("runtimeSemanticCoverage", "NOT_ASSESSED_BY_THIS_CONTRACT");
			return result;
		}
		catch(Exception error) {
			return Map.of("schema", "closed-native-model-capture-v1", "cell", cellId,
				"source", "E_C0", "status", "ERROR", "errorClass", error.getClass().getName(),
				"error", String.valueOf(error.getMessage()));
		}
	}

	private static String sha(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder result = new StringBuilder(digest.length * 2);
		for(byte value : digest) result.append(String.format("%02x", value & 0xff));
		return result.toString();
	}
}

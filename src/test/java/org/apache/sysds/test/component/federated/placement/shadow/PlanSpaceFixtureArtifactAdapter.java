/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalFixtureArtifactBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;

/**
 * Sharded, test-owned raw fixture artifacts. Every requested ordinal is emitted,
 * but the receipt is UNKNOWN until independently complete P/E domains and a
 * physical-plan-v1 projection have been proved. This cannot certify a runner cell.
 */
public final class PlanSpaceFixtureArtifactAdapter {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final String COVERAGE_REASON = "INDEPENDENT_P_E_DOMAIN_COMPLETENESS_UNPROVED";

	private PlanSpaceFixtureArtifactAdapter() { }

	public static void main(String[] args) throws Exception {
		if(args.length == 3 && "size".equals(args[0])) {
			System.out.println(size(args[1], args[2]));
			return;
		}
		if(args.length != 7)
			throw new IllegalArgumentException("Expected: P|E fixture start stop rows receipt audit");
		export(args[0], args[1], new BigInteger(args[2]), new BigInteger(args[3]),
			Path.of(args[4]), Path.of(args[5]), Path.of(args[6]));
	}

	public static BigInteger size(String kind, String fixture) throws Exception {
		checkFixture(fixture);
		if("P".equals(kind))
			return new FullProductionJointPlanExport(protectedAnalysis(fixture)).rawCount();
		if("E".equals(kind))
			return ExactPhysicalFixtureArtifactBridge.size(fixture);
		throw new IllegalArgumentException("Unknown exporter kind: " + kind);
	}

	public static void export(String kind, String fixture, BigInteger start, BigInteger stop,
		Path rows, Path receipt, Path audit) throws Exception {
		checkFixture(fixture);
		BigInteger total = size(kind, fixture);
		if(start.signum() < 0 || stop.compareTo(start) < 0 || stop.compareTo(total) > 0)
			throw new IllegalArgumentException("Invalid half-open ordinal range");
		for(Path path : List.of(rows, receipt, audit)) {
			Path parent = path.toAbsolutePath().getParent();
			if(parent != null)
				Files.createDirectories(parent);
		}
		String sourceSha256 = sha256(ProductionShadowFixtureFactory.scripts().get(fixture));
		// The native finite model can classify a raw row even when the cross-model
		// physical decoder is not yet complete. Keep those two statuses separate.
		BigInteger[] nativeCounts = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
		try(BufferedWriter rowWriter = Files.newBufferedWriter(rows, StandardCharsets.UTF_8);
			BufferedWriter auditWriter = Files.newBufferedWriter(audit, StandardCharsets.UTF_8)) {
			if("P".equals(kind)) {
				FullProductionJointPlanExport exporter = new FullProductionJointPlanExport(protectedAnalysis(fixture));
				if(!exporter.rawCount().equals(total))
					throw new IllegalStateException("P fixture graph changed between size and stream");
				exporter.stream(start, stop, item -> {
					Map<String,Object> row = baseRow(kind, fixture, sourceSha256, item.ordinal());
					Map<String,Object> identity = new LinkedHashMap<>();
					Map<String,String> states = new LinkedHashMap<>();
					item.assignment().forEach((key, state) ->
						states.put(key.normalizedSignature(), state.normalizedSignature()));
					identity.put("states", states);
					identity.put("candidateReceipts", item.candidates().stream()
						.map(value -> value.normalizedSignature()).toList());
					identity.put("relocationReceipts", item.relocations().stream()
						.map(value -> value.normalizedSignature()).toList());
					row.put("identity", identity);
					row.put("productionVerdict", item.verdict().name());
					row.put("productionReason", item.reason());
					String status = switch(item.verdict()) {
						case ACCEPTED -> "EMITTED";
						case REJECTED -> "REJECTED";
						case UNKNOWN -> "ERROR";
					};
					count(nativeCounts, status);
					writeRow(rowWriter, auditWriter, row, item.ordinal(), status);
				});
			}
			else if("E".equals(kind))
				ExactPhysicalFixtureArtifactBridge.stream(fixture, start, stop, item -> {
					BigInteger ordinal = new BigInteger((String) item.get("ordinal"));
					Map<String,Object> row = baseRow(kind, fixture, sourceSha256, ordinal);
				row.put("identity", item.get("identity"));
				String modelStatus = (String)((Map<?,?>) item.get("identity")).get("modelStatus");
				String status = "EMITTED".equals(modelStatus) ? "EMITTED"
					: "REJECTED".equals(modelStatus) ? "REJECTED" : "ERROR";
				count(nativeCounts, status);
				writeRow(rowWriter, auditWriter, row, ordinal, status);
				});
			else
				throw new IllegalArgumentException("Unknown exporter kind: " + kind);
		}
		catch(UncheckedIOException error) {
			throw error.getCause();
		}
		Map<String,Object> proof = new LinkedHashMap<>();
		proof.put("schema", "raw-fixture-export-v1");
		proof.put("status", "UNKNOWN");
		proof.put("reason", COVERAGE_REASON);
		proof.put("fixture", fixture);
		proof.put("kind", kind);
		proof.put("fixtureSourceSha256", sourceSha256);
		proof.put("rawDomainSize", total.toString());
		proof.put("start", start.toString());
		proof.put("stop", stop.toString());
		proof.put("processed", stop.subtract(start));
		proof.put("unknown", stop.subtract(start));
		proof.put("emitted", 0);
		proof.put("nativeEmitted", nativeCounts[0]);
		proof.put("nativeRejected", nativeCounts[1]);
		proof.put("nativeErrors", nativeCounts[2]);
		proof.put("nativeCoverage", start.signum() == 0 && stop.equals(total)
			&& nativeCounts[2].signum() == 0 ? "COMPLETE" : "PARTIAL");
		proof.put("physicalDecode", "UNPROVED");
		proof.put("rowsSha256", sha256(rows));
		proof.put("auditSha256", sha256(audit));
		Files.writeString(receipt, JSON.writeValueAsString(proof) + '\n', StandardCharsets.UTF_8);
	}

	private static Map<String,Object> baseRow(String kind, String fixture, String sourceSha256,
		BigInteger ordinal) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "raw-fixture-export-v1");
		row.put("kind", kind);
		row.put("fixture", fixture);
		row.put("fixtureSourceSha256", sourceSha256);
		row.put("ordinal", ordinal);
		row.put("globalCoverageStatus", "UNKNOWN");
		row.put("globalCoverageReason", COVERAGE_REASON);
		return row;
	}

	private static void count(BigInteger[] counts, String status) {
		int index = "EMITTED".equals(status) ? 0 : "REJECTED".equals(status) ? 1 : 2;
		counts[index] = counts[index].add(BigInteger.ONE);
	}

	private static void writeRow(BufferedWriter rows, BufferedWriter audit,
		Map<String,Object> row, BigInteger ordinal, String status) {
		try {
			rows.write(JSON.writeValueAsString(row));
			rows.newLine();
			Map<String,Object> auditRow = new LinkedHashMap<>();
			auditRow.put("index", ordinal);
			auditRow.put("status", status);
			auditRow.put("raw", row);
			audit.write(JSON.writeValueAsString(auditRow));
			audit.newLine();
		}
		catch(IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis
		protectedAnalysis(String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void checkFixture(String fixture) {
		if(!ProductionShadowFixtureFactory.ids().contains(fixture))
			throw new IllegalArgumentException("Unknown protected fixture: " + fixture);
	}

	private static String sha256(String text) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256")
			.digest(text.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(hash);
	}

	private static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try(InputStream stream = Files.newInputStream(path)) {
			byte[] block = new byte[8192];
			int size;
			while((size = stream.read(block)) != -1)
				digest.update(block, 0, size);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}

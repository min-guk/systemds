/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;

/** Streams a complete P-native state shard into exact physical rows. */
public final class CurrentPlanningPhysicalRows {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	private CurrentPlanningPhysicalRows() { }

	/** Usage: catalog.json evaluation-root cell-id state-start state-stop rows.jsonl.gz receipt.json */
	public static void main(String[] args) throws Exception {
		if(args.length != 7)
			throw new IllegalArgumentException("Expected: catalog evaluation-root cell start stop rows.gz receipt");
		Path catalog = Path.of(args[0]);
		Path evaluation = Path.of(args[1]);
		String cell = args[2];
		BigInteger begin = new BigInteger(args[3]);
		BigInteger end = new BigInteger(args[4]);
		Path rows = Path.of(args[5]).toAbsolutePath().normalize();
		Path receipt = Path.of(args[6]).toAbsolutePath().normalize();
		var input = PlanningNativeModelCapture.prepareInput(catalog, evaluation, cell, true);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(input.program());
		var identity = PlanSpaceComparisonIdentity.from(analysis, input.finalGraph());
		var projector = new CurrentPPhysicalPlanRows(analysis, identity, input.programSha256());
		var relation = new ClosedPlanRelationEnumerator(analysis);
		if(begin.signum() < 0 || end.compareTo(begin) < 0 || end.compareTo(relation.stateCount()) > 0)
			throw new IllegalArgumentException("State shard outside finite P state domain");
		Files.createDirectories(rows.getParent());
		Files.createDirectories(receipt.getParent());
		Path temporary = Files.createTempFile(rows.getParent(), ".p-physical-", ".jsonl.gz.tmp");
		AtomicLong emitted = new AtomicLong();
		ClosedPlanRelationEnumerator.Summary summary;
		try {
			try(BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8))) {
				summary = relation.enumerateStates(begin, end, audit -> {
					try {
						writer.write(JSON.writeValueAsString(projector.physicalPlan(audit)));
						writer.newLine();
						emitted.incrementAndGet();
					}
					catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
				});
			}
			if(summary.unknown().signum() != 0 || !summary.accepted().equals(BigInteger.valueOf(emitted.get())))
				throw new IllegalStateException("P shard has unresolved native verdict or output count");
			Files.move(temporary, rows, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		finally { Files.deleteIfExists(temporary); }
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("schema", "closed-planning-physical-shard-v1");
		result.put("source", "P_C0");
		result.put("cell", cell);
		result.put("status", "COMPLETE");
		result.put("stateCount", relation.stateCount().toString());
		result.put("start", begin.toString());
		result.put("stop", end.toString());
		result.put("raw", summary.raw().toString());
		result.put("accepted", summary.accepted().toString());
		result.put("rejected", summary.rejected().toString());
		result.put("unknown", summary.unknown().toString());
		result.put("candidatePruned", summary.candidatePruned().toString());
		result.put("emittedRows", emitted.get());
		result.put("rowsSha256", sha(rows));
		result.put("conditionSha256", input.conditionSha256());
		result.put("programSha256", input.programSha256());
		result.put("sourceFiles", input.sourceFiles());
		result.put("rows", rows.toString());
		result.put("runtimeSemanticCoverage", "NOT_ASSESSED_BY_THIS_CONTRACT");
		Path receiptTemp = Files.createTempFile(receipt.getParent(), ".p-physical-receipt-", ".tmp");
		try {
			JSON.writeValue(receiptTemp.toFile(), result);
			Files.move(receiptTemp, receipt, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		finally { Files.deleteIfExists(receiptTemp); }
		System.out.println(JSON.writeValueAsString(result));
	}

	private static String sha(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try(var stream = Files.newInputStream(file)) {
			byte[] block = new byte[1024 * 1024];
			for(int count; (count = stream.read(block)) != -1;)
				digest.update(block, 0, count);
		}
		StringBuilder result = new StringBuilder(64);
		for(byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
		return result.toString();
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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

/** Exact E-native prefix traversal; rejects a subtree only after a scoped hard factor fails. */
public final class ExactPlanningPhysicalRows {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	private record Scoped(ExactCategoricalSolver.Factor factor, int[] positions, int last) { }
	private final ExactPhysicalModel model;
	private final PlanSpaceComparisonIdentity identity;
	private final String logical;
	private final BufferedWriter writer;
	private final int[] values;
	private final List<List<Scoped>> ready;
	private final BigInteger[] suffix;
	private BigInteger rejected = BigInteger.ZERO;
	private BigInteger unknown = BigInteger.ZERO;
	private BigInteger accepted = BigInteger.ZERO;
	private final MessageDigest acceptedOrdinals;

	private ExactPlanningPhysicalRows(ExactPhysicalModel model, PlanSpaceComparisonIdentity identity,
		String logical, BufferedWriter writer) {
		this.model = model;
		this.identity = identity;
		this.logical = logical;
		this.writer = writer;
		try { acceptedOrdinals = MessageDigest.getInstance("SHA-256"); }
		catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
		values = new int[model.domains().size()];
		suffix = new BigInteger[values.length + 1];
		suffix[values.length] = BigInteger.ONE;
		for(int i = values.length - 1; i >= 0; i--)
			suffix[i] = suffix[i + 1].multiply(BigInteger.valueOf(model.domains().get(i).alternatives().size()));
		ready = new ArrayList<>();
		for(int i = 0; i <= values.length; i++) ready.add(new ArrayList<>());
		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int i = 0; i < values.length; i++) positions.put(model.domains().get(i).variable(), i);
		for(var factor : model.hardFactors()) {
			int[] scope = new int[factor.scope().size()];
			int last = -1;
			for(int i = 0; i < scope.length; i++) {
				Integer position = positions.get(factor.scope().get(i));
				if(position == null) throw new IllegalStateException("E factor references missing domain");
				scope[i] = position;
				last = Math.max(last, position);
			}
			ready.get(last + 1).add(new Scoped(factor, scope, last));
		}
	}

	/** Usage: catalog.json evaluation-root cell-id rows.jsonl.gz receipt.json */
	public static void main(String[] args) throws Exception {
		if(args.length != 5)
			throw new IllegalArgumentException("Expected: catalog evaluation-root cell rows.gz receipt");
		Path catalog = Path.of(args[0]);
		Path evaluation = Path.of(args[1]);
		String cell = args[2];
		Path rows = Path.of(args[3]).toAbsolutePath().normalize();
		Path receipt = Path.of(args[4]).toAbsolutePath().normalize();
		var input = PlanningNativeModelCapture.prepareInput(catalog, evaluation, cell, true);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(input.program());
		var identity = PlanSpaceComparisonIdentity.from(analysis, input.finalGraph());
		var model = ExactPhysicalModel.build(analysis);
		Files.createDirectories(rows.getParent());
		Files.createDirectories(receipt.getParent());
		Path temporary = Files.createTempFile(rows.getParent(), ".e-physical-", ".jsonl.gz.tmp");
		ExactPlanningPhysicalRows stream;
		try {
			try(BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8))) {
				stream = new ExactPlanningPhysicalRows(model, identity, input.programSha256(), writer);
				stream.walk(0, BigInteger.ZERO, false);
			}
			if(stream.unknown.signum() != 0 ||
				!stream.accepted.add(stream.rejected).equals(stream.suffix[0]))
				throw new IllegalStateException("E raw assignment coverage is not closed");
			Files.move(temporary, rows, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		finally { Files.deleteIfExists(temporary); }
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("schema", "closed-planning-physical-shard-v1");
		result.put("source", "E_C0");
		result.put("cell", cell);
		result.put("status", "COMPLETE");
		result.put("raw", stream.suffix[0].toString());
		result.put("accepted", stream.accepted.toString());
		result.put("rejected", stream.rejected.toString());
		result.put("unknown", stream.unknown.toString());
		result.put("acceptedOrdinalsSha256", hex(stream.acceptedOrdinals.digest()));
		result.put("rowsSha256", sha(rows));
		result.put("conditionSha256", input.conditionSha256());
		result.put("programSha256", input.programSha256());
		result.put("sourceFiles", input.sourceFiles());
		result.put("rows", rows.toString());
		result.put("runtimeSemanticCoverage", "NOT_ASSESSED_BY_THIS_CONTRACT");
		Path receiptTemp = Files.createTempFile(receipt.getParent(), ".e-physical-receipt-", ".tmp");
		try {
			JSON.writeValue(receiptTemp.toFile(), result);
			Files.move(receiptTemp, receipt, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		finally { Files.deleteIfExists(receiptTemp); }
		System.out.println(JSON.writeValueAsString(result));
	}

	private void walk(int position, BigInteger ordinal, boolean unresolved) {
		if(position == 0)
			for(Scoped factor : ready.get(0)) {
				try {
					double cost = factor.factor().cost(new int[0]);
					if(cost == Double.POSITIVE_INFINITY) {
						rejected = rejected.add(suffix[0]);
						return;
					}
					if(cost != 0.0 || Double.doubleToRawLongBits(cost) ==
						Double.doubleToRawLongBits(-0.0d)) unresolved = true;
				}
				catch(RuntimeException error) { unresolved = true; }
			}
		if(position == values.length) {
			if(unresolved) { unknown = unknown.add(BigInteger.ONE); return; }
			ExactPhysicalPlanSpaceExporter.visit(model, ordinal, ordinal.add(BigInteger.ONE), row -> {
				if(row.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED)
					throw new IllegalStateException("E prefix and full validation disagree at " + ordinal);
				try {
					writer.write(JSON.writeValueAsString(ExactPhysicalComparisonRow.project(
						model, identity, row, logical)));
					writer.newLine();
				}
				catch(java.io.IOException error) { throw new UncheckedIOException(error); }
			});
			accepted = accepted.add(BigInteger.ONE);
			acceptedOrdinals.update((ordinal + "\n").getBytes(StandardCharsets.US_ASCII));
			return;
		}
		int radix = model.domains().get(position).alternatives().size();
		for(int value = 0; value < radix; value++) {
			values[position] = value;
			BigInteger next = ordinal.multiply(BigInteger.valueOf(radix)).add(BigInteger.valueOf(value));
			boolean localUnknown = unresolved;
			boolean invalid = false;
			for(Scoped factor : ready.get(position + 1)) {
				int[] scoped = new int[factor.positions().length];
				for(int i = 0; i < scoped.length; i++) scoped[i] = values[factor.positions()[i]];
				try {
					double cost = factor.factor().cost(scoped);
					if(cost == Double.POSITIVE_INFINITY) { invalid = true; break; }
					if(cost != 0.0 || Double.doubleToRawLongBits(cost) ==
						Double.doubleToRawLongBits(-0.0d)) localUnknown = true;
				}
				catch(RuntimeException error) { localUnknown = true; }
			}
			if(invalid) rejected = rejected.add(suffix[position + 1]);
			else walk(position + 1, next, localUnknown);
		}
	}

	private static String sha(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try(var stream = Files.newInputStream(file)) {
			byte[] block = new byte[1024 * 1024];
			for(int count; (count = stream.read(block)) != -1;)
				digest.update(block, 0, count);
		}
		return hex(digest.digest());
	}

	private static String hex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for(byte value : bytes) result.append(String.format("%02x", value & 0xff));
		return result.toString();
	}
}

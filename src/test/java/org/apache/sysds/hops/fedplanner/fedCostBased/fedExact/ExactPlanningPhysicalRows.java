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
import java.util.Arrays;
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
	private static final class Trie {
		private final Map<Integer,Trie> children = new LinkedHashMap<>();
	}
	private record Component(int[] positions, List<Scoped> factors, Trie accepted,
		BigInteger acceptedCount, BigInteger unknownCount, BigInteger rawCount) { }
	record Summary(BigInteger accepted, BigInteger rejected, BigInteger unknown) { }
	private final ExactPhysicalModel model;
	private final PlanSpaceComparisonIdentity identity;
	private final String logical;
	private final BufferedWriter writer;
	private final BufferedWriter references;
	private final Map<String,Integer> dictionaryIds;
	private final int[] values;
	private final List<List<Scoped>> ready;
	private final List<Scoped> fullValidation;
	private final BigInteger[] suffix;
	private BigInteger rejected = BigInteger.ZERO;
	private BigInteger unknown = BigInteger.ZERO;
	private BigInteger accepted = BigInteger.ZERO;
	private final MessageDigest acceptedOrdinals;

	ExactPlanningPhysicalRows(ExactPhysicalModel model, PlanSpaceComparisonIdentity identity,
		String logical, BufferedWriter writer, BufferedWriter references) {
		this.model = model;
		this.identity = identity;
		this.logical = logical;
		this.writer = writer;
		this.references = references;
		dictionaryIds = references == null ? null : new LinkedHashMap<>();
		try { acceptedOrdinals = MessageDigest.getInstance("SHA-256"); }
		catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
		values = new int[model.domains().size()];
		suffix = new BigInteger[values.length + 1];
		suffix[values.length] = BigInteger.ONE;
		for(int i = values.length - 1; i >= 0; i--)
			suffix[i] = suffix[i + 1].multiply(BigInteger.valueOf(model.domains().get(i).alternatives().size()));
		ready = new ArrayList<>();
		fullValidation = new ArrayList<>();
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
			Scoped scoped = new Scoped(factor, scope, last);
			ready.get(last + 1).add(scoped);
		}
		Map<ExactCategoricalSolver.Variable,Integer> validationPositions = new IdentityHashMap<>();
		for(int i = 0; i < model.variables().size(); i++)
			validationPositions.put(model.variables().get(i), i);
		for(var factor : model.hardFactors()) {
			int[] scope = new int[factor.scope().size()];
			int last = -1;
			for(int i = 0; i < scope.length; i++) {
				Integer position = validationPositions.get(factor.scope().get(i));
				if(position == null) throw new IllegalStateException(
					"E validation factor references missing variable");
				scope[i] = position;
				last = Math.max(last, position);
			}
			fullValidation.add(new Scoped(factor, scope, last));
		}
	}

	/** Usage: catalog evaluation-root cell rows.gz [references.gz] receipt. */
	public static void main(String[] args) throws Exception {
		if(args.length != 5 && args.length != 6)
			throw new IllegalArgumentException(
				"Expected: catalog evaluation-root cell rows.gz [references.gz] receipt");
		Path catalog = Path.of(args[0]);
		Path evaluation = Path.of(args[1]);
		String cell = args[2];
		Path rows = Path.of(args[3]).toAbsolutePath().normalize();
		Path references = args.length == 6 ? Path.of(args[4]).toAbsolutePath().normalize() : null;
		Path receipt = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
		var input = PlanningNativeModelCapture.prepareInput(catalog, evaluation, cell, true);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(input.program());
		var identity = PlanSpaceComparisonIdentity.from(analysis, input.finalGraph());
		var model = ExactPhysicalModel.build(analysis);
		Files.createDirectories(rows.getParent());
		if(references != null) Files.createDirectories(references.getParent());
		Files.createDirectories(receipt.getParent());
		Path temporary = Files.createTempFile(rows.getParent(), ".e-physical-", ".jsonl.gz.tmp");
		Path referenceTemporary = references == null ? null : Files.createTempFile(
			references.getParent(), ".e-physical-references-", ".tsv.gz.tmp");
		ExactPlanningPhysicalRows stream;
		try {
			try(BufferedWriter writer = gzipWriter(temporary);
				BufferedWriter referenceWriter = referenceTemporary == null ? null
					: gzipWriter(referenceTemporary)) {
				stream = new ExactPlanningPhysicalRows(model, identity, input.programSha256(), writer,
					referenceWriter);
				if(Boolean.getBoolean("g009.eComponentEnumeration")) stream.walkComponents();
				else stream.walk(0, BigInteger.ZERO, false);
			}
			if(stream.unknown.signum() != 0 ||
				!stream.accepted.add(stream.rejected).equals(stream.suffix[0]))
				throw new IllegalStateException("E raw assignment coverage is not closed");
			Files.move(temporary, rows, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			if(references != null)
				Files.move(referenceTemporary, references, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		}
		finally {
			Files.deleteIfExists(temporary);
			if(referenceTemporary != null) Files.deleteIfExists(referenceTemporary);
		}
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("schema", references == null ? "closed-planning-physical-shard-v1"
			: "closed-planning-physical-shard-compact-v1");
		result.put("source", "E_C0");
		result.put("cell", cell);
		result.put("status", "COMPLETE");
		result.put("raw", stream.suffix[0].toString());
		result.put("accepted", stream.accepted.toString());
		result.put("rejected", stream.rejected.toString());
		result.put("unknown", stream.unknown.toString());
		result.put("acceptedOrdinalsSha256", hex(stream.acceptedOrdinals.digest()));
		if(references == null) result.put("rowsSha256", sha(rows));
		else {
			result.put("dictionarySha256", sha(rows));
			result.put("dictionaryCount", stream.dictionaryIds.size());
			result.put("references", references.toString());
			result.put("referencesSha256", sha(references));
			result.put("emittedRows", stream.accepted.toString());
		}
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

	private static BufferedWriter gzipWriter(Path path) throws java.io.IOException {
		return new BufferedWriter(new OutputStreamWriter(
			new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8));
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
			validateAcceptedAssignment(ordinal);
			try { emit(ordinal, ExactPhysicalComparisonRow.project(model, identity, values, logical)); }
			catch(java.io.IOException error) { throw new UncheckedIOException(error); }
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

	void walkOriginal() { walk(0, BigInteger.ZERO, false); }

	void walkComponents() {
		List<Component> components = components();
		boolean zeroUnknown = false;
		for(Scoped factor : fullValidation) {
			if(Arrays.stream(factor.positions()).anyMatch(position ->
				model.domains().get(position).alternatives().size() > 1)) continue;
			try {
				double cost = factor.factor().cost(new int[factor.positions().length]);
				if(cost == Double.POSITIVE_INFINITY) { rejected = suffix[0]; return; }
				if(cost != 0.0 || Double.doubleToRawLongBits(cost)
					== Double.doubleToRawLongBits(-0.0d)) zeroUnknown = true;
			}
			catch(RuntimeException error) { zeroUnknown = true; }
		}
		BigInteger nonRejected = BigInteger.ONE;
		BigInteger acceptedProduct = BigInteger.ONE;
		for(Component component : components) {
			nonRejected = nonRejected.multiply(
				component.acceptedCount().add(component.unknownCount()));
			acceptedProduct = acceptedProduct.multiply(component.acceptedCount());
		}
		rejected = suffix[0].subtract(nonRejected);
		unknown = zeroUnknown ? nonRejected : nonRejected.subtract(acceptedProduct);
		if(zeroUnknown || acceptedProduct.signum() == 0) return;
		int[] componentByPosition = new int[values.length];
		Arrays.fill(componentByPosition, -1);
		for(int component = 0; component < components.size(); component++) {
			int[] positions = components.get(component).positions();
			for(int local = 0; local < positions.length; local++) {
				componentByPosition[positions[local]] = component;
			}
		}
		Trie[] cursors = components.stream().map(Component::accepted).toArray(Trie[]::new);
		walkAccepted(0, BigInteger.ZERO, componentByPosition, cursors);
		if(!accepted.equals(acceptedProduct))
			throw new IllegalStateException("E component enumeration accepted-count drift");
	}

	private void walkAccepted(int position, BigInteger ordinal,
		int[] componentByPosition, Trie[] cursors) {
		if(position == values.length) {
			validateAcceptedAssignment(ordinal);
			try { emit(ordinal, ExactPhysicalComparisonRow.project(model, identity, values, logical)); }
			catch(java.io.IOException error) { throw new UncheckedIOException(error); }
			accepted = accepted.add(BigInteger.ONE);
			acceptedOrdinals.update((ordinal + "\n").getBytes(StandardCharsets.US_ASCII));
			return;
		}
		int component = componentByPosition[position];
		int radix = model.domains().get(position).alternatives().size();
		if(component < 0) {
			if(radix != 1) throw new IllegalStateException("E varying domain lacks component");
			values[position] = 0;
			walkAccepted(position + 1, ordinal.multiply(BigInteger.valueOf(radix)),
				componentByPosition, cursors);
			return;
		}
		Trie parent = cursors[component];
		for(int value = 0; value < radix; value++) {
			Trie child = parent.children.get(value);
			if(child == null) continue;
			values[position] = value;
			cursors[component] = child;
			walkAccepted(position + 1, ordinal.multiply(BigInteger.valueOf(radix))
				.add(BigInteger.valueOf(value)), componentByPosition, cursors);
		}
		cursors[component] = parent;
	}

	private List<Component> components() {
		int count = values.length;
		int[] parent = new int[count];
		for(int i = 0; i < count; i++) parent[i] = i;
		for(Scoped factor : fullValidation) {
			int[] varying = Arrays.stream(factor.positions()).filter(position ->
				model.domains().get(position).alternatives().size() > 1).toArray();
			for(int i = 1; i < varying.length; i++) union(parent, varying[0], varying[i]);
		}
		Map<Integer,List<Integer>> grouped = new LinkedHashMap<>();
		for(int position = 0; position < count; position++)
			if(model.domains().get(position).alternatives().size() > 1)
				grouped.computeIfAbsent(find(parent, position), ignored -> new ArrayList<>()).add(position);
		List<Component> result = new ArrayList<>();
		for(List<Integer> group : grouped.values()) {
			int[] positions = group.stream().mapToInt(Integer::intValue).toArray();
			BigInteger raw = BigInteger.ONE;
			for(int position : positions) raw = raw.multiply(BigInteger.valueOf(
				model.domains().get(position).alternatives().size()));
			if(Boolean.getBoolean("g009.eComponentDiagnostics"))
				System.err.println("E component positions=" + Arrays.toString(positions) + " raw=" + raw);
			Map<Integer,Integer> local = new LinkedHashMap<>();
			for(int i = 0; i < positions.length; i++) local.put(positions[i], i);
			List<Scoped> factors = new ArrayList<>();
			for(Scoped factor : fullValidation) {
				int[] varying = Arrays.stream(factor.positions()).filter(position ->
					model.domains().get(position).alternatives().size() > 1).toArray();
				if(varying.length == 0 || find(parent, varying[0]) != find(parent, positions[0]))
					continue;
				int[] scoped = Arrays.stream(factor.positions())
					.map(position -> local.getOrDefault(position, -1)).toArray();
				factors.add(new Scoped(factor.factor(), scoped, -1));
			}
			Trie trie = new Trie();
			BigInteger[] counts = {BigInteger.ZERO, BigInteger.ZERO};
			int[] assignment = new int[positions.length];
			enumerateComponent(positions, factors, 0, assignment, trie, counts);
			result.add(new Component(positions, List.copyOf(factors), trie, counts[0], counts[1], raw));
		}
		return List.copyOf(result);
	}

	private void enumerateComponent(int[] positions, List<Scoped> factors, int offset,
		int[] assignment, Trie root, BigInteger[] counts) {
		if(offset < positions.length) {
			int radix = model.domains().get(positions[offset]).alternatives().size();
			for(int value = 0; value < radix; value++) {
				assignment[offset] = value;
				enumerateComponent(positions, factors, offset + 1, assignment, root, counts);
			}
			return;
		}
		boolean unresolved = false;
		for(Scoped factor : factors) {
			int[] scoped = Arrays.stream(factor.positions())
				.map(position -> position < 0 ? 0 : assignment[position]).toArray();
			try {
				double cost = factor.factor().cost(scoped);
				if(cost == Double.POSITIVE_INFINITY) return;
				if(cost != 0.0 || Double.doubleToRawLongBits(cost)
					== Double.doubleToRawLongBits(-0.0d)) unresolved = true;
			}
			catch(RuntimeException error) { unresolved = true; }
		}
		if(unresolved) { counts[1] = counts[1].add(BigInteger.ONE); return; }
		counts[0] = counts[0].add(BigInteger.ONE);
		Trie node = root;
		for(int value : assignment) node = node.children.computeIfAbsent(value, ignored -> new Trie());
	}

	private static int find(int[] parent, int value) {
		while(parent[value] != value) {
			parent[value] = parent[parent[value]];
			value = parent[value];
		}
		return value;
	}

	private static void union(int[] parent, int left, int right) {
		int a = find(parent, left), b = find(parent, right);
		if(a != b) parent[b] = a;
	}

	Summary summary() { return new Summary(accepted, rejected, unknown); }
	void writerFlushForTest() throws java.io.IOException { writer.flush(); }

	private void emit(BigInteger ordinal, Map<String,Object> plan) throws java.io.IOException {
		String row = JSON.writeValueAsString(plan);
		if(references == null) {
			writer.write(row);
			writer.newLine();
			return;
		}
		Integer planId = dictionaryIds.get(row);
		if(planId == null) {
			planId = dictionaryIds.size();
			dictionaryIds.put(row, planId);
			writer.write(row);
			writer.newLine();
		}
		references.write(ordinal.toString());
		references.write('\t');
		references.write(Integer.toString(planId));
		references.newLine();
	}

	/** Re-evaluates every hard factor from the complete assignment, independently of prefix readiness. */
	private void validateAcceptedAssignment(BigInteger ordinal) {
		for(Scoped scoped : fullValidation) {
			int[] local = new int[scoped.positions().length];
			for(int i = 0; i < local.length; i++) local[i] = values[scoped.positions()[i]];
			final double cost;
			try { cost = scoped.factor().cost(local); }
			catch(RuntimeException error) {
				throw new IllegalStateException(
					"E prefix and full validation disagree at " + ordinal, error);
			}
			if(cost == Double.POSITIVE_INFINITY || cost != 0.0
				|| Double.doubleToRawLongBits(cost) == Double.doubleToRawLongBits(-0.0d))
				throw new IllegalStateException(
					"E prefix and full validation disagree at " + ordinal);
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

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED-1 contract for exact occurrence-keyed registration across independently owned scopes. */
public class CampaignBG014ExactOccurrenceRegistrationRedTest {
	private static final String POISON = "poison.invalid:9/P@0:0-1:1|BROADCAST";
	private static final Path REGISTRATION_SOURCE = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/ExactPlacementRegistration.java");

	@Test
	public void sharedTargetAndAnchorEmitOneOrderedReceiptPerExactScope() {
		assertExactRegistration(false);
	}

	@Test
	public void sharedTargetBindsOnlyItsScopeLocalDistinctAnchor() {
		assertExactRegistration(true);
	}

	@Test
	public void sourceSanitizerRejectsLexicalDecoysIncludingEscapedTextBlockQuotes() {
		String source = "int G014_LIVE_BEFORE = 1;\n"
			+ "// G014_DECOY_LINE\n"
			+ "/* G014_DECOY_BLOCK */\n"
			+ "String plain = \"G014_DECOY_STRING\";\n"
			+ "char character = 'q';\n"
			+ "String escaped = \"prefix \\\" G014_DECOY_ESCAPED_STRING\";\n"
			+ "String block = \"\"\"\n"
			+ "G014_DECOY_TEXT_BLOCK\n"
			+ "\\\"\"\" G014_DECOY_ESCAPED_TEXT_BLOCK\n"
			+ "\"\"\";\n"
			+ "int G014_LIVE_AFTER = 2;\n";
		String sanitized = codeOnly(source);
		Assert.assertTrue("G014_RED1_SANITIZER_DROPPED_CODE_BEFORE_DECOYS",
			sanitized.contains("G014_LIVE_BEFORE"));
		Assert.assertTrue("G014_RED1_SANITIZER_DROPPED_CODE_AFTER_ESCAPED_TEXT_BLOCK_QUOTES",
			sanitized.contains("G014_LIVE_AFTER"));
		for(String decoy : List.of("G014_DECOY_LINE", "G014_DECOY_BLOCK", "G014_DECOY_STRING",
			"G014_DECOY_ESCAPED_STRING", "G014_DECOY_TEXT_BLOCK", "G014_DECOY_ESCAPED_TEXT_BLOCK"))
			Assert.assertFalse("G014_RED1_SANITIZER_LEAKED_" + decoy, sanitized.contains(decoy));
		int character = source.indexOf("'q'") + 1;
		Assert.assertEquals("G014_RED1_SANITIZER_LEAKED_CHAR_LITERAL", ' ', sanitized.charAt(character));
	}

	private static void assertExactRegistration(boolean distinctAnchors) {
		Fixture fixture = fixture(distinctAnchors);
		try {
			assertExactOccurrenceReceiptContract();
			String fingerprint = fixture.analysis().analysisFingerprint();
			FederatedPlannerUtils.clearFedAnchorKeys();
			for(DataOp anchor : fixture.anchors())
				FederatedPlannerUtils.registerFedAnchorKey(anchor.getName(), POISON);
			ExactPlacementRegistration.Receipt receipt;
			try {
				receipt = ExactPlacementRegistration.registerProgram(
					fixture.program(), fixture.selectedTypes(), fixture.analysis());
			}
			catch(DMLRuntimeException currentCrossScopeUnion) {
				Assert.fail("G014_RED1_EXACT_SCOPE_ANCHORS_WERE_UNIONED|" + currentCrossScopeUnion.getMessage());
				return;
			}

			Assert.assertEquals("G014_RED1_DROPPED_EXACT_OCCURRENCE", 2, receipt.uploads().size());
			assertExactReceipt(fixture, receipt);
			assertExactRegistries(fixture);
			Assert.assertEquals("G014_RED1_ANALYSIS_MUTATED", fingerprint,
				fixture.analysis().analysisFingerprint());
			assertImmutable(receipt.uploads());
			assertForeignAndCopiedAnalysisRejectWithoutMutation(fixture);
		}
		finally {
			FederatedPlannerUtils.clearFedAnchorKeys();
			clearRegistries();
		}
	}

	private static Fixture fixture(boolean distinctAnchors) {
		DataOp target = matrix("G014_RED1_TARGET");
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DataOp firstAnchor = literalFederatedAnchor("G014_RED1_ANCHOR_A", 18111);
		DataOp secondAnchor = distinctAnchors ? literalFederatedAnchor("G014_RED1_ANCHOR_B", 18121) : firstAnchor;
		Hop firstParent = HopRewriteUtils.createBinary(target, firstAnchor, OpOp2.PLUS);
		Hop secondParent = HopRewriteUtils.createBinary(target, secondAnchor, OpOp2.MINUS);

		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block(firstParent), block(secondParent))));
		PlacementAnalysis analysis = program.bindPlacementAnalysisAtFinalHopBoundary();
		List<PlacementAnalysis.HopOccurrenceProjection> targets = occurrences(analysis, target);
		Assert.assertEquals("G014_RED1_FIXTURE_MUST_PROJECT_TWO_EXACT_TARGET_OCCURRENCES", 2, targets.size());
		Assert.assertNotSame("G014_RED1_FIXTURE_MUST_OWN_DISTINCT_KEYS", targets.get(0).key(), targets.get(1).key());

		List<DataOp> anchors = List.of(firstAnchor, secondAnchor);
		List<PlacementAnalysis.HopOccurrenceProjection> scopedAnchors = new ArrayList<>();
		List<DurableAnchorKey> durableAnchors = new ArrayList<>();
		for(int i = 0; i < targets.size(); i++) {
			long scope = targets.get(i).scopeId();
			PlacementAnalysis.HopOccurrenceProjection anchorOccurrence = occurrences(analysis, anchors.get(i)).stream()
				.filter(value -> value.scopeId() == scope).findFirst().orElseThrow();
			scopedAnchors.add(anchorOccurrence);
			List<DurableAnchorKey> keys = analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors();
			Assert.assertEquals("G014_RED1_LITERAL_ANCHOR_MUST_OWN_ONE_DURABLE_KEY_" + i, 1, keys.size());
			Assert.assertSame("G014_RED1_LITERAL_ANCHOR_KEY_NOT_ANALYSIS_OWNED_" + i, keys,
				analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors());
			durableAnchors.add(keys.get(0));
		}

		Map<Long, FType> selected = new LinkedHashMap<>();
		selected.put(target.getHopID(), FType.ROW);
		selected.put(firstAnchor.getHopID(), FType.ROW);
		selected.put(secondAnchor.getHopID(), FType.ROW);
		return new Fixture(program, analysis, target, targets, anchors, List.copyOf(scopedAnchors),
			List.copyOf(durableAnchors), Map.copyOf(selected));
	}

	private static void assertExactReceipt(Fixture fixture, ExactPlacementRegistration.Receipt receipt) {
		Assert.assertSame("G014_RED1_RECEIPT_ANALYSIS_IDENTITY", fixture.analysis(), receipt.analysis());
		for(int i = 0; i < fixture.targets().size(); i++) {
			ExactPlacementRegistration.RegisteredUpload upload = receipt.uploads().get(i);
			DurableAnchorKey durable = fixture.durableAnchors().get(i);
			Assert.assertEquals("G014_RED1_ORDERED_SCOPE_" + i, fixture.targets().get(i).scopeId(), upload.scopeId());
			Assert.assertEquals("G014_RED1_TARGET_HOP_" + i, fixture.target().getHopID(), upload.hopId());
			Assert.assertEquals("G014_RED1_ANCHOR_HOP_" + i, fixture.anchors().get(i).getHopID(), upload.anchorHopId());
			Assert.assertEquals("G014_RED1_ANALYSIS_RUNTIME_AUTHORITY_" + i, runtimeKey(durable), upload.anchorKey());
			Assert.assertNotEquals("G014_RED1_MUTABLE_GLOBAL_OVERRULED_ANALYSIS_" + i, POISON, upload.anchorKey());
		}
	}

	private static void assertExactOccurrenceReceiptContract() {
		try {
			String source = codeOnly(Files.readString(REGISTRATION_SOURCE));
			String header = recordHeader(source, "RegisteredUpload");
			Assert.assertTrue("G014_RED1_RECEIPT_MISSING_TARGET_OCCURRENCE",
				header.matches("(?s).*CompiledHopKey\\s+targetOccurrence.*"));
			Assert.assertTrue("G014_RED1_RECEIPT_MISSING_ANCHOR_OCCURRENCE",
				header.matches("(?s).*CompiledHopKey\\s+anchorOccurrence.*"));
			Assert.assertTrue("G014_RED1_RECEIPT_MISSING_DURABLE_ANCHOR",
				header.matches("(?s).*DurableAnchorKey\\s+durableAnchor.*"));
			String exactUploads = exactUploadsBody(source);
			Assert.assertTrue("G014_RED1_RECEIPT_NOT_CONSTRUCTED_FROM_EXACT_OCCURRENCES",
				Pattern.compile("new\\s+RegisteredUpload\\s*\\([^;]*occurrence\\s*\\.\\s*key\\s*\\(\\s*\\)"
					+ "[^;]*anchorOccurrence\\s*\\.\\s*key\\s*\\(\\s*\\)[^;]*durableAnchor", Pattern.DOTALL)
					.matcher(exactUploads).find());
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to inspect exact registration source", failure);
		}
	}

	private static String recordHeader(String source, String name) {
		Matcher matcher = Pattern.compile("\\bpublic\\s+record\\s+" + Pattern.quote(name) + "\\s*\\(([^)]*)\\)",
			Pattern.DOTALL).matcher(source);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String exactUploadsBody(String source) {
		Matcher matcher = Pattern.compile("(?m)^\\s*private\\s+static\\s+List\\s*<\\s*RegisteredUpload\\s*>"
			+ "\\s+exactUploads\\s*\\(\\s*PlacementAnalysis\\s+analysis\\s*,\\s*"
			+ "Map\\s*<\\s*Long\\s*,\\s*FType\\s*>\\s+selectedTypes\\s*\\)\\s*\\{").matcher(source);
		if(!matcher.find()) return "";
		int open = matcher.end() - 1;
		int depth = 0;
		for(int i = open; i >= 0 && i < source.length(); i++) {
			char current = source.charAt(i);
			if(current == '{') depth++;
			else if(current == '}' && --depth == 0) return source.substring(open, i + 1);
		}
		return "";
	}

	private static String codeOnly(String source) {
		char[] code = source.toCharArray();
		for(int i = 0; i < code.length;) {
			if(code[i] == '/' && i + 1 < code.length && code[i + 1] == '/') {
				int end = i + 2;
				while(end < code.length && code[end] != '\n' && code[end] != '\r') end++;
				blank(code, i, end);
				i = end;
			}
			else if(code[i] == '/' && i + 1 < code.length && code[i + 1] == '*') {
				int end = i + 2;
				while(end + 1 < code.length && !(code[end] == '*' && code[end + 1] == '/')) end++;
				end = Math.min(code.length, end + 2);
				blank(code, i, end);
				i = end;
			}
			else if(code[i] == '"' && i + 2 < code.length && code[i + 1] == '"' && code[i + 2] == '"') {
				int end = i + 3;
				while(end < code.length) {
					if(code[end] == '\\')
						end = Math.min(code.length, end + 2);
					else if(end + 2 < code.length && code[end] == '"' && code[end + 1] == '"'
						&& code[end + 2] == '"') {
						end += 3;
						break;
					}
					else
						end++;
				}
				blank(code, i, end);
				i = end;
			}
			else if(code[i] == '"' || code[i] == '\'') {
				char delimiter = code[i];
				int end = i + 1;
				while(end < code.length) {
					if(code[end] == '\\')
						end = Math.min(code.length, end + 2);
					else if(code[end++] == delimiter)
						break;
				}
				blank(code, i, end);
				i = end;
			}
			else
				i++;
		}
		return new String(code);
	}

	private static void blank(char[] code, int begin, int end) {
		for(int i = begin; i < end; i++)
			if(code[i] != '\n' && code[i] != '\r')
				code[i] = ' ';
	}

	private static void assertExactRegistries(Fixture fixture) {
		for(int i = 0; i < fixture.targets().size(); i++) {
			long scope = fixture.targets().get(i).scopeId();
			long target = fixture.target().getHopID();
			long anchor = fixture.anchors().get(i).getHopID();
			String key = runtimeKey(fixture.durableAnchors().get(i));
			FederatedRefedRegistry.AnchorSpec refed = FederatedRefedRegistry.snapshot(scope).get(target);
			FederatedFoutMaterializeRegistry.MaterializeSpec fout =
				FederatedFoutMaterializeRegistry.snapshot(scope).get(target);
			Assert.assertNotNull("G014_RED1_MISSING_REFED_SCOPE_" + i, refed);
			Assert.assertNotNull("G014_RED1_MISSING_FOUT_SCOPE_" + i, fout);
			Assert.assertEquals("G014_RED1_REFED_ANCHOR_" + i, anchor, refed.getAnchorHopId());
			Assert.assertEquals("G014_RED1_FOUT_ANCHOR_" + i, anchor, fout.getAnchorHopId());
			Assert.assertEquals("G014_RED1_REFED_RUNTIME_KEY_" + i, key, refed.getAnchorKey());
			Assert.assertEquals("G014_RED1_FOUT_RUNTIME_KEY_" + i, key, fout.getAnchorKey());
		}
	}

	private static void assertForeignAndCopiedAnalysisRejectWithoutMutation(Fixture fixture) {
		RegistrySnapshot before = registrySnapshot(fixture);
		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(fixture.program());
		expectReject(fixture, copied, "G014_RED1_ACCEPTED_COPIED_ANALYSIS");
		Assert.assertEquals("G014_RED1_COPIED_ANALYSIS_MUTATED_REGISTRIES", before, registrySnapshot(fixture));
		DMLProgram foreignProgram = new DMLProgram();
		foreignProgram.setStatementBlocks(new ArrayList<>(List.of(block(matrix("G014_RED1_FOREIGN")))));
		PlacementAnalysis foreign = foreignProgram.bindPlacementAnalysisAtFinalHopBoundary();
		expectReject(fixture, foreign, "G014_RED1_ACCEPTED_FOREIGN_ANALYSIS");
		Assert.assertEquals("G014_RED1_FOREIGN_ANALYSIS_MUTATED_REGISTRIES", before, registrySnapshot(fixture));
	}

	private static void expectReject(Fixture fixture, PlacementAnalysis candidate, String failure) {
		try {
			ExactPlacementRegistration.registerProgram(fixture.program(), fixture.selectedTypes(), candidate);
			Assert.fail(failure);
		}
		catch(IllegalArgumentException expected) {
			// Exact canonical analysis identity is mandatory and rejection must precede registry mutation.
		}
	}

	private static RegistrySnapshot registrySnapshot(Fixture fixture) {
		return new RegistrySnapshot(fixture.targets().stream()
			.map(value -> FederatedRefedRegistry.snapshot(value.scopeId())).toList(), fixture.targets().stream()
			.map(value -> FederatedFoutMaterializeRegistry.snapshot(value.scopeId())).toList());
	}

	private static String runtimeKey(DurableAnchorKey anchor) {
		return anchor.partitions().stream().map(partition -> partition.address() + "@"
			+ partition.begin().get(0) + ":" + partition.begin().get(1) + "-"
			+ partition.end().get(0) + ":" + partition.end().get(1))
			.reduce((left, right) -> left + ";" + right).orElseThrow() + "|" + anchor.fType().name();
	}

	private static DataOp literalFederatedAnchor(String name, int port) {
		String script = name + "=federated(addresses=list(\"localhost:" + port + "/A\",\"localhost:"
			+ (port + 1) + "/B\"),ranges=list(list(0,0),list(5,10),list(5,0),list(10,10)));\n"
			+ "print(sum(" + name + "));\n";
		try {
			DMLProgram parsed = compile(script);
			return (DataOp) parsed.getStatementBlocks().stream().flatMap(value -> value.getHops().stream())
				.flatMap(root -> collect(root).stream()).filter(Hop::isFederatedDataOp).findFirst().orElseThrow();
		}
		catch(Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new java.util.HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> out = new ArrayList<>();
		java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
		java.util.Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(!seen.add(hop)) continue;
			out.add(hop);
			hop.getInput().forEach(pending::push);
		}
		return out;
	}

	private static List<PlacementAnalysis.HopOccurrenceProjection> occurrences(PlacementAnalysis analysis, Hop hop) {
		return analysis.occurrences().stream().filter(value -> value.hop() == hop).toList();
	}

	private static StatementBlock block(Hop root) {
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		return block;
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, -1, 1000);
	}

	private static void assertImmutable(List<ExactPlacementRegistration.RegisteredUpload> uploads) {
		try {
			uploads.clear();
			Assert.fail("G014_RED1_RECEIPT_UPLOADS_MUTABLE");
		}
		catch(UnsupportedOperationException expected) {
			// exact receipt is immutable
		}
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, DataOp target,
		List<PlacementAnalysis.HopOccurrenceProjection> targets, List<DataOp> anchors,
		List<PlacementAnalysis.HopOccurrenceProjection> anchorOccurrences, List<DurableAnchorKey> durableAnchors,
		Map<Long, FType> selectedTypes) { }

	private record RegistrySnapshot(List<Map<Long, FederatedRefedRegistry.AnchorSpec>> refed,
		List<Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec>> fout) { }

}

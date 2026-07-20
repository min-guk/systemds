/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireConsumerEdge;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NormalizedCandidateInputs;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** RED-4: memo-bound upload costing must consume the exact candidate receipt's projected FType. */
public class CampaignBG014MemoBoundProjectedFTypeCostRedTest {
	private static final int WORKERS = 3;
	private static final double FALLBACK_PAYLOAD = 4096.0;
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path COST_ESTIMATOR = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java");
	private static final Path DP_ADAPTER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java");

	@Test
	public void canonicalNaNRecoveryPreservesRawBitsAndCpfoutDoesNotDoubleApplyForwarding() throws Exception {
		Fixture fixture = fixture();
		Snapshot before = snapshot(fixture);

		Assert.assertEquals("raw output estimate must be canonical NaN", bits(Double.NaN),
			bits(fixture.hop().getOutputMemEstimate()));
		Assert.assertEquals("effective upload estimate must remain NaN before memo-bound recovery", bits(Double.NaN),
			bits(FederatedCostModel.getEffectiveUploadMemEstimate(fixture.hop())));
		Assert.assertEquals("finite fallback input payload", bits(FALLBACK_PAYLOAD),
			bits(FederatedCostModel.getEffectiveInputMemEstimate(fixture.hop())));

		FType currentProjectedFType = fixture.decision().logicalFType();
		Assert.assertSame("current root RBIND legally resolves its exact ROW projection",
			FType.ROW, currentProjectedFType);
		double payloadCost = FederatedCostModel.computeUploadNetworkCost(
			FALLBACK_PAYLOAD, currentProjectedFType, WORKERS);
		double forwarding = FederatedCostModel.computeLocalToFedForwardingPenalty(
			currentProjectedFType, WORKERS);
		Assert.assertTrue("payload cost must be finite and positive", Double.isFinite(payloadCost) && payloadCost > 0);
		Assert.assertTrue("forwarding penalty must be finite and non-negative",
			Double.isFinite(forwarding) && forwarding >= 0);

		List<String> missing = projectedCostContract();
		assertSnapshotSame(before, snapshot(fixture));
		Assert.assertEquals("G014_RED4_MEMO_BOUND_PROJECTED_COST_RECEIPT_SEAM", List.of(), missing);
	}

	@Test
	public void copiedOrForeignDecisionEvidenceRejectsBeforeCostOrMemoMutation() throws Exception {
		Fixture owner = fixture();
		Snapshot before = snapshot(owner);
		CandidateDecisionReceipt copied = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.resolveCandidateDecision(owner.context(), owner.normalized(), owner.decision().variantOrdinal());
		Assert.assertNotSame(owner.decision(), copied);
		Assert.assertSame("copy retains the same representable candidate snapshot",
			owner.decision().candidateSnapshot(), copied.candidateSnapshot());

		Fixture foreign = fixture();
		Assert.assertNotSame("foreign block must carry a foreign context", owner.block().context(),
			foreign.block().context());
		Assert.assertNotSame("foreign receipt must carry foreign analysis evidence", owner.decision().context(),
			foreign.decision().context());
		assertRejectedByProjectedUploadOwnershipContract(owner, copied, "copied receipt");
		assertSnapshotSame(before, snapshot(owner));
		assertRejectedByProjectedUploadOwnershipContract(owner, foreign.decision(), "foreign receipt");
		assertSnapshotSame(before, snapshot(owner));
	}

	@Test
	public void executableSourceStripsEveryJavaDecoyIncludingEscapedTextBlockQuotes() {
		String triple = "\"\"\"";
		String escapedTriple = "\\" + triple;
		String source = "LIVE();\n"
			+ "// LINE_DECOY();\n"
			+ "/* BLOCK_DECOY(); */\n"
			+ "String normal=\"STRING_DECOY();\\\"ESCAPED_QUOTE_DECOY();\";\n"
			+ "char quote='\\\''; char c='C';\n"
			+ "String text=" + triple + "\nTEXT_BLOCK_DECOY();\n"
			+ escapedTriple + " ESCAPED_TRIPLE_DECOY();\n" + triple + ";\n"
			+ "TAIL();\n";
		String executable = executableSource(source);
		Assert.assertTrue(executable.contains("LIVE();"));
		Assert.assertTrue(executable.contains("TAIL();"));
		for(String decoy : List.of("LINE_DECOY", "BLOCK_DECOY", "STRING_DECOY",
			"ESCAPED_QUOTE_DECOY", "TEXT_BLOCK_DECOY", "ESCAPED_TRIPLE_DECOY"))
			Assert.assertFalse(decoy + " must be stripped", executable.contains(decoy));
	}

	private static void assertRejectedByProjectedUploadOwnershipContract(Fixture owner,
		CandidateDecisionReceipt rejected, String label) throws Exception {
		Assert.assertNotSame(label + " must not be the owner receipt", owner.decision(), rejected);
		Assert.assertFalse(label + " must fail exact semantic-block receipt ownership",
			ownsExactDecisionReceipt(owner.block(), rejected));
		Assert.assertEquals("G014_RED4_COPIED_OR_FOREIGN_PROJECTED_COST_EVIDENCE_REJECTED: " + label,
			List.of(), projectedCostContract());
	}

	private static boolean ownsExactDecisionReceipt(PreSelectionSemanticBlock block,
		CandidateDecisionReceipt decision) {
		return decision != null && decision.context() == block.context()
			&& block.candidateDecisionReceipts().stream().anyMatch(candidate -> candidate == decision);
	}

	private static List<String> projectedCostContract() throws Exception {
		String estimator = executableSource(Files.readString(COST_ESTIMATOR));
		String adapter = executableSource(Files.readString(DP_ADAPTER));
		List<String> missing = new ArrayList<>();

		String decisionHeader = recordHeader(adapter, "CandidateDecisionReceipt");
		if(!hasField(decisionHeader, "FType", "projectedFType"))
			missing.add("decisionReceipt.projectedFType");

		String costHeader = recordHeader(estimator, "ProjectedUploadCostReceipt");
		String[][] receiptFields = {
			{"PlacementAnalysis", "analysis"}, {"HopOccurrenceProjection", "occurrence"},
			{"FederatedPlannerDpMemoTable", "memo"}, {"CandidateDecisionReceipt", "decisionReceipt"},
			{"FType", "projectedFType"}, {"long", "payloadBits"}, {"long", "payloadCostBits"},
			{"long", "totalCostBits"}, {"boolean", "forwardingApplied"}
		};
		int cursor = 0;
		for(String[] field : receiptFields) {
			int next = fieldIndex(costHeader, field[0], field[1], cursor);
			if(next < 0) missing.add("projectedUploadReceipt." + field[1]);
			else cursor = next + 1;
		}

		String exactEstimator = typeBody(estimator, "ExactEstimator");
		String method = methodBody(exactEstimator, "ProjectedUploadCostReceipt", "projectedUpload",
			"PreSelectionSemanticBlock", "CandidateDecisionReceipt", "boolean");
		if(method.isEmpty())
			missing.add("exactEstimator.projectedUploadSignature");
		else {
			String code = compactExecutableCode(method);
			Matcher projectionMatcher = Pattern.compile(
				"FType(\\w+)=decision\\.projectedFType\\(\\);").matcher(code);
			int projection = projectionMatcher.find() ? projectionMatcher.start() : -1;
			String validation = projection < 0 ? code : code.substring(0, projection);

			requireOwnershipValidation(validation, missing);
			requireProjectedUploadDataflow(code, missing);
			if(count(code, "computeUploadNetworkCost\\(") != 1
				|| count(code, "computeLocalToFedForwardingPenalty\\(") != 1
				|| count(code, "doubleToRawLongBits\\(") != 3
				|| count(code, "returnnewProjectedUploadCostReceipt\\(") != 1)
				missing.add("projectedUpload.noAlternateCostOrReceiptPath");
		}
		return List.copyOf(missing);
	}

	private static void requireOwnershipValidation(String validation, List<String> missing) {
		String[] requiredFacts = {
			"block==null", "decision==null", "block\\.context\\(\\)\\.analysis\\(\\)!=analysis",
			"decision\\.context\\(\\)!=block\\.context\\(\\)", "memo\\.analysis\\(\\)!=analysis",
			"analysis\\.occurrences\\(\\)\\.stream\\(\\)\\.noneMatch\\(\\w+->\\w+==occurrence\\)",
			"decision\\.candidateSnapshot\\(\\)\\.parentOccurrence\\(\\)!=occurrence\\.key\\(\\)",
			"block\\.candidateDecisionReceipts\\(\\)\\.stream\\(\\)\\.noneMatch"
				+ "\\(\\w+->\\w+==decision\\)",
			"decision\\.projectedFType\\(\\)==null"
		};
		Matcher rejectingBranch = Pattern.compile(
			"if\\(([^{}]+)\\)thrownewIllegalArgumentException\\(", Pattern.DOTALL).matcher(validation);
		while(rejectingBranch.find()) {
			String condition = rejectingBranch.group(1);
			boolean exact = true;
			for(String fact : requiredFacts)
				exact &= count(condition, fact) == 1;
			if(exact) return;
		}
		missing.add("projectedUpload.exactOwnerAndDecisionRejectionBranch");
	}

	private static void requireProjectedUploadDataflow(String code, List<String> missing) {
		Matcher projection = Pattern.compile("FType(\\w+)=decision\\.projectedFType\\(\\);").matcher(code);
		if(!projection.find() || count(code, "decision\\.projectedFType\\(\\)") != 1) {
			missing.add("projectedUpload.exactProjectedFTypeDerivation");
			return;
		}
		String projected = projection.group(1);
		Matcher payloadMatch = Pattern.compile("double(\\w+)=FederatedCostModel\\.getEffectiveUploadMemEstimate"
			+ "\\(occurrence\\.hop\\(\\)\\);").matcher(code);
		if(!payloadMatch.find()) {
			missing.add("projectedUpload.outputPayloadDerivation");
			return;
		}
		String payload = payloadMatch.group(1);
		requireExactlyOnce(code, "if\\(!Double\\.isFinite\\(" + Pattern.quote(payload) + "\\)\\|\\|"
			+ Pattern.quote(payload) + "<=0(?:\\.0)?\\)" + Pattern.quote(payload)
			+ "=FederatedCostModel\\.getEffectiveInputMemEstimate\\(occurrence\\.hop\\(\\)\\);",
			"projectedUpload.nanInputRecovery", missing);
		Matcher payloadCostMatch = Pattern.compile("double(\\w+)=FederatedCostModel\\.computeUploadNetworkCost\\("
			+ Pattern.quote(payload) + "," + Pattern.quote(projected)
			+ ",block\\.context\\(\\)\\.numWorkers\\(\\)\\);").matcher(code);
		if(!payloadCostMatch.find()) {
			missing.add("projectedUpload.projectedPayloadCost");
			return;
		}
		String payloadCost = payloadCostMatch.group(1);
		Matcher forwardingMatch = Pattern.compile("double(\\w+)=includeForwarding\\?FederatedCostModel"
			+ "\\.computeLocalToFedForwardingPenalty\\(" + Pattern.quote(projected)
			+ ",block\\.context\\(\\)\\.numWorkers\\(\\)\\):0(?:\\.0)?;").matcher(code);
		if(!forwardingMatch.find()) {
			missing.add("projectedUpload.singleConditionalForwarding");
			return;
		}
		String forwarding = forwardingMatch.group(1);
		Matcher totalMatch = Pattern.compile("double(\\w+)=(?:" + Pattern.quote(payloadCost) + "\\+"
			+ Pattern.quote(forwarding) + "|" + Pattern.quote(forwarding) + "\\+"
			+ Pattern.quote(payloadCost) + ");").matcher(code);
		if(!totalMatch.find()) {
			missing.add("projectedUpload.forwardingAddedOnceToTotal");
			return;
		}
		String total = totalMatch.group(1);
		requireExactlyOnce(code, "returnnewProjectedUploadCostReceipt\\(analysis,occurrence,memo,decision,"
			+ Pattern.quote(projected) + ",Double\\.doubleToRawLongBits\\(" + Pattern.quote(payload)
			+ "\\),Double\\.doubleToRawLongBits\\(" + Pattern.quote(payloadCost)
			+ "\\),Double\\.doubleToRawLongBits\\(" + Pattern.quote(total)
			+ "\\),includeForwarding\\);", "projectedUpload.exactRawBitReceipt", missing);
	}

	private static String recordHeader(String source, String recordName) {
		Matcher matcher = Pattern.compile("\\brecord\\s+" + Pattern.quote(recordName) + "\\s*\\(([^)]*)\\)",
			Pattern.DOTALL).matcher(source);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static boolean hasField(String header, String type, String name) {
		return Pattern.compile("(?:\\b[\\w.]+\\.)?" + Pattern.quote(type) + "\\s+" + Pattern.quote(name) + "\\b")
			.matcher(header).find();
	}

	private static int fieldIndex(String header, String type, String name, int from) {
		Matcher matcher = Pattern.compile("(?:\\b[\\w.]+\\.)?" + Pattern.quote(type) + "\\s+"
			+ Pattern.quote(name) + "\\b").matcher(header);
		return matcher.find(from) ? matcher.start() : -1;
	}

	private static String methodBody(String source, String returnType, String methodName,
		String... parameterTypes) {
		StringBuilder signature = new StringBuilder("\\bpublic\\s+").append(Pattern.quote(returnType))
			.append("\\s+").append(Pattern.quote(methodName)).append("\\s*\\(");
		for(int i = 0; i < parameterTypes.length; i++) {
			if(i > 0) signature.append("\\s*,\\s*");
			signature.append("(?:[\\w.]+\\.)?").append(Pattern.quote(parameterTypes[i])).append("\\s+\\w+");
		}
		signature.append("\\s*\\)");
		Matcher matcher = Pattern.compile(signature.toString(), Pattern.DOTALL).matcher(source);
		if(!matcher.find()) return "";
		int open = source.indexOf('{', matcher.end());
		if(open < 0) return "";
		int depth = 0;
		for(int i = open; i < source.length(); i++) {
			char current = source.charAt(i);
			if(current == '{') depth++;
			else if(current == '}' && --depth == 0) return source.substring(open, i + 1);
		}
		return "";
	}

	private static String typeBody(String source, String typeName) {
		Matcher matcher = Pattern.compile("\\b(?:class|record)\\s+" + Pattern.quote(typeName) + "\\b")
			.matcher(source);
		if(!matcher.find()) return "";
		int open = source.indexOf('{', matcher.end());
		if(open < 0) return "";
		int depth = 0;
		for(int i = open; i < source.length(); i++) {
			char current = source.charAt(i);
			if(current == '{') depth++;
			else if(current == '}' && --depth == 0) return source.substring(open, i + 1);
		}
		return "";
	}

	private static void requireExactlyOnce(String source, String regex, String label, List<String> missing) {
		if(count(source, regex) != 1) missing.add(label);
	}

	private static String compactExecutableCode(String source) {
		return source.replaceAll("\\s+", "");
	}

	private static String executableSource(String source) {
		char[] executable = source.toCharArray();
		int state = 0;
		for(int i = 0; i < executable.length; i++) {
			char current = executable[i];
			char next = i + 1 < executable.length ? executable[i + 1] : '\0';
			char afterNext = i + 2 < executable.length ? executable[i + 2] : '\0';
			if(state == 0) {
				if(current == '/' && next == '/') {
					executable[i] = executable[++i] = ' ';
					state = 1;
				}
				else if(current == '/' && next == '*') {
					executable[i] = executable[++i] = ' ';
					state = 2;
				}
				else if(current == '\"' && next == '\"' && afterNext == '\"') {
					executable[i] = executable[++i] = executable[++i] = ' ';
					state = 5;
				}
				else if(current == '\"' || current == '\'') {
					executable[i] = ' ';
					state = current == '\"' ? 3 : 4;
				}
			}
			else if(state == 1) {
				if(current == '\n' || current == '\r') state = 0;
				else executable[i] = ' ';
			}
			else if(state == 2) {
				if(current == '*' && next == '/') {
					executable[i] = executable[++i] = ' ';
					state = 0;
				}
				else if(current != '\n' && current != '\r') executable[i] = ' ';
			}
			else if(state == 3 || state == 4) {
				executable[i] = current == '\n' || current == '\r' ? current : ' ';
				if(current == '\\' && i + 1 < executable.length) executable[++i] = ' ';
				else if((state == 3 && current == '\"') || (state == 4 && current == '\'')) state = 0;
			}
			else {
				executable[i] = current == '\n' || current == '\r' ? current : ' ';
				if(current == '\\' && i + 1 < executable.length) executable[++i] = ' ';
				else if(current == '\"' && next == '\"' && afterNext == '\"') {
					executable[++i] = executable[++i] = ' ';
					state = 0;
				}
			}
		}
		return new String(executable);
	}

	private static int count(String source, String regex) {
		Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(source);
		int count = 0;
		while(matcher.find()) count++;
		return count;
	}

	private static Fixture fixture() {
		DMLProgram program;
		ControlledMemoryHop hop = new ControlledMemoryHop();
		Hop anchor;
		Hop consumer;
		try {
			DMLProgram compiled = compile(anchoredGeometry());
			anchor = compiled.getStatementBlocks().stream().flatMap(block -> block.getHops().stream())
				.flatMap(root -> collect(root).stream()).filter(Hop::isFederatedDataOp).findFirst().orElseThrow();
			consumer = HopRewriteUtils.createBinary(anchor, hop, OpOp2.RBIND);
			consumer.setDim1(7);
			consumer.setDim2(2);
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(consumer)));
			program = new DMLProgram();
			program.setStatementBlocks(new ArrayList<>(List.of(block)));
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to build G014 RED-4 anchored geometry", failure);
		}
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection anchorOccurrence = occurrence(analysis, anchor);
		HopOccurrenceProjection targetOccurrence = occurrence(analysis, hop);
		HopOccurrenceProjection consumerOccurrence = occurrence(analysis, consumer);
		List<DurableAnchorKey> anchors = analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors();
		Assert.assertEquals("anchored geometry must retain one exact durable anchor", 1, anchors.size());
		Assert.assertSame("anchored geometry FType", FType.ROW, anchors.get(0).fType());

		Map<Hop, HopOccurrenceProjection> carriers = new IdentityHashMap<>();
		for(HopOccurrenceProjection candidate : analysis.occurrences()) carriers.put(candidate.hop(), candidate);
		List<RewireConsumerEdge> consumerEdges = new ArrayList<>();
		for(HopOccurrenceProjection parent : analysis.occurrences())
			for(int position = 0; position < parent.hop().getInput().size(); position++) {
				HopOccurrenceProjection child = carriers.get(parent.hop().getInput(position));
				if(child != null) consumerEdges.add(new RewireConsumerEdge(parent.key(), child.key(), position));
			}
		RewireOccurrenceSnapshot rewire = new RewireOccurrenceSnapshot(analysis, program,
			analysis.analysisFingerprint(), analysis.occurrences(), List.of(), List.of(), consumerEdges, Map.of(),
			carriers, "G014-RED4");
		Map<Long, Privacy> privacy = Map.of(anchor.getHopID(), Privacy.PRIVATE_AGGREGATE,
			hop.getHopID(), Privacy.PRIVATE_AGGREGATE, consumer.getHopID(), Privacy.PRIVATE_AGGREGATE);
		NeutralEnumerationContext context = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.captureNeutralEnumerationContext(analysis, rewire, WORKERS, privacy, java.util.Set.of());

		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(analysis);
		FedPlan anchorPlan = addPlan(memo, anchorOccurrence, FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		FedPlan localPlan = addPlan(memo, targetOccurrence, FederatedOutput.LOUT, ExecType.CP, null);
		List<Pair<Long, FederatedOutput>> childEdges = List.of(
			Pair.of(anchor.getHopID(), FederatedOutput.FOUT), Pair.of(hop.getHopID(), FederatedOutput.LOUT));
		NormalizedCandidateInputs normalized = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.normalizeCandidateInputs(context, consumerOccurrence, childEdges, List.of(anchor, hop),
				java.util.Arrays.asList(FType.ROW, null), Map.of(anchor.getHopID(), FType.ROW), memo);
		CandidateOccurrenceSnapshot candidate = normalized.snapshot();
		CandidateDecisionReceipt decision = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.resolveCandidateDecision(context, normalized, 0L);
		Assert.assertSame("decision exact consumer occurrence", consumerOccurrence.key(),
			decision.candidateSnapshot().parentOccurrence());
		Assert.assertSame("decision retains non-public privacy evidence", Privacy.PRIVATE_AGGREGATE,
			decision.privacy());
		Assert.assertSame("current anchored consumer logical projection", FType.ROW, decision.logicalFType());
		PreSelectionSemanticBlock semanticBlock = new PreSelectionSemanticBlock(context, List.of(candidate),
			List.of(0L), List.of(decision), 1, 1, true);

		FedPlan plan = addPlan(memo, targetOccurrence, FederatedOutput.FOUT, ExecType.CP, FType.ROW);
		FederatedPlannerDpCostEstimator.ExactEstimator estimator =
			FederatedPlannerDpCostEstimator.bindExact(analysis, targetOccurrence, memo);
		Assert.assertSame(anchorPlan, memo.getFedPlanAfterPrune(anchor.getHopID(), FederatedOutput.FOUT));
		Assert.assertSame(localPlan, memo.getFedPlanAfterPrune(hop.getHopID(), FederatedOutput.LOUT));
		return new Fixture(program, analysis, targetOccurrence, hop, memo, plan, semanticBlock, context,
			normalized, decision, estimator);
	}

	private static FedPlan addPlan(FederatedPlannerDpMemoTable memo, HopOccurrenceProjection occurrence,
		FederatedOutput output, ExecType exec, FType fType) {
		HopCommon common = new HopCommon(occurrence.hop(), 1, 1, 1, 1, List.of());
		FedPlanVariants variants = new FedPlanVariants(common, output);
		FedPlan plan = new FedPlan(0.0, variants, List.of());
		plan.setExecType(exec);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memo.addFedPlanVariants(occurrence, output, variants);
		return plan;
	}

	private static HopOccurrenceProjection occurrence(PlacementAnalysis analysis, Hop hop) {
		return analysis.occurrences().stream().filter(candidate -> candidate.hop() == hop).findFirst().orElseThrow();
	}

	private static String anchoredGeometry() {
		return "X=federated(addresses=list(\"localhost:1234/A\",\"localhost:1235/B\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "print(sum(X));\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> result = new ArrayList<>();
		java.util.Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(!seen.add(hop)) continue;
			result.add(hop);
			for(Hop input : hop.getInput()) pending.push(input);
		}
		return result;
	}

	private static Snapshot snapshot(Fixture fixture) {
		FedPlanVariants variants = fixture.memo().getFedPlanVariants(
			org.apache.commons.lang3.tuple.Pair.of(fixture.hop().getHopID(), FederatedOutput.FOUT));
		return new Snapshot(fixture.analysis().analysisFingerprint(), fixture.analysis().occurrences(),
			fixture.hop().getDim1(), fixture.hop().getDim2(), bits(fixture.hop().getInputMemEstimate()),
			bits(fixture.hop().getOutputMemEstimate()), List.copyOf(fixture.hop().getInput()),
			List.copyOf(fixture.hop().getParent()), variants, List.copyOf(variants.getFedPlanVariants()),
			fixture.plan(), bits(fixture.plan().getCumulativeCost()), fixture.plan().getExecType(),
			fixture.plan().getFType(), fixture.plan().getCpFoutType(), fixture.block().candidateDecisionReceipts());
	}

	private static void assertSnapshotSame(Snapshot expected, Snapshot actual) {
		Assert.assertEquals(expected, actual);
		Assert.assertSame(expected.occurrences(), actual.occurrences());
		Assert.assertSame(expected.variants(), actual.variants());
		Assert.assertSame(expected.plan(), actual.plan());
		Assert.assertSame(expected.decisions().get(0), actual.decisions().get(0));
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence, ControlledMemoryHop hop, FederatedPlannerDpMemoTable memo,
		FedPlan plan, PreSelectionSemanticBlock block, NeutralEnumerationContext context,
		NormalizedCandidateInputs normalized, CandidateDecisionReceipt decision,
		FederatedPlannerDpCostEstimator.ExactEstimator estimator) { }

	private record Snapshot(String fingerprint, List<HopOccurrenceProjection> occurrences, long rows, long cols,
		long inputBits, long outputBits, List<Hop> inputs, List<Hop> parents, FedPlanVariants variants,
		List<FedPlan> variantOrder, FedPlan plan, long cumulativeBits, ExecType execType, FType fType,
		FType cpFoutType, List<CandidateDecisionReceipt> decisions) { }

	private static final class ControlledMemoryHop extends DataOp {
		private ControlledMemoryHop() {
			super("S", DataType.MATRIX, ValueType.FP64, OpOpData.PERSISTENTREAD,
				"G014_RED4_CONTROLLED_MEMORY", 3, 2, -1, 1024);
			setDim1(3);
			setDim2(2);
		}

		@Override public double getInputMemEstimate() { return FALLBACK_PAYLOAD; }
		@Override public double getOutputMemEstimate() { return Double.NaN; }
	}
}

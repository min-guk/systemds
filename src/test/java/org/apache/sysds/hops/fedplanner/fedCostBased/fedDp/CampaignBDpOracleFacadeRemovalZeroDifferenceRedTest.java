/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Pre-cutover RED for exact ordered DP candidate-decision parity evidence. */
public class CampaignBDpOracleFacadeRemovalZeroDifferenceRedTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path ADAPTER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java");
	private static final Path ENUMERATOR = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");
	private static final Path POLICY = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java");

	@Test
	public void everyCanonicalCandidateHasExactLegacyNeutralOracleParity() throws Exception {
		List<Token> adapter = tokens(Files.readString(ADAPTER));
		List<Token> enumerator = tokens(Files.readString(ENUMERATOR));
		List<Token> policy = tokens(Files.readString(POLICY));
		List<String> missing = new ArrayList<>();

		int receipt = sequence(adapter, 0, "public", "record", "CandidateDecisionReceipt", "(");
		if(receipt < 0)
			missing.add("adapter.CandidateDecisionReceipt");
		List<Token> receiptHeader = receipt < 0 ? List.of() : parenthesized(adapter, receipt + 3);
		if(!hasTypedReceiptFields(receiptHeader))
			missing.add("adapter.CandidateDecisionReceipt.typedFields");

		int semanticBlock = sequence(adapter, 0, "public", "record", "PreSelectionSemanticBlock", "(");
		List<Token> semanticHeader = semanticBlock < 0 ? List.of() : parenthesized(adapter, semanticBlock + 3);
		if(sequence(semanticHeader, 0, "List", "<", "CandidateDecisionReceipt", ">",
			"candidateDecisionReceipts") < 0)
			missing.add("semanticBlock.candidateDecisionReceipts");
		if(!hasOrderedExactRetention(adapter))
			missing.add("semanticBlock.orderedExactRetention");
		if(!hasCapturedContextEvidence(adapter))
			missing.add("context.completeInvocationPrivacyEvidence");
		if(!hasSingleTypedCapturedPlacementDecision(adapter, policy))
			missing.add("adapter.singleTypedPrivacyAwareDecideCaptured");

		List<Token> canonical = methodTokens(enumerator, "enumerateHop", "parentChildUploadHints");
		if(!hasResolveThenRetainSeam(canonical, enumerator, adapter))
			missing.add("enumerator.resolveThenRetainDecisionReceipt");
		if(!hasNonNullDynamicCapture(enumerator))
			missing.add("enumerator.dynamicNonNullCapture");

		Assert.assertEquals("G014_DP_ORACLE_PARITY_RECEIPT_SEAM", List.of(), missing);
	}

	@Test
	public void oracleParityComparatorDetectsFieldSpecificCorruptionWithoutMutation() {
		LocalOccurrenceToken parent = new LocalOccurrenceToken("analysis-occurrence-7", 1L);
		List<OracleInputState> orderedInputs = List.of(OracleInputState.ROW, OracleInputState.ABSENT_LOCAL);
		LocalCandidateKey key = new LocalCandidateKey("analysis-fingerprint", parent, 7L, orderedInputs);
		LocalInvocationEvidence invocation = new LocalInvocationEvidence(true, true, false, true, 11L, 1L,
			FType.ROW, true, true, false, true, FType.ROW, 3,
			List.of("consumer-0:input-1"), List.of("write-0:read-0"));
		LocalDecisionReceipt baseline = new LocalDecisionReceipt(key, ExecType.FED, FederatedOutput.FOUT,
			FType.ROW, FType.ROW, ReasonCode.OK, ConstructionDisposition.AVAILABLE, Privacy.PRIVATE_AGGREGATE,
			invocation,
			true, false, true, true);
		LocalDecisionReceipt equalCopy = new LocalDecisionReceipt(
			new LocalCandidateKey("analysis-fingerprint", parent, 7L,
				List.of(OracleInputState.ROW, OracleInputState.ABSENT_LOCAL)),
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, FType.ROW, ReasonCode.OK,
			ConstructionDisposition.AVAILABLE, Privacy.PRIVATE_AGGREGATE,
			new LocalInvocationEvidence(true, true, false, true, 11L, 1L, FType.ROW, true, true, false,
				true, FType.ROW, 3, List.of("consumer-0:input-1"), List.of("write-0:read-0")),
			true, false, true, true);
		LocalMutationProbe zeroMutation = new LocalMutationProbe(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		LocalDecisionReceipt frozenBaseline = baseline;
		LocalCandidateKey frozenKey = key;
		List<OracleInputState> frozenInputs = List.copyOf(orderedInputs);

		Assert.assertTrue("equal independently constructed receipts", compare(baseline, equalCopy).isEmpty());
		List<Corruption> corruptions = List.of(
			new Corruption("candidate.analysisFingerprint", baseline.withKey(key.withAnalysisFingerprint("stale"))),
			new Corruption("candidate.parentOccurrenceIdentity",
				baseline.withKey(key.withParent(new LocalOccurrenceToken(parent.diagnostic(), 2L)))),
			new Corruption("candidate.rawOrdinal", baseline.withKey(key.withRawOrdinal(8L))),
			new Corruption("candidate.orderedOracleInputs",
				baseline.withKey(key.withOrderedInputs(List.of(OracleInputState.ABSENT_LOCAL, OracleInputState.ROW)))),
			new Corruption("nativeExec", baseline.withNativeExec(ExecType.CP)),
			new Corruption("nativeOutput", baseline.withNativeOutput(FederatedOutput.LOUT)),
			new Corruption("nativeFoutFType", baseline.withNativeFoutFType(null)),
			new Corruption("logicalFType", baseline.withLogicalFType(FType.COL)),
			new Corruption("reasonCode", baseline.withReasonCode(ReasonCode.NO_RULE)),
			new Corruption("disposition",
				baseline.withDisposition(ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE)),
			new Corruption("privacy", baseline.withPrivacy(Privacy.PRIVATE)),
			new Corruption("invocation.multiReturnBuiltin", baseline.withInvocation(
				invocation.withMultiReturnBuiltin(false))),
			new Corruption("invocation.matrixOutput", baseline.withInvocation(invocation.withMatrixOutput(false))),
			new Corruption("invocation.scalarLikeMatrix", baseline.withInvocation(
				invocation.withScalarLikeMatrix(true))),
			new Corruption("invocation.vectorShape", baseline.withInvocation(invocation.withVectorShape(false))),
			new Corruption("invocation.rows", baseline.withInvocation(invocation.withRows(12L))),
			new Corruption("invocation.cols", baseline.withInvocation(invocation.withCols(2L))),
			new Corruption("invocation.fedInitType", baseline.withInvocation(invocation.withFedInitType(FType.COL))),
			new Corruption("invocation.transientRead", baseline.withInvocation(invocation.withTransientRead(false))),
			new Corruption("invocation.vectorAxisMismatch", baseline.withInvocation(
				invocation.withVectorAxisMismatch(false))),
			new Corruption("invocation.rowAxisLengthMismatch", baseline.withInvocation(
				invocation.withRowAxisLengthMismatch(true))),
			new Corruption("invocation.colAxisLengthMismatch", baseline.withInvocation(
				invocation.withColAxisLengthMismatch(false))),
			new Corruption("invocation.aggregateSharedAxis", baseline.withInvocation(
				invocation.withAggregateSharedAxis(FType.COL))),
			new Corruption("invocation.numWorkers", baseline.withInvocation(invocation.withNumWorkers(4))),
			new Corruption("invocation.consumerEdges", baseline.withInvocation(
				invocation.withConsumerEdges(List.of("consumer-1:input-0")))),
			new Corruption("invocation.transientForwards", baseline.withInvocation(
				invocation.withTransientForwards(List.of("write-1:read-1")))),
			new Corruption("allowCPLOUT", baseline.withAllowCPLOUT(false)),
			new Corruption("allowCPFOUT", baseline.withAllowCPFOUT(true)),
			new Corruption("allowFEDLOUT", baseline.withAllowFEDLOUT(false)),
			new Corruption("allowFEDFOUT", baseline.withAllowFEDFOUT(false)));

		for(Corruption corruption : corruptions) {
			List<ParityMismatch> mismatches = compare(baseline, corruption.receipt());
			Assert.assertEquals("one field changed: " + corruption.field(), 1, mismatches.size());
			ParityMismatch mismatch = mismatches.get(0);
			Assert.assertSame("mismatch retains exact corrupted candidate key",
				corruption.receipt().key(), mismatch.key());
			Assert.assertEquals(corruption.field(), mismatch.field());
			Assert.assertNotEquals(mismatch.legacyValue(), mismatch.neutralValue());
			Assert.assertSame(frozenBaseline, baseline);
			Assert.assertSame(frozenKey, baseline.key());
			Assert.assertSame(parent, baseline.key().parentOccurrence());
			Assert.assertEquals(frozenInputs, baseline.key().orderedOracleInputs());
			Assert.assertSame(zeroMutation, zeroMutation);
			Assert.assertEquals(new LocalMutationProbe(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), zeroMutation);
		}
		try {
			baseline.key().orderedOracleInputs().add(OracleInputState.COL);
			Assert.fail("candidate input order is mutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected: the comparator observes immutable evidence only.
		}
		Assert.assertTrue("corruption checks mutated the equality baseline", compare(baseline, equalCopy).isEmpty());
	}

	private static boolean hasTypedReceiptFields(List<Token> header) {
		if(header.isEmpty())
			return false;
		for(String[] field : List.of(
			new String[] {"NeutralEnumerationContext", "context"},
			new String[] {"CandidateOccurrenceSnapshot", "candidateSnapshot"},
			new String[] {"long", "variantOrdinal"},
			new String[] {"List", "<", "OracleInputState", ">", "orderedOracleInputs"},
			new String[] {"ExecType", "nativeExec"},
			new String[] {"FederatedOutput", "nativeOutput"},
			new String[] {"FType", "nativeFoutFType"},
			new String[] {"FType", "logicalFType"},
			new String[] {"ReasonCode", "reasonCode"},
			new String[] {"ConstructionDisposition", "disposition"},
			new String[] {"CandidateCapabilityFact", "capabilityFact"},
			new String[] {"CapturedInvocationEvidence", "invocationEvidence"},
			new String[] {"Privacy", "privacy"},
			new String[] {"boolean", "allowCPLOUT"},
			new String[] {"boolean", "allowCPFOUT"},
			new String[] {"boolean", "allowFEDLOUT"},
			new String[] {"boolean", "allowFEDFOUT"}))
			if(sequence(header, 0, field) < 0)
				return false;
		for(String forbidden : List.of("Object", "OpCaps", "OracleFacade", "RuleRegistry", "Supplier",
			"Callable", "ThreadLocal"))
			if(sequence(header, 0, forbidden) >= 0)
				return false;
		return true;
	}

	private static boolean hasOrderedExactRetention(List<Token> adapter) {
		return sequence(adapter, 0, "List", "<", "Long", ">", "candidateVariantOrdinals") >= 0
			&& sequence(adapter, 0, "candidateVariantOrdinals", "=", "List", ".", "copyOf", "(",
				"candidateVariantOrdinals", ")") >= 0
			&& sequence(adapter, 0, "candidateDecisionReceipts", "=", "List", ".", "copyOf", "(",
			"candidateDecisionReceipts", ")") >= 0
			&& sequence(adapter, 0, "candidateDecisionReceipts", ".", "size", "(", ")") >= 0
			&& sequence(adapter, 0, "candidateSnapshots", ".", "size", "(", ")") >= 0
			&& sequence(adapter, 0, "candidateVariantOrdinals", ".", "size", "(", ")") >= 0
			&& sequence(adapter, 0, "receipt", ".", "context", "(", ")", "!", "=", "context") >= 0
			&& sequence(adapter, 0, "receipt", ".", "candidateSnapshot", "(", ")", "!", "=",
				"candidateSnapshots", ".", "get", "(", "i", ")") >= 0
			&& sequence(adapter, 0, "receipt", ".", "variantOrdinal", "(", ")", "!", "=",
				"candidateVariantOrdinals", ".", "get", "(", "i", ")") >= 0
			&& sequence(adapter, 0, "receipt", ".", "orderedOracleInputs", "(", ")", ".", "equals", "(",
				"candidateSnapshots", ".", "get", "(", "i", ")", ".", "orderedOracleInputs", "(", ")", ")") >= 0;
	}

	private static boolean hasResolveThenRetainSeam(List<Token> canonical, List<Token> enumerator,
		List<Token> adapter) {
		int normalize = sequence(canonical, 0, "DpPlacementAdapter", ".", "normalizeCandidateInputs", "(");
		int snapshot = sequence(canonical, Math.max(0, normalize), "capture", ".", "capture", "(",
			"normalizedCandidateInputs", ".", "snapshot", "(", ")", ",", "i", ")");
		int declaration = sequence(canonical, Math.max(0, snapshot), "DpPlacementAdapter", ".",
			"CandidateDecisionReceipt");
		String variable = declaration < 0 ? "<missing>" : token(canonical, declaration + 3);
		int resolve = sequence(canonical, Math.max(0, declaration), "=", "DpPlacementAdapter", ".",
			"resolveCandidateDecision", "(", "capture", ".", "context", ",", "normalizedCandidateInputs",
			",", "i", ")");
		int retain = sequence(canonical, Math.max(0, resolve), "capture", ".", "captureDecisionReceipt", "(",
			variable, ",", "i", ")");
		boolean consumed = sequence(canonical, Math.max(0, retain), variable, ".", "logicalFType", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "capabilityFact", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "privacy", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "allowCPLOUT", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "allowCPFOUT", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "allowFEDLOUT", "(", ")") >= 0
			&& sequence(canonical, Math.max(0, retain), variable, ".", "allowFEDFOUT", "(", ")") >= 0;
		return normalize >= 0 && snapshot > normalize && declaration > snapshot && resolve > declaration
			&& retain > resolve && consumed
			&& countSequence(canonical, "DpPlacementAdapter", ".", "resolveCandidateDecision", "(") == 1
			&& countSequence(canonical, "capture", ".", "captureDecisionReceipt", "(") == 1
			&& countSequence(canonical, "ExecPlacementPolicy", ".", "decide", "(") == 0
			&& countSequence(canonical, "ExecPlacementPolicy", ".", "decideCaptured", "(") == 0
			&& sequence(canonical, 0, "new", "CandidateDecisionReceipt") < 0
			&& sequence(enumerator, 0, "List", "<", "CandidateDecisionReceipt", ">",
				"orderedDecisionReceipts") >= 0
			&& sequence(enumerator, 0, "List", "<", "Long", ">", "candidateVariantOrdinals") >= 0
			&& sequence(adapter, 0, "ExecPlacementPolicy", ".", "decideCaptured", "(") >= 0;
	}

	private static boolean hasCapturedContextEvidence(List<Token> adapter) {
		int context = sequence(adapter, 0, "public", "record", "NeutralEnumerationContext", "(");
		List<Token> header = context < 0 ? List.of() : parenthesized(adapter, context + 3);
		return sequence(header, 0, "int", "numWorkers") >= 0
			&& sequence(header, 0, "Map", "<", "CompiledHopKey", ",", "CapturedInvocationEvidence", ">") >= 0
			&& sequence(header, 0, "Map", "<", "CompiledHopKey", ",", "Privacy", ">") >= 0
			&& sequence(adapter, 0, "numWorkers", "<", "=", "0") >= 0
			&& !containsHardCodedInvocationPlaceholder(adapter);
	}

	private static boolean containsHardCodedInvocationPlaceholder(List<Token> adapter) {
		return sequence(adapter, 0, "false", ",", "false", ",", "false", ",", "null", ",", "0") >= 0;
	}

	private static boolean hasSingleTypedCapturedPlacementDecision(List<Token> adapter, List<Token> policy) {
		int request = sequence(policy, 0, "public", "record", "CapturedPlacementRequest", "(");
		List<Token> header = request < 0 ? List.of() : parenthesized(policy, request + 3);
		return sequence(header, 0, "Hop", "hop") >= 0
			&& sequence(header, 0, "Privacy", "privacy") >= 0
			&& sequence(header, 0, "FType", "logicalFType") >= 0
			&& sequence(header, 0, "CandidateCapabilityFact", "capabilityFact") >= 0
			&& sequence(header, 0, "OpCaps") < 0
			&& countSequence(policy, "Decision", "decideCaptured", "(") == 1
			&& countSequence(adapter, "ExecPlacementPolicy", ".", "decideCaptured", "(") == 1;
	}

	private static boolean hasNonNullDynamicCapture(List<Token> enumerator) {
		List<Token> dynamic = methodTokens(enumerator, "enumerateFunctionDynamic", "memoTable");
		return sequence(dynamic, 0, "memoTable", ".", "analysis", "(", ")") >= 0
			&& sequence(dynamic, 0, "analysis", ".", "assertProgramOwner", "(", "prog", ")") >= 0
			&& sequence(dynamic, 0, "new", "NeutralEnumerationContext", "(") >= 0
			&& sequence(dynamic, 0, "new", "EnumerationCapture", "(") >= 0
			&& sequence(dynamic, 0, "capture", ".", "semanticBlock", "(", ")") >= 0
			&& sequence(dynamic, 0, "EnumerationCapture", "capture", "=", "null") < 0;
	}

	private static List<ParityMismatch> compare(LocalDecisionReceipt legacy, LocalDecisionReceipt neutral) {
		List<ParityMismatch> out = new ArrayList<>();
		LocalCandidateKey left = legacy.key(), right = neutral.key();
		if(!left.analysisFingerprint().equals(right.analysisFingerprint()))
			out.add(mismatch(right, "candidate.analysisFingerprint", left.analysisFingerprint(), right.analysisFingerprint()));
		if(left.parentOccurrence() != right.parentOccurrence())
			out.add(mismatch(right, "candidate.parentOccurrenceIdentity",
				render(left.parentOccurrence()), render(right.parentOccurrence())));
		if(left.rawOrdinal() != right.rawOrdinal())
			out.add(mismatch(right, "candidate.rawOrdinal", Long.toString(left.rawOrdinal()),
				Long.toString(right.rawOrdinal())));
		if(!left.orderedOracleInputs().equals(right.orderedOracleInputs()))
			out.add(mismatch(right, "candidate.orderedOracleInputs", left.orderedOracleInputs().toString(),
				right.orderedOracleInputs().toString()));
		if(legacy.nativeExec() != neutral.nativeExec())
			out.add(mismatch(right, "nativeExec", legacy.nativeExec().name(), neutral.nativeExec().name()));
		if(legacy.nativeOutput() != neutral.nativeOutput())
			out.add(mismatch(right, "nativeOutput", legacy.nativeOutput().name(), neutral.nativeOutput().name()));
		if(legacy.nativeFoutFType() != neutral.nativeFoutFType())
			out.add(mismatch(right, "nativeFoutFType", render(legacy.nativeFoutFType()),
				render(neutral.nativeFoutFType())));
		if(legacy.logicalFType() != neutral.logicalFType())
			out.add(mismatch(right, "logicalFType", render(legacy.logicalFType()), render(neutral.logicalFType())));
		if(legacy.reasonCode() != neutral.reasonCode())
			out.add(mismatch(right, "reasonCode", legacy.reasonCode().name(), neutral.reasonCode().name()));
		if(legacy.disposition() != neutral.disposition())
			out.add(mismatch(right, "disposition", legacy.disposition().name(), neutral.disposition().name()));
		if(legacy.privacy() != neutral.privacy())
			out.add(mismatch(right, "privacy", legacy.privacy().name(), neutral.privacy().name()));
		LocalInvocationEvidence leftInvocation = legacy.invocation(), rightInvocation = neutral.invocation();
		if(leftInvocation.multiReturnBuiltin() != rightInvocation.multiReturnBuiltin())
			out.add(mismatch(right, "invocation.multiReturnBuiltin",
				Boolean.toString(leftInvocation.multiReturnBuiltin()),
				Boolean.toString(rightInvocation.multiReturnBuiltin())));
		if(leftInvocation.matrixOutput() != rightInvocation.matrixOutput())
			out.add(mismatch(right, "invocation.matrixOutput", Boolean.toString(leftInvocation.matrixOutput()),
				Boolean.toString(rightInvocation.matrixOutput())));
		if(leftInvocation.scalarLikeMatrix() != rightInvocation.scalarLikeMatrix())
			out.add(mismatch(right, "invocation.scalarLikeMatrix",
				Boolean.toString(leftInvocation.scalarLikeMatrix()),
				Boolean.toString(rightInvocation.scalarLikeMatrix())));
		if(leftInvocation.vectorShape() != rightInvocation.vectorShape())
			out.add(mismatch(right, "invocation.vectorShape", Boolean.toString(leftInvocation.vectorShape()),
				Boolean.toString(rightInvocation.vectorShape())));
		if(leftInvocation.rows() != rightInvocation.rows())
			out.add(mismatch(right, "invocation.rows", Long.toString(leftInvocation.rows()),
				Long.toString(rightInvocation.rows())));
		if(leftInvocation.cols() != rightInvocation.cols())
			out.add(mismatch(right, "invocation.cols", Long.toString(leftInvocation.cols()),
				Long.toString(rightInvocation.cols())));
		if(leftInvocation.fedInitType() != rightInvocation.fedInitType())
			out.add(mismatch(right, "invocation.fedInitType", render(leftInvocation.fedInitType()),
				render(rightInvocation.fedInitType())));
		if(leftInvocation.transientRead() != rightInvocation.transientRead())
			out.add(mismatch(right, "invocation.transientRead", Boolean.toString(leftInvocation.transientRead()),
				Boolean.toString(rightInvocation.transientRead())));
		if(leftInvocation.vectorAxisMismatch() != rightInvocation.vectorAxisMismatch())
			out.add(mismatch(right, "invocation.vectorAxisMismatch",
				Boolean.toString(leftInvocation.vectorAxisMismatch()),
				Boolean.toString(rightInvocation.vectorAxisMismatch())));
		if(leftInvocation.rowAxisLengthMismatch() != rightInvocation.rowAxisLengthMismatch())
			out.add(mismatch(right, "invocation.rowAxisLengthMismatch",
				Boolean.toString(leftInvocation.rowAxisLengthMismatch()),
				Boolean.toString(rightInvocation.rowAxisLengthMismatch())));
		if(leftInvocation.colAxisLengthMismatch() != rightInvocation.colAxisLengthMismatch())
			out.add(mismatch(right, "invocation.colAxisLengthMismatch",
				Boolean.toString(leftInvocation.colAxisLengthMismatch()),
				Boolean.toString(rightInvocation.colAxisLengthMismatch())));
		if(leftInvocation.aggregateSharedAxis() != rightInvocation.aggregateSharedAxis())
			out.add(mismatch(right, "invocation.aggregateSharedAxis", render(leftInvocation.aggregateSharedAxis()),
				render(rightInvocation.aggregateSharedAxis())));
		if(leftInvocation.numWorkers() != rightInvocation.numWorkers())
			out.add(mismatch(right, "invocation.numWorkers", Integer.toString(leftInvocation.numWorkers()),
				Integer.toString(rightInvocation.numWorkers())));
		if(!leftInvocation.consumerEdges().equals(rightInvocation.consumerEdges()))
			out.add(mismatch(right, "invocation.consumerEdges", leftInvocation.consumerEdges().toString(),
				rightInvocation.consumerEdges().toString()));
		if(!leftInvocation.transientForwards().equals(rightInvocation.transientForwards()))
			out.add(mismatch(right, "invocation.transientForwards", leftInvocation.transientForwards().toString(),
				rightInvocation.transientForwards().toString()));
		if(legacy.allowCPLOUT() != neutral.allowCPLOUT())
			out.add(mismatch(right, "allowCPLOUT", Boolean.toString(legacy.allowCPLOUT()),
				Boolean.toString(neutral.allowCPLOUT())));
		if(legacy.allowCPFOUT() != neutral.allowCPFOUT())
			out.add(mismatch(right, "allowCPFOUT", Boolean.toString(legacy.allowCPFOUT()),
				Boolean.toString(neutral.allowCPFOUT())));
		if(legacy.allowFEDLOUT() != neutral.allowFEDLOUT())
			out.add(mismatch(right, "allowFEDLOUT", Boolean.toString(legacy.allowFEDLOUT()),
				Boolean.toString(neutral.allowFEDLOUT())));
		if(legacy.allowFEDFOUT() != neutral.allowFEDFOUT())
			out.add(mismatch(right, "allowFEDFOUT", Boolean.toString(legacy.allowFEDFOUT()),
				Boolean.toString(neutral.allowFEDFOUT())));
		return List.copyOf(out);
	}

	private static ParityMismatch mismatch(LocalCandidateKey key, String field, String legacy, String neutral) {
		return new ParityMismatch(key, field, legacy, neutral);
	}

	private static String render(FType value) {
		return value == null ? "<null>" : value.name();
	}

	private static String render(LocalOccurrenceToken value) {
		return value.diagnostic() + '#' + value.identityOrdinal();
	}

	private static List<Token> methodTokens(List<Token> source, String method, String parameter) {
		for(int i = 0; i < source.size(); i++) {
			if(!source.get(i).text().equals(method) || i + 1 >= source.size() || !source.get(i + 1).text().equals("("))
				continue;
			int close = matching(source, i + 1, "(", ")");
			if(close < 0 || sequence(source.subList(i + 2, close), 0, parameter) < 0)
				continue;
			int open = close + 1;
			while(open < source.size() && !source.get(open).text().equals("{"))
				open++;
			int end = matching(source, open, "{", "}");
			if(end > open)
				return List.copyOf(source.subList(open + 1, end));
		}
		return List.of();
	}

	private static List<Token> parenthesized(List<Token> source, int opening) {
		int end = matching(source, opening, "(", ")");
		return end > opening ? List.copyOf(source.subList(opening + 1, end)) : List.of();
	}

	private static int matching(List<Token> source, int opening, String left, String right) {
		if(opening < 0 || opening >= source.size() || !source.get(opening).text().equals(left))
			return -1;
		int depth = 0;
		for(int i = opening; i < source.size(); i++) {
			if(source.get(i).text().equals(left))
				depth++;
			else if(source.get(i).text().equals(right) && --depth == 0)
				return i;
		}
		return -1;
	}

	private static int countSequence(List<Token> source, String... values) {
		int count = 0;
		for(int from = 0; from < source.size();) {
			int found = sequence(source, from, values);
			if(found < 0)
				return count;
			count++;
			from = found + values.length;
		}
		return count;
	}

	private static int sequence(List<Token> source, int from, String... values) {
		outer: for(int i = Math.max(0, from); i + values.length <= source.size(); i++) {
			for(int j = 0; j < values.length; j++)
				if(!source.get(i + j).text().equals(values[j]))
					continue outer;
			return i;
		}
		return -1;
	}

	private static String token(List<Token> source, int index) {
		return index >= 0 && index < source.size() ? source.get(index).text() : "<missing>";
	}

	private static List<Token> tokens(String source) {
		List<Token> out = new ArrayList<>();
		for(int i = 0; i < source.length();) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
			if(current == '/' && next == '/') {
				i += 2;
				while(i < source.length() && source.charAt(i) != '\n') i++;
			}
			else if(current == '/' && next == '*') {
				i += 2;
				while(i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
				i = Math.min(source.length(), i + 2);
			}
			else if(current == '"' || current == '\'') {
				char quote = current;
				i++;
				while(i < source.length()) {
					char value = source.charAt(i++);
					if(value == '\\' && i < source.length()) i++;
					else if(value == quote) break;
				}
			}
			else if(Character.isJavaIdentifierStart(current)) {
				int start = i++;
				while(i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
				out.add(new Token(source.substring(start, i)));
			}
			else {
				if(!Character.isWhitespace(current)) out.add(new Token(String.valueOf(current)));
				i++;
			}
		}
		return List.copyOf(out);
	}

	private record Token(String text) { }
	private static final class LocalOccurrenceToken {
		private final String diagnostic;
		private final long identityOrdinal;
		private LocalOccurrenceToken(String diagnostic, long identityOrdinal) {
			this.diagnostic = diagnostic;
			this.identityOrdinal = identityOrdinal;
		}
		private String diagnostic() { return diagnostic; }
		private long identityOrdinal() { return identityOrdinal; }
	}
	private record LocalCandidateKey(String analysisFingerprint, LocalOccurrenceToken parentOccurrence,
		long rawOrdinal, List<OracleInputState> orderedOracleInputs) {
		private LocalCandidateKey {
			orderedOracleInputs = List.copyOf(orderedOracleInputs);
		}
		private LocalCandidateKey withAnalysisFingerprint(String value) {
			return new LocalCandidateKey(value, parentOccurrence, rawOrdinal, orderedOracleInputs);
		}
		private LocalCandidateKey withParent(LocalOccurrenceToken value) {
			return new LocalCandidateKey(analysisFingerprint, value, rawOrdinal, orderedOracleInputs);
		}
		private LocalCandidateKey withRawOrdinal(long value) {
			return new LocalCandidateKey(analysisFingerprint, parentOccurrence, value, orderedOracleInputs);
		}
		private LocalCandidateKey withOrderedInputs(List<OracleInputState> value) {
			return new LocalCandidateKey(analysisFingerprint, parentOccurrence, rawOrdinal, value);
		}
	}
	private record LocalInvocationEvidence(boolean multiReturnBuiltin, boolean matrixOutput,
		boolean scalarLikeMatrix, boolean vectorShape, long rows, long cols, FType fedInitType,
		boolean transientRead, boolean vectorAxisMismatch, boolean rowAxisLengthMismatch,
		boolean colAxisLengthMismatch, FType aggregateSharedAxis, int numWorkers,
		List<String> consumerEdges, List<String> transientForwards) {
		private LocalInvocationEvidence {
			consumerEdges = List.copyOf(consumerEdges);
			transientForwards = List.copyOf(transientForwards);
		}
		private LocalInvocationEvidence copy(boolean multiReturn, boolean matrix, boolean scalar, boolean vector,
			long newRows, long newCols, FType initType, boolean transientInput, boolean vectorMismatch,
			boolean rowMismatch, boolean colMismatch, FType sharedAxis, int workers, List<String> consumers,
			List<String> forwards) {
			return new LocalInvocationEvidence(multiReturn, matrix, scalar, vector, newRows, newCols, initType,
				transientInput, vectorMismatch, rowMismatch, colMismatch, sharedAxis, workers, consumers, forwards);
		}
		private LocalInvocationEvidence withMultiReturnBuiltin(boolean value) { return copy(value, matrixOutput,
			scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withMatrixOutput(boolean value) { return copy(multiReturnBuiltin, value,
			scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withScalarLikeMatrix(boolean value) { return copy(multiReturnBuiltin,
			matrixOutput, value, vectorShape, rows, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withVectorShape(boolean value) { return copy(multiReturnBuiltin, matrixOutput,
			scalarLikeMatrix, value, rows, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withRows(long value) { return copy(multiReturnBuiltin, matrixOutput,
			scalarLikeMatrix, vectorShape, value, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withCols(long value) { return copy(multiReturnBuiltin, matrixOutput,
			scalarLikeMatrix, vectorShape, rows, value, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withFedInitType(FType value) { return copy(multiReturnBuiltin, matrixOutput,
			scalarLikeMatrix, vectorShape, rows, cols, value, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withTransientRead(boolean value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, value, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withVectorAxisMismatch(boolean value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead, value,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withRowAxisLengthMismatch(boolean value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead,
			vectorAxisMismatch, value, colAxisLengthMismatch, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withColAxisLengthMismatch(boolean value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead,
			vectorAxisMismatch, rowAxisLengthMismatch, value, aggregateSharedAxis, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withAggregateSharedAxis(FType value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead,
			vectorAxisMismatch, rowAxisLengthMismatch, colAxisLengthMismatch, value, numWorkers, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withNumWorkers(int value) { return copy(multiReturnBuiltin, matrixOutput,
			scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead, vectorAxisMismatch,
			rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, value, consumerEdges,
			transientForwards); }
		private LocalInvocationEvidence withConsumerEdges(List<String> value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead,
			vectorAxisMismatch, rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers,
			value, transientForwards); }
		private LocalInvocationEvidence withTransientForwards(List<String> value) { return copy(multiReturnBuiltin,
			matrixOutput, scalarLikeMatrix, vectorShape, rows, cols, fedInitType, transientRead,
			vectorAxisMismatch, rowAxisLengthMismatch, colAxisLengthMismatch, aggregateSharedAxis, numWorkers,
			consumerEdges, value); }
	}
	private record LocalDecisionReceipt(LocalCandidateKey key, ExecType nativeExec, FederatedOutput nativeOutput,
		FType nativeFoutFType, FType logicalFType, ReasonCode reasonCode, ConstructionDisposition disposition,
		Privacy privacy, LocalInvocationEvidence invocation, boolean allowCPLOUT, boolean allowCPFOUT,
		boolean allowFEDLOUT, boolean allowFEDFOUT) {
		private LocalDecisionReceipt withKey(LocalCandidateKey value) { return new LocalDecisionReceipt(value, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation,
			allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withNativeExec(ExecType value) { return new LocalDecisionReceipt(key, value,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation,
			allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withNativeOutput(FederatedOutput value) { return new LocalDecisionReceipt(key,
			nativeExec, value, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation,
			allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withNativeFoutFType(FType value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, value, logicalFType, reasonCode, disposition, privacy, invocation, allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withLogicalFType(FType value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, value, reasonCode, disposition, privacy, invocation, allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withReasonCode(ReasonCode value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, value, disposition, privacy, invocation, allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withDisposition(ConstructionDisposition value) { return new LocalDecisionReceipt(key,
			nativeExec, nativeOutput, nativeFoutFType, logicalFType, reasonCode, value, privacy, invocation,
			allowCPLOUT, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withPrivacy(Privacy value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, value, invocation, allowCPLOUT,
			allowCPFOUT, allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withInvocation(LocalInvocationEvidence value) { return new LocalDecisionReceipt(key,
			nativeExec, nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, value, allowCPLOUT,
			allowCPFOUT, allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withAllowCPLOUT(boolean value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation, value, allowCPFOUT,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withAllowCPFOUT(boolean value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation, allowCPLOUT, value,
			allowFEDLOUT, allowFEDFOUT); }
		private LocalDecisionReceipt withAllowFEDLOUT(boolean value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation, allowCPLOUT,
			allowCPFOUT,
			value, allowFEDFOUT); }
		private LocalDecisionReceipt withAllowFEDFOUT(boolean value) { return new LocalDecisionReceipt(key, nativeExec,
			nativeOutput, nativeFoutFType, logicalFType, reasonCode, disposition, privacy, invocation, allowCPLOUT,
			allowCPFOUT,
			allowFEDLOUT, value); }
	}
	private record ParityMismatch(LocalCandidateKey key, String field, String legacyValue, String neutralValue) { }
	private record Corruption(String field, LocalDecisionReceipt receipt) { }
	private record LocalMutationProbe(int oracle, int cost, int placement, int candidates, int memoWrites,
		int registryWrites, int reenums, int repairs, int fallbacks, int doubleApplications) { }
}

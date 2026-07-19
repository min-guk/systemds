/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

/** RED guard for moving DP candidate-rule authority to one exact typed neutral receipt. */
public class CampaignBG014DpEnumeratorOracleOwnershipRedTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path ENUMERATOR = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");
	private static final Path ADAPTER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java");
	private static final Set<String> FORBIDDEN_AUTHORITY = Set.of(
		"oraclefacade", "rulescore", "ruleregistry", "opcaps", "oracledecision");
	private static final Set<String> OPAQUE_RECEIPT_FIELDS = Set.of(
		"object", "supplier", "callable", "threadlocal", "methodhandle");

	@Test
	public void enumeratorRelinquishesOracleAuthorityAndConsumesExactTypedCandidateDecisionReceipt()
		throws Exception {
		String enumeratorSource = Files.readString(ENUMERATOR);
		String adapterSource = Files.readString(ADAPTER);
		List<JavaSourceTokenScanner.Token> enumeratorTokens = JavaSourceTokenScanner.tokens(enumeratorSource);
		List<JavaSourceTokenScanner.Token> adapterTokens = JavaSourceTokenScanner.tokens(adapterSource);
		List<String> authority = authorityInventory(enumeratorTokens);
		List<Integer> oracleFacadeLines = identifierLines(enumeratorTokens, "oraclefacade");
		List<String> missing = new ArrayList<>();
		List<String> opaque = new ArrayList<>();

		int receiptRecord = sequence(adapterTokens, 0, "public", "record", "CandidateDecisionReceipt", "(");
		if(receiptRecord < 0)
			missing.add("adapter.CandidateDecisionReceipt");
		else {
			List<JavaSourceTokenScanner.Token> header = parenthesized(adapterTokens, receiptRecord + 3);
			Set<String> headerIds = new LinkedHashSet<>(header.stream().map(JavaSourceTokenScanner.Token::text).toList());
			for(String required : List.of("NeutralEnumerationContext", "CandidateOccurrenceSnapshot", "FType", "ReasonCode"))
				if(!headerIds.contains(required))
					missing.add("receiptField." + required);
			long copiedAllowBits = header.stream().filter(t -> t.text().equals("boolean")).count();
			if(copiedAllowBits < 4)
				missing.add("receiptField.copiedAllowBits>=4(actual=" + copiedAllowBits + ")");
			for(JavaSourceTokenScanner.Token token : header) {
				String normalized = normalize(token.text());
				if(FORBIDDEN_AUTHORITY.contains(normalized) || OPAQUE_RECEIPT_FIELDS.contains(normalized))
					opaque.add("receiptHeader:" + token.line() + ':' + normalized);
			}
		}

		if(sequence(adapterTokens, 0, "public", "static", "CandidateDecisionReceipt",
			"resolveCandidateDecision", "(") < 0)
			missing.add("adapter.resolveCandidateDecision");
		List<String> adapterAuthority = authorityInventory(adapterTokens);
		if(!adapterAuthority.isEmpty())
			opaque.addAll(adapterAuthority.stream().map(v -> "adapter:" + v).toList());
		if(JavaSourceTokenScanner.containsSequence(adapterTokens, "OracleUtils", ".", "decideWithOracle", "("))
			opaque.add("adapter:OracleUtils.decideWithOracle");

		String canonical = JavaSourceBoundaryScanner.methodBody(
			enumeratorSource, "enumerateHop", "parentChildUploadHints");
		List<JavaSourceTokenScanner.Token> canonicalTokens = JavaSourceTokenScanner.tokens(canonical);
		int bothLoop = sequence(canonicalTokens, 0, "for", "(", "int", "j", "=", "0", ";", "j", "<",
			"numBothOutInputs", ";");
		int localLoop = sequence(canonicalTokens, Math.max(0, bothLoop + 1), "for", "(", "int", "j", "=", "0",
			";", "j", "<", "numLoutOnlyInputs", ";");
		int federatedLoop = sequence(canonicalTokens, Math.max(0, localLoop + 1), "for", "(", "int", "j", "=", "0",
			";", "j", "<", "numFoutOnlyInputs", ";");
		int normalization = sequence(canonicalTokens, Math.max(0, federatedLoop + 1),
			"DpPlacementAdapter", ".", "normalizeCandidateInputs", "(");
		int capture = sequence(canonicalTokens, Math.max(0, normalization + 1), "capture", ".", "capture", "(",
			"normalizedCandidateInputs", ".", "snapshot", "(", ")", ",", "i", ")");
		int receipt = sequence(canonicalTokens, Math.max(0, capture + 1), "DpPlacementAdapter", ".",
			"CandidateDecisionReceipt");
		if(!(bothLoop >= 0 && localLoop > bothLoop && federatedLoop > localLoop && normalization > federatedLoop
			&& capture > normalization))
			missing.add("canonical.normalizeThenCaptureAfterExactThreeChildLoops");
		if(receipt < 0)
			missing.add("canonical.typedCandidateDecisionReceipt");
		else {
			String receiptVariable = token(canonicalTokens, receipt + 3);
			int initializer = sequence(canonicalTokens, receipt, "=", "DpPlacementAdapter", ".",
				"resolveCandidateDecision", "(", "capture", ".", "context", ",", "normalizedCandidateInputs", ")");
			if(initializer < 0)
				missing.add("canonical.exactContextAndNormalizedCandidateResolver");
			if(sequence(canonicalTokens, receipt, "new", "CandidateDecisionReceipt") >= 0)
				opaque.add("enumerator:new CandidateDecisionReceipt");
			if(sequence(canonicalTokens, receipt + 1, receiptVariable, ".", "logicalFType", "(", ")") < 0)
				missing.add("canonical.receipt.logicalFType");
			if(!hasCapabilityConsumption(canonicalTokens, receipt + 1, receiptVariable))
				missing.add("canonical.receipt.capabilityOrPlacementEvidence");
		}
		int resolverCalls = countSequence(canonicalTokens,
			"DpPlacementAdapter", ".", "resolveCandidateDecision", "(");
		if(resolverCalls != 1)
			missing.add("canonical.singleResolverCall(actual=" + resolverCalls + ")");
		if(JavaSourceTokenScanner.containsSequence(canonicalTokens, "OracleUtils", ".", "decideWithOracle", "("))
			opaque.add("canonical:OracleUtils.decideWithOracle");

		List<String> failures = new ArrayList<>();
		if(!authority.isEmpty())
			failures.add("authority=" + authority);
		if(!oracleFacadeLines.isEmpty())
			failures.add("oraclefacadeCount=" + oracleFacadeLines.size() + ",oraclefacadeLines=" + oracleFacadeLines);
		if(!missing.isEmpty())
			failures.add("missing=" + missing);
		if(!opaque.isEmpty())
			failures.add("opaque=" + new TreeSet<>(opaque));
		Assert.assertEquals("G014_DP_ENUMERATOR_ORACLE_OWNERSHIP", List.of(), failures);
	}

	private static List<String> authorityInventory(List<JavaSourceTokenScanner.Token> tokens) {
		Set<String> inventory = new TreeSet<>();
		for(JavaSourceTokenScanner.Token token : tokens) {
			String normalized = normalize(token.text());
			if(FORBIDDEN_AUTHORITY.contains(normalized))
				inventory.add(token.line() + ":" + normalized);
		}
		if(JavaSourceTokenScanner.containsSequence(tokens, "RulesCore", ".", "RulesModule", ".",
			"createDefaultRegistry", "("))
			inventory.add("call:RulesCore.RulesModule.createDefaultRegistry");
		if(JavaSourceTokenScanner.containsSequence(tokens, "OracleUtils", ".", "decideWithOracle", "("))
			inventory.add("call:OracleUtils.decideWithOracle");
		return List.copyOf(inventory);
	}

	private static List<Integer> identifierLines(List<JavaSourceTokenScanner.Token> tokens, String identifier) {
		Set<Integer> lines = new TreeSet<>();
		for(JavaSourceTokenScanner.Token token : tokens)
			if(normalize(token.text()).equals(identifier))
				lines.add(token.line());
		return List.copyOf(lines);
	}

	private static List<JavaSourceTokenScanner.Token> parenthesized(
		List<JavaSourceTokenScanner.Token> tokens, int opening) {
		if(opening < 0 || opening >= tokens.size() || !tokens.get(opening).text().equals("("))
			return List.of();
		int depth = 0;
		for(int i = opening; i < tokens.size(); i++) {
			if(tokens.get(i).text().equals("("))
				depth++;
			else if(tokens.get(i).text().equals(")") && --depth == 0)
				return List.copyOf(tokens.subList(opening + 1, i));
		}
		return List.of();
	}

	private static boolean hasCapabilityConsumption(List<JavaSourceTokenScanner.Token> tokens, int start,
		String variable) {
		for(String accessor : List.of("capabilityFact", "placementDecision", "reasonCode", "allowCPLOUT",
			"allowCPFOUT", "allowFEDLOUT", "allowFEDFOUT"))
			if(sequence(tokens, start, variable, ".", accessor) >= 0)
				return true;
		return false;
	}

	private static int countSequence(List<JavaSourceTokenScanner.Token> tokens, String... sequence) {
		int count = 0;
		for(int from = 0; from < tokens.size();) {
			int found = sequence(tokens, from, sequence);
			if(found < 0)
				return count;
			count++;
			from = found + sequence.length;
		}
		return count;
	}

	private static int sequence(List<JavaSourceTokenScanner.Token> tokens, int from, String... sequence) {
		outer: for(int i = Math.max(0, from); i + sequence.length <= tokens.size(); i++) {
			for(int j = 0; j < sequence.length; j++)
				if(!tokens.get(i + j).text().equals(sequence[j]))
					continue outer;
			return i;
		}
		return -1;
	}

	private static String token(List<JavaSourceTokenScanner.Token> tokens, int index) {
		return index >= 0 && index < tokens.size() ? tokens.get(index).text() : "<missing>";
	}

	private static String normalize(String identifier) {
		return identifier.replace("_", "").toLowerCase(Locale.ROOT);
	}
}

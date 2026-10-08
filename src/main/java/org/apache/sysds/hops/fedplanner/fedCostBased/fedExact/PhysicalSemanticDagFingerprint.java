/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.CpRuleFamily;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;

/** Invocation-local, identity-memoized semantic DAG hashes for physical authority subgraphs. */
final class PhysicalSemanticDagFingerprint {
	static final String SCHEMA = "physical-semantic-dag-v3";
	private static final byte[] ABSENT = {(byte)0};
	private static final int TEXT_BUFFER_BYTES = 1024;
	private static final long LITERAL_BYTE_MEMO_MAX_ESTIMATED_BYTES = 64L * 1024 * 1024;
	private final IdentityHashMap<Object,byte[]> memo = new IdentityHashMap<>();
	private final IdentityHashMap<Alternative,byte[]> canonicalAlternativeMemo =
		new IdentityHashMap<>();
	private final IdentityHashMap<RelocationAction,String> relocationActionSignatures =
		new IdentityHashMap<>();
	private final byte[] textBuffer = new byte[TEXT_BUFFER_BYTES];
	private final NormalizedTextSharingDiagnostics textDiagnostics;
	private final LiteralByteMemo literalBytes;

	PhysicalSemanticDagFingerprint() { this(null); }

	PhysicalSemanticDagFingerprint(NormalizedTextSharingDiagnostics textDiagnostics) {
		this(textDiagnostics, LITERAL_BYTE_MEMO_MAX_ESTIMATED_BYTES, false);
	}

	/** Package-visible bounded-storage and work-counter seam for differential tests. */
	PhysicalSemanticDagFingerprint(NormalizedTextSharingDiagnostics textDiagnostics,
		long literalByteMemoMaxEstimatedBytes) {
		this(textDiagnostics, literalByteMemoMaxEstimatedBytes, true);
	}

	private PhysicalSemanticDagFingerprint(NormalizedTextSharingDiagnostics textDiagnostics,
		long literalByteMemoMaxEstimatedBytes, boolean collectLiteralByteMemoStatistics) {
		if(literalByteMemoMaxEstimatedBytes < 0)
			throw new IllegalArgumentException("Literal byte memo limit must be nonnegative");
		this.textDiagnostics = textDiagnostics;
		literalBytes = new LiteralByteMemo(literalByteMemoMaxEstimatedBytes,
			collectLiteralByteMemoStatistics);
	}

	void appendSchema(ExactPhysicalCostModel.FingerprintWriter target) {
		target.append("|semantic-dag-schema=").append(SCHEMA);
	}

	void appendCandidateOccurrence(ExactPhysicalCostModel.FingerprintWriter target,
		CandidateRuleFact fact) {
		target.append("|candidate-dag=").append(HexFormat.of().formatHex(candidate(fact)));
	}

	void appendAlternativeOccurrence(ExactPhysicalCostModel.FingerprintWriter target,
		Alternative alternative) {
		target.append("|alternative-dag=").append(HexFormat.of().formatHex(alternative(alternative)));
	}

	void appendModelAlternativeOccurrences(ExactPhysicalCostModel.FingerprintWriter target,
		ExactPhysicalModel model) {
		// Only the private-factory immutable model may bypass signature classification. Every
		// recipe input must remain present in the typed fields below; changing a recipe requires
		// a new recipe id and semantic DAG schema.
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			target.append("|domain:").append(domain.node().key().normalizedSignature());
			for(Alternative alternative : domain.alternatives())
				target.append("|alternative-dag=")
					.append(HexFormat.of().formatHex(canonicalAlternative(alternative)));
		}
	}

	String candidateFactsForTest(List<CandidateRuleFact> facts) {
		Node root = node("candidate-facts-root");
		root.integer("count", facts.size());
		for(CandidateRuleFact fact : facts)
			root.child("candidate", candidate(fact));
		return SCHEMA + ':' + root.finishHex();
	}

	String alternativeForTest(Alternative value) {
		Node root = node("alternative-root");
		root.child("alternative", alternative(value));
		return SCHEMA + ':' + root.finishHex();
	}

	String modelAlternativesForTest(ExactPhysicalModel model) {
		ExactPhysicalCostModel.FingerprintWriter root = new ExactPhysicalCostModel.FingerprintWriter();
		appendModelAlternativeOccurrences(root, model);
		return SCHEMA + ':' + root.finish();
	}

	String normalizedTextsForTest(List<NormalizedText> values) {
		Node root = node("normalized-text-root");
		root.integer("count", values.size());
		for(NormalizedText value : values)
			root.normalizedText("value", value);
		return SCHEMA + ':' + root.finishHex();
	}

	int memoizedNodesForTest() { return memo.size(); }
	LiteralByteMemoSnapshot literalByteMemoSnapshotForTest() { return literalBytes.snapshot(); }
	void clearLiteralByteMemo() { literalBytes.clear(); }

	private byte[] candidate(CandidateRuleFact fact) {
		return memoized(fact, () -> digest("candidate-rule-fact", node -> {
			node.child("key", ruleKey(fact.key()));
			node.text("status", fact.status().name());
			CandidateCapabilityFact capability = fact.capability();
			node.present("capability", capability != null);
			if(capability != null) {
				node.text("category", capability.category().name());
				node.text("opcode", capability.opcode());
				node.text("nativeExec", capability.nativeExec().name());
				node.text("nativeOutput", capability.nativeOutput().name());
				node.nullableText("nativeFoutFType",
					capability.nativeFoutFType() == null ? null : capability.nativeFoutFType().name());
				node.text("reasonCode", capability.reasonCode().name());
				node.text("detail", capability.detail());
				node.integer("notes", capability.notes().size());
				capability.notes().forEach(note -> {
					node.text("noteCode", note.code().name());
					node.text("noteMessage", note.message());
				});
			}
			node.integer("consultedFacts", fact.shapeProof().consultedFacts().size());
			for(Map.Entry<String,String> entry : fact.shapeProof().consultedFacts().entrySet()) {
				node.text("consultedKey", entry.getKey());
				node.nullableText("consultedValue", entry.getValue());
			}
			texts(node, "requiredFact", fact.shapeProof().requiredFacts());
			texts(node, "missingFact", fact.shapeProof().missingRequiredFacts());
			node.integer("producerOutputs", fact.profile().producerOutputs().size());
			fact.profile().producerOutputs().forEach(value -> node.text("producerOutput", value.name()));
			node.text("profileFailure", fact.profile().evaluationFailure());
			node.integer("emissions", fact.allowedEmissionFacts().size());
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				node.child("emission", emission(emission));
			node.text("failureCode", fact.failureCode());
		}));
	}

	private byte[] emission(CandidateEmissionFact emission) {
		return memoized(emission, () -> digest("candidate-emission", node -> {
			node.text("state", emission.emissionState().placementState().normalizedSignature());
			node.bool("derivedFedFout", emission.emissionState().derivedFedFout());
			node.nullableText("executionFType",
				emission.executionFType() == null ? null : emission.executionFType().name());
			node.nullableText("derivedAction", emission.derivedFoutAction() == null ? null
				: emission.derivedFoutAction().normalizedSignature());
			node.integer("realizations", emission.realizations().size());
			for(CandidateEmissionRealization realization : emission.realizations())
				node.child("realization", realization(realization));
		}));
	}

	private byte[] cpRuleFamily(CpRuleFamily family) {
		return memoized(family, () -> digest("cp-rule-family", node -> {
			node.text("parent", family.parent().normalizedSignature());
			node.integer("axes", family.axes().size());
			for(List<CandidateInputState> axis : family.axes()) {
				node.integer("axisSize", axis.size());
				for(CandidateInputState input : axis)
					node.text("axisInput", input.normalizedSignature());
			}
			CandidateCapabilityFact capability = family.capability();
			node.text("category", capability.category().name());
			node.text("opcode", capability.opcode());
			node.text("nativeExec", capability.nativeExec().name());
			node.text("nativeOutput", capability.nativeOutput().name());
			node.nullableText("nativeFoutFType", capability.nativeFoutFType() == null
				? null : capability.nativeFoutFType().name());
			node.text("reasonCode", capability.reasonCode().name());
			node.text("detail", capability.detail());
			node.integer("notes", capability.notes().size());
			capability.notes().forEach(note -> {
				node.text("noteCode", note.code().name());
				node.text("noteMessage", note.message());
			});
			node.integer("consultedFacts", family.shapeProof().consultedFacts().size());
			for(Map.Entry<String,String> entry : family.shapeProof().consultedFacts().entrySet()) {
				node.text("consultedKey", entry.getKey());
				node.nullableText("consultedValue", entry.getValue());
			}
			texts(node, "requiredFact", family.shapeProof().requiredFacts());
			texts(node, "missingFact", family.shapeProof().missingRequiredFacts());
			node.integer("producerOutputs", family.profile().producerOutputs().size());
			family.profile().producerOutputs().forEach(value -> node.text("producerOutput", value.name()));
			node.text("profileFailure", family.profile().evaluationFailure());
			node.child("emission", emission(family.emission()));
		}));
	}

	private byte[] realization(CandidateEmissionRealization realization) {
		return memoized(realization, () -> digest("candidate-realization", node -> {
			node.child("key", realizationKey(realization.key()));
			node.integer("clauses", realization.supportClauses().size());
			var product = realization.factorizedSupportProduct().orElse(null);
			if(product != null) {
				// Frame the stored relation itself. Expanding clauses here defeats
				// factorized model construction before the optimizer even starts.
				node.text("supportEncoding", "INDEPENDENT_PRODUCT_V1");
				node.integer("proofs", product.proofDependencies().size());
				for(PlacementProofKey proof : product.proofDependencies())
					node.child("proof", proof(proof));
				node.nullableText("nativePool", product.nativeWorkerPoolWitness() == null ? null
					: product.nativeWorkerPoolWitness().normalizedSignature());
				node.bool("nativePoolLayoutExact", product.nativeWorkerPoolLayoutExact());
				node.integer("axes", product.factors().size());
				for(var axis : product.factors()) {
					node.integer("options", axis.size());
					for(CandidateRealizationInputBinding option : axis)
						node.child("binding", binding(option));
				}
			}
			else
				for(CandidateRealizationSupportClause clause : realization.supportClauses())
					node.child("clause", clause(clause));
		}));
	}

	private byte[] clause(CandidateRealizationSupportClause clause) {
		return memoized(clause, () -> digest("support-clause", node -> {
			node.integer("proofs", clause.proofDependencies().size());
			for(PlacementProofKey proof : clause.proofDependencies())
				node.child("proof", proof(proof));
			node.integer("bindings", clause.inputBindings().size());
			for(CandidateRealizationInputBinding binding : clause.inputBindings())
				node.child("binding", binding(binding));
			node.nullableText("nativePool", clause.nativeWorkerPoolWitness() == null ? null
				: clause.nativeWorkerPoolWitness().normalizedSignature());
			node.bool("nativePoolLayoutExact", clause.nativeWorkerPoolLayoutExact());
		}));
	}

	private byte[] proof(PlacementProofKey proof) {
		return memoized(proof, () -> digest("placement-proof", node -> {
			node.text("kind", proof.kind().name());
			node.nullableText("owner", proof.owner() == null ? null : proof.owner().normalizedSignature());
			node.text("authority", proof.authoritySignature());
		}));
	}

	private byte[] binding(CandidateRealizationInputBinding binding) {
		return memoized(binding, () -> digest("input-binding", node -> {
			node.integer("position", binding.inputPosition());
			node.child("source", reference(binding.source()));
			node.text("kind", binding.kind().name());
			node.nullableText("action", binding.relocationAction() == null ? null
				: binding.relocationAction().normalizedSignature());
		}));
	}

	private byte[] reference(CandidateRealizationReference reference) {
		return memoized(reference, () -> digest("realization-reference", node -> {
			node.child("rule", ruleKey(reference.rule()));
			node.child("realizationKey", realizationKey(reference.realization()));
		}));
	}

	private byte[] ruleKey(CandidateRuleKey rule) {
		return memoized(rule, () -> digest("candidate-rule-key", node -> {
			node.text("parent", rule.parentOccurrence().normalizedSignature());
			node.integer("inputs", rule.orderedInputs().size());
			for(CandidateInputState input : rule.orderedInputs()) {
				node.text("presence", input.presence().name());
				node.nullableText("fType", input.fType() == null ? null : input.fType().name());
			}
		}));
	}

	private byte[] realizationKey(PlacementRealizationKey key) {
		return memoized(key, () -> digest("realization-key", node -> {
			node.text("state", key.emissionState().placementState().normalizedSignature());
			node.bool("derivedFedFout", key.emissionState().derivedFedFout());
			node.text("layoutKind", key.layoutKind().name());
			node.nullableText("durableAnchor",
				key.durableAnchor() == null ? null : key.durableAnchor().normalizedSignature());
			node.nullableText("nativeLineage", key.nativeLineage());
		}));
	}

	private byte[] alternative(Alternative alternative) {
		NormalizedText recipe = ExactPhysicalModel.canonicalAlternativeSignature(alternative);
		boolean canonical = recipe != null && recipe.equals(alternative.normalizedSignature());
		return canonical ? canonicalAlternative(alternative)
			: memoized(alternative, () -> encodeAlternative(alternative, false));
	}

	private byte[] canonicalAlternative(Alternative alternative) {
		byte[] retained = canonicalAlternativeMemo.get(alternative);
		if(retained != null)
			return retained;
		byte[] encoded = encodeAlternative(alternative, true);
		canonicalAlternativeMemo.put(alternative, encoded);
		return encoded;
	}

	private byte[] encodeAlternative(Alternative alternative, boolean canonical) {
		return digest("physical-alternative", node -> {
			node.text("decision", alternative.decision().normalizedSignature());
			node.text("state", alternative.state().normalizedSignature());
			node.text("authorityKind", alternative.authorityKind().name());
			node.nullableChild("candidateRule", alternative.candidateRule() == null ? null
				: candidate(alternative.candidateRule()));
			node.nullableChild("candidateEmission", alternative.candidateEmission() == null ? null
				: emission(alternative.candidateEmission()));
			node.nullableChild("executionRule", alternative.executionRule() == null ? null
				: candidate(alternative.executionRule()));
			node.nullableChild("executionEmission", alternative.executionEmission() == null ? null
				: emission(alternative.executionEmission()));
			node.nullableText("durableAnchor", alternative.durableAnchor() == null ? null
				: alternative.durableAnchor().normalizedSignature());
			node.nullableText("relocationAction", alternative.relocationAction() == null ? null
				: relocationActionSignature(alternative.relocationAction()));
			node.nullableText("derivedFoutAction", alternative.derivedFoutAction() == null ? null
				: alternative.derivedFoutAction().normalizedSignature());
			if(alternative.cpRuleFamily() != null)
				node.child("cpRuleFamily", cpRuleFamily(alternative.cpRuleFamily()));
			node.integer("orderedInputs", alternative.orderedInputs().size());
			for(CandidateInputState input : alternative.orderedInputs()) {
				node.text("inputPresence", input.presence().name());
				node.nullableText("inputFType", input.fType() == null ? null : input.fType().name());
			}
			node.integer("inputAuthorities", alternative.inputAuthorities().size());
			for(InputAuthority authority : alternative.inputAuthorities())
				inputAuthority(node, authority);
			node.nullableChild("realization", alternative.realization() == null ? null
				: realization(alternative.realization()));
			node.nullableChild("supportClause", alternative.supportClause() == null ? null
				: clause(alternative.supportClause()));
			if(alternative.compactSupport() != null)
				node.text("supportSelection", "PRODUCER_MEMBERSHIP_V1");
			node.text("signatureEncoding", canonical ? "CANONICAL_RECIPE" : "RAW_UTF16");
			if(canonical)
				node.text("signatureRecipe", alternative.captured() ? "CAPTURED_V1" : "NONCAPTURED_V1");
			else
				node.normalizedText("normalizedSignature", alternative.normalizedSignature());
		});
	}

	private void inputAuthority(Node node, InputAuthority authority) {
		node.integer("authorityPosition", authority.inputPosition());
		node.text("authorityKind", authority.kind().name());
		node.nullableText("authorityFType",
			authority.expectedFType() == null ? null : authority.expectedFType().name());
		node.nullableText("authoritySource", authority.sourceDecision() == null ? null
			: authority.sourceDecision().normalizedSignature());
		node.nullableText("authorityAction", authority.relocationAction() == null ? null
			: relocationActionSignature(authority.relocationAction()));
	}

	private String relocationActionSignature(RelocationAction action) {
		return relocationActionSignatures.computeIfAbsent(action,
			RelocationAction::normalizedSignature);
	}

	private static void texts(Node node, String field, List<String> values) {
		node.integer(field + "Count", values.size());
		values.forEach(value -> node.text(field, value));
	}

	private byte[] memoized(Object owner, Supplier<byte[]> compute) {
		byte[] retained = memo.get(owner);
		if(retained != null)
			return retained;
		byte[] value = compute.get();
		memo.put(owner, value);
		return value;
	}

	private byte[] digest(String type, Consumer<Node> fields) {
		Node node = node(type);
		fields.accept(node);
		return node.finish();
	}

	private Node node(String type) {
		return new Node(type, textBuffer, textDiagnostics, literalBytes);
	}

	static final class NormalizedTextSharingDiagnostics {
		private final IdentityHashMap<NormalizedText,Boolean> normalizedObjects =
			new IdentityHashMap<>();
		private final IdentityHashMap<String,Boolean> literals = new IdentityHashMap<>();
		private long normalizedOccurrences;
		private long normalizedUnits;
		private long uniqueNormalizedUnits;
		private long repeatedNormalizedUnits;
		private long literalOccurrences;
		private long literalUnits;
		private long uniqueLiteralUnits;
		private long repeatedLiteralUnits;

		private void normalized(NormalizedText value) {
			normalizedOccurrences = saturatedAdd(normalizedOccurrences, 1L);
			normalizedUnits = saturatedAdd(normalizedUnits, value.length());
			if(normalizedObjects.put(value, Boolean.TRUE) == null)
				uniqueNormalizedUnits = saturatedAdd(uniqueNormalizedUnits, value.length());
			else
				repeatedNormalizedUnits = saturatedAdd(repeatedNormalizedUnits, value.length());
		}

		private void literal(String value) {
			literalOccurrences = saturatedAdd(literalOccurrences, 1L);
			literalUnits = saturatedAdd(literalUnits, value.length());
			if(literals.put(value, Boolean.TRUE) == null)
				uniqueLiteralUnits = saturatedAdd(uniqueLiteralUnits, value.length());
			else
				repeatedLiteralUnits = saturatedAdd(repeatedLiteralUnits, value.length());
		}

		NormalizedTextSharingSnapshot snapshotAndClear() {
			NormalizedTextSharingSnapshot result = new NormalizedTextSharingSnapshot(
				normalizedOccurrences, normalizedObjects.size(), normalizedUnits,
				uniqueNormalizedUnits, repeatedNormalizedUnits, literalOccurrences,
				literals.size(), literalUnits, uniqueLiteralUnits, repeatedLiteralUnits,
				saturatedMultiplyByTwo(uniqueLiteralUnits),
				saturatedMultiplyByTwo(repeatedLiteralUnits));
			normalizedObjects.clear();
			literals.clear();
			normalizedOccurrences = 0L;
			normalizedUnits = 0L;
			uniqueNormalizedUnits = 0L;
			repeatedNormalizedUnits = 0L;
			literalOccurrences = 0L;
			literalUnits = 0L;
			uniqueLiteralUnits = 0L;
			repeatedLiteralUnits = 0L;
			return result;
		}

		private static long saturatedAdd(long left, long right) {
			return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
		}

		private static long saturatedMultiplyByTwo(long value) {
			return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
		}
	}

	record NormalizedTextSharingSnapshot(long normalizedOccurrences,
		long distinctNormalizedIdentities, long normalizedUnits,
		long uniqueNormalizedUnits, long repeatedNormalizedUnits,
		long literalOccurrences, long distinctLiteralIdentities, long literalUnits,
		long uniqueLiteralUnits, long repeatedLiteralUnits,
		long estimatedRetainedUtf16Bytes, long estimatedAvoidedConversionBytes) { }

	record LiteralByteMemoSnapshot(long lookups, long hits, long conversions,
		long convertedUtf16Units, long admittedEntries, long rejectedEntries,
		long retainedEstimatedBytes, int retainedEntries) { }

	private static final class LiteralByteMemo {
		private static final long ENTRY_OVERHEAD_BYTES = 64L;
		private final IdentityHashMap<String,byte[]> bytesByLiteral = new IdentityHashMap<>();
		private final long maximumEstimatedBytes;
		private final boolean collectStatistics;
		private long retainedEstimatedBytes;
		private long lookups, hits, conversions, convertedUtf16Units;
		private long admittedEntries, rejectedEntries;

		private LiteralByteMemo(long maximumEstimatedBytes, boolean collectStatistics) {
			this.maximumEstimatedBytes = maximumEstimatedBytes;
			this.collectStatistics = collectStatistics;
		}

		private void write(String value, MessageDigest digest, byte[] fallbackBuffer) {
			if(collectStatistics)
				lookups++;
			if(value.isEmpty())
				return;
			byte[] retained = bytesByLiteral.get(value);
			if(retained != null) {
				if(collectStatistics)
					hits++;
				updateDigest(digest, retained);
				return;
			}
			long payloadBytes = 2L * value.length();
			long estimatedBytes = payloadBytes > Long.MAX_VALUE - ENTRY_OVERHEAD_BYTES
				? Long.MAX_VALUE : ENTRY_OVERHEAD_BYTES + payloadBytes;
			if(payloadBytes <= Integer.MAX_VALUE
				&& estimatedBytes <= maximumEstimatedBytes - retainedEstimatedBytes) {
				byte[] converted = convert(value);
				bytesByLiteral.put(value, converted);
				retainedEstimatedBytes += estimatedBytes;
				if(collectStatistics)
					admittedEntries++;
				updateDigest(digest, converted);
			}
			else {
				if(collectStatistics)
					rejectedEntries++;
				recordConversion(value.length());
				Node.writeChars(value, digest, fallbackBuffer);
			}
		}

		private byte[] convert(String value) {
			recordConversion(value.length());
			byte[] converted = new byte[value.length() * 2];
			for(int source = 0, target = 0; source < value.length(); source++) {
				char unit = value.charAt(source);
				converted[target++] = (byte)(unit >>> 8);
				converted[target++] = (byte)unit;
			}
			return converted;
		}

		private void recordConversion(int utf16Units) {
			if(collectStatistics) {
				conversions++;
				convertedUtf16Units += utf16Units;
			}
		}

		private void updateDigest(MessageDigest digest, byte[] bytes) {
			for(int offset = 0; offset < bytes.length; offset += TEXT_BUFFER_BYTES)
				digest.update(bytes, offset, Math.min(TEXT_BUFFER_BYTES, bytes.length - offset));
		}

		private LiteralByteMemoSnapshot snapshot() {
			return new LiteralByteMemoSnapshot(lookups, hits, conversions, convertedUtf16Units,
				admittedEntries, rejectedEntries, retainedEstimatedBytes, bytesByLiteral.size());
		}

		private void clear() {
			bytesByLiteral.clear();
			retainedEstimatedBytes = 0L;
		}
	}

	private static final class Node {
		private final MessageDigest digest;
		private final byte[] textBuffer;
		private final NormalizedTextSharingDiagnostics textDiagnostics;
		private final LiteralByteMemo literalBytes;

		private Node(String type, byte[] textBuffer,
			NormalizedTextSharingDiagnostics textDiagnostics, LiteralByteMemo literalBytes) {
			this.textBuffer = textBuffer;
			this.textDiagnostics = textDiagnostics;
			this.literalBytes = literalBytes;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			}
			catch(NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 is unavailable", ex);
			}
			text("nodeType", SCHEMA + ':' + type);
		}

		private void present(String name, boolean value) {
			name(name);
			digest.update((byte)(value ? 1 : 0));
		}

		private void bool(String name, boolean value) {
			present(name, value);
		}

		private void integer(String name, int value) {
			name(name);
			writeInt(value);
		}

		private void nullableText(String name, String value) {
			name(name);
			if(value == null)
				digest.update(ABSENT);
			else {
				digest.update((byte)1);
				writeInt(value.length());
				// Typed authority strings recur across many physical alternatives.
				literalBytes.write(value, digest, textBuffer);
			}
		}

		private void text(String name, String value) {
			name(name);
			writeText(value);
		}

		private void normalizedText(String name, NormalizedText value) {
			name(name);
			writeInt(value.length());
			if(textDiagnostics == null)
				value.appendTo(this::writeLiteral);
			else {
				textDiagnostics.normalized(value);
				value.appendTo(segment -> {
					textDiagnostics.literal(segment);
					writeLiteral(segment);
				});
			}
		}

		private void writeLiteral(String value) {
			literalBytes.write(value, digest, textBuffer);
		}

		private void nullableChild(String name, byte[] child) {
			name(name);
			if(child == null)
				digest.update(ABSENT);
			else {
				digest.update((byte)1);
				digest.update(child);
			}
		}

		private void child(String name, byte[] child) {
			name(name);
			digest.update(child);
		}

		private void name(String name) { writeText(name); }

		private void writeText(String value) {
			writeInt(value.length());
			writeChars(value);
		}

		private void writeChars(String value) {
			writeChars(value, digest, textBuffer);
		}

		private static void writeChars(String value, MessageDigest digest, byte[] textBuffer) {
			for(int source = 0; source < value.length();) {
				int units = Math.min(value.length() - source, textBuffer.length / 2);
				int target = 0;
				for(int end = source + units; source < end; source++) {
					char unit = value.charAt(source);
					textBuffer[target++] = (byte)(unit >>> 8);
					textBuffer[target++] = (byte)unit;
				}
				digest.update(textBuffer, 0, target);
			}
		}

		private void writeInt(int value) {
			digest.update((byte)(value >>> 24));
			digest.update((byte)(value >>> 16));
			digest.update((byte)(value >>> 8));
			digest.update((byte)value);
		}

		private byte[] finish() { return digest.digest(); }
		private String finishHex() { return HexFormat.of().formatHex(finish()); }
	}
}

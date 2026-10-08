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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
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

/** Test-only schema traversal intentionally independent of production memoization and encoding. */
final class IndependentPhysicalSemanticDagOracle {
	static final String SCHEMA = "physical-semantic-dag-v3";
	private static final byte[] ABSENT = {(byte)0};
	static String candidates(List<CandidateRuleFact> facts) {
		IndependentPhysicalSemanticDagOracle oracle = new IndependentPhysicalSemanticDagOracle();
		Node root = node("candidate-facts-root");
		root.integer("count", facts.size());
		for(CandidateRuleFact fact : facts)
			root.child("candidate", oracle.candidate(fact));
		return SCHEMA + ':' + root.finishHex();
	}

	static String alternativeValue(Alternative value) {
		IndependentPhysicalSemanticDagOracle oracle = new IndependentPhysicalSemanticDagOracle();
		Node root = node("alternative-root");
		root.child("alternative", oracle.alternative(value));
		return SCHEMA + ':' + root.finishHex();
	}

	private byte[] candidate(CandidateRuleFact fact) {
		return digest("candidate-rule-fact", node -> {
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
		});
	}

	private byte[] emission(CandidateEmissionFact emission) {
		return digest("candidate-emission", node -> {
			node.text("state", emission.emissionState().placementState().normalizedSignature());
			node.bool("derivedFedFout", emission.emissionState().derivedFedFout());
			node.nullableText("executionFType",
				emission.executionFType() == null ? null : emission.executionFType().name());
			node.nullableText("derivedAction", emission.derivedFoutAction() == null ? null
				: emission.derivedFoutAction().normalizedSignature());
			node.integer("realizations", emission.realizations().size());
			for(CandidateEmissionRealization realization : emission.realizations())
				node.child("realization", realization(realization));
		});
	}

	private byte[] realization(CandidateEmissionRealization realization) {
		return digest("candidate-realization", node -> {
			node.child("key", realizationKey(realization.key()));
			node.integer("clauses", realization.supportClauses().size());
			var product = realization.factorizedSupportProduct().orElse(null);
			if(product == null) {
				for(CandidateRealizationSupportClause clause : realization.supportClauses())
					node.child("clause", clause(clause));
			}
			else {
				node.text("supportEncoding", "INDEPENDENT_PRODUCT_V1");
				node.integer("proofs", product.proofDependencies().size());
				product.proofDependencies().forEach(value -> node.child("proof", proof(value)));
				node.nullableText("nativePool", product.nativeWorkerPoolWitness() == null ? null
					: product.nativeWorkerPoolWitness().normalizedSignature());
				node.bool("nativePoolLayoutExact", product.nativeWorkerPoolLayoutExact());
				node.integer("axes", product.factors().size());
				product.factors().forEach(axis -> {
					node.integer("options", axis.size());
					axis.forEach(value -> node.child("binding", binding(value)));
				});
			}
		});
	}

	private byte[] clause(CandidateRealizationSupportClause clause) {
		return digest("support-clause", node -> {
			node.integer("proofs", clause.proofDependencies().size());
			for(PlacementProofKey proof : clause.proofDependencies())
				node.child("proof", proof(proof));
			node.integer("bindings", clause.inputBindings().size());
			for(CandidateRealizationInputBinding binding : clause.inputBindings())
				node.child("binding", binding(binding));
			node.nullableText("nativePool", clause.nativeWorkerPoolWitness() == null ? null
				: clause.nativeWorkerPoolWitness().normalizedSignature());
			node.bool("nativePoolLayoutExact", clause.nativeWorkerPoolLayoutExact());
		});
	}

	private byte[] proof(PlacementProofKey proof) {
		return digest("placement-proof", node -> {
			node.text("kind", proof.kind().name());
			node.nullableText("owner", proof.owner() == null ? null : proof.owner().normalizedSignature());
			node.text("authority", proof.authoritySignature());
		});
	}

	private byte[] binding(CandidateRealizationInputBinding binding) {
		return digest("input-binding", node -> {
			node.integer("position", binding.inputPosition());
			node.child("source", reference(binding.source()));
			node.text("kind", binding.kind().name());
			node.nullableText("action", binding.relocationAction() == null ? null
				: binding.relocationAction().normalizedSignature());
		});
	}

	private byte[] reference(CandidateRealizationReference reference) {
		return digest("realization-reference", node -> {
			node.child("rule", ruleKey(reference.rule()));
			node.child("realizationKey", realizationKey(reference.realization()));
		});
	}

	private byte[] ruleKey(CandidateRuleKey rule) {
		return digest("candidate-rule-key", node -> {
			node.text("parent", rule.parentOccurrence().normalizedSignature());
			node.integer("inputs", rule.orderedInputs().size());
			for(CandidateInputState input : rule.orderedInputs()) {
				node.text("presence", input.presence().name());
				node.nullableText("fType", input.fType() == null ? null : input.fType().name());
			}
		});
	}

	private byte[] realizationKey(PlacementRealizationKey key) {
		return digest("realization-key", node -> {
			node.text("state", key.emissionState().placementState().normalizedSignature());
			node.bool("derivedFedFout", key.emissionState().derivedFedFout());
			node.text("layoutKind", key.layoutKind().name());
			node.nullableText("durableAnchor",
				key.durableAnchor() == null ? null : key.durableAnchor().normalizedSignature());
			node.nullableText("nativeLineage", key.nativeLineage());
		});
	}

	private byte[] alternative(Alternative alternative) {
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
				: alternative.relocationAction().normalizedSignature());
			node.nullableText("derivedFoutAction", alternative.derivedFoutAction() == null ? null
				: alternative.derivedFoutAction().normalizedSignature());
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
			String recipe = canonicalRecipe(alternative);
			boolean canonical = recipe != null
				&& NormalizedText.literal(recipe).equals(alternative.normalizedSignature());
			node.text("signatureEncoding", canonical ? "CANONICAL_RECIPE" : "RAW_UTF16");
			if(canonical)
				node.text("signatureRecipe", alternative.captured() ? "CAPTURED_V1" : "NONCAPTURED_V1");
			else
				node.normalizedText("normalizedSignature", alternative.normalizedSignature());
		});
	}

	private static String canonicalRecipe(Alternative alternative) {
		if(alternative.authorityKind() == ExactPhysicalModel.AuthorityKind.CAPTURED_RULE) {
			if(alternative.candidateRule() == null || alternative.candidateEmission() == null
				|| alternative.executionRule() != null || alternative.executionEmission() != null
				|| alternative.durableAnchor() != null || alternative.relocationAction() != null
				|| alternative.realization() == null || alternative.supportClause() == null
				|| !alternative.orderedInputs().equals(alternative.candidateRule().key().orderedInputs()))
				return null;
			return "CAPTURED|" + alternative.state().normalizedSignature() + "|rule="
				+ alternative.candidateRule().key().normalizedSignature() + "|emission="
				+ alternative.candidateEmission().selectionSignature() + "|realization="
				+ alternative.realization().key().normalizedSignature() + "|clause="
				+ alternative.supportClause().normalizedSignature() + "|foutMaterializationAction="
				+ (alternative.derivedFoutAction() == null ? "-"
					: alternative.derivedFoutAction().normalizedSignature())
				+ "|inputs=" + alternative.inputAuthorities().stream()
					.map(IndependentPhysicalSemanticDagOracle::authoritySignature).toList()
				+ compactSignature(alternative);
		}
		if(alternative.candidateRule() != null || alternative.candidateEmission() != null
			|| alternative.derivedFoutAction() != null
			|| !alternative.orderedInputs().equals(alternative.executionRule() == null ? List.of()
				: alternative.executionRule().key().orderedInputs()))
			return null;
		return alternative.authorityKind() + "|" + alternative.state().normalizedSignature()
			+ "|anchor=" + (alternative.durableAnchor() == null ? "-"
				: alternative.durableAnchor().normalizedSignature())
			+ "|action=" + (alternative.relocationAction() == null ? "-"
				: alternative.relocationAction().normalizedSignature())
			+ "|executionRule=" + (alternative.executionRule() == null ? "-"
				: alternative.executionRule().key().normalizedSignature())
			+ "|executionEmission=" + (alternative.executionEmission() == null ? "-"
				: alternative.executionEmission().selectionSignature())
			+ "|realization=" + (alternative.realization() == null ? "-"
				: alternative.realization().key().normalizedSignature())
			+ "|clause=" + (alternative.supportClause() == null ? "-"
				: alternative.supportClause().normalizedSignature())
			+ "|inputs=" + alternative.inputAuthorities().stream()
				.map(IndependentPhysicalSemanticDagOracle::authoritySignature).toList()
				+ compactSignature(alternative);
	}

	private static String compactSignature(Alternative alternative) {
		if(alternative.compactSupport() == null) return "";
		return "|compactSupport=" + alternative.compactSupport().axes().stream().map(axis ->
			axis.inputPosition() + ":" + axis.sourceOwner().normalizedSignature() + ":"
				+ axis.kind().name() + ":" + (axis.relocationAction() == null ? "-"
					: axis.relocationAction().normalizedSignature()) + ":options="
				+ axis.options().stream().map(option -> option.binding().normalizedSignature()).toList()).toList();
	}

	private static String authoritySignature(InputAuthority authority) {
		return authority.inputPosition() + ":" + authority.kind() + ':'
			+ (authority.expectedFType() == null ? "-" : authority.expectedFType()) + ':'
			+ (authority.sourceDecision() == null ? "-"
				: authority.sourceDecision().normalizedSignature()) + ':'
			+ (authority.relocationAction() == null ? "-"
				: authority.relocationAction().normalizedSignature());
	}

	private static void inputAuthority(Node node, InputAuthority authority) {
		node.integer("authorityPosition", authority.inputPosition());
		node.text("authorityKind", authority.kind().name());
		node.nullableText("authorityFType",
			authority.expectedFType() == null ? null : authority.expectedFType().name());
		node.nullableText("authoritySource", authority.sourceDecision() == null ? null
			: authority.sourceDecision().normalizedSignature());
		node.nullableText("authorityAction", authority.relocationAction() == null ? null
			: authority.relocationAction().normalizedSignature());
	}

	private static void texts(Node node, String field, List<String> values) {
		node.integer(field + "Count", values.size());
		values.forEach(value -> node.text(field, value));
	}

	private static byte[] digest(String type, Consumer<Node> fields) {
		Node node = node(type);
		fields.accept(node);
		return node.finish();
	}

	private static Node node(String type) { return new Node(type); }

	private static final class Node {
		private final MessageDigest digest;

		private Node(String type) {
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
				writeText(value);
			}
		}

		private void text(String name, String value) {
			name(name);
			writeText(value);
		}

		private void normalizedText(String name, NormalizedText value) {
			name(name);
			writeInt(value.length());
			value.appendTo(this::writeChars);
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
			for(int index = 0; index < value.length(); index++) {
				char unit = value.charAt(index);
				digest.update((byte)(unit >>> 8));
				digest.update((byte)unit);
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

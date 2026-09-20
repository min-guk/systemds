/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofProductTest {
	@Test
	public void eightToTheEighthRemainsAnUnmaterializedProduct() {
		List<List<CandidateRealizationInputBinding>> dimensions = new ArrayList<>();
		for(int position = 0; position < 8; position++) {
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < 8; option++)
				options.add(binding(position, "p" + position + "-o" + option));
			dimensions.add(options);
		}
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(anchor("seed"), anchor("output"), true, dimensions)));

		Assert.assertEquals(16_777_216L, product.checkedRawCardinality());
		Assert.assertEquals(64, product.bindingAtomCount());
		Assert.assertEquals(0, product.constructionMaterializedLeaves());
		Assert.assertEquals(1, product.routes().size());
	}

	@Test
	public void correlatedRoutesKeepTheirOwnExactAndDynamicMetadata() {
		CandidateRealizationInputBinding exactA = binding(0, "exact-a");
		CandidateRealizationInputBinding exactB = binding(0, "exact-b");
		CandidateRealizationInputBinding dynamicA = binding(0, "dynamic-a");
		CandidateRealizationInputBinding dynamicB = binding(0, "dynamic-b");
		DurableAnchorKey exactSeed = anchor("exact-seed");
		DurableAnchorKey exactOutput = anchor("exact-output");
		DurableAnchorKey dynamicSeed = anchor("dynamic-seed");
		DurableAnchorKey dynamicOutput = anchor("dynamic-output");
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(exactSeed, exactOutput, true,
				List.of(List.of(exactA, exactB))),
			NativeProofProduct.route(dynamicSeed, dynamicOutput, false,
				List.of(List.of(dynamicA, dynamicB)))));

		NativeProofProduct.LegacyExport export = product.exportLegacy();
		Assert.assertEquals(4, export.materializedLeaves());
		Assert.assertEquals(4, export.proofs().size());
		for(NativeContinuityProof proof : export.proofs()) {
			String source = proof.immediateBindings().get(0).source().realization().nativeLineage();
			if(source.startsWith("exact-")) {
				Assert.assertEquals(exactSeed, proof.externalSeed());
				Assert.assertEquals(exactOutput, proof.outputWorkerPoolWitness());
				Assert.assertTrue(proof.exactPartitionRanges());
			}
			else {
				Assert.assertTrue(source.startsWith("dynamic-"));
				Assert.assertEquals(dynamicSeed, proof.externalSeed());
				Assert.assertEquals(dynamicOutput, proof.outputWorkerPoolWitness());
				Assert.assertFalse(proof.exactPartitionRanges());
			}
		}
	}

	@Test
	public void structurallyDuplicateRoutesAreSuppressedWithoutCollapsingMetadata() {
		List<List<CandidateRealizationInputBinding>> descriptor =
			List.of(List.of(binding(0, "a"), binding(0, "b")));
		NativeProofProduct.Route first = NativeProofProduct.route(
			anchor("seed"), anchor("output"), true, descriptor);
		NativeProofProduct.Route same = NativeProofProduct.route(
			anchor("seed"), anchor("output"), true, descriptor);
		NativeProofProduct.Route dynamic = NativeProofProduct.route(
			anchor("seed"), anchor("output"), false, descriptor);
		NativeProofProduct product = NativeProofProduct.of(List.of(first, same, dynamic));

		Assert.assertEquals(2, product.routes().size());
		Assert.assertEquals(1, product.duplicateRoutesDropped());
		Assert.assertEquals(4, product.checkedRawCardinality());
		Assert.assertEquals(0, product.constructionMaterializedLeaves());
	}

	@Test
	public void rootRebindingTransformsAtomsWithoutExpandingLeaves() {
		CompiledHopKey occurrence = key("root");
		CompiledHopKey equalButNotIdenticalOccurrence = key("root");
		Assert.assertEquals(occurrence, equalButNotIdenticalOccurrence);
		Assert.assertNotSame(occurrence, equalButNotIdenticalOccurrence);
		CandidateRuleKey rule = new CandidateRuleKey(occurrence,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference cached = reference(rule, "cached");
		CandidateRealizationReference requested = reference(rule, "requested");
		CandidateRealizationReference identitySentinel = reference(new CandidateRuleKey(
			equalButNotIdenticalOccurrence, List.of(CandidateInputState.present(FType.ROW))), "cached");
		Assert.assertEquals(cached, identitySentinel);
		CandidateRealizationInputBinding cachedBinding =
			CandidateRealizationInputBinding.direct(0, cached);
		CandidateRealizationInputBinding sentinelBinding =
			CandidateRealizationInputBinding.direct(0, identitySentinel);
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(anchor("seed"), anchor("output"), true,
				List.of(List.of(cachedBinding))),
			NativeProofProduct.route(anchor("sentinel-seed"), anchor("sentinel-output"), true,
				List.of(List.of(sentinelBinding)))));

		NativeProofProduct rebound = product.rebindRoot(cached, requested);
		Assert.assertEquals(requested,
			rebound.routes().get(0).bindingOptions().get(0).get(0).source());
		CandidateRealizationReference retainedSentinel =
			rebound.routes().get(1).bindingOptions().get(0).get(0).source();
		Assert.assertEquals(identitySentinel, retainedSentinel);
		Assert.assertSame(equalButNotIdenticalOccurrence,
			retainedSentinel.rule().parentOccurrence());
		Assert.assertEquals(0, rebound.constructionMaterializedLeaves());
		Assert.assertEquals(2, rebound.checkedRawCardinality());
	}

	@Test
	public void zeroDimensionalRouteIsOneProofAndEmptyUnionIsNoProof() {
		NativeProofProduct emptyUnion = NativeProofProduct.of(List.of());
		Assert.assertEquals(0, emptyUnion.checkedRawCardinality());
		Assert.assertTrue(emptyUnion.routes().isEmpty());
		Assert.assertTrue(emptyUnion.exportLegacy().proofs().isEmpty());
		Assert.assertEquals(0, emptyUnion.exportLegacy().materializedLeaves());

		NativeProofProduct unit = NativeProofProduct.of(List.of(NativeProofProduct.route(
			anchor("seed"), anchor("output"), false, List.of())));
		Assert.assertEquals(1, unit.checkedRawCardinality());
		Assert.assertEquals(0, unit.bindingAtomCount());
		NativeProofProduct.LegacyExport export = unit.exportLegacy();
		Assert.assertEquals(1, export.materializedLeaves());
		Assert.assertEquals(1, export.proofs().size());
		Assert.assertTrue(export.proofs().get(0).immediateBindings().isEmpty());
		Assert.assertFalse(export.proofs().get(0).exactPartitionRanges());
	}

	@Test
	public void twoDimensionalCorrelatedOrNeverFormsCrossRouteLeaves() {
		CandidateRealizationInputBinding leftA = binding(0, "left-a");
		CandidateRealizationInputBinding rightA = binding(1, "right-a");
		CandidateRealizationInputBinding leftB = binding(0, "left-b");
		CandidateRealizationInputBinding rightB = binding(1, "right-b");
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(anchor("seed"), anchor("output"), true,
				List.of(List.of(leftA), List.of(rightA))),
			NativeProofProduct.route(anchor("seed"), anchor("output"), true,
				List.of(List.of(leftB), List.of(rightB)))));

		List<String> leaves = product.exportLegacy().proofs().stream().map(proof ->
			proof.immediateBindings().stream()
				.map(binding -> binding.source().realization().nativeLineage()).toList().toString()).toList();
		Assert.assertEquals(new LinkedHashSet<>(List.of(
			"[left-a, right-a]", "[left-b, right-b]")), new LinkedHashSet<>(leaves));
		Assert.assertFalse(leaves.contains("[left-a, right-b]"));
		Assert.assertFalse(leaves.contains("[left-b, right-a]"));
	}

	@Test
	public void atomFilterAndMapPreserveProductsAndDropIncompleteRoutes() {
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(anchor("seed-a"), anchor("output-a"), true,
				List.of(List.of(binding(0, "keep"), binding(0, "drop")),
					List.of(binding(1, "right")))),
			NativeProofProduct.route(anchor("seed-b"), anchor("output-b"), true,
				List.of(List.of(binding(0, "drop-only"))))));

		NativeProofProduct filtered = product.filterAtoms(binding ->
			!binding.source().realization().nativeLineage().startsWith("drop"));
		Assert.assertEquals(1, filtered.routes().size());
		Assert.assertEquals(1, filtered.checkedRawCardinality());
		NativeProofProduct mapped = filtered.mapAtoms(binding -> new CandidateRealizationInputBinding(
			binding.inputPosition(), reference("mapped-" + binding.inputPosition()),
			binding.kind(), binding.relocationAction()));
		Assert.assertEquals("mapped-0", mapped.routes().get(0).bindingOptions().get(0).get(0)
			.source().realization().nativeLineage());
		Assert.assertEquals("mapped-1", mapped.routes().get(0).bindingOptions().get(1).get(0)
			.source().realization().nativeLineage());
		Assert.assertEquals(0, mapped.constructionMaterializedLeaves());
	}

	@Test
	public void atomMapCollisionsAreDeduplicatedWithinTheDimension() {
		NativeProofProduct product = NativeProofProduct.of(List.of(NativeProofProduct.route(
			anchor("seed"), anchor("output"), true,
			List.of(List.of(binding(0, "first"), binding(0, "second"))))));
		CandidateRealizationInputBinding merged = binding(0, "merged");

		NativeProofProduct mapped = product.mapAtoms(ignored -> merged);
		Assert.assertEquals(1, mapped.bindingAtomCount());
		Assert.assertEquals(1, mapped.checkedRawCardinality());
		Assert.assertEquals(List.of(merged), mapped.routes().get(0).bindingOptions().get(0));
		Assert.assertEquals(1, mapped.exportLegacy().proofs().size());
	}

	@Test
	public void existentialAtomSelectionUsesDisjointFirstMatchRoutes() {
		CandidateRealizationInputBinding plainLeft = binding(0, "plain-left");
		CandidateRealizationInputBinding dynamicLeft = binding(0, "dynamic-left");
		CandidateRealizationInputBinding plainRight = binding(1, "plain-right");
		CandidateRealizationInputBinding dynamicRight = binding(1, "dynamic-right");
		NativeProofProduct product = NativeProofProduct.of(List.of(NativeProofProduct.route(
			anchor("seed"), anchor("output"), false,
			List.of(List.of(plainLeft, dynamicLeft), List.of(plainRight, dynamicRight)))));

		NativeProofProduct selected = product.selectAnyAtom(binding ->
			binding.source().realization().nativeLineage().startsWith("dynamic-"));
		Assert.assertEquals(2, selected.routes().size());
		Assert.assertEquals(3, selected.checkedRawCardinality());
		Assert.assertEquals(3, selected.exportLegacy().proofs().size());
		Assert.assertTrue(selected.exportLegacy().proofs().stream().allMatch(proof ->
			proof.immediateBindings().stream().anyMatch(binding ->
				binding.source().realization().nativeLineage().startsWith("dynamic-"))));
		Assert.assertEquals(0, selected.constructionMaterializedLeaves());
	}

	@Test
	public void existentialAtomSelectionRejectsZeroDimensionalAndNonmatchingRoutes() {
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(anchor("unit-seed"), anchor("unit-output"), false, List.of()),
			NativeProofProduct.route(anchor("plain-seed"), anchor("plain-output"), false,
				List.of(List.of(binding(0, "plain"))))));

		NativeProofProduct selected = product.selectAnyAtom(binding -> false);
		Assert.assertTrue(selected.routes().isEmpty());
		Assert.assertEquals(0, selected.checkedRawCardinality());
		Assert.assertEquals(0, selected.constructionMaterializedLeaves());
	}

	@Test
	public void legacyExportMatchesManualEnumerationSignatureForSignature() {
		DurableAnchorKey seed = anchor("seed");
		DurableAnchorKey output = anchor("output");
		CandidateRealizationInputBinding a = binding(0, "a");
		CandidateRealizationInputBinding b = binding(0, "b");
		CandidateRealizationInputBinding c = binding(1, "c");
		NativeProofProduct product = NativeProofProduct.of(List.of(
			NativeProofProduct.route(seed, output, true, List.of(List.of(a, b), List.of(c))),
			NativeProofProduct.route(seed, output, true, List.of(List.of(a), List.of(c)))));
		List<NativeContinuityProof> manual = new ArrayList<>();
		for(CandidateRealizationInputBinding left : List.of(a, b))
			manual.add(new NativeContinuityProof(seed, output, true, List.of(left, c)));
		manual.add(new NativeContinuityProof(seed, output, true, List.of(a, c)));
		manual = new LinkedHashSet<>(manual).stream()
			.sorted(Comparator.comparing(NativeContinuityProof::normalizedSignature)).toList();

		NativeProofProduct.LegacyExport export = product.exportLegacy();
		Assert.assertEquals(3, export.materializedLeaves());
		Assert.assertEquals(manual, export.proofs());
		Assert.assertEquals(manual.stream().map(NativeContinuityProof::normalizedSignature).toList(),
			export.proofs().stream().map(NativeContinuityProof::normalizedSignature).toList());
	}

	@Test
	public void cardinalityOverflowFailsClosed() {
		List<List<CandidateRealizationInputBinding>> dimensions = new ArrayList<>();
		for(int position = 0; position < 63; position++)
			dimensions.add(List.of(binding(position, "a-" + position),
				binding(position, "b-" + position)));
		Assert.assertThrows(ArithmeticException.class, () -> NativeProofProduct.route(
			anchor("seed"), anchor("output"), true, dimensions));
	}

	@Test
	public void cardinalitySumOverflowAcrossRoutesFailsClosed() {
		List<List<CandidateRealizationInputBinding>> dimensions = new ArrayList<>();
		for(int position = 0; position < 62; position++)
			dimensions.add(List.of(binding(position, "a-" + position),
				binding(position, "b-" + position)));
		NativeProofProduct.Route exact = NativeProofProduct.route(
			anchor("seed"), anchor("output"), true, dimensions);
		NativeProofProduct.Route dynamic = NativeProofProduct.route(
			anchor("seed"), anchor("output"), false, dimensions);
		Assert.assertEquals(1L << 62, exact.checkedCardinality());
		Assert.assertEquals(1L << 62, dynamic.checkedCardinality());
		Assert.assertThrows(ArithmeticException.class,
			() -> NativeProofProduct.of(List.of(exact, dynamic)));
	}

	private static CandidateRealizationInputBinding binding(int position, String id) {
		return CandidateRealizationInputBinding.direct(position, reference(id));
	}

	private static CandidateRealizationReference reference(String id) {
		return reference(new CandidateRuleKey(key("owner-" + id), List.of()), id);
	}

	private static CandidateRealizationReference reference(CandidateRuleKey rule, String lineage) {
		PlacementState state = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		return new CandidateRealizationReference(rule, PlacementRealizationKey.nativeLineage(
			new PlacementEmissionState(state, false), lineage));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("worker:8001", List.of(0L), List.of(9L))));
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"program", "main", List.of("root"), "call", "compile");
		return new CompiledHopKey("program", "main", "call", "compile", region,
			"hop-" + id, "source-" + id);
	}
}

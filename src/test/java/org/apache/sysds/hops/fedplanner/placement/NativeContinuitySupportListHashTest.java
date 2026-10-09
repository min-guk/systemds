/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
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

public class NativeContinuitySupportListHashTest {
	@Test
	public void randomizedCanonicalProductsMatchExplicitListHashWithoutHandles() {
		Random random = new Random(0x6e6174697665484cL);
		for(int iteration = 0; iteration < 80; iteration++) {
			CompiledHopKey owner = key("owner-" + iteration);
			DurableAnchorKey seed = pool("seed-" + iteration + (iteration % 3 == 0 ? "-🚀" : ""));
			DurableAnchorKey output = pool("output-" + iteration);
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
			int axisCount = 1 + random.nextInt(3);
			for(int axis = 0; axis < axisCount; axis++) {
				CompiledHopKey sourceOwner = key("source-" + iteration + '-' + axis);
				List<CandidateRealizationInputBinding> options = new ArrayList<>();
				for(int option = 0; option < 1 + random.nextInt(4); option++)
					options.add(direct(axis == 2 ? 10 : axis,
						"o" + option + "-λ" + "x".repeat(random.nextInt(24)), sourceOwner));
				options.sort(PlacementAnalysis.canonicalComparator());
				axes.add(List.copyOf(options));
			}
			var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, iteration % 2 == 0, axes);
			Assert.assertNotNull(product);
			boolean clauseExact = iteration % 3 != 0;
			DurableAnchorKey witness = clauseExact && iteration % 5 == 0 ? null : output;
			var measured = new NativeContinuitySupportClauses(
				owner, product, witness, clauseExact);
			var reference = new NativeContinuitySupportClauses(
				owner, product, witness, clauseExact);
			List<CandidateRealizationSupportClause> explicit = new ArrayList<>(reference);
			Assert.assertEquals(explicit.hashCode(), measured.hashCode());
			Assert.assertEquals("hashing must not request an exact member",
				0, measured.materializedHandleCount());
			Assert.assertEquals(explicit.hashCode(), measured.hashCode());
			Assert.assertEquals(0, measured.materializedHandleCount());
		}
	}

	@Test
	public void multiHeaderVariableLengthsMatchExplicitAndCrossRepresentationMapLookup() {
		CompiledHopKey owner = key("multi-owner");
		DurableAnchorKey output = pool("multi-output");
		CompiledHopKey left = key("left");
		CompiledHopKey right = key("right");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(0, "a", left), direct(0, "nine-xxxx", left),
				direct(0, "ten-xxxxxx", left)).stream()
				.sorted(PlacementAnalysis.canonicalComparator()).toList(),
			List.of(direct(10, "🚀", right), direct(10, "x".repeat(99), right),
				direct(10, "y".repeat(100), right)).stream()
				.sorted(PlacementAnalysis.canonicalComparator()).toList());
		var first = relation(owner, pool("seed-short"), output, true, axes);
		var second = relation(owner, pool("seed-with-a-much-longer-authority-λ"),
			output, true, axes);
		var measured = first.multiHeaderUnion(second).orElseThrow();
		var reference = relation(owner, pool("seed-short"), output, true, axes)
			.multiHeaderUnion(relation(owner, pool("seed-with-a-much-longer-authority-λ"),
				output, true, axes)).orElseThrow();
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>(reference);

		Assert.assertEquals(explicit.hashCode(), measured.hashCode());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals(measured, explicit);
		Assert.assertEquals(explicit, measured);
		Map<List<CandidateRealizationSupportClause>,String> map = new HashMap<>();
		map.put(measured, "native");
		Assert.assertEquals("native", map.get(explicit));
	}

	@Test
	public void donorUnionRestrictionPreservesExactListHashWithoutMaterializingDonors() {
		CompiledHopKey owner = key("donor-owner");
		CompiledHopKey source = key("donor-source");
		DurableAnchorKey seed = pool("donor-seed");
		DurableAnchorKey output = pool("donor-output");
		var a = direct(0, "a", source);
		var b = direct(0, "bbbb", source);
		var c = direct(0, "cccccccccccc", source);
		var left = relation(owner, seed, output, true, List.of(List.of(a, b)));
		var right = relation(owner, seed, output, true, List.of(List.of(b, c)));
		var union = left.oneAxisUnion(right).orElseThrow();
		var retained = union.restrictBindings(binding -> binding != b).orElseThrow();
		var fresh = relation(owner, seed, output, true, List.of(List.of(b)));
		var readded = retained.oneAxisUnion(fresh).orElseThrow();
		var referenceLeft = relation(owner, seed, output, true, List.of(List.of(a, b)));
		var referenceRight = relation(owner, seed, output, true, List.of(List.of(b, c)));
		var referenceRetained = referenceLeft.oneAxisUnion(referenceRight).orElseThrow()
			.restrictBindings(binding -> binding != b).orElseThrow();
		var reference = referenceRetained.oneAxisUnion(
			relation(owner, seed, output, true, List.of(List.of(b)))).orElseThrow();
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>(reference);

		Assert.assertEquals(explicit.hashCode(), readded.hashCode());
		Assert.assertEquals(0, readded.materializedHandleCount());
		Assert.assertEquals(0, retained.materializedHandleCount());
		Assert.assertEquals(0, fresh.materializedHandleCount());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, right.materializedHandleCount());
	}

	@Test
	public void millionMemberProductHashUsesOnlyFactorMetadata() {
		CompiledHopKey owner = key("large-owner");
		DurableAnchorKey output = pool("large-output");
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int axis = 0; axis < 3; axis++) {
			CompiledHopKey source = key("large-source-" + axis);
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < 100; option++)
				options.add(direct(axis, String.format("option-%03d", option), source));
			options.sort(PlacementAnalysis.canonicalComparator());
			axes.add(List.copyOf(options));
		}
		var relation = relation(owner, pool("large-seed"), output, true, axes);
		Assert.assertEquals(1_000_000, relation.size());
		int first = relation.hashCode();
		Assert.assertEquals(0, relation.materializedHandleCount());
		Assert.assertEquals(first, relation.hashCode());
		Assert.assertEquals(0, relation.materializedHandleCount());
	}

	@Test
	public void sparseAttainableLengthStatesMatchExplicitWithoutInvalidSuffixExpansion() {
		CompiledHopKey owner = key("sparse-length-owner");
		DurableAnchorKey output = pool("sparse-length-output");
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int axis = 0; axis < 4; axis++) {
			CompiledHopKey source = key("sparse-length-source-" + axis);
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < 8; option++) {
				String ordinal = Integer.toString(option);
				int width = option < 4 ? 12 : 120;
				options.add(direct(axis, ordinal + "x".repeat(width - ordinal.length()), source));
			}
			options.sort(PlacementAnalysis.canonicalComparator());
			axes.add(List.copyOf(options));
		}
		var measured = relation(owner, pool("sparse-length-seed"), output, true, axes);
		var reference = relation(owner, pool("sparse-length-seed"), output, true, axes);
		Assert.assertEquals(4096, measured.size());
		Assert.assertEquals(new ArrayList<>(reference).hashCode(), measured.hashCode());
		Assert.assertEquals(0, measured.materializedHandleCount());
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output, boolean proofExact,
		List<List<CandidateRealizationInputBinding>> axes) {
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, proofExact, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
	}

	private static CandidateRealizationInputBinding direct(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), "source-" + name));
		return CandidateRealizationInputBinding.direct(position, reference);
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"native-list-hash", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-list-hash", "main", name, "compiled",
			region, name, name);
	}
}

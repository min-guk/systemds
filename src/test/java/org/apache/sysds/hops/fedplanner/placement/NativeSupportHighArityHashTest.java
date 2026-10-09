/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

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

public class NativeSupportHighArityHashTest {
	@Test
	public void singletonAxesHashIterativelyAtHighArity() {
		for(int arity : new int[] {4096, 8192})
			assertHighArityHash(arity);
	}

	@Test
	public void deepSparseLengthIndexHashIterativelyAtHighArity() {
		int arity = 4096;
		CompiledHopKey owner = key("sparse-owner");
		DurableAnchorKey seed = pool("sparse-seed");
		DurableAnchorKey output = pool("sparse-output");
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>(arity);
		for(int axis = 0; axis < arity - 1; axis++) {
			CompiledHopKey sourceOwner = key("sparse-source-" + axis);
			axes.add(List.of(direct(axis, "option-" + axis, sourceOwner)));
		}
		CompiledHopKey finalOwner = key("sparse-source-final");
		axes.add(List.of(
			direct(arity - 1, "a", finalOwner),
			direct(arity - 1, "long-option-" + "x".repeat(96), finalOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList());

		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		Assert.assertNotNull("the sparse two-length product must fit existing index budgets", product);
		var measured = new NativeContinuitySupportClauses(owner, product, output, true);
		var reference = new NativeContinuitySupportClauses(owner, product, output, true);
		Assert.assertEquals(2, measured.size());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals(0, reference.materializedHandleCount());

		List<CandidateRealizationSupportClause> explicit = new ArrayList<>(reference);
		Assert.assertEquals(2, reference.materializedHandleCount());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals(explicit.hashCode(), measured.hashCode());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals(explicit.hashCode(), measured.hashCode());
		Assert.assertEquals(0, measured.materializedHandleCount());
	}

	private static void assertHighArityHash(int arity) {
		CompiledHopKey owner = key("owner-" + arity);
		DurableAnchorKey seed = pool("seed-" + arity);
		DurableAnchorKey output = pool("output-" + arity);
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>(arity);
		for(int axis = 0; axis < arity; axis++) {
			CompiledHopKey sourceOwner = key("source-" + axis);
			axes.add(List.of(direct(axis, "option-" + axis, sourceOwner)));
		}
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		Assert.assertNotNull("the ordinary product factory must admit " + arity
			+ " singleton axes", product);
		var measured = new NativeContinuitySupportClauses(owner, product, output, true);
		var reference = new NativeContinuitySupportClauses(owner, product, output, true);
		Assert.assertEquals(1, measured.size());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals(0, reference.materializedHandleCount());

		List<CandidateRealizationSupportClause> explicit = new ArrayList<>(reference);
		Assert.assertEquals("only the independent explicit reference should materialize",
			1, reference.materializedHandleCount());
		Assert.assertEquals(0, measured.materializedHandleCount());
		Assert.assertEquals("high-arity algebraic hashing must preserve standard List hash",
			explicit.hashCode(), measured.hashCode());
		Assert.assertEquals("algebraic hashing must remain lazy", 0,
			measured.materializedHandleCount());
		Assert.assertEquals(explicit.hashCode(), measured.hashCode());
		Assert.assertEquals(0, measured.materializedHandleCount());
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
			"native-high-arity-hash", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-high-arity-hash", "main", name, "compiled",
			region, name, name);
	}
}

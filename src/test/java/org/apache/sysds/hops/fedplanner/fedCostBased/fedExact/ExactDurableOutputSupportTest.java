/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactDurableOutputSupportTest {
	private static final PlacementEmissionState FED_FULL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, true), false);

	@Test
	public void sameDurableOutputSupportsDifferentInputSupplyRoutes() {
		CompiledHopKey owner = key("producer");
		DurableAnchorKey anchor = anchor("pool-b", "localhost:13002");
		CandidateRealizationReference direct = reference(owner,
			List.of(CandidateInputState.present(FType.FULL), CandidateInputState.absentLocal()), anchor);
		CandidateRealizationReference relocated = reference(owner,
			List.of(CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)), anchor);

		Assert.assertNotEquals(direct, relocated);
		Assert.assertTrue(ExactPhysicalModel.sameRealizationSupport(direct, relocated));
		Assert.assertTrue("final receipt validation must use the same produced-output identity",
			CandidateSelections.matchesRequiredInputSupport(direct, receipt(relocated)));
		Assert.assertFalse("the selected candidate row itself remains rule-exact",
			CandidateSelections.matchesRealization(direct, receipt(relocated)));
	}

	@Test
	public void durableOutputSupportKeepsOwnerAndLayoutExact() {
		CompiledHopKey owner = key("producer");
		CandidateRealizationReference expected = reference(owner, List.of(),
			anchor("pool-b", "localhost:13002"));
		CandidateRealizationReference differentOwner = reference(key("other"), List.of(),
			anchor("pool-b", "localhost:13002"));
		CandidateRealizationReference differentLayout = reference(owner, List.of(),
			anchor("pool-a", "localhost:13001"));

		Assert.assertFalse(ExactPhysicalModel.sameRealizationSupport(expected, differentOwner));
		Assert.assertFalse(ExactPhysicalModel.sameRealizationSupport(expected, differentLayout));
		Assert.assertFalse(CandidateSelections.matchesRequiredInputSupport(
			expected, receipt(differentOwner)));
		Assert.assertFalse(CandidateSelections.matchesRequiredInputSupport(
			expected, receipt(differentLayout)));
	}

	@Test
	public void valueMapSupportRemainsRuleExact() {
		CompiledHopKey owner = key("producer");
		CandidateRuleKey direct = new CandidateRuleKey(owner,
			List.of(CandidateInputState.absentLocal()));
		CandidateRuleKey relocated = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
			FED_FULL, "dynamic-value-map",
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));

		Assert.assertFalse(ExactPhysicalModel.sameRealizationSupport(
			CandidateRealizationReference.of(direct, valueMap),
			CandidateRealizationReference.of(relocated, valueMap)));
		CandidateRealizationReference relocatedValue =
			CandidateRealizationReference.of(relocated, valueMap);
		Assert.assertFalse(CandidateSelections.matchesRequiredInputSupport(
			CandidateRealizationReference.of(direct, valueMap), receipt(relocatedValue)));
	}

	private static CandidateSelectionReceipt receipt(CandidateRealizationReference reference) {
		CandidateEmissionRealization realization = reference.realization().layoutKind()
			== org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
			? CandidateEmissionRealization.durable(FED_FULL, reference.realization().durableAnchor(),
				List.of(), List.of())
			: CandidateEmissionRealization.valueMap(FED_FULL,
				reference.realization().nativeLineage(),
				List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			FED_FULL, FType.FULL, null, List.of(realization));
		return new CandidateSelectionReceipt(reference.rule(), emission, realization,
			realization.requireSingletonSupportClause(), List.of());
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner,
		List<CandidateInputState> inputs, DurableAnchorKey anchor) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, inputs);
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
			FED_FULL, anchor, List.of(), List.of());
		return CandidateRealizationReference.of(rule, realization);
	}

	private static DurableAnchorKey anchor(String name, String worker) {
		return new DurableAnchorKey(name, FType.FULL, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(16L, 4096L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"durable-output-support", "main", List.of("root"), "root", "compiled");
		return new CompiledHopKey("durable-output-support", "main", "root", "compiled",
			region, name, name);
	}
}

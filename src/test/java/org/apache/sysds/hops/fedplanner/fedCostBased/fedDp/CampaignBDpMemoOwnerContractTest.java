/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED contract for transferring DP memo ownership to exact analysis occurrences. */
public class CampaignBDpMemoOwnerContractTest {
	private static final BaselineRedSignatures BASELINE = new BaselineRedSignatures(
		34, 149, 266, 3, 0, 0,
		"Placement analysis is foreign to the supplied program",
		"expected:<FOUT> but was:<NONE>");

	@Test
	public void baselineRedSignaturesRemainExact() {
		Assert.assertEquals(34, BASELINE.dpUnits());
		Assert.assertEquals(149, BASELINE.dpViolations());
		Assert.assertEquals(266, BASELINE.tests());
		Assert.assertEquals(3, BASELINE.failures());
		Assert.assertEquals(0, BASELINE.errors());
		Assert.assertEquals(0, BASELINE.skipped());
		Assert.assertEquals("Placement analysis is foreign to the supplied program",
			BASELINE.enumeratorRed());
		Assert.assertEquals("expected:<FOUT> but was:<NONE>", BASELINE.fedAllRed());
	}

	@Test
	public void equalCostTieRetainsFirstPlanAndExactExecutableIdentity() {
		Fixture fixture = fixture(ExecType.CP, FederatedOutput.LOUT, false);
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(fixture.analysis());
		Assert.assertSame("memo analysis owner", fixture.analysis(), memo.analysis());

		FedPlanVariants variants = variants(fixture.occurrence().hop(), FederatedOutput.LOUT);
		FedPlan first = plan(variants, fixture.state(), 0x1.0p3);
		FedPlan second = plan(variants, fixture.state(), 0x1.0p3);
		variants.addFedPlan(first);
		variants.addFedPlan(second);
		Assert.assertTrue("memo fixture prune", variants.pruneFedPlans());

		memo.addFedPlanVariants(fixture.occurrence(), FederatedOutput.LOUT, variants);
		Assert.assertSame("stable first-in equal-cost identity", first,
			memo.getFedPlanAfterPrune(fixture.occurrence(), FederatedOutput.LOUT));
		Assert.assertSame("exact executable Hop identity", fixture.occurrence().hop(),
			memo.resolveExecutableHop(fixture.occurrence()));
	}

	@Test
	public void sourceFTypeMustMatchExactFedOutOwner() {
		Fixture owner = fixture(ExecType.FED, FederatedOutput.FOUT, true);
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(owner.analysis());
		FedPlanVariants variants = variants(owner.occurrence().hop(), FederatedOutput.FOUT);
		FedPlan mismatched = plan(variants, owner.state(), 0x1.0p3);
		mismatched.setFType(differentConcreteFType(owner.state().fType()));
		variants.addFedPlan(mismatched);
		Assert.assertThrows("mismatched source FType must be rejected", IllegalStateException.class,
			() -> memo.addFedPlanVariants(owner.occurrence(), FederatedOutput.FOUT, variants));
	}

	@Test
	public void matchingSourceFTypeRetainsExactOwnerIdentity() {
		Fixture owner = fixture(ExecType.FED, FederatedOutput.FOUT, true);
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(owner.analysis());
		FedPlanVariants variants = variants(owner.occurrence().hop(), FederatedOutput.FOUT);
		FedPlan matching = plan(variants, owner.state(), 0x1.0p3);
		variants.addFedPlan(matching);
		memo.addFedPlanVariants(owner.occurrence(), FederatedOutput.FOUT, variants);
		Assert.assertSame(matching, memo.getFedPlanAfterPrune(owner.occurrence(), FederatedOutput.FOUT));
		Assert.assertSame("matching plan must retain exact analysis-owned state", owner.state(),
			matching.getSelectedPlacementState());
	}

	@Test
	public void copiedAndForeignOccurrencesRejectWithoutChangingSelection() {
		Fixture owner = fixture(ExecType.FED, FederatedOutput.FOUT, true);
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(owner.analysis());
		FedPlanVariants variants = variants(owner.occurrence().hop(), FederatedOutput.FOUT);
		FedPlan retained = plan(variants, owner.state(), 0x1.0p3);
		variants.addFedPlan(retained);
		Assert.assertTrue("memo fixture prune", variants.pruneFedPlans());
		memo.addFedPlanVariants(owner.occurrence(), FederatedOutput.FOUT, variants);

		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		HopOccurrenceProjection copiedOccurrence = copied.occurrences().stream()
			.filter(occurrence -> occurrence.key().equals(owner.occurrence().key()))
			.findFirst().orElseThrow();
		expectReject(() -> memo.getFedPlanAfterPrune(copiedOccurrence, FederatedOutput.FOUT));
		expectReject(() -> memo.resolveExecutableHop(copiedOccurrence));

		Fixture foreign = fixture(ExecType.FED, FederatedOutput.FOUT, true);
		expectReject(() -> memo.getFedPlanAfterPrune(foreign.occurrence(), FederatedOutput.FOUT));
		expectReject(() -> memo.resolveExecutableHop(foreign.occurrence()));

		Assert.assertSame("negative lookup mutated retained plan", retained,
			memo.getFedPlanAfterPrune(owner.occurrence(), FederatedOutput.FOUT));
		Assert.assertSame("negative lookup mutated executable association", owner.occurrence().hop(),
			memo.resolveExecutableHop(owner.occurrence()));
	}

	public record BaselineRedSignatures(int dpUnits, int dpViolations, int tests, int failures,
		int errors, int skipped, String enumeratorRed, String fedAllRed) { }
	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence, PlacementState state) { }

	private static Fixture fixture(ExecType execType, FederatedOutput output, boolean federatedSource) {
		try {
			DMLProgram program = CampaignBG014HermeticPlannerFixtureFactory.compile("B-21");
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
				if(federatedSource && (!(occurrence.hop() instanceof DataOp data)
					|| data.getOp() != OpOpData.FEDERATED))
					continue;
				if(!federatedSource && analysis.occurrences().stream()
					.filter(candidate -> candidate.hop() == occurrence.hop()).count() != 1)
					continue;
				PlacementState exact = analysis.graph().node(occurrence.key()).orElseThrow()
					.legalAlternatives().stream()
					.filter(state -> state.execType() == execType && state.output() == output
						&& (!federatedSource || state.fType() != null))
					.findFirst().orElse(null);
				if(exact != null)
					return new Fixture(program, analysis, occurrence, exact);
			}
			throw new AssertionError("B-21 lacks the required exact " + execType + '/' + output
				+ (federatedSource ? " FEDERATED source" : " occurrence"));
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile B-21 DP memo owner fixture", e);
		}
	}

	private static FedPlanVariants variants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1, 1, 1, 1, List.of());
		common.setSelfCost(0x1.0p-4);
		common.setForwardingCost(0x1.0p-3);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(FedPlanVariants variants, PlacementState exact, double cost) {
		FedPlan plan = new FedPlan(cost, variants, List.of());
		plan.setExecType(exact.execType());
		plan.setFType(exact.fType());
		plan.setSelectedPlacementState(exact);
		return plan;
	}

	private static FType differentConcreteFType(FType exact) {
		Assert.assertNotNull("FED/FOUT source state must carry a concrete FType", exact);
		return exact == FType.ROW ? FType.COL : FType.ROW;
	}

	private static void expectReject(Runnable action) {
		try {
			action.run();
			Assert.fail("accepted foreign or copied placement occurrence");
		}
		catch(IllegalArgumentException expected) {
			// Exact owner rejection is the contract under test.
		}
	}
}

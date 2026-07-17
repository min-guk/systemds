/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for transferring DP memo ownership to exact placement-analysis occurrences. */
public class CampaignBDpMemoOwnerContractTest {
	private static final String MISSING_API = "CAMPAIGN_B_DP_MEMO_TYPED_OWNER_API_MISSING";

	@Test
	public void memoOwnerApiIsAnalysisBoundAndOccurrenceTyped() throws Exception {
		Api api = api();
		Assert.assertEquals(PlacementAnalysis.class, api.analysis().getReturnType());
		Assert.assertEquals(void.class, api.add().getReturnType());
		Assert.assertEquals(FedPlan.class, api.get().getReturnType());
		Assert.assertEquals(Hop.class, api.executable().getReturnType());
	}

	@Test
	public void equalCostTieRetainsFirstPlanAndExactExecutableIdentity() throws Exception {
		Api api = api();
		Fixture fixture = fixture("B-01");
		Object memo = api.constructor().newInstance(fixture.analysis());
		Assert.assertSame("memo analysis owner", fixture.analysis(), invoke(api.analysis(), memo));

		FedPlanVariants variants = variants(fixture.occurrence().hop(), FederatedOutput.LOUT);
		FedPlan first = plan(variants, ExecType.CP, 0x1.0p3);
		FedPlan second = plan(variants, ExecType.FED, 0x1.0p3);
		variants.addFedPlan(first);
		variants.addFedPlan(second);
		Assert.assertTrue("memo fixture prune", variants.pruneFedPlans());

		invoke(api.add(), memo, fixture.occurrence(), FederatedOutput.LOUT, variants);
		Assert.assertSame("stable first-in equal-cost identity", first,
			invoke(api.get(), memo, fixture.occurrence(), FederatedOutput.LOUT));
		Assert.assertSame("exact executable Hop identity", fixture.occurrence().hop(),
			invoke(api.executable(), memo, fixture.occurrence()));
	}

	@Test
	public void copiedAndForeignOccurrencesRejectWithoutChangingSelection() throws Exception {
		Api api = api();
		Fixture owner = fixture("B-01");
		Object memo = api.constructor().newInstance(owner.analysis());
		FedPlanVariants variants = variants(owner.occurrence().hop(), FederatedOutput.FOUT);
		FedPlan retained = plan(variants, ExecType.FED, 0x1.0p3);
		variants.addFedPlan(retained);
		Assert.assertTrue("memo fixture prune", variants.pruneFedPlans());
		invoke(api.add(), memo, owner.occurrence(), FederatedOutput.FOUT, variants);

		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		HopOccurrenceProjection copiedOccurrence = copied.occurrences().stream()
			.filter(occurrence -> occurrence.key().equals(owner.occurrence().key()))
			.findFirst().orElseThrow();
		expectReject(api.get(), memo, copiedOccurrence, FederatedOutput.FOUT);
		expectReject(api.executable(), memo, copiedOccurrence);

		Fixture foreign = fixture("B-02");
		expectReject(api.get(), memo, foreign.occurrence(), FederatedOutput.FOUT);
		expectReject(api.executable(), memo, foreign.occurrence());

		Assert.assertSame("negative lookup mutated retained plan", retained,
			invoke(api.get(), memo, owner.occurrence(), FederatedOutput.FOUT));
		Assert.assertSame("negative lookup mutated executable association", owner.occurrence().hop(),
			invoke(api.executable(), memo, owner.occurrence()));
	}

	private record Api(Constructor<FederatedPlannerDpMemoTable> constructor, Method analysis,
		Method add, Method get, Method executable) { }
	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence) { }

	private static Api api() {
		try {
			Constructor<FederatedPlannerDpMemoTable> constructor =
				FederatedPlannerDpMemoTable.class.getConstructor(PlacementAnalysis.class);
			Method analysis = FederatedPlannerDpMemoTable.class.getMethod("analysis");
			Method add = FederatedPlannerDpMemoTable.class.getMethod("addFedPlanVariants",
				HopOccurrenceProjection.class, FederatedOutput.class, FedPlanVariants.class);
			Method get = FederatedPlannerDpMemoTable.class.getMethod("getFedPlanAfterPrune",
				HopOccurrenceProjection.class, FederatedOutput.class);
			Method executable = FederatedPlannerDpMemoTable.class.getMethod("resolveExecutableHop",
				HopOccurrenceProjection.class);
			return new Api(constructor, analysis, add, get, executable);
		}
		catch(NoSuchMethodException e) {
			throw new AssertionError(MISSING_API + '|' + e.getMessage(), e);
		}
	}

	private static Fixture fixture(String id) {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		return new Fixture(program, analysis, analysis.occurrences().get(0));
	}

	private static FedPlanVariants variants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1, 1, 1, 1, List.of());
		common.setSelfCost(0x1.0p-4);
		common.setForwardingCost(0x1.0p-3);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(FedPlanVariants variants, ExecType execType, double cost) {
		FedPlan plan = new FedPlan(cost, variants, List.of());
		plan.setExecType(execType);
		plan.setFType(FType.ROW);
		return plan;
	}

	private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
		try {
			return method.invoke(receiver, args);
		}
		catch(InvocationTargetException e) {
			Throwable cause = e.getCause();
			if(cause instanceof Exception)
				throw (Exception) cause;
			if(cause instanceof Error)
				throw (Error) cause;
			throw e;
		}
	}

	private static void expectReject(Method method, Object receiver, Object... args) throws Exception {
		try {
			invoke(method, receiver, args);
			Assert.fail("accepted foreign or copied placement occurrence");
		}
		catch(IllegalArgumentException expected) {
			// Exact owner rejection is the contract under test.
		}
	}
}

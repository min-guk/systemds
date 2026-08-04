/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.recompile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for the exact derived-FOUT bit used by conflict resolution and runtime recompilation. */
public class CampaignBG014DerivedFoutRecompileStateRedTest {
	@Test
	public void plannerRecompileStatePreservesDerivedFoutAuthority() throws Exception {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		try {
			Hop input = new DataOp("X", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
			Hop producer = new UnaryOp("derived", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, input);
			producer.setBeginLine(59);
			producer.setBeginColumn(2);
			producer.setEndLine(59);
			producer.setEndColumn(20);
			producer.setExecType(ExecType.FED);
			producer.setForcedExecType(ExecType.FED);
			producer.setFederatedOutput(FederatedOutput.FOUT);
			producer.setFederatedOutputDerived(true);

			FederatedPlannerUtils.registerPlannerRecompileState(
				producer, ExecType.FED, FederatedOutput.FOUT);
			String signature = FederatedPlannerUtils.plannerRecompileSignature(producer);
			Object published = FederatedPlannerUtils.snapshotPlannerRecompileStates().get(signature);
			Assert.assertNotNull("The exact planner state must be published", published);
			Method derivedAccessor = published.getClass().getMethod("federatedOutputDerived");
			Assert.assertTrue("The published state must retain the derived-FOUT authority bit",
				(Boolean) derivedAccessor.invoke(published));

			Method snapshot = Recompiler.class.getDeclaredMethod("snapshotHopStates", List.class);
			snapshot.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Long, ?> baseStates = (Map<Long, ?>) snapshot.invoke(null, List.of(producer));

			producer.setExecType(ExecType.CP);
			producer.setForcedExecType(ExecType.CP);
			producer.setFederatedOutput(FederatedOutput.LOUT);
			Assert.assertFalse(producer.isFederatedOutputDerived());

			Method restore = Recompiler.class.getDeclaredMethod(
				"restoreHopStates", List.class, Map.class, Map.class);
			restore.setAccessible(true);
			restore.invoke(null, List.of(producer), baseStates, null);

			Assert.assertEquals(ExecType.FED, producer.getForcedExecType());
			Assert.assertEquals(FederatedOutput.FOUT, producer.getFederatedOutput());
			Assert.assertTrue("Runtime recompile must restore derived FOUT, not plain FED/FOUT",
				producer.isFederatedOutputDerived());
		}
		finally {
			FederatedPlannerUtils.clearPlannerRecompileStates();
		}
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.lops.Checkpoint;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.ReBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class PlannerForcedFederatedPlatformTest {
	@Test
	public void globalSparkModePreservesPlannerSelectedFederatedExecution() {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.SPARK);
			BinaryOp selected = plus();
			selected.setExecType(ExecType.FED);
			selected.setForcedExecType(ExecType.FED);
			selected.setPlannerPlacementSelected(true);

			selected.checkAndSetForcedPlatform();

			assertEquals(ExecType.FED, selected.getForcedExecType());
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	@Test
	public void globalSparkModePreservesPlannerSelectedCoordinatorExecution() {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.SPARK);
			BinaryOp selected = plus();
			selected.setExecType(ExecType.CP);
			selected.setForcedExecType(ExecType.CP);
			selected.setPlannerPlacementSelected(true);

			selected.checkAndSetForcedPlatform();

			assertEquals(ExecType.CP, selected.getForcedExecType());
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	@Test
	public void globalSparkModeStillForcesOrdinaryExecutionToSpark() {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.SPARK);
			BinaryOp ordinary = plus();
			ordinary.setForcedExecType(ExecType.CP);

			ordinary.checkAndSetForcedPlatform();

			assertEquals(ExecType.SPARK, ordinary.getForcedExecType());
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	@Test
	public void sparkCheckpointIsNotAppendedToFederationMapBackedPlannerValue() {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.SPARK);
			BinaryOp selected = plus();
			selected.setExecType(ExecType.FED);
			selected.setForcedExecType(ExecType.FED);
			selected.setFederatedOutput(FederatedOutput.FOUT);
			selected.setPlannerPlacementSelected(true);
			selected.setRequiresReblock(true);
			selected.setRequiresCheckpoint(true);
			Lop federatedValue = transientMatrix("fed-value", ExecType.INVALID,
				FederatedOutput.FOUT);
			selected.setLops(federatedValue);

			selected.constructAndSetLopsDataFlowProperties();

			assertTrue("the selected FOUT value retains its required federated reblock",
				selected.getLops() instanceof ReBlock);
			assertSame(federatedValue, selected.getLops().getInput(0));
			assertEquals(ExecType.FED, selected.getLops().getExecType());
			assertEquals(FederatedOutput.FOUT, selected.getLops().getFederatedOutput());
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	@Test
	public void ordinarySparkValueStillReceivesRequestedCheckpoint() {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.SPARK);
			BinaryOp ordinary = plus();
			ordinary.setRequiresCheckpoint(true);
			ordinary.setLops(transientMatrix("spark-value", ExecType.SPARK,
				FederatedOutput.NONE));

			ordinary.constructAndSetLopsDataFlowProperties();

			assertTrue("ordinary RDD-backed values retain Spark checkpointing",
				ordinary.getLops() instanceof Checkpoint);
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	private static Data transientMatrix(String name, ExecType execType,
		FederatedOutput output) {
		Data lop = new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		lop.setExecType(execType);
		lop.setFederatedOutput(output);
		return lop;
	}

	private static BinaryOp plus() {
		DataOp left = new DataOp("left", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "left", 10, 10, 100, 1000);
		DataOp right = new DataOp("right", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "right", 10, 10, 100, 1000);
		return new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, left, right);
	}
}

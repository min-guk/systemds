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

package org.apache.sysds.test.functions.federated.fedplanning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.fed.FEDRefedInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class FederatedRefedPolicyTest {
	private static final int BLOCKSIZE = 1000;

	@Test
	public void testSingleFedParentAnchorRegistration() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 10);
		BinaryOp parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);
		Map<Long, Long> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertTrue("Expected refed registry entry for target hop", snapshot.containsKey(target.getHopID()));
		assertEquals("Anchor hop mismatch in registry", anchor.getHopID(), snapshot.get(target.getHopID()).longValue());
	}

	@Test
	public void testAnchorMismatchThrows() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor1 = createFederatedInput("A1", 10, 10);
		DataOp anchor2 = createFederatedInput("A2", 10, 10);
		BinaryOp parent1 = HopRewriteUtils.createBinary(target, anchor1, OpOp2.PLUS);
		BinaryOp parent2 = HopRewriteUtils.createBinary(target, anchor2, OpOp2.PLUS);
		parent1.setForcedExecType(ExecType.FED);
		parent2.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor1.getHopID(), FType.ROW);
		fTypeMap.put(anchor2.getHopID(), FType.ROW);

		assertThrows(DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFromHops(Arrays.asList(parent1, parent2), true, fTypeMap, -1));
	}

	@Test
	public void testFederatedInputOnTargetAllowed() {
		DataOp fedInput = createFederatedInput("F", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(fedInput, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 10);
		BinaryOp parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedInput.getHopID(), FType.ROW);
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);
		Map<Long, Long> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertEquals("Anchor hop mismatch for federated target input", anchor.getHopID(),
			snapshot.get(target.getHopID()).longValue());

		Lop parentLop = parent.constructLops();
		Dag<Lop> dag = new Dag<>();
		parentLop.addToDag(dag);
		boolean hasRefedInstruction = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			if (inst instanceof FEDRefedInstruction || inst.getInstructionString().contains("fed_refed")) {
				hasRefedInstruction = true;
				break;
			}
		}
		assertTrue("Expected fed_refed instruction for federated target input", hasRefedInstruction);
	}

	@Test
	public void testPartAnchorThrows() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 10);
		BinaryOp parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.PART);

		assertThrows(DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1));
	}

	@Test
	public void testBroadcastParentIgnored() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 10);
		DataOp broadcast = createFederatedInput("B", 10, 10);
		BinaryOp parent1 = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		BinaryOp parent2 = HopRewriteUtils.createBinary(target, broadcast, OpOp2.PLUS);
		parent1.setForcedExecType(ExecType.FED);
		parent2.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);
		fTypeMap.put(broadcast.getHopID(), FType.BROADCAST);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent1, parent2), true, fTypeMap, -1);
		Map<Long, Long> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertEquals("Anchor hop mismatch when broadcast input is present", anchor.getHopID(),
			snapshot.get(target.getHopID()).longValue());
	}

	@Test
	public void testDimMismatchMaterialize() {
		DataOp localLhs = createLocalMatrix("L", 10, 5);
		DataOp localRhs = createLocalMatrix("R", 10, 5);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(5);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 4);
		BinaryOp parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);

		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> snapshot =
			FederatedFoutMaterializeRegistry.snapshot(-1);
		assertTrue("Expected materialize registry entry for target hop", snapshot.containsKey(target.getHopID()));
		FederatedFoutMaterializeRegistry.MaterializeSpec spec = snapshot.get(target.getHopID());
		assertEquals("Anchor hop mismatch for materialize registry", anchor.getHopID(), spec.getAnchorHopId());
		assertEquals("FType hint mismatch for materialize registry", "ROW", spec.getFTypeHint());
		assertTrue("Expected refed registry to be empty for target hop",
			!FederatedRefedRegistry.snapshot(-1).containsKey(target.getHopID()));

		Lop parentLop = parent.constructLops();
		Dag<Lop> dag = new Dag<>();
		parentLop.addToDag(dag);
		boolean hasFoutInstruction = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			if (inst.getInstructionString().contains("fed_fout")) {
				hasFoutInstruction = true;
				break;
			}
		}
		assertTrue("Expected fed_fout instruction for dim mismatch materialize", hasFoutInstruction);
	}

	@Test
	public void testMixedParentRewire() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp anchor = createFederatedInput("A", 10, 10);
		BinaryOp fedParent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		fedParent.setForcedExecType(ExecType.FED);
		UnaryOp cpParent = HopRewriteUtils.createUnary(target, OpOp1.EXP);
		cpParent.setDim1(10);
		cpParent.setDim2(10);
		cpParent.setForcedExecType(ExecType.CP);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedParent, cpParent), true, fTypeMap, -1);
		Lop fedLop = fedParent.constructLops();
		Lop cpLop = cpParent.constructLops();
		Lop targetLop = target.getLops();

		Dag<Lop> dag = new Dag<>();
		fedLop.addToDag(dag);
		cpLop.addToDag(dag);
		dag.getJobs(null, ConfigurationManager.getDMLConfig());

		boolean fedHasRefed = fedParent.getLops().getInputs().stream().anyMatch(l -> l instanceof FederatedRefed);
		assertTrue("Expected FED parent to be rewired via refed", fedHasRefed);
		assertTrue("Expected CP parent to keep local input", cpParent.getLops().getInputs().contains(targetLop));
	}

	private static DataOp createLocalMatrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, -1, BLOCKSIZE);
	}

	private static DataOp createFederatedInput(String name, long rows, long cols) {
		DataOp op = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, -1, BLOCKSIZE);
		op.setFederatedOutput(FederatedOutput.FOUT);
		op.setForcedExecType(ExecType.FED);
		return op;
	}
}

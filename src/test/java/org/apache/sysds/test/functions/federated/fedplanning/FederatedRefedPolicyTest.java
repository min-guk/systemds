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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDRefedInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Before;
import org.junit.Test;

public class FederatedRefedPolicyTest {
	private static final int BLOCKSIZE = 1000;

	@Before
	public void clearFedInitState() {
		// Tests mutate global planner state (fed-init vars / anchor keys). Reset for isolation.
		FederatedPlannerUtils.clearFedInitVars();
		FederatedRefedPolicy.clearHeuristicDemotedHops();
	}

	@Test
	public void testFromFTypesOptionalLocalDoesNotBypassFedInputFeasibility() {
		DataOp localInput = createLocalMatrix("L", 10, 10);
		UnaryOp optionalParent = HopRewriteUtils.createUnary(localInput, OpOp1.BROADCAST);
		optionalParent.setDim1(10);
		optionalParent.setDim2(10);

		boolean canSatisfy = FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(optionalParent, new HashMap<>());
		assertFalse("OPTIONAL local input must still prove materialization feasibility for FED execution", canSatisfy);
	}

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
		Map<Long, FederatedRefedRegistry.AnchorSpec> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertTrue("Expected refed registry entry for target hop", snapshot.containsKey(target.getHopID()));
		assertEquals("Anchor hop mismatch in registry", anchor.getHopID(), snapshot.get(target.getHopID()).getAnchorHopId());
	}

	@Test
	public void testAnchorMismatchFallsBackToLout() {
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

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent1, parent2), true, fTypeMap, -1);
		Map<Long, FederatedRefedRegistry.AnchorSpec> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertTrue("Expected refed registry entry for target hop", snapshot.containsKey(target.getHopID()));
		assertEquals("Anchor hop mismatch when multiple FED parents exist", anchor1.getHopID(),
			snapshot.get(target.getHopID()).getAnchorHopId());
		assertEquals("Expected first parent to remain FED", ExecType.FED, parent1.getForcedExecType());
		assertEquals("Expected second parent to remain FED", ExecType.FED, parent2.getForcedExecType());
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
		Map<Long, FederatedRefedRegistry.AnchorSpec> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertEquals("Anchor hop mismatch for federated target input", anchor.getHopID(),
			snapshot.get(target.getHopID()).getAnchorHopId());

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
		Map<Long, FederatedRefedRegistry.AnchorSpec> snapshot = FederatedRefedRegistry.snapshot(-1);
		assertEquals("Anchor hop mismatch when broadcast input is present", anchor.getHopID(),
			snapshot.get(target.getHopID()).getAnchorHopId());
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
	public void testVectorAxisMismatchBroadcastMaterialize() {
		DataOp localLhs = createLocalMatrix("L", 10, 1);
		DataOp localRhs = createLocalMatrix("R", 10, 1);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(1);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		String anchorName = "FED_INIT_VEC_ANCHOR";
		FederatedPlannerUtils.registerFedInitVar(anchorName, FType.COL, "sig_vec_mismatch");
		DataOp anchor = createFederatedInput(anchorName, 1, 10);
		AggBinaryOp parent = HopRewriteUtils.createMatrixMultiply(target, anchor);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.COL);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);

		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> snapshot =
			FederatedFoutMaterializeRegistry.snapshot(-1);
		assertTrue("Expected materialize registry entry for target hop", snapshot.containsKey(target.getHopID()));
		FederatedFoutMaterializeRegistry.MaterializeSpec spec = snapshot.get(target.getHopID());
		assertEquals("Anchor hop mismatch for broadcast materialize", anchor.getHopID(), spec.getAnchorHopId());
		assertEquals("FType hint mismatch for broadcast materialize", "BROADCAST", spec.getFTypeHint());
		assertTrue("Expected refed registry to be empty for target hop",
			!FederatedRefedRegistry.snapshot(-1).containsKey(target.getHopID()));

		Lop parentLop = parent.constructLops();
		Dag<Lop> dag = new Dag<>();
		parentLop.addToDag(dag);
		boolean hasBroadcastFout = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			String istr = inst.getInstructionString();
			if (istr.contains("fed_fout")) {
				FEDFoutInstruction.parseInstruction(istr);
				if (istr.contains("BROADCAST"))
					hasBroadcastFout = true;
			}
		}
		assertTrue("Expected BROADCAST fed_fout instruction for vector axis mismatch", hasBroadcastFout);
	}

	@Test
	public void testPlannedBroadcastPrefersFoutMaterializeOverRefed() {
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
		// Mimic MinST/DP planned CP->FOUT choice where the upload shape is BROADCAST.
		fTypeMap.put(target.getHopID(), FType.BROADCAST);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);

		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materializeSnapshot =
			FederatedFoutMaterializeRegistry.snapshot(-1);
		assertTrue("Expected materialize registry entry for BROADCAST-planned target",
			materializeSnapshot.containsKey(target.getHopID()));
		assertEquals("Expected BROADCAST materialize hint for BROADCAST-planned target", "BROADCAST",
			materializeSnapshot.get(target.getHopID()).getFTypeHint());
		assertTrue("Expected no refed registry entry when BROADCAST is planned",
			!FederatedRefedRegistry.snapshot(-1).containsKey(target.getHopID()));
	}

	@Test
	public void testScalarLikeMatrixBroadcastMaterializeUnderTernaryOp() {
		DataOp localLhs = createLocalMatrix("L", 1, 1);
		DataOp localRhs = createLocalMatrix("R", 1, 1);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(1);
		target.setDim2(1);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		assertTrue("Expected scalar-like target dims to be known", target.dimsKnown());
		assertEquals("Expected scalar-like target to be forced CP", ExecType.CP, target.getForcedExecType());
		assertEquals("Expected scalar-like target to have FOUT output before refed policy", FederatedOutput.FOUT,
			target.getFederatedOutput());

		DataOp anchor = createFederatedInput("A", 10, 10);
		TernaryOp parent = HopRewriteUtils.createTernary(anchor, target, anchor, OpOp3.PLUS_MULT);
		parent.setForcedExecType(ExecType.FED);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);
		assertEquals("Expected scalar-like target to remain FOUT after refed policy", FederatedOutput.FOUT,
			target.getFederatedOutput());

		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> snapshot =
			FederatedFoutMaterializeRegistry.snapshot(-1);
		assertTrue("Expected materialize registry entry for scalar-like target hop, snapshotKeys=" + snapshot.keySet(),
			snapshot.containsKey(target.getHopID()));
		FederatedFoutMaterializeRegistry.MaterializeSpec spec = snapshot.get(target.getHopID());
		assertEquals("Anchor hop mismatch for scalar-like broadcast materialize", anchor.getHopID(), spec.getAnchorHopId());
		assertEquals("FType hint mismatch for scalar-like broadcast materialize", "BROADCAST", spec.getFTypeHint());
		assertTrue("Expected refed registry to be empty for scalar-like target hop",
			!FederatedRefedRegistry.snapshot(-1).containsKey(target.getHopID()));

		Lop parentLop = parent.constructLops();
		Dag<Lop> dag = new Dag<>();
		parentLop.addToDag(dag);
		boolean hasBroadcastFout = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			String istr = inst.getInstructionString();
			if (istr.contains("fed_fout")) {
				FEDFoutInstruction.parseInstruction(istr);
				if (istr.contains("BROADCAST"))
					hasBroadcastFout = true;
			}
		}
		assertTrue("Expected BROADCAST fed_fout instruction for scalar-like target", hasBroadcastFout);
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

	@Test
	public void testNoCpfoutWithoutFedParentDemand() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		UnaryOp cpParent = HopRewriteUtils.createUnary(target, OpOp1.EXP);
		cpParent.setDim1(10);
		cpParent.setDim2(10);
		cpParent.setForcedExecType(ExecType.CP);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(cpParent), true, new HashMap<>(), -1);
		assertEquals("Expected target to be demoted to LOUT without FED parent demand",
			FederatedOutput.LOUT, target.getFederatedOutput());
		assertTrue("Expected no refed entry without FED parent demand",
			!FederatedRefedRegistry.snapshot(-1).containsKey(target.getHopID()));
		assertTrue("Expected no fed_fout materialization entry without FED parent demand",
			!FederatedFoutMaterializeRegistry.snapshot(-1).containsKey(target.getHopID()));

		Lop cpLop = cpParent.constructLops();
		Dag<Lop> dag = new Dag<>();
		cpLop.addToDag(dag);
		boolean hasFoutInstruction = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			if (inst.getInstructionString().contains("fed_fout")) {
				hasFoutInstruction = true;
				break;
			}
		}
		assertTrue("Expected no fed_fout instruction without FED parent demand", !hasFoutInstruction);
	}

	@Test
	public void testOptionalBroadcastInputStaysLocalForFedParent() {
		DataOp fedMatrix = createFederatedInput("X", 100, 10);
		DataOp localVector = createLocalMatrix("p", 10, 1);
		localVector.setForcedExecType(ExecType.CP);
		localVector.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedParent = HopRewriteUtils.createMatrixMultiply(fedMatrix, localVector);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);
		fTypeMap.put(localVector.getHopID(), FType.BROADCAST);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedParent), true, fTypeMap, -1);
		assertEquals("Expected optional BROADCAST vector input to remain local",
			FederatedOutput.LOUT, localVector.getFederatedOutput());
		assertTrue("Expected no refed entry for optional BROADCAST vector input",
			!FederatedRefedRegistry.snapshot(-1).containsKey(localVector.getHopID()));
		assertTrue("Expected no fed_fout materialization for optional BROADCAST vector input",
			!FederatedFoutMaterializeRegistry.snapshot(-1).containsKey(localVector.getHopID()));
	}

	@Test
	public void testPlannerAllowsOptionalLocalTransientReadInputWhenFedSiblingPresent() {
		DataOp fedMatrix = createFederatedInput("X", 100, 10);
		DataOp localVector = createLocalMatrix("p", 10, 1);
		localVector.setForcedExecType(ExecType.CP);
		localVector.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedParent = HopRewriteUtils.createMatrixMultiply(fedMatrix, localVector);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);

		assertTrue("Expected planner feasibility to allow optional local transient-read vector input when another FED input anchors execution",
			FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(fedParent, fTypeMap));
	}

	@Test
	public void testPlannerAllowsOptionalLocalDerivedInputWhenFedSiblingPresent() {
		DataOp fedMatrix = createFederatedInput("X", 100, 10);
		DataOp localA = createLocalMatrix("A", 100, 10);
		DataOp localB = createLocalMatrix("B", 100, 10);

		BinaryOp localDerived = HopRewriteUtils.createBinary(localA, localB, OpOp2.PLUS);
		localDerived.setDim1(100);
		localDerived.setDim2(10);
		localDerived.setForcedExecType(ExecType.CP);
		localDerived.setFederatedOutput(FederatedOutput.LOUT);

		BinaryOp fedParent = HopRewriteUtils.createBinary(fedMatrix, localDerived, OpOp2.MINUS);
		fedParent.setDim1(100);
		fedParent.setDim2(10);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);

		assertTrue("Expected planner feasibility to allow optional local derived-matrix input when another FED input anchors execution",
			FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(fedParent, fTypeMap));
	}

	@Test
	public void testPlannerAllowsRequiredLocalDerivedInputWhenFedSiblingPresent() {
		DataOp fedMatrix = createFederatedInput("X", 100, 50);
		DataOp localA = createLocalMatrix("A", 50, 10);
		DataOp localB = createLocalMatrix("B", 50, 10);

		BinaryOp localDerived = HopRewriteUtils.createBinary(localA, localB, OpOp2.PLUS);
		localDerived.setDim1(50);
		localDerived.setDim2(10);
		localDerived.setForcedExecType(ExecType.CP);
		localDerived.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedParent = HopRewriteUtils.createMatrixMultiply(fedMatrix, localDerived);
		fedParent.setDim1(100);
		fedParent.setDim2(10);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);

		assertTrue("Expected planner feasibility to allow required local derived input when FED sibling provides a concrete anchor",
			FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(fedParent, fTypeMap));
	}

	@Test
	public void testPlannerRejectsRequiredLocalDerivedInputWithoutFedSiblingAnchor() {
		DataOp localMatrix = createLocalMatrix("X", 100, 50);
		DataOp localA = createLocalMatrix("A", 50, 10);
		DataOp localB = createLocalMatrix("B", 50, 10);

		BinaryOp localDerived = HopRewriteUtils.createBinary(localA, localB, OpOp2.PLUS);
		localDerived.setDim1(50);
		localDerived.setDim2(10);
		localDerived.setForcedExecType(ExecType.CP);
		localDerived.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedParent = HopRewriteUtils.createMatrixMultiply(localMatrix, localDerived);
		fedParent.setDim1(100);
		fedParent.setDim2(10);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		assertFalse("Required local inputs without any FED sibling anchor must not pass FED-input feasibility",
			FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(fedParent, new HashMap<>()));
	}

	@Test
	public void testRecompileTransientWriteCanPromoteMatchingTransientRead() {
		DataOp tRead = createLocalMatrix("samples_vs_runs_map", 3000, 50);
		tRead.setForcedExecType(ExecType.CP);
		tRead.setFederatedOutput(FederatedOutput.LOUT);

		UnaryOp fedParent = HopRewriteUtils.createUnary(tRead, OpOp1.EXP);
		fedParent.setDim1(3000);
		fedParent.setDim2(50);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		DataOp tWriteInput = createLocalMatrix("map_local_src", 3000, 50);
		DataOp tWrite = createTransientWrite("samples_vs_runs_map", tWriteInput, 3000, 50);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.LOUT);
		tWrite.setRequiresRecompile();

		DataOp fedAnchor = createFederatedInput("X", 3000, 50);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedAnchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedParent, tWrite, fedAnchor), true, fTypeMap, -1);

		assertEquals("Expected matching TRead to be promoted to FED when same-block TWrite is available",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("Expected matching TRead to become FOUT",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
		assertEquals("Expected matching TWrite to be materialized as FOUT",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
	}

	@Test
	public void testRecompileTransientWritePromotionUsesRuntimeSignatureAnchorFallback() {
		DataOp tRead = createLocalMatrix("samples_vs_runs_map", 3000, 50);
		tRead.setForcedExecType(ExecType.CP);
		tRead.setFederatedOutput(FederatedOutput.LOUT);

		UnaryOp fedParent = HopRewriteUtils.createUnary(tRead, OpOp1.EXP);
		fedParent.setDim1(3000);
		fedParent.setDim2(50);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		DataOp tWriteInput = createLocalMatrix("map_local_src", 3000, 50);
		DataOp tWrite = createTransientWrite("samples_vs_runs_map", tWriteInput, 3000, 50);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.LOUT);
		tWrite.setRequiresRecompile();

		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X",
			"worker1:8001/data/P2P_features_2_1.data;worker2:8002/data/P2P_features_2_2.data;|0,50000;50000,100000;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(fedParent, tWrite)),
			true, fTypeMap, -1,
			runtimeSignatures, runtimeTypes);

		assertEquals("Expected matching TRead to be promoted to FED via runtime signature anchor fallback",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("Expected matching TRead to become FOUT",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
		assertEquals("Expected matching TWrite to be materialized as FOUT",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
	}

	@Test
	public void testRequiredTransientReadRegistersRefedAndSatisfiesFedParent() {
		DataOp tRead = createLocalMatrix("samples_vs_runs_map", 3000, 50);
		tRead.setForcedExecType(ExecType.CP);
		tRead.setFederatedOutput(FederatedOutput.LOUT);

		DataOp localRhs = createLocalMatrix("local_rhs", 50, 1);
		localRhs.setForcedExecType(ExecType.CP);
		localRhs.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedParent = HopRewriteUtils.createMatrixMultiply(tRead, localRhs);
		fedParent.setDim1(3000);
		fedParent.setDim2(1);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		DataOp fedAnchor = createFederatedInput("X", 3000, 50);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedAnchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedParent, fedAnchor), true, fTypeMap, -1);

		Map<Long, FederatedRefedRegistry.AnchorSpec> refedSnapshot = FederatedRefedRegistry.snapshot(-1);
		assertTrue("Expected required local transient read to be registered via fed_refed",
			refedSnapshot.containsKey(tRead.getHopID()));
		assertEquals("Expected FED parent to remain FED after satisfying required transient-read input",
			ExecType.FED, fedParent.getForcedExecType());
	}

	@Test
	public void testHeuristicDemotedPersistsForRuntimeRegisterFromHops() {
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

		FederatedRefedPolicy.markHeuristicDemotedHop(target.getHopID());
		FederatedRefedPolicy.registerFromProgram(null, fTypeMap);

		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DMLRuntimeException ex = assertThrows(DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1));
		assertTrue("Expected fail-fast error for FED parent with unsatisfied federated inputs",
			ex.getMessage().contains("FED hop has no federated inputs and no CP->FOUT candidate"));
	}

	@Test
	public void testHeuristicDemotedPropagatesToDeepCopyForRegisterFromHops() {
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
		FederatedRefedPolicy.markHeuristicDemotedHop(target.getHopID());

		Map<Long, Hop> deepCopyMemo = new HashMap<>();
		java.util.ArrayList<Hop> copiedRoots = Recompiler.deepCopyHopsDag(Arrays.asList(parent), deepCopyMemo);
		Hop copiedTarget = deepCopyMemo.get(target.getHopID());
		Hop copiedAnchor = deepCopyMemo.get(anchor.getHopID());
		assertTrue("Expected copied target hop in deep-copy memo", copiedTarget != null);
		assertTrue("Expected copied anchor hop in deep-copy memo", copiedAnchor != null);

		Set<Long> clonedDemotedIds = FederatedRefedPolicy.markHeuristicDemotedClones(deepCopyMemo);
		try {
			Map<Long, FType> copiedFTypeMap = new HashMap<>();
			copiedFTypeMap.put(copiedAnchor.getHopID(), FType.ROW);
			DMLRuntimeException ex = assertThrows(DMLRuntimeException.class,
				() -> FederatedRefedPolicy.registerFromHops(copiedRoots, true, copiedFTypeMap, -1));
			assertTrue("Expected fail-fast error for copied FED parent with unsatisfied federated inputs",
				ex.getMessage().contains("FED hop has no federated inputs and no CP->FOUT candidate"));
		}
		finally {
			FederatedRefedPolicy.unmarkHeuristicDemotedHops(clonedDemotedIds);
		}

		assertTrue("Expected original demoted marker to remain after clone cleanup",
			!FederatedRefedPolicy.canGenerateCpfoutCandidate(target, fTypeMap));
	}

	private static DataOp createLocalMatrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, -1, BLOCKSIZE);
	}

	private static DataOp createFederatedInput(String name, long rows, long cols) {
		// Model a runtime-federated variable via the fed-init registry so isRuntimeFederatedInput() is stable.
		FederatedPlannerUtils.registerFedInitVar(name);
		DataOp op = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, -1, BLOCKSIZE);
		op.setFederatedOutput(FederatedOutput.FOUT);
		op.setForcedExecType(ExecType.FED);
		return op;
	}

	private static DataOp createTransientWrite(String name, Hop input, long rows, long cols) {
		DataOp tWrite = new DataOp(name, DataType.MATRIX, ValueType.FP64, input,
			OpOpData.TRANSIENTWRITE, name);
		tWrite.setDim1(rows);
		tWrite.setDim2(cols);
		return tWrite;
	}
}

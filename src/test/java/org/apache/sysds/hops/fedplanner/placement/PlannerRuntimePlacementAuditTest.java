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
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.Instruction.IType;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.FunctionCallCPInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.InitFEDInstruction;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PlannerRuntimePlacementAuditTest {
	private static final PlacementState CP_LOUT = new PlacementState(
		ExecType.CP, FEDInstruction.FederatedOutput.LOUT, null, false);
	private static final PlacementState FED_FOUT = new PlacementState(
		ExecType.FED, FEDInstruction.FederatedOutput.FOUT, null, false);
	private static final PlacementState FED_LOUT = new PlacementState(
		ExecType.FED, FEDInstruction.FederatedOutput.LOUT, null, false);

	@Before
	public void setUp() {
		System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, Boolean.TRUE.toString());
		PlannerRuntimePlacementAudit.resetForTesting();
	}

	@After
	public void tearDown() {
		PlannerRuntimePlacementAudit.resetForTesting();
		System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
	}

	@Test
	public void loweringRejectsAnExecPlacementDifferentFromTheSelectedPhysicalCandidate() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(41, "sig-41", CP_LOUT, CP_LOUT, true)));
		AuditFedInstruction actual = new AuditFedInstruction("ba+*", FEDInstruction.FederatedOutput.LOUT);
		actual.setAuditLocation(41, "sig-41");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(actual))));
		assertTrue(failure.getMessage().contains("LOWERING_MISMATCH"));
		assertTrue(failure.getMessage().contains("plannedPhysical=CP/LOUT"));
		assertTrue(failure.getMessage().contains("actual=FED/LOUT"));
	}

	@Test
	public void exactLoweringAndRuntimeExecutionProduceOneCountedMatch() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(42, "sig-42", FED_FOUT, FED_FOUT, true)));
		AuditFedInstruction lowered = new AuditFedInstruction("ba+*", FEDInstruction.FederatedOutput.FOUT);
		lowered.setAuditLocation(42, "sig-42");
		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(lowered)));

		PlannerRuntimePlacementAudit.validateExecution(lowered);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(lowered);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(lowered);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=MATCH"));
		assertTrue(report.contains("hop=42"));
		assertTrue(report.contains("plannedPhysical=FED/FOUT"));
		assertTrue(report.contains("actual=FED/FOUT"));
		assertTrue(report.contains("count=2"));
	}

	@Test
	public void workerInstructionIsAuditedAsAnExactFragmentOfItsCoordinatorFedParent() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(46, "sig-46", FED_FOUT, FED_FOUT, true)));
		AuditFedInstruction coordinator = new AuditFedInstruction("ba+*", FEDInstruction.FederatedOutput.FOUT);
		coordinator.setAuditLocation(46, "sig-46");
		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(coordinator)));

		FederatedRequest request;
		try(PlannerRuntimePlacementAudit.RuntimeExecutionScope ignored =
			PlannerRuntimePlacementAudit.beginRuntimeExecution(coordinator)) {
			request = new FederatedRequest(RequestType.EXEC_INST, 7,
				"CP" + Instruction.OPERAND_DELIM + "ba+*" + Instruction.OPERAND_DELIM + "payload");
			PlannerRuntimePlacementAudit.validateFederatedRequestDispatch(request);
		}
		assertTrue(request.getPlannerRuntimeAuthority() != null);
		assertEquals("ba+*", request.getPlannerRuntimeAuthority().getParentOpcode());
		assertEquals("FED/FOUT", request.getPlannerRuntimeAuthority().getParentPhysical());

		AuditCpInstruction workerFragment = new AuditCpInstruction("ba+*", IType.CONTROL_PROGRAM);
		PlannerRuntimePlacementAudit.attachWorkerFragment(request, workerFragment);
		PlannerRuntimePlacementAudit.validateExecution(workerFragment);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(workerFragment);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("[Federated-Dispatch] status=MATCH"));
		assertTrue(report.contains("[Worker-Fragment] status=MATCH"));
		assertTrue(report.contains("fragmentOpcode=ba+*"));
		assertTrue(report.contains("actual=CP/LOUT"));
	}

	@Test
	public void workerComputeRequestWithoutAnExactCoordinatorFedParentFailsClosed() {
		PlannerRuntimePlacementAudit.installForTesting(List.of());
		FederatedRequest request = new FederatedRequest(RequestType.EXEC_INST, 8,
			"CP" + Instruction.OPERAND_DELIM + "+" + Instruction.OPERAND_DELIM + "payload");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.validateFederatedRequestDispatch(request));
		assertTrue(failure.getMessage().contains("FEDERATED_REQUEST_UNPLANNED"));
	}

	@Test
	public void serializedWorkerParentCannotClaimACpPhysicalPlan() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(47, "sig-47", FED_FOUT, FED_FOUT, true)));
		FederatedRequest request = new FederatedRequest(RequestType.EXEC_INST, 9,
			"CP" + Instruction.OPERAND_DELIM + "ba+*" + Instruction.OPERAND_DELIM + "payload");
		request.setPlannerRuntimeAuthority(new FederatedRequest.PlannerRuntimeAuthority(
			"test-plan", "forged-parent", "ba+*", "CP/LOUT", 47, -1, "sig-47"));

		AuditCpInstruction workerFragment = new AuditCpInstruction("ba+*", IType.CONTROL_PROGRAM);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.attachWorkerFragment(request, workerFragment));
		assertTrue(failure.getMessage().contains("WORKER_FRAGMENT_INVALID_PARENT"));
	}

	@Test
	public void reparsedInstructionKeepsPlannerOccurrenceProvenance() {
		AuditCpInstruction original = new AuditCpInstruction("+", IType.CONTROL_PROGRAM);
		original.setAuditLocation(43, "sig-43");
		original.setPlannerOriginHopID(41);
		original.setPlannerSyntheticActionKey("selected-action|stage=LOCAL");
		original.setPlannerAuditKey("runtime-proof");
		AuditCpInstruction reparsed = new AuditCpInstruction("+", IType.CONTROL_PROGRAM);

		reparsed.setLocation(original);

		assertEquals(43, reparsed.getHopID());
		assertEquals(41, reparsed.getPlannerOriginHopID());
		assertEquals("sig-43", reparsed.getPlannerRecompileSignature());
		assertEquals("selected-action|stage=LOCAL", reparsed.getPlannerSyntheticActionKey());
		assertEquals("runtime-proof", reparsed.getPlannerAuditKey());
	}

	@Test
	public void recompilerDeepCopiesRetainTheUltimatePlannerOriginHopIdentity() throws Exception {
		LiteralOp original = new LiteralOp(7L);
		LiteralOp firstClone = (LiteralOp) original.clone();
		LiteralOp secondClone = (LiteralOp) firstClone.clone();

		assertTrue(original.getHopID() != firstClone.getHopID());
		assertTrue(firstClone.getHopID() != secondClone.getHopID());
		assertEquals(original.getHopID(), firstClone.getPlannerOriginHopID());
		assertEquals(original.getHopID(), secondClone.getPlannerOriginHopID());
	}

	@Test
	public void sourceLessRecompiledInstructionResolvesOnlyByExactPlannerOriginHopIdentity() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(48, null, CP_LOUT, CP_LOUT, true)));
		AuditCpInstruction recompiled = new AuditCpInstruction("ba+*", IType.CONTROL_PROGRAM);
		recompiled.setAuditLocation(1048, null);
		recompiled.setPlannerOriginHopID(48);

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(recompiled)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=MATCH"));
		assertTrue(report.contains("hop=48"));
		assertTrue(report.contains("origin=48"));
	}

	@Test
	public void dynamicBinaryScalarCastIsProvedOnlyAsAnExactCpLocalOwnerHelper() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(49, "binary-owner", "+", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction cast = new AuditCpInstruction("castdts", IType.CONTROL_PROGRAM);
		cast.setAuditLocation(1049, "binary-owner");
		cast.setPlannerOriginHopID(49);
		cast.setPlannerLoweringAuxiliaryKind("DYNAMIC_BINARY_SCALAR_CAST");
		AuditCpInstruction owner = new AuditCpInstruction("+", IType.CONTROL_PROGRAM);
		owner.setAuditLocation(49, "binary-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(cast, owner)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=LOWERING_HELPER_MATCH"));
		assertTrue(report.contains("kind=DYNAMIC_BINARY_SCALAR_CAST"));
		assertTrue(report.contains("helperOpcode=castdts"));
	}

	@Test
	public void dynamicBinaryScalarCastCannotBorrowAFederatedOwnerPlan() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(50, "fed-binary-owner", "+", FED_FOUT, FED_FOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction cast = new AuditCpInstruction("castdts", IType.CONTROL_PROGRAM);
		cast.setAuditLocation(1050, "fed-binary-owner");
		cast.setPlannerOriginHopID(50);
		cast.setPlannerLoweringAuxiliaryKind("DYNAMIC_BINARY_SCALAR_CAST");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(cast))));
		assertTrue(failure.getMessage().contains("LOWERING_AUXILIARY_MISMATCH"));
	}

	@Test
	public void appendOffsetIsACpMetadataHelperForAFederatedAppendOwner() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(59, "fed-append-owner", "cbind", FED_FOUT, FED_FOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction ncol = new AuditCpInstruction("ncol", IType.CONTROL_PROGRAM);
		ncol.setAuditLocation(1059, "fed-append-owner");
		ncol.setPlannerOriginHopID(59);
		ncol.setPlannerLoweringAuxiliaryKind("APPEND_OFFSET_NCOL");
		AuditFedInstruction append = new AuditFedInstruction("append",
			FEDInstruction.FederatedOutput.FOUT);
		append.setAuditLocation(59, "fed-append-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(ncol, append)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=LOWERING_HELPER_MATCH"));
		assertTrue(report.contains("kind=APPEND_OFFSET_NCOL"));
	}

	@Test
	public void localPersistentReadReblockIsTheExplicitSparkPrimaryReplacement() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			reblockPlan(63, "local-read-reblock", "PRead", CP_LOUT)));
		AuditCpInstruction reblock = new AuditCpInstruction("rblk", IType.SPARK);
		reblock.setAuditLocation(1063, "local-read-reblock");
		reblock.setPlannerOriginHopID(63);
		reblock.setPlannerRewriteReplacementKind("PERSISTENT_READ_REBLOCK");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(),
			new ArrayList<>(List.of(reblock)));
		PlannerRuntimePlacementAudit.validateExecution(reblock);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=REWRITE_MATCH"));
		assertTrue(report.contains("kind=PERSISTENT_READ_REBLOCK"));
		assertTrue(report.contains("actual=SPARK/LOUT"));
	}

	@Test
	public void federatedPhysicalReblockIsLoweredDirectlyAsFedFoutHelper() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			reblockPlan(64, "fed-init-reblock", "Fed X", FED_FOUT)));
		AuditFedInstruction init = new AuditFedInstruction("fedinit",
			FEDInstruction.FederatedOutput.FOUT);
		init.setAuditLocation(64, "fed-init-reblock");
		AuditFedInstruction reblock = new AuditFedInstruction("rblk",
			FEDInstruction.FederatedOutput.FOUT);
		reblock.setAuditLocation(1064, "fed-init-reblock");
		reblock.setPlannerOriginHopID(64);
		reblock.setPlannerLoweringAuxiliaryKind("PHYSICAL_REBLOCK");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(),
			new ArrayList<>(List.of(init, reblock)));
		PlannerRuntimePlacementAudit.validateExecution(reblock);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("kind=PHYSICAL_REBLOCK"));
		assertTrue(report.contains("actual=FED/FOUT"));
	}

	@Test
	public void federatedPhysicalReblockCannotDeferPlacementToRuntimeSparkReplacement() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			reblockPlan(65, "fed-init-dynamic-reblock", "Fed X", FED_FOUT)));
		AuditCpInstruction dynamicReblock = new AuditCpInstruction("rblk", IType.SPARK);
		dynamicReblock.setAuditLocation(1065, "fed-init-dynamic-reblock");
		dynamicReblock.setPlannerOriginHopID(65);
		dynamicReblock.setPlannerLoweringAuxiliaryKind("PHYSICAL_REBLOCK");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(),
				new ArrayList<>(List.of(dynamicReblock))));
		assertTrue(failure.getMessage().contains("LOWERING_AUXILIARY_MISMATCH"));
		assertTrue(failure.getMessage().contains("expected=FED/FOUT"));
	}

	@Test
	public void dynamicAxpyReplacementMustMatchTheExactOwnerOpcodeAndPlacement() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(51, "minus-owner", "-", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction ternary = new AuditCpInstruction("-*", IType.CONTROL_PROGRAM);
		ternary.setAuditLocation(1051, "minus-owner");
		ternary.setPlannerOriginHopID(51);
		ternary.setPlannerRewriteReplacementKind("DYNAMIC_AXPY_MINUS_MULT");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(ternary)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=REWRITE_MATCH"));
		assertTrue(report.contains("kind=DYNAMIC_AXPY_MINUS_MULT"));
		assertTrue(report.contains("replacementOpcode=-*"));
	}

	@Test
	public void dynamicWeightedDivMmReplacementRetainsExactFederatedOwnerAuthority() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(69, "wdivmm-owner", "ba+*", FED_FOUT, FED_FOUT, true, NodeKind.OPERATION)));
		AuditFedInstruction replacement = new AuditFedInstruction(
			"wdivmm", FEDInstruction.FederatedOutput.FOUT);
		replacement.setAuditLocation(1069, "wdivmm-owner");
		replacement.setPlannerOriginHopID(69);
		replacement.setPlannerRewriteReplacementKind("DYNAMIC_WEIGHTED_DIV_MM");

		PlannerRuntimePlacementAudit.verifyLowering(
			List.of(), new ArrayList<>(List.of(replacement)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=REWRITE_MATCH"));
		assertTrue(report.contains("kind=DYNAMIC_WEIGHTED_DIV_MM"));
		assertTrue(report.contains("replacementOpcode=wdivmm"));
		assertTrue(report.contains("plannedPhysical=FED/FOUT"));
	}

	@Test
	public void dynamicWeightedDivMmReplacementCannotBorrowANonFusionOwner() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(70, "non-fusion-owner", "+", FED_FOUT, FED_FOUT, true, NodeKind.OPERATION)));
		AuditFedInstruction replacement = new AuditFedInstruction(
			"wdivmm", FEDInstruction.FederatedOutput.FOUT);
		replacement.setAuditLocation(1070, "non-fusion-owner");
		replacement.setPlannerOriginHopID(70);
		replacement.setPlannerRewriteReplacementKind("DYNAMIC_WEIGHTED_DIV_MM");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(
				List.of(), new ArrayList<>(List.of(replacement))));
		assertTrue(failure.getMessage().contains("LOWERING_REWRITE_OPCODE_MISMATCH"));
	}

	@Test
	public void dynamicWeightedDivMmTransposePairRetainsOuterFederatedLocalAuthority() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(71, "outer-transpose-owner", "r'", FED_FOUT, FED_LOUT, true,
				NodeKind.OPERATION)));
		AuditFedInstruction replacement = new AuditFedInstruction(
			"wdivmm", FEDInstruction.FederatedOutput.LOUT);
		replacement.setAuditLocation(1071, "outer-transpose-owner");
		replacement.setPlannerOriginHopID(71);
		replacement.setPlannerRewriteReplacementKind(
			"DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR");

		PlannerRuntimePlacementAudit.verifyLowering(
			List.of(), new ArrayList<>(List.of(replacement)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=REWRITE_MATCH"));
		assertTrue(report.contains("kind=DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR"));
		assertTrue(report.contains("replacementOpcode=wdivmm"));
		assertTrue(report.contains("plannedPhysical=FED/LOUT"));
	}

	@Test
	public void dynamicWeightedDivMmTransposePairCannotBorrowAMatrixMultiplyOwner() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(72, "inner-mm-owner", "ba+*", FED_FOUT, FED_LOUT, true,
				NodeKind.OPERATION)));
		AuditFedInstruction replacement = new AuditFedInstruction(
			"wdivmm", FEDInstruction.FederatedOutput.LOUT);
		replacement.setAuditLocation(1072, "inner-mm-owner");
		replacement.setPlannerOriginHopID(72);
		replacement.setPlannerRewriteReplacementKind(
			"DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(
				List.of(), new ArrayList<>(List.of(replacement))));
		assertTrue(failure.getMessage().contains("LOWERING_REWRITE_OPCODE_MISMATCH"));
	}

	@Test
	public void dynamicRewriteReplacementCannotUseAnUnregisteredOpcodeSubstitution() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(52, "minus-owner", "-", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction forged = new AuditCpInstruction("+*", IType.CONTROL_PROGRAM);
		forged.setAuditLocation(1052, "minus-owner");
		forged.setPlannerOriginHopID(52);
		forged.setPlannerRewriteReplacementKind("DYNAMIC_AXPY_MINUS_MULT");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(forged))));
		assertTrue(failure.getMessage().contains("LOWERING_REWRITE_OPCODE_MISMATCH"));
	}

	@Test
	public void dynamicDotProductStagesAreClosedAgainstTheOriginalAggregateOwner() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(53, "sum-owner", "ua(+rc)", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction tsmm = new AuditCpInstruction("tsmm", IType.CONTROL_PROGRAM);
		tsmm.setAuditLocation(1053, "sum-owner");
		tsmm.setPlannerOriginHopID(53);
		tsmm.setPlannerRewriteReplacementKind("DYNAMIC_DOT_PRODUCT");
		AuditCpInstruction cast = new AuditCpInstruction("castdts", IType.CONTROL_PROGRAM);
		cast.setAuditLocation(1054, "sum-owner");
		cast.setPlannerOriginHopID(53);
		cast.setPlannerLoweringAuxiliaryKind("DYNAMIC_DOT_PRODUCT_SCALAR_CAST");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(tsmm, cast)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("kind=DYNAMIC_DOT_PRODUCT"));
		assertTrue(report.contains("kind=DYNAMIC_DOT_PRODUCT_SCALAR_CAST"));
	}

	@Test
	public void dynamicTableSequenceRewriteIsAnExactCtableReplacement() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(54, "ctable-owner", "ctable", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction rexpand = new AuditCpInstruction("rexpand", IType.CONTROL_PROGRAM);
		rexpand.setAuditLocation(1055, "ctable-owner");
		rexpand.setPlannerOriginHopID(54);
		rexpand.setPlannerRewriteReplacementKind("DYNAMIC_TABLE_SEQ_REXPAND");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(rexpand)));

		assertTrue(PlannerRuntimePlacementAudit.display().contains("kind=DYNAMIC_TABLE_SEQ_REXPAND"));
	}

	@Test
	public void constantFoldResultBindingIsOnlyAnExactCpLocalHelper() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(55, "constant-owner", "*", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction bind = new AuditCpInstruction("mvvar", IType.CONTROL_PROGRAM);
		bind.setAuditLocation(1055, "constant-owner");
		bind.setPlannerOriginHopID(55);
		bind.setPlannerLoweringAuxiliaryKind("CONSTANT_FOLD_RESULT_BIND");
		AuditCpInstruction owner = new AuditCpInstruction("*", IType.CONTROL_PROGRAM);
		owner.setAuditLocation(55, "constant-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(owner, bind)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("kind=CONSTANT_FOLD_RESULT_BIND"));
		assertTrue(report.contains("helperOpcode=mvvar"));
	}

	@Test
	public void constantFoldResultBindingCannotBorrowAFederatedPlan() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(56, "constant-fed-owner", "-", FED_FOUT, FED_FOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction bind = new AuditCpInstruction("mvvar", IType.CONTROL_PROGRAM);
		bind.setAuditLocation(1056, "constant-fed-owner");
		bind.setPlannerOriginHopID(56);
		bind.setPlannerLoweringAuxiliaryKind("CONSTANT_FOLD_RESULT_BIND");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(bind))));
		assertTrue(failure.getMessage().contains("LOWERING_AUXILIARY_MISMATCH"));
	}

	@Test
	public void ordinaryLoweringRejectsAnUnrelatedOpcodeEvenWhenPlacementMatches() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(57, "multiply-owner", "*", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction forged = new AuditCpInstruction("mvvar", IType.CONTROL_PROGRAM);
		forged.setAuditLocation(57, "multiply-owner");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(forged))));
		assertTrue(failure.getMessage().contains("LOWERING_OPCODE_MISMATCH"));
	}

	@Test
	public void ordinaryLoweringAcceptsOnlyAnExplicitPhysicalOpcodeAlias() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(58, "append-owner", "cbind", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction append = new AuditCpInstruction("append", IType.CONTROL_PROGRAM);
		append.setAuditLocation(58, "append-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(append)));

		assertTrue(PlannerRuntimePlacementAudit.display().contains("status=MATCH"));
	}

	@Test
	public void aggregateUnaryLogicalOpcodeMustMatchTheCompilerPhysicalOpcode() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(60, "sumsq-owner", "ua(sq+RC)", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction sumSq = new AuditCpInstruction("uasqk+", IType.CONTROL_PROGRAM);
		sumSq.setAuditLocation(60, "sumsq-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(sumSq)));

		assertTrue(PlannerRuntimePlacementAudit.display().contains("status=MATCH"));
	}

	@Test
	public void ctableSequenceSpecializationIsAnExplicitCompilerOpcodeAlias() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(61, "ctable-owner", "ctable", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction ctableExpand = new AuditCpInstruction("ctableexpand", IType.CONTROL_PROGRAM);
		ctableExpand.setAuditLocation(61, "ctable-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(ctableExpand)));

		assertTrue(PlannerRuntimePlacementAudit.display().contains("status=MATCH"));
	}

	@Test
	public void sortLogicalOpcodeMatchesTheCompilerRsortOpcode() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(62, "sort-owner", "sort", CP_LOUT, CP_LOUT, true, NodeKind.OPERATION)));
		AuditCpInstruction sort = new AuditCpInstruction("rsort", IType.CONTROL_PROGRAM);
		sort.setAuditLocation(62, "sort-owner");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(sort)));

		assertTrue(PlannerRuntimePlacementAudit.display().contains("status=MATCH"));
	}

	@Test
	public void exactSyntheticActionIdentitySurvivesLoweringAndExecution() {
		String base = "selected-local-action";
		String token = PlannerRuntimePlacementAudit.syntheticActionKey(base, "LOCAL");
		PlannerRuntimePlacementAudit.PlannedSyntheticAction action =
			new PlannerRuntimePlacementAudit.PlannedSyntheticAction(token, base, "LOCAL", "prefetch",
				ExecType.CP, FEDInstruction.FederatedOutput.LOUT, null);
		PlannerRuntimePlacementAudit.installForTesting(List.of(), List.of(action));
		AuditCpInstruction prefetch = new AuditCpInstruction("prefetch", IType.CONTROL_PROGRAM);
		prefetch.setPlannerSyntheticActionKey(token);

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(prefetch)));
		PlannerRuntimePlacementAudit.validateExecution(prefetch);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(prefetch);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("[Lowering-Synthetic] status=MATCH"));
		assertTrue(report.contains("stage=LOCAL"));
		assertTrue(report.contains("plannedSynthetic=1"));
		assertTrue(report.contains("missingSynthetic=0"));
		assertTrue(report.contains("mismatches=0"));
	}

	@Test
	public void syntheticInstructionWithoutExactPlannerTokenFailsClosed() {
		String base = "selected-refed-action";
		String token = PlannerRuntimePlacementAudit.syntheticActionKey(base, "REFED");
		PlannerRuntimePlacementAudit.PlannedSyntheticAction action =
			new PlannerRuntimePlacementAudit.PlannedSyntheticAction(token, base, "REFED", "fed_refed",
				ExecType.FED, FEDInstruction.FederatedOutput.FOUT, null);
		PlannerRuntimePlacementAudit.installForTesting(List.of(), List.of(action));
		AuditFedInstruction refed = new AuditFedInstruction("fed_refed", FEDInstruction.FederatedOutput.FOUT);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(refed))));
		assertTrue(failure.getMessage().contains("LOWERING_SYNTHETIC_UNPROVEN"));
	}

	@Test
	public void sameSyntheticCategoryWithDifferentActionIdentityFailsClosed() {
		String selectedBase = "selected-local-action";
		String selectedToken = PlannerRuntimePlacementAudit.syntheticActionKey(selectedBase, "LOCAL");
		PlannerRuntimePlacementAudit.PlannedSyntheticAction action =
			new PlannerRuntimePlacementAudit.PlannedSyntheticAction(selectedToken, selectedBase, "LOCAL", "prefetch",
				ExecType.CP, FEDInstruction.FederatedOutput.LOUT, null);
		PlannerRuntimePlacementAudit.installForTesting(List.of(), List.of(action));
		AuditCpInstruction prefetch = new AuditCpInstruction("prefetch", IType.CONTROL_PROGRAM);
		prefetch.setPlannerSyntheticActionKey(
			PlannerRuntimePlacementAudit.syntheticActionKey("runtime-inferred-action", "LOCAL"));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(prefetch))));
		assertTrue(failure.getMessage().contains("LOWERING_SYNTHETIC_UNSELECTED"));
	}

	@Test
	public void unplannedRuntimeHopInstructionFailsClosed() {
		PlannerRuntimePlacementAudit.installForTesting(List.of());
		AuditCpInstruction unplanned = new AuditCpInstruction("+", IType.CONTROL_PROGRAM);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(unplanned))));
		assertTrue(failure.getMessage().contains("LOWERING_UNPLANNED"));
	}

	@Test
	public void missingSelectedSyntheticActionIsReportedAsMismatch() {
		String base = "selected-local-action";
		String token = PlannerRuntimePlacementAudit.syntheticActionKey(base, "LOCAL");
		PlannerRuntimePlacementAudit.PlannedSyntheticAction action =
			new PlannerRuntimePlacementAudit.PlannedSyntheticAction(token, base, "LOCAL", "prefetch",
				ExecType.CP, FEDInstruction.FederatedOutput.LOUT, null);
		PlannerRuntimePlacementAudit.installForTesting(List.of(), List.of(action));
		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>());

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("missingSynthetic=1"));
		assertTrue(report.contains("mismatches=1"));
		assertTrue(report.contains("[Lowering-Synthetic] status=MISSING"));
	}

	@Test
	public void missingSelectedPhysicalOperationIsReportedEvenWithoutAnOwnBoundary() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(
			plan(66, "ordinary-missing", CP_LOUT, CP_LOUT, false)));

		String report = PlannerRuntimePlacementAudit.display();

		assertTrue(report.contains("plannedPhysicalHops=1"));
		assertTrue(report.contains("loweredPhysicalHops=0"));
		assertTrue(report.contains("missingPhysicalHops=1"));
		assertTrue(report.contains("mismatches=1"));
		assertTrue(report.contains("[Lowering] status=MISSING"));
	}

	@Test
	public void unchangedOccurrenceRetainsItsExactLoweringAcrossCompleteReplan() {
		PlannerRuntimePlacementAudit.PlannedHop unchanged =
			plan(67, "stable-occurrence", CP_LOUT, CP_LOUT, false);
		PlannerRuntimePlacementAudit.installForTesting("plan-before", List.of(unchanged), List.of());
		AuditCpInstruction instruction = new AuditCpInstruction("ba+*", IType.CONTROL_PROGRAM);
		instruction.setAuditLocation(67, "stable-occurrence");
		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(instruction)));

		PlannerRuntimePlacementAudit.installForTesting("plan-after", List.of(unchanged), List.of());
		PlannerRuntimePlacementAudit.validateExecution(instruction);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(instruction);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("authorityGenerations=2"));
		assertTrue(report.contains("status=AUTHORITY_CARRY_FORWARD_MATCH"));
		assertTrue(report.contains("missingPhysicalHops=0"));
		assertTrue(report.contains("mismatches=0"));
	}

	@Test
	public void changedOccurrenceCannotExecuteAnInstructionLoweredByThePreviousPlan() {
		PlannerRuntimePlacementAudit.installForTesting("plan-before",
			List.of(plan(68, "changed-occurrence", CP_LOUT, CP_LOUT, false)), List.of());
		AuditCpInstruction instruction = new AuditCpInstruction("ba+*", IType.CONTROL_PROGRAM);
		instruction.setAuditLocation(68, "changed-occurrence");
		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(instruction)));
		PlannerRuntimePlacementAudit.installForTesting("plan-after",
			List.of(plan(68, "changed-occurrence", FED_FOUT, FED_FOUT, true)), List.of());

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.validateExecution(instruction));

		assertTrue(failure.getMessage().contains("RUNTIME_STALE_PLAN"));
		assertTrue(failure.getMessage().contains("loweredPlan=plan-before"));
		assertTrue(failure.getMessage().contains("activePlan=plan-after"));
	}

	@Test
	public void federatedInitDeclaresItsInherentFoutPlacement() {
		InitFEDInstruction instruction = new InitFEDInstruction(new CPOperand("type"),
			new CPOperand("addresses"), new CPOperand("ranges"), new CPOperand("out"),
			"fedinit", "fedinit");

		assertEquals(FEDInstruction.FederatedOutput.FOUT, instruction.getFederatedOutput());
	}

	@Test
	public void variableBindingsExposeTheirActualPublishedDestinationForPlacementAudit() {
		Instruction copy = org.apache.sysds.runtime.instructions.cp.VariableCPInstruction
			.prepareCopyInstruction("source", "copyTarget");
		Instruction move = org.apache.sysds.runtime.instructions.cp.VariableCPInstruction
			.prepMoveInstruction("source", "moveTarget");

		assertEquals("copyTarget", copy.getOutputVariableName());
		assertEquals("moveTarget", move.getOutputVariableName());
	}

	@Test
	public void dmlFunctionPlacementIsAuditedAsCoordinatorControlAndNotAsFedExecution() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(planWithNameAndControlTarget(45, "sig-45",
			"fcall", "ns::publicWrapper", "ns::fn", FED_FOUT, FED_FOUT, true,
			NodeKind.FUNCTION_CALL)));
		AuditFunctionCallInstruction call = new AuditFunctionCallInstruction();
		call.setAuditLocation(45, "sig-45");

		PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(call)));
		PlannerRuntimePlacementAudit.validateExecution(call);
		PlannerRuntimePlacementAudit.recordSuccessfulExecution(call);

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=CONTROL_MATCH"));
		assertTrue(report.contains("plannedTarget=FED/FOUT"));
		assertTrue(report.contains("plannedPhysical=CP/LOUT"));
		assertTrue(report.contains("actual=CP/LOUT"));
	}

	@Test
	public void dmlFunctionControlCannotCallADifferentFunction() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(planWithNameAndControlTarget(59, "sig-59",
			"fcall", "ns::publicWrapper", "ns::other", FED_FOUT, FED_FOUT, true,
			NodeKind.FUNCTION_CALL)));
		AuditFunctionCallInstruction call = new AuditFunctionCallInstruction();
		call.setAuditLocation(59, "sig-59");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>(List.of(call))));
		assertTrue(failure.getMessage().contains("FUNCTION_CONTROL_TARGET_MISMATCH"));
	}

	@Test
	public void strictSelectedOperationMissingFromLoweringFailsClosed() {
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(44, "sig-44", FED_FOUT, FED_FOUT, true)));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> PlannerRuntimePlacementAudit.verifyLowering(List.of(), new ArrayList<>()));
		assertTrue(failure.getMessage().contains("LOWERING_MISSING"));
		assertTrue(failure.getMessage().contains("hop=44"));
	}

	private static PlannerRuntimePlacementAudit.PlannedHop plan(long hopId, String signature,
		PlacementState target, PlacementState physical, boolean requiresOwnInstruction) {
		return plan(hopId, signature, target, physical, requiresOwnInstruction, NodeKind.OPERATION);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop plan(long hopId, String signature,
		PlacementState target, PlacementState physical, boolean requiresOwnInstruction, NodeKind nodeKind) {
		return plan(hopId, signature, "ba+*", target, physical, requiresOwnInstruction, nodeKind);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop plan(long hopId, String signature,
		String opcode, PlacementState target, PlacementState physical, boolean requiresOwnInstruction,
		NodeKind nodeKind) {
		return planWithName(hopId, signature, opcode, "value-" + hopId, target, physical,
			requiresOwnInstruction, nodeKind);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop planWithName(long hopId, String signature,
		String opcode, String valueName, PlacementState target, PlacementState physical,
		boolean requiresOwnInstruction, NodeKind nodeKind) {
		return planWithNameAndControlTarget(hopId, signature, opcode, valueName,
			nodeKind == NodeKind.FUNCTION_CALL ? valueName : "-", target, physical,
			requiresOwnInstruction, nodeKind);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop planWithNameAndControlTarget(long hopId,
		String signature, String opcode, String valueName, String controlTarget, PlacementState target,
		PlacementState physical, boolean requiresOwnInstruction, NodeKind nodeKind) {
		return new PlannerRuntimePlacementAudit.PlannedHop(hopId, signature, "DP", "key-" + hopId,
			opcode, nodeKind, "test.dml:1:1-1:1", valueName, controlTarget, List.of(), true,
			new PlacementEmissionState(target, false),
			physical.execType(), physical.output(), physical.fType(), requiresOwnInstruction, false);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop reblockPlan(long hopId, String signature,
		String opcode, PlacementState physical) {
		return new PlannerRuntimePlacementAudit.PlannedHop(hopId, signature, "DP", "key-" + hopId,
			opcode, NodeKind.OPERATION, "test.dml:1:1-1:1", "value-" + hopId, "-", List.of(), true,
			new PlacementEmissionState(physical, false), physical.execType(), physical.output(),
			physical.fType(), true, true);
	}

	private static class AuditCpInstruction extends Instruction {
		private final IType type;

		AuditCpInstruction(String opcode, IType type) {
			super((Operator) null);
			this.type = type;
			instOpcode = opcode;
			instString = opcode;
		}

		void setAuditLocation(long id, String signature) {
			hopID = id;
			setPlannerRecompileSignature(signature);
		}

		@Override public IType getType() { return type; }
		@Override public void processInstruction(ExecutionContext ec) { }
	}

	private static final class AuditFedInstruction extends FEDInstruction {
		AuditFedInstruction(String opcode, FederatedOutput output) {
			super(FEDType.AggregateBinary, null, opcode, opcode, output);
		}

		void setAuditLocation(long id, String signature) {
			hopID = id;
			setPlannerRecompileSignature(signature);
		}

		@Override public void processInstruction(ExecutionContext ec) { }
	}

	private static final class AuditFunctionCallInstruction extends FunctionCallCPInstruction {
		AuditFunctionCallInstruction() {
			super("ns", "fn", false, new CPOperand[0], List.of(), List.of(), "fcall");
		}

		void setAuditLocation(long id, String signature) {
			hopID = id;
			setPlannerRecompileSignature(signature);
		}

		@Override public void processInstruction(ExecutionContext ec) { }
	}
}

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.lops.CumulativeOffsetBinary;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.PickByCount;
import org.apache.sysds.lops.SortKeys;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction.IType;
import org.apache.sysds.runtime.instructions.cp.CPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPInstruction.CPType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Closed contracts for compiler-introduced quantile and cumulative physical stages. */
public class QuantileCumulativeAuxiliaryContractTest {
	private static final PlacementState CP_LOUT = new PlacementState(
		ExecType.CP, FEDInstruction.FederatedOutput.LOUT, null, false);

	@Before
	public void setUp() {
		System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, Boolean.TRUE.toString());
		PlannerRuntimePlacementAudit.resetForTesting();
	}

	@After
	public void tearDown() {
		PlannerRuntimePlacementAudit.resetForTesting();
		System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
		System.clearProperty(PlannerRuntimeCapabilityAudit.PROPERTY);
	}

	@Test
	public void binaryQuantileTagsOnlyQsortAsAnAuxiliaryOfTheLogicalOccurrence() {
		DataOp input = transientMatrix("Q", 24, 1, 1000);
		BinaryOp quantile = new BinaryOp("q", DataType.SCALAR, ValueType.FP64,
			OpOp2.QUANTILE, input, new LiteralOp(0.5));
		quantile.setForcedExecType(ExecType.CP);

		Lop lowered = quantile.constructLops();

		assertTrue(lowered instanceof PickByCount);
		assertEquals(quantile.getHopID(), lowered.getHopID());
		assertNull(lowered.getPlannerLoweringAuxiliaryKind());
		Lop sort = lowered.getInput(0);
		assertTrue(sort instanceof SortKeys);
		assertEquals(quantile.getHopID(), sort.getHopID());
		assertEquals("QUANTILE_SORT", sort.getPlannerLoweringAuxiliaryKind());
	}

	@Test
	public void qsortAndQpickConsumeOneQuantileSelectorState() {
		long hopId = 71;
		String signature = "quantile-owner";
		PlannerRuntimePlacementAudit.installForTesting(List.of(plan(hopId, signature, "quantile")));
		AuditCpInstruction qsort = new AuditCpInstruction("qsort");
		qsort.setAuditLocation(hopId, signature);
		qsort.setPlannerLoweringAuxiliaryKind("QUANTILE_SORT");
		AuditCpInstruction qpick = new AuditCpInstruction("qpick");
		qpick.setAuditLocation(hopId, signature);

		PlannerRuntimePlacementAudit.verifyLowering(
			List.of(), new ArrayList<>(List.of(qsort, qpick)));

		String report = PlannerRuntimePlacementAudit.display();
		assertTrue(report.contains("status=LOWERING_HELPER_MATCH"));
		assertTrue(report.contains("kind=QUANTILE_SORT"));
		assertTrue(report.contains("helperOpcode=qsort"));
		assertTrue(report.contains("opcode=quantile"));
		assertTrue(report.contains("actualIdentity=71/"));
		assertTrue(report.contains("/qsort/CP/LOUT"));
		assertTrue(report.contains("/qpick/CP/LOUT"));
	}

	@Test
	public void sparkCumulativeTagsBcumoffAsACompilerAuxiliaryOfTheUnaryOccurrence() {
		DataOp input = transientMatrix("X", 12, 2, 1000);
		UnaryOp cumulative = new UnaryOp("C", DataType.MATRIX, ValueType.FP64,
			OpOp1.CUMSUM, input);
		setSourcePosition(cumulative);
		cumulative.setForcedExecType(ExecType.SPARK);

		Lop lowered = cumulative.constructLops();

		assertTrue(lowered instanceof CumulativeOffsetBinary);
		assertEquals(cumulative.getHopID(), lowered.getHopID());
		assertEquals("CUMULATIVE_OFFSET", lowered.getPlannerLoweringAuxiliaryKind());
	}

	@Test
	public void sparkCumulativeCascadeRetainsOneLogicalOwnerAndDistinctPhysicalOccurrences() {
		DataOp input = transientMatrix("X", 2_000_000_000_000L, 1_000, 1_000);
		UnaryOp cumulative = new UnaryOp("C", DataType.MATRIX, ValueType.FP64,
			OpOp1.CUMSUM, input);
		setSourcePosition(cumulative);
		cumulative.setForcedExecType(ExecType.SPARK);

		Lop lowered = cumulative.constructLops();
		List<CumulativeOffsetBinary> offsets = collectCumulativeOffsets(lowered);

		assertTrue("Expected a multi-level cumulative offset cascade; offsets=" + offsets.size()
			+ " preaggregateEstimate=" + OptimizerUtils.estimateSize(2_000_000_000L, 1_000)
			+ " localBudget=" + OptimizerUtils.getLocalMemBudget(), offsets.size() >= 2);
		assertEquals(Set.of(cumulative.getHopID()), offsets.stream()
			.map(Lop::getHopID).collect(Collectors.toSet()));
		assertEquals(Set.of(cumulative.getPlannerOriginHopID()), offsets.stream()
			.map(Lop::getPlannerOriginHopID).collect(Collectors.toSet()));
		assertEquals(Set.of(FederatedPlannerUtils.plannerRecompileSignature(cumulative)), offsets.stream()
			.map(Lop::getPlannerRecompileSignature).collect(Collectors.toSet()));
		assertEquals(Set.of("CUMULATIVE_OFFSET"), offsets.stream()
			.map(Lop::getPlannerLoweringAuxiliaryKind).collect(Collectors.toSet()));
		assertEquals("Every physical auxiliary occurrence needs a distinct Lop identity",
			offsets.size(), offsets.stream().map(Lop::getID).distinct().count());
	}

	@Test
	public void cumulativeOffsetWitnessDoesNotPublishAnIndependentSelectorInputState() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		AuditFedInstruction cumulativeOffset = new AuditFedInstruction("bcumoffk+");
		cumulativeOffset.setPlannerLoweringAuxiliaryKind("CUMULATIVE_OFFSET");

		PlannerRuntimeCapabilityAudit.Observation observation =
			PlannerRuntimeCapabilityAudit.begin(cumulativeOffset, null);

		assertEquals("CUMULATIVE_OFFSET",
			observation.instruction().get("plannerLoweringAuxiliaryKind"));
		assertEquals("UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY",
			observation.instruction().get("actualInputSignatureMethod"));
		assertEquals(Map.of(), observation.instruction().get("actualInputSignatures"));
	}

	private static DataOp transientMatrix(String name, long rows, long cols, int blocksize) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, cols > 0 && rows > Long.MAX_VALUE / cols ? -1 : rows * cols,
			blocksize);
	}

	private static void setSourcePosition(UnaryOp hop) {
		hop.setFilename("cumulative-cascade.dml");
		hop.setBeginLine(1);
		hop.setBeginColumn(1);
		hop.setEndLine(1);
		hop.setEndColumn(10);
	}

	private static List<CumulativeOffsetBinary> collectCumulativeOffsets(Lop root) {
		List<CumulativeOffsetBinary> result = new ArrayList<>();
		Set<Lop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		collectCumulativeOffsets(root, visited, result);
		return result;
	}

	private static void collectCumulativeOffsets(Lop current, Set<Lop> visited,
		List<CumulativeOffsetBinary> result) {
		if(current == null || !visited.add(current))
			return;
		if(current instanceof CumulativeOffsetBinary offset)
			result.add(offset);
		for(Lop input : current.getInputs())
			collectCumulativeOffsets(input, visited, result);
	}

	private static PlannerRuntimePlacementAudit.PlannedHop plan(long hopId, String signature,
		String opcode) {
		return new PlannerRuntimePlacementAudit.PlannedHop(hopId, hopId, signature, "DP",
			"key-" + hopId, opcode, NodeKind.OPERATION, "test.dml:1:1-1:1",
			"value-" + hopId, "-", List.of(), true,
			new PlacementEmissionState(CP_LOUT, false), ExecType.CP,
			FEDInstruction.FederatedOutput.LOUT, null, true, false);
	}

	private static final class AuditCpInstruction extends CPInstruction {
		private AuditCpInstruction(String opcode) {
			super(CPType.NoOp, opcode, opcode);
		}

		private void setAuditLocation(long hopId, String signature) {
			this.hopID = hopId;
			setPlannerRecompileSignature(signature);
		}

		@Override public IType getType() { return IType.CONTROL_PROGRAM; }
		@Override public void processInstruction(ExecutionContext ec) { }
	}

	private static final class AuditFedInstruction extends FEDInstruction {
		private AuditFedInstruction(String opcode) {
			super(FEDType.CumsumOffset, null, opcode, opcode, FederatedOutput.NONE);
		}

		@Override public void processInstruction(ExecutionContext ec) { }
	}
}

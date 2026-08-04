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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;
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
		FederatedRefedPolicy.registerFromProgram(null);
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
	public void testTransientReadConcreteFedSourceRejectsLocalOnlySource() {
		DataOp tRead = createLocalMatrix("Y", 100, 10);
		DataOp localSource = createLocalMatrix("Y_local", 100, 10);

		boolean hasConcreteSource = FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(
				tRead, Arrays.asList(localSource));
		assertFalse("Local transient-read source must not be treated as concrete federated source", hasConcreteSource);
	}

	@Test
	public void testTransientReadConcreteFedSourceAcceptsFederatedSource() {
		DataOp tRead = createLocalMatrix("Y", 100, 10);
		DataOp fedSource = createFederatedInput("Xfed", 100, 10);

		boolean hasConcreteSource = FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(
				tRead, Arrays.asList(fedSource));
		assertTrue("Federated transient-read source should be treated as concrete federated source", hasConcreteSource);
	}

	@Test
	public void testTransientReadMappedLocalSourceOverridesGlobalFedVarName() {
		FederatedPlannerUtils.registerFedInitVar("Y");
		DataOp tRead = createLocalMatrix("Y", 100, 10);
		DataOp localSource = createLocalMatrix("Y_local", 100, 10);

		boolean hasConcreteSource = FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(
				tRead, Arrays.asList(localSource));
		assertFalse("Mapped local source must override global fed-init var name for transient-read", hasConcreteSource);
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
		assertEquals("Policy registry must preserve the exact FED consumer",
			List.of(parent.getHopID()), snapshot.get(target.getHopID()).getConsumerHopIds());
		assertEquals("Policy registry must preserve the exact FED consumer input",
			List.of(new ConsumerInputSpec(parent.getHopID(), parent.getInput().indexOf(target))),
			snapshot.get(target.getHopID()).getConsumerInputs());
	}

	@Test
	public void testSingleWorkerFullTransposeIsNotDemotedFromPlannedFout() {
		DataOp full = createFederatedInput("X", 10, 10);
		ReorgOp transpose = new ReorgOp("tX", DataType.MATRIX, ValueType.FP64, ReOrgOp.TRANS, full);
		transpose.setForcedExecType(ExecType.CP);
		transpose.setFederatedOutput(FederatedOutput.FOUT);
		AggBinaryOp consumer = HopRewriteUtils.createMatrixMultiply(transpose, full);
		consumer.setForcedExecType(ExecType.FED);
		consumer.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(full.getHopID(), FType.FULL);
		fTypeMap.put(transpose.getHopID(), FType.FULL);
		fTypeMap.put(consumer.getHopID(), FType.FULL);

		FederatedRefedPolicy.registerFromHops(List.of(consumer), true, fTypeMap, -1L);

		assertEquals("A single-range FULL input is supported by ReorgFEDInstruction and must not close the FOUT plan",
			FederatedOutput.FOUT, transpose.getFederatedOutput());
		assertTrue("The selected FULL transpose must have an explicit planner-owned FOUT lowering receipt",
			FederatedFoutMaterializeRegistry.snapshot(-1L).containsKey(transpose.getHopID())
				|| FederatedRefedRegistry.snapshot(-1L).containsKey(transpose.getHopID()));
	}

	@Test
	public void testSequentialCompatibleRefedRegistrationsMergeExactConsumers() {
		FederatedRefedRegistry.register(-1L, 100L, 200L, "fedinit://workers|ROW", List.of(301L));
		FederatedRefedRegistry.register(-1L, 100L, 200L, "fedinit://workers|ROW", List.of(302L, 301L));

		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(-1L).get(100L);
		assertEquals("Compatible repeated registration must preserve anchor hop", 200L, spec.getAnchorHopId());
		assertEquals("Compatible repeated registration must preserve durable anchor key",
			"fedinit://workers|ROW", spec.getAnchorKey());
		assertEquals("Compatible repeated registration must union exact consumers",
			List.of(301L, 302L), spec.getConsumerHopIds());
	}

	@Test
	public void testDisjointConsumerSpecificRefedLayoutsRemainExact() {
		String fullAnchor = "worker1:8001;|0,0,50000,2100;|FULL";
		FederatedRefedRegistry.register(-1L, 100L, 200L, fullAnchor, FType.FULL, List.of(301L));
		FederatedRefedRegistry.register(-1L, 100L, 200L, fullAnchor, FType.BROADCAST, List.of(302L));

		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(-1L).get(100L);
		assertEquals("One source must retain both selected consumer-specific uploads", 2,
			spec.getAuthorities().size());
		assertEquals(List.of(FType.FULL, FType.BROADCAST), spec.getAuthorities().stream()
			.map(FederatedRefedRegistry.AuthoritySpec::getMaterializationFType).sorted().toList());
		assertEquals(List.of(301L, 302L), spec.getConsumerHopIds());
		assertEquals(List.of(List.of(302L), List.of(301L)), spec.getAuthorities().stream()
			.map(FederatedRefedRegistry.AuthoritySpec::getConsumerHopIds).toList());

		Map<Long, FederatedRefedRegistry.AnchorSpec> before = FederatedRefedRegistry.snapshot(-1L);
		try {
			FederatedRefedRegistry.register(-1L, 100L, 200L, fullAnchor,
				FType.BROADCAST, List.of(301L));
			throw new AssertionError("Expected one consumer with two incompatible layouts to fail closed");
		}
		catch(IllegalArgumentException ex) {
			assertTrue("Expected exact consumer-authority conflict: " + ex.getMessage(),
				ex.getMessage().contains("conflicting fed_refed anchor authority"));
		}
		assertEquals("Rejected overlapping authority must not mutate registry", before,
			FederatedRefedRegistry.snapshot(-1L));
	}

	@Test
	public void testExactRegistrationCannotBeAbsorbedByLegacyConsumerWildcard() {
		FederatedRefedRegistry.register(-1L, 100L, 200L, "fedinit://workers|ROW",
			FType.ROW, List.of(301L));
		Map<Long, FederatedRefedRegistry.AnchorSpec> before = FederatedRefedRegistry.snapshot(-1L);

		assertThrows("An exact input occurrence must not be canonicalized into a legacy consumer wildcard",
			IllegalArgumentException.class,
			() -> FederatedRefedRegistry.registerConsumerInputs(-1L, 100L, 200L,
				"fedinit://workers|ROW", FType.ROW, List.of(new ConsumerInputSpec(301L, 0))));
		assertEquals("Rejected wildcard/exact overlap must be atomic", before,
			FederatedRefedRegistry.snapshot(-1L));

		assertThrows("The exact API itself must reject ALL_INPUTS",
			IllegalArgumentException.class,
			() -> FederatedRefedRegistry.registerConsumerInputs(-1L, 101L, 200L,
				"fedinit://workers|ROW", FType.ROW,
				List.of(new ConsumerInputSpec(302L, ConsumerInputSpec.ALL_INPUTS))));
		assertEquals("Rejected exact-API wildcard must not mutate any registry slot", before,
			FederatedRefedRegistry.snapshot(-1L));
	}

	@Test
	public void testExactRefedEdgeDoesNotSuppressCrossAnchorRelocation() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);

		DataOp firstAnchor = createFederatedInput("A1", 10, 10);
		BinaryOp firstConsumer = HopRewriteUtils.createBinary(target, firstAnchor, OpOp2.PLUS);
		firstConsumer.setForcedExecType(ExecType.FED);
		DataOp secondAnchor = createFederatedInput("A2", 10, 10);
		BinaryOp secondConsumer = HopRewriteUtils.createBinary(target, secondAnchor, OpOp2.MINUS);
		secondConsumer.setForcedExecType(ExecType.FED);

		String firstDurableAnchor = "fedinit://pool-a|ROW";
		String secondDurableAnchor = "fedinit://pool-b|COL";
		FederatedPlannerUtils.registerFedAnchorKey("A1", firstDurableAnchor);
		FederatedPlannerUtils.registerFedAnchorKey("A2", secondDurableAnchor);
		FederatedRefedRegistry.registerConsumerInputs(-1L, target.getHopID(), firstAnchor.getHopID(),
			firstDurableAnchor, FType.ROW,
			List.of(new ConsumerInputSpec(firstConsumer.getHopID(), firstConsumer.getInput().indexOf(target))));

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(firstAnchor.getHopID(), FType.ROW);
		fTypeMap.put(secondAnchor.getHopID(), FType.COL);
		FederatedRefedPolicy.registerFromHops(List.of(secondConsumer), false, fTypeMap, -1L);

		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(-1L).get(target.getHopID());
		assertEquals("The two exact edges require distinct physical relocations", 2,
			spec.getAuthorities().size());
		assertTrue("The original exact edge must retain its ROW authority",
			spec.getAuthorities().stream().anyMatch(authority -> firstDurableAnchor.equals(authority.getAnchorKey())
				&& authority.getMaterializationFType() == FType.ROW
				&& authority.getConsumerInputs().contains(new ConsumerInputSpec(firstConsumer.getHopID(), 0))));
		assertTrue("The new edge must relocate to its COL consumer anchor instead of reusing ROW authority",
			spec.getAuthorities().stream().anyMatch(authority -> secondDurableAnchor.equals(authority.getAnchorKey())
				&& authority.getMaterializationFType() == FType.COL
				&& authority.getConsumerInputs().contains(new ConsumerInputSpec(secondConsumer.getHopID(), 0))));
	}

	@Test
	public void testEquivalentDurableAuthorityMergesConsumersWithoutStaleHopOverride() {
		FederatedRefedRegistry.register(-1L, 100L, 200L, "fedinit://workers|ROW", List.of(301L));
		FederatedRefedRegistry.register(-1L, 100L, 201L, "fedinit://workers|ROW", List.of(302L));

		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(-1L).get(100L);
		assertEquals("Equivalent durable authority must discard disagreeing live Hop-id hints", -1L,
			spec.getAnchorHopId());
		assertEquals("Equivalent durable authority must merge exact consumers", List.of(301L, 302L),
			spec.getConsumerHopIds());
	}

	@Test
	public void testSequentialPolicyRegistrationsMergeNewExactConsumer() {
		DataOp localLhs = createLocalMatrix("L", 10, 10);
		DataOp localRhs = createLocalMatrix("R", 10, 10);
		Hop target = HopRewriteUtils.createBinary(localLhs, localRhs, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DataOp anchor = createFederatedInput("A", 10, 10);
		BinaryOp firstParent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		firstParent.setForcedExecType(ExecType.FED);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(List.of(firstParent), true, fTypeMap, -1L);
		assertEquals(List.of(firstParent.getHopID()), FederatedRefedRegistry.snapshot(-1L)
			.get(target.getHopID()).getConsumerHopIds());
		assertEquals(List.of(new ConsumerInputSpec(firstParent.getHopID(), firstParent.getInput().indexOf(target))),
			FederatedRefedRegistry.snapshot(-1L).get(target.getHopID()).getConsumerInputs());

		BinaryOp secondParent = HopRewriteUtils.createBinary(target, anchor, OpOp2.MINUS);
		secondParent.setForcedExecType(ExecType.FED);
		FederatedRefedPolicy.registerFromHops(List.of(secondParent), false, fTypeMap, -1L);

		assertEquals("Repeated policy registration must merge the newly selected exact consumer",
			List.of(firstParent.getHopID(), secondParent.getHopID()).stream().sorted().toList(),
			FederatedRefedRegistry.snapshot(-1L).get(target.getHopID()).getConsumerHopIds());
		assertEquals("Repeated policy registration must merge exact consumer inputs without a wildcard",
			List.of(
				new ConsumerInputSpec(firstParent.getHopID(), firstParent.getInput().indexOf(target)),
				new ConsumerInputSpec(secondParent.getHopID(), secondParent.getInput().indexOf(target)))
				.stream().sorted().toList(),
			FederatedRefedRegistry.snapshot(-1L).get(target.getHopID()).getConsumerInputs());
	}

	@Test
	public void testSequentialConflictingRefedRegistrationFailsWithoutMutation() {
		FederatedRefedRegistry.register(-1L, 100L, 200L, "fedinit://workers-a|ROW", List.of(301L));
		Map<Long, FederatedRefedRegistry.AnchorSpec> before = FederatedRefedRegistry.snapshot(-1L);

		try {
			FederatedRefedRegistry.register(-1L, 100L, 201L, "fedinit://workers-b|ROW", List.of(301L));
			throw new AssertionError("Expected conflicting repeated anchor authority to fail closed");
		}
		catch (IllegalArgumentException ex) {
			assertTrue("Expected anchor-authority conflict: " + ex.getMessage(),
				ex.getMessage().contains("conflicting fed_refed anchor authority"));
		}
		assertEquals("Rejected conflicting registration must not mutate registry", before,
			FederatedRefedRegistry.snapshot(-1L));
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
	public void testPlannedBroadcastCanBeOverriddenByAlignedAnchor() {
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
		Map<Long, FederatedRefedRegistry.AnchorSpec> refedSnapshot = FederatedRefedRegistry.snapshot(-1);
		assertTrue("Expected no materialize registry entry when aligned anchor is available",
			!materializeSnapshot.containsKey(target.getHopID()));
		assertTrue("Expected refed registry entry for aligned anchor override",
			refedSnapshot.containsKey(target.getHopID()));
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
		assertEquals("Policy registry must select only the FED parent",
			List.of(fedParent.getHopID()), FederatedRefedRegistry.snapshot(-1)
				.get(target.getHopID()).getConsumerHopIds());
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
		DataOp localTail = createLocalMatrix("tail", 1, 10);
		AggBinaryOp fedConsumer = HopRewriteUtils.createMatrixMultiply(fedParent, localTail);
		fedConsumer.setForcedExecType(ExecType.FED);
		fedConsumer.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);
		fTypeMap.put(localVector.getHopID(), FType.BROADCAST);
		fTypeMap.put(fedParent.getHopID(), FType.ROW);
		fTypeMap.put(localTail.getHopID(), FType.BROADCAST);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedConsumer), true, fTypeMap, -1);
		assertEquals("A native ROW-fed AggBinary vector result required as FOUT must not be demoted",
			FederatedOutput.FOUT, fedParent.getFederatedOutput());
		assertEquals("Expected optional BROADCAST vector input to remain local",
			FederatedOutput.LOUT, localVector.getFederatedOutput());
		assertTrue("Expected no refed entry for optional BROADCAST vector input",
			!FederatedRefedRegistry.snapshot(-1).containsKey(localVector.getHopID()));
		assertTrue("Expected no fed_fout materialization for optional BROADCAST vector input",
			!FederatedFoutMaterializeRegistry.snapshot(-1).containsKey(localVector.getHopID()));
	}

	@Test
	public void testNativeAggBinaryFoutVectorSurvivesTransientWriteDemand() {
		DataOp fedMatrix = createFederatedInput("XnativeVector", 100, 10);
		DataOp localVector = createLocalMatrix("vNativeVector", 10, 1);
		localVector.setForcedExecType(ExecType.CP);
		localVector.setFederatedOutput(FederatedOutput.LOUT);

		AggBinaryOp fedProduct = HopRewriteUtils.createMatrixMultiply(fedMatrix, localVector);
		fedProduct.setDim1(100);
		fedProduct.setDim2(1);
		fedProduct.setForcedExecType(ExecType.FED);
		fedProduct.setFederatedOutput(FederatedOutput.FOUT);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("YnativeVector", fedProduct);
		tWrite.setDim1(100);
		tWrite.setDim2(1);
		tWrite.setForcedExecType(ExecType.FED);
		tWrite.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);
		fTypeMap.put(fedProduct.getHopID(), FType.ROW);
		fTypeMap.put(tWrite.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(tWrite), true, fTypeMap, -1);

		assertEquals("A planner-authorized native AggBinary FOUT must survive when a transient write"
				+ " requires the federated representation",
			FederatedOutput.FOUT, fedProduct.getFederatedOutput());
		assertEquals("The transient write must retain the same federated representation",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
	}

	@Test
	public void testNativeReplicatedAggBinaryFoutSurvivesTransientWriteDemand() {
		DataOp left = createFederatedInput("LnativeReplicated", 10, 10);
		DataOp right = createFederatedInput("RnativeReplicated", 10, 10);
		AggBinaryOp product = HopRewriteUtils.createMatrixMultiply(left, right);
		product.setDim1(10);
		product.setDim2(10);
		product.setForcedExecType(ExecType.FED);
		product.setFederatedOutput(FederatedOutput.FOUT);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("YnativeReplicated", product);
		tWrite.setDim1(10);
		tWrite.setDim2(10);
		tWrite.setForcedExecType(ExecType.FED);
		tWrite.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(left.getHopID(), FType.BROADCAST);
		fTypeMap.put(right.getHopID(), FType.BROADCAST);
		fTypeMap.put(product.getHopID(), FType.BROADCAST);
		fTypeMap.put(tWrite.getHopID(), FType.BROADCAST);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(tWrite), true, fTypeMap, -1);

		assertEquals("A native replicated AggBinary FOUT must survive when a transient write"
				+ " requires the federated representation",
			FederatedOutput.FOUT, product.getFederatedOutput());
		assertEquals("The transient write must retain the same replicated representation",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
	}

	@Test
	public void testLeftTransposeRewriteKeepsFoutRhsTransposeFederatedUnderLoutParent() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.COMPRESSED_LINALG, "false");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			DataOp x = createFederatedInput("X", 100000, 100);
			Hop tX = HopRewriteUtils.createTranspose(x);
			tX.setForcedExecType(ExecType.FED);
			tX.setFederatedOutput(FederatedOutput.FOUT);
			DataOp y = createFederatedInput("Y", 100000, 2);
			AggBinaryOp parent = HopRewriteUtils.createMatrixMultiply(tX, y);
			parent.setForcedExecType(ExecType.FED);
			parent.setFederatedOutput(FederatedOutput.LOUT);

			assertTrue("Expected the FED left-transpose rewrite fixture to be applicable",
				parent.usesLeftTransposeRewrite(ExecType.FED));
			Lop outerTranspose = parent.constructLops();
			Lop innerMultiply = outerTranspose.getInputs().get(0);
			Lop rhsTranspose = innerMultiply.getInputs().get(0);
			assertEquals("The rewritten RHS transpose must preserve its FOUT input representation",
				FederatedOutput.FOUT, rhsTranspose.getFederatedOutput());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
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
	public void testPlannerAllowsOptionalLocalVectorInputWithoutStandaloneAnchor() {
		DataOp fedMatrix = createFederatedInput("X", 100, 10);
		DataOp localA = createLocalMatrix("A", 100, 1);
		DataOp localB = createLocalMatrix("B", 100, 1);

		BinaryOp localVector = HopRewriteUtils.createBinary(localA, localB, OpOp2.PLUS);
		localVector.setDim1(100);
		localVector.setDim2(1);
		localVector.setForcedExecType(ExecType.CP);
		localVector.setFederatedOutput(FederatedOutput.LOUT);

		BinaryOp fedParent = HopRewriteUtils.createBinary(fedMatrix, localVector, OpOp2.DIV);
		fedParent.setDim1(100);
		fedParent.setDim2(10);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedMatrix.getHopID(), FType.ROW);

		assertEquals("Expected vector RHS to be classified as OPTIONAL for FED execution",
			FederatedRefedPolicy.InputRequirement.OPTIONAL,
			FederatedRefedPolicy.getInputRequirementForFedExec(fedParent, localVector, 1, fTypeMap));
		assertTrue("Expected optional local vector input to be materializable via parent FED anchor",
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
		assertEquals("A materialized TWrite must use the legal FED/FOUT boundary pair",
			ExecType.FED, tWrite.getForcedExecType());
	}

	@Test
	public void testFedFoutTransientWriteMaterializationSurvivesLiveFedTransientReadCleanup() throws Exception {
		long sbId = 17017L;
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();

		DataOp tRead = createLocalMatrix("is_row_in_samples_like", 3000, 1);
		tRead.setBeginLine(110);
		tRead.setForcedExecType(ExecType.FED);
		tRead.setFederatedOutput(FederatedOutput.FOUT);

		UnaryOp fedParent = HopRewriteUtils.createUnary(tRead, OpOp1.EXP);
		fedParent.setDim1(3000);
		fedParent.setDim2(1);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		DataOp tWriteInput = createLocalMatrix("is_row_local_src", 3000, 1);
		tWriteInput.setForcedExecType(ExecType.CP);
		tWriteInput.setFederatedOutput(FederatedOutput.LOUT);
		DataOp tWrite = createTransientWrite("is_row_in_samples_like", tWriteInput, 3000, 1);
		tWrite.setBeginLine(69);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.FOUT);

		DataOp fedAnchor = createFederatedInput("X_anchor", 3000, 1);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedAnchor.getHopID(), FType.ROW);
		fTypeMap.put(tWrite.getHopID(), FType.ROW);
		fTypeMap.put(tRead.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFromHops(Arrays.asList(fedParent, tWrite, fedAnchor), true, fTypeMap, sbId);

		assertEquals("Live FED transient read must not be demoted after its FED/FOUT TWrite materialization is kept",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("Live FED transient read must remain FOUT",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
		assertEquals("FED/FOUT transient write must remain a materialized federated source for the live TRead",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
		assertEquals("Planner must repair the illegal CP/FOUT seed to FED/FOUT",
			ExecType.FED, tWrite.getForcedExecType());
		assertTrue("Expected materialize registry entry to survive cleanup for the live transient-read consumer",
			FederatedFoutMaterializeRegistry.snapshot(sbId).containsKey(tWrite.getHopID()));

		java.lang.reflect.Method stillNeeds = FederatedRefedPolicy.class.getDeclaredMethod(
			"stillNeedsRegisteredFederatedUpload", Hop.class, Map.class, java.util.List.class);
		stillNeeds.setAccessible(true);
		assertTrue("Prune pass must defer FED/FOUT TWrite materialization liveness to stale-TWrite cleanup",
			(Boolean) stillNeeds.invoke(null, tWrite, fTypeMap, Arrays.asList(tWrite, fedAnchor)));
		java.lang.reflect.Method hasMaterialize = FederatedRefedPolicy.class.getDeclaredMethod(
			"hasRegisteredTransientWriteMaterialize", long.class, DataOp.class, Map.class);
		hasMaterialize.setAccessible(true);
		assertTrue("TRead cleanup must find TWrite materialization registered under the TWrite statement block",
			(Boolean) hasMaterialize.invoke(null, sbId + 1, tWrite, new HashMap<Long, Object>()));

		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	@Test
	public void testRecompileRuntimeSignatureRegistersRequiredRefedWithoutChangingPlan() {
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

		assertEquals("Runtime signature must not override the planner-selected TRead exec type",
			ExecType.CP, tRead.getForcedExecType());
		assertEquals("Runtime signature must not override the planner-selected TRead output",
			FederatedOutput.LOUT, tRead.getFederatedOutput());
		assertEquals("Runtime lowering must not promote a planner-selected local TWrite",
			FederatedOutput.LOUT, tWrite.getFederatedOutput());
		assertTrue("The FED parent must receive an explicit planner-compatible upload input",
			FederatedRefedRegistry.snapshot(-1).containsKey(tRead.getHopID())
				|| FederatedFoutMaterializeRegistry.snapshot(-1).containsKey(tRead.getHopID()));
	}

	@Test
	public void testRuntimeRecompileRejectsIllegalCpFoutTWriteWithoutRepair() {
		DataOp local = createLocalMatrix("local", 10, 10);
		local.setForcedExecType(ExecType.CP);
		local.setFederatedOutput(FederatedOutput.LOUT);
		DataOp tWrite = createTransientWrite("Y", local, 10, 10);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.FOUT);
		Map<String, String> signatures = new HashMap<>();
		signatures.put("X", "worker1:8001/data/X;||FULL");

		assertThrows("Runtime recompile must reject, not repair, an illegal CP/FOUT TWrite",
			DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(List.of(tWrite)), true,
				new HashMap<>(), 72L, signatures, new HashMap<>()));
		assertEquals("Rejected runtime plan must keep its original exec marker for diagnostics",
			ExecType.CP, tWrite.getForcedExecType());
		assertEquals("Rejected runtime plan must keep its original output marker for diagnostics",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
		assertFalse("Rejected runtime plan must not publish materialization",
			FederatedFoutMaterializeRegistry.snapshot(72L).containsKey(tWrite.getHopID()));
		assertFalse("Rejected runtime plan must not populate the CP/FOUT anchor cache",
			FederatedRefedPolicy.snapshotCpfoutAnchorCache().containsKey(tWrite.getHopID()));
	}

	@Test
	public void testTransientFoutObligationNeverExposesCpFoutBoundary() {
		DataOp anchor = createFederatedInput("X", 10, 10);
		DataOp tRead = createLocalMatrix("R", 10, 10);
		tRead.setForcedExecType(ExecType.CP);
		tRead.setFederatedOutput(FederatedOutput.LOUT);
		BinaryOp readConsumer = HopRewriteUtils.createBinary(tRead, anchor, OpOp2.PLUS);
		readConsumer.setForcedExecType(ExecType.FED);
		readConsumer.setFederatedOutput(FederatedOutput.FOUT);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);

		FederatedRefedPolicy.registerFoutMaterializeObligation(tRead, List.of(readConsumer), fTypeMap, 73L);
		assertEquals("TRead obligation must retain the legal CP/LOUT boundary", ExecType.CP,
			tRead.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, tRead.getFederatedOutput());
		assertTrue("TRead obligation must be represented by an exact REFED edge, not CP/FOUT",
			FederatedRefedRegistry.snapshot(73L).containsKey(tRead.getHopID()));

		DataOp local = createLocalMatrix("local", 10, 10);
		local.setForcedExecType(ExecType.CP);
		local.setFederatedOutput(FederatedOutput.LOUT);
		DataOp tWrite = createTransientWrite("W", local, 10, 10);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.LOUT);
		BinaryOp writeConsumer = HopRewriteUtils.createBinary(tWrite, anchor, OpOp2.PLUS);
		writeConsumer.setForcedExecType(ExecType.FED);
		writeConsumer.setFederatedOutput(FederatedOutput.FOUT);
		FederatedRefedPolicy.registerFoutMaterializeObligation(tWrite, List.of(writeConsumer), fTypeMap, 74L);
		assertEquals("TWrite obligation must restore the legal selected CP/LOUT boundary", ExecType.CP,
			tWrite.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, tWrite.getFederatedOutput());
		assertTrue("TWrite obligation must record its exact materialization without CP/FOUT markers",
			FederatedFoutMaterializeRegistry.snapshot(74L).containsKey(tWrite.getHopID()));
	}

	@Test
	public void testTransientFoutObligationFailsAtomicallyWithoutExactAnchor() {
		DataOp local = createLocalMatrix("local", 10, 10);
		DataOp tWrite = createTransientWrite("W", local, 10, 10);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.LOUT);

		assertThrows("Missing exact anchor must fail before any transient-boundary mutation",
			DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFoutMaterializeObligation(
				tWrite, List.of(), new HashMap<>(), 75L));
		assertEquals(ExecType.CP, tWrite.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, tWrite.getFederatedOutput());
		assertFalse(FederatedRefedPolicy.snapshotCpfoutAnchorCache().containsKey(tWrite.getHopID()));
		assertFalse(FederatedRefedRegistry.snapshot(75L).containsKey(tWrite.getHopID()));
		assertFalse(FederatedFoutMaterializeRegistry.snapshot(75L).containsKey(tWrite.getHopID()));
	}

	@Test
	public void testRuntimeObservedLocalTransientReadUsesExactRefedEdgeForFedParent() {
		DataOp localState = createLocalMatrix("S", 3000, 50);
		localState.setForcedExecType(ExecType.CP);
		localState.setFederatedOutput(FederatedOutput.LOUT);

		UnaryOp fedParent = HopRewriteUtils.createUnary(localState, OpOp1.EXP);
		fedParent.setDim1(3000);
		fedParent.setDim2(50);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X",
			"worker1:8001/data/X_1;worker2:8002/data/X_2;|0,1500;1500,3000;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);
		runtimeTypes.put("S", null);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(fedParent)),
			true, fTypeMap, 16L, runtimeSignatures, runtimeTypes);

		FederatedRefedRegistry.AnchorSpec refed =
			FederatedRefedRegistry.snapshot(16L).get(localState.getHopID());
		assertTrue("The observed-local TRead must retain an exact REFED edge for its selected FED consumer",
			refed != null && refed.getConsumerHopIds().equals(Arrays.asList(fedParent.getHopID())));
		assertEquals("REFED lowering must not change the TRead placement marker",
			ExecType.CP, localState.getForcedExecType());
		assertEquals("REFED lowering must keep the TRead output local",
			FederatedOutput.LOUT, localState.getFederatedOutput());
	}

	@Test
	public void testRuntimeRecompileDerivedFedSiblingKeepsConcreteRowAnchorType() {
		DataOp x = createLocalMatrix("X", 50000, 2100);
		x.setForcedExecType(ExecType.FED);
		x.setFederatedOutput(FederatedOutput.FOUT);

		UnaryOp derivedFedSibling = HopRewriteUtils.createUnary(x, OpOp1.EXP);
		derivedFedSibling.setDim1(50000);
		derivedFedSibling.setDim2(10);
		derivedFedSibling.setForcedExecType(ExecType.FED);
		derivedFedSibling.setFederatedOutput(FederatedOutput.FOUT);

		DataOp u = createLocalMatrix("U", 50000, 10);
		DataOp rowNonzeros = createLocalMatrix("row_nonzeros", 50000, 1);
		BinaryOp localRegularization = HopRewriteUtils.createBinary(u, rowNonzeros, OpOp2.MULT);
		localRegularization.setDim1(50000);
		localRegularization.setDim2(10);
		localRegularization.setForcedExecType(ExecType.CP);
		localRegularization.setFederatedOutput(FederatedOutput.LOUT);

		BinaryOp fedParent = HopRewriteUtils.createBinary(
			derivedFedSibling, localRegularization, OpOp2.PLUS);
		fedParent.setDim1(50000);
		fedParent.setDim2(10);
		fedParent.setForcedExecType(ExecType.FED);
		fedParent.setFederatedOutput(FederatedOutput.FOUT);

		String rowSignature =
			"worker1:8001;worker2:8002;|0,25000;25000,50000;";
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW, rowSignature);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(x.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X", rowSignature);
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);

		FederatedRefedPolicy.registerFromHops(
			new java.util.ArrayList<>(Arrays.asList(fedParent)), true, fTypeMap, 14L,
			runtimeSignatures, runtimeTypes);

		FederatedRefedRegistry.AnchorSpec refed =
			FederatedRefedRegistry.snapshot(14L).get(localRegularization.getHopID());
		assertTrue("Expected the local regularization term to be explicitly refederated", refed != null);
		assertEquals("A derived FED sibling must retain the concrete source FederationMap type",
			rowSignature + "|ROW", refed.getAnchorKey());
	}

	@Test
	public void testRuntimeRecompileRegistersDerivedFoutProducerBeforeFedConsumer() {
		DataOp x = createFederatedInput("X", 100, 20);
		UnaryOp derived = HopRewriteUtils.createUnary(x, OpOp1.EXP);
		derived.setDim1(100);
		derived.setDim2(20);
		derived.setForcedExecType(ExecType.FED);
		derived.setFederatedOutput(FederatedOutput.FOUT);
		derived.setFederatedOutputDerived(true);

		UnaryOp fedConsumer = HopRewriteUtils.createUnary(derived, OpOp1.SQRT);
		fedConsumer.setDim1(100);
		fedConsumer.setDim2(20);
		fedConsumer.setForcedExecType(ExecType.FED);
		fedConsumer.setFederatedOutput(FederatedOutput.FOUT);

		String rowSignature = "worker1:8001/data/X_1;worker2:8002/data/X_2;|0,50;50,100;";
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW, rowSignature);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(x.getHopID(), FType.ROW);
		fTypeMap.put(derived.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X", rowSignature);
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);

		FederatedRefedPolicy.registerFromHops(
			new java.util.ArrayList<>(Arrays.asList(fedConsumer)), true, fTypeMap, 17L,
			runtimeSignatures, runtimeTypes);

		assertTrue("Derived FED/FOUT is local until its exact REFED/FOUT receipt is rebuilt",
			FederatedRefedRegistry.snapshot(17L).containsKey(derived.getHopID())
				|| FederatedFoutMaterializeRegistry.snapshot(17L).containsKey(derived.getHopID()));
		assertEquals("Runtime lowering must preserve the selected derived-FOUT bit", true,
			derived.isFederatedOutputDerived());
		assertEquals("The selected consumer must remain FED after its input receipt is rebuilt",
			ExecType.FED, fedConsumer.getForcedExecType());
	}

	@Test
	public void testRuntimeSignatureWithoutTypeDoesNotFabricateFullAnchorKey() {
		DataOp local = createLocalMatrix("local", 10, 10);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X", "worker1:8001/data/X;|");

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(local)), true,
			new HashMap<>(), -1L, runtimeSignatures, new HashMap<>());

		assertTrue("Unknown runtime FType must not publish a fabricated FULL literal anchor",
			FederatedPlannerUtils.getFedAnchorKey("X") == null);
		assertTrue("Unknown runtime FType must not publish REFED authority",
			FederatedRefedRegistry.isEmpty());
	}

	@Test
	public void testRegisterFedInitVarClearsStaleTypeAndAcceptsEncodedFull() {
		String oldSignature = "worker1:8001/data/old;|";
		String unknownSignature = "worker1:8001/data/unknown;|";
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW, oldSignature);
		FederatedPlannerUtils.registerFedInitVar("X", null, unknownSignature);
		assertTrue("An explicit untyped signature must clear stale placement type",
			FederatedPlannerUtils.getFedInitFType("X") == null);
		assertTrue("An explicit untyped signature must clear stale literal authority",
			FederatedPlannerUtils.getFedAnchorKey("X") == null);

		String encodedFull = "worker1:8001/data/X;||FULL";
		FederatedPlannerUtils.registerFedInitVar("X", null, encodedFull);
		assertEquals("Encoded worker=1 FULL provenance remains exact", FType.FULL,
			FederatedPlannerUtils.getFedInitFType("X"));
		assertEquals(encodedFull, FederatedPlannerUtils.getFedAnchorKey("X"));
	}

	@Test
	public void testDerivedFederatedHopWithoutTypeDoesNotBuildLiteralAnchorKey() throws Exception {
		String signature = "worker1:8001/data/X;|";
		FederatedPlannerUtils.registerFedInitVar("X", null, signature);
		DataOp source = createFederatedInput("X", 10, 10);
		UnaryOp derived = HopRewriteUtils.createUnary(source, OpOp1.EXP);
		derived.setDim1(10);
		derived.setDim2(10);
		derived.setForcedExecType(ExecType.FED);
		derived.setFederatedOutput(FederatedOutput.FOUT);

		java.lang.reflect.Method buildAnchorKey = FederatedRefedPolicy.class.getDeclaredMethod(
			"buildAnchorKey", Hop.class, Map.class, Set.class);
		buildAnchorKey.setAccessible(true);
		Object anchorKey = buildAnchorKey.invoke(null, derived, new HashMap<>(), new java.util.HashSet<Long>());
		assertTrue("A signature without observed or encoded FType cannot authorize literal refederation",
			anchorKey == null);
	}

	@Test
	public void testKnownSingleWorkerFullStillPublishesExactLiteralAnchor() throws Exception {
		String signature = "worker1:8001/data/X;|";
		FederatedPlannerUtils.registerFedInitVar("X", FType.FULL, signature);
		assertEquals("Known worker=1 FULL provenance must remain supported",
			signature + "|FULL", FederatedPlannerUtils.getFedAnchorKey("X"));

		DataOp source = createFederatedInput("X", 10, 10);
		UnaryOp derived = HopRewriteUtils.createUnary(source, OpOp1.EXP);
		derived.setForcedExecType(ExecType.FED);
		derived.setFederatedOutput(FederatedOutput.FOUT);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(source.getHopID(), FType.FULL);
		java.lang.reflect.Method buildAnchorKey = FederatedRefedPolicy.class.getDeclaredMethod(
			"buildAnchorKey", Hop.class, Map.class, Set.class);
		buildAnchorKey.setAccessible(true);
		Object anchorKey = buildAnchorKey.invoke(null, derived, fTypeMap, new java.util.HashSet<Long>());
		java.lang.reflect.Field value = anchorKey.getClass().getDeclaredField("value");
		value.setAccessible(true);
		assertEquals(signature + "|FULL", value.get(anchorKey));
	}

	@Test
	public void testEncodedSingleWorkerFullRuntimeSignaturePublishesExactAnchor() {
		String encodedFull = "worker1:8001/data/X;||FULL";
		Map<String, String> signatures = new HashMap<>();
		signatures.put("X", encodedFull);
		FederatedRefedPolicy.registerFromHops(List.of(createLocalMatrix("local", 10, 10)), true,
			new HashMap<>(), -1L, signatures, new HashMap<>());
		assertEquals("A concrete encoded FULL is valid even without a separate runtime type map",
			encodedFull, FederatedPlannerUtils.getFedAnchorKey("X"));
	}

	@Test
	public void testConflictingEncodedAndObservedTypeFailsClosed() {
		String encodedFull = "worker1:8001/data/X;||FULL";
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW, encodedFull);
		assertTrue("Conflicting encoded and observed types must clear the stored type",
			FederatedPlannerUtils.getFedInitFType("X") == null);
		assertTrue("Conflicting encoded and observed types must not publish literal authority",
			FederatedPlannerUtils.getFedAnchorKey("X") == null);
	}

	@Test
	public void testSyntheticRuntimeAnchorDoesNotPickArbitraryFirstPlacement() throws Exception {
		Map<String, String> signatures = new HashMap<>();
		signatures.put("A", "worker1:8001/data/A;|0,10;");
		signatures.put("B", "worker2:8002/data/B;|0,10;");
		Map<String, FType> types = new HashMap<>();
		types.put("A", FType.ROW);
		types.put("B", FType.ROW);
		java.lang.reflect.Method synthetic = FederatedRefedPolicy.class.getDeclaredMethod(
			"buildSyntheticAnchorSelection", List.class, Map.class, Map.class, Map.class);
		synthetic.setAccessible(true);
		Object selection = synthetic.invoke(null, List.of(), new HashMap<Long, FType>(), signatures, types);
		assertTrue("Distinct runtime placements must not be reduced to map iteration order", selection == null);
	}

	@Test
	public void testRawUntypedTransientAnchorIsNotReturnedAsLiteralAuthority() throws Exception {
		FederatedPlannerUtils.registerFedInitVar("X");
		FederatedPlannerUtils.registerFedAnchorKey("X", "worker1:8001/data/X;|");
		DataOp source = createFederatedInput("X", 10, 10);
		java.lang.reflect.Method buildAnchorKey = FederatedRefedPolicy.class.getDeclaredMethod(
			"buildAnchorKey", Hop.class, Map.class, Set.class);
		buildAnchorKey.setAccessible(true);
		Object anchorKey = buildAnchorKey.invoke(null, source, new HashMap<>(), new java.util.HashSet<Long>());
		assertTrue("An untyped raw transient anchor must not re-enter literal REFED authority", anchorKey == null);
	}

	@Test
	public void testSignatureOnlyCpfoutRejectsTWriteAndNonTWriteWithoutMutation() throws Exception {
		String untypedSignature = "worker1:8001/data/X;|";
		DataOp local = createLocalMatrix("local", 10, 10);
		assertCpfoutLiteralAnchorRejected(local, untypedSignature);

		DataOp tWrite = createTransientWrite("Y", local, 10, 10);
		assertCpfoutLiteralAnchorRejected(tWrite, untypedSignature);
		assertTrue("Rejected TWrite must not publish an untyped anchor",
			FederatedPlannerUtils.getFedAnchorKey("Y") == null);
	}

	@Test
	public void testUnknownRequiredAndOptionalParentAnchorsDoNotBecomeFull() throws Exception {
		DataOp unknown = createFederatedInput("unknown", 10, 10);
		DataOp local = createLocalMatrix("local", 10, 10);
		BinaryOp requiredParent = HopRewriteUtils.createBinary(local, unknown, OpOp2.PLUS);
		assertTrue("Unknown required matrix anchor must fail closed",
			invokeDetermineParentAnchor(requiredParent, local) == null);

		UnaryOp optionalParent = HopRewriteUtils.createUnary(unknown, OpOp1.BROADCAST);
		assertTrue("Unknown optional matrix anchor must fail closed",
			invokeDetermineParentAnchor(optionalParent, local) == null);
	}

	@Test
	public void testRuntimeFederatedSymbolDoesNotOverridePlannedLocalTransientRead() {
		DataOp tRead = createLocalMatrix("X", 3000, 50);
		tRead.setForcedExecType(ExecType.CP);
		tRead.setFederatedOutput(FederatedOutput.LOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X",
			"worker1:8001/data/P2P_features_2_1.data;worker2:8002/data/P2P_features_2_2.data;|0,50000;50000,100000;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(tRead)), true, fTypeMap, -1,
			runtimeSignatures, runtimeTypes);

		assertEquals("Runtime observation must not override the planner-selected local TRead exec type",
			ExecType.CP, tRead.getForcedExecType());
		assertEquals("Runtime observation must not override the planner-selected local TRead output",
			FederatedOutput.LOUT, tRead.getFederatedOutput());
	}

	@Test
	public void testRuntimeObservedLocalSymbolRejectsPlannedFederatedTransientRead() {
		DataOp tRead = createLocalMatrix("X", 3000, 50);
		tRead.setForcedExecType(ExecType.FED);
		tRead.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(tRead.getHopID(), FType.ROW);

		Map<String, String> runtimeSignatures = new HashMap<>();
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", null);

		assertThrows("A runtime-local value cannot satisfy a planner-selected FED/FOUT TRead",
			DMLRuntimeException.class,
			() -> FederatedRefedPolicy.registerFromHops(
				new java.util.ArrayList<>(Arrays.asList(tRead)), true, fTypeMap, -1,
				runtimeSignatures, runtimeTypes));
	}

	@Test
	public void testRuntimeUnobservedSymbolDoesNotDemotePlannedFederatedTransientRead() {
		DataOp tRead = createLocalMatrix("X", 3000, 50);
		tRead.setForcedExecType(ExecType.FED);
		tRead.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(tRead.getHopID(), FType.ROW);

		Map<String, String> runtimeSignatures = new HashMap<>();
		Map<String, FType> runtimeTypes = new HashMap<>();

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(tRead)), true, fTypeMap, -1,
			runtimeSignatures, runtimeTypes);

		assertEquals("An absent runtime observation is unknown, not proof that the value is local",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("Unknown runtime placement must preserve the planner-selected FED output",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
		assertTrue("Unknown runtime placement must preserve the planner FType hint",
			fTypeMap.containsKey(tRead.getHopID()));
	}

	@Test
	public void testRuntimeContextLocalTransientWriteClearsFedInitVar() {
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW,
			"worker1:8001/data/P2P_features_2_1.data;worker2:8002/data/P2P_features_2_2.data;|0,50000;50000,100000;");
		assertTrue("Expected test setup to register fed-init marker for X",
			FederatedPlannerUtils.isFedInitVar("X"));

		DataOp localSrc = createLocalMatrix("x_local_src", 3000, 50);
		DataOp tWrite = createTransientWrite("X", localSrc, 3000, 50);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.LOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<String, String> runtimeSignatures = new HashMap<>();
		Map<String, FType> runtimeTypes = new HashMap<>();

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(tWrite)), true, fTypeMap, -1,
			runtimeSignatures, runtimeTypes);

		assertFalse("Expected local transient write to clear stale fed-init marker in runtime context",
			FederatedPlannerUtils.isFedInitVar("X"));
		assertTrue("Expected local transient write to clear stale anchor key in runtime context",
			FederatedPlannerUtils.getFedAnchorKey("X") == null);
	}

	@Test
	public void testRuntimeUnobservedSymbolPreservesFedInitTransientRead() {
		FederatedPlannerUtils.registerFedInitVar("X", FType.ROW,
			"worker1:8001/data/P2P_features_2_1.data;worker2:8002/data/P2P_features_2_2.data;|0,50000;50000,100000;");

		DataOp tRead = createLocalMatrix("X", 3000, 50);
		tRead.setForcedExecType(ExecType.FED);
		tRead.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(tRead.getHopID(), FType.ROW);

		Map<String, String> runtimeSignatures = new HashMap<>();
		Map<String, FType> runtimeTypes = new HashMap<>();

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(tRead)), true, fTypeMap, -1,
			runtimeSignatures, runtimeTypes);

		assertEquals("Unknown runtime placement must preserve a planner-selected FED transient read",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("Unknown runtime placement must preserve the planner-selected FOUT",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
	}

	@Test
	public void testRuntimeTransientWriteMaterializeSurvivesFuturePlannedFedTRead() {
		DataOp fedAnchor = createFederatedInput("X", 3000, 50);
		DataOp localMask = createLocalMatrix("mask_local", 3000, 1);
		localMask.setForcedExecType(ExecType.CP);
		localMask.setFederatedOutput(FederatedOutput.LOUT);

		DataOp tWrite = createTransientWrite("is_row_in_samples", localMask, 3000, 1);
		tWrite.setForcedExecType(ExecType.CP);
		tWrite.setFederatedOutput(FederatedOutput.FOUT);

		DataOp futureTRead = createLocalMatrix("is_row_in_samples", 3000, 1);
		futureTRead.setForcedExecType(ExecType.FED);
		futureTRead.setFederatedOutput(FederatedOutput.FOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedAnchor.getHopID(), FType.ROW);
		fTypeMap.put(futureTRead.getHopID(), FType.ROW);

		// Seed the global write/read relationship as the planner does before runtime recompile.
		FederatedRefedPolicy.registerFromHops(
			new java.util.ArrayList<>(Arrays.asList(tWrite, futureTRead, fedAnchor)), true, fTypeMap, 85);
		assertEquals("Expected initial planner pass to keep transient write materialization",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
		assertEquals("Initial planner pass must encode the materialized write as FED/FOUT",
			ExecType.FED, tWrite.getForcedExecType());

		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("X",
			"worker1:8001/data/P2P_features_2_1.data;worker2:8002/data/P2P_features_2_2.data;|0,1500;1500,3000;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("X", FType.ROW);

		// Runtime recompile of the TWrite block is narrower than the future TRead block.
		// The stale-write pass must still preserve the FED/FOUT materialization because the
		// already-approved planner state has a matching future FED/FOUT TRead for the same variable.
		FederatedRefedPolicy.registerFromHops(
			new java.util.ArrayList<>(Arrays.asList(tWrite, fedAnchor)), true, fTypeMap, 85,
			runtimeSignatures, runtimeTypes);

		assertEquals("Expected runtime stale-write cleanup to preserve FED/FOUT TWrite for future FED TRead",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
		assertEquals("Runtime recompile must preserve the planner-selected legal FED/FOUT pair",
			ExecType.FED, tWrite.getForcedExecType());
		assertTrue("Expected runtime TWrite materialization registry entry to remain",
			FederatedFoutMaterializeRegistry.snapshot(85).containsKey(tWrite.getHopID()));

		// Later runtime recompile of the TRead block should then see the preserved planned write
		// as the federated source and must not silently demote the FED/FOUT TRead.
		FederatedRefedPolicy.registerFromHops(
			new java.util.ArrayList<>(Arrays.asList(futureTRead, fedAnchor)), true, fTypeMap, 4,
			runtimeSignatures, runtimeTypes);
		assertEquals("Expected future transient read to remain FED after matching materialized TWrite is preserved",
			ExecType.FED, futureTRead.getForcedExecType());
		assertEquals("Expected future transient read to remain FOUT after matching materialized TWrite is preserved",
			FederatedOutput.FOUT, futureTRead.getFederatedOutput());
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
	public void testRuntimeRecompilePreservesReachableLocalMaterializeObligation() {
		DataOp producer = createFederatedInput("A", 10, 10);
		UnaryOp consumer = HopRewriteUtils.createUnary(producer, OpOp1.SQRT);
		consumer.setDim1(10);
		consumer.setDim2(10);
		consumer.setForcedExecType(ExecType.CP);
		consumer.setFederatedOutput(FederatedOutput.LOUT);

		FederatedLocalMaterializeRegistry.register(-1L, producer.getHopID(),
			Arrays.asList(consumer.getHopID()), "ROW", "test-preserve");

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(producer.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("A", "worker1:8001/data/A;|0,10;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("A", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(consumer)), true,
			fTypeMap, 42L, runtimeSignatures, runtimeTypes);

		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> snapshot =
			FederatedLocalMaterializeRegistry.snapshot(42L);
		assertTrue("Expected runtime recompile to preserve reachable local-materialize producer",
			snapshot.containsKey(producer.getHopID()));
		assertEquals("Expected reachable consumer to remain registered",
			Arrays.asList(consumer.getHopID()), snapshot.get(producer.getHopID()).getConsumerHopIds());
	}

	@Test
	public void testRuntimeRecompilePrunesUnreachableLocalMaterializeConsumers() {
		DataOp producer = createFederatedInput("A", 10, 10);
		UnaryOp consumer = HopRewriteUtils.createUnary(producer, OpOp1.SQRT);
		consumer.setDim1(10);
		consumer.setDim2(10);
		consumer.setForcedExecType(ExecType.CP);
		consumer.setFederatedOutput(FederatedOutput.LOUT);

		FederatedLocalMaterializeRegistry.register(-1L, producer.getHopID(),
			Arrays.asList(consumer.getHopID(), 999999L), "ROW", "test-prune");

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(producer.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("A", "worker1:8001/data/A;|0,10;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("A", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(consumer)), true,
			fTypeMap, 42L, runtimeSignatures, runtimeTypes);

		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> snapshot =
			FederatedLocalMaterializeRegistry.snapshot(42L);
		assertTrue("Expected producer obligation to survive with a reachable consumer",
			snapshot.containsKey(producer.getHopID()));
		assertEquals("Expected unreachable consumer to be pruned",
			Arrays.asList(consumer.getHopID()), snapshot.get(producer.getHopID()).getConsumerHopIds());
	}

	@Test
	public void testRuntimeRecompilePreservesDefaultLocalMaterializeScopeForLaterLowering() {
		DataOp producer = createFederatedInput("A", 10, 10);
		UnaryOp consumer = HopRewriteUtils.createUnary(producer, OpOp1.SQRT);
		consumer.setDim1(10);
		consumer.setDim2(10);
		consumer.setForcedExecType(ExecType.CP);
		consumer.setFederatedOutput(FederatedOutput.LOUT);

		FederatedLocalMaterializeRegistry.register(-1L, producer.getHopID(),
			Arrays.asList(consumer.getHopID()), "ROW", "test-preserve-default-scope");

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(producer.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("A", "worker1:8001/data/A;|0,10;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("A", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(consumer)), true,
			fTypeMap, 42L, runtimeSignatures, runtimeTypes);

		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> currentBlockSnapshot =
			FederatedLocalMaterializeRegistry.snapshot(42L);
		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> laterBlockSnapshot =
			FederatedLocalMaterializeRegistry.snapshot(43L);
		assertTrue("Expected current runtime block to see the preserved default obligation",
			currentBlockSnapshot.containsKey(producer.getHopID()));
		assertTrue("Expected later loop/body lowering to still see the default obligation",
			laterBlockSnapshot.containsKey(producer.getHopID()));
		assertEquals("Expected default obligation consumers to remain reachable-only",
			Arrays.asList(consumer.getHopID()), laterBlockSnapshot.get(producer.getHopID()).getConsumerHopIds());
	}

	@Test
	public void testRuntimeRecompileKeepsDefaultLocalMaterializeAcrossUnrelatedBlock() {
		DataOp producer = createFederatedInput("A", 10, 10);
		UnaryOp consumer = HopRewriteUtils.createUnary(producer, OpOp1.SQRT);
		consumer.setDim1(10);
		consumer.setDim2(10);
		consumer.setForcedExecType(ExecType.CP);
		consumer.setFederatedOutput(FederatedOutput.LOUT);
		FederatedLocalMaterializeRegistry.register(-1L, producer.getHopID(),
			Arrays.asList(consumer.getHopID()), "ROW", "test-unrelated-block-preserve");

		DataOp unrelatedInput = createLocalMatrix("B", 10, 10);
		UnaryOp unrelatedConsumer = HopRewriteUtils.createUnary(unrelatedInput, OpOp1.LOG);
		unrelatedConsumer.setDim1(10);
		unrelatedConsumer.setDim2(10);
		unrelatedConsumer.setForcedExecType(ExecType.CP);
		unrelatedConsumer.setFederatedOutput(FederatedOutput.LOUT);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(producer.getHopID(), FType.ROW);
		Map<String, String> runtimeSignatures = new HashMap<>();
		runtimeSignatures.put("A", "worker1:8001/data/A;|0,10;");
		Map<String, FType> runtimeTypes = new HashMap<>();
		runtimeTypes.put("A", FType.ROW);

		FederatedRefedPolicy.registerFromHops(new java.util.ArrayList<>(Arrays.asList(unrelatedConsumer)),
			true, fTypeMap, 42L, runtimeSignatures, runtimeTypes);

		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> laterBlockSnapshot =
			FederatedLocalMaterializeRegistry.snapshot(43L);
		assertTrue("Expected unrelated runtime recompile not to drop default local-materialize obligation",
			laterBlockSnapshot.containsKey(producer.getHopID()));

		Lop consumerLop = consumer.constructLops();
		Dag<Lop> dag = new Dag<>();
		consumerLop.addToDag(dag);
		boolean hasPrefetch = false;
		for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
			if (inst.getInstructionString().contains("prefetch")) {
				hasPrefetch = true;
				break;
			}
		}
		assertTrue("Expected later lowering to insert CP prefetch from preserved default obligation", hasPrefetch);
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
		FederatedRefedPolicy.registerFromHops(Arrays.asList(parent), true, fTypeMap, -1);
		assertEquals("Expected unsatisfied FED parent to be demoted to CP after heuristic demotion blocks CP->FOUT",
			ExecType.CP, parent.getForcedExecType());
		assertTrue("Expected demoted parent not to remain FOUT",
			parent.getFederatedOutput() != FederatedOutput.FOUT);
		assertTrue("Expected heuristic-demoted target to remain non-candidate for CP->FOUT",
			!FederatedRefedPolicy.canGenerateCpfoutCandidate(target, fTypeMap));
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
				FederatedRefedPolicy.registerFromHops(copiedRoots, true, copiedFTypeMap, -1);
				assertEquals("Expected copied FED parent to be demoted to CP when copied target remains heuristic-demoted",
					ExecType.CP, copiedRoots.get(0).getForcedExecType());
				assertTrue("Expected copied demoted parent not to remain FOUT",
					copiedRoots.get(0).getFederatedOutput() != FederatedOutput.FOUT);
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

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertCpfoutLiteralAnchorRejected(Hop hop, String anchorValue) throws Exception {
		ExecType originalExec = hop.getForcedExecType();
		FederatedOutput originalOutput = hop.getFederatedOutput();
		Class<?> keyTypeClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorKeyType");
		Object literalType = Enum.valueOf((Class) keyTypeClass, "FEDINIT_SIGNATURE");
		Class<?> keyClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorKey");
		java.lang.reflect.Constructor<?> keyConstructor = keyClass.getDeclaredConstructor(keyTypeClass, Object.class);
		keyConstructor.setAccessible(true);
		Object key = keyConstructor.newInstance(literalType, anchorValue);
		Class<?> selectionClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorSelection");
		java.lang.reflect.Constructor<?> selectionConstructor =
			selectionClass.getDeclaredConstructor(keyClass, Hop.class);
		selectionConstructor.setAccessible(true);
		Object selection = selectionConstructor.newInstance(key, null);
		java.lang.reflect.Method register = FederatedRefedPolicy.class.getDeclaredMethod(
			"registerCpfoutWithSelection", Hop.class, Map.class, long.class, selectionClass, List.class);
		register.setAccessible(true);
		try {
			register.invoke(null, hop, new HashMap<Long, FType>(), 71L, selection, List.of());
			throw new AssertionError("Expected untyped literal anchor rejection");
		}
		catch(java.lang.reflect.InvocationTargetException ex) {
			assertTrue("Untyped literal anchor rejection must be a planner error",
				ex.getCause() instanceof DMLRuntimeException);
		}
		assertEquals("Prevalidation must not mutate exec placement", originalExec, hop.getForcedExecType());
		assertEquals("Prevalidation must not mutate output placement", originalOutput, hop.getFederatedOutput());
		assertFalse("Prevalidation must not populate the CP/FOUT anchor cache",
			FederatedRefedPolicy.snapshotCpfoutAnchorCache().containsKey(hop.getHopID()));
		assertFalse("Prevalidation must not publish REFED work",
			FederatedRefedRegistry.snapshot(71L).containsKey(hop.getHopID()));
		assertFalse("Prevalidation must not publish FOUT materialization",
			FederatedFoutMaterializeRegistry.snapshot(71L).containsKey(hop.getHopID()));
	}

	private static Object invokeDetermineParentAnchor(Hop parent, Hop target) throws Exception {
		java.lang.reflect.Method determine = FederatedRefedPolicy.class.getDeclaredMethod(
			"determineParentAnchor", Hop.class, Hop.class, Map.class,
			boolean.class, boolean.class, boolean.class);
		determine.setAccessible(true);
		return determine.invoke(null, parent, target, new HashMap<Long, FType>(), true, false, false);
	}
}

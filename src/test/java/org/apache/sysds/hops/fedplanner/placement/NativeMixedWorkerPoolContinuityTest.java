/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Candidate-specific pool proofs must preserve each operand's layout. */
public class NativeMixedWorkerPoolContinuityTest {
	@Test
	public void rowByBroadcastUsesBothExactInputsOnTheCanonicalPool() {
		Fixture fixture = fixture(false, "worker2:8002/R", true);
		var proof = fixture.continuity().proveCandidate(fixture.output(), fixture.seed());
		Assert.assertNotNull("ROW output and replicated RHS share canonical endpoints", proof);
		Assert.assertEquals(List.of(0, 1), proof.immediateBindings().stream()
			.map(binding -> binding.inputPosition()).toList());
		Assert.assertEquals(List.of(FType.ROW, FType.BROADCAST), proof.immediateBindings().stream()
			.map(binding -> binding.source().realization().emissionState().placementState().fType()).toList());
		Assert.assertSame("the pool proof must not synthesize another seed", fixture.seed(), proof.externalSeed());
	}

	@Test
	public void sameHostnameWithDifferentPortCannotProveBroadcastColocation() {
		Fixture fixture = fixture(false, "worker2:8999/R", true);
		Assert.assertNull(fixture.continuity().proveCandidate(fixture.output(), fixture.seed()));
	}

	@Test
	public void aMissingBroadcastInputEdgeCannotBecomeAnUnboundProof() {
		Fixture fixture = fixture(false, "worker2:8002/R", false);
		Assert.assertNull(fixture.continuity().proveCandidate(fixture.output(), fixture.seed()));
	}

	@Test
	public void rowPartitionAxisStillMustMatchEvenWhenEndpointsMatch() {
		Fixture fixture = fixture(false, "worker2:8002/R", true);
		DurableAnchorKey changedAxis = new DurableAnchorKey("changed-axis", FType.ROW, List.of(
			new AnchorPartition("worker1:8001", List.of(0L, 0L), List.of(1L, 2L)),
			new AnchorPartition("worker2:8002", List.of(1L, 0L), List.of(4L, 2L))));
		Assert.assertNull(fixture.continuity().proveCandidate(fixture.output(), changedAxis));
	}

	@Test
	public void localByBroadcastBindsTheExactReplicatedOperand() {
		Fixture fixture = fixture(true, "worker2:8002/R", true);
		var proof = fixture.continuity().proveCandidate(fixture.output(), fixture.seed());
		Assert.assertNotNull(proof);
		Assert.assertEquals(List.of(1), proof.immediateBindings().stream()
			.map(binding -> binding.inputPosition()).toList());
	}

	private static Fixture fixture(boolean localLeft, String rightWorker, boolean rightEdge) {
		DurableAnchorKey row = new DurableAnchorKey("left-map", FType.ROW, List.of(
			new AnchorPartition("worker1:8001/L", List.of(0L, 0L), List.of(2L, 2L)),
			new AnchorPartition("worker2:8002/L", List.of(2L, 0L), List.of(4L, 2L))));
		// Reverse order and distinct paths are deliberately not distinct workers.
		DurableAnchorKey broadcast = new DurableAnchorKey("right-map", FType.BROADCAST, List.of(
			new AnchorPartition(rightWorker, List.of(0L, 0L), List.of(2L, 3L)),
			new AnchorPartition("worker1:8001/R", List.of(0L, 0L), List.of(2L, 3L))));
		DataOp left = new DataOp("L", DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED,
			"L", 4, 2, 8, 1000);
		DataOp right = new DataOp("R", DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED,
			"R", 2, 3, 6, 1000);
		Hop product = new AggBinaryOp("product", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
		CompiledHopKey leftKey = key("left"), rightKey = key("right"), outputKey = key("product");
		FType outputType = localLeft ? FType.BROADCAST : FType.ROW;
		CandidateRuleFact leftFact = fact(leftKey, List.of(), FType.ROW, row);
		CandidateRuleFact rightFact = fact(rightKey, List.of(), FType.BROADCAST, broadcast);
		CandidateRuleFact outputFact = fact(outputKey, List.of(localLeft
			? CandidateInputState.absentLocal() : CandidateInputState.present(FType.ROW),
			CandidateInputState.present(FType.BROADCAST)), outputType, null);
		List<CompiledInputEdgeFact> edges = new ArrayList<>();
		edges.add(new CompiledInputEdgeFact(leftKey, outputKey, 0));
		if(rightEdge)
			edges.add(new CompiledInputEdgeFact(rightKey, outputKey, 1));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(leftKey, node(leftKey, FType.ROW, row), rightKey, node(rightKey, FType.BROADCAST, broadcast),
				outputKey, node(outputKey, outputType, null)),
			Map.of(leftKey, left, rightKey, right, outputKey, product),
			List.of(leftFact, rightFact, outputFact), edges, Map.of());
		return new Fixture(continuity, CandidateRealizationReference.of(outputFact.key(),
			outputFact.allowedEmissionFacts().get(0).realizations().get(0)), localLeft ? broadcast : row);
	}

	private static CandidateRuleFact fact(CompiledHopKey key, List<CandidateInputState> inputs,
		FType type, DurableAnchorKey anchor) {
		PlacementEmissionState emission = new PlacementEmissionState(state(type), false);
		CandidateEmissionFact output = anchor == null ? new CandidateEmissionFact(emission, type)
			: new CandidateEmissionFact(emission, type, null, List.of(
				CandidateEmissionRealization.durable(emission, anchor, List.of(), List.of())));
		return new CandidateRuleFact(new CandidateRuleKey(key, inputs), CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED, FederatedOutput.FOUT,
				type, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(type), ""), List.of(output), "");
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("mixed-pool", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("mixed-pool", "main", "main", "compiled", region, name, name);
	}

	private static Node node(CompiledHopKey key, FType type, DurableAnchorKey anchor) {
		ValueVersionKey value = new ValueVersionKey("mixed-pool", key.emittedHopInstance(),
			key.controlRegion(), 0, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, List.of(state(type)), List.of(),
			anchor == null ? List.of() : List.of(anchor));
	}

	private static PlacementState state(FType type) {
		return new PlacementState(ExecType.FED, FederatedOutput.FOUT, type, false);
	}

	private record Fixture(NativePlacementContinuity continuity, CandidateRealizationReference output,
		DurableAnchorKey seed) { }
}

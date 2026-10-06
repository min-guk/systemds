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

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact grounding rules for a declared CP/LOUT-to-CP/FOUT materialized root. */
public class MaterializedContinuityTest {
	@Test
	public void declaredMaterializationIsAContinuityGroundOnlyWithItsExactAction() {
		Fixture valid = fixture(true, true, false);
		Assert.assertNotNull(valid.continuity().proveCandidate(valid.output(), valid.outputPool()));

		Fixture missingAction = fixture(false, true, false);
		Assert.assertNull("a durable CP/FOUT realization alone is not upload authority",
			missingAction.continuity().proveCandidate(missingAction.output(), missingAction.outputPool()));
	}

	@Test
	public void materializationRejectsAnAnchorOwnerFromAnotherPhysicalPool() {
		Fixture wrongPool = fixture(true, true, true);
		Assert.assertNull("the action owner must prove the same concrete worker pool",
			wrongPool.continuity().proveCandidate(wrongPool.output(), wrongPool.outputPool()));
	}

	@Test
	public void materializationRejectsAnActionWhoseLocalSourceEmissionWasRemoved() {
		Fixture missingSource = fixture(true, false, false);
		Assert.assertNull("an upload action cannot survive removal of its CP/LOUT source row",
			missingSource.continuity().proveCandidate(
				missingSource.output(), missingSource.outputPool()));
	}

	@Test
	public void declaredMaterializationCannotSelfGroundAGeneratedTemplate() {
		Fixture valid = fixture(true, true, false);
		Assert.assertTrue("generated native templates require ordinary input continuity",
			valid.continuity().proveGeneratedCandidateAlternatives(valid.producerFact(),
				valid.uploadEmission(), valid.output(), valid.outputPool()).isEmpty());
	}

	private static Fixture fixture(boolean includeAction, boolean includeLocalSource,
		boolean wrongOwnerPool) {
		ControlRegionKey region = new ControlRegionKey(
			"materialized-continuity", "main", List.of("main/0"), "main", "compiled");
		CompiledHopKey producer = key(region, "producer");
		CompiledHopKey anchorOwner = key(region, "anchor");
		ValueVersionKey producerVersion = version(region, "producer", 0);
		ValueVersionKey anchorVersion = version(region, "anchor", 1);
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState upload = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState resident = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		DurableAnchorKey outputPool = pool("output", "worker-a:1234", "worker-b:1234");
		DurableAnchorKey ownerPool = wrongOwnerPool
			? pool("foreign", "worker-c:1234", "worker-d:1234") : outputPool;

		CandidateRuleKey producerRule = new CandidateRuleKey(producer, List.of());
		DerivedFoutMaterializationActionKey action = includeAction
			? new DerivedFoutMaterializationActionKey(producer, producerVersion, producerRule,
				local, upload, outputPool, anchorOwner, FType.ROW, FType.ROW,
				region.normalizedSignature()) : null;
		PlacementEmissionState localEmissionState = new PlacementEmissionState(local, false);
		PlacementEmissionState uploadEmissionState = new PlacementEmissionState(upload, false);
		CandidateEmissionFact localEmission = new CandidateEmissionFact(
			localEmissionState, null, null, List.of(CandidateEmissionRealization.local(localEmissionState)));
		List<PlacementProofKey> uploadProofs = action == null ? List.of() : List.of(
			new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, producer,
				"derived-fout:" + action.normalizedSignature()));
		CandidateEmissionFact uploadEmission = new CandidateEmissionFact(uploadEmissionState, null, action,
			List.of(CandidateEmissionRealization.durable(
				uploadEmissionState, outputPool, uploadProofs, List.of())));
		List<CandidateEmissionFact> producerEmissions = includeLocalSource
			? List.of(localEmission, uploadEmission) : List.of(uploadEmission);
		CandidateRuleFact producerFact = fact(producerRule, ExecType.CP,
			FederatedOutput.FOUT, FType.ROW, List.of(), producerEmissions);

		CandidateRuleKey anchorRule = new CandidateRuleKey(anchorOwner, List.of());
		PlacementEmissionState residentEmissionState = new PlacementEmissionState(resident, false);
		CandidateEmissionFact residentEmission = new CandidateEmissionFact(
			residentEmissionState, FType.ROW, null, List.of(CandidateEmissionRealization.durable(
				residentEmissionState, ownerPool, List.of(), List.of())));
		CandidateRuleFact anchorFact = fact(anchorRule, ExecType.FED,
			FederatedOutput.FOUT, FType.ROW, List.of(FType.ROW), List.of(residentEmission));

		DataOp producerHop = new DataOp("producer", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "producer", 8, 2, 16, 1000);
		DataOp anchorHop = new DataOp("anchor", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "anchor", 8, 2, 16, 1000);
		Node producerNode = new Node(producer, NodeKind.OPERATION, producerVersion, true,
			List.of(local, upload), List.of(), List.of());
		Node anchorNode = new Node(anchorOwner, NodeKind.OPERATION, anchorVersion, true,
			List.of(resident), List.of(), List.of(ownerPool));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(producer, producerNode, anchorOwner, anchorNode),
			Map.of(producer, producerHop, anchorOwner, anchorHop),
			List.of(producerFact, anchorFact), List.of(), Map.of());
		CandidateRealizationReference output = CandidateRealizationReference.of(
			producerRule, uploadEmission.realizations().get(0));
		return new Fixture(continuity, producerFact, uploadEmission, output, outputPool);
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule, ExecType exec,
		FederatedOutput output, FType outputType, List<FType> inputs,
		List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", exec, output,
				outputType, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(inputs, ""), emissions, "");
	}

	private static CompiledHopKey key(ControlRegionKey region, String name) {
		return new CompiledHopKey("materialized-continuity", "main", "main", "compiled",
			region, name, name);
	}

	private static ValueVersionKey version(ControlRegionKey region, String name, int ordinal) {
		return new ValueVersionKey("materialized-continuity", name, region, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey pool(String id, String first, String second) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition(second, List.of(4L, 0L), List.of(8L, 2L))));
	}

	private record Fixture(NativePlacementContinuity continuity,
		CandidateRuleFact producerFact, CandidateEmissionFact uploadEmission,
		CandidateRealizationReference output, DurableAnchorKey outputPool) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class LogicalBoundaryRealizationsTest {
	private static final PlacementState ROW = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState NATIVE = new PlacementEmissionState(ROW, false);
	private static final DurableAnchorKey POOL_A = pool("a", 8001);
	private static final DurableAnchorKey POOL_B = pool("b", 9001);

	@Test
	public void sameCanonicalEndpointsDoNotRequireSameDataPath() {
		var left = durable(key("left"), POOL_A);
		var alias = durable(key("right"), pool("other-data", 8001));
		Assert.assertTrue(LogicalBoundaryRealizations.compatible(left, left.requireSingletonSupportClause(),
			alias, alias.requireSingletonSupportClause()));
		var wrong = durable(key("wrong"), POOL_B);
		Assert.assertFalse(LogicalBoundaryRealizations.compatible(left, left.requireSingletonSupportClause(),
			wrong, wrong.requireSingletonSupportClause()));
	}

	@Test
	public void nativeTargetRejectsLocalOrUnprovedSource() {
		var nativeValue = durable(key("left"), POOL_A);
		var local = CandidateEmissionRealization.local(new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));
		Assert.assertFalse(LogicalBoundaryRealizations.compatible(nativeValue,
			nativeValue.requireSingletonSupportClause(), local, local.requireSingletonSupportClause()));
	}

	@Test
	public void explicitMaterializationIsNotReinterpretedAsImplicitAlias() {
		var local = CandidateEmissionRealization.local(new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));
		var uploaded = CandidateEmissionRealization.durable(new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false), false), POOL_A,
			List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, key("upload"), "action-owned")), List.of());
		Assert.assertTrue(LogicalBoundaryRealizations.compatible(uploaded,
			uploaded.requireSingletonSupportClause(), local, local.requireSingletonSupportClause()));
	}

	@Test
	public void mixedFormalAndOrdinaryWritersRemainConjunctiveAndIdempotent() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, closed);
		Assert.assertEquals(List.of(f.argument, f.writer).stream().sorted().toList(), relation.sources(f.reader));
		Assert.assertEquals(2, relation.relations().size());
		relation.validate(closed);
		Assert.assertEquals(closed, LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, closed));
	}

	@Test
	public void chosenWriterCannotBorrowAnotherAlternativePool() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, closed);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(f.reader, receipt(closed, f.reader, POOL_A));
		selected.put(f.argument, receipt(closed, f.argument, POOL_A));
		selected.put(f.writer, receipt(closed, f.writer, POOL_B));
		Assert.assertFalse(relation.canStillBeCompatible(Map.of(), selected, Map.of()));
		selected.put(f.writer, receipt(closed, f.writer, POOL_A));
		Assert.assertTrue(relation.canStillBeCompatible(Map.of(), selected, Map.of()));
	}

	@Test
	public void completeBoundaryAssignmentSetMatchesIndependentConjunctiveOracle() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, closed);
		List<DurableAnchorKey> pools = List.of(POOL_A, POOL_B);
		List<String> expected = new ArrayList<>();
		List<String> actual = new ArrayList<>();
		for(int argumentPool = 0; argumentPool < pools.size(); argumentPool++)
			for(int writerPool = 0; writerPool < pools.size(); writerPool++)
				for(int readerPool = 0; readerPool < pools.size(); readerPool++) {
					Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
					selected.put(f.argument, receipt(closed, f.argument, pools.get(argumentPool)));
					selected.put(f.writer, receipt(closed, f.writer, pools.get(writerPool)));
					selected.put(f.reader, receipt(closed, f.reader, pools.get(readerPool)));
					String signature = argumentPool + "/" + writerPool + "/" + readerPool;
					boolean independentlyLegal = readerPool == argumentPool && readerPool == writerPool;
					if(independentlyLegal)
						expected.add(signature);
					if(relation.canStillBeCompatible(Map.of(), selected, Map.of()))
						actual.add(signature);
				}

		Assert.assertEquals("fixture must enumerate all 2^3 exact source/reader pool assignments", 8,
			pools.size() * pools.size() * pools.size());
		Assert.assertEquals("argument + ordinary writer are conjunctive; only one common pool is legal",
			List.of("0/0/0", "1/1/1"), expected);
		Assert.assertEquals("boundary relation changed the complete legal assignment set", expected, actual);
	}

	@Test
	public void missingOneSourceDoesNotAuthorizePublishedNativeReader() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		List<CandidateRuleFact> missing = closed.stream().filter(fact -> fact.key().parentOccurrence() != f.writer).toList();
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, missing);
		Assert.assertThrows(IllegalArgumentException.class, () -> relation.validate(missing));
	}

	private static CandidateSelectionReceipt receipt(List<CandidateRuleFact> facts,
		CompiledHopKey key, DurableAnchorKey pool) {
		CandidateRuleFact fact = facts.stream().filter(candidate -> candidate.key().parentOccurrence() == key).findFirst().orElseThrow();
		CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization value = emission.realizations().stream().filter(candidate ->
			PlacementIdentity.samePhysicalWorkerPool(candidate.provenWorkerPool(candidate.requireSingletonSupportClause()), pool))
			.findFirst().orElseThrow();
		return new CandidateSelectionReceipt(fact.key(), emission, value, List.of());
	}

	private static class Fixture {
		final CompiledHopKey argument = key("argument"), boundary = key("boundary"), writer = key("writer"), reader = key("reader");
		final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		final List<Node> nodes = new ArrayList<>();
		final List<Constraint> edges = new ArrayList<>();
		final List<CandidateRuleFact> facts = new ArrayList<>();
		Fixture() {
			for(CompiledHopKey key : List.of(argument, boundary, writer, reader)) {
				NodeKind kind = key == boundary ? NodeKind.FUNCTION_INPUT : key == reader ? NodeKind.TRANSIENT_READ : NodeKind.OPERATION;
				origins.put(key, new DataOp(key.emittedHopInstance(), DataType.MATRIX, ValueType.FP64,
					key == reader ? OpOpData.TRANSIENTREAD : OpOpData.PERSISTENTREAD, "X", 8, 2, 16, 1000));
				nodes.add(new Node(key, kind, new ValueVersionKey("boundary-test", key.emittedHopInstance(), key.controlRegion(),
					nodes.size(), VersionKind.ORDINARY, List.of()), key != boundary,
					key == boundary ? List.of() : List.of(ROW), List.of(), List.of()));
				if(key == boundary) continue;
				CandidateEmissionFact emission = key == reader ? new CandidateEmissionFact(NATIVE, FType.ROW)
					: new CandidateEmissionFact(NATIVE, FType.ROW, null, List.of(durable(key, POOL_A), durable(key, POOL_B)));
				facts.add(new CandidateRuleFact(new CandidateRuleKey(key, List.of()), CandidateEvaluationStatus.AVAILABLE,
					new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED, FederatedOutput.FOUT,
						FType.ROW, ReasonCode.OK, "fixture", List.of()), new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
					new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), ""));
			}
			edges.add(new Constraint(ConstraintKind.CONJUNCTIVE, argument, boundary, 0, "function-argument:X"));
			edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, boundary, reader, 0, "function-formal-input"));
			edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, writer, reader, 0, "cfg-transient-value:BRANCH_JOIN_PHI"));
		}
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("boundary-test", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("boundary-test", "main", "call", "compiled", region, name, name);
	}
	private static DurableAnchorKey pool(String id, int port) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:" + port + "/" + id, List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("localhost:" + (port + 1) + "/" + id, List.of(4L, 0L), List.of(8L, 2L))));
	}
	private static CandidateEmissionRealization durable(CompiledHopKey key, DurableAnchorKey pool) {
		return CandidateEmissionRealization.durable(NATIVE, pool,
			List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, key, pool.normalizedSignature())), List.of());
	}
}

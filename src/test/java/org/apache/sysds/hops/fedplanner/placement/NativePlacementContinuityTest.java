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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Typed, coinductive native worker-pool continuity proofs. */
public class NativePlacementContinuityTest {
	@Test
	public void provesGrowingFullRowAndColIncludingLoopCycle() {
		Fixture typedRow = new Fixture(FType.ROW);
		DurableAnchorKey externalRow = new DurableAnchorKey("external-value", FType.ROW,
			List.of(new AnchorPartition("worker1:8001", List.of(0L, 0L), List.of(4L, 2L))));
		DurableAnchorKey renamedRow = new DurableAnchorKey("different-value", FType.ROW,
			List.of(new AnchorPartition("worker1:8001", List.of(0L, 50L), List.of(4L, 99L))));
		Ref renamedRowSource = typedRow.source("renamed", renamedRow);
		Assert.assertTrue("ROW witnesses exclude placement id and the non-partitioned axis",
			typedRow.resolver().proves(List.of(renamedRowSource.key), externalRow));
		Ref nativeFederatedSource = typedRow.federatedSource("nativeFed", externalRow);
		Assert.assertTrue("An anchored FEDERATED source may have only ABSENT_LOCAL metadata inputs",
			typedRow.resolver().proves(List.of(nativeFederatedSource.key), externalRow));

		Fixture row = new Fixture(FType.ROW);
		Ref rowSeed = row.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref rowRead = row.logicalRead("B");
		Ref rowAppend = row.binary("cbind", OpOp2.CBIND, rowRead, rowSeed, false);
		Ref rowWrite = row.write("Bwrite", rowAppend, NodeKind.LOOP_PHI, false);
		row.reaching.put(rowRead.key, List.of(rowSeed.key, rowWrite.key));
		Assert.assertTrue(row.resolver().proves(List.of(rowRead.key), rowSeed.anchor));
		Ref rowScalar = row.matrixScalar("B+1", OpOp2.PLUS, rowSeed);
		Assert.assertTrue("Native matrix-scalar execution copies the complete federated map",
			row.resolver().proves(List.of(rowScalar.key), rowSeed.anchor));

		Fixture col = new Fixture(FType.COL);
		Ref colSeed = col.source("A", anchor(FType.COL, "worker1:8001", 0, 3));
		Ref colAppend = col.binary("rbind", OpOp2.RBIND, colSeed, colSeed, false);
		Assert.assertTrue(col.resolver().proves(List.of(colAppend.key), colSeed.anchor));

		Fixture full = new Fixture(FType.FULL);
		Ref fullSeed = full.source("A", anchor(FType.FULL, "worker1:8001", 0, 4));
		DurableAnchorKey renamedFull = new DurableAnchorKey("different-full-value", FType.FULL,
			List.of(new AnchorPartition("worker1:8001", List.of(90L, 80L), List.of(100L, 99L))));
		Ref renamedFullSource = full.source("renamedFull", renamedFull);
		Assert.assertTrue("Single-range FULL witnesses contain only the canonical endpoint",
			full.resolver().proves(List.of(renamedFullSource.key), fullSeed.anchor));
		Ref fullAppend = full.binary("cbind", OpOp2.CBIND, fullSeed, fullSeed, false);
		Ref fullTranspose = full.transpose("transpose", fullAppend, false);
		Assert.assertTrue(full.resolver().proves(List.of(fullAppend.key, fullTranspose.key), fullSeed.anchor));
	}

	@Test
	public void rejectsChangedPartitionAxisTransposeAndDifferentPool() {
		Fixture rowAppend = new Fixture(FType.ROW);
		Ref rowSeed = rowAppend.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref rbind = rowAppend.binary("rbind", OpOp2.RBIND, rowSeed, rowSeed, false);
		Assert.assertFalse(rowAppend.resolver().proves(List.of(rbind.key), rowSeed.anchor));

		Fixture rowTranspose = new Fixture(FType.ROW);
		Ref transposeSeed = rowTranspose.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref transpose = rowTranspose.transpose("transpose", transposeSeed, false);
		Assert.assertFalse(rowTranspose.resolver().proves(List.of(transpose.key), transposeSeed.anchor));
		Ref reverse = rowTranspose.reorg("reverse", ReOrgOp.REV, transposeSeed, false);
		Assert.assertFalse("Unspecified map-changing operations fail closed",
			rowTranspose.resolver().proves(List.of(reverse.key), transposeSeed.anchor));

		Fixture pools = new Fixture(FType.FULL);
		Ref first = pools.source("A", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref second = pools.source("C", anchor(FType.FULL, "worker2:8002", 0, 4));
		Ref mixed = pools.binary("mixed", OpOp2.CBIND, first, second, false);
		Assert.assertFalse(pools.resolver().proves(List.of(mixed.key), first.anchor));
	}

	@Test
	public void rejectsDerivedEmissionUnanchoredBranchesAndMultiRangeFull() {
		Fixture derived = new Fixture(FType.ROW);
		Ref seed = derived.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref derivedOnly = derived.binary("derived", OpOp2.CBIND, seed, seed, true);
		Assert.assertFalse(derived.resolver().proves(List.of(derivedOnly.key), seed.anchor));

		Fixture unanchored = new Fixture(FType.ROW);
		Ref good = unanchored.source("good", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref join = unanchored.read("join");
		Ref cycleA = unanchored.read("cycleA");
		Ref cycleB = unanchored.read("cycleB");
		unanchored.reaching.put(join.key, List.of(good.key, cycleA.key));
		unanchored.reaching.put(cycleA.key, List.of(cycleB.key));
		unanchored.reaching.put(cycleB.key, List.of(cycleA.key));
		Assert.assertFalse("Every reaching source must ultimately be grounded",
			unanchored.resolver().proves(List.of(join.key), good.anchor));

		Fixture multi = new Fixture(FType.FULL);
		DurableAnchorKey multiRange = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 2), partition("worker1:8001", 2, 4)));
		Ref multiSource = multi.source("multi", multiRange);
		Assert.assertFalse(multi.resolver().proves(List.of(multiSource.key), multiRange));
	}

	@Test
	public void provesRightIndexOnlyForSingleEndpointFullInput() {
		Fixture full = new Fixture(FType.FULL);
		Ref functionInput = full.source("X_orig", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref selectedColumn = full.rightIndex("X_orig[,column]", functionInput);
		Ref initialWrite = full.write("X_global", selectedColumn, NodeKind.TRANSIENT_WRITE, false);
		Assert.assertTrue("FULL right indexing filters and resizes the map but retains its sole worker endpoint",
			full.resolver().proves(List.of(initialWrite.key), functionInput.anchor));

		Fixture row = new Fixture(FType.ROW);
		Ref rowInput = row.source("X", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref rowSlice = row.rightIndex("X[,column]", rowInput);
		Assert.assertFalse("ROW indexing may filter or resize the partition axis and is not a continuity proof",
			row.resolver().proves(List.of(rowSlice.key), rowInput.anchor));
	}

	private static final class Fixture {
		private final String fingerprint = "native-continuity-" + System.identityHashCode(this);
		private final FType fType;
		private final Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final List<CandidateRuleFact> candidates = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> reaching = new IdentityHashMap<>();
		private int ordinal;

		private Fixture(FType fType) {
			this.fType = fType;
		}

		private Ref source(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
			return add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, anchor);
		}

		private Ref federatedSource(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED,
				name, 4, 2, 8, 1000);
			Ref source = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, anchor);
			candidateFederatedSource(source);
			return source;
		}

		private Ref read(String name) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, -1, -1, -1, 1000);
			return add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, null);
		}

		private Ref logicalRead(String name) {
			Ref read = read(name);
			candidateLogicalRead(read);
			return read;
		}

		private Ref write(String name, Ref input, NodeKind kind, boolean derivedEmission) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, input.hop,
				OpOpData.TRANSIENTWRITE, name);
			Ref result = add(name, hop, kind, VersionKind.LOOP_BACKEDGE, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private Ref binary(String name, OpOp2 op, Ref left, Ref right, boolean derivedEmission) {
			Ref result = add(name, new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, left.hop, right.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(left, right), derivedEmission);
			return result;
		}

		private Ref matrixScalar(String name, OpOp2 op, Ref matrix) {
			BinaryOp hop = new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, matrix.hop, new LiteralOp(1L));
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			CandidateRuleKey rule = new CandidateRuleKey(result.key, List.of(
				CandidateInputState.present(fType), CandidateInputState.absentLocal()));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "binary", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
			edges.add(new CompiledInputEdgeFact(matrix.key, result.key, 0));
			return result;
		}

		private Ref transpose(String name, Ref input, boolean derivedEmission) {
			return reorg(name, ReOrgOp.TRANS, input, derivedEmission);
		}

		private Ref reorg(String name, ReOrgOp operation, Ref input, boolean derivedEmission) {
			Ref result = add(name, new ReorgOp(name, DataType.MATRIX, ValueType.FP64,
				operation, input.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private Ref rightIndex(String name, Ref input) {
			IndexingOp hop = new IndexingOp(name, DataType.MATRIX, ValueType.FP64, input.hop,
				new LiteralOp(1L), new LiteralOp(50L), new LiteralOp(1L), new LiteralOp(1L), false, true);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			CandidateRuleKey rule = new CandidateRuleKey(result.key, List.of(
				CandidateInputState.present(fType), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal()));
			PlacementState target = state(fType);
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(target, false), fType);
			DerivedFoutMaterializationActionKey fallback = new DerivedFoutMaterializationActionKey(
				result.key, nodes.get(result.key).valueVersion(), rule,
				new PlacementState(ExecType.FED, FederatedOutput.LOUT, fType, false), target,
				input.anchor, input.key, fType, fType, result.key.controlRegion().normalizedSignature());
			CandidateEmissionFact derived = new CandidateEmissionFact(
				new PlacementEmissionState(target, true), fType, fallback);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.INDEXING, "rightIndex", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission, derived), ""));
			edges.add(new CompiledInputEdgeFact(input.key, result.key, 0));
			return result;
		}

		private Ref add(String name, Hop hop, NodeKind kind, VersionKind versionKind,
			DurableAnchorKey anchor) {
			ControlRegionKey region = new ControlRegionKey(fingerprint, "main",
				List.of("main/" + ordinal), "main", "compiled");
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled",
				region, name + '@' + ordinal, name);
			ValueVersionKey value = new ValueVersionKey(fingerprint, name, region, ordinal++,
				versionKind, List.of());
			PlacementState state = state(fType);
			Node node = new Node(key, kind, value, true, List.of(state), List.of(),
				anchor == null ? List.of() : List.of(anchor));
			nodes.put(key, node);
			origins.put(key, hop);
			return new Ref(key, hop, anchor);
		}

		private void candidate(Ref owner, List<Ref> inputs, boolean includeDerived) {
			List<CandidateInputState> inputStates = inputs.stream()
				.map(ignored -> CandidateInputState.present(fType)).toList();
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputStates);
			PlacementState target = state(fType);
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			if(includeDerived) {
				DurableAnchorKey anchor = inputs.get(0).anchor;
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					owner.key, nodes.get(owner.key).valueVersion(), rule,
					new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), target,
					anchor, inputs.get(0).key, fType, fType, owner.key.controlRegion().normalizedSignature());
				emissions.add(new CandidateEmissionFact(
					new PlacementEmissionState(target, true), fType, action));
			}
			else
				emissions.add(new CandidateEmissionFact(new PlacementEmissionState(target, false), fType));
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), emissions, ""));
			for(int position = 0; position < inputs.size(); position++)
				edges.add(new CompiledInputEdgeFact(inputs.get(position).key, owner.key, position));
		}

		private void candidateLogicalRead(Ref owner) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key,
				List.of(CandidateInputState.present(fType)));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "logical-read", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
		}

		private void candidateFederatedSource(Ref owner) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key,
				List.of(CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "federated-source", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
		}

		private NativePlacementContinuity resolver() {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching);
		}
	}

	private record Ref(CompiledHopKey key, Hop hop, DurableAnchorKey anchor) { }

	private static PlacementState state(FType fType) {
		return new PlacementState(ExecType.FED, FederatedOutput.FOUT, fType, false);
	}

	private static DurableAnchorKey anchor(FType fType, String worker, long start, long end) {
		return new DurableAnchorKey("seed-" + fType + '-' + worker, fType,
			List.of(partition(worker, start, end)));
	}

	private static AnchorPartition partition(String worker, long start, long end) {
		return new AnchorPartition(worker, List.of(start, 0L), List.of(end, 2L));
	}
}

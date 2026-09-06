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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
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
	public void nativeFullLeftIndexChainKeepsOnlyItsGroundedRhsWorker() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("localZeros");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref first = f.leftIndex("first", local, rhs, false, true, false);
		Ref write = f.write("probabilities", first, NodeKind.TRANSIENT_WRITE, false);
		Ref alias = f.logicalRead("probabilities");
		f.reaching.put(alias.key, List.of(write.key));
		Ref second = f.leftIndex("second", alias, rhs, true, true, false);
		Assert.assertTrue("Only PRESENT FULL operands ground a native LIX result",
			f.resolver().proves(List.of(first.key, alias.key, second.key), rhs.anchor));
		Assert.assertTrue("Continuity is not a fabricated output FederationMap", f.nodes.get(first.key).anchors().isEmpty());
	}

	@Test
	public void leftIndexRequiresAnExactNativeRowAndEveryProtectedInput() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("local");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref other = f.source("other", anchor(FType.FULL, "worker2:8002", 0, 4));
		Ref foreign = f.leftIndex("foreign", other, rhs, true, true, false);
		Ref unknown = f.leftIndex("unknown", rhs, local, true, true, false);
		Ref forged = f.leftIndex("forgedLocalRhs", rhs, local, true, false, false);
		Ref derived = f.leftIndex("derived", local, rhs, false, true, true);
		Ref missing = f.leftIndex("missing", local, rhs, false, true, false);
		f.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missing.key);
		for(Ref invalid : List.of(foreign, unknown, forged, derived, missing))
			Assert.assertFalse("Invalid native LIX proof: " + invalid.hop.getName(),
				f.resolver().proves(List.of(invalid.key), rhs.anchor));
	}

	@Test
	public void inheritedAnchorDoesNotCertifyDerivedOnlyLeftIndex() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("local");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref derived = f.leftIndex("derived", local, rhs, false, true, true);
		Node node = f.nodes.get(derived.key);
		f.nodes.put(derived.key, new Node(derived.key, NodeKind.OPERATION, node.valueVersion(),
			true, List.of(state(FType.FULL)), List.of(), List.of(rhs.anchor)));
		Assert.assertFalse("An inherited endpoint is not a native instruction row",
			f.resolver().proves(List.of(derived.key), rhs.anchor));
	}

	@Test
	public void scalarLeftIndexRetainsOnlyAProvenFullLhs() {
		Fixture f = new Fixture(FType.FULL);
		Ref lhs = f.source("lhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref scalar = f.add("scalar", new LiteralOp(1L), NodeKind.OPERATION, VersionKind.ORDINARY, null);
		Ref remote = f.leftIndex("remote", lhs, scalar, true, false, false);
		Assert.assertTrue(f.resolver().proves(List.of(remote.key), lhs.anchor));
		Ref local = f.leftIndex("local", f.read("unknown"), scalar, false, false, false);
		Assert.assertFalse(f.resolver().proves(List.of(local.key), lhs.anchor));
		DurableAnchorKey ranges = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 2), partition("worker1:8001", 2, 4)));
		Ref multi = f.source("multi", ranges);
		Ref matrix = f.leftIndex("multiRhs", lhs, multi, true, true, false);
		Assert.assertFalse("One endpoint with two ranges cannot run the single-FULL kernel",
			f.resolver().proves(List.of(matrix.key), lhs.anchor));
	}

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
	public void fullAppendEverySelectableRowRetainsTheSameOutputPool() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binary("append", OpOp2.CBIND, loopRead, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal()));
		full.additionalCandidate(append, List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL)));
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		Assert.assertTrue("Every local/FULL append row retains the same FULL worker pool",
			full.resolver().proves(List.of(loopRead.key), seed.anchor));
	}

	@Test
	public void publicSourceWithoutDirectBroadcastKeepsRelocationRowActive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("A public source may still reach BROADCAST through explicit relocation",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void protectedSourceWithoutDirectBroadcastMakesRelocationRowInactive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertTrue("Origin residency makes the unsupported BROADCAST relocation row inactive",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void protectedSourceWithSelectableBroadcastAliasKeepsRowActive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.broadcastAlias("seed-alias", seed);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("Any same-value BROADCAST alias keeps the exact row selectable",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendWithBroadcastOnAnotherPoolIsNotNativeFullContinuity() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref broadcast = full.source("broadcast",
			anchor(FType.BROADCAST, "worker2:8002", 0, 50));
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, seed, broadcast);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("A native or unbound BROADCAST emission has no materialization authority",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendWithBroadcastLeftOperandCannotClaimFullOutputContinuity() {
		Fixture full = new Fixture(FType.FULL);
		Ref broadcast = full.source("broadcast",
			anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, broadcast, seed);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.BROADCAST),
			CandidateInputState.present(FType.FULL)));

		Assert.assertFalse("Aligned append copies the left BROADCAST map, not a FULL map",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendOneSelectableRowOnAnotherPoolInvalidatesTheProof() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = full.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, loopRead, other);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal()));
		full.additionalCandidate(append, List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL)));
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		Assert.assertFalse(full.resolver().proves(List.of(loopRead.key), seed.anchor));
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

	@Test
	public void provesNativeElementwiseChainThroughLogicalRead() {
		for(OpOp3 ternaryOp : List.of(OpOp3.PLUS_MULT, OpOp3.MINUS_MULT, OpOp3.IFELSE)) {
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("X", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref product = full.nary("weights", OpOpN.MULT, false, seed, seed);
			Ref ternary = full.ternary("updated", ternaryOp, product, seed, seed);
			Ref replaced = full.replace("finite", ternary);
			Ref write = full.write("X_global", replaced, NodeKind.TRANSIENT_WRITE, false);
			Ref read = full.logicalRead("X_global");
			full.reaching.put(read.key, List.of(write.key));

			Assert.assertTrue("Native nary/ternary/replace kernels copy the selected FULL input map: "
				+ ternaryOp, full.resolver().proves(List.of(read.key), seed.anchor));
		}
	}

	@Test
	public void provesSelectiveUnaryAndSameEndpointFullBinaryContinuity() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey seedAnchor = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("seed", seedAnchor);
		Ref sameWorker = full.source("sameWorker",
			anchor(FType.FULL, "worker1:8001", 70, 90));
		Ref logged = full.unary("logged", OpOp1.LOG, seed, false);
		Ref sum = full.binary("sum", OpOp2.PLUS, logged, sameWorker, false);

		Assert.assertTrue("UnaryElemwiseRule kernels copy the selected native map",
			full.resolver().proves(List.of(logged.key), seedAnchor));
		Assert.assertTrue("Two exact PRESENT FULL operands on one worker retain that worker",
			full.resolver().proves(List.of(sum.key), seedAnchor));
	}

	@Test
	public void selectiveUnaryAndFullBinaryContinuityFailClosed() {
		Fixture missingUnaryRow = new Fixture(FType.FULL);
		Ref missingUnarySeed = missingUnaryRow.source("missingUnarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref missingLog = missingUnaryRow.unary("missingLog", OpOp1.LOG, missingUnarySeed, false);
		missingUnaryRow.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missingLog.key);
		missingUnaryRow.inheritAnchor(missingLog, missingUnarySeed.anchor);
		Assert.assertFalse("An inherited anchor cannot replace an AVAILABLE native unary row",
			missingUnaryRow.resolver().proves(List.of(missingLog.key), missingUnarySeed.anchor));

		Fixture missingBinaryRow = new Fixture(FType.FULL);
		Ref missingBinarySeed = missingBinaryRow.source("missingBinarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref missingPlus = missingBinaryRow.binary("missingPlus", OpOp2.PLUS,
			missingBinarySeed, missingBinarySeed, false);
		missingBinaryRow.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missingPlus.key);
		missingBinaryRow.inheritAnchor(missingPlus, missingBinarySeed.anchor);
		Assert.assertFalse("An inherited anchor cannot replace an AVAILABLE native two-matrix row",
			missingBinaryRow.resolver().proves(List.of(missingPlus.key), missingBinarySeed.anchor));

		Fixture unsupportedUnary = new Fixture(FType.FULL);
		Ref unarySeed = unsupportedUnary.source("unarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cumulative = unsupportedUnary.unary("cumulative", OpOp1.CUMSUM, unarySeed, false);
		Assert.assertFalse("Cumulative unary kernels do not use the non-cumulative map-copy proof",
			unsupportedUnary.resolver().proves(List.of(cumulative.key), unarySeed.anchor));

		Fixture endpoint = new Fixture(FType.FULL);
		Ref first = endpoint.source("first", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = endpoint.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref different = endpoint.binary("different", OpOp2.PLUS, first, other, false);
		Assert.assertFalse("FULL operands on different workers cannot share native continuity",
			endpoint.resolver().proves(List.of(different.key), first.anchor));

		Fixture unknown = new Fixture(FType.FULL);
		Ref known = unknown.source("known", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknownRead = unknown.read("unknown");
		Ref unknownBinary = unknown.binary("unknownBinary", OpOp2.PLUS, known, unknownRead, false);
		Assert.assertFalse("Every PRESENT FULL operand requires exact endpoint authority",
			unknown.resolver().proves(List.of(unknownBinary.key), known.anchor));

		Fixture multi = new Fixture(FType.FULL);
		Ref single = multi.source("single", anchor(FType.FULL, "worker1:8001", 0, 50));
		DurableAnchorKey multiRange = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 25), partition("worker1:8001", 25, 50)));
		Ref ranges = multi.source("ranges", multiRange);
		Ref multiBinary = multi.binary("multiBinary", OpOp2.PLUS, single, ranges, false);
		Assert.assertFalse("The direct FULL/FULL runtime requires one range per operand",
			multi.resolver().proves(List.of(multiBinary.key), single.anchor));

		Fixture unsupportedBinary = new Fixture(FType.FULL);
		Ref binarySeed = unsupportedBinary.source("binarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref solve = unsupportedBinary.binary("solve", OpOp2.SOLVE, binarySeed, binarySeed, false);
		Assert.assertFalse("Only BinaryElemwiseRule kernels use the two-matrix map-copy proof",
			unsupportedBinary.resolver().proves(List.of(solve.key), binarySeed.anchor));

		Fixture cycle = new Fixture(FType.FULL);
		Ref cycleSeed = cycle.source("cycleSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = cycle.read("cycleA");
		Ref cycleB = cycle.read("cycleB");
		cycle.reaching.put(cycleA.key, List.of(cycleB.key));
		cycle.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref cyclicBinary = cycle.binary("cyclicBinary", OpOp2.PLUS, cycleSeed, cycleA, false);
		Assert.assertFalse("A matching branch cannot ground an independent binary-input cycle",
			cycle.resolver().proves(List.of(cyclicBinary.key), cycleSeed.anchor));
	}

	@Test
	public void rejectsElementwiseChainWithoutOneGroundedNativePool() {
		Fixture unknown = new Fixture(FType.FULL);
		Ref unknownSeed = unknown.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknownRead = unknown.read("unknown");
		Ref unknownProduct = unknown.nary("unknownProduct", OpOpN.MULT, false, unknownSeed, unknownRead);
		Assert.assertFalse("An unknown selected matrix input cannot borrow another input's native map",
			unknown.resolver().proves(List.of(unknownProduct.key), unknownSeed.anchor));

		Fixture endpoint = new Fixture(FType.FULL);
		Ref first = endpoint.source("first", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = endpoint.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref mixed = endpoint.nary("mixed", OpOpN.MULT, false, first, other);
		Assert.assertFalse("Every selected matrix input must have the exact witnessed endpoint",
			endpoint.resolver().proves(List.of(mixed.key), first.anchor));

		Fixture cycle = new Fixture(FType.FULL);
		Ref cycleSeed = cycle.source("cycleSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = cycle.read("cycleA");
		Ref cycleB = cycle.read("cycleB");
		cycle.reaching.put(cycleA.key, List.of(cycleB.key));
		cycle.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref cyclicProduct = cycle.nary("cyclicProduct", OpOpN.MULT, false, cycleSeed, cycleA);
		Assert.assertFalse("A good branch must not ground an independent reaching-definition cycle",
			cycle.resolver().proves(List.of(cyclicProduct.key), cycleSeed.anchor));

		Fixture evidence = new Fixture(FType.FULL);
		Ref evidenceSeed = evidence.source("evidenceSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref derivedOnly = evidence.nary("derivedOnly", OpOpN.MULT, true, evidenceSeed, evidenceSeed);
		Assert.assertFalse("Derived FED/FOUT is not native map-copy evidence",
			evidence.resolver().proves(List.of(derivedOnly.key), evidenceSeed.anchor));
		Ref noNativeRow = evidence.naryWithoutCandidate("noNativeRow", OpOpN.MULT,
			evidenceSeed, evidenceSeed);
		Assert.assertFalse("A supported Hop opcode still requires an actual native FED/FOUT candidate row",
			evidence.resolver().proves(List.of(noNativeRow.key), evidenceSeed.anchor));
	}

	@Test
	public void rejectsUnsupportedMembersOfTernaryAndNaryClasses() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("X", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref ctable = full.ternary("ctable", OpOp3.CTABLE, seed, seed, seed);
		Assert.assertFalse("CTABLE changes topology and must not inherit elementwise ternary authority",
			full.resolver().proves(List.of(ctable.key), seed.anchor));
		Ref append = full.nary("append", OpOpN.CBIND, false, seed, seed);
		Assert.assertFalse("Nary append requires a topology proof and is not a BuiltinNary map copy",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	private static final class Fixture {
		private final String fingerprint = "native-continuity-" + System.identityHashCode(this);
		private final FType fType;
		private final Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final List<CandidateRuleFact> candidates = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> reaching = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Privacy> privacy = new IdentityHashMap<>();
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

		private Ref binaryWithoutCandidate(String name, OpOp2 op, Ref left, Ref right) {
			Ref result = add(name, new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, left.hop, right.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			edges.add(new CompiledInputEdgeFact(left.key, result.key, 0));
			edges.add(new CompiledInputEdgeFact(right.key, result.key, 1));
			return result;
		}

		private Ref unary(String name, OpOp1 op, Ref input, boolean derivedEmission) {
			Ref result = add(name, new UnaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, input.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private void privacy(Ref ref, Privacy value) {
			privacy.put(ref.key, value);
		}

		private void broadcastAlias(String name, Ref source) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
			Ref alias = add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY,
				anchor(FType.BROADCAST, "worker1:8001", 0, 50));
			Node node = nodes.get(alias.key);
			PlacementState broadcast = state(FType.BROADCAST);
			nodes.put(alias.key, new Node(node.key(), node.kind(), nodes.get(source.key).valueVersion(),
				node.emittedWork(), List.of(state(FType.FULL), broadcast), node.exclusions(), node.anchors()));
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

		private Ref leftIndex(String name, Ref lhs, Ref rhs, boolean remoteLhs,
			boolean remoteRhs, boolean derived) {
			Hop hop = new LeftIndexingOp(name, DataType.MATRIX, ValueType.FP64, lhs.hop, rhs.hop,
				new LiteralOp(1L), new LiteralOp(4L), new LiteralOp(1L), new LiteralOp(1L), false, true);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			Map<Integer,Ref> remote = new LinkedHashMap<>();
			if(remoteLhs) remote.put(0, lhs);
			if(remoteRhs) remote.put(1, rhs);
			candidateAtPositions(result, remote, derived);
			return result;
		}

		private Ref nary(String name, OpOpN op, boolean derivedEmission, Ref... inputs) {
			Hop[] inputHops = java.util.Arrays.stream(inputs).map(Ref::hop).toArray(Hop[]::new);
			Ref result = add(name, new NaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, inputHops), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			Map<Integer,Ref> matrixInputs = new java.util.LinkedHashMap<>();
			for(int position = 0; position < inputs.length; position++)
				matrixInputs.put(position, inputs[position]);
			candidateAtPositions(result, matrixInputs, derivedEmission);
			return result;
		}

		private Ref naryWithoutCandidate(String name, OpOpN op, Ref... inputs) {
			Hop[] inputHops = java.util.Arrays.stream(inputs).map(Ref::hop).toArray(Hop[]::new);
			return add(name, new NaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, inputHops), NodeKind.OPERATION, VersionKind.ORDINARY, null);
		}

		private Ref ternary(String name, OpOp3 op, Ref first, Ref second, Ref third) {
			Ref result = add(name, new TernaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, first.hop, second.hop, third.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, first, 1, second, 2, third), false);
			return result;
		}

		private Ref replace(String name, Ref target) {
			LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
			params.put("target", target.hop);
			params.put("pattern", new LiteralOp(Double.POSITIVE_INFINITY));
			params.put("replacement", new LiteralOp(0.0));
			Ref result = add(name, new ParameterizedBuiltinOp(name, DataType.MATRIX,
				ValueType.FP64, ParamBuiltinOp.REPLACE, params), NodeKind.OPERATION,
				VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, target), false);
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

		private void inheritAnchor(Ref target, DurableAnchorKey anchor) {
			Node node = nodes.get(target.key);
			nodes.put(target.key, new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), List.of(anchor)));
		}

		private void candidate(Ref owner, List<Ref> inputs, boolean includeDerived) {
			Map<Integer,Ref> matrixInputs = new java.util.LinkedHashMap<>();
			for(int position = 0; position < inputs.size(); position++)
				matrixInputs.put(position, inputs.get(position));
			candidateAtPositions(owner, matrixInputs, includeDerived);
		}

		private void candidateAtPositions(Ref owner, Map<Integer,Ref> matrixInputs,
			boolean includeDerived) {
			List<CandidateInputState> inputStates = new ArrayList<>();
			for(int position = 0; position < owner.hop.getInput().size(); position++)
				inputStates.add(matrixInputs.containsKey(position)
					? CandidateInputState.present(fType) : CandidateInputState.absentLocal());
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputStates);
			PlacementState target = state(fType);
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			if(includeDerived) {
				Ref firstInput = matrixInputs.values().iterator().next();
				DurableAnchorKey anchor = firstInput.anchor;
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					owner.key, nodes.get(owner.key).valueVersion(), rule,
					new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), target,
					anchor, firstInput.key, fType, fType, owner.key.controlRegion().normalizedSignature());
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
			matrixInputs.forEach((position, input) ->
				edges.add(new CompiledInputEdgeFact(input.key, owner.key, position)));
		}

		private void additionalCandidate(Ref owner, List<CandidateInputState> inputs) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputs);
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
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
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy);
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

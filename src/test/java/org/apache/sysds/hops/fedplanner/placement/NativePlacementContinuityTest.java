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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.apache.sysds.common.Types.OpOp4;
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
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Ignore;
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
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
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
	public void candidateSpecificProofKeepsGoodSiblingAndRejectsBadSibling() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = full.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, loopRead, other);
		List<CandidateInputState> good = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal());
		List<CandidateInputState> bad = List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL));
		full.additionalCandidate(append, good);
		full.additionalCandidate(append, bad);
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		NativePlacementContinuity resolver = full.resolver();
		Assert.assertNotNull("A candidate grounded on the seed pool remains executable",
			resolver.proveCandidate(full.reference(append, good), seed.anchor));
		Assert.assertNull("An incompatible sibling candidate cannot borrow the seed proof",
			resolver.proveCandidate(full.reference(append, bad), seed.anchor));
	}

	@Test
	public void completedProofMemoIsContextExactBoundedAndSemanticallyTransparent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 2, 16);

		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(reference, seed.anchor);
		long builtAfterFirst = metrics.snapshot().proofGraphsBuilt();
		long topologyBuiltAfterFirst = metrics.snapshot().topologyExpansionBuilds();
		List<NativePlacementContinuity.NativeContinuityProof> second =
			resolver.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertSame("an immutable completed result may be reused in one snapshot", first, second);
		Assert.assertEquals(builtAfterFirst, metrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals(1, metrics.snapshot().memoHits());
		Assert.assertEquals(1, metrics.snapshot().memoMisses());

		DurableAnchorKey differentProvenance = new DurableAnchorKey("different-provenance", FType.FULL,
			seed.anchor.partitions());
		List<NativePlacementContinuity.NativeContinuityProof> distinctSeed =
			resolver.proveCandidateAlternatives(reference, differentProvenance);
		Assert.assertEquals(2, metrics.snapshot().memoMisses());
		Assert.assertEquals("physical support is independent of seed provenance",
			builtAfterFirst, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue("the support solution is reused before attaching provenance",
			metrics.snapshot().supportMemoHits() > 0);
		Assert.assertEquals("seed provenance stays in the query overlay, not shared topology",
			topologyBuiltAfterFirst, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue("the second seed reuses root-independent occurrence expansion",
			metrics.snapshot().topologyExpansionHits() > 0);
		Assert.assertTrue(distinctSeed.stream().allMatch(proof ->
			proof.externalSeed().equals(differentProvenance)));

		SearchSpaceMetrics zeroMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity zeroBudget = full.resolver(zeroMetrics, 0, 0);
		Assert.assertEquals(first, zeroBudget.proveCandidateAlternatives(reference, seed.anchor));
		long zeroTopologyBuilds = zeroMetrics.snapshot().topologyExpansionBuilds();
		Assert.assertEquals(first, zeroBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(0, zeroMetrics.snapshot().memoHits());
		Assert.assertEquals(2, zeroMetrics.snapshot().memoMisses());
		Assert.assertTrue(zeroMetrics.snapshot().proofGraphsBuilt() > builtAfterFirst);
		Assert.assertEquals("result-cache eviction may rebuild overlays, never unchanged topology",
			zeroTopologyBuilds, zeroMetrics.snapshot().topologyExpansionBuilds());

		SearchSpaceMetrics evictionMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity oneEntry = full.resolver(evictionMetrics, 1, 16);
		Assert.assertEquals(first, oneEntry.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(distinctSeed,
			oneEntry.proveCandidateAlternatives(reference, differentProvenance));
		Assert.assertEquals(first, oneEntry.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("eviction must only cause exact recomputation", 2,
			evictionMetrics.snapshot().memoEvictions());
		Assert.assertEquals(0, evictionMetrics.snapshot().memoHits());
		Assert.assertEquals(3, evictionMetrics.snapshot().memoMisses());
		Assert.assertEquals(1, evictionMetrics.snapshot().memoEntries());
		Assert.assertTrue(evictionMetrics.snapshot().memoRetainedEstimatedBytes() > 0);

		SearchSpaceMetrics byteMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity byteBudget = full.resolver(byteMetrics, 2, 16, 1);
		Assert.assertEquals(first, byteBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(first, byteBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("an oversized completed result is recomputed, never truncated", 0,
			byteMetrics.snapshot().memoHits());
		Assert.assertEquals(0, byteMetrics.snapshot().memoEntries());
	}

	@Test
	public void completedProofMemoRejectsDisabledAndCountExceededBeforeSignatures() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		NativePlacementContinuity.NativeContinuityProof first = bareProof("first");
		NativePlacementContinuity.NativeContinuityProof second = bareProof("second");

		invokeCacheCompletedProofs(full.resolver(new SearchSpaceMetrics(), 0, 16), List.of(first));
		Assert.assertNull("a disabled memo must not normalize rejected proofs", cachedSignature(first));

		invokeCacheCompletedProofs(full.resolver(new SearchSpaceMetrics(), 2, 1), List.of(first, second));
		Assert.assertNull("a count-exceeded result must not normalize its first proof",
			cachedSignature(first));
		Assert.assertNull("a count-exceeded result must not normalize trailing proofs",
			cachedSignature(second));
	}

	@Test
	public void completedProofMemoStopsByteEstimationAfterBudgetRejection() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		NativePlacementContinuity.NativeContinuityProof first = bareProof("first");
		NativePlacementContinuity.NativeContinuityProof trailing = bareProof("trailing");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		invokeCacheCompletedProofs(full.resolver(metrics, 2, 16, 1), List.of(first, trailing));

		Assert.assertNotNull("the proof that crosses the byte limit is measured", cachedSignature(first));
		Assert.assertNull("proofs after a byte-limit rejection must not be visited",
			cachedSignature(trailing));
		Assert.assertEquals(0, metrics.snapshot().memoEntries());
	}

	@Test
	public void completedProofMemoAcceptsAnEstimateEqualToItsByteLimit() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		NativePlacementContinuity.NativeContinuityProof proof = bareProof("exact-limit");
		long exactBytes = 96L + 2L * proof.normalizedSignature().length();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		invokeCacheCompletedProofs(full.resolver(metrics, 2, 16, exactBytes), List.of(proof));

		Assert.assertEquals(1, metrics.snapshot().memoEntries());
		Assert.assertEquals(exactBytes, metrics.snapshot().memoRetainedEstimatedBytes());
	}

	@Test
	public void completedProofMemoBudgetAdditionIsBoundaryAndOverflowSafe() throws Exception {
		Assert.assertFalse("retained plus additional equal to the budget must be allowed",
			invokeExceedsBudget(7, 3, 10));
		Assert.assertTrue("an overflowing retained plus additional sum must be rejected",
			invokeExceedsBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
	}

	@Test
	public void templateSupportMemoRebindsFallbackRootsWithoutChangingProofs() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference staged = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateRealizationReference firstRoot = new CandidateRealizationReference(staged.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				staged.realization().emissionState(), "template-root:first"));
		CandidateRealizationReference secondRoot = new CandidateRealizationReference(staged.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				staged.realization().emissionState(), "template-root:second"));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		Assert.assertFalse(resolver.proveCandidateAlternatives(firstRoot, seed.anchor).isEmpty());
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();

		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondRoot, seed.anchor);
		NativePlacementContinuity uncached = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals(uncached.proveCandidateAlternatives(secondRoot, seed.anchor), actual);
		Assert.assertEquals("fallback roots with the same rule/emission share one graph solution",
			graphBuilds, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().supportMemoHits() > 0);
	}

	@Test
	public void unchangedFactRevisionReusesTopologyAndCompletedSupportSolution() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity firstRevision = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			firstRevision.proveCandidateAlternatives(reference, seed.anchor);
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();
		long topologyBuilds = metrics.snapshot().topologyExpansionBuilds();

		NativePlacementContinuity nextRevision = firstRevision.nextRevision(
			List.copyOf(full.candidates), Set.of());
		Assert.assertEquals(expected,
			nextRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("the public provenance-bearing result is rebuilt from reused support",
			graphBuilds, metrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals("unchanged occurrence expansion crosses the revision",
			topologyBuilds, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue(metrics.snapshot().topologyRevisionEntriesReused() > 0);
		Assert.assertTrue(metrics.snapshot().supportMemoRevisionEntriesReused() > 0);

		NativePlacementContinuity invalidated = firstRevision.nextRevision(
			List.copyOf(full.candidates), Set.of(source.key));
		invalidated.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertTrue("an explicitly invalidated footprint must be solved again",
			metrics.snapshot().proofGraphsBuilt() > graphBuilds);
	}

	@Test
	public void acyclicPruningRetainsDeadBranchInRevisionInvalidationFootprint() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref dead = full.read("dead");
		Ref choice = full.logicalRead("choice");
		full.inheritAnchor(choice, ground.anchor);
		full.reaching.put(choice.key, List.of(dead.key));
		Ref root = full.unary("root", OpOp1.ABS, choice, false);
		CandidateRealizationReference reference = full.reference(root,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity firstRevision = full.resolver(metrics, 8, 128);

		List<NativePlacementContinuity.NativeContinuityProof> expected =
			firstRevision.proveCandidateAlternatives(reference, ground.anchor);
		Assert.assertFalse("the grounded sibling keeps the root viable: " + metrics.snapshot(),
			expected.isEmpty());
		Assert.assertTrue("the dead sibling must be removed by the acyclic pass",
			metrics.snapshot().acyclicAlternativesRemoved() > 0);
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();

		NativePlacementContinuity invalidated = firstRevision.nextRevision(
			List.copyOf(full.candidates), Set.of(dead.key));
		Assert.assertEquals("dead side-branch invalidation cannot change the surviving proof",
			expected, invalidated.proveCandidateAlternatives(reference, ground.anchor));
		Assert.assertTrue("pruning must retain the dead occurrence in the memo footprint",
			metrics.snapshot().proofGraphsBuilt() > graphBuilds);
	}

	@Test
	public void zeroTopologyBudgetRecomputesWithoutChangingProofs() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			full.resolver().proveCandidateAlternatives(reference, seed.anchor);
		String entriesProperty = "sysds.fedplanner.continuityTopology.maxEntries";
		String priorEntries = System.getProperty(entriesProperty);
		try {
			System.setProperty(entriesProperty, "0");
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);
			Assert.assertEquals(expected, resolver.proveCandidateAlternatives(reference, seed.anchor));
			Assert.assertEquals(expected, resolver.proveCandidateAlternatives(reference, seed.anchor));
			Assert.assertTrue(metrics.snapshot().topologyCacheBypasses() > 0);
			Assert.assertEquals(0, metrics.snapshot().topologyCacheEntries());
			Assert.assertEquals(0, metrics.snapshot().topologyExpansionHits());
		}
		finally {
			if(priorEntries == null)
				System.clearProperty(entriesProperty);
			else
				System.setProperty(entriesProperty, priorEntries);
		}
	}

	@Test
	public void candidateSpecificProofRequiresEveryAndDependencyToBeGrounded() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = full.logicalRead("cycleA");
		Ref cycleB = full.logicalRead("cycleB");
		full.reaching.put(cycleA.key, List.of(cycleB.key));
		full.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref product = full.binaryWithoutCandidate("product", OpOp2.PLUS, seed, cycleA);
		List<CandidateInputState> selected = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		full.additionalCandidate(product, selected);

		Assert.assertNull("one grounded sibling cannot discharge an independent ungrounded SCC",
			full.resolver().proveCandidate(full.reference(product, selected), seed.anchor));
	}

	@Test
	public void transientReplayPreservesEveryGroundedImmediateBindingProof() {
		Fixture full = new Fixture(FType.FULL);
		Ref left = full.source("left", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref right = full.source("right", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.binary("producer", OpOp2.PLUS, left, right, false);
		full.samePoolRealizations(producer,
			List.of(CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)),
			new DurableAnchorKey("producer-a", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("producer-b", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))));
		Ref source = full.unary("source", OpOp1.LOG, producer, false);
		CandidateRealizationReference sourceRealization = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		long candidateRealizationsBefore = full.candidates.stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream()).count();

		List<PlacementAnalysis.TransientCompatibilityProof> proofs =
			NeutralPlacementGraphBuilder.nativeTransientCompatibilityProofs(source.key,
				sourceRealization, left.anchor, full.resolver(), List.of());

		Assert.assertEquals("Both same-pool producer bindings must survive transient replay", 2,
			proofs.size());
		Assert.assertEquals("Each replay support must retain a distinct native-continuity signature", 2,
			proofs.stream().map(PlacementAnalysis.TransientCompatibilityProof::normalizedSignature)
				.distinct().count());
		Assert.assertTrue("Both replay proofs must name the source's native continuity",
			proofs.stream().allMatch(proof -> proof.provesNativeContinuity(source.key, source.key)));
		long candidateRealizationsAfter = full.candidates.stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream()).count();
		Assert.assertEquals("Transient proof enumeration must not change the raw candidate universe",
			candidateRealizationsBefore, candidateRealizationsAfter);
	}

	@Test
	public void alignedRowAndColElementwiseInputsPreserveBothExactBindings() {
		for(FType type : List.of(FType.ROW, FType.COL)) {
			Fixture fixture = new Fixture(type);
			DurableAnchorKey pool = anchor(type, "worker1:8001", 0, 50);
			Ref leftSeed = fixture.source("leftSeed", pool);
			Ref rightSeed = fixture.source("rightSeed", pool);
			Ref left = fixture.logicalRead("left");
			Ref right = fixture.logicalRead("right");
			fixture.reaching.put(left.key, List.of(leftSeed.key));
			fixture.reaching.put(right.key, List.of(rightSeed.key));
			Ref sum = fixture.binary("sum", OpOp2.PLUS, left, right, false);
			List<CandidateInputState> inputs = List.of(
				CandidateInputState.present(type), CandidateInputState.present(type));

			NativePlacementContinuity.NativeContinuityProof proof = fixture.resolver().proveCandidate(
				fixture.reference(sum, inputs), pool);
			Assert.assertNotNull(type + " elementwise inputs on one exact pool retain native continuity", proof);
			Assert.assertEquals("Both aligned operands must remain distinct AND dependencies for " + type,
				Set.of(0, 1), proof.immediateBindings().stream()
					.map(binding -> binding.inputPosition()).collect(java.util.stream.Collectors.toSet()));
			Assert.assertEquals("Repeated geometry must not collapse the two operand positions for " + type,
				2, proof.immediateBindings().size());
		}
	}

	@Test
	public void alignedElementwiseInputsRejectMixedTypeDifferentPoolAndUngroundedOperands() {
		Fixture mixed = new Fixture(FType.ROW);
		DurableAnchorKey rowPool = anchor(FType.ROW, "worker1:8001", 0, 50);
		Ref row = mixed.federatedSource("row", rowPool);
		Ref col = mixed.source("col", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref mixedSum = mixed.binaryWithoutCandidate("mixedSum", OpOp2.PLUS, row, col);
		List<CandidateInputState> mixedInputs = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.COL));
		mixed.additionalCandidate(mixedSum, mixedInputs);
		Assert.assertNull("Every PRESENT elementwise input must have the output witness FType",
			mixed.resolver().proveCandidate(mixed.reference(mixedSum, mixedInputs), rowPool));

		Fixture differentPool = new Fixture(FType.ROW);
		Ref first = differentPool.federatedSource("first", rowPool);
		Ref other = differentPool.federatedSource("other",
			anchor(FType.ROW, "worker2:8002", 0, 50));
		Ref crossPool = differentPool.binary("crossPool", OpOp2.PLUS, first, other, false);
		List<CandidateInputState> rowInputs = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW));
		Assert.assertNull("Matching FTypes do not replace exact common-pool grounding",
			differentPool.resolver().proveCandidate(differentPool.reference(crossPool, rowInputs), rowPool));

		Fixture ungrounded = new Fixture(FType.COL);
		DurableAnchorKey colPool = anchor(FType.COL, "worker1:8001", 0, 50);
		Ref grounded = ungrounded.federatedSource("grounded", colPool);
		Ref unknown = ungrounded.read("unknown");
		Ref incomplete = ungrounded.binary("incomplete", OpOp2.PLUS, grounded, unknown, false);
		List<CandidateInputState> colInputs = List.of(
			CandidateInputState.present(FType.COL), CandidateInputState.present(FType.COL));
		Assert.assertNull("Every aligned input still requires an exact grounded realization",
			ungrounded.resolver().proveCandidate(ungrounded.reference(incomplete, colInputs), colPool));
	}

	@Test
	public void candidateSccGroundingCannotBorrowGroundFromIncompleteAlternative() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref ungrounded = full.logicalRead("ungrounded");
		full.reaching.put(ungrounded.key, List.of(ungrounded.key));
		Ref a = full.naryWithoutCandidate("A", OpOpN.MULT, ground, ground, ground);
		Ref b = full.naryWithoutCandidate("B", OpOpN.MULT, ground, ground);
		full.edges.add(new CompiledInputEdgeFact(a.key, a.key, 0));
		full.edges.add(new CompiledInputEdgeFact(b.key, a.key, 1));
		full.edges.add(new CompiledInputEdgeFact(ungrounded.key, a.key, 2));
		full.edges.add(new CompiledInputEdgeFact(a.key, b.key, 0));
		full.edges.add(new CompiledInputEdgeFact(ground.key, b.key, 1));
		full.additionalCandidate(a, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));
		full.additionalCandidate(a, List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)));
		List<CandidateInputState> bInputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		full.additionalCandidate(b, bInputs);

		Assert.assertNull("A->A OR A->{B,U} cannot let B->{A,G} lend G through the unusable AND branch",
			full.resolver().proveCandidate(full.reference(b, bInputs), ground.anchor));
	}

	@Test
	public void candidateSccGroundingPreservesExternallyGroundedLoop() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.logicalRead("loop");
		full.reaching.put(loop.key, List.of(loop.key, ground.key));
		Ref root = full.unary("root", OpOp1.ABS, loop, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);
		Assert.assertNotNull("A cycle remains grounded when one complete AND alternative reaches direct ground",
			resolver.proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertTrue("a self-loop must keep the cyclic SCC fallback",
			metrics.snapshot().cyclicProofGraphs() > 0);
	}

	@Test
	public void exactAcyclicDirectDagMatchesLegacyProofLists() {
		List<DirectDagDifferentialCase> cases = new ArrayList<>();

		Fixture diamond = new Fixture(FType.BROADCAST);
		Ref diamondSeed = diamond.source("seed", anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref left = diamond.logicalRead("left");
		Ref right = diamond.logicalRead("right");
		diamond.reaching.put(left.key, List.of(diamondSeed.key));
		diamond.reaching.put(right.key, List.of(diamondSeed.key));
		Ref diamondRoot = diamond.binary("root", OpOp2.PLUS, left, right, false);
		DurableAnchorKey distinctSeed = new DurableAnchorKey("alternate-seed", FType.BROADCAST,
			diamondSeed.anchor.partitions());
		cases.add(new DirectDagDifferentialCase("diamond-distinct-seed", diamond,
			diamond.reference(diamondRoot, twoBroadcastInputs()), distinctSeed, true));

		Fixture deadSibling = new Fixture(FType.BROADCAST);
		Ref deadGround = deadSibling.source("ground",
			anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref dead = deadSibling.read("dead");
		Ref choice = deadSibling.logicalRead("choice");
		deadSibling.inheritAnchor(choice, deadGround.anchor);
		deadSibling.reaching.put(choice.key, List.of(dead.key));
		Ref deadRoot = deadSibling.unary("root", OpOp1.ABS, choice, false);
		cases.add(new DirectDagDifferentialCase("dead-sibling", deadSibling,
			deadSibling.reference(deadRoot, List.of(CandidateInputState.present(FType.BROADCAST))),
			deadGround.anchor, true));

		Fixture repeated = new Fixture(FType.BROADCAST);
		Ref repeatedSeed = repeated.source("seed",
			anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref repeatedRoot = repeated.binary("root", OpOp2.PLUS, repeatedSeed, repeatedSeed, false);
		cases.add(new DirectDagDifferentialCase("repeated-operand-positions", repeated,
			repeated.reference(repeatedRoot, twoBroadcastInputs()), repeatedSeed.anchor, true));

		Fixture poolMismatch = new Fixture(FType.BROADCAST);
		Ref expectedPool = poolMismatch.source("expected",
			anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref foreignPool = poolMismatch.source("foreign",
			anchor(FType.BROADCAST, "worker2:8002", 0, 50));
		Ref mismatchedRoot = poolMismatch.binary("root", OpOp2.PLUS,
			expectedPool, foreignPool, false);
		cases.add(new DirectDagDifferentialCase("ground-loss-pool-mismatch", poolMismatch,
			poolMismatch.reference(mismatchedRoot, twoBroadcastInputs()), expectedPool.anchor, false));

		for(DirectDagDifferentialCase testCase : cases)
			assertDirectDagMatchesLegacy(testCase);
	}

	@Test
	public void candidateAcyclicChainGroundsDependenciesBeforeTheirOwners() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref dependency = full.logicalRead("dependency");
		Ref owner = full.logicalRead("owner");
		full.reaching.put(dependency.key, List.of(ground.key));
		full.reaching.put(owner.key, List.of(dependency.key));
		Ref root = full.unary("root", OpOp1.ABS, owner, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNotNull("an acyclic chain must ground every dependency before its owner",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertTrue("the chain must use at least one acyclic proof graph",
			metrics.snapshot().acyclicProofGraphs() > 0);
		Assert.assertEquals("the acyclic chain must not invoke the SCC fallback", 0,
			metrics.snapshot().cyclicProofGraphs());
	}

	@Test
	public void candidateAcyclicDeadDependencyIsPrunedWithoutCyclicFallback() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknown = full.read("unknown");
		Ref product = full.binary("product", OpOp2.PLUS, ground, unknown, false);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNull("an empty dependency state must remove its acyclic owner alternative",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(product, inputs), ground.anchor));
		Assert.assertEquals("the dead acyclic dependency must not invoke the SCC fallback", 0,
			metrics.snapshot().cyclicProofGraphs());
		Assert.assertTrue("the acyclic pruning pass must remove the dependent alternative",
			metrics.snapshot().acyclicAlternativesRemoved() > 0);
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

	@Test
	public void candidateProofDistinguishesDynamicReorgResidencyFromExactAxisContinuity() {
		Fixture row = new Fixture(FType.ROW);
		Ref rowSeed = row.source("rowSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reverse = row.reorg("reverse", ReOrgOp.REV, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof reverseProof = row.resolver().proveCandidate(
			row.reference(reverse, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("ROW reverse keeps worker residency even though endpoint-to-range ownership changes",
			reverseProof);
		Assert.assertFalse("ROW reverse must not publish exact partition ranges",
			reverseProof.exactPartitionRanges());

		Ref diag = row.reorg("diag", ReOrgOp.DIAG, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof diagProof = row.resolver().proveCandidate(
			row.reference(diag, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("DIAG keeps native worker residency", diagProof);
		Assert.assertFalse("DIAG recomputes partition ranges", diagProof.exactPartitionRanges());

		Ref roll = row.reorg("roll", ReOrgOp.ROLL, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof rollProof = row.resolver().proveCandidate(
			row.reference(roll, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("ROW ROLL retains native worker residency when ranges split", rollProof);
		Assert.assertFalse("ROW ROLL publishes dynamic ranges computed from the runtime shift",
			rollProof.exactPartitionRanges());

		Fixture full = new Fixture(FType.FULL);
		Ref fullSeed = full.source("fullSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref fullRoll = full.reorg("fullRoll", ReOrgOp.ROLL, fullSeed, false);
		NativePlacementContinuity.NativeContinuityProof fullRollProof = full.resolver().proveCandidate(
			full.reference(fullRoll, List.of(CandidateInputState.present(FType.FULL))), fullSeed.anchor);
		Assert.assertNotNull("FULL ROLL retains its single-worker native residency", fullRollProof);
		Assert.assertFalse("FULL ROLL may split the runtime map and therefore cannot publish exact ranges",
			fullRollProof.exactPartitionRanges());

		Fixture col = new Fixture(FType.COL);
		Ref colSeed = col.source("colSeed", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref colReverse = col.reorg("colReverse", ReOrgOp.REV, colSeed, false);
		NativePlacementContinuity.NativeContinuityProof colReverseProof = col.resolver().proveCandidate(
			col.reference(colReverse, List.of(CandidateInputState.present(FType.COL))), colSeed.anchor);
		Assert.assertNotNull("COL reverse does not reverse partition ownership", colReverseProof);
		Assert.assertTrue("COL reverse preserves its partition-axis intervals",
			colReverseProof.exactPartitionRanges());
		Ref colRoll = col.reorg("colRoll", ReOrgOp.ROLL, colSeed, false);
		NativePlacementContinuity.NativeContinuityProof colRollProof = col.resolver().proveCandidate(
			col.reference(colRoll, List.of(CandidateInputState.present(FType.COL))), colSeed.anchor);
		Assert.assertNotNull("COL ROLL retains native worker residency while runtime ranges split", colRollProof);
		Assert.assertFalse("COL ROLL must not publish the pre-roll durable geometry",
			colRollProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsDynamicReshapeAndDeterministicRexpandAxisChanges() {
		Fixture reshapeFixture = new Fixture(FType.ROW);
		Ref reshapeSeed = reshapeFixture.source("reshapeSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reshape = reshapeFixture.reshape("reshape", reshapeSeed, false, FType.COL);
		NativePlacementContinuity.NativeContinuityProof reshapeProof = reshapeFixture.resolver().proveCandidate(
			reshapeFixture.reference(reshape, List.of(CandidateInputState.present(FType.ROW),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal())), reshapeSeed.anchor);
		Assert.assertNotNull("RESHAPE retains the same native endpoints", reshapeProof);
		Assert.assertFalse("RESHAPE recomputes partition extents", reshapeProof.exactPartitionRanges());
		Assert.assertEquals(FType.COL, reshapeProof.outputWorkerPoolWitness().fType());

		Fixture rexpandFixture = new Fixture(FType.ROW);
		Ref expandSeed = rexpandFixture.source("expandSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref expandRows = rexpandFixture.rexpand("expandRows", expandSeed, "rows", FType.COL);
		NativePlacementContinuity.NativeContinuityProof rowsProof = rexpandFixture.resolver().proveCandidate(
			rexpandFixture.reference(expandRows, rexpandFixture.inputStates(expandRows, 0, FType.ROW)),
			expandSeed.anchor);
		Assert.assertNotNull("REXPAND rows transposes the native ROW axis into COL", rowsProof);
		Assert.assertTrue("REXPAND rows preserves the exact partition-axis intervals under the transpose",
			rowsProof.exactPartitionRanges());
		Assert.assertEquals(FType.COL, rowsProof.outputWorkerPoolWitness().fType());

		Ref expandCols = rexpandFixture.rexpand("expandCols", expandSeed, "cols", FType.ROW);
		NativePlacementContinuity.NativeContinuityProof colsProof = rexpandFixture.resolver().proveCandidate(
			rexpandFixture.reference(expandCols, rexpandFixture.inputStates(expandCols, 0, FType.ROW)),
			expandSeed.anchor);
		Assert.assertNotNull("REXPAND cols preserves the native ROW axis", colsProof);
		Assert.assertTrue(colsProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsCumulativeCastsAndFrameMapCopy() {
		Fixture row = new Fixture(FType.ROW);
		Ref seed = row.source("X", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref cumulative = row.unary("cumulative", OpOp1.CUMSUM, seed, false);
		Assert.assertNotNull("ROW cumulative kernels preserve their native partition axis",
			row.resolver().proveCandidate(
				row.reference(cumulative, List.of(CandidateInputState.present(FType.ROW))), seed.anchor));

		Ref frame = row.cast("frame", seed, DataType.FRAME, ValueType.STRING, OpOp1.CAST_AS_FRAME);
		NativePlacementContinuity.NativeContinuityProof frameProof = row.resolver().proveCandidate(
			row.reference(frame, List.of(CandidateInputState.present(FType.ROW))), seed.anchor);
		Assert.assertNotNull("Matrix-to-frame cast copies the native map", frameProof);
		Assert.assertTrue(frameProof.exactPartitionRanges());

		Ref frameSeed = row.frameSource("frameSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref mapped = row.frameMap("mapped", frameSeed);
		NativePlacementContinuity.NativeContinuityProof mapProof = row.resolver().proveCandidate(
			row.reference(mapped, row.inputStates(mapped, 0, FType.ROW)), frameSeed.anchor);
		Assert.assertNotNull("Frame MAP copies the selected frame FederationMap", mapProof);
		Assert.assertTrue(mapProof.exactPartitionRanges());

		Ref matrix = row.cast("matrix", frameSeed, DataType.MATRIX, ValueType.FP64, OpOp1.CAST_AS_MATRIX);
		NativePlacementContinuity.NativeContinuityProof matrixProof = row.resolver().proveCandidate(
			row.reference(matrix, List.of(CandidateInputState.present(FType.ROW))), frameSeed.anchor);
		Assert.assertNotNull("Frame-to-matrix cast copies the native map", matrixProof);
		Assert.assertTrue(matrixProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsWeightedQuaternaryNativeOutputFamilies() {
		Fixture row = new Fixture(FType.ROW);
		Ref xRow = row.source("Xrow", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref uRow = row.read("Urow");
		Ref vRow = row.read("Vrow");
		for(Ref weighted : List.of(row.wsigmoid("wsigmoid", xRow, uRow, vRow),
			row.wumm("wumm", xRow, uRow, vRow), row.wdivmm("wdivmmBasic", xRow, uRow, vRow, 0, FType.ROW),
			row.wdivmm("wdivmmRight", xRow, uRow, vRow, 2, FType.ROW))) {
			NativePlacementContinuity.NativeContinuityProof proof = row.resolver().proveCandidate(
				row.reference(weighted, row.inputStates(weighted, 0, FType.ROW)), xRow.anchor);
			Assert.assertNotNull(weighted.hop.getName() + " preserves X's native worker pool", proof);
			Assert.assertTrue(weighted.hop.getName() + " preserves X's partition-axis intervals",
				proof.exactPartitionRanges());
		}

		Fixture col = new Fixture(FType.COL);
		Ref xCol = col.source("Xcol", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref uCol = col.read("Ucol");
		Ref vCol = col.read("Vcol");
		Ref left = col.wdivmm("wdivmmLeft", xCol, uCol, vCol, 1, FType.ROW);
		NativePlacementContinuity.NativeContinuityProof leftProof = col.resolver().proveCandidate(
			col.reference(left, col.inputStates(left, 0, FType.COL)), xCol.anchor);
		Assert.assertNotNull("WDIVMM LEFT transposes the exact COL partition axis into output ROW", leftProof);
		Assert.assertTrue(leftProof.exactPartitionRanges());
		Assert.assertEquals(FType.ROW, leftProof.outputWorkerPoolWitness().fType());
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

		private Ref frameSource(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.FRAME, ValueType.STRING, OpOpData.TRANSIENTREAD,
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

		private Ref cast(String name, Ref input, DataType outputType, ValueType valueType, OpOp1 op) {
			Ref result = add(name, new UnaryOp(name, outputType, valueType, op, input.hop),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), false);
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

		private Ref frameMap(String name, Ref frame) {
			Ref result = add(name, new TernaryOp(name, DataType.FRAME, ValueType.STRING, OpOp3.MAP,
				frame.hop, new LiteralOp("fun"), new LiteralOp(1L)),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, frame), false);
			return result;
		}

		private Ref rexpand(String name, Ref target, String direction, FType outputType) {
			LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
			params.put("target", target.hop);
			params.put("max", new LiteralOp(64L));
			params.put("dir", new LiteralOp(direction));
			params.put("cast", new LiteralOp(true));
			params.put("ignore", new LiteralOp(true));
			ParameterizedBuiltinOp hop = new ParameterizedBuiltinOp(name, DataType.MATRIX,
				ValueType.FP64, ParamBuiltinOp.REXPAND, params);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(hop.getParamIndexMap().get("target"), target), false, outputType);
			return result;
		}

		private Ref reshape(String name, Ref input, boolean byRow, FType outputType) {
			ArrayList<Hop> inputs = new ArrayList<>();
			inputs.add(input.hop);
			inputs.add(new LiteralOp(4L));
			inputs.add(new LiteralOp(2L));
			inputs.add(new LiteralOp(byRow));
			Ref result = add(name, new ReorgOp(name, DataType.MATRIX, ValueType.FP64,
				ReOrgOp.RESHAPE, inputs), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, input), false, outputType);
			return result;
		}

		private Ref wsigmoid(String name, Ref x, Ref u, Ref v) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WSIGMOID, x.hop, u.hop, v.hop, false, false),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false);
			return result;
		}

		private Ref wumm(String name, Ref x, Ref u, Ref v) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WUMM, x.hop, u.hop, v.hop, true, OpOp1.MULT2, null),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false);
			return result;
		}

		private Ref wdivmm(String name, Ref x, Ref u, Ref v, int baseType, FType outputType) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x.hop, u.hop, v.hop, new LiteralOp(-1L), baseType, false, false),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false, outputType);
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
			DerivedFoutMaterializationActionKey materializationAction = new DerivedFoutMaterializationActionKey(
				result.key, nodes.get(result.key).valueVersion(), rule,
				new PlacementState(ExecType.FED, FederatedOutput.LOUT, fType, false), target,
				input.anchor, input.key, fType, fType, result.key.controlRegion().normalizedSignature());
			CandidateEmissionFact derived = new CandidateEmissionFact(
				new PlacementEmissionState(target, true), fType, materializationAction);
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
			candidateAtPositions(owner, matrixInputs, includeDerived, fType);
		}

		private void candidateAtPositions(Ref owner, Map<Integer,Ref> matrixInputs,
			boolean includeDerived, FType outputType) {
			List<CandidateInputState> inputStates = new ArrayList<>();
			for(int position = 0; position < owner.hop.getInput().size(); position++)
				inputStates.add(matrixInputs.containsKey(position)
					? CandidateInputState.present(fType) : CandidateInputState.absentLocal());
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputStates);
			PlacementState target = state(outputType);
			if(outputType != fType) {
				Node node = nodes.get(owner.key);
				nodes.put(owner.key, new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
					List.of(target), node.exclusions(), node.anchors()));
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			if(includeDerived) {
				Ref firstInput = matrixInputs.values().iterator().next();
				DurableAnchorKey anchor = firstInput.anchor;
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					owner.key, nodes.get(owner.key).valueVersion(), rule,
					new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), target,
					anchor, firstInput.key, fType, outputType, owner.key.controlRegion().normalizedSignature());
				emissions.add(new CandidateEmissionFact(
					new PlacementEmissionState(target, true), outputType, action));
			}
			else
				emissions.add(new CandidateEmissionFact(new PlacementEmissionState(target, false), outputType));
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, outputType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(outputType), ""), emissions, ""));
			matrixInputs.forEach((position, input) ->
				edges.add(new CompiledInputEdgeFact(input.key, owner.key, position)));
		}

		private List<CandidateInputState> inputStates(Ref owner, int remotePosition, FType remoteType) {
			List<CandidateInputState> states = new ArrayList<>();
			for(int position = 0; position < owner.hop.getInput().size(); position++)
				states.add(position == remotePosition
					? CandidateInputState.present(remoteType) : CandidateInputState.absentLocal());
			return states;
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

		private void samePoolRealizations(Ref owner, List<CandidateInputState> inputs,
			DurableAnchorKey... anchors) {
			CandidateRuleFact fact = candidates.stream().filter(candidate ->
				candidate.key().parentOccurrence() == owner.key
					&& candidate.key().orderedInputs().equals(inputs)).findFirst().orElseThrow();
			CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
			List<CandidateEmissionRealization> realizations = java.util.Arrays.stream(anchors)
				.map(anchor -> CandidateEmissionRealization.durable(
					emission.emissionState(), anchor, List.of(), List.of()))
				.toList();
			CandidateEmissionFact replacement = new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), realizations);
			candidates.set(candidates.indexOf(fact), new CandidateRuleFact(fact.key(), fact.status(),
				fact.capability(), fact.shapeProof(), fact.profile(), List.of(replacement), fact.failureCode()));
		}

		private CandidateRealizationReference reference(Ref owner, List<CandidateInputState> inputs) {
			CandidateRuleFact fact = candidates.stream().filter(candidate ->
				candidate.key().parentOccurrence() == owner.key
					&& candidate.key().orderedInputs().equals(inputs)).findFirst().orElseThrow();
			return CandidateRealizationReference.of(fact.key(),
				fact.allowedEmissionFacts().get(0).realizations().get(0));
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

		private NativePlacementContinuity resolver(SearchSpaceMetrics metrics,
			int memoMaxEntries, long memoMaxProofs) {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy, metrics, memoMaxEntries, memoMaxProofs);
		}

		private NativePlacementContinuity resolver(SearchSpaceMetrics metrics,
			int memoMaxEntries, long memoMaxProofs, long memoMaxEstimatedBytes) {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy, metrics, memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		}
	}

	private record Ref(CompiledHopKey key, Hop hop, DurableAnchorKey anchor) { }
	private record DirectDagDifferentialCase(String name, Fixture fixture,
		CandidateRealizationReference root, DurableAnchorKey externalSeed,
		boolean expectsProof) { }

	private static List<CandidateInputState> twoBroadcastInputs() {
		return List.of(CandidateInputState.present(FType.BROADCAST),
			CandidateInputState.present(FType.BROADCAST));
	}

	private static void assertDirectDagMatchesLegacy(DirectDagDifferentialCase testCase) {
		SearchSpaceMetrics directMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity direct = testCase.fixture.resolver(directMetrics, 0, 0);
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			direct.proveCandidateAlternatives(testCase.root, testCase.externalSeed);
		NativePlacementContinuity legacy = testCase.fixture.resolver(new SearchSpaceMetrics(), 0, 0);
		legacy.disableDirectDagForTesting();
		List<NativePlacementContinuity.NativeContinuityProof> oracle =
			legacy.proveCandidateAlternatives(testCase.root, testCase.externalSeed);
		Assert.assertEquals(testCase.name + " ordered proof list", oracle, actual);
		Assert.assertEquals(testCase.name + " proof presence",
			testCase.expectsProof, !actual.isEmpty());
		Assert.assertTrue(testCase.name + " external seed attachment", actual.stream()
			.allMatch(proof -> proof.externalSeed().equals(testCase.externalSeed)));
		SearchSpaceMetrics.AttributionSnapshot attribution = directMetrics.attributionSnapshot();
		Assert.assertTrue(testCase.name + " direct evaluation is grounding work",
			attribution.phase(SearchSpaceMetrics.Phase.PROOF_GROUNDING).calls() > 0);
		Assert.assertEquals(testCase.name + " direct evaluation constructs no overlay graph", 0,
			attribution.phase(SearchSpaceMetrics.Phase.PROOF_OVERLAY).calls());
	}

	private static NativePlacementContinuity.NativeContinuityProof bareProof(String id) {
		DurableAnchorKey seed = new DurableAnchorKey("seed-" + id, FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		return new NativePlacementContinuity.NativeContinuityProof(seed, seed, true, List.of());
	}

	private static void invokeCacheCompletedProofs(NativePlacementContinuity resolver,
		List<NativePlacementContinuity.NativeContinuityProof> proofs) throws Exception {
		Method cache = null;
		for(Method method : NativePlacementContinuity.class.getDeclaredMethods())
			if(method.getName().equals("cacheCompletedProofs")) {
				cache = method;
				break;
			}
		Assert.assertNotNull(cache);
		cache.setAccessible(true);
		cache.invoke(resolver, null, proofs);
	}

	private static String cachedSignature(
		NativePlacementContinuity.NativeContinuityProof proof) throws Exception {
		Field signature = NativePlacementContinuity.NativeContinuityProof.class
			.getDeclaredField("normalizedSignature");
		signature.setAccessible(true);
		return (String) signature.get(proof);
	}

	private static boolean invokeExceedsBudget(long retained, long additional, long budget)
		throws Exception {
		Method exceedsBudget = NativePlacementContinuity.class.getDeclaredMethod(
			"exceedsBudget", long.class, long.class, long.class);
		exceedsBudget.setAccessible(true);
		return (boolean) exceedsBudget.invoke(null, retained, additional, budget);
	}

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

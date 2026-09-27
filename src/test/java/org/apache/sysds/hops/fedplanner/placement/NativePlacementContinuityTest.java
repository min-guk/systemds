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

import java.lang.reflect.Constructor;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
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
import org.junit.Ignore;
import org.junit.Test;

/** Typed, coinductive native worker-pool continuity proofs. */
public class NativePlacementContinuityTest {
	@Test
	public void directProofTemplateDoesNotConsumePreviouslyPublishedRootReceipt() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey firstAnchor = anchor(FType.FULL, "worker1:8001", 0, 50);
		DurableAnchorKey secondAnchor = new DurableAnchorKey("same-layout-second-value", FType.FULL,
			firstAnchor.partitions());
		Ref source = full.federatedSource("source", firstAnchor);
		List<CandidateInputState> sourceInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		full.samePoolRealizations(source, sourceInputs, firstAnchor, secondAnchor);
		Ref owner = full.unary("owner", OpOp1.LOG, source, false);
		full.privacy(source, Privacy.PRIVATE_AGGREGATE);
		full.privacy(owner, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact ownerFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == owner.key).findFirst().orElseThrow();
		CandidateEmissionFact ownerEmission = ownerFact.allowedEmissionFacts().get(0);
		CandidateRuleFact sourceFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == source.key).findFirst().orElseThrow();
		List<CandidateRealizationReference> sources = sourceFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				sourceFact.key(), realization)).toList();

		CandidateRealizationReference firstQuery = publish(
			full, ownerFact, ownerEmission, sources.get(0), firstAnchor);
		NativePlacementContinuity publishedFirst = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> firstPublished =
			publishedFirst.proveCandidateAlternatives(firstQuery, firstAnchor);
		List<NativePlacementContinuity.NativeContinuityProof> firstTemplate =
			publishedFirst.provePrimitiveCandidateAlternatives(firstQuery, firstAnchor);
		NativePlacementContinuity primitiveFirst = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> firstTemplateReverse =
			primitiveFirst.provePrimitiveCandidateAlternatives(firstQuery, firstAnchor);
		List<NativePlacementContinuity.NativeContinuityProof> firstPublishedReverse =
			primitiveFirst.proveCandidateAlternatives(firstQuery, firstAnchor);
		Assert.assertEquals("public query cache must be independent of proof-only query order",
			firstPublished, firstPublishedReverse);
		Assert.assertEquals("proof-only query cache must be independent of public-query order",
			firstTemplate, firstTemplateReverse);
		Assert.assertTrue("the proof-only root must never escape as an executable input receipt",
			firstTemplate.stream().flatMap(proof -> proof.immediateBindings().stream())
				.noneMatch(binding -> binding.source().equals(firstQuery)));
		CandidateRealizationReference collidingQuery = publishNativeLineage(
			full, ownerFact, ownerEmission, sources.get(0), firstAnchor,
			"native-continuity:primitive-query-root");
		Assert.assertEquals("an ordinary lineage spelling cannot alter generated-root semantics",
			firstTemplate, full.resolver().provePrimitiveCandidateAlternatives(
				collidingQuery, firstAnchor));
		List<NativePlacementContinuity.NativeContinuityProof> secondPublished = publishAndProve(
			full, ownerFact, ownerEmission, sources.get(1), firstAnchor, false);
		List<NativePlacementContinuity.NativeContinuityProof> secondTemplate = publishAndProve(
			full, ownerFact, ownerEmission, sources.get(1), firstAnchor, true);

		Assert.assertNotEquals("fixture must expose root-receipt feedback",
			firstPublished, secondPublished);
		Assert.assertEquals("the proof-only direct rule relation is revision invariant",
			firstTemplate, secondTemplate);
		Assert.assertEquals("both executable same-pool source variants remain feasible",
			2, firstTemplate.size());
	}

	@Test
	public void primitiveProofAdapterRejectsMissingOrAmbiguousCurrentBase() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref owner = full.unary("owner", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(owner, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact ownerFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == owner.key).findFirst().orElseThrow();
		CandidateEmissionFact emission = ownerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference matching = new CandidateRealizationReference(ownerFact.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(emission.emissionState(), "proof-only-query"));
		Assert.assertFalse(full.resolver().provePrimitiveCandidateAlternatives(
			matching, seed.anchor).isEmpty());

		PlacementEmissionState missingState = new PlacementEmissionState(state(FType.ROW), false);
		CandidateRealizationReference missing = new CandidateRealizationReference(ownerFact.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(missingState, "missing-base-query"));
		Assert.assertTrue(full.resolver().provePrimitiveCandidateAlternatives(
			missing, seed.anchor).isEmpty());

		full.candidates.add(ownerFact);
		Assert.assertTrue("two current matching rows are not an arbitrary proof authority",
			full.resolver().provePrimitiveCandidateAlternatives(matching, seed.anchor).isEmpty());
	}

	private static List<NativePlacementContinuity.NativeContinuityProof> publishAndProve(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness,
		boolean templateQuery) {
		CandidateRealizationReference query = publish(
			fixture, original, emission, selectedSource, witness);
		return templateQuery
			? fixture.resolver().provePrimitiveCandidateAlternatives(query, witness)
			: fixture.resolver().proveCandidateAlternatives(query, witness);
	}

	private static CandidateRealizationReference publish(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness) {
		CandidateEmissionRealization published = CandidateEmissionRealization.durable(
			emission.emissionState(), witness, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, selectedSource)));
		return replacePublished(fixture, original, emission, published);
	}

	private static CandidateRealizationReference publishNativeLineage(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness, String lineage) {
		CandidateEmissionRealization published = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), lineage, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, selectedSource)));
		return replacePublished(fixture, original, emission, published);
	}

	private static CandidateRealizationReference replacePublished(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateEmissionRealization published) {
		CandidateEmissionFact replacementEmission = new CandidateEmissionFact(
			emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
			List.of(published));
		CandidateRuleFact replacement = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			List.of(replacementEmission), original.failureCode());
		int index = java.util.stream.IntStream.range(0, fixture.candidates.size())
			.filter(candidate -> fixture.candidates.get(candidate).key().equals(original.key()))
			.findFirst().orElseThrow();
		fixture.candidates.set(index, replacement);
		return CandidateRealizationReference.of(replacement.key(), published);
	}
	@Test
	public void dynamicPartitionRangeWitnessIsMemoizedPerExactWitness() throws Exception {
		Class<?> witnessClass = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Class<?> intervalClass = Class.forName(NativePlacementContinuity.class.getName() + "$AxisInterval");
		Constructor<?> constructor = witnessClass.getDeclaredConstructor(FType.class, List.class, List.class,
			boolean.class);
		constructor.setAccessible(true);
		Constructor<?> intervalConstructor = intervalClass.getDeclaredConstructor(String.class, long.class,
			long.class);
		intervalConstructor.setAccessible(true);
		Method withDynamicPartitionRanges = witnessClass.getDeclaredMethod("withDynamicPartitionRanges");
		withDynamicPartitionRanges.setAccessible(true);
		Method matches = witnessClass.getDeclaredMethod("matches", witnessClass, boolean.class);
		matches.setAccessible(true);
		Field fTypeField = accessibleField(witnessClass, "fType");
		Field endpointsField = accessibleField(witnessClass, "endpoints");
		Field intervalsField = accessibleField(witnessClass, "partitionAxisIntervals");
		Field exactField = accessibleField(witnessClass, "exactPartitionRanges");

		for(FType fType : List.of(FType.ROW, FType.COL, FType.FULL)) {
			List<String> endpoints = List.of("worker1:8001", "worker2:8002");
			List<Object> intervals = List.of(intervalConstructor.newInstance("worker1:8001", 0L, 5L),
				intervalConstructor.newInstance("worker2:8002", 5L, 10L));
			Object exact = constructor.newInstance(fType, endpoints, intervals, true);
			Object equalExact = constructor.newInstance(fType, endpoints, intervals, true);
			int exactHash = exact.hashCode();

			Object firstDynamic = withDynamicPartitionRanges.invoke(exact);
			Object repeatedDynamic = withDynamicPartitionRanges.invoke(exact);
			Object expectedDynamic = constructor.newInstance(fType, endpoints, intervals, false);

			Assert.assertSame("Repeated conversion must reuse the exact witness's dynamic sibling",
				firstDynamic, repeatedDynamic);
			Assert.assertSame("A dynamic witness conversion must be idempotent", firstDynamic,
				withDynamicPartitionRanges.invoke(firstDynamic));
			Assert.assertEquals(fType, fTypeField.get(firstDynamic));
			Assert.assertEquals(endpoints, endpointsField.get(firstDynamic));
			Assert.assertEquals(intervals, intervalsField.get(firstDynamic));
			Assert.assertFalse(exactField.getBoolean(firstDynamic));
			Assert.assertEquals(expectedDynamic, firstDynamic);
			Assert.assertEquals(expectedDynamic.hashCode(), firstDynamic.hashCode());
			Assert.assertNotEquals(exact, firstDynamic);
			Assert.assertEquals(equalExact, exact);
			Assert.assertEquals(exactHash, exact.hashCode());
			for(Object candidate : List.of(exact, firstDynamic,
				constructor.newInstance(fType, List.of("other:9000"), List.of(), false))) {
				for(boolean anchorLayoutExact : List.of(false, true))
					Assert.assertEquals(matches.invoke(expectedDynamic, candidate, anchorLayoutExact),
						matches.invoke(firstDynamic, candidate, anchorLayoutExact));
			}
		}
		for(FType unsupported : List.of(FType.BROADCAST, FType.PART, FType.OTHER)) {
			Object witness = constructor.newInstance(unsupported, List.of("worker1:8001"), List.of(), true);
			Assert.assertSame("Unsupported witness types must not be transformed", witness,
				withDynamicPartitionRanges.invoke(witness));
		}
	}

	private static Field accessibleField(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

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
	public void freshQueryStateRetainsExactInputsButNoQueryHistory() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(source, Privacy.PRIVATE_AGGREGATE);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<CandidateRuleFact> expectedFacts = List.copyOf(full.candidates);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity populated = full.resolver(metrics, 8, 128, 1024 * 1024);
		NativePlacementContinuity cold = full.resolver(new SearchSpaceMetrics(), 8, 128, 1024 * 1024);
		full.candidates.add(expectedFacts.get(0));
		List<NativePlacementContinuity.NativeContinuityProof> populatedProofs =
			populated.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertFalse(populatedProofs.isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"candidateTopologies").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"completedProofMemo").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"completedSupportMemo").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"candidateHandleByReference").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"nativeWitnessByAnchor").get(populated)).isEmpty());
		for(String fieldName : List.of("requiredInputSupportByClause", "canonicalEndpointByWorker",
			"candidateHandleByStructure", "acyclicRootSupportMemo", "acyclicComponentMemo"))
			Assert.assertFalse(fieldName, ((Map<?,?>)accessibleField(
				NativePlacementContinuity.class, fieldName).get(populated)).isEmpty());
		Assert.assertTrue(accessibleField(NativePlacementContinuity.class,
			"nextCandidateHandle").getInt(populated) < -1);
		for(String fieldName : List.of("topologyRetainedRows", "memoRetainedProofs",
			"memoRetainedEstimatedBytes", "supportMemoRetainedTemplates",
			"supportMemoRetainedEstimatedBytes", "acyclicComponentRetainedStates",
			"acyclicComponentRetainedAlternatives"))
			Assert.assertTrue(fieldName, accessibleField(NativePlacementContinuity.class,
				fieldName).getLong(populated) > 0);
		Object populatedQueryObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(populated);
		Assert.assertTrue(accessibleField(populatedQueryObserver.getClass(),
			"size").getInt(populatedQueryObserver) > 0);

		NativePlacementContinuity fresh = populated.freshQueryState();
		Assert.assertNotSame(populated, fresh);
		Assert.assertSame(accessibleField(NativePlacementContinuity.class, "structuralContext").get(populated),
			accessibleField(NativePlacementContinuity.class, "structuralContext").get(fresh));
		Assert.assertSame(metrics, accessibleField(NativePlacementContinuity.class, "metrics").get(fresh));

		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> retainedFacts = (List<CandidateRuleFact>)accessibleField(
			NativePlacementContinuity.class, "candidateFacts").get(fresh);
		Assert.assertEquals(expectedFacts.size(), retainedFacts.size());
		for(int index = 0; index < expectedFacts.size(); index++)
			Assert.assertSame("fact order and authority must be retained",
				expectedFacts.get(index), retainedFacts.get(index));
		Assert.assertThrows(UnsupportedOperationException.class, () -> retainedFacts.add(expectedFacts.get(0)));
		Assert.assertNotSame(accessibleField(NativePlacementContinuity.class,
			"candidateFactsByKey").get(populated), accessibleField(NativePlacementContinuity.class,
			"candidateFactsByKey").get(fresh));

		for(String fieldName : List.of("memoMaxEntries", "memoMaxProofs", "memoMaxEstimatedBytes",
			"supportMemoMaxEntries", "supportMemoMaxTemplates", "supportMemoMaxEstimatedBytes",
			"acyclicComponentMaxEntries", "acyclicComponentMaxStates",
			"acyclicComponentMaxAlternatives", "topologyMaxEntries", "topologyMaxRows"))
			Assert.assertEquals(fieldName, accessibleField(NativePlacementContinuity.class, fieldName).get(populated),
				accessibleField(NativePlacementContinuity.class, fieldName).get(fresh));

		for(String fieldName : List.of("requiredInputSupportByClause", "nativeWitnessByAnchor",
			"canonicalEndpointByWorker", "candidateHandleByReference", "candidateHandleByStructure",
			"candidateTopologies", "completedProofMemo", "completedSupportMemo",
			"acyclicRootSupportMemo", "acyclicComponentMemo")) {
			Object before = accessibleField(NativePlacementContinuity.class, fieldName).get(populated);
			Object after = accessibleField(NativePlacementContinuity.class, fieldName).get(fresh);
			Assert.assertNotSame(fieldName, before, after);
			Assert.assertTrue(fieldName, ((Map<?,?>)after).isEmpty());
		}
		for(String fieldName : List.of("topologyRetainedRows", "memoRetainedProofs",
			"memoRetainedEstimatedBytes", "supportMemoRetainedTemplates",
			"supportMemoRetainedEstimatedBytes", "acyclicComponentRetainedStates",
			"acyclicComponentRetainedAlternatives"))
			Assert.assertEquals(fieldName, 0L,
				accessibleField(NativePlacementContinuity.class, fieldName).getLong(fresh));
		Assert.assertEquals(-1, accessibleField(NativePlacementContinuity.class,
			"nextCandidateHandle").getInt(fresh));

		Object populatedObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(populated);
		Object freshObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(fresh);
		Assert.assertNotSame(populatedObserver, freshObserver);
		Assert.assertEquals(0, accessibleField(freshObserver.getClass(), "size").getInt(freshObserver));
		Assert.assertTrue(((Map<?,?>)accessibleField(freshObserver.getClass(),
			"buckets").get(freshObserver)).isEmpty());

		List<NativePlacementContinuity.NativeContinuityProof> expectedCold =
			cold.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertEquals(expectedCold, fresh.proveCandidateAlternatives(reference, seed.anchor));
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
	public void siblingRealizationQueriesShareOneAcyclicRootRelation() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(root, inputs,
			new DurableAnchorKey("root-a", FType.FULL, seed.anchor.partitions()),
			new DurableAnchorKey("root-b", FType.FULL, seed.anchor.partitions()));
		CandidateRuleFact fact = full.fact(root, inputs);
		List<CandidateRealizationReference> references = fact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				fact.key(), realization)).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		NativePlacementContinuity.CandidateSupportResult first =
			resolver.proveCandidateSupport(references.get(0), seed.anchor);
		Assert.assertFalse(first.proofs().isEmpty());
		Assert.assertTrue(first.dependencyOccurrences().contains(root.key));
		Assert.assertTrue(first.dependencyOccurrences().contains(seed.key));
		long graphs = metrics.snapshot().proofGraphsBuilt();
		Assert.assertFalse(resolver.proveCandidateAlternatives(references.get(1), seed.anchor).isEmpty());
		Assert.assertEquals("one acyclic root relation must serve every exact realization view",
			graphs, metrics.snapshot().proofGraphsBuilt());
	}

	@Test
	public void siblingRealizationsWithDifferentDependencyTuplesDoNotShareRootRelation() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.candidateLogicalRead(seed);
		CandidateRuleFact originalSeedFact = full.candidates.stream()
			.filter(candidate -> candidate.key().parentOccurrence() == seed.key).findFirst().orElseThrow();
		List<CandidateInputState> seedInputs = originalSeedFact.key().orderedInputs();
		full.samePoolRealizations(seed, seedInputs,
			new DurableAnchorKey("seed-a", FType.FULL, seed.anchor.partitions()),
			new DurableAnchorKey("seed-b", FType.FULL, seed.anchor.partitions()));
		CandidateRuleFact seedFact = full.fact(seed, seedInputs);
		List<CandidateRealizationReference> seedReferences = seedFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				seedFact.key(), realization)).toList();
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact rootFact = full.fact(root, inputs);
		CandidateEmissionFact emission = rootFact.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> rootRealizations = java.util.stream.IntStream.range(0, 2)
			.mapToObj(index -> CandidateEmissionRealization.durable(emission.emissionState(),
				new DurableAnchorKey("root-" + index, FType.FULL, seed.anchor.partitions()), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, seedReferences.get(index)))))
			.toList();
		CandidateEmissionFact replacement = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), rootRealizations);
		full.candidates.set(full.candidates.indexOf(rootFact), new CandidateRuleFact(rootFact.key(),
			rootFact.status(), rootFact.capability(), rootFact.shapeProof(), rootFact.profile(),
			List.of(replacement), rootFact.failureCode()));
		List<CandidateRealizationReference> roots = rootRealizations.stream()
			.map(realization -> CandidateRealizationReference.of(rootFact.key(), realization)).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		resolver.proveCandidateAlternatives(roots.get(0), seed.anchor);
		long graphs = metrics.snapshot().proofGraphsBuilt();
		resolver.proveCandidateAlternatives(roots.get(1), seed.anchor);
		Assert.assertTrue("different exact AND tuples require different pinned root relations",
			metrics.snapshot().proofGraphsBuilt() > graphs);
	}

	@Test
	public void distinctRootsReuseAcyclicGroundedChildComponent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref sharedFirst = full.unary("sharedFirst", OpOp1.LOG, seed, false);
		Ref sharedSecond = full.unary("sharedSecond", OpOp1.ABS, sharedFirst, false);
		Ref firstRoot = full.unary("firstRoot", OpOp1.EXP, sharedSecond, false);
		Ref secondRoot = full.unary("secondRoot", OpOp1.SQRT, sharedSecond, false);
		CandidateRealizationReference firstReference = full.reference(firstRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateRealizationReference secondReference = full.reference(secondRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);

		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(firstReference, seed.anchor);
		long firstStates = metrics.snapshot().proofStatesBuilt();
		long firstOverlays = metrics.snapshot().topologyOverlayEvaluations();
		Assert.assertFalse(first.isEmpty());

		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondReference, seed.anchor);
		long secondStates = metrics.snapshot().proofStatesBuilt() - firstStates;
		long secondOverlays = metrics.snapshot().topologyOverlayEvaluations() - firstOverlays;
		NativePlacementContinuity uncached = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals("component reuse must preserve the ordered proof result",
			uncached.proveCandidateAlternatives(secondReference, seed.anchor), actual);
		Assert.assertTrue("a shared child is not rebuilt for a second query root",
			secondStates < firstStates);
		Assert.assertTrue("the second root must consume the child's grounded summary",
			secondOverlays < firstOverlays);

	}

	@Test
	public void distinctRootsReuseGroundedCyclicChildOutsidePinnedComponent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref body = full.unary("body", OpOp1.LOG, loopRead, false);
		Ref write = full.write("loopWrite", body, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));
		Ref firstRoot = full.unary("firstRoot", OpOp1.EXP, loopRead, false);
		Ref secondRoot = full.unary("secondRoot", OpOp1.SQRT, loopRead, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference firstReference = full.reference(firstRoot, inputs);
		CandidateRealizationReference secondReference = full.reference(secondRoot, inputs);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(firstReference, seed.anchor);
		Assert.assertFalse(first.isEmpty());
		long overlays = metrics.snapshot().topologyOverlayEvaluations();
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondReference, seed.anchor);
		Assert.assertEquals(full.resolver(new SearchSpaceMetrics(), 0, 0)
			.proveCandidateAlternatives(secondReference, seed.anchor), actual);
		Assert.assertTrue("the second root must consume the already grounded cyclic child",
			metrics.snapshot().topologyOverlayEvaluations() - overlays < overlays);
	}

	@Test
	public void acyclicComponentAlternativeBudgetBypassesWideBoundary() throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";
		String previous = System.getProperty(property);
		try {
			System.setProperty(property, "1");
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref shared = full.unary("shared", OpOp1.LOG, seed, false);
			List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
			full.samePoolRealizations(shared, inputs,
				new DurableAnchorKey("wide-1", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))),
				new DurableAnchorKey("wide-2", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))));
			Ref first = full.unary("first", OpOp1.EXP, shared, false);
			Ref second = full.unary("second", OpOp1.SQRT, shared, false);
			NativePlacementContinuity cached = full.resolver(new SearchSpaceMetrics(), 8, 128);
			cached.proveCandidateAlternatives(full.reference(first, inputs), seed.anchor);
			Field cacheField = accessibleField(NativePlacementContinuity.class, "acyclicComponentMemo");
			Map<?,?> summaries = (Map<?,?>) cacheField.get(cached);
			for(Object state : summaries.keySet()) {
				Field keyField = accessibleField(state.getClass(), "key");
				Assert.assertNotSame("oversized child must not be retained", shared.key,
					keyField.get(state));
			}
			CandidateRealizationReference secondReference = full.reference(second, inputs);
			Assert.assertEquals("budget fallback must preserve ordered proof results",
				full.resolver(new SearchSpaceMetrics(), 0, 0)
					.proveCandidateAlternatives(secondReference, seed.anchor),
				cached.proveCandidateAlternatives(secondReference, seed.anchor));
		}
		finally {
			if(previous == null)
				System.clearProperty(property);
			else
				System.setProperty(property, previous);
		}
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
			List.copyOf(full.candidates));
		Assert.assertSame("unchanged semantic and seed authority reuse the published proof",
			expected, nextRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("the complete support relation is reused across the revision",
			graphBuilds, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().memoHits() > 0);
		Assert.assertEquals("unchanged occurrence expansion crosses the revision",
			topologyBuilds, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue(metrics.snapshot().topologyRevisionEntriesReused() > 0);
		Assert.assertTrue(metrics.snapshot().supportMemoRevisionEntriesReused() > 0);

		// Exact row comparison invalidates every cached support that uses a changed
		// private execution projection.
		List<CandidateRuleFact> changedFacts = new ArrayList<>(full.candidates);
		int sourceIndex = -1;
		for(int i = 0; i < changedFacts.size(); i++)
			if(changedFacts.get(i).key().parentOccurrence() == source.key) {
				sourceIndex = i;
				break;
			}
		Assert.assertTrue(sourceIndex >= 0);
		CandidateRuleFact original = changedFacts.get(sourceIndex);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> realizations = new ArrayList<>(emission.realizations());
		realizations.add(CandidateEmissionRealization.nativeLineage(emission.emissionState(),
			"revision-added-alternative", List.of(), List.of()));
		CandidateEmissionFact changedEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), realizations);
		changedFacts.set(sourceIndex, new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(changedEmission),
			original.failureCode()));
		long beforeChangedRevision = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity changedRevision = firstRevision.nextRevision(changedFacts);
		changedRevision.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertTrue("changed rows cannot reuse a stale support",
			metrics.snapshot().proofGraphsBuilt() > beforeChangedRevision);
	}

	@Test
	public void revisionReindexesClausePinnedFallbackHandlesWithTopologyRows() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference producerReference = full.withClauses(producer, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		Ref root = full.unary("root", OpOp1.EXP, producer, false);
		CandidateRealizationReference rootReference = full.withClauses(root, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, producerReference)))));
		Ref unrelated = full.unary("unrelated", OpOp1.ABS, seed, false);
		CandidateRealizationReference unrelatedReference = full.reference(unrelated, oneInput);
		Ref other = full.unary("other", OpOp1.SQRT, seed, false);
		CandidateRealizationReference otherReference = full.reference(other, oneInput);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		// A zero result budget still retains topology rows, while preventing a
		// completed proof from hiding revision-local fallback handle rebinding.
		NativePlacementContinuity first = full.resolver(metrics, 0, 0);
		Assert.assertFalse(first.proveCandidateAlternatives(rootReference, seed.anchor).isEmpty());
		Assert.assertFalse(first.proveCandidateAlternatives(unrelatedReference, seed.anchor).isEmpty());
		Assert.assertFalse(first.proveCandidateAlternatives(otherReference, seed.anchor).isEmpty());
		// Change only the access order of unchanged cached topologies. The destination
		// revision now allocates the unrelated row first, so resolver-local negative
		// handles cannot accidentally retain their old numeric meaning.
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(first, seed.anchor);
		Method candidateTopology = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateTopology", CompiledHopKey.class, witness.getClass());
		candidateTopology.setAccessible(true);
		candidateTopology.invoke(first, producer.key, witness);
		candidateTopology.invoke(first, root.key, witness);
		@SuppressWarnings("unchecked")
		Map<Object,Object> topologies = (Map<Object,Object>)accessibleField(
			NativePlacementContinuity.class, "candidateTopologies").get(first);
		Field topologyOccurrence = accessibleField(
			topologies.keySet().iterator().next().getClass(), "occurrence");
		for(CompiledHopKey owner : List.of(unrelated.key, other.key, producer.key, root.key)) {
			Object topologyKey = topologies.keySet().stream().filter(key -> {
				try {
					return topologyOccurrence.get(key) == owner;
				}
				catch(IllegalAccessException exception) {
					throw new AssertionError(exception);
				}
			}).findFirst().orElseThrow();
			topologies.get(topologyKey);
		}
		Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		candidateHandle.setAccessible(true);
		int oldProducerHandle = (int)candidateHandle.invoke(first, producerReference);

		NativePlacementContinuity revised = first.nextRevision(List.copyOf(full.candidates));
		int revisedProducerHandle = (int)candidateHandle.invoke(revised, producerReference);
		Assert.assertNotEquals("the fixture must change the resolver-local handle namespace",
			oldProducerHandle, revisedProducerHandle);
		@SuppressWarnings("unchecked")
		Map<Object,Object> revisedTopologies = (Map<Object,Object>)accessibleField(
			NativePlacementContinuity.class, "candidateTopologies").get(revised);
		Field revisedOccurrence = accessibleField(
			revisedTopologies.keySet().iterator().next().getClass(), "occurrence");
		Object rootTopology = revisedTopologies.entrySet().stream().filter(entry -> {
			try {
				return revisedOccurrence.get(entry.getKey()) == root.key;
			}
			catch(IllegalAccessException exception) {
				throw new AssertionError(exception);
			}
		}).map(Map.Entry::getValue).findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		List<Object> rootRows = (List<Object>)accessibleField(
			rootTopology.getClass(), "rows").get(rootTopology);
		Object defaultAlternative = accessibleField(
			rootRows.get(0).getClass(), "defaultAlternative").get(rootRows.get(0));
		@SuppressWarnings("unchecked")
		List<Object> defaultDependencies = (List<Object>)accessibleField(
			defaultAlternative.getClass(), "dependencies").get(defaultAlternative);
		Field dependencyKey = accessibleField(defaultDependencies.get(0).getClass(), "key");
		Object producerDependency = defaultDependencies.stream().filter(dependency -> {
			try {
				return dependencyKey.get(dependency) == producer.key;
			}
			catch(IllegalAccessException exception) {
				throw new AssertionError(exception);
			}
		}).findFirst().orElseThrow();
		Assert.assertEquals("reindexed defaults must own destination-revision fallback handles",
			revisedProducerHandle,
			accessibleField(producerDependency.getClass(), "realizationHandle")
				.getInt(producerDependency));
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			revised.proveCandidateAlternatives(rootReference, seed.anchor);
		List<NativePlacementContinuity.NativeContinuityProof> fresh =
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateAlternatives(rootReference, seed.anchor);

		Assert.assertFalse("a retained exact pin must not become an empty fallback-handle state",
			actual.isEmpty());
		Assert.assertEquals("reindexed topology must match a fresh resolver", fresh, actual);
		Assert.assertTrue("the unchanged topology crossed the fact revision",
			metrics.snapshot().topologyRevisionEntriesReused() > 0);
	}

	@Test
	public void factRevisionSharesImmutableProgramStructure() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.unary("source", OpOp1.LOG, seed, false);
		NativePlacementContinuity first = full.resolver();
		NativePlacementContinuity next = first.nextRevision(List.copyOf(full.candidates));

		for(String fieldName : List.of("structuralContext", "nodesByKey", "originsByKey",
			"edgesByConsumer", "reachingDefinitions", "occurrenceComponents",
			"incompleteSources", "privacyByKey")) {
			Field field = accessibleField(NativePlacementContinuity.class, fieldName);
			Assert.assertSame(fieldName + " must be shared rather than rebuilt for a fact revision",
				field.get(first), field.get(next));
		}
	}

	@Test
	public void structuralContextMatcherRequiresEveryExactOwnedInventory() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		full.reaching.put(source.key, List.of(seed.key));
		full.privacy.put(source.key, Privacy.PRIVATE_AGGREGATE);
		Set<CompiledHopKey> incomplete = identitySet(seed.key);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			full.nodes, full.origins, full.candidates, full.edges, full.reaching,
			incomplete, full.privacy);

		Map<CompiledHopKey,Node> equalNodes = new IdentityHashMap<>();
		full.nodes.forEach((key, node) -> equalNodes.put(key, new Node(node.key(), node.kind(),
			node.valueVersion(), node.emittedWork(), node.legalAlternatives(), node.exclusions(), node.anchors())));
		Map<CompiledHopKey,Hop> equalOrigins = new IdentityHashMap<>(full.origins);
		List<CompiledInputEdgeFact> equalEdges = full.edges.stream()
			.map(edge -> new CompiledInputEdgeFact(edge.producer(), edge.consumer(), edge.inputPosition()))
			.toList();
		Map<CompiledHopKey,List<CompiledHopKey>> equalReaching = new IdentityHashMap<>();
		full.reaching.forEach((key, values) -> equalReaching.put(key, List.copyOf(values)));
		Map<CompiledHopKey,Privacy> equalPrivacy = new IdentityHashMap<>(full.privacy);
		Assert.assertTrue("equal new Node and edge values with the same owned identities must match",
			continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
				equalReaching, identitySet(seed.key), equalPrivacy));

		Map<CompiledHopKey,Node> changedNodes = new IdentityHashMap<>(equalNodes);
		Node priorSource = changedNodes.get(source.key);
		changedNodes.put(source.key, new Node(priorSource.key(), priorSource.kind(),
			priorSource.valueVersion(), priorSource.emittedWork(), priorSource.legalAlternatives(),
			priorSource.exclusions(), List.of(seed.anchor)));
		Assert.assertFalse(continuity.matchesStructuralContext(changedNodes, equalOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,Node> missingNode = new IdentityHashMap<>(equalNodes);
		missingNode.remove(source.key);
		Assert.assertFalse(continuity.matchesStructuralContext(missingNode, equalOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,Hop> changedOrigins = new IdentityHashMap<>(equalOrigins);
		changedOrigins.put(source.key, seed.hop);
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, changedOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		List<CompiledInputEdgeFact> changedEdges = List.of(
			new CompiledInputEdgeFact(seed.key, source.key, 1));
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, changedEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,List<CompiledHopKey>> provisionalReaching = new IdentityHashMap<>();
		provisionalReaching.put(source.key, List.of());
		Assert.assertFalse("a provisional seed context cannot replace the full reaching context",
			continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
				provisionalReaching, incomplete, equalPrivacy));
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
			equalReaching, Set.of(), equalPrivacy));

		Map<CompiledHopKey,Privacy> changedPrivacy = new IdentityHashMap<>(equalPrivacy);
		changedPrivacy.put(source.key, Privacy.PUBLIC);
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
			equalReaching, incomplete, changedPrivacy));
	}

	@Test
	public void equalNewFactObjectsUseRevisionGeneratedApiAndMatchFreshResolver() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact original = full.fact(root, inputs);
		CandidateEmissionFact originalEmission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			originalEmission.emissionState(), "equal-new-revision", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			original.key(), proposedRealization);
		NativePlacementContinuity first = full.resolver();
		Assert.assertFalse(first.proveGeneratedCandidateAlternatives(
			original, originalEmission, proposed, seed.anchor).isEmpty());

		CandidateEmissionFact copiedEmission = new CandidateEmissionFact(
			originalEmission.emissionState(), originalEmission.executionFType(),
			originalEmission.derivedFoutAction(), new ArrayList<>(originalEmission.realizations()));
		CandidateRuleFact copied = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			List.of(copiedEmission), original.failureCode());
		List<CandidateRuleFact> revisedFacts = new ArrayList<>(full.candidates);
		revisedFacts.set(revisedFacts.indexOf(original), copied);
		NativePlacementContinuity revised = first.nextRevision(revisedFacts);
		List<NativePlacementContinuity.NativeContinuityProof> actual = revised
			.proveGeneratedCandidateAlternatives(copied, copiedEmission, proposed, seed.anchor);
		NativePlacementContinuity fresh = new NativePlacementContinuity(full.nodes, full.origins,
			revisedFacts, full.edges, full.reaching, Set.of(), full.privacy);
		List<NativePlacementContinuity.NativeContinuityProof> expected = fresh
			.proveGeneratedCandidateAlternatives(copied, copiedEmission, proposed, seed.anchor);
		Assert.assertFalse("equal new current fact/emission identities remain valid", actual.isEmpty());
		Assert.assertEquals("revision reuse must agree with a fresh exact-context resolver", expected, actual);
	}

	@Test
	public void cachedTopologyReusesDefaultOverlayForUnrelatedQueryPins() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref firstRoot = full.unary("first-root", OpOp1.LOG, seed, false);
		Ref secondRoot = full.unary("second-root", OpOp1.EXP, seed, false);
		Ref child = full.unary("child", OpOp1.SQRT, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference childReference = full.reference(child, inputs);
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> first = candidateAlternatives(resolver, childReference, seed.anchor,
			Map.of(firstRoot.key, full.reference(firstRoot, inputs)));
		List<?> second = candidateAlternatives(resolver, childReference, seed.anchor,
			Map.of(secondRoot.key, full.reference(secondRoot, inputs)));
		Assert.assertEquals(1, first.size());
		Assert.assertEquals(first, second);
		Assert.assertSame("a row untouched by either exact root pin must reuse its immutable default overlay",
			first.get(0), second.get(0));
	}

	@Test
	public void rootOverlayStillCollapsesRowsThatConvergeAfterPinning() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, inputs,
			anchor(FType.FULL, "worker1:8001", 0, 50),
			anchor(FType.FULL, "worker1:8001", 100, 150));
		CandidateRuleFact producerFact = full.fact(producer, inputs);
		List<CandidateRealizationReference> producerReferences = producerFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization ->
				CandidateRealizationReference.of(producerFact.key(), realization)).toList();
		Ref dependent = full.unary("dependent", OpOp1.EXP, producer, false);
		CandidateRealizationReference dependentReference = full.withClauses(dependent, inputs,
			producerReferences.stream().map(reference -> new CandidateRealizationSupportClause(
				List.of(), List.of(CandidateRealizationInputBinding.direct(0, reference)))).toList());
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> alternatives = candidateAlternatives(resolver, dependentReference, seed.anchor,
			Map.of(producer.key, producerReferences.get(0)));
		Assert.assertEquals("two clause pins forced to the same exact root realization remain one edge",
			1, alternatives.size());
	}

	@Test
	public void recursiveRootDependencyNeverUsesDefaultOverlay() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.unary("loop", OpOp1.LOG, seed, false);
		full.edges.removeIf(edge -> edge.consumer() == loop.key);
		full.edges.add(new CompiledInputEdgeFact(loop.key, loop.key, 0));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference loopReference = full.reference(loop, inputs);
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> alternatives = candidateAlternatives(resolver, loopReference, seed.anchor,
			Map.of(loop.key, loopReference));
		Assert.assertEquals(1, alternatives.size());
		@SuppressWarnings("unchecked")
		List<Object> dependencies = (List<Object>)accessibleField(
			alternatives.get(0).getClass(), "dependencies").get(alternatives.get(0));
		Assert.assertEquals(1, dependencies.size());
		Object state = accessibleField(dependencies.get(0).getClass(), "state").get(dependencies.get(0));
		Assert.assertTrue("a root re-entry must remain query-pinned/template-root",
			accessibleField(state.getClass(), "templateRoot").getBoolean(state));
	}

	@Test
	public void initialStructuralContextRejectsDuplicateCompiledEdgePosition() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		List<CompiledInputEdgeFact> malformed = new ArrayList<>(full.edges);
		malformed.add(new CompiledInputEdgeFact(seed.key, source.key, 0));

		try {
			new NativePlacementContinuity(full.nodes, full.origins, full.candidates,
				malformed, full.reaching, Set.of(), full.privacy);
			Assert.fail("duplicate compiled input positions must fail at initial structure construction");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("Duplicate compiled input edge position", expected.getMessage());
		}
	}

	@Test
	public void metricsCollectionDoesNotChangeProofResults() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));

		Assert.assertEquals(full.resolver().proveCandidateAlternatives(reference, seed.anchor),
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateAlternatives(reference, seed.anchor));
	}

	@Test
	public void directBindingReusesCanonicalImmutableNativeProofBindings() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref leftSource = full.source("left-source", pool);
		Ref rightSource = full.source("right-source", pool);
		Ref left = full.logicalRead("left");
		Ref right = full.logicalRead("right");
		full.reaching.put(left.key, List.of(leftSource.key));
		full.reaching.put(right.key, List.of(rightSource.key));
		List<CandidateInputState> unaryInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(left, unaryInput, pool);
		full.samePoolRealizations(right, unaryInput, pool);
		Ref sum = full.binary("sum", OpOp2.PLUS, left, right, false);
		for(Ref ref : List.of(leftSource, rightSource, left, right, sum))
			full.privacy(ref, Privacy.PRIVATE_AGGREGATE);
		List<CandidateRuleFact> facts = List.copyOf(full.candidates);
		List<Node> nodes = List.copyOf(full.nodes.values());
		NativePlacementContinuity continuity = full.resolver();

		Method indexMethod = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"directBindingIndex", List.class, List.class, List.class, List.class);
		indexMethod.setAccessible(true);
		Object index = indexMethod.invoke(null, facts, nodes, full.edges, facts);
		Method bind = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsMeasured", index.getClass(), List.class,
			Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		for(Hop hop : full.origins.values())
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 4, 2));
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)bind.invoke(
			new NeutralPlacementGraphBuilder(), index, facts, full.origins, shapes,
			continuity, Set.of(sum.key));

		Field memoField = accessibleField(NativePlacementContinuity.class, "completedProofMemo");
		@SuppressWarnings("unchecked")
		Map<Object,Object> memo = (Map<Object,Object>)memoField.get(continuity);
		List<NativePlacementContinuity.NativeContinuityProof> memoProofs = new ArrayList<>();
		for(Object entry : memo.values()) {
			@SuppressWarnings("unchecked")
			List<NativePlacementContinuity.NativeContinuityProof> proofs =
				(List<NativePlacementContinuity.NativeContinuityProof>)accessibleField(
					entry.getClass(), "proofs").get(entry);
			memoProofs.addAll(proofs);
		}
		CandidateRuleFact reboundSum = rebound.stream()
			.filter(fact -> fact.key().parentOccurrence() == sum.key).findFirst().orElseThrow();
		Set<CompiledHopKey> expectedSources = Set.of(left.key, right.key);
		int matchedClauses = 0;
		for(CandidateEmissionFact emission : reboundSum.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					PlacementProofKey authority = clause.proofDependencies().stream()
						.filter(proof -> proof.kind() == PlacementProofKind.NATIVE_CONTINUITY
							&& proof.owner() == sum.key).findFirst().orElse(null);
					if(authority == null)
						continue;
					NativePlacementContinuity.NativeContinuityProof proof = memoProofs.stream()
						.filter(candidate -> candidate.normalizedSignature().equals(
							authority.authoritySignature())).findFirst().orElseThrow();
					Assert.assertEquals(proof.immediateBindings(), clause.inputBindings());
					Assert.assertSame("direct binding must retain the proof's canonical list marker",
						proof.immediateBindings(), clause.inputBindings());
					Assert.assertEquals(expectedSources, clause.inputBindings().stream()
						.map(binding -> binding.source().rule().parentOccurrence())
						.collect(java.util.stream.Collectors.toSet()));
					Assert.assertEquals(List.of(0, 1), clause.inputBindings().stream()
						.map(CandidateRealizationInputBinding::inputPosition).toList());
					Assert.assertThrows(UnsupportedOperationException.class, () ->
						clause.inputBindings().add(clause.inputBindings().get(0)));
					matchedClauses++;
				}
		Assert.assertTrue("the two-input direct native output must be fully grounded", matchedClauses > 0);
	}

	@Test
	public void proofHistoryOnlyRevisionReusesRelationButChangedBindingRebuilds() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.reference(source, inputs);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity first = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			first.proveCandidateAlternatives(reference, seed.anchor);
		long built = metrics.snapshot().proofGraphsBuilt();
		CandidateRuleFact original = full.fact(source, inputs);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateRealizationSupportClause clause = realization.supportClauses().get(0);
		CandidateRealizationSupportClause renamed = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, source.key, "history-only")),
			clause.inputBindings(), clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization changed = new CandidateEmissionRealization(realization.key(),
			List.of(renamed));
		CandidateEmissionFact changedEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(changed));
		CandidateRuleFact changedFact = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(changedEmission),
			original.failureCode());
		List<CandidateRuleFact> historyFacts = new ArrayList<>(full.candidates);
		historyFacts.set(historyFacts.indexOf(original), changedFact);
		Assert.assertNotEquals(original, changedFact);
		NativePlacementContinuity historyRevision = first.nextRevision(historyFacts);
		Assert.assertSame("proof history does not change the private support relation", expected,
			historyRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());

		CandidateRealizationReference seedReference = new CandidateRealizationReference(
			new CandidateRuleKey(seed.key, List.of()),
			PlacementIdentity.PlacementRealizationKey.durable(emission.emissionState(), seed.anchor));
		CandidateRealizationSupportClause rebound = new CandidateRealizationSupportClause(
			renamed.proofDependencies(),
			List.of(CandidateRealizationInputBinding.direct(0, seedReference)),
			renamed.nativeWorkerPoolWitness(), renamed.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization reboundRealization = new CandidateEmissionRealization(
			realization.key(), List.of(rebound));
		CandidateEmissionFact reboundEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(reboundRealization));
		List<CandidateRuleFact> changedBindingFacts = new ArrayList<>(historyFacts);
		changedBindingFacts.set(historyFacts.indexOf(changedFact), new CandidateRuleFact(
			original.key(), original.status(), original.capability(), original.shapeProof(),
			original.profile(), List.of(reboundEmission), original.failureCode()));
		NativePlacementContinuity changedBinding = historyRevision.nextRevision(
			changedBindingFacts);
		changedBinding.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertTrue("an exact input binding changes the private relation",
			metrics.snapshot().proofGraphsBuilt() > built);
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

		full.candidateLogicalRead(dead);
		NativePlacementContinuity invalidated = firstRevision.nextRevision(
			List.copyOf(full.candidates));
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
	public void transientReplayProjectsNativeAlternativesToStableExistentialCertificate() {
		Fixture full = new Fixture(FType.FULL);
		Ref left = full.source("left", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref right = full.source("right", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.binary("producer", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> producerInputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		DurableAnchorKey producerA = new DurableAnchorKey("producer-a", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		DurableAnchorKey producerB = new DurableAnchorKey("producer-b", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		DurableAnchorKey producerC = new DurableAnchorKey("producer-c", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		full.samePoolRealizations(producer, producerInputs, producerA, producerB);
		Ref source = full.unary("source", OpOp1.LOG, producer, false);
		CandidateRealizationReference sourceRealization = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<CandidateRuleFact> twoFacts = List.copyOf(full.candidates);
		List<NativePlacementContinuity.NativeContinuityProof> twoNative = full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor);
		assertDistinctImmediateSources(twoNative, 2);
		PlacementProofKey valueEvidence = new PlacementProofKey(
			PlacementProofKind.VALUE_IDENTITY, source.key, "source-value-evidence");
		List<PlacementProofKey> commonProofs = List.of(valueEvidence);
		List<PlacementAnalysis.TransientCompatibilityProof> twoCertificates =
			transientCertificates(full, source, sourceRealization, left.anchor, commonProofs);
		Assert.assertEquals("one endpoint relation needs one existential certificate", 1,
			twoCertificates.size());
		Assert.assertTrue(twoCertificates.get(0).provesNativeContinuity(source.key, source.key));
		Assert.assertSame("an exact certificate retains the actual seed witness",
			left.anchor, twoCertificates.get(0).nativeWorkerPoolWitness());
		Assert.assertTrue("common value evidence remains on the projected certificate",
			twoCertificates.get(0).dependencies().contains(valueEvidence));
		Assert.assertEquals("projection keeps every typed witness/precision class",
			proofClasses(twoNative, left.anchor), certificateClasses(twoCertificates));
		String stableCertificate = twoCertificates.get(0).normalizedSignature();
		Assert.assertEquals("Native proof and certificate projection must not mutate candidate supports",
			twoFacts, full.candidates);

		DurableAnchorKey differentGeometry = new DurableAnchorKey("same-worker-different-range",
			FType.FULL, List.of(partition("worker1:8001", 100, 150)));
		List<PlacementAnalysis.TransientCompatibilityProof> geometryCertificates =
			transientCertificates(full, source, sourceRealization, differentGeometry, commonProofs);
		Assert.assertEquals(1, geometryCertificates.size());
		Assert.assertNotEquals("complete seed geometry remains part of the certificate",
			stableCertificate, geometryCertificates.get(0).normalizedSignature());
		DurableAnchorKey differentWorker = new DurableAnchorKey("different-worker", FType.FULL,
			List.of(partition("worker2:8002", 0, 50)));
		Assert.assertTrue("a certificate cannot merge an unproved worker pool",
			transientCertificates(full, source, sourceRealization, differentWorker, commonProofs).isEmpty());

		full.samePoolRealizations(producer, producerInputs, producerA, producerB, producerC);
		List<CandidateRuleFact> threeFacts = List.copyOf(full.candidates);
		assertDistinctImmediateSources(full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor), 3);
		Assert.assertEquals("adding a same-relation execution receipt must not rename the certificate",
			List.of(stableCertificate), transientCertificates(
				full, source, sourceRealization, left.anchor, commonProofs)
				.stream().map(PlacementAnalysis.TransientCompatibilityProof::normalizedSignature).toList());
		Assert.assertEquals(threeFacts, full.candidates);

		full.samePoolRealizations(producer, producerInputs, producerB, producerC);
		List<CandidateRuleFact> remainingFacts = List.copyOf(full.candidates);
		assertDistinctImmediateSources(full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor), 2);
		Assert.assertEquals(List.of(stableCertificate),
			transientCertificates(full, source, sourceRealization, left.anchor, commonProofs).stream()
				.map(PlacementAnalysis.TransientCompatibilityProof::normalizedSignature).toList());
		Assert.assertEquals(remainingFacts, full.candidates);

		CandidateRuleFact remainingProducer = full.fact(producer, producerInputs);
		full.candidates.remove(remainingProducer);
		Assert.assertTrue("withdrawing every execution path withdraws the certificate",
			transientCertificates(full, source, sourceRealization, left.anchor, commonProofs).isEmpty());
		full.candidates.add(remainingProducer);
		Assert.assertEquals("restoring a path restores the same endpoint certificate",
			List.of(stableCertificate), transientCertificates(
				full, source, sourceRealization, left.anchor, commonProofs)
				.stream().map(PlacementAnalysis.TransientCompatibilityProof::normalizedSignature).toList());

		Fixture dynamic = new Fixture(FType.ROW);
		Ref dynamicSeed = dynamic.source("dynamicSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reverse = dynamic.reorg("reverse", ReOrgOp.REV, dynamicSeed, false);
		CandidateRealizationReference reverseReference = dynamic.reference(reverse,
			List.of(CandidateInputState.present(FType.ROW)));
		List<NativePlacementContinuity.NativeContinuityProof> dynamicNative = dynamic.resolver()
			.proveCandidateAlternatives(reverseReference, dynamicSeed.anchor);
		List<PlacementAnalysis.TransientCompatibilityProof> dynamicCertificates =
			transientCertificates(dynamic, reverse, reverseReference, dynamicSeed.anchor, List.of());
		Assert.assertFalse(dynamicNative.isEmpty());
		Assert.assertTrue(dynamicNative.stream().noneMatch(
			NativePlacementContinuity.NativeContinuityProof::exactPartitionRanges));
		Assert.assertEquals("dynamic projection keeps its typed witness/precision classes",
			proofClasses(dynamicNative, dynamicSeed.anchor), certificateClasses(dynamicCertificates));

		List<CandidateInputState> sourceInputs =
			List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(source, sourceInputs,
			new DurableAnchorKey("source-a", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("source-b", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))));
		CandidateRuleFact sourceFact = full.fact(source, sourceInputs);
		List<CandidateEmissionRealization> sourceAlternatives =
			sourceFact.allowedEmissionFacts().get(0).realizations();
		CandidateRealizationReference sourceA = CandidateRealizationReference.of(
			sourceFact.key(), sourceAlternatives.get(0));
		CandidateRealizationReference sourceB = CandidateRealizationReference.of(
			sourceFact.key(), sourceAlternatives.get(1));
		String sourceACertificate = transientCertificates(
			full, source, sourceA, left.anchor, commonProofs).get(0).normalizedSignature();
		String sourceBCertificate = transientCertificates(
			full, source, sourceB, left.anchor, commonProofs).get(0).normalizedSignature();
		Assert.assertNotEquals("distinct exact source references remain distinct relations",
			sourceACertificate, sourceBCertificate);
	}

	private static void assertDistinctImmediateSources(
		List<NativePlacementContinuity.NativeContinuityProof> proofs, int expected) {
		Assert.assertEquals("Native continuity keeps every complete execution receipt", expected,
			proofs.size());
		Assert.assertTrue(proofs.stream().allMatch(proof -> proof.immediateBindings().size() == 1));
		Assert.assertEquals(expected, proofs.stream()
			.map(proof -> proof.immediateBindings().get(0).source()).distinct().count());
	}

	private static List<PlacementAnalysis.TransientCompatibilityProof> transientCertificates(
		Fixture fixture, Ref source, CandidateRealizationReference sourceRealization,
		DurableAnchorKey seed, List<PlacementProofKey> commonProofs) {
		return NeutralPlacementGraphBuilder.nativeTransientCompatibilityProofs(source.key,
			sourceRealization, seed, fixture.resolver(), commonProofs);
	}

	private static Set<String> proofClasses(
		List<NativePlacementContinuity.NativeContinuityProof> proofs, DurableAnchorKey seed) {
		return proofs.stream().map(proof -> witnessClass(
			proof.exactPartitionRanges() ? seed : proof.outputWorkerPoolWitness(),
			proof.exactPartitionRanges()))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static Set<String> certificateClasses(
		List<PlacementAnalysis.TransientCompatibilityProof> certificates) {
		return certificates.stream().map(proof -> witnessClass(
			proof.nativeWorkerPoolWitness(), proof.nativeWorkerPoolLayoutExact()))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static String witnessClass(DurableAnchorKey witness, boolean exact) {
		return witness.fType() + "|" + witness.partitions() + "|exact=" + exact;
	}

	@Test
	public void repeatedProducerInputChoosesOneExactRealizationAtBothPositions() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, oneInput,
			new DurableAnchorKey("producer-a", FType.FULL, List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("producer-b", FType.FULL, List.of(partition("worker1:8001", 0, 50))));
		Ref sum = full.binary("sum", OpOp2.PLUS, producer, producer, false);
		List<CandidateInputState> twoInputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<NativePlacementContinuity.NativeContinuityProof> proofs =
			full.resolver(metrics, 0, 0).proveCandidateAlternatives(full.reference(sum, twoInputs), seed.anchor);

		Assert.assertEquals("one owner cannot choose A and B simultaneously", 2, proofs.size());
		for(var proof : proofs) {
			Assert.assertEquals(2, proof.immediateBindings().size());
			Assert.assertEquals(0, proof.immediateBindings().get(0).inputPosition());
			Assert.assertEquals(1, proof.immediateBindings().get(1).inputPosition());
			Assert.assertEquals(proof.immediateBindings().get(0).source(),
				proof.immediateBindings().get(1).source());
		}
		Assert.assertEquals("inconsistent product leaves must never be materialized", 2,
			metrics.snapshot().supportLeaves());
		Assert.assertEquals(2, metrics.snapshot().supportConflictPrefixes());
	}

	@Test
	public void conflictingClausePinsForOneProducerAreRejectedBeforeGrounding() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, oneInput,
			new DurableAnchorKey("producer-a", FType.FULL, List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("producer-b", FType.FULL, List.of(partition("worker1:8001", 0, 50))));
		CandidateRuleFact producerFact = full.fact(producer, oneInput);
		List<CandidateEmissionRealization> realizations =
			producerFact.allowedEmissionFacts().get(0).realizations();
		CandidateRealizationReference a = CandidateRealizationReference.of(producerFact.key(),
			realizations.get(0));
		CandidateRealizationReference b = CandidateRealizationReference.of(producerFact.key(),
			realizations.get(1));
		Ref sum = full.binary("sum", OpOp2.PLUS, producer, producer, false);
		List<CandidateInputState> twoInputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		CandidateRealizationReference root = full.withClauses(sum, twoInputs, List.of(
			new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, a),
				CandidateRealizationInputBinding.direct(1, b)))));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);
		Class<?> witnessClass = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod("nativeWitness",
			DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Method candidateTopology = NativePlacementContinuity.class.getDeclaredMethod("candidateTopology",
			CompiledHopKey.class, witnessClass);
		candidateTopology.setAccessible(true);
		Object topology = candidateTopology.invoke(resolver, sum.key,
			nativeWitness.invoke(resolver, seed.anchor));
		Assert.assertTrue("a contradictory public clause cannot become a private executable row",
			((List<?>)accessibleField(topology.getClass(), "rows").get(topology)).isEmpty());
		Assert.assertEquals(1, metrics.snapshot().contradictoryClausePins());
		Assert.assertTrue("a declared but contradictory realization cannot use a staging fallback",
			resolver.proveCandidateAlternatives(root, seed.anchor).isEmpty());
	}

	@Test
	public void generatedRootProofIsIndependentOfDeclaredSupportHistory() {
		Fixture full = new Fixture(FType.FULL);
		Ref leftSeed = full.source("leftSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref rightSeed = full.source("rightSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref left = full.unary("left", OpOp1.LOG, leftSeed, false);
		Ref right = full.unary("right", OpOp1.EXP, rightSeed, false);
		CandidateRealizationReference leftReference = full.withClauses(left,
			List.of(CandidateInputState.present(FType.FULL)),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		full.withClauses(right, List.of(CandidateInputState.present(FType.FULL)),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		Ref root = full.binary("root", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		CandidateRuleFact absentBase = full.fact(root, inputs);
		CandidateEmissionFact absentEmission = absentBase.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			absentEmission.emissionState(), "generated-root", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			absentBase.key(), proposedRealization);
		NativePlacementContinuity absentResolver = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> absent = absentResolver
			.proveGeneratedCandidateAlternatives(absentBase, absentEmission, proposed, leftSeed.anchor);
		Assert.assertFalse(absent.isEmpty());
		Assert.assertTrue(absent.stream().allMatch(proof -> proof.immediateBindings().size() == 2));

		CandidateRuleFact stagingBase = replaceRootRealization(
			full, absentBase, absentEmission, proposedRealization);
		NativePlacementContinuity stagingResolver = absentResolver.nextRevision(List.copyOf(full.candidates));
		CandidateEmissionFact stagingEmission = stagingBase.allowedEmissionFacts().get(0);
		List<NativePlacementContinuity.NativeContinuityProof> staging = stagingResolver
			.proveGeneratedCandidateAlternatives(stagingBase, stagingEmission, proposed, leftSeed.anchor);

		CandidateRealizationSupportClause partialClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, leftReference)));
		CandidateEmissionRealization partial = new CandidateEmissionRealization(
			proposedRealization.key(), List.of(partialClause));
		CandidateRuleFact partialBase = replaceRootRealization(
			full, stagingBase, stagingEmission, partial);
		NativePlacementContinuity partialResolver = stagingResolver.nextRevision(List.copyOf(full.candidates));
		List<NativePlacementContinuity.NativeContinuityProof> partialProofs = partialResolver
			.proveGeneratedCandidateAlternatives(partialBase,
				partialBase.allowedEmissionFacts().get(0), proposed, leftSeed.anchor);

		Assert.assertEquals("staging history must not change the generator-root relation", absent, staging);
		Assert.assertEquals("an old exact support subset must not constrain generation", absent, partialProofs);
	}

	@Test
	public void generatedRootRejectsStalePrivateAndUngroundedAuthority() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "generated-private", List.of(), List.of()));
		Fixture foreign = new Fixture(FType.FULL);
		Ref foreignSeed = foreign.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref foreignRoot = foreign.unary("root", OpOp1.LOG, foreignSeed, false);
		CandidateRuleFact foreignBase = foreign.fact(foreignRoot, inputs);
		Assert.assertTrue("a non-current trusted row must be rejected", full.resolver()
			.proveGeneratedCandidateAlternatives(foreignBase,
				foreignBase.allowedEmissionFacts().get(0), proposed, seed.anchor).isEmpty());
		CandidateRuleFact unsupported = new CandidateRuleFact(base.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, base.capability(), base.shapeProof(), base.profile(),
			List.of(), "privacy-excluded-generator-base");
		int baseIndex = full.candidates.indexOf(base);
		full.candidates.set(baseIndex, unsupported);
		Assert.assertTrue("a privacy-excluded current row cannot authorize generation", full.resolver()
			.proveGeneratedCandidateAlternatives(unsupported, emission, proposed, seed.anchor).isEmpty());
		full.candidates.set(baseIndex, base);

		full.privacy(root, Privacy.PRIVATE);
		Assert.assertFalse("origin-resident private native operations remain legal", full.resolver()
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor).isEmpty());

		Fixture ungrounded = new Fixture(FType.FULL);
		Ref loopRead = ungrounded.logicalRead("loopRead");
		Ref ungroundedRoot = ungrounded.unary("root", OpOp1.LOG, loopRead, false);
		Ref loopWrite = ungrounded.write("loopWrite", ungroundedRoot, NodeKind.LOOP_PHI, false);
		ungrounded.reaching.put(loopRead.key, List.of(loopWrite.key));
		CandidateRuleFact ungroundedBase = ungrounded.fact(ungroundedRoot, inputs);
		CandidateEmissionFact ungroundedEmission = ungroundedBase.allowedEmissionFacts().get(0);
		CandidateRealizationReference ungroundedOutput = CandidateRealizationReference.of(
			ungroundedBase.key(), CandidateEmissionRealization.nativeLineage(
				ungroundedEmission.emissionState(), "generated-ungrounded", List.of(), List.of()));
		Assert.assertTrue("a dependency cycle without an external ground cannot generate continuity",
			ungrounded.resolver().proveGeneratedCandidateAlternatives(ungroundedBase,
				ungroundedEmission, ungroundedOutput, seed.anchor).isEmpty());
	}

	@Test
	public void generatedRootAcceptsLoopCarriedAliasOnlyWithItsEntrySeed() {
		Fixture full = new Fixture(FType.FULL);
		Ref entry = full.source("X-entry", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref read = full.logicalRead("X-loop-read");
		Ref root = full.unary("loop-body", OpOp1.LOG, read, false);
		Ref carried = full.write("X-loop-write", root, NodeKind.LOOP_PHI, false);
		full.reaching.put(read.key, List.of(entry.key, carried.key));
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "generated-loop-root", List.of(), List.of()));

		List<NativePlacementContinuity.NativeContinuityProof> proofs = full.resolver()
			.proveGeneratedCandidateAlternatives(base, emission, proposed, entry.anchor);

		Assert.assertFalse("entry FED authority must ground the complete loop-carried alias SCC",
			proofs.isEmpty());
		Assert.assertTrue("the generated root must bind the exact logical read realization",
			proofs.stream().allMatch(proof -> proof.immediateBindings().stream().anyMatch(binding ->
				binding.inputPosition() == 0
					&& binding.source().rule().parentOccurrence() == read.key)));
	}

	@Test
	public void generatedRootMemoIsModeLocalAndSourceRevisionInvalidatesIt() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "generated-memo", List.of(), List.of());
		CandidateRealizationReference unsupportedSeed = new CandidateRealizationReference(
			new CandidateRuleKey(seed.key, List.of()),
			PlacementIdentity.PlacementRealizationKey.durable(
				emission.emissionState(), seed.anchor));
		CandidateEmissionRealization declaredInvalid = new CandidateEmissionRealization(
			proposedRealization.key(), List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, unsupportedSeed)))));
		base = replaceRootRealization(full, base, emission, declaredInvalid);
		emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			proposedRealization);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity first = full.resolver(metrics, 8, 128);
		Assert.assertTrue("declared empty support must remain invalid in validation mode",
			first.proveCandidateAlternatives(proposed, seed.anchor).isEmpty());
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertFalse(expected.isEmpty());
		Assert.assertTrue("generation must not contaminate validation caches",
			first.proveCandidateAlternatives(proposed, seed.anchor).isEmpty());
		long built = metrics.snapshot().proofGraphsBuilt();
		Assert.assertSame("same-revision generated query must reuse its exact result", expected,
			first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor));
		Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());
		long supportHits = metrics.snapshot().supportMemoHits();
		DurableAnchorKey equivalentWitness = anchor(
			FType.FULL, "worker1:8001", 100, 150);
		Assert.assertFalse(first.proveGeneratedCandidateAlternatives(
			base, emission, proposed, equivalentWitness).isEmpty());
		Assert.assertEquals("a new public seed with the same native witness reuses generated support",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().supportMemoHits() > supportHits);
		full.candidateLogicalRead(seed);
		NativePlacementContinuity revised = first.nextRevision(List.copyOf(full.candidates));
		revised.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertTrue("a changed source row must invalidate generated proof cache entries",
			metrics.snapshot().proofGraphsBuilt() > built);
	}

	@Test
	public void generatedRootWithdrawsAndRestoresExactSourceSupportAcrossRevisions() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference producerReference = full.withClauses(producer, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact producerFact = full.fact(producer, oneInput);
		Ref root = full.unary("root", OpOp1.EXP, producer, false);
		CandidateRuleFact base = full.fact(root, oneInput);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization oldSupportedRoot = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "old-root-support", List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, producerReference)));
		base = replaceRootRealization(full, base, emission, oldSupportedRoot);
		emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			base.key(), oldSupportedRoot);
		NativePlacementContinuity initial = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> expected = initial
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertFalse(expected.isEmpty());

		full.candidates.remove(producerFact);
		NativePlacementContinuity withdrawn = initial.nextRevision(List.copyOf(full.candidates));
		Assert.assertTrue("old root support cannot survive withdrawal of its exact source row",
			withdrawn.proveGeneratedCandidateAlternatives(
				base, emission, proposed, seed.anchor).isEmpty());

		full.candidates.add(producerFact);
		NativePlacementContinuity restored = withdrawn.nextRevision(List.copyOf(full.candidates));
		Assert.assertEquals("restoring the exact source row restores the generated proof relation",
			expected, restored.proveGeneratedCandidateAlternatives(
				base, emission, proposed, seed.anchor));
	}

	private static CandidateRuleFact replaceRootRealization(Fixture fixture,
		CandidateRuleFact prior, CandidateEmissionFact priorEmission,
		CandidateEmissionRealization replacement) {
		CandidateEmissionFact emission = new CandidateEmissionFact(priorEmission.emissionState(),
			priorEmission.executionFType(), priorEmission.derivedFoutAction(), List.of(replacement));
		CandidateRuleFact updated = new CandidateRuleFact(prior.key(), prior.status(), prior.capability(),
			prior.shapeProof(), prior.profile(), List.of(emission), prior.failureCode());
		fixture.candidates.set(fixture.candidates.indexOf(prior), updated);
		return updated;
	}

	@SuppressWarnings("unchecked")
	private static List<?> candidateAlternatives(NativePlacementContinuity resolver,
		CandidateRealizationReference pinned, DurableAnchorKey seed,
		Map<CompiledHopKey,CandidateRealizationReference> fixed) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, seed);
		Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		candidateHandle.setAccessible(true);
		Map<CompiledHopKey,CandidateRealizationReference> exactFixed = new IdentityHashMap<>();
		exactFixed.putAll(fixed);
		Map<CompiledHopKey,Integer> handles = new IdentityHashMap<>();
		for(var entry : exactFixed.entrySet())
			handles.put(entry.getKey(), (int)candidateHandle.invoke(resolver, entry.getValue()));
		Class<?> generationRoot = Class.forName(
			NativePlacementContinuity.class.getName() + "$GenerationRoot");
		Method alternatives = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, witness.getClass(), boolean.class,
			Map.class, Map.class, generationRoot);
		alternatives.setAccessible(true);
		return (List<?>)alternatives.invoke(resolver, pinned.rule().parentOccurrence(), pinned,
			(int)candidateHandle.invoke(resolver, pinned), witness, true, exactFixed, handles, null);
	}

	@Test
	public void proofHistoryDoesNotDuplicatePrivateTopologyRows() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.withClauses(root, inputs, List.of(
			new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, root.key, "route-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, root.key, "route-b")), List.of())));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<NativePlacementContinuity.NativeContinuityProof> proofs =
			full.resolver(metrics, 0, 0).proveCandidateAlternatives(reference, seed.anchor);
		Fixture control = new Fixture(FType.FULL);
		Ref controlSeed = control.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref controlRoot = control.unary("root", OpOp1.LOG, controlSeed, false);
		CandidateRealizationReference controlReference = control.withClauses(controlRoot, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, controlRoot.key, "route-a")), List.of())));
		SearchSpaceMetrics controlMetrics = new SearchSpaceMetrics();
		Assert.assertEquals(1, control.resolver(controlMetrics, 0, 0)
			.proveCandidateAlternatives(controlReference, controlSeed.anchor).size());

		Assert.assertEquals(1, proofs.size());
		Assert.assertEquals("proof history must not add a private topology edge",
			controlMetrics.snapshot().topologyRowsBuilt(), metrics.snapshot().topologyRowsBuilt());
		Assert.assertTrue("each history-only duplicate must be collapsed before graph expansion",
			metrics.snapshot().topologyRowsCollapsed() > 0);
		Assert.assertEquals("public proof alternatives retain both authorities", 2,
			full.fact(root, inputs).allowedEmissionFacts().get(0).realizations().get(0)
				.supportClauses().size());
	}

	@Test
	public void rootPinCollapsesOnlyEffectivePrivateBackedgeDuplicates() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		full.edges.clear();
		full.edges.add(new CompiledInputEdgeFact(root.key, root.key, 0));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact fact = full.fact(root, inputs);
		var emission = fact.allowedEmissionFacts().get(0).emissionState();
		CandidateRealizationReference a = CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.durable(emission,
				new DurableAnchorKey("backedge-a", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))), List.of(), List.of()));
		CandidateRealizationReference b = CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.durable(emission,
				new DurableAnchorKey("backedge-b", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))), List.of(), List.of()));
		CandidateRealizationReference selected = full.withClauses(root, inputs, List.of(
			new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, a))),
			new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, b)))));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertTrue("a pure self-cycle cannot gain ground from deduplication",
			full.resolver(metrics, 0, 0).proveCandidateAlternatives(selected, seed.anchor).isEmpty());
		Assert.assertEquals("different static clause pins must remain distinct", 0,
			metrics.snapshot().topologyRowsCollapsed());
		Assert.assertTrue("root pin makes the two effective private backedges identical",
			metrics.snapshot().topologyOverlayRowsCollapsed() > 0);
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
	public void candidateSingletonSccRejectsPureSelfCycleWithoutRefinementScan() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.logicalRead("loop");
		full.reaching.put(loop.key, List.of(loop.key));
		Ref root = full.unary("root", OpOp1.ABS, loop, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNull("a singleton self-cycle without an external ground path must remain rejected",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertEquals("singleton components must bypass their refined Tarjan scans", 2,
			metrics.snapshot().sccInvocations());
	}

	@Test
	public void candidateSccGroundingPreservesExternallyGroundedLoop() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.logicalRead("loop");
		full.reaching.put(loop.key, List.of(loop.key, ground.key));
		Ref root = full.unary("root", OpOp1.ABS, loop, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNotNull("A cycle remains grounded when one complete AND alternative reaches direct ground",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertTrue("a self-loop must keep the cyclic SCC fallback",
			metrics.snapshot().cyclicProofGraphs() > 0);
		Assert.assertEquals("grounded singleton components must bypass their refined Tarjan scans", 2,
			metrics.snapshot().sccInvocations());
	}

	@Test
	public void candidateSingletonSccRejectsIncompleteAndWithoutRefinementScan() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknown = full.read("unknown");
		Ref product = full.binaryWithoutCandidate("product", OpOp2.PLUS, ground, unknown);
		full.edges.clear();
		full.edges.add(new CompiledInputEdgeFact(product.key, product.key, 0));
		full.edges.add(new CompiledInputEdgeFact(unknown.key, product.key, 1));
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		full.additionalCandidate(product, inputs);
		full.inheritAnchor(product, ground.anchor);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNull("direct ground cannot discharge an incomplete AND alternative",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(product, inputs), ground.anchor));
		Assert.assertEquals("singleton SCCs should need only the maximal-component scan", 1,
			metrics.snapshot().sccInvocations());
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

		Fixture singleRow = new Fixture(FType.ROW);
		Ref singleRowInput = singleRow.source("single", anchor(FType.ROW, "worker1:8001", 0, 50));
		singleRowInput.hop.setDim1(50);
		Ref singleRowSlice = singleRow.rightIndex("single[,column]", singleRowInput);
		Assert.assertFalse("runtime filter retypes a single ROW partition to FULL",
			singleRow.resolver().proves(List.of(singleRowSlice.key), singleRowInput.anchor));

		Fixture row = new Fixture(FType.ROW);
		DurableAnchorKey partitioned = new DurableAnchorKey("two-row-workers", FType.ROW, List.of(
			partition("worker1:8001", 0, 25), partition("worker2:8002", 25, 50)));
		Ref rowInput = row.source("X", partitioned);
		rowInput.hop.setDim1(50);
		Ref rowSlice = row.rightIndex("X[,column]", rowInput);
		Assert.assertTrue("a proven full-row column slice preserves every ROW worker and row interval",
			row.resolver().proves(List.of(rowSlice.key), rowInput.anchor));
		Ref partialRowSlice = row.rightIndex("X[2:50,column]", rowInput, new LiteralOp(2L));
		Assert.assertFalse("partial ROW indexing can filter or resize the partition axis",
			row.resolver().proves(List.of(partialRowSlice.key), rowInput.anchor));
		Ref dynamicRowSlice = row.rightIndex("X[unknown:50,column]", rowInput,
			new DataOp("unknown", DataType.SCALAR, ValueType.INT64, OpOpData.TRANSIENTREAD,
				"unknown", -1, -1, -1, 1000));
		Assert.assertFalse("unknown ROW bounds cannot prove that every worker survives",
			row.resolver().proves(List.of(dynamicRowSlice.key), rowInput.anchor));
	}

	@Test
	public void protectedDynamicColumnSliceKeepsExactRowPoolThroughCbind() {
		Fixture row = new Fixture(FType.ROW);
		DurableAnchorKey partitioned = new DurableAnchorKey("two-row-workers", FType.ROW, List.of(
			partition("worker1:8001", 0, 25), partition("worker2:8002", 25, 50)));
		Ref source = row.source("X_orig", partitioned);
		source.hop.setDim1(50);
		row.privacy(source, Privacy.PRIVATE_AGGREGATE);
		Hop selectedColumn = new DataOp("column_best", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "column_best", -1, -1, -1, 1000);
		Ref column = row.rightIndex("X_orig[,column_best]", source,
			new LiteralOp(1L), new UnaryOp("nrow", DataType.SCALAR, ValueType.INT64,
				OpOp1.NROW, source.hop), selectedColumn, selectedColumn);
		Ref appended = row.binary("X_global=cbind", OpOp2.CBIND, column, column, false);
		Assert.assertTrue("full-row dynamic column selection keeps the exact ROW axis",
			row.resolver().proves(List.of(column.key), partitioned));
		Assert.assertTrue("aligned ROW cbind keeps every worker and row interval",
			row.resolver().proves(List.of(appended.key), partitioned));
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
			return rightIndex(name, input, new LiteralOp(1L));
		}

		private Ref rightIndex(String name, Ref input, Hop rowLower) {
			return rightIndex(name, input, rowLower, new LiteralOp(50L),
				new LiteralOp(1L), new LiteralOp(1L));
		}

		private Ref rightIndex(String name, Ref input, Hop rowLower, Hop rowUpper,
			Hop colLower, Hop colUpper) {
			IndexingOp hop = new IndexingOp(name, DataType.MATRIX, ValueType.FP64, input.hop,
				rowLower, rowUpper, colLower, colUpper, false, true);
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
			CandidateRuleFact fact = fact(owner, inputs);
			return CandidateRealizationReference.of(fact.key(),
				fact.allowedEmissionFacts().get(0).realizations().get(0));
		}

		private CandidateRuleFact fact(Ref owner, List<CandidateInputState> inputs) {
			return candidates.stream().filter(candidate ->
				candidate.key().parentOccurrence() == owner.key
					&& candidate.key().orderedInputs().equals(inputs)).findFirst().orElseThrow();
		}

		private CandidateRealizationReference withClauses(Ref owner, List<CandidateInputState> inputs,
			List<CandidateRealizationSupportClause> clauses) {
			CandidateRuleFact fact = fact(owner, inputs);
			CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
			CandidateEmissionRealization original = emission.realizations().get(0);
			CandidateEmissionRealization replacement = new CandidateEmissionRealization(original.key(), clauses);
			CandidateEmissionFact updated = new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), List.of(replacement));
			candidates.set(candidates.indexOf(fact), new CandidateRuleFact(fact.key(), fact.status(),
				fact.capability(), fact.shapeProof(), fact.profile(), List.of(updated), fact.failureCode()));
			return CandidateRealizationReference.of(fact.key(), replacement);
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

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... keys) {
		Set<CompiledHopKey> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		result.addAll(List.of(keys));
		return result;
	}
}

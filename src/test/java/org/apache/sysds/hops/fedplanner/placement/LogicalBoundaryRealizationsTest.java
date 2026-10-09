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
import java.util.Set;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
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
	public void sessionBackedClosureMatchesIndependentColdReconstruction() {
		Fixture fixture = new Fixture();
		Assert.assertEquals(closeCold(
			fixture.nodes, fixture.edges, fixture.origins, fixture.facts),
			LogicalBoundaryRealizations.close(
				fixture.nodes, fixture.edges, fixture.origins, fixture.facts));

		ChainedFixture chained = new ChainedFixture();
		Assert.assertEquals(closeCold(
			chained.nodes, chained.edges, chained.origins, chained.facts),
			LogicalBoundaryRealizations.close(
				chained.nodes, chained.edges, chained.origins, chained.facts));
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
	public void selectedHeterogeneousBoundaryClauseCannotBorrowAnotherClause() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, closed);
		CandidateRuleFact reader = fact(closed, f.reader);
		CandidateEmissionFact emission = reader.allowedEmissionFacts().get(0);
		CandidateEmissionRealization valueMap = emission.realizations().stream().filter(realization ->
			realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.VALUE_MAP).findFirst().orElseThrow();
		Assert.assertEquals("The formal keeps all call-context maps, including fixed-pool contexts", 4,
			valueMap.supportClauses().size());
		CandidateSelectionReceipt argumentA = receipt(closed, f.argument, POOL_A);
		CandidateSelectionReceipt writerB = receipt(closed, f.writer, POOL_B);
		var argumentRef = PlacementIdentity.CandidateRealizationReference.of(argumentA.rule(), argumentA.realization());
		var writerRef = PlacementIdentity.CandidateRealizationReference.of(writerB.rule(), writerB.realization());
		var clause = valueMap.supportClauses().stream().filter(candidate ->
			candidate.requiredInputSupport().contains(argumentRef)
				&& candidate.requiredInputSupport().contains(writerRef)).findFirst().orElseThrow();
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(f.reader, new CandidateSelectionReceipt(reader.key(), emission, valueMap, clause, List.of()));
		selected.put(f.argument, argumentA);
		selected.put(f.writer, writerB);
		Assert.assertTrue(relation.canStillBeCompatible(Map.of(), selected, Map.of()));
		selected.put(f.argument, receipt(closed, f.argument, POOL_B));
		selected.put(f.writer, receipt(closed, f.writer, POOL_A));
		Assert.assertFalse("An AB receipt cannot use BA's support", relation.canStillBeCompatible(Map.of(), selected, Map.of()));
	}

	@Test
	public void missingOneSourceDoesNotAuthorizePublishedNativeReader() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		List<CandidateRuleFact> missing = closed.stream().filter(fact -> fact.key().parentOccurrence() != f.writer).toList();
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, missing);
		Assert.assertThrows(IllegalArgumentException.class, () -> relation.validate(missing));
	}

	@Test
	public void formalInputCanBootstrapFromGroundedCallButMustCloseEveryCallBeforeValidation() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> missingWriter = replaceOwner(f.facts, f.writer,
			unavailable(fact(f.facts, f.writer)));
		List<CandidateRuleFact> seeded = LogicalBoundaryRealizations.close(
			f.nodes, f.edges, f.origins, missingWriter);
		CandidateEmissionRealization provisional = fact(seeded, f.reader).allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
			.findFirst().orElseThrow();
		Assert.assertTrue("The grounded call argument must seed the shared formal",
			provisional.supportClauses().stream().anyMatch(clause -> clause.inputBindings().stream()
				.anyMatch(binding -> binding.source().rule().parentOccurrence() == f.argument)));
		Assert.assertThrows("A provisional seed cannot publish before every call source is grounded",
			IllegalArgumentException.class,
			() -> new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, seeded).validate(seeded));

		List<CandidateRuleFact> completed = replaceOwner(seeded, f.writer, fact(f.facts, f.writer));
		completed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, completed);
		new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, completed).validate(completed);
		CandidateEmissionRealization completeMap = fact(completed, f.reader).allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
			.findFirst().orElseThrow();
		Assert.assertTrue("The completed formal proof must name both call sources",
			completeMap.supportClauses().stream().anyMatch(clause -> {
				Set<CompiledHopKey> sources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
				clause.inputBindings().forEach(binding -> sources.add(binding.source().rule().parentOccurrence()));
				return sources.containsAll(List.of(f.argument, f.writer));
			}));
	}

	@Test
	public void unrelatedCandidateIsPreservedButNotExpandedIntoBoundaryOptions() throws Exception {
		Fixture f = new Fixture();
		CompiledHopKey unrelated = key("unrelated");
		CandidateRuleFact source = f.facts.get(0);
		CandidateRuleFact extra = new CandidateRuleFact(new CandidateRuleKey(unrelated, List.of()),
			source.status(), source.capability(), source.shapeProof(), source.profile(),
			source.allowedEmissionFacts(), source.failureCode());
		f.facts.add(extra);
		List<CandidateRuleFact> closed = LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts);
		Assert.assertSame(extra, closed.get(closed.size() - 1));
		var relation = new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, closed);
		relation.validate(closed);
		Assert.assertEquals(2, relation.relations().size());
		var field = LogicalBoundaryRealizations.class.getDeclaredField("options");
		field.setAccessible(true);
		Map<?,?> indexed = (Map<?,?>)field.get(relation);
		Assert.assertFalse("unrelated clauses must not be expanded in each direct wave", indexed.containsKey(unrelated));
		Assert.assertEquals(3, indexed.size());
	}

	@Test
	public void fixedContextSessionMatchesColdClosureAcrossWithdrawalRestorationAndTargetChange() {
		Fixture f = new Fixture();
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		var first = session.close(f.facts, owners(f.facts));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, f.facts),
			first.facts());
		Assert.assertEquals(Set.of(f.reader), first.changedOwners());

		var stableWork = session.work();
		var stable = session.close(first.facts(), Set.of());
		Assert.assertSame(first.facts(), stable.facts());
		Assert.assertTrue(stable.changedOwners().isEmpty());
		Assert.assertEquals(stableWork, session.work());

		List<CandidateRuleFact> withdrawn = replaceOwner(first.facts(), f.writer,
			unavailable(fact(first.facts(), f.writer)));
		var missing = session.close(withdrawn, Set.of(f.writer));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, withdrawn),
			missing.facts());
		Assert.assertEquals(Set.of(f.reader), missing.changedOwners());

		List<CandidateRuleFact> restored = replaceOwner(missing.facts(), f.writer,
			fact(first.facts(), f.writer));
		var restoredResult = session.close(restored, Set.of(f.writer));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, restored),
			restoredResult.facts());
		Assert.assertEquals(Set.of(f.reader), restoredResult.changedOwners());

		List<CandidateRuleFact> targetUnavailable = replaceOwner(restoredResult.facts(), f.reader,
			unavailable(fact(restoredResult.facts(), f.reader)));
		var targetResult = session.close(targetUnavailable, Set.of(f.reader));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, targetUnavailable),
			targetResult.facts());
		Assert.assertTrue(targetResult.changedOwners().isEmpty());
	}

	@Test
	public void settledSessionWithCompleteEmptyDeltaDoesNotRescanFacts() {
		Fixture f = new Fixture();
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		// Empty incoming delta still has to perform the first boundary closure.
		var first = session.close(f.facts, Set.of());
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, f.facts), first.facts());
		List<CandidateRuleFact> unchanged = new java.util.AbstractList<>() {
			@Override public int size() { return first.facts().size(); }
			@Override public CandidateRuleFact get(int index) {
				throw new AssertionError("A complete empty owner delta must not rescan settled facts");
			}
		};
		var stable = session.close(unchanged, Set.of());
		Assert.assertSame(unchanged, stable.facts());
		Assert.assertTrue(stable.changedOwners().isEmpty());
	}

	@Test
	public void sessionReprojectsOnlyChangedOwnerSlotsAndAffectedBoundaryTargets() {
		Fixture f = new Fixture();
		CompiledHopKey unrelated = key("session-unrelated");
		CandidateRuleFact unrelatedFact = new CandidateRuleFact(
			new CandidateRuleKey(unrelated, List.of()), f.facts.get(0).status(),
			f.facts.get(0).capability(), f.facts.get(0).shapeProof(), f.facts.get(0).profile(),
			f.facts.get(0).allowedEmissionFacts(), f.facts.get(0).failureCode());
		List<CandidateRuleFact> initial = new ArrayList<>(f.facts);
		initial.add(unrelatedFact);
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, initial);
		var closed = session.close(initial, owners(initial));
		var before = session.work();

		List<CandidateRuleFact> withdrawn = replaceOwner(closed.facts(), f.writer,
			unavailable(fact(closed.facts(), f.writer)));
		var result = session.close(withdrawn, Set.of(f.writer));
		var delta = session.work().minus(before);
		Assert.assertEquals("only the changed source and resulting boundary row are reprojected",
			2, delta.optionFactSlotsVisited());
		Assert.assertEquals("only the dependent reader row is rebound", 1, delta.boundaryFactSlotsVisited());
		Assert.assertEquals(0, delta.topologyBuilds());
		Assert.assertSame(unrelatedFact, result.facts().get(result.facts().size() - 1));
	}

	@Test
	public void sessionUsesSynchronousRoundsForDependentBoundaryCarriers() {
		ChainedFixture f = new ChainedFixture();
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		var result = session.close(f.facts, owners(f.facts));
		List<CandidateRuleFact> cold = closeCold(f.nodes, f.edges, f.origins, f.facts);
		Assert.assertEquals(cold, result.facts());
		Assert.assertEquals(Set.of(f.reader, f.downstreamReader), result.changedOwners());
		var downstream = fact(result.facts(), f.downstreamReader).allowedEmissionFacts().get(0).realizations();
		Assert.assertEquals("downstream retains both fixed-map options", 2,
			downstream.stream().filter(realization -> realization.key().layoutKind()
				!= PlacementIdentity.PlacementLayoutKind.VALUE_MAP).count());
		Assert.assertEquals("downstream also retains the heterogeneous source map", 1,
			downstream.stream().filter(realization -> realization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.VALUE_MAP).count());
	}

	@Test
	public void exactToDynamicSourceRevisionMatchesColdAllSourceOracle() {
		Fixture f = new Fixture();
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		var closed = session.close(f.facts, owners(f.facts));
		CandidateRuleFact writer = fact(closed.facts(), f.writer);
		CandidateEmissionFact dynamicEmission = new CandidateEmissionFact(NATIVE, FType.ROW, null,
			List.of(dynamic(f.writer, POOL_A), durable(f.writer, POOL_B)));
		CandidateRuleFact dynamicWriter = new CandidateRuleFact(writer.key(), writer.status(), writer.capability(),
			writer.shapeProof(), writer.profile(), List.of(dynamicEmission), writer.failureCode());
		List<CandidateRuleFact> revision = replaceOwner(closed.facts(), f.writer, dynamicWriter);
		var result = session.close(revision, Set.of(f.writer));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, revision),
			result.facts());
		CandidateEmissionRealization poolA = fact(result.facts(), f.reader).allowedEmissionFacts().get(0)
			.realizations().stream().filter(realization -> {
				DurableAnchorKey pool = realization.nativeWorkerPoolResidencyWitness(
					realization.requireSingletonSupportClause());
				return pool != null && PlacementIdentity.samePhysicalWorkerEndpoints(pool, POOL_A);
			})
			.findFirst().orElseThrow();
		Assert.assertFalse(poolA.nativeWorkerPoolLayoutExact(poolA.requireSingletonSupportClause()));
	}

	@Test
	public void cyclicBoundaryTopologyRemainsIncompleteLikeColdOracle() {
		Fixture f = new Fixture();
		f.edges.add(new Constraint(ConstraintKind.FUNCTION_INPUT_TRANSFER, f.boundary, f.boundary, 1,
			"function-argument:cycle"));
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		var result = session.close(f.facts, owners(f.facts));
		Assert.assertEquals(closeCold(f.nodes, f.edges, f.origins, f.facts),
			result.facts());
		Assert.assertFalse(new LogicalBoundaryRealizations(f.nodes, f.edges, f.origins, result.facts())
			.hasCompleteBoundary(f.reader));
		Assert.assertTrue(fact(result.facts(), f.reader).allowedEmissionFacts().get(0).realizations().stream()
			.allMatch(realization -> realization.nativeWorkerPoolResidencyWitness(
				realization.requireSingletonSupportClause()) == null));
	}

	@Test
	public void carrierCreatedAndWithdrawnDuringClosureMatchesColdRounds() {
		ChainedFixture f = new ChainedFixture();
		f.edges.removeIf(edge -> edge.right() == f.downstreamReader);
		f.edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, f.reader, f.downstreamReader,
			0, "function-formal-input"));
		for(int index = 0; index < f.nodes.size(); index++) {
			Node node = f.nodes.get(index);
			if(node.key() == f.reader)
				f.nodes.set(index, new Node(node.key(), NodeKind.FUNCTION_INPUT, node.valueVersion(),
					node.emittedWork(), node.legalAlternatives(), node.exclusions(), node.anchors()));
		}
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, f.facts);
		List<CandidateRuleFact> expected = coldClose(f.nodes, f.edges, f.origins, f.facts);
		var first = session.close(f.facts, Set.of());
		Assert.assertEquals(expected, first.facts());
		Assert.assertEquals(expected, LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, f.facts));
		Assert.assertEquals(List.of(f.reader), new LogicalBoundaryRealizations(
			f.nodes, f.edges, f.origins, first.facts()).sources(f.downstreamReader));
		Assert.assertEquals(2, session.work().topologyBuilds());

		Assert.assertTrue(hasValueMap(fact(first.facts(), f.reader)));
		List<CandidateRuleFact> revision = first.facts();
		for(CompiledHopKey source : List.of(f.argument, f.writer))
			revision = replaceOwner(revision, source, unavailable(fact(revision, source)));
		var withdrawn = session.close(revision, Set.of(f.argument, f.writer));
		Assert.assertEquals(coldClose(f.nodes, f.edges, f.origins, revision), withdrawn.facts());
		Assert.assertEquals(withdrawn.facts(), LogicalBoundaryRealizations.close(
			f.nodes, f.edges, f.origins, revision));
		Assert.assertFalse(hasValueMap(fact(withdrawn.facts(), f.reader)));
		Assert.assertEquals(Set.of(f.argument, f.writer), Set.copyOf(new LogicalBoundaryRealizations(
			f.nodes, f.edges, f.origins, withdrawn.facts()).sources(f.downstreamReader)));
		Assert.assertEquals(3, session.work().topologyBuilds());
	}

	@Test
	public void structurallyEqualFinalRoundRetainsCurrentSourceParentIdentity() {
		Fixture f = new Fixture();
		List<CandidateRuleFact> closed = coldClose(f.nodes, f.edges, f.origins, f.facts);
		CompiledHopKey currentSource = key(f.argument.emittedHopInstance());
		Assert.assertEquals(f.argument, currentSource);
		Assert.assertNotSame(f.argument, currentSource);
		for(int index = 0; index < f.nodes.size(); index++) {
			Node node = f.nodes.get(index);
			if(node.key() == f.argument)
				f.nodes.set(index, new Node(currentSource, node.kind(), node.valueVersion(), node.emittedWork(),
					node.legalAlternatives(), node.exclusions(), node.anchors()));
		}
		f.origins.put(currentSource, f.origins.remove(f.argument));
		f.edges.replaceAll(edge -> edge.left() != f.argument ? edge : new Constraint(edge.kind(), currentSource,
			edge.right(), edge.inputPosition(), edge.evidence()));
		CandidateRuleFact old = fact(closed, f.argument);
		List<CandidateRuleFact> revision = replaceOwner(closed, f.argument,
			new CandidateRuleFact(new CandidateRuleKey(currentSource, old.key().orderedInputs()), old.status(),
				old.capability(), old.shapeProof(), old.profile(), old.allowedEmissionFacts(), old.failureCode()));
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			f.nodes, f.edges, f.origins, revision);
		for(List<CandidateRuleFact> result : List.of(coldClose(f.nodes, f.edges, f.origins, revision),
			session.close(revision, Set.of()).facts(),
			LogicalBoundaryRealizations.close(f.nodes, f.edges, f.origins, revision))) {
			Assert.assertEquals("identity repair preserves canonical facts", closed, result);
			var bindings = fact(result, f.reader).allowedEmissionFacts().stream()
				.flatMap(emission -> emission.realizations().stream())
				.flatMap(realization -> realization.supportClauses().stream())
				.flatMap(clause -> clause.inputBindings().stream())
				.filter(binding -> binding.source().rule().parentOccurrence().equals(currentSource)).toList();
			Assert.assertFalse(bindings.isEmpty());
			for(var binding : bindings)
				Assert.assertSame(currentSource, binding.source().rule().parentOccurrence());
		}
	}

	@Test
	public void nativeProductsContributeOneBoundaryOptionWithoutLosingExactSupport() {
		for(boolean durableLayout : new boolean[] {false, true}) {
			NativeOptionFixture coldFixture = new NativeOptionFixture(durableLayout);
			Assert.assertEquals(3, coldFixture.relation.headerCount());
			Assert.assertEquals(List.of(2, 2), coldFixture.relation.commonAxes().stream()
				.map(List::size).toList());
			Assert.assertEquals(12, coldFixture.relation.size());
			Assert.assertEquals(0, coldFixture.relation.materializedHandleCount());
			LogicalBoundaryRealizations boundary = new LogicalBoundaryRealizations(coldFixture.fixture.nodes,
				coldFixture.fixture.edges, coldFixture.fixture.origins, coldFixture.nativeFacts);
			Assert.assertTrue(boundary.sources(coldFixture.fixture.reader).stream()
				.anyMatch(source -> source == coldFixture.fixture.argument));
			Assert.assertTrue(boundary.relations().contains(new LogicalBoundaryRealizations.Relation(
				coldFixture.fixture.argument, coldFixture.fixture.reader)));
			Assert.assertTrue("boundary option projection must retain at most one lazy native handle",
				coldFixture.relation.materializedHandleCount() <= 1);

			NativeOptionFixture sessionFixture = new NativeOptionFixture(durableLayout);
			LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
				sessionFixture.fixture.nodes, sessionFixture.fixture.edges,
				sessionFixture.fixture.origins, sessionFixture.nativeFacts);
			Assert.assertTrue("session construction must not enumerate native support members",
				sessionFixture.relation.materializedHandleCount() <= 1);
			var sessionClosed = session.close(sessionFixture.nativeFacts,
				owners(sessionFixture.nativeFacts));
			Assert.assertTrue("session refresh must keep the native option representative lazy",
				sessionFixture.relation.materializedHandleCount() <= 1);

			List<CandidateRuleFact> coldNative = closeCold(coldFixture.fixture.nodes,
				coldFixture.fixture.edges, coldFixture.fixture.origins, coldFixture.nativeFacts);
			List<CandidateRuleFact> explicit = closeCold(coldFixture.fixture.nodes,
				coldFixture.fixture.edges, coldFixture.fixture.origins, coldFixture.explicitFacts);
			Assert.assertEquals("native option compression must preserve complete boundary closure",
				explicit, coldNative);
			Assert.assertEquals(coldNative, sessionClosed.facts());
			Assert.assertEquals(boundary.relations(), new LogicalBoundaryRealizations(
				coldFixture.fixture.nodes, coldFixture.fixture.edges,
				coldFixture.fixture.origins, explicit).relations());
			Assert.assertEquals(boundary.sources(coldFixture.fixture.reader),
				new LogicalBoundaryRealizations(coldFixture.fixture.nodes, coldFixture.fixture.edges,
					coldFixture.fixture.origins, explicit).sources(coldFixture.fixture.reader));
			new LogicalBoundaryRealizations(coldFixture.fixture.nodes, coldFixture.fixture.edges,
				coldFixture.fixture.origins, coldNative).validate(coldNative);
			CandidateEmissionRealization retained = fact(coldNative, coldFixture.fixture.argument)
				.allowedEmissionFacts().get(0).realizations().get(0);
			Assert.assertSame(coldFixture.relation, retained.supportClauses());

			CandidateRealizationSupportClause nonFirst = coldFixture.relation.get(7);
			CandidateSelectionReceipt selected = new CandidateSelectionReceipt(
				fact(coldFixture.nativeFacts, coldFixture.fixture.argument).key(),
				fact(coldFixture.nativeFacts, coldFixture.fixture.argument).allowedEmissionFacts().get(0),
				coldFixture.realization, nonFirst, List.of());
			Assert.assertSame(nonFirst, selected.supportClause());
			LogicalBoundaryRealizations closedBoundary = new LogicalBoundaryRealizations(
				coldFixture.fixture.nodes, coldFixture.fixture.edges,
				coldFixture.fixture.origins, coldNative);
			Map<CompiledHopKey,CandidateSelectionReceipt> selectedAtA = new IdentityHashMap<>();
			selectedAtA.put(coldFixture.fixture.argument, selected);
			selectedAtA.put(coldFixture.fixture.writer,
				receipt(coldNative, coldFixture.fixture.writer, POOL_A));
			selectedAtA.put(coldFixture.fixture.reader,
				receipt(coldNative, coldFixture.fixture.reader, POOL_A));
			Assert.assertTrue(closedBoundary.canStillBeCompatible(Map.of(), selectedAtA, Map.of()));
			Map<CompiledHopKey,CandidateSelectionReceipt> selectedAtB = new IdentityHashMap<>(selectedAtA);
			selectedAtB.put(coldFixture.fixture.writer,
				receipt(coldNative, coldFixture.fixture.writer, POOL_B));
			CandidateEmissionRealization readerB = durable(coldFixture.fixture.reader, POOL_B);
			CandidateEmissionFact readerBEmission = new CandidateEmissionFact(
				NATIVE, FType.ROW, null, List.of(readerB));
			selectedAtB.put(coldFixture.fixture.reader, new CandidateSelectionReceipt(
				fact(coldNative, coldFixture.fixture.reader).key(), readerBEmission, readerB, List.of()));
			Assert.assertFalse("the actual selected non-first native clause remains fixed at pool A",
				closedBoundary.canStillBeCompatible(Map.of(), selectedAtB, Map.of()));
			CandidateEmissionRealization matching = durable(key("selected-match"), POOL_A);
			CandidateEmissionRealization mismatch = durable(key("selected-mismatch"), POOL_B);
			Assert.assertTrue(LogicalBoundaryRealizations.compatible(coldFixture.realization,
				nonFirst, matching, matching.requireSingletonSupportClause()));
			Assert.assertFalse(LogicalBoundaryRealizations.compatible(coldFixture.realization,
				nonFirst, mismatch, mismatch.requireSingletonSupportClause()));
		}
	}

	@Test
	public void dynamicAndUnprovedNativeProductsKeepTheirBoundarySemantics() {
		NativeOptionFixture dynamic = new NativeOptionFixture(false, false, true);
		Assert.assertEquals(0, dynamic.relation.materializedHandleCount());
		new LogicalBoundaryRealizations(dynamic.fixture.nodes, dynamic.fixture.edges,
			dynamic.fixture.origins, dynamic.nativeFacts);
		Assert.assertTrue(dynamic.relation.materializedHandleCount() <= 1);
		List<CandidateRuleFact> dynamicClosed = closeCold(dynamic.fixture.nodes,
			dynamic.fixture.edges, dynamic.fixture.origins, dynamic.nativeFacts);
		Assert.assertEquals(closeCold(dynamic.fixture.nodes, dynamic.fixture.edges,
			dynamic.fixture.origins, dynamic.explicitFacts), dynamicClosed);
		new LogicalBoundaryRealizations(dynamic.fixture.nodes, dynamic.fixture.edges,
			dynamic.fixture.origins, dynamicClosed).validate(dynamicClosed);
		CandidateEmissionRealization dynamicReader = realizationAtNativePool(
			fact(dynamicClosed, dynamic.fixture.reader), POOL_A);
		Assert.assertFalse(dynamicReader.nativeWorkerPoolLayoutExactForOwnedClause(
			dynamicReader.requireSingletonSupportClause()));

		NativeOptionFixture staging = new NativeOptionFixture(false, true, false);
		Assert.assertEquals(0, staging.relation.materializedHandleCount());
		LogicalBoundaryRealizations stagingBoundary = new LogicalBoundaryRealizations(
			staging.fixture.nodes, staging.fixture.edges, staging.fixture.origins, staging.nativeFacts);
		Assert.assertTrue(staging.relation.materializedHandleCount() <= 1);
		List<CandidateRuleFact> stagingClosed = closeCold(staging.fixture.nodes,
			staging.fixture.edges, staging.fixture.origins, staging.nativeFacts);
		Assert.assertEquals(closeCold(staging.fixture.nodes, staging.fixture.edges,
			staging.fixture.origins, staging.explicitFacts), stagingClosed);
		Assert.assertTrue(fact(stagingClosed, staging.fixture.reader).allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream()).allMatch(realization ->
				realization.supportClauses().stream().allMatch(clause ->
					realization.nativeWorkerPoolResidencyForOwnedClause(clause) == null)));
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(staging.fixture.argument, new CandidateSelectionReceipt(
			fact(staging.nativeFacts, staging.fixture.argument).key(),
			fact(staging.nativeFacts, staging.fixture.argument).allowedEmissionFacts().get(0),
			staging.realization, staging.relation.get(5), List.of()));
		selected.put(staging.fixture.writer,
			receipt(staging.nativeFacts, staging.fixture.writer, POOL_A));
		selected.put(staging.fixture.reader,
			receipt(staging.nativeFacts, staging.fixture.reader, POOL_A));
		Assert.assertFalse("an unproved native product must not authorize a fixed boundary pool",
			stagingBoundary.canStillBeCompatible(Map.of(), selected, Map.of()));
	}

	@Test
	public void restrictedMultiHeaderProjectionRetainsTheOriginalFirstDonor() {
		NativeOptionFixture fixture = new NativeOptionFixture(false);
		CandidateRealizationSupportClause originalFirst = fixture.relation.get(0);
		CandidateRealizationInputBinding removed = fixture.axes.get(0).get(1);
		NativeContinuitySupportClauses restricted = fixture.relation
			.restrictBindings(binding -> binding != removed).orElseThrow();
		Assert.assertEquals(3, restricted.headerCount());
		Assert.assertEquals(6, restricted.size());
		Assert.assertEquals(0, restricted.materializedHandleCount());
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			fixture.realization.key(), restricted);
		List<CandidateRuleFact> facts = replaceOwner(fixture.nativeFacts, fixture.fixture.argument,
			withRealization(fact(fixture.nativeFacts, fixture.fixture.argument), realization));
		new LogicalBoundaryRealizations(fixture.fixture.nodes, fixture.fixture.edges,
			fixture.fixture.origins, facts);
		Assert.assertTrue(restricted.materializedHandleCount() <= 1);
		Assert.assertSame("projection must retain the first donor's exact clause identity",
			originalFirst, restricted.get(0));
	}

	@Test
	public void nativeSessionMatchesFreshClosureAcrossWithdrawalRestorationAndPoolChange() {
		NativeOptionFixture fixture = new NativeOptionFixture(false);
		LogicalBoundaryRealizations.Session session = new LogicalBoundaryRealizations.Session(
			fixture.fixture.nodes, fixture.fixture.edges, fixture.fixture.origins, fixture.nativeFacts);
		var initial = session.close(fixture.nativeFacts, owners(fixture.nativeFacts));
		Assert.assertEquals(closeCold(fixture.fixture.nodes, fixture.fixture.edges,
			fixture.fixture.origins, fixture.nativeFacts), initial.facts());

		List<CandidateRuleFact> withdrawnInput = replaceOwner(initial.facts(), fixture.fixture.argument,
			unavailable(fact(initial.facts(), fixture.fixture.argument)));
		var withdrawn = session.close(withdrawnInput, Set.of(fixture.fixture.argument));
		Assert.assertEquals(closeCold(fixture.fixture.nodes, fixture.fixture.edges,
			fixture.fixture.origins, withdrawnInput), withdrawn.facts());

		List<CandidateRuleFact> restoredInput = replaceOwner(withdrawn.facts(), fixture.fixture.argument,
			fact(fixture.nativeFacts, fixture.fixture.argument));
		var restored = session.close(restoredInput, Set.of(fixture.fixture.argument));
		Assert.assertEquals(closeCold(fixture.fixture.nodes, fixture.fixture.edges,
			fixture.fixture.origins, restoredInput), restored.facts());

		NativeContinuitySupportClauses poolBRelation = nativeRelation(fixture.fixture.argument,
			fixture.seeds, POOL_B, fixture.axes, POOL_B, true);
		CandidateEmissionRealization poolBRealization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(NATIVE, "boundary-native-product-b"), poolBRelation);
		List<CandidateRuleFact> changedInput = replaceOwner(restored.facts(), fixture.fixture.argument,
			withRealization(fact(restored.facts(), fixture.fixture.argument), poolBRealization));
		var changed = session.close(changedInput, Set.of(fixture.fixture.argument));
		Assert.assertTrue("pool-change refresh must retain only its first native representative",
			poolBRelation.materializedHandleCount() <= 1);
		Assert.assertEquals(closeCold(fixture.fixture.nodes, fixture.fixture.edges,
			fixture.fixture.origins, changedInput), changed.facts());
		Assert.assertNotNull(receipt(changed.facts(), fixture.fixture.reader, POOL_B));
	}

	@Test
	public void valueMapBoundaryOptionsRemainClauseSpecific() {
		Fixture fixture = new Fixture();
		CandidateRuleFact argument = fact(fixture.facts, fixture.argument);
		CandidateRuleFact writer = fact(fixture.facts, fixture.writer);
		CandidateEmissionRealization argumentA = realizationAtPool(argument, POOL_A);
		CandidateEmissionRealization argumentB = realizationAtPool(argument, POOL_B);
		CandidateEmissionRealization writerA = realizationAtPool(writer, POOL_A);
		CandidateRealizationReference argumentARef = CandidateRealizationReference.of(argument.key(), argumentA);
		CandidateRealizationReference argumentBRef = CandidateRealizationReference.of(argument.key(), argumentB);
		CandidateRealizationReference writerARef = CandidateRealizationReference.of(writer.key(), writerA);
		CandidateRealizationSupportClause first = valueMapClause(
			fixture.reader, "a-unsupported", argumentBRef, writerARef);
		CandidateRealizationSupportClause second = valueMapClause(
			fixture.reader, "z-supported--", argumentARef, writerARef);
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(NATIVE,
			"clause-specific-boundary", List.of(first, second));
		CandidateRuleFact reader = fact(fixture.facts, fixture.reader);
		CandidateEmissionFact emission = new CandidateEmissionFact(NATIVE, FType.ROW,
			null, List.of(valueMap));
		CandidateRuleFact revisedReader = new CandidateRuleFact(reader.key(), reader.status(),
			reader.capability(), reader.shapeProof(), reader.profile(), List.of(emission), reader.failureCode());
		List<CandidateRuleFact> facts = replaceOwner(fixture.facts, fixture.reader, revisedReader);
		LogicalBoundaryRealizations boundary = new LogicalBoundaryRealizations(
			fixture.nodes, fixture.edges, fixture.origins, facts);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(fixture.argument, new CandidateSelectionReceipt(argument.key(),
			argument.allowedEmissionFacts().get(0), argumentA, List.of()));
		selected.put(fixture.writer, new CandidateSelectionReceipt(writer.key(),
			writer.allowedEmissionFacts().get(0), writerA, List.of()));
		Assert.assertTrue("the supported second VALUE_MAP clause must remain visible",
			boundary.canStillBeCompatible(Map.of(), selected, Map.of()));
		selected.put(fixture.reader, new CandidateSelectionReceipt(reader.key(), emission,
			valueMap, first, List.of()));
		Assert.assertFalse("an explicitly selected first clause cannot borrow the second clause's source",
			boundary.canStillBeCompatible(Map.of(), selected, Map.of()));
	}

	/** Independent full-rebuild oracle retained when production uses incremental closure. */
	private static List<CandidateRuleFact> coldClose(List<Node> nodes, java.util.Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts) {
		List<CandidateRuleFact> current = facts;
		for(int pass = 0; pass <= nodes.size(); pass++) {
			List<CandidateRuleFact> next = new LogicalBoundaryRealizations(nodes, constraints, origins, current)
				.bind(current);
			if(next.equals(current))
				return next;
			current = next;
		}
		throw new AssertionError("Cold logical boundary closure did not converge");
	}

	private static Set<CompiledHopKey> owners(List<CandidateRuleFact> facts) {
		Set<CompiledHopKey> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRuleFact fact : facts)
			result.add(fact.key().parentOccurrence());
		return result;
	}

	private static List<CandidateRuleFact> closeCold(List<Node> nodes,
		List<Constraint> constraints, Map<CompiledHopKey,Hop> origins,
		List<CandidateRuleFact> facts) {
		List<CandidateRuleFact> current = facts;
		for(int pass = 0; pass <= nodes.size(); pass++) {
			List<CandidateRuleFact> next =
				new LogicalBoundaryRealizations(nodes, constraints, origins, current).bind(current);
			if(next.equals(current))
				return next;
			current = next;
		}
		throw new IllegalStateException("Cold logical boundary oracle did not converge");
	}

	private static boolean hasValueMap(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.anyMatch(realization -> realization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.VALUE_MAP);
	}

	private static CandidateRuleFact fact(List<CandidateRuleFact> facts, CompiledHopKey owner) {
		return facts.stream().filter(candidate -> candidate.key().parentOccurrence() == owner)
			.findFirst().orElseThrow();
	}

	private static List<CandidateRuleFact> replaceOwner(List<CandidateRuleFact> facts,
		CompiledHopKey owner, CandidateRuleFact replacement) {
		List<CandidateRuleFact> result = new ArrayList<>(facts);
		for(int slot = 0; slot < result.size(); slot++)
			if(result.get(slot).key().parentOccurrence() == owner)
				result.set(slot, replacement);
		return List.copyOf(result);
	}

	private static CandidateRuleFact unavailable(CandidateRuleFact fact) {
		return new CandidateRuleFact(fact.key(), CandidateEvaluationStatus.PRIVACY_EXCLUDED,
			fact.capability(), fact.shapeProof(), fact.profile(), List.of(), "PRIVATE_AGGREGATE");
	}

	private static CandidateEmissionRealization realizationAtPool(CandidateRuleFact fact,
		DurableAnchorKey pool) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.filter(realization -> PlacementIdentity.samePhysicalWorkerPool(
				realization.provenWorkerPool(realization.requireSingletonSupportClause()), pool))
			.findFirst().orElseThrow();
	}

	private static CandidateEmissionRealization realizationAtNativePool(CandidateRuleFact fact,
		DurableAnchorKey pool) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind()
				!= PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
			.filter(realization -> PlacementIdentity.samePhysicalWorkerPool(
				realization.nativeWorkerPoolResidencyForOwnedClause(
					realization.requireSingletonSupportClause()), pool))
			.findFirst().orElseThrow();
	}

	private static CandidateRealizationSupportClause valueMapClause(CompiledHopKey owner,
		String evidence, CandidateRealizationReference... sources) {
		List<CandidateRealizationInputBinding> bindings = java.util.Arrays.stream(sources)
			.map(source -> CandidateRealizationInputBinding.logicalTransient(0, source)).toList();
		return new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
			PlacementProofKind.CONTROL_FLOW, owner, evidence)), bindings);
	}

	private static CandidateRuleFact withRealization(CandidateRuleFact template,
		CandidateEmissionRealization realization) {
		CandidateEmissionFact emission = new CandidateEmissionFact(NATIVE, FType.ROW,
			null, List.of(realization));
		return new CandidateRuleFact(template.key(), template.status(), template.capability(),
			template.shapeProof(), template.profile(), List.of(emission), template.failureCode());
	}

	private static CandidateRealizationInputBinding nativeDirect(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(position,
			new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(NATIVE, "boundary-source-" + name)));
	}

	private static NativeContinuitySupportClauses nativeRelation(CompiledHopKey owner,
		List<DurableAnchorKey> seeds, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes, boolean durableLayout) {
		return nativeRelation(owner, seeds, output, axes,
			durableLayout ? null : output, true);
	}

	private static NativeContinuitySupportClauses nativeRelation(CompiledHopKey owner,
		List<DurableAnchorKey> seeds, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes, DurableAnchorKey clauseWitness,
		boolean exactLayout) {
		NativeContinuitySupportClauses result = null;
		for(DurableAnchorKey seed : seeds) {
			var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, exactLayout, axes);
			Assert.assertNotNull(product);
			NativeContinuitySupportClauses next = new NativeContinuitySupportClauses(
				owner, product, clauseWitness, exactLayout);
			result = result == null ? next : result.multiHeaderUnion(next).orElseThrow();
		}
		return result;
	}

	private static List<CandidateRealizationSupportClause> explicitNativeClauses(
		CompiledHopKey owner, List<DurableAnchorKey> seeds, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes, boolean durableLayout) {
		return explicitNativeClauses(owner, seeds, output, axes,
			durableLayout ? null : output, true);
	}

	private static List<CandidateRealizationSupportClause> explicitNativeClauses(
		CompiledHopKey owner, List<DurableAnchorKey> seeds, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes, DurableAnchorKey clauseWitness,
		boolean exactLayout) {
		List<CandidateRealizationSupportClause> result = new ArrayList<>();
		for(DurableAnchorKey seed : seeds)
			enumerateNativeClauses(owner, seed, output, axes, clauseWitness, exactLayout,
				0, new ArrayList<>(), result);
		return result;
	}

	private static void enumerateNativeClauses(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes,
		DurableAnchorKey clauseWitness, boolean exactLayout, int axis,
		List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> result) {
		if(axis < axes.size()) {
			for(CandidateRealizationInputBinding option : axes.get(axis)) {
				selected.add(option);
				enumerateNativeClauses(owner, seed, output, axes, clauseWitness, exactLayout,
					axis + 1, selected, result);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.copyOf(selected), "logical boundary native reference");
		PlacementProofKey proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, exactLayout, bindings).continuityProofKey(owner);
		result.add(new CandidateRealizationSupportClause(List.of(proof), bindings,
			clauseWitness, exactLayout));
	}

	private static final class NativeOptionFixture {
		private final Fixture fixture = new Fixture();
		private final NativeContinuitySupportClauses relation;
		private final CandidateEmissionRealization realization;
		private final List<CandidateRuleFact> nativeFacts;
		private final List<CandidateRuleFact> explicitFacts;
		private final List<List<CandidateRealizationInputBinding>> axes;
		private final List<DurableAnchorKey> seeds;

		private NativeOptionFixture(boolean durableLayout) {
			this(durableLayout, true, true);
		}

		private NativeOptionFixture(boolean durableLayout, boolean exactLayout, boolean proved) {
			CompiledHopKey left = key("native-option-left");
			CompiledHopKey right = key("native-option-right");
			axes = List.of(
				List.of(nativeDirect(0, "left-a", left), nativeDirect(0, "left-b", left)).stream()
					.sorted(PlacementAnalysis.canonicalComparator()).toList(),
				List.of(nativeDirect(1, "right-a", right), nativeDirect(1, "right-b", right)).stream()
					.sorted(PlacementAnalysis.canonicalComparator()).toList());
			seeds = List.of(
				pool("native-seed-a", 12001), pool("native-seed-b", 13001),
				pool("native-seed-c", 14001));
			DurableAnchorKey clauseWitness = durableLayout || !proved ? null : POOL_A;
			relation = nativeRelation(fixture.argument, seeds, POOL_A, axes,
				clauseWitness, exactLayout);
			PlacementRealizationKey key = durableLayout
				? PlacementRealizationKey.durable(NATIVE, POOL_A)
				: PlacementRealizationKey.nativeLineage(NATIVE, "boundary-native-product");
			realization = new CandidateEmissionRealization(key, relation);
			CandidateEmissionRealization explicit = new CandidateEmissionRealization(key,
				explicitNativeClauses(fixture.argument, seeds, POOL_A, axes,
					clauseWitness, exactLayout));
			List<CandidateRuleFact> targetPrepared = replaceOwner(fixture.facts, fixture.reader,
				withRealization(fact(fixture.facts, fixture.reader),
					durable(fixture.reader, POOL_A)));
			nativeFacts = replaceOwner(targetPrepared, fixture.argument,
				withRealization(fact(fixture.facts, fixture.argument), realization));
			explicitFacts = replaceOwner(targetPrepared, fixture.argument,
				withRealization(fact(fixture.facts, fixture.argument), explicit));
		}
	}

	private static CandidateSelectionReceipt receipt(List<CandidateRuleFact> facts,
		CompiledHopKey key, DurableAnchorKey pool) {
		CandidateRuleFact fact = facts.stream().filter(candidate -> candidate.key().parentOccurrence() == key).findFirst().orElseThrow();
		CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization value = emission.realizations().stream()
			.filter(candidate -> candidate.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
			.filter(candidate -> PlacementIdentity.samePhysicalWorkerPool(
				candidate.provenWorkerPool(candidate.requireSingletonSupportClause()), pool))
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
			edges.add(new Constraint(ConstraintKind.FUNCTION_INPUT_TRANSFER, argument, boundary, 0,
				"function-argument:X"));
			edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, boundary, reader, 0, "function-formal-input"));
			edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, writer, reader, 0, "cfg-transient-value:BRANCH_JOIN_PHI"));
		}
	}

	private static final class ChainedFixture extends Fixture {
		final CompiledHopKey downstreamBoundary = key("downstream-boundary");
		final CompiledHopKey downstreamReader = key("downstream-reader");
		ChainedFixture() {
			origins.put(downstreamBoundary, new DataOp(downstreamBoundary.emittedHopInstance(), DataType.MATRIX,
				ValueType.FP64, OpOpData.PERSISTENTREAD, "X", 8, 2, 16, 1000));
			origins.put(downstreamReader, new DataOp(downstreamReader.emittedHopInstance(), DataType.MATRIX,
				ValueType.FP64, OpOpData.TRANSIENTREAD, "X", 8, 2, 16, 1000));
			nodes.add(new Node(downstreamBoundary, NodeKind.FUNCTION_INPUT,
				new ValueVersionKey("boundary-test", downstreamBoundary.emittedHopInstance(),
					downstreamBoundary.controlRegion(), nodes.size(), VersionKind.ORDINARY, List.of()),
				false, List.of(), List.of(), List.of()));
			nodes.add(new Node(downstreamReader, NodeKind.TRANSIENT_READ,
				new ValueVersionKey("boundary-test", downstreamReader.emittedHopInstance(),
					downstreamReader.controlRegion(), nodes.size(), VersionKind.ORDINARY, List.of()),
				true, List.of(ROW), List.of(), List.of()));
			CandidateRuleFact source = facts.get(0);
			facts.add(new CandidateRuleFact(new CandidateRuleKey(downstreamReader, List.of()),
				CandidateEvaluationStatus.AVAILABLE, source.capability(), source.shapeProof(), source.profile(),
				List.of(new CandidateEmissionFact(NATIVE, FType.ROW)), ""));
			edges.add(new Constraint(ConstraintKind.FUNCTION_INPUT_TRANSFER, reader, downstreamBoundary, 0,
				"function-argument:X"));
			edges.add(new Constraint(ConstraintKind.SAME_PLACEMENT, downstreamBoundary, downstreamReader, 0,
				"function-formal-input"));
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
	private static CandidateEmissionRealization dynamic(CompiledHopKey key, DurableAnchorKey pool) {
		return CandidateEmissionRealization.nativeLineageDynamicLayout(NATIVE,
			"dynamic:" + key.normalizedSignature(), pool,
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, key,
				pool.normalizedSignature())), List.of());
	}
}

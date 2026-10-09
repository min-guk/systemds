/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact regression contract for relocation filtering over compact support products. */
public class RelocationFactorizedFilterTest {
	private static final String FINGERPRINT = "relocation-factorized-filter";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(LOCAL_STATE, false);
	private static final PlacementState NATIVE_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState NATIVE = new PlacementEmissionState(NATIVE_STATE, false);

	@Test
	public void hundredByHundredDirectProductStaysLazyAndMatchesSmallExplicitOracle() throws Exception {
		CandidateEmissionRealization large = factorizedLocal(List.of(
			directAxis(0, "large-left", 100), directAxis(1, "large-right", 100)));
		FactorizedSupportClauses largeClauses = clauses(large);
		CandidateEmissionRealization rebound = onlyRealization(bind(fixture(large, List.of())));
		Assert.assertSame("an all-direct product is already exact", large, rebound);
		Assert.assertSame(largeClauses, rebound.supportClauses());
		Assert.assertEquals("filtering must inspect retained axes, not 10,000 members",
			0, largeClauses.materializedClauseCount());

		List<List<CandidateRealizationInputBinding>> axes = List.of(
			directAxis(0, "oracle-left", 2), directAxis(1, "oracle-right", 3));
		CandidateEmissionRealization compact = factorizedLocal(axes);
		List<CandidateRealizationSupportClause> explicitClauses = new ArrayList<>();
		for(CandidateRealizationInputBinding left : axes.get(0))
			for(CandidateRealizationInputBinding right : axes.get(1))
				explicitClauses.add(new CandidateRealizationSupportClause(List.of(), List.of(left, right)));
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL), explicitClauses);
		CandidateEmissionRealization compactResult = onlyRealization(bind(fixture(compact, List.of())));
		CandidateEmissionRealization explicitResult = onlyRealization(bind(fixture(explicit, List.of())));
		Assert.assertEquals(explicitResult.normalizedSignature(), compactResult.normalizedSignature());
		Assert.assertEquals(explicitResult.supportClauses(), compactResult.supportClauses());
	}

	@Test
	public void relocationOnlyAxisDropsWholeProductWithoutMaterializingMembers() throws Exception {
		RelocationActionKey action = relocationAction("drop");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(NATIVE, "drop-product"), List.of(), List.of(
				directAxis(0, "drop-direct", 100),
				relocationAxis(1, "drop-relocation", 100, action)), null, true);
		FactorizedSupportClauses productClauses = clauses(product);
		CandidateEmissionRealization baseline = CandidateEmissionRealization.durable(
			NATIVE, anchor("drop-baseline"), List.of(), List.of());
		BinderFixture input = fixture(new CandidateEmissionFact(
			NATIVE, FType.ROW, null, List.of(baseline, product)), List.of());
		int before = productClauses.materializedClauseCount();
		Assert.assertEquals("canonical emission construction reads one representative", 1, before);
		List<CandidateEmissionRealization> rebound = realizations(bind(input));
		Assert.assertEquals(List.of(baseline), rebound);
		Assert.assertSame("the ordinary baseline retains its original clause authority",
			baseline.supportClauses().get(0), rebound.get(0).supportClauses().get(0));
		Assert.assertEquals("a relocation-only axis proves that no Cartesian member survives",
			before, productClauses.materializedClauseCount());
	}

	@Test
	public void mixedAxisFallsBackToExactStableClauseFiltering() throws Exception {
		RelocationActionKey action = relocationAction("mixed");
		CandidateRealizationInputBinding direct = directBinding(0, "mixed-direct");
		CandidateRealizationInputBinding relocation = relocationBinding(0, "mixed-relocation", action);
		List<CandidateRealizationInputBinding> right = directAxis(1, "mixed-right", 2);
		CandidateEmissionRealization product = factorizedLocal(List.of(List.of(direct, relocation), right));
		List<CandidateRealizationSupportClause> original = new ArrayList<>(product.supportClauses());
		List<CandidateRealizationSupportClause> expected = original.stream()
			.filter(clause -> clause.inputBindings().stream().noneMatch(binding ->
				binding.kind() == CandidateInputBindingKind.RELOCATION)).toList();

		CandidateEmissionRealization rebound = onlyRealization(bind(fixture(product, List.of())));
		Assert.assertEquals(expected, rebound.supportClauses());
		Assert.assertEquals(expected.size(), rebound.supportClauses().size());
		for(int index = 0; index < expected.size(); index++)
			Assert.assertSame("fallback retains the first canonical donor clause identity",
				expected.get(index), rebound.supportClauses().get(index));
	}

	@Test
	public void repeatedOwnerDirectCorrelationRetainsOriginalRelationWithoutExpansion() throws Exception {
		CompiledHopKey sourceOwner = key("shared-source-owner");
		CandidateRealizationReference first = sourceReference(sourceOwner, "shared-a");
		CandidateRealizationReference second = sourceReference(sourceOwner, "shared-b");
		CandidateEmissionRealization product = factorizedLocal(List.of(
			List.of(CandidateRealizationInputBinding.direct(0, first),
				CandidateRealizationInputBinding.direct(0, second)),
			List.of(CandidateRealizationInputBinding.direct(1, first),
				CandidateRealizationInputBinding.direct(1, second))));
		FactorizedSupportClauses relation = clauses(product);
		Assert.assertEquals("same-owner axes represent two correlated choices, not four tuples", 2, relation.size());
		CandidateEmissionRealization rebound = onlyRealization(bind(fixture(product, List.of())));
		Assert.assertSame(product, rebound);
		Assert.assertSame(relation, rebound.supportClauses());
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@Test
	public void emptyBindingViabilityStillDistinguishesNativeLineageFromLocal() throws Exception {
		CandidateEmissionRealization local = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL), List.of(), List.of(), null, true);
		CandidateEmissionRealization nativeWithoutWitness = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(NATIVE, "unwitnessed"), List.of(), List.of(), null, true);
		CandidateEmissionRealization nativeWithWitness = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(NATIVE, "witnessed"),
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "witnessed")),
			List.of(), anchor("witnessed"), true);
		CandidateEmissionRealization baseline = CandidateEmissionRealization.durable(
			NATIVE, anchor("baseline"), List.of(), List.of());

		List<CandidateInputState> present = List.of(CandidateInputState.present(FType.ROW));
		Assert.assertSame(local, onlyRealization(bind(fixture(local, present))));
		BinderFixture unwitnessedInput = fixture(new CandidateEmissionFact(NATIVE, FType.ROW, null,
			List.of(baseline, nativeWithoutWitness)), present);
		int unwitnessedBefore = clauses(nativeWithoutWitness).materializedClauseCount();
		List<CandidateEmissionRealization> rejected = realizations(bind(unwitnessedInput));
		Assert.assertEquals("present input cannot authorize empty unwitnessed native lineage",
			List.of(baseline), rejected);
		BinderFixture witnessedInput = fixture(new CandidateEmissionFact(NATIVE, FType.ROW, null,
			List.of(baseline, nativeWithWitness)), present);
		int witnessedBefore = clauses(nativeWithWitness).materializedClauseCount();
		List<CandidateEmissionRealization> witnessed = realizations(bind(witnessedInput));
		Assert.assertTrue(witnessed.stream().anyMatch(realization -> realization == nativeWithWitness));
		Assert.assertEquals(1, unwitnessedBefore);
		Assert.assertEquals(unwitnessedBefore, clauses(nativeWithoutWitness).materializedClauseCount());
		Assert.assertEquals(1, witnessedBefore);
		Assert.assertEquals(witnessedBefore, clauses(nativeWithWitness).materializedClauseCount());
	}


	@Test
	public void wholeRelocationAxisAfterMixedAxisStillAvoidsMemberFiltering() throws Exception {
		RelocationActionKey action = relocationAction("mixed-before-drop");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(NATIVE, "mixed-before-drop"), List.of(), List.of(
				List.of(directBinding(0, "mixed-drop-direct"),
					relocationBinding(0, "mixed-drop-relocation", action)),
				relocationAxis(1, "later-relocation", 100, action)), null, true);
		CandidateEmissionRealization baseline = CandidateEmissionRealization.durable(
			NATIVE, anchor("mixed-drop-baseline"), List.of(), List.of());
		BinderFixture input = fixture(new CandidateEmissionFact(
			NATIVE, FType.ROW, null, List.of(baseline, product)), List.of());
		int before = clauses(product).materializedClauseCount();
		Assert.assertEquals(1, before);
		Assert.assertEquals(List.of(baseline), realizations(bind(input)));
		Assert.assertEquals("a later all-relocation axis supersedes an earlier mixed axis",
			before, clauses(product).materializedClauseCount());
	}

	@Test
	public void measuredBinderPartitionsOutcomesWithoutChangingResultsAndResets() throws Exception {
		RelocationActionKey action = relocationAction("metrics");
		BinderFixture keep = fixture(factorizedLocal(List.of(
			directAxis(0, "metric-keep-left", 2), directAxis(1, "metric-keep-right", 3))), List.of());
		CandidateEmissionRealization dropped = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(NATIVE, "metric-drop"), List.of(), List.of(
				directAxis(0, "metric-drop-left", 2), relocationAxis(1, "metric-drop-right", 2, action)),
			null, true);
		CandidateEmissionRealization baseline = CandidateEmissionRealization.durable(
			NATIVE, anchor("metric-baseline"), List.of(), List.of());
		BinderFixture drop = fixture(new CandidateEmissionFact(
			NATIVE, FType.ROW, null, List.of(baseline, dropped)), List.of());
		BinderFixture fallback = fixture(factorizedLocal(List.of(
			List.of(directBinding(0, "metric-mixed-direct"),
				relocationBinding(0, "metric-mixed-relocation", action)),
			directAxis(1, "metric-mixed-right", 2))), List.of());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		for(BinderFixture input : List.of(keep, drop, fallback)) {
			CandidateRuleFact unmeasured = bind(input);
			CandidateRuleFact measured = bind(input, metrics);
			Assert.assertEquals(unmeasured, measured);
		}
		Assert.assertEquals(3, directWork(metrics, "RELOCATION_FACTOR_FILTER_REQUESTS"));
		Assert.assertEquals(1, directWork(metrics, "RELOCATION_FACTOR_FILTER_KEEP_ALL"));
		Assert.assertEquals(1, directWork(metrics, "RELOCATION_FACTOR_FILTER_DROP_ALL"));
		Assert.assertEquals(1, directWork(metrics, "RELOCATION_FACTOR_FILTER_FALLBACK"));
		Assert.assertEquals("only the six kept and four dropped logical clauses bypass filtering",
			10, directWork(metrics, "RELOCATION_FACTOR_FILTER_LOGICAL_CLAUSES_BYPASSED"));
		metrics.reset();
		for(String suffix : List.of("REQUESTS", "KEEP_ALL", "DROP_ALL", "FALLBACK", "LOGICAL_CLAUSES_BYPASSED"))
			Assert.assertEquals(suffix, 0, directWork(metrics, "RELOCATION_FACTOR_FILTER_" + suffix));
	}

	private static long directWork(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(SearchSpaceMetrics.DirectWork.valueOf(name));
	}

	private static CandidateEmissionRealization factorizedLocal(
		List<List<CandidateRealizationInputBinding>> axes) {
		return CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL), List.of(), axes, null, true);
	}

	private static FactorizedSupportClauses clauses(CandidateEmissionRealization realization) {
		return (FactorizedSupportClauses) realization.supportClauses();
	}

	private static List<CandidateRealizationInputBinding> directAxis(int position,
		String prefix, int count) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			bindings.add(directBinding(position, prefix + '-' + index));
		return List.copyOf(bindings);
	}

	private static List<CandidateRealizationInputBinding> relocationAxis(int position,
		String prefix, int count, RelocationActionKey action) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			bindings.add(relocationBinding(position, prefix + '-' + index, action));
		return List.copyOf(bindings);
	}

	private static CandidateRealizationInputBinding directBinding(int position, String id) {
		return CandidateRealizationInputBinding.direct(position, sourceReference(key(id), id));
	}

	private static CandidateRealizationInputBinding relocationBinding(int position,
		String id, RelocationActionKey action) {
		return CandidateRealizationInputBinding.relocation(
			position, sourceReference(key(id), id), action);
	}

	private static CandidateRealizationReference sourceReference(CompiledHopKey owner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
			NATIVE, anchor("source:" + id), List.of(), List.of());
		return CandidateRealizationReference.of(rule, realization);
	}

	private static RelocationActionKey relocationAction(String id) {
		return new RelocationActionKey(version("source-" + id, 100), NATIVE_STATE,
			FType.ROW, anchor(id), REGION.normalizedSignature(), List.of(OWNER));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:19001", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static BinderFixture fixture(CandidateEmissionRealization realization,
		List<CandidateInputState> inputs) {
		return fixture(new CandidateEmissionFact(realization.key().emissionState(), FType.ROW,
			null, List.of(realization)), inputs);
	}

	private static BinderFixture fixture(CandidateEmissionFact emission,
		List<CandidateInputState> inputs) {
		CandidateRuleKey rule = new CandidateRuleKey(OWNER, inputs);
		CandidateRuleFact fact = new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				emission.emissionState().placementState().output(), FType.ROW,
				ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
		Node node = new Node(OWNER, NodeKind.OPERATION, version("owner", 0), true,
			List.of(emission.emissionState().placementState()), List.of(), List.of());
		DataOp hop = new DataOp("owner", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "owner", 4, 2, 8, 1000);
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(OWNER, hop);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 4, 2));
		return new BinderFixture(List.of(fact), List.of(node), List.of(), List.of(), origins, shapes);
	}

	private static CandidateRuleFact bind(BinderFixture fixture) throws Exception {
		return bind(fixture, null);
	}

	private static CandidateRuleFact bind(BinderFixture fixture, SearchSpaceMetrics metrics) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindRelocationCandidateRealizationsMeasured", List.class, List.class,
			List.class, List.class, Map.class, Map.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> result = (List<CandidateRuleFact>) method.invoke(
			PlacementBuilderTestAccess.relationClosure(new NeutralPlacementGraphBuilder(null, metrics)),
			fixture.facts(), fixture.nodes(), fixture.edges(), fixture.actions(),
			fixture.origins(), fixture.shapes());
		return result.get(0);
	}

	private static List<CandidateEmissionRealization> realizations(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().get(0).realizations();
	}

	private static CandidateEmissionRealization onlyRealization(CandidateRuleFact fact) {
		Assert.assertEquals(1, realizations(fact).size());
		return realizations(fact).get(0);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey(FINGERPRINT, id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private record BinderFixture(List<CandidateRuleFact> facts, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, List<RelocationAction> actions,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) { }
}

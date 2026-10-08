/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RelocationFactorizedProductTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"relocation-factor", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL_STATE, false);
	private static final PlacementState FOUT_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState FOUT_EMISSION =
		new PlacementEmissionState(FOUT_STATE, false);

	@Test
	public void sourcePoolInventoryReadsUniformMetadataWithoutExpandingSupport() throws Exception {
		RelocationActionKey action = action("inventory");
		CandidateEmissionRealization realization = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(), List.of(
				bindings(0, "inventory-left", 100, action),
				bindings(1, "inventory-right", 100, action)), null, true);
		CandidateRealizationReference reference = CandidateRealizationReference.of(
			new CandidateRuleKey(OWNER, List.of()), realization);
		Method inventory = PlacementRelationClosure.class.getDeclaredMethod(
			"relocationSourceOptions", CandidateRealizationReference.class,
			CandidateEmissionRealization.class, ValueVersionKey.class);
		inventory.setAccessible(true);
		List<?> options = (List<?>) inventory.invoke(null, reference, realization, null);
		Assert.assertEquals(1, options.size());
		Assert.assertEquals(0,
			((FactorizedSupportClauses) realization.supportClauses()).materializedClauseCount());
	}

	@Test
	public void sourcePoolInventoryPreservesDynamicWitnessAndExactReference() throws Exception {
		RelocationActionKey action = action("inventory-metadata");
		var proof = new PlacementIdentity.PlacementProofKey(
			PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY, OWNER, "inventory-proof");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(FOUT_EMISSION, "inventory"), List.of(proof), List.of(
				bindings(0, "metadata-left", 2, action),
				bindings(1, "metadata-right", 3, action)), action.durableAnchor(), false);
		CandidateRealizationReference reference = CandidateRealizationReference.of(
			new CandidateRuleKey(OWNER, List.of()), product);
		Method inventory = PlacementRelationClosure.class.getDeclaredMethod(
			"relocationSourceOptions", CandidateRealizationReference.class,
			CandidateEmissionRealization.class, ValueVersionKey.class);
		inventory.setAccessible(true);
		List<?> actual = (List<?>) inventory.invoke(null, reference, product, null);
		Assert.assertEquals(0,
			((FactorizedSupportClauses)product.supportClauses()).materializedClauseCount());
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(product.key(),
			new ArrayList<>(product.supportClauses()));
		Assert.assertEquals(inventory.invoke(null, reference, explicit, null), actual);
	}

	@Test
	public void retainDerivedProofFiltersWholeProductWithoutReadingClauses() throws Exception {
		RelocationActionKey action = action("retain-proof");
		var proof = new PlacementIdentity.PlacementProofKey(
			PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY, OWNER, "retained");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(proof), List.of(
				bindings(0, "proof-left", 100, action),
				bindings(1, "proof-right", 100, action)), null, true);
		CandidateEmissionRealization template = CandidateEmissionRealization.local(LOCAL_EMISSION);
		Method retain = PlacementRelationClosure.class.getDeclaredMethod("retainDerivedOutputSupport",
			CandidateEmissionRealization.class, List.class, PlacementIdentity.PlacementProofKey.class);
		retain.setAccessible(true);
		List<?> result = (List<?>)retain.invoke(null, template, List.of(product), proof);
		Assert.assertEquals(2, result.size());
		Assert.assertSame(product.supportClauses(),
			((CandidateEmissionRealization)result.get(1)).supportClauses());
		var missing = new PlacementIdentity.PlacementProofKey(
			PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY, OWNER, "missing");
		Assert.assertEquals(1, ((List<?>)retain.invoke(null, template, List.of(product), missing)).size());
		Assert.assertEquals(0,
			((FactorizedSupportClauses)product.supportClauses()).materializedClauseCount());
	}

	@Test
	public void noOpRelocationAuthorityDoesNotExpandIndependentProducts() throws Exception {
		RelocationActionKey action = action("authority");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			bindings(0, "authority-left", 100, action), bindings(1, "authority-right", 100, action));
		CandidateEmissionRealization current = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(), axes, null, true);
		CandidateEmissionRealization rebound = CandidateEmissionRealization.factorized(
			current.key(), List.of(), axes, null, true);

		Assert.assertTrue(sameAuthority(current, rebound, Set.of(action)));
		Assert.assertEquals("authority validation must inspect the axes, not 10,000 tuples", 0,
			((FactorizedSupportClauses)current.supportClauses()).materializedClauseCount());
		Assert.assertEquals(0,
			((FactorizedSupportClauses)rebound.supportClauses()).materializedClauseCount());
		Assert.assertFalse("removed actions must invalidate even an identical relation",
			sameAuthority(current, current, Set.of()));
	}

	@Test
	public void factorizedAuthorityRejectsEqualButForeignSourceAndAction() throws Exception {
		RelocationActionKey action = action("foreign-authority");
		CompiledHopKey owner = key("authority-owner");
		CandidateEmissionRealization current = authorityRealization(binding(0, owner, action));
		CandidateEmissionRealization foreignOwner = authorityRealization(
			binding(0, key("authority-owner"), action));
		CandidateEmissionRealization foreignAction = authorityRealization(
			binding(0, owner, action("foreign-authority")));

		Assert.assertEquals(current, foreignOwner);
		Assert.assertEquals(current, foreignAction);
		Assert.assertFalse(sameAuthority(current, foreignOwner, Set.of(action)));
		Assert.assertFalse(sameAuthority(current, foreignAction, Set.of(action)));
		Assert.assertEquals(0,
			((FactorizedSupportClauses)current.supportClauses()).materializedClauseCount());
	}

	private static CandidateEmissionRealization authorityRealization(CandidateRealizationInputBinding binding) {
		return CandidateEmissionRealization.factorized(PlacementRealizationKey.local(LOCAL_EMISSION),
			List.of(), List.of(List.of(binding)), null, true);
	}

	private static boolean sameAuthority(CandidateEmissionRealization current,
		CandidateEmissionRealization rebound, Set<RelocationActionKey> actions) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"sameRelocationBoundEmissionAuthority", List.class, List.class, Set.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null,
			List.of(new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW, null, List.of(current))),
			List.of(new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW, null, List.of(rebound))), actions);
	}

	@Test
	public void productionProductKeepsHundredByHundredSourcesFactorized() throws Exception {
		RelocationActionKey action = action("shared");
		List<CandidateRealizationInputBinding> left = bindings(0, "left", 100, action);
		List<CandidateRealizationInputBinding> right = bindings(1, "right", 100, action);
		List<CandidateEmissionRealization> generated = generate(
			new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW), List.of(left, right), null, false, null);

		Assert.assertEquals(1, generated.size());
		FactorizedSupportClauses clauses =
			(FactorizedSupportClauses)generated.get(0).supportClauses();
		Assert.assertEquals(10_000, clauses.size());
		Assert.assertEquals(200, clauses.retainedFactorOptionCount());
		Assert.assertEquals(0, clauses.materializedClauseCount());
		generated.get(0).hashCode();
		Assert.assertEquals(0, clauses.materializedClauseCount());
	}

	@Test
	public void productionProductMatchesExplicitSmallRelationWithoutExpansion() throws Exception {
		RelocationActionKey action = action("small");
		List<CandidateRealizationInputBinding> left = bindings(0, "left-small", 2, action);
		List<CandidateRealizationInputBinding> right = bindings(1, "right-small", 3, action);
		CandidateEmissionRealization actual = generate(
			new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW), List.of(left, right), null, false, null).get(0);
		FactorizedSupportClauses clauses = (FactorizedSupportClauses)actual.supportClauses();
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		for(CandidateRealizationInputBinding leftBinding : left)
			for(CandidateRealizationInputBinding rightBinding : right)
				explicit.add(new CandidateRealizationSupportClause(
					clauses.proofs(), List.of(leftBinding, rightBinding)));
		CandidateEmissionRealization expected = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL_EMISSION), explicit);

		Assert.assertTrue(clauses.equals(expected.supportClauses()));
		Assert.assertEquals(expected.hashCode(), actual.hashCode());
		Assert.assertEquals(0, clauses.materializedClauseCount());
	}

	@Test
	public void productionFactorizedMetricsMatchEnumeratorTree() throws Exception {
		RelocationActionKey action = action("metrics");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		generate(new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW), List.of(
			bindings(0, "metrics-left", 2, action),
			bindings(1, "metrics-right", 2, action)), null, false, null, metrics);

		Assert.assertEquals(7, metrics.snapshot().relocationPrefixes());
		Assert.assertEquals(4, metrics.snapshot().relocationLeaves());
		Assert.assertEquals(2, metrics.snapshot().relocationPeakDepth());
	}

	@Test
	public void independentProductSelectsTheExactOwnedCartesianClause() {
		RelocationActionKey action = action("independent-select");
		CompiledHopKey leftOwner = key("fixed-left-owner");
		CompiledHopKey rightOwner = key("fixed-right-owner");
		List<CandidateRealizationInputBinding> left = durableBindings(
			0, leftOwner, "fixed-left", 2, action);
		List<CandidateRealizationInputBinding> right = durableBindings(
			1, rightOwner, "fixed-right", 3, action);
		CandidateEmissionRealization realization = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(), List.of(left, right), null, true);

		var product = realization.independentSupportProduct().orElseThrow();
		Assert.assertEquals(6, product.logicalClauseCount());
		Assert.assertSame(leftOwner, product.axes().get(0).sourceOwner());
		Assert.assertSame(rightOwner, product.axes().get(1).sourceOwner());
		var selected = new java.util.IdentityHashMap<CompiledHopKey,
			PlacementIdentity.CandidateRealizationSupportKey>();
		selected.put(leftOwner, CandidateSelections.requiredInputSupportIdentity(left.get(1).source()));
		selected.put(rightOwner, CandidateSelections.requiredInputSupportIdentity(right.get(2).source()));
		CandidateRealizationSupportClause clause = product.select(selected::get);
		Assert.assertTrue(realization.ownsSupportClauseIdentity(clause));
		Assert.assertSame(left.get(1), clause.inputBindings().get(0));
		Assert.assertSame(right.get(2), clause.inputBindings().get(1));
	}

	@Test
	public void independentProductRejectsMixedActionForeignOwnerAndRepeatedSupportKey() {
		RelocationActionKey firstAction = action("eligibility-first");
		RelocationActionKey secondAction = action("eligibility-second");
		CompiledHopKey owner = key("eligibility-owner");
		List<CandidateRealizationInputBinding> durable = durableBindings(
			0, owner, "eligibility", 2, firstAction);
		CandidateRealizationInputBinding mixedAction = CandidateRealizationInputBinding.relocation(
			0, durable.get(1).source(), secondAction);
		Assert.assertTrue(CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(),
			List.of(List.of(durable.get(0), mixedAction)), null, true)
			.independentSupportProduct().isEmpty());

		CompiledHopKey equalForeignOwner = key("eligibility-owner");
		CandidateRealizationInputBinding foreignOwner = CandidateRealizationInputBinding.relocation(
			0, durableReference(equalForeignOwner, "eligibility-foreign"), firstAction);
		Assert.assertNotSame(owner, equalForeignOwner);
		Assert.assertTrue(CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(),
			List.of(List.of(durable.get(0), foreignOwner)), null, true)
			.independentSupportProduct().isEmpty());

		PlacementRealizationKey sharedDurable = PlacementRealizationKey.durable(
			FOUT_EMISSION, anchor("shared-support"));
		CandidateRealizationReference firstRule = new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of()), sharedDurable);
		CandidateRealizationReference secondRule = new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of(
				PlacementAnalysis.CandidateInputState.absentLocal())), sharedDurable);
		Assert.assertTrue(CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(), List.of(List.of(
				CandidateRealizationInputBinding.relocation(0, firstRule, firstAction),
				CandidateRealizationInputBinding.relocation(0, secondRule, firstAction))), null, true)
			.independentSupportProduct().isEmpty());
	}

	@Test
	public void independentProductRejectsValueMapConsumerAndSource() {
		RelocationActionKey action = action("value-map-guard");
		CompiledHopKey owner = key("value-map-owner");
		CandidateRealizationInputBinding durable = durableBindings(
			0, owner, "value-map-durable", 1, action).get(0);
		Assert.assertTrue(CandidateEmissionRealization.factorized(
			PlacementRealizationKey.valueMap(FOUT_EMISSION, "consumer-map"), List.of(),
			List.of(List.of(durable)), null, true).independentSupportProduct().isEmpty());
		CandidateRealizationReference valueMap = new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of()),
			PlacementRealizationKey.valueMap(FOUT_EMISSION, "source-map"));
		Assert.assertTrue(CandidateEmissionRealization.factorized(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(), List.of(List.of(
				CandidateRealizationInputBinding.relocation(0, valueMap, action))), null, true)
			.independentSupportProduct().isEmpty());
	}

	@Test
	public void mixedActionsAndRepeatedOwnersRetainCorrelatedEnumerator() throws Exception {
		RelocationActionKey firstAction = action("first");
		RelocationActionKey secondAction = action("second");
		CandidateEmissionRealization singleton = generate(
			new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW),
			List.of(List.of(binding(0, key("singleton"), firstAction))),
			null, false, null).get(0);
		Assert.assertFalse(singleton.supportClauses() instanceof FactorizedSupportClauses);
		CandidateRealizationInputBinding first = binding(0, key("mixed-a"), firstAction);
		CandidateRealizationInputBinding second = binding(0, key("mixed-b"), secondAction);
		CandidateEmissionRealization mixed = generate(
			new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW),
			List.of(List.of(first, second)), null, false, null).get(0);
		Assert.assertFalse(mixed.supportClauses() instanceof FactorizedSupportClauses);

		CompiledHopKey sharedOwner = key("shared-owner");
		CandidateRealizationInputBinding sharedFirst = binding(0, sharedOwner, firstAction);
		CandidateRealizationInputBinding sharedSecond = binding(1, sharedOwner, firstAction);
		CandidateEmissionRealization correlated = generate(
			new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW),
			List.of(List.of(sharedFirst), List.of(sharedSecond)), null, false, null).get(0);
		Assert.assertFalse(correlated.supportClauses() instanceof FactorizedSupportClauses);
		Assert.assertEquals(1, correlated.supportClauses().size());
	}

	@Test
	public void nativeFoutAllDirectStillProducesNoRelocationProduct() throws Exception {
		CandidateRealizationInputBinding direct = CandidateRealizationInputBinding.direct(
			0, reference(key("direct-source")));
		Assert.assertTrue(generate(new CandidateEmissionFact(FOUT_EMISSION, FType.ROW),
			List.of(List.of(direct)), anchor("unused-output"), false, null).isEmpty());
	}

	@Test
	public void memoDeltaRestrictionDeletionAndTwoAxisAdditionStayFactorized() throws Exception {
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(
			null, new SearchSpaceMetrics());
		Map<Object,Object> products = new HashMap<>();
		RelocationActionKey action = action("delta");
		List<CandidateRealizationInputBinding> left = bindings(0, "delta-left", 100, action);
		List<CandidateRealizationInputBinding> right = bindings(1, "delta-right", 100, action);
		CandidateEmissionFact emission = new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW);
		FactorizedSupportClauses initial = factorized(memoProduct(builder, emission,
			List.of(left, right), products));
		Assert.assertEquals(10_000, initial.size());

		FactorizedSupportClauses deleted = factorized(memoProduct(builder, emission,
			List.of(left.subList(1, left.size()), right), products));
		Assert.assertEquals(9_900, deleted.size());
		Assert.assertEquals(0, initial.materializedClauseCount());
		Assert.assertEquals(0, deleted.materializedClauseCount());

		List<CandidateRealizationInputBinding> expandedLeft = new ArrayList<>(left);
		expandedLeft.add(binding(0, key("delta-left-added"), action));
		List<CandidateRealizationInputBinding> expandedRight = new ArrayList<>(right);
		expandedRight.add(binding(1, key("delta-right-added"), action));
		FactorizedSupportClauses expanded = factorized(memoProduct(builder, emission,
			List.of(List.copyOf(expandedLeft), List.copyOf(expandedRight)), products));
		Assert.assertEquals(10_201, expanded.size());
		Assert.assertEquals(202, expanded.retainedFactorOptionCount());
		Assert.assertEquals(0, initial.materializedClauseCount());
		Assert.assertEquals(0, deleted.materializedClauseCount());
		Assert.assertEquals(0, expanded.materializedClauseCount());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generate(CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices, DurableAnchorKey output,
		boolean recomputesRanges, DurableAnchorKey dynamicPool) throws Exception {
		return generate(emission, choices, output, recomputesRanges, dynamicPool, null);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generate(CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices, DurableAnchorKey output,
		boolean recomputesRanges, DurableAnchorKey dynamicPool, SearchSpaceMetrics metrics)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"generateRelocationBindingProduct", CompiledHopKey.class, CandidateEmissionFact.class,
			List.class, DurableAnchorKey.class, boolean.class, DurableAnchorKey.class,
			SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(null, OWNER, emission,
			choices, output, recomputesRanges, dynamicPool, metrics);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> memoProduct(
		NeutralPlacementGraphBuilder builder, CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices,
		Map<Object,Object> products) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"relocationBindingProduct", CompiledHopKey.class, CandidateEmissionFact.class,
			List.class, DurableAnchorKey.class, boolean.class, DurableAnchorKey.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(
			PlacementBuilderTestAccess.relationClosure(builder), OWNER, emission,
			choices, null, false, null, products);
	}

	private static FactorizedSupportClauses factorized(
		List<CandidateEmissionRealization> realizations) {
		Assert.assertEquals(1, realizations.size());
		Assert.assertTrue(realizations.get(0).supportClauses().getClass().getName(),
			realizations.get(0).supportClauses() instanceof FactorizedSupportClauses);
		return (FactorizedSupportClauses)realizations.get(0).supportClauses();
	}

	private static List<CandidateRealizationInputBinding> bindings(int position,
		String prefix, int count, RelocationActionKey action) {
		List<CandidateRealizationInputBinding> result = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			result.add(binding(position, key(prefix + '-' + index), action));
		return List.copyOf(result);
	}

	private static List<CandidateRealizationInputBinding> durableBindings(int position,
		CompiledHopKey owner, String prefix, int count, RelocationActionKey action) {
		List<CandidateRealizationInputBinding> result = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			result.add(CandidateRealizationInputBinding.relocation(position,
				durableReference(owner, prefix + '-' + index), action));
		return List.copyOf(result);
	}

	private static CandidateRealizationReference durableReference(
		CompiledHopKey owner, String anchorIdentity) {
		return new CandidateRealizationReference(new CandidateRuleKey(owner, List.of()),
			PlacementRealizationKey.durable(FOUT_EMISSION, anchor(anchorIdentity)));
	}

	private static CandidateRealizationInputBinding binding(int position,
		CompiledHopKey sourceOwner, RelocationActionKey action) {
		return CandidateRealizationInputBinding.relocation(
			position, reference(sourceOwner), action);
	}

	private static CandidateRealizationReference reference(CompiledHopKey sourceOwner) {
		return new CandidateRealizationReference(new CandidateRuleKey(sourceOwner, List.of()),
			PlacementRealizationKey.local(LOCAL_EMISSION));
	}

	private static RelocationActionKey action(String id) {
		return new RelocationActionKey(version("source-" + id, 0), FOUT_STATE, FType.ROW,
			anchor(id), REGION.normalizedSignature(), List.of(OWNER));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("worker-b", List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey("relocation-factor", id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("relocation-factor", "main", "root", "compiled",
			REGION, id, id);
	}
}

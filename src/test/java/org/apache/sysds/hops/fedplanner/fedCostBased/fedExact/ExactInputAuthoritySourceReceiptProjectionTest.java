/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactInputAuthoritySourceReceiptProjectionTest {
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState FED_ROW_EMISSION =
		new PlacementEmissionState(FED_ROW, false);
	private static final PlacementState FED_LOUT =
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState DERIVED_FED_ROW_EMISSION =
		new PlacementEmissionState(FED_ROW, true);
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"source-receipt", "main", List.of("main/0"), "root", "compiled");
	private static final ValueVersionKey SOURCE_VERSION = version("source");
	private static final CompiledHopKey SOURCE = key("source");
	private static final CompiledHopKey ALIAS = key("alias");
	private static final CompiledHopKey OUTSIDE = key("outside");
	private static final CompiledHopKey CONSUMER = key("consumer");

	@Test
	public void nonSourceRealizationsCoalesceButSourceAliasAndSelfRemainObserved() throws Exception {
		RelocationAction action = action(CONSUMER, 0, "read-set");
		Set<CompiledHopKey> owners = sourceOwners(graphWithEqualVersionAliases());
		Assert.assertTrue(owners.contains(SOURCE));
		Assert.assertTrue(owners.contains(ALIAS));

		Assert.assertEquals(observe(receipt(OUTSIDE, "a", null, null), List.of(action), owners),
			observe(receipt(OUTSIDE, "b", null, null), List.of(action), owners));
		Assert.assertNotEquals(observe(receipt(SOURCE, "a", null, null), List.of(action), owners),
			observe(receipt(SOURCE, "b", null, null), List.of(action), owners));
		Assert.assertNotEquals(observe(receipt(ALIAS, "a", null, null), List.of(action), owners),
			observe(receipt(ALIAS, "b", null, null), List.of(action), owners));

		Set<CompiledHopKey> selfOnly = identitySet(OUTSIDE);
		Assert.assertNotEquals(observe(receipt(OUTSIDE, "a", null, null), List.of(action), selfOnly),
			observe(receipt(OUTSIDE, "b", null, null), List.of(action), selfOnly));
	}

	@Test
	public void equalButDistinctOwnerDoesNotAcquireSourceAuthority() throws Exception {
		CompiledHopKey equalForeign = key("source");
		Assert.assertEquals(SOURCE, equalForeign);
		Assert.assertNotSame(SOURCE, equalForeign);
		Set<CompiledHopKey> owners = identitySet(SOURCE);
		RelocationAction action = action(CONSUMER, 0, "identity");
		Assert.assertEquals(observe(receipt(equalForeign, "a", null, null), List.of(action), owners),
			observe(receipt(equalForeign, "b", null, null), List.of(action), owners));
	}

	@Test
	public void emptyActionsKeepTheCompleteReceiptObservation() throws Exception {
		Set<CompiledHopKey> noSources = identitySet();
		Assert.assertNotEquals(observe(receipt(OUTSIDE, "a", null, null), List.of(), noSources),
			observe(receipt(OUTSIDE, "b", null, null), List.of(), noSources));
	}

	@Test
	public void projectionPreservesReceiptPresenceBindingsAndResidency() throws Exception {
		RelocationAction action = action(OUTSIDE, 0, "fields");
		Set<CompiledHopKey> noSources = identitySet();
		CandidateRealizationReference left = reference(SOURCE, "left");
		CandidateRealizationReference right = reference(SOURCE, "right");
		CandidateSelectionReceipt baseline = receipt(OUTSIDE, "same",
			CandidateRealizationInputBinding.direct(0, left), null);
		CandidateSelectionReceipt changedBinding = receipt(OUTSIDE, "same",
			CandidateRealizationInputBinding.direct(0, right), null);
		Assert.assertNotEquals(observe(null, List.of(action), noSources),
			observe(baseline, List.of(action), noSources));
		Assert.assertNotEquals(observe(baseline, List.of(action), noSources),
			observe(changedBinding, List.of(action), noSources));

		DurableAnchorKey poolA = anchor("pool-a");
		DurableAnchorKey poolB = anchor("pool-b");
		CandidateSelectionReceipt atA = durableReceipt(OUTSIDE, poolA);
		CandidateSelectionReceipt atB = durableReceipt(OUTSIDE, poolB);
		Assert.assertNotEquals("realization may be omitted, but proven residency may not",
			observe(atA, List.of(action), noSources), observe(atB, List.of(action), noSources));

		RelocationAction first = action(OUTSIDE, 0, "relocation-a");
		RelocationAction second = action(OUTSIDE, 0, "relocation-b");
		CandidateSelectionReceipt relocationA = receipt(OUTSIDE, "same",
			CandidateRealizationInputBinding.relocation(0, left, first.key()), null);
		CandidateSelectionReceipt relocationB = receipt(OUTSIDE, "same",
			CandidateRealizationInputBinding.relocation(0, left, second.key()), null);
		Assert.assertNotEquals(observe(relocationA, List.of(first, second), noSources),
			observe(relocationB, List.of(first, second), noSources));

		Assert.assertNotEquals("derived action identity remains observable",
			observe(derivedReceipt(OUTSIDE, anchor("derived")), List.of(action), noSources),
			observe(derivedReceipt(OUTSIDE, anchor("derived")), List.of(action), noSources));
	}

	@Test
	public void projectedObservationClassesPreserveEveryCanonicalTruthBit() throws Exception {
		RelocationAction action = action(OUTSIDE, 0, "truth");
		Set<CompiledHopKey> owners = identitySet(SOURCE);
		CandidateRealizationReference left = reference(SOURCE, "left");
		CandidateRealizationReference right = reference(SOURCE, "right");
		List<CandidateSelectionReceipt> receipts = List.of(
			receipt(OUTSIDE, "a", CandidateRealizationInputBinding.direct(0, left), null),
			receipt(OUTSIDE, "b", CandidateRealizationInputBinding.direct(0, left), null),
			receipt(OUTSIDE, "c", CandidateRealizationInputBinding.direct(0, right), null));
		var receiptVariable = new ExactCategoricalSolver.Variable("receipt", receipts.size());
		var sourceVariable = new ExactCategoricalSolver.Variable("source", 2);
		var dummyVariable = new ExactCategoricalSolver.Variable("dummy", 8);
		List<CandidateRealizationReference> sources = List.of(left, right);
		var canonical = ExactCategoricalSolver.Factor.lazy(
			List.of(receiptVariable, sourceVariable, dummyVariable), values -> {
			var binding = receipts.get(values[0]).supportClause().inputBindings().get(0);
			return binding.source().equals(sources.get(values[1])) ? 0d : Double.POSITIVE_INFINITY;
		});
		List<?>[] keys = new List<?>[] {
			receipts.stream().map(receipt -> uncheckedObserve(receipt, List.of(action), owners)).toList(),
			sources,
			Collections.nCopies(8, "dummy")
		};
		ExactHardFactorObservationDecomposition.Result encoded =
			ExactHardFactorObservationDecomposition.create("source-receipt-truth", canonical, keys);
		Assert.assertNotNull(encoded);
		Assert.assertEquals("the two irrelevant realization variants must share one category",
			encoded.observations().get(0)[0], encoded.observations().get(0)[1]);
		assertEveryCell(canonical, encoded);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> sourceOwners(NeutralPlacementGraph graph) throws Exception {
		Method method = ExactPhysicalModel.class.getDeclaredMethod(
			"relocationSourceOwnersByVersion", NeutralPlacementGraph.class);
		method.setAccessible(true);
		Map<ValueVersionKey,Set<CompiledHopKey>> byVersion =
			(Map<ValueVersionKey,Set<CompiledHopKey>>) method.invoke(null, graph);
		return byVersion.get(SOURCE_VERSION);
	}

	private static Object uncheckedObserve(CandidateSelectionReceipt receipt,
		List<RelocationAction> actions, Set<CompiledHopKey> sourceOwners) {
		try { return observe(receipt, actions, sourceOwners); }
		catch(Exception failure) { throw new AssertionError(failure); }
	}

	private static Object observe(CandidateSelectionReceipt receipt,
		List<RelocationAction> actions, Set<CompiledHopKey> sourceOwners) throws Exception {
		Method observer = Arrays.stream(ExactPhysicalModel.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("receiptObservation"))
			.findFirst().orElseThrow();
		observer.setAccessible(true);
		Method indexer = ExactPhysicalModel.class.getDeclaredMethod(
			"relevantDirectBindingPositions", List.class);
		indexer.setAccessible(true);
		Object positions = indexer.invoke(null, actions);
		if(observer.getParameterCount() == 3)
			return observer.invoke(null, receipt, actions, positions);
		Assert.assertEquals(4, observer.getParameterCount());
		return observer.invoke(null, receipt, actions, positions, sourceOwners);
	}

	private static void assertEveryCell(ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result encoded) {
		List<int[]> maps = encoded.observations();
		var truth = encoded.solverFactors().get(encoded.solverFactors().size() - 1);
		for(int receipt = 0; receipt < canonical.scope().get(0).domainSize(); receipt++)
			for(int source = 0; source < canonical.scope().get(1).domainSize(); source++)
				for(int dummy = 0; dummy < canonical.scope().get(2).domainSize(); dummy++) {
					int[] original = {receipt, source, dummy};
					int[] categories = {maps.get(0)[receipt], maps.get(1)[source], maps.get(2)[dummy]};
					Assert.assertEquals(Double.doubleToRawLongBits(canonical.cost(original)),
						Double.doubleToRawLongBits(truth.cost(categories)));
				}
	}

	private static NeutralPlacementGraph graphWithEqualVersionAliases() {
		return new NeutralPlacementGraph(List.of(
			node(SOURCE, SOURCE_VERSION), node(ALIAS, version("source")),
			node(OUTSIDE, version("outside")), node(CONSUMER, version("consumer"))),
			List.of(), List.of());
	}

	private static Node node(CompiledHopKey owner, ValueVersionKey version) {
		return new Node(owner, NodeKind.OPERATION, version, true, List.of(FED_ROW), List.of(), List.of());
	}

	private static CandidateSelectionReceipt receipt(CompiledHopKey owner, String realization,
		CandidateRealizationInputBinding binding, DurableAnchorKey durable) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementRealizationKey key = durable == null
			? PlacementRealizationKey.sourceLineage(FED_ROW_EMISSION, "lineage-" + realization)
			: PlacementRealizationKey.durable(FED_ROW_EMISSION, durable);
		List<CandidateRealizationInputBinding> bindings = binding == null ? List.of() : List.of(binding);
		CandidateEmissionRealization value = new CandidateEmissionRealization(key,
			List.of(new CandidateRealizationSupportClause(List.of(), bindings)));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			FED_ROW_EMISSION, FType.ROW, null, List.of(value));
		CandidateEmissionRealization owned = emission.realizations().get(0);
		return new CandidateSelectionReceipt(rule, emission, owned,
			owned.supportClauses().get(0), List.of());
	}

	private static CandidateSelectionReceipt durableReceipt(CompiledHopKey owner,
		DurableAnchorKey anchor) {
		return receipt(owner, anchor.placementId(), null, anchor);
	}

	private static CandidateSelectionReceipt derivedReceipt(CompiledHopKey owner,
		DurableAnchorKey anchor) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		DerivedFoutMaterializationActionKey derived = new DerivedFoutMaterializationActionKey(
			owner, version("derived"), rule, FED_LOUT, FED_ROW, anchor, owner,
			FType.ROW, FType.ROW, "derived-scope");
		CandidateEmissionRealization value = new CandidateEmissionRealization(
			PlacementRealizationKey.durable(DERIVED_FED_ROW_EMISSION, anchor),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			DERIVED_FED_ROW_EMISSION, FType.ROW, derived, List.of(value));
		CandidateEmissionRealization owned = emission.realizations().get(0);
		return new CandidateSelectionReceipt(rule, emission, owned,
			owned.supportClauses().get(0), List.of());
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String id) {
		return new CandidateRealizationReference(new CandidateRuleKey(owner, List.of()),
			PlacementRealizationKey.sourceLineage(FED_ROW_EMISSION, "reference-" + id));
	}

	private static RelocationAction action(CompiledHopKey consumer, int position, String id) {
		DurableAnchorKey anchor = anchor("action-" + id);
		RelocationActionKey key = new RelocationActionKey(SOURCE_VERSION, FED_ROW, anchor,
			"scope-" + id, List.of(consumer));
		return new RelocationAction(key, List.of(new ObligationKey(consumer, position,
			SOURCE_VERSION, FED_ROW, key, "context-" + id)));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("worker-" + id + ":1234", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... values) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		result.addAll(List.of(values));
		return Collections.unmodifiableSet(result);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("source-receipt", "main", "root", "compiled", REGION, id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("source-receipt", id, REGION, 0, VersionKind.ORDINARY, List.of());
	}
}

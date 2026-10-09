/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
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

/** Exact derived FOUT actions sharing an anchor must share one canonical-owner inventory scan. */
public class DerivedFoutAnchorOwnerReuseTest {
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.FULL, false);

	@Test
	public void repeatedExactAnchorActionsScanTheOriginInventoryOnce() throws Exception {
		Fixture fixture = new Fixture("repeated");
		DurableAnchorKey exactAnchor = fixture.anchor("shared-placement", FType.FULL, "worker:1");
		DurableAnchorKey equalActionAnchor = fixture.anchor(
			"shared-placement", FType.FULL, "worker:1");
		Assert.assertEquals(exactAnchor, equalActionAnchor);
		Assert.assertNotSame(exactAnchor, equalActionAnchor);

		Node physicalFirst = fixture.owner("a-physical", fixture.anchor(
			"other-placement", FType.FULL, "worker:1"), FType.FULL, true);
		Node exactLater = fixture.owner("z-exact", exactAnchor, FType.FULL, true);
		fixture.addFederatedOrigin(physicalFirst);
		fixture.addFederatedOrigin(exactLater);
		fixture.nodes.addAll(List.of(physicalFirst, exactLater));
		fixture.addNoise(94);

		List<CandidateRuleFact> facts = new ArrayList<>();
		List<CandidateEmissionRealization> originalRealizations = new ArrayList<>();
		List<DurableAnchorKey> actionAnchors = new ArrayList<>();
		for(int index = 0; index < 32; index++) {
			Node producer = fixture.producer("producer-" + index);
			fixture.nodes.add(producer);
			DurableAnchorKey actionAnchor = index % 2 == 0 ? exactAnchor : equalActionAnchor;
			CandidateRuleFact fact = fixture.derivedFact(producer, actionAnchor,
				producer.key(), FType.FULL);
			facts.add(fact);
			actionAnchors.add(actionAnchor);
			originalRealizations.add(fact.allowedEmissionFacts().get(0).realizations().get(0));
		}
		CountingOrigins origins = fixture.countingOrigins();
		List<CandidateRuleFact> rebound = bind(facts, fixture.scopes, fixture.nodes, origins);

		Assert.assertEquals("one lazy owner inventory scan serves every equal full anchor key",
			fixture.nodes.size(), origins.gets());
		for(int index = 0; index < rebound.size(); index++) {
			Assert.assertSame("fact encounter order and exact rule authority are unchanged",
				facts.get(index).key(), rebound.get(index).key());
			CandidateEmissionFact emission = rebound.get(index).allowedEmissionFacts().get(0);
			DerivedFoutMaterializationActionKey action = emission.derivedFoutAction();
			Assert.assertSame("global exact-anchor authority outranks an earlier physical match",
				exactLater.key(), action.durableAnchorOwner());
			Assert.assertSame("rebinding returns the action's equal-distinct exact anchor object",
				actionAnchors.get(index), action.durableAnchor());
			Assert.assertSame("owner rebinding preserves the original support realization",
				originalRealizations.get(index), emission.realizations().get(0));
			Assert.assertEquals(referenceOwner(facts.get(index).allowedEmissionFacts().get(0)
				.derivedFoutAction(), fixture.nodes, null, fixture.origins),
				new ExpectedOwner(action.durableAnchorOwner(), action.durableAnchorOwnerFType()));
		}
	}

	@Test
	public void nativeOwnerMemoUsesTheCompleteAnchorKeyNotOnlyThePhysicalPool()
		throws Exception {
		Fixture fixture = new Fixture("full-anchor-key");
		DurableAnchorKey firstAnchor = fixture.anchor("placement-a", FType.FULL, "worker:keys");
		DurableAnchorKey secondAnchor = fixture.anchor("placement-b", FType.FULL, "worker:keys");
		Node firstOwner = fixture.owner("z-owner-a", firstAnchor, FType.FULL, true);
		Node secondOwner = fixture.owner("a-owner-b", secondAnchor, FType.FULL, true);
		fixture.addFederatedOrigin(firstOwner);
		fixture.addFederatedOrigin(secondOwner);
		Node firstProducer = fixture.producer("producer-a");
		Node secondProducer = fixture.producer("producer-b");
		fixture.nodes.addAll(List.of(firstOwner, secondOwner, firstProducer, secondProducer));
		CandidateRuleFact first = fixture.derivedFact(
			firstProducer, firstAnchor, firstProducer.key(), FType.FULL);
		CandidateRuleFact second = fixture.derivedFact(
			secondProducer, secondAnchor, secondProducer.key(), FType.FULL);

		CountingOrigins origins = fixture.countingOrigins();
		List<CandidateRuleFact> rebound = bind(List.of(first, second), fixture.scopes,
			fixture.nodes, origins);
		Assert.assertEquals("distinct full keys still share the one owner inventory",
			fixture.nodes.size(), origins.gets());
		Assert.assertSame("placement-a must retain its exact owner despite a lower physical peer",
			firstOwner.key(), rebound.get(0).allowedEmissionFacts().get(0)
				.derivedFoutAction().durableAnchorOwner());
		Assert.assertSame("placement-b must perform a distinct full-key native lookup",
			secondOwner.key(), rebound.get(1).allowedEmissionFacts().get(0)
				.derivedFoutAction().durableAnchorOwner());
	}

	@Test
	public void oneActionScansOnceAndNoDerivedActionDoesNotConsultOrigins() throws Exception {
		Fixture fixture = new Fixture("boundaries");
		DurableAnchorKey anchor = fixture.anchor("only", FType.FULL, "worker:2");
		Node owner = fixture.owner("owner", anchor, FType.FULL, true);
		fixture.addFederatedOrigin(owner);
		fixture.nodes.add(owner);
		fixture.addNoise(63);
		Node producer = fixture.producer("producer");
		fixture.nodes.add(producer);
		CandidateRuleFact derived = fixture.derivedFact(producer, anchor, producer.key(), FType.FULL);
		CountingOrigins one = fixture.countingOrigins();
		Assert.assertSame(owner.key(), bind(List.of(derived), fixture.scopes, fixture.nodes, one)
			.get(0).allowedEmissionFacts().get(0).derivedFoutAction().durableAnchorOwner());
		Assert.assertEquals(fixture.nodes.size(), one.gets());

		CandidateRuleFact ordinary = fixture.ordinaryFact(producer);
		CountingOrigins none = fixture.countingOrigins();
		Assert.assertSame("a fact without derived actions remains an identity no-op", ordinary,
			bind(List.of(ordinary), fixture.scopes, fixture.nodes, none).get(0));
		Assert.assertEquals("native/ordinary facts must not initialize the owner inventory", 0,
			none.gets());
	}

	@Test
	public void exactTiesPhysicalFallbackAndOwnerOverlayMatchTheStableReferenceScanner()
		throws Exception {
		Fixture ties = new Fixture("ties");
		DurableAnchorKey exact = ties.anchor("exact", FType.FULL, "worker:3");
		CompiledHopKey firstKey = ties.key("equal-owner");
		CompiledHopKey secondKey = ties.copyKey(firstKey);
		Node first = ties.owner(firstKey, exact, FType.FULL, true);
		Node second = ties.owner(secondKey, exact, FType.FULL, true);
		ties.addFederatedOrigin(first);
		ties.addFederatedOrigin(second);
		Node producer = ties.producer("producer");
		ties.nodes.addAll(List.of(first, second, producer));
		CandidateRuleFact tied = ties.derivedFact(producer, exact, producer.key(), FType.FULL);
		DerivedFoutMaterializationActionKey tiedAction = bind(List.of(tied), ties.scopes,
			ties.nodes, ties.countingOrigins()).get(0).allowedEmissionFacts().get(0).derivedFoutAction();
		Assert.assertSame("comparison ties retain the first encountered exact owner",
			first.key(), tiedAction.durableAnchorOwner());

		Fixture physical = new Fixture("physical");
		DurableAnchorKey requested = physical.anchor("requested", FType.FULL, "worker:4");
		Node later = physical.owner("z-owner", physical.anchor(
			"z-pool", FType.FULL, "worker:4"), FType.FULL, true);
		Node earlier = physical.owner("a-owner", physical.anchor(
			"a-pool", FType.FULL, "worker:4"), FType.FULL, true);
		Node illegal = physical.owner("0-illegal", requested, FType.FULL, false);
		Node empty = physical.owner("1-empty", null, FType.FULL, true);
		for(Node node : List.of(later, earlier, illegal, empty))
			physical.addFederatedOrigin(node);
		Node physicalProducer = physical.producer("producer");
		physical.nodes.addAll(List.of(later, earlier, illegal, empty, physicalProducer));
		CandidateRuleFact physicalFact = physical.derivedFact(
			physicalProducer, requested, physicalProducer.key(), FType.FULL);
		DerivedFoutMaterializationActionKey physicalAction = bind(List.of(physicalFact),
			physical.scopes, physical.nodes, physical.countingOrigins()).get(0)
			.allowedEmissionFacts().get(0).derivedFoutAction();
		ExpectedOwner expected = referenceOwner(physicalFact.allowedEmissionFacts().get(0)
			.derivedFoutAction(), physical.nodes, null, physical.origins);
		Assert.assertEquals(expected,
			new ExpectedOwner(physicalAction.durableAnchorOwner(),
				physicalAction.durableAnchorOwnerFType()));
		Assert.assertSame("physical fallback uses the canonical same-layout owner",
			earlier.key(), physicalAction.durableAnchorOwner());
		Assert.assertEquals(FType.FULL, physicalAction.durableAnchorOwnerFType());

		Fixture layouts = new Fixture("layout-kind");
		DurableAnchorKey partAnchor = layouts.anchor("part", FType.PART, "worker:layout");
		DurableAnchorKey otherAnchor = layouts.anchor("other", FType.OTHER, "worker:layout");
		Node partOwner = layouts.owner("part-owner", partAnchor, FType.PART, true);
		Node otherOwner = layouts.owner("other-owner", otherAnchor, FType.OTHER, true);
		Node fallbackOwner = layouts.owner("fallback-owner", null, FType.FULL, true);
		layouts.addFederatedOrigin(partOwner);
		layouts.addFederatedOrigin(otherOwner);
		Node partProducer = layouts.producer("part-producer");
		Node otherProducer = layouts.producer("other-producer");
		Node fullProducer = layouts.producer("full-producer");
		layouts.nodes.addAll(List.of(partOwner, otherOwner, fallbackOwner,
			partProducer, otherProducer, fullProducer));
		CandidateRuleFact partFact = layouts.derivedFact(
			partProducer, partAnchor, fallbackOwner.key(), FType.FULL);
		CandidateRuleFact otherFact = layouts.derivedFact(
			otherProducer, otherAnchor, fallbackOwner.key(), FType.FULL);
		DurableAnchorKey unmatchedFull = layouts.anchor(
			"full", FType.FULL, "worker:layout");
		CandidateRuleFact fullFact = layouts.derivedFact(
			fullProducer, unmatchedFull, fallbackOwner.key(), FType.FULL);
		List<CandidateRuleFact> layoutResult = bind(List.of(partFact, otherFact, fullFact),
			layouts.scopes, layouts.nodes, layouts.countingOrigins());
		Assert.assertSame("PART anchors match only exact PART authority", partOwner.key(),
			layoutResult.get(0).allowedEmissionFacts().get(0)
				.derivedFoutAction().durableAnchorOwner());
		Assert.assertSame("OTHER anchors match only exact OTHER authority", otherOwner.key(),
			layoutResult.get(1).allowedEmissionFacts().get(0)
				.derivedFoutAction().durableAnchorOwner());
		Assert.assertSame("unequal PART/OTHER layouts are not physical matches for FULL",
			fallbackOwner.key(), layoutResult.get(2).allowedEmissionFacts().get(0)
				.derivedFoutAction().durableAnchorOwner());

		Fixture overlaid = new Fixture("overlay");
		DurableAnchorKey overlayAnchor = overlaid.anchor("overlay", FType.FULL, "worker:5");
		Node committed = overlaid.owner("overlay-owner", null, FType.FULL, false);
		Node overlay = overlaid.owner(committed.key(), overlayAnchor, FType.FULL, true);
		overlaid.addFederatedOrigin(committed);
		Node overlayProducer = overlaid.producer("producer");
		overlaid.nodes.addAll(List.of(committed, overlayProducer));
		CandidateRuleFact overlayFact = overlaid.derivedFact(
			overlayProducer, overlayAnchor, overlayProducer.key(), FType.FULL);
		List<CandidateRuleFact> overlayResult = bindOverlay(List.of(overlayFact), overlaid.scopes,
			overlaid.nodes, overlay, overlaid.countingOrigins());
		DerivedFoutMaterializationActionKey overlayAction = overlayResult.get(0)
			.allowedEmissionFacts().get(0).derivedFoutAction();
		Assert.assertSame(committed.key(), overlayAction.durableAnchorOwner());
		Assert.assertEquals(referenceOwner(overlayFact.allowedEmissionFacts().get(0)
			.derivedFoutAction(), overlaid.nodes, overlay, overlaid.origins),
			new ExpectedOwner(overlayAction.durableAnchorOwner(),
				overlayAction.durableAnchorOwnerFType()));
	}

	@Test
	public void aCachedNativeMissStillValidatesEveryProvisionalOwner() throws Exception {
		Fixture fixture = new Fixture("negative");
		DurableAnchorKey missing = fixture.anchor("missing", FType.FULL, "worker:6");
		Node validOwner = fixture.owner("valid-owner", null, FType.FULL, true);
		Node invalidOwner = fixture.owner("invalid-owner", null, FType.FULL, false);
		Node firstProducer = fixture.producer("first-producer");
		Node secondProducer = fixture.producer("second-producer");
		fixture.nodes.addAll(List.of(validOwner, invalidOwner, firstProducer, secondProducer));
		CandidateRuleFact first = fixture.derivedFact(
			firstProducer, missing, validOwner.key(), FType.FULL);
		CandidateRuleFact second = fixture.derivedFact(
			secondProducer, missing, invalidOwner.key(), FType.FULL);
		CountingOrigins origins = fixture.countingOrigins();

		try {
			bind(List.of(first, second), fixture.scopes, fixture.nodes, origins);
			Assert.fail("a repeated negative native lookup must not reuse another action's fallback owner");
		}
		catch(InvocationTargetException error) {
			Assert.assertTrue(error.getCause() instanceof IllegalStateException);
			Assert.assertTrue(error.getCause().getMessage().contains("no exact graph-owned FOUT"));
		}
		Assert.assertEquals("a negative native result shares the inventory but not action fallback",
			fixture.nodes.size(), origins.gets());
	}

	private static ExpectedOwner referenceOwner(DerivedFoutMaterializationActionKey action,
		List<Node> nodes, Node overlay, Map<CompiledHopKey,Hop> origins) {
		List<Node> nativeOwners = nodes.stream()
			.map(node -> overlay != null && node.key() == overlay.key() ? overlay : node)
			.filter(node -> origins.get(node.key()) instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.filter(node -> node.anchors().stream().anyMatch(anchor ->
				PlacementIdentity.samePhysicalWorkerPool(anchor, action.durableAnchor())
					&& selectable(node, anchor.fType())))
			.sorted().toList();
		List<Node> exact = nativeOwners.stream()
			.filter(node -> node.anchors().contains(action.durableAnchor())).toList();
		if(!exact.isEmpty())
			return new ExpectedOwner(exact.get(0).key(), action.durableAnchor().fType());
		if(!nativeOwners.isEmpty()) {
			Node owner = nativeOwners.get(0);
			DurableAnchorKey anchor = owner.anchors().stream()
				.filter(candidate -> PlacementIdentity.samePhysicalWorkerPool(
					candidate, action.durableAnchor()))
				.filter(candidate -> selectable(owner, candidate.fType()))
				.min(Comparator.naturalOrder()).orElseThrow();
			return new ExpectedOwner(owner.key(), anchor.fType());
		}
		return new ExpectedOwner(action.durableAnchorOwner(), action.durableAnchorOwnerFType());
	}

	private static boolean selectable(Node node, FType fType) {
		return node.legalAlternatives().stream().anyMatch(state ->
			state.output() == FederatedOutput.FOUT && state.fType() == fType);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bind(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Long> scopes, List<Node> nodes, Map<CompiledHopKey,Hop> origins)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindExactDerivedFoutAuthorities", List.class, Map.class, List.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts, scopes, nodes, origins);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bindOverlay(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Long> scopes, List<Node> nodes, Node overlay,
		Map<CompiledHopKey,Hop> origins) throws Exception {
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		nodes.forEach(node -> nodesByKey.put(node.key(), node));
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindExactDerivedFoutAuthorities", List.class, Map.class, List.class,
			Map.class, Node.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(
			null, facts, scopes, nodes, nodesByKey, overlay, origins);
	}

	private record ExpectedOwner(CompiledHopKey owner, FType fType) { }

	private static final class Fixture {
		private final String fingerprint;
		private final ControlRegionKey region;
		private final List<Node> nodes = new ArrayList<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Long> scopes = new IdentityHashMap<>();

		private Fixture(String name) {
			fingerprint = "derived-fout-owner-reuse-" + name;
			region = new ControlRegionKey(fingerprint, "main", List.of("body"), "main", name);
		}

		private Node producer(String name) {
			CompiledHopKey key = key(name);
			scopes.put(key, 1L);
			return new Node(key, NodeKind.OPERATION, version(name), true,
				List.of(LOCAL), List.of(), List.of());
		}

		private Node owner(String name, DurableAnchorKey anchor, FType fType, boolean fout) {
			return owner(key(name), anchor, fType, fout);
		}

		private Node owner(CompiledHopKey key, DurableAnchorKey anchor, FType fType, boolean fout) {
			PlacementState state = new PlacementState(ExecType.FED,
				fout ? FederatedOutput.FOUT : FederatedOutput.LOUT, fType, false);
			return new Node(key, NodeKind.OPERATION, version(key.emittedHopInstance()), true,
				List.of(state), List.of(), anchor == null ? List.of() : List.of(anchor));
		}

		private void addFederatedOrigin(Node node) {
			origins.put(node.key(), new DataOp(node.key().emittedHopInstance(), DataType.MATRIX,
				ValueType.FP64, OpOpData.FEDERATED, node.key().emittedHopInstance(), 4, 2, 8, 1000));
		}

		private void addNoise(int count) {
			for(int index = 0; index < count; index++) {
				FType type = index % 3 == 0 ? FType.PART
					: index % 3 == 1 ? FType.OTHER : FType.FULL;
				DurableAnchorKey anchor = index % 5 == 0 ? null
					: anchor("noise-" + index, type, "noise-worker:" + index);
				Node node = owner("noise-" + index, anchor, type, index % 7 != 0);
				nodes.add(node);
				if(index % 2 == 0)
					addFederatedOrigin(node);
			}
		}

		private CandidateRuleFact derivedFact(Node producer, DurableAnchorKey anchor,
			CompiledHopKey provisionalOwner, FType provisionalFType) {
			CandidateRuleKey rule = new CandidateRuleKey(producer.key(), List.of());
			PlacementState output = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, anchor.fType(), false);
			PlacementEmissionState derived = new PlacementEmissionState(output, true);
			DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
				producer.key(), producer.valueVersion(), rule, LOCAL, output, anchor,
				provisionalOwner, provisionalFType, anchor.fType(),
				producer.key().controlRegion().normalizedSignature());
			CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
				derived, anchor, List.of(), List.of());
			return fact(rule, new CandidateEmissionFact(
				derived, FType.FULL, action, List.of(realization)));
		}

		private CandidateRuleFact ordinaryFact(Node producer) {
			CandidateRuleKey rule = new CandidateRuleKey(producer.key(), List.of());
			return fact(rule, new CandidateEmissionFact(
				new PlacementEmissionState(LOCAL, false), FType.FULL));
		}

		private CountingOrigins countingOrigins() {
			return new CountingOrigins(origins);
		}

		private CompiledHopKey key(String name) {
			return new CompiledHopKey(fingerprint, "main", "root", "compiled", region, name, name);
		}

		private CompiledHopKey copyKey(CompiledHopKey key) {
			return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
				key.callSitePath(), key.recompileContext(), key.controlRegion(),
				key.emittedHopInstance(), key.canonicalSourceOrigin());
		}

		private ValueVersionKey version(String name) {
			return new ValueVersionKey(fingerprint, name, region, 0, VersionKind.ORDINARY, List.of());
		}

		private DurableAnchorKey anchor(String id, FType type, String worker) {
			return new DurableAnchorKey(id, type,
				List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
		}
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, CandidateEmissionFact emission) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.LOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emission), "");
	}

	private static final class CountingOrigins extends IdentityHashMap<CompiledHopKey,Hop> {
		private int gets;

		private CountingOrigins(Map<CompiledHopKey,Hop> source) {
			putAll(source);
		}

		@Override
		public Hop get(Object key) {
			gets++;
			return super.get(key);
		}

		private int gets() {
			return gets;
		}
	}
}

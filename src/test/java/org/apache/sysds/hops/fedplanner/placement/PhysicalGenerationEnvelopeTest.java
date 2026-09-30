/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Atomic physical generation must close a fresh owner against complete, current proof authority. */
public class PhysicalGenerationEnvelopeTest {
	private static final PlacementState FED_FULL_LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.FULL, false);
	private static final PlacementState CP_LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED_BROADCAST = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false);

	@Test
	public void freshPhysicalOwnerUsesOnlyCurrentCompleteAuthority() throws Exception {
		Fixture fixture = new Fixture("compiled");
		Envelope expected = fixture.materialize(fixture.pool("pool-a", "worker-a"));
		Assert.assertTrue("the separate-authority materializer must prove the fixture",
			expected.hasDerivedBroadcast());

		Envelope initial = fixture.normalize(fixture.pool("pool-a", "worker-a"));
		Assert.assertTrue("fresh FULL/LOUT owner must atomically acquire derived FOUT/BROADCAST",
			initial.hasDerivedBroadcast());
		Assert.assertEquals("only the fresh owner is returned", List.of(fixture.owner),
			initial.nodes().stream().map(Node::key).toList());
		Assert.assertFalse("complete proof rows are authority, not output rows",
			initial.facts().stream().anyMatch(fact -> fact.key().parentOccurrence() == fixture.source));
		Assert.assertFalse("stale owner extras in proof authority are not copied to fresh output",
			initial.facts().stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.derivedFoutAction() != null
					&& emission.derivedFoutAction().durableAnchor().placementId().equals("stale-pool")));
		Assert.assertEquals("normalizing the same fresh shell twice is stable", initial,
			fixture.normalize(fixture.pool("pool-a", "worker-a")));

		Envelope withdrawn = fixture.normalize();
		Assert.assertFalse("removing the current input pool withdraws derived materialization",
			withdrawn.hasAnyDerivedFout());
		Envelope ambiguous = fixture.normalize(
			fixture.pool("pool-a", "worker-a"), fixture.pool("pool-b", "worker-b"));
		Assert.assertFalse("ambiguous current pools cannot retain an old derived candidate",
			ambiguous.hasAnyDerivedFout());

		Envelope changed = fixture.normalize(fixture.pool("pool-c", "worker-c"));
		Assert.assertTrue("a changed unique current pool produces a replacement", changed.hasDerivedBroadcast());
		Assert.assertTrue("replacement action owns the current pool",
			changed.derived().derivedFoutAction().durableAnchor().placementId().equals("pool-c"));
		Assert.assertFalse("replacement does not copy the old pool action",
			changed.facts().stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.derivedFoutAction() != null
					&& emission.derivedFoutAction().durableAnchor().placementId().equals("pool-a")));

		Assert.assertEquals("restoring the exact authority restores the same complete envelope", initial,
			fixture.normalize(fixture.pool("pool-a", "worker-a")));
	}

	@Test
	public void bootstrapAndFullGenerationRevisionsAreDistinct() throws Exception {
		Class<?> type = nested("LoopSeedRevision");
		Constructor<?> constructor = type.getDeclaredConstructor(String.class, List.class, List.class,
			List.class, List.class, List.class, List.class, List.class, boolean.class, boolean.class);
		constructor.setAccessible(true);
		Object bootstrap = constructor.newInstance("read", List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), true, false);
		Object full = constructor.newInstance("read", List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), true, true);
		Object sameFull = constructor.newInstance("read", List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), true, true);
		Assert.assertNotEquals("bootstrap memo cannot satisfy materialization-enabled generation",
			bootstrap, full);
		Assert.assertEquals("identical full-generation revisions remain stable", full, sameFull);
	}

	@Test
	public void completeBaseCoverageRequiresExactDerivedOutputKey() {
		Fixture fixture;
		try {
			fixture = new Fixture("compiled");
		}
		catch(Exception error) {
			throw new AssertionError(error);
		}
		DurableAnchorKey actionAnchor = fixture.pool("action-pool", "worker-a");
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			fixture.owner, fixture.ownerVersion, fixture.ownerRule, FED_FULL_LOCAL, FED_BROADCAST,
			actionAnchor, fixture.source, FType.FULL, FType.BROADCAST,
			fixture.owner.controlRegion().normalizedSignature());
		PlacementEmissionState state = new PlacementEmissionState(FED_BROADCAST, true);
		CandidateEmissionFact firstDerived = new CandidateEmissionFact(state, FType.FULL, action,
			List.of(CandidateEmissionRealization.durable(state,
				fixture.outputPool("output-a", "worker-a"), List.of(), List.of())));
		CandidateEmissionFact otherDerivedOutput = new CandidateEmissionFact(state, FType.FULL, action,
			List.of(CandidateEmissionRealization.durable(state,
				fixture.outputPool("output-b", "worker-a"), List.of(), List.of())));
		Assert.assertFalse("same coarse derived action with a different durable output is incomplete",
			PlacementRelationClosure.hasCompleteBaseEmissionCoverage(
				List.of(fact(fixture.ownerRule, firstDerived)),
				List.of(fact(fixture.ownerRule, otherDerivedOutput))));

		PlacementEmissionState nativeState = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		CandidateEmissionFact firstNative = new CandidateEmissionFact(nativeState, FType.FULL, null,
			List.of(CandidateEmissionRealization.durable(nativeState,
				fixture.pool("native-a", "worker-a"), List.of(), List.of())));
		CandidateEmissionFact otherNative = new CandidateEmissionFact(nativeState, FType.FULL, null,
			List.of(CandidateEmissionRealization.durable(nativeState,
				fixture.pool("native-b", "worker-a"), List.of(), List.of())));
		Assert.assertTrue("native staging keys may differ because native support is regenerated",
			PlacementRelationClosure.hasCompleteBaseEmissionCoverage(
				List.of(fact(fixture.ownerRule, firstNative)),
				List.of(fact(fixture.ownerRule, otherNative))));
	}

	@Test
	public void recompileEnvelopeForbidsCpFoutButKeepsFedMaterialization() throws Exception {
		Fixture fixture = new Fixture("recompile", List.of(CP_LOCAL, FED_FULL_LOCAL));
		Envelope normalized = fixture.normalize(fixture.pool("pool-a", "worker-a"));
		Assert.assertTrue(normalized.hasDerivedBroadcast());
		Assert.assertFalse("recompile must never synthesize CP/FOUT",
			normalized.nodes().get(0).legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT));
		Assert.assertTrue("derived action remains owned by the fresh output row and exact scope",
			normalized.derived().derivedFoutAction().producer() == fixture.owner
				&& normalized.derived().derivedFoutAction().candidateRule().equals(fixture.ownerRule)
				&& normalized.derived().derivedFoutAction().statementBlockScope()
					.equals(fixture.owner.controlRegion().normalizedSignature()));
	}

	@Test
	public void stableSiblingPhysicalOwnersScanOneCommittedInventory() throws Exception {
		PhysicalClosureFixture fixture = new PhysicalClosureFixture(false);
		ReplayState settled = fixture.settle(fixture.initial(fixture.pool("pool-a", "worker-a")));
		CountingEdgeList edges = new CountingEdgeList(fixture.edges);

		ReplayState unchanged = fixture.close(settled, edges);
		Assert.assertEquals("settled siblings must remain exact without a commit", settled.envelope(),
			unchanged.envelope());
		Assert.assertEquals("both no-commit consumers share one committed proof inventory", 1,
			edges.completedTraversals());
	}

	@Test
	public void committedInputDomainChangeUpdatesAuthorityWithoutRebuildingEdges() throws Exception {
		PhysicalClosureFixture fixture = new PhysicalClosureFixture(true);
		ReplayState settled = fixture.settle(fixture.initial(fixture.pool("pool-a", "worker-a")));
		List<CandidateRuleFact> priorFirstFacts = fixture.ownedFacts(settled, fixture.firstKey);
		ReplayState changedSource = fixture.withSourcePool(settled,
			fixture.rowPool("pool-c", "worker-c-0", "worker-c-1"));
		Assert.assertEquals("changing the source must not pre-edit the intermediate",
			priorFirstFacts, fixture.ownedFacts(changedSource, fixture.firstKey));
		CountingEdgeList edges = new CountingEdgeList(fixture.edges);

		ReplayState changed = fixture.close(changedSource, edges);
		List<CandidateRuleFact> changedFirstFacts = fixture.ownedFacts(changed, fixture.firstKey);
		Assert.assertNotEquals("the first consumer must commit a changed input-domain row",
			priorFirstFacts, changedFirstFacts);
		Assert.assertFalse("the committed intermediate must retain an executable row",
			changedFirstFacts.isEmpty());
		Assert.assertTrue("the committed intermediate must consume the changed ROW domain",
			changedFirstFacts.stream().allMatch(fact -> fact.key().orderedInputs()
				.equals(List.of(CandidateInputState.present(FType.ROW)))));
		List<CandidateRuleFact> downstreamFacts = fixture.ownedFacts(changed, fixture.secondKey);
		Assert.assertFalse("the downstream owner must retain an executable row",
			downstreamFacts.isEmpty());
		Assert.assertTrue("the downstream owner must consume the committed ROW domain",
			downstreamFacts.stream().allMatch(fact -> fact.key().orderedInputs()
				.equals(List.of(CandidateInputState.present(FType.ROW)))));
		Assert.assertEquals("the validated static edge index is shared across the owner commit",
			1, edges.completedTraversals());
	}

	@Test
	public void changedPoolWithoutIntermediateCommitUsesCurrentAuthority() throws Exception {
		PhysicalClosureFixture fixture = new PhysicalClosureFixture(true);
		ReplayState settled = fixture.settle(fixture.initial(fixture.pool("pool-a", "worker-a")));
		List<CandidateRuleFact> priorFirstFacts = fixture.ownedFacts(settled, fixture.firstKey);
		ReplayState changedSource = fixture.withSourcePool(settled,
			fixture.pool("pool-c", "worker-c"));
		CountingEdgeList edges = new CountingEdgeList(fixture.edges);

		ReplayState changed = fixture.close(changedSource, edges);
		Assert.assertEquals("a same-domain source pool change does not commit the intermediate",
			priorFirstFacts, fixture.ownedFacts(changed, fixture.firstKey));
		Assert.assertEquals("only the downstream owner constructs the lazy proof inventory",
			1, edges.completedTraversals());
		List<CandidateEmissionFact> downstreamDerived = fixture.derivedMaterializations(
			changed, fixture.secondKey);
		Assert.assertFalse("the changed chain must retain an exact downstream materialization",
			downstreamDerived.isEmpty());
		Assert.assertTrue("every downstream action must use the current pool",
			downstreamDerived.stream().allMatch(emission -> emission.derivedFoutAction()
				.durableAnchor().placementId().equals("pool-c")));
	}

	private static final class Fixture {
		private final String fingerprint;
		private final ControlRegionKey region;
		private final CompiledHopKey source;
		private final CompiledHopKey owner;
		private final CandidateRuleKey sourceRule;
		private final CandidateRuleKey ownerRule;
		private final ValueVersionKey ownerVersion;
		private final Node rawNode;
		private final CandidateRuleFact rawFact;
		private final Node sourceNodeTemplate;
		private final CandidateRuleFact sourceFact;
		private final List<CompiledInputEdgeFact> edges;
		private final Map<CompiledHopKey,Hop> origins;
		private final Map<Hop,NodeShapeFact> shapes;
		private final NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();

		private Fixture(String context) throws Exception {
			this(context, List.of(FED_FULL_LOCAL));
		}

		private Fixture(String context, List<PlacementState> rawStates) throws Exception {
			fingerprint = "physical-generation-envelope-" + context;
			region = new ControlRegionKey(fingerprint, "main", List.of("body"), "main", context);
			source = key("source", context);
			owner = key("owner", context);
			sourceRule = new CandidateRuleKey(source, List.of());
			ownerRule = new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL)));
			ValueVersionKey sourceValue = version("source", 0);
			ownerVersion = version("owner", 1);
			sourceNodeTemplate = new Node(source, NodeKind.OPERATION, sourceValue, true,
				List.of(new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false)),
				List.of(), List.of());
			rawNode = new Node(owner, NodeKind.OPERATION, ownerVersion, true, rawStates, List.of(), List.of());
			sourceFact = fact(sourceRule, new CandidateEmissionFact(
				new PlacementEmissionState(sourceNodeTemplate.legalAlternatives().get(0), false), FType.FULL));
			rawFact = fact(ownerRule, new CandidateEmissionFact(
				new PlacementEmissionState(FED_FULL_LOCAL, false), FType.FULL));
			edges = List.of(new CompiledInputEdgeFact(source, owner, 0));
			DataOp sourceHop = new DataOp("source", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "source", 4, 2, 8, 1000);
			Hop ownerHop = new UnaryOp("owner", DataType.MATRIX, ValueType.FP64, OpOp1.LOG, sourceHop);
			origins = Map.of(source, sourceHop, owner, ownerHop);
			// A 4x4 output on the source's 4x2 FULL pool is an exact BROADCAST materialization.
			shapes = Map.of(sourceHop, new NodeShapeFact(DataType.MATRIX, 4, 2),
				ownerHop, new NodeShapeFact(DataType.MATRIX, 4, 4));
			installContext(builder, Map.of(owner, 1L, source, 1L));
		}

		private DurableAnchorKey pool(String id, String endpoint) {
			return new DurableAnchorKey(id, FType.FULL,
				List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
		}

		private DurableAnchorKey outputPool(String id, String endpoint) {
			return new DurableAnchorKey(id, FType.BROADCAST,
				List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 4L))));
		}

		private Envelope normalize(DurableAnchorKey... pools) throws Exception {
			return invokeEnvelope(builder, "normalizePhysicalGenerationEnvelope", rawBuild(),
				proofNodes(pools), proofFacts(), edges, origins, shapes);
		}

		private Envelope materialize(DurableAnchorKey... pools) throws Exception {
			return invokeMaterializer(List.of(rawNode), List.of(rawFact), proofNodes(pools),
				proofFacts(), edges, origins, shapes);
		}

		private List<CandidateRuleFact> proofFacts() {
			DurableAnchorKey staleAnchor = pool("stale-pool", "stale-worker");
			DerivedFoutMaterializationActionKey staleAction = new DerivedFoutMaterializationActionKey(
				owner, ownerVersion, ownerRule, FED_FULL_LOCAL, FED_BROADCAST, staleAnchor,
				source, FType.FULL, FType.BROADCAST, owner.controlRegion().normalizedSignature());
			CandidateEmissionFact staleDerived = new CandidateEmissionFact(
				new PlacementEmissionState(FED_BROADCAST, true), FType.FULL, staleAction);
			return List.of(sourceFact, fact(ownerRule,
				List.of(rawFact.allowedEmissionFacts().get(0), staleDerived)));
		}

		private List<Node> proofNodes(DurableAnchorKey... pools) {
			Node currentSource = new Node(sourceNodeTemplate.key(), sourceNodeTemplate.kind(),
				sourceNodeTemplate.valueVersion(), true, sourceNodeTemplate.legalAlternatives(),
				List.of(), List.of(pools));
			return List.of(currentSource, rawNode);
		}

		private Object rawBuild() throws Exception {
			Class<?> type = nested("CandidateBase");
			Constructor<?> constructor = type.getDeclaredConstructor(Node.class, List.class, List.class);
			constructor.setAccessible(true);
			return constructor.newInstance(rawNode, List.of(ownerRule), List.of(rawFact));
		}

		private CompiledHopKey key(String name, String context) {
			return new CompiledHopKey(fingerprint, "main", "root", context, region, name, name);
		}

		private ValueVersionKey version(String name, int ordinal) {
			return new ValueVersionKey(fingerprint, name, region, ordinal, VersionKind.ORDINARY, List.of());
		}
	}

	private static final class PhysicalClosureFixture {
		private final String fingerprint;
		private final ControlRegionKey region;
		private final StatementBlock block = new StatementBlock();
		private final DataOp sourceHop;
		private final AggUnaryOp firstHop;
		private final AggUnaryOp secondHop;
		private final CompiledHopKey sourceKey;
		private final CompiledHopKey firstKey;
		private final CompiledHopKey secondKey;
		private final CandidateRuleKey sourceRule;
		private final CandidateRuleKey firstRule;
		private final CandidateRuleKey secondRule;
		private final List<PlacementGraphFingerprint.HopOccurrence> occurrences;
		private final List<CompiledInputEdgeFact> edges;
		private final Map<Hop,NodeShapeFact> shapes;
		private final Map<Hop,PlacementAnalysis.AbstractShapeFact> abstractShapes;
		private final Map<CompiledHopKey,Hop> origins;
		private final Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock;
		private final SinglePartitionFacts singlePartitions;
		private final Object cfg;
		private final NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();

		private PhysicalClosureFixture(boolean chain) throws Exception {
			fingerprint = "physical-proof-inventory-" + (chain ? "chain" : "siblings");
			region = new ControlRegionKey(fingerprint, "main", List.of("body"), "main", "compiled");
			sourceHop = new DataOp("source", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "source", 4, 2, 8, 1000);
			firstHop = new AggUnaryOp("first", DataType.MATRIX, ValueType.FP64,
				AggOp.SUM, Direction.Row, sourceHop);
			secondHop = new AggUnaryOp("second", DataType.MATRIX, ValueType.FP64,
				AggOp.SUM, Direction.Row, chain ? firstHop : sourceHop);
			block.setHops(new ArrayList<>(List.of(firstHop, secondHop)));
			sourceKey = key("source");
			firstKey = key("first");
			secondKey = key("second");
			sourceRule = new CandidateRuleKey(sourceKey, List.of());
			firstRule = new CandidateRuleKey(firstKey, List.of(CandidateInputState.present(FType.FULL)));
			secondRule = new CandidateRuleKey(secondKey, List.of(CandidateInputState.present(FType.FULL)));
			occurrences = List.of(occurrence(sourceHop, "source"), occurrence(firstHop, "first"),
				occurrence(secondHop, "second"));
			edges = List.of(new CompiledInputEdgeFact(sourceKey, firstKey, 0),
				new CompiledInputEdgeFact(chain ? firstKey : sourceKey, secondKey, 0));
			shapes = Map.of(sourceHop, new NodeShapeFact(DataType.MATRIX, 4, 2),
				firstHop, new NodeShapeFact(DataType.MATRIX, 4, 1),
				secondHop, new NodeShapeFact(DataType.MATRIX, 4, 1));
			abstractShapes = Map.of(sourceHop, PlacementAnalysis.AbstractShapeFact.fromConcrete(shapes.get(sourceHop)),
				firstHop, PlacementAnalysis.AbstractShapeFact.fromConcrete(shapes.get(firstHop)),
				secondHop, PlacementAnalysis.AbstractShapeFact.fromConcrete(shapes.get(secondHop)));
			Map<CompiledHopKey,Hop> exactOrigins = new IdentityHashMap<>();
			exactOrigins.put(sourceKey, sourceHop);
			exactOrigins.put(firstKey, firstHop);
			exactOrigins.put(secondKey, secondHop);
			origins = exactOrigins;
			Map<Hop,Integer> blockOrdinals = new IdentityHashMap<>();
			blockOrdinals.put(sourceHop, 0);
			blockOrdinals.put(firstHop, 1);
			blockOrdinals.put(secondHop, 2);
			ordinalsByBlock = Map.of(block, blockOrdinals);
			singlePartitions = new SinglePartitionFacts(
				List.of(sourceHop, firstHop, secondHop), Map.of(), Set.of());
			cfg = cfg(occurrences.size());
			installContext(builder, Map.of(sourceKey, 1L, firstKey, 1L, secondKey, 1L));
			installPrivacy(builder, sourceKey, firstKey, secondKey, chain);
		}

		private ReplayState initial(DurableAnchorKey pool) {
			PlacementState sourceState = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, pool.fType(), false);
			Node source = new Node(sourceKey, NodeKind.OPERATION, version("source", 0), true,
				List.of(sourceState), List.of(), List.of(pool));
			Node first = rawOwner(firstKey, "first", 1);
			Node second = rawOwner(secondKey, "second", 2);
			PlacementEmissionState sourceEmissionState = new PlacementEmissionState(sourceState, false);
			CandidateEmissionFact sourceEmission = new CandidateEmissionFact(sourceEmissionState, pool.fType(),
				null, List.of(CandidateEmissionRealization.durable(
					sourceEmissionState, pool, List.of(), List.of())));
			PlacementState nativeOutput = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
			CandidateEmissionFact rawEmission = new CandidateEmissionFact(
				new PlacementEmissionState(nativeOutput, false), FType.FULL);
			return new ReplayState(List.of(source, first, second),
				List.of(sourceRule, firstRule, secondRule),
				List.of(fact(sourceRule, sourceEmission), fact(firstRule, rawEmission),
					fact(secondRule, rawEmission)), List.of(), List.of(0));
		}

		private ReplayState withSourcePool(ReplayState settled, DurableAnchorKey pool) {
			ReplayState fresh = initial(pool);
			List<Node> nodes = new ArrayList<>(settled.nodes());
			nodes.set(0, fresh.nodes().get(0));
			List<CandidateRuleFact> facts = new ArrayList<>(settled.facts());
			for(int slot = 0; slot < facts.size(); slot++)
				if(facts.get(slot).key().parentOccurrence() == sourceKey)
					facts.set(slot, fresh.facts().get(0));
			return new ReplayState(List.copyOf(nodes), settled.domainKeys(), List.copyOf(facts),
				settled.logicalInputs(), List.of(0));
		}

		private ReplayState settle(ReplayState initial) throws Exception {
			ReplayState current = initial;
			for(int pass = 0; pass < 6; pass++) {
				ReplayState next = close(current, edges);
				if(next.envelope().equals(current.envelope()))
					return next;
				current = next;
			}
			throw new AssertionError("physical fixture did not settle");
		}

		private ReplayState close(ReplayState replay, List<CompiledInputEdgeFact> inputEdges) throws Exception {
			Method method = PlacementRelationClosure.class.getDeclaredMethod(
				"closePhysicalDependenciesMeasured", List.class, nested("ClosureUpdate"),
				Map.class, Map.class, SinglePartitionFacts.class, Map.class, nested("CfgAnalysis"),
				List.class, Map.class, Map.class);
			method.setAccessible(true);
			Object closed = method.invoke(PlacementBuilderTestAccess.relationClosure(builder),
				occurrences, candidateReplay(replay), shapes,
				abstractShapes, singlePartitions, ordinalsByBlock, cfg, inputEdges, origins, shapes);
			return replay(closed);
		}

		private List<CandidateRuleFact> ownedFacts(ReplayState replay, CompiledHopKey owner) {
			return replay.facts().stream()
				.filter(fact -> fact.key().parentOccurrence() == owner).toList();
		}

		private List<CandidateEmissionFact> derivedMaterializations(
			ReplayState replay, CompiledHopKey owner) {
			return ownedFacts(replay, owner).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.filter(emission -> emission.derivedFoutAction() != null).toList();
		}

		private Object candidateReplay(ReplayState replay) throws Exception {
			Constructor<?> constructor = nested("ClosureUpdate").getDeclaredConstructor(
				List.class, List.class, List.class, List.class, List.class);
			constructor.setAccessible(true);
			return constructor.newInstance(replay.nodes(), replay.domainKeys(), replay.facts(),
				replay.logicalInputs(), replay.changedOrdinals());
		}

		private Node rawOwner(CompiledHopKey key, String name, int ordinal) {
			PlacementState nativeOutput = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
			return new Node(key, NodeKind.OPERATION, version(name, ordinal), true,
				List.of(nativeOutput), List.of(), List.of());
		}

		private DurableAnchorKey pool(String id, String endpoint) {
			return new DurableAnchorKey(id, FType.FULL,
				List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
		}

		private DurableAnchorKey rowPool(String id, String firstEndpoint, String secondEndpoint) {
			return new DurableAnchorKey(id, FType.ROW, List.of(
				new AnchorPartition(firstEndpoint, List.of(0L, 0L), List.of(2L, 2L)),
				new AnchorPartition(secondEndpoint, List.of(2L, 0L), List.of(4L, 2L))));
		}

		private PlacementGraphFingerprint.HopOccurrence occurrence(Hop hop, String name) {
			return new PlacementGraphFingerprint.HopOccurrence(hop, "main/" + name, "main",
				block, List.of("body"), "main", false);
		}

		private CompiledHopKey key(String name) {
			return new CompiledHopKey(fingerprint, "main", "root", "compiled", region, name, name);
		}

		private ValueVersionKey version(String name, int ordinal) {
			return new ValueVersionKey(fingerprint, name, region, ordinal,
				VersionKind.ORDINARY, List.of());
		}
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, CandidateEmissionFact emission) {
		return fact(key, List.of(emission));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.LOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), emissions, "");
	}

	private static void installContext(NeutralPlacementGraphBuilder builder,
		Map<CompiledHopKey,Long> scopes) throws Exception {
		Class<?> type = nested("PhysicalGenerationContext");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class, Map.class);
		constructor.setAccessible(true);
		Field field = PlacementRelationClosure.class.getDeclaredField("physicalGenerationContext");
		field.setAccessible(true);
		field.set(PlacementBuilderTestAccess.relationClosure(builder),
			constructor.newInstance(List.<Constraint>of(), scopes));
	}

	private static void installPrivacy(NeutralPlacementGraphBuilder builder, CompiledHopKey source,
		CompiledHopKey first, CompiledHopKey second, boolean chain) throws Exception {
		Map<CompiledHopKey,Privacy> effective = new IdentityHashMap<>();
		effective.put(source, Privacy.PRIVATE_AGGREGATE);
		effective.put(first, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		effective.put(second, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> protectedInputs = new IdentityHashMap<>();
		protectedInputs.put(first, Map.of(0, source));
		if(!chain)
			protectedInputs.put(second, Map.of(0, source));
		Class<?> type = nested("StaticPrivacyProjection");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		Field field = PlacementRelationClosure.class.getDeclaredField("staticPrivacyProjection");
		field.setAccessible(true);
		field.set(PlacementBuilderTestAccess.relationClosure(builder),
			constructor.newInstance(effective, protectedInputs));
	}

	private static Object cfg(int size) throws Exception {
		Class<?> type = nested("CfgAnalysis");
		Constructor<?> constructor = type.getDeclaredConstructor(
			List.class, List.class, List.class, List.class, List.class, Map.class);
		constructor.setAccessible(true);
		List<Integer> definitions = java.util.Collections.nCopies(size, 0);
		List<VersionKind> kinds = java.util.Collections.nCopies(size, VersionKind.ORDINARY);
		List<Set<Integer>> reaching = java.util.Collections.nCopies(size, Set.of());
		List<Set<Object>> functionOutputs = java.util.Collections.nCopies(size, Set.of());
		List<Boolean> functionInputs = java.util.Collections.nCopies(size, false);
		return constructor.newInstance(definitions, kinds, reaching, functionOutputs,
			functionInputs, Map.of());
	}

	private static Envelope invokeEnvelope(NeutralPlacementGraphBuilder builder, String name,
		Object raw, List<Node> proofNodes, List<CandidateRuleFact> proofFacts,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		Class<?> inventoryType = nested("CommittedProofInventory");
		Constructor<?> constructor = inventoryType.getDeclaredConstructor(List.class, List.class,
			List.class, List.class, Collection.class, Map.class, Map.class);
		constructor.setAccessible(true);
		Object inventory = constructor.newInstance(proofNodes, proofFacts, edges,
			List.<LogicalTransientInputFact>of(), List.<Constraint>of(), origins, shapes);
		Method method = PlacementRelationClosure.class.getDeclaredMethod(name,
			nested("CandidateBase"), inventoryType);
		method.setAccessible(true);
		Object result = method.invoke(PlacementBuilderTestAccess.relationClosure(builder), raw, inventory);
		return envelope(result);
	}

	private static Envelope invokeMaterializer(List<Node> nodes, List<CandidateRuleFact> facts,
		List<Node> proofNodes, List<CandidateRuleFact> proofFacts, List<CompiledInputEdgeFact> edges,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class, List.class, List.class,
			List.class, List.class, Collection.class, Map.class, Map.class);
		method.setAccessible(true);
		Object result = method.invoke(null, nodes, facts, proofNodes, proofFacts, edges,
			List.<LogicalTransientInputFact>of(), List.<Constraint>of(), origins, shapes);
		return envelope(result);
	}

	@SuppressWarnings("unchecked")
	private static ReplayState replay(Object record) throws Exception {
		Method nodes = record.getClass().getDeclaredMethod("nodes");
		Method keys = record.getClass().getDeclaredMethod("domainKeys");
		Method facts = record.getClass().getDeclaredMethod("facts");
		Method logical = record.getClass().getDeclaredMethod("logicalInputs");
		Method changed = record.getClass().getDeclaredMethod("changedOrdinals");
		for(Method accessor : List.of(nodes, keys, facts, logical, changed))
			accessor.setAccessible(true);
		return new ReplayState((List<Node>)nodes.invoke(record),
			(List<CandidateRuleKey>)keys.invoke(record),
			(List<CandidateRuleFact>)facts.invoke(record),
			(List<LogicalTransientInputFact>)logical.invoke(record),
			(List<Integer>)changed.invoke(record));
	}

	@SuppressWarnings("unchecked")
	private static Envelope envelope(Object record) throws Exception {
		Method nodes;
		boolean singularNode;
		try {
			nodes = record.getClass().getDeclaredMethod("nodes");
			singularNode = false;
		}
		catch(NoSuchMethodException ignored) {
			nodes = record.getClass().getDeclaredMethod("node");
			singularNode = true;
		}
		nodes.setAccessible(true);
		Method facts;
		try {
			facts = record.getClass().getDeclaredMethod("facts");
		}
		catch(NoSuchMethodException ignored) {
			facts = record.getClass().getDeclaredMethod("ruleFacts");
		}
		facts.setAccessible(true);
		List<Node> outputNodes = singularNode ? List.of((Node)nodes.invoke(record))
			: (List<Node>)nodes.invoke(record);
		return new Envelope(outputNodes, (List<CandidateRuleFact>)facts.invoke(record));
	}

	private static Class<?> nested(String simpleName) {
		Class<?> owner = simpleName.equals("CfgAnalysis")
			? PlacementProgramFacts.class : PlacementRelationClosure.class;
		for(Class<?> type : owner.getDeclaredClasses())
			if(type.getSimpleName().equals(simpleName))
				return type;
		throw new AssertionError("missing nested seam " + simpleName);
	}

	private record ReplayState(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logicalInputs,
		List<Integer> changedOrdinals) {
		private ReplayEnvelope envelope() {
			return new ReplayEnvelope(nodes, domainKeys, facts, logicalInputs);
		}
	}

	private record ReplayEnvelope(List<Node> nodes, List<CandidateRuleKey> domainKeys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logicalInputs) { }

	private static final class CountingEdgeList extends AbstractList<CompiledInputEdgeFact> {
		private final List<CompiledInputEdgeFact> edges;
		private int completedTraversals;

		private CountingEdgeList(List<CompiledInputEdgeFact> edges) {
			this.edges = List.copyOf(edges);
		}

		@Override
		public CompiledInputEdgeFact get(int index) {
			return edges.get(index);
		}

		@Override
		public int size() {
			return edges.size();
		}

		@Override
		public Iterator<CompiledInputEdgeFact> iterator() {
			Iterator<CompiledInputEdgeFact> delegate = edges.iterator();
			return new Iterator<>() {
				private boolean completed;

				@Override
				public boolean hasNext() {
					boolean next = delegate.hasNext();
					if(!next && !completed) {
						completed = true;
						completedTraversals++;
					}
					return next;
				}

				@Override
				public CompiledInputEdgeFact next() {
					return delegate.next();
				}
			};
		}

		private int completedTraversals() {
			return completedTraversals;
		}
	}

	private record Envelope(List<Node> nodes, List<CandidateRuleFact> facts) {
		private boolean hasAnyDerivedFout() {
			return facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().derivedFedFout());
		}

		private boolean hasDerivedBroadcast() {
			return facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().derivedFedFout()
					&& emission.emissionState().placementState().fType() == FType.BROADCAST);
		}

		private CandidateEmissionFact derived() {
			return facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.filter(emission -> emission.emissionState().derivedFedFout()).findFirst().orElseThrow();
		}
	}
}

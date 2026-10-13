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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** A no-change frontier certificate must not survive an intervening authority commit. */
public class PhysicalNoOpFrontierInvalidationTest {
	@Test
	public void interveningUpstreamCommitReopensAnAlreadySettledFanInConsumer() throws Exception {
		Fixture referenceFixture = new Fixture();
		State referenceSettled = referenceFixture.settle(referenceFixture.initial(referenceFixture.pool("pool-a")));
		State expected = referenceFixture.close(referenceFixture.changeSecondSource(
			referenceSettled, referenceFixture.rowPool("pool-c"), List.of(1)));
		Assert.assertNotEquals("the second source change must commit the intermediate owner",
			referenceFixture.ownerAuthority(referenceSettled, referenceFixture.intermediateKey),
			referenceFixture.ownerAuthority(expected, referenceFixture.intermediateKey));
		Assert.assertNotEquals("the intermediate commit must refine the fan-in input domain",
			referenceFixture.ownerKeys(referenceSettled, referenceFixture.consumerKey),
			referenceFixture.ownerKeys(expected, referenceFixture.consumerKey));

		Fixture frontierFixture = new Fixture();
		State frontierSettled = frontierFixture.settle(frontierFixture.initial(frontierFixture.pool("pool-a")));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		State actual;
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			// A is visited first and settles C. S then changes B, whose commit must
			// invalidate C's certificate before the B -> C edge is consumed.
			actual = frontierFixture.close(frontierFixture.changeSecondSource(
				frontierSettled, frontierFixture.rowPool("pool-c"), List.of(0, 1)));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

		Assert.assertEquals(referenceFixture.signature(expected), frontierFixture.signature(actual));
		List<String> expectedAuthority = referenceFixture.ownerAuthority(expected, referenceFixture.consumerKey);
		List<String> actualAuthority = frontierFixture.ownerAuthority(actual, frontierFixture.consumerKey);
		Assert.assertFalse("the changed upstream source must reach the final consumer", actualAuthority.isEmpty());
		Assert.assertEquals("the reopened consumer must own the exact current source/proof authority",
			expectedAuthority, actualAuthority);
		Assert.assertTrue("the intervening owner commit must contain the replacement pool",
			frontierFixture.ownerAuthority(actual, frontierFixture.secondKey).stream()
				.anyMatch(value -> value.contains("pool-c")));
		Assert.assertTrue("the fixture must regenerate candidate facts after the intervening commit",
			metrics.objectCreationSnapshot().candidateRuleFacts() > 0);
	}

	private static final class Fixture {
		private static final String FINGERPRINT = "physical-noop-frontier-invalidation";
		private final ControlRegionKey region = new ControlRegionKey(
			FINGERPRINT, "main", List.of("body"), "main", "compiled");
		private final StatementBlock block = new StatementBlock();
		private final DataOp firstSource = new DataOp("a", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "a", 4, 2, 8, 1000);
		private final DataOp secondSource = new DataOp("s", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "s", 4, 2, 8, 1000);
		private final AggUnaryOp intermediate = new AggUnaryOp("b", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, secondSource);
		private final BinaryOp consumer = new BinaryOp("c", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, firstSource, intermediate);
		private final CompiledHopKey firstKey = key("a");
		private final CompiledHopKey secondKey = key("s");
		private final CompiledHopKey intermediateKey = key("b");
		private final CompiledHopKey consumerKey = key("c");
		private final CandidateRuleKey firstRule = new CandidateRuleKey(firstKey, List.of());
		private final CandidateRuleKey secondRule = new CandidateRuleKey(secondKey, List.of());
		private final CandidateRuleKey intermediateRule = new CandidateRuleKey(intermediateKey,
			List.of(CandidateInputState.present(FType.FULL)));
		private final List<PlacementGraphFingerprint.HopOccurrence> occurrences;
		private final Map<Hop,NodeShapeFact> shapes;
		private final Map<Hop,AbstractShapeFact> abstractShapes;
		private final Map<CompiledHopKey,Hop> origins;
		private final Map<StatementBlock,Map<Hop,Integer>> ordinalsByBlock;
		private final SinglePartitionFacts singlePartitions;
		private final Object cfg;
		private final NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();

		private Fixture() throws Exception {
			block.setHops(new ArrayList<>(List.of(firstSource, secondSource, intermediate, consumer)));
			occurrences = List.of(occurrence(firstSource, "a"), occurrence(secondSource, "s"),
				occurrence(intermediate, "b"), occurrence(consumer, "c"));
			shapes = Map.of(firstSource, shape(4, 2), secondSource, shape(4, 2),
				intermediate, shape(4, 1), consumer, shape(4, 2));
			Map<Hop,AbstractShapeFact> abstracts = new IdentityHashMap<>();
			shapes.forEach((hop, shape) -> abstracts.put(hop, AbstractShapeFact.fromConcrete(shape)));
			abstractShapes = abstracts;
			Map<CompiledHopKey,Hop> exactOrigins = new IdentityHashMap<>();
			exactOrigins.put(firstKey, firstSource);
			exactOrigins.put(secondKey, secondSource);
			exactOrigins.put(intermediateKey, intermediate);
			exactOrigins.put(consumerKey, consumer);
			origins = exactOrigins;
			Map<Hop,Integer> ordinals = new IdentityHashMap<>();
			ordinals.put(firstSource, 0);
			ordinals.put(secondSource, 1);
			ordinals.put(intermediate, 2);
			ordinals.put(consumer, 3);
			ordinalsByBlock = Map.of(block, ordinals);
			singlePartitions = new SinglePartitionFacts(
				List.of(firstSource, secondSource, intermediate, consumer), Map.of(), Set.of());
			cfg = cfg(4);
			installContext(builder, Map.of(firstKey, 1L, secondKey, 1L,
				intermediateKey, 1L, consumerKey, 1L));
			installPrivacy(builder, firstKey, secondKey, intermediateKey, consumerKey);
			installAuthority(builder, origins, shapes);
		}

		private State initial(DurableAnchorKey pool) {
			PlacementState sourceState = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
			Node first = source(firstKey, "a", 0, sourceState, pool);
			Node second = source(secondKey, "s", 1, sourceState, pool);
			Node b = raw(intermediateKey, "b", 2);
			Node c = new Node(consumerKey, NodeKind.OPERATION, version("c", 3), false,
				List.of(), List.of(), List.of());
			CandidateEmissionFact sourceEmission = durableEmission(sourceState, pool);
			CandidateEmissionFact rawEmission = new CandidateEmissionFact(
				new PlacementEmissionState(sourceState, false), FType.FULL);
			return new State(List.of(first, second, b, c),
				List.of(firstRule, secondRule, intermediateRule),
				List.of(fact(firstRule, sourceEmission), fact(secondRule, sourceEmission),
					fact(intermediateRule, rawEmission)),
				List.of(), List.of(0, 1));
		}

		private State changeSecondSource(State settled, DurableAnchorKey pool, List<Integer> dirty) {
			PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, pool.fType(), false);
			List<Node> nodes = new ArrayList<>(settled.nodes);
			nodes.set(1, source(secondKey, "s", 1, state, pool));
			List<CandidateRuleFact> facts = new ArrayList<>(settled.facts);
			for(int slot = 0; slot < facts.size(); slot++)
				if(facts.get(slot).key().parentOccurrence() == secondKey)
					facts.set(slot, fact(secondRule, durableEmission(state, pool)));
			return new State(List.copyOf(nodes), settled.keys, List.copyOf(facts), settled.logical, dirty);
		}

		private State settle(State initial) throws Exception {
			State current = initial;
			for(int pass = 0; pass < 8; pass++) {
				State next = close(current);
				if(signature(next).equals(signature(current)))
					return next;
				current = next;
			}
			throw new AssertionError("physical invalidation fixture did not settle");
		}

		private State close(State replay) throws Exception {
			Method method = PlacementRelationClosure.class.getDeclaredMethod(
				"closePhysicalDependenciesMeasured", List.class, nested("ClosureUpdate"),
				Map.class, Map.class, SinglePartitionFacts.class, Map.class, nested("CfgAnalysis"),
				List.class, Map.class, Map.class);
			method.setAccessible(true);
			Object closed = method.invoke(PlacementBuilderTestAccess.relationClosure(builder),
				occurrences, closureUpdate(replay), shapes, abstractShapes, singlePartitions,
				ordinalsByBlock, cfg, null, origins, shapes);
			return state(closed);
		}

		private List<String> ownerAuthority(State state, CompiledHopKey owner) {
			List<String> result = new ArrayList<>();
			for(CandidateRuleFact fact : state.facts)
				if(fact.key().parentOccurrence() == owner)
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
						for(CandidateEmissionRealization realization : emission.realizations())
							result.add(realization.normalizedSignature());
			return List.copyOf(result);
		}

		private List<CandidateRuleKey> ownerKeys(State state, CompiledHopKey owner) {
			return state.keys.stream().filter(key -> key.parentOccurrence() == owner).toList();
		}

		private List<String> signature(State state) {
			return List.of(state.nodes.toString(), state.keys.toString(), state.facts.toString());
		}

		private DurableAnchorKey pool(String id) {
			return new DurableAnchorKey(id, FType.FULL,
				List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
		}

		private DurableAnchorKey rowPool(String id) {
			return new DurableAnchorKey(id, FType.ROW, List.of(
				new AnchorPartition("worker-0", List.of(0L, 0L), List.of(2L, 2L)),
				new AnchorPartition("worker-1", List.of(2L, 0L), List.of(4L, 2L))));
		}

		private CompiledHopKey key(String name) {
			return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", region, name, name);
		}

		private PlacementGraphFingerprint.HopOccurrence occurrence(Hop hop, String name) {
			return new PlacementGraphFingerprint.HopOccurrence(hop, "main/" + name, "main",
				block, List.of("body"), "main", false);
		}
	}

	private static NodeShapeFact shape(long rows, long cols) {
		return new NodeShapeFact(DataType.MATRIX, rows, cols);
	}

	private static Node source(CompiledHopKey key, String name, int ordinal,
		PlacementState state, DurableAnchorKey pool) {
		return new Node(key, NodeKind.OPERATION, version(name, ordinal), true,
			List.of(state), List.of(), List.of(pool));
	}

	private static Node raw(CompiledHopKey key, String name, int ordinal) {
		PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
		return new Node(key, NodeKind.OPERATION, version(name, ordinal), true,
			List.of(state), List.of(), List.of());
	}

	private static CandidateEmissionFact durableEmission(PlacementState state, DurableAnchorKey pool) {
		PlacementEmissionState emission = new PlacementEmissionState(state, false);
		return new CandidateEmissionFact(emission, FType.FULL, null,
			List.of(CandidateEmissionRealization.durable(emission, pool, List.of(), List.of())));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, CandidateEmissionFact emission) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.LOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emission), "");
	}

	private static ValueVersionKey version(String name, int ordinal) {
		ControlRegionKey region = new ControlRegionKey(
			Fixture.FINGERPRINT, "main", List.of("body"), "main", "compiled");
		return new ValueVersionKey(Fixture.FINGERPRINT, name, region, ordinal,
			VersionKind.ORDINARY, List.of());
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

	private static void installPrivacy(NeutralPlacementGraphBuilder builder,
		CompiledHopKey first, CompiledHopKey second, CompiledHopKey intermediate,
		CompiledHopKey consumer) throws Exception {
		Map<CompiledHopKey,Privacy> effective = new IdentityHashMap<>();
		effective.put(first, Privacy.PRIVATE_AGGREGATE);
		effective.put(second, Privacy.PRIVATE_AGGREGATE);
		effective.put(intermediate, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		effective.put(consumer, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> protectedInputs = new IdentityHashMap<>();
		protectedInputs.put(intermediate, Map.of(0, second));
		protectedInputs.put(consumer, Map.of(0, first, 1, intermediate));
		Class<?> type = nested("StaticPrivacyProjection");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		Field field = PlacementRelationClosure.class.getDeclaredField("staticPrivacyProjection");
		field.setAccessible(true);
		field.set(PlacementBuilderTestAccess.relationClosure(builder),
			constructor.newInstance(effective, protectedInputs));
	}

	private static void installAuthority(NeutralPlacementGraphBuilder builder,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) throws Exception {
		PlacementRelationClosure closure = PlacementBuilderTestAccess.relationClosure(builder);
		Field originsField = PlacementRelationClosure.class.getDeclaredField("origins");
		originsField.setAccessible(true);
		originsField.set(closure, origins);
		Field shapesField = PlacementRelationClosure.class.getDeclaredField("shapeFactsByHop");
		shapesField.setAccessible(true);
		shapesField.set(closure, shapes);
	}

	private static Object cfg(int size) throws Exception {
		Class<?> type = nested("CfgAnalysis");
		Constructor<?> constructor = type.getDeclaredConstructor(
			List.class, List.class, List.class, List.class, List.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(java.util.Collections.nCopies(size, 0),
			java.util.Collections.nCopies(size, VersionKind.ORDINARY),
			java.util.Collections.nCopies(size, Set.of()),
			java.util.Collections.nCopies(size, Set.of()),
			java.util.Collections.nCopies(size, false), Map.of());
	}

	private static Object closureUpdate(State state) throws Exception {
		Constructor<?> constructor = nested("ClosureUpdate").getDeclaredConstructor(
			List.class, List.class, List.class, List.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(state.nodes, state.keys, state.facts, state.logical, state.changed);
	}

	@SuppressWarnings("unchecked")
	private static State state(Object record) throws Exception {
		List<Method> accessors = new ArrayList<>();
		for(String name : List.of("nodes", "domainKeys", "facts", "logicalInputs", "changedOrdinals")) {
			Method accessor = record.getClass().getDeclaredMethod(name);
			accessor.setAccessible(true);
			accessors.add(accessor);
		}
		return new State((List<Node>)accessors.get(0).invoke(record),
			(List<CandidateRuleKey>)accessors.get(1).invoke(record),
			(List<CandidateRuleFact>)accessors.get(2).invoke(record),
			(List<LogicalTransientInputFact>)accessors.get(3).invoke(record),
			(List<Integer>)accessors.get(4).invoke(record));
	}

	private static Class<?> nested(String name) {
		Class<?> owner = name.equals("CfgAnalysis") ? PlacementProgramFacts.class : PlacementRelationClosure.class;
		for(Class<?> type : owner.getDeclaredClasses())
			if(type.getSimpleName().equals(name))
				return type;
		throw new AssertionError("missing nested seam " + name);
	}

	private record State(List<Node> nodes, List<CandidateRuleKey> keys,
		List<CandidateRuleFact> facts, List<LogicalTransientInputFact> logical,
		List<Integer> changed) { }
}

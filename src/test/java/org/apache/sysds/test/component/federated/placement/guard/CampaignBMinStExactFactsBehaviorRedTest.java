/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Phase-B1a RED for immutable, owner-bound, pre-solve MinST cost facts. */
public class CampaignBMinStExactFactsBehaviorRedTest {
	private static final String PREFIX =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.";
	private static final String FACTS = PREFIX + "MinStExactCostFacts";
	private static final String PRODUCER = PREFIX + "MinStExactCostFactsProducer";
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;

	@Test
	public void exactFactsRejectCorruptionBeforeAnySelectionRepair() throws Exception {
		Class<?> factsType = boundary(FACTS);
		Class<?> producerType = boundary(PRODUCER);
		assertConstructionSurface(factsType, producerType);

		PlacementAnalysis owner = analysis("B-22");
		List<CompiledHopKey> scope = owner.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Object facts = derive(producerType, owner, scope);
		Assert.assertSame("MINST_FACT_OWNER_IDENTITY", owner, call(facts, "analysis"));
		Assert.assertEquals("MINST_FACT_FINGERPRINT", owner.analysisFingerprint(),
			call(facts, "analysisFingerprint"));
		List<?> actualScope = list(facts, "orderedScope");
		Assert.assertEquals(scope.size(), actualScope.size());
		for(int i = 0; i < scope.size(); i++)
			Assert.assertSame("MINST_FACT_SCOPE_KEY_IDENTITY|index=" + i, scope.get(i), actualScope.get(i));
		assertImmutable(actualScope, "MINST_FACT_SCOPE_MUTABLE");

		Assert.assertEquals("MINST_SOURCE_ID", SOURCE, ((Number)call(facts, "sourceNodeId")).longValue());
		Assert.assertEquals("MINST_SINK_ID", SINK, ((Number)call(facts, "sinkNodeId")).longValue());
		List<?> decisions = list(facts, "decisionFactsInScopeOrder");
		List<?> auxiliaries = list(facts, "auxiliaryNodesInCanonicalOrder");
		List<?> edges = list(facts, "directedEdgesInDerivationOrder");
		List<?> groups = list(facts, "orGroupsInCanonicalOrder");
		List<?> obligations = list(facts, "obligationFactsInCanonicalOrder");
		assertNodeAndEdgeFacts(scope, decisions, auxiliaries, edges);
		assertOrGroups(groups, edges);
		assertB22PreSolveLegality(decisions);
		assertTypedReasons(factsType);

		Constructor<?> carrier = soleNonPublicConstructor(factsType);
		Object[] valid = new Object[] {owner, owner.analysisFingerprint(), actualScope, decisions,
			auxiliaries, edges, groups, obligations, call(facts, "derivationFingerprint")};
		assertRejected(carrier, replace(valid, 2, reversed(actualScope)), "SCOPE_REORDERED");
		assertRejected(carrier, replace(valid, 2, duplicated(actualScope)), "SCOPE_DUPLICATE");
		PlacementAnalysis foreign = analysis("B-21");
		assertRejected(carrier, replace(valid, 0, foreign), "FOREIGN_OWNER");
		assertRejected(carrier, replace(valid, 5, mutateEdgeCapacity(edges)), "CAPACITY_SUM_MISMATCH");
		assertRejected(carrier, replace(valid, 8,
			String.valueOf(valid[8]) + "0"), "DERIVATION_FINGERPRINT_MISMATCH");
		if(!groups.isEmpty()) {
			assertRejected(carrier, replace(valid, 6, mutateRecordList(groups, "priceBits")),
				"OR_GROUP_PRICE_MISMATCH");
			assertRejected(carrier, replace(valid, 6, mutateRecordList(groups, "direction")),
				"OR_GROUP_DIRECTION_MISMATCH");
			assertRejected(carrier, replace(valid, 6, mutateRecordList(groups, "endpointsInCanonicalOrder")),
				"OR_GROUP_ENDPOINT_MISMATCH");
		}
	}

	private static void assertConstructionSurface(Class<?> facts, Class<?> producer) throws Exception {
		Assert.assertTrue(Modifier.isFinal(facts.getModifiers()));
		for(Constructor<?> constructor : facts.getDeclaredConstructors())
			Assert.assertFalse("MINST_FACT_PUBLIC_CANONICAL_CONSTRUCTOR", Modifier.isPublic(constructor.getModifiers()));
		Method derive = producer.getMethod("derive", PlacementAnalysis.class, List.class);
		Assert.assertTrue(Modifier.isStatic(derive.getModifiers()));
		Assert.assertEquals(facts, derive.getReturnType());
		for(Method method : facts.getMethods())
			Assert.assertFalse("MINST_FACT_PUBLIC_LITERAL_FACTORY|" + method,
				Modifier.isStatic(method.getModifiers()) && facts.equals(method.getReturnType()));
	}

	private static void assertNodeAndEdgeFacts(List<CompiledHopKey> scope, List<?> decisions,
		List<?> auxiliaries, List<?> edges) throws Exception {
		Assert.assertEquals("MINST_DECISION_SCOPE_CARDINALITY", scope.size(), decisions.size());
		Set<Long> nodeIds = new HashSet<>(List.of(SOURCE, SINK));
		for(int i = 0; i < decisions.size(); i++) {
			Object decision = decisions.get(i);
			Assert.assertSame(scope.get(i), call(decision, "key"));
			for(String accessor : List.of("computeNodeId", "placementNodeId")) {
				long id = ((Number)call(decision, accessor)).longValue();
				Assert.assertTrue("MINST_DECISION_ID_RESERVED|" + id, id >= 0);
				Assert.assertTrue("MINST_DECISION_ID_COLLISION|" + id, nodeIds.add(id));
			}
			List<?> legal = list(decision, "legalStatesInCanonicalOrder");
			Assert.assertFalse("MINST_EMPTY_LEGAL_STATE", legal.isEmpty());
			Assert.assertEquals("MINST_DUPLICATE_LEGAL_STATE", legal.size(), new HashSet<>(legal).size());
		}
		for(Object auxiliary : auxiliaries) {
			long id = ((Number)call(auxiliary, "nodeId")).longValue();
			Assert.assertTrue("MINST_AUX_ID_DOMAIN|" + id, id < SINK);
			Assert.assertTrue("MINST_AUX_ID_COLLISION|" + id, nodeIds.add(id));
		}
		Set<String> directed = new HashSet<>();
		for(Object edge : edges) {
			long from = ((Number)call(edge, "fromNodeId")).longValue();
			long to = ((Number)call(edge, "toNodeId")).longValue();
			Assert.assertTrue("MINST_EDGE_FROM_UNKNOWN|" + from, nodeIds.contains(from));
			Assert.assertTrue("MINST_EDGE_TO_UNKNOWN|" + to, nodeIds.contains(to));
			Assert.assertTrue("MINST_EDGE_DUPLICATE|" + from + "->" + to, directed.add(from + "->" + to));
			long capacityBits = ((Number)call(edge, "capacityBits")).longValue();
			assertCanonicalCost(capacityBits);
			double sum = 0.0;
			for(Object contribution : list(edge, "contributionsInDerivationOrder")) {
				long bits = ((Number)call(contribution, "costBits")).longValue();
				assertCanonicalCost(bits);
				sum += Double.longBitsToDouble(bits);
			}
			Assert.assertEquals("MINST_CAPACITY_CONTRIBUTION_SUM|" + from + "->" + to,
				capacityBits, Double.doubleToRawLongBits(sum));
		}
	}

	private static void assertOrGroups(List<?> groups, List<?> edges) throws Exception {
		for(Object group : groups) {
			long aux = ((Number)call(group, "auxiliaryNodeId")).longValue();
			String direction = String.valueOf(call(group, "direction"));
			long producerP = ((Number)call(group, "producerPlacementNodeId")).longValue();
			long priceBits = ((Number)call(group, "priceBits")).longValue();
			List<?> endpoints = list(group, "endpointsInCanonicalOrder");
			Assert.assertFalse("MINST_OR_EMPTY_ENDPOINTS", endpoints.isEmpty());
			double max = 0.0;
			for(Object endpoint : endpoints) {
				long demandBits = ((Number)call(endpoint, "demandCostBits")).longValue();
				assertCanonicalCost(demandBits);
				max = Math.max(max, Double.longBitsToDouble(demandBits));
				long consumerC = ((Number)call(endpoint, "consumerComputeNodeId")).longValue();
				assertEdge(edges, direction.equals("UPLOAD") ? consumerC : aux,
					direction.equals("UPLOAD") ? aux : consumerC);
			}
			Assert.assertEquals("MINST_OR_MAX_PRICE", priceBits, Double.doubleToRawLongBits(max));
			assertEdge(edges, direction.equals("UPLOAD") ? aux : producerP,
				direction.equals("UPLOAD") ? producerP : aux);
		}
	}

	private static void assertB22PreSolveLegality(List<?> decisions) throws Exception {
		List<?> yfed = decisions.stream().filter(value -> {
			try { return ((CompiledHopKey)call(value, "key")).normalizedSignature().contains(":Yfed"); }
			catch(Exception ex) { throw new RuntimeException(ex); }
		}).toList();
		Assert.assertEquals("MINST_B22_YFED_DECISION", 1, yfed.size());
		for(Object state : list(yfed.get(0), "legalStatesInCanonicalOrder")) {
			PlacementState placement = (PlacementState)state;
			Assert.assertFalse("RAW_STATE_RECEIPT_MISMATCH|B22_Yfed_FED_LOUT_MUST_BE_PRE_SOLVE_ILLEGAL",
				placement.execType().name().equals("FED") && placement.output().name().equals("LOUT"));
		}
	}

	private static void assertTypedReasons(Class<?> facts) throws Exception {
		Class<?> reason = nested(facts, "ValidationReason");
		Set<String> names = Arrays.stream(reason.getEnumConstants()).map(String::valueOf).collect(java.util.stream.Collectors.toSet());
		for(String required : List.of("FOREIGN_OWNER", "SCOPE_REORDERED", "SCOPE_DUPLICATE",
			"CAPACITY_SUM_MISMATCH", "DERIVATION_FINGERPRINT_MISMATCH", "OR_GROUP_ENDPOINT_MISMATCH",
			"OR_GROUP_DIRECTION_MISMATCH", "OR_GROUP_PRICE_MISMATCH", "RAW_STATE_RECEIPT_MISMATCH"))
			Assert.assertTrue("MINST_FACT_VALIDATION_REASON_MISSING|" + required, names.contains(required));
	}

	private static Object derive(Class<?> producer, PlacementAnalysis owner, List<CompiledHopKey> scope)
		throws Exception {
		return producer.getMethod("derive", PlacementAnalysis.class, List.class).invoke(null, owner, scope);
	}

	private static PlacementAnalysis analysis(String fixture) throws Exception {
		return new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
	}

	private static Class<?> boundary(String name) throws ClassNotFoundException {
		try { return Class.forName(name); }
		catch(ClassNotFoundException ex) {
			throw new AssertionError("MINST_EXACT_FACT_BEHAVIOR_MISSING|class=" + name, ex);
		}
	}

	private static Constructor<?> soleNonPublicConstructor(Class<?> type) {
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		Assert.assertEquals("MINST_FACT_CANONICAL_CONSTRUCTOR_COUNT", 1, constructors.length);
		Assert.assertFalse(Modifier.isPublic(constructors[0].getModifiers()));
		constructors[0].setAccessible(true);
		return constructors[0];
	}

	private static void assertRejected(Constructor<?> constructor, Object[] arguments, String reason)
		throws Exception {
		try {
			constructor.newInstance(arguments);
			Assert.fail("MINST_FACT_CORRUPTION_ACCEPTED|" + reason);
		}
		catch(InvocationTargetException ex) {
			Object actual = call(ex.getCause(), "reason");
			Assert.assertEquals("MINST_FACT_CORRUPTION_REASON", reason, String.valueOf(actual));
		}
	}

	private static List<?> mutateEdgeCapacity(List<?> edges) throws Exception {
		Assert.assertFalse("MINST_EDGE_FIXTURE_EMPTY", edges.isEmpty());
		List<Object> copy = new ArrayList<>(edges);
		Object edge = edges.get(0);
		copy.set(0, rebuildRecord(edge, "capacityBits",
			((Number)call(edge, "capacityBits")).longValue() ^ 1L));
		return List.copyOf(copy);
	}

	private static List<?> mutateRecordList(List<?> values, String component) throws Exception {
		List<Object> copy = new ArrayList<>(values);
		Object value = values.get(0);
		Object replacement;
		if(component.equals("priceBits"))
			replacement = ((Number)call(value, component)).longValue() ^ 1L;
		else if(component.equals("direction")) {
			Object current = call(value, component);
			Object[] constants = current.getClass().getEnumConstants();
			replacement = constants[0].equals(current) ? constants[1] : constants[0];
		}
		else
			replacement = reversed(list(value, component));
		copy.set(0, rebuildRecord(value, component, replacement));
		return List.copyOf(copy);
	}

	private static Object rebuildRecord(Object value, String changed, Object replacement) throws Exception {
		RecordComponent[] components = value.getClass().getRecordComponents();
		Assert.assertNotNull("MINST_FACT_NESTED_VALUE_MUST_BE_RECORD", components);
		Class<?>[] types = new Class<?>[components.length];
		Object[] arguments = new Object[components.length];
		for(int i = 0; i < components.length; i++) {
			types[i] = components[i].getType();
			arguments[i] = components[i].getName().equals(changed) ? replacement
				: components[i].getAccessor().invoke(value);
		}
		Constructor<?> constructor = value.getClass().getDeclaredConstructor(types);
		Assert.assertFalse("MINST_FACT_PUBLIC_NESTED_CANONICAL_CONSTRUCTOR",
			Modifier.isPublic(constructor.getModifiers()));
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static Object[] replace(Object[] values, int index, Object replacement) {
		Object[] copy = values.clone();
		copy[index] = replacement;
		return copy;
	}

	private static List<?> reversed(List<?> values) {
		List<Object> copy = new ArrayList<>(values);
		java.util.Collections.reverse(copy);
		return List.copyOf(copy);
	}

	private static List<?> duplicated(List<?> values) {
		List<Object> copy = new ArrayList<>(values);
		copy.add(values.get(0));
		return List.copyOf(copy);
	}

	private static void assertEdge(List<?> edges, long from, long to) throws Exception {
		for(Object edge : edges)
			if(((Number)call(edge, "fromNodeId")).longValue() == from
				&& ((Number)call(edge, "toNodeId")).longValue() == to)
				return;
		Assert.fail("MINST_OR_EDGE_MISSING|" + from + "->" + to);
	}

	private static void assertCanonicalCost(long bits) {
		double value = Double.longBitsToDouble(bits);
		Assert.assertTrue("MINST_NONFINITE_COST_BITS|" + bits, Double.isFinite(value));
		Assert.assertTrue("MINST_NEGATIVE_COST_BITS|" + bits, value >= 0.0);
		Assert.assertNotEquals("MINST_NEGATIVE_ZERO_COST_BITS", Double.doubleToRawLongBits(-0.0), bits);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values, String marker) {
		Assert.assertThrows(marker, UnsupportedOperationException.class, () -> ((List)values).add(new Object()));
	}

	private static List<?> list(Object owner, String accessor) throws Exception {
		return (List<?>)call(owner, accessor);
	}

	private static Object call(Object owner, String method) throws Exception {
		return owner.getClass().getMethod(method).invoke(owner);
	}

	private static Class<?> nested(Class<?> owner, String name) {
		return Arrays.stream(owner.getDeclaredClasses()).filter(type -> type.getSimpleName().equals(name))
			.findFirst().orElseThrow(() -> new AssertionError("MINST_FACT_NESTED_TYPE_MISSING|" + name));
	}
}

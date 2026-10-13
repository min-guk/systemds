/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeComponentSummaryObservationTest {
	@Test
	public void publicQueriesPartitionEveryCompletedLookupWithoutChangingProofs()
		throws Exception {
		synchronized(NativeComponentSummaryObservationTest.class) {
			String property = "sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";
			String prior = System.getProperty(property);
			try {
				System.setProperty(property, "1");
				Object fixture = fixture(FType.BROADCAST);
				DurableAnchorKey pool = pool("public-summary", FType.BROADCAST);
				Object seed = invokeNamed(fixture, "source", "summary-seed", pool);
				Object producer = invokeNamed(fixture, "unary",
					"summary-producer", OpOp1.LOG, seed, false);
				List<CandidateInputState> inputs =
					List.of(CandidateInputState.present(FType.BROADCAST));
				DurableAnchorKey[] equivalentPools = new DurableAnchorKey[8];
				for(int index = 0; index < equivalentPools.length; index++)
					equivalentPools[index] = new DurableAnchorKey("equivalent-summary-" + index,
						FType.BROADCAST, pool.partitions());
				invokeNamed(fixture, "samePoolRealizations", producer, inputs,
					(Object)equivalentPools);
				CandidateRuleFact producerFact =
					(CandidateRuleFact)invokeNamed(fixture, "fact", producer, inputs);
				List<CandidateRealizationSupportClause> clauses = producerFact
					.allowedEmissionFacts().get(0).realizations().stream()
					.map(realization -> new CandidateRealizationSupportClause(List.of(), List.of(
						CandidateRealizationInputBinding.direct(0,
							CandidateRealizationReference.of(producerFact.key(), realization)))))
					.toList();
				Object shared = invokeNamed(fixture, "unary",
					"summary-shared", OpOp1.ABS, producer, false);
				invokeNamed(fixture, "withClauses", shared, inputs, clauses);
				Object firstRoot = invokeNamed(fixture, "unary",
					"summary-first", OpOp1.EXP, shared, false);
				Object secondRoot = invokeNamed(fixture, "unary",
					"summary-second", OpOp1.SQRT, shared, false);
				CandidateRealizationReference firstReference =
					(CandidateRealizationReference)invokeNamed(fixture, "reference", firstRoot, inputs);
				CandidateRealizationReference secondReference =
					(CandidateRealizationReference)invokeNamed(fixture, "reference", secondRoot, inputs);

				NativePlacementContinuity cold = (NativePlacementContinuity)invokeNamed(
					fixture, "resolver", null, 0, 0L);
				NativePlacementContinuity.CandidateSupportResult coldFirst =
					cold.proveCandidateSupport(firstReference, pool);
				NativePlacementContinuity.CandidateSupportResult coldSecond =
					cold.proveCandidateSupport(secondReference, pool);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity observed = (NativePlacementContinuity)invokeNamed(
					fixture, "resolver", metrics, 8, 128L);
				NativePlacementContinuity.CandidateSupportResult actualFirst =
					observed.proveCandidateSupport(firstReference, pool);
				NativePlacementContinuity.CandidateSupportResult actualSecond =
					observed.proveCandidateSupport(secondReference, pool);
				assertParity(coldFirst, actualFirst);
				assertParity(coldSecond, actualSecond);
				assertCompletedLookupPartition(metrics);
				Assert.assertTrue("the second root must consume the retained duplicate-row summary",
					directWork(metrics, "COMPONENT_SUMMARY_REUSE_HITS") > 0);

				metrics.reset();
				NativePlacementContinuity.CandidateSupportResult replay =
					observed.proveCandidateSupport(secondReference, pool);
				assertParity(coldSecond, replay);
				Assert.assertEquals("completed-result reuse precedes component lookup after reset", 0,
					directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_REQUESTS"));
				Assert.assertEquals(0,
					directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_ABSENT"));
				Assert.assertEquals(0,
					directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_FIXED_OWNER_REJECT"));
				Assert.assertEquals(0,
					directWork(metrics, "COMPONENT_SUMMARY_REUSE_HITS"));
			}
			finally {
				restore(property, prior);
			}
		}
	}

	@Test
	public void privateLookupClassifiesAbsentAndFixedOwnerRejectWithoutClaimingCallerHits()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = empty(metrics);
		Object witness = witness(FType.FULL);
		CompiledHopKey owner = key("lookup-owner");
		CompiledHopKey foreign = key("lookup-foreign");
		Object state = state(owner, witness, 7);
		Method lookup = method(NativePlacementContinuity.class, "reusableAcyclicComponent",
			nested("CandidateProofState"), CompiledHopKey.class);
		Assert.assertNull(lookup.invoke(continuity, state, foreign));
		Object summary = summary(List.of(), identitySet(owner), 1L);
		cache(continuity, state, summary);
		Assert.assertSame(summary, lookup.invoke(continuity, state, foreign));
		Assert.assertNull(lookup.invoke(continuity, state, owner));
		Assert.assertEquals(3, directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_REQUESTS"));
		Assert.assertEquals(1, directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_ABSENT"));
		Assert.assertEquals(1,
			directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_FIXED_OWNER_REJECT"));
		Assert.assertEquals("the private lookup does not own the caller success counter", 0,
			directWork(metrics, "COMPONENT_SUMMARY_REUSE_HITS"));
	}

	@Test
	public void cacheDiagnosticsSeparateDisabledStateAndRowBudgetBypasses()
		throws Exception {
		synchronized(NativeComponentSummaryObservationTest.class) {
			String entries = "sysds.fedplanner.continuityAcyclicComponent.maxEntries";
			String states = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
			String rows = "sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";
			String oldEntries = System.getProperty(entries);
			String oldStates = System.getProperty(states);
			String oldRows = System.getProperty(rows);
			try {
				for(String disabled : List.of(entries, states, rows)) {
					System.setProperty(entries, "8");
					System.setProperty(states, "8");
					System.setProperty(rows, "8");
					System.setProperty(disabled, "0");
					SearchSpaceMetrics metrics = new SearchSpaceMetrics();
					NativePlacementContinuity continuity = empty(metrics);
					Object witness = witness(FType.FULL);
					cache(continuity, state(key("disabled-" + disabled), witness, 1),
						summary(List.of(), identitySet(key("disabled-owner-" + disabled)), 1L));
					Assert.assertEquals(1,
						directWork(metrics, "COMPONENT_SUMMARY_CACHE_DISABLED"));
					Assert.assertTrue(memo(continuity).isEmpty());
				}

				System.setProperty(entries, "8");
				System.setProperty(states, "1");
				System.setProperty(rows, "8");
				SearchSpaceMetrics stateMetrics = new SearchSpaceMetrics();
				NativePlacementContinuity stateLimited = empty(stateMetrics);
				Object witness = witness(FType.FULL);
				cache(stateLimited, state(key("state-bypass"), witness, 2),
					summary(List.of(), identitySet(key("state-owner")), 2L));
				Assert.assertEquals(1,
					directWork(stateMetrics, "COMPONENT_SUMMARY_CACHE_STATE_BYPASS"));

				System.setProperty(states, "8");
				System.setProperty(rows, "1");
				SearchSpaceMetrics rowMetrics = new SearchSpaceMetrics();
				NativePlacementContinuity rowLimited = empty(rowMetrics);
				Object row = selectedRow(witness);
				cache(rowLimited, state(key("row-bypass"), witness, 3),
					summary(List.of(row, row), identitySet(key("row-owner")), 1L));
				Assert.assertEquals(1,
					directWork(rowMetrics, "COMPONENT_SUMMARY_CACHE_ROW_BYPASS"));
			}
			finally {
				restore(entries, oldEntries);
				restore(states, oldStates);
				restore(rows, oldRows);
			}
		}
	}

	@Test
	public void admissionEvictionAndNegativeSummaryAccountingIsExact() throws Exception {
		synchronized(NativeComponentSummaryObservationTest.class) {
			String entries = "sysds.fedplanner.continuityAcyclicComponent.maxEntries";
			String oldEntries = System.getProperty(entries);
			try {
				System.setProperty(entries, "1");
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity continuity = empty(metrics);
				Object witness = witness(FType.FULL);
				Object first = state(key("negative-first"), witness, 1);
				Object second = state(key("negative-second"), witness, 2);
				Object negative = summary(List.of(), identitySet(key("negative-owner")), 1L);
				Object positive = summary(List.of(selectedRow(witness)),
					identitySet(key("positive-owner")), 1L);
				cache(continuity, first, negative);
				cache(continuity, second, positive);
				Assert.assertEquals(List.of(second), List.copyOf(memo(continuity).keySet()));
				Assert.assertSame(positive, memo(continuity).get(second));
				Assert.assertEquals(2,
					directWork(metrics, "COMPONENT_SUMMARY_CACHE_ADMISSIONS"));
				Assert.assertEquals(1,
					directWork(metrics, "COMPONENT_SUMMARY_CACHE_EVICTIONS"));
				metrics.reset();
				cache(continuity, second, positive);
				Assert.assertEquals(1,
					directWork(metrics, "COMPONENT_SUMMARY_CACHE_ADMISSIONS"));
				Assert.assertEquals(0,
					directWork(metrics, "COMPONENT_SUMMARY_CACHE_EVICTIONS"));
			}
			finally {
				restore(entries, oldEntries);
			}
		}
	}

	@Test
	public void generatedRootDependenceRejectIsObservedWithoutAdmission() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = empty(metrics);
		Object witness = witness(FType.FULL);
		CompiledHopKey generatedRoot = key("generated-root");
		Object child = state(key("root-dependent-child"), witness, 1);
		Object footprint = construct(nested("AcyclicComponentFootprint"),
			new Class<?>[] {Set.class, long.class}, identitySet(generatedRoot), 1L);
		Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
		invoke(continuity, "cacheAcyclicRootChildren",
			new Class<?>[] {Map.class, Map.class, Set.class, CompiledHopKey.class,
				traversal.getClass()}, Map.of(child, footprint), Map.of(child, List.of()),
			Set.of(), generatedRoot, traversal);
		Assert.assertEquals(1,
			directWork(metrics, "COMPONENT_SUMMARY_ROOT_DEPENDENCE_REJECT"));
		Assert.assertEquals(0,
			directWork(metrics, "COMPONENT_SUMMARY_CACHE_ADMISSIONS"));
		Assert.assertTrue(memo(continuity).isEmpty());
	}

	@Test
	public void reusedFootprintRejectedByStateBudgetIsObserved() throws Exception {
		synchronized(NativeComponentSummaryObservationTest.class) {
			String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
			String prior = System.getProperty(property);
			try {
				System.setProperty(property, "1");
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity continuity = empty(metrics);
				Object witness = witness(FType.FULL);
				CompiledHopKey owner = key("footprint-over-budget");
				Object child = state(owner, witness, 7);
				Object summary = summary(List.of(), identitySet(owner), 2L);
				Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
				Field reused = traversal.getClass().getDeclaredField("reusedComponents");
				reused.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object,Object> reusedComponents = (Map<Object,Object>)reused.get(traversal);
				reusedComponents.put(child, summary);
				Object footprint = method(NativePlacementContinuity.class,
					"acyclicComponentFootprint", nested("CandidateProofState"), Map.class,
					traversal.getClass()).invoke(continuity, child, Map.of(), traversal);
				Assert.assertNull(footprint);
				Assert.assertEquals(1,
					directWork(metrics, "COMPONENT_FOOTPRINT_STATE_BYPASS"));
			}
			finally {
				restore(property, prior);
			}
		}
	}

	private static void assertCompletedLookupPartition(SearchSpaceMetrics metrics) throws Exception {
		long requests = directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_REQUESTS");
		long absent = directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_ABSENT");
		long rejected = directWork(metrics, "COMPONENT_SUMMARY_LOOKUP_FIXED_OWNER_REJECT");
		long hits = directWork(metrics, "COMPONENT_SUMMARY_REUSE_HITS");
		Assert.assertTrue(requests > 0);
		Assert.assertEquals("every completed public lookup has exactly one outcome",
			requests, absent + rejected + hits);
	}

	private static void assertParity(NativePlacementContinuity.CandidateSupportResult expected,
		NativePlacementContinuity.CandidateSupportResult actual) {
		Assert.assertEquals(expected.proofs(), actual.proofs());
		Assert.assertEquals(expected.dependencyOccurrences().size(), actual.dependencyOccurrences().size());
		for(CompiledHopKey owner : expected.dependencyOccurrences())
			Assert.assertTrue(actual.dependencyOccurrences().stream().anyMatch(candidate -> candidate == owner));
	}

	private static Object fixture(FType type) throws Exception {
		Class<?> fixture = Class.forName(NativePlacementContinuityTest.class.getName() + "$Fixture");
		Constructor<?> constructor = fixture.getDeclaredConstructor(FType.class);
		constructor.setAccessible(true);
		return constructor.newInstance(type);
	}

	private static Object invokeNamed(Object target, String name, Object... arguments) throws Exception {
		for(Method candidate : target.getClass().getDeclaredMethods()) {
			if(!candidate.getName().equals(name) || candidate.getParameterCount() != arguments.length)
				continue;
			Class<?>[] parameters = candidate.getParameterTypes();
			boolean compatible = true;
			for(int index = 0; index < parameters.length; index++)
				if(arguments[index] != null && !boxed(parameters[index]).isInstance(arguments[index])) {
					compatible = false;
					break;
				}
			if(compatible) {
				candidate.setAccessible(true);
				return candidate.invoke(target, arguments);
			}
		}
		throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
	}

	private static Class<?> boxed(Class<?> type) {
		if(type == boolean.class) return Boolean.class;
		if(type == int.class) return Integer.class;
		if(type == long.class) return Long.class;
		return type;
	}

	private static NativePlacementContinuity empty(SearchSpaceMetrics metrics) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
	}

	private static Object state(CompiledHopKey owner, Object witness, int handle) throws Exception {
		return construct(nested("CandidateProofState"), new Class<?>[] {CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, witness.getClass(), boolean.class},
			owner, null, handle, witness, false);
	}

	private static Object summary(List<?> rows, Set<CompiledHopKey> owners, long states)
		throws Exception {
		return construct(nested("AcyclicComponentSummary"),
			new Class<?>[] {List.class, Set.class, long.class}, rows, owners, states);
	}

	private static Object selectedRow(Object witness) throws Exception {
		return construct(nested("SelectedCandidateProof"), new Class<?>[] {
			CandidateRealizationReference.class, List.class, boolean.class, witness.getClass()},
			null, List.of(), true, witness);
	}

	private static void cache(NativePlacementContinuity continuity, Object state, Object summary)
		throws Exception {
		invoke(continuity, "cacheAcyclicSummary",
			new Class<?>[] {state.getClass(), summary.getClass()}, state, summary);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> memo(NativePlacementContinuity continuity) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("acyclicComponentMemo");
		field.setAccessible(true);
		return (Map<Object,Object>)field.get(continuity);
	}

	private static Object witness(FType type) throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(type, List.of("worker:8001"), List.of(), true);
	}

	private static DurableAnchorKey pool(String id, FType type) {
		return new DurableAnchorKey(id, type,
			List.of(new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(50L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"component-observation", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("component-observation", "main", "call", "compiled",
			region, id, "origin-" + id);
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... owners) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, owners);
		return Collections.unmodifiableSet(result);
	}

	private static Object construct(Class<?> type, Class<?>[] parameters, Object... arguments)
		throws Exception {
		Constructor<?> constructor = type.getDeclaredConstructor(parameters);
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static Object invoke(Object target, String name, Class<?>[] parameters,
		Object... arguments) throws Exception {
		return method(target.getClass(), name, parameters).invoke(target, arguments);
	}

	private static Method method(Class<?> type, String name, Class<?>... parameters)
		throws Exception {
		Method method = type.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static long directWork(SearchSpaceMetrics metrics, String name) throws Exception {
		Class<? extends Enum> type = (Class<? extends Enum>)Class.forName(
			SearchSpaceMetrics.class.getName() + "$DirectWork");
		Object work = Enum.valueOf(type, name);
		Method count = SearchSpaceMetrics.class.getDeclaredMethod("directWorkCount", type);
		count.setAccessible(true);
		return (Long)count.invoke(metrics, work);
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static void restore(String property, String value) {
		if(value == null) System.clearProperty(property); else System.setProperty(property, value);
	}
}

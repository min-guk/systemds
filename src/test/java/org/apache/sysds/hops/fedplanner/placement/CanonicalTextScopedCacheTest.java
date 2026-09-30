/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class CanonicalTextScopedCacheTest {
	@Test
	public void sameAuthorityReusesRopeAcrossIndependentLocalContexts() throws Exception {
		PlacementProofKey left = proof("left"), right = proof("right");
		Assert.assertNotSame("outside a scope each local context retains the cold behavior",
			descriptor(left), descriptor(left));
		Object retained;
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			retained = descriptor(left);
			Assert.assertSame("a second local context must receive the exact retained descriptor",
				retained, descriptor(left));
			List<PlacementProofKey> expected = PlacementAnalysis.sharedCanonicalComparableList(
				List.of(right, left), "proof");
			int retainedEntries = scope.retainedEntries();
			Assert.assertTrue(retainedEntries >= 2);
			Assert.assertEquals(expected, PlacementAnalysis.sharedCanonicalComparableList(
				List.of(right, left), "proof"));
			Assert.assertEquals("separate local contexts must hit the shared identity cache",
				retainedEntries, scope.retainedEntries());
		}
		Assert.assertFalse(PlacementAnalysis.canonicalTextScopeActive());
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			Assert.assertNotSame("a new scope must not inherit descriptors from the closed scope",
				retained, descriptor(left));
		}
	}

	@Test
	public void equalButDistinctAuthoritiesNeverSubstituteByValue() throws Exception {
		PlacementProofKey first = proof("same"), equal = proof("same"), other = proof("other");
		Assert.assertEquals(first, equal);
		Assert.assertNotSame(first, equal);
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			Object firstDescriptor = descriptor(first);
			int beforeEqual = scope.retainedEntries();
			Object equalDescriptor = descriptor(equal);
			Assert.assertNotSame("identity cache must not substitute an equal authority object",
				firstDescriptor, equalDescriptor);
			Assert.assertEquals(beforeEqual + 1, scope.retainedEntries());
			PlacementAnalysis.compareCanonicalOrdering(first, other);
			int firstEntries = scope.retainedEntries();
			PlacementAnalysis.compareCanonicalOrdering(equal, other);
			Assert.assertEquals("both equal identities and the comparison peer must now be cache hits",
				firstEntries, scope.retainedEntries());
		}
	}

	@Test
	public void zeroAndTinyBudgetsFallBackWithoutChangingOrdering() {
		PlacementProofKey left = proof("a-long-authority"), right = proof("z-long-authority");
		int cold = PlacementAnalysis.compareCanonicalOrdering(left, right);
		for(long[] budget : new long[][] {{0, 4096}, {32, 1}})
			try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(
				(int)budget[0], budget[1])) {
				Assert.assertEquals(cold, PlacementAnalysis.compareCanonicalOrdering(left, right));
				Assert.assertEquals(0, scope.retainedEntries());
				Assert.assertEquals(0, scope.retainedWeight());
			}
	}

	@Test
	public void metricsPresenceDoesNotChangeCanonicalOrderOrScopeRetention() {
		PlacementProofKey left = proof("metric-left"), right = proof("metric-right");
		List<PlacementProofKey> withoutMetrics;
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			withoutMetrics = PlacementAnalysis.sharedCanonicalComparableList(List.of(right, left), "proof");
			Assert.assertEquals(2, scope.retainedEntries());
		}
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			Assert.assertEquals(withoutMetrics,
				PlacementAnalysis.sharedCanonicalComparableList(List.of(right, left), "proof"));
			Assert.assertEquals(2, scope.retainedEntries());
			Assert.assertTrue(metrics.snapshot().canonicalOrderingKeys() >= 2);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void nestedExceptionalAndThreadScopesRestoreExactlyTheirParent() throws Exception {
		PlacementProofKey parentValue = proof("parent"), childValue = proof("child");
		try(PlacementAnalysis.CanonicalTextScope parent = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			PlacementAnalysis.compareCanonicalOrdering(parentValue, proof("parent-other"));
			int parentEntries = parent.retainedEntries();
			try {
				try(PlacementAnalysis.CanonicalTextScope child = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
					PlacementAnalysis.compareCanonicalOrdering(childValue, proof("child-other"));
					Assert.assertTrue(child.retainedEntries() >= 2);
					throw new IllegalStateException("expected");
				}
			}
			catch(IllegalStateException expected) {
				Assert.assertEquals("expected", expected.getMessage());
			}
			Assert.assertTrue(PlacementAnalysis.canonicalTextScopeActive());
			PlacementAnalysis.compareCanonicalOrdering(parentValue, proof("parent-other"));
			Assert.assertEquals(parentEntries + 1, parent.retainedEntries());
			AtomicBoolean childThreadActive = new AtomicBoolean(true);
			Thread thread = new Thread(() -> childThreadActive.set(PlacementAnalysis.canonicalTextScopeActive()));
			thread.start();
			thread.join();
			Assert.assertFalse(childThreadActive.get());
		}
		Assert.assertFalse(PlacementAnalysis.canonicalTextScopeActive());
	}

	@Test
	public void failedAndSubsequentBuilderRunsLeaveNoScopeBehind() {
		try(PlacementAnalysis.CanonicalTextScope parent = PlacementAnalysis.beginCanonicalTextScope(8, 1024)) {
			for(int run = 0; run < 2; run++) {
				Assert.assertThrows(RuntimeException.class,
					() -> new NeutralPlacementGraphBuilder().buildDetachedAnalysis(null));
				Assert.assertTrue("nested builder failure did not restore parent scope on run " + run,
					PlacementAnalysis.canonicalTextScopeActive());
				Assert.assertEquals(0, parent.retainedEntries());
			}
		}
		Assert.assertFalse(PlacementAnalysis.canonicalTextScopeActive());
	}

	@Test
	public void closedTokenReleasesRetainedIdentityGraphAndWeight() {
		PlacementAnalysis.CanonicalTextScope retained = PlacementAnalysis.beginCanonicalTextScope(32, 4096);
		PlacementAnalysis.compareCanonicalOrdering(proof("retained-left"), proof("retained-right"));
		Assert.assertTrue(retained.retainedEntries() >= 2);
		Assert.assertTrue("weight includes entries, rope nodes, lists, references and literals",
			retained.retainedWeight() > "retained-left".length() + "retained-right".length());
		retained.close();
		Assert.assertEquals(0, retained.retainedEntries());
		Assert.assertEquals(0, retained.retainedWeight());
		Assert.assertFalse(PlacementAnalysis.canonicalTextScopeActive());
	}

	@Test
	public void outOfOrderCloseFailsWithoutLosingTheActiveChildOrParent() {
		PlacementAnalysis.CanonicalTextScope parent = PlacementAnalysis.beginCanonicalTextScope(8, 1024);
		PlacementAnalysis.CanonicalTextScope child = PlacementAnalysis.beginCanonicalTextScope(8, 1024);
		Assert.assertThrows(IllegalStateException.class, parent::close);
		Assert.assertTrue(PlacementAnalysis.canonicalTextScopeActive());
		child.close();
		Assert.assertTrue(PlacementAnalysis.canonicalTextScopeActive());
		parent.close();
		Assert.assertFalse(PlacementAnalysis.canonicalTextScopeActive());
	}

	@Test
	public void nestedDispatcherTypeRemainsRejectedAfterSharedWarmup() throws Exception {
		PlacementEmissionState nested = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		PlacementRealizationKey supported = PlacementRealizationKey.local(nested);
		assertGenericRejected(nested);
		try(PlacementAnalysis.CanonicalTextScope scope = PlacementAnalysis.beginCanonicalTextScope(32, 4096)) {
			descriptor(supported); // Warms the nested emission through its explicit helper dispatcher.
			assertGenericRejected(nested); // A fresh local context must still enforce the generic whitelist.
		}
		assertGenericRejected(nested);
	}

	private static PlacementProofKey proof(String authority) {
		return new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, null, authority);
	}

	private static Object descriptor(Object authority) throws Exception {
		Class<?> contextType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextContext");
		Constructor<?> constructor = contextType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object context = constructor.newInstance();
		Method orderingKey = PlacementAnalysis.class.getDeclaredMethod(
			"canonicalOrderingKey", Object.class, contextType);
		orderingKey.setAccessible(true);
		return orderingKey.invoke(null, authority, context);
	}

	private static void assertGenericRejected(Object value) throws Exception {
		try {
			descriptor(value);
			Assert.fail("nested-only canonical type entered the generic dispatcher");
		}
		catch(InvocationTargetException expected) {
			Assert.assertTrue(expected.getCause() instanceof IllegalArgumentException);
			Assert.assertTrue(expected.getCause().getMessage().startsWith(
				"Unsupported canonical comparable type"));
		}
	}
}

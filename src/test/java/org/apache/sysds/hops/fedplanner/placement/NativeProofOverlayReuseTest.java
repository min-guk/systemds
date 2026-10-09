/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofOverlayReuseTest {
	@Test
	public void overlayPreservesOwnerIdentityAndTemplateRootForExactPin() throws Exception {
		CompiledHopKey owner = owner("left");
		CompiledHopKey equalForeign = owner("left");
		Assert.assertEquals(owner, equalForeign);
		Assert.assertNotSame(owner, equalForeign);
		List<?> skeletons = List.of(skeleton(owner, 0), skeleton(owner("right"), 1));
		List<?> foreign = overlay(skeletons,
			fixedBoundary(equalForeign,reference(equalForeign),11));
		Assert.assertEquals(false, field(foreign.get(0), "templateRoot"));
		List<?> exact = overlay(skeletons,fixedBoundary(owner,reference(owner),17));
		Assert.assertEquals(true, field(exact.get(0), "templateRoot"));
		Assert.assertEquals(17, field(exact.get(0), "realizationHandle"));
		Assert.assertSame(owner, field(exact.get(0), "key"));
		Assert.assertEquals(1, field(exact.get(1), "inputPosition"));
		Assert.assertEquals(foreign.get(1), exact.get(1));
	}

	@Test
	public void rowOverlayReusesOnlyUnpinnedImmutableDependencies() throws Exception {
		CompiledHopKey left = owner("left"), right = owner("right");
		List<?> skeletons = List.of(skeleton(left, 0), skeleton(right, 1));
		CompiledHopKey unrelated = owner("unrelated");
		Object unrelatedBoundary = fixedBoundary(unrelated,reference(unrelated),7);
		List<?> defaults = List.copyOf(overlay(skeletons,unrelatedBoundary));
		Object fixed = fixedBoundary(left,reference(left),9);
		Method method = NativePlacementContinuity.class.getDeclaredMethod("overlayDependencies",
			List.class, List.class, nested("FixedCandidateBoundary"));
		method.setAccessible(true);
		NativePlacementContinuity continuity = empty();
		List<?> actual = (List<?>)method.invoke(continuity, skeletons, defaults, fixed);
		Assert.assertEquals(overlay(skeletons,fixed), actual);
		Assert.assertNotSame(defaults.get(0), actual.get(0));
		Assert.assertSame("unchanged dependency must not allocate another proof state",
			defaults.get(1), actual.get(1));
		List<?> unchanged = (List<?>)method.invoke(continuity, skeletons, defaults, unrelatedBoundary);
		Assert.assertSame(defaults, unchanged);
		Assert.assertEquals(false, field(defaults.get(0), "templateRoot"));
	}

	@Test
	public void fixedBoundaryRejectsNullReferenceInsteadOfCreatingSyntheticAuthority() throws Exception {
		Constructor<?> constructor = nested("FixedCandidateBoundary").getDeclaredConstructor(
			CompiledHopKey.class,CandidateRealizationReference.class,int.class);
		constructor.setAccessible(true);
		InvocationTargetException failure = Assert.assertThrows(InvocationTargetException.class,
			() -> constructor.newInstance(owner("null-pin"),null,1));
		Assert.assertTrue(failure.getCause() instanceof NullPointerException);
	}

	private static List<?> overlay(List<?> skeletons, Object fixed) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("overlayDependencies",
			List.class, nested("FixedCandidateBoundary"));
		method.setAccessible(true);
		return (List<?>)method.invoke(empty(), skeletons, fixed);
	}
	private static Object fixedBoundary(CompiledHopKey owner,
		CandidateRealizationReference reference, int handle) throws Exception {
		Constructor<?> constructor = nested("FixedCandidateBoundary").getDeclaredConstructor(
			CompiledHopKey.class,CandidateRealizationReference.class,int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner,reference,handle);
	}
	private static CandidateRealizationReference reference(CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,List.of());
		PlacementState state = new PlacementState(
			ExecType.FED,FederatedOutput.LOUT,FType.ROW,false);
		return CandidateRealizationReference.of(rule,CandidateEmissionRealization.local(
			new PlacementEmissionState(state,false)));
	}
	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}
	private static NativePlacementContinuity empty() {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of());
	}
	private static Object skeleton(CompiledHopKey owner, int position) throws Exception {
		Class<?> witness = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Constructor<?> wc = witness.getDeclaredConstructor(FType.class, List.class, List.class, boolean.class);
		wc.setAccessible(true);
		Object w = wc.newInstance(FType.FULL, List.of("worker:8001"), List.of(), true);
		Class<?> skeleton = Class.forName(NativePlacementContinuity.class.getName() + "$CandidateDependencySkeleton");
		Constructor<?> sc = skeleton.getDeclaredConstructors()[0]; sc.setAccessible(true);
		return sc.newInstance(owner, null, 0, w, position);
	}
	private static Object field(Object value, String name) throws Exception {
		Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
	}
	private static CompiledHopKey owner(String name) {
		ControlRegionKey region = new ControlRegionKey("proof", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("proof", "main", "call", "compiled", region, name, "origin");
	}
}

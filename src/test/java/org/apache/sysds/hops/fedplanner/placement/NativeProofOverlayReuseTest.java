/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofOverlayReuseTest {
	@Test
	public void overlayPreservesOwnerIdentityAndTemplateRootEvenForNullPin() throws Exception {
		CompiledHopKey owner = owner("left");
		CompiledHopKey equalForeign = owner("left");
		Assert.assertEquals(owner, equalForeign);
		Assert.assertNotSame(owner, equalForeign);
		List<?> skeletons = List.of(skeleton(owner, 0), skeleton(owner("right"), 1));
		Map<CompiledHopKey,Object> fixed = new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> handles = new IdentityHashMap<>();
		fixed.put(equalForeign, null); handles.put(equalForeign, 0);
		List<?> foreign = overlay(skeletons, fixed, handles);
		Assert.assertEquals(false, field(foreign.get(0), "templateRoot"));
		fixed.put(owner, null); handles.put(owner, 17);
		List<?> exact = overlay(skeletons, fixed, handles);
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
		List<?> defaults = List.copyOf(overlay(skeletons, Map.of(), Map.of()));
		Map<CompiledHopKey,Object> fixed = new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> handles = new IdentityHashMap<>();
		fixed.put(left, null); handles.put(left, 9);
		Method method = NativePlacementContinuity.class.getDeclaredMethod("overlayDependencies",
			List.class, List.class, Map.class, Map.class);
		method.setAccessible(true);
		NativePlacementContinuity continuity = empty();
		List<?> actual = (List<?>)method.invoke(continuity, skeletons, defaults, fixed, handles);
		Assert.assertEquals(overlay(skeletons, fixed, handles), actual);
		Assert.assertNotSame(defaults.get(0), actual.get(0));
		Assert.assertSame("unchanged dependency must not allocate another proof state",
			defaults.get(1), actual.get(1));
		List<?> unchanged = (List<?>)method.invoke(continuity, skeletons, defaults, Map.of(), Map.of());
		Assert.assertSame(defaults, unchanged);
		Assert.assertEquals(false, field(defaults.get(0), "templateRoot"));
	}

	private static List<?> overlay(List<?> skeletons, Map<?,?> fixed, Map<?,?> handles) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("overlayDependencies",
			List.class, Map.class, Map.class);
		method.setAccessible(true);
		return (List<?>)method.invoke(empty(), skeletons, fixed, handles);
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

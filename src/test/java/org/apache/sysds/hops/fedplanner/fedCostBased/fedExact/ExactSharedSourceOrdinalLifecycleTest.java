/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Atomic publication and fail-closed tests for preparation-local source ordinals. */
public class ExactSharedSourceOrdinalLifecycleTest {

	@Test
	public void missingOrdinalFailsBeforePublishingOrReleasingRawRows() throws Exception {
		CompiledHopKey known = owner("known"), missing = owner("missing");
		CandidateRealizationReference knownReference = reference(known);
		CandidateRealizationReference missingReference = reference(missing);
		IdentityHashMap<CompiledHopKey,CandidateRealizationReference> first = new IdentityHashMap<>();
		first.put(known, knownReference);
		IdentityHashMap<CompiledHopKey,CandidateRealizationReference> second = new IdentityHashMap<>();
		second.put(missing, missingReference);
		Object view = domain(List.of(known, missing), List.of(first, second));
		Object rawBefore = field(view, "refsByRow");
		IdentityHashMap<CompiledHopKey,Object> references = new IdentityHashMap<>();
		references.put(known, referenceView(view, knownReference, 1));

		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
			() -> freeze().invoke(view, references));
		assertEquals("SOURCE_REFERENCE_DOMAIN_INCOMPLETE", failure.getCause().getMessage());
		assertSame("failure must not release the only recoverable raw representation",
			rawBefore, field(view, "refsByRow"));
		assertNull("partial ordinal rows must never be published",
			field(view, "sourceOrdinalsByRow"));
	}

	@Test
	public void successfulFreezePublishesEqualReferenceOrdinalAndKeepsWildcardDistinct()
		throws Exception {
		CompiledHopKey owner = owner("owner");
		CandidateRealizationReference canonical = reference(owner);
		CandidateRealizationReference equalButDistinct =
			new CandidateRealizationReference(canonical.rule(), canonical.realization());
		IdentityHashMap<CompiledHopKey,CandidateRealizationReference> bound = new IdentityHashMap<>();
		bound.put(owner, equalButDistinct);
		Object view = domain(List.of(owner), List.of(bound, new IdentityHashMap<>()));
		IdentityHashMap<CompiledHopKey,Object> references = new IdentityHashMap<>();
		references.put(owner, referenceView(view, canonical, 3));

		freeze().invoke(view, references);
		assertNull("raw reference graphs are released after complete publication",
			field(view, "refsByRow"));
		assertNotNull(field(view, "sourceOrdinalsByRow"));
		Method sourceOrdinal = view.getClass().getDeclaredMethod("sourceOrdinal", int.class, int.class);
		sourceOrdinal.setAccessible(true);
		assertEquals("record-equal references share the canonical ordinal", 3,
			sourceOrdinal.invoke(view, 0, 0));
		assertEquals("absent binding remains wildcard, not a nonnegative NONE category", -1,
			sourceOrdinal.invoke(view, 1, 0));
	}

	private static Object domain(List<CompiledHopKey> owners,
		List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> rows) throws Exception {
		Object header = construct(nested("AlternativeHeader"), null, null, null, null, null, null,
			null, null, null, null, List.of(), List.of(), null, List.of());
		int[] headers = new int[rows.size()];
		return construct(nested("DomainView"), 0, null, List.of(header), headers, owners, rows);
	}

	private static Object referenceView(Object ownerView, CandidateRealizationReference reference,
		int ordinal) throws Exception {
		Object[] values = new Object[ordinal + 1];
		Arrays.fill(values, new Object());
		values[ordinal] = reference;
		return construct(nested("ReferenceView"), ownerView, List.of(values),
			Map.of(reference, ordinal), new int[] {ordinal});
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner) {
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementRealizationKey layout = PlacementRealizationKey.local(
			new PlacementEmissionState(local, false));
		return new CandidateRealizationReference(new CandidateRuleKey(owner, List.of()), layout);
	}

	private static CompiledHopKey owner(String name) throws Exception {
		Method node = ExactPhysicalSharedSourceEncodingTest.class.getDeclaredMethod(
			"syntheticNode", String.class, List.class);
		node.setAccessible(true);
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		return ((org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node)node.invoke(
			null, "ordinal-lifecycle-" + name, List.of(local))).key();
	}

	private static Method freeze() throws Exception {
		Method method = nested("DomainView").getDeclaredMethod("freezeSourceOrdinals",
			IdentityHashMap.class);
		method.setAccessible(true);
		return method;
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(ExactPhysicalSharedSourceEncoding.class.getName() + '$' + name);
	}

	private static Object construct(Class<?> type, Object... arguments) throws Exception {
		Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == arguments.length)
			.findFirst().orElseThrow();
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
}

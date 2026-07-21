/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.junit.Test;

/** Prepatch B0 RED for the typed, graph-free exact MinST ownership boundary. */
public class CampaignBMinStExactBoundaryRedTest {
	private static final String PREFIX =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.";
	private static final String FACTS = PREFIX + "MinStExactCostFacts";
	private static final String FACTS_PRODUCER = PREFIX + "MinStExactCostFactsProducer";
	private static final String SELECTOR = PREFIX + "MinStExactSelector";
	private static final String SELECTION = PREFIX + "MinStExactSelection";
	private static final String DIAGNOSTICS_PRODUCER = PREFIX + "MinStDiagnosticsProducer";
	private static final String DIAGNOSTICS_LOGGER = PREFIX + "MinStDiagnosticsLogger";

	@Test
	public void exactOwnerSelectorAndDiagnosticsBoundariesExistWithTypedMethods() throws Exception {
		Class<?> facts = boundary(FACTS);
		Class<?> factsProducer = boundary(FACTS_PRODUCER);
		Class<?> selector = boundary(SELECTOR);
		Class<?> selection = boundary(SELECTION);
		Class<?> producer = boundary(DIAGNOSTICS_PRODUCER);
		Class<?> logger = boundary(DIAGNOSTICS_LOGGER);
		Method derive = requireStatic(factsProducer, "derive", PlacementAnalysis.class, List.class);
		org.junit.Assert.assertEquals(facts, derive.getReturnType());
		org.junit.Assert.assertTrue("MINST_EXACT_SCOPE_KEY_TYPE_MISSING",
			derive.getGenericParameterTypes()[1].getTypeName().contains(CompiledHopKey.class.getName()));
		org.junit.Assert.assertEquals(selection, requirePublic(selector, "select", facts).getReturnType());
		org.junit.Assert.assertEquals("MinStDiagnostics",
			requirePublic(producer, "project", PlacementAnalysis.class, facts, selection)
				.getReturnType().getSimpleName());
		org.junit.Assert.assertTrue("MINST_DIAGNOSTICS_LOGGER_TYPED_METHOD_MISSING",
			List.of(logger.getMethods()).stream().anyMatch(method -> method.getName().equals("log")
				&& method.getParameterCount() == 1
				&& method.getParameterTypes()[0].getSimpleName().equals("MinStDiagnostics")));
	}

	@Test
	public void exactSelectionExposesApprovedOrderedProvenance() throws Exception {
		Class<?> selection = boundary(SELECTION);
		for(String accessor : List.of("objectiveBits", "sourcePartitionNodeIds",
			"selectedStatesInScopeOrder", "obligationReceiptsInOrder", "tieCertificate"))
			requirePublic(selection, accessor);
	}

	private static Class<?> boundary(String name) throws ClassNotFoundException {
		try {
			return Class.forName(name);
		}
		catch(ClassNotFoundException ex) {
			throw new AssertionError("MINST_EXACT_FACT_BOUNDARY_MISSING|class=" + name, ex);
		}
	}

	private static Method requireStatic(Class<?> owner, String name, Class<?>... parameters)
		throws NoSuchMethodException {
		Method method = owner.getMethod(name, parameters);
		org.junit.Assert.assertTrue("MINST_EXACT_STATIC_METHOD_REQUIRED|method=" + method,
			Modifier.isStatic(method.getModifiers()));
		return method;
	}

	private static Method requirePublic(Class<?> owner, String name, Class<?>... parameters)
		throws NoSuchMethodException {
		Method method = owner.getMethod(name, parameters);
		org.junit.Assert.assertTrue("MINST_EXACT_PUBLIC_METHOD_REQUIRED|method=" + method,
			Modifier.isPublic(method.getModifiers()));
		return method;
	}
}

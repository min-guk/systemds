/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.junit.Assert;
import org.junit.Test;

/** Prepatch B0 RED for the typed, graph-free exact MinST ownership boundary. */
public class CampaignBMinStExactBoundaryRedTest {
	private static final String PREFIX =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.";
	private static final String FACTS = PREFIX + "MinStExactCostFacts";
	private static final String SELECTOR = PREFIX + "MinStExactSelector";
	private static final String SELECTION = PREFIX + "MinStExactSelection";
	private static final String DIAGNOSTICS_PRODUCER = PREFIX + "MinStDiagnosticsProducer";
	private static final String DIAGNOSTICS_LOGGER = PREFIX + "MinStDiagnosticsLogger";

	@Test
	public void uniqueTwoDecisionLiteralHasFixedRawObjectiveAndSelection() {
		MinStExactTwoDecisionOracle.Selection selected = MinStExactTwoDecisionOracle.enumerateUniqueFixture();
		Assert.assertEquals(MinStExactTwoDecisionOracle.bits(3.0), selected.objectiveBits());
		Assert.assertEquals(List.of(MinStExactTwoDecisionOracle.A_ID, MinStExactTwoDecisionOracle.B_ID),
			selected.sourceNodeIds());
		Assert.assertEquals(3, selected.mask());
		MinStExactTwoDecisionOracle.validateObjective(MinStExactTwoDecisionOracle.UNIQUE_EDGES, selected);
	}

	@Test
	public void literalOracleRejectsCapacityTotalSourceAndOrderCorruption() {
		MinStExactTwoDecisionOracle.Selection selected = MinStExactTwoDecisionOracle.enumerateUniqueFixture();
		String fingerprint = "B0-INDEPENDENT-OWNER";
		List<Long> scope = List.of(MinStExactTwoDecisionOracle.A_ID, MinStExactTwoDecisionOracle.B_ID);
		List<String> states = List.of("FED_FOUT", "FED_FOUT");
		List<String> obligations = List.of("D|10|20", "U|20|10");
		MinStExactTwoDecisionOracle.OwnerBoundLiteral literal = literal(fingerprint, scope,
			MinStExactTwoDecisionOracle.UNIQUE_EDGES, selected, states, obligations);
		MinStExactTwoDecisionOracle.validateLiteral(literal, fingerprint, scope, states, obligations);
		assertRejects(() -> MinStExactTwoDecisionOracle.validateLiteral(literal("FOREIGN", scope,
			literal.edges(), selected, states, obligations), fingerprint, scope, states, obligations));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateLiteral(literal(fingerprint,
			List.of(MinStExactTwoDecisionOracle.B_ID, MinStExactTwoDecisionOracle.A_ID), literal.edges(),
			selected, states, obligations), fingerprint, scope, states, obligations));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateLiteral(literal(fingerprint,
			List.of(MinStExactTwoDecisionOracle.A_ID), literal.edges(), selected, states, obligations),
			fingerprint, scope, states, obligations));
		List<MinStExactTwoDecisionOracle.Edge> mutated = new ArrayList<>(MinStExactTwoDecisionOracle.UNIQUE_EDGES);
		MinStExactTwoDecisionOracle.Edge first = mutated.get(0);
		mutated.set(0, new MinStExactTwoDecisionOracle.Edge(first.from(), first.to(), first.capacityBits() ^ 1L));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateObjective(mutated, selected));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateObjective(MinStExactTwoDecisionOracle.UNIQUE_EDGES,
			new MinStExactTwoDecisionOracle.Selection(selected.objectiveBits() ^ 1L,
				selected.sourceNodeIds(), selected.mask())));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateCanonicalSourceIds(
			List.of(MinStExactTwoDecisionOracle.B_ID, MinStExactTwoDecisionOracle.A_ID)));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateCanonicalSourceIds(
			List.of(MinStExactTwoDecisionOracle.A_ID, MinStExactTwoDecisionOracle.A_ID)));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateLiteral(literal(fingerprint, scope,
			literal.edges(), selected, List.of(states.get(1), states.get(0)), obligations),
			fingerprint, scope, states, obligations));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateLiteral(literal(fingerprint, scope,
			literal.edges(), selected, states, List.of(obligations.get(1), obligations.get(0))),
			fingerprint, scope, states, obligations));
	}

	@Test
	public void exactOwnerSelectorAndDiagnosticsBoundariesExistWithTypedMethods() throws Exception {
		Class<?> facts = boundary(FACTS);
		Class<?> selector = boundary(SELECTOR);
		Class<?> selection = boundary(SELECTION);
		Class<?> producer = boundary(DIAGNOSTICS_PRODUCER);
		Class<?> logger = boundary(DIAGNOSTICS_LOGGER);
		requireStatic(facts, "from", PlacementAnalysis.class, List.class);
		Assert.assertEquals(selection, requirePublic(selector, "select", facts).getReturnType());
		Assert.assertEquals("MinStDiagnostics",
			requirePublic(producer, "project", facts, selection).getReturnType().getSimpleName());
		Assert.assertTrue("MINST_DIAGNOSTICS_LOGGER_TYPED_METHOD_MISSING",
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

	private static MinStExactTwoDecisionOracle.OwnerBoundLiteral literal(String fingerprint,
		List<Long> scope, List<MinStExactTwoDecisionOracle.Edge> edges,
		MinStExactTwoDecisionOracle.Selection selection, List<String> states, List<String> obligations) {
		return new MinStExactTwoDecisionOracle.OwnerBoundLiteral(fingerprint, scope, edges, selection,
			states, obligations);
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
		Assert.assertTrue("MINST_EXACT_STATIC_METHOD_REQUIRED|method=" + method,
			Modifier.isStatic(method.getModifiers()));
		return method;
	}

	private static Method requirePublic(Class<?> owner, String name, Class<?>... parameters)
		throws NoSuchMethodException {
		Method method = owner.getMethod(name, parameters);
		Assert.assertTrue("MINST_EXACT_PUBLIC_METHOD_REQUIRED|method=" + method,
			Modifier.isPublic(method.getModifiers()));
		return method;
	}

	private static void assertRejects(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected literal corruption rejection");
		}
		catch(IllegalArgumentException expected) {
			// expected
		}
	}
}

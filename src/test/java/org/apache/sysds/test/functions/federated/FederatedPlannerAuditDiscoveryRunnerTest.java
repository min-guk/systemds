/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.functions.federated;

import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/**
 * Audit-only exact-leaf discovery boundary.
 *
 * <p>Invoke this test with {@value #INVOCATION_PROPERTY} set to the complete
 * {@code class#method[parameter]} identity. It runs exactly that JUnit leaf and
 * supplies the identity to candidate audit emission. This avoids the unsound
 * inference of a parameter sibling from a PID-scoped candidate file.</p>
 */
public class FederatedPlannerAuditDiscoveryRunnerTest {
	public static final String INVOCATION_PROPERTY =
		"sysds.fedplanner.space.audit.discovery.invocation";

	@Test
	public void discoverExactLeaf() throws Exception {
		String invocation = System.getProperty(INVOCATION_PROPERTY);
		Assume.assumeTrue("exact audit discovery invocation not configured",
			invocation != null && !invocation.isBlank());
		FederatedForcedStateAuditRunnerTest.InvocationIdentity identity =
			FederatedForcedStateAuditRunnerTest.parseInvocation(invocation,
				"exact discovery invocation");
		Class<?> testClass = Class.forName(identity.className());
		String method = identity.methodName();
		List<Description> leaves = FederatedForcedStateAuditRunnerTest
			.matchingTestCases(testClass, method);
		if(leaves.size() != 1)
			throw new IllegalArgumentException("Exact discovery invocation resolved to "
				+ leaves.size() + " JUnit leaves: " + invocation);
		Description leaf = leaves.get(0);
		String exact = leaf.getClassName() + '#' + leaf.getMethodName();
		String context = leaf.getClassName() + '#'
			+ FederatedForcedStateAuditRunnerTest.baseMethodName(leaf.getMethodName());
		System.setProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY, context);
		System.setProperty(PlannerCandidateSpaceAudit.INVOCATION_PROPERTY, exact);
		try {
			Result result = new JUnitCore().run(
				FederatedForcedStateAuditRunnerTest.exactRequest(testClass, leaf));
			if(!result.wasSuccessful())
				throw new AssertionError("Exact audit discovery leaf failed: "
					+ result.getFailures());
		}
		finally {
			System.clearProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY);
			System.clearProperty(PlannerCandidateSpaceAudit.INVOCATION_PROPERTY);
		}
	}
}

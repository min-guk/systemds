/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.Test;

/** Behavioral locks for planner-neutral external configuration acquisition. */
public class FederatedPlannerConfigurationContractTest {
	private static final String CLASS_NAME = "org.apache.sysds.conf.FederatedPlannerConfiguration";

	@Test
	public void propertyAcquisitionPreservesDefaultEmptyAndExplicitValues() throws Exception {
		String key = uniqueKey();
		try {
			Method capture = configuration().getMethod("captureProperty", String.class, String.class);
			assertEquals("fallback", capture.invoke(null, key, "fallback"));
			System.setProperty(key, "");
			assertEquals("", capture.invoke(null, key, "fallback"));
			System.setProperty(key, " explicit ");
			assertEquals(" explicit ", capture.invoke(null, key, "fallback"));
		}
		finally {
			System.clearProperty(key);
		}
	}

	@Test
	public void trimmedPropertyOverridesEnvironmentAndBlankFallsBack() throws Exception {
		String envKey = nonBlankEnvironmentKey();
		String propertyKey = uniqueKey();
		try {
			Method capture = configuration().getMethod("captureTrimmedPropertyOrEnvironment",
				String.class, String.class);
			System.setProperty(propertyKey, "  property-value  ");
			assertEquals("property-value", capture.invoke(null, propertyKey, envKey));
			System.setProperty(propertyKey, "  \t ");
			assertEquals(System.getenv(envKey).trim(), capture.invoke(null, propertyKey, envKey));
			System.clearProperty(propertyKey);
			assertNull(capture.invoke(null, propertyKey, uniqueKey()));
		}
		finally {
			System.clearProperty(propertyKey);
		}
	}

	@Test
	public void nonEmptySameKeyAcquisitionPreservesRawValues() throws Exception {
		String envKey = nonBlankEnvironmentKey();
		String original = System.getProperty(envKey);
		try {
			Method capture = configuration().getMethod("captureNonEmptyPropertyOrEnvironment", String.class);
			System.setProperty(envKey, " raw property value ");
			assertEquals(" raw property value ", capture.invoke(null, envKey));
			System.setProperty(envKey, "");
			assertEquals(System.getenv(envKey), capture.invoke(null, envKey));
			assertNull(capture.invoke(null, uniqueKey()));
		}
		finally {
			restoreProperty(envKey, original);
		}
	}

	private static Class<?> configuration() throws Exception {
		try {
			return Class.forName(CLASS_NAME);
		}
		catch(ClassNotFoundException ex) {
			fail("planner-neutral configuration boundary is absent");
			throw ex;
		}
	}

	private static String nonBlankEnvironmentKey() {
		for(String key : new String[] {"PATH", "HOME", "USER"})
			if(System.getenv(key) != null && !System.getenv(key).trim().isEmpty())
				return key;
		throw new AssertionError("test requires one conventional nonblank environment variable");
	}

	private static String uniqueKey() {
		String key = "sysds.test.federated.configuration." + UUID.randomUUID();
		assertNull(System.getenv(key));
		return key;
	}

	private static void restoreProperty(String key, String value) {
		if(value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}
}

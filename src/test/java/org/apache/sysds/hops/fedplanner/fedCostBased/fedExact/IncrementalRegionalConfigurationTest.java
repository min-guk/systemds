/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class IncrementalRegionalConfigurationTest {
	private static final String ENABLED = IncrementalRegionalOptimizer.PREFIX + "enabled";
	private static final String MODE = "sysds.fedplanner.regional.mode";
	private static final String ALGORITHM = "sysds.fedplanner.regional.algorithm";
	private static final String INITIAL_BOUND = "sysds.fedplanner.regional.initialBound";
	private static final List<String> REMOVED_BUDGETS = List.of(
		IncrementalRegionalOptimizer.PREFIX + "assignments",
		IncrementalRegionalOptimizer.PREFIX + "retainedSlots",
		IncrementalRegionalOptimizer.PREFIX + "timeMillis",
		IncrementalRegionalOptimizer.PREFIX + "scoredCandidates");
	private static final List<String> KEYS = java.util.stream.Stream.concat(
		List.of(ENABLED,MODE,ALGORITHM,INITIAL_BOUND).stream(),REMOVED_BUDGETS.stream()).toList();
	private final Map<String,String> previous = new LinkedHashMap<>();

	@Before
	public void saveAndClearProperties() {
		for(String key : KEYS) {
			previous.put(key, System.getProperty(key));
			System.clearProperty(key);
		}
	}

	@After
	public void restoreProperties() {
		for(String key : KEYS) {
			String value = previous.get(key);
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		}
	}

	@Test
	public void defaultAndExplicitTrueSelectIncrementalRegional() {
		IncrementalRegionalOptimizer.validateConfiguration();
		Assert.assertNotNull(IncrementalRegionalOptimizer.Options.configured());

		System.setProperty(ENABLED, "true");
		IncrementalRegionalOptimizer.validateConfiguration();
		Assert.assertNotNull(IncrementalRegionalOptimizer.Options.configured());
	}

	@Test
	public void falseAndMalformedEnabledValuesAreRejected() {
		System.setProperty(ENABLED, "false");
		assertRejected("INCREMENTAL_REGIONAL_ENABLED_INVALID");

		System.setProperty(ENABLED, "invalid");
		assertRejected("INCREMENTAL_REGIONAL_ENABLED_INVALID");
	}

	@Test
	public void removedModesAreRejected() {
		System.setProperty(MODE, "anytime");
		assertRejected("REGIONAL_MODE_REMOVED");

		System.setProperty(MODE, "remaining-exact");
		assertRejected("REGIONAL_MODE_REMOVED");
	}

	@Test
	public void removedAlgorithmsAreRejected() {
		System.setProperty(ALGORITHM, "target-gap-c");
		assertRejected("REGIONAL_ALGORITHM_REMOVED");
	}

	@Test
	public void everyExplicitInitialBoundIsRejected() {
		System.setProperty(INITIAL_BOUND, "replica");
		assertRejected("REGIONAL_INITIAL_BOUND_REMOVED");

		System.setProperty(INITIAL_BOUND, "");
		assertRejected("REGIONAL_INITIAL_BOUND_REMOVED");
	}

	@Test
	public void historicalRunnerMarkersRemainNeutral() {
		System.setProperty(MODE, "off");
		System.setProperty(ALGORITHM, "legacy");
		IncrementalRegionalOptimizer.validateConfiguration();
		Assert.assertNotNull(IncrementalRegionalOptimizer.Options.configured());
	}

	@Test
	public void fixedResourceBudgetPropertiesAreExplicitlyRemoved() {
		for(String property : REMOVED_BUDGETS) {
			System.setProperty(property,"1");
			assertRejected("INCREMENTAL_REGIONAL_");
			System.clearProperty(property);
		}
	}

	@Test
	public void configuredOptionsContainNoFixedResourceOrElapsedBudget() {
		IncrementalRegionalOptimizer.Options options =
			IncrementalRegionalOptimizer.Options.configured();
		Assert.assertFalse(options.boundedTest());
		Assert.assertEquals(0L,options.maximumMergeAssignments());
		Assert.assertEquals(0L,options.maximumRetainedSlots());
		Assert.assertEquals(0L,options.timeMillis());
		Assert.assertEquals(0,options.scoredCandidates());
		Assert.assertEquals(.05,options.relativeGap(),0d);
		Assert.assertTrue("Local retains its quality-gap stopping rule",options.earlyStop());
	}

	private static void assertRejected(String prefix) {
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			IncrementalRegionalOptimizer::validateConfiguration);
		Assert.assertTrue(failure.getMessage(), failure.getMessage().startsWith(prefix));
	}
}

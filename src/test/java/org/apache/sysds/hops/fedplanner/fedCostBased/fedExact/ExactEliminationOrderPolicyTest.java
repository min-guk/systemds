/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import org.junit.Assert;
import org.junit.Test;

public class ExactEliminationOrderPolicyTest {
	@Test
	public void commonConfigurationOverridesLegacyLocalForCompactAndPlainBlocks() {
		Properties saved = new Properties();
		try {
			clear();
			System.setProperty(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_PROPERTY, "true");
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, "false");
			Assert.assertFalse(ExactEliminationOrderPolicy.globalConfigured().fastOrder());
			Assert.assertFalse(ExactEliminationOrderPolicy.localConfigured(false).fastOrder());
			Assert.assertFalse(ExactEliminationOrderPolicy.localConfigured(true).fastOrder());

			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, "true");
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY, "1000000");
			for(boolean compact : new boolean[] {false, true}) {
				ExactEliminationOrderPolicy.Configuration configuration =
					ExactEliminationOrderPolicy.localConfigured(compact);
				Assert.assertTrue(configuration.fastOrder());
				Assert.assertEquals(1_000_000L, configuration.maximumAssignments());
				Assert.assertEquals("common-exact", configuration.source());
			}
		}
		finally { saved.restore(); }
	}

	@Test
	public void defaultsPreservePortfolioAndLegacyRegionalBehavior() {
		Properties saved = new Properties();
		try {
			clear();
			Assert.assertFalse(ExactEliminationOrderPolicy.globalConfigured().fastOrder());
			Assert.assertFalse(ExactEliminationOrderPolicy.localConfigured(false).fastOrder());
			Assert.assertFalse(ExactEliminationOrderPolicy.localConfigured(true).fastOrder());
			System.setProperty(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_PROPERTY, "true");
			Assert.assertTrue(ExactEliminationOrderPolicy.localConfigured(false).fastOrder());
			Assert.assertFalse(ExactEliminationOrderPolicy.localConfigured(true).fastOrder());
		}
		finally { saved.restore(); }
	}

	@Test
	public void malformedOrPartialCommonConfigurationFailsClosed() {
		Properties saved = new Properties();
		try {
			clear();
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, "yes");
			IllegalArgumentException malformed = Assert.assertThrows(IllegalArgumentException.class,
				ExactEliminationOrderPolicy::globalConfigured);
			Assert.assertEquals("EXACT_FAST_ORDER_OPTION_INVALID|value=yes", malformed.getMessage());

			clear();
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY, "100");
			IllegalArgumentException partial = Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactEliminationOrderPolicy.localConfigured(false));
			Assert.assertEquals("EXACT_FAST_ORDER_OPTION_MISSING", partial.getMessage());

			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, "true");
			System.setProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY, "0");
			IllegalArgumentException assignments = Assert.assertThrows(IllegalArgumentException.class,
				ExactEliminationOrderPolicy::globalConfigured);
			Assert.assertEquals("EXACT_FAST_ORDER_ASSIGNMENTS_INVALID|value=0",
				assignments.getMessage());
		}
		finally { saved.restore(); }
	}

	private static void clear() {
		System.clearProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY);
		System.clearProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY);
		System.clearProperty(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_PROPERTY);
		System.clearProperty(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_ASSIGNMENTS_PROPERTY);
	}

	private static final class Properties {
		private final String fast = System.getProperty(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY);
		private final String assignments =
			System.getProperty(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY);
		private final String legacy =
			System.getProperty(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_PROPERTY);
		private final String legacyAssignments = System.getProperty(
			ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_ASSIGNMENTS_PROPERTY);

		void restore() {
			restore(ExactEliminationOrderPolicy.FAST_ORDER_PROPERTY, fast);
			restore(ExactEliminationOrderPolicy.FAST_ORDER_ASSIGNMENTS_PROPERTY, assignments);
			restore(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_PROPERTY, legacy);
			restore(ExactEliminationOrderPolicy.LEGACY_LOCAL_FAST_ORDER_ASSIGNMENTS_PROPERTY,
				legacyAssignments);
		}

		private static void restore(String key, String value) {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		}
	}
}

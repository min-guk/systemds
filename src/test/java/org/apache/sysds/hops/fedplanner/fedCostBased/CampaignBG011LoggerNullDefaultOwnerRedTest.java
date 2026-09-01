/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Contract for removing the duplicate Logger null/default observer helper. */
public class CampaignBG011LoggerNullDefaultOwnerRedTest {
	@Test
	public void duplicateNullDisplayOwnerMustBeRemoved() {
		boolean duplicateOwnerExists = false;
		for(Method method : FederatedPlannerLogger.class.getDeclaredMethods()) {
			if(method.getName().equals("nullDisplay")
				&& method.getReturnType() == String.class
				&& List.of(method.getParameterTypes()).equals(List.of(String.class, String.class))) {
				duplicateOwnerExists = true;
				break;
			}
		}
		Assert.assertFalse("G011_LOGGER_NULLDISPLAY_HELPER_MUST_BE_REMOVED", duplicateOwnerExists);
	}
}

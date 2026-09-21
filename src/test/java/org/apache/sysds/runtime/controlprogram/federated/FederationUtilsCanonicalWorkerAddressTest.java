/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.runtime.controlprogram.federated;

import org.junit.Assert;
import org.junit.Test;

/** Canonical worker identity is derived from the token and must not require DNS. */
public class FederationUtilsCanonicalWorkerAddressTest {
	@Test(timeout = 5_000)
	public void unresolvedContainerWorkerRetainsLexicalEndpoint() {
		String worker = "worker-that-exists-only-inside-the-fed-container.invalid:8123/data/X";
		for(int i = 0; i < 100_000; i++)
			Assert.assertEquals("worker-that-exists-only-inside-the-fed-container.invalid:8123",
				FederationUtils.canonicalFederatedWorkerAddress(worker));
	}
}

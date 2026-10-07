/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class AutomaticSupplySharingDockerProbeArgumentsTest {
	@Test
	public void threeArgumentsPreserveTheFixedDmlInvocation() {
		Assert.assertEquals(List.of(
			"-f", "script.dml", "-config", "config.xml", "-exec", "singlenode",
			"-seed", "7", "-stats", "100", "-noFedRuntimeConversion", "-explain", "runtime"),
			AutomaticSupplySharingDockerProbe.dmlArguments(
				new String[] {"script.dml", "config.xml", "result.json"}));
	}

	@Test
	public void acceptsSafeNamedArgumentsAndEqualsInValues() {
		List<String> arguments = AutomaticSupplySharingDockerProbe.dmlArguments(new String[] {
			"script.dml", "config.xml", "result.json", "-nvargs",
			"MODEL_OUTPUT=/evidence/model.csv", "TOKEN=left=right"});

		Assert.assertEquals(List.of("-nvargs", "MODEL_OUTPUT=/evidence/model.csv", "TOKEN=left=right"),
			arguments.subList(arguments.size() - 3, arguments.size()));
	}

	@Test
	public void rejectsOptionLikeAndMalformedNamedArguments() {
		for(String invalid : List.of("-exec=hybrid", "--config=other.xml", "=value", "NAME=", "", "1NAME=value",
			"BAD-NAME=value"))
			Assert.assertThrows(IllegalArgumentException.class,
				() -> AutomaticSupplySharingDockerProbe.dmlArguments(new String[] {
					"script.dml", "config.xml", "result.json", "-nvargs", invalid}));
	}

	@Test
	public void rejectsMissingOrEmptyNamedArgumentLists() {
		Assert.assertThrows(IllegalArgumentException.class,
			() -> AutomaticSupplySharingDockerProbe.dmlArguments(new String[0]));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> AutomaticSupplySharingDockerProbe.dmlArguments(new String[] {
				"script.dml", "config.xml", "result.json", "-nvargs"}));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> AutomaticSupplySharingDockerProbe.dmlArguments(new String[] {
				"script.dml", "config.xml", "result.json", "MODEL_OUTPUT=x"}));
	}
}

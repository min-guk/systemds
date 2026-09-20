/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.misc;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.utils.Statistics;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class DMLScriptPlanningFullInitialStatisticsTest {
	private boolean oldStatistics;

	@Before
	public void setUp() {
		oldStatistics = DMLScript.STATISTICS;
	}

	@After
	public void tearDown() {
		Statistics.reset();
		DMLScript.STATISTICS = oldStatistics;
	}

	@Test
	public void successfulCompilationPublishesPlanningReceipt() throws Exception {
		Assert.assertTrue(DMLScript.executeScript(new String[] {"-s", "x=1;", "-stats"}));
		Assert.assertTrue(Statistics.getPlanningFullInitialTime() >= 0);
		Assert.assertTrue(Statistics.display(1).contains(
			"PlanningFullInitialReceipt schema=planning-full-initial-v1 Tplanning_full_initial_nanos="));
	}

	@Test
	public void parserFailureDoesNotPublishPlanningReceipt() throws Exception {
		try {
			DMLScript.executeScript(new String[] {"-s", "x = ;", "-stats"});
			Assert.fail("Expected invalid DML to fail during compilation");
		}
		catch(Exception expected) {
			// The failed compile must leave no completed planning receipt.
		}
		Assert.assertEquals(-1, Statistics.getPlanningFullInitialTime());
		Assert.assertFalse(Statistics.display(1).contains("PlanningFullInitialReceipt"));
	}
}

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

package org.apache.sysds.utils;

import org.apache.sysds.api.DMLScript;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StatisticsPlanningFullInitialTest {
	private boolean oldStatistics;

	@Before
	public void setUp() {
		oldStatistics = DMLScript.STATISTICS;
		DMLScript.STATISTICS = true;
		Statistics.reset();
	}

	@After
	public void tearDown() {
		Statistics.reset();
		DMLScript.STATISTICS = oldStatistics;
	}

	@Test
	public void successfulPlanningPublishesExactNanosecondReceipt() {
		Statistics.startPlanningFullInitialTimer();
		Statistics.startCompileTimer();
		Statistics.stopPlanningFullInitialTimer();

		long nanos = Statistics.getPlanningFullInitialTime();
		Assert.assertTrue(nanos >= 0);
		String receipt = "PlanningFullInitialReceipt schema=planning-full-initial-v1 "
			+ "Tplanning_full_initial_nanos=" + nanos;
		Assert.assertEquals(1, occurrences(Statistics.display(1), receipt));
	}

	@Test
	public void newFailedPlanningAttemptDoesNotRetainSuccessReceipt() {
		Statistics.startPlanningFullInitialTimer();
		Statistics.stopPlanningFullInitialTimer();
		Assert.assertTrue(Statistics.getPlanningFullInitialTime() >= 0);

		Statistics.startPlanningFullInitialTimer();
		Assert.assertEquals(-1, Statistics.getPlanningFullInitialTime());
		Assert.assertFalse(Statistics.display(1).contains("PlanningFullInitialReceipt"));
		Statistics.abortPlanningFullInitialTimer();
		Assert.assertFalse(Statistics.display(1).contains("PlanningFullInitialReceipt"));
	}

	@Test
	public void statisticsResetClearsPlanningReceipt() {
		Statistics.startPlanningFullInitialTimer();
		Statistics.stopPlanningFullInitialTimer();
		Assert.assertTrue(Statistics.display(1).contains("PlanningFullInitialReceipt"));

		Statistics.reset();
		Assert.assertEquals(-1, Statistics.getPlanningFullInitialTime());
		Assert.assertFalse(Statistics.display(1).contains("PlanningFullInitialReceipt"));
	}

	private static int occurrences(String value, String needle) {
		int count = 0;
		for(int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length())
			count++;
		return count;
	}
}

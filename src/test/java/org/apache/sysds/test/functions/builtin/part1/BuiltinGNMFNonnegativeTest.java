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

package org.apache.sysds.test.functions.builtin.part1;

import java.util.HashMap;

import org.apache.sysds.runtime.matrix.data.MatrixValue.CellIndex;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class BuiltinGNMFNonnegativeTest extends AutomatedTestBase {
	private static final String TEST_NAME = "GNMFNonnegative";
	private static final String TEST_DIR = "functions/builtin/";
	private static final String TEST_CLASS_DIR = TEST_DIR + BuiltinGNMFNonnegativeTest.class.getSimpleName() + "/";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"W0", "H0", "W", "H"}));
	}

	@Test
	public void factorsRemainFiniteAndNonnegative() {
		loadTestConfiguration(getTestConfiguration(TEST_NAME));
		fullDMLScriptName = SCRIPT_DIR + TEST_DIR + TEST_NAME + ".dml";
		programArgs = new String[] {"-args", output("W0"), output("H0"), output("W"), output("H")};

		runTest(true, false, null, -1);

		assertFiniteAndNonnegative("W0", readDMLMatrixFromOutputDir("W0"));
		assertFiniteAndNonnegative("H0", readDMLMatrixFromOutputDir("H0"));
		assertFiniteAndNonnegative("W", readDMLMatrixFromOutputDir("W"));
		assertFiniteAndNonnegative("H", readDMLMatrixFromOutputDir("H"));
	}

	private static void assertFiniteAndNonnegative(String name, HashMap<CellIndex, Double> matrix) {
		Assert.assertFalse(name + " must not be empty", matrix.isEmpty());
		for(double value : matrix.values()) {
			Assert.assertTrue(name + " must contain only finite values", Double.isFinite(value));
			Assert.assertTrue(name + " must contain no negative values, but found " + value, value >= 0);
		}
	}
}

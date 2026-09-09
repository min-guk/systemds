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

import org.apache.sysds.runtime.matrix.data.MatrixValue.CellIndex;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class BuiltinGNMFSeedTest extends AutomatedTestBase {
	private static final String TEST_NAME = "GNMFSeed";
	private static final String TEST_DIR = "functions/builtin/";
	private static final String TEST_CLASS_DIR = TEST_DIR + BuiltinGNMFSeedTest.class.getSimpleName() + "/";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"same", "different", "unspecified"}));
	}

	@Test
	public void explicitSeedControlsBothInitialFactors() {
		loadTestConfiguration(getTestConfiguration(TEST_NAME));
		fullDMLScriptName = SCRIPT_DIR + TEST_DIR + TEST_NAME + ".dml";
		programArgs = new String[] {"-seed", "1011081480", "-args",
			output("same"), output("different"), output("unspecified")};

		runTest(true, false, null, -1);

		double same = readDMLMatrixFromOutputDir("same").getOrDefault(new CellIndex(1, 1), 0d);
		double different = readDMLMatrixFromOutputDir("different").get(new CellIndex(1, 1));
		double unspecified = readDMLMatrixFromOutputDir("unspecified").get(new CellIndex(1, 1));
		Assert.assertEquals("Identical explicit seeds must reproduce W and H", 0, same, 0);
		Assert.assertTrue("A different explicit seed must change W or H", different > 1e-12);
		Assert.assertTrue("The CLI seed must not silently change the unspecified-seed contract", unspecified > 1e-12);
	}
}

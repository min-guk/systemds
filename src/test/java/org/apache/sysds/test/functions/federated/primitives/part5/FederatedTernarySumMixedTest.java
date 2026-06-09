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

package org.apache.sysds.test.functions.federated.primitives.part5;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class FederatedTernarySumMixedTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/federated/aggregate/";
	private static final String TEST_NAME = "FederatedTernarySumMixedTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + FederatedTernarySumMixedTest.class.getSimpleName() + "/";

	private static final int blocksize = 1024;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"S.scalar"}));
	}

	@Test
	public void testFedTernarySumMixedInputsCP() {
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		Types.ExecMode platformOld = rtplatform;
		rtplatform = Types.ExecMode.SINGLE_NODE;

		Thread t1 = null, t2 = null;

		try {
			getAndLoadTestConfiguration(TEST_NAME);
			String HOME = SCRIPT_DIR + TEST_DIR;

			final int rows = 1000;
			final int cols = 10;
			final int halfRows = rows / 2;

			double[][] A = getRandomMatrix(rows, cols, -1, 1, 1, 42);
			double[][] B = getRandomMatrix(rows, cols, -1, 1, 1, 1337);
			double[][] F = getRandomMatrix(rows, cols, -1, 1, 1, 7);

			double[][] F1 = new double[halfRows][cols];
			double[][] F2 = new double[halfRows][cols];
			for(int i = 0; i < halfRows; i++) {
				System.arraycopy(F[i], 0, F1[i], 0, cols);
				System.arraycopy(F[i + halfRows], 0, F2[i], 0, cols);
			}

			MatrixCharacteristics mcFull = new MatrixCharacteristics(rows, cols, blocksize, (long) rows * cols);
			MatrixCharacteristics mcPart = new MatrixCharacteristics(halfRows, cols, blocksize, (long) halfRows * cols);
			writeInputMatrixWithMTD("A", A, false, mcFull);
			writeInputMatrixWithMTD("B", B, false, mcFull);
			writeInputMatrixWithMTD("F", F, false, mcFull);
			writeInputMatrixWithMTD("F1", F1, false, mcPart);
			writeInputMatrixWithMTD("F2", F2, false, mcPart);

			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			t1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			t2 = startLocalFedWorkerThread(port2);

			// reference (all local)
			fullDMLScriptName = HOME + TEST_NAME + "Reference.dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"in_A=" + input("A"),
				"in_B=" + input("B"),
				"in_F=" + input("F"),
				"out_S=" + expected("S")};
			runTest(true, false, null, -1);

			// federated (only F is federated)
			fullDMLScriptName = HOME + TEST_NAME + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"in_A=" + input("A"),
				"in_B=" + input("B"),
				"in_F1=" + TestUtils.federatedAddress(port1, input("F1")),
				"in_F2=" + TestUtils.federatedAddress(port2, input("F2")),
				"rows=" + rows,
				"cols=" + cols,
				"out_S=" + output("S")};
			runTest(true, false, null, -1);

			compareResults(1e-9);
		}
		finally {
			TestUtils.shutdownThreads(t1, t2);
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
		}
	}

}

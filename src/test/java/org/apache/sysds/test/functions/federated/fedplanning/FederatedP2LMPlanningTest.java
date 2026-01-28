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

package org.apache.sysds.test.functions.federated.fedplanning;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

@net.jcip.annotations.NotThreadSafe
public class FederatedP2LMPlanningTest extends AutomatedTestBase {
	private static final Log LOG = LogFactory.getLog(FederatedP2LMPlanningTest.class.getName());

	private final static String TEST_DIR = "functions/federated/fedplanning/";
	private final static String TEST_NAME = "P2_LM";
	private final static String TEST_CLASS_DIR = TEST_DIR + FederatedP2LMPlanningTest.class.getSimpleName() + "/";
	private static File TEST_CONF_FILE;

	private final static int blocksize = 1024;
	public final int rows = 1000;
	public final int cols = 15; // Features for tabular data
	public final boolean verbose = false;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] { "Z" }));
	}

	@Ignore
	@Test
	public void runP2LMFOUTTest() {
		runTestWithConfig("SystemDS-config-fout.xml", null);
	}

	@Test
	public void runP2LMHeuristicTest() {
		runTestWithConfig("SystemDS-config-heuristic.xml", null);
	}

	@Ignore
	@Test
	public void runP2LMCostBasedTestPrivate() {
		runTestWithConfig("SystemDS-config-cost-based.xml", "private");
	}

	@Test
	public void runP2LMCostBasedTestPrivateAggregate() {
		runTestWithConfig("SystemDS-config-cost-based.xml", "private-aggregate");
	}

	@Ignore
	@Test
	public void runP2LMCostBasedTestPublic() {
		runTestWithConfig("SystemDS-config-cost-based.xml", "public");
	}

	@Test
	public void runRuntimeTest() {
		TEST_CONF_FILE = new File("src/test/config/SystemDS-config.xml");
		loadAndRunTest(new String[] {}, TEST_NAME, null);
	}

	private void runTestWithConfig(String configFile, String privacyConstraints) {
		TEST_CONF_FILE = new File(SCRIPT_DIR + "functions/privacy/fedplanning/", configFile);
		loadAndRunTest(new String[] {}, TEST_NAME, privacyConstraints);
	}

	private void writeInputMatrices(String privacyConstraints) {
		// Generate tabular data similar to P2_LM.dml expectations
		double[][] features = generateTabularFeatures();
		double[][] labels = generateRegressionLabels();

		// Write full matrices instead of federated splits (like P2_FFN test)
		writeStandardMatrix("features", features, privacyConstraints);
		writeStandardMatrix("labels", labels, privacyConstraints);
	}

	private double[][] generateTabularFeatures() {
		double[][] features = getRandomMatrix(rows, cols, -5, 5, 0.7, 789);
		// Make first column categorical (0 or 1) for the dummycode transformation
		for (int i = 0; i < rows; i++) {
			features[i][0] = (features[i][0] > 0) ? 1.0 : 0.0;
		}
		return features;
	}

	private double[][] generateRegressionLabels() {
		// Generate continuous labels for linear regression
		return getRandomMatrix(rows, 1, -10, 10, 1, 987);
	}

	private void writeStandardMatrix(String matrixName, double[][] matrix, String privacyConstraints) {
		MatrixCharacteristics mc = new MatrixCharacteristics(matrix.length, matrix[0].length, blocksize,
				(long) matrix.length * matrix[0].length);
		if (privacyConstraints == null) {
			writeInputMatrixWithMTD(matrixName, matrix, false, mc);
		} else {
			writeInputMatrixWithMTD(matrixName, matrix, false, mc, privacyConstraints);
		}
	}

	private void loadAndRunTest(String[] expectedHeavyHitters, String testName, String privacyConstraints) {

		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		Types.ExecMode platformOld = rtplatform;
		rtplatform = Types.ExecMode.SINGLE_NODE;

		Thread t1 = null, t2 = null;

		try {
			getAndLoadTestConfiguration(testName);
			String HOME = SCRIPT_DIR + TEST_DIR;

			writeInputMatrices(privacyConstraints);

			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			t1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			t2 = startLocalFedWorkerThread(port2);

			// Run actual dml script with regular file paths (like P2_FFN test)
			fullDMLScriptName = HOME + testName + ".dml";
			programArgs = new String[] { "-stats", "-nvargs",
					"1=" + input("features"),
					"2=" + input("labels"),
					"3=" + (verbose ? "TRUE" : "FALSE"),
					"4=" + output("Z") };
			runTest(true, false, null, -1);
		} finally {
			TestUtils.shutdownThreads(t1, t2);
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
		}
	}

	/**
	 * Override default configuration with custom test configuration to ensure
	 * scratch space and local temporary directory locations are also updated.
	 */
	@Override
	protected File getConfigTemplateFile() {
		// Instrumentation in this test's output log to show custom configuration file
		// used for template.
		LOG.info("This test case overrides default configuration with " + TEST_CONF_FILE.getPath());
		return TEST_CONF_FILE;
	}
}

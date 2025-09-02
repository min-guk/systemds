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
public class FederatedP2FFNPlanningTest extends AutomatedTestBase {
	private static final Log LOG = LogFactory.getLog(FederatedP2FFNPlanningTest.class.getName());

	private final static String TEST_DIR = "functions/privacy/fedplanning/";
	private final static String TEST_NAME = "P2_FFN";
	private final static String TEST_CLASS_DIR = TEST_DIR + FederatedP2FFNPlanningTest.class.getSimpleName() + "/";
	private static File TEST_CONF_FILE;

	private final static int blocksize = 1024;
	public final int rows = 500;
	public final int cols = 20; // Features for tabular data
	public final int epochs = 2;
	public final int batch_size = 32;
	public final double eta = 0.01;
	public final int numWorkers = 2;
	public final String utype = "BSP";
	public final String freq = "EPOCH";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"Z"}));
	}

	@Ignore
	@Test
	public void runP2FFNFOUTTest(){
		runTestWithConfig("SystemDS-config-fout.xml", null);
	}

	@Test
	public void runP2FFNHeuristicTest(){
		runTestWithConfig("SystemDS-config-heuristic.xml", null);
	}

	@Ignore
	@Test
	public void runP2FFNCostBasedTestPrivate(){
		runTestWithConfig("SystemDS-config-cost-based.xml", "private");
	}

	@Ignore
	@Test
	public void runP2FFNCostBasedTestPrivateAggregate(){
		runTestWithConfig("SystemDS-config-cost-based.xml", "private-aggregate");
	}

	@Ignore
	@Test
	public void runP2FFNCostBasedTestPublic(){
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

	private void writeInputMatrices(String privacyConstraints){
		// Generate federated features split across 2 workers
		writeStandardRowFedMatrix("features1", 123, privacyConstraints);
		writeStandardRowFedMatrix("features2", 124, privacyConstraints);
		
		// Generate federated labels split across 2 workers
		writeStandardRowFedMatrix("labels1", 456, privacyConstraints);
		writeStandardRowFedMatrix("labels2", 457, privacyConstraints);
		
		// Generate federated test data split across 2 workers
		writeStandardRowFedMatrix("X_test1", 789, privacyConstraints);
		writeStandardRowFedMatrix("X_test2", 790, privacyConstraints);
		writeStandardRowFedMatrix("y_test1", 101, privacyConstraints);
		writeStandardRowFedMatrix("y_test2", 102, privacyConstraints);
	}

	private double[][] generateTabularFeatures() {
		return generateTabularFeatures(rows, 123);
	}

	private double[][] generateTabularFeatures(int numRows, long seed) {
		double[][] features = getRandomMatrix(numRows, cols, 0, 10, 1, seed);
		// Make first column categorical (0 or 1)
		for(int i = 0; i < numRows; i++) {
			features[i][0] = (features[i][0] > 5) ? 1.0 : 0.0;
		}
		return features;
	}

	private double[][] generateRegressionLabels() {
		return generateRegressionLabels(rows, 456);
	}

	private double[][] generateRegressionLabels(int numRows, long seed) {
		// Generate continuous labels for regression
		return getRandomMatrix(numRows, 1, -5, 5, 1, seed);
	}

	private void writeStandardMatrix(String matrixName, double[][] matrix, String privacyConstraints){
		MatrixCharacteristics mc = new MatrixCharacteristics(matrix.length, matrix[0].length, blocksize, 
			(long) matrix.length * matrix[0].length);
		if (privacyConstraints == null) {
			writeInputMatrixWithMTD(matrixName, matrix, false, mc);
		} else {
			writeInputMatrixWithMTD(matrixName, matrix, false, mc, privacyConstraints);
		}
	}

	private void writeStandardRowFedMatrix(String matrixName, long seed, String privacyConstraints){
		if (matrixName.startsWith("features") || matrixName.startsWith("X_test")) {
			// Features data: rows/2 x cols
			double[][] matrix = generateTabularFeatures(rows / 2, seed);
			writeStandardMatrix(matrixName, matrix, privacyConstraints);
		} else if (matrixName.startsWith("labels") || matrixName.startsWith("y_test")) {
			// Labels data: rows/2 x 1
			double[][] matrix = generateRegressionLabels(rows / 2, seed);
			writeStandardMatrix(matrixName, matrix, privacyConstraints);
		}
	}

	private void writeStandardRowFedMatrix(String matrixName, double[][] fullMatrix, int startRow, int endRow, String privacyConstraints){
		int numRows = endRow - startRow;
		double[][] matrix = new double[numRows][fullMatrix[0].length];
		for(int i = 0; i < numRows; i++) {
			System.arraycopy(fullMatrix[startRow + i], 0, matrix[i], 0, fullMatrix[0].length);
		}
		writeStandardMatrix(matrixName, matrix, privacyConstraints);
	}

	private void loadAndRunTest(String[] expectedHeavyHitters, String testName, String privacyConstraints){

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

			// Run actual dml script with federated matrix
			fullDMLScriptName = HOME + testName + ".dml";
			programArgs = new String[] { "-stats", "-nvargs",
				"features1=" + TestUtils.federatedAddress(port1, input("features1")),
				"features2=" + TestUtils.federatedAddress(port2, input("features2")),
				"labels1=" + TestUtils.federatedAddress(port1, input("labels1")),
				"labels2=" + TestUtils.federatedAddress(port2, input("labels2")),
				"X_test1=" + TestUtils.federatedAddress(port1, input("X_test1")),
				"X_test2=" + TestUtils.federatedAddress(port2, input("X_test2")),
				"y_test1=" + TestUtils.federatedAddress(port1, input("y_test1")),
				"y_test2=" + TestUtils.federatedAddress(port2, input("y_test2")),
				"r=" + rows, "c=" + cols,
				"epochs=" + epochs, "batch_size=" + batch_size, "eta=" + eta,
				"numWorkers=" + numWorkers, "utype=" + utype, "freq=" + freq, "Z=" + output("Z")};
			runTest(true, false, null, -1);
		}
		finally {
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
		// Instrumentation in this test's output log to show custom configuration file used for template.
		LOG.info("This test case overrides default configuration with " + TEST_CONF_FILE.getPath());
		return TEST_CONF_FILE;
	}
}
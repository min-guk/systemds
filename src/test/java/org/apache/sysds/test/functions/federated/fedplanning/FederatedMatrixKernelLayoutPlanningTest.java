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

import java.io.File;
import java.io.IOException;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalForcedStateAudit;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** End-to-end ROW/COL witnesses for MMFED, TSMM, and MMChain kernels. */
@net.jcip.annotations.NotThreadSafe
public class FederatedMatrixKernelLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_MAPMM = "FederatedMatrixKernelLayoutPlanningTestMapMM";
	private static final String TEST_TSMM = "FederatedMatrixKernelLayoutPlanningTestTsmmChain";
	private static final String TEST_CHAIN = "FederatedMatrixKernelLayoutPlanningTestMMChain";
	private static final String TEST_CLASS_DIR = TEST_DIR + "FederatedMatrixKernelLayoutPlanningTest/";
	private static final int ROWS = 12;
	private static final int COLS = 10;
	private static final int OUT_COLS = 8;
	private static final int BLOCKSIZE = 1000;
	private static final int LARGE_ROWS = 2000;
	private static final int LARGE_COLS = 1000;
	private boolean runtimePlannerConfig;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_MAPMM, new TestConfiguration(TEST_CLASS_DIR, TEST_MAPMM,
			new String[] {"RowMM", "ColMV", "LeftVM"}));
		addTestConfiguration(TEST_MAPMM + "SparseLarge", new TestConfiguration(TEST_CLASS_DIR,
			TEST_MAPMM + "SparseLarge", new String[] {"RowMM"}));
		addTestConfiguration(TEST_TSMM, new TestConfiguration(TEST_CLASS_DIR, TEST_TSMM,
			new String[] {"TsmmLeft", "TsmmRight", "TsmmWrongCol", "TsmmWrongRow"}));
		addTestConfiguration(TEST_CHAIN, new TestConfiguration(TEST_CLASS_DIR, TEST_CHAIN,
			new String[] {"Chain", "WChain", "ColChain"}));
	}

	@Test
	public void sparkExecutesMMFedForRowAndCol() {
		ExecMode oldPlatform = rtplatform;
		boolean oldSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		try {
			rtplatform = ExecMode.SPARK;
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;
			runMapMM();
		}
		finally {
			rtplatform = oldPlatform;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldSpark;
		}
	}

	/**
	 * Runtime-planner witness for the legacy SP MapMM-to-MMFED conversion frontier.
	 * The compiled-planner tests above intentionally prove CP/FED selector authority instead;
	 * this fixture independently keeps the specialized runtime kernel represented in R.
	 */
	@Test
	public void runtimePlannerExecutesSparseLargeMapMMThroughMMFed() {
		ExecMode oldPlatform = rtplatform;
		boolean oldSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		long oldMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		runtimePlannerConfig = true;
		try {
			rtplatform = ExecMode.SPARK;
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024);
			runSparseLargeMapMM();
		}
		finally {
			runtimePlannerConfig = false;
			InfrastructureAnalyzer.setLocalMaxMemory(oldMemory);
			rtplatform = oldPlatform;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldSpark;
		}
	}

	@Test
	public void fedAllExecutesTsmmForRowAndCol() {
		ExecMode oldPlatform = rtplatform;
		try {
			rtplatform = ExecMode.SINGLE_NODE;
			runTsmm();
		}
		finally {
			rtplatform = oldPlatform;
		}
	}

	@Test
	public void fedAllExecutesMMChainForRowAndCol() {
		ExecMode oldPlatform = rtplatform;
		try {
			rtplatform = ExecMode.SINGLE_NODE;
			runMMChain();
		}
		finally {
			rtplatform = oldPlatform;
		}
	}

	private void runMapMM() {
		getAndLoadTestConfiguration(TEST_MAPMM);
		writeInputs();
		Thread[] workers = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			workers = new Thread[] {startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S),
				startLocalFedWorkerThread(port2)};
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_MAPMM + ".dml";
			programArgs = commonFederatedArgs(port1, port2, new String[] {
				"B=" + input("B"), "V=" + input("V"), "Left=" + input("Left"),
				"RowMM=" + output("RowMM"), "ColMV=" + output("ColMV"),
				"LeftVM=" + output("LeftVM")});
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_MAPMM + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"), "B=" + input("B"),
				"V=" + input("V"), "Left=" + input("Left"),
				"RowMM=" + expected("RowMM"), "ColMV=" + expected("ColMV"),
				"LeftVM=" + expected("LeftVM")};
			runTest(true, false, null, -1);
			compareResults(1e-9);
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected federated matrix multiplication",
					heavyHittersContainsString("fed_ba+*") || heavyHittersContainsString("fed_mapmm"));
		}
		finally {
			TestUtils.shutdownThreads(workers);
		}
	}

	private void runSparseLargeMapMM() {
		String name = TEST_MAPMM + "SparseLarge";
		getAndLoadTestConfiguration(name);
		double[][] x = getRandomMatrix(LARGE_ROWS, LARGE_COLS, 0.1, 1.0, 0.001, 631L);
		double[][] b = getRandomMatrix(LARGE_COLS, OUT_COLS, 0.1, 1.0, 0.5, 633L);
		writePublicMatrix("LargeX", x);
		writePublicMatrix("LargeXR1", rows(x, 0, LARGE_ROWS / 2));
		writePublicMatrix("LargeXR2", rows(x, LARGE_ROWS / 2, LARGE_ROWS));
		writePublicMatrix("LargeB", b);
		Thread[] workers = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			workers = new Thread[] {startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S),
				startLocalFedWorkerThread(port2)};
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + name + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"XR1=" + TestUtils.federatedAddress(port1, input("LargeXR1")),
				"XR2=" + TestUtils.federatedAddress(port2, input("LargeXR2")),
				"B=" + input("LargeB"), "r=" + LARGE_ROWS, "c=" + LARGE_COLS,
				"RowMM=" + output("RowMM")};
			runTest(true, false, null, -1);
			Assert.assertTrue("Expected specialized SP MapMM-to-MMFED runtime conversion",
				heavyHittersContainsString("fed_mapmm"));

			rtplatform = ExecMode.SINGLE_NODE;
			InfrastructureAnalyzer.setLocalMaxMemory(oldUnrestrictedMemory());
			fullDMLScriptName = home + name + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("LargeX"),
				"B=" + input("LargeB"), "RowMM=" + expected("RowMM")};
			runTest(true, false, null, -1);
			compareResults(1e-8);
		}
		finally {
			TestUtils.shutdownThreads(workers);
		}
	}

	private static long oldUnrestrictedMemory() {
		return Math.max(512L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2);
	}

	private void runTsmm() {
		getAndLoadTestConfiguration(TEST_TSMM);
		writeInputs();
		Thread[] workers = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			workers = new Thread[] {startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S),
				startLocalFedWorkerThread(port2)};
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_TSMM + ".dml";
			programArgs = commonFederatedArgs(port1, port2, new String[] {
				"TsmmLeft=" + output("TsmmLeft"), "TsmmRight=" + output("TsmmRight"),
				"TsmmWrongCol=" + output("TsmmWrongCol"),
				"TsmmWrongRow=" + output("TsmmWrongRow")});
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_TSMM + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"),
				"TsmmLeft=" + expected("TsmmLeft"),
				"TsmmRight=" + expected("TsmmRight"),
				"TsmmWrongCol=" + expected("TsmmWrongCol"),
				"TsmmWrongRow=" + expected("TsmmWrongRow")};
			runTest(true, false, null, -1);
			compareResults(1e-8);
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected federated TSMM kernel",
					heavyHittersContainsString("fed_ba+*") || heavyHittersContainsString("fed_tsmm"));
		}
		finally {
			TestUtils.shutdownThreads(workers);
		}
	}

	private void runMMChain() {
		getAndLoadTestConfiguration(TEST_CHAIN);
		writeInputs();
		Thread[] workers = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			workers = new Thread[] {startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S),
				startLocalFedWorkerThread(port2)};
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_CHAIN + ".dml";
			programArgs = commonFederatedArgs(port1, port2, new String[] {
				"V=" + input("V"), "W=" + input("W"), "Chain=" + output("Chain"),
				"WChain=" + output("WChain"), "ColChain=" + output("ColChain")});
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_CHAIN + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"), "V=" + input("V"),
				"W=" + input("W"), "Chain=" + expected("Chain"),
				"WChain=" + expected("WChain"), "ColChain=" + expected("ColChain")};
			runTest(true, false, null, -1);
			compareResults(1e-8);
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected federated MMChain kernel",
					heavyHittersContainsString("fed_ba+*") || heavyHittersContainsString("fed_mmchain"));
		}
		finally {
			TestUtils.shutdownThreads(workers);
		}
	}

	private void writeInputs() {
		double[][] x = getRandomMatrix(ROWS, COLS, 0.1, 1.0, 1, 601L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2));
		writePublicMatrix("XC2", cols(x, COLS / 2, COLS));
		writePublicMatrix("B", getRandomMatrix(COLS, OUT_COLS, 0.1, 1.0, 1, 607L));
		writePublicMatrix("V", getRandomMatrix(COLS, 1, 0.1, 1.0, 1, 613L));
		writePublicMatrix("Left", getRandomMatrix(1, ROWS, 0.1, 1.0, 1, 617L));
		writePublicMatrix("W", getRandomMatrix(ROWS, 1, 0.1, 1.0, 1, 619L));
	}

	private String[] commonFederatedArgs(int port1, int port2, String[] tail) {
		String[] args = new String[9 + tail.length];
		String[] head = {"-stats", "100", "-nvargs",
			"XR1=" + TestUtils.federatedAddress(port1, input("XR1")),
			"XR2=" + TestUtils.federatedAddress(port2, input("XR2")),
			"XC1=" + TestUtils.federatedAddress(port1, input("XC1")),
			"XC2=" + TestUtils.federatedAddress(port2, input("XC2")),
			"r=" + ROWS, "c=" + COLS};
		System.arraycopy(head, 0, args, 0, head.length);
		System.arraycopy(tail, 0, args, head.length, tail.length);
		return args;
	}

	private void writePublicMatrix(String name, double[][] values) {
		MatrixBlock block = DataConverter.convertToMatrixBlock(values);
		MatrixCharacteristics characteristics = new MatrixCharacteristics(
			values.length, values[0].length, BLOCKSIZE, block.getNonZeros());
		writeBinaryWithMTD(name, block, characteristics);
		try {
			HDFSTool.writeMetaDataFile(baseDirectory + INPUT_DIR + name + ".mtd",
				ValueType.FP64, null, DataType.MATRIX, characteristics,
				FileFormat.BINARY, null, "public");
		}
		catch(IOException ex) {
			throw new RuntimeException("Unable to write public test metadata", ex);
		}
	}

	private static double[][] rows(double[][] input, int from, int to) {
		double[][] result = new double[to - from][input[0].length];
		for(int i = from; i < to; i++)
			System.arraycopy(input[i], 0, result[i - from], 0, input[i].length);
		return result;
	}

	private static double[][] cols(double[][] input, int from, int to) {
		double[][] result = new double[input.length][to - from];
		for(int i = 0; i < input.length; i++)
			System.arraycopy(input[i], from, result[i], 0, to - from);
		return result;
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR,
			runtimePlannerConfig ? "SystemDS-config-runtime.xml" : "SystemDS-config-fout.xml");
	}
}

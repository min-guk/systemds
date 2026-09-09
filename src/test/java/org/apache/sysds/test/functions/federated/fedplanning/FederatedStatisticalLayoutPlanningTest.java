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

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalForcedStateAudit;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Layout and output-residency witnesses for central moment, covariance, and ctable. */
@net.jcip.annotations.NotThreadSafe
public class FederatedStatisticalLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedStatisticalLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String[] OUTPUTS = {
		"CMRow", "CMCol", "CMFull", "CMBroadcast",
		"CovRow", "CovCol", "CovFull", "CovBroadcast",
		"CTRowForward", "CTRowReverse", "CTRowOverlap",
		"CTCol", "CTFull", "CTBroadcast"
	};
	private static final int ROWS = 12;
	private static final int COLS = 1;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, OUTPUTS));
	}

	@Test
	public void executeStatisticalOperatorsAcrossConcreteLayouts() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = TestUtils.floor(getRandomMatrix(ROWS, COLS, 1, 5, 1, 701L));
		double[][] y = TestUtils.floor(getRandomMatrix(ROWS, COLS, 1, 7, 1, 709L));
		writePublicMatrix("X", x);
		writePublicMatrix("Y", y);
		writeLayoutInputs("X", x);
		writeLayoutInputs("Y", y);

		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);

			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_NAME + ".dml";
			programArgs = federatedArgs(port1, port2);
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_NAME + "Reference.dml";
			programArgs = referenceArgs();
			runTest(true, false, null, -1);

			compareResults(1e-9);
			if(!ExactPhysicalForcedStateAudit.isActive()) {
				Assert.assertTrue("Expected federated central moment", heavyHittersContainsString("fed_cm"));
				Assert.assertTrue("Expected federated covariance", heavyHittersContainsString("fed_cov"));
				Assert.assertTrue("Expected federated ctable", heavyHittersContainsString("fed_ctable"));
			}
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private void writeLayoutInputs(String prefix, double[][] values) {
		writePublicMatrix(prefix + "R1", rows(values, 0, ROWS / 2));
		writePublicMatrix(prefix + "R2", rows(values, ROWS / 2, ROWS));
		double[][] transposed = transpose(values);
		writePublicMatrix(prefix + "C1", cols(transposed, 0, ROWS / 2));
		writePublicMatrix(prefix + "C2", cols(transposed, ROWS / 2, ROWS));
		writePublicMatrix(prefix + "F", values);
		writePublicMatrix(prefix + "B1", values);
		writePublicMatrix(prefix + "B2", values);
	}

	private String[] federatedArgs(int port1, int port2) {
		return new String[] {"-stats", "100", "-nvargs",
			"XR1=" + TestUtils.federatedAddress(port1, input("XR1")),
			"XR2=" + TestUtils.federatedAddress(port2, input("XR2")),
			"XC1=" + TestUtils.federatedAddress(port1, input("XC1")),
			"XC2=" + TestUtils.federatedAddress(port2, input("XC2")),
			"XF=" + TestUtils.federatedAddress(port1, input("XF")),
			"XB1=" + TestUtils.federatedAddress(port1, input("XB1")),
			"XB2=" + TestUtils.federatedAddress(port2, input("XB2")),
			"YR1=" + TestUtils.federatedAddress(port1, input("YR1")),
			"YR2=" + TestUtils.federatedAddress(port2, input("YR2")),
			"YC1=" + TestUtils.federatedAddress(port1, input("YC1")),
			"YC2=" + TestUtils.federatedAddress(port2, input("YC2")),
			"YF=" + TestUtils.federatedAddress(port1, input("YF")),
			"YB1=" + TestUtils.federatedAddress(port1, input("YB1")),
			"YB2=" + TestUtils.federatedAddress(port2, input("YB2")),
			"rows=" + ROWS, "cols=" + COLS,
			"CMRow=" + output("CMRow"), "CMCol=" + output("CMCol"),
			"CMFull=" + output("CMFull"), "CMBroadcast=" + output("CMBroadcast"),
			"CovRow=" + output("CovRow"), "CovCol=" + output("CovCol"),
			"CovFull=" + output("CovFull"), "CovBroadcast=" + output("CovBroadcast"),
			"CTRowForward=" + output("CTRowForward"), "CTRowReverse=" + output("CTRowReverse"),
			"CTRowOverlap=" + output("CTRowOverlap"), "CTCol=" + output("CTCol"),
			"CTFull=" + output("CTFull"), "CTBroadcast=" + output("CTBroadcast")};
	}

	private String[] referenceArgs() {
		return new String[] {"-nvargs", "X=" + input("X"), "Y=" + input("Y"),
			"CMRow=" + expected("CMRow"), "CMCol=" + expected("CMCol"),
			"CMFull=" + expected("CMFull"), "CMBroadcast=" + expected("CMBroadcast"),
			"CovRow=" + expected("CovRow"), "CovCol=" + expected("CovCol"),
			"CovFull=" + expected("CovFull"), "CovBroadcast=" + expected("CovBroadcast"),
			"CTRowForward=" + expected("CTRowForward"), "CTRowReverse=" + expected("CTRowReverse"),
			"CTRowOverlap=" + expected("CTRowOverlap"), "CTCol=" + expected("CTCol"),
			"CTFull=" + expected("CTFull"), "CTBroadcast=" + expected("CTBroadcast")};
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

	private static double[][] transpose(double[][] input) {
		double[][] result = new double[input[0].length][input.length];
		for(int i = 0; i < input.length; i++)
			for(int j = 0; j < input[0].length; j++)
				result[j][i] = input[i][j];
		return result;
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

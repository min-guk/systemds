/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
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

/** Direct ROW, COL, and FULL runtime witnesses for append and matrix reshape. */
@net.jcip.annotations.NotThreadSafe
public class FederatedAppendReshapeLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedAppendReshapeLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String[] OUTPUTS = {
		"RBindRow", "CBindRow", "RBindCol", "CBindCol", "CBindFull",
		"ReshapeRow", "ReshapeCol", "ReshapeFull"
	};
	private static final int ROWS = 12;
	private static final int COLS = 8;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, OUTPUTS));
	}

	@Test
	public void fedAllExecutesAppendAndReshapeLayouts() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = getRandomMatrix(ROWS, COLS, 1, 7, 1, 607L);
		double[][] y = getRandomMatrix(ROWS, COLS, 1, 7, 1, 613L);
		double[][] local = getRandomMatrix(ROWS, 2, 1, 7, 1, 617L);
		writePublicMatrix("X", x);
		writePublicMatrix("Y", y);
		writePublicMatrix("L", local);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("YR1", rows(y, 0, ROWS / 2));
		writePublicMatrix("YR2", rows(y, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2));
		writePublicMatrix("XC2", cols(x, COLS / 2, COLS));
		writePublicMatrix("YC1", cols(y, 0, COLS / 2));
		writePublicMatrix("YC2", cols(y, COLS / 2, COLS));
		writePublicMatrix("XF", x);

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
				Assert.assertTrue("Expected direct federated append",
					heavyHittersContainsString("fed_append"));
				Assert.assertTrue("Expected direct federated reshape",
					heavyHittersContainsString("fed_rshape"));
			}
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private String[] federatedArgs(int port1, int port2) {
		return new String[] {"-stats", "100", "-nvargs", "r=" + ROWS, "c=" + COLS,
			"XR1=" + fed(port1, "XR1"), "XR2=" + fed(port2, "XR2"),
			"YR1=" + fed(port1, "YR1"), "YR2=" + fed(port2, "YR2"),
			"XC1=" + fed(port1, "XC1"), "XC2=" + fed(port2, "XC2"),
			"YC1=" + fed(port1, "YC1"), "YC2=" + fed(port2, "YC2"),
			"XF=" + fed(port1, "XF"), "L=" + input("L"),
			"RBindRow=" + output("RBindRow"), "CBindRow=" + output("CBindRow"),
			"RBindCol=" + output("RBindCol"), "CBindCol=" + output("CBindCol"),
			"CBindFull=" + output("CBindFull"), "ReshapeRow=" + output("ReshapeRow"),
			"ReshapeCol=" + output("ReshapeCol"), "ReshapeFull=" + output("ReshapeFull")};
	}

	private String[] referenceArgs() {
		return new String[] {"-nvargs", "X=" + input("X"), "Y=" + input("Y"),
			"L=" + input("L"), "RBindRow=" + expected("RBindRow"),
			"CBindRow=" + expected("CBindRow"), "RBindCol=" + expected("RBindCol"),
			"CBindCol=" + expected("CBindCol"), "CBindFull=" + expected("CBindFull"),
			"ReshapeRow=" + expected("ReshapeRow"), "ReshapeCol=" + expected("ReshapeCol"),
			"ReshapeFull=" + expected("ReshapeFull")};
	}

	private String fed(int port, String name) {
		return TestUtils.federatedAddress(port, input(name));
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
		for(int row = from; row < to; row++)
			System.arraycopy(input[row], 0, result[row - from], 0, input[row].length);
		return result;
	}

	private static double[][] cols(double[][] input, int from, int to) {
		double[][] result = new double[input.length][to - from];
		for(int row = 0; row < input.length; row++)
			System.arraycopy(input[row], from, result[row], 0, to - from);
		return result;
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

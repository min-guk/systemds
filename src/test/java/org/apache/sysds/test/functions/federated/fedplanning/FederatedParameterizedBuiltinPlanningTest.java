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
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class FederatedParameterizedBuiltinPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedParameterizedBuiltinPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final int ROWS = 20;
	private static final int COLS = 12;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"S"}));
	}

	@Test
	public void fedAllExecutesParameterizedBuiltinWithNamedInputs() {
		runNamedInputCase(true);
	}

	@Test
	public void fedAllExecutesColumnParameterizedBuiltinWithNamedInputs() {
		runNamedInputCase(false);
	}

	private void runNamedInputCase(boolean rowPartitioned) {
		getAndLoadTestConfiguration(TEST_NAME);
		writePartition("X1", 42, rowPartitioned, true);
		writePartition("X2", 1340, rowPartitioned, false);

		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);

			String home = SCRIPT_DIR + TEST_DIR;
			String script = TEST_NAME + (rowPartitioned ? "" : "Col");
			fullDMLScriptName = home + script + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"X1=" + TestUtils.federatedAddress(port1, input("X1")),
				"X2=" + TestUtils.federatedAddress(port2, input("X2")),
				"r=" + ROWS, "c=" + COLS, "S=" + output("S")};
			runTest(true, false, null, -1);

			fullDMLScriptName = home + script + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X1=" + input("X1"),
				"X2=" + input("X2"), "S=" + expected("S")};
			runTest(true, false, null, -1);

			compareResults(1e-9);
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected ParameterizedBuiltinFEDInstruction",
					heavyHittersContainsString("fed_rmempty"));
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private void writePartition(String name, long seed, boolean rowPartitioned,
		boolean clearDimension) {
		int rows = rowPartitioned ? ROWS / 2 : ROWS;
		int cols = rowPartitioned ? COLS : COLS / 2;
		double[][] matrix = getRandomMatrix(rows, cols, 1, 5, 1, seed);
		if(clearDimension && rowPartitioned) {
			java.util.Arrays.fill(matrix[1], 0);
			java.util.Arrays.fill(matrix[3], 0);
		}
		else if(clearDimension) {
			for(double[] row : matrix)
				row[1] = 0;
		}
		MatrixCharacteristics characteristics = new MatrixCharacteristics(
			rows, cols, BLOCKSIZE, -1);
		writeBinaryWithMTD(name, DataConverter.convertToMatrixBlock(matrix), characteristics);
		try {
			HDFSTool.writeMetaDataFile(baseDirectory + INPUT_DIR + name + ".mtd",
				ValueType.FP64, null, DataType.MATRIX, characteristics,
				FileFormat.BINARY, null, "public");
		}
		catch(IOException ex) {
			throw new RuntimeException("Unable to write public test metadata", ex);
		}
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

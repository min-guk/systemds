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

/**
 * End-to-end witnesses for ordered matrix/matrix and matrix/vector binary placement rows.
 * The fixture intentionally exposes ROW, COL, coordinator-local, and mixed-layout inputs in
 * one planner invocation so a forced-state campaign can test P and R in the same attempt.
 */
@net.jcip.annotations.NotThreadSafe
public class FederatedBinaryLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedBinaryLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String[] OUTPUTS = {
		"RR", "CC", "RL", "LR", "CL", "LC",
		"RRV", "CCV", "RC", "CR"
	};
	private static final int ROWS = 12;
	private static final int COLS = 12;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, OUTPUTS));
	}

	@Test
	public void fedAllExecutesOrderedBinaryLayoutTransitions() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = getRandomMatrix(ROWS, COLS, 1, 5, 1, 317L);
		double[][] y = getRandomMatrix(ROWS, COLS, 1, 5, 1, 331L);
		double[][] local = getRandomMatrix(ROWS, COLS, 1, 5, 1, 347L);
		double[][] rowVector = getRandomMatrix(1, COLS, 1, 5, 1, 349L);
		double[][] colVector = getRandomMatrix(ROWS, 1, 1, 5, 1, 353L);

		writePublicMatrix("X", x);
		writePublicMatrix("Y", y);
		writePublicMatrix("L", local);
		writePublicMatrix("RV", rowVector);
		writePublicMatrix("CV", colVector);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("YR1", rows(y, 0, ROWS / 2));
		writePublicMatrix("YR2", rows(y, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2));
		writePublicMatrix("XC2", cols(x, COLS / 2, COLS));
		writePublicMatrix("YC1", cols(y, 0, COLS / 2));
		writePublicMatrix("YC2", cols(y, COLS / 2, COLS));

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
				Assert.assertTrue("Expected federated binary addition",
					heavyHittersContainsString("fed_+"));
				Assert.assertTrue("Expected federated binary subtraction",
					heavyHittersContainsString("fed_-"));
			}
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private String[] federatedArgs(int port1, int port2) {
		return new String[] {"-stats", "100", "-nvargs",
			"XR1=" + TestUtils.federatedAddress(port1, input("XR1")),
			"XR2=" + TestUtils.federatedAddress(port2, input("XR2")),
			"YR1=" + TestUtils.federatedAddress(port1, input("YR1")),
			"YR2=" + TestUtils.federatedAddress(port2, input("YR2")),
			"XC1=" + TestUtils.federatedAddress(port1, input("XC1")),
			"XC2=" + TestUtils.federatedAddress(port2, input("XC2")),
			"YC1=" + TestUtils.federatedAddress(port1, input("YC1")),
			"YC2=" + TestUtils.federatedAddress(port2, input("YC2")),
			"L=" + input("L"), "RV=" + input("RV"), "CV=" + input("CV"),
			"r=" + ROWS, "c=" + COLS,
			"RR=" + output("RR"), "CC=" + output("CC"),
			"RL=" + output("RL"), "LR=" + output("LR"),
			"CL=" + output("CL"), "LC=" + output("LC"),
			"RRV=" + output("RRV"), "CCV=" + output("CCV"),
			"RC=" + output("RC"), "CR=" + output("CR")};
	}

	private String[] referenceArgs() {
		return new String[] {"-nvargs", "X=" + input("X"), "Y=" + input("Y"),
			"L=" + input("L"), "RV=" + input("RV"), "CV=" + input("CV"),
			"RR=" + expected("RR"), "CC=" + expected("CC"),
			"RL=" + expected("RL"), "LR=" + expected("LR"),
			"CL=" + expected("CL"), "LC=" + expected("LC"),
			"RRV=" + expected("RRV"), "CCV=" + expected("CCV"),
			"RC=" + expected("RC"), "CR=" + expected("CR")};
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
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

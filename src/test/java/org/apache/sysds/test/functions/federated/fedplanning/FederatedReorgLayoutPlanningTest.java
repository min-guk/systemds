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

/** End-to-end transpose and matrix-to-vector rdiag witnesses for all concrete runtime layouts. */
@net.jcip.annotations.NotThreadSafe
public class FederatedReorgLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedReorgLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String[] OUTPUTS = {
		"TRow", "TCol", "TFull", "TBroadcast",
		"DRow", "DCol", "DFull", "DBroadcast",
		"DVRow", "DVFull", "DVBroadcast"
	};
	private static final int SIZE = 12;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, OUTPUTS));
	}

	@Test
	public void fedAllExecutesTransposeAndRdiagForAllConcreteLayouts() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = getRandomMatrix(SIZE, SIZE, 1, 7, 1, 467L);
		double[][] v = getRandomMatrix(SIZE, 1, 1, 7, 1, 479L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, SIZE / 2));
		writePublicMatrix("XR2", rows(x, SIZE / 2, SIZE));
		writePublicMatrix("XC1", cols(x, 0, SIZE / 2));
		writePublicMatrix("XC2", cols(x, SIZE / 2, SIZE));
		writePublicMatrix("XF", x);
		writePublicMatrix("XB1", x);
		writePublicMatrix("XB2", x);
		writePublicMatrix("V", v);
		writePublicMatrix("VR1", rows(v, 0, SIZE / 2));
		writePublicMatrix("VR2", rows(v, SIZE / 2, SIZE));
		writePublicMatrix("VF", v);
		writePublicMatrix("VB1", v);
		writePublicMatrix("VB2", v);

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
				Assert.assertTrue("Expected direct federated transpose",
					heavyHittersContainsString("fed_r'"));
				Assert.assertTrue("Expected direct federated rdiag",
					heavyHittersContainsString("fed_rdiag"));
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
			"XC1=" + TestUtils.federatedAddress(port1, input("XC1")),
			"XC2=" + TestUtils.federatedAddress(port2, input("XC2")),
			"XF=" + TestUtils.federatedAddress(port1, input("XF")),
			"XB1=" + TestUtils.federatedAddress(port1, input("XB1")),
			"XB2=" + TestUtils.federatedAddress(port2, input("XB2")),
			"VR1=" + TestUtils.federatedAddress(port1, input("VR1")),
			"VR2=" + TestUtils.federatedAddress(port2, input("VR2")),
			"VF=" + TestUtils.federatedAddress(port1, input("VF")),
			"VB1=" + TestUtils.federatedAddress(port1, input("VB1")),
			"VB2=" + TestUtils.federatedAddress(port2, input("VB2")),
			"n=" + SIZE,
			"TRow=" + output("TRow"), "TCol=" + output("TCol"),
			"TFull=" + output("TFull"), "TBroadcast=" + output("TBroadcast"),
			"DRow=" + output("DRow"), "DCol=" + output("DCol"),
			"DFull=" + output("DFull"), "DBroadcast=" + output("DBroadcast"),
			"DVRow=" + output("DVRow"), "DVFull=" + output("DVFull"),
			"DVBroadcast=" + output("DVBroadcast")};
	}

	private String[] referenceArgs() {
		return new String[] {"-nvargs", "X=" + input("X"), "V=" + input("V"),
			"TRow=" + expected("TRow"), "TCol=" + expected("TCol"),
			"TFull=" + expected("TFull"), "TBroadcast=" + expected("TBroadcast"),
			"DRow=" + expected("DRow"), "DCol=" + expected("DCol"),
			"DFull=" + expected("DFull"), "DBroadcast=" + expected("DBroadcast"),
			"DVRow=" + expected("DVRow"), "DVFull=" + expected("DVFull"),
			"DVBroadcast=" + expected("DVBroadcast")};
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

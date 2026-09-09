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

/** Direct runtime witnesses for ternary, aggregate-ternary, nary, qsort, and qpick FED instructions. */
@net.jcip.annotations.NotThreadSafe
public class FederatedTernaryNaryQuantileLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedTernaryNaryQuantileLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String[] OUTPUTS = {"TerRow", "TerCol", "AggRow", "AggCol",
		"NaryRow", "NaryCol", "QRow", "QFull"};
	private static final int ROWS = 12;
	private static final int COLS = 8;
	private static final int QROWS = 24;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, OUTPUTS));
	}

	@Test
	public void fedAllExecutesDirectFamilies() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = getRandomMatrix(ROWS, COLS, 0.2, 1.2, 1, 503L);
		double[][] y = getRandomMatrix(ROWS, COLS, 0.3, 1.3, 1, 509L);
		double[][] z = getRandomMatrix(ROWS, COLS, 0.4, 1.4, 1, 521L);
		double[][] q = getRandomMatrix(QROWS, 1, 1, 20, 1, 523L);
		writePublicMatrix("X", x); writePublicMatrix("Y", y); writePublicMatrix("Z", z); writePublicMatrix("Q", q);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2)); writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("YR1", rows(y, 0, ROWS / 2)); writePublicMatrix("YR2", rows(y, ROWS / 2, ROWS));
		writePublicMatrix("ZR1", rows(z, 0, ROWS / 2)); writePublicMatrix("ZR2", rows(z, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2)); writePublicMatrix("XC2", cols(x, COLS / 2, COLS));
		writePublicMatrix("YC1", cols(y, 0, COLS / 2)); writePublicMatrix("YC2", cols(y, COLS / 2, COLS));
		writePublicMatrix("ZC1", cols(z, 0, COLS / 2)); writePublicMatrix("ZC2", cols(z, COLS / 2, COLS));
		writePublicMatrix("Q1", rows(q, 0, QROWS / 2)); writePublicMatrix("Q2", rows(q, QROWS / 2, QROWS));

		Thread worker1 = null, worker2 = null;
		try {
			int port1 = getRandomAvailablePort(), port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_NAME + ".dml";
			programArgs = federatedArgs(port1, port2);
			runTest(true, false, null, -1);
			fullDMLScriptName = home + TEST_NAME + "Reference.dml";
			programArgs = referenceArgs();
			runTest(true, false, null, -1);
			compareResults(1e-8);
				if(!ExactPhysicalForcedStateAudit.isActive()) {
					Assert.assertTrue(heavyHittersContainsString("fed_ifelse"));
					Assert.assertTrue(heavyHittersContainsString("fed_nmin"));
				// Quantile FED instructions intentionally expose their worker opcode
				// without a fed_ prefix in statistics.
				Assert.assertTrue(heavyHittersContainsString("qsort"));
				Assert.assertTrue(heavyHittersContainsString("qpick"));
			}
		}
		finally { TestUtils.shutdownThreads(worker1, worker2); }
	}

		private String[] federatedArgs(int p1, int p2) {
			return new String[] {"-stats", "100", "-nvargs", "r=" + ROWS, "c=" + COLS, "qr=" + QROWS,
				"X=" + input("X"), "Y=" + input("Y"),
			"XR1=" + fed(p1,"XR1"), "XR2=" + fed(p2,"XR2"), "YR1=" + fed(p1,"YR1"), "YR2=" + fed(p2,"YR2"),
			"ZR1=" + fed(p1,"ZR1"), "ZR2=" + fed(p2,"ZR2"), "XC1=" + fed(p1,"XC1"), "XC2=" + fed(p2,"XC2"),
			"YC1=" + fed(p1,"YC1"), "YC2=" + fed(p2,"YC2"), "ZC1=" + fed(p1,"ZC1"), "ZC2=" + fed(p2,"ZC2"),
			"Q1=" + fed(p1,"Q1"), "Q2=" + fed(p2,"Q2"), "QF=" + fed(p1,"Q"),
			"TerRow="+output("TerRow"), "TerCol="+output("TerCol"), "AggRow="+output("AggRow"), "AggCol="+output("AggCol"),
			"NaryRow="+output("NaryRow"), "NaryCol="+output("NaryCol"), "QRow="+output("QRow"), "QFull="+output("QFull")};
	}

	private String[] referenceArgs() {
		return new String[] {"-nvargs", "X="+input("X"), "Y="+input("Y"), "Z="+input("Z"), "Q="+input("Q"),
			"TerRow="+expected("TerRow"), "TerCol="+expected("TerCol"), "AggRow="+expected("AggRow"), "AggCol="+expected("AggCol"),
			"NaryRow="+expected("NaryRow"), "NaryCol="+expected("NaryCol"), "QRow="+expected("QRow"), "QFull="+expected("QFull")};
	}

	private String fed(int port, String name) { return TestUtils.federatedAddress(port, input(name)); }

	private void writePublicMatrix(String name, double[][] values) {
		MatrixBlock block = DataConverter.convertToMatrixBlock(values);
		MatrixCharacteristics mc = new MatrixCharacteristics(values.length, values[0].length, BLOCKSIZE, block.getNonZeros());
		writeBinaryWithMTD(name, block, mc);
		try { HDFSTool.writeMetaDataFile(baseDirectory + INPUT_DIR + name + ".mtd", ValueType.FP64, null,
			DataType.MATRIX, mc, FileFormat.BINARY, null, "public"); }
		catch(IOException ex) { throw new RuntimeException(ex); }
	}

	private static double[][] rows(double[][] in, int from, int to) {
		double[][] out = new double[to-from][in[0].length];
		for(int i=from; i<to; i++) System.arraycopy(in[i], 0, out[i-from], 0, in[0].length);
		return out;
	}

	private static double[][] cols(double[][] in, int from, int to) {
		double[][] out = new double[in.length][to-from];
		for(int i=0; i<in.length; i++) System.arraycopy(in[i], from, out[i], 0, to-from);
		return out;
	}

	@Override protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

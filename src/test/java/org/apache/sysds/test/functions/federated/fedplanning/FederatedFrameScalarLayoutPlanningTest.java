/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 * Licensed under the Apache License, Version 2.0.
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

/** Direct ROW and COL witnesses for the federated frame-scalar map instruction. */
@net.jcip.annotations.NotThreadSafe
public class FederatedFrameScalarLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedFrameScalarLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final int ROWS = 12;
	private static final int COLS = 8;
	private static final int BLOCKSIZE = 1000;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"MapRow", "MapCol"}));
	}

	@Test
	public void fedAllExecutesFrameScalarMapLayouts() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x = getRandomMatrix(ROWS, COLS, 1, 3, 1, 631L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2));
		writePublicMatrix("XC2", cols(x, COLS / 2, COLS));

		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + TEST_NAME + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs", "r=" + ROWS, "c=" + COLS,
				"XR1=" + fed(port1, "XR1"), "XR2=" + fed(port2, "XR2"),
				"XC1=" + fed(port1, "XC1"), "XC2=" + fed(port2, "XC2"),
				"MapRow=" + output("MapRow"), "MapCol=" + output("MapCol")};
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_NAME + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"),
				"MapRow=" + expected("MapRow"), "MapCol=" + expected("MapCol")};
			runTest(true, false, null, -1);
			compareResults(0);
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected direct federated frame map",
					heavyHittersContainsString("fed__map"));
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
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

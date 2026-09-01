/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.functions.federated.fedplanning;

import java.io.File;
import java.util.HashMap;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalForcedStateAudit;
import org.apache.sysds.runtime.matrix.data.MatrixValue.CellIndex;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** A small compiled-planner witness for post-codegen Spoof fused HOPs. */
@net.jcip.annotations.NotThreadSafe
public class FederatedSpoofPlanningSpaceTest extends AutomatedTestBase {
	private static final String TEST_NAME = "FederatedCellwiseTmplTest";
	private static final String TEST_DIR = "functions/federated/codegen/";
	private static final String TEST_CLASS_DIR = TEST_DIR + "FederatedSpoofPlanningSpaceTest/";
	private static final int ROWS = 4;
	private static final int COLS = 4;

	@Override
	public void setUp() {
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"Z"}));
	}

	@Test
	public void rowPartitionedCellwiseSpoofIsPlannerVisibleAndExecutable() {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] x1 = getRandomMatrix(ROWS / 2, COLS, 0, 1, 0.5, 733L);
		double[][] x2 = getRandomMatrix(ROWS / 2, COLS, 0, 1, 0.5, 739L);
		MatrixCharacteristics mc = new MatrixCharacteristics(ROWS / 2, COLS, 1024);
		writeInputMatrixWithMTD("X1", x1, false, mc);
		writeInputMatrixWithMTD("X2", x2, false, mc);
		writeExpectedMatrix("Z", expected(x1, x2));

		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);
			String home = SCRIPT_DIR + TEST_DIR;

			fullDMLScriptName = home + TEST_NAME + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"in_X1=" + TestUtils.federatedAddress(port1, input("X1")),
				"in_X2=" + TestUtils.federatedAddress(port2, input("X2")),
				"in_rp=TRUE", "in_test_num=4", "rows=" + ROWS, "cols=" + COLS,
				"out_Z=" + output("Z")};
			runTest(true, false, null, -1);

			HashMap<CellIndex, Double> expected = readDMLMatrixFromExpectedDir("Z");
			HashMap<CellIndex, Double> actual = readDMLMatrixFromOutputDir("Z");
			TestUtils.compareMatrices(actual, expected, 1e-11, "Fed", "Ref");
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected federated codegen execution",
					heavyHittersContainsSubString("fed_spoofCell"));
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private static double[][] expected(double[][] first, double[][] second) {
		double[][] result = new double[ROWS][COLS];
		for(int row = 0; row < ROWS; row++) {
			double[][] source = row < ROWS / 2 ? first : second;
			int sourceRow = row % (ROWS / 2);
			for(int col = 0; col < COLS; col++)
				result[row][col] = 10 + Math.floor(Math.round(Math.abs((source[sourceRow][col] + 3) * 5)));
		}
		return result;
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + "functions/privacy/fedplanning/",
			"SystemDS-config-codegen-fedall.xml");
	}
}

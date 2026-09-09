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
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalForcedStateAudit;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Exact witnesses for logical quantile lowering and the separate runtime-planner cumulative path. */
@net.jcip.annotations.NotThreadSafe
public class FederatedQuantileCumulativeAuxiliaryRuntimeTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedQuantileCumulativeAuxiliaryRuntimeTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final int ONE_BLOCK_ROWS = 12;
	private static final int MULTI_BLOCK_ROWS = 2_000;
	private static final int BLOCKSIZE = 1000;
	private boolean runtimePlanner;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"C"}));
	}

	@Test
	public void compiledPlannerMapsOneQuantileStateToQsortAndQpick() {
		runFixture(ExecType.CP, ONE_BLOCK_ROWS, false, false);
		if(!ExactPhysicalForcedStateAudit.isActive()) {
			Assert.assertTrue("Expected physical quantile sort stage",
				heavyHittersContainsString("qsort"));
			Assert.assertTrue("Expected logical quantile result stage",
				heavyHittersContainsString("qpick"));
		}
	}

	@Test
	public void runtimePlannerCumulativeMatchesReferenceWithPlacementAuditDisabled() {
		runtimePlanner = true;
		try {
			runFixture(ExecType.SPARK, ONE_BLOCK_ROWS, false, false);
			Assert.assertTrue("Expected runtime-converted cumulative offset",
				heavyHittersContainsString("fed_bcumoffk+"));
		}
		finally {
			runtimePlanner = false;
		}
	}

	@Test
	public void runtimePlannerCumulativeMatchesReferenceWithPlacementAuditEnabled() {
		runtimePlanner = true;
		try {
			runFixture(ExecType.SPARK, ONE_BLOCK_ROWS, true, false);
			Assert.assertTrue("Expected runtime-converted cumulative offset",
				heavyHittersContainsString("fed_bcumoffk+"));
		}
		finally {
			runtimePlanner = false;
		}
	}

	@Test
	public void runtimePlannerMultiBlockReportsUcumackWorkerParsingGap() {
		runtimePlanner = true;
		try {
			runFixture(ExecType.SPARK, MULTI_BLOCK_ROWS, false, true);
		}
		finally {
			runtimePlanner = false;
		}
	}

	private void runFixture(ExecType execType, int rows, boolean placementAudit,
		boolean expectUcumackGap) {
		getAndLoadTestConfiguration(TEST_NAME);
		double[][] values = getRandomMatrix(rows, 1, 0.1, 10, 1, 2209L);
		writePublicMatrix("X", values);
		writePublicMatrix("X1", rows(values, 0, rows / 2));
		writePublicMatrix("X2", rows(values, rows / 2, rows));

		ExecMode old = setExecMode(execType);
		String oldAudit = System.getProperty(PlannerRuntimePlacementAudit.PROPERTY);
		if(placementAudit)
			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, Boolean.TRUE.toString());
		else
			System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
		PlannerRuntimePlacementAudit.resetForTesting();
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
				"XFull=" + TestUtils.federatedAddress(port1, input("X")),
				"X1=" + TestUtils.federatedAddress(port1, input("X1")),
				"X2=" + TestUtils.federatedAddress(port2, input("X2")),
				"r=" + rows, "Q=" + output("Q"), "C=" + output("C")};
			if(expectUcumackGap) {
				runTest(true, true, DMLRuntimeException.class, "ucumack+", -1);
				return;
			}
			runTest(true, false, null, -1);

			fullDMLScriptName = home + TEST_NAME + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"),
				"Q=" + expected("Q"), "C=" + expected("C")};
			runTest(true, false, null, -1);
			compareResults(1e-8, "reference", "federated");
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			PlannerRuntimePlacementAudit.resetForTesting();
			if(oldAudit == null)
				System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
			else
				System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, oldAudit);
			resetExecMode(old);
		}
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

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR,
			runtimePlanner ? "SystemDS-config-runtime.xml" : "SystemDS-config-fout.xml");
	}
}

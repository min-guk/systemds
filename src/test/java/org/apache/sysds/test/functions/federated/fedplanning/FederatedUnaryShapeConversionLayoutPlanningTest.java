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
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Focused runtime witnesses for unary, cumulative, reshape, reblock, cast, and write conversion. */
@net.jcip.annotations.NotThreadSafe
public class FederatedUnaryShapeConversionLayoutPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedUnaryShapeConversionLayoutPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final int ROWS = 12;
	private static final int COLS = 12;
	private static final int BLOCKSIZE = 1000;
	private boolean runtimePlannerConfig;

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME + "UnaryShape", new TestConfiguration(TEST_CLASS_DIR,
			TEST_NAME + "UnaryShape", new String[] {"ExpRow", "SqrtCol", "ShapeRow", "ShapeCol"}));
		addTestConfiguration(TEST_NAME + "Cumulative", new TestConfiguration(TEST_CLASS_DIR,
			TEST_NAME + "Cumulative", new String[] {"CumSumRow", "CumMaxCol"}));
		addTestConfiguration(TEST_NAME + "CastWrite", new TestConfiguration(TEST_CLASS_DIR,
			TEST_NAME + "CastWrite", new String[] {"CastSum"}));
		addTestConfiguration(TEST_NAME + "VariableWrite", new TestConfiguration(TEST_CLASS_DIR,
			TEST_NAME + "VariableWrite", new String[] {"WriteSum"}));
	}

	@Test
	public void fedAllExecutesUnaryAndReshape() {
		runMatrixFixture("UnaryShape", ExecType.CP, new String[] {"ExpRow", "SqrtCol", "ShapeRow", "ShapeCol"});
		if(!ExactPhysicalForcedStateAudit.isActive()) {
			Assert.assertTrue("Expected federated unary execution", heavyHittersContainsString("fed_exp"));
			Assert.assertTrue("Expected federated reshape execution", heavyHittersContainsString("fed_rshape"));
		}
	}

	@Test
	public void fedAllExecutesCumulativeSingleNode() {
		runMatrixFixture("Cumulative", ExecType.CP, new String[] {"CumSumRow", "CumMaxCol"});
		if(!ExactPhysicalForcedStateAudit.isActive())
			Assert.assertTrue("Expected federated cumulative execution", heavyHittersContainsString("fed_ucumk+"));
	}

	@Test
	public void fedAllExecutesCumulativeSpark() {
		runMatrixFixture("Cumulative", ExecType.SPARK, new String[] {"CumSumRow", "CumMaxCol"});
		if(!ExactPhysicalForcedStateAudit.isActive())
			Assert.assertTrue("Compiled FedAll keeps the cumulative operation directly federated",
				heavyHittersContainsString("fed_ucumk+"));
	}

	@Test
	public void fedAllExecutesCastAndFederatedWriteSingleNode() {
		runCastWriteFixture(ExecType.CP);
		if(!ExactPhysicalForcedStateAudit.isActive()) {
			Assert.assertTrue("Expected federated cast-to-frame execution",
				heavyHittersContainsString("fed_castdtf"));
			Assert.assertTrue("Expected federated cast-to-matrix execution",
				heavyHittersContainsString("fed_castdtm"));
		}
	}

	@Test
	public void fedAllExecutesCastAndFederatedWriteSpark() {
		runCastWriteFixture(ExecType.SPARK);
		if(!ExactPhysicalForcedStateAudit.isActive()) {
			Assert.assertTrue("Expected federated cast-to-frame execution",
				heavyHittersContainsString("fed_castdtf"));
			Assert.assertTrue("Expected federated cast-to-matrix execution",
				heavyHittersContainsString("fed_castdtm"));
		}
	}

	@Test
	public void runtimePlannerExecutesCumulativeOffsetSpark() {
		runtimePlannerConfig = true;
		try {
			runMatrixFixture("Cumulative", ExecType.SPARK, new String[] {"CumSumRow", "CumMaxCol"});
			if(!ExactPhysicalForcedStateAudit.isActive())
				Assert.assertTrue("Expected Spark cumulative-offset conversion",
					heavyHittersContainsString("fed_bcumoffk+"));
		}
		finally {
			runtimePlannerConfig = false;
		}
	}

	@Test
	public void runtimePlannerExecutesVariableWrite() {
		runtimePlannerConfig = true;
		String name = TEST_NAME + "VariableWrite";
		getAndLoadTestConfiguration(name);
		double[][] x = getRandomMatrix(ROWS, COLS, 0.1, 3.0, 1, 839L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);
			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + name + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"XR1=" + TestUtils.federatedAddress(port1, input("XR1")),
				"XR2=" + TestUtils.federatedAddress(port2, input("XR2")),
				"r=" + ROWS, "c=" + COLS,
				"FedWrite=" + output("VariableWrite.json"), "WriteSum=" + output("WriteSum")};
			runTest(true, false, null, -1);
			Assert.assertTrue("Expected federated variable-write conversion",
				heavyHittersContainsString("fed_write"));

			fullDMLScriptName = home + name + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"),
				"WriteSum=" + expected("WriteSum")};
			runTest(true, false, null, -1);
			compareResults(1e-8, "reference", "federated");
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			runtimePlannerConfig = false;
		}
	}

	private void runMatrixFixture(String suffix, ExecType execType, String[] outputs) {
		String name = TEST_NAME + suffix;
		getAndLoadTestConfiguration(name);
		double[][] x = getRandomMatrix(ROWS, COLS, 0.1, 3.0, 1, 821L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));
		writePublicMatrix("XC1", cols(x, 0, COLS / 2));
		writePublicMatrix("XC2", cols(x, COLS / 2, COLS));

		ExecMode old = setExecMode(execType);
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);

			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + name + ".dml";
			programArgs = matrixArgs(port1, port2, outputs, false);
			runTest(true, false, null, -1);

			fullDMLScriptName = home + name + "Reference.dml";
			programArgs = referenceArgs(outputs);
			runTest(true, false, null, -1);
			compareResults(1e-8, "reference", "federated");
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			resetExecMode(old);
		}
	}

	private void runCastWriteFixture(ExecType execType) {
		String name = TEST_NAME + "CastWrite";
		getAndLoadTestConfiguration(name);
		double[][] x = getRandomMatrix(ROWS, COLS, 0.1, 3.0, 1, 829L);
		writePublicMatrix("X", x);
		writePublicMatrix("XR1", rows(x, 0, ROWS / 2));
		writePublicMatrix("XR2", rows(x, ROWS / 2, ROWS));

		ExecMode old = setExecMode(execType);
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);

			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + name + ".dml";
			programArgs = new String[] {"-stats", "100", "-nvargs",
				"XR1=" + TestUtils.federatedAddress(port1, input("XR1")),
				"XR2=" + TestUtils.federatedAddress(port2, input("XR2")),
				"r=" + ROWS, "c=" + COLS, "CastSum=" + output("CastSum")};
			runTest(true, false, null, -1);

			fullDMLScriptName = home + name + "Reference.dml";
			programArgs = new String[] {"-nvargs", "X=" + input("X"),
				"CastSum=" + expected("CastSum")};
			runTest(true, false, null, -1);
			compareResults(1e-8);
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			resetExecMode(old);
		}
	}

	private String[] matrixArgs(int port1, int port2, String[] outputs, boolean includeFedWrite) {
		String[] args = new String[13 + outputs.length * 2 + (includeFedWrite ? 2 : 0)];
		int i = 0;
		args[i++] = "-stats";
		args[i++] = "100";
		args[i++] = "-nvargs";
		args[i++] = "XR1=" + TestUtils.federatedAddress(port1, input("XR1"));
		args[i++] = "XR2=" + TestUtils.federatedAddress(port2, input("XR2"));
		args[i++] = "XC1=" + TestUtils.federatedAddress(port1, input("XC1"));
		args[i++] = "XC2=" + TestUtils.federatedAddress(port2, input("XC2"));
		args[i++] = "r=" + ROWS;
		args[i++] = "c=" + COLS;
		for(String output : outputs)
			args[i++] = output + "=" + output(output);
		if(includeFedWrite)
			args[i++] = "FedWrite=" + output("FederatedWrite");
		String[] result = new String[i];
		System.arraycopy(args, 0, result, 0, i);
		return result;
	}

	private String[] referenceArgs(String[] outputs) {
		String[] args = new String[2 + outputs.length];
		int i = 0;
		args[i++] = "-nvargs";
		args[i++] = "X=" + input("X");
		for(String output : outputs)
			args[i++] = output + "=" + expected(output);
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

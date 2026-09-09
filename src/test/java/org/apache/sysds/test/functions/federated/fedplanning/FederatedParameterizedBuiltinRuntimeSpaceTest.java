/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.functions.federated.fedplanning;

import java.io.File;
import java.io.IOException;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class FederatedParameterizedBuiltinRuntimeSpaceTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedParameterizedBuiltinRuntimeSpaceTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final int ROWS = 20;
	private static final int COLS = 6;
	private static final int BLOCKSIZE = 1000;

	private enum Layout { ROW, COL, FULL }

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME + "Parameterized",
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME + "Parameterized", new String[] {"S"}));
		addTestConfiguration(TEST_NAME + "TransformEncode",
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME + "TransformEncode", new String[] {"T"}));
	}

	@Test public void fedAllExecutesParameterizedRow() { runFixture("Parameterized", Layout.ROW, ExecType.CP); }
	@Test public void fedAllExecutesParameterizedCol() { runFixture("Parameterized", Layout.COL, ExecType.CP); }
	@Test public void fedAllExecutesParameterizedFull() { runFixture("Parameterized", Layout.FULL, ExecType.CP); }
	@Test public void fedAllExecutesTransformEncodeRow() { runFixture("TransformEncode", Layout.ROW, ExecType.CP); }
	@Test public void fedAllExecutesTransformEncodeCol() { runFixture("TransformEncode", Layout.COL, ExecType.CP); }
	@Test public void fedAllExecutesTransformEncodeFullSpark() { runFixture("TransformEncode", Layout.FULL, ExecType.SPARK); }
	@Test public void fedAllExecutesTransformEncodeDummyRow() {
		runFixture("TransformEncode", Layout.ROW, ExecType.CP, true);
	}
	@Test public void fedAllExecutesTransformEncodeDummyCol() {
		runFixture("TransformEncode", Layout.COL, ExecType.CP, true);
	}

	private void runFixture(String fixture, Layout layout, ExecType execType) {
		runFixture(fixture, layout, execType, false);
	}

	private void runFixture(String fixture, Layout layout, ExecType execType, boolean dummycode) {
		String name = TEST_NAME + fixture;
		getAndLoadTestConfiguration(name);
		double[][] data = data(layout);
		writeInputs(data, layout);

		ExecMode old = setExecMode(execType);
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int port1 = getRandomAvailablePort();
			int port2 = layout == Layout.FULL ? -1 : getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			if(layout != Layout.FULL)
				worker2 = startLocalFedWorkerThread(port2);

			String home = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = home + name + ".dml";
			programArgs = federatedArgs(port1, port2, layout, dummycode);
			runTest(true, false, null, -1);

			fullDMLScriptName = home + name + "Reference.dml";
			programArgs = fixture.equals("Parameterized") ?
				new String[] {"-nvargs", "X=" + input("XRef"),
					"margin=" + (layout == Layout.COL ? "cols" : "rows"),
					"S=" + expected("S"), "C=" + expected("C")} :
				new String[] {"-nvargs", "X=" + input("XRef"), "T=" + expected("T"),
					"spec=" + transformSpec(dummycode)};
			runTest(true, false, null, -1);
			if(fixture.equals("Parameterized")) {
				double expected = readDMLScalarFromExpectedDir("S").values().iterator().next();
				double actual = readDMLScalarFromOutputDir("S").values().iterator().next();
				Assert.assertEquals(expected, actual, 1e-8);
			}
			else
				compareResults(1e-8);
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			resetExecMode(old);
		}
	}

	private String[] federatedArgs(int port1, int port2, Layout layout, boolean dummycode) {
		String margin = layout == Layout.COL ? "cols" : "rows";
		if(layout == Layout.FULL)
			return new String[] {"-stats", "100", "-nvargs", "X1=" + TestUtils.federatedAddress(port1, input("X1")),
				"X2=unused", "r=" + ROWS, "c=" + COLS, "layout=full", "margin=" + margin,
				"S=" + output("S"), "C=" + output("C"), "T=" + output("T"),
				"spec=" + transformSpec(dummycode)};
		return new String[] {"-stats", "100", "-nvargs", "X1=" + TestUtils.federatedAddress(port1, input("X1")),
			"X2=" + TestUtils.federatedAddress(port2, input("X2")), "r=" + ROWS, "c=" + COLS,
			"layout=" + layout.name().toLowerCase(), "margin=" + margin,
			"S=" + output("S"), "C=" + output("C"), "T=" + output("T"),
			"spec=" + transformSpec(dummycode)};
	}

	private static String transformSpec(boolean dummycode) {
		return dummycode ? "{ids:true,dummycode:[1]}" : "{ids:true}";
	}

	private void writeInputs(double[][] data, Layout layout) {
		writeMatrix("XRef", data, "public");
		if(layout == Layout.ROW) {
			writeMatrix("X1", rows(data, 0, ROWS / 2), "private-aggregate");
			writeMatrix("X2", rows(data, ROWS / 2, ROWS), "private-aggregate");
		}
		else if(layout == Layout.COL) {
			writeMatrix("X1", cols(data, 0, COLS / 2), "private-aggregate");
			writeMatrix("X2", cols(data, COLS / 2, COLS), "private-aggregate");
		}
		else
			writeMatrix("X1", data, "private-aggregate");
	}

	private void writeMatrix(String name, double[][] data, String privacy) {
		MatrixCharacteristics mc = new MatrixCharacteristics(data.length, data[0].length, BLOCKSIZE, -1);
		writeBinaryWithMTD(name, DataConverter.convertToMatrixBlock(data), mc);
		try {
			HDFSTool.writeMetaDataFile(baseDirectory + INPUT_DIR + name + ".mtd", ValueType.FP64, null,
				DataType.MATRIX, mc, FileFormat.BINARY, null, privacy);
		}
		catch(IOException ex) {
			throw new RuntimeException("Unable to write test metadata", ex);
		}
	}

	private static double[][] data(Layout layout) {
		double[][] data = new double[ROWS][COLS];
		for(int i = 0; i < ROWS; i++)
			for(int j = 0; j < COLS; j++)
				data[i][j] = 1 + (i + 2 * j) % 5;
		if(layout == Layout.COL)
			for(int i = 0; i < ROWS; i++) data[i][1] = 0;
		else
			for(int j = 0; j < COLS; j++) data[1][j] = 0;
		return data;
	}

	private static double[][] rows(double[][] in, int begin, int end) {
		double[][] out = new double[end - begin][in[0].length];
		for(int i = begin; i < end; i++) System.arraycopy(in[i], 0, out[i - begin], 0, in[i].length);
		return out;
	}

	private static double[][] cols(double[][] in, int begin, int end) {
		double[][] out = new double[in.length][end - begin];
		for(int i = 0; i < in.length; i++) System.arraycopy(in[i], begin, out[i], 0, end - begin);
		return out;
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}
}

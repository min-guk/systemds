/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.functions.federated.fedplanning;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Regression for planner source metadata leaking between sequential compilations in one JVM. */
@net.jcip.annotations.NotThreadSafe
public class FederatedSequentialCompilationDeterminismTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedSequentialCompilationDeterminismTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + TEST_NAME + "/";
	private static final String PARAMETERIZED =
		"FederatedParameterizedBuiltinRuntimeSpaceTestParameterized";
	private static final String TRANSFORM =
		"FederatedParameterizedBuiltinRuntimeSpaceTestTransformEncode";
	private static final int ROWS = 20;
	private static final int COLS = 6;
	private static final int BLOCKSIZE = 1000;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME,
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"S", "C", "T"}));
	}

	@Test
	public void colCandidateDomainIsIndependentOfPriorFullSparkCompilation() throws Exception {
		getAndLoadTestConfiguration(TEST_NAME);
		writeInputs();
		Path auditRoot = Files.createTempDirectory("sequential-fedplanner-candidates-");
		String oldEnabled = System.getProperty(PlannerCandidateSpaceAudit.PROPERTY);
		String oldDirectory = System.getProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
		String oldContext = System.getProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY);
		ExecMode oldMode = setExecMode(ExecType.CP);
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			worker1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			worker2 = startLocalFedWorkerThread(port2);
			System.setProperty(PlannerCandidateSpaceAudit.PROPERTY, "true");

			CandidateSnapshot baseline = compileParameterizedCol(port1, port2,
				auditRoot.resolve("baseline"), "baseline-col");
			compileTransformFullSpark(port1, auditRoot.resolve("predecessor"), "predecessor-full-spark");
			Assert.assertEquals("The predecessor must leave the exact FULL source fact that exposed the leak",
				FType.FULL, FederatedPlannerUtils.snapshotFedInitTypes().get("X"));
			CandidateSnapshot after = compileParameterizedCol(port1, port2,
				auditRoot.resolve("after"), "after-full-col");

			Assert.assertEquals("Identical COL programs must produce the same placement-analysis fingerprint",
				baseline.analysisFingerprints(), after.analysisFingerprints());
			Assert.assertEquals("Identical COL programs must publish the same exact candidate JSON domain",
				baseline.canonicalRows(), after.canonicalRows());
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			resetExecMode(oldMode);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			restoreProperty(PlannerCandidateSpaceAudit.PROPERTY, oldEnabled);
			restoreProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, oldDirectory);
			restoreProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY, oldContext);
		}
	}

	private CandidateSnapshot compileParameterizedCol(int port1, int port2, Path auditDir,
		String context) throws Exception {
		setExecMode(ExecType.CP);
		configureAudit(auditDir, context);
		fullDMLScriptName = SCRIPT_DIR + TEST_DIR + PARAMETERIZED + ".dml";
		programArgs = new String[] {"-stats", "100", "-nvargs",
			"X1=" + TestUtils.federatedAddress(port1, input("X1")),
			"X2=" + TestUtils.federatedAddress(port2, input("X2")),
			"r=" + ROWS, "c=" + COLS, "layout=col", "margin=cols",
			"S=" + output("S"), "C=" + output("C"), "T=" + output("T"), "spec={ids:true}"};
		runTest(true, false, null, -1);
		return readCandidates(auditDir);
	}

	private void compileTransformFullSpark(int port, Path auditDir, String context) {
		setExecMode(ExecType.SPARK);
		configureAudit(auditDir, context);
		fullDMLScriptName = SCRIPT_DIR + TEST_DIR + TRANSFORM + ".dml";
		programArgs = new String[] {"-stats", "100", "-nvargs",
			"X1=" + TestUtils.federatedAddress(port, input("XF")), "X2=unused",
			"r=" + ROWS, "c=" + COLS, "layout=full", "margin=rows",
			"S=" + output("S"), "C=" + output("C"), "T=" + output("T"), "spec={ids:true}"};
		runTest(true, false, null, -1);
	}

	private static void configureAudit(Path directory, String context) {
		System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, directory.toString());
		System.setProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY, context);
	}

	private static CandidateSnapshot readCandidates(Path directory) throws Exception {
		List<JsonNode> rows = new ArrayList<>();
		try(var files = Files.list(directory)) {
			for(Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList())
				for(String line : Files.readAllLines(file))
					if(!line.isBlank()) rows.add(MAPPER.readTree(line));
		}
		Assert.assertFalse("Candidate audit must contain the production placement analysis", rows.isEmpty());
		Set<String> fingerprints = new LinkedHashSet<>();
		List<String> canonical = new ArrayList<>();
		for(JsonNode row : rows) {
			fingerprints.add(row.path("analysisFingerprint").asText());
			canonical.add(String.join("|", row.path("opcode").asText(), row.path("hopClass").asText(),
				row.path("inputSignature").toString(), row.path("publishedNodeStates").toString(),
				row.path("publishedStatesP").toString(), row.path("concreteShape").toString(),
				row.path("abstractShape").toString(), row.path("privacy").asText()));
		}
		canonical.sort(Comparator.naturalOrder());
		return new CandidateSnapshot(Set.copyOf(fingerprints), List.copyOf(canonical));
	}

	private void writeInputs() {
		double[][] full = data();
		writeMatrix("X1", cols(full, 0, COLS / 2));
		writeMatrix("X2", cols(full, COLS / 2, COLS));
		writeMatrix("XF", full);
	}

	private void writeMatrix(String name, double[][] data) {
		MatrixCharacteristics mc = new MatrixCharacteristics(data.length, data[0].length, BLOCKSIZE, -1);
		writeBinaryWithMTD(name, DataConverter.convertToMatrixBlock(data), mc);
		try {
			HDFSTool.writeMetaDataFile(baseDirectory + INPUT_DIR + name + ".mtd", ValueType.FP64, null,
				DataType.MATRIX, mc, FileFormat.BINARY, null, "private-aggregate");
		}
		catch(IOException ex) {
			throw new RuntimeException("Unable to write test metadata", ex);
		}
	}

	private static double[][] data() {
		double[][] data = new double[ROWS][COLS];
		for(int i = 0; i < ROWS; i++)
			for(int j = 0; j < COLS; j++) data[i][j] = 1 + (i + 2 * j) % 5;
		for(int i = 0; i < ROWS; i++) data[i][1] = 0;
		return data;
	}

	private static double[][] cols(double[][] in, int begin, int end) {
		double[][] out = new double[in.length][end - begin];
		for(int i = 0; i < in.length; i++) System.arraycopy(in[i], begin, out[i], 0, end - begin);
		return out;
	}

	private static void restoreProperty(String name, String value) {
		if(value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-fout.xml");
	}

	private record CandidateSnapshot(Set<String> analysisFingerprints, List<String> canonicalRows) { }
}

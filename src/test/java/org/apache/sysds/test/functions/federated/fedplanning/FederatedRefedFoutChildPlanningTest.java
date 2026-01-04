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

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.api.ScriptExecutorUtils;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.CompilerConfig.ConfigType;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.utils.Statistics;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@net.jcip.annotations.NotThreadSafe
public class FederatedRefedFoutChildPlanningTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/privacy/fedplanning/";
	private static final String TEST_NAME = "FederatedRefedFoutChildPlanningTest";
	private static final String TEST_CLASS_DIR = TEST_DIR + FederatedRefedFoutChildPlanningTest.class.getSimpleName() + "/";

	private static final int blocksize = 1024;
	private final int rows = 10;
	private final int cols = 10;
	private final String privacyConstraints = "private-aggregate";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"W1", "W2"}));
	}

	@Test
	public void runRefedFoutChildCostBased() throws IOException {
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		Types.ExecMode platformOld = rtplatform;
		boolean statsOld = DMLScript.STATISTICS;
		int statsCountOld = DMLScript.STATISTICS_COUNT;
		CompilerConfig compilerConfigOld = ConfigurationManager.getCompilerConfig();
		CompilerConfig compilerConfigNew = new CompilerConfig(compilerConfigOld);
		DMLConfig dmlConfigOld = ConfigurationManager.getDMLConfig();
		DMLConfig dmlConfigNew = new DMLConfig(dmlConfigOld);
		compilerConfigNew.set(ConfigType.ALLOW_DYN_RECOMPILATION, false);
		compilerConfigNew.set(ConfigType.ALLOW_PARALLEL_DYN_RECOMPILATION, false);
		compilerConfigNew.set(ConfigType.ALLOW_INDIVIDUAL_SB_SPECIFIC_OPS, false);
		Thread t1 = null, t2 = null;
		rtplatform = Types.ExecMode.SINGLE_NODE;

		try {
			ConfigurationManager.setGlobalConfig(compilerConfigNew);
			ConfigurationManager.setLocalConfig(compilerConfigNew);
			getAndLoadTestConfiguration(TEST_NAME);
			dmlConfigNew.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			ConfigurationManager.setGlobalConfig(dmlConfigNew);
			String home = SCRIPT_DIR + TEST_DIR;

			writeInputMatrices();

			int port1 = getRandomAvailablePort();
			int port2 = getRandomAvailablePort();
			t1 = startLocalFedWorkerThread(port1, FED_WORKER_WAIT_S);
			t2 = startLocalFedWorkerThread(port2);

			Map<String, String> argVals = new HashMap<>();
			argVals.put("$X1", TestUtils.federatedAddress(port1, input("X1")));
			argVals.put("$X2", TestUtils.federatedAddress(port2, input("X2")));
			argVals.put("$W1", output("W1"));
			argVals.put("$W2", output("W2"));

			String scriptPath = home + TEST_NAME + ".dml";
			String script = DMLScript.readDMLScript(true, scriptPath);

			ParserWrapper parser = ParserFactory.createParser();
			DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, argVals);

			DMLTranslator dmlt = new DMLTranslator(prog);
			dmlt.liveVariableAnalysis(prog);
			dmlt.validateParseTree(prog);
			dmlt.constructHops(prog);
			dmlt.rewriteHopsDAG(prog);

			Hop targetHop = findRefedTargetHop(prog.getStatementBlocks());
			assertNotNull("Expected to find refed target hop for forcing", targetHop);
			targetHop.setForcedExecType(ExecType.CP);
			targetHop.setFederatedOutput(FederatedOutput.FOUT);
			forceFederatedChildInput(targetHop);
			forceFederatedParents(targetHop);
			FederatedRefedPolicy.registerFromProgram(prog);

			dmlt.constructLops(prog);
			Program rtprog = dmlt.getRuntimeProgram(prog, ConfigurationManager.getDMLConfig());
			ExecutionContext ec = ExecutionContextFactory.createContext(rtprog);

			DMLScript.STATISTICS = true;
			DMLScript.STATISTICS_COUNT = 10;
			Statistics.reset();
			ScriptExecutorUtils.executeRuntimeProgram(rtprog, ec, ConfigurationManager.getDMLConfig(),
				DMLScript.STATISTICS_COUNT, null);

			assertTrue("Expected fed_refed in heavy hitters",
				heavyHittersContainsSubString(Opcodes.FEDREFED.toString()));
		}
		finally {
			TestUtils.shutdownThreads(t1, t2);
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
			DMLScript.STATISTICS = statsOld;
			DMLScript.STATISTICS_COUNT = statsCountOld;
			ConfigurationManager.setGlobalConfig(compilerConfigOld);
			ConfigurationManager.setGlobalConfig(dmlConfigOld);
		}
	}

	private void writeInputMatrices() {
		writeIdentityRowPartitions("X1", "X2");
	}

	private void writeIdentityRowPartitions(String part1Name, String part2Name) {
		int halfRows = rows / 2;
		double[][] part1 = new double[halfRows][cols];
		double[][] part2 = new double[rows - halfRows][cols];
		for (int i = 0; i < halfRows; i++) {
			part1[i][i] = 1.0;
		}
		for (int i = 0; i < rows - halfRows; i++) {
			part2[i][i + halfRows] = 1.0;
		}
		MatrixCharacteristics mc1 = new MatrixCharacteristics(halfRows, cols, blocksize, halfRows);
		MatrixCharacteristics mc2 = new MatrixCharacteristics(rows - halfRows, cols, blocksize, rows - halfRows);
		writeInputMatrixWithMTD(part1Name, part1, false, mc1, privacyConstraints);
		writeInputMatrixWithMTD(part2Name, part2, false, mc2, privacyConstraints);
	}

	private static Hop findRefedTargetHop(List<org.apache.sysds.parser.StatementBlock> blocks) {
		Set<Long> visited = new HashSet<>();
		for (org.apache.sysds.parser.StatementBlock sb : blocks) {
			List<Hop> roots = sb.getHops();
			if (roots == null)
				continue;
			for (Hop root : roots) {
				Hop found = findRefedTargetHop(root, visited);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	private static Hop findRefedTargetHop(Hop hop, Set<Long> visited) {
		if (hop == null || !visited.add(hop.getHopID()))
			return null;
		if (hop instanceof BinaryOp) {
			BinaryOp bin = (BinaryOp) hop;
			if (bin.getOp() == OpOp2.PLUS || bin.getOp() == OpOp2.MINUS) {
				Hop lhs = bin.getInput(0);
				Hop rhs = bin.getInput(1);
				if (isFederatedX(lhs) && rhs != null)
					return rhs;
				if (isFederatedX(rhs) && lhs != null)
					return lhs;
			}
		}
		if (hop.getInput() == null)
			return null;
		for (Hop input : hop.getInput()) {
			Hop found = findRefedTargetHop(input, visited);
			if (found != null)
				return found;
		}
		return null;
	}

	private static boolean isFederatedX(Hop hop) {
		return hop instanceof DataOp && "X".equals(hop.getName());
	}

	private static void forceFederatedParents(Hop hop) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return;
		for (Hop parent : hop.getParent()) {
			if (parent == null || !parent.getDataType().isMatrix())
				continue;
			parent.setForcedExecType(ExecType.FED);
			parent.setFederatedOutput(FederatedOutput.FOUT);
		}
	}

	private static void forceFederatedChildInput(Hop hop) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return;
		Hop input = hop.getInput(0);
		if (input == null || !input.getDataType().isMatrix())
			return;
		input.setForcedExecType(ExecType.FED);
		input.setFederatedOutput(FederatedOutput.FOUT);
	}
}

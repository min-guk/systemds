/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class FederatedPlanMemoTest {
	@Test public void exactSelectionIgnoresTreeSpecificResourceProperties() {
		String algorithm=System.getProperty(FederatedPlanMemo.ALGORITHM_PROPERTY);
		String property="sysds.fedplanner.memo.tree.factorCells";
		String cells=System.getProperty(property);
		try {
			System.clearProperty(FederatedPlanMemo.ALGORITHM_PROPERTY);
			System.setProperty(property,"invalid");
			new FederatedPlanMemo();
			System.setProperty(FederatedPlanMemo.ALGORITHM_PROPERTY,"TREE_DP_LOCAL");
			Assert.assertThrows(NumberFormatException.class,FederatedPlanMemo::new);
		}
		finally {
			if(algorithm==null) System.clearProperty(FederatedPlanMemo.ALGORITHM_PROPERTY);
			else System.setProperty(FederatedPlanMemo.ALGORITHM_PROPERTY,algorithm);
			if(cells==null) System.clearProperty(property); else System.setProperty(property,cells);
		}
	}
	@After public void clear() {
		MemoSupplyRegistry.clear(); MemoLoopRuntime.clear(); MemoPlanRegistry.clear();
	}
	@Test public void actualCompilerUsesMemoReceiptAndExecutesSharedDag() throws Exception {
		String output = execute("A=matrix(2,3,3);B=abs(A);C=B+1;D=B*2;print(sum(C+D));");
		Assert.assertTrue(output,output.contains("63"));
	}
	@Test public void loopEntryBackedgeAndFinalValueExecuteThroughNewCompiler() throws Exception {
		String output = execute("A=matrix(0,2,2);for(i in 1:3){A=A+1;}print(sum(A));");
		Assert.assertTrue(output,output.contains("12"));
		Assert.assertFalse(MemoLoopRuntime.snapshotAll().isEmpty());
		Assert.assertEquals(0,MemoLoopRuntime.backedgeTransfers());
	}
	@Test public void correlatedBranchAndInlineFunctionPreserveActualValues() throws Exception {
		String output = execute("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "p=as.scalar(matrix(1,1,1));if(p>0){A=matrix(-2,2,2);B=matrix(3,2,2);}"
			+ "else{A=matrix(-5,2,2);B=matrix(7,2,2);}C=f(A);print(sum(C+B));");
		Assert.assertTrue(output,output.contains("20"));
	}
	@Test public void protectedCanonicalTwoWorkerProgramCompilesAndExecutes() throws Exception {
		int port1 = org.apache.sysds.test.AutomatedTestBase.getRandomAvailablePort();
		int port2 = org.apache.sysds.test.AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1) port2 = org.apache.sysds.test.AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null, worker2 = null;
		Path directory = Files.createTempDirectory("memo-planner-runtime");
		try {
			for(int part = 0; part < 2; part++) {
				Path file = directory.resolve("part" + part + ".csv");
				Files.writeString(file,"-1,-2\n-3,-4\n");
				Files.writeString(Path.of(file + ".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\","
					+ "\"rows\":2,\"cols\":2,\"nnz\":4,\"format\":\"csv\",\"privacy\":\"private-aggregate\"}");
			}
			worker1 = org.apache.sysds.test.AutomatedTestBase.startLocalFedWorkerThread(port1,1000);
			worker2 = org.apache.sysds.test.AutomatedTestBase.startLocalFedWorkerThread(port2,1000);
			String source = "X=federated(addresses=list(\"localhost:" + port1 + "/" + directory.resolve("part0.csv")
				+ "\",\"localhost:" + port2 + "/" + directory.resolve("part1.csv")
				+ "\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
			String output = execute(source + "A=abs(X);print(sum(A));");
			Assert.assertTrue(output,output.contains("20"));
		}
		finally {
			org.apache.sysds.runtime.controlprogram.federated.FederatedData.clearFederatedWorkers();
			org.apache.sysds.test.TestUtils.shutdownThreads(worker1,worker2);
			try(var paths = Files.walk(directory)) {
				for(Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
	@Test public void zeroIterationsPreserveTheInitialValueAtExit() throws Exception {
		String output = execute("A=matrix(2,2,2);i=0;while(i>0){A=A+1;i=i-1;}print(sum(A));");
		Assert.assertTrue(output, output.lines().anyMatch(line -> line.trim().equals("8.0")));
		Assert.assertEquals(0, MemoLoopRuntime.backedgeTransfers());
	}
	@Test public void oneIterationUsesTheBodyValueAtExitWithoutBackedgeTransfer() throws Exception {
		String output = execute("A=matrix(2,2,2);for(i in 1:1){A=A+1;}print(sum(A));");
		Assert.assertTrue(output, output.lines().anyMatch(line -> line.trim().equals("12.0")));
		Assert.assertEquals(0, MemoLoopRuntime.backedgeTransfers());
	}
	@Test public void functionOutputReuseIsRejectedBeforePlanPublication() throws Exception {
		var previous = DMLScript.LINEAGE_REUSE;
		try {
			DMLProgram program = parse("A=matrix(1,2,2);print(sum(A));");
			DMLScript.LINEAGE_REUSE = org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType.REUSE_MULTILEVEL;
			IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
				() -> new FederatedPlanMemo().plan(program));
			Assert.assertTrue(failure.getMessage(),failure.getMessage().contains("MEMO_UNSUPPORTED_FUNCTION_OUTPUT_REUSE"));
			Assert.assertTrue(MemoSupplyRegistry.snapshotAll().isEmpty());
		}
		finally { DMLScript.LINEAGE_REUSE = previous; }
	}
	private static String execute(String script) throws Exception {
		DMLConfig old = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
		PrintStream stdout = System.out; ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = parse(script); DMLTranslator translator = new DMLTranslator(program);
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			translator.constructLops(program,captured::set);
			Assert.assertTrue(captured.get() instanceof FederatedPlanMemo.Receipt);
			var receipt = (FederatedPlanMemo.Receipt)captured.get();
			Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,receipt.solution().optimization().status());
			Program runtime = translator.getRuntimeProgram(program,config);
			System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
			runtime.execute(ExecutionContextFactory.createContext(runtime));
			return bytes.toString(StandardCharsets.UTF_8);
		}
		finally { System.setOut(stdout); ConfigurationManager.setLocalConfig(old); }
	}
	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}

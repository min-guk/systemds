/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/** Executes selected Memo plans against two real federated workers. */
public class MemoPlannerFederatedRuntimeTest {
	private static int port1;
	private static int port2;
	private static Thread worker1;
	private static Thread worker2;
	private static Path directory;
	private static String federatedSource;

	private record Execution(String output, FederatedPlanMemo.Receipt receipt,
		MemoPlanRegistry.SupplySchedule schedule) { }

	@BeforeClass
	public static void startWorkers() throws Exception {
		port1 = AutomatedTestBase.getRandomAvailablePort();
		port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		directory = Files.createTempDirectory("memo-planner-federated-runtime");
		writePart(0, "-1,-2\n-3,-4\n");
		writePart(1, "-1,-2\n-3,-4\n");
		worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
		worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
		federatedSource = "X=federated(addresses=list(\"localhost:" + port1 + "/"
			+ directory.resolve("part0.csv") + "\",\"localhost:" + port2 + "/"
			+ directory.resolve("part1.csv")
			+ "\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	@AfterClass
	public static void stopWorkers() throws Exception {
		FederatedData.clearFederatedWorkers();
		TestUtils.shutdownThreads(worker1, worker2);
		if(directory != null && Files.exists(directory))
			try(var paths = Files.walk(directory)) {
				for(Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
	}

	@After
	public void clearMemoState() {
		MemoSupplyRegistry.clear();
		MemoLoopRuntime.clear();
		MemoPlanRegistry.clear();
	}

	@Test
	public void guardedBranchUsesTheActualRowOrLocalDefinition() throws Exception {
		String body = "if(p>0){B=abs(X);}else{B=matrix(2,4,2);}print(sum(B));";
		Execution thenRun = execute(federatedSource + "p=as.scalar(matrix(1,1,1));" + body);
		Assert.assertTrue(thenRun.output(), thenRun.output().contains("20"));
		Assert.assertTrue("branch join must retain the ROW direct and LOCAL upload alternatives",
			thenRun.receipt().solution().supplies().values().stream().flatMap(java.util.List::stream)
				.anyMatch(supply -> supply.guarded() && supply.targetPartition() == MemoPartition.ROW
					&& supply.recipes().stream().anyMatch(recipe -> recipe.movement() == Movement.DIRECT
						&& recipe.sourcePartition() == MemoPartition.ROW)
					&& supply.recipes().stream().anyMatch(recipe -> recipe.movement() == Movement.UPLOAD
						&& recipe.sourcePartition() == MemoPartition.LOCAL)));

		clearMemoState();
		Execution elseRun = execute(federatedSource + "p=as.scalar(matrix(0,1,1));" + body);
		Assert.assertTrue(elseRun.output(), elseRun.output().contains("16"));
	}

	@Test
	public void loopCarriesTheFederatedValueWithoutBackedgeTransfer() throws Exception {
		Execution execution = execute(federatedSource
			+ "A=abs(X);for(i in 1:3){A=abs(A);}print(sum(A));");
		Assert.assertTrue(execution.output(), execution.output().contains("20"));
		Assert.assertFalse("the selected loop contract must be installed", MemoLoopRuntime.snapshotAll().isEmpty());
		Assert.assertEquals("a loop backedge is validation-only", 0, MemoLoopRuntime.backedgeTransfers());
	}

	@Test
	public void dynamicZeroOneAndManyLoopsPreserveTheActualFinalValue() throws Exception {
		for(int count : new int[] {0,1,2,3}) {
			clearMemoState();
			// The count comes from a runtime scalar operation, so planning must
			// preserve every cardinality rather than treating the cost estimate as a guard.
			Execution execution = execute(federatedSource
				+ "A=abs(X);n=as.scalar(matrix(" + count + ",1,1));i=0;"
				+ "while(i<n){A=A+1;i=i+1;}print(sum(A));");
			Assert.assertTrue("count=" + count + " output=" + execution.output(),
				printedNumber(execution.output(),20+8*count));
			Assert.assertEquals("only taken backedges are checked, never repaired",0,
				MemoLoopRuntime.backedgeTransfers());
		}
	}

	@Test
	public void zeroIterationSkipsASelectedEntryUploadAndOneIterationUploadsOnce() throws Exception {
		for(int count : new int[] {0,1,3}) {
			clearMemoState();
			Execution execution = execute("f=function(boolean flag,matrix[double] V) return(matrix[double] R){"
				+ "if(flag){R=abs(V);}else{R=abs(V);}}"
				+ federatedSource + "B=f(TRUE,X);A=matrix(-2,4,2);"
				+ "n=as.scalar(matrix(" + count + ",1,1));i=0;"
				+ "while(i<n){A=f(TRUE,A);i=i+1;}print(sum(A)+sum(B));");
			Assert.assertTrue("count=" + count + " output=" + execution.output(),
				printedNumber(execution.output(),count==0 ? 4 : 36));
			Assert.assertTrue("the shared FED body must require an entry upload for A",
				MemoLoopRuntime.snapshotAll().values().stream().flatMap(java.util.List::stream)
					.anyMatch(contract -> contract.variable().equals("A") && contract.entryRecipes().stream()
						.anyMatch(recipe -> recipe.movement()==Movement.UPLOAD)));
			Assert.assertEquals("zero paths must not execute selected entry movements",count==0 ? 0 : 1,
				MemoLoopRuntime.entryTransfers());
			Assert.assertEquals(0,MemoLoopRuntime.backedgeTransfers());
		}
	}

	@Test
	public void transposeMatrixMultiplyReleasesOnlyTheFinalAggregate() throws Exception {
		Execution execution = execute(federatedSource
			+ "v=matrix(1,4,1);Y=t(X)%*%v;print(sum(Y));");
		Assert.assertTrue(execution.output(), execution.output().contains("-20"));
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,
			 execution.receipt().solution().optimization().status());
	}

	@Test
	public void sameLayoutBranchDefinitionsKeepTheirActualPayload() throws Exception {
		String body = "if(p>0){B=matrix(2,4,2);}else{B=matrix(3,4,2);}"
			+ "C=X+B;D=X+B;print(sum(C)+sum(D));";
		Execution thenRun = execute(federatedSource + "p=as.scalar(matrix(1,1,1));" + body);
		Assert.assertTrue(thenRun.output(), printedNumber(thenRun.output(), -8));
		clearMemoState();
		Execution elseRun = execute(federatedSource + "p=as.scalar(matrix(0,1,1));" + body);
		Assert.assertTrue(elseRun.output(), printedNumber(elseRun.output(), 8));
	}

	@Test
	public void guardedLocalUploadRewiresOnlyNoncommutativeInputOne() throws Exception {
		String body = "f=function(boolean flag,matrix[double] U,matrix[double] V) return(matrix[double] R){"
			+ "if(flag){R=U-V;}else{R=U-V;}}"
			+ "A=f(TRUE,X,X);if(p>0){B=matrix(2,4,2);}else{B=matrix(3,4,2);}"
			+ "C=f(TRUE,B,matrix(1,4,2));print(sum(A)+sum(C));";
		for(int predicate : new int[] {1, 0}) {
			clearMemoState();
			Execution execution = execute(federatedSource + "p=as.scalar(matrix(" + predicate + ",1,1));" + body);
			double expected = predicate > 0 ? 8 : 16;
			Assert.assertTrue("predicate=" + predicate + " output=" + execution.output(),
				printedNumber(execution.output(), expected));
			Assert.assertTrue("the guarded LOCAL value must be uploaded into exact binary input position 1: "
				+ execution.receipt().solution().supplies(),
				execution.receipt().solution().supplies().values().stream().flatMap(java.util.List::stream)
					.anyMatch(supply -> supply.guarded() && supply.inputPosition() == 1
						&& supply.targetPartition() == MemoPartition.ROW
						&& supply.recipes().stream().anyMatch(recipe -> recipe.sourcePartition() == MemoPartition.LOCAL
							&& recipe.movement() == Movement.UPLOAD)));
		}
	}

	@Test
	public void repeatedFunctionReturnsRemainDistinctWhenEarlierReturnIsReused() throws Exception {
		Execution execution = execute("f=function(matrix[double] V) return(matrix[double] R){R=abs(V);};"
			+ federatedSource + "P=matrix(-2,4,2);Q=matrix(-3,4,2);"
			+ "A=f(P);B=f(Q);C=X+A;D=X+B;E=X+A;print(sum(C)+sum(D)+sum(E));");
		// X sums to -20: the three results sum to -4 + 4 -4.
		Assert.assertTrue(execution.output(), printedNumber(execution.output(), -4));
	}

	@Test
	public void twoReturnFunctionPreservesExactOutputSlotsAcrossSharedFedBody() throws Exception {
		Execution execution = execute("f=function(boolean flag,matrix[double] V) "
			+ "return(matrix[double] Y,matrix[double] Z){"
			+ "if(flag){Y=abs(V);Z=V*2;}else{Y=abs(V);Z=V*2;}}"
			+ federatedSource + "[XA,XB]=f(TRUE,X);P=matrix(-2,4,2);[A,B]=f(FALSE,P);"
			+ "print(sum(XA)+sum(XB)+sum(A)+sum(B)+sum(A)+sum(B));");
		Assert.assertTrue(execution.output(), printedNumber(execution.output(), -52));
		Assert.assertEquals("both exact output slots must be preserved by selected return metadata: "
			+ execution.schedule().returns(), java.util.Set.of(0, 1), execution.schedule().returns().stream()
				.map(MemoPlanRegistry.ReturnDefinitionSite::outputPosition)
				.collect(java.util.stream.Collectors.toSet()));
		Assert.assertEquals(2, execution.schedule().returns().stream()
			.map(MemoPlanRegistry.ReturnDefinitionSite::targetOriginValueId).distinct().count());
		Map<Integer, Long> alternativesPerOutput = execution.schedule().returns().stream()
			.collect(java.util.stream.Collectors.groupingBy(MemoPlanRegistry.ReturnDefinitionSite::outputPosition,
				java.util.stream.Collectors.mapping(MemoPlanRegistry.ReturnDefinitionSite::sourceOriginValueId,
					java.util.stream.Collectors.toSet())))
			.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
				entry -> (long) entry.getValue().size()));
		Assert.assertEquals("each output slot must retain both guarded reaching definitions: "
			+ execution.schedule().returns(), Map.of(0, 2L, 1, 2L), alternativesPerOutput);
	}

	private static boolean printedNumber(String output, double expected) {
		return output.lines().anyMatch(line -> {
			try { return Double.parseDouble(line.trim()) == expected; }
			catch(NumberFormatException ignored) { return false; }
		});
	}

	private static void writePart(int part, String values) throws Exception {
		Path file = directory.resolve("part" + part + ".csv");
		Files.writeString(file, values);
		Files.writeString(Path.of(file + ".mtd"), "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"rows\":2,\"cols\":2,\"nnz\":4,\"format\":\"csv\","
			+ "\"privacy\":\"private-aggregate\"}");
	}

	private static Execution execute(String script) throws Exception {
		DMLConfig old = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig();
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "COMPILE_MEMO");
		PrintStream stdout = System.out;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = parse(script);
			DMLTranslator translator = new DMLTranslator(program);
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			translator.constructLops(program, captured::set);
			Assert.assertTrue(captured.get() instanceof FederatedPlanMemo.Receipt);
			FederatedPlanMemo.Receipt receipt = (FederatedPlanMemo.Receipt) captured.get();
			Assert.assertEquals(MemoOptimizer.Status.OPTIMAL, receipt.solution().optimization().status());
			Program runtime = translator.getRuntimeProgram(program, config);
			System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
			runtime.execute(ExecutionContextFactory.createContext(runtime));
			return new Execution(bytes.toString(StandardCharsets.UTF_8), receipt,
				MemoPlanRegistry.schedule(program));
		}
		finally {
			System.setOut(stdout);
			ConfigurationManager.setLocalConfig(old);
		}
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** One selected cost/action must correspond to one real upload across two invocations. */
public class MemoSharedSupplyRuntimeTest {
	@Test
	public void sharedUploadMatchesOriginalCostScheduleExecutionAndCleanup() throws Exception {
		int port1=AutomatedTestBase.getRandomAvailablePort();
		int port2=AutomatedTestBase.getRandomAvailablePort();
		while(port1==port2) port2=AutomatedTestBase.getRandomAvailablePort();
		Path directory=Files.createTempDirectory("memo-shared-supply");
		Thread worker1=null,worker2=null;
		DMLConfig old=ConfigurationManager.getDMLConfig();
		PrintStream stdout=System.out;
		try {
			for(int part=0;part<2;part++) {
				Path file=directory.resolve("part"+part+".csv");
				Files.writeString(file,"-1,-2\n-3,-4\n");
				Files.writeString(Path.of(file+".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\","
					+"\"rows\":2,\"cols\":2,\"nnz\":4,\"format\":\"csv\",\"privacy\":\"private-aggregate\"}");
			}
			worker1=AutomatedTestBase.startLocalFedWorkerThread(port1,1000);
			worker2=AutomatedTestBase.startLocalFedWorkerThread(port2,1000);
			String script="f=function(boolean flag,matrix[double] U,matrix[double] V) return(matrix[double] R){"
				+"if(flag){R=U-V;}else{R=U-V;}}X=federated(addresses=list(\"localhost:"+port1+"/"
				+directory.resolve("part0.csv")+"\",\"localhost:"+port2+"/"+directory.resolve("part1.csv")
				+"\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
				+"B=matrix(2,4,2);Q=f(TRUE,X,X);C=f(TRUE,X,B);D=f(TRUE,X,B);print(sum(Q)+sum(C)+sum(D));";
			DMLConfig config=new DMLConfig();
			config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
			DMLTranslator translator=new DMLTranslator(program);
			translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
			MemoProgramFacts facts=MemoProgramFacts.analyze(program);
			MemoSources sources=MemoSources.acquire(facts,null);
			var universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
			MemoProgramModel original=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
			var originalSolution=original.optimize(MemoOptimizer.Limits.exactDefaults());
			var beforeCost=original.evaluateCost(originalSolution.optimization().selectedRules());
			AtomicReference<PlannerInvocationReceipt> capture=new AtomicReference<>();
			translator.constructLops(program,capture::set);
			FederatedPlanMemo.Receipt receipt=(FederatedPlanMemo.Receipt) capture.get();
			Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,receipt.solution().optimization().status());
			var cost=original.evaluateCost(receipt.solution().optimization().selectedRules());
			Assert.assertEquals("before="+beforeCost+" after="+cost+" emitted="
				+receipt.solution().optimization().cost(),0,
				cost.total().compareTo(receipt.solution().optimization().cost()));
			int generated=facts.occurrences().stream().filter(o -> o.hop() instanceof DataGenOp)
				.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
			BigDecimal oneUpload=new BigDecimal(MemoCostModel.movementCost(universe.shapes().view(generated),
				MemoPartition.LOCAL,MemoPartition.ROW,2,1));
			Assert.assertEquals("the original objective charges the retained upload once",0,
				oneUpload.compareTo(cost.movement()));
			var schedule=MemoPlanRegistry.schedule(program);
			var actions=schedule.scopes().stream().flatMap(scope -> scope.actions().stream()).toList();
			Assert.assertEquals("two calls share one explicit action",1,actions.size());
			Assert.assertEquals(MemoSupplyRegistry.Movement.UPLOAD,actions.get(0).recipe().movement());
			Assert.assertTrue(schedule.scopes().stream()
				.allMatch(scope -> scope.kind()==MemoPlanRegistry.ScopeKind.PROGRAM_ROOT));
			Assert.assertFalse(schedule.edges().isEmpty());
			Assert.assertTrue(schedule.edges().stream().allMatch(edge -> edge.inputPosition()==1
				&& edge.action().equals(actions.get(0))));
			Program runtime=translator.getRuntimeProgram(program,config);
			ExecutionContext ec=ExecutionContextFactory.createContext(runtime);
			AtomicReference<PlannedSupplyRuntime.Session> session=new AtomicReference<>();
			ByteArrayOutputStream bytes=new ByteArrayOutputStream();
			try(var mocked=Mockito.mockStatic(PlannedSupplyRuntime.class,Mockito.CALLS_REAL_METHODS)) {
				mocked.when(() -> PlannedSupplyRuntime.open(Mockito.eq(program),Mockito.any(ExecutionContext.class)))
					.thenAnswer(invocation -> {
						Object execution=invocation.callRealMethod();
						session.set(((ExecutionContext)invocation.getArgument(1)).getPlannedSupplySession());
						return execution;
					});
				System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
				runtime.execute(ec);
			}
			String output=bytes.toString(StandardCharsets.UTF_8);
			Assert.assertTrue(output,output.lines().anyMatch(line -> {
				try { return Double.parseDouble(line.trim())==-72; }
				catch(NumberFormatException ignored) { return false; }
			}));
			Assert.assertNotNull(session.get());
			var statistics=session.get().statistics();
			Assert.assertEquals(1,statistics.materializations());
			Assert.assertEquals(1,statistics.reuses());
			Assert.assertEquals(0,statistics.liveLeases());
			Assert.assertEquals(0,statistics.pinnedSources());
			Assert.assertNull(ec.getPlannedSupplySession());
		}
		finally {
			System.setOut(stdout); ConfigurationManager.setLocalConfig(old);
			MemoSupplyRegistry.clear(); MemoPlanRegistry.clear(); FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1,worker2);
			try(var paths=Files.walk(directory)) {
				for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
}

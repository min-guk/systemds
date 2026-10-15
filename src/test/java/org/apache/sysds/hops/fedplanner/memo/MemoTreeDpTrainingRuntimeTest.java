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
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Small training execution with two real workers; sealed workload planning is benchmarked separately. */
public class MemoTreeDpTrainingRuntimeTest {
	@Test public void protectedGradientTrainingMatchesIndependentArithmeticForAllAlgorithms() throws Exception {
		String previous=System.getProperty(FederatedPlanMemo.ALGORITHM_PROPERTY);
		DMLConfig old=ConfigurationManager.getDMLConfig();
		Path directory=Files.createTempDirectory("memo-tree-training");
		Thread worker1=null,worker2=null;
		int port1=AutomatedTestBase.getRandomAvailablePort(),port2=AutomatedTestBase.getRandomAvailablePort();
		while(port1==port2) port2=AutomatedTestBase.getRandomAvailablePort();
		try {
			for(int part=0;part<2;part++) {
				Path file=directory.resolve("X"+part+".csv");
				Files.writeString(file,part==0 ? "1,0\n0,1\n" : "1,1\n2,1\n");
				Files.writeString(Path.of(file+".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\","
					+"\"rows\":2,\"cols\":2,\"nnz\":"+(part==0 ? 2 : 4)
					+",\"format\":\"csv\",\"privacy\":\"private-aggregate\"}");
			}
			worker1=AutomatedTestBase.startLocalFedWorkerThread(port1,1000);
			worker2=AutomatedTestBase.startLocalFedWorkerThread(port2,1000);
			String source="X=federated(addresses=list(\"localhost:"+port1+"/"+directory.resolve("X0.csv")
				+"\",\"localhost:"+port2+"/"+directory.resolve("X1.csv")
				+"\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
			double[][] x={{1,0},{0,1},{1,1},{2,1}};
			for(boolean logistic:new boolean[] {false,true}) {
				double[] expected={0,0};
				for(int step=0;step<3;step++) {
					double[] gradient={0,0};
					for(double[] row:x) {
						double prediction=row[0]*expected[0]+row[1]*expected[1];
						if(logistic) prediction=1/(1+Math.exp(-prediction));
						for(int column=0;column<2;column++) gradient[column]+=row[column]*(prediction-1);
					}
					for(int column=0;column<2;column++) expected[column]-=0.1*gradient[column];
				}
				String body="w=matrix(0,rows=2,cols=1);y=matrix(1,rows=4,cols=1);for(i in 1:3){"
					+"p=X%*%w;"+(logistic ? "p=1/(1+exp(-p));" : "")
					+"g=t(X)%*%(p-y);w=w-0.1*g;}print(as.scalar(w[1,1]));print(as.scalar(w[2,1]));";
				java.math.BigDecimal optimum=null;
				for(FederatedPlanMemo.Algorithm algorithm:FederatedPlanMemo.Algorithm.values()) {
					clear(); System.setProperty(FederatedPlanMemo.ALGORITHM_PROPERTY,algorithm.name());
					DMLConfig config=new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
					ConfigurationManager.setLocalConfig(config);
					DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
						source+body,new HashMap<>());
					DMLTranslator translator=new DMLTranslator(program);
					translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
					AtomicReference<PlannerInvocationReceipt> receipt=new AtomicReference<>();
					translator.constructLops(program,receipt::set);
					Assert.assertTrue(receipt.get() instanceof FederatedPlanMemo.Receipt);
					MemoProgramModel.Solution solution=((FederatedPlanMemo.Receipt)receipt.get()).solution();
					if(algorithm==FederatedPlanMemo.Algorithm.EXACT) {
						Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,solution.optimization().status());
						optimum=solution.optimization().cost();
					}
					else if(algorithm==FederatedPlanMemo.Algorithm.MEMO_GLOBAL
						|| algorithm==FederatedPlanMemo.Algorithm.MEMO_LOCAL) {
						Assert.assertNotNull(solution.dualModeCertificate());
						Assert.assertTrue(solution.dualModeCertificate().certified());
						Assert.assertTrue(solution.dualModeCertificate().lowerBound().compareTo(optimum)<=0);
						Assert.assertTrue(solution.dualModeCertificate().upperBound().compareTo(optimum)>=0);
						Assert.assertTrue(solution.dualModeCertificate().upperBound()
							.compareTo(optimum.multiply(new java.math.BigDecimal("1.05")))<=0);
						if(algorithm==FederatedPlanMemo.Algorithm.MEMO_GLOBAL)
							Assert.assertEquals(0,solution.dualModeCertificate().upperBound().compareTo(optimum));
					}
					else {
						Assert.assertNotNull(solution.treeDpCertificate());
						Assert.assertTrue(solution.treeDpCertificate().certified());
						Assert.assertTrue(solution.treeDpCertificate().lowerBound().compareTo(optimum)<=0);
						Assert.assertTrue(solution.treeDpCertificate().upperBound().compareTo(optimum)>=0);
						Assert.assertTrue(solution.treeDpCertificate().upperBound()
							.compareTo(optimum.multiply(new java.math.BigDecimal("1.05")))<=0);
					}
					Program runtime=translator.getRuntimeProgram(program,config);
					PrintStream saved=System.out; ByteArrayOutputStream output=new ByteArrayOutputStream();
					try {
						System.setOut(new PrintStream(output,true,StandardCharsets.UTF_8));
						runtime.execute(ExecutionContextFactory.createContext(runtime));
					}
					finally { System.setOut(saved); }
					String result=output.toString(StandardCharsets.UTF_8);
					java.util.List<Double> values=new java.util.ArrayList<>();
					for(String line:result.lines().toList()) try { values.add(Double.valueOf(line.trim())); }
					catch(NumberFormatException ignored) { /* runtime diagnostics are not model output */ }
					Assert.assertEquals(result,2,values.size());
					for(int column=0;column<2;column++) Assert.assertEquals(algorithm+" "+result,
						expected[column],values.get(column),1e-10);
					System.out.println("TREE_DP_TRAINING kind="+(logistic ? "logistic" : "linear")+" algorithm="+algorithm
						+" status="+solution.optimization().status()+" weights="+values+" cost="+solution.optimization().cost());
				}
			}
		}
		finally {
			if(previous==null) System.clearProperty(FederatedPlanMemo.ALGORITHM_PROPERTY);
			else System.setProperty(FederatedPlanMemo.ALGORITHM_PROPERTY,previous);
			ConfigurationManager.setLocalConfig(old); clear(); FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1,worker2);
			try(var paths=Files.walk(directory)) {
				for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
	private static void clear() { MemoSupplyRegistry.clear(); MemoLoopRuntime.clear(); MemoPlanRegistry.clear(); }
}

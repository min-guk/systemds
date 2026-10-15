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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.junit.Assert;
import org.junit.Test;

/** Real parsed program coverage for Tree-DP, checked against direct full enumeration. */
public class MemoTreeDpModelTest {
	@Test public void parsedFunctionsBranchesLoopsAndSharedProducersMatchEnumeration() throws Exception {
		List<String> scripts=List.of(
			"A=abs(P);B=A+1;C=A*2;print(sum(B)+sum(C));",
			"p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=P;}else{A=matrix(2,4,2);}"+
				"B=abs(A);print(sum(B));",
			"p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=P;B=P;}else{"+
				"A=matrix(2,4,2);B=matrix(3,4,2);}print(sum(A+B));",
			"A=P;for(i in seq(1,0,1)){A=abs(A);}print(sum(A));",
			"A=P;for(i in 1:1){A=abs(A);}print(sum(A));",
			"A=P;for(i in 1:2){A=abs(A);}print(sum(A));",
			"f=function(boolean p,matrix[double] X) return(matrix[double] Y){"+
				"if(p){Y=abs(X);}else{Y=X;}}A=f(TRUE,P);B=f(FALSE,P);print(sum(A)+sum(B));");
		for(String script:scripts) assertParsedExact(script);
	}

	private static void assertParsedExact(String script) throws Exception {
		MemoProgramModel model=model("P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"+
			"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"+script);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(model)).clone();
		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("parsed fixture must remain exhaustible: "+combinations,combinations<=500_000);
		BigDecimal optimum=null;
		int[] selected=new int[sizes.length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean legal=true;
			for(MemoOptimizer.Check check:model.checks()) {
				int[] owners=check.owners(),tuple=new int[owners.length];
				for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
				MemoOptimizer.Truth truth=check.evaluate(tuple);
				Assert.assertNotEquals(MemoOptimizer.Truth.UNKNOWN,truth);
				if(truth==MemoOptimizer.Truth.NO) { legal=false; break; }
			}
			if(legal) {
				BigDecimal cost=model.evaluateCost(selected).total();
				if(optimum==null || cost.compareTo(optimum)<0) optimum=cost;
			}
			increment(selected,sizes);
		}
		Assert.assertNotNull(optimum);
		MemoProgramModel.Solution exact=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,exact.optimization().status());
		Assert.assertEquals(0,optimum.compareTo(exact.optimization().cost()));
		for(MemoTreeDpOptimizer.Mode mode:MemoTreeDpOptimizer.Mode.values()) {
			MemoProgramModel.Solution solution=model.optimizeTreeDp(mode,MemoTreeDpOptimizer.Limits.defaults());
			Assert.assertTrue(solution.diagnostics().toString(),solution.treeDpCertificate().certified());
			Assert.assertTrue(solution.treeDpCertificate().lowerBound().compareTo(optimum)<=0);
			Assert.assertTrue(optimum.compareTo(solution.treeDpCertificate().upperBound())<=0);
			Assert.assertTrue(solution.treeDpCertificate().upperBound()
				.compareTo(optimum.multiply(new BigDecimal("1.05")))<=0);
			if(mode==MemoTreeDpOptimizer.Mode.GLOBAL) {
				Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,solution.optimization().status());
				Assert.assertEquals(0,optimum.compareTo(solution.optimization().cost()));
				int[] treeSelection=solution.optimization().selectedRules();
				Assert.assertEquals(reconstruct(model,treeSelection),solution.supplies());
				Assert.assertEquals(contractSignatures(model.loopContracts(treeSelection)),
					contractSignatures(model.loopContracts(treeSelection.clone())));
			}
		}
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int i=selected.length-1;i>=0;i--) {
			if(++selected[i]<sizes[i]) return;
			selected[i]=0;
		}
	}

	private static List<String> contractSignatures(
		Map<?,List<MemoLoopRuntime.Contract>> contracts) {
		List<String> result=new java.util.ArrayList<>();
		for(var entry:contracts.entrySet()) for(MemoLoopRuntime.Contract contract:entry.getValue())
			result.add(entry.getKey().toString()+"|"+contract.variable()+"|"+contract.dataType()+"|"+
				contract.entryValueType()+"|"+contract.valueType()+"|"+contract.target()+"|"+
				contract.entryRows()+"|"+contract.entryColumns()+"|"+contract.repeatRows()+"|"+
				contract.repeatColumns()+"|"+contract.entryRequired()+"|"+
				contract.localResidencyApproved()+"|"+contract.entryRecipes()+"|"+
				(contract.pool()==null ? "null" : contract.pool().encode()));
		result.sort(String::compareTo);
		return result;
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name); result.setAccessible(true); return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<Long,List<MemoSupplyRegistry.Supply>> reconstruct(MemoProgramModel model,
		int[] selected) throws Exception {
		var method=MemoProgramModel.class.getDeclaredMethod("reconstructSupplies",int[].class);
		method.setAccessible(true);
		return (Map<Long,List<MemoSupplyRegistry.Supply>>)method.invoke(model,(Object)selected.clone());
	}

	private static MemoProgramModel model(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"P2")))));
		return new MemoProgramModel(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()),
			MemoProgramModel.Profile.defaults());
	}
}

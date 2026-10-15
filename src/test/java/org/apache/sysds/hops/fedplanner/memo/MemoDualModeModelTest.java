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
import org.junit.Assert;
import org.junit.Test;

/** Original-model integration checks for function, control-flow and shared-producer dependencies. */
public class MemoDualModeModelTest {
	@Test public void parsedProgramsMatchOriginalCartesianOracleAndReconstruction() throws Exception {
		List<String> scripts=List.of(
			// One producer with two consumers exercises shared movement ownership.
			"A=abs(P);B=A+1;C=A*2;print(sum(B)+sum(C));",
			// Both branch exits must remain part of the frozen candidate model.
			"p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=P;B=P;}else{"+
				"A=matrix(2,4,2);B=matrix(3,4,2);}print(sum(A+B));",
			// Entry, head, backedge and exit costs are represented by the loop contract.
			"A=P;for(i in 1:2){A=abs(A);}B=A+1;print(sum(B));",
			// Two function call contexts share source P but choose independently.
			"f=function(boolean p,matrix[double] X) return(matrix[double] Y){"+
				"if(p){Y=abs(X);}else{Y=X;}}A=f(TRUE,P);B=f(FALSE,P);print(sum(A)+sum(B));");
		for(String script:scripts) assertParsedProgram(script);
	}

	private static void assertParsedProgram(String body) throws Exception {
		MemoProgramModel model=model("P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"+
			"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"+body);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(model)).clone();
		Oracle oracle=enumerate(model,sizes);
		Assert.assertNotNull(oracle.cost());

		for(MemoDualModeOptimizer.Mode mode:MemoDualModeOptimizer.Mode.values()) {
			for(boolean direct:new boolean[] {true,false}) {
				MemoDualModeOptimizer.Options options=MemoDualModeOptimizer.Options.defaults()
					.withDirectExact(direct).withTrace(true);
				MemoProgramModel.Solution solution=model.optimizeDualMode(mode,options);
				MemoDualModeOptimizer.Result certificate=solution.dualModeCertificate();
				Assert.assertTrue(solution.diagnostics().toString(),certificate.certified());
				Assert.assertTrue(certificate.lowerBound().compareTo(oracle.cost())<=0);
				Assert.assertTrue(oracle.cost().compareTo(certificate.upperBound())<=0);
				Assert.assertTrue(certificate.upperBound()
					.compareTo(oracle.cost().multiply(new BigDecimal("1.05")))<=0);
				Assert.assertEquals(0,model.evaluateCost(certificate.selectedRules()).total()
					.compareTo(certificate.upperBound()));
				Assert.assertEquals(reconstruct(model,certificate.selectedRules()),solution.supplies());
				if(mode==MemoDualModeOptimizer.Mode.GLOBAL) {
					Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,certificate.status());
					Assert.assertEquals(0,oracle.cost().compareTo(certificate.upperBound()));
					Assert.assertArrayEquals(oracle.selected(),certificate.selectedRules());
				}
			}
		}
	}

	private record Oracle(BigDecimal cost,int[] selected) {
		private Oracle { selected=selected==null ? null : selected.clone(); }
	}

	private static Oracle enumerate(MemoProgramModel model,int[] sizes) {
		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("parsed fixture must remain independently exhaustible: "+combinations,
			combinations<=500_000);
		BigDecimal best=null;
		int[] winner=null;
		int[] selected=new int[sizes.length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean legal=true;
			for(MemoOptimizer.Check check:model.checks()) {
				int[] owners=check.owners(),tuple=new int[owners.length];
				for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
				MemoOptimizer.Truth truth=check.evaluate(tuple);
				Assert.assertNotEquals("complete original tuple remained UNKNOWN",MemoOptimizer.Truth.UNKNOWN,truth);
				if(truth==MemoOptimizer.Truth.NO) { legal=false; break; }
			}
			if(legal) {
				BigDecimal cost=model.evaluateCost(selected).total();
				if(best==null || cost.compareTo(best)<0) {
					best=cost;
					winner=selected.clone();
				}
			}
			increment(selected,sizes);
		}
		return new Oracle(best,winner);
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int owner=selected.length-1;owner>=0;owner--) {
			if(++selected[owner]<sizes[owner]) return;
			selected[owner]=0;
		}
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name);
		result.setAccessible(true);
		return result;
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

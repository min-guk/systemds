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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactAliasGetCoalescingTest {
	@Test public void twoReadsOfOneStableValueCollectOnlyOnce() throws Exception { assertGets(false, 1); }
	@Test public void updatedValueIsNotMergedWithItsPredecessor() throws Exception { assertGets(true, 2); }

	@Test public void mutuallyExclusiveBranchesKeepConditionalCosts() throws Exception {
		assertGets("if(sum(rand(rows=1,cols=1,seed=7))>0.5){print(sum(S));}else{print(sum(S));}", 2);
	}
	@Test public void repeatedBranchArmsShareTheStableSourceLifetime() throws Exception {
		assertGets("for(i in 1:4){if(sum(rand(rows=1,cols=1,seed=7))>0.5){print(sum(S));}else{print(sum(S));}}", 1);
	}
	private static void assertGets(boolean updated, int copies) throws Exception {
		assertGets("for(i in 1:2){print(sum(S));}" + (updated ? "S=rowSums(X+1);" : "")
			+ "for(j in 1:3){print(sum(S));}", copies);
	}
	private static void assertGets(String suffix, int copies) throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));S=rowSums(X);"
			+ suffix;
		DMLProgram p = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator t = new DMLTranslator(p);
		t.liveVariableAnalysis(p); t.validateParseTree(p); t.constructHops(p); t.rewriteHopsDAG(p);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(p);
		PlacementAnalysis a = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(p);
		var model = ExactPhysicalModel.build(a);
		var surface = ExactPhysicalCostModel.physicalCostSurface(a, model);
		var reads = model.domains().stream().filter(d -> a.hop(d.node().key()).orElse(null)
			instanceof DataOp read && read.getOp() == OpOpData.TRANSIENTREAD && read.getName().equals("S"))
			.filter(d -> a.compiledInputEdgesInCanonicalOrder().stream().anyMatch(e -> e.producer() == d.node().key()
				&& a.hop(e.consumer()).orElse(null) instanceof AggUnaryOp)).toList();
		Assert.assertEquals(2, reads.size());
		List<Integer> values = new ArrayList<>();
		for(var d : model.domains()) {
			int chosen = 0;
			if(reads.contains(d)) {
				chosen = -1;
				for(int i = 0; i < d.alternatives().size(); i++)
					if(d.alternatives().get(i).state().output() == FederatedOutput.FOUT) {chosen=i; break;}
				Assert.assertTrue("Read must expose FOUT cost cell", chosen >= 0);
			}
			values.add(chosen);
		}
		double both = getCost(surface, reads, values);
		List<Integer> firstOnly = new ArrayList<>(values), secondOnly = new ArrayList<>(values);
		for(int r = 0; r < reads.size(); r++) {
			var read = reads.get(r);
			int local = -1;
			for(int v = 0; v < read.alternatives().size(); v++)
				if(read.alternatives().get(v).state().output() == FederatedOutput.LOUT) { local = v; break; }
			Assert.assertTrue(local >= 0);
			(r == 0 ? secondOnly : firstOnly).set(model.domains().indexOf(read), local);
		}
		double first = getCost(surface, reads, firstOnly), second = getCost(surface, reads, secondOnly);
		Assert.assertTrue(first > 0 && second > 0);
		Assert.assertEquals(first, second, 1e-9);
		Assert.assertEquals("GETs are owned by produced values, not by the number of TRead nodes",
			copies == 1 ? first : first + second, both, 1e-9);
	}

	private static double getCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		List<ExactPhysicalModel.DecisionDomain> reads, List<Integer> values) {
		return surface.contributions().stream().filter(c -> c.factor().scope().size() > 1
			&& reads.stream().anyMatch(d -> c.factor().scope().get(0) == d.variable()))
			.mapToDouble(c -> surface.evaluateContributionCanonical(c, values)).sum();
	}
}

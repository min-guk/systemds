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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Independent finite placement universe, including choices absent from generated domains. */
public class LoopEntryCompletePlacementSpaceTest {
	private record Fixture(PlacementAnalysis analysis, ExactPhysicalModel model) { }

	@Test
	public void identityLoopAdmitsExactlyLocalAndEveryExecutableEntryLayout() throws Exception {
		Fixture fixture = fixture(3);
		ExactPhysicalModel model = fixture.model();
		BigInteger size = ExactPhysicalRawSpaceExporter.size(model);
		Assert.assertTrue("fixture must remain exhaustible: " + size, size.compareTo(BigInteger.valueOf(100000)) < 0);
		Set<String> actual = new LinkedHashSet<>();
		int[] admitted = {0};
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO, size, row -> {
			Assert.assertNotEquals(row.reason(), ExactPhysicalRawSpaceExporter.Status.UNKNOWN, row.status());
			if(row.status() != ExactPhysicalRawSpaceExporter.Status.EMITTED)
				return;
			admitted[0]++;
			ExactPhysicalSelection selection = validate(model, row);
			List<String> states = new ArrayList<>();
			String initialState = null;
			int uploads = 0;
			for(int i = 0; i < model.domains().size(); i++) {
				var domain = model.domains().get(i);
				var hop = fixture.analysis().hop(domain.node().key()).orElseThrow();
				if(!(hop instanceof DataGenOp) && !(hop instanceof DataOp && "p".equals(hop.getName())))
					continue;
				var alternative = domain.alternatives().get(row.values().get(i));
				var state = alternative.state();
				boolean initializer = hop instanceof DataGenOp;
				Assert.assertEquals(initializer || state.output() == FederatedOutput.LOUT ? ExecType.CP : ExecType.FED,
					state.execType());
				String layout = state.output() == FederatedOutput.LOUT ? "LOCAL" : state.fType().name();
				if(initializer) initialState = layout;
				else states.add(layout);
				if(alternative.derivedFoutAction() != null) {
					Assert.assertTrue("only the initializer can upload in this identity fixture", initializer);
					uploads++;
				}
			}
			Assert.assertEquals("entry write, loop read and back write", 3, states.size());
			Assert.assertEquals("every reaching version must have the same stored layout", 1, Set.copyOf(states).size());
			Assert.assertTrue("identity transport needs no per-iteration relocation", selection.emittedRelocations().isEmpty());
			actual.add(initialState + ">" + states.get(0) + ":entryUploads=" + uploads);
		});
		// W=2, shape=8x2: local plus ROW, COL and BROADCAST; FULL is not a two-worker map.
		Set<String> expected = new LinkedHashSet<>(Set.of("LOCAL>LOCAL:entryUploads=0"));
		for(String layout : List.of("ROW", "COL", "BROADCAST")) {
			expected.add(layout + ">" + layout + ":entryUploads=1");
			// A selected producer upload may be gathered by a CP entry writer. It is
			// unnecessary for this fixture, but remains a valid, more expensive plan.
			expected.add(layout + ">LOCAL:entryUploads=1");
		}
		System.out.println("LOOP_ENTRY_COMPLETE raw=" + size + " admitted=" + admitted[0] + " physical=" + actual.size());
		Assert.assertEquals("missing or extra complete physical plans", expected, actual);
	}

	@Test
	public void uploadUnaryCostIsPositiveAndIndependentOfInnerTripCount() throws Exception {
		Double expected = null;
		for(int trips : List.of(1, 2, 10)) {
			Fixture fixture = fixture(trips);
			var domain = fixture.model().domains().stream().filter(d ->
				fixture.analysis().hop(d.node().key()).orElseThrow() instanceof DataGenOp).findFirst().orElseThrow();
			int local = -1, remote = -1;
			for(int i = 0; i < domain.alternatives().size(); i++) {
				var state = domain.alternatives().get(i).state();
				if(state.output() == FederatedOutput.LOUT) local = i;
				if(state.output() == FederatedOutput.FOUT && "ROW".equals(state.fType().name())) remote = i;
			}
			Assert.assertTrue(local >= 0 && remote >= 0);
			var surface = ExactPhysicalCostModel.physicalCostSurface(fixture.analysis(), fixture.model());
			double cost = 0;
			for(var factor : surface.factors())
				if(factor.scope().equals(List.of(domain.variable())))
					cost += factor.cost(new int[] {remote}) - factor.cost(new int[] {local});
			Assert.assertTrue("a real upload must be priced", Double.isFinite(cost) && cost > 0);
			if(expected == null) expected = cost;
			else Assert.assertEquals("entry upload must not scale with T", expected, cost, 0.0);
		}
	}

	private static ExactPhysicalSelection validate(ExactPhysicalModel model, ExactPhysicalRawSpaceExporter.Row row) {
		var stats = new ExactCategoricalSolver.Statistics(List.of(), 0, 1, 1, 1, 1);
		var result = new ExactCategoricalSolver.Result(0.0, row.values(), stats);
		return ExactPhysicalSelection.create(model, new ExactPhysicalOptimizer.Result(result,
			Double.doubleToRawLongBits(0.0), "loop-entry-complete-space"));
	}

	private static Fixture fixture(int trips) throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=rand(rows=8,cols=2,seed=7);for(i in 1:" + trips + "){p=p;}print(sum(X));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program); // retain the identity backedge for the bounded oracle
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		return new Fixture(analysis, ExactPhysicalModel.build(analysis));
	}
}

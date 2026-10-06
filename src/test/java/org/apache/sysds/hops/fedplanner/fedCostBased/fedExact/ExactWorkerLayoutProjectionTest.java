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

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactWorkerLayoutProjectionTest {
	@Test
	public void skewChangesProductionUnaryAtFixedLogicalShape() throws Exception {
		Assert.assertTrue("Same logical shape and W do not imply equal critical-worker work",
			unaryAtSplit(800) > unaryAtSplit(500));
	}

	private static double unaryAtSplit(int split) throws Exception {
		var analysis = analysis(split);
		var model = ExactPhysicalModel.build(analysis);
		var method = ExactPhysicalCostModel.class.getDeclaredMethod("addPhysicalUnaryFactor",
			PlacementAnalysis.class, PlacementCostSemantics.ExpectedSparseAssignmentEstimates.class,
			ExactPhysicalModel.DecisionDomain.class, int.class,
			org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.class, List.class);
		method.setAccessible(true);
		for(var domain : model.domains()) {
			if(!(analysis.hop(domain.node().key()).orElseThrow() instanceof BinaryOp))
				continue;
			for(int value = 0; value < domain.alternatives().size(); value++) {
				var alternative = domain.alternatives().get(value);
				if(alternative.state().execType() != ExecType.FED
					|| alternative.state().output() != FederatedOutput.FOUT
					|| alternative.orderedInputs().isEmpty()
					|| alternative.orderedInputs().get(0).fType() != FType.ROW)
					continue;
				List<ExactCategoricalSolver.Factor> factors = new java.util.ArrayList<>();
				method.invoke(null, analysis, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis),
					domain, 2, analysis.executionFrequencyFacts(), factors);
				return factors.get(0).cost(new int[] {value});
			}
		}
		throw new AssertionError("Missing partitioned binary fixture");
	}
	@Test
	public void dynamicEndpointWitnessDoesNotAuthorizeStaleOperandRanges() {
		var dynamic = CandidateEmissionRealization.nativeLineageDynamicLayout(emission(), "dynamic",
			anchor("witness"), List.of(proof()), List.of());
		var layout = ExactPhysicalCostModel.realizationInputLayout(dynamic);
		Assert.assertEquals(FType.ROW, layout.fType());
		Assert.assertFalse(layout.exactRanges());
		Assert.assertTrue(layout.ranges().isEmpty());
	}

	@Test
	public void exactNativeRangesAreProjectedWithoutProofOrFileIdentity() {
		var left = CandidateEmissionRealization.nativeLineage(emission(), "left-proof",
			anchor("X"), List.of(proof()), List.of());
		var right = CandidateEmissionRealization.nativeLineage(emission(), "right-proof",
			anchor("Y"), List.of(proof()), List.of());
		var layout = ExactPhysicalCostModel.realizationInputLayout(left);
		Assert.assertTrue(layout.exactRanges());
		Assert.assertEquals(2, layout.ranges().size());
		Assert.assertEquals("Equivalent physical input layouts share a price despite different receipts/files",
			layout, ExactPhysicalCostModel.realizationInputLayout(right));
	}

	@Test
	public void productionInputProjectionRetainsSkewAtEqualWorkerCount() throws Exception {
		PlacementAnalysis analysis = analysis();
		var model = ExactPhysicalModel.build(analysis);
		for(var domain : model.domains()) {
			if(!(analysis.hop(domain.node().key()).orElseThrow() instanceof BinaryOp))
				continue;
			for(var alternative : domain.alternatives()) {
				if(alternative.state().execType() != ExecType.FED
					|| alternative.orderedInputs().isEmpty()
					|| alternative.orderedInputs().get(0).fType() != FType.ROW)
					continue;
				var layout = ExactPhysicalCostModel.executionLayout(analysis, alternative, FType.ROW, 2);
				Assert.assertTrue("The actual source map, not graph-wide W, supplies ranges",
					layout.inputs().get(0).exactRanges());
				var ranges = layout.inputs().get(0).ranges();
				Assert.assertEquals(800L, (long)ranges.get(0).end().get(0));
				var balanced = new java.util.ArrayList<>(layout.inputs());
				balanced.set(0, new InputLayout(FType.ROW, List.of(
					new AnchorPartition(ranges.get(0).workerId(), List.of(0L,0L), List.of(500L,100L)),
					new AnchorPartition(ranges.get(1).workerId(), List.of(500L,0L), List.of(1000L,100L))), true));
				var other = new FederatedExecutionLayout(FType.ROW, 2, balanced);
				Assert.assertNotEquals("Projection cache must distinguish equal-W skew", layout, other);
				var prepared = PlacementCostSemantics.prepareExecutionCost(analysis, domain.node().key());
				Assert.assertTrue("Critical worker owns 80%, not 50%, of this elementwise kernel",
					prepared.federatedCost(layout) > prepared.federatedCost(other));
				return;
			}
		}
		Assert.fail("Missing partitioned binary fixture");
	}

	@Test
	public void fusedInputProjectionUsesEmittedMapRatherThanExecutionMap() throws Exception {
		var analysis = analysis(800);
		var model = ExactPhysicalModel.build(analysis);
		var source = model.domains().stream().filter(domain -> analysis.hop(domain.node().key()).orElseThrow()
			instanceof BinaryOp).flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.state().fType() == FType.ROW
				&& alternative.state().output() == FederatedOutput.FOUT).findFirst().orElseThrow();
		var execution = CandidateEmissionRealization.durable(source.realization().key().emissionState(),
			anchor("execution-skew"), List.of(), List.of());
		var output = new DurableAnchorKey("emitted-balanced", FType.ROW, List.of(
			new AnchorPartition("localhost:1234/emitted", List.of(0L, 0L), List.of(500L, 100L)),
			new AnchorPartition("localhost:1235/emitted", List.of(500L, 0L), List.of(1000L, 100L))));
		var selected = new ExactPhysicalModel.Alternative(source.decision(), source.state(), source.authorityKind(),
			source.candidateRule(), source.candidateEmission(), source.executionRule(), source.executionEmission(),
			output, source.relocationAction(), source.derivedFoutAction(), source.orderedInputs(),
			source.inputAuthorities(), execution, execution.supportClauses().get(0), "emitted-map-test");
		Class<?> cacheType = Class.forName(ExactPhysicalCostModel.class.getName() + "$InputLayoutCache");
		var constructor = cacheType.getDeclaredConstructor();
		constructor.setAccessible(true);
		var method = ExactPhysicalCostModel.class.getDeclaredMethod("physicalValueLayout",
			PlacementAnalysis.class, ExactPhysicalModel.Alternative.class, int.class, cacheType);
		method.setAccessible(true);
		InputLayout projected = (InputLayout)method.invoke(null, analysis, selected, 2, constructor.newInstance());
		Assert.assertTrue(projected.exactRanges());
		Assert.assertEquals("The emitted value has 50% shards, even though its previous execution had 80%",
			500L, (long)projected.ranges().get(0).end().get(0));
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(ExecType.FED,
			FederatedOutput.FOUT, FType.ROW, false), false);
	}
	private static DurableAnchorKey anchor(String value) {
		return new DurableAnchorKey(value, FType.ROW, List.of(
			new AnchorPartition("localhost:1234/" + value, List.of(0L,0L), List.of(800L,100L)),
			new AnchorPartition("localhost:1235/" + value, List.of(800L,0L), List.of(1000L,100L))));
	}
	private static PlacementProofKey proof() {
		var region = new ControlRegionKey("layout-test", "main", List.of("sb-1"), "main", "compiled");
		return new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			new CompiledHopKey("layout-test", "main", "main", "compiled", region, "owner", "owner"), "pool");
	}
	private static PlacementAnalysis analysis() throws Exception {
		return analysis(800);
	}
	static PlacementAnalysis analysis(int split) throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\",\"localhost:1235/X\"),"
			+ "ranges=list(list(0,0),list(" + split + ",100),list(" + split
			+ ",0),list(1000,100)));Y=X+1;print(sum(Y));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}
}

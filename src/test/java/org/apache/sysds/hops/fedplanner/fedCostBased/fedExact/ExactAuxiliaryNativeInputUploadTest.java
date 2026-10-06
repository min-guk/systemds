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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for auxiliary stages sharing native-local input edges. */
public class ExactAuxiliaryNativeInputUploadTest {
	@Test
	public void weightedCovarianceAuxiliaryStagesDoNotEraseTheLocalWeightsUpload() throws Exception {
		Fixture fixture = fixture(OpOp3.COV);
		ExactPhysicalModel.DecisionDomain owner = domain(fixture, fixture.owner);
		int target = ownerAlternative(owner, 2);
		var edge = fixture.analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(candidate -> candidate.consumer() == owner.node().key()
				&& candidate.inputPosition() == 2).findFirst().orElseThrow();
		Hop weights = fixture.owner.getInput(2);
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(weights);
		int workers = ExactPhysicalCostModel.executionWorkerCount(fixture.analysis,
			owner.alternatives().get(target), ExactPhysicalCostModel.workerCount(fixture.analysis.graph()));
		double actual = nativeLocalTargetCost(fixture.analysis, edge, owner, fixture.owner,
			weights, bytes, workers, target);
		double expected = FederatedCostModel.computeInBandUploadPayloadCost(bytes, FType.ROW, workers);
		Assert.assertTrue("fixture must have a positive local weights upload", expected > 0);
		Assert.assertEquals("Covariance mean/weight-sum stages must not suppress the sliced weights PUT",
			expected, actual, 1e-12);
	}

	@Test
	public void ctableLocalSecondaryIsSlicedExactlyOnce() throws Exception {
		DataOp primary = matrix("X", 1000, 20);
		DataOp secondary = matrix("Y", 1000, 1);
		TernaryOp table = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, primary, secondary, new LiteralOp(1D));
		List<FType> types = Arrays.asList(FType.ROW, null, null);
		int workers = 2;
		double primaryBytes = FederatedCostModel.getEffectiveOutputMemEstimate(primary);
		double secondaryBytes = FederatedCostModel.getEffectiveOutputMemEstimate(secondary);
		double actual = FederatedCostModel.computeMixedFedLocalCost(table, table.getInput(),
			List.of(primaryBytes, secondaryBytes, 8D), types, FType.ROW, 0, 8, workers)
			.getInputPreparationCost();

		double primaryDimension = FederatedCostModel.computeRequestResponseLatency()
			+ replicatedWorkerResultDownloadCost(8, workers)
			+ FederatedCostModel.computeExecutionCost(null, 1000D * 20 / workers,
				primaryBytes / workers, 8)
			+ FederatedCostModel.computeExecutionCost(null, workers, 8D * workers, 8);
		double localDimension = 2 * FederatedCostModel.computeMemoryAccessCost(secondaryBytes)
			+ FederatedCostModel.computeExecutionCost(null, 1000, secondaryBytes, 8D * workers)
			+ FederatedCostModel.computeExecutionCost(null, workers, 8D * workers, 8);
		double oneSlicedPut = FederatedCostModel.computeInBandUploadPayloadCost(
			secondaryBytes, FType.ROW, workers);
		double sliceAndFedOutputScan = 4 * FederatedCostModel.computeMemoryAccessCost(secondaryBytes)
			+ FederatedCostModel.computeExecutionCost(null, 2D * 1000,
				2 * secondaryBytes, 16D * workers);
		Assert.assertTrue("fixture must have a positive sliced PUT", oneSlicedPut > 0);
		Assert.assertEquals("CTABLE must charge its local secondary broadcastSliced payload once",
			primaryDimension + localDimension + oneSlicedPut + sliceAndFedOutputScan, actual, 1e-12);
	}

	private static double replicatedWorkerResultDownloadCost(double bytes, int workers) throws Exception {
		Method method = FederatedCostModel.class.getDeclaredMethod(
			"computeReplicatedWorkerResultDownloadCost", double.class, int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, bytes, workers);
	}

	private static double nativeLocalTargetCost(PlacementAnalysis analysis,
			PlacementAnalysis.CompiledInputEdgeFact edge, ExactPhysicalModel.DecisionDomain owner,
			Hop ownerHop, Hop producerHop, double bytes, int workers, int target) throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod("nativeLocalTargetCost",
			PlacementAnalysis.class, PlacementCostSemantics.ExpectedSparseAssignmentEstimates.class,
			PlacementAnalysis.CompiledInputEdgeFact.class, ExactPhysicalModel.DecisionDomain.class,
			Hop.class, Hop.class, double.class, double.class, int.class, int.class);
		method.setAccessible(true);
		Object result = method.invoke(null, analysis,
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis), edge, owner,
			ownerHop, producerHop, bytes, -1.0, workers, target);
		Method baseCost = result.getClass().getDeclaredMethod("baseCost");
		baseCost.setAccessible(true);
		return (double) baseCost.invoke(result);
	}

	private static int ownerAlternative(ExactPhysicalModel.DecisionDomain owner, int localPosition) {
		for(int index = 0; index < owner.alternatives().size(); index++) {
			var alternative = owner.alternatives().get(index);
			if(alternative.state().execType() != ExecType.FED
				|| alternative.state().output() != FederatedOutput.LOUT
				|| alternative.state().fType() != FType.ROW
				|| alternative.orderedInputs().size() <= localPosition
				|| !alternative.orderedInputs().get(0).present()
				|| !alternative.orderedInputs().get(1).present()
				|| alternative.orderedInputs().get(localPosition).present())
				continue;
			boolean nativeLocal = alternative.inputAuthorities().stream().anyMatch(authority ->
				authority.inputPosition() == localPosition
					&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL);
			if(nativeLocal)
				return index;
		}
		throw new AssertionError("fixture has no ROW FED/LOUT alternative with a native-local input");
	}

	private static ExactPhysicalModel.DecisionDomain domain(Fixture fixture, Hop hop) {
		return fixture.model.domains().stream()
			.filter(candidate -> fixture.analysis.hop(candidate.node().key()).orElse(null) == hop)
			.findFirst().orElseThrow();
	}

	private static Fixture fixture(OpOp3 op) throws Exception {
		String script = fed("X", 1234, 1000, 20) + fed("Y", 1234, 1000, 20)
			+ "W=rand(rows=1000,cols=1,seed=9);print(sum(X));print(sum(Y));print(sum(W));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		List<Hop> hops = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var block : program.getStatementBlocks())
			for(Hop root : block.getHops())
				collect(root, seen, hops);
		Hop x = namedMatrix(hops, "X");
		Hop y = namedMatrix(hops, "Y");
		Hop w = namedMatrix(hops, "W");
		TernaryOp owner = new TernaryOp("owner", DataType.SCALAR, ValueType.FP64, op, x, y, w);
		owner.setDim1(0);
		owner.setDim2(0);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(new DataOp("out", DataType.SCALAR, ValueType.FP64,
			owner, OpOpData.TRANSIENTWRITE, "out"))));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		return new Fixture(analysis, ExactPhysicalModel.build(analysis), owner);
	}

	private static String fed(String name, int port, int rows, int cols) {
		return name + "=federated(addresses=list(\"localhost:" + port + "/" + name + "0\",\"localhost:"
			+ (port + 1) + "/" + name + "1\"),ranges=list(list(0,0),list(" + rows / 2 + "," + cols
			+ "),list(" + rows / 2 + ",0),list(" + rows + "," + cols + ")));";
	}

	private static Hop namedMatrix(List<Hop> hops, String name) {
		return hops.stream().filter(hop -> (hop instanceof DataOp || hop instanceof DataGenOp)
			&& hop.getName().equals(name)).findFirst().orElseThrow();
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, rows * cols, 1000);
	}

	private static void collect(Hop hop, Set<Hop> seen, List<Hop> hops) {
		if(!seen.add(hop))
			return;
		hop.setBlocksize(1000);
		hops.add(hop);
		for(Hop input : hop.getInput())
			collect(input, seen, hops);
	}

	private record Fixture(PlacementAnalysis analysis, ExactPhysicalModel model, TernaryOp owner) { }
}

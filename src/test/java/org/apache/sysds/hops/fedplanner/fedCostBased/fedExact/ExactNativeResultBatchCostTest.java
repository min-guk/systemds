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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactNativeResultBatchCostTest {
	@Test public void transposeGetSharesExecutionBatch() throws Exception {
		assertResult("Y=t(X);", hop -> hop instanceof ReorgOp, true);
	}
	@Test public void ternaryGetSharesExecutionBatch() throws Exception {
		assertResult("Y=ifelse(X>0,X,0);", hop -> hop instanceof TernaryOp, true);
	}
	@Test public void binaryForcedLocalGetOwnsAdditionalBatch() throws Exception {
		assertResult("Y=X+1;", hop -> hop instanceof BinaryOp, false);
	}
	@Test public void mixedCovarianceResultSharesItsUdfBatch() throws Exception {
		assertResult("L=rand(rows=900,cols=100,seed=8);C=cov(X,L);Y=matrix(C,rows=1,cols=1);",
			hop -> hop instanceof BinaryOp binary && binary.getOp() == OpOp2.COV,
			true, false, Arrays.asList(FType.ROW, null));
	}
	@Test public void alignedCovarianceMainResultSharesExecutionBatch() throws Exception {
		assertResult("C=cov(X,X);Y=matrix(C,rows=1,cols=1);",
			hop -> hop instanceof BinaryOp binary && binary.getOp() == OpOp2.COV, true);
	}

	@Test public void exactIndexSliceGetsOnlyItsOverlappingWorkerResponse() throws Exception {
		assertResult("Y=X[1:10,1:20];", hop -> hop instanceof IndexingOp, false, true);
	}

	private static void assertResult(String operation, Predicate<Hop> match, boolean inBand) throws Exception {
		assertResult(operation, match, inBand, false);
	}

	private static void assertResult(String operation, Predicate<Hop> match, boolean inBand, boolean exactSlice) throws Exception {
		assertResult(operation, match, inBand, exactSlice, null);
	}

	private static void assertResult(String operation, Predicate<Hop> match, boolean inBand,
			boolean exactSlice, List<FType> requestedInputTypes) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"X=rand(rows=900,cols=100,seed=7);" + operation + "print(sum(Y));", new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey key = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(candidate -> analysis.hop(candidate).map(match::test).orElse(false)).findFirst().orElseThrow();
		Hop hop = analysis.hop(key).orElseThrow();
		Method project = ExactPhysicalCostModel.class.getDeclaredMethod("fedCostProjection",
			PlacementAnalysis.class, PlacementCostSemantics.ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class);
		project.setAccessible(true);
		List<FType> inputs = requestedInputTypes != null ? requestedInputTypes : hop.getInput().stream()
			.map(input -> input.getDataType().isMatrix() ? FType.ROW : null).toList();
		Object projection = project.invoke(null, analysis, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis),
			key, hop, inputs, FType.ROW, 3, 1.0);
		if(exactSlice) {
			Method exactProject = ExactPhysicalCostModel.class.getDeclaredMethod("fedCostProjection",
				PlacementAnalysis.class, PlacementCostSemantics.ExpectedSparseAssignmentEstimates.class,
				CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class,
				FederatedExecutionLayout.class);
			exactProject.setAccessible(true);
			List<InputLayout> layouts = new ArrayList<>();
			layouts.add(new InputLayout(FType.ROW, List.of(
				new AnchorPartition("w0", List.of(0L,0L), List.of(300L,100L)),
				new AnchorPartition("w1", List.of(300L,0L), List.of(600L,100L)),
				new AnchorPartition("w2", List.of(600L,0L), List.of(900L,100L))), true));
			while(layouts.size() < hop.getInput().size()) layouts.add(new InputLayout(null, List.of(), false));
			projection = exactProject.invoke(null, analysis, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis),
				key, hop, inputs, FType.ROW, 3, 1.0, new FederatedExecutionLayout(FType.ROW, 3, layouts));
		}
		Method result = projection.getClass().getDeclaredMethod("resultDownloadCost");
		result.setAccessible(true);
		double actual = (double)result.invoke(projection);
		boolean alignedCovariance = hop instanceof BinaryOp binary && binary.getOp() == OpOp2.COV
			&& inputs.get(0) != null && inputs.get(1) != null;
		double expected = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveUploadMemEstimate(hop),
			alignedCovariance ? FType.PART : exactSlice ? FType.FULL : FType.ROW, exactSlice ? 1 : 3);
		if(inBand) expected -= FederatedCostModel.computeRequestResponseLatency();
		Assert.assertEquals("Production projection must own exactly the runtime's additional GET batches",
			expected, actual, 1e-9);
	}
}

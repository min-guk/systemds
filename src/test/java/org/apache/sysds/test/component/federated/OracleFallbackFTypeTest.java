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

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Test;

public class OracleFallbackFTypeTest {
	private static final long ROWS = 10;
	private static final long COLS = 10;
	private static final int BLOCKSIZE = 1000;

	@Test
	public void testFallbackConflictingConsumersReturnsNull() {
		DataOp left = matrixRead("left", ROWS, COLS);
		DataOp right = matrixRead("right", ROWS, COLS);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		AggUnaryOp rowAgg = new AggUnaryOp("rowAgg", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, plus);
		AggUnaryOp colAgg = new AggUnaryOp("colAgg", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, plus);
		assertEquals(2, plus.getParent().size());
		assertNotNull(rowAgg);
		assertNotNull(colAgg);

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		FType inferred = OracleUtils.inferFallbackFType(plus, Collections.emptyList(), oracle, null);
		assertNull("Expected no fallback FType for conflicting consumer constraints", inferred);
	}

	@Test
	public void testFallbackPrefersColForSingleRowShape() {
		DataOp left = matrixRead("left", 1, 10);
		DataOp right = matrixRead("right", 1, 10);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		List<FType> inputFTypes = Arrays.asList(FType.ROW, FType.COL);
		FType inferred = OracleUtils.inferFallbackFType(plus, inputFTypes, null, null);
		assertEquals("Expected COL for single-row shape with ROW/COL candidates", FType.COL, inferred);
	}

	@Test
	public void testSharedAnalysisOwnsLogicalTransientForwardInsteadOfFedAllPrivateRewire() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			ProductionShadowFixtureFactory.compile("B-21"));
		var forwards = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> analysis.hop(fact.sourceWrite()).orElseThrow().getDataType().isMatrix())
			.toList();
		assertFalse("Fixture must retain matrix transient forwarding", forwards.isEmpty());
		for(var forward : forwards) {
			assertEquals(OpOpData.TRANSIENTWRITE,
				((DataOp) analysis.hop(forward.sourceWrite()).orElseThrow()).getOp());
			assertEquals(OpOpData.TRANSIENTREAD,
				((DataOp) analysis.hop(forward.targetRead()).orElseThrow()).getOp());
			assertTrue("Logical CFG forwarding must not be fabricated as a compiled physical HOP edge",
				analysis.compiledInputEdgesInCanonicalOrder().stream().noneMatch(edge ->
					edge.producer() == forward.sourceWrite()
						&& edge.consumer() == forward.targetRead()));
		}
	}

	@Test
	public void testSharedAnalysisKeepsDistinctControlFlowValueVersions() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			ProductionShadowFixtureFactory.compile("B-18"));
		var xVersions = analysis.graph().nodes().stream()
			.map(node -> node.valueVersion()).filter(version -> "X".equals(version.lexicalVariable()))
			.distinct().toList();
		assertTrue("Loop/branch definitions of X must not collapse into one memo-table identity",
			xVersions.size() > 1);
	}

	@Test
	public void testFedAllConsumesSharedCpfoutCandidateDomain() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-22");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var facts = analysis.candidateRuleFacts().orderedFacts();
		boolean hasSharedLocalAndCpfoutDomain = false;
		for(var nativeFact : facts) {
			for(int position = 0; position < nativeFact.key().orderedInputs().size(); position++) {
				if(nativeFact.key().orderedInputs().get(position).present())
					continue;
				int inputPosition = position;
				hasSharedLocalAndCpfoutDomain = facts.stream().anyMatch(materializedFact ->
					materializedFact.key().parentOccurrence() == nativeFact.key().parentOccurrence()
						&& materializedFact.key().orderedInputs().size()
							== nativeFact.key().orderedInputs().size()
						&& materializedFact.key().orderedInputs().get(inputPosition).present());
				if(hasSharedLocalAndCpfoutDomain)
					break;
			}
			if(hasSharedLocalAndCpfoutDomain)
				break;
		}
		assertTrue("Shared candidate domain must retain native-local and legal CP-to-FOUT alternatives",
			hasSharedLocalAndCpfoutDomain);

		var result = new FederatedPlannerFedAll().select(analysis);
		assertSame("FedAll must consume the prebuilt shared PlacementAnalysis", analysis, result.analysis());
		assertFalse(result.selectedCandidateSelections().isEmpty());
		result.selectedCandidateSelections().forEach(receipt -> assertSame(
			"FedAll must select an exact analysis-owned candidate receipt", receipt,
			analysis.canonicalCandidateReceipt(receipt.rule(), receipt.emission())));
	}

	@Test
	public void testFunctionOutputFromMultiReturnBuiltinIsCpOnlyButKeepsLogicalFType() {
		DataOp input = matrixRead("X", ROWS, COLS);
		DataOp eigenValues = new DataOp("eigen_values", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		DataOp eigenVectors = new DataOp("eigen_vectors", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, ".builtinNS", "eigen",
			new String[] {"X"}, List.of(input), new String[] {"eigen_values", "eigen_vectors"},
			new java.util.ArrayList<>(List.of(eigenValues, eigenVectors)));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		OpCaps caps = oracle.decide(eigenValues, List.of(FType.ROW));
		assertNotNull(caps);
		assertEquals(ExecType.CP, caps.exec());
		assertEquals(ReasonCode.MISSING_FED_INSTRUCTION, caps.reason());

		OracleUtils.OracleDecision decision = OracleUtils.decideWithOracle(
			eigenValues, Privacy.PUBLIC, List.of(input), List.of(FType.ROW), oracle, null, null);
		assertNotNull(decision);
		assertEquals("FunOut should keep logical FType for downstream candidate reasoning",
			FType.ROW, decision.logicalFType());
	}

	@Test
	public void testTransformEncodeUsesNativeFedCapabilityAndHeterogeneousOutputs() {
		DataOp frame = new DataOp("Fall", DataType.FRAME, ValueType.STRING,
			OpOpData.TRANSIENTREAD, null, ROWS, COLS, ROWS * COLS, BLOCKSIZE);
		LiteralOp spec = new LiteralOp("{ids:true,dummycode:[1]}");
		DataOp encoded = new DataOp("X0", DataType.MATRIX, ValueType.FP64,
			frame, OpOpData.FUNCTIONOUTPUT, null);
		DataOp metadata = new DataOp("M", DataType.FRAME, ValueType.STRING,
			frame, OpOpData.FUNCTIONOUTPUT, null);
		FunctionOp transform = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "_internal", "transformencode",
			null, List.of(frame, spec), new String[] {"X0", "M"},
			new java.util.ArrayList<>(List.of(encoded, metadata)));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		OpCaps callCaps = oracle.decide(transform, Arrays.asList(FType.ROW, null));
		assertEquals(ExecType.FED, callCaps.exec());
		assertEquals(FederatedOutput.FOUT, callCaps.placement());
		assertEquals(FType.ROW, callCaps.foutFType().orElse(null));

		OpCaps encodedCaps = oracle.decide(encoded, List.of(FType.ROW));
		assertEquals(ExecType.FED, encodedCaps.exec());
		assertEquals(FederatedOutput.FOUT, encodedCaps.placement());
		assertEquals(FType.ROW, encodedCaps.foutFType().orElse(null));

		OpCaps metadataCaps = oracle.decide(metadata, List.of(FType.ROW));
		assertEquals(ExecType.CP, metadataCaps.exec());
		assertEquals(FederatedOutput.LOUT, metadataCaps.placement());
	}

	@Test
	public void testBuiltinMKMeansSpecializationPrecedesGenericFunctionRule() {
		DataOp input = matrixRead("X", ROWS, COLS);
		FunctionOp call = new FunctionOp(FunctionType.DML, ".builtinNS", "m_kmeans",
			new String[] {"X"}, List.of(input), new String[] {"C"}, false);

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		OpCaps caps = oracle.decide(call, List.of(FType.ROW));
		assertEquals(ExecType.FED, caps.exec());
		assertEquals(FederatedOutput.FOUT, caps.placement());
		assertEquals("builtin m_kmeans placeholder", caps.detail().orElse(null));
	}

	private static DataOp matrixRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, rows, cols, rows * cols, BLOCKSIZE);
	}

	private static DataOp transientRead(String name) {
		return matrixRead(name, ROWS, COLS);
	}

}

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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCostEstimator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.EffectiveDemandClass;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTHyperedgeTest {

	@Test
	public void testAddVertexCreatesOnlyComputeAndPlacementNodes() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Vertex vertex = createVertex(new LiteralOp(7L));

		graph.addVertex(vertex);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		Assert.assertTrue("MinST graph must contain compute node", g.containsVertex(computeId(vertex.getHopID())));
		Assert.assertTrue("MinST graph must contain placement node", g.containsVertex(placementId(vertex.getHopID())));
		Assert.assertFalse("MinST graph must not create unconditional locality node",
			g.containsVertex(localityId(vertex.getHopID())));
	}

	@Test
	public void testEffectiveDemandIndexClassifiesRequiredLocalOnly() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Vertex child = createVertex(new LiteralOp(1L));
		Vertex parent = createVertex(new LiteralOp(2L));
		graph.addVertex(child);
		graph.addVertex(parent);

		graph.addRequiredLocalInputEdge(parent.getHopID(), child.getHopID());

		Assert.assertEquals("Required-local edge should create a LOCAL-only effective demand",
			EffectiveDemandClass.LOCAL_ONLY, graph.getEffectiveDemandClass(child.getHopID()));
		Assert.assertTrue("Demand summary should retain the effective consumer source",
			graph.getEffectiveDemandSummary(child.getHopID()).get(0).contains("raw-parent-local"));
	}

	@Test
	public void testEffectiveDemandIndexClassifiesRawSingleParentMixedBoundary() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 8.0;

		DataOp childHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, uploadCost, 0.0);
		graph.addVertex(child);

		Vertex parent = createVertex(new LiteralOp(3L));
		graph.addVertex(parent);

		graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);

		Assert.assertEquals("A raw single parent can still create mixed LOCAL+FED effective demand",
			EffectiveDemandClass.MIXED_LOCAL_FED, graph.getEffectiveDemandClass(child.getHopID()));
		String summary = graph.getEffectiveDemandSummary(child.getHopID()).toString();
		Assert.assertTrue(summary.contains("raw-parent-local"));
		Assert.assertTrue(summary.contains("raw-parent-fed-boundary"));
	}

	@Test
	public void testIntrinsicComputePlacementTruthTableWithoutUDObligations() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 5.0;
		double downloadCost = 7.0;
		Vertex vertex = createVertex(new LiteralOp(4L));
		vertex.setCost(0.0, uploadCost, downloadCost);
		graph.addVertex(vertex);

		graph.addExecPlacementResultEdge(vertex);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long cId = computeId(vertex.getHopID());
		long pId = placementId(vertex.getHopID());
		Assert.assertEquals("CP/LOUT should not pay intrinsic conversion", 0.0,
			computeCutCost(g, Collections.emptySet()), 1e-9);
		Assert.assertEquals("CP/FOUT should pay exactly one intrinsic upload/refed", uploadCost,
			computeCutCost(g, setOf(pId)), 1e-9);
		Assert.assertEquals("FED/FOUT should not pay intrinsic conversion", 0.0,
			computeCutCost(g, setOf(cId, pId)), 1e-9);
		Assert.assertEquals("FED/LOUT should pay exactly one intrinsic materialization/download", downloadCost,
			computeCutCost(g, setOf(cId)), 1e-9);
	}

	@Test
	public void testStateConditionedUDTruthTableEnumeratesActiveParentDemand() {
		for (int numParents = 1; numParents <= 3; numParents++) {
			int parentStates = 1 << numParents;
			for (int parentMask = 0; parentMask < parentStates; parentMask++) {
				for (boolean unconditionalLocal : new boolean[] {false, true}) {
					for (boolean unconditionalFed : new boolean[] {false, true}) {
						for (boolean cpFoutLocalReuse : new boolean[] {false, true}) {
							for (boolean fedLoutFedReuse : new boolean[] {false, true}) {
								for (boolean uCapability : new boolean[] {false, true}) {
									for (boolean dCapability : new boolean[] {false, true}) {
										UDCapabilities caps = new UDCapabilities(
											cpFoutLocalReuse, fedLoutFedReuse, uCapability, dCapability);
										for (ExecType childExec : new ExecType[] {ExecType.CP, ExecType.FED}) {
											for (FederatedOutput childOut : new FederatedOutput[] {
													FederatedOutput.LOUT, FederatedOutput.FOUT}) {
												UDDecision expected = expectedUDDecision(
													childExec, childOut, numParents, parentMask,
													unconditionalLocal, unconditionalFed, caps);
												assertUDDecisionMatchesSemanticInvariants(
													expected, childExec, childOut, numParents, parentMask,
													unconditionalLocal, unconditionalFed, caps);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	@Test
	public void testNaivePOnlyUDModelOverchargesCapabilityBackedReuseStates() {
		UDCapabilities allCapabilities = new UDCapabilities(true, true, true, true);

		UDDecision cpFoutLocal = expectedUDDecision(ExecType.CP, FederatedOutput.FOUT,
			1, 0, false, false, allCapabilities);
		UDDecision naiveCpFoutLocal = naivePOnlyUDDecision(ExecType.CP, FederatedOutput.FOUT,
			1, 0, false, false, allCapabilities);
		Assert.assertFalse("CP/FOUT with proven local reuse must not need D",
			cpFoutLocal.requiresD);
		Assert.assertTrue("Naive P-only D would overcharge CP/FOUT local reuse",
			naiveCpFoutLocal.requiresD);

		UDDecision fedLoutFed = expectedUDDecision(ExecType.FED, FederatedOutput.LOUT,
			1, 1, false, false, allCapabilities);
		UDDecision naiveFedLoutFed = naivePOnlyUDDecision(ExecType.FED, FederatedOutput.LOUT,
			1, 1, false, false, allCapabilities);
		Assert.assertFalse("FED/LOUT with proven federated reuse must not need U",
			fedLoutFed.requiresU);
		Assert.assertTrue("Naive P-only U would overcharge FED/LOUT federated reuse",
			naiveFedLoutFed.requiresU);
	}

	@Test
	public void testCapabilityFalseNeverBecomesFreeUDConversion() {
		UDCapabilities noExtraConversions = new UDCapabilities(false, false, false, false);

		UDDecision cpLoutFedDemand = expectedUDDecision(ExecType.CP, FederatedOutput.LOUT,
			1, 1, false, false, noExtraConversions);
		Assert.assertFalse("CP/LOUT cannot satisfy FED demand without U capability",
			cpLoutFedDemand.valid);
		Assert.assertTrue("Invalid CP/LOUT + FED demand must record missing U",
			cpLoutFedDemand.requiresU);

		UDDecision fedFoutLocalDemand = expectedUDDecision(ExecType.FED, FederatedOutput.FOUT,
			1, 0, false, false, noExtraConversions);
		Assert.assertFalse("FED/FOUT cannot satisfy LOCAL demand without D capability",
			fedFoutLocalDemand.valid);
		Assert.assertTrue("Invalid FED/FOUT + LOCAL demand must record missing D",
			fedFoutLocalDemand.requiresD);

		UDDecision cpFoutMixedDemand = expectedUDDecision(ExecType.CP, FederatedOutput.FOUT,
			2, 2, false, false, noExtraConversions);
		Assert.assertFalse("CP/FOUT cannot assume LOCAL reuse when capability is false",
			cpFoutMixedDemand.valid);
		Assert.assertTrue("CP/FOUT mixed demand must require D unless local reuse is proven",
			cpFoutMixedDemand.requiresD);

		UDDecision fedLoutMixedDemand = expectedUDDecision(ExecType.FED, FederatedOutput.LOUT,
			2, 1, false, false, noExtraConversions);
		Assert.assertFalse("FED/LOUT cannot assume FED reuse when capability is false",
			fedLoutMixedDemand.valid);
		Assert.assertTrue("FED/LOUT mixed demand must require U unless fed reuse is proven",
			fedLoutMixedDemand.requiresU);
	}

	@Test
	public void testUploadOrCostSingleActivation() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 10.0;
		double downloadCost = 7.0;

		DataOp childHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, uploadCost, downloadCost);
		child.setNumParents(3);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 3);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());

		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			sourceSide.add(computeId(parent.getHopID()));
			Assert.assertNull("Shared U encoding must not charge direct per-parent upload edges",
				g.getEdge(parentC, childP));
		}
		double cutCost = computeCutCostWithHardClosure(g, sourceSide);
		Assert.assertEquals("Shared U upload cost should be paid once for the compatible child domain",
			uploadCost, cutCost, 1e-9);
	}

	@Test
	public void testOptionalFedInputAddsUploadHyperedgeForMatrixInput() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 8.0;
		double downloadCost = 5.0;

		DataOp childHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, uploadCost, downloadCost);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 2);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), false);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());

		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			Assert.assertNull("Shared U encoding must not charge direct per-parent upload edges",
				g.getEdge(parentC, childP));
			sourceSide.add(computeId(parent.getHopID()));
		}
		Assert.assertEquals("Optional matrix input should pay forwarding upload once per compatible child domain",
			uploadCost, computeCutCostWithHardClosure(g, sourceSide), 1e-9);
	}

	@Test
	public void testParentChildEdgeDoesNotCreateDownloadHyperedge() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 9.0;
		double downloadCost = 11.0;

		DataOp childHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, uploadCost, downloadCost);
		child.setNumParents(3);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 3);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());
		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			sourceSide.add(parentC);
			Assert.assertNull("Shared U encoding must not charge direct per-parent upload edges",
				g.getEdge(parentC, childP));
			DefaultWeightedEdge requiredLocalEdge = g.getEdge(childP, parentC);
			Assert.assertNotNull("Expected conservative required-local constraint edge", requiredLocalEdge);
			Assert.assertTrue("Required-local constraint must not be the finite download cost",
				g.getEdgeWeight(requiredLocalEdge) > 1e12);
		}
		Assert.assertEquals("Shared U upload cost should be charged once, not once per parent",
			uploadCost, computeCutCostWithHardClosure(g, sourceSide), 1e-9);
		for (DefaultWeightedEdge edge : g.edgeSet()) {
			if (g.getEdgeSource(edge).equals(childP) && approxEqual(g.getEdgeWeight(edge), downloadCost)) {
				Assert.fail("Parent-child edge must not introduce download hyperedge");
			}
		}
	}

	@Test
	public void testSelectedUObligationExtractedForCpLoutChildWithFedConsumers() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.setNumOfWorkers(2);

		DataOp childHop = new DataOp("X_u_obligation", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
		ExecPlacementCaps childCaps = new ExecPlacementCaps();
		childCaps.allowFED_LOUT = false;
		childCaps.allowFED_FOUT = false;
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, childCaps);
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, 6.0, 4.0);
		graph.addVertex(child);
		graph.setVertexCost(child);
		graph.addExecPlacementResultEdge(child);

		ExecPlacementCaps fedParentCaps = new ExecPlacementCaps();
		fedParentCaps.allowCP_LOUT = false;
		fedParentCaps.allowCP_FOUT = false;
		Vertex parent = new Vertex(new LiteralOp(21L), Privacy.PUBLIC, FType.FULL, fedParentCaps);
		parent.setMetadata(1.0, 1.0, Collections.emptyList());
		parent.setCost(100.0, 0.0, 0.0);
		graph.addVertex(parent);
		graph.setVertexCost(parent);
		graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);

		ExecPlacementCaps localParentCaps = new ExecPlacementCaps();
		localParentCaps.allowFED_LOUT = false;
		localParentCaps.allowFED_FOUT = false;
		Vertex localParent = new Vertex(new LiteralOp(22L), Privacy.PUBLIC, FType.FULL, localParentCaps);
		localParent.setMetadata(1.0, 1.0, Collections.emptyList());
		localParent.setCost(0.0, 0.0, 0.0);
		graph.addVertex(localParent);
		graph.setVertexCost(localParent);
		graph.addRequiredLocalInputEdge(localParent.getHopID(), child.getHopID());

		graph.getOptimalPlan();

		Assert.assertEquals("Expected one selected U obligation",
			1, graph.getSelectedObligations().size());
		FederatedPlanMinSTGraph.SelectedObligation obligation = graph.getSelectedObligations().get(0);
		Assert.assertEquals(FederatedPlanMinSTGraph.ObligationKind.U, obligation.getKind());
		Assert.assertEquals(child.getHopID(), obligation.getChildHopId());
		Assert.assertTrue(obligation.getConsumerHopIds().contains(parent.getHopID()));
		Assert.assertEquals("Child remains primary local; U is an extra obligation, not a C/P flip",
			FederatedOutput.LOUT, childHop.getFederatedOutput());
		Assert.assertEquals(ExecType.CP, childHop.getForcedExecType());
	}

	@Test
	public void testSelectedDObligationRegistersLocalMaterialize() throws Exception {
		FederatedLocalMaterializeRegistry.clear();
		try {
			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			graph.setNumOfWorkers(4);

			DataOp childHop = new DataOp("X_d_obligation", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, null, 100, 100, 100L * 100L, 1000);
			ExecPlacementCaps childCaps = new ExecPlacementCaps();
			childCaps.allowCP_LOUT = false;
			childCaps.allowCP_FOUT = false;
			childCaps.allowFED_LOUT = false;
			Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, childCaps);
			child.setMetadata(1.0, 1.0, Collections.emptyList());
			child.setCost(100.0, 6.0, 4.0);
			graph.addVertex(child);
			graph.setVertexCost(child);
			graph.addExecPlacementResultEdge(child);

			Vertex localConsumer = createVertex(new LiteralOp(31L));
			graph.addVertex(localConsumer);
			graph.setVertexCost(localConsumer);
			graph.addLoopCarryNetEdge(localConsumer.getHopID(), child.getHopID(), 0.0, 0.0);

			graph.getOptimalPlan();

			Assert.assertEquals("Expected one selected D obligation",
				1, graph.getSelectedObligations().size());
			FederatedPlanMinSTGraph.SelectedObligation obligation = graph.getSelectedObligations().get(0);
			Assert.assertEquals(FederatedPlanMinSTGraph.ObligationKind.D, obligation.getKind());
			Assert.assertTrue("Selected D must carry runtime capability", obligation.hasCapability());

			Method register = FederatedPlanMinSTCut.class.getDeclaredMethod(
				"registerMinstSelectedObligations", FederatedPlanMinSTGraph.class, Map.class);
			register.setAccessible(true);
			register.invoke(null, graph, new HashMap<Long, FType>());

			Assert.assertTrue("Selected D must register a runtime local materialization obligation",
				FederatedLocalMaterializeRegistry.snapshot(-1L).containsKey(child.getHopID()));
		}
		finally {
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	@Test
	public void testSelectedUObligationSkipsNonMatrixChild() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.setNumOfWorkers(2);

		LiteralOp childHop = new LiteralOp(1.0);
		ExecPlacementCaps childCaps = new ExecPlacementCaps();
		childCaps.allowFED_LOUT = false;
		childCaps.allowFED_FOUT = false;
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.FULL, childCaps);
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, 6.0, 4.0);
		graph.addVertex(child);
		graph.setVertexCost(child);
		graph.addExecPlacementResultEdge(child);

		ExecPlacementCaps fedParentCaps = new ExecPlacementCaps();
		fedParentCaps.allowCP_LOUT = false;
		fedParentCaps.allowCP_FOUT = false;
		Vertex parent = new Vertex(new LiteralOp(23L), Privacy.PUBLIC, FType.FULL, fedParentCaps);
		parent.setMetadata(1.0, 1.0, Collections.emptyList());
		parent.setCost(100.0, 0.0, 0.0);
		graph.addVertex(parent);
		graph.setVertexCost(parent);
		graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);

		ExecPlacementCaps localParentCaps = new ExecPlacementCaps();
		localParentCaps.allowFED_LOUT = false;
		localParentCaps.allowFED_FOUT = false;
		Vertex localParent = new Vertex(new LiteralOp(24L), Privacy.PUBLIC, FType.FULL, localParentCaps);
		localParent.setMetadata(1.0, 1.0, Collections.emptyList());
		localParent.setCost(0.0, 0.0, 0.0);
		graph.addVertex(localParent);
		graph.setVertexCost(localParent);
		graph.addRequiredLocalInputEdge(localParent.getHopID(), child.getHopID());

		graph.getOptimalPlan();

		Assert.assertTrue("Scalar/non-matrix values must not create CP->FOUT U obligations",
			graph.getSelectedObligations().isEmpty());
	}

	@Test
	public void testTransientReadAddsDownloadEdgeForCpConsumers() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		DataOp tRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100000, 1050, 100000L * 1050L, 1000);
		Vertex trVertex = new Vertex(tRead, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		trVertex.setMetadata(3.0, 2.0, Collections.emptyList());
		trVertex.setCost(0.0, 13.0, 7.0);
		graph.addVertex(trVertex);

		graph.addExecPlacementResultEdge(trVertex);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long trC = computeId(tRead.getHopID());
		long trP = placementId(tRead.getHopID());
		DefaultWeightedEdge downloadEdge = g.getEdge(trC, trP);
		Assert.assertNotNull("TransientRead should add FED/LOUT materialization edge", downloadEdge);
		Assert.assertEquals("Unexpected download edge weight for TransientRead",
			21.0, g.getEdgeWeight(downloadEdge), 1e-9);
		Assert.assertNull("TransientRead should not add placement->compute upload edge",
			g.getEdge(trP, trC));
	}

	@Test
	public void testFedInitTransientReadDownloadEdgeIsSharedAcrossLoopIterations() {
		FederatedPlannerUtils.registerFedInitVar("X");
		try {
			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			DataOp tRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, null, 100000, 1050, 100000L * 1050L, 1000);
			Vertex trVertex = new Vertex(tRead, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
			trVertex.setMetadata(60.0, 60.0, Collections.emptyList());
			trVertex.setCost(0.0, 13.0, 7.0);
			graph.addVertex(trVertex);

			graph.addExecPlacementResultEdge(trVertex);

			Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
			long trC = computeId(tRead.getHopID());
			long trP = placementId(tRead.getHopID());
			DefaultWeightedEdge downloadEdge = g.getEdge(trC, trP);
			Assert.assertNotNull("Fed-init TransientRead should add FED/LOUT materialization edge", downloadEdge);
			Assert.assertEquals("Fed-init TransientRead download should be shared across loop iterations",
				7.0, g.getEdgeWeight(downloadEdge), 1e-9);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testComputeVertexCostUsesFallbackMemForTransientRead() {
		DataOp tRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 1000, 1000, 1000L * 1000L, 1000) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 1024 * 1024;
			}
		};
		Vertex trVertex = new Vertex(tRead, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		trVertex.setMetadata(1.0, 1.0, Collections.emptyList());

		FederatedPlanMinSTCostEstimator.computeVertexCost(trVertex, 2);

		Assert.assertTrue("TransientRead download cost should use fallback mem estimate",
			trVertex.getDownloadCostWithoutWeight() > 0.0);
		Assert.assertTrue("TransientRead CP upload cost should use fallback mem estimate",
			trVertex.getCpUploadCostWithoutWeight() > 0.0);
	}

	@Test
	public void testComputeVertexCostUsesFallbackOpCostWhenRawMemUnknown() {
		LiteralOp syntheticHop = new LiteralOp(1.0) {
			@Override
			public double getInputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getInputMemEstimate(double injectedDefault) {
				return 8 * 1024 * 1024;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 4 * 1024 * 1024;
			}
		};
		Vertex vertex = new Vertex(syntheticHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		vertex.setMetadata(1.0, 1.0, Collections.emptyList());

		FederatedPlanMinSTCostEstimator.computeVertexCost(vertex, 2);

		Assert.assertTrue("Vertex op cost should not remain zero when fallback mem estimate is available",
			vertex.getOpCostWithWeight() > 0.0);
	}

	@Test
	public void testParentChildUploadFallbackUsesEffectiveMemEstimate() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		DataOp childHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 1000, 1000, 1000L * 1000L, 1000) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 2 * 1024 * 1024;
			}
		};
		Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		child.setMetadata(1.0, 1.0, Collections.emptyList());
		child.setCost(0.0, 0.0, 0.0);
		child.setCpUploadCostWithoutWeight(0.0);
		graph.addVertex(child);

		Vertex parent = createVertex(new LiteralOp(7.0));
		graph.addVertex(parent);

		graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());
		long parentC = computeId(parent.getHopID());
		Assert.assertNull("Fallback upload should be encoded through the shared U hyperedge",
			g.getEdge(parentC, childP));
		Assert.assertTrue("Parent-child shared U hyperedge should use fallback mem estimate",
			computeCutCostWithHardClosure(g, setOf(parentC)) > 0.0);
	}

	@Test
	public void testLoopCarryUploadFallbackAddsForwardingPenalty() throws Exception {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.setNumOfWorkers(4);
		DataOp readerHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 1000, 1000, 1000L * 1000L, 1000) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 2 * 1024 * 1024;
			}
		};
		Vertex reader = new Vertex(readerHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		reader.setMetadata(1.0, 1.0, Collections.emptyList());
		reader.setCost(0.0, 0.0, 3.0);
		reader.setCpUploadCostWithoutWeight(0.0);
		graph.addVertex(reader);

		Vertex writer = createVertex(new LiteralOp(17.0));
		graph.addVertex(writer);
		graph.addLoopCarryEdge(writer.getHopID(), reader.getHopID(), 2.0);

		Method m = FederatedPlanMinSTCostEstimator.class.getDeclaredMethod(
			"addLoopCarryEdgesForHop", Hop.class, Vertex.class, FederatedPlanMinSTGraph.class);
		m.setAccessible(true);
		m.invoke(null, readerHop, reader, graph);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long readerC = computeId(reader.getHopID());
		long writerP = placementId(writer.getHopID());
		DefaultWeightedEdge uploadEdge = g.getEdge(readerC, writerP);
		DefaultWeightedEdge downloadEdge = g.getEdge(writerP, readerC);
		Assert.assertNotNull("Expected loop-carry upload edge", uploadEdge);
		Assert.assertNotNull("Expected loop-carry download edge", downloadEdge);

		double uploadBase = FederatedCostModel.computeUploadNetworkCost(2 * 1024 * 1024, FType.ROW, 4);
		double penalty = FederatedCostModel.computeLocalToFedForwardingPenalty(FType.ROW, 4);
		double expectedUpload = 2.0 * (uploadBase + penalty);
		Assert.assertEquals("Loop-carry upload should use fallback mem and fan-out penalty",
			expectedUpload, g.getEdgeWeight(uploadEdge), 1e-9);
		Assert.assertEquals("Loop-carry download should preserve reader download cost",
			6.0, g.getEdgeWeight(downloadEdge), 1e-9);
	}

	@Test
	public void testLoopCarryUploadFallbackUsesWriterMemWhenReaderUnknown() throws Exception {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.setNumOfWorkers(4);
		DataOp readerHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, -1, -1, -1, 1000) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 0.0;
			}
		};
		DataOp writerHop = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTWRITE, null, 1000, 1000, 1000L * 1000L, 1000) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 3 * 1024 * 1024;
			}
		};

		Vertex reader = new Vertex(readerHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		reader.setMetadata(1.0, 1.0, Collections.emptyList());
		reader.setCost(0.0, 0.0, 2.0);
		reader.setCpUploadCostWithoutWeight(0.0);
		graph.addVertex(reader);

		Vertex writer = new Vertex(writerHop, Privacy.PUBLIC, FType.ROW, new ExecPlacementCaps());
		writer.setMetadata(1.0, 1.0, Collections.emptyList());
		writer.setCost(0.0, 0.0, 0.0);
		graph.addVertex(writer);

		graph.addLoopCarryEdge(writer.getHopID(), reader.getHopID(), 1.0);

		Method m = FederatedPlanMinSTCostEstimator.class.getDeclaredMethod(
			"addLoopCarryEdgesForHop", Hop.class, Vertex.class, FederatedPlanMinSTGraph.class);
		m.setAccessible(true);
		m.invoke(null, readerHop, reader, graph);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long readerC = computeId(reader.getHopID());
		long writerP = placementId(writer.getHopID());
		DefaultWeightedEdge uploadEdge = g.getEdge(readerC, writerP);
		Assert.assertNotNull("Expected loop-carry upload edge via writer fallback mem", uploadEdge);

		double uploadBase = FederatedCostModel.computeUploadNetworkCost(3 * 1024 * 1024, FType.ROW, 4);
		double penalty = FederatedCostModel.computeLocalToFedForwardingPenalty(FType.ROW, 4);
		Assert.assertEquals("Loop-carry upload should recover from writer-side mem estimate",
			uploadBase + penalty, g.getEdgeWeight(uploadEdge), 1e-9);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testBuildPlannedFTypeMapKeepsLoutHintWhenCpFoutTypeMissing() throws Exception {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();

		DataOp lhs = new DataOp("L", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 10, 1, -1, 1000);
		DataOp rhs = new DataOp("R", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 10, 1, -1, 1000);
		Hop localHop = HopRewriteUtils.createBinary(lhs, rhs, OpOp2.PLUS);
		localHop.setDim1(10);
		localHop.setDim2(1);
		localHop.setForcedExecType(ExecType.CP);
		localHop.setFederatedOutput(FederatedOutput.LOUT);

		Vertex localVertex = new Vertex(localHop, Privacy.PUBLIC, FType.BROADCAST, null, new ExecPlacementCaps());
		graph.addVertex(localVertex);

		Method m = FederatedPlanMinSTCut.class.getDeclaredMethod(
			"buildPlannedFTypeMap", FederatedPlanMinSTGraph.class);
		m.setAccessible(true);
		Map<Long, FType> fTypeMap = (Map<Long, FType>) m.invoke(null, graph);

		Assert.assertEquals("Expected LOUT hop to retain BROADCAST hint via vertex dataType fallback",
			FType.BROADCAST, fTypeMap.get(localHop.getHopID()));
	}

	private static Vertex createVertex(LiteralOp hop) {
		Vertex vertex = new Vertex(hop, Privacy.PUBLIC, FType.FULL, new ExecPlacementCaps());
		vertex.setMetadata(1.0, 1.0, Collections.emptyList());
		return vertex;
	}

	private static List<Vertex> createParents(FederatedPlanMinSTGraph graph, int count) {
		List<Vertex> parents = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Vertex parent = createVertex(new LiteralOp((long) (i + 10)));
			graph.addVertex(parent);
			parents.add(parent);
		}
		return parents;
	}

	private static double computeCutCost(Graph<Long, DefaultWeightedEdge> graph, Set<Long> sourceSide) {
		double cost = 0.0;
		for (DefaultWeightedEdge edge : graph.edgeSet()) {
			Long u = graph.getEdgeSource(edge);
			Long v = graph.getEdgeTarget(edge);
			if (sourceSide.contains(u) && !sourceSide.contains(v)) {
				cost += graph.getEdgeWeight(edge);
			}
		}
		return cost;
	}

	private static double computeCutCostWithHardClosure(Graph<Long, DefaultWeightedEdge> graph, Set<Long> sourceSide) {
		Set<Long> closed = new HashSet<>(sourceSide);
		boolean changed;
		do {
			changed = false;
			for (DefaultWeightedEdge edge : graph.edgeSet()) {
				Long u = graph.getEdgeSource(edge);
				Long v = graph.getEdgeTarget(edge);
				if (closed.contains(u) && !closed.contains(v) && graph.getEdgeWeight(edge) > 1e12) {
					closed.add(v);
					changed = true;
				}
			}
		}
		while (changed);
		return computeCutCost(graph, closed);
	}

	private static Set<Long> setOf(long... values) {
		Set<Long> set = new HashSet<>();
		for (long value : values)
			set.add(value);
		return set;
	}

	private static void assertUDDecisionMatchesSemanticInvariants(UDDecision actual,
			ExecType childExec, FederatedOutput childOut, int numParents, int parentFedMask,
			boolean unconditionalLocal, boolean unconditionalFed, UDCapabilities caps) {
		boolean activeLocal = unconditionalLocal || hasParentState(numParents, parentFedMask, ExecType.CP);
		boolean activeFed = unconditionalFed || hasParentState(numParents, parentFedMask, ExecType.FED);
		boolean reusableLocal = hasReusableLocal(childExec, childOut, caps);
		boolean reusableFed = hasReusableFed(childExec, childOut, caps);

		Assert.assertEquals("activeLocal formula mismatch",
			activeLocal, actual.activeLocal);
		Assert.assertEquals("activeFed formula mismatch",
			activeFed, actual.activeFed);
		Assert.assertEquals("U must be active exactly when FED demand lacks reusable federated representation",
			activeFed && !reusableFed, actual.requiresU);
		Assert.assertEquals("D must be active exactly when LOCAL demand lacks reusable local representation",
			activeLocal && !reusableLocal, actual.requiresD);
		Assert.assertEquals("U must charge at most once per compatible active domain",
			actual.requiresU ? 1 : 0, actual.uChargeCount);
		Assert.assertEquals("D must charge at most once per compatible active domain",
			actual.requiresD ? 1 : 0, actual.dChargeCount);
		Assert.assertEquals("Capability false must not become free U/D conversion",
			(!actual.requiresU || caps.uRuntimeRewrite)
				&& (!actual.requiresD || caps.dSharedLocalMaterialization),
			actual.valid);
	}

	private static UDDecision expectedUDDecision(ExecType childExec, FederatedOutput childOut,
			int numParents, int parentFedMask, boolean unconditionalLocal, boolean unconditionalFed,
			UDCapabilities caps) {
		boolean activeLocal = unconditionalLocal || hasParentState(numParents, parentFedMask, ExecType.CP);
		boolean activeFed = unconditionalFed || hasParentState(numParents, parentFedMask, ExecType.FED);
		boolean requiresU = activeFed && !hasReusableFed(childExec, childOut, caps);
		boolean requiresD = activeLocal && !hasReusableLocal(childExec, childOut, caps);
		boolean valid = (!requiresU || caps.uRuntimeRewrite)
			&& (!requiresD || caps.dSharedLocalMaterialization);
		return new UDDecision(activeLocal, activeFed, requiresU, requiresD,
			requiresU ? 1 : 0, requiresD ? 1 : 0, valid);
	}

	private static UDDecision naivePOnlyUDDecision(ExecType childExec, FederatedOutput childOut,
			int numParents, int parentFedMask, boolean unconditionalLocal, boolean unconditionalFed,
			UDCapabilities caps) {
		boolean activeLocal = unconditionalLocal || hasParentState(numParents, parentFedMask, ExecType.CP);
		boolean activeFed = unconditionalFed || hasParentState(numParents, parentFedMask, ExecType.FED);
		boolean requiresU = activeFed && childOut == FederatedOutput.LOUT;
		boolean requiresD = activeLocal && childOut == FederatedOutput.FOUT;
		boolean valid = (!requiresU || caps.uRuntimeRewrite)
			&& (!requiresD || caps.dSharedLocalMaterialization);
		return new UDDecision(activeLocal, activeFed, requiresU, requiresD,
			requiresU ? 1 : 0, requiresD ? 1 : 0, valid);
	}

	private static boolean hasParentState(int numParents, int parentFedMask, ExecType state) {
		for (int i = 0; i < numParents; i++) {
			boolean fedParent = ((parentFedMask >> i) & 1) != 0;
			if ((state == ExecType.FED && fedParent) || (state == ExecType.CP && !fedParent))
				return true;
		}
		return false;
	}

	private static boolean hasReusableLocal(ExecType childExec, FederatedOutput childOut, UDCapabilities caps) {
		if (childExec == ExecType.CP && childOut == FederatedOutput.LOUT)
			return true;
		if (childExec == ExecType.CP && childOut == FederatedOutput.FOUT)
			return caps.cpFoutLocalReuse;
		if (childExec == ExecType.FED && childOut == FederatedOutput.LOUT)
			return true;
		return false;
	}

	private static boolean hasReusableFed(ExecType childExec, FederatedOutput childOut, UDCapabilities caps) {
		if (childExec == ExecType.CP && childOut == FederatedOutput.FOUT)
			return true;
		if (childExec == ExecType.FED && childOut == FederatedOutput.FOUT)
			return true;
		if (childExec == ExecType.FED && childOut == FederatedOutput.LOUT)
			return caps.fedLoutFedReuse;
		return false;
	}

	private static final class UDCapabilities {
		private final boolean cpFoutLocalReuse;
		private final boolean fedLoutFedReuse;
		private final boolean uRuntimeRewrite;
		private final boolean dSharedLocalMaterialization;

		private UDCapabilities(boolean cpFoutLocalReuse, boolean fedLoutFedReuse,
				boolean uRuntimeRewrite, boolean dSharedLocalMaterialization) {
			this.cpFoutLocalReuse = cpFoutLocalReuse;
			this.fedLoutFedReuse = fedLoutFedReuse;
			this.uRuntimeRewrite = uRuntimeRewrite;
			this.dSharedLocalMaterialization = dSharedLocalMaterialization;
		}
	}

	private static final class UDDecision {
		private final boolean activeLocal;
		private final boolean activeFed;
		private final boolean requiresU;
		private final boolean requiresD;
		private final int uChargeCount;
		private final int dChargeCount;
		private final boolean valid;

		private UDDecision(boolean activeLocal, boolean activeFed,
				boolean requiresU, boolean requiresD,
				int uChargeCount, int dChargeCount, boolean valid) {
			this.activeLocal = activeLocal;
			this.activeFed = activeFed;
			this.requiresU = requiresU;
			this.requiresD = requiresD;
			this.uChargeCount = uChargeCount;
			this.dChargeCount = dChargeCount;
			this.valid = valid;
		}
	}

	private static boolean approxEqual(double actual, double expected) {
		return Math.abs(actual - expected) < 1e-9;
	}

	private static long computeId(long hopId) {
		return hopId << 2;
	}

	private static long placementId(long hopId) {
		return (hopId << 2) | 1;
	}

	private static long localityId(long hopId) {
		return (hopId << 2) | 2;
	}
}

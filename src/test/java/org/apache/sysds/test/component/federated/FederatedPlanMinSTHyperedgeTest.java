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
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTHyperedgeTest {

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

		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			DefaultWeightedEdge uploadEdge = g.getEdge(parentC, childP);
			Assert.assertNotNull("Expected direct upload edge from parent to child placement", uploadEdge);
			Assert.assertEquals("Unexpected direct upload edge weight",
				uploadCost, g.getEdgeWeight(uploadEdge), 1e-9);
		}

		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents) {
			sourceSide.add(computeId(parent.getHopID()));
		}
		double cutCost = computeCutCost(g, sourceSide);
		Assert.assertEquals("Upload cost should be paid per parent-child edge",
			uploadCost * parents.size(), cutCost, 1e-9);
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
			DefaultWeightedEdge uploadEdge = g.getEdge(parentC, childP);
			Assert.assertNotNull("Expected direct upload edge from parent to child placement", uploadEdge);
			Assert.assertEquals("Unexpected direct upload edge weight",
				uploadCost, g.getEdgeWeight(uploadEdge), 1e-9);
			sourceSide.add(computeId(parent.getHopID()));
		}
		Assert.assertEquals("Optional matrix input should still pay forwarding upload per parent edge",
			uploadCost * parents.size(), computeCutCost(g, sourceSide), 1e-9);
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
		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			DefaultWeightedEdge uploadEdge = g.getEdge(parentC, childP);
			Assert.assertNotNull("Expected direct upload edge from parent to child placement", uploadEdge);
			Assert.assertEquals("Unexpected direct upload edge weight",
				uploadCost, g.getEdgeWeight(uploadEdge), 1e-9);
			Assert.assertNull("Unexpected direct download edge from child placement to parent",
					g.getEdge(childP, parentC));
		}
		for (DefaultWeightedEdge edge : g.edgeSet()) {
			if (g.getEdgeSource(edge).equals(childP) && approxEqual(g.getEdgeWeight(edge), downloadCost)) {
				Assert.fail("Parent-child edge must not introduce download hyperedge");
			}
		}

		Set<Long> sourceSide = new HashSet<>();
		sourceSide.add(childP);
		double cutCost = computeCutCost(g, sourceSide);
		Assert.assertEquals("Download hyperedge should not be charged on parent-child edge", 0.0, cutCost, 1e-9);
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
		long trL = localityId(tRead.getHopID());
		DefaultWeightedEdge downloadEdge = g.getEdge(trC, trL);
		Assert.assertNotNull("TransientRead should add FED->local download edge", downloadEdge);
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
			long trL = localityId(tRead.getHopID());
			DefaultWeightedEdge downloadEdge = g.getEdge(trC, trL);
			Assert.assertNotNull("Fed-init TransientRead should add FED->local download edge", downloadEdge);
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
		DefaultWeightedEdge uploadEdge = g.getEdge(parentC, childP);
		boolean hasPositiveUploadCostToChild = uploadEdge != null && g.getEdgeWeight(uploadEdge) > 0.0;
		Assert.assertTrue("Parent-child upload edge should use fallback mem estimate",
			hasPositiveUploadCostToChild);
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

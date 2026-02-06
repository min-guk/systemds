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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTHyperedgeTest {

	@Test
	public void testUploadOrCostSingleActivation() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 10.0;
		double downloadCost = 7.0;

		Vertex child = createVertex(new LiteralOp(1.0));
		child.setCost(0.0, uploadCost, downloadCost);
		child.setNumParents(3);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 3);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());

		long uploadAux = findAuxToChild(g, childP, uploadCost);
		Assert.assertTrue("Expected upload aux node to be negative", uploadAux < 0);

		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
			Assert.assertNull("Unexpected direct upload edge from parent to child placement",
					g.getEdge(parentC, childP));
		}

		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents) {
			sourceSide.add(computeId(parent.getHopID()));
		}
		sourceSide.add(uploadAux);
		double cutCost = computeCutCost(g, sourceSide);
		Assert.assertEquals("Upload OR cost should be paid once", uploadCost, cutCost, 1e-9);
	}

	@Test
	public void testOptionalFedInputSkipsUploadHyperedge() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 8.0;
		double downloadCost = 5.0;

		Vertex child = createVertex(new LiteralOp(3.0));
		child.setCost(0.0, uploadCost, downloadCost);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 2);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), false);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());
		for (DefaultWeightedEdge edge : g.edgeSet()) {
			if (g.getEdgeTarget(edge).equals(childP)
					&& approxEqual(g.getEdgeWeight(edge), uploadCost)) {
				Assert.fail("Optional input must not create an upload hyperedge");
			}
		}

		Set<Long> sourceSide = new HashSet<>();
		for (Vertex parent : parents)
			sourceSide.add(computeId(parent.getHopID()));
		Assert.assertEquals("Optional input edge should not add forwarding cost", 0.0, computeCutCost(g, sourceSide), 1e-9);
	}

	@Test
	public void testParentChildEdgeDoesNotCreateDownloadHyperedge() {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		double uploadCost = 9.0;
		double downloadCost = 11.0;

		Vertex child = createVertex(new LiteralOp(2.0));
		child.setCost(0.0, uploadCost, downloadCost);
		child.setNumParents(3);
		graph.addVertex(child);

		List<Vertex> parents = createParents(graph, 3);
		for (Vertex parent : parents) {
			graph.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);
		}

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		long childP = placementId(child.getHopID());

		long uploadAux = findAuxToChild(g, childP, uploadCost);
		for (Vertex parent : parents) {
			long parentC = computeId(parent.getHopID());
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
		sourceSide.add(uploadAux);
		double cutCost = computeCutCost(g, sourceSide);
		Assert.assertEquals("Download hyperedge should not be charged on parent-child edge", 0.0, cutCost, 1e-9);
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

	private static long findAuxToChild(Graph<Long, DefaultWeightedEdge> graph, long childP, double weight) {
		List<Long> matches = new ArrayList<>();
		for (DefaultWeightedEdge edge : graph.edgeSet()) {
			if (graph.getEdgeTarget(edge).equals(childP) && approxEqual(graph.getEdgeWeight(edge), weight)) {
				matches.add(graph.getEdgeSource(edge));
			}
		}
		Assert.assertEquals("Expected exactly one upload aux edge to child placement", 1, matches.size());
		return matches.get(0);
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
}

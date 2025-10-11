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

package org.apache.sysds.hops.fedplanner;

import java.util.HashMap;
import java.util.Map;
import org.apache.sysds.hops.Hop;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import java.util.*;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.common.Types.ExecType;

/**
 * A Memoization Table for managing federated plans (FedPlan) based on combinations of Hops and fedOutTypes.
 * This table stores and manages different execution plan variants for each Hop and fedOutType combination,
 * facilitating the optimization of federated execution plans.
 */
public class FederatedPlanMinSTGraph {
	private final Map<Long, Vertex> memoTable = new HashMap<>();
    private final Graph<Long, DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);

	int numOfWorkers = 0;
	final double POSITIVE_INFINITY = 1e12;
	final long leafedSource = -1L, rootLocalSink = -2L;
	{ graph.addVertex(leafedSource); graph.addVertex(rootLocalSink); }
	
	public FederatedPlanMinSTGraph() {}

	public Map<Long, Vertex> getMemoTable() {
		return memoTable;
	}

	public int getNumOfWorkers() {
		return numOfWorkers;
	} 

	public Graph<Long, DefaultWeightedEdge> getGraph() {
		return graph;
	}

	public void setNumOfWorkers(int numOfWorkers) {
		this.numOfWorkers = numOfWorkers;
	} 

	public void addVertex(Vertex vertex) {
		long hopID = vertex.getHopID();
		memoTable.put(hopID, vertex);
		graph.addVertex(hopID);
	}

	public void setVertexCost (Vertex vertex){
		long hopID = vertex.getHopID();
		
		double localOpCost = vertex.isLocalExecutable_ ? vertex.getOpCostWithWeight() : POSITIVE_INFINITY;
		double fedOpCost = vertex.isFedExecutable_ ? vertex.getOpCostWithWeight() / Math.max(1, numOfWorkers) : POSITIVE_INFINITY;

		// Operation Cost
		this.addCap(leafedSource, hopID, localOpCost);
		this.addCap(hopID, rootLocalSink, fedOpCost);
	}

	public void addEdgeWithNetCost(Vertex childVertex, long childHopID,
								Vertex parentVertex, long parentHopID) {
		// 1) 불일치 비용 w 계산 (자식 분할 기준)
		double forwardingWeight = parentVertex.getChildForwardingWeight(childVertex.getLoopContext());
		double netCostWithoutWeight = childVertex.getNetCostWithoutWeight();
		double netCost = forwardingWeight * netCostWithoutWeight;
		FType dt = childVertex.getDataType();

		// 주석 설계대로: BROADCAST면 netCost, COL/ROW면 netCost/numOfWorkers
		if (dt == FType.ROW || dt == FType.COL) {
			netCost /= Math.max(1, numOfWorkers);
		}

		// Todo: 간선 구현 뭐가 맞는지 확인하기
		// addCap(childHopID, parentHopID, netCost); // charges when child=FED(S), parent=LOCAL(T) (F→L polarity only)
		// addCap(parentHopID, childHopID, netCost); // charges when parent=FED(S), child=LOCAL(T) (L→F polarity only)

		boolean canFL = childVertex.isFedExecutable_ && parentVertex.isLocalExecutable_; // 자식=F, 부모=L
		boolean canLF = childVertex.isLocalExecutable_ && parentVertex.isFedExecutable_; // 자식=L, 부모=F

		// 3) 경우별 간선 추가
		if (canFL && canLF) {
			addCap(childHopID, parentHopID, netCost); // charges when child=FED(S), parent=LOCAL(T) (F→L polarity only)
			addCap(parentHopID, childHopID, netCost); // charges when parent=FED(S), child=LOCAL(T) (L→F polarity only)
		}
		else if (canFL) {
			// 오직 F→L만 가능
			addCap(childHopID, parentHopID, netCost); 
		}
		else if (canLF) {
			// 오직 L→F만 가능
			addCap(parentHopID, childHopID, netCost); 
		}
	}

	public void addTransReadWriteEdgeWithNetCost(Vertex childVertex, long childHopID,
							Vertex parentVertex, long parentHopID) {
		addCap(childHopID, parentHopID, POSITIVE_INFINITY);
		addCap(parentHopID, childHopID, POSITIVE_INFINITY);
	}

	private void addCap(long u, long v, double cap) {
		if (cap <= 0) return;
		DefaultWeightedEdge e = graph.getEdge(u, v);
		if (e == null) {
			e = graph.addEdge(u, v);
			if (e == null) return; // 방어
			graph.setEdgeWeight(e, cap);
		} else {
			graph.setEdgeWeight(e, graph.getEdgeWeight(e) + cap);
		}
	}

	public Hop getHopRef(long hopID) {
		return memoTable.get(hopID).getHopRef();
	}

	public Vertex getVertex(long hopID) {
		return memoTable.get(hopID);
	}

	public boolean contains(long hopID) {
		return graph.containsVertex(hopID) && memoTable.containsKey(hopID);
	}

	public void getOptimalPlan(){
		PushRelabelMFImpl<Long, DefaultWeightedEdge> algo = new PushRelabelMFImpl<>(graph);
		algo.calculateMinCut(leafedSource, rootLocalSink); 

		Set<Long> sourceSide = algo.getSourcePartition();  // S
		Set<Long> sinkSide = algo.getSinkPartition();  // T

		for (Long hopID : sourceSide) {
			Vertex vertex = memoTable.get(hopID);
			if (hopID == leafedSource || hopID == rootLocalSink) continue; // Vertex sink/source(-1, -2)
			vertex.getHopRef().setForcedExecType(ExecType.FED);

			// Check if the hop has no parents
			List<Hop> parents = vertex.getHopRef().getParent();

			// Todo: Maybe TRead?
			if (parents.isEmpty()) {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.FOUT);
				continue;  // Process next hop instead of returning
			}

			// If the hop has parents, compare sum forwarding cost
			// 1. Check parents' Exec Type
			// 2. Calculate forwarding cost considering weight (getChildForwardingWeight)
			// 3. Assign to the side with higher total forwarding cost
			double fedParentForwardingCostSum = 0.0;
			double localParentForwardingCostSum = 0.0;

			for (Hop parent : parents) {
				Vertex parentVertex = memoTable.get(parent.getHopID());
				if (parentVertex == null) continue;

				// Get child forwarding weight considering loop context
				double weight = parentVertex.getChildForwardingWeight(vertex.getLoopContext());
				double forwardingCost = weight * vertex.getNetCostWithoutWeight();

				if (sourceSide.contains(parent.getHopID())) {
					// Parent is on federated (source) side
					fedParentForwardingCostSum += forwardingCost;
				} else {
					// Parent is on local (sink) side
					localParentForwardingCostSum += forwardingCost;
				}
			}

			// Assign to the side with higher forwarding cost sum
			if (fedParentForwardingCostSum > localParentForwardingCostSum) {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.FOUT);
			} else {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.LOUT);
			}
		}

		for (Long hopID : sinkSide) {
			Vertex vertex = memoTable.get(hopID);
			if (hopID == leafedSource || hopID == rootLocalSink) continue; // Vertex sink/source(-1, -2)
			vertex.getHopRef().setForcedExecType(ExecType.CP);

			// Check if the hop has no parents
			List<Hop> parents = vertex.getHopRef().getParent();

			// Todo: Maybe TRead?
			if (parents.isEmpty()) {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.LOUT);
				continue;  // Process next hop instead of returning
			}
			
			// If the hop has parents, compare sum forwarding cost
			// 1. Check parents' Exec Type
			// 2. Calculate forwarding cost considering weight (getChildForwardingWeight)
			// 3. Assign to the side with higher total forwarding cost
			double fedParentForwardingCostSum = 0.0;
			double localParentForwardingCostSum = 0.0;

			for (Hop parent : parents) {
				Vertex parentVertex = memoTable.get(parent.getHopID());
				if (parentVertex == null) continue;

				// Get child forwarding weight considering loop context
				double weight = parentVertex.getChildForwardingWeight(vertex.getLoopContext());
				double forwardingCost = weight * vertex.getNetCostWithoutWeight();

				if (sourceSide.contains(parent.getHopID())) {
					// Parent is on federated (source) side
					fedParentForwardingCostSum += forwardingCost;
				} else {
					// Parent is on local (sink) side
					localParentForwardingCostSum += forwardingCost;
				}
			}

			// Assign to the side with higher forwarding cost sum
			if (fedParentForwardingCostSum > localParentForwardingCostSum) {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.FOUT);
			} else {
				vertex.getHopRef().setFederatedOutput(FederatedOutput.LOUT);
			}
		}
	}

	public static class Vertex {
		public final Hop hop_;
		public final long hopId_;

		public final Privacy privacy_;
		public final boolean isFedExecutable_;
		public final FType dataType_;
		public final boolean isLocalExecutable_;

		private double opCostWithWeight_;
		private double netCostWithoutWeight_;

		private double opWeight; // Weight used to calculate cost based on hop execution frequency
		private double networkWeight; // Weight used to calculate cost based on hop execution frequency
		private List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists

		public Vertex(Hop hop, Privacy privacy, FType dataType) {
		  this.hop_ = hop; 
		  this.hopId_ = hop.getHopID();
		  this.privacy_ = privacy; 
		  this.dataType_ = dataType; 
		
		  isFedExecutable_ = dataType != null;
		  isLocalExecutable_ = privacy == Privacy.PUBLIC;
		}

		public Hop getHopRef() {return hop_;}
		public long getHopID() {return hopId_;}
		public Privacy getPrivacy() {return privacy_;}
		public FType getDataType() {return dataType_;}

		public double getOpCostWithWeight() {return opCostWithWeight_;}
		public double getNetCostWithoutWeight() {return netCostWithoutWeight_;}

		public double getOpWeight() {return opWeight;}
		public double getNetworkWeight() {return networkWeight;}
		public List<Pair<Long, Double>> getLoopContext() {return loopContext;}

		public void setMetadata(double opWeight, double networkWeight, List<Pair<Long, Double>> loopContext) {
			this.opWeight = opWeight;
			this.networkWeight = networkWeight;
			this.loopContext = loopContext;
		}

		public void setCost(double opCostWithWeight, double netCostWithoutWeight) {
			this.opCostWithWeight_ = opCostWithWeight; 
			this.netCostWithoutWeight_ = netCostWithoutWeight;
		}

		public double getChildForwardingWeight(List<Pair<Long, Double>> childLoopContext) {
			final double base = (networkWeight != 0.0) ? networkWeight : 1.0;
		
			final List<Pair<Long, Double>> parent =
				(loopContext != null) ? loopContext : Collections.emptyList();
			final List<Pair<Long, Double>> child =
				(childLoopContext != null) ? childLoopContext : Collections.emptyList();
		
			// 1) 부모/자식 루프 ID의 최장 공통 접두(Longest Common Prefix) 길이
			int lcp = 0;
			while (lcp < parent.size() && lcp < child.size()
				   && Objects.equals(parent.get(lcp).getLeft(), child.get(lcp).getLeft())) {
				lcp++;
			}
		
			// 2) 자식의 LCP 이후(=자식만 추가로 갖는 내부 루프) 반복수로만 상쇄
			double amort = 1.0;
			for (int i = lcp; i < child.size(); i++) {
				double iters = child.get(i).getRight();
				if (iters > 0.0) amort *= iters;
			}
		
			return base / amort;  // 부모 루프 반복수로는 나누지 않음!
		}

		@Override public boolean equals(Object o) {
		  if (this == o) return true;
		  if (!(o instanceof Vertex)) return false;
		  Vertex other = (Vertex) o;
		  return hopId_ == other.hopId_ && privacy_ == other.privacy_ && dataType_ == other.dataType_;
		}
		@Override public int hashCode() {
		  return Objects.hash(hopId_, privacy_, dataType_);
		}
		@Override public String toString() { // 디버깅 편의
		  return "h" + hopId_ + ":" + privacy_ + ":" + dataType_;
		}	
	}
}

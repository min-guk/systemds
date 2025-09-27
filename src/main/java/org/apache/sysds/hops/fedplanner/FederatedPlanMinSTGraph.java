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

/**
 * A Memoization Table for managing federated plans (FedPlan) based on combinations of Hops and fedOutTypes.
 * This table stores and manages different execution plan variants for each Hop and fedOutType combination,
 * facilitating the optimization of federated execution plans.
 */
public class FederatedPlanMinSTGraph {
	private final Map<Long, Vertex> memoTable = new HashMap<>();
    private final Graph<Long, DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);

	final int numOfWorkers;
	final double POSITIVE_INFINITY = 1e15;
	final long leafedSource = -1L, rootLocalSink = -2L;
	{ graph.addVertex(leafedSource); graph.addVertex(rootLocalSink); }
	
	public FederatedPlanMinSTGraph(int numOfWorkers) {
		this.numOfWorkers = numOfWorkers;
	}

	public void addVertexWithCost (Vertex vertex){
		long hopID = vertex.getHopID();
		// Memoization Table & Graph
		memoTable.put(hopID, vertex);
		graph.addVertex(hopID);
		
		double localOpCost = vertex.isLocalExecutable_ ? vertex.getOpCost() : POSITIVE_INFINITY;
		double fedOpCost = vertex.isFedExecutable_ ? vertex.getOpCost() / numOfWorkers : POSITIVE_INFINITY;

		// Operation Cost
		this.addCap(leafedSource, hopID, fedOpCost);
		this.addCap(hopID, rootLocalSink, localOpCost);
	}

	public void addEdgeWithNetCost(Vertex childVertex, long childHopID,
								Vertex parentVertex, long parentHopID) {
		// 1) 불일치 비용 w 계산 (자식 분할 기준)
		double netCost = childVertex.getNetCost();
		FType dt = childVertex.getDataType();

		// 주석 설계대로: BROADCAST면 netCost, COL/ROW면 netCost/numOfWorkers
		if (dt == FType.ROW || dt == FType.COL) {
			netCost /= Math.max(1, numOfWorkers);
		}
		if (netCost <= 0) return;
		
		boolean canFL = childVertex.isFedExecutable_ && parentVertex.isLocalExecutable_; // 자식=F, 부모=L
		boolean canLF = childVertex.isLocalExecutable_ && parentVertex.isFedExecutable_; // 자식=L, 부모=F

		// 3) 경우별 간선 추가
		if (canFL && canLF) {
			// 두 방향 모두 가능 → 불일치(Potts) 대칭 간선
			addCap(childHopID, parentHopID, netCost); // L→F
			addCap(parentHopID, childHopID, netCost); // F→L
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

	public static class Vertex {
		public final Hop hop_;
		public final long hopId_;

		public final Privacy privacy_;
		public final boolean isFedExecutable_;
		public final FType dataType_;
		public final boolean isLocalExecutable_;

		private double opCost_;
		private double netCost_;

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

		public double getOpCost() {return opCost_;}
		public double getNetCost() {return netCost_;}

		public double getOpWeight() {return opWeight;}
		public double getNetworkWeight() {return networkWeight;}
		// Todo: 지워야하는 것 아닌가?
		public List<Pair<Long, Double>> getLoopContext() {return loopContext;}

		public void setMetadata(double opWeight, double networkWeight, List<Pair<Long, Double>> loopContext) {
			this.opWeight = opWeight;
			this.networkWeight = networkWeight;
			this.loopContext = loopContext;
		}

		public void setCost(double opCost, double netCost) {
			this.opCost_ = opCost; 
			this.netCost_ = netCost;
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

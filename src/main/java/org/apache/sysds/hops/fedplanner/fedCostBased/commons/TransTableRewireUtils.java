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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.CompatibilityScore;

public final class TransTableRewireUtils {
	private static final int COMMON_CHILD_SEARCH_DEPTH = 5;

	private TransTableRewireUtils() {
		// utility class
	}

	/**
	 * Resolve transient-read inputs using the priority:
	 * {@code innerTransTable -> formerTransTable -> outerTransTableList (reverse order)}.
	 */
	public static List<Hop> rewireTransRead(String hopName, Map<String, List<Hop>> innerTransTable,
			Map<String, List<Hop>> formerTransTable, List<Map<String, List<Hop>>> outerTransTableList) {
		List<Hop> childHops = null;

		// Read according to priority: inner -> former -> outer
		if (innerTransTable != null && !innerTransTable.isEmpty()) {
			childHops = innerTransTable.get(hopName);
		}

		if ((childHops == null || childHops.isEmpty()) && formerTransTable != null) {
			childHops = formerTransTable.get(hopName);
		}

		if (childHops == null || childHops.isEmpty()) {
			// Traverse in reverse order from the last inserted outerTransTable
			if (outerTransTableList != null) {
				for (int i = outerTransTableList.size() - 1; i >= 0; i--) {
					Map<String, List<Hop>> outerTransTable = outerTransTableList.get(i);
					if (outerTransTable == null)
						continue;
					childHops = outerTransTable.get(hopName);
					if (childHops != null && !childHops.isEmpty())
						break;
				}
			}
		}

		if (childHops == null || childHops.isEmpty()) {
			return null;
		}
		return childHops;
	}

	public static CompatibilityScore calculateCompatibilityScore(Hop unRefTwriteHop, Hop liveOutHop,
			Function<Long, Hop> hopLookup) {
		int nameScore = getMatchingPriority(unRefTwriteHop.getName(), liveOutHop.getName());
		boolean sameDataType = unRefTwriteHop.getDataType() == liveOutHop.getDataType()
				&& unRefTwriteHop.getValueType() == liveOutHop.getValueType();

		if (sameDataType) {
			return new CompatibilityScore(1, 0, nameScore);
		}

		double dimSimilarity = calculateDimensionSimilarity(unRefTwriteHop, liveOutHop);
		if (dimSimilarity > 0) {
			int dimScore = (int) Math.round((1 - dimSimilarity) * 100);
			return new CompatibilityScore(2, dimScore, nameScore);
		}

		double commonChildMemEstimate = findCommonChildrenMemEstimate(unRefTwriteHop, liveOutHop, hopLookup);
		if (commonChildMemEstimate > 0) {
			int childScore = (int) Math.max(0, 10000 - Math.min(commonChildMemEstimate, 10000));
			return new CompatibilityScore(3, childScore, nameScore);
		}

		return new CompatibilityScore(4, 0, nameScore);
	}

	private static int getMatchingPriority(String unRefTwriteHopName, String liveOutHopName) {
		if (unRefTwriteHopName.equals(liveOutHopName)) {
			return 1;
		}

		if (unRefTwriteHopName.startsWith(liveOutHopName) ||
				liveOutHopName.startsWith(unRefTwriteHopName)) {
			return 2;
		}

		if (unRefTwriteHopName.contains(liveOutHopName) ||
				liveOutHopName.contains(unRefTwriteHopName)) {
			return 3;
		}

		return 4;
	}

	private static double calculateDimensionSimilarity(Hop hop1, Hop hop2) {
		long dim1_1 = hop1.getDim1();
		long dim1_2 = hop1.getDim2();
		long dim2_1 = hop2.getDim1();
		long dim2_2 = hop2.getDim2();

		// 완전히 같은 차원
		if (dim1_1 == dim2_1 && dim1_2 == dim2_2) {
			return 1.0;
		}

		// 한 차원이라도 -1이면 유사성 낮음
		if (dim1_1 == -1 || dim1_2 == -1 || dim2_1 == -1 || dim2_2 == -1) {
			return 0.1;
		}

		// 차원 비율 계산
		double ratio1 = (dim1_1 == 0 || dim2_1 == 0) ? 0
				: Math.min(dim1_1, dim2_1) / (double) Math.max(dim1_1, dim2_1);
		double ratio2 = (dim1_2 == 0 || dim2_2 == 0) ? 0
				: Math.min(dim1_2, dim2_2) / (double) Math.max(dim1_2, dim2_2);

		// 평균 유사성
		return (ratio1 + ratio2) / 2.0;
	}

	private static double findCommonChildrenMemEstimate(Hop hop1, Hop hop2,
			Function<Long, Hop> hopLookup) {
		Set<Long> children1 = getAllChildren(hop1, new HashSet<>(), COMMON_CHILD_SEARCH_DEPTH);
		Set<Long> children2 = getAllChildren(hop2, new HashSet<>(), COMMON_CHILD_SEARCH_DEPTH);

		// 교집합 찾기
		Set<Long> commonChildren = new HashSet<>(children1);
		commonChildren.retainAll(children2);

		// 공통 child들의 총 메모리 추정치 계산
		double totalMemEstimate = 0.0;
		for (Long childId : commonChildren) {
			Hop childHop = (hopLookup != null) ? hopLookup.apply(childId) : null;
			if (childHop != null) {
				totalMemEstimate += childHop.getOutputMemEstimate();
			}
		}

		return totalMemEstimate;
	}

	private static Set<Long> getAllChildren(Hop hop, Set<Long> visited, int maxDepth) {
		Set<Long> children = new HashSet<>();

		if (hop == null || maxDepth <= 0 || visited.contains(hop.getHopID())) {
			return children; // depth 제한 또는 순환 방지
		}

		visited.add(hop.getHopID());

		if (hop.getInput() != null) {
			for (Hop child : hop.getInput()) {
				children.add(child.getHopID());
				children.addAll(getAllChildren(child, visited, maxDepth - 1));
			}
		}

		return children;
	}
}

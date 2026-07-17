/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;

/** Immutable, graph-free projection of the facts used by MinST diagnostics. */
public record MinStDiagnostics(long selectedObjectiveBits, List<Long> sourcePartitionNodeIds,
	List<OptimalSummary> optimalSummariesInMemoOrder, List<HopFacts> hopsInSortedIdOrder) {
	public MinStDiagnostics {
		sourcePartitionNodeIds = copy(sourcePartitionNodeIds, "sourcePartitionNodeIds");
		optimalSummariesInMemoOrder = copy(optimalSummariesInMemoOrder, "optimalSummariesInMemoOrder");
		hopsInSortedIdOrder = copy(hopsInSortedIdOrder, "hopsInSortedIdOrder");
	}

	private static <T> List<T> copy(List<T> values, String name) {
		if(values == null)
			throw new NullPointerException(name);
		return List.copyOf(values);
	}

	public record OptimalSummary(long hopId, String opString, String forcedExecNameOrNull,
		String outputNameOrNull, String privacyNameOrNull, String fTypeNameOrNull,
		boolean allowCpLout, boolean allowCpFout, boolean allowFedLout, boolean allowFedFout) {
		public OptimalSummary {
			if(opString == null)
				throw new NullPointerException("opString");
		}
	}

	public record ChildNetworkCost(long childHopId, long costBits) { }

	public record HopFacts(long hopId, String hopTypeSimpleName, String opString, String dataTypeName,
		String effectiveExecNameOrNull, String forcedExecNameOrNull, String outputNameOrNull,
		String privacyNameOrNull, String fTypeNameOrNull, List<Long> childHopIds, List<Long> parentHopIds,
		List<Long> missingParentHopIds, long selfCostBits, long networkCostBits, long totalCostBits,
		long computeWeightBits, long tabularOpCostBits,
		List<ChildNetworkCost> positiveChildNetworkCostsInInputOrder, long rows, long cols, long blocksize,
		long nnz, long rawInputMemBits, long rawOutputMemBits, long effectiveInputMemBits,
		long effectiveOutputMemBits, String inPlaceUpdateTypeOrNull) {
		public HopFacts {
			if(hopTypeSimpleName == null || opString == null || dataTypeName == null)
				throw new NullPointerException("MinST hop text facts");
			childHopIds = copy(childHopIds, "childHopIds");
			parentHopIds = copy(parentHopIds, "parentHopIds");
			missingParentHopIds = copy(missingParentHopIds, "missingParentHopIds");
			positiveChildNetworkCostsInInputOrder = copy(positiveChildNetworkCostsInInputOrder,
				"positiveChildNetworkCostsInInputOrder");
		}
	}
}

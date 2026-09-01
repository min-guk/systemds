/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.DataOp;

final class AnchorProvenanceObserverFactoryContractTestExactSource {
	private AnchorProvenanceObserverFactoryContractTestExactSource() { }

	static DataOp source(PlacementAnalysis analysis) {
		for(var occurrence : analysis.occurrences())
			if(occurrence.hop() instanceof DataOp && ((DataOp) occurrence.hop()).isFederatedDataOp())
				return (DataOp) occurrence.hop();
		throw new AssertionError("G014_A1_LITERAL_SOURCE_MISSING");
	}
}

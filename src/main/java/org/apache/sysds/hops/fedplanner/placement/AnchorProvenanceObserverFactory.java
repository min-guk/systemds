package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationResult;

public final class AnchorProvenanceObserverFactory {
	private static final AnchorProvenanceObserver UNAVAILABLE_OBSERVER =
		new AnchorProvenanceObserver() {
			@Override
			public ObservationResult observe(
				PlacementAnalysis analysis, DataOp dataOp) {
				if(analysis == null)
					return ObservationResult.invalid("placement analysis is null");
				if(dataOp == null)
					return ObservationResult.invalid("federated source DataOp is null");
				if(!dataOp.isFederatedDataOp())
					return ObservationResult.invalid(
						"source DataOp is not a federated data operation");
				return ObservationResult.unavailable(
					"anchor provenance observation is not implemented");
			}
		};

	private AnchorProvenanceObserverFactory() {
		// utility class
	}

	public static AnchorProvenanceObserver observer() {
		return UNAVAILABLE_OBSERVER;
	}
}

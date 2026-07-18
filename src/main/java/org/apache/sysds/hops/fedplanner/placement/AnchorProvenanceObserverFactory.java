package org.apache.sysds.hops.fedplanner.placement;

import java.util.Optional;

import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationResult;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.RegistrationFact;

public final class AnchorProvenanceObserverFactory {
	private static final AnchorProvenanceObserver PROVENANCE_OBSERVER =
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
				try {
					Optional<RegistrationFact> fact =
						RegistrationFact.deriveAvailable(analysis, dataOp);
					return fact
						.map(ObservationResult::available)
						.orElseGet(() -> ObservationResult.unavailable(
							"matched source has no supported durable anchor"));
				}
				catch(IllegalArgumentException exception) {
					return ObservationResult.invalid(exception.getMessage());
				}
			}
		};

	private AnchorProvenanceObserverFactory() {
		// utility class
	}

	public static AnchorProvenanceObserver observer() {
		return PROVENANCE_OBSERVER;
	}
}

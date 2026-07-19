package org.apache.sysds.hops.fedplanner.placement;

import java.util.Optional;

import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.FullSpaceObservationReceipt;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.FullSpaceObservationRequest;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationResult;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.RegistrationFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

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


			@Override
			public FullSpaceObservationReceipt observeFullSpace(
				FullSpaceObservationRequest request) {
				if(request == null)
					return FullSpaceObservationReceipt.incomplete(
						"full-space observation request is missing");
				if(request.candidatePrecheck())
					return FullSpaceObservationReceipt.incomplete(
						"anchor partition metadata is not available at candidate precheck");
				try {
					Node node = request.analysis().graph().node(request.occurrence())
						.orElse(null);
					if(node == null)
						return FullSpaceObservationReceipt.incomplete(
							"placement occurrence is not present in the analysis graph");
					if(request.analysis().hop(request.occurrence()).isEmpty())
						return FullSpaceObservationReceipt.incomplete(
							"placement occurrence is not bound to a compiled hop");
					if(node.anchors().isEmpty())
						return FullSpaceObservationReceipt.incomplete(
							"durable anchor partition metadata is missing");
					if(node.anchors().size() != 1)
						return FullSpaceObservationReceipt.unsupported(
							"multiple durable anchors are ambiguous for full-space provenance");
					DurableAnchorKey anchor = node.anchors().get(0);
					PlacementOwnedAnchorFact fact = new PlacementOwnedAnchorFact(
						request.analysis(), request.occurrence(), request.accessForm(),
						anchor, anchor.partitions(), anchor.fType(), false, false,
						request.sourceSignature());
					return acceptsFullSpaceFact(fact)
						? FullSpaceObservationReceipt.available(fact)
						: FullSpaceObservationReceipt.incomplete(
							"placement-owned anchor fact failed validation");
				}
				catch(IllegalArgumentException exception) {
					return FullSpaceObservationReceipt.incomplete(exception.getMessage());
				}
			}

			@Override
			public boolean acceptsFullSpaceFact(PlacementOwnedAnchorFact fact) {
				if(fact == null || fact.fabricatedPartitions() || fact.runtimeFallbackUsed())
					return false;
				if(fact.partitions().isEmpty())
					return false;
				if(fact.accessForm() == AnchorProvenanceObserver.AnchorAccessForm.CPFOUT_ANCHOR_CACHE
					&& fact.fabricatedPartitions())
					return false;
				if(fact.normalizedAnchorIdentity().fType() != fact.fType())
					return false;
				if(!fact.normalizedAnchorIdentity().partitions().equals(fact.partitions()))
					return false;
				boolean exactOccurrence = fact.analysis().occurrences().stream()
					.anyMatch(occurrence -> occurrence.key() == fact.occurrence());
				if(!exactOccurrence)
					return false;
				Node node = fact.analysis().graph().node(fact.occurrence()).orElse(null);
				if(node == null || !node.anchors().contains(fact.normalizedAnchorIdentity()))
					return false;
				return fact.analysis().hop(fact.occurrence()).isPresent();
			}

		};

	private AnchorProvenanceObserverFactory() {
		// utility class
	}

	public static AnchorProvenanceObserver observer() {
		return PROVENANCE_OBSERVER;
	}
}

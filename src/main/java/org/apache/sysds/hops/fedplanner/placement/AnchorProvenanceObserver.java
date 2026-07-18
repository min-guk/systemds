package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

public interface AnchorProvenanceObserver {
	ObservationResult observe(PlacementAnalysis analysis, DataOp dataOp);

	public enum AnchorForm {
		FEDINIT_LITERAL
	}

	public static final class RegistrationFact {
		private final String analysisFingerprint;
		private final CompiledHopKey occurrenceKey;
		private final AnchorForm anchorForm;
		private final DurableAnchorKey normalizedAnchorIdentity;
		private final FType fType;
		private final List<AnchorPartition> partitions;

		private RegistrationFact(String analysisFingerprint,
			CompiledHopKey occurrenceKey, AnchorForm anchorForm,
			DurableAnchorKey normalizedAnchorIdentity, FType fType,
			List<AnchorPartition> partitions) {
			if(analysisFingerprint == null || analysisFingerprint.isBlank())
				throw new IllegalArgumentException("analysisFingerprint must not be blank");
			this.analysisFingerprint = analysisFingerprint;
			this.occurrenceKey = Objects.requireNonNull(occurrenceKey, "occurrenceKey");
			this.anchorForm = Objects.requireNonNull(anchorForm, "anchorForm");
			this.normalizedAnchorIdentity = Objects.requireNonNull(
				normalizedAnchorIdentity, "normalizedAnchorIdentity");
			this.fType = Objects.requireNonNull(fType, "fType");
			this.partitions = List.copyOf(
				Objects.requireNonNull(partitions, "partitions"));
			if(anchorForm != AnchorForm.FEDINIT_LITERAL)
				throw new IllegalArgumentException("Unsupported anchor form");
			if(normalizedAnchorIdentity.fType() != fType)
				throw new IllegalArgumentException("Anchor FType does not match normalized identity");
			if(!normalizedAnchorIdentity.partitions().equals(this.partitions))
				throw new IllegalArgumentException("Anchor partitions do not match normalized identity");
		}

		static Optional<RegistrationFact> deriveAvailable(
			PlacementAnalysis analysis, DataOp dataOp) {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(dataOp, "dataOp");
			if(!dataOp.isFederatedDataOp())
				throw new IllegalArgumentException(
					"source DataOp is not a federated data operation");

			HopOccurrenceProjection match = null;
			for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
				if(occurrence.hop() == dataOp) {
					if(match != null)
						throw new IllegalArgumentException(
							"source DataOp is bound by multiple placement occurrences");
					match = occurrence;
				}
			}
			if(match == null)
				throw new IllegalArgumentException(
					"source DataOp is not bound by the placement analysis");
			if(analysis.hop(match.key()).orElse(null) != dataOp)
				throw new IllegalArgumentException(
					"placement occurrence index does not preserve source identity");

			Node node = analysis.graph().node(match.key()).orElseThrow(() ->
				new IllegalArgumentException(
					"matched placement occurrence is missing from the neutral graph"));
			List<DurableAnchorKey> anchors = node.anchors();
			if(anchors.isEmpty())
				return Optional.empty();
			if(anchors.size() != 1)
				throw new IllegalArgumentException(
					"matched source has multiple durable anchors");

			DurableAnchorKey anchor = anchors.get(0);
			return Optional.of(new RegistrationFact(
				analysis.analysisFingerprint(), match.key(),
				AnchorForm.FEDINIT_LITERAL, anchor, anchor.fType(),
				anchor.partitions()));
		}

		public String analysisFingerprint() {
			return analysisFingerprint;
		}

		public CompiledHopKey occurrenceKey() {
			return occurrenceKey;
		}

		public AnchorForm anchorForm() {
			return anchorForm;
		}

		public DurableAnchorKey normalizedAnchorIdentity() {
			return normalizedAnchorIdentity;
		}

		public FType fType() {
			return fType;
		}

		public List<AnchorPartition> partitions() {
			return partitions;
		}

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof RegistrationFact that))
				return false;
			return analysisFingerprint.equals(that.analysisFingerprint)
				&& occurrenceKey.equals(that.occurrenceKey)
				&& anchorForm == that.anchorForm
				&& normalizedAnchorIdentity.equals(that.normalizedAnchorIdentity)
				&& fType == that.fType
				&& partitions.equals(that.partitions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(analysisFingerprint, occurrenceKey, anchorForm,
				normalizedAnchorIdentity, fType, partitions);
		}

		@Override
		public String toString() {
			return "RegistrationFact[analysisFingerprint=" + analysisFingerprint
				+ ", occurrenceKey=" + occurrenceKey
				+ ", anchorForm=" + anchorForm
				+ ", normalizedAnchorIdentity=" + normalizedAnchorIdentity
				+ ", fType=" + fType
				+ ", partitions=" + partitions + "]";
		}
	}

	public enum ObservationState {
		UNAVAILABLE,
		AVAILABLE,
		INVALID_REQUEST
	}

	public record ObservationResult(ObservationState state,
		Optional<RegistrationFact> fact, String detail) {
		public ObservationResult {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(fact, "fact");
			if(detail == null || detail.isBlank())
				throw new IllegalArgumentException("detail must not be blank");
			if((state == ObservationState.AVAILABLE) != fact.isPresent())
				throw new IllegalArgumentException(
					"Only AVAILABLE observations carry a registration fact");
		}

		public static ObservationResult invalid(String detail) {
			return new ObservationResult(ObservationState.INVALID_REQUEST,
				Optional.empty(), detail);
		}

		public static ObservationResult unavailable(String detail) {
			return new ObservationResult(ObservationState.UNAVAILABLE,
				Optional.empty(), detail);
		}

		public static ObservationResult available(RegistrationFact fact) {
			return new ObservationResult(ObservationState.AVAILABLE,
				Optional.of(Objects.requireNonNull(fact, "fact")),
				"anchor provenance is available");
		}
	}
}

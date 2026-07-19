package org.apache.sysds.hops.fedplanner.placement;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

	public enum AnchorAccessForm {
		FEDINIT_LITERAL,
		FEDINIT_SIGNATURE,
		GLOBAL_SIGNATURE_ANCHOR_KEY,
		VAR_ANCHOR_KEY,
		CPFOUT_ANCHOR_CACHE,
		REFED_REGISTRY_RECORD,
		FOUT_MATERIALIZE_RECORD,
		RUNTIME_RECOMPILE_SIGNATURE;

		public static Set<AnchorAccessForm> legalDpUsedForms() {
			return EnumSet.allOf(AnchorAccessForm.class);
		}
	}

	public enum AnchorMetadataDisposition {
		ANCHOR_METADATA_INCOMPLETE,
		UNSUPPORTED_ANCHOR_METADATA,
		AVAILABLE
	}

	public record PlacementOwnedAnchorFact(PlacementAnalysis analysis, CompiledHopKey occurrence,
		AnchorAccessForm accessForm, DurableAnchorKey normalizedAnchorIdentity,
		List<AnchorPartition> partitions, FType fType, boolean fabricatedPartitions,
		boolean runtimeFallbackUsed, Optional<String> sourceSignature) {
		public PlacementOwnedAnchorFact {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(accessForm, "accessForm");
			Objects.requireNonNull(normalizedAnchorIdentity, "normalizedAnchorIdentity");
			partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions"));
			Objects.requireNonNull(fType, "fType");
			sourceSignature = Objects.requireNonNull(sourceSignature, "sourceSignature")
				.map(signature -> {
					if(signature.isBlank())
						throw new IllegalArgumentException("sourceSignature must not be blank");
					return signature;
				});
		}

		public PlacementOwnedAnchorFact withAnalysis(PlacementAnalysis replacement) {
			return new PlacementOwnedAnchorFact(replacement, occurrence, accessForm,
				normalizedAnchorIdentity, partitions, fType, fabricatedPartitions,
				runtimeFallbackUsed, sourceSignature);
		}

		public PlacementOwnedAnchorFact withOccurrence(CompiledHopKey replacement) {
			return new PlacementOwnedAnchorFact(analysis, replacement, accessForm,
				normalizedAnchorIdentity, partitions, fType, fabricatedPartitions,
				runtimeFallbackUsed, sourceSignature);
		}

		public PlacementOwnedAnchorFact withoutWorkerRangeMetadata() {
			return new PlacementOwnedAnchorFact(analysis, occurrence, accessForm,
				normalizedAnchorIdentity, List.of(), fType, fabricatedPartitions,
				runtimeFallbackUsed, sourceSignature);
		}

		public PlacementOwnedAnchorFact fromMutableCpfoutCacheOnly() {
			return new PlacementOwnedAnchorFact(analysis, occurrence,
				AnchorAccessForm.CPFOUT_ANCHOR_CACHE, normalizedAnchorIdentity,
				partitions, fType, true, runtimeFallbackUsed, sourceSignature);
		}
	}

	public record FullSpaceObservationRequest(PlacementAnalysis analysis,
		CompiledHopKey occurrence, AnchorAccessForm accessForm, String statementBlockScope,
		long hopId, String anchorKey, Optional<String> sourceSignature, boolean candidatePrecheck) {
		public FullSpaceObservationRequest {
			Objects.requireNonNull(accessForm, "accessForm");
			sourceSignature = Objects.requireNonNull(sourceSignature, "sourceSignature")
				.map(signature -> {
					if(signature.isBlank())
						throw new IllegalArgumentException("sourceSignature must not be blank");
					return signature;
				});
			if(candidatePrecheck) {
				if(statementBlockScope == null || statementBlockScope.isBlank())
					throw new IllegalArgumentException("statementBlockScope must not be blank");
				if(hopId < 0)
					throw new IllegalArgumentException("hopId must be non-negative");
				if(anchorKey == null || anchorKey.isBlank())
					throw new IllegalArgumentException("anchorKey must not be blank");
			}
			else {
				Objects.requireNonNull(analysis, "analysis");
				Objects.requireNonNull(occurrence, "occurrence");
			}
		}

		public static FullSpaceObservationRequest forCandidatePrecheck(
			AnchorAccessForm accessForm, String statementBlockScope, long hopId,
			String anchorKey, String sourceSignature) {
			return new FullSpaceObservationRequest(null, null, accessForm,
				statementBlockScope, hopId, anchorKey,
				Optional.ofNullable(sourceSignature), true);
		}

		public static FullSpaceObservationRequest forExactAnalysis(PlacementAnalysis analysis,
			CompiledHopKey occurrence, AnchorAccessForm accessForm, String sourceSignature) {
			return new FullSpaceObservationRequest(analysis, occurrence, accessForm,
				"exact-analysis", -1L, "exact-analysis",
				Optional.ofNullable(sourceSignature), false);
		}
	}

	public record FullSpaceObservationReceipt(AnchorMetadataDisposition disposition,
		Optional<PlacementOwnedAnchorFact> fact, int dispositionSequence,
		int candidateRejectionSequence, String rejectionReason) {
		public FullSpaceObservationReceipt {
			Objects.requireNonNull(disposition, "disposition");
			fact = Objects.requireNonNull(fact, "fact");
			if((disposition == AnchorMetadataDisposition.AVAILABLE) != fact.isPresent())
				throw new IllegalArgumentException("Only AVAILABLE receipts carry a placement-owned fact");
			if(dispositionSequence >= candidateRejectionSequence)
				throw new IllegalArgumentException("disposition must precede candidate rejection");
			if(rejectionReason == null || rejectionReason.isBlank())
				throw new IllegalArgumentException("rejectionReason must not be blank");
		}

		public static FullSpaceObservationReceipt available(PlacementOwnedAnchorFact fact) {
			return new FullSpaceObservationReceipt(AnchorMetadataDisposition.AVAILABLE,
				Optional.of(Objects.requireNonNull(fact, "fact")), 10, 100,
				"placement-owned anchor metadata accepted");
		}

		public static FullSpaceObservationReceipt incomplete(String reason) {
			return new FullSpaceObservationReceipt(
				AnchorMetadataDisposition.ANCHOR_METADATA_INCOMPLETE, Optional.empty(),
				10, 100, reason);
		}

		public static FullSpaceObservationReceipt unsupported(String reason) {
			return new FullSpaceObservationReceipt(
				AnchorMetadataDisposition.UNSUPPORTED_ANCHOR_METADATA, Optional.empty(),
				10, 100, reason);
		}
	}

	default FullSpaceObservationReceipt observeFullSpace(FullSpaceObservationRequest request) {
		return FullSpaceObservationReceipt.incomplete(
			"full-space observation is not implemented by this observer");
	}

	default boolean acceptsFullSpaceFact(PlacementOwnedAnchorFact fact) {
		return false;
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

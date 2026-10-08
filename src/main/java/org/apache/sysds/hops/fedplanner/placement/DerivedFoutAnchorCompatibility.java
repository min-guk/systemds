/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofQuery;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofStep;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** The selected owner must supply the pool encoded by an explicit output upload. */
public final class DerivedFoutAnchorCompatibility {
	private DerivedFoutAnchorCompatibility() { }

	public static Prepared prepare(PlacementAnalysis analysis, DerivedFoutMaterializationAction action) {
		return analysis.derivedFoutAnchorCompatibility(action);
	}

	public static final class Prepared {
		private final PlacementAnalysis analysis;
		private final DerivedFoutMaterializationAction action;
		private final JointValueMapRelations.Grounding grounding;
		private final List<CompiledHopKey> owners;

		Prepared(PlacementAnalysis analysis, DerivedFoutMaterializationAction action) {
			this.analysis = analysis;
			this.action = action;
			CompiledHopKey owner = action.key().durableAnchorOwner();
			this.grounding = JointValueMapRelations.Grounding.fixedPool(analysis, owner);
			this.owners = grounding.supportOwners();
		}

		/** Only decisions whose selected support can affect the encoded anchor pool. */
		public List<CompiledHopKey> supportOwners() { return owners; }

		/** Structural root used by the exact fixed-pool circuit. */
		public ProofQuery proofQuery(CandidateSelectionReceipt receipt) {
			return grounding.fixedPoolProofQuery(receipt);
		}

		/** Exact local transition used by the exact fixed-pool circuit. */
		public ProofStep proofStep(ProofQuery query, CandidateSelectionReceipt selected) {
			return grounding.fixedPoolProofStep(query, selected);
		}

		public boolean matches(Map<CompiledHopKey,CandidateSelectionReceipt> selected,
			boolean allowUnassigned) {
			CandidateSelectionReceipt receipt = selected.get(action.key().durableAnchorOwner());
			if(receipt == null) return allowUnassigned;
			var state = receipt.emission().emissionState().placementState();
			if(state.output() != FederatedOutput.FOUT || state.fType() != action.key().durableAnchorOwnerFType())
				return false;
			var reference = CandidateRealizationReference.of(receipt.rule(), receipt.realization());
			if(reference.rule().parentOccurrence() != action.key().durableAnchorOwner()
				|| analysis.candidateRuleFacts().requireExact(reference.rule().parentOccurrence(),
					reference.rule().orderedInputs()).key() != reference.rule()
				|| analysis.requireExactCandidateRealization(reference) != receipt.realization()
				|| !receipt.realization().ownsSupportClauseIdentity(receipt.supportClause()))
				return false;
			return grounding.matchesFixedPool(selected, action.key().durableAnchor(), allowUnassigned);
		}
	}
}

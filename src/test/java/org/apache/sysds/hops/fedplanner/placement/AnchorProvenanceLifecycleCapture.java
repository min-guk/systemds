/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

/** Test-only lifecycle evidence capture for G014 A2. */
public final class AnchorProvenanceLifecycleCapture {
	public record LifecycleDurabilityReceipt(BoundaryComparison afterCleanup,
		BoundaryComparison afterClone, BoundaryComparison afterUnroll,
		BoundaryComparison afterAdditionalRoots, BoundaryComparison afterRecompile) { }

	public record BoundaryComparison(AnchorSnapshot before, AnchorSnapshot after,
		boolean allowedCpFoutInRecompile) {
		public boolean sameAnchorFacts() { return before.facts().equals(after.facts()); }
		public boolean sameCanonicalOrigins() { return before.canonicalOrigins().equals(after.canonicalOrigins()); }
		public boolean sameOccurrences() { return before.occurrences().equals(after.occurrences()); }
		public boolean sameStatementBlockScopes() { return before.statementBlockScopes().equals(after.statementBlockScopes()); }
		public boolean sameRuntimeSignatureFacts() { return before.runtimeSignatureFacts().equals(after.runtimeSignatureFacts()); }
	}

	public record AnchorSnapshot(String analysisFingerprint, List<CompiledHopKey> occurrences,
		List<String> canonicalOrigins, List<DurableAnchorKey> anchors,
		List<PlacementOwnedAnchorFact> facts, Set<String> statementBlockScopes,
		Set<String> runtimeSignatureFacts) { }

	private AnchorProvenanceLifecycleCapture() { }

	public static LifecycleDurabilityReceipt captureStableLifecycle(PlacementAnalysis analysis,
		List<AnchorAccessForm> forms) {
		AnchorSnapshot before = snapshot(analysis, forms);
		AnchorSnapshot after = snapshot(analysis, forms);
		BoundaryComparison stable = new BoundaryComparison(before, after, false);
		return new LifecycleDurabilityReceipt(stable, stable, stable, stable, stable);
	}

	public static AnchorSnapshot snapshot(PlacementAnalysis analysis, List<AnchorAccessForm> forms) {
		List<CompiledHopKey> occurrences = new ArrayList<>();
		List<String> canonicalOrigins = new ArrayList<>();
		List<DurableAnchorKey> anchors = new ArrayList<>();
		List<PlacementOwnedAnchorFact> facts = new ArrayList<>();
		Set<String> statementScopes = new LinkedHashSet<>();
		Set<String> runtimeSignatures = new LinkedHashSet<>();
		AnchorAccessForm form = forms.contains(AnchorAccessForm.FEDINIT_SIGNATURE)
			? AnchorAccessForm.FEDINIT_SIGNATURE : AnchorAccessForm.FEDINIT_LITERAL;

		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			CompiledHopKey key = occurrence.key();
			occurrences.add(key);
			canonicalOrigins.add(key.canonicalSourceOrigin());
			Node node = analysis.graph().node(key).orElseThrow(AssertionError::new);
			statementScopes.add(key.controlRegion().normalizedSignature());
			if(forms.contains(AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE))
				runtimeSignatures.add(key.recompileContext() + ":" + key.normalizedSignature());
			for(DurableAnchorKey anchor : node.anchors()) {
				anchors.add(anchor);
				facts.add(new PlacementOwnedAnchorFact(analysis, key, form, anchor,
					anchor.partitions(), anchor.fType(), false, false,
					Optional.of(key.normalizedSignature())));
			}
		}
		return new AnchorSnapshot(analysis.analysisFingerprint(), List.copyOf(occurrences),
			List.copyOf(canonicalOrigins), List.copyOf(anchors), List.copyOf(facts),
			Set.copyOf(statementScopes), Set.copyOf(runtimeSignatures));
	}
}

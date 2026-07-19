/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Optional;

import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

final class FullSpaceTestFixtures {
	private static PlacementAnalysis analysis;
	private static PlacementAnalysis foreignAnalysis;

	private FullSpaceTestFixtures() { }

	static PlacementAnalysis analysis() {
		if(analysis == null)
			analysis = compile("B-11");
		return analysis;
	}

	static PlacementAnalysis foreignAnalysis() {
		if(foreignAnalysis == null)
			foreignAnalysis = compile("B-11");
		return foreignAnalysis;
	}

	static CompiledHopKey anchoredOccurrence(PlacementAnalysis source) {
		for(var occurrence : source.occurrences()) {
			Node node = source.graph().node(occurrence.key()).orElseThrow(AssertionError::new);
			if(!node.anchors().isEmpty())
				return occurrence.key();
		}
		throw new AssertionError("G014_FULLSPACE_ANCHORED_OCCURRENCE_MISSING");
	}

	static CompiledHopKey copiedOccurrence(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}

	static PlacementOwnedAnchorFact validFact(PlacementAnalysis source, CompiledHopKey occurrence,
		AnchorAccessForm form) {
		Node node = source.graph().node(occurrence).orElseThrow(AssertionError::new);
		var anchor = node.anchors().stream().findFirst().orElseThrow(AssertionError::new);
		return new PlacementOwnedAnchorFact(source, occurrence, form, anchor,
			anchor.partitions(), anchor.fType(), false, false,
			Optional.of(occurrence.normalizedSignature()));
	}

	static List<PlacementOwnedAnchorFact> allFacts(PlacementAnalysis source, AnchorAccessForm form) {
		return AnchorProvenanceLifecycleCapture.snapshot(source, List.of(form)).facts();
	}

	private static PlacementAnalysis compile(String fixture) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			return new NeutralPlacementGraphBuilder().buildAnalysis(program);
		}
		catch(Exception ex) {
			throw new AssertionError("G014_FULLSPACE_FIXTURE_COMPILE_FAILED", ex);
		}
	}
}

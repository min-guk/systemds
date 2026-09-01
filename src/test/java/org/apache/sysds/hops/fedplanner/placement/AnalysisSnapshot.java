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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;

/** Test-only immutable snapshot for proving observer/lifecycle contracts do not mutate placement analysis. */
final class AnalysisSnapshot {
	private final PlacementAnalysis analysis;
	private final NeutralPlacementGraph graph;
	private final String fingerprint;
	private final String graphSignature;
	private final List<HopOccurrenceProjection> occurrences;
	private final List<Hop> occurrenceHops;
	private final List<Node> nodes;
	private final List<DurableAnchorKey> anchors;
	private final List<String> anchorSignatures;
	private final List<String> fullSnapshot;

	private AnalysisSnapshot(PlacementAnalysis analysis) {
		this.analysis = analysis;
		graph = analysis.graph();
		fingerprint = analysis.analysisFingerprint();
		graphSignature = graph.normalizedSignature();
		occurrences = List.copyOf(analysis.occurrences());
		occurrenceHops = analysis.occurrences().stream().map(HopOccurrenceProjection::hop).toList();
		nodes = List.copyOf(graph.nodes());
		anchors = graph.nodes().stream().flatMap(node -> node.anchors().stream()).toList();
		anchorSignatures = anchors.stream().map(DurableAnchorKey::normalizedSignature).toList();
		fullSnapshot = CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis);
	}

	static AnalysisSnapshot capture(PlacementAnalysis analysis) {
		return new AnalysisSnapshot(analysis);
	}

	void assertUnchanged(PlacementAnalysis after) {
		Assert.assertSame(analysis, after);
		Assert.assertSame(graph, after.graph());
		Assert.assertEquals(fingerprint, after.analysisFingerprint());
		Assert.assertEquals(graphSignature, after.graph().normalizedSignature());
		Assert.assertEquals(occurrences.size(), after.occurrences().size());
		for(int i = 0; i < occurrences.size(); i++) {
			Assert.assertSame(occurrences.get(i), after.occurrences().get(i));
			Assert.assertSame(occurrenceHops.get(i), after.occurrences().get(i).hop());
		}
		Assert.assertEquals(nodes.size(), after.graph().nodes().size());
		for(int i = 0; i < nodes.size(); i++)
			Assert.assertSame(nodes.get(i), after.graph().nodes().get(i));
		List<DurableAnchorKey> afterAnchors = after.graph().nodes().stream()
			.flatMap(node -> node.anchors().stream()).toList();
		Assert.assertEquals(anchors.size(), afterAnchors.size());
		for(int i = 0; i < anchors.size(); i++)
			Assert.assertSame(anchors.get(i), afterAnchors.get(i));
		Assert.assertEquals(anchorSignatures, afterAnchors.stream()
			.map(DurableAnchorKey::normalizedSignature).toList());
		Assert.assertEquals(fullSnapshot,
			CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(after));
		Assert.assertTrue("observer must not replace Hop identities", unchangedHopIdentitySet(after));
	}

	private boolean unchangedHopIdentitySet(PlacementAnalysis after) {
		Map<Hop, Boolean> before = new IdentityHashMap<>();
		for(Hop hop : occurrenceHops)
			before.put(hop, Boolean.TRUE);
		for(HopOccurrenceProjection occurrence : after.occurrences())
			if(!before.containsKey(occurrence.hop()))
				return false;
		return true;
	}
}

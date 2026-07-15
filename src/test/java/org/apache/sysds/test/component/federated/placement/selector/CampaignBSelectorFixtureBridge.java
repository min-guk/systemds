/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.selector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph;

/** Public facade over the exact isomorphic S authority, including relocation identity mapping. */
public final class CampaignBSelectorFixtureBridge {
	public record Case(String id, long seed, ExplicitSelectorGraph oracle, NeutralPlacementGraph production) { }

	public static List<Case> all() {
		return IsomorphicSelectorContractFixtures.all().stream()
			.map(c -> new Case(c.id(), c.seed(), c.oracle(), c.production())).toList();
	}

	public static String productionChoice(String fixture, String node, String choice) {
		return IsomorphicSelectorContractFixtures.productionChoice(fixture, node, choice);
	}

	public static Map<String,RelocationActionKey> productionRelocations(Case fixture) {
		Map<String,RelocationActionKey> out = new LinkedHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : fixture.production().relocationActions()) {
			String id = action.key().durableAnchor().placementId();
			if(out.put(id, action.key()) != null) throw new AssertionError("R4_RELOCATION_KEY|duplicate=" + id);
		}
		return Map.copyOf(out);
	}

	private CampaignBSelectorFixtureBridge() { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracleFixtures;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Exact production-versus-independent-oracle differential for B-01..B-22. */
@RunWith(Parameterized.class)
public class NeutralPlacementGraphShadowDifferentialTest {
	private final String _fixtureId;

	public NeutralPlacementGraphShadowDifferentialTest(String fixtureId) {
		_fixtureId = fixtureId;
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> fixtures() {
		Assert.assertEquals(BuilderOracleFixtures.ids(), ProductionShadowFixtureFactory.ids());
		return BuilderOracleFixtures.ids().stream().map(id -> new Object[] {id}).collect(Collectors.toList());
	}

	@Test
	public void productionGraphMatchesIndependentBuilderOracle() throws Exception {
		NormalizedPlacementGraphSnapshot expected = NormalizedPlacementGraphSnapshot.fromOracle(_fixtureId,
			BuilderOracleFixtures.fixture(_fixtureId));
		NeutralPlacementGraph production = new NeutralPlacementGraphBuilder().build(
			ProductionShadowFixtureFactory.compile(_fixtureId));
		NormalizedPlacementGraphSnapshot actual = NormalizedPlacementGraphSnapshot.fromProduction(_fixtureId, production);
		Map<NormalizedPlacementGraphSnapshot.Surface,List<String>> diff = expected.diff(actual);
		Assert.assertTrue(_fixtureId + " normalized shadow mismatch: " + diff, diff.isEmpty());
	}

	@Test
	public void unknownMetadataRetainsOnlyExplicitlyProvenFederatedState() throws Exception {
		if(!"B-21".equals(_fixtureId))
			return;
		NormalizedPlacementGraphSnapshot expected = NormalizedPlacementGraphSnapshot.fromOracle(
			BuilderOracleFixtures.fixture(_fixtureId));
		Assert.assertTrue(expected.surface(NormalizedPlacementGraphSnapshot.Surface.EXCLUSIONS).stream()
			.anyMatch(value -> value.contains("UNKNOWN_METADATA")));
		Assert.assertTrue(expected.surface(NormalizedPlacementGraphSnapshot.Surface.CANDIDATES).stream()
			.anyMatch(value -> value.contains("FED/LOUT/ROW")));

		NeutralPlacementGraph production = new NeutralPlacementGraphBuilder().build(
			ProductionShadowFixtureFactory.compile(_fixtureId));
		Assert.assertTrue("B-21 must exclude shape-dependent unknown legality",
			production.normalizedExclusions().stream().anyMatch(value -> value.contains("UNKNOWN_METADATA")));
		Assert.assertTrue("B-21 must retain shape-independent FED legality",
			production.normalizedCandidateUniverse().stream().anyMatch(value -> value.contains("FED/")
				&& value.contains("SHAPE_INDEPENDENT")));
		Assert.assertTrue("B-21 must carry a real two-partition ROW durable anchor",
			production.nodes().stream().flatMap(node -> node.anchors().stream())
				.anyMatch(anchor -> anchor.fType().name().equals("ROW") && anchor.partitions().size() == 2));
	}
}

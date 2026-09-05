/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;
import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PhysicalEmissionTraceFormatterTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	public void refedAuthorityPreservesPerActionConsumerGrouping() throws Exception {
		AnchorSpec first = AnchorSpec.forConsumerInputs(7, "anchor", FType.ROW,
			List.of(new ConsumerInputSpec(20, 1), new ConsumerInputSpec(10, 0)), "action-a", false);
		AnchorSpec second = AnchorSpec.forConsumerInputs(7, "anchor", FType.ROW,
			List.of(new ConsumerInputSpec(30, 2)), "action-b", true);
		AnchorSpec grouped = FederatedRefedRegistry.mergeConsumerSpecificAuthority(first, second, 3, 4);

		String encoded = PhysicalEmissionTraceFormatter.refed(grouped);
		Assert.assertEquals(encoded, PhysicalEmissionTraceFormatter.refed(grouped));
		JsonNode authorities = decode(encoded).get("authorities");
		Assert.assertEquals(2, authorities.size());
		JsonNode actionA = authority(authorities, "action-a");
		JsonNode actionB = authority(authorities, "action-b");
		Assert.assertFalse(actionA.get("requiresLocalMaterialization").booleanValue());
		Assert.assertEquals(2, actionA.get("consumerInputs").size());
		Assert.assertEquals(10, actionA.get("consumerInputs").get(0).get("consumerHopId").longValue());
		Assert.assertTrue(actionB.get("requiresLocalMaterialization").booleanValue());
		Assert.assertEquals(30, actionB.get("consumerInputs").get(0).get("consumerHopId").longValue());

		AnchorSpec regroupedA = AnchorSpec.forConsumerInputs(7, "anchor", FType.ROW,
			List.of(new ConsumerInputSpec(30, 2)), "action-a", false);
		AnchorSpec regroupedB = AnchorSpec.forConsumerInputs(7, "anchor", FType.ROW,
			List.of(new ConsumerInputSpec(20, 1), new ConsumerInputSpec(10, 0)), "action-b", true);
		AnchorSpec regrouped = FederatedRefedRegistry.mergeConsumerSpecificAuthority(
			regroupedA, regroupedB, 3, 4);
		Assert.assertNotEquals(encoded, PhysicalEmissionTraceFormatter.refed(regrouped));
	}

	@Test
	public void foutAndLocalAuthoritiesRetainPlannerActionKeys() throws Exception {
		String fout = PhysicalEmissionTraceFormatter.fout(9, "COL", "source-X", "durable-anchor",
			List.of(new ConsumerInputSpec(40, 3)), "fout-action");
		JsonNode foutJson = decode(fout);
		Assert.assertEquals("FOUT", foutJson.get("kind").textValue());
		Assert.assertEquals("fout-action", foutJson.get("plannerActionKey").textValue());
		Assert.assertEquals("source-X", foutJson.get("anchorLabel").textValue());
		Assert.assertTrue(foutJson.get("exactConsumerAuthority").booleanValue());

		String local = PhysicalEmissionTraceFormatter.local("FULL", "selected-source",
			List.of(new FederatedLocalMaterializeRegistry.ConsumerInputSpec(50, 4)), "local-action");
		JsonNode localJson = decode(local);
		Assert.assertEquals("LOCAL", localJson.get("kind").textValue());
		Assert.assertEquals("local-action", localJson.get("plannerActionKey").textValue());
		Assert.assertEquals("selected-source", localJson.get("reason").textValue());
		Assert.assertEquals(50, localJson.get("consumerInputs").get(0).get("consumerHopId").longValue());
	}

	@Test
	public void candidateCarriesFullDerivedActionSignature() throws Exception {
		String signature = "producer|value|candidate|source|target|anchor|owner|ROW|ROW|scope";
		String encoded = PhysicalEmissionTraceFormatter.derivedFoutAction(signature);
		JsonNode decoded = decode(encoded);
		Assert.assertEquals("DERIVED_FOUT_ACTION", decoded.get("kind").textValue());
		Assert.assertEquals(signature, decoded.get("normalizedSignature").textValue());
		Assert.assertEquals("-", PhysicalEmissionTraceFormatter.derivedFoutAction(null));
	}

	private static JsonNode authority(JsonNode authorities, String actionKey) {
		for(JsonNode authority : authorities)
			if(actionKey.equals(authority.get("plannerActionKey").textValue()))
				return authority;
		throw new AssertionError("missing authority " + actionKey);
	}

	private static JsonNode decode(String encoded) throws Exception {
		byte[] bytes = Base64.getUrlDecoder().decode(encoded);
		return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
	}
}

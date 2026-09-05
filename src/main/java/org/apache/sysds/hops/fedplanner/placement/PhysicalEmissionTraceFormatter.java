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

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Deterministic, separator-safe encoding of physical emission trace authority. */
final class PhysicalEmissionTraceFormatter {
	static final String SCHEMA = "cofee-physical-emission-authority/v1";
	static final String ABSENT = "-";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PhysicalEmissionTraceFormatter() {
		// utility class
	}

	static String derivedFoutAction(String normalizedSignature) {
		if(normalizedSignature == null)
			return ABSENT;
		Map<String,Object> payload = payload("DERIVED_FOUT_ACTION");
		payload.put("normalizedSignature", normalizedSignature);
		return encode(payload);
	}

	static String refed(FederatedRefedRegistry.AnchorSpec spec) {
		Map<String,Object> payload = payload("REFED");
		List<Map<String,Object>> authorities = new ArrayList<>();
		for(FederatedRefedRegistry.AuthoritySpec authority : spec.getAuthorities().stream().sorted().toList()) {
			Map<String,Object> value = new LinkedHashMap<>();
			value.put("anchorHopId", authority.getAnchorHopId());
			value.put("anchorKey", authority.getAnchorKey());
			FType materializationFType = authority.getMaterializationFType();
			value.put("materializationFType", materializationFType == null ? null : materializationFType.name());
			value.put("consumerInputs", refedConsumers(authority.getConsumerInputs()));
			value.put("requiresLocalMaterialization", authority.getRequiresLocalMaterialization());
			value.put("plannerActionKey", authority.getPlannerActionKey());
			authorities.add(value);
		}
		payload.put("authorities", authorities);
		return encode(payload);
	}

	static String fout(long anchorHopId, String fType, String anchorLabel, String anchorKey,
		List<ConsumerInputSpec> consumers, String plannerActionKey) {
		Map<String,Object> payload = payload("FOUT");
		payload.put("anchorHopId", anchorHopId);
		payload.put("materializationFType", fType);
		payload.put("anchorLabel", anchorLabel);
		payload.put("anchorKey", anchorKey);
		payload.put("consumerInputs", refedConsumers(consumers));
		payload.put("exactConsumerAuthority", true);
		payload.put("plannerActionKey", plannerActionKey);
		return encode(payload);
	}

	static String local(String fType, String reason,
		List<FederatedLocalMaterializeRegistry.ConsumerInputSpec> consumers, String plannerActionKey) {
		Map<String,Object> payload = payload("LOCAL");
		payload.put("materializationFType", fType);
		payload.put("reason", reason);
		List<Map<String,Object>> inputs = consumers.stream().sorted().map(input ->
			consumer(input.consumerHopId(), input.inputPosition())).toList();
		payload.put("consumerInputs", inputs);
		payload.put("plannerActionKey", plannerActionKey);
		return encode(payload);
	}

	private static Map<String,Object> payload(String kind) {
		Map<String,Object> payload = new LinkedHashMap<>();
		payload.put("schema", SCHEMA);
		payload.put("kind", kind);
		return payload;
	}

	private static List<Map<String,Object>> refedConsumers(List<ConsumerInputSpec> consumers) {
		return consumers.stream().sorted().map(input ->
			consumer(input.consumerHopId(), input.inputPosition())).toList();
	}

	private static Map<String,Object> consumer(long consumerHopId, int inputPosition) {
		Map<String,Object> value = new LinkedHashMap<>();
		value.put("consumerHopId", consumerHopId);
		value.put("inputPosition", inputPosition);
		return value;
	}

	private static String encode(Map<String,Object> payload) {
		try {
			byte[] json = MAPPER.writeValueAsBytes(payload);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
		}
		catch(JsonProcessingException error) {
			throw new IllegalStateException("Cannot encode physical emission trace authority", error);
		}
	}
}

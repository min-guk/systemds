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

package org.apache.sysds.runtime.transform;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

/**
 * Validation for the narrowly scoped, explicitly authorized release of
 * transformencode recode metadata.
 */
public final class TransformEncodeMetadataPrivacy {
	public static final String RELEASE_PROPERTY = "sysds.privacy.allowPublicRecodeMetadata";
	public static final String RELEASE_SPEC_KEY = "cofeePublicRecodeMetadata";

	private static final Set<String> RELEASE_SPEC_KEYS = new HashSet<>(
		Arrays.asList("ids", "recode", "dummycode", RELEASE_SPEC_KEY));

	private TransformEncodeMetadataPrivacy() {
		// utility class
	}

	/**
	 * Returns whether the specification requests and is eligible for the
	 * explicitly enabled public-recode-metadata policy.
	 *
	 * @param spec transform specification
	 * @return true only for an enabled, strictly metadata-only specification
	 */
	public static boolean allowsPublicRecodeMetadata(String spec) {
		if(!Boolean.getBoolean(RELEASE_PROPERTY) || spec == null)
			return false;

		try {
			JSONObject parsedSpec = new JSONObject(spec);
			if(!Boolean.TRUE.equals(parsedSpec.get(RELEASE_SPEC_KEY)) ||
				!Boolean.TRUE.equals(parsedSpec.get("ids")))
				return false;

			@SuppressWarnings("unchecked")
			Iterator<String> keys = parsedSpec.keys();
			while(keys.hasNext())
				if(!RELEASE_SPEC_KEYS.contains(keys.next()))
					return false;

			boolean hasRecode = parsedSpec.containsKey("recode");
			boolean hasDummycode = parsedSpec.containsKey("dummycode");
			return (hasRecode || hasDummycode) &&
				(!hasRecode || isPositiveIntegerArray(parsedSpec.get("recode"))) &&
				(!hasDummycode || isPositiveIntegerArray(parsedSpec.get("dummycode")));
		}
		catch(Exception ex) {
			return false;
		}
	}

	/**
	 * Rejects an explicit public-recode-metadata request unless it satisfies
	 * both the strict specification contract and deployment opt-in.
	 * Ordinary transform specifications are deliberately left to the existing
	 * transform parser and retain its current validation behavior.
	 *
	 * @param spec transform specification
	 */
	public static void validateReleaseRequest(String spec) {
		if(!explicitlyRequestsRelease(spec))
			return;
		if(!allowsPublicRecodeMetadata(spec))
			throw new DMLRuntimeException("Public transformencode recode metadata release is not authorized " +
				"or the transform specification is not metadata-only");
	}

	/**
	 * Validates an explicit release request and returns the ordinary transform
	 * specification consumed by encoder construction. The policy declaration is
	 * removed only after successful validation.
	 *
	 * @param spec transform specification
	 * @return the original ordinary specification, or a validated release specification without its declaration
	 */
	public static String validateAndStripReleaseRequest(String spec) {
		validateReleaseRequest(spec);
		if(!explicitlyRequestsRelease(spec))
			return spec;

		try {
			JSONObject parsedSpec = new JSONObject(spec);
			parsedSpec.remove(RELEASE_SPEC_KEY);
			return parsedSpec.toString();
		}
		catch(Exception ex) {
			throw new DMLRuntimeException("Failed to normalize validated transformencode release specification", ex);
		}
	}

	private static boolean explicitlyRequestsRelease(String spec) {
		if(spec == null)
			return false;
		try {
			JSONObject parsedSpec = new JSONObject(spec);
			return parsedSpec.containsKey(RELEASE_SPEC_KEY) &&
				Boolean.TRUE.equals(parsedSpec.get(RELEASE_SPEC_KEY));
		}
		catch(Exception ex) {
			// Preserve the existing parser's diagnostics for malformed ordinary specs.
			return false;
		}
	}

	private static boolean isPositiveIntegerArray(Object value) {
		if(!(value instanceof JSONArray))
			return false;

		JSONArray values = (JSONArray) value;
		if(values.length() == 0)
			return false;

		try {
			for(int i = 0; i < values.length(); i++) {
				Object column = values.get(i);
				if(!(column instanceof Integer || column instanceof Long) || ((Number) column).longValue() <= 0 ||
					((Number) column).longValue() > Integer.MAX_VALUE)
					return false;
			}
			return true;
		}
		catch(Exception ex) {
			return false;
		}
	}
}

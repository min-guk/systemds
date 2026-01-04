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

package org.apache.sysds.lops.compile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FederatedFoutMaterializeRegistry {
	private static final Map<Long, Map<Long, MaterializeSpec>> MATERIALIZE_ANCHORS = new ConcurrentHashMap<>();

	private FederatedFoutMaterializeRegistry() {
	}

	public static void clear() {
		MATERIALIZE_ANCHORS.clear();
	}

	public static void register(long sbId, long hopId, long anchorHopId, String fTypeHint) {
		MATERIALIZE_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, new MaterializeSpec(anchorHopId, fTypeHint));
	}

	public static boolean isEmpty() {
		return MATERIALIZE_ANCHORS.isEmpty();
	}

	public static Map<Long, MaterializeSpec> snapshot(long sbId) {
		Map<Long, MaterializeSpec> entries = MATERIALIZE_ANCHORS.get(sbId);
		if (entries == null || entries.isEmpty())
			return Collections.emptyMap();
		return Collections.unmodifiableMap(new HashMap<>(entries));
	}

	public static final class MaterializeSpec {
		private final long _anchorHopId;
		private final String _fTypeHint;

		public MaterializeSpec(long anchorHopId, String fTypeHint) {
			_anchorHopId = anchorHopId;
			_fTypeHint = fTypeHint;
		}

		public long getAnchorHopId() {
			return _anchorHopId;
		}

		public String getFTypeHint() {
			return _fTypeHint;
		}
	}
}

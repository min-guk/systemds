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
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class FederatedRefedRegistry {
	private static final Map<Long, Map<Long, AnchorSpec>> REFED_ANCHORS = new ConcurrentHashMap<>();

	private FederatedRefedRegistry() {
	}

	public static void clear() {
		REFED_ANCHORS.clear();
	}

	/** Deep immutable snapshot of every statement-block scope in this registry. */
	public record Snapshot(Map<Long, Map<Long, AnchorSpec>> scopes) {
		public Snapshot {
			scopes = immutableSnapshot(scopes);
		}
	}

	public static Snapshot snapshotAll() {
		return new Snapshot(REFED_ANCHORS);
	}

	/** Exactly replaces all registry scopes with the supplied typed snapshot. */
	public static void restoreAll(Snapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		REFED_ANCHORS.clear();
		for(Map.Entry<Long, Map<Long, AnchorSpec>> scope : snapshot.scopes().entrySet()) {
			Map<Long, AnchorSpec> entries = new ConcurrentHashMap<>();
			for(Map.Entry<Long, AnchorSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				REFED_ANCHORS.put(scope.getKey(), entries);
		}
	}

	public static void register(long sbId, long hopId, long anchorHopId) {
		register(sbId, hopId, anchorHopId, null);
	}

	public static void register(long sbId, long hopId, long anchorHopId, String anchorKey) {
		REFED_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, new AnchorSpec(anchorHopId, anchorKey));
	}

	public static void remove(long sbId, long hopId) {
		Map<Long, AnchorSpec> anchors = REFED_ANCHORS.get(sbId);
		if (anchors == null)
			return;
		anchors.remove(hopId);
		if (anchors.isEmpty())
			REFED_ANCHORS.remove(sbId);
	}

	public static Long getAnchorHopId(long hopId) {
		for (Map<Long, AnchorSpec> anchors : REFED_ANCHORS.values()) {
			AnchorSpec anchor = anchors.get(hopId);
			if (anchor != null)
				return anchor.getAnchorHopId();
		}
		return null;
	}

	public static boolean isEmpty() {
		return REFED_ANCHORS.isEmpty();
	}

	public static boolean hasEntry(long hopId) {
		for (Map<Long, AnchorSpec> anchors : REFED_ANCHORS.values()) {
			if (anchors != null && anchors.containsKey(hopId))
				return true;
		}
		return false;
	}

	public static Map<Long, AnchorSpec> snapshot(long sbId) {
		Map<Long, AnchorSpec> anchors = REFED_ANCHORS.get(sbId);
		if (anchors == null || anchors.isEmpty())
			return Collections.emptyMap();
		return Collections.unmodifiableMap(new HashMap<>(anchors));
	}

	private static Map<Long, Map<Long, AnchorSpec>> immutableSnapshot(
		Map<Long, ? extends Map<Long, AnchorSpec>> source) {
		Objects.requireNonNull(source, "scopes");
		Map<Long, Map<Long, AnchorSpec>> scopes = new TreeMap<>();
		for(Map.Entry<Long, ? extends Map<Long, AnchorSpec>> scope : source.entrySet()) {
			Map<Long, AnchorSpec> entries = new TreeMap<>();
			for(Map.Entry<Long, AnchorSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				scopes.put(scope.getKey(), Collections.unmodifiableMap(entries));
		}
		return Collections.unmodifiableMap(scopes);
	}

	private static AnchorSpec copy(AnchorSpec spec) {
		Objects.requireNonNull(spec, "anchorSpec");
		return new AnchorSpec(spec.getAnchorHopId(), spec.getAnchorKey());
	}

	public static final class AnchorSpec {
		private final long _anchorHopId;
		private final String _anchorKey;

		public AnchorSpec(long anchorHopId, String anchorKey) {
			_anchorHopId = anchorHopId;
			_anchorKey = anchorKey;
		}

		public long getAnchorHopId() {
			return _anchorHopId;
		}

		public String getAnchorKey() {
			return _anchorKey;
		}
	}
}

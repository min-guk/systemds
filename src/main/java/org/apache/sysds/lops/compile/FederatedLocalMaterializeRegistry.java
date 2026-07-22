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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for MinST-selected D obligations.
 *
 * A D obligation materializes one local representation of a selected FED/FOUT
 * producer for a compatible domain of local consumers.  The planner registers
 * only capability-proven obligations; Dag consumes the registry by inserting one
 * reusable CP prefetch/materialization lop and rewiring only the selected local
 * consumers.
 */
public final class FederatedLocalMaterializeRegistry {
	private static final Map<Long, Map<Long, LocalMaterializeSpec>> LOCAL_MATERIALIZE = new ConcurrentHashMap<>();

	private FederatedLocalMaterializeRegistry() {
	}

	public static void clear() {
		LOCAL_MATERIALIZE.clear();
	}

	/** Deep immutable snapshot of every statement-block scope in this registry. */
	public record Snapshot(Map<Long, Map<Long, LocalMaterializeSpec>> scopes) {
		public Snapshot {
			scopes = immutableSnapshot(scopes);
		}
	}

	public static Snapshot snapshotAll() {
		return new Snapshot(LOCAL_MATERIALIZE);
	}

	/** Exactly replaces all registry scopes with the supplied typed snapshot. */
	public static void restoreAll(Snapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		LOCAL_MATERIALIZE.clear();
		for(Map.Entry<Long, Map<Long, LocalMaterializeSpec>> scope : snapshot.scopes().entrySet()) {
			Map<Long, LocalMaterializeSpec> entries = new ConcurrentHashMap<>();
			for(Map.Entry<Long, LocalMaterializeSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				LOCAL_MATERIALIZE.put(scope.getKey(), entries);
		}
	}

	public static void register(long sbId, long hopId, List<Long> consumerHopIds, String fTypeHint,
			String reason) {
		LOCAL_MATERIALIZE.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, new LocalMaterializeSpec(consumerHopIds, fTypeHint, reason));
	}

	public static void remove(long sbId, long hopId) {
		Map<Long, LocalMaterializeSpec> entries = LOCAL_MATERIALIZE.get(sbId);
		if (entries == null)
			return;
		entries.remove(hopId);
		if (entries.isEmpty())
			LOCAL_MATERIALIZE.remove(sbId);
	}

	public static boolean isEmpty() {
		return LOCAL_MATERIALIZE.isEmpty();
	}

	public static boolean hasEntry(long hopId) {
		for (Map<Long, LocalMaterializeSpec> entries : LOCAL_MATERIALIZE.values()) {
			if (entries != null && entries.containsKey(hopId))
				return true;
		}
		return false;
	}

	public static Map<Long, LocalMaterializeSpec> snapshot(long sbId) {
		Map<Long, LocalMaterializeSpec> defaults = LOCAL_MATERIALIZE.get(-1L);
		Map<Long, LocalMaterializeSpec> entries = LOCAL_MATERIALIZE.get(sbId);
		if ((defaults == null || defaults.isEmpty()) && (entries == null || entries.isEmpty()))
			return Collections.emptyMap();
		Map<Long, LocalMaterializeSpec> snapshot = new HashMap<>();
		if (defaults != null)
			snapshot.putAll(defaults);
		if (entries != null)
			snapshot.putAll(entries);
		return Collections.unmodifiableMap(snapshot);
	}

	public static Map<Long, Map<Long, LocalMaterializeSpec>> snapshotScopes(long sbId) {
		Map<Long, Map<Long, LocalMaterializeSpec>> snapshot = new HashMap<>();
		copyScope(snapshot, -1L);
		if (sbId != -1L)
			copyScope(snapshot, sbId);
		return snapshot.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(snapshot);
	}

	private static void copyScope(Map<Long, Map<Long, LocalMaterializeSpec>> snapshot, long sbId) {
		Map<Long, LocalMaterializeSpec> entries = LOCAL_MATERIALIZE.get(sbId);
		if (entries == null || entries.isEmpty())
			return;
		snapshot.put(sbId, Collections.unmodifiableMap(new HashMap<>(entries)));
	}

	private static Map<Long, Map<Long, LocalMaterializeSpec>> immutableSnapshot(
		Map<Long, ? extends Map<Long, LocalMaterializeSpec>> source) {
		Objects.requireNonNull(source, "scopes");
		Map<Long, Map<Long, LocalMaterializeSpec>> scopes = new TreeMap<>();
		for(Map.Entry<Long, ? extends Map<Long, LocalMaterializeSpec>> scope : source.entrySet()) {
			Map<Long, LocalMaterializeSpec> entries = new TreeMap<>();
			for(Map.Entry<Long, LocalMaterializeSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				scopes.put(scope.getKey(), Collections.unmodifiableMap(entries));
		}
		return Collections.unmodifiableMap(scopes);
	}

	private static LocalMaterializeSpec copy(LocalMaterializeSpec spec) {
		Objects.requireNonNull(spec, "localMaterializeSpec");
		return new LocalMaterializeSpec(spec.getConsumerHopIds(), spec.getFTypeHint(), spec.getReason());
	}

	public static final class LocalMaterializeSpec {
		private final List<Long> _consumerHopIds;
		private final String _fTypeHint;
		private final String _reason;

		public LocalMaterializeSpec(List<Long> consumerHopIds, String fTypeHint, String reason) {
			_consumerHopIds = Collections.unmodifiableList(new ArrayList<>(
				consumerHopIds != null ? consumerHopIds : Collections.emptyList()));
			_fTypeHint = fTypeHint;
			_reason = reason;
		}

		public List<Long> getConsumerHopIds() {
			return _consumerHopIds;
		}

		public String getFTypeHint() {
			return _fTypeHint;
		}

		public String getReason() {
			return _reason;
		}
	}
}

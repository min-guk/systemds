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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;

public final class FederatedFoutMaterializeRegistry {
	private static final Map<Long, Map<Long, MaterializeSpec>> MATERIALIZE_ANCHORS = new ConcurrentHashMap<>();

	private FederatedFoutMaterializeRegistry() {
	}

	public static void clear() {
		MATERIALIZE_ANCHORS.clear();
	}

	/** Deep immutable snapshot of every statement-block scope in this registry. */
	public record Snapshot(Map<Long, Map<Long, MaterializeSpec>> scopes) {
		public Snapshot {
			scopes = immutableSnapshot(scopes);
		}
	}

	public static Snapshot snapshotAll() {
		return new Snapshot(MATERIALIZE_ANCHORS);
	}

	/** Exactly replaces all registry scopes with the supplied typed snapshot. */
	public static void restoreAll(Snapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		MATERIALIZE_ANCHORS.clear();
		for(Map.Entry<Long, Map<Long, MaterializeSpec>> scope : snapshot.scopes().entrySet()) {
			Map<Long, MaterializeSpec> entries = new ConcurrentHashMap<>();
			for(Map.Entry<Long, MaterializeSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				MATERIALIZE_ANCHORS.put(scope.getKey(), entries);
		}
	}

	public static void register(long sbId, long hopId, long anchorHopId, String fTypeHint) {
		register(sbId, hopId, anchorHopId, fTypeHint, null, null);
	}

	public static void register(long sbId, long hopId, long anchorHopId, String fTypeHint, String anchorLabel) {
		register(sbId, hopId, anchorHopId, fTypeHint, anchorLabel, null);
	}

	public static void register(long sbId, long hopId, long anchorHopId, String fTypeHint, String anchorLabel,
			String anchorKey) {
		MATERIALIZE_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, MaterializeSpec.legacy(anchorHopId, fTypeHint, anchorLabel, anchorKey));
	}

	/**
	 * Registers common-planner authority for one derived FOUT value. Only the
	 * listed physical consumer input occurrences may be rewired from the existing
	 * local result to the materialized federated result. An empty list is valid:
	 * the selected output itself is still materialized, but no downstream input is
	 * authorized to consume it directly.
	 */
	public static void registerConsumerInputs(long sbId, long hopId, long anchorHopId,
		String fTypeHint, String anchorLabel, String anchorKey,
		List<ConsumerInputSpec> consumerInputs) {
		MATERIALIZE_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, MaterializeSpec.forConsumerInputs(anchorHopId, fTypeHint,
				anchorLabel, anchorKey, consumerInputs));
	}

	/** Restores one typed registration without degrading exact input authority. */
	public static void registerSpec(long sbId, long hopId, MaterializeSpec spec) {
		MATERIALIZE_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, copy(Objects.requireNonNull(spec, "materializeSpec")));
	}

	public static void remove(long sbId, long hopId) {
		Map<Long, MaterializeSpec> entries = MATERIALIZE_ANCHORS.get(sbId);
		if (entries == null)
			return;
		entries.remove(hopId);
		if (entries.isEmpty())
			MATERIALIZE_ANCHORS.remove(sbId);
	}

	public static boolean isEmpty() {
		return MATERIALIZE_ANCHORS.isEmpty();
	}

	public static boolean hasEntry(long hopId) {
		for (Map<Long, MaterializeSpec> entries : MATERIALIZE_ANCHORS.values()) {
			if (entries != null && entries.containsKey(hopId))
				return true;
		}
		return false;
	}

	/** True when the planner selected at least one input of this consumer for FOUT materialization. */
	public static boolean hasSelectedConsumerInput(long consumerHopId) {
		for(Map<Long, MaterializeSpec> entries : MATERIALIZE_ANCHORS.values())
			if(entries != null)
				for(MaterializeSpec spec : entries.values())
					if(spec.getConsumerInputs().stream()
						.anyMatch(input -> input.consumerHopId() == consumerHopId))
						return true;
		return false;
	}

	public static Map<Long, MaterializeSpec> snapshot(long sbId) {
		Map<Long, MaterializeSpec> entries = MATERIALIZE_ANCHORS.get(sbId);
		if (entries == null || entries.isEmpty())
			return Collections.emptyMap();
		return Collections.unmodifiableMap(new HashMap<>(entries));
	}

	private static Map<Long, Map<Long, MaterializeSpec>> immutableSnapshot(
		Map<Long, ? extends Map<Long, MaterializeSpec>> source) {
		Objects.requireNonNull(source, "scopes");
		Map<Long, Map<Long, MaterializeSpec>> scopes = new TreeMap<>();
		for(Map.Entry<Long, ? extends Map<Long, MaterializeSpec>> scope : source.entrySet()) {
			Map<Long, MaterializeSpec> entries = new TreeMap<>();
			for(Map.Entry<Long, MaterializeSpec> entry : scope.getValue().entrySet())
				entries.put(entry.getKey(), copy(entry.getValue()));
			if(!entries.isEmpty())
				scopes.put(scope.getKey(), Collections.unmodifiableMap(entries));
		}
		return Collections.unmodifiableMap(scopes);
	}

	private static MaterializeSpec copy(MaterializeSpec spec) {
		Objects.requireNonNull(spec, "materializeSpec");
		return new MaterializeSpec(spec.getAnchorHopId(), spec.getFTypeHint(), spec.getAnchorLabel(),
			spec.getAnchorKey(), spec.getConsumerInputs(), spec.hasExactConsumerAuthority());
	}

	public static final class MaterializeSpec {
		private final long _anchorHopId;
		private final String _fTypeHint;
		private final String _anchorLabel;
		private final String _anchorKey;
		private final List<ConsumerInputSpec> _consumerInputs;
		private final boolean _exactConsumerAuthority;

		public MaterializeSpec(long anchorHopId, String fTypeHint, String anchorLabel, String anchorKey) {
			this(anchorHopId, fTypeHint, anchorLabel, anchorKey, List.of(), false);
		}

		private MaterializeSpec(long anchorHopId, String fTypeHint, String anchorLabel, String anchorKey,
			List<ConsumerInputSpec> consumerInputs, boolean exactConsumerAuthority) {
			_anchorHopId = anchorHopId;
			_fTypeHint = fTypeHint;
			_anchorLabel = anchorLabel;
			_anchorKey = anchorKey;
			_exactConsumerAuthority = exactConsumerAuthority;
			_consumerInputs = exactConsumerAuthority
				? canonicalExactConsumerInputs(consumerInputs) : List.of();
		}

		private static MaterializeSpec legacy(long anchorHopId, String fTypeHint,
			String anchorLabel, String anchorKey) {
			return new MaterializeSpec(anchorHopId, fTypeHint, anchorLabel, anchorKey,
				List.of(), false);
		}

		private static MaterializeSpec forConsumerInputs(long anchorHopId, String fTypeHint,
			String anchorLabel, String anchorKey, List<ConsumerInputSpec> consumerInputs) {
			return new MaterializeSpec(anchorHopId, fTypeHint, anchorLabel, anchorKey,
				consumerInputs, true);
		}

		public long getAnchorHopId() {
			return _anchorHopId;
		}

		public String getFTypeHint() {
			return _fTypeHint;
		}

		public String getAnchorLabel() {
			return _anchorLabel;
		}

		public String getAnchorKey() {
			return _anchorKey;
		}

		public List<ConsumerInputSpec> getConsumerInputs() {
			return _consumerInputs;
		}

		public boolean hasExactConsumerAuthority() {
			return _exactConsumerAuthority;
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(!(obj instanceof MaterializeSpec that))
				return false;
			return _anchorHopId == that._anchorHopId && Objects.equals(_fTypeHint, that._fTypeHint)
				&& Objects.equals(_anchorLabel, that._anchorLabel) && Objects.equals(_anchorKey, that._anchorKey)
				&& _exactConsumerAuthority == that._exactConsumerAuthority
				&& _consumerInputs.equals(that._consumerInputs);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_anchorHopId, _fTypeHint, _anchorLabel, _anchorKey,
				_consumerInputs, _exactConsumerAuthority);
		}
	}

	private static List<ConsumerInputSpec> canonicalExactConsumerInputs(
		List<ConsumerInputSpec> consumerInputs) {
		Objects.requireNonNull(consumerInputs, "fout materialization consumer inputs");
		TreeSet<ConsumerInputSpec> sorted = new TreeSet<>();
		for(ConsumerInputSpec input : consumerInputs) {
			Objects.requireNonNull(input, "fout materialization consumer input");
			if(input.allInputs())
				throw new IllegalArgumentException(
					"exact FOUT materialization does not accept ALL_INPUTS");
			sorted.add(input);
		}
		return List.copyOf(sorted);
	}
}

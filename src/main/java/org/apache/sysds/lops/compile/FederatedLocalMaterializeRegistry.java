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

/**
 * Registry for planner-selected local-materialization obligations.
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

	/** Registers planner-owned, input-occurrence-specific FOUT-to-local authority. */
	public static void registerConsumerInputs(long sbId, long hopId,
		List<ConsumerInputSpec> consumerInputs, String fTypeHint, String reason) {
		registerConsumerInputs(sbId, hopId, consumerInputs, fTypeHint, reason, null);
	}

	/** Registers exact common-planner authority together with its immutable action identity. */
	public static void registerConsumerInputs(long sbId, long hopId,
		List<ConsumerInputSpec> consumerInputs, String fTypeHint, String reason,
		String plannerActionKey) {
		if(consumerInputs == null || consumerInputs.isEmpty())
			throw new IllegalArgumentException(
				"local materialization requires at least one exact selected consumer input");
		if(consumerInputs.stream().anyMatch(input -> input == null || input.allInputs()))
			throw new IllegalArgumentException(
				"exact local materialization does not accept null or ALL_INPUTS");
		LOCAL_MATERIALIZE.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, LocalMaterializeSpec.forConsumerInputs(
				consumerInputs, fTypeHint, reason, plannerActionKey));
	}

	/** Restores one already validated typed authority without degrading exact input positions. */
	public static void registerSpec(long sbId, long hopId, LocalMaterializeSpec spec) {
		LOCAL_MATERIALIZE.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, copy(Objects.requireNonNull(spec, "localMaterializeSpec")));
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

	/** True when the planner selected at least one input of this consumer for local materialization. */
	public static boolean hasSelectedConsumerInput(long consumerHopId) {
		for(Map<Long, LocalMaterializeSpec> entries : LOCAL_MATERIALIZE.values())
			if(entries != null)
				for(LocalMaterializeSpec spec : entries.values())
					if(spec.getConsumerInputs().stream()
						.anyMatch(input -> input.consumerHopId() == consumerHopId))
						return true;
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
		return LocalMaterializeSpec.copyOf(spec.getConsumerInputs(), spec.getFTypeHint(),
			spec.getReason(), spec.getPlannerActionKey());
	}

	/** Exact physical input identity; {@link #ALL_INPUTS} is retained for legacy registrations. */
	public record ConsumerInputSpec(long consumerHopId, int inputPosition)
		implements Comparable<ConsumerInputSpec> {
		public static final int ALL_INPUTS = -1;

		public ConsumerInputSpec {
			if(consumerHopId < 0)
				throw new IllegalArgumentException(
					"local materialization consumer hop id must be non-negative");
			if(inputPosition < ALL_INPUTS)
				throw new IllegalArgumentException(
					"local materialization input position must be -1 or non-negative");
		}

		public boolean allInputs() {
			return inputPosition == ALL_INPUTS;
		}

		@Override
		public int compareTo(ConsumerInputSpec that) {
			int hopOrder = Long.compare(consumerHopId, that.consumerHopId);
			return hopOrder != 0 ? hopOrder : Integer.compare(inputPosition, that.inputPosition);
		}
	}

	public static final class LocalMaterializeSpec {
		private final List<ConsumerInputSpec> _consumerInputs;
		private final String _fTypeHint;
		private final String _reason;
		private final String _plannerActionKey;

		public LocalMaterializeSpec(List<Long> consumerHopIds, String fTypeHint, String reason) {
			this(consumerInputsForHopIds(consumerHopIds), fTypeHint, reason, true, null);
		}

		private LocalMaterializeSpec(List<ConsumerInputSpec> consumerInputs,
			String fTypeHint, String reason, boolean canonical, String plannerActionKey) {
			_consumerInputs = canonicalConsumerInputs(consumerInputs);
			_fTypeHint = fTypeHint;
			_reason = reason;
			_plannerActionKey = normalizePlannerActionKey(plannerActionKey);
		}

		public static LocalMaterializeSpec forConsumerInputs(List<ConsumerInputSpec> consumerInputs,
			String fTypeHint, String reason) {
			return forConsumerInputs(consumerInputs, fTypeHint, reason, null);
		}

		public static LocalMaterializeSpec forConsumerInputs(List<ConsumerInputSpec> consumerInputs,
			String fTypeHint, String reason, String plannerActionKey) {
			return new LocalMaterializeSpec(consumerInputs, fTypeHint, reason, true, plannerActionKey);
		}

		private static LocalMaterializeSpec copyOf(List<ConsumerInputSpec> consumerInputs,
			String fTypeHint, String reason, String plannerActionKey) {
			return new LocalMaterializeSpec(consumerInputs, fTypeHint, reason, true, plannerActionKey);
		}

		public List<Long> getConsumerHopIds() {
			return _consumerInputs.stream().map(ConsumerInputSpec::consumerHopId)
				.distinct().sorted().toList();
		}

		public List<ConsumerInputSpec> getConsumerInputs() {
			return _consumerInputs;
		}

		public String getFTypeHint() {
			return _fTypeHint;
		}

		public String getReason() {
			return _reason;
		}

		public String getPlannerActionKey() {
			return _plannerActionKey;
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(!(obj instanceof LocalMaterializeSpec that))
				return false;
			return _consumerInputs.equals(that._consumerInputs) && Objects.equals(_fTypeHint, that._fTypeHint)
				&& Objects.equals(_reason, that._reason)
				&& Objects.equals(_plannerActionKey, that._plannerActionKey);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_consumerInputs, _fTypeHint, _reason, _plannerActionKey);
		}
	}

	private static String normalizePlannerActionKey(String plannerActionKey) {
		return plannerActionKey == null || plannerActionKey.isBlank() ? null : plannerActionKey;
	}

	private static List<ConsumerInputSpec> consumerInputsForHopIds(List<Long> consumerHopIds) {
		if(consumerHopIds == null || consumerHopIds.isEmpty())
			return List.of();
		return consumerHopIds.stream().filter(Objects::nonNull).distinct().sorted()
			.map(consumerHopId -> new ConsumerInputSpec(consumerHopId, ConsumerInputSpec.ALL_INPUTS))
			.toList();
	}

	private static List<ConsumerInputSpec> canonicalConsumerInputs(List<ConsumerInputSpec> consumerInputs) {
		if(consumerInputs == null || consumerInputs.isEmpty())
			return List.of();
		TreeSet<ConsumerInputSpec> sorted = new TreeSet<>();
		for(ConsumerInputSpec input : consumerInputs)
			sorted.add(Objects.requireNonNull(input, "local materialization consumer input"));
		java.util.Set<Long> wildcardConsumers = sorted.stream().filter(ConsumerInputSpec::allInputs)
			.map(ConsumerInputSpec::consumerHopId).collect(java.util.stream.Collectors.toSet());
		return sorted.stream().filter(input -> input.allInputs()
			|| !wildcardConsumers.contains(input.consumerHopId())).toList();
	}
}

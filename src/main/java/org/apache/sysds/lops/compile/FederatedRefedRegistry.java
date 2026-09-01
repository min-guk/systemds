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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.hops.fedplanner.FTypes.FType;

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

	public static void register(long sbId, long hopId, long anchorHopId, String anchorKey, List<Long> consumerHopIds) {
		register(sbId, hopId, anchorHopId, anchorKey, null, consumerHopIds);
	}

	public static void register(long sbId, long hopId, long anchorHopId, String anchorKey,
		FType materializationFType, List<Long> consumerHopIds) {
		AnchorSpec spec = new AnchorSpec(anchorHopId, anchorKey, materializationFType, consumerHopIds);
		register(sbId, hopId, spec);
	}

	/** Registers planner-owned, input-occurrence-specific REFED authority. */
	public static void registerConsumerInputs(long sbId, long hopId, long anchorHopId, String anchorKey,
		FType materializationFType, List<ConsumerInputSpec> consumerInputs) {
		registerConsumerInputs(sbId, hopId, anchorHopId, anchorKey, materializationFType,
			consumerInputs, null);
	}

	/** Registers exact common-planner authority together with its immutable action identity. */
	public static void registerConsumerInputs(long sbId, long hopId, long anchorHopId, String anchorKey,
		FType materializationFType, List<ConsumerInputSpec> consumerInputs, String plannerActionKey) {
		registerConsumerInputs(sbId, hopId, anchorHopId, anchorKey, materializationFType,
			consumerInputs, plannerActionKey, null);
	}

	/** Registers exact common-planner authority, including the selected FED-to-local pre-stage. */
	public static void registerConsumerInputs(long sbId, long hopId, long anchorHopId, String anchorKey,
		FType materializationFType, List<ConsumerInputSpec> consumerInputs, String plannerActionKey,
		Boolean requiresLocalMaterialization) {
		if(consumerInputs == null || consumerInputs.isEmpty())
			throw new IllegalArgumentException("fed_refed requires at least one exact selected consumer input");
		if(consumerInputs.stream().anyMatch(input -> input == null || input.allInputs()))
			throw new IllegalArgumentException("exact fed_refed registration does not accept null or ALL_INPUTS");
		register(sbId, hopId,
			AnchorSpec.forConsumerInputs(anchorHopId, anchorKey, materializationFType,
				consumerInputs, plannerActionKey, requiresLocalMaterialization));
	}

	private static void register(long sbId, long hopId, AnchorSpec spec) {
		REFED_ANCHORS.compute(sbId, (scopeId, existingScope) -> {
			Map<Long, AnchorSpec> scope = existingScope != null ? existingScope : new ConcurrentHashMap<>();
			scope.compute(hopId, (registeredHopId, existingSpec) ->
				existingSpec == null ? spec : mergeConsumerSpecificAuthority(existingSpec, spec, sbId, hopId));
			return scope;
		});
	}

	/**
	 * Purely merges exact consumer-specific REFED authorities for one source Hop.
	 *
	 * <p>One local value may be consumed by multiple FED operations that require
	 * different materialization layouts or worker pools. Those are separate,
	 * planner-selected uploads, not conflicting authority, provided their exact
	 * consumer input occurrences are disjoint. A legacy consumer-only entry is a
	 * wildcard over every input occurrence and therefore conflicts with any
	 * incompatible authority for that same consumer.</p>
	 */
	public static AnchorSpec mergeConsumerSpecificAuthority(AnchorSpec existing, AnchorSpec incoming,
		long sbId, long hopId) {
		Objects.requireNonNull(existing, "existing");
		Objects.requireNonNull(incoming, "incoming");
		List<AuthoritySpec> merged = new ArrayList<>(existing.getAuthorities());
		for(AuthoritySpec candidate : incoming.getAuthorities()) {
			List<Integer> overlapping = new ArrayList<>();
			for(int i = 0; i < merged.size(); i++)
				if(authoritiesOverlap(merged.get(i), candidate))
					overlapping.add(i);
			if(overlapping.size() > 1)
				throw conflictingAuthority(sbId, hopId, existing, incoming);
			if(overlapping.size() == 1) {
				int index = overlapping.get(0);
				AuthoritySpec prior = merged.get(index);
				if(hasWildcardExactOverlap(prior, candidate))
					throw conflictingAuthority(sbId, hopId, existing, incoming);
				try {
					merged.set(index, mergeCompatibleAuthority(prior, candidate, sbId, hopId));
				}
				catch(IncompatibleAuthorityException failure) {
					throw conflictingAuthority(sbId, hopId, existing, incoming);
				}
				continue;
			}

			int compatibleIndex = -1;
			AuthoritySpec compatibleMerge = null;
			for(int i = 0; i < merged.size(); i++) {
				try {
					AuthoritySpec value = mergeCompatibleAuthority(merged.get(i), candidate, sbId, hopId);
					if(compatibleIndex >= 0)
						throw new IllegalArgumentException("ambiguous compatible fed_refed authority for scope="
							+ sbId + " hop=" + hopId);
					compatibleIndex = i;
					compatibleMerge = value;
				}
				catch(IncompatibleAuthorityException ignored) {
					// A different exact layout/worker pool is a distinct upload when consumers are disjoint.
				}
			}
			if(compatibleIndex >= 0)
				merged.set(compatibleIndex, compatibleMerge);
			else
				merged.add(candidate);
		}
		return AnchorSpec.fromAuthorities(merged);
	}

	/**
	 * An exact input occurrence and a legacy consumer wildcard are intentionally
	 * not interchangeable. Merging them would canonicalize the exact planner
	 * receipt back to ALL_INPUTS and silently authorize unrelated input edges.
	 */
	private static boolean hasWildcardExactOverlap(AuthoritySpec left, AuthoritySpec right) {
		for(ConsumerInputSpec leftInput : left.getConsumerInputs())
			for(ConsumerInputSpec rightInput : right.getConsumerInputs())
				if(leftInput.consumerHopId() == rightInput.consumerHopId()
					&& leftInput.allInputs() != rightInput.allInputs())
					return true;
		return false;
	}

	/**
	 * Purely validates and merges two REFED authorities for one registry slot.
	 *
	 * <p>This method performs no registry mutation. Placement emission uses it
	 * during transaction prevalidation so compatible consumer-specific
	 * relocations can be represented by one source entry without weakening the
	 * registry's conflicting-authority checks.</p>
	 */
	public static AnchorSpec mergeCompatibleAuthority(AnchorSpec existing, AnchorSpec incoming,
		long sbId, long hopId) {
		Objects.requireNonNull(existing, "existing");
		Objects.requireNonNull(incoming, "incoming");
		if(existing.getAuthorities().size() != 1 || incoming.getAuthorities().size() != 1)
			throw conflictingAuthority(sbId, hopId, existing, incoming);
		return AnchorSpec.fromAuthorities(List.of(mergeCompatibleAuthority(existing.getAuthorities().get(0),
			incoming.getAuthorities().get(0), sbId, hopId)));
	}

	private static AuthoritySpec mergeCompatibleAuthority(AuthoritySpec existing, AuthoritySpec incoming,
		long sbId, long hopId) {
		if(!Objects.equals(existing.getPlannerActionKey(), incoming.getPlannerActionKey()))
			throw incompatibleAuthority(sbId, hopId, existing, incoming);
		long existingAnchorHopId = existing.getAnchorHopId();
		long incomingAnchorHopId = incoming.getAnchorHopId();
		String existingAnchorKey = normalizeAnchorKey(existing.getAnchorKey());
		String incomingAnchorKey = normalizeAnchorKey(incoming.getAnchorKey());
		if(existingAnchorKey != null && incomingAnchorKey != null && !existingAnchorKey.equals(incomingAnchorKey))
			throw incompatibleAuthority(sbId, hopId, existing, incoming);
		String mergedAnchorKey = existingAnchorKey != null ? existingAnchorKey : incomingAnchorKey;
		FType existingMaterializationFType = existing.getMaterializationFType();
		FType incomingMaterializationFType = incoming.getMaterializationFType();
		if(existingMaterializationFType != null && incomingMaterializationFType != null
			&& existingMaterializationFType != incomingMaterializationFType)
			throw incompatibleAuthority(sbId, hopId, existing, incoming);
		FType mergedMaterializationFType = existingMaterializationFType != null
			? existingMaterializationFType : incomingMaterializationFType;
		Boolean existingLocal = existing.getRequiresLocalMaterialization();
		Boolean incomingLocal = incoming.getRequiresLocalMaterialization();
		if(existingLocal != null && incomingLocal != null && !existingLocal.equals(incomingLocal))
			throw incompatibleAuthority(sbId, hopId, existing, incoming);
		Boolean mergedLocal = existingLocal != null ? existingLocal : incomingLocal;
		boolean durableKeyProvesEquivalence = isDurableAnchorKey(existingAnchorKey)
			&& existingAnchorKey.equals(incomingAnchorKey);
		if(!durableKeyProvesEquivalence && existingAnchorHopId >= 0 && incomingAnchorHopId >= 0
			&& existingAnchorHopId != incomingAnchorHopId)
			throw incompatibleAuthority(sbId, hopId, existing, incoming);
		long mergedAnchorHopId;
		if(durableKeyProvesEquivalence && existingAnchorHopId != incomingAnchorHopId)
			mergedAnchorHopId = -1L;
		else
			mergedAnchorHopId = existingAnchorHopId >= 0 ? existingAnchorHopId : incomingAnchorHopId;
		TreeSet<ConsumerInputSpec> mergedConsumers = new TreeSet<>(existing.getConsumerInputs());
		mergedConsumers.addAll(incoming.getConsumerInputs());
		return new AuthoritySpec(mergedAnchorHopId, mergedAnchorKey, mergedMaterializationFType,
			canonicalConsumerInputs(List.copyOf(mergedConsumers)), existing.getPlannerActionKey(), mergedLocal);
	}

	private static boolean authoritiesOverlap(AuthoritySpec left, AuthoritySpec right) {
		for(ConsumerInputSpec leftInput : left.getConsumerInputs())
			for(ConsumerInputSpec rightInput : right.getConsumerInputs())
				if(leftInput.overlaps(rightInput))
					return true;
		return false;
	}

	private static IllegalArgumentException conflictingAuthority(long sbId, long hopId,
		AnchorSpec existing, AnchorSpec incoming) {
		return new IllegalArgumentException("conflicting fed_refed anchor authority for scope=" + sbId
			+ " hop=" + hopId + " existing=" + existing.getAuthorities()
			+ " incoming=" + incoming.getAuthorities());
	}

	private static IncompatibleAuthorityException incompatibleAuthority(long sbId, long hopId,
		AuthoritySpec existing, AuthoritySpec incoming) {
		return new IncompatibleAuthorityException("incompatible fed_refed authority for scope=" + sbId
			+ " hop=" + hopId + " existing=" + existing + " incoming=" + incoming);
	}

	private static String normalizeAnchorKey(String anchorKey) {
		return anchorKey == null || anchorKey.isBlank() ? null : anchorKey;
	}

	private static boolean isDurableAnchorKey(String anchorKey) {
		return anchorKey != null && !anchorKey.startsWith("VAR:");
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

	/** True when the planner selected at least one input of this consumer for REFED materialization. */
	public static boolean hasSelectedConsumerInput(long consumerHopId) {
		for(Map<Long, AnchorSpec> anchors : REFED_ANCHORS.values())
			if(anchors != null)
				for(AnchorSpec spec : anchors.values())
					if(spec.getConsumerInputs().stream()
						.anyMatch(input -> input.consumerHopId() == consumerHopId))
						return true;
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
		return AnchorSpec.fromAuthorities(spec.getAuthorities());
	}

	private static List<Long> immutableConsumerIds(List<Long> consumerHopIds) {
		if (consumerHopIds == null || consumerHopIds.isEmpty())
			throw new IllegalArgumentException("fed_refed requires at least one exact selected consumer hop id");
		for (Long consumerHopId : consumerHopIds) {
			if (consumerHopId == null)
				throw new IllegalArgumentException("fed_refed consumer hop ids must not contain null");
		}
		return consumerHopIds.stream()
			.distinct()
			.sorted()
			.toList();
	}

	private static List<ConsumerInputSpec> consumerInputsForHopIds(List<Long> consumerHopIds) {
		return immutableConsumerIds(consumerHopIds).stream()
			.map(consumerHopId -> new ConsumerInputSpec(consumerHopId, ConsumerInputSpec.ALL_INPUTS))
			.toList();
	}

	private static List<ConsumerInputSpec> canonicalConsumerInputs(List<ConsumerInputSpec> consumerInputs) {
		if(consumerInputs == null || consumerInputs.isEmpty())
			throw new IllegalArgumentException("fed_refed requires at least one exact selected consumer input");
		TreeSet<ConsumerInputSpec> sorted = new TreeSet<>();
		for(ConsumerInputSpec input : consumerInputs)
			sorted.add(Objects.requireNonNull(input, "fed_refed consumer input"));
		Set<Long> wildcardConsumers = sorted.stream().filter(ConsumerInputSpec::allInputs)
			.map(ConsumerInputSpec::consumerHopId).collect(java.util.stream.Collectors.toSet());
		return sorted.stream().filter(input -> input.allInputs()
			|| !wildcardConsumers.contains(input.consumerHopId())).toList();
	}

	/** Exact physical input identity; {@link #ALL_INPUTS} is retained for legacy registrations. */
	public record ConsumerInputSpec(long consumerHopId, int inputPosition)
		implements Comparable<ConsumerInputSpec> {
		public static final int ALL_INPUTS = -1;

		public ConsumerInputSpec {
			if(consumerHopId < 0)
				throw new IllegalArgumentException("fed_refed consumer hop id must be non-negative");
			if(inputPosition < ALL_INPUTS)
				throw new IllegalArgumentException("fed_refed input position must be -1 or non-negative");
		}

		public boolean allInputs() {
			return inputPosition == ALL_INPUTS;
		}

		private boolean overlaps(ConsumerInputSpec that) {
			return consumerHopId == that.consumerHopId
				&& (allInputs() || that.allInputs() || inputPosition == that.inputPosition);
		}

		@Override
		public int compareTo(ConsumerInputSpec that) {
			int hopOrder = Long.compare(consumerHopId, that.consumerHopId);
			return hopOrder != 0 ? hopOrder : Integer.compare(inputPosition, that.inputPosition);
		}
	}

	public static final class AnchorSpec {
		private final List<AuthoritySpec> _authorities;

		public AnchorSpec(long anchorHopId, String anchorKey, List<Long> consumerHopIds) {
			this(anchorHopId, anchorKey, null, consumerHopIds);
		}

		public AnchorSpec(long anchorHopId, String anchorKey, FType materializationFType,
			List<Long> consumerHopIds) {
			this(List.of(new AuthoritySpec(anchorHopId, anchorKey, materializationFType,
				consumerInputsForHopIds(consumerHopIds), null, null)));
		}

		public static AnchorSpec forConsumerInputs(long anchorHopId, String anchorKey,
			FType materializationFType, List<ConsumerInputSpec> consumerInputs) {
			return forConsumerInputs(anchorHopId, anchorKey, materializationFType,
				consumerInputs, null);
		}

		public static AnchorSpec forConsumerInputs(long anchorHopId, String anchorKey,
			FType materializationFType, List<ConsumerInputSpec> consumerInputs,
			String plannerActionKey) {
			return forConsumerInputs(anchorHopId, anchorKey, materializationFType,
				consumerInputs, plannerActionKey, null);
		}

		public static AnchorSpec forConsumerInputs(long anchorHopId, String anchorKey,
			FType materializationFType, List<ConsumerInputSpec> consumerInputs,
			String plannerActionKey, Boolean requiresLocalMaterialization) {
			return new AnchorSpec(List.of(new AuthoritySpec(anchorHopId, anchorKey, materializationFType,
				consumerInputs, plannerActionKey, requiresLocalMaterialization)));
		}

		private AnchorSpec(List<AuthoritySpec> authorities) {
			if(authorities == null || authorities.isEmpty())
				throw new IllegalArgumentException("fed_refed requires at least one exact authority");
			List<AuthoritySpec> sorted = authorities.stream().map(AuthoritySpec::copy)
				.distinct().sorted().toList();
			List<AuthoritySpec> accepted = new ArrayList<>();
			for(AuthoritySpec authority : sorted) {
				for(AuthoritySpec prior : accepted)
					if(authoritiesOverlap(prior, authority))
						throw new IllegalArgumentException("fed_refed consumer input belongs to multiple authorities: "
							+ prior.getConsumerInputs() + " and " + authority.getConsumerInputs());
				accepted.add(authority);
			}
			_authorities = sorted;
		}

		private static AnchorSpec fromAuthorities(List<AuthoritySpec> authorities) {
			return new AnchorSpec(authorities);
		}

		public List<AuthoritySpec> getAuthorities() {
			return _authorities;
		}

		public long getAnchorHopId() {
			long anchorHopId = _authorities.get(0).getAnchorHopId();
			return _authorities.stream().allMatch(authority -> authority.getAnchorHopId() == anchorHopId)
				? anchorHopId : -1L;
		}

		public String getAnchorKey() {
			String anchorKey = _authorities.get(0).getAnchorKey();
			return _authorities.stream().allMatch(authority -> Objects.equals(authority.getAnchorKey(), anchorKey))
				? anchorKey : null;
		}

		public FType getMaterializationFType() {
			FType fType = _authorities.get(0).getMaterializationFType();
			return _authorities.stream().allMatch(authority -> authority.getMaterializationFType() == fType)
				? fType : null;
		}

		public List<Long> getConsumerHopIds() {
			return _authorities.stream().flatMap(authority -> authority.getConsumerHopIds().stream())
				.distinct().sorted().toList();
		}

		public List<ConsumerInputSpec> getConsumerInputs() {
			return _authorities.stream().flatMap(authority -> authority.getConsumerInputs().stream())
				.distinct().sorted().toList();
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(!(obj instanceof AnchorSpec that))
				return false;
			return Objects.equals(_authorities, that._authorities);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_authorities);
		}

		@Override
		public String toString() {
			return "AnchorSpec" + _authorities;
		}
	}

	public static final class AuthoritySpec implements Comparable<AuthoritySpec> {
		private final long _anchorHopId;
		private final String _anchorKey;
		private final FType _materializationFType;
		private final List<ConsumerInputSpec> _consumerInputs;
		private final String _plannerActionKey;
		private final Boolean _requiresLocalMaterialization;

		private AuthoritySpec(long anchorHopId, String anchorKey, FType materializationFType,
			List<ConsumerInputSpec> consumerInputs, String plannerActionKey,
			Boolean requiresLocalMaterialization) {
			_anchorHopId = anchorHopId;
			_anchorKey = anchorKey;
			if(materializationFType == FType.PART || materializationFType == FType.OTHER)
				throw new IllegalArgumentException("fed_refed does not support materialization type "
					+ materializationFType);
			_materializationFType = materializationFType;
			_consumerInputs = canonicalConsumerInputs(consumerInputs);
			_plannerActionKey = normalizePlannerActionKey(plannerActionKey);
			_requiresLocalMaterialization = requiresLocalMaterialization;
		}

		private AuthoritySpec copy() {
			return new AuthoritySpec(_anchorHopId, _anchorKey, _materializationFType,
				_consumerInputs, _plannerActionKey, _requiresLocalMaterialization);
		}

		public long getAnchorHopId() {
			return _anchorHopId;
		}

		public String getAnchorKey() {
			return _anchorKey;
		}

		public FType getMaterializationFType() {
			return _materializationFType;
		}

		public List<Long> getConsumerHopIds() {
			return _consumerInputs.stream().map(ConsumerInputSpec::consumerHopId).distinct().sorted().toList();
		}

		public List<ConsumerInputSpec> getConsumerInputs() {
			return _consumerInputs;
		}

		public String getPlannerActionKey() {
			return _plannerActionKey;
		}

		public Boolean getRequiresLocalMaterialization() {
			return _requiresLocalMaterialization;
		}

		private String normalizedSignature() {
			return _anchorHopId + "|" + Objects.toString(_anchorKey, "") + "|"
				+ Objects.toString(_materializationFType, "") + "|" + _consumerInputs + "|"
				+ Objects.toString(_plannerActionKey, "") + "|"
				+ Objects.toString(_requiresLocalMaterialization, "");
		}

		@Override
		public int compareTo(AuthoritySpec that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(!(obj instanceof AuthoritySpec that))
				return false;
			return _anchorHopId == that._anchorHopId && Objects.equals(_anchorKey, that._anchorKey)
				&& _materializationFType == that._materializationFType
				&& Objects.equals(_consumerInputs, that._consumerInputs)
				&& Objects.equals(_plannerActionKey, that._plannerActionKey)
				&& Objects.equals(_requiresLocalMaterialization, that._requiresLocalMaterialization);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_anchorHopId, _anchorKey, _materializationFType,
				_consumerInputs, _plannerActionKey, _requiresLocalMaterialization);
		}

		@Override
		public String toString() {
			return "(" + _anchorHopId + "," + _anchorKey + "," + _materializationFType + ","
				+ _consumerInputs + "," + _plannerActionKey + ","
				+ _requiresLocalMaterialization + ")";
		}
	}

	private static String normalizePlannerActionKey(String plannerActionKey) {
		return plannerActionKey == null || plannerActionKey.isBlank() ? null : plannerActionKey;
	}

	private static final class IncompatibleAuthorityException extends IllegalArgumentException {
		private static final long serialVersionUID = 6607550702624227807L;

		private IncompatibleAuthorityException(String message) {
			super(message);
		}
	}
}

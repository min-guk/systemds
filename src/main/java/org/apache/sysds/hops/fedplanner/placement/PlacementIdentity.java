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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.FTypes.FType;

/** Structural identities used by the immutable neutral placement graph. */
public final class PlacementIdentity {
	public enum VersionKind {
		ORDINARY,
		BRANCH_JOIN_PHI,
		LOOP_HEAD_PHI,
		LOOP_BACKEDGE,
		FUNCTION_INPUT,
		FUNCTION_OUTPUT,
		CLONE_RECOMPILE
	}

	public record ControlRegionKey(String programFingerprint, String functionNamespace,
		List<String> regionPath, String callSitePath, String recompileContext)
		implements Comparable<ControlRegionKey> {

		public ControlRegionKey {
			programFingerprint = requireText(programFingerprint, "programFingerprint");
			functionNamespace = requireText(functionNamespace, "functionNamespace");
			regionPath = immutableStrings(regionPath, "regionPath");
			callSitePath = requireText(callSitePath, "callSitePath");
			recompileContext = requireText(recompileContext, "recompileContext");
		}

		public String normalizedSignature() {
			return fields(programFingerprint, functionNamespace, list(regionPath), callSitePath,
				recompileContext);
		}

		@Override
		public int compareTo(ControlRegionKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record CompiledHopKey(String programFingerprint, String functionNamespace,
		String callSitePath, String recompileContext, ControlRegionKey controlRegion,
		String emittedHopInstance, String canonicalSourceOrigin)
		implements Comparable<CompiledHopKey> {

		public CompiledHopKey {
			programFingerprint = requireText(programFingerprint, "programFingerprint");
			functionNamespace = requireText(functionNamespace, "functionNamespace");
			callSitePath = requireText(callSitePath, "callSitePath");
			recompileContext = requireText(recompileContext, "recompileContext");
			Objects.requireNonNull(controlRegion, "controlRegion");
			emittedHopInstance = requireText(emittedHopInstance, "emittedHopInstance");
			canonicalSourceOrigin = requireText(canonicalSourceOrigin, "canonicalSourceOrigin");
			if(!programFingerprint.equals(controlRegion.programFingerprint()))
				throw new IllegalArgumentException("Compiled Hop and control region fingerprints differ");
			if(!functionNamespace.equals(controlRegion.functionNamespace()))
				throw new IllegalArgumentException("Compiled Hop and control region namespaces differ");
		}

		public String normalizedSignature() {
			return fields(programFingerprint, functionNamespace, callSitePath, recompileContext,
				controlRegion.normalizedSignature(), emittedHopInstance, canonicalSourceOrigin);
		}

		@Override
		public int compareTo(CompiledHopKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record ValueVersionKey(String programFingerprint, String lexicalVariable,
		ControlRegionKey definingControlRegion, int definitionOrdinal, VersionKind versionKind,
		List<String> predecessorVersions) implements Comparable<ValueVersionKey> {

		public ValueVersionKey {
			programFingerprint = requireText(programFingerprint, "programFingerprint");
			lexicalVariable = requireText(lexicalVariable, "lexicalVariable");
			Objects.requireNonNull(definingControlRegion, "definingControlRegion");
			if(definitionOrdinal < 0)
				throw new IllegalArgumentException("definitionOrdinal must be non-negative");
			Objects.requireNonNull(versionKind, "versionKind");
			predecessorVersions = sortedStrings(predecessorVersions, "predecessorVersions");
			if(!programFingerprint.equals(definingControlRegion.programFingerprint()))
				throw new IllegalArgumentException("Value version and control region fingerprints differ");
		}

		public String normalizedSignature() {
			return fields(programFingerprint, lexicalVariable,
				definingControlRegion.normalizedSignature(), Integer.toString(definitionOrdinal),
				versionKind.name(), list(predecessorVersions));
		}

		@Override
		public int compareTo(ValueVersionKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record AnchorPartition(String workerId, List<Long> begin, List<Long> end)
		implements Comparable<AnchorPartition> {

		public AnchorPartition {
			workerId = requireText(workerId, "workerId");
			begin = immutableLongs(begin, "begin");
			end = immutableLongs(end, "end");
			if(begin.isEmpty() || begin.size() != end.size())
				throw new IllegalArgumentException("Anchor range bounds must have equal non-zero dimensions");
			for(int i = 0; i < begin.size(); i++)
				if(begin.get(i) > end.get(i))
					throw new IllegalArgumentException("Anchor range begin exceeds end at dimension " + i);
		}

		public String normalizedSignature() {
			return fields(workerId, longs(begin), longs(end));
		}

		@Override
		public int compareTo(AnchorPartition that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record DurableAnchorKey(String placementId, FType fType,
		List<AnchorPartition> partitions) implements Comparable<DurableAnchorKey> {

		public DurableAnchorKey {
			placementId = requireText(placementId, "placementId");
			Objects.requireNonNull(fType, "fType");
			partitions = sorted(partitions, "partitions");
			if(partitions.isEmpty())
				throw new IllegalArgumentException("A durable anchor requires placement partitions");
		}

		public String normalizedSignature() {
			return fields(placementId, fType.name(), signatures(partitions));
		}

		@Override
		public int compareTo(DurableAnchorKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record RelocationActionKey(ValueVersionKey sourceValueVersion,
		PlacementState targetPlacement, DurableAnchorKey durableAnchor,
		String statementBlockScope, List<CompiledHopKey> compatibleConsumers)
		implements Comparable<RelocationActionKey> {

		public RelocationActionKey {
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(targetPlacement, "targetPlacement");
			Objects.requireNonNull(durableAnchor, "durableAnchor");
			statementBlockScope = requireText(statementBlockScope, "statementBlockScope");
			compatibleConsumers = sorted(compatibleConsumers, "compatibleConsumers");
			if(compatibleConsumers.isEmpty())
				throw new IllegalArgumentException("A relocation action requires compatible consumers");
		}

		public String normalizedSignature() {
			return fields(sourceValueVersion.normalizedSignature(), targetPlacement.normalizedSignature(),
				durableAnchor.normalizedSignature(), statementBlockScope,
				signatures(compatibleConsumers));
		}

		@Override
		public int compareTo(RelocationActionKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record ObligationKey(CompiledHopKey consumer, int inputPosition,
		ValueVersionKey sourceValueVersion, PlacementState requiredPlacement,
		RelocationActionKey relocationAction, String callRecompileContext)
		implements Comparable<ObligationKey> {

		public ObligationKey {
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("inputPosition must be non-negative");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			Objects.requireNonNull(relocationAction, "relocationAction");
			callRecompileContext = requireText(callRecompileContext, "callRecompileContext");
			if(!sourceValueVersion.equals(relocationAction.sourceValueVersion()))
				throw new IllegalArgumentException("Obligation and relocation source versions differ");
			if(!relocationAction.compatibleConsumers().contains(consumer))
				throw new IllegalArgumentException("Obligation consumer is not compatible with relocation");
		}

		public String normalizedSignature() {
			return fields(consumer.normalizedSignature(), Integer.toString(inputPosition),
				sourceValueVersion.normalizedSignature(), requiredPlacement.normalizedSignature(),
				relocationAction.normalizedSignature(), callRecompileContext);
		}

		@Override
		public int compareTo(ObligationKey that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	private PlacementIdentity() {
		// utility class
	}

	private static String requireText(String value, String name) {
		if(value == null || value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	private static List<String> sortedStrings(Collection<String> values, String name) {
		Objects.requireNonNull(values, name);
		List<String> copy = new ArrayList<>(values.size());
		for(String value : values)
			copy.add(requireText(value, name + " entry"));
		Collections.sort(copy);
		if(hasDuplicates(copy))
			throw new IllegalArgumentException(name + " contains duplicates");
		return List.copyOf(copy);
	}

	private static List<String> immutableStrings(Collection<String> values, String name) {
		Objects.requireNonNull(values, name);
		List<String> copy = new ArrayList<>(values.size());
		for(String value : values)
			copy.add(requireText(value, name + " entry"));
		return List.copyOf(copy);
	}

	private static List<Long> immutableLongs(Collection<Long> values, String name) {
		Objects.requireNonNull(values, name);
		List<Long> copy = new ArrayList<>(values.size());
		for(Long value : values)
			copy.add(Objects.requireNonNull(value, name + " entry"));
		return List.copyOf(copy);
	}

	private static <T extends Comparable<? super T>> List<T> sorted(Collection<T> values,
		String name) {
		Objects.requireNonNull(values, name);
		List<T> copy = new ArrayList<>(values.size());
		for(T value : values)
			copy.add(Objects.requireNonNull(value, name + " entry"));
		copy.sort(Comparator.naturalOrder());
		if(hasDuplicates(copy))
			throw new IllegalArgumentException(name + " contains duplicates");
		return List.copyOf(copy);
	}

	private static boolean hasDuplicates(List<?> values) {
		for(int i = 1; i < values.size(); i++)
			if(values.get(i - 1).equals(values.get(i)))
				return true;
		return false;
	}

	private static String fields(String... values) {
		List<String> encoded = new ArrayList<>(values.length);
		for(String value : values)
			encoded.add(token(value));
		return String.join("|", encoded);
	}

	private static String list(Collection<String> values) {
		List<String> encoded = new ArrayList<>(values.size());
		for(String value : values)
			encoded.add(token(value));
		return String.join(",", encoded);
	}

	private static String longs(Collection<Long> values) {
		List<String> strings = new ArrayList<>(values.size());
		for(Long value : values)
			strings.add(Long.toString(value));
		return list(strings);
	}

	private static String signatures(Collection<? extends Comparable<?>> values) {
		List<String> strings = new ArrayList<>(values.size());
		for(Object value : values) {
			if(value instanceof AnchorPartition partition)
				strings.add(partition.normalizedSignature());
			else if(value instanceof CompiledHopKey key)
				strings.add(key.normalizedSignature());
			else
				throw new IllegalArgumentException("Unsupported identity signature type " + value.getClass());
		}
		return list(strings);
	}

	private static String token(String value) {
		Objects.requireNonNull(value, "signature value");
		return value.length() + ":" + value;
	}
}

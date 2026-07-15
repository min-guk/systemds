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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;

/** Immutable planner-neutral placement graph used by shadow comparison. */
public final class NeutralPlacementGraph {
	public enum NodeKind {
		OPERATION,
		TRANSIENT_READ,
		TRANSIENT_WRITE,
		BRANCH_JOIN,
		LOOP_PHI,
		FUNCTION_CALL,
		FUNCTION_INPUT,
		FUNCTION_OUTPUT,
		CLONE,
		ROOT
	}

	public enum ConstraintKind {
		SAME_PLACEMENT,
		SAME_FTYPE,
		DOMINATES,
		CONJUNCTIVE,
		DISTINCT_CONTEXT
	}

	public enum ReasonCode {
		UNREACHABLE_BRANCH,
		MISSING_ANCHOR,
		UNSUPPORTED_ANCHOR,
		PRIVACY,
		UNSUPPORTED_OPERATION_SHAPE,
		ILLEGAL_TRANSIENT_PLACEMENT,
		RECOMPILE_CP_FOUT,
		UNKNOWN_METADATA,
		CONSTRAINT_CONFLICT,
		NO_FEDERATED_INPUT,
		RUNTIME_UNSUPPORTED,
		RULE_ERROR
	}

	public record Exclusion(PlacementState state, ReasonCode reasonCode, String detail)
		implements Comparable<Exclusion> {

		public Exclusion {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(reasonCode, "reasonCode");
			detail = detail == null ? "" : detail;
		}

		public String normalizedSignature() {
			return fields(state.normalizedSignature(), reasonCode.name(), detail);
		}

		@Override
		public int compareTo(Exclusion that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record Node(CompiledHopKey key, NodeKind kind, ValueVersionKey valueVersion,
		boolean emittedWork, List<PlacementState> legalAlternatives, List<Exclusion> exclusions,
		List<DurableAnchorKey> anchors) implements Comparable<Node> {

		public Node {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(valueVersion, "valueVersion");
			legalAlternatives = sorted(legalAlternatives, "legalAlternatives");
			exclusions = sorted(exclusions, "exclusions");
			anchors = sorted(anchors, "anchors");
			Set<PlacementState> excludedStates = new LinkedHashSet<>();
			for(Exclusion exclusion : exclusions)
				if(!excludedStates.add(exclusion.state()))
					throw new IllegalArgumentException("Node has multiple exclusions for one state: " + key);
			for(PlacementState state : legalAlternatives)
				if(excludedStates.contains(state))
					throw new IllegalArgumentException("Node state is both legal and excluded: " + key);
			if(!key.programFingerprint().equals(valueVersion.programFingerprint()))
				throw new IllegalArgumentException("Node key and value version fingerprints differ");
		}

		public String normalizedIdentity() {
			return fields(key.normalizedSignature(), kind.name(), valueVersion.normalizedSignature(),
				Boolean.toString(emittedWork), anchorSignatures(anchors));
		}

		@Override
		public int compareTo(Node that) {
			return key.compareTo(that.key);
		}
	}

	public record Constraint(ConstraintKind kind, CompiledHopKey left, CompiledHopKey right,
		int inputPosition, String evidence)
		implements Comparable<Constraint> {
		public Constraint(ConstraintKind kind, CompiledHopKey left, CompiledHopKey right) {
			this(kind, left, right, -1, "structural");
		}

		public Constraint {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(left, "left");
			Objects.requireNonNull(right, "right");
			if(inputPosition < -1)
				throw new IllegalArgumentException("inputPosition must be -1 or non-negative");
			evidence = evidence == null ? "" : evidence;
		}

		public String normalizedSignature() {
			return fields(kind.name(), left.normalizedSignature(), right.normalizedSignature(),
				Integer.toString(inputPosition), evidence);
		}

		@Override
		public int compareTo(Constraint that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public record RelocationAction(RelocationActionKey key, List<ObligationKey> obligations)
		implements Comparable<RelocationAction> {

		public RelocationAction {
			Objects.requireNonNull(key, "key");
			obligations = sorted(obligations, "obligations");
			if(obligations.isEmpty())
				throw new IllegalArgumentException("A relocation action requires obligations");
			for(ObligationKey obligation : obligations)
				if(!key.equals(obligation.relocationAction()))
					throw new IllegalArgumentException("Obligation refers to a different relocation action");
		}

		public String normalizedSignature() {
			return key.normalizedSignature();
		}

		@Override
		public int compareTo(RelocationAction that) {
			return key.compareTo(that.key);
		}
	}

	private final List<Node> nodes;
	private final List<Constraint> constraints;
	private final List<RelocationAction> relocationActions;
	private final Map<CompiledHopKey, Node> nodesByKey;

	public NeutralPlacementGraph(Collection<Node> nodes, Collection<Constraint> constraints,
		Collection<RelocationAction> relocationActions) {
		this.nodes = sorted(nodes, "nodes");
		this.constraints = sorted(constraints, "constraints");
		this.relocationActions = sorted(relocationActions, "relocationActions");
		nodesByKey = indexNodes(this.nodes);
		validateReferences();
	}

	public List<Node> nodes() {
		return nodes;
	}

	public List<Constraint> constraints() {
		return constraints;
	}

	public List<RelocationAction> relocationActions() {
		return relocationActions;
	}

	public Optional<Node> node(CompiledHopKey key) {
		return Optional.ofNullable(nodesByKey.get(Objects.requireNonNull(key, "key")));
	}

	public List<String> normalizedCandidateUniverse() {
		List<String> normalized = new ArrayList<>();
		for(Node node : nodes)
			for(PlacementState state : node.legalAlternatives())
				normalized.add(fields(node.key().normalizedSignature(), state.normalizedSignature()));
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedIdentities() {
		List<String> normalized = new ArrayList<>(nodes.size());
		for(Node node : nodes)
			normalized.add(node.normalizedIdentity());
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedValueVersions() {
		List<String> normalized = new ArrayList<>(nodes.size());
		for(Node node : nodes)
			normalized.add(fields(node.key().normalizedSignature(),
				node.valueVersion().normalizedSignature()));
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedProvenance() {
		List<String> normalized = new ArrayList<>(nodes.size());
		for(Node node : nodes)
			normalized.add(fields(node.key().normalizedSignature(),
				node.key().canonicalSourceOrigin(), node.key().controlRegion().normalizedSignature(),
				node.valueVersion().normalizedSignature()));
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedAnchors() {
		List<String> normalized = new ArrayList<>();
		for(Node node : nodes)
			for(DurableAnchorKey anchor : node.anchors())
				normalized.add(fields("NODE", node.key().normalizedSignature(),
					anchor.normalizedSignature()));
		for(RelocationAction action : relocationActions)
			normalized.add(fields("RELOCATION", action.key().normalizedSignature(),
				action.key().durableAnchor().normalizedSignature()));
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedConstraints() {
		List<String> normalized = new ArrayList<>(constraints.size());
		for(Constraint constraint : constraints)
			normalized.add(constraint.normalizedSignature());
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedExclusions() {
		List<String> normalized = new ArrayList<>();
		for(Node node : nodes)
			for(Exclusion exclusion : node.exclusions())
				normalized.add(fields(node.key().normalizedSignature(), exclusion.normalizedSignature()));
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedRelocationActions() {
		List<String> normalized = new ArrayList<>(relocationActions.size());
		for(RelocationAction action : relocationActions)
			normalized.add(action.normalizedSignature());
		return immutableSortedStrings(normalized);
	}

	public List<String> normalizedObligations() {
		List<String> normalized = new ArrayList<>();
		for(RelocationAction action : relocationActions)
			for(ObligationKey obligation : action.obligations())
				normalized.add(obligation.normalizedSignature());
		return immutableSortedStrings(normalized);
	}

	/**
	 * Exhaustively enumerates legal assignments for bounded shadow fixtures.
	 * Production callers should compare the normalized graph surfaces instead.
	 */
	public List<String> normalizedLegalAssignments() {
		List<Node> active = new ArrayList<>();
		for(Node node : nodes)
			if(!node.legalAlternatives().isEmpty())
				active.add(node);
		List<String> assignments = new ArrayList<>();
		enumerateAssignments(active, 0, new LinkedHashMap<>(), assignments);
		return immutableSortedStrings(assignments);
	}

	public String normalizedSignature() {
		return section("CANDIDATES", normalizedCandidateUniverse())
			+ section("IDENTITIES", normalizedIdentities())
			+ section("VALUE_VERSIONS", normalizedValueVersions())
			+ section("PROVENANCE", normalizedProvenance())
			+ section("ANCHORS", normalizedAnchors())
			+ section("CONSTRAINTS", normalizedConstraints())
			+ section("EXCLUSIONS", normalizedExclusions())
			+ section("RELOCATIONS", normalizedRelocationActions())
			+ section("OBLIGATIONS", normalizedObligations());
	}

	public String normalizedSignatureWithLegalAssignments() {
		return normalizedSignature() + section("LEGAL_ASSIGNMENTS", normalizedLegalAssignments());
	}

	private void enumerateAssignments(List<Node> active, int index,
		Map<CompiledHopKey, PlacementState> assignment, List<String> assignments) {
		if(index == active.size()) {
			if(satisfiesAssignmentConstraints(assignment))
				assignments.add(normalizeAssignment(assignment));
			return;
		}
		Node node = active.get(index);
		for(PlacementState state : node.legalAlternatives()) {
			assignment.put(node.key(), state);
			enumerateAssignments(active, index + 1, assignment, assignments);
		}
		assignment.remove(node.key());
	}

	private boolean satisfiesAssignmentConstraints(Map<CompiledHopKey, PlacementState> assignment) {
		for(Constraint constraint : constraints) {
			PlacementState left = assignment.get(constraint.left());
			PlacementState right = assignment.get(constraint.right());
			if(left == null || right == null)
				continue;
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT && !left.equals(right))
				return false;
			if(constraint.kind() == ConstraintKind.SAME_FTYPE
				&& !Objects.equals(left.fType(), right.fType()))
				return false;
			if(constraint.kind() == ConstraintKind.CONJUNCTIVE && right.output() ==
				org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& (left.output() != org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
					|| !Objects.equals(left.fType(), right.fType())))
				return false;
		}
		return true;
	}

	private static String normalizeAssignment(Map<CompiledHopKey, PlacementState> assignment) {
		List<String> entries = new ArrayList<>(assignment.size());
		for(Map.Entry<CompiledHopKey, PlacementState> entry : assignment.entrySet())
			entries.add(fields(entry.getKey().normalizedSignature(),
				entry.getValue().normalizedSignature()));
		return signatures(entries);
	}

	private static Map<CompiledHopKey, Node> indexNodes(List<Node> nodes) {
		Map<CompiledHopKey, Node> indexed = new LinkedHashMap<>();
		for(Node node : nodes)
			if(indexed.put(node.key(), node) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop key: " + node.key());
		return Collections.unmodifiableMap(indexed);
	}

	private void validateReferences() {
		Set<ValueVersionKey> valueVersions = new LinkedHashSet<>();
		Set<RelocationActionKey> relocationKeys = new LinkedHashSet<>();
		for(Node node : nodes)
			valueVersions.add(node.valueVersion());
		for(Constraint constraint : constraints) {
			requireNode(constraint.left(), "constraint left");
			requireNode(constraint.right(), "constraint right");
		}
		for(RelocationAction action : relocationActions) {
			RelocationActionKey key = action.key();
			if(!relocationKeys.add(key))
				throw new IllegalArgumentException("Duplicate relocation action key: " + key);
			if(!valueVersions.contains(key.sourceValueVersion()))
				throw new IllegalArgumentException("Relocation source value is absent from graph");
			for(CompiledHopKey consumer : key.compatibleConsumers())
				requireNode(consumer, "relocation consumer");
			for(ObligationKey obligation : action.obligations()) {
				requireNode(obligation.consumer(), "obligation consumer");
				if(!valueVersions.contains(obligation.sourceValueVersion()))
					throw new IllegalArgumentException("Obligation source value is absent from graph");
			}
		}
	}

	private void requireNode(CompiledHopKey key, String role) {
		if(!nodesByKey.containsKey(key))
			throw new IllegalArgumentException(role + " is absent from graph: " + key);
	}

	private static <T extends Comparable<? super T>> List<T> sorted(Collection<T> values,
		String name) {
		Objects.requireNonNull(values, name);
		List<T> copy = new ArrayList<>(values.size());
		for(T value : values)
			copy.add(Objects.requireNonNull(value, name + " entry"));
		copy.sort(Comparator.naturalOrder());
		for(int i = 1; i < copy.size(); i++)
			if(copy.get(i - 1).equals(copy.get(i)))
				throw new IllegalArgumentException(name + " contains duplicates");
		return List.copyOf(copy);
	}

	private static List<String> immutableSortedStrings(Collection<String> values) {
		List<String> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return List.copyOf(copy);
	}

	private static String anchorSignatures(Collection<DurableAnchorKey> anchors) {
		List<String> normalized = new ArrayList<>(anchors.size());
		for(DurableAnchorKey anchor : anchors)
			normalized.add(anchor.normalizedSignature());
		return signatures(normalized);
	}

	private static String section(String name, List<String> values) {
		return name + "[" + signatures(values) + "]\n";
	}

	private static String fields(String... values) {
		List<String> encoded = new ArrayList<>(values.length);
		for(String value : values)
			encoded.add(token(value));
		return String.join("|", encoded);
	}

	private static String signatures(Collection<String> values) {
		List<String> encoded = new ArrayList<>(values.size());
		for(String value : values)
			encoded.add(token(value));
		return String.join(",", encoded);
	}

	private static String token(String value) {
		return value.length() + ":" + value;
	}
}

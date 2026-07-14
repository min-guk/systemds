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

package org.apache.sysds.test.component.federated.placement.oracle.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only semantic oracle for the candidate universe emitted by a future
 * unified placement-graph builder. This class intentionally has no dependency
 * on a production planner, builder, selector, or rules facade.
 */
public final class BuilderOracle {
	public enum Exec { CP, FED }
	public enum Output { LOUT, FOUT }
	public enum FType { ROW, COL, BROADCAST, PART, UNKNOWN }
	public enum Kind { OP, TREAD, TWRITE, JOIN, PHI, CALL, FUNCTION_INPUT, FUNCTION_OUTPUT, CLONE, ROOT }
	public enum ConstraintKind { SAME_PLACEMENT, SAME_FTYPE, DOMINATES, CONJUNCTIVE, DISTINCT_CONTEXT }
	public enum Reason {
		UNREACHABLE_BRANCH,
		MISSING_ANCHOR,
		UNSUPPORTED_ANCHOR,
		PRIVACY,
		UNSUPPORTED_OPERATION_SHAPE,
		ILLEGAL_TRANSIENT_PLACEMENT,
		RECOMPILE_CP_FOUT,
		UNKNOWN_METADATA,
		UNSATISFIED_CONJUNCTIVE_INPUT
	}

	public static final class Placement implements Comparable<Placement> {
		public final Exec exec;
		public final Output output;
		public final FType fType;
		public final boolean shapeDependent;

		private Placement(Exec exec, Output output, FType fType, boolean shapeDependent) {
			this.exec = exec;
			this.output = output;
			this.fType = fType;
			this.shapeDependent = shapeDependent;
		}

		public static Placement cpLout() { return new Placement(Exec.CP, Output.LOUT, null, false); }
		public static Placement cpFout(FType type) { return new Placement(Exec.CP, Output.FOUT, type, true); }
		public static Placement fedLout(FType type) { return new Placement(Exec.FED, Output.LOUT, type, true); }
		public static Placement fedLoutShapeIndependent(FType type) {
			return new Placement(Exec.FED, Output.LOUT, type, false);
		}
		public static Placement fedFout(FType type) { return new Placement(Exec.FED, Output.FOUT, type, true); }

		@Override
		public int compareTo(Placement that) { return signature().compareTo(that.signature()); }

		public String signature() {
			return exec + "/" + output + (fType == null ? "" : "/" + fType);
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Placement)) return false;
			Placement that = (Placement) obj;
			return exec == that.exec && output == that.output && fType == that.fType;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * exec.hashCode() + output.hashCode()) + (fType == null ? 0 : fType.hashCode());
		}

		@Override
		public String toString() { return signature(); }
	}

	public static final class Node {
		public final String id;
		public final Kind kind;
		public final String valueVersion;
		public final String originId;
		public final String contextId;
		public final boolean emittedWork;
		public final boolean reachable;
		private final Set<Placement> candidates;
		private final Map<Placement, Reason> exclusions;

		private Node(NodeSpec spec) {
			id = spec.id;
			kind = spec.kind;
			valueVersion = spec.valueVersion;
			originId = spec.originId;
			contextId = spec.contextId;
			emittedWork = spec.emittedWork;
			reachable = spec.reachable;
			candidates = new LinkedHashSet<>(spec.candidates);
			exclusions = new LinkedHashMap<>();
		}

		public Set<Placement> candidates() { return Collections.unmodifiableSet(candidates); }
		public Map<Placement, Reason> exclusions() { return Collections.unmodifiableMap(exclusions); }
	}

	private static final class NodeSpec {
		private final String id;
		private final Kind kind;
		private String valueVersion;
		private String originId;
		private String contextId = "global";
		private boolean emittedWork = true;
		private boolean reachable = true;
		private boolean privacyProtected;
		private boolean operationShapeSupported = true;
		private boolean metadataKnown = true;
		private boolean recompile;
		private String anchorId;
		private FType anchorType;
		private final Set<Placement> candidates = new LinkedHashSet<>();

		private NodeSpec(String id, Kind kind) {
			this.id = id;
			this.kind = kind;
			this.valueVersion = id;
			this.originId = id;
		}
	}

	public static final class Constraint {
		public final ConstraintKind kind;
		public final String left;
		public final String right;

		private Constraint(ConstraintKind kind, String left, String right) {
			this.kind = kind;
			this.left = left;
			this.right = right;
		}

		public String signature() { return kind + ":" + left + "->" + right; }
	}

	public static final class Relocation {
		public final String id;
		public final String source;
		public final String anchor;
		private final Set<String> obligations = new LinkedHashSet<>();

		private Relocation(String id, String source, String anchor) {
			this.id = id;
			this.source = source;
			this.anchor = anchor;
		}

		private Relocation(Relocation that) {
			this(that.id, that.source, that.anchor);
			obligations.addAll(that.obligations);
		}

		public Set<String> obligations() { return Collections.unmodifiableSet(obligations); }
	}

	public static final class Graph {
		private final Map<String, Node> nodes;
		private final List<Constraint> constraints;
		private final Map<String, Relocation> relocations;

		private Graph(Map<String, Node> nodes, List<Constraint> constraints,
			Map<String, Relocation> relocations) {
			this.nodes = Collections.unmodifiableMap(nodes);
			this.constraints = Collections.unmodifiableList(constraints);
			Map<String, Relocation> relocationCopy = new LinkedHashMap<>();
			for (Map.Entry<String, Relocation> entry : relocations.entrySet())
				relocationCopy.put(entry.getKey(), new Relocation(entry.getValue()));
			this.relocations = Collections.unmodifiableMap(relocationCopy);
		}

		public Node node(String id) { return nodes.get(id); }
		public Collection<Node> nodes() { return nodes.values(); }
		public List<Constraint> constraints() { return constraints; }
		public Map<String, Relocation> relocations() { return relocations; }
		public Set<String> legalAssignments() {
			List<Node> active = new ArrayList<>();
			for (Node node : nodes.values()) {
				if (node.candidates.isEmpty()) {
					if (node.reachable) return Collections.emptySet();
				}
				else active.add(node);
			}
			Set<String> assignments = new LinkedHashSet<>();
			enumerate(active, 0, new LinkedHashMap<String, Placement>(), assignments);
			return assignments;
		}

		public Set<String> normalizedCandidateUniverse() {
			Set<String> out = new LinkedHashSet<>();
			for (Node node : nodes.values()) {
				List<Placement> sorted = new ArrayList<>(node.candidates);
				Collections.sort(sorted);
				for (Placement placement : sorted)
					out.add(node.id + "=" + placement.signature());
			}
			return out;
		}

		private void enumerate(List<Node> active, int index, Map<String, Placement> assignment,
			Set<String> assignments) {
			if (index == active.size()) {
				if (satisfiesConstraints(assignment)) assignments.add(normalize(assignment));
				return;
			}
			Node node = active.get(index);
			List<Placement> candidates = new ArrayList<>(node.candidates);
			Collections.sort(candidates);
			for (Placement candidate : candidates) {
				assignment.put(node.id, candidate);
				enumerate(active, index + 1, assignment, assignments);
			}
			assignment.remove(node.id);
		}

		private boolean satisfiesConstraints(Map<String, Placement> assignment) {
			for (Constraint constraint : constraints) {
				Placement left = assignment.get(constraint.left);
				Placement right = assignment.get(constraint.right);
				if (left == null || right == null) continue;
				if (constraint.kind == ConstraintKind.SAME_PLACEMENT && !left.equals(right)) return false;
				if (constraint.kind == ConstraintKind.SAME_FTYPE && left.fType != right.fType) return false;
				if (constraint.kind == ConstraintKind.CONJUNCTIVE && !conjunctivelyCompatible(left, right))
					return false;
			}
			return true;
		}

		private static String normalize(Map<String, Placement> assignment) {
			List<String> entries = new ArrayList<>();
			for (Map.Entry<String, Placement> entry : assignment.entrySet())
				entries.add(entry.getKey() + "=" + entry.getValue().signature());
			Collections.sort(entries);
			return String.join(";", entries);
		}
	}

	public static final class Builder {
		private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();
		private final List<Constraint> constraints = new ArrayList<>();
		private final Map<String, Relocation> relocations = new LinkedHashMap<>();

		public Builder node(String id, Kind kind, Placement... candidates) {
			if (nodes.containsKey(id)) throw new IllegalArgumentException("Duplicate node " + id);
			NodeSpec spec = new NodeSpec(id, kind);
			Collections.addAll(spec.candidates, candidates);
			nodes.put(id, spec);
			return this;
		}

		public Builder identity(String id, String version, String origin, String context) {
			NodeSpec spec = require(id);
			spec.valueVersion = version;
			spec.originId = origin;
			spec.contextId = context;
			return this;
		}

		public Builder reachable(String id, boolean value) { require(id).reachable = value; return this; }
		public Builder emitted(String id, boolean value) { require(id).emittedWork = value; return this; }
		public Builder privacy(String id) { require(id).privacyProtected = true; return this; }
		public Builder unsupportedShape(String id) { require(id).operationShapeSupported = false; return this; }
		public Builder unknownMetadata(String id) { require(id).metadataKnown = false; return this; }
		public Builder recompile(String id) { require(id).recompile = true; return this; }

		public Builder anchor(String id, String anchorId, FType anchorType) {
			NodeSpec spec = require(id);
			spec.anchorId = anchorId;
			spec.anchorType = anchorType;
			return this;
		}

		public Builder constraint(ConstraintKind kind, String left, String right) {
			require(left);
			require(right);
			constraints.add(new Constraint(kind, left, right));
			return this;
		}

		public Builder relocation(String materializationId, String source, String anchor, String obligation) {
			Relocation relocation = relocations.get(materializationId);
			if (relocation == null) {
				relocation = new Relocation(materializationId, source, anchor);
				relocations.put(materializationId, relocation);
			}
			else if (!relocation.source.equals(source) || !relocation.anchor.equals(anchor))
				throw new IllegalArgumentException("Conflicting relocation identity " + materializationId);
			relocation.obligations.add(obligation);
			return this;
		}

		public Graph build() {
			Map<String, Node> result = new LinkedHashMap<>();
			for (NodeSpec spec : nodes.values()) {
				Node node = new Node(spec);
				applyUnaryRules(spec, node);
				result.put(node.id, node);
			}
			closeConstraints(result);
			List<Constraint> ordered = new ArrayList<>(constraints);
			ordered.sort(Comparator.comparing(Constraint::signature));
			return new Graph(result, ordered, new LinkedHashMap<>(relocations));
		}

		private static void applyUnaryRules(NodeSpec spec, Node node) {
			List<Placement> snapshot = new ArrayList<>(node.candidates);
			for (Placement placement : snapshot) {
				Reason reason = null;
				if (!spec.reachable) reason = Reason.UNREACHABLE_BRANCH;
				else if ((spec.kind == Kind.TREAD || spec.kind == Kind.TWRITE)
					&& !((placement.exec == Exec.CP && placement.output == Output.LOUT)
						|| (placement.exec == Exec.FED && placement.output == Output.FOUT)))
					reason = Reason.ILLEGAL_TRANSIENT_PLACEMENT;
				else if (spec.privacyProtected && placement.exec == Exec.FED) reason = Reason.PRIVACY;
				else if (!spec.operationShapeSupported && placement.exec == Exec.FED)
					reason = Reason.UNSUPPORTED_OPERATION_SHAPE;
				else if (!spec.metadataKnown && placement.shapeDependent) reason = Reason.UNKNOWN_METADATA;
				else if (spec.recompile && placement.exec == Exec.CP && placement.output == Output.FOUT)
					reason = Reason.RECOMPILE_CP_FOUT;
				else if (placement.exec == Exec.CP && placement.output == Output.FOUT) {
					if (spec.anchorId == null) reason = Reason.MISSING_ANCHOR;
					else if (spec.anchorType == FType.PART || spec.anchorType == FType.UNKNOWN)
						reason = Reason.UNSUPPORTED_ANCHOR;
				}
				if (reason != null) exclude(node, placement, reason);
			}
		}

		private void closeConstraints(Map<String, Node> result) {
			Deque<Constraint> work = new ArrayDeque<>(constraints);
			while (!work.isEmpty()) {
				Constraint constraint = work.removeFirst();
				Node left = result.get(constraint.left);
				Node right = result.get(constraint.right);
				boolean changed = false;
				if (constraint.kind == ConstraintKind.SAME_PLACEMENT)
					changed = retainCompatible(left, right, false) | retainCompatible(right, left, false);
				else if (constraint.kind == ConstraintKind.SAME_FTYPE)
					changed = retainCompatible(left, right, true) | retainCompatible(right, left, true);
				else if (constraint.kind == ConstraintKind.CONJUNCTIVE)
					changed = retainConjunctivelyCompatible(right, left);
				if (changed)
					work.addAll(constraints);
			}
		}

		private static boolean retainCompatible(Node target, Node other, boolean fTypeOnly) {
			boolean changed = false;
			for (Placement candidate : new ArrayList<>(target.candidates)) {
				boolean match = false;
				for (Placement peer : other.candidates) {
					match |= fTypeOnly ? candidate.fType == peer.fType : candidate.equals(peer);
				}
				if (!match) {
					exclude(target, candidate, Reason.UNSUPPORTED_OPERATION_SHAPE);
					changed = true;
				}
			}
			return changed;
		}

		private static boolean retainConjunctivelyCompatible(Node target, Node predecessor) {
			boolean changed = false;
			for (Placement candidate : new ArrayList<>(target.candidates)) {
				boolean match = false;
				for (Placement input : predecessor.candidates)
					match |= conjunctivelyCompatible(input, candidate);
				if (!match) {
					exclude(target, candidate, Reason.UNSATISFIED_CONJUNCTIVE_INPUT);
					changed = true;
				}
			}
			return changed;
		}

		private static boolean conjunctivelyCompatible(Placement predecessor, Placement target) {
			return target.output != Output.FOUT || (predecessor.output == Output.FOUT
				&& predecessor.fType == target.fType);
		}

		private static void exclude(Node node, Placement placement, Reason reason) {
			node.candidates.remove(placement);
			node.exclusions.put(placement, reason);
		}

		private NodeSpec require(String id) {
			NodeSpec spec = nodes.get(id);
			if (spec == null) throw new IllegalArgumentException("Unknown node " + id);
			return spec;
		}
	}

	private BuilderOracle() {
		// utility class
	}
}

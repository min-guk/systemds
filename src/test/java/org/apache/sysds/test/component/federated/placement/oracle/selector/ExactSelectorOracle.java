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

package org.apache.sysds.test.component.federated.placement.oracle.selector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Execution;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Output;

/** Exhaustive, bounded selector oracle used only by independent tests. */
public final class ExactSelectorOracle {
	public enum Policy {
		FED_ALL,
		HEURISTIC
	}

	public enum TerminationReason {
		EXHAUSTED,
		TIGHT_BOUND_EQUALITY
	}

	public static final class Score implements Comparable<Score> {
		private final int fedCount;
		private final int foutCount;
		private final int relocationCount;
		private final String signature;

		private Score(int fedCount, int foutCount, int relocationCount, String signature) {
			this.fedCount = fedCount;
			this.foutCount = foutCount;
			this.relocationCount = relocationCount;
			this.signature = signature;
		}

		public int getFedCount() {
			return fedCount;
		}

		public int getFoutCount() {
			return foutCount;
		}

		public int getRelocationCount() {
			return relocationCount;
		}

		public String getSignature() {
			return signature;
		}

		@Override
		public int compareTo(Score that) {
			int comparison = Integer.compare(fedCount, that.fedCount);
			if (comparison != 0)
				return comparison;
			comparison = Integer.compare(foutCount, that.foutCount);
			if (comparison != 0)
				return comparison;
			comparison = Integer.compare(that.relocationCount, relocationCount);
			if (comparison != 0)
				return comparison;
			return that.signature.compareTo(signature);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof Score))
				return false;
			Score that = (Score) obj;
			return fedCount == that.fedCount && foutCount == that.foutCount &&
				relocationCount == that.relocationCount && signature.equals(that.signature);
		}

		@Override
		public int hashCode() {
			return Objects.hash(fedCount, foutCount, relocationCount, signature);
		}

		@Override
		public String toString() {
			return "Score{fed=" + fedCount + ",fout=" + foutCount + ",relocations=" + relocationCount +
				",signature='" + signature + "'}";
		}
	}

	public static final class Certificate {
		private final Score incumbentScore;
		private final Score finalUpperBound;
		private final long exploredCount;
		private final long prunedCount;
		private final String assignmentHash;
		private final int graphNodeCount;
		private final int graphEdgeCount;
		private final int componentCount;
		private final String boundDerivation;
		private final String generatorSizeClass;
		private final long seed;
		private final TerminationReason terminationReason;

		private Certificate(Score incumbentScore, long exploredCount, long prunedCount, String assignmentHash,
			ExplicitSelectorGraph graph) {
			this.incumbentScore = incumbentScore;
			this.finalUpperBound = incumbentScore;
			this.exploredCount = exploredCount;
			this.prunedCount = prunedCount;
			this.assignmentHash = assignmentHash;
			this.graphNodeCount = graph.getNodes().size();
			this.graphEdgeCount = graph.getEdgeCount();
			this.componentCount = graph.getComponentCount();
			this.boundDerivation = "complete-cartesian-enumeration-with-partial-legality-pruning";
			this.generatorSizeClass = graph.getSizeClass();
			this.seed = graph.getSeed();
			this.terminationReason = TerminationReason.EXHAUSTED;
		}

		public Score getIncumbentScore() {
			return incumbentScore;
		}

		public Score getFinalUpperBound() {
			return finalUpperBound;
		}

		public long getExploredCount() {
			return exploredCount;
		}

		public long getPrunedCount() {
			return prunedCount;
		}

		public String getAssignmentHash() {
			return assignmentHash;
		}

		public int getGraphNodeCount() {
			return graphNodeCount;
		}

		public int getGraphEdgeCount() {
			return graphEdgeCount;
		}

		public int getComponentCount() {
			return componentCount;
		}

		public String getBoundDerivation() {
			return boundDerivation;
		}

		public String getGeneratorSizeClass() {
			return generatorSizeClass;
		}

		public long getSeed() {
			return seed;
		}

		public TerminationReason getTerminationReason() {
			return terminationReason;
		}
	}

	public static final class Result {
		private final Map<String, Choice> assignment;
		private final Score score;
		private final Certificate certificate;

		private Result(Map<String, Choice> assignment, Score score, Certificate certificate) {
			this.assignment = Collections.unmodifiableMap(new LinkedHashMap<>(assignment));
			this.score = score;
			this.certificate = certificate;
		}

		public Map<String, Choice> getAssignment() {
			return assignment;
		}

		public Score getScore() {
			return score;
		}

		public Certificate getCertificate() {
			return certificate;
		}
	}

	private ExactSelectorOracle() {
	}

	public static Result select(ExplicitSelectorGraph graph, Policy policy) {
		Search search = new Search(graph, policy);
		search.enumerate(0);
		if (search.bestAssignment == null)
			throw new IllegalStateException("explicit selector graph has no legal assignment");
		Certificate certificate = new Certificate(search.bestScore, search.explored, search.pruned,
			sha256(search.bestScore.getSignature()), graph);
		return new Result(search.bestAssignment, search.bestScore, certificate);
	}

	public static Score score(Map<String, Choice> assignment) {
		int fedCount = 0;
		int foutCount = 0;
		Set<String> relocations = new LinkedHashSet<>();
		StringBuilder signature = new StringBuilder();
		for (Map.Entry<String, Choice> entry : assignment.entrySet()) {
			Choice choice = entry.getValue();
			if (choice.getExecution() == Execution.FED)
				fedCount++;
			if (choice.getOutput() == Output.FOUT)
				foutCount++;
			relocations.addAll(choice.getRelocationActions());
			if (signature.length() > 0)
				signature.append('|');
			signature.append(entry.getKey()).append('=').append(choice.getId());
		}
		return new Score(fedCount, foutCount, relocations.size(), signature.toString());
	}

	private static final class Search {
		private final ExplicitSelectorGraph graph;
		private final Policy policy;
		private final LinkedHashMap<String, Choice> current = new LinkedHashMap<>();
		private Map<String, Choice> bestAssignment;
		private Score bestScore;
		private long explored;
		private long pruned;

		private Search(ExplicitSelectorGraph graph, Policy policy) {
			this.graph = Objects.requireNonNull(graph);
			this.policy = Objects.requireNonNull(policy);
		}

		private void enumerate(int nodeIndex) {
			if (nodeIndex == graph.getNodes().size()) {
				explored++;
				if (!graph.isLegal(current)) {
					pruned++;
					return;
				Score candidateScore = score(current);
				if (bestScore == null || candidateScore.compareTo(bestScore) > 0) {
					bestScore = candidateScore;
					bestAssignment = new LinkedHashMap<>(current);
				}
				return;
			}

			Node node = graph.getNodes().get(nodeIndex);
			for (Choice choice : node.getChoices()) {
				if (policy == Policy.HEURISTIC && !node.isHeuristicAllowed(choice)) {
					pruned++;
					continue;
				}
				current.put(node.getId(), choice);
				if (graph.canStillBeLegal(current))
					enumerate(nodeIndex + 1);
				else
					pruned++;
				current.remove(node.getId());
			}
		}
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(digest.length * 2);
			for (byte b : digest)
				result.append(String.format("%02x", b));
			return result.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JVM must provide SHA-256", e);
		}
	}
}

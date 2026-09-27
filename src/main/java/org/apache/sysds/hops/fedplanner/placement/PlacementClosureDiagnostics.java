/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Optional closure diagnostics that observe, but never mutate, semantic placement state. */
final class PlacementClosureDiagnostics {
	private static final int COMPOSITION_RECURRENCE_WINDOW = 8;
	private static final String PHASE_MARKERS_PROPERTY = "sysds.fedplanner.phaseMarkers";

	private PlacementClosureDiagnostics() {
		// utility class
	}

	private static final class RecurrenceContextEntry {
		private final Object key;
		private final Object value;

		private RecurrenceContextEntry(Object key, Object value) {
			this.key = Objects.requireNonNull(key, "recurrence context key");
			this.value = Objects.requireNonNull(value, "recurrence context value");
		}

		@Override public boolean equals(Object other) {
			return this == other || other instanceof RecurrenceContextEntry that
				&& key == that.key && value.equals(that.value);
		}

		@Override public int hashCode() {
			// Diagnostics compare entry lists directly and must not hash rich replay values.
			return System.identityHashCode(key);
		}
	}

	private record CompositionRecurrenceState(int completedPass, List<?> nodes,
		List<?> domain, List<?> facts, List<?> logical, List<?> publishedActions,
		List<?> pendingPhysicalRebuildOrdinals, int ledgerSize,
		List<RecurrenceContextEntry> generationBasesByOccurrence) {
		private CompositionRecurrenceState {
			nodes = List.copyOf(nodes);
			domain = List.copyOf(domain);
			facts = List.copyOf(facts);
			logical = List.copyOf(logical);
			publishedActions = List.copyOf(publishedActions);
			pendingPhysicalRebuildOrdinals = List.copyOf(pendingPhysicalRebuildOrdinals);
			generationBasesByOccurrence = List.copyOf(generationBasesByOccurrence);
		}

		private boolean samePublication(CompositionRecurrenceState that) {
			return nodes.equals(that.nodes) && domain.equals(that.domain)
				&& facts.equals(that.facts) && logical.equals(that.logical)
				&& publishedActions.equals(that.publishedActions)
				&& pendingPhysicalRebuildOrdinals.equals(that.pendingPhysicalRebuildOrdinals);
		}

		private boolean sameReplayContext(CompositionRecurrenceState that) {
			return ledgerSize == that.ledgerSize
				&& generationBasesByOccurrence.equals(that.generationBasesByOccurrence);
		}
	}

	record CompositionRecurrenceObservation(int completedPass, int publicationPreviousPass,
		int fullContextPreviousPass, boolean nodesSame, boolean domainSame, boolean factsSame,
		boolean logicalSame, boolean actionsSame, boolean pendingSame, int ledgerSize,
		int baseSize, long diagnosticNanos) {
		boolean publicationRecurrence() { return publicationPreviousPass >= 0; }
		boolean fullContextRecurrence() { return fullContextPreviousPass >= 0; }
		int repeatPeriod() { return completedPass - publicationPreviousPass; }
	}

	static final class CompositionRecurrenceTracker {
		private final ArrayDeque<CompositionRecurrenceState> states = new ArrayDeque<>();

		static CompositionRecurrenceTracker enabled(SearchSpaceMetrics metrics) {
			return metrics != null && Boolean.getBoolean("sysds.fedplanner.cycleDiagnostics")
				? new CompositionRecurrenceTracker() : null;
		}

		CompositionRecurrenceObservation observe(int completedPass, List<?> nodes,
			List<?> domain, List<?> facts, List<?> logical, List<?> publishedActions,
			List<?> pendingPhysicalRebuildOrdinals, int ledgerSize, Map<?,?> generationBasesByOccurrence) {
			long started = System.nanoTime();
			List<RecurrenceContextEntry> bases = new ArrayList<>(generationBasesByOccurrence.size());
			for(Map.Entry<?,?> entry : generationBasesByOccurrence.entrySet())
				bases.add(new RecurrenceContextEntry(entry.getKey(), entry.getValue()));
			CompositionRecurrenceState current = new CompositionRecurrenceState(completedPass,
				nodes, domain, facts, logical, publishedActions, pendingPhysicalRebuildOrdinals, ledgerSize, bases);
			CompositionRecurrenceState publicationMatch = null;
			CompositionRecurrenceState fullContextMatch = null;
			for(java.util.Iterator<CompositionRecurrenceState> iterator = states.descendingIterator();
				iterator.hasNext();) {
				CompositionRecurrenceState prior = iterator.next();
				if(current.samePublication(prior)) {
					if(publicationMatch == null)
						publicationMatch = prior;
					if(current.sameReplayContext(prior)) {
						fullContextMatch = prior;
						break;
					}
				}
			}
			states.addLast(current);
			if(states.size() > COMPOSITION_RECURRENCE_WINDOW)
				states.removeFirst();
			if(publicationMatch == null)
				return null;
			long elapsed = System.nanoTime() - started;
			return new CompositionRecurrenceObservation(completedPass,
				publicationMatch.completedPass(),
				fullContextMatch == null ? -1 : fullContextMatch.completedPass(),
				current.nodes().equals(publicationMatch.nodes()),
				current.domain().equals(publicationMatch.domain()),
				current.facts().equals(publicationMatch.facts()),
				current.logical().equals(publicationMatch.logical()),
				current.publishedActions().equals(publicationMatch.publishedActions()),
				current.pendingPhysicalRebuildOrdinals().equals(publicationMatch.pendingPhysicalRebuildOrdinals()),
				ledgerSize, bases.size(), elapsed);
		}

		int retainedStates() { return states.size(); }
	}

	static final class ExportDeltaDiagnostics {
		private static final String PROPERTY = "sysds.fedplanner.exportDiagnostics";
		private static final int OWNER_LIMIT = 8;
		private static final int RULE_LIMIT = 3;
		private final Map<CompiledHopKey,Hop> origins;
		private List<Node> phaseNodes = List.of();
		private List<CandidateRuleFact> phaseFacts = List.of();
		private Set<CompiledHopKey> trackedRoots = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CompiledHopKey,List<OwnerDelta>> passOwnerDeltas = new IdentityHashMap<>();
		private record OwnerDelta(String phase, Node beforeNode, Node afterNode,
			List<CandidateRuleFact> beforeFacts, List<CandidateRuleFact> afterFacts) { }

		private ExportDeltaDiagnostics(Map<CompiledHopKey,Hop> origins) {
			this.origins = origins;
		}

		static ExportDeltaDiagnostics enabled(SearchSpaceMetrics metrics,
			Map<CompiledHopKey,Hop> origins) {
			return metrics != null && Boolean.getBoolean(PROPERTY)
				? new ExportDeltaDiagnostics(Objects.requireNonNull(origins, "origins")) : null;
		}

		void beginPass(List<Node> nodes, List<CandidateRuleFact> facts) {
			phaseNodes = nodes;
			phaseFacts = facts;
			passOwnerDeltas.clear();
		}

		void phase(int pass, String phase, List<Node> nodes, List<CandidateRuleFact> facts) {
			emit(pass, phase, phaseNodes, phaseFacts, nodes, facts, false);
			phaseNodes = nodes;
			phaseFacts = facts;
		}

		void finishPass(int pass, List<Node> priorNodes, List<CandidateRuleFact> priorFacts,
			List<Node> nodes, List<CandidateRuleFact> facts) {
			emit(pass, "pass-end", priorNodes, priorFacts, nodes, facts, true);
		}

		void logicalDelta(int pass, String phase, List<LogicalTransientInputFact> before,
			List<LogicalTransientInputFact> after) {
			if(before.equals(after))
				return;
			Map<String,LogicalTransientInputFact> prior = logicalRows(before);
			Map<String,LogicalTransientInputFact> current = logicalRows(after);
			Set<String> keys = new LinkedHashSet<>(prior.keySet());
			keys.addAll(current.keySet());
			System.err.println("SEARCH_SPACE_LOGICAL_DELTA|pass=" + pass + "|phase=" + phase
				+ "|relations=" + before.size() + "->" + after.size()
				+ "|edges=" + before.stream().mapToInt(row -> row.compatibility().size()).sum()
				+ "->" + after.stream().mapToInt(row -> row.compatibility().size()).sum());
			int emitted = 0;
			for(String key : keys) {
				LogicalTransientInputFact old = prior.get(key), now = current.get(key);
				if(Objects.equals(old, now))
					continue;
				LogicalTransientInputFact basis = now != null ? now : old;
				Set<TransientPlacementCompatibility> oldEdges = new LinkedHashSet<>(
					old == null ? List.of() : old.compatibility());
				Set<TransientPlacementCompatibility> newEdges = new LinkedHashSet<>(
					now == null ? List.of() : now.compatibility());
				List<TransientPlacementCompatibility> removed = oldEdges.stream()
					.filter(edge -> !newEdges.contains(edge)).toList();
				List<TransientPlacementCompatibility> added = newEdges.stream()
					.filter(edge -> !oldEdges.contains(edge)).toList();
				System.err.println("SEARCH_SPACE_LOGICAL_ROW|pass=" + pass + "|phase=" + phase
					+ "|source=" + basis.sourceWrite().callSitePath() + ':' + basis.sourceWrite().emittedHopInstance()
					+ "|reader=" + basis.targetRead().callSitePath() + ':' + basis.targetRead().emittedHopInstance()
					+ "|edges=" + oldEdges.size() + "->" + newEdges.size()
					+ "|removed=" + removed.size() + ':' + logicalEdges(removed)
					+ "|added=" + added.size() + ':' + logicalEdges(added));
				if(++emitted == OWNER_LIMIT)
					break;
			}
		}

		private static Map<String,LogicalTransientInputFact> logicalRows(List<LogicalTransientInputFact> facts) {
			Map<String,LogicalTransientInputFact> rows = new LinkedHashMap<>();
			for(LogicalTransientInputFact fact : facts)
				rows.put(fact.sourceWrite().normalizedSignature() + "->" + fact.targetRead().normalizedSignature(), fact);
			return rows;
		}

		private static List<String> logicalEdges(List<TransientPlacementCompatibility> edges) {
			return edges.stream().limit(3).map(edge ->
				"source=" + edge.sourceRealization().realization().layoutKind() + ':' + edge.sourceRealization().hashCode()
				+ ",reader=" + edge.readerRealization().realization().layoutKind() + ':' + edge.readerRealization().hashCode()
				+ ",inputs=" + edge.sourceInput().normalizedSignature() + "->" + edge.readerInput().normalizedSignature()
				+ ",proof=" + boundedIdentity(edge.proof().normalizedSignature())).toList();
		}

		private void emit(int pass, String phase, List<Node> beforeNodes,
			List<CandidateRuleFact> beforeFacts, List<Node> afterNodes,
			List<CandidateRuleFact> afterFacts, boolean passEnd) {
			Map<CompiledHopKey,Node> beforeNodeMap = nodesByOwner(beforeNodes);
			Map<CompiledHopKey,Node> afterNodeMap = nodesByOwner(afterNodes);
			Map<CompiledHopKey,List<CandidateRuleFact>> beforeFactMap = factsByOwner(beforeFacts);
			Map<CompiledHopKey,List<CandidateRuleFact>> afterFactMap = factsByOwner(afterFacts);
			List<CompiledHopKey> changed = new ArrayList<>();
			for(Node node : afterNodes) {
				CompiledHopKey owner = node.key();
				if(!Objects.equals(beforeNodeMap.get(owner), node)
					|| !Objects.equals(beforeFactMap.getOrDefault(owner, List.of()),
						afterFactMap.getOrDefault(owner, List.of())))
					changed.add(owner);
			}
			for(Node node : beforeNodes)
				if(!afterNodeMap.containsKey(node.key()))
					changed.add(node.key());
			if(changed.isEmpty())
				return;
			if(!passEnd)
				for(CompiledHopKey owner : changed)
					passOwnerDeltas.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(new OwnerDelta(
						phase, beforeNodeMap.get(owner), afterNodeMap.get(owner),
						beforeFactMap.getOrDefault(owner, List.of()), afterFactMap.getOrDefault(owner, List.of())));
			List<CompiledHopKey> trackedChanged = changed.stream()
				.filter(trackedRoots::contains).toList();
			System.err.println("SEARCH_SPACE_EXPORT_DELTA|pass=" + pass + "|phase=" + phase
				+ "|changedOwners=" + changed.size() + "|trackedRootsChanged="
				+ trackedChanged.stream().map(CompiledHopKey::emittedHopInstance).toList());
			List<CompiledHopKey> detailed = new ArrayList<>();
			for(CompiledHopKey owner : changed) {
				if(detailed.size() >= OWNER_LIMIT)
					break;
				detailed.add(owner);
			}
			for(CompiledHopKey owner : trackedChanged)
				if(!detailed.contains(owner) && detailed.size() < OWNER_LIMIT + trackedChanged.size())
					detailed.add(owner);
			for(CompiledHopKey owner : detailed)
				emitOwner(pass, phase, owner, beforeNodeMap.get(owner), afterNodeMap.get(owner),
					beforeFactMap.getOrDefault(owner, List.of()), afterFactMap.getOrDefault(owner, List.of()));
			if(passEnd && changed.size() <= 10) {
				for(CompiledHopKey owner : changed)
					for(OwnerDelta delta : passOwnerDeltas.getOrDefault(owner, List.of()))
						emitOwner(pass, delta.phase() + "-root-trace", owner,
							delta.beforeNode(), delta.afterNode(), delta.beforeFacts(), delta.afterFacts());
				Set<CompiledHopKey> roots = Collections.newSetFromMap(new IdentityHashMap<>());
				roots.addAll(changed);
				trackedRoots = roots;
				System.err.println("SEARCH_SPACE_EXPORT_ROOTS|pass=" + pass + "|owners="
					+ changed.stream().map(CompiledHopKey::emittedHopInstance).toList());
			}
		}

		private void emitOwner(int pass, String phase, CompiledHopKey owner,
			Node beforeNode, Node afterNode, List<CandidateRuleFact> before,
			List<CandidateRuleFact> after) {
			Hop hop = origins.get(owner);
			System.err.println("SEARCH_SPACE_EXPORT_OWNER|pass=" + pass + "|phase=" + phase
				+ "|name=" + safe(hop == null ? "-" : hop.getName())
				+ "|opcode=" + safe(hop == null ? "-" : hop.getOpString())
				+ "|emittedHopInstance=" + safe(owner.emittedHopInstance())
				+ "|nodeLegal=" + stateSummary(beforeNode) + "->" + stateSummary(afterNode)
				+ "|rows=" + before.size() + "->" + after.size());
			int emitted = 0;
			for(CandidateRuleFact current : after) {
				CandidateRuleFact prior = findRule(before, current.key());
				if(Objects.equals(prior, current))
					continue;
				emitRule(pass, phase, owner, prior, current);
				if(++emitted == RULE_LIMIT)
					break;
			}
			if(emitted < RULE_LIMIT)
				for(CandidateRuleFact prior : before) {
					if(findRule(after, prior.key()) != null)
						continue;
					emitRule(pass, phase, owner, prior, null);
					if(++emitted == RULE_LIMIT)
						break;
				}
		}

		private static void emitRule(int pass, String phase, CompiledHopKey owner,
			CandidateRuleFact before, CandidateRuleFact after) {
			CandidateRuleFact basis = after != null ? after : before;
			System.err.println("SEARCH_SPACE_EXPORT_RULE|pass=" + pass + "|phase=" + phase
				+ "|emittedHopInstance=" + safe(owner.emittedHopInstance())
				+ "|inputs=" + basis.key().orderedInputs().stream()
					.map(input -> input.present() ? input.fType().name() : "LOCAL").toList()
				+ "|status=" + status(before) + "->" + status(after)
				+ "|metadataEqual=" + metadataEqual(before, after)
				+ "|emissions=" + emissionSummary(before) + "->" + emissionSummary(after)
				+ "|realizationDelta=" + realizationDelta(before, after));
		}

		private static Map<CompiledHopKey,Node> nodesByOwner(List<Node> nodes) {
			Map<CompiledHopKey,Node> result = new IdentityHashMap<>();
			for(Node node : nodes)
				result.put(node.key(), node);
			return result;
		}

		private static Map<CompiledHopKey,List<CandidateRuleFact>> factsByOwner(
			List<CandidateRuleFact> facts) {
			Map<CompiledHopKey,List<CandidateRuleFact>> mutable = new IdentityHashMap<>();
			for(CandidateRuleFact fact : facts)
				mutable.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
			Map<CompiledHopKey,List<CandidateRuleFact>> result = new IdentityHashMap<>();
			mutable.forEach((owner, rows) -> result.put(owner, List.copyOf(rows)));
			return result;
		}

		private static CandidateRuleFact findRule(List<CandidateRuleFact> facts, CandidateRuleKey key) {
			for(CandidateRuleFact fact : facts)
				if(fact.key().equals(key))
					return fact;
			return null;
		}

		private static String stateSummary(Node node) {
			return node == null ? "-" : node.legalAlternatives().toString();
		}

		private static String status(CandidateRuleFact fact) {
			return fact == null ? "-" : fact.status().name();
		}

		private static String metadataEqual(CandidateRuleFact before, CandidateRuleFact after) {
			if(before == null || after == null)
				return "-";
			return "cap:" + Objects.equals(before.capability(), after.capability())
				+ ",shape:" + before.shapeProof().equals(after.shapeProof())
				+ ",profile:" + before.profile().equals(after.profile())
				+ ",failure:" + before.failureCode().equals(after.failureCode());
		}

		private static String emissionSummary(CandidateRuleFact fact) {
			if(fact == null)
				return "-";
			List<CandidateEmissionFact> emissions = fact.allowedEmissionFacts();
			String bounded = emissions.stream().limit(6).map(emission -> {
				int supports = emission.realizations().stream()
					.mapToInt(realization -> realization.supportClauses().size()).sum();
				return emission.emissionState().placementState() + "/exec="
					+ (emission.executionFType() == null ? "-" : emission.executionFType().name())
					+ "/derived=" + (emission.derivedFoutAction() != null)
					+ "/layouts=" + emission.realizations().size() + "/supports=" + supports;
			}).toList().toString();
			return bounded + (emissions.size() > 6 ? "/total=" + emissions.size() : "");
		}

		private static String realizationDelta(CandidateRuleFact before, CandidateRuleFact after) {
			if(before == null || after == null)
				return "-";
			List<String> deltas = new ArrayList<>();
			for(CandidateEmissionFact current : after.allowedEmissionFacts().stream().limit(6).toList()) {
				CandidateEmissionFact prior = before.allowedEmissionFacts().stream()
					.filter(candidate -> candidate.emissionState().equals(current.emissionState()))
					.findFirst().orElse(null);
				if(prior == null) {
					deltas.add("added:" + realizationKeys(current));
					continue;
				}
				List<?> priorKeys = prior.realizations().stream().map(CandidateEmissionRealization::key).toList();
				List<?> currentKeys = current.realizations().stream().map(CandidateEmissionRealization::key).toList();
				boolean supportSame = prior.realizations().size() == current.realizations().size();
				if(supportSame)
					for(int index = 0; index < prior.realizations().size(); index++)
						supportSame &= prior.realizations().get(index).supportClauses()
							.equals(current.realizations().get(index).supportClauses());
				deltas.add(current.emissionState().placementState() + ":keySame="
					+ priorKeys.equals(currentKeys) + ",supportSame=" + supportSame
					+ ",keys=" + realizationKeys(prior) + "->" + realizationKeys(current));
			}
			return deltas.toString();
		}

		private static String realizationKeys(CandidateEmissionFact emission) {
			return emission.realizations().stream().limit(4).map(realization -> {
				var key = realization.key();
				String identity = key.nativeLineage() != null ? boundedIdentity(key.nativeLineage())
					: key.durableAnchor() != null ? boundedIdentity(key.durableAnchor().placementId()) : "-";
				return key.layoutKind().name() + ":hash=" + key.hashCode()
					+ ":identityHash=" + identity.hashCode() + ":id=" + identity;
			}).toList().toString();
		}

		private static String boundedIdentity(String value) {
			String safe = safe(value);
			if(safe.length() <= 240)
				return safe;
			int seed = safe.lastIndexOf("/seed=");
			if(seed >= 0)
				return "..." + safe.substring(Math.max(seed, safe.length() - 237));
			return safe.substring(0, 117) + "..." + safe.substring(safe.length() - 117);
		}

		private static String safe(String value) {
			return value == null ? "-" : value.replace('|', '/').replace('\n', ' ');
		}
	}

	static void emitCompositionRecurrence(int completedPass,
		CompositionRecurrenceObservation recurrence) {
		System.err.println(formatCompositionRecurrence(completedPass, recurrence));
	}

	static String formatCompositionRecurrence(int completedPass,
		CompositionRecurrenceObservation recurrence) {
		return "SEARCH_SPACE_COMPOSITION_RECURRENCE|completedPass=" + completedPass
			+ "|publicationRecurrence=" + recurrence.publicationRecurrence()
			+ "|publicationPreviousPass=" + recurrence.publicationPreviousPass()
			+ "|repeatPeriod=" + recurrence.repeatPeriod()
			+ "|fullContextRecurrence=" + recurrence.fullContextRecurrence()
			+ "|fullContextPreviousPass=" + recurrence.fullContextPreviousPass()
			+ "|nodesSame=" + recurrence.nodesSame()
			+ "|domainSame=" + recurrence.domainSame()
			+ "|factsSame=" + recurrence.factsSame()
			+ "|logicalSame=" + recurrence.logicalSame()
			+ "|actionsSame=" + recurrence.actionsSame()
			+ "|pendingSame=" + recurrence.pendingSame()
			+ "|ledgerSize=" + recurrence.ledgerSize()
			+ "|baseSize=" + recurrence.baseSize()
			+ "|diagnosticNanos=" + recurrence.diagnosticNanos();
	}

	static void emitComposition(SearchSpaceMetrics metrics, int pass, boolean stable,
		boolean nodesSame, boolean factsSame, boolean actionsSame, boolean domainSame,
		boolean logicalSame, boolean boundActionsSame, boolean pendingSame,
		int changedExports, int pendingPhysicalRebuilds, int nativeContextHits,
		int nativeContextMisses, long relocationProductHits, long relocationProductMisses,
		long relocationProductAvoidedLeaves) {
		System.err.println(formatComposition(pass, stable, nodesSame, factsSame, actionsSame,
			domainSame, logicalSame, boundActionsSame, pendingSame, changedExports,
			pendingPhysicalRebuilds, metrics.snapshot().directClosurePasses(), nativeContextHits,
			nativeContextMisses, relocationProductHits, relocationProductMisses,
			relocationProductAvoidedLeaves));
	}

	static boolean compositionEnabled(SearchSpaceMetrics metrics, int pass) {
		return metrics != null && Boolean.getBoolean(PHASE_MARKERS_PROPERTY)
			&& (pass < 16 || (pass & (pass - 1)) == 0);
	}

	static String formatComposition(int pass, boolean stable, boolean nodesSame,
		boolean factsSame, boolean actionsSame, boolean domainSame, boolean logicalSame,
		boolean boundActionsSame, boolean pendingSame, int changedExports,
		int pendingPhysicalRebuilds, long directWaves, int nativeContextHits,
		int nativeContextMisses, long relocationProductHits, long relocationProductMisses,
		long relocationProductAvoidedLeaves) {
		return "SEARCH_SPACE_COMPOSITION|pass=" + pass + "|stable=" + stable
			+ "|nodesSame=" + nodesSame + "|factsSame=" + factsSame
			+ "|actionsSame=" + actionsSame
			+ "|domainSame=" + domainSame
			+ "|logicalSame=" + logicalSame
			+ "|boundActionsSame=" + boundActionsSame
			+ "|pendingSame=" + pendingSame
			+ "|changedExports=" + changedExports + "|pending=" + pendingPhysicalRebuilds
			+ "|directWaves=" + directWaves
			+ "|nativeContextHits=" + nativeContextHits + "|nativeContextMisses=" + nativeContextMisses
			+ "|relocationProductHits=" + relocationProductHits
			+ "|relocationProductMisses=" + relocationProductMisses
			+ "|relocationProductAvoidedLeaves=" + relocationProductAvoidedLeaves;
	}
}

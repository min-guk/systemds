/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;

/**
 * Cost-only dimension bounds for normally completing executions. These facts never
 * replace exact geometry, alter candidate legality, or specialize control flow.
 *
 * A matrix stored into a bounded slice, or consumed by a dimension-constrained
 * matrix multiply, can be small even when its precise dimensions vary. Shared
 * function return contracts require every call result; alternative callers join
 * with max, not min. Noncontracting ancestry intersects ALL reaching definitions
 * and stops at cycles, so a conditional append cannot narrow a growing loop phi.
 */
public final class PlacementCostSizeBounds {
	public record Bounds(long rowsUpperBound, long colsUpperBound) { }
	private record Axis(CompiledHopKey key, boolean rows) { }
	private record Entry(String function, String formalName, boolean rows) { }
	private record Output(String function, int position) { }
	private record Witness(CompiledHopKey consumer, String functionExit) {
		static Witness at(CompiledHopKey consumer) { return new Witness(consumer, null); }
		static Witness exit(String function) { return new Witness(null, function); }
	}
	private record CallResult(CompiledHopKey call, Output output) { }
	private final Map<CompiledHopKey,Bounds> bounds;

	private PlacementCostSizeBounds(Map<CompiledHopKey,Bounds> bounds) {
		this.bounds = Map.copyOf(bounds);
	}

	static PlacementCostSizeBounds from(PlacementAnalysis analysis) {
		return new Builder(analysis).build();
	}

	public Optional<Bounds> get(CompiledHopKey key) {
		return Optional.ofNullable(bounds.get(Objects.requireNonNull(key, "key")));
	}

	private static final class Builder {
		private final PlacementAnalysis analysis;
		private final Map<CompiledHopKey,Map<Integer,CompiledHopKey>> scalarInputs = new HashMap<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> sources = new LinkedHashMap<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> returns = new LinkedHashMap<>();
		private final Map<CompiledHopKey,CallResult> results = new LinkedHashMap<>();
		private final Map<Output,List<CompiledHopKey>> resultGroups = new LinkedHashMap<>();
		private final Map<CompiledHopKey,Entry> inputs = new LinkedHashMap<>();
		private final Map<CompiledHopKey,CompiledHopKey> inputCalls = new LinkedHashMap<>();
		private final Map<Axis,Long> demanded = new HashMap<>();
		private final Map<Object,Long> contracts = new HashMap<>();
		private final Map<Axis,Set<Object>> ancestorCache = new HashMap<>();
		private final Map<Axis,Long> upperCache = new HashMap<>();
		private boolean changed;
		private boolean inconsistent;

		Builder(PlacementAnalysis analysis) {
			this.analysis = analysis;
			for(var edge : analysis.graph().constraints()) {
				String evidence = edge.evidence();
				if("data-input".equals(evidence) && analysis.hop(edge.left())
					.map(hop -> hop.getDataType().isScalar()).orElse(false)) {
					var positions = scalarInputs.computeIfAbsent(edge.right(), ignored -> new HashMap<>());
					CompiledHopKey previous = positions.putIfAbsent(edge.inputPosition(), edge.left());
					if(previous != null && !previous.equals(edge.left())) inconsistent = true;
				}
				if(evidence.startsWith("cfg-transient-value:")
					|| evidence.startsWith("cfg-function-output-value:")
					|| "function-formal-input".equals(evidence)
					|| evidence.startsWith("function-argument:"))
					add(sources, edge.right(), edge.left());
				if(evidence.startsWith("function-result:"))
					add(returns, edge.right(), edge.left());
				if("function-callsite-control".equals(evidence))
					inputCalls.put(edge.right(), edge.left());
				if("function-callsite-output-control".equals(evidence)
					&& analysis.isDmlFunctionCallBoundary(edge.left())) {
					FunctionOp call = (FunctionOp)analysis.hop(edge.left()).orElseThrow();
					Output output = new Output(functionIdentity(call), edge.inputPosition());
					results.put(edge.right(), new CallResult(edge.left(), output));
					add(resultGroups, output, edge.right());
				}
			}
			for(var input : analysis.logicalFunctionInputsInCanonicalOrder()) {
				// Named actual arguments need not be in declaration order. The compiler's
				// qualified formal read, not the caller's positional slot, identifies a formal.
				String formal = analysis.hop(input.targetRead()).orElseThrow().getName();
				Entry entry = new Entry(input.targetRead().functionNamespace(), formal, true);
				Entry previous = inputs.putIfAbsent(input.boundary(), entry);
				if(formal == null || formal.isBlank() || previous != null && !previous.equals(entry))
					inconsistent = true;
			}
		}

		PlacementCostSizeBounds build() {
			// Bounds only tighten, and no inference descends through a cyclic value proof.
			// Fail closed rather than publish a partial contract if closure does not finish.
			int passes = Math.max(8, analysis.occurrences().size() * 2);
			for(int pass = 0; pass < passes; pass++) {
				changed = false;
				upperCache.clear();
				for(var occurrence : analysis.compiledHopOccurrences()) {
					CompiledHopKey key = occurrence.key();
					Hop hop = occurrence.hop();
					if(hop instanceof LeftIndexingOp && hop.getInput().get(1).getDataType().isMatrix()) {
						for(boolean rows : List.of(true, false))
							demand(input(key, 1), rows, upper(input(key, 0), rows), Witness.at(key));
					}
					else if(hop instanceof AggBinaryOp mm && mm.isMatrixMultiply()) {
						demand(input(key, 0), false, upper(input(key, 1), true), Witness.at(key));
						demand(input(key, 1), true, upper(input(key, 0), false), Witness.at(key));
					}
				}
				for(var binding : analysis.logicalFunctionInputsInCanonicalOrder()) {
					Entry entry = inputs.get(binding.boundary());
					CompiledHopKey call = inputCalls.get(binding.boundary());
					if(entry == null || call == null) continue;
					for(boolean rows : List.of(true, false))
						demand(binding.sourceArgument(), rows,
							contracts.getOrDefault(new Entry(entry.function(), entry.formalName(), rows), -1L),
							Witness.at(call));
				}
				for(var group : resultGroups.entrySet()) {
					if(!completeCalls(group.getKey(), group.getValue())) continue;
					for(boolean rows : List.of(true, false)) {
						long bound = -1;
						Set<Object> common = null;
						boolean complete = true;
						for(CompiledHopKey result : group.getValue()) {
							long value = upper(result, rows);
							List<CompiledHopKey> definitions = returns.getOrDefault(result, List.of());
							if(value <= 0 || definitions.isEmpty()) { complete = false; break; }
							bound = Math.max(bound, value);
							for(CompiledHopKey definition : definitions)
								common = intersect(common, ancestry(definition, rows));
						}
						if(complete && common != null)
							for(Object token : common)
								contract(token, bound, Witness.exit(group.getKey().function()));
					}
				}
				if(inconsistent) return new PlacementCostSizeBounds(Map.of());
				if(!changed) {
					upperCache.clear();
					Map<CompiledHopKey,Bounds> published = new LinkedHashMap<>();
					for(var occurrence : analysis.occurrences()) {
						long rows = upper(occurrence.key(), true);
						long cols = upper(occurrence.key(), false);
						if(rows > 0 || cols > 0)
							published.put(occurrence.key(), new Bounds(rows, cols));
					}
					return new PlacementCostSizeBounds(published);
				}
			}
			return new PlacementCostSizeBounds(Map.of());
		}

		private boolean completeCalls(Output output, List<CompiledHopKey> boundaries) {
			Set<CompiledHopKey> represented = new HashSet<>();
			boundaries.forEach(key -> represented.add(results.get(key).call()));
			for(var occurrence : analysis.compiledHopOccurrences())
				if(analysis.isDmlFunctionCallBoundary(occurrence.key())
					&& occurrence.hop() instanceof FunctionOp call
					&& functionIdentity(call).equals(output.function())
					&& !represented.contains(occurrence.key()))
					return false;
			return !represented.isEmpty();
		}

		private void demand(CompiledHopKey key, boolean rows, long bound, Witness witness) {
			if(key == null || bound <= 0) return;
			long exact = exact(key, rows);
			if(exact > bound) { inconsistent = true; return; }
			if(witness.consumer() != null && sameExecution(key, witness.consumer()))
				tighten(demanded, new Axis(key, rows), bound);
			for(Object token : ancestry(key, rows))
				contract(token, bound, witness);
		}

		private void contract(Object token, long bound, Witness witness) {
			if(token instanceof Entry entry) {
				if(entry.function().equals(witness.functionExit())
					|| witness.consumer() != null && unconditional(witness.consumer(), entry.function()))
					tighten(contracts, token, bound);
			}
			else if(token instanceof Axis result && results.containsKey(result.key())) {
				CompiledHopKey call = results.get(result.key()).call();
				if(witness.functionExit() != null && unconditional(call, witness.functionExit())
					|| witness.consumer() != null && sameExecution(call, witness.consumer()))
					tighten(contracts, token, bound);
			}
		}

		private <K> void tighten(Map<K,Long> map, K key, long bound) {
			long previous = map.getOrDefault(key, -1L);
			if(bound > 0 && (previous < 0 || bound < previous)) {
				map.put(key, bound);
				upperCache.clear();
				changed = true;
			}
		}

		private boolean sameExecution(CompiledHopKey left, CompiledHopKey right) {
			if(!left.functionNamespace().equals(right.functionNamespace())) return false;
			try {
				return sameProfiles(analysis.executionFrequencyFacts().exactProfiles(left),
					analysis.executionFrequencyFacts().exactProfiles(right));
			}
			catch(IllegalArgumentException | IllegalStateException exception) { return false; }
		}

		private boolean unconditional(CompiledHopKey key, String function) {
			if(!key.functionNamespace().equals(function)) return false;
			var entry = analysis.executionFrequencyFacts().profilesByPath().get("function/" + function);
			if(entry == null) return false;
			try { return sameProfiles(analysis.executionFrequencyFacts().exactProfiles(key), entry); }
			catch(IllegalArgumentException | IllegalStateException exception) { return false; }
		}

		private static boolean sameProfiles(List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> left,
			List<OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact> right) {
			if(left.isEmpty() || left.size() != right.size()) return false;
			return left.stream().allMatch(a -> right.stream().anyMatch(b ->
				a.contextOrdinal() == b.contextOrdinal() && a.loopContext().equals(b.loopContext())
					&& a.activationConditions().equals(b.activationConditions())));
		}

		private Set<Object> ancestry(CompiledHopKey key, boolean rows) {
			return ancestorCache.computeIfAbsent(new Axis(key, rows), axis ->
				Set.copyOf(ancestors(axis, new HashSet<>())));
		}

		private Set<Object> ancestors(Axis axis, Set<Axis> visiting) {
			if(axis.key() == null || !visiting.add(axis)) return Set.of();
			try {
				// Never memoize a cycle-dependent partial proof.
				if(results.containsKey(axis.key())) return Set.of(axis);
				Entry entry = inputs.get(axis.key());
				if(entry != null) return Set.of(new Entry(entry.function(), entry.formalName(), axis.rows()));
				List<CompiledHopKey> alternatives = sources.get(axis.key());
				if(alternatives != null && !alternatives.isEmpty()) {
					Set<Object> common = null;
					for(CompiledHopKey source : alternatives)
						common = intersect(common, ancestors(new Axis(source, axis.rows()), visiting));
					return common == null ? Set.of() : common;
				}
				Hop hop = analysis.hop(axis.key()).orElse(null);
				if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE)
					return ancestors(new Axis(input(axis.key(), 0), axis.rows()), visiting);
				if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
					return ancestors(new Axis(input(axis.key(), 0), !axis.rows()), visiting);
				if(hop instanceof BinaryOp binary && (binary.getOp() == OpOp2.CBIND
					|| binary.getOp() == OpOp2.RBIND)) {
					Set<Object> result = new LinkedHashSet<>(ancestors(
						new Axis(input(axis.key(), 0), axis.rows()), visiting));
					result.addAll(ancestors(new Axis(input(axis.key(), 1), axis.rows()), visiting));
					return result;
				}
				return Set.of();
			}
			finally { visiting.remove(axis); }
		}

		private long upper(CompiledHopKey key, boolean rows) {
			return upperCache.computeIfAbsent(new Axis(key, rows), axis -> upper(axis, new HashSet<>()));
		}

		private long upper(Axis axis, Set<Axis> visiting) {
			if(axis.key() == null || !visiting.add(axis)) return -1;
			try {
				long exact = exact(axis.key(), axis.rows());
				if(exact > 0) return exact;
				long bound = demanded.getOrDefault(axis, -1L);
				if(results.containsKey(axis.key()))
					return min(bound, contracts.getOrDefault(axis, -1L));
				Entry entry = inputs.get(axis.key());
				if(entry != null)
					bound = min(bound, contracts.getOrDefault(
						new Entry(entry.function(), entry.formalName(), axis.rows()), -1L));
				List<CompiledHopKey> alternatives = sources.get(axis.key());
				if(alternatives != null && !alternatives.isEmpty()) {
					long joined = 0;
					for(CompiledHopKey source : alternatives) {
						long value = upper(new Axis(source, axis.rows()), visiting);
						if(value <= 0) { joined = -1; break; }
						joined = Math.max(joined, value);
					}
					return min(bound, joined);
				}
				Hop hop = analysis.hop(axis.key()).orElse(null);
				if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE
					|| hop instanceof LeftIndexingOp)
					return min(bound, upper(new Axis(input(axis.key(), 0), axis.rows()), visiting));
				if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
					return min(bound, upper(new Axis(input(axis.key(), 0), !axis.rows()), visiting));
				if(hop instanceof AggBinaryOp mm && mm.isMatrixMultiply())
					return min(bound, upper(new Axis(input(axis.key(), axis.rows() ? 0 : 1), axis.rows()), visiting));
				if(hop instanceof BinaryOp binary && binary.getOp() == OpOp2.SOLVE)
					return min(bound, upper(new Axis(input(axis.key(), axis.rows() ? 0 : 1), false), visiting));
				if(hop instanceof BinaryOp binary && (binary.getOp() == OpOp2.CBIND
					|| binary.getOp() == OpOp2.RBIND)) {
					long a = upper(new Axis(input(axis.key(), 0), axis.rows()), visiting);
					long b = upper(new Axis(input(axis.key(), 1), axis.rows()), visiting);
					if(a > 0 && b > 0) {
						boolean grows = axis.rows() == (binary.getOp() == OpOp2.RBIND);
						try { bound = min(bound, grows ? Math.addExact(a, b) : Math.min(a, b)); }
						catch(ArithmeticException exception) { return bound; }
					}
				}
				return bound;
			}
			finally { visiting.remove(axis); }
		}

		private long exact(CompiledHopKey key, boolean rows) {
			var shape = analysis.abstractShapeFact(key).orElse(null);
			if(shape != null) {
				var dimension = rows ? shape.rows() : shape.cols();
				if(dimension.isExact()) return dimension.value();
			}
			Hop hop = analysis.hop(key).orElse(null);
			if(hop instanceof TernaryOp table && table.getOp() == OpOp3.CTABLE && hop.getInput().size() >= 5) {
				CompiledHopKey size = scalarInputs.getOrDefault(key, Map.of()).get(rows ? 3 : 4);
				if(size != null) {
					var scalar = analysis.scalarLiteralFact(size).orElse(null);
					if(scalar != null) {
						try { return new java.math.BigDecimal(scalar.canonicalValue()).longValueExact(); }
						catch(NumberFormatException | ArithmeticException exception) { return -1; }
					}
				}
			}
			return -1;
		}

		private CompiledHopKey input(CompiledHopKey key, int position) {
			if(key == null) return null;
			return analysis.compiledInputEdge(key, position).map(
				PlacementAnalysis.CompiledInputEdgeFact::producer).orElse(null);
		}

		private static String functionIdentity(FunctionOp call) {
			return DMLProgram.DEFAULT_NAMESPACE.equals(call.getFunctionNamespace())
				? call.getFunctionName() : call.getFunctionKey();
		}

		private static long min(long a, long b) { return a <= 0 ? b : b <= 0 ? a : Math.min(a, b); }
		private static Set<Object> intersect(Set<Object> a, Set<Object> b) {
			Set<Object> result = new LinkedHashSet<>(a == null ? b : a);
			if(a != null) result.retainAll(b);
			return result;
		}
		private static <K> void add(Map<K,List<CompiledHopKey>> map, K key, CompiledHopKey value) {
			List<CompiledHopKey> values = map.computeIfAbsent(key, ignored -> new ArrayList<>());
			if(!values.contains(value)) values.add(value);
		}
	}
}

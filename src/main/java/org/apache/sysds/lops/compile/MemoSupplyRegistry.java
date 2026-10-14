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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections4.map.AbstractReferenceMap.ReferenceStrength;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;

/** Immutable hand-off of exact Memo planner supply edges to Lop lowering. */
public final class MemoSupplyRegistry {
	// Planner-owned entries live only below a weak program key. The separate manual map is the
	// bounded compatibility surface used by lowering unit tests and legacy register/replaceAll callers.
	private static volatile Map<Long, List<Supply>> MANUAL_SUPPLIES = Map.of();
	private static final Map<DMLProgram,Map<Long,List<Supply>>> OWNER_SUPPLIES = new ReferenceIdentityMap<>(
		ReferenceStrength.WEAK,ReferenceStrength.HARD);

	private MemoSupplyRegistry() {
	}

	public enum Movement {
		DIRECT,
		COLLECT,
		UPLOAD
	}

	public record Recipe(MemoPartition sourcePartition, Movement movement, String workerPoolDescriptor) {
		public Recipe {
			Objects.requireNonNull(sourcePartition, "sourcePartition");
			Objects.requireNonNull(movement, "movement");
			if(sourcePartition.federated()) {
				if(!CanonicalWorkerPool.isDescriptor(workerPoolDescriptor))
					throw new IllegalArgumentException("A FED source recipe requires its canonical worker pool.");
				CanonicalWorkerPool pool = CanonicalWorkerPool.parse(workerPoolDescriptor);
				pool.runtimeType(sourcePartition.runtimeType(pool.size()));
			}
			else if(movement == Movement.UPLOAD) {
				if(!CanonicalWorkerPool.isDescriptor(workerPoolDescriptor))
					throw new IllegalArgumentException("UPLOAD requires its canonical target worker pool.");
				CanonicalWorkerPool.parse(workerPoolDescriptor);
			}
			else if(workerPoolDescriptor != null)
				throw new IllegalArgumentException("A LOCAL DIRECT recipe has no worker pool.");
		}
	}

	public record Supply(long producerHopId, long consumerHopId, int inputPosition,
		MemoPartition targetPartition, List<Recipe> recipes) {
		public Supply {
			if(producerHopId < 0 || consumerHopId < 0 || inputPosition < 0)
				throw new IllegalArgumentException("Memo supply requires non-negative exact Hop/input identities.");
			Objects.requireNonNull(targetPartition, "targetPartition");
			recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
			if(recipes.isEmpty())
				throw new IllegalArgumentException("Memo supply requires at least one selected source recipe.");
			Set<MemoPartition> sources = new HashSet<>();
			for(Recipe recipe : recipes) {
				Objects.requireNonNull(recipe, "recipe");
				if(!sources.add(recipe.sourcePartition()))
					throw new IllegalArgumentException("Memo supply has ambiguous recipes for "
						+ recipe.sourcePartition() + '.');
				validateRecipe(targetPartition, recipe);
			}
		}

		public boolean guarded() {
			return recipes.size() > 1;
		}

		private static void validateRecipe(MemoPartition target, Recipe recipe) {
			MemoPartition source = recipe.sourcePartition();
			Movement expected = source == target ? Movement.DIRECT
				: source == MemoPartition.LOCAL ? Movement.UPLOAD
				: target == MemoPartition.LOCAL ? Movement.COLLECT : null;
			if(expected == null || recipe.movement() != expected)
				throw new IllegalArgumentException("Illegal canonical supply recipe " + source + " -> " + target
					+ " using " + recipe.movement() + '.');
			if(target.federated() && recipe.movement() == Movement.UPLOAD) {
				CanonicalWorkerPool pool = CanonicalWorkerPool.parse(recipe.workerPoolDescriptor());
				pool.runtimeType(target.runtimeType(pool.size()));
			}
		}
	}

	public static synchronized void register(long statementBlockId, List<Supply> supplies) {
		List<Supply> immutable = validateEntry(statementBlockId, supplies);
		if(OWNER_SUPPLIES.values().stream().anyMatch(values -> values.containsKey(statementBlockId)))
			throw new IllegalStateException("Statement block "+statementBlockId+" belongs to a memo owner");
		List<Supply> prior = MANUAL_SUPPLIES.get(statementBlockId);
		if(prior != null && !prior.equals(immutable))
			throw new IllegalStateException("Memo supply plan is already registered for statement block "
				+ statementBlockId + '.');
		if(prior == null) {
			Map<Long, List<Supply>> replacement = new LinkedHashMap<>(MANUAL_SUPPLIES);
			replacement.put(statementBlockId, immutable);
			MANUAL_SUPPLIES = Map.copyOf(replacement);
		}
	}

	public static synchronized void replaceAll(Map<Long, List<Supply>> suppliesByStatementBlock) {
		Objects.requireNonNull(suppliesByStatementBlock, "suppliesByStatementBlock");
		Map<Long, List<Supply>> replacement = new LinkedHashMap<>();
		for(Map.Entry<Long, List<Supply>> entry : suppliesByStatementBlock.entrySet()) {
			Long statementBlockId = Objects.requireNonNull(entry.getKey(), "statementBlockId");
			if(OWNER_SUPPLIES.values().stream().anyMatch(values -> values.containsKey(statementBlockId)))
				throw new IllegalStateException("Statement block "+statementBlockId+" belongs to a memo owner");
			replacement.put(statementBlockId, validateEntry(statementBlockId, entry.getValue()));
		}
		MANUAL_SUPPLIES = Map.copyOf(replacement);
	}

	/** Replaces only one program's selected statement-block ids. */
	public static synchronized void replaceForOwner(DMLProgram owner,
		Map<Long,List<Supply>> suppliesByStatementBlock) {
		Objects.requireNonNull(owner,"program owner");
		Objects.requireNonNull(suppliesByStatementBlock,"suppliesByStatementBlock");
		Set<Long> occupied=new HashSet<>();
		occupied.addAll(MANUAL_SUPPLIES.keySet());
		for(var entry:OWNER_SUPPLIES.entrySet())
			if(entry.getKey()!=owner) occupied.addAll(entry.getValue().keySet());
		Map<Long,List<Supply>> replacement=new LinkedHashMap<>();
		for(var entry:suppliesByStatementBlock.entrySet()) {
			Long id=Objects.requireNonNull(entry.getKey(),"statementBlockId");
			if(occupied.contains(id))
				throw new IllegalStateException("Statement block "+id+" belongs to another memo owner");
			replacement.put(id,validateEntry(id,entry.getValue()));
		}
		OWNER_SUPPLIES.put(owner,Map.copyOf(replacement));
	}

	public static synchronized void remove(DMLProgram owner) {
		if(owner!=null) OWNER_SUPPLIES.remove(owner);
	}

	public static synchronized Map<Long, List<Supply>> snapshotAll() {
		Map<Long,List<Supply>> result=new LinkedHashMap<>(MANUAL_SUPPLIES);
		for(Map<Long,List<Supply>> supplies:OWNER_SUPPLIES.values())
			for(var entry:supplies.entrySet())
				if(result.putIfAbsent(entry.getKey(),entry.getValue())!=null)
					throw new IllegalStateException("Duplicate memo supply statement block "+entry.getKey());
		return Map.copyOf(result);
	}

	public static synchronized Map<Long,List<Supply>> snapshot(DMLProgram owner) {
		return owner==null ? Map.of() : OWNER_SUPPLIES.getOrDefault(owner,Map.of());
	}

	private static List<Supply> validateEntry(long statementBlockId, List<Supply> supplies) {
		if(statementBlockId < 0)
			throw new IllegalArgumentException("Memo supplies require an exact statement block id.");
		Objects.requireNonNull(supplies, "supplies");
		List<Supply> immutable = List.copyOf(supplies);
		Set<EdgeKey> edges = new HashSet<>();
		for(Supply supply : immutable) {
			Objects.requireNonNull(supply, "supply");
			if(!edges.add(new EdgeKey(supply.consumerHopId(), supply.inputPosition())))
				throw new IllegalArgumentException("Memo supplies assign one consumer input more than once: "
					+ supply.consumerHopId() + ':' + supply.inputPosition());
		}
		return immutable;
	}

	public static synchronized List<Supply> snapshot(DMLProgram owner,long statementBlockId) {
		Map<Long,List<Supply>> owned=owner==null ? null : OWNER_SUPPLIES.get(owner);
		return owned!=null ? owned.getOrDefault(statementBlockId,List.of())
			: MANUAL_SUPPLIES.getOrDefault(statementBlockId,List.of());
	}

	public static synchronized List<Supply> snapshot(long statementBlockId) {
		List<Supply> manual=MANUAL_SUPPLIES.get(statementBlockId);
		if(manual!=null) return manual;
		for(Map<Long,List<Supply>> supplies:OWNER_SUPPLIES.values()) {
			List<Supply> value=supplies.get(statementBlockId);
			if(value!=null) return value;
		}
		return List.of();
	}

	public static synchronized void clear() {
		MANUAL_SUPPLIES = Map.of();
		OWNER_SUPPLIES.clear();
	}

	private record EdgeKey(long consumerHopId, int inputPosition) {
	}
}

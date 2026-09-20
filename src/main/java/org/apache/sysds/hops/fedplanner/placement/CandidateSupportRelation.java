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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;

/** Exact owner-scoped OR-set of factorized conjunctive support routes. */
public final class CandidateSupportRelation {
	private static final int MAX_FLAT_CLAUSES = Integer.MAX_VALUE - 8;
	private final Object owner;
	private final Object epoch;
	private final List<ProductRoute> routes;
	private final long[] cumulativeRouteOffsets;
	private final long rawCardinality;
	private final Map<ChoicePath,CandidateRealizationSupportClause> materializedPaths = new HashMap<>();
	private final Map<CandidateRealizationSupportClause,CandidateRealizationSupportClause> canonicalLeaves =
		new HashMap<>();
	private Long exactCardinality;

	private CandidateSupportRelation(Object owner, Object epoch, List<ProductRoute> routes) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.epoch = Objects.requireNonNull(epoch, "epoch");
		this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
		cumulativeRouteOffsets = new long[this.routes.size()];
		long cardinality = 0;
		for(int index = 0; index < this.routes.size(); index++) {
			ProductRoute route = Objects.requireNonNull(this.routes.get(index), "product route");
			cardinality = Math.addExact(cardinality, route.rawCardinality());
			cumulativeRouteOffsets[index] = cardinality;
		}
		rawCardinality = cardinality;
	}

	/** Constructs only the factor graph; it does not enumerate or instantiate leaves. */
	public static CandidateSupportRelation fromProducts(Object owner, Object epoch,
		List<ProductRoute> routes) {
		return new CandidateSupportRelation(owner, epoch, routes);
	}

	/** Exact adapter for the existing flat OR-set boundary, including same-position conjunctions. */
	public static CandidateSupportRelation fromFlat(Object owner, Object epoch,
		List<CandidateRealizationSupportClause> flatClauses) {
		Objects.requireNonNull(flatClauses, "flatClauses");
		List<ProductRoute> routes = new ArrayList<>(flatClauses.size());
		for(CandidateRealizationSupportClause clause : flatClauses) {
			Objects.requireNonNull(clause, "support clause");
			routes.add(new ProductRoute(clause.proofDependencies(), clause.inputBindings(), List.of(),
				new SupportAnnotations(clause.nativeWorkerPoolWitness(),
					clause.nativeWorkerPoolLayoutExact())));
		}
		return fromProducts(owner, epoch, routes);
	}

	public Object owner() { return owner; }
	public Object epoch() { return epoch; }
	public List<ProductRoute> routes() { return routes; }
	public long rawCardinality() { return rawCardinality; }

	/** Computes the extensional OR-set size on demand. */
	public synchronized long exactCardinality() {
		if(exactCardinality == null) {
			long count = 0;
			for(CandidateRealizationSupportClause ignored : decoder())
				count++;
			exactCardinality = count;
		}
		return exactCardinality;
	}

	public synchronized long constructionMaterializedCount() { return materializedPaths.size(); }

	public List<CandidateRealizationInputBinding> distinctBindingAtoms() {
		Set<CandidateRealizationInputBinding> distinct = new TreeSet<>();
		for(ProductRoute route : routes) {
			distinct.addAll(route.fixedBindingAtoms());
			for(List<CandidateRealizationInputBinding> slot : route.bindingChoicesBySlot())
				distinct.addAll(slot);
		}
		return List.copyOf(distinct);
	}

	public Summary summary() {
		long proofs = 0;
		long fixed = 0;
		long choices = 0;
		for(ProductRoute route : routes) {
			proofs = Math.addExact(proofs, route.proofAtoms().size());
			fixed = Math.addExact(fixed, route.fixedBindingAtoms().size());
			choices = Math.addExact(choices, route.storedChoiceBindingAtomCount());
		}
		return new Summary(routes.size(), rawCardinality, proofs, fixed, choices,
			distinctBindingAtoms().size());
	}

	/** Independent sequential cursor; unique leaves are yielded in O(raw leaves). */
	public Decoder decoder() { return new Decoder(); }

	/** Explicit publication boundary: extensional dedupe followed by global canonical ordering. */
	public List<CandidateRealizationSupportClause> exportCanonicalClauses() {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(CandidateRealizationSupportClause clause : decoder()) {
			if(clauses.size() >= MAX_FLAT_CLAUSES)
				throw new IllegalStateException("Flat support export exceeds list capacity");
			clauses.add(clause);
		}
		clauses.sort(null);
		return List.copyOf(clauses);
	}

	/** Random raw-product lookup uses cumulative offsets and binary search. */
	public OwnedSupportChoice choiceAt(long rawOrdinal) {
		if(rawOrdinal < 0 || rawOrdinal >= rawCardinality)
			throw new IndexOutOfBoundsException("Support choice ordinal outside raw cardinality");
		int routeOrdinal = Arrays.binarySearch(cumulativeRouteOffsets, rawOrdinal + 1);
		if(routeOrdinal < 0)
			routeOrdinal = -routeOrdinal - 1;
		long routeStart = routeOrdinal == 0 ? 0 : cumulativeRouteOffsets[routeOrdinal - 1];
		return choice(routeOrdinal, routes.get(routeOrdinal).productPath(rawOrdinal - routeStart), rawOrdinal);
	}

	public CandidateRealizationSupportClause decode(OwnedSupportChoice choice) {
		requireOwned(choice);
		return materialize(choice.path);
	}

	/** True only for the canonical interned leaf already materialized for this owned path. */
	public synchronized boolean ownsMaterialized(OwnedSupportChoice choice,
		CandidateRealizationSupportClause clause) {
		if(!owns(choice))
			return false;
		return materializedPaths.get(choice.path) == clause;
	}

	public boolean owns(OwnedSupportChoice choice) {
		return choice != null && choice.authority == this && validPath(choice.path)
			&& choice.rawOrdinal >= 0 && choice.rawOrdinal < rawCardinality;
	}

	public void requireOwned(OwnedSupportChoice choice) {
		if(!owns(choice))
			throw new IllegalArgumentException("Support choice has foreign relation authority or path");
	}

	private OwnedSupportChoice choice(int routeOrdinal, List<Integer> productPath, long rawOrdinal) {
		return new OwnedSupportChoice(this, owner, epoch, rawOrdinal,
			new ChoicePath(routeOrdinal, productPath));
	}

	private boolean validPath(ChoicePath path) {
		if(path.routeOrdinal() < 0 || path.routeOrdinal() >= routes.size())
			return false;
		return routes.get(path.routeOrdinal()).validPath(path.productPath());
	}

	private synchronized CandidateRealizationSupportClause materialize(ChoicePath path) {
		CandidateRealizationSupportClause cached = materializedPaths.get(path);
		if(cached != null)
			return cached;
		CandidateRealizationSupportClause leaf = routes.get(path.routeOrdinal()).decode(path.productPath());
		CandidateRealizationSupportClause canonical = canonicalLeaves.get(leaf);
		if(canonical == null) {
			canonical = leaf;
			canonicalLeaves.put(canonical, canonical);
		}
		materializedPaths.put(path, canonical);
		return canonical;
	}

	private CandidateRealizationSupportClause ephemeralDecode(ChoicePath path) {
		return routes.get(path.routeOrdinal()).decode(path.productPath());
	}

	public final class Decoder implements Iterable<CandidateRealizationSupportClause> {
		private Decoder() { }
		public CandidateRealizationSupportClause get(long rawOrdinal) {
			OwnedSupportChoice choice = choiceAt(rawOrdinal);
			return ephemeralDecode(choice.path);
		}

		@Override public Iterator<CandidateRealizationSupportClause> iterator() {
			return new Iterator<>() {
				private final Set<CandidateRealizationSupportClause> emitted = new HashSet<>();
				private int routeOrdinal;
				private long ordinalInRoute;
				private CandidateRealizationSupportClause next = advance();

				@Override public boolean hasNext() { return next != null; }
				@Override public CandidateRealizationSupportClause next() {
					if(next == null)
						throw new NoSuchElementException();
					CandidateRealizationSupportClause current = next;
					next = advance();
					return current;
				}

				private CandidateRealizationSupportClause advance() {
					while(routeOrdinal < routes.size()) {
						ProductRoute route = routes.get(routeOrdinal);
						if(ordinalInRoute == route.rawCardinality()) {
							routeOrdinal++;
							ordinalInRoute = 0;
							continue;
						}
						List<Integer> path = route.productPath(ordinalInRoute++);
						CandidateRealizationSupportClause candidate = ephemeralDecode(
							new ChoicePath(routeOrdinal, path));
						if(emitted.add(candidate))
							return candidate;
					}
					return null;
				}
			};
		}
	}

	/** One correlated root route: fixed AND atoms plus independent choice slots. */
	public static final class ProductRoute {
		private final List<PlacementProofKey> proofAtoms;
		private final List<CandidateRealizationInputBinding> fixedBindingAtoms;
		private final List<List<CandidateRealizationInputBinding>> bindingChoicesBySlot;
		private final SupportAnnotations annotations;
		private final long rawCardinality;

		public ProductRoute(List<PlacementProofKey> proofAtoms,
			List<List<CandidateRealizationInputBinding>> bindingChoicesBySlot,
			SupportAnnotations annotations) {
			this(proofAtoms, List.of(), bindingChoicesBySlot, annotations);
		}

		public ProductRoute(List<PlacementProofKey> proofAtoms,
			List<CandidateRealizationInputBinding> fixedBindingAtoms,
			List<List<CandidateRealizationInputBinding>> bindingChoicesBySlot,
			SupportAnnotations annotations) {
			this.proofAtoms = PlacementAnalysis.sharedCanonicalComparableList(
				Objects.requireNonNull(proofAtoms, "proofAtoms"), "support proof atom");
			this.fixedBindingAtoms = PlacementAnalysis.sharedCanonicalComparableList(
				Objects.requireNonNull(fixedBindingAtoms, "fixedBindingAtoms"), "fixed support binding");
			Objects.requireNonNull(bindingChoicesBySlot, "bindingChoicesBySlot");
			List<List<CandidateRealizationInputBinding>> slots = new ArrayList<>();
			Set<CandidateRealizationInputBinding> conjunctiveAtoms =
				new HashSet<>(this.fixedBindingAtoms);
			long cardinality = 1;
			for(List<CandidateRealizationInputBinding> choices : bindingChoicesBySlot) {
				TreeSet<CandidateRealizationInputBinding> canonical = new TreeSet<>(
					Objects.requireNonNull(choices, "binding slot"));
				if(canonical.isEmpty())
					throw new IllegalArgumentException("Binding product slot must not be empty");
				int position = canonical.first().inputPosition();
				for(CandidateRealizationInputBinding binding : canonical)
					if(binding.inputPosition() != position)
						throw new IllegalArgumentException("One binding product slot must target one input");
				List<CandidateRealizationInputBinding> slot = List.copyOf(canonical);
				for(CandidateRealizationInputBinding binding : slot)
					if(!conjunctiveAtoms.add(binding))
						throw new IllegalArgumentException(
							"Binding atom overlaps fixed atoms or another product slot");
				slots.add(slot);
				cardinality = Math.multiplyExact(cardinality, slot.size());
			}
			this.bindingChoicesBySlot = List.copyOf(slots);
			this.annotations = Objects.requireNonNull(annotations, "annotations");
			if(annotations.nativeWorkerPoolWitness() != null && this.proofAtoms.stream().noneMatch(proof ->
				proof.kind() == PlacementProofKind.NATIVE_CONTINUITY && proof.owner() != null))
				throw new IllegalArgumentException(
					"Native worker-pool annotation requires owned native-continuity proof");
			rawCardinality = cardinality;
		}

		public List<PlacementProofKey> proofAtoms() { return proofAtoms; }
		public List<CandidateRealizationInputBinding> fixedBindingAtoms() { return fixedBindingAtoms; }
		public List<List<CandidateRealizationInputBinding>> bindingChoicesBySlot() {
			return bindingChoicesBySlot;
		}
		public SupportAnnotations annotations() { return annotations; }
		public long rawCardinality() { return rawCardinality; }
		public long storedChoiceBindingAtomCount() {
			long count = 0;
			for(List<CandidateRealizationInputBinding> slot : bindingChoicesBySlot)
				count = Math.addExact(count, slot.size());
			return count;
		}

		private List<Integer> productPath(long ordinal) {
			List<Integer> reversed = new ArrayList<>(bindingChoicesBySlot.size());
			for(int slot = bindingChoicesBySlot.size() - 1; slot >= 0; slot--) {
				int radix = bindingChoicesBySlot.get(slot).size();
				reversed.add((int) (ordinal % radix));
				ordinal /= radix;
			}
			List<Integer> path = new ArrayList<>(reversed.size());
			for(int index = reversed.size() - 1; index >= 0; index--)
				path.add(reversed.get(index));
			return List.copyOf(path);
		}

		private boolean validPath(List<Integer> path) {
			if(path.size() != bindingChoicesBySlot.size())
				return false;
			for(int slot = 0; slot < path.size(); slot++)
				if(path.get(slot) < 0 || path.get(slot) >= bindingChoicesBySlot.get(slot).size())
					return false;
			return true;
		}

		private CandidateRealizationSupportClause decode(List<Integer> path) {
			if(!validPath(path))
				throw new IllegalArgumentException("Support choice path has wrong arity or index");
			List<CandidateRealizationInputBinding> bindings = new ArrayList<>(fixedBindingAtoms);
			for(int slot = 0; slot < path.size(); slot++)
				bindings.add(bindingChoicesBySlot.get(slot).get(path.get(slot)));
			return new CandidateRealizationSupportClause(proofAtoms, bindings,
				annotations.nativeWorkerPoolWitness(), annotations.nativeWorkerPoolLayoutExact());
		}
	}

	public record SupportAnnotations(DurableAnchorKey nativeWorkerPoolWitness,
		boolean nativeWorkerPoolLayoutExact) {
		public SupportAnnotations {
			if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
				throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
		}
		public static SupportAnnotations exact() { return new SupportAnnotations(null, true); }
	}

	public record Summary(int routeCount, long rawCardinality, long storedProofAtomCount,
		long storedFixedBindingAtomCount, long storedChoiceBindingAtomCount,
		long distinctBindingAtomCount) { }

	private record ChoicePath(int routeOrdinal, List<Integer> productPath) { }

	/** Path handle whose authority is the originating relation instance itself. */
	public static final class OwnedSupportChoice {
		private final CandidateSupportRelation authority;
		private final Object owner;
		private final Object epoch;
		private final long rawOrdinal;
		private final ChoicePath path;

		private OwnedSupportChoice(CandidateSupportRelation authority, Object owner,
			Object epoch, long rawOrdinal, ChoicePath path) {
			this.authority = authority;
			this.owner = owner;
			this.epoch = epoch;
			this.rawOrdinal = rawOrdinal;
			this.path = path;
		}

		public Object owner() { return owner; }
		public Object epoch() { return epoch; }
		public long rawOrdinal() { return rawOrdinal; }
		public int routeOrdinal() { return path.routeOrdinal(); }
		public List<Integer> productPath() { return path.productPath(); }
	}
}

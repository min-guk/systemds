/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

/**
 * An exact union of correlated native-proof products. Each route owns its
 * metadata and its ordered binding dimensions; alternatives from different
 * routes are therefore never combined.
 */
final class NativeProofProduct {
	private final List<Route> routes;
	private final long rawCardinality;
	private final long bindingAtoms;
	private final long duplicateRoutesDropped;

	private NativeProofProduct(Collection<Route> source) {
		Objects.requireNonNull(source, "native proof routes");
		Set<Route> distinct = new LinkedHashSet<>();
		long atoms = 0;
		long supplied = 0;
		for(Route route : source) {
			supplied = Math.incrementExact(supplied);
			Route checked = Objects.requireNonNull(route, "native proof route");
			if(distinct.add(checked))
				atoms = Math.addExact(atoms, checked.bindingAtomCount());
		}
		routes = List.copyOf(distinct);
		long cardinality = 0;
		for(Route route : routes)
			cardinality = Math.addExact(cardinality, route.checkedCardinality());
		rawCardinality = cardinality;
		bindingAtoms = atoms;
		duplicateRoutesDropped = Math.subtractExact(supplied, routes.size());
	}

	static NativeProofProduct of(Collection<Route> routes) {
		return new NativeProofProduct(routes);
	}

	static NativeProofProduct union(Collection<NativeProofProduct> products) {
		Objects.requireNonNull(products, "native proof products");
		List<Route> routes = new ArrayList<>();
		for(NativeProofProduct product : products)
			routes.addAll(Objects.requireNonNull(product, "native proof product").routes);
		return of(routes);
	}

	static Route route(DurableAnchorKey externalSeed, DurableAnchorKey outputWorkerPoolWitness,
		boolean exactPartitionRanges, List<List<CandidateRealizationInputBinding>> bindingOptions) {
		return new Route(externalSeed, outputWorkerPoolWitness, exactPartitionRanges, bindingOptions);
	}

	List<Route> routes() { return routes; }
	long checkedRawCardinality() { return rawCardinality; }
	long bindingAtomCount() { return bindingAtoms; }
	long duplicateRoutesDropped() { return duplicateRoutesDropped; }
	long constructionMaterializedLeaves() { return 0; }

	NativeProofProduct reseed(DurableAnchorKey externalSeed) {
		Objects.requireNonNull(externalSeed, "external seed");
		return of(routes.stream().map(route -> route.withExternalSeed(externalSeed)).toList());
	}

	NativeProofProduct rebindRoot(CandidateRealizationReference cachedRoot,
		CandidateRealizationReference requestedRoot) {
		Objects.requireNonNull(cachedRoot, "cached root");
		Objects.requireNonNull(requestedRoot, "requested root");
		return mapAtoms(binding -> binding.source().rule().parentOccurrence()
			== cachedRoot.rule().parentOccurrence() && binding.source().equals(cachedRoot)
				? new CandidateRealizationInputBinding(binding.inputPosition(), requestedRoot,
					binding.kind(), binding.relocationAction())
				: binding);
	}

	NativeProofProduct filterAtoms(Predicate<CandidateRealizationInputBinding> predicate) {
		Objects.requireNonNull(predicate, "binding predicate");
		List<Route> filtered = new ArrayList<>(routes.size());
		for(Route route : routes) {
			List<List<CandidateRealizationInputBinding>> dimensions = new ArrayList<>(
				route.bindingOptions().size());
			boolean complete = true;
			for(List<CandidateRealizationInputBinding> dimension : route.bindingOptions()) {
				List<CandidateRealizationInputBinding> retained = dimension.stream()
					.filter(predicate).toList();
				if(retained.isEmpty()) {
					complete = false;
					break;
				}
				dimensions.add(retained);
			}
			if(complete)
				filtered.add(route.withBindingOptions(dimensions));
		}
		return of(filtered);
	}

	/** Exact disjoint decomposition of leaves for which at least one atom matches. */
	NativeProofProduct selectAnyAtom(Predicate<CandidateRealizationInputBinding> predicate) {
		Objects.requireNonNull(predicate, "binding predicate");
		List<Route> selected = new ArrayList<>();
		for(Route route : routes) {
			List<List<CandidateRealizationInputBinding>> dimensions = route.bindingOptions();
			for(int match = 0; match < dimensions.size(); match++) {
				List<List<CandidateRealizationInputBinding>> branch = new ArrayList<>(dimensions.size());
				boolean complete = true;
				for(int index = 0; index < dimensions.size(); index++) {
					List<CandidateRealizationInputBinding> choices = dimensions.get(index);
					List<CandidateRealizationInputBinding> retained = index < match
						? choices.stream().filter(predicate.negate()).toList()
						: index == match ? choices.stream().filter(predicate).toList() : choices;
					if(retained.isEmpty()) {
						complete = false;
						break;
					}
					branch.add(retained);
				}
				if(complete)
					selected.add(route.withBindingOptions(branch));
			}
		}
		return of(selected);
	}

	NativeProofProduct mapAtoms(
		Function<CandidateRealizationInputBinding,CandidateRealizationInputBinding> mapper) {
		Objects.requireNonNull(mapper, "binding mapper");
		List<Route> mapped = new ArrayList<>(routes.size());
		for(Route route : routes) {
			List<List<CandidateRealizationInputBinding>> dimensions = new ArrayList<>(
				route.bindingOptions().size());
			for(List<CandidateRealizationInputBinding> dimension : route.bindingOptions()) {
				Set<CandidateRealizationInputBinding> transformed = new LinkedHashSet<>();
				for(CandidateRealizationInputBinding binding : dimension)
					transformed.add(Objects.requireNonNull(mapper.apply(binding), "mapped binding"));
				dimensions.add(List.copyOf(transformed));
			}
			mapped.add(route.withBindingOptions(dimensions));
		}
		return of(mapped);
	}

	LegacyExport exportLegacy() {
		Set<NativeContinuityProof> distinct = new LinkedHashSet<>();
		long[] materialized = {0};
		for(Route route : routes)
			exportRoute(route, 0, new ArrayList<>(), distinct, materialized);
		List<NativeContinuityProof> proofs = distinct.stream()
			.sorted(Comparator.comparing(NativeContinuityProof::normalizedSignature)).toList();
		return new LegacyExport(proofs, materialized[0]);
	}

	private static void exportRoute(Route route, int ordinal,
		List<CandidateRealizationInputBinding> bindings, Set<NativeContinuityProof> proofs,
		long[] materialized) {
		if(ordinal == route.bindingOptions().size()) {
			materialized[0] = Math.incrementExact(materialized[0]);
			proofs.add(new NativeContinuityProof(route.externalSeed(),
				route.outputWorkerPoolWitness(), route.exactPartitionRanges(), bindings));
			return;
		}
		for(CandidateRealizationInputBinding binding : route.bindingOptions().get(ordinal)) {
			bindings.add(binding);
			exportRoute(route, ordinal + 1, bindings, proofs, materialized);
			bindings.remove(bindings.size() - 1);
		}
	}

	static final class Route {
		private final DurableAnchorKey externalSeed;
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<List<CandidateRealizationInputBinding>> bindingOptions;
		private final long checkedCardinality;
		private final long bindingAtomCount;
		private final int hashCode;

		private Route(DurableAnchorKey externalSeed, DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> bindingOptions) {
			this.externalSeed = Objects.requireNonNull(externalSeed, "external seed");
			this.outputWorkerPoolWitness = Objects.requireNonNull(
				outputWorkerPoolWitness, "output worker-pool witness");
			Objects.requireNonNull(bindingOptions, "binding options");
			this.exactPartitionRanges = exactPartitionRanges;
			List<List<CandidateRealizationInputBinding>> copied = new ArrayList<>(bindingOptions.size());
			long cardinality = 1;
			long atoms = 0;
			int priorPosition = -1;
			for(List<CandidateRealizationInputBinding> options : bindingOptions) {
				Objects.requireNonNull(options, "binding option dimension");
				if(options.isEmpty())
					throw new IllegalArgumentException("Native proof product dimension must not be empty");
				List<CandidateRealizationInputBinding> dimension = List.copyOf(options);
				int position = Objects.requireNonNull(dimension.get(0), "binding option").inputPosition();
				if(position <= priorPosition)
					throw new IllegalArgumentException(
						"Native proof product positions must be strictly increasing");
				Set<CandidateRealizationInputBinding> unique = new LinkedHashSet<>();
				for(CandidateRealizationInputBinding binding : dimension) {
					Objects.requireNonNull(binding, "binding option");
					if(binding.inputPosition() != position)
						throw new IllegalArgumentException(
							"Native proof product dimension mixes input positions");
					if(!unique.add(binding))
						throw new IllegalArgumentException(
							"Native proof product dimension contains a duplicate binding");
				}
				priorPosition = position;
				copied.add(dimension);
				cardinality = Math.multiplyExact(cardinality, dimension.size());
				atoms = Math.addExact(atoms, dimension.size());
			}
			this.bindingOptions = List.copyOf(copied);
			checkedCardinality = cardinality;
			bindingAtomCount = atoms;
			int hash = 31 * externalSeed.hashCode() + outputWorkerPoolWitness.hashCode();
			hash = 31 * hash + Boolean.hashCode(exactPartitionRanges);
			hashCode = 31 * hash + this.bindingOptions.hashCode();
		}

		DurableAnchorKey externalSeed() { return externalSeed; }
		DurableAnchorKey outputWorkerPoolWitness() { return outputWorkerPoolWitness; }
		boolean exactPartitionRanges() { return exactPartitionRanges; }
		List<List<CandidateRealizationInputBinding>> bindingOptions() { return bindingOptions; }
		long checkedCardinality() { return checkedCardinality; }
		long bindingAtomCount() { return bindingAtomCount; }

		private Route withBindingOptions(List<List<CandidateRealizationInputBinding>> options) {
			return new Route(externalSeed, outputWorkerPoolWitness, exactPartitionRanges, options);
		}

		private Route withExternalSeed(DurableAnchorKey seed) {
			return new Route(seed, outputWorkerPoolWitness, exactPartitionRanges, bindingOptions);
		}

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof Route that))
				return false;
			return exactPartitionRanges == that.exactPartitionRanges
				&& externalSeed.equals(that.externalSeed)
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				&& bindingOptions.equals(that.bindingOptions);
		}
	}

	record LegacyExport(List<NativeContinuityProof> proofs, long materializedLeaves) {
		LegacyExport {
			proofs = List.copyOf(Objects.requireNonNull(proofs, "legacy proofs"));
			if(materializedLeaves < 0)
				throw new IllegalArgumentException("Materialized leaf count must not be negative");
		}
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.IndependentSupportAxis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.IndependentSupportOption;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.IndependentSupportProduct;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;

/**
 * Exact Cartesian support relation with uniform proof and native-pool authority.
 *
 * <p>The relation retains the sum of its input alternatives and materializes an
 * individual support clause only when a legacy {@link List} consumer requests it.
 * A materialized clause is cached by ordinal so identity-based receipt checks keep
 * their existing semantics.</p>
 */
final class FactorizedSupportClauses extends AbstractList<CandidateRealizationSupportClause>
	implements RandomAccess {
	private final List<PlacementProofKey> proofs;
	private final List<List<CandidateRealizationInputBinding>> factors;
	private final DurableAnchorKey nativeWorkerPoolWitness;
	private final boolean nativeWorkerPoolLayoutExact;
	private final int size;
	private final ConcurrentHashMap<Integer,CandidateRealizationSupportClause> materialized;
	private int cachedHash;
	private volatile boolean hashComputed;

	private FactorizedSupportClauses(List<PlacementProofKey> proofs,
		List<List<CandidateRealizationInputBinding>> factors,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) {
		this.proofs = PlacementAnalysis.sharedCanonicalComparableList(proofs,
			"factorized realization proof dependency");
		this.factors = canonicalFactors(factors);
		this.nativeWorkerPoolWitness = nativeWorkerPoolWitness;
		this.nativeWorkerPoolLayoutExact = nativeWorkerPoolLayoutExact;
		validateWitness();
		long cardinality = 1;
		for(List<CandidateRealizationInputBinding> factor : this.factors) {
			cardinality *= factor.size();
			if(cardinality > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Factorized support relation exceeds List capacity");
		}
		size = (int)cardinality;
		materialized = new ConcurrentHashMap<>();
	}

	static FactorizedSupportClauses of(List<PlacementProofKey> proofs,
		List<List<CandidateRealizationInputBinding>> factors,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) {
		return new FactorizedSupportClauses(proofs, factors,
			nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact);
	}

	static Optional<FactorizedSupportClauses> fromExplicitIfRectangular(
		List<CandidateRealizationSupportClause> clauses) {
		Objects.requireNonNull(clauses, "explicit realization support clauses");
		if(clauses.isEmpty())
			return Optional.empty();
		CandidateRealizationSupportClause first = clauses.get(0);
		List<PlacementProofKey> proofs = first.proofDependencies();
		DurableAnchorKey witness = first.nativeWorkerPoolWitness();
		boolean layoutExact = first.nativeWorkerPoolLayoutExact();
		java.util.Map<Integer,Set<CandidateRealizationInputBinding>> options =
			new java.util.TreeMap<>();
		Set<List<CandidateRealizationInputBinding>> tuples = new HashSet<>();
		for(CandidateRealizationSupportClause clause : clauses) {
			if(!sameIdentitySequence(proofs, clause.proofDependencies())
				|| !Objects.equals(witness, clause.nativeWorkerPoolWitness())
				|| layoutExact != clause.nativeWorkerPoolLayoutExact())
				return Optional.empty();
			Set<Integer> positions = new HashSet<>();
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				if(!positions.add(binding.inputPosition()))
					return Optional.empty();
				options.computeIfAbsent(binding.inputPosition(), ignored -> new HashSet<>()).add(binding);
			}
			if(!tuples.add(clause.inputBindings()))
				return Optional.empty();
		}
		int factorCount = first.inputBindings().size();
		if(options.size() != factorCount)
			return Optional.empty();
		for(CandidateRealizationSupportClause clause : clauses)
			if(clause.inputBindings().size() != factorCount
				|| clause.inputBindings().stream().anyMatch(binding ->
					!options.containsKey(binding.inputPosition())))
				return Optional.empty();
		List<List<CandidateRealizationInputBinding>> factors = options.values().stream()
			.map(option -> PlacementAnalysis.sharedCanonicalComparableList(option,
				"factorized realization input binding"))
			.toList();
		FactorizedSupportClauses candidate;
		try {
			candidate = of(proofs, factors, witness, layoutExact);
		}
		catch(IllegalArgumentException correlated) {
			return Optional.empty();
		}
		if(candidate.size() != clauses.size())
			return Optional.empty();
		for(int ordinal = 0; ordinal < candidate.size(); ordinal++) {
			List<CandidateRealizationInputBinding> tuple = candidate.bindingsAt(ordinal);
			if(!tuples.contains(tuple))
				return Optional.empty();
		}
		return Optional.of(candidate);
	}

	private static boolean sameIdentitySequence(List<?> left, List<?> right) {
		if(left.size() != right.size())
			return false;
		for(int index = 0; index < left.size(); index++)
			if(left.get(index) != right.get(index))
				return false;
		return true;
	}

	private static List<List<CandidateRealizationInputBinding>> canonicalFactors(
		List<List<CandidateRealizationInputBinding>> inputFactors) {
		Objects.requireNonNull(inputFactors, "factorized support input factors");
		if(inputFactors.isEmpty())
			return List.of();
		List<List<CandidateRealizationInputBinding>> canonical = new ArrayList<>(inputFactors.size());
		Set<Integer> positions = new HashSet<>();
		Set<CompiledHopKey> sourceOwners = new HashSet<>();
		for(List<CandidateRealizationInputBinding> inputFactor : inputFactors) {
			List<CandidateRealizationInputBinding> factor = PlacementAnalysis.sharedCanonicalComparableList(
				Objects.requireNonNull(inputFactor, "factorized support input factor"),
				"factorized realization input binding");
			if(factor.isEmpty())
				throw new IllegalArgumentException("Factorized support input factor must not be empty");
			int position = factor.get(0).inputPosition();
			if(!positions.add(position))
				throw new IllegalArgumentException("Factorized support requires one factor per input position");
			Set<CompiledHopKey> factorOwners = new HashSet<>();
			for(CandidateRealizationInputBinding binding : factor) {
				if(binding.inputPosition() != position)
					throw new IllegalArgumentException(
						"One factor cannot mix realization input positions");
				factorOwners.add(binding.source().rule().parentOccurrence());
			}
			for(CompiledHopKey owner : factorOwners)
				if(!sourceOwners.add(owner))
					throw new IllegalArgumentException(
						"Repeated source owner requires an explicit correlated support relation");
			canonical.add(factor);
		}
		canonical.sort((left, right) -> left.get(0).compareTo(right.get(0)));
		return List.copyOf(canonical);
	}

	private void validateWitness() {
		if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
			throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
		if(nativeWorkerPoolWitness != null && proofs.stream().noneMatch(proof ->
			proof.kind() == PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY
				&& proof.owner() != null))
			throw new IllegalArgumentException(
				"Native worker-pool witness requires owned native-continuity proof");
	}

	@Override
	public CandidateRealizationSupportClause get(int index) {
		Objects.checkIndex(index, size);
		CandidateRealizationSupportClause cached = materialized.get(index);
		if(cached != null)
			return cached;
		return materialized.computeIfAbsent(index, this::materializeClause);
	}

	@Override public int size() { return size; }

	@Override
	public boolean equals(Object other) {
		if(this == other)
			return true;
		if(!(other instanceof List<?> that) || size != that.size())
			return false;
		if(that instanceof FactorizedSupportClauses factorized)
			return proofs.equals(factorized.proofs)
				&& factors.equals(factorized.factors)
				&& Objects.equals(nativeWorkerPoolWitness, factorized.nativeWorkerPoolWitness)
				&& nativeWorkerPoolLayoutExact == factorized.nativeWorkerPoolLayoutExact;
		for(int ordinal = 0; ordinal < size; ordinal++)
			if(!matchesClauseAt(that.get(ordinal), ordinal))
				return false;
		return true;
	}

	@Override
	public int hashCode() {
		if(!hashComputed) {
			cachedHash = factorizedListHash();
			hashComputed = true;
		}
		return cachedHash;
	}

	@Override
	public boolean contains(Object value) {
		return indexOf(value) >= 0;
	}

	@Override
	public int indexOf(Object value) {
		if(!(value instanceof CandidateRealizationSupportClause clause)
			|| !proofs.equals(clause.proofDependencies())
			|| !Objects.equals(nativeWorkerPoolWitness, clause.nativeWorkerPoolWitness())
			|| nativeWorkerPoolLayoutExact != clause.nativeWorkerPoolLayoutExact()
			|| clause.inputBindings().size() != factors.size())
			return -1;
		int ordinal = 0;
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			CandidateRealizationInputBinding binding = clause.inputBindings().get(factorIndex);
			List<CandidateRealizationInputBinding> factor = factors.get(factorIndex);
			int option = factor.indexOf(binding);
			if(option < 0)
				return -1;
			ordinal = ordinal * factor.size() + option;
		}
		return ordinal;
	}

	@Override public int lastIndexOf(Object value) { return indexOf(value); }

	int firstIdentityOrdinal(Object value) {
		for(java.util.Map.Entry<Integer,CandidateRealizationSupportClause> entry : materialized.entrySet())
			if(entry.getValue() == value)
				return entry.getKey();
		return -1;
	}

	Optional<FactorizedSupportClauses> oneAxisUnion(FactorizedSupportClauses that) {
		if(!sameIdentitySequence(proofs, that.proofs)
			|| !Objects.equals(nativeWorkerPoolWitness, that.nativeWorkerPoolWitness)
			|| nativeWorkerPoolLayoutExact != that.nativeWorkerPoolLayoutExact
			|| factors.size() != that.factors.size())
			return Optional.empty();
		// A contained rectangle adds no tuples, even when several axes differ.
		// Keep exact owner/action identities: value equality alone is insufficient
		// for reusing graph-owned bindings across closure revisions.
		boolean containsOther = true;
		boolean containedByOther = true;
		for(int axis = 0; axis < factors.size() && (containsOther || containedByOther); axis++) {
			List<CandidateRealizationInputBinding> left = factors.get(axis);
			List<CandidateRealizationInputBinding> right = that.factors.get(axis);
			if(sameBindingAuthority(left, right))
				continue;
			if(containsOther)
				containsOther = containsBindingsWithAuthority(left, right);
			if(containedByOther)
				containedByOther = containsBindingsWithAuthority(right, left);
		}
		if(containsOther || containedByOther)
			return Optional.of(containsOther ? this : that);
		List<List<CandidateRealizationInputBinding>> union = new ArrayList<>(factors.size());
		int changedAxis = -1;
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			List<CandidateRealizationInputBinding> left = factors.get(factorIndex);
			List<CandidateRealizationInputBinding> right = that.factors.get(factorIndex);
			if(sameBindingAuthority(left, right)) {
				union.add(left);
				continue;
			}
			if(changedAxis >= 0)
				return Optional.empty();
			changedAxis = factorIndex;
			List<CandidateRealizationInputBinding> alternatives = new ArrayList<>(left);
			for(CandidateRealizationInputBinding candidate : right) {
				boolean exact = alternatives.stream().anyMatch(existing ->
					sameBindingAuthority(existing, candidate));
				if(exact)
					continue;
				if(alternatives.contains(candidate))
					return Optional.empty();
				alternatives.add(candidate);
			}
			union.add(PlacementAnalysis.sharedCanonicalComparableList(alternatives,
				"factorized realization input binding"));
		}
		if(changedAxis < 0)
			return Optional.of(this);
		try {
			return Optional.of(of(proofs, union,
				nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact));
		}
		catch(IllegalArgumentException correlated) {
			return Optional.empty();
		}
	}

	private static boolean containsBindingsWithAuthority(List<CandidateRealizationInputBinding> outer,
		List<CandidateRealizationInputBinding> inner) {
		if(outer.size() < inner.size())
			return false;
		java.util.Map<CandidateRealizationInputBinding,CandidateRealizationInputBinding> indexed =
			new java.util.HashMap<>();
		for(CandidateRealizationInputBinding binding : outer)
			indexed.put(binding, binding);
		for(CandidateRealizationInputBinding binding : inner) {
			CandidateRealizationInputBinding existing = indexed.get(binding);
			if(existing == null || !sameBindingAuthority(existing, binding))
				return false;
		}
		return true;
	}

	boolean sameExactAuthority(FactorizedSupportClauses that) {
		if(!sameIdentitySequence(proofs, that.proofs)
			|| !Objects.equals(nativeWorkerPoolWitness, that.nativeWorkerPoolWitness)
			|| nativeWorkerPoolLayoutExact != that.nativeWorkerPoolLayoutExact
			|| factors.size() != that.factors.size())
			return false;
		for(int index = 0; index < factors.size(); index++)
			if(!sameBindingAuthority(factors.get(index), that.factors.get(index)))
				return false;
		return true;
	}

	private static boolean sameBindingAuthority(
		List<CandidateRealizationInputBinding> left,
		List<CandidateRealizationInputBinding> right) {
		if(left.size() != right.size())
			return false;
		for(int index = 0; index < left.size(); index++)
			if(!sameBindingAuthority(left.get(index), right.get(index)))
				return false;
		return true;
	}

	private static boolean sameBindingAuthority(
		CandidateRealizationInputBinding left,
		CandidateRealizationInputBinding right) {
		return left.equals(right)
			&& left.source().rule().parentOccurrence() == right.source().rule().parentOccurrence()
			&& left.relocationAction() == right.relocationAction();
	}

	Optional<FactorizedSupportClauses> restrictBindings(
		Predicate<CandidateRealizationInputBinding> retained) {
		Objects.requireNonNull(retained, "retained binding predicate");
		List<List<CandidateRealizationInputBinding>> restricted = new ArrayList<>(factors.size());
		boolean changed = false;
		for(List<CandidateRealizationInputBinding> factor : factors) {
			List<CandidateRealizationInputBinding> options = factor.stream().filter(retained).toList();
			if(options.isEmpty())
				return Optional.empty();
			changed |= options.size() != factor.size();
			restricted.add(options.size() == factor.size() ? factor : options);
		}
		return Optional.of(changed ? of(proofs, restricted,
			nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact) : this);
	}

	private List<CandidateRealizationInputBinding> bindingsAt(int index) {
		int remainder = index;
		CandidateRealizationInputBinding[] selected =
			new CandidateRealizationInputBinding[factors.size()];
		for(int factorIndex = factors.size() - 1; factorIndex >= 0; factorIndex--) {
			List<CandidateRealizationInputBinding> factor = factors.get(factorIndex);
			selected[factorIndex] = factor.get(remainder % factor.size());
			remainder /= factor.size();
		}
		return List.of(selected);
	}

	int ordinalOfBindings(List<CandidateRealizationInputBinding> bindings) {
		if(bindings.size() != factors.size())
			return -1;
		int ordinal = 0;
		for(int axis = 0; axis < factors.size(); axis++) {
			int option = factors.get(axis).indexOf(bindings.get(axis));
			if(option < 0)
				return -1;
			ordinal = ordinal * factors.get(axis).size() + option;
		}
		return ordinal;
	}

	Optional<IndependentSupportProduct> independentRelocationProduct() {
		List<IndependentSupportAxis> axes = new ArrayList<>(factors.size());
		Set<CompiledHopKey> owners = java.util.Collections.newSetFromMap(
			new java.util.IdentityHashMap<>());
		for(List<CandidateRealizationInputBinding> factor : factors) {
			CandidateRealizationInputBinding first = factor.get(0);
			if(first.kind() != PlacementIdentity.CandidateInputBindingKind.RELOCATION
				&& first.kind() != PlacementIdentity.CandidateInputBindingKind.DIRECT)
				return Optional.empty();
			CompiledHopKey owner = first.source().rule().parentOccurrence();
			if(!owners.add(owner))
				return Optional.empty();
			PlacementIdentity.DurableAnchorKey directAnchor =
				first.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT
					&& first.source().realization().layoutKind()
						== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
					? first.source().realization().durableAnchor() : null;
			if(first.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT
				&& directAnchor == null)
				return Optional.empty();
			List<IndependentSupportOption> options = new ArrayList<>(factor.size());
			Set<PlacementIdentity.CandidateRealizationSupportKey> keys = new HashSet<>();
			for(CandidateRealizationInputBinding binding : factor) {
				if(binding.inputPosition() != first.inputPosition()
					|| binding.kind() != first.kind()
					|| binding.source().realization().layoutKind()
						== PlacementIdentity.PlacementLayoutKind.VALUE_MAP
					|| binding.source().rule().parentOccurrence() != owner
					|| binding.relocationAction() != first.relocationAction())
					return Optional.empty();
				if(directAnchor != null && (binding.source().realization().layoutKind()
						!= PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
					|| binding.source().realization().durableAnchor().fType() != directAnchor.fType()
					|| !PlacementIdentity.samePhysicalLayout(directAnchor,
						binding.source().realization().durableAnchor())))
					return Optional.empty();
				PlacementIdentity.CandidateRealizationSupportKey key =
					CandidateSelections.requiredInputSupportIdentity(binding.source());
				if(!keys.add(key))
					return Optional.empty();
				options.add(new IndependentSupportOption(key, binding));
			}
			axes.add(new IndependentSupportAxis(first.inputPosition(), owner, first.kind(),
				first.relocationAction(), options));
		}
		return axes.isEmpty() ? Optional.empty()
			: Optional.of(new IndependentSupportProduct(this, axes));
	}

	private CandidateRealizationSupportClause materializeClause(int index) {
		return new CandidateRealizationSupportClause(
			proofs, bindingsAt(index), nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact);
	}

	private boolean matchesClauseAt(Object value, int ordinal) {
		if(!(value instanceof CandidateRealizationSupportClause clause))
			return false;
		return proofs.equals(clause.proofDependencies())
			&& bindingsAt(ordinal).equals(clause.inputBindings())
			&& Objects.equals(nativeWorkerPoolWitness, clause.nativeWorkerPoolWitness())
			&& nativeWorkerPoolLayoutExact == clause.nativeWorkerPoolLayoutExact();
	}

	private int factorizedListHash() {
		int tupleWeights = geometricPowers(31, size).sum();
		int bindingsWeightedHash = pow31(factors.size()) * tupleWeights;
		int suffixCardinality = size;
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			List<CandidateRealizationInputBinding> factor = factors.get(factorIndex);
			suffixCardinality /= factor.size();
			int prefixCardinality = size / (factor.size() * suffixCardinality);
			int prefixWeights = geometricPowers(
				pow31(factor.size() * suffixCardinality), prefixCardinality).sum();
			int optionWeights = 0;
			for(int option = 0; option < factor.size(); option++)
				optionWeights += factor.get(option).hashCode()
					* pow31((factor.size() - 1 - option) * suffixCardinality);
			int tailWeights = geometricPowers(31, suffixCardinality).sum();
			bindingsWeightedHash += pow31(factors.size() - 1 - factorIndex)
				* prefixWeights * optionWeights * tailWeights;
		}
		int witnessHash = nativeWorkerPoolWitness == null ? 0 : nativeWorkerPoolWitness.hashCode();
		int clauseConstant = proofs.hashCode() * pow31(3)
			+ witnessHash * 31 + Boolean.hashCode(nativeWorkerPoolLayoutExact);
		int weightedClauseHashes = clauseConstant * tupleWeights
			+ pow31(2) * bindingsWeightedHash;
		return pow31(size) + weightedClauseHashes;
	}

	private static int pow31(int exponent) {
		int result = 1;
		int base = 31;
		for(int remaining = exponent; remaining != 0; remaining >>>= 1) {
			if((remaining & 1) != 0)
				result *= base;
			base *= base;
		}
		return result;
	}

	private static PowerSum geometricPowers(int base, int count) {
		PowerSum result = new PowerSum(1, 0);
		PowerSum block = new PowerSum(base, 1);
		for(int remaining = count; remaining != 0; remaining >>>= 1) {
			if((remaining & 1) != 0)
				result = result.append(block);
			block = block.append(block);
		}
		return result;
	}

	private record PowerSum(int power, int sum) {
		private PowerSum append(PowerSum highExponents) {
			return new PowerSum(power * highExponents.power,
				sum + power * highExponents.sum);
		}
	}

	List<PlacementProofKey> proofs() { return proofs; }
	List<List<CandidateRealizationInputBinding>> factors() { return factors; }
	DurableAnchorKey nativeWorkerPoolWitness() { return nativeWorkerPoolWitness; }
	boolean nativeWorkerPoolLayoutExact() { return nativeWorkerPoolLayoutExact; }
	int retainedFactorOptionCount() {
		int count = 0;
		for(List<CandidateRealizationInputBinding> factor : factors)
			count += factor.size();
		return count;
	}
	int materializedClauseCount() {
		return materialized.size();
	}
}

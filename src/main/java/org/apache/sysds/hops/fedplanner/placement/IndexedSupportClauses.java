/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;

/**
 * Immutable storage for an already admitted set of complete support tuples.
 *
 * <p>Only legal whole-combination IDs and identity-preserving dictionaries are
 * retained. Individual clause objects are lightweight, cached handles created on
 * demand; the canonical input clauses are never retained.</p>
 */
final class IndexedSupportClauses extends AbstractList<CandidateRealizationSupportClause>
	implements RandomAccess {
	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

	private static final class Metadata {
		private final List<PlacementProofKey> proofs;
		private final DurableAnchorKey witness;
		private final boolean layoutExact;
		private final int identityHash;

		private Metadata(List<PlacementProofKey> proofs, DurableAnchorKey witness,
			boolean layoutExact) {
			this.proofs = proofs;
			this.witness = witness;
			this.layoutExact = layoutExact;
			int hash = 1;
			for(PlacementProofKey proof : proofs)
				hash = 31 * hash + System.identityHashCode(proof);
			hash = 31 * hash + System.identityHashCode(witness);
			identityHash = 31 * hash + Boolean.hashCode(layoutExact);
		}

		@Override public int hashCode() { return identityHash; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof Metadata that
				&& witness == that.witness && layoutExact == that.layoutExact
				&& sameIdentitySequence(proofs, that.proofs);
		}
	}

	private final List<Metadata> metadata;
	private final List<List<CandidateRealizationInputBinding>> bindingOptions;
	private final List<CandidateRealizationInputBinding> uniqueBindings;
	private final long[] radices;
	private final BigInteger[] suffixMultipliers;
	private final long[] suffixMultipliersLong;
	private final long metadataDivisorLong;
	private final long[] combinationIds;
	private final BigInteger[] largeCombinationIds;
	private final ConcurrentHashMap<Integer,CandidateRealizationSupportClause> handles =
		new ConcurrentHashMap<>();
	private int cachedHash;
	private volatile boolean hashComputed;

	private IndexedSupportClauses(List<Metadata> metadata,
		List<List<CandidateRealizationInputBinding>> bindingOptions,
		List<CandidateRealizationInputBinding> uniqueBindings, long[] radices,
		BigInteger[] suffixMultipliers, long[] suffixMultipliersLong,
		long metadataDivisorLong, long[] combinationIds,
		BigInteger[] largeCombinationIds) {
		this.metadata = List.copyOf(metadata);
		this.bindingOptions = bindingOptions.stream().map(List::copyOf).toList();
		this.uniqueBindings = List.copyOf(uniqueBindings);
		this.radices = radices;
		this.suffixMultipliers = suffixMultipliers;
		this.suffixMultipliersLong = suffixMultipliersLong;
		this.metadataDivisorLong = metadataDivisorLong;
		this.combinationIds = combinationIds;
		this.largeCombinationIds = largeCombinationIds;
	}

	static IndexedSupportClauses fromCanonical(
		List<CandidateRealizationSupportClause> clauses) {
		Objects.requireNonNull(clauses, "canonical support clauses");
		List<Metadata> metadata = new ArrayList<>();
		Map<Metadata,Integer> metadataOrdinals = new HashMap<>();
		List<List<CandidateRealizationInputBinding>> options = new ArrayList<>();
		List<Map<CandidateRealizationInputBinding,Integer>> optionOrdinals = new ArrayList<>();
		List<CandidateRealizationInputBinding> uniqueBindings = new ArrayList<>();
		Map<CandidateRealizationInputBinding,Boolean> uniqueBindingIdentities =
			new IdentityHashMap<>();
		List<int[]> rowOptions = new ArrayList<>(clauses.size());
		int[] rowMetadata = new int[clauses.size()];
		int row = 0;
		for(CandidateRealizationSupportClause clause : clauses) {
			Objects.requireNonNull(clause, "canonical support clause");
			rowMetadata[row] = metadataOrdinal(metadata, metadataOrdinals, clause);
			List<CandidateRealizationInputBinding> bindings = clause.inputBindings();
			while(options.size() < bindings.size()) {
				options.add(new ArrayList<>());
				optionOrdinals.add(new IdentityHashMap<>());
			}
			int[] encoded = new int[bindings.size()];
			for(int axis = 0; axis < bindings.size(); axis++) {
				CandidateRealizationInputBinding binding = bindings.get(axis);
				Map<CandidateRealizationInputBinding,Integer> ordinals = optionOrdinals.get(axis);
				Integer ordinal = ordinals.get(binding);
				if(ordinal == null) {
					ordinal = options.get(axis).size();
					options.get(axis).add(binding);
					ordinals.put(binding, ordinal);
				}
				encoded[axis] = ordinal + 1; // zero is trailing absence
				if(uniqueBindingIdentities.put(binding, Boolean.TRUE) == null)
					uniqueBindings.add(binding);
			}
			rowOptions.add(encoded);
			row++;
		}

		long[] radices = new long[options.size()];
		BigInteger[] suffix = new BigInteger[options.size()];
		long[] suffixLong = new long[options.size()];
		BigInteger multiplier = BigInteger.ONE;
		for(int axis = options.size() - 1; axis >= 0; axis--) {
			suffix[axis] = multiplier;
			suffixLong[axis] = multiplier.compareTo(LONG_MAX) <= 0
				? multiplier.longValueExact() : -1L;
			radices[axis] = (long)options.get(axis).size() + 1L;
			multiplier = multiplier.multiply(BigInteger.valueOf(radices[axis]));
		}
		BigInteger[] ids = new BigInteger[clauses.size()];
		boolean requiresBigInteger = false;
		for(int ordinal = 0; ordinal < ids.length; ordinal++) {
			BigInteger id = BigInteger.valueOf(rowMetadata[ordinal]).multiply(multiplier);
			int[] digits = rowOptions.get(ordinal);
			for(int axis = 0; axis < digits.length; axis++)
				id = id.add(suffix[axis].multiply(BigInteger.valueOf(digits[axis])));
			ids[ordinal] = id;
			requiresBigInteger |= id.compareTo(LONG_MAX) > 0;
		}
		long[] compactIds = null;
		BigInteger[] largeIds = null;
		if(requiresBigInteger)
			largeIds = ids;
		else {
			compactIds = new long[ids.length];
			for(int ordinal = 0; ordinal < ids.length; ordinal++)
				compactIds[ordinal] = ids[ordinal].longValueExact();
		}
		long metadataDivisorLong = multiplier.compareTo(LONG_MAX) <= 0
			? multiplier.longValueExact() : -1L;
		return new IndexedSupportClauses(metadata, options, uniqueBindings, radices,
			suffix, suffixLong, metadataDivisorLong, compactIds, largeIds);
	}

	private static int metadataOrdinal(List<Metadata> metadata,
		Map<Metadata,Integer> ordinals,
		CandidateRealizationSupportClause clause) {
		Metadata key = new Metadata(clause.proofDependencies(),
			clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
		Integer retained = ordinals.get(key);
		if(retained != null)
			return retained;
		int ordinal = metadata.size();
		metadata.add(key);
		ordinals.put(key, ordinal);
		return ordinal;
	}

	private static boolean sameIdentitySequence(List<?> left, List<?> right) {
		if(left.size() != right.size())
			return false;
		for(int index = 0; index < left.size(); index++)
			if(left.get(index) != right.get(index))
				return false;
		return true;
	}

	@Override
	public CandidateRealizationSupportClause get(int row) {
		Objects.checkIndex(row, size());
		CandidateRealizationSupportClause current = handles.get(row);
		return current != null ? current
			: handles.computeIfAbsent(row, ordinal -> CandidateRealizationSupportClause.indexed(this, ordinal));
	}

	@Override public int size() {
		return combinationIds == null ? largeCombinationIds.length : combinationIds.length;
	}

	List<PlacementProofKey> proofsAt(int row) {
		return metadata.get(metadataOrdinalAt(row)).proofs;
	}

	List<CandidateRealizationInputBinding> bindingsAt(int row) {
		Objects.checkIndex(row, size());
		return new BindingView(row);
	}

	DurableAnchorKey witnessAt(int row) {
		return metadata.get(metadataOrdinalAt(row)).witness;
	}

	boolean layoutExactAt(int row) {
		return metadata.get(metadataOrdinalAt(row)).layoutExact;
	}

	BigInteger combinationIdAt(int row) {
		Objects.checkIndex(row, size());
		return combinationIds == null ? largeCombinationIds[row]
			: BigInteger.valueOf(combinationIds[row]);
	}

	int ordinalOfCombinationId(BigInteger id) {
		if(id == null || id.signum() < 0)
			return -1;
		if(combinationIds != null) {
			if(id.compareTo(LONG_MAX) > 0)
				return -1;
			long target = id.longValue();
			for(int ordinal = 0; ordinal < combinationIds.length; ordinal++)
				if(combinationIds[ordinal] == target)
					return ordinal;
		}
		else
			for(int ordinal = 0; ordinal < largeCombinationIds.length; ordinal++)
				if(largeCombinationIds[ordinal].equals(id))
					return ordinal;
		return -1;
	}

	int firstIdentityOrdinal(CandidateRealizationSupportClause clause) {
		if(clause == null || clause.indexedRelation() != this)
			return -1;
		int row = clause.indexedRowOrdinal();
		return handles.get(row) == clause ? row : -1;
	}

	int retainedBindingOptionCount() {
		return bindingOptions.stream().mapToInt(List::size).sum();
	}

	int retainedMetadataCount() { return metadata.size(); }
	int materializedHandleCount() { return handles.size(); }
	Collection<CandidateRealizationInputBinding> uniqueBindings() { return uniqueBindings; }
	boolean hasMissingNativeWitness() {
		return metadata.stream().anyMatch(candidate -> candidate.witness == null);
	}

	void validateRealizationKey(PlacementRealizationKey key) {
		Objects.requireNonNull(key, "realization key");
		for(Metadata candidate : metadata)
			if(candidate.witness != null && (key.layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
				|| key.emissionState().placementState().fType() != candidate.witness.fType()))
				throw new IllegalArgumentException(
					"Native worker-pool witness and realization layout differ");
		if(metadata.isEmpty())
			return;
		Metadata first = metadata.get(0);
		for(int ordinal = 1; ordinal < metadata.size(); ordinal++) {
			Metadata candidate = metadata.get(ordinal);
			if((first.witness == null) != (candidate.witness == null)
				|| first.witness != null && (first.layoutExact != candidate.layoutExact
					|| !(first.layoutExact
						? PlacementIdentity.samePhysicalLayout(first.witness, candidate.witness)
						: PlacementIdentity.samePhysicalWorkerEndpoints(first.witness, candidate.witness))))
				throw new IllegalArgumentException(
					"Candidate realization support clauses disagree on native worker-pool authority");
		}
	}

	@Override
	public boolean equals(Object other) {
		if(this == other)
			return true;
		if(!(other instanceof List<?> that) || size() != that.size())
			return false;
		if(that instanceof IndexedSupportClauses indexed) {
			for(int row = 0; row < size(); row++)
				if(!rowEquals(row, indexed, row))
					return false;
			return true;
		}
		for(int row = 0; row < size(); row++)
			if(!rowEquals(row, that.get(row)))
				return false;
		return true;
	}

	@Override
	public int hashCode() {
		if(!hashComputed) {
			int hash = 1;
			for(int row = 0; row < size(); row++)
				hash = 31 * hash + rowHash(row);
			cachedHash = hash;
			hashComputed = true;
		}
		return cachedHash;
	}

	@Override public int indexOf(Object value) {
		if(value instanceof CandidateRealizationSupportClause clause
			&& clause.indexedRelation() == this)
			return clause.indexedRowOrdinal();
		for(int row = 0; row < size(); row++)
			if(rowEquals(row, value))
				return row;
		return -1;
	}

	@Override public int lastIndexOf(Object value) { return indexOf(value); }
	@Override public boolean contains(Object value) { return indexOf(value) >= 0; }

	private int metadataOrdinalAt(int row) {
		Objects.checkIndex(row, size());
		if(metadata.isEmpty())
			throw new IllegalStateException("Indexed support row has no metadata");
		if(combinationIds != null)
			return metadataDivisorLong < 0 ? 0
				: Math.toIntExact(combinationIds[row] / metadataDivisorLong);
		BigInteger divisor = suffixMultipliers.length == 0 ? BigInteger.ONE
			: suffixMultipliers[0].multiply(BigInteger.valueOf(radices[0]));
		return combinationIdAt(row).divide(divisor).intValueExact();
	}

	private int digitAt(int row, int axis) {
		if(combinationIds != null) {
			long suffix = suffixMultipliersLong[axis];
			return suffix < 0 ? 0
				: Math.toIntExact((combinationIds[row] / suffix) % radices[axis]);
		}
		return combinationIdAt(row).divide(suffixMultipliers[axis])
			.mod(BigInteger.valueOf(radices[axis])).intValue();
	}

	private int bindingCount(int row) {
		int count = 0;
		while(count < bindingOptions.size() && digitAt(row, count) != 0)
			count++;
		return count;
	}

	private boolean rowEquals(int row, IndexedSupportClauses that, int thatRow) {
		return layoutExactAt(row) == that.layoutExactAt(thatRow)
			&& Objects.equals(witnessAt(row), that.witnessAt(thatRow))
			&& proofsAt(row).equals(that.proofsAt(thatRow))
			&& bindingsEqual(row, that, thatRow);
	}

	private boolean rowEquals(int row, Object value) {
		if(!(value instanceof CandidateRealizationSupportClause clause))
			return false;
		return layoutExactAt(row) == clause.nativeWorkerPoolLayoutExact()
			&& Objects.equals(witnessAt(row), clause.nativeWorkerPoolWitness())
			&& proofsAt(row).equals(clause.proofDependencies())
			&& bindingsEqual(row, clause.inputBindings());
	}

	private boolean bindingsEqual(int row, IndexedSupportClauses that, int thatRow) {
		int count = bindingCount(row);
		if(count != that.bindingCount(thatRow))
			return false;
		for(int axis = 0; axis < count; axis++)
			if(!bindingAt(row, axis).equals(that.bindingAt(thatRow, axis)))
				return false;
		return true;
	}

	private boolean bindingsEqual(int row, List<CandidateRealizationInputBinding> that) {
		int count = bindingCount(row);
		if(count != that.size())
			return false;
		for(int axis = 0; axis < count; axis++)
			if(!bindingAt(row, axis).equals(that.get(axis)))
				return false;
		return true;
	}

	private CandidateRealizationInputBinding bindingAt(int row, int axis) {
		int digit = digitAt(row, axis);
		if(digit == 0)
			throw new IndexOutOfBoundsException("Absent indexed support binding");
		return bindingOptions.get(axis).get(digit - 1);
	}

	private int rowHash(int row) {
		int hash = proofsAt(row).hashCode();
		int bindingHash = 1;
		int count = bindingCount(row);
		for(int axis = 0; axis < count; axis++)
			bindingHash = 31 * bindingHash + bindingAt(row, axis).hashCode();
		hash = 31 * hash + bindingHash;
		hash = 31 * hash + Objects.hashCode(witnessAt(row));
		return 31 * hash + Boolean.hashCode(layoutExactAt(row));
	}

	private final class BindingView extends AbstractList<CandidateRealizationInputBinding>
		implements RandomAccess {
		private final int row;
		private final int size;

		private BindingView(int row) {
			this.row = row;
			size = bindingCount(row);
		}

		@Override public CandidateRealizationInputBinding get(int index) {
			Objects.checkIndex(index, size);
			return bindingAt(row, index);
		}
		@Override public int size() { return size; }
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;

/**
 * Exact native-continuity product whose member proof is restored only when that
 * member is requested. The relation never substitutes one member's proof for
 * another: every handle owns the same proof and bindings as the explicit path.
 */
final class NativeContinuitySupportClauses
	extends AbstractList<CandidateRealizationSupportClause> implements RandomAccess {
	// Optional union representation only: exceeding these limits uses the exact
	// ordinary merge, never removes a legal support member. Units are not bytes.
	private static final int MAX_AUTHORITY_DONORS = 64;
	private static final long MAX_AUTHORITY_RETENTION_UNITS = 1_000_000L;
	private final CompiledHopKey owner;
	private final List<NativePlacementContinuity.NativeSupportProduct> products;
	private final NativeMultiHeaderIndex canonicalIndex;
	private final DurableAnchorKey clauseWitness;
	private final boolean clauseLayoutExact;
	private final List<AuthorityDonor> authorityDonors;
	private final ConcurrentHashMap<Integer,CandidateRealizationSupportClause> handles =
		new ConcurrentHashMap<>();

	NativeContinuitySupportClauses(CompiledHopKey owner,
		NativePlacementContinuity.NativeSupportProduct product,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
		this(owner, product, clauseWitness, clauseLayoutExact, List.of());
	}

	private NativeContinuitySupportClauses(CompiledHopKey owner,
		NativePlacementContinuity.NativeSupportProduct product,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact,
		List<AuthorityDonor> authorityDonors) {
		this(owner, List.of(product), clauseWitness, clauseLayoutExact, authorityDonors);
	}

	private NativeContinuitySupportClauses(CompiledHopKey owner,
		List<NativePlacementContinuity.NativeSupportProduct> products,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact,
		List<AuthorityDonor> authorityDonors) {
		this.owner = Objects.requireNonNull(owner, "native continuity proof owner");
		this.products = canonicalProducts(products);
		this.canonicalIndex = this.products.size() == 1 ? null : Objects.requireNonNull(
			NativeMultiHeaderIndex.tryCreate(this.products), "native support relation index");
		if(clauseWitness == null && !clauseLayoutExact)
			throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
		this.clauseWitness = clauseWitness;
		this.clauseLayoutExact = clauseLayoutExact;
		this.authorityDonors = List.copyOf(authorityDonors);
	}

	@Override public int size() {
		return canonicalIndex == null ? products.get(0).size() : canonicalIndex.size();
	}

	@Override
	public CandidateRealizationSupportClause get(int ordinal) {
		Objects.checkIndex(ordinal, size());
		CandidateRealizationSupportClause current = handles.get(ordinal);
		return current != null ? current : handles.computeIfAbsent(ordinal, this::materialize);
	}

	private CandidateRealizationSupportClause materialize(int ordinal) {
		NativeMember member = canonicalIndex == null
			? new NativeMember(products.get(0), products.get(0).bindingsAt(ordinal))
			: canonicalIndex.memberAt(ordinal);
		var bindings = member.bindings();
		for(AuthorityDonor donor : authorityDonors) {
			if(!donor.scope().sameHeaderAuthority(member.product())
				|| donor.scope().ordinalOfExactAuthorityBindings(bindings) < 0)
				continue;
			int donorOrdinal = donor.relation().ordinalOfExactAuthorityMember(
				member.product(), bindings);
			if(donorOrdinal >= 0)
				return donor.relation().get(donorOrdinal);
		}
		if(!authorityDonors.isEmpty())
			throw new IllegalStateException("Native product union lost exact member authority");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			member.product().externalSeed(), member.product().outputWorkerPoolWitness(),
			member.product().exactPartitionRanges(), bindings);
		return new CandidateRealizationSupportClause(List.of(proof.continuityProofKey(owner)),
			bindings, clauseWitness, clauseLayoutExact);
	}

	NativePlacementContinuity.NativeSupportProduct product() {
		return singleProduct().orElseThrow(() ->
			new IllegalStateException("Native relation has multiple proof headers"));
	}
	List<NativePlacementContinuity.NativeSupportProduct> products() { return products; }
	java.util.Optional<NativePlacementContinuity.NativeSupportProduct> singleProduct() {
		return products.size() == 1 ? java.util.Optional.of(products.get(0))
			: java.util.Optional.empty();
	}
	List<List<CandidateRealizationInputBinding>> commonAxes() { return products.get(0).axes(); }
	int headerCount() { return products.size(); }
	DurableAnchorKey clauseWitness() { return clauseWitness; }
	boolean clauseLayoutExact() { return clauseLayoutExact; }
	boolean hasGroundedBindings() { return !commonAxes().isEmpty(); }
	java.util.Optional<NativeContinuitySupportClauses> restrictBindings(
		Predicate<CandidateRealizationInputBinding> supported) {
		List<List<CandidateRealizationInputBinding>> axes = commonAxes().stream()
			.map(axis -> axis.stream().filter(supported).toList()).toList();
		if(axes.stream().anyMatch(List::isEmpty))
			return java.util.Optional.empty();
		boolean unchanged = true;
		for(int axis = 0; axis < axes.size(); axis++)
			unchanged &= axes.get(axis).size() == commonAxes().get(axis).size();
		if(unchanged)
			return java.util.Optional.of(this);
		List<NativePlacementContinuity.NativeSupportProduct> restricted = new java.util.ArrayList<>();
		for(var product : products) {
			var next = product.withAxes(axes);
			if(next == null)
				return java.util.Optional.empty();
			restricted.add(next);
		}
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
			restricted, clauseWitness, clauseLayoutExact, restrictAuthorityDonors(restricted)));
	}
	java.util.Optional<NativeContinuitySupportClauses> oneAxisUnion(
		NativeContinuitySupportClauses that) {
		if(that == null || owner != that.owner
			|| clauseLayoutExact != that.clauseLayoutExact
			|| !Objects.equals(clauseWitness, that.clauseWitness))
			return java.util.Optional.empty();
		if(products.size() != 1 || that.products.size() != 1)
			return java.util.Optional.empty();
		var product = products.get(0);
		var thatProduct = that.products.get(0);
		var union = product.oneAxisUnion(thatProduct);
		if(union.isEmpty())
			return java.util.Optional.empty();
		if(product.sameExactAuthority(union.get()))
			return java.util.Optional.of(this);
		int changedAxis = -1;
		for(int axis = 0; axis < union.get().axes().size(); axis++)
			if(union.get().axes().get(axis).size() != product.axes().get(axis).size()
				|| !union.get().axes().get(axis).equals(product.axes().get(axis))) {
				if(changedAxis >= 0)
					return java.util.Optional.empty();
				changedAxis = axis;
			}
		if(changedAxis < 0)
			return java.util.Optional.of(this);
		int addedAxis = changedAxis;
		List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
		for(AuthorityDonor donor : that.authoritySources()) {
			AuthorityDonor restricted = donor.restrict(binding ->
				binding.inputPosition() != union.get().axes().get(addedAxis).get(0).inputPosition()
					|| !product.containsExactBinding(addedAxis, binding));
			if(restricted != null)
				donors.add(restricted);
		}
		if(!withinAuthorityRetentionBudget(List.of(union.get()), donors))
			return java.util.Optional.empty();
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
			union.get(), clauseWitness, clauseLayoutExact, donors));
	}
	java.util.Optional<NativeContinuitySupportClauses> multiHeaderUnion(
		NativeContinuitySupportClauses that) {
		if(that == null || owner != that.owner
			|| clauseLayoutExact != that.clauseLayoutExact
			|| !Objects.equals(clauseWitness, that.clauseWitness)
			|| !products.get(0).sameExactAxes(that.products.get(0))
			|| !products.get(0).sameOutputHeaderAuthority(that.products.get(0)))
			return java.util.Optional.empty();
		for(var product : products)
			for(var candidate : that.products)
				if(!product.sameExactAxes(candidate)
					|| !product.sameOutputHeaderAuthority(candidate))
					return java.util.Optional.empty();
		List<NativePlacementContinuity.NativeSupportProduct> merged =
			new java.util.ArrayList<>(products);
		for(var candidate : that.products) {
			NativePlacementContinuity.NativeSupportProduct matching = null;
			for(var retained : merged)
				if(retained.sameHeaderAuthority(candidate)) {
					matching = retained;
					break;
				}
			if(matching == null)
				merged.add(candidate);
			else if(!matching.sameExactAuthority(candidate))
				return java.util.Optional.empty();
		}
		if(merged.size() == products.size())
			return java.util.Optional.of(this);
		NativeMultiHeaderIndex mergedIndex =
			NativeMultiHeaderIndex.tryCreate(canonicalProducts(merged));
		if(mergedIndex == null)
			return java.util.Optional.empty();
		List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
		donors.addAll(that.authoritySources());
		if(!withinAuthorityRetentionBudget(merged, mergedIndex.retainedMetadataUnits(), donors))
			return java.util.Optional.empty();
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner, merged,
			clauseWitness, clauseLayoutExact, donors));
	}
	private List<AuthorityDonor> authoritySources() {
		return authorityDonors.isEmpty()
			? products.stream().map(product -> new AuthorityDonor(this, product)).toList()
			: authorityDonors;
	}
	private List<AuthorityDonor> restrictAuthorityDonors(
		List<NativePlacementContinuity.NativeSupportProduct> supportedProducts) {
		// Ordinary pruning must not start retaining an entire leaf product.
		if(authorityDonors.isEmpty())
			return List.of();
		List<AuthorityDonor> restricted = new java.util.ArrayList<>();
		for(AuthorityDonor donor : authorityDonors) {
			NativePlacementContinuity.NativeSupportProduct supported = null;
			for(var product : supportedProducts)
				if(product.sameHeaderAuthority(donor.scope())) {
					supported = product;
					break;
				}
			AuthorityDonor retained = supported == null ? null : donor.restrictTo(supported);
			if(retained != null)
				restricted.add(retained);
		}
		return List.copyOf(restricted);
	}
	private static boolean withinAuthorityRetentionBudget(
		List<NativePlacementContinuity.NativeSupportProduct> products,
		List<AuthorityDonor> donors) {
		return withinAuthorityRetentionBudget(products, 0, donors);
	}
	private static boolean withinAuthorityRetentionBudget(
		List<NativePlacementContinuity.NativeSupportProduct> products,
		long relationMetadataUnits, List<AuthorityDonor> donors) {
		if(donors.size() > MAX_AUTHORITY_DONORS)
			return false;
		try {
			long units = relationMetadataUnits;
			for(var product : products)
				units = Math.addExact(units,
					Math.addExact(product.retainedMetadataUnits(), product.size()));
			for(AuthorityDonor donor : donors) {
				// Donors are flattened leaves. Charge all original possible handles:
				// an alias may materialize members outside this donor's restricted scope.
				if(!donor.relation().authorityDonors.isEmpty())
					return false;
				NativePlacementContinuity.NativeSupportProduct donorProduct =
					donor.relation().singleProduct().orElse(null);
				if(donorProduct == null)
					return false;
				long original = Math.addExact(donorProduct.retainedMetadataUnits(),
					Math.multiplyExact((long)donor.relation().size(),
						donorProduct.axes().size() + 1L));
				units = Math.addExact(units, Math.addExact(2L,
					Math.addExact(original, donor.scope().retainedMetadataUnits())));
				if(units > MAX_AUTHORITY_RETENTION_UNITS)
					return false;
			}
			return units <= MAX_AUTHORITY_RETENTION_UNITS;
		}
		catch(ArithmeticException overflow) {
			return false;
		}
	}
	private record AuthorityDonor(NativeContinuitySupportClauses relation,
		NativePlacementContinuity.NativeSupportProduct scope) {
		private AuthorityDonor {
			Objects.requireNonNull(relation);
			Objects.requireNonNull(scope);
		}
		private AuthorityDonor restrict(Predicate<CandidateRealizationInputBinding> supported) {
			List<List<CandidateRealizationInputBinding>> axes = scope.axes().stream()
				.map(axis -> axis.stream().filter(supported).toList()).toList();
			return withAxes(axes);
		}
		private AuthorityDonor withAxes(List<List<CandidateRealizationInputBinding>> axes) {
			if(axes.stream().anyMatch(List::isEmpty))
				return null;
			NativePlacementContinuity.NativeSupportProduct restricted = scope.withAxes(axes);
			return restricted == null ? null : new AuthorityDonor(relation, restricted);
		}
		private AuthorityDonor restrictTo(
			NativePlacementContinuity.NativeSupportProduct supportedProduct) {
			if(scope.axes().size() != supportedProduct.axes().size())
				return null;
			List<List<CandidateRealizationInputBinding>> axes = new java.util.ArrayList<>(
				scope.axes().size());
			for(int axis = 0; axis < scope.axes().size(); axis++) {
				List<CandidateRealizationInputBinding> retained = new java.util.ArrayList<>();
				for(CandidateRealizationInputBinding binding : scope.axes().get(axis))
					if(supportedProduct.containsExactBinding(axis, binding))
						retained.add(binding);
				if(retained.isEmpty())
					return null;
				axes.add(List.copyOf(retained));
			}
			NativePlacementContinuity.NativeSupportProduct restricted = scope.withAxes(axes);
			return restricted == null ? null : new AuthorityDonor(relation, restricted);
		}
	}
	long retainedFactorOptionCount() {
		// This feeds the fixed-point reverse-incidence work counter. Common axes are
		// indexed once regardless of how many proof headers reuse that domain.
		return commonAxes().stream().mapToLong(List::size).sum();
	}
	boolean sameExactAuthority(NativeContinuitySupportClauses that) {
		return this == that || that != null && owner == that.owner
			&& clauseLayoutExact == that.clauseLayoutExact
			&& Objects.equals(clauseWitness, that.clauseWitness)
			&& sameExactProducts(that);
	}
	@Override
	public boolean equals(Object other) {
		if(this == other)
			return true;
		if(other instanceof NativeContinuitySupportClauses that)
			return owner.equals(that.owner)
				&& clauseLayoutExact == that.clauseLayoutExact
				&& Objects.equals(clauseWitness, that.clauseWitness)
				&& sameStructuralProducts(that);
		return super.equals(other);
	}
	String authoritySignature() {
		return owner.normalizedSignature() + "|headers=" + products.stream()
			.map(NativePlacementContinuity.NativeSupportProduct::headerAuthoritySignature).toList()
			+ "|axes=" + commonAxes().stream().map(axis -> axis.stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList()).toList()
			+ "|clausePool=" + (clauseWitness == null ? "-" : clauseWitness.normalizedSignature())
			+ "|clauseLayout=" + (clauseLayoutExact ? "exact" : "dynamic");
	}
	int materializedHandleCount() { return handles.size(); }
	int authorityDonorCount() { return authorityDonors.size(); }
	int firstIdentityOrdinal(Object value) {
		for(var entry : handles.entrySet())
			if(entry.getValue() == value)
				return entry.getKey();
		return -1;
	}

	int ordinalOfExactAuthorityMember(NativePlacementContinuity.NativeSupportProduct header,
		List<CandidateRealizationInputBinding> bindings) {
		return canonicalIndex == null
			? products.get(0).sameHeaderAuthority(header)
				? products.get(0).ordinalOfExactAuthorityBindings(bindings) : -1
			: canonicalIndex.ordinalOfExactAuthorityMember(header, bindings);
	}
	CandidateRealizationSupportClause firstCanonicalMemberForBindings(
		List<CandidateRealizationInputBinding> bindings) {
		int best = Integer.MAX_VALUE;
		for(var product : products) {
			int ordinal = ordinalOfExactAuthorityMember(product, bindings);
			if(ordinal >= 0 && ordinal < best)
				best = ordinal;
		}
		if(best == Integer.MAX_VALUE)
			throw new IllegalArgumentException("Bindings are outside native support relation");
		return get(best);
	}

	private boolean sameExactProducts(NativeContinuitySupportClauses that) {
		if(products.size() != that.products.size())
			return false;
		for(int index = 0; index < products.size(); index++)
			if(!products.get(index).sameExactAuthority(that.products.get(index)))
				return false;
		return true;
	}
	private boolean sameStructuralProducts(NativeContinuitySupportClauses that) {
		if(products.size() != that.products.size())
			return false;
		for(int index = 0; index < products.size(); index++)
			if(!products.get(index).sameStructuralAuthority(that.products.get(index)))
				return false;
		return true;
	}
	private static List<NativePlacementContinuity.NativeSupportProduct> canonicalProducts(
		List<NativePlacementContinuity.NativeSupportProduct> supplied) {
		if(supplied == null || supplied.isEmpty())
			throw new IllegalArgumentException("Native relation requires at least one proof header");
		List<NativePlacementContinuity.NativeSupportProduct> canonical =
			new java.util.ArrayList<>(supplied);
		canonical.sort(java.util.Comparator.comparing(
			NativePlacementContinuity.NativeSupportProduct::headerAuthoritySignature));
		for(int index = 1; index < canonical.size(); index++)
			if(canonical.get(index - 1).sameHeaderAuthority(canonical.get(index)))
				throw new IllegalArgumentException("Duplicate native proof header");
		return List.copyOf(canonical);
	}

	private record NativeMember(NativePlacementContinuity.NativeSupportProduct product,
		List<CandidateRealizationInputBinding> bindings) { }
	private record NativeHeaderBucket(NativePlacementContinuity.NativeSupportProduct product,
		int bindingLength, int count, String lengthPrefix, String headerPrefix) { }
	private static final class NativeMultiHeaderIndex {
		private static final int MAXIMUM_HEADER_BUCKETS = 65_536;
		private final List<NativeHeaderBucket> buckets;
		private final int size;
		private NativeMultiHeaderIndex(List<NativeHeaderBucket> buckets, int size) {
			this.buckets = buckets;
			this.size = size;
		}
		private static NativeMultiHeaderIndex tryCreate(
			List<NativePlacementContinuity.NativeSupportProduct> products) {
			try {
				List<NativeHeaderBucket> buckets = new java.util.ArrayList<>();
				int size = 0;
				for(var product : products) {
					size = Math.addExact(size, product.size());
					String header = product.headerAuthoritySignature() + "|bindings=[";
					for(var entry : product.bindingLengthCounts().entrySet()) {
						if(buckets.size() >= MAXIMUM_HEADER_BUCKETS)
							return null;
						int totalLength = Math.addExact(product.fixedAuthorityLength(), entry.getKey());
						buckets.add(new NativeHeaderBucket(product, entry.getKey(), entry.getValue(),
							Integer.toString(totalLength) + ':', header));
					}
				}
				buckets.sort(java.util.Comparator.comparing(NativeHeaderBucket::lengthPrefix)
					.thenComparing(NativeHeaderBucket::headerPrefix));
				return new NativeMultiHeaderIndex(List.copyOf(buckets), size);
			}
			catch(ArithmeticException overflow) {
				return null;
			}
		}
		private int size() { return size; }
		private long retainedMetadataUnits() {
			long units = 2L + 5L * buckets.size();
			for(NativeHeaderBucket bucket : buckets)
				// Charge stored text too; repeated references are conservatively charged
				// per bucket, avoiding another retained identity index just for accounting.
				units = Math.addExact(units, Math.addExact(
					(long)bucket.lengthPrefix().length(), bucket.headerPrefix().length()));
			return units;
		}
		private NativeMember memberAt(int ordinal) {
			Objects.checkIndex(ordinal, size);
			int remaining = ordinal;
			for(NativeHeaderBucket bucket : buckets) {
				if(remaining < bucket.count())
					return new NativeMember(bucket.product(),
						bucket.product().bindingsAtLengthRank(bucket.bindingLength(), remaining));
				remaining -= bucket.count();
			}
			throw new IllegalStateException("Native multi-header ordinal exceeds relation");
		}
		private int ordinalOfExactAuthorityMember(
			NativePlacementContinuity.NativeSupportProduct header,
			List<CandidateRealizationInputBinding> bindings) {
			int rank = 0;
			for(NativeHeaderBucket bucket : buckets) {
				if(bucket.product().sameHeaderAuthority(header)) {
					int length = bucket.product().bindingLength(bindings);
					if(length == bucket.bindingLength()) {
						int within = bucket.product().rankWithinBindingLength(bindings);
						return within < 0 ? -1 : Math.addExact(rank, within);
					}
				}
				rank = Math.addExact(rank, bucket.count());
			}
			return -1;
		}
	}

}

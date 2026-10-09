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
	private final NativePlacementContinuity.NativeSupportProduct product;
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
		this.owner = Objects.requireNonNull(owner, "native continuity proof owner");
		this.product = Objects.requireNonNull(product, "native support product");
		if(clauseWitness == null && !clauseLayoutExact)
			throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
		this.clauseWitness = clauseWitness;
		this.clauseLayoutExact = clauseLayoutExact;
		this.authorityDonors = List.copyOf(authorityDonors);
	}

	@Override public int size() { return product.size(); }

	@Override
	public CandidateRealizationSupportClause get(int ordinal) {
		Objects.checkIndex(ordinal, size());
		CandidateRealizationSupportClause current = handles.get(ordinal);
		return current != null ? current : handles.computeIfAbsent(ordinal, this::materialize);
	}

	private CandidateRealizationSupportClause materialize(int ordinal) {
		var bindings = product.bindingsAt(ordinal);
		for(AuthorityDonor donor : authorityDonors) {
			if(donor.scope().ordinalOfExactAuthorityBindings(bindings) < 0)
				continue;
			int donorOrdinal = donor.relation().product.ordinalOfExactAuthorityBindings(bindings);
			if(donorOrdinal >= 0)
				return donor.relation().get(donorOrdinal);
		}
		if(!authorityDonors.isEmpty())
			throw new IllegalStateException("Native product union lost exact member authority");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			product.externalSeed(), product.outputWorkerPoolWitness(),
			product.exactPartitionRanges(), bindings);
		return new CandidateRealizationSupportClause(List.of(proof.continuityProofKey(owner)),
			bindings, clauseWitness, clauseLayoutExact);
	}

	NativePlacementContinuity.NativeSupportProduct product() { return product; }
	DurableAnchorKey clauseWitness() { return clauseWitness; }
	boolean clauseLayoutExact() { return clauseLayoutExact; }
	boolean hasGroundedBindings() { return !product.axes().isEmpty(); }
	java.util.Optional<NativeContinuitySupportClauses> restrictBindings(
		Predicate<CandidateRealizationInputBinding> supported) {
		List<List<CandidateRealizationInputBinding>> axes = product.axes().stream()
			.map(axis -> axis.stream().filter(supported).toList()).toList();
		if(axes.stream().anyMatch(List::isEmpty))
			return java.util.Optional.empty();
		boolean unchanged = true;
		for(int axis = 0; axis < axes.size(); axis++)
			unchanged &= axes.get(axis).size() == product.axes().get(axis).size();
		if(unchanged)
			return java.util.Optional.of(this);
		NativePlacementContinuity.NativeSupportProduct restricted = product.withAxes(axes);
		return restricted == null ? java.util.Optional.empty()
			: java.util.Optional.of(new NativeContinuitySupportClauses(
				owner, restricted, clauseWitness, clauseLayoutExact,
				restrictAuthorityDonors(restricted)));
	}
	java.util.Optional<NativeContinuitySupportClauses> oneAxisUnion(
		NativeContinuitySupportClauses that) {
		if(that == null || owner != that.owner
			|| clauseLayoutExact != that.clauseLayoutExact
			|| !Objects.equals(clauseWitness, that.clauseWitness))
			return java.util.Optional.empty();
		var union = product.oneAxisUnion(that.product);
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
		if(!withinAuthorityRetentionBudget(union.get(), donors))
			return java.util.Optional.empty();
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
			union.get(), clauseWitness, clauseLayoutExact, donors));
	}
	private List<AuthorityDonor> authoritySources() {
		return authorityDonors.isEmpty()
			? List.of(new AuthorityDonor(this, product)) : authorityDonors;
	}
	private List<AuthorityDonor> restrictAuthorityDonors(
		NativePlacementContinuity.NativeSupportProduct retainedProduct) {
		// Ordinary pruning must not start retaining the entire superseded product.
		if(authorityDonors.isEmpty())
			return List.of();
		List<AuthorityDonor> restricted = new java.util.ArrayList<>();
		for(AuthorityDonor donor : authorityDonors) {
			List<List<CandidateRealizationInputBinding>> axes = new java.util.ArrayList<>();
			for(int axis = 0; axis < donor.scope().axes().size(); axis++) {
				int position = axis;
				// The original predicate can be identity-sensitive to union bindings.
				// Test donor-local objects against the already filtered exact authority.
				axes.add(donor.scope().axes().get(axis).stream()
					.filter(binding -> retainedProduct.containsExactBinding(position, binding)).toList());
			}
			AuthorityDonor retained = donor.withAxes(axes);
			if(retained != null)
				restricted.add(retained);
		}
		return List.copyOf(restricted);
	}
	private static boolean withinAuthorityRetentionBudget(
		NativePlacementContinuity.NativeSupportProduct product, List<AuthorityDonor> donors) {
		if(donors.size() > MAX_AUTHORITY_DONORS)
			return false;
		try {
			long units = Math.addExact(product.retainedMetadataUnits(), product.size());
			for(AuthorityDonor donor : donors) {
				// Donors are flattened leaves. Charge all original possible handles:
				// an alias may materialize members outside this donor's restricted scope.
				if(!donor.relation().authorityDonors.isEmpty())
					return false;
				long original = Math.addExact(donor.relation().product.retainedMetadataUnits(),
					Math.multiplyExact((long)donor.relation().size(),
						donor.relation().product.axes().size() + 1L));
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
	}
	long retainedFactorOptionCount() {
		return product.axes().stream().mapToLong(List::size).sum();
	}
	boolean sameExactAuthority(NativeContinuitySupportClauses that) {
		return this == that || that != null && owner == that.owner
			&& clauseLayoutExact == that.clauseLayoutExact
			&& Objects.equals(clauseWitness, that.clauseWitness)
			&& product.sameExactAuthority(that.product);
	}
	@Override
	public boolean equals(Object other) {
		if(this == other)
			return true;
		if(other instanceof NativeContinuitySupportClauses that)
			return owner.equals(that.owner)
				&& clauseLayoutExact == that.clauseLayoutExact
				&& Objects.equals(clauseWitness, that.clauseWitness)
				&& product.sameStructuralAuthority(that.product);
		return super.equals(other);
	}
	String authoritySignature() {
		return owner.normalizedSignature() + "|seed=" + product.externalSeed().normalizedSignature()
			+ "|outputPool=" + product.outputWorkerPoolWitness().normalizedSignature()
			+ "|partitionRanges=" + (product.exactPartitionRanges() ? "exact" : "dynamic")
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

}

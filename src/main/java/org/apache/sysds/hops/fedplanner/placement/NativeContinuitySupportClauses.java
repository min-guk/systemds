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
	private final CompiledHopKey owner;
	private final NativePlacementContinuity.NativeSupportProduct product;
	private final DurableAnchorKey clauseWitness;
	private final boolean clauseLayoutExact;
	private final ConcurrentHashMap<Integer,CandidateRealizationSupportClause> handles =
		new ConcurrentHashMap<>();

	NativeContinuitySupportClauses(CompiledHopKey owner,
		NativePlacementContinuity.NativeSupportProduct product,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
		this.owner = Objects.requireNonNull(owner, "native continuity proof owner");
		this.product = Objects.requireNonNull(product, "native support product");
		if(clauseWitness == null && !clauseLayoutExact)
			throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
		this.clauseWitness = clauseWitness;
		this.clauseLayoutExact = clauseLayoutExact;
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
				owner, restricted, clauseWitness, clauseLayoutExact));
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
	int firstIdentityOrdinal(Object value) {
		for(var entry : handles.entrySet())
			if(entry.getValue() == value)
				return entry.getKey();
		return -1;
	}

}

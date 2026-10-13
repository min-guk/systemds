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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;

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
	private int listHash;
	private volatile boolean listHashComputed;

	NativeContinuitySupportClauses(CompiledHopKey owner,
		NativePlacementContinuity.NativeSupportProduct product,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
		this(owner, product, clauseWitness, clauseLayoutExact, List.of());
	}
	static NativeContinuitySupportClauses exactComplement(CompiledHopKey owner,
		NativePlacementContinuity.NativeSupportProduct base,
		List<List<CandidateRealizationInputBinding>> exactAxes,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
		var conditional = NativePlacementContinuity.NativeSupportProduct
			.tryCreateExactComplement(base, exactAxes);
		return conditional == null ? null : new NativeContinuitySupportClauses(
			owner, conditional, clauseWitness, clauseLayoutExact);
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

	@Override
	public int hashCode() {
		if(listHashComputed)
			return listHash;
		NativePlacementContinuity.NativeHashSequence summary =
			new NativePlacementContinuity.NativeHashSequence(0, 1, 0);
		if(canonicalIndex == null) {
			for(var block : products.get(0).clauseHashSummaries(
				owner, clauseWitness, clauseLayoutExact).values())
				summary = summary.append(block);
		}
		else {
			java.util.Map<NativePlacementContinuity.NativeSupportProduct,
				java.util.Map<Integer,NativePlacementContinuity.NativeHashSequence>> summaries =
				new java.util.IdentityHashMap<>();
			for(var product : products)
				summaries.put(product, product.clauseHashSummaries(
					owner, clauseWitness, clauseLayoutExact));
			for(NativeHeaderBucket bucket : canonicalIndex.buckets)
				summary = summary.append(summaries.get(bucket.product()).get(bucket.bindingLength()));
		}
		int computed = summary.power() + summary.weightedHash();
		listHash = computed;
		listHashComputed = true;
		return computed;
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

	/**
	 * Retains bounded earlier explicit members as first-authority donors without
	 * reading this native relation. Small prefixes use singleton leaves; large
	 * prefixes must prove one exact dense sub-product before sharing a single leaf.
	 */
	ExplicitAuthorityOverlay withExplicitAuthority(
		java.util.Collection<CandidateRealizationSupportClause> explicitClauses) {
		if(explicitClauses == null || explicitClauses.isEmpty())
			return new ExplicitAuthorityOverlay(this, false);
		boolean denseRequest = !explicitAuthorityPrefixWithinCountLimit(explicitClauses.size());
		if(products.size() != 1 || conditionalComplement())
			return denseRequest
				? new ExplicitAuthorityOverlay(null, false,
					DenseAuthorityOutcome.REJECT_AUTHORITY)
				: new ExplicitAuthorityOverlay(null, false);
		if(denseRequest)
			return withDenseExplicitAuthority(explicitClauses);
		List<AuthorityDonor> prefix = new java.util.ArrayList<>(explicitClauses.size());
		java.util.Set<Integer> ordinals = new java.util.HashSet<>();
		for(CandidateRealizationSupportClause clause : explicitClauses) {
			int ordinal = ordinalOfExactAuthorityClause(clause);
			if(ordinal < 0)
				return new ExplicitAuthorityOverlay(null, false);
			if(!ordinals.add(ordinal))
				continue;
			NativeMember member = canonicalIndex == null
				? new NativeMember(products.get(0), products.get(0).bindingsAt(ordinal))
				: canonicalIndex.memberAt(ordinal);
			if(firstAuthorityClause(member) == clause)
				continue;
			NativePlacementContinuity.NativeSupportProduct singleton = member.product().withAxes(
				member.bindings().stream().map(List::of).toList());
			if(singleton == null || singleton.size() != 1)
				return new ExplicitAuthorityOverlay(null, false);
			NativeContinuitySupportClauses leaf = new NativeContinuitySupportClauses(
				owner, List.of(singleton), clauseWitness, clauseLayoutExact, List.of());
			if(leaf.ordinalOfExactAuthorityClause(clause) != 0)
				return new ExplicitAuthorityOverlay(null, false);
			leaf.handles.put(0, clause);
			prefix.add(new AuthorityDonor(leaf, singleton));
		}
		if(prefix.isEmpty())
			return new ExplicitAuthorityOverlay(this, false);
		List<AuthorityDonor> donors = new java.util.ArrayList<>(
			prefix.size() + authoritySources().size());
		donors.addAll(prefix);
		donors.addAll(authoritySources());
		long indexUnits = canonicalIndex == null ? 0 : canonicalIndex.retainedMetadataUnits();
		if(!withinAuthorityRetentionBudget(products, indexUnits, donors))
			return new ExplicitAuthorityOverlay(null, true);
		return new ExplicitAuthorityOverlay(new NativeContinuitySupportClauses(owner, products,
			clauseWitness, clauseLayoutExact, donors), false);
	}

	record ExplicitAuthorityOverlay(NativeContinuitySupportClauses relation,
		boolean capRejected, DenseAuthorityOutcome denseOutcome) {
		ExplicitAuthorityOverlay(NativeContinuitySupportClauses relation, boolean capRejected) {
			this(relation, capRejected, DenseAuthorityOutcome.NOT_ATTEMPTED);
		}
	}
	enum DenseAuthorityOutcome {
		NOT_ATTEMPTED,
		REUSED,
		ADMITTED,
		REJECT_AUTHORITY,
		REJECT_NONRECTANGULAR,
		REJECT_CAP,
		REJECT_INDEX
	}
	static boolean explicitAuthorityPrefixWithinCountLimit(int count) {
		return count > 0 && count < MAX_AUTHORITY_DONORS;
	}

	private ExplicitAuthorityOverlay withDenseExplicitAuthority(
		java.util.Collection<CandidateRealizationSupportClause> explicitClauses) {
		NativePlacementContinuity.NativeSupportProduct candidate = products.get(0);
		int axesCount = candidate.axes().size();
		int rowCount = explicitClauses.size();
		if(reusesDenseExplicitAuthority(candidate, explicitClauses))
			return new ExplicitAuthorityOverlay(this, false,
				DenseAuthorityOutcome.REUSED);
		long baseUnits = authorityRetentionUnits(products, 0, authoritySources());
		long minimumUnits = denseRetentionPreflightUnits(
			baseUnits, rowCount, axesCount, 0, 0, false);
		if(minimumUnits > MAX_AUTHORITY_RETENTION_UNITS)
			return new ExplicitAuthorityOverlay(null, true,
				DenseAuthorityOutcome.REJECT_CAP);

		long optionCount = 0;
		int previousInputPosition = -1;
		for(int axis = 0; axis < axesCount; axis++) {
			List<CandidateRealizationInputBinding> options = candidate.axes().get(axis);
			if(options.isEmpty())
				return new ExplicitAuthorityOverlay(null, false,
					DenseAuthorityOutcome.REJECT_AUTHORITY);
			int position = options.get(0).inputPosition();
			if(position <= previousInputPosition)
				return new ExplicitAuthorityOverlay(null, false,
					DenseAuthorityOutcome.REJECT_AUTHORITY);
			previousInputPosition = position;
			for(CandidateRealizationInputBinding option : options) {
				if(option.inputPosition() != position)
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_AUTHORITY);
			}
			try {
				optionCount = Math.addExact(optionCount, (long)options.size());
			}
			catch(ArithmeticException overflow) {
				return new ExplicitAuthorityOverlay(null, true,
					DenseAuthorityOutcome.REJECT_CAP);
			}
		}
		long stagedUnits = denseRetentionPreflightUnits(baseUnits, rowCount, axesCount,
			optionCount, candidate.retainedMetadataUnits(), true);
		if(stagedUnits > MAX_AUTHORITY_RETENTION_UNITS)
			return new ExplicitAuthorityOverlay(null, true,
				DenseAuthorityOutcome.REJECT_CAP);
		List<java.util.Map<ExactBindingAuthorityKey,
			CandidateRealizationInputBinding>> optionsByAxis = new java.util.ArrayList<>(axesCount);
		java.util.Map<Integer,Integer> axisByPosition = new java.util.HashMap<>();
		for(int axis = 0; axis < axesCount; axis++) {
			List<CandidateRealizationInputBinding> options = candidate.axes().get(axis);
			int position = options.get(0).inputPosition();
			axisByPosition.put(position, axis);
			java.util.Map<ExactBindingAuthorityKey,
				CandidateRealizationInputBinding> byEquality = new java.util.HashMap<>();
			for(CandidateRealizationInputBinding option : options)
				if(byEquality.put(ExactBindingAuthorityKey.of(option), option) != null)
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_AUTHORITY);
			optionsByAxis.add(byEquality);
		}

		java.util.Map<CandidateRealizationSupportClause,CandidateRealizationSupportClause> firstRows =
			new java.util.LinkedHashMap<>();
		List<java.util.Set<CandidateRealizationInputBinding>> selected =
			new java.util.ArrayList<>(axesCount);
		for(int axis = 0; axis < axesCount; axis++)
			selected.add(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
		for(CandidateRealizationSupportClause clause : explicitClauses) {
			if(!matchesExactClauseHeader(clause, candidate)
				|| clause.inputBindings().size() != axesCount)
				return new ExplicitAuthorityOverlay(null, false,
					DenseAuthorityOutcome.REJECT_AUTHORITY);
			CandidateRealizationInputBinding[] tuple =
				new CandidateRealizationInputBinding[axesCount];
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				Integer axis = axisByPosition.get(binding.inputPosition());
				if(axis == null || tuple[axis] != null)
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_AUTHORITY);
				CandidateRealizationInputBinding canonical = optionsByAxis.get(axis).get(
					ExactBindingAuthorityKey.of(binding));
				if(canonical == null)
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_AUTHORITY);
				tuple[axis] = canonical;
			}
			for(CandidateRealizationInputBinding binding : tuple)
				if(binding == null)
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_AUTHORITY);
			if(firstRows.putIfAbsent(clause, clause) == null)
				for(int axis = 0; axis < axesCount; axis++)
					selected.get(axis).add(tuple[axis]);
		}
		List<List<CandidateRealizationInputBinding>> denseAxes =
			new java.util.ArrayList<>(axesCount);
		long cardinality = 1;
		try {
			for(int axis = 0; axis < axesCount; axis++) {
				java.util.Set<CandidateRealizationInputBinding> selectedAxis = selected.get(axis);
				List<CandidateRealizationInputBinding> retained = candidate.axes().get(axis).stream()
					.filter(selectedAxis::contains).toList();
				if(retained.isEmpty())
					return new ExplicitAuthorityOverlay(null, false,
						DenseAuthorityOutcome.REJECT_NONRECTANGULAR);
				cardinality = Math.multiplyExact(cardinality, retained.size());
				denseAxes.add(retained);
			}
		}
		catch(ArithmeticException overflow) {
			return new ExplicitAuthorityOverlay(null, true,
				DenseAuthorityOutcome.REJECT_CAP);
		}
		if(cardinality != firstRows.size())
			return new ExplicitAuthorityOverlay(null, false,
				DenseAuthorityOutcome.REJECT_NONRECTANGULAR);
		NativePlacementContinuity.NativeSupportProduct denseProduct = candidate.withAxes(denseAxes);
		if(denseProduct == null || denseProduct.size() != firstRows.size())
			return new ExplicitAuthorityOverlay(null, false,
				DenseAuthorityOutcome.REJECT_INDEX);
		List<CandidateRealizationSupportClause> canonicalRows;
		try {
			canonicalRows = PlacementAnalysis.canonicalExplicitSupportClauses(firstRows.values());
		}
		catch(IllegalArgumentException duplicate) {
			return new ExplicitAuthorityOverlay(null, false,
				DenseAuthorityOutcome.REJECT_NONRECTANGULAR);
		}
		if(reusesDenseExplicitAuthority(denseProduct, canonicalRows))
			return new ExplicitAuthorityOverlay(this, false,
				DenseAuthorityOutcome.REUSED);
		if(authoritySources().size() >= MAX_AUTHORITY_DONORS)
			return new ExplicitAuthorityOverlay(null, true,
				DenseAuthorityOutcome.REJECT_CAP);
		NativeContinuitySupportClauses leaf = new NativeContinuitySupportClauses(
			owner, denseProduct, clauseWitness, clauseLayoutExact, List.of());
		for(int ordinal = 0; ordinal < canonicalRows.size(); ordinal++)
			leaf.handles.put(ordinal, canonicalRows.get(ordinal));
		List<AuthorityDonor> donors = new java.util.ArrayList<>(1 + authoritySources().size());
		donors.add(new AuthorityDonor(leaf, denseProduct));
		donors.addAll(authoritySources());
		if(!withinAuthorityRetentionBudget(products, 0, donors))
			return new ExplicitAuthorityOverlay(null, true,
				DenseAuthorityOutcome.REJECT_CAP);
		return new ExplicitAuthorityOverlay(new NativeContinuitySupportClauses(owner, products,
			clauseWitness, clauseLayoutExact, donors), false,
			DenseAuthorityOutcome.ADMITTED);
	}

	private boolean reusesDenseExplicitAuthority(
		NativePlacementContinuity.NativeSupportProduct candidate,
		java.util.Collection<CandidateRealizationSupportClause> explicitClauses) {
		if(authorityDonors.isEmpty())
			return false;
		AuthorityDonor first = authorityDonors.get(0);
		NativePlacementContinuity.NativeSupportProduct leafProduct =
			first.relation().singleProduct().orElse(null);
		if(leafProduct == null || !first.scope().sameExactAuthority(leafProduct)
			|| explicitClauses.size() != leafProduct.size()
			|| first.relation().handles.size() != leafProduct.size()
			|| !candidate.sameHeaderAuthority(leafProduct))
			return false;
		int ordinal = 0;
		for(CandidateRealizationSupportClause clause : explicitClauses)
			if(first.relation().handles.get(ordinal++) != clause)
				return false;
		return true;
	}

	private boolean reusesDenseExplicitAuthority(
		NativePlacementContinuity.NativeSupportProduct denseProduct,
		List<CandidateRealizationSupportClause> canonicalRows) {
		if(authorityDonors.isEmpty())
			return false;
		AuthorityDonor first = authorityDonors.get(0);
		NativePlacementContinuity.NativeSupportProduct leafProduct =
			first.relation().singleProduct().orElse(null);
		if(leafProduct == null || !first.scope().sameExactAuthority(denseProduct)
			|| !leafProduct.sameExactAuthority(denseProduct)
			|| first.relation().handles.size() != canonicalRows.size())
			return false;
		for(int ordinal = 0; ordinal < canonicalRows.size(); ordinal++)
			if(first.relation().handles.get(ordinal) != canonicalRows.get(ordinal))
				return false;
		return true;
	}

	private boolean matchesExactClauseHeader(CandidateRealizationSupportClause clause,
		NativePlacementContinuity.NativeSupportProduct product) {
		if(clause.proofDependencies().size() != 1
			|| clause.nativeWorkerPoolLayoutExact() != clauseLayoutExact
			|| !Objects.equals(clause.nativeWorkerPoolWitness(), clauseWitness))
			return false;
		var key = clause.proofDependencies().get(0);
		if(key.kind() != PlacementProofKind.NATIVE_CONTINUITY || key.owner() != owner)
			return false;
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			product.externalSeed(), product.outputWorkerPoolWitness(),
			product.exactPartitionRanges(), clause.inputBindings());
		return key.hasNativeContinuityDescriptor()
			? key.matchesNativeContinuityDescriptor(proof)
			: key.authoritySignature().equals(proof.normalizedSignature());
	}

	private static long denseRetentionPreflightUnits(long baseUnits, int rows, int axes,
		long options, long candidateMetadata, boolean includeStaging) {
		try {
			long units = Math.addExact(baseUnits, 2L);
			units = Math.addExact(units, Math.multiplyExact((long)rows, axes + 1L));
			if(includeStaging) {
				long staging = Math.addExact(Math.multiplyExact(4L, options),
					Math.addExact(Math.multiplyExact(8L, rows), Math.multiplyExact(4L, axes)));
				units = Math.addExact(units, staging);
				units = Math.addExact(units, Math.multiplyExact(2L, candidateMetadata));
			}
			return units;
		}
		catch(ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}

	private CandidateRealizationSupportClause firstAuthorityClause(NativeMember member) {
		if(authorityDonors.isEmpty()) {
			int ordinal = ordinalOfExactAuthorityMember(member.product(), member.bindings());
			return ordinal < 0 ? null : handles.get(ordinal);
		}
		for(AuthorityDonor donor : authorityDonors) {
			if(!donor.scope().sameHeaderAuthority(member.product())
				|| donor.scope().ordinalOfExactAuthorityBindings(member.bindings()) < 0)
				continue;
			int donorOrdinal = donor.relation().ordinalOfExactAuthorityMember(
				member.product(), member.bindings());
			return donorOrdinal < 0 ? null : donor.relation().handles.get(donorOrdinal);
		}
		return null;
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
	boolean conditionalComplement() { return products.get(0).conditionalComplement(); }
	List<List<CandidateRealizationInputBinding>> excludedExactAxes() {
		return products.get(0).excludedExactAxes();
	}
	boolean isExcludedExactBinding(int axis, CandidateRealizationInputBinding binding) {
		return products.get(0).isExcludedExactBinding(axis, binding);
	}
	int headerCount() { return products.size(); }
	DurableAnchorKey clauseWitness() { return clauseWitness; }
	boolean clauseLayoutExact() { return clauseLayoutExact; }
	boolean hasGroundedBindings() { return !commonAxes().isEmpty(); }
	ExactAuthorityResidual exactAuthorityResidualAfter(NativeContinuitySupportClauses retained) {
		if(retained == null || owner != retained.owner
			|| clauseLayoutExact != retained.clauseLayoutExact
			|| !Objects.equals(clauseWitness, retained.clauseWitness))
			return null;
		var candidateProduct = singleProduct().orElse(null);
		var retainedProduct = retained.singleProduct().orElse(null);
		if(candidateProduct == null || retainedProduct == null)
			return null;
		List<List<CandidateRealizationInputBinding>> intersection =
			candidateProduct.exactAuthorityIntersectionAxes(retainedProduct);
		if(intersection == null)
			return null;
		long covered = 1;
		try {
			for(List<CandidateRealizationInputBinding> axis : intersection)
				covered = Math.multiplyExact(covered, axis.size());
		}
		catch(ArithmeticException overflow) {
			return null;
		}
		if(covered == candidateProduct.size())
			return new ExactAuthorityResidual(null, null, covered);
		var residual = NativePlacementContinuity.NativeSupportProduct
			.tryCreateExactComplement(candidateProduct, intersection);
		if(residual == null)
			return null;
		NativeContinuitySupportClauses residualRelation = new NativeContinuitySupportClauses(
			owner, residual, clauseWitness, clauseLayoutExact,
			List.of(new AuthorityDonor(this, residual)));
		return new ExactAuthorityResidual(residual, residualRelation, covered);
	}
	record ExactAuthorityResidual(
		NativePlacementContinuity.NativeSupportProduct product,
		NativeContinuitySupportClauses relation, long coveredMembers) { }
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
		if(that.firstAuthorityIs(this))
			return java.util.Optional.of(that);
		if(!conditionalComplement() && that.conditionalComplement()) {
			var completed = ordinaryComplementUnion(that);
			if(completed.isPresent())
				return completed;
		}
		if(conditionalComplement() && that.conditionalComplement()
			&& products.size() == 1 && that.products.size() == 1
			&& that.authorityDonors.isEmpty()) {
			var rightOnly = that.products.get(0).conditionalGrowthResidualAfter(products.get(0));
			if(rightOnly != null) {
				List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
				donors.add(new AuthorityDonor(that, rightOnly));
				if(!withinAuthorityRetentionBudget(that.products, donors))
					return java.util.Optional.empty();
				return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
					that.products, clauseWitness, clauseLayoutExact, donors));
			}
		}
		if(conditionalComplement() || that.conditionalComplement()) {
			if(!conditionalComplement() || !that.conditionalComplement()
				|| products.size() != 1 || that.products.size() != 1
				|| !that.authorityDonors.isEmpty())
				return sameExactAuthority(that) ? java.util.Optional.of(this)
					: java.util.Optional.empty();
			var conditional = products.get(0).conditionalUnion(that.products.get(0));
			if(conditional.isEmpty())
				return java.util.Optional.empty();
			if(conditional.get().unchanged())
				return java.util.Optional.of(this);
			List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
			donors.add(new AuthorityDonor(that, conditional.get().rightOnly()));
			if(!withinAuthorityRetentionBudget(List.of(conditional.get().product()), donors))
				return java.util.Optional.empty();
			return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
				conditional.get().product(), clauseWitness, clauseLayoutExact, donors));
		}
		if(products.size() != 1 || that.products.size() != 1)
			return multiHeaderAxisUnion(that);
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
	private java.util.Optional<NativeContinuitySupportClauses> ordinaryComplementUnion(
		NativeContinuitySupportClauses that) {
		if(products.size() != 1 || that.products.size() != 1)
			return java.util.Optional.empty();
		var retained = products.get(0);
		var complement = that.products.get(0);
		if(!retained.sameHeaderAuthority(complement)
			|| !complement.excludesExactAuthority(retained))
			return java.util.Optional.empty();
		var complete = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			complement.externalSeed(), complement.outputWorkerPoolWitness(),
			complement.exactPartitionRanges(), complement.axes());
		if(complete == null)
			return java.util.Optional.empty();
		List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
		donors.addAll(that.authoritySources());
		if(!withinAuthorityRetentionBudget(List.of(complete), donors))
			return java.util.Optional.empty();
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner,
			complete, clauseWitness, clauseLayoutExact, donors));
	}
	private boolean firstAuthorityIs(NativeContinuitySupportClauses retained) {
		if(authorityDonors.isEmpty() || products.size() != 1 || retained.products.size() != 1)
			return false;
		List<AuthorityDonor> retainedSources = retained.authoritySources();
		if(authorityDonors.size() < retainedSources.size())
			return false;
		for(int index = 0; index < retainedSources.size(); index++) {
			AuthorityDonor expected = retainedSources.get(index);
			AuthorityDonor actual = authorityDonors.get(index);
			if(actual.relation() != expected.relation()
				|| !actual.scope().sameExactAuthority(expected.scope()))
				return false;
		}
		var retainedProduct = retained.products.get(0);
		if(products.get(0).containsConditionalBaseAuthority(retainedProduct))
			return true;
		List<List<CandidateRealizationInputBinding>> intersection =
			products.get(0).exactAuthorityIntersectionAxes(retainedProduct);
		if(intersection == null)
			return false;
		long members = 1;
		try {
			for(List<CandidateRealizationInputBinding> axis : intersection)
				members = Math.multiplyExact(members, axis.size());
		}
		catch(ArithmeticException overflow) {
			return false;
		}
		return members == retainedProduct.size();
	}
	private java.util.Optional<NativeContinuitySupportClauses> multiHeaderAxisUnion(
		NativeContinuitySupportClauses that) {
		// Header growth and axis growth together need a correlated union. Only
		// the identical complete header set can share this rectangular axis union.
		if(products.size() != that.products.size())
			return java.util.Optional.empty();
		for(int header = 0; header < products.size(); header++)
			if(!products.get(header).sameHeaderAuthority(that.products.get(header)))
				return java.util.Optional.empty();
		var original = products.get(0);
		var first = original.oneAxisUnion(that.products.get(0));
		if(first.isEmpty())
			return java.util.Optional.empty();
		if(original.sameExactAuthority(first.get()))
			return java.util.Optional.of(this);
		List<NativePlacementContinuity.NativeSupportProduct> merged = new java.util.ArrayList<>();
		merged.add(first.get());
		for(int header = 1; header < products.size(); header++) {
			var next = products.get(header).oneAxisUnion(that.products.get(header));
			if(next.isEmpty() || !first.get().sameExactAxes(next.get()))
				return java.util.Optional.empty();
			merged.add(next.get());
		}
		int changedAxis = -1;
		for(int axis = 0; axis < first.get().axes().size(); axis++)
			if(first.get().axes().get(axis).size() != original.axes().get(axis).size()) {
				if(changedAxis >= 0)
					return java.util.Optional.empty();
				changedAxis = axis;
			}
		if(changedAxis < 0)
			return java.util.Optional.empty();
		int addedAxis = changedAxis;
		List<AuthorityDonor> donors = new java.util.ArrayList<>(authoritySources());
		for(AuthorityDonor donor : that.authoritySources()) {
			AuthorityDonor added = donor.restrict(binding ->
				binding.inputPosition() != first.get().axes().get(addedAxis).get(0).inputPosition()
					|| !original.containsExactBinding(addedAxis, binding));
			if(added != null)
				donors.add(added);
		}
		NativeMultiHeaderIndex index = NativeMultiHeaderIndex.tryCreate(merged);
		if(index == null || !withinAuthorityRetentionBudget(merged, index.retainedMetadataUnits(), donors))
			return java.util.Optional.empty();
		return java.util.Optional.of(new NativeContinuitySupportClauses(owner, merged,
			clauseWitness, clauseLayoutExact, donors));
	}
	java.util.Optional<NativeContinuitySupportClauses> multiHeaderUnion(
		NativeContinuitySupportClauses that) {
		if(that != null && (conditionalComplement() || that.conditionalComplement())
			&& !sameConditionalDomain(products.get(0), that.products.get(0)))
			return java.util.Optional.empty();
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
	/** Complete-key union: finish each proof header before comparing shared domains. */
	static java.util.Optional<NativeContinuitySupportClauses> unionSameAxesHeaderGroup(
		List<NativeContinuitySupportClauses> relations) {
		if(relations.isEmpty())
			return java.util.Optional.empty();
		NativeContinuitySupportClauses first = relations.get(0);
		if(relations.stream().anyMatch(NativeContinuitySupportClauses::conditionalComplement)) {
			if(relations.stream().anyMatch(relation -> !relation.conditionalComplement()))
				return java.util.Optional.empty();
			NativeContinuitySupportClauses union = first;
			for(int index = 1; index < relations.size(); index++) {
				NativeContinuitySupportClauses next = relations.get(index);
				var merged = union.oneAxisUnion(next);
				union = merged.isPresent() ? merged.get() : union.multiHeaderUnion(next).orElse(null);
				if(union == null)
					return java.util.Optional.empty();
			}
			return java.util.Optional.of(union);
		}
		java.util.Map<HeaderAuthority,NativeContinuitySupportClauses> byHeader =
			new java.util.LinkedHashMap<>();
		for(NativeContinuitySupportClauses relation : relations) {
			if(relation.owner != first.owner || relation.clauseLayoutExact != first.clauseLayoutExact
				|| !Objects.equals(relation.clauseWitness, first.clauseWitness))
				return java.util.Optional.empty();
			for(var product : relation.products) {
				HeaderAuthority header = new HeaderAuthority(product.externalSeed(),
					product.outputWorkerPoolWitness(), product.exactPartitionRanges());
				NativeContinuitySupportClauses retained = byHeader.get(header);
				if(retained == null && byHeader.size() >= MAX_AUTHORITY_DONORS)
					return java.util.Optional.empty();
				NativeContinuitySupportClauses member = relation.singleHeaderView(product);
				if(member == null)
					return java.util.Optional.empty();
				if(retained != null) {
					member = retained.oneAxisUnion(member).orElse(null);
					if(member == null)
						return java.util.Optional.empty();
				}
				byHeader.put(header, member);
			}
		}
		NativeContinuitySupportClauses union = null;
		for(NativeContinuitySupportClauses member : byHeader.values()) {
			union = union == null ? member : union.multiHeaderUnion(member).orElse(null);
			if(union == null)
				return java.util.Optional.empty();
		}
		return java.util.Optional.of(union.sameExactAuthority(first) ? first : union);
	}
	private NativeContinuitySupportClauses singleHeaderView(
		NativePlacementContinuity.NativeSupportProduct product) {
		if(products.size() == 1)
			return this;
		// Keep original leaf handles and scope ownership, never the family itself
		// as a donor. Reconstructing from just the product would lose first authority.
		List<AuthorityDonor> donors = authorityDonors.stream()
			.filter(donor -> donor.scope().sameHeaderAuthority(product)).toList();
		return donors.isEmpty() ? null : new NativeContinuitySupportClauses(
			owner, product, clauseWitness, clauseLayoutExact, donors);
	}
	private record HeaderAuthority(DurableAnchorKey seed, DurableAnchorKey output,
		boolean exactRanges) { }

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
		return authorityRetentionUnits(products, relationMetadataUnits, donors)
			<= MAX_AUTHORITY_RETENTION_UNITS;
	}
	private static long authorityRetentionUnits(
		List<NativePlacementContinuity.NativeSupportProduct> products,
		long relationMetadataUnits, List<AuthorityDonor> donors) {
		try {
			long units = relationMetadataUnits;
			for(var product : products)
				units = Math.addExact(units,
					Math.addExact(product.retainedMetadataUnits(), product.size()));
			for(AuthorityDonor donor : donors) {
				// Donors are flattened leaves. Charge all original possible handles:
				// an alias may materialize members outside this donor's restricted scope.
				if(!donor.relation().authorityDonors.isEmpty())
					return Long.MAX_VALUE;
				NativePlacementContinuity.NativeSupportProduct donorProduct =
					donor.relation().singleProduct().orElse(null);
				if(donorProduct == null)
					return Long.MAX_VALUE;
				long original = Math.addExact(donorProduct.retainedMetadataUnits(),
					Math.multiplyExact((long)donor.relation().size(),
						donorProduct.axes().size() + 1L));
				units = Math.addExact(units, Math.addExact(2L,
					Math.addExact(original, donor.scope().retainedMetadataUnits())));
				if(units > MAX_AUTHORITY_RETENTION_UNITS)
					return units;
			}
			return units;
		}
		catch(ArithmeticException overflow) {
			return Long.MAX_VALUE;
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
			+ (conditionalComplement() ? "|excludeExact=" + excludedExactAxes().stream()
				.map(axis -> axis.stream().map(CandidateRealizationInputBinding::normalizedSignature)
					.toList()).toList() : "")
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
	/** Locates an exact clause without materializing this relation's member handles. */
	int ordinalOfExactAuthorityClause(CandidateRealizationSupportClause clause) {
		if(clause.proofDependencies().size() != 1
			|| clause.nativeWorkerPoolLayoutExact() != clauseLayoutExact
			|| !Objects.equals(clause.nativeWorkerPoolWitness(), clauseWitness))
			return -1;
		var key = clause.proofDependencies().get(0);
		if(key.kind() != PlacementProofKind.NATIVE_CONTINUITY || key.owner() != owner)
			return -1;
		for(var product : products) {
			var proof = new NativePlacementContinuity.NativeContinuityProof(
				product.externalSeed(), product.outputWorkerPoolWitness(),
				product.exactPartitionRanges(), clause.inputBindings());
			boolean sameProof = key.hasNativeContinuityDescriptor()
				? key.matchesNativeContinuityDescriptor(proof)
				: key.authoritySignature().equals(proof.normalizedSignature());
			if(sameProof)
				return ordinalOfExactAuthorityMember(product, clause.inputBindings());
		}
		return -1;
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
	/** Shared axes and holes let every downstream consumer use one exact domain. */
	private static boolean sameConditionalDomain(
		NativePlacementContinuity.NativeSupportProduct left,
		NativePlacementContinuity.NativeSupportProduct right) {
		return left.conditionalComplement() && right.conditionalComplement()
			&& left.sameExactAxes(right)
			&& left.excludedExactAxes().equals(right.excludedExactAxes());
	}

	private static List<NativePlacementContinuity.NativeSupportProduct> canonicalProducts(
		List<NativePlacementContinuity.NativeSupportProduct> supplied) {
		if(supplied == null || supplied.isEmpty())
			throw new IllegalArgumentException("Native relation requires at least one proof header");
		List<NativePlacementContinuity.NativeSupportProduct> canonical =
			new java.util.ArrayList<>(supplied);
		if(canonical.size() > 1 && canonical.stream().anyMatch(
			NativePlacementContinuity.NativeSupportProduct::conditionalComplement))
			for(var product : canonical)
				if(!sameConditionalDomain(canonical.get(0), product))
					throw new IllegalArgumentException("Conditional native headers require identical domains");
		canonical.sort(java.util.Comparator.comparing(
			NativePlacementContinuity.NativeSupportProduct::headerAuthoritySignature));
		for(int index = 1; index < canonical.size(); index++)
			if(canonical.get(index - 1).sameHeaderAuthority(canonical.get(index)))
				throw new IllegalArgumentException("Duplicate native proof header");
		return List.copyOf(canonical);
	}

	private record ExactBindingAuthorityKey(CandidateRealizationInputBinding binding,
		CompiledHopKey sourceOwner) {
		private static ExactBindingAuthorityKey of(CandidateRealizationInputBinding binding) {
			return new ExactBindingAuthorityKey(binding,
				binding.source().rule().parentOccurrence());
		}
		@Override public boolean equals(Object other) {
			return this == other || other instanceof ExactBindingAuthorityKey that
				&& binding.equals(that.binding) && sourceOwner == that.sourceOwner;
		}
		@Override public int hashCode() {
			return 31 * binding.hashCode() + System.identityHashCode(sourceOwner);
		}
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

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementPrivacyFacts.PrivacyFact;

/**
 * Compact rejection region for one exact, unmasked input-domain revision.
 * No Oracle capability/profile is invented for tuples that were never visited.
 * Only ABSENT_LOCAL at a certified protected payload operand is rejected.
 */
public final class CandidatePrivacyInputPruning {
	private final PrivacyFact consumer;
	private final List<List<CandidateInputState>> originalDomains;
	private final Map<Integer,PrivacyFact> protectedInputs;
	private final BigInteger generatedTupleCount;
	private final BigInteger rejectedTupleCount;

	CandidatePrivacyInputPruning(PrivacyFact consumer, List<List<FType>> domains,
		Map<Integer,PrivacyFact> protectedInputs) {
		this.consumer = Objects.requireNonNull(consumer, "consumer");
		this.originalDomains = domains.stream().map(domain -> domain.stream()
			.map(type -> type == null ? CandidateInputState.absentLocal() : CandidateInputState.present(type))
			.toList()).toList();
		Map<Integer,PrivacyFact> sources = new java.util.TreeMap<>(protectedInputs);
		if(sources.isEmpty())
			throw new IllegalArgumentException("A privacy mask needs a protected operand");
		for(Map.Entry<Integer,PrivacyFact> entry : sources.entrySet())
			if(entry.getKey() < 0 || entry.getKey() >= domains.size()
				|| !ExecPlacementPolicy.requiresOriginResidency(entry.getValue().privacy()))
				throw new IllegalArgumentException("Invalid protected payload authority");
		this.protectedInputs = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
		BigInteger original = BigInteger.ONE, generated = BigInteger.ONE;
		for(int position = 0; position < originalDomains.size(); position++) {
			List<CandidateInputState> domain = originalDomains.get(position);
			if(domain.size() != domain.stream().distinct().count())
				throw new IllegalArgumentException("Input domain contains duplicate states");
			original = original.multiply(BigInteger.valueOf(domain.size()));
			long retained = sources.containsKey(position)
				? domain.stream().filter(CandidateInputState::present).count() : domain.size();
			generated = generated.multiply(BigInteger.valueOf(retained));
		}
		generatedTupleCount = generated;
		rejectedTupleCount = original.subtract(generated);
	}

	public PrivacyFact consumer() { return consumer; }
	public List<List<CandidateInputState>> originalDomains() { return originalDomains; }
	public Map<Integer,PrivacyFact> protectedInputs() { return protectedInputs; }
	public BigInteger generatedTupleCount() { return generatedTupleCount; }
	public BigInteger rejectedTupleCount() { return rejectedTupleCount; }

	boolean containsOriginalTuple(List<CandidateInputState> inputs) {
		if(inputs == null || inputs.size() != originalDomains.size())
			return false;
		for(int position = 0; position < inputs.size(); position++)
			if(!originalDomains.get(position).contains(inputs.get(position)))
				return false;
		return true;
	}

	public boolean rejects(List<CandidateInputState> inputs) {
		return containsOriginalTuple(inputs) && protectedInputs.keySet().stream()
			.anyMatch(position -> !inputs.get(position).present());
	}

	List<List<FType>> maskedDomains() {
		List<List<FType>> masked = new java.util.ArrayList<>(originalDomains.size());
		for(int position = 0; position < originalDomains.size(); position++) {
			boolean protectedInput = protectedInputs.containsKey(position);
			// Stream.toList permits the null used for an unprotected local operand.
			masked.add(originalDomains.get(position).stream()
				.filter(input -> !protectedInput || input.present()).map(CandidateInputState::fType).toList());
		}
		return List.copyOf(masked);
	}

	/** Publication must recheck occurrence/value identity, access kind and complete coverage. */
	void validate(PlacementAnalysis analysis) {
		var owner = analysis.graph().node(consumer.occurrence()).orElseThrow(() ->
			new IllegalArgumentException("Privacy mask has a foreign consumer"));
		if(analysis.privacyFactAuthority().requireExact(consumer.occurrence()) != consumer
			|| owner.key() != consumer.occurrence() || owner.valueVersion() != consumer.valueVersion()
			|| analysis.requirePrivacy(owner.key()) != consumer.privacy())
			throw new IllegalArgumentException("Privacy mask consumer authority is stale");
		if(PlacementAnalysis.isDmlFunctionCallBoundary(owner, analysis.hop(owner.key()).orElseThrow()))
			throw new IllegalArgumentException("Function handles are not payload pruning authority");
		Map<Integer,PrivacyFact> expectedSources = new LinkedHashMap<>();
		var ownerHop = analysis.hop(owner.key()).orElseThrow();
		for(int position = 0; position < ownerHop.getInput().size(); position++) {
			var edge = analysis.compiledInputEdge(owner.key(), position).orElse(null);
			if(edge != null && ExecPlacementPolicy.requiresOriginResidency(analysis.requirePrivacy(edge.producer()))
				&& PlacementAnalysis.coordinatorInputAccess(analysis.hop(edge.producer()).orElseThrow(),
					ownerHop, position) == PlacementAnalysis.CoordinatorInputAccess.PAYLOAD)
				expectedSources.put(position, analysis.privacyFactAuthority().requireExact(edge.producer()));
		}

		if(!expectedSources.keySet().equals(protectedInputs.keySet()))
			throw new IllegalArgumentException("Privacy mask does not cover every protected payload operand");
		for(Map.Entry<Integer,PrivacyFact> entry : protectedInputs.entrySet()) {
			var edge = analysis.compiledInputEdge(owner.key(), entry.getKey()).orElseThrow(() ->
				new IllegalArgumentException("Privacy mask operand has no exact compiled edge"));
			PrivacyFact source = entry.getValue();
			var producer = analysis.graph().node(edge.producer()).orElseThrow();
			if(expectedSources.get(entry.getKey()) != source
				|| edge.producer() != source.occurrence() || producer.valueVersion() != source.valueVersion()
				|| analysis.requirePrivacy(source.occurrence()) != source.privacy()
				|| !ExecPlacementPolicy.requiresOriginResidency(source.privacy())
				|| PlacementAnalysis.coordinatorInputAccess(analysis.hop(edge.producer()).orElseThrow(),
					analysis.hop(owner.key()).orElseThrow(), entry.getKey())
					!= PlacementAnalysis.CoordinatorInputAccess.PAYLOAD)
				throw new IllegalArgumentException("Privacy mask source/access authority is stale");
		}
		List<CandidateRuleKey> keys = analysis.candidateRuleFacts().orderedFactsForParent(owner.key()).stream()
			.map(PlacementAnalysis.CandidateRuleFact::key).toList();
		if(!generatedTupleCount.equals(BigInteger.valueOf(keys.size())) || keys.stream().anyMatch(key ->
			!containsOriginalTuple(key.orderedInputs()) || rejects(key.orderedInputs())))
			throw new IllegalArgumentException("Privacy mask does not partition this exact domain revision");
	}
}

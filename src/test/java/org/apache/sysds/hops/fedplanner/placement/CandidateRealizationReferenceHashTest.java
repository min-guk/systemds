/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashSet;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Locks the former two-component record's public value contract. */
public class CandidateRealizationReferenceHashTest {
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);

	private record ReferenceOracle(CandidateRuleKey rule,
		PlacementRealizationKey realization) { }

	@Test
	public void matchesIndependentRecordValueContract() {
		CandidateRuleKey rule = rule("owner");
		PlacementRealizationKey realization = PlacementRealizationKey.valueMap(EMISSION, "layout");
		CandidateRealizationReference reference = new CandidateRealizationReference(rule, realization);
		ReferenceOracle oracle = new ReferenceOracle(rule, realization);
		CandidateRealizationReference equalCopy = new CandidateRealizationReference(
			rule("owner"), PlacementRealizationKey.valueMap(EMISSION, "layout"));

		Assert.assertSame(rule, reference.rule());
		Assert.assertSame(realization, reference.realization());
		Assert.assertEquals(oracle.hashCode(), reference.hashCode());
		Assert.assertEquals(oracle.toString().replaceFirst("^ReferenceOracle",
			"CandidateRealizationReference"), reference.toString());
		Assert.assertEquals(reference, equalCopy);
		Assert.assertEquals(equalCopy, reference);
		Assert.assertEquals(reference.hashCode(), equalCopy.hashCode());
		Assert.assertEquals(reference.normalizedSignature(), equalCopy.normalizedSignature());
		Assert.assertEquals(0, reference.compareTo(equalCopy));
		Assert.assertNotEquals(reference, realization);
		Assert.assertNotEquals(reference, null);
	}

	@Test
	public void structuralHashCollisionDoesNotCreateEquality() {
		CandidateRuleKey rule = rule("collision-owner");
		PlacementRealizationKey leftKey = PlacementRealizationKey.valueMap(EMISSION, "Aa");
		PlacementRealizationKey rightKey = PlacementRealizationKey.valueMap(EMISSION, "BB");
		Assert.assertNotEquals(leftKey, rightKey);
		Assert.assertEquals("fixture must exercise equal structural hashes",
			leftKey.hashCode(), rightKey.hashCode());
		CandidateRealizationReference left = new CandidateRealizationReference(rule, leftKey);
		CandidateRealizationReference right = new CandidateRealizationReference(rule, rightKey);

		Assert.assertEquals(left.hashCode(), right.hashCode());
		Assert.assertNotEquals(left, right);
		HashSet<CandidateRealizationReference> references = new HashSet<>();
		references.add(left);
		references.add(right);
		Assert.assertEquals(2, references.size());
	}

	@Test
	public void equalCopiesRemainHashSetLookupCompatible() {
		CandidateRealizationReference stored = new CandidateRealizationReference(
			rule("lookup"), PlacementRealizationKey.valueMap(EMISSION, "lookup-layout"));
		CandidateRealizationReference query = new CandidateRealizationReference(
			rule("lookup"), PlacementRealizationKey.valueMap(EMISSION, "lookup-layout"));
		HashSet<CandidateRealizationReference> references = new HashSet<>(List.of(stored));

		Assert.assertTrue(references.contains(query));
		Assert.assertEquals(stored, query);
	}

	@Test
	public void factoryAndNullGuardsRetainTheirContracts() {
		CandidateRuleKey rule = rule("factory");
		CandidateRealizationSupportClause clause =
			new CandidateRealizationSupportClause(List.of(), List.of());
		CandidateEmissionRealization realization =
			CandidateEmissionRealization.valueMap(EMISSION, "factory-layout", List.of(clause));
		CandidateRealizationReference direct =
			new CandidateRealizationReference(rule, realization.key());

		Assert.assertEquals(direct, CandidateRealizationReference.of(rule, realization));
		Assert.assertThrows(NullPointerException.class,
			() -> new CandidateRealizationReference(null, realization.key()));
		Assert.assertThrows(NullPointerException.class,
			() -> new CandidateRealizationReference(rule, null));
		Assert.assertThrows(NullPointerException.class,
			() -> CandidateRealizationReference.of(null, realization));
		Assert.assertThrows(NullPointerException.class,
			() -> CandidateRealizationReference.of(rule, null));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"reference-hash", "main", List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("reference-hash", "main", "main",
			"compiled", region, name, name);
		return new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL)));
	}
}

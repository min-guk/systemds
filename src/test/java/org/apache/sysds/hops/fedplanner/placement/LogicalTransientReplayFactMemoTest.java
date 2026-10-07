/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class LogicalTransientReplayFactMemoTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"logical-replay-memo", "main", List.of("root"), "root", "compiled");
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState COORDINATOR = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final CandidateInputState INPUT = CandidateInputState.present(FType.ROW);

	@Test
	public void identicalReplayInputReusesOnlyTheLatestExactFact() throws Exception {
		PlacementRelationClosure closure = closure();
		CompiledHopKey owner = key("reader");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(INPUT));
		Hop read = transientRead("A");
		CandidateEmissionRealization firstRealization = realization(owner, LOCAL, "proof-a");
		Object firstAlternative = alternative(LOCAL, INPUT, List.of(firstRealization));

		CandidateRuleFact first = fact(closure, read, rule, firstAlternative);
		CandidateRuleFact reused = fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.of(firstRealization)));
		Assert.assertSame("an exact replay value should reuse its immutable fact", first, reused);

		CandidateRuleFact changedProof = fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.of(realization(owner, LOCAL, "proof-b"))));
		Assert.assertNotSame("a changed realization proof must rebuild the fact", first, changedProof);
		CandidateRuleFact rebuiltOriginal = fact(closure, read, rule, firstAlternative);
		Assert.assertNotSame("the memo must retain only the latest value for one rule", first,
			rebuiltOriginal);
	}

	@Test
	public void stateInputOwnerAndOpcodeChangesCannotReuseAuthority() throws Exception {
		PlacementRelationClosure closure = closure();
		CompiledHopKey owner = key("reader");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(INPUT));
		Hop read = transientRead("A");
		CandidateRuleFact baseline = fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.of(realization(owner, LOCAL, "proof"))));

		CandidateRuleFact changedState = fact(closure, read, rule,
			alternative(COORDINATOR, INPUT, List.of(realization(owner, COORDINATOR, "proof"))));
		Assert.assertNotSame(baseline, changedState);
		CandidateRuleFact restoredState = fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.of(realization(owner, LOCAL, "proof"))));
		Assert.assertNotSame(changedState, restoredState);

		CandidateInputState changedInput = CandidateInputState.absentLocal();
		CandidateRuleKey changedInputRule = new CandidateRuleKey(owner, List.of(changedInput));
		CandidateRuleFact inputFact = fact(closure, read, changedInputRule,
			alternative(LOCAL, changedInput, List.of(realization(owner, LOCAL, "proof"))));
		Assert.assertNotSame(baseline, inputFact);

		CompiledHopKey equalOwner = key("reader");
		Assert.assertEquals(owner, equalOwner);
		Assert.assertNotSame(owner, equalOwner);
		CandidateRuleFact ownerFact = fact(closure, read,
			new CandidateRuleKey(equalOwner, List.of(INPUT)),
			alternative(LOCAL, INPUT, List.of(realization(equalOwner, LOCAL, "proof"))));
		Assert.assertNotSame("structural equality cannot replace exact owner identity", restoredState, ownerFact);

		CandidateRuleFact restoredOwner = fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.of(realization(owner, LOCAL, "proof"))));
		CandidateRuleFact opcodeFact = fact(closure, new LiteralOp(7L), rule,
			alternative(LOCAL, INPUT, List.of(realization(owner, LOCAL, "proof"))));
		Assert.assertNotSame("a changed read opcode must rebuild capability evidence", restoredOwner, opcodeFact);
	}

	@Test
	public void cachedFactPreservesTheCompleteLegacyDetailAndEvidence() throws Exception {
		PlacementRelationClosure closure = closure();
		CompiledHopKey owner = key("reader");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(INPUT));
		Hop read = transientRead("A");
		List<CandidateEmissionRealization> realizations = List.of(
			realization(owner, LOCAL, "proof-a"), realization(owner, LOCAL, "proof-b"));
		CandidateRuleFact actual = fact(closure, read, rule, alternative(LOCAL, INPUT, realizations));

		String expectedDetail = "logical-transient-replay|read=" + owner.normalizedSignature()
			+ "|input=" + INPUT.normalizedSignature() + "|realizations="
			+ realizations.stream().map(CandidateEmissionRealization::normalizedSignature).toList();
		Assert.assertEquals(expectedDetail, actual.capability().detail());
		Assert.assertEquals(read.getOpString(), actual.capability().opcode());
		Assert.assertEquals(Map.of("logicalTransientReplay", "builder-local",
			"read", owner.normalizedSignature(), "realizationCount", "2"),
			actual.shapeProof().consultedFacts());
		Assert.assertEquals(List.of("cfg-reaching-definitions", "reader-layout", "source-realization"),
			actual.shapeProof().requiredFacts());
		Assert.assertEquals(List.of(new CandidateEmissionFact(
			new PlacementEmissionState(LOCAL, false), FType.ROW, null, realizations)),
			actual.allowedEmissionFacts());
		Assert.assertSame(actual, fact(closure, read, rule,
			alternative(LOCAL, INPUT, List.copyOf(realizations))));
	}

	private static PlacementRelationClosure closure() {
		return new PlacementRelationClosure(null, null, null, false, null, false);
	}

	private static CandidateRuleFact fact(PlacementRelationClosure closure, Hop read,
		CandidateRuleKey key, Object alternative) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("logicalTransientReplayFact",
			Hop.class, CandidateRuleKey.class, alternative.getClass());
		method.setAccessible(true);
		return (CandidateRuleFact) method.invoke(closure, read, key, alternative);
	}

	private static Object alternative(PlacementState state, CandidateInputState input,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName()
			+ "$ReplayPlacementAlternative");
		Constructor<?> constructor = type.getDeclaredConstructor(
			PlacementState.class, CandidateInputState.class, List.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(state, input, List.copyOf(realizations), Map.of());
	}

	private static CandidateEmissionRealization realization(CompiledHopKey owner,
		PlacementState state, String proof) {
		return CandidateEmissionRealization.local(new PlacementEmissionState(state, false),
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, owner, proof)), List.of());
	}

	private static Hop transientRead(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, 0, 0, -1, -1);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("logical-replay-memo", "main", "root", "compiled",
			REGION, id, id);
	}
}

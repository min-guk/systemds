/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleNote;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DeferredLogicalTransientReplayDetailTest {
	private static final CandidateInputState INPUT = CandidateInputState.present(FType.ROW);
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);

	@Test
	public void replayConstructionMemoAndStructuredEqualityDoNotMaterializeNativeMembers() throws Exception {
		CandidateEmissionRealization realization =
			NativeContinuitySupportFixtureBridge.realization("deferred-replay", 4, 5);
		CandidateEmissionRealization equalRealization =
			NativeContinuitySupportFixtureBridge.realization("deferred-replay", 4, 5);
		CompiledHopKey owner = key("reader");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(INPUT));
		Hop read = transientRead("A");
		Object alternative = alternative(List.of(realization));
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());

		CandidateRuleFact first = fact(closure(), read, rule, alternative);
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		PlacementRelationClosure sameClosure = closure();
		CandidateRuleFact second = fact(sameClosure, read, rule, alternative(List.of(equalRealization)));
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(0, equalRealization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(first.capability(), second.capability());
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(0, equalRealization.fullyMaterializedSupportClauseCount());

		PlacementRelationClosure memoClosure = closure();
		CandidateRuleFact memoized = fact(memoClosure, read, rule, alternative);
		CandidateRuleFact reused = fact(memoClosure, read, rule, alternative(List.of(realization)));
		Assert.assertSame(memoized, reused);
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		Assert.assertSame(realization,
			memoized.allowedEmissionFacts().get(0).realizations().get(0));
	}

	@Test
	public void onDemandDetailEqualsHashAndToStringMatchTheLegacyRecordContract() throws Exception {
		CandidateEmissionRealization realization =
			NativeContinuitySupportFixtureBridge.realization("deferred-contract", 2, 3);
		CandidateEmissionRealization explicitOracle =
			NativeContinuitySupportFixtureBridge.realization("deferred-contract", 2, 3);
		CompiledHopKey owner = key("reader");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(INPUT));
		CandidateRuleFact fact = fact(closure(), transientRead("A"), rule,
			alternative(List.of(realization)));
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());

		String expectedDetail = "logical-transient-replay|read=" + owner.normalizedSignature()
			+ "|input=" + INPUT.normalizedSignature() + "|realizations="
			+ List.of(explicitOracle.normalizedSignature());
		Assert.assertEquals(expectedDetail, fact.capability().detail());
		Assert.assertEquals("deferred detail must retain exact legacy bytes without expanding native support",
			0, realization.fullyMaterializedSupportClauseCount());

		CandidateCapabilityFact eager = new CandidateCapabilityFact(
			OpCategory.OTHER, fact.capability().opcode(), STATE.execType(), STATE.output(), STATE.fType(),
			ReasonCode.OK, expectedDetail, List.of(new CandidateRuleNote(ReasonCode.INFO,
				"builder-local logical transient replay from exact compatible realizations")));
		LegacyCapability legacy = new LegacyCapability(eager.category(), eager.opcode(), eager.nativeExec(),
			eager.nativeOutput(), eager.nativeFoutFType(), eager.reasonCode(), eager.detail(), eager.notes());
		Assert.assertEquals(eager, fact.capability());
		Assert.assertEquals(fact.capability(), eager);
		Assert.assertEquals(legacy.hashCode(), fact.capability().hashCode());
		String legacyText = legacy.toString().replaceFirst("LegacyCapability", "CandidateCapabilityFact");
		Assert.assertEquals(legacyText, fact.capability().toString());
		Assert.assertEquals(legacyText, new StringBuilder().append(fact.capability()).toString());
		Assert.assertEquals("equality, hash and text must keep the native relation lazy",
			0, realization.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void ordinaryConstructorKeepsLegacyNullNormalizationAndDefensiveNotes() {
		List<CandidateRuleNote> mutableNotes = new ArrayList<>();
		mutableNotes.add(new CandidateRuleNote(ReasonCode.INFO, "note"));
		CandidateCapabilityFact actual = new CandidateCapabilityFact(
			OpCategory.OTHER, null, ExecType.CP, FederatedOutput.LOUT, null,
			ReasonCode.OK, null, mutableNotes);
		mutableNotes.clear();
		LegacyCapability legacy = new LegacyCapability(OpCategory.OTHER, "", ExecType.CP,
			FederatedOutput.LOUT, null, ReasonCode.OK, "",
			List.of(new CandidateRuleNote(ReasonCode.INFO, "note")));
		CandidateCapabilityFact normalized = new CandidateCapabilityFact(
			OpCategory.OTHER, "", ExecType.CP, FederatedOutput.LOUT, null,
			ReasonCode.OK, "", legacy.notes());

		Assert.assertEquals("", actual.opcode());
		Assert.assertEquals("", actual.detail());
		Assert.assertNull(actual.nativeFoutFType());
		Assert.assertEquals(legacy.notes(), actual.notes());
		Assert.assertEquals(normalized, actual);
		Assert.assertEquals(legacy.hashCode(), actual.hashCode());
		Assert.assertEquals(legacy.toString().replaceFirst("LegacyCapability", "CandidateCapabilityFact"),
			actual.toString());
	}

	@Test
	public void distinctReplayMetadataFallsBackToExactLegacyDetailBytes() {
		CandidateEmissionRealization realization =
			NativeContinuitySupportFixtureBridge.realization("deferred-fallback", 2, 2);
		List<CandidateRuleNote> notes = List.of(new CandidateRuleNote(ReasonCode.INFO, "note"));
		CandidateCapabilityFact left = CandidateCapabilityFact.logicalTransientReplay(
			OpCategory.OTHER, "read", ExecType.FED, FederatedOutput.FOUT, FType.ROW,
			ReasonCode.OK, "a|input=b", "c", List.of(realization), notes);
		CandidateCapabilityFact right = CandidateCapabilityFact.logicalTransientReplay(
			OpCategory.OTHER, "read", ExecType.FED, FederatedOutput.FOUT, FType.ROW,
			ReasonCode.OK, "a", "b|input=c", List.of(realization), notes);
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());

		Assert.assertEquals(left, right);
		Assert.assertEquals(4, realization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(left.detail(), right.detail());
		Assert.assertEquals(left.hashCode(), right.hashCode());
		Assert.assertEquals("a repeated exact comparison must remain stable", left, right);
	}

	private record LegacyCapability(OpCategory category, String opcode, ExecType nativeExec,
		FederatedOutput nativeOutput, FType nativeFoutFType, ReasonCode reasonCode,
		String detail, List<CandidateRuleNote> notes) { }

	private static PlacementRelationClosure closure() {
		return new PlacementRelationClosure(null, null, null, false, null, false);
	}

	private static CandidateRuleFact fact(PlacementRelationClosure closure, Hop read,
		CandidateRuleKey key, Object alternative) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("logicalTransientReplayFact",
			Hop.class, CandidateRuleKey.class, alternative.getClass());
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(closure, read, key, alternative);
	}

	private static Object alternative(List<CandidateEmissionRealization> realizations) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName()
			+ "$ReplayPlacementAlternative");
		Constructor<?> constructor = type.getDeclaredConstructor(
			PlacementState.class, CandidateInputState.class, List.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(STATE, INPUT, List.copyOf(realizations), Map.of());
	}

	private static Hop transientRead(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, 0, 0, -1, -1);
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"deferred-logical-replay", "main", List.of("root"), "root", "compiled");
		return new CompiledHopKey("deferred-logical-replay", "main", "root", "compiled",
			region, id, id);
	}
}

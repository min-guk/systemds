/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.*;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactRuleResidualTest {
	@Test
	public void sharesFullEvidenceButStillEvaluatesFreshExactHints() {
		AtomicInteger calls = new AtomicInteger();
		var prepared = prepared((sig, inputs, hint) -> {
			calls.incrementAndGet(); hint.rows(); return caps(sig).build();
		});
		var residual = prepared.prepareExactRuleDecision(inputs -> new ShapeHint(8, 4, 1000));
		var first = residual.decide(List.of(FType.ROW));
		for(int i = 0; i < 1000; i++)
			Assert.assertSame(first, residual.decide(List.of(FType.COL)));
		Assert.assertEquals(1001, calls.get());
		Assert.assertEquals(1000, residual.diagnostics().reusedEvidence());
		Assert.assertEquals(1, residual.diagnostics().retainedEvidence());
	}

	@Test
	public void completeShapeProofAndOrderedNotesRemainDistinct() {
		AtomicInteger mode = new AtomicInteger();
		var prepared = prepared((sig, inputs, hint) -> {
			hint.fullSinglePartition();
			return caps(sig).note(ReasonCode.INFO, mode.get() % 2 == 0 ? "a" : "b")
				.note(ReasonCode.INFO, mode.get() % 2 == 0 ? "b" : "a").build();
		});
		Boolean[] full = {true, false, null};
		var residual = prepared.prepareExactRuleDecision(inputs -> new ShapeHint(8, 4, 1000, full[mode.get()/2]));
		Object[] observed = new Object[6];
		for(int i = 0; i < 6; i++) {
			mode.set(i); observed[i] = residual.decide(List.of(FType.FULL));
			for(int j = 0; j < i; j++) Assert.assertNotSame(observed[j], observed[i]);
		}
		Assert.assertEquals(6, residual.diagnostics().retainedEvidence());
		Assert.assertEquals(0, residual.diagnostics().reusedEvidence());
	}

	@Test
	public void saturationLosesOnlySharingAndKeepsExistingEntries() {
		AtomicInteger rows = new AtomicInteger();
		var prepared = prepared((sig, inputs, hint) -> { hint.rows(); return caps(sig).build(); });
		var residual = prepared.prepareExactRuleDecision(inputs -> new ShapeHint(rows.get(), 4, 1000));
		var first = residual.decide(List.of(FType.ROW));
		for(int i = 1; i < 600; i++) {
			rows.set(i);
			var actual = residual.decide(List.of(FType.ROW));
			Assert.assertEquals(prepared.decideWithEvidence(List.of(FType.ROW),
				new ShapeHint(i, 4, 1000)).shapeProof(), actual.shapeProof());
		}
		Assert.assertEquals(256, residual.diagnostics().retainedEvidence());
		Assert.assertEquals(344, residual.diagnostics().overflowEvidence());
		rows.set(0); Assert.assertSame(first, residual.decide(List.of(FType.ROW)));
	}

	@Test
	public void protectedNullTuplesNeverReachTheForwardRuleAndFailuresPropagate() {
		AtomicInteger calls = new AtomicInteger();
		var prepared = prepared((sig, inputs, hint) -> {
			calls.incrementAndGet(); return caps(sig).build();
		});
		var residual = prepared.prepareExactRuleDecision(inputs -> new ShapeHint(8, 4, 1000));
		PlacementCandidateGenerator.forEachInputCombination(List.of(Arrays.asList(null, FType.ROW)),
			new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE, Set.of(0)), prepared,
			residual::decide, null);
		Assert.assertEquals(1, calls.get());
		var failing = prepared((sig, inputs, hint) -> { throw new IllegalArgumentException("rule failure"); });
		Assert.assertThrows(IllegalStateException.class, () -> failing.prepareExactRuleDecision(
			inputs -> new ShapeHint(8, 4, 1000)).decide(List.of(FType.ROW)));
	}

	private static OpCaps.Builder caps(OpSig sig) {
		return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
			.exec(ExecType.FED).placement(FederatedOutput.LOUT).reason(ReasonCode.OK);
	}
	private static OracleFacade.PreparedDecision prepared(FedOracle oracle) {
		var registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.OTHER; }
			@Override public Set<String> opcodes() { return Set.of("exp"); }
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				return oracle.caps(sig, inputs, hint);
			}
		});
		var matrix = new DataOp("X", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			"X", 8, 4, 32, 1000);
		return new OracleFacade(registry).prepareDecision(new UnaryOp("exp", DataType.MATRIX,
			ValueType.FP64, OpOp1.EXP, matrix));
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.recompile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class PlannerRecompileProgramAuthorityTest {
	@After
	public void clearLegacyDiagnosticAuthority() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
	}

	@Test
	public void laterProgramCannotClearOrReplacePreparedProgramAuthority() throws Exception {
		DMLProgram first = new DMLProgram();
		DMLProgram second = new DMLProgram();
		Hop firstOwner = hop("first", ExecType.FED, FederatedOutput.FOUT);
		Hop secondOwner = hop("second", ExecType.CP, FederatedOutput.LOUT);
		publish(first, firstOwner, ExecType.FED, FederatedOutput.FOUT);
		publish(second, secondOwner, ExecType.CP, FederatedOutput.LOUT);

		assertRestored(first, firstOwner, ExecType.FED, FederatedOutput.FOUT);
		assertRestored(second, secondOwner, ExecType.CP, FederatedOutput.LOUT);
		assertRestored(first, firstOwner, ExecType.FED, FederatedOutput.FOUT);
	}

	@Test
	public void ownerAwareRecompileEntrypointRestoresProgramState() {
		DMLProgram owner = new DMLProgram();
		Hop planned = hop("planned", ExecType.CP, FederatedOutput.LOUT);
		publish(owner, planned, ExecType.CP, FederatedOutput.LOUT);
		Hop replacement = hop("replacement", null, FederatedOutput.NONE);

		Recompiler.recompileHopsDag(replacement, new LocalVariableMap(), null,
			true, false, 0, owner);

		assertEquals(ExecType.CP, replacement.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, replacement.getFederatedOutput());
	}

	@Test
	public void statementBlockAndAuxiliaryEntrypointsUseProgramAuthority() {
		DMLProgram owner = new DMLProgram();
		Hop planned = hop("planned", ExecType.CP, FederatedOutput.LOUT);
		publish(owner, planned, ExecType.CP, FederatedOutput.LOUT);
		StatementBlock statementBlock = new StatementBlock();
		statementBlock.setDMLProg(owner);
		statementBlock.initializeforwardLV(new VariableSet());
		statementBlock.analyze(new VariableSet());

		Hop statementReplacement = hop("statement", null, FederatedOutput.NONE);
		ArrayList<Hop> roots = new ArrayList<>(List.of(statementReplacement));
		statementBlock.setHops(roots);
		Recompiler.recompileHopsDag(statementBlock, roots, new LocalVariableMap(),
			null, true, false, 0);
		assertEquals(ExecType.CP, statementReplacement.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, statementReplacement.getFederatedOutput());

		Hop instructionReplacement = hop("instruction", null, FederatedOutput.NONE);
		Recompiler.recompileHopsDagInstructions(instructionReplacement, owner);
		assertEquals(ExecType.CP, instructionReplacement.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, instructionReplacement.getFederatedOutput());

		Hop forcedReplacement = hop("forced", null, FederatedOutput.NONE);
		Recompiler.recompileHopsDag2Forced(forcedReplacement, 0, ExecType.CP, owner);
		assertEquals(ExecType.CP, forcedReplacement.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, forcedReplacement.getFederatedOutput());
	}

	@Test
	public void authorityIsInvisibleToUnrelatedCompilationRewrite() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DMLProgram planned = new DMLProgram();
		Hop owner = hop("planned", ExecType.FED, FederatedOutput.FOUT);
		publish(planned, owner, ExecType.FED, FederatedOutput.FOUT);

		assertFalse("An unrelated compilation must not observe another program's rewrite guard",
			FederatedPlannerUtils.hasPlannerRecompileStateAuthority());
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(planned)) {
			assertTrue(FederatedPlannerUtils.hasPlannerRecompileStateAuthority());
			assertThrows(IllegalStateException.class, () ->
				FederatedPlannerUtils.registerPlannerRecompileState(
					hop("late", ExecType.CP, FederatedOutput.LOUT),
					ExecType.CP, FederatedOutput.LOUT));
		}
	}

	@Test
	public void concurrentProgramsResolveSameSignatureAgainstOwnAuthority() throws Exception {
		DMLProgram first = new DMLProgram();
		DMLProgram second = new DMLProgram();
		Hop firstOwner = hop("first", ExecType.FED, FederatedOutput.FOUT);
		Hop secondOwner = hop("second", ExecType.CP, FederatedOutput.LOUT);
		publish(first, firstOwner, ExecType.FED, FederatedOutput.FOUT);
		publish(second, secondOwner, ExecType.CP, FederatedOutput.LOUT);
		String signature = FederatedPlannerUtils.plannerRecompileSignature(firstOwner);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread firstThread = lookupThread(first, signature, ExecType.FED,
			FederatedOutput.FOUT, ready, start, failure);
		Thread secondThread = lookupThread(second, signature, ExecType.CP,
			FederatedOutput.LOUT, ready, start, failure);
		firstThread.start();
		secondThread.start();
		assertTrue(ready.await(10, TimeUnit.SECONDS));
		start.countDown();
		firstThread.join(10000);
		secondThread.join(10000);
		assertFalse(firstThread.isAlive());
		assertFalse(secondThread.isAlive());
		if(failure.get() != null)
			throw new AssertionError("Concurrent owner lookup failed", failure.get());
	}

	@Test
	public void rollbackRestoreMutatesOnlyActiveProgram() {
		DMLProgram first = new DMLProgram();
		DMLProgram second = new DMLProgram();
		Hop firstOwner = hop("first", ExecType.FED, FederatedOutput.FOUT);
		Hop secondOwner = hop("second", ExecType.CP, FederatedOutput.LOUT);
		publish(first, firstOwner, ExecType.FED, FederatedOutput.FOUT);

		second.getPlannerRecompileAuthority().beginPlanning();
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(second)) {
			FederatedPlannerUtils.registerPlannerRecompileState(
				secondOwner, ExecType.CP, FederatedOutput.LOUT);
			Map<String, FederatedPlannerUtils.PlannerRecompileStateSnapshot> snapshot =
				FederatedPlannerUtils.snapshotPlannerRecompileStates();
			FederatedPlannerUtils.registerPlannerRecompileState(
				hop("conflict", ExecType.FED, FederatedOutput.FOUT),
				ExecType.FED, FederatedOutput.FOUT);
			assertTrue(FederatedPlannerUtils.snapshotPlannerRecompileStates().isEmpty());
			FederatedPlannerUtils.restorePlannerRecompileStates(snapshot, java.util.Set.of());
			assertEquals(ExecType.CP, FederatedPlannerUtils.getPlannerRecompileState(
				FederatedPlannerUtils.plannerRecompileSignature(secondOwner)).getExecType());
		}
		second.getPlannerRecompileAuthority().seal();
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(first)) {
			assertEquals(ExecType.FED, FederatedPlannerUtils.getPlannerRecompileState(
				FederatedPlannerUtils.plannerRecompileSignature(firstOwner)).getExecType());
		}
	}

	private static Thread lookupThread(DMLProgram owner, String signature, ExecType exec,
		FederatedOutput output, CountDownLatch ready, CountDownLatch start,
		AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
				FederatedPlannerUtils.activatePlannerRecompileOwner(owner)) {
				ready.countDown();
				if(!start.await(10, TimeUnit.SECONDS))
					throw new AssertionError("Timed out waiting for concurrent lookup");
				for(int i = 0; i < 1000; i++) {
					PlannerRecompileState state = FederatedPlannerUtils.getPlannerRecompileState(signature);
					assertEquals(exec, state.getExecType());
					assertEquals(output, state.getFederatedOutput());
				}
			}
			catch(Throwable ex) {
				failure.compareAndSet(null, ex);
			}
		});
	}

	private static void publish(DMLProgram program, Hop hop, ExecType exec,
		FederatedOutput output) {
		program.getPlannerRecompileAuthority().beginPlanning();
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(program)) {
			FederatedPlannerUtils.registerPlannerRecompileState(hop, exec, output);
		}
		program.getPlannerRecompileAuthority().seal();
	}

	private static void assertRestored(DMLProgram program, Hop owner, ExecType exec,
		FederatedOutput output) throws Exception {
		Method snapshot = Recompiler.class.getDeclaredMethod("snapshotHopStates", List.class);
		snapshot.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Long, ?> baseStates = (Map<Long, ?>) snapshot.invoke(null, List.of(owner));
		Hop replacement = hop("replacement", null, FederatedOutput.NONE);
		Method restore = Recompiler.class.getDeclaredMethod(
			"restoreHopStates", List.class, Map.class, Map.class);
		restore.setAccessible(true);
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(program)) {
			restore.invoke(null, List.of(replacement), baseStates, null);
		}
		assertEquals(exec, replacement.getForcedExecType());
		assertEquals(output, replacement.getFederatedOutput());
	}

	private static Hop hop(String name, ExecType exec, FederatedOutput output) {
		Hop input = new DataOp(name + "-input", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name + "-input", 10, 10, 100, 1000);
		Hop hop = new UnaryOp(name, DataType.MATRIX, ValueType.FP64, OpOp1.EXP, input);
		hop.setBeginLine(17);
		hop.setBeginColumn(3);
		hop.setEndLine(17);
		hop.setEndColumn(12);
		hop.setExecType(exec);
		hop.setForcedExecType(exec);
		hop.setFederatedOutput(output);
		return hop;
	}
}

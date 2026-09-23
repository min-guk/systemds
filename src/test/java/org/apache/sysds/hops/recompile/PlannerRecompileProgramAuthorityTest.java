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
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileState;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimeActionRegistry;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.ConsumerInputSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.runtime.controlprogram.IfProgramBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class PlannerRecompileProgramAuthorityTest {
	@After
	public void clearLegacyDiagnosticAuthority() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		PlannerRuntimeActionRegistry.clear();
	}

	@Test
	public void compiledRecompileClearsStaleDefaultMaterializationBeforeConstantFolding() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			FederatedRefedRegistry.register(-1L, 12343L, -1L, "stale-anchor", List.of(67889L));
			FederatedFoutMaterializeRegistry.register(-1L, 12344L, -1L, "COL");
			FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, 12345L,
				List.of(new ConsumerInputSpec(67891L, 0)), "COL", "stale-compiler-temp");
			BinaryOp foldable = new BinaryOp("foldable", DataType.SCALAR, ValueType.FP64,
				OpOp2.PLUS, new LiteralOp(1D), new LiteralOp(2D));

			Recompiler.recompileHopsDag(foldable, new LocalVariableMap(), null,
				false, false, 0, new DMLProgram());

			assertTrue("Per-call compiler scratch must be empty after recompilation",
				FederatedLocalMaterializeRegistry.isEmpty());
			assertTrue(FederatedRefedRegistry.isEmpty());
			assertTrue(FederatedFoutMaterializeRegistry.isEmpty());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void ownerlessRecompileAlsoClearsStaleDefaultMaterializationBeforeConstantFolding() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "none");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, 22345L,
				List.of(new ConsumerInputSpec(77891L, 0)), "COL", "stale-ownerless-temp");
			BinaryOp foldable = new BinaryOp("foldable-ownerless", DataType.SCALAR, ValueType.FP64,
				OpOp2.PLUS, new LiteralOp(1D), new LiteralOp(2D));

			Recompiler.recompileHopsDag(foldable, new LocalVariableMap(), null,
				false, false, 0);

			assertTrue(FederatedLocalMaterializeRegistry.isEmpty());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void concurrentDifferentDagRecompilesCannotClearActiveCompilerScratch() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		CountDownLatch scratchPublished = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		AtomicReference<Throwable> secondFailure = new AtomicReference<>();
		try {
			Hop first = new RegistryPublishingUnary("first", scratchPublished, releaseFirst, false);
			Hop second = hop("second", ExecType.CP, FederatedOutput.LOUT);
			Thread firstThread = recompileThread(first, new DMLProgram(), firstFailure);
			Thread secondThread = recompileThread(second, new DMLProgram(), secondFailure);
			firstThread.start();
			assertTrue("First recompile did not publish its compiler scratch",
				scratchPublished.await(10, TimeUnit.SECONDS));
			secondThread.start();
			Thread.sleep(250);
			releaseFirst.countDown();
			firstThread.join(10000);
			secondThread.join(10000);
			assertFalse(firstThread.isAlive());
			assertFalse(secondThread.isAlive());
			if(firstFailure.get() != null)
				throw new AssertionError("First recompile lost its active scratch", firstFailure.get());
			if(secondFailure.get() != null)
				throw new AssertionError("Second recompile failed", secondFailure.get());
		}
		finally {
			releaseFirst.countDown();
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void failedCompiledRecompileClearsAllCompilerScratch() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			Hop failing = new RegistryPublishingUnary("failing",
				new CountDownLatch(0), new CountDownLatch(0), true);
			assertThrows(RuntimeException.class, () -> Recompiler.recompileHopsDag(failing,
				new LocalVariableMap(), null, true, false, 0, new DMLProgram()));
			assertTrue(FederatedRefedRegistry.isEmpty());
			assertTrue(FederatedFoutMaterializeRegistry.isEmpty());
			assertTrue(FederatedLocalMaterializeRegistry.isEmpty());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void durableSelectedLocalActionStillLowersInsideIsolatedRecompile() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			DataOp producer = new DataOp("X", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "X", 10, 10, 100, 1000);
			producer.setForcedExecType(ExecType.FED);
			producer.setFederatedOutput(FederatedOutput.FOUT);
			producer.setPlannerPlacementSelected(true);
			UnaryOp consumer = new UnaryOp("abs", DataType.MATRIX, ValueType.FP64,
				OpOp1.ABS, producer);
			consumer.setForcedExecType(ExecType.CP);
			consumer.setFederatedOutput(FederatedOutput.LOUT);
			consumer.setPlannerPlacementSelected(true);
			FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, producer.getHopID(),
				List.of(new ConsumerInputSpec(consumer.getHopID(), 0)), "COL", "durable-local",
				"LOCAL:test-durable-action");
			PlannerRuntimeActionRegistry.commitCurrentLoweringAuthorities();
			FederatedLocalMaterializeRegistry.clear();

			ArrayList<Instruction> instructions = Recompiler.recompileHopsDag(consumer,
				new LocalVariableMap(), null, true, false, 0, new DMLProgram());

			assertTrue("Durable exact local action must lower to a prefetch instruction: " + instructions,
				instructions.stream().map(Object::toString).anyMatch(value -> value.contains("prefetch")));
			assertTrue("Durable action authority must survive compiler-scratch cleanup",
				PlannerRuntimeActionRegistry.snapshot().local().scopes().values().stream()
					.anyMatch(scope -> scope.containsKey(producer.getHopID())));
			assertTrue(FederatedLocalMaterializeRegistry.isEmpty());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
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
	public void nestedIfHierarchyRecompileUsesStatementProgramAuthority() {
		DMLProgram unrelated = new DMLProgram();
		DMLProgram hierarchyOwner = new DMLProgram();
		Hop unrelatedPlan = hop("unrelated-plan", ExecType.FED, FederatedOutput.FOUT);
		Hop hierarchyPlan = hop("hierarchy-plan", ExecType.CP, FederatedOutput.LOUT);
		publish(unrelated, unrelatedPlan, ExecType.FED, FederatedOutput.FOUT);
		publish(hierarchyOwner, hierarchyPlan, ExecType.CP, FederatedOutput.LOUT);

		Program runtimeProgram = new Program(hierarchyOwner);
		IfProgramBlock outer = ifBlock(runtimeProgram, hierarchyOwner, new LiteralOp(true));
		Hop nestedPredicate = hop("nested-predicate", null, FederatedOutput.NONE);
		IfProgramBlock nested = ifBlock(runtimeProgram, hierarchyOwner, nestedPredicate);
		outer.addProgramBlockIfBody(nested);

		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored =
			FederatedPlannerUtils.activatePlannerRecompileOwner(unrelated)) {
			Recompiler.recompileProgramBlockHierarchy(List.<ProgramBlock>of(outer),
				new LocalVariableMap(), 0, true, Recompiler.ResetType.NO_RESET);
		}

		assertEquals("Nested if predicates must ignore unrelated thread-local authority",
			ExecType.CP, nestedPredicate.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, nestedPredicate.getFederatedOutput());
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

	private static Thread recompileThread(Hop root, DMLProgram owner,
		AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			try {
				Recompiler.recompileHopsDag(root, new LocalVariableMap(), null,
					true, false, 0, owner);
			}
			catch(Throwable ex) {
				failure.compareAndSet(null, ex);
			}
		});
	}

	private static final class RegistryPublishingUnary extends UnaryOp {
		private final CountDownLatch _published;
		private final CountDownLatch _release;
		private final boolean _fail;

		private RegistryPublishingUnary(String name, CountDownLatch published,
			CountDownLatch release, boolean fail) {
			super(name, DataType.MATRIX, ValueType.FP64, OpOp1.EXP,
				new DataOp(name + "-input", DataType.MATRIX, ValueType.FP64,
					OpOpData.TRANSIENTREAD, name + "-input", 10, 10, 100, 1000));
			_published = published;
			_release = release;
			_fail = fail;
			setForcedExecType(ExecType.CP);
			setFederatedOutput(FederatedOutput.LOUT);
		}

		@Override
		public Lop constructLops() {
			Lop result = super.constructLops();
			FederatedRefedRegistry.register(-1L, 70001L, -1L, "active-anchor", List.of(70002L));
			FederatedFoutMaterializeRegistry.register(-1L, 70003L, -1L, "COL");
			FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, 70004L,
				List.of(new ConsumerInputSpec(70005L, 0)), "COL", "active-compiler-scratch");
			_published.countDown();
			try {
				if(!_release.await(10, TimeUnit.SECONDS))
					throw new AssertionError("Timed out waiting to finish compiler scratch probe");
			}
			catch(InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while probing compiler scratch", ex);
			}
			if(_fail)
				throw new IllegalStateException("injected compiler failure");
			if(!FederatedRefedRegistry.hasEntry(70001L)
				|| !FederatedFoutMaterializeRegistry.hasEntry(70003L)
				|| !FederatedLocalMaterializeRegistry.hasEntry(70004L))
				throw new AssertionError("Another recompile cleared active compiler scratch");
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			return result;
		}
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

	private static IfProgramBlock ifBlock(Program runtimeProgram, DMLProgram owner, Hop predicate) {
		IfStatementBlock statementBlock = new IfStatementBlock();
		statementBlock.setDMLProg(owner);
		statementBlock.setPredicateHops(predicate);
		statementBlock.setRecompileOnce(true);
		IfProgramBlock programBlock = new IfProgramBlock(runtimeProgram, new ArrayList<>());
		programBlock.setStatementBlock(statementBlock);
		return programBlock;
	}
}

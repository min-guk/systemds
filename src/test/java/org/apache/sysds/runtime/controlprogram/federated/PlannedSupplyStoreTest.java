/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopeKind;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopePlan;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.SupplySchedule;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.parser.DataIdentifier;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.FunctionCallCPInstruction;
import org.apache.sysds.runtime.lineage.LineageItem;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.junit.Test;

public class PlannedSupplyStoreTest {
	private static final long SNAPSHOT = 7;
	private static final long REGION = 11;

	@Test
	public void selectedActionIsLazyAndSharedExactlyOnceWithinInvocation() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 3);
		AtomicInteger creates = new AtomicInteger();
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			assertEquals(0, creates.get());
			CacheableData<?> first = store.getOrCreate(scope, action, 0, source,
				(input, recipe) -> uploaded("out", input, pool, creates.incrementAndGet()));
			CacheableData<?> second = store.getOrCreate(scope, action, 0, source,
				(input, recipe) -> uploaded("unused", input, pool, creates.incrementAndGet()));
			assertSame(first, second);
			assertEquals(1, creates.get());
			assertFalse(source.isCleanupEnabled());
		}
		assertTrue(source.isCleanupEnabled());
	}

	@Test
	public void mutationDefinitionAndInvocationTokensPreventStaleReuse() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 4);
		AtomicInteger creates = new AtomicInteger();
		PlannedSupplyStore.Materializer materializer = (input, recipe) ->
			uploaded("out" + creates.get(), input, pool, creates.incrementAndGet());

		CacheableData<?> first;
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			first = store.getOrCreate(scope, action, 0, source, materializer);
			source.acquireModify(new MatrixBlock(2, 2, 9));
			source.release();
			CacheableData<?> afterMutation = store.getOrCreate(scope, action, 0, source, materializer);
			CacheableData<?> afterDefinition = store.getOrCreate(scope, action, 1, source, materializer);
			assertNotSame(first, afterMutation);
			assertNotSame(afterMutation, afterDefinition);
		}
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			assertNotSame(first, store.getOrCreate(scope, action, 1, source, materializer));
		}
		assertEquals(4, creates.get());
	}

	@Test
	public void changedRuntimeShapeInvalidatesTheStaticActionSlot() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 5);
		AtomicInteger creates = new AtomicInteger();
		PlannedSupplyStore.Materializer materializer = (input, recipe) ->
			uploaded("out", input, pool, creates.incrementAndGet());
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			CacheableData<?> first = store.getOrCreate(scope, action, 0, source, materializer);
			source.getDataCharacteristics().set(3, 2, 1024);
			CacheableData<?> resized = store.getOrCreate(scope, action, 0, source, materializer);
			assertNotSame(first, resized);
		}
		assertEquals(2, creates.get());
	}

	@Test
	public void repeatedDefinitionsKeepOneBoundedSourcePinPerActionSlot() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 51);
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			for(int epoch = 0; epoch < 100; epoch++) {
				int current = epoch;
				store.getOrCreate(scope, action, epoch, source,
					(input, recipe) -> uploaded("out" + current, input, pool, current + 100));
				assertEquals(1, store.activePinnedSourceCount());
				assertFalse(source.isCleanupEnabled());
			}
		}
		assertEquals(0, store.activePinnedSourceCount());
		assertTrue(source.isCleanupEnabled());
	}

	@Test
	public void overlappingInvocationScopesReferenceCountTheSameSourcePin() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext firstContext = context();
		ExecutionContext secondContext = context();
		MatrixObject source = localMatrix("source", 2, 2);
		firstContext.setVariable("source", source);
		secondContext.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 52);
		PlannedSupplyStore.Scope first = store.beginScope(firstContext, SNAPSHOT, REGION, List.of(action));
		PlannedSupplyStore.Scope second = store.beginScope(secondContext, SNAPSHOT, REGION, List.of(action));
		store.getOrCreate(first, action, 0, source,
			(input, recipe) -> uploaded("first", input, pool, 201));
		store.getOrCreate(second, action, 0, source,
			(input, recipe) -> uploaded("second", input, pool, 202));
		assertEquals(1, store.activePinnedSourceCount());
		first.close();
		assertFalse(source.isCleanupEnabled());
		assertEquals(1, store.activePinnedSourceCount());
		second.close();
		assertTrue(source.isCleanupEnabled());
		assertEquals(0, store.activePinnedSourceCount());
	}

	@Test
	public void failedSelectedRecipeIsNotRetriedOrReplacedByFallback() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		PlannedSupplyStore.Action action = uploadAction(pool(), 6);
		AtomicInteger attempts = new AtomicInteger();
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			DMLRuntimeException first = assertThrows(DMLRuntimeException.class,
				() -> store.getOrCreate(scope, action, 0, source, (input, recipe) -> {
					attempts.incrementAndGet();
					throw new DMLRuntimeException("selected recipe failed");
				}));
			DMLRuntimeException second = assertThrows(DMLRuntimeException.class,
				() -> store.getOrCreate(scope, action, 0, source, (input, recipe) -> {
					attempts.incrementAndGet();
					return uploaded("fallback", input, pool(), 99);
				}));
			assertSame(first, second);
			assertEquals(1, attempts.get());
		}
	}

	@Test
	public void unselectedActionsAndNonLifoParentClosureAreRejected() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action selected = uploadAction(pool, 7);
		PlannedSupplyStore.Action other = uploadAction(pool, 8);
		PlannedSupplyStore.Scope parent = store.beginScope(context, SNAPSHOT, REGION, List.of(selected));
		PlannedSupplyStore.Scope child = store.beginChildScope(parent, context, 12, List.of(
			new PlannedSupplyStore.Action(SNAPSHOT, 9, MemoPartition.ROW,
				new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode()), 12, "child")));
		assertThrows(DMLRuntimeException.class, () -> store.getOrCreate(parent, other, 0,
			localMatrix("source", 2, 2), (input, recipe) -> uploaded("out", input, pool, 1)));
		assertThrows(IllegalStateException.class, parent::close);
		child.close();
		parent.close();
	}

	@Test
	public void exportedCollectedOutputSurvivesRegionCleanup() {
		PlannedSupplyStore store = new PlannedSupplyStore();
		ExecutionContext context = context();
		CanonicalWorkerPool pool = pool();
		MatrixObject source = federatedMatrix("source", pool, 2, 2, 31);
		source.enableCleanup(false); // external remote owner; this fixture must not issue worker cleanup
		context.setVariable("source", source);
		Recipe recipe = new Recipe(MemoPartition.ROW, Movement.COLLECT, pool.encode());
		PlannedSupplyStore.Action action = new PlannedSupplyStore.Action(
			SNAPSHOT, 10, MemoPartition.LOCAL, recipe, REGION, "collect");
		MatrixObject output;
		try(PlannedSupplyStore.Scope scope = store.beginScope(context, SNAPSHOT, REGION, List.of(action))) {
			output = (MatrixObject) store.getOrCreate(scope, action, 0, source,
				(input, selected) -> localMatrix("collected", 2, 2));
			context.setVariable("exported", output);
		}
		assertTrue(output.isCleanupEnabled());
		assertEquals(4, output.acquireReadAndRelease().getNumRows() * output.getNumColumns());
	}

	@Test
	public void selectedRuntimeRequiresDefinitionMarkerAndSharesOnlyCurrentEpoch() {
		ExecutionContext context = context();
		MatrixObject source = localMatrix("source", 2, 2);
		context.setVariable("source", source);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action action = uploadAction(pool, 53);
		SupplySchedule schedule = new SupplySchedule(
			List.of(new ScopePlan(ScopeKind.SAME_SB, 41, REGION, List.of(action))), List.of(), List.of());
		AtomicInteger creates = new AtomicInteger();
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(context, SNAPSHOT, schedule);
			PlannedSupplyRuntime.Region region = PlannedSupplyRuntime.enter(context, ScopeKind.SAME_SB, 41)) {
			String plannedSupply = InstructionUtils.concatOperands("FED", "fed_supply",
				InstructionUtils.concatOperandParts("source", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("planned", DataType.MATRIX.name(), ValueType.FP64.name()),
				MemoPartition.ROW.name(), InstructionUtils.createLiteralOperand(
					org.apache.sysds.lops.FederatedSupply.encodeRecipes(List.of(action.recipe())), ValueType.STRING),
				InstructionUtils.createLiteralOperand(
					org.apache.sysds.lops.FederatedSupply.encodeActions(Map.of(0, action)), ValueType.STRING));
			assertThrows(DMLRuntimeException.class,
				() -> InstructionParser.parseSingleInstruction(plannedSupply).processInstruction(context));
			assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.materialize(context, action, source,
				(input, recipe) -> uploaded("unmarked", input, pool, 300)));
			String marker = InstructionUtils.concatOperands("FED", "memo_def",
				InstructionUtils.concatOperandParts("source", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("marked", DataType.MATRIX.name(), ValueType.FP64.name()), "53");
			InstructionParser.parseSingleInstruction(marker).processInstruction(context);
			CacheableData<?> first = PlannedSupplyRuntime.materialize(context, action, source,
				(input, recipe) -> uploaded("first", input, pool, 301 + creates.getAndIncrement()));
			assertSame(first, PlannedSupplyRuntime.materialize(context, action, source,
				(input, recipe) -> uploaded("unused", input, pool, 999)));
			InstructionParser.parseSingleInstruction(marker).processInstruction(context);
			CacheableData<?> nextEpoch = PlannedSupplyRuntime.materialize(context, action, source,
				(input, recipe) -> uploaded("second", input, pool, 301 + creates.getAndIncrement()));
			assertNotSame(first, nextEpoch);
			assertEquals(2, creates.get());
		}
	}

	@Test
	public void guardedSameLayoutCandidatesMatchOnlyExactOriginAndSourceIdentity() {
		ExecutionContext context=context();
		CanonicalWorkerPool pool=pool();
		Recipe recipe=new Recipe(MemoPartition.LOCAL,Movement.UPLOAD,pool.encode());
		PlannedSupplyStore.Action branchA=new PlannedSupplyStore.Action(
			SNAPSHOT,80,MemoPartition.ROW,recipe,REGION,"branch-a");
		PlannedSupplyStore.Action branchB=new PlannedSupplyStore.Action(
			SNAPSHOT,81,MemoPartition.ROW,recipe,REGION,"branch-b");
		MatrixObject sourceA=localMatrix("branch-a",2,2);
		MatrixObject sourceB=localMatrix("branch-b",2,2);
		context.setVariable("guarded-source",sourceA);
		SupplySchedule schedule=new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.SAME_SB,41,REGION,List.of(branchA,branchB))),List.of(),List.of());
		try(PlannedSupplyRuntime.Execution execution=PlannedSupplyRuntime.open(context,SNAPSHOT,schedule);
			PlannedSupplyRuntime.Region region=PlannedSupplyRuntime.enter(context,ScopeKind.SAME_SB,41)) {
			PlannedSupplyRuntime.markDefinition(context,80,sourceA);
			PlannedSupplyRuntime.markDefinition(context,81,sourceB);
			assertSame(branchA,PlannedSupplyRuntime.requireMatchingAction(
				context,List.of(branchA,branchB),sourceA));
			assertSame(branchB,PlannedSupplyRuntime.requireMatchingAction(
				context,List.of(branchA,branchB),sourceB));
			assertThrows(DMLRuntimeException.class,() -> PlannedSupplyRuntime.requireMatchingAction(
				context,List.of(branchA,branchB),localMatrix("unmarked",2,2)));
			PlannedSupplyStore.Action stale=new PlannedSupplyStore.Action(
				SNAPSHOT+1,82,MemoPartition.ROW,recipe,REGION,"stale");
			assertThrows(DMLRuntimeException.class,() -> PlannedSupplyRuntime.requireMatchingAction(
				context,List.of(branchA,stale),sourceA));
			String staleInstruction=plannedSupplyInstruction(recipe,List.of(branchA,stale));
			assertThrows(DMLRuntimeException.class,
				() -> InstructionParser.parseSingleInstruction(staleInstruction).processInstruction(context));
			PlannedSupplyRuntime.markDefinition(context,81,sourceA);
			assertThrows(DMLRuntimeException.class,() -> PlannedSupplyRuntime.requireMatchingAction(
				context,List.of(branchA,branchB),sourceA));
			String ambiguousInstruction=plannedSupplyInstruction(recipe,List.of(branchA,branchB));
			assertThrows(DMLRuntimeException.class,
				() -> InstructionParser.parseSingleInstruction(ambiguousInstruction).processInstruction(context));
		}
	}

	@Test
	public void guardedActionCandidateEncodingPreservesMultipleOriginsPerRecipe() {
		Recipe recipe=new Recipe(MemoPartition.LOCAL,Movement.UPLOAD,pool().encode());
		PlannedSupplyStore.Action first=new PlannedSupplyStore.Action(
			SNAPSHOT,83,MemoPartition.ROW,recipe,REGION,"first");
		PlannedSupplyStore.Action second=new PlannedSupplyStore.Action(
			SNAPSHOT,84,MemoPartition.ROW,recipe,REGION,"second");
		String encoded=org.apache.sysds.lops.FederatedSupply.encodeActionCandidates(
			Map.of(0,List.of(first,second)));
		assertEquals(Map.of(0,List.of(first,second)),
			org.apache.sysds.lops.FederatedSupply.decodeActionCandidates(encoded,List.of(recipe)));
		assertThrows(org.apache.sysds.lops.LopsException.class,
			() -> org.apache.sysds.lops.FederatedSupply.decodeActions(encoded,List.of(recipe)));
	}

	@Test
	public void rootAndReentrantFunctionScopesShareOnlyExplicitParentState() {
		ExecutionContext root = context();
		ExecutionContext function = context();
		ExecutionContext nested = context();
		CanonicalWorkerPool pool = pool();
		MatrixObject rootSource = localMatrix("root-source", 2, 2);
		MatrixObject functionSource = federatedMatrix("function-source", pool, 2, 2, 390);
		functionSource.enableCleanup(false);
		root.setVariable("root-source", rootSource);
		function.setVariable("root-source", rootSource);
		function.setVariable("function-source", functionSource);
		nested.setVariable("function-source", functionSource);
		PlannedSupplyStore.Action rootAction = new PlannedSupplyStore.Action(SNAPSHOT, 61, MemoPartition.ROW,
			new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode()), 100, "program-root");
		PlannedSupplyStore.Action functionAction = new PlannedSupplyStore.Action(SNAPSHOT, 62, MemoPartition.LOCAL,
			new Recipe(MemoPartition.ROW, Movement.COLLECT, pool.encode()), 101, "function-invocation");
		SupplySchedule schedule = new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.PROGRAM_ROOT, -1, 100, List.of(rootAction)),
			new ScopePlan(ScopeKind.FUNCTION_INVOCATION, 55, 101, List.of(functionAction))), List.of(), List.of());
		AtomicInteger creates = new AtomicInteger();
		CacheableData<?> returned;
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(root, SNAPSHOT, schedule)) {
			PlannedSupplyRuntime.markDefinition(root, 61, rootSource);
			try(PlannedSupplyRuntime.Attachment attached = PlannedSupplyRuntime.attachFunctionContext(root, function);
				PlannedSupplyRuntime.Region invocation = PlannedSupplyRuntime.enter(
					function, ScopeKind.FUNCTION_INVOCATION, 55)) {
				PlannedSupplyRuntime.materialize(function, rootAction, rootSource,
					(input, recipe) -> uploaded("root-upload", input, pool, 400 + creates.getAndIncrement()));
				PlannedSupplyRuntime.markDefinition(function, 62, functionSource);
				returned = PlannedSupplyRuntime.materialize(function, functionAction, functionSource,
					(input, recipe) -> { creates.incrementAndGet(); return localMatrix("outer-return", 2, 2); });
				function.setVariable("return", returned);
				try(PlannedSupplyRuntime.Attachment nestedAttached =
						PlannedSupplyRuntime.attachFunctionContext(function, nested);
					PlannedSupplyRuntime.Region nestedInvocation = PlannedSupplyRuntime.enter(
						nested, ScopeKind.FUNCTION_INVOCATION, 55)) {
					PlannedSupplyRuntime.markDefinition(nested, 62, functionSource);
					assertNotSame(returned, PlannedSupplyRuntime.materialize(nested, functionAction, functionSource,
						(input, recipe) -> { creates.incrementAndGet(); return localMatrix("nested-return", 2, 2); }));
				}
				assertSame(returned, PlannedSupplyRuntime.materialize(function, functionAction, functionSource,
					(input, recipe) -> localMatrix("unused", 2, 2)));
			}
			root.setVariable("escaped", returned);
		}
		assertEquals(2, ((MatrixObject) root.getVariable("escaped")).acquireReadAndRelease().getNumRows());
		assertEquals(3, creates.get());
	}

	@Test
	public void functionCallInstructionAttachesTheSelectedRuntimeSession() {
		Program program = new Program();
		AtomicBoolean observed = new AtomicBoolean();
		FunctionProgramBlock function = new FunctionProgramBlock(program, List.of(), List.of()) {
			@Override public void execute(ExecutionContext ec) {
				observed.set(ec.getPlannedSupplySession() != null);
			}
		};
		program.addFunctionProgramBlock(org.apache.sysds.parser.DMLProgram.DEFAULT_NAMESPACE, "f", function, true);
		ExecutionContext root = ExecutionContextFactory.createContext(program);
		FunctionCallCPInstruction call = new FunctionCallCPInstruction(
			org.apache.sysds.parser.DMLProgram.DEFAULT_NAMESPACE, "f", true, new CPOperand[0],
			List.of(), List.of(), "function session test");
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(
			root, SNAPSHOT, SupplySchedule.empty())) {
			call.processInstruction(root);
		}
		assertTrue(observed.get());
	}

	@Test
	public void functionReturnExportRequiresLocalExactMarkersAndPublishesAtomically() {
		ExecutionContext root = context();
		ExecutionContext inheritedOnly = context();
		ExecutionContext incomplete = context();
		ExecutionContext complete = context();
		MatrixObject inherited = localMatrix("inherited", 2, 2);
		MatrixObject first = localMatrix("first", 2, 2);
		MatrixObject second = localMatrix("second", 2, 2);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action firstAction = uploadAction(pool, 170);
		PlannedSupplyStore.Action secondAction = uploadAction(pool, 171);
		SupplySchedule schedule = new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.PROGRAM_ROOT, -1, REGION, List.of(firstAction, secondAction))),
			List.of(), List.of());
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(root, SNAPSHOT, schedule)) {
			PlannedSupplyRuntime.markDefinition(root, 69, inherited);
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, inheritedOnly)) {
				assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.exportFunctionReturns(
					root, inheritedOnly, SNAPSHOT, Map.of(0, 69L), Map.of(0, inherited)));
			}
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, incomplete)) {
				PlannedSupplyRuntime.markDefinition(incomplete, 70, first);
				assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.exportFunctionReturns(
					root, incomplete, SNAPSHOT, Map.of(0, 70L, 1, 71L), Map.of(0, 170L, 1, 171L),
					Map.of(0, first, 1, second)));
			}
			assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.materialize(
				root, firstAction, first, (input, recipe) -> uploaded("partial", input, pool, 700)));
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, complete)) {
				PlannedSupplyRuntime.markDefinition(complete, 70, first);
				PlannedSupplyRuntime.markDefinition(complete, 71, second);
				PlannedSupplyRuntime.exportFunctionReturns(root, complete, SNAPSHOT,
					Map.of(0, 70L, 1, 71L), Map.of(0, 170L, 1, 171L), Map.of(0, first, 1, second));
			}
			assertNotSame(first, PlannedSupplyRuntime.materialize(root, firstAction, first,
				(input, recipe) -> uploaded("first-export", input, pool, 701)));
			assertNotSame(second, PlannedSupplyRuntime.materialize(root, secondAction, second,
				(input, recipe) -> uploaded("second-export", input, pool, 702)));
		}
	}

	@Test
	public void functionCallExportsTheExactBoundReturnBeforeChildDetach() {
		Program program = new Program();
		DataIdentifier output = new DataIdentifier("Y", DataType.MATRIX, ValueType.FP64);
		AtomicReference<MatrixObject> returned = new AtomicReference<>();
		FunctionProgramBlock function = new FunctionProgramBlock(program, List.of(), List.of(output)) {
			@Override public void execute(ExecutionContext ec) {
				MatrixObject result = localMatrix("returned", 2, 2);
				returned.set(result);
				ec.setVariable("Y", result);
				PlannedSupplyRuntime.markDefinition(ec, 72, result);
			}
		};
		program.addFunctionProgramBlock(DMLProgram.DEFAULT_NAMESPACE, "f", function, true);
		ExecutionContext root = ExecutionContextFactory.createContext(program);
		PlannedSupplyStore.Action action = uploadAction(pool(), 170);
		SupplySchedule schedule = new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.PROGRAM_ROOT, -1, REGION, List.of(action))), List.of(), List.of());
		FunctionCallCPInstruction call = new FunctionCallCPInstruction(DMLProgram.DEFAULT_NAMESPACE, "f", true,
			new CPOperand[0], null, List.of(), List.of("A"), SNAPSHOT,
			Map.of(0, 72L), Map.of(0, 170L), "function return test");
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(root, SNAPSHOT, schedule)) {
			call.processInstruction(root);
			assertSame(returned.get(), root.getVariable("A"));
			assertNotSame(returned.get(), PlannedSupplyRuntime.materialize(root, action, returned.get(),
				(input, recipe) -> uploaded("bound-return", input, pool(), 703)));
		}
	}

	@Test
	public void functionReturnMetadataRoundTripsThroughInstructionParser() {
		String instruction = InstructionUtils.concatOperands("CP", "fcall", DMLProgram.DEFAULT_NAMESPACE,
			"f", "true", "0", "1", "A", "memo_returns=7:0=72>170");
		FunctionCallCPInstruction parsed = FunctionCallCPInstruction.parseInstruction(instruction);
		assertEquals(SNAPSHOT, parsed.getMemoReturnSnapshot());
		assertEquals(Map.of(0, 72L), parsed.getMemoReturnDefinitions());
		assertEquals(Map.of(0, 170L), parsed.getMemoReturnTargets());
	}

	@Test
	public void guardedFunctionReturnCandidatesRoundTripThroughInstructionParser() {
		String instruction = InstructionUtils.concatOperands("CP", "fcall", DMLProgram.DEFAULT_NAMESPACE,
			"f", "true", "0", "2", "A", "B", "memo_returns=7:0=70|72>170,1=71|73>171");
		FunctionCallCPInstruction parsed = FunctionCallCPInstruction.parseInstruction(instruction);
		assertEquals(SNAPSHOT, parsed.getMemoReturnSnapshot());
		assertThrows(DMLRuntimeException.class, parsed::getMemoReturnDefinitions);
		assertEquals(Map.of(0, List.of(70L, 72L), 1, List.of(71L, 73L)),
			parsed.getMemoReturnDefinitionCandidates());
		assertEquals(Map.of(0, 170L, 1, 171L), parsed.getMemoReturnTargets());
	}

	@Test
	public void rejectedFunctionReturnLeavesCallerBindingLineageAndOldDataUntouched() {
		boolean oldLineage = DMLScript.LINEAGE;
		DMLScript.LINEAGE = true;
		try {
			for(boolean ambiguous : new boolean[] {false, true}) {
				Program program = new Program();
				DataIdentifier output = new DataIdentifier("Y", DataType.MATRIX, ValueType.FP64);
				FunctionProgramBlock function = new FunctionProgramBlock(program, List.of(), List.of(output)) {
					@Override public void execute(ExecutionContext ec) {
						MatrixObject result = localMatrix("rejected-return", 2, 2);
						ec.setVariable("Y", result);
						ec.getLineage().set("Y", new LineageItem("new-return"));
						if(ambiguous) {
							PlannedSupplyRuntime.markDefinition(ec, 70, result);
							PlannedSupplyRuntime.markDefinition(ec, 71, result);
						}
					}
				};
				program.addFunctionProgramBlock(DMLProgram.DEFAULT_NAMESPACE, "f", function, true);
				ExecutionContext root = ExecutionContextFactory.createContext(program);
				MatrixObject old = localMatrix("old-caller", 2, 2);
				LineageItem oldItem = new LineageItem("old-lineage");
				root.setVariable("A", old);
				root.getLineage().set("A", oldItem);
				String instruction = InstructionUtils.concatOperands("CP", "fcall", DMLProgram.DEFAULT_NAMESPACE,
					"f", "true", "0", "1", "A", "memo_returns=7:0=70|71>170");
				FunctionCallCPInstruction call = FunctionCallCPInstruction.parseInstruction(instruction);
				try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(
					root, SNAPSHOT, SupplySchedule.empty())) {
					assertThrows(DMLRuntimeException.class, () -> call.processInstruction(root));
					assertSame(old, root.getVariable("A"));
					assertSame(oldItem, root.getLineage().get("A"));
					assertTrue("old caller data must not be cleaned on rejected export", old.isCached(true));
				}
			}
		}
		finally {
			DMLScript.LINEAGE = oldLineage;
		}
	}

	@Test
	public void duplicateCallerOutputNamesPreserveSequentialBindingAndCleanupOnce() {
		Program program = new Program();
		DataIdentifier firstOutput = new DataIdentifier("Y", DataType.MATRIX, ValueType.FP64);
		DataIdentifier secondOutput = new DataIdentifier("Z", DataType.MATRIX, ValueType.FP64);
		AtomicReference<MatrixObject> secondReturned = new AtomicReference<>();
		FunctionProgramBlock function = new FunctionProgramBlock(program, List.of(),
			List.of(firstOutput, secondOutput)) {
			@Override public void execute(ExecutionContext ec) {
				MatrixObject first = localMatrix("first-return", 2, 2);
				MatrixObject second = localMatrix("second-return", 2, 2);
				second.acquireModify(new MatrixBlock(2, 2, 3d));
				second.release();
				secondReturned.set(second);
				ec.setVariable("Y", first);
				ec.setVariable("Z", second);
				PlannedSupplyRuntime.markDefinition(ec, 70, first);
				PlannedSupplyRuntime.markDefinition(ec, 71, second);
			}
		};
		program.addFunctionProgramBlock(DMLProgram.DEFAULT_NAMESPACE, "f", function, true);
		ExecutionContext root = ExecutionContextFactory.createContext(program);
		TrackingMatrixObject old = new TrackingMatrixObject("old-caller", 2, 2);
		root.setVariable("A", old);
		old.clearCalls = 0; // MatrixObject construction may normalize its initial in-memory state.
		FunctionCallCPInstruction call = new FunctionCallCPInstruction(DMLProgram.DEFAULT_NAMESPACE, "f", true,
			new CPOperand[0], null, List.of(), List.of("A", "A"), SNAPSHOT,
			Map.of(0, 70L, 1, 71L), Map.of(0, 170L, 1, 171L), "duplicate outputs");
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(
			root, SNAPSHOT, SupplySchedule.empty())) {
			call.processInstruction(root);
			assertSame(secondReturned.get(), root.getVariable("A"));
			assertEquals(12d, secondReturned.get().acquireReadAndRelease().sum(), 0d);
			assertEquals("the pre-call object is cleaned exactly once", 1, old.clearCalls);
		}
	}

	@Test
	public void guardedFunctionReturnCandidatesRequireExactlyOneLocalMarker() {
		ExecutionContext root = context();
		ExecutionContext selected = context();
		ExecutionContext missing = context();
		ExecutionContext ambiguous = context();
		MatrixObject value = localMatrix("return", 2, 2);
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(
			root, SNAPSHOT, SupplySchedule.empty())) {
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, selected)) {
				PlannedSupplyRuntime.markDefinition(selected, 70, value);
				PlannedSupplyRuntime.exportFunctionReturns(root, selected, SNAPSHOT,
					Map.of(0, List.of(70L, 71L)), Map.of(0, 170L), Map.of(0, value));
			}
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, missing)) {
				assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.exportFunctionReturns(
					root, missing, SNAPSHOT, Map.of(0, List.of(70L, 71L)), Map.of(0, 171L), Map.of(0, value)));
			}
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, ambiguous)) {
				PlannedSupplyRuntime.markDefinition(ambiguous, 70, value);
				PlannedSupplyRuntime.markDefinition(ambiguous, 71, value);
				assertThrows(DMLRuntimeException.class, () -> PlannedSupplyRuntime.exportFunctionReturns(
					root, ambiguous, SNAPSHOT, Map.of(0, List.of(70L, 71L)),
					Map.of(0, 172L), Map.of(0, value)));
			}
		}
	}

	@Test
	public void repeatedCallsRemapOneCalleeOriginToDistinctCallerVersions() {
		ExecutionContext root = context();
		ExecutionContext firstCall = context();
		ExecutionContext secondCall = context();
		MatrixObject first = localMatrix("first-call", 2, 2);
		MatrixObject second = localMatrix("second-call", 2, 2);
		CanonicalWorkerPool pool = pool();
		PlannedSupplyStore.Action firstAction = uploadAction(pool, 170);
		PlannedSupplyStore.Action secondAction = uploadAction(pool, 171);
		SupplySchedule schedule = new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.PROGRAM_ROOT, -1, REGION, List.of(firstAction, secondAction))),
			List.of(), List.of());
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(root, SNAPSHOT, schedule)) {
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, firstCall)) {
				PlannedSupplyRuntime.markDefinition(firstCall, 72, first);
				PlannedSupplyRuntime.exportFunctionReturns(root, firstCall, SNAPSHOT,
					Map.of(0, 72L), Map.of(0, 170L), Map.of(0, first));
			}
			try(PlannedSupplyRuntime.Attachment attached =
				PlannedSupplyRuntime.attachFunctionContext(root, secondCall)) {
				PlannedSupplyRuntime.markDefinition(secondCall, 72, second);
				PlannedSupplyRuntime.exportFunctionReturns(root, secondCall, SNAPSHOT,
					Map.of(0, 72L), Map.of(0, 171L), Map.of(0, second));
			}
			CacheableData<?> firstMaterialized = PlannedSupplyRuntime.materialize(root, firstAction, first,
				(input, recipe) -> uploaded("first-version", input, pool, 704));
			assertNotSame(first, firstMaterialized);
			assertNotSame(second, PlannedSupplyRuntime.materialize(root, secondAction, second,
				(input, recipe) -> uploaded("second-version", input, pool, 705)));
			assertSame(firstMaterialized, PlannedSupplyRuntime.materialize(root, firstAction, first,
				(input, recipe) -> { throw new AssertionError("the earlier caller version must stay cached"); }));
		}
	}

	@Test
	public void nestedFunctionReturnsChainExactTargetOrigins() {
		ExecutionContext root = context();
		ExecutionContext middle = context();
		ExecutionContext inner = context();
		MatrixObject value = localMatrix("nested", 2, 2);
		PlannedSupplyStore.Action action = uploadAction(pool(), 270);
		SupplySchedule schedule = new SupplySchedule(List.of(
			new ScopePlan(ScopeKind.PROGRAM_ROOT, -1, REGION, List.of(action))), List.of(), List.of());
		try(PlannedSupplyRuntime.Execution execution = PlannedSupplyRuntime.open(root, SNAPSHOT, schedule);
			PlannedSupplyRuntime.Attachment middleAttachment =
				PlannedSupplyRuntime.attachFunctionContext(root, middle);
			PlannedSupplyRuntime.Attachment innerAttachment =
				PlannedSupplyRuntime.attachFunctionContext(middle, inner)) {
			PlannedSupplyRuntime.markDefinition(inner, 72, value);
			PlannedSupplyRuntime.exportFunctionReturns(middle, inner, SNAPSHOT,
				Map.of(0, 72L), Map.of(0, 170L), Map.of(0, value));
			PlannedSupplyRuntime.exportFunctionReturns(root, middle, SNAPSHOT,
				Map.of(0, 170L), Map.of(0, 270L), Map.of(0, value));
			assertNotSame(value, PlannedSupplyRuntime.materialize(root, action, value,
				(input, recipe) -> uploaded("nested-version", input, pool(), 706)));
		}
	}

	private static PlannedSupplyStore.Action uploadAction(CanonicalWorkerPool pool, long origin) {
		return new PlannedSupplyStore.Action(SNAPSHOT, origin, MemoPartition.ROW,
			new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode()), REGION, "canonical-upload");
	}

	private static String plannedSupplyInstruction(Recipe recipe,List<PlannedSupplyStore.Action> actions) {
		return InstructionUtils.concatOperands("FED","fed_supply",
			InstructionUtils.concatOperandParts("guarded-source",DataType.MATRIX.name(),ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("guarded-output",DataType.MATRIX.name(),ValueType.FP64.name()),
			MemoPartition.ROW.name(),InstructionUtils.createLiteralOperand(
				org.apache.sysds.lops.FederatedSupply.encodeRecipes(List.of(recipe)),ValueType.STRING),
			InstructionUtils.createLiteralOperand(
				org.apache.sysds.lops.FederatedSupply.encodeActionCandidates(Map.of(0,actions)),ValueType.STRING));
	}

	private static ExecutionContext context() {
		return new ExecutionContext(new LocalVariableMap());
	}

	private static CanonicalWorkerPool pool() {
		return CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("worker-a", 1234), new InetSocketAddress("worker-b", 2345)));
	}

	private static MatrixObject localMatrix(String name, int rows, int columns) {
		MatrixBlock block = new MatrixBlock(rows, columns, 1);
		return new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(rows, columns, 1024, rows * columns),
				FileFormat.BINARY), block);
	}

	private static final class TrackingMatrixObject extends MatrixObject {
		private int clearCalls;
		private TrackingMatrixObject(String name, int rows, int columns) {
			super(ValueType.FP64, name,
				new MetaDataFormat(new MatrixCharacteristics(rows, columns, 1024, rows * columns),
					FileFormat.BINARY), new MatrixBlock(rows, columns, 1));
		}
		@Override public synchronized void clearData(long tid) {
			clearCalls++;
			super.clearData(tid);
		}
	}

	private static MatrixObject uploaded(String name, CacheableData<?> input,
		CanonicalWorkerPool pool, long id) {
		MatrixObject output = federatedMatrix(name, pool, input.getNumRows(), input.getNumColumns(), id);
		output.enableCleanup(false); // no workers exist in this unit fixture
		return output;
	}

	private static MatrixObject federatedMatrix(String name, CanonicalWorkerPool pool,
		long rows, long columns, long id) {
		MatrixObject output = new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(rows, columns, 1024, rows * columns),
				FileFormat.BINARY));
		output.setFedMapping(pool.newMap(DataType.MATRIX, id, FType.ROW, rows, columns));
		return output;
	}
}

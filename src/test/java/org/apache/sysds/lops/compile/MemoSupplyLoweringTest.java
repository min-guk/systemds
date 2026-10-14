/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.lops.compile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.DefinitionSite;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ReturnDefinitionSite;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FederatedFoutMaterialize;
import org.apache.sysds.lops.FederatedSupply;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.LopsException;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

public class MemoSupplyLoweringTest {
	@After
	public void clearRegistry() {
		MemoSupplyRegistry.clear();
	}

	@Test
	public void sharedCollectIsMaterializedOnceAndRewiredToExactConsumers() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("fed", 101, ExecType.FED, FederatedOutput.FOUT);
		UnaryCP first = consumer(producer, 201);
		UnaryCP second = consumer(producer, 202);
		Hop logicalProducer = logical(101, producer, List.of());
		Hop logicalFirst = logical(201, first, List.of(logicalProducer));
		Hop logicalSecond = logical(202, second, List.of(logicalProducer));
		String pool = pool();
		MemoSupplyRegistry.register(block.getSBID(), List.of(
			new Supply(101, 201, 0, MemoPartition.LOCAL,
				List.of(new Recipe(MemoPartition.ROW, Movement.COLLECT, pool))),
			new Supply(101, 202, 0, MemoPartition.LOCAL,
				List.of(new Recipe(MemoPartition.ROW, Movement.COLLECT, pool)))));

		List<Lop> inserted = MemoSupplyLowering.apply(
			new ArrayList<>(List.of(producer, first, second)), block, List.of(logicalFirst, logicalSecond));

		assertEquals(1, inserted.size());
		assertTrue(inserted.get(0) instanceof UnaryCP);
		assertEquals(OpOp1.PREFETCH.toString(), ((UnaryCP) inserted.get(0)).getOpCode());
		assertSame(inserted.get(0), first.getInput(0));
		assertSame(inserted.get(0), second.getInput(0));
		assertTrue(inserted.get(0).getPlannerSyntheticActionKey().startsWith("memo-supply|"));
	}

	@Test
	public void uploadUsesCanonicalPoolMaterializerAndExactPartition() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("local", 111, ExecType.CP, FederatedOutput.LOUT);
		UnaryCP consumer = consumer(producer, 211);
		Hop logicalProducer = logical(111, producer, List.of());
		Hop logicalConsumer = logical(211, consumer, List.of(logicalProducer));
		String pool = pool();
		MemoSupplyRegistry.register(block.getSBID(), List.of(
			new Supply(111, 211, 0, MemoPartition.ROW,
				List.of(new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool)))));

		Lop inserted = MemoSupplyLowering.apply(new ArrayList<>(List.of(producer, consumer)),
			block, List.of(logicalConsumer)).get(0);

		assertTrue(inserted instanceof FederatedFoutMaterialize);
		assertTrue(((FederatedFoutMaterialize) inserted).usesCanonicalWorkerPool());
		assertEquals(FederatedOutput.FOUT, inserted.getFederatedOutput());
		assertSame(inserted, consumer.getInput(0));
	}

	@Test
	public void guardedBranchLayoutsUseOneExplicitRuntimeSupply() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("branch-value", 116, ExecType.CP, FederatedOutput.NONE);
		UnaryCP consumer = consumer(producer, 216);
		Hop logicalProducer = logical(116, producer, List.of());
		Hop logicalConsumer = logical(216, consumer, List.of(logicalProducer));
		MemoSupplyRegistry.register(block.getSBID(), List.of(new Supply(116, 216, 0,
			MemoPartition.LOCAL, List.of(
				new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null),
				new Recipe(MemoPartition.ROW, Movement.COLLECT, pool())))));

		Lop inserted = MemoSupplyLowering.apply(new ArrayList<>(List.of(producer, consumer)),
			block, List.of(logicalConsumer)).get(0);

		assertTrue(inserted instanceof FederatedSupply);
		assertSame(inserted, consumer.getInput(0));
		assertEquals(2, FederatedSupply.decodeRecipes(
			FederatedSupply.encodeRecipes(MemoSupplyRegistry.snapshot(block.getSBID()).get(0).recipes())).size());
	}

	@Test
	public void staleBindingFailsBeforeAnyEdgeIsMutated() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("fed", 121, ExecType.FED, FederatedOutput.FOUT);
		UnaryCP valid = consumer(producer, 221);
		UnaryCP stale = consumer(producer, 222);
		Hop logicalProducer = logical(121, producer, List.of());
		Hop logicalValid = logical(221, valid, List.of(logicalProducer));
		Hop logicalStale = logical(222, stale, List.of(logicalProducer));
		Recipe collect = new Recipe(MemoPartition.ROW, Movement.COLLECT, pool());
		MemoSupplyRegistry.register(block.getSBID(), List.of(
			new Supply(121, 221, 0, MemoPartition.LOCAL, List.of(collect)),
			new Supply(121, 222, 1, MemoPartition.LOCAL, List.of(collect))));

		assertThrows(LopsException.class, () -> MemoSupplyLowering.apply(
			new ArrayList<>(List.of(producer, valid, stale)), block, List.of(logicalValid, logicalStale)));
		assertSame(producer, valid.getInput(0));
		assertSame(producer, stale.getInput(0));
	}

	@Test
	public void staleDefinitionOrSupplyCannotPartiallyReplaceFunctionReturnMetadata() {
		assertRejectedPlanKeepsFunctionMetadata(true);
		assertRejectedPlanKeepsFunctionMetadata(false);
	}

	@Test
	public void invalidSecondCallCannotPublishFirstCallMetadata() {
		StatementBlock block = new StatementBlock();
		FunctionCallCP first = functionCall(321, "A");
		FunctionCallCP second = functionCall(322, "B");
		first.setMemoReturnDefinitions(5, Map.of(0, 9L), Map.of(0, 19L));
		second.setMemoReturnDefinitions(5, Map.of(0, 10L), Map.of(0, 20L));
		String firstBefore = first.getInstructions(new String[0], new String[] {"A"});
		String secondBefore = second.getInstructions(new String[0], new String[] {"B"});
		Hop firstHop = logical(321, first, List.of());
		Hop secondHop = logical(322, second, List.of());
		try(MockedStatic<MemoPlanRegistry> registry = mockStatic(MemoPlanRegistry.class)) {
			registry.when(() -> MemoPlanRegistry.hasPlan(block.getDMLProg())).thenReturn(true);
			registry.when(() -> MemoPlanRegistry.definitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of());
			registry.when(() -> MemoPlanRegistry.returnDefinitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of(
					new ReturnDefinitionSite(block.getSBID(), 321, 0, 72, 170),
					new ReturnDefinitionSite(block.getSBID(), 322, 1, 73, 171)));
			registry.when(() -> MemoPlanRegistry.epoch(block.getDMLProg())).thenReturn(7L);
			assertThrows(LopsException.class, () -> MemoSupplyLowering.apply(
				new ArrayList<>(List.of(first, second)), block, List.of(firstHop, secondHop), List.of()));
		}
		assertEquals(firstBefore, first.getInstructions(new String[0], new String[] {"A"}));
		assertEquals(secondBefore, second.getInstructions(new String[0], new String[] {"B"}));
	}

	@Test
	public void negativeDefinitionOriginCannotPublishValidatedReturnMetadata() {
		StatementBlock block = new StatementBlock();
		FunctionCallCP call = functionCall(331, "A");
		call.setMemoReturnDefinitions(5, Map.of(0, 9L), Map.of(0, 19L));
		String before = call.getInstructions(new String[0], new String[] {"A"});
		Data producer = matrixData("source", 332, ExecType.CP, FederatedOutput.LOUT);
		Hop callHop = logical(331, call, List.of());
		Hop producerHop = logical(332, producer, List.of());
		try(MockedStatic<MemoPlanRegistry> registry = mockStatic(MemoPlanRegistry.class)) {
			registry.when(() -> MemoPlanRegistry.hasPlan(block.getDMLProg())).thenReturn(true);
			registry.when(() -> MemoPlanRegistry.returnDefinitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of(new ReturnDefinitionSite(block.getSBID(), 331, 0, 72, 170)));
			registry.when(() -> MemoPlanRegistry.definitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of(new DefinitionSite(block.getSBID(), 332, -1)));
			registry.when(() -> MemoPlanRegistry.epoch(block.getDMLProg())).thenReturn(7L);
			assertThrows(LopsException.class, () -> MemoSupplyLowering.apply(
				new ArrayList<>(List.of(call, producer)), block, List.of(callHop, producerHop), List.of()));
		}
		assertEquals(before, call.getInstructions(new String[0], new String[] {"A"}));
	}

	private static void assertRejectedPlanKeepsFunctionMetadata(boolean staleDefinition) {
		StatementBlock block = new StatementBlock();
		FunctionCallCP call = new FunctionCallCP(new ArrayList<>(), DMLProgram.DEFAULT_NAMESPACE,
			"f", new String[0], new String[] {"A"}, true, ExecType.CP);
		call.setHopID(301);
		call.setMemoReturnDefinitions(5, Map.of(0, 9L), Map.of(0, 19L));
		String original = call.getInstructions(new String[0], new String[] {"A"});
		Data producer = matrixData("source", 302, ExecType.CP, FederatedOutput.LOUT);
		UnaryCP consumer = consumer(producer, 303);
		Hop logicalCall = logical(301, call, List.of());
		Hop logicalProducer = logical(302, producer, List.of());
		Hop logicalConsumer = logical(303, consumer, List.of(logicalProducer));
		List<Supply> supplies = staleDefinition ? List.of() : List.of(new Supply(302, 303, 1,
			MemoPartition.LOCAL, List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null))));
		try(MockedStatic<MemoPlanRegistry> registry = mockStatic(MemoPlanRegistry.class)) {
			registry.when(() -> MemoPlanRegistry.hasPlan(block.getDMLProg())).thenReturn(true);
			registry.when(() -> MemoPlanRegistry.returnDefinitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of(new ReturnDefinitionSite(block.getSBID(), 301, 0, 72, 170)));
			registry.when(() -> MemoPlanRegistry.definitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(staleDefinition ? List.of(new DefinitionSite(block.getSBID(), 999, 44)) : List.of());
			registry.when(() -> MemoPlanRegistry.epoch(block.getDMLProg())).thenReturn(7L);
			assertThrows(LopsException.class, () -> MemoSupplyLowering.apply(
				new ArrayList<>(List.of(call, producer, consumer)), block,
				List.of(logicalCall, logicalConsumer), supplies));
		}
		assertEquals(original, call.getInstructions(new String[0], new String[] {"A"}));
		assertSame(producer, consumer.getInput(0));
	}

	private static FunctionCallCP functionCall(long hopId, String output) {
		FunctionCallCP call = new FunctionCallCP(new ArrayList<>(), DMLProgram.DEFAULT_NAMESPACE,
			"f", new String[0], new String[] {output}, true, ExecType.CP);
		call.setHopID(hopId);
		return call;
	}

	@Test
	public void definitionMarkerDoesNotRewireConsumerOutsideExactPhysicalDag() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("source", 311, ExecType.CP, FederatedOutput.LOUT);
		UnaryCP inside = consumer(producer, 312);
		UnaryCP outside = consumer(producer, 313);
		Hop logicalProducer = logical(311, producer, List.of());
		Hop logicalInside = logical(312, inside, List.of(logicalProducer));
		try(MockedStatic<MemoPlanRegistry> registry = mockStatic(MemoPlanRegistry.class)) {
			registry.when(() -> MemoPlanRegistry.hasPlan(block.getDMLProg())).thenReturn(true);
			registry.when(() -> MemoPlanRegistry.returnDefinitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of());
			registry.when(() -> MemoPlanRegistry.definitions(block.getDMLProg(), block.getSBID()))
				.thenReturn(List.of(new DefinitionSite(block.getSBID(), 311, 44)));
			Lop marker = MemoSupplyLowering.apply(new ArrayList<>(List.of(producer, inside)), block,
				List.of(logicalInside), List.of()).get(0);
			assertSame(marker, inside.getInput(0));
			assertSame(producer, outside.getInput(0));
		}
	}

	@Test
	public void staticSourceLayoutMustMatchPhysicalProducer() {
		StatementBlock block = new StatementBlock();
		Data producer = matrixData("local", 126, ExecType.CP, FederatedOutput.LOUT);
		UnaryCP consumer = consumer(producer, 226);
		Hop logicalProducer = logical(126, producer, List.of());
		Hop logicalConsumer = logical(226, consumer, List.of(logicalProducer));
		MemoSupplyRegistry.register(block.getSBID(), List.of(new Supply(126, 226, 0,
			MemoPartition.LOCAL, List.of(new Recipe(MemoPartition.ROW, Movement.COLLECT, pool())))));

		assertThrows(LopsException.class, () -> MemoSupplyLowering.apply(
			new ArrayList<>(List.of(producer, consumer)), block, List.of(logicalConsumer)));
		assertSame(producer, consumer.getInput(0));
	}

	@Test
	public void registeredPlanCannotBeReplacedAndDuplicateEdgeIsRejected() {
		StatementBlock block = new StatementBlock();
		Supply collect = new Supply(1, 2, 0, MemoPartition.LOCAL,
			List.of(new Recipe(MemoPartition.ROW, Movement.COLLECT, pool())));
		MemoSupplyRegistry.register(block.getSBID(), List.of(collect));
		MemoSupplyRegistry.register(block.getSBID(), List.of(collect));
		assertThrows(IllegalStateException.class, () -> MemoSupplyRegistry.register(
			block.getSBID(), List.of(new Supply(1, 3, 0, MemoPartition.LOCAL, collect.recipes()))));
		assertThrows(IllegalArgumentException.class, () -> MemoSupplyRegistry.register(
			new StatementBlock().getSBID(), List.of(collect, collect)));
	}

	@Test
	public void wholePlanReplacementValidatesBeforeAtomicPublication() {
		Supply first = new Supply(1, 2, 0, MemoPartition.LOCAL,
			List.of(new Recipe(MemoPartition.ROW, Movement.COLLECT, pool())));
		MemoSupplyRegistry.replaceAll(Map.of(10L, List.of(first)));
		Map<Long, List<Supply>> before = MemoSupplyRegistry.snapshotAll();
		assertThrows(IllegalArgumentException.class, () -> MemoSupplyRegistry.replaceAll(Map.of(
			20L, List.of(new Supply(3, 4, 0, MemoPartition.LOCAL, first.recipes())),
			30L, List.of(first, first))));
		assertEquals(before, MemoSupplyRegistry.snapshotAll());

		Supply second = new Supply(5, 6, 0, MemoPartition.ROW,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool())));
		MemoSupplyRegistry.replaceAll(Map.of(40L, List.of(second)));
		assertEquals(Map.of(40L, List.of(second)), MemoSupplyRegistry.snapshotAll());
		assertThrows(UnsupportedOperationException.class,
			() -> MemoSupplyRegistry.snapshotAll().put(41L, List.of(first)));
	}

	@Test
	public void plannedActionsRoundTripByExactRecipeIndex() {
		List<Recipe> recipes = List.of(
			new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null),
			new Recipe(MemoPartition.ROW, Movement.COLLECT, pool()));
		Action action = new Action(7, 31, MemoPartition.LOCAL, recipes.get(1), 19, "guarded collect/v1");
		Map<Integer, Action> expected = Map.of(1, action);
		assertEquals(expected, FederatedSupply.decodeActions(
			FederatedSupply.encodeActions(expected), recipes));
		assertThrows(LopsException.class, () -> FederatedSupply.decodeActions("0,7,31,LOCAL,19,eA", recipes));
	}

	private static Data matrixData(String name, long hopId, ExecType execType,
		FederatedOutput output) {
		Data lop = new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		lop.setHopID(hopId);
		lop.setExecType(execType);
		lop.setFederatedOutput(output);
		lop.getOutputParameters().setDimensions(4, 3, 1024, 12);
		return lop;
	}

	private static UnaryCP consumer(Lop producer, long hopId) {
		UnaryCP consumer = new UnaryCP(producer, OpOp1.ABS,
			DataType.MATRIX, ValueType.FP64, ExecType.CP);
		consumer.setHopID(hopId);
		return consumer;
	}

	private static Hop logical(long hopId, Lop lop, List<Hop> inputs) {
		Hop hop = mock(Hop.class);
		when(hop.getHopID()).thenReturn(hopId);
		when(hop.getLops()).thenReturn(lop);
		when(hop.getInput()).thenReturn(new ArrayList<>(inputs));
		return hop;
	}

	private static String pool() {
		return CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("worker-a", 1234), new InetSocketAddress("worker-b", 2345))).encode();
	}
}

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

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FederatedFoutMaterialize;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.ConsumerInputSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

public class FederatedDagLocalMaterializeTest {
	@After
	public void clearRegistry() {
		FederatedLocalMaterializeRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	@Test
	public void testExplicitLocalMaterializePrefetchIsNotWrappedAgain() throws Exception {
		Lop producer = mock(Lop.class);
		when(producer.prefetchActivated()).thenReturn(true);
		when(producer.getFederatedOutput()).thenReturn(FederatedOutput.FOUT);

		UnaryCP localMaterialize = new UnaryCP(producer, OpOp1.PREFETCH,
			DataType.MATRIX, ValueType.FP64, ExecType.CP);
		UnaryCP ordinaryLocalConsumer = new UnaryCP(producer, OpOp1.ABS,
			DataType.MATRIX, ValueType.FP64, ExecType.CP);

		Method inputNeedsPrefetch = Dag.class.getDeclaredMethod("inputNeedsPrefetch", Lop.class, Lop.class);
		inputNeedsPrefetch.setAccessible(true);
		Dag<Lop> dag = new Dag<>();

		assertTrue("Ordinary local consumers still require automatic prefetch",
			(boolean) inputNeedsPrefetch.invoke(dag, producer, ordinaryLocalConsumer));
		assertFalse("An explicit local materialization must not be wrapped in another prefetch",
			(boolean) inputNeedsPrefetch.invoke(dag, producer, localMaterialize));
	}

	@Test
	public void exactAbsentLocalInputOfFederatedConsumerIsRewired() throws Exception {
		Data producer = matrixData("fed", 101L, ExecType.FED, FederatedOutput.FOUT);
		Data unrelated = matrixData("local", 102L, ExecType.CP, FederatedOutput.LOUT);
		FunctionCallCP consumer = new FunctionCallCP(new ArrayList<>(List.of(unrelated, producer)),
			DMLProgram.INTERNAL_NAMESPACE, "mock", new String[] {"left", "right"},
			new String[] {"out"}, false, ExecType.FED);
		consumer.setHopID(201L);
		consumer.setFederatedOutput(FederatedOutput.LOUT);
		List<Lop> lops = new ArrayList<>(List.of(producer, unrelated, consumer));
		FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, producer.getHopID(),
			List.of(new ConsumerInputSpec(consumer.getHopID(), 1)), "ROW", "selected-absent-local");

		Method insert = Dag.class.getDeclaredMethod("insertLocalMaterializeLops",
			List.class, StatementBlock.class, List.class);
		insert.setAccessible(true);
		assertTrue((boolean) insert.invoke(new Dag<>(), lops, null, null));

		assertSame("unselected input must remain unchanged", unrelated, consumer.getInput(0));
		assertTrue("exact ABSENT_LOCAL input must consume an explicit CP prefetch",
			consumer.getInput(1) instanceof UnaryCP);
		UnaryCP materialize = (UnaryCP) consumer.getInput(1);
		assertSame(producer, materialize.getInput(0));
		assertSame("FED consumer must receive the selected local input", materialize, consumer.getInput(1));
	}

	@Test
	public void derivedFoutRewiresOnlyExactPresentConsumerInput() throws Exception {
		Data localResult = matrixData("local-result", 301L, ExecType.FED, FederatedOutput.LOUT);
		Data anchor = matrixData("anchor", 302L, ExecType.FED, FederatedOutput.FOUT);
		Data unrelated = matrixData("unrelated", 303L, ExecType.CP, FederatedOutput.LOUT);
		FunctionCallCP presentConsumer = new FunctionCallCP(
			new ArrayList<>(List.of(unrelated, localResult)), DMLProgram.INTERNAL_NAMESPACE, "present",
			new String[] {"left", "right"}, new String[] {"out"}, false, ExecType.FED);
		presentConsumer.setHopID(401L);
		presentConsumer.setFederatedOutput(FederatedOutput.LOUT);
		FunctionCallCP absentLocalConsumer = new FunctionCallCP(
			new ArrayList<>(List.of(localResult, unrelated)), DMLProgram.INTERNAL_NAMESPACE, "absent",
			new String[] {"left", "right"}, new String[] {"out"}, false, ExecType.FED);
		absentLocalConsumer.setHopID(402L);
		absentLocalConsumer.setFederatedOutput(FederatedOutput.LOUT);
		List<Lop> lops = new ArrayList<>(List.of(localResult, anchor, unrelated,
			presentConsumer, absentLocalConsumer));
		FederatedFoutMaterializeRegistry.registerConsumerInputs(-1L, localResult.getHopID(),
			anchor.getHopID(), "ROW", "anchor", "anchor-key",
			List.of(new FederatedRefedRegistry.ConsumerInputSpec(presentConsumer.getHopID(), 1)));

		Method insert = Dag.class.getDeclaredMethod("insertFoutMaterializeLops",
			List.class, StatementBlock.class, List.class);
		insert.setAccessible(true);
		assertTrue((boolean) insert.invoke(new Dag<>(), lops, null, null));

		assertTrue("selected PRESENT edge must consume the derived FOUT",
			presentConsumer.getInput(1) instanceof FederatedFoutMaterialize);
		FederatedFoutMaterialize materialize =
			(FederatedFoutMaterialize) presentConsumer.getInput(1);
		assertSame(localResult, materialize.getInput(0));
		assertSame("unselected input must remain unchanged", unrelated, presentConsumer.getInput(0));
		assertSame("ABSENT_LOCAL edge must retain the producer's existing local result",
			localResult, absentLocalConsumer.getInput(0));
		assertSame("unrelated ABSENT_LOCAL consumer input must remain unchanged",
			unrelated, absentLocalConsumer.getInput(1));
	}

	private static Data matrixData(String name, long hopId, ExecType execType,
		FederatedOutput output) {
		Data lop = new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		lop.setHopID(hopId);
		lop.setExecType(execType);
		lop.setFederatedOutput(output);
		return lop;
	}
}

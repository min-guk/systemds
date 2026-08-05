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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.LopsException;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

public class FederatedDagExactRefedInputProjectionTest {
	@After
	public void clearRegistry() {
		FederatedRefedRegistry.clear();
	}

	@Test
	public void exactLogicalInputProjectsThroughDeterministicLopReorder() throws Exception {
		Fixture fixture = fixture(false, true);
		FederatedRefedRegistry.registerConsumerInputs(-1L, fixture.localHop.getHopID(), -1L,
			"fedinit://pool|ROW", FType.ROW,
			List.of(new ConsumerInputSpec(fixture.consumerHop.getHopID(), 0)));

		assertTrue(invokeInsertRefedLops(fixture));
		assertSame("The unrelated physical input must remain in position zero",
			fixture.otherLop, fixture.consumerLop.getInput(0));
		assertTrue("The reordered physical occurrence of the exact logical source must be rewired",
			fixture.consumerLop.getInput(1) instanceof org.apache.sysds.lops.FederatedRefed);
	}

	@Test
	public void duplicateLogicalSourceSubsetFailsClosed() throws Exception {
		Fixture fixture = fixture(true, false);
		FederatedRefedRegistry.registerConsumerInputs(-1L, fixture.localHop.getHopID(), -1L,
			"fedinit://pool|ROW", FType.ROW,
			List.of(new ConsumerInputSpec(fixture.consumerHop.getHopID(), 0)));

		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
			() -> invokeInsertRefedLops(fixture));
		assertTrue(failure.getCause() instanceof LopsException);
		assertEquals("Ambiguous subset failure must not mutate either physical edge", 2,
			fixture.consumerLop.getInputs().stream().filter(input -> input == fixture.localLop).count());
	}

	@Test
	public void duplicateLogicalSourceCompleteCoverageRewiresAllOccurrences() throws Exception {
		Fixture fixture = fixture(true, false);
		FederatedRefedRegistry.registerConsumerInputs(-1L, fixture.localHop.getHopID(), -1L,
			"fedinit://pool|ROW", FType.ROW,
			List.of(new ConsumerInputSpec(fixture.consumerHop.getHopID(), 0),
				new ConsumerInputSpec(fixture.consumerHop.getHopID(), 1)));

		assertTrue(invokeInsertRefedLops(fixture));
		Lop first = fixture.consumerLop.getInput(0);
		assertTrue(first instanceof org.apache.sysds.lops.FederatedRefed);
		assertSame("All identical occurrences must share the selected REFED value", first,
			fixture.consumerLop.getInput(1));
	}

	@Test
	public void fusedLogicalConsumersSharingOnePhysicalEdgeRewireOnce() throws Exception {
		DataOp localHop = localHop("L");
		DataOp otherHop = localHop("R");
		BinaryOp firstLogicalConsumer = HopRewriteUtils.createBinary(localHop, otherHop, OpOp2.PLUS);
		BinaryOp secondLogicalConsumer = HopRewriteUtils.createBinary(localHop, otherHop, OpOp2.MINUS);
		Data localLop = localLop("L", localHop.getHopID());
		FunctionCallCP fusedPhysicalConsumer = new FunctionCallCP(new ArrayList<>(List.of(localLop)),
			DMLProgram.INTERNAL_NAMESPACE, "mock", new String[] {"X"},
			new String[] {"Out"}, false, ExecType.CP);
		fusedPhysicalConsumer.setHopID(-1L);
		localHop.setLops(localLop);
		firstLogicalConsumer.setLops(fusedPhysicalConsumer);
		secondLogicalConsumer.setLops(fusedPhysicalConsumer);
		List<Lop> lops = new ArrayList<>(List.of(localLop, fusedPhysicalConsumer));

		FederatedRefedRegistry.registerConsumerInputs(-1L, localHop.getHopID(), -1L,
			"fedinit://pool|ROW", FType.ROW,
			List.of(new ConsumerInputSpec(firstLogicalConsumer.getHopID(), 0),
				new ConsumerInputSpec(secondLogicalConsumer.getHopID(), 0)));

		assertTrue(invokeInsertRefedLops(lops, List.of(firstLogicalConsumer, secondLogicalConsumer)));
		assertTrue("The one physical edge shared by both selected logical consumers must be rewired once",
			fusedPhysicalConsumer.getInput(0) instanceof org.apache.sysds.lops.FederatedRefed);
		assertEquals("The fused physical consumer must retain one physical input", 1,
			fusedPhysicalConsumer.getInputs().size());
	}

	private static Fixture fixture(boolean duplicateLocal, boolean reorderPhysicalInputs) {
		DataOp localHop = localHop("L");
		DataOp otherHop = localHop("R");
		BinaryOp consumerHop = HopRewriteUtils.createBinary(localHop,
			duplicateLocal ? localHop : otherHop, OpOp2.PLUS);
		Data localLop = localLop("L", localHop.getHopID());
		Data otherLop = localLop("R", otherHop.getHopID());
		List<Lop> physicalInputs = duplicateLocal ? List.of(localLop, localLop)
			: reorderPhysicalInputs ? List.of(otherLop, localLop) : List.of(localLop, otherLop);
		FunctionCallCP consumerLop = new FunctionCallCP(new ArrayList<>(physicalInputs),
			DMLProgram.INTERNAL_NAMESPACE, "mock", new String[] {"X", "Y"},
			new String[] {"Out"}, false, ExecType.CP);
		consumerLop.setHopID(consumerHop.getHopID());
		localHop.setLops(localLop);
		otherHop.setLops(otherLop);
		consumerHop.setLops(consumerLop);
		List<Lop> lops = new ArrayList<>(List.of(localLop, otherLop, consumerLop));
		return new Fixture(localHop, consumerHop, localLop, otherLop, consumerLop, lops);
	}

	private static DataOp localHop(String name) {
		DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, -1, 1000);
		hop.setForcedExecType(ExecType.CP);
		hop.setFederatedOutput(FederatedOutput.LOUT);
		return hop;
	}

	private static Data localLop(String name, long hopId) {
		Data lop = new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		lop.setHopID(hopId);
		lop.setExecType(ExecType.CP);
		lop.setFederatedOutput(FederatedOutput.LOUT);
		return lop;
	}

	private static boolean invokeInsertRefedLops(Fixture fixture) throws Exception {
		return invokeInsertRefedLops(fixture.lops, List.of(fixture.consumerHop));
	}

	private static boolean invokeInsertRefedLops(List<Lop> lops, List<Hop> logicalHopRoots) throws Exception {
		Method insert = Dag.class.getDeclaredMethod("insertRefedLops",
			List.class, StatementBlock.class, List.class);
		insert.setAccessible(true);
		return (boolean) insert.invoke(new Dag<>(), lops, null, logicalHopRoots);
	}

	private record Fixture(DataOp localHop, Hop consumerHop, Data localLop, Data otherLop,
		FunctionCallCP consumerLop, List<Lop> lops) { }
}

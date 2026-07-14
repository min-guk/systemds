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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class FederatedDagLocalMaterializeTest {
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
}

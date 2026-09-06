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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.junit.Assert;
import org.junit.Test;

/** Cardinality contracts for GLM's cbind then row-normalize topology. */
public class SinglePartitionFactsGlmAppendTest {
	@Test
	public void localCompanionAppendAndRowSumRetainTheFullProvider() {
		DataOp x = source("x", "localhost:1234/x");
		UnaryOp exp = new UnaryOp("exp", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, x);
		Hop ones = localMatrix(4, 1);
		BinaryOp probabilities = new BinaryOp("probabilities", DataType.MATRIX,
			ValueType.FP64, OpOp2.CBIND, exp, ones);
		AggUnaryOp rowSums = new AggUnaryOp("rowSums", DataType.MATRIX,
			ValueType.FP64, AggOp.SUM, Direction.Row, probabilities);
		BinaryOp normalized = new BinaryOp("normalized", DataType.MATRIX,
			ValueType.FP64, OpOp2.DIV, probabilities, rowSums);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(x, exp, ones, probabilities, rowSums, normalized), Map.of(), Set.of());

		Assert.assertEquals(Optional.of(true),
			facts.fullInputHint(probabilities, java.util.Arrays.asList(FType.FULL, null)));
		Assert.assertTrue("A native FULL+local append copies the sole FULL provider",
			facts.isSinglePartition(probabilities));
		Assert.assertTrue("Native rowSums copies a single FULL input map",
			facts.isSinglePartition(rowSums));
		Assert.assertEquals(Optional.of(true),
			facts.fullInputHint(normalized, List.of(FType.FULL, FType.FULL)));
		Assert.assertTrue("The normalized output stays on the common FULL provider",
			facts.isSinglePartition(normalized));
	}

	@Test
	public void appendRejectsSelectedFullInputsOnDifferentEndpoints() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp right = source("right", "localhost:1235/right");
		BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, left, right);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(left, right, append), Map.of(), Set.of());
		Assert.assertEquals("Both selected FULL inputs independently have one range;"
			+ " endpoint agreement remains a separate placement proof", Optional.of(true),
			facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
		Assert.assertFalse("Different workers cannot prove one FULL append result",
			facts.isSinglePartition(append));
	}

	@Test
	public void appendRejectsAnUnknownSelectedFullInput() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp unknown = new DataOp("unknown", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "unknown", -1, -1, -1, 1000);
		BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, left, unknown);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(left, unknown, append), Map.of(), Set.of());
		Assert.assertEquals("Every selected FULL input requires its own exact provider",
			Optional.empty(), facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
		Assert.assertTrue("The potential FULL+local row may inherit the known provider;"
			+ " fullInputHint must still reject the row when the unknown input is selected FULL",
			facts.isSinglePartition(append));
	}

	@Test
	public void localCompanionCannotGroundAnAppendCycle() {
		DataOp carried = new DataOp("probabilities", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "probabilities", -1, -1, -1, 1000);
		Hop ones = localMatrix(4, 1);
		BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, carried, ones);
		DataOp write = new DataOp("probabilities", DataType.MATRIX, ValueType.FP64,
			append, OpOpData.TRANSIENTWRITE, "probabilities");
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(carried, ones, append, write), Map.of(carried, List.of(write)), Set.of());
		Assert.assertFalse("An ungrounded append SCC cannot borrow its local companion",
			facts.isSinglePartition(carried));
		Assert.assertFalse(facts.isSinglePartition(append));
	}

	private static Hop localMatrix(long rows, long columns) {
		return HopRewriteUtils.createDataGenOpByVal(new LiteralOp(rows), new LiteralOp(columns),
			null, DataType.MATRIX, ValueType.FP64, 1);
	}

	private static DataOp source(String name, String address) {
		HashMap<String,Hop> params = new HashMap<>();
		params.put(DataExpression.FED_ADDRESSES, list(new LiteralOp(address)));
		params.put(DataExpression.FED_RANGES, list(
			list(new LiteralOp(0L), new LiteralOp(0L)),
			list(new LiteralOp(4L), new LiteralOp(2L))));
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED, params);
	}

	private static NaryOp list(Hop... inputs) {
		return new NaryOp("list", DataType.LIST, ValueType.UNKNOWN, OpOpN.LIST, inputs);
	}
}

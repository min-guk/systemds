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
package org.apache.sysds.hops.fedplanner.memo;

import java.util.Optional;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.PreparationStats;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.Request;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.RequestFactory;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.RequestKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeRequestTest {
	@Test
	public void preparedAndLegacyRequestsHaveIdenticalResultsAndCacheAccounting() {
		ShapeHint hint = shape(8, 4, 8, 4, -1, -1);
		MemoNativeRules legacy = new MemoNativeRules();
		MemoNativeRules.Result legacyFirst = legacy.rules(unary("X"), 3, hint);
		MemoNativeRules.Result legacySecond = legacy.rules(unary("different-source"), 3, hint);

		RequestFactory factory = new RequestFactory();
		Request first = factory.prepare(unary("X"), 3, hint);
		Request equivalent = factory.prepare(unary("different-source"), 3, hint);
		MemoNativeRules prepared = new MemoNativeRules();
		MemoNativeRules.Result preparedFirst = prepared.rules(first);
		MemoNativeRules.Result preparedSecond = prepared.rules(equivalent);

		assertEquivalent(legacyFirst, preparedFirst);
		Assert.assertSame(legacyFirst, legacySecond);
		Assert.assertSame(preparedFirst, preparedSecond);
		Assert.assertEquals(new MemoNativeRules.CacheStats(1, 1, 1), legacy.cacheStats());
		Assert.assertEquals(legacy.cacheStats(), prepared.cacheStats());
		Assert.assertEquals(new PreparationStats(2, 1, 1, 0), factory.statistics());
		Assert.assertSame("equivalent operators reuse the interned immutable string",
			first.key().operator(), equivalent.key().operator());
	}

	@Test
	public void requestKeyIncludesWorkersAndTheCompleteShapeSnapshot() {
		RequestFactory factory = new RequestFactory();
		Request base = factory.prepare(unary("X"), 3,
			shape(8, 4, 8, 4, -1, -1));
		Request equivalent = factory.prepare(unary("Y"), 3,
			shape(8, 4, 8, 4, -1, -1));
		Request workers = factory.prepare(unary("X"), 4,
			shape(8, 4, 8, 4, -1, -1));
		Request unknown = factory.prepare(unary("X"), 3,
			shape(-1, -1, -1, -1, -1, -1));
		Request full = factory.prepare(unary("X"), 3,
			new ShapeHint(8, 4, 1000, Optional.of(true), 8, 4, -1, -1));
		Request notFull = factory.prepare(unary("X"), 3,
			new ShapeHint(8, 4, 1000, Optional.of(false), 8, 4, -1, -1));
		Request blockSize = factory.prepare(unary("X"), 3,
			new ShapeHint(8, 4, 512, Optional.empty(), 8, 4, -1, -1));

		Assert.assertEquals(base.key(), equivalent.key());
		Assert.assertNotEquals(base.key(), workers.key());
		Assert.assertNotEquals(base.key(), unknown.key());
		Assert.assertNotEquals(base.key(), full.key());
		Assert.assertNotEquals(base.key(), notFull.key());
		Assert.assertNotEquals(full.key(), notFull.key());
		Assert.assertNotEquals(base.key(), blockSize.key());
		Assert.assertEquals(new PreparationStats(7, 1, 6, 0), factory.statistics());
	}

	@Test
	public void tsmmIdentityAndMapmmChainStructureArePartOfTheOperatorKey() {
		RequestFactory factory = new RequestFactory();
		Hop shared = matrix("X", 8, 4);
		AggBinaryOp tsmm = mm("tsmm", transpose("tX", shared), shared, 4, 4);
		AggBinaryOp sameDimensions = mm("ordinary", transpose("tL", matrix("L", 8, 4)),
			matrix("R", 8, 4), 4, 4);
		Request sharedTsmm = factory.prepare(tsmm, 3, shape(4, 4, 4, 8, 8, 4));
		Request distinctGraph = factory.prepare(sameDimensions, 3, shape(4, 4, 4, 8, 8, 4));

		Hop chainInput = matrix("CX", 8, 4);
		AggBinaryOp inner = mm("inner", chainInput, matrix("v", 4, 1), 8, 1);
		AggBinaryOp chain = mm("chain", transpose("tCX", chainInput), inner, 4, 1);
		AggBinaryOp ordinary = mm("ordinary-chain-shape", transpose("tOX", matrix("OX", 8, 4)),
			matrix("ordinary-right", 8, 1), 4, 1);
		Request mapmmChain = factory.prepare(chain, 3, shape(4, 1, 4, 8, 8, 1));
		Request ordinaryMm = factory.prepare(ordinary, 3, shape(4, 1, 4, 8, 8, 1));

		Assert.assertNotEquals("TSMM depends on graph identity, not dimensions alone",
			sharedTsmm.key(), distinctGraph.key());
		Assert.assertNotEquals("mapmm-chain lowering must not collide with an ordinary multiply",
			mapmmChain.key(), ordinaryMm.key());
		Assert.assertEquals(new PreparationStats(4, 4, 0, 0), factory.statistics());
	}

	@Test
	public void noncacheableReorgRequestsHaveNullKeysAndAreBothEvaluated() {
		RequestFactory factory = new RequestFactory();
		Request first = factory.prepare(transpose("t1", matrix("X", 8, 4)), 2,
			shape(4, 8, 8, 4, -1, -1));
		Request second = factory.prepare(transpose("t2", matrix("Y", 8, 4)), 2,
			shape(4, 8, 8, 4, -1, -1));

		Assert.assertNull(first.key());
		Assert.assertNull(second.key());
		MemoNativeRules catalog = new MemoNativeRules();
		MemoNativeRules.Result firstResult = catalog.rules(first);
		MemoNativeRules.Result secondResult = catalog.rules(second);

		assertEquivalent(firstResult, secondResult);
		Assert.assertNotSame(firstResult, secondResult);
		Assert.assertEquals(new MemoNativeRules.CacheStats(0, 2, 0), catalog.cacheStats());
		Assert.assertEquals(new PreparationStats(2, 0, 0, 2), factory.statistics());
	}

	@Test
	public void preparedKeyAndShapeSnapshotRemainStableAfterCallerMutation() {
		RequestFactory factory = new RequestFactory();
		UnaryOp hop = unary("X");
		ShapeHint originalHint = shape(8, 4, 8, 4, -1, -1);
		Request original = factory.prepare(hop, 3, originalHint);
		RequestKey captured = original.key();
		MemoNativeRules catalog = new MemoNativeRules();
		MemoNativeRules.Result beforeMutation = catalog.rules(original);

		hop.setDim1(16);
		hop.getInput(0).setDim1(16);
		Request afterHopMutation = factory.prepare(hop, 3, originalHint);
		ShapeHint replacementHint = shape(16, 4, 16, 4, -1, -1);
		Request afterHintReplacement = factory.prepare(unary("fresh"), 3, replacementHint);

		Assert.assertSame("a prepared request remains bound to its captured key", captured, original.key());
		Assert.assertNotEquals(captured, afterHopMutation.key());
		Assert.assertNotEquals(captured, afterHintReplacement.key());
		Request originalAgain = factory.prepare(unary("equivalent"), 3, originalHint);
		Assert.assertEquals("later hint objects cannot change an earlier diagnostic snapshot",
			captured, originalAgain.key());
		Assert.assertSame("an equivalent fresh view reuses the result under the captured key",
			beforeMutation, catalog.rules(originalAgain));
		Assert.assertEquals(new MemoNativeRules.CacheStats(1, 1, 1), catalog.cacheStats());
		Assert.assertEquals(new PreparationStats(4, 2, 2, 0), factory.statistics());
	}

	private static void assertEquivalent(MemoNativeRules.Result expected, MemoNativeRules.Result actual) {
		Assert.assertEquals(expected.rules(), actual.rules());
		Assert.assertEquals(expected.inputRoles(), actual.inputRoles());
		Assert.assertEquals(expected.metadataInputs(), actual.metadataInputs());
		Assert.assertEquals(expected.complete(), actual.complete());
		Assert.assertEquals(expected.diagnostics(), actual.diagnostics());
		Assert.assertEquals(expected.generation(), actual.generation());
	}

	private static UnaryOp unary(String source) {
		UnaryOp exp = new UnaryOp("exp", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, matrix(source, 8, 4));
		exp.setDim1(8);
		exp.setDim2(4);
		return exp;
	}

	private static ReorgOp transpose(String name, Hop input) {
		ReorgOp transpose = new ReorgOp(name, DataType.MATRIX, ValueType.FP64, ReOrgOp.TRANS, input);
		transpose.setDim1(input.getDim2());
		transpose.setDim2(input.getDim1());
		return transpose;
	}

	private static AggBinaryOp mm(String name, Hop left, Hop right, long rows, long cols) {
		AggBinaryOp multiply = new AggBinaryOp(name, DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
		multiply.setDim1(rows);
		multiply.setDim2(cols);
		return multiply;
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
	}

	private static ShapeHint shape(long rows, long cols, long rowsA, long colsA,
		long rowsB, long colsB) {
		return new ShapeHint(rows, cols, 1000, Optional.empty(), rowsA, colsA, rowsB, colsB);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.hops.fedplanner.rules;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Assert;
import org.junit.Test;

public class RuleInnerFailurePropagationTest {
	private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);

	@Test
	public void transformEncodePropagatesUnexpectedException() {
		IllegalStateException failure = new IllegalStateException("synthetic transform failure");
		OpSig sig = OpSig.of(Opcodes.TRANSFORMENCODE.toString(), OpCategory.OTHER, Map.of());

		IllegalStateException actual = Assert.assertThrows(IllegalStateException.class,
			() -> new Rulesets.TransformEncodeRule().caps(sig, throwingList(1, failure), UNKNOWN_SHAPE));

		Assert.assertSame(failure, actual);
	}

	@Test
	public void transformEncodePropagatesErrors() {
		AssertionError failure = new AssertionError("synthetic transform error");
		OpSig sig = OpSig.of(Opcodes.TRANSFORMENCODE.toString(), OpCategory.OTHER, Map.of());

		AssertionError actual = Assert.assertThrows(AssertionError.class,
			() -> new Rulesets.TransformEncodeRule().caps(sig, throwingList(1, failure), UNKNOWN_SHAPE));

		Assert.assertSame(failure, actual);
	}

	@Test
	public void covariancePropagatesUnexpectedException() {
		IllegalStateException failure = new IllegalStateException("synthetic covariance failure");
		OpSig sig = OpSig.of(OpOp3.COV.toString(), OpCategory.BINARY_EWISE, Map.of());

		IllegalStateException actual = Assert.assertThrows(IllegalStateException.class,
			() -> new Rulesets.CovarianceRule().caps(sig, throwingList(2, failure), UNKNOWN_SHAPE));

		Assert.assertSame(failure, actual);
	}

	@Test
	public void explicitUnsupportedResultsRemainNormalDecisions() {
		OpSig transform = OpSig.of(Opcodes.TRANSFORMENCODE.toString(), OpCategory.OTHER, Map.of());
		Assert.assertEquals(ReasonCode.ARITY_MISMATCH,
			new Rulesets.TransformEncodeRule().caps(transform, List.of(), UNKNOWN_SHAPE).reason());

		OpSig covariance = OpSig.of(OpOp3.COV.toString(), OpCategory.OTHER, Map.of());
		Assert.assertEquals(ReasonCode.OPCODE_UNSUPPORTED,
			new Rulesets.CovarianceRule().caps(covariance, List.of(FType.ROW, FType.ROW), UNKNOWN_SHAPE).reason());
	}

	private static List<FType> throwingList(int size, Throwable failure) {
		return new AbstractList<>() {
			@Override
			public FType get(int index) {
				if(failure instanceof RuntimeException)
					throw (RuntimeException) failure;
				if(failure instanceof Error)
					throw (Error) failure;
				throw new AssertionError("unsupported synthetic failure", failure);
			}

			@Override
			public int size() {
				return size;
			}
		};
	}
}

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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.ArrayDeque;
import java.util.Queue;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.WhileStatementBlock;

public final class RewireConstants {
	public static final double DEFAULT_LOOP_WEIGHT = 10.0;
	public static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

	public static double estimateWhileLoopWeight(WhileStatementBlock wsb) {
		Hop predicate = wsb.getPredicateHops();
		if (predicate == null) {
			return DEFAULT_LOOP_WEIGHT;
		}

		double maxLiteralBound = -1;
		Queue<Hop> queue = new ArrayDeque<>();
		queue.add(predicate);

		while (!queue.isEmpty()) {
			Hop hop = queue.poll();
			if (hop instanceof BinaryOp) {
				BinaryOp bop = (BinaryOp) hop;
				Types.OpOp2 op = bop.getOp();
				Hop left = bop.getInput().get(0);
				Hop right = bop.getInput().get(1);

				if ((op == Types.OpOp2.LESS || op == Types.OpOp2.LESSEQUAL)
						&& right instanceof LiteralOp && !(left instanceof LiteralOp)) {
					maxLiteralBound = Math.max(maxLiteralBound,
							HopRewriteUtils.getDoubleValue((LiteralOp) right));
				} else if ((op == Types.OpOp2.GREATER || op == Types.OpOp2.GREATEREQUAL)
						&& left instanceof LiteralOp && !(right instanceof LiteralOp)) {
					maxLiteralBound = Math.max(maxLiteralBound,
							HopRewriteUtils.getDoubleValue((LiteralOp) left));
				}
			}

			if (hop.getInput() != null) {
				queue.addAll(hop.getInput());
			}
		}

		return maxLiteralBound > 0 ? maxLiteralBound : DEFAULT_LOOP_WEIGHT;
	}

	private RewireConstants() {
		// utility class
	}
}

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
import java.util.List;
import java.util.Queue;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;

public final class RewireConstants {
	public static final double DEFAULT_LOOP_WEIGHT = 10.0;
	public static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

	public static double estimateWhileLoopWeight(WhileStatementBlock wsb) {
		double maxLiteralBound = -1;
		Queue<Hop> queue = new ArrayDeque<>();
		Hop predicate = wsb.getPredicateHops();
		if (predicate != null)
			queue.add(predicate);

		// Heuristic: Many real-world algorithms (e.g., kmeans) use a predicate such as
		// `term_code == 0` and enforce a max-iteration bound inside the loop body.
		// If we only inspect the while predicate, we miss the runtime iteration upper
		// bound (max_iter), which causes systematic under-estimation of both compute
		// and network costs inside the loop.
		//
		// To improve planning accuracy, also scan the loop body for scalar comparison
		// predicates with literal bounds (e.g., `iter_count >= 60`).
		WhileStatement wstmt = null;
		if (wsb.getNumStatements() > 0 && wsb.getStatement(0) instanceof WhileStatement)
			wstmt = (WhileStatement) wsb.getStatement(0);
		if (wstmt != null) {
			List<StatementBlock> body = wstmt.getBody();
			if (body != null) {
				for (StatementBlock sb : body) {
					enqueueStatementBlockHops(sb, queue, 0);
				}
			}
		}

		while (!queue.isEmpty()) {
			Hop hop = queue.poll();
			if (hop instanceof BinaryOp) {
				BinaryOp bop = (BinaryOp) hop;
				Types.OpOp2 op = bop.getOp();
				Hop left = bop.getInput().get(0);
				Hop right = bop.getInput().get(1);

				if (op == Types.OpOp2.LESS || op == Types.OpOp2.LESSEQUAL) {
					if (right instanceof LiteralOp && !(left instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) right));
					} else if (left instanceof LiteralOp && !(right instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) left));
					}
				} else if (op == Types.OpOp2.GREATER || op == Types.OpOp2.GREATEREQUAL) {
					if (left instanceof LiteralOp && !(right instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) left));
					} else if (right instanceof LiteralOp && !(left instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) right));
					}
				}
			}

			if (hop.getInput() != null) {
				queue.addAll(hop.getInput());
			}
		}

		return maxLiteralBound > 0 ? maxLiteralBound : DEFAULT_LOOP_WEIGHT;
	}

	private static final int BODY_SCAN_MAX_DEPTH = 6;

	private static void enqueueStatementBlockHops(StatementBlock sb, Queue<Hop> queue, int depth) {
		if (sb == null || queue == null || depth > BODY_SCAN_MAX_DEPTH)
			return;
		if (sb.getHops() != null)
			queue.addAll(sb.getHops());

		// Include control-flow predicates and descend into nested statement blocks.
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			if (isb.getPredicateHops() != null)
				queue.add(isb.getPredicateHops());
			if (isb.getNumStatements() > 0 && isb.getStatement(0) instanceof IfStatement) {
				IfStatement istmt = (IfStatement) isb.getStatement(0);
				if (istmt.getIfBody() != null) {
					for (StatementBlock inner : istmt.getIfBody())
						enqueueStatementBlockHops(inner, queue, depth + 1);
				}
				if (istmt.getElseBody() != null) {
					for (StatementBlock inner : istmt.getElseBody())
						enqueueStatementBlockHops(inner, queue, depth + 1);
				}
			}
		}
		else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			if (fsb.getFromHops() != null)
				queue.add(fsb.getFromHops());
			if (fsb.getToHops() != null)
				queue.add(fsb.getToHops());
			if (fsb.getIncrementHops() != null)
				queue.add(fsb.getIncrementHops());
			if (fsb.getNumStatements() > 0 && fsb.getStatement(0) instanceof ForStatement) {
				ForStatement fstmt = (ForStatement) fsb.getStatement(0);
				if (fstmt.getBody() != null) {
					for (StatementBlock inner : fstmt.getBody())
						enqueueStatementBlockHops(inner, queue, depth + 1);
				}
			}
		}
		else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			if (wsb.getPredicateHops() != null)
				queue.add(wsb.getPredicateHops());
			if (wsb.getNumStatements() > 0 && wsb.getStatement(0) instanceof WhileStatement) {
				WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
				if (wstmt.getBody() != null) {
					for (StatementBlock inner : wstmt.getBody())
						enqueueStatementBlockHops(inner, queue, depth + 1);
				}
			}
		}
	}

	private RewireConstants() {
		// utility class
	}
}

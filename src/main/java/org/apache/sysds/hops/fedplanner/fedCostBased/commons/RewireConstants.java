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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
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
	private static final int SCALAR_CONST_EVAL_MAX_DEPTH = 8;

	public static double estimateWhileLoopWeight(WhileStatementBlock wsb) {
		return estimateWhileLoopWeight(wsb, null);
	}

	public static double estimateWhileLoopWeight(WhileStatementBlock wsb,
			List<Map<String, List<Hop>>> transTableStack) {
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

				Double inductionBound = estimateInductionPredicateLoopWeight(wsb, bop, transTableStack);
				if (inductionBound != null)
					maxLiteralBound = Math.max(maxLiteralBound, inductionBound);

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

	private static Double estimateInductionPredicateLoopWeight(WhileStatementBlock wsb, BinaryOp predicate,
			List<Map<String, List<Hop>>> transTableStack) {
		if (predicate == null || predicate.getInput() == null || predicate.getInput().size() < 2)
			return null;
		Types.OpOp2 op = predicate.getOp();
		if (op == Types.OpOp2.AND) {
			Double left = estimatePredicateLoopWeight(wsb, predicate.getInput().get(0), transTableStack);
			Double right = estimatePredicateLoopWeight(wsb, predicate.getInput().get(1), transTableStack);
			if (left == null)
				return right;
			if (right == null)
				return left;
			return Math.max(left, right);
		}
		return estimateComparisonLoopWeight(wsb, predicate, transTableStack);
	}

	private static Double estimatePredicateLoopWeight(WhileStatementBlock wsb, Hop predicate,
			List<Map<String, List<Hop>>> transTableStack) {
		if (!(predicate instanceof BinaryOp))
			return null;
		return estimateInductionPredicateLoopWeight(wsb, (BinaryOp) predicate, transTableStack);
	}

	private static Double estimateComparisonLoopWeight(WhileStatementBlock wsb, BinaryOp predicate,
			List<Map<String, List<Hop>>> transTableStack) {
		Types.OpOp2 op = predicate.getOp();
		Hop left = predicate.getInput().get(0);
		Hop right = predicate.getInput().get(1);
		LoopBoundExpression leftExpr = extractLoopBoundExpression(left);
		LoopBoundExpression rightExpr = extractLoopBoundExpression(right);

		if (op == Types.OpOp2.LESS || op == Types.OpOp2.LESSEQUAL) {
			Double bound = tryEvaluateScalarConstant(right, transTableStack);
			Double estimate = estimateLoopIterationsFromUpperBound(wsb, leftExpr, bound,
				op == Types.OpOp2.LESS, transTableStack);
			if (estimate != null)
				return estimate;
		}
		else if (op == Types.OpOp2.GREATER || op == Types.OpOp2.GREATEREQUAL) {
			Double bound = tryEvaluateScalarConstant(left, transTableStack);
			Double estimate = estimateLoopIterationsFromUpperBound(wsb, rightExpr, bound,
				op == Types.OpOp2.GREATER, transTableStack);
			if (estimate != null)
				return estimate;
		}
		return null;
	}

	private static Double estimateLoopIterationsFromUpperBound(WhileStatementBlock wsb,
			LoopBoundExpression expr, Double bound, boolean strictUpperBound,
			List<Map<String, List<Hop>>> transTableStack) {
		if (expr == null || bound == null || !Double.isFinite(bound) || bound < 0.0)
			return null;
		Double init = tryEvaluateScalarConstant(expr.variableName, transTableStack);
		Double step = findPositiveLoopStep(wsb, expr.variableName);
		if (init == null || step == null || step <= 0.0 || !Double.isFinite(step))
			return null;
		double scaledExclusiveBound = strictUpperBound
			? bound * expr.divisor
			: (bound + 1.0) * expr.divisor;
		double iterations = Math.ceil((scaledExclusiveBound - init) / step);
		return iterations > 0.0 && Double.isFinite(iterations) ? iterations : null;
	}

	private static final class LoopBoundExpression {
		private final String variableName;
		private final double divisor;

		private LoopBoundExpression(String variableName, double divisor) {
			this.variableName = variableName;
			this.divisor = divisor;
		}
	}

	private static LoopBoundExpression extractLoopBoundExpression(Hop hop) {
		Hop stripped = stripScalarCasts(hop);
		String varName = transientReadName(stripped);
		if (varName != null)
			return new LoopBoundExpression(varName, 1.0);
		if (stripped instanceof BinaryOp) {
			BinaryOp bop = (BinaryOp) stripped;
			if ((bop.getOp() == Types.OpOp2.DIV || bop.getOp() == Types.OpOp2.INTDIV)
					&& bop.getInput() != null && bop.getInput().size() >= 2) {
				LoopBoundExpression inputExpr = extractLoopBoundExpression(bop.getInput().get(0));
				Double divisor = tryEvaluateScalarConstant(bop.getInput().get(1), null);
				if (inputExpr != null && divisor != null && divisor > 0.0 && Double.isFinite(divisor))
					return new LoopBoundExpression(inputExpr.variableName, inputExpr.divisor * divisor);
			}
		}
		return null;
	}

	private static Hop stripScalarCasts(Hop hop) {
		Hop current = hop;
		while (current instanceof UnaryOp && current.getDataType() == Types.DataType.SCALAR
				&& current.getInput() != null && !current.getInput().isEmpty()) {
			UnaryOp uop = (UnaryOp) current;
			switch (uop.getOp()) {
				case CAST_AS_INT:
				case CAST_AS_DOUBLE:
				case CAST_AS_BOOLEAN:
				case CAST_AS_MATRIX:
				case CAST_AS_SCALAR:
					current = uop.getInput().get(0);
					continue;
				default:
					return current;
			}
		}
		return current;
	}

	private static String transientReadName(Hop hop) {
		if (!(hop instanceof DataOp))
			return null;
		DataOp dop = (DataOp) hop;
		return dop.getOp() == Types.OpOpData.TRANSIENTREAD ? dop.getName() : null;
	}

	private static Double findPositiveLoopStep(WhileStatementBlock wsb, String variableName) {
		if (wsb == null || variableName == null || variableName.isEmpty())
			return null;
		Queue<Hop> queue = new ArrayDeque<>();
		if (wsb.getNumStatements() > 0 && wsb.getStatement(0) instanceof WhileStatement) {
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			if (wstmt.getBody() != null) {
				for (StatementBlock sb : wstmt.getBody())
					enqueueStatementBlockHops(sb, queue, 0);
			}
		}
		Set<Long> visited = new HashSet<>();
		while (!queue.isEmpty()) {
			Hop hop = queue.poll();
			if (hop == null || !visited.add(hop.getHopID()))
				continue;
			if (hop instanceof DataOp) {
				DataOp dop = (DataOp) hop;
				if (dop.getOp() == Types.OpOpData.TRANSIENTWRITE && variableName.equals(dop.getName())
						&& dop.getInput() != null && !dop.getInput().isEmpty()) {
					Double step = extractSelfIncrementStep(dop.getInput().get(0), variableName);
					if (step != null && step > 0.0)
						return step;
				}
			}
			if (hop.getInput() != null)
				queue.addAll(hop.getInput());
		}
		return null;
	}

	private static Double extractSelfIncrementStep(Hop hop, String variableName) {
		if (!(hop instanceof BinaryOp))
			return null;
		BinaryOp bop = (BinaryOp) hop;
		if (bop.getInput() == null || bop.getInput().size() < 2)
			return null;
		Hop left = bop.getInput().get(0);
		Hop right = bop.getInput().get(1);
		Double leftConst = tryEvaluateScalarConstant(left, null);
		Double rightConst = tryEvaluateScalarConstant(right, null);
		boolean leftVar = variableName.equals(transientReadName(stripScalarCasts(left)));
		boolean rightVar = variableName.equals(transientReadName(stripScalarCasts(right)));
		if (bop.getOp() == Types.OpOp2.PLUS) {
			if (leftVar && rightConst != null)
				return rightConst;
			if (rightVar && leftConst != null)
				return leftConst;
		}
		else if (bop.getOp() == Types.OpOp2.MINUS) {
			if (leftVar && rightConst != null)
				return -rightConst;
		}
		return null;
	}

	/**
	 * Best-effort scalar constant evaluation for loop-bound estimation.
	 *
	 * <p>This resolves scalar literals through transient variables (TRead/TWrite) and
	 * basic scalar expressions. It is used to improve loop iteration-count estimation
	 * in federated planner rewiring, especially for builtins where loop bounds are
	 * derived from constant function arguments (e.g., {@code kmeans} with {@code k=50}).</p>
	 *
	 * <p>Returns {@code null} if the value cannot be determined safely.</p>
	 */
	public static Double tryEvaluateScalarConstant(Hop hop, List<Map<String, List<Hop>>> transTableStack) {
		if (hop == null)
			return null;
		Set<Long> visited = new HashSet<>();
		return tryEvaluateScalarConstant(hop, transTableStack, visited, 0);
	}

	private static Double tryEvaluateScalarConstant(Hop hop, List<Map<String, List<Hop>>> transTableStack,
			Set<Long> visited, int depth) {
		if (hop == null || depth > SCALAR_CONST_EVAL_MAX_DEPTH)
			return null;
		if (!visited.add(hop.getHopID()))
			return null;

		if (hop instanceof LiteralOp) {
			return HopRewriteUtils.getDoubleValue((LiteralOp) hop);
		}

		if (hop instanceof DataOp) {
			DataOp dop = (DataOp) hop;
			if (dop.getOp() == Types.OpOpData.TRANSIENTWRITE) {
				if (hop.getInput() != null && !hop.getInput().isEmpty())
					return tryEvaluateScalarConstant(hop.getInput().get(0), transTableStack, visited, depth + 1);
				return null;
			}
			if (dop.getOp() == Types.OpOpData.TRANSIENTREAD) {
				String name = dop.getName();
				Hop mapped = lookupLatestTransTableHop(name, transTableStack);
				if (mapped == null || mapped == hop)
					return null;
				return tryEvaluateScalarConstant(mapped, transTableStack, visited, depth + 1);
			}
		}

		if (hop instanceof UnaryOp && hop.getDataType() == Types.DataType.SCALAR) {
			UnaryOp uop = (UnaryOp) hop;
			if (uop.getInput() == null || uop.getInput().isEmpty())
				return null;
			Double in = tryEvaluateScalarConstant(uop.getInput().get(0), transTableStack, visited, depth + 1);
			if (in == null)
				return null;
			switch (uop.getOp()) {
				case CAST_AS_INT:
				case CAST_AS_DOUBLE:
				case CAST_AS_BOOLEAN:
				case CAST_AS_MATRIX:
				case CAST_AS_SCALAR:
					return in;
				case ABS:
					return Math.abs(in);
				default:
					return null;
			}
		}

		if (hop instanceof BinaryOp && hop.getDataType() == Types.DataType.SCALAR) {
			BinaryOp bop = (BinaryOp) hop;
			if (bop.getInput() == null || bop.getInput().size() < 2)
				return null;
			Double left = tryEvaluateScalarConstant(bop.getInput().get(0), transTableStack, visited, depth + 1);
			Double right = tryEvaluateScalarConstant(bop.getInput().get(1), transTableStack, visited, depth + 1);
			if (left == null || right == null)
				return null;
			switch (bop.getOp()) {
				case PLUS:
					return left + right;
				case MINUS:
					return left - right;
				case MULT:
					return left * right;
				case DIV:
					if (right == 0.0)
						return null;
					return left / right;
				default:
					return null;
			}
		}

		return null;
	}

	private static Double tryEvaluateScalarConstant(String name, List<Map<String, List<Hop>>> transTableStack) {
		Hop mapped = lookupLatestTransTableHop(name, transTableStack);
		if (mapped == null)
			return null;
		Set<Long> visited = new HashSet<>();
		return tryEvaluateScalarConstant(mapped, transTableStack, visited, 0);
	}

	private static Hop lookupLatestTransTableHop(String name, List<Map<String, List<Hop>>> transTableStack) {
		if (name == null || name.isEmpty() || transTableStack == null || transTableStack.isEmpty())
			return null;
		for (int i = transTableStack.size() - 1; i >= 0; i--) {
			Map<String, List<Hop>> table = transTableStack.get(i);
			if (table == null)
				continue;
			List<Hop> hops = table.get(name);
			if (hops == null || hops.isEmpty())
				continue;
			return hops.get(hops.size() - 1);
		}
		return null;
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

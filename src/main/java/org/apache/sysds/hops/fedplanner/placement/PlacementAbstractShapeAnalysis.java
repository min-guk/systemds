/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.ScalarLiteralFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DataExpression;

/**
 * Common, occurrence-aware abstract-value closure used before selector policy is
 * applied. The lattice is deliberately finite: bottom, one exact fact, or unknown.
 * No HOP metadata is mutated and no planner may widen this shared domain privately.
 */
final class PlacementAbstractShapeAnalysis {
	private PlacementAbstractShapeAnalysis() { }

	record HopFacts(Map<Hop,AbstractShapeFact> shapes, Map<Hop,ScalarState> scalars) {
		HopFacts {
			shapes = Collections.unmodifiableMap(new IdentityHashMap<>(shapes));
			scalars = Collections.unmodifiableMap(new IdentityHashMap<>(scalars));
		}
	}

	record KeyFacts(Map<CompiledHopKey,AbstractShapeFact> shapes,
		Map<CompiledHopKey,ScalarLiteralFact> scalarLiterals) {
		KeyFacts {
			shapes = Map.copyOf(shapes);
			scalarLiterals = Map.copyOf(scalarLiterals);
		}
	}

	private enum ScalarKnowledge { BOTTOM, EXACT, UNKNOWN }

	static final class ScalarState {
		private static final ScalarState BOTTOM = new ScalarState(ScalarKnowledge.BOTTOM, null);
		private static final ScalarState UNKNOWN = new ScalarState(ScalarKnowledge.UNKNOWN, null);
		private final ScalarKnowledge knowledge;
		private final ScalarLiteralFact literal;

		private ScalarState(ScalarKnowledge knowledge, ScalarLiteralFact literal) {
			this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
			this.literal = literal;
		}

		static ScalarState bottom() { return BOTTOM; }
		static ScalarState unknown() { return UNKNOWN; }
		static ScalarState exact(ScalarLiteralFact literal) {
			return new ScalarState(ScalarKnowledge.EXACT, Objects.requireNonNull(literal, "literal"));
		}
		static ScalarState exact(LiteralOp literal) {
			return exact(new ScalarLiteralFact(literal.getValueType(), literal.getStringValue()));
		}
		boolean isBottom() { return knowledge == ScalarKnowledge.BOTTOM; }
		boolean isExact() { return knowledge == ScalarKnowledge.EXACT; }
		boolean isUnknown() { return knowledge == ScalarKnowledge.UNKNOWN; }
		ScalarLiteralFact literal() { return literal; }
		ScalarState join(ScalarState that) {
			if(isBottom()) return that;
			if(that.isBottom()) return this;
			if(knowledge == ScalarKnowledge.UNKNOWN || that.knowledge == ScalarKnowledge.UNKNOWN)
				return unknown();
			return literal.equals(that.literal) ? this : unknown();
		}
		@Override public boolean equals(Object that) {
			return that instanceof ScalarState state && knowledge == state.knowledge
				&& Objects.equals(literal, state.literal);
		}
		@Override public int hashCode() { return Objects.hash(knowledge, literal); }
	}

	static HopFacts inferOriginalOccurrences(List<Hop> hops, List<String> namespaces,
		List<Set<Integer>> reachingDefinitions, List<Boolean> reachingFunctionInputs,
		FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		Objects.requireNonNull(fgraph, "fgraph");
		Objects.requireNonNull(fcallSizes, "fcallSizes");
		if(hops.size() != namespaces.size() || hops.size() != reachingDefinitions.size()
			|| hops.size() != reachingFunctionInputs.size())
			throw new IllegalArgumentException("Abstract-analysis occurrence inputs differ in size");

		Map<Hop,AbstractShapeFact> shapes = new IdentityHashMap<>();
		Map<Hop,ScalarState> scalars = new IdentityHashMap<>();
		for(Hop hop : hops) {
			shapes.put(hop, concreteSeed(hop));
			scalars.put(hop, hop instanceof LiteralOp literal ? ScalarState.exact(literal) : ScalarState.bottom());
		}

		Map<Hop,List<Hop>> valueSources = new IdentityHashMap<>();
		Set<Hop> unsafeScalarFunctionInputs = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int ordinal = 0; ordinal < hops.size(); ordinal++) {
			Hop target = hops.get(ordinal);
			for(int sourceOrdinal : reachingDefinitions.get(ordinal))
				if(sourceOrdinal >= 0 && sourceOrdinal < hops.size())
					valueSources.computeIfAbsent(target, ignored -> new ArrayList<>()).add(hops.get(sourceOrdinal));
			if(!reachingFunctionInputs.get(ordinal))
				continue;
			String formal = target.getName();
			for(Hop candidate : hops) {
				if(!(candidate instanceof FunctionOp call) || !functionMatches(call, namespaces.get(ordinal))
					|| !fgraph.getReachableFunctions().contains(call.getFunctionKey()))
					continue;
				String[] names = call.getInputVariableNames();
				for(int position = 0; names != null && position < names.length
					&& position < call.getInput().size(); position++) {
					if(!Objects.equals(formal, names[position]))
						continue;
					valueSources.computeIfAbsent(target, ignored -> new ArrayList<>()).add(call.getInput(position));
					if(!fcallSizes.isSafeLiteral(call.getFunctionKey(), position))
						unsafeScalarFunctionInputs.add(target);
				}
			}
		}

		closeHopFacts(hops, shapes, scalars, valueSources, unsafeScalarFunctionInputs);
		return new HopFacts(shapes, scalars);
	}

	static KeyFacts closeCompiledOccurrences(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections,
		FunctionCallSizeInfo fcallSizes) {
		Map<CompiledHopKey,Hop> hops = new LinkedHashMap<>();
		Map<CompiledHopKey,AbstractShapeFact> shapes = new LinkedHashMap<>();
		Map<CompiledHopKey,ScalarState> scalars = new LinkedHashMap<>();
		for(HopOccurrenceProjection projection : projections) {
			CompiledHopKey key = projection.key();
			Hop hop = projection.hop();
			hops.put(key, hop);
			NodeKind kind = graph.node(key).orElseThrow().kind();
			boolean synthetic = kind == NodeKind.FUNCTION_INPUT || kind == NodeKind.FUNCTION_OUTPUT;
			shapes.put(key, synthetic ? AbstractShapeFact.bottom(DataType.UNKNOWN) : concreteSeed(hop));
			scalars.put(key, synthetic ? ScalarState.bottom()
				: hop instanceof LiteralOp literal ? ScalarState.exact(literal) : ScalarState.bottom());
		}

		Map<CompiledHopKey,Map<Integer,List<CompiledHopKey>>> physicalInputs = new LinkedHashMap<>();
		Map<CompiledHopKey,List<CompiledHopKey>> valueSources = new LinkedHashMap<>();
		Set<CompiledHopKey> unsafeScalarFunctionInputs = new LinkedHashSet<>();
		for(Constraint constraint : graph.constraints()) {
			String evidence = constraint.evidence();
			if("data-input".equals(evidence))
				physicalInputs.computeIfAbsent(constraint.right(), ignored -> new LinkedHashMap<>())
					.computeIfAbsent(constraint.inputPosition(), ignored -> new ArrayList<>()).add(constraint.left());
			if(carriesAbstractValue(evidence)) {
				valueSources.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>()).add(constraint.left());
				if("function-formal-input".equals(evidence)) {
					Hop callHop = hops.get(constraint.left());
					if(!(callHop instanceof FunctionOp call)
						|| !fcallSizes.isSafeLiteral(call.getFunctionKey(), constraint.inputPosition()))
						unsafeScalarFunctionInputs.add(constraint.right());
				}
			}
			if("stable-origin".equals(evidence))
				valueSources.computeIfAbsent(constraint.left(), ignored -> new ArrayList<>()).add(constraint.right());
		}

		int maxPasses = Math.max(8, projections.size() * 8);
		boolean converged = closeKeyFacts(projections, shapes, scalars, physicalInputs,
			valueSources, unsafeScalarFunctionInputs, maxPasses);
		if(!converged)
			throw new IllegalStateException("Occurrence-scoped abstract-value closure did not converge");
		promoteUnresolvedKeyFacts(projections, shapes, scalars);
		converged = closeKeyFacts(projections, shapes, scalars, physicalInputs,
			valueSources, unsafeScalarFunctionInputs, maxPasses);
		if(!converged)
			throw new IllegalStateException("Occurrence-scoped abstract-value unknown closure did not converge");

		Map<CompiledHopKey,AbstractShapeFact> publishedShapes = new LinkedHashMap<>();
		Map<CompiledHopKey,ScalarLiteralFact> publishedScalars = new LinkedHashMap<>();
		for(HopOccurrenceProjection projection : projections) {
			CompiledHopKey key = projection.key();
			publishedShapes.put(key, publish(shapes.get(key)));
			ScalarState scalar = scalars.get(key);
			if(scalar.isExact())
				publishedScalars.put(key, scalar.literal());
		}
		return new KeyFacts(publishedShapes, publishedScalars);
	}

	private static boolean closeKeyFacts(List<HopOccurrenceProjection> projections,
		Map<CompiledHopKey,AbstractShapeFact> shapes, Map<CompiledHopKey,ScalarState> scalars,
		Map<CompiledHopKey,Map<Integer,List<CompiledHopKey>>> physicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> valueSources,
		Set<CompiledHopKey> unsafeScalarFunctionInputs, int maxPasses) {
		for(int pass = 0; pass < maxPasses; pass++) {
			boolean changed = false;
			for(HopOccurrenceProjection projection : projections) {
				CompiledHopKey key = projection.key();
				AbstractShapeFact inferredShape = inferShape(projection.hop(),
					orderedInputShapes(projection.hop(), physicalInputs.get(key), shapes),
					orderedInputScalars(projection.hop(), physicalInputs.get(key), scalars));
				ScalarState inferredScalar = inferScalar(projection.hop(),
					orderedInputShapes(projection.hop(), physicalInputs.get(key), shapes),
					orderedInputScalars(projection.hop(), physicalInputs.get(key), scalars));
				List<CompiledHopKey> sources = valueSources.get(key);
				if(sources != null && !sources.isEmpty()) {
					inferredShape = inferredShape.join(completeShapeJoinKeys(sources, shapes));
					ScalarState joinedScalar = unsafeScalarFunctionInputs.contains(key)
						? ScalarState.unknown() : completeScalarJoinKeys(sources, scalars);
					inferredScalar = inferredScalar.join(joinedScalar);
				}
				AbstractShapeFact nextShape = shapes.get(key).join(inferredShape);
				ScalarState nextScalar = scalars.get(key).join(inferredScalar);
				if(!nextShape.equals(shapes.get(key))) {
					shapes.put(key, nextShape);
					changed = true;
				}
				if(!nextScalar.equals(scalars.get(key))) {
					scalars.put(key, nextScalar);
					changed = true;
				}
			}
			if(!changed) {
				return true;
			}
		}
		return false;
	}

	private static void closeHopFacts(List<Hop> hops, Map<Hop,AbstractShapeFact> shapes,
		Map<Hop,ScalarState> scalars, Map<Hop,List<Hop>> valueSources,
		Set<Hop> unsafeScalarFunctionInputs) {
		int maxPasses = Math.max(8, hops.size() * 8);
		if(!closeHopFactsPass(hops, shapes, scalars, valueSources, unsafeScalarFunctionInputs, maxPasses))
			throw new IllegalStateException("Preliminary abstract-value closure did not converge");
		for(Hop hop : hops) {
			shapes.put(hop, promoteUnresolved(shapes.get(hop)));
			if(hop.getDataType() == DataType.SCALAR && scalars.get(hop).isBottom())
				scalars.put(hop, ScalarState.unknown());
		}
		if(!closeHopFactsPass(hops, shapes, scalars, valueSources, unsafeScalarFunctionInputs, maxPasses))
			throw new IllegalStateException("Preliminary abstract-value unknown closure did not converge");
	}

	private static boolean closeHopFactsPass(List<Hop> hops, Map<Hop,AbstractShapeFact> shapes,
		Map<Hop,ScalarState> scalars, Map<Hop,List<Hop>> valueSources,
		Set<Hop> unsafeScalarFunctionInputs, int maxPasses) {
		for(int pass = 0; pass < maxPasses; pass++) {
			boolean changed = false;
			for(Hop hop : hops) {
				List<AbstractShapeFact> inputShapes = hop.getInput().stream()
					.map(input -> shapes.getOrDefault(input, concreteSeed(input))).toList();
				List<ScalarState> inputScalars = hop.getInput().stream()
					.map(input -> scalars.getOrDefault(input, ScalarState.bottom())).toList();
				AbstractShapeFact inferredShape = inferShape(hop, inputShapes, inputScalars);
				ScalarState inferredScalar = inferScalar(hop, inputShapes, inputScalars);
				List<Hop> sources = valueSources.get(hop);
				if(sources != null && !sources.isEmpty()) {
					inferredShape = inferredShape.join(completeShapeJoinHops(sources, shapes));
					inferredScalar = inferredScalar.join(unsafeScalarFunctionInputs.contains(hop)
						? ScalarState.unknown() : completeScalarJoinHops(sources, scalars));
				}
				AbstractShapeFact nextShape = shapes.get(hop).join(inferredShape);
				ScalarState nextScalar = scalars.get(hop).join(inferredScalar);
				if(!nextShape.equals(shapes.get(hop))) {
					shapes.put(hop, nextShape);
					changed = true;
				}
				if(!nextScalar.equals(scalars.get(hop))) {
					scalars.put(hop, nextScalar);
					changed = true;
				}
			}
			if(!changed)
				return true;
		}
		return false;
	}

	private static AbstractShapeFact inferShape(Hop hop, List<AbstractShapeFact> inputs,
		List<ScalarState> scalars) {
		AbstractShapeFact bottom = AbstractShapeFact.bottom(hop.getDataType());
		if(hop instanceof LiteralOp || hop.getDataType() == DataType.SCALAR)
			return bottom;
		if(hop instanceof DataGenOp dataGen)
			return dataGenShape(dataGen, scalars);
		if(hop instanceof ReorgOp reorg) {
			AbstractShapeFact input = inputShape(inputs, 0);
			if(reorg.getOp() == ReOrgOp.TRANS)
				return new AbstractShapeFact(hop.getDataType(), input.cols(), input.rows());
			if(reorg.getOp() == ReOrgOp.RESHAPE)
				return new AbstractShapeFact(hop.getDataType(), dimension(inputScalar(scalars, 1)),
					dimension(inputScalar(scalars, 2)));
			return input;
		}
		if(hop instanceof AggBinaryOp)
			return new AbstractShapeFact(hop.getDataType(), inputShape(inputs, 0).rows(),
				inputShape(inputs, 1).cols());
		if(hop instanceof AggUnaryOp aggregate) {
			AbstractShapeFact input = inputShape(inputs, 0);
			if(aggregate.getDirection() == Direction.Col)
				return new AbstractShapeFact(hop.getDataType(), DimensionFact.exact(1), input.cols());
			if(aggregate.getDirection() == Direction.Row)
				return new AbstractShapeFact(hop.getDataType(), input.rows(), DimensionFact.exact(1));
			return bottom;
		}
		if(hop instanceof ParameterizedBuiltinOp parameterized) {
			int target = parameterized.getParamIndexMap().getOrDefault("target", -1);
			AbstractShapeFact targetShape = inputShape(inputs, target);
			if(parameterized.getOp() == ParamBuiltinOp.RMEMPTY) {
				int margin = parameterized.getParamIndexMap().getOrDefault("margin", -1);
				String value = exactString(inputScalar(scalars, margin)).orElse("");
				if("rows".equals(value))
					return new AbstractShapeFact(hop.getDataType(), DimensionFact.unknown(), targetShape.cols());
				if("cols".equals(value))
					return new AbstractShapeFact(hop.getDataType(), targetShape.rows(), DimensionFact.unknown());
				return new AbstractShapeFact(hop.getDataType(), DimensionFact.unknown(), DimensionFact.unknown());
			}
			if(parameterized.getOp() == ParamBuiltinOp.REPLACE)
				return targetShape;
		}
		if(hop instanceof IndexingOp indexing) {
			AbstractShapeFact input = inputShape(inputs, 0);
			DimensionFact rows = indexedDimension(indexing.isRowLowerEqualsUpper(), input.rows(),
				inputScalar(scalars, 1), inputScalar(scalars, 2), hop.getInput(), 0, 1, 2, OpOp1.NROW);
			DimensionFact cols = indexedDimension(indexing.isColLowerEqualsUpper(), input.cols(),
				inputScalar(scalars, 3), inputScalar(scalars, 4), hop.getInput(), 0, 3, 4, OpOp1.NCOL);
			return new AbstractShapeFact(hop.getDataType(), rows, cols);
		}
		if(hop instanceof DataOp data && (data.getOp().isWrite() || data.getOp() == org.apache.sysds.common.Types.OpOpData.FUNCTIONOUTPUT))
			return inputShape(inputs, 0);
		if(hop instanceof UnaryOp unary && hop.getDataType() == DataType.MATRIX)
			return inputShape(inputs, 0);
		if(hop instanceof BinaryOp && hop.getDataType() == DataType.MATRIX)
			return broadcastShape(hop.getDataType(), inputs);
		if(hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME) {
			List<AbstractShapeFact> dataInputs = new ArrayList<>();
			for(int i = 0; i < hop.getInput().size() && i < inputs.size(); i++)
				if(hop.getInput(i).getDataType() == DataType.MATRIX
					|| hop.getInput(i).getDataType() == DataType.FRAME)
					dataInputs.add(inputs.get(i));
			if(dataInputs.size() == 1)
				return dataInputs.get(0);
		}
		return bottom;
	}

	private static ScalarState inferScalar(Hop hop, List<AbstractShapeFact> inputs,
		List<ScalarState> scalars) {
		if(hop instanceof LiteralOp literal)
			return ScalarState.exact(literal);
		if(hop instanceof BinaryOp binary && hop.getDataType() == DataType.SCALAR)
			return inferScalarBinary(binary, inputScalar(scalars, 0), inputScalar(scalars, 1));
		if(hop instanceof UnaryOp unary) {
			if(unary.getOp() == OpOp1.NROW)
				return exactDimension(inputShape(inputs, 0).rows());
			if(unary.getOp() == OpOp1.NCOL)
				return exactDimension(inputShape(inputs, 0).cols());
			if(hop.getDataType() == DataType.SCALAR && !scalars.isEmpty())
				return inferScalarUnary(unary, scalars.get(0));
		}
		if(hop instanceof DataOp data && hop.getDataType() == DataType.SCALAR
			&& (data.getOp().isWrite() || data.getOp() == org.apache.sysds.common.Types.OpOpData.FUNCTIONOUTPUT))
			return inputScalar(scalars, 0);
		return ScalarState.bottom();
	}

	private static ScalarState inferScalarUnary(UnaryOp unary, ScalarState input) {
		if(input.isBottom())
			return ScalarState.bottom();
		if(input.isUnknown())
			return ScalarState.unknown();
		if(unary.getOp() == OpOp1.NOT)
			return exactBoolean(input).map(value -> exactBooleanLiteral(!value))
				.orElseGet(ScalarState::unknown);
		// A generic scalar unary operator does not preserve its operand's literal. Treat
		// unsupported transfers conservatively instead of publishing the input value as
		// the output value (which is unsound for !, arithmetic functions, and casts).
		return ScalarState.unknown();
	}

	private static ScalarState inferScalarBinary(BinaryOp binary, ScalarState left,
		ScalarState right) {
		if(left.isUnknown() || right.isUnknown())
			return ScalarState.unknown();
		if(!left.isExact() || !right.isExact())
			return ScalarState.bottom();

		OpOp2 op = binary.getOp();
		if(op == OpOp2.AND || op == OpOp2.OR) {
			Optional<Boolean> lhs = exactBoolean(left);
			Optional<Boolean> rhs = exactBoolean(right);
			if(lhs.isEmpty() || rhs.isEmpty())
				return ScalarState.unknown();
			return exactBooleanLiteral(op == OpOp2.AND
				? lhs.get() && rhs.get() : lhs.get() || rhs.get());
		}

		Optional<BigDecimal> lhs = exactNumber(left);
		Optional<BigDecimal> rhs = exactNumber(right);
		if(lhs.isPresent() && rhs.isPresent()) {
			int comparison = lhs.get().compareTo(rhs.get());
			return switch(op) {
				case GREATER -> exactBooleanLiteral(comparison > 0);
				case GREATEREQUAL -> exactBooleanLiteral(comparison >= 0);
				case LESS -> exactBooleanLiteral(comparison < 0);
				case LESSEQUAL -> exactBooleanLiteral(comparison <= 0);
				case EQUAL -> exactBooleanLiteral(comparison == 0);
				case NOTEQUAL -> exactBooleanLiteral(comparison != 0);
				default -> ScalarState.bottom();
			};
		}
		if(op == OpOp2.EQUAL || op == OpOp2.NOTEQUAL) {
			boolean equal = left.literal().canonicalValue().equals(right.literal().canonicalValue());
			return exactBooleanLiteral(op == OpOp2.EQUAL ? equal : !equal);
		}
		return ScalarState.unknown();
	}

	private static ScalarState exactBooleanLiteral(boolean value) {
		return ScalarState.exact(new ScalarLiteralFact(
			org.apache.sysds.common.Types.ValueType.BOOLEAN, Boolean.toString(value)));
	}

	private static Optional<BigDecimal> exactNumber(ScalarState scalar) {
		if(!scalar.isExact())
			return Optional.empty();
		try {
			return Optional.of(new BigDecimal(scalar.literal().canonicalValue()));
		}
		catch(NumberFormatException ignored) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> exactBoolean(ScalarState scalar) {
		if(!scalar.isExact())
			return Optional.empty();
		String value = scalar.literal().canonicalValue();
		if("true".equalsIgnoreCase(value) || "1".equals(value))
			return Optional.of(true);
		if("false".equalsIgnoreCase(value) || "0".equals(value))
			return Optional.of(false);
		return Optional.empty();
	}

	private static AbstractShapeFact dataGenShape(DataGenOp hop, List<ScalarState> scalars) {
		if(hop.getDataType() != DataType.MATRIX && hop.getDataType() != DataType.FRAME)
			return AbstractShapeFact.bottom(hop.getDataType());
		if(hop.getOp() == OpOpDG.SEQ)
			return new AbstractShapeFact(hop.getDataType(), DimensionFact.bottom(), DimensionFact.exact(1));
		int rowPosition = hop.getParamIndexMap().getOrDefault(DataExpression.RAND_ROWS, -1);
		int colPosition = hop.getParamIndexMap().getOrDefault(DataExpression.RAND_COLS, -1);
		return new AbstractShapeFact(hop.getDataType(), dimension(inputScalar(scalars, rowPosition)),
			dimension(inputScalar(scalars, colPosition)));
	}

	private static DimensionFact indexedDimension(boolean singleton, DimensionFact input,
		ScalarState lower, ScalarState upper, List<Hop> hopInputs, int dataPosition,
		int lowerPosition, int upperPosition, OpOp1 fullBoundOp) {
		if(singleton)
			return DimensionFact.exact(1);
		Optional<Long> lo = exactLong(lower);
		Optional<Long> hi = exactLong(upper);
		if(lo.isPresent() && hi.isPresent() && hi.get() >= lo.get())
			return DimensionFact.exact(hi.get() - lo.get() + 1);
		if(lo.orElse(-1L) == 1L && upperPosition < hopInputs.size()) {
			Hop upperHop = hopInputs.get(upperPosition);
			if(upperHop instanceof UnaryOp unary && unary.getOp() == fullBoundOp
				&& !upperHop.getInput().isEmpty() && upperHop.getInput(0) == hopInputs.get(dataPosition))
				return input;
		}
		// BOTTOM means a predecessor has not been visited yet. Publishing UNKNOWN here
		// would irreversibly poison the finite join lattice before the exact scalar bound
		// arrives later in the same closure (for example PCA's [,1:K] after a function-
		// literal/CFG refinement). Defer the result until all required bounds are known.
		if(lower.isBottom() || upper.isBottom())
			return DimensionFact.bottom();
		return DimensionFact.unknown();
	}

	private static AbstractShapeFact broadcastShape(DataType dataType, List<AbstractShapeFact> inputs) {
		List<AbstractShapeFact> matrices = inputs.stream().filter(AbstractShapeFact::isMatrix).toList();
		if(matrices.isEmpty())
			return AbstractShapeFact.bottom(dataType);
		DimensionFact rows = matrices.get(0).rows();
		DimensionFact cols = matrices.get(0).cols();
		for(int i = 1; i < matrices.size(); i++) {
			rows = broadcastDimension(rows, matrices.get(i).rows());
			cols = broadcastDimension(cols, matrices.get(i).cols());
		}
		return new AbstractShapeFact(dataType, rows, cols);
	}

	private static DimensionFact broadcastDimension(DimensionFact left, DimensionFact right) {
		if(left.knowledge() == DimensionKnowledge.BOTTOM || right.knowledge() == DimensionKnowledge.BOTTOM)
			return DimensionFact.bottom();
		if(left.isExact(1)) return right;
		if(right.isExact(1)) return left;
		return left.join(right);
	}

	private static AbstractShapeFact concreteSeed(Hop hop) {
		return AbstractShapeFact.fromConcrete(new NodeShapeFact(hop.getDataType(), hop.getDim1(), hop.getDim2()));
	}

	private static AbstractShapeFact inputShape(List<AbstractShapeFact> inputs, int position) {
		return position >= 0 && position < inputs.size() ? inputs.get(position)
			: AbstractShapeFact.bottom(DataType.UNKNOWN);
	}

	private static ScalarState inputScalar(List<ScalarState> inputs, int position) {
		return position >= 0 && position < inputs.size() ? inputs.get(position) : ScalarState.bottom();
	}

	private static DimensionFact dimension(ScalarState scalar) {
		Optional<Long> value = exactLong(scalar);
		return value.isPresent() && value.get() >= 0 ? DimensionFact.exact(value.get())
			: scalar.isBottom() ? DimensionFact.bottom() : DimensionFact.unknown();
	}

	private static ScalarState exactDimension(DimensionFact dimension) {
		return dimension.isExact() ? ScalarState.exact(new ScalarLiteralFact(
			org.apache.sysds.common.Types.ValueType.INT64, Long.toString(dimension.value())))
			: dimension.knowledge() == DimensionKnowledge.BOTTOM ? ScalarState.bottom() : ScalarState.unknown();
	}

	private static Optional<Long> exactLong(ScalarState scalar) {
		if(!scalar.isExact()) return Optional.empty();
		try {
			double value = Double.parseDouble(scalar.literal().canonicalValue());
			if(!Double.isFinite(value) || value != Math.rint(value))
				return Optional.empty();
			return Optional.of((long) value);
		}
		catch(NumberFormatException ignored) {
			return Optional.empty();
		}
	}

	private static Optional<String> exactString(ScalarState scalar) {
		return scalar.isExact() ? Optional.of(scalar.literal().canonicalValue()) : Optional.empty();
	}

	private static AbstractShapeFact completeShapeJoinHops(List<Hop> sources,
		Map<Hop,AbstractShapeFact> facts) {
		List<AbstractShapeFact> values = sources.stream()
			.map(source -> facts.getOrDefault(source, AbstractShapeFact.bottom(source.getDataType()))).toList();
		return completeShapeJoin(values);
	}

	private static ScalarState completeScalarJoinHops(List<Hop> sources, Map<Hop,ScalarState> facts) {
		return completeScalarJoin(sources.stream().map(source -> facts.getOrDefault(source, ScalarState.bottom())).toList());
	}

	private static AbstractShapeFact completeShapeJoinKeys(List<CompiledHopKey> sources,
		Map<CompiledHopKey,AbstractShapeFact> facts) {
		return completeShapeJoin(sources.stream().map(source -> facts.get(source)).toList());
	}

	private static ScalarState completeScalarJoinKeys(List<CompiledHopKey> sources,
		Map<CompiledHopKey,ScalarState> facts) {
		return completeScalarJoin(sources.stream().map(source -> facts.get(source)).toList());
	}

	private static AbstractShapeFact completeShapeJoin(List<AbstractShapeFact> facts) {
		if(facts.isEmpty()) return AbstractShapeFact.bottom(DataType.UNKNOWN);
		DataType dataType = DataType.UNKNOWN;
		for(AbstractShapeFact fact : facts)
			if(fact.dataType() != DataType.UNKNOWN) {
				if(dataType != DataType.UNKNOWN && dataType != fact.dataType()) dataType = DataType.UNKNOWN;
				else dataType = fact.dataType();
			}
		return new AbstractShapeFact(dataType,
			completeDimensionJoin(facts.stream().map(AbstractShapeFact::rows).toList()),
			completeDimensionJoin(facts.stream().map(AbstractShapeFact::cols).toList()));
	}

	private static DimensionFact completeDimensionJoin(List<DimensionFact> facts) {
		DimensionFact result = DimensionFact.bottom();
		for(DimensionFact fact : facts) result = result.join(fact);
		return result;
	}

	private static ScalarState completeScalarJoin(List<ScalarState> facts) {
		ScalarState result = ScalarState.bottom();
		for(ScalarState fact : facts) result = result.join(fact);
		return result;
	}

	private static List<AbstractShapeFact> orderedInputShapes(Hop hop,
		Map<Integer,List<CompiledHopKey>> inputs, Map<CompiledHopKey,AbstractShapeFact> facts) {
		List<AbstractShapeFact> result = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			List<CompiledHopKey> sources = inputs == null ? null : inputs.get(position);
			result.add(sources == null || sources.isEmpty()
				? AbstractShapeFact.bottom(hop.getInput(position).getDataType())
				: completeShapeJoinKeys(sources, facts));
		}
		return result;
	}

	private static List<ScalarState> orderedInputScalars(Hop hop,
		Map<Integer,List<CompiledHopKey>> inputs, Map<CompiledHopKey,ScalarState> facts) {
		List<ScalarState> result = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			List<CompiledHopKey> sources = inputs == null ? null : inputs.get(position);
			result.add(sources == null || sources.isEmpty() ? ScalarState.bottom()
				: completeScalarJoinKeys(sources, facts));
		}
		return result;
	}

	private static boolean carriesAbstractValue(String evidence) {
		return "function-input-binding".equals(evidence)
			|| "multi-return-output-value".equals(evidence)
			|| "logical-transient-input".equals(evidence)
			|| "function-formal-input".equals(evidence)
			|| "stable-origin".equals(evidence)
			|| evidence.startsWith("cfg-transient-value:")
			|| evidence.startsWith("cfg-function-output-value:")
			|| evidence.startsWith("function-argument:")
			|| evidence.startsWith("inlined-function-argument:")
			|| evidence.startsWith("function-result:")
			|| evidence.startsWith("inlined-function-result:");
	}

	private static AbstractShapeFact publish(AbstractShapeFact fact) {
		return new AbstractShapeFact(fact.dataType(), publish(fact.rows()), publish(fact.cols()));
	}

	private static void promoteUnresolvedKeyFacts(List<HopOccurrenceProjection> projections,
		Map<CompiledHopKey,AbstractShapeFact> shapes, Map<CompiledHopKey,ScalarState> scalars) {
		for(HopOccurrenceProjection projection : projections) {
			CompiledHopKey key = projection.key();
			shapes.put(key, promoteUnresolved(shapes.get(key)));
			if(projection.hop().getDataType() == DataType.SCALAR && scalars.get(key).isBottom())
				scalars.put(key, ScalarState.unknown());
		}
	}

	private static AbstractShapeFact promoteUnresolved(AbstractShapeFact fact) {
		if(fact.dataType() != DataType.MATRIX && fact.dataType() != DataType.FRAME)
			return fact;
		return new AbstractShapeFact(fact.dataType(), publish(fact.rows()), publish(fact.cols()));
	}

	private static DimensionFact publish(DimensionFact fact) {
		return fact.knowledge() == DimensionKnowledge.BOTTOM ? DimensionFact.unknown() : fact;
	}

	private static boolean functionMatches(FunctionOp call, String namespace) {
		return namespace.equals(call.getFunctionName()) || namespace.endsWith("::" + call.getFunctionName())
			|| namespace.endsWith("/" + call.getFunctionName());
	}
}

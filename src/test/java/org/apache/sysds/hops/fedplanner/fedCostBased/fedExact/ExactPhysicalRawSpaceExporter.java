/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Test-owned stream of the model's raw, unquotiented encoded assignments.
 * This is not a full E physical-plan exporter: the model may have omitted an
 * alternative before its domains were constructed.
 */
final class ExactPhysicalRawSpaceExporter {
	enum Status { EMITTED, REJECTED, UNKNOWN }

	record Row(BigInteger ordinal, List<Integer> values, List<String> alternativeSignatures,
		Status status, String reason) {
		Row {
			values = List.copyOf(values);
			alternativeSignatures = List.copyOf(alternativeSignatures);
			Objects.requireNonNull(status);
			Objects.requireNonNull(reason);
		}
	}

	static record ScopedFactor(ExactCategoricalSolver.Factor factor, int[] positions) { }
	static record Evaluation(Status status, String reason) { }

	private ExactPhysicalRawSpaceExporter() { }

	static BigInteger size(ExactPhysicalModel model) {
		Objects.requireNonNull(model, "model");
		BigInteger result = BigInteger.ONE;
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			if(domain.alternatives().isEmpty())
				throw new IllegalStateException("EXACT_RAW_DOMAIN_EMPTY");
			result = result.multiply(BigInteger.valueOf(domain.alternatives().size()));
		}
		return result;
	}

	/** Visits precisely [start, end), including rejected and unknown assignments. */
	static void visit(ExactPhysicalModel model, BigInteger start, BigInteger end,
		Consumer<Row> sink) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
		Objects.requireNonNull(sink, "sink");
		BigInteger size = size(model);
		if(start.signum() < 0 || end.compareTo(start) < 0 || end.compareTo(size) > 0)
			throw new IllegalArgumentException("EXACT_RAW_RANGE_INVALID");
		List<ScopedFactor> factors = scopes(model);
		for(BigInteger ordinal = start; ordinal.compareTo(end) < 0;
			ordinal = ordinal.add(BigInteger.ONE)) {
			int[] values = decode(ordinal, model.domains());
			Evaluation evaluation = evaluate(values, factors);
			List<Integer> valueList = new ArrayList<>(values.length);
			List<String> signatures = new ArrayList<>(values.length);
			for(int index = 0; index < values.length; index++) {
				valueList.add(values[index]);
				signatures.add(model.domains().get(index).alternatives().get(values[index]).signature());
			}
			sink.accept(new Row(ordinal, valueList, signatures,
				evaluation.status(), evaluation.reason()));
		}
	}

	/** A decisive rejection wins even if an earlier factor could not be decoded. */
	static Evaluation evaluate(int[] values, List<ScopedFactor> factors) {
		String unknown = "";
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			ScopedFactor scoped = factors.get(factorIndex);
			int[] local = Arrays.stream(scoped.positions()).map(position -> values[position]).toArray();
			try {
				double cost = scoped.factor().cost(local);
				if(cost == Double.POSITIVE_INFINITY)
					return new Evaluation(Status.REJECTED, "hard-factor:" + factorIndex);
				if((cost != 0.0 || Double.doubleToRawLongBits(cost) ==
					Double.doubleToRawLongBits(-0.0d)) && unknown.isEmpty())
					unknown = "non-boolean-hard-factor:" + factorIndex;
			}
			catch(RuntimeException exception) {
				if(unknown.isEmpty())
					unknown = "hard-factor-error:" + factorIndex + ':'
						+ exception.getClass().getSimpleName();
			}
		}
		return unknown.isEmpty() ? new Evaluation(Status.EMITTED, "")
			: new Evaluation(Status.UNKNOWN, unknown);
	}

	private static int[] decode(BigInteger ordinal,
		List<ExactPhysicalModel.DecisionDomain> domains) {
		int[] values = new int[domains.size()];
		BigInteger remainder = ordinal;
		for(int index = domains.size() - 1; index >= 0; index--) {
			BigInteger[] quotient = remainder.divideAndRemainder(
				BigInteger.valueOf(domains.get(index).alternatives().size()));
			values[index] = quotient[1].intValueExact();
			remainder = quotient[0];
		}
		if(remainder.signum() != 0)
			throw new IllegalStateException("EXACT_RAW_DECODE_OVERFLOW");
		return values;
	}

	private static List<ScopedFactor> scopes(ExactPhysicalModel model) {
		List<ExactCategoricalSolver.Variable> variables = model.variables();
		Map<ExactCategoricalSolver.Variable,Integer> indexes = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			if(indexes.put(variables.get(index), index) != null)
				throw new IllegalStateException("EXACT_RAW_DUPLICATE_VARIABLE");
		List<ScopedFactor> result = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : model.hardFactors()) {
			int[] positions = new int[factor.scope().size()];
			for(int index = 0; index < positions.length; index++) {
				Integer position = indexes.get(factor.scope().get(index));
				if(position == null)
					throw new IllegalStateException("EXACT_RAW_FACTOR_VARIABLE_UNKNOWN");
				positions[index] = position;
			}
			result.add(new ScopedFactor(factor, positions));
		}
		return result;
	}
}

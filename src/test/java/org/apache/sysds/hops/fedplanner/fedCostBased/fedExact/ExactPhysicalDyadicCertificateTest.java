/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ExactPhysicalDyadicCertificateTest {
	@Test
	public void physicalSurfaceDerivesNumericBoundAndBindsFrozenFactorOrder() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, factor -> { });
		var certificate = ExactDyadicCosts.certify(surface);
		var oracle = oracle(surface.dyadicCostTransport().canonical());

		assertTrue(certificate.hasPhysicalAuthority());
		assertTrue(certificate.supported());
		assertEquals(oracle.q(), certificate.q());
		assertEquals(oracle.maximum().bitLength(), certificate.maximumSumBits());
		assertEquals(surface.contributions().size(), certificate.canonicalContributionCount());
		int nonzero = 0;
		for(var encoding : surface.dyadicCostTransport().canonical()) {
			var monetary = monetary(encoding.transport());
			if(monetary != null && maximum(monetary) > 0d)
				nonzero++;
		}
		assertTrue("fixture must contain identically positive-zero contributions",
			nonzero < certificate.canonicalContributionCount());
		assertEquals(nonzero, certificate.accumulationContributionCount());
		int accumulationBits = nonzero <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(nonzero - 1);
		assertEquals(accumulationBits, certificate.ceilLog2AccumulationCount());
		assertEquals(oracle.maximum().bitLength() + accumulationBits,
			certificate.bitsWithAccumulationHeadroom());
		certificate.validateSurface(surface);
		certificate.validateSourceFactors(surface.exactSolverFactors());

		List<ExactCategoricalSolver.Factor> replaced = new ArrayList<>(surface.exactSolverFactors());
		ExactCategoricalSolver.Factor first = replaced.get(0);
		double[] copied = values(first);
		replaced.set(0, ExactCategoricalSolver.Factor.dense(first.scope(), copied));
		assertNotSame(first, replaced.get(0));
		assertThrows(IllegalArgumentException.class,
			() -> certificate.validateSourceFactors(replaced));

		List<ExactCategoricalSolver.Factor> reordered = new ArrayList<>(surface.exactSolverFactors());
		if(reordered.size() > 1) {
			var held = reordered.get(0);
			reordered.set(0, reordered.get(1));
			reordered.set(1, held);
			assertThrows(IllegalArgumentException.class,
				() -> certificate.validateSourceFactors(reordered));
		}
	}

	@Test
	public void foreignOwnerAndFreshSurfaceObjectsCannotReuseAuthority() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		var wrapper = new ExactPhysicalCostModel.PhysicalCostSurface(surface.owner(),
			surface.ownerFingerprint(), surface.variables(), surface.contributions(), surface.transferKeys(),
			surface.contributionFingerprint(), surface.exactSolverVariables(),
			surface.exactSolverFactors(), surface.dyadicCostTransport());
		assertThrows(IllegalArgumentException.class, () -> certificate.validateSurface(wrapper));
		var sameOwnerFreshSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		assertThrows(IllegalArgumentException.class,
			() -> certificate.validateSurface(sameOwnerFreshSurface));

		var foreignAnalysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var foreignSurface = ExactPhysicalCostModel.physicalCostSurface(foreignAnalysis,
			ExactPhysicalModel.build(foreignAnalysis));
		assertThrows(IllegalArgumentException.class,
			() -> certificate.validateSurface(foreignSurface));
	}

	@Test
	public void allFourConstructionCasesCarryTypedMetadataWithoutDescriptorParsing() {
		for(var constructor : ExactActivationClassFactorDecomposition.Decomposition.class
			.getDeclaredConstructors())
			assertTrue("zero proof construction must stay inside the decomposition factory",
				Modifier.isPrivate(constructor.getModifiers()));
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var consumer = new ExactCategoricalSolver.Variable("consumer", 2);
		var unresolved = activation(source, List.of(
			demand(consumer, 0.6, "a"), demand(consumer, 0.6, "b"), demand(consumer, 0.6, "c")));
		assertEquals(ExactPhysicalCostModel.CostTransportKind.IDENTITY,
			unresolved.factorization().costTransportKind());
		assertMaximumTransport(unresolved);

		var resolved = activation(source, List.of(new ExactPhysicalCostModel.ActivationDemand(
			List.of(consumer), List.of(new boolean[] {false, true}),
			new ExactMaterializationActivation.Event(1, List.of()))));
		assertEquals(ExactPhysicalCostModel.CostTransportKind.ONE_MONETARY_TABLE,
			resolved.factorization().costTransportKind());
		assertMaximumTransport(resolved);

		var zero = activation(source, List.of());
		assertEquals(ExactPhysicalCostModel.CostTransportKind.ZERO,
			zero.factorization().costTransportKind());
		assertMaximumTransport(zero);

		var target = new ExactCategoricalSolver.Variable("target", 2);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target), values -> values[1]);
		var projection = ExactPhysicalCostModel.projectNativeLocalSource("typed", canonical,
			new int[] {0, 0});
		assertTrue(projection.costTransport()
			instanceof ExactPhysicalCostModel.CostTransportDeclaration.OneMonetaryTable);
		assertMaximumTransport(new Factorized(canonical, projection));
	}

	@Test
	public void denseInputMutationCannotChangeFrozenIdentityTable() {
		var variable = new ExactCategoricalSolver.Variable("dense", 2);
		double[] caller = {0.25, 2.0};
		var factor = ExactCategoricalSolver.Factor.dense(List.of(variable), caller);
		caller[1] = 99;
		assertEquals(Double.doubleToRawLongBits(2.0),
			Double.doubleToRawLongBits(factor.denseCostAt(1)));
	}

	@Test
	public void projectedMonetaryTransportPublishesTheFrozenCallbackSnapshot() {
		var source = new ExactCategoricalSolver.Variable("snapshot-source", 4);
		var target = new ExactCategoricalSolver.Variable("snapshot-target", 2);
		int[] classes = {0, 1, 0, 1};
		AtomicInteger generation = new AtomicInteger(1);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target), values ->
			generation.get() * (1 + classes[values[0]] + values[1]));
		var factorization = ExactPhysicalCostModel.projectNativeLocalSource(
			"typed-snapshot", canonical, classes);
		var declaration = (ExactPhysicalCostModel.CostTransportDeclaration.OneMonetaryTable)
			factorization.costTransport();
		assertTrue(declaration.factor() == factorization.ordinaryFactors().get(0));
		var frozen = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactCategoricalSolver.Factor>();
		frozen.put(declaration.factor(),
			ExactCategoricalSolver.freezeValidatedFactor(declaration.factor()));
		var published = factorization.canonicalFactorAfterFreeze(canonical, frozen);
		generation.set(17);
		assertEquals(Double.doubleToRawLongBits(3),
			Double.doubleToRawLongBits(published.cost(new int[] {1, 1})));
	}

	private static Factorized activation(
		ExactCategoricalSolver.Variable source,
		List<ExactPhysicalCostModel.ActivationDemand> demands) {
		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("typed", source,
			new boolean[] {false, true}, new double[] {1, 2}, demands, 1,
			canonical, factorizations, null);
		return new Factorized(canonical.get(0), factorizations.get(canonical.get(0)));
	}

	private static void assertMaximumTransport(Factorized value) {
		double canonicalMaximum = maximum(value.canonical());
		var declaration = value.factorization().costTransport();
		double encodedMaximum;
		if(declaration instanceof ExactPhysicalCostModel.CostTransportDeclaration.Identity identity)
			encodedMaximum = maximum(ExactCategoricalSolver.freezeValidatedFactor(identity.factor()));
		else if(declaration instanceof ExactPhysicalCostModel.CostTransportDeclaration.OneMonetaryTable one)
			encodedMaximum = maximum(ExactCategoricalSolver.freezeValidatedFactor(one.factor()));
		else
			encodedMaximum = 0;
		assertEquals(Double.doubleToRawLongBits(canonicalMaximum),
			Double.doubleToRawLongBits(encodedMaximum));
	}

	private static double maximum(ExactCategoricalSolver.Factor factor) {
		double maximum = 0;
		int[] assignment = new int[factor.scope().size()];
		int cells = factor.scope().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = assignment.length - 1; axis >= 0; axis--) {
				assignment[axis] = remaining % factor.scope().get(axis).domainSize();
				remaining /= factor.scope().get(axis).domainSize();
			}
			maximum = Math.max(maximum, factor.cost(assignment));
		}
		return maximum;
	}

	private static ExactPhysicalCostModel.ActivationDemand demand(
		ExactCategoricalSolver.Variable consumer, double weight, String branch) {
		return new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
			List.of(new boolean[] {false, true}), new ExactMaterializationActivation.Event(weight,
				List.of(new ExactPhysicalCostModel.BranchLiteral(branch, true))));
	}

	private static double[] values(ExactCategoricalSolver.Factor factor) {
		int cells = factor.scope().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = factor.denseCostAt(cell);
		return values;
	}

	private static Oracle oracle(List<ExactPhysicalCostModel.ContributionEncoding> encodings) {
		int q = Integer.MAX_VALUE;
		double[] maxima = new double[encodings.size()];
		for(int ordinal = 0; ordinal < encodings.size(); ordinal++) {
			ExactCategoricalSolver.Factor factor = monetary(encodings.get(ordinal).transport());
			if(factor == null)
				continue;
			for(double value : values(factor)) {
				maxima[ordinal] = Math.max(maxima[ordinal], value);
				if(value > 0)
					q = Math.min(q, exponent(value));
			}
		}
		if(q == Integer.MAX_VALUE)
			q = 0;
		BigInteger maximum = BigInteger.ZERO;
		for(double value : maxima)
			if(value > 0) {
				long bits = Double.doubleToRawLongBits(value);
				long fraction = bits & ((1L << 52) - 1);
				int encodedExponent = (int)((bits >>> 52) & 0x7ffL);
				long significand = encodedExponent == 0 ? fraction : (1L << 52) | fraction;
				int trailing = Long.numberOfTrailingZeros(significand);
				maximum = maximum.add(BigInteger.valueOf(significand >>> trailing)
					.shiftLeft(exponent(value) - q));
			}
		return new Oracle(q, maximum);
	}

	private static ExactCategoricalSolver.Factor monetary(
		ExactPhysicalCostModel.FrozenCostTransport transport) {
		if(transport instanceof ExactPhysicalCostModel.FrozenCostTransport.Identity identity)
			return identity.factor();
		if(transport instanceof ExactPhysicalCostModel.FrozenCostTransport.OneMonetaryTable one)
			return one.factor();
		return null;
	}

	private static int exponent(double value) {
		long bits = Double.doubleToRawLongBits(value);
		long fraction = bits & ((1L << 52) - 1);
		int encoded = (int)((bits >>> 52) & 0x7ffL);
		long significand = encoded == 0 ? fraction : (1L << 52) | fraction;
		return (encoded == 0 ? -1074 : encoded - 1023 - 52)
			+ Long.numberOfTrailingZeros(significand);
	}

	private record Oracle(int q, BigInteger maximum) { }
	private record Factorized(ExactCategoricalSolver.Factor canonical,
		ExactPhysicalCostModel.SolverFactorization factorization) { }
}

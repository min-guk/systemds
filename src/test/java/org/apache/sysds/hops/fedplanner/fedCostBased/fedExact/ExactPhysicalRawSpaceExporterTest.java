/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalRawSpaceExporterTest {
	@Test
	public void splitRangesEqualUnsplitAndKeepAlternativeIdentity() throws Exception {
		ExactPhysicalModel model = boundedModel();
		BigInteger end = ExactPhysicalRawSpaceExporter.size(model).min(BigInteger.valueOf(64));
		Assert.assertTrue(end.compareTo(BigInteger.ONE) > 0);
		BigInteger cut = end.divide(BigInteger.TWO);
		List<ExactPhysicalRawSpaceExporter.Row> unsplit = new ArrayList<>();
		List<ExactPhysicalRawSpaceExporter.Row> split = new ArrayList<>();
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO, end, unsplit::add);
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO, cut, split::add);
		ExactPhysicalRawSpaceExporter.visit(model, cut, end, split::add);
		Assert.assertEquals(unsplit, split);
		Assert.assertEquals(end.intValueExact(), unsplit.size());
		for(int ordinal = 0; ordinal < unsplit.size(); ordinal++) {
			var row = unsplit.get(ordinal);
			Assert.assertEquals(BigInteger.valueOf(ordinal), row.ordinal());
			Assert.assertEquals(model.domains().size(), row.values().size());
			for(int index = 0; index < row.values().size(); index++)
				Assert.assertEquals(model.domains().get(index).alternatives()
					.get(row.values().get(index)).signature(), row.alternativeSignatures().get(index));
		}
	}

	@Test
	public void invalidAndMissingRangesFailClosed() throws Exception {
		ExactPhysicalModel model = boundedModel();
		BigInteger size = ExactPhysicalRawSpaceExporter.size(model);
		assertInvalidRange(model, BigInteger.ONE.negate(), BigInteger.ZERO);
		assertInvalidRange(model, BigInteger.ONE, BigInteger.ZERO);
		assertInvalidRange(model, BigInteger.ZERO, size.add(BigInteger.ONE));
		List<ExactPhysicalRawSpaceExporter.Row> empty = new ArrayList<>();
		ExactPhysicalRawSpaceExporter.visit(model, size, size, empty::add);
		Assert.assertTrue(empty.isEmpty());
	}

	@Test
	public void directFactorEvaluationDoesNotRequireOptimizerOrCostSurface() throws Exception {
		ExactPhysicalModel model = boundedModel();
		Assert.assertFalse(model.costSurfaceComplete());
		List<ExactPhysicalRawSpaceExporter.Row> rows = new ArrayList<>();
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO,
			ExactPhysicalRawSpaceExporter.size(model).min(BigInteger.valueOf(128)), rows::add);
		Assert.assertFalse(rows.isEmpty());
		for(var row : rows)
			Assert.assertNotEquals(ExactPhysicalRawSpaceExporter.Status.UNKNOWN, row.status());
	}

	@Test
	public void laterDecisiveRejectionWinsOverUndecodableFactors() {
		var variable = new ExactCategoricalSolver.Variable("x", 2);
		var invalid = new ExactPhysicalRawSpaceExporter.ScopedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(variable), value -> Double.NaN), new int[] {0});
		var failing = new ExactPhysicalRawSpaceExporter.ScopedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(variable), value -> {
				throw new IllegalStateException("factor failed");
			}), new int[] {0});
		var reject = new ExactPhysicalRawSpaceExporter.ScopedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(variable), value -> Double.POSITIVE_INFINITY),
			new int[] {0});
		Assert.assertEquals(new ExactPhysicalRawSpaceExporter.Evaluation(
			ExactPhysicalRawSpaceExporter.Status.REJECTED, "hard-factor:2"),
			ExactPhysicalRawSpaceExporter.evaluate(new int[] {0}, List.of(invalid, failing, reject)));
		Assert.assertEquals(new ExactPhysicalRawSpaceExporter.Evaluation(
			ExactPhysicalRawSpaceExporter.Status.UNKNOWN, "non-boolean-hard-factor:0"),
			ExactPhysicalRawSpaceExporter.evaluate(new int[] {0}, List.of(invalid, failing)));
		Assert.assertEquals(new ExactPhysicalRawSpaceExporter.Evaluation(
			ExactPhysicalRawSpaceExporter.Status.UNKNOWN, "hard-factor-error:0:IllegalStateException"),
			ExactPhysicalRawSpaceExporter.evaluate(new int[] {0}, List.of(failing)));
	}

	private static void assertInvalidRange(ExactPhysicalModel model, BigInteger start,
		BigInteger end) {
		try {
			ExactPhysicalRawSpaceExporter.visit(model, start, end, row -> { });
			Assert.fail("range accepted: " + start + ".." + end);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("EXACT_RAW_RANGE_INVALID", expected.getMessage());
		}
	}

	private static ExactPhysicalModel boundedModel() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			program);
		return ExactPhysicalModel.build(analysis);
	}
}

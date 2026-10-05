/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** SOLVE is a linear-algebra shape transfer, not an elementwise broadcast. */
public class PlacementSolveAbstractShapeTest {
	@Test
	public void solveRetainsResponseColumnsForSquareAndRectangularInputs() {
		for(long rows : new long[] {128, 500}) {
			DataOp a = matrix("A", rows, 128);
			DataOp b = matrix("b", rows, 1);
			BinaryOp beta = solve(a, b);
			var shape = infer(List.of(a, b, beta)).shapes().get(beta);
			Assert.assertTrue("Solve result rows come from A columns: " + shape,
				shape.rows().isExact(128));
			Assert.assertTrue("Solve must preserve the one-column response: " + shape,
				shape.cols().isExact(1));
		}
	}

	@Test
	public void changingFeatureCountDoesNotErasePredictionColumnCount() {
		DataOp a = matrix("A", -1, -1);
		DataOp b = matrix("b", -1, 1);
		BinaryOp beta = solve(a, b);
		DataOp x = matrix("X", 50000, -1);
		AggBinaryOp prediction = new AggBinaryOp("prediction", DataType.MATRIX, ValueType.FP64,
			org.apache.sysds.common.Types.OpOp2.MULT, org.apache.sysds.common.Types.AggOp.SUM, x, beta);
		var facts = infer(List.of(a, b, beta, x, prediction));
		var betaShape = facts.shapes().get(beta);
		Assert.assertEquals(DimensionKnowledge.UNKNOWN, betaShape.rows().knowledge());
		Assert.assertTrue("Unknown feature count must not erase the response axis", betaShape.cols().isExact(1));
		var shape = facts.shapes().get(prediction);
		Assert.assertTrue("Prediction rows remain the sample count", shape.rows().isExact(50000));
		Assert.assertTrue("Prediction is 50000x1, not a sentinel-sized matrix", shape.cols().isExact(1));
	}

	@Test
	public void unknownResponseColumnsAreNotInventedFromCoefficientColumns() {
		DataOp a = matrix("A", 128, 128);
		DataOp b = matrix("b", 128, -1);
		BinaryOp beta = solve(a, b);
		var shape = infer(List.of(a, b, beta)).shapes().get(beta);
		Assert.assertTrue(shape.rows().isExact(128));
		Assert.assertEquals(DimensionKnowledge.UNKNOWN, shape.cols().knowledge());
	}

	private static PlacementAbstractShapeAnalysis.HopFacts infer(List<Hop> hops) {
		FunctionCallGraph calls = new FunctionCallGraph(new DMLProgram());
		return PlacementAbstractShapeAnalysis.inferOriginalOccurrences(hops,
			Collections.nCopies(hops.size(), "main"), Collections.nCopies(hops.size(), Set.of()),
			Collections.nCopies(hops.size(), false), calls, new FunctionCallSizeInfo(calls));
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, -1, 1000);
	}

	private static BinaryOp solve(Hop a, Hop b) {
		BinaryOp result = new BinaryOp("beta", DataType.MATRIX, ValueType.FP64, OpOp2.SOLVE, a, b);
		// Exercise the common analysis independently of concrete HOP dimension inference.
		result.setDim1(-1);
		result.setDim2(-1);
		return result;
	}
}

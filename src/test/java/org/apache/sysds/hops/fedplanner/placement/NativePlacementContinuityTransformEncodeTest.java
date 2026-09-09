/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Assert;
import org.junit.Test;

public class NativePlacementContinuityTransformEncodeTest {
	@Test
	public void rowDummycodePrimaryOutputPreservesPool() {
		TransformHops transform = transform("transformencode",
			new LiteralOp("{\"ids\":true,\"dummycode\":[1]}"));

		Assert.assertTrue(NativePlacementContinuity.transformEncodePreservesPool(transform.encoded, FType.ROW));
	}

	@Test
	public void columnMetadataAndOtherBuiltinsDoNotPreserveTransformPool() {
		TransformHops transform = transform("transformencode",
			new LiteralOp("{\"ids\":true,\"dummycode\":[1]}"));
		TransformHops other = transform("eigen", new LiteralOp("{}"));

		Assert.assertFalse(NativePlacementContinuity.transformEncodePreservesPool(transform.encoded, FType.COL));
		Assert.assertFalse(NativePlacementContinuity.transformEncodePreservesPool(transform.metadata, FType.ROW));
		Assert.assertFalse(NativePlacementContinuity.transformEncodePreservesPool(other.encoded, FType.ROW));
	}

	@Test
	public void rowOmitAndUnknownSpecsDoNotPreservePool() {
		TransformHops omit = transform("transformencode",
			new LiteralOp("{\"ids\":true,\"recode\":[1],\"omit\":[1]}"));
		Hop unresolvedSpec = new DataOp("spec", DataType.SCALAR, ValueType.STRING,
			OpOpData.TRANSIENTREAD, "spec", 0, 0, -1, 1000);
		TransformHops unknown = transform("transformencode", unresolvedSpec);

		Assert.assertFalse(NativePlacementContinuity.transformEncodePreservesPool(omit.encoded, FType.ROW));
		Assert.assertFalse(NativePlacementContinuity.transformEncodePreservesPool(unknown.encoded, FType.ROW));
	}

	private static TransformHops transform(String functionName, Hop spec) {
		DataOp frame = new DataOp("F", DataType.FRAME, ValueType.STRING,
			OpOpData.TRANSIENTREAD, "F", 4, 2, -1, 1000);
		DataOp encoded = new DataOp("X0", DataType.MATRIX, ValueType.FP64,
			frame, OpOpData.FUNCTIONOUTPUT, "X0");
		DataOp metadata = new DataOp("M", DataType.FRAME, ValueType.STRING,
			frame, OpOpData.FUNCTIONOUTPUT, "M");
		new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "_internal", functionName,
			null, List.of(frame, spec), new String[] {"X0", "M"},
			new ArrayList<>(List.of(encoded, metadata)));
		return new TransformHops(encoded, metadata);
	}

	private record TransformHops(DataOp encoded, DataOp metadata) { }
}

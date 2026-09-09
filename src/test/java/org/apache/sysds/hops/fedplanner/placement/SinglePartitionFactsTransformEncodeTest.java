/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DataExpression;
import org.junit.Assert;
import org.junit.Test;

public class SinglePartitionFactsTransformEncodeTest {
	@Test
	public void primaryOutputCarriesOneRangeFrameCardinality() {
		TransformHops transform = transform(frameSource("localhost:1234/F"));
		Hop local = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(3L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		BinaryOp comparison = new BinaryOp("comparison", DataType.MATRIX, ValueType.FP64,
			OpOp2.LESS, transform.encoded, local);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(transform.frame, transform.encoded, transform.metadata, local, comparison), Map.of(), Set.of());

		Assert.assertTrue("transformencode maps one input range to one primary-output range",
			facts.isSinglePartition(transform.encoded));
		Assert.assertEquals(Optional.of(true),
			facts.fullInputHint(comparison, Arrays.asList(FType.FULL, null)));
		Assert.assertFalse("coordinator-local metadata is not a FULL cardinality carrier",
			facts.isSinglePartition(transform.metadata));
	}

	@Test
	public void primaryOutputDoesNotCollapseMultipleFrameRanges() {
		TransformHops transform = transform(frameSource("localhost:1234/F1", "localhost:1234/F2"));
		Hop local = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(3L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		BinaryOp comparison = new BinaryOp("comparison", DataType.MATRIX, ValueType.FP64,
			OpOp2.LESS, transform.encoded, local);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(transform.frame, transform.encoded, transform.metadata, local, comparison), Map.of(), Set.of());

		Assert.assertFalse(facts.isSinglePartition(transform.encoded));
		Assert.assertEquals(Optional.empty(),
			facts.fullInputHint(comparison, Arrays.asList(FType.FULL, null)));
	}

	@Test
	public void matrixToFrameCastCarriesOneRangeIntoPrimaryOutput() {
		Hop frame = castAsFrame(matrixSource("localhost:1234/A"));
		TransformHops transform = transform(frame);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(frame.getInput(0), frame, transform.encoded, transform.metadata), Map.of(), Set.of());

		Assert.assertTrue("native CAST_AS_FRAME preserves its one input federation range",
			facts.isSinglePartition(frame));
		Assert.assertTrue(facts.isSinglePartition(transform.encoded));
	}

	@Test
	public void matrixToFrameCastDoesNotCollapseMultipleRanges() {
		Hop frame = castAsFrame(matrixSource("localhost:1234/A1", "localhost:1234/A2"));
		TransformHops transform = transform(frame);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(frame.getInput(0), frame, transform.encoded, transform.metadata), Map.of(), Set.of());

		Assert.assertFalse(facts.isSinglePartition(frame));
		Assert.assertFalse(facts.isSinglePartition(transform.encoded));
	}

	private static TransformHops transform(Hop frame) {
		DataOp encoded = new DataOp("X0", DataType.MATRIX, ValueType.FP64,
			frame, OpOpData.FUNCTIONOUTPUT, "X0");
		DataOp metadata = new DataOp("M", DataType.FRAME, ValueType.STRING,
			frame, OpOpData.FUNCTIONOUTPUT, "M");
		new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "_internal", "transformencode",
			null, List.of(frame, new LiteralOp("{ids:true,recode:[1]}")), new String[] {"X0", "M"},
			new ArrayList<>(List.of(encoded, metadata)));
		return new TransformHops(frame, encoded, metadata);
	}

	private static DataOp frameSource(String... addresses) {
		return federatedSource("F", DataType.FRAME, ValueType.STRING, addresses);
	}

	private static DataOp matrixSource(String... addresses) {
		return federatedSource("A", DataType.MATRIX, ValueType.FP64, addresses);
	}

	private static UnaryOp castAsFrame(Hop matrix) {
		return new UnaryOp("F", DataType.FRAME, ValueType.STRING, OpOp1.CAST_AS_FRAME, matrix);
	}

	private static DataOp federatedSource(String name, DataType dataType, ValueType valueType,
		String... addresses) {
		Hop[] addressHops = new Hop[addresses.length];
		Hop[] ranges = new Hop[addresses.length * 2];
		for(int index = 0; index < addresses.length; index++) {
			addressHops[index] = new LiteralOp(addresses[index]);
			ranges[2 * index] = list(new LiteralOp(0L), new LiteralOp(0L));
			ranges[2 * index + 1] = list(new LiteralOp(4L), new LiteralOp(2L));
		}
		HashMap<String, Hop> params = new HashMap<>();
		params.put(DataExpression.FED_ADDRESSES, list(addressHops));
		params.put(DataExpression.FED_RANGES, list(ranges));
		return new DataOp(name, dataType, valueType, OpOpData.FEDERATED, params);
	}

	private static NaryOp list(Hop... inputs) {
		return new NaryOp("list", DataType.LIST, ValueType.UNKNOWN, OpOpN.LIST, inputs);
	}

	private record TransformHops(Hop frame, DataOp encoded, DataOp metadata) { }
}

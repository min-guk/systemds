/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DataExpression;
import org.junit.Assert;
import org.junit.Test;

public class SinglePartitionFactsTest {
	@Test
	public void lateConflictingDefinitionInvalidatesPreviouslyGroundedMatmul() {
		DataOp first = source("first", "localhost:1234/first");
		DataOp other = source("other", "localhost:1235/other");
		List<Hop> hops = new ArrayList<>(List.of(first, other));
		Hop delayed = other;
		// A reaching definition can close after the read and its consumer have
		// provisionally acquired the first source's endpoint.
		for(int index = 0; index < 32; index++) {
			delayed = new DataOp("later" + index, DataType.MATRIX, ValueType.FP64,
				delayed, OpOpData.TRANSIENTWRITE, "later" + index);
			hops.add(delayed);
		}
		DataOp read = read("joined");
		Hop local = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		AggBinaryOp product = new AggBinaryOp("product", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, local, read);
		hops.addAll(List.of(read, local, product));
		SinglePartitionFacts facts = new SinglePartitionFacts(hops,
			Map.of(read, List.of(first, delayed)), Set.of());
		Assert.assertFalse(facts.isSinglePartition(read));
		Assert.assertFalse("MM must discard its provisional certificate when its provider widens",
			facts.isSinglePartition(product));
	}

	@Test
	public void conflictingMutualMatmulsDoNotDependOnClosureTraversalOrder() {
		DataOp first = source("first", "localhost:1234/first");
		DataOp other = source("other", "localhost:1235/other");
		DataOp leftRead = read("left"), rightRead = read("right");
		AggBinaryOp left = new AggBinaryOp("left", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, first, rightRead);
		AggBinaryOp right = new AggBinaryOp("right", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, other, leftRead);
		List<Hop> hops = new ArrayList<>(List.of(first, other, leftRead, rightRead, left, right));
		for(int rotation = 0; rotation < hops.size(); rotation++) {
			java.util.Collections.rotate(hops, 1);
			SinglePartitionFacts facts = new SinglePartitionFacts(hops,
				Map.of(leftRead, List.of(left), rightRead, List.of(right)), Set.of());
			Assert.assertFalse("First MM must not retain an order-dependent endpoint", facts.isSinglePartition(left));
			Assert.assertFalse("Second MM must not retain an order-dependent endpoint", facts.isSinglePartition(right));
		}
	}

	@Test
	public void transposeSelfProductRetainsConservativeAllInputEvidence() {
		DataOp source = source("X", "localhost:1234/X");
		Hop transpose = HopRewriteUtils.createTranspose(source);
		AggBinaryOp product = new AggBinaryOp("tsmm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, transpose, source);
		Assert.assertNotEquals(org.apache.sysds.lops.MMTSJ.MMTSJType.NONE, product.checkTransposeSelf());
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(source, transpose, product), Map.of(), Set.of());
		Assert.assertTrue("Cardinality alone does not grant a native FULL/FOUT TSMM state",
			facts.isSinglePartition(product));
	}

	@Test
	public void localSelectionMatrixChainKeepsFullResultCardinality() {
		DataOp source = source("X", "localhost:1234/X");
		Hop selection = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(3L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		AggBinaryOp sample = new AggBinaryOp("sample", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, selection, source);
		DataOp write = new DataOp("sample", DataType.MATRIX, ValueType.FP64,
			sample, OpOpData.TRANSIENTWRITE, "sample");
		DataOp read = read("sample");
		AggBinaryOp next = new AggBinaryOp("next", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, selection, read);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(source, selection, sample, write, read, next), Map.of(read, List.of(write)), Set.of());
		Assert.assertEquals(Optional.of(true), facts.fullInputHint(sample, Arrays.asList(null, FType.FULL)));
		Assert.assertEquals("A legal local x single-FULL result copies its one native range",
			Optional.of(true), facts.fullInputHint(next, Arrays.asList(null, FType.FULL)));
		Assert.assertFalse("A coordinator value is not itself an existing single-range FULL input",
			facts.isSinglePartition(selection));
		Assert.assertEquals("No exact value geometry may be invented by a cardinality fact", -1, read.getDim2());
	}

	@Test
	public void mmResultProofDoesNotCertifyUnknownFullOperandsOrUngroundedLoops() {
		DataOp source = source("X", "localhost:1234/X"), unknown = read("unknown");
		AggBinaryOp mixed = new AggBinaryOp("mixed", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, source, unknown);
		DataOp loop = read("loop");
		Hop local = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		AggBinaryOp loopProduct = new AggBinaryOp("loopProduct", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, local, loop);
		DataOp loopWrite = new DataOp("loop", DataType.MATRIX, ValueType.FP64,
			loopProduct, OpOpData.TRANSIENTWRITE, "loop");
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(source, unknown, mixed, local, loop, loopProduct, loopWrite),
			Map.of(loop, List.of(loopWrite)), Set.of());
		Assert.assertEquals("Every FULL operand of the selected row needs its own proof",
			Optional.empty(), facts.fullInputHint(mixed, List.of(FType.FULL, FType.FULL)));
		Assert.assertFalse(facts.isSinglePartition(unknown));
		Assert.assertFalse("An unrelated single-range source cannot ground an MM cycle",
			facts.isSinglePartition(loop));
		Assert.assertFalse(facts.isSinglePartition(loopProduct));
	}

	@Test
	public void groundedGrowingLoopKeepsCardinalityWithoutKnowingValueDimensions() {
		DataOp source = source("A", "localhost:1234/A");
		DataOp carried = read("B");
		BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, carried, source);
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(source, carried, append),
			Map.of(carried, List.of(source, append)), Set.of());
		Assert.assertTrue(facts.isSinglePartition(carried));
		Assert.assertTrue(facts.isSinglePartition(append));
		Assert.assertEquals(Optional.of(true), facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
		Assert.assertEquals("Cardinality must not fabricate the growing value's dimensions", -1, carried.getDim2());
		Assert.assertEquals(Optional.empty(), facts.fullInputHint(append, List.of(FType.ROW, FType.ROW)));
	}

	@Test
	public void everyDefinitionMustBeGroundedAndOnTheSameEndpoint() {
		DataOp left = source("A", "localhost:1234/A");
		DataOp right = source("A", "localhost:1235/A");
		DataOp otherPath = source("A", "localhost:1234/another-file");
		DataOp same = read("same"), mixed = read("mixed"), cycle = read("cycle"), partial = read("partial");
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(left, right, otherPath, same, mixed, cycle, partial),
			Map.of(same, List.of(left, otherPath), mixed, List.of(left, right),
				cycle, List.of(cycle), partial, List.of(left, cycle)), Set.of());
		Assert.assertTrue(facts.isSinglePartition(same));
		Assert.assertFalse(facts.isSinglePartition(mixed));
		Assert.assertFalse(facts.isSinglePartition(cycle));
		Assert.assertFalse("A grounded sibling cannot hide an ungrounded SCC", facts.isSinglePartition(partial));
	}

	@Test
	public void incompleteOrForeignSourcesCannotBorrowARealSourcesCertificate() {
		DataOp source = source("A", "localhost:1234/A");
		DataOp incomplete = read("incomplete"), foreign = read("foreign"), foreignSource = read("not-owned");
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(source, incomplete, foreign),
			Map.of(incomplete, List.of(source), foreign, List.of(source, foreignSource)), Set.of(incomplete));
		Assert.assertFalse("An unrepresented function return is not an absent branch", facts.isSinglePartition(incomplete));
		Assert.assertFalse("An out-of-invocation dependency is not lattice bottom", facts.isSinglePartition(foreign));
	}

	@Test
	public void repeatedEndpointRangesAndAnUnknownFullInputAreNotSinglePartitionProofs() {
		DataOp source = source("A", "localhost:1234/A");
		DataOp multi = source("A", "localhost:1234/A", "localhost:1234/A2");
		DataOp unknown = read("A");
		BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, source, unknown);
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(source, multi, unknown, append), Map.of(), Set.of());
		Assert.assertFalse("One endpoint does not imply one range", facts.isSinglePartition(multi));
		Assert.assertFalse(facts.isSinglePartition(unknown));
		Assert.assertEquals(Optional.empty(), facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
		Assert.assertFalse(facts.isSinglePartition(append));
	}

	private static DataOp read(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, -1, -1, -1, 1000);
	}

	private static DataOp source(String name, String... addresses) {
		Hop[] addressHops = new Hop[addresses.length];
		Hop[] ranges = new Hop[addresses.length * 2];
		for(int index = 0; index < addresses.length; index++) {
			addressHops[index] = new LiteralOp(addresses[index]);
			ranges[2 * index] = list(new LiteralOp(0L), new LiteralOp(0L));
			ranges[2 * index + 1] = list(new LiteralOp(4L), new LiteralOp(2L));
		}
		HashMap<String,Hop> params = new HashMap<>();
		params.put(DataExpression.FED_ADDRESSES, list(addressHops));
		params.put(DataExpression.FED_RANGES, list(ranges));
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED, params);
	}

	private static NaryOp list(Hop... inputs) {
		return new NaryOp("list", DataType.LIST, ValueType.UNKNOWN, OpOpN.LIST, inputs);
	}
}

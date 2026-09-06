/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
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
	public void endpointIdentityChangeDoesNotReplayUnchangedCardinalityEvidence() {
		Assert.assertEquals(List.of(), SinglePartitionFacts.changedCardinalityEvidenceOrdinals(
			List.of("worker-a"), List.of("worker-b"), 1));
	}

	@Test
	public void knownToUnknownCardinalityEvidenceSeedsReplay() {
		Assert.assertEquals(List.of(0), SinglePartitionFacts.changedCardinalityEvidenceOrdinals(
			List.of("worker-a"), List.of(""), 1));
	}

	@Test
	public void unknownToKnownCardinalityEvidenceSeedsReplay() {
		Assert.assertEquals(List.of(0), SinglePartitionFacts.changedCardinalityEvidenceOrdinals(
			List.of(""), List.of("worker-a"), 1));
	}

	@Test
	public void unchangedUnknownCardinalityEvidenceDoesNotReplay() {
		Assert.assertEquals(List.of(), SinglePartitionFacts.changedCardinalityEvidenceOrdinals(
			List.of(""), List.of(""), 1));
	}

	@Test
	public void syntheticTailIsExcludedFromPhysicalReplayOrdinals() {
		Assert.assertEquals(List.of(), SinglePartitionFacts.changedCardinalityEvidenceOrdinals(
			List.of("worker-a", ""), List.of("worker-a", "worker-b"), 1));
	}
	@Test
	public void rexpandPreservesSingleEndpointWithoutInventingGeometry() {
		DataOp input = source("labels", "localhost:1234/labels");
		for(String direction : List.of("rows", "cols")) {
			Hop expanded = rexpand(input, direction);
			SinglePartitionFacts facts = new SinglePartitionFacts(List.of(input, expanded), Map.of(), Set.of());
			Assert.assertTrue("Native REXPAND copies one map entry for each input entry",
				facts.isSinglePartition(expanded));
			Assert.assertEquals("Cardinality proof must not stamp output dimensions", -1, expanded.getDim1());
		}
	}

	@Test
	public void rexpandCannotInventUnknownOrMultiRangeEndpoint() {
		for(DataOp input : List.of(read("unknownLabels"),
			source("splitLabels", "localhost:1234/part1", "localhost:1234/part2"))) {
			Hop expanded = rexpand(input, "cols");
			SinglePartitionFacts facts = new SinglePartitionFacts(List.of(input, expanded), Map.of(), Set.of());
			Assert.assertFalse("A shared host is not proof of one exact range", facts.isSinglePartition(expanded));
		}
	}

	private static Hop rexpand(Hop input, String direction) {
		LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
		params.put("target", input);
		params.put("max", new DataOp("dynamicMaximum", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "dynamicMaximum", 0, 0, -1, 1000));
		params.put("dir", new LiteralOp(direction));
		params.put("cast", new LiteralOp(true));
		params.put("ignore", new LiteralOp(true));
		return HopRewriteUtils.createParameterizedBuiltinOp(input, params, ParamBuiltinOp.REXPAND);
	}

	@Test
	public void nativeFullLeftIndexCarriesRhsEndpointIntoNextUpdate() {
		DataOp local = read("localZeros");
		DataOp rhs = source("probability", "localhost:1234/probability");
		Hop first = leftIndex(local, rhs);
		DataOp write = new DataOp("probabilities", DataType.MATRIX, ValueType.FP64,
			first, OpOpData.TRANSIENTWRITE, "probabilities");
		DataOp alias = read("probabilities");
		Hop second = leftIndex(alias, rhs);
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(local, rhs, first, write, alias, second),
			Map.of(alias, List.of(write)), Set.of());
		Assert.assertTrue("Native matrix update runs on its single FULL RHS endpoint",
			facts.isSinglePartition(first));
		Assert.assertEquals(Optional.of(true), facts.fullInputHint(second, List.of(FType.FULL, FType.FULL)));
		Assert.assertFalse("Output cardinality must not federate the local LHS", facts.isSinglePartition(local));
		Assert.assertEquals("No exact output geometry is synthesized", -1, alias.getDim1());
	}

	@Test
	public void fullLeftIndexCannotBorrowLhsForUnknownRhsOrConflictingWorker() {
		DataOp lhs = source("lhs", "localhost:1234/lhs");
		DataOp other = source("other", "localhost:1235/other");
		DataOp unknown = read("unknown");
		for(Hop rhs : List.of(other, unknown)) {
			Hop update = leftIndex(lhs, rhs);
			SinglePartitionFacts facts = new SinglePartitionFacts(List.of(lhs, other, unknown, update),
				Map.of(), Set.of());
			Assert.assertFalse("Matrix-RHS FULL LIX requires its own single RHS provider",
				facts.isSinglePartition(update));
		}
	}

	@Test
	public void scalarLeftIndexNeedsRemoteLhsAndMatrixRhsNeedsSingleRange() {
		DataOp lhs = source("lhs", "localhost:1234/lhs");
		DataOp local = read("local");
		DataOp multi = source("multi", "localhost:1234/part1", "localhost:1234/part2");
		Hop remoteUpdate = leftIndex(lhs, new LiteralOp(1L));
		Hop localUpdate = leftIndex(local, new LiteralOp(1L));
		Hop multiUpdate = leftIndex(lhs, multi);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(lhs, local, multi, remoteUpdate, localUpdate, multiUpdate), Map.of(), Set.of());
		Assert.assertTrue(facts.isSinglePartition(remoteUpdate));
		Assert.assertFalse(facts.isSinglePartition(localUpdate));
		Assert.assertFalse("Range count, not distinct hostname count, is the FULL contract",
			facts.isSinglePartition(multiUpdate));
	}

	private static Hop leftIndex(Hop lhs, Hop rhs) {
		return HopRewriteUtils.createLeftIndexingOp(lhs, rhs,
			new LiteralOp(1L), new LiteralOp(4L), new LiteralOp(1L), new LiteralOp(1L));
	}

	@Test
	public void fusedNaryAndReplaceChainKeepsFullCardinality() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp right = source("right", "localhost:1234/right");
		for(OpOpN opcode : List.of(OpOpN.PLUS, OpOpN.MULT, OpOpN.MIN, OpOpN.MAX)) {
			NaryOp nary = new NaryOp("fused", DataType.MATRIX, ValueType.FP64,
				opcode, new Hop[] {left, right, left});
			Hop ternary = HopRewriteUtils.createTernary(nary, new LiteralOp(0.5), left, OpOp3.PLUS_MULT);
			LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
			params.put("target", ternary);
			params.put("pattern", new LiteralOp(Double.POSITIVE_INFINITY));
			params.put("replacement", new LiteralOp(0.0));
			Hop replace = HopRewriteUtils.createParameterizedBuiltinOp(ternary, params, ParamBuiltinOp.REPLACE);
			BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
				OpOp2.CBIND, replace, left);
			SinglePartitionFacts facts = new SinglePartitionFacts(
				List.of(left, right, nary, ternary, replace, append), Map.of(), Set.of());
			Assert.assertTrue("Nary elementwise output copies its input map: " + opcode,
				facts.isSinglePartition(nary));
			Assert.assertTrue("Replacing values does not replace the input FederationMap",
				facts.isSinglePartition(replace));
			Assert.assertEquals(Optional.of(true),
				facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
		}
	}

	@Test
	public void naryAppendAndUnknownReplaceDoNotBorrowSingleEndpointEvidence() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp unknown = read("unknown");
		NaryOp append = new NaryOp("append", DataType.MATRIX, ValueType.FP64,
			OpOpN.CBIND, new Hop[] {left, left, left});
		LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
		params.put("target", unknown);
		params.put("pattern", new LiteralOp(1.0));
		params.put("replacement", new LiteralOp(0.0));
		Hop replace = HopRewriteUtils.createParameterizedBuiltinOp(unknown, params, ParamBuiltinOp.REPLACE);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(left, unknown, append, replace), Map.of(), Set.of());
		Assert.assertFalse("Nary concatenation requires its own topology proof", facts.isSinglePartition(append));
		Assert.assertFalse("Replacing unknown input does not obtain a source", facts.isSinglePartition(replace));
	}

	@Test
	public void rewrittenElementwiseTernaryKeepsSingleEndpointThroughAliases() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp right = source("right", "localhost:1234/right");
		for(OpOp3 opcode : List.of(OpOp3.PLUS_MULT, OpOp3.MINUS_MULT, OpOp3.IFELSE)) {
			Hop ternary = HopRewriteUtils.createTernary(left, new LiteralOp(0.5), right, opcode);
			DataOp write = new DataOp("result", DataType.MATRIX, ValueType.FP64,
				ternary, OpOpData.TRANSIENTWRITE, "result");
			DataOp read = read("result");
			BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64,
				OpOp2.CBIND, read, left);
			SinglePartitionFacts facts = new SinglePartitionFacts(
				List.of(left, right, ternary, write, read, append), Map.of(read, List.of(write)), Set.of());
			Assert.assertTrue("Native elementwise ternary copies its grounded input map: " + opcode,
				facts.isSinglePartition(ternary));
			Assert.assertEquals("A HOP fusion must not erase a downstream FULL proof: " + opcode,
				Optional.of(true), facts.fullInputHint(append, List.of(FType.FULL, FType.FULL)));
			Assert.assertEquals("Cardinality does not invent the alias geometry", -1, read.getDim2());
		}
	}

	@Test
	public void ternaryProofRequiresEveryMatrixInputOnOneKnownEndpoint() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp other = source("other", "localhost:1235/other");
		DataOp unknown = read("unknown");
		for(Hop right : List.of(other, unknown)) {
			Hop ternary = HopRewriteUtils.createTernary(left, new LiteralOp(0.5), right, OpOp3.PLUS_MULT);
			SinglePartitionFacts facts = new SinglePartitionFacts(
				List.of(left, other, unknown, ternary), Map.of(), Set.of());
			Assert.assertFalse("One valid input does not authorize a conflicting/unknown ternary map",
				facts.isSinglePartition(ternary));
		}
	}

	@Test
	public void naryCellProofRequiresEveryMatrixInputOnOneKnownEndpoint() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp other = source("other", "localhost:1235/other");
		DataOp unknown = read("unknown");
		for(Hop right : List.of(other, unknown)) {
			NaryOp nary = new NaryOp("nary", DataType.MATRIX, ValueType.FP64,
				OpOpN.MULT, new Hop[] {left, right, left});
			SinglePartitionFacts facts = new SinglePartitionFacts(
				List.of(left, other, unknown, nary), Map.of(), Set.of());
			Assert.assertFalse("An nary provider cannot hide an unknown or conflicting matrix input",
				facts.isSinglePartition(nary));
		}
	}

	@Test
	public void nonElementwiseTernaryDoesNotInheritInputCardinality() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp right = source("right", "localhost:1234/right");
		Hop ctable = HopRewriteUtils.createTernary(left, right, new LiteralOp(1.0), OpOp3.CTABLE);
		SinglePartitionFacts facts = new SinglePartitionFacts(List.of(left, right, ctable), Map.of(), Set.of());
		Assert.assertFalse("CTABLE changes output topology; the ternary Java class is not map-copy authority",
			facts.isSinglePartition(ctable));
	}

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
	public void localElementwiseCompanionKeepsFullResultCardinality() {
		DataOp weights = source("W", "localhost:1234/W");
		Hop localInner = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		BinaryOp weighted = new BinaryOp("weighted", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, weights, localInner);
		Hop localRight = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		AggBinaryOp next = new AggBinaryOp("next", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, weighted, localRight);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(weights, localInner, weighted, localRight, next), Map.of(), Set.of());
		Assert.assertEquals(Optional.of(true),
			facts.fullInputHint(weighted, Arrays.asList(FType.FULL, null)));
		Assert.assertEquals("A legal single-FULL elementwise result copies its provider's one range",
			Optional.of(true), facts.fullInputHint(next, Arrays.asList(FType.FULL, null)));
		Assert.assertFalse("The local companion is not itself a FULL cardinality source",
			facts.isSinglePartition(localInner));
	}


	@Test
	public void ewiseFullTransferRejectsConflictingAndUnknownSelectedFullInputs() {
		DataOp left = source("left", "localhost:1234/left");
		DataOp other = source("other", "localhost:1235/other");
		DataOp unknown = read("unknown");
		BinaryOp conflicting = new BinaryOp("conflicting", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, left, other);
		Hop local = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		AggBinaryOp downstream = new AggBinaryOp("downstream", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, conflicting, local);
		BinaryOp partiallyKnown = new BinaryOp("partiallyKnown", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, left, unknown);
		SinglePartitionFacts facts = new SinglePartitionFacts(
			List.of(left, other, unknown, conflicting, local, downstream, partiallyKnown), Map.of(), Set.of());
		Assert.assertFalse("Different one-range endpoints cannot produce one FULL result",
			facts.isSinglePartition(conflicting));
		Assert.assertEquals("A conflicting elementwise result cannot become a downstream FULL input",
			Optional.empty(), facts.fullInputHint(downstream, Arrays.asList(FType.FULL, null)));
		Assert.assertEquals("Every selected FULL operand needs its own exact one-range proof",
			Optional.empty(), facts.fullInputHint(partiallyKnown, List.of(FType.FULL, FType.FULL)));
	}

	@Test
	public void ungroundedElementwiseCycleIsUnknownForEveryHopOrder() {
		DataOp leftRead = read("left"), rightRead = read("right");
		Hop leftLocal = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		Hop rightLocal = HopRewriteUtils.createDataGenOpByVal(new LiteralOp(4L), new LiteralOp(4L),
			null, DataType.MATRIX, ValueType.FP64, 1);
		BinaryOp left = new BinaryOp("leftUpdate", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, leftRead, leftLocal);
		BinaryOp right = new BinaryOp("rightUpdate", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, rightRead, rightLocal);
		List<Hop> hops = new ArrayList<>(List.of(leftRead, rightRead, leftLocal, rightLocal, left, right));
		for(int rotation = 0; rotation < hops.size(); rotation++) {
			java.util.Collections.rotate(hops, 1);
			SinglePartitionFacts facts = new SinglePartitionFacts(hops,
				Map.of(leftRead, List.of(right), rightRead, List.of(left)), Set.of());
			Assert.assertFalse("Ungrounded left elementwise SCC must stay unknown", facts.isSinglePartition(left));
			Assert.assertFalse("Ungrounded right elementwise SCC must stay unknown", facts.isSinglePartition(right));
		}
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
		Assert.assertTrue("The potential FULL+local row may inherit the known provider;"
			+ " the selected FULL+FULL row remains rejected by fullInputHint", facts.isSinglePartition(append));
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

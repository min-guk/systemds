/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DimensionKnowledge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Append-specific abstract-shape transfer and authoritative concrete seed coverage. */
public class PlacementAppendAbstractShapeTest {
	@Test
	public void infersStraightCbindAndRbindShapes() {
		DataOp cLeft = matrix("cLeft", 4, 2);
		DataOp cRight = matrix("cRight", 4, 3);
		BinaryOp cbind = append("cbind", OpOp2.CBIND, cLeft, cRight);
		DataOp rTop = matrix("rTop", 3, 2);
		DataOp rBottom = matrix("rBottom", 4, 2);
		BinaryOp rbind = append("rbind", OpOp2.RBIND, rTop, rBottom);

		var facts = infer(List.of(cLeft, cRight, cbind, rTop, rBottom, rbind), Map.of(), Map.of());
		assertShape(facts.shapes().get(cbind), 4, 5);
		assertShape(facts.shapes().get(rbind), 7, 2);
	}

	@Test
	public void suppliedConcreteSeedClosesLoopWithoutInventingGrowingAxis() {
		DataOp federated = matrix("A", -1, -1);
		DataOp loopRead = matrix("B", -1, -1);
		BinaryOp loopAppend = append("Bnext", OpOp2.CBIND, loopRead, federated);
		ReorgOp transpose = new ReorgOp("tA", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.TRANS, federated);
		List<Hop> hops = List.of(federated, loopRead, loopAppend, transpose);
		Map<Hop,NodeShapeFact> supplied = new IdentityHashMap<>();
		supplied.put(federated, new NodeShapeFact(DataType.MATRIX, 4, 2));

		var facts = infer(hops, Map.of(loopRead, Set.of(0, 2)), supplied);
		AbstractShapeFact loopShape = facts.shapes().get(loopRead);
		Assert.assertTrue("Loop invariant rows remain exact", loopShape.rows().isExact(4));
		Assert.assertEquals("Loop-growing columns must reach UNKNOWN instead of freezing at 2 or 4",
			DimensionKnowledge.UNKNOWN, loopShape.cols().knowledge());
		assertShape(facts.shapes().get(transpose), 2, 4);
	}

	@Test
	public void unknownMismatchAndOverflowNeverProduceFalseExactDimensions() {
		DataOp mismatchLeft = matrix("mismatchLeft", 4, 2);
		DataOp mismatchRight = matrix("mismatchRight", 5, 3);
		BinaryOp mismatch = append("mismatch", OpOp2.CBIND, mismatchLeft, mismatchRight);
		DataOp unknownLeft = matrix("unknownLeft", -1, 2);
		DataOp unknownRight = matrix("unknownRight", 4, 3);
		BinaryOp unknown = append("unknown", OpOp2.CBIND, unknownLeft, unknownRight);
		DataOp overflowTop = matrix("overflowTop", Long.MAX_VALUE, 2);
		DataOp overflowBottom = matrix("overflowBottom", 1, 2);
		BinaryOp overflow = append("overflow", OpOp2.RBIND, overflowTop, overflowBottom);

		var facts = infer(List.of(mismatchLeft, mismatchRight, mismatch, unknownLeft, unknownRight,
			unknown, overflowTop, overflowBottom, overflow), Map.of(), Map.of());
		Assert.assertEquals("Conflicting invariant rows must be UNKNOWN", DimensionKnowledge.UNKNOWN,
			facts.shapes().get(mismatch).rows().knowledge());
		Assert.assertEquals("An unknown invariant row must remain UNKNOWN", DimensionKnowledge.UNKNOWN,
			facts.shapes().get(unknown).rows().knowledge());
		Assert.assertEquals("Overflow on the growing row axis must be UNKNOWN", DimensionKnowledge.UNKNOWN,
			facts.shapes().get(overflow).rows().knowledge());
		Assert.assertTrue("Unaffected invariant columns remain exact",
			facts.shapes().get(overflow).cols().isExact(2));
	}

	private static PlacementAbstractShapeAnalysis.HopFacts infer(List<Hop> hops,
		Map<Hop,Set<Integer>> reachingByHop, Map<Hop,NodeShapeFact> concreteShapes) {
		List<Set<Integer>> reaching = new ArrayList<>(hops.size());
		for(Hop hop : hops)
			reaching.add(reachingByHop.getOrDefault(hop, Set.of()));
		DMLProgram program = new DMLProgram();
		FunctionCallGraph fgraph = new FunctionCallGraph(program);
		return PlacementAbstractShapeAnalysis.inferOriginalOccurrences(hops,
			Collections.nCopies(hops.size(), "main"), reaching,
			Collections.nCopies(hops.size(), false), fgraph, new FunctionCallSizeInfo(fgraph),
			concreteShapes);
	}

	private static DataOp matrix(String name, long rows, long cols) {
		long nnz = rows >= 0 && cols >= 0 && rows <= Long.MAX_VALUE / Math.max(1, cols)
			? rows * cols : -1;
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, nnz, 1000);
	}

	private static BinaryOp append(String name, OpOp2 operation, Hop left, Hop right) {
		return new BinaryOp(name, DataType.MATRIX, ValueType.FP64, operation, left, right);
	}

	private static void assertShape(AbstractShapeFact shape, long rows, long cols) {
		Assert.assertTrue("Expected exact rows=" + rows + ", got " + shape.rows(), shape.rows().isExact(rows));
		Assert.assertTrue("Expected exact cols=" + cols + ", got " + shape.cols(), shape.cols().isExact(cols));
	}
}

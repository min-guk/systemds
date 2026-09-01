/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.BoundaryName;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Preserves existing known function-boundary identity bytes while adding typed unknown metadata. */
public class PlacementIdentityKnownEqualityContractTest {
	@Test
	public void knownBoundaryNameKeepsExactLegacySignatureToken() {
		BoundaryName left = BoundaryName.known("X");
		BoundaryName right = BoundaryName.known("X");

		Assert.assertEquals("known-name equality must stay value based", left, right);
		Assert.assertEquals("known-name normalized bytes must remain the raw legacy variable", "X",
			left.normalizedSignature());
		Assert.assertEquals("known canonical source token must remain the raw legacy variable", "X",
			left.canonicalSourceOriginToken());
		Assert.assertEquals("known value-version token must remain the raw legacy variable", "X",
			left.identityToken());
	}

	@Test
	public void knownFunctionBoundaryCanonicalOriginRemainsByteIdentical() {
		FunctionOp call = functionCall(new String[] {"X"}, new String[] {"Y"});
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program(call));
		Node input = onlyBoundary(analysis, NodeKind.FUNCTION_INPUT);

		Assert.assertEquals("known canonical source origin must be byte-identical to pre-A1b encoding",
			"function-boundary:" + call.getFunctionKey() + ":input:X",
			input.key().canonicalSourceOrigin());
		Assert.assertEquals("known value version variable must remain the raw boundary name", "X",
			input.valueVersion().lexicalVariable());
		Assert.assertTrue("known metadata remains a legal emitted boundary", input.emittedWork());
		Assert.assertFalse("known metadata keeps its legal alternatives", input.legalAlternatives().isEmpty());
	}

	@Test
	public void structuralEncodingKeepsExactLengthDelimitedBytes() {
		ControlRegionKey key = new ControlRegionKey(
			"p|x", "n,\u03c3", List.of("a|b", "c,d"), "call:1", "r");

		Assert.assertEquals(
			"3:p|x|3:n,\u03c3|11:3:a|b,3:c,d|6:call:1|1:r",
			key.normalizedSignature());
		Assert.assertSame("immutable structural identities must reuse one serialization",
			key.normalizedSignature(), key.normalizedSignature());
	}

	@Test
	public void dataflowPredecessorsUseCompactValueReferences() {
		Hop root = new LiteralOp(1L);
		for(int index = 0; index < 48; index++)
			root = new UnaryOp("u" + index, DataType.SCALAR, ValueType.FP64, OpOp1.EXP, root);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program(root));
		Map<Hop,ValueVersionKey> values = new IdentityHashMap<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Node node = analysis.graph().node(occurrence.key()).orElseThrow();
			for(int inputPosition = 0; inputPosition < occurrence.hop().getInput().size(); inputPosition++) {
				ValueVersionKey inputValue = values.get(occurrence.hop().getInput(inputPosition));
				if(inputValue == null)
					continue;
				String expected = "input-" + inputPosition + ':' + inputValue.cfgReferenceSignature();
				Assert.assertTrue("dataflow identity must reference the predecessor without nesting its full ancestry",
					node.valueVersion().predecessorVersions().contains(expected));
				Assert.assertTrue("one compact predecessor token must remain bounded across a long chain",
					expected.length() < 256);
			}
			values.put(occurrence.hop(), node.valueVersion());
		}
	}

	static FunctionOp functionCall(String[] inputNames, String[] outputNames) {
		Hop argument = new LiteralOp(7L);
		return new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, "pca", inputNames,
			List.of(argument), outputNames, true);
	}

	static DMLProgram program(Hop root) {
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		return program;
	}

	static Node onlyBoundary(PlacementAnalysis analysis, NodeKind kind) {
		List<Node> matches = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == kind).toList();
		Assert.assertEquals("expected exactly one " + kind + " boundary", 1, matches.size());
		return matches.get(0);
	}
}

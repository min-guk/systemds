/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.BoundaryName;
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

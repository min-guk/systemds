/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Cost bounds must not manufacture exact shapes or leak between alternative executions. */
public class PlacementCostSizeBoundsTest {
	private static final String SOURCE = "X=federated(addresses=list(\"localhost:1234/X\"),"
		+ "ranges=list(list(0,0),list(8192,1)));\n";
	// A control block keeps the parser from inlining away the shared function body.
	private static final String PASS = "pass=function(matrix[double] A) return(matrix[double] B){"
		+ "if(sum(A)>0){print(1);}B=A;}\n";
	private static final String TWO_CALLS = SOURCE
		+ "small=removeEmpty(target=X,margin=\"rows\");\n"
		+ "large=removeEmpty(target=X+1,margin=\"rows\");\n"
		+ "a=pass(small);b=pass(large);\n";
	private static final String SMALL_SINK = "S=matrix(0,rows=128,cols=1);S[1:nrow(a),1]=a;print(sum(S));\n";

	@Test
	public void alternativeCallersJoinUpperBoundsWithMaxNotMin() throws Exception {
		PlacementAnalysis analysis = analyze(PASS + TWO_CALLS + SMALL_SINK
			+ "L=matrix(0,rows=4096,cols=1);L[1:nrow(b),1]=b;print(sum(L));\n");
		assertFunctionReturnRows(analysis, 4096);
	}

	@Test
	public void unconstrainedCallerPreventsSharedReturnBound() throws Exception {
		PlacementAnalysis analysis = analyze(PASS + TWO_CALLS + SMALL_SINK + "print(sum(b));\n");
		assertFunctionReturnRows(analysis, -1);
	}

	@Test
	public void conditionalConsumerDoesNotConstrainUnconditionalCall() throws Exception {
		PlacementAnalysis analysis = analyze(PASS + SOURCE
			+ "small=removeEmpty(target=X,margin=\"rows\");a=pass(small);\n"
			+ "if(sum(X)>0){" + SMALL_SINK + "}\nprint(sum(a));\n");
		assertFunctionReturnRows(analysis, -1);
	}

	@Test
	public void namedArgumentOrderDoesNotSwapFormalContracts() throws Exception {
		PlacementAnalysis analysis = analyze(
			"fit=function(matrix[double] A,matrix[double] B) return(matrix[double] C){"
			+ "P=matrix(1,rows=1,cols=128);C=P%*%A;if(sum(B)>0){print(1);}}\n" + SOURCE
			+ "a=removeEmpty(target=X,margin=\"rows\");b=removeEmpty(target=X+1,margin=\"rows\");\n"
			+ "c=removeEmpty(target=X+2,margin=\"rows\");d=removeEmpty(target=X+3,margin=\"rows\");\n"
			+ "first=fit(A=a,B=b);second=fit(B=d,A=c);print(sum(first+second));\n");
		int aInputs = 0;
		int bInputs = 0;
		for(var binding : analysis.logicalFunctionInputsInCanonicalOrder()) {
			String formal = analysis.hop(binding.targetRead()).orElseThrow().getName();
			long rows = rows(analysis, binding.sourceArgument());
			if("A".equals(formal)) {
				Assert.assertEquals("A is constrained regardless of named-argument order", 128, rows);
				aInputs++;
			}
			else if("B".equals(formal)) {
				Assert.assertEquals("A's contract must not leak to unconstrained B", -1, rows);
				bInputs++;
			}
		}
		Assert.assertEquals(2, aInputs);
		Assert.assertEquals(2, bInputs);
	}

	@Test
	public void growingLoopDoesNotAcquireOriginalInputColumnBound() throws Exception {
		PlacementAnalysis analysis = analyze(SOURCE
			+ "Y=X;k=0;while(k<as.integer(sum(X))){Y=cbind(Y,X);k=k+1;}print(sum(Y));\n");
		var reads = reads(analysis, "Y");
		Assert.assertFalse(reads.isEmpty());
		for(CompiledHopKey key : reads) {
			Assert.assertFalse(analysis.abstractShapeFact(key).orElseThrow().cols().isExact());
			Assert.assertEquals("A cyclic growing axis is not bounded by X.cols", -1,
				analysis.costSizeBound(key).orElseThrow().colsUpperBound());
		}
	}

	@Test
	public void tableDimensionsUseScalarDataEdgesNotMatrixPlacementEdges() throws Exception {
		PlacementAnalysis analysis = analyze(SOURCE
			+ "n=nrow(X);P=table(seq(1,n),seq(1,n),n,n);print(sum(P));\n");
		var tables = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof TernaryOp table
				&& table.getOp() == OpOp3.CTABLE).toList();
		Assert.assertEquals(1, tables.size());
		CompiledHopKey key = tables.get(0).key();
		Assert.assertTrue("The physical matrix edge API must stay matrix-only",
			analysis.compiledInputEdge(key, 3).isEmpty());
		var exactShape = analysis.abstractShapeFact(key).orElseThrow();
		Assert.assertFalse("The regression must exercise scalar-bound lookup", exactShape.rows().isExact());
		Assert.assertFalse("The regression must exercise scalar-bound lookup", exactShape.cols().isExact());
		var bound = analysis.costSizeBound(key).orElseThrow();
		Assert.assertEquals(8192, bound.rowsUpperBound());
		Assert.assertEquals(8192, bound.colsUpperBound());
	}

	private static void assertFunctionReturnRows(PlacementAnalysis analysis, long expected) {
		var writes = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> "pass".equals(occurrence.key().functionNamespace()))
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && "B".equals(data.getName())).toList();
		Assert.assertEquals(1, writes.size());
		CompiledHopKey key = writes.get(0).key();
		Assert.assertFalse("Cost bounds must not replace the exact-shape lattice",
			analysis.abstractShapeFact(key).orElseThrow().rows().isExact());
		Assert.assertEquals(expected, rows(analysis, key));
		Assert.assertTrue("Bounds are not proof of exact geometry",
			Double.isNaN(PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key)));
	}

	private static List<CompiledHopKey> reads(PlacementAnalysis analysis, String name) {
		return analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && name.equals(data.getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
	}

	private static long rows(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.costSizeBound(key).map(PlacementCostSizeBounds.Bounds::rowsUpperBound).orElse(-1L);
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

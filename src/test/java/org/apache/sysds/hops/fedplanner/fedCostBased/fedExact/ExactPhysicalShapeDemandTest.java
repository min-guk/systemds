/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compare demand-driven shape traversal to the former full recursive scan. */
public class ExactPhysicalShapeDemandTest {
	private static final String SOURCE =
		"X=federated(addresses=list(\"localhost:23334/X\"),ranges=list(list(0,0),list(8,2)));";

	@Test
	public void shapeTraversalMatchesLegacyOnBranchesFunctionsAndUnknownDimensions() throws Exception {
		List<String> scripts = List.of(
			SOURCE + "n=as.integer(as.scalar(rand(rows=1,cols=1))*5)+1;"
				+ "A=rand(rows=n,cols=2);B=A+1;C=rowSums(B);D=colSums(B);"
				+ "print(sum(C)+sum(D)+sum(X));write(B,\"/tmp/shape-demand\",format=\"binary\");",
			"f=function(matrix[double] A) return(matrix[double] B){B=rowSums(A);}" + SOURCE
				+ "p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=X;}else{A=X+1;}"
				+ "B=f(A);print(sum(B));",
			SOURCE + "A=X;for(i in 1:2){A=A+1;}print(sum(rowSums(A)));"
		);
		int compared = 0;
		int unknown = 0;
		for(String script : scripts) {
			PlacementAnalysis analysis = analysis(script);
			for(var node : analysis.graph().nodes()) {
				Shape expected = legacyShape(analysis, node.key(), identitySet());
				Assert.assertEquals(node.key().normalizedSignature(), expected, actualShape(analysis, node.key()));
				compared++;
				if(expected == null) unknown++;
			}
		}
		Assert.assertTrue(compared > 20);
		Assert.assertTrue("unresolved shapes must retain the same fallback", unknown > 0);
	}

	@Test
	public void indexedInputLookupRetainsExactIdentityAndAbsentInputBehavior() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCE + "A=X+1;print(sum(A));");
		Method lookup = ExactPhysicalCostModel.class.getDeclaredMethod(
			"exactCompiledInput", PlacementAnalysis.class, CompiledHopKey.class, int.class);
		lookup.setAccessible(true);
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			Assert.assertSame(edge.producer(), lookup.invoke(null, analysis, edge.consumer(), edge.inputPosition()));
			CompiledHopKey key = edge.consumer();
			CompiledHopKey equalForeign = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
				key.callSitePath(), key.recompileContext(), key.controlRegion(), key.emittedHopInstance(),
				key.canonicalSourceOrigin());
			Assert.assertEquals(key, equalForeign);
			Assert.assertNull(lookup.invoke(null, analysis, equalForeign, edge.inputPosition()));
			Assert.assertNull(lookup.invoke(null, analysis, key, -1));
			Assert.assertNull(lookup.invoke(null, analysis, key, 1000));
		}
		Assert.assertNull(lookup.invoke(null, analysis, null, 0));
	}

	private static Shape actualShape(PlacementAnalysis analysis, CompiledHopKey key) throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod(
			"exactMatrixShape", PlacementAnalysis.class, CompiledHopKey.class, Set.class);
		method.setAccessible(true);
		Object actual = method.invoke(null, analysis, key, identitySet());
		if(actual == null) return null;
		Method rows = actual.getClass().getDeclaredMethod("rows");
		Method cols = actual.getClass().getDeclaredMethod("cols");
		rows.setAccessible(true);
		cols.setAccessible(true);
		return new Shape((long)rows.invoke(actual), (long)cols.invoke(actual));
	}

	private static Set<CompiledHopKey> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private record Shape(long rows, long cols) { }

	// Frozen pre-optimization oracle: deliberately retains the full edge scan and
	// recursive input walk even when the result cannot be used by the current Hop.
	private static Shape legacyShape(PlacementAnalysis analysis,
		CompiledHopKey key, Set<CompiledHopKey> visiting) {
		if(!visiting.add(key))
			return null;
		try {
			double abstractBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key);
			if(Double.isFinite(abstractBytes) && abstractBytes > 0.0) {
				var abstractShape = analysis.abstractShapeFact(key).orElseThrow();
				return new Shape(abstractShape.rows().value(), abstractShape.cols().value());
			}
			Shape captured = analysis.shapeFact(key)
				.filter(shape -> shape.rows() > 0 && shape.cols() > 0)
				.map(shape -> new Shape(shape.rows(), shape.cols())).orElse(null);
			if(captured != null)
				return captured;
			Shape anchored = legacyAnchorShape(analysis, key);
			if(anchored != null)
				return anchored;

			Shape logicalFunctionShape = null;
			for(LogicalFunctionInputFact fact : analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(fact.targetRead() != key)
					continue;
				Shape source = legacyShape(analysis, fact.sourceArgument(), visiting);
				if(source == null)
					continue;
				if(logicalFunctionShape != null && !logicalFunctionShape.equals(source))
					return null;
				logicalFunctionShape = source;
			}
			if(logicalFunctionShape != null)
				return logicalFunctionShape;

			Shape logicalTransientShape = null;
			for(LogicalTransientInputFact fact : analysis.logicalTransientInputsInCanonicalOrder()) {
				if(fact.targetRead() != key)
					continue;
				Shape source = legacyShape(analysis, fact.sourceWrite(), visiting);
				if(source == null)
					continue;
				if(logicalTransientShape != null && !logicalTransientShape.equals(source))
					return null;
				logicalTransientShape = source;
			}
			if(logicalTransientShape != null)
				return logicalTransientShape;

			Shape cfgDefinitionShape = null;
			for(CompiledHopKey sourceKey : analysis.cfgDefinitionSourcesInCanonicalOrder(key)) {
				Shape source = legacyShape(analysis, sourceKey, visiting);
				if(source == null)
					continue;
				if(cfgDefinitionShape != null && !cfgDefinitionShape.equals(source))
					return null;
				cfgDefinitionShape = source;
			}
			if(cfgDefinitionShape != null)
				return cfgDefinitionShape;

			Hop hop = analysis.hop(key).orElse(null);
			var estimate = hop == null ? null : analysis.physicalCostEstimateFact(key);
			if(estimate != null && estimate.multiReturnRows() > 0L && estimate.multiReturnCols() > 0L)
				return new Shape(estimate.multiReturnRows(), estimate.multiReturnCols());
			CompiledHopKey input = legacyInput(analysis, key, 0);
			Shape inputShape = input == null ? null
				: legacyShape(analysis, input, visiting);
			if(hop instanceof AggUnaryOp && inputShape != null) {
				org.apache.sysds.common.Types.Direction direction = ((AggUnaryOp)hop).getDirection();
				if(direction == org.apache.sysds.common.Types.Direction.Row)
					return new Shape(inputShape.rows(), 1L);
				if(direction == org.apache.sysds.common.Types.Direction.Col)
					return new Shape(1L, inputShape.cols());
			}
			if(hop instanceof DataOp && inputShape != null) {
				OpOpData op = ((DataOp)hop).getOp();
				if(op == OpOpData.TRANSIENTWRITE || op == OpOpData.PERSISTENTWRITE)
					return inputShape;
			}
			return null;
		}
		finally {
			visiting.remove(key);
		}
	}

	private static CompiledHopKey legacyInput(PlacementAnalysis analysis,
		CompiledHopKey consumer, int inputPosition) {
		List<CompiledHopKey> inputs = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer && edge.inputPosition() == inputPosition)
			.map(CompiledInputEdgeFact::producer).toList();
		return inputs.size() == 1 ? inputs.get(0) : null;
	}

	private static Shape legacyAnchorShape(PlacementAnalysis analysis, CompiledHopKey key) {
		NeutralPlacementGraph.Node node = analysis.graph().node(key).orElseThrow();
		if(node.anchors().size() != 1)
			return null;
		DurableAnchorKey anchor = node.anchors().get(0);
		long rows = 0L;
		long cols = 0L;
		for(AnchorPartition partition : anchor.partitions()) {
			if(partition.end().size() < 2)
				return null;
			rows = Math.max(rows, partition.end().get(0));
			cols = Math.max(cols, partition.end().get(1));
		}
		return rows > 0L && cols > 0L ? new Shape(rows, cols) : null;
	}

}

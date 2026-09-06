/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact contracts for a pre-loop transient definition consumed in a recompiled loop body. */
public class NeutralPlacementGraphCrossRecompileTransientTest {
	private static final PlacementState CP =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	public void privateAggregateFederatedValueForwardsFromCompiledDefinitionToRecompileRead() throws Exception {
		PlacementAnalysis analysis = buildPrivateAggregate(
			"X=federated(addresses=list(\"localhost:1234/X\"),"
				+ "ranges=list(list(0,0),list(10,3)));"
				+ "Y=X;for(i in 1:2){Z=Y+1;}print(sum(Z));");
		List<Node> reads = analysis.graph().nodes().stream()
			.filter(node -> "Y".equals(node.valueVersion().lexicalVariable()))
			.filter(node -> node.kind() == NodeKind.TRANSIENT_READ)
			.filter(node -> "recompile".equals(node.key().recompileContext()))
			.toList();
		Assert.assertEquals("fixture must expose one loop-body recompile TRead Y", 1, reads.size());
		Node read = reads.get(0);
		Assert.assertTrue("private aggregate TRead must retain native FULL/FOUT",
			read.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
		Assert.assertFalse("private aggregate recompile must not authorize coordinator execution or upload",
			read.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.CP));
	}

	@Test
	public void ambiguousBranchDefinitionsDoNotInventFederatedRecompilePlacement() throws Exception {
		try {
			buildPrivateAggregate("X=federated(addresses=list(\"localhost:1234/X\"),"
				+ "ranges=list(list(0,0),list(10,3)));Y=X;"
				+ "if(sum(X)>0){Y=matrix(1,rows=10,cols=3);}"
				+ "for(i in 1:2){Z=Y+1;}print(sum(Z));");
			Assert.fail("ambiguous local/federated definitions must fail closed");
		}
		catch(DMLRuntimeException ex) {
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("No privacy-safe physical placement"));
		}
	}

	@Test
	public void localCompiledValueDoesNotBecomeFederatedAtRecompile() throws Exception {
		PlacementAnalysis analysis = buildPrivateAggregate(
			"Y=matrix(1,rows=10,cols=3);for(i in 1:2){Z=Y+1;}print(sum(Z));");
		Assert.assertFalse("a coordinator-only definition must not invent any federated state",
			analysis.graph().nodes().stream().flatMap(node -> node.legalAlternatives().stream())
				.anyMatch(state -> state.execType() == ExecType.FED));
	}

	@Test
	public void crossCompileBoundaryBridgeRetainsIdentityAndKnownContextGuards() throws Exception {
		Method guard = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"sameTransientForwardContext", Node.class, Node.class);
		guard.setAccessible(true);
		Node source = node("program", "main", "X", "compiled");
		Assert.assertTrue("compiled definition must feed exact recompile read",
			(Boolean) guard.invoke(null, source, node("program", "main", "X", "recompile")));
		Assert.assertTrue("exact recompile definition may return to a compiled read",
			(Boolean) guard.invoke(null, node("program", "main", "X", "recompile"), source));
		Assert.assertFalse("different programs remain isolated",
			(Boolean) guard.invoke(null, source, node("other-program", "main", "X", "recompile")));
		Assert.assertFalse("different namespaces remain isolated",
			(Boolean) guard.invoke(null, source, node("program", "f", "X", "recompile")));
		Assert.assertFalse("different lexical variables remain isolated",
			(Boolean) guard.invoke(null, source, node("program", "main", "Y", "recompile")));
		Assert.assertFalse("unknown context transitions remain fail closed",
			(Boolean) guard.invoke(null, source, node("program", "main", "X", "other")));
	}

	private static PlacementAnalysis buildPrivateAggregate(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		registerPrivacy(program.getStatementBlocks(), Collections.newSetFromMap(new IdentityHashMap<>()));
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void registerPrivacy(List<StatementBlock> blocks, Set<Hop> visited) {
		for(StatementBlock block : blocks) {
			if(block.getHops() != null)
				for(Hop root : block.getHops())
					registerPrivacy(root, visited);
			if(block instanceof ForStatementBlock forBlock) {
				registerPrivacy(forBlock.getFromHops(), visited);
				registerPrivacy(forBlock.getToHops(), visited);
				registerPrivacy(forBlock.getIncrementHops(), visited);
				registerPrivacy(((ForStatement) forBlock.getStatement(0)).getBody(), visited);
			}
		}
	}

	private static void registerPrivacy(Hop hop, Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PRIVATE_AGGREGATE);
		for(Hop input : hop.getInput())
			registerPrivacy(input, visited);
	}

	private static Node node(String program, String namespace, String variable, String context) {
		ControlRegionKey region = new ControlRegionKey(program, namespace, List.of("main"), "main", context);
		CompiledHopKey key = new CompiledHopKey(program, namespace, "main", context, region,
			"root-0", variable);
		ValueVersionKey value = new ValueVersionKey(program, variable, region, 1,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.TRANSIENT_WRITE, value, true, List.of(CP), List.of(), List.of());
	}
}

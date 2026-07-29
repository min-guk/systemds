/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for planner authority crossing a compiled DML function boundary. */
public class SharedPlannerFunctionPlanPropagationRedTest {
	private static final String KMEANS_SCRIPT = """
		X = federated(addresses=list("localhost:8001/X", "localhost:8002/X"),
			ranges=list(list(0, 0), list(500, 100), list(500, 0), list(1000, 100)));
		[C, Y] = kmeans(X=X, k=4, runs=1, max_iter=2, seed=93);
		""";
	private static final String SMALL_FUNCTION_SCRIPT = """
		f = function(matrix[double] A) return (matrix[double] B) {
			B = A;
			i = 1;
			while(i < 2) {
				B = B + 1;
				i = i + 1;
			}
		}
		X = federated(addresses=list("localhost:8001/X", "localhost:8002/X"),
			ranges=list(list(0, 0), list(500, 100), list(500, 0), list(1000, 100)));
		Y = f(X);
		print(sum(Y));
		""";

	@Test
	public void kmeansCompiledFunctionBodyExposesFedCandidates() throws Exception {
		DMLProgram program = compile(KMEANS_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		long functionBodyFedCandidates = analysis.graph().decisionNodes().stream()
			.filter(node -> node.key().functionNamespace().contains("m_kmeans"))
			.filter(node -> analysis.isCompiledHopOccurrence(node.key()))
			.filter(node -> node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED))
			.count();
		Assert.assertTrue("The compiled kmeans function body must expose FED candidates",
			functionBodyFedCandidates > 0);
		List<NeutralPlacementGraph.Node> formalReads = analysis.graph().decisionNodes().stream()
			.filter(node -> node.valueVersion().versionKind() == VersionKind.FUNCTION_INPUT)
			.filter(node -> analysis.isCompiledHopOccurrence(node.key())).toList();
		Assert.assertFalse("The compiled function body must expose concrete formal reads", formalReads.isEmpty());
		Assert.assertTrue("Mapped formal reads must use the exact logical argument domain, not an empty bypass",
			formalReads.stream().allMatch(node -> {
				List<PlacementAnalysis.CandidateRuleKey> keys = analysis.candidateRuleDomain().orderedRuleKeys().stream()
					.filter(key -> key.parentOccurrence() == node.key()).toList();
				return keys.stream().noneMatch(key -> key.orderedInputs().isEmpty())
					&& keys.stream().allMatch(key -> key.orderedInputs().size() == 1);
			}));
		Assert.assertTrue("Each mapped formal read must have an exact caller-argument authority",
			formalReads.stream().allMatch(node -> analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.anyMatch(fact -> fact.targetRead() == node.key())));
	}

	@Test
	public void compilerGeneratedFormalInputBindingPreservesPlacement() throws Exception {
		DMLProgram program = compile(KMEANS_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<NeutralPlacementGraph.Constraint> formalBindings = analysis.graph().constraints().stream()
			.filter(constraint -> analysis.graph().node(constraint.left()).orElseThrow()
				.valueVersion().versionKind() == VersionKind.FUNCTION_INPUT)
			.filter(constraint -> {
				var source = analysis.hop(constraint.left()).orElseThrow();
				var target = analysis.hop(constraint.right()).orElseThrow();
				return source instanceof DataOp && target instanceof DataOp
					&& ((DataOp) source).getOp() == OpOpData.TRANSIENTREAD
					&& ((DataOp) target).getOp() == OpOpData.TRANSIENTWRITE
					&& source.getName().equals(target.getName());
			})
			.toList();
		Assert.assertFalse("The compiler-generated formal input binding must be represented",
			formalBindings.isEmpty());
		Assert.assertTrue("A formal input binding is an identity, not a planner-visible download/upload point",
			formalBindings.stream().allMatch(constraint -> constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& "function-input-binding".equals(constraint.evidence())));
	}

	@Test
	public void fedAllPublishesSelectedFunctionBodyStatesForRecompile() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_all");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		try {
			DMLProgram program = compile(SMALL_FUNCTION_SCRIPT);
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			new DMLTranslator(program).constructLops(program, captured::set);
			Assert.assertTrue(captured.get() instanceof FedAllInvocationReceipt);
			NormalizedPlannerResult result = ((FedAllInvocationReceipt) captured.get()).normalizedResult();
			Map<CompiledHopKey, PlacementEmissionState> selected = result.selectedEmissionStates();
			long functionBodyFedCandidates = result.analysis().graph().decisionNodes().stream()
				.filter(node -> !"main".equals(node.key().functionNamespace()))
				.filter(node -> result.analysis().isCompiledHopOccurrence(node.key()))
				.filter(node -> node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED))
				.count();
			Assert.assertTrue("The compiled function body must expose FED candidates",
				functionBodyFedCandidates > 0);
			Assert.assertTrue("FedAll must select a concrete FED state in the compiled function body",
				selected.entrySet().stream()
					.filter(entry -> entry.getValue().placementState().execType() == ExecType.FED)
					.anyMatch(entry -> !"main".equals(entry.getKey().functionNamespace())
						&& result.analysis().isCompiledHopOccurrence(entry.getKey())));
			Assert.assertFalse("Selected compiled function-body states must be published for recompile",
				FederatedPlannerUtils.snapshotPlannerRecompileStates().isEmpty());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}

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
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for planner authority crossing a compiled DML function boundary. */
public class SharedPlannerFunctionPlanPropagationRedTest {
	private static final String KMEANS_SCRIPT = """
		X = federated(addresses=list("localhost:8001/X", "localhost:8002/X"),
			ranges=list(list(0, 0), list(500, 100), list(500, 0), list(1000, 100)));
		[C, Y] = kmeans(X=X, k=4, runs=1, max_iter=2, seed=93);
		""";
	private static final String KMEANS_DOCKER_SHAPE_SCRIPT = """
		X = federated(addresses=list("localhost:8001/X"),
			ranges=list(list(0, 0), list(50000, 2100)));
		[C, Y] = kmeans(X=X, k=50, is_verbose=FALSE, runs=1, eps=1e-9, max_iter=60,
			avg_sample_size_per_centroid=50, seed=133815928);
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
	public void kmeansDistanceMultiplyProducerRemainsEmittedWithItsConsumer() throws Exception {
		DMLProgram program = compile(KMEANS_DOCKER_SHAPE_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<PlacementAnalysis.CompiledInputEdgeFact> matrixMultiplyInputs =
			analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> analysis.hop(edge.producer()).orElseThrow() instanceof AggBinaryOp)
				.filter(edge -> analysis.hop(edge.consumer()).orElseThrow() instanceof BinaryOp)
				.toList();
		Assert.assertFalse("The compiled m_kmeans distance expression must retain a physical matrix-multiply edge",
			matrixMultiplyInputs.isEmpty());
		for(PlacementAnalysis.CompiledInputEdgeFact edge : matrixMultiplyInputs) {
			NeutralPlacementGraph.Node producer = analysis.graph().node(edge.producer()).orElseThrow();
			NeutralPlacementGraph.Node consumer = analysis.graph().node(edge.consumer()).orElseThrow();
			Assert.assertTrue("A physical AggBinary producer cannot be classified non-emitted while its consumer is emitted"
				+ "|producer=" + producer.key().normalizedSignature()
				+ "|producer-kind=" + producer.kind()
				+ "|consumer=" + consumer.key().normalizedSignature()
				+ "|consumer-emitted=" + consumer.emittedWork(),
				!consumer.emittedWork() || producer.emittedWork());
		}
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

	@Test
	public void dmlFunctionCallBoundaryDoesNotMaterializeFederatedArgumentLocally() throws Exception {
		DMLProgram program = compile(SMALL_FUNCTION_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NeutralPlacementGraph.Node call = analysis.graph().decisionNodes().stream()
			.filter(node -> node.kind() == NodeKind.FUNCTION_CALL)
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof FunctionOp function
				&& function.getFunctionType() == FunctionType.DML)
			.findFirst().orElseThrow();
		PlacementAnalysis.CompiledInputEdgeFact argumentEdge = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == call.key())
			.filter(edge -> analysis.hop(edge.producer()).orElseThrow().getDataType().isMatrix())
			.findFirst().orElseThrow();
		NeutralPlacementGraph.Node argument = analysis.graph().node(argumentEdge.producer()).orElseThrow();
		PlacementState federatedArgument = argument.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow();
		PlacementState localCallPlaceholder = call.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow();

		NormalizedPlannerResult normalized = NormalizedPlannerResults.create(analysis, "Exact-boundary-regression",
			Map.of(argument.key(), federatedArgument, call.key(), localCallPlaceholder), "fixture");
		Assert.assertTrue("A DML FunctionOp is a logical forwarding boundary, not a local matrix consumer",
			normalized.selectedLocalMaterializations().isEmpty());
	}

	@Test
	public void localFormalMaterializesFederatedArgumentAtExactFunctionCallInput() throws Exception {
		DMLProgram program = compile(SMALL_FUNCTION_SCRIPT);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlacementAnalysis.LogicalFunctionInputFact fact = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(candidate -> analysis.hop(candidate.sourceArgument()).orElseThrow()
				.getDataType().isMatrix()).findFirst().orElseThrow();
		CompiledHopKey call = analysis.requireExactPhysicalFunctionInputConsumer(fact);
		NeutralPlacementGraph.Node argument = analysis.graph().node(fact.sourceArgument()).orElseThrow();
		NeutralPlacementGraph.Node formal = analysis.graph().node(fact.targetRead()).orElseThrow();
		NeutralPlacementGraph.Node callNode = analysis.graph().node(call).orElseThrow();
		PlacementState federatedArgument = argument.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow();
		PlacementState localFormal = formal.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow();
		PlacementState callState = callNode.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow();
		Map<CompiledHopKey,PlacementState> selected = new java.util.IdentityHashMap<>();
		selected.put(argument.key(), federatedArgument);
		selected.put(formal.key(), localFormal);
		selected.put(call, callState);
		Map<CompiledHopKey,PlacementEmissionState> emissions = new java.util.IdentityHashMap<>();
		selected.forEach((key, state) -> emissions.put(key, new PlacementEmissionState(state, false)));

		List<LocalMaterializationActionKey> actions = LocalMaterializationSelections.derive(
			analysis, selected, emissions, List.of());
		Assert.assertEquals(1, actions.size());
		Assert.assertSame(argument.key(), actions.get(0).sourceOccurrence());
		Assert.assertEquals(1, actions.get(0).obligations().size());
		Assert.assertSame(call, actions.get(0).obligations().get(0).consumerOccurrence());
		Assert.assertEquals(fact.callInputPosition(), actions.get(0).obligations().get(0).inputPosition());
		Assert.assertSame(callState, actions.get(0).obligations().get(0).requiredPlacement());
		Assert.assertFalse("The selected function-call LOCAL action makes the CP/LOUT formal physically local",
			PlacementCostSemantics.requiresRefedLocalMaterialization(analysis, formal, emissions));
		Assert.assertTrue("A direct FED/FOUT source still needs the explicit FED-to-local REFED pre-stage",
			PlacementCostSemantics.requiresRefedLocalMaterialization(analysis, argument, emissions));
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

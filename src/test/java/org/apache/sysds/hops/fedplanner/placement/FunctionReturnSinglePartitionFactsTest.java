/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact one-worker cardinality must follow the same CFG-authorized Data object across DML returns. */
public class FunctionReturnSinglePartitionFactsTest {
	private static final String DIRECT = """
		m_scale = function(Matrix[Double] A, Boolean center=TRUE, Boolean scale=TRUE)
			return(Matrix[Double] Out, Matrix[Double] Centering, Matrix[Double] ScaleFactor) {
			Centering = matrix(0, rows=0, cols=0);
			ScaleFactor = matrix(0, rows=0, cols=0);
			if(center) {
				Centering = colMeans(A);
				A = A - Centering;
			}
			if(scale) {
				ScaleFactor = sqrt(colSums(A^2) / (nrow(A) - 1));
				A = A / ScaleFactor;
			}
			Out = A;
		}
		X = federated(addresses=list("localhost:8001/X"), ranges=list(list(0,0), list(20,4)));
		I = matrix(1, rows=4, cols=1);
		Xselected = removeEmpty(target=X, margin="cols", select=t(I));
		[Y,C,S] = m_scale(Xselected, TRUE, TRUE);
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";
	private static final String NESTED = """
		f = function(Matrix[Double] A) return(Matrix[Double] B) {
			B = A;
		}
		g = function(Matrix[Double] C) return(Matrix[Double] D) {
			D = f(C);
		}
		X = federated(addresses=list("localhost:8001/X"), ranges=list(list(0,0), list(20,4)));
		Y = g(X);
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";

	private static final String CONFLICTING_EXITS = """
		f = function(Matrix[Double] A, Matrix[Double] C, Boolean flag) return(Matrix[Double] B) {
			if(flag) {
				B = A;
			}
			else {
				B = C;
			}
		}
		X1 = federated(addresses=list("localhost:8001/X"), ranges=list(list(0,0), list(20,4)));
		X2 = federated(addresses=list("localhost:8002/X"), ranges=list(list(0,0), list(20,4)));
		flag = as.scalar(rand(rows=1, cols=1, seed=13)) > 0.5;
		Y = f(X1, X2, flag);
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";

	private static final String LOCAL_AND_FEDERATED_EXITS = """
		f = function(Matrix[Double] A, Matrix[Double] C, Boolean flag) return(Matrix[Double] B) {
			if(flag) {
				B = A;
			}
			else {
				B = C;
			}
		}
		X1 = federated(addresses=list("localhost:8001/X"), ranges=list(list(0,0), list(20,4)));
		X2 = rand(rows=20, cols=4, seed=9);
		flag = as.scalar(rand(rows=1, cols=1, seed=13)) > 0.5;
		Y = f(X1, X2, flag);
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";

	private static final String FEDERATED_SELECT_DOES_NOT_GROUND_LOCAL_TARGET = """
		X = rand(rows=20, cols=4, seed=11);
		S = federated(addresses=list("localhost:8001/S"), ranges=list(list(0,0), list(4,1)));
		Y = removeEmpty(target=X, margin="cols", select=t(S));
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";

	private static final String MULTI_PARTITION_TARGET_IS_NOT_SINGLE = """
		X = federated(addresses=list("localhost:8001/X1", "localhost:8002/X2"),
			ranges=list(list(0,0), list(10,4), list(10,0), list(20,4)));
		S = matrix(1, rows=20, cols=1);
		Y = removeEmpty(target=X, margin="rows", select=S);
		R = rand(rows=nrow(Y), cols=ncol(Y), seed=7);
		Z = Y < R;
		print(sum(Z));
		""";

	private static final String DIFFERENT_ENDPOINT_RMEMPTY_CALLERS = """
		prune = function(Matrix[Double] A, Matrix[Double] S, Boolean flag) return(Matrix[Double] B) {
			T = A;
			if(flag) {
				T = A + 0;
			}
			B = removeEmpty(target=T, margin="rows", select=S);
		}
		X1 = federated(addresses=list("localhost:8001/X1"), ranges=list(list(0,0), list(20,4)));
		X2 = federated(addresses=list("localhost:8002/X2"), ranges=list(list(0,0), list(20,4)));
		S = matrix(1, rows=20, cols=1);
		flag = as.scalar(rand(rows=1, cols=1, seed=13)) > 0.5;
		Y1 = prune(X1, S, flag);
		Y2 = prune(X2, S, flag);
		R = rand(rows=nrow(Y1), cols=ncol(Y1), seed=7);
		Z = Y1 < R;
		print(sum(Z) + sum(Y2));
		""";

	private static final String INLINEABLE_RMEMPTY_CALLERS = """
		prune = function(Matrix[Double] A, Matrix[Double] S) return(Matrix[Double] B) {
			B = removeEmpty(target=A, margin="rows", select=S);
		}
		X1 = federated(addresses=list("localhost:8001/X1"), ranges=list(list(0,0), list(20,4)));
		X2 = federated(addresses=list("localhost:8002/X2"), ranges=list(list(0,0), list(20,4)));
		S = matrix(1, rows=20, cols=1);
		Y1 = prune(X1, S);
		Y2 = prune(X2, S);
		R = rand(rows=nrow(Y1), cols=ncol(Y1), seed=7);
		Z = Y1 < R;
		print(sum(Z) + sum(Y2));
		""";

	@Test
	public void exactSingleEndpointSurvivesDirectDmlReturn() throws Exception {
		assertMainConsumerKeepsFull(DIRECT, "Y");
	}

	@Test
	public void exactSingleEndpointSurvivesNestedDmlReturns() throws Exception {
		assertMainConsumerKeepsFull(NESTED, "Y");
	}

	@Test
	public void differentEndpointExitsDoNotCreateSingleEndpointAuthority() throws Exception {
		assertMainConsumerHasNoFull(CONFLICTING_EXITS, "Y");
	}

	@Test
	public void localExitMakesFunctionResultEndpointUnknown() throws Exception {
		assertMainConsumerHasNoFull(LOCAL_AND_FEDERATED_EXITS, "Y");
	}

	@Test
	public void federatedSelectDoesNotGroundLocalRmemptyTarget() throws Exception {
		assertRmemptyHasNoFull(FEDERATED_SELECT_DOES_NOT_GROUND_LOCAL_TARGET);
	}

	@Test
	public void multiPartitionRmemptyTargetDoesNotBecomeSingle() throws Exception {
		assertRmemptyHasNoFull(MULTI_PARTITION_TARGET_IS_NOT_SINGLE);
	}

	@Test
	public void differentEndpointRmemptyCallersDoNotCreateSingleAuthority() throws Exception {
		assertSharedFunctionRmemptyHasNoFull(DIFFERENT_ENDPOINT_RMEMPTY_CALLERS, "prune");
	}

	@Test
	public void inlineableDifferentEndpointRmemptyBodyRemainsNonEmitted() throws Exception {
		assertInlineableRmemptyBodyRemainsNonEmitted(INLINEABLE_RMEMPTY_CALLERS, "prune");
	}

	@Test
	public void inlineableSameEndpointRmemptyBodyRemainsNonEmitted() throws Exception {
		assertInlineableRmemptyBodyRemainsNonEmitted(
			INLINEABLE_RMEMPTY_CALLERS.replace("Y2 = prune(X2, S)", "Y2 = prune(X1, S)"), "prune");
	}

	private static void assertSharedFunctionRmemptyHasNoFull(String script, String functionName) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<NeutralPlacementGraph.Node> rmempty = analysis.graph().decisionNodes().stream()
			.filter(node -> functionName.equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
			.toList();
		Assert.assertEquals("Fixture must expose one shared function-body rmempty", 1, rmempty.size());
		Assert.assertFalse("Different caller endpoints must not ground the shared rmempty occurrence",
			rmempty.get(0).legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
	}

	private static void assertInlineableRmemptyBodyRemainsNonEmitted(String script, String functionName)
		throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<NeutralPlacementGraph.Node> functionBody = analysis.graph().nodes().stream()
			.filter(node -> functionName.equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
			.toList();
		Assert.assertEquals("Fixture must expose one inlineable function-body rmempty", 1, functionBody.size());
		Assert.assertEquals(NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED,
			functionBody.get(0).kind());
		Assert.assertFalse(functionBody.get(0).emittedWork());
		Assert.assertTrue(functionBody.get(0).legalAlternatives().isEmpty());

		List<NeutralPlacementGraph.Node> inlined = analysis.graph().decisionNodes().stream()
			.filter(node -> "main".equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
			.toList();
		Assert.assertEquals("Both inlined physical rmempty calls must remain active", 2, inlined.size());
		for(NeutralPlacementGraph.Node node : inlined)
			Assert.assertTrue("Each exact one-worker inlined rmempty retains FULL/FOUT: " + node,
				node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
	}

	private static void assertRmemptyHasNoFull(String script) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<NeutralPlacementGraph.Node> rmempty = analysis.graph().decisionNodes().stream()
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
			.toList();
		Assert.assertFalse("Fixture must expose at least one rmempty decision", rmempty.isEmpty());
		for(NeutralPlacementGraph.Node node : rmempty)
			Assert.assertFalse("Only the rmempty target may ground one-endpoint authority: " + node,
				node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
	}

	private static void assertMainConsumerKeepsFull(String script, String returnedVariable) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<NeutralPlacementGraph.Node> consumers = consumersOf(analysis, returnedVariable);
		String consumerSummary = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> "main".equals(occurrence.key().functionNamespace()))
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp)
			.map(occurrence -> occurrence.key() + " inputs=" + occurrence.hop().getInput().stream()
				.map(input -> input.getClass().getSimpleName() + ":" + input.getName()).toList())
			.toList().toString();
		Assert.assertEquals("Fixture must have exactly one downstream consumer of the returned value; "
			+ consumerSummary, 1, consumers.size());
		Assert.assertTrue("Exact one-endpoint return must keep a native FULL/FOUT consumer: " + consumers.get(0),
			consumers.get(0).legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
	}

	private static void assertMainConsumerHasNoFull(String script, String returnedVariable) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<NeutralPlacementGraph.Node> consumers = consumersOf(analysis, returnedVariable);
		Assert.assertEquals("Fixture must have exactly one downstream consumer of " + returnedVariable,
			1, consumers.size());
		Assert.assertFalse("Ambiguous caller provenance must not manufacture FULL/FOUT: " + consumers.get(0),
			consumers.get(0).legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL));
	}

	private static List<NeutralPlacementGraph.Node> consumersOf(PlacementAnalysis analysis,
		String returnedVariable) {
		return analysis.graph().decisionNodes().stream()
			.filter(node -> "main".equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof BinaryOp)
			.filter(node -> analysis.hop(node.key()).orElseThrow().getInput().stream()
				.anyMatch(input -> input instanceof DataOp && returnedVariable.equals(input.getName())))
			.toList();
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}

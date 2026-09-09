/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for candidate construction failures at the oracle boundary. */
public class NeutralPlacementGraphBuilderOracleFailureTest {
	private static final String SCRIPT =
		"X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,2)));\n"
			+ "Y=X+1; print(sum(Y));\n";

	@Test
	public void decisionRuntimeFailureAbortsWithOccurrenceContextAndCause() throws Exception {
		RuntimeException oracleFailure = new IllegalArgumentException("decision exploded");
		RulesCore.BaseRule rule = new PlusRule() {
			@Override
			public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				throw oracleFailure;
			}
		};

		assertContextualFailure(oracleFailure, rule, "oracle decision");
	}

	@Test
	public void candidateProfileRuntimeFailureAbortsInsteadOfClearingProfile() throws Exception {
		RuntimeException oracleFailure = new IllegalStateException("profile exploded");
		RulesCore.BaseRule rule = new PlusRule() {
			@Override
			public FTypeProfile profile(OpSig sig, List<List<FType>> inputs, ShapeHint hint) {
				if(calledFrom("candidateRuleFact"))
					throw oracleFailure;
				return FTypeProfile.empty();
			}
		};

		assertContextualFailure(oracleFailure, rule, "candidate profile");
	}

	@Test
	public void consumerProfileRuntimeFailureAbortsInsteadOfExcludingLayouts() throws Exception {
		RuntimeException oracleFailure = new IllegalStateException("consumer profile exploded");
		RulesCore.BaseRule rule = new PlusRule() {
			@Override
			public FTypeProfile profile(OpSig sig, List<List<FType>> inputs, ShapeHint hint) {
				if(calledFrom("evaluateConsumerProfile"))
					throw oracleFailure;
				return FTypeProfile.empty();
			}
		};

		assertContextualFailure(oracleFailure, rule, "consumer profile");
	}

	@Test
	public void oracleErrorEscapesUnwrapped() throws Exception {
		AssertionError oracleFailure = new AssertionError("fatal oracle failure");
		RulesCore.BaseRule rule = new PlusRule() {
			@Override
			public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				throw oracleFailure;
			}
		};

		AssertionError actual = Assert.assertThrows(AssertionError.class,
			() -> builder(rule).buildAnalysis(privateAggregateProgram()));
		Assert.assertSame(oracleFailure, actual);
	}

	@Test
	public void declaredRuleErrorAbortsInsteadOfCreatingAnEmptyCandidateRow() throws Exception {
		RulesCore.BaseRule rule = new PlusRule() {
			@Override
			public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				return OpCaps.newBuilder().category(OpCategory.BINARY_EWISE).opcode(sig.opcode())
					.exec(ExecType.CP).placement(FederatedOutput.LOUT).reason(ReasonCode.RULE_ERROR)
					.detail("declared rule failure").build();
			}
		};

		IllegalStateException actual = Assert.assertThrows(IllegalStateException.class,
			() -> builder(rule).buildAnalysis(privateAggregateProgram()));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("RULE_ERROR"));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("occurrence="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("hop="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("op="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("inputLayouts="));
	}

	private static void assertContextualFailure(RuntimeException oracleFailure,
		RulesCore.BaseRule rule, String expectedPhase) throws Exception {
		IllegalStateException actual = Assert.assertThrows(IllegalStateException.class,
			() -> builder(rule).buildAnalysis(privateAggregateProgram()));
		Throwable root = actual;
		while(root.getCause() != null)
			root = root.getCause();
		Assert.assertSame("the original oracle exception must remain in the cause chain", oracleFailure, root);
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains(expectedPhase));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("occurrence="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("hop="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("op="));
		Assert.assertTrue(actual.getMessage(), actual.getMessage().contains("inputLayouts="));
	}

	private static boolean calledFrom(String methodName) {
		return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
			frame.getClassName().equals(NeutralPlacementGraphBuilder.class.getName())
				&& frame.getMethodName().equals(methodName)));
	}

	private static NeutralPlacementGraphBuilder builder(RulesCore.BaseRule rule) throws ReflectiveOperationException {
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(rule);
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		Field oracle = NeutralPlacementGraphBuilder.class.getDeclaredField("oracle");
		oracle.setAccessible(true);
		oracle.set(builder, new OracleFacade(registry));
		return builder;
	}

	private static class PlusRule extends RulesCore.BaseRule {
		@Override
		public OpCategory category() {
			return OpCategory.BINARY_EWISE;
		}

		@Override
		public Set<String> opcodes() {
			return Set.of(OpOp2.PLUS.toString());
		}
	}

	private static DMLProgram privateAggregateProgram() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}

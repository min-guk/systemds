/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact rule contract for scalar dimensions read from FederationMap metadata. */
public class FederationMapDimensionRuleTest {
	private static final List<ShapeHint> SHAPES = List.of(
		new ShapeHint(-1, -1, -1),
		new ShapeHint(37, 19, 1024, Optional.empty(), 37, 19, -1, -1));

	@Test
	public void nrowAndNcolPreserveTheExistingCpFallbackForEveryRuntimeInputType() {
		Rulesets.FederationMapDimensionRule rule = new Rulesets.FederationMapDimensionRule();
		List<FType> inputs = new ArrayList<>();
		inputs.add(null);
		inputs.addAll(Arrays.asList(FType.values()));
		for(OpOp1 operation : List.of(OpOp1.NROW, OpOp1.NCOL)) {
			OpSig matrix = signature(operation, OpSig.InputKind.MATRIX);
			OpSig frame = signature(operation, OpSig.InputKind.FRAME);
			for(OpSig signature : List.of(matrix, frame)) {
				Assert.assertTrue(rule.supports(signature));
				Assert.assertEquals(Set.of(), rule.decisionDependencies(signature).orElseThrow()
					.determinantPositions());
				Assert.assertEquals(Set.of(), rule.candidateFamilyDependencies(signature).orElseThrow()
					.allPositions());
				Assert.assertTrue(rule.profile(signature, List.of(inputs), SHAPES.get(0)).outputs().isEmpty());
				for(FType input : inputs)
					for(ShapeHint shape : SHAPES)
						assertHistoricalFallback(rule.caps(signature,
							java.util.Collections.singletonList(input), shape), signature);
			}
		}
	}

	@Test
	public void onlyUnaryMatrixOrFrameDimensionSignaturesAreClaimed() {
		Rulesets.FederationMapDimensionRule rule = new Rulesets.FederationMapDimensionRule();
		for(OpSig unsupported : List.of(
			new OpSig(OpOp1.NROW.toString(), OpCategory.OTHER, Map.of()),
			new OpSig(OpOp1.NCOL.toString(), OpCategory.OTHER, Map.of(),
				OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX),
			signature(OpOp1.NROW, OpSig.InputKind.SCALAR),
			signature(OpOp1.NCOL, OpSig.InputKind.UNKNOWN),
			signature(OpOp1.EXP, OpSig.InputKind.MATRIX)))
			Assert.assertFalse(unsupported.opcode(), rule.supports(unsupported));
	}

	@Test
	public void defaultRegistryPublishesOneShapeIndependentRectangle() {
		RulesCore.RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		RulesCore.OracleEngine oracle = new RulesCore.OracleEngine(registry);
		List<FType> domain = new ArrayList<>();
		domain.add(null);
		domain.addAll(Arrays.asList(FType.values()));
		for(OpOp1 operation : List.of(OpOp1.NROW, OpOp1.NCOL)) {
			OpSig signature = signature(operation, OpSig.InputKind.MATRIX);
			Assert.assertTrue("dimension rule must be registered by exact opcode",
				registry.byOpcode(operation.toString()).isPresent());
			Assert.assertEquals(Set.of(), oracle.decisionDependencies(signature).orElseThrow()
				.enumeratedPositions());
			Assert.assertEquals(Set.of(), oracle.candidateFamilyDependencies(signature).orElseThrow()
				.allPositions());
			for(FType input : domain)
				assertHistoricalFallback(oracle.decide(signature,
					java.util.Collections.singletonList(input), SHAPES.get(0)), signature);
		}
	}

	private static OpSig signature(OpOp1 operation, OpSig.InputKind kind) {
		return OpSig.of(operation.toString(), OpCategory.OTHER, Map.of(), kind);
	}

	private static void assertHistoricalFallback(OpCaps caps, OpSig signature) {
		Assert.assertEquals(signature.opcode(), caps.opcode());
		Assert.assertEquals(OpCategory.OTHER, caps.category());
		Assert.assertEquals(ExecType.CP, caps.exec());
		Assert.assertEquals(FederatedOutput.LOUT, caps.placement());
		Assert.assertFalse(caps.foutFType().isPresent());
		Assert.assertEquals(ReasonCode.NO_RULE, caps.reason());
	}
}

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

/** Exact rule contract for collecting a 1x1 matrix as a scalar. */
public class CastAsScalarRuleTest {
	private static final List<ShapeHint> SHAPES = List.of(
		new ShapeHint(-1, -1, -1),
		new ShapeHint(1, 1, 1024, Optional.empty(), 1, 1, -1, -1));

	@Test
	public void everyRuntimeInputTypePreservesHistoricalCpFallback() {
		Rulesets.CastAsScalarRule rule = new Rulesets.CastAsScalarRule();
		OpSig signature = signature(OpSig.InputKind.MATRIX);
		List<FType> inputs = allRuntimeTypes();

		Assert.assertTrue(rule.supports(signature));
		Assert.assertEquals(Set.of(), rule.decisionDependencies(signature).orElseThrow()
			.enumeratedPositions());
		Assert.assertEquals(Set.of(), rule.candidateFamilyDependencies(signature).orElseThrow()
			.allPositions());
		Assert.assertTrue(rule.profile(signature, List.of(inputs), SHAPES.get(0)).outputs().isEmpty());
		for(FType input : inputs)
			for(ShapeHint shape : SHAPES)
				assertHistoricalFallback(rule.caps(signature,
					java.util.Collections.singletonList(input), shape), signature);
	}

	@Test
	public void onlyUnaryMatrixCastAsScalarSignatureIsClaimed() {
		Rulesets.CastAsScalarRule rule = new Rulesets.CastAsScalarRule();
		for(OpSig unsupported : List.of(
			new OpSig(OpOp1.CAST_AS_SCALAR.toString(), OpCategory.OTHER, Map.of()),
			new OpSig(OpOp1.CAST_AS_SCALAR.toString(), OpCategory.OTHER, Map.of(),
				OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX),
			signature(OpSig.InputKind.SCALAR), signature(OpSig.InputKind.FRAME),
			signature(OpSig.InputKind.UNKNOWN),
			OpSig.of(OpOp1.NROW.toString(), OpCategory.OTHER, Map.of(), OpSig.InputKind.MATRIX)))
			Assert.assertFalse(unsupported.opcode(), rule.supports(unsupported));
	}

	@Test
	public void defaultRegistryPublishesEmptyDeterminantsWithoutChangingCaps() {
		RulesCore.RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		RulesCore.OracleEngine oracle = new RulesCore.OracleEngine(registry);
		OpSig signature = signature(OpSig.InputKind.MATRIX);
		Assert.assertTrue(registry.byOpcode(OpOp1.CAST_AS_SCALAR.toString()).isPresent());
		Assert.assertEquals(Set.of(), oracle.decisionDependencies(signature).orElseThrow()
			.enumeratedPositions());
		Assert.assertEquals(Set.of(), oracle.candidateFamilyDependencies(signature).orElseThrow()
			.allPositions());
		for(FType input : allRuntimeTypes())
			assertHistoricalFallback(oracle.decide(signature,
				java.util.Collections.singletonList(input), SHAPES.get(0)), signature);
	}

	private static OpSig signature(OpSig.InputKind inputKind) {
		return OpSig.of(OpOp1.CAST_AS_SCALAR.toString(), OpCategory.OTHER, Map.of(), inputKind);
	}

	private static List<FType> allRuntimeTypes() {
		List<FType> result = new ArrayList<>();
		result.add(null);
		result.addAll(Arrays.asList(FType.values()));
		return result;
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

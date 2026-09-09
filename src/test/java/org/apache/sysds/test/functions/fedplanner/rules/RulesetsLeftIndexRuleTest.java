/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.test.functions.fedplanner.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsLeftIndexRuleTest {
	private final Rulesets.LeftIndexRule rule = new Rulesets.LeftIndexRule();

	@Test
	public void localMatrixCanUseSingleRangeFullRhsAsNativeAnchor() {
		ShapeHint hint = fullSingle();
		assertFederatedFull(caps(types(null, FType.FULL), hint,
			InputKind.MATRIX, InputKind.MATRIX));
		assertSingleRangeProof(hint, false);
	}

	@Test
	public void localMatrixProfileIncludesFullRhsAnchor() {
		FTypeProfile profile = rule.profile(sig(InputKind.MATRIX, InputKind.MATRIX),
			List.of(types((FType) null), List.of(FType.FULL)), fullSingle());

		assertEquals(List.of(FType.FULL), profile.outputs());
	}

	@Test
	public void fullLhsAndFullRhsCanUpdateOnOneWorker() {
		ShapeHint hint = fullSingle();
		assertFederatedFull(caps(List.of(FType.FULL, FType.FULL), hint,
			InputKind.MATRIX, InputKind.MATRIX));
		assertSingleRangeProof(hint, false);
	}

	@Test
	public void localLhsRequiresProvenSingleRangeFullRhs() {
		ShapeHint multiple = new ShapeHint(-1, -1, 0, false);
		assertCp(caps(types(null, FType.FULL), multiple,
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(multiple, false);

		ShapeHint unknown = unknownShape();
		assertCp(caps(types(null, FType.FULL), unknown,
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(unknown, true);
	}

	@Test
	public void fullFullRequiresProvenSingleRangeMaps() {
		ShapeHint multiple = new ShapeHint(-1, -1, 0, false);
		assertCp(caps(List.of(FType.FULL, FType.FULL), multiple,
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(multiple, false);

		ShapeHint unknown = unknownShape();
		assertCp(caps(List.of(FType.FULL, FType.FULL), unknown,
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(unknown, true);
	}

	@Test
	public void fullLhsDoesNotAdvertiseUnsupportedLocalMatrixUpdate() {
		assertCp(caps(types(FType.FULL, null), fullSingle(),
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
	}

	@Test
	public void unsupportedFederatedMatrixLayoutsRemainClosed() {
		for(List<FType> inputTypes : List.of(
			List.of(FType.FULL, FType.ROW),
			List.of(FType.FULL, FType.COL),
			List.of(FType.FULL, FType.PART),
			List.of(FType.FULL, FType.OTHER),
			List.of(FType.ROW, FType.BROADCAST),
			List.of(FType.COL, FType.OTHER),
			List.of(FType.ROW, FType.FULL),
			List.of(FType.COL, FType.FULL),
			List.of(FType.ROW, FType.ROW),
			List.of(FType.COL, FType.COL)))
			assertCp(caps(inputTypes, fullSingle(), InputKind.MATRIX, InputKind.MATRIX),
				ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
	}

	@Test
	public void fullLhsLocalScalarRequiresProvenSingleRangeMap() {
		assertFederatedFull(caps(types(FType.FULL, null), fullSingle(),
			InputKind.MATRIX, InputKind.SCALAR));
		assertFederatedFull(caps(types(FType.FULL, null), fullSingle(),
			InputKind.FRAME, InputKind.SCALAR));

		ShapeHint multiple = new ShapeHint(-1, -1, 0, false);
		assertCp(caps(types(FType.FULL, null), multiple,
			InputKind.MATRIX, InputKind.SCALAR), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(multiple, false);

		ShapeHint unknown = unknownShape();
		assertCp(caps(types(FType.FULL, null), unknown,
			InputKind.MATRIX, InputKind.SCALAR), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertSingleRangeProof(unknown, true);
	}

	@Test
	public void mapLeftIndexUsesTheSameStrictFullCapability() {
		assertFederatedFull(caps(Opcodes.MAPLEFTINDEX, types(null, FType.FULL), fullSingle(),
			InputKind.MATRIX, InputKind.MATRIX));
	}

	@Test
	public void fullMatrixRhsDoesNotAdvertiseAFrameLhsNativePath() {
		assertCp(caps(types(null, FType.FULL), fullSingle(),
			InputKind.FRAME, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
		assertCp(caps(types(FType.FULL, FType.FULL), fullSingle(),
			InputKind.FRAME, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

		FTypeProfile profile = rule.profile(sig(InputKind.FRAME, InputKind.MATRIX),
			List.of(types((FType) null), List.of(FType.FULL)), fullSingle());
		assertFalse(profile.outputs().contains(FType.FULL));
	}

	@Test
	public void partLhsRetainsOnlyTheExistingScalarPath() {
		assertFederated(caps(types(FType.PART, null), unknownShape(),
			InputKind.MATRIX, InputKind.SCALAR), FType.PART);
		assertCp(caps(types(FType.PART, null), unknownShape(),
			InputKind.MATRIX, InputKind.MATRIX), ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
	}

	@Test
	public void rowAndColLhsRetainExistingLocalMatrixAndScalarPaths() {
		for(FType type : List.of(FType.ROW, FType.COL)) {
			assertFederated(caps(types(type, null), unknownShape(),
				InputKind.MATRIX, InputKind.MATRIX), type);
			assertFederated(caps(types(type, null), unknownShape(),
				InputKind.MATRIX, InputKind.SCALAR), type);
		}
	}

	private OpCaps caps(List<FType> types, ShapeHint hint, InputKind... inputKinds) {
		return caps(Opcodes.LEFT_INDEX, types, hint, inputKinds);
	}

	private OpCaps caps(Opcodes opcode, List<FType> types, ShapeHint hint, InputKind... inputKinds) {
		return rule.caps(sig(opcode, inputKinds), types, hint);
	}

	private static OpSig sig(InputKind... inputKinds) {
		return sig(Opcodes.LEFT_INDEX, inputKinds);
	}

	private static OpSig sig(Opcodes opcode, InputKind... inputKinds) {
		return OpSig.of(opcode.toString(), OpCategory.INDEXING, Map.of(), inputKinds);
	}

	private static List<FType> types(FType... types) {
		return Arrays.asList(types);
	}

	private static ShapeHint fullSingle() {
		return new ShapeHint(-1, -1, 0, true);
	}

	private static ShapeHint unknownShape() {
		return new ShapeHint(-1, -1, 0);
	}

	private static void assertFederatedFull(OpCaps caps) {
		assertFederated(caps, FType.FULL);
	}

	private static void assertFederated(OpCaps caps, FType type) {
		assertEquals(ExecType.FED, caps.exec());
		assertEquals(FederatedOutput.FOUT, caps.placement());
		assertEquals(ReasonCode.OK, caps.reason());
		assertTrue(caps.foutEnabled());
		assertEquals(type, caps.foutFType().orElse(null));
	}

	private static void assertCp(OpCaps caps, ReasonCode reason) {
		assertEquals(ExecType.CP, caps.exec());
		assertEquals(FederatedOutput.LOUT, caps.placement());
		assertEquals(reason, caps.reason());
		assertFalse(caps.foutEnabled());
		assertFalse(caps.foutFType().isPresent());
	}

	private static void assertSingleRangeProof(ShapeHint hint, boolean missing) {
		assertTrue(hint.proof().requiredFacts().contains("fullSinglePartition"));
		assertEquals(missing, hint.proof().missingRequiredFacts().contains("fullSinglePartition"));
	}
}

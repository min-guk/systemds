/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Assert;
import org.junit.Test;

public class RulesetShapeIndependentKernelContractTest {
	private static final List<FType> SMALL_DOMAIN = Arrays.asList(
		null, FType.ROW, FType.COL, FType.FULL, FType.BROADCAST, FType.PART, FType.OTHER);
	private static final List<HintFactory> HINTS = List.of(
		() -> new ShapeHint(-1, -1, -1, Optional.empty(), -1, -1, -1, -1),
		() -> new ShapeHint(1, 1, 1, Optional.of(true), 1, 1, 1, 1),
		() -> new ShapeHint(8, 3, 1000, Optional.of(false), 8, 3, 3, 5),
		() -> new ShapeHint(3, 8, 512, Optional.empty(), 3, 8, 5, 3));

	@Test
	public void advertisedKernelsAreExhaustivelyShapeAndNonDeterminantIndependent() {
		for(KernelCase kernel : kernels())
			assertKernelContract(kernel);
	}

	@Test
	public void invalidAritiesAndShapeSensitiveFamiliesRemainUndeclared() {
		assertNoKernel(new Rulesets.UnaryElemwiseRule(), sig(OpOp1.EXP.toString(), OpCategory.OTHER));
		assertNoKernel(new Rulesets.ReorgUnaryRule(), sig(ReOrgOp.TRANS.toString(), OpCategory.REORG,
			OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR));
		assertNoKernel(new Rulesets.ReorgUnaryRule(), sig(ReOrgOp.ROLL.toString(), OpCategory.REORG,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX));
		assertNoKernel(new Rulesets.AggUnaryRule(), sig("uak+", OpCategory.AGG_UNARY,
			Map.of("direction", "ROW", "aggOp", "SUM")));
		assertNoKernel(new Rulesets.ReblockRule(), sig(Opcodes.RBLK.toString(), OpCategory.REORG));
		assertNoKernel(new Rulesets.CentralMomentRule(), sig(OpOp3.MOMENT.toString(), OpCategory.AGG_UNARY,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX));
		assertNoKernel(new Rulesets.PlacementAliasRule(), sig(OpOp1._PLACEMENT.toString(), OpCategory.OTHER));
		assertNoKernel(new Rulesets.VariableWriteRule(), sig(Opcodes.WRITE.toString(), OpCategory.OTHER,
			OpSig.InputKind.MATRIX));

		assertNoKernel(new Rulesets.BinaryMMRule(), sig(Opcodes.MMULT.toString(), OpCategory.BINARY_MM,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX));
		assertNoKernel(new Rulesets.BinaryElemwiseRule(), sig(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX));
		assertNoKernel(new Rulesets.RightIndexRule(), sig("rightIndex", OpCategory.INDEXING,
			OpSig.InputKind.MATRIX));
		assertNoKernel(new Rulesets.FunctionOutputRule(), sig(OpOpData.FUNCTIONOUTPUT.toString(), OpCategory.OTHER,
			OpSig.InputKind.MATRIX));
	}

	private static List<KernelCase> kernels() {
		return List.of(
			kernel("unary", new Rulesets.UnaryElemwiseRule(),
				sig(OpOp1.EXP.toString(), OpCategory.OTHER, OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("reorg-trans", new Rulesets.ReorgUnaryRule(),
				sig(ReOrgOp.TRANS.toString(), OpCategory.REORG, OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("reorg-roll", new Rulesets.ReorgUnaryRule(),
				sig(ReOrgOp.ROLL.toString(), OpCategory.REORG,
					OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR), Set.of(0)),
			kernel("agg-unary", new Rulesets.AggUnaryRule(),
				sig("uak+", OpCategory.AGG_UNARY, Map.of("direction", "ROW", "aggOp", "SUM"),
					OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("reblock", new Rulesets.ReblockRule(),
				sig(Opcodes.RBLK.toString(), OpCategory.REORG, OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("central-moment-unweighted", new Rulesets.CentralMomentRule(),
				sig(OpOp3.MOMENT.toString(), OpCategory.AGG_UNARY, OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("central-moment-weighted", new Rulesets.CentralMomentRule(),
				sig(OpOp3.MOMENT.toString(), OpCategory.AGG_UNARY,
					OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX), Set.of(0, 1)),
			kernel("solve-deny", new Rulesets.SolveRule(),
				sig(Opcodes.SOLVE.toString(), OpCategory.BINARY_EWISE,
					OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX), Set.of()),
			kernel("quantile-deny", new Rulesets.QuantileInterquantileCtableDenyRule(),
				sig(OpOp3.QUANTILE.toString(), OpCategory.OTHER,
					OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR, OpSig.InputKind.SCALAR), Set.of()),
			kernel("contains", new Rulesets.ContainsRule(),
				sig(Opcodes.CONTAINS.toString(), OpCategory.OTHER,
					OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR), Set.of(0)),
			kernel("replace", new Rulesets.ReplaceRule(),
				sig(Opcodes.REPLACE.toString(), OpCategory.OTHER,
					OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR, OpSig.InputKind.SCALAR), Set.of(0)),
			kernel("placement-alias", new Rulesets.PlacementAliasRule(),
				sig(OpOp1._PLACEMENT.toString(), OpCategory.OTHER, OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("variable-write", new Rulesets.VariableWriteRule(),
				sig(Opcodes.WRITE.toString(), OpCategory.OTHER, Map.of("var.write.federated", "true"),
					OpSig.InputKind.MATRIX, OpSig.InputKind.SCALAR, OpSig.InputKind.SCALAR), Set.of(0)),
			kernel("transient-write", new Rulesets.TransientWriteRule(),
				sig(OpOpData.TRANSIENTWRITE.toString(), OpCategory.OTHER,
					OpSig.InputKind.MATRIX), Set.of(0)),
			kernel("transient-read", new Rulesets.TransientReadRule(),
				sig(OpOpData.TRANSIENTREAD.toString(), OpCategory.OTHER,
					Map.of("var.read.ftype", "ROW")), Set.of()));
	}

	private static void assertKernelContract(KernelCase kernel) {
		Set<Integer> actual = kernel.rule.shapeIndependentDecision(kernel.sig)
			.orElseThrow(() -> new AssertionError("missing kernel: " + kernel.name))
			.determinantPositions();
		Assert.assertEquals(kernel.name, kernel.determinants, actual);

		Map<List<FType>, CapsSnapshot> byDeterminants = new LinkedHashMap<>();
		for(List<FType> tuple : tuples(kernel.sig.arity())) {
			List<FType> key = actual.stream().map(tuple::get).toList();
			for(HintFactory factory : HINTS) {
				ShapeHint hint = factory.create();
				CapsSnapshot snapshot = CapsSnapshot.of(kernel.rule.caps(kernel.sig, tuple, hint));
				Assert.assertTrue(kernel.name + " consulted shape for " + tuple,
					hint.proof().requiredFacts().isEmpty());
				CapsSnapshot prior = byDeterminants.putIfAbsent(key, snapshot);
				if(prior != null)
					Assert.assertEquals(kernel.name + " changed outside determinants " + key, prior, snapshot);
			}
		}
	}

	private static List<List<FType>> tuples(int arity) {
		List<List<FType>> out = new ArrayList<>();
		enumerate(arity, new ArrayList<>(), out);
		return out;
	}

	private static void enumerate(int arity, List<FType> prefix, List<List<FType>> out) {
		if(prefix.size() == arity) {
			out.add(new ArrayList<>(prefix));
			return;
		}
		for(FType value : SMALL_DOMAIN) {
			prefix.add(value);
			enumerate(arity, prefix, out);
			prefix.remove(prefix.size() - 1);
		}
	}

	private static void assertNoKernel(Rule rule, OpSig sig) {
		Assert.assertTrue(rule.getClass().getSimpleName(), rule.shapeIndependentDecision(sig).isEmpty());
	}

	private static KernelCase kernel(String name, Rule rule, OpSig sig, Set<Integer> determinants) {
		return new KernelCase(name, rule, sig, determinants);
	}

	private static OpSig sig(String opcode, OpCategory category, OpSig.InputKind... inputs) {
		return sig(opcode, category, Map.of(), inputs);
	}

	private static OpSig sig(String opcode, OpCategory category, Map<String,String> attrs,
		OpSig.InputKind... inputs) {
		return OpSig.of(opcode, category, attrs, inputs);
	}

	private record KernelCase(String name, Rule rule, OpSig sig, Set<Integer> determinants) { }
	private interface HintFactory { ShapeHint create(); }
	private record NoteSnapshot(Object code, String message) { }
	private record CapsSnapshot(Object category, String opcode, Object exec, Object placement,
		boolean foutEnabled, FType foutType, Object reason, String detail, List<NoteSnapshot> notes) {
		private static CapsSnapshot of(OpCaps caps) {
			return new CapsSnapshot(caps.category(), caps.opcode(), caps.exec(), caps.placement(),
				caps.foutEnabled(), caps.foutFType().orElse(null), caps.reason(), caps.detail().orElse(""),
				caps.notes().stream().map(note -> new NoteSnapshot(note.code(), note.message())).toList());
		}
	}
}

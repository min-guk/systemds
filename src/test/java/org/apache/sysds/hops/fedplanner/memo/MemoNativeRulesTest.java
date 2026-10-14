/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeRulesTest {
	@Test
	public void equivalentUnarySourcesReuseSourceFreeCatalogEntry() {
		MemoNativeRules catalog = new MemoNativeRules();
		MemoNativeRules.Result first = catalog.rules(unary("X"), 3, shape(8, 4, 8, 4, -1, -1));
		MemoNativeRules.Result second = catalog.rules(unary("differentSource"), 3,
			shape(8, 4, 8, 4, -1, -1));

		Assert.assertSame("cache identity excludes source name and realization", first, second);
		Assert.assertEquals(new MemoNativeRules.CacheStats(1, 1, 1), catalog.cacheStats());
		Assert.assertEquals("cache hits do not recount native generation",first.generation(),
			catalog.generationStats());
		Assert.assertTrue(first.diagnostics().toString(), first.complete());
		Assert.assertTrue(first.rules().stream().anyMatch(rule -> rule.exec() == ExecType.CP
			&& rule.inputs().equals(List.of(MemoPartition.LOCAL))
			&& rule.output() == MemoPartition.LOCAL));
		Assert.assertTrue("the existing oracle supplies a native partition-preserving unary path",
			first.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.ROW));
	}

	@Test
	public void scalarOperandIsARoleAndNeverBecomesAFederatedPayloadAxis() {
		Hop matrix = matrix("X", 8, 4);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix, new LiteralOp(1D));
		plus.setDim1(8);
		plus.setDim2(4);
		MemoNativeRules.Result result = new MemoNativeRules().rules(plus, 3,
			shape(8, 4, 8, 4, -1, -1));

		Assert.assertEquals(List.of(InputRole.PAYLOAD, InputRole.SCALAR), result.inputRoles());
		Assert.assertTrue(result.rules().stream().allMatch(rule ->
			rule.inputs().get(1) == MemoPartition.LOCAL));
		Assert.assertFalse("a temporary scalar broadcast is not a persistent REP input or output",
			result.rules().stream().anyMatch(rule -> rule.inputs().equals(
				List.of(MemoPartition.LOCAL, MemoPartition.LOCAL))
				&& rule.exec() == ExecType.FED && rule.output() == MemoPartition.REP));
		Assert.assertTrue(result.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED));
	}

	@Test
	public void metadataReadDoesNotRequireRawPayloadCollection() {
		UnaryOp nrow = new UnaryOp("nrow", DataType.SCALAR, ValueType.INT64,
			OpOp1.NROW, matrix("X", 8, 4));
		MemoNativeRules.Result result = new MemoNativeRules().rules(nrow, 3,
			shape(1, 1, 8, 4, -1, -1));

		Assert.assertEquals(List.of(InputRole.METADATA), result.inputRoles());
		Assert.assertTrue(result.metadataInputs().get(0));
		Assert.assertTrue("CP may inspect ROW FederationMap metadata without a COLLECT",
			result.rules().stream().anyMatch(rule -> rule.exec() == ExecType.CP
				&& rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.LOCAL));
	}

	@Test
	public void aggregateUnaryAndMatrixMultiplyExposeOracleFedVariants() {
		Hop x = matrix("X", 8, 4);
		AggUnaryOp rowSums = new AggUnaryOp("rowsums", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, x);
		rowSums.setDim1(8);
		rowSums.setDim2(1);
		MemoNativeRules.Result aggregate = new MemoNativeRules().rules(rowSums, 3,
			shape(8, 1, 8, 4, -1, -1));
		Assert.assertTrue(aggregate.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED));

		Hop left = matrix("L", 8, 4);
		Hop right = matrix("R", 4, 3);
		AggBinaryOp mm = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
		mm.setDim1(8);
		mm.setDim2(3);
		MemoNativeRules.Result multiply = new MemoNativeRules().rules(mm, 3,
			shape(8, 3, 8, 4, 4, 3));
		Assert.assertTrue(multiply.diagnostics().toString(), multiply.complete());
		Assert.assertTrue("MM must not disappear from the source-free catalog",
			multiply.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED));
		Assert.assertTrue("Oracle FED/FOUT MM retains the verified forced-local lowering variant",
			multiply.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.output() == MemoPartition.LOCAL));

		Hop vectorRight = matrix("v", 4, 1);
		AggBinaryOp vectorMm = new AggBinaryOp("vector-mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, vectorRight);
		vectorMm.setDim1(8);
		vectorMm.setDim2(1);
		MemoNativeRules.Result vector = new MemoNativeRules().rules(vectorMm, 3,
			shape(8, 1, 8, 4, 4, 1));
		Assert.assertTrue("the vector aggregate-binary local runtime variant stays explicit",
			vector.rules().stream().anyMatch(rule -> rule.variant().startsWith("fed/vector-local/")));
	}

	@Test
	public void indexingPublishesOnlyCanonicallyProvedPersistentOutput() {
		Hop input = matrix("X", 8, 4);
		IndexingOp identitySlice = new IndexingOp("identity", DataType.MATRIX, ValueType.FP64,
			input, lit(1), lit(8), lit(1), lit(4), false, false);
		identitySlice.setDim1(8);
		identitySlice.setDim2(4);
		MemoNativeRules.Result identity = new MemoNativeRules().rules(identitySlice, 3,
			shape(8, 4, 8, 4, -1, -1));
		Assert.assertTrue(identity.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
			&& rule.inputs().get(0) == MemoPartition.ROW && rule.output() == MemoPartition.ROW));

		IndexingOp partial = new IndexingOp("partial", DataType.MATRIX, ValueType.FP64,
			input, lit(1), lit(4), lit(1), lit(4), false, false);
		partial.setDim1(4);
		partial.setDim2(4);
		MemoNativeRules.Result unresolved = new MemoNativeRules().rules(partial, 3,
			shape(4, 4, 8, 4, -1, -1));
		Assert.assertTrue("a proven noncanonical pattern is an exclusion, not missing catalog coverage",
			unresolved.complete());
		Assert.assertTrue(unresolved.diagnostics().stream()
			.anyMatch(d -> "INDEXING_NONCANONICAL_NATIVE_OUTPUT".equals(d.code())
				&& d.kind() == MemoNativeRules.DiagnosticKind.POLICY_EXCLUSION));
		Assert.assertTrue("persistent output exclusion must not erase a supported forced-local runtime path",
			unresolved.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().get(0) == MemoPartition.ROW
				&& rule.output() == MemoPartition.LOCAL));
	}

	@Test
	public void indexingUsesOrderedRemainderCanonicalRanges() {
		Hop input = matrix("X", 3, 4);
		IndexingOp slice = new IndexingOp("slice", DataType.MATRIX, ValueType.FP64,
			input, lit(1), lit(2), lit(1), lit(4), false, false);
		slice.setDim1(2);
		slice.setDim2(4);
		MemoNativeRules.Result result = new MemoNativeRules().rules(slice, 2,
			shape(2, 4, 3, 4, -1, -1));

		Assert.assertFalse("[0,2)+[2,3) input ranges cannot become canonical [0,1)+[1,2) ranges",
			result.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().get(0) == MemoPartition.ROW && rule.output() == MemoPartition.ROW));
		Assert.assertTrue(result.diagnostics().stream()
			.anyMatch(d -> "INDEXING_NONCANONICAL_NATIVE_OUTPUT".equals(d.code())));
	}

	@Test
	public void canonicalEffectsCoverTransposeAggregateAndBindAtTwoWorkers() {
		Hop input = matrix("X", 8, 4);
		ReorgOp transpose = new ReorgOp("t", DataType.MATRIX, ValueType.FP64, ReOrgOp.TRANS, input);
		transpose.setDim1(4);
		transpose.setDim2(8);
		MemoNativeRules.Result transposed = new MemoNativeRules().rules(transpose, 2,
			shape(4, 8, 8, 4, -1, -1));
		Assert.assertTrue(transposed.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
			&& rule.inputs().equals(List.of(MemoPartition.ROW)) && rule.output() == MemoPartition.COL));

		AggUnaryOp rowSums = new AggUnaryOp("rowsums", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, input);
		rowSums.setDim1(8);
		rowSums.setDim2(1);
		MemoNativeRules.Result aggregate = new MemoNativeRules().rules(rowSums, 2,
			shape(8, 1, 8, 4, -1, -1));
		Assert.assertTrue(aggregate.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
			&& rule.inputs().equals(List.of(MemoPartition.ROW)) && rule.output() == MemoPartition.ROW));

		BinaryOp cbind = new BinaryOp("cbind", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND,
			matrix("A", 8, 2), matrix("B", 8, 2));
		cbind.setDim1(8);
		cbind.setDim2(4);
		MemoNativeRules.Result appended = new MemoNativeRules().rules(cbind, 2,
			shape(8, 4, 8, 2, 8, 2));
		Assert.assertTrue(appended.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
			&& rule.output() == MemoPartition.ROW));
		Assert.assertFalse("cbind may not publish a fixed canonical COL output",
			appended.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.output() == MemoPartition.COL));
		Assert.assertTrue(appended.diagnostics().stream()
			.anyMatch(d -> "APPEND_NONCANONICAL_NATIVE_OUTPUT".equals(d.code())));
	}

	@Test
	public void cacheKeyKeepsAggregateDirectionAndInnerOuterSelectors() {
		MemoNativeRules catalog = new MemoNativeRules();
		Hop input = matrix("X", 8, 4);
		AggUnaryOp byRow = new AggUnaryOp("sum", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, input);
		byRow.setDim1(8);
		byRow.setDim2(1);
		AggUnaryOp byCol = new AggUnaryOp("sum", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, input);
		byCol.setDim1(1);
		byCol.setDim2(4);
		catalog.rules(byRow, 2, shape(8, 1, 8, 4, -1, -1));
		catalog.rules(byCol, 2, shape(1, 4, 8, 4, -1, -1));
		AggBinaryOp multiply = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, matrix("L", 8, 4), matrix("R", 4, 3));
		multiply.setDim1(8);
		multiply.setDim2(3);
		AggBinaryOp plusAggregate = new AggBinaryOp("plus-aggregate", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, AggOp.SUM, matrix("L2", 8, 4), matrix("R2", 4, 3));
		plusAggregate.setDim1(8);
		plusAggregate.setDim2(3);
		catalog.rules(multiply, 2, shape(8, 3, 8, 4, 4, 3));
		catalog.rules(plusAggregate, 2, shape(8, 3, 8, 4, 4, 3));

		Assert.assertEquals("different aggregate selectors must not collide",
			new MemoNativeRules.CacheStats(0, 4, 4), catalog.cacheStats());
	}

	@Test
	public void excludedReshapeIsEvaluatedPerOccurrenceUntilCanonicalOpSigIsPublic() {
		MemoNativeRules catalog = new MemoNativeRules();
		for(int i = 0; i < 2; i++) {
			ReorgOp reshape = new ReorgOp("reshape" + i, DataType.MATRIX, ValueType.FP64,
				ReOrgOp.RESHAPE, List.of(matrix("X" + i, 8, 4), lit(4), lit(8), lit(-1),
					new LiteralOp(true)));
			reshape.setDim1(4);
			reshape.setDim2(8);
			catalog.rules(reshape, 2, shape(4, 8, 8, 4, -1, -1));
		}
		Assert.assertEquals(new MemoNativeRules.CacheStats(0, 2, 0), catalog.cacheStats());
	}

	@Test
	public void oneWorkerNormalizesEveryFederatedLayoutToRep() {
		MemoNativeRules.Result result = new MemoNativeRules().rules(unary("X"), 1,
			new ShapeHint(8, 4, 1000, Optional.of(true), 8, 4, -1, -1));

		Assert.assertTrue(result.rules().stream().flatMap(rule -> rule.inputs().stream())
			.noneMatch(partition -> partition == MemoPartition.ROW || partition == MemoPartition.COL));
		Assert.assertTrue(result.rules().stream().filter(rule -> rule.exec() == ExecType.FED)
			.allMatch(rule -> rule.output() == MemoPartition.REP || rule.output() == MemoPartition.LOCAL));
	}

	@Test
	public void glmNaryMultiplyIsNativeWhileNaryPlusIsVerifiedCpOnly() {
		Hop first = matrix("X", 8, 4);
		Hop second = matrix("W", 8, 4);
		Hop scalar = new LiteralOp(2D);
		NaryOp multiply = new NaryOp("product", DataType.MATRIX, ValueType.FP64,
			OpOpN.MULT, first, second, scalar);
		multiply.setDim1(8);
		multiply.setDim2(4);
		MemoNativeRules.Result supported = new MemoNativeRules().rules(multiply, 2,
			shape(8, 4, 8, 4, 8, 4));
		Assert.assertTrue(supported.diagnostics().toString(), supported.complete());
		Assert.assertTrue(supported.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED
			&& rule.inputs().get(0) == MemoPartition.ROW
			&& rule.inputs().get(1) == MemoPartition.ROW
			&& rule.inputs().get(2) == MemoPartition.LOCAL
			&& rule.output() == MemoPartition.ROW));

		NaryOp plus = new NaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOpN.PLUS, first, second);
		plus.setDim1(8);
		plus.setDim2(4);
		MemoNativeRules.Result plusResult = new MemoNativeRules().rules(plus, 2,
			shape(8, 4, 8, 4, 8, 4));
		Assert.assertTrue(plusResult.diagnostics().toString(), plusResult.complete());
		Assert.assertFalse("the current Oracle/runtime contract has no nary-plus FED path",
			plusResult.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED));
	}

	@Test
	public void currentWeightedQuaternaryCapabilityRemainsFederated() {
		Hop x = matrix("X", 8, 4);
		Hop u = matrix("U", 8, 2);
		Hop v = matrix("V", 4, 2);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 0, false, false);
		wdivmm.setDim1(8);
		wdivmm.setDim2(4);
		MemoNativeRules.Result result = new MemoNativeRules().rules(wdivmm, 2,
			shape(8, 4, 8, 4, 8, 2));

		Assert.assertTrue(result.diagnostics().toString(), result.complete());
		Assert.assertTrue("current origin/main Oracle advertises direct FED quaternary lowering",
			result.rules().stream().anyMatch(rule -> rule.exec() == ExecType.FED));
	}

	@Test
	public void highArityGenerationStreamsSupportedPrefixesAndCountsCacheMissOnly() {
		Hop[] inputs=new Hop[8];
		for(int position=0;position<inputs.length;position++)
			inputs[position]=matrix("H"+position,8,4);
		NaryOp product=new NaryOp("high-arity-product",DataType.MATRIX,ValueType.FP64,
			OpOpN.MULT,inputs);
		product.setDim1(8);
		product.setDim2(4);
		MemoNativeRules catalog=new MemoNativeRules();
		MemoNativeRules.Result first=catalog.rules(product,2,shape(8,4,8,4,8,4));

		Assert.assertTrue(first.diagnostics().toString(),first.complete());
		Assert.assertTrue("the Oracle must remove known impossible descendants before leaf allocation",
			first.generation().prunedPrefixes()>0);
		Assert.assertTrue("streaming visits fewer leaves than the former 4^8 product",
			first.generation().visitedNativePatterns()<65_536);
		Assert.assertTrue(first.rules().stream().anyMatch(rule -> rule.exec()==ExecType.FED));
		Assert.assertEquals("published-rule counter counts only successful RuleMemo insertions",
			first.rules().size(),first.generation().publishedRules());
		Assert.assertTrue(first.generation().oracleEvaluations()>0);

		Assert.assertEquals(first.generation(),catalog.generationStats());
	}

	@Test
	public void smallNaryCatalogMatchesTheCompleteConcreteRuleSet() {
		NaryOp product=new NaryOp("small-product",DataType.MATRIX,ValueType.FP64,
			OpOpN.MULT,matrix("A",8,4),matrix("B",8,4),matrix("C",8,4));
		product.setDim1(8);
		product.setDim2(4);
		MemoNativeRules.Result result=new MemoNativeRules().rules(product,2,
			shape(8,4,8,4,8,4));

		LinkedHashSet<RuleMemo.Rule> expected=new LinkedHashSet<>();
		expected.add(new RuleMemo.Rule(ExecType.CP,
			List.of(MemoPartition.LOCAL,MemoPartition.LOCAL,MemoPartition.LOCAL),
			List.of(MemoPartition.LOCAL),"cp/local"));
		List<MemoPartition> domain=List.of(MemoPartition.LOCAL,MemoPartition.ROW,
			MemoPartition.COL,MemoPartition.REP);
		for(MemoPartition first : domain)
			for(MemoPartition second : domain)
				for(MemoPartition third : domain) {
					List<MemoPartition> inputs=List.of(first,second,third);
					boolean row=inputs.contains(MemoPartition.ROW);
					boolean col=inputs.contains(MemoPartition.COL);
					if(row == col)
						continue;
					expected.add(new RuleMemo.Rule(ExecType.FED,inputs,
						List.of(row ? MemoPartition.ROW : MemoPartition.COL),"oracle/*/ok"));
				}

		Assert.assertTrue(result.diagnostics().toString(),result.complete());
		Assert.assertEquals("prefix pruning must preserve the original concrete catalog",
			expected.size(),result.rules().size());
		Assert.assertEquals(expected.stream().map(MemoNativeRulesTest::ruleSemantics).collect(
			java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
			result.rules().stream().map(MemoNativeRulesTest::ruleSemantics).collect(
				java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
	}

	@Test
	public void unresolvedCompressedRegionCannotUseTheAllLocalCoverageExemption() {
		RulesCore.RuleRegistry registry=new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
				decisionDependencies(OpSig sig) {
				return java.util.Optional.of(
					new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
						Set.of(0),Set.of(),Set.of("rows")));
			}
			@Override public OpCaps caps(OpSig sig,List<FType> inputs,ShapeHint hint) {
				hint.rows();
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(ExecType.CP)
					.placement(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT)
					.reason(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.NO_FED_INPUT).build();
			}
		});
		BinaryOp plus=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,
			matrix("A",8,4),matrix("B",8,4));
		MemoNativeRules.Result result=new MemoNativeRules(new OracleFacade(registry)).rules(plus,2,
			new ShapeHint(-1,4,1000));

		Assert.assertFalse(result.complete());
		Assert.assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
			"MISSING_SHAPE_FACT".equals(diagnostic.code())
				&& diagnostic.pattern().contains("ROW")));
		Assert.assertEquals(4,result.generation().coverageObligations());
		Assert.assertEquals(4,result.generation().visitedNativePatterns());
	}

	@Test
	public void transientCarrierIsAnExplicitCoverageGapNotAnInventedOperatorRule() {
		DataOp read = matrix("X", 8, 4);
		MemoNativeRules.Result result = new MemoNativeRules().rules(read, 3,
			shape(8, 4, 8, 4, -1, -1));

		Assert.assertFalse(result.complete());
		Assert.assertTrue(result.rules().isEmpty());
		Assert.assertTrue(result.diagnostics().stream()
			.anyMatch(d -> "BOUNDARY_RULE_OWNED_BY_PROGRAM_FACTS".equals(d.code())));
	}

	private static UnaryOp unary(String source) {
		UnaryOp exp = new UnaryOp("exp", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, matrix(source, 8, 4));
		exp.setDim1(8);
		exp.setDim2(4);
		return exp;
	}

	private static String ruleSemantics(RuleMemo.Rule rule) {
		return rule.exec()+"|"+rule.inputs()+"|"+rule.outputs();
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
	}

	private static ShapeHint shape(long rows, long cols, long rowsA, long colsA,
		long rowsB, long colsB) {
		return new ShapeHint(rows, cols, 1000, Optional.empty(), rowsA, colsA, rowsB, colsB);
	}

	private static LiteralOp lit(long value) {
		return new LiteralOp(value);
	}
}

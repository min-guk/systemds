/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.junit.Assert;
import org.junit.Test;

/** One shared executable owner must satisfy every occurrence, not a union of convenient variants. */
public class MemoOwnerRuleIntersectionTest {

	@Test
	public void matrixAndVectorOccurrencesPublishOnlyTheirCommonNativeRules() {
		Fixture fixture = fixture(3, 1);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(fixture.facts(), fixture.sources(), new MemoNativeRules());
		MemoNativeRules oracle = new MemoNativeRules();
		List<Set<RuleMemo.Rule>> perOccurrence = new ArrayList<>();
		for(int occurrence : fixture.facts().occurrenceIds(fixture.kernel())) {
			MemoNativeRules.Result nativeRules = oracle.rules(result.shapes().viewAt(occurrence), 2,
				result.shapes().hintAt(occurrence, MemoBindingResolver.EvaluationContext.empty()));
			Assert.assertTrue(nativeRules.diagnostics().toString(), nativeRules.complete());
			perOccurrence.add(new LinkedHashSet<>(nativeRules.rules()));
		}
		Assert.assertEquals(2, perOccurrence.size());
		Assert.assertNotEquals("The fixture must have genuinely different native capabilities",
			perOccurrence.get(0), perOccurrence.get(1));
		Set<RuleMemo.Rule> intersection = new LinkedHashSet<>(perOccurrence.get(0));
		intersection.retainAll(perOccurrence.get(1));
		Set<RuleMemo.Rule> union = new LinkedHashSet<>(perOccurrence.get(0));
		union.addAll(perOccurrence.get(1));
		int owner = fixture.facts().compiledOwnerId(fixture.kernel());
		Assert.assertFalse(intersection.isEmpty());
		Assert.assertTrue(union.size() > intersection.size());
		Assert.assertEquals(intersection, new LinkedHashSet<>(result.memo().rules(owner)));
		Assert.assertEquals("rule ordinals must retain the first context's order",
			new ArrayList<>(intersection), result.memo().rules(owner));
		Assert.assertTrue(result.memo().complete(owner));
	}

	@Test
	public void unknownSecondOccurrenceNeverInheritsFirstOccurrenceCompleteness() {
		Fixture fixture = fixture(true, 3, -1);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(fixture.facts(), fixture.sources(), new MemoNativeRules());
		int owner = fixture.facts().compiledOwnerId(fixture.kernel());
		Assert.assertFalse("A known first context is not a proof of complete shared-owner coverage",
			result.memo().complete(owner));
		Assert.assertFalse(result.diagnostics().isEmpty());
		Assert.assertTrue("Known local capability remains represented without accepting the unresolved model",
			result.memo().rules(owner).stream().anyMatch(rule -> rule.exec() == ExecType.CP));
	}

	@Test
	public void repeatedOccurrenceShapesDoNotChangeTheOwnerIntersection() {
		Fixture two = fixture(3, 1);
		Fixture repeated = fixture(3, 1, 3, 1);
		MemoRuleBuilder.Result first = MemoRuleBuilder.build(two.facts(), two.sources(), new MemoNativeRules());
		MemoRuleBuilder.Result second = MemoRuleBuilder.build(repeated.facts(), repeated.sources(), new MemoNativeRules());
		Assert.assertEquals(first.memo().rules(two.facts().compiledOwnerId(two.kernel())),
			second.memo().rules(repeated.facts().compiledOwnerId(repeated.kernel())));
		Assert.assertEquals(4, repeated.facts().occurrenceIds(repeated.kernel()).size());
	}

	@Test
	public void repeatedKnownContextsDoNotHideUnknownCoverageInEitherOrder() {
		for(int[] columns : new int[][] {{3, 3, -1, 3}, {-1, 3, 3, 3}, {3, -1, -1, 3}}) {
			Fixture fixture = fixture(true, columns);
			MemoNativeRules oracle = new MemoNativeRules();
			MemoRuleBuilder.Result result = MemoRuleBuilder.build(fixture.facts(), fixture.sources(), oracle);
			int owner = fixture.facts().compiledOwnerId(fixture.kernel());
			Assert.assertTrue("the fixture must exercise full-key Oracle cache reuse", oracle.cacheStats().hits() > 0);
			Assert.assertFalse("repeated known contexts cannot clear UNKNOWN", result.memo().complete(owner));
			Assert.assertTrue(result.memo().rules(owner).stream().anyMatch(rule -> rule.exec() == ExecType.CP));
			for(int index = 0; index < columns.length; index++) {
				if(columns[index] >= 0) continue;
				int unknownOccurrence = fixture.facts().occurrenceIds(fixture.kernel()).get(index);
				Assert.assertTrue("each unresolved occurrence must retain its diagnostic: " + result.diagnostics(),
					result.diagnostics().stream().anyMatch(message -> message.startsWith(
						"owner=" + owner + " occurrence=" + unknownOccurrence + " ")));
			}
		}
	}

	@Test
	public void callerActivationCorrelatesFormalGuardWithActualShape() throws Exception {
		DMLProgram program = parse("f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
			+ "if(q){Y=t(X);}else{Y=X;}}"
			+ "p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "if(p){A=matrix(1,rows=8,cols=1);}else{A=matrix(1,rows=1,cols=1);}"
			+ "B=f(p,A);print(sum(B));");
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts, sources(facts), new MemoNativeRules());
		MemoProgramFacts.Occurrence transpose = facts.occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		int owner = transpose.compiledOwnerId();
		Assert.assertTrue(result.diagnostics().toString(), result.memo().complete(owner));
		Assert.assertTrue("the only active shape is 8x1, whose native transpose maps ROW to COL: "
			+ result.memo().rules(owner),
			result.memo().rules(owner).stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.COL));
	}

	@Test
	public void terminatedReturnArmDoesNotConstrainContinuationOwnerRules() throws Exception {
		DMLProgram program = parse("f=function(boolean q) return(matrix[double] Y){"
			+ "if(q){Y=matrix(1,rows=8,cols=1);}}"
			+ "p=as.scalar(rand(rows=1,cols=1))>0.5;A=f(p);B=t(A);print(sum(B));");
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts, sources(facts), new MemoNativeRules());
		MemoProgramFacts.Occurrence transpose = facts.occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		int owner = transpose.compiledOwnerId();
		Assert.assertTrue(result.diagnostics().toString(), result.memo().complete(owner));
		Assert.assertTrue("the missing-return arm terminates before the transpose",
			result.memo().rules(owner).stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.COL));
	}

	@Test
	public void phaseProvenInactiveBranchDoesNotConstrainOwnerRules() throws Exception {
		DMLProgram program = parse("A=matrix(1,rows=8,cols=1);i=1;"
			+ "while(i<=3){if(i==1){B=t(A);}A=matrix(1,rows=1,cols=1);i=i+1;}print(sum(B));");
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts, sources(facts), new MemoNativeRules());
		MemoProgramFacts.Occurrence transpose = facts.occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		MemoProgramFacts.OccurrenceContext occurrenceContext=facts.occurrenceContexts(transpose.id()).get(0);
		MemoProgramFacts.PhaseLoop phaseLoop=occurrenceContext.phaseLoops().get(0);
		MemoBindingResolver.LoopDecision loop=new MemoBindingResolver.LoopDecision(
			phaseLoop.loopId(),phaseLoop.callFrame());
		MemoBindingResolver.LoopCardinalityDecision cardinality=
			new MemoBindingResolver.LoopCardinalityDecision(phaseLoop.loopId(),phaseLoop.callFrame());
		List<MemoBindingResolver.EvaluationContext> visited = new ArrayList<>();
		MemoBindingResolver.EvaluationContext base = new MemoBindingResolver.EvaluationContext(
			java.util.Map.of(),java.util.Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			occurrenceContext.callFrame(),java.util.Map.of(loop,MemoProgramFacts.IterationPhase.MIDDLE),
			java.util.Map.of(cardinality,MemoProgramFacts.LoopCardinality.MANY));
		MemoBindingResolver.VisitResult activation = new MemoReachableContexts(facts,
			new MemoBindingResolver(facts)).visit(transpose,base,context -> {
				visited.add(context);
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,activation.status());
		Assert.assertEquals("phase contradiction must be rejected before the Oracle callback",
			List.of(),visited);
		int owner = transpose.compiledOwnerId();
		Assert.assertTrue(result.diagnostics().toString(), result.memo().complete(owner));
		Assert.assertTrue("only the FIRST phase reaches the guarded transpose",
			result.memo().rules(owner).stream().anyMatch(rule -> rule.exec() == ExecType.FED
				&& rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.COL));
	}

	private record Fixture(MemoProgramFacts facts, MemoSources sources, Hop kernel) { }

	private static Fixture fixture(int... columns) {
		return fixture(false, columns);
	}

	private static Fixture fixture(boolean append, int... columns) {
		DataOp left = read("A");
		DataOp right = read("B");
		Hop kernel = append
			? new BinaryOp("shared_append", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND, left, right)
			: new AggBinaryOp("shared_mm", DataType.MATRIX, ValueType.FP64, OpOp2.MULT, AggOp.SUM, left, right);
		List<StatementBlock> blocks = new ArrayList<>();
		for(int index = 0; index < columns.length; index++) {
			Hop a = matrix("left" + index, 8, 4);
			Hop b = matrix("right" + index, append ? 8 : 4, columns[index]);
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(write("A", a), write("B", b), write("out" + index, kernel))));
			blocks.add(block);
		}
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(blocks));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
			InetSocketAddress.createUnresolved("127.0.0.1", 19001),
			InetSocketAddress.createUnresolved("127.0.0.1", 19002)));
		return new Fixture(facts, MemoSources.acquire(facts, pool), kernel);
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static MemoSources sources(MemoProgramFacts facts) {
		return MemoSources.acquire(facts, CanonicalWorkerPool.of(List.of(
			InetSocketAddress.createUnresolved("127.0.0.1", 19001),
			InetSocketAddress.createUnresolved("127.0.0.1", 19002))));
	}

	private static DataOp read(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, -1, -1, -1, 1000);
	}

	private static DataOp write(String name, Hop value) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, value, OpOpData.TRANSIENTWRITE, name);
	}

	private static Hop matrix(String name, int rows, int columns) {
		DataOp dimensions = new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "nonexistent-owner-intersection-" + name, rows, columns,
			columns > 0 ? (long) rows * columns : -1, 1000);
		return columns > 0 ? HopRewriteUtils.createDataGenOp(dimensions, -1) : dimensions;
	}
}

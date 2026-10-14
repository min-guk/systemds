/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Status;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class MemoProgramFactsTest {
	@Test
	public void recursiveFunctionRetainsTypedUnsupportedDiagnosticWithoutCrashing() throws Exception {
		MemoProgramFacts facts = analyze("f=function(double x) return(double y){"
			+ "if(x<=0){y=1;}else{y=f(x-1);}}z=f(2);print(z);");

		Assert.assertEquals(Status.UNSUPPORTED, facts.status());
		Assert.assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
			"RECURSIVE_FUNCTION".equals(diagnostic.code())));
	}

	@Test
	public void correlatedBranchInputsShareOneGuardWithoutTupleExpansion() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));");
		facts.requireComplete();
		int consumer = binaryTransientConsumer(facts, "A", "B");
		MemoProgramFacts.BindingNode left = facts.binding(facts.inputBinding(consumer, 0));
		MemoProgramFacts.BindingNode right = facts.binding(facts.inputBinding(consumer, 1));

		Assert.assertEquals(BindingKind.SELECT, left.kind());
		Assert.assertEquals(BindingKind.SELECT, right.kind());
		Assert.assertEquals("both definitions must use the same runtime guard", left.referenceId(), right.referenceId());
		Assert.assertEquals(2, left.children().size());
		Assert.assertEquals(2, right.children().size());
		Assert.assertEquals("symbolic facts must not contain branch source tuples", 0,
			countClass(facts, "Tuple"));
	}

	@Test
	public void independentBranchesKeepDistinctGuardIdentities() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}"
			+ "if(q>0.5){B=matrix(3,2,2);}else{B=matrix(4,2,2);}C=A+B;print(sum(C));");
		int consumer = binaryTransientConsumer(facts, "A", "B");
		MemoProgramFacts.BindingNode left = facts.binding(facts.inputBinding(consumer, 0));
		MemoProgramFacts.BindingNode right = facts.binding(facts.inputBinding(consumer, 1));

		Assert.assertEquals(BindingKind.SELECT, left.kind());
		Assert.assertEquals(BindingKind.SELECT, right.kind());
		Assert.assertTrue(left.referenceId() != right.referenceId());
	}

	@Test
	public void sequentialReadsRetainExactDefinitionVersions() {
		DMLProgram program = new DMLProgram();
		DataOp initialA = transientWrite("A", new LiteralOp(1L));
		DataOp firstRead = transientRead("A");
		DataOp writeU = transientWrite("U", firstRead);
		DataOp laterA = transientWrite("A", new LiteralOp(2L));
		DataOp secondRead = transientRead("A");
		DataOp writeV = transientWrite("V", secondRead);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(initialA, writeU, laterA, writeV)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		List<MemoProgramFacts.Occurrence> reads = facts.occurrences().stream()
			.filter(o -> isTransientRead(o.hop()) && "A".equals(o.hop().getName())).toList();
		Assert.assertEquals(2, reads.size());
		int first = reachingBinding(facts, reads.get(0).id());
		int second = reachingBinding(facts, reads.get(1).id());
		Assert.assertTrue("the later assignment must not rebind the earlier read", first != second);
		Assert.assertEquals(2, facts.definitions().stream().filter(d -> "A".equals(d.variable())).count());
	}

	@Test
	public void reusedHopHasDistinctOccurrencesButOneCompiledOwner() {
		DMLProgram program = new DMLProgram();
		DataOp sharedRead = transientRead("A");
		DataOp initialA = transientWrite("A", new LiteralOp(1L));
		DataOp writeU = transientWrite("U", sharedRead);
		StatementBlock firstBlock = new StatementBlock();
		firstBlock.setHops(new ArrayList<>(List.of(initialA, writeU)));
		DataOp laterA = transientWrite("A", new LiteralOp(2L));
		DataOp writeV = transientWrite("V", sharedRead);
		StatementBlock secondBlock = new StatementBlock();
		secondBlock.setHops(new ArrayList<>(List.of(laterA, writeV)));
		program.setStatementBlocks(new ArrayList<>(List.of(firstBlock, secondBlock)));

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		List<Integer> occurrences = facts.occurrenceIds(sharedRead);
		Assert.assertEquals(2, occurrences.size());
		Assert.assertTrue(facts.occurrence(occurrences.get(0)).statementBlockId()
			!= facts.occurrence(occurrences.get(1)).statementBlockId());
		Assert.assertEquals(facts.occurrence(occurrences.get(0)).compiledOwnerId(),
			facts.occurrence(occurrences.get(1)).compiledOwnerId());
		Assert.assertTrue(facts.valueBinding(occurrences.get(0))
			!= facts.valueBinding(occurrences.get(1)));
		try {
			facts.occurrenceId(sharedRead);
			Assert.fail("singular occurrence lookup must reject an ambiguous shared Hop");
		}
		catch(IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void loopUsesFiniteEntryBackedgeAndLastBindings() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);i=1;while(i<3){A=A+1;i=i+1;}Z=sum(A);print(Z);");
		facts.requireComplete();
		MemoProgramFacts.CarriedValue carried = facts.loops().get(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();

		Assert.assertEquals(BindingKind.LOOP_HEAD, facts.binding(carried.headBindingId()).kind());
		Assert.assertEquals(List.of(carried.entryBindingId(), carried.backedgeBindingId()),
			facts.binding(carried.headBindingId()).children());
		Assert.assertEquals(BindingKind.LAST, facts.binding(carried.lastBindingId()).kind());
		Assert.assertNotNull(facts.statementBlock(facts.loops().get(0).statementBlockId()));
		Assert.assertTrue("loop predicate scalar uses are semantic uses",
			facts.semanticUses().stream().anyMatch(use -> use.kind() == MemoProgramFacts.UseKind.CONTROL));
	}

	@Test
	public void bodyOnlyLoopDefinitionNeedsNoInventedEntryButKeepsLastValue() throws Exception {
		MemoProgramFacts facts = analyze("for(i in 1:3){S=matrix(i,2,2);}Z=sum(S);print(Z);");
		facts.requireComplete();
		Assert.assertFalse(facts.loops().get(0).carriedValues().stream()
			.anyMatch(value -> "S".equals(value.variable())));
		MemoProgramFacts.Occurrence exitRead = facts.occurrences().stream()
			.filter(occurrence -> isTransientRead(occurrence.hop()) && "S".equals(occurrence.hop().getName()))
			.filter(occurrence -> !occurrence.context().contains("loop-")).findFirst().orElseThrow();
		Assert.assertEquals(BindingKind.LAST, facts.binding(facts.valueBinding(exitRead.id())).kind());
		Assert.assertFalse(facts.diagnostics().stream()
			.anyMatch(diagnostic -> "MISSING_LOOP_ENTRY".equals(diagnostic.code())));
	}

	@Test
	public void functionReturnAssignmentIsALoopCarriedDefinition() throws Exception {
		MemoProgramFacts facts = analyze(
			"f=function(boolean flag,matrix[double] X) return(matrix[double] Y){"
				+ "if(flag){Y=abs(X);}else{Y=abs(X);}}"
				+ "A=matrix(1,rows=2,cols=2);for(i in 1:3){A=f(TRUE,A);}print(sum(A));");
		facts.requireComplete();
		MemoProgramFacts.CarriedValue carried = facts.loops().get(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		Assert.assertEquals(BindingKind.LOOP_HEAD,facts.binding(carried.headBindingId()).kind());
		Assert.assertEquals(BindingKind.CALL_RETURN,facts.binding(carried.backedgeBindingId()).kind());
		Assert.assertEquals(BindingKind.LAST,facts.binding(carried.lastBindingId()).kind());
	}

	@Test
	public void staticForIteratorProducesExactFirstMiddleLastGuardFacts() throws Exception {
		MemoProgramFacts facts = analyze("X=0;for(i in 1:3){if(i==3){X=3;}else{X=1;}}print(X);");
		facts.requireComplete();
		MemoProgramFacts.GuardPhase phase = facts.guardPhases(facts.loops().get(0).id()).stream()
			.filter(value -> Boolean.FALSE.equals(value.first()) && Boolean.FALSE.equals(value.middle())
				&& Boolean.TRUE.equals(value.last())).findFirst().orElseThrow();
		Assert.assertTrue(phase.proof(),phase.proof().contains("static-for-affine-iterator"));
		Assert.assertEquals(Boolean.TRUE,facts.guardValue(phase.loopId(),phase.guardId(),
			MemoProgramFacts.IterationPhase.LAST));
	}

	@Test
	public void staticForMiddleGuardRequiresWholePhaseProof() throws Exception {
		MemoProgramFacts facts = analyze(
			"X=0;for(i in 1:5){if(i<3){X=3;}else{X=4;}}print(X);");
		facts.requireComplete();
		MemoProgramFacts.GuardPhase phase = facts.guardPhases(facts.loops().get(0).id()).stream()
			.filter(value -> Boolean.TRUE.equals(value.first()) && value.middle() == null
				&& Boolean.FALSE.equals(value.last())).findFirst().orElseThrow();
		Assert.assertNull(facts.guardValue(phase.loopId(),phase.guardId(),
			MemoProgramFacts.IterationPhase.MIDDLE));
		MemoProgramFacts.Occurrence thenOccurrence = facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/then")).findFirst().orElseThrow();
		MemoProgramFacts.Occurrence elseOccurrence = facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/else")).findFirst().orElseThrow();
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.FIRST,
			MemoProgramFacts.IterationPhase.MIDDLE), facts.occurrenceContexts(thenOccurrence.id()).get(0)
				.phaseLoops().get(0).reachablePhases());
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.MIDDLE,
			MemoProgramFacts.IterationPhase.LAST), facts.occurrenceContexts(elseOccurrence.id()).get(0)
				.phaseLoops().get(0).reachablePhases());
	}

	@Test
	public void positiveAffineCarriedCounterProvesFirstVersusLaterGuard() throws Exception {
		MemoProgramFacts facts = analyze("i=0;X=0;while(i<3){if(i>0){X=2;}else{X=1;}i=i+1;}print(X);");
		facts.requireComplete();
		MemoProgramFacts.GuardPhase phase = facts.guardPhases(facts.loops().get(0).id()).stream()
			.filter(value -> Boolean.FALSE.equals(value.first()) && Boolean.TRUE.equals(value.middle())
				&& Boolean.TRUE.equals(value.last())).findFirst().orElseThrow();
		Assert.assertTrue(phase.proof(),phase.proof().contains("carried-positive-affine-counter"));
	}

	@Test
	public void phaseProvenOuterGuardPropagatesUnconditionalScalarAssignment() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,rows=4,cols=1);W=matrix(0,rows=1,cols=1);i=0;"
			+ "while(i<3){accept=1;if(i>0){accept=0;}if(accept==1){W=A;}i=i+1;}print(sum(W));");
		facts.requireComplete();
		MemoProgramFacts.GuardPhase accept=facts.guardPhases(facts.loop(0).id()).stream()
			.filter(value -> Boolean.TRUE.equals(value.first()) && Boolean.FALSE.equals(value.middle())
				&& Boolean.FALSE.equals(value.last()))
			.filter(value -> value.proof().contains("symbolic-scalar-binding"))
			.findFirst().orElseThrow();
		Assert.assertEquals(Boolean.TRUE,facts.guardValue(accept.loopId(),accept.guardId(),
			MemoProgramFacts.IterationPhase.FIRST));
	}

	@Test
	public void occurrenceContextsKeepCallerAndCalleeLoopFramesFactorized() throws Exception {
		MemoProgramFacts facts=analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=X;"
				+ "for(j in 1:3){if(j==3){Y=abs(Y);}else{Y=abs(Y);}}}"
				+ "A=matrix(1,rows=2,cols=2);for(i in 1:3){A=f(A);}print(sum(A));");
		facts.requireComplete();
		MemoProgramFacts.Occurrence bodyAbs=facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("function") && occurrence.hop() instanceof UnaryOp)
			.findFirst().orElseThrow();
		List<MemoProgramFacts.OccurrenceContext> contexts=facts.occurrenceContexts(bodyAbs.id());
		Assert.assertEquals(1,contexts.size());
		Assert.assertEquals(2,contexts.get(0).phaseLoops().size());
		Assert.assertTrue(contexts.get(0).phaseLoops().stream().anyMatch(loop -> loop.callFrame().isEmpty()));
		Assert.assertTrue(contexts.get(0).phaseLoops().stream().anyMatch(loop -> loop.callFrame().size()==1));
		Assert.assertTrue("unguarded caller loop remains a phase boundary",
			contexts.get(0).phaseLoops().stream().filter(loop -> loop.callFrame().isEmpty())
				.anyMatch(loop -> loop.reachablePhases().size()==3));

		MemoProgramFacts.Occurrence thenAbs=facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/then") && occurrence.hop() instanceof UnaryOp)
			.findFirst().orElseThrow();
		MemoProgramFacts.PhaseLoop inner=facts.occurrenceContexts(thenAbs.id()).get(0).phaseLoops().stream()
			.filter(loop -> loop.callFrame().size()==1).findFirst().orElseThrow();
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.LAST),inner.reachablePhases());
	}

	@Test
	public void outerIteratorPhaseRestrictsNestedLoopBranch() throws Exception {
		MemoProgramFacts facts=analyze(
			"A=matrix(1,rows=2,cols=2);for(i in 1:3){for(j in 1:3){"
				+ "if(i==3){A=abs(A);}else{A=A+1;}}}print(sum(A));");
		facts.requireComplete();
		MemoProgramFacts.Occurrence thenAbs=facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/then") && occurrence.hop() instanceof UnaryOp)
			.findFirst().orElseThrow();
		List<MemoProgramFacts.PhaseLoop> loops=facts.occurrenceContexts(thenAbs.id()).get(0).phaseLoops();
		MemoProgramFacts.PhaseLoop outer=loops.stream().filter(loop -> loop.loopId()==facts.loops().get(0).id())
			.findFirst().orElseThrow();
		MemoProgramFacts.PhaseLoop inner=loops.stream().filter(loop -> loop.loopId()==facts.loops().get(1).id())
			.findFirst().orElseThrow();
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.LAST),outer.reachablePhases());
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.FIRST,
			MemoProgramFacts.IterationPhase.MIDDLE,MemoProgramFacts.IterationPhase.LAST),inner.reachablePhases());
	}

	@Test
	public void exactTwoIterationLoopHasNoMiddleOccurrencePhase() throws Exception {
		MemoProgramFacts facts=analyze(
			"A=matrix(1,rows=2,cols=2);for(i in 1:2){A=abs(A);}print(sum(A));");
		MemoProgramFacts.Occurrence body=facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp
				&& occurrence.context().contains("/loop-"))
			.findFirst().orElseThrow();
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.FIRST,
			MemoProgramFacts.IterationPhase.LAST),
			facts.occurrenceContexts(body.id()).get(0).phaseLoops().get(0).reachablePhases());
	}

	@Test
	public void conditionalFirstIterationDefinitionRetainsGroundedEntry() throws Exception {
		MemoProgramFacts facts = analyze("X=matrix(0,2,2);for(i in 1:3){if(i>1){X=matrix(i,2,2);}}"
			+ "Z=sum(X);print(Z);");
		facts.requireComplete();
		Assert.assertTrue(facts.loops().get(0).carriedValues().stream()
			.anyMatch(value -> "X".equals(value.variable())));
	}

	@Test
	public void genuinelyCarriedValueWithoutOuterDefinitionIsExplicitlyIncomplete() throws Exception {
		DMLProgram program = compiled("A=matrix(0,2,2);for(i in 1:3){A=A+1;}Z=sum(A);print(Z);");
		program.setStatementBlocks(new ArrayList<>(program.getStatementBlocks().subList(1,
			program.getStatementBlocks().size())));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		Assert.assertEquals(Status.INCOMPLETE, facts.status());
		Assert.assertTrue(facts.diagnostics().stream()
			.anyMatch(diagnostic -> "MISSING_REACHING_DEFINITION".equals(diagnostic.code())
				&& "A".equals(diagnostic.message())));
	}

	@Test
	public void forIteratorIsAnExplicitCompilerDefinition() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);for(i in 1:2){A=A+i;}Z=sum(A);print(Z+i);");
		facts.requireComplete();
		Assert.assertTrue(facts.definitions().stream()
			.anyMatch(definition -> "i".equals(definition.variable()) && definition.occurrenceId() == -1));
		Assert.assertTrue(facts.bindings().stream()
			.anyMatch(binding -> "i#for-iterator".equals(binding.label())));
		Assert.assertEquals(2D, facts.executionFrequencies().stream()
			.filter(frequency -> frequency.referenceId() == facts.loops().get(0).id())
			.filter(frequency -> frequency.kind() == MemoProgramFacts.FrequencyKind.LOOP_ITERATIONS)
			.findFirst().orElseThrow().multiplier(), 0D);
		MemoProgramFacts.Occurrence exitRead = facts.occurrences().stream()
			.filter(occurrence -> isTransientRead(occurrence.hop()) && "i".equals(occurrence.hop().getName()))
			.filter(occurrence -> !occurrence.context().contains("loop-")).findFirst().orElseThrow();
		Assert.assertEquals(BindingKind.LAST, facts.binding(facts.valueBinding(exitRead.id())).kind());
	}

	@Test
	public void activeLiteralOneIterationLoopHasOneExactExecutionCase() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);for(i in 1:1){A=A+1;}print(sum(A));");
		facts.requireComplete();
		Assert.assertEquals(List.of(new MemoProgramFacts.LoopExecutionCase(
			MemoProgramFacts.LoopCardinality.ONE,1D,List.of(MemoProgramFacts.IterationPhase.FIRST))),
			facts.loopExecutionCases(0));
		MemoProgramFacts.Occurrence body=facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/body")).findFirst().orElseThrow();
		MemoProgramFacts.PhaseLoop phase=facts.occurrenceContexts(body.id()).get(0).phaseLoops().get(0);
		Assert.assertEquals(List.of(MemoProgramFacts.IterationPhase.FIRST),phase.reachablePhases());
		Assert.assertEquals(facts.loopExecutionCases(0),phase.executionCases());
	}

	@Test
	public void literalZeroIterationLoopKeepsEntryAsExitAndHasNoBodyContext() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(0,2,2);for(i in seq(1,0,1)){A=A+1;}print(sum(A));");
		facts.requireComplete();
		Assert.assertEquals(List.of(new MemoProgramFacts.LoopExecutionCase(
			MemoProgramFacts.LoopCardinality.ZERO,0D,List.of())),facts.loopExecutionCases(0));
		MemoProgramFacts.CarriedValue carried=facts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		Assert.assertEquals(List.of(carried.entryBindingId(),carried.backedgeBindingId()),
			facts.binding(carried.lastBindingId()).children());
		MemoProgramFacts.Occurrence body=facts.occurrences().stream()
			.filter(occurrence -> occurrence.context().contains("/body") && "A".equals(occurrence.hop().getName()))
			.findFirst().orElseThrow();
		Assert.assertTrue(facts.occurrenceContexts(body.id()).isEmpty());
	}

	@Test
	public void parforIsRejectedByTheSequentialLoopContract() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,3,1);parfor(i in 1:3){A[i,1]=i;}print(sum(A));");
		Assert.assertEquals(Status.UNSUPPORTED,facts.status());
		Assert.assertTrue(facts.diagnostics().stream()
			.anyMatch(diagnostic -> "UNSUPPORTED_PARFOR".equals(diagnostic.code())));
		Assert.assertTrue("a PARFOR must not be published as a sequential LoopRecord",facts.loops().isEmpty());
	}

	@Test
	public void unusedFunctionLoopDoesNotViolateActiveLoopScope() throws Exception {
		MemoProgramFacts facts = analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){"
				+ "Y=X;for(i in 1:1){Y=Y+1;}}print(1);");
		Assert.assertEquals(Status.COMPLETE, facts.status());
		Assert.assertFalse(facts.diagnostics().stream()
			.anyMatch(diagnostic -> "LOOP_REQUIRES_MULTIPLE_ITERATIONS".equals(diagnostic.code())));
	}

	@Test
	public void invokedFunctionLiteralOneIterationLoopIsSupportedExactly() throws Exception {
		MemoProgramFacts facts = analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){"
				+ "Y=X;for(i in 1:1){Y=Y+1;}}A=f(matrix(1,2,2));print(sum(A));");
		facts.requireComplete();
		Assert.assertEquals(MemoProgramFacts.LoopCardinality.ONE,
			facts.loopExecutionCases(0).get(0).cardinality());
	}

	@Test
	public void nonRecursiveCallsShareCompiledSummaryButKeepCallBindings() throws Exception {
		MemoProgramFacts facts = analyze("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);C=A+B;print(sum(C));");
		facts.requireComplete();
		Assert.assertEquals(1, facts.functions().size());
		Assert.assertEquals(facts.occurrences().toString(), 2, facts.calls().size());
		Assert.assertEquals(facts.calls().get(0).functionSummaryId(), facts.calls().get(1).functionSummaryId());
		Assert.assertFalse(facts.calls().get(0).argumentBindingIds().equals(facts.calls().get(1).argumentBindingIds()));
		Assert.assertFalse(facts.calls().get(0).returnBindingIds().equals(facts.calls().get(1).returnBindingIds()));
		Assert.assertFalse(facts.functions().get(0).compiledOwnerIds().isEmpty());
		long functionStatementBlock = facts.functionStatementBlockId(facts.functions().get(0).id());
		Assert.assertTrue(functionStatementBlock >= 0);
		Assert.assertNotNull(facts.statementBlock(functionStatementBlock));
		Assert.assertSame(facts.statementBlock(functionStatementBlock),
			facts.statementBlock(facts.functions().get(0).statementBlockId()));
		Assert.assertEquals(facts.occurrences().size(), facts.shapeFacts().size());
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			Assert.assertSame(occurrence.hop(), facts.compiledOwnerHop(occurrence.compiledOwnerId()));
	}

	@Test
	public void missingReachingDefinitionIsExplicitlyIncomplete() {
		DMLProgram program = new DMLProgram();
		DataOp read = new DataOp("A", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "A", 2, 2, -1, -1);
		DataOp write = new DataOp("B", DataType.MATRIX, ValueType.FP64, read,
			OpOpData.TRANSIENTWRITE, "B");
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(write)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		Assert.assertEquals(Status.INCOMPLETE, facts.status());
		Assert.assertTrue(facts.diagnostics().stream()
			.anyMatch(d -> "MISSING_REACHING_DEFINITION".equals(d.code())));
		try {
			facts.requireComplete();
			Assert.fail("incomplete facts must not pass the completeness gate");
		}
		catch(IllegalStateException expected) {
			// expected
		}
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		return MemoProgramFacts.analyze(compiled(script));
	}

	private static DMLProgram compiled(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static int binaryTransientConsumer(MemoProgramFacts facts, String left, String right) {
		return facts.occurrences().stream().filter(o -> o.hop().getInput().size() == 2
			&& isTransientRead(o.hop().getInput(0)) && left.equals(o.hop().getInput(0).getName())
			&& isTransientRead(o.hop().getInput(1)) && right.equals(o.hop().getInput(1).getName()))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
	}

	private static int reachingBinding(MemoProgramFacts facts, int readOccurrence) {
		int binding = facts.valueBinding(readOccurrence);
		if(binding < 0)
			throw new AssertionError("No reaching binding for " + facts.occurrence(readOccurrence).hop());
		return binding;
	}

	private static boolean isTransientRead(Hop hop) {
		return hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD;
	}

	private static DataOp transientRead(String name) {
		return new DataOp(name, DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, name, 0, 0, -1, -1);
	}

	private static DataOp transientWrite(String name, Hop input) {
		return new DataOp(name, input.getDataType(), input.getValueType(), input,
			OpOpData.TRANSIENTWRITE, name);
	}

	private static int countClass(MemoProgramFacts facts, String simpleName) {
		int count = 0;
		for(Class<?> nested : facts.getClass().getDeclaredClasses())
			if(nested.getSimpleName().contains(simpleName)) count++;
		return count;
	}
}

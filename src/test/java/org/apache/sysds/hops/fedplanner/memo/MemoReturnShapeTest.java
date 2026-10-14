/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Semantic regressions for call-return shape recovery at terminating function boundaries. */
public class MemoReturnShapeTest {
	@Test
	public void normalReturnShapeSurvivesTerminatingAlternativeAndEnablesNativeTranspose() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
				+ "if(q){Y=abs(X);}else{stop('invalid input');}}"
				+ "X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "A=f(q,X);B=t(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence transpose = transposes(facts).get(0);

		assertShape(shapes.inputShape(transpose.id(), 0, EvaluationContext.empty()), 8, 4);
		assertShape(shapes.shapeAt(transpose.id(), EvaluationContext.empty()), 4, 8);
		MemoNativeRules.Result nativeRules = new MemoNativeRules().rules(
			shapes.viewAt(transpose.id(), EvaluationContext.empty()), 2,
			shapes.hintAt(transpose.id(), EvaluationContext.empty()));
		Assert.assertTrue(nativeRules.diagnostics().toString(), nativeRules.complete());
		Assert.assertTrue("the recovered shape must retain the existing ROW-to-COL native transpose",
			nativeRules.rules().stream().anyMatch(rule ->
				rule.exec() == ExecType.FED
					&& rule.inputs().equals(List.of(MemoPartition.ROW))
					&& rule.output() == MemoPartition.COL));
	}

	@Test
	public void explicitlySelectedTerminatingReturnDoesNotInventAShape() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
				+ "if(q){Y=abs(X);}else{stop('invalid input');}}"
				+ "X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "A=f(q,X);B=t(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence transpose = transposes(facts).get(0);
		ResolvedEnvironment terminal = completionEnvironments(facts).stream().filter(environment ->
			environment.origins().get(0).kind() == OriginKind.TERMINATED_AT_RETURN)
			.findFirst().orElseThrow();
		EvaluationContext context = context(terminal);

		Assert.assertFalse("a caller continuation that terminates at the call has no material input shape",
			shapes.inputShape(transpose.id(), 0, context).exact());
		Assert.assertFalse("a terminal call path must not manufacture a transpose output shape",
			shapes.shapeAt(transpose.id(), context).exact());
	}

	@Test
	public void unresolvedDepthDoesNotBorrowANormalReturnShape() throws Exception {
		MemoProgramFacts facts = facts(
			"g=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
				+ "f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
				+ "if(q){Y=g(X);}else{stop('invalid input');}}"
				+ "X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "A=f(q,X);B=t(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence transpose = transposes(facts).get(0);
		EvaluationContext shallow = new EvaluationContext(Map.of(), Map.of(),
			MemoBindingResolver.NO_SELECTION, 1);

		Assert.assertFalse("an unresolved bounded visit may not reuse an exact normal-path result",
			shapes.inputShape(transpose.id(), 0, shallow).exact());
		Assert.assertFalse(shapes.shapeAt(transpose.id(), shallow).exact());
	}

	@Test
	public void differentNormalReturnShapesRemainUnknown() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean q) return(matrix[double] Y){"
				+ "if(q){Y=matrix(1,rows=8,cols=4);}else{Y=matrix(1,rows=7,cols=3);}}"
				+ "q=as.scalar(rand(rows=1,cols=1))>0.5;A=f(q);B=t(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence transpose = transposes(facts).get(0);

		Assert.assertFalse("normal alternatives with different shapes may not select either arm",
			shapes.inputShape(transpose.id(), 0, EvaluationContext.empty()).exact());
		Assert.assertFalse(shapes.shapeAt(transpose.id(), EvaluationContext.empty()).exact());
	}

	@Test
	public void sharedFunctionKeepsCallsiteSpecificReturnShapes() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
				+ "P=matrix(1,rows=8,cols=4);Q=matrix(1,rows=7,cols=3);"
				+ "A=f(P);B=f(Q);C=t(A);D=t(B);print(sum(C)+sum(D));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		List<String> dimensions = new ArrayList<>();
		for(MemoProgramFacts.Occurrence transpose : transposes(facts)) {
			MemoShapes.Shape input = shapes.inputShape(transpose.id(), 0, EvaluationContext.empty());
			MemoShapes.Shape output = shapes.shapeAt(transpose.id(), EvaluationContext.empty());
			Assert.assertTrue(String.valueOf(input), input.exact());
			Assert.assertTrue(String.valueOf(output), output.exact());
			dimensions.add(input.rows() + "x" + input.columns() + "->"
				+ output.rows() + "x" + output.columns());
		}

		Assert.assertEquals(2, dimensions.size());
		Assert.assertTrue(dimensions.toString(), dimensions.contains("8x4->4x8"));
		Assert.assertTrue(dimensions.toString(), dimensions.contains("7x3->3x7"));
	}

	@Test
	public void missingSecondaryReturnStillTerminatesTheCall() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean q,matrix[double] X) return(matrix[double] Y,matrix[double] Z){"
				+ "Y=abs(X);if(q){Z=X;}}"
				+ "X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "[A,B]=f(q,X);C=t(A);print(sum(C));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoProgramFacts.CallBinding call = facts.calls().get(0);
		List<ResolvedEnvironment> environments = new ArrayList<>();
		resolver.visitJoint(List.of(call.completionBindingId()), EvaluationContext.empty(), environment -> {
			environments.add(environment);
			return true;
		});
		MemoProgramFacts.Occurrence transpose = transposes(facts).get(0);
		ResolvedEnvironment normal = environments.stream().filter(environment ->
			environment.origins().get(0).kind() == OriginKind.NORMAL_CONTINUATION)
			.findFirst().orElseThrow();
		ResolvedEnvironment terminal = environments.stream().filter(environment ->
			environment.origins().get(0).kind() == OriginKind.TERMINATED_AT_RETURN)
			.findFirst().orElseThrow();

		Assert.assertEquals("one assigned and one missing-secondary-return environment",
			2, environments.size());
		assertAncestorShapeQueryOrder(facts, transpose, normal, terminal);
		assertAncestorShapeQueryOrder(facts, transpose, terminal, normal);
	}

	@Test
	public void nestedMissingReturnsDoNotPromoteUnknownTypeToExact() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean p,boolean q) return(matrix[double] Y){"
				+ "if(p){if(q){Y=matrix(1,rows=8,cols=4);}}}"
				+ "p=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "q=as.scalar(rand(rows=1,cols=1))>0.5;A=f(p,q);print(sum(A));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		int returned = facts.functionSummary(0).returnBindingIds().get(0);

		MemoShapes.Shape shape = shapes.bindingShape(returned);
		Assert.assertFalse("UNKNOWN joined with UNKNOWN is not an exact scalar shape: " + shape,
			shape.exact());
	}

	@Test
	public void conditionalReturnKeepsLoopPhaseAndZeroOneExitShapesDistinct() throws Exception {
		MemoProgramFacts facts = facts(
			"f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
				+ "if(q){Y=X;p=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "while(p){Y=abs(Y);p=as.scalar(rand(rows=1,cols=1))>0.5;}}"
				+ "else{stop('invalid input');}}"
				+ "A=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "R=f(q,A);C=t(R);print(sum(C));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence body = facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.ABS)
			.findFirst().orElseThrow();
		MemoProgramFacts.Occurrence caller = transposes(facts).get(0);
		MemoProgramFacts.OccurrenceContext occurrenceContext = facts.occurrenceContexts(body.id()).stream()
			.filter(value -> !value.phaseLoops().isEmpty()).findFirst().orElseThrow();
		MemoProgramFacts.PhaseLoop phaseLoop = occurrenceContext.phaseLoops().get(0);
		LoopDecision loop = new LoopDecision(phaseLoop.loopId(), phaseLoop.callFrame());
		LoopCardinalityDecision cardinality =
			new LoopCardinalityDecision(phaseLoop.loopId(), phaseLoop.callFrame());

		for(IterationPhase phase : IterationPhase.values()) {
			EvaluationContext context = new EvaluationContext(Map.of(),
				Map.of(loop, phase == IterationPhase.FIRST ? LoopPhase.ENTRY : LoopPhase.BACKEDGE),
				MemoBindingResolver.NO_SELECTION, 4096, occurrenceContext.callFrame(),
				Map.of(loop, phase), Map.of(cardinality, LoopCardinality.MANY));
			assertShape(shapes.inputShape(body.id(), 0, context), 8, 4);
			assertShape(shapes.shapeAt(body.id(), context), 8, 4);
		}
		for(LoopCardinality count : LoopCardinality.values()) {
			EvaluationContext context = new EvaluationContext(Map.of(), Map.of(),
				MemoBindingResolver.NO_SELECTION, 4096, List.of(), Map.of(),
				Map.of(cardinality, count));
			assertShape(shapes.inputShape(caller.id(), 0, context), 8, 4);
			assertShape(shapes.shapeAt(caller.id(), context), 4, 8);
		}
	}

	@Test
	public void mutableSelectionLookupIsSnapshottedByLogicalShapeState() throws Exception {
		MemoProgramFacts facts = facts(
			"A=matrix(1,rows=8,cols=4);p=as.scalar(rand(rows=1,cols=1))>0.5;"
				+ "while(p){A=abs(A);p=as.scalar(rand(rows=1,cols=1))>0.5;}"
				+ "B=t(A);print(sum(B));");
		MemoProgramFacts.Occurrence body = facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.ABS)
			.findFirst().orElseThrow();
		MemoProgramFacts.CarriedValue carried = facts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();

		assertMutableSelectionQueryOrder(facts, body, carried, false);
		assertMutableSelectionQueryOrder(facts, body, carried, true);
	}

	private static List<MemoProgramFacts.Occurrence> transposes(MemoProgramFacts facts) {
		return facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg
				&& reorg.getOp() == ReOrgOp.TRANS)
			.filter(occurrence -> occurrence.context().startsWith("main"))
			.toList();
	}

	private static void assertAncestorShapeQueryOrder(MemoProgramFacts facts,
		MemoProgramFacts.Occurrence transpose, ResolvedEnvironment first, ResolvedEnvironment second) {
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		assertEnvironmentShape(shapes, transpose, first);
		assertEnvironmentShape(shapes, transpose, second);
	}

	private static void assertEnvironmentShape(MemoShapes shapes,
		MemoProgramFacts.Occurrence transpose, ResolvedEnvironment environment) {
		EvaluationContext context = context(environment);
		MemoShapes.Shape input = shapes.inputShape(transpose.id(), 0, context);
		MemoShapes.Shape output = shapes.shapeAt(transpose.id(), context);
		if(environment.origins().get(0).kind() == OriginKind.NORMAL_CONTINUATION) {
			assertShape(input, 8, 4);
			assertShape(output, 4, 8);
		}
		else {
			Assert.assertFalse("terminal input query reused a normal cached shape: " + input, input.exact());
			Assert.assertFalse("terminal ancestor query reused a normal cached shape: " + output, output.exact());
		}
	}

	private static List<ResolvedEnvironment> completionEnvironments(MemoProgramFacts facts) {
		List<ResolvedEnvironment> environments = new ArrayList<>();
		new MemoBindingResolver(facts).visitJoint(List.of(facts.calls().get(0).completionBindingId()),
			EvaluationContext.empty(), environment -> {
				environments.add(environment);
				return true;
			});
		return environments;
	}

	private static EvaluationContext context(ResolvedEnvironment environment) {
		return new EvaluationContext(environment.guards(), environment.loops(),
			MemoBindingResolver.NO_SELECTION, 4096, List.of(), environment.iterationPhases(),
			environment.cardinalities());
	}

	private static void assertMutableSelectionQueryOrder(MemoProgramFacts facts,
		MemoProgramFacts.Occurrence occurrence, MemoProgramFacts.CarriedValue carried,
		boolean initiallyBound) throws Exception {
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MutableSelection selections = new MutableSelection(0, carried.slot(), initiallyBound);
		LoopDecision loop = new LoopDecision(0, List.of());
		EvaluationContext context = new EvaluationContext(Map.of(), Map.of(loop, LoopPhase.BACKEDGE),
			selections, 4096, List.of(), Map.of(loop, IterationPhase.MIDDLE),
			Map.of(new LoopCardinalityDecision(0, List.of()), LoopCardinality.MANY));

		Object firstKey = shapeContextKey(shapes, context);
		int firstHash = firstKey.hashCode();
		MemoShapes.Shape first = shapes.shapeAt(occurrence.id(), context);
		selections.bound = !initiallyBound;
		Object secondKey = shapeContextKey(shapes, context);
		MemoShapes.Shape second = shapes.shapeAt(occurrence.id(), context);

		assertShape(first, 8, 4);
		assertShape(second, 8, 4);
		Assert.assertEquals("head binding changes availability, not this shape-preserving operator", first, second);
		Assert.assertNotEquals("headIsBound must be snapshotted instead of keyed by lookup identity",
			firstKey, secondKey);
		Assert.assertEquals("later lookup mutation may not change an already published key",
			firstHash, firstKey.hashCode());

		selections.rule = 7;
		selections.layout = MemoPartition.COL;
		Object layoutOnlyKey = shapeContextKey(shapes, context);
		MemoShapes.Shape layoutOnly = shapes.shapeAt(occurrence.id(), context);
		Assert.assertEquals("physical rule/layout choices do not alter logical shape", second, layoutOnly);
		Assert.assertEquals("physical-only mutation must retain the logical shape cache key",
			secondKey, layoutOnlyKey);

		selections.bound = initiallyBound;
		Assert.assertEquals("restoring logical selection state must restore the original key",
			firstKey, shapeContextKey(shapes, context));
	}

	private static Object shapeContextKey(MemoShapes shapes, EvaluationContext context) throws Exception {
		java.lang.reflect.Method method = MemoShapes.class.getDeclaredMethod(
			"shapeContextKey", EvaluationContext.class);
		method.setAccessible(true);
		return method.invoke(shapes, context);
	}

	private static final class MutableSelection implements MemoBindingResolver.SelectionLookup {
		private final int targetLoop;
		private final int targetSlot;
		private boolean bound;
		private int rule = -1;
		private MemoPartition layout;

		private MutableSelection(int targetLoop, int targetSlot, boolean bound) {
			this.targetLoop = targetLoop;
			this.targetSlot = targetSlot;
			this.bound = bound;
		}

		@Override public int selectedRule(int owner) { return rule; }
		@Override public MemoPartition selectedLayout(int owner) { return layout; }
		@Override public MemoPartition selectedHeadLayout(int loop, int slot) { return layout; }
		@Override public boolean headIsBound(int loop, int slot) {
			return bound && loop == targetLoop && slot == targetSlot;
		}
	}

	private static MemoProgramFacts facts(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}

	private static void assertShape(MemoShapes.Shape shape, long rows, long columns) {
		Assert.assertTrue(String.valueOf(shape), shape.exact());
		Assert.assertEquals(rows, shape.rows());
		Assert.assertEquals(columns, shape.columns());
	}
}

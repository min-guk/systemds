/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.CarriedValue;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoLoopPrivacyTest {
	private static final String MIXED_PRIVACY_LOOP =
		"A=matrix(1,2,2);B=matrix(2,2,2);U=matrix(3,2,2);X=matrix(4,2,2);i=1;"
		+ "while(i<3){B=abs(A)+B;U=abs(U);A=abs(X);i=i+1;}C=A+1;print(sum(C)+sum(B)+sum(U));";

	@Test
	public void loopSummaryKeepsIndependentInvariantsAndRefinesRejectedDependencies() throws Exception {
		MemoProgramFacts facts=analyze(MIXED_PRIVACY_LOOP);
		MemoPrivacy privacy=privacy(facts);
		Map<MemoPrivacy.LoopSlot,MemoPrivacy.Result> summary=privacy.summarizeLoop(0,MemoPrivacy.Context.empty());

		CarriedValue a=carried(facts,"A"), b=carried(facts,"B"), u=carried(facts,"U");
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,summary.get(slot(a)).status());
		Assert.assertEquals("B depends on the rejected A head and must be reevaluated without it",
			MemoPrivacy.Status.UNKNOWN,summary.get(slot(b)).status());
		Assert.assertEquals(MemoPrivacy.Status.EXACT,summary.get(slot(u)).status());
		Assert.assertEquals(Privacy.PUBLIC,summary.get(slot(u)).privacy());
		Assert.assertSame("the exact loop/context summary must be reused across consumer regions",summary,
			privacy.summarizeLoop(0,MemoPrivacy.Context.empty()));
	}

	@Test
	public void phaseContextsKeepOneIterationRuleWithoutWeakeningManyIterationPrivacy() throws Exception {
		MemoProgramFacts facts=analyze(MIXED_PRIVACY_LOOP);
		MemoPrivacy privacy=privacy(facts);
		CarriedValue a=carried(facts,"A");
		int bOccurrence=facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS && !unary.getInput().isEmpty() && "A".equals(unary.getInput().get(0).getName())
			&& value.context().contains("/loop-")).mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int bInput=facts.inputBinding(bOccurrence,0);
		RuleMemo.Rule local=new RuleMemo.Rule(ExecType.CP,List.of(MemoPartition.LOCAL),
			List.of(MemoPartition.LOCAL),"test/local");

		MemoPrivacy.Context first=context(LoopCardinality.ONE,IterationPhase.FIRST)
			.withLoopHead(0,a.slot(),Privacy.PUBLIC).withLoopHead(0,carried(facts,"B").slot(),Privacy.PUBLIC);
		MemoPrivacy.Context repeat=context(LoopCardinality.MANY,IterationPhase.MIDDLE)
			.withLoopHead(0,a.slot(),Privacy.PRIVATE).withLoopHead(0,carried(facts,"B").slot(),Privacy.PRIVATE);
		Assert.assertEquals(Privacy.PUBLIC,privacy.privacyAt(bInput,first).privacy());
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL,
			privacy.unconditionalRuleLegalityAt(bOccurrence,local,List.of(InputRole.PAYLOAD),first).legality());
		Assert.assertEquals(Privacy.PRIVATE,privacy.privacyAt(bInput,repeat).privacy());
		Assert.assertEquals(MemoPrivacy.Legality.ILLEGAL,
			privacy.unconditionalRuleLegalityAt(bOccurrence,local,List.of(InputRole.PAYLOAD),repeat).legality());
	}

	@Test
	public void zeroOneAndManyExitSelectEntryOrPrivateRepeatExactly() throws Exception {
		MemoProgramFacts facts=analyze(MIXED_PRIVACY_LOOP);
		MemoPrivacy privacy=privacy(facts);
		int consumer=facts.occurrences().stream().filter(value -> "C".equals(value.hop().getName()))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int binding=facts.inputBinding(consumer,0);
		Assert.assertEquals(Privacy.PUBLIC,privacy.privacyAt(binding,
			context(LoopCardinality.ZERO,null)).privacy());
		Assert.assertEquals(Privacy.PRIVATE,privacy.privacyAt(binding,
			context(LoopCardinality.ONE,IterationPhase.FIRST)).privacy());
		Assert.assertEquals(Privacy.PRIVATE,privacy.privacyAt(binding,
			context(LoopCardinality.MANY,IterationPhase.LAST)).privacy());
	}

	@Test
	public void outerSummaryLazilyGroundsNestedLoopHeadsInTheOuterContext() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,2,2);U=matrix(2,2,2);X=matrix(3,2,2);T=A;i=1;"
			+ "while(i<3){T=abs(A);j=1;while(j<3){U=abs(U);j=j+1;}A=abs(X);i=i+1;}"
			+ "print(sum(A)+sum(U)+sum(T));");
		MemoPrivacy privacy=privacy(facts);
		int outer=facts.loops().stream().filter(loop -> loop.carriedValues().stream()
			.anyMatch(value -> "A".equals(value.variable()))).mapToInt(MemoProgramFacts.LoopRecord::id)
			.findFirst().orElseThrow();
		CarriedValue a=carried(facts,outer,"A"),u=carried(facts,outer,"U");
		Map<MemoPrivacy.LoopSlot,MemoPrivacy.Result> summary=
			privacy.summarizeLoop(outer,MemoPrivacy.Context.empty());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,
			summary.get(new MemoPrivacy.LoopSlot(outer,a.slot())).status());
		Assert.assertEquals(summary.toString(),MemoPrivacy.Status.EXACT,
			summary.get(new MemoPrivacy.LoopSlot(outer,u.slot())).status());
		Assert.assertEquals(Privacy.PUBLIC,
			summary.get(new MemoPrivacy.LoopSlot(outer,u.slot())).privacy());
	}

	@Test
	public void outerHeadRejectionInvalidatesDependentNestedSummaries() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,2,2);B=matrix(2,2,2);X=matrix(3,2,2);"
			+"for(i in 1:2){C=matrix(0,2,2);for(j in 1:2){B=abs(B)+C;C=abs(A);}A=abs(X);}"
			+"print(sum(A)+sum(B));");
		int outer=facts.loops().stream().filter(loop -> loop.carriedValues().stream()
			.anyMatch(value -> value.variable().equals("A"))).findFirst().orElseThrow().id();
		var summary=privacy(facts).summarizeLoop(outer,MemoPrivacy.Context.empty());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,
			summary.get(new MemoPrivacy.LoopSlot(outer,carried(facts,outer,"A").slot())).status());
		Assert.assertEquals("B depends on A through inner summaries, not just direct outer heads",
			MemoPrivacy.Status.UNKNOWN,
			summary.get(new MemoPrivacy.LoopSlot(outer,carried(facts,outer,"B").slot())).status());
	}

	@Test
	public void functionLoopPrivacyIsQualifiedByCallFrameRegardlessOfInsertionOrder() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X) return(matrix[double] Y){A=X;"
			+"for(i in 1:2){A=abs(A);}Y=A;}P=matrix(1,2,2);Q=matrix(2,2,2);"
			+"R=f(P);S=f(Q);print(sum(R)+sum(S));");
		int p=owner(facts,"P"),q=owner(facts,"Q"),loop=facts.loops().get(0).id();
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(p,Privacy.PUBLIC,q,Privacy.PRIVATE));
		for(List<Integer> order : List.of(List.of(0,1),List.of(1,0))) {
			MemoPrivacy.Context combined=MemoPrivacy.Context.empty();
			for(int callIndex : order) {
				List<Integer> frame=List.of(facts.call(callIndex).id());
				MemoPrivacy.Context invoked=new MemoPrivacy.Context(
					MemoBindingResolver.EvaluationContext.empty().withCallFrame(frame),Map.of());
				for(var entry : privacy.summarizeLoop(loop,invoked).entrySet()) if(entry.getValue().exact())
					combined=combined.withLoopHeadAt(frame,entry.getKey().loopId(),entry.getKey().slot(),
						entry.getValue().privacy());
			}
			Assert.assertEquals(Privacy.PUBLIC,
				privacy.privacyAt(facts.call(0).returnBindingIds().get(0),combined).privacy());
			Assert.assertEquals(Privacy.PRIVATE,
				privacy.privacyAt(facts.call(1).returnBindingIds().get(0),combined).privacy());
		}
	}

	private static MemoPrivacy.Context context(LoopCardinality cardinality,IterationPhase phase) {
		var evaluation=MemoBindingResolver.EvaluationContext.empty().withLoopCardinality(0,cardinality);
		if(phase!=null) evaluation=evaluation.withIterationPhase(0,phase);
		return new MemoPrivacy.Context(evaluation,Map.of());
	}

	private static MemoPrivacy.LoopSlot slot(CarriedValue value) {
		return new MemoPrivacy.LoopSlot(0,value.slot());
	}

	private static CarriedValue carried(MemoProgramFacts facts,String variable) {
		return carried(facts,0,variable);
	}

	private static CarriedValue carried(MemoProgramFacts facts,int loop,String variable) {
		return facts.loop(loop).carriedValues().stream().filter(value -> variable.equals(value.variable()))
			.findFirst().orElseThrow();
	}

	private static MemoPrivacy privacy(MemoProgramFacts facts) {
		int x=facts.occurrences().stream().filter(value -> "X".equals(value.hop().getName())
			&& !value.context().contains("/loop-")).mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId)
			.findFirst().orElseThrow();
		return new MemoPrivacy(facts,new MemoBindingResolver(facts),Map.of(x,Privacy.PRIVATE));
	}

	private static int owner(MemoProgramFacts facts,String name) {
		return facts.occurrences().stream().filter(value -> name.equals(value.hop().getName())
			&& !value.context().contains("function") && !value.context().contains("loop-"))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

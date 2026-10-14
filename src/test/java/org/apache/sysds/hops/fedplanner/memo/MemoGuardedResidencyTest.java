/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
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
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Local residency is weaker than exact provenance, but never weaker than availability. */
public class MemoGuardedResidencyTest {

	@Test
	public void normalReturnUsesEveryReleasedOriginWithoutInventingAnExactLabel() throws Exception {
		MemoProgramFacts facts=branchReturn();
		MemoPrivacy privacy=privacy(facts,Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		int returned=facts.call(0).returnBindingIds().get(0);
		var normal=context(facts,0,true);
		Assert.assertFalse(privacy.provesReleasedBinding(returned));
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,privacy.privacyAt(returned,normal).status());
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL,privacy.localResidencyAt(returned,normal).legality());
		Assert.assertEquals("residency must not rewrite exact provenance",MemoPrivacy.Status.UNKNOWN,
			privacy.privacyAt(returned,normal).status());
		Assert.assertEquals("the consumer output must reuse the same proof",MemoPrivacy.Legality.LEGAL,
			localRule(privacy,facts,normal).legality());
	}

	@Test
	public void missingArmAndUnspecifiedCompletionCannotBecomeReleased() throws Exception {
		MemoProgramFacts facts=branchReturn();
		MemoPrivacy privacy=privacy(facts,Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		int returned=facts.call(0).returnBindingIds().get(0);
		for(var context : List.of(MemoPrivacy.Context.empty(),context(facts,0,false))) {
			Assert.assertEquals(MemoPrivacy.Legality.DEFER,privacy.localResidencyAt(returned,context).legality());
			Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,privacy.privacyAt(returned,context).status());
		}
	}

	@Test
	public void oneReleasedOriginDoesNotHideAProtectedNormalAlternative() throws Exception {
		MemoProgramFacts facts=branchReturn();
		for(Privacy protectedValue : List.of(Privacy.PRIVATE,Privacy.PRIVATE_AGGREGATE)) {
			MemoPrivacy privacy=privacy(facts,protectedValue);
			int returned=facts.call(0).returnBindingIds().get(0);
			Assert.assertNotEquals(MemoPrivacy.Legality.LEGAL,
				privacy.localResidencyAt(returned,context(facts,0,true)).legality());
			// Query both orders: a prior successful arm must not authorize its sibling.
			for(boolean branch : new boolean[] {true,false,true,false}) {
				var selected=context(facts,0,true,branch);
				Assert.assertEquals(branch ? MemoPrivacy.Legality.LEGAL : MemoPrivacy.Legality.ILLEGAL,
					privacy.localResidencyAt(returned,selected).legality());
				Assert.assertEquals(branch ? MemoPrivacy.Legality.LEGAL : MemoPrivacy.Legality.ILLEGAL,
					localRule(privacy,facts,selected).legality());
			}
		}
	}

	@Test
	public void releasedLoopPayloadIsUsableOnlyOnTheNormalReturnPath() throws Exception {
		MemoProgramFacts facts=analyze("f=function(boolean p) return(matrix[double] Y){"
			+"if(p){X=matrix(1,2,2);A=matrix(2,2,2);i=0;while(i<3){A=abs(X);i=i+1;}Y=abs(A);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;B=f(p);C=abs(B);print(sum(C));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(owner(facts,"X"),Privacy.PRIVATE_AGGREGATE_TO_PUBLIC));
		int returned=facts.call(0).returnBindingIds().get(0);
		Assert.assertFalse(privacy.provesReleasedBinding(returned));
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL,
			privacy.localResidencyAt(returned,context(facts,0,true)).legality());
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL,localRule(privacy,facts,context(facts,0,true)).legality());
		Assert.assertEquals(MemoPrivacy.Legality.DEFER,
			privacy.localResidencyAt(returned,context(facts,0,false)).legality());
	}

	@Test
	public void siblingCallFramesDoNotReuseNormalReturnProofs() throws Exception {
		MemoProgramFacts facts=analyze("f=function(boolean p) return(matrix[double] Y){if(p){Y=matrix(1,2,2);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p);B=f(q);print(sum(A)+sum(B));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),Map.of());
		for(int call : new int[] {0,1,0}) {
			int returned=facts.call(call).returnBindingIds().get(0);
			Assert.assertEquals(MemoPrivacy.Legality.LEGAL,
				privacy.localResidencyAt(returned,context(facts,call,true)).legality());
			Assert.assertEquals(MemoPrivacy.Legality.DEFER,
				privacy.localResidencyAt(returned,context(facts,call,false)).legality());
		}
	}

	@Test
	public void loopZeroAndNonzeroResidencyDoNotShareAProof() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,2,2);A=matrix(2,2,2);"
			+"n=as.scalar(rand(rows=1,cols=1));i=0;while(i<n){A=abs(X);i=i+1;}C=abs(A);print(sum(C));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),Map.of(owner(facts,"X"),Privacy.PRIVATE));
		int binding=facts.inputBinding(consumer(facts),0);
		for(var count : List.of(MemoProgramFacts.LoopCardinality.ZERO,MemoProgramFacts.LoopCardinality.ONE,
			MemoProgramFacts.LoopCardinality.MANY,MemoProgramFacts.LoopCardinality.ZERO)) {
			var context=new MemoPrivacy.Context(EvaluationContext.empty().withLoopCardinality(0,count),Map.of());
			Assert.assertEquals(count==MemoProgramFacts.LoopCardinality.ZERO
				? MemoPrivacy.Legality.LEGAL : MemoPrivacy.Legality.ILLEGAL,
				privacy.localResidencyAt(binding,context).legality());
		}
	}

	@Test
	public void releasedInputsCannotOverrideAnExplicitlyProtectedOutput() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,2,2);C=abs(X);print(sum(C));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(facts.occurrence(consumer(facts)).compiledOwnerId(),Privacy.PRIVATE));
		Assert.assertEquals(MemoPrivacy.Legality.ILLEGAL,
			localRule(privacy,facts,MemoPrivacy.Context.empty()).legality());
	}

	@Test
	public void missingSecondaryReturnDoesNotAuthorizeTheFirstPayload() throws Exception {
		MemoProgramFacts facts=analyze("f=function(boolean p,boolean q) return(matrix[double] Y,matrix[double] Z){"
			+"if(p){if(q){Y=matrix(1,2,2);Z=matrix(2,2,2);}else{Y=matrix(2,2,2);}}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"[A,B]=f(p,q);C=abs(A);print(sum(C)+sum(B));");
		MemoPrivacy privacy=privacy(facts,Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		int returned=facts.call(0).returnBindingIds().get(0);
		for(boolean q : new boolean[] {true,false,true})
			Assert.assertEquals(q ? MemoPrivacy.Legality.LEGAL : MemoPrivacy.Legality.DEFER,
				privacy.localResidencyAt(returned,context(facts,0,true,q)).legality());
	}

	@Test
	public void sharedFunctionBodyKeepsReleasedAndPrivateActualsSeparate() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X)return(matrix[double] Y){Y=abs(X);}"
			+"P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);print(sum(A)+sum(B));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(owner(facts,"P"),Privacy.PRIVATE_AGGREGATE_TO_PUBLIC,owner(facts,"Q"),Privacy.PRIVATE));
		for(int call : new int[] {0,1,0,1})
			Assert.assertEquals(call==0 ? MemoPrivacy.Legality.LEGAL : MemoPrivacy.Legality.ILLEGAL,
				privacy.localResidencyAt(facts.call(call).returnBindingIds().get(0),MemoPrivacy.Context.empty()).legality());
	}

	private static MemoProgramFacts branchReturn() throws Exception {
		return analyze("f=function(boolean p,boolean q) return(matrix[double] Y){if(p){"
			+"if(q){Y=matrix(1,2,2);}else{Y=matrix(2,2,2);}}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p,q);C=abs(A);print(sum(C));");
	}
	private static MemoPrivacy privacy(MemoProgramFacts facts,Privacy secondArm) {
		Map<Integer,Privacy> sources=new HashMap<>();
		facts.occurrences().stream().filter(o -> "Y".equals(o.hop().getName()))
			.filter(o -> o.context().contains("/if-1/")).forEach(o -> sources.put(o.compiledOwnerId(),
				o.context().contains("/if-1/else") ? secondArm : Privacy.PUBLIC));
		Assert.assertFalse(sources.isEmpty());
		return new MemoPrivacy(facts,new MemoBindingResolver(facts),sources);
	}
	private static MemoPrivacy.Context context(MemoProgramFacts facts,int call,boolean... guards) {
		Map<GuardDecision,Boolean> selected=new HashMap<>();
		for(int prior=0;prior<call;prior++)
			selected.put(new GuardDecision(facts.guards().get(0).id(),List.of(prior)),true);
		for(int i=0;i<guards.length;i++) selected.put(new GuardDecision(facts.guards().get(i).id(),List.of(call)),guards[i]);
		return new MemoPrivacy.Context(new EvaluationContext(selected,Map.of(),MemoBindingResolver.NO_SELECTION,4096),Map.of());
	}
	private static MemoPrivacy.RuleCheck localRule(MemoPrivacy privacy,MemoProgramFacts facts,MemoPrivacy.Context context) {
		var rule=new RuleMemo.Rule(ExecType.CP,List.of(MemoPartition.LOCAL),List.of(MemoPartition.LOCAL),"guarded-residency-test");
		return privacy.unconditionalRuleLegalityAt(consumer(facts),rule,List.of(InputRole.PAYLOAD),context);
	}
	private static int consumer(MemoProgramFacts facts) {
		return facts.occurrences().stream().filter(o -> "C".equals(o.hop().getName())
			&& o.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
	}
	private static int owner(MemoProgramFacts facts,String name) {
		return facts.occurrences().stream().filter(o -> name.equals(o.hop().getName()))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
	}
	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

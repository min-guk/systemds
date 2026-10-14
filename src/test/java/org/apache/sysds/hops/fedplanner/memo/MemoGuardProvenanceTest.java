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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoGuardProvenanceTest {
	@Test public void actualAndFormalGuardAreOneDecisionAndDoNotAdmitMissingReturn() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,2,2);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);C=abs(matrix(3,2,2));print(sum(C));}");
		GuardPair pair=outerAndFunctionGuard(fixture.facts());
		Assert.assertEquals(pair.outerDecision(),pair.innerDecision());

		MemoBindingResolver resolver=new MemoBindingResolver(fixture.facts());
		int returned=fixture.facts().call(pair.callId()).returnBindingIds().get(0);
		Map<MemoBindingResolver.GuardDecision,Boolean> outerTrue=Map.of(pair.outerDecision(),
			fixture.facts().canonicalGuardValue(pair.outerGuard(),List.of(),true));
		List<MemoBindingResolver.ResolvedEnvironment> environments=new ArrayList<>();
		MemoBindingResolver.VisitResult result=resolver.visitJoint(List.of(returned),
			new MemoBindingResolver.EvaluationContext(outerTrue,Map.of(),MemoBindingResolver.NO_SELECTION,4096),
			environment -> { environments.add(environment); return true; });
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
		Assert.assertEquals("the inner false/missing arm is impossible once the same actual is true",1,
			environments.size());
		Assert.assertNotEquals(MemoBindingResolver.OriginKind.MISSING,environments.get(0).origins().get(0).kind());
	}

	@Test public void correlatedActivationCostsOneHalfRatherThanOneQuarter() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,2,2);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);C=abs(matrix(3,2,2));print(sum(C));}");
		MemoProgramFacts.Occurrence abs=fixture.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		MemoProgramModel model=new MemoProgramModel(fixture.facts(),fixture.sources(),fixture.universe(),
			MemoProgramModel.Profile.defaults());
		double primitive=MemoCostModel.nativeCost(fixture.universe().shapes().viewAt(abs.id()),
			fixture.universe().memo().rule(abs.compiledOwnerId(),0),1,0.5);
		Assert.assertEquals("outer p and formal q are the same decision, not two independent 0.5 guards",
			0,new BigDecimal(primitive).compareTo(nativeOwnerCost(model,abs.compiledOwnerId(),0)));
	}

	@Test public void nestedFunctionKernelIsNotChargedAsTwoIndependentGuards() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){"
			+"if(q){Y=abs(matrix(1,2,2));}}p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);}");
		MemoProgramFacts.Occurrence abs=fixture.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		MemoProgramModel model=new MemoProgramModel(fixture.facts(),fixture.sources(),fixture.universe(),
			MemoProgramModel.Profile.defaults());
		double primitive=MemoCostModel.nativeCost(fixture.universe().shapes().viewAt(abs.id()),
			fixture.universe().memo().rule(abs.compiledOwnerId(),0),1,0.5);
		Assert.assertEquals(0,new BigDecimal(primitive).compareTo(nativeOwnerCost(model,abs.compiledOwnerId(),0)));
	}

	@Test public void independentAndRedefinedBooleansRemainDistinct() throws Exception {
		Fixture independent=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"q=as.scalar(rand(rows=1,cols=1))>0.5;if(p){print(1);}if(q){print(2);}");
		Assert.assertEquals(2,independent.facts().guards().size());
		Assert.assertNotEquals(independent.facts().guardDecision(0,List.of()),
			independent.facts().guardDecision(1,List.of()));

		Fixture redefined=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){print(1);}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){print(2);}");
		Assert.assertEquals(2,redefined.facts().guards().size());
		Assert.assertNotEquals("a new reaching definition is a new boolean value version",
			redefined.facts().guardDecision(0,List.of()),redefined.facts().guardDecision(1,List.of()));
	}

	@Test public void repeatedCallFramesCorrelateOnlyThroughTheSameActual() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,1,1);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p);B=f(p);C=f(q);");
		int functionGuard=fixture.facts().guards().stream()
			.filter(guard -> fixture.facts().region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow().id();
		Assert.assertEquals(3,fixture.facts().calls().size());
		var first=fixture.facts().guardDecision(functionGuard,List.of(0));
		var second=fixture.facts().guardDecision(functionGuard,List.of(1));
		var third=fixture.facts().guardDecision(functionGuard,List.of(2));
		Assert.assertEquals(first,second);
		Assert.assertNotEquals(first,third);
	}

	@Test public void knownNegationSharesValueAndImpossibleConjunctionCostsZero() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(!q){Y=matrix(1,1,1);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);}");
		GuardPair pair=outerAndFunctionGuard(fixture.facts());
		Assert.assertEquals(pair.outerDecision(),pair.innerDecision());
		Assert.assertEquals(fixture.facts().canonicalGuardValue(pair.outerGuard(),List.of(),true),
			fixture.facts().canonicalGuardValue(pair.innerGuard(),List.of(pair.callId()),false));

		Fixture impossible=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){if(!p){C=abs(matrix(3,2,2));print(sum(C));}}");
		MemoProgramFacts.Occurrence abs=impossible.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		MemoProgramModel model=new MemoProgramModel(impossible.facts(),impossible.sources(),impossible.universe(),
			MemoProgramModel.Profile.defaults());
		Assert.assertEquals(BigDecimal.ZERO,nativeOwnerCost(model,abs.compiledOwnerId(),0));
	}

	@Test public void privacyCacheProjectsCanonicalCallerGuardInEitherQueryOrder() throws Exception {
		Fixture fixture=fixture("f=function(boolean q,matrix[double] P,matrix[double] U) "
			+"return(matrix[double] Y){if(q){Y=P;}else{Y=U;}}"
			+"P=matrix(1,2,2);U=matrix(2,2,2);p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=f(p,P,U);print(sum(A));}");
		GuardPair pair=outerAndFunctionGuard(fixture.facts());
		MemoBindingResolver resolver=new MemoBindingResolver(fixture.facts());
		MemoProgramFacts.CallBinding call=fixture.facts().call(pair.callId());
		Map<Integer,Privacy> sourcePrivacy=new HashMap<>();
		resolver.influencingCompiledOwners(call.actualBindingIds().get(1)).forEach(owner ->
			sourcePrivacy.put(owner,Privacy.PRIVATE));
		resolver.influencingCompiledOwners(call.actualBindingIds().get(2)).forEach(owner ->
			sourcePrivacy.put(owner,Privacy.PUBLIC));
		for(boolean privateFirst : new boolean[] {true,false}) {
			MemoPrivacy privacy=new MemoPrivacy(fixture.facts(),resolver,sourcePrivacy);
			Assert.assertEquals(privateFirst ? Privacy.PRIVATE : Privacy.PUBLIC,
				returnPrivacy(privacy,fixture.facts(),call,pair.outerDecision(),privateFirst));
			Assert.assertEquals(privateFirst ? Privacy.PUBLIC : Privacy.PRIVATE,
				returnPrivacy(privacy,fixture.facts(),call,pair.outerDecision(),!privateFirst));
			Assert.assertTrue("the second arm must be evaluated rather than reusing the first arm's cache entry",
				privacy.statistics().evaluations()>1);
		}
	}

	private static Privacy returnPrivacy(MemoPrivacy privacy,MemoProgramFacts facts,
		MemoProgramFacts.CallBinding call,MemoBindingResolver.GuardDecision callerDecision,boolean callerTruth) {
		var evaluation=new MemoBindingResolver.EvaluationContext(Map.of(callerDecision,callerTruth),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(call.id()));
		int summaryReturn=facts.functionSummary(call.functionSummaryId()).returnBindingIds().get(0);
		var result=privacy.privacyAt(summaryReturn,new MemoPrivacy.Context(evaluation,Map.of()));
		if(result.privacy()==null) throw new AssertionError(result);
		return result.privacy();
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) { }
	private record GuardPair(int outerGuard,int innerGuard,int callId,
		MemoBindingResolver.GuardDecision outerDecision,MemoBindingResolver.GuardDecision innerDecision) { }

	private static GuardPair outerAndFunctionGuard(MemoProgramFacts facts) {
		int outer=facts.guards().stream().filter(guard -> facts.region(guard.parentRegionId()).kind()!=
			MemoProgramFacts.RegionKind.FUNCTION).findFirst().orElseThrow().id();
		int inner=facts.guards().stream().filter(guard -> facts.region(guard.parentRegionId()).kind()==
			MemoProgramFacts.RegionKind.FUNCTION).findFirst().orElseThrow().id();
		int call=facts.calls().get(0).id();
		return new GuardPair(outer,inner,call,facts.guardDecision(outer,List.of()),
			facts.guardDecision(inner,List.of(call)));
	}

	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,
			new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}

	@SuppressWarnings("unchecked")
	private static BigDecimal nativeOwnerCost(MemoProgramModel model,int owner,int rule) throws Exception {
		Field nativeCostsField=MemoProgramModel.class.getDeclaredField("nativeCosts");
		nativeCostsField.setAccessible(true);
		BigDecimal result=new BigDecimal(((double[][])nativeCostsField.get(model))[owner][rule]);
		Field countField=MemoProgramModel.class.getDeclaredField("nativeCostTermCount"); countField.setAccessible(true);
		Field costsField=MemoProgramModel.class.getDeclaredField("costs"); costsField.setAccessible(true);
		List<MemoOptimizer.CostTerm> terms=(List<MemoOptimizer.CostTerm>)costsField.get(model);
		for(int index=0;index<(int)countField.get(model);index++) {
			MemoOptimizer.CostTerm term=terms.get(index);
			if(term.owners().length==1 && term.owners()[0]==owner)
				result=result.add(term.evaluateExact(new int[] {rule}));
		}
		return result;
	}
}

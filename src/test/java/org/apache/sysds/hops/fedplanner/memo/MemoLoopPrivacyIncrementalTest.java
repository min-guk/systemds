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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.LoopSlot;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Differential behavior lock for the dependency-scoped loop-summary scheduler.
 *
 * <p>The reference deliberately retains the original synchronous full scan: in
 * every refinement round it evaluates every surviving carried value, removes
 * all failures as one batch, and reconstructs direct nested-loop summaries from
 * the incoming context.  It is test-only and does not share production summary
 * caches or scheduling decisions.</p>
 */
public class MemoLoopPrivacyIncrementalTest {
	private static final List<Privacy> LABELS=List.of(Privacy.values());
	private static final Method WITH_LOOP_HEADS=withLoopHeadsMethod();

	@Test
	public void exhaustiveLabelsPreserveIndependentChainAndDelayedRemoval() throws Exception {
		MemoProgramFacts facts=analyze("P=matrix(1,2,2);Q=matrix(2,2,2);X=matrix(3,2,2);"
			+"A=P;B=Q;C=P;for(i in 1:2){B=abs(A)+B;C=abs(C);A=abs(X);}"
			+"print(sum(A)+sum(B)+sum(C));");
		int p=owner(facts,"P"),q=owner(facts,"Q"),x=owner(facts,"X");
		for(Privacy pp : LABELS) for(Privacy pq : LABELS) for(Privacy px : LABELS)
			assertEquivalent(facts,0,MemoPrivacy.Context.empty(),Map.of(p,pp,q,pq,x,px));
	}

	@Test
	public void dependencySchedulingSkipsUnaffectedSurvivorAfterBatchRemoval() throws Exception {
		MemoProgramFacts facts=analyze("P=matrix(1,2,2);Q=matrix(2,2,2);X=matrix(3,2,2);"
			+"A=P;B=Q;C=P;for(i in 1:2){B=abs(A)+B;C=abs(C);A=abs(X);}"
			+"print(sum(A)+sum(B)+sum(C));");
		Map<Integer,Privacy> labels=Map.of(owner(facts,"P"),Privacy.PUBLIC,
			owner(facts,"Q"),Privacy.PUBLIC,owner(facts,"X"),Privacy.PRIVATE);
		MemoPrivacy actual=new MemoPrivacy(facts,new MemoBindingResolver(facts),labels);
		FullScanReference reference=new FullScanReference(facts,
			new MemoPrivacy(facts,new MemoBindingResolver(facts),labels));
		Map<LoopSlot,Result> expected=reference.summarize(0,MemoPrivacy.Context.empty());
		Assert.assertEquals(expected,actual.summarizeLoop(0,MemoPrivacy.Context.empty()));
		Assert.assertEquals("the full scan checks A/B/C, then B/C, then surviving C",6,
			reference.backedgeChecks());
		Assert.assertEquals("the dependency scheduler must not recheck independent C",4,
			actual.statistics().loopBackedgeChecks());
		Assert.assertTrue("a removed head must drive a bounded reverse-influence walk",
			actual.statistics().invalidationVisits()>0);
		System.out.printf("loop-summary-checks full=%d incremental=%d invalidationVisits=%d "
			+"invalidationEdges=%d indexVisits=%d indexEdges=%d%n",reference.backedgeChecks(),
			actual.statistics().loopBackedgeChecks(),actual.statistics().invalidationVisits(),
			actual.statistics().invalidationEdges(),actual.statistics().indexVisits(),
			actual.statistics().indexEdges());
	}

	@Test
	public void transitiveHeadInfluenceAcrossThreeRemovalWavesMatchesFullScan() throws Exception {
		MemoProgramFacts facts=analyze("P=matrix(1,2,2);Q=matrix(2,2,2);R=matrix(3,2,2);"
			+"X=matrix(4,2,2);A=P;B=Q;D=R;C=P;"
			+"for(i in 1:2){D=abs(B)+D;B=abs(A)+B;C=abs(C);A=abs(X);}"
			+"print(sum(A)+sum(B)+sum(C)+sum(D));");
		int p=owner(facts,"P"),q=owner(facts,"Q"),r=owner(facts,"R"),x=owner(facts,"X");
		for(Privacy px : LABELS)
			assertEquivalent(facts,0,MemoPrivacy.Context.empty(),
				Map.of(p,Privacy.PUBLIC,q,Privacy.PUBLIC,r,Privacy.PUBLIC,x,px));
	}

	@Test
	public void nestedLoopAndGuardSummariesMatchFullScanForAllLabels() throws Exception {
		MemoProgramFacts nested=analyze("P=matrix(1,2,2);Q=matrix(2,2,2);X=matrix(3,2,2);"
			+"A=P;B=Q;for(i in 1:2){C=P;for(j in 1:2){B=abs(B)+C;C=abs(A);}A=abs(X);}"
			+"print(sum(A)+sum(B));");
		int outer=nested.loops().stream().filter(loop -> loop.carriedValues().stream()
			.anyMatch(value -> "A".equals(value.variable()))).findFirst().orElseThrow().id();
		int p=owner(nested,"P"),q=owner(nested,"Q"),x=owner(nested,"X");
		for(Privacy pp : LABELS) for(Privacy px : LABELS)
			assertEquivalent(nested,outer,MemoPrivacy.Context.empty(),
				Map.of(p,pp,q,Privacy.PUBLIC,x,px));

		MemoProgramFacts guarded=analyze("P=matrix(1,2,2);Q=matrix(2,2,2);A=P;"
			+"for(i in 1:3){if(i==1){A=Q;}else{A=abs(A);}}print(sum(A));");
		int gp=owner(guarded,"P"),gq=owner(guarded,"Q");
		for(Privacy pp : LABELS) for(Privacy pq : LABELS)
			assertEquivalent(guarded,0,MemoPrivacy.Context.empty(),Map.of(gp,pp,gq,pq));
		for(MemoPrivacy.Context context : phaseContexts(0))
			for(Privacy pp : LABELS) for(Privacy pq : LABELS)
				assertEquivalent(guarded,0,context,Map.of(gp,pp,gq,pq));
	}

	@Test
	public void inlinedReturnInfluenceMatchesFullScanForEveryLabelPair() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X)return(matrix[double] Y){Y=abs(X);}"
			+"P=matrix(1,2,2);Q=matrix(2,2,2);X=matrix(3,2,2);A=P;B=Q;"
			+"for(i in 1:2){T=f(A);B=abs(T)+B;A=abs(X);}print(sum(A)+sum(B));");
		Assert.assertTrue("the straight-line function must expose an inlined call boundary",
			facts.calls().stream().anyMatch(call -> call.callOccurrenceId()<0));
		int p=owner(facts,"P"),q=owner(facts,"Q"),x=owner(facts,"X");
		for(Privacy pp : LABELS) for(Privacy px : LABELS)
			assertEquivalent(facts,0,MemoPrivacy.Context.empty(),
				Map.of(p,pp,q,Privacy.PUBLIC,x,px));
	}

	@Test
	public void aggregationReleaseMatchesFullScanForEveryInputLabel() throws Exception {
		MemoProgramFacts facts=analyze("P=matrix(1,2,2);A=sum(P);"
			+"for(i in 1:2){A=sum(P);}print(A);");
		int p=owner(facts,"P");
		for(Privacy label : LABELS)
			assertEquivalent(facts,0,MemoPrivacy.Context.empty(),Map.of(p,label));
	}

	@Test
	public void retainedMultiReturnFunctionCallFramesMatchFullScan() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X)"
			+"return(matrix[double] Y,matrix[double] Z){A=X;B=X;"
			+"for(i in 1:2){A=abs(A);B=abs(B);}Y=A;Z=B;}"
			+"P=matrix(1,2,2);Q=matrix(2,2,2);[R,S]=f(P);[T,U]=f(Q);"
			+"print(sum(R)+sum(S)+sum(T)+sum(U));");
		Assert.assertEquals("the loop body must remain a shared compiled function summary",1,facts.loops().size());
		Assert.assertEquals(2,facts.calls().size());
		int p=owner(facts,"P"),q=owner(facts,"Q"),loop=facts.loops().get(0).id();
		for(Privacy pp : LABELS) for(Privacy pq : LABELS) {
			Map<Integer,Privacy> labels=Map.of(p,pp,q,pq);
			for(MemoProgramFacts.CallBinding call : facts.calls()) {
				MemoPrivacy.Context context=MemoPrivacy.Context.empty().withCallFrame(List.of(call.id()));
				assertEquivalent(facts,loop,context,labels);
			}
		}
	}

	@Test
	public void outerLoopRechecksRequestedReturnThroughWholeCallCompletion() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] U,matrix[double] V,boolean p)"
			+"return(matrix[double] Y,matrix[double] Z){Y=abs(U);Z=V;if(p){Y=abs(U);}}"
			+"P=matrix(1,2,2);Q=matrix(2,2,2);X=matrix(3,2,2);A=P;B=Q;D=P;"
			+"for(i in 1:2){[B,D]=f(B,A,i>1);A=abs(X);}print(sum(A)+sum(B)+sum(D));");
		Assert.assertTrue("the guarded multi-return call must retain a runtime call occurrence",
			facts.calls().stream().anyMatch(call -> call.callOccurrenceId()>=0));
		int outer=facts.loops().stream().filter(loop -> loop.carriedValues().stream()
			.anyMatch(value -> "A".equals(value.variable()))).findFirst().orElseThrow().id();
		Map<Integer,Privacy> labels=Map.of(owner(facts,"P"),Privacy.PUBLIC,
			owner(facts,"Q"),Privacy.PUBLIC,owner(facts,"X"),Privacy.PRIVATE);
		MemoPrivacy actual=new MemoPrivacy(facts,new MemoBindingResolver(facts),labels);
		FullScanReference reference=new FullScanReference(facts,
			new MemoPrivacy(facts,new MemoBindingResolver(facts),labels));
		long initialCandidates=facts.loop(outer).carriedValues().stream()
			.filter(value -> actual.privacyAt(value.entryBindingId(),MemoPrivacy.Context.empty()).exact()).count();
		Map<LoopSlot,Result> expected=reference.summarize(outer,MemoPrivacy.Context.empty());
		Map<LoopSlot,Result> observed=actual.summarizeLoop(outer,MemoPrivacy.Context.empty());
		Assert.assertEquals(expected,observed);
		MemoProgramFacts.CarriedValue a=carried(facts,outer,"A"),b=carried(facts,outer,"B");
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,
			observed.get(new LoopSlot(outer,a.slot())).status());
		Assert.assertEquals("B's payload stays PUBLIC, but must be rechecked because the same call's Z uses A",
			Result.exact(Privacy.PUBLIC),observed.get(new LoopSlot(outer,b.slot())));
		Assert.assertTrue("at least one requested return must be rechecked after the initial candidate scan",
			actual.statistics().loopBackedgeChecks()>initialCandidates);
		Assert.assertEquals(reference.backedgeChecks(),actual.statistics().loopBackedgeChecks());
		Assert.assertTrue(actual.statistics().invalidationVisits()>0);
		System.out.printf("whole-call-completion initialCandidates=%d outerChecks=%d invalidationVisits=%d "
			+"invalidationEdges=%d%n",initialCandidates,actual.statistics().loopBackedgeChecks(),
			actual.statistics().invalidationVisits(),actual.statistics().invalidationEdges());
	}

	private static void assertEquivalent(MemoProgramFacts facts,int loopId,MemoPrivacy.Context context,
		Map<Integer,Privacy> sources) {
		MemoPrivacy actual=new MemoPrivacy(facts,new MemoBindingResolver(facts),sources);
		MemoPrivacy referencePrivacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),sources);
		Map<LoopSlot,Result> expected=new FullScanReference(facts,referencePrivacy).summarize(loopId,context);
		Map<LoopSlot,Result> observed=actual.summarizeLoop(loopId,context);
		Assert.assertEquals("loop="+loopId+", frame="+context.evaluation().callFrame()+", sources="+sources,
			expected,observed);
	}

	private static final class FullScanReference {
		private final MemoProgramFacts facts;
		private final MemoPrivacy privacy;
		private final Set<String> active=new LinkedHashSet<>();
		private long backedgeChecks;

		private FullScanReference(MemoProgramFacts facts,MemoPrivacy privacy) {
			this.facts=facts;
			this.privacy=privacy;
		}

		private Map<LoopSlot,Result> summarize(int loopId,MemoPrivacy.Context context) {
			MemoProgramFacts.LoopRecord loop=facts.loop(loopId);
			String invocation=loopId+"@"+context.evaluation().callFrame();
			if(!active.add(invocation)) return unknownLoop(loop,"loop privacy summary cycle is not grounded");
			try {
				Map<LoopSlot,Result> entry=new LinkedHashMap<>(),rejected=new LinkedHashMap<>();
				Set<LoopSlot> candidates=new LinkedHashSet<>();
				for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
					LoopSlot slot=new LoopSlot(loopId,value.slot());
					Result fact=privacy.privacyAt(value.entryBindingId(),context);
					entry.put(slot,fact);
					if(fact.exact()) candidates.add(slot);
					else rejected.put(slot,Result.unknown("loop entry privacy is unresolved"));
				}
				while(!candidates.isEmpty()) {
					Map<LoopSlot,Privacy> current=new LinkedHashMap<>(context.loopHeadPrivacy());
					for(LoopSlot slot : candidates) current.put(slot,entry.get(slot).privacy());
					MemoPrivacy.Context bound=withNestedLoopSummaries(loopId,withLoopHeads(context,current));
					Set<LoopSlot> remove=new LinkedHashSet<>();
					for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
						LoopSlot slot=new LoopSlot(loopId,value.slot());
						if(!candidates.contains(slot)) continue;
						backedgeChecks++;
						Result backedge=privacy.privacyAt(value.backedgeBindingId(),bound);
						if(!backedge.exact()) {
							remove.add(slot);
							rejected.put(slot,Result.unknown("loop backedge privacy is unresolved"));
						}
						else if(backedge.privacy()!=entry.get(slot).privacy()) {
							remove.add(slot);
							rejected.put(slot,Result.unknown("loop privacy changes between entry and backedge"));
						}
					}
					if(remove.isEmpty()) break;
					candidates.removeAll(remove);
				}
				Map<LoopSlot,Result> result=new LinkedHashMap<>();
				for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
					LoopSlot slot=new LoopSlot(loopId,value.slot());
					result.put(slot,candidates.contains(slot) ? entry.get(slot) : rejected.get(slot));
				}
				return Map.copyOf(result);
			}
			finally {
				active.remove(invocation);
			}
		}

		private long backedgeChecks() {
			return backedgeChecks;
		}

		private MemoPrivacy.Context withNestedLoopSummaries(int loopId,MemoPrivacy.Context context) {
			MemoPrivacy.Context result=context;
			for(MemoProgramFacts.LoopRecord nested : facts.loops()) {
				if(nested.id()==loopId || directEnclosingLoop(nested)!=loopId) continue;
				Map<LoopSlot,Result> summary=summarize(nested.id(),result);
				for(Map.Entry<LoopSlot,Result> entry : summary.entrySet()) if(entry.getValue().exact())
					result=result.withLoopHead(entry.getKey().loopId(),entry.getKey().slot(),entry.getValue().privacy());
			}
			return result;
		}

		private int directEnclosingLoop(MemoProgramFacts.LoopRecord loop) {
			for(int region=facts.region(loop.regionId()).parentId();region>=0;
				region=facts.region(region).parentId()) {
				int enclosing=facts.region(region).loopId();
				if(enclosing>=0 && enclosing!=loop.id()) return enclosing;
			}
			return -1;
		}
	}

	private static MemoPrivacy.Context withLoopHeads(MemoPrivacy.Context context,Map<LoopSlot,Privacy> heads) {
		try {
			return (MemoPrivacy.Context) WITH_LOOP_HEADS.invoke(context,heads);
		}
		catch(IllegalAccessException e) {
			throw new AssertionError(e);
		}
		catch(InvocationTargetException e) {
			throw new AssertionError(e.getCause());
		}
	}

	private static Method withLoopHeadsMethod() {
		try {
			Method method=MemoPrivacy.Context.class.getDeclaredMethod("withLoopHeads",Map.class);
			method.setAccessible(true);
			return method;
		}
		catch(ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static Map<LoopSlot,Result> unknownLoop(MemoProgramFacts.LoopRecord loop,String detail) {
		Map<LoopSlot,Result> result=new LinkedHashMap<>();
		for(MemoProgramFacts.CarriedValue value : loop.carriedValues())
			result.put(new LoopSlot(loop.id(),value.slot()),Result.unknown(detail));
		return Map.copyOf(result);
	}

	private static List<MemoPrivacy.Context> phaseContexts(int loopId) {
		return List.of(
			new MemoPrivacy.Context(MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(loopId,LoopCardinality.ZERO),Map.of()),
			new MemoPrivacy.Context(MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(loopId,LoopCardinality.ONE)
				.withIterationPhase(loopId,IterationPhase.FIRST),Map.of()),
			new MemoPrivacy.Context(MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(loopId,LoopCardinality.MANY)
				.withIterationPhase(loopId,IterationPhase.FIRST),Map.of()),
			new MemoPrivacy.Context(MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(loopId,LoopCardinality.MANY)
				.withIterationPhase(loopId,IterationPhase.MIDDLE),Map.of()),
			new MemoPrivacy.Context(MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(loopId,LoopCardinality.MANY)
				.withIterationPhase(loopId,IterationPhase.LAST),Map.of()));
	}

	private static int owner(MemoProgramFacts facts,String name) {
		return facts.occurrences().stream().filter(value -> name.equals(value.hop().getName())
			&& !value.context().contains("function") && !value.context().contains("loop-"))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
	}

	private static MemoProgramFacts.CarriedValue carried(MemoProgramFacts facts,int loopId,String variable) {
		return facts.loop(loopId).carriedValues().stream()
			.filter(value -> variable.equals(value.variable())).findFirst().orElseThrow();
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

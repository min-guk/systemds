/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoPhaseGuardIndexTest {
	@Test public void indexedPredicatePreservesCanonicalFramesAndMalformedFallback() throws Exception {
		String script="f=function(boolean p,matrix[double] X) return(matrix[double] Y){"
			+"Y=X;for(i in 1:3){if(i==1){Y=abs(Y);}if(p){Y=Y+1;}if(i==3){print(sum(Y));}}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;A=matrix(1,2,3);B=f(p,A);C=f(!p,A);print(sum(C));";
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		Method candidate=MemoShapes.class.getDeclaredMethod("phaseProvesGuard",LoopDecision.class,
			IterationPhase.class,GuardDecision.class);
		candidate.setAccessible(true);
		List<List<Integer>> frames=new ArrayList<>(); frames.add(List.of());
		for(int call=0;call<facts.calls().size();call++) frames.add(List.of(call));
		if(facts.calls().size()>1) frames.add(List.of(0,1));
		List<GuardDecision> decisions=new ArrayList<>();
		for(MemoProgramFacts.Guard guard:facts.guards()) for(List<Integer> frame:frames) {
			decisions.add(facts.guardDecision(guard.id(),frame));
			decisions.add(new GuardDecision(guard.id(),frame));
		}
		for(MemoProgramFacts.Guard guard:facts.guards())
			decisions.add(new GuardDecision(guard.id(),List.of(999)));
		frames.add(List.of(999));
		List<IterationPhase> phases=new ArrayList<>(List.of(IterationPhase.values())); phases.add(null);
		boolean knownFalse=false;
		int comparisons=0;
		for(int loop=-1;loop<=facts.loops().size();loop++) for(List<Integer> frame:frames)
			for(IterationPhase phase:phases) for(GuardDecision decision:decisions) {
				LoopDecision identity=new LoopDecision(loop,frame);
				Object expected;
				try { expected=reference(facts,identity,phase,decision); }
				catch(RuntimeException error) { expected=error.getClass(); }
				Object actual;
				try { actual=candidate.invoke(shapes,identity,phase,decision); }
				catch(InvocationTargetException error) { actual=error.getCause().getClass(); }
				Assert.assertEquals(identity+" "+phase+" "+decision,expected,actual);
				comparisons++;
			}
		for(MemoProgramFacts.GuardPhase guard:facts.guardPhases())
			for(IterationPhase phase:IterationPhase.values())
				knownFalse|=Boolean.FALSE.equals(facts.guardValue(guard.loopId(),guard.guardId(),phase));
		Assert.assertTrue("fixture must exercise phase-proven false",knownFalse);
		Assert.assertTrue(comparisons>100);
	}

	private static boolean reference(MemoProgramFacts facts,LoopDecision loop,IterationPhase iteration,
		GuardDecision decision) {
		for(MemoProgramFacts.GuardPhase phase:facts.guardPhases(loop.loopId())) {
			int guard=phase.guardId();
			if(facts.guardValue(loop.loopId(),guard,iteration)==null) continue;
			for(int length=loop.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,loop.callFrame().subList(0,length))) return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,decision.callFrame().subList(0,length))) return true;
		}
		return false;
	}
}

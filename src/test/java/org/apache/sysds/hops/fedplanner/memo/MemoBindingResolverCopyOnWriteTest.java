/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoBindingResolverCopyOnWriteTest {
	@Test public void repeatedImmutableContextKeepsExactEnvironmentOrderAndSnapshots() throws Exception {
		MemoProgramFacts facts=analyze("p=as.scalar(rand(rows=1,cols=1));"
			+"if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+"else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));");
		int consumer=facts.occurrences().stream().filter(o -> o.hop().getInput().size()==2
			&& o.hop().getInput(0) instanceof DataOp left && "A".equals(left.getName())
			&& o.hop().getInput(1) instanceof DataOp right && "B".equals(right.getName()))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		List<Integer> roots=List.of(facts.inputBinding(consumer,0),facts.inputBinding(consumer,1));
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		EvaluationContext context=EvaluationContext.empty();
		List<MemoBindingResolver.ResolvedEnvironment> first=new ArrayList<>(),second=new ArrayList<>();
		resolver.visitJoint(roots,context,environment -> { first.add(environment); return true; });
		String before=signature(first);
		resolver.visitJoint(roots,context,environment -> { second.add(environment); return true; });
		Assert.assertEquals(before,signature(first));
		Assert.assertEquals(before,signature(second));
		System.out.println("RESOLVER_COW_ENV_SHA256="+sha256(before));
		Assert.assertEquals(2,first.size());
		Assert.assertEquals(1,first.get(0).guards().size());
		Assert.assertNotEquals(first.get(0).guards(),first.get(1).guards());
		try {
			first.get(0).guards().clear();
			Assert.fail("resolved environment must be immutable");
		}
		catch(UnsupportedOperationException expected) { }
	}

	@Test public void sameDecisionMapsDoNotCacheDepthOrLiveSelectionPayload() throws Exception {
		MemoProgramFacts loopFacts=analyze("A=matrix(0,2,2);i=1;while(i<3){A=A+1;i=i+1;}print(sum(A));");
		MemoProgramFacts.CarriedValue carried=loopFacts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		LoopDecision backedge=new LoopDecision(loopFacts.loop(0).id(),List.of());
		Map<LoopDecision,LoopPhase> sharedLoops=Map.of(backedge,LoopPhase.BACKEDGE);
		MemoBindingResolver loopResolver=new MemoBindingResolver(loopFacts);
		List<OriginKind> shallow=new ArrayList<>(),deep=new ArrayList<>();
		var shallowResult=loopResolver.visitJoint(List.of(carried.headBindingId()),
			new EvaluationContext(Map.of(),sharedLoops,MemoBindingResolver.NO_SELECTION,1),environment -> {
				shallow.add(environment.origins().get(0).kind()); return true;
			});
		var deepResult=loopResolver.visitJoint(List.of(carried.headBindingId()),
			new EvaluationContext(Map.of(),sharedLoops,MemoBindingResolver.NO_SELECTION,64),environment -> {
				deep.add(environment.origins().get(0).kind()); return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.UNRESOLVED,shallowResult.status());
		Assert.assertNotEquals(shallow,deep);
		Assert.assertEquals(OriginKind.UNRESOLVED,shallow.get(0));

		MemoProgramFacts selectedFacts=analyze("A=matrix(1,2,2);B=abs(A);print(sum(B));");
		MemoProgramFacts.Occurrence abs=selectedFacts.occurrences().stream()
			.filter(o -> o.hop() instanceof UnaryOp).findFirst().orElseThrow();
		AtomicInteger selected=new AtomicInteger(3);
		MemoBindingResolver.SelectionLookup live=new MemoBindingResolver.SelectionLookup() {
			@Override public int selectedRule(int owner) { return selected.get(); }
			@Override public MemoPartition selectedLayout(int owner) { return MemoPartition.ROW; }
		};
		EvaluationContext selectedContext=new EvaluationContext(Map.of(),Map.of(),live,64);
		MemoBindingResolver selectedResolver=new MemoBindingResolver(selectedFacts);
		List<Integer> rules=new ArrayList<>();
		for(int expected : List.of(3,7)) {
			selected.set(expected);
			selectedResolver.visitJoint(List.of(selectedFacts.valueBinding(abs.id())),selectedContext,environment -> {
				rules.add(environment.origins().get(0).selectedRule()); return true;
			});
		}
		Assert.assertEquals(List.of(3,7),rules);
		System.out.println("RESOLVER_COW_STATE_SHA256="+sha256(shallowResult.status()+":"+shallow+":"
			+deepResult.status()+":"+deep+":"+rules));
	}

	@Test public void unbranchedSnapshotMatchesBaselineRebuildOrderForCollidingKeys() throws Exception {
		StringBuilder script=new StringBuilder();
		for(int i=0;i<6;i++) script.append("p").append(i)
			.append("=as.scalar(rand(rows=1,cols=1));if(p").append(i).append(">0.5){print(")
			.append(i).append(");}");
		MemoProgramFacts facts=analyze(script.toString());
		Map<MemoBindingResolver.GuardDecision,Boolean> insertion=new java.util.LinkedHashMap<>();
		for(int i=0;i<6;i++) {
			var key=new MemoBindingResolver.GuardDecision(i,List.of(1008-31*i));
			Assert.assertEquals(2000,key.hashCode());
			insertion.put(key,i<4);
		}
		EvaluationContext context=new EvaluationContext(insertion,Map.of(),MemoBindingResolver.NO_SELECTION,64);
		Map<MemoBindingResolver.GuardDecision,Boolean> expected=
			Map.copyOf(new java.util.LinkedHashMap<>(context.guards()));
		List<Map<MemoBindingResolver.GuardDecision,Boolean>> actual=new ArrayList<>();
		new MemoBindingResolver(facts).visitJoint(List.of(),context,environment -> {
			actual.add(environment.guards()); return true;
		});
		Assert.assertEquals(1,actual.size());
		Assert.assertEquals("copy-on-write must preserve the former unsorted snapshot rebuild order",
			new ArrayList<>(expected.keySet()),new ArrayList<>(actual.get(0).keySet()));
		Assert.assertEquals("ordered probability must retain exact binary64 bits",
			Double.doubleToRawLongBits(orderedProbability(expected)),
			Double.doubleToRawLongBits(orderedProbability(actual.get(0))));
	}

	private static double orderedProbability(Map<MemoBindingResolver.GuardDecision,Boolean> guards) {
		double result=1;
		for(boolean value:guards.values()) result*=value ? 0.37 : 0.63;
		return result;
	}

	private static String signature(List<MemoBindingResolver.ResolvedEnvironment> environments) {
		StringBuilder result=new StringBuilder();
		for(var environment:environments) result.append(environment.guards()).append('|')
			.append(environment.loops()).append('|').append(environment.iterationPhases()).append('|')
			.append(environment.cardinalities()).append('|')
			.append(environment.origins().stream().map(origin -> origin.kind()+":"+origin.referenceId()+":"
				+origin.compiledOwnerId()+":"+origin.boundaryPhase()+":"+origin.callFrame()).toList()).append('\n');
		return result.toString();
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.memo;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeDescriptorContextTest {
	@Test
	public void callReturnGuardLoopHeadAndDepthContextsMatchLegacyViewKeys() throws Exception {
		DMLProgram program=parse("f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
			+"if(q){Y=X;}}p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=matrix(1,rows=8,cols=1);R=f(p,A);B=abs(R);i=1;"
			+"while(i<=3){C=abs(A);A=matrix(1,rows=8,cols=1);i=i+1;}print(sum(B)+sum(C));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,CanonicalWorkerPool.of(List.of(
			InetSocketAddress.createUnresolved("127.0.0.1",19001),
			InetSocketAddress.createUnresolved("127.0.0.1",19002))));
		MemoShapes shapes=MemoShapes.analyze(facts,sources);
		List<MemoProgramFacts.Occurrence> absolute=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS).toList();
		Assert.assertEquals(2,absolute.size());
		for(MemoProgramFacts.Occurrence occurrence:absolute) {
			List<Integer> frame=facts.occurrenceContexts(occurrence.id()).get(0).callFrame();
			for(int maxDepth:new int[] {1,4096}) {
				assertParity(shapes,occurrence,new EvaluationContext(Map.of(),Map.of(),
					MemoBindingResolver.NO_SELECTION,maxDepth,frame,Map.of(),Map.of()));
				assertParity(shapes,occurrence,new EvaluationContext(Map.of(),Map.of(),
					boundHeads(),maxDepth,frame,Map.of(),Map.of()));
			}
			if(!facts.guards().isEmpty()) for(boolean value:new boolean[] {false,true}) {
				GuardDecision guard=facts.guardDecision(facts.guards().get(0).id(),frame);
				Map<GuardDecision,Boolean> guards=new LinkedHashMap<>(); guards.put(guard,value);
				assertParity(shapes,occurrence,new EvaluationContext(guards,Map.of(),
					MemoBindingResolver.NO_SELECTION,4096,frame,Map.of(),Map.of()));
			}
		}
	}

	private static SelectionLookup boundHeads() {
		return new SelectionLookup() {
			@Override public boolean headIsBound(int loopId,int slot) { return true; }
		};
	}

	private static void assertParity(MemoShapes shapes,MemoProgramFacts.Occurrence occurrence,
		EvaluationContext context) {
		MemoNativeRules.RequestFactory directFactory=new MemoNativeRules.RequestFactory();
		MemoNativeRules.Request direct=directFactory.prepare(shapes.view(occurrence.compiledOwnerId()),2,
			shapes.nativeDescriptorAt(occurrence.id(),context));
		var view=shapes.viewAt(occurrence.id(),context);
		MemoNativeRules.Request legacy=new MemoNativeRules.RequestFactory().prepare(view,2,
			MemoShapes.hintForView(view));
		Assert.assertNotNull(direct.key());
		Assert.assertEquals(legacy.key(),direct.key());
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}

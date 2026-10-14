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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoEmptyHeadBitsCandidateTest {
	@Test
	public void noSelectionAndMutableFalseTrueFalseRetainExactKeys() throws Exception {
		Fixture fixture=fixture();
		MutableHeads heads=new MutableHeads();
		Object noSelection=semanticKey(fixture.shapes(),EvaluationContext.empty());
		Object firstFalse=semanticKey(fixture.shapes(),context(heads));
		Assert.assertEquals(noSelection,firstFalse);

		heads.bound=Set.of(slot(fixture.first()));
		Object whenTrue=semanticKey(fixture.shapes(),context(heads));
		Assert.assertNotEquals(firstFalse,whenTrue);

		heads.bound=Set.of();
		Object secondFalse=semanticKey(fixture.shapes(),context(heads));
		Assert.assertEquals(firstFalse,secondFalse);
	}

	@Test
	public void multipleTrueHeadsAndDependencyIndexPreserveProjection() throws Exception {
		Fixture fixture=fixture();
		MutableHeads heads=new MutableHeads();
		heads.bound=Set.of(slot(fixture.first()),slot(fixture.second()));
		Object both=semanticKey(fixture.shapes(),context(heads));
		heads.bound=Set.of(slot(fixture.first()));
		Object firstOnly=semanticKey(fixture.shapes(),context(heads));
		Assert.assertNotEquals(both,firstOnly);

		heads.bound=Set.of();
		Object projectedEmpty=fixture.shapes().projectedShapeKeyForOccurrence(fixture.consumer(),context(heads));
		heads.bound=Set.of(slot(fixture.second()));
		Object unrelated=fixture.shapes().projectedShapeKeyForOccurrence(fixture.consumer(),context(heads));
		heads.bound=Set.of(slot(fixture.first()));
		Object relevant=fixture.shapes().projectedShapeKeyForOccurrence(fixture.consumer(),context(heads));
		Assert.assertEquals(projectedEmpty,unrelated);
		Assert.assertNotEquals(projectedEmpty,relevant);
	}

	private static Object semanticKey(MemoShapes shapes,EvaluationContext context) throws Exception {
		Method method=MemoShapes.class.getDeclaredMethod("semanticContext",EvaluationContext.class);
		method.setAccessible(true);
		return method.invoke(shapes,context);
	}

	private static EvaluationContext context(SelectionLookup lookup) {
		return new EvaluationContext(Map.of(),Map.of(),lookup,4096,List.of(),Map.of(),Map.of());
	}

	private static Fixture fixture() throws Exception {
		String script="A=matrix(1,2,3);for(i in 1:2){A=abs(A);}"+
			"B=matrix(1,7,9);for(j in 1:2){B=abs(B);}C=abs(A);print(sum(C));";
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var first=facts.loop(0).carriedValues().get(0);
		var second=facts.loop(1).carriedValues().get(0);
		int consumer=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.filter(value -> value.context().contains("main") && value.id()>0)
			.reduce((left,right) -> right).orElseThrow().id();
		return new Fixture(shapes,new Head(facts.loop(0).id(),first.slot()),
			new Head(facts.loop(1).id(),second.slot()),consumer);
	}

	private static String slot(Head head) { return head.loop()+":"+head.slot(); }
	private record Head(int loop,int slot) { }
	private record Fixture(MemoShapes shapes,Head first,Head second,int consumer) { }
	private static final class MutableHeads implements SelectionLookup {
		private Set<String> bound=Set.of();
		@Override public boolean headIsBound(int loop,int slot) { return bound.contains(loop+":"+slot); }
	}
}

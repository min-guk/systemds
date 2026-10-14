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

import java.lang.reflect.*;
import java.util.*;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.*;
import org.apache.sysds.parser.*;
import org.junit.Assert;
import org.junit.Test;

public class MemoHeadMembershipSnapshotTest {

	@Test public void livePrivateMapsFramesForeignOwnerAndFiltering() throws Exception {
		MemoShapes shapes=fixture();
		MemoShapes foreign=fixture();
		Object evaluation=newEvaluation(foreign);
		Map<Object,Object> heads=headMap(evaluation);
		SelectionLookup lookup=newSelection(evaluation,MemoBindingResolver.NO_SELECTION);
		Assert.assertTrue(bits(shapes,lookup,null).isEmpty());
		heads.put(headKey(0,0,List.of()),null);
		BitSet first=bits(shapes,lookup,null);
		Assert.assertFalse(first.isEmpty());
		heads.put(headKey(0,0,List.of(7)),null);
		Assert.assertEquals(first,bits(shapes,lookup,null));
		heads.put(headKey(1,0,List.of()),null);
		BitSet both=bits(shapes,lookup,null);
		Assert.assertTrue(both.cardinality()>first.cardinality());
		heads.put(headKey(999,999,List.of()),null);
		Assert.assertEquals(both,bits(shapes,lookup,null));
		Object dep=dependencies(Set.of(identity(0,0)));
		Assert.assertEquals(first,bits(shapes,lookup,dep));
		Assert.assertTrue(bits(shapes,lookup,dependencies(Set.of())).isEmpty());
		heads.clear();
		Assert.assertTrue(bits(shapes,lookup,null).isEmpty());
		Assert.assertFalse("prior snapshot must remain immutable",first.isEmpty());
	}

	@Test public void duplicateOrdinalsAreAllPreserved() throws Exception {
		Object a=identity(0,3),b=identity(1,4);
		Method index=MemoShapes.class.getDeclaredMethod("indexHeadOrdinals",List.class);
		index.setAccessible(true);
		Map<?,?> values=(Map<?,?>)index.invoke(null,List.of(a,b,a));
		Assert.assertArrayEquals(new int[]{0,2},(int[])values.get(a));
		Assert.assertArrayEquals(new int[]{1},(int[])values.get(b));
	}

	@Test public void arbitraryDelegateRetainsCallsAndStatefulOrder() throws Exception {
		MemoShapes shapes=fixture();
		List<String> calls=new ArrayList<>();
		SelectionLookup delegate=new SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) {
				calls.add(loop+":"+slot);
				return calls.size()%2==1;
			}
		};
		Object evaluation=newEvaluation(shapes);
		headMap(evaluation).put(headKey(0,0,List.of()),null);
		SelectionLookup lookup=newSelection(evaluation,delegate);
		BitSet original=referenceBits(shapes,lookup); List<String> expected=List.copyOf(calls);
		calls.clear();
		Assert.assertEquals(original,bits(shapes,lookup,null));
		Assert.assertEquals(expected,calls);
	}

	@Test public void nestedPrivatePrefixKeepsStatefulTerminalAndPublicReentry() throws Exception {
		MemoShapes shapes=fixture();
		Field factsField=MemoShapes.class.getDeclaredField("facts");factsField.setAccessible(true);
		MemoProgramFacts facts=(MemoProgramFacts)factsField.get(shapes);
		var carried=facts.loop(0).carriedValues().stream().filter(v->v.variable().equals("A")).findFirst().orElseThrow();
		LoopDecision loopDecision=new LoopDecision(0,List.of());
		EvaluationContext nestedContext=new EvaluationContext(Map.of(),Map.of(loopDecision,LoopPhase.BACKEDGE),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),
			Map.of(loopDecision,MemoProgramFacts.IterationPhase.MIDDLE),Map.of());
		List<String> calls=new ArrayList<>();
		SelectionLookup terminal=new SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) {
				calls.add(loop+":"+slot);
				MemoShapes.Shape reentered=shapes.bindingShape(carried.headBindingId(),nestedContext);
				Assert.assertEquals(2L,reentered.rows());
				Assert.assertEquals(3L,reentered.columns());
				return calls.size()%2==1;
			}
		};
		Object first=newEvaluation(shapes),second=newEvaluation(fixture());
		headMap(first).put(headKey(0,0,List.of()),null);
		headMap(second).put(headKey(0,0,List.of(3)),null);
		SelectionLookup lookup=newSelection(first,newSelection(second,terminal));
		BitSet reference=referenceBits(shapes,lookup); List<String> expected=List.copyOf(calls);
		calls.clear();
		Assert.assertEquals(reference,bits(shapes,lookup,null));Assert.assertEquals(expected,calls);
	}

	@Test public void terminalExceptionRetainsExactPositionAndCalls() throws Exception {
		MemoShapes shapes=fixture(); List<String> calls=new ArrayList<>();
		SelectionLookup terminal=new SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) {
				calls.add(loop+":"+slot);throw new IllegalStateException("terminal");
			}
		};
		Object evaluation=newEvaluation(shapes); headMap(evaluation).put(headKey(0,0,List.of()),null);
		SelectionLookup lookup=newSelection(evaluation,terminal);
		IllegalStateException old=Assert.assertThrows(IllegalStateException.class,()->referenceBits(shapes,lookup));
		List<String> expected=List.copyOf(calls); calls.clear();
		InvocationTargetException actual=Assert.assertThrows(InvocationTargetException.class,()->bits(shapes,lookup,null));
		Assert.assertEquals(old.getClass(),actual.getCause().getClass());Assert.assertEquals(expected,calls);
	}


	private static BitSet referenceBits(MemoShapes shapes,SelectionLookup lookup) throws Exception {
		Field field=MemoShapes.class.getDeclaredField("selectionSensitiveHeads"); field.setAccessible(true);
		List<?> heads=(List<?>)field.get(shapes); BitSet result=new BitSet();
		for(int index=0;index<heads.size();index++) {
			Object head=heads.get(index);
			Method loop=head.getClass().getDeclaredMethod("loopId"),slot=head.getClass().getDeclaredMethod("slot");
			loop.setAccessible(true); slot.setAccessible(true);
			if(lookup.headIsBound((int)loop.invoke(head),(int)slot.invoke(head))) result.set(index);
		}
		return result;
	}
	private static MemoShapes fixture() throws Exception {
		String script="A=matrix(1,2,3);for(i in 1:2){A=abs(A);}B=matrix(1,7,9);for(j in 1:2){B=abs(B);}C=abs(A);print(sum(C));";
		DMLProgram p=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator t=new DMLTranslator(p);t.liveVariableAnalysis(p);t.validateParseTree(p);t.constructHops(p);
		MemoProgramFacts f=MemoProgramFacts.analyze(p); return MemoShapes.analyze(f,MemoSources.acquire(f,null));
	}
	private static Object newEvaluation(MemoShapes shapes) throws Exception {
		Class<?> c=Class.forName(MemoShapes.class.getName()+"$PhaseEvaluation"); Constructor<?> ctor=c.getDeclaredConstructor(MemoShapes.class);ctor.setAccessible(true);return ctor.newInstance(shapes);
	}
	private static SelectionLookup newSelection(Object eval,SelectionLookup delegate) throws Exception {
		Class<?> c=Class.forName(MemoShapes.class.getName()+"$PhaseEvaluation$HeadSelection");Constructor<?> ctor=c.getDeclaredConstructor(eval.getClass(),SelectionLookup.class);ctor.setAccessible(true);return (SelectionLookup)ctor.newInstance(eval,delegate);
	}
	@SuppressWarnings("unchecked") private static Map<Object,Object> headMap(Object eval) throws Exception {Field f=eval.getClass().getDeclaredField("heads");f.setAccessible(true);return (Map<Object,Object>)f.get(eval);}
	private static Object headKey(int loop,int slot,List<Integer> frame) throws Exception {Class<?> c=Class.forName(MemoShapes.class.getName()+"$LoopSlotKey");Constructor<?> ctor=c.getDeclaredConstructor(LoopDecision.class,int.class);ctor.setAccessible(true);return ctor.newInstance(new LoopDecision(loop,frame),slot);}
	private static Object identity(int loop,int slot) throws Exception {Class<?> c=Class.forName(MemoShapes.class.getName()+"$LoopSlotIdentity");Constructor<?> ctor=c.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);return ctor.newInstance(loop,slot);}
	private static Object dependencies(Set<?> heads) throws Exception {Class<?> c=Class.forName(MemoShapes.class.getName()+"$ShapeDependencies");Constructor<?> ctor=c.getDeclaredConstructor(Set.class,Set.class,Set.class);ctor.setAccessible(true);return ctor.newInstance(Set.of(),Set.of(),heads);}
	private static BitSet bits(MemoShapes shapes,SelectionLookup lookup,Object dependencies) throws Exception {
		Class<?> c=Class.forName(MemoShapes.class.getName()+"$ShapeDependencies");Method method=MemoShapes.class.getDeclaredMethod("headBindings",EvaluationContext.class,c);method.setAccessible(true);return (BitSet)method.invoke(shapes,new EvaluationContext(Map.of(),Map.of(),lookup,4096),dependencies);
	}
}

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
import org.junit.Assert;
import org.junit.Test;
public class MemoShapeKeyAtomsTest {
 @Test public void exactProvenanceAndTruthRemainDistinctUnderCanonicalAlias()throws Exception {
  MemoShapes shapes=shapes();
  var a=new MemoBindingResolver.GuardDecision(1,List.of(2),77,List.of(3));
  var alias=new MemoBindingResolver.GuardDecision(5,List.of(6),77,List.of(3));
  Assert.assertEquals("resolver canonical alias",a,alias);
  Object yes=atom(shapes,a,true),no=atom(shapes,a,false);
  Assert.assertSame(yes,atom(shapes,a,true));Assert.assertSame(no,atom(shapes,a,false));
  Assert.assertNotEquals(yes,no);Assert.assertNotEquals(yes,atom(shapes,alias,true));
  var equal=new MemoBindingResolver.GuardDecision(1,List.of(2),77,List.of(3));
  Assert.assertEquals(yes,atom(shapes,equal,true));
  Assert.assertEquals(yes.hashCode(),atom(shapes,equal,true).hashCode());
 }
 @Test public void boundedPoolFallbackPreservesExactEqualityAndFrozenFrames()throws Exception {
  MemoShapes shapes=shapes();
  for(int i=0;i<4200;i++)atom(shapes,new MemoBindingResolver.GuardDecision(i,List.of(i)),true);
  Field f=MemoShapes.class.getDeclaredField("guardAssignments");f.setAccessible(true);
  Assert.assertEquals(4096,((Map<?,?>)f.get(shapes)).size());
  ArrayList<Integer> frame=new ArrayList<>(List.of(7,8));
  var decision=new MemoBindingResolver.GuardDecision(50000,frame,60000,frame);
  Object first=atom(shapes,decision,true);int hash=first.hashCode();frame.set(0,9);
  Object second=atom(shapes,decision,true);
  Assert.assertNotSame(first,second);Assert.assertEquals(first,second);Assert.assertEquals(hash,second.hashCode());
  Assert.assertEquals(first,atom(shapes,new MemoBindingResolver.GuardDecision(50000,List.of(7,8),60000,List.of(7,8)),true));
 }
 private static Object atom(MemoShapes shapes,MemoBindingResolver.GuardDecision decision,boolean value)throws Exception {
  Method m=MemoShapes.class.getDeclaredMethod("guardAssignment",MemoBindingResolver.GuardDecision.class,boolean.class);
  m.setAccessible(true);return m.invoke(shapes,decision,value);
 }
 private static MemoShapes shapes()throws Exception {
  Method m=MemoMovementReuseTest.class.getDeclaredMethod("fixture",String.class);m.setAccessible(true);
  Object f=m.invoke(null,"A=abs(P);print(sum(A));");
  Method u=f.getClass().getDeclaredMethod("universe");u.setAccessible(true);
  return ((MemoRuleBuilder.Result)u.invoke(f)).shapes();
 }
}

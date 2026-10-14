/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class MemoMovementCostStateTest {
 @Test public void guardedMovementClassesPreserveEveryExactTuple() throws Exception {
  verify("p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=P;}else{A=matrix(2,rows=4,cols=2);}B=abs(A);C=B+1;print(sum(C));");
 }
 @Test public void loopHeadsAndMultiReturnSlotsPreserveEveryExactTuple() throws Exception {
  verify("X=matrix(1,rows=4,cols=2);[Q,R]=qr(X);A=P;for(i in 1:2){A=abs(A);}C=abs(R);print(sum(A)+sum(Q)+sum(C));");
 }
 @Test public void conditionalMissingReturnPreservesEveryExactTuple() throws Exception {
  verify("f=function(boolean q,matrix[double] X) return(matrix[double] Y,matrix[double] Z){Y=abs(X);if(q){Z=X+1;}}p=as.scalar(rand(rows=1,cols=1))>0.5;[A,B]=f(p,P);C=abs(A);print(sum(C));");
 }
 @SuppressWarnings("unchecked") private static void verify(String body) throws Exception {
  Method fixture=MemoMovementReuseTest.class.getDeclaredMethod("fixture",String.class);fixture.setAccessible(true);
  Object f=fixture.invoke(null,body);
  MemoProgramFacts facts=(MemoProgramFacts)access(f,"facts");
  MemoSources sources=(MemoSources)access(f,"sources");
  MemoRuleBuilder.Result universe=(MemoRuleBuilder.Result)access(f,"universe");
  MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults(),true);
  List<MemoOptimizer.CostTerm> terms=(List<MemoOptimizer.CostTerm>)field(MemoProgramModel.class,"costs").get(model);
  int[] sizes=(int[])field(MemoProgramModel.class,"sizes").get(model);
  int annotated=0,collapsed=0;
  for(MemoOptimizer.CostTerm term:terms) {
   int[][] classes=(int[][])field(MemoOptimizer.CostTerm.class,"costClasses").get(term);
   if(classes==null) continue;
   annotated++;
   int[] owners=term.owners(); int[] values=new int[owners.length];long product=1;
   for(int position=0;position<owners.length;position++) {
    Assert.assertEquals(sizes[owners[position]],classes[position].length);
    product=Math.multiplyExact(product,classes[position].length);
    if(java.util.Arrays.stream(classes[position]).distinct().count()<classes[position].length) collapsed++;
   }
   Assert.assertTrue("bounded exhaustive term "+product,product<=100000);
   Map<List<Integer>,BigDecimal> exact=new HashMap<>();
   for(long ordinal=0;ordinal<product;ordinal++) {
    List<Integer> key=new ArrayList<>();
    for(int position=0;position<owners.length;position++) key.add(classes[position][values[position]]);
    BigDecimal value=term.evaluateExact(values),previous=exact.putIfAbsent(key,value);
    if(previous!=null) Assert.assertEquals("same observed raw layouts must preserve exact cost bits",previous,value);
    for(int position=values.length-1;position>=0;position--) {
     if(++values[position]<sizes[owners[position]])break;
     values[position]=0;
    }
   }
  }
  Assert.assertTrue("fixture has proven complete factors",annotated>0);
  System.out.println("MOVEMENT_COST_STATES annotated="+annotated+" collapsedOwners="+collapsed);
 }
 private static Object access(Object value,String name) throws Exception {
  Method method=value.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(value);
 }
 private static Field field(Class<?> type,String name) throws Exception {
  Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
 }
}

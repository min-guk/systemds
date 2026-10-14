/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeCostVectorTest {
 @Test public void repeatedPhasesPreserveEachPrimitiveAndFullOptimum() throws Exception {
  verify("A=P;for(i in 1:4){B=abs(A);A=B+1;}print(sum(A));",true);
 }
 @Test public void guardedShapesAndFrequenciesStayDistinct() throws Exception {
  verify("p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=P;}else{A=matrix(0,rows=8,cols=2);}"+
   "A=abs(A);B=t(A)%*%A;print(sum(B));",false);
 }
 @Test public void irregularAndMultiOutputKernelsKeepReferenceSemantics() throws Exception {
  verify("X=matrix(1,rows=4,cols=2);[Q,R]=qr(X);A=P;for(i in 1:3){A=abs(A);}print(sum(A)+sum(Q)+sum(R));",true);
 }
 @SuppressWarnings("unchecked") private static void verify(String body,boolean expectHits)throws Exception {
  Method fixture=MemoMovementReuseTest.class.getDeclaredMethod("fixture",String.class);fixture.setAccessible(true);
  Object f=fixture.invoke(null,body);
  MemoProgramFacts facts=(MemoProgramFacts)access(f,"facts");
  MemoSources sources=(MemoSources)access(f,"sources");
  MemoRuleBuilder.Result universe=(MemoRuleBuilder.Result)access(f,"universe");
  MemoProgramModel baseline=new MemoProgramModel(facts,sources,universe,new MemoProgramModel.Profile(10,.37),true,0);
  MemoProgramModel cached=new MemoProgramModel(facts,sources,universe,new MemoProgramModel.Profile(10,.37),true,4096);
  double[][] before=(double[][])field("nativeCosts").get(baseline),after=(double[][])field("nativeCosts").get(cached);
  Assert.assertEquals(before.length,after.length);
  for(int owner=0;owner<before.length;owner++) assertBits(before[owner],after[owner]);
  int count=field("nativeCostTermCount").getInt(baseline);
  Assert.assertEquals(count,field("nativeCostTermCount").getInt(cached));
  List<MemoOptimizer.CostTerm> left=(List<MemoOptimizer.CostTerm>)field("costs").get(baseline);
  List<MemoOptimizer.CostTerm> right=(List<MemoOptimizer.CostTerm>)field("costs").get(cached);
  int[] sizes=(int[])field("sizes").get(baseline);
  for(int term=0;term<count;term++) {
   Assert.assertArrayEquals(left.get(term).owners(),right.get(term).owners());
   int owner=left.get(term).owners()[0];
   for(int ordinal=0;ordinal<sizes[owner];ordinal++)
    Assert.assertEquals(left.get(term).evaluateExact(new int[]{ordinal}),right.get(term).evaluateExact(new int[]{ordinal}));
  }
  Assert.assertEquals(baseline.costProjectionStatistics(),cached.costProjectionStatistics());
  var a=baseline.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
  var b=cached.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
  Assert.assertEquals(a.status(),b.status());Assert.assertEquals(a.cost(),b.cost());
  Assert.assertArrayEquals(a.selectedRules(),b.selectedRules());
  if(expectHits) Assert.assertTrue("fixture must exercise native reuse",field("nativeVectorHits").getLong(cached)>0);
 }
 private static void assertBits(double[] a,double[] b) {
  Assert.assertEquals(a.length,b.length);
  for(int i=0;i<a.length;i++) Assert.assertEquals(Double.doubleToLongBits(a[i]),Double.doubleToLongBits(b[i]));
 }
 private static Object access(Object value,String name)throws Exception {
  Method m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(value);
 }
 private static Field field(String name)throws Exception {
  Field f=MemoProgramModel.class.getDeclaredField(name);f.setAccessible(true);return f;
 }
}

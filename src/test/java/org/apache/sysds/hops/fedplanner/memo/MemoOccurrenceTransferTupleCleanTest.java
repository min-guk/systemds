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

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.memo.MemoShapes.Knowledge;
import org.apache.sysds.hops.fedplanner.memo.MemoShapes.Shape;
import org.junit.Assert;
import org.junit.Test;

public class MemoOccurrenceTransferTupleCleanTest {
	@Test public void privateTupleKeyRetainsTheLegacyRecordHashAndValueSemantics() throws Exception {
		Shape matrix=new Shape(DataType.MATRIX,ValueType.FP64,8,4,17,Knowledge.EXACT);
		Shape scalar=new Shape(DataType.SCALAR,ValueType.FP64,-1,-1,-1,Knowledge.EXACT);
		Shape[] inputs={matrix,null,scalar,matrix};
		Object[] replacements={replacement("NONE",0,0),replacement("INT64",Long.MIN_VALUE,0),
			replacement("FP64",0,Double.longBitsToDouble(0x7ff8000000000001L)),
			replacement("FP64",0,-0.0d)};
		Object key=key(7,inputs,replacements);
		int expected=31*(31*Integer.hashCode(7)+Arrays.asList(inputs).hashCode())
			+Arrays.asList(replacements).hashCode();
		Assert.assertEquals(expected,key.hashCode());
		Assert.assertEquals(key,key(7,inputs.clone(),replacements.clone()));
		Assert.assertNotEquals(key,key(8,inputs.clone(),replacements.clone()));

		Object[] canonicalNan=replacements.clone();
		canonicalNan[2]=replacement("FP64",0,Double.NaN);
		Assert.assertEquals("ScalarReplacement keeps record NaN equality",
			key(7,inputs,replacements),key(7,inputs,canonicalNan));
		Object[] positiveZero=replacements.clone();
		positiveZero[3]=replacement("FP64",0,0.0d);
		Assert.assertNotEquals("ScalarReplacement keeps signed zero distinct",
			key(7,inputs,replacements),key(7,inputs,positiveZero));
		Assert.assertNotEquals(key,key(7,new Shape[] {null,matrix,scalar,matrix},replacements.clone()));
		Shape differentNnz=new Shape(DataType.MATRIX,ValueType.FP64,8,4,18,Knowledge.EXACT);
		Assert.assertNotEquals(key,key(7,new Shape[] {differentNnz,null,scalar,matrix},replacements.clone()));
		Shape unknown=new Shape(DataType.UNKNOWN,ValueType.UNKNOWN,-1,-1,-1,Knowledge.UNKNOWN);
		Assert.assertNotEquals(key(7,new Shape[] {null},new Object[] {replacement("NONE",0,0)}),
			key(7,new Shape[] {unknown},new Object[] {replacement("NONE",0,0)}));
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	private static Object replacement(String kind,long longValue,double doubleValue) throws Exception {
		Class<?> kindClass=Class.forName(MemoShapes.class.getName()+"$ScalarReplacementKind");
		Class<?> replacementClass=Class.forName(MemoShapes.class.getName()+"$ScalarReplacement");
		Constructor<?> constructor=replacementClass.getDeclaredConstructor(kindClass,long.class,double.class);
		constructor.setAccessible(true);
		return constructor.newInstance(Enum.valueOf((Class<? extends Enum>)kindClass,kind),longValue,doubleValue);
	}

	private static Object key(int occurrence,Shape[] inputs,Object[] replacements) throws Exception {
		Class<?> keyClass=Class.forName(MemoShapes.class.getName()+"$OccurrenceTransferKey");
		Constructor<?> constructor=keyClass.getDeclaredConstructor(int.class,List.class,List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(occurrence,Arrays.asList(inputs.clone()),Arrays.asList(replacements.clone()));
	}
}

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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.junit.Assert;
import org.junit.Test;

public class MemoMovementMeasurementReuseTest {
	@Test public void oneUploadMeasurementPricesEveryFederatedTargetWithExactBits() {
		Hop source=source(8,4);
		for(int workers:new int[] {1,3}) {
			var measured=MemoCostModel.movementMeasurement(source,MemoPartition.LOCAL,MemoPartition.ROW,workers);
			for(MemoPartition target:new MemoPartition[] {MemoPartition.ROW,MemoPartition.COL,MemoPartition.REP})
				assertBits(MemoCostModel.movementCost(source,MemoPartition.LOCAL,target,workers,1),
					MemoCostModel.movementCost(measured,MemoPartition.LOCAL,target,workers,1));
		}
	}

	@Test public void oneDownloadMeasurementPricesEveryFederatedSourceWithExactBits() {
		Hop source=source(8,4);
		for(int workers:new int[] {1,3}) {
			var measured=MemoCostModel.movementMeasurement(source,MemoPartition.ROW,MemoPartition.LOCAL,workers);
			for(MemoPartition from:new MemoPartition[] {MemoPartition.ROW,MemoPartition.COL,MemoPartition.REP})
				assertBits(MemoCostModel.movementCost(source,from,MemoPartition.LOCAL,workers,1),
					MemoCostModel.movementCost(measured,from,MemoPartition.LOCAL,workers,1));
		}
	}

	@Test public void frameMeasurementsPreserveUploadAndDownloadBitsAtW1AndW3() {
		Hop source=new DataOp("F",DataType.FRAME,ValueType.STRING,OpOpData.TRANSIENTREAD,
			"F",8,4,-1,1024);
		for(int workers:new int[] {1,3}) {
			var upload=MemoCostModel.movementMeasurement(source,MemoPartition.LOCAL,MemoPartition.COL,workers);
			assertBits(MemoCostModel.movementCost(source,MemoPartition.LOCAL,MemoPartition.COL,workers,.37),
				MemoCostModel.movementCost(upload,MemoPartition.LOCAL,MemoPartition.COL,workers,.37));
			var download=MemoCostModel.movementMeasurement(source,MemoPartition.REP,MemoPartition.LOCAL,workers);
			assertBits(MemoCostModel.movementCost(source,MemoPartition.REP,MemoPartition.LOCAL,workers,.37),
				MemoCostModel.movementCost(download,MemoPartition.REP,MemoPartition.LOCAL,workers,.37));
		}
	}

	@Test public void retainedReplacementCarriesMeasurementForAnotherTarget() throws Exception {
		Class<?> primitiveClass=Class.forName(MemoProgramModel.class.getName()+"$MovementPrimitive");
		Constructor<?> constructor=primitiveClass.getDeclaredConstructor(
			ResolvedEnvironment.class,double.class,Map.class,Map.class);
		constructor.setAccessible(true);
		ResolvedEnvironment empty=new ResolvedEnvironment(List.of(),Map.of(),Map.of());
		Object initial=constructor.newInstance(empty,1d,Map.of(),Map.of());
		Object retained=constructor.newInstance(empty,1d,Map.of(),Map.of());
		Hop source=source(8,4);
		var measurement=MemoCostModel.movementMeasurement(
			source,MemoPartition.LOCAL,MemoPartition.ROW,3);
		Field upload=primitiveClass.getDeclaredField("uploadMeasurement");
		upload.setAccessible(true);
		upload.set(initial,measurement);
		Method copy=primitiveClass.getDeclaredMethod("copyMeasurementsFrom",primitiveClass);
		copy.setAccessible(true);
		copy.invoke(retained,initial);
		var copied=(MemoCostModel.MovementMeasurement)upload.get(retained);
		Assert.assertSame("retained primitive must reuse the immutable measurement",measurement,copied);
		assertBits(MemoCostModel.movementCost(source,MemoPartition.LOCAL,MemoPartition.COL,3,1),
			MemoCostModel.movementCost(copied,MemoPartition.LOCAL,MemoPartition.COL,3,1));
	}

	@Test public void frequencyAndDirectSemanticsRemainInSharedPricingPath() {
		Hop source=source(8,4);
		var upload=MemoCostModel.movementMeasurement(source,MemoPartition.LOCAL,MemoPartition.ROW,3);
		assertBits(MemoCostModel.movementCost(source,MemoPartition.LOCAL,MemoPartition.COL,3,.37),
			MemoCostModel.movementCost(upload,MemoPartition.LOCAL,MemoPartition.COL,3,.37));
		Assert.assertEquals(0,MemoCostModel.movementCost(upload,MemoPartition.LOCAL,MemoPartition.LOCAL,3,7),0);
		Assert.assertEquals(0,MemoCostModel.movementCost(upload,MemoPartition.LOCAL,MemoPartition.COL,3,0),0);
	}

	@Test public void categoryMismatchCannotReuseTheWrongByteEstimate() {
		Hop source=source(8,4);
		var upload=MemoCostModel.movementMeasurement(source,MemoPartition.LOCAL,MemoPartition.ROW,3);
		MemoCostModel.CostException error=Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.movementCost(upload,MemoPartition.ROW,MemoPartition.LOCAL,3,1));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.ILLEGAL_MOVEMENT,error.code());
	}

	@Test public void unknownAndIllegalPayloadFailuresRemainTypedAndRetryable() {
		Hop unknown=source(-1,4);
		MemoCostModel.CostException size=Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.movementMeasurement(unknown,MemoPartition.LOCAL,MemoPartition.ROW,3));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.UNKNOWN_SIZE,size.code());
		unknown.setDim1(8);
		Assert.assertTrue(MemoCostModel.movementMeasurement(
			unknown,MemoPartition.LOCAL,MemoPartition.ROW,3).bytes()>0);

		DataOp scalar=new DataOp("s",DataType.SCALAR,ValueType.FP64,OpOpData.TRANSIENTREAD,
			"s",0,0,-1,-1);
		MemoCostModel.CostException type=Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.movementMeasurement(scalar,MemoPartition.LOCAL,MemoPartition.ROW,3));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.ILLEGAL_MOVEMENT,type.code());
	}

	private static Hop source(long rows,long columns) {
		DataOp input=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,
			"X",rows,columns,rows>0 ? rows*columns : -1,1024);
		UnaryOp result=new UnaryOp("abs",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,input);
		result.setDim1(rows);
		result.setDim2(columns);
		result.setNnz(rows>0 ? rows*columns : -1);
		return result;
	}

	private static void assertBits(double expected,double actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));
	}
}

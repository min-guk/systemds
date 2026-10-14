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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

public class MemoSelectionScratchLifecycleTest {
	@Test
	public void alternatingScopesNestedFallbackAndFailureLeaveNoStaleChoice() throws Exception {
		Scratch scratch=new Scratch(6);
		int[] leased=scratch.acquire(new int[] {1,4},new int[] {7,9});
		Assert.assertArrayEquals(new int[] {-1,7,-1,-1,9,-1},leased);
		int[] nested=scratch.acquire(new int[] {0,5},new int[] {3,8});
		Assert.assertNotSame(leased,nested);
		Assert.assertArrayEquals(new int[] {3,-1,-1,-1,-1,8},nested);
		Assert.assertArrayEquals(new int[] {-1,7,-1,-1,9,-1},leased);
		scratch.release(new int[] {0,5},nested);
		scratch.release(new int[] {1,4},leased);
		InvocationTargetException failure=Assert.assertThrows(InvocationTargetException.class,
			() -> scratch.acquireRaw(new int[] {2,3},new int[] {6}));
		Assert.assertTrue(failure.getCause() instanceof ArrayIndexOutOfBoundsException);
		int[] retry=scratch.acquire(new int[] {0,2},new int[] {4,5});
		Assert.assertSame(leased,retry);
		Assert.assertArrayEquals(new int[] {4,-1,5,-1,-1,-1},retry);
		scratch.release(new int[] {0,2},retry);
		Assert.assertArrayEquals(new int[] {-1,-1,-1,-1,-1,-1},retry);
	}

	@Test
	public void concurrentBusyLeaseUsesIndependentLegacyExpansion() throws Exception {
		Scratch scratch=new Scratch(5);
		int[] leased=scratch.acquire(new int[] {1},new int[] {2});
		AtomicReference<int[]> concurrent=new AtomicReference<>();
		AtomicReference<Throwable> failure=new AtomicReference<>();
		Thread thread=new Thread(() -> {
			try {
				int[] value=scratch.acquire(new int[] {3},new int[] {6});
				concurrent.set(value); scratch.release(new int[] {3},value);
			}
			catch(Throwable thrown) { failure.set(thrown); }
		},"selection-scratch-test");
		thread.start(); thread.join();
		if(failure.get()!=null) throw new AssertionError(failure.get());
		Assert.assertNotSame(leased,concurrent.get());
		Assert.assertArrayEquals(new int[] {-1,-1,-1,6,-1},concurrent.get());
		Assert.assertArrayEquals(new int[] {-1,2,-1,-1,-1},leased);
		scratch.release(new int[] {1},leased);
		Assert.assertArrayEquals(new int[] {-1,-1,-1,-1,-1},leased);
	}

	@Test
	public void evaluatorFailureReleasesTheModelsSuccessfulLease() throws Exception {
		MemoProgramModel model=MemoFederatedModelTest.rewrittenWorkloadModel("logreg");
		Class<?> use=Class.forName(MemoProgramModel.class.getName()+"$Use");
		Class<?> edge=Class.forName(MemoProgramModel.class.getName()+"$MovementEdge");
		Method callback=MemoProgramModel.class.getDeclaredMethod("inputCompatibleExpanded",
			use,edge,int[].class,int[].class);
		callback.setAccessible(true);
		InvocationTargetException failure=Assert.assertThrows(InvocationTargetException.class,
			() -> callback.invoke(model,null,null,new int[] {0},new int[] {0}));
		Assert.assertTrue(failure.getCause() instanceof NullPointerException);

		Field scratchField=MemoProgramModel.class.getDeclaredField("selectionScratch");
		scratchField.setAccessible(true);
		Object modelScratch=scratchField.get(model);
		Field valuesField=modelScratch.getClass().getDeclaredField("values");
		Field leasedField=modelScratch.getClass().getDeclaredField("leased");
		valuesField.setAccessible(true); leasedField.setAccessible(true);
		int[] values=(int[])valuesField.get(modelScratch);
		Assert.assertTrue(Arrays.stream(values).allMatch(value -> value==-1));
		Assert.assertFalse(((AtomicBoolean)leasedField.get(modelScratch)).get());

		Scratch retry=new Scratch(modelScratch);
		int[] selected=retry.acquire(new int[] {0},new int[] {0});
		Assert.assertSame(values,selected);
		retry.release(new int[] {0},selected);
		Assert.assertTrue(Arrays.stream(values).allMatch(value -> value==-1));
		Assert.assertFalse(((AtomicBoolean)leasedField.get(modelScratch)).get());
	}

	private static final class Scratch {
		private final Object instance;
		private final Method acquire;
		private final Method release;
		private Scratch(int width) throws Exception {
			Class<?> type=Class.forName(MemoProgramModel.class.getName()+"$SelectionScratch");
			Constructor<?> constructor=type.getDeclaredConstructor(int.class); constructor.setAccessible(true);
			instance=constructor.newInstance(width);
			acquire=type.getDeclaredMethod("acquire",int[].class,int[].class); acquire.setAccessible(true);
			release=type.getDeclaredMethod("release",int[].class,int[].class); release.setAccessible(true);
		}
		private Scratch(Object instance) throws Exception {
			this.instance=instance;
			Class<?> type=instance.getClass();
			acquire=type.getDeclaredMethod("acquire",int[].class,int[].class); acquire.setAccessible(true);
			release=type.getDeclaredMethod("release",int[].class,int[].class); release.setAccessible(true);
		}
		private int[] acquire(int[] scope,int[] choices) throws Exception {
			return (int[])acquireRaw(scope,choices);
		}
		private Object acquireRaw(int[] scope,int[] choices) throws Exception {
			return acquire.invoke(instance,scope,choices);
		}
		private void release(int[] scope,int[] values) throws Exception {
			release.invoke(instance,scope,values);
		}
	}
}

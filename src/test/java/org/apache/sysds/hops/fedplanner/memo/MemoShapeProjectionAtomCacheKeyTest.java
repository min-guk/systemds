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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class MemoShapeProjectionAtomCacheKeyTest {
	@Test
	public void cachedAtomsStillBuildFreshLiveHeadAndDepthKeys() throws Exception {
		Fixture fixture=fixture();
		MemoShapes shapes=MemoShapes.analyze(fixture.facts,fixture.sources,true,65536);
		int binding=bindingWithHeads(shapes);
		var unbound=context(Map.of(),MemoBindingResolver.NO_SELECTION,7,List.of());
		var bound=context(Map.of(),new MemoBindingResolver.SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) { return true; }
		},7,List.of());
		Object first=shapes.projectedShapeKeyForBinding(binding,unbound);
		Object second=shapes.projectedShapeKeyForBinding(binding,bound);
		Assert.assertNotEquals("live heads are outside the cached atom projection",first,second);
		Assert.assertEquals(2,shapes.projectionAtomStatistics().requests());
		Assert.assertEquals(1,shapes.projectionAtomStatistics().hits());

		Object depth=shapes.projectedShapeKeyForBinding(binding,
			context(Map.of(),MemoBindingResolver.NO_SELECTION,8,List.of()));
		Assert.assertNotEquals("current maxDepth belongs to every fresh whole key",first,depth);
		Assert.assertEquals(2,shapes.projectionAtomStatistics().hits());
	}

	@Test
	public void mapIdentityFrameAndKindRemainDistinctAndCallerMutationIsSnapshotted() throws Exception {
		Fixture fixture=fixture();
		MemoShapes shapes=MemoShapes.analyze(fixture.facts,fixture.sources,true,65536);
		MemoBindingResolver.GuardDecision firstDecision=
			new MemoBindingResolver.GuardDecision(1,List.of(),77,List.of());
		MemoBindingResolver.GuardDecision aliasDecision=
			new MemoBindingResolver.GuardDecision(2,List.of(9),77,List.of());
		Map<MemoBindingResolver.GuardDecision,Boolean> first=Map.of(firstDecision,true);
		Map<MemoBindingResolver.GuardDecision,Boolean> alias=Map.of(aliasDecision,true);
		var firstContext=context(first,MemoBindingResolver.NO_SELECTION,9,List.of());
		var aliasContext=context(alias,MemoBindingResolver.NO_SELECTION,9,List.of());
		Assert.assertEquals(first,alias);
		shapes.projectedShapeKeyForBinding(0,firstContext);
		shapes.projectedShapeKeyForBinding(0,aliasContext);
		Assert.assertEquals("equal canonical guards in distinct maps are not identity hits",
			0,shapes.projectionAtomStatistics().hits());
		shapes.projectedShapeKeyForBinding(0,firstContext.withCallFrame(List.of(3)));
		Assert.assertEquals("frame is part of the projection cache key",0,
			shapes.projectionAtomStatistics().hits());

		Method semantic=MemoShapes.class.getDeclaredMethod("semanticContext",
			MemoBindingResolver.EvaluationContext.class);
		Method shape=MemoShapes.class.getDeclaredMethod("shapeContextKey",
			MemoBindingResolver.EvaluationContext.class);
		semantic.setAccessible(true); shape.setAccessible(true);
		semantic.invoke(shapes,firstContext); shape.invoke(shapes,firstContext);
		Assert.assertEquals("semantic and shape projection kinds cannot share",0,
			shapes.projectionAtomStatistics().hits());

		Map<MemoBindingResolver.GuardDecision,Boolean> mutable=new LinkedHashMap<>();
		mutable.put(firstDecision,true);
		var snapshot=context(mutable,MemoBindingResolver.NO_SELECTION,9,List.of());
		Object before=shapes.projectedShapeKeyForBinding(0,snapshot);
		mutable.clear(); mutable.put(firstDecision,false);
		Object after=shapes.projectedShapeKeyForBinding(0,snapshot);
		Assert.assertEquals(before,after);
		Assert.assertEquals("the immutable EvaluationContext map identity is reused",1,
			shapes.projectionAtomStatistics().hits());
	}

	@Test
	public void lowAtomCapFallsBackAndClearDoesNotMutatePublishedBits() throws Exception {
		Fixture fixture=fixture();
		MemoBindingResolver.GuardDecision decision=new MemoBindingResolver.GuardDecision(1,List.of());
		var first=context(Map.of(decision,true),MemoBindingResolver.NO_SELECTION,9,List.of());
		MemoShapes low=MemoShapes.analyze(fixture.facts,fixture.sources,true,0);
		Object fallback=invokeShapeKey(low,first);
		Assert.assertEquals(fallback,invokeShapeKey(low,first));
		Assert.assertEquals("legacy fallback is never published as cached atoms",0,
			low.projectionAtomStatistics().requests());

		MemoShapes bounded=MemoShapes.analyze(fixture.facts,fixture.sources,true,65536);
		MemoBindingResolver.EvaluationContext firstBounded=null;
		Object published=null;
		for(int index=0;index<=4096;index++) {
			var current=context(Map.of(new MemoBindingResolver.GuardDecision(1,List.of()),index%2==0),
				MemoBindingResolver.NO_SELECTION,9,List.of(index));
			if(index==0) { firstBounded=current; published=invokeShapeKey(bounded,current); }
			else invokeShapeKey(bounded,current);
		}
		Assert.assertEquals(1,bounded.projectionAtomStatistics().clears());
		Assert.assertEquals(1,bounded.projectionAtomStatistics().entries());
		Assert.assertEquals("clearing the lookup cannot mutate an already published key",
			published,invokeShapeKey(bounded,firstBounded));
		Assert.assertEquals(1,bounded.projectionAtomStatistics().clears());
		Assert.assertEquals(2,bounded.projectionAtomStatistics().entries());
	}

	@Test
	public void saturatedAtomCapReusesAdmittedProjectionButNewAtomFallsBack() throws Exception {
		Fixture fixture=fixture();
		MemoShapes shapes=MemoShapes.analyze(fixture.facts,fixture.sources,true,1);
		MemoBindingResolver.GuardDecision decision=new MemoBindingResolver.GuardDecision(1,List.of());
		var admitted=context(Map.of(decision,true),MemoBindingResolver.NO_SELECTION,9,List.of());
		Object first=invokeShapeKey(shapes,admitted);
		Assert.assertEquals(first,invokeShapeKey(shapes,admitted));
		Assert.assertEquals(2,shapes.projectionAtomStatistics().requests());
		Assert.assertEquals(1,shapes.projectionAtomStatistics().hits());

		var newAtom=context(Map.of(decision,false),MemoBindingResolver.NO_SELECTION,9,List.of());
		Object fallback=invokeShapeKey(shapes,newAtom);
		Assert.assertNotEquals(first,fallback);
		Assert.assertEquals("a newly required atom beyond the cap must bypass the cache",
			2,shapes.projectionAtomStatistics().requests());
		Assert.assertEquals(1,shapes.projectionAtomStatistics().entries());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void canonicalDependenciesPreserveFirstRepresentativeAndExactGuardOrder() throws Exception {
		Class<?> dependency=Class.forName(MemoShapes.class.getName()+"$ShapeDependencies");
		Constructor<?> constructor=dependency.getDeclaredConstructor(Set.class,Set.class,Set.class);
		constructor.setAccessible(true);
		Object first=constructor.newInstance(Set.of(),Set.of(0,1),Set.of());
		Object second=constructor.newInstance(Set.of(),Set.of(0,1),Set.of());
		Class<?> keyType=Class.forName(MemoShapes.class.getName()+"$CanonicalDependencyKey");
		Constructor<?> keyConstructor=keyType.getDeclaredConstructor(dependency,List.class);
		keyConstructor.setAccessible(true);
		Object forward=keyConstructor.newInstance(first,List.of(0,1));
		Object reverse=keyConstructor.newInstance(first,List.of(1,0));
		Assert.assertNotEquals("guard iteration order is part of the certificate",forward,reverse);
		Method canonical=MemoShapes.class.getDeclaredMethod("canonicalShapeDependencies",List.class,Map.class);
		canonical.setAccessible(true);
		List<Object> result=(List<Object>)canonical.invoke(null,List.of(first,second),new LinkedHashMap<>());
		Assert.assertSame("binding-order representative must be retained",first,result.get(0));
		Assert.assertSame("an equal dependency with the same order reuses the first representative",
			first,result.get(1));
	}

	private static Object invokeShapeKey(MemoShapes shapes,MemoBindingResolver.EvaluationContext context)
		throws Exception {
		Method method=MemoShapes.class.getDeclaredMethod("shapeContextKey",
			MemoBindingResolver.EvaluationContext.class);
		method.setAccessible(true); return method.invoke(shapes,context);
	}

	private static MemoBindingResolver.EvaluationContext context(
		Map<MemoBindingResolver.GuardDecision,Boolean> guards,
		MemoBindingResolver.SelectionLookup selections,int depth,List<Integer> frame) {
		return new MemoBindingResolver.EvaluationContext(guards,Map.of(),selections,depth,frame,Map.of(),Map.of());
	}

	@SuppressWarnings("unchecked")
	private static int bindingWithHeads(MemoShapes shapes) throws Exception {
		List<Object> dependencies=(List<Object>)field(MemoShapes.class,"shapeDependenciesByBinding").get(shapes);
		Field heads=dependencies.get(0).getClass().getDeclaredField("heads"); heads.setAccessible(true);
		for(int binding=0;binding<dependencies.size();binding++)
			if(!((Set<?>)heads.get(dependencies.get(binding))).isEmpty()) return binding;
		throw new AssertionError("fixture has no head-sensitive binding");
	}

	private static Fixture fixture() throws Exception {
		Method parsed=MemoFederatedModelTest.class.getDeclaredMethod(
			"parsedRewrittenFixture",String.class,long.class,long.class);
		parsed.setAccessible(true);
		DMLProgram program=(DMLProgram)parsed.invoke(null,"logreg",192L,8L);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program); facts.requireComplete();
		MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data.getName()));
		return new Fixture(facts,sources);
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field value=owner.getDeclaredField(name); value.setAccessible(true); return value;
	}

	private static FederatedSourceMetadata metadata(String variable) {
		try {
			Method method=MemoFederatedModelTest.class.getDeclaredMethod("sourceMetadata",
				Privacy.class,long.class,long.class,String.class);
			method.setAccessible(true);
			return (FederatedSourceMetadata)method.invoke(null,
				"X".equals(variable) ? Privacy.PRIVATE_AGGREGATE : Privacy.PUBLIC,
				192L,"X".equals(variable) ? 8L : 1L,variable);
		}
		catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources) { }
}

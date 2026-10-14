/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class MemoIndexedShapeContextSameModelTest {
	@Test public void logregOldAndDirectHaveIdenticalSelection() throws Exception { assertSame("logreg"); }
	@Test public void glmOldAndDirectHaveIdenticalSelection() throws Exception { assertSame("glm"); }

	private static void assertSame(String workload) throws Exception {
		Method parsed=MemoFederatedModelTest.class.getDeclaredMethod(
			"parsedRewrittenFixture",String.class,long.class,long.class);
		parsed.setAccessible(true);
		DMLProgram program=(DMLProgram)parsed.invoke(null,workload,192L,8L);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data.getName()));

		MemoShapes oldShapes=MemoShapes.analyze(facts,sources,false,65536);
		MemoShapes directShapes=MemoShapes.analyze(facts,sources,true,65536);

		MemoProgramModel oldModel=new MemoProgramModel(facts,sources,
			MemoRuleBuilder.build(facts,sources,new MemoNativeRules(),oldShapes),
			MemoProgramModel.Profile.defaults());
		MemoProgramModel directModel=new MemoProgramModel(facts,sources,
			MemoRuleBuilder.build(facts,sources,new MemoNativeRules(),directShapes),
			MemoProgramModel.Profile.defaults());
		var oldResult=oldModel.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
		var directResult=directModel.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
		Assert.assertEquals(workload,MemoOptimizer.Status.OPTIMAL,oldResult.status());
		Assert.assertEquals(workload,oldResult.status(),directResult.status());
		Assert.assertEquals(workload,oldResult.cost(),directResult.cost());
		Assert.assertEquals(workload,oldResult.statistics().nodes(),directResult.statistics().nodes());
		Assert.assertArrayEquals(workload,oldResult.selectedRules(),directResult.selectedRules());
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
}

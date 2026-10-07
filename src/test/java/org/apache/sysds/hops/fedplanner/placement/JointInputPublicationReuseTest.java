/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class JointInputPublicationReuseTest {
	@Test
	public void publicationRetainsLogicalTuplesAfterBuilderCleanup() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=X;B=X;}else{A=X*2;B=X*3;}C=A+B;print(sum(C));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		BranchPlacementNormalization.prepare(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		PlacementAnalysis published = builder.buildAnalysis(program);
		PlacementJointInputAnalysis joint = published.jointInputAnalysis().orElseThrow();
		Field cacheField = PlacementJointInputAnalysis.class.getDeclaredField("tupleCache");
		cacheField.setAccessible(true);
		Map<?,?> cached = (Map<?,?>)cacheField.get(joint);
		Assert.assertFalse("publication must retain the logical tuples computed by closure", cached.isEmpty());
		Map<?,?> before = new HashMap<>(cached);
		Assert.assertFalse("fixture must exercise physical relation projection",
			JointValueMapRelations.from(published).isEmpty());
		for(var entry : before.entrySet())
			Assert.assertSame("model queries must reuse the completed tuple list", entry.getValue(),
				cached.get(entry.getKey()));

		// Builder lifetime cleanup/reuse must not empty a previously published analysis.
		PlacementAnalysis second = builder.buildAnalysis(program);
		Assert.assertNotSame(joint, second.jointInputAnalysis().orElseThrow());
		Assert.assertEquals(before.keySet(), cached.keySet());
		Assert.assertEquals(signatures(JointValueMapRelations.from(published)),
			signatures(JointValueMapRelations.from(second)));
	}

	private static List<String> signatures(List<JointValueMapRelations.Relation> relations) {
		return relations.stream().map(JointValueMapRelations.Relation::normalizedSignature).toList();
	}
}

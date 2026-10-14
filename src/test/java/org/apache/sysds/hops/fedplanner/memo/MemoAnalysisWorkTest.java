/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Component-level work counters for the independent privacy and shape analyses. */
public class MemoAnalysisWorkTest {
	@Test
	public void privacyChangingLoopDoesNotRescheduleShapeAnalysis() throws Exception {
		Fixture fixture=fixture("P=matrix(1,2,2);X=matrix(2,2,2);A=P;"
			+"for(i in 1:2){A=abs(A)+X;}print(sum(A));",Map.of("X",Privacy.PRIVATE));
		MemoShapes.Statistics shapeBefore=fixture.shapes().statistics();
		MemoPrivacy.Statistics privacyBefore=fixture.privacy().statistics();

		var first=fixture.privacy().summarizeLoop(0,MemoPrivacy.Context.empty());
		MemoPrivacy.Statistics privacyAfterFirst=fixture.privacy().statistics();
		var second=fixture.privacy().summarizeLoop(0,MemoPrivacy.Context.empty());
		MemoPrivacy.Statistics privacyAfterSecond=fixture.privacy().statistics();

		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN,first.values().iterator().next().status());
		Assert.assertSame("the second request must reuse the same loop summary",first,second);
		Assert.assertTrue(privacyAfterFirst.loopBackedgeChecks()>privacyBefore.loopBackedgeChecks());
		Assert.assertTrue(privacyAfterSecond.loopSummaryCacheHits()>privacyAfterFirst.loopSummaryCacheHits());
		Assert.assertEquals("privacy refinement must not enqueue shape work",shapeBefore,
			fixture.shapes().statistics());
	}

	@Test
	public void shapeChangingLoopDoesNotChangeExactPrivacySemantics() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);for(i in 1:2){A=rbind(A,matrix(1,1,2));}"
			+"print(sum(A));",Map.of());
		MemoShapes.Statistics shapeBefore=fixture.shapes().statistics();

		var summary=fixture.privacy().summarizeLoop(0,MemoPrivacy.Context.empty());

		int head=fixture.facts().loop(0).carriedValues().get(0).headBindingId();
		Assert.assertFalse("the fixture must exercise a non-invariant loop shape",
			fixture.shapes().bindingShape(head).exact());
		Assert.assertEquals(MemoPrivacy.Status.EXACT,summary.values().iterator().next().status());
		Assert.assertEquals(Privacy.PUBLIC,summary.values().iterator().next().privacy());
		Assert.assertEquals("shape conflict must not be represented as privacy work",shapeBefore,
			fixture.shapes().statistics());
	}

	@Test
	public void glmAnalysisCountersShowRequestLocalCacheReuse() throws Exception {
		assertWorkloadCounters("glm");
	}

	@Test
	public void logregAnalysisCountersShowRequestLocalCacheReuse() throws Exception {
		assertWorkloadCounters("logreg");
	}

	private static void assertWorkloadCounters(String workload) throws Exception {
		MemoProgramModel model=MemoFederatedModelTest.rewrittenWorkloadModel(workload);
		MemoPrivacy privacy=(MemoPrivacy) field(model,"privacy");
		MemoShapes shapes=(MemoShapes) field(model,"shapes");
		MemoProgramFacts facts=(MemoProgramFacts) field(model,"facts");
		MemoPrivacy.Statistics buildPrivacy=privacy.statistics();
		MemoShapes.Statistics buildShapes=shapes.statistics();
		System.out.println("MEMO_ANALYSIS_WORK workload="+workload+" phase=build privacy="
			+buildPrivacy+" shapes="+buildShapes);

		Set<?> sourceOwners=((Map<?,?>) field(privacy,"sourcePrivacy")).keySet();
		Set<Integer> owners=new LinkedHashSet<>();
		facts.occurrences().stream().filter(occurrence -> !occurrence.hop().getInput().isEmpty()
			&& !sourceOwners.contains(occurrence.compiledOwnerId()))
			.forEach(occurrence -> owners.add(occurrence.compiledOwnerId()));
		Assert.assertFalse("the workload must contain cacheable derived owners",owners.isEmpty());
		owners.forEach(owner -> privacy.outputPrivacy(owner,MemoPrivacy.Context.empty()));
		MemoPrivacy.Statistics firstQueries=privacy.statistics();
		owners.forEach(owner -> privacy.outputPrivacy(owner,MemoPrivacy.Context.empty()));
		MemoPrivacy.Statistics repeatedQueries=privacy.statistics();
		System.out.println("MEMO_ANALYSIS_WORK workload="+workload+" phase=same-request-queries privacy="
			+repeatedQueries+" shapes="+shapes.statistics());

		Assert.assertTrue("the binding index must perform measurable request-local work",
			buildPrivacy.indexVisits()>0 && buildPrivacy.indexEdges()>0 && buildPrivacy.dependencyEdges()>0);
		Assert.assertTrue("shape analysis must perform measurable request-local work",
			buildShapes.bindingVisits()>0 && buildShapes.ownerVisits()>0
				&& buildShapes.relevanceVisits()>0 && buildShapes.relevanceEdges()>0);
		Assert.assertEquals("an identical second query pass must be answered from privacy caches",
			firstQueries.evaluations(),repeatedQueries.evaluations());
		Assert.assertTrue("an identical second query pass must record privacy cache reuse",
			repeatedQueries.cacheHits()>firstQueries.cacheHits());
		Assert.assertEquals("privacy queries must not reschedule eager shape analysis",buildShapes,
			shapes.statistics());
	}

	private static Fixture fixture(String script,Map<String,Privacy> labels) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		Map<Integer,Privacy> sources=new HashMap<>();
		facts.occurrences().forEach(occurrence -> {
			Privacy label=labels.get(occurrence.hop().getName());
			if(label!=null && !occurrence.context().contains("loop-"))
				sources.put(occurrence.compiledOwnerId(),label);
		});
		MemoSources acquired=MemoSources.acquire(facts,null);
		return new Fixture(facts,MemoShapes.analyze(facts,acquired),
			new MemoPrivacy(facts,new MemoBindingResolver(facts),sources));
	}

	private static Object field(Object owner,String name) throws Exception {
		Field field=owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private record Fixture(MemoProgramFacts facts,MemoShapes shapes,MemoPrivacy privacy) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Request-local analysis reuse must preserve both cached answers and authority boundaries. */
public class MemoAnalysisReuseTest {

	@Test
	public void modelReusesBuilderPrivacyAndItsWarmCache() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);B=abs(A);print(sum(B));");
		MemoPrivacy privacy=fixture.universe().privacy();
		int owner=fixture.facts().occurrences().stream().filter(o -> "B".equals(o.hop().getName()))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
		var expected=privacy.outputPrivacy(owner,MemoPrivacy.Context.empty());
		Assert.assertEquals(Privacy.PUBLIC,expected.privacy());
		MemoProgramModel model=fixture.model();
		Assert.assertSame("one residency solve and privacy index per request",privacy,field(model,"privacy"));
		Assert.assertSame("reuse the resolver and its request-local caches",field(privacy,"resolver"),field(model,"resolver"));
		long evaluations=privacy.statistics().evaluations(), hits=privacy.statistics().cacheHits();
		Assert.assertEquals(expected,privacy.outputPrivacy(owner,MemoPrivacy.Context.empty()));
		Assert.assertEquals(evaluations,privacy.statistics().evaluations());
		Assert.assertTrue(privacy.statistics().cacheHits()>hits);
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,
			model.optimize(MemoOptimizer.Limits.exactDefaults()).optimization().status());
	}

	@Test
	public void modelRejectsAnotherFactsSnapshotBeforeUsingCachedAnalysis() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);print(sum(A));");
		MemoProgramFacts other=facts("A=matrix(1,2,2);print(sum(A));");
		Assert.assertThrows(IllegalArgumentException.class,() -> new MemoProgramModel(other,fixture.sources(),
			fixture.universe(),MemoProgramModel.Profile.defaults()));
	}

	@Test
	public void modelRejectsAnotherSourceSnapshotEvenWithEqualLabels() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);print(sum(A));");
		MemoSources other=MemoSources.acquire(fixture.facts(),null);
		Assert.assertEquals(fixture.sources().privacyFacts(),other.privacyFacts());
		Assert.assertThrows(IllegalArgumentException.class,() -> new MemoProgramModel(fixture.facts(),other,
			fixture.universe(),MemoProgramModel.Profile.defaults()));
	}

	@Test
	public void builderRejectsShapesFromAnotherFactsOrSourcesSnapshot() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);print(sum(A));");
		MemoProgramFacts otherFacts=facts("A=matrix(1,2,2);print(sum(A));");
		MemoSources otherSources=MemoSources.acquire(fixture.facts(),null);
		Assert.assertThrows(IllegalArgumentException.class,() -> MemoRuleBuilder.build(otherFacts,
			fixture.sources(),new MemoNativeRules(),fixture.universe().shapes()));
		Assert.assertThrows(IllegalArgumentException.class,() -> MemoRuleBuilder.build(fixture.facts(),
			otherSources,new MemoNativeRules(),fixture.universe().shapes()));
	}

	@Test
	public void modelRejectsAResultWithForeignShapesAndOtherwiseMatchingPrivacy() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);print(sum(A));");
		MemoSources otherSources=MemoSources.acquire(fixture.facts(),null);
		var original=fixture.universe();
		var mixed=new MemoRuleBuilder.Result(original.memo(),original.inputRoles(),original.diagnostics(),
			MemoShapes.analyze(fixture.facts(),otherSources),original.privacy());
		Assert.assertThrows(IllegalArgumentException.class,() -> new MemoProgramModel(fixture.facts(),
			fixture.sources(),mixed,MemoProgramModel.Profile.defaults()));
	}

	@Test
	public void aFreshBuildDoesNotInheritAnotherBuildsAnalysisCache() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,2);print(sum(A));");
		MemoRuleBuilder.Result next=MemoRuleBuilder.build(fixture.facts(),fixture.sources(),new MemoNativeRules());
		Assert.assertNotSame(fixture.universe().privacy(),next.privacy());
		MemoProgramModel model=new MemoProgramModel(fixture.facts(),fixture.sources(),next,
			MemoProgramModel.Profile.defaults());
		Assert.assertSame(next.privacy(),field(model,"privacy"));
	}

	@Test
	public void separateRequestsDoNotSharePublicAndPrivateSourceAnswers() throws Exception {
		Fixture released=sourceFixture("public"),protectedInput=sourceFixture("private");
		MemoPrivacy first=released.universe().privacy(),second=protectedInput.universe().privacy();
		Assert.assertNotSame(first,second);
		int firstOwner=released.sources().privacyFacts().keySet().iterator().next();
		int secondOwner=protectedInput.sources().privacyFacts().keySet().iterator().next();
		Assert.assertEquals(Privacy.PUBLIC,first.outputPrivacy(firstOwner,MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(Privacy.PRIVATE,second.outputPrivacy(secondOwner,MemoPrivacy.Context.empty()).privacy());
		Assert.assertSame(first,field(released.model(),"privacy"));
		Assert.assertSame(second,field(protectedInput.model(),"privacy"));
	}

	private static Object field(Object owner,String name) throws Exception {
		Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
	}

	private static Fixture fixture(String script) throws Exception {
		MemoProgramFacts facts=facts(script);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}

	private static Fixture sourceFixture(String privacy) {
		HashMap<String,Hop> parameters=new HashMap<>();
		parameters.put(DataExpression.PRIVACY,new LiteralOp(privacy));
		DataOp read=new DataOp("A",DataType.MATRIX,ValueType.FP64,OpOpData.PERSISTENTREAD,parameters);
		read.setDim1(2); read.setDim2(2); read.setNnz(4);
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(read)));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}

	private static MemoProgramFacts facts(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) {
		MemoProgramModel model() {
			return new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		}
	}
}

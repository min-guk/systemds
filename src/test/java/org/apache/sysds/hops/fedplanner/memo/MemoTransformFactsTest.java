/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.junit.Test;

public class MemoTransformFactsTest {
	private static final String SPEC="{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}";

	@Test
	public void aliasProofShapesCloneAndCostWhileNoProofStillRejectsDummycode() throws Exception {
		withPolicy(true,()->{
			Fixture fixture=fixture(single("F=as.frame(A);","F"));
			AtomicInteger collections=new AtomicInteger();
			MemoTransformFacts prepared=MemoTransformFacts.prepare(fixture.facts(),fixture.sources(),
				(source,metadata,spec)->{
					collections.incrementAndGet();
					assertSame(fixture.facts().compiledOwnerHop(fixture.sourceOwner("A")),source);
					return new MemoTransformFacts.Collected(7,3,2);
				});
			assertEquals(1,collections.get());
			MemoTransformFacts.Proof proof=prepared.proof(fixture.transformOwner());
			assertNotNull(proof);
			MemoShapes shapes=MemoShapes.analyze(fixture.facts(),fixture.sources(),prepared);
			int occurrence=fixture.transformOccurrence();
			FunctionOp original=(FunctionOp)fixture.facts().occurrence(occurrence).hop();
			FunctionOp view=(FunctionOp)shapes.viewAt(occurrence);
			assertNotSameIdentity(original,view);
			assertEquals(4,view.getOutputs().get(0).getDim1());
			assertEquals(7,view.getOutputs().get(0).getDim2());
			assertEquals(3,view.getOutputs().get(1).getDim1());
			assertEquals(2,view.getOutputs().get(1).getDim2());
			RuleMemo.Rule rule=new RuleMemo.Rule(ExecType.CP,List.of(MemoPartition.LOCAL,MemoPartition.LOCAL),
				List.of(MemoPartition.LOCAL,MemoPartition.LOCAL),"test");
			assertUnknown(()->MemoCostModel.nativeCost(view,rule,2,1));
			double cost=MemoCostModel.nativeCost(view,rule,2,1,fixture.transformOwner(),proof);
			assertTrue(Double.isFinite(cost) && cost>=0);
		});
	}

	@Test
	public void policyOffDoesNotCollectAndProofCannotCrossRequests() throws Exception {
		Fixture fixture=fixture(single("F=as.frame(A);","F"));
		AtomicInteger calls=new AtomicInteger();
		withPolicy(false,()->assertNull(MemoTransformFacts.prepare(fixture.facts(),fixture.sources(),
			(source,metadata,spec)->{ calls.incrementAndGet(); return new MemoTransformFacts.Collected(7,3,2); })
			.proof(fixture.transformOwner())));
		assertEquals(0,calls.get());
		withPolicy(true,()->{
			MemoTransformFacts prepared=MemoTransformFacts.prepare(fixture.facts(),fixture.sources(),
				(source,metadata,spec)->new MemoTransformFacts.Collected(7,3,2));
			Fixture other=fixture(single("F=as.frame(A);","F"));
			try {
				MemoShapes.analyze(other.facts(),other.sources(),prepared);
				fail("cross-request proof authority must be rejected");
			}
			catch(IllegalArgumentException expected) { /* expected */ }
		});
	}

	@Test
	public void arithmeticAndExplicitSchemaAliasesDoNotAuthorizeCollection() throws Exception {
		withPolicy(true,()->{
			for(String prefix:List.of("F=as.frame(A+1);",
				"F=as.frame(A,schema=list(\"FP64\",\"FP64\"));")) {
				Fixture fixture=fixture(single(prefix,"F"));
				AtomicInteger calls=new AtomicInteger();
				MemoTransformFacts prepared=MemoTransformFacts.prepare(fixture.facts(),fixture.sources(),
					(source,metadata,spec)->{ calls.incrementAndGet(); return new MemoTransformFacts.Collected(7,3,2); });
				assertEquals(0,calls.get());
				assertNull(prepared.proof(fixture.transformOwner()));
				assertFalse(prepared.diagnostics().isEmpty());
			}
		});
	}

	@Test
	public void sameShapeDifferentSourcesAreNotMergedAndAmbiguousContextIsRejected() throws Exception {
		withPolicy(true,()->{
			Fixture separate=fixture(twoSources("FA=as.frame(A);FB=as.frame(B);"
				+transform("XA","MA","FA")+transform("XB","MB","FB")));
			AtomicInteger calls=new AtomicInteger();
			List<DataOp> seen=new ArrayList<>();
			MemoTransformFacts prepared=MemoTransformFacts.prepare(separate.facts(),separate.sources(),
				(source,metadata,spec)->{ calls.incrementAndGet(); seen.add(source);
					return new MemoTransformFacts.Collected(6,3,2); });
			assertEquals(2,calls.get());
			assertEquals(2,seen.stream().distinct().count());
			List<MemoTransformFacts.Proof> proofs=separate.transformOwners().stream()
				.map(prepared::proof).toList();
			assertTrue(proofs.stream().allMatch(java.util.Objects::nonNull));
			assertTrue(proofs.get(0).sourceOwner()!=proofs.get(1).sourceOwner());

			Fixture ambiguous=fixture(twoSources("if(rand()<0.5){F=as.frame(A);}else{F=as.frame(B);}"
				+transform("X","M","F")));
			AtomicInteger rejectedCalls=new AtomicInteger();
			MemoTransformFacts rejected=MemoTransformFacts.prepare(ambiguous.facts(),ambiguous.sources(),
				(source,metadata,spec)->{ rejectedCalls.incrementAndGet(); return new MemoTransformFacts.Collected(6,3,2); });
			assertEquals(0,rejectedCalls.get());
			assertNull(rejected.proof(ambiguous.transformOwner()));
		});
	}

	@Test
	public void everyCallsiteMustResolveToTheSameSourceOwner() throws Exception {
		withPolicy(true,()->{
			String function="txfun=function(Frame[Unknown] F) return (Matrix[Double] X){"
				+"[X0,M]=transformencode(target=F,spec=\""+SPEC+"\");X=X0;}\n";
			Fixture same=fixture(function+source("A",19101)+"FA=as.frame(A);"
				+"X1=txfun(F=FA);X2=txfun(F=FA);print(sum(X1));print(sum(X2));");
			AtomicInteger sameCalls=new AtomicInteger();
			MemoTransformFacts accepted=MemoTransformFacts.prepare(same.facts(),same.sources(),
				(source,metadata,spec)->{ sameCalls.incrementAndGet(); return new MemoTransformFacts.Collected(5,3,2); });
			assertEquals(1,sameCalls.get());
			assertNotNull(accepted.proof(same.transformOwner()));

			Fixture different=fixture(function+twoSources("FA=as.frame(A);FB=as.frame(B);"
				+"X1=txfun(F=FA);X2=txfun(F=FB);print(sum(X1));print(sum(X2));"));
			AtomicInteger differentCalls=new AtomicInteger();
			MemoTransformFacts rejected=MemoTransformFacts.prepare(different.facts(),different.sources(),
				(source,metadata,spec)->{ differentCalls.incrementAndGet(); return new MemoTransformFacts.Collected(5,3,2); });
			assertEquals(0,differentCalls.get());
			assertNull(rejected.proof(different.transformOwner()));
		});
	}

	private static String single(String preparation,String target) {
		return source("A",19101)+preparation+transform("X","M",target);
	}
	private static String twoSources(String body) { return source("A",19101)+source("B",19101)+body; }
	private static String source(String name,int port) {
		return name+"=federated(addresses=list(\"worker-0:"+port+"/"+name+"0\",\"worker-1:"
			+(port+1)+"/"+name+"1\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}
	private static String transform(String output,String metadata,String target) {
		return "["+output+","+metadata+"]=transformencode(target="+target+",spec=\""+SPEC+"\");"
			+"print(sum("+output+"));";
	}

	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data.getName()));
		return new Fixture(facts,sources);
	}

	private static FederatedSourceMetadata metadata(String name) {
		int base=19101;
		return new FederatedSourceMetadata(Privacy.PUBLIC,List.of(part(name,0,2,base),part(name,2,4,base+1)));
	}
	private static Pair<FederatedRange,FederatedData> part(String name,long begin,long end,int port) {
		return Pair.of(new FederatedRange(new long[] {begin,0},new long[] {end,2}),
			FederatedData.forPlannerMetadata(DataType.MATRIX,
				InetSocketAddress.createUnresolved("worker-"+(begin==0 ? 0 : 1),port),name+(begin==0 ? 0 : 1)));
	}

	private static void withPolicy(boolean enabled,Throwing action) throws Exception {
		String prepare=System.getProperty(MemoTransformFacts.PREPARE_PROPERTY);
		String release=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			if(enabled) {
				System.setProperty(MemoTransformFacts.PREPARE_PROPERTY,"true");
				System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			}
			else {
				System.clearProperty(MemoTransformFacts.PREPARE_PROPERTY);
				System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			}
			action.run();
		}
		finally { restore(MemoTransformFacts.PREPARE_PROPERTY,prepare);
			restore(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,release); }
	}
	private static void restore(String key,String value) {
		if(value==null) System.clearProperty(key); else System.setProperty(key,value);
	}
	private static void assertUnknown(Runnable action) {
		try { action.run(); fail("Expected UNKNOWN_SIZE"); }
		catch(MemoCostModel.CostException expected) {
			assertEquals(MemoCostModel.DiagnosticCode.UNKNOWN_SIZE,expected.code());
		}
	}
	private static void assertNotSameIdentity(Object left,Object right) { assertFalse(left==right); }
	@FunctionalInterface private interface Throwing { void run() throws Exception; }
	private record Fixture(MemoProgramFacts facts,MemoSources sources) {
		List<Integer> transformOwners() { return facts.occurrences().stream()
			.filter(value -> value.hop() instanceof FunctionOp function
				&& "transformencode".equalsIgnoreCase(function.getFunctionName()))
			.map(MemoProgramFacts.Occurrence::compiledOwnerId).distinct().toList(); }
		int transformOwner() { return transformOwners().get(0); }
		int transformOccurrence() { return facts.occurrences().stream()
			.filter(value -> value.compiledOwnerId()==transformOwner()).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow(); }
		int sourceOwner(String name) { return facts.occurrences().stream()
			.filter(value -> value.hop() instanceof DataOp data && name.equals(data.getName()))
			.map(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow(); }
	}
}

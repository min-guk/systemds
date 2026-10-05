/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementRelationClosure.HeuristicPathMetrics;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Seed-specific proofs share immutable edge tests and incremental all-definition phi events. */
public class HeuristicPathIncrementalTest {
	static final String PREFIX = "X=federated(addresses=list(\"localhost:1234/X\"),"
		+ "ranges=list(list(0,0),list(8,3)));\n"
		+ "u=matrix(1,rows=3,cols=1);v=matrix(2,rows=3,cols=1);\n";
	static final String JOINED = PREFIX + "a=X%*%u;b=X%*%v;c=a+b;d=c*2;\n"
		+ "if(sum(a)>0){z=c;}else{z=d;}\n"
		+ "if(sum(b)>0){q=z+1;}else{q=z*2;}\nprint(sum(q));\n";
	static final String MIXED = PREFIX + "a=X%*%u;\n"
		+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(8,1)));\n"
		+ "if(sum(a)>0){z=a;}else{z=Y;}\nprint(sum(z));\n";
	static final String WITHDRAWN = "f=function(Matrix[double] A) return(Matrix[double] R) {"
		+ "if(sum(A)>0){R=exp(A);}else{R=abs(A);}}\n" + PREFIX
		+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(8,1)));\n"
		+ "z=X%*%u;a=f(z);b=f(Y);print(sum(a)+sum(b));\n";
	static final String NESTED_WITHDRAWN = WITHDRAWN.replace("list(8,1)", "list(3,1)")
		.replace("z=X%*%u;a=f(z)", "z=X%*%u;q=t(X)%*%z;a=f(q)");

	@Test
	public void joinedSeedsRetainSeparateProofsWithoutRetracingPhiRounds() throws Exception {
		Fixture fixture = fixture(JOINED);
		HeuristicPathMetrics metrics = new HeuristicPathMetrics();
		HeuristicPolicyFacts facts = replay(fixture, metrics);
		Assert.assertEquals(fixture.analysis().heuristicPolicyFacts(), facts);
		Assert.assertEquals(2, facts.demotions().size());
		Assert.assertEquals("CFG additions must not restart every seed path", 1, metrics.tracingEpochs);
		Assert.assertTrue("Fixture exercises shared edge classifications",
			metrics.edgeClassifications < metrics.pathEdgeVisits);
		Assert.assertTrue("Fixture must activate exact transient forwards", metrics.cfgEdgesActivated > 0);
		Set<CompiledHopKey> first = Set.copyOf(facts.paths().get(0).localPrefix());
		Assert.assertTrue("The two seeds share a downstream local region", facts.paths().get(1).localPrefix()
			.stream().anyMatch(first::contains));
		for(var path : facts.paths()) {
			Assert.assertTrue(path.localPrefix().contains(path.demotion().producer()));
			Assert.assertTrue("Each proof must pass through multiple CFG consumers", path.edges().stream()
				.filter(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD)
				.map(PlacementAnalysis.HeuristicPathEdgeFact::consumer).distinct().count() >= 2);
		}
	}

	@Test
	public void unprovenWriterCannotActivateALocalPhi() throws Exception {
		Fixture fixture = fixture(MIXED, true);
		HeuristicPolicyFacts facts = replay(fixture, new HeuristicPathMetrics());
		Assert.assertEquals(fixture.analysis().heuristicPolicyFacts(), facts);
		Assert.assertFalse(facts.demotions().isEmpty());
		Set<CompiledHopKey> joinedReads = fixture.analysis().logicalTransientInputsInCanonicalOrder().stream()
			.collect(Collectors.groupingBy(PlacementAnalysis.LogicalTransientInputFact::targetRead))
			.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
			.map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
		Assert.assertFalse("Fixture must have a multi-writer read", joinedReads.isEmpty());
		Assert.assertTrue(facts.paths().stream().flatMap(path -> path.edges().stream())
			.noneMatch(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD
				&& joinedReads.contains(edge.consumer())));
	}

	@Test
	public void protectedSharedFormalDoesNotRetainAnInvalidSeed() throws Exception {
		Fixture fixture = fixture(WITHDRAWN);
		HeuristicPathMetrics metrics = new HeuristicPathMetrics();
		HeuristicPolicyFacts facts = replay(fixture, metrics);
		Assert.assertEquals(fixture.analysis().heuristicPolicyFacts(), facts);
		Assert.assertTrue("The shared protected formal disallows this local policy seed", facts.demotions().isEmpty());
		Assert.assertTrue(facts.paths().isEmpty());
		Assert.assertEquals("A withdrawn seed must start a fresh proof closure", 2, metrics.tracingEpochs);
	}

	@Test
	public void noDemotionsNeedNoPathIndexOrTraversal() throws Exception {
		Fixture fixture = fixture(PREFIX + "print(sum(exp(X)));\n");
		HeuristicPathMetrics metrics = new HeuristicPathMetrics();
		Assert.assertTrue(replay(fixture, metrics).paths().isEmpty());
		Assert.assertEquals(0, metrics.tracingEpochs);
		Assert.assertEquals(0, metrics.edgeClassifications);
		Assert.assertEquals(0, metrics.pathEdgeVisits);
	}

	@Test
	public void withdrawnNestedSeedDoesNotReuseItsNativeLocalPreference() throws Exception {
		Fixture fixture = fixture(NESTED_WITHDRAWN);
		HeuristicPathMetrics metrics = new HeuristicPathMetrics();
		HeuristicPolicyFacts facts = replay(fixture, metrics);
		Assert.assertEquals(fixture.analysis().heuristicPolicyFacts(), facts);
		Assert.assertEquals("Only the upstream seed remains", 1, facts.demotions().size());
		Assert.assertEquals(2, metrics.tracingEpochs);
		Assert.assertTrue("The removed nested seed cannot retain its cached LOUT boundary preference",
			facts.paths().stream().flatMap(path -> path.nativeContinuations().stream())
				.noneMatch(fact -> fact.consumerState().output() == FederatedOutput.LOUT));
	}

	static record Fixture(PlacementAnalysis analysis, PlacementProgramFacts programFacts,
		PlacementShapeFacts shapes) { }

	static Fixture fixture(String script) throws Exception {
		return fixture(script, false);
	}

	static Fixture fixture(String script, boolean publicY) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		if(publicY) {
			java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
			program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
				.forEach(block -> pending.addAll(block.getHops()));
			Set<Hop> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			while(!pending.isEmpty()) {
				Hop hop = pending.removeFirst();
				if(!seen.add(hop)) continue;
				if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED && "Y".equals(data.getName()))
					FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PUBLIC);
				pending.addAll(hop.getInput());
			}
		}
		FunctionCallGraph calls = new FunctionCallGraph(program);
		PlacementProgramFacts programFacts = PlacementProgramFacts.analyze(program, calls,
			new FunctionCallSizeInfo(calls), null, null);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Field field = PlacementAnalysis.class.getDeclaredField("shapeFacts");
		field.setAccessible(true);
		return new Fixture(analysis, programFacts, (PlacementShapeFacts) field.get(analysis));
	}

	static HeuristicPolicyFacts replay(Fixture fixture, HeuristicPathMetrics metrics) {
		PlacementAnalysis analysis = fixture.analysis();
		return PlacementRelationClosure.heuristicPolicyFacts(analysis.graph(), analysis.occurrences(),
			fixture.shapes(), analysis.compiledInputEdgesInCanonicalOrder(), analysis.candidateRuleFacts().orderedFacts(),
			fixture.programFacts().occurrences(), fixture.programFacts().cfg(), metrics);
	}
}

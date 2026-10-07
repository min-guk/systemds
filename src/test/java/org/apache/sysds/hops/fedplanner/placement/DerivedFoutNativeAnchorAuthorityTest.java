/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutAnchorAuthority;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class DerivedFoutNativeAnchorAuthorityTest {
	@Test
	public void formalNativeMapRequiresItsOwnedCertificate() throws Exception {
		Fixture fixture = fixture();
		new NeutralPlacementGraph(fixture.nodes(), fixture.analysis().graph().constraints(), List.of(),
			List.of(fixture.action()));
		Assert.assertThrows(IllegalArgumentException.class, () -> new NeutralPlacementGraph(
			fixture.nodes(), fixture.analysis().graph().constraints(), List.of(),
			List.of(new DerivedFoutMaterializationAction(fixture.action().key()))));
	}

	@Test
	public void otherWorkerPoolDoesNotAuthorizeFormalUpload() throws Exception {
		Fixture fixture = fixture();
		var key = fixture.action().key();
		DurableAnchorKey pool = key.durableAnchor();
		var partitions = pool.partitions().stream().map(partition ->
			new PlacementIdentity.AnchorPartition("other-worker:9000/data", partition.begin(), partition.end())).toList();
		var foreign = new DurableAnchorKey(pool.placementId(), pool.fType(), partitions);
		var wrongKey = new DerivedFoutMaterializationActionKey(key.producer(), key.producerValueVersion(),
			key.candidateRule(), key.sourcePlacement(), key.targetPlacement(), foreign,
			key.durableAnchorOwner(), key.durableAnchorOwnerFType(), key.materializationFType(), key.statementBlockScope());
		Assert.assertThrows(IllegalArgumentException.class, () -> new NeutralPlacementGraph(
			fixture.nodes(), fixture.analysis().graph().constraints(), List.of(),
			List.of(new DerivedFoutMaterializationAction(wrongKey, List.of(), fixture.action().nativeAnchorAuthorities()))));
	}

	@Test
	public void structurallyEqualForeignClauseDoesNotOwnCertificate() throws Exception {
		var authority = fixture().action().nativeAnchorAuthorities().get(0);
		var clause = authority.clause();
		var foreign = new PlacementAnalysis.CandidateRealizationSupportClause(clause.proofDependencies(),
			clause.inputBindings(), clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
		Assert.assertEquals(clause, foreign);
		Assert.assertNotSame(clause, foreign);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new DerivedFoutAnchorAuthority(authority.reference(), authority.realization(), foreign));
	}

	@Test
	public void otherOccurrenceDoesNotOwnFormalCertificate() throws Exception {
		Fixture fixture = fixture();
		var authority = fixture.action().nativeAnchorAuthorities().get(0);
		var foreignRule = new PlacementAnalysis.CandidateRuleKey(fixture.action().key().producer(),
			authority.reference().rule().orderedInputs());
		var foreign = new DerivedFoutAnchorAuthority(
			CandidateRealizationReference.of(foreignRule, authority.realization()), authority.realization(), authority.clause());
		Assert.assertThrows(IllegalArgumentException.class, () -> new NeutralPlacementGraph(
			fixture.nodes(), fixture.analysis().graph().constraints(), List.of(), List.of(
				new DerivedFoutMaterializationAction(fixture.action().key(), List.of(), List.of(foreign)))));
	}

	@Test
	public void sameWorkersWithDifferentRowRangesDoNotAuthorizeUpload() throws Exception {
		Fixture fixture = fixture(true);
		var key = fixture.action().key();
		DurableAnchorKey pool = key.durableAnchor();
		Assert.assertEquals(org.apache.sysds.hops.fedplanner.FTypes.FType.ROW, pool.fType());
		var partitions = new ArrayList<>(pool.partitions());
		var first = partitions.get(0);
		var end = new ArrayList<>(first.end());
		end.set(0, end.get(0) + 1);
		partitions.set(0, new PlacementIdentity.AnchorPartition(first.workerId(), first.begin(), end));
		var foreign = new DurableAnchorKey(pool.placementId(), pool.fType(), partitions);
		var wrongKey = new DerivedFoutMaterializationActionKey(key.producer(), key.producerValueVersion(),
			key.candidateRule(), key.sourcePlacement(), key.targetPlacement(), foreign,
			key.durableAnchorOwner(), key.durableAnchorOwnerFType(), key.materializationFType(), key.statementBlockScope());
		Assert.assertThrows(IllegalArgumentException.class, () -> new NeutralPlacementGraph(
			fixture.nodes(), fixture.analysis().graph().constraints(), List.of(),
			List.of(new DerivedFoutMaterializationAction(wrongKey, List.of(), fixture.action().nativeAnchorAuthorities()))));
	}

	private record Fixture(PlacementAnalysis analysis, List<Node> nodes, DerivedFoutMaterializationAction action) { }

	private static Fixture fixture() throws Exception {
		return fixture(false);
	}

	private static Fixture fixture(boolean rowPartitioned) throws Exception {
		String script = """
			inner=function(matrix[double] Y, double k) return(double out) {
			  if(k>0) {out=sum(abs(Y));} else {out=sum(Y);}
			}
			Y=federated(addresses=list("localhost:1234/Y1"),ranges=list(list(0,0),list(8,1)));
			Y=cbind(1-Y,Y);
			s=inner(Y,sum(Y)); print(s);
			""";
		if(rowPartitioned)
			script = script.replace("list(\"localhost:1234/Y1\"),ranges=list(list(0,0),list(8,1))",
				"list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),ranges=list(list(0,0),list(4,1),list(4,0),list(8,1))");
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var original = analysis.graph().derivedFoutMaterializationActions().stream().filter(action ->
			analysis.hop(action.key().producer()).orElseThrow().getOpString().equals("u(abs)"))
			.findFirst().orElseThrow();
		var owner = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == original.key().producer() && edge.inputPosition() == 0)
			.findFirst().orElseThrow().producer();
		DerivedFoutAnchorAuthority authority = analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream()
				.filter(realization -> realization.placementState().fType() == original.key().durableAnchorOwnerFType())
				.flatMap(realization -> realization.supportClauses().stream()
					.filter(clause -> realization.provenWorkerPool(clause) != null)
					.map(clause -> new DerivedFoutAnchorAuthority(
						CandidateRealizationReference.of(fact.key(), realization), realization, clause)))))
			.findFirst().orElseThrow();
		var key = original.key();
		var rebound = new DerivedFoutMaterializationActionKey(key.producer(), key.producerValueVersion(),
			key.candidateRule(), key.sourcePlacement(), key.targetPlacement(), authority.pool(), owner,
			key.durableAnchorOwnerFType(), key.materializationFType(), key.statementBlockScope());
		List<Node> nodes = new ArrayList<>(analysis.graph().nodes());
		Node current = analysis.graph().node(owner).orElseThrow();
		Assert.assertEquals(NeutralPlacementGraph.NodeKind.TRANSIENT_READ, current.kind());
		nodes.set(nodes.indexOf(current), new Node(current.key(), current.kind(), current.valueVersion(),
			current.emittedWork(), current.legalAlternatives(), current.exclusions(), List.of()));
		return new Fixture(analysis, nodes, new DerivedFoutMaterializationAction(rebound, List.of(), List.of(authority)));
	}
}

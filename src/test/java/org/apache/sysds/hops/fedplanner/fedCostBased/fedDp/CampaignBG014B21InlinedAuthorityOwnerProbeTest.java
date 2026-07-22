/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Isolated diagnostic for the exact B21 parent candidate-fact and graph-state owner. */
public class CampaignBG014B21InlinedAuthorityOwnerProbeTest {
	@Test
	public void exposeExactParentInputFactsAndExclusions() throws Exception {
		DMLProgram program = CampaignBG014HermeticPlannerFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection parent = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp)
			.filter(occurrence -> ((DataOp) occurrence.hop()).getOp() == OpOpData.TRANSIENTWRITE)
			.filter(occurrence -> "A".equals(occurrence.hop().getName()))
			.findFirst().orElseThrow(() -> new AssertionError("PROBE_INCONCLUSIVE no physical TWrite A"));
		Node parentNode = analysis.graph().node(parent.key()).orElseThrow();
		if(parent.hop().getInput().size() != 1)
			throw new AssertionError("PROBE_INCONCLUSIVE TWrite A direct-input arity="
				+ parent.hop().getInput().size());
		Hop input = parent.hop().getInput(0);
		List<HopOccurrenceProjection> inputOccurrences = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == input).toList();
		List<CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == parent.key()).toList();
		if(inputOccurrences.isEmpty() || facts.isEmpty())
			throw new AssertionError("PROBE_INCONCLUSIVE missing exact input occurrence or parent facts: inputOccurrences="
				+ inputOccurrences.size() + " facts=" + facts.size());

		String parentDump = occurrence(analysis, parent);
		String inputDump = "hop=" + input.getHopID() + ':' + input.getOpString() + ':' + input.getName()
			+ ",occurrences=" + inputOccurrences.stream().map(value -> occurrence(analysis, value)).toList();
		String factsDump = facts.stream().map(CampaignBG014B21InlinedAuthorityOwnerProbeTest::fact).toList().toString();
		String exclusionsDump = parentNode.exclusions().stream().map(exclusion ->
			"state=" + exclusion.state() + ",reason=" + exclusion.reasonCode() + ",detail=" + exclusion.detail())
			.toList().toString();
		String dump = "PARENT={" + parentDump + "}; INPUT={" + inputDump + "}; FACTS=" + factsDump
			+ "; EXCLUSIONS=" + exclusionsDump;
		System.out.println("TASK82 " + dump);

		Assert.fail("TASK82_OWNER_PROBE " + dump);
	}

	private static String occurrence(PlacementAnalysis analysis, HopOccurrenceProjection occurrence) {
		Node node = analysis.graph().node(occurrence.key()).orElseThrow();
		return "hop=" + occurrence.hop().getHopID() + ':' + occurrence.hop().getOpString() + ':'
			+ occurrence.hop().getName() + ",scope=" + occurrence.scopeId() + ",ordinal="
			+ occurrence.normalizedOrdinal() + ",kind=" + node.kind() + ",states=" + node.legalAlternatives()
			+ ",anchors=" + node.anchors() + ",key=" + occurrence.key().normalizedSignature();
	}

	private static String fact(CandidateRuleFact fact) {
		return "inputs=" + fact.key().orderedInputs() + ",status=" + fact.status() + ",capability="
			+ (fact.capability() == null ? "null" : "native=" + fact.capability().nativeExec() + '/'
				+ fact.capability().nativeOutput() + ",fType=" + fact.capability().nativeFoutFType()
				+ ",reason=" + fact.capability().reasonCode() + ",detail=" + fact.capability().detail())
			+ ",shape={consulted=" + fact.shapeProof().consultedFacts() + ",required="
			+ fact.shapeProof().requiredFacts() + ",missing=" + fact.shapeProof().missingRequiredFacts()
			+ "},profile={outputs=" + fact.profile().producerOutputs() + ",failure="
			+ fact.profile().evaluationFailure() + "},failureCode=" + fact.failureCode();
	}
}

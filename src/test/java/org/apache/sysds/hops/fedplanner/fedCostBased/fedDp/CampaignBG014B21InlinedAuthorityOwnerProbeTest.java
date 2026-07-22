/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionCallBoundary;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Temporary owner probe for single-callsite DISTINCT_CONTEXT pollution in hermetic B21. */
public class CampaignBG014B21InlinedAuthorityOwnerProbeTest {
	@Test
	public void singleB21CallsiteMustNotBecomeDistinctFromItself() throws Exception {
		DMLProgram b17Program = ProductionShadowFixtureFactory.compile("B-17");
		PlacementAnalysis b17 = new NeutralPlacementGraphBuilder().buildAnalysis(b17Program);
		List<String> b17Callsites = callsites(b17Program);
		Set<String> b17UniqueCallsites = new LinkedHashSet<>(b17Callsites);
		List<Constraint> b17Distinct = functionCallDistinctConstraints(b17);
		if(b17UniqueCallsites.size() != 2 || b17Distinct.isEmpty())
			throw new AssertionError("PROBE_INCONCLUSIVE B17 control lacks two unequal callsites: callsites="
				+ b17Callsites + " distinct=" + signatures(b17Distinct));

		DMLProgram b21Program = CampaignBG014HermeticPlannerFixtureFactory.compile("B-21");
		PlacementAnalysis b21 = new NeutralPlacementGraphBuilder().buildAnalysis(b21Program);
		List<String> b21Callsites = callsites(b21Program);
		Set<String> b21UniqueCallsites = new LinkedHashSet<>(b21Callsites);
		if(b21UniqueCallsites.size() != 1)
			throw new AssertionError("PROBE_INCONCLUSIVE hermetic B21 is not one exact syntactic callsite: "
				+ b21Callsites);

		List<HopOccurrenceProjection> writes = b21.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp)
			.filter(occurrence -> ((DataOp) occurrence.hop()).getOp() == OpOpData.TRANSIENTWRITE)
			.filter(occurrence -> "A".equals(occurrence.hop().getName()))
			.toList();
		if(writes.isEmpty())
			throw new AssertionError("PROBE_INCONCLUSIVE B21 lacks an exact physical TWrite A occurrence");
		List<HopOccurrenceProjection> federatedWrites = writes.stream().filter(write ->
			b21.graph().node(write.key()).orElseThrow().legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT && state.fType() != null))
			.toList();
		if(federatedWrites.isEmpty())
			throw new AssertionError("PROBE_INCONCLUSIVE no B21 TWrite A occurrence has an exact concrete FED/FOUT state: "
				+ writes.stream().map(write -> occurrence(b21, write)).toList());

		Set<Object> writeKeys = new LinkedHashSet<>();
		federatedWrites.forEach(write -> writeKeys.add(write.key()));
		List<Constraint> incident = b21.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.DISTINCT_CONTEXT)
			.filter(constraint -> writeKeys.contains(constraint.left()) || writeKeys.contains(constraint.right()))
			.toList();
		System.out.println("TASK78 B17_CALLSITES=" + b17Callsites);
		System.out.println("TASK78 B17_DISTINCT=" + signatures(b17Distinct));
		System.out.println("TASK78 B21_CALLSITES=" + b21Callsites);
		System.out.println("TASK78 B21_TWRITE_A=" + writes.stream().map(write -> occurrence(b21, write)).toList());
		System.out.println("TASK78 B21_INCIDENT_DISTINCT=" + signatures(incident));

		Assert.assertTrue("A single B21 syntactic callsite must not create DISTINCT_CONTEXT between repeated "
			+ "authorities of that same callsite; callsites=" + b21Callsites + " writes="
			+ federatedWrites.stream().map(write -> occurrence(b21, write)).toList()
			+ " incident=" + signatures(incident), incident.isEmpty());
	}

	private static List<String> callsites(DMLProgram program) {
		List<String> result = new ArrayList<>();
		for(StatementBlock block : program.getStatementBlocks())
			for(InlinedFunctionCallBoundary call : block.getInlinedFunctionCallBoundaries())
				result.add(block.getSBID() + ":" + call.functionKey() + ":" + call.callStatementPosition());
		return result;
	}

	private static List<Constraint> functionCallDistinctConstraints(PlacementAnalysis analysis) {
		return analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.DISTINCT_CONTEXT)
			.filter(constraint -> analysis.graph().node(constraint.left())
				.map(node -> node.kind() == NodeKind.FUNCTION_CALL).orElse(false))
			.filter(constraint -> analysis.graph().node(constraint.right())
				.map(node -> node.kind() == NodeKind.FUNCTION_CALL).orElse(false))
			.toList();
	}

	private static String occurrence(PlacementAnalysis analysis, HopOccurrenceProjection occurrence) {
		return "scope=" + occurrence.scopeId() + ",ordinal=" + occurrence.normalizedOrdinal()
			+ ",kind=" + analysis.graph().node(occurrence.key()).orElseThrow().kind()
			+ ",states=" + analysis.graph().node(occurrence.key()).orElseThrow().legalAlternatives()
			+ ",key=" + occurrence.key().normalizedSignature();
	}

	private static List<String> signatures(List<Constraint> constraints) {
		return constraints.stream().map(Constraint::normalizedSignature).toList();
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for preserving demoted LogReg vectors across named-function loop CFG boundaries. */
public class CampaignBG014HeuristicLogRegLoopLocalityRedTest {
	@Test
	public void singleWorkerFullDoesNotReuploadDemotedGradOrHv() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(logRegScript(1)));
		Set<CompiledHopKey> markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.producer())
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Assert.assertFalse("The LogReg fixture must contain Heuristic demotion markers", markers.isEmpty());

		Set<Integer> forwardedReadLines = new LinkedHashSet<>();
		for(var path : analysis.heuristicPolicyFacts().paths())
			for(CompiledHopKey key : path.localPrefix()) {
				var node = analysis.graph().node(key).orElseThrow();
				var hop = analysis.hop(key).orElseThrow();
				if(node.kind() == NodeKind.TRANSIENT_READ && hop instanceof DataOp
					&& Set.of("Grad", "HV").contains(hop.getName()))
					forwardedReadLines.add(hop.getBeginLine());
			}
		Assert.assertTrue("Demoted Grad/HV must remain local across all repeated LogReg reads: "
			+ forwardedReadLines, forwardedReadLines.containsAll(Set.of(179, 209, 226, 235)));

		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markerValues =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		var selected = new HeuristicPlacementAdapter().select(analysis, markerValues);
		Assert.assertTrue("Single-worker FULL must not turn loop-local vectors back into REFED actions",
			selected.selectedRelocations().stream().noneMatch(action ->
				Set.of("Grad", "HV", "R", "V", "S", "Snew")
					.contains(action.sourceValueVersion().lexicalVariable())));
	}

	private static String logRegScript(int workers) throws Exception {
		return federated("X", 50000, 2100, workers) + '\n'
			+ federated("Y", 50000, 1, workers) + '\n'
			+ "Y=(Y<0)+1;\n"
			+ "m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
			+ "numclasses=2,numrows=50000,numcols=2100);\n"
			+ "write(m,\"out\",format=\"csv\");\n";
	}

	private static String federated(String name, long rows, long cols, int workers) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = rows * worker / workers;
			long end = rows * (worker + 1L) / workers;
			Path data = Files.createTempFile("g014-heuristic-logreg-" + name.toLowerCase()
				+ "-w" + workers + "-p" + (worker + 1) + '-', ".data");
			Path metadata = Path.of(data + ".mtd");
			Files.writeString(data, "");
			Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
				+ "\"format\":\"binary\",\"rows\":" + (end - begin) + ",\"cols\":" + cols + ','
				+ "\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + ((end - begin) * cols)
				+ ",\"privacy\":\"private-aggregate\"}");
			data.toFile().deleteOnExit();
			metadata.toFile().deleteOnExit();
			addresses.add("\"localhost:" + (1234 + worker) + "//" + data + "\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ',' + cols + ")");
		}
		return name + "=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}

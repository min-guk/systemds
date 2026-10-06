/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.runtime.controlprogram.federated.FederatedStatistics;
import org.apache.sysds.utils.Statistics;

/** Docker-only DP-LocalConflict runtime witness with separate modeled and measured fields. */
public final class CostRuntimeDockerProbe {
	private static final Pattern ASSIGNMENT = Pattern.compile("assignment=\\[([^]]*)]");
	private static final Pattern OBJECTIVE = Pattern.compile("local-conflict-objective=([0-9]+)");

	private CostRuntimeDockerProbe() { }

	public static void main(String[] args) throws Exception {
		if(args.length != 3)
			throw new IllegalArgumentException("script config result-json expected");
		Map<String,Object> output = new LinkedHashMap<>();
		long probeStarted = System.nanoTime();
		try {
			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, "true");
			boolean success = DMLScript.executeScript(new String[] {"-f", args[0], "-config", args[1],
				"-exec", "singlenode", "-seed", "2026072701", "-stats", "100", "-noFedRuntimeConversion"});
			if(!success)
				throw new IllegalStateException("DML execution returned false");
			String configured = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			if(!"compile_cost_based".equals(configured))
				throw new IllegalStateException("Wrong planner: " + configured);
			var observability = PlacementEmissionTransaction.observabilitySnapshot();
			if(observability.runtimeFallbackCount() != 0 || observability.runtimeRepairCount() != 0)
				throw new IllegalStateException("Runtime fallback/repair is forbidden: " + observability);

			NormalizedPlannerResult selected = selectedResult();
			List<Integer> assignment = assignment(selected.objectiveCertificate());
			Matcher objective = OBJECTIVE.matcher(selected.objectiveCertificate());
			if(!objective.find())
				throw new IllegalStateException("Selected objective certificate is missing objective bits");
			long certificateObjectiveBits = Long.parseLong(objective.group(1));

			Map<String,Long> heavyHitters = new LinkedHashMap<>();
			Map<String,Long> allHeavyHitters = new LinkedHashMap<>();
			Statistics.getCPHeavyHitterOpCodes().stream().sorted()
				.forEach(opcode -> {
					long count = Statistics.getCPHeavyHitterCount(opcode);
					allHeavyHitters.put(opcode, count);
					if(opcode.startsWith("fed_"))
						heavyHitters.put(opcode, count);
				});
			output.put("status", "passed");
			output.put("planner", selected.plannerId());
			output.put("predictedObjectiveMillis", Double.longBitsToDouble(certificateObjectiveBits));
			output.put("predictedObjectiveBits", Long.toUnsignedString(certificateObjectiveBits));
			output.put("objectiveCertificate", selected.objectiveCertificate());
			output.put("selectedAssignmentSize", assignment.size());
			output.put("selectedRelocations", descriptions(selected.selectedRelocations()));
			output.put("selectedLocalMaterializations", descriptions(selected.selectedLocalMaterializations()));
			output.put("compileNanos", Statistics.getCompileTime());
			output.put("executionNanos", Statistics.getRunTime());
			output.put("probeWallNanos", System.nanoTime() - probeStarted);
			output.put("federatedHeavyHitters", heavyHitters);
			output.put("allHeavyHitters", allHeavyHitters);
			output.put("federatedRequestCounts", FederatedStatistics.displayFedIOExecStatistics());
			output.put("networkTraffic", FederatedStatistics.displayNetworkTrafficStatistics());
			output.put("runtimeAudit", true);
			output.put("runtimeFallbackCount", observability.runtimeFallbackCount());
			output.put("runtimeRepairCount", observability.runtimeRepairCount());
			output.put("sourcePrivacy", "see run manifest");
		}
		catch(Throwable failure) {
			output.put("status", "failed");
			output.put("error", failure.toString());
			failure.printStackTrace();
		}
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), output);
		if(!"passed".equals(output.get("status")))
			System.exit(1);
	}

	private static NormalizedPlannerResult selectedResult() {
		var programs = PlacementEmissionTransaction.receiptSnapshotForTesting();
		if(programs.isEmpty())
			throw new IllegalStateException("No committed planner receipt");
		List<NormalizedPlannerResult> results = new ArrayList<>();
		for(var program : programs.keySet()) {
			NormalizedPlannerResult result = PlacementEmissionTransaction.currentNormalizedResult(program);
			if(result != null && "DP-LocalConflict".equals(result.plannerId()))
				results.add(result);
		}
		return results.stream().max(Comparator.comparingInt(result -> result.selectedStates().size()))
			.orElseThrow(() -> new IllegalStateException("No DP-LocalConflict result"));
	}

	private static List<Integer> assignment(String certificate) {
		Matcher matcher = ASSIGNMENT.matcher(certificate);
		if(!matcher.find())
			throw new IllegalStateException("No assignment in objective certificate");
		return Arrays.stream(matcher.group(1).split(","))
			.map(String::trim).map(Integer::valueOf).toList();
	}

	private static List<String> descriptions(List<?> values) {
		return values.stream().map(Object::toString).toList();
	}
}

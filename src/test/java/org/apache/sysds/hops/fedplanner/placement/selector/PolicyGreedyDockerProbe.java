/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.runtime.controlprogram.federated.FederatedStatistics;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.utils.Statistics;

/** Docker-only validation entrypoint. No exception-swallowing DMLScript.main success signal. */
public final class PolicyGreedyDockerProbe {
	private PolicyGreedyDockerProbe() { }
	public static void main(String[] args) throws Exception {
		if(args.length > 0 && args[0].equals("als")) {
			runAls(args);
			return;
		}
		if(args.length == 1 && args[0].equals("scaling")) {
			ObjectMapper json = new ObjectMapper();
			for(int size : new int[] {512, 4096, 16384, 65536}) {
				var graph = graph(size);
				for(int repeat = 0; repeat < 3; repeat++) {
					long start = System.nanoTime();
					var run = new PolicyGreedyPlacementSelector().selectWithMetrics(null, graph);
					long elapsed = System.nanoTime() - start;
					if(run.metrics().decisionCommits() != size || run.metrics().candidateChecks() != 2L * size
						|| run.metrics().supportIncidences() >= 32L * size
						|| run.selection().certificate().optimalityProven())
						throw new IllegalStateException("Greedy structural scaling contract failed");
					System.out.println(json.writeValueAsString(Map.of("nodes", size, "repeat", repeat,
						"elapsedNanos", elapsed, "metrics", run.metrics(), "kind", "synthetic-selector-not-full-analysis")));
				}
			}
			return;
		}
		if(args.length != 3) throw new IllegalArgumentException("script config expected-planner | scaling");
		System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, "true");
		if(!DMLScript.executeScript(new String[] {"-f", args[0], "-config", args[1], "-exec", "singlenode", "-stats"}))
			throw new IllegalStateException("DML execution returned false");
		String planner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		if(!args[2].equals(planner)) throw new IllegalStateException("Wrong planner: " + planner);
		var observability = PlacementEmissionTransaction.observabilitySnapshot();
		if(observability.runtimeFallbackCount() != 0 || observability.runtimeRepairCount() != 0)
			throw new IllegalStateException("Runtime fallback/repair is forbidden: " + observability);
		long fedCompute = Statistics.getCPHeavyHitterOpCodes().stream()
			.filter(opcode -> opcode.startsWith("fed_") && !opcode.startsWith("fed_fed"))
			.mapToLong(Statistics::getCPHeavyHitterCount).sum();
		if(!"NONE".equals(planner) && fedCompute == 0)
			throw new IllegalStateException("Federated validation did not execute a fed_ compute opcode");
		System.out.println("FEDPOLICY_RUNTIME_FALLBACK=0;repair=0;fedComputeInstructions=" + fedCompute);
		System.out.println(PlannerRuntimePlacementAudit.display().lines().findFirst().orElseThrow());
		// Sum of per-pool high-water marks, not a simultaneous total-heap peak.
		long heapPeaks = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
			.filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
			.mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
		System.out.println("FEDPOLICY_HEAP_POOL_PEAK_BYTES=" + heapPeaks);
		System.out.println("FEDPOLICY_PROBE_SUCCESS=" + planner + ";runtimeAudit=true");
	}

	private static void runAls(String[] args) throws Exception {
		if(args.length != 5)
			throw new IllegalArgumentException("als script config expected-planner result-json");
		Map<String,Object> output = new LinkedHashMap<>();
		long started = System.nanoTime();
		try {
			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, "true");
			if(!DMLScript.executeScript(new String[] {"-f", args[1], "-config", args[2], "-exec", "singlenode",
				"-seed", "2026072701", "-stats", "100"}))
				throw new IllegalStateException("DML execution returned false");
			String planner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			if(!args[3].equals(planner))
				throw new IllegalStateException("Wrong planner: " + planner);
			var observability = PlacementEmissionTransaction.observabilitySnapshot();
			if(observability.runtimeFallbackCount() != 0 || observability.runtimeRepairCount() != 0)
				throw new IllegalStateException("Runtime fallback/repair is forbidden: " + observability);

			Map<String,Long> allHeavyHitters = new LinkedHashMap<>();
			Map<String,Long> federatedHeavyHitters = new LinkedHashMap<>();
			Map<String,Long> federatedComputeHeavyHitters = new LinkedHashMap<>();
			Statistics.getCPHeavyHitterOpCodes().stream().sorted().forEach(opcode -> {
				long count = Statistics.getCPHeavyHitterCount(opcode);
				allHeavyHitters.put(opcode, count);
				if(opcode.startsWith("fed_")) {
					federatedHeavyHitters.put(opcode, count);
					if(!opcode.startsWith("fed_fed"))
						federatedComputeHeavyHitters.put(opcode, count);
				}
			});
			boolean expectFederated = !"NONE".equals(args[3]);
			if(!expectFederated && !federatedHeavyHitters.isEmpty())
				throw new IllegalStateException("CP reference executed FED opcodes: " + federatedHeavyHitters);
			if(expectFederated && federatedComputeHeavyHitters.isEmpty())
				throw new IllegalStateException("FedAll execution did not execute a fed_ compute opcode");

			output.put("status", "passed");
			output.put("planner", planner);
			output.put("compileNanos", Statistics.getCompileTime());
			output.put("executionNanos", Statistics.getRunTime());
			output.put("probeWallNanos", System.nanoTime() - started);
			output.put("allHeavyHitters", allHeavyHitters);
			output.put("federatedHeavyHitters", federatedHeavyHitters);
			output.put("federatedComputeHeavyHitters", federatedComputeHeavyHitters);
			output.put("federatedRequestCounts", FederatedStatistics.displayFedIOExecStatistics());
			output.put("networkTraffic", FederatedStatistics.displayNetworkTrafficStatistics());
			output.put("runtimeAudit", PlannerRuntimePlacementAudit.display());
			output.put("runtimeFallbackCount", observability.runtimeFallbackCount());
			output.put("runtimeRepairCount", observability.runtimeRepairCount());
		}
		catch(Throwable failure) {
			output.put("status", "failed");
			output.put("error", failure.toString());
			failure.printStackTrace();
		}
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[4]).toFile(), output);
		if(!"passed".equals(output.get("status")))
			System.exit(1);
	}

	private static NeutralPlacementGraph graph(int size) {
		List<Node> nodes = new ArrayList<>();
		List<Constraint> edges = new ArrayList<>();
		var region = new ControlRegionKey("docker-depth", "main", List.of("main"), "root", "compiled");
		for(int i = 0; i < size; i++) {
			var key = new CompiledHopKey("docker-depth", "main", "root", "compiled", region,
				String.format("hop-%06d", i), "origin-" + i);
			nodes.add(new Node(key, NodeKind.OPERATION, new ValueVersionKey("docker-depth", "v" + i,
				region, i, VersionKind.ORDINARY, List.of()), true, List.of(
					new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false),
					new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false)), List.of(), List.of()));
			if(i > 0) {
				edges.add(new Constraint(ConstraintKind.DOMINATES, nodes.get(i-1).key(), key, 0, "data-input"));
				edges.add(new Constraint(ConstraintKind.CONJUNCTIVE, nodes.get(i-1).key(), key, 0, "value-flow"));
			}
		}
		return new NeutralPlacementGraph(nodes, edges, List.of());
	}
}

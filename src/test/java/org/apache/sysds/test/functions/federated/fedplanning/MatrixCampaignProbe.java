/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.functions.federated.fedplanning;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.utils.Statistics;

/**
 * Error-aware, single-invocation entry point for the federated planner matrix campaign.
 *
 * <p>The compile mode deliberately uses the regular {@link DMLScript#executeScript(String[])}
 * pipeline with statistics enabled.  Its configuration must enable
 * {@code sysds.benchmark.compile_only}; that production branch returns only after runtime-program
 * construction, before {@code ScriptExecutorUtils.executeRuntimeProgram}.  This is not the
 * search-space-only diagnostic path.</p>
 */
public final class MatrixCampaignProbe {
	private static final String SCHEMA = "matrix-campaign-probe-v1";
	private static final Pattern CANDIDATE_RECEIPT = Pattern.compile(
		"(?m)^CandidateE2EReceipt schema=candidate-e2e-v1 calls=(\\d+) exactPhaseCalls=(\\d+)(.*)$");
	private static final Pattern NANOS_FIELD = Pattern.compile(" ([A-Za-z][A-Za-z0-9]*)=(\\d+)");
	private static final Pattern AUDIT_SUMMARY = Pattern.compile(
		"(?m)^\\[PlannerRuntimeAudit]\\[Summary] plan=(\\S+) authorityGenerations=(\\d+)"
			+ " plannedHops=(\\d+) plannedPhysicalHops=(\\d+) loweredPhysicalHops=(\\d+)"
			+ " missingPhysicalHops=(\\d+) plannedSynthetic=(\\d+) missingSynthetic=(\\d+)"
			+ " loweringRecords=(\\d+) runtimeInstructionKinds=(\\d+) federatedDispatchKinds=(\\d+)"
			+ " workerFragmentKinds=(\\d+) mismatches=(\\d+)$");

	private MatrixCampaignProbe() {
		// utility class
	}

	public static void main(String[] args) throws Throwable {
		Options options = Options.parse(args);
		Map<String, Object> receipt = baseReceipt(options);
		long wallStarted = System.nanoTime();
		try {
			DMLConfig requestedConfig = new DMLConfig(options.config().toString(), false);
			boolean configuredCompileOnly = requestedConfig.getBooleanValue(DMLConfig.BENCHMARK_COMPILE_ONLY);
			String configuredPlanner = requestedConfig.getTextValue(DMLConfig.FEDERATED_PLANNER);
			receipt.put("configuredCompileOnly", configuredCompileOnly);
			receipt.put("configuredPlanner", configuredPlanner);
			if(configuredCompileOnly != (options.mode() == Mode.COMPILE))
				throw new IllegalArgumentException("Mode " + options.mode().cliName
					+ " requires sysds.benchmark.compile_only=" + (options.mode() == Mode.COMPILE));
			if(!options.planner().equals(canonicalPlanner(configuredPlanner)))
				throw new IllegalArgumentException("Expected planner " + options.planner()
					+ " but config declares " + configuredPlanner);
			boolean runtimeAuditEnabled = PlannerRuntimePlacementAudit.isEnabled();
			receipt.put("runtimeAuditEnabled", runtimeAuditEnabled);
			if(!runtimeAuditEnabled)
				throw new IllegalStateException("Planner runtime audit must be enabled with -D"
					+ PlannerRuntimePlacementAudit.PROPERTY + "=true");

			Statistics.reset();
			Statistics.resetNoOfExecutedJobs();
			List<String> dmlArgs = new ArrayList<>(List.of("-f", options.script().toString(),
				"-config", options.config().toString(), "-exec", "singlenode", "-stats"));
			if(options.seed() != null) {
				DMLScript.SEED = Math.toIntExact(options.seed());
				dmlArgs.add("-seed");
				dmlArgs.add(Long.toString(options.seed()));
			}
			boolean returned = DMLScript.executeScript(dmlArgs.toArray(String[]::new));
			if(!returned)
				throw new IllegalStateException("DMLScript.executeScript returned false");

			String actualPlanner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			boolean actualCompileOnly = ConfigurationManager.getDMLConfig()
				.getBooleanValue(DMLConfig.BENCHMARK_COMPILE_ONLY);
			if(!options.planner().equals(canonicalPlanner(actualPlanner)))
				throw new IllegalStateException("Executed with planner " + actualPlanner
					+ ", expected " + options.planner());
			if(actualCompileOnly != configuredCompileOnly)
				throw new IllegalStateException("Compile-only configuration changed during execution");

			long planningNanos = Statistics.getPlanningFullInitialTime();
			if(planningNanos < 0)
				throw new IllegalStateException("No successful full runtime-program construction receipt");
			long runtimeProgramNanos = Statistics.getCompilePhaseRuntimeProgramTime();
			if(runtimeProgramNanos <= 0)
				throw new IllegalStateException("Runtime-program construction phase was not observed");
			String statistics = Statistics.display(1);
			Map<String, Long> candidate = candidateReceipt(statistics);
			if(candidate.isEmpty())
				throw new IllegalStateException("No CandidateE2EReceipt was published by planner " + actualPlanner);
			AuditSummary audit = auditSummary(statistics);
			long observedRunNanos = Statistics.getRunTime();
			long executedSparkInstructions = Statistics.getNoOfExecutedSPInst();
			receipt.put("observedRunNanos", observedRunNanos);
			receipt.put("executedSparkInstructions", executedSparkInstructions);
			receipt.put("plannerRuntimeAuditSummary", audit.raw());
			receipt.put("plannerRuntimeAudit", audit.values());
			validateExecutionEvidence(options.mode(), observedRunNanos, executedSparkInstructions, audit);

			receipt.put("status", "success");
			receipt.put("executeScriptReturned", true);
			receipt.put("statisticsEnabled", DMLScript.STATISTICS);
			receipt.put("actualPlanner", actualPlanner);
			receipt.put("actualPlannerCanonical", canonicalPlanner(actualPlanner));
			receipt.put("actualCompileOnly", actualCompileOnly);
			receipt.put("compileNanos", Statistics.getCompileTime());
			receipt.put("planningFullInitialNanos", planningNanos);
			receipt.put("compilePhasesNanos", compilePhases());
			receipt.put("candidateE2E", candidate);
			receipt.put("analysisNanos", candidate.get("analysisNanos"));
			receipt.put("searchSpaceNanos", Math.addExact(candidate.get("commonPreparationNanos"),
				candidate.get("analysisNanos")));
			receipt.put("selectionNanos", candidate.get("selectionNanos"));
			receipt.put("candidatePlanningNanos", candidatePlanningNanos(candidate));
			receipt.put("runtimeProgramConstructed", true);
			receipt.put("runtimeProgramConstructionEvidence",
				"successful production planning receipt and positive RuntimeProgram phase time");
			receipt.put("workloadExecutionStarted", options.mode() == Mode.RUNTIME);
			receipt.put("workloadExecutionCompleted", options.mode() == Mode.RUNTIME);
			receipt.put("executionNanos", observedRunNanos);
			receipt.put("compileOnlyExecutionEvidence", options.mode() == Mode.COMPILE
				? "production compile_only return after runtime-program construction and before runtime execution"
				: "not-applicable");
		}
		catch(Throwable failure) {
			receipt.put("status", "failure");
			receipt.put("errorClass", failure.getClass().getName());
			receipt.put("errorMessage", String.valueOf(failure.getMessage()));
			try {
				receipt.put("compileNanos", Statistics.getCompileTime());
				receipt.put("planningFullInitialNanos", Statistics.getPlanningFullInitialTime());
			}
			catch(Throwable statisticsFailure) {
				failure.addSuppressed(statisticsFailure);
			}
			receipt.put("wallNanos", System.nanoTime() - wallStarted);
			try {
				writeReceipt(options.receipt(), receipt);
			}
			catch(Throwable receiptFailure) {
				failure.addSuppressed(receiptFailure);
			}
			throw failure;
		}
		receipt.put("wallNanos", System.nanoTime() - wallStarted);
		writeReceipt(options.receipt(), receipt);
	}

	private static Map<String, Object> baseReceipt(Options options) throws IOException {
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("schema", SCHEMA);
		receipt.put("status", "started");
		receipt.put("mode", options.mode().cliName);
		receipt.put("script", options.script().toRealPath().toString());
		receipt.put("config", options.config().toRealPath().toString());
		receipt.put("expectedPlanner", options.planner());
		if(options.seed() != null)
			receipt.put("seed", options.seed());
		return receipt;
	}

	private static Map<String, Long> compilePhases() {
		Map<String, Long> phases = new LinkedHashMap<>();
		phases.put("parseNanos", Statistics.getCompilePhaseParseTime());
		phases.put("hopsBuildNanos", Statistics.getCompilePhaseHopsBuildTime());
		phases.put("hopsRewriteNanos", Statistics.getCompilePhaseHopsRewriteTime());
		phases.put("lopsBuildNanos", Statistics.getCompilePhaseLopsBuildTime());
		phases.put("lopsRewriteNanos", Statistics.getCompilePhaseLopsRewriteTime());
		phases.put("runtimeProgramNanos", Statistics.getCompilePhaseRuntimeProgramTime());
		phases.put("fedPlannerNanos", Statistics.getCompilePhaseFedPlannerTime());
		phases.put("fedPlannerCalls", Statistics.getCompilePhaseFedPlannerCalls());
		phases.put("observedHops", Statistics.getCompileObservedHops());
		return phases;
	}

	private static String canonicalPlanner(String planner) {
		return FederatedPlanner.valueOf(planner.toUpperCase(java.util.Locale.ROOT)).name();
	}

	private static long candidatePlanningNanos(Map<String, Long> candidate) {
		return List.of("plannerSetupNanos", "modelNanos", "costSurfaceNanos", "optimizerNanos",
			"selectionNanos", "otherPlanningNanos").stream().mapToLong(key -> candidate.getOrDefault(key, 0L)).sum();
	}

	private static Map<String, Long> candidateReceipt(String statistics) {
		Matcher receipt = CANDIDATE_RECEIPT.matcher(statistics);
		if(!receipt.find())
			return Map.of();
		Map<String, Long> values = new LinkedHashMap<>();
		values.put("calls", Long.parseLong(receipt.group(1)));
		values.put("exactPhaseCalls", Long.parseLong(receipt.group(2)));
		Matcher fields = NANOS_FIELD.matcher(receipt.group(3));
		while(fields.find())
			values.put(fields.group(1), Long.parseLong(fields.group(2)));
		return values;
	}

	private static AuditSummary auditSummary(String statistics) {
		Matcher summary = AUDIT_SUMMARY.matcher(statistics);
		if(!summary.find())
			throw new IllegalStateException("Planner runtime audit Summary was not published");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("plan", summary.group(1));
		String[] names = {"authorityGenerations", "plannedHops", "plannedPhysicalHops",
			"loweredPhysicalHops", "missingPhysicalHops", "plannedSynthetic", "missingSynthetic",
			"loweringRecords", "runtimeInstructionKinds", "federatedDispatchKinds",
			"workerFragmentKinds", "mismatches"};
		for(int i = 0; i < names.length; i++)
			values.put(names[i], Long.parseLong(summary.group(i + 2)));
		return new AuditSummary(summary.group(), values);
	}

	private static void validateExecutionEvidence(Mode mode, long runNanos, long executedSparkInstructions,
		AuditSummary audit) {
		long plannedPhysical = audit.value("plannedPhysicalHops");
		long loweredPhysical = audit.value("loweredPhysicalHops");
		long runtimeKinds = audit.value("runtimeInstructionKinds");
		long dispatchKinds = audit.value("federatedDispatchKinds");
		long workerKinds = audit.value("workerFragmentKinds");
		if(audit.value("mismatches") != 0 || audit.value("missingPhysicalHops") != 0
			|| audit.value("missingSynthetic") != 0)
			throw new IllegalStateException("Planner runtime audit reports missing or mismatched lowering");
		if(plannedPhysical <= 0 || loweredPhysical != plannedPhysical)
			throw new IllegalStateException("Planner runtime audit lacks complete physical lowering evidence");
		if(mode == Mode.COMPILE) {
			if(runNanos != 0 || executedSparkInstructions != 0)
				throw new IllegalStateException("Compile-only mode observed workload execution statistics");
			if(runtimeKinds != 0 || dispatchKinds != 0 || workerKinds != 0)
				throw new IllegalStateException("Compile-only mode observed runtime audit execution records");
		}
		else if(runtimeKinds <= 0)
			throw new IllegalStateException("Runtime mode produced no runtime instruction audit evidence");
	}

	private static void writeReceipt(Path destination, Map<String, Object> receipt) throws IOException {
		Path absolute = destination.toAbsolutePath().normalize();
		Path parent = absolute.getParent();
		if(parent != null)
			Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
		try {
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), receipt);
			try {
				Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch(AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private enum Mode {
		COMPILE("compile"), RUNTIME("runtime");
		private final String cliName;
		Mode(String cliName) { this.cliName = cliName; }
		private static Mode parse(String value) {
			for(Mode mode : values())
				if(mode.cliName.equals(value))
					return mode;
			throw new IllegalArgumentException("Unknown mode: " + value + " (expected compile or runtime)");
		}
	}

	private record AuditSummary(String raw, Map<String, Object> values) {
		private long value(String key) {
			return ((Number) values.get(key)).longValue();
		}
	}

	private record Options(Path script, Path config, String planner, Mode mode, Path receipt, Long seed) {
		private static Options parse(String[] args) {
			Map<String, String> values = new LinkedHashMap<>();
			for(int i = 0; i < args.length; i += 2) {
				if(i + 1 >= args.length || !args[i].startsWith("--"))
					throw usage();
				String previous = values.put(args[i], args[i + 1]);
				if(previous != null)
					throw new IllegalArgumentException("Duplicate option: " + args[i]);
			}
			for(String key : values.keySet())
				if(!List.of("--script", "--config", "--planner", "--mode", "--receipt", "--seed").contains(key))
					throw new IllegalArgumentException("Unknown option: " + key);
			Path script = Path.of(required(values, "--script")).toAbsolutePath().normalize();
			Path config = Path.of(required(values, "--config")).toAbsolutePath().normalize();
			Path receipt = Path.of(required(values, "--receipt")).toAbsolutePath().normalize();
			if(!Files.isRegularFile(script))
				throw new IllegalArgumentException("Script is not a regular file: " + script);
			if(!Files.isRegularFile(config))
				throw new IllegalArgumentException("Config is not a regular file: " + config);
			Long seed = values.containsKey("--seed") ? Long.valueOf(values.get("--seed")) : null;
			return new Options(script, config, canonicalPlanner(required(values, "--planner")),
				Mode.parse(required(values, "--mode")), receipt, seed);
		}

		private static String required(Map<String, String> values, String key) {
			String value = values.get(key);
			if(value == null || value.isBlank())
				throw usage();
			return value;
		}

		private static IllegalArgumentException usage() {
			return new IllegalArgumentException("Usage: MatrixCampaignProbe --script FILE --config FILE"
				+ " --planner NAME --mode compile|runtime --receipt FILE [--seed LONG]");
		}
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.CacheStatistics;
import org.apache.sysds.runtime.controlprogram.caching.LazyWriteBuffer;
import org.apache.sysds.runtime.controlprogram.caching.UnifiedMemoryManager;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedStatistics;
import org.apache.sysds.runtime.controlprogram.federated.FederatedUDF;
import org.apache.sysds.runtime.controlprogram.federated.RefedReuseAudit;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.lineage.LineageItem;
import org.apache.sysds.utils.Statistics;

/**
 * Docker-only diagnostic that proves an automatically selected physical plan against the
 * production cost surface and records the corresponding runtime observations.
 */
public final class AutomaticSupplySharingDockerProbe {
	private static final String SCHEMA = "automatic-supply-sharing-probe-v1";
	private static final Pattern ASSIGNMENT = Pattern.compile("(?:^|;)assignment=\\[([^]]*)]");
	private static final Pattern OBJECTIVE = Pattern.compile(
		"(?:physical-ve|local-conflict)-objective=(-?[0-9]+)");
	private static final Pattern COST_SURFACE = Pattern.compile("(?:^|;)costSurface=([^;]+)");
	private static final Pattern FED_IO = Pattern.compile(
		"Federated I/O \\(Read, Put, Get\\):\\s*([0-9]+)/([0-9]+)/([0-9]+)\\.");
	private static final String FLAT_DIAGNOSTIC_PROPERTY =
		"sysds.fed.supply.flat.diagnostic";

	private AutomaticSupplySharingDockerProbe() {
		// utility class
	}

	public static void main(String[] args) throws Throwable {
		if(args.length == 2 && "--observe-workers".equals(args[0])) {
			observeWorkers(Path.of(args[1]));
			return;
		}
		if(args.length == 3 && "--worker-cache".equals(args[0])) {
			runCacheEnabledWorker(args[1], args[2]);
			return;
		}
		if(args.length != 3)
			throw new IllegalArgumentException(
				"script config result-json | --observe-workers result-json | "
					+ "--worker-cache config port expected");
		Path resultPath = Path.of(args[2]);
		Map<String,Object> output = new LinkedHashMap<>();
		output.put("schema", SCHEMA);
		output.put("status", "started");
		output.put("script", Path.of(args[0]).toAbsolutePath().normalize().toString());
		output.put("config", Path.of(args[1]).toAbsolutePath().normalize().toString());
		long probeStarted = System.nanoTime();
		Throwable failure = null;
		try {
			DMLConfig requested = new DMLConfig(args[1], false);
			String configuredPlanner = requested.getTextValue(DMLConfig.FEDERATED_PLANNER);
			String expectedPlanner = expectedPlanner(configuredPlanner);
			output.put("configuredPlanner", configuredPlanner);
			output.put("expectedNormalizedPlanner", expectedPlanner);

			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, "true");
			System.setProperty(RefedReuseAudit.PROPERTY, "true");
			RefedReuseAudit.reset();
			boolean success = DMLScript.executeScript(new String[] {"-f", args[0], "-config", args[1],
				"-exec", "singlenode", "-seed", "7", "-stats", "100",
				"-noFedRuntimeConversion", "-explain", "runtime"});
			if(!success)
				throw new IllegalStateException("DMLScript.executeScript returned false");

			var observability = PlacementEmissionTransaction.observabilitySnapshot();
			if(observability.runtimeFallbackCount() != 0 || observability.runtimeRepairCount() != 0)
				throw new IllegalStateException("Runtime fallback/repair is forbidden: " + observability);

			List<NormalizedPlannerResult> committed = committedResults(expectedPlanner);
			NormalizedPlannerResult selected = committed.stream()
				.max(Comparator.comparingInt(result -> result.selectedStates().size()))
				.orElseThrow(() -> new IllegalStateException(
					"No committed normalized result for " + expectedPlanner));
			RefedReuseAudit.Snapshot reuseAudit = RefedReuseAudit.snapshot();
			if(!reuseAudit.enabled() || reuseAudit.droppedEvents() != 0
				|| reuseAudit.loggingFailures() != 0)
				throw new IllegalStateException("REFED reuse audit is incomplete: " + reuseAudit);
			CanonicalProof proof = canonicalProof(selected, reuseAudit);
			if(!proof.objectiveMatches() || !proof.costSurfaceMatches()
				|| !proof.selectedStatesMatch() || !proof.sharedLifetimesMatch())
				throw new IllegalStateException("Selected plan failed canonical reconstruction: " + proof.summary());

			output.put("normalizedPlanner", selected.plannerId());
			output.put("analysisFingerprint", selected.analysisFingerprint());
			output.put("normalizedPlanFingerprint", selected.normalizedPlanFingerprint());
			output.put("objectiveCertificate", selected.objectiveCertificate());
			output.put("committedResultCount", committed.size());
			output.put("committedResults", committed.stream().map(
				AutomaticSupplySharingDockerProbe::committedResult).toList());
			output.put("canonicalProof", proof.summary());
			if(proof.flatUpdatedDiagnostic() != null) {
				output.put("flatUpdatedDiagnostic", proof.flatUpdatedDiagnostic());
				if(!Boolean.TRUE.equals(proof.flatUpdatedDiagnostic()
					.get("commonRelocationCompletionLegal")))
					throw new IllegalStateException(
						"Flat updated common relocation has no legal full assignment");
				if(!Boolean.TRUE.equals(proof.flatUpdatedDiagnostic()
					.get("selectedCommonRelocation")))
					throw new IllegalStateException(
						"Flat updated plan did not select one common QB0/QC0 relocation");
				if("Exact".equals(selected.plannerId()) && Boolean.TRUE.equals(proof.flatUpdatedDiagnostic()
					.get("cheaperLegalAlternativeFound")))
					throw new IllegalStateException(
						"Flat updated exact plan is more expensive than a legal relocation plan");
			}
			output.put("selectedOccurrences", proof.selectedOccurrences());
			output.put("selectedRelocations", proof.selectedRelocations());
			output.put("selectedCandidateSelections",
				selected.selectedCandidateSelections().stream().map(Object::toString).toList());
			List<?> selectedLocalMaterializations = selected.selectedLocalMaterializations();
			output.put("selectedLocalMaterializations",
				selectedLocalMaterializations.stream().map(Object::toString).toList());
			output.put("sharedSupplyLifetimes", selected.sharedSupplyLifetimes().stream().sorted().toList());
			output.put("refedReuseAudit", reuseAudit);

			String fedIo = FederatedStatistics.displayFedIOExecStatistics();
			Map<String,Long> fedIoCounts = federatedIoCounts(fedIo);
			Map<String,Long> heavyHitters = new LinkedHashMap<>();
			Statistics.getCPHeavyHitterOpCodes().stream().sorted().forEach(opcode ->
				heavyHitters.put(opcode, Statistics.getCPHeavyHitterCount(opcode)));
			Map<String,Object> coordinatorMemory = coordinatorMemory();

			output.put("federatedIo", fedIoCounts);
			output.put("federatedIoRaw", fedIo);
			output.put("federatedHeavyHitters", heavyHitters.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("fed_"))
				.collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()),
					LinkedHashMap::putAll));
			output.put("allHeavyHitters", heavyHitters);
			output.put("compileNanos", Statistics.getCompileTime());
			output.put("executionNanos", Statistics.getRunTime());
			output.put("coordinatorMemory", coordinatorMemory);
			output.put("coordinatorGcCount", Statistics.getJVMgcCount());
			output.put("coordinatorGcMillis", Statistics.getJVMgcTime());
			output.put("workerObservationReceipt",
				"deferred to a fresh --observe-workers JVM to preserve production runtime authority");
			output.put("runtimeFallbackCount", observability.runtimeFallbackCount());
			output.put("runtimeRepairCount", observability.runtimeRepairCount());
			output.put("status", "passed");
		}
		catch(Throwable thrown) {
			failure = thrown;
			output.put("status", "failed");
			output.put("errorClass", thrown.getClass().getName());
			output.put("errorMessage", String.valueOf(thrown.getMessage()));
			output.put("stackTrace", Arrays.stream(thrown.getStackTrace()).map(Object::toString).toList());
			if(RefedReuseAudit.isEnabled())
				output.put("refedReuseAudit", RefedReuseAudit.snapshot());
		}
		failure = clearClientResources(output, failure);
		output.put("probeWallNanos", System.nanoTime() - probeStarted);
		try {
			writeJson(resultPath, output);
		}
		catch(Throwable writeFailure) {
			if(failure != null)
				failure.addSuppressed(writeFailure);
			else
				throw writeFailure;
		}
		if(failure != null)
			throw failure;
	}

	/**
	 * Test-only worker startup that activates the standard SystemDS cache before entering the
	 * ordinary federated-worker server loop. Production {@code -w} startup intentionally remains
	 * unchanged; this mode exists solely to measure physical worker spill/restore separately from
	 * coordinator-side planned-copy lifetime.
	 */
	private static void runCacheEnabledWorker(String config, String port) throws Throwable {
		DMLScript.loadConfiguration(config);
		CacheableData.initCaching();
		Throwable failure = null;
		try {
			boolean success = DMLScript.executeScript(new String[] {
				"-w", String.valueOf(Integer.parseInt(port)), "-config", config, "-stats", "100"});
			if(!success)
				throw new IllegalStateException("Cache-enabled federated worker returned false");
		}
		catch(Throwable thrown) {
			failure = thrown;
		}
		finally {
			try {
				CacheableData.cleanupCacheDir();
			}
			catch(Throwable cleanupFailure) {
				if(failure != null)
					failure.addSuppressed(cleanupFailure);
				else
					failure = cleanupFailure;
			}
		}
		if(failure != null)
			throw failure;
	}

	private static void observeWorkers(Path resultPath) throws Throwable {
		Map<String,Object> output = new LinkedHashMap<>();
		output.put("schema", "automatic-supply-sharing-worker-observation-v1");
		output.put("status", "started");
		Throwable failure = null;
		try {
			output.put("workerStatisticsCollection",
				"fresh coordinator JVM; WorkerObservationFunction has empty input ids and acquires no MatrixBlocks");
			output.put("workerObservations", collectWorkerObservations());
			output.put("observationLimitation",
				"post-DML snapshot; runtime CLEAR/source cleanup may precede it, while cache and GC counters are cumulative for each worker JVM");
			output.put("status", "passed");
		}
		catch(Throwable thrown) {
			failure = thrown;
			output.put("status", "failed");
			output.put("errorClass", thrown.getClass().getName());
			output.put("errorMessage", String.valueOf(thrown.getMessage()));
			output.put("stackTrace", Arrays.stream(thrown.getStackTrace()).map(Object::toString).toList());
		}
		failure = clearClientResources(output, failure);
		try {
			writeJson(resultPath, output);
		}
		catch(Throwable writeFailure) {
			if(failure != null)
				failure.addSuppressed(writeFailure);
			else
				throw writeFailure;
		}
		if(failure != null)
			throw failure;
	}

	private static Throwable clearClientResources(Map<String,Object> output, Throwable failure) {
		try {
			// Close only this JVM's client pool/event loops. Never send worker CLEAR here.
			FederatedData.clearWorkGroup();
			return failure;
		}
		catch(Throwable cleanupFailure) {
			output.put("clientCleanupError", cleanupFailure.toString());
			if(failure != null) {
				failure.addSuppressed(cleanupFailure);
				return failure;
			}
			output.put("status", "failed");
			output.put("errorClass", cleanupFailure.getClass().getName());
			output.put("errorMessage", String.valueOf(cleanupFailure.getMessage()));
			output.put("stackTrace", Arrays.stream(cleanupFailure.getStackTrace())
				.map(Object::toString).toList());
			return cleanupFailure;
		}
	}

	private static String expectedPlanner(String configured) {
		return switch(configured) {
			case "compile_cost_based" -> "DP-LocalConflict";
			case "compile_exact" -> "Exact";
			default -> throw new IllegalArgumentException(
				"Probe supports compile_cost_based and compile_exact, got " + configured);
		};
	}

	private static List<NormalizedPlannerResult> committedResults(String expectedPlanner) {
		List<NormalizedPlannerResult> results = new ArrayList<>();
		for(DMLProgram program : PlacementEmissionTransaction.receiptSnapshotForTesting().keySet()) {
			NormalizedPlannerResult result = PlacementEmissionTransaction.currentNormalizedResult(program);
			if(result != null && expectedPlanner.equals(result.plannerId()))
				results.add(result);
		}
		results.sort(Comparator.comparing(NormalizedPlannerResult::normalizedPlanFingerprint));
		return List.copyOf(results);
	}

	private static Map<String,Object> committedResult(NormalizedPlannerResult result) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("planner", result.plannerId());
		out.put("analysisFingerprint", result.analysisFingerprint());
		out.put("normalizedPlanFingerprint", result.normalizedPlanFingerprint());
		out.put("selectedStateCount", result.selectedStates().size());
		out.put("selectedRelocationCount", result.selectedRelocations().size());
		out.put("sharedSupplyLifetimeCount", result.sharedSupplyLifetimes().size());
		out.put("objectiveCertificate", result.objectiveCertificate());
		return out;
	}

	private static CanonicalProof canonicalProof(NormalizedPlannerResult selected,
		RefedReuseAudit.Snapshot reuseAudit) {
		PlacementAnalysis analysis = selected.analysis();
		analysis.assertProgramStructureUnchanged();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<Integer> assignment = assignment(selected.objectiveCertificate());
		if(assignment.size() != model.domains().size())
			throw new IllegalStateException("Certificate assignment size " + assignment.size()
				+ " differs from production model size " + model.domains().size());
		long certificateBits = objectiveBits(selected.objectiveCertificate());
		long recostBits = surface.evaluateCanonical(assignment);
		String certificateSurface = costSurface(selected.objectiveCertificate());
		boolean selectedStatesMatch = true;
		List<Map<String,Object>> occurrences = new ArrayList<>(model.domains().size());
		IdentityHashMap<Object,Integer> domainPositions = new IdentityHashMap<>();
		for(int position = 0; position < model.domains().size(); position++) {
			var domain = model.domains().get(position);
			domainPositions.put(domain.node().key(), position);
			int value = assignment.get(position);
			if(value < 0 || value >= domain.alternatives().size())
				throw new IllegalStateException("Certificate assignment is outside domain " + position);
			var alternative = domain.alternatives().get(value);
			selectedStatesMatch &= selected.selectedStates().get(domain.node().key()) == alternative.state();
			occurrences.add(selectedOccurrence(analysis, domain, alternative, position, value));
		}
		if(selected.selectedStates().size() != model.domains().size())
			selectedStatesMatch = false;
		Set<String> derivedShared = surface.selectedSharedSupplyLifetimes(assignment);
		List<Map<String,Object>> relocations = selected.selectedRelocations().stream()
			.sorted().map(action -> selectedRelocation(analysis, model, surface, assignment,
				domainPositions, action, selected.sharedSupplyLifetimes(), reuseAudit)).toList();
		Map<String,Object> flatUpdatedDiagnostic = Boolean.getBoolean(FLAT_DIAGNOSTIC_PROPERTY)
			? flatUpdatedDiagnostic(analysis, model, surface, assignment) : null;
		Map<String,Long> modelCounts = new LinkedHashMap<>();
		modelCounts.put("decisionCount", (long)model.domains().size());
		modelCounts.put("totalAlternativeCount", model.domains().stream()
			.mapToLong(domain -> domain.alternatives().size()).sum());
		modelCounts.put("hardFactorCount", (long)model.hardFactors().size());
		modelCounts.put("canonicalCostFactorCount", (long)surface.contributions().size());
		modelCounts.put("exactSolverVariableCount", (long)surface.exactSolverVariables().size());
		modelCounts.put("exactSolverAuxiliaryVariableCount",
			(long)surface.exactSolverVariables().size() - model.variables().size());
		modelCounts.put("exactSolverFactorCount", (long)surface.exactSolverFactors().size());
		return new CanonicalProof(certificateBits, recostBits,
			certificateSurface.equals(surface.contributionFingerprint()), selectedStatesMatch,
			derivedShared.equals(selected.sharedSupplyLifetimes()), surface.contributionFingerprint(),
			assignment, List.copyOf(occurrences), relocations, flatUpdatedDiagnostic,
			Map.copyOf(modelCounts));
	}

	private static Map<String,Object> flatUpdatedDiagnostic(PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface,
		List<Integer> selectedAssignment) {
		ExactPhysicalModel.DecisionDomain qb = uniqueNamedDomain(analysis, model, "QB0");
		ExactPhysicalModel.DecisionDomain qc = uniqueNamedDomain(analysis, model, "QC0");
		ExactPhysicalModel.DecisionDomain qm = uniqueNamedOpcodeDomain(analysis, model, "QM0", "b(/)");
		List<RelocationAlternative> qbRelocations = relocationAlternatives(qb, 1, "fed-init:UB");
		List<RelocationAlternative> qcRelocations = relocationAlternatives(qc, 1, "fed-init:UB");
		Set<String> commonIdentities = new LinkedHashSet<>();
		qbRelocations.forEach(relocation -> commonIdentities.add(relocation.physicalIdentity()));
		commonIdentities.retainAll(qcRelocations.stream()
			.map(RelocationAlternative::physicalIdentity).collect(java.util.stream.Collectors.toSet()));
		if(commonIdentities.isEmpty())
			throw new IllegalStateException(
				"Flat updated diagnostic found no common S0 -> fed-init:UB FOUT relocation for QB0/QC0");

		double selectedHard = RegionalSearchProblem.evaluateFactors(
			model.variables(), model.hardFactors(), selectedAssignment);
		double selectedCost = Double.longBitsToDouble(surface.evaluateCanonical(selectedAssignment));
		Set<String> selectedQb = selectedRelocationIdentities(
			qbRelocations, selectedAssignment.get(model.domains().indexOf(qb)));
		Set<String> selectedQc = selectedRelocationIdentities(
			qcRelocations, selectedAssignment.get(model.domains().indexOf(qc)));
		Set<String> selectedCommonIdentities = new LinkedHashSet<>(selectedQb);
		selectedCommonIdentities.retainAll(selectedQc);
		selectedCommonIdentities.retainAll(commonIdentities);
		List<Map<String,Object>> attempts = new ArrayList<>();
		Map<String,Object> best = null;
		double bestForcedCost = Double.POSITIVE_INFINITY;
		double bestForcedHard = Double.POSITIVE_INFINITY;
		for(String identity : commonIdentities) {
			Map<String,Object> attempt = new LinkedHashMap<>();
			attempt.put("physicalEmissionIdentity", identity);
			ExactCategoricalSolver.Factor forceQb = forceAlternatives(
				qb.variable(), matchingValues(qbRelocations, identity));
			ExactCategoricalSolver.Factor forceQc = forceAlternatives(
				qc.variable(), matchingValues(qcRelocations, identity));
			Set<Integer> compatibleQm = compatibleParentAlternatives(qm, qb,
				matchingValues(qbRelocations, identity), qc,
				matchingValues(qcRelocations, identity));
			attempt.put("compatibleParentAlternativeCount", compatibleQm.size());
			attempt.put("individualCompletions", List.of(
				forcedCompletion("QB0", model, surface, forceQb),
				forcedCompletion("QC0", model, surface, forceQc)));
			if(!compatibleQm.isEmpty()) {
				ExactCategoricalSolver.Factor forceQm = forceAlternatives(qm.variable(), compatibleQm);
				Map<String,Object> parentCompletion = forcedCompletion(
					"QB0+QC0+compatible-QM0", model, surface, forceQb, forceQc, forceQm);
				if("infeasible".equals(parentCompletion.get("status")))
					parentCompletion.put("minimumHardViolationWitness", minimumHardViolationWitness(
						analysis, model, surface, forceQb, forceQc, forceQm));
				attempt.put("compatibleParentCompletion", parentCompletion);
			}
			try {
				List<Integer> forced = solveForced(model, surface, forceQb, forceQc);
				double hard = RegionalSearchProblem.evaluateFactors(
					model.variables(), model.hardFactors(), forced);
				double cost = Double.longBitsToDouble(surface.evaluateCanonical(forced));
				attempt.put("status", Double.isFinite(hard) && Double.isFinite(cost)
					? "feasible" : "non-finite");
				attempt.put("hardCost", hard);
				attempt.put("canonicalCost", cost);
				attempt.put("deltaFromSelected", cost - selectedCost);
				attempt.put("assignment", forced);
				attempt.put("changedAlternatives", changedAlternatives(
					analysis, model, selectedAssignment, forced));
				attempt.put("changedContributions", changedContributions(
					surface, selectedAssignment, forced));
				if(Double.isFinite(hard) && Double.isFinite(cost) && cost < bestForcedCost) {
					bestForcedCost = cost;
					bestForcedHard = hard;
					best = attempt;
				}
			}
			catch(IllegalArgumentException failure) {
				if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
					throw failure;
				attempt.put("status", "infeasible");
				attempt.put("error", failure.getMessage());
				attempt.put("minimumHardViolationWitness",
					minimumHardViolationWitness(analysis, model, surface, forceQb, forceQc));
			}
			attempts.add(attempt);
		}
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("status", "checked");
		out.put("selectedHardCost", selectedHard);
		out.put("bestForcedHardCost", best == null ? null : bestForcedHard);
		out.put("selectedCost", selectedCost);
		out.put("bestForcedCost", best == null ? null : bestForcedCost);
		out.put("exactOptimalityGap", best == null ? null : selectedCost - bestForcedCost);
		out.put("commonRelocationCompletionLegal", best != null && bestForcedHard == 0d);
		out.put("selectedCommonRelocation", selectedCommonIdentities.size() == 1);
		out.put("selectedCommonPhysicalEmissionIdentity", selectedCommonIdentities.size() == 1
			? selectedCommonIdentities.iterator().next() : null);
		out.put("cheaperLegalAlternativeFound", best != null && bestForcedHard == 0d
			&& Double.compare(bestForcedCost, selectedCost) < 0);
		out.put("bestForcedPhysicalEmissionIdentity",
			best == null ? null : best.get("physicalEmissionIdentity"));
		out.put("selectedAlternatives", namedAlternativeSummary(
			analysis, model, selectedAssignment, Set.of("S0", "QB0", "QC0", "QM0")));
		out.put("forcedAttempts", List.copyOf(attempts));
		return out;
	}

	private static Map<String,Object> forcedCompletion(String label, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactCategoricalSolver.Factor... forcedFactors) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("forcedDecision", label);
		try {
			List<Integer> assignment = solveForced(model, surface, forcedFactors);
			out.put("status", "feasible");
			out.put("hardCost", RegionalSearchProblem.evaluateFactors(
				model.variables(), model.hardFactors(), assignment));
			out.put("canonicalCost",
				Double.longBitsToDouble(surface.evaluateCanonical(assignment)));
		}
		catch(IllegalArgumentException failure) {
			if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				throw failure;
			out.put("status", "infeasible");
			out.put("error", failure.getMessage());
		}
		return out;
	}

	private static ExactPhysicalModel.DecisionDomain uniqueNamedOpcodeDomain(
		PlacementAnalysis analysis, ExactPhysicalModel model, String name, String opcode) {
		List<ExactPhysicalModel.DecisionDomain> matches = model.domains().stream()
			.filter(domain -> analysis.hop(domain.node().key())
				.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false))
			.toList();
		if(matches.size() != 1)
			throw new IllegalStateException("Flat updated diagnostic expected one " + name + '/' + opcode
				+ " decision, found " + matches.size());
		return matches.get(0);
	}

	private static Set<Integer> compatibleParentAlternatives(
		ExactPhysicalModel.DecisionDomain parent, ExactPhysicalModel.DecisionDomain left,
		Set<Integer> leftValues, ExactPhysicalModel.DecisionDomain right, Set<Integer> rightValues) {
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
			leftReferences = candidateReferences(left, leftValues);
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
			rightReferences = candidateReferences(right, rightValues);
		Set<Integer> result = new LinkedHashSet<>();
		for(int value = 0; value < parent.alternatives().size(); value++) {
			var alternative = parent.alternatives().get(value);
			if(alternative.supportClause() == null)
				continue;
			List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
				supports = alternative.supportClause().requiredInputSupport();
			if(supports.stream().anyMatch(support -> leftReferences.stream().anyMatch(selected ->
				org.apache.sysds.hops.fedplanner.placement.CandidateSelections
					.requiredInputSupportIdentity(support).equals(
						org.apache.sysds.hops.fedplanner.placement.CandidateSelections
							.requiredInputSupportIdentity(selected))))
				&& supports.stream().anyMatch(support -> rightReferences.stream().anyMatch(selected ->
					org.apache.sysds.hops.fedplanner.placement.CandidateSelections
						.requiredInputSupportIdentity(support).equals(
							org.apache.sysds.hops.fedplanner.placement.CandidateSelections
								.requiredInputSupportIdentity(selected)))))
				result.add(value);
		}
		return Set.copyOf(result);
	}

	private static Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
		candidateReferences(ExactPhysicalModel.DecisionDomain domain, Set<Integer> values) {
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
			result = new LinkedHashSet<>();
		for(int value : values) {
			var alternative = domain.alternatives().get(value);
			if(alternative.candidateRule() != null && alternative.realization() != null)
				result.add(org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
					.CandidateRealizationReference.of(
						alternative.candidateRule().key(), alternative.realization()));
		}
		return Set.copyOf(result);
	}

	private static Map<String,Object> minimumHardViolationWitness(PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactCategoricalSolver.Factor... forcedFactors) {
		List<ExactCategoricalSolver.Factor> softened = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : model.exactSolverHardFactors())
			softened.add(ExactCategoricalSolver.Factor.lazy(factor.scope(), local ->
				Double.isFinite(factor.cost(local)) ? 0d : 1d));
		softened.addAll(List.of(forcedFactors));
		var prepared = ExactPhysicalReducedSolver.prepare(model.variables().size(),
			surface.exactSolverVariables(), softened, ExactPhysicalOptimizer.PRODUCTION_LIMITS,
			ExactEliminationOrderPolicy.globalConfigured(), "flat-updated-hard-violation-diagnostic");
		var solved = ExactPhysicalReducedSolver.solve(prepared);
		List<Integer> fullAssignment = solved.assignmentInVariableOrder();
		List<Integer> decisions = List.copyOf(fullAssignment.subList(0, model.variables().size()));
		Map<Integer,String> descriptors = new LinkedHashMap<>();
		for(var encoding : model.hardFactorEncodings())
			descriptors.put(encoding.canonicalOrdinal(), encoding.decomposition().descriptor());
		List<Map<String,Object>> violated = new ArrayList<>();
		for(int ordinal = 0; ordinal < model.hardFactors().size(); ordinal++) {
			ExactCategoricalSolver.Factor factor = model.hardFactors().get(ordinal);
			double value = evaluateFactor(model.variables(), factor, decisions);
			if(Double.isFinite(value))
				continue;
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("canonicalOrdinal", ordinal);
			row.put("descriptor", descriptors.getOrDefault(ordinal, "unencoded"));
			row.put("scope", factor.scope().stream().map(variable ->
				variableSummary(analysis, model, decisions, variable)).toList());
			violated.add(row);
		}
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("encodedViolationObjective", solved.objective());
		out.put("canonicalViolatedFactorCount", violated.size());
		out.put("canonicalViolatedFactors", List.copyOf(violated));
		out.put("decisionAssignment", decisions);
		return out;
	}

	private static double evaluateFactor(List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Factor factor, List<Integer> assignment) {
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int position = 0; position < variables.size(); position++)
			positions.put(variables.get(position), position);
		int[] local = new int[factor.scope().size()];
		for(int position = 0; position < local.length; position++)
			local[position] = assignment.get(positions.get(factor.scope().get(position)));
		return factor.cost(local);
	}

	private static Map<String,Object> variableSummary(PlacementAnalysis analysis,
		ExactPhysicalModel model, List<Integer> assignment,
		ExactCategoricalSolver.Variable variable) {
		int position = model.variables().indexOf(variable);
		if(position < 0)
			return Map.of("variable", variable.key(), "kind", "auxiliary");
		var domain = model.domains().get(position);
		int value = assignment.get(position);
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("decisionPosition", position);
		out.put("variable", variable.key());
		out.put("name", analysis.hop(domain.node().key()).map(Hop::getName).orElse(null));
		out.put("selectedAlternative", value);
		out.put("signature", domain.alternatives().get(value).signature());
		return out;
	}

	private static ExactPhysicalModel.DecisionDomain uniqueNamedDomain(PlacementAnalysis analysis,
		ExactPhysicalModel model, String name) {
		List<ExactPhysicalModel.DecisionDomain> matches = model.domains().stream()
			.filter(domain -> analysis.hop(domain.node().key())
				.map(hop -> name.equals(hop.getName())).orElse(false)).toList();
		if(matches.size() != 1)
			throw new IllegalStateException("Flat updated diagnostic expected one " + name
				+ " decision, found " + matches.size());
		return matches.get(0);
	}

	private static List<RelocationAlternative> relocationAlternatives(
		ExactPhysicalModel.DecisionDomain domain, int inputPosition, String targetAnchor) {
		List<RelocationAlternative> result = new ArrayList<>();
		for(int value = 0; value < domain.alternatives().size(); value++) {
			for(ExactPhysicalModel.InputAuthority authority :
				domain.alternatives().get(value).inputAuthorities()) {
				if(authority.inputPosition() != inputPosition
					|| authority.kind() != ExactPhysicalModel.InputAuthorityKind.RELOCATION)
					continue;
				var key = authority.relocationAction().key();
				if(targetAnchor.equals(key.durableAnchor().placementId())
					&& key.targetPlacement().output()
						== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT)
					result.add(new RelocationAlternative(value,
						RelocationSelections.physicalEmissionIdentity(key)));
			}
		}
		return List.copyOf(result);
	}

	private static Set<Integer> matchingValues(List<RelocationAlternative> alternatives,
		String physicalIdentity) {
		Set<Integer> values = new LinkedHashSet<>();
		alternatives.stream().filter(alternative ->
			physicalIdentity.equals(alternative.physicalIdentity()))
			.forEach(alternative -> values.add(alternative.value()));
		return Set.copyOf(values);
	}

	private static Set<String> selectedRelocationIdentities(
		List<RelocationAlternative> alternatives, int selectedValue) {
		return alternatives.stream().filter(alternative -> alternative.value() == selectedValue)
			.map(RelocationAlternative::physicalIdentity)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static ExactCategoricalSolver.Factor forceAlternatives(
		ExactCategoricalSolver.Variable variable, Set<Integer> allowed) {
		if(allowed.isEmpty())
			throw new IllegalArgumentException("Flat updated diagnostic has no allowed alternatives");
		return ExactCategoricalSolver.Factor.lazy(List.of(variable), local ->
			allowed.contains(local[0]) ? 0d : Double.POSITIVE_INFINITY);
	}

	private static List<Integer> solveForced(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactCategoricalSolver.Factor... forcedFactors) {
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.exactSolverHardFactors());
		factors.addAll(surface.exactSolverFactors());
		factors.addAll(List.of(forcedFactors));
		var prepared = ExactPhysicalReducedSolver.prepare(model.variables().size(),
			surface.exactSolverVariables(), factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS,
			ExactEliminationOrderPolicy.globalConfigured(), "flat-updated-supply-diagnostic");
		List<Integer> solved = ExactPhysicalReducedSolver.solve(prepared).assignmentInVariableOrder();
		return List.copyOf(solved.subList(0, model.variables().size()));
	}

	private static List<Map<String,Object>> changedAlternatives(PlacementAnalysis analysis,
		ExactPhysicalModel model, List<Integer> selected, List<Integer> forced) {
		List<Map<String,Object>> changed = new ArrayList<>();
		for(int position = 0; position < model.domains().size(); position++) {
			if(selected.get(position).equals(forced.get(position)))
				continue;
			var domain = model.domains().get(position);
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("decisionPosition", position);
			row.put("occurrence", domain.node().key().normalizedSignature());
			row.put("name", analysis.hop(domain.node().key()).map(Hop::getName).orElse(null));
			row.put("selectedAlternative", selected.get(position));
			row.put("selectedSignature", domain.alternatives().get(selected.get(position)).signature());
			row.put("forcedAlternative", forced.get(position));
			row.put("forcedSignature", domain.alternatives().get(forced.get(position)).signature());
			changed.add(row);
		}
		return List.copyOf(changed);
	}

	private static List<Map<String,Object>> changedContributions(
		ExactPhysicalCostModel.PhysicalCostSurface surface, List<Integer> selected,
		List<Integer> forced) {
		List<Map<String,Object>> changed = new ArrayList<>();
		for(var contribution : surface.contributions()) {
			double selectedCost = surface.evaluateContributionCanonical(contribution, selected);
			double forcedCost = surface.evaluateContributionCanonical(contribution, forced);
			if(Double.doubleToRawLongBits(selectedCost) == Double.doubleToRawLongBits(forcedCost))
				continue;
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("id", contribution.id());
			row.put("scope", contribution.factor().scope().stream()
				.map(ExactCategoricalSolver.Variable::key).toList());
			row.put("selectedCost", selectedCost);
			row.put("forcedCost", forcedCost);
			row.put("delta", forcedCost - selectedCost);
			changed.add(row);
		}
		return List.copyOf(changed);
	}

	private static List<Map<String,Object>> namedAlternativeSummary(PlacementAnalysis analysis,
		ExactPhysicalModel model, List<Integer> assignment, Set<String> names) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(int position = 0; position < model.domains().size(); position++) {
			var domain = model.domains().get(position);
			Hop hop = analysis.hop(domain.node().key()).orElse(null);
			if(hop == null || !names.contains(hop.getName()))
				continue;
			int value = assignment.get(position);
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("decisionPosition", position);
			row.put("name", hop.getName());
			row.put("selectedAlternative", value);
			row.put("signature", domain.alternatives().get(value).signature());
			result.add(row);
		}
		return List.copyOf(result);
	}

	private record RelocationAlternative(int value, String physicalIdentity) { }

	private static Map<String,Object> selectedOccurrence(PlacementAnalysis analysis,
		ExactPhysicalModel.DecisionDomain domain, ExactPhysicalModel.Alternative alternative,
		int position, int selectedValue) {
		Map<String,Object> out = new LinkedHashMap<>();
		Hop hop = analysis.hop(domain.node().key()).orElse(null);
		out.put("decisionPosition", position);
		out.put("selectedAlternative", selectedValue);
		out.put("occurrence", domain.node().key().normalizedSignature());
		out.put("valueVersion", domain.node().valueVersion().normalizedSignature());
		out.put("lexicalVariable", domain.node().valueVersion().lexicalVariable());
		out.put("name", hop == null ? null : hop.getName());
		out.put("opcode", hop == null ? null : hop.getOpString());
		out.put("line", hop == null ? null : hop.getBeginLine());
		out.put("physicalState", alternative.state().normalizedSignature());
		out.put("authority", alternative.authorityKind().name());
		out.put("alternativeSignature", alternative.signature());
		out.put("expectedExecutions", domain.node().kind().name().startsWith("FUNCTION_") ? null
			: analysis.executionFrequencyFacts().exactExecutionWeight(domain.node().key()));
		return out;
	}

	private static Map<String,Object> selectedRelocation(PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface,
		List<Integer> assignment, IdentityHashMap<Object,Integer> positions,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey action,
		Set<String> sharedLifetimes, RefedReuseAudit.Snapshot reuseAudit) {
		String physicalIdentity = RelocationSelections.physicalEmissionIdentity(action);
		var transfers = surface.transferKeys().stream()
			.filter(key -> key.sourceValueVersion().equals(action.sourceValueVersion())
				&& key.physicalEmissionIdentity().equals(physicalIdentity)).toList();
		if(transfers.isEmpty())
			throw new IllegalStateException("Selected relocation has no production transfer descriptor: "
				+ action.normalizedSignature());
		List<Map<String,Object>> transferDescriptions = new ArrayList<>();
		for(var transfer : transfers) {
			LinkedHashSet<ExactCategoricalSolver.Variable> sourceVariables = new LinkedHashSet<>();
			LinkedHashSet<ExactCategoricalSolver.Variable> consumerVariables = new LinkedHashSet<>();
			for(var endpoint : transfer.endpoints()) {
				Integer producer = positions.get(endpoint.producer());
				Integer consumer = positions.get(endpoint.consumer());
				if(producer == null || consumer == null)
					throw new IllegalStateException("Transfer endpoint is absent from production model");
				sourceVariables.add(model.domains().get(producer).variable());
				consumerVariables.add(model.domains().get(consumer).variable());
			}
			List<Map<String,Object>> scopedContributions = surface.contributions().stream()
				.filter(contribution -> contribution.factor().scope().stream()
					.anyMatch(sourceVariables::contains)
					&& contribution.factor().scope().stream().anyMatch(consumerVariables::contains))
				.map(contribution -> contribution(contribution,
					surface.evaluateContributionCanonical(contribution, assignment))).toList();
			if(scopedContributions.isEmpty())
				throw new IllegalStateException("Selected relocation has no finite movement contribution: "
					+ action.normalizedSignature());
			List<Map<String,Object>> charged = scopedContributions.stream()
				.filter(entry -> ((Double)entry.get("costMillis")) > 0d).toList();
			Map<String,Object> transferOut = new LinkedHashMap<>();
			transferOut.put("direction", transfer.direction().name());
			transferOut.put("boundaryMode", transfer.boundaryMode().name());
			transferOut.put("fType", transfer.fType().name());
			transferOut.put("physicalEmissionIdentity", transfer.physicalEmissionIdentity());
			transferOut.put("endpoints", transfer.endpoints().stream()
				.map(endpoint -> endpoint(analysis, endpoint)).toList());
			transferOut.put("scopeDerivedContributions", scopedContributions);
			transferOut.put("positiveScopeDerivedContributions", charged);
			transferOut.put("positiveScopedCostMillis", charged.stream()
				.mapToDouble(entry -> (Double)entry.get("costMillis")).sum());
			transferOut.put("costAttribution",
				"scope-derived diagnostic; may include another joint cost and is not an independent action price");
			transferDescriptions.add(transferOut);
		}
		Map<String,Object> out = new LinkedHashMap<>();
		var sourceDomains = model.domains().stream()
			.filter(domain -> domain.node().valueVersion().equals(action.sourceValueVersion()))
			.filter(domain -> analysis.isCompiledHopOccurrence(domain.node().key())).toList();
		if(sourceDomains.size() != 1)
			throw new IllegalStateException("Selected relocation source has " + sourceDomains.size()
				+ " compiled physical owners: " + action.normalizedSignature());
		var sourceDomain = sourceDomains.get(0);
		Integer sourcePosition = positions.get(sourceDomain.node().key());
		if(sourcePosition == null)
			throw new IllegalStateException("Selected relocation source is absent from production model");
		var sourceAlternative = sourceDomain.alternatives().get(assignment.get(sourcePosition));
		boolean staged = sourceAlternative.state().output()
			== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT;
		String runtimeActionKey = PlannerRuntimePlacementAudit.syntheticActionKey(
			action.normalizedSignature(), staged ? "REFED_STAGED" : "REFED");
		String actionKeyDigest = RefedReuseAudit.digest(runtimeActionKey);
		out.put("action", action.normalizedSignature());
		out.put("physicalEmissionIdentity", physicalIdentity);
		out.put("runtimeSyntheticActionKey", runtimeActionKey);
		out.put("actionKeyDigest", actionKeyDigest);
		out.put("sourcePhysicalState", sourceAlternative.state().normalizedSignature());
		out.put("expectedStaged", staged);
		out.put("expectedSharingGroupDigest", RefedReuseAudit.digest(
			sharedLifetimes.contains(physicalIdentity) ? physicalIdentity : ""));
		out.put("sharedAcrossExecutions", sharedLifetimes.contains(physicalIdentity));
		out.put("sourceValueVersion", action.sourceValueVersion().normalizedSignature());
		out.put("sourceLexicalVariable", action.sourceValueVersion().lexicalVariable());
		out.put("sourceDefinitionOrdinal", action.sourceValueVersion().definitionOrdinal());
		out.put("sourceVersionKind", action.sourceValueVersion().versionKind().name());
		out.put("targetPhysicalState", action.targetPlacement().normalizedSignature());
		out.put("materializationFType", action.materializationFType().name());
		out.put("statementBlockScope", action.statementBlockScope());
		out.put("compatibleConsumers", action.compatibleConsumers().stream()
			.map(key -> key.normalizedSignature()).toList());
		out.put("transfers", transferDescriptions);
		List<RefedReuseAudit.SupplyEvent> matchingEvents = reuseAudit.supplyEvents().stream()
			.filter(event -> actionKeyDigest.equals(event.actionKeyDigest())).toList();
		if(matchingEvents.stream().anyMatch(event -> event.staged() != staged))
			throw new IllegalStateException("Runtime supply staging differs from selected source state: "
				+ action.normalizedSignature());
		out.put("runtimeSupplyEventCount", matchingEvents.size());
		out.put("runtimeCreationCount", matchingEvents.stream().filter(
			RefedReuseAudit.SupplyEvent::created).count());
		out.put("runtimeSupplyEvents", matchingEvents);
		return out;
	}

	private static Map<String,Object> endpoint(PlacementAnalysis analysis,
		ExactPhysicalCostModel.PhysicalTransferEndpoint endpoint) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("inputPosition", endpoint.inputPosition());
		out.put("producer", occurrence(analysis, endpoint.producer()));
		out.put("consumer", occurrence(analysis, endpoint.consumer()));
		return out;
	}

	private static Map<String,Object> occurrence(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		Map<String,Object> out = new LinkedHashMap<>();
		Hop hop = analysis.hop(key).orElse(null);
		out.put("occurrence", key.normalizedSignature());
		out.put("name", hop == null ? null : hop.getName());
		out.put("opcode", hop == null ? null : hop.getOpString());
		out.put("line", hop == null ? null : hop.getBeginLine());
		return out;
	}

	private static Map<String,Object> contribution(
		ExactPhysicalCostModel.PhysicalContribution contribution, double cost) {
		if(!Double.isFinite(cost))
			throw new IllegalStateException("Non-finite selected movement contribution " + contribution.id());
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("id", contribution.id());
		out.put("scope", contribution.factor().scope().stream()
			.map(ExactCategoricalSolver.Variable::key).toList());
		out.put("costMillis", cost);
		out.put("costBits", Long.toUnsignedString(Double.doubleToRawLongBits(cost)));
		return out;
	}

	private static List<Integer> assignment(String certificate) {
		Matcher matcher = ASSIGNMENT.matcher(certificate);
		if(!matcher.find())
			throw new IllegalStateException("Objective certificate has no assignment");
		String body = matcher.group(1).trim();
		return body.isEmpty() ? List.of() : Arrays.stream(body.split(","))
			.map(String::trim).map(Integer::valueOf).toList();
	}

	private static long objectiveBits(String certificate) {
		Matcher matcher = OBJECTIVE.matcher(certificate);
		if(!matcher.find())
			throw new IllegalStateException("Objective certificate has no recognized objective bits");
		return Long.parseLong(matcher.group(1));
	}

	private static String costSurface(String certificate) {
		Matcher matcher = COST_SURFACE.matcher(certificate);
		if(!matcher.find())
			throw new IllegalStateException("Objective certificate has no cost-surface fingerprint");
		return matcher.group(1);
	}

	private static Map<String,Long> federatedIoCounts(String statistics) {
		Matcher matcher = FED_IO.matcher(statistics);
		if(!matcher.find())
			throw new IllegalStateException("Federated I/O counters were not published: " + statistics);
		Map<String,Long> counts = new LinkedHashMap<>();
		counts.put("read", Long.parseLong(matcher.group(1)));
		counts.put("put", Long.parseLong(matcher.group(2)));
		counts.put("get", Long.parseLong(matcher.group(3)));
		return counts;
	}

	private static Map<String,Object> coordinatorMemory() {
		Map<String,Object> memory = new LinkedHashMap<>();
		var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		memory.put("heapUsedBytesAtObservation", heap.getUsed());
		memory.put("heapCommittedBytesAtObservation", heap.getCommitted());
		memory.put("heapMaxBytes", heap.getMax());
		memory.put("sumOfHeapPoolPeakUsedBytes", ManagementFactory.getMemoryPoolMXBeans().stream()
			.filter(pool -> pool.getType() == MemoryType.HEAP)
			.mapToLong(pool -> pool.getPeakUsage().getUsed()).sum());
		memory.put("peakSemantics",
			"sum of per-pool high-water marks; not a simultaneous total-heap observation");
		return memory;
	}

	private static List<Map<String,Object>> collectWorkerObservations() throws Exception {
		List<Map<String,Object>> observations = new ArrayList<>(2);
		for(int port : new int[] {13001, 13002}) {
			InetSocketAddress address = new InetSocketAddress("localhost", port);
			FederatedRequest request = new FederatedRequest(RequestType.EXEC_UDF, -1,
				new WorkerObservationFunction());
			FederatedResponse response = FederatedData.executeFederatedOperation(address, request)
				.get(30, TimeUnit.SECONDS);
			if(!response.isSuccessful())
				throw new IllegalStateException("Worker observation failed for " + address + ": "
					+ response.getErrorMessage());
			Object[] data = response.getData();
			if(data == null || data.length != 1 || !(data[0] instanceof Map<?,?> raw))
				throw new IllegalStateException("Worker observation returned unexpected data for " + address);
			Map<String,Object> observation = new LinkedHashMap<>();
			observation.put("address", address.toString());
			raw.forEach((key, value) -> observation.put(String.valueOf(key), value));
			observations.add(observation);
		}
		return List.copyOf(observations);
	}

	/** Scalar-only worker observation; the empty input-id list forbids MatrixBlock acquisition. */
	public static final class WorkerObservationFunction extends FederatedUDF {
		private static final long serialVersionUID = 1L;

		public WorkerObservationFunction() {
			super(new long[0]);
		}

		@Override
		public FederatedResponse execute(ExecutionContext ec, Data... data) {
			Map<String,Object> observation = new LinkedHashMap<>();
			observation.put("processId", ProcessHandle.current().pid());
			observation.put("memoryHits", CacheStatistics.getMemHits());
			observation.put("hdfsHits", CacheStatistics.getHDFSHits());
			observation.put("fsWrites", CacheStatistics.getFSWrites());
			observation.put("fsHits", CacheStatistics.getFSHits());
			observation.put("fsBufferWrites", CacheStatistics.getFSBuffWrites());
			observation.put("fsBufferHits", CacheStatistics.getFSBuffHits());
			observation.put("cacheOperationTimesSeconds", CacheStatistics.displayTime());
			boolean cachingActive = CacheableData.isCachingActive();
			boolean unifiedMemoryManager = OptimizerUtils.isUMMEnabled();
			observation.put("cachingActive", cachingActive);
			observation.put("unifiedMemoryManagerEnabled", unifiedMemoryManager);
			observation.put("cacheManager", unifiedMemoryManager ? "UNIFIED" : "STATIC");
			if(!cachingActive)
				observation.put("cacheManagerObservation", "INACTIVE_CACHING_DISABLED");
			else if(unifiedMemoryManager) {
				observation.put("unifiedMemoryLimitBytes", UnifiedMemoryManager.getUMMSize());
				observation.put("unifiedMemoryFreeBytes", UnifiedMemoryManager.getUMMFree());
			}
			else {
				observation.put("writeBufferSizeBytes", LazyWriteBuffer.getWriteBufferSize());
				observation.put("writeBufferLimitBytes", LazyWriteBuffer.getWriteBufferLimit());
				observation.put("writeBufferQueueSize", LazyWriteBuffer.getQueueSize());
			}
			observeCacheFiles(observation);
			var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
			observation.put("heapUsedBytes", heap.getUsed());
			observation.put("heapCommittedBytes", heap.getCommitted());
			observation.put("heapMaxBytes", heap.getMax());
			observation.put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(bean -> Math.max(0L, bean.getCollectionCount())).sum());
			observation.put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(bean -> Math.max(0L, bean.getCollectionTime())).sum());
			return new FederatedResponse(FederatedResponse.ResponseType.SUCCESS, observation);
		}

		@Override
		public Pair<String,LineageItem> getLineageItem(ExecutionContext ec) {
			return null;
		}

		private static void observeCacheFiles(Map<String,Object> observation) {
			String cachePath = CacheableData.cacheEvictionLocalFilePath;
			observation.put("cachePath", cachePath);
			if(cachePath == null) {
				observation.put("cacheFileCount", 0L);
				observation.put("cacheFileBytes", 0L);
				return;
			}
			Path directory = Path.of(cachePath);
			if(!Files.isDirectory(directory)) {
				observation.put("cacheFileCount", 0L);
				observation.put("cacheFileBytes", 0L);
				return;
			}
			try(var files = Files.list(directory)) {
				List<Path> cacheFiles = files.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString()
						.startsWith(CacheableData.cacheEvictionLocalFilePrefix))
					.toList();
				long totalBytes = 0;
				for(Path cacheFile : cacheFiles)
					totalBytes += Files.size(cacheFile);
				observation.put("cacheFileCount", (long)cacheFiles.size());
				observation.put("cacheFileBytes", totalBytes);
			}
			catch(Exception ex) {
				observation.put("cacheFileObservationError", ex.toString());
			}
		}
	}

	private static void writeJson(Path target, Map<String,Object> output) throws Exception {
		Path absolute = target.toAbsolutePath().normalize();
		Path parent = absolute.getParent();
		if(parent != null)
			Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
		try {
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), output);
			try {
				Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch(java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private record CanonicalProof(long certificateObjectiveBits, long canonicalObjectiveBits,
		boolean costSurfaceMatches, boolean selectedStatesMatch, boolean sharedLifetimesMatch,
		String reconstructedCostSurfaceFingerprint, List<Integer> assignment,
		List<Map<String,Object>> selectedOccurrences, List<Map<String,Object>> selectedRelocations,
		Map<String,Object> flatUpdatedDiagnostic, Map<String,Long> modelCounts) {

		boolean objectiveMatches() {
			return certificateObjectiveBits == canonicalObjectiveBits;
		}

		Map<String,Object> summary() {
			Map<String,Object> out = new LinkedHashMap<>();
			out.put("certificateObjectiveBits", Long.toUnsignedString(certificateObjectiveBits));
			out.put("canonicalObjectiveBits", Long.toUnsignedString(canonicalObjectiveBits));
			out.put("canonicalObjectiveMillis", Double.longBitsToDouble(canonicalObjectiveBits));
			out.put("objectiveMatches", objectiveMatches());
			out.put("costSurfaceMatches", costSurfaceMatches);
			out.put("selectedStatesMatch", selectedStatesMatch);
			out.put("sharedLifetimesMatch", sharedLifetimesMatch);
			out.put("reconstructedCostSurfaceFingerprint", reconstructedCostSurfaceFingerprint);
			out.put("modelCounts", modelCounts);
			out.put("assignment", assignment);
			return out;
		}
	}
}

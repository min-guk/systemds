/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CoordinatorInputAccess;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Compile-only regression for native origin residency in the two-worker L2SVM plan. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactL2SvmInternalEmissionCostRedTest {
	@Test
	public void l2SvmExactCompilesWithMultipleExactInternalEmissions() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		Path script = Path.of("target/g014-exact-l2svm-cli.dml");
		Path config = Path.of("target/g014-exact-l2svm-cli.xml");
		Path x1 = Path.of("target/g014-exact-l2svm-x-1.data");
		Path x2 = Path.of("target/g014-exact-l2svm-x-2.data");
		Path y1 = Path.of("target/g014-exact-l2svm-y-1.data");
		Path y2 = Path.of("target/g014-exact-l2svm-y-2.data");
		Files.createDirectories(script.getParent());
		String x1Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker1", 8001, x1, 25000, 2100, 25000L * 2100);
		String x2Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker2", 8002, x2, 25000, 2100, 25000L * 2100);
		String y1Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker1", 8001, y1, 25000, 1, 25000);
		String y2Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker2", 8002, y2, 25000, 1, 25000);
		Files.writeString(script, String.join("\n",
			"X = federated(addresses=list(\"" + x1Address + "\", \"" + x2Address + "\"), "
				+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))",
			"Y = federated(addresses=list(\"" + y1Address + "\", \"" + y2Address + "\"), "
				+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))",
			"",
			"m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon=1e-22, maxIterations=30)",
			"write(m, \"target/g014-exact-l2svm-cli.csv\", format=\"csv\")", ""));
		Files.writeString(config, String.join("\n",
			"<root>",
			"    <sysds.local.spark>true</sysds.local.spark>",
			"    <sysds.federated.planner>compile_exact</sysds.federated.planner>",
			"    <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
			"</root>", ""));
		try {
			PrintStream originalOut = System.out;
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			boolean success;
			try(PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
				System.setOut(capture);
				success = DMLScript.executeScript(new String[] {
					"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
					"-stats", "100", "-debug", "-explain", "runtime", "-config", config.toString()
				});
			}
			finally {
				System.setOut(originalOut);
			}
			Assert.assertTrue(success);
			String runtimeProgram = captured.toString(StandardCharsets.UTF_8);
			String outerLoop = between(runtimeProgram, "WHILE (lines 96-140)",
				"GENERIC (lines 141-141)");
			Assert.assertEquals("Exact must retain the cheaper direct local-left/FED-right matmul plan "
				+ "instead of materializing the full X transpose; boundaries="
				+ boundaryRegistrySlots() + "; normalizedLocals=" + normalizedLocalSummary()
				+ "; selectedRelocations=" + normalizedRelocationSummary()
				+ "; variantTrace=" + runtimeProgram.lines()
					.filter(line -> line.contains("Exact-ExactRowVariant")).toList(),
				0, count(runtimeProgram, "FED r' X.MATRIX"));
			Assert.assertFalse("The outer L2SVM loop must not materialize the stable X transpose",
				outerLoop.contains("FED r' X.MATRIX"));
			Assert.assertTrue("The outer loop must keep X-times-s federated",
				outerLoop.contains("FED ba+* X.MATRIX"));
			Assert.assertTrue("The outer loop must keep local-left-times-X on the federated workers",
				outerLoop.lines().anyMatch(line -> line.contains("FED ba+*")
					&& line.contains(" X.MATRIX") && line.contains(" LOUT")));
			var normalized = committedResult();
			assertExactRelocationEmissionCoverage(normalized);
			Assert.assertFalse("Exact must not publish a relocation boundary on the loop-local X transpose",
				normalized.selectedRelocations().stream().anyMatch(action ->
					normalized.analysis().graph().nodes().stream()
						.filter(node -> node.valueVersion().equals(action.sourceValueVersion()))
						.anyMatch(source -> normalized.analysis().hop(source.key()).map(hop ->
							hop.getBeginLine() == 124 && "r(r')".equals(hop.getOpString())).orElse(false))));
			Assert.assertTrue("Exact must not relocate Xd inside the 30x20 nested loop; relocations="
				+ normalized.selectedRelocations(), normalized.selectedRelocations().stream().noneMatch(action ->
					normalized.analysis().graph().nodes().stream().anyMatch(node ->
						node.valueVersion().equals(action.sourceValueVersion())
							&& normalized.analysis().hop(node.key()).map(hop -> "Xd".equals(hop.getName())
								&& hop.getBeginLine() == 110).orElse(false))));
			var xdNodes = normalized.analysis().graph().nodes().stream()
				.filter(node -> normalized.analysis().isCompiledHopOccurrence(node.key()))
				.filter(node -> normalized.analysis().hop(node.key()).map(hop ->
					"Xd".equals(hop.getName()) && hop.getBeginLine() == 110
						&& "b(*)".equals(hop.getOpString())).orElse(false))
				.toList();
			Assert.assertEquals("Expected one exact Xd loop occurrence", 1, xdNodes.size());
			var xd = xdNodes.get(0);
			assertOriginBoundNativeState(normalized, xd.key(), "exact Xd loop occurrence");
			var labelReads = normalized.analysis().graph().nodes().stream()
				.filter(node -> normalized.analysis().isCompiledHopOccurrence(node.key()))
				.filter(node -> normalized.analysis().hop(node.key()).map(hop ->
						hop.getBeginLine() == 109 && "TRead Y".equals(hop.getOpString())).orElse(false))
				.toList();
			Assert.assertFalse("Expected a loop-local TRead Y occurrence", labelReads.isEmpty());
			labelReads.forEach(node -> assertOriginBoundNativeState(normalized, node.key(),
				"loop-local TRead Y occurrence"));
			assertProtectedEmissionContract(normalized);
		}
		finally {
			Files.deleteIfExists(script);
			Files.deleteIfExists(config);
			ExactCliMetadataFixture.delete(x1, x2, y1, y2);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			restoreProperties(oldCostProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static void assertOriginBoundNativeState(
			org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult normalized,
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key, String witness) {
		Assert.assertTrue(witness + " must require origin residency",
			ExecPlacementPolicy.requiresOriginResidency(normalized.analysis().requirePrivacy(key)));
		var emission = normalized.selectedEmissionStates().get(key);
		Assert.assertNotNull(witness + " must have a selected emission state", emission);
		Assert.assertEquals(witness + " must execute on federated workers", ExecType.FED,
			emission.placementState().execType());
		Assert.assertEquals(witness + " must retain a federated result", FederatedOutput.FOUT,
			emission.placementState().output());
		Assert.assertNotNull(witness + " must retain a native federated layout",
			emission.placementState().fType());
		Assert.assertFalse(witness + " must not use a coordinator-derived FED/FOUT",
			emission.derivedFedFout());
	}

	private static void assertProtectedEmissionContract(
			org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult normalized) {
		var analysis = normalized.analysis();
		for(var node : analysis.graph().decisionNodes()) {
			if(!node.emittedWork() || analysis.isDmlFunctionCallBoundary(node.key())
				|| !ExecPlacementPolicy.requiresOriginResidency(analysis.requirePrivacy(node.key())))
				continue;
			assertOriginBoundNativeState(normalized, node.key(), "origin-bound emitted decision");
		}
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(analysis.coordinatorInputAccess(edge) != CoordinatorInputAccess.PAYLOAD
				|| analysis.isDmlFunctionCallBoundary(edge.consumer())
				|| !ExecPlacementPolicy.requiresOriginResidency(analysis.requirePrivacy(edge.producer())))
				continue;
			var consumer = normalized.selectedStates().get(edge.consumer());
			Assert.assertNotNull("Protected payload consumer must have a selected state", consumer);
			Assert.assertEquals("Protected payload must be consumed on federated workers",
				ExecType.FED, consumer.execType());
			var candidate = normalized.selectedCandidateSelections().stream()
				.filter(receipt -> receipt.rule().parentOccurrence() == edge.consumer()).findFirst().orElse(null);
			Assert.assertNotNull("Protected FED payload must retain exact candidate authority", candidate);
			Assert.assertTrue("Protected FED payload must be PRESENT in its selected candidate row",
				edge.inputPosition() < candidate.rule().orderedInputs().size()
					&& candidate.rule().orderedInputs().get(edge.inputPosition()).present());
		}
		for(Object raw : normalized.selectedLocalMaterializations()) {
			LocalMaterializationActionKey action = (LocalMaterializationActionKey) raw;
			Assert.assertFalse("Protected source must not have a selected local materialization",
				ExecPlacementPolicy.requiresOriginResidency(
					analysis.requirePrivacy(action.sourceOccurrence())));
		}
		for(var action : analysis.graph().relocationActions()) {
			if(!analysis.graph().isRelocationActive(action, normalized.selectedStates(),
				normalized.selectedCandidateSelections()))
				continue;
			var owners = analysis.graph().nodes().stream()
				.filter(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
				.filter(node -> analysis.isCompiledHopOccurrence(node.key())).toList();
			Assert.assertFalse("Active relocation must have compiled source owners", owners.isEmpty());
			Assert.assertTrue("Every compiled owner of an active relocation source must be unprotected",
				owners.stream().noneMatch(owner -> ExecPlacementPolicy.requiresOriginResidency(
					analysis.requirePrivacy(owner.key()))));
		}
	}

	private static String between(String value, String start, String end) {
		int from = value.indexOf(start);
		Assert.assertTrue("Missing runtime-program start marker: " + start, from >= 0);
		int to = value.indexOf(end, from + start.length());
		Assert.assertTrue("Missing runtime-program end marker: " + end, to > from);
		return value.substring(from, to);
	}

	private static int count(String value, String needle) {
		int result = 0;
		for(int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length())
			result++;
		return result;
	}

	private static void assertExactRelocationEmissionCoverage(
			org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult normalized) {
		var selectedActions = normalized.selectedRelocations().stream().collect(
			java.util.stream.Collectors.toMap(action -> action.normalizedSignature(), action -> action));
		var graphActions = normalized.analysis().graph().relocationActions().stream().collect(
			java.util.stream.Collectors.toMap(action -> action.key().normalizedSignature(), action -> action));
		var expectedInputs = new java.util.TreeMap<String,java.util.Set<
			FederatedRefedRegistry.ConsumerInputSpec>>();
		var resolvedChoices = RelocationSelections.resolveAndValidate(normalized.analysis(),
			normalized.selectedStates(), normalized.selectedCandidateSelections(),
			normalized.selectedRelocationChoices());
		for(var choice : resolvedChoices) {
			String actionKey = choice.action().key().normalizedSignature();
			var graphAction = graphActions.get(actionKey);
			Assert.assertNotNull("Every selected choice must belong to the immutable placement graph", graphAction);
			Assert.assertTrue("Every selected demand must be one exact graph obligation",
				graphAction.obligations().stream().anyMatch(obligation ->
					org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey
						.from(obligation).equals(choice.receipt().demand())));
			if(!choice.requiresEmission())
				continue;
			Assert.assertEquals("Every physically emitted exact choice must reference its selected action",
				choice.action().key(), selectedActions.get(actionKey));
			long consumerHop = normalized.analysis().hop(choice.receipt().demand().consumer()).orElseThrow().getHopID();
			expectedInputs.computeIfAbsent(actionKey, ignored -> new java.util.TreeSet<>()).add(
				new FederatedRefedRegistry.ConsumerInputSpec(consumerHop,
					choice.receipt().demand().inputPosition()));
		}
		Assert.assertEquals("Selected physical actions and emission-requiring demand receipts must agree",
			selectedActions.keySet(), expectedInputs.keySet());

		var emittedInputs = new java.util.TreeMap<String,java.util.Set<
			FederatedRefedRegistry.ConsumerInputSpec>>();
		for(var scope : FederatedRefedRegistry.snapshotAll().scopes().values())
			for(var spec : scope.values())
				for(var authority : spec.getAuthorities()) {
					String actionKey = authority.getPlannerActionKey();
					Assert.assertNotNull("Exact REFED emission must retain its planner action identity", actionKey);
					var selected = selectedActions.get(actionKey);
					Assert.assertNotNull("Physical REFED emission must be backed by a selected action", selected);
					Assert.assertEquals("Physical REFED layout must equal the selected materialization layout",
						selected.materializationFType(), authority.getMaterializationFType());
					Assert.assertTrue("Exact emission must never broaden a selected input to ALL_INPUTS",
						authority.getConsumerInputs().stream().noneMatch(
							FederatedRefedRegistry.ConsumerInputSpec::allInputs));
					emittedInputs.computeIfAbsent(actionKey, ignored -> new java.util.TreeSet<>())
						.addAll(authority.getConsumerInputs());
				}
		Assert.assertEquals("Lowering must emit exactly the selected consumer-input obligations, not every "
			+ "compatible consumer of an action", expectedInputs, emittedInputs);
	}

	private static String boundaryRegistrySlots() {
		return "refed=" + FederatedRefedRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().entrySet().stream().map(entry -> scope.getKey() + ":" + entry.getKey()
				+ "->" + entry.getValue().getConsumerHopIds())).sorted().toList()
			+ ",fout=" + FederatedFoutMaterializeRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().keySet().stream().map(hop -> scope.getKey() + ":" + hop)).sorted().toList()
			+ ",local=" + FederatedLocalMaterializeRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().entrySet().stream().map(entry -> scope.getKey() + ":" + entry.getKey()
				+ "->" + entry.getValue().getConsumerHopIds()
				+ "[fType=" + entry.getValue().getFTypeHint()
				+ ",reason=" + entry.getValue().getReason() + "]")).sorted().toList();
	}

	private static String normalizedLocalSummary() {
		var result = committedResult();
		return ((java.util.List<?>) result.selectedLocalMaterializations()).stream()
			.map(value -> {
				LocalMaterializationActionKey action = (LocalMaterializationActionKey) value;
				var sourceHop = result.analysis().hop(action.sourceOccurrence()).orElseThrow();
				return sourceHop.getHopID() + "@" + sourceHop.getBeginLine() + ":" + sourceHop.getOpString()
					+ "->" + action.obligations().stream().map(obligation -> {
					long consumer = result.analysis().hop(obligation.consumerOccurrence()).orElseThrow().getHopID();
					return consumer + "=" + result.selectedStates().get(obligation.consumerOccurrence());
				}).toList();
			}).toList().toString();
	}

	private static String normalizedRelocationSummary() {
		var result = committedResult();
		return result.selectedRelocations().stream().map(action -> {
			var source = result.analysis().graph().nodes().stream()
				.filter(node -> node.valueVersion().equals(action.sourceValueVersion())).findFirst().orElseThrow();
			long sourceHop = result.analysis().hop(source.key()).orElseThrow().getHopID();
			return sourceHop + "[" + result.selectedStates().get(source.key()) + "]->"
				+ action.compatibleConsumers().stream()
					.map(consumer -> Long.toString(result.analysis().hop(consumer).orElseThrow().getHopID())).toList()
				+ " target=" + action.targetPlacement() + " materialization=" + action.materializationFType()
				+ " anchor=" + action.durableAnchor().fType();
		}).toList().toString();
	}

	private static org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult committedResult() {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed program", 1, committed.size());
		return PlacementEmissionTransaction.currentNormalizedResult(committed.keySet().iterator().next());
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "1250",
			"SYSDS_FED_COST_NET_BW_C2W", "1250",
			"SYSDS_FED_COST_NET_BW_W2C", "1250",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.001",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "1",
			"SYSDS_FED_COST_FLOPS", "2147483648");
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}

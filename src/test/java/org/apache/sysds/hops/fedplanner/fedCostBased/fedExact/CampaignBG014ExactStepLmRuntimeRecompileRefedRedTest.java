/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.Statistics;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Regression for runtime-recompiled StepLM blocks re-uploading a local formal without a FED consumer. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactStepLmRuntimeRecompileRefedRedTest {
	private static final int ROWS = 50;
	private static final int COLS = 1;

	@Test
	public void exactRecompileDoesNotUploadLocalYWithoutPhysicalFedConsumer() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		String oldRuntimeAudit = System.getProperty(PlannerRuntimePlacementAudit.PROPERTY);
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		Path root = Files.createTempDirectory(Path.of("target"), "g014-exact-steplm-refed-");
		Path features = root.resolve("features.data");
		Path labels = root.resolve("labels.data");
		try {
			writeMatrix(features, true);
			writeMatrix(labels, false);
			Path script = root.resolve("steplm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, stepLmScript(port, features, labels, root.resolve("result.csv")));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1_000);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, Boolean.TRUE.toString());
			Statistics.reset();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			long refed = Statistics.getCPHeavyHitterCount("fed_fed_refed");
			assertExecutedRefedMatchesCommittedAuthority(refed, PlannerRuntimePlacementAudit.display());
		}
		finally {
			TestUtils.shutdownThreads(worker);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			PlannerRuntimePlacementAudit.resetForTesting();
			if(oldRuntimeAudit == null)
				System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
			else
				System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, oldRuntimeAudit);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			delete(features);
			delete(labels);
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("result.csv").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("result.csv.mtd").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("scratch").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("localtmp").toString());
			Files.deleteIfExists(root.resolve("steplm.dml"));
			Files.deleteIfExists(root.resolve("SystemDS-config.xml"));
			Files.deleteIfExists(root);
		}
	}

	private static void assertExecutedRefedMatchesCommittedAuthority(long heavyHitterCount, String audit) {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed StepLM program", 1, committed.size());
		var normalized = PlacementEmissionTransaction.currentNormalizedResult(
			committed.keySet().iterator().next());
		var selectedActions = normalized.selectedRelocations().stream().collect(
			java.util.stream.Collectors.toMap(action -> action.normalizedSignature(), action -> action));
		var expectedInputs = new TreeMap<String,java.util.Set<
			FederatedRefedRegistry.ConsumerInputSpec>>();
		var resolvedChoices = RelocationSelections.resolveAndValidate(normalized.analysis(),
			normalized.selectedStates(), normalized.selectedCandidateSelections(),
			normalized.selectedRelocationChoices());
		for(var choice : resolvedChoices) {
			if(!choice.requiresEmission())
				continue;
			String actionKey = choice.action().key().normalizedSignature();
			Assert.assertEquals("Emission-requiring relocation choice must reference a committed selected action",
				choice.action().key(), selectedActions.get(actionKey));
			long consumerHop = normalized.analysis().hop(
				choice.receipt().demand().consumer()).orElseThrow().getHopID();
			expectedInputs.computeIfAbsent(actionKey, ignored -> new TreeSet<>()).add(
				new FederatedRefedRegistry.ConsumerInputSpec(consumerHop,
					choice.receipt().demand().inputPosition()));
		}
		Assert.assertEquals("Committed physical relocations must equal emission-requiring exact choices",
			selectedActions.keySet(), expectedInputs.keySet());

		var emittedInputs = new TreeMap<String,java.util.Set<
			FederatedRefedRegistry.ConsumerInputSpec>>();
		var emittedPlacements = new TreeMap<String,String>();
		for(var scope : FederatedRefedRegistry.snapshotAll().scopes().values())
			for(var spec : scope.values())
				for(var authority : spec.getAuthorities()) {
					String actionKey = authority.getPlannerActionKey();
					Assert.assertNotNull("Runtime REFED registry must retain selected action identity", actionKey);
					var action = selectedActions.get(actionKey);
					Assert.assertNotNull("Runtime REFED registry contains an uncommitted action", action);
					Assert.assertEquals("Runtime REFED layout differs from selected placement",
						action.materializationFType(), authority.getMaterializationFType());
					Assert.assertTrue("Runtime REFED must retain exact consumer-input authority",
						authority.getConsumerInputs().stream().noneMatch(
							FederatedRefedRegistry.ConsumerInputSpec::allInputs));
					emittedInputs.computeIfAbsent(actionKey, ignored -> new TreeSet<>())
						.addAll(authority.getConsumerInputs());
					emittedPlacements.put(actionKey, action.sourceValueVersion().cfgReferenceSignature()
						+ "->" + action.targetPlacement().normalizedSignature() + "/"
						+ action.materializationFType() + " consumers=" + authority.getConsumerInputs());
				}
		Assert.assertEquals("Committed REFED emissions must cover exactly the selected source/consumer inputs",
			expectedInputs, emittedInputs);

		List<RuntimeRefedExecution> executedRefed = runtimeRefedExecutions(audit);
		long auditedCount = executedRefed.stream().mapToLong(RuntimeRefedExecution::count).sum();
		Assert.assertEquals("Heavy-hitter and exact runtime-audit REFED counts must agree; emitted="
			+ executedRefed + "; audit=" + audit, heavyHitterCount, auditedCount);
		var authorizedSyntheticActions = new TreeMap<String,String>();
		for(String actionKey : selectedActions.keySet())
			authorizedSyntheticActions.put(PlannerRuntimePlacementAudit.shortHash(
				PlannerRuntimePlacementAudit.syntheticActionKey(actionKey, "REFED")), actionKey);
		for(RuntimeRefedExecution executed : executedRefed) {
			Assert.assertTrue("Runtime-recompiled coordinator emitted REFED without a committed source/consumer/"
				+ "placement receipt: emitted=" + executed + "; authorized="
				+ authorizedSyntheticActions + "; placements=" + emittedPlacements + "; audit=" + audit,
				authorizedSyntheticActions.containsKey(executed.syntheticAction()));
			String actionKey = authorizedSyntheticActions.get(executed.syntheticAction());
			String expectedPhysical = "FED/FOUT/" + selectedActions.get(actionKey).materializationFType();
			Assert.assertEquals("Runtime REFED physical placement differs from its selected action: " + executed,
				expectedPhysical, executed.plannedPhysical());
			Assert.assertEquals("Executed REFED value placement differs from its lowering receipt: " + executed,
				expectedPhysical, executed.actual());
		}
	}

	private static List<RuntimeRefedExecution> runtimeRefedExecutions(String audit) {
		java.util.ArrayList<RuntimeRefedExecution> result = new java.util.ArrayList<>();
		for(String line : audit.lines().filter(value -> value.startsWith(
				"[PlannerRuntimeAudit][Execution]") && value.contains(" opcode=fed_refed ")).toList()) {
			Assert.assertTrue("Executed REFED must have MATCH audit status: " + line,
				line.contains(" status=MATCH "));
			String action = field(line, "syntheticAction");
			Assert.assertNotEquals("Executed REFED must retain its synthetic action identity: " + line,
				"-", action);
			result.add(new RuntimeRefedExecution(action, Long.parseLong(field(line, "hop")),
				Long.parseLong(field(line, "lop")), field(line, "plannedPhysical"),
				field(line, "actual"), Long.parseLong(field(line, "count"))));
		}
		return List.copyOf(result);
	}

	private record RuntimeRefedExecution(String syntheticAction, long hopId, long lopId,
		String plannedPhysical, String actual, long count) { }

	private static String field(String line, String name) {
		String prefix = name + '=';
		int start = line.indexOf(prefix);
		Assert.assertTrue("Missing " + name + " in runtime-audit line: " + line, start >= 0);
		start += prefix.length();
		int end = line.indexOf(' ', start);
		return end < 0 ? line.substring(start) : line.substring(start, end);
	}

	private static String stepLmScript(int port, Path features, Path labels, Path output) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString())
			+ "\"), ranges=list(list(0, 0), list(" + ROWS + ", " + COLS + ")))\n"
			+ "Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString())
			+ "\"), ranges=list(list(0, 0), list(" + ROWS + ", 1)))\n"
			+ "[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=2, verbose=FALSE)\n"
			+ "write(B, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_exact</sysds.federated.planner>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static void writeMatrix(Path path, boolean features) throws Exception {
		MatrixBlock block = new MatrixBlock(ROWS, features ? COLS : 1, false);
		for(int row = 0; row < ROWS; row++)
			block.set(row, 0, features ? row + 1 : row % 2);
		block.recomputeNonZeros();
		MatrixCharacteristics mc = new MatrixCharacteristics(block.getNumRows(), block.getNumColumns(),
			1_000, block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block,
			path.toString(), block.getNumRows(), block.getNumColumns(), 1_000, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			mc, FileFormat.BINARY, null, "private-aggregate");
	}

	private static void delete(Path path) throws Exception {
		HDFSTool.deleteFileIfExistOnHDFS(path.toString());
		HDFSTool.deleteFileIfExistOnHDFS(path + ".mtd");
	}
}

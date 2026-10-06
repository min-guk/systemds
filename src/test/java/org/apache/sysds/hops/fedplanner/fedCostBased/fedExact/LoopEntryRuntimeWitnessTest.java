/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.api.ScriptExecutorUtils;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.BasicProgramBlock;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedStatistics;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.utils.Statistics;
import org.junit.Assert;
import org.junit.Test;

/** Full compiler-to-worker witness for the one-time loop-entry upload. */
@net.jcip.annotations.NotThreadSafe
public class LoopEntryRuntimeWitnessTest {
	@Test
	public void selectedRowEntryUploadKeepsFederatedSteadyLoop() throws Exception {
		runWitness(FType.ROW);
	}

	@Test
	public void selectedBroadcastEntryUploadKeepsFederatedSteadyLoop() throws Exception {
		runWitness(FType.BROADCAST);
	}

	private static void runWitness(FType forcedType) throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		Path root = Files.createTempDirectory("loop-entry-runtime-");
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			int available1 = AutomatedTestBase.getRandomAvailablePort();
			int available2 = AutomatedTestBase.getRandomAvailablePort();
			while(available2 == available1)
				available2 = AutomatedTestBase.getRandomAvailablePort();
			int firstPort = Math.max(available1, available2);
			int secondPort = Math.min(available1, available2);
			writeProtectedSlice(root.resolve("X1"), 1.0);
			writeProtectedSlice(root.resolve("X2"), 101.0);
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(firstPort, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(secondPort, 1000);

			DMLConfig config = new DMLConfig(oldConfig);
			config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_exact");
			CompilerConfig compiler = OptimizerUtils.constructCompilerConfig(config);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			ConfigurationManager.setGlobalConfig(compiler);
			ConfigurationManager.setLocalConfig(compiler);
			DMLScript.setGlobalExecMode(ExecMode.SINGLE_NODE);
			DMLScript.STATISTICS = true;
			DMLScript.STATISTICS_COUNT = 50;
			Statistics.reset();
			FederatedStatistics.reset();
			PlacementEmissionTransaction.resetForTesting();

			Path initial = root.resolve("initial.scalar");
			Path result = root.resolve("result.scalar");
			Path protectedSum = root.resolve("xsum.scalar");
			String script = script(firstPort, secondPort, root, initial, result, protectedSum);
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
				program, Privacy.PRIVATE_AGGREGATE);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			ExactPlacementInput selected = forceFederatedLoop(analysis, forcedType);

			program.getPlannerRecompileAuthority().beginPlanning();
			var emission = PlacementEmissionTransaction.emit(program,
				selected.normalizedResult(), FailureInjector.none());
			program.getPlannerRecompileAuthority().seal();
			Assert.assertTrue("the exact forced selection must mutate the compiled HOPs", emission.applied());
			Assert.assertEquals("one graph-owned loop-entry upload must be registered", 1,
				FederatedFoutMaterializeRegistry.snapshotAll().scopes().values().stream()
					.mapToInt(Map::size).sum());

			for(StatementBlock block : program.getStatementBlocks())
				translator.constructLops(block);
			Program runtime = translator.getRuntimeProgram(program, config);
			List<InstructionLocation> instructions = instructions(runtime.getProgramBlocks(), false);
			List<InstructionLocation> uploads = instructions.stream()
				.filter(location -> location.instruction().getInstructionString().contains("fed_fout"))
				.toList();
			Assert.assertEquals("the initializer must lower to one physical fed_fout", 1, uploads.size());
			Assert.assertFalse("the one-time upload must be outside the loop body", uploads.get(0).nested());
			Assert.assertTrue("the loop body must retain a real federated plus instruction",
				instructions.stream().anyMatch(location -> location.nested()
					&& location.instruction() instanceof org.apache.sysds.runtime.instructions.fed.FEDInstruction
					&& "+".equals(location.instruction().getOpcode())));

			ExecutionContext context = ExecutionContextFactory.createContext(runtime);
			ScriptExecutorUtils.executeRuntimeProgram(runtime, context, config,
				DMLScript.STATISTICS_COUNT, null);
			double initialValue = Double.parseDouble(Files.readString(initial).trim());
			double finalValue = Double.parseDouble(Files.readString(result).trim());
			Assert.assertEquals("three p=p+1 iterations over 8x2 add exactly 48",
				48.0, finalValue - initialValue, 1e-10);
			Assert.assertEquals("reverse-lexical workers must retain their declared ROW ranges",
				5564.0, Double.parseDouble(Files.readString(protectedSum).trim()), 1e-10);
			Assert.assertTrue("the federated loop plus must execute once per iteration",
				Statistics.getCPHeavyHitterCount("fed_+") >= 3);
			String workerStats = FederatedStatistics.displayFedIOExecStatistics();
			Assert.assertTrue("runtime must issue federated worker reads: " + workerStats,
				workerStats.contains("Federated I/O (Read, Put, Get):"));
			Assert.assertTrue("fed_fout must transfer real matrix slices to the workers: " + workerStats,
				FederatedStatistics.getTotalFedTransferCount() >= 2);
			var observed = PlacementEmissionTransaction.observabilitySnapshot();
			Assert.assertEquals(0, observed.runtimeFallbackCount());
			Assert.assertEquals(0, observed.runtimeRepairCount());
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.setGlobalExecMode(oldMode);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			FederatedStatistics.reset();
			try(var paths = Files.walk(root)) {
				for(Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	private static ExactPlacementInput forceFederatedLoop(PlacementAnalysis analysis, FType forcedType) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel.DecisionDomain initializer = model.domains().stream()
			.filter(domain -> analysis.hop(domain.node().key()).orElse(null) instanceof DataGenOp hop
				&& hop.getDim1() == 8 && hop.getDim2() == 2)
			.findFirst().orElseThrow(AssertionError::new);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
		int forced = force(initializer, factors, alternative -> {
			PlacementState state = alternative.state();
			return state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT
				&& state.fType() == forcedType && alternative.derivedFoutAction() != null;
		});
		Assert.assertTrue("fixture must expose a graph-owned CP/FOUT " + forcedType + " entry", forced >= 0);
		List<ExactPhysicalModel.DecisionDomain> steady = model.domains().stream().filter(domain -> {
			var hop = analysis.hop(domain.node().key()).orElse(null);
			boolean loopBody = domain.node().key().controlRegion().regionPath().stream()
				.anyMatch(part -> part.contains("loop-body"));
			return loopBody && (hop instanceof DataOp data && "p".equals(data.getName())
				&& (data.getOp() == OpOpData.TRANSIENTREAD || data.getOp() == OpOpData.TRANSIENTWRITE)
				|| hop instanceof BinaryOp binary && binary.getOp() == OpOp2.PLUS
					&& binary.getDataType().isMatrix());
		}).toList();
		Assert.assertTrue("fixture must retain the loop read, plus, and back write", steady.size() >= 3);
		for(ExactPhysicalModel.DecisionDomain domain : steady)
			Assert.assertTrue("every steady loop owner must expose FED/FOUT " + forcedType + ": "
				+ domain.node().key(),
				force(domain, factors, alternative -> {
					PlacementState state = alternative.state();
					return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
						&& state.fType() == forcedType;
				}) >= 0);
		for(ExactPhysicalModel.DecisionDomain domain : model.domains())
			if(domain != initializer)
				Assert.assertTrue("only the initializer may select a derived output upload: "
					+ domain.node().key(), force(domain, factors,
						alternative -> alternative.derivedFoutAction() == null) >= 0);
		ExactCategoricalSolver.Result solved = ExactCategoricalSolver.solve(model.variables(), factors,
			new ExactCategoricalSolver.Limits(Integer.MAX_VALUE, Long.MAX_VALUE));
		ExactPhysicalOptimizer.Result optimized = new ExactPhysicalOptimizer.Result(solved,
			Double.doubleToRawLongBits(0.0), "forced-loop-entry-runtime-witness");
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model, optimized);
		Assert.assertSame(initializer.alternatives().get(forced).state(),
			selection.selectedStates().get(initializer.node().key()));
		for(ExactPhysicalModel.DecisionDomain domain : steady) {
			PlacementState state = selection.selectedStates().get(domain.node().key());
			Assert.assertEquals(ExecType.FED, state.execType());
			Assert.assertEquals(FederatedOutput.FOUT, state.output());
			Assert.assertEquals(forcedType, state.fType());
		}
		return ExactPhysicalPlacementProjector.project(selection,
			"Exact-Forced-Loop-Entry-Witness", "forced-graph-owned-entry-upload");
	}

	private static int force(ExactPhysicalModel.DecisionDomain domain,
		List<ExactCategoricalSolver.Factor> factors,
		java.util.function.Predicate<ExactPhysicalModel.Alternative> predicate) {
		int selected = -1;
		double[] costs = new double[domain.alternatives().size()];
		java.util.Arrays.fill(costs, Double.POSITIVE_INFINITY);
		for(int index = 0; index < domain.alternatives().size(); index++)
			if(predicate.test(domain.alternatives().get(index))) {
				if(selected < 0)
					selected = index;
				costs[index] = 0.0;
			}
		if(selected < 0)
			return -1;
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), costs));
		return selected;
	}

	private static String script(int port1, int port2, Path root, Path initial,
		Path result, Path protectedSum) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port1,
			root.resolve("X1").toString()) + "\",\"" + TestUtils.federatedAddress(port2,
				root.resolve("X2").toString()) + "\"),ranges=list(list(0,0),list(4,2),"
			+ "list(4,0),list(8,2)));"
			+ "p=rand(rows=8,cols=2,seed=7);initial=sum(p);"
			+ "for(i in 1:3){p=p+1;}final_sum=sum(p);x_sum=sum(rowSums(X)*seq(1,8));"
			+ "write(initial,\"" + initial + "\");"
			+ "write(final_sum,\"" + result + "\");"
			+ "write(x_sum,\"" + protectedSum + "\");";
	}

	private static void writeProtectedSlice(Path path, double firstValue) throws Exception {
		MatrixBlock block = new MatrixBlock(4, 2, false);
		block.allocateDenseBlock();
		for(int row = 0, value = 0; row < 4; row++)
			for(int col = 0; col < 2; col++, value++)
				block.set(row, col, firstValue + value);
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block,
			path.toString(), 4, 2, 1000, 8);
		Files.writeString(Path.of(path + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\",\"rows\":4,"
			+ "\"cols\":2,\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":8,"
			+ "\"privacy\":\"private-aggregate\"}");
	}

	private static List<InstructionLocation> instructions(List<ProgramBlock> blocks, boolean nested) {
		List<InstructionLocation> result = new ArrayList<>();
		for(ProgramBlock block : blocks) {
			if(block instanceof BasicProgramBlock basic)
				for(Instruction instruction : basic.getInstructions())
					result.add(new InstructionLocation(instruction, nested));
			List<ProgramBlock> children = block.getChildBlocks();
			if(children != null)
				result.addAll(instructions(children, true));
		}
		return List.copyOf(result);
	}

	private record InstructionLocation(Instruction instruction, boolean nested) { }
}

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
package org.apache.sysds.parser;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.placement.SearchSpaceMetrics;
import org.junit.Assert;
import org.junit.Test;

public class SearchSpaceOnlyBoundaryTest {
	private static final String SCRIPT =
		"X=matrix(seq(1,16),rows=4,cols=4); Y=t(X)%*%X; print(sum(Y));";

	@Test
	public void searchSpaceOnlyStopsBeforeSelectorAndRuntimeEmission() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_heuristic_single_pass");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		try {
			DMLProgram program = compileThroughFinalHopRewrite(SCRIPT);
			DMLTranslator.SearchSpaceOnlyReceipt receipt =
				new DMLTranslator(program).prepareSearchSpaceOnly(program);

			Assert.assertTrue(receipt.spaceReady());
			Assert.assertEquals(0, receipt.selectorInvocationCount());
			Assert.assertEquals(0, receipt.runtimeProgramEmissionCount());
			Assert.assertEquals(0, receipt.workloadExecutionCount());
			Assert.assertTrue(receipt.commonPreparationNanos() >= 0);
			Assert.assertTrue(receipt.analysisNanos() > 0);
			Assert.assertEquals(receipt.commonPreparationNanos() + receipt.analysisNanos()
				+ receipt.boundaryFinalizationNanos(),
				receipt.searchSpaceNanos());
			Assert.assertTrue(receipt.diagnosticSnapshotNanos() >= 0);
			Assert.assertNotNull(program.requirePlacementAnalysisAuthority());
			Assert.assertEquals(receipt.analysisFingerprint(),
				program.requirePlacementAnalysisAuthority().analysisFingerprint());
			Assert.assertEquals(1, receipt.attribution().phases().stream()
				.filter(phase -> phase.phase().equals(SearchSpaceMetrics.Phase.ANALYSIS.name()))
				.findFirst().orElseThrow().calls());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void consecutiveSearchSpaceOnlyCompilationsDoNotLeakBoundaryState() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_heuristic_single_pass");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		try {
			DMLProgram first = compileThroughFinalHopRewrite(SCRIPT);
			DMLTranslator.SearchSpaceOnlyReceipt firstReceipt =
				new DMLTranslator(first).prepareSearchSpaceOnly(first);
			DMLProgram second = compileThroughFinalHopRewrite(SCRIPT);
			DMLTranslator.SearchSpaceOnlyReceipt secondReceipt =
				new DMLTranslator(second).prepareSearchSpaceOnly(second);

			Assert.assertTrue(firstReceipt.spaceReady());
			Assert.assertTrue(secondReceipt.spaceReady());
			Assert.assertEquals(firstReceipt.analysisFingerprint(), secondReceipt.analysisFingerprint());
			Assert.assertNotSame(first.requirePlacementAnalysisAuthority(),
				second.requirePlacementAnalysisAuthority());
			Assert.assertEquals(0, secondReceipt.selectorInvocationCount());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void failedBoundaryAttemptDoesNotPoisonNextCompilation() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_heuristic_single_pass");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		try {
			DMLProgram first = compileThroughFinalHopRewrite(SCRIPT);
			DMLTranslator translator = new DMLTranslator(first);
			Assert.assertTrue(translator.prepareSearchSpaceOnly(first).spaceReady());
			try {
				translator.prepareSearchSpaceOnly(first);
				Assert.fail("a final-Hop boundary cannot be bound twice");
			}
			catch(IllegalStateException expected) {
				Assert.assertTrue(expected.getMessage().contains("rewritten after placement authority binding"));
			}
			DMLProgram next = compileThroughFinalHopRewrite(SCRIPT);
			DMLTranslator.SearchSpaceOnlyReceipt nextReceipt =
				new DMLTranslator(next).prepareSearchSpaceOnly(next);
			Assert.assertTrue(nextReceipt.spaceReady());
			Assert.assertEquals(0, nextReceipt.selectorInvocationCount());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	private static DMLProgram compileThroughFinalHopRewrite(String script) throws Exception {
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

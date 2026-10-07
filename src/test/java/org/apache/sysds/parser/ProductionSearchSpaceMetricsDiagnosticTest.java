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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.placement.SearchSpaceMetrics;
import org.junit.Assert;
import org.junit.Test;

public class ProductionSearchSpaceMetricsDiagnosticTest {
	private static final String SCRIPT =
		"X=matrix(seq(1,16),rows=4,cols=4); Y=t(X)%*%X; print(sum(Y));";

	@Test
	public void productionMetricsRemainDefaultOffAndEmitOnlyWhenEnabled() throws Exception {
		String previous = System.getProperty(DMLTranslator.PRODUCTION_METRICS_PROPERTY);
		PrintStream oldErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.clearProperty(DMLTranslator.PRODUCTION_METRICS_PROPERTY);
			Assert.assertNull(DMLTranslator.productionSearchSpaceMetrics());
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			DMLTranslator.emitProductionSearchSpaceMetrics(null, "disabled");
			Assert.assertEquals("", captured.toString(StandardCharsets.UTF_8));

			System.setProperty(DMLTranslator.PRODUCTION_METRICS_PROPERTY, "true");
			SearchSpaceMetrics metrics = DMLTranslator.productionSearchSpaceMetrics();
			Assert.assertNotNull(metrics);
			DMLTranslator.emitProductionSearchSpaceMetrics(metrics, "fingerprint");
			String line = captured.toString(StandardCharsets.UTF_8);
			Assert.assertTrue(line.contains("SEARCH_SPACE_TOPOLOGY|analysis=fingerprint"));
			Assert.assertTrue(line.contains("|builds=0|hits=0|rowsBuilt=0"));
			Assert.assertTrue(line.contains("|evictions=0|bypasses=0|residentEntries=0|retainedRows=0"));
			Assert.assertTrue(line.contains(
				"SEARCH_SPACE_SIGNATURE_ADMISSION|analysis=fingerprint|identityHits=0"));
			Assert.assertTrue(line.contains("|structuralHits=0|misses=0|admitted=0"));
			Assert.assertFalse(line.contains("SEARCH_SPACE_SIGNATURE_ADMISSION_CLASS"));
		}
		finally {
			System.setErr(oldErr);
			if(previous == null)
				System.clearProperty(DMLTranslator.PRODUCTION_METRICS_PROPERTY);
			else
				System.setProperty(DMLTranslator.PRODUCTION_METRICS_PROPERTY, previous);
		}
	}

	@Test
	public void detailedMetricsPreserveCommonAnalysisFingerprint() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_heuristic_single_pass");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		try {
			DMLProgram disabledProgram = compileThroughFinalHopRewrite(SCRIPT);
			String withoutMetrics = new DMLTranslator(disabledProgram)
				.prepareSearchSpaceOnly(disabledProgram, false).analysisFingerprint();
			DMLProgram enabledProgram = compileThroughFinalHopRewrite(SCRIPT);
			String withMetrics = new DMLTranslator(enabledProgram)
				.prepareSearchSpaceOnly(enabledProgram, true).analysisFingerprint();
			Assert.assertEquals(withoutMetrics, withMetrics);
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

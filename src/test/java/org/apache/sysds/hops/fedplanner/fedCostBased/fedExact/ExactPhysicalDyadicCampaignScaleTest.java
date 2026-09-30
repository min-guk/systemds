/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Real-scale compiler/cost fixture; no worker or workload execution, and no performance assertion. */
public class ExactPhysicalDyadicCampaignScaleTest {
	@Test
	public void protectedCalibratedGlmHasCertifiedSumAndSharedSourceEncoding() throws Exception {
		// Cost calibration is captured in static finals. A fresh JVM avoids test-order
		// dependence and never changes calibration in the parent test process.
		Path log = Files.createTempFile("dyadic-campaign-certificate-", ".log");
		Process process = null;
		try {
			ProcessBuilder builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
				"--add-modules", "jdk.incubator.vector", "-Xmx16g", "-XX:ActiveProcessorCount=8",
				"-Dsysds.fedplanner.structuralArena.maxEntries=262144",
				"-Dsysds.fedplanner.structuralArena.maxIdentityEntries=262144",
				"-Dsysds.fedplanner.signatureCache.maxChars=536870912",
				"-cp", System.getProperty("java.class.path"), getClass().getName(), "campaign-probe");
			builder.environment().putAll(java.util.Map.ofEntries(
				java.util.Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"),
				java.util.Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
				java.util.Map.entry("SYSDS_FED_COST_NET_BW", "625"),
				java.util.Map.entry("SYSDS_FED_COST_NET_BW_C2W", "625"),
				java.util.Map.entry("SYSDS_FED_COST_NET_BW_W2C", "625"),
				java.util.Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.001"),
				java.util.Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
				java.util.Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
				java.util.Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
				java.util.Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0.35")));
			process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
			Assert.assertTrue("hermetic compiler/certificate child timed out", process.waitFor(60, TimeUnit.SECONDS));
			String output = Files.readString(log);
			System.out.print(output);
			Assert.assertEquals(output, 0, process.exitValue());
			Assert.assertTrue(output, output.contains("EXACT_CAMPAIGN_CERTIFICATE_OK"));
		}
		finally {
			if(process != null && process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
			Files.deleteIfExists(log);
		}
	}

	public static void main(String[] args) throws Exception {
		if(args.length != 1 || !"campaign-probe".equals(args[0]))
			throw new IllegalArgumentException("CAMPAIGN_CERTIFICATE_PROBE_ARGUMENT_REQUIRED");
		DMLConfig config = new DMLConfig();
		DMLScript.setGlobalExecMode(ExecMode.SINGLE_NODE);
		DMLScript.SEED = 1011081480;
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "COMPILE_EXACT");
		config.setTextValue(DMLConfig.NATIVE_BLAS, "mkl");
		config.setTextValue("sysds.local.spark", "true");
		config.setTextValue("sysds.benchmark.compile_only", "true");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setGlobalConfig(OptimizerUtils.constructCompilerConfig(config));
		DMLScript.setGlobalFlags(config);
		// Frozen rendered source; source metadata below is overridden before any RPC.
		DMLScript.DML_FILE_PATH_ANTLR_PARSER = "tmp/cell.dml";
		String script = """
			X = federated(addresses=list("130.149.237.12:8001//workspace/experiments/data/continuous/ADULT_features_3_1.data","130.149.237.13:8002//workspace/experiments/data/continuous/ADULT_features_3_2.data","130.149.237.14:8003//workspace/experiments/data/continuous/ADULT_features_3_3.data"), ranges=list(list(0,0),list(16667,128),list(16667,0),list(33334,128),list(33334,0),list(50000,128)))
			Y = federated(addresses=list("130.149.237.12:8001//workspace/experiments/data/continuous/ADULT_labels_3_1.data","130.149.237.13:8002//workspace/experiments/data/continuous/ADULT_labels_3_2.data","130.149.237.14:8003//workspace/experiments/data/continuous/ADULT_labels_3_3.data"), ranges=list(list(0,0),list(16667,1),list(16667,0),list(33334,1),list(33334,0),list(50000,1)))

			# The frozen synthetic response is continuous.  A mean threshold yields a
			# deterministic, approximately balanced binary target via a privacy-permitted
			# aggregate instead of treating only the single maximum row as positive.
			threshold = mean(Y)
			Y = (Y > threshold) * 1

			beta = glm(
			  X=X, Y=Y,
			  dfam=2, vpow=0.0, link=2, lpow=1.0, yneg=0.0,
			  icpt=0, disp=0.0, reg=0.0, tol=1e-6, moi=20, mii=5,
			  verbose=FALSE
			)

			write(beta, "tmp/w1357/529ac865dd4557cff967/result", format="csv")
			""";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int sources = 0;
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops())
					sources += registerSource(root, visited);
		Assert.assertEquals("every source must have a hermetic override before preparation", 2, sources);
		translator.prepareSearchSpaceOnly(program, false);
		var analysis = program.requirePlacementAnalysisAuthority();
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		var positiveCountBound = ExactDyadicCosts.certify(certificate.q(), certificate.maximumSumBits(),
			certificate.accumulationContributionCount(), true);

		System.out.println("EXACT_CAMPAIGN_CENSUS|q=" + certificate.q()
			+ "|B=" + certificate.maximumSumBits() + "|canonical=" + certificate.canonicalContributionCount()
			+ "|positive=" + certificate.accumulationContributionCount()
			+ "|headroom=" + certificate.bitsWithAccumulationHeadroom() + "|reason=" + certificate.reason());
		Assert.assertEquals("INSUFFICIENT_ACCUMULATION_HEADROOM", positiveCountBound.reason());
		Assert.assertFalse(positiveCountBound.supported());
		Assert.assertEquals(-77, certificate.q());
		Assert.assertEquals(97, certificate.maximumSumBits());
		// Lock this hermetic inventory, not the Docker inventory (5063/1519).
		// Remote compilation is an independent acceptance gate, not inferred here.
		Assert.assertEquals(5091, certificate.canonicalContributionCount());
		Assert.assertEquals(1525, certificate.accumulationContributionCount());
		Assert.assertEquals(108, certificate.bitsWithAccumulationHeadroom());
		Assert.assertTrue("protected calibrated fixture needs the stronger ordered proof", certificate.supported());
		Assert.assertEquals("CERTIFIED_ORDERED_RESIDUAL_BOUND", certificate.reason());
		Assert.assertEquals(surface.contributions().size(), certificate.canonicalContributionCount());
		Assert.assertTrue(certificate.accumulationContributionCount() < certificate.canonicalContributionCount());
		certificate.validateSurface(surface);
		certificate.validateSourceFactors(surface.exactSolverFactors());
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, java.util.List.of(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		System.out.println("EXACT_CAMPAIGN_ENCODING|" + encoding.statistics());
		Assert.assertTrue("protected calibrated source relation must be representable: "
			+ encoding.statistics().reason(), encoding.statistics().transformed());
		System.out.println("EXACT_CAMPAIGN_CERTIFICATE_OK");
	}

	private static int registerSource(Hop hop, Set<Hop> visited) {
		if(!visited.add(hop))
			return 0;
		int sources = 0;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			Assert.assertTrue("only the frozen X/Y widths are valid", data.getDim2() == 128 || data.getDim2() == 1);
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data,
				data.getDim2() == 128 ? Privacy.PRIVATE_AGGREGATE : Privacy.PUBLIC);
			sources++;
		}
		for(Hop input : hop.getInput())
			sources += registerSource(input, visited);
		return sources;
	}

}

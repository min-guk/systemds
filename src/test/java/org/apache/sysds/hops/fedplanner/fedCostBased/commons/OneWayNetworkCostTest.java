/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.junit.Assert;
import org.junit.Test;

/** Hermetic pricing checks: one-way latency + wire + codec, never a control intercept. */
public class OneWayNetworkCostTest {

	@Test public void standaloneGetAndPutShareDirectionalStagePrimitive() throws Exception { probe("standalone"); }
	@Test public void inBandPayloadDoesNotRepeatRequestOrResponseLatency() throws Exception { probe("inband"); }
	@Test public void controlSettingDoesNotChangePrice() throws Exception { probe("control"); }
	@Test public void retiredControlEnvironmentIsAlsoIgnored() throws Exception { probe("control-env"); }
	@Test public void defaultRoundTripIsNotDoubled() throws Exception { probe("default"); }
	@Test public void zeroLatenciesAreHonored() throws Exception { probe("zero"); }
	@Test public void negativeOneWayLatencyIsRejected() throws Exception { probe("negative"); }
	@Test public void nonfiniteOneWayLatencyIsRejected() throws Exception { probe("nan"); }
	@Test public void zeroPayloadStageStillHasLatency() throws Exception { probe("empty"); }
	@Test public void instructionFrequencyAndMetadataPreserveStageExistence() throws Exception { probe("instruction"); }
	@Test public void directionalEnvironmentValuesAreOneWaySeconds() throws Exception { probe("direction-env"); }
	@Test public void directionalPropertyOverridesEnvironment() throws Exception { probe("direction-precedence"); }

	private static void probe(String mode) throws Exception {
		List<String> args = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java",
			"-DSYSDS_FED_COST_NET_BW_C2W=100", "-DSYSDS_FED_COST_NET_BW_W2C=125",
			"-DSYSDS_FED_COST_NET_SERDES_BW_C2W=50", "-DSYSDS_FED_COST_NET_SERDES_BW_W2C=40"));
		if(!mode.equals("default") && !mode.equals("direction-env")) {
			String c2w = mode.equals("negative") ? "-0.003" : mode.equals("nan") ? "NaN"
				: mode.equals("zero") ? "0" : "0.003";
			args.add("-DSYSDS_FED_COST_NET_LATENCY_C2W=" + c2w);
			args.add("-DSYSDS_FED_COST_NET_LATENCY_W2C=" + (mode.equals("zero") ? "0" : "0.007"));
		}
		if(mode.equals("control")) args.add("-DSYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=9000");
		args.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
		builder.environment().keySet().removeIf(key -> key.startsWith("SYSDS_FED_COST_"));
		if(mode.equals("control-env")) builder.environment().put("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "9000");
		if(mode.startsWith("direction-")) {
			builder.environment().put("SYSDS_FED_COST_NET_LATENCY_C2W", mode.equals("direction-env") ? ".003" : ".4");
			builder.environment().put("SYSDS_FED_COST_NET_LATENCY_W2C", mode.equals("direction-env") ? ".007" : ".6");
		}
		Process child = builder.start();
		String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = child.waitFor();
		if(mode.equals("negative") || mode.equals("nan")) {
			Assert.assertNotEquals(output, 0, status);
			Assert.assertTrue(output, output.contains("FED_COST_INVALID_LATENCY: SYSDS_FED_COST_NET_LATENCY_C2W"));
		}
		else Assert.assertEquals(output, 0, status);
	}

	public static class Probe {
		private static final double MIB = 1024.0 * 1024.0;
		public static void main(String[] args) throws Exception {
			String mode = args[0];
			double expectedLatency = mode.equals("default") ? 1 : mode.equals("zero") ? 0 : 10;
			Assert.assertEquals("No fixed/control charge, no hidden RTT addend", expectedLatency,
				FederatedCostModel.computeRequestResponseLatency(), 1e-12);
			if(mode.equals("instruction")) {
				DataOp input = new DataOp("X", DataType.MATRIX, ValueType.FP64,
					OpOpData.TRANSIENTREAD, "X", 10, 10, 100, 1000);
				BinaryOp plus = new BinaryOp("+", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, input, input);
				for(double frequency : new double[] {0.0, .5, 1.0, 7.0})
					Assert.assertEquals(frequency * 10,
						FederatedCostModel.computeFederatedInstructionNetworkCost(plus, frequency), 1e-12);
				Assert.assertEquals(0, FederatedCostModel.computeFederatedInstructionNetworkCost(input, 1), 0);
				var transpose = HopRewriteUtils.createTranspose(input);
				Assert.assertEquals("FULL transpose still executes PUT_VAR + EXEC on the worker", 10,
					FederatedCostModel.computeFederatedInstructionNetworkCost(transpose, 1), 0);
			}
			if(mode.equals("empty")) {
				Assert.assertEquals(10, FederatedCostModel.computeDownloadNetworkCost(0), 1e-12);
				Assert.assertEquals(10, FederatedCostModel.computeUploadNetworkCost(0, FType.ROW, 3), 1e-12);
				Assert.assertEquals(0, FederatedCostModel.computeInBandUploadPayloadCost(0, FType.ROW, 3), 0);
				var stage = FederatedCostModel.class.getDeclaredMethod("computeOneWayNetworkCost",
					double.class, double.class, double.class, double.class, double.class, double.class, double.class);
				stage.setAccessible(true);
				Assert.assertEquals(3, (double)stage.invoke(null, 0d, 0d, 0d, 100d, 0d, 50d, .003), 0);
				Assert.assertEquals(403, (double)stage.invoke(null, 20*MIB, 20*MIB, 10*MIB, 100d, 0d, 50d, .003), 1e-10);
			}
			for(int workers : new int[] {1, 3, 7}) {
				double getPayload = (12.0 / workers / 125 + 12.0 / 40) * 1000;
				Assert.assertEquals(getPayload + expectedLatency,
					FederatedCostModel.computeDownloadNetworkCost(12*MIB, FType.ROW, workers), 1e-9);
				Assert.assertEquals(getPayload + expectedLatency,
					FederatedCostModel.computeReusableMaterializationDownloadCost(12*MIB, FType.ROW, workers), 1e-9);
				for(FType type : new FType[] {FType.ROW, FType.FULL, FType.BROADCAST}) {
					double total = type == FType.BROADCAST ? 12.0*workers : 12.0;
					double putPayload = (total / 100 + total / 50)*1000;
					Assert.assertEquals(putPayload + expectedLatency,
						FederatedCostModel.computeUploadNetworkCost(12*MIB, type, workers), 1e-9);
					Assert.assertEquals(putPayload,
						FederatedCostModel.computeInBandUploadPayloadCost(12*MIB, type, workers), 1e-9);
				}
			}
		}
	}
}

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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Assert;
import org.junit.Test;

/** Regression contract for purpose-independent GET response pricing. */
public class FederatedNetworkCostUnificationTest {
	private static final double MIB = 1024D * 1024D;

	@Test
	public void purePayloadArithmeticHasNoFixedStage() throws Exception {
		double actual = payload(20 * MIB, 10 * MIB, 100.0, 50.0);
		Assert.assertEquals((20.0 / 100.0 + 10.0 / 50.0) * 1000.0, actual, 1e-12);
	}

	@Test
	public void payloadArithmeticSanitizesQuantitiesAndDisablesInvalidCodecRates() throws Exception {
		Assert.assertEquals(0.0, payload(0.0, 0.0, 100.0, 50.0), 0.0);
		Assert.assertEquals(0.0, payload(Double.NaN, Double.POSITIVE_INFINITY, 100.0, 50.0), 0.0);
		Assert.assertEquals(100.0, payload(10 * MIB, 10 * MIB, 100.0, 0.0), 1e-12);
		Assert.assertEquals(100.0, payload(10 * MIB, 10 * MIB, 100.0, Double.NaN), 1e-12);
	}

	@Test
	public void allGetPurposesShareOnePayloadPriceForWorkersOneThreeAndSixteen() throws Exception {
		for(int workers : new int[] {1, 3, 16}) {
			double payload = getPayload(96 * MIB, workers, 120.0, 30.0);
			double inBand = getResponse(96 * MIB, workers, 120.0, 30.0, 0, 0.030, 0.050);
			double standalonePayload = getResponse(96 * MIB, workers, 120.0, 30.0, 1, 0.030, 0.050) - 80.0;
			Assert.assertEquals(payload, inBand, 0.0);
			Assert.assertEquals("GET purpose must affect only batch count for workers=" + workers,
				payload, standalonePayload, 1e-9);
		}
	}

	@Test
	public void aggregateProcessingDoesNotAcquireUnmeasuredThreadSpeedup() throws Exception {
		for(int workers : new int[] {1, 3, 8, 16})
			Assert.assertEquals((160.0 / workers / 100.0 + 160.0 / 50.0) * 1000.0,
				getPayload(160 * MIB, workers, 100.0, 50.0), 1e-9);
	}

	@Test
	public void responsePriceIsLinearAcrossFormerSizeThreshold() throws Exception {
		for(int workers : new int[] {1, 3, 16})
			for(double perWorker : new double[] {3.999, 4, 4.001})
				Assert.assertEquals((perWorker / 100 + perWorker * workers / 20) * 1000,
					getPayload(perWorker * workers * MIB, workers, 100, 20), 1e-8);
	}

	@Test
	public void standaloneGetOwnsOneBatchAndInBandGetOwnsNone() throws Exception {
		double inBand = getResponse(24 * MIB, 3, 120.0, 30.0, 0, 0.030, 0.050);
		double standalone = getResponse(24 * MIB, 3, 120.0, 30.0, 1, 0.030, 0.050);
		Assert.assertEquals(80.0, standalone - inBand, 1e-9);
	}

	@Test
	public void zeroPayloadDoesNotEraseExplicitBatchOwnership() throws Exception {
		Assert.assertEquals(80.0, getResponse(0, 3, 120.0, 30.0, 1, 0.030, 0.050), 1e-9);
		Assert.assertEquals(0.0, getResponse(0, 3, 120.0, 30.0, 0, 0.030, 0.050), 0.0);
	}

	@Test
	public void ordinaryAndReusablePublicGetsHaveIdenticalPrice() throws Exception {
		runPublicProbe("same-price");
	}

	@Test
	public void replicatedAndPartialCollectsCountAllResponses() throws Exception {
		runPublicProbe("all-responses");
	}

	@Test
	public void fullUploadUsesOneNonReplicatedTarget() {
		for(int workers : new int[] {1, 3, 16}) {
			Assert.assertEquals(FederatedCostModel.computeUploadNetworkCost(MIB, FType.FULL, 1),
				FederatedCostModel.computeUploadNetworkCost(MIB, FType.FULL, workers), 0.0);
			Assert.assertEquals(FederatedCostModel.computeInBandUploadPayloadCost(MIB, FType.FULL, 1),
				FederatedCostModel.computeInBandUploadPayloadCost(MIB, FType.FULL, workers), 0.0);
		}
	}

	@Test
	public void partialWdivmmResponseHasFullOutputPerWorkerAndNoAdditionalBatch() {
		for(int workers : new int[] {1, 3, 16}) {
			double getPayload = FederatedCostModel.computeDownloadNetworkCost(MIB, FType.PART, workers)
				- FederatedCostModel.computeRequestResponseLatency();
			double actual = FederatedCostModel.computeWdivmmLoutResultCost(1, FType.ROW, MIB, workers);
			Assert.assertTrue("Full partial response payload plus coordinator aggregation", actual >= getPayload);
			Assert.assertEquals(actual,
				FederatedCostModel.computeWdivmmLoutResultCost(2, FType.COL, MIB, workers), 0.0);
			Assert.assertEquals(FederatedCostModel.computeDownloadNetworkCost(MIB, FType.ROW, workers)
				- FederatedCostModel.computeRequestResponseLatency(),
				FederatedCostModel.computeWdivmmLoutResultCost(2, FType.ROW, MIB, workers), 1e-9);
		}
	}

	private static void runPublicProbe(String mode) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
		command.add("-DSYSDS_FED_COST_NET_BW_W2C=125");
		command.add("-DSYSDS_FED_COST_NET_SERDES_BW_W2C=14.7");
		command.add("-DSYSDS_FED_COST_NET_LATENCY_C2W=0.0005");
		command.add("-DSYSDS_FED_COST_NET_LATENCY_W2C=0.0005");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(PublicGetPriceProbe.class.getName());
		command.add(mode);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		StringBuilder output = new StringBuilder();
		try(BufferedReader reader = new BufferedReader(new InputStreamReader(
				process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while((line = reader.readLine()) != null)
				output.append(line).append(System.lineSeparator());
		}
		Assert.assertEquals("Fresh-JVM public GET prices differ: " + output,
			0, process.waitFor());
	}

	public static final class PublicGetPriceProbe {
		private PublicGetPriceProbe() {
			// test process entry point
		}

		public static void main(String[] args) {
			if(args.length > 0 && args[0].equals("all-responses")) {
				for(FType type : new FType[] {FType.BROADCAST, FType.PART})
					for(int workers : new int[] {1, 3, 16}) {
						double expected = (1.0 / 125.0 + workers / 14.7)
							* 1000.0 + 1.0;
						double actual = FederatedCostModel.computeDownloadNetworkCost(MIB, type, workers);
						Assert.assertEquals("Every full/overlapping worker response must be counted: "
							+ type + " W=" + workers, expected, actual, 1e-10);
						Assert.assertEquals(actual, FederatedCostModel.computeReusableMaterializationDownloadCost(
							MIB, type, workers), 0d);
					}
				for(int workers : new int[] {1, 3, 16})
					Assert.assertEquals("FULL is non-replicated regardless of a global pool estimate",
						FederatedCostModel.computeDownloadNetworkCost(MIB, FType.FULL, 1),
						FederatedCostModel.computeDownloadNetworkCost(MIB, FType.FULL, workers), 0.0);
				return;
			}
			double bytes = 400_304.0;
			double ordinary = FederatedCostModel.computeDownloadNetworkCost(bytes, FType.ROW, 3);
			double reusable = FederatedCostModel.computeReusableMaterializationDownloadCost(
				bytes, FType.ROW, 3);
			if(Math.abs(ordinary - reusable) > 1e-12)
				throw new AssertionError("ordinary=" + ordinary + ", reusable=" + reusable);
		}
	}

	private static double payload(double wireBytes, double codecBytes,
			double bandwidth, double codecBandwidth) throws Exception {
		return invoke("computeNetworkPayloadCost",
			new Class<?>[] {double.class, double.class, double.class, double.class, double.class, double.class},
			wireBytes, wireBytes, codecBytes, bandwidth, 0d, codecBandwidth);
	}

	private static double getPayload(double totalBytes, int fanIn, double bandwidth,
			double aggregateProcessingRate) throws Exception {
		return invoke("computeGetResponsePayloadCost",
			new Class<?>[] {double.class, int.class, double.class, double.class},
			totalBytes, fanIn, bandwidth, aggregateProcessingRate);
	}

	private static double getResponse(double totalBytes, int fanIn, double bandwidth,
			double aggregateProcessingRate, int additionalBatches, double requestLatencySec, double responseLatencySec)
			throws Exception {
		return invoke("computeGetResponseCost",
			new Class<?>[] {double.class, int.class, double.class, double.class,
				int.class, double.class, double.class},
			totalBytes, fanIn, bandwidth, aggregateProcessingRate, additionalBatches, requestLatencySec, responseLatencySec);
	}

	private static double invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
		Method method = FederatedCostModel.class.getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return (double) method.invoke(null, args);
	}
}

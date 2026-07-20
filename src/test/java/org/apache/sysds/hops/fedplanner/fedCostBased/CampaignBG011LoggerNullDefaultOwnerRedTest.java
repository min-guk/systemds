/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.ChildNetworkCost;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.HopFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.OptimalSummary;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for removing the duplicate Logger null/default observer helper. */
public class CampaignBG011LoggerNullDefaultOwnerRedTest {
	private static final String RULE = "-".repeat(200);

	@Test
	public void duplicateNullDisplayOwnerMustBeRemoved() {
		boolean duplicateOwnerExists = false;
		for(Method method : FederatedPlannerLogger.class.getDeclaredMethods()) {
			if(method.getName().equals("nullDisplay")
				&& method.getReturnType() == String.class
				&& List.of(method.getParameterTypes()).equals(List.of(String.class, String.class))) {
				duplicateOwnerExists = true;
				break;
			}
		}
		Assert.assertFalse("G011_LOGGER_NULLDISPLAY_HELPER_MUST_BE_REMOVED", duplicateOwnerExists);
	}

	@Test
	public void minStRenderersPreserveNullDefaults() {
		Rendered rendered = render(diagnostics(null, null, null, null));
		String expectedTabular = "\n[Optimal Federated Plan]\n" + RULE + "\n"
			+ String.format(Locale.ROOT,
				"%-7s | %-12s | %-20s | %-10s | %-13s | %-8s | %-9s | %-15s | %-15s | %-10s | %s%n",
				"Hop ID", "Type", "OpCode", "ExecType", "FedOutputType", "Privacy", "FType", "ChildIDs",
				"ParentIDs", "OpCost", "Network Costs (Child -> Cost)")
			+ RULE + "\n"
			+ String.format(Locale.ROOT,
				"%-7d | %-12s | %-20s | %-10s | %-13s | %-8s | %-9s | %-15s | %-15s | %-10.1f | %s%n",
				7L, "Binary", "+", "N/A", "N/A", "N/A", "N/A", "-", "-", 5.5d, "")
			+ RULE + "\n";
		Assert.assertEquals(expectedTabular, rendered.stdout());
		Assert.assertEquals("", rendered.stderr());

		Rendered structured = renderStructured(diagnostics(null, null, null, null));
		Assert.assertEquals("[HopID]: 7, [Name]: +, [DataType]: MATRIX, [ExecType]: null, "
			+ "[OutputType]: null, [FType]: null, [ChildHopIDs]: (), [ParentHopIDs]: (), "
			+ "[CostInfo]: {TotalCost: 3.5, SelfCost: 1.5, NetworkCost: 2.0, ComputeWeight: 4.5}, "
			+ "[MatrixInfo]: {Dimensions: (2x3), Blocksize: 1024, NNZ: 6, OutputMem: 8.5}\n",
			structured.stdout());
		Assert.assertEquals("", structured.stderr());
	}

	@Test
	public void minStRenderersPreserveNonNullValues() {
		Rendered rendered = render(diagnostics("FED", "FED", "FOUT", "ROW"));
		Assert.assertTrue(rendered.stdout().contains("| FED        | FOUT          | N/A      | ROW"));
		Assert.assertEquals("", rendered.stderr());

		Rendered structured = renderStructured(diagnostics("FED", "FED", "FOUT", "ROW"));
		Assert.assertEquals("[HopID]: 7, [Name]: +, [DataType]: MATRIX, [ExecType]: FED, "
			+ "[OutputType]: FOUT, [FType]: ROW, [ChildHopIDs]: (), [ParentHopIDs]: (), "
			+ "[CostInfo]: {TotalCost: 3.5, SelfCost: 1.5, NetworkCost: 2.0, ComputeWeight: 4.5}, "
			+ "[MatrixInfo]: {Dimensions: (2x3), Blocksize: 1024, NNZ: 6, OutputMem: 8.5}\n",
			structured.stdout());
		Assert.assertEquals("", structured.stderr());
	}

	private static MinStDiagnostics diagnostics(String effectiveExec, String forcedExec, String output,
		String fType) {
		HopFacts facts = new HopFacts(7L, "BinaryOp", "+", "MATRIX", effectiveExec, forcedExec, output, null,
			fType, List.of(), List.of(), List.of(), bits(1.5), bits(2.0), bits(3.5), bits(4.5), bits(5.5),
			List.<ChildNetworkCost>of(), 2L, 3L, 1024L, 6L, bits(7.5), bits(8.5), bits(7.5), bits(8.5), null);
		OptimalSummary summary = new OptimalSummary(7L, "+", forcedExec, output, null, fType,
			true, true, true, true);
		return new MinStDiagnostics(bits(3.5), List.of(), List.of(summary), List.of(facts));
	}

	private static Rendered render(MinStDiagnostics diagnostics) {
		return capture(() -> FederatedPlannerLogger.logOptimalPlan(diagnostics));
	}

	private static Rendered renderStructured(MinStDiagnostics diagnostics) {
		return capture(() -> FederatedPlannerLogger.logOptimalPlanStructured(diagnostics));
	}

	private static Rendered capture(Runnable action) {
		Assert.assertTrue("sysds.fedplanner.trace must be supplied to the forked test JVM",
			Boolean.getBoolean("sysds.fedplanner.trace"));
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		Locale originalLocale = Locale.getDefault();
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		try(PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
			PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
			Locale.setDefault(Locale.ROOT);
			System.setOut(capturedOut);
			System.setErr(capturedErr);
			action.run();
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
			Locale.setDefault(originalLocale);
		}
		return new Rendered(normalize(stdout), normalize(stderr));
	}

	private static String normalize(ByteArrayOutputStream stream) {
		return stream.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record Rendered(String stdout, String stderr) { }
}

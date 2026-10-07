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
package org.apache.sysds.test.functions.jmlc;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.api.jmlc.Connection;
import org.apache.sysds.api.jmlc.ResultVariables;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.utils.Statistics;
import org.junit.Assert;
import org.junit.Test;

/** Small in-memory algorithm regressions, not federated performance measurements. */
@net.jcip.annotations.NotThreadSafe
public class StepLmTrainingBudgetTest {
	private static final String FIXTURE = "X=matrix(0,rows=80,cols=8);\n"
		+ "for(i in 1:80) { X[i,((i-1) %% 8)+1]=ifelse(i<=40,1,-1); }\n"
		+ "y=X%*%seq(1,8)+rand(rows=80,cols=1,min=-0.1,max=0.1,seed=42);\n";

	@Test
	public void defaultKeepsAllBeneficialFeatures() {
		ResultVariables result = run(call("B", "S", 0, ""), "B", "S");
		Assert.assertEquals(8, result.getMatrix("S")[0].length);
		Assert.assertEquals(8, result.getMatrix("B").length);
	}

	@Test
	public void fiveFeatureBudgetPreservesGreedyPrefixAndFullOutputShape() {
		ResultVariables result = run(call("B", "S", 0, ",max_features=5")
			+ call("B0", "S0", 0, ",max_features=0"), "B", "S", "S0");
		double[] selected = result.getMatrix("S")[0];
		Assert.assertEquals(5, selected.length);
		Assert.assertTrue("All eight candidates remain eligible, not just the first five", selected[0] > 5);
		double[] unlimited = result.getMatrix("S0")[0];
		Assert.assertEquals(8, unlimited.length);
		for(int i = 0; i < selected.length; i++)
			Assert.assertEquals(unlimited[i], selected[i], 0);
		double[][] beta = result.getMatrix("B");
		Assert.assertEquals(8, beta.length);
		int nonzero = 0;
		for(double[] row : beta) if(row[0] != 0) nonzero++;
		Assert.assertEquals(5, nonzero);
	}

	@Test
	public void twoFeatureBudgetPreservesCandidatesAndStopsAfterTwoPasses() {
		boolean oldStatistics = DMLScript.STATISTICS;
		try {
			DMLScript.STATISTICS = true;
			Statistics.reset();
			ResultVariables result = run(call("B", "S", 0, ",max_features=2"), "B", "S");
			Assert.assertArrayEquals(new double[] {8, 7}, result.getMatrix("S")[0], 0);
			Assert.assertEquals("Keep the original feature-space output shape", 8,
				result.getMatrix("B").length);
			Assert.assertEquals("8+7 candidate fits plus one final fit", 16,
				Statistics.getCPHeavyHitterCount("linear_regression"));
		}
		finally {
			Statistics.reset();
			DMLScript.STATISTICS = oldStatistics;
		}
	}

	@Test
	public void cgTransposeReusePreservesInterceptAndStoppingSemantics() throws Exception {
		// Reconstruct only the old expressions; keep the rest of the reference algorithm shared.
		String reference = Files.readString(Path.of("scripts/builtin/lmCG.dml"))
			.replace("m_lmCG = function", "cg_reference = function")
			.replace("Xt = t(X)", "")
			.replace("r = -(Xt %*% y)", "r = - t(X) %*% y")
			.replace("q = Xt %*% (X %*% ssX_p)", "q = t(X) %*% (X %*% ssX_p)");
		Assert.assertTrue(reference.contains("r = - t(X) %*% y"));
		Assert.assertTrue(reference.contains("q = t(X) %*% (X %*% ssX_p)"));
		Assert.assertFalse(reference.contains("Xt = t(X)"));
		int[][] settings = {{0, 10}, {1, 10}, {2, 10}, {0, 1}, {0, 0}};
		for(int[] setting : settings) {
			String args = "X=X,y=y,icpt=" + setting[0] + ",maxi=" + setting[1]
				+ ",reg=1e-7,tol=1e-10,verbose=FALSE";
			ResultVariables result = run(reference
				+ "\nX=rand(rows=80,cols=8,min=-1,max=1,seed=17);\n"
				+ "y=X%*%seq(1,8)+0.25;\n"
				+ "actual_result=lmCG(" + args + ");expected_result=cg_reference(" + args + ");\n"
				+ "write(actual_result,\"actual_result\");write(expected_result,\"expected_result\");\n",
				"actual_result", "expected_result");
			assertCoefficientsEqual(result);
		}
		ResultVariables zero = run(reference + "\ny=matrix(0,rows=80,cols=1);\n"
			+ "actual_result=lmCG(X=X,y=y,maxi=10,verbose=FALSE);"
			+ "expected_result=cg_reference(X=X,y=y,maxi=10,verbose=FALSE);"
			+ "write(actual_result,\"actual_result\");write(expected_result,\"expected_result\");",
			"actual_result", "expected_result");
		assertCoefficientsEqual(zero);
	}

	private static void assertCoefficientsEqual(ResultVariables result) {
		double[][] actual = result.getMatrix("actual_result");
		double[][] reference = result.getMatrix("expected_result");
		Assert.assertEquals(reference.length, actual.length);
		for(int i = 0; i < actual.length; i++) {
			Assert.assertTrue("CG coefficients must be finite", Double.isFinite(actual[i][0]));
			Assert.assertArrayEquals(reference[i], actual[i], 1e-9);
		}
	}

	@Test
	public void oneFeatureBudgetExcludesIntercept() {
		ResultVariables result = run(call("B", "S", 1, ",max_features=1"), "B", "S");
		double[] selected = result.getMatrix("S")[0];
		Assert.assertEquals("One feature plus the intercept", 2, selected.length);
		Assert.assertEquals(9, selected[1], 0);
		Assert.assertEquals(9, result.getMatrix("B").length);
	}

	@Test
	public void budgetStopsFurtherCandidateFitsRatherThanTruncatingTheOutput() {
		boolean oldStatistics = DMLScript.STATISTICS;
		try {
			DMLScript.STATISTICS = true;
			Statistics.reset();
			run(call("B", "S", 0, ",max_features=5"), "B", "S");
			Assert.assertEquals("8+7+6+5+4 candidate fits plus one final fit", 31,
				Statistics.getCPHeavyHitterCount("linear_regression"));
		}
		finally {
			Statistics.reset();
			DMLScript.STATISTICS = oldStatistics;
		}
	}

	@Test
	public void zeroAndOversizedBudgetsKeepTheDefaultResult() {
		ResultVariables result = run(call("B", "S", 0, "")
			+ call("B0", "S0", 0, ",max_features=0")
			+ call("B9", "S9", 0, ",max_features=99"), "B", "S", "B0", "S0", "B9", "S9");
		Assert.assertArrayEquals(result.getMatrix("S")[0], result.getMatrix("S0")[0], 0);
		Assert.assertArrayEquals(result.getMatrix("S")[0], result.getMatrix("S9")[0], 0);
		for(int i = 0; i < 8; i++) {
			Assert.assertArrayEquals(result.getMatrix("B")[i], result.getMatrix("B0")[i], 0);
			Assert.assertArrayEquals(result.getMatrix("B")[i], result.getMatrix("B9")[i], 0);
		}
	}

	@Test
	public void negativeFeatureBudgetIsRejected() {
		RuntimeException error = Assert.assertThrows(RuntimeException.class,
			() -> run(call("B", "S", 0, ",max_features=-1"), "B", "S"));
		StringBuilder messages = new StringBuilder();
		for(Throwable cause = error; cause != null; cause = cause.getCause())
			messages.append(cause.getMessage()).append('\n');
		Assert.assertTrue(messages.toString(), messages.toString().contains("max_features must be nonnegative"));
	}

	private static String call(String beta, String selected, int intercept, String limit) {
		return "[" + beta + "," + selected + "]=steplm(X=X,y=y,icpt=" + intercept
			+ ",reg=1e-7,tol=1e-7,maxi=10,verbose=FALSE" + limit + ");\n";
	}

	private static ResultVariables run(String script, String... outputs) {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldCaching = CacheableData.isCachingActive();
		try(Connection connection = new Connection()) {
			return connection.prepareScript(FIXTURE + script, new String[0], outputs).executeScript();
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
			ConfigurationManager.setLocalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldCompiler);
			if(oldCaching) CacheableData.enableCaching();
			else CacheableData.disableCaching();
		}
	}
}

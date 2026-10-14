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
package org.apache.sysds.hops.fedplanner.memo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLScriptException;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.junit.Assert;
import org.junit.Test;

/** Locks the builtin GLM contract for rejected inputs and normal CP completion. */
public class MemoGlmFailureTest {
	private static final String SOURCE = "source(\"scripts/builtin/glm.dml\") as glm;";
	private static final String CALL =
		"B=glm::m_glm(X=X,Y=Y,dfam=dfam,vpow=0.0,link=link,lpow=lpow,yneg=0.0,"
			+ "icpt=0,disp=1.0,reg=0.0,tol=1e-6,moi=20,mii=5,verbose=FALSE);";

	@Test
	public void unsupportedDistributionStopsBeforeCallerContinuation() throws Exception {
		Execution execution = execute(SOURCE
			+ "X=matrix(1,rows=3,cols=1);Y=matrix(1,rows=3,cols=1);"
			+ "dfam=3;link=1;lpow=1.0;print('BEFORE');" + CALL + "print('AFTER');");
		assertExplicitStop(execution,"Distribution/Link not supported.  Terminating the DML.");
	}

	@Test
	public void invalidLogLinkResponseStopsBeforeCallerContinuation() throws Exception {
		Execution execution = execute(SOURCE
			+ "X=matrix(1,rows=3,cols=1);Y=matrix(-1,rows=3,cols=1);"
			+ "dfam=1;link=1;lpow=0.0;print('BEFORE');" + CALL + "print('AFTER');");
		assertExplicitStop(execution,"Input matrices are out of range.  Terminating the DML.");
	}

	@Test
	public void supportedGaussianIdentityReturnsAndContinuesCaller() throws Exception {
		Execution execution = execute(SOURCE
			+ "X=matrix('1 0 0 1 1 1 2 1',rows=4,cols=2);Y=matrix('1 2 3 4',rows=4,cols=1);"
			+ "dfam=1;link=1;lpow=1.0;print('BEFORE');" + CALL
			+ "print(nrow(B));print(ncol(B));"
			+ "print('COEF_ERROR='+sum(abs(B-matrix('1 2',rows=2,cols=1))));print('AFTER');");
		Assert.assertNull(message(execution.failure()),execution.failure());
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("BEFORE"));
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("AFTER"));
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("\n2\n1\n"));
		String error=execution.stdout().lines().filter(line -> line.startsWith("COEF_ERROR="))
			.findFirst().orElseThrow().substring("COEF_ERROR=".length());
		Assert.assertTrue("known Gaussian coefficients [1,2]: "+error,Double.parseDouble(error)<1e-5);
	}

	private static void assertExplicitStop(Execution execution,String expectedMessage) {
		Assert.assertNotNull("rejected GLM input must fail",execution.failure());
		Assert.assertTrue(message(execution.failure()),hasCause(execution.failure(),DMLScriptException.class));
		Assert.assertTrue(message(execution.failure()),message(execution.failure()).contains(expectedMessage));
		Assert.assertTrue("work preceding m_glm remains observable",execution.stdout().contains("BEFORE"));
		Assert.assertFalse("caller continuation must not run after stop",execution.stdout().contains("AFTER"));
		Assert.assertFalse("GLM must not fabricate a return after stop",
			message(execution.failure()).contains("was not assigned a return value"));
	}

	private static Execution execute(String script) throws Exception {
		boolean oldIPA = OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS;
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		PrintStream oldOut = System.out;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Throwable failure = null;
		try {
			OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS = false;
			DMLScript.setGlobalExecMode(ExecMode.SINGLE_NODE);
			DMLConfig config = new DMLConfig();
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.constructLops(program);
			Program runtime = translator.getRuntimeProgram(program,config);
			System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
			try {
				runtime.execute(ExecutionContextFactory.createContext(runtime));
			}
			catch(Throwable ex) {
				failure = ex;
			}
			return new Execution(bytes.toString(StandardCharsets.UTF_8),failure);
		}
		finally {
			System.setOut(oldOut);
			ConfigurationManager.setLocalConfig(oldConfig);
			DMLScript.setGlobalExecMode(oldMode);
			OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS = oldIPA;
		}
	}

	private static boolean hasCause(Throwable failure,Class<? extends Throwable> type) {
		for(Throwable current = failure; current != null; current = current.getCause())
			if(type.isInstance(current))
				return true;
		return false;
	}

	private static String message(Throwable failure) {
		if(failure == null)
			return "";
		StringBuilder result = new StringBuilder();
		for(Throwable current = failure; current != null; current = current.getCause())
			if(current.getMessage() != null)
				result.append(current.getClass().getSimpleName()).append(": ")
					.append(current.getMessage()).append('\n');
		return result.toString();
	}

	private record Execution(String stdout,Throwable failure) { }
}

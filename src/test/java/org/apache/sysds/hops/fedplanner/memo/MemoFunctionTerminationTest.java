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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.junit.Assert;
import org.junit.Test;

/** Locks the existing non-inlined function-return failure boundary used by memo activation facts. */
public class MemoFunctionTerminationTest {
	private static final String FUNCTION =
		"f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(7,rows=2,cols=2);}}";

	@Test
	public void assignedMatrixReturnContinuesCaller() throws Exception {
		Execution execution = execute(FUNCTION
			+ "print('BEFORE');A=f(TRUE);print(as.scalar(A[1,1]));print('AFTER');");
		Assert.assertNull(execution.failure());
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("BEFORE"));
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("7.0"));
		Assert.assertTrue(execution.stdout(),execution.stdout().contains("AFTER"));
	}

	@Test
	public void missingMatrixReturnTerminatesBeforeLaterCallerWork() throws Exception {
		Execution execution = execute(FUNCTION
			+ "print('BEFORE');A=f(FALSE);print('AFTER');");
		Assert.assertNotNull("missing bound return must fail at the call boundary",execution.failure());
		Assert.assertTrue(message(execution.failure()),
			message(execution.failure()).contains("was not assigned a return value"));
		Assert.assertTrue("work preceding the call remains observable",execution.stdout().contains("BEFORE"));
		Assert.assertFalse("caller continuation must not run after the failed call",
			execution.stdout().contains("AFTER"));
	}

	private static Execution execute(String script) throws Exception {
		boolean oldIPA = OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS;
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		PrintStream oldOut = System.out;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Throwable failure = null;
		try {
			// A real FunctionCallCPInstruction is required: IPA may inline this small body.
			OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS = false;
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
			OptimizerUtils.ALLOW_INTER_PROCEDURAL_ANALYSIS = oldIPA;
		}
	}

	private static String message(Throwable failure) {
		StringBuilder result = new StringBuilder();
		for(Throwable current = failure; current != null; current = current.getCause())
			if(current.getMessage() != null) result.append(current.getMessage()).append('\n');
		return result.toString();
	}

	private record Execution(String stdout,Throwable failure) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** A branch must test the incoming scalar before its body can update it. */
public class ConditionalLiteralPredicateRewriteTest {
	@Test
	public void knownNonzeroLinkRemovesConditionalSelfUpdate() throws Exception {
		String script = "link_type=2;\n"
			+ "if(link_type==0) { link_type=1; }\n"
			+ "if(link_type==2) { answer=1; } else { answer=0; }\n"
			+ "print(answer);\n";
		DMLProgram program = compile(script);
		Assert.assertFalse("the known false self-update must not keep a generic link branch",
			program.getStatementBlocks().stream().anyMatch(IfStatementBlock.class::isInstance));
	}

	@Test
	public void knownLinkPassedToFunctionRemovesGenericBranch() throws Exception {
		String script = "f=function(int link) return(int answer) {\n"
			+ " link_type=link;\n"
			+ " if(link_type==0) { link_type=1; }\n"
			+ " if(link_type==2) { answer=2; } else { answer=3; }\n"
			+ "}\n"
			+ "result=f(2);\nprint(result);\n";
		DMLProgram program = compile(script);
		Assert.assertEquals("the single literal call has no reachable generic branch",
			0, countIfs(program.getStatementBlocks()) + program.getNamedNSFunctionStatementBlocks()
				.values().stream().mapToInt(block -> countIfs(List.<StatementBlock>of(block))).sum());
	}

	@Test
	public void differentLiteralCallSitesDoNotSpecializeSharedFunction() throws Exception {
		String script = "f=function(int link) return(int answer) {\n"
			+ " if(link==2) { answer=2; } else { answer=3; }\n"
			+ "}\n"
			+ "left=f(2);\nright=f(3);\nprint(left+right);\n";
		DMLProgram program = compile(script);
		int functionBranches = program.getNamedNSFunctionStatementBlocks().values().stream()
			.mapToInt(block -> countIfs(List.<StatementBlock>of(block))).sum();
		Assert.assertTrue("distinct callsite arguments must retain the shared function predicate",
			functionBranches > 0);
	}

	private static int countIfs(List<StatementBlock> blocks) {
		int count = 0;
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock)
				count++;
			if(block instanceof FunctionStatementBlock)
				count += countIfs(((FunctionStatement) block.getStatement(0)).getBody());
		}
		return count;
	}

	private static DMLProgram compile(String script) throws Exception {
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

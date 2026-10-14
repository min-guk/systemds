/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Locks request-local dependency lookup reuse without changing dependency semantics. */
public class MemoAnalysisDependenciesTest {
	private static final String TWO_CALLS =
		"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"+
		"g=function(matrix[double] X) return(matrix[double] Y){Y=sqrt(X);}"+
		"P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=g(Q);print(sum(A+B));";
	private static final String TWO_RUNTIME_CALLS =
		"f=function(matrix[double] X) return(matrix[double] Y){Y=X;for(i in 1:2){Y=abs(Y);}}"+
		"g=function(matrix[double] X) return(matrix[double] Y){Y=X;for(j in 1:2){Y=sqrt(Y);}}"+
		"P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=g(Q);print(sum(A+B));";

	@Test
	public void indexedCallsEqualPriorFilteringForRuntimeCalls() throws Exception {
		assertIndexedCallsEqualPriorFiltering(analyze(TWO_RUNTIME_CALLS),false);
	}

	@Test
	public void indexedCallsEqualPriorFilteringForInlinedCalls() throws Exception {
		assertIndexedCallsEqualPriorFiltering(analyze(TWO_CALLS),true);
	}

	@Test
	public void callIndexIsImmutableAndInvalidFunctionHasNoCalls() throws Exception {
		MemoProgramFacts facts=analyze(TWO_CALLS);
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> facts.callsForFunction(0).add(facts.callsForFunction(0).get(0)));
		Assert.assertTrue(facts.callsForFunction(-1).isEmpty());
		Assert.assertTrue(facts.callsForFunction(facts.functions().size()).isEmpty());
	}

	@Test
	public void shapesRejectAnotherRequestSnapshot() throws Exception {
		MemoProgramFacts first=analyze("A=matrix(1,2,2);print(sum(A));");
		MemoSources firstSources=MemoSources.acquire(first,null);
		MemoShapes shapes=MemoShapes.analyze(first,firstSources);
		MemoProgramFacts second=analyze("A=matrix(1,2,2);print(sum(A));");
		MemoSources secondSources=MemoSources.acquire(second,null);

		shapes.requireRequest(first,firstSources);
		Assert.assertThrows(IllegalArgumentException.class,() -> shapes.requireRequest(second,firstSources));
		Assert.assertThrows(IllegalArgumentException.class,() -> shapes.requireRequest(first,secondSources));
	}

	private static void assertIndexedCallsEqualPriorFiltering(MemoProgramFacts facts,boolean expectInlined) {
		Assert.assertFalse(facts.calls().isEmpty());
		Assert.assertTrue("fixture must distinguish per-function indexing",facts.functions().size()>1);
		Assert.assertEquals(expectInlined,
			facts.calls().stream().anyMatch(call -> call.callOccurrenceId()<0));
		int indexed=0;
		for(MemoProgramFacts.FunctionSummary function : facts.functions()) {
			List<MemoProgramFacts.CallBinding> filtered=facts.calls().stream()
				.filter(call -> call.functionSummaryId()==function.id()).toList();
			Assert.assertEquals(filtered,facts.callsForFunction(function.id()));
			indexed+=facts.callsForFunction(function.id()).size();
		}
		Assert.assertEquals("each call must occur in exactly one function index",facts.calls().size(),indexed);
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

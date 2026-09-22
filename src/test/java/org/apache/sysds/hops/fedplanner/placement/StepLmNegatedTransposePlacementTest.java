/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
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
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** StepLM's lmCG line 106 negates a protected transpose before matrix multiply. */
public class StepLmNegatedTransposePlacementTest {
	@Test
	public void protectedTransposeOutputCanSeedImmediateNegation() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:18101/X1\","
			+ "\"localhost:18102/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "A=t(X);B=0-A;print(sum(B));\n";
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		Assert.assertTrue(analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& "-".equals(binary.getOp().toString()))
			.anyMatch(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow()
				.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT)));
	}

	@Test
	public void negatedProtectedTransposeInFunctionHasNativeCandidate() throws Exception {
		String script = "cg=function(matrix[double] A,matrix[double] b) return (matrix[double] r) {"
			+ "r=-t(A)%*%b; }\n"
			+ "X=federated(addresses=list(\"localhost:18101/X1\","
			+ "\"localhost:18102/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "y=federated(addresses=list(\"localhost:18101/y1\","
			+ "\"localhost:18102/y2\"),ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));\n"
			+ "r=cg(X,y);print(sum(r));\n";
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		assertProtectedNegationHasNativeFout(analysis);
	}

	@Test
	public void negatedProtectedTransposeHasNativeCandidate() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:18101/X1\","
			+ "\"localhost:18102/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "y=federated(addresses=list(\"localhost:18101/y1\","
			+ "\"localhost:18102/y2\"),ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));\n"
			+ "r=-t(X)%*%y;print(sum(r));\n";
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		assertProtectedNegationHasNativeFout(analysis);
	}

	private static void assertProtectedNegationHasNativeFout(PlacementAnalysis analysis) {
		var protectedNegations = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& "-".equals(binary.getOp().toString()))
			.filter(occurrence -> analysis.requirePrivacy(occurrence.key()) == Privacy.PRIVATE_AGGREGATE)
			.toList();
		Assert.assertFalse("fixture must contain a protected negation", protectedNegations.isEmpty());
		for(var negation : protectedNegations)
			Assert.assertTrue("protected negation must retain native FOUT",
				analysis.graph().node(negation.key()).orElseThrow().legalAlternatives().stream()
					.anyMatch(state -> state.execType() == ExecType.FED
						&& state.output() == FederatedOutput.FOUT));
	}
}

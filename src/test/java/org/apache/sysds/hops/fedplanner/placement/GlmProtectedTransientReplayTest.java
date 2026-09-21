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

package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** The protected logit path must carry its row-partitioned value across a transient read. */
public class GlmProtectedTransientReplayTest {
	private static final String SOURCE =
		"A=federated(addresses=list(\"localhost:18101/X1\",\"localhost:18102/X2\"),"
			+ "ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));\n";
	private static final String LOGIT_BODY =
		" ones=matrix(1,rows=nrow(A),cols=1);\n"
			+ " is_LT_infinite=cbind(A==Inf,A==-Inf);\n"
			+ " finite=replace(target=A,pattern=Inf,replacement=0);\n"
			+ " Y_prob=cbind(exp(finite),ones);\n"
			+ " if(sum(A)>0) { Y_prob=Y_prob/rowSums(Y_prob); }\n"
			+ " else { Y_prob=Y_prob/rowSums(Y_prob); }\n"
			+ " Y_prob=Y_prob*(1-rowSums(is_LT_infinite))+is_LT_infinite;\n";
	private static final String INITIALIZE_BODY =
		"init=function(matrix[double] Y, int dist_type, int link_type) return (matrix[double] B) {\n"
			+ " y_corr=Y[,1];\n"
			+ " if(dist_type==2) { n_corr=rowSums(Y); y_corr=Y[,1]/(n_corr+(n_corr==0)); }\n"
			+ " linear_terms=y_corr;\n"
			+ " if(dist_type==2 & link_type>=1 & link_type<=5) {\n"
			+ "  is_zero_y_corr=(y_corr<=0); is_one_y_corr=(y_corr>=1);\n"
			+ "  y_corr=y_corr*(1-is_zero_y_corr)*(1-is_one_y_corr)"
			+ " +0.5*(is_zero_y_corr+is_one_y_corr);\n"
			+ "  if(link_type==2) { linear_terms=log(y_corr/(1-y_corr))"
			+ " +is_one_y_corr/(1-is_one_y_corr)-is_zero_y_corr/(1-is_zero_y_corr); }\n"
			+ " }\n"
			+ " if(sum(Y)>0) { B=linear_terms+1; } else { B=linear_terms+2; }\n"
			+ "}\n";

	@Test
	public void protectedLogitTransientReplayRetainsFederatedRowCandidate() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(SOURCE + LOGIT_BODY
				+ "print(sum(Y_prob));\n"));
		var reads = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& "Y_prob".equals(data.getName()))
			.toList();
		Assert.assertFalse("fixture must contain the logit transient read: "
			+ analysis.graph().nodes().stream().map(node -> analysis.hop(node.key())
				.map(hop -> hop.getOpString() + ':' + hop.getName()).orElse("synthetic"))
				.toList(), reads.isEmpty());
		Assert.assertTrue("protected logit read must keep a remote row alternative", reads.stream()
			.filter(read -> analysis.requirePrivacy(read.key()) == Privacy.PRIVATE_AGGREGATE)
			.anyMatch(read -> analysis.graph().node(read.key()).orElseThrow().legalAlternatives().stream()
				.anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT)));
	}

	@Test
	public void protectedInitializeTermsRetainFederatedReadAcrossConditionalAssignment() throws Exception {
		var program = NeutralPlacementFixedPointCompositionTest.compileProtected(
			INITIALIZE_BODY + SOURCE + "B=init(A,2,2); print(sum(B));\n");
		var compiledReads = PlacementGraphFingerprint.orderedOccurrences(program).stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& "linear_terms".equals(data.getName()))
			.toList();
		Assert.assertFalse("fixture must compile a cross-block linear_terms read", compiledReads.isEmpty());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertTrue("protected linear_terms read must keep a remote candidate", analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& "linear_terms".equals(data.getName()))
			.anyMatch(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow()
				.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT)));
	}
}

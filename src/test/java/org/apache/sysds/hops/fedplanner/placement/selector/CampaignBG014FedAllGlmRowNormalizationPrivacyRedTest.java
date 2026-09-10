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
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for the protected row normalization in GLM's real binomial-logit branch. */
public class CampaignBG014FedAllGlmRowNormalizationPrivacyRedTest {
	@Test
	public void singlePassKeepsProtectedBinomialRowNormalizationRemote() throws Exception {
		Path features = Files.createTempFile("glm-row-normalization-features-", ".data");
		Path labels = Files.createTempFile("glm-row-normalization-labels-", ".data");
		try {
			writeMetadata(features, 50000, 2100);
			writeMetadata(labels, 50000, 1);
			DMLProgram program = compile(federatedInput("X", features, 50000, 2100)
				+ federatedInput("Y", labels, 50000, 1)
				+ "threshold=mean(Y);\nY=(Y>threshold)*1;\n"
				+ "beta=glm(X=X,Y=Y,dfam=2,vpow=0.0,link=2,lpow=1.0,yneg=0.0,"
				+ "icpt=0,disp=0.0,reg=0.0,tol=1e-6,moi=20,mii=5,verbose=FALSE);\n"
				+ "write(beta,\"/tmp/glm-row-normalization-planning-only.res\",format=\"csv\");\n");

			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			List<PlacementAnalysis.HopOccurrenceProjection> normalizations = analysis
				.compiledHopOccurrences().stream().filter(occurrence -> {
					var hop = occurrence.hop();
					return "b(/)".equals(hop.getOpString()) && hop.getBeginLine() == 917
						&& occurrence.key().normalizedSignature()
							.contains("binomial_probability_two_column")
						&& analysis.requirePrivacy(occurrence.key()) == Privacy.PRIVATE_AGGREGATE;
				}).toList();
			Assert.assertEquals("fixture must identify only the actual glm.dml:917 normalization",
				1, normalizations.size());

			FedAllInvocationReceipt receipt = (FedAllInvocationReceipt)
				new FederatedPlannerFedAllMaxFedFoutSinglePass()
					.rewriteProgram(program, null, null, analysis);
			var state = receipt.normalizedResult().selectedStates().get(normalizations.get(0).key());
			Assert.assertNotNull("single-pass omitted the protected row normalization", state);
			Assert.assertEquals(ExecType.FED, state.execType());
			Assert.assertEquals(FederatedOutput.FOUT, state.output());
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			Files.deleteIfExists(Path.of(features + ".mtd"));
			Files.deleteIfExists(Path.of(labels + ".mtd"));
			Files.deleteIfExists(features);
			Files.deleteIfExists(labels);
		}
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

	private static String federatedInput(String name, Path data, int rows, int columns) {
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return name + "=federated(addresses=list(\"localhost:1234/" + path
			+ "\"),ranges=list(list(0,0),list(" + rows + ',' + columns + ")));\n";
	}

	private static void writeMetadata(Path data, int rows, int columns) throws Exception {
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\",\"rows\":" + rows
			+ ",\"cols\":" + columns + ",\"rows_in_block\":1000,\"cols_in_block\":1000,"
			+ "\"nnz\":" + ((long) rows * columns) + ",\"privacy\":\"private-aggregate\"}");
	}
}

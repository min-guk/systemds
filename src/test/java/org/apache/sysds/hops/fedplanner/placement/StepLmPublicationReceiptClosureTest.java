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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Publication must consume regenerated producer receipts before deleting dependent native routes. */
public class StepLmPublicationReceiptClosureTest {
	private static final String Q_OWNER =
		"root-0/input-0/input-1/input-0/input-1/input-0/input-1/input-0";

	@Test(timeout = 120_000)
	public void fullRankStepLmRetainsBothDirectWorkerPoolRoutes() throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script(), new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		boolean previous = OptimizerUtils.FEDERATED_COMPILATION;
		try {
			OptimizerUtils.FEDERATED_COMPILATION = true;
			translator.prepareSearchSpaceOnly(program, false);
		}
		finally {
			OptimizerUtils.FEDERATED_COMPILATION = previous;
		}

		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		analysis.assertCanonicalProgramAuthority(program);
		CandidateRuleFact full = qFact(analysis, FType.FULL);
		CandidateRuleFact broadcast = qFact(analysis, FType.BROADCAST);
		Assert.assertTrue("q must retain its exact FULL direct route",
			hasDirectRoute(analysis, full, FType.FULL));
		Assert.assertTrue("q must retain its exact BROADCAST direct route",
			hasDirectRoute(analysis, broadcast, FType.BROADCAST));
		PlacementSupportRelations.verifyPublishedRelocationRealizations(
			analysis.candidateRuleFacts().orderedFacts(), analysis.graph().relocationActions());
	}

	private static CandidateRuleFact qFact(PlacementAnalysis analysis, FType inputType) {
		var matches = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence().functionNamespace().equals(".builtinNS::m_lmCG"))
			.filter(fact -> fact.key().parentOccurrence().emittedHopInstance().equals(Q_OWNER))
			.filter(fact -> fact.key().orderedInputs().equals(
				java.util.List.of(CandidateInputState.absentLocal(), CandidateInputState.present(inputType))))
			.toList();
		Assert.assertEquals("the regression must identify one exact lmCG q occurrence", 1, matches.size());
		return matches.get(0);
	}

	private static boolean hasDirectRoute(PlacementAnalysis analysis, CandidateRuleFact fact, FType fType) {
		return fact.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState().output() == FederatedOutput.FOUT)
			.filter(emission -> emission.emissionState().placementState().fType() == fType)
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.flatMap(clause -> clause.inputBindings().stream())
			.filter(binding -> binding.inputPosition() == 1)
			.filter(binding -> binding.kind() == CandidateInputBindingKind.DIRECT)
			.filter(binding -> binding.source().rule().parentOccurrence().emittedHopInstance()
				.equals(Q_OWNER + "/input-1"))
			.filter(binding -> binding.source().realization().layoutKind() == PlacementLayoutKind.DURABLE_MAP)
			.filter(binding -> binding.source().realization().emissionState().placementState().fType() == fType)
			.anyMatch(binding -> analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(source -> source.key().equals(binding.source().rule()))
				.flatMap(source -> source.allowedEmissionFacts().stream())
				.flatMap(source -> source.realizations().stream())
				.anyMatch(source -> source.key().equals(binding.source().realization())
					&& !source.supportClauses().isEmpty()));
	}

	private static String script() {
		return "X_LOCAL=matrix(\"" + xValues() + "\",rows=20,cols=5,byrow=TRUE);"
			+ "X=federated(local_matrix=X_LOCAL,addresses=list(\"localhost:13000\"),"
			+ "ranges=list(list(0,0),list(20,5)));"
			+ "Y_LOCAL=matrix(\"" + yValues() + "\",rows=20,cols=1,byrow=TRUE);"
			+ "Y=federated(local_matrix=Y_LOCAL,addresses=list(\"localhost:13000\"),"
			+ "ranges=list(list(0,0),list(20,1)));"
			+ "[m,s]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);"
			+ "write(s,\"tmp/selection.csv\",format=\"csv\");"
			+ "print(\"JOINT_E2E_SUM=\"+sum(m));"
			+ "print(\"JOINT_E2E_NORM2=\"+sum(m*m));"
			+ "print(\"JOINT_E2E_ROWS=\"+nrow(m));"
			+ "print(\"JOINT_E2E_COLS=\"+ncol(m));"
			+ "write(m,\"tmp/model.csv\",format=\"csv\");";
	}

	private static String xValues() {
		StringBuilder values = new StringBuilder();
		for(int row = 0; row < 20; row++) {
			double[] x = row(row);
			for(double value : x) {
				if(!values.isEmpty())
					values.append(' ');
				values.append(Double.toString(value));
			}
		}
		return values.toString();
	}

	private static String yValues() {
		StringBuilder values = new StringBuilder();
		for(int index = 0; index < 20; index++) {
			double[] x = row(index);
			if(!values.isEmpty())
				values.append(' ');
			values.append(Double.toString(1.75 * x[0] - 2.0 * x[2] + 0.65 * x[4]
				+ ((index % 3) - 1) / 100.0));
		}
		return values.toString();
	}

	private static double[] row(int row) {
		return new double[] {(row - 9.5) / 10.0, ((row * row) % 17 - 8) / 7.0,
			((row * 5 + 3) % 19 - 9) / 6.0, (row % 4) - 1.5,
			((row * 7 + row / 3) % 23 - 11) / 8.0};
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public class JointValueMapSelectionLegalityTest {
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void canonicalIndependentDirectWitnessIsRejectedBeforeNormalization() throws Exception {
		Fixture fixture = canonical(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));");
		RuntimeException failure = Assert.assertThrows(RuntimeException.class,
			() -> new HeuristicPlacementAdapter().select(fixture.analysis(), Set.of()));
		Assert.assertTrue("the full canonical failure must retain the shared joint diagnosis: " + failure,
			causalMessages(failure).contains("JOINT_VALUE_MAP_INCOMPATIBLE:"));
	}

	@Test
	public void canonicalCorrelatedDirectWitnessNormalizesAndPrevalidatesEmission() throws Exception {
		Fixture fixture = canonical(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));");
		var selected = new HeuristicPlacementAdapter().select(fixture.analysis(), Set.of());
		NormalizedPlannerResult normalized = PlacementPlannerAdapter.normalize(fixture.analysis(), selected);
		Method prevalidate = PlacementEmissionTransaction.class.getDeclaredMethod(
			"prevalidate", DMLProgram.class, NormalizedPlannerResult.class);
		prevalidate.setAccessible(true);
		try {
			Assert.assertNotNull(prevalidate.invoke(null, fixture.program(), normalized));
		}
		catch(InvocationTargetException failure) {
			throw new AssertionError("correlated AA/BB witness failed emission prevalidation", failure.getCause());
		}
	}

	@Test
	public void receiptlessCpLoutConsumerDoesNotImposeFederatedPoolAlignment() throws Exception {
		Fixture fixture = canonical(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));", Privacy.PUBLIC);
		JointValueMapRelations.Relation relation = JointValueMapRelations.from(fixture.analysis()).stream()
			.filter(candidate -> candidate.readers().size() == 2).findFirst().orElseThrow();
		CandidateSelectionReceipt mappedReader = receipts(fixture.analysis(), relation.readers().get(0)).stream()
			.filter(receipt -> receipt.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP)
			.findFirst().orElseThrow();
		PlacementState local = fixture.analysis().graph().node(relation.consumer()).orElseThrow()
			.legalAlternatives().stream().filter(state -> state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.LOUT).findFirst().orElseThrow();
		Map<PlacementIdentity.CompiledHopKey,PlacementState> assignment = new IdentityHashMap<>();
		assignment.put(relation.consumer(), local);
		Assert.assertTrue(JointValueMapRelations.selectedExecutionRowsAligned(
			fixture.analysis(), assignment, List.of(mappedReader)));
		assignment.put(relation.consumer(), new PlacementState(ExecType.CP, FederatedOutput.FOUT,
			local.fType(), local.shapeDependent()));
		Assert.assertFalse("receiptless CP/FOUT has no selected realization authority",
			JointValueMapRelations.selectedExecutionRowsAligned(
				fixture.analysis(), assignment, List.of(mappedReader)));
		assignment.put(relation.consumer(), new PlacementState(ExecType.FED, FederatedOutput.FOUT,
			local.fType(), local.shapeDependent()));
		Assert.assertFalse("receiptless FED has no selected realization authority",
			JointValueMapRelations.selectedExecutionRowsAligned(
				fixture.analysis(), assignment, List.of(mappedReader)));
	}

	private static List<CandidateSelectionReceipt> receipts(PlacementAnalysis analysis,
		PlacementIdentity.CompiledHopKey owner) {
		List<CandidateSelectionReceipt> receipts = new java.util.ArrayList<>();
		for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(owner)) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(var emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations())
					for(var clause : realization.supportClauses())
						receipts.add(new CandidateSelectionReceipt(fact.key(), emission,
							realization, clause, List.of()));
		}
		return receipts;
	}

	private static String causalMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for(Throwable current = failure; current != null; current = current.getCause())
			messages.append(String.valueOf(current.getMessage())).append('\n');
		return messages.toString();
	}

	private static Fixture canonical(String script) throws Exception {
		return canonical(script, Privacy.PRIVATE_AGGREGATE);
	}

	private static Fixture canonical(String script, Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		boolean previousFederatedCompilation = OptimizerUtils.FEDERATED_COMPILATION;
		try {
			OptimizerUtils.FEDERATED_COMPILATION = true;
			translator.prepareSearchSpaceOnly(program, false);
		}
		finally {
			OptimizerUtils.FEDERATED_COMPILATION = previousFederatedCompilation;
		}
		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		analysis.assertCanonicalProgramAuthority(program);
		return new Fixture(program, analysis);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis) { }
}

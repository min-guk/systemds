/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class DerivedFoutMaterializationAuthorityTest {
	@Test
	public void selectedDerivedCandidateRequiresItsExactGraphOwnedAction() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(
			fed() + "P=A/2;S=colSums(P);write(S,\"out\",format=\"binary\");"));
		CandidateEmissionFact derived = analysis.candidateRuleFacts().orderedFacts().stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.filter(fact -> fact.emissionState().derivedFedFout()).findFirst().orElseThrow();
		CandidateRuleKey rule = derived.derivedFoutAction().candidateRule();
		CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(rule, derived, List.of());

		Assert.assertTrue(CandidateSelections.derivedFoutActionReachable(analysis.graph(), receipt));
		NeutralPlacementGraph missing = new NeutralPlacementGraph(analysis.graph().nodes(),
			analysis.graph().constraints(), analysis.graph().relocationActions());
		Assert.assertFalse("coarse FED/FOUT state must not replace physical output action authority",
			CandidateSelections.derivedFoutActionReachable(missing, receipt));

		CandidateRuleKey foreignRule = new CandidateRuleKey(rule.parentOccurrence(), rule.orderedInputs());
		CandidateSelectionReceipt foreign = new CandidateSelectionReceipt(foreignRule, derived, List.of());
		Assert.assertFalse("structurally equal foreign candidate identity must fail closed",
			CandidateSelections.derivedFoutActionReachable(analysis.graph(), foreign));
		Assert.assertThrows("derived emission without an action must be unrepresentable",
			IllegalArgumentException.class,
			() -> new CandidateEmissionFact(derived.emissionState(), derived.executionFType()));
	}

	private static String fed() {
		return "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}

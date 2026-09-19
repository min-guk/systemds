/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class CandidateIncomingSupportCompletenessTest {
	private static final String SCRIPT = """
		Y = federated(addresses=list("localhost:1234/Y1", "localhost:1235/Y2"),
			ranges=list(list(0, 0), list(4, 1), list(4, 0), list(8, 1)));
		Z = (Y < 0) + 1;
		write(Z, "out", format="csv");
		""";

	@Test
	public void incomingRealizationSupportDisablesConsumerSeparableReduction() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(SCRIPT));
		Set<CompiledHopKey> referencedCandidates = new LinkedHashSet<>();
		for(var fact : analysis.candidateRuleFacts().orderedFacts())
			for(var emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations())
					for(var clause : realization.supportClauses())
						for(var support : clause.requiredInputSupport())
							if(support.rule().parentOccurrence() != fact.key().parentOccurrence()
								&& !analysis.candidateRuleFacts()
									.orderedFactsForParent(support.rule().parentOccurrence()).isEmpty())
								referencedCandidates.add(support.rule().parentOccurrence());

		Assert.assertFalse("fixture must contain incoming candidate-realization support",
			referencedCandidates.isEmpty());
		Method gate = CandidateSelections.class.getDeclaredMethod(
			"consumerHasRealizationDependencies", PlacementAnalysis.class, CompiledHopKey.class);
		gate.setAccessible(true);
		for(CompiledHopKey referenced : referencedCandidates)
			Assert.assertEquals("incoming exact-realization support must disable consumer-separable row reduction",
				Boolean.TRUE, gate.invoke(null, analysis, referenced));
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}

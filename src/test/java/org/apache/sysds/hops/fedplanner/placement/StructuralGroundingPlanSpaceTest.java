/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.LanguageException;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Real-compiler regression contract for deriving loop grounding from mandatory
 * entry/input relations instead of a separate per-candidate grounding decision.
 */
public class StructuralGroundingPlanSpaceTest {
	private static final String ROW_ANCHOR =
		"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));";

	@Test
	public void undefinedLoopCarriedValueIsRejectedBeforePlanning() {
		LanguageException error = Assert.assertThrows(LanguageException.class,
			() -> validateFrontend("i=1;while(i<=2){x=x+1;i=i+1;}print(x);"));
		Assert.assertTrue("frontend diagnostic must identify the missing loop seed",
			error.getMessage().contains("x"));
	}

	@Test
	public void mutuallyDependentLoopValuesCannotSeedEachOther() {
		LanguageException error = Assert.assertThrows(LanguageException.class,
			() -> validateFrontend("i=1;while(i<=2){x=y+1;y=x+1;i=i+1;}print(x+y);"));
		Assert.assertTrue("frontend diagnostic must identify an uninitialized carried value",
			error.getMessage().contains("x") || error.getMessage().contains("y"));
	}

	@Test
	public void localAndFederatedEntriesRemainMandatoryForTwoCarriedValues() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis analysis = analyze(ROW_ANCHOR
			+ "p=rand(rows=8,cols=2,seed=7);q=X;i=1;"
			+ "while(i<=2){p=p+1;q=q+1;i=i+1;}"
			+ "print(sum(p));print(sum(q));", metrics);

		HopOccurrenceProjection p = joinedMatrixRead(analysis, "p");
		HopOccurrenceProjection q = joinedMatrixRead(analysis, "q");
		assertEntryAndBackedgeAreConjunctive(analysis, p, 2);
		assertEntryAndBackedgeAreConjunctive(analysis, q, 2);

		Set<PlacementState> pEntryStates = writerStates(analysis, outsideWriter(analysis, p));
		Assert.assertTrue("local initialization must remain a legal plan", pEntryStates.stream()
			.anyMatch(state -> state.output().name().equals("LOUT")));
		Assert.assertTrue("local initialization must retain entry upload plans", pEntryStates.stream()
			.anyMatch(state -> state.output().name().equals("FOUT")));
		Assert.assertTrue("the federated alias must retain a federated entry plan",
			writerStates(analysis, outsideWriter(analysis, q)).stream()
				.anyMatch(state -> state.output().name().equals("FOUT")));

		var phases = metrics.attributionSnapshot();
		Assert.assertTrue("structural dependency pruning must run for the real loop fixture",
			phases.phase(SearchSpaceMetrics.Phase.PROOF_DEPENDENCY_PRUNING).calls() > 0);
		Assert.assertEquals("mandatory entry/input relations require no candidate grounding",
			0, phases.phase(SearchSpaceMetrics.Phase.PROOF_GROUNDING).calls());
	}

	@Test
	public void everyBranchDefinitionMustSupportTheChosenLoopReader() throws Exception {
		PlacementAnalysis analysis = analyze(ROW_ANCHOR
			+ "if(sum(X)>0){p=rand(rows=8,cols=2,seed=7);}"
			+ "else{p=rand(rows=8,cols=2,seed=8);}"
			+ "i=1;while(i<=2){p=p+1;i=i+1;}print(sum(p));");
		HopOccurrenceProjection read = joinedMatrixRead(analysis, "p");

		assertEntryAndBackedgeAreConjunctive(analysis, read, 3);
		long entries = reachingFacts(analysis, read).stream()
			.filter(fact -> analysis.executionFrequencyFacts().executionWeight(fact.sourceWrite())
				!= analysis.executionFrequencyFacts().executionWeight(read.key()))
			.count();
		Assert.assertEquals("both runtime branch entries must remain mandatory", 2, entries);
	}

	@Test
	public void nonRecursiveFunctionResultRemainsALoopEntrySource() throws Exception {
		PlacementAnalysis analysis = analyzePublic(
			"seed=function(matrix[double] A) return(matrix[double] B){"
				+ "B=A;k=1;while(k<2){B=B+1;k=k+1;}};"
				+ ROW_ANCHOR
				+ "p=seed(X);i=1;while(i<=2){p=p+1;i=i+1;}print(sum(p));");
		HopOccurrenceProjection read = loopMatrixRead(analysis, "p");
		List<CompiledHopKey> ordinaryBackedges =
			analysis.cfgDefinitionSourcesInCanonicalOrder(read.key());
		Assert.assertEquals("the ordinary CFG channel must retain the loop backedge", 1,
			ordinaryBackedges.size());
		Assert.assertEquals("the backedge must retain one ordinary compatibility relation", 1,
			reachingFacts(analysis, read).size());

		Assert.assertTrue("the loop read must have a complete function-result boundary",
			analysis.logicalBoundaryRealizations().hasCompleteBoundary(read.key()));
		List<CompiledHopKey> allEntrySources =
			analysis.logicalBoundaryRealizations().sources(read.key());
		Assert.assertTrue("function result and ordinary backedge must both remain mandatory",
			allEntrySources.size() > ordinaryBackedges.size());
		Assert.assertTrue("the final boundary union must include the ordinary backedge",
			allEntrySources.containsAll(ordinaryBackedges));
		List<CompiledHopKey> functionEntries = allEntrySources.stream()
			.filter(source -> !ordinaryBackedges.contains(source))
			.toList();
		Assert.assertFalse("the function result must contribute at least one entry source",
			functionEntries.isEmpty());
		for(CompiledHopKey functionEntry : functionEntries)
			Assert.assertFalse("each function-result source must retain eligible entry placements",
				writerStates(analysis, functionEntry).isEmpty());
		Assert.assertFalse("the carried read must retain eligible placements",
			writerStates(analysis, read.key()).isEmpty());
	}

	@Test
	public void nestedFunctionLoopsAndZeroTripExitKeepExternalSeeds() throws Exception {
		PlacementAnalysis nested = analyze(
			"advance=function(matrix[double] A) return(matrix[double] B){"
				+ "B=A;i=1;while(i<=2){j=1;while(j<=2){B=B+1;j=j+1;}i=i+1;}};"
				+ ROW_ANCHOR + "Y=advance(X);print(sum(Y));");
		List<HopOccurrenceProjection> carried = joinedMatrixReads(nested, "B");
		Assert.assertFalse("fixture must retain a compiled carried value", carried.isEmpty());
		for(HopOccurrenceProjection read : carried)
			assertEntryAndBackedgeAreConjunctive(nested, read, 2);

		PlacementAnalysis zeroTrip = analyze(ROW_ANCHOR
			+ "p=rand(rows=8,cols=2,seed=7);i=1;"
			+ "while(i<1){p=p+1;i=i+1;}print(sum(p));");
		HopOccurrenceProjection loopRead = joinedMatrixRead(zeroTrip, "p");
		assertEntryAndBackedgeAreConjunctive(zeroTrip, loopRead, 2);
		CompiledHopKey entry = outsideWriter(zeroTrip, loopRead);
		HopOccurrenceProjection exitRead = zeroTrip.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "p".equals(data.getName()))
			.filter(occurrence -> zeroTrip.executionFrequencyFacts().executionWeight(occurrence.key()) == 1.0)
			.filter(occurrence -> zeroTrip.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertTrue("zero-trip exit must still be reachable from the initial write",
			zeroTrip.cfgDefinitionSourcesInCanonicalOrder(exitRead.key()).contains(entry));
	}

	private static void assertEntryAndBackedgeAreConjunctive(PlacementAnalysis analysis,
		HopOccurrenceProjection read, int expectedWriters) {
		List<CompiledHopKey> reaching = analysis.cfgDefinitionSourcesInCanonicalOrder(read.key());
		List<LogicalTransientInputFact> facts = reachingFacts(analysis, read);
		Assert.assertEquals("unexpected reaching-definition count for " + variable(read),
			expectedWriters, reaching.size());
		Assert.assertEquals("every reaching definition needs its own compatibility relation",
			Set.copyOf(reaching), facts.stream().map(LogicalTransientInputFact::sourceWrite)
				.collect(Collectors.toSet()));

		Set<CandidateRealizationReference> supportedReaders = null;
		for(LogicalTransientInputFact fact : facts) {
			Set<CandidateRealizationReference> readers = fact.compatibility().stream()
				.map(edge -> edge.readerRealization()).collect(Collectors.toSet());
			Assert.assertFalse("each reaching writer must support an executable reader", readers.isEmpty());
			if(supportedReaders == null)
				supportedReaders = new HashSet<>(readers);
			else
				supportedReaders.retainAll(readers);
		}
		Assert.assertNotNull(supportedReaders);
		Assert.assertFalse("entry and feedback must have a common executable reader realization",
			supportedReaders.isEmpty());
		for(CandidateRealizationReference reader : supportedReaders)
			Assert.assertEquals("a selected loop reader must consume every reaching definition",
				expectedWriters, analysis.transientCompatibilityForReader(reader).stream()
					.map(edge -> edge.sourceRealization().rule().parentOccurrence()).distinct().count());

		double readWeight = analysis.executionFrequencyFacts().executionWeight(read.key());
		long entries = facts.stream().filter(fact ->
			analysis.executionFrequencyFacts().executionWeight(fact.sourceWrite()) != readWeight).count();
		Assert.assertTrue("a loop needs at least one external first-iteration source", entries >= 1);
		Assert.assertTrue("fixture must retain a real feedback definition", entries < expectedWriters);
	}

	private static List<LogicalTransientInputFact> reachingFacts(PlacementAnalysis analysis,
		HopOccurrenceProjection read) {
		return analysis.logicalTransientInputsForReader(read.key(), 0);
	}

	private static CompiledHopKey outsideWriter(PlacementAnalysis analysis, HopOccurrenceProjection read) {
		return reachingFacts(analysis, read).stream()
			.map(LogicalTransientInputFact::sourceWrite)
			.filter(source -> analysis.executionFrequencyFacts().executionWeight(source) == 1.0)
			.findFirst().orElseThrow(AssertionError::new);
	}

	private static Set<PlacementState> writerStates(PlacementAnalysis analysis, CompiledHopKey writer) {
		return Set.copyOf(analysis.graph().node(writer).orElseThrow().legalAlternatives());
	}

	private static HopOccurrenceProjection joinedMatrixRead(PlacementAnalysis analysis, String name) {
		List<HopOccurrenceProjection> reads = joinedMatrixReads(analysis, name);
		Assert.assertEquals("expected one joined matrix read for " + name + ": "
			+ analysis.occurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD && name.equals(data.getName()))
				.map(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow().kind() + "@"
					+ analysis.executionFrequencyFacts().executionWeight(occurrence.key()) + "#"
					+ analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size()).toList(),
			1, reads.size());
		return reads.get(0);
	}

	private static HopOccurrenceProjection loopMatrixRead(PlacementAnalysis analysis, String name) {
		List<HopOccurrenceProjection> reads = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && name.equals(data.getName()))
			.filter(occurrence -> occurrence.hop().getDataType().isMatrix())
			.filter(occurrence -> analysis.executionFrequencyFacts().executionWeight(occurrence.key()) != 1.0)
			.toList();
		Assert.assertEquals("expected one loop matrix read for " + name, 1, reads.size());
		return reads.get(0);
	}

	private static List<HopOccurrenceProjection> joinedMatrixReads(PlacementAnalysis analysis, String name) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && name.equals(data.getName()))
			.filter(occurrence -> occurrence.hop().getDataType().isMatrix())
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.filter(occurrence -> analysis.executionFrequencyFacts().executionWeight(occurrence.key()) != 1.0)
			.toList();
	}

	private static String variable(HopOccurrenceProjection occurrence) {
		Hop hop = occurrence.hop();
		return hop instanceof DataOp data ? data.getName() : hop.getName();
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		return analyze(script, null);
	}

	private static PlacementAnalysis analyzePublic(String script) throws Exception {
		return analyze(script, null, Privacy.PUBLIC);
	}

	private static PlacementAnalysis analyze(String script, SearchSpaceMetrics metrics) throws Exception {
		return analyze(script, metrics, Privacy.PRIVATE_AGGREGATE);
	}

	private static PlacementAnalysis analyze(String script, SearchSpaceMetrics metrics,
		Privacy privacy) throws Exception {
		DMLProgram program = validateFrontend(script);
		DMLTranslator translator = new DMLTranslator(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder(null, null, metrics).buildAnalysis(program);
	}

	private static DMLProgram validateFrontend(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		return program;
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class NeutralPlacementFixedPointCompositionTest {
	private static final String SOURCE = "X=federated(addresses=list(\"localhost:18101/X1\","
		+ "\"localhost:18102/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n";
	private static final String TRANSIENT_CFG = SOURCE
		+ "a=X+1; gate=matrix(1,rows=1,cols=1);\n"
		+ "if(sum(gate)>0) { z=a+2; } else { z=a+3; }\n"
		+ "print(sum(z));\n";
	private static final String FUNCTION = "f=function(matrix[double] A) return (matrix[double] B) {\n"
		+ " B=t(A)%*%A;\n}\n"
		+ SOURCE + "Y=f(X); print(sum(Y));\n";
	static final String ACTIONS = SOURCE
		+ "p=matrix(1,rows=2,cols=1); pred=X%*%p; grad=t(X)%*%pred; print(sum(grad));\n";
	private static final String SPARSE_CFG = SOURCE
		+ "left=X+1; right=X+2; gate=matrix(1,rows=1,cols=1); "
		+ "if(sum(gate)>0) { print(sum(left)); print(sum(right)); }\n";
	private static final String LOOP_CFG = SOURCE
		+ "B=matrix(1,rows=2,cols=2); i=1; while(i<=2) { B=B+1; i=i+1; } print(sum(B));\n";

	@Test
	public void representativeCompositionsTerminateAtStablePassWithinDeclaredBound() throws Exception {
		for(String script : List.of(TRANSIENT_CFG, FUNCTION, ACTIONS)) {
			List<NeutralPlacementGraphBuilder.FixedPointPass> trace = new ArrayList<>();
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder(trace::add)
				.buildAnalysis(compileProtected(script));
			Assert.assertFalse(analysis.graph().nodes().isEmpty());
			if(ACTIONS.equals(script))
				Assert.assertTrue("action fixture must exercise relocation or derived-FOUT publication",
					!analysis.graph().relocationActions().isEmpty()
						|| !analysis.graph().derivedFoutMaterializationActions().isEmpty());
			assertAllPhasesConverged(trace);
		}
	}

	@Test
	public void rebuildingTheSameCompiledProgramIsIdempotent() throws Exception {
		DMLProgram program = compileProtected(FUNCTION);
		List<NeutralPlacementGraphBuilder.FixedPointPass> firstTrace = new ArrayList<>();
		List<NeutralPlacementGraphBuilder.FixedPointPass> secondTrace = new ArrayList<>();
		PlacementAnalysis first = new NeutralPlacementGraphBuilder(firstTrace::add).buildAnalysis(program);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder(secondTrace::add).buildAnalysis(program);

		Assert.assertEquals(first.analysisFingerprint(), second.analysisFingerprint());
		Assert.assertEquals(first.graph().normalizedSignatureWithLegalAssignments(),
			second.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(first.candidateRuleFacts().orderedFacts(), second.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(first.logicalTransientInputsInCanonicalOrder(),
			second.logicalTransientInputsInCanonicalOrder());
		Assert.assertEquals(firstTrace, secondTrace);
		assertAllPhasesConverged(firstTrace);
	}

	@Test
	public void independentStatementOrderPreservesSemanticFixedPointSnapshot() throws Exception {
		String firstOrder = SOURCE + "a=X+1; b=X+2; print(sum(a)+sum(b));\n";
		String reverseOrder = SOURCE + "b=X+2; a=X+1; print(sum(a)+sum(b));\n";
		List<NeutralPlacementGraphBuilder.FixedPointPass> firstTrace = new ArrayList<>();
		List<NeutralPlacementGraphBuilder.FixedPointPass> reverseTrace = new ArrayList<>();
		PlacementAnalysis first = new NeutralPlacementGraphBuilder(firstTrace::add)
			.buildAnalysis(compileProtected(firstOrder));
		PlacementAnalysis reverse = new NeutralPlacementGraphBuilder(reverseTrace::add)
			.buildAnalysis(compileProtected(reverseOrder));

		Assert.assertEquals(semanticSnapshot(first), semanticSnapshot(reverse));
		assertAllPhasesConverged(firstTrace);
		assertAllPhasesConverged(reverseTrace);
	}

	@Test
	public void aggregateComplexityMetricsAreAnalysisScopedAndSemanticallyInert() throws Exception {
		PlacementAnalysis uninstrumented = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder instrumentedBuilder = new NeutralPlacementGraphBuilder(null, metrics);
		PlacementAnalysis instrumented = instrumentedBuilder.buildAnalysis(compileProtected(ACTIONS));

		Assert.assertEquals(uninstrumented.analysisFingerprint(), instrumented.analysisFingerprint());
		Assert.assertEquals(uninstrumented.graph().normalizedSignatureWithLegalAssignments(),
			instrumented.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(uninstrumented.candidateRuleFacts().orderedFacts(),
			instrumented.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(uninstrumented.logicalTransientInputsInCanonicalOrder(),
			instrumented.logicalTransientInputsInCanonicalOrder());

		SearchSpaceMetrics.Snapshot first = metrics.snapshot();
		Assert.assertTrue(first.fixedPointPasses() > 0);
		Assert.assertTrue(first.cfgRefinementPasses() > 0);
		Assert.assertTrue(first.semanticPasses() > 0);
		Assert.assertTrue(first.publicationPasses() > 0);
		Assert.assertTrue(first.directClosurePasses() > 0);
		Assert.assertTrue(first.directClosureStablePasses() > 0);
		Assert.assertTrue(first.directClosureFullPasses() > 0);
		Assert.assertTrue(first.proofQueries() > 0);
		Assert.assertEquals(first.proofQueries(),
			first.exactContextUniqueQueries() + first.exactContextRepeatedQueries()
				+ first.exactContextOverflowQueries());
		Assert.assertEquals(first.proofQueries(), first.proofGraphsBuilt());
		Assert.assertTrue(first.proofStatesBuilt() > 0);
		Assert.assertTrue(first.proofAlternativesBuilt() >= 0);
		Assert.assertTrue(first.proofDependencyEdgesBuilt() >= 0);
		Assert.assertTrue("factorized proof construction may publish alternatives without row export",
			first.proofRowsExamined() >= 0);
		Assert.assertTrue(first.supportProductDescriptorsExpanded()
			+ first.supportProductDescriptorsReused() > 0);
		Assert.assertTrue("owner compaction scans cannot be negative",
			first.ownerCompactionElementsScanned() >= 0);
		Assert.assertTrue("only legacy-pruned alternatives may require an owner compaction scan",
			first.ownerCompactionElementsScanned() <= first.proofAlternativesBuilt());
		Assert.assertTrue("the direct acyclic evaluator must eliminate compaction for this fixture",
			first.ownerCompactionElementsScanned() < first.proofAlternativesBuilt());
		Assert.assertTrue(first.alternativesRemoved() <= first.proofAlternativesBuilt());
		Assert.assertTrue(first.supportLeaves() >= first.uniqueProofs());
		Assert.assertEquals(first.supportLeaves(), first.uniqueProofs() + first.duplicateProofs());
		Assert.assertTrue(first.factorizedClauses() > 0);
		Assert.assertTrue("receipt relation slots are a non-materializing raw product upper bound",
			first.receiptRelationSlots() >= first.factorizedClauses());
		Assert.assertEquals(0, first.candidateReceiptsCreated());
		Assert.assertTrue(first.factorizedProofListsReused() > 0);
		Assert.assertTrue(first.factorizedBindingListsReused() > 0);
		assertSupportFactorizationPreservesClauseOwnership(instrumented);

		PlacementAnalysis repeated = instrumentedBuilder.buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics.Snapshot second = metrics.snapshot();
		Assert.assertEquals(instrumented.analysisFingerprint(), repeated.analysisFingerprint());
		Assert.assertEquals("a reused builder must reset rather than accumulate analysis counters",
			first, second);
	}

	@Test
	public void lazyReceiptRelationRetainsExactLegacyLexicalRank() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder(null, metrics)
			.buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics.Snapshot built = metrics.snapshot();
		Assert.assertTrue(built.receiptRelationSlots() > 0);
		Assert.assertEquals("analysis construction must not allocate selector receipts",
			0, built.candidateReceiptsCreated());

		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt> receipts =
			analysis.candidateRuleFacts().orderedFacts().stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream()
					.flatMap(emission -> analysis.canonicalCandidateReceipts(fact.key(), emission).stream()))
				.toList();
		Assert.assertEquals(built.receiptRelationSlots(), receipts.size());
		Assert.assertEquals(receipts.size(), metrics.snapshot().candidateReceiptsCreated());
		List<?> lexical = receipts.stream().sorted().toList();
		List<?> ranked = receipts.stream()
			.sorted(java.util.Comparator.comparingInt(analysis::candidateReceiptRank)).toList();
		for(int index = 0; index < lexical.size(); index++)
			Assert.assertEquals("compressed relation rank differs at " + index,
				((org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt)
					lexical.get(index)).normalizedSignature(),
				((org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt)
					ranked.get(index)).normalizedSignature());
		Assert.assertEquals("compressed relation rank must equal legacy receipt bytes", lexical, ranked);
	}

	@Test
	public void directNativeProductsRemainUnmaterializedThroughAnalysisFormation() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileProtected(SOURCE + "Y=X+1; print(sum(Y));\n"));
		List<CandidateSupportRelation> deferred = analysis.candidateRuleFacts().orderedFacts().stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.map(PlacementAnalysis.CandidateEmissionRealization::supportRelation)
			.filter(relation -> !relation.distinctDeferredNativeProofRecipes().isEmpty()).toList();
		Assert.assertFalse("fixture must publish deferred native proof products", deferred.isEmpty());
		Assert.assertTrue("analysis formation must not decode factorized support leaves",
			deferred.stream().allMatch(relation -> relation.leafMaterializationCount() == 0));
		Assert.assertTrue("builder must retain product dimensions instead of flat clauses",
			deferred.stream().anyMatch(relation -> relation.routes().stream()
				.anyMatch(route -> !route.bindingChoicesBySlot().isEmpty())));
	}

	@Test
	public void exactContextDiagnosticsRemainBoundedWhenTrackingIsDisabled() throws Exception {
		String property = "sysds.fedplanner.metrics.maxExactContexts";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "0");
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			new NeutralPlacementGraphBuilder(null, metrics).buildAnalysis(compileProtected(ACTIONS));
			SearchSpaceMetrics.Snapshot snapshot = metrics.snapshot();
			Assert.assertTrue(snapshot.proofQueries() > 0);
			Assert.assertEquals(0, snapshot.exactContextUniqueQueries());
			Assert.assertEquals(0, snapshot.exactContextRepeatedQueries());
			Assert.assertEquals(snapshot.proofQueries(), snapshot.exactContextOverflowQueries());
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void zeroStructuralArenaBudgetChangesWorkOnlyNeverCandidateSemantics() throws Exception {
		String property = "sysds.fedplanner.structuralArena.maxEntries";
		String prior = System.getProperty(property);
		try {
			PlacementAnalysis baseline = new NeutralPlacementGraphBuilder()
				.buildAnalysis(compileProtected(ACTIONS));
			System.setProperty(property, "0");
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			PlacementAnalysis zeroBudget = new NeutralPlacementGraphBuilder(null, metrics)
				.buildAnalysis(compileProtected(ACTIONS));
			Assert.assertEquals(baseline.analysisFingerprint(), zeroBudget.analysisFingerprint());
			Assert.assertEquals(baseline.candidateRuleFacts().orderedFacts(),
				zeroBudget.candidateRuleFacts().orderedFacts());
			Assert.assertTrue("arena exhaustion must fall back to exact structural work",
				metrics.snapshot().structuralArenaOverflows() > 0);
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void dirtyDirectClosureMatchesFullRecomputeAtTheComposedTransferBoundary() throws Exception {
		SearchSpaceMetrics incrementalMetrics = new SearchSpaceMetrics();
		PlacementAnalysis incremental = new NeutralPlacementGraphBuilder(
			null, incrementalMetrics, true).buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics fullMetrics = new SearchSpaceMetrics();
		PlacementAnalysis full = new NeutralPlacementGraphBuilder(
			null, fullMetrics, false).buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics shadowMetrics = new SearchSpaceMetrics();
		PlacementAnalysis shadow = new NeutralPlacementGraphBuilder(null, shadowMetrics,
			NeutralPlacementGraphBuilder.DirectClosureMode.SHADOW)
			.buildAnalysis(compileProtected(ACTIONS));

		Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
		Assert.assertEquals(full.graph().normalizedSignatureWithLegalAssignments(),
			incremental.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
			incremental.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			incremental.logicalTransientInputsInCanonicalOrder());
		Assert.assertEquals("shadow compares FULL and DELTA from each identical pre-transfer input",
			full.analysisFingerprint(), shadow.analysisFingerprint());
		Assert.assertEquals(full.graph().nodes(), shadow.graph().nodes());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
			shadow.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			shadow.logicalTransientInputsInCanonicalOrder());
		Assert.assertTrue("fixture must execute at least one revision-local dirty pass",
			incrementalMetrics.snapshot().incrementalPasses() > 0);
		Assert.assertTrue("an independent component must be reused rather than rebuilt",
			incrementalMetrics.snapshot().incrementalFactsReused() > 0);
		Assert.assertTrue("DELTA must recompute fewer facts than FULL",
			incrementalMetrics.snapshot().incrementalFactsRecomputed()
				< fullMetrics.snapshot().directClosureFullPasses()
					* full.candidateRuleFacts().orderedFacts().size());
		Assert.assertEquals("FULL mode must count every actual full recomputation",
			fullMetrics.snapshot().directClosurePasses(),
			fullMetrics.snapshot().directClosureFullPasses());
		Assert.assertEquals("SHADOW performs one full comparison on every closure pass",
			shadowMetrics.snapshot().directClosurePasses(),
			shadowMetrics.snapshot().directClosureFullPasses());
		Assert.assertTrue("DELTA must retain non-full passes after its initial full seed",
			incrementalMetrics.snapshot().directClosureFullPasses()
				< incrementalMetrics.snapshot().directClosurePasses());
	}

	@Test
	public void sparseCfgReplaySkipsIndependentReadersAndMatchesFullAndShadow() throws Exception {
		SearchSpaceMetrics deltaMetrics = new SearchSpaceMetrics();
		PlacementAnalysis delta = new NeutralPlacementGraphBuilder(null, deltaMetrics,
			NeutralPlacementGraphBuilder.DirectClosureMode.DELTA).buildAnalysis(compileProtected(SPARSE_CFG));
		SearchSpaceMetrics fullMetrics = new SearchSpaceMetrics();
		PlacementAnalysis full = new NeutralPlacementGraphBuilder(null, fullMetrics,
			NeutralPlacementGraphBuilder.DirectClosureMode.FULL).buildAnalysis(compileProtected(SPARSE_CFG));
		PlacementAnalysis shadow = new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(),
			NeutralPlacementGraphBuilder.DirectClosureMode.SHADOW).buildAnalysis(compileProtected(SPARSE_CFG));

		Assert.assertEquals(full.analysisFingerprint(), delta.analysisFingerprint());
		Assert.assertEquals(full.analysisFingerprint(), shadow.analysisFingerprint());
		Assert.assertEquals(full.graph().nodes(), delta.graph().nodes());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(), delta.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			delta.logicalTransientInputsInCanonicalOrder());
		Assert.assertTrue("sparse CFG replay must execute a selective pass",
			deltaMetrics.snapshot().cfgReplaySelectivePasses() > 0);
		Assert.assertTrue("sparse CFG replay must reuse at least one unrelated exact reader",
			deltaMetrics.snapshot().cfgReplayReadersReused() > 0);
		Assert.assertTrue("fixture must still recompute the affected owner/reader cone",
			deltaMetrics.snapshot().cfgReplayReadersRecomputed() > 0);
		Assert.assertTrue(fullMetrics.snapshot().cfgReplayFullPasses() > 0);
		Assert.assertEquals(0, fullMetrics.snapshot().cfgReplaySelectivePasses());
		Assert.assertEquals(0, fullMetrics.snapshot().cfgReplayReadersReused());
	}

	@Test
	public void multiDefinitionLoopFallbackMatchesFullDeltaAndShadowIncludingSeeds() throws Exception {
		SearchSpaceMetrics deltaMetrics = new SearchSpaceMetrics();
		PlacementAnalysis delta = new NeutralPlacementGraphBuilder(null, deltaMetrics,
			NeutralPlacementGraphBuilder.DirectClosureMode.DELTA).buildAnalysis(compileProtected(LOOP_CFG));
		PlacementAnalysis full = new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(),
			NeutralPlacementGraphBuilder.DirectClosureMode.FULL).buildAnalysis(compileProtected(LOOP_CFG));
		PlacementAnalysis shadow = new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(),
			NeutralPlacementGraphBuilder.DirectClosureMode.SHADOW).buildAnalysis(compileProtected(LOOP_CFG));

		Assert.assertEquals(full.analysisFingerprint(), delta.analysisFingerprint());
		Assert.assertEquals(full.analysisFingerprint(), shadow.analysisFingerprint());
		Assert.assertEquals(full.graph().nodes(), delta.graph().nodes());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(), shadow.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			shadow.logicalTransientInputsInCanonicalOrder());
		Assert.assertTrue("actual loop replay must install provisional loop authority",
			deltaMetrics.snapshot().cfgReplayLoopSeedsInstalled() > 0);
		Assert.assertTrue("multi-definition/loop epochs must exercise unsafe fallback",
			deltaMetrics.snapshot().cfgReplayFallbackPasses() > 0);
		Assert.assertTrue("mandatory initial full must remain distinct from unsafe fallback",
			deltaMetrics.snapshot().cfgReplayFullPasses()
				> deltaMetrics.snapshot().cfgReplayFallbackPasses());
	}

	private static void assertSupportFactorizationPreservesClauseOwnership(PlacementAnalysis analysis) {
		List<CandidateRealizationSupportClause> clauses = analysis.candidateRuleFacts().orderedFacts().stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream()).toList();
		Set<CandidateRealizationSupportClause> ownerIdentities =
			Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRealizationSupportClause clause : clauses)
			Assert.assertTrue("support-clause owners must never be structurally interned",
				ownerIdentities.add(clause));
		boolean sharedSubstructure = false;
		for(int left = 0; left < clauses.size() && !sharedSubstructure; left++)
			for(int right = left + 1; right < clauses.size(); right++)
				if(clauses.get(left).proofDependencies() == clauses.get(right).proofDependencies()
					|| clauses.get(left).inputBindings() == clauses.get(right).inputBindings()) {
					sharedSubstructure = true;
					break;
				}
		Assert.assertTrue("fixture must retain analysis-scoped factorized support substructure",
			sharedSubstructure);
	}

	private static void assertAllPhasesConverged(List<NeutralPlacementGraphBuilder.FixedPointPass> trace) {
		for(String phase : List.of("cfg-refinement", "function-boundary", "semantic", "publication")) {
			List<NeutralPlacementGraphBuilder.FixedPointPass> passes = trace.stream()
				.filter(pass -> phase.equals(pass.phase())).toList();
			Assert.assertFalse("missing fixed-point trace for " + phase, passes.isEmpty());
			NeutralPlacementGraphBuilder.FixedPointPass last = passes.get(passes.size() - 1);
			Assert.assertTrue("last pass must be an idempotent replay for " + phase, last.stable());
			Assert.assertTrue("phase exceeded its declared termination bound: " + last,
				last.pass() >= 0 && last.pass() < last.passLimit());
		}
	}

	private static List<String> semanticSnapshot(PlacementAnalysis analysis) {
		List<String> rows = new ArrayList<>();
		for(var node : analysis.graph().nodes()) {
			String op = analysis.hop(node.key()).map(hop -> hop.getClass().getSimpleName() + ':' + hop.getOpString())
				.orElse(node.kind().name());
			rows.add("NODE|" + op + '|' + node.emittedWork() + '|'
				+ node.legalAlternatives().stream().map(PlacementState::normalizedSignature).sorted().toList());
		}
		rows.add("COUNTS|candidates=" + analysis.candidateRuleFacts().orderedFacts().size()
			+ "|logical=" + analysis.logicalTransientInputsInCanonicalOrder().size()
			+ "|relocations=" + analysis.graph().relocationActions().size()
			+ "|derived=" + analysis.graph().derivedFoutMaterializationActions().size());
		return rows.stream().sorted().toList();
	}

	static DMLProgram compileProtected(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}

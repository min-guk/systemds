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
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
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
	private static final String RECURSIVE_DIRECT = "X=federated(addresses=list("
		+ "\"localhost:18101/X1\",\"localhost:18102/X2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
		+ "B=matrix(1,rows=2,cols=2); P=X%*%B; outer=1;\n"
		+ "while(outer<=2) { S=matrix(0,rows=2,cols=2); R=B-S; V=R; inner=1; P_1K=P;\n"
		+ " while(inner<=2) { ssX_V=V; Q=P_1K*(X%*%ssX_V);"
		+ " HV=t(X)%*%(Q-P_1K*(rowSums(Q)%*%matrix(1,rows=1,cols=2)));"
		+ " alpha=sum(R^2)/sum(V*HV); S=S+alpha*V; R=R-alpha*HV; V=R+V; inner=inner+1; }\n"
		+ " B=B+S; P=X%*%B; outer=outer+1; }\n"
		+ "print(sum(Q));\n";
	private static final String PRIVACY_LOOP_SEED = """
		X=federated(addresses=list("localhost:18101/X1","localhost:18102/X2",
		 "localhost:18103/X3","localhost:18104/X4"),
		 ranges=list(list(0,0),list(1024,64),list(1024,0),list(2048,64),
		 list(2048,0),list(3072,64),list(3072,0),list(4096,64)));
		R=matrix(1,rows=64,cols=64);
		n=nrow(X); d=ncol(X);
		v0=t(colSums(X))/n;
		s=v0/(abs(v0)+1);
		Rn=R/(abs(R)+1);
		anchor=s; state=s;
		for(iter in 1:5) {
		 RUP1=X%*%state;
		 RUQ1=abs(RUP1);
		 RUA1=(t(X)%*%RUQ1)/n;
		 RU1=(abs(RUA1)+anchor)/(d+1)+1/1000;
		 state=RU1;
		}
		print(sum(state));
		""";

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
	public void nativeContinuityIsClearedAfterExceptionalAndSuccessfulBuilds() throws Exception {
		java.lang.reflect.Field continuity = NeutralPlacementGraphBuilder.class
			.getDeclaredField("allDefinitionContinuity");
		continuity.setAccessible(true);
		java.lang.reflect.Field products = NeutralPlacementGraphBuilder.class
			.getDeclaredField("relocationProducts");
		products.setAccessible(true);
		boolean[] abort = {true};
		NeutralPlacementGraphBuilder[] builder = new NeutralPlacementGraphBuilder[1];
		RuntimeException expected = new RuntimeException("test-only publication interruption");
		builder[0] = new NeutralPlacementGraphBuilder(pass -> {
			if(abort[0] && "publication".equals(pass.phase())) {
				try {
					Assert.assertNotNull("fixture must populate the build-local resolver before abort",
						continuity.get(builder[0]));
				}
				catch(IllegalAccessException e) {
					throw new AssertionError(e);
				}
				throw expected;
			}
		});
		DMLProgram program = compileProtected(ACTIONS);
		try {
			builder[0].buildAnalysis(program);
			Assert.fail("publication observer must interrupt the first build");
		}
		catch(RuntimeException failure) {
			Assert.assertSame(expected, failure);
		}
		Assert.assertNull("failed analysis must not retain its proof context", continuity.get(builder[0]));
		Assert.assertTrue("failed analysis must release relocation products",
			((java.util.Map<?,?>)products.get(builder[0])).isEmpty());
		abort[0] = false;
		PlacementAnalysis repeated = builder[0].buildAnalysis(program);
		Assert.assertNull("successful analysis must also release its context", continuity.get(builder[0]));
		Assert.assertTrue("successful analysis must release relocation products",
			((java.util.Map<?,?>)products.get(builder[0])).isEmpty());
		PlacementAnalysis fresh = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertEquals(fresh.analysisFingerprint(), repeated.analysisFingerprint());
		Assert.assertEquals(fresh.candidateRuleFacts().orderedFacts(), repeated.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(fresh.logicalTransientInputsInCanonicalOrder(),
			repeated.logicalTransientInputsInCanonicalOrder());
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
		long composedClosures = metrics.attributionSnapshot().phases().stream()
			.filter(phase -> phase.phase().equals("CLOSURE_REPLAY")).findFirst().orElseThrow().calls();
		Assert.assertEquals("one closure per function/composed pass, plus initial and privacy boundaries",
			first.functionBoundaryPasses() + first.publicationPasses() + 2, composedClosures);
		Assert.assertEquals("semantic and publication are one completed transfer",
			first.semanticPasses(), first.publicationPasses());
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
		Assert.assertTrue(first.proofRowsExamined() >= first.proofAlternativesBuilt());
		Assert.assertEquals("dead pruning must compact each built alternative exactly once",
			first.proofAlternativesBuilt(), first.ownerCompactionElementsScanned());
		Assert.assertTrue(first.alternativesRemoved() <= first.proofAlternativesBuilt());
		Assert.assertTrue(first.supportLeaves() >= first.uniqueProofs());
		Assert.assertEquals(first.supportLeaves(), first.uniqueProofs() + first.duplicateProofs());
		Assert.assertTrue(first.factorizedClauses() > 0);
		Assert.assertEquals("receipt slots preserve every clause without eager receipt objects",
			first.factorizedClauses(), first.receiptRelationSlots());
		Assert.assertEquals(0, first.candidateReceiptsCreated());
		Assert.assertTrue(first.factorizedProofListsReused() > 0);
		Assert.assertTrue(first.factorizedBindingListsReused() > 0);
		assertSupportFactorizationPreservesClauseOwnership(instrumented);

		PlacementAnalysis repeated = instrumentedBuilder.buildAnalysis(compileProtected(ACTIONS));
		SearchSpaceMetrics.Snapshot second = metrics.snapshot();
		Assert.assertEquals(instrumented.analysisFingerprint(), repeated.analysisFingerprint());
		for(var component : SearchSpaceMetrics.Snapshot.class.getRecordComponents()) {
			// Identity-cache hits depend on object reuse across analysis invocations;
			// they are diagnostic, not an analysis-scoped work count.
			if(component.getName().equals("signatureIdentityCacheHits"))
				continue;
			Assert.assertEquals("a reused builder must reset rather than accumulate "
				+ component.getName(), component.getAccessor().invoke(first),
				component.getAccessor().invoke(second));
		}
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
		for(String script : List.of(ACTIONS, TRANSIENT_CFG, FUNCTION)) {
			SearchSpaceMetrics incrementalMetrics = new SearchSpaceMetrics();
			PlacementAnalysis incremental = new NeutralPlacementGraphBuilder(
				null, incrementalMetrics, true).buildAnalysis(compileProtected(script));
			PlacementAnalysis full = new NeutralPlacementGraphBuilder(
				null, new SearchSpaceMetrics(), false).buildAnalysis(compileProtected(script));

			Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
			Assert.assertEquals(full.graph().normalizedSignatureWithLegalAssignments(),
				incremental.graph().normalizedSignatureWithLegalAssignments());
			Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
				incremental.candidateRuleFacts().orderedFacts());
			Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
				incremental.logicalTransientInputsInCanonicalOrder());
			if(ACTIONS.equals(script)) {
				Assert.assertTrue("fixture must execute at least one revision-local dirty pass",
					incrementalMetrics.snapshot().incrementalPasses() > 0);
				Assert.assertTrue("an independent component must be reused rather than rebuilt",
					incrementalMetrics.snapshot().incrementalFactsReused() > 0);
			}
		}
	}

	@Test
	public void ordinaryTransientReaderRetainsExactNativeBindingAfterPhysicalReplay() throws Exception {
		String script = TRANSIENT_CFG;
		PlacementAnalysis incremental = new NeutralPlacementGraphBuilder(null,
			new SearchSpaceMetrics(), true).buildAnalysis(compileProtected(script));
		PlacementAnalysis full = new NeutralPlacementGraphBuilder(null,
			new SearchSpaceMetrics(), false).buildAnalysis(compileProtected(script));
		Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
			incremental.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			incremental.logicalTransientInputsInCanonicalOrder());
		boolean readerBoundNative = incremental.candidateRuleFacts().orderedFacts().stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.filter(emission -> emission.emissionState().placementState().execType() == ExecType.FED
				&& emission.emissionState().placementState().output() == FederatedOutput.FOUT)
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
				|| realization.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP)
			.flatMap(realization -> realization.supportClauses().stream())
			.flatMap(clause -> clause.inputBindings().stream())
			.anyMatch(binding -> incremental.hop(binding.source().rule().parentOccurrence())
				.filter(hop -> hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD)
				.isPresent());
		Assert.assertTrue("fixture must actually exercise direct native proof through an ordinary TRead",
			readerBoundNative);
	}

	@Test
	public void recursiveDirectPublicationIsStableWithIncrementalAndMemoDisabled() throws Exception {
		String memoProperty = "sysds.fedplanner.continuityMemo.maxEntries";
		String prior = System.getProperty(memoProperty);
		try {
			System.setProperty(memoProperty, "0");
			SearchSpaceMetrics incrementalMetrics = new SearchSpaceMetrics();
			PlacementAnalysis incremental = new NeutralPlacementGraphBuilder(
				null, incrementalMetrics, true).buildAnalysis(compileProtected(RECURSIVE_DIRECT));
			PlacementAnalysis full = new NeutralPlacementGraphBuilder(
				null, new SearchSpaceMetrics(), false).buildAnalysis(compileProtected(RECURSIVE_DIRECT));

			Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
			// Compare the complete domains/constraints/actions, not the exponential
			// assignment product intended only for bounded shadow fixtures.
			Assert.assertEquals(full.graph().normalizedSignature(),
				incremental.graph().normalizedSignature());
			Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
				incremental.candidateRuleFacts().orderedFacts());
			Assert.assertTrue("fixture must exercise repeated direct closure",
				incrementalMetrics.snapshot().directClosurePasses() > 1);
		}
		finally {
			if(prior == null)
				System.clearProperty(memoProperty);
			else
				System.setProperty(memoProperty, prior);
		}
	}

	@Test
	public void privacyFilteredLoopSeedProgressDoesNotBecomeAFalseCycle() throws Exception {
		DMLProgram program = compileProtected(PRIVACY_LOOP_SEED);
		PlacementAnalysis first = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertFalse(first.candidateRuleFacts().orderedFacts().isEmpty());
		Assert.assertEquals(first.analysisFingerprint(), second.analysisFingerprint());
		Assert.assertEquals(first.candidateRuleFacts().orderedFacts(),
			second.candidateRuleFacts().orderedFacts());
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

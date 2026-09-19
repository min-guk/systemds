/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Composition regressions for dynamic native FederationMap authority. */
public class DynamicNativeLayoutCompositionTest {
	private static final String SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "R=rev(A);\nU=exp(R);\nprint(sum(U));\n";
	private static final String CHAINED_DYNAMIC_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "R=rev(A);\nU=roll(R,1);\nV=exp(U);\nprint(sum(V));\n";
	private static final String FULL_ROLL_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\"),"
			+ "ranges=list(list(0,0),list(8,2)));\n"
			+ "R=roll(A,1);\nU=exp(R);\nprint(sum(U));\n";
	private static final String COL_ROLL_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(8,1),list(0,1),list(8,2)));\n"
			+ "R=roll(A,1);\nU=exp(R);\nprint(sum(U));\n";
	private static final String TRANSIENT_REVERSE_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "gate=matrix(1,rows=1,cols=1);\n"
			+ "if(sum(gate)>0) { T=rev(A); } else { T=rev(A); }\n"
			+ "U=exp(T);\nprint(sum(U));\n";

	@Test
	public void mapCopyAfterDynamicRowReverseRetainsDynamicNativeAuthority() throws Exception {
		DMLProgram program = compile(SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var reverse = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.REV)
			.findFirst().orElseThrow(AssertionError::new);
		var exponential = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.EXP)
			.findFirst().orElseThrow(AssertionError::new);

		var fact = analysis.candidateRuleFacts().requireExact(exponential.key(),
			List.of(CandidateInputState.present(FType.ROW)));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, fact.status());
		var dynamic = fact.allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
			.filter(realization -> realization.supportClauses().stream().anyMatch(clause ->
				!realization.nativeWorkerPoolLayoutExact(clause)
					&& clause.inputBindings().stream().anyMatch(binding ->
						binding.kind() == CandidateInputBindingKind.DIRECT
							&& binding.source().rule().parentOccurrence() == reverse.key())))
			.toList();
		Assert.assertFalse("exp(rev(A)) must retain the reversed map's dynamic native authority: "
			+ fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.map(PlacementAnalysis.CandidateEmissionRealization::normalizedSignature).toList(), dynamic.isEmpty());
		Assert.assertFalse("A dynamic predecessor cannot be republished with the source's stale durable geometry",
			fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.anyMatch(realization -> realization.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP
					&& realization.supportClauses().stream().flatMap(clause -> clause.inputBindings().stream())
						.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.DIRECT
							&& binding.source().rule().parentOccurrence() == reverse.key())));
		var selected = new FedAllPlacementAdapter().select(analysis);
		var receipt = selected.selectedCandidateSelections().stream()
			.filter(candidate -> candidate.rule().parentOccurrence() == exponential.key())
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertEquals(PlacementLayoutKind.NATIVE_LINEAGE, receipt.realization().key().layoutKind());
		Assert.assertFalse(receipt.realization().nativeWorkerPoolLayoutExact(receipt.supportClause()));
		Assert.assertTrue("The released aggregate must consume the selected dynamic result directly",
			selected.selectedCandidateSelections().stream()
				.filter(candidate -> analysis.hop(candidate.rule().parentOccurrence())
					.map(hop -> hop instanceof AggUnaryOp).orElse(false))
				.flatMap(candidate -> candidate.supportClause().inputBindings().stream())
				.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.DIRECT
					&& binding.source().rule().parentOccurrence() == exponential.key()));
	}

	@Test
	public void chainedDynamicReorgsRetainEndpointResidency() throws Exception {
		DMLProgram program = compile(CHAINED_DYNAMIC_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var reverse = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.REV)
			.findFirst().orElseThrow(AssertionError::new);
		var roll = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.ROLL)
			.findFirst().orElseThrow(AssertionError::new);
		var exponential = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.EXP)
			.findFirst().orElseThrow(AssertionError::new);

		var rollFact = analysis.candidateRuleFacts().requireExact(roll.key(),
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal()));
		Assert.assertTrue("ROLL must consume REV's dynamic native realization directly",
			rollFact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					!realization.nativeWorkerPoolLayoutExact(clause)
						&& clause.inputBindings().stream().anyMatch(binding ->
							binding.kind() == CandidateInputBindingKind.DIRECT
								&& binding.source().rule().parentOccurrence() == reverse.key()))));

		var expFact = analysis.candidateRuleFacts().requireExact(exponential.key(),
			List.of(CandidateInputState.present(FType.ROW)));
		Assert.assertTrue("EXP must retain ROLL's dynamic native realization",
			expFact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					!realization.nativeWorkerPoolLayoutExact(clause)
						&& clause.inputBindings().stream().anyMatch(binding ->
							binding.kind() == CandidateInputBindingKind.DIRECT
								&& binding.source().rule().parentOccurrence() == roll.key()))));

		var selected = new FedAllPlacementAdapter().select(analysis);
		Assert.assertTrue("FedAll must keep a legal exact assignment through REV -> ROLL -> EXP",
			selected.selectedCandidateSelections().stream()
				.anyMatch(candidate -> candidate.rule().parentOccurrence() == exponential.key()));
	}

	@Test
	public void mapCopyAfterDynamicFullRollRetainsSingleWorkerResidency() throws Exception {
		DMLProgram program = compile(FULL_ROLL_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var roll = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.ROLL)
			.findFirst().orElseThrow(AssertionError::new);
		var exponential = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.EXP)
			.findFirst().orElseThrow(AssertionError::new);

		var fact = analysis.candidateRuleFacts().requireExact(exponential.key(),
			List.of(CandidateInputState.present(FType.FULL)));
		Assert.assertTrue("EXP must consume FULL ROLL's dynamic single-worker realization directly",
			fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					!realization.nativeWorkerPoolLayoutExact(clause)
						&& clause.inputBindings().stream().anyMatch(binding ->
							binding.kind() == CandidateInputBindingKind.DIRECT
								&& binding.source().rule().parentOccurrence() == roll.key()))));

		var selected = new FedAllPlacementAdapter().select(analysis);
		Assert.assertTrue("FedAll must retain an exact legal assignment through FULL ROLL -> EXP",
			selected.selectedCandidateSelections().stream()
				.anyMatch(candidate -> candidate.rule().parentOccurrence() == exponential.key()));
	}

	@Test
	public void mapCopyAfterColRollRetainsDynamicNativeAuthority() throws Exception {
		DMLProgram program = compile(COL_ROLL_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var roll = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.ROLL)
			.findFirst().orElseThrow(AssertionError::new);
		var exponential = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.EXP)
			.findFirst().orElseThrow(AssertionError::new);

		var fact = analysis.candidateRuleFacts().requireExact(exponential.key(),
			List.of(CandidateInputState.present(FType.COL)));
		Assert.assertTrue("EXP must consume COL ROLL's dynamic native realization directly",
			fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					!realization.nativeWorkerPoolLayoutExact(clause)
						&& clause.inputBindings().stream().anyMatch(binding ->
							binding.kind() == CandidateInputBindingKind.DIRECT
								&& binding.source().rule().parentOccurrence() == roll.key()))));

		var selected = new FedAllPlacementAdapter().select(analysis);
		Assert.assertTrue("FedAll must retain an exact legal assignment through COL ROLL -> EXP",
			selected.selectedCandidateSelections().stream()
				.anyMatch(candidate -> candidate.rule().parentOccurrence() == exponential.key()));
	}

	@Test
	public void transientReplayPreservesDynamicReverseAuthority() throws Exception {
		DMLProgram program = compile(TRANSIENT_REVERSE_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var read = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "T".equals(data.getName()))
			.filter(occurrence -> !analysis.logicalTransientInputsForReader(occurrence.key(), 0).isEmpty())
			.findFirst().orElseThrow(() -> new AssertionError(
				"fixture must retain the transient read fed by rev(A)"));
		var relations = analysis.logicalTransientInputsForReader(read.key(), 0);
		var dynamicEdges = relations.stream().flatMap(relation -> relation.compatibility().stream())
			.filter(edge -> edge.proof().nativeWorkerPoolWitness() != null
				&& !edge.proof().nativeWorkerPoolLayoutExact()).toList();
		Assert.assertFalse("dynamic REV authority must survive TWrite/TRead replay", dynamicEdges.isEmpty());
		for(var edge : dynamicEdges) {
			var reader = analysis.requireExactCandidateRealization(edge.readerRealization());
			Assert.assertEquals(PlacementLayoutKind.NATIVE_LINEAGE, reader.key().layoutKind());
			Assert.assertTrue("dynamic replay reader must retain a typed endpoint witness",
				reader.supportClauses().stream().allMatch(clause ->
					reader.nativeWorkerPoolResidencyWitness(clause) != null
						&& !reader.nativeWorkerPoolLayoutExact(clause)
						&& PlacementIdentity.samePhysicalWorkerEndpoints(
							reader.nativeWorkerPoolResidencyWitness(clause),
							edge.proof().nativeWorkerPoolWitness())));
		}
		Assert.assertFalse("endpoint-only transient authority cannot publish stale durable ranges",
			relations.stream().flatMap(relation -> relation.compatibility().stream())
				.filter(edge -> !edge.proof().nativeWorkerPoolLayoutExact())
				.map(edge -> analysis.requireExactCandidateRealization(edge.readerRealization()))
				.anyMatch(reader -> reader.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP
					|| reader.supportClauses().stream().anyMatch(reader::nativeWorkerPoolLayoutExact)));

		var exponential = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.EXP)
			.findFirst().orElseThrow(AssertionError::new);
		var fact = analysis.candidateRuleFacts().requireExact(exponential.key(),
			List.of(CandidateInputState.present(FType.ROW)));
		Assert.assertTrue("downstream EXP must consume the dynamic transient reader directly",
			fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					!realization.nativeWorkerPoolLayoutExact(clause)
						&& clause.inputBindings().stream().anyMatch(binding ->
							binding.kind() == CandidateInputBindingKind.DIRECT
								&& binding.source().rule().parentOccurrence() == read.key()))));
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
}

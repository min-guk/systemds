/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Structural and placement contracts for compiler-owned branch-exit transfer sites. */
public class BranchPlacementNormalizationTest {
	private static final String FED = "F=federated(addresses=list(\"localhost:1234/X1\","
		+ "\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void missingElseGetsScopedPassThroughExitAndPreparationIsIdempotent() throws Exception {
		DMLProgram program = compile(FED + "Y=F;if(sum(F)>0){Y=F+1;}print(sum(Y));\n");
		IfStatement branch = onlyIf(program.getStatementBlocks());
		Assert.assertTrue(branch.getElseBody().isEmpty());

		BranchPlacementNormalization.prepare(program);
		assertOneExitSite(branch.getIfBody());
		assertOneExitSite(branch.getElseBody());
		int first = placementAliasCount(program);
		BranchPlacementNormalization.prepare(program);
		Assert.assertEquals("preparing one immutable search space twice must not duplicate sites",
			first, placementAliasCount(program));
		assertOneExitSite(branch.getIfBody());
		assertOneExitSite(branch.getElseBody());
	}

	@Test
	public void nestedLoopAndNonrecursiveFunctionBranchesArePreparedRecursively() throws Exception {
		DMLProgram program = compile("""
			f=function(matrix[double] A) return(matrix[double] B) {
			  B=A; i=1;
			  while(i<3) {
			    if(sum(B)>0) { if(i>1) { B=B+1; } else { B=B+2; } }
			    else { B=B-1; }
			    i=i+1;
			  }
			}
			""" + FED + "R=f(F);print(sum(R));\n");
		BranchPlacementNormalization.prepare(program);
		List<IfStatement> branches = allIfs(program);
		Assert.assertEquals("fixture must retain outer and nested branches", 2, branches.size());
		for(IfStatement branch : branches) {
			assertOneExitSite(branch.getIfBody());
			assertOneExitSite(branch.getElseBody());
		}
		Assert.assertTrue("prepared aliases must remain in the named nonrecursive function body",
			placementAliasCount(program) >= 4);
	}

	@Test
	public void branchExitTwTrCandidatesRemainCanonicalAndPrivacyFiltersLocalGet() throws Exception {
		String script = FED + "p=as.scalar(rand(rows=1,cols=1,seed=7));"
			+ "Y=F;if(p>0.5){Y=F+1;}Z=Y+1;print(sum(Z));\n";
		DMLProgram publicProgram = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(publicProgram, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(publicProgram);
		PlacementAnalysis publicAnalysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(publicProgram);
		List<Node> aliases = aliasNodes(publicAnalysis);
		Assert.assertFalse(aliases.isEmpty());
		Assert.assertTrue("public branch exits must expose a costed LOCAL alternative",
			aliases.stream().anyMatch(node -> node.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)));
		assertCanonicalCarriers(publicAnalysis);

		DMLProgram protectedProgram = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(protectedProgram,
			Privacy.PRIVATE_AGGREGATE);
		BranchPlacementNormalization.prepare(protectedProgram);
		PlacementAnalysis protectedAnalysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(protectedProgram);
		String protectedDiagnostics = aliasNodes(protectedAnalysis).stream().map(node ->
			node.key().emittedHopInstance() + " legal=" + node.legalAlternatives() + " facts="
				+ protectedAnalysis.candidateRuleFacts().orderedFactsForParent(node.key()).stream()
					.map(fact -> fact.status() + "/" + fact.failureCode() + "/"
						+ fact.allowedEmissionFacts().stream().map(emission ->
							emission.emissionState().normalizedSignature()).toList()).toList()
				+ " input=" + protectedAnalysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == node.key()).map(edge -> {
						CompiledHopKey read = edge.producer();
						return protectedAnalysis.hop(read).orElseThrow().getName() + '@'
							+ read.emittedHopInstance() + "/privacy=" + protectedAnalysis.requirePrivacy(read)
							+ "/logical=" + protectedAnalysis.logicalTransientInputsForReader(read, 0).stream()
								.map(fact -> protectedAnalysis.hop(fact.sourceWrite()).orElseThrow().getName()
									+ '@' + fact.sourceWrite().emittedHopInstance() + "/privacy="
									+ protectedAnalysis.requirePrivacy(fact.sourceWrite()) + "/version="
									+ fact.sourceValueVersion().normalizedSignature()).toList();
					}).toList()).toList() + " beforeIf=" + rootsBeforeFirstIf(protectedProgram);
		Assert.assertTrue("PRIVATE branch exits must not invent a coordinator materialization: "
			+ protectedDiagnostics,
			aliasNodes(protectedAnalysis).stream().flatMap(node -> node.legalAlternatives().stream())
				.noneMatch(state -> state.execType() == ExecType.CP
					&& state.output() == FederatedOutput.LOUT));
		assertCanonicalCarriers(protectedAnalysis);
	}

	private static List<String> rootsBeforeFirstIf(DMLProgram program) {
		List<String> result = new ArrayList<>();
		for(StatementBlock block : program.getStatementBlocks()) {
			if(block instanceof IfStatementBlock)
				break;
			if(block.getHops() != null)
				for(Hop root : block.getHops())
					result.add(root.getOpString() + ':' + root.getName() + '#' + root.getHopID());
		}
		return result;
	}

	@Test
	public void selectedBranchExitGetEmitsExactLocalReceiptWithoutReclassifyingSource() throws Exception {
		DMLProgram program = compile(FED + "p=as.scalar(rand(rows=1,cols=1,seed=7));"
			+ "Y=F;if(p>0.5){Y=F+1;}Z=Y+1;print(sum(Z));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ForcedLocalPlan forced = forceOneBranchExitGet(analysis);
		NormalizedPlannerResult plan = forced.plan();
		LocalMaterializationActionKey action = localActions(plan).stream()
			.filter(local -> local.sourceOccurrence() == forced.edge().producer())
			.filter(local -> local.obligations().stream().anyMatch(obligation ->
				obligation.consumerOccurrence() == forced.edge().consumer()
					&& obligation.inputPosition() == forced.edge().inputPosition()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"forced branch-exit GET must own the exact compiled consumer input"));
		Assert.assertEquals("the physical branch source must remain FED/FOUT in the selected map",
			forced.sourceState(), plan.selectedStates().get(forced.edge().producer()));

		FederatedLocalMaterializeRegistry.clear();
		PlacementEmissionTransaction.resetForTesting();
		try {
			PlacementEmissionTransaction.emit(program, plan,
				PlacementEmissionTransaction.FailureInjector.none());
			Hop source = analysis.hop(action.sourceOccurrence()).orElseThrow();
			Hop consumer = analysis.hop(forced.edge().consumer()).orElseThrow();
			Assert.assertTrue("emission must publish the selected source-to-alias input receipt",
				FederatedLocalMaterializeRegistry.snapshotAll().scopes().values().stream()
					.map(entries -> entries.get(source.getHopID()))
					.filter(java.util.Objects::nonNull)
					.anyMatch(spec -> spec.getConsumerInputs().stream().anyMatch(input ->
						input.consumerHopId() == consumer.getHopID()
							&& input.inputPosition() == forced.edge().inputPosition())));
			Assert.assertEquals("emission must not rewrite the source placement decision",
				forced.sourceState(), plan.selectedStates().get(forced.edge().producer()));
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	@Test
	public void localBranchExitDoesNotInventLocalMaterialization() throws Exception {
		DMLProgram program = compile("F=matrix(seq(1,8),rows=4,cols=2);p=1;"
			+ "Y=F;if(p>0){Y=F+1;}Z=Y+1;print(sum(Z));\n");
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(analysis, analysis.graph());
		Map<CompiledHopKey,PlacementEmissionState> emissions = NormalizedPlannerResults.exactEmissionStates(
			analysis, selected.assignment(), selected.selectedCandidateSelections());
		NormalizedPlannerResult plan = NormalizedPlannerResults.createWithEmissionStatesAndCandidateSelections(
			analysis, "local-branch-exit", emissions, selected.selectedCandidateSelections(),
			selected.selectedRelocationChoices(), "fixture");
		Assert.assertTrue("an all-local branch exit must not invent a FOUT-to-local action",
			localActions(plan).stream().noneMatch(action -> action.obligations().stream()
				.anyMatch(obligation -> BranchPlacementNormalization.isPlacementAlias(
					analysis.hop(obligation.consumerOccurrence()).orElseThrow()))));
	}

	@Test
	public void localBranchResultCanUploadAtExitWithoutWeakeningTransientCarrier() throws Exception {
		DMLProgram program = compile(FED + "p=as.scalar(rand(rows=1,cols=1,seed=7));Y=F;"
			+ "if(p>0.5){Y=matrix(seq(1,8),rows=4,cols=2);}else{Y=F;}"
			+ "Z=Y+F;print(sum(Z));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ForcedUploadPlan forced = forceOneBranchExitUpload(analysis);
		CandidateSelectionReceipt aliasCandidate = forced.plan().selectedCandidateSelections().stream()
			.filter(receipt -> receipt.rule().parentOccurrence() == forced.edge().consumer())
			.findFirst().orElseThrow();
		Assert.assertNotNull("the local branch result must have explicit graph-owned upload authority",
			aliasCandidate.emission().derivedFoutAction());
		var upload = aliasCandidate.emission().derivedFoutAction();
		Hop anchorOwner = analysis.hop(upload.durableAnchorOwner()).orElseThrow();
		Assert.assertTrue("the upload map must be owned by a branch-entry definition, not a sibling-arm object: "
			+ anchorOwner.getOpString() + ':' + anchorOwner.getName() + " region="
			+ upload.durableAnchorOwner().controlRegion().normalizedSignature(),
			anchorOwner instanceof DataOp
				&& !upload.durableAnchorOwner().controlRegion().normalizedSignature().contains("branch-"));
		Assert.assertTrue("the selected candidate set must price the explicit branch upload",
			CandidateSelections.foutMaterializationPhysicalEmissionCount(
				forced.plan().selectedCandidateSelections()) > 0);

		List<CompiledInputEdgeFact> carrierEdges = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == forced.edge().consumer())
			.filter(edge -> analysis.hop(edge.consumer()).orElseThrow() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE)
			.toList();
		Assert.assertFalse("the placement site must feed its canonical transient write", carrierEdges.isEmpty());
		for(CompiledInputEdgeFact edge : carrierEdges) {
			PlacementState state = forced.plan().selectedStates().get(edge.consumer());
			Assert.assertEquals(ExecType.FED, state.execType());
			Assert.assertEquals(FederatedOutput.FOUT, state.output());
		}
		assertCanonicalCarriers(analysis);

		FederatedFoutMaterializeRegistry.clear();
		PlacementEmissionTransaction.resetForTesting();
		try {
			PlacementEmissionTransaction.emit(program, forced.plan(),
				PlacementEmissionTransaction.FailureInjector.none());
			Hop alias = analysis.hop(forced.edge().consumer()).orElseThrow();
			long scope = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.key() == forced.edge().consumer())
				.mapToLong(HopOccurrenceProjection::scopeId).findFirst().orElseThrow();
			Assert.assertNotNull("emission must lower the selected CP/FOUT upload authority",
				FederatedFoutMaterializeRegistry.snapshot(scope).get(alias.getHopID()));
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			FederatedFoutMaterializeRegistry.clear();
		}
	}

	@Test
	public void publicLocalArmUploadsToProtectedBranchEntryMap() throws Exception {
		DMLProgram program = compile(FED.replaceFirst("F=", "X=")
			+ "flag=sum(X)>0;if(flag){Y=matrix(0,rows=nrow(X),cols=ncol(X));}else{Y=X;}"
			+ "Z=Y+X;print(sum(Z));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementGraphFingerprint.orderedOccurrences(program).stream().map(occurrence -> occurrence.hop())
			.filter(hop -> hop instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED
				&& "X".equals(data.getName()))
			.map(hop -> (DataOp)hop).forEach(source ->
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
					source, Privacy.PRIVATE_AGGREGATE));
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ForcedUploadPlan forced = forceOneBranchExitUpload(analysis);
		CandidateSelectionReceipt aliasCandidate = forced.plan().selectedCandidateSelections().stream()
			.filter(receipt -> receipt.rule().parentOccurrence() == forced.edge().consumer())
			.findFirst().orElseThrow();
		Assert.assertNotNull("the public local result must upload to the protected source's existing map",
			aliasCandidate.emission().derivedFoutAction());
		Assert.assertEquals("the joined transient carrier must stay on the worker pool",
			FederatedOutput.FOUT, forced.plan().selectedStates().entrySet().stream()
				.filter(entry -> analysis.hop(entry.getKey()).orElseThrow() instanceof DataOp data
					&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
					&& "Y".equals(data.getName())
					&& !entry.getKey().callSitePath().contains("branch-"))
				.map(Map.Entry::getValue).map(PlacementState::output).findFirst().orElseThrow());

		FederatedFoutMaterializeRegistry.clear();
		PlacementEmissionTransaction.resetForTesting();
		try {
			PlacementEmissionTransaction.emit(program, forced.plan(),
				PlacementEmissionTransaction.FailureInjector.none());
			Hop alias = analysis.hop(forced.edge().consumer()).orElseThrow();
			long scope = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.key() == forced.edge().consumer())
				.mapToLong(HopOccurrenceProjection::scopeId).findFirst().orElseThrow();
			Assert.assertNotNull("the selected public-arm upload must lower to an exact map receipt",
				FederatedFoutMaterializeRegistry.snapshot(scope).get(alias.getHopID()));
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			FederatedFoutMaterializeRegistry.clear();
		}
	}

	@Test
	public void singleWorkerFullUploadRetainsPrivateAggregateConsumer() throws Exception {
		String full = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(8,3)));\n";
		DMLProgram program = compile(full
			+ "flag=sum(X)>0;if(flag){Y=matrix(0,rows=nrow(X),cols=ncol(X));}else{Y=X;}"
			+ "Z=Y+X;print(sum(Z));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementGraphFingerprint.orderedOccurrences(program).stream().map(occurrence -> occurrence.hop())
			.filter(hop -> hop instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED
				&& "X".equals(data.getName()))
			.map(hop -> (DataOp)hop).forEach(source ->
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
					source, Privacy.PRIVATE_AGGREGATE));
		BranchPlacementNormalization.prepare(program);

		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		CompiledHopKey consumer = analysis.occurrences().stream()
			.filter(occurrence -> "Z".equals(occurrence.hop().getName()))
			.map(HopOccurrenceProjection::key).findFirst().orElseThrow();
		var exactFull = analysis.candidateRuleFacts().orderedFactsForParent(consumer).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.key().orderedInputs().size() == 2
				&& fact.key().orderedInputs().stream().allMatch(input -> input.present()
					&& input.fType() == org.apache.sysds.hops.fedplanner.FTypes.FType.FULL))
			.filter(fact -> "true".equals(fact.shapeProof().consultedFacts()
				.get("fullSinglePartition")))
			.findFirst().orElseThrow();
		Assert.assertTrue("the exact FULL/FULL row must retain native origin-resident execution",
			exactFull.allowedEmissionFacts().stream().anyMatch(emission ->
					emission.derivedFoutAction() == null
					&& emission.emissionState().placementState().execType() == ExecType.FED
					&& emission.emissionState().placementState().output() == FederatedOutput.FOUT));
		Assert.assertTrue("the local branch must expose an explicit one-worker FULL upload",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> BranchPlacementNormalization.isPlacementAlias(
					analysis.hop(fact.key().parentOccurrence()).orElseThrow()))
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.derivedFoutAction() != null
					&& emission.derivedFoutAction().durableAnchor().partitions().size() == 1
					&& emission.emissionState().placementState().output() == FederatedOutput.FOUT));

		ForcedUploadPlan forced = forceOneBranchExitUpload(analysis);
		Assert.assertEquals("the protected consumer must execute at the selected worker pool",
			new PlacementState(ExecType.FED, FederatedOutput.FOUT,
				org.apache.sysds.hops.fedplanner.FTypes.FType.FULL, true),
			forced.plan().selectedStates().get(consumer));
		FederatedFoutMaterializeRegistry.clear();
		PlacementEmissionTransaction.resetForTesting();
		try {
			PlacementEmissionTransaction.emit(program, forced.plan(),
				PlacementEmissionTransaction.FailureInjector.none());
			Hop alias = analysis.hop(forced.edge().consumer()).orElseThrow();
			long scope = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.key() == forced.edge().consumer())
				.mapToLong(HopOccurrenceProjection::scopeId).findFirst().orElseThrow();
			Assert.assertNotNull("the selected FULL upload must lower to the branch-exit alias",
				FederatedFoutMaterializeRegistry.snapshot(scope).get(alias.getHopID()));
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			FederatedFoutMaterializeRegistry.clear();
		}
	}

	@Test
	public void mixedSingleAndMultiPartitionBranchesDoNotProveFullSinglePartition() throws Exception {
		String sources = "S=federated(addresses=list(\"localhost:1234/S\"),"
			+ "ranges=list(list(0,0),list(8,3)));"
			+ "M=federated(addresses=list(\"localhost:1234/M1\",\"localhost:1235/M2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));";
		DMLProgram program = compile(sources
			+ "flag=as.scalar(rand(rows=1,cols=1,seed=7))>0.5;"
			+ "if(flag){Y=S;}else{Y=M;}Z=Y+Y;print(sum(Z));");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		Assert.assertFalse("an unavailable branch-support reference cannot hide a selectable multi-partition arm",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> analysis.hop(fact.key().parentOccurrence()).orElseThrow()
					instanceof org.apache.sysds.hops.BinaryOp)
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.key().orderedInputs().size() == 2
					&& fact.key().orderedInputs().stream().allMatch(input -> input.present()
						&& input.fType() == org.apache.sysds.hops.fedplanner.FTypes.FType.FULL))
				.anyMatch(fact -> "true".equals(fact.shapeProof().consultedFacts()
					.get("fullSinglePartition"))));
	}

	@Test
	public void localArmCannotBorrowMapCreatedOnlyInsideSiblingArm() throws Exception {
		String branchFed = "G=federated(addresses=list(\"localhost:1234/X1\","
			+ "\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
		DMLProgram program = compile(FED.replaceFirst("F=", "X=")
			+ "p=as.scalar(rand(rows=1,cols=1,seed=7));"
			+ "Y=matrix(seq(1,8),rows=4,cols=2);if(p>0.5){Y=Y+1;}else{"
			+ branchFed + "Y=G;}print(sum(Y));print(sum(X));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementGraphFingerprint.orderedOccurrences(program).stream().map(occurrence -> occurrence.hop())
			.filter(hop -> hop instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED
				&& "X".equals(data.getName()))
			.map(hop -> (DataOp)hop).forEach(source ->
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
					source, Privacy.PRIVATE_AGGREGATE));
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		List<Node> localExitAliases = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> BranchPlacementNormalization.isPlacementAlias(
				analysis.hop(edge.consumer()).orElseThrow()))
			.filter(edge -> analysis.graph().node(edge.producer()).orElseThrow().legalAlternatives().stream()
				.noneMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT))
			.map(edge -> analysis.graph().node(edge.consumer()).orElseThrow()).toList();
		Assert.assertFalse("fixture must retain a genuinely local branch exit", localExitAliases.isEmpty());
		for(Node alias : localExitAliases) {
			Assert.assertTrue("a nonexecuted sibling's map cannot authorize a local-arm upload",
				alias.legalAlternatives().stream().noneMatch(state ->
					state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT));
			Assert.assertTrue(analysis.candidateRuleFacts().orderedFactsForParent(alias.key()).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.noneMatch(emission -> emission.derivedFoutAction() != null));
		}
	}

	@Test
	public void nestedBranchRetainsEachDistinctUploadTargetAsAnExactAction() throws Exception {
		String sources = "X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";
		DMLProgram program = compile(sources
			+ "if(p>0.5){if(q>0.5){A=X;B=X;}else{A=Y;B=Y;}}else{A=X;B=X;}"
			+ "C=A+B;print(sum(C));");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);

		List<PlacementAnalysis.CandidateEmissionFact> twoPools = aliasNodes(analysis).stream()
			.flatMap(alias -> analysis.candidateRuleFacts().orderedFactsForParent(alias.key()).stream())
			.map(fact -> fact.allowedEmissionFacts().stream()
				.filter(emission -> emission.derivedFoutAction() != null)
				.collect(java.util.stream.Collectors.groupingBy(
					PlacementAnalysis.CandidateEmissionFact::emissionState)))
			.flatMap(byState -> byState.values().stream())
			.filter(group -> group.stream().map(emission ->
				emission.derivedFoutAction().durableAnchor()).distinct().count() >= 2)
			.findFirst().orElseThrow(() -> new AssertionError(
				"nested exit must retain each real upload worker pool"));
		Set<String> targets = twoPools.stream().map(emission ->
			emission.derivedFoutAction().durableAnchor().placementId()).collect(
				java.util.stream.Collectors.toSet());
		Assert.assertTrue("both upload pools must remain independently selectable: " + targets,
			targets.size() >= 2);
		Assert.assertEquals("each upload target needs a distinct realization reference",
			twoPools.size(), twoPools.stream().flatMap(emission -> emission.realizations().stream())
				.map(PlacementAnalysis.CandidateEmissionRealization::key).distinct().count());
		for(PlacementAnalysis.CandidateEmissionFact emission : twoPools) {
			var action = emission.derivedFoutAction();
			Assert.assertTrue("every alternative must retain its exact graph-owned action",
				analysis.graph().derivedFoutMaterializationActions().stream()
					.anyMatch(candidate -> candidate.key() == action));
			Hop owner = analysis.hop(action.durableAnchorOwner()).orElseThrow();
			if(action.durableAnchor().placementId().startsWith("fed-init:"))
				Assert.assertTrue("each branch-entry upload target must retain its exact pre-branch owner",
					owner instanceof DataOp && !action.durableAnchorOwner().controlRegion()
						.normalizedSignature().contains("branch-"));
		}
	}

	@Test
	public void fedLoutToFoutRetainsEverySameFTypeConcretePool() throws Exception {
		String sources = "X=federated(addresses=list(\"localhost:24334/X1\",\"localhost:24335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:24336/Y1\",\"localhost:24337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";
		DMLProgram program = compile(sources
			+ "if(p>0.5){if(q>0.5){A=X;B=X;}else{A=Y;B=Y;}}else{A=X;B=X;}"
			+ "C=A+B;print(sum(C));");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);

		CandidateRuleFact original = aliasNodes(analysis).stream()
			.flatMap(alias -> analysis.candidateRuleFacts().orderedFactsForParent(alias.key()).stream())
			.filter(fact -> fact.allowedEmissionFacts().stream()
				.filter(emission -> emission.derivedFoutAction() != null
					&& emission.emissionState().placementState().fType() == FType.ROW)
				.map(emission -> emission.derivedFoutAction().durableAnchor()).distinct().count() >= 2)
			.findFirst().orElseThrow(() -> new AssertionError(
				"fixture must expose two concrete ROW upload pools"));
		Node originalNode = analysis.graph().node(original.key().parentOccurrence()).orElseThrow();
		PlacementState fedLout = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		List<PlacementState> legal = originalNode.legalAlternatives().stream()
			.filter(state -> state.execType() != ExecType.CP
				|| state.output() != FederatedOutput.FOUT)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		if(!legal.contains(fedLout))
			legal.add(fedLout);
		Node raw = new Node(originalNode.key(), originalNode.kind(), originalNode.valueVersion(), true,
			legal.stream().distinct().sorted().toList(), originalNode.exclusions(), originalNode.anchors());
		CandidateRuleFact nativeLout = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			List.of(new CandidateEmissionFact(new PlacementEmissionState(fedLout, false), FType.ROW)),
			original.failureCode());

		MaterializationFixture fixture = materializationInventory(analysis);
		Object inventory = fixture.inventory();
		Method close = PlacementRelationClosure.class.getDeclaredMethod(
			"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class,
			inventory.getClass());
		close.setAccessible(true);
		Object closed = close.invoke(materializationClosure(fixture.origins(), fixture.shapes()),
			List.of(raw), List.of(nativeLout), inventory);
		Method facts = closed.getClass().getDeclaredMethod("ruleFacts");
		facts.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> closedFacts = (List<CandidateRuleFact>)facts.invoke(closed);
		List<CandidateEmissionFact> derived = closedFacts.get(0).allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().derivedFedFout()
				&& emission.emissionState().placementState().execType() == ExecType.FED
				&& emission.emissionState().placementState().output() == FederatedOutput.FOUT
				&& emission.emissionState().placementState().fType() == FType.ROW)
			.toList();
		Assert.assertTrue("each same-FType concrete pool must retain a separate FED/LOUT -> FOUT action: "
			+ derived.stream().map(CandidateEmissionFact::selectionSignature).toList(),
			derived.stream().map(emission -> emission.derivedFoutAction().durableAnchor())
				.distinct().count() >= 2);
		Assert.assertEquals("action-aware emission identity must keep every alternative canonical",
			derived.size(), derived.stream().map(CandidateEmissionFact::selectionSignature).distinct().count());
	}

	private record MaterializationFixture(Object inventory, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) { }

	private static MaterializationFixture materializationInventory(PlacementAnalysis analysis) throws Exception {
		List<Node> nodes = analysis.graph().nodes();
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		for(Node node : nodes) {
			Hop hop = analysis.hop(node.key()).orElseThrow();
			origins.put(node.key(), hop);
			shapes.put(hop, analysis.shapeFact(node.key()).orElseThrow());
		}
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName()
			+ "$CommittedProofInventory");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class, List.class,
			List.class, List.class, Collection.class, Map.class, Map.class);
		constructor.setAccessible(true);
		Object inventory = constructor.newInstance(nodes, analysis.candidateRuleFacts().orderedFacts(),
			analysis.compiledInputEdgesInCanonicalOrder(),
			analysis.logicalTransientInputsInCanonicalOrder(), analysis.graph().constraints(), origins, shapes);
		return new MaterializationFixture(inventory, origins, shapes);
	}

	private static Object materializationClosure(Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		Constructor<?> constructor = java.util.Arrays.stream(
			PlacementRelationClosure.class.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == 6).findFirst().orElseThrow();
		constructor.setAccessible(true);
		Object closure = constructor.newInstance(null, null, null, false, null, false);
		for(Map.Entry<String,Object> field : Map.<String,Object>of(
			"origins", origins, "shapeFactsByHop", shapes).entrySet()) {
			java.lang.reflect.Field target = PlacementRelationClosure.class.getDeclaredField(field.getKey());
			target.setAccessible(true);
			target.set(closure, field.getValue());
		}
		return closure;
	}

	private static ForcedLocalPlan forceOneBranchExitGet(PlacementAnalysis analysis) {
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(!BranchPlacementNormalization.isPlacementAlias(analysis.hop(edge.consumer()).orElseThrow()))
				continue;
			Node source = analysis.graph().node(edge.producer()).orElseThrow();
			Node alias = analysis.graph().node(edge.consumer()).orElseThrow();
			for(PlacementState sourceState : source.legalAlternatives()) {
				if(sourceState.execType() != ExecType.FED || sourceState.output() != FederatedOutput.FOUT)
					continue;
				for(PlacementState aliasState : alias.legalAlternatives()) {
					if(aliasState.execType() != ExecType.CP || aliasState.output() != FederatedOutput.LOUT)
						continue;
					try {
						NeutralPlacementGraph pinned = pin(analysis.graph(), Map.of(
							source, sourceState, alias, aliasState));
						PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(analysis, pinned);
						Map<CompiledHopKey,PlacementEmissionState> emissions = NormalizedPlannerResults.exactEmissionStates(
							analysis, selected.assignment(), selected.selectedCandidateSelections());
						NormalizedPlannerResult plan = NormalizedPlannerResults
							.createWithEmissionStatesAndCandidateSelections(analysis, "forced-branch-exit-local",
								emissions, selected.selectedCandidateSelections(),
								selected.selectedRelocationChoices(), "fixture");
						if(localActions(plan).stream().anyMatch(action ->
							action.sourceOccurrence() == edge.producer()
								&& action.obligations().stream().anyMatch(obligation ->
									obligation.consumerOccurrence() == edge.consumer()
										&& obligation.inputPosition() == edge.inputPosition())))
							return new ForcedLocalPlan(edge, sourceState, plan);
					}
					catch(IllegalArgumentException | IllegalStateException ignored) {
						// Try the next graph-owned branch edge/state pair.
					}
				}
			}
		}
		throw new AssertionError("fixture must expose one feasible explicit branch-exit GET");
	}

	private static ForcedUploadPlan forceOneBranchExitUpload(PlacementAnalysis analysis) {
		List<String> diagnostics = new ArrayList<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(!BranchPlacementNormalization.isPlacementAlias(analysis.hop(edge.consumer()).orElseThrow()))
				continue;
			Node source = analysis.graph().node(edge.producer()).orElseThrow();
			Node alias = analysis.graph().node(edge.consumer()).orElseThrow();
			if(source.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT))
				continue; // exercise the genuinely local arm, not a downloaded sibling source
			Node carrier = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(next -> next.producer() == edge.consumer())
				.filter(next -> analysis.hop(next.consumer()).orElseThrow() instanceof DataOp data
					&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE)
				.map(next -> analysis.graph().node(next.consumer()).orElseThrow()).findFirst().orElse(null);
			PlacementState federatedCarrier = carrier == null ? null : carrier.legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)
				.findFirst().orElse(null);
			diagnostics.add("edge=" + edge.producer().emittedHopInstance() + "->"
				+ edge.consumer().emittedHopInstance() + " source=" + source.legalAlternatives()
				+ " alias=" + alias.legalAlternatives() + " carrier="
				+ (carrier == null ? "missing" : carrier.legalAlternatives()));
			if(federatedCarrier == null)
				continue;
			for(PlacementState sourceState : source.legalAlternatives()) {
				if(sourceState.execType() != ExecType.CP || sourceState.output() != FederatedOutput.LOUT)
					continue;
				for(PlacementState aliasState : alias.legalAlternatives()) {
					if(aliasState.execType() != ExecType.CP || aliasState.output() != FederatedOutput.FOUT)
						continue;
					try {
						NeutralPlacementGraph pinned = pin(analysis.graph(), Map.of(
							source, sourceState, alias, aliasState, carrier, federatedCarrier));
						PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(analysis, pinned);
						Map<CompiledHopKey,PlacementEmissionState> emissions = NormalizedPlannerResults.exactEmissionStates(
							analysis, selected.assignment(), selected.selectedCandidateSelections());
						NormalizedPlannerResult plan = NormalizedPlannerResults
							.createWithEmissionStatesAndCandidateSelections(analysis, "forced-branch-exit-upload",
								emissions, selected.selectedCandidateSelections(),
								selected.selectedRelocationChoices(), "fixture");
						if(plan.selectedCandidateSelections().stream().anyMatch(receipt ->
							receipt.rule().parentOccurrence() == edge.consumer()
								&& receipt.emission().derivedFoutAction() != null))
							return new ForcedUploadPlan(edge, plan);
					}
					catch(IllegalArgumentException | IllegalStateException ignored) {
						diagnostics.add(sourceState.normalizedSignature() + "->"
							+ aliasState.normalizedSignature() + " failed=" + ignored.getMessage());
						// Try the next graph-owned branch edge/state pair.
					}
				}
			}
		}
		throw new AssertionError("fixture must expose one feasible explicit branch-exit upload: "
			+ diagnostics);
	}

	private static List<LocalMaterializationActionKey> localActions(NormalizedPlannerResult plan) {
		List<LocalMaterializationActionKey> result = new ArrayList<>();
		for(Object action : plan.selectedLocalMaterializations())
			result.add((LocalMaterializationActionKey)action);
		return result;
	}

	private static NeutralPlacementGraph pin(NeutralPlacementGraph graph,
		Map<Node,PlacementState> selections) {
		List<Constraint> constraints = new ArrayList<>(graph.constraints());
		for(Map.Entry<Node,PlacementState> pin : selections.entrySet())
			for(PlacementState rejected : pin.getKey().legalAlternatives())
				if(!rejected.equals(pin.getValue()))
					constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE,
						pin.getKey().key(), pin.getKey().key(), -1, "forbid-pair:"
							+ rejected.normalizedSignature() + "=>" + rejected.normalizedSignature()));
		return new NeutralPlacementGraph(graph.nodes(), constraints,
			graph.relocationActions(), graph.derivedFoutMaterializationActions());
	}

	private record ForcedLocalPlan(CompiledInputEdgeFact edge, PlacementState sourceState,
		NormalizedPlannerResult plan) {
	}

	private record ForcedUploadPlan(CompiledInputEdgeFact edge, NormalizedPlannerResult plan) {
	}

	private static void assertCanonicalCarriers(PlacementAnalysis analysis) {
		for(Node node : analysis.graph().decisionNodes()) {
			Hop hop = analysis.hop(node.key()).orElseThrow();
			if(!(hop instanceof DataOp))
				continue;
			if(hop instanceof DataOp data && data.getOp() != org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& data.getOp() != org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE)
				continue;
			Assert.assertTrue("branch carrier must remain CP/LOUT or FED/FOUT: " + node.key(),
				node.legalAlternatives().stream().allMatch(state ->
					state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
						|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
		}
	}

	private static List<Node> aliasNodes(PlacementAnalysis analysis) {
		return analysis.graph().decisionNodes().stream()
			.filter(node -> BranchPlacementNormalization.isPlacementAlias(
				analysis.hop(node.key()).orElseThrow())).toList();
	}

	private static void assertOneExitSite(ArrayList<StatementBlock> body) {
		Assert.assertFalse("branch arm must contain a compiler-owned exit site", body.isEmpty());
		StatementBlock exit = body.get(body.size() - 1);
		Assert.assertNotNull(exit.getHops());
		Assert.assertFalse(exit.getHops().isEmpty());
		Assert.assertTrue(exit.getHops().stream().allMatch(root -> root instanceof DataOp
			&& root.getInput().size() == 1 && BranchPlacementNormalization.isPlacementAlias(root.getInput(0))));
	}

	private static IfStatement onlyIf(List<StatementBlock> blocks) {
		List<IfStatement> branches = new ArrayList<>();
		collectIfs(blocks, branches);
		Assert.assertEquals(1, branches.size());
		return branches.get(0);
	}

	private static List<IfStatement> allIfs(DMLProgram program) {
		List<IfStatement> result = new ArrayList<>();
		collectIfs(program.getStatementBlocks(), result);
		for(FunctionStatementBlock function : program.getNamedNSFunctionStatementBlocks().values())
			collectIfs(List.of(function), result);
		return result;
	}

	private static void collectIfs(List<StatementBlock> blocks, List<IfStatement> result) {
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock branch) {
				IfStatement statement = (IfStatement)branch.getStatement(0);
				result.add(statement);
				collectIfs(statement.getIfBody(), result);
				collectIfs(statement.getElseBody(), result);
			}
			else if(block instanceof WhileStatementBlock loop)
				collectIfs(((WhileStatement)loop.getStatement(0)).getBody(), result);
			else if(block instanceof ForStatementBlock loop)
				collectIfs(((ForStatement)loop.getStatement(0)).getBody(), result);
			else if(block instanceof FunctionStatementBlock function)
				collectIfs(((FunctionStatement)function.getStatement(0)).getBody(), result);
		}
	}

	private static int placementAliasCount(DMLProgram program) {
		return (int)PlacementGraphFingerprint.orderedOccurrences(program).stream()
			.filter(occurrence -> BranchPlacementNormalization.isPlacementAlias(occurrence.hop())).count();
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

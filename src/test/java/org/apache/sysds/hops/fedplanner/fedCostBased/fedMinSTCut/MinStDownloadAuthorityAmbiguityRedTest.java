/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact physical contract preventing upload authority from shadowing download authority. */
public class MinStDownloadAuthorityAmbiguityRedTest {
	@Test
	public void downloadIsCostedFromDurableFoutSourceAndNeverEncodedAsRelocationDemand() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(pca());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);

		DownloadCase exact = downloadCase(analysis, model, surface);
		var assignment = new IdentityHashMap<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,
			org.apache.sysds.hops.fedplanner.placement.PlacementState>();
		assignment.put(exact.endpoint().producer(), exact.sourceState());
		assignment.put(exact.endpoint().consumer(), exact.consumerState());
		Assert.assertTrue("MINST_DOWNLOAD_DURABLE_SOURCE_MUST_DEACTIVATE_UPLOAD_ALTERNATIVES",
			exact.competingRelocations().stream().noneMatch(action ->
				analysis.graph().isRelocationActive(action, assignment)));

		MinStExactPhysicalSelection selected = MinStExactPhysicalSelection.create(model,
			MinStExactPhysicalOptimizer.optimize(model, surface,
				MinStExactPhysicalOptimizer.PRODUCTION_LIMITS));
		MinStExactPhysicalPlacementProjector.project(selected);
	}

	private static DownloadCase downloadCase(PlacementAnalysis analysis,
		MinStExactPhysicalModel model, MinStExactCostFactsProducer.PhysicalCostSurface surface) {
		for(var key : surface.transferKeys()) {
			if(key.direction() != Direction.DOWNLOAD)
				continue;
			for(var endpoint : key.endpoints()) {
				var sourceNode = analysis.graph().node(endpoint.producer()).orElseThrow();
				if(sourceNode.anchors().stream().noneMatch(anchor -> anchor.fType() == key.fType()))
					continue;
				var sourceDomain = model.domains().stream()
					.filter(domain -> domain.node().key() == endpoint.producer()).findFirst().orElse(null);
				var consumerDomain = model.domains().stream()
					.filter(domain -> domain.node().key() == endpoint.consumer()).findFirst().orElse(null);
				if(sourceDomain == null || consumerDomain == null)
					continue;
				var sourceState = sourceDomain.alternatives().stream().map(alternative -> alternative.state())
					.filter(state -> state.output() == FederatedOutput.FOUT && state.fType() == key.fType())
					.findFirst().orElse(null);
				var consumerState = consumerDomain.alternatives().stream().map(alternative -> alternative.state())
					.filter(state -> state.execType() == ExecType.CP).findFirst().orElse(null);
				if(sourceState == null || consumerState == null)
					continue;
				var competing = analysis.graph().relocationActions().stream().filter(action ->
					action.key().sourceValueVersion().equals(key.sourceValueVersion())
						&& action.obligations().stream().anyMatch(obligation ->
							obligation.consumer() == endpoint.consumer()
								&& obligation.inputPosition() == endpoint.inputPosition())).toList();
				if(!competing.isEmpty())
					return new DownloadCase(endpoint, sourceState, consumerState, competing);
			}
		}
		throw new AssertionError("MINST_EXACT_DURABLE_DOWNLOAD_WITH_COMPETING_UPLOAD_MISSING");
	}

	@Test
	public void uploadRetainsExactRelocationActionWithoutFallback() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(kmeans());
		ForcedRelocation forced = forceOneRelocation(analysis);
		MinStExactPhysicalSelection selected = forced.selection();
		Assert.assertTrue("MINST_EXACT_CANDIDATES_MUST_NOT_REINTRODUCE_FALLBACK",
			selected.candidateReceipts().stream().allMatch(receipt ->
				receipt.fallbackMaterializations().isEmpty()));
		var matching = selected.relocationChoices().stream()
			.filter(choice -> choice.action().equals(forced.action())).toList();
		Assert.assertFalse("MINST_EXACT_RELOCATION_ACTION_RECEIPT_MISSING", matching.isEmpty());
		Assert.assertEquals("MINST_SHARED_RELOCATION_ACTION_EMITTED_ONCE", 1L,
			selected.emittedRelocations().stream().filter(forced.action()::equals).count());
		var resolved = RelocationSelections.resolveAndValidate(analysis,
			analysis.graph().relocationActions(), selected.selectedStates(),
			selected.candidateReceipts(), selected.relocationChoices());
		Assert.assertTrue("MINST_EXACT_RELOCATION_ACTION_MUST_RESOLVE",
			resolved.stream().anyMatch(choice -> choice.receipt().action().equals(forced.action())));
	}

	private static ForcedRelocation forceOneRelocation(PlacementAnalysis analysis) {
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		for(var domain : model.domains())
			for(int value = 0; value < domain.alternatives().size(); value++) {
				var relocation = domain.alternatives().get(value).inputAuthorities().stream()
					.filter(authority -> authority.kind() == InputAuthorityKind.RELOCATION)
					.findFirst().orElse(null);
				if(relocation == null)
					continue;
				int required = value;
				List<MinStExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
				factors.addAll(surface.factors());
				factors.add(MinStExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
					values -> values[0] == required ? 0.0 : Double.POSITIVE_INFINITY));
				try {
					var solved = MinStExactCategoricalSolver.solve(model.variables(), factors,
						MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
					long objective = surface.evaluateCanonical(solved.assignmentInVariableOrder());
					var selected = MinStExactPhysicalSelection.create(model,
						new MinStExactPhysicalOptimizer.Result(solved, objective,
							surface.contributionFingerprint()));
					if(selected.relocationChoices().stream().anyMatch(choice ->
						choice.action().equals(relocation.relocationAction().key())))
						return new ForcedRelocation(selected, relocation.relocationAction().key());
				}
				catch(IllegalArgumentException infeasible) {
					if(!infeasible.getMessage().startsWith("MINST_VE_NO_FEASIBLE_ASSIGNMENT"))
						throw infeasible;
				}
			}
		throw new AssertionError("MINST_EXACT_RELOCATION_ALTERNATIVE_MISSING");
	}

	private static DMLProgram pca() throws Exception {
		return compile(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));",
			"[PC,V]=pca(X=X,K=5,scale=TRUE,center=TRUE);",
			"write(PC,\"out\",format=\"binary\");") + "\n");
	}

	private static DMLProgram kmeans() throws Exception {
		return compile(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n");
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

	private record ForcedRelocation(MinStExactPhysicalSelection selection,
		RelocationActionKey action) { }
	private record DownloadCase(MinStExactCostFactsProducer.PhysicalTransferEndpoint endpoint,
		org.apache.sysds.hops.fedplanner.placement.PlacementState sourceState,
		org.apache.sysds.hops.fedplanner.placement.PlacementState consumerState,
		List<org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction>
			competingRelocations) { }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.BoundaryMode;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Production-shape PCA guards for exact physical authority and TWrite transfer pricing. */
public class MinStPcaAuthorityClosureAndTWriteMetadataTest {
	@Test
	public void oneWorkerPcaUsesEffectiveUnknownDimTransferEstimate() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileHarnessShapePca());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		MinStExactPhysicalSelection selected = optimize(model, surface);

		var covarianceDomain = model.domains().stream().filter(candidate ->
			candidate.node().key().normalizedSignature().contains(
				"pca.dml:89:16:org.apache.sysds.hops.AggBinaryOp:ba(+*):compiler-temp"))
			.findFirst().orElseThrow();
		var projectionDomain = model.domains().stream().filter(candidate ->
			candidate.node().key().normalizedSignature().contains(
				"pca.dml:110:21:org.apache.sysds.hops.AggBinaryOp:ba(+*):XReduced"))
			.findFirst().orElseThrow();
		var componentsReadDomain = model.domains().stream().filter(candidate ->
			candidate.node().key().normalizedSignature().contains(
				"pca.dml:110:21:org.apache.sysds.hops.DataOp:TRead Components:Components"))
			.findFirst().orElseThrow();
		var componentsRead = analysis.hop(componentsReadDomain.node().key()).orElseThrow();
		Assert.assertTrue("PCA_COMPONENTS_UNKNOWN_DIM_ESTIMATE_MUST_BE_CLAMPED",
			FederatedCostModel.getEffectiveOutputMemEstimate(componentsRead)
				< componentsRead.getOutputMemEstimate());
		Assert.assertEquals("PCA_ONE_WORKER_COVARIANCE_TSMM_MUST_STAY_FEDERATED",
			ExecType.FED, selected.selectedStates().get(covarianceDomain.node().key()).execType());
		Assert.assertEquals("PCA_ONE_WORKER_PROJECTION_MUST_BROADCAST_SMALL_COMPONENTS",
			ExecType.FED, selected.selectedStates().get(projectionDomain.node().key()).execType());
	}

	@Test
	public void pcaGroundsEveryFedContainsAlternativeAndPricesTWriteAsMetadata() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compilePca());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);

		CompiledInputEdgeFact containsInput = uniqueEdge(analysis,
			"AggBinaryOp:ba(+*):XReduced", "ParameterizedBuiltinOp:CONTAINS:containsInf");
		var containsDomain = domain(model, containsInput.consumer());
		List<MinStExactPhysicalModel.Alternative> federated = containsDomain.alternatives().stream()
			.filter(alternative -> alternative.state().execType() == ExecType.FED).toList();
		Assert.assertFalse("PCA_CONTAINS_FED_ALTERNATIVE_EXPECTED", federated.isEmpty());
		for(var alternative : federated) {
			Assert.assertNotEquals("PCA_CONTAINS_FED_MUST_NOT_BE_UNGROUNDED_SINGLETON",
				AuthorityKind.LEGAL_SINGLETON, alternative.authorityKind());
			Assert.assertFalse("PCA_CONTAINS_FED_MUST_RETAIN_RUNTIME_INPUT_ROW",
				alternative.orderedInputs().isEmpty());
			for(int position = 0; position < alternative.orderedInputs().size(); position++) {
				if(!alternative.orderedInputs().get(position).present())
					continue;
				int inputPosition = position;
				Assert.assertEquals("PCA_CONTAINS_PRESENT_INPUT_HAS_ONE_EXACT_AUTHORITY|input=" + position,
					1L, alternative.inputAuthorities().stream()
						.filter(authority -> authority.inputPosition() == inputPosition).count());
			}
		}

		CompiledInputEdgeFact tWriteInput = uniqueEdge(analysis,
			"BinaryOp:b(/):X", "DataOp:TWrite X:X");
		var sourceVersion = analysis.graph().node(tWriteInput.producer()).orElseThrow().valueVersion();
		Assert.assertTrue("PCA_TWRITE_TRANSFER_KEY_MUST_USE_METADATA_BOUNDARY|producer="
			+ sourceVersion.normalizedSignature(),
			surface.transferKeys().stream().anyMatch(key ->
				key.sourceValueVersion().equals(sourceVersion)
					&& key.direction() == Direction.UPLOAD
					&& key.boundaryMode() == BoundaryMode.TWRITE_METADATA
					&& key.endpoints().stream().anyMatch(endpoint ->
						endpoint.producer() == tWriteInput.producer()
							&& endpoint.consumer() == tWriteInput.consumer()
							&& endpoint.inputPosition() == tWriteInput.inputPosition())));

		MinStExactPhysicalSelection selected = optimize(model, surface);
		MinStExactPhysicalPlacementProjector.project(selected);
		var tWriteState = selected.selectedStates().get(tWriteInput.consumer());
		Assert.assertTrue("PCA_TWRITE_STRICT_CP_LOUT_OR_FED_FOUT",
			tWriteState.execType() == ExecType.CP && tWriteState.output() == FederatedOutput.LOUT
				|| tWriteState.execType() == ExecType.FED && tWriteState.output() == FederatedOutput.FOUT);
	}

	@Test
	public void pcaWrittenSecondOutputReusesExactFoutProducerWithoutUpload() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileHarnessShapePca());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		MinStExactPhysicalSelection selected = optimize(model, surface);
		MinStExactPhysicalPlacementProjector.project(selected);

		CompiledInputEdgeFact input = uniqueEdge(analysis,
			"AggUnaryOp:ua(meanC):Centering", "ParameterizedBuiltinOp:REPLACE:Centering");
		var producerState = selected.selectedStates().get(input.producer());
		Assert.assertEquals("PCA_MEANC_SELECTED_FOUT", FederatedOutput.FOUT, producerState.output());
		var consumerAlternative = selected.alternativesInDecisionOrder().stream()
			.filter(alternative -> alternative.decision() == input.consumer()).findFirst().orElseThrow();
		var authority = consumerAlternative.inputAuthorities().stream()
			.filter(candidate -> candidate.inputPosition() == input.inputPosition())
			.findFirst().orElseThrow();
		Assert.assertEquals("PCA_MEANC_DIRECT_FOUT_AUTHORITY", InputAuthorityKind.DIRECT_FOUT,
			authority.kind());
		Assert.assertEquals("PCA_MEANC_DIRECT_FOUT_FTYPE", producerState.fType(), authority.expectedFType());
		var sourceVersion = analysis.graph().node(input.producer()).orElseThrow().valueVersion();
		Assert.assertFalse("PCA_MEANC_DIRECT_FOUT_MUST_NOT_EMIT_UPLOAD",
			selected.emittedRelocations().stream().anyMatch(action ->
				action.sourceValueVersion().equals(sourceVersion)
					&& action.compatibleConsumers().contains(input.consumer())));
	}

	private static MinStExactPhysicalSelection optimize(MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface) {
		return MinStExactPhysicalSelection.create(model, MinStExactPhysicalOptimizer.optimize(
			model, surface, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	private static MinStExactPhysicalModel.DecisionDomain domain(MinStExactPhysicalModel model,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		return model.domains().stream().filter(candidate -> candidate.node().key() == key)
			.findFirst().orElseThrow(() -> new AssertionError(
				"PCA_PHYSICAL_DOMAIN_MISSING|" + key.normalizedSignature()));
	}

	private static DMLProgram compilePca() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));",
			"[PC,V]=pca(X=X,K=5,scale=TRUE,center=TRUE);",
			"write(PC,\"out\",format=\"binary\");") + "\n";
		return compile(script);
	}

	private static DMLProgram compileHarnessShapePca() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X\"),"
				+ "ranges=list(list(0,0),list(50000,2100)));",
			"[Xout,Mout]=pca(X=X,K=10);",
			"write(Mout,\"out\",format=\"csv\");") + "\n";
		return compile(script);
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static CompiledInputEdgeFact uniqueEdge(PlacementAnalysis analysis,
		String producerSignature, String consumerSignature) {
		List<CompiledInputEdgeFact> matches = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer().normalizedSignature().contains(producerSignature)
				&& edge.consumer().normalizedSignature().contains(consumerSignature))
			.toList();
		Assert.assertEquals("PCA_EXPECTED_EDGE_MUST_BE_UNIQUE|producer=" + producerSignature
			+ "|consumer=" + consumerSignature, 1, matches.size());
		return matches.get(0);
	}
}

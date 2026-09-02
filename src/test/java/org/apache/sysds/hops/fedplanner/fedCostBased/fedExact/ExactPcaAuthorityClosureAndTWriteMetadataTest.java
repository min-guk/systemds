/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.BoundaryMode;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Production-shape PCA guards for exact physical authority and TWrite transfer pricing. */
public class ExactPcaAuthorityClosureAndTWriteMetadataTest {
	@Test
	public void wanHeavyPcaScoresCoherentFedAllAssignmentOnExactSurface() throws Exception {
		Map<String,String> previous = installWanHeavyCostProperties();
		try {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildDetachedAnalysis(compileHarnessShapePca(3));
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result exact = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			var fedAll = new FedAllPlacementAdapter().select(analysis);
			var projection = model.domains().stream().filter(domain -> domain.node().key().normalizedSignature()
				.contains("pca.dml:110:21:org.apache.sysds.hops.AggBinaryOp:ba(+*):XReduced"))
				.findFirst().orElseThrow();
			var projectionShape = analysis.abstractShapeFact(projection.node().key()).orElseThrow();
			Assert.assertTrue("PCA_PROJECTION_ROWS_MUST_BE_EXACT", projectionShape.rows().isExact(50000));
			Assert.assertTrue("PCA_PROJECTION_RANK_MUST_USE_EXACT_K", projectionShape.cols().isExact(10));
			int projectionIndex = model.domains().indexOf(projection);
			var projectionState = projection.alternatives().get(
				exact.solverResult().assignmentInVariableOrder().get(projectionIndex)).state();
			Assert.assertEquals("PCA_WAN_HEAVY_MUST_NOT_COLLECT_X_BEFORE_PROJECTION",
				ExecType.FED, projectionState.execType());

			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
			factors.addAll(surface.factors());
			for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
				var selectedState = fedAll.selectedStates().get(domain.node().key());
				Assert.assertNotNull("PCA_FEDALL_ASSIGNMENT_MUST_BE_TOTAL", selectedState);
				double[] force = new double[domain.alternatives().size()];
				Arrays.fill(force, Double.POSITIVE_INFINITY);
				for(int value = 0; value < domain.alternatives().size(); value++)
					if(domain.alternatives().get(value).state() == selectedState)
						force[value] = 0.0;
				Assert.assertTrue("PCA_FEDALL_STATE_MUST_EXIST_IN_EXACT_DOMAIN|decision="
					+ domain.node().key().normalizedSignature(),
					Arrays.stream(force).anyMatch(cost -> cost == 0.0));
				factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), force));
			}
			ExactCategoricalSolver.Result coherentFedAll = ExactCategoricalSolver.solve(
				model.variables(), factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			long coherentBits = surface.evaluateCanonical(coherentFedAll.assignmentInVariableOrder());
			Assert.assertEquals("PCA_ZERO_COST_STATE_FORCING_MUST_PRESERVE_CANONICAL_OBJECTIVE",
				coherentBits, Double.doubleToRawLongBits(coherentFedAll.objective()));

			Assert.assertTrue("PCA_EXACT_OBJECTIVE_MUST_NOT_EXCEED_FEASIBLE_COHERENT_FEDALL_OBJECTIVE",
				Double.longBitsToDouble(exact.canonicalObjectiveBits()) <= Double.longBitsToDouble(coherentBits));
		}
		finally {
			restoreProperties(previous);
		}
	}

	@Test
	public void oneWorkerPcaUsesEffectiveUnknownDimTransferEstimate() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileHarnessShapePca());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalSelection selected = optimize(model, surface);

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
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);

		CompiledInputEdgeFact containsInput = uniqueEdge(analysis,
			"AggBinaryOp:ba(+*):XReduced", "ParameterizedBuiltinOp:CONTAINS:containsInf");
		var containsDomain = domain(model, containsInput.consumer());
		List<ExactPhysicalModel.Alternative> federated = containsDomain.alternatives().stream()
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

		ExactPhysicalSelection selected = optimize(model, surface);
		ExactPhysicalPlacementProjector.project(selected);
		var tWriteState = selected.selectedStates().get(tWriteInput.consumer());
		Assert.assertTrue("PCA_TWRITE_STRICT_CP_LOUT_OR_FED_FOUT",
			tWriteState.execType() == ExecType.CP && tWriteState.output() == FederatedOutput.LOUT
				|| tWriteState.execType() == ExecType.FED && tWriteState.output() == FederatedOutput.FOUT);
	}

	@Test
	public void pcaWrittenSecondOutputReusesExactFoutProducerWithoutUpload() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileHarnessShapePca());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalSelection selected = optimize(model, surface);
		ExactPhysicalPlacementProjector.project(selected);

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

	private static ExactPhysicalSelection optimize(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) {
		return ExactPhysicalSelection.create(model, ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
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
		return compileHarnessShapePca(1);
	}

	private static DMLProgram compileHarnessShapePca(int workers) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = 50000L * worker / workers;
			long end = 50000L * (worker + 1) / workers;
			addresses.add("\"localhost:" + (1234 + worker) + "/X\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ",2100)");
		}
		String script = String.join("\n",
			"X=federated(addresses=list(" + String.join(",", addresses) + "),"
				+ "ranges=list(" + String.join(",", ranges) + "));",
			"[Xout,Mout]=pca(X=X,K=10);",
			"write(Mout,\"out\",format=\"csv\");") + "\n";
		return compile(script);
	}

	private static Map<String,String> installWanHeavyCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("DOCKER_NUM_WORKERS", "3"),
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"),
			Map.entry("SYSDS_FED_COST_NET_BW", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.2"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0.35"));
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
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

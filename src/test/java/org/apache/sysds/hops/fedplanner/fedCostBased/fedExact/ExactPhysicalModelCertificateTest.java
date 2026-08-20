/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014HermeticPlannerFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalModelCertificateTest {
	private static final ExactCategoricalSolver.Limits CAMPAIGN_LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000, 50_000_000);

	@Test
	public void sevenWorkloadsBuildBaselineFreePhysicalDomainsAndFactors() throws Exception {
		for(Workload workload : List.of(
			new Workload("KMEANS", kmeans()), new Workload("PCA", pca()),
			new Workload("LM", lm()), new Workload("L2SVM", l2svm()),
			new Workload("LOGREG", logreg()), new Workload("ALS", als()),
			new Workload("STEPLM", steplm()))) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(workload.program());
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactCategoricalSolver.Statistics statistics = model.analyze(CAMPAIGN_LIMITS);
			ExactCategoricalSolver.Result feasible = model.solveLegalityOnly(CAMPAIGN_LIMITS);
			ExactPhysicalModel.PhysicalSelection physical = model.physicalSelection(feasible);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
				model, surface, CAMPAIGN_LIMITS);
			ExactPhysicalSelection exact = ExactPhysicalSelection.create(model, optimized);
			var projected = ExactPhysicalPlacementProjector.project(exact);
			String certificate = workload.name() + "|decisions=" + model.domains().size()
				+ "|maxDomain=" + model.domains().stream()
					.mapToInt(domain -> domain.alternatives().size()).max().orElse(0)
				+ "|stats=" + statistics;
			Assert.assertEquals(certificate + "|synthetic boundaries are exact legality variables",
				analysis.graph().decisionNodes().size(), model.domains().size());
			Assert.assertTrue(certificate, model.domains().stream().allMatch(domain ->
				domain.alternatives().stream().allMatch(alternative ->
					alternative.decision() == domain.node().key()
						&& domain.node().legalAlternatives().stream()
							.anyMatch(state -> state == alternative.state()))));
			Assert.assertFalse(certificate, model.costSurfaceComplete());
			Assert.assertEquals(certificate, 0.0, feasible.objective(), 0.0);
			Assert.assertEquals(certificate, model.domains().size(),
				physical.alternativesInDecisionOrder().size());
			Assert.assertEquals(certificate, analysis.graph().decisionNodes().size(),
				exact.selectedStates().size());
			Assert.assertEquals(certificate, exact.selectedStates(),
				projected.normalizedResult().selectedStates());
			Assert.assertEquals(certificate, exact.candidateReceipts(),
				projected.normalizedResult().selectedCandidateSelections());
			for(var rule : analysis.candidateRuleFacts().orderedFacts()) {
				if(rule.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE)
					continue;
				var domain = model.domains().stream()
					.filter(candidate -> candidate.node().key() == rule.key().parentOccurrence())
					.findFirst().orElse(null);
				if(domain == null)
					continue;
				for(var emission : rule.allowedEmissionFacts()) {
					PlacementState state = emission.emissionState().placementState();
					if(!emission.emissionState().derivedFedFout()
						|| domain.node().legalAlternatives().stream().noneMatch(state::equals))
						continue;
					Assert.assertTrue(workload.name() + "|derived candidate silently removed|rule="
						+ rule.key().normalizedSignature() + "|emission=" + emission.normalizedSignature(),
						domain.alternatives().stream().anyMatch(alternative -> alternative.captured()
							&& alternative.candidateRule() == rule
							&& alternative.candidateEmission() == emission));
				}
			}
			Assert.assertTrue(certificate, physical.candidates().stream().allMatch(candidate ->
				candidate.rule().key().parentOccurrence() == candidate.decision()
					&& candidate.rule().allowedEmissionFacts().stream()
						.anyMatch(emission -> emission == candidate.emission())));
			for(var domain : model.domains())
				for(var alternative : domain.alternatives())
					if(alternative.authorityKind() == ExactPhysicalModel.AuthorityKind.LEGAL_SINGLETON) {
						long membership = domain.node().legalAlternatives().stream().filter(state ->
							state.execType() == alternative.state().execType()
								&& state.output() == alternative.state().output()).count();
						boolean authorityInputs = analysis.compiledInputEdgesInCanonicalOrder().stream()
							.anyMatch(edge -> edge.consumer() == domain.node().key())
							|| analysis.logicalTransientInputsInCanonicalOrder().stream()
								.anyMatch(input -> input.targetRead() == domain.node().key())
							|| analysis.logicalFunctionInputsInCanonicalOrder().stream()
								.anyMatch(input -> input.targetRead() == domain.node().key());
						Assert.assertEquals(certificate, 1L, membership);
						Assert.assertEquals(certificate, FederatedOutput.LOUT, alternative.state().output());
						Assert.assertTrue(certificate, alternative.state().execType() != ExecType.FED
							|| !authorityInputs);
					}
					else if(alternative.authorityKind()
						== ExactPhysicalModel.AuthorityKind.DURABLE_ANCHOR) {
						Assert.assertTrue(certificate, analysis.hop(domain.node().key()).orElseThrow()
							instanceof org.apache.sysds.hops.DataOp data
							&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED);
					}
					else if(alternative.authorityKind()
						== ExactPhysicalModel.AuthorityKind.CAPTURED_RULE
						&& alternative.candidateEmission().emissionState().derivedFedFout()) {
						Assert.assertNull(certificate, alternative.relocationAction());
						Assert.assertNotNull(certificate, alternative.derivedFoutAction());
						Assert.assertSame(certificate, domain.node().key(),
							alternative.derivedFoutAction().key().producer());
						Assert.assertSame(certificate, alternative.state(),
							alternative.derivedFoutAction().key().targetPlacement());
						Assert.assertTrue(certificate, domain.alternatives().stream().noneMatch(candidate ->
							candidate.authorityKind()
								== ExactPhysicalModel.AuthorityKind.RELOCATION_SOURCE
								&& candidate.state().equals(alternative.state())));
					}
			Assert.assertTrue(certificate, statistics.maximumFactorCells() <= 10_000_000);
		}
	}

	@Test
	public void everyPhysicalAlternativeRetainsItsTargetNodeStateIdentity() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(kmeans());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		for(var domain : model.domains())
			for(var alternative : domain.alternatives())
				Assert.assertTrue("foreign value-equal state for "
					+ domain.node().key().normalizedSignature(),
					domain.node().legalAlternatives().stream()
						.anyMatch(state -> state == alternative.state()));
	}

	@Test
	public void nativeLocalInputDoesNotConstrainProducerPlacement() {
		var nativeLocal = new ExactPhysicalModel.InputAuthority(0,
			ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL, null, null, null);
		Assert.assertTrue(ExactPhysicalModel.inputAuthorityPlacementSatisfied(nativeLocal,
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false)));
		Assert.assertTrue(ExactPhysicalModel.inputAuthorityPlacementSatisfied(nativeLocal,
			new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false)));
		Assert.assertTrue(ExactPhysicalModel.inputAuthorityPlacementSatisfied(nativeLocal,
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false)));
	}

	@Test
	public void conjunctiveForbidPairMatchesCategoricalFactorSemantics() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		var nodes = analysis.graph().decisionNodes();
		PlacementState left = nodes.get(0).legalAlternatives().get(0);
		PlacementState right = nodes.get(1).legalAlternatives().get(0);
		Constraint forbidden = new Constraint(ConstraintKind.CONJUNCTIVE,
			nodes.get(0).key(), nodes.get(1).key(), -1,
			"forbid-pair:" + left.normalizedSignature() + "=>" + right.normalizedSignature());
		Assert.assertFalse(ExactPhysicalModel.constraintSatisfied(forbidden, left, right));
		PlacementState different = new PlacementState(right.execType(), right.output(), right.fType(),
			!right.shapeDependent());
		Assert.assertTrue(ExactPhysicalModel.constraintSatisfied(forbidden, left, different));
	}

	@Test
	public void boundedFixtureVariableEliminationMatchesUnquotientedBruteForce() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		long combinations = model.domains().stream().mapToLong(domain -> domain.alternatives().size())
			.reduce(1L, Math::multiplyExact);
		Assert.assertTrue("fixture must remain a bounded brute-force oracle: " + combinations,
			combinations <= 1_000_000);

		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
		for(int variable = 0; variable < model.domains().size(); variable++) {
			int priority = variable;
			var domain = model.domains().get(variable);
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
				value -> (priority + 1) * 1000.0 + value[0]));
		}
		ExactCategoricalSolver.Result exact = ExactCategoricalSolver.solve(
			model.variables(), factors, CAMPAIGN_LIMITS);
		BruteForce brute = bruteForce(model, factors, 0, new ArrayList<>());
		Assert.assertEquals(brute.objective(), exact.objective(), 0.0);
		Assert.assertEquals(brute.assignment(), exact.assignmentInVariableOrder());
	}

	@Test
	public void optimizerRefusesToInventMissingCanonicalCosts() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalOptimizer.optimize(model, null, CAMPAIGN_LIMITS));
		Assert.assertEquals(model.missingCostSurface(), error.getMessage());
	}

	@Test
	public void physicalSelectionAndProjectorPreserveExactReceipts() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
			model, surface, CAMPAIGN_LIMITS);
		ExactCategoricalSolver.Result feasible = optimized.solverResult();
		ExactPhysicalSelection selected = ExactPhysicalSelection.create(model, optimized);
		var projected = ExactPhysicalPlacementProjector.project(selected);
		Assert.assertEquals(analysis.graph().decisionNodes().size(),
			projected.exactSelectedStates().size());
		Assert.assertEquals(selected.candidateReceipts(),
			projected.normalizedResult().selectedCandidateSelections());
		Assert.assertEquals(selected.relocationChoices(),
			projected.normalizedResult().selectedRelocationChoices());
		Assert.assertEquals(selected.emittedRelocations(),
			projected.normalizedResult().selectedRelocations());
		for(int index = 0; index < model.domains().size(); index++)
			Assert.assertSame(model.domains().get(index).alternatives()
				.get(feasible.assignmentInVariableOrder().get(index)),
				selected.alternativesInDecisionOrder().get(index));
	}

	@Test
	public void inlinedFunctionPhysicalSelectionCompletesSyntheticBoundaryAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-21"));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
			model, surface, CAMPAIGN_LIMITS);
		ExactPhysicalSelection selected = ExactPhysicalSelection.create(model, optimized);

		var boundaries = analysis.graph().decisionNodes().stream().filter(node ->
			node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
				|| node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
			.toList();
		Assert.assertTrue("B-21 must retain synthetic function-boundary decisions",
			boundaries.stream().anyMatch(node -> node.kind()
				== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT));
		Assert.assertTrue("B-21 must retain synthetic function-boundary decisions",
			boundaries.stream().anyMatch(node -> node.kind()
				== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT));
		Assert.assertEquals("exact selection must own every compiled and synthetic decision",
			analysis.graph().decisionNodes().size(), selected.selectedStates().size());
		Assert.assertTrue("every synthetic boundary must inherit an exact selected state",
			boundaries.stream().allMatch(node -> selected.selectedStates().containsKey(node.key())));
	}

	private static BruteForce bruteForce(ExactPhysicalModel model,
		List<ExactCategoricalSolver.Factor> factors, int index, List<Integer> assignment) {
		if(index == model.domains().size()) {
			double objective = 0;
			for(var factor : factors) {
				int[] local = new int[factor.scope().size()];
				for(int position = 0; position < local.length; position++)
					local[position] = assignment.get(model.variables().indexOf(factor.scope().get(position)));
				objective += factor.cost(local);
			}
			return new BruteForce(objective, List.copyOf(assignment));
		}
		BruteForce best = new BruteForce(Double.POSITIVE_INFINITY, List.of());
		for(int value = 0; value < model.domains().get(index).alternatives().size(); value++) {
			assignment.add(value);
			BruteForce candidate = bruteForce(model, factors, index + 1, assignment);
			assignment.remove(assignment.size() - 1);
			if(Double.compare(candidate.objective(), best.objective()) < 0
				|| candidate.objective() == best.objective()
					&& lexicographic(candidate.assignment(), best.assignment()) < 0)
				best = candidate;
		}
		return best;
	}

	private static int lexicographic(List<Integer> left, List<Integer> right) {
		if(right.isEmpty()) return -1;
		for(int index = 0; index < left.size(); index++) {
			int comparison = Integer.compare(left.get(index), right.get(index));
			if(comparison != 0) return comparison;
		}
		return 0;
	}

	private static DMLProgram kmeans() throws Exception {
		return compile(featuresPrelude() + String.join("\n",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n");
	}
	private static DMLProgram pca() throws Exception {
		return compile(featuresPrelude() + "[Xout,Mout]=pca(X=X,K=10);\n"
			+ "write(Mout,\"out\",format=\"csv\");\n");
	}
	private static DMLProgram lm() throws Exception {
		return compile(dataPrelude() + "B=lm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);\n"
			+ "write(B,\"out\",format=\"csv\");\n");
	}
	private static DMLProgram l2svm() throws Exception {
		return compile(dataPrelude() + "B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);\n"
			+ "write(B,\"out\",format=\"csv\");\n");
	}
	private static DMLProgram logreg() throws Exception {
		return compile(dataPrelude() + "Y=(Y<0)+1;\n"
			+ "B=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
			+ "numclasses=2,numrows=50000,numcols=2100);\nwrite(B,\"out\",format=\"csv\");\n");
	}
	private static DMLProgram als() throws Exception {
		return compile(featuresPrelude() + "[U,V]=als(X=X,rank=10,regType=\"L2\",reg=0.000001,maxi=2,"
			+ "check=FALSE,thr=0.0001,seed=1389632218,verbose=FALSE);\nwrite(V,\"out\",format=\"csv\");\n");
	}
	private static DMLProgram steplm() throws Exception {
		return compile(dataPrelude() + "[B,S]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);\n"
			+ "write(B,\"out\",format=\"csv\");\n");
	}
	private static String dataPrelude() {
		return featuresPrelude() + "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n";
	}
	private static String featuresPrelude() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n";
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

	private record Workload(String name, DMLProgram program) { }
	private record BruteForce(double objective, List<Integer> assignment) { }
	private static PlacementAnalysis boundedAnalysis() throws Exception {
		PlacementAnalysis full = CampaignBPlacementAnalysisFixtureBridge.build(
			ProductionShadowFixtureFactory.compile("B-01"));
		return CampaignBPlacementAnalysisFixtureBridge.prefix(full, 4);
	}

}

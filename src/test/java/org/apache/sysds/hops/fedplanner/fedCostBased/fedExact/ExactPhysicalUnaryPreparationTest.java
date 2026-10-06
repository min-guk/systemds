/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Keeps every unary cell equal to independent first-principles, per-alternative evaluation. */
public class ExactPhysicalUnaryPreparationTest {
	@Test
	public void repeatedReceiptsRetainEveryEagerUnaryRawBit() throws Exception {
		assertUnaryParity(false);
	}

	@Test
	public void newAnalysisAndWorkerCountDoNotReuseOldUnaryCosts() throws Exception {
		assertUnaryParity(false);
		assertUnaryParity(true);
	}

	@Test
	public void emittedOutputAnchorDoesNotOverrideUnaryExecutionPool() throws Exception {
		// A real partitioned elementwise kernel, not FED initialization or a
		// replicated FULL kernel, is the work whose W=2/W=4 prices must differ.
		var analysis = ExactWorkerLayoutProjectionTest.analysis(500);
		var sparse = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		var model = ExactPhysicalModel.build(analysis);
		var frequencies = analysis.executionFrequencyFacts();
		Method actual = method("addPhysicalUnaryFactor", PlacementAnalysis.class,
			ExpectedSparseAssignmentEstimates.class, ExactPhysicalModel.DecisionDomain.class,
			int.class, OccurrenceExecutionFrequencyFacts.class, List.class);
		Method fed = method("fedCostProjection", PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class);

		ExactPhysicalModel.DecisionDomain selectedDomain = null;
		ExactPhysicalModel.Alternative selected = null;
		double expectedTwo = Double.NaN;
		double expectedFour = Double.NaN;
		for(var domain : model.domains()) {
			Hop hop = analysis.hop(domain.node().key()).orElseThrow();
			double weight = frequencies.exactExecutionWeight(domain.node().key());
			for(var alternative : domain.alternatives()) {
				var emission = alternative.captured()
					? alternative.candidateEmission() : alternative.executionEmission();
				if(alternative.state().execType() != ExecType.FED
					|| alternative.state().output() != FederatedOutput.FOUT || alternative.realization() == null
					|| emission == null || emission.emissionState().derivedFedFout())
					continue;
				FType executionType = emission.executionFType();
				List<FType> inputs = alternative.orderedInputs().stream()
					.map(input -> input.present() ? input.fType() : null).toList();
				Object two = fed.invoke(null, analysis, sparse, domain.node().key(), hop,
					inputs, executionType, 2, weight);
				Object four = fed.invoke(null, analysis, sparse, domain.node().key(), hop,
					inputs, executionType, 4, weight);
				expectedTwo = projectionUnaryCost(two);
				expectedFour = projectionUnaryCost(four);
				if(Double.doubleToRawLongBits(expectedTwo) != Double.doubleToRawLongBits(expectedFour)) {
					selectedDomain = domain;
					selected = alternative;
					break;
				}
			}
			if(selected != null)
				break;
		}
		Assert.assertNotNull("fixture must contain a FED unary whose W=2/W=4 costs differ", selected);

		var twoWorkers = withExecutionPool(selected, 2, "two-worker-execution");
		var fourWorkers = withExecutionPool(selected, 4, "four-worker-execution");
		Assert.assertEquals("test requires one shared emitted relocation pool", twoWorkers.durableAnchor(),
			fourWorkers.durableAnchor());
		Assert.assertEquals("boundary pricing still follows the emitted anchor", 7,
			ExactPhysicalCostModel.realizationWorkerCount(analysis, twoWorkers, 99));
		Assert.assertEquals("unary execution follows the selected realization", 2,
			ExactPhysicalCostModel.executionWorkerCount(analysis, twoWorkers, 99));
		Assert.assertEquals(4,
			ExactPhysicalCostModel.executionWorkerCount(analysis, fourWorkers, 99));
		var variable = new ExactCategoricalSolver.Variable("execution-pool-unary", 2);
		var domain = new ExactPhysicalModel.DecisionDomain(selectedDomain.node(), variable,
			List.of(twoWorkers, fourWorkers));
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		actual.invoke(null, analysis, sparse, domain, ExactPhysicalCostModel.workerCount(analysis.graph()),
			frequencies, factors);

		Assert.assertEquals(4, factors.size());
		Assert.assertEquals(Double.doubleToRawLongBits(expectedTwo),
			Double.doubleToRawLongBits(factors.get(0).cost(new int[] {0})));
		Assert.assertEquals("projection caching must include the execution worker count",
			Double.doubleToRawLongBits(expectedFour),
			Double.doubleToRawLongBits(factors.get(0).cost(new int[] {1})));
	}

	@Test
	public void capturedDerivedFoutUsesInputSupportPoolNotOutputAnchor() throws Exception {
		Method workload = ExactPhysicalModelCertificateTest.class.getDeclaredMethod("kmeans");
		workload.setAccessible(true);
		var program = (org.apache.sysds.parser.DMLProgram)workload.invoke(null);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var sparse = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		var model = ExactPhysicalModel.build(analysis);
		var frequencies = analysis.executionFrequencyFacts();
		int executionWorkers = ExactPhysicalCostModel.workerCount(analysis.graph());
		Assert.assertEquals("campaign fixture execution pool", 2, executionWorkers);
		Method actual = method("addPhysicalUnaryFactor", PlacementAnalysis.class,
			ExpectedSparseAssignmentEstimates.class, ExactPhysicalModel.DecisionDomain.class,
			int.class, OccurrenceExecutionFrequencyFacts.class, List.class);
		Method fed = method("fedCostProjection", PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class);

		for(var originalDomain : model.domains()) {
			Hop hop = analysis.hop(originalDomain.node().key()).orElseThrow();
			double weight = frequencies.exactExecutionWeight(originalDomain.node().key());
			for(var source : originalDomain.alternatives()) {
				if(!source.captured() || source.candidateEmission() == null
					|| !source.candidateEmission().emissionState().derivedFedFout()
					|| source.supportClause().inputBindings().isEmpty())
					continue;
				FType type = source.candidateEmission().executionFType();
				List<FType> inputs = source.orderedInputs().stream()
					.map(input -> input.present() ? input.fType() : null).toList();
				Object expectedProjection = fed.invoke(null, analysis, sparse, originalDomain.node().key(), hop,
					inputs, type, executionWorkers, weight);
				Object wrongProjection = fed.invoke(null, analysis, sparse, originalDomain.node().key(), hop,
					inputs, type, 7, weight);
				double expected = projectionDerivedCost(expectedProjection);
				if(Double.doubleToRawLongBits(expected)
					== Double.doubleToRawLongBits(projectionDerivedCost(wrongProjection)))
					continue;

				DurableAnchorKey output = workerPool(source.state().fType(), 7, "derived-output-only");
				var realization = new PlacementAnalysis.CandidateEmissionRealization(
					PlacementRealizationKey.durable(source.candidateEmission().emissionState(), output),
					List.of(source.supportClause()));
				var derived = new ExactPhysicalModel.Alternative(source.decision(), source.state(),
					source.authorityKind(), source.candidateRule(), source.candidateEmission(),
					source.executionRule(), source.executionEmission(), source.durableAnchor(),
					source.relocationAction(), source.derivedFoutAction(), source.orderedInputs(),
					source.inputAuthorities(), realization, realization.supportClauses().get(0),
					"captured-derived-output-seven-execution-two");
				Assert.assertEquals(7, derived.realization().anchor().partitions().size());
				var variable = new ExactCategoricalSolver.Variable("captured-derived-unary", 1);
				var domain = new ExactPhysicalModel.DecisionDomain(
					originalDomain.node(), variable, List.of(derived));
				List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
				actual.invoke(null, analysis, sparse, domain, executionWorkers, frequencies, factors);

				Assert.assertEquals("derived output residency is not execution residency", executionWorkers,
					ExactPhysicalCostModel.executionWorkerCount(analysis, derived, executionWorkers));
				Assert.assertEquals(Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(factors.get(0).cost(new int[] {0})));
				return;
			}
		}
		Assert.fail("fixture must expose a cost-sensitive captured derived-FOUT unary");
	}

	private static void assertUnaryParity(boolean unrelatedWorkers) throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(unrelatedWorkers);
		var sparse = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		var model = ExactPhysicalModel.build(analysis);
		int workers = ExactPhysicalCostModel.workerCount(analysis.graph());
		var frequencies = analysis.executionFrequencyFacts();
		Method actual = method("addPhysicalUnaryFactor", PlacementAnalysis.class,
			ExpectedSparseAssignmentEstimates.class, ExactPhysicalModel.DecisionDomain.class,
			int.class, OccurrenceExecutionFrequencyFacts.class, List.class);
		Method cp = method("cpUnaryCost", PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, double.class);
		Method fed = method("fedCostProjection", PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class,
			FederatedExecutionLayout.class);
		Method upload = method("physicalResultUploadCost", PlacementAnalysis.class,
			ExpectedSparseAssignmentEstimates.class, CompiledHopKey.class, Hop.class,
			FType.class, int.class, double.class);
		int duplicateReceiptObservations = 0;
		int checked = 0;
		for(var domain : model.domains()) {
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			actual.invoke(null, analysis, sparse, domain, workers, frequencies, factors);
			if(domain.node().kind() == NodeKind.FUNCTION_INPUT
				|| domain.node().kind() == NodeKind.FUNCTION_OUTPUT
				|| analysis.isDmlFunctionCallBoundary(domain.node().key()))
				continue;
			Assert.assertEquals(4, factors.size());
			Hop hop = analysis.hop(domain.node().key()).orElseThrow();
			double weight = frequencies.exactExecutionWeight(domain.node().key());
			Set<List<?>> receiptObservations = new HashSet<>();
			for(int value = 0; value < domain.alternatives().size(); value++) {
				var alternative = domain.alternatives().get(value);
				var state = alternative.state();
				double[] expected = new double[4];
				if(state.execType() == ExecType.CP) {
					expected[0] = (double)cp.invoke(null, analysis, sparse, domain.node().key(), hop, weight);
					if(state.output() == FederatedOutput.FOUT)
						expected[3] = (double)upload.invoke(null, analysis, sparse,
							domain.node().key(), hop, state.fType(),
							ExactPhysicalCostModel.realizationWorkerCount(analysis, alternative, workers), weight);
				}
				else {
					var emission = alternative.captured()
						? alternative.candidateEmission() : alternative.executionEmission();
					FType executionType = emission == null ? state.fType() : emission.executionFType();
					int executionWorkers = ExactPhysicalCostModel.executionWorkerCount(
						analysis, alternative, workers);
					boolean source = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
					List<FType> inputs = source ? List.of() : alternative.orderedInputs().stream()
						.map(input -> input.present() ? input.fType() : null).toList();
					Object projection = fed.invoke(null, analysis, sparse, domain.node().key(), hop,
						inputs, executionType, executionWorkers, weight,
						ExactPhysicalCostModel.executionLayout(analysis, alternative, executionType, executionWorkers));
					Method compute = projection.getClass().getDeclaredMethod("fedUnaryCost");
					Method download = projection.getClass().getDeclaredMethod("resultDownloadCost");
					compute.setAccessible(true);
					download.setAccessible(true);
					double computeCost = (double)compute.invoke(projection);
					double downloadCost = (double)download.invoke(projection);
					boolean derived = state.output() == FederatedOutput.FOUT && emission != null
						&& emission.emissionState().derivedFedFout();
					expected[0] = derived ? computeCost + downloadCost : computeCost;
					if(derived)
						expected[1] = (double)upload.invoke(null, analysis, sparse,
							domain.node().key(), hop, state.fType(),
							ExactPhysicalCostModel.realizationWorkerCount(analysis, alternative, workers), weight);
					else if(state.output() == FederatedOutput.LOUT)
						expected[2] = downloadCost;
					if(!receiptObservations.add(java.util.Arrays.asList(
						alternative.orderedInputs(), executionType)))
						duplicateReceiptObservations++;
				}
				for(int factor = 0; factor < 4; factor++)
					Assert.assertEquals("domain=" + domain.variable().key() + " value=" + value + " factor=" + factor,
						Double.doubleToRawLongBits(expected[factor]),
						Double.doubleToRawLongBits(factors.get(factor).cost(new int[] {value})));
				checked++;
			}
		}
		Assert.assertTrue("fixture must exercise repeated receipt-independent projections",
			duplicateReceiptObservations > 0);
		Assert.assertTrue(checked > duplicateReceiptObservations);
	}

	private static Method method(String name, Class<?>... parameters) throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	private static double projectionUnaryCost(Object projection) throws Exception {
		Method compute = projection.getClass().getDeclaredMethod("fedUnaryCost");
		compute.setAccessible(true);
		return (double)compute.invoke(projection);
	}

	private static double projectionDerivedCost(Object projection) throws Exception {
		Method download = projection.getClass().getDeclaredMethod("resultDownloadCost");
		download.setAccessible(true);
		return projectionUnaryCost(projection) + (double)download.invoke(projection);
	}

	private static ExactPhysicalModel.Alternative withExecutionPool(
		ExactPhysicalModel.Alternative source, int workers, String signature) {
		var emission = source.captured() ? source.candidateEmission() : source.executionEmission();
		FType type = emission.executionFType();
		DurableAnchorKey pool = workerPool(type, workers, signature);
		var realization = PlacementAnalysis.CandidateEmissionRealization.durable(
			emission.emissionState(), pool, List.of(), List.of());
		return new ExactPhysicalModel.Alternative(source.decision(), source.state(), source.authorityKind(),
			source.candidateRule(), source.candidateEmission(), source.executionRule(), source.executionEmission(),
			workerPool(source.state().fType(), 7, "emitted-relocation-pool"), source.relocationAction(),
			source.derivedFoutAction(), source.orderedInputs(),
			source.inputAuthorities(), realization, realization.supportClauses().get(0), signature);
	}

	private static DurableAnchorKey workerPool(FType type, int workers, String placement) {
		List<AnchorPartition> partitions = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++)
			partitions.add(new AnchorPartition("execution-worker-" + worker + ":1234/" + placement,
				List.of((long)worker, 0L), List.of((long)worker + 1L, 2L)));
		return new DurableAnchorKey(placement, type, partitions);
	}
}

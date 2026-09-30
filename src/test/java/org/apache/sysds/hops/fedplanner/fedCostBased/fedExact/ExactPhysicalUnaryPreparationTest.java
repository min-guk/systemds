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
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
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
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class);
		Method upload = method("physicalResultUploadCost", PlacementAnalysis.class,
			ExpectedSparseAssignmentEstimates.class, CompiledHopKey.class, Hop.class,
			FType.class, int.class, double.class);
		int duplicateObservations = 0;
		int checked = 0;
		for(var domain : model.domains()) {
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			actual.invoke(null, analysis, sparse, domain, workers, frequencies, factors);
			if(domain.node().kind() == NodeKind.FUNCTION_INPUT
				|| domain.node().kind() == NodeKind.FUNCTION_OUTPUT)
				continue;
			Assert.assertEquals(4, factors.size());
			Hop hop = analysis.hop(domain.node().key()).orElseThrow();
			double weight = frequencies.exactExecutionWeight(domain.node().key());
			Set<List<?>> observations = new HashSet<>();
			for(int value = 0; value < domain.alternatives().size(); value++) {
				var alternative = domain.alternatives().get(value);
				var state = alternative.state();
				double[] expected = new double[4];
				if(state.execType() == ExecType.CP) {
					expected[0] = (double)cp.invoke(null, analysis, sparse, domain.node().key(), hop, weight);
					if(state.output() == FederatedOutput.FOUT)
						expected[3] = (double)upload.invoke(null, analysis, sparse,
							domain.node().key(), hop, state.fType(), workers, weight);
				}
				else {
					var emission = alternative.captured()
						? alternative.candidateEmission() : alternative.executionEmission();
					FType executionType = emission == null ? state.fType() : emission.executionFType();
					boolean source = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
					List<FType> inputs = source ? List.of() : alternative.orderedInputs().stream()
						.map(input -> input.present() ? input.fType() : null).toList();
					Object projection = fed.invoke(null, analysis, sparse, domain.node().key(), hop,
						inputs, executionType, workers, weight);
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
							domain.node().key(), hop, state.fType(), workers, weight);
					else if(state.output() == FederatedOutput.LOUT)
						expected[2] = downloadCost;
					if(!observations.add(java.util.Arrays.asList(alternative.orderedInputs(), executionType)))
						duplicateObservations++;
				}
				for(int factor = 0; factor < 4; factor++)
					Assert.assertEquals("domain=" + domain.variable().key() + " value=" + value + " factor=" + factor,
						Double.doubleToRawLongBits(expected[factor]),
						Double.doubleToRawLongBits(factors.get(factor).cost(new int[] {value})));
				checked++;
			}
		}
		Assert.assertTrue("fixture must exercise repeated receipt-independent projections", duplicateObservations > 0);
		Assert.assertTrue(checked > duplicateObservations);
	}

	private static Method method(String name, Class<?>... parameters) throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}
}

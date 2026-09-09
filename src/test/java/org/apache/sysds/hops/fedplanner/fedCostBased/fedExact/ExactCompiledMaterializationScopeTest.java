/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.BoundaryMode;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.Direction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for creation scope derived from a TRead's logical source definition. */
public class ExactCompiledMaterializationScopeTest {
	private static final double ITERATIONS = 3.0;

	@Test
	public void compiledDownloadsUseLogicalWriteLifetimeInsteadOfRepeatedTreadLifetime()
		throws Exception {
		PlacementAnalysis analysis = analyze();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);

		Download stable = download(analysis, model, surface, "stable");
		Download updated = download(analysis, model, surface, "updated");
		Assert.assertEquals("Both transient reads execute once per loop iteration",
			ITERATIONS, executionWeight(analysis, stable.read()), 0.0);
		Assert.assertEquals("Both transient reads execute once per loop iteration",
			ITERATIONS, executionWeight(analysis, updated.read()), 0.0);
		Assert.assertTrue("The stable value has an outside-loop reaching definition",
			stable.sourceWrites().stream().anyMatch(write -> executionWeight(analysis, write) == 1.0));
		Assert.assertTrue("The updated value has a loop-backedge definition for every new state",
			updated.sourceWrites().stream().anyMatch(write ->
				executionWeight(analysis, write) == ITERATIONS));

		double stableCost = forcedDownloadContribution(surface, model, stable);
		double updatedCost = forcedDownloadContribution(surface, model, updated);
		Assert.assertTrue("The production physical surface must expose a nonzero stable download",
			stableCost > 0.0);
		Assert.assertEquals("A loop-invariant MatrixObject is collected once while a loop-updated"
			+ " MatrixObject is collected once per produced state",
			ITERATIONS * stableCost, updatedCost, Math.max(1e-12, stableCost * 1e-12));
	}

	private static Download download(PlacementAnalysis analysis, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, String variable) {
		CompiledHopKey read = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && variable.equals(data.getName()))
			.map(occurrence -> occurrence.key())
			.filter(key -> executionWeight(analysis, key) == ITERATIONS)
			.findFirst().orElseThrow(() -> new AssertionError(
				"Repeated TRead missing for " + variable));
		List<CompiledHopKey> sourceWrites = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == read)
			.map(fact -> fact.sourceWrite()).distinct().toList();
		Assert.assertFalse("Logical transient source missing for " + variable,
			sourceWrites.isEmpty());
		CompiledHopKey consumer = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == read
				&& analysis.hop(edge.consumer()).orElseThrow() instanceof AggUnaryOp)
			.map(edge -> edge.consumer()).findFirst().orElseThrow(() -> new AssertionError(
				"CP aggregate consumer missing for " + variable));
		var sourceDomain = domain(model, read);
		var consumerDomain = domain(model, consumer);
		for(var key : surface.transferKeys()) {
			if(key.direction() != Direction.DOWNLOAD
				|| key.boundaryMode() != BoundaryMode.ANCHOR_TRANSFER
				|| key.endpoints().stream().noneMatch(endpoint -> endpoint.producer() == read
					&& endpoint.consumer() == consumer))
				continue;
			int sourceValue = alternative(sourceDomain, state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == key.fType());
			int consumerValue = alternative(consumerDomain, state -> state.execType() == ExecType.CP);
			if(sourceValue >= 0 && consumerValue >= 0)
				return new Download(variable, read, sourceWrites, consumer, sourceDomain,
					consumerDomain, sourceValue, consumerValue);
		}
		throw new AssertionError("Forced download alternative missing for " + variable
			+ "|sourceAlternatives=" + sourceDomain.alternatives().stream()
				.map(alternative -> alternative.state().normalizedSignature()).toList()
			+ "|consumerAlternatives=" + consumerDomain.alternatives().stream()
				.map(alternative -> alternative.state().normalizedSignature()).toList()
			+ "|downloads=" + surface.transferKeys().stream()
				.filter(key -> key.direction() == Direction.DOWNLOAD)
				.map(Object::toString).toList());
	}

	private static double forcedDownloadContribution(
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalModel model,
		Download download) {
		List<Integer> assignment = new ArrayList<>();
		for(int index = 0; index < model.domains().size(); index++)
			assignment.add(0);
		assignment.set(model.domains().indexOf(download.sourceDomain()), download.sourceValue());
		assignment.set(model.domains().indexOf(download.consumerDomain()), download.consumerValue());
		List<ExactCategoricalSolver.Variable> targetScope =
			List.of(download.sourceDomain().variable(), download.consumerDomain().variable());
		List<Double> positive = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().equals(targetScope))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution, assignment))
			.filter(cost -> cost > 0.0).boxed().toList();
		Assert.assertEquals("Exactly one selected download contribution for " + download.variable(),
			1, positive.size());
		return positive.get(0);
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
		CompiledHopKey key) {
		return model.domains().stream().filter(candidate -> candidate.node().key() == key)
			.findFirst().orElseThrow(() -> new AssertionError(
				"Exact physical domain missing for " + key.normalizedSignature()));
	}

	private static int alternative(ExactPhysicalModel.DecisionDomain domain,
		java.util.function.Predicate<org.apache.sysds.hops.fedplanner.placement.PlacementState> predicate) {
		for(int index = 0; index < domain.alternatives().size(); index++)
			if(predicate.test(domain.alternatives().get(index).state()))
				return index;
		return -1;
	}

	private static double executionWeight(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.executionFrequencyFacts().exactExecutionWeight(key);
	}

	private static PlacementAnalysis analyze() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));",
			"stable=rowSums(X);",
			"updated=stable;",
			"for(i in 1:3) {",
			"  print(sum(stable));",
			"  print(sum(updated));",
			"  updated=rowSums(X+i);",
			"}") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private record Download(String variable, CompiledHopKey read, List<CompiledHopKey> sourceWrites,
		CompiledHopKey consumer, ExactPhysicalModel.DecisionDomain sourceDomain,
		ExactPhysicalModel.DecisionDomain consumerDomain, int sourceValue, int consumerValue) { }
}

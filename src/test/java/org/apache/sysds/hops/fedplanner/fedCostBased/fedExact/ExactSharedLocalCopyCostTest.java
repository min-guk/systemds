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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
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

/** Canonical FOUT-to-local copies are shared by CP and FED native-local uses. */
public class ExactSharedLocalCopyCostTest {
	@Test
	public void mixedConsumersShareOneMaximumDemand() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains = new IdentityHashMap<>();
		for(var domain : model.domains())
			domains.put(domain.node().key(), domain);

		ExactPhysicalModel.DecisionDomain producer = null;
		List<ExactPhysicalModel.DecisionDomain> consumers = List.of();
		for(var candidate : model.domains()) {
			if(candidate.alternatives().stream().noneMatch(ExactSharedLocalCopyCostTest::isRemoteFout))
				continue;
			List<ExactPhysicalModel.DecisionDomain> outgoing = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer() == candidate.node().key())
				.filter(edge -> !analysis.isCoordinatorMetadataOnlyInput(edge))
				.map(edge -> domains.get(edge.consumer())).filter(domain -> domain != null).distinct().toList();
			long nativeLocal = outgoing.stream()
				.filter(consumer -> hasNativeLocalFor(analysis, candidate, consumer)).count();
			if(nativeLocal >= 2 && outgoing.stream().anyMatch(ExactSharedLocalCopyCostTest::hasCp)) {
				producer = candidate;
				consumers = outgoing;
				break;
			}
		}
		Assert.assertNotNull("Fixture must contain one remote source shared by CP and two FED-local uses", producer);
		ExactPhysicalModel.DecisionDomain sharedProducer = producer;

		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<ExactPhysicalModel.DecisionDomain> groupedConsumers = consumers;
		int remoteSource = alternative(sharedProducer, ExactSharedLocalCopyCostTest::isRemoteFout);
		Map<ExactCategoricalSolver.Variable,Integer> inactive = new IdentityHashMap<>();
		model.domains().forEach(domain -> inactive.put(domain.variable(), inactiveAlternative(domain)));
		inactive.put(sharedProducer.variable(), remoteSource);
		Map<ExactCategoricalSolver.Variable,Integer> all = new IdentityHashMap<>(inactive);
		for(var consumer : consumers) {
			int inputPosition = inputPosition(analysis, sharedProducer, consumer);
			java.util.function.Predicate<ExactPhysicalModel.Alternative> intended =
				hasNativeLocalFor(analysis, sharedProducer, consumer)
				? alternative -> alternative.state().execType() == ExecType.FED
					&& alternative.inputAuthorities().stream().anyMatch(authority ->
						authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL
							&& authority.inputPosition() == inputPosition)
				: alternative -> alternative.state().execType() == ExecType.CP;
			int active = alternativeOrNegative(consumer, intended);
			if(active >= 0)
				all.put(consumer.variable(), active);
		}
		var shared = surface.contributions().stream().map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> !factor.scope().isEmpty() && factor.scope().get(0) == sharedProducer.variable())
			.filter(factor -> groupedConsumers.stream()
				.allMatch(consumer -> factor.scope().contains(consumer.variable())))
			.filter(factor -> Double.isFinite(cost(factor, all)) && cost(factor, all) > 0d).toList();
		Assert.assertFalse("Shared materialization must have priced activation factors", shared.isEmpty());
		double inactiveCost = cost(shared, inactive);
		Assert.assertEquals("No consumer activation creates the shared local copy", 0d, inactiveCost, 0d);
		List<Double> singles = new ArrayList<>();
		singles.add(inactiveCost);
		int nativeActivations = 0;
		int cpActivations = 0;
		for(var consumer : consumers) {
			int active = all.get(consumer.variable());
			if(active < 0 || shared.stream().noneMatch(factor -> factor.scope().contains(consumer.variable())))
				continue;
			Map<ExactCategoricalSolver.Variable,Integer> one = new IdentityHashMap<>(inactive);
			one.put(consumer.variable(), active);
			double single = cost(shared, one);
			if(single > 0.0) {
				singles.add(single);
				var selected = consumer.alternatives().get(active);
				if(selected.state().execType() == ExecType.CP)
					cpActivations++;
				else
					nativeActivations++;
			}
		}
		Assert.assertTrue("Fixture must expose a CP activation: " + singles, cpActivations >= 1);
		Assert.assertTrue("Fixture must expose two correctly bound FED native-local activations: " + singles,
			nativeActivations >= 2);
		Assert.assertEquals("Co-active uses share one copy charged at the largest per-creation unit cost",
			singles.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), cost(shared, all), 1e-12);
		int localSource = alternativeOrNegative(sharedProducer, alternative -> !isRemoteFout(alternative));
		if(localSource >= 0) {
			var local = new IdentityHashMap<>(all);
			local.put(sharedProducer.variable(), localSource);
			Assert.assertEquals("Native LOUT and derived local-held sources require no FOUT download",
				0d, cost(shared, local), 0d);
		}
	}

	@Test
	public void logicalAndCompiledUsesActivateOneSharedDownload() throws Exception {
		String script = "f=function(matrix[double] P,matrix[double] Q,matrix[double] R)"
			+ " return (scalar[double] z) {z=sum(Q)+sum(R);"
			+ "i=1;while(i<2){z=z+sum(P);i=i+1;}}\n"
			+ "X=federated(addresses=list(\"localhost:1234/X\",\"localhost:1235/X\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "A=rowSums(X); z=f(A,A,rowSums(A)); print(z);\n";
		var analysis = analysis(script);
		var model = ExactPhysicalModel.build(analysis);
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains = new IdentityHashMap<>();
		model.domains().forEach(domain -> domains.put(domain.node().key(), domain));
		var producer = model.domains().stream().filter(domain ->
			analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.filter(input -> input.sourceArgument() == domain.node().key()).count() >= 2)
			.findFirst().orElseThrow();
		var logicalUses = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(input -> input.sourceArgument() == producer.node().key()).toList();
		var logical = logicalUses.stream().map(input -> domains.get(input.targetRead())).distinct().toList();
		Assert.assertEquals("P and Q must be distinct formal uses of the identical actual source", 2, logical.size());
		logicalUses.forEach(input -> Assert.assertSame(producer.node().key(), input.sourceArgument()));
		var callsites = logicalUses.stream()
			.map(analysis::requireExactPhysicalFunctionInputConsumer).distinct().toList();
		Assert.assertEquals("The repeated formal use must retain its one physical callsite", 1, callsites.size());
		var callsiteProfiles = analysis.executionFrequencyFacts().exactProfiles(callsites.get(0));
		Assert.assertEquals(1, callsiteProfiles.size());
		Assert.assertEquals("Formal use inside the callee loop activates at caller scope", 1,
			callsiteProfiles.get(0).expectedExecutions(), 0);
		Assert.assertTrue("Callee loops must not be projected onto the caller binding",
			callsiteProfiles.get(0).loopContext().isEmpty());
		var compiled = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == producer.node().key())
			.map(edge -> domains.get(edge.consumer())).distinct().filter(domain -> domain != null)
			.filter(domain -> domain.node().kind()
				!= org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_CALL)
			.findFirst().orElseThrow();
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Map<ExactCategoricalSolver.Variable,Integer> inactive = new IdentityHashMap<>();
		model.domains().forEach(domain -> inactive.put(domain.variable(), inactiveAlternative(domain)));
		inactive.put(producer.variable(), alternative(producer, ExactSharedLocalCopyCostTest::isRemoteFout));
		List<ExactPhysicalModel.DecisionDomain> downloadUsers = new ArrayList<>(logical);
		downloadUsers.add(compiled);
		Map<ExactCategoricalSolver.Variable,Integer> all = new IdentityHashMap<>(inactive);
		for(var user : downloadUsers)
			all.put(user.variable(), alternative(user, choice -> choice.state().execType() == ExecType.CP
				&& choice.state().output() == FederatedOutput.LOUT));
		var shared = materializationFactors(surface, producer, downloadUsers, inactive, all);
		Assert.assertFalse("Shared logical/compiled materialization must have priced activation factors",
			shared.isEmpty());
		Assert.assertEquals("No local use activates this download", 0d, cost(shared, inactive), 0d);
		double maximum = 0d;
		for(var user : downloadUsers) {
			var one = new IdentityHashMap<>(inactive);
			one.put(user.variable(), all.get(user.variable()));
			double price = cost(shared, one);
			Assert.assertTrue("Each compiled/logical use must independently activate the shared copy", price > 0d);
			maximum = Math.max(maximum, price);
		}
		Assert.assertEquals("Logical and compiled activations share one per-creation maximum",
			maximum, cost(shared, all), 1e-12);

		Assert.assertTrue("These direct/local-only formals do not admit relocation alternatives",
			logical.stream().flatMap(user -> user.alternatives().stream())
				.flatMap(choice -> choice.inputAuthorities().stream()).noneMatch(authority ->
					authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION));
		Assert.assertTrue("No logical upload may be invented without an authorized relocation",
			surface.transferKeys().stream().filter(key -> key.direction() == ExactPhysicalCostModel.Direction.UPLOAD)
				.flatMap(key -> key.endpoints().stream()).noneMatch(endpoint ->
					endpoint.producer() == producer.node().key() && logical.stream()
						.anyMatch(user -> endpoint.consumer() == user.node().key())));
	}

	@Test
	public void unequalAndInactiveDemandsUseExactMaximum() {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var cp = new ExactCategoricalSolver.Variable("cp", 2);
		var fedOne = new ExactCategoricalSolver.Variable("fed-one", 2);
		var fedTwo = new ExactCategoricalSolver.Variable("fed-two", 2);
		var event = new ExactMaterializationActivation.Event(1, List.of());
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addPricedMaterializationActivationFactors("shared-local-copy", source,
			new boolean[] {true, false}, List.of(
				new ExactPhysicalCostModel.PricedActivationDemand(
					new ExactPhysicalCostModel.ActivationDemand(List.of(cp),
						List.of(new boolean[] {false, true}), event), new double[] {9.0, 9.0}),
				new ExactPhysicalCostModel.PricedActivationDemand(
					new ExactPhysicalCostModel.ActivationDemand(List.of(fedOne),
						List.of(new boolean[] {false, true}), event), new double[] {4.0, 4.0}),
				new ExactPhysicalCostModel.PricedActivationDemand(
					new ExactPhysicalCostModel.ActivationDemand(List.of(fedTwo),
						List.of(new boolean[] {false, true}), event), new double[] {7.0, 7.0})),
			1, factors, factorizations, null);
		Assert.assertEquals("Inactive supplies do not activate a copy", 0.0,
			cost(factors, List.of(source, cp, fedOne, fedTwo), 0, 0, 0, 0), 0.0);
		Assert.assertEquals("Two FED uses share the larger per-creation unit cost", 7.0,
			cost(factors, List.of(source, cp, fedOne, fedTwo), 0, 0, 1, 1), 0.0);
		Assert.assertEquals("CP and FED activations share one cross-kind unit maximum", 9.0,
			cost(factors, List.of(source, cp, fedOne, fedTwo), 0, 1, 1, 1), 0.0);
		Assert.assertEquals("A local-held source has no download activation", 0.0,
			cost(factors, List.of(source, cp, fedOne, fedTwo), 1, 1, 1, 1), 0.0);
	}

	private static double cost(List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> variables, Integer... values) {
		return ExactCategoricalSolver.evaluate(variables, factors,
			new ExactCategoricalSolver.Limits(100_000, 1_000_000), List.of(values));
	}

	private static boolean isRemoteFout(ExactPhysicalModel.Alternative alternative) {
		if(alternative.state().execType() != ExecType.FED
			|| alternative.state().output() != FederatedOutput.FOUT)
			return false;
		var emission = alternative.captured()
			? alternative.candidateEmission() : alternative.executionEmission();
		return emission == null || !emission.emissionState().derivedFedFout();
	}

	private static boolean hasCp(ExactPhysicalModel.DecisionDomain domain) {
		return domain.alternatives().stream().anyMatch(alternative ->
			alternative.state().execType() == ExecType.CP);
	}

	private static boolean hasNativeLocalFor(PlacementAnalysis analysis,
		ExactPhysicalModel.DecisionDomain producer, ExactPhysicalModel.DecisionDomain consumer) {
		int inputPosition = inputPosition(analysis, producer, consumer);
		return consumer.alternatives().stream().anyMatch(alternative ->
			alternative.state().execType() == ExecType.FED && alternative.inputAuthorities().stream()
				.anyMatch(authority -> authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL
					&& authority.inputPosition() == inputPosition));
	}

	private static int inputPosition(PlacementAnalysis analysis,
		ExactPhysicalModel.DecisionDomain producer, ExactPhysicalModel.DecisionDomain consumer) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == producer.node().key()
				&& edge.consumer() == consumer.node().key())
			.mapToInt(edge -> edge.inputPosition()).findFirst().orElseThrow();
	}

	private static int inactiveAlternative(ExactPhysicalModel.DecisionDomain domain) {
		int fedPresent = alternativeOrNegative(domain, alternative ->
			alternative.state().execType() == ExecType.FED && alternative.inputAuthorities().stream()
				.noneMatch(authority -> authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL));
		return fedPresent >= 0 ? fedPresent : 0;
	}

	private static int alternative(ExactPhysicalModel.DecisionDomain domain,
		java.util.function.Predicate<ExactPhysicalModel.Alternative> predicate) {
		int value = alternativeOrNegative(domain, predicate);
		if(value < 0)
			throw new AssertionError("Required alternative is absent for " + domain.node().key());
		return value;
	}

	private static int alternativeOrNegative(ExactPhysicalModel.DecisionDomain domain,
		java.util.function.Predicate<ExactPhysicalModel.Alternative> predicate) {
		for(int value = 0; value < domain.alternatives().size(); value++)
			if(predicate.test(domain.alternatives().get(value)))
				return value;
		return -1;
	}

	private static double cost(ExactCategoricalSolver.Factor factor,
		Map<ExactCategoricalSolver.Variable,Integer> assignment) {
		int[] values = factor.scope().stream().mapToInt(variable -> assignment.getOrDefault(variable, 0)).toArray();
		return factor.cost(values);
	}

	private static double cost(List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Variable,Integer> assignment) {
		return factors.stream().mapToDouble(factor -> cost(factor, assignment)).sum();
	}

	private static List<ExactCategoricalSolver.Factor> materializationFactors(
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalModel.DecisionDomain producer,
		List<ExactPhysicalModel.DecisionDomain> consumers,
		Map<ExactCategoricalSolver.Variable,Integer> inactive,
		Map<ExactCategoricalSolver.Variable,Integer> active) {
		return surface.contributions().stream().map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> !factor.scope().isEmpty() && factor.scope().get(0) == producer.variable())
			.filter(factor -> factor.scope().size() > 1 && factor.scope().stream().skip(1).allMatch(variable ->
				consumers.stream().anyMatch(consumer -> consumer.variable() == variable)))
			.filter(factor -> Double.isFinite(cost(factor, active))
				&& cost(factor, active) > cost(factor, inactive))
			.toList();
	}

	private static PlacementAnalysis analysis() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\",\"localhost:1235/X\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "R=federated(addresses=list(\"localhost:1234/R\",\"localhost:1235/R\"),"
			+ "ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));\n"
			+ "A=rowSums(X); B=rowSums(R); O1=A+B; O2=A-B;\n"
			+ "for(i in 1:3) print(sum(B)); print(sum(O1)); print(sum(O2));\n";
		return analysis(script);
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

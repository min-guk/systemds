/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.DerivedFoutAnchorCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofQuery;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exhaustive oracle for the derived-FOUT factor's producer-prefix certificate. */
public class DerivedFoutAnchorPartialHardTest {
	private static final ExactCategoricalSolver.Limits PARITY_LIMITS =
		new ExactCategoricalSolver.Limits(2_000_000, 20_000_000);

	@Test public void certifiedPrefixesAndFrozenTableMatchEveryLeaf() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> byVariable =
			new IdentityHashMap<>();
		for(var domain : model.domains()) byVariable.put(domain.variable(), domain);
		long certifiedLeaves = 0;
		long logicalLeaves = 0;
		int checkedFactors = 0;
		List<ExactCategoricalSolver.Factor> derivedFactors = derivedFactors(analysis, model);
		Assert.assertEquals(analysis.graph().derivedFoutMaterializationActions().size(), derivedFactors.size());
		for(int actionIndex = 0; actionIndex < derivedFactors.size(); actionIndex++) {
			var action = analysis.graph().derivedFoutMaterializationActions().get(actionIndex);
			var factor = derivedFactors.get(actionIndex);
			var producer = model.domains().stream()
				.filter(domain -> domain.node().key() == action.key().producer()).findFirst().orElseThrow();
			var compatibility = DerivedFoutAnchorCompatibility.prepare(analysis, action);
			List<ExactCategoricalSolver.Variable> expectedScope = new ArrayList<>();
			expectedScope.add(producer.variable());
			for(var owner : compatibility.supportOwners()) {
				var domain = model.domains().stream()
					.filter(candidate -> candidate.node().key() == owner).findFirst().orElseThrow();
				if(!expectedScope.contains(domain.variable())) expectedScope.add(domain.variable());
			}
			Assert.assertTrue("derived anchor factor must expose prefix truth", factor.supportsPartialTruth());
			Assert.assertTrue("derived anchor factor scope", sameIdentityScope(factor.scope(), expectedScope));
			int[] prefix = new int[factor.scope().size()];
			{
				checkedFactors++;
				int cells = factor.scope().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
					.reduce(1, Math::multiplyExact);
				Assert.assertTrue("bounded fixture", cells <= 500_000);
				logicalLeaves += cells;
				var frozen = ExactCategoricalSolver.freezeValidatedFactor(factor);
				int[] values = new int[factor.scope().size()];
				for(int cell = 0; cell < cells; cell++) {
					long expected = Double.doubleToRawLongBits(legacyCost(
						analysis, action, compatibility, byVariable, factor.scope(), values));
					Assert.assertEquals("legacy cell=" + cell, expected,
						Double.doubleToRawLongBits(factor.cost(values)));
					Assert.assertEquals("cell=" + cell,
						Double.doubleToRawLongBits(factor.cost(values)),
						Double.doubleToRawLongBits(frozen.denseCostAt(cell)));
					increment(values, factor.scope());
				}
				Arrays.fill(prefix, -1);
				for(int producerValue = 0; producerValue < factor.scope().get(0).domainSize(); producerValue++) {
					prefix[0] = producerValue;
					var truth = factor.partialTruth(prefix);
					var selected = byVariable.get(factor.scope().get(0)).alternatives().get(producerValue);
					var expected = selected.derivedFoutAction() == action
						? ExactCategoricalSolver.PartialTruth.UNKNOWN
						: ExactCategoricalSolver.PartialTruth.ALL_ZERO;
					Assert.assertEquals(expected, truth);
					if(truth == ExactCategoricalSolver.PartialTruth.ALL_ZERO) {
						long suffix = 1;
						for(int axis = 1; axis < factor.scope().size(); axis++)
							suffix *= factor.scope().get(axis).domainSize();
						certifiedLeaves += suffix;
						assertAllCompletionsZero(factor, prefix, 1);
					}
				}
				Arrays.fill(prefix, -1);
				Assert.assertEquals(ExactCategoricalSolver.PartialTruth.UNKNOWN, factor.partialTruth(prefix));
			}
		}
		System.out.println("DERIVED_ANCHOR_PARTIAL_EVIDENCE|factors=" + checkedFactors
			+ "|logicalLeaves=" + logicalLeaves + "|certifiedLeaves=" + certifiedLeaves);
		Assert.assertTrue("fixture must expose derived anchor factors", checkedFactors > 0);
		Assert.assertTrue("fixture must certify inactive Cartesian suffixes", certifiedLeaves > 0);
		assertOrderedReceiptViewUsesOwnerIdentity(analysis, model);
	}

	@Test public void fixedPoolCircuitIsExistentiallyEquivalentForEveryCanonicalLeaf() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		List<ExactPhysicalModel.HardFactorEncoding> tinyEncodings = model.hardFactorEncodings().stream()
			.filter(encoding -> encoding.decomposition().descriptor()
				.startsWith("derived-fout-anchor|"))
			.toList();
		Assert.assertTrue("tiny derived factors must retain the cheaper canonical form",
			tinyEncodings.isEmpty());
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains = new IdentityHashMap<>();
		for(var domain : model.domains()) domains.put(domain.node().key(), domain);
		List<ExactCategoricalSolver.Factor> canonicalFactors = derivedFactors(analysis, model);
		long checked = 0;
		java.util.Set<String> globalAuxiliaryKeys = new java.util.HashSet<>();
		for(int actionIndex = 0; actionIndex < canonicalFactors.size(); actionIndex++) {
			var action = analysis.graph().derivedFoutMaterializationActions().get(actionIndex);
			ExactCategoricalSolver.Factor original = canonicalFactors.get(actionIndex);
			var dummy = new ExactCategoricalSolver.Variable("derived-fout-test-dummy=" + actionIndex, 1024);
			List<ExactCategoricalSolver.Variable> inflatedScope = new ArrayList<>(original.scope());
			inflatedScope.add(dummy);
			ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(
				inflatedScope, values -> original.cost(Arrays.copyOf(values, values.length - 1)));
			var producer = domains.get(action.key().producer());
			var encoded = ExactDerivedFoutAnchorEncoding.create("test=" + actionIndex,
				analysis, action, DerivedFoutAnchorCompatibility.prepare(analysis, action),
				producer, domains, canonical);
			Assert.assertNotNull("inflated canonical factor should use the bounded circuit", encoded);
			var repeated = ExactDerivedFoutAnchorEncoding.create("test=" + actionIndex,
				analysis, action, DerivedFoutAnchorCompatibility.prepare(analysis, action),
				producer, domains, canonical);
			Assert.assertEquals(encoded.descriptor(), repeated.descriptor());
			Assert.assertEquals(encoded.auxiliaryVariables().stream()
				.map(ExactCategoricalSolver.Variable::key).toList(), repeated.auxiliaryVariables().stream()
				.map(ExactCategoricalSolver.Variable::key).toList());
			Assert.assertTrue(encoded.encodedCells() < encoded.canonicalCells());
			Assert.assertEquals(encoded.auxiliaryVariables().size(),
				encoded.auxiliaryVariables().stream().map(ExactCategoricalSolver.Variable::key).distinct().count());
			for(var auxiliary : encoded.auxiliaryVariables())
				Assert.assertTrue("auxiliary key must be unique across actions: " + auxiliary.key(),
					globalAuxiliaryKeys.add(auxiliary.key()));
			for(var factor : encoded.solverFactors()) {
				Assert.assertTrue("bounded circuit arity", factor.scope().size() <= 3);
				long cells = 1;
				for(var variable : factor.scope())
					cells = Math.multiplyExact(cells, variable.domainSize());
				Assert.assertTrue("bounded circuit cells=" + cells, cells <= Integer.MAX_VALUE);
				int[] factorValues = new int[factor.scope().size()];
				for(int cell = 0; cell < cells; cell++) {
					double cost = factor.cost(factorValues);
					Assert.assertTrue("hard circuit cost", Double.doubleToRawLongBits(cost) == 0L
						|| cost == Double.POSITIVE_INFINITY);
					increment(factorValues, factor.scope());
				}
			}
			int[] values = new int[original.scope().size()];
			int cells = original.scope().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			for(int cell = 0; cell < cells; cell++) {
				int[] inflatedValues = Arrays.copyOf(values, values.length + 1);
				boolean expected = Double.isFinite(canonical.cost(inflatedValues));
				Assert.assertEquals("encoded leaf=" + cell + " descriptor=" + encoded.descriptor(),
					expected, encodedFinite(canonical, encoded, inflatedValues));
				increment(values, original.scope());
				checked++;
			}
			assertEncodedOptimizationParity(analysis, action, domains, producer,
				canonical, encoded);
		}
		Assert.assertTrue(checked > 0);
	}

	private static void assertEncodedOptimizationParity(PlacementAnalysis analysis,
		DerivedFoutMaterializationAction action,
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains,
		ExactPhysicalModel.DecisionDomain producer, ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result encoded) {
		List<ExactCategoricalSolver.Factor> objective = new ArrayList<>();
		int producerAxis = canonical.scope().indexOf(producer.variable());
		int activeValue = java.util.stream.IntStream.range(0, producer.alternatives().size())
			.filter(value -> producer.alternatives().get(value).derivedFoutAction() == action)
			.findFirst().orElseThrow();
		for(int axis = 0; axis < canonical.scope().size(); axis++) {
			var variable = canonical.scope().get(axis);
			final int position = axis;
			objective.add(ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
				if(position == producerAxis && values[0] != activeValue)
					return Double.POSITIVE_INFINITY;
				return (double)(position + 1) * values[0];
			}));
		}
		List<ExactCategoricalSolver.Factor> canonicalFactors = new ArrayList<>(objective);
		canonicalFactors.add(canonical);
		var explicit = ExactCategoricalSolver.solve(canonical.scope(), canonicalFactors,
			PARITY_LIMITS, (variable, value) -> 0L);
		List<ExactCategoricalSolver.Variable> encodedVariables = new ArrayList<>(canonical.scope());
		encodedVariables.addAll(encoded.auxiliaryVariables());
		List<ExactCategoricalSolver.Factor> encodedFactors = new ArrayList<>(objective);
		encodedFactors.addAll(encoded.solverFactors());
		var compact = ExactCategoricalSolver.solve(encodedVariables, encodedFactors,
			PARITY_LIMITS, (variable, value) -> 0L);
		Assert.assertEquals(Double.doubleToRawLongBits(explicit.objective()),
			Double.doubleToRawLongBits(compact.objective()));
		Assert.assertEquals(explicit.assignmentInVariableOrder(),
			compact.assignmentInVariableOrder().subList(0, canonical.scope().size()));
		var owner = domains.get(action.key().durableAnchorOwner());
		int ownerAxis = canonical.scope().indexOf(owner.variable());
		Assert.assertTrue(ownerAxis >= 0);
		CandidateSelectionReceipt explicitReceipt = receipt(analysis,
			owner.alternatives().get(explicit.assignmentInVariableOrder().get(ownerAxis)));
		CandidateSelectionReceipt compactReceipt = receipt(analysis,
			owner.alternatives().get(compact.assignmentInVariableOrder().get(ownerAxis)));
		Assert.assertNotNull(explicitReceipt);
		Assert.assertEquals(explicitReceipt.normalizedSignature(), compactReceipt.normalizedSignature());
	}

	@Test public void proofCircuitKeysRetainCompiledOwnerIdentity() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		for(var domain : model.domains()) for(var alternative : domain.alternatives()) {
			CandidateSelectionReceipt receipt = receipt(analysis, alternative);
			if(receipt == null) continue;
			CompiledHopKey owner = domain.node().key();
			CompiledHopKey equalCopy = new CompiledHopKey(owner.programFingerprint(), owner.functionNamespace(),
				owner.callSitePath(), owner.recompileContext(), owner.controlRegion(),
				owner.emittedHopInstance(), owner.canonicalSourceOrigin());
			Assert.assertEquals(owner, equalCopy);
			CandidateRealizationReference reference = CandidateRealizationReference.of(
				receipt.rule(), receipt.realization());
			ProofQuery original = new ProofQuery(reference, owner, owner, null);
			ProofQuery foreign = new ProofQuery(reference, equalCopy, owner, null);
			Class<?> keyType = Class.forName(ExactDerivedFoutAnchorEncoding.class.getName() + "$QueryKey");
			var constructor = keyType.getDeclaredConstructor(ProofQuery.class);
			constructor.setAccessible(true);
			Object originalKey = constructor.newInstance(original);
			Object foreignKey = constructor.newInstance(foreign);
			Assert.assertNotEquals("structurally equal foreign owner must retain separate authority",
				originalKey, foreignKey);
			return;
		}
		Assert.fail("fixture must expose a candidate receipt");
	}

	@Test public void localAndExactRetainCanonicalObjectiveAndSelection() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel canonicalModel = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel encodedModel =
			ExactPhysicalModel.buildWithForcedDerivedFoutEncodingForTest(analysis);
		Assert.assertFalse(encodedModel.hardFactorEncodings().stream().filter(encoding ->
			encoding.decomposition().descriptor().startsWith("derived-fout-anchor|")).toList().isEmpty());
		var canonicalSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, canonicalModel);
		var encodedSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, encodedModel);
		var local = LocalPhysicalOptimizer.optimize(canonicalModel, canonicalSurface).physicalResult();
		var canonical = ExactPhysicalOptimizer.optimize(canonicalModel, canonicalSurface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var encoded = ExactPhysicalOptimizer.optimize(encodedModel, encodedSurface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(local.canonicalObjectiveBits(), canonical.canonicalObjectiveBits());
		Assert.assertEquals(canonical.canonicalObjectiveBits(), encoded.canonicalObjectiveBits());
		Assert.assertEquals(local.solverResult().assignmentInVariableOrder(),
			canonical.solverResult().assignmentInVariableOrder());
		Assert.assertEquals(canonical.solverResult().assignmentInVariableOrder(),
			encoded.solverResult().assignmentInVariableOrder().subList(0, canonicalModel.domains().size()));
		ExactPhysicalSelection canonicalSelection = ExactPhysicalSelection.create(canonicalModel, canonical);
		ExactPhysicalSelection encodedSelection = ExactPhysicalSelection.create(encodedModel, encoded);
		Assert.assertEquals(canonicalSelection.objectiveBits(), encodedSelection.objectiveBits());
		Assert.assertEquals(canonicalSelection.assignmentInDecisionOrder(),
			encodedSelection.assignmentInDecisionOrder());
		Assert.assertEquals(canonicalSelection.candidateReceipts().stream()
			.map(CandidateSelectionReceipt::normalizedSignature).toList(),
			encodedSelection.candidateReceipts().stream()
				.map(CandidateSelectionReceipt::normalizedSignature).toList());
		for(int index = 0; index < canonicalSelection.candidateReceipts().size(); index++)
			Assert.assertSame("canonical receipt authority position=" + index,
				canonicalSelection.candidateReceipts().get(index),
				encodedSelection.candidateReceipts().get(index));
		Assert.assertEquals(canonicalSelection.relocationChoices(), encodedSelection.relocationChoices());
		Assert.assertEquals(canonicalSelection.emittedRelocations(), encodedSelection.emittedRelocations());
	}

	@Test public void cyclicGroundingRequiresAnExactOrInvariantExit() {
		Assert.assertFalse("pure self-loop cannot prove grounding",
			ExactDerivedFoutAnchorEncoding.groundingCircuitFeasibleForTest(
				new boolean[] {false}, new int[][] {{0}}));
		Assert.assertFalse("pure two-node cycle cannot prove grounding",
			ExactDerivedFoutAnchorEncoding.groundingCircuitFeasibleForTest(
				new boolean[] {false, false}, new int[][] {{1}, {0}}));
		Assert.assertTrue("cycle with an exact external exit is grounded",
			ExactDerivedFoutAnchorEncoding.groundingCircuitFeasibleForTest(
				new boolean[] {false, false, true}, new int[][] {{1}, {0, 2}, {}}));
		Assert.assertTrue("a terminal inside an SCC needs no cyclic witness",
			ExactDerivedFoutAnchorEncoding.groundingCircuitFeasibleForTest(
				new boolean[] {true}, new int[][] {{0}}));
		Assert.assertFalse("every conjunctive child must independently ground",
			ExactDerivedFoutAnchorEncoding.groundingCircuitFeasibleForTest(
				new boolean[] {false, true, false}, new int[][] {{1, 2}, {}, {2}}));
	}

	private static boolean encodedFinite(ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result encoded, int[] values) {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(canonical.scope());
		variables.addAll(encoded.auxiliaryVariables());
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(encoded.solverFactors());
		for(int position = 0; position < values.length; position++) {
			int expected = values[position];
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(canonical.scope().get(position)), selected ->
					selected[0] == expected ? 0d : Double.POSITIVE_INFINITY));
		}
		try {
			return Double.isFinite(ExactCategoricalSolver.solve(
				variables, factors, PARITY_LIMITS, (variable, value) -> 0L).objective());
		}
		catch(IllegalArgumentException failure) {
			String message = String.valueOf(failure.getMessage());
			if(!message.contains("INFEASIBLE") && !message.contains("NO_FEASIBLE_ASSIGNMENT"))
				throw failure;
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ExactCategoricalSolver.Factor> derivedFactors(PlacementAnalysis analysis,
		ExactPhysicalModel model) throws Exception {
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains = new IdentityHashMap<>();
		for(var domain : model.domains()) domains.put(domain.node().key(), domain);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
			new IdentityHashMap<>();
		var method = ExactPhysicalModel.class.getDeclaredMethod("addDerivedFoutAnchorFactors",
			PlacementAnalysis.class, Map.class, List.class, Map.class, boolean.class);
		method.setAccessible(true);
		method.invoke(null, analysis, domains, factors, encodings, false);
		return factors;
	}

	@SuppressWarnings("unchecked")
	private static void assertOrderedReceiptViewUsesOwnerIdentity(PlacementAnalysis analysis,
		ExactPhysicalModel model) throws Exception {
		for(var domain : model.domains()) for(var alternative : domain.alternatives()) {
			CandidateSelectionReceipt receipt = receipt(analysis, alternative);
			if(receipt == null) continue;
			CompiledHopKey owner = domain.node().key();
			CompiledHopKey equalCopy = new CompiledHopKey(owner.programFingerprint(), owner.functionNamespace(),
				owner.callSitePath(), owner.recompileContext(), owner.controlRegion(),
				owner.emittedHopInstance(), owner.canonicalSourceOrigin());
			Assert.assertEquals(owner, equalCopy);
			Assert.assertNotSame(owner, equalCopy);
			Class<?> type = Class.forName(ExactPhysicalModel.class.getName() + "$OrderedReceiptMap");
			var constructor = type.getDeclaredConstructor(CompiledHopKey[].class,
				CandidateSelectionReceipt[].class);
			constructor.setAccessible(true);
			Map<CompiledHopKey,CandidateSelectionReceipt> view =
				(Map<CompiledHopKey,CandidateSelectionReceipt>) constructor.newInstance(
					new CompiledHopKey[] {owner}, new CandidateSelectionReceipt[] {receipt});
			Assert.assertSame(receipt, view.get(owner));
			Assert.assertNull("equal foreign owner must not acquire identity authority", view.get(equalCopy));
			return;
		}
		Assert.fail("fixture must expose a candidate receipt");
	}

	private static double legacyCost(PlacementAnalysis analysis, DerivedFoutMaterializationAction action,
		DerivedFoutAnchorCompatibility.Prepared compatibility,
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> byVariable,
		List<ExactCategoricalSolver.Variable> scope, int[] values) {
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		ExactPhysicalModel.Alternative selectedProducer = null;
		ExactPhysicalModel.Alternative selectedOwner = null;
		for(int index = 0; index < scope.size(); index++) {
			var domain = byVariable.get(scope.get(index));
			var alternative = domain.alternatives().get(values[index]);
			if(domain.node().key() == action.key().producer()) selectedProducer = alternative;
			if(domain.node().key() == action.key().durableAnchorOwner()) selectedOwner = alternative;
			CandidateSelectionReceipt receipt = receipt(analysis, alternative);
			if(receipt != null) selected.put(domain.node().key(), receipt);
		}
		if(selectedProducer == null || selectedProducer.derivedFoutAction() != action) return 0.0;
		return selectedOwner != null
			&& selectedOwner.decision() == action.key().durableAnchorOwner()
			&& selectedOwner.state().output() == FederatedOutput.FOUT
			&& selectedOwner.state().fType() == action.key().durableAnchorOwnerFType()
			&& compatibility.matches(selected, false) ? 0.0 : Double.POSITIVE_INFINITY;
	}

	private static CandidateSelectionReceipt receipt(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative) {
		var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
		return rule == null || emission == null || alternative.realization() == null ? null
			: analysis.canonicalCandidateReceipt(rule.key(), emission, alternative.realization(),
				alternative.supportClause());
	}

	private static PlacementAnalysis analysis() throws Exception {
		String script = """
			inner=function(matrix[double] Y, double k) return(double out) {
			  if(k>0) {out=sum(abs(Y));} else {out=sum(Y);}
			}
			Y=federated(addresses=list("localhost:1234/Y1","localhost:1235/Y2"),
			  ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));
			Y=cbind(1-Y,Y); s=inner(Y,sum(Y)); print(s);
			""";
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static boolean sameIdentityScope(List<ExactCategoricalSolver.Variable> left,
		List<ExactCategoricalSolver.Variable> right) {
		if(left.size() != right.size()) return false;
		for(int index = 0; index < left.size(); index++) if(left.get(index) != right.get(index)) return false;
		return true;
	}

	private static void assertAllCompletionsZero(ExactCategoricalSolver.Factor factor,
		int[] values, int position) {
		if(position == values.length) {
			Assert.assertEquals(0L, Double.doubleToRawLongBits(factor.cost(values)));
			return;
		}
		for(int value = 0; value < factor.scope().get(position).domainSize(); value++) {
			values[position] = value;
			assertAllCompletionsZero(factor, values, position + 1);
		}
		values[position] = -1;
	}

	private static void increment(int[] values, List<ExactCategoricalSolver.Variable> scope) {
		for(int index = values.length - 1; index >= 0; index--) {
			if(++values[index] < scope.get(index).domainSize()) return;
			values[index] = 0;
		}
	}
}

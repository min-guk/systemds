/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact parity regressions for the bounded-scope joint alignment encoding. */
public class JointCompactAlignmentTest {
	private static final long MAX_EXHAUSTIVE_CELLS = 5_000_000;
	private static final ExactCategoricalSolver.Limits PARITY_LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 20_000_000);
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";
	private static final String SQUARE_SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,8),list(4,0),list(8,8)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,8),list(4,0),list(8,8)));"
			+ "p=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void correlatedBranchRowsPreserveLegalityCostAndSelection() throws Exception {
		assertCompactParity(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));", true, false);
	}

	@Test
	public void independentBranchRowsRemainForbiddenWithoutMovement() throws Exception {
		assertCompactParity(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));", false, false);
	}

	@Test
	public void loopCarriedValueOriginsPreserveLegalityCostAndSelection() throws Exception {
		assertCompactParity(SOURCES + "A=X;B=X;i=1;while(i<=2){"
			+ "if(p>0.5){A=Y;}else{A=A;}B=A;i=i+1;}"
			+ "C=A+B;print(sum(C));", true, true);
	}

	@Test
	public void loopCycleCannotSatisfyItsOwnPoolWitness() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES + "A=X;B=X;i=1;while(i<=2){"
			+ "T=A;A=B;B=T;D=A+B;A=D;B=D;i=i+1;}"
			+ "C=A+B;print(sum(C));");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var cyclic = model.hardFactorEncodings().stream()
			.filter(encoding -> encoding.decomposition().descriptor().startsWith("joint-value-map|"))
			.filter(encoding -> encoding.decomposition().auxiliaryVariables().stream()
				.anyMatch(variable -> variable.key().contains("|rank")))
			.findFirst().orElseThrow(() -> new AssertionError(
				"initialized alias loop must exercise an SCC rank witness"));
		var canonical = cyclic.canonicalFactor();
		var encoded = cyclic.decomposition();
		List<ExactCategoricalSolver.Factor> withoutRankDescent = encoded.solverFactors().stream()
			.filter(factor -> factor.scope().stream()
				.noneMatch(variable -> variable.key().contains("|rank")))
			.toList();
		Assert.assertTrue("fixture must contain a rank-descent constraint",
			withoutRankDescent.size() < encoded.solverFactors().size());
		long canonicalCells = cells(canonical.scope());
		Assert.assertTrue("bounded cyclic canonical fixture: " + canonicalCells,
			canonicalCells <= MAX_EXHAUSTIVE_CELLS);
		int[] values = new int[canonical.scope().size()];
		boolean exposedSelfProof = false;
		for(long cell = 0; cell < canonicalCells && !exposedSelfProof; cell++) {
			decode(canonical.scope(), cell, values);
			if(Double.isFinite(canonical.cost(values)))
				continue;
			boolean guarded = encodedFinite(canonical, encoded, encoded.solverFactors(), values);
			boolean unguarded = encodedFinite(canonical, encoded, withoutRankDescent, values);
			exposedSelfProof = !guarded && unguarded;
		}
		Assert.assertTrue("rank descent must reject at least one aligned cyclic self-proof",
			exposedSelfProof);
	}

	@Test
	public void repeatedFunctionCallOriginsPreserveLegalityCostAndSelection() throws Exception {
		assertCompactParity(
			"f=function(matrix[double] A,matrix[double] B) return (matrix[double] C){C=A+B;}"
				+ SOURCES + "C1=f(X,X);C2=f(Y,Y);print(sum(C1)+sum(C2));", true, false);
	}

	@Test
	public void narrowRepeatedReaderJointKeepsLegacyEncoding() throws Exception {
		PlacementAnalysis analysis = analysis(SQUARE_SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}C=A%*%A;print(sum(C));");
		List<JointValueMapRelations.Relation> relations = JointValueMapRelations.from(analysis);
		Assert.assertEquals("repeated matrix input must form one joint relation", 1, relations.size());
		var relation = relations.get(0);
		List<?> supportOwners = JointValueMapRelations.supportOwners(analysis, relation);
		Assert.assertEquals("the repeated reader is the only dynamic support owner", 1,
			supportOwners.size());

		ExactPhysicalModel legacy = ExactPhysicalModel.buildWithLegacyJointEncodingForTest(analysis);
		ExactPhysicalModel actual = ExactPhysicalModel.build(analysis);
		var reader = actual.domains().stream()
			.filter(domain -> domain.node().key() == relation.readers().get(0)).findFirst().orElseThrow();
		Assert.assertTrue("branch-selected repeated reader must retain VALUE_MAP alternatives",
			reader.alternatives().stream().anyMatch(alternative -> alternative.realization() != null
				&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP));
		var consumer = actual.domains().stream()
			.filter(domain -> domain.node().key() == relation.consumer()).findFirst().orElseThrow();
		Assert.assertTrue("matrix multiply must retain a multi-physical-input alternative",
			consumer.alternatives().stream().anyMatch(alternative -> alternative.inputAuthorities().stream()
				.filter(authority -> authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT
					|| authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
				.map(ExactPhysicalModel.InputAuthority::inputPosition).distinct().count() > 1));
		Assert.assertEquals("consumer plus its repeated reader forms a two-variable canonical joint", 2,
			1 + supportOwners.size());

		assertSameDecisionSpace(legacy, actual);
		assertSameCanonicalHardPredicates(legacy, actual);
		assertCanonicalCostSurface(analysis, legacy, actual);
		Assert.assertEquals(legacy.exactSolverAuxiliaryVariables().size(),
			actual.exactSolverAuxiliaryVariables().size());
		Assert.assertEquals(legacy.exactSolverHardFactors().size(), actual.exactSolverHardFactors().size());
		Assert.assertEquals(maximumScope(legacy.exactSolverHardFactors()),
			maximumScope(actual.exactSolverHardFactors()));
		Assert.assertEquals(legacy.exactSolverHardFactorDescriptors(),
			actual.exactSolverHardFactorDescriptors());
		Assert.assertTrue("narrow joint must not introduce a pool-witness circuit",
			actual.exactSolverHardFactorDescriptors().stream()
				.noneMatch(descriptor -> descriptor.contains("encoding=pool-witness")));

		var oldSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, legacy);
		var newSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, actual);
		var oldResult = ExactPhysicalOptimizer.optimize(
			legacy, oldSurface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var newResult = ExactPhysicalOptimizer.optimize(
			actual, newSurface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(oldResult.canonicalObjectiveBits(), newResult.canonicalObjectiveBits());
		Assert.assertEquals(selectedSignatures(legacy, oldResult), selectedSignatures(actual, newResult));
	}

	private static void assertCompactParity(String script, boolean feasible,
		boolean expectStrictScopeReduction) throws Exception {
		PlacementAnalysis analysis = analysis(script);
		ExactPhysicalModel legacy =
			ExactPhysicalModel.buildWithLegacyJointEncodingForTest(analysis);
		ExactPhysicalModel compact = ExactPhysicalModel.build(analysis);
		assertSameDecisionSpace(legacy, compact);
		assertSameCanonicalHardPredicates(legacy, compact);
		assertJointEncodingTruth(analysis, compact, expectStrictScopeReduction);
		assertCanonicalCostSurface(analysis, legacy, compact);

		if(!feasible) {
			assertPhysicalInfeasible(analysis, legacy);
			assertPhysicalInfeasible(analysis, compact);
			return;
		}
		var oldSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, legacy);
		var newSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, compact);
		var oldResult = ExactPhysicalOptimizer.optimize(
			legacy, oldSurface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var newResult = ExactPhysicalOptimizer.optimize(
			compact, newSurface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals("compact joint encoding changed canonical cost",
			oldResult.canonicalObjectiveBits(), newResult.canonicalObjectiveBits());
		Assert.assertEquals("compact joint encoding changed selected decisions",
			selectedSignatures(legacy, oldResult), selectedSignatures(compact, newResult));
	}

	private static void assertJointEncodingTruth(PlacementAnalysis analysis, ExactPhysicalModel model,
		boolean expectStrictScopeReduction) {
		List<ExactPhysicalModel.HardFactorEncoding> joint = model.hardFactorEncodings().stream()
			.filter(encoding -> encoding.decomposition().descriptor().startsWith("joint-value-map|"))
			.toList();
		if(joint.isEmpty()) {
			Assert.assertFalse("wide fixture must retain a compact joint encoding",
				expectStrictScopeReduction);
			Assert.assertFalse("fixture must exercise a joint alignment relation",
				JointValueMapRelations.from(analysis).isEmpty());
			return;
		}
		boolean reducedScope = false;
		for(var encoding : joint) {
			var canonical = encoding.canonicalFactor();
			var decomposition = encoding.decomposition();
			long canonicalCells = cells(canonical.scope());
			Assert.assertTrue("bounded canonical joint fixture: " + canonicalCells,
				canonicalCells <= MAX_EXHAUSTIVE_CELLS);
			boolean compact = canonical.scope().size() > 3;
			Assert.assertEquals("only wide joint factors use the pool-witness circuit: "
				+ decomposition.descriptor(), compact,
				decomposition.descriptor().contains("encoding=pool-witness"));
			Assert.assertEquals("wide circuits have no positional observation star",
				compact, decomposition.observations().isEmpty());
			int maximumEncodedScope = decomposition.solverFactors().stream()
				.mapToInt(factor -> factor.scope().size()).max().orElse(0);
			Assert.assertTrue("compact joint factors must remain small-scope: "
				+ decomposition.descriptor(), maximumEncodedScope <= 3);
			if(canonical.scope().size() > 3)
				Assert.assertTrue("wide canonical joint predicate must reduce maximum scope",
					maximumEncodedScope < canonical.scope().size());
			reducedScope |= maximumEncodedScope < canonical.scope().size();
			assertExistentialParity(canonical, decomposition);
		}
		if(expectStrictScopeReduction)
			Assert.assertTrue("recursive alias-loop joint predicate must reduce maximum scope",
				reducedScope);
	}

	private static int maximumScope(List<ExactCategoricalSolver.Factor> factors) {
		return factors.stream().mapToInt(factor -> factor.scope().size()).max().orElse(0);
	}

	private static void assertExistentialParity(ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result encoded) {
		int[] canonicalValues = new int[canonical.scope().size()];
		long canonicalCells = cells(canonical.scope());
		for(long cell = 0; cell < canonicalCells; cell++) {
			decode(canonical.scope(), cell, canonicalValues);
			boolean encodedFinite = encodedFinite(
				canonical, encoded, encoded.solverFactors(), canonicalValues);
			Assert.assertEquals("existential compact truth changed at canonical cell " + cell,
				Double.isFinite(canonical.cost(canonicalValues)), encodedFinite);
		}
	}

	private static boolean encodedFinite(ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result encoded,
		List<ExactCategoricalSolver.Factor> encodedFactors, int[] canonicalValues) {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(canonical.scope());
		variables.addAll(encoded.auxiliaryVariables());
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(encodedFactors);
		for(int position = 0; position < canonicalValues.length; position++) {
			int expected = canonicalValues[position];
			factors.add(ExactCategoricalSolver.Factor.lazy(
				List.of(canonical.scope().get(position)),
				values -> values[0] == expected ? 0.0 : Double.POSITIVE_INFINITY));
		}
		try {
			return Double.isFinite(ExactCategoricalSolver.solve(
				variables, factors, PARITY_LIMITS, (variable, value) -> value).objective());
		}
		catch(IllegalArgumentException failure) {
			String message = String.valueOf(failure.getMessage());
			if(!message.contains("INFEASIBLE") && !message.contains("NO_FEASIBLE_ASSIGNMENT"))
				throw failure;
			return false;
		}
	}

	private static void assertSameDecisionSpace(ExactPhysicalModel expected,
		ExactPhysicalModel actual) {
		Assert.assertEquals(expected.domains().size(), actual.domains().size());
		for(int ordinal = 0; ordinal < expected.domains().size(); ordinal++) {
			var left = expected.domains().get(ordinal);
			var right = actual.domains().get(ordinal);
			Assert.assertSame(left.node().key(), right.node().key());
			Assert.assertEquals(left.alternatives().stream()
				.map(ExactPhysicalModel.Alternative::signature).toList(), right.alternatives().stream()
					.map(ExactPhysicalModel.Alternative::signature).toList());
		}
	}

	private static void assertSameCanonicalHardPredicates(ExactPhysicalModel expected,
		ExactPhysicalModel actual) {
		Assert.assertEquals(expected.hardFactors().size(), actual.hardFactors().size());
		var jointOrdinals = actual.hardFactorEncodings().stream()
			.filter(encoding -> encoding.decomposition().descriptor().startsWith("joint-value-map|"))
			.map(ExactPhysicalModel.HardFactorEncoding::canonicalOrdinal)
			.collect(java.util.stream.Collectors.toSet());
		for(int ordinal = 0; ordinal < expected.hardFactors().size(); ordinal++) {
			var left = expected.hardFactors().get(ordinal);
			var right = actual.hardFactors().get(ordinal);
			Assert.assertEquals(left.scope().stream().map(ExactCategoricalSolver.Variable::key).toList(),
				right.scope().stream().map(ExactCategoricalSolver.Variable::key).toList());
			if(!jointOrdinals.contains(ordinal))
				continue;
			long factorCells = cells(left.scope());
			Assert.assertTrue("bounded canonical joint factor at ordinal " + ordinal + ": "
				+ factorCells, factorCells <= MAX_EXHAUSTIVE_CELLS);
			int[] values = new int[left.scope().size()];
			for(long cell = 0; cell < factorCells; cell++) {
				decode(left.scope(), cell, values);
				Assert.assertEquals("canonical hard truth changed at factor=" + ordinal
					+ ",cell=" + cell, Double.doubleToRawLongBits(left.cost(values)),
					Double.doubleToRawLongBits(right.cost(values)));
			}
		}
	}

	private static void assertCanonicalCostSurface(PlacementAnalysis analysis,
		ExactPhysicalModel expected, ExactPhysicalModel actual) {
		var left = ExactPhysicalCostModel.physicalCostSurface(analysis, expected);
		var right = ExactPhysicalCostModel.physicalCostSurface(analysis, actual);
		Assert.assertEquals(left.contributions().size(), right.contributions().size());
		for(int ordinal = 0; ordinal < left.contributions().size(); ordinal++) {
			var expectedContribution = left.contributions().get(ordinal);
			var actualContribution = right.contributions().get(ordinal);
			Assert.assertEquals(expectedContribution.id(), actualContribution.id());
			var expectedFactor = expectedContribution.factor();
			var actualFactor = actualContribution.factor();
			Assert.assertEquals(expectedFactor.scope().stream()
				.map(ExactCategoricalSolver.Variable::key).toList(), actualFactor.scope().stream()
					.map(ExactCategoricalSolver.Variable::key).toList());
			long factorCells = cells(expectedFactor.scope());
			Assert.assertTrue("bounded canonical cost contribution at ordinal " + ordinal + ": "
				+ factorCells, factorCells <= MAX_EXHAUSTIVE_CELLS);
			int[] values = new int[expectedFactor.scope().size()];
			for(long cell = 0; cell < factorCells; cell++) {
				decode(expectedFactor.scope(), cell, values);
				Assert.assertEquals("canonical monetary table changed at contribution=" + ordinal
					+ ",cell=" + cell, Double.doubleToRawLongBits(expectedFactor.cost(values)),
					Double.doubleToRawLongBits(actualFactor.cost(values)));
			}
		}
	}

	private static List<String> selectedSignatures(ExactPhysicalModel model,
		ExactPhysicalOptimizer.Result result) {
		List<String> signatures = new ArrayList<>();
		for(int index = 0; index < model.domains().size(); index++)
			signatures.add(model.domains().get(index).alternatives()
				.get(result.solverResult().assignmentInVariableOrder().get(index)).signature());
		return signatures;
	}

	private static void assertPhysicalInfeasible(PlacementAnalysis analysis,
		ExactPhysicalModel model) {
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		String message = String.valueOf(failure.getMessage());
		Assert.assertFalse("overflow is not proof of physical infeasibility: " + message,
			message.contains("OVERFLOW") || message.contains("LIMIT_EXCEEDED"));
		Assert.assertTrue("expected physical infeasibility: " + message,
			message.contains("INFEASIBLE") || message.contains("NO_FEASIBLE_ASSIGNMENT"));
	}

	private static long cells(List<ExactCategoricalSolver.Variable> variables) {
		long cells = 1;
		for(var variable : variables) {
			if(cells > MAX_EXHAUSTIVE_CELLS / variable.domainSize())
				return MAX_EXHAUSTIVE_CELLS + 1;
			cells *= variable.domainSize();
		}
		return cells;
	}

	private static void decode(List<ExactCategoricalSolver.Variable> variables,
		long cell, int[] values) {
		long remainder = cell;
		for(int position = values.length - 1; position >= 0; position--) {
			int radix = variables.get(position).domainSize();
			values[position] = (int) (remainder % radix);
			remainder /= radix;
		}
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

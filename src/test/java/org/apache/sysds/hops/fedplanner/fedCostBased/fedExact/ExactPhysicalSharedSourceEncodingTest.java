/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class ExactPhysicalSharedSourceEncodingTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(10_000, 100_000);
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL, false);
	private static final PlacementRealizationKey LOCAL_LAYOUT =
		PlacementRealizationKey.local(LOCAL_EMISSION);

	@Test
	public void fullRectangleEmitsExecutableMembershipFactorsAndRetainsFibers() {
		Object h = new Object(), a = new Object(), b = new Object();
		Object a0 = new Object(), a1 = new Object(), b0 = new Object(), b1 = new Object();
		var rows = List.of(row(3, h, a, a0, b, b0), row(7, h, a, a0, b, b1),
			row(11, h, a, a1, b, b0), row(13, h, a, a1, b, b1),
			row(17, h, a, a0, b, b0));
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest(
			"rectangle", rows, List.of(a, b), List.of(), LIMITS);

		assertTrue(encoded.supported());
		assertEquals(List.of(a, b), encoded.sourceOwners());
		assertEquals(2, encoded.factors().size());
		assertEquals(List.of(3, 17), encoded.fiber(0, 0, 0));
		assertEquals(3, encoded.decode(0, 0, 0));
		for(int av = 0; av < 2; av++)
			for(int bv = 0; bv < 2; bv++) {
				assertEquals(0.0, encoded.factors().get(0).cost(new int[] {0, av}), 0.0);
				assertEquals(0.0, encoded.factors().get(1).cost(new int[] {0, bv}), 0.0);
				assertFalse(encoded.fiber(0, av, bv).isEmpty());
			}
		assertEquals(5, encoded.statistics().originalRows());
		assertEquals(4, encoded.statistics().encodedTuples());
	}

	@Test
	public void correlatedAaBbRelationFailsClosedInsteadOfInventingCrossRows() {
		Object h = new Object(), a = new Object(), b = new Object();
		Object a0 = new Object(), a1 = new Object(), b0 = new Object(), b1 = new Object();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("aa-bb",
			List.of(row(0, h, a, a0, b, b0), row(1, h, a, a1, b, b1)),
			List.of(a, b), List.of(), LIMITS);
		assertFalse(encoded.supported());
		assertEquals("NON_RECTANGULAR_HEADER", encoded.reason());
	}

	@Test
	public void repeatedSourceMustSelectOneIdentityValue() {
		Object h = new Object(), owner = new Object(), value = new Object(), other = new Object();
		var accepted = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("repeat-ok",
			List.of(new ExactPhysicalSharedSourceEncoding.RelationRow(0, h,
				List.of(selection(owner, value), selection(owner, value)))),
			List.of(owner), List.of(), LIMITS);
		assertTrue(accepted.supported());

		var rejected = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("repeat-conflict",
			List.of(new ExactPhysicalSharedSourceEncoding.RelationRow(0, h,
				List.of(selection(owner, value), selection(owner, other)))),
			List.of(owner), List.of(), LIMITS);
		assertFalse(rejected.supported());
		assertEquals("CONFLICTING_REPEATED_SOURCE", rejected.reason());
	}

	@Test
	public void absentSourceIsWildcardAndExplicitNoneRemainsAValue() {
		Object wildcard = new Object(), explicit = new Object(), owner = new Object();
		Object none = new Object(), value = new Object();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("wildcard-none",
			List.of(row(0, wildcard), row(1, explicit, owner, none), row(2, explicit, owner, value)),
			List.of(owner), List.of(), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(0, encoded.decode(0, 0));
		assertEquals(0, encoded.decode(0, 1));
		assertEquals(1, encoded.decode(1, 0));
		assertEquals(2, encoded.decode(1, 1));
	}

	@Test
	public void illegalHeaderSourceTupleIsExplicitInfinityNotSparseZero() {
		Object h0 = new Object(), h1 = new Object(), owner = new Object();
		Object value0 = new Object(), value1 = new Object();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("illegal-tuple",
			List.of(row(0, h0, owner, value0), row(1, h1, owner, value1)),
			List.of(owner), List.of(), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(0.0, encoded.factors().get(0).cost(new int[] {0, 0}), 0.0);
		assertEquals(Double.POSITIVE_INFINITY,
			encoded.factors().get(0).cost(new int[] {0, 1}), 0.0);
		assertEquals(Double.POSITIVE_INFINITY,
			encoded.factors().get(0).cost(new int[] {1, 0}), 0.0);
	}

	@Test
	public void equalLookingButIdentityDistinctOwnersAndReferencesStayDistinct() {
		Object ownerA = new String("owner"), ownerB = new String("owner");
		Object valueA = new String("value"), valueB = new String("value");
		assertEquals(ownerA, ownerB);
		assertNotSame(ownerA, ownerB);
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("identity",
			List.of(row(0, new Object(), ownerA, valueA, ownerB, valueB)),
			List.of(ownerA, ownerB), List.of(), LIMITS);
		assertTrue(encoded.supported());
		assertSame(ownerA, encoded.sourceOwners().get(0));
		assertSame(ownerB, encoded.sourceOwners().get(1));
		assertEquals(0, encoded.decode(0, 0, 0));
	}

	@Test
	public void cyclicOwnerIdentityIsRepresentedWithoutRecursiveExpansion() {
		Object decisionAndOwner = new Object(), selected = new Object();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("cycle",
			List.of(row(0, decisionAndOwner, decisionAndOwner, selected)),
			List.of(decisionAndOwner), List.of(), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(0, encoded.decode(0, 0));
	}

	@Test
	public void rawIncidencesAreRetargetedOnlyOnFiberCongruence() {
		Object h = new Object(), owner = new Object(), source = new Object();
		var rows = List.of(row(0, h, owner, source), row(1, h, owner, source));
		var good = new ExactPhysicalSharedSourceEncoding.RawIncidence("cost",
			new double[] {-0.0, -0.0});
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest(
			"raw", rows, List.of(owner), List.of(good), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(Double.doubleToRawLongBits(-0.0),
			Double.doubleToRawLongBits(encoded.factors().get(1).cost(new int[] {0, 0})));

		var bad = new ExactPhysicalSharedSourceEncoding.RawIncidence("mismatch",
			new double[] {0.0, -0.0});
		var bypass = ExactPhysicalSharedSourceEncoding.encodeRelationForTest(
			"raw-bad", rows, List.of(owner), List.of(bad), LIMITS);
		assertFalse(bypass.supported());
		assertEquals("RAW_FACTOR_FIBER_MISMATCH|mismatch", bypass.reason());
	}

	@Test
	public void thirdIncidenceAndFactorLocalViewsRemainSeparate() {
		Object h = new Object(), owner = new Object(), s0 = new Object(), s1 = new Object();
		var rows = List.of(row(0, h, owner, s0), row(1, h, owner, s1));
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("views", rows,
			List.of(owner), List.of(
				new ExactPhysicalSharedSourceEncoding.RawIncidence("local-view-a", new double[] {2, 3}),
				new ExactPhysicalSharedSourceEncoding.RawIncidence("local-view-b", new double[] {5, 7}),
				new ExactPhysicalSharedSourceEncoding.RawIncidence("third", new double[] {11, 13})), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(4, encoded.factors().size());
		assertArrayEquals(new double[] {2, 5, 11}, costs(encoded, 0), 0.0);
		assertArrayEquals(new double[] {3, 7, 13}, costs(encoded, 1), 0.0);
	}

	@Test
	public void exhaustiveOriginalAndEncodedDirectionsPreserveRelationAndRawBits() {
		Object h0 = new Object(), h1 = new Object(), a = new Object(), b = new Object();
		Object a0 = new Object(), a1 = new Object(), b0 = new Object(), b1 = new Object();
		var rows = List.of(row(0, h0, a, a0, b, b0), row(1, h0, a, a0, b, b1),
			row(2, h0, a, a1, b, b0), row(3, h0, a, a1, b, b1), row(4, h1));
		long[] bits = {0x0000000000000000L, 0x3ff0000000000000L,
			0x4000000000000000L, 0x4008000000000000L, 0x8000000000000000L};
		double[] raw = Arrays.stream(bits).mapToDouble(Double::longBitsToDouble).toArray();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("exhaustive", rows,
			List.of(a, b), List.of(new ExactPhysicalSharedSourceEncoding.RawIncidence("raw", raw)), LIMITS);
		assertTrue(encoded.supported());

		boolean[] recoveredOriginal = new boolean[rows.size()];
		for(int header = 0; header < 2; header++)
			for(int av = 0; av < 2; av++)
				for(int bv = 0; bv < 2; bv++) {
					List<Integer> fiber = encoded.fiber(header, av, bv);
					assertFalse("every projected tuple is membership-legal", fiber.isEmpty());
					double encodedRaw = encoded.factors().get(2).cost(new int[] {header, av, bv});
					for(int ordinal : fiber) {
						recoveredOriginal[ordinal] = true;
						assertEquals(Double.doubleToRawLongBits(raw[ordinal]),
							Double.doubleToRawLongBits(encodedRaw));
					}
				}
		assertArrayEquals(new boolean[] {true, true, true, true, true}, recoveredOriginal);
	}

	@Test
	public void deterministicStructuralTieUsesMinimumOriginalOrdinalNotLegacyObservationTie() {
		Object h = new Object(), owner = new Object(), source = new Object();
		var encoded = ExactPhysicalSharedSourceEncoding.encodeRelationForTest("tie",
			List.of(row(9, h, owner, source), row(4, h, owner, source)),
			List.of(owner), List.of(new ExactPhysicalSharedSourceEncoding.RawIncidence(
				"constant-truth", new double[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0})), LIMITS);
		assertTrue(encoded.supported());
		assertEquals(List.of(4, 9), encoded.fiber(0, 0));
		assertEquals(4, encoded.decode(0, 0));
		assertEquals("MINIMUM_ORIGINAL_ORDINAL", encoded.statistics().tiePolicy());
	}

	@Test
	public void productionBoundaryPreservesWholeLegacyModelAndForcedFactors() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, factor -> { });
		var forced = ExactCategoricalSolver.Factor.dense(List.of(model.variables().get(0)),
			new double[model.variables().get(0).domainSize()]);
		var encoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface,
			List.of(forced), ExactPhysicalOptimizer.PRODUCTION_LIMITS);

		assertFalse(encoded.statistics().transformed());
		assertEquals("EXTRA_HARD_FACTORS_REQUIRE_CONGRUENCE", encoded.statistics().reason());
		assertEquals(model.variables().size(), encoded.decisionPrefixCount());
		assertEquals(surface.exactSolverVariables().size(), encoded.variables().size());
		for(int index = 0; index < surface.exactSolverVariables().size(); index++)
			assertSame(surface.exactSolverVariables().get(index), encoded.variables().get(index));
		List<ExactCategoricalSolver.Factor> expectedFactors = new ArrayList<>(
			model.exactSolverHardFactors());
		expectedFactors.addAll(surface.exactSolverFactors());
		expectedFactors.add(forced);
		assertEquals(expectedFactors.size(), encoded.factors().size());
		for(int index = 0; index < expectedFactors.size(); index++)
			assertSame("legacy factor identity/order at " + index,
				expectedFactors.get(index), encoded.factors().get(index));
		List<Integer> assignment = encoded.variables().stream().map(ignored -> 0).toList();
		assertEquals(assignment.subList(0, model.variables().size()), encoded.decode(assignment));
	}

	@Test
	public void productionBoundaryBuildsActualFactorsAndDecodesCanonicalWitness() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, factor -> { });
		var encoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface,
			List.of(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);

		assertTrue(encoded.statistics().transformed());
		assertEquals("EXACT_SHARED_SOURCE_TRUTH_QUOTIENT", encoded.statistics().reason());
		assertTrue(encoded.statistics().rawProfileCells() > 0);
		assertEquals(model.variables().size(), encoded.decisionPrefixCount());
		var result = ExactCategoricalSolver.solve(encoded.variables(), encoded.factors(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		List<Integer> decoded = encoded.decode(result.assignmentInVariableOrder());
		List<Integer> reconstructed = encoded.reconstructOriginalAssignment(
			result.assignmentInVariableOrder());
		assertEquals(model.variables().size(), decoded.size());
		assertEquals(surface.exactSolverVariables().size(), reconstructed.size());
		for(int decision = 0; decision < decoded.size(); decision++)
			assertTrue(decoded.get(decision) >= 0
				&& decoded.get(decision) < model.domains().get(decision).alternatives().size());
		assertCanonicalHardFactorsFinite(model, decoded);
		assertFactorsFinite(model.exactSolverHardFactors(), surface.exactSolverVariables(),
			reconstructed);
		long canonicalBits = surface.evaluateCanonical(decoded);
		assertTrue(Double.isFinite(Double.longBitsToDouble(canonicalBits)));
		assertEquals(canonicalBits, Double.doubleToRawLongBits(result.objective()));
	}

	@Test
	public void productionRefinesCorrelatedHeadersWithoutAddingOrDroppingAssignments() throws Exception {
		Node seed = syntheticNode("correlated-seed", List.of(LOCAL));
		Node left = syntheticNode("correlated-left", List.of(LOCAL));
		Node right = syntheticNode("correlated-right", List.of(LOCAL));
		Node consumer = syntheticNode("correlated-consumer", List.of(LOCAL));
		var graph = new NeutralPlacementGraph(List.of(seed, left, right, consumer), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), left.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, seed.key(), right.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, left.key(), consumer.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, right.key(), consumer.key(), 1, "data-input")), List.of());
		var leftA = candidateRule(left, List.of(CandidateInputState.absentLocal()));
		var leftB = candidateRule(left, List.of(CandidateInputState.present(FType.ROW)));
		var rightA = candidateRule(right, List.of(CandidateInputState.absentLocal()));
		var rightB = candidateRule(right, List.of(CandidateInputState.present(FType.ROW)));
		var consumerRule = candidateRule(consumer,
			List.of(CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));
		List<CandidateRuleFact> facts = List.of(candidateFact(candidateRule(seed, List.of()), supportClause()),
			candidateFact(leftA, supportClause()), candidateFact(leftB, supportClause()),
			candidateFact(rightA, supportClause()), candidateFact(rightB, supportClause()),
			candidateFact(consumerRule, List.of(candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT, List.of(
				supportClause(CandidateRealizationInputBinding.direct(0,
					new CandidateRealizationReference(leftA, LOCAL_LAYOUT)),
					CandidateRealizationInputBinding.direct(1,
						new CandidateRealizationReference(rightA, LOCAL_LAYOUT))),
				supportClause(CandidateRealizationInputBinding.direct(0,
					new CandidateRealizationReference(leftB, LOCAL_LAYOUT)),
					CandidateRealizationInputBinding.direct(1,
						new CandidateRealizationReference(rightB, LOCAL_LAYOUT))))))));
		Class<?> fixtures = Class.forName("org.apache.sysds.hops.fedplanner.placement.PolicyGreedyGroundingTest");
		var factory = fixtures.getDeclaredMethod("analysis", NeutralPlacementGraph.class, List.class);
		factory.setAccessible(true);
		var analysis = (PlacementAnalysis) factory.invoke(null, graph, facts);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var encoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		assertTrue(encoded.statistics().reason(), encoded.statistics().transformed());
		List<ExactCategoricalSolver.Factor> original = new ArrayList<>(model.exactSolverHardFactors());
		original.addAll(surface.exactSolverFactors());
		var expected = finiteDecisionCosts(surface.exactSolverVariables(), original, model.variables().size(), null);
		var actual = finiteDecisionCosts(encoded.variables(), encoded.factors(), model.variables().size(), encoded);
		assertEquals("both correlated rows remain feasible", 2, expected.size());
		assertEquals("exact decision assignments and raw cost bits, with no crossed rows", expected, actual);
	}

	@Test
	public void actualPrepareRejectsCrossedHeaderReferenceAndUsesProjectedLinks() throws Exception {
		PlacementAnalysis analysis = minimalCrossTupleAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		assertTrue("fixture must produce typed hard-factor decompositions",
			model.hardFactorEncodings().size() >= 1);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, factor -> { });
		var encoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface,
			List.of(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);

		assertTrue(encoded.statistics().transformed());
		assertTrue("actual preparation must exercise projected-link construction",
			encoded.statistics().projectedLinks() >= 1);
		ExactCategoricalSolver.Factor membership = encoded.factors().stream()
			.filter(factor -> factor.scope().size() == 2
				&& factor.scope().get(0).key().contains("decision=2|header")
				&& factor.scope().get(1).key().contains("decision=1|reference"))
			.findFirst().orElseThrow();
		int finite = 0, infinite = 0;
		for(int header = 0; header < 2; header++)
			for(int reference = 0; reference < 2; reference++) {
				double value = membership.cost(new int[] {header, reference});
				if(Double.isFinite(value))
					finite++;
				else {
					assertEquals(Double.POSITIVE_INFINITY, value, 0.0);
					infinite++;
				}
			}
		assertEquals("one source reference is legal for each header", 2, finite);
		assertEquals("both crossed header/reference tuples are explicit infinity", 2, infinite);

		var result = ExactCategoricalSolver.solve(encoded.variables(), encoded.factors(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		List<Integer> decoded = encoded.decode(result.assignmentInVariableOrder());
		List<Integer> reconstructed = encoded.reconstructOriginalAssignment(
			result.assignmentInVariableOrder());
		assertEquals(model.variables().size(), decoded.size());
		assertFactorsFinite(model.exactSolverHardFactors(), surface.exactSolverVariables(), reconstructed);
		assertEquals(surface.evaluateCanonical(decoded),
			Double.doubleToRawLongBits(result.objective()));
	}

	@Test
	public void demandedReferenceWithoutSelectableOwnerAlternativeRemainsAnInfeasibleValue()
		throws Exception {
		PlacementAnalysis analysis = minimalUnselectableReferenceAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, factor -> { });
		var encoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface,
			List.of(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);

		assertTrue("a retained dead consumer row must not force whole-model fallback: "
			+ encoded.statistics().reason(), encoded.statistics().transformed());
		ExactCategoricalSolver.Variable sourceReference = encoded.variables().stream()
			.filter(variable -> variable.key().contains("|reference"))
			.findFirst().orElseThrow();
		assertEquals("one selectable and one demanded-only reference", 2,
			sourceReference.domainSize());

		List<ExactCategoricalSolver.Factor> referenceLinks = encoded.factors().stream()
			.filter(factor -> factor.scope().size() == 2
				&& factor.scope().get(0).key().contains("|header")
				&& factor.scope().get(1) == sourceReference)
			.toList();
		ExactCategoricalSolver.Factor ownerLink = referenceLinks.stream()
			.filter(ExactPhysicalSharedSourceEncodingTest::hasAllInfiniteReferenceColumn)
			.findFirst().orElseThrow();
		int demandedOnly = -1;
		for(int reference = 0; reference < sourceReference.domainSize(); reference++) {
			boolean ownerCanSelect = false;
			for(int header = 0; header < ownerLink.scope().get(0).domainSize(); header++)
				ownerCanSelect |= Double.isFinite(ownerLink.cost(new int[] {header, reference}));
			if(!ownerCanSelect) {
				assertEquals("exactly one reference is demanded-only", -1, demandedOnly);
				demandedOnly = reference;
			}
		}
		assertTrue(demandedOnly >= 0);
		boolean retainedConsumerRow = false;
		for(ExactCategoricalSolver.Factor link : referenceLinks)
			if(link != ownerLink)
				for(int header = 0; header < link.scope().get(0).domainSize(); header++)
					retainedConsumerRow |= Double.isFinite(
						link.cost(new int[] {header, demandedOnly}));
		assertTrue("the original dead consumer row remains represented", retainedConsumerRow);

		List<ExactCategoricalSolver.Factor> legacyFactors = new ArrayList<>(
			model.exactSolverHardFactors());
		legacyFactors.addAll(surface.exactSolverFactors());
		Map<List<Integer>,Long> legacyFinite = finiteDecisionCosts(surface.exactSolverVariables(),
			legacyFactors, model.variables().size(), null);
		Map<List<Integer>,Long> encodedFinite = finiteDecisionCosts(encoded.variables(),
			encoded.factors(), model.variables().size(), encoded);
		assertFalse(legacyFinite.isEmpty());
		assertFalse(encodedFinite.isEmpty());
		assertEquals("finite legacy and encoded decision/raw-cost relations",
			legacyFinite, encodedFinite);
		assertEquals("all original rows remain in representation statistics",
			model.domains().stream().mapToInt(domain -> domain.alternatives().size()).sum(),
			encoded.statistics().originalRows());

		int sourcePosition = encoded.variables().indexOf(sourceReference);
		assertTrue(sourcePosition >= 0);
		assertFalse("the demanded-only source value must never become a finite full assignment",
			anyFiniteAssignmentWithValue(encoded.variables(), encoded.factors(),
				sourcePosition, demandedOnly));
	}

	@Test
	public void profilePreflightRejectsBeforeLazyEvaluatorInvocationAndPreservesRawClasses() {
		var variable = new ExactCategoricalSolver.Variable("profile", 4);
		AtomicInteger calls = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			calls.incrementAndGet();
			return values[0];
		});
		assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(lazy, 4, 1,
				new ExactCategoricalSolver.Limits(3, 3)));
		assertEquals(0, calls.get());

		var dense = ExactCategoricalSolver.Factor.dense(List.of(variable),
			0.0, -0.0, Double.POSITIVE_INFINITY, Double.longBitsToDouble(1));
		assertArrayEquals(new int[] {0, 1, 2, 3},
			ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(dense, 4, 1,
				new ExactCategoricalSolver.Limits(1, 1)));

		var a = new ExactCategoricalSolver.Variable("a", 2);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		var c = new ExactCategoricalSolver.Variable("c", 2);
		double[] values = {0, -0.0, 1, 2, 0, -0.0, 0, -0.0, 1, 3, 0, -0.0};
		var multi = ExactCategoricalSolver.Factor.dense(List.of(a, b, c), values);
		assertArrayEquals(ExactFactorValueClasses.denseAxisClasses(values, null, 2, 6),
			ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(multi, 2, 6,
				new ExactCategoricalSolver.Limits(1, 1)));
		assertArrayEquals(ExactFactorValueClasses.denseAxisClasses(values, null, 3, 2),
			ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(multi, 3, 2,
				new ExactCategoricalSolver.Limits(1, 1)));
	}

	@Test
	public void materializationBudgetRejectsLongOverflowInsteadOfWrapping() {
		assertTrue(ExactPhysicalSharedSourceEncoding.materializationFitsForTest(
			Long.MAX_VALUE - 1, 1, Long.MAX_VALUE));
		assertFalse(ExactPhysicalSharedSourceEncoding.materializationFitsForTest(
			Long.MAX_VALUE - 1, 2, Long.MAX_VALUE));
	}

	@Test
	public void projectedOutputPreflightAccountsForEveryPairwiseFactorAndRetainedCell() {
		// 5 retained output + 1 retained temporary + (3*2) header + (4*2, 5*2) source links.
		assertTrue(ExactPhysicalSharedSourceEncoding.projectedOutputFitsForTest(
			3, 2, new int[] {4, 5}, 5, 1,
			new ExactCategoricalSolver.Limits(10, 30)));
		assertFalse(ExactPhysicalSharedSourceEncoding.projectedOutputFitsForTest(
			3, 2, new int[] {4, 5}, 5, 1,
			new ExactCategoricalSolver.Limits(10, 29)));
		assertFalse(ExactPhysicalSharedSourceEncoding.projectedOutputFitsForTest(
			3, 2, new int[] {4, 5}, 5, 1,
			new ExactCategoricalSolver.Limits(9, 30)));
	}

	@Test
	public void projectedCapRejectionLeavesTheSmallerDenseFallbackEligible() {
		// A singleton header, two binary sources and binary Q need ten pairwise cells,
		// while the exact dense H/R1/R2/Q fallback needs eight. This is the XOR-shaped
		// non-lossless boundary where projected failure must mean "try dense", not legacy.
		var projectedCap = new ExactCategoricalSolver.Limits(8, 9);
		assertFalse(ExactPhysicalSharedSourceEncoding.projectedOutputFitsForTest(
			1, 2, new int[] {2, 2}, 0, 0, projectedCap));
		assertTrue(ExactPhysicalSharedSourceEncoding.materializationFitsForTest(0, 8, 9));

		// If the dense representation also exceeds the same logical materialization cap,
		// the existing dense preflight remains responsible for whole-model fallback.
		assertFalse(ExactPhysicalSharedSourceEncoding.materializationFitsForTest(0, 8, 7));
	}

	@Test
	public void actualProjectedXorReturnsDenseFallbackAtAndBelowItsPairwiseCap() throws Exception {
		Object[] fixture = projectedXorFixture();
		Object xorPlan = fixture[0];
		var quotient = new ExactCategoricalSolver.Variable("xor-q", 2);
		int[] sourceToClass = {0, 1, 1, 0};
		@SuppressWarnings("unchecked")
		IdentityHashMap<Object,Object> references = (IdentityHashMap<Object,Object>)fixture[1];

		Method projected = Arrays.stream(ExactPhysicalSharedSourceEncoding.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("projectedLinkFactors"))
			.findFirst().orElseThrow();
		projected.setAccessible(true);
		assertEquals("XOR is not losslessly representable by pairwise projections",
			null, projected.invoke(null, xorPlan, quotient, sourceToClass, references,
				0L, 0L, new ExactCategoricalSolver.Limits(8, 100), "TEST_XOR"));
		assertEquals("a projected-only cap rejection must select the same dense fallback",
			null, projected.invoke(null, xorPlan, quotient, sourceToClass, references,
				0L, 0L, new ExactCategoricalSolver.Limits(8, 9), "TEST_XOR"));

		var h = new ExactCategoricalSolver.Variable("xor-h", 1);
		var r1 = new ExactCategoricalSolver.Variable("xor-r1", 2);
		var r2 = new ExactCategoricalSolver.Variable("xor-r2", 2);
		double[] dense = new double[8];
		for(int left = 0; left < 2; left++)
			for(int right = 0; right < 2; right++)
				for(int q = 0; q < 2; q++)
					dense[(left * 2 + right) * 2 + q] = q == (left ^ right)
						? 0.0 : Double.POSITIVE_INFINITY;
		var denseFallback = ExactCategoricalSolver.Factor.dense(List.of(h, r1, r2, quotient), dense);
		for(int left = 0; left < 2; left++)
			for(int right = 0; right < 2; right++)
				for(int q = 0; q < 2; q++)
					assertEquals(q == (left ^ right),
						Double.isFinite(denseFallback.cost(new int[] {0, left, right, q})));
		var solved = ExactCategoricalSolver.solve(List.of(h, r1, r2, quotient),
			List.of(denseFallback), new ExactCategoricalSolver.Limits(8, 100));
		assertEquals((int)solved.assignmentInVariableOrder().get(3),
			(int)solved.assignmentInVariableOrder().get(1)
				^ (int)solved.assignmentInVariableOrder().get(2));
		assertThrows("when dense also exceeds the cap, the caller must reject the candidate",
			IllegalArgumentException.class, () -> ExactCategoricalSolver.solve(
				List.of(h, r1, r2, quotient), List.of(denseFallback),
				new ExactCategoricalSolver.Limits(8, 7)));
	}

	@Test
	public void retainedTruthProfileIsNotChargedAsIterationLocalTemporaryStorage() {
		assertEquals(0, ExactPhysicalSharedSourceEncoding.profileTemporaryChargeForTest(true, 17));
		assertEquals(17, ExactPhysicalSharedSourceEncoding.profileTemporaryChargeForTest(false, 17));
		assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalSharedSourceEncoding.profileTemporaryChargeForTest(false, -1));
	}

	@Test
	public void wildcardProjectionTraversalPreservesOrderAndCanStopEarly() {
		List<int[]> visited = ExactPhysicalSharedSourceEncoding.projectedWildcardTuplesForTest(
			new int[] {7, -1, 1, -1}, new int[] {2, 3, 2}, 4);
		assertEquals(4, visited.size());
		assertArrayEquals(new int[] {7, 0, 1, 0}, visited.get(0));
		assertArrayEquals(new int[] {7, 0, 1, 1}, visited.get(1));
		assertArrayEquals(new int[] {7, 1, 1, 0}, visited.get(2));
		assertArrayEquals(new int[] {7, 1, 1, 1}, visited.get(3));
	}

	@Test
	public void trustedDecompositionMapComposesWithoutReadingOneHotLinkTable() throws Exception {
		int[] actual = ExactPhysicalSharedSourceEncoding.composeObservationClasses(
			new int[] {0, 1, 1, 0}, new int[] {3, 0, 2, 1, 2}, 4);
		assertArrayEquals(new int[] {0, 0, 1, 1, 1}, actual);
	}

	@Test
	public void originalObservationIsRecoveredFromDecodedSourceNotTruthRepresentative() {
		// O=1-X and a constant truth factor collapse both O values into Q=0.  Choosing
		// Q's first representative would recover O=0 for X=0, which violates X->O.
		int[] sourceToObservation = {1, 0};
		assertEquals(1, ExactPhysicalSharedSourceEncoding.recoverOriginalObservation(
			0, sourceToObservation));
		assertEquals(0, ExactPhysicalSharedSourceEncoding.recoverOriginalObservation(
			1, sourceToObservation));
	}

	private static Object[] projectedXorFixture() throws Exception {
		Class<?> alternativeHeader = nested("AlternativeHeader");
		Object header = construct(alternativeHeader, null, null, null, null, null, null, null,
			null, null, null, List.of(), List.of(), null, List.of());
		CompiledHopKey leftOwner = syntheticNode("xor-left", List.of(LOCAL)).key();
		CompiledHopKey rightOwner = syntheticNode("xor-right", List.of(LOCAL)).key();
		Class<?> domainView = nested("DomainView");
		Object view = construct(domainView, 0, null, List.of(header), new int[] {0},
			List.of(leftOwner, rightOwner), List.of(new IdentityHashMap<>()));

		Class<?> referenceView = nested("ReferenceView");
		Object left = construct(referenceView, view, List.of(new Object(), new Object()), Map.of(),
			new int[] {0});
		Object right = construct(referenceView, view, List.of(new Object(), new Object()), Map.of(),
			new int[] {0});
		IdentityHashMap<Object,Object> references = new IdentityHashMap<>();
		references.put(leftOwner, left);
		references.put(rightOwner, right);

		Class<?> intTuple = nested("IntTuple");
		Map<Object,List<Integer>> fibers = new LinkedHashMap<>();
		fibers.put(construct(intTuple, new int[] {0, 0, 0}), List.of(0));
		fibers.put(construct(intTuple, new int[] {0, 0, 1}), List.of(1));
		fibers.put(construct(intTuple, new int[] {0, 1, 0}), List.of(2));
		fibers.put(construct(intTuple, new int[] {0, 1, 1}), List.of(3));
		Object plan = construct(nested("AxisPlan"), 0, view, new boolean[] {true, true},
			new int[] {0, 1, 1, 0}, new boolean[][] {{true, true}}, fibers);
		return new Object[] {plan, references};
	}

	private static Class<?> nested(String simpleName) throws ClassNotFoundException {
		return Class.forName(ExactPhysicalSharedSourceEncoding.class.getName() + '$' + simpleName);
	}

	private static Object construct(Class<?> type, Object... arguments) throws Exception {
		Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == arguments.length)
			.findFirst().orElseThrow();
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static PlacementAnalysis minimalCrossTupleAnalysis() throws Exception {
		PlacementState fout = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		PlacementEmissionState foutEmission = new PlacementEmissionState(fout, false);
		PlacementRealizationKey foutLayout =
			PlacementRealizationKey.sourceLineage(foutEmission, "consumer-fout");
		Node seed = syntheticNode("seed", List.of(LOCAL));
		Node source = syntheticNode("source", List.of(LOCAL));
		Node consumer = syntheticNode("consumer", List.of(LOCAL, fout));
		CandidateRuleKey seedRule = candidateRule(seed, List.of());
		CandidateRuleKey sourceLocal = candidateRule(source,
			List.of(CandidateInputState.absentLocal()));
		CandidateRuleKey sourceRow = candidateRule(source,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRuleKey consumerRule = candidateRule(consumer,
			List.of(CandidateInputState.absentLocal()));
		var graph = new NeutralPlacementGraph(List.of(seed, source, consumer), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), source.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, source.key(), consumer.key(), 0, "data-input")),
			List.of());
		var localReference = new CandidateRealizationReference(sourceLocal, LOCAL_LAYOUT);
		var rowReference = new CandidateRealizationReference(sourceRow, LOCAL_LAYOUT);
		List<CandidateEmissionFact> consumerEmissions = List.of(
			candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT,
				distinctClauses(consumer.key(), "consumer-local",
					CandidateRealizationInputBinding.direct(0, localReference))),
			candidateEmission(foutEmission, foutLayout,
				distinctClauses(consumer.key(), "consumer-row",
					CandidateRealizationInputBinding.direct(0, rowReference))));
		List<CandidateRuleFact> facts = List.of(
			candidateFact(seedRule, supportClause()),
			candidateFact(sourceLocal, List.of(candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT,
				distinctClauses(source.key(), "source-local")))),
			candidateFact(sourceRow, List.of(candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT,
				distinctClauses(source.key(), "source-row")))),
			candidateFact(consumerRule, consumerEmissions));

		// Reuse the placement package's invariant-preserving synthetic analysis fixture.
		Class<?> fixtures = Class.forName(
			"org.apache.sysds.hops.fedplanner.placement.PolicyGreedyGroundingTest");
		var analysis = fixtures.getDeclaredMethod("analysis", NeutralPlacementGraph.class, List.class);
		analysis.setAccessible(true);
		return (PlacementAnalysis)analysis.invoke(null, graph, facts);
	}

	private static PlacementAnalysis minimalUnselectableReferenceAnalysis() throws Exception {
		PlacementState fout = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		PlacementEmissionState foutEmission = new PlacementEmissionState(fout, false);
		PlacementRealizationKey foutLayout =
			PlacementRealizationKey.sourceLineage(foutEmission, "consumer-dead-reference");
		Node seed = syntheticNode("dead-seed", List.of(LOCAL));
		Node source = syntheticNode("dead-source", List.of(LOCAL));
		Node consumer = syntheticNode("dead-consumer", List.of(LOCAL, fout));
		CandidateRuleKey seedRule = candidateRule(seed, List.of());
		CandidateRuleKey selectableSource = candidateRule(source,
			List.of(CandidateInputState.absentLocal()));
		CandidateRuleKey absentSource = candidateRule(source,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRuleKey consumerRule = candidateRule(consumer,
			List.of(CandidateInputState.absentLocal()));
		var graph = new NeutralPlacementGraph(List.of(seed, source, consumer), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), source.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, source.key(), consumer.key(), 0, "data-input")),
			List.of());
		PlacementState equalButUnselectable = new PlacementState(
			ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementEmissionState unselectableEmission =
			new PlacementEmissionState(equalButUnselectable, false);
		PlacementRealizationKey unselectableLayout =
			PlacementRealizationKey.local(unselectableEmission);
		var selectableReference = new CandidateRealizationReference(selectableSource, LOCAL_LAYOUT);
		var demandedOnlyReference = new CandidateRealizationReference(absentSource, unselectableLayout);
		List<CandidateEmissionFact> consumerEmissions = List.of(
			candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT,
				List.of(supportClause(
					CandidateRealizationInputBinding.direct(0, selectableReference)))),
			candidateEmission(foutEmission, foutLayout,
				List.of(supportClause(
					CandidateRealizationInputBinding.direct(0, demandedOnlyReference)))));
		List<CandidateRuleFact> facts = List.of(
			candidateFact(seedRule, supportClause()),
			candidateFact(selectableSource, List.of(candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT,
				List.of(supportClause())))),
			candidateFact(absentSource, List.of(candidateEmission(unselectableEmission,
				unselectableLayout, List.of(supportClause())))),
			candidateFact(consumerRule, consumerEmissions));

		Class<?> fixtures = Class.forName(
			"org.apache.sysds.hops.fedplanner.placement.PolicyGreedyGroundingTest");
		var analysis = fixtures.getDeclaredMethod("analysis", NeutralPlacementGraph.class, List.class);
		analysis.setAccessible(true);
		return (PlacementAnalysis)analysis.invoke(null, graph, facts);
	}

	private static Map<List<Integer>,Long> finiteDecisionCosts(
		List<ExactCategoricalSolver.Variable> variables, List<ExactCategoricalSolver.Factor> factors,
		int decisions, ExactPhysicalSharedSourceEncoding.Encoding encoding) {
		long cells = 1;
		for(ExactCategoricalSolver.Variable variable : variables)
			cells = Math.multiplyExact(cells, variable.domainSize());
		assertTrue("fixture must remain exhaustively enumerable", cells <= 100_000);
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		Map<List<Integer>,Long> finite = new LinkedHashMap<>();
		int[] assignment = new int[variables.size()];
		for(long cell = 0; cell < cells; cell++) {
			decodeCell(cell, variables, assignment);
			double cost = factorSum(factors, positions, assignment);
			if(!Double.isFinite(cost))
				continue;
			List<Integer> values = Arrays.stream(assignment).boxed().toList();
			List<Integer> decisionValues = encoding == null
				? List.copyOf(values.subList(0, decisions)) : encoding.decode(values);
			Long previous = finite.putIfAbsent(decisionValues, Double.doubleToRawLongBits(cost));
			if(previous != null)
				assertEquals("all auxiliary witnesses for one decision have identical raw cost",
					(long)previous, Double.doubleToRawLongBits(cost));
		}
		return finite;
	}

	private static boolean anyFiniteAssignmentWithValue(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int position, int requiredValue) {
		long cells = 1;
		for(ExactCategoricalSolver.Variable variable : variables)
			cells = Math.multiplyExact(cells, variable.domainSize());
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		int[] assignment = new int[variables.size()];
		for(long cell = 0; cell < cells; cell++) {
			decodeCell(cell, variables, assignment);
			if(assignment[position] == requiredValue
				&& Double.isFinite(factorSum(factors, positions, assignment)))
				return true;
		}
		return false;
	}

	private static boolean hasAllInfiniteReferenceColumn(ExactCategoricalSolver.Factor factor) {
		for(int reference = 0; reference < factor.scope().get(1).domainSize(); reference++) {
			boolean allInfinite = true;
			for(int header = 0; header < factor.scope().get(0).domainSize(); header++)
				allInfinite &= !Double.isFinite(factor.cost(new int[] {header, reference}));
			if(allInfinite)
				return true;
		}
		return false;
	}

	private static void decodeCell(long cell, List<ExactCategoricalSolver.Variable> variables,
		int[] assignment) {
		for(int axis = variables.size() - 1; axis >= 0; axis--) {
			assignment[axis] = (int)(cell % variables.get(axis).domainSize());
			cell /= variables.get(axis).domainSize();
		}
	}

	private static double factorSum(List<ExactCategoricalSolver.Factor> factors,
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions, int[] assignment) {
		double sum = 0;
		for(ExactCategoricalSolver.Factor factor : factors) {
			int[] local = new int[factor.scope().size()];
			for(int axis = 0; axis < local.length; axis++)
				local[axis] = assignment[positions.get(factor.scope().get(axis))];
			double value = factor.cost(local);
			if(!Double.isFinite(value))
				return value;
			sum += value;
		}
		return sum;
	}

	private static CandidateRuleKey candidateRule(Node node, List<CandidateInputState> inputs) {
		return new CandidateRuleKey(node.key(), inputs);
	}

	private static CandidateRealizationSupportClause supportClause(
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(), List.of(bindings));
	}

	private static List<CandidateRealizationSupportClause> distinctClauses(CompiledHopKey owner,
		String prefix, CandidateRealizationInputBinding... bindings) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(int ordinal = 0; ordinal < 3; ordinal++)
			clauses.add(new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, owner, prefix + ordinal)),
				List.of(bindings)));
		return clauses;
	}

	private static CandidateEmissionFact candidateEmission(PlacementEmissionState state,
		PlacementRealizationKey layout, List<CandidateRealizationSupportClause> clauses) {
		return new CandidateEmissionFact(state, null, null,
			List.of(new CandidateEmissionRealization(layout, clauses)));
	}

	private static CandidateRuleFact candidateFact(CandidateRuleKey rule,
		CandidateRealizationSupportClause clause) {
		return candidateFact(rule,
			List.of(candidateEmission(LOCAL_EMISSION, LOCAL_LAYOUT, List.of(clause))));
	}

	private static CandidateRuleFact candidateFact(CandidateRuleKey rule,
		List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, "");
	}

	private static Node syntheticNode(String id, List<PlacementState> states) {
		var region = new ControlRegionKey("shared-source-cross", "main", List.of("main"),
			"root", "compiled");
		var key = new CompiledHopKey("shared-source-cross", "main", "root", "compiled",
			region, id, id);
		return new Node(key, NodeKind.OPERATION,
			new ValueVersionKey("shared-source-cross", id, region, 0, VersionKind.ORDINARY, List.of()),
			true, states, List.of(), List.of());
	}

	private static double[] costs(ExactPhysicalSharedSourceEncoding.RelationEncoding encoded,
		int sourceValue) {
		List<Double> costs = new ArrayList<>();
		for(int index = 1; index < encoded.factors().size(); index++)
			costs.add(encoded.factors().get(index).cost(new int[] {0, sourceValue}));
		return costs.stream().mapToDouble(Double::doubleValue).toArray();
	}

	private static void assertCanonicalHardFactorsFinite(ExactPhysicalModel model,
		List<Integer> assignment) {
		var positions = new java.util.IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		for(ExactCategoricalSolver.Factor factor : model.hardFactors()) {
			int[] local = new int[factor.scope().size()];
			for(int axis = 0; axis < local.length; axis++)
				local[axis] = assignment.get(positions.get(factor.scope().get(axis)));
			assertTrue(Double.isFinite(factor.cost(local)));
		}
	}

	private static void assertFactorsFinite(List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> variables, List<Integer> assignment) {
		var positions = new java.util.IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		for(ExactCategoricalSolver.Factor factor : factors) {
			int[] local = new int[factor.scope().size()];
			for(int axis = 0; axis < local.length; axis++)
				local[axis] = assignment.get(positions.get(factor.scope().get(axis)));
			assertTrue(Double.isFinite(factor.cost(local)));
		}
	}

	private static ExactPhysicalSharedSourceEncoding.RelationRow row(int ordinal, Object header,
		Object... ownerValuePairs) {
		List<ExactPhysicalSharedSourceEncoding.SourceSelection> selections = new ArrayList<>();
		for(int index = 0; index < ownerValuePairs.length; index += 2)
			selections.add(selection(ownerValuePairs[index], ownerValuePairs[index + 1]));
		return new ExactPhysicalSharedSourceEncoding.RelationRow(ordinal, header, selections);
	}

	private static ExactPhysicalSharedSourceEncoding.SourceSelection selection(Object owner,
		Object value) {
		return new ExactPhysicalSharedSourceEncoding.SourceSelection(owner, value);
	}
}

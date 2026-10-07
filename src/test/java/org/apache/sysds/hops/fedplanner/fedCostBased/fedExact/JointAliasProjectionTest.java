/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Physical regression for eliminating a support-certified internal alias from one joint factor. */
public class JointAliasProjectionTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 5_000_000);
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void supportConjunctionPreservesEveryOriginalAssignmentAfterAliasProjection()
		throws Exception {
		PlacementAnalysis analysis = analysis("A=X;B=X;i=1;while(i<=2){"
			+ "if(p>0.5){A=Y;}else{A=A;}B=A;i=i+1;}C=A+B;print(sum(C));");
		ExactPhysicalModel original =
			ExactPhysicalModel.buildWithUnprojectedJointFactorsForTest(analysis);
		ExactPhysicalModel projected = ExactPhysicalModel.build(analysis);
		assertSameDomainsAndCanonicalFactorOrder(original, projected);

		List<Integer> changed = changedFactorOrdinals(original, projected);
		Assert.assertEquals("the fixture must project exactly one joint predicate", 1, changed.size());
		int ordinal = changed.get(0);
		var oldJoint = original.hardFactors().get(ordinal);
		var newJoint = projected.hardFactors().get(ordinal);
		Assert.assertTrue(oldJoint.supportsPartialTruth());
		Assert.assertTrue(newJoint.supportsPartialTruth());
		long[] proofWork = new long[4];
		int[] partial = new int[newJoint.scope().size()];
		Arrays.fill(partial, -1);
		verifyPartialProofs(newJoint, partial, 0, proofWork);
		Assert.assertTrue("projected predicate must retain partial certificates", proofWork[1] > 0);
		Assert.assertTrue("projected predicate must retain finite completions", proofWork[2] > 0);
		Assert.assertTrue("projected predicate must retain forbidden completions", proofWork[3] > 0);
		Assert.assertEquals(oldJoint.scope().size() - 1, newJoint.scope().size());
		Assert.assertTrue(factorCells(newJoint) < factorCells(oldJoint));

		Set<String> unionKeys = factorKeys(oldJoint);
		Set<String> projectedKeys = factorKeys(newJoint);
		Set<String> removedKeys = new HashSet<>(unionKeys);
		removedKeys.removeAll(projectedKeys);
		Assert.assertEquals(1, removedKeys.size());
		String removedKey = removedKeys.iterator().next();
		var removedDomain = original.domains().stream()
			.filter(domain -> domain.variable().key().equals(removedKey)).findFirst().orElseThrow();
		Assert.assertEquals("B", analysis.hop(removedDomain.node().key()).orElseThrow().getName());
		Assert.assertTrue(analysis.hop(removedDomain.node().key()).orElseThrow() instanceof DataOp);
		Assert.assertEquals(factorCells(oldJoint),
			factorCells(newJoint) * removedDomain.variable().domainSize());

		List<Integer> supportOrdinals = new ArrayList<>();
		for(int factor = 0; factor < original.hardFactors().size(); factor++)
			if(factor != ordinal && unionKeys.containsAll(factorKeys(original.hardFactors().get(factor))))
				supportOrdinals.add(factor);
		Assert.assertFalse("the fixture must retain support factors over the joint scope",
			supportOrdinals.isEmpty());

		Map<String,Integer> assignment = new HashMap<>();
		long supportFinite = 0;
		long legal = 0;
		long supportInvalidPredicateDifference = 0;
		long referenceSupportFinite = 0;
		long referenceSupportLegal = 0;
		long referenceSupportInvalidDifference = 0;
		long checked = 0;
		int[] values = new int[oldJoint.scope().size()];
		long cells = factorCells(oldJoint);
		for(long cell = 0; cell < cells; cell++) {
			decode(oldJoint, cell, values);
			assignment.clear();
			for(int position = 0; position < values.length; position++)
				assignment.put(oldJoint.scope().get(position).key(), values[position]);
			double oldSupport = conjunction(original, supportOrdinals, assignment);
			double newSupport = conjunction(projected, supportOrdinals, assignment);
			Assert.assertEquals("support factors changed at original cell " + cell,
				Double.doubleToRawLongBits(oldSupport), Double.doubleToRawLongBits(newSupport));
			double oldTruth = cost(oldJoint, assignment);
			double newTruth = cost(newJoint, assignment);
			boolean referenceSupport = referenceSupportSatisfied(original, unionKeys, assignment);
			if(referenceSupport) {
				referenceSupportFinite++;
				Assert.assertEquals("certified reference support changed H at original cell " + cell,
					Double.doubleToRawLongBits(oldTruth), Double.doubleToRawLongBits(newTruth));
				if(Double.isFinite(oldTruth)) referenceSupportLegal++;
			}
			else if(Double.doubleToRawLongBits(oldTruth) != Double.doubleToRawLongBits(newTruth))
				referenceSupportInvalidDifference++;
			double oldConjunction = Double.isInfinite(oldSupport) ? oldSupport : oldTruth;
			double newConjunction = Double.isInfinite(newSupport) ? newSupport : newTruth;
			Assert.assertEquals("S+H changed at original cell " + cell,
				Double.doubleToRawLongBits(oldConjunction),
				Double.doubleToRawLongBits(newConjunction));
			if(Double.isFinite(oldSupport)) {
				supportFinite++;
				if(Double.isFinite(oldTruth)) legal++;
			}
			else if(Double.doubleToRawLongBits(oldTruth) != Double.doubleToRawLongBits(newTruth))
				supportInvalidPredicateDifference++;
			checked++;
		}
		Assert.assertEquals("the full original joint space must be exercised", cells, checked);
		Assert.assertTrue("the support premise must admit at least one completion", supportFinite > 0);
		Assert.assertTrue("the projected conjunction must retain a legal physical tuple", legal > 0);
		Assert.assertTrue("the test must distinguish predicate-only from support-conjoined equivalence",
			supportInvalidPredicateDifference > 0);
		Assert.assertTrue("exact realization references must admit at least one tuple",
			referenceSupportFinite > 0);
		Assert.assertTrue("exact realization references must admit a legal tuple",
			referenceSupportLegal > 0);
		Assert.assertTrue("invalid references must expose the removed predicate dependency",
			referenceSupportInvalidDifference > 0);
		System.out.println("JOINT_ALIAS_PROJECTION_EVIDENCE|oldCells=" + factorCells(oldJoint)
			+ "|newCells=" + factorCells(newJoint) + "|assignments=" + checked
			+ "|supportFinite=" + supportFinite + "|referenceSupportFinite="
			+ referenceSupportFinite + "|invalidPredicateDifferences="
			+ referenceSupportInvalidDifference + "|partialCertificates=" + proofWork[1]);
	}

	@Test
	public void readerOwnedLoopSourcesAreNotProjected() throws Exception {
		PlacementAnalysis analysis = analysis("A=X;B=X;i=1;while(i<=2){if(p>0.5){A=X;B=X;}"
			+ "else{A=Y;B=Y;}i=i+1;}C=A+B;print(sum(C));");
		ExactPhysicalModel original =
			ExactPhysicalModel.buildWithUnprojectedJointFactorsForTest(analysis);
		ExactPhysicalModel projected = ExactPhysicalModel.build(analysis);
		assertSameDomainsAndCanonicalFactorOrder(original, projected);
		Assert.assertTrue("reader-owned loop sources must retain every joint axis",
			changedFactorOrdinals(original, projected).isEmpty());
		for(int ordinal = 0; ordinal < original.hardFactors().size(); ordinal++) {
			var oldFactor = original.hardFactors().get(ordinal);
			var newFactor = projected.hardFactors().get(ordinal);
			int[] values = new int[oldFactor.scope().size()];
			Map<String,Integer> assignment = new HashMap<>();
			for(long cell = 0; cell < factorCells(oldFactor); cell++) {
				decode(oldFactor, cell, values);
				assignment.clear();
				for(int position = 0; position < values.length; position++)
					assignment.put(oldFactor.scope().get(position).key(), values[position]);
				Assert.assertEquals("unprojected loop truth changed at factor=" + ordinal
					+ ",cell=" + cell, Double.doubleToRawLongBits(cost(oldFactor, assignment)),
					Double.doubleToRawLongBits(cost(newFactor, assignment)));
			}
		}
		System.out.println("JOINT_ALIAS_REJECTION_EVIDENCE|factors="
			+ original.hardFactors().size());
	}

	@Test
	public void exactAndConditionalRegionalSolvesPreserveProjectedOptima() throws Exception {
		PlacementAnalysis analysis = analysis("A=X;B=X;i=1;while(i<=2){"
			+ "if(p>0.5){A=Y;}else{A=A;}B=A;i=i+1;}C=A+B;print(sum(C));");
		ExactPhysicalModel original =
			ExactPhysicalModel.buildWithUnprojectedJointFactorsForTest(analysis);
		ExactPhysicalModel projected = ExactPhysicalModel.build(analysis);
		List<Integer> changedOrdinals = changedFactorOrdinals(original, projected);
		Assert.assertEquals("solver fixture must project exactly one joint predicate",
			1, changedOrdinals.size());
		int changed = changedOrdinals.get(0);
		assertEncodedCanonicalTruth(original, changed);
		assertEncodedCanonicalTruth(projected, changed);

		var oldJoint = original.hardFactors().get(changed);
		var newJoint = projected.hardFactors().get(changed);
		Set<String> removed = factorKeys(oldJoint);
		removed.removeAll(factorKeys(newJoint));
		String aliasKey = removed.iterator().next();
		String targetKey = retainedReferenceTargetKey(original, newJoint, aliasKey);
		int aliasIndex = variableIndex(original, aliasKey);
		int targetIndex = variableIndex(original, targetKey);
		Assert.assertTrue(aliasIndex >= 0 && targetIndex >= 0 && aliasIndex != targetIndex);

		List<ExactCategoricalSolver.Factor> oldFactors = weightedFactors(original);
		List<ExactCategoricalSolver.Factor> newFactors = weightedFactors(projected);
		var oldFree = ExactCategoricalSolver.solve(original.variables(), oldFactors, LIMITS,
			(variable, value) -> value);
		var newFree = ExactCategoricalSolver.solve(projected.variables(), newFactors, LIMITS,
			(variable, value) -> value);
		assertSameSolve(oldFree, newFree);
		int aliasValue = oldFree.assignmentInVariableOrder().get(aliasIndex);
		int targetValue = oldFree.assignmentInVariableOrder().get(targetIndex);
		for(boolean[] fixed : List.of(new boolean[] {false, true},
			new boolean[] {true, false}, new boolean[] {true, true})) {
				boolean fixAlias = fixed[0];
				boolean fixTarget = fixed[1];
				List<ExactCategoricalSolver.Factor> oldConditional = new ArrayList<>(oldFactors);
				List<ExactCategoricalSolver.Factor> newConditional = new ArrayList<>(newFactors);
				if(fixAlias) {
					oldConditional.add(fixed(original.variables().get(aliasIndex), aliasValue));
					newConditional.add(fixed(projected.variables().get(aliasIndex), aliasValue));
				}
				if(fixTarget) {
					oldConditional.add(fixed(original.variables().get(targetIndex), targetValue));
					newConditional.add(fixed(projected.variables().get(targetIndex), targetValue));
				}
				assertSameSolve(ExactCategoricalSolver.solve(original.variables(), oldConditional,
					LIMITS, (variable, value) -> value),
					ExactCategoricalSolver.solve(projected.variables(), newConditional,
						LIMITS, (variable, value) -> value));
			}

		SharedRegionalPreparation oldShared = new SharedRegionalPreparation(
			RegionalSearchProblem.generic(original.variables(), oldFactors), LIMITS, false);
		SharedRegionalPreparation newShared = new SharedRegionalPreparation(
			RegionalSearchProblem.generic(projected.variables(), newFactors), LIMITS, false);
		int[] incumbent = oldFree.assignmentInVariableOrder().stream().mapToInt(Integer::intValue).toArray();
		for(int[] block : List.of(new int[] {aliasIndex}, new int[] {targetIndex},
			new int[] {aliasIndex, targetIndex}, new int[] {aliasIndex})) {
			var oldPrepared = oldShared.prepare(incumbent, block);
			var newPrepared = newShared.prepare(incumbent, block);
			Assert.assertNotNull(oldPrepared);
			Assert.assertNotNull(newPrepared);
			assertSameSolve(oldPrepared.solve(), newPrepared.solve());
		}
		Assert.assertTrue("revisiting alias-free after fixed/free changes must reuse a table",
			oldShared.cacheHits() > 0 && newShared.cacheHits() > 0);
		System.out.println("JOINT_ALIAS_SOLVER_EVIDENCE|exactScenarios=4|regionalBlocks=4"
			+ "|oldCacheHits=" + oldShared.cacheHits() + "|newCacheHits=" + newShared.cacheHits());
	}

	private static PlacementAnalysis analysis(String body) throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			SOURCES + body, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void assertSameDomainsAndCanonicalFactorOrder(ExactPhysicalModel expected,
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
		Assert.assertEquals(expected.hardFactors().size(), actual.hardFactors().size());
	}

	private static List<Integer> changedFactorOrdinals(ExactPhysicalModel original,
		ExactPhysicalModel projected) {
		List<Integer> changed = new ArrayList<>();
		for(int ordinal = 0; ordinal < original.hardFactors().size(); ordinal++) {
			var oldFactor = original.hardFactors().get(ordinal);
			var newFactor = projected.hardFactors().get(ordinal);
			if(!oldFactor.scope().stream().map(ExactCategoricalSolver.Variable::key).toList().equals(
				newFactor.scope().stream().map(ExactCategoricalSolver.Variable::key).toList()))
				changed.add(ordinal);
		}
		return changed;
	}

	private static double conjunction(ExactPhysicalModel model, List<Integer> ordinals,
		Map<String,Integer> assignment) {
		for(int ordinal : ordinals)
			if(Double.isInfinite(cost(model.hardFactors().get(ordinal), assignment)))
				return Double.POSITIVE_INFINITY;
		return 0.0;
	}

	private static boolean referenceSupportSatisfied(ExactPhysicalModel model,
		Set<String> unionKeys, Map<String,Integer> assignment) {
		Set<CompiledHopKey> unionOwners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected = new IdentityHashMap<>();
		for(var domain : model.domains())
			if(unionKeys.contains(domain.variable().key())) {
				unionOwners.add(domain.node().key());
				selected.put(domain.node().key(),
					domain.alternatives().get(assignment.get(domain.variable().key())));
			}
		for(var alternative : selected.values()) {
			if(alternative.supportClause() == null) continue;
			for(CandidateRealizationReference required :
				alternative.supportClause().requiredInputSupport()) {
				CompiledHopKey owner = required.rule().parentOccurrence();
				if(!unionOwners.contains(owner)) continue;
				ExactPhysicalModel.Alternative target = selected.get(owner);
				if(target == null || target.realization() == null) return false;
				var rule = target.captured() ? target.candidateRule() : target.executionRule();
				if(rule == null || !required.equals(
					CandidateRealizationReference.of(rule.key(), target.realization())))
					return false;
			}
		}
		return true;
	}

	private static String retainedReferenceTargetKey(ExactPhysicalModel model,
		ExactCategoricalSolver.Factor newJoint, String aliasKey) {
		var alias = model.domains().stream()
			.filter(domain -> domain.variable().key().equals(aliasKey)).findFirst().orElseThrow();
		Set<CompiledHopKey> projectedOwners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(var domain : model.domains())
			if(factorKeys(newJoint).contains(domain.variable().key()))
				projectedOwners.add(domain.node().key());
		Set<CompiledHopKey> targets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(var alternative : alias.alternatives())
			if(alternative.supportClause() != null)
				for(var reference : alternative.supportClause().requiredInputSupport())
					if(projectedOwners.contains(reference.rule().parentOccurrence()))
						targets.add(reference.rule().parentOccurrence());
		Assert.assertEquals("projected alias must forward to one retained owner", 1, targets.size());
		CompiledHopKey target = targets.iterator().next();
		return model.domains().stream().filter(domain -> domain.node().key() == target)
			.map(domain -> domain.variable().key()).findFirst().orElseThrow();
	}

	private static int variableIndex(ExactPhysicalModel model, String key) {
		for(int index = 0; index < model.variables().size(); index++)
			if(model.variables().get(index).key().equals(key)) return index;
		return -1;
	}

	private static List<ExactCategoricalSolver.Factor> weightedFactors(ExactPhysicalModel model) {
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
		for(int index = 0; index < model.variables().size(); index++) {
			var variable = model.variables().get(index);
			int weight = index + 1;
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(variable),
				values -> (double) weight * values[0]));
		}
		return factors;
	}

	private static ExactCategoricalSolver.Factor fixed(ExactCategoricalSolver.Variable variable,
		int expected) {
		return ExactCategoricalSolver.Factor.lazy(List.of(variable),
			values -> values[0] == expected ? 0.0 : Double.POSITIVE_INFINITY);
	}

	private static void assertSameSolve(ExactCategoricalSolver.Result expected,
		ExactCategoricalSolver.Result actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
	}

	private static void assertEncodedCanonicalTruth(ExactPhysicalModel model, int ordinal) {
		var canonical = model.hardFactors().get(ordinal);
		var matching = model.hardFactorEncodings().stream()
			.filter(candidate -> candidate.canonicalOrdinal() == ordinal
				&& candidate.decomposition().isObservationStar()).findFirst();
		var decomposition = matching.map(ExactPhysicalModel.HardFactorEncoding::decomposition)
			.orElseGet(() -> ExactHardFactorObservationDecomposition.create(
				"joint-alias-test|ordinal=" + ordinal, canonical,
				exactTruthObservationKeys(canonical)));
		Assert.assertNotNull("changed bounded factor must admit an exact observation quotient",
			decomposition);
		int[] values = new int[canonical.scope().size()];
		for(long cell = 0; cell < factorCells(canonical); cell++) {
			decode(canonical, cell, values);
			Map<ExactCategoricalSolver.Variable,Integer> assignment = new IdentityHashMap<>();
			for(int position = 0; position < values.length; position++) {
				assignment.put(canonical.scope().get(position), values[position]);
				assignment.put(decomposition.auxiliaryVariables().get(position),
					decomposition.observations().get(position)[values[position]]);
			}
			double encoded = 0.0;
			for(var factor : decomposition.solverFactors())
				encoded += factor.cost(factor.scope().stream().mapToInt(assignment::get).toArray());
			Assert.assertEquals("encoded truth changed at factor=" + ordinal + ",cell=" + cell,
				Double.doubleToRawLongBits(canonical.cost(values)),
				Double.doubleToRawLongBits(encoded));
		}
	}

	@SuppressWarnings("unchecked")
	private static List<?>[] exactTruthObservationKeys(ExactCategoricalSolver.Factor factor) {
		List<?>[] keys = new List<?>[factor.scope().size()];
		int[] values = new int[factor.scope().size()];
		long cells = factorCells(factor);
		for(int position = 0; position < values.length; position++) {
			List<List<Long>> positionKeys = new ArrayList<>();
			for(int selected = 0; selected < factor.scope().get(position).domainSize(); selected++) {
				List<Long> truth = new ArrayList<>();
				for(long cell = 0; cell < cells; cell++) {
					decode(factor, cell, values);
					if(values[position] == selected)
						truth.add(Double.doubleToRawLongBits(factor.cost(values)));
				}
				positionKeys.add(List.copyOf(truth));
			}
			keys[position] = positionKeys;
		}
		return keys;
	}

	private static double cost(ExactCategoricalSolver.Factor factor,
		Map<String,Integer> assignment) {
		return factor.cost(factor.scope().stream().mapToInt(variable -> {
			Integer value = assignment.get(variable.key());
			if(value == null)
				throw new AssertionError("missing assignment for " + variable.key());
			return value;
		}).toArray());
	}

	private static Set<String> factorKeys(ExactCategoricalSolver.Factor factor) {
		return new HashSet<>(factor.scope().stream()
			.map(ExactCategoricalSolver.Variable::key).toList());
	}

	private static long factorCells(ExactCategoricalSolver.Factor factor) {
		return factor.scope().stream().mapToLong(
			ExactCategoricalSolver.Variable::domainSize).reduce(1L, Math::multiplyExact);
	}

	private static void decode(ExactCategoricalSolver.Factor factor, long cell, int[] values) {
		long remainder = cell;
		for(int position = values.length - 1; position >= 0; position--) {
			int radix = factor.scope().get(position).domainSize();
			values[position] = (int) (remainder % radix);
			remainder /= radix;
		}
	}

	private static int verifyPartialProofs(ExactCategoricalSolver.Factor factor, int[] values,
		int position, long[] work) {
		ExactCategoricalSolver.PartialTruth proof = factor.partialTruth(values);
		int outcomes = 0;
		if(position == values.length) {
			double value = factor.cost(values);
			Assert.assertTrue(value == 0.0 || value == Double.POSITIVE_INFINITY);
			outcomes = value == 0.0 ? 1 : 2;
			work[0]++;
			work[value == 0.0 ? 2 : 3]++;
		}
		else {
			for(int value = 0; value < factor.scope().get(position).domainSize(); value++) {
				values[position] = value;
				outcomes |= verifyPartialProofs(factor, values, position + 1, work);
			}
			values[position] = -1;
		}
		if(proof != ExactCategoricalSolver.PartialTruth.UNKNOWN) {
			work[1]++;
			Assert.assertEquals("invalid projected proof for " + Arrays.toString(values),
				proof == ExactCategoricalSolver.PartialTruth.ALL_ZERO ? 1 : 2, outcomes);
		}
		return outcomes;
	}
}

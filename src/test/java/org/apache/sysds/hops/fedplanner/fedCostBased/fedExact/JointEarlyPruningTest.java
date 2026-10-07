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

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for omitting joint predicates whose consumer alternatives make alignment vacuous. */
public class JointEarlyPruningTest {
	private static final long MAX_FACTOR_CELLS = 1_000_000;
	private static final long MAX_TOTAL_CELLS = 5_000_000;
	private enum ExpectedJointOutcomes { ZERO, FORBIDDEN, BOTH }
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void loopMatrixScalarDropsOnlyItsVacuousJointPredicate() throws Exception {
		PlacementAnalysis analysis = analysis(SOURCES
			+ "A=X;i=1;while(i<=2){if(p>0.5){A=X;}else{A=Y;}i=i+1;}"
			+ "C=p*A;print(sum(C));");
		ExactPhysicalModel reference =
			ExactPhysicalModel.buildWithUnprunedJointFactorsForTest(analysis);
		ExactPhysicalModel pruned = ExactPhysicalModel.build(analysis);
		assertSameDomains(reference, pruned);
		Assert.assertEquals("exactly one vacuous joint predicate must be omitted",
			reference.hardFactors().size() - 1, pruned.hardFactors().size());

		int omitted = omittedFactorOrdinal(reference, pruned);
		var factor = reference.hardFactors().get(omitted);
		Assert.assertTrue("the omitted predicate must retain the old partial-truth contract",
			factor.supportsPartialTruth());
		Assert.assertTrue("the omitted predicate must be owned by a published joint consumer",
			isJointPredicate(analysis, reference, factor));
		Assert.assertTrue("the fixture must still publish a joint relation",
			!JointValueMapRelations.from(analysis).isEmpty());

		long cells = factorCells(factor);
		Assert.assertTrue("bounded exhaustive fixture: " + cells, cells <= MAX_FACTOR_CELLS);
		int[] values = new int[factor.scope().size()];
		for(long cell = 0; cell < cells; cell++) {
			decode(factor, cell, values);
			Assert.assertEquals("removed joint predicate must be zero at cell " + cell,
				Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(factor.cost(values)));
		}
		assertCostSurfaceParity(analysis, reference, pruned);
	}

	@Test
	public void correlatedMultiFedRowsKeepTheirJointPredicate() throws Exception {
		assertMultiFedJointRetained(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));",
			ExpectedJointOutcomes.ZERO);
	}

	@Test
	public void independentMultiFedRowsKeepTheirJointPredicate() throws Exception {
		assertMultiFedJointRetained(SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));", ExpectedJointOutcomes.FORBIDDEN);
	}

	@Test
	public void aliasLoopJointPredicateRetainsBothAlignmentOutcomes() throws Exception {
		assertMultiFedJointRetained(SOURCES + "A=X;B=X;i=1;while(i<=2){"
			+ "if(p>0.5){A=Y;}else{A=A;}B=A;i=i+1;}C=A+B;print(sum(C));",
			ExpectedJointOutcomes.BOTH);
	}

	private static void assertMultiFedJointRetained(String script, ExpectedJointOutcomes expected)
		throws Exception {
		PlacementAnalysis analysis = analysis(script);
		ExactPhysicalModel reference =
			ExactPhysicalModel.buildWithUnprunedJointFactorsForTest(analysis);
		ExactPhysicalModel pruned = ExactPhysicalModel.build(analysis);
		assertSameDomains(reference, pruned);
		Assert.assertEquals("multi-FED alignment predicates must not be pruned",
			reference.hardFactors().size(), pruned.hardFactors().size());
		assertJointPredicateOutcomes(analysis, pruned, expected);
		assertAllFactorParity(reference, pruned);
	}

	private static void assertJointPredicateOutcomes(PlacementAnalysis analysis,
		ExactPhysicalModel model, ExpectedJointOutcomes expected) {
		var factors = model.hardFactors().stream()
			.filter(factor -> isJointPredicate(analysis, model, factor)).toList();
		Assert.assertFalse("fixture must retain a joint predicate for a published consumer",
			factors.isEmpty());
		long zero = 0;
		long forbidden = 0;
		for(var factor : factors) {
			long cells = factorCells(factor);
			Assert.assertTrue("bounded exhaustive joint predicate: " + cells,
				cells <= MAX_FACTOR_CELLS);
			int[] values = new int[factor.scope().size()];
			for(long cell = 0; cell < cells; cell++) {
				decode(factor, cell, values);
				double cost = factor.cost(values);
				if(cost == 0.0)
					zero++;
				else if(cost == Double.POSITIVE_INFINITY)
					forbidden++;
				else
					Assert.fail("joint predicate returned a non-hard cost at cell " + cell + ": " + cost);
			}
		}
		if(expected == ExpectedJointOutcomes.ZERO || expected == ExpectedJointOutcomes.BOTH)
			Assert.assertTrue("retained joint predicate must admit an aligned completion", zero > 0);
		if(expected == ExpectedJointOutcomes.FORBIDDEN || expected == ExpectedJointOutcomes.BOTH)
			Assert.assertTrue("retained joint predicate must reject a misaligned completion", forbidden > 0);
	}

	private static boolean isJointPredicate(PlacementAnalysis analysis, ExactPhysicalModel model,
		ExactCategoricalSolver.Factor factor) {
		if(!factor.supportsPartialTruth() || factor.scope().isEmpty())
			return false;
		return JointValueMapRelations.from(analysis).stream().anyMatch(relation ->
			model.domains().stream().anyMatch(domain -> domain.node().key() == relation.consumer()
				&& factor.scope().get(0).equals(domain.variable())));
	}

	private static void assertCostSurfaceParity(PlacementAnalysis analysis,
		ExactPhysicalModel reference, ExactPhysicalModel pruned) {
		var expected = ExactPhysicalCostModel.physicalCostSurface(analysis, reference);
		var actual = ExactPhysicalCostModel.physicalCostSurface(analysis, pruned);
		Assert.assertEquals(expected.contributions().size(), actual.contributions().size());
		long total = 0;
		for(int ordinal = 0; ordinal < expected.contributions().size(); ordinal++) {
			var left = expected.contributions().get(ordinal);
			var right = actual.contributions().get(ordinal);
			Assert.assertEquals("canonical cost contribution changed at ordinal " + ordinal,
				left.id(), right.id());
			long cells = factorCells(left.factor());
			total = Math.addExact(total, cells);
			Assert.assertTrue("bounded canonical cost factor at ordinal " + ordinal + ": " + cells,
				cells <= MAX_FACTOR_CELLS);
			Assert.assertTrue("canonical monetary table changed at ordinal " + ordinal,
				sameFactor(left.factor(), right.factor()));
		}
		Assert.assertTrue("bounded total canonical cost work: " + total, total <= MAX_TOTAL_CELLS);
	}

	private static int omittedFactorOrdinal(ExactPhysicalModel reference, ExactPhysicalModel pruned) {
		for(int omitted = 0; omitted < reference.hardFactors().size(); omitted++) {
			boolean same = true;
			for(int right = 0; right < pruned.hardFactors().size(); right++) {
				int left = right < omitted ? right : right + 1;
				if(!sameFactor(reference.hardFactors().get(left), pruned.hardFactors().get(right))) {
					same = false;
					break;
				}
			}
			if(same)
				return omitted;
		}
		throw new AssertionError("no single omitted factor preserves every surviving predicate");
	}

	private static void assertAllFactorParity(ExactPhysicalModel expected, ExactPhysicalModel actual) {
		long total = 0;
		for(int ordinal = 0; ordinal < expected.hardFactors().size(); ordinal++) {
			var left = expected.hardFactors().get(ordinal);
			var right = actual.hardFactors().get(ordinal);
			long cells = factorCells(left);
			total = Math.addExact(total, cells);
			Assert.assertTrue("bounded factor at ordinal " + ordinal + ": " + cells,
				cells <= MAX_FACTOR_CELLS);
			Assert.assertTrue("hard-factor legality changed at ordinal " + ordinal,
				sameFactor(left, right));
		}
		Assert.assertTrue("bounded total hard-factor work: " + total, total <= MAX_TOTAL_CELLS);
	}

	private static boolean sameFactor(ExactCategoricalSolver.Factor left,
		ExactCategoricalSolver.Factor right) {
		if(!left.scope().stream().map(ExactCategoricalSolver.Variable::key).toList().equals(
			right.scope().stream().map(ExactCategoricalSolver.Variable::key).toList()))
			return false;
		long cells = factorCells(left);
		if(cells != factorCells(right) || cells > MAX_FACTOR_CELLS)
			return false;
		int[] values = new int[left.scope().size()];
		for(long cell = 0; cell < cells; cell++) {
			decode(left, cell, values);
			if(Double.doubleToRawLongBits(left.cost(values))
				!= Double.doubleToRawLongBits(right.cost(values)))
				return false;
		}
		return true;
	}

	private static void assertSameDomains(ExactPhysicalModel expected, ExactPhysicalModel actual) {
		Assert.assertEquals("early factor pruning must not remove decisions",
			expected.domains().size(), actual.domains().size());
		for(int ordinal = 0; ordinal < expected.domains().size(); ordinal++) {
			var left = expected.domains().get(ordinal);
			var right = actual.domains().get(ordinal);
			Assert.assertSame(left.node().key(), right.node().key());
			Assert.assertEquals("early factor pruning must not remove alternatives at domain " + ordinal,
				left.alternatives().stream().map(ExactPhysicalModel.Alternative::signature).toList(),
				right.alternatives().stream().map(ExactPhysicalModel.Alternative::signature).toList());
		}
	}

	private static long factorCells(ExactCategoricalSolver.Factor factor) {
		return factor.scope().stream().mapToLong(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1L, Math::multiplyExact);
	}

	private static void decode(ExactCategoricalSolver.Factor factor, long cell, int[] values) {
		long remainder = cell;
		for(int position = values.length - 1; position >= 0; position--) {
			int radix = factor.scope().get(position).domainSize();
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
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

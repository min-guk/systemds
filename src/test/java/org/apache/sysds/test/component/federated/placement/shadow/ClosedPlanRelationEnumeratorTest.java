/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class ClosedPlanRelationEnumeratorTest {
	@Test
	public void compressedRelationExactlyMatchesRawOrdinalsOnSmallFixtures() throws Exception {
		boolean witnessedCandidatePruning = false;
		for(String id : List.of("B-01", "B-14")) {
			ClosedPlanRelationCoverageVerifier.Result result = ClosedPlanRelationCoverageVerifier.verify(
				analysis(id), BigInteger.valueOf(100_000));
			witnessedCandidatePruning |= result.candidatePrunedCount().signum() > 0;
			Assert.assertTrue(id, result.rawCount().signum() > 0);
			Assert.assertEquals(id, BigInteger.ZERO, result.unknownCount());
			Assert.assertTrue(id, result.rejectedCount().signum() > 0);
			Assert.assertTrue(id, result.acceptedCount().signum() > 0);
		}
		Assert.assertTrue("At least one raw invalid candidate branch must be skipped", witnessedCandidatePruning);
	}

	@Test
	public void b21PilotReportsMeasuredStateFrontierWithoutEnumeratingRawProduct() throws Exception {
		ClosedPlanRelationEnumerator enumerator = new ClosedPlanRelationEnumerator(analysis("B-21"));
		Assert.assertTrue(enumerator.rawCount().compareTo(BigInteger.valueOf(1_000_000)) > 0);
		BigInteger surviving = BigInteger.ZERO;
		BigInteger earlyRejected = BigInteger.ZERO;
		BigInteger earlyUnknown = BigInteger.ZERO;
		for(BigInteger state = BigInteger.ZERO; state.compareTo(enumerator.stateCount()) < 0;
			state = state.add(BigInteger.ONE)) {
			var inspection = enumerator.inspectState(state);
			Assert.assertTrue(inspection.survivingCandidateAssignments().compareTo(
				inspection.rawCandidateAssignments()) <= 0);
			surviving = surviving.add(inspection.survivingCandidateAssignments());
			if(inspection.earlyVerdict() == FullProductionJointPlanExport.Verdict.REJECTED)
				earlyRejected = earlyRejected.add(BigInteger.ONE);
			if(inspection.earlyVerdict() == FullProductionJointPlanExport.Verdict.UNKNOWN)
				earlyUnknown = earlyUnknown.add(BigInteger.ONE);
		}
		System.out.println("B-21 P-native relation pilot: stateCount=" + enumerator.stateCount()
			+ " rawCount=" + enumerator.rawCount() + " survivingCandidateAssignments=" + surviving
			+ " earlyRejectedStates=" + earlyRejected + " earlyUnknownStates=" + earlyUnknown);
	}

	@Test
	public void b21CompressedRelationAccountsForEntireRawProduct() throws Exception {
		ClosedPlanRelationEnumerator enumerator = new ClosedPlanRelationEnumerator(analysis("B-21"));
		List<FullProductionJointPlanExport.Audit> accepted = new ArrayList<>();
		var result = enumerator.enumerateStates(BigInteger.ZERO, enumerator.stateCount(), accepted::add);
		Assert.assertEquals(enumerator.rawCount(), result.raw());
		Assert.assertEquals(BigInteger.ZERO, result.unknown());
		Assert.assertTrue(result.candidatePruned().signum() > 0);
		Assert.assertEquals(result.accepted().intValueExact(), accepted.size());
		Assert.assertEquals(accepted.size(), accepted.stream().map(FullProductionJointPlanExport.Audit::ordinal)
			.distinct().count());
		System.out.println("B-21 P-native compressed full relation: " + result);
	}

	@Test
	public void stateRangesAddToWholeRelationWithoutDuplicateAcceptedOrdinals() throws Exception {
		ClosedPlanRelationEnumerator enumerator = new ClosedPlanRelationEnumerator(analysis("B-14"));
		List<FullProductionJointPlanExport.Audit> whole = new ArrayList<>();
		var all = enumerator.enumerateStates(BigInteger.ZERO, enumerator.stateCount(), whole::add);
		BigInteger cut = enumerator.stateCount().divide(BigInteger.TWO);
		List<FullProductionJointPlanExport.Audit> split = new ArrayList<>();
		var left = enumerator.enumerateStates(BigInteger.ZERO, cut, split::add);
		var right = enumerator.enumerateStates(cut, enumerator.stateCount(), split::add);
		Assert.assertEquals(whole, split);
		Assert.assertEquals(all.raw(), left.raw().add(right.raw()));
		Assert.assertEquals(all.accepted(), left.accepted().add(right.accepted()));
		Assert.assertEquals(all.rejected(), left.rejected().add(right.rejected()));
		Assert.assertEquals(all.unknown(), left.unknown().add(right.unknown()));
		Assert.assertEquals(all.candidatePruned(), left.candidatePruned().add(right.candidatePruned()));
	}

	private static PlacementAnalysis analysis(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

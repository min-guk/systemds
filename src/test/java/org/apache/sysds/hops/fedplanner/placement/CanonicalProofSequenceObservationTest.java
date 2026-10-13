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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.junit.Assert;
import org.junit.Test;

public class CanonicalProofSequenceObservationTest {
	@Test
	public void repeatedExactProofListIdentityIsObservedWithoutChangingCanonicalBytes()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored =
			PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
			CandidateRealizationSupportClause first = clause(List.of(
				proof(repeat("a", 9)), proof(repeat("b", 10))));
			CandidateRealizationSupportClause second =
				clause(first.proofDependencies());
			String firstFlat = legacyFlat(first);
			String secondFlat = legacyFlat(second);
			Assert.assertEquals(firstFlat, canonicalText(first));
			Assert.assertEquals(secondFlat, canonicalText(second));
			Assert.assertEquals(Integer.signum(firstFlat.compareTo(secondFlat)),
				Integer.signum(PlacementAnalysis.compareCanonicalOrdering(first, second)));
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_REQUESTS"));
			Assert.assertEquals(1, work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
			Assert.assertEquals(1, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED_CHILDREN"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_OVERFLOW"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void equalDistinctProofListsRemainDistinctObservations() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored =
			PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
			List<PlacementProofKey> values = List.of(proof("same-a"), proof("same-b"));
			CandidateRealizationSupportClause first = clause(new ArrayList<>(values));
			CandidateRealizationSupportClause second = clause(new ArrayList<>(values));
			Assert.assertEquals(first.proofDependencies(), second.proofDependencies());
			Assert.assertNotSame(first.proofDependencies(), second.proofDependencies());
			canonicalText(first);
			canonicalText(second);
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void emptyAndSingletonListsAreIneligibleBeforeScopeLookup() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			canonicalText(clause(List.of()));
			canonicalText(clause(List.of(proof("one"))));
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_REQUESTS"));
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_INELIGIBLE"));
			Assert.assertEquals("eligibility is classified before missing scope",
				0, work(metrics, "CANONICAL_PROOF_SEQUENCE_NO_SCOPE"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void eligibleSequenceWithoutCanonicalScopeIsCountedButNotRetained()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			CandidateRealizationSupportClause value =
				clause(List.of(proof("left"), proof("right")));
			canonicalText(value);
			canonicalText(value);
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_REQUESTS"));
			Assert.assertEquals(2, work(metrics, "CANONICAL_PROOF_SEQUENCE_NO_SCOPE"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void metricsOffDoesNotCreateObserverOrChangeDescriptors() throws Exception {
		CandidateRealizationSupportClause value = clause(List.of(proof("off-a"), proof("off-b")));
		String expected = legacyFlat(value);
		try(PlacementAnalysis.CanonicalTextScope scope =
			PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
			int entries = scope.retainedEntries();
			Assert.assertEquals(expected, canonicalText(value));
			int afterFirst = scope.retainedEntries();
			Assert.assertTrue(afterFirst > entries);
			Assert.assertEquals(expected, canonicalText(value));
			Assert.assertEquals("diagnostics cannot change canonical descriptor retention",
				afterFirst, scope.retainedEntries());
		}
	}

	@Test
	public void nestedScopesObserveIndependentlyAndRestoreParentGeneration()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			CandidateRealizationSupportClause first =
				clause(List.of(proof("nested-a"), proof("nested-b")));
			CandidateRealizationSupportClause second = clause(first.proofDependencies());
			try(PlacementAnalysis.CanonicalTextScope parent =
				PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
				canonicalText(first);
				long parentFirst = work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST");
				try(PlacementAnalysis.CanonicalTextScope child =
					PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
					canonicalText(first);
					canonicalText(second);
					Assert.assertEquals(parentFirst + 1,
						work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
					Assert.assertEquals(1,
						work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
				}
				canonicalText(second);
				Assert.assertEquals("closing the child restores the parent's exact observer",
					2, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
			}
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void successfulCanonicalGenerationReplacementClearsObserver() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored =
			PlacementAnalysis.beginCanonicalTextScope(1, 1L << 20)) {
			CandidateRealizationSupportClause first =
				clause(List.of(proof("rotation-a"), proof("rotation-b")));
			CandidateRealizationSupportClause second = clause(first.proofDependencies());
			canonicalText(first); // Child/root retention rotates the one-entry generation.
			long before = work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST");
			canonicalText(second);
			Assert.assertTrue("a successful value-generation replacement resets observations",
				work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST") > before);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void observerCapacityIsExactAndDoesNotUseStructuralEquality() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored =
			PlacementAnalysis.beginCanonicalTextScope(10000, 64L << 20)) {
			List<PlacementProofKey> values = List.of(proof("capacity-a"), proof("capacity-b"));
			for(int index = 0; index < 4096; index++)
				canonicalText(clause(new ArrayList<>(values)));
			canonicalText(clause(new ArrayList<>(values)));
			Assert.assertEquals(4096, work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
			Assert.assertEquals(1, work(metrics, "CANONICAL_PROOF_SEQUENCE_OVERFLOW"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void metricResetDoesNotMutateScopeLocalObservationState() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored =
			PlacementAnalysis.beginCanonicalTextScope(64, 1L << 20)) {
			CandidateRealizationSupportClause first =
				clause(List.of(proof("reset-a"), proof("reset-b")));
			CandidateRealizationSupportClause second = clause(first.proofDependencies());
			canonicalText(first);
			metrics.reset();
			canonicalText(second);
			Assert.assertEquals(1, work(metrics, "CANONICAL_PROOF_SEQUENCE_REQUESTS"));
			Assert.assertEquals(0, work(metrics, "CANONICAL_PROOF_SEQUENCE_FIRST"));
			Assert.assertEquals(1, work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED"));
			Assert.assertEquals(2,
				work(metrics, "CANONICAL_PROOF_SEQUENCE_REPEATED_CHILDREN"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void oldFlatOracleCoversUtf16AndDecimalLengthBoundaries() throws Exception {
		List<String> authorities = List.of(repeat("x", 9), repeat("y", 10), repeat("z", 99),
			repeat("w", 100), "\u0000|:,", "\uD800", "\uDC00", "\uD83D\uDE00");
		for(int index = 0; index + 1 < authorities.size(); index++) {
			CandidateRealizationSupportClause left = clause(List.of(
				proof(authorities.get(index)), proof("suffix-a")));
			CandidateRealizationSupportClause right = clause(List.of(
				proof(authorities.get(index + 1)), proof("suffix-b")));
			String leftFlat = legacyFlat(left), rightFlat = legacyFlat(right);
			Assert.assertEquals(leftFlat, canonicalText(left));
			Assert.assertEquals(rightFlat, canonicalText(right));
			Assert.assertEquals(Integer.signum(leftFlat.compareTo(rightFlat)),
				Integer.signum(PlacementAnalysis.compareCanonicalOrdering(left, right)));
		}
	}

	private static CandidateRealizationSupportClause clause(List<PlacementProofKey> proofs) {
		return new CandidateRealizationSupportClause(proofs, List.of());
	}

	private static PlacementProofKey proof(String authority) {
		return new PlacementProofKey(PlacementProofKind.SHAPE, null, authority);
	}

	private static String legacyFlat(CandidateRealizationSupportClause clause) {
		StringBuilder result = new StringBuilder("proofs=[");
		for(int index = 0; index < clause.proofDependencies().size(); index++) {
			if(index > 0)
				result.append(", ");
			result.append(clause.proofDependencies().get(index).normalizedSignature());
		}
		result.append("]|inputs=[");
		for(int index = 0; index < clause.inputBindings().size(); index++) {
			if(index > 0)
				result.append(", ");
			result.append(clause.inputBindings().get(index).normalizedSignature());
		}
		result.append("]|nativePool=");
		if(clause.nativeWorkerPoolWitness() == null)
			result.append('-');
		else {
			result.append(clause.nativeWorkerPoolWitness().normalizedSignature());
			if(!clause.nativeWorkerPoolLayoutExact())
				result.append("|nativePoolLayout=dynamic");
		}
		return result.toString();
	}

	private static String canonicalText(CandidateRealizationSupportClause clause) throws Exception {
		Class<?> contextType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextContext");
		Constructor<?> contextConstructor = contextType.getDeclaredConstructor();
		contextConstructor.setAccessible(true);
		Object context = contextConstructor.newInstance();
		Method orderingKey = PlacementAnalysis.class.getDeclaredMethod(
			"canonicalOrderingKey", Object.class, contextType);
		orderingKey.setAccessible(true);
		Object text = orderingKey.invoke(null, clause, context);
		Class<?> normalizedType = Class.forName(PlacementAnalysis.class.getName() + "$NormalizedText");
		Constructor<?> normalizedConstructor = normalizedType.getDeclaredConstructor(text.getClass());
		normalizedConstructor.setAccessible(true);
		PlacementAnalysis.NormalizedText normalized =
			(PlacementAnalysis.NormalizedText)normalizedConstructor.newInstance(text);
		return normalized.materialize();
	}

	private static long work(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(SearchSpaceMetrics.DirectWork.valueOf(name));
	}

	private static String repeat(String value, int count) {
		return value.repeat(count);
	}
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Cross-checks the compressed relation with every no-prune raw ordinal on a bounded fixture. */
public final class ClosedPlanRelationCoverageVerifier {
	private ClosedPlanRelationCoverageVerifier() { }

	public record Result(BigInteger rawCount, BigInteger acceptedCount, BigInteger rejectedCount,
		BigInteger unknownCount, BigInteger candidatePrunedCount) { }

	public static Result verify(PlacementAnalysis analysis, BigInteger maximumRawRows) {
		FullProductionJointPlanExport raw = new FullProductionJointPlanExport(analysis);
		ClosedPlanRelationEnumerator compressed = new ClosedPlanRelationEnumerator(analysis);
		if(!raw.rawCount().equals(compressed.rawCount()))
			throw new AssertionError("Raw product cardinality differs");
		if(raw.rawCount().compareTo(maximumRawRows) > 0)
			throw new IllegalArgumentException("Raw fixture exceeds verifier limit: " + raw.rawCount());
		List<FullProductionJointPlanExport.Audit> noPruneAccepted = new ArrayList<>();
		BigInteger[] noPruneCounts = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
		raw.stream(BigInteger.ZERO, raw.rawCount(), row -> {
			switch(row.verdict()) {
				case ACCEPTED -> {
					noPruneAccepted.add(row);
					noPruneCounts[0] = noPruneCounts[0].add(BigInteger.ONE);
				}
				case REJECTED -> noPruneCounts[1] = noPruneCounts[1].add(BigInteger.ONE);
				case UNKNOWN -> noPruneCounts[2] = noPruneCounts[2].add(BigInteger.ONE);
			}
		});
		List<FullProductionJointPlanExport.Audit> relationAccepted = new ArrayList<>();
		ClosedPlanRelationEnumerator.Summary summary = compressed.enumerateStates(BigInteger.ZERO,
			compressed.stateCount(), relationAccepted::add);
		if(!summary.raw().equals(raw.rawCount()) || !summary.accepted().equals(noPruneCounts[0])
			|| !summary.rejected().equals(noPruneCounts[1]) || !summary.unknown().equals(noPruneCounts[2]))
			throw new AssertionError("Compressed/raw verdict counts differ: compressed=" + summary
				+ " raw=" + List.of(noPruneCounts));
		Set<BigInteger> rawOrdinals = new HashSet<>();
		for(var row : noPruneAccepted)
			rawOrdinals.add(row.ordinal());
		Set<BigInteger> relationOrdinals = new HashSet<>();
		for(var row : relationAccepted)
			relationOrdinals.add(row.ordinal());
		if(noPruneAccepted.size() != rawOrdinals.size() || relationAccepted.size() != relationOrdinals.size()
			|| !rawOrdinals.equals(relationOrdinals))
			throw new AssertionError("Compressed/raw accepted ordinal sets differ: missing="
				+ difference(rawOrdinals, relationOrdinals) + " extra="
				+ difference(relationOrdinals, rawOrdinals));
		return new Result(summary.raw(), summary.accepted(), summary.rejected(), summary.unknown(),
			summary.candidatePruned());
	}

	private static Set<BigInteger> difference(Set<BigInteger> left, Set<BigInteger> right) {
		Set<BigInteger> result = new HashSet<>(left);
		result.removeAll(right);
		return result;
	}
}

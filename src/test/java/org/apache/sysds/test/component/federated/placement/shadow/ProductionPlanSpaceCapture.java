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

package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;

/**
 * Production-side capture for differential tests. This exposes every published
 * candidate and every excluded rule fact without deciding whether any joint plan
 * is legal. The independent oracle must never use this capture to create its own
 * primitive choices or legality rules.
 */
public final class ProductionPlanSpaceCapture {
	private ProductionPlanSpaceCapture() { }

	public record Binding(int position, String sourceRule, String sourceRealization,
		String kind, String relocationAction) { }

	public record Row(String owner, String rule, String emissionState, String realization,
		String supportClause, String derivedFoutAction, String authority,
		List<Binding> bindings, String diagnosticSignature) {
		public Row {
			bindings = List.copyOf(bindings);
		}
	}

	public record RejectedRule(String owner, String rule, String status, String failureCode) { }

	public record Snapshot(List<Row> rows, List<RejectedRule> rejectedRules, int availableRuleCount) {
		public Snapshot {
			rows = List.copyOf(rows);
			rejectedRules = List.copyOf(rejectedRules);
		}
	}

	public static Snapshot capture(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		List<Row> rows = new ArrayList<>();
		List<RejectedRule> rejected = new ArrayList<>();
		int available = 0;
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			String owner = fact.key().parentOccurrence().normalizedSignature();
			String rule = fact.key().normalizedSignature();
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				rejected.add(new RejectedRule(owner, rule, fact.status().name(), fact.failureCode()));
				continue;
			}
			available++;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateSelectionReceipt receipt : analysis.canonicalCandidateReceipts(fact.key(), emission))
					rows.add(capture(receipt));
		}
		rows.sort(Comparator.comparing(Row::owner).thenComparing(Row::rule)
			.thenComparing(Row::diagnosticSignature));
		rejected.sort(Comparator.comparing(RejectedRule::owner).thenComparing(RejectedRule::rule));
		return new Snapshot(rows, rejected, available);
	}

	private static Row capture(CandidateSelectionReceipt receipt) {
		List<Binding> bindings = receipt.supportClause().inputBindings().stream()
			.map(ProductionPlanSpaceCapture::capture).toList();
		return new Row(receipt.rule().parentOccurrence().normalizedSignature(),
			receipt.rule().normalizedSignature(),
			receipt.emission().emissionState().normalizedSignature(),
			receipt.realization().key().normalizedSignature(),
			receipt.supportClause().normalizedSignature(),
			receipt.emission().derivedFoutAction() == null ? ""
				: receipt.emission().derivedFoutAction().normalizedSignature(),
			receipt.provenWorkerPool() == null ? ""
				: receipt.provenWorkerPool().normalizedSignature(),
			bindings, receipt.normalizedSignature());
	}

	private static Binding capture(CandidateRealizationInputBinding binding) {
		return new Binding(binding.inputPosition(),
			binding.source().rule().normalizedSignature(),
			binding.source().realization().normalizedSignature(),
			binding.kind().name(),
			binding.relocationAction() == null ? ""
				: binding.relocationAction().normalizedSignature());
	}
}

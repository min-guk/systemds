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
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ValueMapReferenceIndexTest {
	@Test
	public void indexMatchesColdScanOrderingDeduplicationAndOwnerIdentity() throws Exception {
		CompiledHopKey owner = key("owner");
		CompiledHopKey foreign = key("owner");
		Assert.assertEquals(owner, foreign);
		Assert.assertNotSame(owner, foreign);
		CandidateRuleKey ownerRule = new CandidateRuleKey(owner, List.of());
		CandidateRuleKey foreignRule = new CandidateRuleKey(foreign, List.of());
		CandidateEmissionRealization rowZ = valueMap(FType.ROW, "z");
		CandidateEmissionRealization rowA = valueMap(FType.ROW, "a");
		CandidateEmissionRealization col = valueMap(FType.COL, "col");
		List<CandidateRuleFact> facts = List.of(
			fact(ownerRule, CandidateEvaluationStatus.AVAILABLE, rowZ),
			fact(ownerRule, CandidateEvaluationStatus.AVAILABLE, rowA),
			fact(ownerRule, CandidateEvaluationStatus.AVAILABLE, rowA),
			fact(ownerRule, CandidateEvaluationStatus.AVAILABLE, col),
			fact(ownerRule, CandidateEvaluationStatus.PRIVACY_EXCLUDED, valueMap(FType.ROW, "excluded")),
			fact(foreignRule, CandidateEvaluationStatus.AVAILABLE, valueMap(FType.ROW, "foreign")));
		Object index = index(facts);

		for(FType type : List.of(FType.ROW, FType.COL))
			Assert.assertEquals(cold(owner, type, facts), indexed(index, owner, type));
		List<CandidateRealizationReference> rows = indexed(index, owner, FType.ROW);
		Assert.assertEquals("duplicate structural references must collapse", 2, rows.size());
		List<String> signatures = rows.stream().map(CandidateRealizationReference::normalizedSignature).toList();
		List<String> sorted = new ArrayList<>(signatures);
		sorted.sort(String::compareTo);
		Assert.assertEquals("the index must retain canonical reference order", sorted, signatures);
		Assert.assertEquals(cold(foreign, FType.ROW, facts), indexed(index, foreign, FType.ROW));
		Assert.assertEquals("a structurally equal foreign owner has separate authority", 1,
			indexed(index, foreign, FType.ROW).size());
		Assert.assertTrue(indexed(index, key("absent"), FType.ROW).isEmpty());
	}

	@Test
	public void indexReportsEmptyWhenNoValueMapExists() throws Exception {
		CompiledHopKey owner = key("local");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState state = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		CandidateEmissionRealization local = CandidateEmissionRealization.local(state);
		Object index = index(List.of(fact(rule, CandidateEvaluationStatus.AVAILABLE, local)));
		Method empty = index.getClass().getDeclaredMethod("isEmpty");
		empty.setAccessible(true);
		Assert.assertEquals(Boolean.TRUE, empty.invoke(index));
	}

	private static CandidateEmissionRealization valueMap(FType type, String id) {
		PlacementEmissionState state = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, type, false), false);
		return CandidateEmissionRealization.valueMap(state, id,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, CandidateEvaluationStatus status,
		CandidateEmissionRealization... realizations) {
		PlacementEmissionState state = realizations[0].key().emissionState();
		FType type = state.placementState().fType();
		List<CandidateEmissionFact> emissions = status == CandidateEvaluationStatus.AVAILABLE
			? java.util.Arrays.stream(realizations).map(realization -> new CandidateEmissionFact(
				realization.key().emissionState(), realization.placementState().fType(), null,
				List.of(realization))).toList() : List.of();
		return new CandidateRuleFact(key, status,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, type, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(type == null ? List.of() : List.of(type), ""),
			emissions,
			status == CandidateEvaluationStatus.AVAILABLE ? "" : "excluded");
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"value-map-index", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("value-map-index", "main", "main", "compiled",
			region, name, name);
	}

	private static Object index(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(
			PlacementRelationClosure.class.getName() + "$ValueMapReferenceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> indexed(Object index,
		CompiledHopKey owner, FType type) throws Exception {
		Method references = index.getClass().getDeclaredMethod(
			"references", CompiledHopKey.class, FType.class);
		references.setAccessible(true);
		return (List<CandidateRealizationReference>)references.invoke(index, owner, type);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> cold(CompiledHopKey owner,
		FType type, List<CandidateRuleFact> facts) throws Exception {
		Method references = PlacementRelationClosure.class.getDeclaredMethod(
			"valueMapReferences", CompiledHopKey.class, FType.class, List.class);
		references.setAccessible(true);
		return (List<CandidateRealizationReference>)references.invoke(null, owner, type, facts);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file to You under
 * the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Closure-to-storage regression for exact repeated-owner support choices. */
public class CorrelatedFactorizedSupportStorageTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"correlated-storage", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState LOCAL =
		new PlacementEmissionState(LOCAL_STATE, false);

	@Test
	public void closureStoresRepeatedOwnerProductBySourceChoices() throws Exception {
		CompiledHopKey shared = key("shared"), independent = key("independent");
		RelocationActionKey action = action("uniform");
		List<CandidateRealizationInputBinding> first = new ArrayList<>();
		List<CandidateRealizationInputBinding> second = new ArrayList<>();
		List<CandidateRealizationInputBinding> third = new ArrayList<>();
		for(int option = 0; option < 100; option++) {
			CandidateRealizationReference sharedChoice = durableReference(shared, "shared-" + option);
			first.add(CandidateRealizationInputBinding.relocation(0, sharedChoice, action));
			second.add(CandidateRealizationInputBinding.relocation(1, sharedChoice, action));
			third.add(CandidateRealizationInputBinding.relocation(2,
				durableReference(independent, "independent-" + option), action));
		}

		List<CandidateEmissionRealization> generated = generate(
			new CandidateEmissionFact(LOCAL, FType.ROW), List.of(first, second, third));
		Assert.assertEquals(1, generated.size());
		Assert.assertTrue(generated.get(0).supportClauses() instanceof FactorizedSupportClauses);
		FactorizedSupportClauses relation =
			(FactorizedSupportClauses)generated.get(0).supportClauses();
		Assert.assertEquals(10_000, relation.size());
		Assert.assertEquals(200, relation.retainedFactorOptionCount());
		Assert.assertEquals(2, relation.choiceGroups().size());
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generate(CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"generateRelocationBindingProduct", CompiledHopKey.class, CandidateEmissionFact.class,
			List.class, DurableAnchorKey.class, boolean.class, DurableAnchorKey.class,
			SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(null, CONSUMER, emission,
			choices, null, false, null, null);
	}

	private static CandidateRealizationReference durableReference(
		CompiledHopKey owner, String identity) {
		return new CandidateRealizationReference(new CandidateRuleKey(owner, List.of()),
			PlacementRealizationKey.durable(new PlacementEmissionState(new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false), anchor(identity)));
	}

	private static RelocationActionKey action(String identity) {
		return new RelocationActionKey(new ValueVersionKey("correlated-storage", identity,
			REGION, 0, VersionKind.ORDINARY, List.of()), LOCAL_STATE, FType.ROW,
			anchor(identity), REGION.normalizedSignature(), List.of(CONSUMER));
	}

	private static DurableAnchorKey anchor(String identity) {
		return new DurableAnchorKey(identity, FType.ROW, List.of(new AnchorPartition(
			"localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String identity) {
		return new CompiledHopKey("correlated-storage", "main", "root", "compiled",
			REGION, identity, identity);
	}
}

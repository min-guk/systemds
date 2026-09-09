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

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Contract for closing privacy-filtered derived materializations over their exact row-owned source. */
public class PrivacyDerivedMaterializationClosureTest {
	@Test
	public void survivingNativeSourceRetainsDerivedMaterialization() {
		Fixture fixture = fixture();
		Assert.assertEquals(List.of(fixture.nativeSource(), fixture.derivedTarget()),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(
				List.of(fixture.nativeSource(), fixture.derivedTarget())));
	}

	@Test
	public void removedSourceDropsOnlyDerivedTargetAndPreservesNativeFout() {
		Fixture fixture = fixture();
		Assert.assertEquals(List.of(fixture.nativeFout()),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(
				List.of(fixture.derivedTarget(), fixture.nativeFout())));
	}

	@Test
	public void equalSourceInAnotherCandidateRowCannotRescueDerivedTarget() {
		Fixture fixture = fixture();
		Assert.assertEquals(List.of(fixture.nativeSource()),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(
				List.of(fixture.nativeSource())));
		Assert.assertEquals(List.of(fixture.nativeFout()),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(
				List.of(fixture.derivedTarget(), fixture.nativeFout())));
	}

	@Test
	public void sourceWitnessRequiresExactFTypeAndShapeIdentity() {
		Fixture fixture = fixture();
		PlacementState wrongFType = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.COL, false);
		PlacementState wrongShapeDependence = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, true);
		CandidateEmissionFact colSource = new CandidateEmissionFact(
			new PlacementEmissionState(wrongFType, false), FType.COL);
		CandidateEmissionFact shapeDependentSource = new CandidateEmissionFact(
			new PlacementEmissionState(wrongShapeDependence, false), FType.ROW);

		Assert.assertEquals(List.of(colSource, shapeDependentSource),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(
				List.of(colSource, shapeDependentSource, fixture.derivedTarget())));
	}

	private static Fixture fixture() {
		String fingerprint = "privacy-derived-source-closure";
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
			"main", "compiled");
		CompiledHopKey producer = new CompiledHopKey(fingerprint, "main", "main", "compiled",
			region, "producer", "producer");
		CompiledHopKey anchorOwner = new CompiledHopKey(fingerprint, "main", "main", "compiled",
			region, "anchor", "anchor");
		ValueVersionKey value = new ValueVersionKey(fingerprint, "value", region, 0,
			VersionKind.ORDINARY, List.of());
		DurableAnchorKey anchor = new DurableAnchorKey("row-workers", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 4L))));
		PlacementState source = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		PlacementState target = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		CandidateRuleKey rule = new CandidateRuleKey(producer,
			List.of(CandidateInputState.present(FType.ROW)));
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			producer, value, rule, source, target, anchor, anchorOwner, FType.ROW, FType.ROW,
			region.normalizedSignature());
		CandidateEmissionFact nativeSource = new CandidateEmissionFact(
			new PlacementEmissionState(source, false), FType.ROW);
		CandidateEmissionFact derivedTarget = new CandidateEmissionFact(
			new PlacementEmissionState(target, true), FType.ROW, action);
		CandidateEmissionFact nativeFout = new CandidateEmissionFact(
			new PlacementEmissionState(target, false), FType.ROW);
		return new Fixture(nativeSource, derivedTarget, nativeFout);
	}

	private record Fixture(CandidateEmissionFact nativeSource,
		CandidateEmissionFact derivedTarget, CandidateEmissionFact nativeFout) { }
}

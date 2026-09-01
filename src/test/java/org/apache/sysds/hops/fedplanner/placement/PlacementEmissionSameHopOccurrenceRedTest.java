/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** RED for occurrence-distinct decisions that share one concrete compiled Hop. */
public class PlacementEmissionSameHopOccurrenceRedTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED_LOCAL =
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false);
	private Fixture fixture;

	@Before
	public void setUp() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		fixture = fixture();
	}

	@After
	public void tearDown() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
	}

	@Test
	public void identicalOccurrenceStatesCoalesceToOneConcreteHopMutation() {
		Map<CompiledHopKey, PlacementState> selected = selected(LOCAL, LOCAL);
		NormalizedPlannerResult result = result(selected);

		Assert.assertEquals("P4_SHARED_HOP_RETAINS_TWO_OCCURRENCE_DECISIONS", 2, selected.size());
		Assert.assertNotSame("P4_SHARED_HOP_KEYS_REMAIN_OCCURRENCE_DISTINCT", fixture.first(), fixture.second());
		Assert.assertSame("P4_FIXTURE_PROJECTS_ONE_CONCRETE_HOP", fixture.analysis().hop(fixture.first()).orElseThrow(),
			fixture.analysis().hop(fixture.second()).orElseThrow());

		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), result, FailureInjector.none());

		Assert.assertTrue("P4_COMPATIBLE_SHARED_HOP_PLAN_APPLIES", receipt.applied());
		Assert.assertEquals("P4_SHARED_HOP_MUTATES_ONCE", 1, receipt.hopMutations());
		Assert.assertEquals("P4_NO_DECISION_DROPPED_FROM_CANONICAL_HASH",
			PlacementEmissionTransaction.canonicalPlanHash(result), receipt.planHash());
		Assert.assertEquals("P4_SHARED_HOP_HAS_EXACT_SELECTED_EXEC", LOCAL.execType(), fixture.hop().getExecType());
		Assert.assertEquals("P4_SHARED_HOP_HAS_EXACT_SELECTED_OUTPUT", LOCAL.output(),
			fixture.hop().getFederatedOutput());
	}

	@Test
	public void conflictingOccurrenceStatesFailClosedBeforeAnyOwnedMutation() {
		seedRegistries();
		State before = snapshot();
		NormalizedPlannerResult conflicting = result(selected(LOCAL, FED_LOCAL));

		try {
			PlacementEmissionTransaction.emit(fixture.program(), conflicting, FailureInjector.none());
			Assert.fail("P4_CONFLICTING_SHARED_HOP_STATES_MUST_FAIL_CLOSED");
		}
		catch(IllegalStateException expected) {
			// Exact failure category is owned by the transaction core.
		}

		Assert.assertEquals("P4_SHARED_HOP_CONFLICT_PRECEDES_ALL_MUTATION", before, snapshot());
	}

	private Map<CompiledHopKey, PlacementState> selected(PlacementState first, PlacementState second) {
		Map<CompiledHopKey, PlacementState> selected = new LinkedHashMap<>();
		selected.put(fixture.first(), first);
		selected.put(fixture.second(), second);
		return Map.copyOf(selected);
	}

	private NormalizedPlannerResult result(Map<CompiledHopKey, PlacementState> selected) {
		NormalizedPlannerResult draft = normalized(selected, "unused");
		return normalized(selected, PlacementEmissionTransaction.canonicalPlanHash(draft));
	}

	private NormalizedPlannerResult normalized(Map<CompiledHopKey, PlacementState> selected, String planHash) {
		return new NormalizedPlannerResult() {
			@Override public PlacementAnalysis analysis() { return fixture.analysis(); }
			@Override public String plannerId() { return "SAME_HOP_OCCURRENCE_RED"; }
			@Override public String analysisFingerprint() { return fixture.analysis().analysisFingerprint(); }
			@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return selected; }
			@Override public List<RelocationActionKey> selectedRelocations() { return List.of(); }
			@Override public String objectiveCertificate() { return "exact-occurrence-assignment"; }
			@Override public String normalizedPlanFingerprint() { return planHash; }
		};
	}

	private State snapshot() {
		Hop hop = fixture.hop();
		return new State(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
			hop.isFederatedOutputDerived(), FederatedRefedRegistry.snapshotAll(),
			FederatedFoutMaterializeRegistry.snapshotAll(), FederatedLocalMaterializeRegistry.snapshotAll(),
			PlacementEmissionTransaction.receiptSnapshotForTesting(),
			PlacementEmissionTransaction.observabilitySnapshot());
	}

	private static Fixture fixture() {
		String fingerprint = "same-concrete-hop-occurrence-red";
		ControlRegionKey firstRegion = new ControlRegionKey(fingerprint, "main", List.of("sb-1"),
			"main", "compiled");
		ControlRegionKey secondRegion = new ControlRegionKey(fingerprint, "main", List.of("sb-2"),
			"main", "compiled");
		CompiledHopKey first = new CompiledHopKey(fingerprint, "main", "main", "compiled", firstRegion,
			"shared-hop@sb-1", "shared-hop");
		CompiledHopKey second = new CompiledHopKey(fingerprint, "main", "main", "compiled", secondRegion,
			"shared-hop@sb-2", "shared-hop");
		Node firstNode = node(first, firstRegion, 0);
		Node secondNode = node(second, secondRegion, 1);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(firstNode, secondNode), List.of(), List.of());
		LiteralOp shared = new LiteralOp(7L);
		List<HopOccurrenceProjection> occurrences = List.of(
			new HopOccurrenceProjection(first, shared, 1L, 0, first.normalizedSignature()),
			new HopOccurrenceProjection(second, shared, 2L, 1, second.normalizedSignature()));
		Map<CompiledHopKey, NodeShapeFact> shapes = Map.of(first,
			new NodeShapeFact(DataType.SCALAR, -1, -1), second,
			new NodeShapeFact(DataType.SCALAR, -1, -1));
		FixtureProgram program = new FixtureProgram();
		PlacementAnalysis analysis = new PlacementAnalysis(graph, occurrences, program,
			new PlacementShapeFacts(shapes, shapes.keySet()), "same-hop-analysis", new HeuristicPolicyFacts(List.of()));
		program.install(analysis);
		return new Fixture(program, analysis, shared, first, second);
	}

	private static Node node(CompiledHopKey key, ControlRegionKey region, int ordinal) {
		ValueVersionKey value = new ValueVersionKey(key.programFingerprint(), "shared", region, ordinal,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, List.of(LOCAL, FED_LOCAL), List.of(), List.of());
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.register(9001L, 11L, 12L, "seed-anchor", java.util.List.of(13L));
		FederatedFoutMaterializeRegistry.register(9002L, 21L, 22L, "ROW", "seed", "seed-anchor");
		FederatedLocalMaterializeRegistry.register(9003L, 31L, List.of(32L), "ROW", "seed");
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private record Fixture(FixtureProgram program, PlacementAnalysis analysis, Hop hop,
		CompiledHopKey first, CompiledHopKey second) { }

	private record State(ExecType execType, ExecType forcedExecType, FederatedOutput output,
		boolean outputDerived, FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout, FederatedLocalMaterializeRegistry.Snapshot local,
		Map<DMLProgram, PlacementEmissionTransaction.PlacementEmissionReceipt> receipts,
		PlacementEmissionTransaction.ObservabilitySnapshot observability) { }

	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;

		private void install(PlacementAnalysis analysis) {
			authority = analysis;
		}

		@Override
		public PlacementAnalysis requirePlacementAnalysisAuthority() {
			return authority;
		}

		@Override
		public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate == null || candidate != authority)
				throw new IllegalArgumentException("Placement analysis is not the canonical fixture owner");
		}
	}
}

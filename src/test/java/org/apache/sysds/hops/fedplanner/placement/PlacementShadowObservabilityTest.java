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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator.Observation;
import org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator.ObservationReason;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class PlacementShadowObservabilityTest {
	@After
	public void clearRecordedObservation() {
		PlacementShadowCoordinator.clearRecordedObservationForTesting();
	}

	@Test
	public void cleanObservationIsRecordedWithDeterministicEvidence() {
		NeutralPlacementGraph graph = emptyGraph();
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(graph, graph),
			List.of(List.of("selected"), List.of("selected")), List.of());
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertEquals(List.of(ObservationReason.CLEAN), observation.reasons());
		Assert.assertEquals("reasons=[CLEAN]", observation.normalizedEvidence());
		Assert.assertSame(observation, PlacementShadowCoordinator.lastRecordedObservation());
	}

	@Test
	public void graphSurfaceDriftIsRecordedWithSurfaceEvidence() throws Exception {
		NeutralPlacementGraph baseline = emptyGraph();
		NeutralPlacementGraph changed = new NeutralPlacementGraphBuilder()
			.build(ProductionShadowFixtureFactory.compile("B-10"));
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(baseline, changed),
			List.of(List.of(), List.of()), List.of());
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertTrue(observation.reasons().contains(ObservationReason.GRAPH_SURFACE_DIFF));
		Assert.assertTrue(observation.normalizedEvidence(),
			observation.normalizedEvidence().contains("graph.candidates="));
	}

	@Test
	public void selectedProjectionDriftIsRecorded() {
		NeutralPlacementGraph graph = emptyGraph();
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(graph, graph),
			List.of(List.of("before"), List.of("after")), List.of());
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertEquals(List.of(ObservationReason.SELECTED_PROJECTION_DIFF), observation.reasons());
		Assert.assertTrue(observation.normalizedEvidence().contains("selected.diff=[+after, -before]"));
	}

	@Test
	public void selectedMembershipViolationIsRecorded() {
		NeutralPlacementGraph graph = emptyGraph();
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(graph, graph),
			List.of(List.of(), List.of()), List.of("node|FED/FOUT/ROW"));
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertEquals(List.of(ObservationReason.SELECTED_MEMBERSHIP_VIOLATION), observation.reasons());
		Assert.assertTrue(observation.normalizedEvidence().contains("membership=[node|FED/FOUT/ROW]"));
	}

	@Test
	public void beginFailureIsRecordedWithoutThrowing() {
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(), List.of(List.of("before")), List.of());
		analysis.failBuildCall = 0;
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertEquals(List.of(ObservationReason.BEGIN_FAILURE), observation.reasons());
		Assert.assertTrue(observation.normalizedEvidence().contains("failure.phase=begin"));
	}

	@Test
	public void observeFailureIsRecordedWithoutThrowing() {
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(emptyGraph()),
			List.of(List.of("before"), List.of("after")), List.of());
		analysis.failBuildCall = 1;
		Observation observation = observe(new DMLProgram(), analysis);

		Assert.assertEquals(List.of(ObservationReason.OBSERVE_FAILURE), observation.reasons());
		Assert.assertTrue(observation.normalizedEvidence().contains("failure.phase=observe"));
	}

	@Test
	public void observationAndRecordingDoNotMutatePlannerOutput() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-10");
		String before = PlacementGraphFingerprint.capture(program);
		NeutralPlacementGraph graph = new NeutralPlacementGraphBuilder().build(program);
		SequenceAnalysis analysis = new SequenceAnalysis(List.of(graph, graph),
			List.of(List.of("planner-output"), List.of("planner-output")), List.of());

		Observation observation = observe(program, analysis);
		Assert.assertEquals(before, PlacementGraphFingerprint.capture(program));
		Assert.assertEquals(observation.selectedBefore(), observation.selectedAfter());
	}

	@Test
	public void ipaRecordsRatherThanDiscardsReturnedObservation() throws Exception {
		String source = Files.readString(Path.of(
			"src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java"));
		Assert.assertTrue("IPA must consume and record the returned shadow observation",
			source.contains("PlacementShadowCoordinator.record(shadow.observe(prog));"));
	}

	private static Observation observe(DMLProgram program, SequenceAnalysis analysis) {
		Observation observation = PlacementShadowCoordinator.begin(program, analysis).observe(program);
		PlacementShadowCoordinator.record(observation);
		return observation;
	}

	private static NeutralPlacementGraph emptyGraph() {
		return new NeutralPlacementGraph(List.of(), List.of(), List.of());
	}

	private static final class SequenceAnalysis implements PlacementShadowCoordinator.ShadowAnalysis {
		private final List<NeutralPlacementGraph> graphs;
		private final List<List<String>> projections;
		private final List<String> violations;
		private int buildCall;
		private int projectionCall;
		private int failBuildCall = -1;

		private SequenceAnalysis(List<NeutralPlacementGraph> graphs, List<List<String>> projections,
			List<String> violations) {
			this.graphs = new ArrayList<>(graphs);
			this.projections = new ArrayList<>(projections);
			this.violations = List.copyOf(violations);
		}

		@Override
		public NeutralPlacementGraph build(DMLProgram program) {
			if(buildCall == failBuildCall) {
				buildCall++;
				throw new IllegalStateException("deliberate-build-failure");
			}
			return graphs.get(buildCall++);
		}

		@Override
		public List<String> selectedProjection(DMLProgram program) {
			return projections.get(projectionCall++);
		}

		@Override
		public List<String> selectedMembershipViolations(DMLProgram program, NeutralPlacementGraph graph) {
			return violations;
		}
	}
}

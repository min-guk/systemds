/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

/** Test-only lifecycle evidence capture for G014 A2. */
public final class AnchorProvenanceLifecycleCapture {
	private static final String ANCHORED_UPLOAD_FIXTURE = "B-11";
	private static final String LOOP_FIXTURE = "B-05";
	private static final String RECOMPILE_FIXTURE = "B-09";
	private static final String ADDITIONAL_ROOT_FIXTURE = "B-10";

	public record LifecycleDurabilityReceipt(BoundaryComparison afterCleanup,
		BoundaryComparison afterClone, BoundaryComparison afterUnroll,
		BoundaryComparison afterAdditionalRoots, BoundaryComparison afterRecompile) {
		public List<BoundaryComparison> boundaries() {
			return List.of(afterCleanup, afterClone, afterUnroll, afterAdditionalRoots, afterRecompile);
		}

		public boolean cleanupBoundaryExecuted() { return afterCleanup.cleanupBoundaryExecuted(); }
		public boolean cloneUnrollBoundaryExecuted() { return afterClone.cloneUnrollBoundaryExecuted()
			&& afterUnroll.cloneUnrollBoundaryExecuted(); }
		public boolean additionalRootsBoundaryExecuted() {
			return afterAdditionalRoots.additionalRootsBoundaryExecuted();
		}
		public boolean registrySnapshotClearReconstructExecuted() {
			return afterCleanup.registrySnapshotClearReconstructExecuted();
		}
		public boolean recompileBoundaryExecuted() { return afterRecompile.recompileBoundaryExecuted(); }
		public String normalizedDigest() { return digest(boundaries().stream()
			.flatMap(boundary -> boundary.evidence().stream()).sorted().toList().toString()); }
	}

	public record BoundaryComparison(String stage, String fixtureId, AnchorSnapshot before, AnchorSnapshot after,
		boolean allowedCpFoutInRecompile, boolean realLifecycleEvidence, boolean cleanupBoundaryExecuted,
		boolean cloneUnrollBoundaryExecuted, boolean additionalRootsBoundaryExecuted,
		boolean registrySnapshotClearReconstructExecuted, boolean recompileBoundaryExecuted, List<String> evidence) {
		public BoundaryComparison {
			if(stage == null || stage.isBlank())
				throw new IllegalArgumentException("stage must not be blank");
			if(fixtureId == null || fixtureId.isBlank())
				throw new IllegalArgumentException("fixtureId must not be blank");
			evidence = List.copyOf(evidence);
		}
		public boolean sameAnchorFacts() { return before.facts().equals(after.facts()); }
		public boolean sameCanonicalOrigins() { return before.canonicalOrigins().equals(after.canonicalOrigins()); }
		public boolean sameOccurrences() { return before.occurrences().equals(after.occurrences()); }
		public boolean sameStatementBlockScopes() { return before.statementBlockScopes().equals(after.statementBlockScopes()); }
		public boolean sameRuntimeSignatureFacts() { return before.runtimeSignatureFacts().equals(after.runtimeSignatureFacts()); }
	}

	public record AnchorSnapshot(String analysisFingerprint, List<CompiledHopKey> occurrences,
		List<String> canonicalOrigins, List<DurableAnchorKey> anchors,
		List<PlacementOwnedAnchorFact> facts, Set<String> statementBlockScopes,
		Set<String> runtimeSignatureFacts, List<String> lifecycleEvidence) { }

	private AnchorProvenanceLifecycleCapture() { }

	public static LifecycleDurabilityReceipt captureStableLifecycle(PlacementAnalysis analysis,
		List<AnchorAccessForm> forms) {
		return new LifecycleDurabilityReceipt(
			captureBoundary("cleanup", ANCHORED_UPLOAD_FIXTURE, analysis, forms),
			captureBoundary("clone", RECOMPILE_FIXTURE, analysis(RECOMPILE_FIXTURE), forms),
			captureBoundary("unroll", LOOP_FIXTURE, analysis(LOOP_FIXTURE), forms),
			captureBoundary("additional-roots", ADDITIONAL_ROOT_FIXTURE,
				analysis(ADDITIONAL_ROOT_FIXTURE), forms),
			captureBoundary("recompile", RECOMPILE_FIXTURE, analysis(RECOMPILE_FIXTURE), forms));
	}

	private static BoundaryComparison captureBoundary(String stage, String fixtureId, PlacementAnalysis analysis,
		List<AnchorAccessForm> forms) {
		AnchorSnapshot before = snapshot(analysis, forms);
		List<String> invocationEvidence = invokeBoundary(stage, fixtureId, analysis);
		AnchorSnapshot after = snapshot(analysis, forms);
		List<String> evidence = new ArrayList<>(lifecycleEvidence(analysis));
		evidence.addAll(invocationEvidence);
		boolean allowedCpFoutInRecompile = !hasRecompileCpFoutExclusion(analysis);
		return new BoundaryComparison(stage, fixtureId, before, after,
			allowedCpFoutInRecompile, lifecycleEvidenceMatchesStage(stage, evidence),
			contains(evidence, "cleanup.clearFedInitVars=invoked")
				&& contains(evidence, "cleanup.resetFederatedPlannerRunState=invoked"),
			contains(evidence, "clone.publicHopClone=invoked") || contains(evidence, "unroll.constructLopsConsumer=invoked"),
			contains(evidence, "additional-roots.directDpRewriteProgram=invoked"),
			contains(evidence, "registry.clearRegisterSnapshotRemove=invoked")
				&& contains(evidence, "registry.snapshotUnmodifiable=true"),
			contains(evidence, "recompile.publicRecompiler=invoked")
				&& contains(evidence, "recompile.directDpRewriteProgram=invoked"), evidence);
	}

	public static AnchorSnapshot snapshot(PlacementAnalysis analysis, List<AnchorAccessForm> forms) {
		List<CompiledHopKey> occurrences = new ArrayList<>();
		List<String> canonicalOrigins = new ArrayList<>();
		List<DurableAnchorKey> anchors = new ArrayList<>();
		List<PlacementOwnedAnchorFact> facts = new ArrayList<>();
		Set<String> statementScopes = new LinkedHashSet<>();
		Set<String> runtimeSignatures = new LinkedHashSet<>();
		AnchorAccessForm form = forms.contains(AnchorAccessForm.FEDINIT_SIGNATURE)
			? AnchorAccessForm.FEDINIT_SIGNATURE : AnchorAccessForm.FEDINIT_LITERAL;

		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			CompiledHopKey key = occurrence.key();
			occurrences.add(key);
			canonicalOrigins.add(key.canonicalSourceOrigin());
			Node node = analysis.graph().node(key).orElseThrow(AssertionError::new);
			statementScopes.add(key.controlRegion().normalizedSignature());
			if(forms.contains(AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE))
				runtimeSignatures.add(key.recompileContext() + ":" + key.normalizedSignature());
			for(DurableAnchorKey anchor : node.anchors()) {
				anchors.add(anchor);
				facts.add(new PlacementOwnedAnchorFact(analysis, key, form, anchor,
					anchor.partitions(), anchor.fType(), false, false,
					Optional.of(key.normalizedSignature())));
			}
		}
		List<String> lifecycleEvidence = lifecycleEvidence(analysis);
		return new AnchorSnapshot(analysis.analysisFingerprint(), List.copyOf(occurrences),
			List.copyOf(canonicalOrigins), List.copyOf(anchors), List.copyOf(facts),
			Set.copyOf(statementScopes), Set.copyOf(runtimeSignatures), lifecycleEvidence);
	}

	private static List<String> lifecycleEvidence(PlacementAnalysis analysis) {
		List<String> evidence = new ArrayList<>();
		evidence.add("occurrences=" + analysis.occurrences().size());
		evidence.add("anchors=" + analysis.graph().nodes().stream().mapToInt(node -> node.anchors().size()).sum());
		evidence.add("loopNodes=" + analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.LOOP_PHI).count());
		evidence.add("cloneNodes=" + analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.CLONE).count());
		evidence.add("sameOriginConstraints=" + analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_ORIGIN).count());
		evidence.add("recompileCpFoutExclusions=" + analysis.graph().nodes().stream()
			.flatMap(node -> node.exclusions().stream())
			.filter(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).count());
		return List.copyOf(evidence);
	}

	private static List<String> invokeBoundary(String stage, String fixtureId, PlacementAnalysis analysis) {
		try {
			return switch(stage) {
				case "cleanup" -> invokeCleanupBoundary(fixtureId);
				case "clone" -> invokeCloneBoundary(analysis);
				case "unroll" -> invokeConstructLopsBoundary(stage, fixtureId);
				case "additional-roots" -> invokeConstructLopsBoundary(stage, fixtureId);
				case "recompile" -> invokeConstructLopsBoundary(stage, fixtureId);
				default -> List.of("boundary.failure=unknown-stage:" + stage);
			};
		}
		catch(Exception ex) {
			return List.of("boundary.failure=" + stage + ":" + ex.getClass().getName()
				+ ":" + String.valueOf(ex.getMessage()));
		}
	}

	private static List<String> invokeCleanupBoundary(String fixtureId) throws Exception {
		DMLProgram program = program(fixtureId);
		DMLTranslator.resetHopsDAGVisitStatus(program);
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		List<String> evidence = new ArrayList<>();
		evidence.add("cleanup.resetHopsDAGVisitStatus=invoked");
		evidence.add("cleanup.clearFedInitVars=invoked");
		evidence.add("cleanup.resetFederatedPlannerRunState=invoked");
		evidence.addAll(invokeRegistryLifecycle());
		return List.copyOf(evidence);
	}

	private static List<String> invokeCloneBoundary(PlacementAnalysis analysis) throws CloneNotSupportedException {
		Hop cloneSource = analysis.occurrences().stream()
			.filter(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow().kind() == NodeKind.CLONE)
			.map(HopOccurrenceProjection::hop).findFirst().orElseThrow(AssertionError::new);
		Hop cloned = (Hop) cloneSource.clone();
		return List.of("clone.publicHopClone=invoked",
			"clone.sameIdentity=" + Boolean.toString(cloned == cloneSource),
			"clone.class=" + cloned.getClass().getName());
	}

	private static List<String> invokeConstructLopsBoundary(String stage, String fixtureId) throws Exception {
		DMLProgram program = program(fixtureId);
		DMLTranslator translator = new DMLTranslator(program);
		DMLTranslator.resetHopsDAGVisitStatus(program);
		PlacementAnalysis[] finalBoundaryAnalysis = new PlacementAnalysis[1];
		String oldPlanner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			translator.constructLops(program, value -> finalBoundaryAnalysis[0] = value.analysis());
		}
		finally {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, oldPlanner);
		}
		List<String> evidence = new ArrayList<>();
		evidence.add(stage + ".constructLopsConsumer=invoked");
		evidence.add(stage + ".statementBlocks=" + program.getNumStatementBlocks());
		if(finalBoundaryAnalysis[0] != null) {
			evidence.add(stage + ".constructLopsDpRewriteProgram=invoked");
			new FederatedPlannerDpFedCostBased().rewriteProgram(program, new FunctionCallGraph(program),
				null, finalBoundaryAnalysis[0]);
			evidence.add(stage + ".directDpRewriteProgram=invoked");
		}
		if("recompile".equals(stage)) {
			var block = program.getStatementBlocks().stream().filter(sb -> sb.getHops() != null
				&& !sb.getHops().isEmpty()).findFirst().orElseThrow(AssertionError::new);
			Recompiler.recompile(block, block.getHops(), ExecutionContextFactory.createContext(),
				null, false, false, true, false, false, null, 0);
			evidence.add("recompile.publicRecompiler=invoked");
		}
		return List.copyOf(evidence);
	}

	private static List<String> invokeRegistryLifecycle() {
		long scope = 71019L;
		long hop = 3L;
		long anchor = 1L;
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedRefedRegistry.register(scope, hop, anchor, "anchor-key");
		FederatedFoutMaterializeRegistry.register(scope, hop, anchor, "ROW", "anchor", "anchor-key");
		FederatedLocalMaterializeRegistry.register(scope, hop, List.of(anchor), "ROW", "test-only");
		var refedSnapshot = FederatedRefedRegistry.snapshot(scope);
		var foutSnapshot = FederatedFoutMaterializeRegistry.snapshot(scope);
		var localSnapshot = FederatedLocalMaterializeRegistry.snapshot(scope);
		boolean registered = !refedSnapshot.isEmpty() && !foutSnapshot.isEmpty()
			&& !localSnapshot.isEmpty() && FederatedRefedRegistry.hasEntry(hop)
			&& FederatedFoutMaterializeRegistry.hasEntry(hop) && FederatedLocalMaterializeRegistry.hasEntry(hop);
		boolean snapshotUnmodifiable = rejectsMutation(refedSnapshot) && rejectsMutation(foutSnapshot)
			&& rejectsMutation(localSnapshot);
		FederatedRefedRegistry.remove(scope, hop);
		FederatedFoutMaterializeRegistry.remove(scope, hop);
		FederatedLocalMaterializeRegistry.remove(scope, hop);
		boolean removed = FederatedRefedRegistry.snapshot(scope).isEmpty()
			&& FederatedFoutMaterializeRegistry.snapshot(scope).isEmpty()
			&& FederatedLocalMaterializeRegistry.snapshot(scope).isEmpty();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		return List.of("registry.clearRegisterSnapshotRemove=invoked",
			"registry.registered=" + registered, "registry.removed=" + removed,
			"registry.snapshotUnmodifiable=" + snapshotUnmodifiable,
			"registry.empty=" + (FederatedRefedRegistry.isEmpty() && FederatedFoutMaterializeRegistry.isEmpty()
				&& FederatedLocalMaterializeRegistry.isEmpty()));
	}

	private static boolean rejectsMutation(java.util.Map<Long, ?> snapshot) {
		try { snapshot.clear(); return false; }
		catch(UnsupportedOperationException expected) { return true; }
	}

	private static PlacementAnalysis analysis(String fixtureId) {
		try { return new NeutralPlacementGraphBuilder().buildAnalysis(program(fixtureId)); }
		catch(Exception ex) { throw new AssertionError("G014_A2_FIXTURE_ANALYSIS_FAILED", ex); }
	}

	private static DMLProgram program(String fixtureId) throws Exception {
		return ProductionShadowFixtureFactory.compile(fixtureId);
	}

	private static String digest(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
		catch(java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
	}

	private static boolean hasRecompileCpFoutExclusion(PlacementAnalysis analysis) {
		return analysis.graph().nodes().stream().flatMap(node -> node.exclusions().stream())
			.anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT);
	}

	private static boolean lifecycleEvidenceMatchesStage(String stage, List<String> evidence) {
		return switch(stage) {
			case "cleanup" -> contains(evidence, "cleanup.resetHopsDAGVisitStatus=invoked")
				&& contains(evidence, "cleanup.clearFedInitVars=invoked")
				&& contains(evidence, "cleanup.resetFederatedPlannerRunState=invoked")
				&& contains(evidence, "registry.clearRegisterSnapshotRemove=invoked")
				&& contains(evidence, "registry.registered=true")
				&& contains(evidence, "registry.removed=true")
				&& contains(evidence, "registry.snapshotUnmodifiable=true") && positive(evidence, "anchors=");
			case "clone" -> contains(evidence, "clone.publicHopClone=invoked")
				&& contains(evidence, "clone.sameIdentity=false")
				&& positive(evidence, "cloneNodes=") && positive(evidence, "sameOriginConstraints=");
			case "unroll" -> contains(evidence, "unroll.constructLopsConsumer=invoked")
				&& positive(evidence, "loopNodes=");
			case "additional-roots" -> contains(evidence, "additional-roots.constructLopsConsumer=invoked")
				&& contains(evidence, "additional-roots.directDpRewriteProgram=invoked")
				&& positive(evidence, "additional-roots.statementBlocks=");
			case "recompile" -> contains(evidence, "recompile.constructLopsConsumer=invoked")
				&& contains(evidence, "recompile.directDpRewriteProgram=invoked")
				&& contains(evidence, "recompile.publicRecompiler=invoked")
				&& positive(evidence, "recompileCpFoutExclusions=");
			default -> false;
		};
	}

	private static boolean contains(List<String> evidence, String expected) {
		return evidence.contains(expected);
	}

	private static boolean positive(List<String> evidence, String prefix) {
		return evidence.stream().filter(value -> value.startsWith(prefix))
			.map(value -> value.substring(prefix.length())).mapToLong(Long::parseLong).anyMatch(value -> value > 0);
	}
}

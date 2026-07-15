/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlannerPlacementContext;
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.junit.Assert;

/** Direct executable behavioral probes against the Stage-A adapter API present in Task52. */
final class CampaignASharedResultBehavioralProbe {
	private static final Comparator<RelocationActionKey> STABLE_ORDER =
		Comparator.comparing(RelocationActionKey::normalizedSignature);

	record Fixture(PlacementAnalysis exact, PlacementAnalysis foreign,
		Map<CompiledHopKey, PlacementState> states, List<RelocationActionKey> relocations) { }

	static Fixture fixture() {
		var selected = CampaignBSelectorFixtureBridge.all().stream()
			.filter(c -> c.id().equals("S-06")).findFirst().orElseThrow();
		PlacementAnalysis exact = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(selected.production());
		PlacementAnalysis foreign = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(selected.production());
		Map<CompiledHopKey, PlacementState> states = new LinkedHashMap<>();
		exact.graph().nodes().forEach(n -> states.put(n.key(), n.legalAlternatives().get(0)));
		List<RelocationActionKey> relocations = exact.graph().relocationActions().stream()
			.map(a -> a.key()).sorted(STABLE_ORDER).toList();
		if(exact == foreign || !exact.analysisFingerprint().equals(foreign.analysisFingerprint())
			|| states.isEmpty() || relocations.size() < 2)
			throw new AssertionError("A_FIXTURE_NONEMPTY_IDENTITY_TRAP");
		return new Fixture(exact, foreign, Map.copyOf(states), List.copyOf(relocations));
	}

	static void assertExactAnalysisIdentity() {
		Fixture f = fixture();
		ConcreteResult foreign = result(f.foreign(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-plan");
		try {
			boundary(adapter(i -> foreign), f.exact());
			Assert.fail("A_IDENTITY_RECEIPT");
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_IDENTITY_REJECTION_REASON", rejected, "identity", "analysis");
		}
	}

	static void assertStateInputDefensiveCopy() {
		Fixture f = fixture();
		Map<CompiledHopKey, PlacementState> mutable = new LinkedHashMap<>(f.states());
		ConcreteResult draft = result(f.exact(), mutable,
			new ArrayList<>(f.relocations()), "stable-plan");
		try {
			NormalizedPlannerResult actual = boundary(adapter(i -> draft), f.exact());
			mutable.clear();
			if(!f.states().equals(actual.selectedStates()))
				Assert.fail("A_STATE_DEFENSIVE_COPY");
			assertWrapped(draft, actual);
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_STATE_MUTABLE_REJECTION_REASON", rejected, "mutable", "immutable", "copy");
		}
	}

	static void assertRelocationInputDefensiveCopy() {
		Fixture f = fixture();
		List<RelocationActionKey> mutable = new ArrayList<>(f.relocations());
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()),
			mutable, "stable-plan");
		try {
			NormalizedPlannerResult actual = boundary(adapter(i -> draft), f.exact());
			mutable.clear();
			if(!f.relocations().equals(actual.selectedRelocations()))
				Assert.fail("A_RELOCATION_DEFENSIVE_COPY");
			assertWrapped(draft, actual);
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_RELOCATION_MUTABLE_REJECTION_REASON", rejected, "mutable", "immutable", "copy");
		}
	}

	static void assertStateViewImmutable() {
		Fixture f = fixture();
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-plan");
		try {
			NormalizedPlannerResult actual = boundary(adapter(i -> draft), f.exact());
			try {
				actual.selectedStates().clear();
				Assert.fail("A_STATE_VIEW_MUTABLE");
			}
			catch(UnsupportedOperationException expected) { }
			assertWrapped(draft, actual);
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_STATE_VIEW_REJECTION_REASON", rejected, "mutable", "immutable", "copy");
		}
	}

	static void assertRelocationViewImmutable() {
		Fixture f = fixture();
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-plan");
		try {
			NormalizedPlannerResult actual = boundary(adapter(i -> draft), f.exact());
			try {
				actual.selectedRelocations().clear();
				Assert.fail("A_RELOCATION_VIEW_MUTABLE");
			}
			catch(UnsupportedOperationException expected) { }
			assertWrapped(draft, actual);
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_RELOCATION_VIEW_REJECTION_REASON", rejected, "mutable", "immutable", "copy");
		}
	}

	static void assertDuplicateRelocationsRejected() {
		Fixture f = fixture();
		List<RelocationActionKey> duplicate = new ArrayList<>(f.relocations());
		duplicate.add(f.relocations().get(0));
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()), duplicate, "stable-plan");
		try {
			boundary(adapter(i -> draft), f.exact());
			Assert.fail("A_DUPLICATE_RELOCATION_ACCEPTED");
		}
		catch(IllegalArgumentException rejected) {
			assertReason("A_DUPLICATE_REJECTION_REASON", rejected, "duplicate", "unique");
		}
	}

	static void assertCanonicalRelocationOrder() {
		Fixture f = fixture();
		List<RelocationActionKey> reversed = new ArrayList<>(f.relocations());
		java.util.Collections.reverse(reversed);
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()), reversed, "stable-plan");
		try {
			NormalizedPlannerResult actual = boundary(adapter(i -> draft), f.exact());
			if(!f.relocations().equals(actual.selectedRelocations()))
				Assert.fail("A_RELOCATION_STABLE_KEY_ORDER");
			assertWrapped(draft, actual);
		}
		catch(IllegalArgumentException | IllegalStateException rejected) {
			assertReason("A_ORDER_REJECTION_REASON", rejected, "order", "canonical", "stable");
		}
	}

	static void assertRepeatStableContents() {
		Fixture f = fixture();
		List<RelocationActionKey> reversed = new ArrayList<>(f.relocations());
		java.util.Collections.reverse(reversed);
		ConcreteResult firstDraft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-a");
		ConcreteResult secondDraft = result(f.exact(), new LinkedHashMap<>(f.states()), reversed, "stable-b");
		PlacementPlannerAdapter<ConcreteResult> adapter = adapter(i -> i == 0 ? firstDraft : secondDraft);
		NormalizedPlannerResult first = boundary(adapter, f.exact());
		NormalizedPlannerResult second = boundary(adapter, f.exact());
		if(!first.selectedRelocations().equals(second.selectedRelocations()))
			Assert.fail("A_REPEAT_NORMALIZED_CONTENTS");
		assertWrapped(firstDraft, first);
		assertWrapped(secondDraft, second);
	}

	static void assertRepeatStableFingerprint() {
		Fixture f = fixture();
		ConcreteResult firstDraft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-a");
		ConcreteResult secondDraft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-b");
		PlacementPlannerAdapter<ConcreteResult> adapter = adapter(i -> i == 0 ? firstDraft : secondDraft);
		NormalizedPlannerResult first = boundary(adapter, f.exact());
		NormalizedPlannerResult second = boundary(adapter, f.exact());
		if(!first.normalizedPlanFingerprint().equals(second.normalizedPlanFingerprint()))
			Assert.fail("A_REPEAT_NORMALIZED_FINGERPRINT");
		assertWrapped(firstDraft, first);
		assertWrapped(secondDraft, second);
	}

	static boolean interfaceTypedBoundaryIsCompileFeasible() {
		Fixture f = fixture();
		ConcreteResult draft = result(f.exact(), new LinkedHashMap<>(f.states()),
			new ArrayList<>(f.relocations()), "stable-plan");
		NormalizedPlannerResult before = draft;
		NormalizedPlannerResult after = boundary(adapter(i -> draft), f.exact());
		return draft.analysis() == f.exact() && before == draft && after != null;
	}

	private static ConcreteResult result(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> states, List<RelocationActionKey> relocations, String fingerprint) {
		return new ConcreteResult(analysis, states, relocations, fingerprint);
	}

	private static PlacementPlannerAdapter<ConcreteResult> adapter(IntFunction<ConcreteResult> results) {
		return new PlacementPlannerAdapter<>() {
			private int invocation;
			@Override public ConcreteResult select(PlannerPlacementContext context) {
				return results.apply(invocation++);
			}
		};
	}

	private static NormalizedPlannerResult boundary(
		PlacementPlannerAdapter<? extends NormalizedPlannerResult> adapter, PlacementAnalysis analysis) {
		return adapter.select(analysis);
	}

	private static void assertWrapped(ConcreteResult draft, NormalizedPlannerResult actual) {
		if(actual == draft)
			Assert.fail("A_SHARED_NORMALIZED_WRAPPER");
	}

	private static void assertReason(String code, RuntimeException failure, String... terms) {
		String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
		for(String term : terms)
			if(message.contains(term))
				return;
		throw new AssertionError(code + " actual=" + message);
	}

	static final class ConcreteResult implements NormalizedPlannerResult {
		private final PlacementAnalysis analysis;
		private final Map<CompiledHopKey, PlacementState> states;
		private final List<RelocationActionKey> relocations;
		private final String planFingerprint;

		ConcreteResult(PlacementAnalysis analysis, Map<CompiledHopKey, PlacementState> states,
			List<RelocationActionKey> relocations, String planFingerprint) {
			this.analysis = analysis;
			this.states = states;
			this.relocations = relocations;
			this.planFingerprint = planFingerprint;
		}

		public PlacementAnalysis analysis() { return analysis; }
		@Override public String plannerId() { return "NEUTRAL-A"; }
		@Override public String analysisFingerprint() { return analysis.analysisFingerprint(); }
		@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return states; }
		@Override public List<RelocationActionKey> selectedRelocations() { return relocations; }
		@Override public String objectiveCertificate() { return "objective-neutral"; }
		@Override public String normalizedPlanFingerprint() { return planFingerprint; }
	}

	private CampaignASharedResultBehavioralProbe() { }
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Independent finite oracle for multi-row, multi-anchor relocation selection. */
public class RelocationActionPlanSpaceCompletenessTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState FED_COL =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
	private static final CompiledHopKey SOURCE_0 = key("source-0");
	private static final CompiledHopKey SOURCE_1 = key("source-1");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final ValueVersionKey VERSION_0 = version("version-0");
	private static final ValueVersionKey VERSION_1 = version("version-1");
	private static final ValueVersionKey CONSUMER_VERSION = version("consumer-version");

	@Test
	public void exhaustiveMultiRowMultiAnchorChoicesMatchLiteralUniverse() {
		Fixture fixture = fixture();
		assertExactSet("all production actions", expectedActionUniverse(),
			fixture.actionsByName().keySet());

		Set<String> expectedPlans = expectedPlanUniverse();
		Set<String> actualPlans = new LinkedHashSet<>();
		for(PlacementState consumerState : List.of(FED_ROW, FED_COL)) {
			Map<CompiledHopKey,PlacementState> assignment = assignment(consumerState);
			Set<String> actualForRow = acceptedChoiceSets(fixture, assignment);
			assertExactSet("accepted " + row(consumerState) + " choices",
				expectedPlans.stream().filter(plan -> plan.startsWith(row(consumerState) + '|'))
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
				actualForRow);
			actualPlans.addAll(actualForRow);
		}
		assertExactSet("complete action-plan universe", expectedPlans, actualPlans);
	}

	@Test
	public void canonicalPriorityFallbackAndOrderPreserveTheSameLegalChoices() {
		Fixture fixture = fixture();
		for(PlacementState consumerState : List.of(FED_ROW, FED_COL)) {
			Map<CompiledHopKey,PlacementState> assignment = assignment(consumerState);
			List<RelocationAction> reversed = new ArrayList<>(fixture.graph().relocationActions());
			Collections.reverse(reversed);

			List<RelocationChoiceReceipt> canonical = RelocationSelections.selectCanonical(
				fixture.graph(), fixture.graph().relocationActions(), assignment, (demand, action) -> true);
			List<RelocationChoiceReceipt> reordered = RelocationSelections.selectCanonical(
				fixture.graph(), reversed, assignment, (demand, action) -> true);
			Assert.assertEquals("canonical priority must be input-order invariant",
				choiceIdentity(fixture, canonical), choiceIdentity(fixture, reordered));
			Assert.assertTrue(expectedPlanUniverse().contains(
				row(consumerState) + '|' + choiceIdentity(fixture, canonical)));

			List<RelocationChoiceReceipt> forcedB = RelocationSelections.selectCanonical(
				fixture.graph(), reversed, assignment,
				(demand, action) -> anchorName(action.durableAnchor()).equals("B"));
			String expectedB = row(consumerState) + '|' + row(consumerState) + "|S0|B,"
				+ row(consumerState) + "|S1|B";
			Assert.assertEquals("fallback must retain the complete alternate anchor", expectedB,
				row(consumerState) + '|' + choiceIdentity(fixture, forcedB));

			var minimum = RelocationSelections.selectMinimumCost(fixture.graph(), reversed, assignment,
				action -> anchorName(action.durableAnchor()).equals("B") ? 1.0 : 10.0);
			Assert.assertEquals(choiceIdentity(fixture, forcedB),
				choiceIdentity(fixture, minimum.choices()));
			Assert.assertEquals(2.0, minimum.cost(), 0.0);
			Assert.assertEquals(2, minimum.emittedActions().size());

			List<RelocationChoiceReceipt> completed =
				RelocationSelections.completeFromSelectedRelocations(
					fixture.graph(), assignment, minimum.emittedActions());
			Assert.assertEquals("emitted action round-trip must preserve exact choices",
				choiceIdentity(fixture, forcedB), choiceIdentity(fixture, completed));
			Assert.assertEquals(2, RelocationSelections.resolveAndValidate(
				fixture.graph(), assignment, completed).size());
		}
	}

	@Test
	public void canonicalIndexAndAlternativeDeletionDoNotConflateAnchorsOrRows() {
		Fixture fixture = fixture();
		List<RelocationAction> reversed = new ArrayList<>(fixture.graph().relocationActions());
		Collections.reverse(reversed);
		var forwardIndex = RelocationSelections.canonicalOrderIndex(fixture.graph().relocationActions());
		var reverseIndex = RelocationSelections.canonicalOrderIndex(reversed);
		List<RelocationActionKey> keys = fixture.graph().relocationActions().stream()
			.map(RelocationAction::key).toList();
		Assert.assertEquals(forwardIndex.canonicalActions(keys), reverseIndex.canonicalActions(keys));
		Assert.assertEquals(8, forwardIndex.physicalEmissionCount());
		Assert.assertEquals(8, reverseIndex.physicalEmissionCount());

		String removedName = "ROW|S1|B";
		List<RelocationAction> mutatedActions = fixture.graph().relocationActions().stream()
			.filter(action -> !actionName(fixture, action.key()).equals(removedName)).toList();
		NeutralPlacementGraph mutated = graph(mutatedActions);
		Set<String> actual = acceptedChoiceSets(new Fixture(mutated, fixture.actionsByName()),
			assignment(FED_ROW));
		assertExactSet("one deleted alternative", Set.of("ROW|ROW|S0|A,ROW|S1|A"), actual);
		Assert.assertFalse("deleting one anchor-row action must remove the B plan",
			actual.contains("ROW|ROW|S0|B,ROW|S1|B"));
	}

	private static Set<String> acceptedChoiceSets(Fixture fixture,
		Map<CompiledHopKey,PlacementState> assignment) {
		Map<RelocationDemandKey,List<RelocationAction>> options = new LinkedHashMap<>();
		for(RelocationAction action : fixture.graph().relocationActions())
			for(ObligationKey obligation : action.obligations())
				if(obligation.requiredPlacement().equals(assignment.get(obligation.consumer())))
					options.computeIfAbsent(RelocationDemandKey.from(obligation), ignored -> new ArrayList<>())
						.add(action);
		List<RelocationDemandKey> demands = options.keySet().stream().sorted().toList();
		Set<String> accepted = new LinkedHashSet<>();
		enumerateChoices(fixture, assignment, options, demands, 0, new ArrayList<>(), accepted);
		return Set.copyOf(accepted);
	}

	private static void enumerateChoices(Fixture fixture,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<RelocationDemandKey,List<RelocationAction>> options,
		List<RelocationDemandKey> demands, int position,
		List<RelocationChoiceReceipt> selected, Set<String> accepted) {
		if(position == demands.size()) {
			try {
				RelocationSelections.resolveAndValidate(fixture.graph(), assignment, selected);
				accepted.add(row(assignment.get(CONSUMER)) + '|' + choiceIdentity(fixture, selected));
			}
			catch(IllegalArgumentException ignored) {
				// Independently enumerated mixed-anchor combinations are expected rejections.
			}
			return;
		}
		RelocationDemandKey demand = demands.get(position);
		for(RelocationAction action : options.get(demand)) {
			selected.add(new RelocationChoiceReceipt(demand, action.key()));
			enumerateChoices(fixture, assignment, options, demands, position + 1, selected, accepted);
			selected.remove(selected.size() - 1);
		}
	}

	private static Set<String> expectedActionUniverse() {
		Set<String> expected = new LinkedHashSet<>();
		for(String row : List.of("ROW", "COL"))
			for(String source : List.of("S0", "S1"))
				for(String anchor : List.of("A", "B"))
					expected.add(row + '|' + source + '|' + anchor);
		return Set.copyOf(expected);
	}

	private static Set<String> expectedPlanUniverse() {
		Set<String> expected = new LinkedHashSet<>();
		for(String row : List.of("ROW", "COL"))
			for(String anchor : List.of("A", "B"))
				expected.add(row + '|' + row + "|S0|" + anchor + ','
					+ row + "|S1|" + anchor);
		return Set.copyOf(expected);
	}

	private static String choiceIdentity(Fixture fixture,
		List<RelocationChoiceReceipt> choices) {
		return choices.stream().map(choice -> actionName(fixture, choice.action())).sorted()
			.reduce((left, right) -> left + ',' + right).orElse("");
	}

	private static String actionName(Fixture fixture, RelocationActionKey key) {
		return fixture.actionsByName().entrySet().stream()
			.filter(entry -> entry.getValue().key().equals(key)).map(Map.Entry::getKey)
			.findFirst().orElseThrow(() -> new AssertionError("unknown action " + key.normalizedSignature()));
	}

	private static Fixture fixture() {
		Map<String,RelocationAction> actions = new LinkedHashMap<>();
		for(PlacementState state : List.of(FED_ROW, FED_COL)) {
			String row = row(state);
			for(int source = 0; source < 2; source++)
				for(String anchor : List.of("A", "B")) {
					String name = row + "|S" + source + '|' + anchor;
					actions.put(name, action(source, state, durableAnchor(state.fType(), anchor)));
				}
		}
		return new Fixture(graph(List.copyOf(actions.values())), Map.copyOf(actions));
	}

	private static NeutralPlacementGraph graph(List<RelocationAction> actions) {
		return new NeutralPlacementGraph(List.of(
			new Node(SOURCE_0, NodeKind.OPERATION, VERSION_0, true,
				List.of(LOCAL), List.of(), List.of()),
			new Node(SOURCE_1, NodeKind.OPERATION, VERSION_1, true,
				List.of(LOCAL), List.of(), List.of()),
			new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
				List.of(FED_ROW, FED_COL), List.of(), List.of())), List.of(), actions);
	}

	private static RelocationAction action(int source, PlacementState target,
		DurableAnchorKey anchor) {
		ValueVersionKey version = source == 0 ? VERSION_0 : VERSION_1;
		RelocationActionKey key = new RelocationActionKey(version, target, target.fType(), anchor,
			"scope", List.of(CONSUMER));
		ObligationKey obligation = new ObligationKey(CONSUMER, source, version,
			target, key, "scope");
		return new RelocationAction(key, List.of(obligation));
	}

	private static DurableAnchorKey durableAnchor(FType type, String name) {
		boolean row = type == FType.ROW;
		long port = "A".equals(name) ? 1234 : 2234;
		return new DurableAnchorKey(type.name() + '-' + name, type, List.of(
			new AnchorPartition("localhost:" + port, List.of(0L, 0L),
				List.of(row ? 4L : 2L, row ? 2L : 4L)),
			new AnchorPartition("localhost:" + (port + 1),
				List.of(row ? 4L : 0L, row ? 0L : 4L), List.of(8L, row ? 2L : 8L))));
	}

	private static String anchorName(DurableAnchorKey anchor) {
		return anchor.placementId().endsWith("-A") ? "A" : "B";
	}

	private static String row(PlacementState state) {
		return state.fType().name();
	}

	private static Map<CompiledHopKey,PlacementState> assignment(PlacementState consumerState) {
		return Map.of(SOURCE_0, LOCAL, SOURCE_1, LOCAL, CONSUMER, consumerState);
	}

	private static void assertExactSet(String label, Set<String> expected, Set<String> actual) {
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(actual);
		Set<String> extra = new TreeSet<>(actual);
		extra.removeAll(expected);
		Assert.assertTrue(label + " missing=" + missing + " extra=" + extra,
			missing.isEmpty() && extra.isEmpty());
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("program", "ns", "call", "compiled", region(), id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("program", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("program", "ns", List.of("main/0"), "call", "compiled");
	}

	private record Fixture(NeutralPlacementGraph graph,
		Map<String,RelocationAction> actionsByName) { }
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Builder;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Execution;

/** Independently spelled, semantically isomorphic selector inputs for the production and oracle universes. */
final class IsomorphicSelectorContractFixtures {
	record Case(String id, long seed, ExplicitSelectorGraph oracle, NeutralPlacementGraph production) { }

	static List<Case> all() {
		List<Case> cases = new ArrayList<>();
		cases.add(new Case("S-01", 1, explicitS01(), neutralS01()));
		cases.add(new Case("S-02", 2, explicitS02(), neutralS02()));
		cases.add(new Case("S-03", 3, explicitS03(), neutralS03()));
		cases.add(new Case("S-04", 4, explicitS04(), neutralS04()));
		cases.add(new Case("S-05", 5, explicitS05(), neutralS05()));
		cases.add(new Case("S-06", 6, explicitS06(), neutralS06()));
		cases.add(new Case("S-07", 7, explicitS07(), neutralS07()));
		for(int size = 2; size <= 6; size++)
			for(long seed : new long[] {11, 29, 47})
				cases.add(new Case("S-08-n" + size, seed, explicitGenerated(size, seed), neutralGenerated(size, seed)));
		return List.copyOf(cases);
	}

	private static ExplicitSelectorGraph explicitS01() {
		return new Builder("S-01", 1).addNode(explicitOrdinary("a")).addNode(explicitOrdinary("b")).build();
	}

	private static NeutralPlacementGraph neutralS01() {
		return neutral("S-01", List.of(spec("a", CP, FL_ROW, FF_ROW), spec("b", CP, FL_ROW, FF_ROW)),
			List.of(), List.of());
	}

	private static ExplicitSelectorGraph explicitS02() {
		Builder b = new Builder("S-02", 2)
			.addNode(explicit("child", CP, FF_ROW)).addNode(explicit("parent", CP, FF_FULL))
			.addEdge("child", "parent");
		for(PlacementState left : List.of(CP, FF_ROW))
			b.addConstraint(ExplicitSelectorGraph.forbidPair("child", sig(left), "parent", sig(FF_FULL)));
		return b.build();
	}

	private static NeutralPlacementGraph neutralS02() {
		return neutral("S-02", List.of(spec("child", CP, FF_ROW), spec("parent", CP, FF_FULL)),
			List.of(edge("child", "parent"), conjunctive("child", "parent")), List.of());
	}

	private static ExplicitSelectorGraph explicitS03() {
		return new Builder("S-03", 3).addNode(explicitOrdinary("shared"))
			.addNode(explicitOrdinary("left")).addNode(explicitOrdinary("right"))
			.addEdge("shared", "left").addEdge("shared", "right").build();
	}

	private static NeutralPlacementGraph neutralS03() {
		return neutral("S-03", List.of(spec("shared", CP, FL_ROW, FF_ROW), spec("left", CP, FL_ROW, FF_ROW),
			spec("right", CP, FL_ROW, FF_ROW)), List.of(edge("shared", "left"), edge("shared", "right")),
			List.of());
	}

	private static ExplicitSelectorGraph explicitS04() {
		Builder b = new Builder("S-04", 4)
			.addNode(explicit("value", CP, choice(CF_ROW, "upload:value")))
			.addNode(explicit("left", CP, FF_ROW)).addNode(explicit("right", CP, FF_ROW))
			.addEdge("value", "left").addEdge("value", "right");
		for(String consumer : List.of("left", "right"))
			for(PlacementState left : List.of(CP, CF_ROW))
				if(!left.equals(CF_ROW))
					b.addConstraint(ExplicitSelectorGraph.forbidPair("value", sig(left), consumer, sig(FF_ROW)));
		return b.build();
	}

	private static NeutralPlacementGraph neutralS04() {
		return neutral("S-04", List.of(spec("value", CP, CF_ROW), spec("left", CP, FF_ROW),
			spec("right", CP, FF_ROW)), List.of(edge("value", "left"), edge("value", "right"),
			conjunctive("value", "left"), conjunctive("value", "right")),
			List.of(relocation("upload:value", "value", CF_ROW, List.of("value"))));
	}

	private static ExplicitSelectorGraph explicitS05() {
		Builder b = new Builder("S-05", 5).addNode(explicit("fedGain", CP, FL_ROW))
			.addNode(explicit("foutOne", CP, CF_ROW)).addNode(explicit("foutTwo", CP, CF_ROW));
		for(String consumer : List.of("foutOne", "foutTwo"))
			for(PlacementState left : List.of(CP, FL_ROW))
				b.addConstraint(ExplicitSelectorGraph.forbidPair("fedGain", sig(left), consumer, sig(CF_ROW)));
		return b.build();
	}

	private static NeutralPlacementGraph neutralS05() {
		return neutral("S-05", List.of(spec("fedGain", CP, FL_ROW), spec("foutOne", CP, CF_ROW),
			spec("foutTwo", CP, CF_ROW)), List.of(conjunctive("fedGain", "foutOne"),
			conjunctive("fedGain", "foutTwo")), List.of());
	}

	private static ExplicitSelectorGraph explicitS06() {
		Builder b = new Builder("S-06", 6)
			.addNode(explicit("a", choice(FF_ROW, "upload:shared"), choice(FF_COL, "upload:a")))
			.addNode(explicit("b", choice(FF_ROW, "upload:shared"), choice(FF_COL, "upload:b")))
			.addEdge("a", "b");
		b.addConstraint(ExplicitSelectorGraph.forbidPair("a", sig(FF_ROW), "b", sig(FF_COL)));
		b.addConstraint(ExplicitSelectorGraph.forbidPair("a", sig(FF_COL), "b", sig(FF_ROW)));
		return b.build();
	}

	private static NeutralPlacementGraph neutralS06() {
		return neutral("S-06", List.of(spec("a", FF_ROW, FF_COL), spec("b", FF_ROW, FF_COL)),
			List.of(edge("a", "b"), sameFType("a", "b")), List.of(
				relocation("upload:shared", "a", FF_ROW, List.of("a", "b")),
				relocation("upload:a", "a", FF_COL, List.of("a")),
				relocation("upload:b", "b", FF_COL, List.of("b"))));
	}

	private static ExplicitSelectorGraph explicitS07() {
		return new Builder("S-07", 7).addNode(explicit("node", FL_ROW, FL_COL)).build();
	}

	private static NeutralPlacementGraph neutralS07() {
		return neutral("S-07", List.of(spec("node", FL_ROW, FL_COL)), List.of(), List.of());
	}

	private static ExplicitSelectorGraph explicitGenerated(int size, long seed) {
		Random random = new Random(seed * 31 + size);
		Builder builder = new Builder("S-08-n" + size, seed);
		for(int i = 0; i < size; i++) {
			String relocation = "upload:r" + random.nextInt(Math.max(1, size / 2));
			builder.addNode(explicit("n" + i, CP, FL_ROW, choice(FF_ROW, relocation)));
			if(i > 0) {
				int parent = random.nextInt(i);
				builder.addEdge("n" + parent, "n" + i);
				if(random.nextBoolean()) {
					builder.addConstraint(ExplicitSelectorGraph.forbidPair("n" + parent, sig(CP), "n" + i,
						sig(FF_ROW)));
					builder.addConstraint(ExplicitSelectorGraph.forbidPair("n" + parent, sig(FL_ROW), "n" + i,
						sig(FF_ROW)));
				}
			}
		}
		return builder.build();
	}

	private static NeutralPlacementGraph neutralGenerated(int size, long seed) {
		Random random = new Random(seed * 31 + size);
		List<NodeSpec> nodes = new ArrayList<>();
		List<ConstraintSpec> constraints = new ArrayList<>();
		Map<String,List<String>> consumersByRelocation = new LinkedHashMap<>();
		for(int i = 0; i < size; i++) {
			String node = "n" + i;
			String relocation = "upload:r" + random.nextInt(Math.max(1, size / 2));
			nodes.add(spec(node, CP, FL_ROW, FF_ROW));
			consumersByRelocation.computeIfAbsent(relocation, ignored -> new ArrayList<>()).add(node);
			if(i > 0) {
				String parent = "n" + random.nextInt(i);
				constraints.add(edge(parent, node));
				if(random.nextBoolean())
					constraints.add(conjunctive(parent, node));
			}
		}
		List<RelocationSpec> relocations = new ArrayList<>();
		for(Map.Entry<String,List<String>> entry : consumersByRelocation.entrySet())
			relocations.add(relocation(entry.getKey(), entry.getValue().get(0), FF_ROW, entry.getValue()));
		return neutral("S-08-n" + size, nodes, constraints, relocations);
	}

	private static NeutralPlacementGraph neutral(String id, List<NodeSpec> specs,
		List<ConstraintSpec> constraintSpecs, List<RelocationSpec> relocationSpecs) {
		Map<String,Node> nodes = new LinkedHashMap<>();
		for(NodeSpec spec : specs)
			nodes.put(spec.id(), node(id, spec));
		List<Constraint> constraints = new ArrayList<>();
		for(ConstraintSpec spec : constraintSpecs)
			constraints.add(new Constraint(spec.kind(), nodes.get(spec.left()).key(), nodes.get(spec.right()).key(),
				-1, spec.kind().name().toLowerCase()));
		List<RelocationAction> relocations = new ArrayList<>();
		for(RelocationSpec spec : relocationSpecs) {
			Node source = nodes.get(spec.source());
			List<CompiledHopKey> consumers = spec.consumers().stream().map(nodes::get).map(Node::key).toList();
			DurableAnchorKey anchor = new DurableAnchorKey(spec.id(), FType.ROW,
				List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
			RelocationActionKey key = new RelocationActionKey(source.valueVersion(), spec.target(), anchor, id, consumers);
			List<ObligationKey> obligations = new ArrayList<>();
			for(int i = 0; i < consumers.size(); i++)
				obligations.add(new ObligationKey(consumers.get(i), i, source.valueVersion(), spec.target(), key,
					"compiled"));
			relocations.add(new RelocationAction(key, obligations));
		}
		return new NeutralPlacementGraph(nodes.values(), constraints, relocations);
	}

	private static Node node(String id, NodeSpec spec) {
		ControlRegionKey region = new ControlRegionKey(id, "main", List.of("root"), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey(id, "main", "main", "compiled", region, spec.id(), spec.id());
		ValueVersionKey value = new ValueVersionKey(id, spec.id(), region, 0, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, spec.states(), List.of(), List.of());
	}

	private static org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node
		explicitOrdinary(String id) {
		return explicit(id, CP, FL_ROW, FF_ROW);
	}

	private static org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node
		explicit(String id, Object... choices) {
		List<Choice> result = new ArrayList<>();
		for(Object value : choices)
			result.add(value instanceof Choice ? (Choice) value : choice((PlacementState) value));
		return org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node.of(id,
			result.toArray(new Choice[0]));
	}

	private static Choice choice(PlacementState state, String... relocations) {
		return Choice.of(sig(state), state.execType() == ExecType.FED ? Execution.FED : Execution.CP,
			state.output() == FederatedOutput.FOUT
				? org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Output.FOUT
				: org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Output.LOUT,
			relocations);
	}

	private static String sig(PlacementState state) { return state.normalizedSignature(); }
	private static NodeSpec spec(String id, PlacementState... states) { return new NodeSpec(id, List.of(states)); }
	private static ConstraintSpec edge(String left, String right) { return new ConstraintSpec(ConstraintKind.DOMINATES, left, right); }
	private static ConstraintSpec conjunctive(String left, String right) { return new ConstraintSpec(ConstraintKind.CONJUNCTIVE, left, right); }
	private static ConstraintSpec sameFType(String left, String right) { return new ConstraintSpec(ConstraintKind.SAME_FTYPE, left, right); }
	private static RelocationSpec relocation(String id, String source, PlacementState target, List<String> consumers) {
		return new RelocationSpec(id, source, target, List.copyOf(consumers));
	}

	private record NodeSpec(String id, List<PlacementState> states) { }
	private record ConstraintSpec(ConstraintKind kind, String left, String right) { }
	private record RelocationSpec(String id, String source, PlacementState target, List<String> consumers) { }

	static final PlacementState CP = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	static final PlacementState CF_ROW = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
	static final PlacementState FL_ROW = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	static final PlacementState FL_COL = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.COL, false);
	static final PlacementState FF_ROW = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	static final PlacementState FF_COL = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
	static final PlacementState FF_FULL = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);

	private IsomorphicSelectorContractFixtures() { }
}

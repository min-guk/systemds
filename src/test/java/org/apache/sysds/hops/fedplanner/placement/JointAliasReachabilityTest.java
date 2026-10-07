/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.InputSource;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Relation;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Row;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Exact alias reachability, including reconvergent support graphs and cyclic queries. */
public class JointAliasReachabilityTest {

	@Test
	public void diamondExpandsEachRealizationOnce() throws Exception {
		Fixture fixture = new Fixture();
		var root = fixture.node("root");
		List<CandidateRealizationReference> previous = List.of(root);
		for(int level = 0; level < 10; level++) {
			List<CandidateRealizationReference> next = List.of(
				fixture.node("left-" + level), fixture.node("right-" + level));
			for(var reference : previous)
				fixture.edges(reference, next);
			previous = next;
		}
		var sink = fixture.node("sink");
		for(var reference : previous)
			fixture.edges(reference, List.of(sink));
		var absent = fixture.node("absent");

		Assert.assertFalse(uncached(fixture, root, owner(absent)));
		int expansions = fixture.expansions.values().stream().mapToInt(Integer::intValue).sum();
		System.out.println("JOINT_ALIAS_WORK|uniqueReachable=22|expansions=" + expansions);
		Assert.assertEquals("reconverging paths must share the completed node traversal", 22, expansions);
		Assert.assertTrue(fixture.expansions.values().stream().allMatch(count -> count == 1));
	}

	@Test
	public void cyclicExitRemainsReachableInEitherQueryOrder() throws Exception {
		Fixture fixture = new Fixture();
		var a = fixture.node("a");
		var b = fixture.node("b");
		var exit = fixture.node("exit");
		fixture.edges(a, List.of(b), List.of(exit));
		fixture.edges(b, List.of(a));
		for(var order : List.of(List.of(a, b), List.of(b, a))) {
			Grounding grounding = fixture.grounding();
			for(var reference : order)
				Assert.assertTrue(cached(grounding, reference, owner(exit)));
			int calls = fixture.expansionCount();
			for(var reference : order)
				Assert.assertTrue(cached(grounding, reference, owner(exit)));
			Assert.assertEquals("completed queries must be reused", calls, fixture.expansionCount());
		}
	}

	@Test
	public void closedCycleCachesOnlyTheCompletedNegativeQuery() throws Exception {
		Fixture fixture = new Fixture();
		var a = fixture.node("a");
		var b = fixture.node("b");
		var absent = fixture.node("absent");
		fixture.edges(a, List.of(b));
		fixture.edges(b, List.of(a));
		Grounding grounding = fixture.grounding();
		Assert.assertFalse(cached(grounding, a, owner(absent)));
		Assert.assertEquals(2, fixture.expansionCount());
		Assert.assertFalse(cached(grounding, a, owner(absent)));
		Assert.assertEquals(2, fixture.expansionCount());
		Assert.assertTrue(cached(grounding, a, owner(b)));
	}

	@Test
	public void sourcePriorityAndAliasFallbackReuseRemainIntact() throws Exception {
		Fixture fixture = new Fixture();
		var a = fixture.node("a");
		var b = fixture.node("b");
		var origin = fixture.node("origin");
		var absent = fixture.node("absent");
		fixture.edges(a, List.of(origin));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(), List.of(
			CandidateRealizationInputBinding.logicalTransient(0, a),
			CandidateRealizationInputBinding.logicalTransient(1, b)));
		Grounding grounding = fixture.grounding();
		Assert.assertEquals(List.of(a), sources(grounding, clause, a, owner(a), owner(b)));
		Assert.assertEquals(List.of(b), sources(grounding, clause, a, owner(absent), owner(b)));
		var singleton = new CandidateRealizationSupportClause(List.of(),
			List.of(CandidateRealizationInputBinding.logicalTransient(0, b)));
		Assert.assertEquals(List.of(b), sources(grounding, singleton, a, owner(absent), owner(origin)));
		Assert.assertEquals("explicit supplier, origin and singleton do not need alias searches",
			0, fixture.expansionCount());
		Assert.assertEquals(List.of(a), sources(grounding, clause, a, owner(absent), owner(origin)));
		int calls = fixture.expansionCount();
		Assert.assertEquals(2, calls);
		Assert.assertEquals(List.of(a), sources(grounding, clause, a, owner(absent), owner(origin)));
		Assert.assertEquals("the production fallback reuses positive and negative queries",
			calls, fixture.expansionCount());
	}

	@Test
	public void longAliasChainDoesNotUseTheJavaCallStack() throws Exception {
		Fixture fixture = new Fixture();
		var root = fixture.node("root");
		var previous = root;
		for(int i = 0; i < 5000; i++) {
			var next = fixture.node("chain-" + i);
			fixture.edges(previous, List.of(next));
			previous = next;
		}
		Assert.assertTrue(uncached(fixture, root, owner(previous)));
		Assert.assertEquals(5000, fixture.expansionCount());
	}

	@Test
	public void followsOnlyAliasesAndRecognizesTheOriginBeforeHopClassification() throws Exception {
		Fixture fixture = new Fixture();
		var origin = fixture.node("origin");
		var reference = fixture.node("reference");
		fixture.edges(reference, List.of(origin));
		for(Hop alias : List.of(
			new DataOp("read", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD, null, 1, 1, 1, 1),
			new DataOp("write", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTWRITE, null, 1, 1, 1, 1),
			new UnaryOp("placement", DataType.MATRIX, ValueType.FP64, OpOp1._PLACEMENT, new LiteralOp(1)))) {
			fixture.hops.put(owner(reference), alias);
			Assert.assertTrue(uncached(fixture, reference, owner(origin)));
		}
		fixture.hops.remove(owner(reference));
		Assert.assertTrue("synthetic owners without a Hop retain alias traversal",
			uncached(fixture, reference, owner(origin)));
		fixture.hops.put(owner(reference), new UnaryOp("compute", DataType.MATRIX,
			ValueType.FP64, OpOp1.ABS, new LiteralOp(1)));
		Assert.assertFalse("an operand is not the result of a computation",
			uncached(fixture, reference, owner(origin)));
		Assert.assertTrue(uncached(fixture, reference, owner(reference)));
	}

	@Test
	public void cacheSeparatesOriginsRealizationsAndAnalysisSnapshots() throws Exception {
		Fixture fixture = new Fixture();
		var a = fixture.node("a");
		var b = fixture.node("b");
		var first = fixture.node("first");
		var second = fixture.variant(first, "second");
		fixture.edges(first, List.of(a));
		fixture.edges(second, List.of(b));
		Grounding grounding = fixture.grounding();
		Assert.assertTrue(cached(grounding, first, owner(a)));
		Assert.assertFalse(cached(grounding, first, owner(b)));
		Assert.assertFalse(cached(grounding, second, owner(a)));
		Assert.assertTrue(cached(grounding, second, owner(b)));
		CompiledHopKey original = owner(a);
		CompiledHopKey equalButForeign = new CompiledHopKey(original.programFingerprint(),
			original.functionNamespace(), original.callSitePath(), original.recompileContext(),
			original.controlRegion(), original.emittedHopInstance(), original.canonicalSourceOrigin());
		Assert.assertEquals(original, equalButForeign);
		Assert.assertFalse("origin ownership uses identity, not structural equality",
			cached(grounding, first, equalButForeign));
		int calls = fixture.expansionCount();
		Assert.assertTrue(cached(fixture.grounding(), first, owner(a)));
		Assert.assertTrue("separate Groundings do not inherit another query cache",
			fixture.expansionCount() > calls);
	}

	@Test
	public void cyclicGraphsMatchIndependentTransitiveClosure() throws Exception {
		Random random = new Random(0xA11A5L);
		for(int sample = 0; sample < 40; sample++) {
			Fixture fixture = new Fixture();
			List<CandidateRealizationReference> nodes = new ArrayList<>();
			for(int node = 0; node < 8; node++)
				nodes.add(fixture.node("node-" + node));
			boolean[][] reachable = new boolean[8][8];
			for(int from = 0; from < 8; from++) {
				boolean alias = random.nextInt(4) != 0;
				if(!alias) fixture.hops.put(owner(nodes.get(from)), new LiteralOp(from));
				List<CandidateRealizationReference> targets = new ArrayList<>();
				for(int to = 0; to < 8; to++) if(random.nextInt(4) == 0) {
					targets.add(nodes.get(to));
					reachable[from][to] = alias;
				}
				fixture.edges(nodes.get(from), targets);
				reachable[from][from] = true;
			}
			for(int via = 0; via < 8; via++)
				for(int from = 0; from < 8; from++)
					for(int to = 0; to < 8; to++)
						reachable[from][to] |= reachable[from][via] && reachable[via][to];
			Grounding grounding = fixture.grounding();
			for(int from = 0; from < 8; from++)
				for(int to = 0; to < 8; to++)
					Assert.assertEquals("sample=" + sample + ",from=" + from + ",to=" + to,
						reachable[from][to], cached(grounding, nodes.get(from), owner(nodes.get(to))));
		}
	}

	private static boolean uncached(Fixture fixture, CandidateRealizationReference reference,
		CompiledHopKey origin) throws Exception {
		Method method = JointValueMapRelations.class.getDeclaredMethod("aliasesOrigin",
			PlacementAnalysis.class, CandidateRealizationReference.class, CompiledHopKey.class, Set.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null, fixture.analysis, reference, origin, new HashSet<>());
	}

	private static boolean cached(Grounding grounding, CandidateRealizationReference reference,
		CompiledHopKey origin) throws Exception {
		Method method = Grounding.class.getDeclaredMethod("aliasesOrigin",
			CandidateRealizationReference.class, CompiledHopKey.class);
		method.setAccessible(true);
		return (boolean)method.invoke(grounding, reference, origin);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> sources(Grounding grounding,
		CandidateRealizationSupportClause clause, CandidateRealizationReference reference,
		CompiledHopKey supplier, CompiledHopKey origin) throws Exception {
		Class<?> queryType = Class.forName(JointValueMapRelations.class.getName() + "$PoolQuery");
		var constructor = queryType.getDeclaredConstructor(
			CandidateRealizationReference.class, CompiledHopKey.class, CompiledHopKey.class);
		constructor.setAccessible(true);
		Method method = Grounding.class.getDeclaredMethod("sources", CandidateRealizationSupportClause.class, queryType);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>)method.invoke(grounding, clause,
			constructor.newInstance(reference, supplier, origin));
	}

	private static CompiledHopKey owner(CandidateRealizationReference reference) {
		return reference.rule().parentOccurrence();
	}

	private static final class Fixture {
		private static final PlacementEmissionState FED = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		private final PlacementAnalysis analysis = Mockito.mock(PlacementAnalysis.class);
		private final Map<CompiledHopKey,Hop> hops = new IdentityHashMap<>();
		private final Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = new HashMap<>();
		private final Map<CandidateRealizationReference,Integer> expansions = new HashMap<>();

		private Fixture() {
			Mockito.when(analysis.hop(Mockito.any())).thenAnswer(call -> Optional.ofNullable(hops.get(call.getArgument(0))));
			Mockito.when(analysis.requireExactCandidateRealization(Mockito.any())).thenAnswer(call -> {
				CandidateRealizationReference reference = call.getArgument(0);
				expansions.merge(reference, 1, Integer::sum);
				return java.util.Objects.requireNonNull(realizations.get(reference));
			});
		}

		private CandidateRealizationReference node(String name) {
			ControlRegionKey region = new ControlRegionKey("alias-test", "main", List.of("main"), "main", "compiled");
			CompiledHopKey owner = new CompiledHopKey("alias-test", "main", "main", "compiled", region, name, name);
			hops.put(owner, new DataOp(name, DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, null, 1, 1, 1, 1));
			return realization(new CandidateRuleKey(owner, List.of()), name);
		}

		private CandidateRealizationReference variant(CandidateRealizationReference reference, String name) {
			return realization(reference.rule(), name);
		}

		private CandidateRealizationReference realization(CandidateRuleKey rule, String name) {
			var realization = CandidateEmissionRealization.valueMap(FED, name,
				List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
			var reference = CandidateRealizationReference.of(rule, realization);
			realizations.put(reference, realization);
			return reference;
		}

		@SafeVarargs
		private final void edges(CandidateRealizationReference reference, List<CandidateRealizationReference>... rows) {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
			for(var row : rows) {
				List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
				for(int position = 0; position < row.size(); position++)
					bindings.add(CandidateRealizationInputBinding.logicalTransient(position, row.get(position)));
				clauses.add(new CandidateRealizationSupportClause(List.of(), bindings));
			}
			realizations.put(reference, new CandidateEmissionRealization(reference.realization(), clauses));
		}

		private int expansionCount() {
			return expansions.values().stream().mapToInt(Integer::intValue).sum();
		}

		private Grounding grounding() {
			CompiledHopKey key = owner(realizations.keySet().iterator().next());
			return new Grounding(analysis, new Relation(key, List.of(key, key), List.of(new Row(List.of(
				new InputSource(0, key, key), new InputSource(1, key, key)))), List.of(key)));
		}
	}
}

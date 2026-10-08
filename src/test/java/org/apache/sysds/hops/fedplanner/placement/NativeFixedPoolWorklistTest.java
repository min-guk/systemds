/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.FixedValueMapPool;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Behavior lock for revision-shared fixed-pool graph/worklist resolution. */
public class NativeFixedPoolWorklistTest {
	@Test
	public void fixedPoolWorkCountersStayLinearAndCachedQueriesDoNoWork() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Graph graph = longDiamondGraph(metrics);
		NativePlacementContinuity continuity = graph.continuity();
		continuity.fixedValueMapPool(graph.reference("root"));
		var first = continuity.fixedPoolWorkSnapshot();
		Assert.assertEquals(101L, first.decodedRows());
		Assert.assertEquals(101L, first.activations());
		Assert.assertEquals(102L, first.groundingEdgeVisits());
		Assert.assertEquals(103L, first.geometryVisits());
		Assert.assertTrue(first.inexactEdgeVisits() <= 204L);

		continuity.fixedValueMapPool(graph.reference("root"));
		Assert.assertEquals("a cached root query must perform no work",
			first, continuity.fixedPoolWorkSnapshot());

		Graph unmeasured = longDiamondGraph();
		unmeasured.continuity().fixedValueMapPool(unmeasured.reference("root"));
		var zero = unmeasured.continuity().fixedPoolWorkSnapshot();
		Assert.assertEquals(0L, zero.decodedRows());
		Assert.assertEquals(0L, zero.activations());
		Assert.assertEquals(0L, zero.groundingEdgeVisits());
		Assert.assertEquals(0L, zero.geometryVisits());
		Assert.assertEquals(0L, zero.inexactEdgeVisits());
	}

	@Test
	public void sharedRowsDecodeOnceButEachDistinctQueryActivatesItsReachableGraph() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Graph graph = longDiamondGraph(metrics);
		NativePlacementContinuity continuity = graph.continuity();
		continuity.fixedValueMapPool(graph.reference("alias-47"));
		var middle = continuity.fixedPoolWorkSnapshot();
		Assert.assertEquals(49L, middle.decodedRows());
		Assert.assertEquals(49L, middle.activations());
		Assert.assertEquals(48L, middle.groundingEdgeVisits());
		Assert.assertEquals(49L, middle.geometryVisits());
		Assert.assertTrue(middle.inexactEdgeVisits() <= 96L);

		continuity.fixedValueMapPool(graph.reference("root"));
		var root = continuity.fixedPoolWorkSnapshot();
		Assert.assertEquals("the shared chain rows must not be decoded again", 101L,
			root.decodedRows());
		Assert.assertEquals("activation is honestly per distinct query", 150L,
			root.activations());
		Assert.assertEquals(150L, root.groundingEdgeVisits());
		Assert.assertEquals(152L, root.geometryVisits());
		Assert.assertTrue(root.inexactEdgeVisits() <= 300L);

		continuity.fixedValueMapPool(graph.reference("root"));
		Assert.assertEquals(root, continuity.fixedPoolWorkSnapshot());
	}

	@Test
	public void dynamicLeafPropagatesInexactnessAcrossEveryReachableEdgeOnce() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Graph graph = longDiamondGraph(metrics, true);
		FixedValueMapPool fixed = graph.continuity().fixedValueMapPool(graph.reference("root"));
		Assert.assertNotNull(fixed);
		Assert.assertFalse(fixed.exactLayout());
		Assert.assertFalse(fixed.exactPhysicalLayout());
		var work = graph.continuity().fixedPoolWorkSnapshot();
		Assert.assertEquals(101L, work.decodedRows());
		Assert.assertEquals(101L, work.activations());
		Assert.assertEquals(102L, work.groundingEdgeVisits());
		Assert.assertEquals(103L, work.geometryVisits());
		Assert.assertEquals("reverse false propagation must visit each reachable edge once",
			102L, work.inexactEdgeVisits());
	}

	@Test
	public void longAliasChainAndSharedDiamondAreQueryOrderIndependent() {
		Graph first = longDiamondGraph();
		FixedValueMapPool rootFirst = first.continuity().fixedValueMapPool(first.reference("root"));
		FixedValueMapPool middleSecond = first.continuity().fixedValueMapPool(first.reference("alias-47"));
		assertExactPool(first.pool("pool"), rootFirst);
		assertExactPool(first.pool("pool"), middleSecond);

		Graph reverse = longDiamondGraph();
		assertExactPool(reverse.pool("pool"),
			reverse.continuity().fixedValueMapPool(reverse.reference("alias-47")));
		assertExactPool(reverse.pool("pool"),
			reverse.continuity().fixedValueMapPool(reverse.reference("right")));
		assertExactPool(reverse.pool("pool"),
			reverse.continuity().fixedValueMapPool(reverse.reference("root")));
	}

	@Test
	public void leafAndSelfMustBeOneConjunctionNotSeparatelySelectableClauses() {
		GraphBuilder builder = new GraphBuilder();
		Element leaf = builder.leaf("leaf", rowPool("pool", "a:9000", "b:9000", 4, 2));
		AliasShell grounded = builder.aliasShell("grounded", 2);
		AliasShell selectable = builder.aliasShell("selectable", 2);
		builder.realize(grounded, List.of(List.of(grounded.reference(), leaf.reference())));
		builder.realize(selectable, List.of(
			List.of(selectable.reference()), List.of(leaf.reference())));
		Graph graph = builder.build(Map.of("pool", leaf.pool()));

		assertExactPool(leaf.pool(), graph.continuity().fixedValueMapPool(grounded.reference()));
		Assert.assertNull("a separately selectable ungrounded clause cannot borrow its sibling leaf",
			graph.continuity().fixedValueMapPool(selectable.reference()));
	}

	@Test
	public void laterGroundedFirstInputDoesNotReplaceSecondSweepRepresentative() {
		GraphBuilder builder = new GraphBuilder();
		DurableAnchorKey poolA = rowPool("anchor-a", "a:9000", "b:9000", 4, 2);
		DurableAnchorKey poolB = rowPool("anchor-b", "a:9000", "b:9000", 4, 2);
		Element x = builder.leaf("x", poolA);
		Element b = builder.leaf("b", poolB);
		AliasShell a = builder.aliasShell("a", 1);
		AliasShell root = builder.aliasShell("root", 2);
		// Registration order is R, A, B, X in the reachable BFS even though the
		// builder stores facts independently: R's first source grounds one sweep later.
		builder.realize(a, List.of(List.of(x.reference())));
		builder.realize(root, List.of(List.of(a.reference(), b.reference())));
		Graph graph = builder.build(Map.of());

		FixedValueMapPool fixed = graph.continuity().fixedValueMapPool(root.reference());
		Assert.assertEquals("legacy sweep resolves R from B before A grounds and retains B",
			new FixedValueMapPool(poolB, true, true), fixed);
	}

	@Test
	public void selfIncompatiblePartEndpointsRemainUnresolvedAfterStableSweep() {
		GraphBuilder builder = new GraphBuilder();
		DurableAnchorKey part = new DurableAnchorKey("part", FType.PART, List.of(
			new AnchorPartition("a:9000", List.of(0L, 0L), List.of(4L, 2L))));
		Element leaf = builder.leaf("part-leaf", part);
		AliasShell alias = builder.aliasShell("part-alias", 1);
		builder.realize(alias, List.of(List.of(leaf.reference())));
		Graph graph = builder.build(Map.of());

		Assert.assertFalse(PlacementIdentity.samePhysicalWorkerEndpoints(part, part));
		Assert.assertNull("PART has no supported endpoint identity and cannot gain authority",
			graph.continuity().fixedValueMapPool(alias.reference()));
	}

	@Test
	public void structurallyEqualForeignOwnerCannotPoisonOrBorrowDecodedRows() {
		GraphBuilder negativeFirst = new GraphBuilder();
		Element negativeLeaf = negativeFirst.leaf("leaf",
			rowPool("pool", "a:9000", "b:9000", 4, 2));
		AliasShell negativeChild = negativeFirst.aliasShell("child", 1);
		negativeFirst.realize(negativeChild, List.of(List.of(negativeLeaf.reference())));
		CandidateRealizationReference negativeForeign = foreignReference(negativeChild);
		AliasShell canonicalParent = negativeFirst.aliasShell("parent", 1);
		negativeFirst.realize(canonicalParent,
			List.of(List.of(negativeChild.reference())));
		Graph negativeGraph = negativeFirst.build(Map.of());
		Assert.assertNull("the foreign owner has no candidate-fact authority",
			negativeGraph.continuity().fixedValueMapPool(negativeForeign));
		assertExactPool(negativeLeaf.pool(),
			negativeGraph.continuity().fixedValueMapPool(canonicalParent.reference()));

		GraphBuilder positiveFirst = new GraphBuilder();
		Element positiveLeaf = positiveFirst.leaf("leaf",
			rowPool("pool", "a:9000", "b:9000", 4, 2));
		AliasShell positiveChild = positiveFirst.aliasShell("child", 1);
		positiveFirst.realize(positiveChild, List.of(List.of(positiveLeaf.reference())));
		CandidateRealizationReference positiveForeign = foreignReference(positiveChild);
		AliasShell foreignParent = positiveFirst.aliasShell("parent", 1);
		positiveFirst.realize(foreignParent, List.of(List.of(positiveForeign)));
		Graph positiveGraph = positiveFirst.build(Map.of());
		assertExactPool(positiveLeaf.pool(),
			positiveGraph.continuity().fixedValueMapPool(positiveChild.reference()));
		Assert.assertNull("a valid structural twin cannot lend its decoded row to a foreign owner",
			positiveGraph.continuity().fixedValueMapPool(foreignParent.reference()));
	}

	@Test
	public void conflictingEndpointsHiddenBehindCycleInvalidateOnlyReachableAncestors() {
		for(boolean unrelatedFirst : List.of(false, true)) {
			GraphBuilder builder = new GraphBuilder();
			Element leafA = builder.leaf("leaf-a", rowPool("pool-a", "a:9000", "b:9000", 4, 2));
			Element leafB = builder.leaf("leaf-b", rowPool("pool-b", "a:9000", "c:9000", 4, 2));
			AliasShell left = builder.aliasShell("left-cycle", 2);
			AliasShell right = builder.aliasShell("right-cycle", 2);
			AliasShell ancestor = builder.aliasShell("ancestor", 1);
			AliasShell unrelated = builder.aliasShell("unrelated", 1);
			builder.realize(left, List.of(List.of(right.reference(), leafA.reference())));
			builder.realize(right, List.of(List.of(left.reference(), leafB.reference())));
			builder.realize(ancestor, List.of(List.of(left.reference())));
			builder.realize(unrelated, List.of(List.of(leafA.reference())));
			Graph graph = builder.build(Map.of("pool-a", leafA.pool(), "pool-b", leafB.pool()));

			if(unrelatedFirst)
				assertExactPool(leafA.pool(), graph.continuity().fixedValueMapPool(unrelated.reference()));
			Assert.assertNull("mixed worker endpoints inside a reachable cycle invalidate its ancestor",
				graph.continuity().fixedValueMapPool(ancestor.reference()));
			assertExactPool(leafA.pool(), graph.continuity().fixedValueMapPool(unrelated.reference()));
		}
	}

	@Test
	public void dynamicPartitionAndFullGeometryFlagsPropagateThroughAliases() {
		GraphBuilder builder = new GraphBuilder();
		DurableAnchorKey dynamicPool = rowPool("dynamic", "a:9000", "b:9000", 4, 2);
		Element dynamic = builder.dynamicLeaf("dynamic-leaf", dynamicPool);
		AliasShell dynamicParent = builder.aliasShell("dynamic-parent", 1);
		builder.realize(dynamicParent, List.of(List.of(dynamic.reference())));

		Element partitionA = builder.leaf("partition-a",
			rowPool("partition-a", "a:9000", "b:9000", 4, 2));
		Element partitionB = builder.leaf("partition-b",
			rowPool("partition-b", "a:9000", "b:9000", 4, 3));
		AliasShell partitionJoin = builder.aliasShell("partition-join", 2);
		AliasShell partitionParent = builder.aliasShell("partition-parent", 1);
		builder.realize(partitionJoin,
			List.of(List.of(partitionA.reference(), partitionB.reference())));
		builder.realize(partitionParent, List.of(List.of(partitionJoin.reference())));

		Element fullA = builder.leaf("full-a", fullPool("full-a", 1));
		Element fullB = builder.leaf("full-b", fullPool("full-b", 7));
		AliasShell fullJoin = builder.aliasShell("full-join", 2);
		AliasShell fullParent = builder.aliasShell("full-parent", 1);
		builder.realize(fullJoin, List.of(List.of(fullA.reference(), fullB.reference())));
		builder.realize(fullParent, List.of(List.of(fullJoin.reference())));
		Graph graph = builder.build(Map.of());

		FixedValueMapPool dynamicFixed = graph.continuity().fixedValueMapPool(dynamicParent.reference());
		Assert.assertNotNull(dynamicFixed);
		Assert.assertFalse(dynamicFixed.exactLayout());
		Assert.assertFalse(dynamicFixed.exactPhysicalLayout());

		FixedValueMapPool partitionFixed =
			graph.continuity().fixedValueMapPool(partitionParent.reference());
		Assert.assertNotNull(partitionFixed);
		Assert.assertTrue("ROW authority ignores only the orthogonal extent",
			partitionFixed.exactLayout());
		Assert.assertFalse(partitionFixed.exactPhysicalLayout());

		FixedValueMapPool fullFixed = graph.continuity().fixedValueMapPool(fullParent.reference());
		Assert.assertNotNull(fullFixed);
		Assert.assertTrue("FULL worker-pool authority is endpoint based", fullFixed.exactLayout());
		Assert.assertFalse("different FULL ranges are not complete physical geometry",
			fullFixed.exactPhysicalLayout());
	}

	@Test
	public void nextRevisionRetargetsDependencyAndMatchesFreshResolution() {
		GraphBuilder initialBuilder = new GraphBuilder();
		Element leafA = initialBuilder.leaf("leaf-a",
			rowPool("pool-a", "a:9000", "b:9000", 4, 2));
		Element leafB = initialBuilder.leaf("leaf-b",
			rowPool("pool-b", "c:9000", "d:9000", 4, 2));
		AliasShell root = initialBuilder.aliasShell("root", 1);
		initialBuilder.realize(root, List.of(List.of(leafA.reference())));
		Graph initial = initialBuilder.build(Map.of());
		assertExactPool(leafA.pool(), initial.continuity().fixedValueMapPool(root.reference()));

		List<CandidateRuleFact> revisedFacts = new ArrayList<>();
		revisedFacts.add(initial.fact("leaf-a"));
		revisedFacts.add(initial.fact("leaf-b"));
		CandidateEmissionRealization retargeted = valueMap(root,
			List.of(List.of(leafB.reference())));
		revisedFacts.add(fact(root.rule(), retargeted));
		NativePlacementContinuity revised = initial.continuity().nextRevision(revisedFacts);
		assertExactPool(leafB.pool(), revised.fixedValueMapPool(root.reference()));

		NativePlacementContinuity fresh = continuity(initial.nodes(), revisedFacts);
		FixedValueMapPool freshPool = fresh.fixedValueMapPool(root.reference());
		assertExactPool(leafB.pool(), freshPool);
		Assert.assertEquals(freshPool, revised.fixedValueMapPool(root.reference()));
	}

	@Test
	public void randomizedFiniteGraphsMatchFrozenRepeatedScanResolverAcrossQueryOrders() {
		Random random = new Random(0xF17ED00DL);
		for(int trial = 0; trial < 80; trial++) {
			GraphBuilder builder = new GraphBuilder();
			List<Element> values = new ArrayList<>();
			values.add(builder.leaf("t" + trial + "-leaf-a",
				rowPool("a", "a:9000", "b:9000", 4, 2)));
			values.add(builder.leaf("t" + trial + "-leaf-geometry",
				rowPool("geometry", "a:9000", "b:9000", 4, 5)));
			values.add(builder.dynamicLeaf("t" + trial + "-leaf-other",
				rowPool("other", "a:9000", "c:9000", 4, 2)));
			List<AliasShell> aliases = new ArrayList<>();
			for(int index = 0; index < 7; index++)
				aliases.add(builder.aliasShell("t" + trial + "-alias-" + index, 3));
			List<CandidateRealizationReference> choices = new ArrayList<>();
			for(Element value : values)
				choices.add(value.reference());
			for(AliasShell alias : aliases)
				choices.add(alias.reference());
			for(AliasShell alias : aliases) {
				List<List<CandidateRealizationReference>> clauses = new ArrayList<>();
				Set<List<Integer>> distinct = new HashSet<>();
				int clauseCount = 1 + random.nextInt(2);
				while(clauses.size() < clauseCount) {
					List<Integer> indexes = new ArrayList<>();
					for(int count = 1 + random.nextInt(3); indexes.size() < count;) {
						int candidate = random.nextInt(choices.size());
						if(!indexes.contains(candidate))
							indexes.add(candidate);
					}
					if(!distinct.add(List.copyOf(indexes)))
						continue;
					clauses.add(indexes.stream().map(choices::get).toList());
				}
				builder.realize(alias, clauses);
				values.add(alias.element());
			}
			Graph graph = builder.build(Map.of());
			Map<CandidateRealizationReference,OracleNode> oracle = oracleGraph(graph);
			Map<CandidateRealizationReference,FixedValueMapPool> oracleCache = new HashMap<>();
			Set<CandidateRealizationReference> oracleUnresolved = new HashSet<>();
			List<Element> queryOrder = aliases.stream().map(AliasShell::element)
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
			Collections.shuffle(queryOrder, random);
			for(Element value : queryOrder)
				Assert.assertEquals("trial " + trial + " query "
					+ value.node().key().canonicalSourceOrigin(),
					resolveFrozen(value.reference(), oracle, oracleCache, oracleUnresolved),
					graph.continuity().fixedValueMapPool(value.reference()));
		}
	}

	private static Graph longDiamondGraph() {
		return longDiamondGraph(null);
	}

	private static Graph longDiamondGraph(SearchSpaceMetrics metrics) {
		return longDiamondGraph(metrics, false);
	}

	private static Graph longDiamondGraph(SearchSpaceMetrics metrics, boolean dynamicLeaf) {
		GraphBuilder builder = new GraphBuilder(metrics);
		DurableAnchorKey pool = rowPool("pool", "a:9000", "b:9000", 4, 2);
		Element previous = dynamicLeaf
			? builder.dynamicLeaf("leaf", pool) : builder.leaf("leaf", pool);
		for(int index = 0; index < 96; index++) {
			AliasShell alias = builder.aliasShell("alias-" + index, 1);
			builder.realize(alias, List.of(List.of(previous.reference())));
			previous = alias.element();
		}
		AliasShell left = builder.aliasShell("left", 1);
		AliasShell right = builder.aliasShell("right", 1);
		builder.realize(left, List.of(List.of(previous.reference())));
		builder.realize(right, List.of(List.of(previous.reference())));
		AliasShell diamond = builder.aliasShell("diamond", 2);
		builder.realize(diamond, List.of(List.of(left.reference(), right.reference())));
		AliasShell root = builder.aliasShell("root", 1);
		builder.realize(root, List.of(List.of(diamond.reference()), List.of(
			builder.elements.get("leaf").reference())));
		return builder.build(Map.of("pool", pool));
	}

	private static void assertExactPool(DurableAnchorKey expected, FixedValueMapPool actual) {
		Assert.assertNotNull(actual);
		Assert.assertEquals("the deterministic representative pool must be preserved",
			new FixedValueMapPool(expected, true, true), actual);
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(expected, actual.pool()));
		Assert.assertTrue(actual.exactLayout());
		Assert.assertTrue(actual.exactPhysicalLayout());
	}

	private static CandidateRealizationReference foreignReference(AliasShell canonical) {
		CompiledHopKey key = canonical.node().key();
		CompiledHopKey foreign = new CompiledHopKey(key.programFingerprint(),
			key.functionNamespace(), key.callSitePath(), key.recompileContext(),
			key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
		Assert.assertEquals(key, foreign);
		Assert.assertNotSame(key, foreign);
		CandidateRuleKey foreignRule = new CandidateRuleKey(
			foreign, canonical.rule().orderedInputs());
		CandidateRealizationReference reference =
			new CandidateRealizationReference(foreignRule, canonical.key());
		Assert.assertEquals(canonical.reference(), reference);
		Assert.assertNotSame(canonical.reference(), reference);
		return reference;
	}

	private record OracleNode(FixedValueMapPool leaf,
		List<List<CandidateRealizationReference>> clauses) { }

	private static Map<CandidateRealizationReference,OracleNode> oracleGraph(Graph graph) {
		Map<CandidateRealizationReference,OracleNode> oracle = new HashMap<>();
		for(Element element : graph.elements().values()) {
			CandidateEmissionRealization realization = element.realization();
			DurableAnchorKey leaf = realization.nativeWorkerPoolResidencyForOwnedClause(
				realization.supportClauses().get(0));
			if(leaf != null) {
				boolean exact = realization.nativeWorkerPoolLayoutExactForOwnedClause(
					realization.supportClauses().get(0));
				oracle.put(element.reference(), new OracleNode(
					new FixedValueMapPool(leaf, exact, exact), List.of()));
				continue;
			}
			List<List<CandidateRealizationReference>> clauses = realization.supportClauses().stream()
				.map(clause -> clause.inputBindings().stream()
					.map(CandidateRealizationInputBinding::source).toList()).toList();
			oracle.put(element.reference(), new OracleNode(null, clauses));
		}
		return oracle;
	}

	/** Frozen semantic copy of the former per-query BFS plus repeated whole-graph scans. */
	private static FixedValueMapPool resolveFrozen(CandidateRealizationReference root,
		Map<CandidateRealizationReference,OracleNode> all,
		Map<CandidateRealizationReference,FixedValueMapPool> cache,
		Set<CandidateRealizationReference> unresolved) {
		FixedValueMapPool cached = cache.get(root);
		if(cached != null || unresolved.contains(root))
			return cached;
		FixedValueMapPool result = resolveFrozenGraph(root, all, cache);
		if(result == null)
			unresolved.add(root);
		return result;
	}

	private static FixedValueMapPool resolveFrozenGraph(CandidateRealizationReference root,
		Map<CandidateRealizationReference,OracleNode> all,
		Map<CandidateRealizationReference,FixedValueMapPool> cache) {
		Map<CandidateRealizationReference,OracleNode> graph = new LinkedHashMap<>();
		ArrayDeque<CandidateRealizationReference> pending = new ArrayDeque<>();
		pending.add(root);
		while(!pending.isEmpty()) {
			CandidateRealizationReference current = pending.removeFirst();
			if(graph.containsKey(current))
				continue;
			OracleNode node = all.get(current);
			if(node == null)
				return null;
			graph.put(current, node);
			for(List<CandidateRealizationReference> clause : node.clauses())
				pending.addAll(clause);
		}
		Map<CandidateRealizationReference,FixedValueMapPool> resolved = new HashMap<>();
		boolean changed;
		do {
			changed = false;
			for(var entry : graph.entrySet()) {
				OracleNode node = entry.getValue();
				if(node.leaf() != null) {
					changed |= resolved.putIfAbsent(entry.getKey(), node.leaf()) == null;
					continue;
				}
				FixedValueMapPool common = null;
				boolean complete = true;
				for(List<CandidateRealizationReference> clause : node.clauses()) {
					FixedValueMapPool clausePool = null;
					for(CandidateRealizationReference source : clause) {
						FixedValueMapPool sourcePool = resolved.get(source);
						if(sourcePool == null)
							continue;
						if(clausePool != null && !sameEndpoints(clausePool, sourcePool))
							return null;
						clausePool = merge(clausePool, sourcePool);
					}
					if(clausePool == null) {
						complete = false;
						continue;
					}
					if(common != null && !sameEndpoints(common, clausePool))
						return null;
					common = merge(common, clausePool);
				}
				if(complete && common != null) {
					FixedValueMapPool prior = resolved.putIfAbsent(entry.getKey(), common);
					if(prior != null && !sameEndpoints(prior, common))
						return null;
					changed |= prior == null;
				}
			}
		}
		while(changed);
		if(resolved.size() != graph.size())
			return null;

		Set<CandidateRealizationReference> inexact = new HashSet<>();
		Set<CandidateRealizationReference> physicallyInexact = new HashSet<>();
		do {
			changed = false;
			for(var entry : graph.entrySet()) {
				boolean exact = true, physicallyExact = true;
				DurableAnchorKey representative = null;
				OracleNode node = entry.getValue();
				if(node.leaf() != null) {
					exact = node.leaf().exactLayout();
					physicallyExact = node.leaf().exactPhysicalLayout();
					representative = node.leaf().pool();
				}
				for(List<CandidateRealizationReference> clause : node.clauses())
					for(CandidateRealizationReference source : clause) {
						exact &= !inexact.contains(source);
						physicallyExact &= !physicallyInexact.contains(source);
						DurableAnchorKey sourcePool = resolved.get(source).pool();
						if(representative != null
							&& !PlacementIdentity.samePhysicalWorkerPool(representative, sourcePool))
							exact = false;
						if(representative != null
							&& !PlacementIdentity.samePhysicalLayout(representative, sourcePool))
							physicallyExact = false;
						representative = representative == null ? sourcePool : representative;
					}
				if(!exact)
					changed |= inexact.add(entry.getKey());
				if(!physicallyExact)
					changed |= physicallyInexact.add(entry.getKey());
			}
		}
		while(changed);
		for(var entry : resolved.entrySet())
			cache.put(entry.getKey(), new FixedValueMapPool(entry.getValue().pool(),
				!inexact.contains(entry.getKey()), !physicallyInexact.contains(entry.getKey())));
		return cache.get(root);
	}

	private static boolean sameEndpoints(FixedValueMapPool left, FixedValueMapPool right) {
		return left.pool().fType() == right.pool().fType()
			&& PlacementIdentity.samePhysicalWorkerEndpoints(left.pool(), right.pool());
	}

	private static FixedValueMapPool merge(FixedValueMapPool left, FixedValueMapPool right) {
		return left == null ? right : new FixedValueMapPool(left.pool(),
			left.exactLayout() && right.exactLayout(),
			left.exactPhysicalLayout() && right.exactPhysicalLayout());
	}

	private record Element(Node node, CandidateRuleKey rule,
		CandidateEmissionRealization realization, CandidateRealizationReference reference,
		CandidateRuleFact fact, DurableAnchorKey pool) { }

	private record AliasShell(Node node, CandidateRuleKey rule, PlacementRealizationKey key,
		CandidateRealizationReference reference, GraphBuilder owner) {
		private Element element() { return owner.elements.get(node.key().canonicalSourceOrigin()); }
	}

	private record Graph(NativePlacementContinuity continuity, Map<String,Element> elements,
		List<Node> nodes, List<CandidateRuleFact> facts, Map<String,DurableAnchorKey> pools) {
		private CandidateRealizationReference reference(String name) {
			return elements.get(name).reference();
		}
		private CandidateRuleFact fact(String name) { return elements.get(name).fact(); }
		private DurableAnchorKey pool(String name) { return pools.get(name); }
	}

	private static final class GraphBuilder {
		private final Map<String,Element> elements = new LinkedHashMap<>();
		private final List<Node> nodes = new ArrayList<>();
		private final SearchSpaceMetrics metrics;
		private int ordinal;

		private GraphBuilder() { this(null); }
		private GraphBuilder(SearchSpaceMetrics metrics) { this.metrics = metrics; }

		private Element leaf(String name, DurableAnchorKey pool) {
			return leaf(name, pool, false);
		}

		private Element dynamicLeaf(String name, DurableAnchorKey pool) {
			return leaf(name, pool, true);
		}

		private Element leaf(String name, DurableAnchorKey pool, boolean dynamic) {
			Node node = node(name, ordinal++, pool.fType());
			CandidateRuleKey rule = rule(node, 0);
			PlacementEmissionState emission = emission(pool.fType());
			CandidateEmissionRealization realization = dynamic
				? CandidateEmissionRealization.nativeLineageDynamicLayout(emission,
					"dynamic-" + name, pool, List.of(new PlacementProofKey(
						PlacementProofKind.NATIVE_CONTINUITY, node.key(), pool.normalizedSignature())), List.of())
				: CandidateEmissionRealization.durable(emission, pool, List.of(), List.of());
			Element element = new Element(node, rule, realization,
				CandidateRealizationReference.of(rule, realization), fact(rule, realization), pool);
			remember(name, element);
			return element;
		}

		private AliasShell aliasShell(String name, int inputs) {
			Node node = node(name, ordinal++, FType.ROW);
			CandidateRuleKey rule = rule(node, inputs);
			PlacementRealizationKey key = PlacementRealizationKey.valueMap(
				emission(FType.ROW), "map-" + name);
			return new AliasShell(node, rule, key,
				new CandidateRealizationReference(rule, key), this);
		}

		private void realize(AliasShell shell,
			List<List<CandidateRealizationReference>> clauses) {
			CandidateEmissionRealization realization = valueMap(shell, clauses);
			Element element = new Element(shell.node(), shell.rule(), realization,
				shell.reference(), fact(shell.rule(), realization), null);
			remember(shell.node().key().canonicalSourceOrigin(), element);
		}

		private void remember(String name, Element element) {
			if(elements.put(name, element) != null)
				throw new IllegalArgumentException("duplicate test element " + name);
			nodes.add(element.node());
		}

		private Graph build(Map<String,DurableAnchorKey> pools) {
			List<CandidateRuleFact> facts = elements.values().stream().map(Element::fact).toList();
			return new Graph(continuity(nodes, facts, metrics), Map.copyOf(elements), List.copyOf(nodes),
				facts, Map.copyOf(pools));
		}
	}

	private static CandidateEmissionRealization valueMap(AliasShell shell,
		List<List<CandidateRealizationReference>> clauses) {
		List<CandidateRealizationSupportClause> support = new ArrayList<>();
		for(List<CandidateRealizationReference> sources : clauses) {
			List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
			for(int position = 0; position < sources.size(); position++)
				bindings.add(CandidateRealizationInputBinding.logicalTransient(position,
					sources.get(position)));
			support.add(new CandidateRealizationSupportClause(List.of(), bindings));
		}
		return new CandidateEmissionRealization(shell.key(), support);
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		PlacementEmissionState emission = realization.key().emissionState();
		FType type = emission.placementState().fType();
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixed-worklist", ExecType.FED,
				FederatedOutput.FOUT, type, ReasonCode.OK, "fixed-worklist", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(rule.orderedInputs().stream()
				.map(CandidateInputState::fType).toList(), ""),
			List.of(new CandidateEmissionFact(emission, type, null, List.of(realization))), "");
	}

	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(),
			java.util.Collections.nCopies(inputs, CandidateInputState.present(FType.ROW)));
	}

	private static NativePlacementContinuity continuity(List<Node> nodes,
		List<CandidateRuleFact> facts) {
		return continuity(nodes, facts, null);
	}

	private static NativePlacementContinuity continuity(List<Node> nodes,
		List<CandidateRuleFact> facts, SearchSpaceMetrics metrics) {
		Map<CompiledHopKey,Node> nodeMap = new IdentityHashMap<>();
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> hops = new IdentityHashMap<>();
		for(Node node : nodes) {
			nodeMap.put(node.key(), node);
			hops.put(node.key(), new DataOp(node.key().canonicalSourceOrigin(),
				DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				node.key().canonicalSourceOrigin(), 8, 2, 16, 1000));
		}
		return new NativePlacementContinuity(nodeMap, hops, facts, List.of(), Map.of(), metrics);
	}

	private static Node node(String name, int ordinal, FType type) {
		ControlRegionKey region = new ControlRegionKey(
			"fixed-worklist", "main", List.of("main/0"), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey("fixed-worklist", "main", "main", "compiled",
			region, name, name);
		PlacementState state = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, type, false);
		return new Node(key, NodeKind.OPERATION,
			new ValueVersionKey("fixed-worklist", name, region, ordinal,
				VersionKind.ORDINARY, List.of()), true, List.of(state), List.of(), List.of());
	}

	private static PlacementEmissionState emission(FType type) {
		return new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, type, false), false);
	}

	private static DurableAnchorKey rowPool(
		String id, String first, String second, long split, long columns) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(split, columns)),
			new AnchorPartition(second, List.of(split, 0L), List.of(8L, columns))));
	}

	private static DurableAnchorKey fullPool(String id, long extent) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition("a:9000", List.of(0L, 0L), List.of(extent, extent)),
			new AnchorPartition("b:9000", List.of(0L, 0L), List.of(extent, extent))));
	}
}

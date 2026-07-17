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
package org.apache.sysds.test.component.federated.placement.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contracts for builder-owned fingerprints and producer-bound Heuristic policy facts. */
public class PlacementAnalysisS2ContractTest {
	private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
	private static final Path ANALYSIS = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java");
	private static final Path BUILDER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java");
	private static final List<Path> SELECTOR_CLOSURE = List.of(
		ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner/placement/selector"),
		ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter"),
		ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner/fedAll"),
		ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic"));

	@Test
	public void builderAloneOwnsTheSingleCanonicalAnalysisFingerprintComputation() throws Exception {
		String analysis = Files.readString(ANALYSIS);
		String builder = Files.readString(BUILDER);
		Assert.assertFalse("selector-reachable PlacementAnalysis still owns fingerprint traversal/hash logic",
			analysis.contains("PlacementGraphFingerprint"));
		Assert.assertFalse("selector-reachable PlacementAnalysis still depends on the production graph builder",
			analysis.contains("NeutralPlacementGraphBuilder"));
		Assert.assertEquals("builder must compute the canonical graph/projection fingerprint exactly once", 1,
			occurrences(builder, "graph.normalizedSignature()"));
		Assert.assertTrue("builder does not supply its canonical fingerprint to PlacementAnalysis",
			builder.matches("(?s).*new\\s+PlacementAnalysis\\s*\\([^;]*analysisFingerprint[^;]*\\).*"));
		for(Path root : SELECTOR_CLOSURE)
			try(var files = Files.walk(root)) {
				for(Path file : files.filter(p -> p.toString().endsWith(".java")).toList())
					Assert.assertFalse("selector closure reaches traversal owner through " + ROOT.relativize(file),
						Files.readString(file).contains("PlacementGraphFingerprint"));
			}
	}

	@Test
	public void analysisStoresTheExactSuppliedFingerprintAndRejectsMissingFacts() throws Exception {
		PlacementAnalysis source = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-15"));
		PolicyApi api = policyApi();
		Object facts = api.factsConstructor.newInstance(List.of(api.fact(source.graph().nodes().get(0))));
		Constructor<?> analysisConstructor = suppliedAnalysisConstructor(api.factsClass);
		Object[] args = suppliedArguments(source, facts, "S2-SUPPLIED-FINGERPRINT-SENTINEL");
		PlacementAnalysis copy = (PlacementAnalysis) analysisConstructor.newInstance(args);
		Assert.assertEquals("PlacementAnalysis derived or changed the supplied fingerprint",
			"S2-SUPPLIED-FINGERPRINT-SENTINEL", copy.analysisFingerprint());
		Assert.assertSame("copy seam changed the exact graph identity", source.graph(), copy.graph());
		Assert.assertSame("copy seam changed concrete Hop identity", source.occurrences().get(0).hop(),
			copy.occurrences().get(0).hop());
		args[5] = null;
		assertInvocationRejected("missing Heuristic facts silently defaulted", analysisConstructor, args);
	}

	@Test
	public void copiedReversedRepeatedAndConcurrentProjectionsPreserveTheExactFingerprint() throws Exception {
		PlacementAnalysis source = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-17"));
		PlacementAnalysis normal = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(source.graph());
		PlacementAnalysis reversed = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(source.graph(),
			ProjectionOrder.REVERSED);
		Assert.assertEquals("projection reversal changed the analysis universe", normal.analysisFingerprint(),
			reversed.analysisFingerprint());
		Assert.assertEquals("repeated reads changed the fingerprint", normal.analysisFingerprint(),
			normal.analysisFingerprint());
		Assert.assertEquals("a concrete-Hop copy changed the fingerprint", source.analysisFingerprint(),
			CampaignBPlacementAnalysisFixtureBridge.sameHopContextTrap(source).analysisFingerprint());

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<String>> tasks = new ArrayList<>();
			for(int i = 0; i < 16; i++) {
				ProjectionOrder order = i % 2 == 0 ? ProjectionOrder.NORMAL : ProjectionOrder.REVERSED;
				tasks.add(() -> CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(source.graph(), order)
					.analysisFingerprint());
			}
			Set<String> fingerprints = new LinkedHashSet<>();
			for(Future<String> future : pool.invokeAll(tasks))
				fingerprints.add(future.get());
			Assert.assertEquals("concurrent projection order created multiple analysis universes",
				Set.of(normal.analysisFingerprint()), fingerprints);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void heuristicPolicyFactsAreTypedProducerScopedDeterministicAndDeeplyImmutable() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-15"));
		PolicyApi api = policyApi();
		Assert.assertTrue("HeuristicPolicyFact must be a typed record", api.factClass.isRecord());
		Assert.assertTrue("HeuristicPolicyFacts must be a typed record", api.factsClass.isRecord());
		assertRecordComponent(api.factClass, "producer", CompiledHopKey.class);
		assertRecordComponent(api.factClass, "valueVersion", ValueVersionKey.class);
		RecordComponent demotions = assertRecordComponent(api.factsClass, "demotions", List.class);
		Assert.assertTrue("demotions lost its typed HeuristicPolicyFact element",
			demotions.getGenericType().getTypeName().contains(api.factClass.getSimpleName()));

		Node first = analysis.graph().nodes().get(0);
		Node second = analysis.graph().nodes().stream()
			.filter(node -> !node.valueVersion().equals(first.valueVersion())).findFirst().orElseThrow();
		Object firstFact = api.fact(first);
		Object secondFact = api.fact(second);
		Object forward = api.factsConstructor.newInstance(List.of(firstFact, secondFact));
		Object reverse = api.factsConstructor.newInstance(List.of(secondFact, firstFact));
		Assert.assertEquals("policy fact order leaked traversal order", forward, reverse);
		@SuppressWarnings("unchecked")
		List<Object> immutable = (List<Object>) api.demotions.invoke(forward);
		Assert.assertThrows("policy fact list remained mutable", UnsupportedOperationException.class,
			() -> immutable.add(firstFact));
		Method accessor = PlacementAnalysis.class.getMethod("heuristicPolicyFacts");
		Assert.assertSame("analysis does not return its exact immutable fact carrier",
			accessor.invoke(analysis), accessor.invoke(analysis));
		for(Object fact : (List<?>) api.demotions.invoke(accessor.invoke(analysis))) {
			CompiledHopKey producer = (CompiledHopKey) api.producer.invoke(fact);
			ValueVersionKey value = (ValueVersionKey) api.valueVersion.invoke(fact);
			Assert.assertEquals("policy fact producer/value does not exactly match the final graph", value,
				analysis.graph().node(producer).orElseThrow().valueVersion());
		}
	}

	@Test
	public void ambiguousValueVersionFactsRejectBeforeAnalysisOrSelection() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-15"));
		PolicyApi api = policyApi();
		Node first = analysis.graph().nodes().get(0);
		Node otherProducer = analysis.graph().nodes().stream()
			.filter(node -> !node.key().equals(first.key())).findFirst().orElseThrow();
		Object firstFact = api.fact(first);
		Object ambiguousFact = api.factConstructor.newInstance(otherProducer.key(), first.valueVersion());
		assertInvocationRejected("one ValueVersionKey was accepted for multiple producers",
			api.factsConstructor, List.of(firstFact, ambiguousFact));
	}

	private static PolicyApi policyApi() throws Exception {
		Class<?> factClass = Class.forName(PlacementAnalysis.class.getName() + "$HeuristicPolicyFact");
		Class<?> factsClass = Class.forName(PlacementAnalysis.class.getName() + "$HeuristicPolicyFacts");
		Constructor<?> factConstructor = factClass.getConstructor(CompiledHopKey.class, ValueVersionKey.class);
		Constructor<?> factsConstructor = factsClass.getConstructor(List.class);
		return new PolicyApi(factClass, factsClass, factConstructor, factsConstructor,
			factClass.getMethod("producer"), factClass.getMethod("valueVersion"), factsClass.getMethod("demotions"));
	}

	private static Constructor<?> suppliedAnalysisConstructor(Class<?> factsClass) {
		for(Constructor<?> constructor : PlacementAnalysis.class.getDeclaredConstructors()) {
			Class<?>[] parameters = constructor.getParameterTypes();
			if(parameters.length == 6 && parameters[4] == String.class && parameters[5] == factsClass) {
				constructor.setAccessible(true);
				return constructor;
			}
		}
		throw new AssertionError("PlacementAnalysis lacks supplied fingerprint/policy-facts construction boundary");
	}

	private static Object[] suppliedArguments(PlacementAnalysis source, Object facts, String fingerprint)
		throws Exception {
		return new Object[] {source.graph(), source.occurrences(), field(source, "programOwner"),
			field(source, "shapeFacts"), fingerprint, facts};
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static RecordComponent assertRecordComponent(Class<?> type, String name, Class<?> expectedType) {
		for(RecordComponent component : type.getRecordComponents())
			if(component.getName().equals(name)) {
				Assert.assertEquals(name + " has the wrong typed boundary", expectedType, component.getType());
				return component;
			}
		throw new AssertionError(type.getSimpleName() + " lacks record component " + name);
	}

	private static void assertInvocationRejected(String message, Constructor<?> constructor, Object... args) {
		InvocationTargetException thrown = Assert.assertThrows(message, InvocationTargetException.class,
			() -> constructor.newInstance(args));
		Assert.assertTrue(message + ": " + thrown.getCause(), thrown.getCause() instanceof IllegalArgumentException
			|| thrown.getCause() instanceof NullPointerException);
	}

	private static int occurrences(String source, String needle) {
		int count = 0;
		for(int from = 0; (from = source.indexOf(needle, from)) >= 0; from += needle.length())
			count++;
		return count;
	}

	private record PolicyApi(Class<?> factClass, Class<?> factsClass, Constructor<?> factConstructor,
		Constructor<?> factsConstructor, Method producer, Method valueVersion, Method demotions) {
		private Object fact(Node node) throws Exception {
			return factConstructor.newInstance(node.key(), node.valueVersion());
		}
	}
}

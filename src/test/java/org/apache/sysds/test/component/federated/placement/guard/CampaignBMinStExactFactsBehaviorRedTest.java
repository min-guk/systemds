/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Phase-B1a RED for immutable, owner-bound, pre-solve MinST cost facts. */
public class CampaignBMinStExactFactsBehaviorRedTest {
	private static final String PREFIX =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.";
	private static final String FACTS = PREFIX + "MinStExactCostFacts";
	private static final String PRODUCER = PREFIX + "MinStExactCostFactsProducer";
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;
	private static final long HARD_LEGALITY_BITS = Double.doubleToRawLongBits(1e15);
	private static final String[] DECISION_ACCESSORS = {
		"key", "computeNodeId", "placementNodeId", "legalStatesInCanonicalOrder"};
	private static final String[] EDGE_ACCESSORS = {
		"fromNodeId", "toNodeId", "capacityBits", "contributionsInDerivationOrder"};
	private static final String[] GROUP_ACCESSORS = {"auxiliaryNodeId", "direction", "producerKey",
		"producerPlacementNodeId", "conversionType", "priceBits", "endpointsInCanonicalOrder"};

	@Test
	public void neutralAnalysisOwnsExactCompiledInputEdgeFactsOnly() throws Exception {
		PlacementAnalysis analysis = persistentReadAnalysis();
		CompiledHopKey producer = persistentReadKey(analysis);
		List<CompiledHopKey> consumers = persistentReadConsumers(analysis, producer);
		assertPersistentReadTwoConsumerFixture(analysis);

		Class<?> edgeFact = nestedPlacementAnalysisClass("CompiledInputEdgeFact",
			"NEUTRAL_EDGE_FACT_CARRIER_MISSING");
		Assert.assertTrue("NEUTRAL_EDGE_FACT_IMMUTABLE", Modifier.isFinal(edgeFact.getModifiers()));
		assertTypedCarrier(edgeFact, new String[] {"producer", "consumer", "inputPosition"},
			new Class<?>[] {CompiledHopKey.class, CompiledHopKey.class, int.class});
		assertGenericListReturn(PlacementAnalysis.class.getMethod("compiledInputEdgesInCanonicalOrder"),
			edgeFact);
		Method require = PlacementAnalysis.class.getMethod("requireExactCompiledInputEdge",
			CompiledHopKey.class, CompiledHopKey.class, int.class);
		Assert.assertEquals(edgeFact, require.getReturnType());

		List<?> edges = list(analysis, "compiledInputEdgesInCanonicalOrder");
		assertImmutable(edges, "NEUTRAL_EDGE_FACT_LIST_MUTABLE");
		Assert.assertEquals("NEUTRAL_EDGE_FACT_PERSISTENT_CONSUMER_COUNT", 2,
			edges.stream().filter(edge -> edgeMatches(edge, producer, consumers, 1)).count());

		for(CompiledHopKey consumer : consumers) {
			Object edge = require.invoke(analysis, producer, consumer, 1);
			Assert.assertTrue("NEUTRAL_EDGE_FACT_CANONICAL_LIST_MISSING", edges.stream()
				.anyMatch(candidate -> candidate == edge));
			Assert.assertSame("NEUTRAL_EDGE_FACT_PRODUCER_IDENTITY", producer, call(edge, "producer"));
			Assert.assertSame("NEUTRAL_EDGE_FACT_CONSUMER_IDENTITY", consumer, call(edge, "consumer"));
			Assert.assertEquals("NEUTRAL_EDGE_FACT_INPUT_POSITION", 1,
				((Number)call(edge, "inputPosition")).intValue());
		}
		assertLookupRejects(require, analysis, equalCopy(producer), consumers.get(0), 1);
		assertLookupRejects(require, analysis, producer, equalCopy(consumers.get(0)), 1);
		assertLookupRejects(require, analysis, consumers.get(0), producer, 1);
		assertLookupRejects(require, analysis, producer, consumers.get(0), 0);

		Constructor<?> constructor = typedConstructor(edgeFact);
		Object valid = constructor.newInstance(producer, consumers.get(0), 1);
		Assert.assertSame("NEUTRAL_EDGE_FACT_CONSTRUCTOR_PRODUCER_IDENTITY", producer,
			call(valid, "producer"));
		assertIllegalArgument(constructor, null, consumers.get(0), 1);
		assertIllegalArgument(constructor, producer, null, 1);
		assertIllegalArgument(constructor, producer, consumers.get(0), -1);
		assertAnalysisRejectsCorruptedInputEdges(analysis, edgeFact, edges, valid);
	}

	@Test
	public void neutralBoundaryExcludesMinStNumericCostAndDpCloneSemantics() throws Exception {
		assertSourceExcludes(Path.of("src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java"),
			"RewireConstants", "DEFAULT_LOOP_WEIGHT", "DEFAULT_IF_ELSE_WEIGHT", "estimateWhileLoopWeight",
			"computeForwardingWeightOfChild", "selectRequiredTargetType", "TransferSourceProof",
			"TransferSourceKind", "semanticHash");
		assertSourceExcludes(Path.of("src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java"),
			"ExecutionFrequencyFact", "ProducerConsumerDemandFact", "executionCostFactsFingerprint",
			"forwardingWeight", "computeWeight", "networkWeight", "multiplicity");
		for(Path source : Files.list(Path.of("src/main/java/org/apache/sysds/hops/fedplanner/placement"))
			.filter(path -> path.getFileName().toString().endsWith(".java")).toList())
			assertSourceExcludes(source, "cloneToOrig", "isVirtualClone", "MinStExact", "auxiliaryNodeId");
	}

	@Test
	public void sameHopDifferentContextCannotCreateAlternateInputEdgeUniverse() throws Exception {
		PlacementAnalysis source = analysis("B-17");
		PlacementAnalysis trapped = CampaignBPlacementAnalysisFixtureBridge.sameHopContextTrap(source);
		List<?> actual = list(trapped, "compiledInputEdgesInCanonicalOrder");
		Set<String> expected = trapped.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.DOMINATES)
			.filter(constraint -> "data-input".equals(constraint.evidence()))
			.filter(constraint -> trapped.hop(constraint.left())
				.map(hop -> hop.getDataType() != null && hop.getDataType().isMatrix()).orElse(false))
			.map(constraint -> edgeSignature(constraint.left(), constraint.right(), constraint.inputPosition()))
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> observed = actual.stream().map(edge -> {
			try {
				return edgeSignature((CompiledHopKey)call(edge, "producer"),
					(CompiledHopKey)call(edge, "consumer"),
					((Number)call(edge, "inputPosition")).intValue());
			}
			catch(Exception ex) {
				throw new AssertionError("NEUTRAL_EDGE_CONTEXT_TRAP_ACCESS_FAILED", ex);
			}
		}).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Assert.assertEquals("NEUTRAL_EDGE_CONTEXT_TRAP_ALTERNATE_UNIVERSE", expected, observed);
		Assert.assertEquals("NEUTRAL_EDGE_CONTEXT_TRAP_DUPLICATE_OR_MISSING", expected.size(), actual.size());
		Method require = PlacementAnalysis.class.getMethod("requireExactCompiledInputEdge",
			CompiledHopKey.class, CompiledHopKey.class, int.class);
		for(var constraint : trapped.graph().constraints())
			if(constraint.kind() == ConstraintKind.DOMINATES && "data-input".equals(constraint.evidence())
				&& trapped.hop(constraint.left()).map(hop -> hop.getDataType() != null
					&& hop.getDataType().isMatrix()).orElse(false))
				Assert.assertNotNull("NEUTRAL_EDGE_CONTEXT_TRAP_EXACT_LOOKUP",
					require.invoke(trapped, constraint.left(), constraint.right(), constraint.inputPosition()));
	}

	private static String edgeSignature(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
		return producer.normalizedSignature() + "->" + consumer.normalizedSignature() + '@' + inputPosition;
	}

	private static Class<?> nestedPlacementAnalysisClass(String simpleName, String marker) throws Exception {
		try {
			return Class.forName(PlacementAnalysis.class.getName() + "$" + simpleName);
		}
		catch(ClassNotFoundException ex) {
			throw new AssertionError(marker + "|" + simpleName, ex);
		}
	}

	private static boolean edgeMatches(Object edge, CompiledHopKey producer,
		List<CompiledHopKey> consumers, int inputPosition) {
		try {
			if(call(edge, "producer") != producer)
				return false;
			Object consumerKey = call(edge, "consumer");
			boolean consumerMatches = false;
			for(CompiledHopKey consumer : consumers)
				consumerMatches |= consumerKey == consumer;
			return consumerMatches && ((Number)call(edge, "inputPosition")).intValue() == inputPosition;
		}
		catch(Exception ex) {
			throw new AssertionError("NEUTRAL_EDGE_FACT_ACCESS_FAILED", ex);
		}
	}

	private static void assertAnalysisRejectsCorruptedInputEdges(PlacementAnalysis owner,
		Class<?> edgeFact, List<?> canonicalEdges, Object validEdge) throws Exception {
		assertFreshExactKeyInputEdgesAccepted(owner, edgeFact, canonicalEdges);
		if(canonicalEdges.size() > 1)
			assertInputEdgeAnalysisConstructionRejected(owner, edgeFact, reversed(canonicalEdges),
				"NEUTRAL_EDGE_FACT_REORDERED_ACCEPTED");
		assertInputEdgeAnalysisConstructionRejected(owner, edgeFact, duplicated(canonicalEdges),
			"NEUTRAL_EDGE_FACT_DUPLICATE_ACCEPTED");
		int canonicalPosition = ((Number)call(validEdge, "inputPosition")).intValue();
		Assert.assertEquals("NEUTRAL_EDGE_FACT_PERSISTENT_READ_POSITION", 1, canonicalPosition);
		assertInputEdgeAnalysisConstructionRejected(owner, edgeFact,
			replaceFirstRelevantInputEdge(edgeFact, canonicalEdges, validEdge, true, false, canonicalPosition),
			"NEUTRAL_EDGE_FACT_FOREIGN_PRODUCER_KEY_ACCEPTED");
		assertInputEdgeAnalysisConstructionRejected(owner, edgeFact,
			replaceFirstRelevantInputEdge(edgeFact, canonicalEdges, validEdge, false, true, canonicalPosition),
			"NEUTRAL_EDGE_FACT_FOREIGN_CONSUMER_KEY_ACCEPTED");
		assertInputEdgeAnalysisConstructionRejected(owner, edgeFact,
			replaceFirstRelevantInputEdge(edgeFact, canonicalEdges, validEdge, false, false, 0),
			"NEUTRAL_EDGE_FACT_WRONG_INPUT_POSITION_ACCEPTED");
		assertInputEdgeAnalysisConstructionRejected(owner, edgeFact,
			foreignValueEqualKeyInputEdges(edgeFact, canonicalEdges),
			"NEUTRAL_EDGE_FACT_FOREIGN_VALUE_EQUAL_ACCEPTED");
		List<Object> swapped = new ArrayList<>(canonicalEdges);
		if(swapped.size() > 1) {
			Collections.swap(swapped, 0, 1);
			assertInputEdgeAnalysisConstructionRejected(owner, edgeFact, List.copyOf(swapped),
				"NEUTRAL_EDGE_FACT_SWAPPED_ACCEPTED");
		}
		Assert.assertNotNull("NEUTRAL_EDGE_FACT_VALID_EDGE_UNUSED", validEdge);
	}

	private static void assertFreshExactKeyInputEdgesAccepted(PlacementAnalysis owner,
		Class<?> edgeFact, List<?> canonicalEdges) throws Exception {
		List<?> freshEdges = freshExactKeyInputEdges(edgeFact, canonicalEdges);
		Constructor<?> constructor = exactInputEdgeOwnerConstructor(edgeFact);
		constructor.setAccessible(true);
		PlacementAnalysis reconstructed;
		try {
			reconstructed = (PlacementAnalysis)constructor.newInstance(
				exactInputEdgeOwnerArguments(owner, freshEdges));
		}
		catch(InvocationTargetException ex) {
			throw new AssertionError("NEUTRAL_EDGE_FACT_FRESH_EXACT_KEYS_REJECTED|" + ex.getCause(),
				ex.getCause());
		}

		List<?> actualEdges = list(reconstructed, "compiledInputEdgesInCanonicalOrder");
		assertImmutable(actualEdges, "NEUTRAL_EDGE_FACT_RECONSTRUCTED_LIST_MUTABLE");
		Assert.assertEquals("NEUTRAL_EDGE_FACT_RECONSTRUCTED_SIZE", canonicalEdges.size(), actualEdges.size());
		Method require = PlacementAnalysis.class.getMethod("requireExactCompiledInputEdge",
			CompiledHopKey.class, CompiledHopKey.class, int.class);
		for(int i = 0; i < canonicalEdges.size(); i++) {
			Object canonical = canonicalEdges.get(i);
			Object fresh = freshEdges.get(i);
			Object actual = actualEdges.get(i);
			Assert.assertNotSame("NEUTRAL_EDGE_FACT_FRESH_CARRIER_REQUIRED|" + i, canonical, fresh);
			assertExactInputEdgeSemantics(canonical, fresh, "FRESH|" + i);
			assertExactInputEdgeSemantics(canonical, actual, "RECONSTRUCTED|" + i);
			Assert.assertSame("NEUTRAL_EDGE_FACT_RECONSTRUCTED_LOOKUP|" + i, actual,
				require.invoke(reconstructed, call(actual, "producer"), call(actual, "consumer"),
					((Number)call(actual, "inputPosition")).intValue()));
		}
	}

	private static void assertExactInputEdgeSemantics(Object expected, Object actual, String marker)
		throws Exception {
		Assert.assertSame("NEUTRAL_EDGE_FACT_PRODUCER_SEMANTICS|" + marker,
			call(expected, "producer"), call(actual, "producer"));
		Assert.assertSame("NEUTRAL_EDGE_FACT_CONSUMER_SEMANTICS|" + marker,
			call(expected, "consumer"), call(actual, "consumer"));
		Assert.assertEquals("NEUTRAL_EDGE_FACT_POSITION_SEMANTICS|" + marker,
			call(expected, "inputPosition"), call(actual, "inputPosition"));
	}

	private static List<?> freshExactKeyInputEdges(Class<?> edgeFact, List<?> canonicalEdges) throws Exception {
		Constructor<?> constructor = typedConstructor(edgeFact);
		List<Object> copied = new ArrayList<>();
		for(Object edge : canonicalEdges)
			copied.add(constructor.newInstance(call(edge, "producer"), call(edge, "consumer"),
				((Number)call(edge, "inputPosition")).intValue()));
		return List.copyOf(copied);
	}

	private static List<?> replaceFirstRelevantInputEdge(Class<?> edgeFact, List<?> canonicalEdges,
		Object relevantEdge, boolean copyProducer, boolean copyConsumer, int inputPosition) throws Exception {
		CompiledHopKey producer = (CompiledHopKey)call(relevantEdge, "producer");
		CompiledHopKey consumer = (CompiledHopKey)call(relevantEdge, "consumer");
		int canonicalPosition = ((Number)call(relevantEdge, "inputPosition")).intValue();
		List<Object> replacement = new ArrayList<>(canonicalEdges);
		for(int i = 0; i < canonicalEdges.size(); i++) {
			Object candidate = canonicalEdges.get(i);
			if(call(candidate, "producer") == producer && call(candidate, "consumer") == consumer
				&& ((Number)call(candidate, "inputPosition")).intValue() == canonicalPosition) {
				Constructor<?> constructor = typedConstructor(edgeFact);
				replacement.set(i, constructor.newInstance(copyProducer ? equalCopy(producer) : producer,
					copyConsumer ? equalCopy(consumer) : consumer, inputPosition));
				return List.copyOf(replacement);
			}
		}
		throw new AssertionError("NEUTRAL_EDGE_FACT_RELEVANT_EDGE_MISSING");
	}

	private static List<?> foreignValueEqualKeyInputEdges(Class<?> edgeFact, List<?> canonicalEdges)
		throws Exception {
		Constructor<?> constructor = typedConstructor(edgeFact);
		List<Object> foreign = new ArrayList<>();
		for(Object edge : canonicalEdges)
			foreign.add(constructor.newInstance(equalCopy((CompiledHopKey)call(edge, "producer")),
				equalCopy((CompiledHopKey)call(edge, "consumer")),
				((Number)call(edge, "inputPosition")).intValue()));
		return List.copyOf(foreign);
	}

	private static void assertInputEdgeAnalysisConstructionRejected(PlacementAnalysis owner,
		Class<?> edgeFact, List<?> edgeReplacement, String marker) throws Exception {
		Constructor<?> constructor = exactInputEdgeOwnerConstructor(edgeFact);
		constructor.setAccessible(true);
		try {
			constructor.newInstance(exactInputEdgeOwnerArguments(owner, edgeReplacement));
			Assert.fail(marker);
		}
		catch(InvocationTargetException ex) {
			Assert.assertTrue(marker + "|owner_reason=" + ex.getCause(),
				ex.getCause() instanceof IllegalArgumentException || ex.getCause() instanceof NullPointerException);
		}
	}

	private static Object[] exactInputEdgeOwnerArguments(PlacementAnalysis owner, List<?> edgeReplacement)
		throws Exception {
		return new Object[] {
			owner.graph(),
			owner.occurrences(),
			owner.topLevelStatementBlocks(),
			privateField(owner, "programOwner"),
			privateField(owner, "shapeFacts"),
			owner.analysisFingerprint(),
			owner.heuristicPolicyFacts(),
			owner.candidateRuleDomain().orderedRuleKeys(),
			owner.candidateRuleFacts().orderedFacts(),
			owner.candidateRuleDomain().orderedConsumerKeys(),
			owner.candidateConsumerProfileFacts().orderedFacts(),
			owner.detachedConsumerProfileFacts().orderedFacts(),
			edgeReplacement
		};
	}

	private static Constructor<?> exactInputEdgeOwnerConstructor(Class<?> edgeFact) {
		return Arrays.stream(PlacementAnalysis.class.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == 13)
			.filter(candidate -> candidate.getParameterTypes()[12] == List.class)
			.filter(candidate -> genericListElementIs(candidate.getGenericParameterTypes()[12], edgeFact))
			.findFirst().orElseThrow(() -> new AssertionError(
				"NEUTRAL_EDGE_FACT_13_ARG_OWNER_CONSTRUCTOR_MISSING|" + edgeFact.getName()));
	}

	private static boolean genericListElementIs(Type generic, Class<?> elementType) {
		if(!(generic instanceof ParameterizedType))
			return false;
		Type[] arguments = ((ParameterizedType)generic).getActualTypeArguments();
		return arguments.length == 1 && (arguments[0].getTypeName().equals(elementType.getName())
			|| arguments[0].getTypeName().equals(elementType.getName().replace('$', '.')));
	}

	private static Object privateField(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Constructor<?> typedConstructor(Class<?> type) {
		Constructor<?> constructor = soleNonPublicConstructor(type);
		constructor.setAccessible(true);
		return constructor;
	}

	private static void assertIllegalArgument(Constructor<?> constructor, Object... arguments)
		throws Exception {
		try {
			constructor.newInstance(arguments);
			Assert.fail("MINST_TYPED_INVALID_CONSTRUCTION_ACCEPTED|" + constructor);
		}
		catch(InvocationTargetException ex) {
			Assert.assertTrue("MINST_TYPED_INVALID_CONSTRUCTION_REASON|" + ex.getCause(),
				ex.getCause() instanceof IllegalArgumentException || ex.getCause() instanceof NullPointerException);
		}
	}

	private static void assertLookupRejects(Method lookup, Object owner, Object... arguments)
		throws Exception {
		try {
			lookup.invoke(owner, arguments);
			Assert.fail("MINST_TYPED_FOREIGN_LOOKUP_ACCEPTED|" + lookup);
		}
		catch(InvocationTargetException ex) {
			Assert.assertTrue("MINST_TYPED_FOREIGN_LOOKUP_REASON|" + ex.getCause(),
				ex.getCause() instanceof IllegalArgumentException);
		}
	}


	private static void assertSourceExcludes(Path source, String... forbiddenTokens) throws Exception {
		String text = Files.readString(source);
		for(String token : forbiddenTokens)
			Assert.assertFalse("NEUTRAL_BOUNDARY_FORBIDDEN_TOKEN|" + source.getFileName() + "|" + token,
				text.contains(token));
	}


	private static void assertGenericListReturn(Method method, Class<?> elementType) {
		Assert.assertEquals("NEUTRAL_EDGE_FACT_LIST_RAW_TYPE|" + method, List.class, method.getReturnType());
		Type generic = method.getGenericReturnType();
		Assert.assertTrue("NEUTRAL_EDGE_FACT_LIST_PARAMETERIZED|" + method, generic instanceof ParameterizedType);
		Type[] arguments = ((ParameterizedType)generic).getActualTypeArguments();
		Assert.assertArrayEquals("NEUTRAL_EDGE_FACT_LIST_ELEMENT|" + method,
			new Type[] {elementType}, arguments);
	}

	private static CompiledHopKey equalCopy(CompiledHopKey key) {
		CompiledHopKey copy = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(), key.emittedHopInstance(),
			key.canonicalSourceOrigin());
		Assert.assertEquals(key, copy);
		Assert.assertNotSame(key, copy);
		return copy;
	}

	private static CompiledHopKey persistentReadKey(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).map(hop -> "S".equals(hop.getName())
				&& hop.getOpString().startsWith("PRead")).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("MINST_TYPED_PERSISTENT_READ_MISSING"));
	}

	private static List<CompiledHopKey> persistentReadConsumers(PlacementAnalysis analysis,
		CompiledHopKey producer) {
		Hop producerHop = analysis.hop(producer).orElseThrow();
		List<CompiledHopKey> result = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).map(hop -> List.of("Y1", "Y2").contains(hop.getName())
				&& hop.getInput().size() > 1 && hop.getInput().get(1) == producerHop).orElse(false))
			.toList();
		Assert.assertEquals("MINST_TYPED_PERSISTENT_CONSUMER_COUNT", 2, result.size());
		return result;
	}

	private static void assertTypedCarrier(Class<?> type, String[] accessors, Class<?>[] types)
		throws Exception {
		Assert.assertEquals("MINST_TYPED_CARRIER_ARITY|" + type.getName(), accessors.length, types.length);
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		Assert.assertEquals("MINST_TYPED_CARRIER_CONSTRUCTOR_COUNT|" + type.getName(), 1,
			constructors.length);
		Assert.assertFalse("MINST_TYPED_CARRIER_PUBLIC_LITERAL_CONSTRUCTOR|" + type.getName(),
			Modifier.isPublic(constructors[0].getModifiers()));
		Assert.assertArrayEquals("MINST_TYPED_CARRIER_CONSTRUCTOR_TYPES|" + type.getName(), types,
			constructors[0].getParameterTypes());
		for(int i = 0; i < accessors.length; i++)
			Assert.assertEquals("MINST_TYPED_CARRIER_ACCESSOR_TYPE|" + type.getName() + '|' + accessors[i],
				types[i], type.getMethod(accessors[i]).getReturnType());
		for(Method method : type.getMethods())
			Assert.assertFalse("MINST_TYPED_CARRIER_PUBLIC_LITERAL_FACTORY|" + method,
				Modifier.isStatic(method.getModifiers()) && type.equals(method.getReturnType()));
	}

	@Test
	public void persistentReadFixtureHasTwoFederatedConsumerOccurrences() throws Exception {
		assertPersistentReadTwoConsumerFixture(persistentReadAnalysis());
	}

	@Test
	public void exactFactsRejectCorruptionBeforeAnySelectionRepair() throws Exception {
		Class<?> factsType = boundary(FACTS);
		Class<?> producerType = boundary(PRODUCER);
		assertConstructionSurface(factsType, producerType);

		PlacementAnalysis owner = persistentReadAnalysis();
		assertPersistentReadTwoConsumerFixture(owner);
		List<CompiledHopKey> scope = owner.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Object facts = derive(producerType, owner, scope);
		Assert.assertSame("MINST_FACT_OWNER_IDENTITY", owner, call(facts, "analysis"));
		Assert.assertEquals("MINST_FACT_FINGERPRINT", owner.analysisFingerprint(),
			call(facts, "analysisFingerprint"));
		List<?> actualScope = list(facts, "orderedScope");
		Assert.assertEquals(scope.size(), actualScope.size());
		for(int i = 0; i < scope.size(); i++)
			Assert.assertSame("MINST_FACT_SCOPE_KEY_IDENTITY|index=" + i, scope.get(i), actualScope.get(i));
		assertImmutable(actualScope, "MINST_FACT_SCOPE_MUTABLE");

		Assert.assertEquals("MINST_SOURCE_ID", SOURCE, ((Number)call(facts, "sourceNodeId")).longValue());
		Assert.assertEquals("MINST_SINK_ID", SINK, ((Number)call(facts, "sinkNodeId")).longValue());
		List<?> decisions = list(facts, "decisionFactsInScopeOrder");
		List<?> edges = list(facts, "directedEdgesInDerivationOrder");
		List<?> groups = list(facts, "auxiliaryGroupsInCanonicalOrder");
		List<?> obligations = list(facts, "obligationFactsInCanonicalOrder");
		for(List<?> values : List.of(decisions, edges, groups, obligations))
			assertImmutable(values, "MINST_FACT_TOP_LEVEL_LIST_MUTABLE");
		assertNodeAndEdgeFacts(scope, decisions, groups, edges);
		int targetGroup = assertOrGroups(owner, decisions, groups, edges);
		assertNestedCarrierSurfaces(decisions, edges, groups, obligations);
		assertTypedReasons(factsType);

		Constructor<?> carrier = soleNonPublicConstructor(factsType);
		assertCarrierSignature(carrier);
		Object[] valid = new Object[] {owner, owner.analysisFingerprint(), actualScope, decisions,
			edges, groups, obligations, call(facts, "derivationFingerprint")};
		assertRejected(carrier, replace(valid, 2, reversed(actualScope)), "SCOPE_REORDERED");
		assertRejected(carrier, replace(valid, 2, duplicated(actualScope)), "SCOPE_DUPLICATE");
		assertRejected(carrier, replace(valid, 2, equalCopiedScope(actualScope)), "SCOPE_FOREIGN");
		PlacementAnalysis foreign = analysis("B-21");
		assertRejected(carrier, replace(valid, 0, foreign), "FOREIGN_OWNER");
		assertRejected(carrier, replace(valid, 4, mutateEdgeCapacity(edges)), "CAPACITY_SUM_MISMATCH");
		assertRejected(carrier, replace(valid, 7,
			String.valueOf(valid[7]) + "0"), "DERIVATION_FINGERPRINT_MISMATCH");
		if(!groups.isEmpty()) {
			assertRejected(carrier, replace(valid, 5, mutateGroupList(groups, targetGroup, "priceBits")),
				"OR_GROUP_PRICE_MISMATCH");
			assertRejected(carrier, replace(valid, 5, mutateGroupList(groups, targetGroup, "direction")),
				"OR_GROUP_DIRECTION_MISMATCH");
			assertRejected(carrier, replace(valid, 5,
				mutateGroupList(groups, targetGroup, "endpointsInCanonicalOrder")),
				"OR_GROUP_ENDPOINT_MISMATCH");
		}
		assertB22RepairLegalityAndCorruption(producerType, carrier);
	}

	private static void assertConstructionSurface(Class<?> facts, Class<?> producer) throws Exception {
		Assert.assertTrue(Modifier.isFinal(facts.getModifiers()));
		for(Constructor<?> constructor : facts.getDeclaredConstructors())
			Assert.assertFalse("MINST_FACT_PUBLIC_CANONICAL_CONSTRUCTOR", Modifier.isPublic(constructor.getModifiers()));
		Method derive = producer.getMethod("derive", PlacementAnalysis.class, List.class);
		Assert.assertTrue(Modifier.isStatic(derive.getModifiers()));
		Assert.assertEquals(facts, derive.getReturnType());
		for(Method method : facts.getMethods())
			Assert.assertFalse("MINST_FACT_PUBLIC_LITERAL_FACTORY|" + method,
				Modifier.isStatic(method.getModifiers()) && facts.equals(method.getReturnType()));
	}

	private static void assertNodeAndEdgeFacts(List<CompiledHopKey> scope, List<?> decisions,
		List<?> groups, List<?> edges) throws Exception {
		Assert.assertEquals("MINST_DECISION_SCOPE_CARDINALITY", scope.size(), decisions.size());
		Set<Long> nodeIds = new HashSet<>(List.of(SOURCE, SINK));
		for(int i = 0; i < decisions.size(); i++) {
			Object decision = decisions.get(i);
			Assert.assertSame(scope.get(i), call(decision, "key"));
			for(String accessor : List.of("computeNodeId", "placementNodeId")) {
				long id = ((Number)call(decision, accessor)).longValue();
				Assert.assertTrue("MINST_DECISION_ID_RESERVED|" + id, id >= 0);
				Assert.assertTrue("MINST_DECISION_ID_COLLISION|" + id, nodeIds.add(id));
			}
			List<?> legal = list(decision, "legalStatesInCanonicalOrder");
			Assert.assertFalse("MINST_EMPTY_LEGAL_STATE", legal.isEmpty());
			Assert.assertEquals("MINST_DUPLICATE_LEGAL_STATE", legal.size(), new HashSet<>(legal).size());
			assertImmutable(legal, "MINST_LEGAL_STATES_MUTABLE");
		}
		for(Object group : groups) {
			long id = ((Number)call(group, "auxiliaryNodeId")).longValue();
			Assert.assertTrue("MINST_AUX_ID_DOMAIN|" + id, id < SINK);
			Assert.assertTrue("MINST_AUX_ID_COLLISION|" + id, nodeIds.add(id));
		}
		Set<String> directed = new HashSet<>();
		for(Object edge : edges) {
			long from = ((Number)call(edge, "fromNodeId")).longValue();
			long to = ((Number)call(edge, "toNodeId")).longValue();
			Assert.assertTrue("MINST_EDGE_FROM_UNKNOWN|" + from, nodeIds.contains(from));
			Assert.assertTrue("MINST_EDGE_TO_UNKNOWN|" + to, nodeIds.contains(to));
			Assert.assertTrue("MINST_EDGE_DUPLICATE|" + from + "->" + to, directed.add(from + "->" + to));
			long capacityBits = ((Number)call(edge, "capacityBits")).longValue();
			assertCanonicalCost(capacityBits);
			double sum = 0.0;
			for(Object contribution : list(edge, "contributionsInDerivationOrder")) {
				long bits = ((Number)call(contribution, "costBits")).longValue();
				assertCanonicalCost(bits);
				sum += Double.longBitsToDouble(bits);
			}
			Assert.assertEquals("MINST_CAPACITY_CONTRIBUTION_SUM|" + from + "->" + to,
				capacityBits, Double.doubleToRawLongBits(sum));
			assertImmutable(list(edge, "contributionsInDerivationOrder"),
				"MINST_EDGE_CONTRIBUTIONS_MUTABLE");
		}
	}

	private static int assertOrGroups(PlacementAnalysis owner, List<?> decisions, List<?> groups,
		List<?> edges) throws Exception {
		Set<Long> computeNodes = new HashSet<>();
		Set<Long> placementNodes = new HashSet<>();
		for(Object decision : decisions) {
			computeNodes.add(((Number)call(decision, "computeNodeId")).longValue());
			placementNodes.add(((Number)call(decision, "placementNodeId")).longValue());
		}
		boolean hasMultiEndpointGroup = false;
		CompiledHopKey persistentReadKey = owner.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> owner.hop(key).map(hop -> "S".equals(hop.getName())
				&& hop.getOpString().startsWith("PRead")).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("MINST_OR_PREAD_KEY_MISSING"));
		Set<Long> expectedConsumerNodes = new HashSet<>();
		for(Object decision : decisions) {
			CompiledHopKey key = (CompiledHopKey)call(decision, "key");
			if(owner.hop(key).map(hop -> List.of("Y1", "Y2").contains(hop.getName()) && hop.getInput().size() > 1
				&& hop.getInput().get(1) == owner.hop(persistentReadKey).orElse(null)).orElse(false))
				expectedConsumerNodes.add(((Number)call(decision, "computeNodeId")).longValue());
		}
		int boundFanoutGroups = 0;
		int boundFanoutIndex = -1;
		long persistentPlacementNode = -1;
		Set<Long> exactConsumerNodes = Set.of();
		for(Object group : groups) {
			long aux = ((Number)call(group, "auxiliaryNodeId")).longValue();
			String direction = String.valueOf(call(group, "direction"));
			long producerP = ((Number)call(group, "producerPlacementNodeId")).longValue();
			Assert.assertTrue("MINST_OR_PRODUCER_P_UNKNOWN|" + producerP, placementNodes.contains(producerP));
			long priceBits = ((Number)call(group, "priceBits")).longValue();
			List<?> endpoints = list(group, "endpointsInCanonicalOrder");
			Assert.assertFalse("MINST_OR_EMPTY_ENDPOINTS", endpoints.isEmpty());
			assertImmutable(endpoints, "MINST_OR_ENDPOINTS_MUTABLE");
			hasMultiEndpointGroup |= endpoints.size() >= 2;
			if(direction.equals("UPLOAD") && call(group, "producerKey") == persistentReadKey) {
				boundFanoutGroups++;
				boundFanoutIndex = groups.indexOf(group);
				Object producerDecision = null;
				for(Object decision : decisions)
					if(call(decision, "key") == persistentReadKey) {
						producerDecision = decision;
						break;
					}
				Assert.assertNotNull("MINST_OR_PREAD_DECISION_MISSING", producerDecision);
				persistentPlacementNode = ((Number)call(producerDecision, "placementNodeId")).longValue();
				Assert.assertEquals("MINST_OR_PREAD_PRODUCER_PLACEMENT", persistentPlacementNode, producerP);
				Assert.assertEquals("MINST_OR_PREAD_CONVERSION_TYPE", FType.ROW, call(group, "conversionType"));
				Assert.assertEquals("MINST_OR_PREAD_FANOUT_ENDPOINT_COUNT", 2, endpoints.size());
				Set<Long> observedConsumers = new HashSet<>();
				IdentityHashMap<CompiledHopKey, Long> expectedConsumerKeys = new IdentityHashMap<>();
				for(Object decision : decisions) {
					CompiledHopKey key = (CompiledHopKey)call(decision, "key");
					if(owner.hop(key).map(hop -> List.of("Y1", "Y2").contains(hop.getName()) && hop.getInput().size() > 1
						&& hop.getInput().get(1) == owner.hop(persistentReadKey).orElse(null)).orElse(false))
						expectedConsumerKeys.put(key, ((Number)call(decision, "computeNodeId")).longValue());
				}
				Assert.assertEquals("MINST_OR_EXPECTED_CONSUMER_KEYS", 2, expectedConsumerKeys.size());
				Set<CompiledHopKey> unmatchedKeys = Collections.newSetFromMap(new IdentityHashMap<>());
				unmatchedKeys.addAll(expectedConsumerKeys.keySet());
				for(Object endpoint : endpoints) {
					Assert.assertEquals("MINST_OR_PREAD_FANOUT_INPUT_POSITION", 1,
						((Number)call(endpoint, "inputPosition")).intValue());
					observedConsumers.add(((Number)call(endpoint, "consumerComputeNodeId")).longValue());
					CompiledHopKey consumerKey = (CompiledHopKey)call(endpoint, "consumerKey");
					Assert.assertTrue("MINST_OR_PREAD_CONSUMER_KEY", unmatchedKeys.remove(consumerKey));
					Assert.assertEquals("MINST_OR_PREAD_CONSUMER_COMPUTE_NODE",
						expectedConsumerKeys.get(consumerKey).longValue(), ((Number)call(endpoint, "consumerComputeNodeId")).longValue());
					assertHardContribution(edges, ((Number)call(endpoint, "consumerComputeNodeId")).longValue(),
						aux, consumerKey, persistentReadKey);
				}
				Assert.assertEquals("MINST_OR_PREAD_FANOUT_CONSUMERS", expectedConsumerNodes,
					observedConsumers);
				exactConsumerNodes = observedConsumers;
				Assert.assertTrue("MINST_OR_PREAD_UNMATCHED_CONSUMERS", unmatchedKeys.isEmpty());
				assertPriceContributions(edges, aux, producerP, priceBits, persistentReadKey, expectedConsumerKeys);
			}
			double max = 0.0;
			for(Object endpoint : endpoints) {
				long demandBits = ((Number)call(endpoint, "demandCostBits")).longValue();
				assertCanonicalCost(demandBits);
				max = Math.max(max, Double.longBitsToDouble(demandBits));
				long consumerC = ((Number)call(endpoint, "consumerComputeNodeId")).longValue();
				Assert.assertTrue("MINST_OR_CONSUMER_C_UNKNOWN|" + consumerC, computeNodes.contains(consumerC));
				assertEdgeCapacity(edges, direction.equals("UPLOAD") ? consumerC : aux,
					direction.equals("UPLOAD") ? aux : consumerC, HARD_LEGALITY_BITS,
					"MINST_OR_HARD_EDGE");
			}
			Assert.assertEquals("MINST_OR_MAX_PRICE", priceBits, Double.doubleToRawLongBits(max));
			assertEdgeCapacity(edges, direction.equals("UPLOAD") ? aux : producerP,
				direction.equals("UPLOAD") ? producerP : aux, priceBits, "MINST_OR_PRICE_EDGE");
		}
		Assert.assertTrue("MINST_OR_MULTI_ENDPOINT_FIXTURE_MISSING", hasMultiEndpointGroup);
		Assert.assertEquals("MINST_OR_PREAD_FANOUT_GROUP_COUNT", 1, boundFanoutGroups);
		Assert.assertTrue("MINST_OR_PREAD_BOUND_GROUP_INDEX", boundFanoutIndex >= 0);
		return boundFanoutIndex;
	}

	private static int assertB22PreSolveLegality(PlacementAnalysis owner, List<?> decisions,
		List<?> edges) throws Exception {
		List<Integer> repairOwned = new ArrayList<>();
		for(int i = 0; i < decisions.size(); i++) {
			CompiledHopKey key = (CompiledHopKey)call(decisions.get(i), "key");
			if(owner.hop(key).map(hop -> "Y1".equals(hop.getName())).orElse(false))
				repairOwned.add(i);
		}
		Assert.assertEquals("MINST_B22_REPAIR_OWNED_DECISION", 1, repairOwned.size());
		int index = repairOwned.get(0);
		Object decision = decisions.get(index);
		for(Object state : list(decision, "legalStatesInCanonicalOrder")) {
			PlacementState placement = (PlacementState)state;
			Assert.assertFalse("RAW_STATE_RECEIPT_MISMATCH|B22_Y1_FED_LOUT_MUST_BE_PRE_SOLVE_ILLEGAL",
				placement.execType().name().equals("FED") && placement.output().name().equals("LOUT"));
		}
		assertEdgeCapacity(edges, ((Number)call(decision, "computeNodeId")).longValue(),
			((Number)call(decision, "placementNodeId")).longValue(), HARD_LEGALITY_BITS,
			"MINST_B22_PRE_SOLVE_HARD_LEGALITY_EDGE");
		return index;
	}

	private static void assertB22RepairLegalityAndCorruption(Class<?> producer,
		Constructor<?> carrier) throws Exception {
		PlacementAnalysis owner = analysis("B-22");
		List<CompiledHopKey> scope = owner.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Object facts = derive(producer, owner, scope);
		List<?> decisions = list(facts, "decisionFactsInScopeOrder");
		List<?> edges = list(facts, "directedEdgesInDerivationOrder");
		int repairOwned = assertB22PreSolveLegality(owner, decisions, edges);
		Object[] valid = new Object[] {owner, owner.analysisFingerprint(), list(facts, "orderedScope"),
			decisions, edges, list(facts, "auxiliaryGroupsInCanonicalOrder"),
			list(facts, "obligationFactsInCanonicalOrder"), call(facts, "derivationFingerprint")};
		assertRejected(carrier, replace(valid, 3, mutateDecisionState(decisions, repairOwned)),
			"RAW_STATE_RECEIPT_MISMATCH");
	}

	private static void assertTypedReasons(Class<?> facts) throws Exception {
		Class<?> reason = nested(facts, "ValidationReason");
		Set<String> names = Arrays.stream(reason.getEnumConstants()).map(String::valueOf).collect(java.util.stream.Collectors.toSet());
		for(String required : List.of("FOREIGN_OWNER", "SCOPE_REORDERED", "SCOPE_DUPLICATE",
			"SCOPE_FOREIGN", "CAPACITY_SUM_MISMATCH", "DERIVATION_FINGERPRINT_MISMATCH", "OR_GROUP_ENDPOINT_MISMATCH",
			"OR_GROUP_DIRECTION_MISMATCH", "OR_GROUP_PRICE_MISMATCH", "RAW_STATE_RECEIPT_MISMATCH"))
			Assert.assertTrue("MINST_FACT_VALIDATION_REASON_MISSING|" + required, names.contains(required));
	}

	private static Object derive(Class<?> producer, PlacementAnalysis owner, List<CompiledHopKey> scope)
		throws Exception {
		return producer.getMethod("derive", PlacementAnalysis.class, List.class).invoke(null, owner, scope);
	}

	private static PlacementAnalysis analysis(String fixture) throws Exception {
		return new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
	}

	private static PlacementAnalysis persistentReadAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-two-consumer-read-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"Y1=X+S;", "Y2=X-S;", "write(Y1,\"out-1\",format=\"binary\");",
				"write(Y2,\"out-2\",format=\"binary\");") + "\n";
			DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
				script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			return new NeutralPlacementGraphBuilder().buildAnalysis(program);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static void writePrivateLocalMatrix(String path) throws Exception {
		MatrixBlock block = new MatrixBlock(4, 2, 3.0);
		MatrixCharacteristics characteristics = new MatrixCharacteristics(4, 2, 1024,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, path,
			4, 2, 1024, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			characteristics, FileFormat.BINARY, null, "private");
	}

	private static void assertPersistentReadTwoConsumerFixture(PlacementAnalysis owner) {
		Set<Hop> reads = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> consumers = new HashSet<>();
		Set<String> observed = new HashSet<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : owner.compiledHopOccurrences()) {
			Hop hop = owner.hop(occurrence.key()).orElseThrow();
			observed.add(hop.getName() + "/" + hop.getOpString());
			if("S".equals(hop.getName()) && hop.getOpString().startsWith("PRead"))
				reads.add(hop);
		}
		Assert.assertEquals("MINST_OR_FIXTURE_PERSISTENT_READ_IDENTITY|observed=" + observed, 1, reads.size());
		Hop read = reads.iterator().next();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : owner.compiledHopOccurrences()) {
			Hop hop = owner.hop(occurrence.key()).orElseThrow();
			if(List.of("Y1", "Y2").contains(hop.getName()) && hop.getInput().size() > 1
				&& hop.getInput().get(1) == read)
				consumers.add(hop.getName());
		}
		Assert.assertEquals("MINST_OR_FIXTURE_TWO_CONSUMERS_AT_INPUT_1", Set.of("Y1", "Y2"), consumers);
	}

	private static Class<?> boundary(String name) throws ClassNotFoundException {
		try { return Class.forName(name); }
		catch(ClassNotFoundException ex) {
			throw new AssertionError("MINST_EXACT_FACT_BEHAVIOR_MISSING|class=" + name, ex);
		}
	}

	private static Constructor<?> soleNonPublicConstructor(Class<?> type) {
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		Assert.assertEquals("MINST_FACT_CANONICAL_CONSTRUCTOR_COUNT", 1, constructors.length);
		Assert.assertFalse(Modifier.isPublic(constructors[0].getModifiers()));
		constructors[0].setAccessible(true);
		return constructors[0];
	}

	private static void assertCarrierSignature(Constructor<?> constructor) {
		Class<?>[] types = constructor.getParameterTypes();
		Assert.assertEquals("MINST_FACT_CANONICAL_PARAMETER_COUNT", 8, types.length);
		Assert.assertEquals(PlacementAnalysis.class, types[0]);
		Assert.assertEquals(String.class, types[1]);
		for(int i = 2; i <= 6; i++)
			Assert.assertTrue("MINST_FACT_CANONICAL_LIST_PARAMETER|" + i, List.class.isAssignableFrom(types[i]));
		Assert.assertEquals(String.class, types[7]);
	}

	private static void assertRejected(Constructor<?> constructor, Object[] arguments, String reason)
		throws Exception {
		try {
			constructor.newInstance(arguments);
			Assert.fail("MINST_FACT_CORRUPTION_ACCEPTED|" + reason);
		}
		catch(InvocationTargetException ex) {
			Object actual = call(ex.getCause(), "reason");
			Assert.assertEquals("MINST_FACT_CORRUPTION_REASON", reason, String.valueOf(actual));
		}
	}

	private static List<?> mutateEdgeCapacity(List<?> edges) throws Exception {
		Assert.assertFalse("MINST_EDGE_FIXTURE_EMPTY", edges.isEmpty());
		List<Object> copy = new ArrayList<>(edges);
		Object edge = edges.get(0);
		copy.set(0, rebuildCarrier(edge, EDGE_ACCESSORS, "capacityBits",
			((Number)call(edge, "capacityBits")).longValue() ^ 1L));
		return List.copyOf(copy);
	}

	private static List<?> mutateDecisionState(List<?> decisions, int index) throws Exception {
		List<Object> copy = new ArrayList<>(decisions);
		Object decision = decisions.get(index);
		List<Object> states = new ArrayList<>(list(decision, "legalStatesInCanonicalOrder"));
		states.add(new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false));
		copy.set(index, rebuildCarrier(decision, DECISION_ACCESSORS, "legalStatesInCanonicalOrder",
			List.copyOf(states)));
		return List.copyOf(copy);
	}

	private static List<?> mutateGroupList(List<?> values, int index, String component) throws Exception {
		List<Object> copy = new ArrayList<>(values);
		Assert.assertTrue("MINST_OR_TARGET_GROUP_INDEX", index >= 0 && index < values.size());
		Object value = values.get(index);
		Object replacement;
		if(component.equals("priceBits"))
			replacement = ((Number)call(value, component)).longValue() ^ 1L;
		else if(component.equals("direction")) {
			Object current = call(value, component);
			Object[] constants = current.getClass().getEnumConstants();
			replacement = constants[0].equals(current) ? constants[1] : constants[0];
		}
		else {
			List<?> original = list(value, component);
			replacement = reversed(original);
			Assert.assertNotEquals("MINST_OR_ENDPOINT_ORDER_UNCHANGED", original, replacement);
		}
		copy.set(index, rebuildCarrier(value, GROUP_ACCESSORS, component, replacement));
		return List.copyOf(copy);
	}

	private static Object rebuildCarrier(Object value, String[] accessors, String changed,
		Object replacement) throws Exception {
		assertNestedCarrierSurface(value.getClass());
		Constructor<?> constructor = soleNonPublicConstructor(value.getClass());
		Assert.assertEquals("MINST_FACT_NESTED_CANONICAL_PARAMETER_COUNT|" + value.getClass(),
			accessors.length, constructor.getParameterCount());
		Object[] arguments = new Object[accessors.length];
		for(int i = 0; i < accessors.length; i++)
			arguments[i] = accessors[i].equals(changed) ? replacement : call(value, accessors[i]);
		return constructor.newInstance(arguments);
	}

	private static void assertNestedCarrierSurfaces(List<?> decisions, List<?> edges, List<?> groups,
		List<?> obligations) throws Exception {
		Set<Class<?>> types = new HashSet<>();
		for(Object decision : decisions)
			types.add(decision.getClass());
		for(Object edge : edges) {
			types.add(edge.getClass());
			for(Object contribution : list(edge, "contributionsInDerivationOrder"))
				types.add(contribution.getClass());
		}
		for(Object group : groups) {
			types.add(group.getClass());
			for(Object endpoint : list(group, "endpointsInCanonicalOrder"))
				types.add(endpoint.getClass());
		}
		for(Object obligation : obligations) {
			types.add(obligation.getClass());
			assertImmutable(list(obligation, "endpointsInCanonicalOrder"),
				"MINST_OBLIGATION_ENDPOINTS_MUTABLE");
		}
		for(Class<?> type : types)
			assertNestedCarrierSurface(type);
	}

	private static void assertNestedCarrierSurface(Class<?> type) {
		Assert.assertTrue("MINST_FACT_NESTED_VALUE_MUST_BE_FINAL|" + type, Modifier.isFinal(type.getModifiers()));
		for(Constructor<?> constructor : type.getDeclaredConstructors())
			Assert.assertFalse("MINST_FACT_PUBLIC_NESTED_CANONICAL_CONSTRUCTOR|" + type,
				Modifier.isPublic(constructor.getModifiers()));
		for(Method method : type.getMethods())
			Assert.assertFalse("MINST_FACT_PUBLIC_NESTED_LITERAL_FACTORY|" + method,
				Modifier.isStatic(method.getModifiers()) && type.equals(method.getReturnType()));
	}

	private static Object[] replace(Object[] values, int index, Object replacement) {
		Object[] copy = values.clone();
		copy[index] = replacement;
		return copy;
	}

	private static List<?> reversed(List<?> values) {
		List<Object> copy = new ArrayList<>(values);
		java.util.Collections.reverse(copy);
		return List.copyOf(copy);
	}

	private static List<?> duplicated(List<?> values) {
		List<Object> copy = new ArrayList<>(values);
		copy.add(values.get(0));
		return List.copyOf(copy);
	}

	private static List<?> equalCopiedScope(List<?> values) {
		List<Object> copy = new ArrayList<>(values);
		CompiledHopKey key = (CompiledHopKey)values.get(0);
		CompiledHopKey equalCopy = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(), key.emittedHopInstance(),
			key.canonicalSourceOrigin());
		Assert.assertEquals(key, equalCopy);
		Assert.assertNotSame(key, equalCopy);
		copy.set(0, equalCopy);
		return List.copyOf(copy);
	}

	private static void assertEdgeCapacity(List<?> edges, long from, long to, long expectedBits,
		String marker) throws Exception {
		for(Object edge : edges)
			if(((Number)call(edge, "fromNodeId")).longValue() == from
				&& ((Number)call(edge, "toNodeId")).longValue() == to) {
				Assert.assertEquals(marker + "|capacity", expectedBits,
					((Number)call(edge, "capacityBits")).longValue());
				Assert.assertFalse(marker + "|provenance",
					list(edge, "contributionsInDerivationOrder").isEmpty());
				return;
			}
		Assert.fail(marker + "|missing|" + from + "->" + to);
	}

	private static void assertHardContribution(List<?> edges, long from, long to,
		CompiledHopKey ownerKey, CompiledHopKey peerKey) throws Exception {
		for(Object edge : edges)
			if(((Number)call(edge, "fromNodeId")).longValue() == from
				&& ((Number)call(edge, "toNodeId")).longValue() == to)
				for(Object contribution : list(edge, "contributionsInDerivationOrder")) {
					Object kind = call(contribution, "kind");
					Assert.assertTrue("MINST_HARD_KIND_TYPED", kind.getClass().isEnum());
					Assert.assertTrue("MINST_HARD_KIND_OR", String.valueOf(kind).contains("HARD"));
					Assert.assertSame("MINST_HARD_OWNER_KEY", ownerKey, call(contribution, "ownerKey"));
					Assert.assertSame("MINST_HARD_PEER_KEY", peerKey, call(contribution, "peerKeyOrNull"));
					Assert.assertEquals("MINST_HARD_INPUT_POSITION", 1,
						((Number)call(contribution, "inputPosition")).intValue());
					Assert.assertEquals("MINST_HARD_BITS", HARD_LEGALITY_BITS,
						((Number)call(contribution, "costBits")).longValue());
					Assert.assertFalse("MINST_HARD_PROVENANCE", String.valueOf(call(contribution, "provenance")).isBlank());
					return;
				}
		Assert.fail("MINST_HARD_TYPED_CONTRIBUTION_MISSING|" + from + "->" + to);
	}

	private static void assertPriceContributions(List<?> edges, long from, long to,
		long priceBits, CompiledHopKey producerKey, IdentityHashMap<CompiledHopKey, Long> consumerKeys) throws Exception {
		for(Object edge : edges)
			if(((Number)call(edge, "fromNodeId")).longValue() == from
				&& ((Number)call(edge, "toNodeId")).longValue() == to) {
				double sum = 0.0;
				List<?> contributions = list(edge, "contributionsInDerivationOrder");
				Assert.assertFalse("MINST_PRICE_CONTRIBUTIONS_EMPTY", contributions.isEmpty());
				for(Object contribution : contributions) {
					Object kind = call(contribution, "kind");
					Assert.assertTrue("MINST_PRICE_KIND_TYPED", kind.getClass().isEnum());
					String kindName = String.valueOf(kind);
					Assert.assertTrue("MINST_PRICE_KIND_ROLE", kindName.contains("PRICE") && (kindName.contains("UPLOAD") || kindName.contains("OR")));
					long contributionBits = ((Number)call(contribution, "costBits")).longValue();
					assertCanonicalCost(contributionBits);
					sum += Double.longBitsToDouble(contributionBits);
					Assert.assertSame("MINST_PRICE_OWNER_KEY", producerKey, call(contribution, "ownerKey"));
					Object peer = call(contribution, "peerKeyOrNull");
					Assert.assertTrue("MINST_PRICE_PEER_KEY", consumerKeys.keySet().stream().anyMatch(k -> k == peer));
					Assert.assertEquals("MINST_PRICE_INPUT_POSITION", 1, ((Number)call(contribution, "inputPosition")).intValue());
					Assert.assertFalse("MINST_PRICE_PROVENANCE",
						String.valueOf(call(contribution, "provenance")).isBlank());
				}
				Assert.assertEquals("MINST_PRICE_CONTRIBUTION_SUM", priceBits,
					Double.doubleToRawLongBits(sum));
				return;
			}
		Assert.fail("MINST_PRICE_TYPED_CONTRIBUTION_MISSING|" + from + "->" + to);
	}

	private static void assertCanonicalCost(long bits) {
		double value = Double.longBitsToDouble(bits);
		Assert.assertTrue("MINST_NONFINITE_COST_BITS|" + bits, Double.isFinite(value));
		Assert.assertTrue("MINST_NEGATIVE_COST_BITS|" + bits, value >= 0.0);
		Assert.assertNotEquals("MINST_NEGATIVE_ZERO_COST_BITS", Double.doubleToRawLongBits(-0.0), bits);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values, String marker) {
		Assert.assertThrows(marker, UnsupportedOperationException.class, () -> ((List)values).add(new Object()));
	}

	private static List<?> list(Object owner, String accessor) throws Exception {
		return (List<?>)call(owner, accessor);
	}

	private static Object call(Object owner, String method) throws Exception {
		Class<?> type = owner.getClass();
		while(type != null) {
			try {
				Method declared = type.getDeclaredMethod(method);
				Assert.assertTrue("MINST_FACT_ACCESSOR_MUST_BE_PUBLIC|" + declared,
					Modifier.isPublic(declared.getModifiers()));
				declared.setAccessible(true);
				return declared.invoke(owner);
			}
			catch(NoSuchMethodException ignored) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchMethodException(owner.getClass().getName() + "#" + method);
	}

	private static Class<?> nested(Class<?> owner, String name) {
		return Arrays.stream(owner.getDeclaredClasses()).filter(type -> type.getSimpleName().equals(name))
			.findFirst().orElseThrow(() -> new AssertionError("MINST_FACT_NESTED_TYPE_MISSING|" + name));
	}
}

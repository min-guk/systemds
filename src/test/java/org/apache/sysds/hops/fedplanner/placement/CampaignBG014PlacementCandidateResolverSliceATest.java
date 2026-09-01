/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolution;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionException;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionFailure;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerEdgeEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerNodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.InvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.LegacyCharacterizationRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ProfileEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.TransientForwardEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused A4-A5 lock for the pure captured candidate-rule resolver. */
public class CampaignBG014PlacementCandidateResolverSliceATest {
	private static final List<String> FIXTURES = List.of("B-02", "B-05", "B-07", "B-09", "B-15");

	@Test
	public void capturedAndLegacyProjectionRemainIdenticalAcrossInvocationAdjustments() {
		for(String fixture : FIXTURES) {
			FixtureState state = fixture(fixture);
			for(CandidateRuleFact fact : state.analysis().candidateRuleFacts().orderedFacts()) {
				if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				for(InvocationEvidence invocation : invocationMatrix()) {
					CapturedResolution captured = resolve(state, fact, invocation, List.of(), List.of());
					FType legacy = PlacementCandidateRuleResolver.projectLegacyCharacterization(
						new LegacyCharacterizationRequest(fact.key().orderedInputs(), fact.capability(),
							producerOnly(fact), invocation));
					Assert.assertEquals(fixture + " captured/legacy parity", legacy, captured.logicalFType());
					Assert.assertSame(fixture + " exact fact ownership", fact, captured.fact());
					Assert.assertTrue(fixture + " no consumers means no retained profiles",
						captured.retainedConsumerFacts().isEmpty());
				}
			}
			state.assertUnchanged();
		}
	}

	@Test
	public void consumerIntersectionAndEmptyAllowedSemanticsMatchLegacyProjection() {
		FixtureState constrainedState = fixtureWithConsumers(2, false);
		CandidateRuleFact constrainedRule = firstAvailableRule(constrainedState.analysis());
		List<CandidateConsumerProfileFact> nonEmpty = availableConsumers(constrainedState.analysis(), false);
		assertConsumerParity(constrainedState, constrainedRule, List.of(), false);
		assertConsumerParity(constrainedState, constrainedRule, List.of(nonEmpty.get(0)), true);
		assertConsumerParity(constrainedState, constrainedRule,
			List.of(nonEmpty.get(0), nonEmpty.get(1)), true);
		constrainedState.assertUnchanged();

		FixtureState emptyState = fixtureWithConsumers(0, true);
		CandidateRuleFact emptyRule = firstAvailableRule(emptyState.analysis());
		CandidateConsumerProfileFact empty = availableConsumers(emptyState.analysis(), true).get(0);
		assertConsumerParity(emptyState, emptyRule, List.of(empty), false);
		emptyState.assertUnchanged();
	}

	@Test
	public void transientWriteForwardsToExactReadConsumerWithoutFallback() {
		FixtureState state = fixtureWithConsumers(1, false);
		CandidateRuleFact rule = firstAvailableRule(state.analysis());
		CandidateConsumerProfileFact terminal = availableConsumers(state.analysis(), false).get(0);
		List<CompiledHopKey> carriers = distinctCandidateParents(state.analysis(), rule.key().parentOccurrence(),
			terminal.key().consumerOccurrence());
		Assert.assertTrue("fixture must expose exact write/read carrier identities", carriers.size() >= 2);
		CompiledHopKey write = carriers.get(0);
		CompiledHopKey read = carriers.get(1);

		List<ConsumerEdgeEvidence> edges = List.of(
			new ConsumerEdgeEvidence(0, write, rule.key().parentOccurrence(), 0,
				ConsumerNodeKind.TRANSIENT_WRITE),
			new ConsumerEdgeEvidence(1, terminal.key().consumerOccurrence(), read,
				terminal.key().inputPosition(), ConsumerNodeKind.NORMAL));
		List<TransientForwardEvidence> forwards = List.of(new TransientForwardEvidence(0, write, read));
		CapturedResolution captured = resolve(state, rule, standardInvocation(3), edges, forwards);
		Assert.assertEquals(1, captured.retainedConsumerFacts().size());
		Assert.assertSame(terminal, captured.retainedConsumerFacts().get(0));

		CapturedResolutionException failure = Assert.assertThrows(CapturedResolutionException.class,
			() -> resolve(state, rule, standardInvocation(3), edges, List.of()));
		Assert.assertEquals(CapturedResolutionFailure.AMBIGUOUS_TRANSIENT_FORWARD, failure.failure());
		state.assertUnchanged();
	}

	@Test
	public void originalFunctionCarrierIsCapturedButSyntheticBoundaryKeysAreNot() {
		FunctionOp call = PlacementIdentityKnownEqualityContractTest.functionCall(
			new String[] {"X"}, new String[] {"Y"});
		DMLProgram program = PlacementIdentityKnownEqualityContractTest.program(call);
		String before = PlacementGraphFingerprint.capture(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		List<HopOccurrenceProjection> originals = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == call)
			.filter(occurrence -> !isFunctionBoundary(occurrence)).toList();
		Assert.assertEquals("test-local fixture must retain exactly one original FunctionOp", 1, originals.size());
		Assert.assertTrue("original FunctionOp remains candidate-bearing",
			analysis.candidateRuleDomain().containsExactParent(originals.get(0).key()));

		List<HopOccurrenceProjection> boundaries = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == call).filter(
				CampaignBG014PlacementCandidateResolverSliceATest::isFunctionBoundary).toList();
		Assert.assertEquals("one input and one output must create two synthetic boundaries", 2, boundaries.size());
		Set<NodeKind> kinds = new LinkedHashSet<>();
		for(HopOccurrenceProjection boundary : boundaries) {
			Assert.assertFalse("synthetic function boundary must not become a candidate carrier",
				analysis.candidateRuleDomain().containsExactParent(boundary.key()));
			kinds.add(analysis.graph().node(boundary.key()).orElseThrow().kind());
		}
		Assert.assertEquals(Set.of(NodeKind.FUNCTION_INPUT, NodeKind.FUNCTION_OUTPUT), kinds);
		Assert.assertEquals("function-boundary analysis must be mutation-free", before,
			PlacementGraphFingerprint.capture(program));
	}

	private static boolean isFunctionBoundary(HopOccurrenceProjection occurrence) {
		return occurrence.key().canonicalSourceOrigin().startsWith("function-boundary:");
	}

	@Test
	public void capturedResolutionHasNoSecondPassOrLiveOracleAuthority() throws IOException {
		String resolver = Files.readString(Path.of(
			"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateRuleResolver.java"));
		Assert.assertFalse("resolver must not retain or invoke OracleFacade", resolver.contains("OracleFacade"));
		Assert.assertFalse("resolver must not traverse analysis occurrences", resolver.contains("analysis.occurrences("));
		Assert.assertFalse("resolver must not rebuild a neutral graph", resolver.contains("NeutralPlacementGraphBuilder"));

		String builder = Files.readString(Path.of(
			"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java"));
		int targetBranch = builder.indexOf("if(i == targetPosition)");
		int matrixBranch = builder.indexOf("else if(inputShapeFacts.get(i).dataType().isMatrix())", targetBranch);
		Assert.assertTrue("target position must be pinned before scalar/non-matrix handling",
			targetBranch >= 0 && matrixBranch > targetBranch);
		Assert.assertFalse("consumer profile capture must not read live Hop shape metadata",
			builder.contains("else if(input != null && input.getDataType() != null"));
	}

	private static void assertConsumerParity(FixtureState state, CandidateRuleFact rule,
		List<CandidateConsumerProfileFact> consumers, boolean constrained) {
		List<ConsumerEdgeEvidence> edges = new ArrayList<>();
		Set<FType> intersection = new LinkedHashSet<>(PlacementCandidateRuleResolver.matrixFTypeCandidates());
		for(int i = 0; i < consumers.size(); i++) {
			CandidateConsumerProfileFact consumer = consumers.get(i);
			edges.add(new ConsumerEdgeEvidence(i, consumer.key().consumerOccurrence(),
				rule.key().parentOccurrence(), consumer.key().inputPosition(), ConsumerNodeKind.NORMAL));
			if(!consumer.allowedTargetTypes().isEmpty())
				intersection.retainAll(consumer.allowedTargetTypes());
		}
		Set<FType> expectedConsumers = constrained ? intersection : Set.of();
		InvocationEvidence invocation = standardInvocation(4);
		CapturedResolution captured = resolve(state, rule, invocation, edges, List.of());
		FType legacy = PlacementCandidateRuleResolver.projectLegacyCharacterization(
			new LegacyCharacterizationRequest(rule.key().orderedInputs(), rule.capability(),
				new ProfileEvidence(new LinkedHashSet<>(rule.profile().producerOutputs()), expectedConsumers,
					constrained), invocation));
		Assert.assertEquals("consumer projection parity", legacy, captured.logicalFType());
		Assert.assertEquals(consumers.size(), captured.retainedConsumerFacts().size());
		for(int i = 0; i < consumers.size(); i++)
			Assert.assertSame(consumers.get(i), captured.retainedConsumerFacts().get(i));
	}

	private static CapturedResolution resolve(FixtureState state, CandidateRuleFact fact,
		InvocationEvidence invocation, List<ConsumerEdgeEvidence> edges,
		List<TransientForwardEvidence> forwards) {
		return PlacementCandidateRuleResolver.resolveCaptured(new CapturedResolutionRequest(state.analysis(),
			state.analysis().analysisFingerprint(), fact.key().parentOccurrence(), fact.key().orderedInputs(),
			new CapturedInvocationEvidence(invocation, edges, forwards)));
	}

	private static ProfileEvidence producerOnly(CandidateRuleFact fact) {
		return new ProfileEvidence(new LinkedHashSet<>(fact.profile().producerOutputs()), Set.of(), false);
	}

	private static List<InvocationEvidence> invocationMatrix() {
		return List.of(standardInvocation(0), standardInvocation(1), standardInvocation(8),
			new InvocationEvidence(false, true, true, false, 8, 8, null, false,
				false, false, false, null, 4),
			new InvocationEvidence(false, true, false, true, 1, 16, null, false,
				true, false, false, null, 4),
			new InvocationEvidence(false, true, false, false, 16, 1, null, true,
				false, false, false, null, 4));
	}

	private static InvocationEvidence standardInvocation(int workers) {
		return new InvocationEvidence(false, true, false, false, 16, 16, null, false,
			false, false, false, null, workers);
	}

	private static CandidateRuleFact firstAvailableRule(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE).findFirst()
			.orElseThrow(() -> new AssertionError("No available candidate rule fact"));
	}

	private static List<CandidateConsumerProfileFact> availableConsumers(PlacementAnalysis analysis,
		boolean empty) {
		return analysis.candidateConsumerProfileFacts().orderedFacts().stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedTargetTypes().isEmpty() == empty).toList();
	}

	private static FixtureState fixtureWithConsumers(int minimumNonEmpty, boolean requireEmpty) {
		for(String fixture : FIXTURES) {
			FixtureState state = fixture(fixture);
			if(availableConsumers(state.analysis(), false).size() >= minimumNonEmpty
				&& (!requireEmpty || !availableConsumers(state.analysis(), true).isEmpty()))
				return state;
		}
		throw new AssertionError("Focused fixtures lack required captured consumer evidence");
	}

	private static List<CompiledHopKey> distinctCandidateParents(PlacementAnalysis analysis,
		CompiledHopKey... excluded) {
		Set<CompiledHopKey> exclusions = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(exclusions, excluded);
		List<CompiledHopKey> result = new ArrayList<>();
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
			if(!exclusions.contains(fact.key().parentOccurrence()) && seen.add(fact.key().parentOccurrence()))
				result.add(fact.key().parentOccurrence());
		return result;
	}

	private static FixtureState fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			String before = PlacementGraphFingerprint.capture(program);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			return new FixtureState(id, program, analysis, before,
				List.copyOf(analysis.candidateRuleFacts().orderedFacts()),
				List.copyOf(analysis.candidateConsumerProfileFacts().orderedFacts()));
		}
		catch(Exception ex) {
			throw new AssertionError("Unable to compile focused fixture " + id, ex);
		}
	}

	private record FixtureState(String id, DMLProgram program, PlacementAnalysis analysis,
		String programFingerprint, List<CandidateRuleFact> ruleFacts,
		List<CandidateConsumerProfileFact> consumerFacts) {
		private void assertUnchanged() {
			Assert.assertEquals(id + " program mutation", programFingerprint,
				PlacementGraphFingerprint.capture(program));
			assertIdentityList(id + " candidate facts", ruleFacts,
				analysis.candidateRuleFacts().orderedFacts());
			assertIdentityList(id + " consumer facts", consumerFacts,
				analysis.candidateConsumerProfileFacts().orderedFacts());
		}
	}

	private static void assertIdentityList(String label, List<?> expected, List<?> actual) {
		Assert.assertEquals(label + " size", expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}
}

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

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateLookupFailure;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleLookupException;
import org.apache.sysds.hops.fedplanner.placement.PlacementPrivacyFacts.PrivacyFact;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Completeness and authority tests for compact pre-Cartesian privacy rejection evidence. */
public class CandidatePrivacyInputPruningTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";
	private static final String PAYLOAD_SCRIPT =
		FEDERATED_SOURCE + "B=A+1;C=B*2;print(sum(C));\n";

	@Test
	public void certificatePartitionsTheOriginalCartesianDomainAndPreservesSurvivorOrder()
		throws Exception {
		PlacementAnalysis analysis = analyze(PAYLOAD_SCRIPT);
		List<CandidatePrivacyInputPruning> evidence =
			analysis.candidateRuleDomain().privacyPrunedInputs();
		Assert.assertFalse("protected payload fixture must publish compact input rejection evidence",
			evidence.isEmpty());

		for(CandidatePrivacyInputPruning certificate : evidence) {
			certificate.validate(analysis);
			List<List<CandidateInputState>> original = cartesian(certificate.originalDomains());
			List<List<CandidateInputState>> rejected = original.stream()
				.filter(certificate::rejects).toList();
			List<List<CandidateInputState>> survivors = original.stream()
				.filter(tuple -> !certificate.rejects(tuple)).toList();
			Assert.assertEquals(BigInteger.valueOf(original.size()),
				certificate.generatedTupleCount().add(certificate.rejectedTupleCount()));
			Assert.assertEquals(BigInteger.valueOf(rejected.size()), certificate.rejectedTupleCount());
			Assert.assertEquals(BigInteger.valueOf(survivors.size()), certificate.generatedTupleCount());
			Assert.assertTrue("only a local state at a protected payload slot may be rejected",
				rejected.stream().allMatch(tuple -> certificate.protectedInputs().keySet().stream()
					.anyMatch(position -> !tuple.get(position).present())));
			Assert.assertEquals("surviving tuples must retain the original Cartesian order", survivors,
				analysis.candidateRuleFacts().orderedFactsForParent(certificate.consumer().occurrence())
					.stream().map(fact -> fact.key().orderedInputs()).toList());

			List<CandidateInputState> rejectedTuple = rejected.stream().findFirst().orElseThrow();
			CandidateRuleLookupException failure = Assert.assertThrows(CandidateRuleLookupException.class,
				() -> analysis.candidateRuleFacts().requireExact(
					certificate.consumer().occurrence(), rejectedTuple));
			Assert.assertEquals(CandidateLookupFailure.PRIVACY_EXCLUDED, failure.failure());

			List<CandidateInputState> outsideOriginalDomain = new ArrayList<>(rejectedTuple);
			outsideOriginalDomain.add(CandidateInputState.absentLocal());
			Assert.assertFalse(certificate.rejects(outsideOriginalDomain));
			CandidateRuleLookupException missing = Assert.assertThrows(CandidateRuleLookupException.class,
				() -> analysis.candidateRuleFacts().requireExact(
					certificate.consumer().occurrence(), outsideOriginalDomain));
			Assert.assertEquals("uncertified domain misses must not be relabeled as privacy rejection",
				CandidateLookupFailure.MISSING_FACT, missing.failure());
		}

		PlacementAnalysis foreign = analyze(PAYLOAD_SCRIPT);
		CandidatePrivacyInputPruning foreignEvidence = foreign.candidateRuleDomain()
			.privacyPrunedInputs().stream().findFirst().orElseThrow();
		List<CandidateInputState> foreignRejected = cartesian(foreignEvidence.originalDomains()).stream()
			.filter(foreignEvidence::rejects).findFirst().orElseThrow();
		CandidateRuleLookupException foreignOwner = Assert.assertThrows(CandidateRuleLookupException.class,
			() -> analysis.candidateRuleFacts().requireExact(
				foreignEvidence.consumer().occurrence(), foreignRejected));
		Assert.assertEquals("foreign owners remain outside the candidate domain",
			CandidateLookupFailure.NON_CANDIDATE_PARENT, foreignOwner.failure());
	}

	@Test
	public void certificateOwnsImmutableCopiesOfDomainsAndAuthorities() throws Exception {
		PlacementAnalysis analysis = analyze(PAYLOAD_SCRIPT);
		CandidatePrivacyInputPruning original = analysis.candidateRuleDomain()
			.privacyPrunedInputs().stream().findFirst().orElseThrow();
		List<List<FType>> mutableDomains = new ArrayList<>();
		for(List<CandidateInputState> domain : original.originalDomains())
			mutableDomains.add(new ArrayList<>(domain.stream().map(CandidateInputState::fType).toList()));
		Map<Integer,PrivacyFact> mutableProtected = new LinkedHashMap<>(original.protectedInputs());
		CandidatePrivacyInputPruning copied = new CandidatePrivacyInputPruning(
			original.consumer(), mutableDomains, mutableProtected);
		List<List<CandidateInputState>> frozenDomains = copied.originalDomains();
		Map<Integer,PrivacyFact> frozenProtected = copied.protectedInputs();

		mutableDomains.get(0).clear();
		mutableDomains.clear();
		mutableProtected.clear();
		Assert.assertEquals(original.originalDomains(), frozenDomains);
		Assert.assertEquals(original.protectedInputs(), frozenProtected);
		Assert.assertThrows(UnsupportedOperationException.class, () -> frozenDomains.clear());
		Assert.assertThrows(UnsupportedOperationException.class, () -> frozenDomains.get(0).clear());
		Assert.assertThrows(UnsupportedOperationException.class, () -> frozenProtected.clear());
	}

	@Test
	public void validationRejectsForeignAndStaleOccurrenceValueAuthorities() throws Exception {
		PlacementAnalysis analysis = analyze(PAYLOAD_SCRIPT);
		PlacementAnalysis foreign = analyze(PAYLOAD_SCRIPT);
		CandidatePrivacyInputPruning valid = analysis.candidateRuleDomain()
			.privacyPrunedInputs().stream().findFirst().orElseThrow();
		Assert.assertThrows("same signatures from a fresh analysis are still foreign identity",
			IllegalArgumentException.class, () -> valid.validate(foreign));

		List<List<FType>> domains = rawDomains(valid);
		PrivacyFact consumer = valid.consumer();
		PrivacyFact different = analysis.privacyFactAuthority().orderedFacts().stream()
			.filter(fact -> fact.valueVersion() != consumer.valueVersion()).findFirst().orElseThrow();
		PrivacyFact staleConsumer = new PrivacyFact(consumer.occurrence(), different.valueVersion(),
			consumer.privacy(), consumer.predecessors());
		CandidatePrivacyInputPruning wrongConsumer = new CandidatePrivacyInputPruning(
			staleConsumer, domains, valid.protectedInputs());
		Assert.assertThrows(IllegalArgumentException.class, () -> wrongConsumer.validate(analysis));

		Map<Integer,PrivacyFact> staleSources = new LinkedHashMap<>(valid.protectedInputs());
		var first = staleSources.entrySet().iterator().next();
		PrivacyFact source = first.getValue();
		PrivacyFact staleSource = new PrivacyFact(source.occurrence(), different.valueVersion(),
			source.privacy(), source.predecessors());
		staleSources.put(first.getKey(), staleSource);
		CandidatePrivacyInputPruning wrongSource = new CandidatePrivacyInputPruning(
			consumer, domains, staleSources);
		Assert.assertThrows(IllegalArgumentException.class, () -> wrongSource.validate(analysis));

		List<PlacementIdentity.CompiledHopKey> stalePredecessors = source.predecessors().isEmpty()
			? List.of(consumer.occurrence())
			: source.predecessors().subList(0, source.predecessors().size() - 1);
		PrivacyFact clonedStaleSource = new PrivacyFact(source.occurrence(), source.valueVersion(),
			source.privacy(), stalePredecessors);
		Map<Integer,PrivacyFact> clonedSources = new LinkedHashMap<>(valid.protectedInputs());
		clonedSources.put(first.getKey(), clonedStaleSource);
		CandidatePrivacyInputPruning clonedAuthority = new CandidatePrivacyInputPruning(
			consumer, domains, clonedSources);
		Assert.assertThrows("equal occurrence/value/privacy does not authorize a stale predecessor fact",
			IllegalArgumentException.class, () -> clonedAuthority.validate(analysis));
	}

	@Test
	public void survivorDomainCertificateRequiresIdenticalConsumerAndSourceAuthorities() throws Exception {
		PlacementAnalysis analysis = analyze(PAYLOAD_SCRIPT);
		CandidatePrivacyInputPruning prior = analysis.candidateRuleDomain()
			.privacyPrunedInputs().stream().findFirst().orElseThrow();
		Assert.assertTrue("the same exact revision may retain its original-domain certificate",
			PlacementRelationClosure.samePrivacyMaskAuthority(
				prior, prior.consumer(), prior.protectedInputs()));

		PrivacyFact differentValue = analysis.privacyFactAuthority().orderedFacts().stream()
			.filter(fact -> fact.valueVersion() != prior.consumer().valueVersion())
			.findFirst().orElseThrow();
		PrivacyFact changedConsumer = new PrivacyFact(prior.consumer().occurrence(),
			differentValue.valueVersion(), prior.consumer().privacy(), prior.consumer().predecessors());
		Assert.assertFalse("a new consumer/value revision cannot inherit the older unmasked domain",
			PlacementRelationClosure.samePrivacyMaskAuthority(
				prior, changedConsumer, prior.protectedInputs()));

		Map<Integer,PrivacyFact> changedSources = new LinkedHashMap<>(prior.protectedInputs());
		var first = changedSources.entrySet().iterator().next();
		PrivacyFact source = first.getValue();
		PrivacyFact changedSource = new PrivacyFact(source.occurrence(), differentValue.valueVersion(),
			source.privacy(), source.predecessors());
		changedSources.put(first.getKey(), changedSource);
		Assert.assertEquals("adversary keeps the same protected input position",
			prior.protectedInputs().keySet(), changedSources.keySet());
		Assert.assertFalse("a changed source/value authority cannot import omitted tuples",
			PlacementRelationClosure.samePrivacyMaskAuthority(
				prior, prior.consumer(), changedSources));
	}

	@Test
	public void validationRejectsCertificateThatOmitsOneProtectedPayloadPosition() throws Exception {
		PlacementAnalysis analysis = analyze(FEDERATED_SOURCE + "B=A+1;C=B+B;print(sum(C));\n");
		CandidatePrivacyInputPruning complete = analysis.candidateRuleDomain().privacyPrunedInputs().stream()
			.filter(evidence -> evidence.protectedInputs().size() == 2).findFirst().orElseThrow();
		complete.validate(analysis);
		Map<Integer,PrivacyFact> incomplete = new LinkedHashMap<>(complete.protectedInputs());
		incomplete.remove(incomplete.keySet().stream().reduce((first, second) -> second).orElseThrow());
		CandidatePrivacyInputPruning omitted = new CandidatePrivacyInputPruning(
			complete.consumer(), rawDomains(complete), incomplete);
		Assert.assertThrows("every protected payload slot in this exact domain revision must be certified",
			IllegalArgumentException.class, () -> omitted.validate(analysis));
	}

	@Test
	public void metadataOnlyInputIsNotMaskedAsProtectedPayload() throws Exception {
		PlacementAnalysis analysis = analyze(FEDERATED_SOURCE + "r=nrow(A);print(r);print(sum(A));\n");
		var dimensions = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof org.apache.sysds.hops.UnaryOp unary
				&& unary.getOp() == org.apache.sysds.common.Types.OpOp1.NROW)
			.findFirst().orElseThrow();
		Assert.assertTrue(analysis.isCoordinatorMetadataOnlyInput(
			analysis.compiledInputEdge(dimensions.key(), 0).orElseThrow()));
		Assert.assertTrue("metadata/unknown-access exceptions must stay outside payload masks",
			analysis.candidateRuleDomain().privacyPrunedInputs().stream()
				.noneMatch(evidence -> evidence.consumer().occurrence() == dimensions.key()));
	}

	@Test
	public void auditWritesCompactPruningSidecarWithoutExpandingRejectedTuples() throws Exception {
		Path directory = Files.createTempDirectory("fed-planner-pruning-audit-");
		String priorEnabled = System.getProperty(PlannerCandidateSpaceAudit.PROPERTY);
		String priorDirectory = System.getProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
		try {
			System.setProperty(PlannerCandidateSpaceAudit.PROPERTY, Boolean.TRUE.toString());
			System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, directory.toString());
			PlacementAnalysis analysis = analyze(PAYLOAD_SCRIPT);
			Path pruning;
			Path candidates;
			try(var files = Files.list(directory)) {
				List<Path> outputs = files.toList();
				pruning = outputs.stream().filter(path -> path.getFileName().toString()
					.startsWith("candidate-pruning-")).findFirst().orElseThrow();
				candidates = outputs.stream().filter(path -> path.getFileName().toString()
					.startsWith("candidate-space-")).findFirst().orElseThrow();
			}
			List<JsonNode> pruningRows = Files.readAllLines(pruning).stream().map(line -> {
				try {
					return MAPPER.readTree(line);
				}
				catch(Exception ex) {
					throw new RuntimeException(ex);
				}
			}).toList();
			Assert.assertEquals(analysis.candidateRuleDomain().privacyPrunedInputs().size(),
				pruningRows.size());
			for(JsonNode row : pruningRows) {
				Assert.assertEquals("fedplanner-candidate-pruning-v1", row.path("schema").asText());
				Assert.assertEquals(analysis.analysisFingerprint(), row.path("analysisFingerprint").asText());
				Assert.assertTrue(row.path("originalInputDomains").isArray());
				Assert.assertTrue(row.path("protectedPayloadAuthorities").isObject());
				Assert.assertTrue(new BigInteger(row.path("generatedTuples").asText()).signum() > 0);
				Assert.assertTrue(new BigInteger(row.path("rejectedTuples").asText()).signum() > 0);
				Assert.assertFalse("compact sidecar must not enumerate rejected tuples",
					row.has("rejectedTuple") || row.has("rejectedTuplesExpanded"));
			}
			for(String line : Files.readAllLines(candidates))
				Assert.assertEquals("candidate-space file must remain candidate rows only",
					"fedplanner-candidate-space-v1", MAPPER.readTree(line).path("schema").asText());
		}
		finally {
			if(priorEnabled == null) System.clearProperty(PlannerCandidateSpaceAudit.PROPERTY);
			else System.setProperty(PlannerCandidateSpaceAudit.PROPERTY, priorEnabled);
			if(priorDirectory == null) System.clearProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
			else System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, priorDirectory);
			try(var paths = Files.walk(directory)) {
				paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					}
					catch(Exception ex) {
						throw new RuntimeException(ex);
					}
				});
			}
		}
	}

	private static List<List<FType>> rawDomains(CandidatePrivacyInputPruning evidence) {
		return evidence.originalDomains().stream().map(domain ->
			domain.stream().map(CandidateInputState::fType).toList()).toList();
	}

	private static List<List<CandidateInputState>> cartesian(
		List<List<CandidateInputState>> domains) {
		List<List<CandidateInputState>> tuples = new ArrayList<>();
		enumerate(domains, 0, new ArrayList<>(), tuples);
		return tuples;
	}

	private static void enumerate(List<List<CandidateInputState>> domains, int position,
		List<CandidateInputState> prefix, List<List<CandidateInputState>> tuples) {
		if(position == domains.size()) {
			tuples.add(List.copyOf(prefix));
			return;
		}
		for(CandidateInputState value : domains.get(position)) {
			prefix.add(value);
			enumerate(domains, position + 1, prefix, tuples);
			prefix.remove(prefix.size() - 1);
		}
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		assertFederatedSourcePresent(program);
		return new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(), true, true)
			.buildDetachedAnalysis(program);
	}

	private static void assertFederatedSourcePresent(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean found = false;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			found |= hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			pending.addAll(hop.getInput());
		}
		Assert.assertTrue("fixture requires a federated source", found);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** Regressions for complete cross-block transient placement forwarding. */
public class TransientPlacementAlternativesTest {
	private static final String LM_DIAGNOSTIC_SCRIPT =
		"X=federated(addresses=list(\"localhost:19334/X1\",\"localhost:19335/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));\n"
			+ "y=matrix(1,rows=8,cols=1); p=matrix(1,rows=3,cols=1);\n"
			+ "pred=X%*%p; err=pred-y;\n"
			+ "if(sum(p)>0) { z=pmin(pmax(err,-1),1); } else { z=err; }\n"
			+ "grad=t(X)%*%z; stats=sum(pred);\n"
			+ "print(sum(grad)+stats);\n";
	private static String branchJoinScript(int port, boolean compatible) {
		String second = compatible
			? "z=X+2;"
			: "z=Y+2;";
		return "X=federated(addresses=list(\"localhost:" + port + "/X1\",\"localhost:"
			+ (port + 1) + "/X2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ (compatible ? "" : "Y=federated(addresses=list(\"localhost:" + (port + 2)
				+ "/Y1\",\"localhost:" + (port + 3)
				+ "/Y2\"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n")
			+ "gate=matrix(1,rows=1,cols=1);\n"
			+ "if(sum(gate)>0) { z=X+1; } else { " + second + " }\n"
			+ "print(sum(z));\n";
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void rowPredictionSurvivesBranchIntoDownstreamAggregate() throws Exception {
		DMLProgram program = compile(LM_DIAGNOSTIC_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		assertCandidateBackedFedStatesArePublished(analysis);
		PlacementState row = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);

		List<PlacementAnalysis.HopOccurrenceProjection> predictionReads = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "pred".equals(data.getName()))
			.toList();
		Assert.assertFalse("The diagnostic must read pred after the unrelated branch", predictionReads.isEmpty());
		for(var read : predictionReads) {
			Assert.assertTrue("ROW pred must remain an executable reader alternative",
				analysis.graph().node(read.key()).orElseThrow().legalAlternatives().contains(row));
			assertAllExecutableTransientAlternativesAreProved(analysis, read.key());
		}

		List<PlacementAnalysis.HopOccurrenceProjection> joinedReads = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "z".equals(data.getName()))
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.toList();
		Assert.assertFalse("The diagnostic must contain the branch join for z", joinedReads.isEmpty());
		joinedReads.forEach(read -> assertAllExecutableTransientAlternativesAreProved(analysis, read.key()));

		var stats = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp
				&& occurrence.hop().getInput().stream().anyMatch(input -> input instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD && "pred".equals(data.getName())))
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertTrue("Downstream aggregate candidate must be regenerated from forwarded pred",
			analysis.graph().node(stats.key()).orElseThrow().legalAlternatives().stream()
				.anyMatch(state -> state.execType() == ExecType.FED));
		Assert.assertTrue("Downstream aggregate must retain an AVAILABLE ROW input candidate",
			analysis.candidateRuleFacts().orderedFacts().stream().anyMatch(fact ->
				fact.key().parentOccurrence() == stats.key()
					&& fact.status() == CandidateEvaluationStatus.AVAILABLE
					&& fact.key().orderedInputs().stream().anyMatch(input ->
						input.present() && input.fType() == FType.ROW)
					&& fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
						.anyMatch(realization -> realization.supportClauses().stream()
							.anyMatch(clause -> !clause.inputBindings().isEmpty()))));
	}

	@Test
	public void protectedRowIntermediatesPublishOnlyFederatedTransientContinuations() throws Exception {
		// Matrix multiplication is an authorized aggregate release under the existing policy.
		// Use a non-aggregating row transform to exercise origin-resident transient values.
		DMLProgram program = compile(LM_DIAGNOSTIC_SCRIPT.replace("pred=X%*%p", "pred=X+1")
			.replace("localhost:19334", "localhost:19434")
			.replace("localhost:19335", "localhost:19435"));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		assertCandidateBackedFedStatesArePublished(analysis);
		List<PlacementAnalysis.HopOccurrenceProjection> protectedReads = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD
				&& ("pred".equals(data.getName()) || "z".equals(data.getName())))
			.filter(occurrence -> !analysis.logicalTransientInputsForReader(occurrence.key(), 0).isEmpty())
			.toList();
		Assert.assertFalse("fixture must expose protected transient continuations", protectedReads.isEmpty());
		for(var read : protectedReads) {
			assertAllExecutableTransientAlternativesAreProved(analysis, read.key());
			Assert.assertTrue("protected row-sized values cannot publish a local transient continuation",
				analysis.logicalTransientInputsForReader(read.key(), 0).stream()
					.flatMap(fact -> fact.compatibility().stream())
					.allMatch(edge -> edge.readerRealization().realization().emissionState()
						.placementState().output() == FederatedOutput.FOUT));
		}
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void compatibleAndIncompatibleBranchJoinsUseExactPhysicalMaps() throws Exception {
		PlacementAnalysis compatible = build(branchJoinScript(19534, true), Privacy.PUBLIC);
		CompiledHopKey compatibleRead = joinedRead(compatible, "z");
		Set<CandidateRealizationReference> compatibleReaders = commonReaderRealizations(
			compatible, compatibleRead);
		Assert.assertTrue("same-pool branch definitions must retain their common ROW continuation",
			compatibleReaders.stream().anyMatch(reference -> reference.realization().emissionState()
				.placementState().fType() == FType.ROW));
		assertAllExecutableTransientAlternativesAreProved(compatible, compatibleRead);

		PlacementAnalysis incompatible = build(branchJoinScript(19634, false), Privacy.PUBLIC);
		CompiledHopKey incompatibleRead = joinedRead(incompatible, "z");
		Set<CandidateRealizationReference> incompatibleReaders = commonReaderRealizations(
			incompatible, incompatibleRead);
		for(CandidateRealizationReference reader : incompatibleReaders) {
			CandidateEmissionRealization readerRealization = incompatible.requireExactCandidateRealization(reader);
			if(readerRealization.placementState().output() != FederatedOutput.FOUT)
				continue;
			var readerPool = readerRealization.provenWorkerPool(
				readerRealization.supportClauses().get(0));
			Assert.assertNotNull("a retained federated join requires exact output-pool authority", readerPool);
			for(LogicalTransientInputFact fact : incompatible.logicalTransientInputsForReader(incompatibleRead, 0))
				for(var edge : fact.compatibilityForReader(reader)) {
					CandidateEmissionRealization source = incompatible.requireExactCandidateRealization(
						edge.sourceRealization());
					var sourcePool = source.provenWorkerPool(source.supportClauses().get(0));
					Assert.assertTrue("same FType must not fabricate a common map across different endpoints",
						sourcePool != null && PlacementIdentity.samePhysicalLayout(sourcePool, readerPool));
				}
		}
		assertAllExecutableTransientAlternativesAreProved(incompatible, incompatibleRead);
	}

	@Test
	public void privacyNarrowingPreservesUnrelatedDistributedAlternativeAndRebuildIsStable() throws Exception {
		PlacementAnalysis publicAnalysis = build(branchJoinScript(19734, true), Privacy.PUBLIC);
		CompiledHopKey publicRead = joinedRead(publicAnalysis, "z");
		Set<PlacementState> publicStates = commonReaderRealizations(publicAnalysis, publicRead).stream()
			.map(reference -> reference.realization().emissionState().placementState())
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertTrue("public join must expose a local continuation before privacy narrowing",
			publicStates.stream().anyMatch(state -> state.output() == FederatedOutput.LOUT));
		Assert.assertTrue("public join must also preserve the executable ROW continuation",
			publicStates.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT
				&& state.fType() == FType.ROW));

		DMLProgram protectedProgram = compile(branchJoinScript(19834, true));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			protectedProgram, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis first = new NeutralPlacementGraphBuilder().buildAnalysis(protectedProgram);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder().buildAnalysis(protectedProgram);
		assertCandidateBackedFedStatesArePublished(first);
		assertCandidateBackedFedStatesArePublished(second);
		CompiledHopKey protectedRead = joinedRead(first, "z");
		Set<PlacementState> protectedStates = commonReaderRealizations(first, protectedRead).stream()
			.map(reference -> reference.realization().emissionState().placementState())
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertFalse("privacy narrowing must remove the protected local continuation",
			protectedStates.stream().anyMatch(state -> state.output() == FederatedOutput.LOUT));
		Assert.assertTrue("privacy narrowing must not remove an unrelated legal ROW continuation",
			protectedStates.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT
				&& state.fType() == FType.ROW));
		Assert.assertEquals(candidateSignatures(first), candidateSignatures(second));
		Assert.assertEquals(logicalRelationSignatures(first), logicalRelationSignatures(second));
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void identityLoopBackedgeRestoresAllReachingTransientRelations() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:19934/X1\",\"localhost:19935/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "B=A;i=1;while(i<=2){B=B;i=i+1;}print(sum(B));\n";
		DMLProgram program = compileWithoutRewrite(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis first = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		assertCandidateBackedFedStatesArePublished(first);
		assertCandidateBackedFedStatesArePublished(second);

		var identityRead = first.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "B".equals(data.getName()))
			.filter(occurrence -> first.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.filter(occurrence -> first.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).stream()
				.map(first::hop).flatMap(java.util.Optional::stream)
				.anyMatch(source -> source instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTWRITE
					&& data.getInput().size() == 1 && data.getInput(0) == occurrence.hop()
					&& "B".equals(data.getName())))
			.findFirst().orElseThrow(() -> new AssertionError(
				"fixture must retain an exact TWrite(B <- TRead(B)) identity loop backedge"));

		List<CompiledHopKey> reaching = first.cfgDefinitionSourcesInCanonicalOrder(identityRead.key());
		Assert.assertTrue("identity loop read must retain external seed plus loop backedge", reaching.size() >= 2);
		assertAllExecutableTransientAlternativesAreProved(first, identityRead.key());
		Set<CompiledHopKey> related = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		first.logicalTransientInputsForReader(identityRead.key(), 0)
			.forEach(fact -> related.add(fact.sourceWrite()));
		Assert.assertEquals("final publication must restore every reaching writer after provisional loop seeding",
			reaching.size(), related.size());
		Assert.assertTrue("final publication must include every reaching writer identity",
			reaching.stream().allMatch(source -> related.stream().anyMatch(candidate -> candidate == source)));

		Assert.assertEquals("identity-loop candidate publication must be rebuild-stable",
			candidateSignatures(first), candidateSignatures(second));
		Assert.assertEquals("identity-loop transient relations must be rebuild-stable",
			logicalRelationSignatures(first), logicalRelationSignatures(second));
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void allReachingDurableAndLocalAssignmentSetMatchesIndependentPhysicalOracle() throws Exception {
		PlacementAnalysis analysis = build(branchJoinScript(20034, true), Privacy.PUBLIC);
		CompiledHopKey read = joinedRead(analysis, "z");
		assertDurableAndLocalFullSet(analysis, read, 2);
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void identityLoopDurableAndLocalAssignmentSetMatchesIndependentPhysicalOracle() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:20134/X1\",\"localhost:20135/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "B=A;i=1;while(i<=2){B=B;i=i+1;}print(sum(B));\n";
		DMLProgram program = compileWithoutRewrite(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey read = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "B".equals(data.getName()))
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).stream()
				.map(analysis::hop).flatMap(java.util.Optional::stream)
				.anyMatch(source -> source instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTWRITE
					&& data.getInput().size() == 1 && data.getInput(0) == occurrence.hop()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow(AssertionError::new);
		assertDurableAndLocalFullSet(analysis, read,
			analysis.cfgDefinitionSourcesInCanonicalOrder(read).size());
	}

	private static void assertDurableAndLocalFullSet(PlacementAnalysis analysis,
		CompiledHopKey read, int expectedWriterCount) {
		List<LogicalTransientInputFact> relations = analysis.logicalTransientInputsForReader(read, 0);
		Assert.assertEquals("fixture must retain every intended reaching definition",
			expectedWriterCount, relations.size());

		List<CompiledHopKey> owners = new java.util.ArrayList<>();
		relations.stream().map(LogicalTransientInputFact::sourceWrite).sorted().forEach(owners::add);
		owners.add(read);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains = rawReceiptDomains(analysis, owners);
		domains.put(read, domains.get(read).stream().filter(receipt ->
			receipt.realization().key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE).toList());
		Assert.assertFalse("bounded independent oracle requires a non-native reader subset",
			domains.get(read).isEmpty());
		Set<String> expected = enumerateAssignments(owners, domains,
			selected -> independentlyCompatibleTransient(relations, selected));
		Set<String> actual = enumerateAssignments(owners, domains, selected -> {
			Map<CompiledHopKey,PlacementState> assignment = new IdentityHashMap<>();
			selected.forEach((owner, receipt) -> assignment.put(owner,
				receipt.emission().emissionState().placementState()));
			return CandidateSelections.realizationsCanStillBeCompatible(
				analysis, assignment, selected.values());
		});

		Assert.assertFalse("fixture must retain at least one exact legal transient assignment",
			expected.isEmpty());
		Assert.assertEquals("all-reaching durable/local compatibility changed the complete legal assignment set",
			expected, actual);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> rawReceiptDomains(
		PlacementAnalysis analysis, List<CompiledHopKey> owners) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new LinkedHashMap<>();
		for(CompiledHopKey owner : owners) {
			List<CandidateSelectionReceipt> receipts = analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.flatMap(fact -> fact.allowedEmissionFacts().stream().flatMap(emission ->
					analysis.canonicalCandidateReceipts(fact.key(), emission).stream()))
				.distinct().sorted().toList();
			Assert.assertFalse("full-set fixture owner has no raw candidate receipts: "
				+ owner.normalizedSignature(), receipts.isEmpty());
			result.put(owner, receipts);
		}
		return result;
	}

	private static Set<String> enumerateAssignments(List<CompiledHopKey> owners,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains,
		java.util.function.Predicate<Map<CompiledHopKey,CandidateSelectionReceipt>> legal) {
		Set<String> result = new LinkedHashSet<>();
		enumerateAssignments(owners, domains, 0, new IdentityHashMap<>(), legal, result);
		return result;
	}

	private static void enumerateAssignments(List<CompiledHopKey> owners,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains, int position,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		java.util.function.Predicate<Map<CompiledHopKey,CandidateSelectionReceipt>> legal,
		Set<String> result) {
		if(position == owners.size()) {
			if(legal.test(selected))
				result.add(java.util.stream.IntStream.range(0, owners.size()).mapToObj(index -> {
					CompiledHopKey owner = owners.get(index);
					return Integer.toString(domains.get(owner).indexOf(selected.get(owner)));
				}).reduce((left, right) -> left + "/" + right).orElse(""));
			return;
		}
		CompiledHopKey owner = owners.get(position);
		for(CandidateSelectionReceipt receipt : domains.get(owner)) {
			selected.put(owner, receipt);
			enumerateAssignments(owners, domains, position + 1, selected, legal, result);
			selected.remove(owner);
		}
	}

	private static boolean independentlyCompatibleTransient(List<LogicalTransientInputFact> relations,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		for(LogicalTransientInputFact relation : relations) {
			CandidateSelectionReceipt source = selected.get(relation.sourceWrite());
			CandidateSelectionReceipt reader = selected.get(relation.targetRead());
			if(source == null || reader == null)
				return false;
			PlacementState sourceState = source.emission().emissionState().placementState();
			PlacementState readerState = reader.emission().emissionState().placementState();
			PlacementLayoutKind sourceLayout = source.realization().key().layoutKind();
			PlacementLayoutKind readerLayout = reader.realization().key().layoutKind();
			boolean sourceLocal = sourceLayout == PlacementLayoutKind.LOCAL;
			boolean readerLocal = readerLayout == PlacementLayoutKind.LOCAL;
			if(sourceLocal || readerLocal) {
				if(!sourceLocal || !readerLocal || sourceState.output() != FederatedOutput.LOUT
					|| readerState.output() != FederatedOutput.LOUT)
					return false;
				continue;
			}
			if(sourceLayout != PlacementLayoutKind.DURABLE_MAP
				|| readerLayout != PlacementLayoutKind.DURABLE_MAP
				|| sourceState.output() != FederatedOutput.FOUT || readerState.output() != FederatedOutput.FOUT
				|| sourceState.fType() == null || sourceState.fType() != readerState.fType()
				|| source.provenWorkerPool() == null || reader.provenWorkerPool() == null
				|| !PlacementIdentity.samePhysicalLayout(source.provenWorkerPool(), reader.provenWorkerPool()))
				return false;
		}
		return true;
	}

	private static void assertAllExecutableTransientAlternativesAreProved(
		PlacementAnalysis analysis, CompiledHopKey read) {
		List<CompiledHopKey> reaching = analysis.cfgDefinitionSourcesInCanonicalOrder(read);
		List<LogicalTransientInputFact> facts = analysis.logicalTransientInputsForReader(read, 0);
		Assert.assertEquals("one exact relation is required for every reaching writer",
			reaching.size(), facts.size());
		Set<CompiledHopKey> supplied = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		facts.forEach(fact -> supplied.add(fact.sourceWrite()));
		Assert.assertTrue("logical relations must cover every exact reaching definition",
			reaching.stream().allMatch(source -> supplied.stream().anyMatch(candidate -> candidate == source)));

		Set<CandidateRealizationReference> commonReaders = null;
		for(LogicalTransientInputFact fact : facts) {
			Set<CandidateRealizationReference> readers = new HashSet<>();
			fact.compatibility().forEach(edge -> {
				Assert.assertSame(fact.sourceWrite(), edge.sourceRealization().rule().parentOccurrence());
				Assert.assertSame(read, edge.readerRealization().rule().parentOccurrence());
				assertPublishedRealization(analysis, edge.sourceRealization());
				assertPublishedRealization(analysis, edge.readerRealization());
				readers.add(edge.readerRealization());
			});
			if(commonReaders == null)
				commonReaders = new LinkedHashSet<>(readers);
			else
				commonReaders.retainAll(readers);
		}
		Assert.assertNotNull(commonReaders);
		Assert.assertFalse("a join may retain only reader realizations supported by all writers",
			commonReaders.isEmpty());

		Set<PlacementState> provenStates = new HashSet<>();
		commonReaders.forEach(reference -> provenStates.add(reference.realization()
			.emissionState().placementState()));
		analysis.graph().node(read).orElseThrow().legalAlternatives().stream()
			.filter(TransientPlacementAlternativesTest::isExecutableTransientAlternative)
			.forEach(state -> Assert.assertTrue(
				"every retained executable reader state needs an exact all-definition proof: " + state,
				provenStates.contains(state)));
	}

	private static Set<CandidateRealizationReference> commonReaderRealizations(
		PlacementAnalysis analysis, CompiledHopKey read) {
		Set<CandidateRealizationReference> common = null;
		for(LogicalTransientInputFact fact : analysis.logicalTransientInputsForReader(read, 0)) {
			Set<CandidateRealizationReference> readers = fact.compatibility().stream()
				.map(PlacementAnalysis.TransientPlacementCompatibility::readerRealization)
				.collect(java.util.stream.Collectors.toSet());
			if(common == null)
				common = new LinkedHashSet<>(readers);
			else
				common.retainAll(readers);
		}
		return common == null ? Set.of() : Set.copyOf(common);
	}

	private static CompiledHopKey joinedRead(PlacementAnalysis analysis, String name) {
		return analysis.occurrences().stream().filter(occurrence -> occurrence.hop() instanceof DataOp data
			&& data.getOp() == OpOpData.TRANSIENTREAD && name.equals(data.getName())
			&& analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow(AssertionError::new);
	}

	private static List<String> candidateSignatures(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.map(fact -> fact.key().normalizedSignature() + '|' + fact.status() + "|emissions="
				+ fact.allowedEmissionFacts().stream()
					.map(PlacementAnalysis.CandidateEmissionFact::normalizedSignature).toList()
				+ "|failure=" + fact.failureCode())
			.toList();
	}

	private static List<String> logicalRelationSignatures(PlacementAnalysis analysis) {
		return analysis.logicalTransientInputsInCanonicalOrder().stream().map(fact ->
			fact.sourceWrite().normalizedSignature() + "->" + fact.targetRead().normalizedSignature()
				+ "@" + fact.logicalPosition() + fact.compatibility().stream()
					.map(PlacementAnalysis.TransientPlacementCompatibility::normalizedSignature).toList())
			.toList();
	}

	private static PlacementAnalysis build(String script, Privacy privacy) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		assertCandidateBackedFedStatesArePublished(analysis);
		return analysis;
	}

	private static void assertCandidateBackedFedStatesArePublished(PlacementAnalysis analysis) {
		for(var node : analysis.graph().decisionNodes()) {
			List<PlacementAnalysis.CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == node.key()).toList();
			if(facts.isEmpty())
				continue;
			boolean synthetic = !analysis.isCompiledHopOccurrence(node.key());
			boolean sourceAuthority = !node.anchors().isEmpty()
				&& analysis.hop(node.key()).filter(DataOp.class::isInstance).map(DataOp.class::cast)
					.map(data -> data.getOp() == OpOpData.FEDERATED).orElse(false);
			boolean candidateHasInputs = facts.stream().anyMatch(fact -> !fact.key().orderedInputs().isEmpty());
			for(PlacementState state : node.legalAlternatives()) {
				if(state.execType() != ExecType.FED || synthetic || sourceAuthority
					|| state.output() == FederatedOutput.LOUT && !candidateHasInputs)
					continue;
				boolean published = facts.stream()
					.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
					.flatMap(fact -> fact.allowedEmissionFacts().stream())
					.flatMap(emission -> emission.realizations().stream())
					.anyMatch(realization -> realization.placementState().equals(state));
				boolean explicitOutputMaterialization = state.output() == FederatedOutput.FOUT
					&& analysis.graph().derivedFoutMaterializationActions().stream()
					.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
					.anyMatch(action -> action.producer() == node.key()
						&& action.targetPlacement().equals(state));
				Assert.assertTrue("candidate-backed FED state lacks exact final publication: "
					+ node.key().normalizedSignature() + " state=" + state,
					published || explicitOutputMaterialization);
			}
		}
	}

	private static void assertPublishedRealization(PlacementAnalysis analysis,
		CandidateRealizationReference reference) {
		CandidateEmissionRealization realization = analysis.requireExactCandidateRealization(reference);
		Assert.assertTrue("support clauses must remain finite for the motivating branch fixture: "
			+ reference.rule().parentOccurrence().canonicalSourceOrigin() + "|count=" + realization.supportClauses().size()
			+ "|bindingVariants=" + realization.supportClauses().stream().map(clause -> clause.inputBindings()).distinct().count()
			+ "|layout=" + realization.key().layoutKind(),
			realization.supportClauses().size() <= 16);
		if(realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
			Assert.assertTrue("published native lineage must have typed worker-pool authority",
				realization.supportClauses().stream()
					.allMatch(clause -> realization.provenWorkerPool(clause) != null));
	}

	private static boolean isExecutableTransientAlternative(PlacementState state) {
		return state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
			&& state.fType() == null && !state.shapeDependent()
			|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() != null && state.fType() != FType.PART && state.fType() != FType.OTHER;
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram compileWithoutRewrite(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}

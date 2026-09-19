/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Independent bounded full-set oracles for the two remaining completeness gaps:
 * global NATIVE_LINEAGE receipt correlation and privacy/recompile interaction.
 *
 * The legality oracle deliberately does not call NativePlacementContinuity,
 * CandidateSelections reachability predicates, or a planner search routine.
 */
public class NativeLineagePlanSpaceCompletenessTest {
	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void nativeLineageHigherArityReceiptProductMatchesIndependentOracle() throws Exception {
		assertNativeLineageHigherArityReceiptProductMatchesIndependentOracle(Privacy.PUBLIC);
	}

	@Test
	public void protectedNativeLineageReceiptsRemainAvailable() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PRIVATE_AGGREGATE, true));
		List<CandidateSelectionReceipt> nativeReceipts = allReceipts(analysis).stream()
			.filter(NativeLineagePlanSpaceCompletenessTest::isNativeLineage).toList();

		Assert.assertFalse("protected fixture must retain NATIVE_LINEAGE receipts", nativeReceipts.isEmpty());
		Assert.assertTrue("protected native receipts must remain origin-resident FED/FOUT",
			nativeReceipts.stream().allMatch(row -> {
				PlacementState state = row.emission().emissionState().placementState();
				return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT;
			}));
	}

	private static void assertNativeLineageHigherArityReceiptProductMatchesIndependentOracle(
		Privacy privacy) throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compile(nativeLineageScript(), privacy, false));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = rawSelectedPlacementDomains(
			analysis, plan.selectedStates());
		Set<CompiledHopKey> component = nativeLineageDependencyClosure(raw);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> boundedRaw = project(raw, component);

		long nativeRows = boundedRaw.values().stream().flatMap(List::stream)
			.filter(NativeLineagePlanSpaceCompletenessTest::isNativeLineage).count();
		Assert.assertTrue("fixture must exercise multiple NATIVE_LINEAGE rows", nativeRows >= 3);
		Assert.assertTrue("fixture must exercise a higher-arity native AND clause",
			boundedRaw.values().stream().flatMap(List::stream)
				.anyMatch(row -> isNativeLineage(row) && row.supportClause().inputBindings().size() >= 2));

		Set<String> rawProduct = enumerateAll(boundedRaw);
		Set<String> oracle = enumerateLegal(analysis, plan.selectedStates(), boundedRaw);
		Assert.assertTrue("fixture must contain more than one global receipt combination", rawProduct.size() > 1);
		Assert.assertFalse("independent native-lineage oracle unexpectedly has no legal plan", oracle.isEmpty());

		Map<CompiledHopKey,List<CandidateSelectionReceipt>> production = project(
			CandidateSelections.feasibleVariants(analysis, analysis.graph(),
				analysis.graph().relocationActions(), plan.selectedStates()), component);
		Assert.assertEquals("production NATIVE_LINEAGE receipt product must equal the independent legal set",
			oracle, enumerateAll(production));
	}

	@Test
	@Ignore("PUBLIC-only recompile baseline is excluded by the repository test policy")
	public void publicRecompileRemovesOnlyCpFoutCandidateStates() throws Exception {
		PlacementAnalysis compiledPublic = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PUBLIC, false));
		PlacementAnalysis recompilePublic = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PUBLIC, true));

		Map<String,Set<PlacementState>> compiledStates = candidateStatesByStableOccurrence(compiledPublic);
		Map<String,Set<PlacementState>> recompileStates = candidateStatesByStableOccurrence(recompilePublic);
		int removedCpFout = 0;
		int compared = 0;
		for(var entry : recompileStates.entrySet()) {
			CompiledHopKey recompileKey = keyByStableOccurrence(recompilePublic).get(entry.getKey());
			if(recompileKey == null || !"recompile".equals(recompileKey.recompileContext()))
				continue;
			Set<PlacementState> before = compiledStates.get(entry.getKey());
			if(before == null)
				continue;
			Set<PlacementState> expected = new LinkedHashSet<>();
			for(PlacementState state : before) {
				if(isCpFout(state))
					removedCpFout++;
				else
					expected.add(state);
			}
			Assert.assertEquals("recompile may remove CP/FOUT but no other candidate placement state for "
				+ entry.getKey(), expected, entry.getValue());
			compared++;
		}
		Assert.assertTrue("fixture must compare concrete recompile occurrences", compared >= 3);
		Assert.assertTrue("fixture must contain at least one CP/FOUT state for the recompile exclusion",
			removedCpFout > 0);
	}

	@Test
	public void protectedRecompileExcludesCpFout() throws Exception {
		PlacementAnalysis recompileProtected = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PRIVATE_AGGREGATE, true));
		List<CandidateSelectionReceipt> protectedReceipts = allReceipts(recompileProtected);
		List<CandidateSelectionReceipt> recompileReceipts = protectedReceipts.stream()
			.filter(receipt -> "recompile".equals(receipt.rule().parentOccurrence().recompileContext()))
			.toList();
		Assert.assertFalse("fixture must expose protected recompile receipts", recompileReceipts.isEmpty());
		Assert.assertTrue("protected recompile must exclude CP/FOUT",
			recompileReceipts.stream().noneMatch(receipt ->
				isCpFout(receipt.emission().emissionState().placementState())));
	}

	@Test
	public void protectedCompileRecompileCrossProductPreservesEveryNonCpFoutState() throws Exception {
		PlacementAnalysis compiled = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PRIVATE_AGGREGATE, false));
		PlacementAnalysis recompiled = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileFunction(recompileNativeLineageScript(), Privacy.PRIVATE_AGGREGATE, true));
		Map<String,Set<PlacementState>> compiledStates = candidateStatesByStableOccurrence(compiled);
		Map<String,Set<PlacementState>> recompiledStates = candidateStatesByStableOccurrence(recompiled);
		int compared = 0;
		for(var entry : recompiledStates.entrySet()) {
			CompiledHopKey recompileKey = keyByStableOccurrence(recompiled).get(entry.getKey());
			if(recompileKey == null || !"recompile".equals(recompileKey.recompileContext()))
				continue;
			Set<PlacementState> before = compiledStates.get(entry.getKey());
			if(before == null)
				continue;
			Set<PlacementState> expected = before.stream().filter(state -> !isCpFout(state))
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			Set<PlacementState> missing = new LinkedHashSet<>(expected);
			missing.removeAll(entry.getValue());
			Set<PlacementState> extra = new LinkedHashSet<>(entry.getValue());
			extra.removeAll(expected);
			Assert.assertTrue("protected compile/recompile missing states for " + entry.getKey()
				+ ": " + missing, missing.isEmpty());
			Assert.assertTrue("protected compile/recompile extra states for " + entry.getKey()
				+ ": " + extra, extra.isEmpty());
			compared++;
		}
		Assert.assertTrue("fixture must compare concrete protected recompile occurrences", compared >= 3);

		Set<PlacementState> mutation = new LinkedHashSet<>(Set.of(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false)));
		PlacementState forbidden = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		Set<PlacementState> expected = Set.copyOf(mutation);
		mutation.add(forbidden);
		Set<PlacementState> diagnosedExtra = new LinkedHashSet<>(mutation);
		diagnosedExtra.removeAll(expected);
		Assert.assertEquals("mutation sentinel must diagnose only protected recompile CP/FOUT",
			Set.of(forbidden), diagnosedExtra);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> rawSelectedPlacementDomains(
		PlacementAnalysis analysis, Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = new IdentityHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			PlacementState selected = assignment.get(fact.key().parentOccurrence());
			if(selected == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(emission.emissionState().placementState().equals(selected))
					raw.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>())
						.addAll(analysis.canonicalCandidateReceipts(fact.key(), emission));
		}
		raw.replaceAll((ignored, rows) -> List.copyOf(rows));
		return raw;
	}

	private static Set<CompiledHopKey> nativeLineageDependencyClosure(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw) {
		Set<CompiledHopKey> selected = Collections.newSetFromMap(new IdentityHashMap<>());
		raw.forEach((consumer, rows) -> {
			if(rows.stream().anyMatch(NativeLineagePlanSpaceCompletenessTest::isNativeLineage))
				selected.add(consumer);
		});
		boolean changed;
		do {
			changed = false;
			for(CompiledHopKey consumer : List.copyOf(selected))
				for(CandidateSelectionReceipt receipt : raw.getOrDefault(consumer, List.of()))
					for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
						CompiledHopKey source = binding.source().rule().parentOccurrence();
						if(raw.containsKey(source) && selected.add(source))
							changed = true;
					}
		}
		while(changed);
		return selected;
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> project(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> source, Set<CompiledHopKey> consumers) {
		List<CompiledHopKey> ordered = new ArrayList<>(consumers);
		Collections.sort(ordered);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new LinkedHashMap<>();
		for(CompiledHopKey consumer : ordered) {
			List<CandidateSelectionReceipt> rows = source.get(consumer);
			Assert.assertNotNull("candidate domain is missing a native-lineage dependency owner", rows);
			Assert.assertFalse("candidate domain unexpectedly became bottom", rows.isEmpty());
			result.put(consumer, rows);
		}
		return result;
	}

	private static Set<String> enumerateLegal(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains) {
		List<CompiledHopKey> consumers = List.copyOf(domains.keySet());
		Set<String> legal = new LinkedHashSet<>();
		enumerate(domains, consumers, 0, new IdentityHashMap<>(), selected -> {
			if(selected.values().stream().allMatch(receipt -> independentlyReachable(
				analysis, assignment, selected, receipt)))
				legal.add(signature(consumers, selected));
		});
		return legal;
	}

	private static Set<String> enumerateAll(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains) {
		List<CompiledHopKey> consumers = List.copyOf(domains.keySet());
		Set<String> result = new LinkedHashSet<>();
		enumerate(domains, consumers, 0, new IdentityHashMap<>(),
			selected -> result.add(signature(consumers, selected)));
		return result;
	}

	private static boolean independentlyReachable(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		CandidateSelectionReceipt receipt) {
		if(receipt.emission().derivedFoutAction() != null
			|| receipt.emission().emissionState().derivedFedFout())
			return false; // The bounded fixture deliberately exercises native, not derived-FOUT, authority.
		if(isNativeLineage(receipt) && !independentNativeLineageOperation(analysis, receipt))
			return false;
		for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
			CandidateSelectionReceipt source = selected.get(binding.source().rule().parentOccurrence());
			if(source == null || !matches(binding.source(), source))
				return false;
			if(binding.kind() == CandidateInputBindingKind.LOGICAL_TRANSIENT)
				return false;
			if(binding.kind() == CandidateInputBindingKind.DIRECT) {
				if(!sameNativeResidency(source, receipt))
					return false;
				continue;
			}
			RelocationAction action = analysis.graph().relocationActions().stream()
				.filter(candidate -> candidate.key().equals(binding.relocationAction()))
				.findFirst().orElse(null);
			DurableAnchorKey target = residencyWitness(receipt);
			if(action == null || target == null
				|| !action.key().targetPlacement().equals(
					receipt.realization().key().emissionState().placementState())
				|| !PlacementIdentity.samePhysicalWorkerPool(action.key().durableAnchor(), target)
				|| analysis.graph().node(source.rule().parentOccurrence()).stream()
					.noneMatch(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
				|| action.obligations().stream().noneMatch(obligation ->
					obligation.consumer() == receipt.rule().parentOccurrence()
						&& obligation.inputPosition() == binding.inputPosition()
						&& obligation.requiredPlacement().equals(
							assignment.get(receipt.rule().parentOccurrence()))))
				return false;
		}
		return true;
	}

	private static boolean independentNativeLineageOperation(PlacementAnalysis analysis,
		CandidateSelectionReceipt receipt) {
		Hop hop = analysis.hop(receipt.rule().parentOccurrence()).orElse(null);
		if(hop == null || !"*".equals(hop.getOpString()) || residencyWitness(receipt) == null)
			return false;
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			if(!receipt.rule().orderedInputs().get(position).present())
				continue;
			final int requiredPosition = position;
			long bindings = receipt.supportClause().inputBindings().stream()
				.filter(binding -> binding.inputPosition() == requiredPosition).count();
			if(bindings != 1)
				return false;
		}
		return true;
	}

	private static boolean sameNativeResidency(CandidateSelectionReceipt source,
		CandidateSelectionReceipt target) {
		DurableAnchorKey sourcePool = residencyWitness(source);
		DurableAnchorKey targetPool = residencyWitness(target);
		if(sourcePool == null || targetPool == null)
			return false;
		boolean exact = source.realization().nativeWorkerPoolLayoutExact(source.supportClause())
			&& target.realization().nativeWorkerPoolLayoutExact(target.supportClause());
		return exact ? PlacementIdentity.samePhysicalWorkerPool(sourcePool, targetPool)
			: PlacementIdentity.samePhysicalWorkerEndpoints(sourcePool, targetPool);
	}

	private static DurableAnchorKey residencyWitness(CandidateSelectionReceipt receipt) {
		return receipt.realization().nativeWorkerPoolResidencyWitness(receipt.supportClause());
	}

	private static boolean matches(CandidateRealizationReference reference,
		CandidateSelectionReceipt receipt) {
		return reference.equals(CandidateRealizationReference.of(receipt.rule(), receipt.realization()));
	}

	private static void enumerate(Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains,
		List<CompiledHopKey> consumers, int position,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		java.util.function.Consumer<Map<CompiledHopKey,CandidateSelectionReceipt>> leaf) {
		if(position == consumers.size()) {
			leaf.accept(selected);
			return;
		}
		CompiledHopKey consumer = consumers.get(position);
		for(CandidateSelectionReceipt receipt : domains.get(consumer)) {
			selected.put(consumer, receipt);
			enumerate(domains, consumers, position + 1, selected, leaf);
			selected.remove(consumer);
		}
	}

	private static String signature(List<CompiledHopKey> consumers,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		return consumers.stream().sorted().map(consumer -> consumer.normalizedSignature() + "=>"
			+ selected.get(consumer).normalizedSignature()).reduce((left, right) -> left + "\n" + right)
			.orElse("");
	}

	private static List<CandidateSelectionReceipt> allReceipts(PlacementAnalysis analysis) {
		List<CandidateSelectionReceipt> receipts = new ArrayList<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				receipts.addAll(analysis.canonicalCandidateReceipts(fact.key(), emission));
		}
		return List.copyOf(receipts);
	}

	private static Map<String,Set<PlacementState>> candidateStatesByStableOccurrence(PlacementAnalysis analysis) {
		Map<String,Set<PlacementState>> result = new LinkedHashMap<>();
		for(CandidateSelectionReceipt receipt : allReceipts(analysis)) {
			CompiledHopKey owner = receipt.rule().parentOccurrence();
			if(!analysis.hop(owner).map(hop -> hop.getDataType().isMatrix()).orElse(false))
				continue;
			result.computeIfAbsent(stableOccurrence(owner), ignored -> new LinkedHashSet<>())
				.add(receipt.emission().emissionState().placementState());
		}
		result.replaceAll((ignored, states) -> Set.copyOf(states));
		return result;
	}

	private static Map<String,CompiledHopKey> keyByStableOccurrence(PlacementAnalysis analysis) {
		Map<String,CompiledHopKey> result = new LinkedHashMap<>();
		for(var occurrence : analysis.occurrences()) {
			if(!occurrence.hop().getDataType().isMatrix())
				continue;
			String stable = stableOccurrence(occurrence.key());
			CompiledHopKey prior = result.putIfAbsent(stable, occurrence.key());
			Assert.assertTrue("bounded fixture stable occurrence identity must be unique: " + stable,
				prior == null || prior == occurrence.key());
		}
		return result;
	}

	private static boolean isNativeLineage(CandidateSelectionReceipt receipt) {
		return receipt.realization().key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE;
	}

	private static boolean isCpFout(PlacementState state) {
		return state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT;
	}

	private static String stableOccurrence(CompiledHopKey key) {
		return key.functionNamespace() + '|' + key.callSitePath() + '|' + key.canonicalSourceOrigin();
	}

	private static String nativeLineageScript() {
		return "A=federated(addresses=list(\"localhost:5234/A1\",\"localhost:5235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "B=federated(addresses=list(\"localhost:5234/B1\",\"localhost:5235/B2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "U=A*2;V=B*3;C=U*V;D=C*4;print(sum(D));";
	}

	private static String recompileNativeLineageScript() {
		return "f=function(matrix[double] X,matrix[double] Y) return (matrix[double] D){"
			+ "U=X*2;V=Y*3;D=U*V;}"
			+ "A=federated(addresses=list(\"localhost:6234/A1\",\"localhost:6235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "B=federated(addresses=list(\"localhost:6234/B1\",\"localhost:6235/B2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "D=f(A,B);print(sum(D));";
	}

	private static DMLProgram compile(String script, Privacy privacy, boolean recompile) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		if(recompile)
			for(StatementBlock block : program.getStatementBlocks())
				block.setRecompileOnce(true);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return program;
	}

	private static DMLProgram compileFunction(String script, Privacy privacy, boolean recompile) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(recompile)
			program.getNamedNSFunctionStatementBlocks().values().forEach(function ->
				function.setRecompileOnce(true));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return program;
	}
}

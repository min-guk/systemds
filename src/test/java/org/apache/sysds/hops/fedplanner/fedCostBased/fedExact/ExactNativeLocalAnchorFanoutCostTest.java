/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
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
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Unrelated graph endpoints must not multiply the selected native FULL upload. */
public class ExactNativeLocalAnchorFanoutCostTest {
	// R56 cost-ownership baseline: source GET moved out of native-local/REFED PUT
	// tables into the shared activation collector. Legality products are unchanged.
	// Component regression: ExactExplicitWdivmmTransferTest; alias lifetime/versions:
	// ExactAliasGetCoalescingTest. Prior R55 digests and change evidence are recorded
	// in docs/COST_EXPLICIT_ALIAS_CALIBRATION_2026-10-05_KO.md.
	private static final String PROTECTED_NATIVE_LOCAL_FINGERPRINT =
		"physical-semantic-dag-v1:f299ad63b5d73b9f1f6fab0386b9ecd998e069a378daa49e561cc4f55664ac1c";
	private static final String PROTECTED_NATIVE_LOCAL_STRUCTURE_SHA256 =
		"be38b2b3443c56373846f157adaffaca113f1952673f64550beb7e295d7d2507";
	private static final String PROTECTED_NATIVE_LOCAL_BITS_SHA256 =
		"ed382489615a62cb3bccecf5f90264d071a1a8413505a8f60dc15bbfe2dda2db";

	@Test
	public void buildScopedWorkerCountMemoPreservesLegacyInvalidAddressCardinality() {
		DurableAnchorKey mixed = new DurableAnchorKey("mixed-invalid", FType.ROW, List.of(
			new AnchorPartition("worker:not-a-port", List.of(0L, 0L), List.of(2L, 2L)),
			new AnchorPartition("other:not-a-port", List.of(2L, 0L), List.of(4L, 2L)),
			new AnchorPartition("worker:1234/path-a", List.of(4L, 0L), List.of(6L, 2L)),
			new AnchorPartition("worker:1234/path-b", List.of(6L, 0L), List.of(8L, 2L))));
		DurableAnchorKey equalButDistinct = new DurableAnchorKey(
			mixed.placementId(), mixed.fType(), mixed.partitions());
		var probe = ExactPhysicalCostModel.physicalWorkerCountCacheProbeForTest(
			List.of(mixed, mixed, equalButDistinct));
		int legacy = legacyPhysicalWorkerCount(mixed);
		Assert.assertEquals("invalid canonical addresses must retain the old null set member", 2, legacy);
		Assert.assertEquals(List.of(legacy, legacy, legacy), probe.counts());
		Assert.assertEquals("same anchor identity must compute once; equal foreign identity remains separate",
			2, probe.computations());
	}

	private static int legacyPhysicalWorkerCount(DurableAnchorKey anchor) {
		var workers = new java.util.LinkedHashSet<String>();
		for(var partition : anchor.partitions())
			workers.add(FederationUtils.canonicalFederatedWorkerAddress(partition.workerId()));
		return workers.size();
	}

	@Test
	public void protectedNativeLocalCostSurfaceIsBitAndStructureStable() throws Exception {
		PlacementAnalysis analysis = analysis(false);
		// Keep the historical full legality product as an independent authority reference.
		// R56 intentionally changes transfer ownership, not which rows are legal; the
		// next test checks every surviving current row against this full-product model.
		ExactPhysicalModel model = ExactPhysicalModel.buildWithLegacyInputAuthorityProductsForTest(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		long nativeLocalAlternatives = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.inputAuthorities().stream().anyMatch(authority ->
					authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL))
			.count();
		Assert.assertTrue(nativeLocalAlternatives > 0);
		Assert.assertEquals(PROTECTED_NATIVE_LOCAL_STRUCTURE_SHA256, structureDigest(model, surface));
		Assert.assertEquals(PROTECTED_NATIVE_LOCAL_BITS_SHA256, contributionBitsDigest(model, surface));
		// The authority receipt hashes even excluded rows. Certified omission of
		// the aggregate's illegal ABSENT_LOCAL row changes that receipt, not the
		// raw cost bits asserted above. The derived-supply change gives the
		// same factors explicit operator/movement ownership labels, changing
		// the structure and receipt digests while preserving those bits. R59 also
		// canonicalizes initializer/TWrite/TRead creation identity in the activation
		// descriptor. That receipt change preserves this fixture's structure and bits.
		// Fused FOUT staging unifies the upload descriptor under source=ORIGINAL;
		// this protected fixture still preserves both structure and numeric bit digests.
		var omittedAggregate = analysis.candidateRuleDomain().privacyPrunedInputs().stream()
			.filter(proof -> analysis.hop(proof.consumer().occurrence()).orElseThrow()
				instanceof org.apache.sysds.hops.AggUnaryOp).findFirst().orElseThrow();
		var localTuple = List.of(PlacementAnalysis.CandidateInputState.absentLocal());
		Assert.assertTrue(omittedAggregate.rejects(localTuple));
		var lookup = Assert.assertThrows(PlacementAnalysis.CandidateRuleLookupException.class,
			() -> analysis.candidateRuleFacts().requireExact(omittedAggregate.consumer().occurrence(), localTuple));
		Assert.assertEquals(PlacementAnalysis.CandidateLookupFailure.PRIVACY_EXCLUDED, lookup.failure());
		Assert.assertEquals(PROTECTED_NATIVE_LOCAL_FINGERPRINT, surface.contributionFingerprint());
	}

	@Test
	public void privacySurvivorCostsMatchEveryCorrespondingHistoricalProductCell() throws Exception {
		PlacementAnalysis analysis = analysis(false);
		var legacy = ExactPhysicalModel.buildWithLegacyInputAuthorityProductsForTest(analysis);
		var current = ExactPhysicalModel.build(analysis);
		var oldSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, legacy);
		var newSurface = ExactPhysicalCostModel.physicalCostSurface(analysis, current);
		Map<ExactCategoricalSolver.Variable,int[]> originalOrdinals = new IdentityHashMap<>();
		int removed = 0;
		for(int d = 0; d < current.domains().size(); d++) {
			var oldDomain = legacy.domains().get(d);
			var newDomain = current.domains().get(d);
			Assert.assertSame(oldDomain.node(), newDomain.node());
			int[] map = new int[newDomain.alternatives().size()];
			for(int value = 0; value < map.length; value++) {
				String signature = newDomain.alternatives().get(value).signature();
				int matched = -1;
				for(int old = 0; old < oldDomain.alternatives().size(); old++)
					if(oldDomain.alternatives().get(old).signature().equals(signature)) {
						Assert.assertEquals("ambiguous historical authority", -1, matched);
						matched = old;
					}
				Assert.assertTrue("missing historical authority", matched >= 0);
				map[value] = matched;
			}
			removed += oldDomain.alternatives().size() - map.length;
			originalOrdinals.put(newDomain.variable(), map);
		}
		Assert.assertTrue("fixture must exercise privacy product removal", removed > 0);
		// A transfer demanded only by deleted illegal rows may disappear. It must be
		// identically +0 on every surviving Cartesian cell; all other contributions
		// retain their original order, scope, and every raw numeric bit.
		Assert.assertEquals(mappedCostTables(oldSurface, current, originalOrdinals),
			mappedCostTables(newSurface, current, null));
	}

	private record CostTable(List<String> scope, List<Long> rawBits) { }

	private static List<CostTable> mappedCostTables(ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactPhysicalModel current, Map<ExactCategoricalSolver.Variable,int[]> originalOrdinals) {
		Map<String,ExactCategoricalSolver.Variable> variables = new HashMap<>();
		for(var variable : current.variables())
			variables.put(variable.key(), variable);
		List<CostTable> tables = new ArrayList<>();
		for(var contribution : surface.contributions()) {
			var factor = contribution.factor();
			var currentScope = factor.scope().stream().map(v -> variables.get(v.key())).toList();
			int cells = currentScope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
				.reduce(1, Math::multiplyExact);
			List<Long> bits = new ArrayList<>(cells);
			int[] values = new int[currentScope.size()];
			for(int cell = 0; cell < cells; cell++) {
				int remaining = cell;
				for(int p = values.length - 1; p >= 0; p--) {
					int value = remaining % currentScope.get(p).domainSize();
					remaining /= currentScope.get(p).domainSize();
					values[p] = originalOrdinals == null ? value : originalOrdinals.get(currentScope.get(p))[value];
				}
				bits.add(Double.doubleToRawLongBits(factor.cost(values)));
			}
			if(bits.stream().anyMatch(value -> value != 0L))
				tables.add(new CostTable(currentScope.stream().map(ExactCategoricalSolver.Variable::key).toList(), bits));
		}
		return tables;
	}

	@Test
	public void fullInputUploadDoesNotGrowWithUnrelatedGraphWorkers() throws Exception {
		double isolated = nativeInputCost(analysis(false));
		double unrelated = nativeInputCost(analysis(true));
		Assert.assertTrue("The fixture must exercise a nonzero native local upload", isolated > 0);
		Assert.assertEquals("The same single-FULL runtime map receives one local LHS upload,"
			+ " regardless of unrelated ROW workers elsewhere in the program", isolated, unrelated, 1e-12);
	}

	@Test
	public void broadcastCountsEveryActualPartitionAndNoAuthorityKeepsFallback() {
		var full = authority(FType.FULL, 1);
		var broadcast = authority(FType.BROADCAST, 3);
		Assert.assertEquals(1, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(full), 9));
		Assert.assertEquals(3, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(broadcast), 9));
		Assert.assertEquals(3, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(broadcast, broadcast), 9));
		Assert.assertEquals(9, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(), 9));
		Assert.assertEquals(1, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(), 0));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(full, broadcast), 9));
	}

	@Test
	public void sameWorkerPoolWithDistinctMetadataIdentitiesRetainsPhysicalProduct() {
		var first = authority(FType.BROADCAST, 3);
		var action = first.relocationAction();
		var old = action.key();
		DurableAnchorKey alias = new DurableAnchorKey("different-metadata-owner", old.durableAnchor().fType(),
			old.durableAnchor().partitions());
		RelocationActionKey key = new RelocationActionKey(old.sourceValueVersion(), old.targetPlacement(),
			alias, old.statementBlockScope(), old.compatibleConsumers());
		var previous = action.obligations().get(0);
		ObligationKey obligation = new ObligationKey(previous.consumer(), previous.inputPosition(),
			previous.sourceValueVersion(), previous.requiredPlacement(), key, previous.callRecompileContext());
		var second = new ExactPhysicalModel.InputAuthority(first.inputPosition(), first.kind(),
			first.expectedFType(), first.sourceDecision(), new RelocationAction(key, List.of(obligation)));
		Assert.assertTrue(ExactPhysicalModel.hasOneExactConsumerAnchor(List.of(first, second)));
		Assert.assertEquals(3, ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(first, second), 9));
		Assert.assertFalse(ExactPhysicalModel.hasOneExactConsumerAnchor(List.of(first, authority(FType.FULL, 1))));
	}

	@Test
	public void sourceRealizationNotGraphWorkerUnionDeterminesDownloadFanIn() {
		var two = sourceAlternative(2);
		var four = sourceAlternative(4);
		Assert.assertEquals(2, ExactPhysicalCostModel.realizationWorkerCount(null, two, 9));
		Assert.assertEquals(4, ExactPhysicalCostModel.realizationWorkerCount(null, four, 9));
		double bytes = 16 * 1024 * 1024;
		double twoCost = org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel
			.computeReusableMaterializationDownloadCost(bytes, FType.ROW,
				ExactPhysicalCostModel.realizationWorkerCount(null, two, 9));
		double fourCost = org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel
			.computeReusableMaterializationDownloadCost(bytes, FType.ROW,
				ExactPhysicalCostModel.realizationWorkerCount(null, four, 9));
		Assert.assertNotEquals(twoCost, fourCost, 1e-12);
	}

	@Test
	public void conflictingAuthorityPrefixDoesNotExpandLaterGroups() throws Exception {
		var first = authority(FType.BROADCAST, 3);
		var incompatible = authority(FType.FULL, 1);
		var visits = new java.util.concurrent.atomic.AtomicInteger();
		List<List<ExactPhysicalModel.InputAuthority>> suffix = new java.util.AbstractList<>() {
			@Override public int size() { return 20; }
			@Override public List<ExactPhysicalModel.InputAuthority> get(int index) {
				visits.incrementAndGet();
				return List.of(first);
			}
		};
		var enumerate = ExactPhysicalModel.class.getDeclaredMethod("expandAuthorityGroups",
			List.class, int.class, List.class, List.class);
		enumerate.setAccessible(true);
		List<List<ExactPhysicalModel.InputAuthority>> rows = new java.util.ArrayList<>();
		enumerate.invoke(null, List.of(List.of(List.of(first)), List.of(List.of(incompatible)), suffix),
			0, new java.util.ArrayList<>(), rows);
		Assert.assertTrue(rows.isEmpty());
		Assert.assertEquals("a conflicting pool prefix cannot be repaired by later inputs", 0, visits.get());
	}

	@Test
	public void emittedRelocationPoolOverridesExecutionRealizationFanIn() {
		var execution = sourceAlternative(2);
		var outputAction = authority(FType.ROW, 4).relocationAction();
		var relocated = new ExactPhysicalModel.Alternative(execution.decision(),
			outputAction.key().targetPlacement(), ExactPhysicalModel.AuthorityKind.RELOCATION_SOURCE,
			null, null, null, null, outputAction.key().durableAnchor(), outputAction,
			null, List.of(), List.of(), execution.realization(), execution.supportClause(), "relocated-output");
		Assert.assertEquals("The download reads the emitted target map, not the pre-relocation pool",
			4, ExactPhysicalCostModel.realizationWorkerCount(null, relocated, 9));
	}

	@Test
	public void workerCardinalityCountsCanonicalDistinctEndpointsNotPartitions() {
		List<String> twoEndpoints = List.of(
			"worker0:1234/data/features", "worker0:1234/data/labels", "worker1:1234/data/features");
		List<String> threeEndpoints = List.of(
			"worker0:1234/data/features", "worker0:1235/data/labels", "worker1:1234/data/features");
		var twoEndpointAuthority = authority(FType.ROW, twoEndpoints);
		var threeEndpointAuthority = authority(FType.ROW, threeEndpoints);

		Assert.assertEquals("Two paths on one host:port are one physical worker", 2,
			ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(twoEndpointAuthority), 9));
		Assert.assertEquals("A different port is a distinct physical worker", 3,
			ExactPhysicalCostModel.nativeLocalInputWorkerCount(List.of(threeEndpointAuthority), 9));
		Assert.assertEquals(2, ExactPhysicalCostModel.realizationWorkerCount(null,
			sourceAlternative(twoEndpointAuthority), 9));
		Assert.assertEquals(3, ExactPhysicalCostModel.realizationWorkerCount(null,
			sourceAlternative(threeEndpointAuthority), 9));
	}

	private static ExactPhysicalModel.Alternative sourceAlternative(int workers) {
		return sourceAlternative(authority(FType.ROW, workers));
	}

	private static ExactPhysicalModel.Alternative sourceAlternative(ExactPhysicalModel.InputAuthority input) {
		var state = input.relocationAction().key().targetPlacement();
		var emission = new org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState(state, false);
		var realization = PlacementAnalysis.CandidateEmissionRealization.durable(emission,
			input.relocationAction().key().durableAnchor(), List.of(), List.of());
		return new ExactPhysicalModel.Alternative(input.sourceDecision(), state,
			ExactPhysicalModel.AuthorityKind.DURABLE_ANCHOR, null, null, null, null,
			realization.anchor(), null, null, List.of(), List.of(), realization, realization.supportClauses().get(0), "test-map");
	}

	private static ExactPhysicalModel.InputAuthority authority(FType type, int partitions) {
		var workers = new ArrayList<String>();
		for(int index = 0; index < partitions; index++)
			workers.add("worker" + index + ":1234");
		return authority(type, workers);
	}

	private static ExactPhysicalModel.InputAuthority authority(FType type, List<String> workers) {
		ControlRegionKey region = new ControlRegionKey("fanout", "main", List.of("main/0"), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey("fanout", "main", "main", "compiled", region, "input", "input");
		ValueVersionKey value = new ValueVersionKey("fanout", "input", region, 0, VersionKind.ORDINARY, List.of());
		var ranges = new ArrayList<AnchorPartition>();
		for(int index = 0; index < workers.size(); index++)
			ranges.add(new AnchorPartition(workers.get(index),
				type == FType.ROW ? List.of(index * 8L / workers.size(), 0L) : List.of(0L, 0L),
				type == FType.ROW ? List.of((index + 1) * 8L / workers.size(), 2L) : List.of(4L, 2L)));
		DurableAnchorKey anchor = new DurableAnchorKey("anchor-" + workers.size(), type, ranges);
		PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, type, false);
		RelocationActionKey actionKey = new RelocationActionKey(value, state, anchor, "main", List.of(key));
		ObligationKey obligation = new ObligationKey(key, 1, value, state, actionKey, "main");
		RelocationAction action = new RelocationAction(actionKey, List.of(obligation));
		return new ExactPhysicalModel.InputAuthority(1, ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT,
			type, key, action);
	}

	private static double nativeInputCost(PlacementAnalysis analysis) {
		var lix = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof LeftIndexingOp).findFirst().orElseThrow();
		var edge = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(input -> input.consumer() == lix.key() && input.inputPosition() == 0)
			.findFirst().orElseThrow();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var producer = model.domains().stream().filter(domain -> domain.node().key() == edge.producer())
			.findFirst().orElseThrow();
		var consumer = model.domains().stream().filter(domain -> domain.node().key() == lix.key())
			.findFirst().orElseThrow();
		int sourceValue = -1;
		for(int i = 0; i < producer.alternatives().size(); i++) {
			var state = producer.alternatives().get(i).state();
			if(state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT) {
				sourceValue = i;
				break;
			}
		}
		Assert.assertTrue("The LHS must be coordinator-local", sourceValue >= 0);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Double expected = null;
		for(int i = 0; i < consumer.alternatives().size(); i++) {
			var target = consumer.alternatives().get(i);
			if(target.state().execType() != ExecType.FED || target.state().fType() != FType.FULL
				|| target.inputAuthorities().stream().noneMatch(authority -> authority.inputPosition() == 0
					&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.NATIVE_LOCAL)
				|| target.inputAuthorities().stream().noneMatch(authority -> authority.relocationAction() != null))
				continue;
			for(var authority : target.inputAuthorities())
				if(authority.relocationAction() != null)
					Assert.assertEquals("Only one worker actually consumes this upload", 1,
						authority.relocationAction().key().durableAnchor().partitions().size());
			int[] values = {sourceValue, i};
			double cost = surface.contributions().stream().map(ExactPhysicalCostModel.PhysicalContribution::factor)
				.filter(factor -> factor.scope().equals(List.of(producer.variable(), consumer.variable())))
				.mapToDouble(factor -> factor.cost(values)).sum();
			if(expected == null) expected = cost;
			else Assert.assertEquals("Direct and relocated RHS share the same local-upload map", expected, cost, 1e-12);
		}
		Assert.assertNotNull("Expected an exact-anchor native FULL LIX input factor", expected);
		return expected;
	}

	private static String structureDigest(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++) {
			var variable = model.variables().get(index);
			positions.put(variable, index);
			update(digest, "variable=" + index + ':' + variable.key() + ':' + variable.domainSize() + '\n');
		}
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			update(digest, "domain=" + index + ':' + domain.node().key().normalizedSignature() + '\n');
			for(int alternative = 0; alternative < domain.alternatives().size(); alternative++)
				update(digest, "alternative=" + alternative + ':'
					+ domain.alternatives().get(alternative).signature() + '\n');
		}
		for(int index = 0; index < surface.contributions().size(); index++) {
			var contribution = surface.contributions().get(index);
			update(digest, "contribution=" + index + ':' + contribution.id() + "|scope=");
			for(var variable : contribution.factor().scope())
				update(digest, positions.get(variable) + ":" + variable.domainSize() + ',');
			update(digest, "\n");
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String contributionBitsDigest(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		for(int index = 0; index < surface.contributions().size(); index++) {
			var factor = surface.contributions().get(index).factor();
			update(digest, "factor=" + index + "|scope=");
			for(var variable : factor.scope())
				update(digest, positions.get(variable) + ":" + variable.domainSize() + ',');
			int cells = factor.scope().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < cells; cell++) {
				int remainder = cell;
				for(int position = values.length - 1; position >= 0; position--) {
					int radix = factor.scope().get(position).domainSize();
					values[position] = remainder % radix;
					remainder /= radix;
				}
				update(digest, Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values))) + ',');
			}
			update(digest, "\n");
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}

	static PlacementAnalysis analysis(boolean unrelatedWorkers) throws Exception {
		String script = "Z=matrix(1,rows=4,cols=2);\n"
			+ "R=federated(addresses=list(\"localhost:1234/R\"),ranges=list(list(0,0),list(4,1)));\n"
			+ "O=Z;O[1:4,1]=R;print(sum(O));\n"
			+ (unrelatedWorkers ? "B=federated(addresses=list(\"localhost:1235/B\",\"localhost:1236/B\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));print(sum(B));\n" : "");
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

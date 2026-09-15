/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Unrelated graph endpoints must not multiply the selected native FULL upload. */
public class ExactNativeLocalAnchorFanoutCostTest {
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

	private static PlacementAnalysis analysis(boolean unrelatedWorkers) throws Exception {
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

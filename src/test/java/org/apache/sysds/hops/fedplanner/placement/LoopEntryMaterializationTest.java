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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for a one-time local-to-federated loop-entry materialization. */
public class LoopEntryMaterializationTest {
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState ROW_UPLOAD = new PlacementState(
		ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState COL_UPLOAD = new PlacementState(
		ExecType.CP, FederatedOutput.FOUT, FType.COL, false);
	private static final PlacementState BROADCAST_UPLOAD = new PlacementState(
		ExecType.CP, FederatedOutput.FOUT, FType.BROADCAST, false);
	private static final PlacementState FED_ROW = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState FED_COL = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.COL, false);
	private static final PlacementState FED_BROADCAST = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false);

	@Test
	public void localInitializerCanEnterFederatedLoopThroughRealRowAnchor() throws Exception {
		PlacementAnalysis analysis = analyze(withRowAnchor(3));
		HopOccurrenceProjection initializer = initializer(analysis);
		Set<PlacementState> initializerStates = states(analysis, initializer);

		Assert.assertTrue("ordinary local execution must remain available", initializerStates.contains(LOCAL));
		Assert.assertTrue("matching ROW upload must be available at loop entry",
			initializerStates.contains(ROW_UPLOAD));
		Assert.assertTrue("the same two-worker pool must admit COL repartitioning",
			initializerStates.contains(COL_UPLOAD));
		Assert.assertTrue("worker-pool broadcast upload must also remain available",
			initializerStates.contains(BROADCAST_UPLOAD));

		Set<FType> materializations = analysis.graph().derivedFoutMaterializationActions().stream()
			.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
			.filter(action -> action.producer() == initializer.key())
			.map(action -> {
				Assert.assertEquals(LOCAL, action.sourcePlacement());
				Assert.assertEquals(action.targetPlacement().fType(), action.materializationFType());
				Assert.assertEquals(FType.ROW, action.durableAnchorOwnerFType());
				return action.materializationFType();
			})
			.collect(Collectors.toSet());
		Assert.assertEquals("each legal entry layout needs exact graph-owned action authority",
			Set.of(FType.ROW, FType.COL, FType.BROADCAST), materializations);

		HopOccurrenceProjection read = joinedLoopRead(analysis);
		List<HopOccurrenceProjection> writes = pWrites(analysis);
		Assert.assertEquals("fixture must retain one entry write and one loop back write", 2, writes.size());
		for(HopOccurrenceProjection write : writes) {
			Set<PlacementState> writeStates = states(analysis, write);
			Assert.assertTrue("each p writer must support the steady ROW loop state",
				writeStates.contains(FED_ROW));
			Assert.assertTrue("each p writer must support the steady COL loop state",
				writeStates.contains(FED_COL));
			Assert.assertTrue("each p writer must support the steady broadcast loop state",
				writeStates.contains(FED_BROADCAST));
		}
		Assert.assertTrue(states(analysis, read).contains(FED_ROW));
		Assert.assertTrue(states(analysis, read).contains(FED_COL));
		Assert.assertTrue(states(analysis, read).contains(FED_BROADCAST));
		Assert.assertEquals("the loop read must retain entry and back-edge definitions", 2,
			analysis.logicalTransientInputsForReader(read.key(), 0).size());
	}

	@Test
	public void initializerUploadIsChargedOnceForEveryLoopTripCount() throws Exception {
		for(int trips : List.of(1, 2, 10)) {
			PlacementAnalysis analysis = analyze(withRowAnchor(trips));
			HopOccurrenceProjection initializer = initializer(analysis);
			Assert.assertEquals("entry materialization is outside the loop for T=" + trips,
				1.0, analysis.executionFrequencyFacts().executionWeight(initializer.key()), 0.0);
			HopOccurrenceProjection backWrite = pWrites(analysis).stream()
				.filter(write -> write.hop().getInput(0) != initializer.hop())
				.findFirst().orElseThrow(AssertionError::new);
			Assert.assertEquals("back write executes once per body iteration for T=" + trips,
				(double) trips, analysis.executionFrequencyFacts().executionWeight(backWrite.key()), 0.0);
		}
	}

	@Test
	public void differentSourceLayoutsOnTheSameWorkersKeepDistinctUploadAuthorities() throws Exception {
		PlacementAnalysis analysis = analyze(
			"R=federated(addresses=list(\"localhost:1284/R1\",\"localhost:1285/R2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
				+ "C=federated(addresses=list(\"localhost:1284/C1\",\"localhost:1285/C2\"),"
				+ "ranges=list(list(0,0),list(8,1),list(0,1),list(8,2)));"
				+ "p=rand(rows=8,cols=2,seed=7);for(i in 1:3){p=p+1;}"
				+ "print(sum(p));print(sum(R));print(sum(C));");
		var producer = initializer(analysis).key();
		var actions = analysis.graph().derivedFoutMaterializationActions().stream()
			.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
			.filter(action -> action.producer() == producer).toList();
		Assert.assertEquals("both concrete sources must authorize each of three upload layouts", 6, actions.size());
		Assert.assertEquals(Set.of(FType.ROW, FType.COL), actions.stream()
			.map(action -> action.durableAnchorOwnerFType()).collect(Collectors.toSet()));
		Assert.assertTrue(states(analysis, joinedLoopRead(analysis)).containsAll(Set.of(FED_ROW, FED_COL, FED_BROADCAST)));
	}

	@Test
	public void everyCompatibleAnchorPoolRetainsItsOwnEntryAuthority() throws Exception {
		String script = "R=federated(addresses=list(\"localhost:1244/R1\",\"localhost:1245/R2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "C=federated(addresses=list(\"localhost:1254/C1\",\"localhost:1255/C2\"),"
			+ "ranges=list(list(0,0),list(8,1),list(0,1),list(8,2)));"
			+ "p=rand(rows=8,cols=2,seed=7);"
			+ "for(i in 1:3){p=p+1;}print(sum(p));print(sum(R));print(sum(C));";
		PlacementAnalysis analysis = analyze(script);
		HopOccurrenceProjection initializer = initializer(analysis);
		List<HopOccurrenceProjection> anchors = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.toList();
		Assert.assertEquals(2, anchors.size());
		CompiledHopKey rowAnchor = source(analysis, "R").key();
		CompiledHopKey colAnchor = source(analysis, "C").key();

		var actions = analysis.graph().derivedFoutMaterializationActions().stream()
			.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
			.filter(action -> action.producer() == initializer.key())
			.toList();
		Set<CompiledHopKey> owners = actions.stream().map(action -> action.durableAnchorOwner())
			.collect(Collectors.toSet());
		Assert.assertEquals("anchor discovery must not stop after the first worker pool",
			anchors.stream().map(HopOccurrenceProjection::key).collect(Collectors.toSet()), owners);
		Assert.assertEquals(Set.of(FType.ROW, FType.COL, FType.BROADCAST), actions.stream()
			.filter(action -> action.durableAnchorOwner() == rowAnchor)
			.map(action -> action.materializationFType()).collect(Collectors.toSet()));
		Assert.assertEquals(Set.of(FType.ROW, FType.COL, FType.BROADCAST), actions.stream()
			.filter(action -> action.durableAnchorOwner() == colAnchor)
			.map(action -> action.materializationFType()).collect(Collectors.toSet()));
		Assert.assertFalse("a two-worker partitioned anchor cannot authorize FULL",
			actions.stream().anyMatch(action -> action.materializationFType() == FType.FULL));
	}

	@Test
	public void singleWorkerAnchorRetainsFullEntryLayout() throws Exception {
		PlacementAnalysis analysis = analyze(
			"F=federated(addresses=list(\"localhost:1264/F1\"),"
				+ "ranges=list(list(0,0),list(8,2)));"
				+ "p=rand(rows=8,cols=2,seed=7);"
				+ "for(i in 1:3){p=p+1;}print(sum(p));print(sum(F));");
		HopOccurrenceProjection initializer = initializer(analysis);
		Assert.assertTrue("a one-worker whole-matrix map authorizes FULL",
			analysis.graph().derivedFoutMaterializationActions().stream()
				.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
				.anyMatch(action -> action.producer() == initializer.key()
					&& action.materializationFType() == FType.FULL));
	}

	@Test
	public void everyRuntimeBranchInitializerCanEnterTheSameFederatedLoop() throws Exception {
		PlacementAnalysis analysis = analyze(
			"X=federated(addresses=list(\"localhost:1284/X1\",\"localhost:1285/X2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
				+ "if(sum(X)>0){p=rand(rows=8,cols=2,seed=7);}"
				+ "else{p=rand(rows=8,cols=2,seed=8);}"
				+ "for(i in 1:3){p=p+1;}print(sum(p));print(sum(X));");
		List<HopOccurrenceProjection> initializers = initializers(analysis);
		Assert.assertEquals("runtime branch must retain both reaching initial values", 2, initializers.size());
		for(HopOccurrenceProjection initializer : initializers) {
			Assert.assertTrue(states(analysis, initializer).containsAll(
				Set.of(LOCAL, ROW_UPLOAD, COL_UPLOAD, BROADCAST_UPLOAD)));
			Assert.assertEquals("each branch producer needs its own exact upload authority",
				Set.of(FType.ROW, FType.COL, FType.BROADCAST),
				analysis.graph().derivedFoutMaterializationActions().stream()
					.map(NeutralPlacementGraph.DerivedFoutMaterializationAction::key)
					.filter(action -> action.producer() == initializer.key())
					.map(action -> action.materializationFType()).collect(Collectors.toSet()));
		}

		HopOccurrenceProjection read = joinedLoopRead(analysis);
		Assert.assertEquals("loop read must retain both branch entries plus its back edge", 3,
			analysis.logicalTransientInputsForReader(read.key(), 0).size());
		Assert.assertTrue(states(analysis, read).containsAll(Set.of(FED_ROW, FED_COL, FED_BROADCAST)));
		Assert.assertEquals(3, pWrites(analysis).size());
		for(HopOccurrenceProjection write : pWrites(analysis))
			Assert.assertTrue(states(analysis, write).containsAll(
				Set.of(FED_ROW, FED_COL, FED_BROADCAST)));
	}

	@Test
	public void loopEntryUploadDoesNotRemoveAnOutsideLocalConsumer() throws Exception {
		PlacementAnalysis analysis = analyze(
			"X=federated(addresses=list(\"localhost:1294/X1\",\"localhost:1295/X2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
				+ "p=rand(rows=8,cols=2,seed=7);q=p+2;"
				+ "for(i in 1:3){p=p+1;}print(sum(q));print(sum(p));print(sum(X));");
		Set<PlacementState> initializerStates = states(analysis, initializer(analysis));

		Assert.assertTrue("the ordinary local result remains usable outside the loop",
			initializerStates.contains(LOCAL));
		Assert.assertTrue("the same producer may independently materialize the loop entry",
			initializerStates.containsAll(Set.of(ROW_UPLOAD, COL_UPLOAD, BROADCAST_UPLOAD)));
	}

	@Test
	public void bodyOnlyFederatedSourceCannotAuthorizeLoopEntryUpload() throws Exception {
		PlacementAnalysis analysis = analyze("p=rand(rows=8,cols=2,seed=7);"
			+ "for(i in 1:3){"
			+ "X=federated(addresses=list(\"localhost:1274/X1\",\"localhost:1275/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=p+1;print(sum(X));}print(sum(p));");
		HopOccurrenceProjection initializer = initializer(analysis);

		Assert.assertEquals(Set.of(LOCAL), states(analysis, initializer));
		Assert.assertTrue("an anchor created only after loop entry cannot authorize the entry upload",
			analysis.graph().derivedFoutMaterializationActions().stream()
				.noneMatch(action -> action.key().producer() == initializer.key()));
	}

	@Test
	public void localLoopWithoutFederatedAnchorDoesNotInventUploadAuthority() throws Exception {
		PlacementAnalysis analysis = analyze("p=rand(rows=8,cols=2,seed=7);"
			+ "for(i in 1:3){p=p+1;}print(sum(p));");
		HopOccurrenceProjection initializer = initializer(analysis);

		Assert.assertEquals(Set.of(LOCAL), states(analysis, initializer));
		Assert.assertTrue("no physical anchor means no local-to-FOUT action",
			analysis.graph().derivedFoutMaterializationActions().stream()
				.noneMatch(action -> action.key().producer() == initializer.key()));
	}

	private static String withRowAnchor(int trips) {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=rand(rows=8,cols=2,seed=7);"
			+ "for(i in 1:" + trips + "){p=p+1;}print(sum(p));print(sum(X));";
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static HopOccurrenceProjection initializer(PlacementAnalysis analysis) {
		return initializers(analysis).stream().findFirst().orElseThrow(AssertionError::new);
	}

	private static List<HopOccurrenceProjection> initializers(PlacementAnalysis analysis) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataGenOp)
			.filter(occurrence -> occurrence.hop().getDim1() == 8 && occurrence.hop().getDim2() == 2)
			.toList();
	}

	private static HopOccurrenceProjection joinedLoopRead(PlacementAnalysis analysis) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "p".equals(data.getName()))
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() > 1)
			.findFirst().orElseThrow(AssertionError::new);
	}

	private static HopOccurrenceProjection source(PlacementAnalysis analysis, String name) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED && name.equals(data.getName()))
			.findFirst().orElseThrow(AssertionError::new);
	}

	private static List<HopOccurrenceProjection> pWrites(PlacementAnalysis analysis) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && "p".equals(data.getName()))
			.toList();
	}

	private static Set<PlacementState> states(PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence) {
		return Set.copyOf(analysis.graph().node(occurrence.key()).orElseThrow().legalAlternatives());
	}
}

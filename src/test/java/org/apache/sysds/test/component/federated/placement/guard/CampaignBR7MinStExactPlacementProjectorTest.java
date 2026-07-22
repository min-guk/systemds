/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPlacementProjector;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused guard for exact MinST selection-to-carrier projection. */
public class CampaignBR7MinStExactPlacementProjectorTest {
	@Test
	public void projectsActualCampaignAnalysesAndReplaysThroughAdapter() throws Exception {
		int fixtures = 0;
		int nonEmittedNoneReceipts = 0;
		int selectedObligations = 0;
		int groupedObligations = 0;
		for(String id : List.of("ACTUAL-ROOT")) {
			Projection projection = project(id);
			fixtures++;
			Assert.assertSame(id, projection.analysis, projection.facts.analysis());
			Assert.assertEquals(id, projection.facts.analysisFingerprint(),
				projection.input.producerReceipt().analysisFingerprint());
			Assert.assertEquals(id, projection.selection.objectiveBits(),
				projection.input.producerReceipt().cutObjectiveBits());
			Assert.assertEquals(id, projection.selection.sourcePartitionNodeIds(),
				projection.input.producerReceipt().sourcePartitionNodeIds());
			Assert.assertEquals(id, projection.analysis.occurrences().size(),
				projection.input.occurrenceReceipts().size());

			for(int i = 0; i < projection.analysis.occurrences().size(); i++) {
				var occurrence = projection.analysis.occurrences().get(i);
				var receipt = projection.input.occurrenceReceipts().get(i);
				Assert.assertSame(id, occurrence.key(), receipt.planningKey());
				Assert.assertSame(id, occurrence.hop(), receipt.planningHop());
				NeutralPlacementGraph.Node node = projection.analysis.graph().node(occurrence.key()).orElseThrow();
				if(!node.emittedWork()) {
					Assert.assertNull(id, receipt.execType());
					Assert.assertEquals(id, FederatedOutput.NONE, receipt.output());
					nonEmittedNoneReceipts++;
				}
				else {
					Assert.assertNotNull(id, receipt.execType());
					Assert.assertNotEquals(id, FederatedOutput.NONE, receipt.output());
				}
			}

			MinStPlacementAdapter.Selection replay = replayWithSelectedHopState(projection);
			Assert.assertSame(id, projection.analysis, replay.analysis());
			Assert.assertSame(id, projection.input.producerReceipt(), replay.producer());
			Assert.assertEquals(id, projection.selection.objectiveBits(), replay.cutObjectiveBits());
			Assert.assertEquals(id, projection.input.occurrenceReceipts().size(),
				replay.selectedReceipts().size());

			selectedObligations += projection.selection.obligationReceiptsInOrder().size();
			groupedObligations += projection.input.obligationReceipts().size();
			assertObligationProjectionUsesExactHopIds(id, projection);
			projection.analysis.assertProgramStructureUnchanged();
			Assert.assertEquals(id, projection.facts.analysisFingerprint(),
				projection.analysis.analysisFingerprint());
		}
		Assert.assertEquals("root null/NONE contextual receipts", 0, nonEmittedNoneReceipts);
		assertActualCampaignBFixturesFailClosedUntilExactFactsCanBeDerived();
		if(selectedObligations > 0)
			Assert.assertTrue("actual obligations are retained/grouped", groupedObligations > 0
				&& groupedObligations <= selectedObligations);
		Assert.assertEquals("fixtures exercised", 1, fixtures);
	}

	private static void assertActualCampaignBFixturesFailClosedUntilExactFactsCanBeDerived() throws Exception {
		for(String id : List.of("B-01", "B-07", "B-09", "B-16")) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(id));
			Assert.assertFalse(id, analysis.occurrences().isEmpty());
			if("B-07".equals(id))
				Assert.assertTrue(id, analysis.graph().nodes().stream().anyMatch(node -> !node.emittedWork()));
			Assert.assertThrows(id, IllegalArgumentException.class,
				() -> MinStExactCostFactsProducer.derive(analysis, scope(analysis)));
		}
	}

	@Test
	public void rejectsTieForeignCopiedCardinalityAndStaleCertificates() throws Exception {
		Projection owner = project("ACTUAL-ROOT");
		Projection foreign = project("ACTUAL-ROOT");

		Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
			owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
				owner.selection.selectedStatesInScopeOrder(), owner.selection.obligationReceiptsInOrder(),
				MinStExactSelection.TIE_UNSPECIFIED, owner.selection.minimaCertificates())));

		Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
			owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
				owner.selection.selectedStatesInScopeOrder().subList(0,
					owner.selection.selectedStatesInScopeOrder().size() - 1),
				owner.selection.obligationReceiptsInOrder(), MinStExactSelection.UNIQUE,
				owner.selection.minimaCertificates())));

		List<PlacementState> copiedStates = new ArrayList<>(owner.selection.selectedStatesInScopeOrder());
		PlacementState first = copiedStates.get(0);
		copiedStates.set(0, new PlacementState(first.execType(), first.output(), first.fType(),
			first.shapeDependent()));
		Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
			owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
				copiedStates, owner.selection.obligationReceiptsInOrder(), MinStExactSelection.UNIQUE,
				owner.selection.minimaCertificates())));

		Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
			owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
				foreign.selection.selectedStatesInScopeOrder(), List.of(), MinStExactSelection.UNIQUE,
				owner.selection.minimaCertificates())));

		List<List<Long>> staleCertificate = List.of(List.of(1234567L));
		Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
			owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
				owner.selection.selectedStatesInScopeOrder(), owner.selection.obligationReceiptsInOrder(),
				MinStExactSelection.UNIQUE, staleCertificate)));

		if(!owner.selection.obligationReceiptsInOrder().isEmpty()) {
			var exact = owner.selection.obligationReceiptsInOrder().get(0);
			var copied = obligation(exact.direction(), exact.producerKey(), foreign.facts.orderedScope().get(0),
				exact.inputPosition(), exact.requiredPlacement(), exact.actionSignature());
			Assert.assertThrows(IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(
				owner.facts, selection(owner.selection.objectiveBits(), owner.selection.sourcePartitionNodeIds(),
					owner.selection.selectedStatesInScopeOrder(), List.of(copied), MinStExactSelection.UNIQUE,
					owner.selection.minimaCertificates())));
		}
	}

	private static MinStPlacementAdapter.Selection replayWithSelectedHopState(Projection projection) {
		List<ExecType> oldExec = projection.analysis.compiledHopOccurrences().stream()
			.map(o -> o.hop().getExecType()).toList();
		List<ExecType> oldForced = projection.analysis.compiledHopOccurrences().stream()
			.map(o -> o.hop().getForcedExecType()).toList();
		List<FederatedOutput> oldOutput = projection.analysis.compiledHopOccurrences().stream()
			.map(o -> o.hop().getFederatedOutput()).toList();
		try {
			for(int i = 0; i < projection.analysis.compiledHopOccurrences().size(); i++) {
				var occurrence = projection.analysis.compiledHopOccurrences().get(i);
				PlacementState state = projection.selection.selectedStatesInScopeOrder().get(i);
				occurrence.hop().clearForcedExecType();
				occurrence.hop().setExecType(state.execType());
				occurrence.hop().setFederatedOutput(state.output());
			}
			return new MinStPlacementAdapter().select(projection.analysis, projection.input);
		}
		finally {
			for(int i = 0; i < projection.analysis.compiledHopOccurrences().size(); i++) {
				var hop = projection.analysis.compiledHopOccurrences().get(i).hop();
				hop.setExecType(oldExec.get(i));
				if(oldForced.get(i) == null)
					hop.clearForcedExecType();
				else
					hop.setForcedExecType(oldForced.get(i));
				hop.setFederatedOutput(oldOutput.get(i));
			}
		}
	}

	private static void assertObligationProjectionUsesExactHopIds(String id, Projection projection) {
		for(MinStPlacementInput.ObligationReceipt obligation : projection.input.obligationReceipts()) {
			Assert.assertTrue(id, obligation.kind().equals("U") || obligation.kind().equals("D"));
			Assert.assertEquals(id, obligation.childHopId(), obligation.originalHopId());
			Assert.assertNotEquals(id, 0L, obligation.childHopId());
			Assert.assertTrue(id, obligation.capability());
			Assert.assertTrue(id, obligation.capabilityReason().contains("proven by exact neutral action"));
			Assert.assertTrue(id, obligation.reason().equals("CP/LOUT child has active FED consumers")
				|| obligation.reason().equals("FED/FOUT child has active LOCAL consumers"));
			Assert.assertNotNull(id, obligation.fType());
			Assert.assertEquals(id, new LinkedHashSet<>(obligation.consumerHopIds()).size(),
				obligation.consumerHopIds().size());
			Assert.assertFalse(id, obligation.consumerHopIds().isEmpty());
			Set<Long> exactHopIds = projection.analysis.occurrences().stream()
				.map(o -> o.hop().getHopID()).collect(java.util.stream.Collectors.toSet());
			Assert.assertTrue(id, exactHopIds.contains(obligation.childHopId()));
			Assert.assertTrue(id, exactHopIds.containsAll(obligation.consumerHopIds()));
		}
	}

	private static Projection project(String id) throws Exception {
		PlacementAnalysis analysis = switch(id) {
			case "ACTUAL-ROOT" -> actualRootAnalysis();
			case "OCCURRENCE" -> occurrenceAnalysis();
			default -> new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(id));
		};
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		MinStExactSelection selection = MinStExactSelector.select(facts);
		MinStPlacementInput input = MinStExactPlacementProjector.project(facts, selection);
		return new Projection(analysis, facts, selection, input);
	}

	private static PlacementAnalysis actualRootAnalysis() throws Exception {
		return buildAnalysis(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"write(X,\"out-r7-selector-projector\",format=\"binary\");") + "\n");
	}

	private static PlacementAnalysis occurrenceAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r7-occurrence-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"Yout1=X+S;", "Yout2=X-S;",
				"write(Yout1,\"out-1\",format=\"binary\");",
				"write(Yout2,\"out-2\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis buildAnalysis(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
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

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
	}

	private static MinStExactSelection selection(long objectiveBits, List<Long> source,
		List<PlacementState> states, List<MinStExactSelection.ObligationReceipt> obligations,
		String tie, List<List<Long>> minima) {
		try {
			Constructor<MinStExactSelection> constructor = MinStExactSelection.class.getDeclaredConstructor(
				long.class, List.class, List.class, List.class, String.class, List.class);
			constructor.setAccessible(true);
			return constructor.newInstance(objectiveBits, source, states, obligations, tie, minima);
		}
		catch(Exception ex) {
			throw new AssertionError("Unable to build MinStExactSelection", ex);
		}
	}

	private static MinStExactSelection.ObligationReceipt obligation(Direction direction,
		CompiledHopKey producer, CompiledHopKey consumer, int inputPosition, PlacementState required,
		String actionSignature) {
		try {
			Constructor<MinStExactSelection.ObligationReceipt> constructor =
				MinStExactSelection.ObligationReceipt.class.getDeclaredConstructor(Direction.class,
					CompiledHopKey.class, CompiledHopKey.class, int.class, PlacementState.class,
					String.class);
			constructor.setAccessible(true);
			return constructor.newInstance(direction, producer, consumer, inputPosition, required,
				actionSignature);
		}
		catch(Exception ex) {
			throw new AssertionError("Unable to build obligation receipt", ex);
		}
	}

	private record Projection(PlacementAnalysis analysis, MinStExactCostFacts facts,
		MinStExactSelection selection, MinStPlacementInput input) {
		private Projection {
			Objects.requireNonNull(analysis);
			Objects.requireNonNull(facts);
			Objects.requireNonNull(selection);
			Objects.requireNonNull(input);
		}
	}
}

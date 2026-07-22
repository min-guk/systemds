/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.junit.Assert;
import org.junit.Test;

/** Structural RED for the real persistent-read heavy-MM upload authority boundary. */
public class CampaignBMinStDuplicateObligationRedTest {
	@Test
	public void duplicateSelectedObligationIsRejected() throws Exception {
		Path directory = Files.createTempDirectory("minst-g014-heavy-mm-");
		String input = directory.resolve("S").toString();
		writePersistentMatrix(input);
		try {
			PlacementAnalysis analysis = buildAnalysis(script(input));
			List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
			MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
			AuxiliaryGroupFact upload = facts.auxiliaryGroupsInCanonicalOrder().stream()
				.filter(group -> isPersistentReadUpload(analysis, group)).findFirst()
				.orElseThrow(() -> new AssertionError("G014_HEAVY_MM_UPLOAD_GROUP_MISSING"));
			var endpoint = upload.endpointsInCanonicalOrder().stream()
				.filter(candidate -> candidate.inputPosition() == 1).findFirst()
				.orElseThrow(() -> new AssertionError("G014_HEAVY_MM_INPUT1_ENDPOINT_MISSING"));
			MinStExactSelection selection = MinStExactSelector.select(facts);
			Assert.assertEquals("UNIQUE", selection.tieCertificate());
			long selected = selection.obligationReceiptsInOrder().stream()
				.filter(receipt -> receipt.direction() == Direction.UPLOAD
					&& receipt.producerKey().equals(upload.producerKey())
					&& receipt.consumerKey().equals(endpoint.consumerKey())
					&& receipt.inputPosition() == endpoint.inputPosition()).count();
			Assert.assertEquals("G014_HEAVY_MM_SELECTED_UPLOAD_COUNT", 1L, selected);
			var receipt = selection.obligationReceiptsInOrder().stream().filter(r -> r.direction() == Direction.UPLOAD && r.producerKey().equals(upload.producerKey()) && r.consumerKey().equals(endpoint.consumerKey()) && r.inputPosition() == endpoint.inputPosition()).findFirst().orElseThrow();
			List<MinStExactSelection.ObligationReceipt> dup = new java.util.ArrayList<>(selection.obligationReceiptsInOrder());
			dup.add(receipt);
			MinStExactSelection forged = new MinStExactSelection(selection.objectiveBits(), selection.sourcePartitionNodeIds(), selection.selectedStatesInScopeOrder(), dup, selection.tieCertificate(), selection.minimumSourcePartitionCertificates());
			Assert.assertThrows("duplicate endpoint must be rejected", IllegalArgumentException.class, () -> MinStExactPlacementProjector.project(facts, forged));
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static boolean isPersistentReadUpload(PlacementAnalysis analysis, AuxiliaryGroupFact group) {
		if(group.direction() != Direction.UPLOAD || group.endpointsInCanonicalOrder().stream()
			.noneMatch(endpoint -> endpoint.inputPosition() == 1))
			return false;
		return analysis.hop(group.producerKey()).map(hop -> hop.getName().equals("S")
			&& hop.getClass().getSimpleName().equals("DataOp"))
			.orElse(false);
	}

	private static PlacementAnalysis buildAnalysis(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static String script(String input) {
		return String.join("\n",
			"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\",rows=10000,cols=2,format=\"binary\");",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(5000,10000),list(5000,0),list(10000,10000)));",
			"Y=X%*%S;", "write(Y,\"g014-heavy-mm-out\",format=\"binary\");") + "\n";
	}

	private static void writePersistentMatrix(String input) throws Exception {
		MatrixBlock block = new MatrixBlock(10000, 2, 3.0);
		MatrixCharacteristics characteristics = new MatrixCharacteristics(10000, 2, 1024,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, input,
			10000, 2, 1024, block.getNonZeros());
		HDFSTool.writeMetaDataFile(input + ".mtd", ValueType.FP64, null,
			DataType.MATRIX, characteristics, FileFormat.BINARY, null, "private");
	}
}

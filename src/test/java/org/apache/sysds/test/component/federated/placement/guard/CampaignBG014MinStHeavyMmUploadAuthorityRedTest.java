/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.test.component.federated.placement.guard;

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
public class CampaignBG014MinStHeavyMmUploadAuthorityRedTest {
	@Test
	public void heavyMmRealUploadReachesExactObligationAuthorityBoundary() throws Exception {
		Path directory = Files.createTempDirectory("minst-g014-heavy-mm-");
		String input = directory.resolve("S").toString();
		writePersistentMatrix(input);
		try {
			PlacementAnalysis analysis = buildAnalysis(script(input));
			List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
			MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
			Assert.assertTrue("G014_HEAVY_MM_UPLOAD_GROUP_MISSING", facts.auxiliaryGroupsInCanonicalOrder()
				.stream().anyMatch(group -> isPersistentReadUpload(analysis, group)));
			IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
				() -> MinStExactSelector.select(facts));
			Assert.assertTrue("G014_HEAVY_MM_MUST_FAIL_SELECTED_UPLOAD_AUTHORITY",
				failure.getMessage().startsWith("MINST_EXACT_OBLIGATION_AUTHORITY_MISSING")
					&& failure.getMessage().contains("input=1"));
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

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
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

/** RED for MinST-local occurrence weighting and multi-parent upload pricing. */
public class CampaignBR4MinStOccurrenceDemandRedTest {
	@Test
	public void loopOccurrenceAndMultiParentDemandArePricedInsideMinStProjection() throws Exception {
		PlacementAnalysis analysis = occurrenceAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));

		CompiledHopKey outsideConsumer = keyByName(analysis, "Yout1");
		CompiledHopKey loopConsumer = loopBinaryConsumerOfLocalRead(analysis, "Sloop", "b(-)");
		Assert.assertFalse("R4_MINST_OUTSIDE_CONSUMER_MISCLASSIFIED",
			isLoopOccurrence(outsideConsumer));
		Assert.assertTrue("R4_MINST_LOOP_CONSUMER_IDENTITY_MISSING",
			isLoopOccurrence(loopConsumer));

		AuxiliaryGroupFact outsideGroup = uploadGroupContaining(facts, outsideConsumer);
		AuxiliaryGroupFact loopGroup = uploadGroupContaining(facts, loopConsumer);
		Assert.assertTrue("R4_MINST_OUTSIDE_MULTI_PARENT_GROUP_MISSING",
			outsideGroup.endpointsInCanonicalOrder().size() >= 2);
		Assert.assertTrue("R4_MINST_LOOP_MULTI_PARENT_GROUP_MISSING",
			loopGroup.endpointsInCanonicalOrder().size() >= 2);
		Assert.assertEquals("R4_MINST_FIXTURE_WORKER_COUNT_DRIFT", 2, workerCount(analysis));
		outsideGroup.endpointsInCanonicalOrder().forEach(candidate -> assertCanonicalEdge(analysis, candidate));
		loopGroup.endpointsInCanonicalOrder().forEach(candidate -> assertCanonicalEdge(analysis, candidate));

		EndpointFact outsideEndpoint = endpoint(outsideGroup, outsideConsumer);
		EndpointFact loopEndpoint = endpoint(loopGroup, loopConsumer);

		double outsideDemand = Double.longBitsToDouble(outsideEndpoint.demandCostBits());
		double loopDemand = Double.longBitsToDouble(loopEndpoint.demandCostBits());
		double expectedOutside = expectedUploadDemand(analysis, outsideGroup, 1.0);
		double expectedLoop = expectedUploadDemand(analysis, loopGroup, 2.0);

		List<String> failures = new ArrayList<>();
		checkBits(failures, "R4_MINST_OUTSIDE_FORWARDING_PENALTY_MISSING",
			expectedOutside, outsideDemand);
		checkBits(failures, "R4_MINST_LOOP_OCCURRENCE_WEIGHT_MISSING",
			expectedLoop, loopDemand);
		if(!(loopDemand > outsideDemand))
			failures.add("R4_MINST_LOOP_DEMAND_NOT_GREATER|outside=" + outsideDemand
				+ "|loop=" + loopDemand);
		checkBits(failures, "R4_MINST_OUTSIDE_OR_PRICE_NOT_WEIGHTED_MAX",
			expectedOutside, Double.longBitsToDouble(outsideGroup.priceBits()));
		checkBits(failures, "R4_MINST_LOOP_OR_PRICE_NOT_WEIGHTED_MAX",
			expectedLoop, Double.longBitsToDouble(loopGroup.priceBits()));
		Assert.assertEquals("R4_MINST_OUTSIDE_OR_PRICE_NOT_ENDPOINT_MAX",
			maxDemandBits(outsideGroup), outsideGroup.priceBits());
		Assert.assertEquals("R4_MINST_LOOP_OR_PRICE_NOT_ENDPOINT_MAX",
			maxDemandBits(loopGroup), loopGroup.priceBits());
		Assert.assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	private static void checkBits(List<String> failures, String marker,
		double expected, double actual) {
		if(Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
			failures.add(marker + "|expected=" + expected + "|actual=" + actual);
	}

	private static double expectedUploadDemand(PlacementAnalysis analysis,
		AuxiliaryGroupFact group, double occurrenceWeight) {
		Hop producer = analysis.hop(group.producerKey()).orElseThrow();
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(producer);
		if(!Double.isFinite(bytes) || bytes <= 0.0)
			bytes = analysis.shapeFact(group.producerKey())
				.filter(shape -> shape.rows() > 0 && shape.cols() > 0)
				.map(shape -> (double)shape.rows() * shape.cols() * 8.0)
				.orElseThrow(() -> new AssertionError("R4_MINST_PRODUCER_BYTES_UNPROVEN"));
		int workers = workerCount(analysis);
		double upload = FederatedCostModel.computeUploadNetworkCost(bytes,
			group.conversionType(), workers);
		double penalty = FederatedCostModel.computeLocalToFedForwardingPenalty(
			group.conversionType(), workers);
		return occurrenceWeight * (upload + penalty);
	}

	private static int workerCount(PlacementAnalysis analysis) {
		Set<String> workers = new LinkedHashSet<>();
		analysis.graph().nodes().stream().flatMap(node -> node.anchors().stream())
			.flatMap(anchor -> anchor.partitions().stream())
			.map(partition -> partition.workerId()).forEach(workers::add);
		return workers.size();
	}

	private static long maxDemandBits(AuxiliaryGroupFact group) {
		double max = group.endpointsInCanonicalOrder().stream()
			.mapToDouble(endpoint -> Double.longBitsToDouble(endpoint.demandCostBits()))
			.max().orElseThrow();
		return Double.doubleToRawLongBits(max);
	}

	private static void assertCanonicalEdge(PlacementAnalysis analysis, EndpointFact endpoint) {
		CompiledInputEdgeFact edge = analysis.requireExactCompiledInputEdge(endpoint.producerKey(),
			endpoint.consumerKey(), endpoint.inputPosition());
		Assert.assertSame("R4_MINST_ENDPOINT_PRODUCER_IDENTITY_DRIFT", endpoint.producerKey(), edge.producer());
		Assert.assertSame("R4_MINST_ENDPOINT_CONSUMER_IDENTITY_DRIFT", endpoint.consumerKey(), edge.consumer());
		Assert.assertEquals("R4_MINST_ENDPOINT_INPUT_POSITION_DRIFT",
			endpoint.inputPosition(), edge.inputPosition());
	}

	private static AuxiliaryGroupFact uploadGroupContaining(MinStExactCostFacts facts,
		CompiledHopKey consumer) {
		return facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.UPLOAD)
			.filter(group -> group.endpointsInCanonicalOrder().stream()
				.anyMatch(endpoint -> endpoint.consumerKey() == consumer))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R4_MINST_UPLOAD_GROUP_MISSING|consumer=" + consumer.normalizedSignature()
					+ "|legal=" + facts.decisionFactsInScopeOrder().stream()
						.filter(decision -> decision.key() == consumer)
						.map(decision -> decision.legalStatesInCanonicalOrder().toString()).toList()
					+ "|groups=" + facts.auxiliaryGroupsInCanonicalOrder().stream().map(group ->
						group.direction() + ":" + group.endpointsInCanonicalOrder().stream()
							.map(endpoint -> facts.analysis().hop(endpoint.consumerKey()).orElseThrow().getName())
							.toList()).toList()));
	}

	private static EndpointFact endpoint(AuxiliaryGroupFact group, CompiledHopKey consumer) {
		return group.endpointsInCanonicalOrder().stream()
			.filter(candidate -> candidate.consumerKey() == consumer).findFirst()
			.orElseThrow(() -> new AssertionError("R4_MINST_ENDPOINT_MISSING"));
	}

	private static boolean isLoopOccurrence(CompiledHopKey key) {
		return key.controlRegion().regionPath().stream().anyMatch(part -> part.contains("/loop-body"));
	}

	private static CompiledHopKey keyByName(PlacementAnalysis analysis, String name) {
		return scope(analysis).stream()
			.filter(key -> analysis.hop(key).map(hop -> name.equals(hop.getName())).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("R4_MINST_HOP_MISSING|name=" + name
				+ "|observed=" + scope(analysis).stream().map(key -> analysis.hop(key).orElseThrow())
					.map(hop -> hop.getName() + '/' + hop.getOpString()).distinct().sorted().toList()));
	}

	private static CompiledHopKey loopBinaryConsumerOfLocalRead(PlacementAnalysis analysis,
		String readName, String opString) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> isLoopOccurrence(edge.consumer()))
			.filter(edge -> analysis.hop(edge.producer()).map(hop -> readName.equals(hop.getName())).orElse(false))
			.map(CompiledInputEdgeFact::consumer).distinct()
			.filter(key -> analysis.hop(key).map(hop -> opString.equals(hop.getOpString())).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R4_MINST_LOOP_BINARY_CONSUMER_MISSING|read=" + readName + "|op=" + opString + "|edges="
					+ analysis.compiledInputEdgesInCanonicalOrder().stream().map(edge ->
						analysis.hop(edge.producer()).orElseThrow().getName() + '/'
							+ analysis.hop(edge.producer()).orElseThrow().getOpString() + "->"
							+ analysis.hop(edge.consumer()).orElseThrow().getName() + '/'
							+ analysis.hop(edge.consumer()).orElseThrow().getOpString() + '@'
							+ edge.consumer().controlRegion().regionPath()).toList()));
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
	}

	private static PlacementAnalysis occurrenceAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-occurrence-");
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
				"write(Yout2,\"out-2\",format=\"binary\");",
				"for(i in 1:2) {",
				"  Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"  Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"  Yloop1=Xloop-Sloop;", "  Yloop2=Xloop+Sloop;",
				"  write(Yloop1,\"out-loop-1\",format=\"binary\");",
				"  write(Yloop2,\"out-loop-2\",format=\"binary\");", "}") + "\n";
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			return new NeutralPlacementGraphBuilder().buildAnalysis(program);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
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
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Field;
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
import org.apache.sysds.hops.LiteralOp;
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
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
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

		AuxiliaryGroupFact outsideDownload = transferGroupContaining(facts,
			Direction.DOWNLOAD, outsideConsumer, "X");
		AuxiliaryGroupFact loopDownload = transferGroupContaining(facts,
			Direction.DOWNLOAD, loopConsumer, "Xloop");
		double expectedOutsideDownload = expectedDownloadDemand(analysis, outsideDownload, 1.0);
		double expectedLoopDownload = expectedDownloadDemand(analysis, loopDownload, 2.0);
		checkBits(failures, "R4_MINST_OUTSIDE_DOWNLOAD_WEIGHT_DRIFT",
			expectedOutsideDownload, demand(outsideDownload, outsideConsumer));
		checkBits(failures, "R4_MINST_LOOP_DOWNLOAD_WEIGHT_MISSING",
			expectedLoopDownload, demand(loopDownload, loopConsumer));
		Assert.assertEquals("R4_MINST_OUTSIDE_DOWNLOAD_PRICE_NOT_ENDPOINT_MAX",
			maxDemandBits(outsideDownload), outsideDownload.priceBits());
		Assert.assertEquals("R4_MINST_LOOP_DOWNLOAD_PRICE_NOT_ENDPOINT_MAX",
			maxDemandBits(loopDownload), loopDownload.priceBits());
		Assert.assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	@Test
	public void functionLoopOccurrenceUsesTheFunctionControlPath() throws Exception {
		PlacementAnalysis analysis = functionLoopAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumer = loopBinaryConsumerOfLocalRead(analysis, "Sloop", "b(-)");
		Assert.assertTrue("R4_MINST_FUNCTION_NAMESPACE_MISSING",
			consumer.functionNamespace().contains("f"));
		AuxiliaryGroupFact group = uploadGroupContaining(facts, consumer);
		double expected = expectedUploadDemand(analysis, group, 2.0);
		Assert.assertEquals("R4_MINST_FUNCTION_LOOP_OCCURRENCE_WEIGHT_MISSING",
			Double.doubleToRawLongBits(expected), endpoint(group, consumer).demandCostBits());
	}

	@Test
	public void transientScalarLoopBoundUsesLexicalConstantContext() throws Exception {
		PlacementAnalysis analysis = transientBoundLoopAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumer = loopBinaryConsumerOfLocalRead(analysis, "Sloop", "b(-)");
		AuxiliaryGroupFact group = uploadGroupContaining(facts, consumer);
		double expected = expectedUploadDemand(analysis, group, 2.0);
		Assert.assertEquals("R4_MINST_TRANSIENT_LOOP_BOUND_WEIGHT_DRIFT",
			Double.doubleToRawLongBits(expected), endpoint(group, consumer).demandCostBits());
	}

	@Test
	public void nestedFunctionCallAggregatesEachDistinctCallerContext() throws Exception {
		PlacementAnalysis analysis = nestedFunctionLoopAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumer = loopBinaryConsumerOfLocalRead(analysis, "Sloop", "b(-)");
		Assert.assertTrue("R4_MINST_NESTED_FUNCTION_NAMESPACE_MISSING",
			consumer.functionNamespace().contains("g"));
		AuxiliaryGroupFact group = uploadGroupContaining(facts, consumer);
		double expected = expectedUploadDemand(analysis, group, 5.0);
		Assert.assertEquals("R4_MINST_NESTED_FUNCTION_CONTEXTS_NOT_AGGREGATED",
			Double.doubleToRawLongBits(expected), endpoint(group, consumer).demandCostBits());
	}

	@Test
	public void sameFunctionNameInDistinctNamespacesUsesQualifiedRoots() throws Exception {
		PlacementAnalysis analysis = namespacedFunctionLoopAnalysis();
		String functionA = functionRootEndingWith(analysis, "a.dml::f");
		String functionB = functionRootEndingWith(analysis, "b.dml::f");
		Assert.assertNotSame("R4_MINST_NAMESPACED_FUNCTION_ROOTS_COLLAPSED",
			analysis.namedFunctionStatementBlocks().get(functionA),
			analysis.namedFunctionStatementBlocks().get(functionB));

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumerA = loopBinaryConsumerOfLocalRead(analysis, "Sa", "b(-)");
		CompiledHopKey consumerB = loopBinaryConsumerOfLocalRead(analysis, "Sb", "b(+)");
		Assert.assertTrue("R4_MINST_NAMESPACE_A_CONSUMER_MISBOUND",
			consumerA.functionNamespace().contains(functionA));
		Assert.assertTrue("R4_MINST_NAMESPACE_B_CONSUMER_MISBOUND",
			consumerB.functionNamespace().contains(functionB));
		AuxiliaryGroupFact groupA = uploadGroupContaining(facts, consumerA);
		AuxiliaryGroupFact groupB = uploadGroupContaining(facts, consumerB);
		Assert.assertEquals("R4_MINST_NAMESPACE_A_OCCURRENCE_WEIGHT_DRIFT",
			Double.doubleToRawLongBits(expectedUploadDemand(analysis, groupA, 2.0)),
			endpoint(groupA, consumerA).demandCostBits());
		Assert.assertEquals("R4_MINST_NAMESPACE_B_OCCURRENCE_WEIGHT_DRIFT",
			Double.doubleToRawLongBits(expectedUploadDemand(analysis, groupB, 3.0)),
			endpoint(groupB, consumerB).demandCostBits());
	}

	private static String functionRootEndingWith(PlacementAnalysis analysis, String suffix) {
		List<String> matches = analysis.namedFunctionStatementBlocks().keySet().stream()
			.filter(key -> key.endsWith(suffix)).toList();
		Assert.assertEquals("R4_MINST_QUALIFIED_FUNCTION_ROOT_MISSING|suffix=" + suffix
			+ "|roots=" + analysis.namedFunctionStatementBlocks().keySet(), 1, matches.size());
		return matches.get(0);
	}

	@Test
	public void functionStructureMutationInvalidatesTheAnalysis() throws Exception {
		PlacementAnalysis analysis = functionLoopAnalysis();
		FunctionStatementBlock block = analysis.namedFunctionStatementBlocks().get("f");
		FunctionStatement function = (FunctionStatement)block.getStatement(0);
		StatementBlock injected = new StatementBlock();
		injected.setHops(new ArrayList<>(List.of(new LiteralOp(7L))));
		function.getBody().add(injected);
		try {
			analysis.assertProgramStructureUnchanged();
			Assert.fail("R4_PLACEMENT_ANALYSIS_FUNCTION_MUTATION_NOT_DETECTED");
		}
		catch(IllegalStateException ex) {
			Assert.assertTrue("R4_PLACEMENT_ANALYSIS_FUNCTION_MUTATION_WRONG_FAILURE|" + ex.getMessage(),
				ex.getMessage().contains("PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED"));
		}
	}

	@Test
	public void compatibilityCopyDoesNotExposeUnguardedFunctionRoots() throws Exception {
		PlacementAnalysis analysis = functionLoopAnalysis();
		PlacementAnalysis copy = CampaignBPlacementAnalysisFixtureBridge.withProjectionOrder(
			analysis, programOwner(analysis), ProjectionOrder.NORMAL);
		Assert.assertTrue("R4_COMPATIBILITY_ANALYSIS_EXPOSED_UNGUARDED_FUNCTION_ROOTS",
			copy.namedFunctionStatementBlocks().isEmpty());
		Assert.assertFalse("R4_COMPATIBILITY_ANALYSIS_CLAIMED_GUARDED_FUNCTION_ROOTS",
			copy.hasGuardedFunctionRoots());
		try {
			MinStExactCostFactsProducer.derive(copy, scope(copy));
			Assert.fail("R4_COMPATIBILITY_ANALYSIS_SILENTLY_DROPPED_FUNCTION_CONTEXT");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertTrue("R4_COMPATIBILITY_ANALYSIS_WRONG_FUNCTION_FAILURE|" + ex.getMessage(),
				ex.getMessage().contains("MINST_GUARDED_FUNCTION_ROOTS_REQUIRED"));
		}
	}

	@Test
	public void uncalledHelperHasNoExecutableMinStTransferDemand() throws Exception {
		PlacementAnalysis analysis = uncalledFunctionAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		List<CompiledHopKey> unused = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> key.functionNamespace().contains("unused")).toList();
		for(CompiledHopKey key : unused)
			Assert.assertEquals("R4_MINST_UNUSED_FUNCTION_NOT_NON_EMITTED",
				org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED,
				analysis.graph().node(key).orElseThrow().kind());
		Assert.assertTrue("R4_MINST_UNUSED_FUNCTION_TRANSFER_WAS_PRICED",
			facts.auxiliaryGroupsInCanonicalOrder().stream().flatMap(group ->
				group.endpointsInCanonicalOrder().stream()).noneMatch(endpoint ->
					endpoint.consumerKey().functionNamespace().contains("unused")
						|| endpoint.producerKey().functionNamespace().contains("unused")));
	}

	@Test
	public void recursiveFunctionContextFailsClosedInsteadOfTruncatingExpansion() throws Exception {
		PlacementAnalysis analysis = recursiveFunctionAnalysis();
		try {
			MinStExactCostFactsProducer.derive(analysis, scope(analysis));
			Assert.fail("R4_MINST_RECURSIVE_FUNCTION_CONTEXT_WAS_SILENTLY_TRUNCATED");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertTrue("R4_MINST_RECURSIVE_FUNCTION_CONTEXT_WRONG_FAILURE|" + ex.getMessage(),
				ex.getMessage().contains("MINST_RECURSIVE_FUNCTION_CONTEXT_UNSUPPORTED"));
		}
	}

	private static void checkBits(List<String> failures, String marker,
		double expected, double actual) {
		if(Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
			failures.add(marker + "|expected=" + expected + "|actual=" + actual);
	}

	private static double expectedUploadDemand(PlacementAnalysis analysis,
		AuxiliaryGroupFact group, double occurrenceWeight) {
		double bytes = estimatedBytes(analysis, group);
		int workers = workerCount(analysis);
		double upload = FederatedCostModel.computeUploadNetworkCost(bytes,
			group.conversionType(), workers);
		double penalty = FederatedCostModel.computeLocalToFedForwardingPenalty(
			group.conversionType(), workers);
		return occurrenceWeight * (upload + penalty);
	}

	private static double expectedDownloadDemand(PlacementAnalysis analysis,
		AuxiliaryGroupFact group, double occurrenceWeight) {
		return occurrenceWeight * FederatedCostModel.computeDownloadNetworkCost(
			estimatedBytes(analysis, group), group.conversionType(), workerCount(analysis));
	}

	private static double estimatedBytes(PlacementAnalysis analysis, AuxiliaryGroupFact group) {
		Hop producer = analysis.hop(group.producerKey()).orElseThrow();
		double bytes = producer.getOutputMemEstimate();
		if(Double.isFinite(bytes) && bytes > 0.0)
			return bytes;
		return analysis.shapeFact(group.producerKey())
			.filter(shape -> shape.rows() > 0 && shape.cols() > 0)
			.map(shape -> (double)shape.rows() * shape.cols() * 8.0)
			.orElseThrow(() -> new AssertionError("R4_MINST_PRODUCER_BYTES_UNPROVEN"));
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

	private static double demand(AuxiliaryGroupFact group, CompiledHopKey consumer) {
		return Double.longBitsToDouble(endpoint(group, consumer).demandCostBits());
	}

	private static AuxiliaryGroupFact transferGroupContaining(MinStExactCostFacts facts,
		Direction direction, CompiledHopKey consumer, String producerName) {
		return facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == direction)
			.filter(group -> facts.analysis().hop(group.producerKey())
				.map(hop -> producerName.equals(hop.getName())).orElse(false))
			.filter(group -> group.endpointsInCanonicalOrder().stream()
				.anyMatch(endpoint -> endpoint.consumerKey() == consumer))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R4_MINST_TRANSFER_GROUP_MISSING|direction=" + direction
					+ "|producer=" + producerName + "|consumer=" + consumer.normalizedSignature()));
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
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis functionLoopAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-function-loop-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"f=function(integer n) return(matrix[double] Y){",
				"  for(i in 1:n) {",
				"    Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xloop-Sloop;", "  }", "}", "Z=f(2);",
				"write(Z,\"out-function\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis transientBoundLoopAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-transient-bound-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n", "n=2;", "for(i in 1:n) {",
				"  Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"  Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"  Y=Xloop-Sloop;", "}", "write(Y,\"out-transient\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis nestedFunctionLoopAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-nested-function-loop-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"g=function(integer m) return(matrix[double] Y){",
				"  for(i in 1:m) {",
				"    Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xloop-Sloop;", "  }", "}",
				"f=function(integer n) return(matrix[double] Y){", "  Y=g(n);", "}",
				"A=f(2);", "B=f(3);",
				"write(A,\"out-nested-a\",format=\"binary\");",
				"write(B,\"out-nested-b\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis namespacedFunctionLoopAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-namespaced-function-loop-");
		String input = directory.resolve("S").toString();
		Path libraryA = directory.resolve("a.dml");
		Path libraryB = directory.resolve("b.dml");
		writePrivateLocalMatrix(input);
		try {
			Files.writeString(libraryA, String.join("\n",
				"f=function(integer n) return(matrix[double] Y){",
				"  for(i in 1:n) {",
				"    Sa=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xa=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xa-Sa;", "  }", "}") + "\n");
			Files.writeString(libraryB, String.join("\n",
				"f=function(integer n) return(matrix[double] Y){",
				"  for(i in 1:n) {",
				"    Sb=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xb=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xb+Sb;", "  }", "}") + "\n");
			String script = String.join("\n",
				"source(\"" + libraryA + "\") as A",
				"source(\"" + libraryB + "\") as B",
				"YA=A::f(2);", "YB=B::f(3);",
				"write(YA,\"out-ns-a\",format=\"binary\");",
				"write(YB,\"out-ns-b\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(libraryA);
			Files.deleteIfExists(libraryB);
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis uncalledFunctionAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r4-uncalled-function-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"unused=function(integer n) return(matrix[double] Y){",
				"  for(i in 1:n) {",
				"    Sdead=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xdead=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xdead-Sdead;", "  }", "}",
				"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"Z=X-S;", "write(Z,\"out-main\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis recursiveFunctionAnalysis() throws Exception {
		String script = String.join("\n",
			"f=function(integer n) return(integer y){",
			"  if(n <= 0) {", "    y=0;", "  } else {", "    y=f(n-1);", "  }", "}",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"z=f(2);", "print(z);", "write(X,\"out-recursive\",format=\"binary\");") + "\n";
		return buildAnalysis(script);
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

	private static DMLProgram programOwner(PlacementAnalysis analysis) throws Exception {
		Field field = PlacementAnalysis.class.getDeclaredField("programOwner");
		field.setAccessible(true);
		return (DMLProgram)field.get(analysis);
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

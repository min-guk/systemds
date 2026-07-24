/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
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
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.junit.Assert;
import org.junit.Test;

/** R3 RED for exact MinST projection ownership of neutral compiled input edges. */
public class CampaignBR3MinStExactProjectionBoundaryRedTest {
	@Test
	public void projectionUseOwnsTheExactCanonicalInputEdgeCarrier() throws Exception {
		Class<?> use = Arrays.stream(MinStExactCostFactsProducer.class.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("Use")).findFirst()
			.orElseThrow(() -> new AssertionError("R3_MINST_EXACT_EDGE_USE_MISSING"));
		List<Field> edgeFields = Arrays.stream(use.getDeclaredFields())
			.filter(field -> field.getType() == CompiledInputEdgeFact.class).toList();
		Assert.assertEquals("R3_MINST_GLOBAL_HOP_SCAN_REMAINS|Use must own exactly one canonical edge fact",
			1, edgeFields.size());
		Assert.assertFalse("R3_MINST_GLOBAL_HOP_SCAN_REMAINS|Use must not retain a Hop identity",
			Arrays.stream(use.getDeclaredFields()).anyMatch(field -> field.getType() == Hop.class));
	}

	@Test
	public void auxiliaryEndpointsRetainAndResolveTheirExactCanonicalEdgeIdentity() throws Exception {
		assertTypedAccessor(EndpointFact.class, "producerKey", CompiledHopKey.class,
			"R3_MINST_AUX_ENDPOINT_EDGE_IDENTITY_INCOMPLETE");
		assertTypedAccessor(EndpointFact.class, "consumerKey", CompiledHopKey.class,
			"R3_MINST_AUX_ENDPOINT_EDGE_IDENTITY_INCOMPLETE");
		assertTypedAccessor(EndpointFact.class, "inputPosition", int.class,
			"R3_MINST_AUX_ENDPOINT_EDGE_IDENTITY_INCOMPLETE");

		PlacementAnalysis analysis = persistentReadAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		Assert.assertFalse("R3_MINST_AUX_ENDPOINT_FIXTURE_MISSING",
			facts.auxiliaryGroupsInCanonicalOrder().isEmpty());
		for(AuxiliaryGroupFact group : facts.auxiliaryGroupsInCanonicalOrder())
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
				CompiledHopKey producerKey = key(endpoint, "producerKey");
				Assert.assertSame("R3_MINST_AUX_ENDPOINT_PRODUCER_DRIFT",
					group.producerKey(), producerKey);
				CompiledInputEdgeFact edge = analysis.requireExactCompiledInputEdge(producerKey,
					endpoint.consumerKey(), endpoint.inputPosition());
				Assert.assertSame("R3_MINST_AUX_ENDPOINT_PRODUCER_IDENTITY", producerKey, edge.producer());
				Assert.assertSame("R3_MINST_AUX_ENDPOINT_CONSUMER_IDENTITY", endpoint.consumerKey(), edge.consumer());
			}
	}

	@Test
	public void sharedHopIdentityCannotRedirectTheCanonicalMinStUseSet() throws Exception {
		PlacementAnalysis source = persistentReadAnalysis();
		CompiledHopKey consumer = keyByName(source, "Y1");
		CompiledHopKey localRead = source.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer && edge.inputPosition() == 1)
			.map(CompiledInputEdgeFact::producer).findFirst().orElseThrow();
		CompiledHopKey federatedInput = source.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer && edge.inputPosition() == 0)
			.map(CompiledInputEdgeFact::producer).findFirst().orElseThrow();
		Assert.assertNotSame("R3_MINST_SHARED_HOP_TRAP_INPUTS_MUST_BE_DISTINCT",
			localRead, federatedInput);
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.replaceHop(source,
			localRead, source.hop(federatedInput).orElseThrow());
		Method capturedInvocation = MinStExactCostFactsProducer.class.getDeclaredMethod(
			"capturedInvocationEvidence", PlacementAnalysis.class, CompiledHopKey.class);
		capturedInvocation.setAccessible(true);
		InvocationTargetException failure = Assert.assertThrows(
			"R3_MINST_SHARED_HOP_TRAP_MUST_FAIL_CLOSED_ON_AMBIGUOUS_ANCHOR",
			InvocationTargetException.class,
			() -> capturedInvocation.invoke(null, analysis, localRead));
		Throwable cause = failure.getCause();
		Assert.assertTrue("R3_MINST_SHARED_HOP_TRAP_WRONG_FAILURE|" + cause,
			cause instanceof IllegalArgumentException
				&& cause.getMessage() != null
				&& cause.getMessage().contains("MINST_EXACT_INVOCATION_ANCHOR_AMBIGUOUS"));
	}

	@Test
	public void unprovenWorkerShapeAndLayoutMetadataCannotBecomeWorkerOneBroadcastZeroCost() {
		var fixture = CampaignBSelectorFixtureBridge.all().stream()
			.filter(candidate -> candidate.id().equals("S-01")).findFirst()
			.orElseThrow(() -> new AssertionError("R3_MINST_UNPROVEN_METADATA_FIXTURE_MISSING"));
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
		IllegalArgumentException failure = Assert.assertThrows(
			"R3_MINST_SILENT_DEFAULTS_REMAIN",
			IllegalArgumentException.class,
			() -> MinStExactCostFactsProducer.derive(analysis, scope(analysis)));
		Assert.assertTrue("R3_MINST_UNTYPED_MISSING_METADATA_FAILURE|" + failure,
			failure.getMessage() != null && failure.getMessage().contains("UNPROVEN"));
	}

	private static void assertTypedAccessor(Class<?> owner, String name, Class<?> returnType,
		String marker) throws Exception {
		Method method;
		try {
			method = owner.getMethod(name);
		}
		catch(NoSuchMethodException ex) {
			throw new AssertionError(marker + "|missing=" + owner.getSimpleName() + '#' + name, ex);
		}
		Assert.assertEquals(marker + "|type=" + name, returnType, method.getReturnType());
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
	}

	private static CompiledHopKey key(Object owner, String accessor) throws Exception {
		return (CompiledHopKey)owner.getClass().getMethod(accessor).invoke(owner);
	}

	private static CompiledHopKey keyByName(PlacementAnalysis analysis, String name) {
		return analysis.compiledHopOccurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).map(hop -> name.equals(hop.getName())).orElse(false))
			.findFirst().orElseThrow();
	}

	private static PlacementAnalysis persistentReadAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r3-exact-edge-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"Y1=X+S;", "Y2=X-S;", "write(Y1,\"out-1\",format=\"binary\");",
				"write(Y2,\"out-2\",format=\"binary\");") + "\n";
			DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
				script, new HashMap<>());
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

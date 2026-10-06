/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact native outputs from transient VALUE_MAP inputs retain their complete worker geometry. */
public class NativeOutputGeometryTest {
	private record ExactOutput(CandidateEmissionRealization realization,
		CandidateRealizationSupportClause clause) { }

	@Test
	public void colNativeOutputRetainsNonPartitionedRowExtent() throws Exception {
		PlacementAnalysis analysis = analyze(
			"A=federated(addresses=list(\"localhost:6234/A1\",\"localhost:6235/A2\"),"
				+ "ranges=list(list(0,0),list(8,2),list(0,2),list(8,4)));"
				+ "D=A;i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
				+ "print(sum(D));");
		List<CompiledHopKey> branchOperations = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& binary.getDataType().isMatrix()
				&& (binary.getOp() == OpOp2.PLUS || binary.getOp() == OpOp2.MINUS))
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.toList();
		Assert.assertEquals("fixture must retain both matrix-scalar branch operations", 2,
			branchOperations.size());

		for(CompiledHopKey branchOperation : branchOperations)
			assertExactNativeGeometry(analysis, branchOperation, FType.COL, List.of(
				partition("localhost:6234", 0, 0, 8, 2),
				partition("localhost:6235", 0, 2, 8, 4)));
	}

	@Test
	public void rowCbindNativeOutputRetainsExpandedColumnExtent() throws Exception {
		PlacementAnalysis analysis = analyze(
			"A=federated(addresses=list(\"localhost:6244/A1\",\"localhost:6245/A2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
				+ "D=A;i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
				+ "E=cbind(D,D);print(sum(E));");
		CompiledHopKey cbind = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> isCbind(occurrence.hop()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.findFirst().orElseThrow(AssertionError::new);

		assertExactNativeGeometry(analysis, cbind, FType.ROW, List.of(
			partition("localhost:6244", 0, 0, 4, 4),
			partition("localhost:6245", 4, 0, 8, 4)));
	}

	private static void assertExactNativeGeometry(PlacementAnalysis analysis, CompiledHopKey owner,
		FType expectedType, List<AnchorPartition> expectedPartitions) {
		List<ExactOutput> outputs = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == owner)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
			.flatMap(realization -> realization.supportClauses().stream()
				.map(clause -> new ExactOutput(realization, clause)))
			.filter(output -> output.realization().nativeWorkerPoolLayoutExact(output.clause()))
			.filter(output -> output.clause().inputBindings().stream().anyMatch(binding ->
				binding.kind() == CandidateInputBindingKind.DIRECT
					&& binding.source().realization().layoutKind() == PlacementLayoutKind.VALUE_MAP))
			.toList();
		Assert.assertFalse("fixture must publish an exact NATIVE_LINEAGE from a DIRECT VALUE_MAP input",
			outputs.isEmpty());
		for(ExactOutput output : outputs) {
			var witness = output.realization().nativeWorkerPoolResidencyWitness(output.clause());
			Assert.assertNotNull("exact native output requires a concrete worker map", witness);
			Assert.assertEquals(expectedType, witness.fType());
			Assert.assertEquals("exact output must preserve both partitioned and nonpartitioned extents",
				expectedPartitions, witness.partitions());
		}
	}

	private static AnchorPartition partition(String worker, long beginRow, long beginCol,
		long endRow, long endCol) {
		return new AnchorPartition(worker, List.of(beginRow, beginCol), List.of(endRow, endCol));
	}

	private static boolean isCbind(Hop hop) {
		return hop instanceof BinaryOp binary && binary.getOp() == OpOp2.CBIND
			|| hop instanceof NaryOp nary && nary.getOp() == OpOpN.CBIND;
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

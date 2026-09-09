/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.rewrite.ProgramRewriteStatus;
import org.apache.sysds.hops.rewrite.RewriteSplitDagDataDependentOperators;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class SharedPrivacyPlacementAnalysisContractTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void publicAggregateCannotCollectPrivateAggregateInputsForCpExecution() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var sum = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp aggregate
				&& aggregate.getOp() == AggOp.SUM).findFirst().orElseThrow();
		Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(sum.key()));
		var states = analysis.graph().node(sum.key()).orElseThrow().legalAlternatives();
		Assert.assertTrue("worker-side aggregation may release its public result",
			states.stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.LOUT));
		Assert.assertFalse("public aggregate output does not authorize raw input download",
			states.stream().anyMatch(state -> state.execType() == ExecType.CP));
		analysis.candidateRuleFacts().orderedFactsForParent(sum.key()).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream()).forEach(emission ->
				Assert.assertEquals("candidate authority must exclude the same CP payload release",
					ExecType.FED, emission.emissionState().placementState().execType()));
	}

	@Test
	public void privateAggregateDimensionsRemainCoordinatorMetadataOnly() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(nrow(A));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var dimensions = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof org.apache.sysds.hops.UnaryOp unary
				&& unary.getOp() == org.apache.sysds.common.Types.OpOp1.NROW)
			.findFirst().orElseThrow();
		Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(dimensions.key()));
		Assert.assertTrue(analysis.graph().node(dimensions.key()).orElseThrow().legalAlternatives()
			.stream().anyMatch(state -> state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.LOUT));
		var edge = analysis.compiledInputEdge(dimensions.key(), 0).orElseThrow();
		Assert.assertTrue("dimensions read FederationMap ranges, not private matrix values",
			analysis.isCoordinatorMetadataOnlyInput(edge));
	}

	@Test
	public void privateDataClosesOneExactOccurrenceDomainBeforeSelection() throws Exception {
		DMLProgram program = isolatedFederatedChain();
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		DataOp source = analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(DataOp.class::isInstance).map(DataOp.class::cast)
			.filter(data -> data.getOp() == OpOpData.FEDERATED).findFirst().orElseThrow();
		var sourceMetadata = FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
		Assert.assertEquals(2, sourceMetadata.partitions().size());
		Assert.assertTrue(sourceMetadata.partitions().stream()
			.allMatch(partition -> partition.getRight().getAddress() != null));
		Assert.assertEquals(2, analysis.numWorkers());
		Assert.assertEquals(analysis.graph().nodes().size(),
			analysis.privacyFactAuthority().orderedFacts().size());
		Assert.assertTrue("fixture must propagate strict privacy beyond the literal source",
			analysis.privacyFactAuthority().orderedFacts().stream()
				.filter(fact -> fact.privacy() == Privacy.PRIVATE).count() > 1);
		for(var node : analysis.graph().nodes()) {
			Assert.assertSame(node.key(), analysis.privacyFactAuthority()
				.requireExact(node.key()).occurrence());
			Assert.assertSame(node.valueVersion(), analysis.privacyFactAuthority()
				.requireExact(node.key()).valueVersion());
			if(analysis.requirePrivacy(node.key()) != Privacy.PRIVATE)
				continue;
			Assert.assertTrue("strict-private node retained a local or collected state: "
				+ node.normalizedIdentity(), node.legalAlternatives().stream().allMatch(state ->
					state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
		}
		Assert.assertTrue("privacy pruning must remain explicit in the shared graph",
			analysis.graph().nodes().stream().flatMap(node -> node.exclusions().stream())
				.anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.PRIVACY));
		Assert.assertTrue("privacy-denied candidate rows must remain auditable",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.anyMatch(fact -> fact.status() == CandidateEvaluationStatus.PRIVACY_EXCLUDED));
		analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> analysis.requirePrivacy(fact.key().parentOccurrence()) == Privacy.PRIVATE)
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream()).forEach(emission -> {
				PlacementState state = emission.emissionState().placementState();
				Assert.assertEquals(ExecType.FED, state.execType());
				Assert.assertEquals(FederatedOutput.FOUT, state.output());
			});
	}

	@Test
	public void plannerMetadataResolutionDoesNotRegisterRuntimeCleanupSites() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DataOp source = federatedSource(program);

		FederatedData.resetFederatedSites();
		try {
			FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
			Assert.assertEquals("pre-selector metadata must not create runtime CLEAR targets",
				0, registeredFederatedSiteCount());
		}
		finally {
			FederatedData.resetFederatedSites();
		}
	}

	@Test
	public void dataDependentDagSplitPreservesExactFederatedSourcePartitions() throws Exception {
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		try {
			DMLScript.setGlobalExecMode(ExecMode.HYBRID);
			DMLProgram program = compile(FEDERATED_SOURCE
				+ "C=table(A[,1],A[,2]);D=C+1;print(sum(D));print(sum(A));\n", false);
			Set<Hop> beforeSplit = Collections.newSetFromMap(new IdentityHashMap<>());
			ArrayDeque<Hop> pending = new ArrayDeque<>(program.getStatementBlock(0).getHops());
			while(!pending.isEmpty()) {
				Hop hop = pending.removeFirst();
				if(!beforeSplit.add(hop))
					continue;
				if(hop instanceof TernaryOp ternary
					&& ternary.getOp() == org.apache.sysds.common.Types.OpOp3.CTABLE) {
					ternary.setDim1(-1);
					ternary.setDim2(-1);
				}
				pending.addAll(hop.getInput());
			}
			RewriteSplitDagDataDependentOperators split = new RewriteSplitDagDataDependentOperators();
			program.setStatementBlocks(new ArrayList<>(split.rewriteStatementBlock(
				program.getStatementBlock(0), new ProgramRewriteStatus())));
			List<DataOp> sources = federatedSources(program);

			Assert.assertTrue("ctable with unknown output dimensions must split the statement block",
				program.getNumStatementBlocks() > 1);
			Set<Hop> firstBlockHops = reachableHops(program.getStatementBlock(0).getHops());
			for(int i = 1; i < program.getNumStatementBlocks(); i++) {
				Set<Hop> laterBlockHops = reachableHops(program.getStatementBlock(i).getHops());
				Assert.assertTrue("split DAGs must not retain shared Hop identities",
					Collections.disjoint(firstBlockHops, laterBlockHops));
			}
			Assert.assertFalse("split program must retain federated source occurrences", sources.isEmpty());
			for(DataOp source : sources) {
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(source, Privacy.PRIVATE_AGGREGATE);
				var metadata = FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
				Assert.assertEquals(2, metadata.partitions().size());
				Assert.assertArrayEquals(new long[] {0, 0},
					metadata.partitions().get(0).getLeft().getBeginDims());
				Assert.assertArrayEquals(new long[] {2, 2},
					metadata.partitions().get(0).getLeft().getEndDims());
				Assert.assertArrayEquals(new long[] {2, 0},
					metadata.partitions().get(1).getLeft().getBeginDims());
				Assert.assertArrayEquals(new long[] {4, 2},
					metadata.partitions().get(1).getLeft().getEndDims());
			}
		}
		finally {
			DMLScript.setGlobalExecMode(oldMode);
		}
	}

	@Test
	public void privateAggregateScalarRightIndexCannotReleaseARawCell() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE
			+ "x=as.scalar(A[1,1])+1;print(x);\n", true);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		// A one-cell selection is not an aggregation. The native FED/LOUT right-index
		// would expose a protected input value, so there is no legal local result.
		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	@Test
	public void branchLoopAndFunctionBoundariesShareTheSamePrivacyClosure() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);print(sum(C));\n";
		DMLProgram program = compile(script, false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		Assert.assertTrue("loop-body data must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.key().controlRegion().regionPath()
				.stream().anyMatch(path -> path.contains("loop-body"))
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("branch data must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.key().controlRegion().regionPath()
				.stream().anyMatch(path -> path.contains("branch-"))
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("function input boundary must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_INPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("function output boundary must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		for(var node : analysis.graph().nodes()) {
			if(analysis.requirePrivacy(node.key()) != Privacy.PRIVATE_AGGREGATE)
				continue;
			if(analysis.isDmlFunctionCallBoundary(node.key())) {
				Assert.assertEquals(NodeKind.FUNCTION_CALL, node.kind());
				continue;
			}
			if(!node.emittedWork() && node.legalAlternatives().isEmpty()) {
				// AST-inlined formal placeholders retain privacy/value identity but have
				// no runtime call carrier. This exception does not cover emitted CFG reads.
				Assert.assertEquals(NodeKind.FUNCTION_INPUT, node.kind());
				Assert.assertFalse(node.exclusions().isEmpty());
				Assert.assertTrue(node.exclusions().stream().allMatch(exclusion -> exclusion.reasonCode()
					== NeutralPlacementGraph.ReasonCode.NON_EMITTED_INLINED_FUNCTION_INPUT));
				continue;
			}
			Assert.assertFalse("protected data-bearing boundary must retain a legal remote state: "
				+ node.normalizedIdentity() + " kind=" + node.kind() + " exclusions=" + node.exclusions(),
				node.legalAlternatives().isEmpty());
			Assert.assertTrue("a function formal/CFG alias is not a coordinator call placeholder",
				node.legalAlternatives().stream().allMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT));
		}
	}

	@Test
	public void privateAggregateRecodeMetadataCannotExposeDistinctRawValues() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE
			+ "Fall=as.frame(A);jspec=\"{ids:true,dummycode:[1]}\";"
			+ "[X0,M]=transformencode(target=Fall,spec=jspec);print(sum(X0));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		// Dummycoding constructs recode maps containing distinct source values and
		// returns them in a local metadata frame. Neither their name nor the FED
		// primary result is an aggregate-release proof for those original values.
		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	@Test
	public void authorizedRecodeMetadataReleaseKeepsEncodedRowsProtected() throws Exception {
		String property = "sysds.privacy.allowPublicRecodeMetadata";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "true");
			DMLProgram program = compile(FEDERATED_SOURCE
				+ "Fall=as.frame(A);[X0,M]=transformencode(target=Fall,"
				+ "spec=\"{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}\");"
				+ "N=M;write(N,\"metadata\");C=A+nrow(M);print(sum(C));print(sum(X0));\n", false);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			int metadataOutputs = 0;
			int encodedOutputs = 0;
			int privateJoins = 0;
			for(var occurrence : analysis.compiledHopOccurrences()) {
				if(occurrence.hop() instanceof BinaryOp binary && binary.getOp() == OpOp2.PLUS) {
					privateJoins++;
					Assert.assertEquals("joining public metadata with protected rows stays protected",
						Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(occurrence.key()));
				}
				if(!(occurrence.hop() instanceof DataOp data) || data.getOp() != OpOpData.FUNCTIONOUTPUT)
					continue;
				var parent = FederatedPlannerUtils.getMultiReturnFunctionOutputParent(data);
				if(parent == null || !"transformencode".equalsIgnoreCase(parent.getFunctionName()))
					continue;
				var states = analysis.graph().node(occurrence.key()).orElseThrow().legalAlternatives();
				if(parent.getOutputs().get(1) == data) {
					metadataOutputs++;
					Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(occurrence.key()));
					Assert.assertTrue(states.stream().anyMatch(state -> state.execType() == ExecType.CP
						&& state.output() == FederatedOutput.LOUT));
				}
				else {
					encodedOutputs++;
					Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(occurrence.key()));
					Assert.assertTrue(states.stream().allMatch(state -> state.execType() == ExecType.FED
						&& state.output() == FederatedOutput.FOUT));
				}
			}
			Assert.assertTrue("private join must be observed", privateJoins > 0);
			Assert.assertTrue("metadata exact output must be observed", metadataOutputs > 0);
			Assert.assertTrue("encoded exact output must be observed", encodedOutputs > 0);
			for(var occurrence : analysis.compiledHopOccurrences())
				if(occurrence.hop() instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
					Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(occurrence.key()));
		}
		finally {
			if(prior == null) System.clearProperty(property);
			else System.setProperty(property, prior);
		}
	}

	@Test
	public void declarationAloneCannotReleaseRecodeMetadata() throws Exception {
		assertDeclaredMetadataRejected(false, Privacy.PRIVATE_AGGREGATE);
	}

	@Test
	public void authorizedDeclarationCannotReleaseStrictPrivateMetadata() throws Exception {
		assertDeclaredMetadataRejected(true, Privacy.PRIVATE);
	}

	@Test
	public void unresolvedDeclaredMetadataCannotBeReleased() throws Exception {
		assertDeclaredMetadataRejected(true, Privacy.PRIVATE_AGGREGATE,
			"jspec=\"{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}\";", "jspec");
	}

	@Test
	public void unsupportedMarkedEncoderCannotBeReleased() throws Exception {
		assertDeclaredMetadataRejected(true, Privacy.PRIVATE_AGGREGATE, "",
			"\"{ids:true,dummycode:[1],omit:[2],cofeePublicRecodeMetadata:true}\"");
	}

	private static void assertDeclaredMetadataRejected(boolean authorized, Privacy privacy) throws Exception {
		assertDeclaredMetadataRejected(authorized, privacy, "",
			"\"{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}\"");
	}

	private static void assertDeclaredMetadataRejected(boolean authorized, Privacy privacy,
		String prefix, String spec) throws Exception {
		String property = "sysds.privacy.allowPublicRecodeMetadata";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, Boolean.toString(authorized));
			DMLProgram program = compile(FEDERATED_SOURCE + prefix
				+ "Fall=as.frame(A);[X0,M]=transformencode(target=Fall,"
				+ "spec=" + spec + ");"
				+ "write(M,\"metadata\");print(sum(X0));\n", false);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
			Assert.assertThrows(DMLRuntimeException.class,
				() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		}
		finally {
			if(prior == null) System.clearProperty(property);
			else System.setProperty(property, prior);
		}
	}

	@Test
	public void unknownWidthRetainsSafeFederatedAggregateAndCenteringDomain() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE
			+ "colMean=colMeans(A);X=A-colMean;print(sum(X));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		federatedSource(program).setDim2(-1);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlacementAnalysis.HopOccurrenceProjection mean = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp aggregate
				&& aggregate.getOp() == AggOp.MEAN && aggregate.getDirection() == org.apache.sysds.common.Types.Direction.Col
				&& "colMean".equals(aggregate.getName()))
			.findFirst().orElseThrow();
		PlacementAnalysis.HopOccurrenceProjection centered = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& binary.getOp() == OpOp2.MINUS && "X".equals(binary.getName()))
			.findFirst().orElseThrow();

		Assert.assertTrue("fixture must retain its unknown compiled width",
			mean.hop().getInput(0).getDim2() < 0);
		assertHasState(analysis, mean, ExecType.FED, FederatedOutput.LOUT, FType.ROW);
		assertHasState(analysis, centered, ExecType.FED, FederatedOutput.FOUT, FType.ROW);
	}

	@Test
	public void transformEncodeWithLocalMetadataFailsClosedUnderStrictPrivacy() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE
			+ "Fall=as.frame(A);jspec=\"{ids:true,dummycode:[1]}\";"
			+ "[X0,M]=transformencode(target=Fall,spec=jspec);print(sum(X0));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	@Test
	public void privateCollectionFailsBeforeAnyPlannerSelectorCanRun() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "B=A+1;print(sum(B));\n", true);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	private static DMLProgram compile(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram isolatedFederatedChain() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n", false);
		DataOp source = federatedSource(program);
		BinaryOp plus = HopRewriteUtils.createBinary(source, new LiteralOp(1), OpOp2.PLUS);
		plus.setDim1(4);
		plus.setDim2(2);
		BinaryOp times = HopRewriteUtils.createBinary(plus, new LiteralOp(2), OpOp2.MULT);
		times.setDim1(4);
		times.setDim2(2);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(times)));
		program.getStatementBlocks().clear();
		program.getStatementBlocks().add(block);
		return program;
	}

	private static void assertHasState(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection occurrence, ExecType exec,
		FederatedOutput output, FType fType) {
		List<PlacementState> alternatives = analysis.graph().node(occurrence.key()).orElseThrow().legalAlternatives();
		List<String> candidateFacts = analysis.candidateRuleFacts().orderedFactsForParent(occurrence.key()).stream()
			.map(fact -> fact.key().orderedInputs() + "=>" + fact.capability() + "/" + fact.status()
				+ "/shapeProof=" + fact.shapeProof()
				+ "/emissions=" + fact.allowedEmissionFacts()).toList();
		List<String> graphDump = analysis.compiledHopOccurrences().stream().map(candidate -> candidate.hop().getHopID()
			+ ":" + candidate.hop().getOpString() + ":" + candidate.hop().getName() + "="
			+ analysis.graph().node(candidate.key()).orElseThrow().legalAlternatives()).toList();
		Assert.assertTrue("missing state " + exec + '/' + output + '/' + fType + " for " + occurrence.hop()
			+ "; alternatives=" + alternatives + "; candidates=" + candidateFacts + "; graph=" + graphDump,
			alternatives.stream()
				.anyMatch(state -> state.execType() == exec && state.output() == output && state.fType() == fType));
	}

	private static DataOp federatedSource(DMLProgram program) {
		return federatedSources(program).stream().findFirst()
			.orElseThrow(() -> new IllegalStateException("Fixture has no federated source"));
	}

	private static List<DataOp> federatedSources(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		List<DataOp> sources = new ArrayList<>();
		for(StatementBlock block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = reachableHops(pending);
		for(Hop hop : visited)
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				sources.add(data);
		return sources;
	}

	private static Set<Hop> reachableHops(Iterable<Hop> roots) {
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		roots.forEach(pending::add);
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			pending.addAll(hop.getInput());
		}
		return visited;
	}

	private static int registeredFederatedSiteCount() throws Exception {
		Field sites = FederatedData.class.getDeclaredField("_allFedSites");
		sites.setAccessible(true);
		return ((Set<?>) sites.get(null)).size();
	}
}

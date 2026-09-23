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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** A later function-boundary source widening must survive loop CFG replay. */
public class LoopSeedReplayWideningTest {
	@Test
	public void failedLoopSeedAttemptDoesNotConsumeItsRevision() {
		Set<String> seen = new LinkedHashSet<>();
		Map<String,String> eligible = Map.of("loop-read", "proof-input-revision");

		NeutralPlacementGraphBuilder.recordInstalledLoopSeedRevisions(
			eligible, Set.of(), seen);
		Assert.assertFalse("a rejected seed must remain retryable", seen.contains("proof-input-revision"));

		NeutralPlacementGraphBuilder.recordInstalledLoopSeedRevisions(
			eligible, Set.of("loop-read"), seen);
		Assert.assertTrue("a successfully installed seed must become one-shot",
			seen.contains("proof-input-revision"));
	}

	@Test
	public void completedLoopSeedMemoizesTheExactConvergedTransfer() {
		Map<String,String> completedTransfers = new HashMap<>();
		NeutralPlacementGraphBuilder.recordCompletedLoopSeedTransfer(
			"entry-proof-state", "converged-proof-state", true, completedTransfers);

		Assert.assertEquals(Map.of("entry-proof-state", "converged-proof-state"), completedTransfers);
		Assert.assertFalse("a later transitive proof change must remain seedable",
			completedTransfers.containsKey("late-transitive-proof-state"));
	}

	@Test
	public void privacyRejectedLoopSeedDoesNotConsumeEitherProofState() {
		Map<String,String> completedTransfers = new HashMap<>();
		try {
			NeutralPlacementGraphBuilder.recordCompletedLoopSeedTransfer(
				"entry-proof-state", "privacy-filtered-state", false, completedTransfers);
			Assert.fail("a filtered provisional seed must fail closed");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("not retained"));
		}
		Assert.assertTrue("a rejected seed must not publish a completed transfer",
			completedTransfers.isEmpty());
	}

	@Test
	public void repeatedReuseUpdateLoopConvergesWithOneAnalysisScopedSeed() throws Exception {
		String source = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\","
			+ "\"localhost:1236/X3\",\"localhost:1237/X4\"),ranges=list(list(0,0),list(4,4),"
			+ "list(4,0),list(8,4),list(8,0),list(12,4),list(12,0),list(16,4)));\n"
			+ "n=nrow(X);d=ncol(X);v0=t(colSums(X))/n;s=v0/(abs(v0)+1);anchor=s;state=s;\n"
			+ "for(iter in 1:5) {\n"
			+ " RUP1=X%*%state;RUQ1=abs(RUP1);RUA1=(t(X)%*%RUQ1)/n;"
			+ "RU1=(abs(RUA1)+anchor)/(d+1)+1/1000;\n"
			+ " RUP2=X%*%RU1;RUQ2=abs(RUP2);RUA2=(t(X)%*%RUQ2)/n;"
			+ "RU2=(abs(RUA2)+anchor)/(d+1)+2/1000;\n"
			+ " RUP3=X%*%RU2;RUQ3=abs(RUP3);RUA3=(t(X)%*%RUQ3)/n;"
			+ "RU3=(abs(RUA3)+anchor)/(d+1)+3/1000;\n"
			+ " RUP4=X%*%RU3;RUQ4=abs(RUP4);RUA4=(t(X)%*%RUQ4)/n;"
			+ "RU4=(abs(RUA4)+anchor)/(d+1)+4/1000;state=RU4;\n"
			+ "}\nprint(sum(state));\n";
		DMLProgram program = compileProtected(source);
		List<NeutralPlacementGraphBuilder.FixedPointPass> trace = new ArrayList<>();
		PlacementAnalysis first = new NeutralPlacementGraphBuilder(trace::add).buildAnalysis(program);
		PlacementAnalysis second = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		List<NeutralPlacementGraphBuilder.FixedPointPass> functionPasses = trace.stream()
			.filter(pass -> "function-boundary".equals(pass.phase())).toList();
		Assert.assertFalse(functionPasses.isEmpty());
		Assert.assertTrue("loop replay must reach an idempotent function-boundary pass",
			functionPasses.get(functionPasses.size() - 1).stable());
		Assert.assertEquals(first.analysisFingerprint(), second.analysisFingerprint());
		Assert.assertEquals(first.candidateRuleFacts().orderedFacts(),
			second.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(first.logicalTransientInputsInCanonicalOrder(),
			second.logicalTransientInputsInCanonicalOrder());
	}

	@Test
	public void protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(4,2)));\n"
			+ "[U,V]=alsCG(X=X,rank=2,maxi=2,check=FALSE,verbose=FALSE);\n"
			+ "print(sum(V));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<CompiledHopKey> loopReads = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "W".equals(data.getName())
				&& occurrence.key().normalizedSignature().contains("loop-body"))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Assert.assertFalse("fixture must contain the ALS loop-carried W read", loopReads.isEmpty());
		boolean witnessedWidening = false;
		for(CompiledHopKey read : loopReads) {
			List<CompiledHopKey> reaching = analysis.cfgDefinitionSourcesInCanonicalOrder(read);
			if(reaching.size() < 2)
				continue;
			Set<CompiledHopKey> published = analysis.logicalTransientInputsForReader(read, 0).stream()
				.map(PlacementAnalysis.LogicalTransientInputFact::sourceWrite).collect(Collectors.toSet());
			Assert.assertEquals("final replay must restore the full CFG reaching relation",
				reaching.stream().collect(Collectors.toSet()), published);
			if(analysis.graph().node(read).orElseThrow().legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() == FType.FULL))
				witnessedWidening = true;
		}
		Assert.assertTrue("later native source must widen a loop read to FED/FOUT/FULL",
			witnessedWidening);
	}

	@Test
	public void l2svmMemoReplayRebindsCurrentRelocationActionAuthority() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n"
			+ "B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);\n"
			+ "write(B,\"out\",format=\"csv\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertFalse("L2SVM fixture must publish relocation actions",
			analysis.graph().relocationActions().isEmpty());
	}

	private static DMLProgram compileProtected(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}

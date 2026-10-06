/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Entry evidence must traverse every identity branch before a loop seed is retired. */
public class LoopIdentityBackedgeClosureTest {
	@Test public void smallBuiltinStepLmCompletesCanonicalCommonClosure() throws Exception {
		String script = "X_LOCAL=matrix(1,rows=20,cols=5);Y_LOCAL=matrix(1,rows=20,cols=1);"
			+ "X=federated(local_matrix=X_LOCAL,addresses=list(\"localhost:8001\"),"
			+ "ranges=list(list(0,0),list(20,5)));"
			+ "Y=federated(local_matrix=Y_LOCAL,addresses=list(\"localhost:8001\"),"
			+ "ranges=list(list(0,0),list(20,1)));"
			+ "[B,S]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);"
			+ "write(B,\"tmp/steplm-dp-repeated-forward.res\",format=\"csv\");";
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		boolean previous = OptimizerUtils.FEDERATED_COMPILATION;
		try {
			OptimizerUtils.FEDERATED_COMPILATION = true;
			translator.prepareSearchSpaceOnly(program, false);
		}
		finally { OptimizerUtils.FEDERATED_COMPILATION = previous; }
		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		analysis.assertCanonicalProgramAuthority(program);
		assertCompleteFullRelations(analysis, "X_global");
		var inputs = analysis.logicalFunctionInputsInCanonicalOrder();
		Assert.assertFalse(inputs.isEmpty());
		for(int i = 0; i < inputs.size(); i++)
			for(int j = i + 1; j < inputs.size(); j++)
				Assert.assertFalse("same-name formal forwarding must keep unique occurrence ownership",
					inputs.get(i).sourceArgument() == inputs.get(j).sourceArgument()
						&& inputs.get(i).targetRead() == inputs.get(j).targetRead()
						&& inputs.get(i).logicalPosition() == inputs.get(j).logicalPosition());
	}

	@Test public void nestedIdentityBackedgesKeepTheGroundedFullAlternative() throws Exception {
		for(int depth : new int[] {1, 2, 4}) {
			PlacementAnalysis analysis = analyze(script(depth, false), Privacy.PUBLIC, true);
			assertCompleteFullRelations(analysis);
		}
	}

	@Test public void protectedNestedIdentityBackedgesMatchFullRecompute() throws Exception {
		String script = script(2, false);
		PlacementAnalysis incremental = analyze(script, Privacy.PRIVATE_AGGREGATE, true);
		PlacementAnalysis full = analyze(script, Privacy.PRIVATE_AGGREGATE, false);
		assertCompleteFullRelations(incremental);
		Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(), incremental.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			incremental.logicalTransientInputsInCanonicalOrder());
	}

	@Test public void anIncompatibleBackedgeCannotBorrowEntryFullEvidence() throws Exception {
		PlacementAnalysis analysis = analyze(script(2, true), Privacy.PUBLIC, true);
		int checked = 0;
		for(var occurrence : analysis.occurrences()) {
			if(!(occurrence.hop() instanceof DataOp data) || data.getOp() != OpOpData.TRANSIENTREAD
				|| !"G".equals(data.getName())
				|| analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() < 3)
				continue;
			checked++;
			Assert.assertFalse("entry FULL cannot survive a local matrix-construction backedge", analysis.graph()
				.node(occurrence.key()).orElseThrow().legalAlternatives().stream()
				.anyMatch(state -> state.execType() == ExecType.FED && state.fType() == FType.FULL));
		}
		Assert.assertTrue("fixture must contain the loop header or exit join", checked > 0);
	}

	private static String script(int depth, boolean incompatible) {
		String body = "if(q>0.5){G=cbind(G,X[,j]);}else{G=cbind(G,X[,j]);}";
		for(int i = 0; i < depth; i++) body = "if(p>0.5){" + body + "}";
		if(incompatible) body += "if(q<0.25){G=matrix(sum(G),rows=8,cols=1);}";
		return "X=federated(addresses=list(\"localhost:19954/X\"),ranges=list(list(0,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));"
			+ "j=as.integer(1+p);G=X[,j];i=1;while(i<=3){" + body + "i=i+1;}print(sum(G));";
	}

	private static PlacementAnalysis analyze(String script, Privacy privacy, boolean incremental) throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(), incremental).buildAnalysis(program);
	}

	private static void assertCompleteFullRelations(PlacementAnalysis analysis) {
		assertCompleteFullRelations(analysis, "G");
	}

	private static void assertCompleteFullRelations(PlacementAnalysis analysis, String name) {
		int checked = 0;
		for(var occurrence : analysis.occurrences()) {
			if(!(occurrence.hop() instanceof DataOp data) || data.getOp() != OpOpData.TRANSIENTREAD
				|| !name.equals(data.getName())) continue;
			var definitions = analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key());
			if(definitions.size() < 3) continue;
			checked++;
			var relations = analysis.logicalTransientInputsForReader(occurrence.key(), 0);
			Assert.assertEquals(Set.copyOf(definitions), relations.stream()
				.map(PlacementAnalysis.LogicalTransientInputFact::sourceWrite).collect(Collectors.toSet()));
			Set<CandidateRealizationReference> common = null;
			for(var relation : relations) {
				Set<CandidateRealizationReference> readers = relation.compatibility().stream()
					.filter(edge -> {
						var state = edge.readerRealization().realization().emissionState().placementState();
						return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
							&& state.fType() == FType.FULL;
					}).peek(edge -> {
						analysis.requireExactCandidateRealization(edge.sourceRealization());
						analysis.requireExactCandidateRealization(edge.readerRealization());
					}).map(PlacementAnalysis.TransientPlacementCompatibility::readerRealization)
					.collect(Collectors.toSet());
				if(common == null) common = new HashSet<>(readers);
				else common.retainAll(readers);
			}
			Assert.assertNotNull(common);
			Assert.assertFalse("all reaching writers must support one exact FULL reader", common.isEmpty());
		}
		Assert.assertTrue("fixture must contain nested identity backedges", checked > 0);
	}
}

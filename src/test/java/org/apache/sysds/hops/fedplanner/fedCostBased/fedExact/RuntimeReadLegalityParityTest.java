/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization;
import org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalizationTest;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression boundary between alias legality and WDIVMM runtime-input costing. */
public class RuntimeReadLegalityParityTest {
	private static final String FED = "F=federated(addresses=list(\"localhost:1234/X1\","
		+ "\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void explicitBranchUploadSeparatesUltimateSourceFromRuntimeReadPlacement() throws Exception {
		DMLProgram program = compile(FED + "p=as.scalar(rand(rows=1,cols=1,seed=7));Y=F;"
			+ "if(p>0.5){Y=matrix(seq(1,8),rows=4,cols=2);}else{Y=F;}"
			+ "Z=Y+F;print(sum(Z));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		BranchPlacementNormalization.prepare(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		Object forced = forceBranchExitUpload(analysis);
		CompiledInputEdgeFact uploadEdge = (CompiledInputEdgeFact)access(forced, "edge");
		NormalizedPlannerResult plan = (NormalizedPlannerResult)access(forced, "plan");

		PlacementState ultimateSource = plan.selectedStates().get(uploadEdge.producer());
		PlacementState uploadSite = plan.selectedStates().get(uploadEdge.consumer());
		Assert.assertEquals(ExecType.CP, ultimateSource.execType());
		Assert.assertEquals(FederatedOutput.LOUT, ultimateSource.output());
		Assert.assertEquals(ExecType.CP, uploadSite.execType());
		Assert.assertEquals(FederatedOutput.FOUT, uploadSite.output());
		Assert.assertTrue("the selected exact candidate must own the explicit upload",
			plan.selectedCandidateSelections().stream().anyMatch(receipt ->
				receipt.rule().parentOccurrence() == uploadEdge.consumer()
					&& receipt.emission().derivedFoutAction() != null));

		CompiledInputEdgeFact carrierEdge = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == uploadEdge.consumer())
			.filter(edge -> analysis.hop(edge.consumer()).orElseThrow() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE)
			.findFirst().orElseThrow();
		LogicalTransientInputFact logicalRead = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.sourceWrite() == carrierEdge.consumer()).findFirst().orElseThrow();
		PlacementState carrier = plan.selectedStates().get(carrierEdge.consumer());
		PlacementState read = plan.selectedStates().get(logicalRead.targetRead());
		Assert.assertEquals(ExecType.FED, carrier.execType());
		Assert.assertEquals(FederatedOutput.FOUT, carrier.output());
		Assert.assertEquals(ExecType.FED, read.execType());
		Assert.assertEquals(FederatedOutput.FOUT, read.output());
		Assert.assertEquals(uploadSite.fType(), read.fType());

		List<?> resolvedSources = resolveRuntimeMaterializationSources(analysis, logicalRead.targetRead());
		Assert.assertEquals("the merged read retains both branch payload origins", 2, resolvedSources.size());
		CompiledHopKey resolvedLocalKey = localResolvedSource(resolvedSources, plan);
		PlacementState resolvedLocal = plan.selectedStates().get(resolvedLocalKey);
		Assert.assertNotNull("the cost resolver must cross TW and the explicit upload alias: "
			+ sourceSignatures(resolvedSources), resolvedLocal);
		Assert.assertEquals(ExecType.CP, resolvedLocal.execType());
		Assert.assertEquals(FederatedOutput.LOUT, resolvedLocal.output());

		Assert.assertTrue("ordinary alias propagation must preserve the uploaded FOUT layout",
			foutReadCompatible(uploadSite, read));
		Assert.assertFalse("following assignment aliases through the explicit upload and comparing the"
			+ " pre-upload local source to the FOUT read would reject this legal selected plan",
			foutReadCompatible(resolvedLocal, read));
	}

	private static boolean foutReadCompatible(PlacementState source, PlacementState read) {
		return read.output() != FederatedOutput.FOUT
			|| source.output() == FederatedOutput.FOUT && source.fType() == read.fType();
	}

	private static Object forceBranchExitUpload(PlacementAnalysis analysis) throws Exception {
		Method method = BranchPlacementNormalizationTest.class.getDeclaredMethod(
			"forceOneBranchExitUpload", PlacementAnalysis.class);
		method.setAccessible(true);
		return method.invoke(null, analysis);
	}

	private static List<?> resolveRuntimeMaterializationSources(PlacementAnalysis analysis,
		CompiledHopKey read) throws Exception {
		IdentityHashMap<CompiledHopKey,List<LogicalTransientInputFact>> transientByRead =
			new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : analysis.logicalTransientInputsInCanonicalOrder())
			transientByRead.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod("runtimeMaterializationSources",
			PlacementAnalysis.class, CompiledHopKey.class, IdentityHashMap.class, IdentityHashMap.class,
			IdentityHashMap.class, Set.class, Set.class);
		method.setAccessible(true);
		return (List<?>)method.invoke(null, analysis, read, transientByRead, new IdentityHashMap<>(),
			new IdentityHashMap<>(), Collections.newSetFromMap(new IdentityHashMap<>()),
			Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static Object access(Object record, String accessor) throws Exception {
		Method method = record.getClass().getDeclaredMethod(accessor);
		method.setAccessible(true);
		return method.invoke(record);
	}

	private static CompiledHopKey localResolvedSource(List<?> sources, NormalizedPlannerResult plan)
		throws Exception {
		for(Object source : sources) {
			CompiledHopKey occurrence = (CompiledHopKey)access(source, "occurrence");
			PlacementState state = plan.selectedStates().get(occurrence);
			if(state != null && state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
				return occurrence;
		}
		return null;
	}

	private static List<String> sourceSignatures(List<?> sources) throws Exception {
		List<String> result = new ArrayList<>();
		for(Object source : sources)
			result.add(((CompiledHopKey)access(source, "occurrence")).normalizedSignature());
		return result;
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}

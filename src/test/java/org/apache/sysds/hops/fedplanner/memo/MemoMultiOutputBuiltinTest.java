/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.After;
import org.junit.Test;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public class MemoMultiOutputBuiltinTest {
	private static final String QR =
		"X=rand(rows=6,cols=3,seed=7);[Q,R]=qr(X);print(sum(Q)+sum(R));";

	@After public void cleanup() { MemoPlanRegistry.clear(); }

	@Test
	public void qrOutputsAreDistinctSlotsOfOneExecutingOwner() throws Exception {
		MemoProgramFacts facts = MemoProgramFacts.analyze(compiled(QR));
		facts.requireComplete();
		MemoProgramFacts.Occurrence qr = builtin(facts);

		int q = transientRead(facts, "Q");
		int r = transientRead(facts, "R");
		MemoProgramFacts.BindingNode qBinding = facts.binding(facts.valueBinding(q));
		MemoProgramFacts.BindingNode rBinding = facts.binding(facts.valueBinding(r));
		Assert.assertEquals(MemoProgramFacts.BindingKind.DEFINITION, qBinding.kind());
		Assert.assertEquals(MemoProgramFacts.BindingKind.DEFINITION, rBinding.kind());
		Assert.assertEquals(qr.id(), qBinding.referenceId());
		Assert.assertEquals(qr.id(), rBinding.referenceId());
		Assert.assertEquals(0, qBinding.position());
		Assert.assertEquals(1, rBinding.position());
		Assert.assertNotEquals(qBinding.id(), rBinding.id());
	}

	@Test
	public void nativeCatalogExposesOneCpRuleWithEveryLocalOutputSlot() throws Exception {
		FunctionOp qr = (FunctionOp) builtin(MemoProgramFacts.analyze(compiled(QR))).hop();
		MemoNativeRules.Result result = new MemoNativeRules().rules(qr, 2,
			new org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint(-1,-1,-1));

		Assert.assertTrue(result.diagnostics().toString(), result.complete());
		Assert.assertEquals(1, result.rules().size());
		RuleMemo.Rule rule = result.rules().get(0);
		Assert.assertEquals(ExecType.CP, rule.exec());
		Assert.assertEquals(List.of(MemoPartition.LOCAL), rule.inputs());
		Assert.assertEquals(List.of(MemoPartition.LOCAL, MemoPartition.LOCAL), rule.outputs());
		Assert.assertTrue("the builtin is an executing operation, not a zero-cost carrier",
			MemoCostModel.nativeCost(qr, rule, 2, 1) > 0d);
	}

	@Test
	public void transformEncodeRetainsItsVerifiedHeterogeneousFedOutputs() {
		DataOp frame=new DataOp("F",DataType.FRAME,ValueType.STRING,
			OpOpData.TRANSIENTREAD,"F",8,2,-1,1000);
		DataOp encoded=new DataOp("X0",DataType.MATRIX,ValueType.FP64,
			frame,OpOpData.FUNCTIONOUTPUT,"X0");
		encoded.setDim1(8); encoded.setDim2(3);
		DataOp metadata=new DataOp("M",DataType.FRAME,ValueType.STRING,
			frame,OpOpData.FUNCTIONOUTPUT,"M");
		metadata.setDim1(2); metadata.setDim2(3);
		FunctionOp transform=new FunctionOp(FunctionType.MULTIRETURN_BUILTIN,"_internal","transformencode",
			null,List.of(frame,new LiteralOp("{\"ids\":true,\"recode\":[1]}")),new String[] {"X0","M"},
			new ArrayList<Hop>(List.of(encoded,metadata)));
		MemoNativeRules.Result result=new MemoNativeRules().rules(transform,2,
			new org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint(-1,-1,1000));

		Assert.assertTrue(result.diagnostics().toString(),result.complete());
		RuleMemo.Rule fed=result.rules().stream().filter(rule -> rule.exec()==ExecType.FED
			&& rule.inputs().equals(List.of(MemoPartition.ROW,MemoPartition.LOCAL))
			&& rule.outputs().equals(List.of(MemoPartition.ROW,MemoPartition.LOCAL))).findFirst().orElseThrow();
		Assert.assertTrue(MemoCostModel.nativeCost(transform,fed,2,1)>0);
		Assert.assertFalse(result.rules().stream().anyMatch(rule -> rule.exec()==ExecType.FED
			&& rule.inputs().get(0)==MemoPartition.COL));
	}

	@Test
	public void qrRuleBuildAndExactOptimizationChargeOneNativeExecution() throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(compiled(QR));
		facts.requireComplete();
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		int owner=builtin(facts).compiledOwnerId();
		Assert.assertTrue(universe.diagnostics().toString(),universe.memo().complete(owner));
		Assert.assertEquals(1,universe.memo().rules(owner).size());
		Assert.assertEquals(2,universe.memo().rules(owner).get(0).outputs().size());

		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		MemoProgramModel.Solution solution=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,solution.optimization().status());
		Assert.assertTrue(model.evaluateCost(solution.optimization().selectedRules()).nativeExecution()
			.signum()>0);
	}

	@Test
	public void qrPlanProjectsEveryPhysicalOutputWithoutDuplicatingExecution() throws Exception {
		DMLProgram program=compiled(QR);
		MemoProgramFacts before=MemoProgramFacts.analyze(program);
		FunctionOp qr=(FunctionOp)builtin(before).hop();
		new FederatedPlanMemo().plan(program);

		Assert.assertEquals(ExecType.CP,qr.getForcedExecType());
		Assert.assertTrue(qr.isPlannerPlacementSelected());
		for(var output : qr.getOutputs()) {
			Assert.assertEquals(ExecType.CP,output.getForcedExecType());
			Assert.assertEquals(FederatedOutput.LOUT,output.getFederatedOutput());
			Assert.assertTrue(output.isPlannerPlacementSelected());
		}
		Assert.assertTrue(MemoPlanRegistry.hasPlan(program));
	}

	@Test
	public void builtinOutputPrivacyIsDerivedFromItsInputRatherThanDefaultedPublic() throws Exception {
		MemoProgramFacts facts = MemoProgramFacts.analyze(compiled(QR));
		facts.requireComplete();
		int inputOwner = facts.occurrences().stream().filter(occurrence -> "X".equals(occurrence.hop().getName()))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
		MemoPrivacy privacy = new MemoPrivacy(facts, new MemoBindingResolver(facts),
			Map.of(inputOwner, Privacy.PRIVATE));

		MemoPrivacy.Result result = privacy.outputPrivacyAt(builtin(facts).id(), MemoPrivacy.Context.empty());
		Assert.assertTrue(result.detail(), result.exact());
		Assert.assertEquals(Privacy.PRIVATE, result.privacy());
	}

	private static MemoProgramFacts.Occurrence builtin(MemoProgramFacts facts) {
		return facts.occurrences().stream().filter(occurrence -> occurrence.hop() instanceof FunctionOp function
			&& function.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN).findFirst().orElseThrow();
	}

	private static int transientRead(MemoProgramFacts facts, String name) {
		return facts.occurrences().stream().filter(occurrence -> name.equals(occurrence.hop().getName())
			&& occurrence.hop().getOpString().startsWith("TRead")).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
	}

	private static DMLProgram compiled(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class MemoProgramModelTest {
	@Test public void loopPhaseCostsPartitionAllIterationsExactly() {
		Assert.assertEquals(0,MemoProgramModel.phaseIterations(0,MemoProgramFacts.IterationPhase.FIRST),0);
		Assert.assertEquals(0,MemoProgramModel.phaseIterations(0,MemoProgramFacts.IterationPhase.LAST),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(1,MemoProgramFacts.IterationPhase.FIRST),0);
		Assert.assertEquals(0,MemoProgramModel.phaseIterations(1,MemoProgramFacts.IterationPhase.LAST),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(3,MemoProgramFacts.IterationPhase.FIRST),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(3,MemoProgramFacts.IterationPhase.MIDDLE),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(3,MemoProgramFacts.IterationPhase.LAST),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(4,MemoProgramFacts.IterationPhase.FIRST),0);
		Assert.assertEquals(2,MemoProgramModel.phaseIterations(4,MemoProgramFacts.IterationPhase.MIDDLE),0);
		Assert.assertEquals(1,MemoProgramModel.phaseIterations(4,MemoProgramFacts.IterationPhase.LAST),0);
		Assert.assertEquals(0,MemoProgramModel.phaseIterations(2,MemoProgramFacts.IterationPhase.MIDDLE),0);
	}
	@Test public void sharedMovementChargesTheUnionOfActiveDemands() {
		var guard = new MemoBindingResolver.GuardDecision(7,List.of());
		var independent = new MemoBindingResolver.GuardDecision(8,List.of());
		var beforeCall = new MemoProgramModel.Activation(Map.of(),1);
		var normalReturn = new MemoProgramModel.Activation(Map.of(guard,true),0.5);
		var oppositeArm = new MemoProgramModel.Activation(Map.of(guard,false),0.5);
		var independentArm = new MemoProgramModel.Activation(Map.of(independent,true),0.5);
		Assert.assertEquals("the frozen estimator correlates one unknown guard id across repeated evaluations",
			0.5,MemoProgramModel.activationUnion(List.of(normalReturn,normalReturn),1),0);
		Assert.assertEquals(1,MemoProgramModel.activationUnion(List.of(normalReturn,oppositeArm),1),0);
		Assert.assertEquals("independent guarded demands use the exact union, not their sum",0.75,
			MemoProgramModel.activationUnion(List.of(normalReturn,independentArm),1),0);
		Assert.assertEquals(1,MemoProgramModel.activationUnion(List.of(normalReturn,beforeCall),1),0);
		Assert.assertEquals(0,MemoProgramModel.activationUnion(List.of(),1),0);
	}
	@Test public void movementActivationKeepsDefinitionPhasesDistinct() {
		var loop=new MemoBindingResolver.LoopDecision(3,List.of());
		var first=new MemoProgramModel.Activation(Map.of(),Map.of(loop,MemoProgramFacts.IterationPhase.FIRST),1);
		var last=new MemoProgramModel.Activation(Map.of(),Map.of(loop,MemoProgramFacts.IterationPhase.LAST),1);
		Assert.assertEquals(2,MemoProgramModel.activationUnion(List.of(first,last),Double.POSITIVE_INFINITY),0);
		Assert.assertEquals(1,MemoProgramModel.activationUnion(List.of(first,first),Double.POSITIVE_INFINITY),0);
	}
	@Test public void parsedSharedDagUsesOneNativeOwnerAndNoSourceProducts() throws Exception {
		MemoProgramModel model = model("A=matrix(2,3,3);B=abs(A);C=B+1;D=B*2;print(sum(C+D));");
		Assert.assertTrue("fixed native patterns have edge-separable supply checks, not input products",
			model.checks().stream().allMatch(check -> check.owners().length<=2));
		var result = model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
		Assert.assertTrue(result.supplies().isEmpty());
	}
	@Test public void parsedCorrelatedBranchRequiresEveryReachableArm() throws Exception {
		var result = model("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}print(sum(A+B));")
			.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
	}
	@Test public void parsedLoopKeepsEntryHeadBackedgeAndLiveOut() throws Exception {
		MemoProgramModel model = model("A=matrix(0,2,2);for(i in 1:3){A=A+1;}print(sum(A));");
		Assert.assertFalse(model.heads().isEmpty());
		var result = model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
	}
	@Test public void zeroIterationLoopCostsNoBodyAndKeepsInitialExit() throws Exception {
		MemoProgramModel model=model("A=matrix(0,2,2);for(i in seq(1,0,1)){A=matrix(1,4,2);}print(sum(A));");
		Assert.assertTrue("a statically zero loop needs no physical loop-head choice",model.heads().isEmpty());
		var result=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
	}
	@Test public void oneIterationLoopUsesEntryWithoutTakenBackedge() throws Exception {
		MemoProgramModel model=model("A=matrix(0,2,2);for(i in 1:1){A=rbind(A,A);}print(sum(A));");
		Assert.assertFalse(model.heads().isEmpty());
		var result=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
		Assert.assertFalse(model.loopContracts(result.optimization().selectedRules()).isEmpty());
	}
	@Test public void overwrittenFirstEntryDoesNotConstrainRepeatShape() throws Exception {
		MemoProgramModel model=model("A=matrix(0,1,1);for(i in 1:3){if(i==1){A=matrix(2,4,2);}"
			+"else{A=abs(A);}print(sum(A));}print(sum(A));");
		var result=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
		var carried=model.loopContracts(result.optimization().selectedRules()).values().stream().flatMap(List::stream)
			.filter(contract -> contract.variable().equals("A")).findFirst().orElseThrow();
		Assert.assertFalse(carried.entryRequired());
		Assert.assertTrue(carried.entryRecipes().isEmpty());
		Assert.assertEquals(4,carried.rows()); Assert.assertEquals(2,carried.columns());
	}
	@Test public void finalOnlyShapeChangeIsAnExitNotATakenBackedge() throws Exception {
		MemoProgramModel model=model("A=matrix(0,2,2);for(i in 1:3){if(i==3){A=matrix(2,4,2);}"
			+"else{A=abs(A);}}print(sum(A));");
		var result=model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
		var carried=model.loopContracts(result.optimization().selectedRules()).values().stream().flatMap(List::stream)
			.filter(contract -> contract.variable().equals("A")).findFirst().orElseThrow();
		Assert.assertTrue(carried.entryRequired()); Assert.assertEquals(2,carried.rows());
	}
	@Test public void parsedInlineCallsUseActualClones() throws Exception {
		var result = model("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);print(sum(A+B));")
			.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
	}
	@Test public void sharedCompiledKernelCostsDistinctValueVersionShapesExactly() {
		DataOp read = new DataOp("A",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,"A",-1,-1,-1,1000);
		Hop kernel = new UnaryOp("abs",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,read);
		List<StatementBlock> blocks = new ArrayList<>();
		for(int size : new int[] {2,4}) {
			DataOp dimensions = new DataOp("dimensions",DataType.MATRIX,ValueType.FP64,
				OpOpData.PERSISTENTREAD,"unused",size,size,size*size,1000);
			Hop value = HopRewriteUtils.createDataGenOp(dimensions,-1);
			DataOp write = new DataOp("A",DataType.MATRIX,ValueType.FP64,value,OpOpData.TRANSIENTWRITE,"A");
			DataOp output = new DataOp("out"+size,DataType.MATRIX,ValueType.FP64,kernel,
				OpOpData.TRANSIENTWRITE,"out"+size);
			StatementBlock block = new StatementBlock(); block.setHops(new ArrayList<>(List.of(write,output)));
			blocks.add(block);
		}
		DMLProgram program = new DMLProgram(); program.setStatementBlocks(new ArrayList<>(blocks));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts,null);
		var universe = MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		var result = new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults())
			.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(result.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,result.optimization().status());
		List<Integer> occurrences = facts.occurrenceIds(kernel);
		Assert.assertEquals(2,occurrences.size());
		Assert.assertEquals(2,universe.shapes().viewAt(occurrences.get(0)).getDim1());
		Assert.assertEquals(4,universe.shapes().viewAt(occurrences.get(1)).getDim1());
		BigDecimal expected = BigDecimal.ZERO;
		for(var occurrence : facts.occurrences()) if(!MemoRuleBuilder.transparent(occurrence.hop()))
			expected = expected.add(new BigDecimal(MemoCostModel.nativeCost(universe.shapes().viewAt(occurrence.id()),
				universe.memo().rule(occurrence.compiledOwnerId(),0),1,1)));
		Assert.assertEquals("Each executed occurrence contributes once without an intermediate rounded sum",
			0,expected.compareTo(result.optimization().cost()));
	}
	@Test public void nativeCostUsesShapeGuardProjectionWithoutSourceProducts() throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=matrix(1,2,2);}"
				+"else{A=matrix(2,4,2);}B=abs(A);print(sum(B));",new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence occurrence=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		Assert.assertEquals(1,universe.shapes().guardSensitiveDecisions(occurrence.id(),List.of()).size());

		BigDecimal exhaustive=BigDecimal.ZERO;
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		List<BigDecimal> primitives=new ArrayList<>();
		resolver.visitJoint(List.of(facts.inputBinding(occurrence.id(),0)),
			MemoBindingResolver.EvaluationContext.empty(),environment -> {
				MemoBindingResolver.EvaluationContext exact=new MemoBindingResolver.EvaluationContext(
					environment.guards(),environment.loops(),MemoBindingResolver.NO_SELECTION,4096,List.of(),
					environment.iterationPhases(),environment.cardinalities());
				double primitive=MemoCostModel.nativeCost(universe.shapes().viewAt(occurrence.id(),exact),
					universe.memo().rule(occurrence.compiledOwnerId(),0),1,0.5);
				primitives.add(new BigDecimal(primitive)); return true;
			});
		for(BigDecimal primitive : primitives) exhaustive=exhaustive.add(primitive);
		Assert.assertEquals("the guard projection retains two shape-dependent primitives",2,primitives.size());

		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		Assert.assertEquals(0,exhaustive.compareTo(nativeOwnerCost(model,occurrence.compiledOwnerId(),0)));
	}
	@Test public void sourceOnlyGuardDoesNotSplitNativeCost() throws Exception {
		ModelFixture fixture=modelFixture("p=as.scalar(rand(rows=1,cols=1));"
			+"if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}B=abs(A);print(sum(B));",OpOp1.ABS);
		Assert.assertTrue(fixture.universe().shapes()
			.guardSensitiveDecisions(fixture.occurrence().id(),List.of()).isEmpty());
		double primitive=MemoCostModel.nativeCost(fixture.universe().shapes().viewAt(fixture.occurrence().id()),
			fixture.universe().memo().rule(fixture.occurrence().compiledOwnerId(),0),1,1);
		Assert.assertEquals(0,new BigDecimal(primitive).compareTo(nativeOwnerCost(fixture.model(),
			fixture.occurrence().compiledOwnerId(),0)));
	}
	@Test public void conditionalMissingReturnWeightsFollowingNativeExecution() throws Exception {
		ModelFixture fixture=modelFixture("f=function(boolean p) return(matrix[double] Y){"
			+"if(p){Y=matrix(1,2,2);}}p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p);C=abs(matrix(3,2,2));print(sum(C));",OpOp1.ABS);
		double primitive=MemoCostModel.nativeCost(fixture.universe().shapes().viewAt(fixture.occurrence().id()),
			fixture.universe().memo().rule(fixture.occurrence().compiledOwnerId(),0),1,0.5);
		Assert.assertEquals(0,new BigDecimal(primitive).compareTo(nativeOwnerCost(fixture.model(),
			fixture.occurrence().compiledOwnerId(),0)));
	}

	private record ModelFixture(MemoRuleBuilder.Result universe,MemoProgramModel model,
		MemoProgramFacts.Occurrence occurrence) { }
	private static ModelFixture modelFixture(String script,OpOp1 operator) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,
			new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence occurrence=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==operator)
			.findFirst().orElseThrow();
		return new ModelFixture(universe,
			new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults()),occurrence);
	}

	@SuppressWarnings("unchecked")
	private static BigDecimal nativeOwnerCost(MemoProgramModel model,int owner,int rule) throws Exception {
		Field nativeCostsField=MemoProgramModel.class.getDeclaredField("nativeCosts");
		nativeCostsField.setAccessible(true);
		BigDecimal result=new BigDecimal(((double[][])nativeCostsField.get(model))[owner][rule]);
		Field countField=MemoProgramModel.class.getDeclaredField("nativeCostTermCount");
		countField.setAccessible(true);
		Field costsField=MemoProgramModel.class.getDeclaredField("costs"); costsField.setAccessible(true);
		List<MemoOptimizer.CostTerm> terms=(List<MemoOptimizer.CostTerm>)costsField.get(model);
		for(int index=0;index<(int)countField.get(model);index++) {
			MemoOptimizer.CostTerm term=terms.get(index);
			if(term.owners().length==1 && term.owners()[0]==owner)
				result=result.add(term.evaluateExact(new int[] {rule}));
		}
		return result;
	}
	private static MemoProgramModel model(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts,null);
		return new MemoProgramModel(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()),
			MemoProgramModel.Profile.defaults());
	}
}

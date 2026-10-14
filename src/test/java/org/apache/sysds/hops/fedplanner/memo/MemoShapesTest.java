/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class MemoShapesTest {
	@Test
	public void provenFirstOverwriteGroundsLaterLoopPhaseShape() throws Exception {
		MemoProgramFacts facts = facts("A=matrix(0,rows=1,cols=1);"
			+ "for(i in 1:3){if(i==1){A=matrix(1,rows=8,cols=1);}B=abs(A);}print(sum(B));");
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence abs=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();

		assertShape(shapes.inputShape(abs.id(),0,
			EvaluationContext.empty().withIterationPhase(0,IterationPhase.FIRST)),8,1);
		assertShape(shapes.inputShape(abs.id(),0,
			EvaluationContext.empty().withIterationPhase(0,IterationPhase.MIDDLE)),8,1);
		assertShape(shapes.inputShape(abs.id(),0,
			EvaluationContext.empty().withIterationPhase(0,IterationPhase.LAST)),8,1);
	}

	@Test
	public void nestedLoopPhaseReusesAnExactAllPathHeadInvariant() throws Exception {
		MemoProgramFacts facts=facts("V=matrix(0,rows=128,cols=10);flag=TRUE;"
			+"for(i in 1:3){for(j in 1:3){if(flag){dummy=1;}else{V=V+1;}Z=t(V);}flag=!flag;}"
			+"print(sum(Z));");
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence transpose=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp()==ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		boolean[] sawNestedRepeat={false};
		MemoBindingResolver.VisitResult visit=new MemoReachableContexts(facts,new MemoBindingResolver(facts))
			.visit(transpose,EvaluationContext.empty(),context -> {
				long repeats=context.iterationPhases().values().stream()
					.filter(phase -> phase==IterationPhase.MIDDLE).count();
				if(repeats==2) sawNestedRepeat[0]=true;
				Assert.assertTrue("every reachable nested phase keeps the exact V invariant: "+context,
					shapes.inputShape(transpose.id(),0,context).exact());
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,visit.status());
		Assert.assertTrue("the fixture must exercise simultaneous nested repeat phases",sawNestedRepeat[0]);
	}

	@Test
	public void exactLoopSeedDoesNotHideAnUnknownBackedgeArm() throws Exception {
		Path data=Files.createTempFile("memo-unknown-loop-backedge", ".csv");
		try {
			Files.writeString(data,"1,2\n3,4\n");
			String path=data.toString().replace("\\","\\\\").replace("\"","\\\"");
			MemoProgramFacts facts=facts("X=read(\""+path+"\",data_type=\"matrix\",format=\"csv\");"
				+"V=matrix(0,rows=8,cols=4);p=as.scalar(rand(rows=1,cols=1));"
				+"for(i in 1:3){if(p>0.5){V=X;}Z=t(V);}print(sum(Z));");
			MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
			MemoProgramFacts.Occurrence transpose=facts.occurrences().stream()
				.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp()==ReOrgOp.TRANS)
				.findFirst().orElseThrow();
			boolean[] sawUnknown={false};
			new MemoReachableContexts(facts,new MemoBindingResolver(facts))
				.visit(transpose,EvaluationContext.empty(),context -> {
					sawUnknown[0] |= !shapes.inputShape(transpose.id(),0,context).exact();
					return true;
				});
			Assert.assertTrue("an unknown backedge arm must remain unknown",sawUnknown[0]);
		}
		finally {
			Files.deleteIfExists(data);
		}
	}

	@Test
	public void transientFormalAliasKeepsTheGroundedShapeAcrossLoopPhases() throws Exception {
		DMLProgram program=rewrittenGlm();
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,data ->
			sourceMetadata(192,"X".equals(data.getName()) ? 8 : 1));
		MemoShapes shapes=MemoShapes.analyze(facts,sources);
		MemoProgramFacts.Occurrence multiply=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof BinaryOp binary && binary.getOp()==OpOp2.MULT)
			.filter(value -> !value.hop().getInput().isEmpty()
				&& "w".equals(value.hop().getInput(0).getName()))
			.filter(value -> {
				MemoProgramFacts.BindingNode binding=facts.binding(facts.inputBinding(value.id(),0));
				return binding.kind()==BindingKind.DEFINITION && !binding.children().isEmpty()
					&& facts.binding(binding.children().get(0)).kind()==BindingKind.FORMAL;
			})
			.findFirst().orElseThrow();
		int input=facts.inputBinding(multiply.id(),0);
		Assert.assertEquals(BindingKind.DEFINITION,facts.binding(input).kind());
		Assert.assertFalse("the regression requires a transparent value-version wrapper",
			facts.binding(input).children().isEmpty());
		MemoProgramFacts.OccurrenceContext occurrenceContext=facts.occurrenceContexts(multiply.id()).stream()
			.filter(context -> !context.phaseLoops().isEmpty()).findFirst().orElseThrow();
		int dependentLoop=shapes.phaseSensitiveLoops(multiply.id()).stream().findFirst().orElseThrow();
		MemoProgramFacts.PhaseLoop phaseLoop=occurrenceContext.phaseLoops().stream()
			.filter(loop -> loop.loopId()==dependentLoop).findFirst().orElseThrow();
		LoopDecision loop=new LoopDecision(dependentLoop,phaseLoop.callFrame());
		LoopCardinalityDecision cardinality=
			new LoopCardinalityDecision(dependentLoop,phaseLoop.callFrame());

		for(IterationPhase phase : IterationPhase.values()) {
			EvaluationContext context=new EvaluationContext(java.util.Map.of(),
				java.util.Map.of(loop,phase==IterationPhase.FIRST ? LoopPhase.ENTRY : LoopPhase.BACKEDGE),
				MemoBindingResolver.NO_SELECTION,4096,occurrenceContext.callFrame(),
				java.util.Map.of(loop,phase),java.util.Map.of(cardinality,LoopCardinality.MANY));
			assertShape(shapes.inputShape(multiply.id(),0,context),192,1);
		}
	}

	@Test
	public void persistentReadMetadataIsAnExactShapeAuthority() throws Exception {
		Path directory = Files.createTempDirectory("memo-local-shape");
		Path data = directory.resolve("Y.csv");
		Path metadata = Path.of(data + ".mtd");
		try {
			Files.writeString(data,"1\n");
			Files.writeString(metadata,"{\"data_type\":\"matrix\",\"value_type\":\"double\","
				+ "\"rows\":192,\"cols\":1,\"nnz\":192,\"format\":\"csv\"}");
			DataOp read = new DataOp("Y",DataType.MATRIX,ValueType.FP64,
				OpOpData.PERSISTENTREAD,data.toString(),-1,-1,-1,1000);
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(read)));
			DMLProgram program = new DMLProgram();
			program.setStatementBlocks(new ArrayList<>(List.of(block)));
			MemoProgramFacts facts = MemoProgramFacts.analyze(program);
			MemoShapes shapes = MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
			assertShape(shapes.shape(facts.compiledOwnerId(read)),192,1);
		}
		finally {
			Files.deleteIfExists(metadata);
			Files.deleteIfExists(data);
			Files.deleteIfExists(directory);
		}
	}

	@Test
	public void occurrenceDataGenResolvesNrowFromItsReachingDefinitionShape() throws Exception {
		MemoProgramFacts facts = facts("A=matrix(1,rows=8,cols=4);B=rand(rows=nrow(A),cols=1);"
			+ "C=cbind(A[,1],B);print(sum(C));");
		MemoProgramFacts.Occurrence dataGen = facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataGenOp
				&& "B".equals(occurrence.hop().getName()))
			.findFirst().orElseThrow();
		dataGen.hop().setDim1(-1);
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));

		assertShape(shapes.shapeAt(dataGen.id()), 8, 1);
		Assert.assertEquals(8, shapes.viewAt(dataGen.id()).getDim1());
		MemoProgramFacts.Occurrence cbind = facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& binary.getOp() == OpOp2.CBIND)
			.findFirst().orElseThrow();
		assertShape(shapes.inputShape(cbind.id(),1),8,1);
		Assert.assertEquals(8,shapes.viewAt(cbind.id()).getInput(1).getDim1());
		Assert.assertEquals("live compiler HOP remains untouched", -1, dataGen.hop().getDim1());
	}

	@Test
	public void rexpandRetainsACompilerProvedValueDependentAxis() {
		DataOp target = new DataOp("Y", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "Y", 8, 1, 8, 1000);
		DataOp maximum = new DataOp("max_y", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "max_y", -1, -1, -1, 1000);
		LinkedHashMap<String,Hop> parameters = new LinkedHashMap<>();
		parameters.put("target", target);
		parameters.put("max", maximum);
		parameters.put("dir", new LiteralOp("cols"));
		ParameterizedBuiltinOp rexpand = new ParameterizedBuiltinOp("Y", DataType.MATRIX,
			ValueType.FP64, ParamBuiltinOp.REXPAND, parameters);
		// IPA/compiler facts know max_y=2 even though shape propagation deliberately
		// does not carry arbitrary scalar values through transient bindings.
		rexpand.setDim1(8);
		rexpand.setDim2(2);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(rexpand)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));

		assertShape(shapes.shape(owner(facts, rexpand)), 8, 2);
		Assert.assertEquals("live compiler HOP remains untouched", 8, rexpand.getDim1());
		Assert.assertEquals(2, rexpand.getDim2());
	}

	@Test
	public void privateViewsDiscardPriorPlannerSelectionsAndKeepTheNativeUniverseStable() {
		DataOp source = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		UnaryOp abs = new UnaryOp("abs", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, source);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(abs)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts, null, ignored -> sourceMetadata(8, 4));
		MemoRuleBuilder.Result pristine = MemoRuleBuilder.build(facts, sources, new MemoNativeRules());

		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			Hop live = facts.compiledOwnerHop(owner);
			live.setForcedExecType(ExecType.FED);
			live.setExecType(ExecType.FED);
			live.setFederatedOutput(FederatedOutput.FOUT);
			live.setFederatedOutputDerived(true);
			live.activatePrefetch();
			live.setPlannerPlacementSelected(true);
		}
		MemoRuleBuilder.Result replanned = MemoRuleBuilder.build(facts, sources, new MemoNativeRules());

		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			Assert.assertEquals(pristine.memo().rules(owner), replanned.memo().rules(owner));
			Assert.assertEquals(pristine.memo().complete(owner), replanned.memo().complete(owner));
			Hop view = replanned.shapes().view(owner);
			Assert.assertNull(view.getForcedExecType());
			Assert.assertNull(view.getExecType());
			Assert.assertEquals(FederatedOutput.NONE, view.getFederatedOutput());
			Assert.assertFalse(view.isFederatedOutputDerived());
			Assert.assertFalse(view.prefetchActivated());
			Assert.assertFalse(view.isPlannerPlacementSelected());
			Assert.assertNull(view.getLops());
			Hop live = facts.compiledOwnerHop(owner);
			Assert.assertEquals(ExecType.FED, live.getForcedExecType());
			Assert.assertEquals(FederatedOutput.FOUT, live.getFederatedOutput());
			Assert.assertTrue(live.isPlannerPlacementSelected());
		}
	}

	@Test
	public void exactSourceRangesDriveUnaryMultiplyTransposeWithoutMutatingLiveHops() {
		DataOp source = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		UnaryOp abs = new UnaryOp("abs", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, source);
		DataOp right = new DataOp("R", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "R", 4, 3, 12, 1000);
		AggBinaryOp mm = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, abs, right);
		ReorgOp transpose = new ReorgOp("t", DataType.MATRIX, ValueType.FP64, ReOrgOp.TRANS, abs);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(mm, transpose)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts, null, ignored -> sourceMetadata(8, 4));

		MemoShapes shapes = MemoShapes.analyze(facts, sources);
		int sourceOwner = owner(facts, source), absOwner = owner(facts, abs);
		int mmOwner = owner(facts, mm), transposeOwner = owner(facts, transpose);
		assertShape(shapes.shape(sourceOwner), 8, 4);
		assertShape(shapes.shape(absOwner), 8, 4);
		assertShape(shapes.shape(mmOwner), 8, 3);
		assertShape(shapes.shape(transposeOwner), 4, 8);
		Assert.assertEquals(8, shapes.hint(mmOwner).rowsA());
		Assert.assertEquals(4, shapes.hint(mmOwner).colsA());
		Assert.assertNotSame(source, shapes.view(sourceOwner));
		Assert.assertEquals("source acquisition must not rewrite the compiled HOP", -1, source.getDim1());
		Assert.assertEquals(-1, abs.getDim1());
	}

	@Test
	public void transientDefinitionAcrossStatementBlocksPreservesShape() throws Exception {
		MemoProgramFacts facts = facts("A=matrix(1,rows=8,cols=4);B=abs(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence abs = facts.occurrences().stream()
			.filter(o -> o.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.ABS)
			.findFirst().orElseThrow();
		assertShape(shapes.shape(abs.compiledOwnerId()), 8, 4);
	}

	@Test
	public void ctableOfAOneBasedSequenceAndSortIndexesHasExactPermutationShape() throws Exception {
		MemoProgramFacts facts=facts("X=matrix(0,rows=8,cols=1);"
			+"I=order(target=X,by=1,decreasing=TRUE,index.return=TRUE);"
			+"P=table(seq(1,8),I);print(sum(P));");
		MemoProgramFacts.Occurrence table=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof TernaryOp ternary && ternary.getOp()==OpOp3.CTABLE)
			.findFirst().orElseThrow();
		table.hop().setDim1(-1);
		table.hop().setDim2(-1);
		table.hop().setNnz(-1);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));

		assertShape(shapes.shapeAt(table.id(),EvaluationContext.empty()),8,8);
	}

	@Test
	public void ctablePermutationProofDoesNotOverrideExplicitTargetDimensions() throws Exception {
		MemoProgramFacts facts=facts("X=matrix(0,rows=8,cols=1);"
			+"I=order(target=X,by=1,decreasing=TRUE,index.return=TRUE);"
			+"P=table(seq(1,8),I,2,2);print(sum(P));");
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence table=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof TernaryOp ternary && ternary.getOp()==OpOp3.CTABLE)
			.findFirst().orElseThrow();

		assertShape(shapes.shapeAt(table.id(),EvaluationContext.empty()),2,2);
	}

	@Test
	public void branchMismatchBecomesUnknownInsteadOfChoosingOneArm() throws Exception {
		MemoProgramFacts facts = facts("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,rows=8,cols=4);}else{A=matrix(1,rows=7,cols=4);}"
			+ "B=abs(A);print(sum(B));");
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		MemoProgramFacts.Occurrence consumer = facts.occurrences().stream()
			.filter(o -> o.hop() instanceof UnaryOp unary && unary.getOp() == OpOp1.ABS)
			.filter(o -> o.context().startsWith("main"))
			.findFirst().orElseThrow();

		Assert.assertFalse(shapes.shape(consumer.compiledOwnerId()).exact());
		Assert.assertTrue(shapes.diagnostics().stream()
			.anyMatch(d -> "BRANCH_SHAPE_MISMATCH".equals(d.code())));
	}

	@Test
	public void loopEntryGroundsUniformBackedgeButRejectsChangingShape() throws Exception {
		MemoProgramFacts uniformFacts = facts(
			"A=matrix(1,rows=8,cols=4);for(i in 1:3){A=abs(A);}print(sum(A));");
		MemoShapes uniform = MemoShapes.analyze(uniformFacts, MemoSources.acquire(uniformFacts, null));
		MemoProgramFacts.CarriedValue carried = uniformFacts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		assertShape(uniform.bindingShape(carried.headBindingId()), 8, 4);

		MemoProgramFacts changingFacts = facts(
			"A=matrix(1,rows=8,cols=4);for(i in 1:3){A=t(A);}print(sum(A));");
		MemoShapes changing = MemoShapes.analyze(changingFacts, MemoSources.acquire(changingFacts, null));
		MemoProgramFacts.CarriedValue changed = changingFacts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		Assert.assertFalse(changing.bindingShape(changed.headBindingId()).exact());
		Assert.assertTrue(changing.diagnostics().stream()
			.anyMatch(d -> "LOOP_CARRIED_SHAPE_MISMATCH".equals(d.code())));
	}

	@Test
	public void numericScalarWideningKeepsPhaseTypesWithoutReportingAShapeMismatch() throws Exception {
		MemoProgramFacts facts=facts(
			"step_sz=0;for(i in 1:3){step_sz=step_sz-0.5;}print(step_sz);");
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.CarriedValue carried=facts.loop(0).carriedValues().stream()
			.filter(value -> "step_sz".equals(value.variable())).findFirst().orElseThrow();

		MemoShapes.Shape entry=shapes.bindingShape(carried.entryBindingId());
		MemoShapes.Shape repeat=shapes.bindingShape(carried.backedgeBindingId());
		MemoShapes.Shape summary=shapes.bindingShape(carried.headBindingId());
		Assert.assertTrue(entry.exact());
		Assert.assertEquals(DataType.SCALAR,entry.dataType());
		Assert.assertEquals(ValueType.INT64,entry.valueType());
		Assert.assertTrue(repeat.exact());
		Assert.assertEquals(DataType.SCALAR,repeat.dataType());
		Assert.assertEquals(ValueType.FP64,repeat.valueType());
		Assert.assertFalse(summary.exact());
		Assert.assertEquals(DataType.SCALAR,summary.dataType());
		Assert.assertEquals(ValueType.UNKNOWN,summary.valueType());
		Assert.assertFalse(shapes.diagnostics().stream()
			.anyMatch(d -> "LOOP_CARRIED_SHAPE_MISMATCH".equals(d.code())));
	}

	@Test
	public void sharedFunctionWithDifferentActualShapesDoesNotPretendUniform() throws Exception {
		DMLProgram program = parsed("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}");
		DataOp first = new DataOp("P", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "P", 8, 4, 32, 1000);
		DataOp second = new DataOp("Q", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "Q", 7, 4, 28, 1000);
		FunctionOp firstCall = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, "f",
			new String[] {"X"}, List.of(first), new String[] {"A"}, true);
		FunctionOp secondCall = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, "f",
			new String[] {"X"}, List.of(second), new String[] {"B"}, true);
		StatementBlock calls = new StatementBlock();
		calls.setHops(new ArrayList<>(List.of(firstCall, secondCall)));
		program.setStatementBlocks(new ArrayList<>(List.of(calls)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		int formal = facts.functionSummary(0).formalBindingIds().get(0);

		Assert.assertFalse(shapes.bindingShape(formal).exact());
		Assert.assertTrue(shapes.diagnostics().stream()
			.anyMatch(d -> "FUNCTION_ACTUAL_SHAPE_MISMATCH".equals(d.code())));
	}

	@Test
	public void sharedTransientOwnerRequiresAllOccurrenceBindingsToHaveOneShape() throws Exception {
		DMLProgram program = parsed("p=as.scalar(rand(rows=1,cols=1));A=matrix(1,rows=8,cols=4);"
			+ "if(p>0.5){B=abs(A);}else{B=A;}"
			+ "A=matrix(1,rows=7,cols=4);for(j in 1:2){print(j);}"
			+ "C=exp(A);print(sum(B)+sum(C));");
		UnaryOp abs = findUnary(program, OpOp1.ABS);
		UnaryOp exp = findUnary(program, OpOp1.EXP);
		Hop sharedRead = abs.getInput(0);
		org.apache.sysds.hops.rewrite.HopRewriteUtils.replaceChildReference(exp, exp.getInput(0), sharedRead);
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoShapes shapes = MemoShapes.analyze(facts, MemoSources.acquire(facts, null));
		int owner = facts.occurrences().stream().filter(o -> o.hop() == sharedRead)
			.findFirst().orElseThrow().compiledOwnerId();

		Assert.assertTrue("the same compiled TRead must appear in both statement contexts",
			facts.occurrences().stream().filter(o -> o.compiledOwnerId() == owner).count() >= 2);
		String bindings = facts.occurrences().stream().filter(o -> o.compiledOwnerId() == owner)
			.map(o -> o.context() + ":" + facts.valueBinding(o.id()) + ":"
				+ shapes.bindingShape(facts.valueBinding(o.id())))
			.collect(java.util.stream.Collectors.joining(","));
		Assert.assertFalse(bindings + " owner=" + shapes.shape(owner), shapes.shape(owner).exact());
		Assert.assertEquals("the common column axis remains independently proved", 4,
			shapes.shape(owner).columns());
		Assert.assertEquals(-1, shapes.shape(owner).rows());
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences()) {
			if(occurrence.compiledOwnerId() != owner) continue;
			MemoShapes.Shape binding = shapes.bindingShape(facts.valueBinding(occurrence.id()));
			Hop occurrenceView = shapes.viewAt(occurrence.id());
			Assert.assertEquals(binding.rows(), occurrenceView.getDim1());
			Assert.assertEquals(binding.columns(), occurrenceView.getDim2());
		}
		Assert.assertTrue(shapes.diagnostics().stream()
			.anyMatch(d -> "SHARED_OCCURRENCE_SHAPE_MISMATCH".equals(d.code())));
	}

	private static FederatedSourceMetadata sourceMetadata(long rows, long columns) {
		long split=rows/2;
		List<org.apache.commons.lang3.tuple.Pair<FederatedRange,FederatedData>> parts = List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0, 0}, new long[] {split, columns}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 19001), "X1")),
			new ImmutablePair<>(new FederatedRange(new long[] {split, 0}, new long[] {rows, columns}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 19002), "X2")));
		return new FederatedSourceMetadata(Privacy.PUBLIC, parts);
	}

	private static MemoProgramFacts facts(String script) throws Exception {
		return MemoProgramFacts.analyze(parsed(script));
	}

	private static DMLProgram rewrittenGlm() throws Exception {
		String resource="fedplanner/training-operations/glm.dml";
		try(InputStream stream=MemoShapesTest.class.getClassLoader().getResourceAsStream(resource)) {
			if(stream==null) throw new AssertionError("Missing fixture " + resource);
			String script=new String(stream.readAllBytes(),StandardCharsets.UTF_8)
				.replace("N = 50000","N = 192")
				.replace("D = 2100","D = 8")
				.replace("list(50000, 2100)","list(192, 8)")
				.replace("list(50000, 1)","list(192, 1)");
			DMLProgram program=ParserFactory.createParser().parse(resource,script,new HashMap<>());
			DMLTranslator translator=new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			return program;
		}
	}

	private static DMLProgram parsed(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static int owner(MemoProgramFacts facts, Hop hop) {
		return facts.compiledOwnerId(hop);
	}

	private static UnaryOp findUnary(DMLProgram program, OpOp1 operation) {
		for(StatementBlock block : program.getStatementBlocks()) {
			UnaryOp result = findUnary(block, operation);
			if(result != null) return result;
		}
		throw new AssertionError("Missing unary operation " + operation);
	}

	private static UnaryOp findUnary(StatementBlock block, OpOp1 operation) {
		if(block.getHops() != null)
			for(Hop root : block.getHops()) {
				UnaryOp result = findUnary(root, operation);
				if(result != null) return result;
			}
		if(block instanceof org.apache.sysds.parser.IfStatementBlock conditional) {
			org.apache.sysds.parser.IfStatement statement =
				(org.apache.sysds.parser.IfStatement) conditional.getStatement(0);
			for(StatementBlock child : statement.getIfBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
			for(StatementBlock child : statement.getElseBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
		}
		return null;
	}

	private static UnaryOp findUnary(Hop hop, OpOp1 operation) {
		if(hop instanceof UnaryOp unary && unary.getOp() == operation) return unary;
		for(Hop input : hop.getInput()) {
			UnaryOp result = findUnary(input, operation);
			if(result != null) return result;
		}
		return null;
	}

	private static void assertShape(MemoShapes.Shape shape, long rows, long columns) {
		Assert.assertTrue(String.valueOf(shape), shape.exact());
		Assert.assertEquals(rows, shape.rows());
		Assert.assertEquals(columns, shape.columns());
	}
}

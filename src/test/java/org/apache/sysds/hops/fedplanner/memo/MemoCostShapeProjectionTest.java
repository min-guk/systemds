/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class MemoCostShapeProjectionTest {
	private Path directory;
	@org.junit.After public void cleanup() throws Exception {
		if(directory!=null) try(var paths=Files.walk(directory)) {
			for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
	private String input(String name,int nnz) throws Exception {
		Path file=directory.resolve(name+".csv");
		Files.writeString(file,nnz==32 ? "1,1,1,1\n".repeat(8) : "1,1,0,0\n"+"0,0,0,0\n".repeat(7));
		Files.writeString(Path.of(file+".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\",\"format\":\"csv\",\"rows\":8,\"cols\":4,\"nnz\":"+nnz+"}");
		return file.toString();
	}
	@Test public void equalDimensionsWithDifferentNnzRetainCostGuard() throws Exception {
		directory=Files.createTempDirectory("memo-cost-shape");
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1));"
			+ "X=read(\""+input("dense",32)+"\");Y=read(\""+input("sparse",2)+"\");"
			+ "if(p>0.5){A=X;}else{A=Y;}B=A%*%matrix(1,4,2);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		for(var occurrence:facts.occurrences())
			if(occurrence.hop() instanceof DataOp data && data.getOp()==org.apache.sysds.common.Types.OpOpData.PERSISTENTREAD)
				data.setNnz(data.getName().equals("X") ? 32 : 2);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var multiply=facts.occurrences().stream().filter(value -> value.hop() instanceof AggBinaryOp)
			.findFirst().orElseThrow();
		var guards=shapes.guardSensitiveDecisions(multiply.id(),List.of());
		Assert.assertEquals("dimension equality does not establish sparsity-cost equality",1,guards.size());
		var guard=guards.iterator().next();
		Hop dense=shapes.viewAt(multiply.id(),context(guard,true));
		Hop sparse=shapes.viewAt(multiply.id(),context(guard,false));
		Assert.assertEquals(32,dense.getInput(0).getNnz());
		Assert.assertEquals(2,sparse.getInput(0).getNnz());
		var rule=new RuleMemo.Rule(ExecType.CP,List.of(MemoPartition.LOCAL,MemoPartition.LOCAL),
			List.of(MemoPartition.LOCAL),"cp/local");
		Assert.assertNotEquals(org.apache.sysds.hops.cost.ComputeCost.getHOPComputeCost(dense),
			org.apache.sysds.hops.cost.ComputeCost.getHOPComputeCost(sparse),0);
		// Memory-bound tiny kernels may have equal total estimates; FLOPs still
		// differ and must be retained for other shapes/hardware/cost contexts.
		Assert.assertTrue(MemoCostModel.nativeCost(dense,rule,1,1)>0);
	}

	@Test public void guardedScalarDimensionsAreResolvedPerEnvironment() throws Exception {
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){r=2;}else{r=4;}A=matrix(1,rows=r,cols=2);B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoShapes shapes=MemoShapes.analyze(facts,sources);
		var abs=facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS).findFirst().orElseThrow();
		var guards=shapes.guardSensitiveDecisions(abs.id(),List.of());
		Assert.assertEquals("scalar dimensions remain a real shape dependency",1,guards.size());
		var guard=guards.iterator().next();
		Assert.assertEquals(2,shapes.viewAt(abs.id(),context(guard,true)).getDim1());
		Assert.assertEquals(4,shapes.viewAt(abs.id(),context(guard,false)).getDim1());
		var rules=MemoRuleBuilder.build(facts,sources,new MemoNativeRules(),shapes);
		var model=new MemoProgramModel(facts,sources,rules,MemoProgramModel.Profile.defaults());
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,
			model.optimize(MemoOptimizer.Limits.exactDefaults()).optimization().status());
	}

	@Test public void guardedFp64SequenceBoundsAreResolvedPerEnvironment() throws Exception {
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){n=10.0;}else{n=100.0;}A=seq(1,n,1);B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var abs=facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS).findFirst().orElseThrow();
		var guards=shapes.guardSensitiveDecisions(abs.id(),List.of());
		Assert.assertEquals("FP64 SEQ bounds remain an exact shape dependency",1,guards.size());
		var guard=guards.iterator().next();
		Assert.assertEquals(10,shapes.viewAt(abs.id(),context(guard,true)).getDim1());
		Assert.assertEquals(100,shapes.viewAt(abs.id(),context(guard,false)).getDim1());
	}

	@Test public void contextualCloneRetainsSixCostDescendants() throws Exception {
		DMLProgram program=parse("A=matrix(1,rows=8,cols=4);"
			+ "B=abs(abs(abs(abs(abs(abs(abs(A)))))));print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var root=facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS && value.hop().getParent().stream()
				.noneMatch(parent -> parent instanceof UnaryOp parentUnary && parentUnary.getOp()==OpOp1.ABS))
			.findFirst().orElseThrow();
		Hop view=shapes.viewAt(root.id(),MemoBindingResolver.EvaluationContext.empty());
		for(int depth=0;depth<6;depth++) {
			Assert.assertFalse("cost descendant truncated at depth "+depth,view.getInput().isEmpty());
			view=view.getInput(0);
		}
	}

	@Test public void loopPhaseInvalidatesCanonicalGuardRepresentative() throws Exception {
		DMLProgram program=parse("A=matrix(1,rows=1,cols=1);for(i in 1:2){"
			+ "if(i==1){A=matrix(1,rows=2,cols=1);}else{A=matrix(1,rows=5,cols=1);}}"
			+ "B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var consumer=facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS && facts.binding(facts.inputBinding(value.id(),0)).kind()
				== MemoProgramFacts.BindingKind.LAST).findFirst().orElseThrow();
		var outerGuard=facts.guards().stream().filter(value -> Boolean.FALSE.equals(
			facts.guardValue(0,value.id(),MemoProgramFacts.IterationPhase.LAST))).findFirst().orElseThrow();
		var canonical=facts.guardDecision(outerGuard.id(),List.of());
		var guard=new MemoBindingResolver.GuardDecision(outerGuard.id()+1000,List.of(77),
			canonical.valueBindingId(),canonical.valueFrame());
		Assert.assertEquals(canonical,guard);
		var loop=new MemoBindingResolver.LoopDecision(0,List.of());
		var cardinality=new MemoBindingResolver.LoopCardinalityDecision(0,List.of());
		var context=new MemoBindingResolver.EvaluationContext(Map.of(guard,true),
			Map.of(loop,MemoBindingResolver.LoopPhase.BACKEDGE),MemoBindingResolver.NO_SELECTION,4096,
			List.of(),Map.of(loop,MemoProgramFacts.IterationPhase.FIRST),
			Map.of(cardinality,MemoProgramFacts.LoopCardinality.MANY));
		Assert.assertEquals("LAST phase must replace the stale first-iteration formal guard",5,
			shapes.inputShape(consumer.id(),0,context).rows());
	}

	@Test public void transformEncodeRecodeOnlyClonesAndShapesPrimaryOutput() throws Exception {
		DataOp frame=new DataOp("F",DataType.FRAME,ValueType.STRING,
			OpOpData.FEDERATED,"F",4,2,-1,1000);
		DataOp encoded=new DataOp("X",DataType.MATRIX,ValueType.FP64,
			frame,OpOpData.FUNCTIONOUTPUT,"X");
		DataOp metadata=new DataOp("M",DataType.FRAME,ValueType.STRING,
			frame,OpOpData.FUNCTIONOUTPUT,"M");
		FunctionOp transform=new FunctionOp(FunctionOp.FunctionType.MULTIRETURN_BUILTIN,
			"_internal","transformencode",null,
			List.of(frame,new LiteralOp("{\"ids\":true,\"recode\":[1]}")),
			new String[] {"X","M"},new java.util.ArrayList<>(List.of(encoded,metadata)));
		StatementBlock block=new StatementBlock();
		block.setHops(new java.util.ArrayList<>(List.of(transform)));
		DMLProgram program=new DMLProgram();
		program.setStatementBlocks(new java.util.ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null,ignored -> {
			var address=new java.net.InetSocketAddress("localhost",19001);
			var range=new org.apache.sysds.runtime.controlprogram.federated.FederatedRange(
				new long[] {0,0},new long[] {4,2});
			var data=new org.apache.sysds.runtime.controlprogram.federated.FederatedData(
				DataType.FRAME,address,"F");
			return new org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata(
				org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC,
				List.of(new org.apache.commons.lang3.tuple.ImmutablePair<>(range,data)));
		}));
		var occurrenceFact=facts.occurrences().stream().filter(value -> value.hop()==transform)
			.findFirst().orElseThrow();
		int occurrence=occurrenceFact.id();
		Assert.assertEquals(4,shapes.inputShape(occurrence,0,
			MemoBindingResolver.EvaluationContext.empty()).rows());
		FunctionOp view=(FunctionOp)shapes.viewAt(occurrence,MemoBindingResolver.EvaluationContext.empty());
		Assert.assertEquals(4,view.getInput(0).getDim1());
		Assert.assertNotSame("private cost view must not expose the compiled output Hop",encoded,view.getOutputs().get(0));
		Assert.assertEquals(4,view.getOutputs().get(0).getDim1());
		Assert.assertEquals(2,view.getOutputs().get(0).getDim2());
		Assert.assertEquals("compiled output remains untouched",-1,encoded.getDim1());
	}

	private static MemoBindingResolver.EvaluationContext context(MemoBindingResolver.GuardDecision guard,
		boolean arm) {
		return new MemoBindingResolver.EvaluationContext(Map.of(guard,arm),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of());
	}
	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);translator.validateParseTree(program);translator.constructHops(program);
		return program;
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class MemoResidencyTest {
	@Test
	public void groundedLoopProvesReleasedWithoutInventingPrivacyProvenance() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,2,2);A=X;i=1;while(i<3){A=abs(A);i=i+1;}"+
			"B=A+1;print(sum(B));");
		MemoResidency proof=new MemoResidency(facts,
			Map.of(owner(facts,"X"),Privacy.PRIVATE_AGGREGATE_TO_PUBLIC));
		int bodyAbs=occurrence(facts,"A",true),consumer=occurrence(facts,"B",false);
		Assert.assertTrue(proof.provesReleasedOccurrence(bodyAbs));
		Assert.assertTrue(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
	}

	@Test
	public void mutuallyCarriedLoopValuesUseOneGroundedInduction() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,2,2);B=matrix(2,2,2);i=1;while(i<3){"+
			"oldA=A;A=B+1;B=oldA+1;i=i+1;}C=A+B;print(sum(C));");
		MemoResidency proof=new MemoResidency(facts,Map.of());
		int consumer=occurrence(facts,"C",false);
		Assert.assertTrue(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
		Assert.assertTrue(proof.provesReleasedBinding(facts.inputBinding(consumer,1)));
		Assert.assertTrue(proof.provesReleasedOccurrence(consumer));
	}

	@Test
	public void loopInductionCrossesNonRecursiveFunctionBoundary() throws Exception {
		MemoProgramFacts facts=analyze("step=function(matrix[double] X)return(matrix[double] Y){Y=abs(X);}"+
			"A=matrix(1,2,2);i=1;while(i<3){A=step(A);i=i+1;}B=A+1;print(sum(B));");
		MemoResidency proof=new MemoResidency(facts,Map.of());
		int consumer=occurrence(facts,"B",false);
		Assert.assertTrue(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
		Assert.assertTrue(proof.provesReleasedOccurrence(consumer));
	}

	@Test
	public void aggregateReleaseIsProvenButStrictPrivateAndAggregateOnlyAreNot() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,rows=2,cols=2);A=abs(X);s=sum(X);print(sum(A)+s);");
		int source=owner(facts,"X");
		int abs=facts.occurrences().stream().filter(o -> o.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS).mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int sum=facts.occurrences().stream().filter(o -> o.hop() instanceof AggUnaryOp aggregate
			&& aggregate.getOp()==AggOp.SUM).mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		MemoResidency aggregate=new MemoResidency(facts,Map.of(source,Privacy.PRIVATE_AGGREGATE));
		Assert.assertFalse(aggregate.provesReleasedOccurrence(abs));
		Assert.assertTrue(aggregate.provesReleasedOccurrence(sum));
		MemoResidency strict=new MemoResidency(facts,Map.of(source,Privacy.PRIVATE));
		Assert.assertFalse(strict.provesReleasedOccurrence(abs));
		Assert.assertFalse(strict.provesReleasedOccurrence(sum));
	}

	@Test
	public void branchWithUnboundArmCannotProveValueExistenceOrReleasedResidency() throws Exception {
		MemoProgramFacts facts=analyze("p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=matrix(1,2,2);}"+
			"B=A+1;print(sum(B));");
		MemoResidency proof=new MemoResidency(facts,Map.of());
		int consumer=occurrence(facts,"B",false);
		Assert.assertFalse(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
		Assert.assertFalse(proof.provesReleasedOccurrence(consumer));
	}

	@Test
	public void loopCannotTurnConditionallyUnboundEntryIntoReleasedProof() throws Exception {
		MemoProgramFacts facts=analyze("p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=matrix(1,2,2);}"+
			"i=1;while(i<3){A=abs(A);i=i+1;}B=A+1;print(sum(B));");
		MemoResidency proof=new MemoResidency(facts,Map.of());
		int consumer=occurrence(facts,"B",false);
		Assert.assertFalse(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
		Assert.assertFalse(proof.provesReleasedOccurrence(consumer));
	}

	@Test
	public void unknownExternalSourceDoesNotBecomeReleasedThroughASeparatePublicOperand() throws Exception {
		DataOp source=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.PERSISTENTREAD,
			"missing.csv",2,2,4,1000);
		BinaryOp plus=new BinaryOp("B",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,source,new LiteralOp(1));
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(plus)));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoResidency proof=new MemoResidency(facts,Map.of());
		int consumer=occurrence(facts,"B",false);
		Assert.assertFalse(proof.provesReleasedBinding(facts.inputBinding(consumer,0)));
		Assert.assertFalse(proof.provesReleasedOccurrence(consumer));
		int sourceOwner=facts.occurrences().stream().filter(o -> o.hop()==source)
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
		Assert.assertTrue(new MemoResidency(facts,Map.of(sourceOwner,Privacy.PUBLIC))
			.provesReleasedOccurrence(consumer));
		Assert.assertFalse(new MemoResidency(facts,Map.of(sourceOwner,Privacy.PRIVATE))
			.provesReleasedOccurrence(consumer));
	}

	@Test
	public void missingFunctionReturnArmCannotProveReleasedResidency() throws Exception {
		MemoProgramFacts facts=analyze("f=function(boolean p) return(matrix[double] Y){"
			+"if(p){Y=matrix(1,2,2);}}p=as.scalar(rand(rows=1,cols=1));A=f(p);print(sum(A));");
		Assert.assertEquals(1,facts.calls().size());
		int returned=facts.call(0).returnBindingIds().get(0);
		MemoResidency proof=new MemoResidency(facts,Map.of());
		Assert.assertFalse("the missing arm is an execution failure, not a released value",
			proof.provesReleasedBinding(returned));
	}

	@Test
	public void recursiveFunctionIsRejectedBeforeResidencyCanBecomeAuthority() throws Exception {
		MemoProgramFacts facts=analyze("f=function(double x) return(double y){if(x<=0){y=1;}"
			+"else{y=f(x-1);}}z=f(2);print(z);");
		Assert.assertEquals(MemoProgramFacts.Status.UNSUPPORTED,facts.status());
		Assert.assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
			"RECURSIVE_FUNCTION".equals(diagnostic.code())));
	}

	@Test
	public void calledFormalJoinsAllActualPrivacyWithoutSourceCartesianProducts() throws Exception {
		MemoProgramFacts facts=analyze("f=function(matrix[double] X)return(matrix[double] Y){Y=abs(X);}"+
			"P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);print(sum(A)+sum(B));");
		MemoResidency released=new MemoResidency(facts,Map.of(owner(facts,"P"),Privacy.PUBLIC,
			owner(facts,"Q"),Privacy.PRIVATE_AGGREGATE_TO_PUBLIC));
		int functionAbs=facts.occurrences().stream().filter(o -> o.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS && o.context().contains("function")).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
		Assert.assertTrue(released.provesReleasedOccurrence(functionAbs));
		MemoResidency mixed=new MemoResidency(facts,Map.of(owner(facts,"P"),Privacy.PUBLIC,
			owner(facts,"Q"),Privacy.PRIVATE));
		Assert.assertFalse(mixed.provesReleasedOccurrence(functionAbs));
	}

	@Test
	public void abstractTransferOverapproximatesEveryConcretePrivacyTuple() {
		Hop x=matrix("X"),y=matrix("Y");
		List<Hop> operators=List.of(
			new UnaryOp("abs",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,x),
			new AggUnaryOp("sum",DataType.SCALAR,ValueType.FP64,AggOp.SUM,Direction.RowCol,x),
			new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,x,y),
			new AggBinaryOp("mm",DataType.MATRIX,ValueType.FP64,OpOp2.MULT,AggOp.SUM,x,y));
		for(Hop hop : operators) verifyConcreteTuples(hop);
	}

	private static void verifyConcreteTuples(Hop hop) {
		int arity=hop.getInput().size(),combinations=(int)Math.pow(Privacy.values().length,arity);
		for(int mask=0;mask<combinations;mask++) {
			int value=mask; int[] classes=new int[arity];
			java.util.ArrayList<Privacy> concrete=new java.util.ArrayList<>(arity);
			for(int i=0;i<arity;i++) {
				Privacy privacy=Privacy.values()[value%Privacy.values().length]; value/=Privacy.values().length;
				concrete.add(privacy); classes[i]=privacyClass(privacy);
			}
			int abstractResult=MemoResidency.abstractTransfer(hop,classes);
			Privacy exact=FederatedPlannerUtils.derivePrivacyConstraint(hop,concrete);
			if(exact==Privacy.PUBLIC && concrete.contains(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC))
				exact=Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			Assert.assertTrue(hop+" inputs="+concrete+" abstract="+abstractResult+" exact="+exact,
				privacyClass(exact)<=abstractResult);
		}
	}

	private static int privacyClass(Privacy privacy) {
		return switch(privacy) {
			case PUBLIC, PRIVATE_AGGREGATE_TO_PUBLIC -> 0;
			case PRIVATE_AGGREGATE -> 1;
			case PRIVATE -> 2;
		};
	}
	private static DataOp matrix(String name) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,name,2,2,4,1000);
	}
	private static int owner(MemoProgramFacts facts,String name) {
		return facts.occurrences().stream().filter(o -> name.equals(o.hop().getName())
			&& !o.context().contains("loop-")).mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId)
			.findFirst().orElseThrow();
	}
	private static int occurrence(MemoProgramFacts facts,String name,boolean inLoop) {
		return facts.occurrences().stream().filter(o -> name.equals(o.hop().getName())
			&& o.context().contains("/loop-")==inLoop).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
	}
	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}

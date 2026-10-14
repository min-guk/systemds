package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

public class MemoCheckStateModelSignatureTest {
	@Test public void guardedLocalPrivacyRowsKeepExactCompleteClasses() throws Exception {
		MemoProgramModel model=model("f=function(boolean p,boolean q,matrix[double] X) "
			+"return(matrix[double] Y){if(p){if(q){Y=X;}else{Y=matrix(2,rows=4,cols=2);}}else{Y=X;}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p,q,P);for(i in 1:2){A=abs(A);}print(sum(A));",
			Map.of("P",new SourceSpec(4,2,Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)));
		ProofCounts counts=verifyAnnotatedClassProofs(model);
		Assert.assertTrue("fixture must expose annotated model rows",counts.annotated()>0);
	}
	@Test public void modelLayoutSignatureSeparatesNullLocalAndCompositeOrder() throws Exception {
		Method pair=MemoProgramModel.class.getDeclaredMethod("layoutPair",MemoPartition.class,MemoPartition.class);
		pair.setAccessible(true);
		int nullLocal=(int)pair.invoke(null,null,MemoPartition.LOCAL);
		int localNull=(int)pair.invoke(null,MemoPartition.LOCAL,null);
		int rowLocal=(int)pair.invoke(null,MemoPartition.ROW,MemoPartition.LOCAL);
		Assert.assertNotEquals(nullLocal,localNull);
		Assert.assertNotEquals(nullLocal,rowLocal);
		Assert.assertNotEquals(localNull,rowLocal);
	}

	@Test public void multiOutputRowsKeepExactClasses() throws Exception {
		MemoProgramModel model=model("X=matrix(1,rows=4,cols=2);[Q,R]=qr(X);A=P;for(i in 1:2){A=abs(A);}"
			+"C=abs(R);print(sum(A)+sum(Q)+sum(C));",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC)));
		ProofCounts counts=verifyAnnotatedClassProofs(model);
		Assert.assertTrue(counts.annotated()>0);
	}

	@Test public void incompleteBoundaryProjectionRetainsUnannotatedBroadCheck() throws Exception {
		MemoProgramModel model=model("f=function(matrix[double] X) return(matrix[double] Y){A=X;"
			+"for(i in 1:2){A=A;}Y=A;}R=f(P);S=f(Q);print(\"done\");",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC),"Q",new SourceSpec(6,2,Privacy.PUBLIC)));
		Field classes=field(MemoOptimizer.Check.class,"truthClasses");
		List<MemoOptimizer.Check> boundaries=model.checks().stream().filter(check -> check.site()<0).toList();
		Assert.assertFalse(boundaries.isEmpty());
		for(MemoOptimizer.Check check:boundaries)
			Assert.assertNull("incomplete broad boundary must not claim class proof",classes.get(check));
	}

	private static ProofCounts verifyAnnotatedClassProofs(MemoProgramModel model) throws Exception {
		Field classesField=field(MemoOptimizer.Check.class,"truthClasses");
		Field predicateField=field(MemoOptimizer.Check.class,"predicate");
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(model)).clone();
		int annotated=0,unary=0,unknown=0;
		for(MemoOptimizer.Check check:model.checks()) {
			int[][] classes=(int[][])classesField.get(check);
			if(classes==null) continue;
			annotated++; if(check.owners().length==1) unary++;
			MemoOptimizer.Predicate predicate=(MemoOptimizer.Predicate)predicateField.get(check);
			Map<String,MemoOptimizer.Truth> byClass=new LinkedHashMap<>();
			int[] tuple=new int[check.owners().length];
			long combinations=1;
			for(int owner:check.owners()) combinations=Math.multiplyExact(combinations,sizes[owner]);
			Assert.assertTrue("row check must remain a small complete proof",combinations<=256);
			for(long index=0;index<combinations;index++) {
				MemoOptimizer.Truth truth=predicate.test(tuple.clone());
				if(truth==MemoOptimizer.Truth.UNKNOWN) unknown++;
				int[] classTuple=new int[tuple.length];
				for(int position=0;position<tuple.length;position++) classTuple[position]=classes[position][tuple[position]];
				String key=Arrays.toString(classTuple);
				MemoOptimizer.Truth prior=byClass.putIfAbsent(key,truth);
				if(prior!=null) Assert.assertEquals("complete Truth differs inside class "+key,prior,truth);
				increment(tuple,check.owners(),sizes);
			}
			int[] partial=new int[tuple.length]; Arrays.fill(partial,-1);
			Assert.assertNotNull("partial tuple must keep original callback semantics",predicate.test(partial));
		}
		return new ProofCounts(annotated,unary,unknown);
	}

	private static void increment(int[] tuple,int[] owners,int[] sizes) {
		for(int position=tuple.length-1;position>=0;position--) {
			if(++tuple[position]<sizes[owners[position]]) return;
			tuple[position]=0;
		}
	}

	private static MemoProgramModel model(String body,Map<String,SourceSpec> specs) throws Exception {
		StringBuilder script=new StringBuilder();
		for(var entry:specs.entrySet()) {
			SourceSpec spec=entry.getValue(); long split=spec.rows()/2;
			script.append(entry.getKey()).append("=federated(addresses=list(\"localhost:19101/")
				.append(entry.getKey()).append("1\",\"localhost:19102/").append(entry.getKey())
				.append("2\"),ranges=list(list(0,0),list(").append(split).append(',').append(spec.columns())
				.append("),list(").append(split).append(",0),list(").append(spec.rows()).append(',')
				.append(spec.columns()).append(")));\n");
		}
		script.append(body);
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script.toString(),new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data,specs.get(data.getName())));
		MemoRuleBuilder.Result rules=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		return new MemoProgramModel(facts,sources,rules,MemoProgramModel.Profile.defaults(),true);
	}
	private static FederatedSourceMetadata metadata(DataOp data,SourceSpec spec) {
		Assert.assertNotNull("missing source metadata for "+data.getName(),spec);
		long split=spec.rows()/2;
		return new FederatedSourceMetadata(spec.privacy(),List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {split,spec.columns()}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),data.getName()+"1")),
			new ImmutablePair<>(new FederatedRange(new long[] {split,0},new long[] {spec.rows(),spec.columns()}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),data.getName()+"2"))));
	}
	private static Field field(Class<?> type,String name) throws Exception {
		Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private record SourceSpec(long rows,long columns,Privacy privacy) { }
	private record ProofCounts(int annotated,int unary,int unknown) { }
}

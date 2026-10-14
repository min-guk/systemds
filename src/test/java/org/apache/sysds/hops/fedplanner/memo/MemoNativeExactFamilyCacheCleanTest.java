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
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeExactFamilyCacheCleanTest {
	@Test public void graphAndLoweringStateDoNotChangeTransposeNativeResult() {
		Hop ordinaryInput=matrix("ordinary",8,4);
		ReorgOp ordinary=transpose("ordinaryT",ordinaryInput);
		Hop nestedInput=transpose("inner",matrix("nested",4,8));
		ReorgOp nested=transpose("nestedT",nestedInput);
		Hop compressedInput=matrix("compressed",8,4);
		compressedInput.setCompressedOutput(true); compressedInput.setCompressedSize(17);
		ReorgOp compressed=transpose("compressedT",compressedInput);
		ReorgOp selected=transpose("selectedT",matrix("selected",8,4));
		selected.setPlannerPlacementSelected(true);
		ShapeHint hint=shape(4,8,8,4);

		MemoNativeRules.RequestFactory factory=new MemoNativeRules.RequestFactory();
		var requests=List.of(factory.prepare(ordinary,3,hint),factory.prepare(nested,3,hint),
			factory.prepare(compressed,3,hint),factory.prepare(selected,3,hint));
		for(var request:requests) Assert.assertEquals(requests.get(0).key(),request.key());

		MemoNativeRules.Result expected=new MemoNativeRules().rules(ordinary,3,hint);
		for(Hop hop:List.of(nested,compressed,selected)) assertEquivalent(expected,
			new MemoNativeRules().rules(hop,3,hint));

		MemoNativeRules catalog=new MemoNativeRules();
		MemoNativeRules.Result first=catalog.rules(requests.get(0));
		for(int i=1;i<requests.size();i++) Assert.assertSame(first,catalog.rules(requests.get(i)));
		assertEquivalent(expected,first);
		Assert.assertEquals(new MemoNativeRules.CacheStats(3,1,1),catalog.cacheStats());
	}

	@Test public void oneByOneTransposeSharesOnlyItsExactFullKey() {
		MemoNativeRules.RequestFactory factory=new MemoNativeRules.RequestFactory();
		var first=factory.prepare(transpose("a",matrix("A",1,1)),2,shape(1,1,1,1));
		var selectedHop=transpose("b",matrix("B",1,1)); selectedHop.setPlannerPlacementSelected(true);
		var selected=factory.prepare(selectedHop,2,shape(1,1,1,1));
		var workers=factory.prepare(transpose("c",matrix("C",1,1)),3,shape(1,1,1,1));
		var dimensions=factory.prepare(transpose("d",matrix("D",2,1)),2,shape(1,2,2,1));
		var block=factory.prepare(transpose("e",matrix("E",1,1)),2,
			new ShapeHint(1,1,512,Optional.empty(),1,1,-1,-1));
		var full=factory.prepare(transpose("f",matrix("F",1,1)),2,
			new ShapeHint(1,1,1000,Optional.of(true),1,1,-1,-1));
		Assert.assertEquals(first.key(),selected.key());
		Assert.assertNotEquals(first.key(),workers.key());
		Assert.assertNotEquals(first.key(),dimensions.key());
		Assert.assertNotEquals(first.key(),block.key());
		Assert.assertNotEquals(first.key(),full.key());
	}

	@Test public void reshapeByrowVariantsRemainUncacheable() {
		MemoNativeRules.RequestFactory factory=new MemoNativeRules.RequestFactory();
		var rowMajor=factory.prepare(reshape("row",true),2,shape(4,8,8,4));
		var columnMajor=factory.prepare(reshape("column",false),2,shape(4,8,8,4));
		Assert.assertNull(rowMajor.key());
		Assert.assertNull(columnMajor.key());
		Assert.assertEquals(2,factory.statistics().uncacheableRequests());
	}

	@Test public void descriptorAndActualViewHaveIdenticalFullTransposeRequests() {
		Hop compressedInput=matrix("compressed",8,4);
		compressedInput.setCompressedOutput(true); compressedInput.setCompressedSize(17);
		ReorgOp selected=transpose("selected",matrix("selectedInput",8,4));
		selected.setPlannerPlacementSelected(true);
		ReorgOp nested=transpose("nested",transpose("inner",matrix("nestedInput",4,8)));
		ReorgOp unknown=transpose("unknown",matrix("unknownInput",-1,-1));
		for(ReorgOp actual:List.of(transpose("ordinary",matrix("ordinaryInput",8,4)),
			transpose("compressed",compressedInput),selected,nested,unknown)) {
			long outputRows=actual.getDim1(),outputCols=actual.getDim2();
			long inputRows=actual.getInput(0).getDim1(),inputCols=actual.getInput(0).getDim2();
			ShapeHint hint=shape(outputRows,outputCols,inputRows,inputCols);
			MemoShapes.Knowledge knowledge=outputRows>0 && outputCols>0
				? MemoShapes.Knowledge.EXACT : MemoShapes.Knowledge.UNKNOWN;
			MemoShapes.NativeShapeDescriptor descriptor=new MemoShapes.NativeShapeDescriptor(
				new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,outputRows,outputCols,-1,knowledge),
				List.of(new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,inputRows,inputCols,-1,knowledge)),
				actual.getBlocksize(),hint);
			MemoNativeRules.RequestFactory descriptorFactory=new MemoNativeRules.RequestFactory();
			Assert.assertTrue(descriptorFactory.descriptorCapable(actual));
			var direct=descriptorFactory.prepare(actual,3,descriptor);
			var legacy=new MemoNativeRules.RequestFactory().prepare(actual,3,hint);
			Assert.assertEquals(actual.getName(),legacy.key(),direct.key());
			MemoNativeRules catalog=new MemoNativeRules();
			Assert.assertSame(catalog.rules(direct.withHop(actual)),catalog.rules(legacy));
		}
	}

	@Test public void exactFamiliesRetainArityOrderGeometryAndIgnoreLiteralValues() {
		MemoNativeRules.RequestFactory factory=new MemoNativeRules.RequestFactory();
		NaryOp plus2=nary("plus2",OpOpN.PLUS,matrix("A",8,4),matrix("B",8,4));
		NaryOp plus3=nary("plus3",OpOpN.PLUS,matrix("A3",8,4),matrix("B3",8,4),matrix("C3",8,4));
		NaryOp mult2=nary("mult2",OpOpN.MULT,matrix("M1",8,4),matrix("M2",8,4));
		var plus2Request=factory.prepare(plus2,3,shape(8,4,8,4));
		Assert.assertNotEquals(plus2Request.key(),factory.prepare(plus3,3,shape(8,4,8,4)).key());
		Assert.assertNotEquals(plus2Request.key(),factory.prepare(mult2,3,shape(8,4,8,4)).key());

		TernaryOp literal2=ternary("literal2",OpOp3.PLUS_MULT,matrix("X",8,4),
			new LiteralOp(2d),matrix("Y",8,4));
		TernaryOp literal7=ternary("literal7",OpOp3.PLUS_MULT,matrix("X2",8,4),
			new LiteralOp(7d),matrix("Y2",8,4));
		var literal2Request=factory.prepare(literal2,3,shape(8,4,8,4));
		var literal7Request=factory.prepare(literal7,3,shape(8,4,8,4));
		Assert.assertEquals("literal value is not a placement determinant",
			literal2Request.key(),literal7Request.key());
		assertEquivalent(new MemoNativeRules().rules(literal2,3,shape(8,4,8,4)),
			new MemoNativeRules().rules(literal7,3,shape(8,4,8,4)));

		TernaryOp thirdWide=ternary("thirdWide",OpOp3.PLUS_MULT,matrix("P",8,4),
			new LiteralOp(2d),matrix("wide",8,5));
		Assert.assertNotEquals(literal2Request.key(),
			factory.prepare(thirdWide,3,new ShapeHint(8,4,1000,Optional.empty(),8,4,-1,-1)).key());
		TernaryOp scalarLeading=ternary("ifScalar",OpOp3.IFELSE,new LiteralOp(true),
			matrix("then",8,4),matrix("else",8,4));
		TernaryOp payloadLeading=ternary("ifPayload",OpOp3.IFELSE,matrix("condition",8,4),
			new LiteralOp(1d),matrix("else2",8,4));
		ShapeHint identicalHint=shape(8,4,8,4);
		Assert.assertNotEquals(factory.prepare(scalarLeading,3,identicalHint).key(),
			factory.prepare(payloadLeading,3,identicalHint).key());
	}

	@Test public void excludedNaryAndTernaryFamiliesRemainUncacheable() {
		MemoNativeRules.RequestFactory factory=new MemoNativeRules.RequestFactory();
		Assert.assertNull(factory.prepare(nary("cbind",OpOpN.CBIND,
			matrix("A",8,4),matrix("B",8,4)),3,shape(8,8,8,4)).key());
		Assert.assertNull(factory.prepare(ternary("map",OpOp3.MAP,matrix("M",8,4),
			new LiteralOp(1d),new LiteralOp(2d)),3,shape(8,4,8,4)).key());
		Assert.assertNull(factory.prepare(ternary("ctable",OpOp3.CTABLE,matrix("C",8,4),
			matrix("D",8,4),new LiteralOp(1d)),3,shape(8,4,8,4)).key());
	}

	@Test public void sharedInputDescriptorUsesLastPositionalShape() {
		Hop shared=matrix("shared",8,4);
		NaryOp template=nary("sharedPlus",OpOpN.PLUS,shared,shared);
		MemoShapes.Shape first=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,8,4,-1,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.Shape last=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,16,4,-1,
			MemoShapes.Knowledge.EXACT);
		List<MemoShapes.Shape> effective=MemoShapes.effectiveInputShapes(template,List.of(first,last));
		Assert.assertEquals(List.of(last,last),effective);
		ShapeHint hint=shape(16,4,16,4);
		MemoShapes.NativeShapeDescriptor descriptor=new MemoShapes.NativeShapeDescriptor(last,effective,
			template.getBlocksize(),hint);
		var direct=new MemoNativeRules.RequestFactory().prepare(template,3,descriptor);
		shared.setDim1(16); template.setDim1(16);
		var actual=new MemoNativeRules.RequestFactory().prepare(template,3,hint);
		Assert.assertEquals(actual.key(),direct.key());
	}

	@Test public void realShapeDescriptorPathMatchesIndependentContextualView() throws Exception {
		java.util.Set<String> seenFamilies=new java.util.LinkedHashSet<>();
		boolean found=false,loopContext=false,unknownContext=false;
		for(String workload:List.of("logreg","glm")) {
			MemoProgramFacts facts=MemoProgramFacts.analyze(parsed(workload)); facts.requireComplete();
			MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data.getName()));
			MemoShapes descriptorShapes=MemoShapes.analyze(facts,sources);
			MemoShapes viewShapes=MemoShapes.analyze(facts,sources);
			MemoReachableContexts reachable=new MemoReachableContexts(facts,new MemoBindingResolver(facts));
			for(var occurrence:facts.occurrences()) {
				if(!admitted(occurrence.hop())) continue;
				seenFamilies.add(family(occurrence.hop()));
				for(var occurrenceContext:facts.occurrenceContexts(occurrence.id())) {
					var base=new MemoBindingResolver.EvaluationContext(Map.of(),Map.of(),
						MemoBindingResolver.NO_SELECTION,4096,occurrenceContext.callFrame(),Map.of());
					var contexts=new java.util.ArrayList<MemoBindingResolver.EvaluationContext>();
					reachable.visit(occurrence,base,context -> { contexts.add(context); return true; });
					for(var context:contexts) {
						MemoShapes.NativeShapeDescriptor descriptor=
							descriptorShapes.nativeDescriptorAt(occurrence.id(),context);
						Hop view=viewShapes.viewAt(occurrence.id(),context);
						var direct=new MemoNativeRules.RequestFactory().prepare(
							occurrence.hop(),3,descriptor);
						var legacy=new MemoNativeRules.RequestFactory().prepare(
							view,3,MemoShapes.hintForView(view));
						Assert.assertEquals(workload+" occurrence="+occurrence.id(),legacy.key(),direct.key());
						MemoNativeRules catalog=new MemoNativeRules();
						Assert.assertSame(catalog.rules(direct.withHop(view)),catalog.rules(legacy));
						found=true;
						loopContext|=!context.iterationPhases().isEmpty();
						unknownContext|=descriptor.output().knowledge()!=MemoShapes.Knowledge.EXACT
							|| descriptor.inputs().stream().anyMatch(shape ->
								shape.knowledge()!=MemoShapes.Knowledge.EXACT);
					}
				}
			}
		}
		Assert.assertTrue("rewritten fixtures must contain an admitted occurrence",found);
		Assert.assertEquals(java.util.Set.of("TRANS","NARY_PLUS","NARY_MULT","TERNARY_IFELSE",
			"TERNARY_PLUS_MULT","TERNARY_MINUS_MULT"),seenFamilies);
		Assert.assertTrue("comparison must include a loop or UNKNOWN admitted context",loopContext||unknownContext);
		System.out.printf("EXACT_FAMILY_DESCRIPTOR_CONTEXT loop=%s unknown=%s%n",loopContext,unknownContext);
	}



	private static void assertEquivalent(MemoNativeRules.Result expected,MemoNativeRules.Result actual) {
		Assert.assertEquals(expected.rules(),actual.rules());
		Assert.assertEquals(expected.inputRoles(),actual.inputRoles());
		Assert.assertEquals(expected.metadataInputs(),actual.metadataInputs());
		Assert.assertEquals(expected.complete(),actual.complete());
		Assert.assertEquals(expected.diagnostics(),actual.diagnostics());
		Assert.assertEquals(expected.generation(),actual.generation());
	}
	private static ReorgOp transpose(String name,Hop input) {
		ReorgOp result=new ReorgOp(name,DataType.MATRIX,ValueType.FP64,ReOrgOp.TRANS,input);
		result.setDim1(input.getDim2()); result.setDim2(input.getDim1()); return result;
	}
	private static ReorgOp reshape(String name,boolean byrow) {
		ReorgOp result=new ReorgOp(name,DataType.MATRIX,ValueType.FP64,ReOrgOp.RESHAPE,List.of(
			matrix(name+"X",8,4),new LiteralOp(4L),new LiteralOp(8L),new LiteralOp(-1L),
			new LiteralOp(byrow)));
		result.setDim1(4); result.setDim2(8); return result;
	}
	private static NaryOp nary(String name,OpOpN op,Hop... inputs) {
		NaryOp result=new NaryOp(name,DataType.MATRIX,ValueType.FP64,op,inputs);
		result.setDim1(8); result.setDim2(4); return result;
	}
	private static TernaryOp ternary(String name,OpOp3 op,Hop first,Hop second,Hop third) {
		TernaryOp result=new TernaryOp(name,DataType.MATRIX,ValueType.FP64,op,first,second,third);
		result.setDim1(8); result.setDim2(4); return result;
	}
	private static boolean admitted(Hop hop) {
		if(hop instanceof ReorgOp reorg) return reorg.getOp()==ReOrgOp.TRANS;
		if(hop instanceof NaryOp nary) return nary.getOp()==OpOpN.PLUS || nary.getOp()==OpOpN.MULT;
		if(hop instanceof TernaryOp ternary) return ternary.getOp()==OpOp3.IFELSE
			|| ternary.getOp()==OpOp3.PLUS_MULT || ternary.getOp()==OpOp3.MINUS_MULT;
		return false;
	}
	private static String family(Hop hop) {
		if(hop instanceof ReorgOp) return "TRANS";
		if(hop instanceof NaryOp nary) return "NARY_"+nary.getOp().name();
		if(hop instanceof TernaryOp ternary) return "TERNARY_"+ternary.getOp().name();
		throw new IllegalArgumentException(hop.getClass().getName());
	}
	private static DataOp matrix(String name,long rows,long cols) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,
			name,rows,cols,rows*cols,1000);
	}
	private static ShapeHint shape(long rows,long cols,long rowsA,long colsA) {
		return new ShapeHint(rows,cols,1000,Optional.empty(),rowsA,colsA,-1,-1);
	}
	private static DMLProgram parsed(String workload) throws Exception {
		Method method=MemoFederatedModelTest.class.getDeclaredMethod(
			"parsedRewrittenFixture",String.class,long.class,long.class);
		method.setAccessible(true); return (DMLProgram)method.invoke(null,workload,192L,8L);
	}
	private static FederatedSourceMetadata metadata(String variable) {
		try {
			Method method=MemoFederatedModelTest.class.getDeclaredMethod("sourceMetadata",
				Privacy.class,long.class,long.class,String.class); method.setAccessible(true);
			return (FederatedSourceMetadata)method.invoke(null,
				"X".equals(variable)?Privacy.PRIVATE_AGGREGATE:Privacy.PUBLIC,
				192L,"X".equals(variable)?8L:1L,variable);
		}
		catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
	}
}

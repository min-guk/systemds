/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.List;
import java.util.Optional;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.Request;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.RequestFactory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeDescriptorTest {
	@Test
	public void descriptorAndLegacyViewProduceTheSameFullRequestIdentity() {
		UnaryOp template=unary("template",8,4);
		UnaryOp contextual=unary("contextual",16,5);
		MemoShapes.Shape output=shape(16,5);
		MemoShapes.Shape input=shape(16,5);
		ShapeHint hint=hint(16,5,template.getBlocksize(),16,5);
		MemoShapes.NativeShapeDescriptor descriptor=
			new MemoShapes.NativeShapeDescriptor(output,List.of(input),template.getBlocksize(),hint);

		RequestFactory descriptorFactory=new RequestFactory();
		Request direct=descriptorFactory.prepare(template,3,descriptor);
		RequestFactory legacyFactory=new RequestFactory();
		Request legacy=legacyFactory.prepare(contextual,3,hint);

		Assert.assertEquals(legacy.key(),direct.key());
		MemoNativeRules catalog=new MemoNativeRules();
		MemoNativeRules.Result first=catalog.rules(direct.withHop(contextual));
		MemoNativeRules.Result second=catalog.rules(legacy);
		Assert.assertSame(first,second);
		Assert.assertEquals(new MemoNativeRules.CacheStats(1,1,1),catalog.cacheStats());
	}

	@Test
	public void descriptorRetainsOrderedInputGeometryAndUncacheableFallback() {
		UnaryOp template=unary("template",8,4);
		MemoShapes.NativeShapeDescriptor first=descriptor(16,5,16,5);
		MemoShapes.NativeShapeDescriptor second=descriptor(16,5,32,5);
		RequestFactory factory=new RequestFactory();
		Assert.assertNotEquals(factory.prepare(template,3,first).key(),
			factory.prepare(template,3,second).key());

		ReorgOp reshape=new ReorgOp("reshape",DataType.MATRIX,ValueType.FP64,ReOrgOp.RESHAPE,
			List.of(matrix("X",8,4),new LiteralOp(4L),new LiteralOp(8L),new LiteralOp(-1L),
				new LiteralOp(true)));
		reshape.setDim1(4); reshape.setDim2(8);
		MemoShapes.NativeShapeDescriptor reorg=new MemoShapes.NativeShapeDescriptor(
			shape(4,8),List.of(shape(8,4)),1000,hint(4,8,8,4));
		Assert.assertNull("irregular families keep the legacy per-context path",
			factory.prepare(reshape,3,reorg).key());
	}

	@Test
	public void everyCacheableFamilyRetainsItsStructuralOperatorSelectors() {
		Hop left=matrix("L",8,4),right=matrix("R",4,3);
		BinaryOp binary=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,
			matrix("A",8,4),matrix("B",8,4));
		binary.setDim1(8); binary.setDim2(4);
		AggUnaryOp aggregate=new AggUnaryOp("rowsums",DataType.MATRIX,ValueType.FP64,
			AggOp.SUM,Direction.Row,matrix("U",8,4));
		aggregate.setDim1(8); aggregate.setDim2(1);
		AggBinaryOp multiply=new AggBinaryOp("mm",DataType.MATRIX,ValueType.FP64,
			OpOp2.MULT,AggOp.SUM,left,right);
		multiply.setDim1(8); multiply.setDim2(3);
		for(Hop hop:List.of(unary("unary",8,4),binary,aggregate,multiply))
			assertSelfDescriptorParity(hop);

		Hop shared=matrix("X",8,4);
		ReorgOp transpose=new ReorgOp("tX",DataType.MATRIX,ValueType.FP64,ReOrgOp.TRANS,shared);
		transpose.setDim1(4); transpose.setDim2(8);
		AggBinaryOp tsmm=new AggBinaryOp("tsmm",DataType.MATRIX,ValueType.FP64,
			OpOp2.MULT,AggOp.SUM,transpose,shared);
		tsmm.setDim1(4); tsmm.setDim2(4);
		assertSelfDescriptorParity(tsmm);
	}

	@Test
	public void sharedDagInputsUseTheLegacyLastPositionShape() {
		Hop shared=matrix("shared",8,4);
		BinaryOp template=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,
			OpOp2.PLUS,shared,shared);
		MemoShapes.Shape first=shape(8,4),last=shape(16,4);
		List<MemoShapes.Shape> effective=MemoShapes.effectiveInputShapes(template,List.of(first,last));
		Assert.assertEquals(List.of(last,last),effective);
		Assert.assertEquals("a missing contextual shape keeps the unchanged legacy input geometry",
			List.of(shape(shared),shape(shared)),
			MemoShapes.effectiveInputShapes(template,java.util.Arrays.asList(null,null)));
		BinaryOp legacy=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,shared,shared);
		legacy.setDim1(16); legacy.setDim2(4); shared.setDim1(16); shared.setDim2(4);
		ShapeHint hint=hint(16,4,legacy.getBlocksize(),16,4);
		MemoShapes.NativeShapeDescriptor descriptor=new MemoShapes.NativeShapeDescriptor(
			shape(16,4),effective,legacy.getBlocksize(),hint);
		RequestFactory factory=new RequestFactory();
		Assert.assertEquals(factory.prepare(legacy,3,hint).key(),factory.prepare(template,3,descriptor).key());
	}

	private static void assertSelfDescriptorParity(Hop hop) {
		List<MemoShapes.Shape> inputs=hop.getInput().stream().map(MemoNativeDescriptorTest::shape).toList();
		MemoShapes.Shape output=shape(hop);
		List<MemoShapes.Shape> payloads=inputs.stream()
			.filter(value -> value.dataType()==DataType.MATRIX || value.dataType()==DataType.FRAME).limit(2).toList();
		MemoShapes.Shape first=payloads.size()>0 ? payloads.get(0) : null;
		MemoShapes.Shape second=payloads.size()>1 ? payloads.get(1) : null;
		ShapeHint hint=new ShapeHint(output.rows(),output.columns(),hop.getBlocksize(),Optional.empty(),
			first==null ? -1:first.rows(),first==null ? -1:first.columns(),
			second==null ? -1:second.rows(),second==null ? -1:second.columns());
		MemoShapes.NativeShapeDescriptor descriptor=new MemoShapes.NativeShapeDescriptor(
			output,inputs,hop.getBlocksize(),hint);
		RequestFactory factory=new RequestFactory();
		Assert.assertEquals(factory.prepare(hop,3,hint).key(),factory.prepare(hop,3,descriptor).key());
	}

	private static MemoShapes.NativeShapeDescriptor descriptor(long rows,long columns,
		long inputRows,long inputColumns) {
		return new MemoShapes.NativeShapeDescriptor(shape(rows,columns),
			List.of(shape(inputRows,inputColumns)),1000,hint(rows,columns,inputRows,inputColumns));
	}

	private static MemoShapes.Shape shape(long rows,long columns) {
		return new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,rows,columns,rows*columns,
			MemoShapes.Knowledge.EXACT);
	}

	private static MemoShapes.Shape shape(Hop hop) {
		return new MemoShapes.Shape(hop.getDataType(),hop.getValueType(),hop.getDim1(),hop.getDim2(),hop.getNnz(),
			MemoShapes.Knowledge.EXACT);
	}

	private static ShapeHint hint(long rows,long columns,long inputRows,long inputColumns) {
		return hint(rows,columns,1000,inputRows,inputColumns);
	}

	private static ShapeHint hint(long rows,long columns,int blocksize,long inputRows,long inputColumns) {
		return new ShapeHint(rows,columns,blocksize,Optional.empty(),inputRows,inputColumns,-1,-1);
	}

	private static UnaryOp unary(String name,long rows,long columns) {
		UnaryOp result=new UnaryOp("exp",DataType.MATRIX,ValueType.FP64,OpOp1.EXP,
			matrix(name,rows,columns));
		result.setDim1(rows); result.setDim2(columns);
		return result;
	}

	private static Hop matrix(String name,long rows,long columns) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,
			name,rows,columns,rows*columns,1000);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Test;

public class MemoSourceGeometryTest {
	@Test
	public void sourceAcquisitionBuildsOneOrderIndependentSharedAxisProfile() {
		DataOp row=source("row"),col=source("col");
		MemoProgramFacts first=facts(row,col), reverse=facts(col,row);
		MemoSources a=MemoSources.acquire(first,null,data -> metadata(data.getName().equals("row")
			? FType.ROW : FType.COL,new long[] {0,4,8,10}));
		MemoSources b=MemoSources.acquire(reverse,null,data -> metadata(data.getName().equals("row")
			? FType.ROW : FType.COL,new long[] {0,4,8,10}));
		assertArrayEquals(new long[] {0,4,8,10},a.pool().axisCuts(10));
		assertTrue(a.pool().encode().startsWith("POOLv2:"));
		assertTrue("source traversal order must not change the complete descriptor",
			a.pool().encode().equals(b.pool().encode()));
	}

	@Test
	public void sameLengthRowRowAndRowColConflictsAreRejected() {
		for(FType second : List.of(FType.ROW,FType.COL)) {
			DataOp a=source("a"),b=source("b");
			MemoProgramFacts facts=facts(a,b);
			assertIllegal(()->MemoSources.acquire(facts,null,data ->
				metadata(data.getName().equals("a") ? FType.ROW : second,
					data.getName().equals("a") ? new long[] {0,4,8,10} : new long[] {0,3,7,10})));
		}
	}

	@Test
	public void sourceEndpointOrderAndRangeGapsRemainStrictlyRejected() {
		DataOp source=source("X");
		MemoProgramFacts facts=facts(source);
		CanonicalWorkerPool configured=CanonicalWorkerPool.of(workers());
		assertIllegal(()->MemoSources.acquire(facts,configured,ignored -> new FederatedSourceMetadata(
			Privacy.PUBLIC,List.of(part(0,4,workers().get(1)),part(4,8,workers().get(0)),
				part(8,10,workers().get(2))))));
		assertIllegal(()->MemoSources.acquire(facts,configured,ignored -> new FederatedSourceMetadata(
			Privacy.PUBLIC,List.of(part(0,4,workers().get(0)),part(5,8,workers().get(1)),
				part(8,10,workers().get(2))))));
	}

	@Test
	public void indexingUsesBoundPoolAuthorityAndCatalogRejectsGeometryReuse() {
		CanonicalWorkerPool custom=CanonicalWorkerPool.of(workers(),Map.of(10L,new long[] {0,4,8,10}));
		MemoNativeRules catalog=new MemoNativeRules();
		catalog.bindGeometry(custom);
		Hop input=matrix("X",10,4);
		IndexingOp slice=new IndexingOp("slice",DataType.MATRIX,ValueType.FP64,input,
			new LiteralOp(2L),new LiteralOp(9L),new LiteralOp(1L),new LiteralOp(4L),false,false);
		slice.setDim1(8); slice.setDim2(4);
		MemoNativeRules.Result result=catalog.rules(slice,3,
			new ShapeHint(8,4,1000,java.util.Optional.empty(),10,4,-1,-1));
		assertFalse("actual [0,3)+[3,7)+[7,8) output is not profile/default [0,3)+[3,6)+[6,8)",
			result.rules().stream().anyMatch(rule -> rule.exec()==org.apache.sysds.common.Types.ExecType.FED
				&& rule.inputs().get(0)==MemoPartition.ROW && rule.output()==MemoPartition.ROW));
		assertTrue(result.diagnostics().stream().anyMatch(d ->
			"INDEXING_NONCANONICAL_NATIVE_OUTPUT".equals(d.code())));
		catalog.bindGeometry(CanonicalWorkerPool.parse(custom.encode()));
		assertIllegal(()->catalog.bindGeometry(
			CanonicalWorkerPool.of(workers(),Map.of(10L,new long[] {0,3,7,10}))));
	}

	private static DataOp source(String name) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.FEDERATED,name,-1,-1,-1,1000);
	}

	private static Hop matrix(String name,long rows,long columns) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.PERSISTENTREAD,
			name,rows,columns,rows*columns,1000);
	}

	private static MemoProgramFacts facts(Hop... roots) {
		StatementBlock block=new StatementBlock();
		block.setHops(new ArrayList<>(List.of(roots)));
		DMLProgram program=new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		return MemoProgramFacts.analyze(program);
	}

	private static FederatedSourceMetadata metadata(FType type,long[] cuts) {
		List<Pair<FederatedRange,FederatedData>> parts=new ArrayList<>();
		for(int i=0;i<cuts.length-1;i++) {
			long[] begin=type==FType.ROW ? new long[] {cuts[i],0} : new long[] {0,cuts[i]};
			long[] end=type==FType.ROW ? new long[] {cuts[i+1],10} : new long[] {10,cuts[i+1]};
			parts.add(Pair.of(new FederatedRange(begin,end),
				new FederatedData(DataType.MATRIX,workers().get(i),"source-"+i)));
		}
		return new FederatedSourceMetadata(Privacy.PUBLIC,parts);
	}

	private static Pair<FederatedRange,FederatedData> part(long begin,long end,InetSocketAddress worker) {
		return Pair.of(new FederatedRange(new long[] {begin,0},new long[] {end,10}),
			new FederatedData(DataType.MATRIX,worker,"source"));
	}

	private static List<InetSocketAddress> workers() {
		return List.of(InetSocketAddress.createUnresolved("worker-0",19000),
			InetSocketAddress.createUnresolved("worker-1",19001),
			InetSocketAddress.createUnresolved("worker-2",19002));
	}

	private static void assertIllegal(Runnable action) {
		try { action.run(); fail("Expected IllegalStateException"); }
		catch(IllegalStateException expected) { /* expected */ }
	}
}

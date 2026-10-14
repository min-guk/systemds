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

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.meta.InputColumnMetadata;
import org.apache.sysds.runtime.meta.InputColumnMetadataTest;
import org.apache.wink.json4j.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MemoInputColumnMetadataTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private String previous;
	@Before public void enable() {
		previous=System.getProperty(InputColumnMetadata.RELEASE_PROPERTY);
		System.setProperty(InputColumnMetadata.RELEASE_PROPERTY,"true");
	}
	@After public void restore() {
		if(previous==null) System.clearProperty(InputColumnMetadata.RELEASE_PROPERTY);
		else System.setProperty(InputColumnMetadata.RELEASE_PROPERTY,previous);
	}

	@Test(timeout=10000) public void acquiresExactSourceFactsWithoutPayloadWorkersOrHopMutation() throws Exception {
		DataOp read=source("X");
		var metadata=metadata("X",true);
		var facts=facts(read);
		long rows=read.getDim1(),cols=read.getDim2(),nnz=read.getNnz();
		MemoSources sources=MemoSources.acquire(facts,null,ignored->metadata);
		int owner=facts.compiledOwnerId(read);
		assertEquals(List.of(100L),sources.inputMetadata(owner).exactFdom().orElseThrow());
		assertEquals(Privacy.PRIVATE_AGGREGATE,sources.privacy(owner));
		assertEquals(4,sources.source(owner).rows());
		assertTrue(sources.inputMetadataDiagnostics().isEmpty());
		assertEquals(rows,read.getDim1()); assertEquals(cols,read.getDim2()); assertEquals(nnz,read.getNnz());
		assertNull(sources.inputMetadata(-1));
		assertThrows(UnsupportedOperationException.class,()->sources.inputMetadata(owner).columns().clear());
		assertThrows(UnsupportedOperationException.class,()->sources.inputMetadataDiagnostics().clear());
		// A new acquisition must not mutate an earlier request's immutable snapshot.
		for(var part:metadata.partitions()) change(part,s->s.replace("\"observedMaxCode\":100","\"observedMaxCode\":99"));
		assertEquals(List.of(99L),MemoSources.acquire(facts,null,ignored->metadata).inputMetadata(owner).exactFdom().orElseThrow());
		assertEquals(List.of(100L),sources.inputMetadata(owner).exactFdom().orElseThrow());
	}

	@Test public void authorizationIsRequiredEvenForMalformedExistingMetadata() throws Exception {
		DataOp read=source("X"); var facts=facts(read); var metadata=metadata("X",true);
		for(var part:metadata.partitions()) Files.writeString(sidecar(part),"this is deliberately invalid JSON");
		System.clearProperty(InputColumnMetadata.RELEASE_PROPERTY);
		MemoSources sources=MemoSources.acquire(facts,null,ignored->metadata);
		assertNull(sources.inputMetadata(facts.compiledOwnerId(read)));
		assertTrue(sources.inputMetadataDiagnostics().isEmpty());
		assertEquals(Privacy.PRIVATE_AGGREGATE,sources.privacy(facts.compiledOwnerId(read)));
	}

	@Test public void missingMetadataRemainsUnknownAndPartialMetadataIsRejected() throws Exception {
		DataOp read=source("X"); var facts=facts(read); var metadata=metadata("X",false);
		MemoSources absent=MemoSources.acquire(facts,null,ignored->metadata);
		assertNull(absent.inputMetadata(facts.compiledOwnerId(read)));
		assertTrue(absent.inputMetadataDiagnostics().isEmpty());
		write(metadata.partitions().get(0));
		assertRejected(facts,read,metadata,"only some shards");
	}

	@Test public void staleBindingOrBaseIsRejectedWithoutChangingSourceLegality() throws Exception {
		for(String field:List.of("host","port","path","range","base","privacy")) {
			DataOp read=source(field); var facts=facts(read); var metadata=metadata(field,true);
			var part=metadata.partitions().get(0);
			JSONObject mtd=new JSONObject(Files.readString(sidecar(part)));
			JSONObject extension=mtd.getJSONObject(InputColumnMetadata.KEY),binding=extension.getJSONObject("binding");
			switch(field) {
				case "host": binding.put("host","different.invalid"); break;
				case "port": binding.put("port",9000); break;
				case "path": binding.put("path","another-file"); break;
				case "range": binding.put("begin",List.of(1,0)); break;
				case "base": mtd.put("nnz",0); break;
				case "privacy": mtd.put("privacy","private"); extension.getJSONObject("baseMetadata").put("privacy","private"); break;
			}
			Files.writeString(sidecar(part),mtd.toString());
			assertRejected(facts,read,metadata,null);
		}
	}

	@Test public void globalFactsMustAgreeAcrossEveryShard() throws Exception {
		for(String field:List.of("datasetVersion","transformId","rowSetId")) {
			DataOp read=source(field); var facts=facts(read); var metadata=metadata(field,true);
			var part=metadata.partitions().get(1);
			JSONObject mtd=new JSONObject(Files.readString(sidecar(part)));
			mtd.getJSONObject(InputColumnMetadata.KEY).put(field,"different");
			Files.writeString(sidecar(part),mtd.toString());
			assertRejected(facts,read,metadata,"differ between shards");
		}
	}

	@Test public void equalShapeSourcesDoNotShareIdentity() throws Exception {
		DataOp x=source("X"),y=source("Y"); var facts=facts(x,y);
		var mx=metadata("X",true); var my=metadata("Y",true);
		for(var part:my.partitions()) change(part,s->s.replace("\"datasetId\":\"X\"","\"datasetId\":\"Y\"")
			.replace("\"rowSetId\":\"rows1\"","\"rowSetId\":\"other-rows\""));
		MemoSources sources=MemoSources.acquire(facts,null,data->data==x?mx:my);
		var a=sources.inputMetadata(facts.compiledOwnerId(x)); var b=sources.inputMetadata(facts.compiledOwnerId(y));
		assertEquals("X",a.datasetId()); assertEquals("Y",b.datasetId());
		assertFalse(a.sameRowOrder(b)); assertTrue(sources.inputMetadataDiagnostics().isEmpty());
	}

	@Test public void inMemoryFederatedSourceDoesNotBorrowOnDiskFacts() throws Exception {
		HashMap<String,Hop> params=new HashMap<>();
		params.put(DataExpression.FED_LOCAL_OBJECT,new DataOp("local",DataType.MATRIX,ValueType.FP64,
			OpOpData.TRANSIENTREAD,"local",4,1,4,1000));
		DataOp read=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.FEDERATED,params);
		var facts=facts(read); var metadata=metadata("X",true);
		MemoSources sources=MemoSources.acquire(facts,null,ignored->metadata);
		assertNull(sources.inputMetadata(facts.compiledOwnerId(read)));
		assertTrue(sources.inputMetadataDiagnostics().isEmpty());
	}

	@Test public void fineGrainedPrivacyDoesNotAuthorizeMetadata() throws Exception {
		DataOp read=source("X"); var facts=facts(read); var metadata=metadata("X",true);
		var part=metadata.partitions().get(0);
		JSONObject mtd=new JSONObject(Files.readString(sidecar(part)));
		JSONObject fine=new JSONObject("{\"Private\":[[[0,0],[0,0]]]}");
		mtd.put("fine_grained_privacy",fine);
		mtd.getJSONObject(InputColumnMetadata.KEY).getJSONObject("baseMetadata").put("fine_grained_privacy",fine);
		Files.writeString(sidecar(part),mtd.toString());
		assertRejected(facts,read,metadata,null);
	}

	private static void assertRejected(MemoProgramFacts facts,DataOp read,FederatedSourceMetadata metadata,String reason) {
		MemoSources sources=MemoSources.acquire(facts,null,ignored->metadata);
		int owner=facts.compiledOwnerId(read);
		assertNull(sources.inputMetadata(owner)); assertEquals(1,sources.inputMetadataDiagnostics().size());
		if(reason!=null) assertTrue(sources.inputMetadataDiagnostics().get(0).contains(reason));
		assertEquals(Privacy.PRIVATE_AGGREGATE,sources.privacy(owner));
		assertEquals(4,sources.source(owner).rows());
	}
	private FederatedSourceMetadata metadata(String prefix,boolean write) throws Exception {
		List<Pair<FederatedRange,FederatedData>> parts=new ArrayList<>();
		for(int i=0;i<2;i++) {
			String path=temporary.getRoot().toPath().resolve(prefix+" shard-"+i).toString();
			var part=Pair.of(new FederatedRange(new long[]{i*2,0},new long[]{(i+1)*2,1}),
				FederatedData.forPlannerMetadata(DataType.MATRIX,
					InetSocketAddress.createUnresolved("unreachable-"+i+".invalid",8001+i),path));
			assertFalse(Files.exists(Path.of(path))); // No payload exists, only metadata.
			if(write) write(part);
			parts.add(part);
		}
		return new FederatedSourceMetadata(Privacy.PRIVATE_AGGREGATE,parts);
	}
	private static void write(Pair<FederatedRange,FederatedData> part) throws Exception {
		var data=part.getValue(); var range=part.getKey();
		Files.writeString(sidecar(part),InputColumnMetadataTest.fixture(data.getFilepath(),data.getAddress().getHostString(),
			data.getAddress().getPort(),range.getBeginDims()[0],range.getEndDims()[0]));
	}
	private static Path sidecar(Pair<FederatedRange,FederatedData> part) { return Path.of(part.getValue().getFilepath()+".mtd"); }
	private static void change(Pair<FederatedRange,FederatedData> part,UnaryOperator<String> edit) throws Exception {
		Files.writeString(sidecar(part),edit.apply(Files.readString(sidecar(part))));
	}
	private static DataOp source(String name) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.FEDERATED,name,-1,-1,-1,1000);
	}
	private static MemoProgramFacts facts(Hop... roots) {
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(roots)));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		return MemoProgramFacts.analyze(program);
	}
}

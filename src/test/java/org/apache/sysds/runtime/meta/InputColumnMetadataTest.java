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
package org.apache.sysds.runtime.meta;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.Expression;
import org.apache.wink.json4j.JSONObject;
import org.junit.Test;

public class InputColumnMetadataTest {
	public static String fixture(String path,String host,int port,long begin,long end) throws Exception {
		JSONObject base=new JSONObject("{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+"\"rows\":"+(end-begin)+",\"cols\":1,\"nnz\":"+(end-begin)
			+",\"format\":\"binary\",\"privacy\":\"private-aggregate\"}");
		JSONObject extension=new JSONObject("{\"schema\":\"cofee-input-column-metadata-v1\","
			+"\"scope\":\"GLOBAL\",\"release\":\"PUBLIC_COLUMN_STATISTICS\",\"datasetId\":\"X\","
			+"\"datasetVersion\":\"revision1\",\"producerVersion\":\"producer1\",\"transformId\":\"encoding1\","
			+"\"rowSetId\":\"rows1\",\"rows\":4,\"cols\":1,\"columns\":[{\"id\":1,\"sourceColumn\":2,"
			+"\"kind\":\"CATEGORY_CODE\",\"encoding\":\"RECODE\",\"missing\":\"NONE\","
			+"\"contiguousCodes\":false,\"codeBase\":1,\"observedMaxCode\":100,\"codeUpperBound\":1000}]}");
		JSONObject binding=new JSONObject(); binding.put("host",host); binding.put("port",port);
		binding.put("path",path); binding.put("begin",List.of(begin,0)); binding.put("end",List.of(end,1));
		extension.put("binding",binding); extension.put("baseMetadata",new JSONObject(base));
		base.put(InputColumnMetadata.KEY,extension); return base.toString();
	}
	@Test public void nonContiguousMaxIsNotDistinctAndSnapshotsAreImmutable() throws Exception {
		MetaDataAll parsed=new MetaDataAll(fixture("X","unreachable.invalid",8001,0,4));
		var facts=parsed.getInputColumnMetadata().dataset();
		assertEquals(List.of(100L),facts.exactFdom().orElseThrow());
		assertEquals(Long.valueOf(1000),facts.columns().get(0).codeUpperBound());
		assertEquals(4,parsed.getDim1()); assertEquals(1,parsed.getDim2());
		assertThrows(UnsupportedOperationException.class,()->facts.columns().clear());
		assertThrows(UnsupportedOperationException.class,()->facts.exactFdom().orElseThrow().clear());
	}
	@Test public void boundsNeverBecomeExactMaxima() throws Exception {
		String source=fixture("X","worker",8001,0,4).replace("\"observedMaxCode\":100,","")
			.replace(",\"observedMaxCode\":100","");
		assertTrue(new MetaDataAll(source).getInputColumnMetadata().dataset().exactFdom().isEmpty());
	}
	@Test public void invalidDomainUnknownFieldsAndStaleBaseAreRejected() throws Exception {
		String source=fixture("X","worker",8001,0,4);
		for(String bad : List.of(source.replace("\"codeUpperBound\":1000","\"codeUpperBound\":99"),
			source.replace("\"observedMaxCode\":100","\"observedMaxCode\":1.5"),
			source.replace("\"observedMaxCode\":100","\"observedMaxCode\":100.0"),
			source.replace("\"observedMaxCode\":100","\"observedMaxCode\":9007199254740993.0"),
			source.replace("\"scope\":\"GLOBAL\"","\"scope\":\"LOCAL\""),
			source.replace("\"contiguousCodes\":false","\"contiguousCodes\":false,\"estimate\":20"),
			source.replace("\"release\":\"PUBLIC_COLUMN_STATISTICS\"","\"release\":\"private\"")))
			assertThrows(RuntimeException.class,()->new MetaDataAll(bad).getInputColumnMetadata());
		JSONObject changed=new JSONObject(source); changed.put("nnz",0);
		assertThrows(RuntimeException.class,()->new MetaDataAll(changed.toString()).getInputColumnMetadata());
	}
	@Test public void extensionDoesNotBecomeAReadArgument() throws Exception {
		Path file=Files.createTempFile("column-metadata-",".mtd");
		try {
			Files.writeString(file,fixture("X","worker",8001,0,4));
			HashMap<String,Expression> parameters=new MetaDataAll(Files.readString(file)).parseMetaDataFileParameters(file.toString(),false,new HashMap<>());
			assertFalse(parameters.containsKey(InputColumnMetadata.KEY));
			assertFalse(DataExpression.READ_VALID_PARAM_NAMES.contains(InputColumnMetadata.KEY));
			assertTrue(parameters.containsKey("rows"));
		}
		finally { Files.deleteIfExists(file); }
	}
	@Test public void absentRemoteAndWrongTypeHaveExplicitBehavior() throws Exception {
		assertNull(new MetaDataAll("{\"rows\":4,\"cols\":1,\"format\":\"binary\"}").getInputColumnMetadata());
		assertNull(InputColumnMetadata.readLocal("hdfs://unreachable.invalid/data"));
		assertNull(InputColumnMetadata.readLocal("/does/not/exist/input"));
		assertThrows(RuntimeException.class,()->new MetaDataAll("{\"cofee_input_metadata\":17}"));
	}
	@Test public void localSpaceAndFileUriPathsNeedNoPayload() throws Exception {
		Path directory=Files.createTempDirectory("column metadata ");
		Path payload=directory.resolve("input matrix"),sidecar=Path.of(payload+".mtd");
		try {
			Files.writeString(sidecar,fixture(payload.toString(),"worker",8001,0,4));
			assertEquals(List.of(100L),InputColumnMetadata.readLocal(payload.toString()).getInputColumnMetadata()
				.dataset().exactFdom().orElseThrow());
			assertNotNull(InputColumnMetadata.readLocal(payload.toUri().toString()));
			assertFalse(Files.exists(payload));
		}
		finally { Files.deleteIfExists(sidecar); Files.deleteIfExists(directory); }
	}
	@Test public void rowIdentityIsIndependentOfColumnShapeButRequiresRevision() throws Exception {
		var a=new MetaDataAll(fixture("X","worker",8001,0,4)).getInputColumnMetadata().dataset();
		var b=new InputColumnMetadata.Dataset("e","revision1","producer1","error-bitmap","rows1",4,1,
			List.of(new InputColumnMetadata.Column(1,1,InputColumnMetadata.Kind.BINARY,
				InputColumnMetadata.Encoding.NONE,InputColumnMetadata.Missing.NONE,false,0L,null,1L)));
		assertTrue(a.sameRowOrder(b)); assertFalse(b.exactFdom().isPresent());
		assertFalse(a.sameRowOrder(new InputColumnMetadata.Dataset("e","revision2","producer1","error-bitmap","rows1",4,1,b.columns())));
	}
}

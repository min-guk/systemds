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
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class FederatedSourceCacheIdentityTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	private static class Source extends MatrixObject {
		private int reads;
		private boolean changeDuringRead;

		Source() {
			super(ValueType.FP64, "source", new MetaDataFormat(
				new MatrixCharacteristics(2, 1, 1024, -1), FileFormat.BINARY));
			setFedMapping(map(1));
		}

		@Override
		protected MatrixBlock readBlobFromFederated(FederationMap map, long[] dims) {
			reads++;
			double value = map.getFederatedData()[0].getVarID();
			if(changeDuringRead)
				map.getFederatedData()[0].setVarID(99);
			return new MatrixBlock(2, 1, value);
		}
	}

	private static FederationMap map(long id) {
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("127.0.0.1", 19001), "source");
		data.setVarID(id);
		return new FederationMap(id, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {2, 1}), data)), FType.ROW);
	}

	@Test
	public void invariantCollectionPreservesLogicalVersionAndReadsOnce() {
		Source source = new Source();
		long version = source.getMutationVersion();
		for(int i = 0; i < 3; i++)
			assertEquals(1, source.acquireReadAndRelease().get(0, 0), 0);
		assertEquals(1, source.reads);
		assertEquals(version, source.getMutationVersion());
	}

	@Test
	public void replacementMapInvalidatesCollectedBytesEvenWhenFunctionCleanupIsDisabled() {
		Source source = new Source();
		source.enableCleanup(false);
		assertEquals(1, source.acquireReadAndRelease().get(0, 0), 0);
		source.setFedMapping(map(2));
		assertEquals(2, source.acquireReadAndRelease().get(0, 0), 0);
		assertEquals(2, source.reads);
	}

	@Test
	public void inPlaceRemoteIdentityChangeInvalidatesCollectedBytes() {
		Source source = new Source();
		assertEquals(1, source.acquireReadAndRelease().get(0, 0), 0);
		source.getFedMapping().getFederatedData()[0].setVarID(3);
		assertEquals(3, source.acquireReadAndRelease().get(0, 0), 0);
		assertEquals(2, source.reads);
	}

	@Test
	public void sourceChangeDuringCollectionIsRejected() {
		Source source = new Source();
		source.changeDuringRead = true;
		assertThrows(DMLRuntimeException.class, source::acquireReadAndRelease);
	}

	@Test
	public void exportAfterRemoteUpdateDoesNotWritePreviouslyCollectedBytes() throws Exception {
		Source source = new Source();
		source.acquireReadAndRelease();
		source.getFedMapping().getFederatedData()[0].setVarID(4);
		Path output = temporary.getRoot().toPath().resolve("updated.csv");
		source.exportData(output.toString(), "csv", 1, null);
		assertEquals(2, source.reads);
		for(String line : Files.readAllLines(output))
			assertEquals(4, Double.parseDouble(line), 0);
	}
}

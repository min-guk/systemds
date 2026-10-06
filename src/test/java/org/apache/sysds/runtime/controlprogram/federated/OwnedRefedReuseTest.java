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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.MatrixObjectFuture;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OwnedRefedReuseTest {
	private static class CountingMap extends FederationMap {
		private int _aliases;
		private int _cleanups;
		private boolean _failCopy;

		CountingMap(long id) {
			super(id, entries(), FType.ROW);
		}

		CountingMap(long id, List<Pair<FederatedRange, FederatedData>> entries) {
			super(id, entries, FType.ROW);
		}

		@Override
		public FederationMap identCopy(long tid, long id) {
			_aliases++;
			if (_failCopy)
				throw new DMLRuntimeException("expected copy failure");
			return new CountingMap(id);
		}

		@Override
		public void execCleanup(long tid, long... ids) {
			_cleanups++;
		}
	}

	private static List<Pair<FederatedRange, FederatedData>> entries() {
		return entries(10, 1);
	}

	private static List<Pair<FederatedRange, FederatedData>> entries(long rows, long cols) {
		List<Pair<FederatedRange, FederatedData>> ret = new ArrayList<>();
		ret.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {rows, cols}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("127.0.0.1", 19001), "unused")));
		return ret;
	}

	private static MatrixObject local(String file) {
		return new MatrixObject(ValueType.FP64, file,
			new MetaData(new MatrixCharacteristics(10, 1, 1024, 10)));
	}

	private static MatrixObject federated(String file, FederationMap map) {
		MatrixObject result = local(file);
		result.setFedMapping(map);
		return result;
	}

	private static FederationMap alias(MatrixObject owner, long tid, String layout, FType type,
		CountingMap canonical, AtomicInteger materializations) {
		return FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			10, 1, 10, tid, layout, type, () -> {
				materializations.incrementAndGet();
				return canonical;
			});
	}

	@Before
	public void before() {
		FederationUtils.clearOwnedRefedReuseCache();
	}

	@After
	public void after() {
		FederationUtils.clearOwnedRefedReuseCache();
	}

	@Test
	public void repeatedCallsMaterializeOnceAndPublishDistinctAliases() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(500);
		AtomicInteger materializations = new AtomicInteger();
		FederationMap first = alias(owner, 7, "layout", FType.ROW, canonical, materializations);
		FederationMap second = alias(owner, 7, "layout", FType.ROW, canonical, materializations);
		assertEquals(1, materializations.get());
		assertEquals(2, canonical._aliases);
		assertNotEquals(canonical.getID(), first.getID());
		assertNotEquals(first.getID(), second.getID());
	}

	@Test
	public void syntheticAliasCleanupLeavesCanonicalReusableAndLocalOwnerUnchanged() {
		MatrixObject owner = local("Y");
		long version = owner.getMutationVersion();
		CountingMap canonical = new CountingMap(509);
		AtomicInteger materializations = new AtomicInteger();
		FederationMap published = alias(owner, 7, "layout", FType.ROW, canonical, materializations);
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("Y", owner);
		ec.setVariable("Y_refed", federated("Y_refed", published));
		MatrixObject removed = (MatrixObject) ec.removeVariable("Y_refed");
		ec.cleanupDataObject(removed);
		alias(owner, 7, "layout", FType.ROW, canonical, materializations);
		assertEquals(1, materializations.get());
		assertEquals(0, canonical._cleanups);
		assertEquals(version, owner.getMutationVersion());
	}

	@Test
	public void workerCpvarAliasSurvivesAliasRmvarWithoutMutatingCanonicalData() {
		ExecutionContext worker = new ExecutionContext(new LocalVariableMap());
		MatrixBlock block = new MatrixBlock(2, 1, false);
		block.set(0, 0, 2);
		block.set(1, 0, 3);
		MatrixObject canonical = ExecutionContext.createMatrixObject(block);
		worker.setVariable("canonical", canonical);
		VariableCPInstruction.prepareCopyInstruction("canonical", "alias").processInstruction(worker);
		assertSame(canonical, worker.getVariable("alias"));
		VariableCPInstruction.prepareRemoveInstruction("alias").processInstruction(worker);
		MatrixBlock retained = worker.getMatrixInput("canonical");
		assertEquals(2, retained.get(0, 0), 0);
		assertEquals(3, retained.get(1, 0), 0);
		worker.releaseMatrixInput("canonical");
	}

	@Test
	public void workerCpvarAliasKeepsValueWhenCanonicalNameIsRemovedFirst() {
		ExecutionContext worker = new ExecutionContext(new LocalVariableMap());
		MatrixBlock block = new MatrixBlock(2, 1, false);
		block.set(0, 0, 4);
		block.set(1, 0, 5);
		worker.setVariable("canonical", ExecutionContext.createMatrixObject(block));
		VariableCPInstruction.prepareCopyInstruction("canonical", "published").processInstruction(worker);
		VariableCPInstruction.prepareRemoveInstruction("canonical").processInstruction(worker);
		MatrixBlock retained = worker.getMatrixInput("published");
		assertEquals(4, retained.get(0, 0), 0);
		assertEquals(5, retained.get(1, 0), 0);
		worker.releaseMatrixInput("published");
	}

	@Test
	public void exactOwnerAndAllSemanticKeyFieldsAreRequired() {
		MatrixObject owner = local("same-file");
		MatrixObject other = local("same-file");
		AtomicInteger materializations = new AtomicInteger();
		alias(owner, 1, "a", FType.ROW, new CountingMap(501), materializations);
		alias(other, 1, "a", FType.ROW, new CountingMap(502), materializations);
		assertThrows(DMLRuntimeException.class, () -> FederationUtils.getOrCreateOwnedRefedAlias(owner,
			owner.getMutationVersion() + 1, 10, 1, 10, 1, "a", FType.ROW,
			() -> { materializations.incrementAndGet(); return new CountingMap(503); }));
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			10, 1, 10, 2, "a", FType.ROW, () -> { materializations.incrementAndGet(); return new CountingMap(504); });
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			10, 1, 10, 1, "b", FType.ROW, () -> { materializations.incrementAndGet(); return new CountingMap(505); });
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			10, 1, 10, 1, "a", FType.FULL, () -> { materializations.incrementAndGet(); return new CountingMap(506); });
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			11, 1, 10, 1, "a", FType.ROW, () -> { materializations.incrementAndGet(); return new CountingMap(507); });
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(),
			10, 1, 9, 1, "a", FType.ROW, () -> { materializations.incrementAndGet(); return new CountingMap(508); });
		owner.acquireModify(new MatrixBlock(10, 1, false));
		owner.release();
		alias(owner, 1, "a", FType.ROW, new CountingMap(509), materializations);
		assertEquals(8, materializations.get());
	}

	@Test
	public void directClearDataRetiresOwnedCanonical() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(515);
		alias(owner, 3, "layout", FType.ROW, canonical, new AtomicInteger());
		owner.clearData();
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void rmfilevarDirectClearPathRetiresOwnedCanonical() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(517);
		alias(owner, 3, "layout", FType.ROW, canonical, new AtomicInteger());
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("Y", owner);
		String instruction = InstructionUtils.concatOperands("CP", "rmfilevar",
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("false", DataType.SCALAR.name(), ValueType.BOOLEAN.name(), "true"));
		InstructionParser.parseSingleInstruction(instruction).processInstruction(ec);
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void matrixObjectFutureDirectClearRetiresOwnedCanonical() {
		MatrixObjectFuture owner = new MatrixObjectFuture(ValueType.FP64, "future-Y",
			CompletableFuture.completedFuture(new MatrixBlock(10, 1, false)));
		CountingMap canonical = new CountingMap(518);
		alias(owner, 3, "layout", FType.ROW, canonical, new AtomicInteger());
		owner.clearData(3);
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void cleanupDisabledMutationStillRetiresOwnedCanonical() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(516);
		alias(owner, 3, "layout", FType.ROW, canonical, new AtomicInteger());
		owner.enableCleanup(false);
		owner.acquireModify(new MatrixBlock(10, 1, false));
		owner.release();
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void materializerCannotPublishCanonicalUnderChangedOwnerVersion() {
		MatrixObject owner = local("Y");
		long version = owner.getMutationVersion();
		CountingMap canonical = new CountingMap(519);
		assertThrows(DMLRuntimeException.class, () -> FederationUtils.getOrCreateOwnedRefedAlias(owner,
			version, 10, 1, 10, 3, "layout", FType.ROW, () -> {
				owner.acquireModify(new MatrixBlock(10, 1, false));
				owner.release();
				return canonical;
			}));
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void mutationRetiresCanonicalButPublishedAliasRemainsIndependent() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(510);
		FederationMap published = alias(owner, 3, "layout", FType.ROW, canonical, new AtomicInteger());
		owner.acquireModify(new MatrixBlock(10, 1, false));
		owner.release();
		assertEquals(1, canonical._cleanups);
		assertNotNull(published.getMap());
		assertNotEquals(canonical.getID(), published.getID());
	}

	@Test
	public void executionContextRemovalRetiresLocalOwnerDespiteCleanupEarlyAbort() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(520);
		FederationMap published = alias(owner, 4, "layout", FType.ROW, canonical, new AtomicInteger());
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("Y", owner);
		ec.setVariable("Y_refed", federated("Y_refed", published));
		ec.removeVariable("Y");
		ec.cleanupDataObject(owner);
		assertEquals(1, canonical._cleanups);
		assertEquals(published.getID(), ((MatrixObject) ec.getVariable("Y_refed")).getFedMapping().getID());
	}

	@Test
	public void copyFailureRetiresEntryAndNextCallRematerializes() {
		MatrixObject owner = local("Y");
		CountingMap failed = new CountingMap(530);
		failed._failCopy = true;
		AtomicInteger materializations = new AtomicInteger();
		assertThrows(DMLRuntimeException.class,
			() -> alias(owner, 5, "layout", FType.ROW, failed, materializations));
		CountingMap recovered = new CountingMap(531);
		FederationMap result = alias(owner, 5, "layout", FType.ROW, recovered, materializations);
		assertNotNull(result);
		assertEquals(2, materializations.get());
		assertEquals(2, failed._cleanups);
	}

	@Test
	public void resetCleansOwnedCanonicalMaps() {
		MatrixObject owner = local("Y");
		CountingMap canonical = new CountingMap(540);
		alias(owner, 6, "layout", FType.ROW, canonical, new AtomicInteger());
		FederationUtils.clearOwnedRefedReuseCache();
		assertEquals(1, canonical._cleanups);
	}

	@Test
	public void boundedCacheEvictsAndCleansLeastRecentlyUsedCanonical() {
		CountingMap eldest = new CountingMap(600);
		alias(local("owner-0"), 8, "layout-0", FType.ROW, eldest, new AtomicInteger());
		for (int i = 1; i <= 4096; i++)
			alias(local("owner-" + i), 8, "layout-" + i, FType.ROW,
				new CountingMap(600 + i), new AtomicInteger());
		assertEquals(1, eldest._cleanups);
	}

	@Test
	public void retiredCopyCannotBeReusedEvenWithUnchangedOwnerVersionAndLayout() {
		MatrixObject owner = local("retired-Y");
		long version = owner.getMutationVersion();
		AtomicInteger creations = new AtomicInteger();
		CountingMap expired = new CountingMap(4900);
		alias(owner, 9, "same-layout", FType.ROW, expired, creations);
		FederationUtils.retireOwnedRefedReuseMaps(owner);
		assertEquals(version, owner.getMutationVersion());
		CountingMap fresh = new CountingMap(4901);
		alias(owner, 9, "same-layout", FType.ROW, fresh, creations);
		alias(owner, 9, "same-layout", FType.ROW, fresh, creations);
		assertEquals(2, creations.get());
		assertEquals(1, expired._aliases);
		assertEquals(1, expired._cleanups);
		assertEquals(2, fresh._aliases);
	}

	@Test
	public void evictedCopyIsRecreatedWhenItsOriginalOwnerRequestsItAgain() {
		MatrixObject owner = local("evicted-Y");
		AtomicInteger creations = new AtomicInteger();
		CountingMap expired = new CountingMap(4910, entries(5_000_000, 1));
		alias(owner, 10, "same-layout", FType.ROW, expired, creations);
		alias(local("evictor"), 10, "other-layout", FType.ROW,
			new CountingMap(4911, entries(5_000_000, 1)), new AtomicInteger());
		assertEquals(1, expired._cleanups);
		CountingMap fresh = new CountingMap(4912, entries(5_000_000, 1));
		alias(owner, 10, "same-layout", FType.ROW, fresh, creations);
		assertEquals(2, creations.get());
		assertEquals(1, expired._aliases);
		assertEquals(1, fresh._aliases);
	}

	@Test
	public void oversizedCanonicalIsAliasedThenRetiredWithoutReuse() {
		MatrixObject owner = local("large-Y");
		CountingMap first = new CountingMap(5000, entries(10_000_000, 1));
		CountingMap second = new CountingMap(5001, entries(10_000_000, 1));
		AtomicInteger materializations = new AtomicInteger();
		alias(owner, 9, "large", FType.ROW, first, materializations);
		alias(owner, 9, "large", FType.ROW, second, materializations);
		assertEquals(2, materializations.get());
		assertEquals(1, first._aliases);
		assertEquals(1, first._cleanups);
		assertEquals(1, second._cleanups);
	}

	@Test
	public void byteBudgetEvictsAndCleansLeastRecentlyUsedCanonical() {
		MatrixObject firstOwner = local("large-Y-1");
		MatrixObject secondOwner = local("large-Y-2");
		CountingMap first = new CountingMap(5010, entries(5_000_000, 1));
		CountingMap second = new CountingMap(5011, entries(5_000_000, 1));
		alias(firstOwner, 10, "large-1", FType.ROW, first, new AtomicInteger());
		assertEquals(0, first._cleanups);
		alias(secondOwner, 10, "large-2", FType.ROW, second, new AtomicInteger());
		assertEquals(1, first._cleanups);
		assertEquals(0, second._cleanups);
	}

	@Test
	public void overflowExtentIsNeverRetainedOrCountedAsSmall() {
		MatrixObject owner = local("overflow-Y");
		CountingMap first = new CountingMap(5020, entries(Long.MAX_VALUE, 2));
		CountingMap second = new CountingMap(5021, entries(Long.MAX_VALUE, 2));
		AtomicInteger materializations = new AtomicInteger();
		alias(owner, 11, "overflow", FType.ROW, first, materializations);
		alias(owner, 11, "overflow", FType.ROW, second, materializations);
		assertEquals(2, materializations.get());
		assertEquals(1, first._cleanups);
		assertEquals(1, second._cleanups);
	}

	@Test
	public void invalidExtentDoesNotPoisonByteCountForFollowingValidEntry() {
		MatrixObject owner = local("invalid-Y");
		CountingMap invalid = new CountingMap(5030, entries(0, 1));
		CountingMap valid = new CountingMap(5031);
		AtomicInteger materializations = new AtomicInteger();
		alias(owner, 12, "invalid", FType.ROW, invalid, materializations);
		alias(owner, 12, "valid", FType.ROW, valid, materializations);
		alias(owner, 12, "valid", FType.ROW, valid, materializations);
		assertEquals(2, materializations.get());
		assertEquals(1, invalid._cleanups);
		assertEquals(0, valid._cleanups);
	}
}

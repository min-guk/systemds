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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.compression.FastLzFrameDecoder;
import io.netty.handler.codec.compression.FastLzFrameEncoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.JdkZlibEncoder;
import io.netty.handler.codec.compression.Lz4FrameDecoder;
import io.netty.handler.codec.compression.Lz4FrameEncoder;
import io.netty.handler.codec.compression.LzfDecoder;
import io.netty.handler.codec.compression.LzfEncoder;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.FTypes.FPartitioning;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.parfor.util.IDSequence;
import org.apache.sysds.runtime.functionobjects.Builtin;
import org.apache.sysds.runtime.functionobjects.Builtin.BuiltinCode;
import org.apache.sysds.runtime.functionobjects.CM;
import org.apache.sysds.runtime.functionobjects.KahanFunction;
import org.apache.sysds.runtime.functionobjects.Mean;
import org.apache.sysds.runtime.functionobjects.Multiply;
import org.apache.sysds.runtime.functionobjects.Plus;
import org.apache.sysds.runtime.functionobjects.ReduceAll;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.matrix.data.LibMatrixAgg;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.AggregateOperator;
import org.apache.sysds.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysds.runtime.matrix.operators.BinaryOperator;
import org.apache.sysds.runtime.matrix.operators.ScalarOperator;
import org.apache.sysds.runtime.matrix.operators.SimpleOperator;

import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;

@SuppressWarnings("deprecation")
public class FederationUtils {
	protected static Logger log = Logger.getLogger(FederationUtils.class);
	private static final IDSequence _idSeq = new IDSequence();
	private static final java.util.concurrent.ConcurrentHashMap<String, FederationMap> _anchorMaps =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.ConcurrentHashMap<String, String> _anchorKeys =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final int REFED_REUSE_CACHE_LIMIT = Math.max(1, Integer.getInteger(
		"sysds.fed.refed.reuse.cache.limit", 4096));
	private static final long OWNED_REFED_REUSE_CACHE_BYTES = Math.max(0L, Long.getLong(
		"sysds.fed.refed.reuse.cache.bytes", 64L * 1024 * 1024));
	private static final Map<RefedReuseKey, FederationMap> _refedReuseCache = Collections.synchronizedMap(
		new LinkedHashMap<RefedReuseKey, FederationMap>(256, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<RefedReuseKey, FederationMap> eldest) {
				return size() > REFED_REUSE_CACHE_LIMIT;
			}
		});
	/**
	 * Canonical worker-side copies owned by the exact local MatrixObject that was materialized.
	 * This cache is deliberately separate from the legacy non-owning cache above: callers only
	 * receive fresh worker-side aliases, never the privately owned canonical IDs.
	 */
	private static final LinkedHashMap<OwnedRefedReuseKey, OwnedRefedReuseEntry> _ownedRefedReuseCache =
		new LinkedHashMap<>(256, 0.75f, true);
	private static long _ownedRefedReuseCacheBytes = 0;

	public static void resetFedDataID() {
		clearOwnedRefedReuseCache();
		_idSeq.reset();
		clearRefedReuseCache();
	}

	/**
	 * Return a fresh worker-side alias of a canonical REFED materialization. On a miss, the
	 * materializer is invoked exactly once while holding the cache lock, and its returned map is
	 * retained privately. Both hits and misses publish a fresh ID via worker cpvar, so ordinary
	 * cleanup of the published output cannot delete the cached canonical value.
	 */
	public static FederationMap getOrCreateOwnedRefedAlias(MatrixObject owner, long inputMutationVersion,
		long rows, long cols, long nnz, long tid, String layoutSig, FType outType,
		Supplier<FederationMap> materializer) {
		return getOrCreateOwnedRefedAlias(owner, inputMutationVersion, rows, cols, nnz, tid,
			layoutSig, outType, null, materializer);
	}

	/**
	 * Execute a selected supply. An empty group publishes the new map directly and its
	 * consumers own the ordinary output lifetime. A nonempty group was derived by the
	 * planner from repeated demands for this source version; retain its private copy
	 * until source invalidation/removal, independently of the opportunistic LRU cache.
	 */
	public static FederationMap materializePlannedRefed(MatrixObject owner, long inputMutationVersion,
		long rows, long cols, long nnz, long tid, String layoutSig, FType outType,
		String sharingGroup, Supplier<FederationMap> materializer) {
		if(owner == null || materializer == null || sharingGroup == null)
			throw new DMLRuntimeException("Planned REFED requires explicit source and sharing authority");
		if(!sharingGroup.isEmpty())
			return getOrCreateOwnedRefedAlias(owner, inputMutationVersion, rows, cols, nnz, tid,
				layoutSig, outType, sharingGroup, materializer);
		RefedReuseAudit.Context audit = RefedReuseAudit.isEnabled()
			? RefedReuseAudit.context("SINGLE_USE", owner.getUniqueID(), inputMutationVersion, sharingGroup,
				normalizeRefedReuseLayoutSig(layoutSig), String.valueOf(outType)) : null;
		synchronized(owner) {
			if(owner.getMutationVersion() != inputMutationVersion)
				throw new DMLRuntimeException("Planned REFED used a stale source version");
			FederatedValueIdentity source = federatedValueIdentity(owner.getFedMapping());
			FederationMap result = null;
			RefedReuseAudit.creationAttempt(audit);
			try {
				result = materializer.get();
				if(result == null || result.getMap() == null || result.getMap().isEmpty())
					throw new DMLRuntimeException("Planned REFED materializer returned an empty federation map");
				if(owner.getMutationVersion() != inputMutationVersion
					|| !Objects.equals(source, federatedValueIdentity(owner.getFedMapping()))) {
					cleanupFailedRefedCreation(audit, result, tid);
					throw new DMLRuntimeException("Planned REFED materializer changed the source version");
				}
				if(audit != null)
					RefedReuseAudit.creationSuccess(audit, result.getID(), estimateOwnedRefedBytes(result));
				return result;
			}
			catch(RuntimeException ex) {
				RefedReuseAudit.creationFailure(audit, result == null ? -1 : result.getID(),
					ex.getClass().getSimpleName());
				throw ex;
			}
		}
	}

	private static FederationMap getOrCreateOwnedRefedAlias(MatrixObject owner, long inputMutationVersion,
		long rows, long cols, long nnz, long tid, String layoutSig, FType outType,
		String sharingGroup, Supplier<FederationMap> materializer) {
		if (owner == null || materializer == null)
			throw new DMLRuntimeException("Owned REFED reuse requires an owner and materializer");
		// Mutation paths lock the MatrixObject before retiring from the global cache. Keep
		// the same owner -> cache lock order while materializing (which acquires owner data).
		synchronized (owner) {
			if (owner.getMutationVersion() != inputMutationVersion)
				throw new DMLRuntimeException("Owned REFED request used stale local input version "
					+ inputMutationVersion + "; current version is " + owner.getMutationVersion());
			FederatedValueIdentity source = federatedValueIdentity(owner.getFedMapping());
			// A FED read can resolve previously unknown nnz without changing its value. Source
			// identity/version and dimensions already distinguish its semantic definitions.
			OwnedRefedReuseKey key = new OwnedRefedReuseKey(owner, inputMutationVersion, source,
				rows, cols, source == null ? nnz : -1, tid, normalizeRefedReuseLayoutSig(layoutSig),
				outType, sharingGroup);
			RefedReuseAudit.Context audit = RefedReuseAudit.isEnabled()
				? RefedReuseAudit.context(sharingGroup == null ? "LEGACY" : "PLANNED", owner.getUniqueID(),
					inputMutationVersion, sharingGroup, key._layoutSig, String.valueOf(outType)) : null;
			synchronized (_ownedRefedReuseCache) {
				OwnedRefedReuseEntry entry = _ownedRefedReuseCache.get(key);
				boolean retained = entry != null;
				if(entry != null)
					RefedReuseAudit.hit(entry._audit, entry._canonical.getID(), entry._estimatedBytes);
				if (entry == null) {
					FederationMap canonical = null;
					RefedReuseAudit.creationAttempt(audit);
					try {
						canonical = materializer.get();
						if (canonical == null || canonical.getMap() == null || canonical.getMap().isEmpty())
							throw new DMLRuntimeException("Owned REFED materializer returned an empty federation map");
						if (owner.getMutationVersion() != inputMutationVersion
							|| !Objects.equals(source, federatedValueIdentity(owner.getFedMapping()))) {
							cleanupFailedRefedCreation(audit, canonical, tid);
							throw new DMLRuntimeException("Owned REFED materializer changed the source value/version");
						}
					}
					catch(RuntimeException ex) {
						RefedReuseAudit.creationFailure(audit, canonical == null ? -1 : canonical.getID(),
							ex.getClass().getSimpleName());
						throw ex;
					}
					long estimatedBytes = estimateOwnedRefedBytes(canonical);
					RefedReuseAudit.creationSuccess(audit, canonical.getID(), estimatedBytes);
					entry = new OwnedRefedReuseEntry(canonical, tid, estimatedBytes,
						sharingGroup != null, audit);
					retained = entry._planned || entry._estimatedBytes <= OWNED_REFED_REUSE_CACHE_BYTES;
					if (retained) {
						_ownedRefedReuseCache.put(key, entry);
						_ownedRefedReuseCacheBytes = saturatedAdd(_ownedRefedReuseCacheBytes,
							entry._estimatedBytes);
						RefedReuseAudit.retained(audit, canonical.getID(), estimatedBytes);
					}
				}
				long aliasID = getNextFedDataID();
				try {
					FederationMap alias = entry._canonical.identCopy(tid, aliasID);
					if(owner.getMutationVersion() != inputMutationVersion
						|| !Objects.equals(source, federatedValueIdentity(owner.getFedMapping())))
						throw new DMLRuntimeException("Owned REFED source changed while publishing an alias");
					RefedReuseAudit.alias(entry._audit, entry._canonical.getID());
					if (retained)
						evictOwnedRefedEntries();
					else
						cleanupOwnedRefedEntry(entry, "CACHE_NOT_RETAINED");
					return alias;
				}
				catch(RuntimeException ex) {
					RefedReuseAudit.aliasFailure(entry._audit, entry._canonical.getID());
					if (retained) {
						OwnedRefedReuseEntry removed = removeOwnedRefedEntry(key);
						if(removed != null)
							RefedReuseAudit.retirement(removed._audit, removed._canonical.getID(),
								removed._estimatedBytes, RefedReuseAudit.ALIAS_FAILURE);
					}
					try {
						entry._canonical.execCleanup(tid, aliasID);
					}
					catch(RuntimeException cleanupEx) {
						ex.addSuppressed(cleanupEx);
					}
					try {
						cleanupOwnedRefedEntry(entry, RefedReuseAudit.ALIAS_FAILURE);
					}
					catch(RuntimeException cleanupEx) {
						ex.addSuppressed(cleanupEx);
					}
					throw ex;
				}
			}
		}
	}

	/** Retire all canonical REFED materializations owned by this exact local object. */
	public static void retireOwnedRefedReuseMaps(MatrixObject owner) {
		retireOwnedRefedReuseMaps(owner, RefedReuseAudit.SOURCE_REMOVAL);
	}

	/** Retire all canonical REFED materializations owned by this object for the stated lifecycle reason. */
	public static void retireOwnedRefedReuseMaps(MatrixObject owner, String reason) {
		if (owner == null)
			return;
		synchronized (_ownedRefedReuseCache) {
			RuntimeException failure = null;
			java.util.Iterator<Map.Entry<OwnedRefedReuseKey, OwnedRefedReuseEntry>> iter =
				_ownedRefedReuseCache.entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<OwnedRefedReuseKey, OwnedRefedReuseEntry> cached = iter.next();
				if (cached.getKey()._owner == owner) {
					OwnedRefedReuseEntry entry = cached.getValue();
					iter.remove();
					_ownedRefedReuseCacheBytes = saturatedSubtract(_ownedRefedReuseCacheBytes,
						entry._estimatedBytes);
					RefedReuseAudit.retirement(entry._audit, entry._canonical.getID(), entry._estimatedBytes, reason);
					try {
						cleanupOwnedRefedEntry(entry, reason);
					}
					catch(RuntimeException ex) {
						if (failure == null)
							failure = ex;
						else
							failure.addSuppressed(ex);
					}
				}
			}
			if (failure != null)
				throw failure;
		}
	}

	/** Retire all privately owned canonical REFED materializations. */
	public static void clearOwnedRefedReuseCache() {
		synchronized (_ownedRefedReuseCache) {
			RuntimeException failure = null;
			for (OwnedRefedReuseEntry entry : _ownedRefedReuseCache.values()) {
				RefedReuseAudit.retirement(entry._audit, entry._canonical.getID(), entry._estimatedBytes,
					RefedReuseAudit.EXPLICIT_CLEAR);
				try {
					cleanupOwnedRefedEntry(entry, RefedReuseAudit.EXPLICIT_CLEAR);
				}
				catch(RuntimeException ex) {
					if (failure == null)
						failure = ex;
					else
						failure.addSuppressed(ex);
				}
			}
			_ownedRefedReuseCache.clear();
			_ownedRefedReuseCacheBytes = 0;
			if (failure != null)
				throw failure;
		}
	}

	/** Forget ownership after a worker-wide CLEAR, which is itself the remote cleanup. */
	static void discardOwnedRefedReuseCache() {
		discardOwnedRefedReuseCache(null);
	}

	/** Forget ownership and record whether the worker-wide CLEAR completed successfully. */
	static void discardOwnedRefedReuseCache(Boolean remoteCleanupSuccess) {
		synchronized (_ownedRefedReuseCache) {
			for(OwnedRefedReuseEntry entry : _ownedRefedReuseCache.values()) {
				RefedReuseAudit.retirement(entry._audit, entry._canonical.getID(), entry._estimatedBytes,
					RefedReuseAudit.WORKER_RESET);
				RefedReuseAudit.cleanup(entry._audit, entry._canonical.getID(), entry._estimatedBytes,
					RefedReuseAudit.WORKER_RESET, remoteCleanupSuccess);
			}
			_ownedRefedReuseCache.clear();
			_ownedRefedReuseCacheBytes = 0;
		}
	}

	private static void evictOwnedRefedEntries() {
		var entries = _ownedRefedReuseCache.entrySet().iterator();
		while (entries.hasNext() && (_ownedRefedReuseCache.size() > REFED_REUSE_CACHE_LIMIT
			|| _ownedRefedReuseCacheBytes > OWNED_REFED_REUSE_CACHE_BYTES)) {
			Map.Entry<OwnedRefedReuseKey, OwnedRefedReuseEntry> eldest = entries.next();
			if(eldest.getValue()._planned)
				continue;
			entries.remove();
			_ownedRefedReuseCacheBytes = saturatedSubtract(_ownedRefedReuseCacheBytes,
				eldest.getValue()._estimatedBytes);
			OwnedRefedReuseEntry entry = eldest.getValue();
			RefedReuseAudit.retirement(entry._audit, entry._canonical.getID(), entry._estimatedBytes,
				RefedReuseAudit.LEGACY_EVICTION);
			cleanupOwnedRefedEntry(entry, RefedReuseAudit.LEGACY_EVICTION);
		}
	}

	private static OwnedRefedReuseEntry removeOwnedRefedEntry(OwnedRefedReuseKey key) {
		OwnedRefedReuseEntry removed = _ownedRefedReuseCache.remove(key);
		if (removed != null)
			_ownedRefedReuseCacheBytes = saturatedSubtract(_ownedRefedReuseCacheBytes,
				removed._estimatedBytes);
		return removed;
	}

	private static long estimateOwnedRefedBytes(FederationMap map) {
		long total = 0;
		if (map == null || map.getMap() == null || map.getMap().isEmpty())
			return Long.MAX_VALUE;
		for (Pair<FederatedRange, FederatedData> part : map.getMap()) {
			if (part == null || part.getKey() == null)
				return Long.MAX_VALUE;
			long[] begin = part.getKey().getBeginDims();
			long[] end = part.getKey().getEndDims();
			if (begin == null || end == null || begin.length < 2 || end.length < 2)
				return Long.MAX_VALUE;
			long rows = end[0] - begin[0];
			long cols = end[1] - begin[1];
			if (rows <= 0 || cols <= 0)
				return Long.MAX_VALUE;
			long cells = saturatedMultiply(rows, cols);
			if (cells == Long.MAX_VALUE)
				return Long.MAX_VALUE;
			total = saturatedAdd(total, MatrixBlock.estimateSizeDenseInMemory(rows, cols));
		}
		return total;
	}

	private static long saturatedMultiply(long left, long right) {
		if (left < 0 || right < 0 || (left != 0 && right > Long.MAX_VALUE / left))
			return Long.MAX_VALUE;
		return left * right;
	}

	private static long saturatedAdd(long left, long right) {
		if (left < 0 || right < 0 || right > Long.MAX_VALUE - left)
			return Long.MAX_VALUE;
		return left + right;
	}

	private static long saturatedSubtract(long left, long right) {
		if (right == Long.MAX_VALUE || right >= left)
			return 0;
		return left - right;
	}

	private static void cleanupOwnedRefedEntry(OwnedRefedReuseEntry entry, String reason) {
		if (entry == null || entry._canonical == null)
			return;
		try {
			entry._canonical.execCleanup(entry._tid, entry._canonical.getID());
			RefedReuseAudit.cleanup(entry._audit, entry._canonical.getID(), entry._estimatedBytes, reason, true);
		}
		catch(RuntimeException ex) {
			RefedReuseAudit.cleanup(entry._audit, entry._canonical.getID(), entry._estimatedBytes, reason, false);
			throw ex;
		}
	}

	private static void cleanupFailedRefedCreation(RefedReuseAudit.Context audit, FederationMap map, long tid) {
		long estimatedBytes = audit == null ? 0 : estimateOwnedRefedBytes(map);
		try {
			map.execCleanup(tid, map.getID());
			RefedReuseAudit.cleanup(audit, map.getID(), estimatedBytes,
				RefedReuseAudit.CREATION_FAILURE, true);
		}
		catch(RuntimeException ex) {
			RefedReuseAudit.cleanup(audit, map.getID(), estimatedBytes,
				RefedReuseAudit.CREATION_FAILURE, false);
			throw ex;
		}
	}

	/**
	 * Remove refed-reuse cache entries that refer to the given federated data ID.
	 * <p>
	 * This is required when worker-side federated variables are explicitly cleaned up (rmvar),
	 * because otherwise stale cached federation maps could be reused later and reference deleted
	 * variables on the workers.
	 */
	public static void purgeRefedReuseCacheByFedDataID(long fedDataID) {
		if (fedDataID <= 0)
			return;
		synchronized (_refedReuseCache) {
			_refedReuseCache.entrySet().removeIf(e -> e.getValue() != null && e.getValue().getID() == fedDataID);
		}
	}

	public static long getNextFedDataID() {
		return _idSeq.getNextID();
	}

	public static void registerAnchorMap(String varName, FederationMap map) {
		if (varName == null || varName.isEmpty() || map == null)
			return;
		_anchorMaps.put(varName, map);
	}

	public static FederationMap getAnchorMap(String varName) {
		return (varName == null) ? null : _anchorMaps.get(varName);
	}

	public static void registerAnchorKey(String varName, String anchorKey) {
		if (varName == null || varName.isEmpty() || anchorKey == null || anchorKey.isEmpty())
			return;
		_anchorKeys.put(varName, anchorKey);
	}

	public static String getAnchorKey(String varName) {
		return (varName == null) ? null : _anchorKeys.get(varName);
	}

	public static void removeAnchorMap(String varName) {
		if (varName == null || varName.isEmpty())
			return;
		_anchorMaps.remove(varName);
	}

	public static void removeAnchorKey(String varName) {
		if (varName == null || varName.isEmpty())
			return;
		_anchorKeys.remove(varName);
	}

	public static FederationMap getRefedReuseMap(long inputUniqueId, long inputMutationVersion,
		long rows, long cols, long nnz, String layoutSig, FType outType) {
		return getRefedReuseMap(null, inputUniqueId, inputMutationVersion,
			rows, cols, nnz, layoutSig, outType);
	}

	public static FederationMap getRefedReuseMap(String inputKey, long inputUniqueId, long inputMutationVersion,
		long rows, long cols, long nnz, String layoutSig, FType outType) {
		RefedReuseKey key = new RefedReuseKey(normalizeRefedReuseInputSig(inputKey, inputUniqueId),
			inputMutationVersion, rows, cols, nnz, normalizeRefedReuseLayoutSig(layoutSig), outType);
		FederationMap map = _refedReuseCache.get(key);
		return (map != null) ? map.copyWithNewID(map.getID()) : null;
	}

	public static void putRefedReuseMap(long inputUniqueId, long inputMutationVersion,
		long rows, long cols, long nnz, String layoutSig, FType outType, FederationMap map) {
		putRefedReuseMap(null, inputUniqueId, inputMutationVersion,
			rows, cols, nnz, layoutSig, outType, map);
	}

	public static void putRefedReuseMap(String inputKey, long inputUniqueId, long inputMutationVersion,
		long rows, long cols, long nnz, String layoutSig, FType outType, FederationMap map) {
		if (map == null || map.getMap() == null || map.getMap().isEmpty())
			return;
		RefedReuseKey key = new RefedReuseKey(normalizeRefedReuseInputSig(inputKey, inputUniqueId),
			inputMutationVersion, rows, cols, nnz, normalizeRefedReuseLayoutSig(layoutSig), outType);
		_refedReuseCache.put(key, map.copyWithNewID(map.getID()));
	}

	public static void clearRefedReuseCache() {
		_refedReuseCache.clear();
	}

	private static String normalizeRefedReuseInputSig(String inputKey, long inputUniqueId) {
		if (inputKey != null) {
			String trimmed = inputKey.trim();
			if (!trimmed.isEmpty())
				return trimmed;
		}
		return "uid:" + inputUniqueId;
	}

	private static String normalizeRefedReuseLayoutSig(String layoutSig) {
		if (layoutSig == null)
			return "layout:null";
		String trimmed = layoutSig.trim();
		return trimmed.isEmpty() ? "layout:null" : trimmed;
	}

	/**
	 * Identity of the remote value behind a local collection. Unlike a placement signature,
	 * this includes the remote variable IDs and the exact map owner. It is captured by value
	 * because FederationMap, its entries and their ranges remain mutable runtime structures.
	 */
	public record FederatedValueIdentity(FederationMap mapping, String signature) { }

	public static FederatedValueIdentity federatedValueIdentity(FederationMap map) {
		if(map == null)
			return null;
		String layout = deriveFedLayoutSignature(map);
		if(layout == null)
			throw new DMLRuntimeException("Cannot identify an incomplete federated source map");
		StringBuilder signature = new StringBuilder().append(map.getID()).append('|').append(layout);
		for(FederatedData data : map.getFederatedData())
			signature.append('|').append(data.getVarID());
		return new FederatedValueIdentity(map, signature.toString());
	}

	public static String deriveFedLayoutSignature(FederationMap fmap) {
		if (fmap == null)
			return null;
		List<Pair<FederatedRange, FederatedData>> entries = fmap.getMap();
		if (entries == null || entries.isEmpty() || fmap.getType() == null)
			return null;

		StringBuilder sb = new StringBuilder();
		for (Pair<FederatedRange, FederatedData> entry : entries) {
			FederatedData data = entry.getValue();
			if (data == null || data.getAddress() == null)
				return null;
			String worker = canonicalFederatedWorkerAddress(data.getAddress());
			if (worker == null)
				return null;
			sb.append(worker).append(';');
		}
		sb.append('|');

		FType fType = fmap.getType();
		for (Pair<FederatedRange, FederatedData> entry : entries) {
			FederatedRange range = entry.getKey();
			if (range == null)
				return null;
			long[] beg = range.getBeginDims();
			long[] end = range.getEndDims();
			if (beg == null || end == null || beg.length < 2 || end.length < 2)
				return null;
			sb.append(beg[0]).append(',').append(beg[1]).append(',')
				.append(end[0]).append(',').append(end[1]).append(';');
		}

		sb.append('|').append(fType.name());
		return sb.toString();
	}

	public static String deriveMaterializedLayoutSignature(FederationMap workerPoolMap, FType mapType,
		long rlen, long clen) {
		if (workerPoolMap == null || workerPoolMap.getMap() == null || workerPoolMap.getMap().isEmpty() || mapType == null)
			return null;
		List<Pair<FederatedRange, FederatedData>> entries = workerPoolMap.getMap();
		int numWorkers = entries.size();
		if (numWorkers <= 0)
			return null;

		StringBuilder sb = new StringBuilder();
		for (Pair<FederatedRange, FederatedData> entry : entries) {
			FederatedData data = entry.getValue();
			if (data == null || data.getAddress() == null)
				return null;
			String worker = canonicalFederatedWorkerAddress(data.getAddress());
			if (worker == null)
				return null;
			sb.append(worker).append(';');
		}
		sb.append('|');

		if (mapType == FType.ROW) {
			long base = rlen / numWorkers;
			long rem = rlen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				sb.append(pos).append(',').append(0).append(',')
					.append(pos + size).append(',').append(clen).append(';');
				pos += size;
			}
		}
		else if (mapType == FType.COL) {
			long base = clen / numWorkers;
			long rem = clen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				sb.append(0).append(',').append(pos).append(',')
					.append(rlen).append(',').append(pos + size).append(';');
				pos += size;
			}
		}
		else {
			for (int i = 0; i < numWorkers; i++)
				sb.append(0).append(',').append(0).append(',')
					.append(rlen).append(',').append(clen).append(';');
		}

		sb.append('|').append(mapType.name());
		return sb.toString();
	}

	public static FederationMap buildAnchorMapFromKey(String anchorKey) {
		if (anchorKey == null || anchorKey.isEmpty())
			return null;
		String sig = anchorKey;
		FType fType = null;
		int lastSep = anchorKey.lastIndexOf('|');
		if (lastSep > 0 && lastSep < anchorKey.length() - 1) {
			String tail = anchorKey.substring(lastSep + 1);
			try {
				fType = FType.valueOf(tail);
				sig = anchorKey.substring(0, lastSep);
			}
			catch (IllegalArgumentException ex) {
				// no ftype suffix
			}
		}
		if (fType == null)
			return null;
		int sep = sig.indexOf('|');
		String addrPart = (sep >= 0) ? sig.substring(0, sep) : sig;
		String rangePart = (sep >= 0 && sep < sig.length() - 1) ? sig.substring(sep + 1) : "";
		if (addrPart == null || addrPart.isEmpty())
			return null;

		String[] addrTokens = addrPart.split(";");
		List<String> rangeTokens = new ArrayList<>();
		if (rangePart != null && !rangePart.isEmpty()) {
			for (String token : rangePart.split(";")) {
				if (token != null && !token.isEmpty())
					rangeTokens.add(token);
			}
		}
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		int workerIx = 0;
		for (String token : addrTokens) {
			if (token == null || token.isEmpty())
				continue;
			InetSocketAddress isa = parseAddress(token);
			if (isa == null)
				return null;
			// Anchor maps are consumed by runtime instructions and must use the same
			// resolved endpoint representation as native fedinit maps. Keep parseAddress
			// lexical for DNS-free planner identity, and resolve only at this boundary.
			isa = new InetSocketAddress(isa.getHostString(), isa.getPort());
			FederatedData data = new FederatedData(Types.DataType.MATRIX, isa, null);
			FederatedRange range = parseRangeToken(rangeTokens, workerIx, fType);
			if (range == null)
				range = new FederatedRange(new long[] {0, 0}, new long[] {0, 0});
			entries.add(new ImmutablePair<>(range, data));
			workerIx++;
		}
		if (entries.isEmpty())
			return null;
		return new FederationMap(getNextFedDataID(), entries, fType);
	}

	private static FederatedRange parseRangeToken(List<String> rangeTokens, int workerIx, FType fType) {
		if (rangeTokens == null || workerIx < 0 || workerIx >= rangeTokens.size())
			return null;
		String token = rangeTokens.get(workerIx);
		if (token == null || token.isEmpty())
			return null;
		String[] dims = token.split(",");
		try {
			if ((fType == FType.ROW || fType == FType.COL) && dims.length == 4) {
				long rb = Long.parseLong(dims[0].trim());
				long cb = Long.parseLong(dims[1].trim());
				long re = Long.parseLong(dims[2].trim());
				long ce = Long.parseLong(dims[3].trim());
				return new FederatedRange(new long[] {rb, cb}, new long[] {re, ce});
			}
			if (fType == FType.ROW && dims.length == 2) {
				long rb = Long.parseLong(dims[0].trim());
				long re = Long.parseLong(dims[1].trim());
				return new FederatedRange(new long[] {rb, 0}, new long[] {re, 0});
			}
			if (fType == FType.COL && dims.length == 2) {
				long cb = Long.parseLong(dims[0].trim());
				long ce = Long.parseLong(dims[1].trim());
				return new FederatedRange(new long[] {0, cb}, new long[] {0, ce});
			}
			if ((fType == FType.FULL || fType == FType.BROADCAST) && dims.length == 4) {
				long rb = Long.parseLong(dims[0].trim());
				long cb = Long.parseLong(dims[1].trim());
				long re = Long.parseLong(dims[2].trim());
				long ce = Long.parseLong(dims[3].trim());
				return new FederatedRange(new long[] {rb, cb}, new long[] {re, ce});
			}
		}
		catch (NumberFormatException ex) {
			return null;
		}
		return null;
	}

	public static String canonicalFederatedWorkerAddress(String token) {
		return canonicalFederatedWorkerAddress(parseAddress(token));
	}

	public static String canonicalFederatedWorkerAddress(InetSocketAddress address) {
		if (address == null || address.getHostString() == null || address.getHostString().isBlank()
			|| address.getPort() < 0)
			return null;
		return address.getHostString() + ':' + address.getPort();
	}

	private static InetSocketAddress parseAddress(String token) {
		if (token == null)
			return null;
		String addr = token.trim();
		if (addr.isEmpty())
			return null;
		int slash = addr.indexOf('/');
		if (slash >= 0) {
			String before = addr.substring(0, slash);
			String after = slash < addr.length() - 1 ? addr.substring(slash + 1) : "";
			Integer renderedPort = socketPort(after);
			if (before.contains(":") && !before.isEmpty()) {
				// fedinit signatures embed host:port/path
				addr = before;
			}
			else if (renderedPort != null) {
				// InetSocketAddress#toString -> host/addr:port. Preserve the original
				// host when present so DNS resolution does not change durable identity.
				addr = (before.isEmpty() ? after : before + ':' + renderedPort);
			}
			else if (!before.isEmpty()) {
				// fedinit address without an explicit port: host/path
				addr = before;
			}
		}
		int colon = addr.lastIndexOf(':');
		String host = colon > 0 ? addr.substring(0, colon) : addr;
		String portStr = colon > 0 && colon < addr.length() - 1
			? addr.substring(colon + 1) : Integer.toString(DMLConfig.DEFAULT_FEDERATED_PORT);
		if (host.isBlank())
			return null;
		int port;
		try {
			port = Integer.parseInt(portStr);
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (port < 0 || port > 65535)
			return null;
		// Canonical placement identity is lexical: resolving a worker hostname can
		// change neither the durable host:port token nor its authority.  In addition
		// to making planning depend on external DNS, the resolving constructor creates
		// an UnknownHostException for every comparison of container-only names such as
		// worker1 outside Docker. Candidate search performs these comparisons very
		// frequently, so retain the parsed address without network resolution.
		return InetSocketAddress.createUnresolved(host, port);
	}

	private static Integer socketPort(String renderedAddress) {
		if (renderedAddress == null || renderedAddress.isEmpty())
			return null;
		int colon = renderedAddress.lastIndexOf(':');
		if (colon < 0 || colon >= renderedAddress.length() - 1)
			return null;
		try {
			int port = Integer.parseInt(renderedAddress.substring(colon + 1));
			return port >= 0 && port <= 65535 ? port : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	public static void checkFedMapType(MatrixObject mo) {
		FederationMap fedMap = mo.getFedMapping();
		FType oldType = fedMap.getType();

		boolean isRow = true;
		long prev = 0;
		for(FederatedRange e : fedMap.getFederatedRanges()) {
			if(e.getBeginDims()[0] < e.getEndDims()[0] && e.getBeginDims()[0] == prev && isRow)
				prev = e.getEndDims()[0];
			else
				isRow = false;
		}
		if(isRow && oldType.getPartType() == FPartitioning.COL)
			fedMap.setType(FType.ROW);
		else if(!isRow && oldType.getPartType() == FPartitioning.ROW)
			fedMap.setType(FType.COL);
	}

	//TODO remove rmFedOutFlag, once all federated instructions have this flag, then unconditionally remove
	public static FederatedRequest callInstruction(String inst, CPOperand varOldOut, CPOperand[] varOldIn, long[] varNewIn, boolean rmFedOutFlag){
		long id = getNextFedDataID();
		boolean isFedInstr = inst != null
			&& inst.startsWith(ExecType.FED.name() + Lop.OPERAND_DELIMITOR);
		String linst = InstructionUtils.instructionStringFEDPrepare(
			inst, varOldOut, id, varOldIn, varNewIn, rmFedOutFlag || isFedInstr);
		return new FederatedRequest(RequestType.EXEC_INST, id, linst);
	}

	public static FederatedRequest callInstruction(String inst, CPOperand varOldOut, CPOperand[] varOldIn, long[] varNewIn) {
		return callInstruction(inst,varOldOut, varOldIn, varNewIn, false);
	}

	public static FederatedRequest[] callInstruction(String[] inst, CPOperand varOldOut, CPOperand[] varOldIn, long[] varNewIn) {
		long id = getNextFedDataID();
		String[] linst = inst;
		FederatedRequest[] fr = new FederatedRequest[inst.length];
		for(int j=0; j<inst.length; j++) {
			for(int i = 0; i < varOldIn.length; i++) {
				linst[j] = linst[j].replace(
					Lop.OPERAND_DELIMITOR + varOldOut.getName() + Lop.DATATYPE_PREFIX,
					Lop.OPERAND_DELIMITOR + String.valueOf(id) + Lop.DATATYPE_PREFIX);

				if(varOldIn[i] != null) {
					linst[j] = linst[j].replace(
						Lop.OPERAND_DELIMITOR + varOldIn[i].getName() + Lop.DATATYPE_PREFIX,
						Lop.OPERAND_DELIMITOR + String.valueOf(varNewIn[i]) + Lop.DATATYPE_PREFIX);
					linst[j] = linst[j].replace("=" + varOldIn[i].getName(), "=" + String.valueOf(varNewIn[i])); //parameterized
				}
			}
			fr[j] = new FederatedRequest(RequestType.EXEC_INST, id, (Object) linst[j]);
		}
		return fr;
	}

	public static FederatedRequest[] callInstruction(String[] inst, CPOperand varOldOut, long outputId, CPOperand[] varOldIn, long[] varNewIn, ExecType type) {
		String[] linst = inst;
		FederatedRequest[] fr = new FederatedRequest[inst.length];
		for(int j=0; j<inst.length; j++) {
			ExecType targetExec = type == null ? InstructionUtils.getExecType(linst[j]) : type;
			if(targetExec == ExecType.SPARK)
				targetExec = ExecType.CP;
			linst[j] = InstructionUtils.replaceOperand(linst[j], 0, targetExec.name());
			// replace inputs before before outputs in order to prevent conflicts
			// on outputId matching input literals (due to a mix of input instructions,
			// have to apply this replacement even for literal inputs)
			for(int i = 0; i < varOldIn.length; i++) {
				if( varOldIn[i] != null ) {
					linst[j] = linst[j].replace(
						Lop.OPERAND_DELIMITOR + varOldIn[i].getName() + Lop.DATATYPE_PREFIX,
						Lop.OPERAND_DELIMITOR + String.valueOf(varNewIn[i]) + Lop.DATATYPE_PREFIX);
					// handle parameterized builtin functions
					linst[j] = linst[j].replace("=" + varOldIn[i].getName(), "=" + String.valueOf(varNewIn[i]));
				}
			}
			for(int i = 0; i < varOldIn.length; i++) {
				linst[j] = linst[j].replace(
					Lop.OPERAND_DELIMITOR + varOldOut.getName() + Lop.DATATYPE_PREFIX,
					Lop.OPERAND_DELIMITOR + String.valueOf(outputId) + Lop.DATATYPE_PREFIX);
			}
			// Planner output flags are coordinator-only metadata. They can be appended
			// to FED as well as dynamically converted SPARK instructions, but no CP
			// worker parser accepts them as an extra operand.
			if(targetExec != ExecType.FED)
				linst[j] = InstructionUtils.removeFEDOutputFlag(linst[j]);

			fr[j] = new FederatedRequest(RequestType.EXEC_INST, outputId, (Object) linst[j]);
		}
		return fr;
	}

	public static FederatedRequest callInstruction(String inst, CPOperand varOldOut, long outputId, CPOperand[] varOldIn, long[] varNewIn, ExecType type, boolean rmFedOutputFlag) {
		ExecType targetExec = type == null ? InstructionUtils.getExecType(inst) : type;
		if(targetExec == ExecType.SPARK)
			targetExec = ExecType.CP;
		String linst = InstructionUtils.replaceOperand(inst, 0, targetExec.name());
		linst = linst.replace(Lop.OPERAND_DELIMITOR+varOldOut.getName()+Lop.DATATYPE_PREFIX, Lop.OPERAND_DELIMITOR+outputId+Lop.DATATYPE_PREFIX);
		for(int i=0; i<varOldIn.length; i++)
			if( varOldIn[i] != null ) {
				linst = linst.replace(
					Lop.OPERAND_DELIMITOR+varOldIn[i].getName()+Lop.DATATYPE_PREFIX,
					Lop.OPERAND_DELIMITOR+(varNewIn[i])+Lop.DATATYPE_PREFIX);
				linst = linst.replace("="+varOldIn[i].getName(), "="+(varNewIn[i])); //parameterized
			}
		if(rmFedOutputFlag || targetExec != ExecType.FED)
			linst = InstructionUtils.removeFEDOutputFlag(linst);
		return new FederatedRequest(RequestType.EXEC_INST, outputId, linst);
	}

	public static MatrixBlock aggAdd(Future<FederatedResponse>[] ffr) {
		try {
			SimpleOperator op = new SimpleOperator(Plus.getPlusFnObject());
			MatrixBlock[] in = new MatrixBlock[ffr.length];
			for(int i=0; i<ffr.length; i++)
				in[i] = (MatrixBlock) ffr[i].get().getData()[0];
			return MatrixBlock.naryOperations(op, in, new ScalarObject[0], new MatrixBlock());
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggMean(Future<FederatedResponse>[] ffr, FederationMap map) {
		try {
			FederatedRange[] ranges = map.getFederatedRanges();
			BinaryOperator bop = InstructionUtils.parseBinaryOperator(Opcodes.PLUS.toString());
			ScalarOperator sop1 = InstructionUtils.parseScalarBinaryOperator(Opcodes.MULT.toString(), false);
			MatrixBlock ret = null;
			long size = 0;
			for(int i=0; i<ffr.length; i++) {
				Object input = ffr[i].get().getData()[0];
				MatrixBlock tmp = (input instanceof ScalarObject) ?
					new MatrixBlock(((ScalarObject)input).getDoubleValue()) : (MatrixBlock) input;
				size += ranges[i].getSize(0);
				sop1 = sop1.setConstant(ranges[i].getSize(0));
				tmp = tmp.scalarOperations(sop1, new MatrixBlock());
				ret = (ret==null) ? tmp : ret.binaryOperationsInPlace(bop, tmp);
			}
			ScalarOperator sop2 = InstructionUtils.parseScalarBinaryOperator("/", false);
			sop2 = sop2.setConstant(size);
			return ret.scalarOperations(sop2, new MatrixBlock());
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock[] getResults(Future<FederatedResponse>[] ffr) {
		try {
			MatrixBlock[] ret = new MatrixBlock[ffr.length];
			for(int i=0; i<ffr.length; i++)
				ret[i] = (MatrixBlock) ffr[i].get().getData()[0];
			return ret;
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock bind(Future<FederatedResponse>[] ffr, boolean cbind) {
		// TODO handle non-contiguous cases
		try {
			MatrixBlock[] tmp = getResults(ffr);
			return tmp[0].append(
				Arrays.copyOfRange(tmp, 1, tmp.length),
				new MatrixBlock(), cbind);
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggMinMax(Future<FederatedResponse>[] ffr, boolean isMin, boolean isScalar, Optional<FType> fedType) {
		try {
			if (!fedType.isPresent() || fedType.get() == FType.OTHER) {
				double res = isMin ? Double.MAX_VALUE : -Double.MAX_VALUE;
				for (Future<FederatedResponse> fr : ffr) {
					double v = isScalar ? ((ScalarObject) fr.get().getData()[0]).getDoubleValue() :
						isMin ? ((MatrixBlock) fr.get().getData()[0]).min() : ((MatrixBlock) fr.get().getData()[0]).max();
					res = isMin ? Math.min(res, v) : Math.max(res, v);
				}
				return new MatrixBlock(1, 1, res);
			} else {
				MatrixBlock[] tmp = getResults(ffr);
				int dim = fedType.get() == FType.COL ? tmp[0].getNumRows() : tmp[0].getNumColumns();

				for (int i = 0; i < ffr.length - 1; i++)
					for (int j = 0; j < dim; j++)
						if (fedType.get() == FType.COL)
							tmp[i + 1].set(j, 0, isMin ? Double.min(tmp[i].get(j, 0), tmp[i + 1].get(j, 0)) :
								Double.max(tmp[i].get(j, 0), tmp[i + 1].get(j, 0)));
						else tmp[i + 1].set(0, j, isMin ? Double.min(tmp[i].get(0, j), tmp[i + 1].get(0, j)) :
							Double.max(tmp[i].get(0, j), tmp[i + 1].get(0, j)));
				return tmp[ffr.length-1];
			}
		}
		catch (Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggProd(Future<FederatedResponse>[] ffr, FederationMap fedMap, AggregateUnaryOperator aop) {
		try {
			boolean rowFed = fedMap.getType() == FType.ROW;
			MatrixBlock ret = aop.isFullAggregate() ? (rowFed ?
				new MatrixBlock(ffr.length, 1, 1.0) : new MatrixBlock(1, ffr.length, 1.0)) :
				(rowFed ?
				new MatrixBlock(ffr.length, (int) fedMap.getFederatedRanges()[0].getEndDims()[1], 1.0) :
				new MatrixBlock((int) fedMap.getFederatedRanges()[0].getEndDims()[0], ffr.length, 1.0));
			MatrixBlock res = aop.isFullAggregate() ? new MatrixBlock(1, 1, 1.0) :
				(rowFed ?
				new MatrixBlock(1, (int) fedMap.getFederatedRanges()[0].getEndDims()[1], 1.0) :
				new MatrixBlock((int) fedMap.getFederatedRanges()[0].getEndDims()[0], 1, 1.0));

			for(int i = 0; i < ffr.length; i++) {
				MatrixBlock tmp = (MatrixBlock) ffr[i].get().getData()[0];
				if(rowFed)
					ret.copy(i, i, 0, ret.getNumColumns()-1, tmp, true);
				else
					ret.copy(0, ret.getNumRows()-1, i, i, tmp, true);
			}

			LibMatrixAgg.aggregateUnaryMatrix(ret, res, aop);
			return res;
		}
		catch (Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggMinMaxIndex(Future<FederatedResponse>[] ffr, boolean isMin, FederationMap map) {
		try {
			MatrixBlock prev = (MatrixBlock) ffr[0].get().getData()[0];
			int size = 0;
			for(int i = 1; i < ffr.length; i++) {
				MatrixBlock next = (MatrixBlock) ffr[i].get().getData()[0];
				size = map.getFederatedRanges()[i-1].getEndDimsInt()[1];
				for(int j = 0; j < prev.getNumRows(); j++) {
					next.set(j, 0, next.get(j, 0) + size);
					if((prev.get(j, 1) > next.get(j, 1) && !isMin) ||
						(prev.get(j, 1) < next.get(j, 1) && isMin)) {
						next.set(j, 0, prev.get(j, 0));
						next.set(j, 1, prev.get(j, 1));
					}
				}
				prev = next;
			}
			return prev.slice(0, prev.getNumRows()-1, 0,0, true, new MatrixBlock());
		}
		catch (Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggVar(Future<FederatedResponse>[] ffr, Future<FederatedResponse>[] meanFfr, FederationMap map, boolean isRowAggregate, boolean isScalar) {
		try {
			FederatedRange[] ranges = map.getFederatedRanges();
			BinaryOperator plus = InstructionUtils.parseBinaryOperator(Opcodes.PLUS.toString());
			BinaryOperator minus = InstructionUtils.parseBinaryOperator("-");

			ScalarOperator mult1 = InstructionUtils.parseScalarBinaryOperator(Opcodes.MULT.toString(), false);
			ScalarOperator dev1 = InstructionUtils.parseScalarBinaryOperator(Opcodes.DIV.toString(), false);
			ScalarOperator pow = InstructionUtils.parseScalarBinaryOperator(Opcodes.POW2.toString(), false);

			long size1 = isScalar ? ranges[0].getSize() : ranges[0].getSize(isRowAggregate ? 1 : 0);
			MatrixBlock var1 = (MatrixBlock)ffr[0].get().getData()[0];
			MatrixBlock mean1 = (MatrixBlock)meanFfr[0].get().getData()[0];
			for(int i=0; i < ffr.length - 1; i++) {
				MatrixBlock var2 = (MatrixBlock)ffr[i+1].get().getData()[0];
				MatrixBlock mean2 = (MatrixBlock)meanFfr[i+1].get().getData()[0];
				long size2 = isScalar ? ranges[i+1].getSize() : ranges[i+1].getSize(isRowAggregate ? 1 : 0);

				mult1 = mult1.setConstant(size1);
				var1 = var1.scalarOperations(mult1, new MatrixBlock());
				mult1 = mult1.setConstant(size2);
				var1 = var1.binaryOperationsInPlace(plus, var2.scalarOperations(mult1, new MatrixBlock()));
				dev1 = dev1.setConstant(size1 + size2);
				var1 = var1.scalarOperations(dev1, new MatrixBlock());

				MatrixBlock tmp1 = new MatrixBlock(mean1);
				tmp1 = tmp1.binaryOperationsInPlace(minus, mean2);
				tmp1 = tmp1.scalarOperations(dev1, new MatrixBlock());
				tmp1 = tmp1.scalarOperations(pow, new MatrixBlock());
				mult1 = mult1.setConstant(size1*size2);
				tmp1 = tmp1.scalarOperations(mult1, new MatrixBlock());
				var1 = tmp1.binaryOperationsInPlace(plus, var1);

				// next mean
				mult1 = mult1.setConstant(size1);
				tmp1 = mean1.scalarOperations(mult1, new MatrixBlock());
				mult1 = mult1.setConstant(size2);
				mean1 = tmp1.binaryOperationsInPlace(plus, mean2.scalarOperations(mult1, new MatrixBlock()));
				mean1 = mean1.scalarOperations(dev1, new MatrixBlock());

				size1 = size1 + size2;
			}

			return var1;
		}
		catch (Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static ScalarObject aggScalar(AggregateUnaryOperator aop, Future<FederatedResponse>[] ffr, Future<FederatedResponse>[] meanFfr, FederationMap map) {
		if(!(aop.aggOp.increOp.fn instanceof KahanFunction || aop.aggOp.increOp.fn instanceof CM ||
			(aop.aggOp.increOp.fn instanceof Builtin &&
				(((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN ||
				((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MAX)
				|| aop.aggOp.increOp.fn instanceof Mean))) {
			throw new DMLRuntimeException("Unsupported aggregation operator: "
				+ aop.aggOp.increOp.getClass().getSimpleName());
		}

		try {
			if(aop.aggOp.increOp.fn instanceof Builtin){
				// then we know it is a Min or Max based on the previous check.
				boolean isMin = ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN;
				return new DoubleObject(aggMinMax(ffr, isMin, true,  Optional.empty()).get(0,0));
			}
			else if( aop.aggOp.increOp.fn instanceof Mean ) {
				return new DoubleObject(aggMean(ffr, map).get(0,0));
			}
			else if(aop.aggOp.increOp.fn instanceof CM) {
				long size1 = map.getFederatedRanges()[0].getSize();
				double mean1 = ((ScalarObject) meanFfr[0].get().getData()[0]).getDoubleValue();
				double squaredM1 = ((ScalarObject) ffr[0].get().getData()[0]).getDoubleValue() * (size1 - 1);
				for(int i = 1; i < ffr.length; i++) {
					long size2 = map.getFederatedRanges()[i].getSize();
					double delta = ((ScalarObject) meanFfr[i].get().getData()[0]).getDoubleValue() - mean1;
					double squaredM2 =  ((ScalarObject) ffr[i].get().getData()[0]).getDoubleValue() * (size2 - 1);
					squaredM1 = squaredM1 + squaredM2 + (Math.pow(delta, 2) * size1 * size2 / (size1 + size2));

					size1 += size2;
					mean1 = mean1 + delta * size2 / size1;
				}
				double var = squaredM1 / (size1 - 1);
				return new DoubleObject(var);

			}
			else { //if (aop.aggOp.increOp.fn instanceof KahanFunction)
				double sum = 0; //uak+
				for( Future<FederatedResponse> fr : ffr )
					sum += ((ScalarObject)fr.get().getData()[0]).getDoubleValue();
				return new DoubleObject(sum);
			}
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static MatrixBlock aggMatrix(AggregateUnaryOperator aop, Future<FederatedResponse>[] ffr, Future<FederatedResponse>[] meanFfr, FederationMap map) {
		// Replicated input/output: all workers produce identical results, hence no aggregation required.
		if (map != null && map.getType() == FType.BROADCAST)
			return getResults(ffr)[0];
		if (aop.isRowAggregate() && map.getType() == FType.ROW)
			return bind(ffr, false);
		else if (aop.isColAggregate() && map.getType() == FType.COL)
			return bind(ffr, true);

		if (aop.aggOp.increOp.fn instanceof KahanFunction)
			return aggAdd(ffr);
		else if( aop.aggOp.increOp.fn instanceof Mean )
			return aggMean(ffr, map);
		else if (aop.aggOp.increOp.fn instanceof Builtin &&
			(((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN ||
				((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MAX)) {
			boolean isMin = ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN;
			return aggMinMax(ffr,isMin,false, Optional.of(map.getType()));
		} else if(aop.aggOp.increOp.fn instanceof CM) {
			return aggVar(ffr, meanFfr, map, aop.isRowAggregate(), !(aop.isColAggregate() || aop.isRowAggregate())); //TODO
		}
		else
			throw new DMLRuntimeException("Unsupported aggregation operator: "
				+ aop.aggOp.increOp.fn.getClass().getSimpleName());
	}

	public static void waitFor(List<Future<FederatedResponse>> responses) {
		try {
			final int timeout = ConfigurationManager.getFederatedTimeout();
			for(Future<FederatedResponse> fr : responses) {
				FederatedResponse response = timeout > 0
					? fr.get(timeout, TimeUnit.SECONDS) : fr.get();
				// A transport future completes normally even when the worker returns an
				// ERROR response. Waiting without inspecting the response therefore lets
				// callers publish nonexistent federated output IDs. Treat wait=true as a
				// successful-completion barrier, as its API contract and callers expect.
				if(!response.isSuccessful())
					response.throwExceptionFromResponse();
			}
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	public static ScalarObject aggScalar(AggregateUnaryOperator aop, Future<FederatedResponse>[] ffr) {
		return aggScalar(aop, ffr, null);
	}

	public static ScalarObject aggScalar(AggregateUnaryOperator aop, Future<FederatedResponse>[] ffr, FederationMap map) {
		// Replicated input/output: all workers produce identical results, hence no aggregation required.
		if (map != null && map.getType() == FType.BROADCAST) {
			try {
				Object o = ffr[0].get().getData()[0];
				if (o instanceof ScalarObject)
					return (ScalarObject) o;
				if (o instanceof MatrixBlock)
					return new DoubleObject(((MatrixBlock) o).get(0, 0));
				throw new DMLRuntimeException("Unexpected federated scalar type: " + (o != null ? o.getClass() : "null"));
			}
			catch (Exception ex) {
				throw new DMLRuntimeException(ex);
			}
		}
		if(!(aop.aggOp.increOp.fn instanceof KahanFunction || (aop.aggOp.increOp.fn instanceof Builtin &&
			(((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN
			|| ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MAX)
			|| aop.aggOp.increOp.fn instanceof Mean
			|| aop.aggOp.increOp.fn instanceof Multiply))) {
			throw new DMLRuntimeException("Unsupported aggregation operator: "
				+ aop.aggOp.increOp.getClass().getSimpleName());
		}

		try {
			if(aop.aggOp.increOp.fn instanceof Multiply){
				MatrixBlock ret = new MatrixBlock(ffr.length, 1, false);
				MatrixBlock res = new MatrixBlock(0);
				for(int i = 0; i < ffr.length; i++)
					ret.set(i, 0, ((ScalarObject)ffr[i].get().getData()[0]).getDoubleValue());
				LibMatrixAgg.aggregateUnaryMatrix(ret, res,
					new AggregateUnaryOperator(new AggregateOperator(1, Multiply.getMultiplyFnObject()),
						ReduceAll.getReduceAllFnObject()));
				return new DoubleObject(res.get(0, 0));
			}
			else if(aop.aggOp.increOp.fn instanceof Builtin){
				// then we know it is a Min or Max based on the previous check.
				boolean isMin = ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN;
				return new DoubleObject(aggMinMax(ffr, isMin, true,  Optional.empty()).get(0,0));
			}
			else if( aop.aggOp.increOp.fn instanceof Mean ) {
				return new DoubleObject(aggMean(ffr, map).get(0,0));
			}
			else { //if (aop.aggOp.increOp.fn instanceof KahanFunction)
				double sum = 0; //uak+
				for( Future<FederatedResponse> fr : ffr )
					sum += ((ScalarObject)fr.get().getData()[0]).getDoubleValue();
				return new DoubleObject(sum);
			}
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}
	
	public static boolean aggBooleanScalar(Future<FederatedResponse>[] tmp) {
		boolean ret = false;
		try {
			for( Future<FederatedResponse> fr : tmp )
				ret |= ((ScalarObject)fr.get().getData()[0]).getBooleanValue();
		}
		catch (Exception e) {
			throw new DMLRuntimeException(e);
		}
		return ret;
	}
	
	public static MatrixBlock aggMatrix(AggregateUnaryOperator aop, Future<FederatedResponse>[] ffr, FederationMap map) {
		// Replicated input/output: all workers produce identical results, hence no aggregation required.
		if (map != null && map.getType() == FType.BROADCAST)
			return getResults(ffr)[0];
		if (aop.isRowAggregate() && map.getType() == FType.ROW)
			return bind(ffr, false);
		else if (aop.isColAggregate() && map.getType() == FType.COL)
			return bind(ffr, true);

		if (aop.aggOp.increOp.fn instanceof KahanFunction)
			return aggAdd(ffr);
		else if( aop.aggOp.increOp.fn instanceof Mean )
			return aggMean(ffr, map);
		else if(aop.aggOp.increOp.fn instanceof Multiply)
			return aggProd(ffr, map, aop);
		else if (aop.aggOp.increOp.fn instanceof Builtin) {
			if ((((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN ||
				((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MAX)) {
				boolean isMin = ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MIN;
				return aggMinMax(ffr,isMin,false, Optional.of(map.getType()));
			}
			else if((((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MININDEX)
				|| (((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MAXINDEX)) {
				boolean isMin = ((Builtin) aop.aggOp.increOp.fn).getBuiltinCode() == BuiltinCode.MININDEX;
				return aggMinMaxIndex(ffr, isMin, map);
			}
			else throw new DMLRuntimeException("Unsupported aggregation operator: "
					+ aop.aggOp.increOp.fn.getClass().getSimpleName());
		}
		else
			throw new DMLRuntimeException("Unsupported aggregation operator: "
				+ aop.aggOp.increOp.fn.getClass().getSimpleName());
	}

	public static FederationMap federateLocalData(CacheableData<?> data) {
		long id = FederationUtils.getNextFedDataID();
		FederatedLocalData federatedLocalData = new FederatedLocalData(id, data);
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
		fedMap.add(Pair.of(
			new FederatedRange(new long[2], new long[] {data.getNumRows(), data.getNumColumns()}),
			federatedLocalData));
		return new FederationMap(id, fedMap);
	}

	/**
	 * Bind data from federated workers based on non-overlapping federated ranges.
	 * @param readResponses responses from federated workers containing the federated ranges and data
	 * @param dims dimensions of output MatrixBlock
	 * @return MatrixBlock of consolidated data
	 * @throws Exception in case of problems with getting data from responses
	 */
	public static MatrixBlock bindResponses(List<Pair<FederatedRange, Future<FederatedResponse>>> readResponses, long[] dims)
		throws Exception
	{
		long totalNNZ = 0;
		for(Pair<FederatedRange, Future<FederatedResponse>> readResponse : readResponses) {
			FederatedResponse response = readResponse.getRight().get();
			MatrixBlock multRes = (MatrixBlock) response.getData()[0];
			totalNNZ += multRes.getNonZeros();
		}
		MatrixBlock ret = new MatrixBlock((int) dims[0], (int) dims[1], MatrixBlock.evalSparseFormatInMemory(dims[0], dims[1], totalNNZ));
		for(Pair<FederatedRange, Future<FederatedResponse>> readResponse : readResponses) {
			FederatedRange range = readResponse.getLeft();
			FederatedResponse response = readResponse.getRight().get();
			// add result
			int[] beginDimsInt = range.getBeginDimsInt();
			int[] endDimsInt = range.getEndDimsInt();
			MatrixBlock multRes = (MatrixBlock) response.getData()[0];
			ret.copy(beginDimsInt[0], endDimsInt[0] - 1, beginDimsInt[1], endDimsInt[1] - 1, multRes, false);
		}
		ret.setNonZeros(totalNNZ);
		return ret;
	}

	/**
	 * Aggregate partially aggregated data from federated workers
	 * by adding values with the same index in different federated locations.
	 * @param readResponses responses from federated workers containing the federated data
	 * @return MatrixBlock of consolidated, aggregated data
	 */
	@SuppressWarnings("unchecked")
	public static MatrixBlock aggregateResponses(List<Pair<FederatedRange, Future<FederatedResponse>>> readResponses) {
		List<Future<FederatedResponse>> dataParts = new ArrayList<>();
		for ( Pair<FederatedRange, Future<FederatedResponse>> readResponse : readResponses )
			dataParts.add(readResponse.getValue());
		return FederationUtils.aggAdd(dataParts.toArray(new Future[0]));
	}

	public static ObjectDecoder decoder() {
		return new ObjectDecoder(Integer.MAX_VALUE,
			ClassResolvers.weakCachingResolver(ClassLoader.getSystemClassLoader()));
	}

	public static Optional<ChannelOutboundHandlerAdapter> compressionEncoder() {
		return compressionStrategy().map(strategy -> strategy.right);
	}

	public static Optional<ChannelInboundHandlerAdapter> compressionDecoder() {
		return compressionStrategy().map(strategy -> strategy.left);
	}

	public static Optional<ImmutablePair<ChannelInboundHandlerAdapter, ChannelOutboundHandlerAdapter>> compressionStrategy() {
		String strategy = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_COMPRESSION).toLowerCase();
		switch (strategy) {
			case "none":
				return Optional.empty();
			case "zlib":
				return Optional.of(new ImmutablePair<>(new JdkZlibDecoder(), new JdkZlibEncoder()));
			case "snappy":
				return Optional.of(new ImmutablePair<>(new SnappyFrameDecoder(), new SnappyFrameEncoder()));
			case "fastlz":
				return Optional.of(new ImmutablePair<>(new FastLzFrameDecoder(), new FastLzFrameEncoder()));
			case "lz4":
				return Optional.of(new ImmutablePair<>(new Lz4FrameDecoder(), new Lz4FrameEncoder()));
			case "lzf":
				return Optional.of(new ImmutablePair<>(new LzfDecoder(), new LzfEncoder()));
			default:
				throw new IllegalArgumentException("Invalid federated compression strategy: " + strategy);
		}
	}

	public static long sumNonZeros(Future<FederatedResponse>[] responses) {
		long nnz = 0;
		boolean allLong = true;
		for(Future<FederatedResponse> r : responses) {
			try {
				Object[] data = r.get().getData(); // propagates federated worker errors
				if(data == null || data.length == 0 || !(data[0] instanceof Long))
					allLong = false;
				else
					nnz += (Long) data[0];
			}
			catch(Exception ex) {
				// Do not silently swallow federated execution errors: otherwise callers may
				// proceed with inconsistent fed mappings and fail later with confusing
				// "variable does not exist" errors.
				throw new DMLRuntimeException(ex);
			}
		}
		return allLong ? nnz : -1;
	}

	private static final class RefedReuseKey {
		private final String _inputSig;
		private final long _inputMutationVersion;
		private final long _rows;
		private final long _cols;
		private final long _nnz;
		private final String _layoutSig;
		private final FType _outType;

		private RefedReuseKey(String inputSig, long inputMutationVersion,
			long rows, long cols, long nnz, String layoutSig, FType outType) {
			_inputSig = inputSig;
			_inputMutationVersion = inputMutationVersion;
			_rows = rows;
			_cols = cols;
			_nnz = nnz;
			_layoutSig = layoutSig;
			_outType = outType;
		}

		@Override
		public int hashCode() {
			return Objects.hash(_inputSig, _inputMutationVersion, _rows, _cols, _nnz,
				_layoutSig, _outType);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof RefedReuseKey))
				return false;
			RefedReuseKey that = (RefedReuseKey) obj;
			return Objects.equals(_inputSig, that._inputSig)
				&& _inputMutationVersion == that._inputMutationVersion
				&& _rows == that._rows
				&& _cols == that._cols
				&& _nnz == that._nnz
				&& Objects.equals(_layoutSig, that._layoutSig)
				&& _outType == that._outType;
		}
	}

	private static final class OwnedRefedReuseEntry {
		private final FederationMap _canonical;
		private final long _tid;
		private final long _estimatedBytes;
		private final boolean _planned;
		private final RefedReuseAudit.Context _audit;

		private OwnedRefedReuseEntry(FederationMap canonical, long tid, long estimatedBytes, boolean planned,
			RefedReuseAudit.Context audit) {
			_canonical = canonical;
			_tid = tid;
			_estimatedBytes = estimatedBytes;
			_planned = planned;
			_audit = audit;
		}
	}

	private static final class OwnedRefedReuseKey {
		private final MatrixObject _owner;
		private final long _inputMutationVersion;
		private final FederatedValueIdentity _source;
		private final long _rows;
		private final long _cols;
		private final long _nnz;
		private final long _tid;
		private final String _layoutSig;
		private final FType _outType;
		private final String _sharingGroup;

		private OwnedRefedReuseKey(MatrixObject owner, long inputMutationVersion, FederatedValueIdentity source,
			long rows, long cols, long nnz, long tid, String layoutSig, FType outType, String sharingGroup) {
			_owner = owner;
			_inputMutationVersion = inputMutationVersion;
			_source = source;
			_rows = rows;
			_cols = cols;
			_nnz = nnz;
			_tid = tid;
			_layoutSig = layoutSig;
			_outType = outType;
			_sharingGroup = sharingGroup;
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(_owner);
			result = 31 * result + Long.hashCode(_inputMutationVersion);
			result = 31 * result + Objects.hashCode(_source);
			result = 31 * result + Long.hashCode(_rows);
			result = 31 * result + Long.hashCode(_cols);
			result = 31 * result + Long.hashCode(_nnz);
			result = 31 * result + Long.hashCode(_tid);
			result = 31 * result + Objects.hashCode(_layoutSig);
			result = 31 * result + Objects.hashCode(_outType);
			result = 31 * result + Objects.hashCode(_sharingGroup);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof OwnedRefedReuseKey))
				return false;
			OwnedRefedReuseKey that = (OwnedRefedReuseKey) obj;
			return _owner == that._owner
				&& _inputMutationVersion == that._inputMutationVersion
				&& Objects.equals(_source, that._source)
				&& _rows == that._rows
				&& _cols == that._cols
				&& _nnz == that._nnz
				&& _tid == that._tid
				&& Objects.equals(_layoutSig, that._layoutSig)
				&& _outType == that._outType
				&& Objects.equals(_sharingGroup, that._sharingGroup);
		}
	}
}

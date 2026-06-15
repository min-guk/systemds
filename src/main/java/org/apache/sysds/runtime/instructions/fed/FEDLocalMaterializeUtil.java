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

package org.apache.sysds.runtime.instructions.fed;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.cp.Data;

final class FEDLocalMaterializeUtil {
	private static final Log LOG = LogFactory.getLog(FEDLocalMaterializeUtil.class.getName());

	private FEDLocalMaterializeUtil() {
		// utility class
	}

	static FType normalizeReplicatedMapType(FType materializeType, FType mapType, int workers) {
		// If we materialize via FULL broadcast, every worker receives the entire object,
		// which is semantically replicated (BROADCAST) regardless of worker count.
		//
		// Keeping FULL for a single-worker pool causes downstream FED ops to treat the
		// value as non-broadcast and can trigger expensive fallback paths (e.g., FULL
		// alignment checks leading to GET+PUT of large matrices).
		if (materializeType == FType.FULL && mapType == FType.FULL)
			return FType.BROADCAST;
		return mapType;
	}

	static FType normalizeSupportedAnchorType(FederationMap map) {
		if (map == null)
			return null;
		FType type = map.getType();
		if (type != FType.OTHER)
			return type;

		FType inferred = inferSupportedOtherType(map);
		if (inferred != null && inferred != FType.OTHER) {
			map.setType(inferred);
			return inferred;
		}
		return type;
	}

	static boolean hasLocalFederatedData(FederationMap map) {
		if (map == null)
			return false;
		for (Pair<FederatedRange, FederatedData> entry : map.getMap()) {
			FederatedData data = entry != null ? entry.getValue() : null;
			if (data instanceof FederatedLocalData)
				return true;
			if (data != null && data.getAddress() == null)
				return true;
		}
		return false;
	}

	static FederationMap findUniqueWorkerPoolAnchor(ExecutionContext ec) {
		if (ec == null)
			return null;
		LocalVariableMap vars = ec.getVariables();
		if (vars == null || vars.keySet().isEmpty())
			return null;

		HashSet<java.net.InetSocketAddress> pool = null;
		FederationMap anchor = null;

		for (String name : vars.keySet()) {
			Data dat = vars.get(name);
			if (!(dat instanceof MatrixObject))
				continue;
			MatrixObject mo = (MatrixObject) dat;
			if (!mo.isFederated() || mo.getFedMapping() == null || mo.getFedMapping().getSize() == 0)
				continue;
			FederationMap map = mo.getFedMapping();
			HashSet<java.net.InetSocketAddress> workers = new HashSet<>();
			for (FederatedData d : map.getFederatedData()) {
				if (d != null && d.getAddress() != null)
					workers.add(d.getAddress());
			}
			if (workers.isEmpty())
				continue;
			if (pool == null) {
				pool = workers;
				anchor = map;
			}
			else if (!pool.equals(workers)) {
				return null;
			}
		}

		return anchor;
	}

	private static FType inferSupportedOtherType(FederationMap map) {
		if (map == null || map.getSize() <= 0)
			return null;
		// A single-worker OTHER map still provides a concrete worker pool. For local->federated
		// materialization/refederation, using that worker as a FULL anchor is explicit and safe.
		if (map.getSize() == 1)
			return FType.FULL;

		FederatedRange[] ranges = map.getFederatedRanges();
		if (ranges == null || ranges.length != map.getSize())
			return null;
		for (FederatedRange range : ranges) {
			if (range == null || range.getBeginDims() == null || range.getEndDims() == null
				|| range.getBeginDims().length < 2 || range.getEndDims().length < 2)
				return null;
		}

		long[] firstBegin = ranges[0].getBeginDims();
		long[] firstEnd = ranges[0].getEndDims();
		boolean allSame = Arrays.stream(ranges)
			.allMatch(r -> Arrays.equals(firstBegin, r.getBeginDims()) && Arrays.equals(firstEnd, r.getEndDims()));
		if (allSame)
			return FType.BROADCAST;

		long maxRow = map.getMaxIndexInRange(0);
		long maxCol = map.getMaxIndexInRange(1);
		boolean rowPartitioned = Arrays.stream(ranges)
			.allMatch(r -> r.getBeginDims()[1] == 0 && r.getEndDims()[1] == maxCol);
		boolean colPartitioned = Arrays.stream(ranges)
			.allMatch(r -> r.getBeginDims()[0] == 0 && r.getEndDims()[0] == maxRow);
		if (rowPartitioned && !colPartitioned)
			return FType.ROW;
		if (colPartitioned && !rowPartitioned)
			return FType.COL;
		return FType.OTHER;
	}

	static FederationMap materializeLocalToAnchor(long tid, MatrixObject in, FederationMap anchorMap,
		FType materializeType, FType mapType, long rlen, long clen,
		boolean retryOnChannelClose, String retryContext) {
		if (anchorMap == null || anchorMap.getSize() == 0)
			throw new DMLRuntimeException("fed_fout cannot materialize: no federated parent/worker pool (empty anchor map)");
		if (materializeType == FType.PART || materializeType == FType.OTHER
			|| mapType == FType.PART || mapType == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support ftype " + mapType);

		final int numWorkers = anchorMap.getSize();
		FType effectiveMapType = normalizeReplicatedMapType(materializeType, mapType, numWorkers);
		long outId;
		List<Pair<FederatedRange, FederatedData>> outMap = new ArrayList<>();

		if (materializeType == FType.FULL) {
			FederatedRequest fr = executeBroadcastWithOptionalRetry(tid, in, anchorMap,
				retryOnChannelClose, retryContext);
			outId = fr.getID();
			for (Pair<FederatedRange, FederatedData> entry : anchorMap.getMap()) {
				FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rlen, clen});
				FederatedData data = entry.getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}
		else {
			long[] rowBeg = new long[numWorkers];
			long[] rowEnd = new long[numWorkers];
			long[] colBeg = new long[numWorkers];
			long[] colEnd = new long[numWorkers];
			if (materializeType == FType.ROW) {
				long base = rlen / numWorkers;
				long rem = rlen % numWorkers;
				long pos = 0;
				for (int i = 0; i < numWorkers; i++) {
					long size = base + (i < rem ? 1 : 0);
					rowBeg[i] = pos;
					rowEnd[i] = pos + size;
					colBeg[i] = 0;
					colEnd[i] = clen;
					pos += size;
				}
			}
			else if (materializeType == FType.COL) {
				long base = clen / numWorkers;
				long rem = clen % numWorkers;
				long pos = 0;
				for (int i = 0; i < numWorkers; i++) {
					long size = base + (i < rem ? 1 : 0);
					rowBeg[i] = 0;
					rowEnd[i] = rlen;
					colBeg[i] = pos;
					colEnd[i] = pos + size;
					pos += size;
				}
			}
			else {
				throw new DMLRuntimeException("Unsupported materialize type for local->federated upload: " + materializeType);
			}

			int[][] ix = new int[numWorkers][4];
			for (int i = 0; i < numWorkers; i++) {
				long rb = rowBeg[i];
				long re = rowEnd[i];
				long cb = colBeg[i];
				long ce = colEnd[i];
				ix[i][0] = (int) rb;
				ix[i][1] = (int) (re - 1);
				ix[i][2] = (int) cb;
				ix[i][3] = (int) (ce - 1);
			}

			FederationMap sliceMap = anchorMap;
			if (anchorMap.getType() == FType.FULL)
				sliceMap = new FederationMap(anchorMap.getID(), anchorMap.getMap(), materializeType);

			FederatedRequest[] frSlices = executeBroadcastSlicedWithOptionalRetry(tid, in, anchorMap, sliceMap, ix,
				retryOnChannelClose, retryContext);
			if (frSlices.length != numWorkers)
				throw new DMLRuntimeException("fed_fout slice request count mismatch: expected=" + numWorkers
					+ " actual=" + frSlices.length);
			outId = frSlices[0].getID();

			for (int i = 0; i < numWorkers; i++) {
				FederatedRange range = new FederatedRange(new long[] {rowBeg[i], colBeg[i]},
					new long[] {rowEnd[i], colEnd[i]});
				FederatedData data = anchorMap.getMap().get(i).getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}

		return new FederationMap(outId, outMap, effectiveMapType);
	}

	private static FederatedRequest executeBroadcastWithOptionalRetry(long tid, MatrixObject in, FederationMap anchorMap,
		boolean retryOnChannelClose, String retryContext) {
		int attempts = 0;
		while (true) {
			try {
				FederatedRequest fr = anchorMap.broadcast(in);
				anchorMap.execute(tid, true, fr);
				return fr;
			}
			catch (DMLRuntimeException ex) {
				if (retryOnChannelClose && attempts == 0 && (isConnectionReset(ex) || isChannelClosed(ex))) {
					LOG.warn(retryContext + " retrying broadcast after channel closure");
					attempts++;
					continue;
				}
				throw ex;
			}
		}
	}

	private static FederatedRequest[] executeBroadcastSlicedWithOptionalRetry(long tid, MatrixObject in,
		FederationMap anchorMap, FederationMap sliceMap, int[][] ix, boolean retryOnChannelClose, String retryContext) {
		int attempts = 0;
		while (true) {
			try {
				FederatedRequest[] frSlices = sliceMap.broadcastSliced(in, false, ix);
				anchorMap.execute(tid, true, frSlices, new FederatedRequest[0]);
				return frSlices;
			}
			catch (DMLRuntimeException ex) {
				if (retryOnChannelClose && attempts == 0 && (isConnectionReset(ex) || isChannelClosed(ex))) {
					LOG.warn(retryContext + " retrying sliced broadcast after channel closure");
					attempts++;
					continue;
				}
				throw ex;
			}
		}
	}

	private static boolean isConnectionReset(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof SocketException)
				return true;
			String msg = cur.getMessage();
			if (msg != null && msg.contains("Connection reset"))
				return true;
			cur = cur.getCause();
		}
		return false;
	}

	private static boolean isChannelClosed(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			String msg = cur.getMessage();
			if (msg != null && msg.toLowerCase().contains("channel closed"))
				return true;
			cur = cur.getCause();
		}
		return false;
	}
}

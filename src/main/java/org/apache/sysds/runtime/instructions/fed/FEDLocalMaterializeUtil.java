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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;

final class FEDLocalMaterializeUtil {
	private FEDLocalMaterializeUtil() {
		// utility class
	}

	static FType normalizeReplicatedMapType(FType materializeType, FType mapType, int workers) {
		// FULL denotes one complete, non-replicated federated object.  Preserve it for
		// the only exact representation: a single worker.  Re-labelling a multi-worker
		// broadcast as FULL would mutate the planner-selected placement at runtime.
		if (materializeType == FType.FULL && mapType == FType.FULL && workers != 1)
			throw new DMLRuntimeException("FULL materialization requires exactly one worker but found " + workers);
		return mapType;
	}

	static FType declaredAnchorType(FederationMap map) {
		if (map == null)
			return null;
		FType type = map.getType();
		if (type == FType.FULL && map.getSize() != 1)
			throw new DMLRuntimeException("FULL anchor requires exactly one worker but found " + map.getSize());
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

	/**
	 * Verifies that an already-federated value is the exact physical value selected by
	 * a planned FOUT/refederation boundary.  Equal worker sets and equal enum labels are
	 * insufficient: ROW/COL ranges must target the same workers, while FULL is valid only
	 * for one range and BROADCAST must contain one complete range per selected worker.
	 */
	static boolean matchesPlannedLayout(FederationMap inMap, FederationMap anchorMap,
		FType materializeType, FType mapType, long rlen, long clen) {
		if(inMap == null || anchorMap == null || rlen <= 0 || clen <= 0)
			return false;
		int workers = anchorMap.getSize();
		if(workers <= 0 || inMap.getSize() != workers)
			return false;
		FType inType = inMap.getType();
		if(mapType == FType.FULL) {
			if(workers != 1 || inType != FType.FULL && inType != FType.BROADCAST)
				return false;
		}
		else if(mapType == FType.BROADCAST) {
			if(inType != FType.BROADCAST && !(workers == 1 && inType == FType.FULL))
				return false;
		}
		else if(inType != mapType)
			return false;

		FederatedRange[] inputRanges = inMap.getFederatedRanges();
		FederatedData[] inputData = inMap.getFederatedData();
		FederatedData[] anchorData = anchorMap.getFederatedData();
		FederatedRange[] expectedRanges;
		try {
			expectedRanges = plannedMaterializationRanges(anchorMap, materializeType, rlen, clen);
		}
		catch(DMLRuntimeException ex) {
			return false;
		}
		if(inputRanges.length != workers || inputData.length != workers
			|| expectedRanges.length != workers || anchorData.length != workers)
			return false;

		for(int i = 0; i < workers; i++) {
			if(inputData[i] == null || anchorData[i] == null
				|| inputData[i].getAddress() == null
				|| !inputData[i].getAddress().equals(anchorData[i].getAddress()))
				return false;
			FederatedRange expected = expectedRanges[i];
			if(expected == null || inputRanges[i] == null
				|| !java.util.Arrays.equals(inputRanges[i].getBeginDims(), expected.getBeginDims())
				|| !java.util.Arrays.equals(inputRanges[i].getEndDims(), expected.getEndDims()))
				return false;
		}
		return true;
	}

	private static FederatedRange[] plannedMaterializationRanges(FederationMap anchorMap,
		FType materializeType, long rlen, long clen) {
		if(anchorMap == null || anchorMap.getSize() <= 0 || rlen <= 0 || clen <= 0)
			throw new DMLRuntimeException("Invalid anchor or output dimensions for planned materialization");
		int workers = anchorMap.getSize();
		FederatedRange[] ranges = new FederatedRange[workers];
		if(materializeType == FType.FULL) {
			for(int i = 0; i < workers; i++)
				ranges[i] = new FederatedRange(new long[] {0, 0}, new long[] {rlen, clen});
			return ranges;
		}
		if(materializeType != FType.ROW && materializeType != FType.COL)
			throw new DMLRuntimeException("Unsupported materialize type " + materializeType);

		boolean preserveAnchorRanges = materializeType == anchorMap.getType()
			&& anchorMap.getMaxIndexInRange(0) == rlen
			&& anchorMap.getMaxIndexInRange(1) == clen;
		FederatedRange[] anchorRanges = anchorMap.getFederatedRanges();
		long length = materializeType == FType.ROW ? rlen : clen;
		long previousEnd = 0;
		for(int i = 0; i < workers; i++) {
			if(preserveAnchorRanges) {
				if(anchorRanges.length != workers || anchorRanges[i] == null)
					throw new DMLRuntimeException("Anchor range count does not match its worker count");
				long[] begin = anchorRanges[i].getBeginDims();
				long[] end = anchorRanges[i].getEndDims();
				if(begin == null || end == null || begin.length < 2 || end.length < 2)
					throw new DMLRuntimeException("Anchor contains an invalid range");
				ranges[i] = new FederatedRange(begin.clone(), end.clone());
			}
			else {
				long base = length / workers;
				long remainder = length % workers;
				long begin = i * base + Math.min(i, remainder);
				long end = begin + base + (i < remainder ? 1 : 0);
				ranges[i] = materializeType == FType.ROW
					? new FederatedRange(new long[] {begin, 0}, new long[] {end, clen})
					: new FederatedRange(new long[] {0, begin}, new long[] {rlen, end});
			}
			long[] begin = ranges[i].getBeginDims();
			long[] end = ranges[i].getEndDims();
			long partitionBegin = materializeType == FType.ROW ? begin[0] : begin[1];
			long partitionEnd = materializeType == FType.ROW ? end[0] : end[1];
			boolean fullOtherDimension = materializeType == FType.ROW
				? begin[1] == 0 && end[1] == clen : begin[0] == 0 && end[0] == rlen;
			if(!fullOtherDimension || partitionBegin != previousEnd || partitionEnd <= partitionBegin
				|| partitionEnd > length)
				throw new DMLRuntimeException("Anchor ranges do not form an exact contiguous "
					+ materializeType + " partition for " + rlen + "x" + clen);
			previousEnd = partitionEnd;
		}
		if(previousEnd != length)
			throw new DMLRuntimeException("Anchor ranges do not cover the planned output");
		return ranges;
	}

	static FederationMap materializeLocalToAnchor(long tid, MatrixObject in, FederationMap anchorMap,
		FType materializeType, FType mapType, long rlen, long clen) {
		if (anchorMap == null || anchorMap.getSize() == 0)
			throw new DMLRuntimeException("fed_fout cannot materialize: no federated parent/worker pool (empty anchor map)");
		if (materializeType == FType.PART || materializeType == FType.OTHER
			|| mapType == FType.PART || mapType == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support ftype " + mapType);

		final int numWorkers = anchorMap.getSize();
		if (materializeType == FType.ROW && rlen < numWorkers)
			throw new DMLRuntimeException("ROW materialization requires at least one row per worker: rows="
				+ rlen + " workers=" + numWorkers);
		if (materializeType == FType.COL && clen < numWorkers)
			throw new DMLRuntimeException("COL materialization requires at least one column per worker: cols="
				+ clen + " workers=" + numWorkers);
		FType effectiveMapType = normalizeReplicatedMapType(materializeType, mapType, numWorkers);
		long outId;
		List<Pair<FederatedRange, FederatedData>> outMap = new ArrayList<>();

		if (materializeType == FType.FULL) {
			FederatedRequest fr = anchorMap.broadcast(in);
			anchorMap.execute(tid, true, fr);
			outId = fr.getID();
			for (Pair<FederatedRange, FederatedData> entry : anchorMap.getMap()) {
				FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rlen, clen});
				FederatedData data = entry.getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}
		else {
			FederatedRange[] plannedRanges = plannedMaterializationRanges(anchorMap,
				materializeType, rlen, clen);
			int[][] ix = new int[numWorkers][4];
			for (int i = 0; i < numWorkers; i++) {
				long[] begin = plannedRanges[i].getBeginDims();
				long[] end = plannedRanges[i].getEndDims();
				long rb = begin[0];
				long re = end[0];
				long cb = begin[1];
				long ce = end[1];
				if(rb > Integer.MAX_VALUE || re > Integer.MAX_VALUE
					|| cb > Integer.MAX_VALUE || ce > Integer.MAX_VALUE)
					throw new DMLRuntimeException("Federated materialization range exceeds int indexing limits");
				ix[i][0] = (int) rb;
				ix[i][1] = (int) (re - 1);
				ix[i][2] = (int) cb;
				ix[i][3] = (int) (ce - 1);
			}

			FederationMap sliceMap = anchorMap;
			if (anchorMap.getType() == FType.FULL)
				sliceMap = new FederationMap(anchorMap.getID(), anchorMap.getMap(), materializeType);

			FederatedRequest[] frSlices = sliceMap.broadcastSliced(in, false, ix);
			anchorMap.execute(tid, true, frSlices, new FederatedRequest[0]);
			if (frSlices.length != numWorkers)
				throw new DMLRuntimeException("fed_fout slice request count mismatch: expected=" + numWorkers
					+ " actual=" + frSlices.length);
			outId = frSlices[0].getID();

			for (int i = 0; i < numWorkers; i++) {
				FederatedData data = anchorMap.getMap().get(i).getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(plannedRanges[i], data));
			}
		}

		return new FederationMap(outId, outMap, effectiveMapType);
	}

}

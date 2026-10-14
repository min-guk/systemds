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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;

/**
 * Immutable ordered worker pool used by canonical planner uploads. The encoded
 * descriptor contains transport endpoints only; it is never a live data anchor.
 */
public final class CanonicalWorkerPool {
	public static final String DESCRIPTOR_PREFIX = "POOLv1:";

	private final List<InetSocketAddress> _workers;

	private CanonicalWorkerPool(List<InetSocketAddress> workers) {
		if(workers == null || workers.isEmpty())
			throw new DMLRuntimeException("Canonical worker pool must contain at least one worker.");
		List<InetSocketAddress> copy = new ArrayList<>(workers.size());
		Set<String> unique = new HashSet<>();
		for(int i = 0; i < workers.size(); i++) {
			InetSocketAddress worker = workers.get(i);
			if(worker == null || worker.getHostString() == null || worker.getHostString().isEmpty()
				|| worker.getPort() <= 0 || worker.getPort() > 65535)
				throw new DMLRuntimeException("Invalid canonical worker endpoint at index " + i + ".");
			String host = worker.getHostString();
			String key = host.toLowerCase(Locale.ROOT) + ':' + worker.getPort();
			if(!unique.add(key))
				throw new DMLRuntimeException("Canonical worker pool contains duplicate endpoint " + key + ".");
			copy.add(InetSocketAddress.createUnresolved(host, worker.getPort()));
		}
		_workers = Collections.unmodifiableList(copy);
	}

	public static CanonicalWorkerPool of(List<InetSocketAddress> workers) {
		return new CanonicalWorkerPool(workers);
	}

	public static CanonicalWorkerPool fromFederationMap(FederationMap map) {
		if(map == null || map.getMap() == null || map.getMap().isEmpty())
			throw new DMLRuntimeException("Cannot derive canonical worker pool from an empty federation map.");
		List<InetSocketAddress> workers = new ArrayList<>(map.getSize());
		for(int i = 0; i < map.getMap().size(); i++) {
			Pair<FederatedRange, FederatedData> entry = map.getMap().get(i);
			if(entry == null || entry.getValue() == null || entry.getValue().getAddress() == null)
				throw new DMLRuntimeException("Federation map has no worker endpoint at index " + i + ".");
			workers.add(entry.getValue().getAddress());
		}
		return of(workers);
	}

	public static boolean isDescriptor(String value) {
		return value != null && value.startsWith(DESCRIPTOR_PREFIX);
	}

	public static CanonicalWorkerPool parse(String descriptor) {
		if(!isDescriptor(descriptor))
			throw new DMLRuntimeException("Invalid canonical worker pool descriptor.");
		String body = descriptor.substring(DESCRIPTOR_PREFIX.length());
		if(body.isEmpty())
			throw new DMLRuntimeException("Canonical worker pool descriptor is empty.");
		List<InetSocketAddress> workers = new ArrayList<>();
		for(String endpoint : body.split(",", -1)) {
			int separator = endpoint.lastIndexOf('.');
			if(separator <= 0 || separator == endpoint.length() - 1)
				throw new DMLRuntimeException("Malformed canonical worker endpoint.");
			try {
				String host = new String(Base64.getUrlDecoder().decode(endpoint.substring(0, separator)),
					StandardCharsets.UTF_8);
				int port = Integer.parseInt(endpoint.substring(separator + 1));
				workers.add(InetSocketAddress.createUnresolved(host, port));
			}
			catch(IllegalArgumentException ex) {
				throw new DMLRuntimeException("Malformed canonical worker endpoint.", ex);
			}
		}
		return of(workers);
	}

	public String encode() {
		StringBuilder descriptor = new StringBuilder(DESCRIPTOR_PREFIX);
		for(int i = 0; i < _workers.size(); i++) {
			if(i > 0)
				descriptor.append(',');
			InetSocketAddress worker = _workers.get(i);
			descriptor.append(Base64.getUrlEncoder().withoutPadding().encodeToString(
				worker.getHostString().getBytes(StandardCharsets.UTF_8)));
			descriptor.append('.').append(worker.getPort());
		}
		return descriptor.toString();
	}

	public List<InetSocketAddress> workers() {
		return _workers;
	}

	public int size() {
		return _workers.size();
	}

	/** Translate planner REP (FULL or BROADCAST literal) and singleton FED layouts. */
	public FType runtimeType(FType targetType) {
		validateTargetType(targetType);
		if(size() == 1)
			return FType.FULL;
		return targetType == FType.FULL || targetType == FType.BROADCAST
			? FType.BROADCAST : targetType;
	}

	public FederatedRange[] canonicalRanges(FType targetType, long rows, long cols) {
		validateShape(rows, cols);
		FType runtimeType = runtimeType(targetType);
		FederatedRange[] ranges = new FederatedRange[size()];
		if(runtimeType == FType.FULL || runtimeType == FType.BROADCAST) {
			for(int i = 0; i < ranges.length; i++)
				ranges[i] = fullRange(rows, cols);
			return ranges;
		}

		long length = runtimeType == FType.ROW ? rows : cols;
		if(length < size())
			throw new DMLRuntimeException(runtimeType + " canonical partition requires length >= workers: length="
				+ length + " workers=" + size() + ".");
		long base = length / size();
		long remainder = length % size();
		long begin = 0;
		for(int i = 0; i < ranges.length; i++) {
			long end = begin + base + (i < remainder ? 1 : 0);
			ranges[i] = runtimeType == FType.ROW
				? new FederatedRange(new long[] {begin, 0}, new long[] {end, cols})
				: new FederatedRange(new long[] {0, begin}, new long[] {rows, end});
			begin = end;
		}
		return ranges;
	}

	public FederationMap newMap(DataType dataType, long id, FType targetType, long rows, long cols) {
		return newMap(dataType, id, targetType, rows, cols, false);
	}

	public FederationMap newTransportMap(DataType dataType, long id, FType targetType, long rows, long cols) {
		return newMap(dataType, id, targetType, rows, cols, true);
	}

	private FederationMap newMap(DataType dataType, long id, FType targetType, long rows, long cols,
		boolean registerWorkers) {
		if(dataType != DataType.MATRIX && dataType != DataType.FRAME)
			throw new DMLRuntimeException("Canonical upload supports matrix and frame values only.");
		FederatedRange[] ranges = canonicalRanges(targetType, rows, cols);
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>(size());
		for(int i = 0; i < size(); i++) {
			FederatedData data = registerWorkers
				? new FederatedData(dataType, _workers.get(i), "canonical-pool")
				: new FederatedData(dataType, _workers.get(i), "canonical-pool", id);
			entries.add(Pair.of(ranges[i], data));
		}
		return new FederationMap(id, entries, runtimeType(targetType));
	}

	public void validateIngress(FederationMap map, DataType dataType, FType targetType, long rows, long cols) {
		if(dataType != DataType.MATRIX && dataType != DataType.FRAME)
			throw new DMLRuntimeException("Canonical ingress supports matrix and frame values only.");
		if(map == null || map.getSize() != size() || map.getType() != runtimeType(targetType))
			throw new DMLRuntimeException("Federated value does not match the canonical worker pool/type.");
		FederatedRange[] expectedRanges = canonicalRanges(targetType, rows, cols);
		for(int i = 0; i < size(); i++) {
			Pair<FederatedRange, FederatedData> entry = map.getMap().get(i);
			if(entry == null || entry.getKey() == null || entry.getValue() == null
				|| entry.getValue().getAddress() == null
				|| entry.getValue().getDataType() != dataType
				|| !sameEndpoint(_workers.get(i), entry.getValue().getAddress())
				|| !java.util.Arrays.equals(expectedRanges[i].getBeginDims(), entry.getKey().getBeginDims())
				|| !java.util.Arrays.equals(expectedRanges[i].getEndDims(), entry.getKey().getEndDims()))
				throw new DMLRuntimeException("Federated value violates canonical ingress at worker index " + i + ".");
		}
	}

	private static FederatedRange fullRange(long rows, long cols) {
		return new FederatedRange(new long[] {0, 0}, new long[] {rows, cols});
	}

	private static boolean sameEndpoint(InetSocketAddress left, InetSocketAddress right) {
		return left.getPort() == right.getPort()
			&& left.getHostString().equalsIgnoreCase(right.getHostString());
	}

	private static void validateTargetType(FType targetType) {
		if(targetType != FType.ROW && targetType != FType.COL
			&& targetType != FType.FULL && targetType != FType.BROADCAST)
			throw new DMLRuntimeException("Unsupported canonical target type " + targetType + ".");
	}

	private static void validateShape(long rows, long cols) {
		if(rows <= 0 || cols <= 0 || rows > Integer.MAX_VALUE || cols > Integer.MAX_VALUE)
			throw new DMLRuntimeException("Canonical upload requires positive int-indexable matrix dimensions: "
				+ rows + "x" + cols + ".");
	}
}

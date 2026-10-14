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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;

/**
 * Immutable ordered worker pool and axis-length geometry used by canonical planner
 * uploads. The descriptor is transport metadata, never a live data anchor.
 */
public final class CanonicalWorkerPool {
	public static final String DESCRIPTOR_PREFIX = "POOLv1:";
	public static final String GEOMETRY_DESCRIPTOR_PREFIX = "POOLv2:";

	private final List<InetSocketAddress> _workers;
	private final Map<Long,List<Long>> _axisCuts;

	private CanonicalWorkerPool(List<InetSocketAddress> workers, Map<Long,long[]> axisCuts) {
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
		TreeMap<Long,List<Long>> cuts = new TreeMap<>();
		if(axisCuts != null) for(Map.Entry<Long,long[]> entry : axisCuts.entrySet()) {
			long length = entry.getKey() == null ? -1 : entry.getKey();
			long[] boundaries = entry.getValue();
			validateCuts(length,boundaries,copy.size());
			List<Long> immutable = new ArrayList<>(boundaries.length);
			for(long boundary : boundaries) immutable.add(boundary);
			cuts.put(length,List.copyOf(immutable));
		}
		_axisCuts = Collections.unmodifiableMap(cuts);
	}

	public static CanonicalWorkerPool of(List<InetSocketAddress> workers) {
		return new CanonicalWorkerPool(workers,Map.of());
	}

	public static CanonicalWorkerPool of(List<InetSocketAddress> workers,Map<Long,long[]> axisCuts) {
		return new CanonicalWorkerPool(workers,axisCuts);
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
		CanonicalWorkerPool endpoints=of(workers);
		if(map.getType()!=FType.ROW && map.getType()!=FType.COL) return endpoints;
		long length=0;
		long[] cuts=new long[workers.size()+1];
		long orthogonalBegin=-1,orthogonalEnd=-1;
		for(int i=0;i<map.getMap().size();i++) {
			FederatedRange range=map.getMap().get(i).getKey();
			int axis=map.getType()==FType.ROW ? 0 : 1;
			int orthogonal=1-axis;
			if(range==null) throw new DMLRuntimeException("Federation map has no range at index "+i+".");
			if(range.getBeginDims()==null || range.getEndDims()==null
				|| range.getBeginDims().length!=2 || range.getEndDims().length!=2)
				throw new DMLRuntimeException("Federation map has malformed dimensions at index "+i+".");
			if(i==0) {
				cuts[0]=range.getBeginDims()[axis];
				orthogonalBegin=range.getBeginDims()[orthogonal];
				orthogonalEnd=range.getEndDims()[orthogonal];
			}
			else if(range.getBeginDims()[axis]!=cuts[i]
				|| range.getBeginDims()[orthogonal]!=orthogonalBegin
				|| range.getEndDims()[orthogonal]!=orthogonalEnd)
				throw new DMLRuntimeException("Federation map ranges are not contiguous full-axis rectangles.");
			cuts[i+1]=range.getEndDims()[axis];
			length=Math.max(length,cuts[i+1]);
		}
		if(orthogonalBegin!=0 || orthogonalEnd<=0)
			throw new DMLRuntimeException("Federation map does not cover a positive full orthogonal axis.");
		validateCuts(length,cuts,workers.size());
		return java.util.Arrays.equals(cuts,endpoints.axisCuts(length))
			? endpoints : of(workers,Map.of(length,cuts));
	}

	public static boolean isDescriptor(String value) {
		return value != null && (value.startsWith(DESCRIPTOR_PREFIX)
			|| value.startsWith(GEOMETRY_DESCRIPTOR_PREFIX));
	}

	public static CanonicalWorkerPool parse(String descriptor) {
		if(!isDescriptor(descriptor))
			throw new DMLRuntimeException("Invalid canonical worker pool descriptor.");
		boolean geometry = descriptor.startsWith(GEOMETRY_DESCRIPTOR_PREFIX);
		String body = descriptor.substring((geometry ? GEOMETRY_DESCRIPTOR_PREFIX : DESCRIPTOR_PREFIX).length());
		String endpoints = body;
		String profiles = null;
		if(geometry) {
			int separator=body.indexOf('|');
			if(separator<0 || separator!=body.lastIndexOf('|'))
				throw new DMLRuntimeException("Malformed canonical worker pool geometry descriptor.");
			endpoints=body.substring(0,separator);
			profiles=body.substring(separator+1);
			if(profiles.isEmpty())
				throw new DMLRuntimeException("Canonical worker pool geometry profile is empty.");
		}
		if(endpoints.isEmpty())
			throw new DMLRuntimeException("Canonical worker pool descriptor is empty.");
		List<InetSocketAddress> workers = new ArrayList<>();
		for(String endpoint : endpoints.split(",", -1)) {
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
		if(!geometry) return of(workers);
		Map<Long,long[]> cuts=new TreeMap<>();
		try {
			for(String profile : profiles.split(",",-1)) {
				int separator=profile.indexOf(':');
				if(separator<=0 || separator!=profile.lastIndexOf(':'))
					throw new IllegalArgumentException();
				long length=Long.parseLong(profile.substring(0,separator));
				String[] encoded=profile.substring(separator+1).split("\\.",-1);
				long[] boundaries=new long[encoded.length];
				for(int i=0;i<encoded.length;i++) boundaries[i]=Long.parseLong(encoded[i]);
				if(cuts.put(length,boundaries)!=null) throw new IllegalArgumentException();
			}
		}
		catch(IllegalArgumentException ex) {
			throw new DMLRuntimeException("Malformed canonical worker pool geometry profile.",ex);
		}
		return of(workers,cuts);
	}

	public String encode() {
		StringBuilder descriptor = new StringBuilder(_axisCuts.isEmpty()
			? DESCRIPTOR_PREFIX : GEOMETRY_DESCRIPTOR_PREFIX);
		for(int i = 0; i < _workers.size(); i++) {
			if(i > 0)
				descriptor.append(',');
			InetSocketAddress worker = _workers.get(i);
			descriptor.append(Base64.getUrlEncoder().withoutPadding().encodeToString(
				worker.getHostString().getBytes(StandardCharsets.UTF_8)));
			descriptor.append('.').append(worker.getPort());
		}
		if(!_axisCuts.isEmpty()) {
			descriptor.append('|');
			int profile=0;
			for(Map.Entry<Long,List<Long>> entry : _axisCuts.entrySet()) {
				if(profile++>0) descriptor.append(',');
				descriptor.append(entry.getKey()).append(':');
				for(int i=0;i<entry.getValue().size();i++) {
					if(i>0) descriptor.append('.');
					descriptor.append(entry.getValue().get(i));
				}
			}
		}
		return descriptor.toString();
	}

	public boolean hasAxisProfile(long length) { return _axisCuts.containsKey(length); }
	public Map<Long,long[]> axisProfiles() {
		Map<Long,long[]> result=new TreeMap<>();
		for(Long length : _axisCuts.keySet()) result.put(length,axisCuts(length));
		return Collections.unmodifiableMap(result);
	}

	/** Returns a defensive copy of the immutable effective cuts for either axis. */
	public long[] axisCuts(long length) {
		if(length<=0) throw new DMLRuntimeException("Canonical axis length must be positive.");
		List<Long> configured=_axisCuts.get(length);
		if(configured!=null) {
			long[] result=new long[configured.size()];
			for(int i=0;i<result.length;i++) result[i]=configured.get(i);
			return result;
		}
		if(length<size()) throw new DMLRuntimeException("Canonical partition requires length >= workers: length="
			+length+" workers="+size()+".");
		long[] result=new long[size()+1];
		for(int i=0;i<=size();i++) result[i]=defaultBoundary(length,size(),i);
		return result;
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
		long[] cuts=axisCuts(length);
		for(int i = 0; i < ranges.length; i++) {
			ranges[i] = runtimeType == FType.ROW
				? new FederatedRange(new long[] {cuts[i], 0}, new long[] {cuts[i+1], cols})
				: new FederatedRange(new long[] {0, cuts[i]}, new long[] {rows, cuts[i+1]});
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

	private static long defaultBoundary(long length,int workers,int boundary) {
		long base=length/workers, remainder=length%workers;
		return boundary*base+Math.min(boundary,remainder);
	}

	private static void validateCuts(long length,long[] cuts,int workers) {
		if(length<=0 || cuts==null || cuts.length!=workers+1 || cuts[0]!=0 || cuts[cuts.length-1]!=length)
			throw new DMLRuntimeException("Invalid canonical axis cuts for length "+length+".");
		for(int i=1;i<cuts.length;i++) if(cuts[i]<=cuts[i-1])
			throw new DMLRuntimeException("Canonical axis cuts must be strictly increasing for length "+length+".");
	}
}

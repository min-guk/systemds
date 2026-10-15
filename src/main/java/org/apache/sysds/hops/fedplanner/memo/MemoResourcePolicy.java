/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Byte admission for optional Memo workspaces.  An estimate is the projected
 * simultaneous additional memory owned by a kernel, rather than an allocation
 * delta.  Rejection asks the caller to use another exact representation; it
 * never changes the optimization universe or proves resource exhaustion.
 */
public final class MemoResourcePolicy {
	private static final Path CGROUP_CURRENT=Path.of("/sys/fs/cgroup/memory.current");
	private static final Path CGROUP_MAX=Path.of("/sys/fs/cgroup/memory.max");
	private static final long DEFAULT_MIN_RESERVE=16L*1024*1024;
	private static final long DEFAULT_MAX_RESERVE=256L*1024*1024;

	public record Stats(long admissions,long rejections,long peakEstimatedBytes,
		long peakAdmittedBytes,long peakRejectedBytes) { }

	private final long byteCeiling;
	private final long reserveBytes;
	private final MemoResourcePolicy parent;
	private final long residentBase;
	private long admissions,rejections,peakEstimatedBytes,peakAdmittedBytes,peakRejectedBytes;

	/** Uses current JVM/cgroup headroom and keeps a bounded progress/reconstruction reserve. */
	public MemoResourcePolicy() {
		this(Long.MAX_VALUE,defaultReserve());
	}

	/** Deterministic finite ceiling for tests/controllers; an unbounded ceiling uses the default reserve. */
	public MemoResourcePolicy(long byteCeiling) {
		this(byteCeiling,byteCeiling==Long.MAX_VALUE ? defaultReserve() : 0);
	}

	public MemoResourcePolicy(long byteCeiling,long reserveBytes) {
		this(byteCeiling,reserveBytes,null,0);
	}

	private MemoResourcePolicy(long byteCeiling,long reserveBytes,MemoResourcePolicy parent,long residentBase) {
		if(byteCeiling<0 || reserveBytes<0)
			throw new IllegalArgumentException("Memory ceiling and reserve must be nonnegative");
		this.byteCeiling=byteCeiling;
		this.reserveBytes=reserveBytes;
		this.parent=parent;
		this.residentBase=residentBase;
	}

	public static MemoResourcePolicy defaults() { return new MemoResourcePolicy(); }

	/**
	 * Admits one projected peak.  The call is observational except for statistics;
	 * callers retain ownership of allocation and publication.
	 */
	public synchronized boolean admit(long estimatedAdditionalBytes) {
		return admit(0,estimatedAdditionalBytes);
	}

	/**
	 * Admits an additional allocation while separately accounting for workspace
	 * already owned by the solver.  The configured ceiling applies to their sum;
	 * current JVM/cgroup headroom applies only to the new allocation.
	 */
	public synchronized boolean admit(long residentBytes,long additionalBytes) {
		if(residentBytes<0 || additionalBytes<0)
			throw new IllegalArgumentException("Negative memory estimate");
		if(parent!=null)
			return parent.admit(checkedAdd(residentBase,residentBytes),additionalBytes);
		long projected=checkedAdd(residentBytes,additionalBytes);
		peakEstimatedBytes=Math.max(peakEstimatedBytes,projected);
		long configuredAdditional=saturatingSubtract(byteCeiling,residentBytes);
		// A configured-ceiling rejection needs no filesystem accounting reads.
		long physicalAdditional=projected>byteCeiling ? -1 : saturatingSubtract(physicalHeadroom(),reserveBytes);
		boolean admitted=projected<=byteCeiling && additionalBytes<=Math.min(configuredAdditional,physicalAdditional);
		if(Boolean.getBoolean("sysds.fedplanner.memo.dual.diagnostics"))
			System.out.println("[MemoAdmission] {\"residentBytes\":"+residentBytes+",\"additionalBytes\":"+additionalBytes
				+",\"ceilingBytes\":"+byteCeiling+",\"physicalAdditionalBytes\":"+physicalAdditional
				+",\"reserveBytes\":"+reserveBytes+",\"admitted\":"+admitted+",\"reason\":\""
				+(admitted ? "admitted" : projected>byteCeiling ? "configured-ceiling" : "physical-headroom")+"\"}");
		if(admitted) {
			admissions++;
			peakAdmittedBytes=Math.max(peakAdmittedBytes,projected);
			return true;
		}
		rejections++;
		peakRejectedBytes=Math.max(peakRejectedBytes,projected);
		return false;
	}

	/** Current additional bytes available after the configured progress reserve. */
	public synchronized long availableBytes() {
		if(parent!=null) return parent.availableBytes(residentBase);
		return availableBytes(0);
	}

	private synchronized long availableBytes(long residentBytes) {
		if(parent!=null) return parent.availableBytes(checkedAdd(residentBase,residentBytes));
		long configured=saturatingSubtract(byteCeiling,residentBytes);
		long physical=saturatingSubtract(physicalHeadroom(),reserveBytes);
		return Math.min(configured,physical);
	}

	public synchronized long peakEstimatedBytes() {
		return parent==null ? peakEstimatedBytes : parent.peakEstimatedBytes();
	}

	public synchronized Stats stats() {
		if(parent!=null) return parent.stats();
		return new Stats(admissions,rejections,peakEstimatedBytes,peakAdmittedBytes,peakRejectedBytes);
	}

	/** A shared-statistics view whose admissions include already retained controller state. */
	public MemoResourcePolicy withResidentBytes(long residentBytes) {
		if(residentBytes<0) throw new IllegalArgumentException("Negative resident memory estimate");
		MemoResourcePolicy root=parent==null ? this : parent;
		long base=parent==null ? residentBytes : checkedAdd(residentBase,residentBytes);
		return new MemoResourcePolicy(byteCeiling,reserveBytes,root,base);
	}

	public long byteCeiling() { return byteCeiling; }
	public long reserveBytes() { return reserveBytes; }

	/** Checked helpers keep overflow from turning an impossible workspace into a small estimate. */
	public static long checkedAdd(long left,long right) {
		if(left<0 || right<0) throw new IllegalArgumentException("Negative byte estimate");
		return left>Long.MAX_VALUE-right ? Long.MAX_VALUE : left+right;
	}

	public static long checkedMultiply(long count,long bytesPerItem) {
		if(count<0 || bytesPerItem<0) throw new IllegalArgumentException("Negative byte estimate");
		return count!=0 && bytesPerItem>Long.MAX_VALUE/count ? Long.MAX_VALUE : count*bytesPerItem;
	}

	private static long defaultReserve() {
		long max=Runtime.getRuntime().maxMemory();
		return Math.max(DEFAULT_MIN_RESERVE,Math.min(DEFAULT_MAX_RESERVE,max/8));
	}

	private static long cgroupHeadroom() {
		try {
			String maximum=Files.readString(CGROUP_MAX).trim();
			if("max".equals(maximum)) return Long.MAX_VALUE;
			long limit=Long.parseLong(maximum);
			long current=Long.parseLong(Files.readString(CGROUP_CURRENT).trim());
			return saturatingSubtract(limit,current);
		}
		catch(IOException | NumberFormatException ignored) {
			// Runtime.maxMemory remains a conservative JVM allocation boundary when
			// cgroup v2 accounting is absent or inaccessible.
			return Long.MAX_VALUE;
		}
	}

	private static long physicalHeadroom() {
		Runtime runtime=Runtime.getRuntime();
		long used=saturatingSubtract(runtime.totalMemory(),runtime.freeMemory());
		return Math.min(saturatingSubtract(runtime.maxMemory(),used),cgroupHeadroom());
	}

	private static long saturatingSubtract(long left,long right) {
		return left<=right ? 0 : left-right;
	}
}

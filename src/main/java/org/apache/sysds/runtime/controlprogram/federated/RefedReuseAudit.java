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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.sysds.runtime.DMLRuntimeException;

/**
 * Opt-in observations for REFED materialization reuse. This class deliberately stores only
 * detached scalar metadata: it must never extend the lifetime of a source or federation map.
 */
public final class RefedReuseAudit {
	public static final String PROPERTY = "sysds.fed.refed.reuse.audit";
	public static final String SOURCE_MUTATION = "SOURCE_MUTATION";
	public static final String MAPPING_CHANGE = "MAPPING_CHANGE";
	public static final String SOURCE_CLEAR = "SOURCE_CLEAR";
	public static final String SOURCE_REMOVAL = "SOURCE_REMOVAL";
	public static final String EXPLICIT_CLEAR = "EXPLICIT_CLEAR";
	public static final String LEGACY_EVICTION = "LEGACY_EVICTION";
	public static final String ALIAS_FAILURE = "ALIAS_FAILURE";
	public static final String WORKER_RESET = "WORKER_RESET";
	public static final String CREATION_FAILURE = "CREATION_FAILURE";
	private static final String EVENT_PREFIX = "REFED_REUSE_AUDIT ";
	private static final int MAX_EVENTS = Math.max(1,
		Integer.getInteger("sysds.fed.refed.reuse.audit.events", 4096));
	private static final Logger LOG = Logger.getLogger(RefedReuseAudit.class);
	private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
	private static final ArrayDeque<SupplyEvent> SUPPLY_EVENTS = new ArrayDeque<>();
	private static final MutableModeCounters PLANNED = new MutableModeCounters("PLANNED");
	private static final MutableModeCounters LEGACY = new MutableModeCounters("LEGACY");
	private static final MutableModeCounters SINGLE_USE = new MutableModeCounters("SINGLE_USE");

	private static long _creationAttempts;
	private static long _creationSuccesses;
	private static long _creationFailures;
	private static long _hits;
	private static long _aliases;
	private static long _currentCanonicalCount;
	private static long _peakCanonicalCount;
	private static long _currentEstimatedBytes;
	private static long _peakEstimatedBytes;
	private static long _droppedLifecycleEvents;
	private static long _droppedSupplyEvents;
	private static long _loggingFailures;

	private RefedReuseAudit() {
		// utility class
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	public static synchronized void reset() {
		if(_currentCanonicalCount != 0)
			throw new IllegalStateException("Cannot reset REFED reuse audit while canonical copies are live");
		_creationAttempts = 0;
		_creationSuccesses = 0;
		_creationFailures = 0;
		_hits = 0;
		_aliases = 0;
		_currentCanonicalCount = 0;
		_peakCanonicalCount = 0;
		_currentEstimatedBytes = 0;
		_peakEstimatedBytes = 0;
		_droppedLifecycleEvents = 0;
		_droppedSupplyEvents = 0;
		_loggingFailures = 0;
		PLANNED.reset();
		LEGACY.reset();
		SINGLE_USE.reset();
		EVENTS.clear();
		SUPPLY_EVENTS.clear();
	}

	public static synchronized Snapshot snapshot() {
		return new Snapshot(isEnabled(), _creationAttempts, _creationSuccesses, _creationFailures,
			_hits, _aliases, _currentCanonicalCount, _peakCanonicalCount,
			_currentEstimatedBytes, _peakEstimatedBytes, MAX_EVENTS,
			_droppedLifecycleEvents + _droppedSupplyEvents, _droppedLifecycleEvents, _droppedSupplyEvents,
			_loggingFailures,
			List.of(PLANNED.snapshot(), LEGACY.snapshot(), SINGLE_USE.snapshot()),
			List.copyOf(new ArrayList<>(EVENTS)), List.copyOf(new ArrayList<>(SUPPLY_EVENTS)));
	}

	static Context context(String mode, long sourceUniqueId, long sourceVersion, String group,
		String layout, String outType) {
		return new Context(mode, sourceUniqueId, sourceVersion, digest(group), digest(layout), outType);
	}

	static void creationAttempt(Context context) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_creationAttempts++;
			mode(context)._creationAttempts++;
			record("CREATION_ATTEMPT", context, -1, 0, null, null);
		}
	}

	static void creationSuccess(Context context, long canonicalRemoteId, long estimatedBytes) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_creationSuccesses++;
			mode(context)._creationSuccesses++;
			record("CREATION_SUCCESS", context, canonicalRemoteId, estimatedBytes, null, null);
		}
	}

	static void creationFailure(Context context, long canonicalRemoteId, String reason) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_creationFailures++;
			mode(context)._creationFailures++;
			record("CREATION_FAILURE", context, canonicalRemoteId, 0, reason, null);
		}
	}

	static void hit(Context context, long canonicalRemoteId, long estimatedBytes) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_hits++;
			mode(context)._hits++;
			record("HIT", context, canonicalRemoteId, estimatedBytes, null, null);
		}
	}

	static void alias(Context context, long canonicalRemoteId) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_aliases++;
			mode(context)._aliases++;
			record("ALIAS", context, canonicalRemoteId, 0, null, null);
		}
	}

	static void aliasFailure(Context context, long canonicalRemoteId) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			record("ALIAS_FAILURE", context, canonicalRemoteId, 0, ALIAS_FAILURE, null);
		}
	}

	static void retained(Context context, long canonicalRemoteId, long estimatedBytes) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_currentCanonicalCount = saturatedAdd(_currentCanonicalCount, 1);
			_currentEstimatedBytes = saturatedAdd(_currentEstimatedBytes, estimatedBytes);
			MutableModeCounters counters = mode(context);
			counters._currentCanonicalCount = saturatedAdd(counters._currentCanonicalCount, 1);
			counters._currentEstimatedBytes = saturatedAdd(counters._currentEstimatedBytes, estimatedBytes);
			_peakCanonicalCount = Math.max(_peakCanonicalCount, _currentCanonicalCount);
			_peakEstimatedBytes = Math.max(_peakEstimatedBytes, _currentEstimatedBytes);
			counters._peakCanonicalCount = Math.max(counters._peakCanonicalCount, counters._currentCanonicalCount);
			counters._peakEstimatedBytes = Math.max(counters._peakEstimatedBytes, counters._currentEstimatedBytes);
			record("RETAINED", context, canonicalRemoteId, estimatedBytes, null, null);
		}
	}

	static void retirement(Context context, long canonicalRemoteId, long estimatedBytes,
		String reason) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			_currentCanonicalCount = saturatedSubtract(_currentCanonicalCount, 1);
			_currentEstimatedBytes = saturatedSubtract(_currentEstimatedBytes, estimatedBytes);
			MutableModeCounters counters = mode(context);
			counters._currentCanonicalCount = saturatedSubtract(counters._currentCanonicalCount, 1);
			counters._currentEstimatedBytes = saturatedSubtract(counters._currentEstimatedBytes, estimatedBytes);
			record("RETIREMENT", context, canonicalRemoteId, estimatedBytes, reason, null);
		}
	}

	static void cleanup(Context context, long canonicalRemoteId, long estimatedBytes,
		String reason, Boolean success) {
		if(!isEnabled() || context == null)
			return;
		synchronized(RefedReuseAudit.class) {
			record("CLEANUP", context, canonicalRemoteId, estimatedBytes, reason, success);
		}
	}

	/** Record the production supply action that published a REFED map. */
	public static void recordSupply(String actionKey, String inputName, long sourceUniqueId,
		long sourceVersion, String group, String layout, boolean staged, long publishedMapId, boolean created) {
		if(!isEnabled())
			return;
		synchronized(RefedReuseAudit.class) {
			SupplyEvent value = new SupplyEvent(System.nanoTime(), digest(actionKey), digest(inputName),
				sourceUniqueId, sourceVersion, digest(group), digest(layout), staged, publishedMapId, created);
			if(SUPPLY_EVENTS.size() == MAX_EVENTS) {
				_droppedSupplyEvents++;
				return;
			}
			SUPPLY_EVENTS.addLast(value);
			safeLog(value.toLogLine());
		}
	}

	private static void record(String event, Context context, long canonicalRemoteId, long estimatedBytes,
		String reason, Boolean cleanupSuccess) {
		Event value = new Event(System.nanoTime(), event, context.mode(), context.sourceUniqueId(),
			context.sourceVersion(), context.groupDigest(), context.layoutDigest(), context.outType(),
			canonicalRemoteId, estimatedBytes, reason, cleanupSuccess);
		if(EVENTS.size() == MAX_EVENTS) {
			_droppedLifecycleEvents++;
			return;
		}
		EVENTS.addLast(value);
		safeLog(value.toLogLine());
	}

	private static void safeLog(String event) {
		try {
			LOG.info(EVENT_PREFIX + event);
		}
		catch(RuntimeException ex) {
			_loggingFailures++;
		}
	}

	/** Return the same detached digest used by audit events, for joining planner-side evidence. */
	public static String digest(String value) {
		if(value == null)
			return "null";
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(16);
			for(int i = 0; i < 8; i++)
				result.append(String.format("%02x", bytes[i]));
			return result.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new DMLRuntimeException("SHA-256 is unavailable for REFED audit", ex);
		}
	}

	private static long saturatedAdd(long left, long right) {
		if(left == Long.MAX_VALUE || right == Long.MAX_VALUE || right > Long.MAX_VALUE - left)
			return Long.MAX_VALUE;
		return left + right;
	}

	private static long saturatedSubtract(long left, long right) {
		if(right == Long.MAX_VALUE || right >= left)
			return 0;
		return left - right;
	}

	private static MutableModeCounters mode(Context context) {
		if("PLANNED".equals(context.mode()))
			return PLANNED;
		if("SINGLE_USE".equals(context.mode()))
			return SINGLE_USE;
		return LEGACY;
	}

	static record Context(String mode, long sourceUniqueId, long sourceVersion,
		String groupDigest, String layoutDigest, String outType) { }

	/**
	 * Detached audit snapshot. Alias counts are publications of names for a canonical value,
	 * not independent payload copies; event timestamps do not establish a consumer's last use.
	 */
	public record Snapshot(boolean enabled, long creationAttempts, long creationSuccesses,
		long creationFailures, long hits, long aliases, long currentCanonicalCount,
		long peakCanonicalCount, long currentEstimatedBytes, long peakEstimatedBytes,
		int eventCapacity, long droppedEvents, long droppedLifecycleEvents, long droppedSupplyEvents,
		long loggingFailures, List<ModeCounters> modes, List<Event> events, List<SupplyEvent> supplyEvents) { }

	public record ModeCounters(String mode, long creationAttempts, long creationSuccesses,
		long creationFailures, long hits, long aliases, long currentCanonicalCount,
		long peakCanonicalCount, long currentEstimatedBytes, long peakEstimatedBytes) { }

	public record Event(long nanoTime, String event, String mode, long sourceUniqueId,
		long sourceVersion, String groupDigest, String layoutDigest, String outType,
		long canonicalRemoteId, long estimatedBytes, String reason, Boolean cleanupSuccess) {
		private String toLogLine() {
			return "nanoTime=" + nanoTime + " event=" + event + " mode=" + mode
				+ " sourceUniqueId=" + sourceUniqueId + " sourceVersion=" + sourceVersion
				+ " groupDigest=" + groupDigest + " layoutDigest=" + layoutDigest
				+ " outType=" + outType + " canonicalRemoteId=" + canonicalRemoteId
				+ " estimatedBytes=" + estimatedBytes + " reason=" + reason
				+ " cleanupSuccess=" + cleanupSuccess;
		}
	}

	public record SupplyEvent(long nanoTime, String actionKeyDigest, String inputNameDigest,
		long sourceUniqueId, long sourceVersion, String groupDigest, String layoutDigest,
		boolean staged, long publishedMapId, boolean created) {
		private String toLogLine() {
			return "nanoTime=" + nanoTime + " event=SUPPLY actionKeyDigest=" + actionKeyDigest
				+ " inputNameDigest=" + inputNameDigest + " sourceUniqueId=" + sourceUniqueId
				+ " sourceVersion=" + sourceVersion + " groupDigest=" + groupDigest
				+ " layoutDigest=" + layoutDigest + " staged=" + staged
				+ " publishedMapId=" + publishedMapId + " created=" + created;
		}
	}

	private static final class MutableModeCounters {
		private final String _mode;
		private long _creationAttempts;
		private long _creationSuccesses;
		private long _creationFailures;
		private long _hits;
		private long _aliases;
		private long _currentCanonicalCount;
		private long _peakCanonicalCount;
		private long _currentEstimatedBytes;
		private long _peakEstimatedBytes;

		private MutableModeCounters(String mode) {
			_mode = mode;
		}

		private void reset() {
			_creationAttempts = _creationSuccesses = _creationFailures = _hits = _aliases = 0;
			_currentCanonicalCount = _peakCanonicalCount = 0;
			_currentEstimatedBytes = _peakEstimatedBytes = 0;
		}

		private ModeCounters snapshot() {
			return new ModeCounters(_mode, _creationAttempts, _creationSuccesses, _creationFailures,
				_hits, _aliases, _currentCanonicalCount, _peakCanonicalCount,
				_currentEstimatedBytes, _peakEstimatedBytes);
		}
	}
}

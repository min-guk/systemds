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

package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.sysds.conf.FederatedPlannerConfiguration;
import org.apache.sysds.hops.Hop;

/**
 * Targeted planner tracing for investigating hop-level execution/placement decisions.
 * Enabled by environment variables:
 *   - SYSDS_FED_PLANNER_TRACE=1
 *   - SYSDS_FED_PLANNER_TRACE_HOPS=12,34 (optional)
 *   - SYSDS_FED_PLANNER_TRACE_MAX_EDGES=8 (optional)
 *   - SYSDS_FED_PLANNER_TRACE_MAX_RECORDS_PER_STAGE=4096 (optional)
 *
 * Matching system properties are also supported:
 *   - -Dsysds.fedplanner.trace=true
 *   - -Dsysds.fedplanner.trace.hops=12,34
 *   - -Dsysds.fedplanner.trace.max.edges=8
 *   - -Dsysds.fedplanner.trace.max.records.per.stage=4096
 */
public final class FederatedPlannerTrace {
	private static final String ENV_TRACE = "SYSDS_FED_PLANNER_TRACE";
	private static final String ENV_TRACE_HOPS = "SYSDS_FED_PLANNER_TRACE_HOPS";
	private static final String ENV_TRACE_MAX_EDGES = "SYSDS_FED_PLANNER_TRACE_MAX_EDGES";
	private static final String ENV_TRACE_MAX_RECORDS_PER_STAGE =
		"SYSDS_FED_PLANNER_TRACE_MAX_RECORDS_PER_STAGE";

	private static final String PROP_TRACE = "sysds.fedplanner.trace";
	private static final String PROP_TRACE_HOPS = "sysds.fedplanner.trace.hops";
	private static final String PROP_TRACE_MAX_EDGES = "sysds.fedplanner.trace.max.edges";
	private static final String PROP_TRACE_MAX_RECORDS_PER_STAGE =
		"sysds.fedplanner.trace.max.records.per.stage";

	private static final boolean ENABLED = parseBoolean(resolveConfig(PROP_TRACE, ENV_TRACE), false);
	private static final Set<Long> TRACE_HOP_IDS = parseHopIds(resolveConfig(PROP_TRACE_HOPS, ENV_TRACE_HOPS));
	private static final int TRACE_MAX_EDGES = parsePositiveInt(resolveConfig(PROP_TRACE_MAX_EDGES, ENV_TRACE_MAX_EDGES), 8);
	private static final int TRACE_MAX_RECORDS_PER_STAGE = parsePositiveInt(
		resolveConfig(PROP_TRACE_MAX_RECORDS_PER_STAGE, ENV_TRACE_MAX_RECORDS_PER_STAGE), 4096);
	private static final Map<String, StageRecordBudget> STAGE_RECORD_BUDGETS = new ConcurrentHashMap<>();

	private FederatedPlannerTrace() {
		// utility class
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	public static boolean shouldTrace(Hop hop) {
		if (!ENABLED || hop == null)
			return false;
		return TRACE_HOP_IDS.isEmpty() || TRACE_HOP_IDS.contains(hop.getHopID());
	}

	/** True when the operator intentionally restricted hop-level trace output. */
	public static boolean hasExplicitHopFilter() {
		return !TRACE_HOP_IDS.isEmpty();
	}

	public static int getMaxEdgeLogsPerHop() {
		return TRACE_MAX_EDGES;
	}

	/** Reset bounded detail counters before one top-level planner invocation. */
	public static void beginInvocation() {
		if (ENABLED)
			STAGE_RECORD_BUDGETS.clear();
	}

	/** Emit a deterministic receipt for every stage whose detail records were suppressed. */
	public static void completeInvocation() {
		if (!ENABLED)
			return;
		for (Map.Entry<String, StageRecordBudget> entry :
			new TreeMap<>(STAGE_RECORD_BUDGETS).entrySet()) {
			StageRecordBudget budget = entry.getValue();
			long omitted = budget.getOmitted();
			if (omitted > 0) {
				logGlobal("Trace-SuppressionSummary", "stage=" + entry.getKey()
					+ " emitted=" + budget.getEmitted()
					+ " omitted=" + omitted
					+ " maxRecordsPerStage=" + TRACE_MAX_RECORDS_PER_STAGE);
			}
		}
	}

	public static void log(Hop hop, String stage, String message) {
		if (!shouldTrace(hop) || !tryAcquireStageRecord(stage))
			return;
		printHopRecord(hop, stage, message);
	}

	/**
	 * Lazily construct a detail message only if its hop matches and its stage still
	 * has record budget. This keeps audit tracing observational: suppressed records
	 * do not pay formatting/allocation costs inside planner hot loops.
	 */
	public static void logLazy(Hop hop, String stage, Supplier<String> messageSupplier) {
		if (!shouldTrace(hop) || !tryAcquireStageRecord(stage))
			return;
		printHopRecord(hop, stage, messageSupplier.get());
	}

	public static void logGlobal(String stage, String message) {
		if (!ENABLED)
			return;
		System.out.println("[PlannerTrace][" + stage + "] " + message);
	}

	private static boolean tryAcquireStageRecord(String stage) {
		String stageKey = stage != null ? stage : "Unknown";
		return STAGE_RECORD_BUDGETS.computeIfAbsent(stageKey,
			ignored -> new StageRecordBudget(TRACE_MAX_RECORDS_PER_STAGE)).tryAcquire();
	}

	private static void printHopRecord(Hop hop, String stage, String message) {
		System.out.println("[PlannerTrace][" + stage + "] hop=" + hop.getHopID()
			+ " (" + hop.getOpString() + ") " + message);
	}

	static final class StageRecordBudget {
		private final long limit;
		private final AtomicLong emitted = new AtomicLong();
		private final AtomicLong omitted = new AtomicLong();

		StageRecordBudget(long limit) {
			this.limit = Math.max(1L, limit);
		}

		boolean tryAcquire() {
			while (true) {
				long current = emitted.get();
				if (current >= limit) {
					omitted.incrementAndGet();
					return false;
				}
				if (emitted.compareAndSet(current, current + 1L))
					return true;
			}
		}

		long getEmitted() {
			return emitted.get();
		}

		long getOmitted() {
			return omitted.get();
		}
	}

	private static String resolveConfig(String propKey, String envKey) {
		return FederatedPlannerConfiguration.captureTrimmedPropertyOrEnvironment(propKey, envKey);
	}

	private static boolean parseBoolean(String raw, boolean defaultValue) {
		if (raw == null)
			return defaultValue;
		String lower = raw.trim().toLowerCase();
		switch (lower) {
			case "1":
			case "true":
			case "yes":
			case "on":
				return true;
			case "0":
			case "false":
			case "no":
			case "off":
				return false;
			default:
				return defaultValue;
		}
	}

	private static int parsePositiveInt(String raw, int defaultValue) {
		if (raw == null)
			return defaultValue;
		try {
			int parsed = Integer.parseInt(raw.trim());
			return parsed > 0 ? parsed : defaultValue;
		}
		catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static Set<Long> parseHopIds(String raw) {
		if (raw == null)
			return Collections.emptySet();
		Set<Long> hopIds = new LinkedHashSet<>();
		for (String token : Arrays.asList(raw.split("[,\\s]+"))) {
			String trimmed = token.trim();
			if (trimmed.isEmpty())
				continue;
			try {
				hopIds.add(Long.parseLong(trimmed));
			}
			catch (NumberFormatException ignored) {
				// ignore malformed token
			}
		}
		return hopIds;
	}
}

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
import java.util.Set;
import org.apache.sysds.hops.Hop;

/**
 * Targeted planner tracing for investigating hop-level execution/placement decisions.
 * Enabled by environment variables:
 *   - SYSDS_FED_PLANNER_TRACE=1
 *   - SYSDS_FED_PLANNER_TRACE_HOPS=12,34 (optional)
 *   - SYSDS_FED_PLANNER_TRACE_MAX_EDGES=8 (optional)
 *
 * Matching system properties are also supported:
 *   - -Dsysds.fedplanner.trace=true
 *   - -Dsysds.fedplanner.trace.hops=12,34
 *   - -Dsysds.fedplanner.trace.max.edges=8
 */
public final class FederatedPlannerTrace {
	private static final String ENV_TRACE = "SYSDS_FED_PLANNER_TRACE";
	private static final String ENV_TRACE_HOPS = "SYSDS_FED_PLANNER_TRACE_HOPS";
	private static final String ENV_TRACE_MAX_EDGES = "SYSDS_FED_PLANNER_TRACE_MAX_EDGES";

	private static final String PROP_TRACE = "sysds.fedplanner.trace";
	private static final String PROP_TRACE_HOPS = "sysds.fedplanner.trace.hops";
	private static final String PROP_TRACE_MAX_EDGES = "sysds.fedplanner.trace.max.edges";

	private static final boolean ENABLED = parseBoolean(resolveConfig(PROP_TRACE, ENV_TRACE), false);
	private static final Set<Long> TRACE_HOP_IDS = parseHopIds(resolveConfig(PROP_TRACE_HOPS, ENV_TRACE_HOPS));
	private static final int TRACE_MAX_EDGES = parsePositiveInt(resolveConfig(PROP_TRACE_MAX_EDGES, ENV_TRACE_MAX_EDGES), 8);

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

	public static int getMaxEdgeLogsPerHop() {
		return TRACE_MAX_EDGES;
	}

	public static void log(Hop hop, String stage, String message) {
		if (!shouldTrace(hop))
			return;
		System.out.println("[PlannerTrace][" + stage + "] hop=" + hop.getHopID()
				+ " (" + hop.getOpString() + ") " + message);
	}

	public static void logGlobal(String stage, String message) {
		if (!ENABLED)
			return;
		System.out.println("[PlannerTrace][" + stage + "] " + message);
	}

	private static String resolveConfig(String propKey, String envKey) {
		String propValue = trimToNull(System.getProperty(propKey));
		if (propValue != null)
			return propValue;
		return trimToNull(System.getenv(envKey));
	}

	private static String trimToNull(String value) {
		if (value == null)
			return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
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

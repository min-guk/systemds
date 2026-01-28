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

package org.apache.sysds.lops.compile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FederatedRefedRegistry {
	private static final Map<Long, Map<Long, Long>> REFED_ANCHORS = new ConcurrentHashMap<>();

	private FederatedRefedRegistry() {
	}

	public static void clear() {
		REFED_ANCHORS.clear();
	}

	public static void register(long sbId, long hopId, long anchorHopId) {
		REFED_ANCHORS.computeIfAbsent(sbId, k -> new ConcurrentHashMap<>())
			.put(hopId, anchorHopId);
	}

	public static void remove(long sbId, long hopId) {
		Map<Long, Long> anchors = REFED_ANCHORS.get(sbId);
		if (anchors == null)
			return;
		anchors.remove(hopId);
		if (anchors.isEmpty())
			REFED_ANCHORS.remove(sbId);
	}

	public static Long getAnchorHopId(long hopId) {
		for (Map<Long, Long> anchors : REFED_ANCHORS.values()) {
			Long anchor = anchors.get(hopId);
			if (anchor != null)
				return anchor;
		}
		return null;
	}

	public static boolean isEmpty() {
		return REFED_ANCHORS.isEmpty();
	}

	public static Map<Long, Long> snapshot(long sbId) {
		Map<Long, Long> anchors = REFED_ANCHORS.get(sbId);
		if (anchors == null || anchors.isEmpty())
			return Collections.emptyMap();
		return Collections.unmodifiableMap(new HashMap<>(anchors));
	}
}

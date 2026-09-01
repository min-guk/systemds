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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Map;
import java.util.Objects;

import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;

/**
 * Durable, immutable authority selected by the common planner transaction.
 *
 * <p>The three Lop lowering registries are intentionally mutable scratch state:
 * statement blocks clear and rebuild them while compiling. Runtime recompilation
 * must not infer a replacement action from the current DAG after that scratch
 * state has been consumed. This registry therefore retains the exact committed
 * REFED/FOUT/LOCAL actions, including their action keys and consumer positions,
 * until the next complete planner invocation.</p>
 */
public final class PlannerRuntimeActionRegistry {
	public record Snapshot(FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout,
		FederatedLocalMaterializeRegistry.Snapshot local) {
		public Snapshot {
			Objects.requireNonNull(refed, "refed");
			Objects.requireNonNull(fout, "fout");
			Objects.requireNonNull(local, "local");
		}
	}

	private static volatile Snapshot CURRENT = emptySnapshot();

	private PlannerRuntimeActionRegistry() { }

	public static synchronized void commitCurrentLoweringAuthorities() {
		CURRENT = new Snapshot(FederatedRefedRegistry.snapshotAll(),
			FederatedFoutMaterializeRegistry.snapshotAll(),
			FederatedLocalMaterializeRegistry.snapshotAll());
		if(PlannerRuntimePlacementAudit.isEnabled())
			System.out.println("[PlannerRuntimeAudit][AuthorityCommit] refedScopes="
				+ scopeSummary(CURRENT.refed().scopes()) + " foutScopes="
				+ scopeSummary(CURRENT.fout().scopes()) + " localScopes="
				+ scopeSummary(CURRENT.local().scopes()));
	}

	public static Snapshot snapshot() {
		return CURRENT;
	}

	public static synchronized void restore(Snapshot snapshot) {
		CURRENT = Objects.requireNonNull(snapshot, "planner runtime action snapshot");
	}

	public static synchronized void clear() {
		CURRENT = emptySnapshot();
	}

	private static Snapshot emptySnapshot() {
		return new Snapshot(new FederatedRefedRegistry.Snapshot(Map.of()),
			new FederatedFoutMaterializeRegistry.Snapshot(Map.of()),
			new FederatedLocalMaterializeRegistry.Snapshot(Map.of()));
	}

	private static String scopeSummary(Map<Long, ? extends Map<Long, ?>> scopes) {
		return scopes.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> entry.getKey() + ":" + entry.getValue().size()).toList().toString();
	}
}

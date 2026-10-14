/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import org.apache.sysds.hops.fedplanner.FTypes.FType;

/** Persistent layouts in the fixed-worker planner, independent of runtime output modes. */
public enum MemoPartition {
	LOCAL, ROW, COL, REP;

	public boolean federated() { return this != LOCAL; }

	public MemoPartition normalize(int workers) {
		if(workers < 1)
			throw new IllegalArgumentException("A worker pool must be nonempty");
		return workers == 1 && federated() ? REP : this;
	}

	/** Runtime translation only; a temporary broadcast input is still LOCAL in the memo. */
	public FType runtimeType(int workers) {
		return switch(normalize(workers)) {
			case LOCAL -> null;
			case ROW -> FType.ROW;
			case COL -> FType.COL;
			case REP -> workers == 1 ? FType.FULL : FType.BROADCAST;
		};
	}

	public static MemoPartition fromRuntime(FType type, int workers) {
		if(workers < 1)
			throw new IllegalArgumentException("A worker pool must be nonempty");
		if(type == null)
			return LOCAL;
		return switch(type) {
			case ROW -> ROW.normalize(workers);
			case COL -> COL.normalize(workers);
			case FULL, BROADCAST -> REP;
			case PART, OTHER -> throw new IllegalArgumentException("Noncanonical persistent layout: " + type);
		};
	}
}

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

import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Planner-neutral execution and output-placement alternative.
 *
 * <p>The optional {@code fType} is deliberately not inferred here. The graph
 * builder owns legality and records rejected alternatives separately.</p>
 */
public record PlacementState(ExecType execType, FederatedOutput output, FType fType,
	boolean shapeDependent) implements Comparable<PlacementState> {

	public PlacementState {
		Objects.requireNonNull(execType, "execType");
		Objects.requireNonNull(output, "output");
	}

	public String normalizedSignature() {
		return execType.name() + "/" + output.name() + "/"
			+ (fType == null ? "-" : fType.name()) + "/"
			+ (shapeDependent ? "SHAPE_DEPENDENT" : "SHAPE_INDEPENDENT");
	}

	@Override
	public int compareTo(PlacementState that) {
		return normalizedSignature().compareTo(that.normalizedSignature());
	}
}

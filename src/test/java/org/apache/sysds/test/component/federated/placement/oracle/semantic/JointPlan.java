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

package org.apache.sysds.test.component.federated.placement.oracle.semantic;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural identity: no cost, hash-only key, or production canonicalizer. */
public record JointPlan(List<PrimitiveChoice> choices) {
	public JointPlan {
		choices = choices.stream().sorted(Comparator.comparing(PrimitiveChoice::nodeId)).toList();
		Set<String> ids = new HashSet<>();
		for (PrimitiveChoice choice : choices)
			if (!ids.add(choice.nodeId()))
				throw new IllegalArgumentException("duplicate choice for " + choice.nodeId());
	}

	public boolean hasCompleteIdentity() {
		return choices.stream().allMatch(PrimitiveChoice::hasCompleteIdentity);
	}
}

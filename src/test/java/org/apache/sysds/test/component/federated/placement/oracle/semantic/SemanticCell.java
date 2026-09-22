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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A deliberately small, candidate-independent semantic snapshot for literal fixtures. */
public record SemanticCell(String id, List<NodeSpec> nodes, List<Anchor> anchors) {
	public SemanticCell {
		Objects.requireNonNull(id);
		nodes = List.copyOf(nodes);
		anchors = List.copyOf(anchors);
		Set<String> ids = new HashSet<>();
		for (NodeSpec node : nodes)
			if (!ids.add(node.id()))
				throw new IllegalArgumentException("duplicate node " + node.id());
		ids.clear();
		for (Anchor anchor : anchors)
			if (!ids.add(anchor.id()))
				throw new IllegalArgumentException("duplicate anchor " + anchor.id());
	}

	public record NodeSpec(String id, String operation, String shape, List<String> inputProducerIds,
		boolean restricted, boolean recompile, String fixedSourceAnchor) {
		public NodeSpec {
			Objects.requireNonNull(id);
			Objects.requireNonNull(operation);
			Objects.requireNonNull(shape);
			inputProducerIds = List.copyOf(inputProducerIds);
			if (fixedSourceAnchor != null && !("SOURCE".equals(operation) || "TREAD".equals(operation)))
				throw new IllegalArgumentException("only source-like nodes own a fixed input anchor");
		}
		/** Literal fixtures without a frozen input FederationMap may omit the source anchor. */
		public NodeSpec(String id, String operation, String shape, List<String> inputProducerIds,
			boolean restricted, boolean recompile) {
			this(id, operation, shape, inputProducerIds, restricted, recompile, null);
		}
	}

	/** Geometry is a frozen worker/range/FType identity supplied before candidate creation. */
	public record Anchor(String id, String geometry) {
		public Anchor {
			Objects.requireNonNull(id);
			Objects.requireNonNull(geometry);
		}
	}
}

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

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Exec;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.InputBinding;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Output;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Transfer;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.Anchor;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.NodeSpec;

/** Candidate-independent domains for the explicitly bounded direct-edge grammar. */
public final class PrimitiveDomainGenerator {
	public enum Coverage { DIRECT_ONLY, UNKNOWN }
	public record Result(Coverage coverage, List<List<PrimitiveChoice>> domains, String limitation) {
		public Result {
			domains = domains.stream().map(List::copyOf).toList();
		}
	}

	public Result generate(SemanticCell cell) {
		List<List<PrimitiveChoice>> domains = new ArrayList<>();
		for (NodeSpec node : cell.nodes()) {
			if (!"MATRIX".equals(node.shape()) || !supported(node.operation(), node.inputProducerIds().size()))
				return new Result(Coverage.UNKNOWN, List.of(), "uncovered operation/shape/arity tuple at " + node.id());
			List<PrimitiveChoice> choices = new ArrayList<>();
			choices.add(new PrimitiveChoice(node.id(), Exec.CP, Output.LOUT, null, null,
				directBindings(node, Output.LOUT)));
			for (Anchor anchor : cell.anchors()) {
				if (node.fixedSourceAnchor() != null && !anchor.id().equals(node.fixedSourceAnchor()))
					continue;
				choices.add(new PrimitiveChoice(node.id(), Exec.FED, Output.FOUT, anchor.geometry(), anchor.id(),
					directBindings(node, Output.FOUT)));
			}
			domains.add(choices);
		}
		return new Result(Coverage.DIRECT_ONLY, domains,
			"Full physical grammar is UNKNOWN: upload, download, derived geometry, and global transient relation are unmodeled");
	}

	private static List<InputBinding> directBindings(NodeSpec node, Output required) {
		List<InputBinding> bindings = new ArrayList<>();
		for (String producer : node.inputProducerIds())
			bindings.add(new InputBinding(producer, required, Transfer.DIRECT, null, null));
		return bindings;
	}

	private static boolean supported(String operation, int arity) {
		switch (operation) {
			case "SOURCE":
			case "TREAD": return arity == 0;
			case "COPY":
			case "TWRITE": return arity == 1;
			case "ELEMENTWISE": return arity >= 1;
			default: return false;
		}
	}
}

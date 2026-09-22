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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Exec;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.InputBinding;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Output;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Transfer;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.Anchor;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.NodeSpec;

/**
 * Bounded literal semantics. Unspecified runtime tuples stay UNKNOWN; this is not a
 * certificate for arbitrary SystemDS operations or dynamic execution contexts.
 */
public final class JointPlanLegalityChecker {
	public enum Status { LEGAL, ILLEGAL, UNKNOWN }
	public record Verdict(Status status, String ruleId, String reason) { }

	private record Action(Transfer transfer, String producer, String authority, String geometry) { }

	/** The certification entry point fails closed on literal-fixture identity shorthand. */
	public Verdict checkForCertification(SemanticCell cell, JointPlan plan) {
		if (!plan.hasCompleteIdentity())
			return unknown("S-IDENTITY", "layout/FType/occurrence/value/context identity is unspecified");
		Verdict bounded = check(cell, plan);
		if (bounded.status() != Status.LEGAL) return bounded;
		return unknown("S-COVERAGE", "physical transfer/derived layout and global semantic closure are unproven");
	}

	/** Bounded literal semantics; callers must not use this as a full certificate. */
	public Verdict check(SemanticCell cell, JointPlan plan) {
		Map<String, NodeSpec> nodes = new HashMap<>();
		for (NodeSpec node : cell.nodes()) nodes.put(node.id(), node);
		Set<String> complete = new HashSet<>();
		for (NodeSpec node : cell.nodes())
			if (hasUnspecifiedCycle(node.id(), nodes, new HashSet<>(), complete))
				return unknown("S-CYCLE", "native cycle grounding is not specified");
		Map<String, Anchor> anchors = new HashMap<>();
		for (Anchor anchor : cell.anchors()) anchors.put(anchor.id(), anchor);
		Map<String, PrimitiveChoice> choices = new HashMap<>();
		for (PrimitiveChoice choice : plan.choices()) {
			if (!nodes.containsKey(choice.nodeId())) return illegal("S-STRUCT", "extra node " + choice.nodeId());
			choices.put(choice.nodeId(), choice);
		}
		if (choices.size() != nodes.size()) return illegal("S-STRUCT", "missing node choice");
		Map<String, Action> actions = new HashMap<>();
		for (NodeSpec node : cell.nodes()) {
			PrimitiveChoice choice = choices.get(node.id());
			if (!"MATRIX".equals(node.shape()))
				return unknown("S-CAPABILITY", "uncovered shape " + node.shape());
			if (!supportedOperation(node.operation()))
				return unknown("S-CAPABILITY", "uncovered operation " + node.operation());
			int arity = node.inputProducerIds().size();
			if ((sourceLike(node.operation()) && arity != 0) || ("COPY".equals(node.operation()) && arity != 1)
				|| ("TWRITE".equals(node.operation()) && arity != 1)
				|| ("ELEMENTWISE".equals(node.operation()) && arity < 1))
				return unknown("S-CAPABILITY", "uncovered operation arity");
			if (choice.bindings().size() != arity)
				return illegal("S-BIND", "input occurrence count differs");
			if (node.recompile() && choice.exec() == Exec.CP && choice.output() == Output.FOUT)
				return illegal("S-RECOMPILE", "CP/FOUT in recompile context");
			if (("TREAD".equals(node.operation()) || "TWRITE".equals(node.operation()))
				&& ((choice.exec() == Exec.CP && choice.output() != Output.LOUT)
					|| (choice.exec() == Exec.FED && choice.output() != Output.FOUT)))
				return illegal("S-TRANSIENT", "TRead/TWrite placement pair");
			if (choice.exec() == Exec.CP && choice.output() == Output.FOUT)
				return unknown("S-OUTPUT", "CP/FOUT output materialization is not specified");
			if (choice.exec() == Exec.FED && choice.output() == Output.LOUT)
				return unknown("S-OUTPUT", "FED/LOUT operation tuple is not specified");
			if (choice.output() == Output.FOUT) {
				Anchor anchor = anchors.get(choice.authority());
				if (anchor == null || !anchor.geometry().equals(choice.geometry()))
					return illegal("S-ANCHOR", "output anchor or geometry mismatch");
				if (node.fixedSourceAnchor() != null && !node.fixedSourceAnchor().equals(choice.authority()))
					return illegal("S-SOURCE-MAP", "source output uses another input FederationMap");
			}
			else if (choice.authority() != null || choice.geometry() != null)
				return illegal("S-LOCAL", "local output carries federated authority");
			if (node.restricted() && choice.output() == Output.LOUT)
				return illegal("S-PRIVACY", "restricted local materialization");
			for (int i = 0; i < arity; i++) {
				InputBinding binding = choice.bindings().get(i);
				String producerId = node.inputProducerIds().get(i);
				if (!producerId.equals(binding.producerId()))
					return illegal("S-BIND", "ordered producer occurrence mismatch");
				PrimitiveChoice producer = choices.get(producerId);
				if (producer == null) return illegal("S-BIND", "missing producer choice");
				Output needed = choice.exec() == Exec.FED ? Output.FOUT : Output.LOUT;
				if (binding.required() != needed)
					return illegal("S-BIND", "execution input layout mismatch");
				Verdict transfer = checkTransfer(binding, producer, choice, nodes.get(producerId), anchors, actions);
				if (transfer != null) return transfer;
			}
		}
		return new Verdict(Status.LEGAL, "S-BOUNDED-MATRIX", "all specified tuple and joint constraints hold");
	}

	private static Verdict checkTransfer(InputBinding binding, PrimitiveChoice producer, PrimitiveChoice consumer,
		NodeSpec source, Map<String, Anchor> anchors, Map<String, Action> actions) {
		if (binding.transfer() == Transfer.DIRECT) {
			if (binding.actionId() != null || binding.authority() != null)
				return illegal("S-ACTION", "direct edge carries action");
			if (producer.output() != binding.required())
				return illegal("S-DIRECT", "direct layout mismatch");
			if (binding.required() == Output.FOUT && (!Objects.equals(producer.geometry(), consumer.geometry())
				|| !Objects.equals(producer.authority(), consumer.authority())))
				return illegal("S-DIRECT", "direct federation geometry/authority mismatch");
			return null;
		}
		if (binding.actionId() == null || binding.actionId().isEmpty())
			return illegal("S-ACTION", "transfer action identity missing");
		if (binding.transfer() == Transfer.UPLOAD) {
			if (producer.output() != Output.LOUT || binding.required() != Output.FOUT)
				return illegal("S-UPLOAD", "upload endpoints mismatch");
			Anchor anchor = anchors.get(binding.authority());
			if (anchor == null || !anchor.geometry().equals(consumer.geometry())
				|| !anchor.id().equals(consumer.authority()))
				return illegal("S-UPLOAD", "upload lacks matching anchor");
		}
		else if (binding.transfer() == Transfer.DOWNLOAD) {
			if (producer.output() != Output.FOUT || binding.required() != Output.LOUT)
				return illegal("S-DOWNLOAD", "download endpoints mismatch");
			if (source.restricted()) return illegal("S-PRIVACY", "restricted download");
			if (!Objects.equals(producer.authority(), binding.authority()))
				return illegal("S-DOWNLOAD", "download authority mismatch");
		}
		Action action = new Action(binding.transfer(), binding.producerId(), binding.authority(),
			binding.transfer() == Transfer.UPLOAD ? consumer.geometry() : producer.geometry());
		Action earlier = actions.putIfAbsent(binding.actionId(), action);
		if (earlier != null && !earlier.equals(action))
			return illegal("S-SHARED-ACTION", "same action ID has conflicting meaning");
		return null;
	}

	private static boolean sourceLike(String operation) {
		return "SOURCE".equals(operation) || "TREAD".equals(operation);
	}

	private static boolean hasUnspecifiedCycle(String id, Map<String, NodeSpec> nodes,
		Set<String> visiting, Set<String> complete) {
		if (complete.contains(id) || !nodes.containsKey(id)) return false;
		if (!visiting.add(id)) return true;
		for (String producer : nodes.get(id).inputProducerIds())
			if (hasUnspecifiedCycle(producer, nodes, visiting, complete)) return true;
		visiting.remove(id);
		complete.add(id);
		return false;
	}

	private static boolean supportedOperation(String operation) {
		return sourceLike(operation) || "COPY".equals(operation) || "ELEMENTWISE".equals(operation)
			|| "TWRITE".equals(operation);
	}

	private static Verdict illegal(String id, String reason) { return new Verdict(Status.ILLEGAL, id, reason); }
	private static Verdict unknown(String id, String reason) { return new Verdict(Status.UNKNOWN, id, reason); }
}

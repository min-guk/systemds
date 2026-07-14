/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Finite mutation-free construction of the planner-neutral shadow graph. */
public final class NeutralPlacementGraphBuilder {
	private static final List<FType> INPUT_FTYPES = Collections.unmodifiableList(
		Arrays.asList(FType.ROW, FType.COL, FType.FULL, FType.BROADCAST, FType.PART));
	private final OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

	public NeutralPlacementGraph build(DMLProgram program) {
		String before = PlacementGraphFingerprint.capture(program);
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = PlacementGraphFingerprint.orderedOccurrences(program);
		String programId = structuralFingerprint(occurrences);
		List<Node> nodes = new ArrayList<>();
		Map<String,Integer> versions = new java.util.TreeMap<>();
		Map<Hop,ValueVersionKey> values = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			Hop hop = occurrence.hop();
			String context = requiresRecompileMetadata(hop) ? "recompile" : "compiled";
			ControlRegionKey region = new ControlRegionKey(programId, occurrence.namespace(),
				Arrays.asList(occurrence.path().split("/")), occurrence.path(), context);
			CompiledHopKey key = new CompiledHopKey(programId, occurrence.namespace(), occurrence.path(), context, region,
				"ordinal-" + ordinal + "-hop-" + hop.getHopID(), PlacementGraphFingerprint.structuralKey(hop));
			String variable = lexicalVariable(hop, ordinal);
			int version = isTransientWrite(hop) ? versions.merge(variable, 1, Integer::sum) : versions.getOrDefault(variable, 0);
			VersionKind versionKind = context.equals("recompile") ? VersionKind.CLONE_RECOMPILE
				: occurrence.path().contains("loop-") ? VersionKind.LOOP_HEAD_PHI
				: occurrence.path().contains("branch-") ? VersionKind.BRANCH_JOIN_PHI : VersionKind.ORDINARY;
			ValueVersionKey value = new ValueVersionKey(programId, variable, region, version, versionKind, List.of());
			values.put(hop, value);
			nodes.add(buildNode(hop, key, value));
		}
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, List.of(), List.of());
		String after = PlacementGraphFingerprint.capture(program);
		if(!before.equals(after))
			throw new IllegalStateException("Neutral placement analysis mutated the compiled Hop graph");
		return graph;
	}

	private Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value) {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState,Exclusion> excluded = new java.util.TreeMap<>();
		PlacementState cp = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		legal.add(cp);
		boolean transientAccess = isTransientRead(hop) || isTransientWrite(hop);
		for(List<FType> inputs : inputCombinations(hop.getInput().size())) {
			OpCaps caps;
			try { caps = oracle.decide(hop, inputs); }
			catch(Throwable t) {
				PlacementState failure = new PlacementState(ExecType.FED, FederatedOutput.LOUT, firstFType(inputs), false);
				excluded.putIfAbsent(failure, new Exclusion(failure, ReasonCode.RUNTIME_UNSUPPORTED,
					"RULE_ERROR:" + t.getClass().getSimpleName()));
				continue;
			}
			FType outType = caps.foutFType().orElse(firstFType(inputs));
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType,
				caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.FOUT);
			String detail = caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RUNTIME_UNSUPPORTED, detail));
			else if(transientAccess && !isLegalTransient(state))
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, detail));
			else if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RECOMPILE_CP_FOUT, detail));
			else if(hasUnknownShape(hop) && state.shapeDependent())
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail));
			else if(caps.exec() == ExecType.FED)
				legal.add(state);
		}
		if(transientAccess)
			legal.removeIf(s -> !isLegalTransient(s));
		return new Node(key, nodeKind(hop), value, true, new ArrayList<>(legal), new ArrayList<>(excluded.values()), List.of());
	}

	private static List<List<FType>> inputCombinations(int arity) {
		if(arity == 0) return List.of(List.of());
		List<List<FType>> result = new ArrayList<>();
		for(FType type : INPUT_FTYPES) result.add(new ArrayList<>(Collections.nCopies(arity, type)));
		return result;
	}

	private static FType firstFType(List<FType> values) { return values.isEmpty() ? null : values.get(0); }
	private static boolean hasUnknownShape(Hop h) { return h.getDim1() < 0 || h.getDim2() < 0; }
	private static boolean requiresRecompileMetadata(Hop h) { return h.requiresRecompile(); }
	private static boolean isLegalTransient(PlacementState s) {
		return (s.execType() == ExecType.CP && s.output() == FederatedOutput.LOUT)
			|| (s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT);
	}
	private static boolean isTransientRead(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTREAD; }
	private static boolean isTransientWrite(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTWRITE; }
	private static String lexicalVariable(Hop h, int ordinal) {
		return h instanceof DataOp && h.getName() != null && !h.getName().isBlank() ? h.getName() : "value-" + ordinal;
	}
	private static NodeKind nodeKind(Hop h) {
		if(isTransientRead(h)) return NodeKind.TRANSIENT_READ;
		if(isTransientWrite(h)) return NodeKind.TRANSIENT_WRITE;
		if(h instanceof FunctionOp) return NodeKind.FUNCTION_CALL;
		return NodeKind.OPERATION;
	}
	private static String structuralFingerprint(List<PlacementGraphFingerprint.HopOccurrence> hops) {
		List<String> rows = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence h : hops)
			rows.add(h.namespace() + '|' + h.path() + '|' + PlacementGraphFingerprint.structuralKey(h.hop()));
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}
}

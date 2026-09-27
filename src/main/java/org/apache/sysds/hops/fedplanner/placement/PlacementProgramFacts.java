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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.FixedPointObserver;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.FixedPointPass;

/** Compiler facts prepared before candidate generation; candidate-dependent refinements stay in L3. */
record PlacementProgramFacts(String programFingerprint, String registryFingerprint, String programId,
	List<StatementBlock> topLevelStatementBlocks,
	List<PlacementGraphFingerprint.HopOccurrence> occurrences,
	Map<Hop,NodeShapeFact> compiledShapeFactsByHop, CfgAnalysis cfg,
	PlacementAbstractShapeAnalysis.HopFacts preliminaryAbstractFacts,
	SinglePartitionFacts initialSinglePartitions) {
	PlacementProgramFacts {
		occurrences = List.copyOf(occurrences);
	}

	static PlacementProgramFacts analyze(DMLProgram program, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, FixedPointObserver fixedPointObserver,
		SearchSpaceMetrics complexityMetrics) {
		String before = PlacementGraphFingerprint.capture(program);
		String registryBefore = registrySentinel(program);
		List<StatementBlock> topLevelStatementBlocks = List.copyOf(program.getStatementBlocks());
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = PlacementGraphFingerprint.orderedOccurrences(program);
		Map<Hop,NodeShapeFact> compiledShapeFactsByHop = new IdentityHashMap<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences)
			compiledShapeFactsByHop.put(occurrence.hop(), deriveNodeShapeFact(occurrence.hop()));
		String programId = structuralFingerprint(occurrences);
		CfgAnalysis conservativeCfg = analyzeCfg(program, topLevelStatementBlocks, occurrences, Map.of());
		CfgAnalysis cfg = conservativeCfg;
		PlacementAbstractShapeAnalysis.HopFacts preliminaryAbstractFacts = null;
		int maxCfgRefinementPasses = Math.max(8, occurrences.size() + 1);
		boolean cfgRefinementConverged = false;
		for(int pass = 0; pass < maxCfgRefinementPasses; pass++) {
			preliminaryAbstractFacts = PlacementAbstractShapeAnalysis.inferOriginalOccurrences(
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::namespace).toList(),
				cfg.reachingDefinitions(), cfg.reachingFunctionInputs(), fgraph, fcallSizes, compiledShapeFactsByHop);
			CfgAnalysis refined = analyzeCfg(program, topLevelStatementBlocks, occurrences,
				preliminaryAbstractFacts.scalars());
			boolean stable = refined.equals(cfg);
			if(fixedPointObserver != null)
				fixedPointObserver.accept(new FixedPointPass("cfg-refinement", pass, maxCfgRefinementPasses,
					stable, occurrences.size(), 0, 0, 0));
			if(complexityMetrics != null)
				complexityMetrics.recordFixedPointPass("cfg-refinement");
			if(stable) {
				cfgRefinementConverged = true;
				break;
			}
			cfg = refined;
		}
		if(!cfgRefinementConverged) {
			// Branch refinement is an optional precision improvement. Planning must remain
			// fail-closed if a future transfer function breaks monotonicity: retain the
			// conservative all-branches CFG instead of rejecting a legal program or pruning
			// a reachable branch.
			cfg = conservativeCfg;
			preliminaryAbstractFacts = PlacementAbstractShapeAnalysis.inferOriginalOccurrences(
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
				occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::namespace).toList(),
				cfg.reachingDefinitions(), cfg.reachingFunctionInputs(), fgraph, fcallSizes, compiledShapeFactsByHop);
		}
		Set<Hop> unresolvedValueSources = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++)
			if(cfg.reachingFunctionInputs().get(ordinal)
				|| !cfg.reachingFunctionOutputDefinitions().get(ordinal).isEmpty())
				unresolvedValueSources.add(occurrences.get(ordinal).hop());
		// The Hop-keyed abstract source relation cannot distinguish the expanded
		// function-boundary occurrences. A partial union is not a FULL cardinality
		// certificate; those reads remain UNKNOWN until the occurrence closure below.
		SinglePartitionFacts singlePartitions = new SinglePartitionFacts(
			occurrences.stream().map(PlacementGraphFingerprint.HopOccurrence::hop).toList(),
			preliminaryAbstractFacts.valueSources(), unresolvedValueSources);
		return new PlacementProgramFacts(before, registryBefore, programId, topLevelStatementBlocks,
			occurrences, Collections.unmodifiableMap(compiledShapeFactsByHop), cfg,
			preliminaryAbstractFacts, singlePartitions);
	}

	static final int CFG_FUNCTION_INPUT_DEFINITION = -1;

	static CfgAnalysis analyzeCfg(DMLProgram program, List<StatementBlock> topLevelStatementBlocks,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
		Map<Integer,CfgFunctionOutputDefinition> functionOutputDefinitionsByToken = new java.util.TreeMap<>();
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall = new java.util.TreeMap<>();
		int nextFunctionOutputToken = CFG_FUNCTION_INPUT_DEFINITION - 1;
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			if(!(occurrences.get(ordinal).hop() instanceof FunctionOp call))
				continue;
			int outputCount = boundaryCount(call.getOutputVariableNames(),
				call.getOutputs() == null ? 0 : call.getOutputs().size());
			List<CfgFunctionOutputDefinition> definitions = new ArrayList<>(outputCount);
			for(int outputPosition = 0; outputPosition < outputCount; outputPosition++) {
				String variable = functionOutputVariableName(call, outputPosition);
				if(variable == null)
					continue;
				CfgFunctionOutputDefinition definition = new CfgFunctionOutputDefinition(
					ordinal, outputPosition, variable, nextFunctionOutputToken--);
				definitions.add(definition);
				functionOutputDefinitionsByToken.put(definition.token(), definition);
			}
			functionOutputDefinitionsByCall.put(ordinal, List.copyOf(definitions));
		}
		Map<StatementBlock,Set<StatementBlock>> predecessors = new IdentityHashMap<>();
		Set<StatementBlock> loopHeaders = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<StatementBlock> loopLatches = Collections.newSetFromMap(new IdentityHashMap<>());
		connectSequence(topLevelStatementBlocks, Set.of(), predecessors, loopHeaders, loopLatches,
			scalarFacts);
		Map<StatementBlock,Map<String,Set<Integer>>> functionInputSeeds = new IdentityHashMap<>();
		Map<String,Set<StatementBlock>> functionExits = new java.util.TreeMap<>();
		for(Map.Entry<String,FunctionStatementBlock> entry :
			program.getNamedNSFunctionStatementBlocks().entrySet()) {
			FunctionStatementBlock function = entry.getValue();
			Set<StatementBlock> exits = connectSequence(List.of(function), Set.of(), predecessors,
				loopHeaders, loopLatches, scalarFacts);
			functionExits.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(exits)));
			FunctionStatement statement = (FunctionStatement) function.getStatement(0);
			Map<String,Set<Integer>> seeds = new java.util.TreeMap<>();
			for(var input : statement.getInputParams())
				seeds.put(entry.getKey() + '\u0000' + input.getName(),
					Set.of(CFG_FUNCTION_INPUT_DEFINITION));
			functionInputSeeds.put(function, Collections.unmodifiableMap(seeds));
		}
		Map<StatementBlock,List<Integer>> byBlock = new IdentityHashMap<>();
		for(int i = 0; i < occurrences.size(); i++)
			byBlock.computeIfAbsent(occurrences.get(i).block(), k -> new ArrayList<>()).add(i);
		Map<String,Integer> counters = new java.util.TreeMap<>();
		List<Integer> ordinals = new ArrayList<>(occurrences.size());
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), ordinals.size());
			ordinals.add(isDefinition(occurrence.hop()) ? counters.merge(variable, 1, Integer::sum)
				: counters.getOrDefault(variable, 0));
		}
		Map<StatementBlock,Map<String,Set<Integer>>> out = new IdentityHashMap<>();
		boolean changed;
		do {
			changed = false;
			for(StatementBlock block : predecessors.keySet()) {
				Map<String,Set<Integer>> state = new java.util.TreeMap<>();
				mergeDefinitions(state, functionInputSeeds.get(block));
				for(StatementBlock predecessor : predecessors.get(block))
					mergeDefinitions(state, out.get(predecessor));
				transfer(state, byBlock.getOrDefault(block, List.of()), occurrences,
					functionOutputDefinitionsByCall);
				if(!state.equals(out.get(block))) {
					out.put(block, state);
					changed = true;
				}
			}
		} while(changed);
		List<Set<Integer>> reaching = new ArrayList<>(occurrences.size());
		List<Set<CfgFunctionOutputDefinition>> reachingFunctionOutputs = new ArrayList<>(occurrences.size());
		List<Boolean> reachingFunctionInputs = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) {
			reaching.add(Set.of());
			reachingFunctionOutputs.add(Set.of());
			reachingFunctionInputs.add(false);
		}
		for(Map.Entry<StatementBlock,List<Integer>> entry : byBlock.entrySet()) {
			Map<String,Set<Integer>> state = new java.util.TreeMap<>();
			mergeDefinitions(state, functionInputSeeds.get(entry.getKey()));
			for(StatementBlock predecessor : predecessors.getOrDefault(entry.getKey(), Set.of()))
				mergeDefinitions(state, out.get(predecessor));
			for(int index : entry.getValue()) {
				PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(index);
				String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), index);
				if(isTransientRead(occurrence.hop())) {
					Set<Integer> raw = state.getOrDefault(variable, Set.of());
					reachingFunctionInputs.set(index, raw.contains(CFG_FUNCTION_INPUT_DEFINITION));
					Set<Integer> definitions = raw.stream().filter(token -> token >= 0)
						.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
					Set<CfgFunctionOutputDefinition> functionOutputs = raw.stream()
						.map(functionOutputDefinitionsByToken::get).filter(Objects::nonNull)
						.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
					reaching.set(index, Collections.unmodifiableSet(definitions));
					reachingFunctionOutputs.set(index, Collections.unmodifiableSet(functionOutputs));
				}
				transferDefinition(state, index, occurrence, functionOutputDefinitionsByCall);
			}
		}
		List<VersionKind> kinds = new ArrayList<>(occurrences.size());
		for(int i = 0; i < occurrences.size(); i++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(i);
			VersionKind kind = VersionKind.ORDINARY;
			int sourceCount = reaching.get(i).size() + reachingFunctionOutputs.get(i).size()
				+ (reachingFunctionInputs.get(i) ? 1 : 0);
			if(isFormalFunctionInputRead(program, occurrence, reachingFunctionInputs.get(i), reaching.get(i)))
				kind = VersionKind.FUNCTION_INPUT;
			else if(isTransientRead(occurrence.hop()) && sourceCount > 1)
				kind = loopHeaders.contains(occurrence.block()) ? VersionKind.LOOP_HEAD_PHI
					: branchDefinitionsDiffer(occurrence, predecessors, out)
						? VersionKind.BRANCH_JOIN_PHI : VersionKind.ORDINARY;
			else if(isDefinition(occurrence.hop()) && loopLatches.contains(occurrence.block()))
				kind = VersionKind.LOOP_BACKEDGE;
			kinds.add(kind);
		}
		Map<String,Map<String,FunctionExitValue>> functionExitValues = new java.util.TreeMap<>();
		for(Map.Entry<String,FunctionStatementBlock> entry :
			program.getNamedNSFunctionStatementBlocks().entrySet()) {
			FunctionStatement statement = (FunctionStatement) entry.getValue().getStatement(0);
			Map<String,FunctionExitValue> outputs = new java.util.TreeMap<>();
			for(var output : statement.getOutputParams()) {
				String variable = entry.getKey() + '\u0000' + output.getName();
				Set<Integer> raw = new java.util.TreeSet<>();
				for(StatementBlock exit : functionExits.getOrDefault(entry.getKey(), Set.of()))
					raw.addAll(out.getOrDefault(exit, Map.of()).getOrDefault(variable, Set.of()));
				boolean reachesFunctionInput = raw.remove(CFG_FUNCTION_INPUT_DEFINITION);
				Set<CfgFunctionOutputDefinition> functionOutputs = raw.stream()
					.map(functionOutputDefinitionsByToken::get).filter(Objects::nonNull)
					.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
				raw.removeIf(token -> token < 0);
				outputs.put(output.getName(), new FunctionExitValue(
					Collections.unmodifiableSet(raw), Collections.unmodifiableSet(functionOutputs),
					reachesFunctionInput));
			}
			functionExitValues.put(entry.getKey(), Collections.unmodifiableMap(outputs));
		}
		return new CfgAnalysis(Collections.unmodifiableList(ordinals), Collections.unmodifiableList(kinds),
			Collections.unmodifiableList(reaching), Collections.unmodifiableList(reachingFunctionOutputs),
			Collections.unmodifiableList(reachingFunctionInputs),
			Collections.unmodifiableMap(functionExitValues));
	}

	static boolean isFormalFunctionInputRead(DMLProgram program,
		PlacementGraphFingerprint.HopOccurrence occurrence, boolean reachesFunctionInput,
		Set<Integer> reachingDefinitions) {
		if(!isTransientRead(occurrence.hop()) || !reachesFunctionInput || !reachingDefinitions.isEmpty()
			|| occurrence.namespace() == null || "main".equals(occurrence.namespace()))
			return false;
		FunctionStatementBlock function = program.getNamedNSFunctionStatementBlocks().get(occurrence.namespace());
		if(function == null || function.getNumStatements() != 1
			|| !(function.getStatement(0) instanceof FunctionStatement statement))
			return false;
		String variable = lexicalVariable(occurrence.hop(), -1);
		return statement.getInputParams().stream().anyMatch(input -> variable.equals(input.getName()));
	}

	static Set<StatementBlock> connectSequence(List<StatementBlock> blocks, Set<StatementBlock> incoming,
		Map<StatementBlock,Set<StatementBlock>> predecessors, Set<StatementBlock> loopHeaders,
		Set<StatementBlock> loopLatches,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
		Set<StatementBlock> exits = new LinkedHashSet<>(incoming);
		for(StatementBlock block : blocks == null ? List.<StatementBlock>of() : blocks) {
			predecessors.computeIfAbsent(block, k -> Collections.newSetFromMap(new IdentityHashMap<>())).addAll(exits);
			if(block instanceof IfStatementBlock) {
				IfStatement statement = (IfStatement) block.getStatement(0);
				Boolean predicate = exactPredicate(isbPredicate((IfStatementBlock)block), scalarFacts);
				if(predicate == null || predicate) {
					Set<StatementBlock> thenExits = connectSequence(statement.getIfBody(), Set.of(block), predecessors,
						loopHeaders, loopLatches, scalarFacts);
					exits = new LinkedHashSet<>(thenExits.isEmpty() ? Set.of(block) : thenExits);
				}
				if(predicate == null || !predicate) {
					Set<StatementBlock> elseExits = connectSequence(statement.getElseBody(), Set.of(block), predecessors,
						loopHeaders, loopLatches, scalarFacts);
					if(predicate == null)
						exits.addAll(elseExits.isEmpty() ? Set.of(block) : elseExits);
					else
						exits = new LinkedHashSet<>(elseExits.isEmpty() ? Set.of(block) : elseExits);
				}
			}
			else if(block instanceof WhileStatementBlock) {
				loopHeaders.add(block);
				WhileStatement statement = (WhileStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches, scalarFacts);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof ForStatementBlock) {
				loopHeaders.add(block);
				ForStatement statement = (ForStatement) block.getStatement(0);
				Set<StatementBlock> bodyExits = connectSequence(statement.getBody(), Set.of(block), predecessors,
					loopHeaders, loopLatches, scalarFacts);
				predecessors.get(block).addAll(bodyExits);
				bodyExits.stream().filter(exit -> exit != block).forEach(loopLatches::add);
				exits = new LinkedHashSet<>(Set.of(block));
			}
			else if(block instanceof FunctionStatementBlock) {
				FunctionStatement statement = (FunctionStatement) block.getStatement(0);
				exits = connectSequence(statement.getBody(), Set.of(block), predecessors, loopHeaders, loopLatches,
					scalarFacts);
			}
			else exits = new LinkedHashSet<>(Set.of(block));
		}
		return exits;
	}

	static Hop isbPredicate(IfStatementBlock block) {
		Hop predicateRoot = block.getPredicateHops();
		return predicateRoot != null && predicateRoot.getInput() != null
			&& predicateRoot.getInput().size() == 1 ? predicateRoot.getInput(0) : predicateRoot;
	}

	static Boolean exactPredicate(Hop predicate,
		Map<Hop,PlacementAbstractShapeAnalysis.ScalarState> scalarFacts) {
		if(predicate == null || scalarFacts == null)
			return null;
		PlacementAbstractShapeAnalysis.ScalarState state = scalarFacts.get(predicate);
		if(state == null || !state.isExact())
			return null;
		String value = state.literal().canonicalValue();
		if("true".equalsIgnoreCase(value) || "1".equals(value))
			return true;
		if("false".equalsIgnoreCase(value) || "0".equals(value))
			return false;
		return null;
	}

	static void transfer(Map<String,Set<Integer>> state, List<Integer> indices,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall) {
		for(int index : indices) {
			transferDefinition(state, index, occurrences.get(index), functionOutputDefinitionsByCall);
		}
	}

	static void transferDefinition(Map<String,Set<Integer>> state, int index,
		PlacementGraphFingerprint.HopOccurrence occurrence,
		Map<Integer,List<CfgFunctionOutputDefinition>> functionOutputDefinitionsByCall) {
		if(isDefinition(occurrence.hop()))
			state.put(occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), index), Set.of(index));
		for(CfgFunctionOutputDefinition definition :
			functionOutputDefinitionsByCall.getOrDefault(index, List.of()))
			state.put(occurrence.namespace() + '\u0000' + definition.variable(), Set.of(definition.token()));
	}

	static void mergeDefinitions(Map<String,Set<Integer>> target, Map<String,Set<Integer>> source) {
		if(source == null) return;
		for(Map.Entry<String,Set<Integer>> entry : source.entrySet()) {
			Set<Integer> merged = new java.util.TreeSet<>(target.getOrDefault(entry.getKey(), Set.of()));
			merged.addAll(entry.getValue());
			target.put(entry.getKey(), Collections.unmodifiableSet(merged));
		}
	}

	static boolean branchDefinitionsDiffer(PlacementGraphFingerprint.HopOccurrence occurrence,
		Map<StatementBlock,Set<StatementBlock>> predecessors,
		Map<StatementBlock,Map<String,Set<Integer>>> out) {
		Set<StatementBlock> incoming = predecessors.getOrDefault(occurrence.block(), Set.of());
		if(incoming.size() < 2) return false;
		String variable = occurrence.namespace() + '\u0000' + lexicalVariable(occurrence.hop(), -1);
		Set<Set<Integer>> branchOut = new HashSet<>();
		for(StatementBlock predecessor : incoming)
			branchOut.add(out.getOrDefault(predecessor, Map.of()).getOrDefault(variable, Set.of()));
		return branchOut.size() > 1;
	}

	static boolean isDefinition(Hop hop) { return isTransientWrite(hop) || isFunctionOutput(hop); }

	record CfgFunctionOutputDefinition(int callOrdinal, int outputPosition,
		String variable, int token) implements Comparable<CfgFunctionOutputDefinition> {
		@Override public int compareTo(CfgFunctionOutputDefinition that) {
			int callOrder = Integer.compare(callOrdinal, that.callOrdinal);
			return callOrder != 0 ? callOrder : Integer.compare(outputPosition, that.outputPosition);
		}
	}

	record FunctionExitValue(Set<Integer> definitionOrdinals,
		Set<CfgFunctionOutputDefinition> functionOutputDefinitions, boolean reachesFunctionInput) { }

	record CfgAnalysis(List<Integer> definitionOrdinals, List<VersionKind> versionKinds,
		List<Set<Integer>> reachingDefinitions,
		List<Set<CfgFunctionOutputDefinition>> reachingFunctionOutputDefinitions,
		List<Boolean> reachingFunctionInputs,
		Map<String,Map<String,FunctionExitValue>> functionExitValues) { }

	static String registrySentinel(DMLProgram program) {
		List<String> rows = new ArrayList<>();
		for(long sbId : PlacementGraphFingerprint.statementBlockIds(program)) {
			FederatedRefedRegistry.snapshot(sbId).forEach((hop, spec) -> spec.getAuthorities().forEach(authority ->
				rows.add("R|" + sbId + '|' + hop + '|' + authority.getAnchorHopId() + '|'
					+ authority.getAnchorKey() + '|' + authority.getMaterializationFType() + '|'
					+ authority.getConsumerInputs())));
			FederatedFoutMaterializeRegistry.snapshot(sbId).forEach((hop, spec) -> rows.add("F|" + sbId + '|' + hop
				+ '|' + spec.getAnchorHopId() + '|' + spec.getFTypeHint() + '|' + spec.getAnchorLabel() + '|' + spec.getAnchorKey()));
			FederatedLocalMaterializeRegistry.snapshotScopes(sbId).forEach((scope, entries) -> entries.forEach((hop, spec) ->
				rows.add("L|" + scope + '|' + hop + '|' + spec.getConsumerHopIds() + '|' + spec.getFTypeHint() + '|' + spec.getReason())));
		}
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}

	static int boundaryCount(String[] names, int structuralArity) {
		return names == null ? structuralArity : Math.max(names.length, structuralArity);
	}

	static String functionOutputVariableName(FunctionOp call, int position) {
		String[] names = call.getOutputVariableNames();
		if(names != null && position < names.length && names[position] != null
			&& !names[position].isBlank())
			return names[position];
		List<Hop> outputs = call.getOutputs();
		if(outputs != null && position < outputs.size() && outputs.get(position) != null
			&& outputs.get(position).getName() != null && !outputs.get(position).getName().isBlank())
			return outputs.get(position).getName();
		return null;
	}

	static NodeShapeFact deriveNodeShapeFact(Hop hop) {
		var shape = OracleFacade.nodeShape(Objects.requireNonNull(hop, "hop"));
		// InitFEDInstruction establishes this literal source's dimensions from its own
		// range endpoints. Do not transfer these dimensions through inherited anchors:
		// transpose, indexing and other derived values can have different geometry.
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& (shape.rows() < 0 || shape.cols() < 0)) {
			List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
			long rows = -1, cols = -1;
			for(AnchorPartition partition : partitions) {
				if(partition.begin().get(0) < 0 || partition.begin().get(1) < 0
					|| partition.end().get(0) <= partition.begin().get(0)
					|| partition.end().get(1) <= partition.begin().get(1))
					return new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
				rows = Math.max(rows, partition.end().get(0));
				cols = Math.max(cols, partition.end().get(1));
			}
			return new NodeShapeFact(shape.dataType(), shape.rows() < 0 ? rows : shape.rows(),
				shape.cols() < 0 ? cols : shape.cols());
		}
		return new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols());
	}

	static List<DurableAnchorKey> durableAnchor(Hop hop) {
		if(!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FEDERATED) return List.of();
		DataOp data = (DataOp) hop;
		List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
		if(partitions.isEmpty()) return List.of();
		FType type = durableFedInitAnchorFType(data, partitions);
		if(type == null || type == FType.PART || type == FType.OTHER) return List.of();
		return List.of(new DurableAnchorKey("fed-init:" + data.getName(), type, partitions));
	}

	static FType exactFederatedSourceFType(Hop hop, List<DurableAnchorKey> anchors) {
		if(!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FEDERATED)
			return null;
		if(anchors.size() == 1)
			return anchors.get(0).fType();
		if(!anchors.isEmpty())
			return null;
		DataOp data = (DataOp) hop;
		List<AnchorPartition> partitions = fedInitLiteralPartitions(data);
		return partitions.isEmpty() ? null : exactFedInitSourceFType(data, partitions);
	}

	static FType exactFedInitSourceFType(DataOp data, List<AnchorPartition> partitions) {
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		return type == null ? deriveAnchorFType(partitions) : type;
	}

	static FType durableFedInitAnchorFType(DataOp data, List<AnchorPartition> partitions) {
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		return type == null || type == FType.PART || type == FType.OTHER ? deriveAnchorFType(partitions) : type;
	}

	static List<AnchorPartition> fedInitLiteralPartitions(DataOp data) {
		int addressIndex = data.getParameterIndex(DataExpression.FED_ADDRESSES);
		int rangeIndex = data.getParameterIndex(DataExpression.FED_RANGES);
		if(addressIndex < 0 || rangeIndex < 0) return List.of();
		List<Hop> addresses = data.getInput(addressIndex).getInput();
		List<Hop> ranges = data.getInput(rangeIndex).getInput();
		if(addresses.isEmpty() || ranges.size() != addresses.size() * 2) return List.of();
		List<AnchorPartition> partitions = new ArrayList<>();
		for(int i = 0; i < addresses.size(); i++) {
			if(!(addresses.get(i) instanceof LiteralOp)) return List.of();
			Hop begin = ranges.get(2 * i), end = ranges.get(2 * i + 1);
			if(begin.getInput().size() < 2 || end.getInput().size() < 2
				|| !(begin.getInput(0) instanceof LiteralOp) || !(begin.getInput(1) instanceof LiteralOp)
				|| !(end.getInput(0) instanceof LiteralOp) || !(end.getInput(1) instanceof LiteralOp)) return List.of();
			partitions.add(new AnchorPartition(((LiteralOp) addresses.get(i)).getStringValue(),
				List.of(((LiteralOp) begin.getInput(0)).getLongValue(), ((LiteralOp) begin.getInput(1)).getLongValue()),
				List.of(((LiteralOp) end.getInput(0)).getLongValue(), ((LiteralOp) end.getInput(1)).getLongValue())));
		}
		return List.copyOf(partitions);
	}

	static FType deriveAnchorFType(List<AnchorPartition> partitions) {
		if(partitions.isEmpty()) return null;
		long maxRow = partitions.stream().mapToLong(p -> p.end().get(0)).max().orElse(-1);
		long maxCol = partitions.stream().mapToLong(p -> p.end().get(1)).max().orElse(-1);
		boolean spansRows = partitions.stream().allMatch(p -> p.begin().get(0) == 0 && p.end().get(0) == maxRow);
		boolean spansCols = partitions.stream().allMatch(p -> p.begin().get(1) == 0 && p.end().get(1) == maxCol);
		if(spansRows && spansCols) return partitions.size() == 1 ? FType.FULL : FType.BROADCAST;
		if(spansCols) return FType.ROW;
		if(spansRows) return FType.COL;
		return FType.OTHER;
	}
	static boolean requiresRecompileMetadata(Hop h) { return h.requiresRecompile(); }
	static boolean isTransientRead(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTREAD; }
	static boolean isTransientWrite(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTWRITE; }
	static boolean isFunctionOutput(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.FUNCTIONOUTPUT; }
	static boolean isMultiReturnBuiltinOutputCarrier(Hop hop) {
		if(!isFunctionOutput(hop) || hop.getInput() == null || hop.getInput().isEmpty())
			return false;
		for(Hop parent : hop.getInput(0).getParent()) {
			if(!(parent instanceof FunctionOp function)
				|| function.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				|| function.getOutputs() == null)
				continue;
			if(function.getOutputs().stream().anyMatch(output -> output == hop))
				return true;
		}
		return false;
	}
	static String lexicalVariable(Hop h, int ordinal) {
		return h instanceof DataOp && h.getName() != null && !h.getName().isBlank() ? h.getName() : "value-" + ordinal;
	}
	static NodeKind nodeKind(Hop h, ValueVersionKey value) {
		if(value.versionKind() == VersionKind.CLONE_RECOMPILE) return NodeKind.CLONE;
		return physicalNodeKind(h, value);
	}

	static NodeKind physicalNodeKind(Hop h) {
		return physicalNodeKind(h, null);
	}

	static NodeKind physicalNodeKind(Hop h, ValueVersionKey value) {
		// FUNCTION_INPUT is a value-version property for a concrete formal read. Keep the
		// physical node classified as a compiled TRANSIENT_READ; only synthetic call-site
		// boundary nodes use NodeKind.FUNCTION_INPUT.
		if(value != null && (value.versionKind() == VersionKind.LOOP_HEAD_PHI
			|| value.versionKind() == VersionKind.LOOP_BACKEDGE))
			return NodeKind.LOOP_PHI;
		if(value != null && value.versionKind() == VersionKind.BRANCH_JOIN_PHI) return NodeKind.BRANCH_JOIN;
		if(isTransientRead(h)) return NodeKind.TRANSIENT_READ;
		if(isTransientWrite(h)) return NodeKind.TRANSIENT_WRITE;
		if(isFunctionOutput(h)) return NodeKind.TRANSIENT_WRITE;
		// Only a DML FunctionOp is a non-executing call-site placeholder. Multi-return
		// builtins such as transformencode lower to a concrete CP/FED instruction at this
		// occurrence and must participate in ordinary physical input feasibility and cost.
		if(h instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.DML)
			return NodeKind.FUNCTION_CALL;
		return NodeKind.OPERATION;
	}
	static String structuralFingerprint(List<PlacementGraphFingerprint.HopOccurrence> hops) {
		List<String> rows = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence h : hops)
			rows.add(h.namespace() + '|' + h.path() + '|' + h.topology() + '|'
				+ PlacementGraphFingerprint.semanticStructuralKey(h.hop()));
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}

	/** Create the exact occurrence identity at its original point in seeding order. */
	CompiledHopKey compiledOccurrenceKey(PlacementGraphFingerprint.HopOccurrence occurrence) {
		Hop hop = occurrence.hop();
		String context = requiresRecompileMetadata(hop) || occurrence.dynamicRecompileRegion()
			? "recompile" : "compiled";
		ControlRegionKey region = new ControlRegionKey(programId, occurrence.namespace(),
			occurrence.regionPath(), occurrence.path(), context);
		return new CompiledHopKey(programId, occurrence.namespace(), occurrence.path(), context, region,
			occurrence.topology(), PlacementGraphFingerprint.semanticStructuralKey(hop));
	}

	/** Original compiler value identity; relation-refined value versions remain closure-owned. */
	ValueVersionKey valueVersion(int ordinal, Hop hop, ControlRegionKey region, Map<Hop,ValueVersionKey> values) {
		String variable = lexicalVariable(hop, ordinal);
		int version = cfg.definitionOrdinals().get(ordinal);
		// CLONE_RECOMPILE identifies a concrete Hop whose own metadata requires a
		// clone. An inherited function/loop recompile region still retains its exact
		// CFG value kind (notably FUNCTION_INPUT), while its CompiledHopKey context
		// independently closes CP/FOUT for runtime recompilation.
		VersionKind versionKind = requiresRecompileMetadata(hop) ? VersionKind.CLONE_RECOMPILE
			: cfg.versionKinds().get(ordinal);
		List<String> predecessorEdges = new ArrayList<>();
		for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
			Hop input = hop.getInput(inputPosition);
			if(values.containsKey(input)) predecessorEdges.add("input-" + inputPosition + ':'
				+ values.get(input).cfgReferenceSignature());
		}
		return new ValueVersionKey(programId, variable, region, version, versionKind,
			predecessorEdges);
	}
}

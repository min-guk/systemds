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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.DefinitionSite;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ReturnDefinitionSite;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.FederatedFoutMaterialize;
import org.apache.sysds.lops.FederatedSupply;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.LopsException;
import org.apache.sysds.lops.MemoDefinition;
import org.apache.sysds.lops.OutputParameters;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;

/** Exact, fail-closed lowering of Memo-selected movement edges. */
public final class MemoSupplyLowering {
	private MemoSupplyLowering() {
	}

	/**
	 * Validates the complete statement-block supply plan before mutating any Lop edge.
	 * The returned Lops must be added to the owning {@link Dag} by the caller.
	 */
	public static List<Lop> apply(List<Lop> lops, StatementBlock statementBlock,
		List<Hop> logicalHopRoots) {
		if(statementBlock == null)
			return List.of();
		return apply(lops, statementBlock, logicalHopRoots,
			MemoSupplyRegistry.snapshot(statementBlock.getDMLProg(),statementBlock.getSBID()));
	}

	public static List<Lop> apply(List<Lop> lops, StatementBlock statementBlock,
		List<Hop> logicalHopRoots, List<Supply> supplies) {
		if(statementBlock == null)
			return List.of();
		List<DefinitionSite> definitions = MemoPlanRegistry.hasPlan(statementBlock.getDMLProg())
			? MemoPlanRegistry.definitions(statementBlock.getDMLProg(), statementBlock.getSBID()) : List.of();
		List<ReturnDefinitionSite> returns = MemoPlanRegistry.hasPlan(statementBlock.getDMLProg())
			? MemoPlanRegistry.returnDefinitions(statementBlock.getDMLProg(), statementBlock.getSBID()) : List.of();
		if(supplies.isEmpty() && definitions.isEmpty() && returns.isEmpty())
			return List.of();
		if(logicalHopRoots == null || logicalHopRoots.isEmpty())
			throw new LopsException("Memo supply lowering requires the exact logical Hop DAG.");

		Map<Long, Hop> logical = exactLogicalHops(logicalHopRoots);
		Map<FunctionCallCP, Map<Integer, List<Long>>> returnDefinitions = new LinkedHashMap<>();
		Map<FunctionCallCP, Map<Integer, Long>> returnTargets = new LinkedHashMap<>();
		for(ReturnDefinitionSite site : returns) {
			Hop callHop = logical.get(site.callHopId());
			if(callHop == null || !(callHop.getLops() instanceof FunctionCallCP)
				|| !lops.contains(callHop.getLops()))
				throw new LopsException("Memo function return references a stale or eliminated call: " + site);
			Map<Integer, List<Long>> outputs = returnDefinitions.computeIfAbsent(
				(FunctionCallCP) callHop.getLops(), ignored -> new LinkedHashMap<>());
			Map<Integer, Long> targets = returnTargets.computeIfAbsent(
				(FunctionCallCP) callHop.getLops(), ignored -> new LinkedHashMap<>());
			List<Long> candidates = outputs.computeIfAbsent(site.outputPosition(), ignored -> new ArrayList<>());
			if(candidates.contains(site.sourceOriginValueId()))
				throw new LopsException("Duplicate Memo function return source: " + site);
			candidates.add(site.sourceOriginValueId());
			Long priorTarget = targets.putIfAbsent(site.outputPosition(), site.targetOriginValueId());
			if(priorTarget != null && priorTarget != site.targetOriginValueId())
				throw new LopsException("Memo function return alternatives disagree on caller target: " + site);
		}
		long snapshot = returns.isEmpty() ? -1 : MemoPlanRegistry.epoch(statementBlock.getDMLProg());
		Map<DefinitionKey, ResolvedDefinition> resolvedDefinitions = new LinkedHashMap<>();
		for(DefinitionSite definition : definitions) {
			if(definition.originValueId() < 0)
				throw new LopsException("Memo definition has an invalid native origin: " + definition);
			Hop producerHop = logical.get(definition.producerHopId());
			if(producerHop == null || producerHop.getLops() == null || !lops.contains(producerHop.getLops()))
				throw new LopsException("Memo definition references a stale or eliminated Hop: " + definition);
			Lop producer = producerHop.getLops();
			if(!producer.getDataType().isMatrix() && !producer.getDataType().isFrame())
				throw new LopsException("Memo definition supports matrix and frame values only: " + definition);
			DefinitionKey key = new DefinitionKey(producer, definition.originValueId());
			if(resolvedDefinitions.putIfAbsent(key, new ResolvedDefinition(definition, producer)) != null)
				throw new LopsException("Duplicate memo native definition marker: " + definition);
		}
		List<ResolvedSupply> resolved = new ArrayList<>(supplies.size());
		for(Supply supply : supplies)
			resolved.add(resolve(lops, logical, statementBlock, supply));

		Map<MaterializationKey, List<ResolvedSupply>> groups = new LinkedHashMap<>();
		for(ResolvedSupply supply : resolved)
			groups.computeIfAbsent(supply.key(), ignored -> new ArrayList<>()).add(supply);
		Map<Lop, Integer> definitionsPerProducer = new IdentityHashMap<>();
		for(ResolvedDefinition definition : resolvedDefinitions.values())
			definitionsPerProducer.merge(definition.producer(), 1, Integer::sum);
		for(Map.Entry<Lop, Integer> entry : definitionsPerProducer.entrySet())
			if(entry.getValue() != 1)
				throw new LopsException("One physical Memo producer cannot publish multiple native origin markers: hop="
					+ entry.getKey().getHopID() + " definitions=" + entry.getValue() + " sites="
					+ resolvedDefinitions.values().stream().filter(value -> value.producer()==entry.getKey())
						.map(ResolvedDefinition::site).toList());
		Set<Lop> exactPhysicalDAG = Collections.newSetFromMap(new IdentityHashMap<>());
		exactPhysicalDAG.addAll(lops);
		Map<DefinitionKey, List<PhysicalUse>> markerUses = new LinkedHashMap<>();
		for(Map.Entry<DefinitionKey, ResolvedDefinition> entry : resolvedDefinitions.entrySet())
			markerUses.put(entry.getKey(), validateMarkerUses(entry.getValue().producer(), exactPhysicalDAG));

		// Only publish call metadata after every definition, supply, and physical marker use validated.
		// From this point onward no selected-plan validation remains that can reject a later stale edge.
		returnDefinitions.forEach((call, outputs) ->
			call.validateMemoReturnDefinitionCandidates(snapshot, outputs, returnTargets.get(call)));
		returnDefinitions.forEach((call, outputs) ->
			call.setMemoReturnDefinitionCandidates(snapshot, outputs, returnTargets.get(call)));

		List<Lop> inserted = new ArrayList<>(groups.size() + resolvedDefinitions.size());
		Map<DefinitionKey, Lop> markers = new LinkedHashMap<>();
		for(Map.Entry<DefinitionKey, ResolvedDefinition> entry : resolvedDefinitions.entrySet()) {
			MemoDefinition marker = new MemoDefinition(entry.getValue().producer(), entry.getKey().originValueId());
			marker.setFederatedOutput(entry.getValue().producer().getFederatedOutput());
			marker.setPlannerSyntheticActionKey("memo-definition|sb=" + statementBlock.getSBID()
				+ "|producer=" + entry.getValue().producer().getHopID() + "|origin=" + entry.getKey().originValueId());
			marker.getOutputParameters().setLabel(Dag.getNextUniqueVarname(marker.getDataType()));
			copyOutputParameters(marker.getOutputParameters(), entry.getValue().producer().getOutputParameters());
			markers.put(entry.getKey(), marker);
			inserted.add(marker);
		}
		for(Map.Entry<MaterializationKey, List<ResolvedSupply>> entry : groups.entrySet()) {
			MaterializationKey key = entry.getKey();
			if(key.recipes().size() == 1 && key.recipes().get(0).movement() == Movement.DIRECT)
				continue;
			Lop materializerInput = key.producer();
			if(!key.actions().isEmpty()) {
				Set<Long> origins = new java.util.LinkedHashSet<>();
				key.actions().values().forEach(candidates ->
					candidates.forEach(action -> origins.add(action.originValueId())));
				if(origins.size() == 1) {
					Lop marker = markers.get(new DefinitionKey(key.producer(), origins.iterator().next()));
					if(marker != null) materializerInput = marker;
				}
			}
			Lop materializer = createMaterializer(statementBlock.getSBID(), key, materializerInput);
			for(ResolvedSupply supply : entry.getValue()) {
				supply.consumer().replaceInput(supply.supply().inputPosition(), materializer);
				supply.producer().removeOutput(supply.consumer());
				materializer.addOutput(supply.consumer());
			}
			inserted.add(materializer);
		}
		// A definition marker is an ordering edge as well as a side effect. Inline native values
		// (for example a DataGen passed directly to a function) have no prior transient write to
		// order the marker before the call. Route every remaining direct use through the unique
		// marker so the callee inherits the exact source marker with the same CacheableData identity.
		for(Map.Entry<DefinitionKey, Lop> entry : markers.entrySet())
			rewireRemainingUsesThroughMarker(entry.getKey().producer(), entry.getValue(), markerUses.get(entry.getKey()));
		return List.copyOf(inserted);
	}

	private static List<PhysicalUse> validateMarkerUses(Lop producer, Set<Lop> exactPhysicalDAG) {
		List<PhysicalUse> uses = new ArrayList<>();
		for(Lop consumer : producer.getOutputs()) {
			if(!exactPhysicalDAG.contains(consumer))
				continue; // A caller/other compilation scope is not owned by this lowering invocation.
			int matches = 0;
			for(int input = 0; input < consumer.getInputs().size(); input++)
				if(consumer.getInput(input) == producer) {
					uses.add(new PhysicalUse(consumer, input));
					matches++;
				}
			if(matches == 0)
				throw new LopsException("Memo definition producer has a stale physical output edge: producer="
					+ producer.getHopID() + " consumer=" + consumer.getHopID());
		}
		return List.copyOf(uses);
	}

	private static void rewireRemainingUsesThroughMarker(Lop producer, Lop marker, List<PhysicalUse> uses) {
		Set<Lop> rewired = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PhysicalUse use : uses)
			if(use.consumer().getInput(use.inputPosition()) == producer) {
				use.consumer().replaceInput(use.inputPosition(), marker);
				rewired.add(use.consumer());
			}
		for(Lop consumer : rewired) {
			boolean stillUsesProducer = consumer.getInputs().stream().anyMatch(input -> input == producer);
			if(!stillUsesProducer)
				producer.removeOutput(consumer);
			if(!marker.getOutputs().contains(consumer))
				marker.addOutput(consumer);
		}
	}

	private static ResolvedSupply resolve(List<Lop> lops, Map<Long, Hop> logical,
		StatementBlock statementBlock, Supply supply) {
		Hop producerHop = logical.get(supply.producerHopId());
		Hop consumerHop = logical.get(supply.consumerHopId());
		if(producerHop == null || consumerHop == null)
			throw new LopsException("Memo supply references a stale Hop: " + supply);
		if(supply.inputPosition() >= consumerHop.getInput().size()
			|| consumerHop.getInput().get(supply.inputPosition()) != producerHop)
			throw new LopsException("Memo supply does not match the exact logical def-use edge: " + supply);

		Lop producer = producerHop.getLops();
		Lop consumer = consumerHop.getLops();
		if(producer == null || consumer == null || !lops.contains(producer) || !lops.contains(consumer))
			throw new LopsException("Memo supply references a Lop eliminated from this compilation: " + supply);
		if(supply.inputPosition() >= consumer.getInputs().size()
			|| consumer.getInput(supply.inputPosition()) != producer)
			throw new LopsException("Memo supply cannot preserve the exact physical input position: " + supply);
		if(!producer.getDataType().isMatrix() && !producer.getDataType().isFrame())
			throw new LopsException("Memo supply supports matrix and frame values only: " + supply);
		if(!supply.guarded()) {
			MemoSupplyRegistry.Recipe recipe = supply.recipes().get(0);
			boolean physicalFout = producer.getFederatedOutput() == FederatedOutput.FOUT;
			if(recipe.sourcePartition().federated() != physicalFout)
				throw new LopsException("Memo supply source layout disagrees with its physical Lop: " + supply);
		}
		List<Action> selectedActions = MemoPlanRegistry.hasPlan(statementBlock.getDMLProg())
			? MemoPlanRegistry.actions(statementBlock.getDMLProg(), statementBlock.getSBID(),
				supply.producerHopId(), supply.consumerHopId(), supply.inputPosition()) : List.of();
		Map<Integer, List<Action>> actions = new LinkedHashMap<>();
		for(Action action : selectedActions) {
			if(action.target() != supply.targetPartition())
				throw new LopsException("Memo planned action target disagrees with selected supply: " + supply);
			int index = supply.recipes().indexOf(action.recipe());
			if(index < 0)
				throw new LopsException("Memo planned action does not identify one exact selected recipe: " + supply);
			List<Action> candidates=actions.computeIfAbsent(index,ignored -> new ArrayList<>());
			if(candidates.contains(action))
				throw new LopsException("Memo planned action is duplicated for one exact selected recipe: " + supply);
			candidates.add(action);
		}
		if(!actions.isEmpty())
			for(int i = 0; i < supply.recipes().size(); i++)
				if(supply.recipes().get(i).movement() != Movement.DIRECT && !actions.containsKey(i))
					throw new LopsException("Memo retained supply recipe has no selected planned action: " + supply);
		return new ResolvedSupply(supply, producer, consumer,
			new MaterializationKey(producer, supply.targetPartition(), supply.recipes(), actions));
	}

	private static Lop createMaterializer(long statementBlockId, MaterializationKey key, Lop producer) {
		Lop materializer;
		if(key.recipes().size() > 1 || !key.actions().isEmpty()) {
			materializer = FederatedSupply.withActionCandidates(
				producer, key.targetPartition(), key.recipes(), key.actions());
			materializer.setFederatedOutput(key.targetPartition() == MemoPartition.LOCAL
				? FederatedOutput.LOUT : FederatedOutput.FOUT);
		}
		else if(key.recipes().get(0).movement() == Movement.COLLECT) {
			materializer = new UnaryCP(producer, OpOp1.PREFETCH,
				producer.getDataType(), producer.getValueType(), ExecType.CP);
			materializer.setFederatedOutput(FederatedOutput.LOUT);
		}
		else {
			MemoSupplyRegistry.Recipe recipe = key.recipes().get(0);
			materializer = FederatedFoutMaterialize.forWorkerPool(producer,
				recipe.workerPoolDescriptor(), key.targetPartition().name());
			materializer.setFederatedOutput(FederatedOutput.FOUT);
		}
		materializer.setPlannerSyntheticActionKey(actionKey(statementBlockId, key));
		materializer.getOutputParameters().setLabel(Dag.getNextUniqueVarname(materializer.getDataType()));
		copyOutputParameters(materializer.getOutputParameters(), producer.getOutputParameters());
		return materializer;
	}

	private static String actionKey(long statementBlockId, MaterializationKey key) {
		return "memo-supply|sb=" + statementBlockId + "|producer=" + key.producer().getHopID()
			+ "|target=" + key.targetPartition() + "|recipes=" + FederatedSupply.encodeRecipes(key.recipes())
			+ "|origins=" + key.actions().entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getKey() + ":" + entry.getValue().stream()
					.map(action -> Long.toString(action.originValueId())).sorted()
					.collect(java.util.stream.Collectors.joining(",")))
				.collect(java.util.stream.Collectors.joining(";"));
	}

	private static void copyOutputParameters(OutputParameters target, OutputParameters source) {
		if(source == null)
			return;
		target.setDimensions(source.getNumRows(), source.getNumCols(), source.getBlocksize(),
			source.getNnz(), source.getUpdateType(), source.getCompressedSize());
		target.setFormat(source.getFormat());
	}

	private static Map<Long, Hop> exactLogicalHops(List<Hop> roots) {
		Map<Long, Hop> result = new LinkedHashMap<>();
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> pending = new ArrayDeque<>(roots);
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(hop == null || !visited.add(hop))
				continue;
			Hop prior = result.putIfAbsent(hop.getHopID(), hop);
			if(prior != null && prior != hop)
				throw new LopsException("Memo supply lowering found duplicate Hop id " + hop.getHopID() + '.');
			pending.addAll(hop.getInput());
		}
		return result;
	}

	private record MaterializationKey(Lop producer, MemoPartition targetPartition,
		List<MemoSupplyRegistry.Recipe> recipes, Map<Integer, List<Action>> actions) {
		private MaterializationKey {
			recipes = List.copyOf(recipes);
			Map<Integer,List<Action>> immutable=new LinkedHashMap<>();
			actions.forEach((index,candidates) -> immutable.put(index,List.copyOf(candidates)));
			actions = Map.copyOf(immutable);
		}
	}

	private record DefinitionKey(Lop producer, long originValueId) { }
	private record ResolvedDefinition(DefinitionSite site, Lop producer) { }
	private record PhysicalUse(Lop consumer, int inputPosition) { }

	private record ResolvedSupply(Supply supply, Lop producer, Lop consumer, MaterializationKey key) {
	}
}

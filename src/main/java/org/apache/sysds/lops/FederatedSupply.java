/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
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

package org.apache.sysds.lops;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;
import org.apache.sysds.runtime.instructions.InstructionUtils;

/** Runtime guard dispatch for an exact set of planner-selected supply recipes. */
public class FederatedSupply extends Lop {
	private final MemoPartition _target;
	private final List<Recipe> _recipes;
	private final Map<Integer, List<Action>> _actions;

	public FederatedSupply(Lop input, MemoPartition target, List<Recipe> recipes) {
		this(input, target, recipes, Map.of());
	}

	public FederatedSupply(Lop input, MemoPartition target, List<Recipe> recipes,
		Map<Integer, Action> actions) {
		this(input,target,recipes,singletonCandidates(actions),true);
	}

	private FederatedSupply(Lop input, MemoPartition target, List<Recipe> recipes,
		Map<Integer, List<Action>> actions, boolean candidates) {
		super(Type.FederatedSupply, input.getDataType(), input.getValueType());
		_target = target;
		_recipes = List.copyOf(recipes);
		_actions = validateActions(_recipes, target, actions);
		addInput(input);
		input.addOutput(this);
		setLevel();
		lps.setProperties(inputs, ExecType.FED);
	}

	public static FederatedSupply withActionCandidates(Lop input, MemoPartition target, List<Recipe> recipes,
		Map<Integer, List<Action>> actions) {
		return new FederatedSupply(input,target,recipes,actions,true);
	}

	@Override
	public String getInstructions(String input, String output) {
		String base = InstructionUtils.concatOperands("FED", "fed_supply",
			getInput(0).prepInputOperand(input), prepOutputOperand(output), _target.name(),
			InstructionUtils.createLiteralOperand(encodeRecipes(_recipes), ValueType.STRING));
		return _actions.isEmpty() ? base : InstructionUtils.concatOperands(base,
			InstructionUtils.createLiteralOperand(encodeActionCandidates(_actions), ValueType.STRING));
	}

	private static Map<Integer, List<Action>> validateActions(List<Recipe> recipes, MemoPartition target,
		Map<Integer, List<Action>> actions) {
		Map<Integer, List<Action>> result = new LinkedHashMap<>();
		for(Map.Entry<Integer, List<Action>> entry : actions.entrySet()) {
			int index = entry.getKey();
			List<Action> candidates=entry.getValue();
			if(index < 0 || index >= recipes.size() || candidates == null || candidates.isEmpty())
				throw new LopsException("Planned fed_supply action does not match its exact recipe.");
			for(Action action : candidates)
				if(action == null || action.target() != target || !action.recipe().equals(recipes.get(index)))
					throw new LopsException("Planned fed_supply action does not match its exact recipe.");
			if(new java.util.LinkedHashSet<>(candidates).size()!=candidates.size())
				throw new LopsException("Planned fed_supply action candidate is duplicated.");
			result.put(index,List.copyOf(candidates));
		}
		return Map.copyOf(result);
	}

	private static Map<Integer,List<Action>> singletonCandidates(Map<Integer,Action> actions) {
		Map<Integer,List<Action>> result=new LinkedHashMap<>();
		actions.forEach((index,action) -> result.put(index,List.of(action)));
		return result;
	}

	public static String encodeRecipes(List<Recipe> recipes) {
		List<String> encoded = new ArrayList<>(recipes.size());
		for(Recipe recipe : recipes)
			encoded.add(recipe.sourcePartition().name() + '@' + recipe.movement().name() + '@'
				+ (recipe.workerPoolDescriptor() == null ? "-" : recipe.workerPoolDescriptor()));
		return String.join(";", encoded);
	}

	public static List<Recipe> decodeRecipes(String encoded) {
		if(encoded == null || encoded.isBlank())
			throw new LopsException("fed_supply requires at least one recipe.");
		List<Recipe> recipes = new ArrayList<>();
		for(String item : encoded.split(";", -1)) {
			String[] fields = item.split("@", -1);
			if(fields.length != 3)
				throw new LopsException("Malformed fed_supply recipe.");
			try {
				recipes.add(new Recipe(MemoPartition.valueOf(fields[0]), Movement.valueOf(fields[1]),
					"-".equals(fields[2]) ? null : fields[2]));
			}
			catch(IllegalArgumentException ex) {
				throw new LopsException("Malformed fed_supply recipe.", ex);
			}
		}
		return List.copyOf(recipes);
	}

	public static String encodeActions(Map<Integer, Action> actions) {
		return encodeActionCandidates(singletonCandidates(actions));
	}

	public static String encodeActionCandidates(Map<Integer,List<Action>> actions) {
		List<String> encoded = new ArrayList<>();
		for(int index : actions.keySet().stream().sorted().toList())
		for(Action action : actions.get(index)) {
			String variant = Base64.getUrlEncoder().withoutPadding().encodeToString(
				action.loweringVariant().getBytes(StandardCharsets.UTF_8));
			encoded.add(index + "," + action.snapshot() + "," + action.originValueId() + ","
				+ action.target().name() + "," + action.retentionRegion() + "," + variant);
		}
		return String.join(";", encoded);
	}

	public static Map<Integer, Action> decodeActions(String encoded, List<Recipe> recipes) {
		Map<Integer,Action> result=new LinkedHashMap<>();
		for(var entry : decodeActionCandidates(encoded,recipes).entrySet()) {
			if(entry.getValue().size()!=1)
				throw new LopsException("Legacy planned fed_supply action has multiple candidates.");
			result.put(entry.getKey(),entry.getValue().get(0));
		}
		return Map.copyOf(result);
	}

	public static Map<Integer,List<Action>> decodeActionCandidates(String encoded, List<Recipe> recipes) {
		if(encoded == null || encoded.isBlank())
			throw new LopsException("Planned fed_supply requires at least one action.");
		Map<Integer, List<Action>> result = new LinkedHashMap<>();
		for(String item : encoded.split(";", -1)) {
			String[] fields = item.split(",", -1);
			if(fields.length != 6)
				throw new LopsException("Malformed planned fed_supply action.");
			try {
				int index = Integer.parseInt(fields[0]);
				if(index < 0 || index >= recipes.size())
					throw new IllegalArgumentException("invalid recipe index");
				String variant = new String(Base64.getUrlDecoder().decode(fields[5]), StandardCharsets.UTF_8);
				Action action = new Action(Long.parseLong(fields[1]), Long.parseLong(fields[2]),
					MemoPartition.valueOf(fields[3]), recipes.get(index), Long.parseLong(fields[4]), variant);
				List<Action> candidates=result.computeIfAbsent(index,ignored -> new ArrayList<>());
				if(candidates.contains(action)) throw new IllegalArgumentException("duplicate action candidate");
				candidates.add(action);
			}
			catch(IllegalArgumentException ex) {
				throw new LopsException("Malformed planned fed_supply action.", ex);
			}
		}
		Map<Integer,List<Action>> immutable=new LinkedHashMap<>();
		result.forEach((index,candidates) -> immutable.put(index,List.copyOf(candidates)));
		return Map.copyOf(immutable);
	}

	@Override
	public String toString() {
		return "FederatedSupply";
	}
}

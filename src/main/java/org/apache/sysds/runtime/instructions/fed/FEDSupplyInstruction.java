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

package org.apache.sysds.runtime.instructions.fed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.FederatedSupply;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheBlock;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Executes exactly one recipe from a planner-selected guarded supply. */
public class FEDSupplyInstruction extends FEDInstruction {
	private static final AtomicLong NEXT_IN_PLACE_TEMP = new AtomicLong();
	private final CPOperand _input;
	private final CPOperand _output;
	private final MemoPartition _target;
	private final List<Recipe> _recipes;
	private final Map<Integer, List<Action>> _actions;

	private FEDSupplyInstruction(CPOperand input, CPOperand output, MemoPartition target,
		List<Recipe> recipes, Map<Integer, List<Action>> actions, String opcode, String instruction) {
		super(FEDType.Supply, null, opcode, instruction,
			target == MemoPartition.LOCAL ? FederatedOutput.LOUT : FederatedOutput.FOUT);
		_input = input;
		_output = output;
		_target = target;
		_recipes = List.copyOf(recipes);
		_actions = Map.copyOf(actions);
	}

	public static FEDSupplyInstruction parseInstruction(String instruction) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(instruction);
		if(parts.length != 5 && parts.length != 6)
			throw new DMLRuntimeException("Invalid number of operands in fed_supply instruction: " + instruction);
		try {
			List<Recipe> recipes = FederatedSupply.decodeRecipes(new CPOperand(parts[4]).getName());
			return new FEDSupplyInstruction(new CPOperand(parts[1]), new CPOperand(parts[2]),
				MemoPartition.valueOf(parts[3]), recipes, parts.length == 6
					? FederatedSupply.decodeActionCandidates(new CPOperand(parts[5]).getName(), recipes) : Map.of(),
				parts[0], instruction);
		}
		catch(IllegalArgumentException ex) {
			throw new DMLRuntimeException("Invalid fed_supply instruction: " + instruction, ex);
		}
	}

	/** Execute one planner-selected boundary supply while retaining the variable name. */
	public static void supplyInPlace(ExecutionContext ec, long tid, String variable,
		DataType dataType, ValueType valueType, MemoPartition target, List<Recipe> recipes) {
		if(ec == null || variable == null || variable.isBlank())
			throw new DMLRuntimeException("In-place fed_supply requires an execution context and variable.");
		CacheableData<?> input = ec.getCacheableData(variable);
		if(input == null || input.getDataType() != dataType || input.getValueType() != valueType)
			throw new DMLRuntimeException("In-place fed_supply operand does not match runtime variable " + variable + '.');
		String temporary = "__memo_supply_" + NEXT_IN_PLACE_TEMP.incrementAndGet();
		while(ec.containsVariable(temporary))
			temporary = "__memo_supply_" + NEXT_IN_PLACE_TEMP.incrementAndGet();
		CacheableData<?> placeholder = input instanceof MatrixObject
			? new MatrixObject((MatrixObject) input) : new FrameObject((FrameObject) input);
		placeholder.setFedMapping(null);
		ec.setVariable(temporary, placeholder);
		try {
			FEDSupplyInstruction supply = new FEDSupplyInstruction(
				new CPOperand(variable, valueType, dataType), new CPOperand(temporary, valueType, dataType),
				target, recipes, Map.of(), "fed_supply", "memo boundary supply");
			supply.setTID(tid);
			supply.processInstruction(ec);
			Data result = ec.removeVariable(temporary);
			Data prior = ec.removeVariable(variable);
			if(prior != null && prior != result)
				ec.cleanupDataObject(prior);
			ec.setVariable(variable, result);
		}
		catch(RuntimeException ex) {
			Data failed = ec.removeVariable(temporary);
			if(failed != null && failed != input)
				ec.cleanupDataObject(failed);
			throw ex;
		}
	}

	@Override
	public String getOutputVariableName() {
		return _output.getName();
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		if(!_input.isMatrix() && !_input.isFrame() || _input.getDataType() != _output.getDataType())
			throw new DMLRuntimeException("fed_supply supports matching matrix or frame operands only.");
		CacheableData<?> input = ec.getCacheableData(_input);
		long[] shape = runtimeShape(input);
		SelectedRecipe selected = selectRecipe(input, shape[0], shape[1]);
		Recipe recipe = selected.recipe();
		validateMovement(recipe);
		if(!_actions.isEmpty()) {
			if(recipe.movement() == Movement.DIRECT) {
				if(_actions.containsKey(selected.index()))
					throw new DMLRuntimeException("DIRECT fed_supply cannot own a retained planned action.");
				publishAlias(ec, input);
				return;
			}
			List<Action> candidates = _actions.get(selected.index());
			if(candidates == null)
				throw new DMLRuntimeException("Selected fed_supply recipe has no exact planned action.");
			Action action=PlannedSupplyRuntime.requireMatchingAction(ec,candidates,input);
			CacheableData<?> materialized = PlannedSupplyRuntime.materialize(ec, action, input,
				(source, exactRecipe) -> materializeExact(ec, source, exactRecipe, shape[0], shape[1]));
			publishAlias(ec, materialized);
			return;
		}
		executeRecipe(ec, input, recipe, shape[0], shape[1]);
	}

	private void executeRecipe(ExecutionContext ec, CacheableData<?> input, Recipe recipe, long rows, long cols) {
		switch(recipe.movement()) {
			case DIRECT:
				publishAlias(ec, input);
				break;
			case COLLECT:
				// The GET_VAR responses used by acquireRead are authoritative for this
				// concrete worker variable. Its backing filepath can belong to an older
				// protected source even when a derived aggregate has been released.
				publishLocal(ec, input);
				break;
			case UPLOAD:
				CanonicalWorkerPool pool = CanonicalWorkerPool.parse(recipe.workerPoolDescriptor());
				FederationMap uploaded = FEDLocalMaterializeUtil.materializeLocalToPool(getTID(), input,
					pool, _target.runtimeType(pool.size()), rows, cols);
				publishFederated(ec, input, uploaded, rows, cols);
				break;
			default:
				throw new DMLRuntimeException("Unsupported fed_supply movement " + recipe.movement());
		}
	}

	private CacheableData<?> materializeExact(ExecutionContext ec, CacheableData<?> source,
		Recipe recipe, long rows, long cols) {
		String temporary = "__memo_planned_supply_" + NEXT_IN_PLACE_TEMP.incrementAndGet();
		while(ec.containsVariable(temporary))
			temporary = "__memo_planned_supply_" + NEXT_IN_PLACE_TEMP.incrementAndGet();
		CacheableData<?> placeholder = source instanceof MatrixObject
			? new MatrixObject((MatrixObject) source) : new FrameObject((FrameObject) source);
		placeholder.setFedMapping(null);
		ec.setVariable(temporary, placeholder);
		FEDSupplyInstruction exact = new FEDSupplyInstruction(_input,
			new CPOperand(temporary, source.getValueType(), source.getDataType()), _target,
			List.of(recipe), Map.of(), "fed_supply", "selected planned supply");
		exact.setTID(getTID());
		try {
			exact.executeRecipe(ec, source, recipe, rows, cols);
			Data result = ec.removeVariable(temporary);
			if(!(result instanceof CacheableData))
				throw new DMLRuntimeException("Selected planned supply did not create a cacheable output.");
			return (CacheableData<?>) result;
		}
		catch(RuntimeException ex) {
			Data failed = ec.removeVariable(temporary);
			if(failed != null && failed != source)
				ec.cleanupDataObject(failed);
			throw ex;
		}
	}

	private SelectedRecipe selectRecipe(CacheableData<?> input, long rows, long cols) {
		List<SelectedRecipe> matches = new ArrayList<>();
		for(int index = 0; index < _recipes.size(); index++) {
			Recipe recipe = _recipes.get(index);
			if(!input.isFederated()) {
				if(recipe.sourcePartition() == MemoPartition.LOCAL)
					matches.add(new SelectedRecipe(index, recipe));
				continue;
			}
			if(!recipe.sourcePartition().federated())
				continue;
			try {
				CanonicalWorkerPool pool = CanonicalWorkerPool.parse(recipe.workerPoolDescriptor());
				pool.validateIngress(input.getFedMapping(), input.getDataType(),
					recipe.sourcePartition().runtimeType(pool.size()), rows, cols);
				matches.add(new SelectedRecipe(index, recipe));
			}
			catch(DMLRuntimeException ignored) {
				// A nonmatching selected recipe is expected at a guarded branch join.
			}
		}
		if(matches.size() != 1)
			throw new DMLRuntimeException("fed_supply requires exactly one matching selected recipe but found "
				+ matches.size() + ".");
		return matches.get(0);
	}

	private record SelectedRecipe(int index, Recipe recipe) { }

	private void validateMovement(Recipe recipe) {
		MemoPartition source = recipe.sourcePartition();
		Movement expected = source == _target ? Movement.DIRECT
			: source == MemoPartition.LOCAL ? Movement.UPLOAD
			: _target == MemoPartition.LOCAL ? Movement.COLLECT : null;
		if(expected == null || expected != recipe.movement())
			throw new DMLRuntimeException("fed_supply recipe does not implement " + source + " -> " + _target + '.');
	}

	private void publishAlias(ExecutionContext ec, CacheableData<?> input) {
		Data displaced = ec.removeVariable(_output.getName());
		if(displaced != null && displaced != input)
			ec.cleanupDataObject(displaced);
		ec.setVariable(_output.getName(), input);
	}

	private void publishLocal(ExecutionContext ec, CacheableData<?> input) {
		if(input instanceof MatrixObject)
			ec.setMatrixOutput(_output.getName(), ((MatrixObject) input).acquireReadAndRelease());
		else
			ec.setFrameOutput(_output.getName(), ((FrameObject) input).acquireReadAndRelease());
	}

	private void publishFederated(ExecutionContext ec, CacheableData<?> input, FederationMap map,
		long rows, long cols) {
		CacheableData<?> output = ec.getCacheableData(_output);
		output.setFedMapping(map);
		if(input instanceof MatrixObject)
			output.getDataCharacteristics().set(rows, cols, input.getBlocksize(), ((MatrixObject) input).getNnz());
		else {
			output.getDataCharacteristics().set(rows, cols, input.getBlocksize());
			org.apache.sysds.common.Types.ValueType[] schema = ((FrameObject) input).getSchema();
			((FrameObject) output).setSchema(schema == null ? null : schema.clone());
		}
	}

	private static long[] runtimeShape(CacheableData<?> input) {
		long rows = input.getNumRows();
		long cols = input.getNumColumns();
		if(input.isFederated() && (rows <= 0 || cols <= 0)) {
			rows = input.getFedMapping().getMaxIndexInRange(0);
			cols = input.getFedMapping().getMaxIndexInRange(1);
		}
		else if(!input.isFederated() && (rows <= 0 || cols <= 0)) {
			CacheBlock<?> block = input.acquireRead();
			try {
				rows = block.getNumRows();
				cols = block.getNumColumns();
			}
			finally {
				input.release();
			}
		}
		if(rows <= 0 || cols <= 0)
			throw new DMLRuntimeException("fed_supply requires known positive runtime dimensions.");
		return new long[] {rows, cols};
	}

}

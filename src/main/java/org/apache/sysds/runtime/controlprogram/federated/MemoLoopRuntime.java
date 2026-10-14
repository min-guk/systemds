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
package org.apache.sysds.runtime.controlprogram.federated;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.collections4.map.AbstractReferenceMap.ReferenceStrength;
import org.apache.commons.collections4.map.ReferenceIdentityMap;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheBlock;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.fed.FEDSupplyInstruction;

/** Runtime enforcement of one selected, transfer-free loop backedge contract. */
public final class MemoLoopRuntime {
	public record Contract(String variable, DataType dataType, ValueType entryValueType, ValueType valueType,
		MemoPartition target, long entryRows, long entryColumns, long repeatRows, long repeatColumns,
		boolean entryRequired, boolean localResidencyApproved,
		List<Recipe> entryRecipes, CanonicalWorkerPool pool) {
		/** Compatibility constructor for contracts whose entry and repeat values have the same type. */
		public Contract(String variable, DataType dataType, ValueType valueType,
			MemoPartition target, long entryRows, long entryColumns, long repeatRows, long repeatColumns,
			boolean entryRequired, boolean localResidencyApproved,
			List<Recipe> entryRecipes, CanonicalWorkerPool pool) {
			this(variable,dataType,valueType,valueType,target,entryRows,entryColumns,repeatRows,repeatColumns,
				entryRequired,localResidencyApproved,entryRecipes,pool);
		}

		/** Compatibility constructor; privacy is converted only to the runtime's local-residency gate. */
		public Contract(String variable, DataType dataType, ValueType valueType,
			MemoPartition target, long entryRows, long entryColumns, long repeatRows, long repeatColumns,
			boolean entryRequired, Privacy privacy, List<Recipe> entryRecipes, CanonicalWorkerPool pool) {
			this(variable,dataType,valueType,valueType,target,entryRows,entryColumns,repeatRows,repeatColumns,
				entryRequired,!unreleased(Objects.requireNonNull(privacy,"privacy")),entryRecipes,pool);
		}

		/** Backward-compatible contract where entry and repeat values have the same required shape. */
		public Contract(String variable, DataType dataType, ValueType valueType,
			MemoPartition target, long rows, long columns, Privacy privacy,
			List<Recipe> entryRecipes, CanonicalWorkerPool pool) {
			this(variable, dataType, valueType, target, rows, columns, rows, columns, true,
				privacy, entryRecipes, pool);
		}

		public Contract {
			if(variable == null || variable.isEmpty())
				throw new IllegalArgumentException("A loop contract requires an exact variable name.");
			Objects.requireNonNull(dataType, "dataType");
			Objects.requireNonNull(entryValueType, "entryValueType");
			Objects.requireNonNull(valueType, "valueType");
			if(entryValueType == ValueType.UNKNOWN || valueType == ValueType.UNKNOWN)
				throw new IllegalArgumentException("Loop entry and repeat types must be exact.");
			Objects.requireNonNull(target, "target");
			entryRecipes = List.copyOf(Objects.requireNonNull(entryRecipes, "entryRecipes"));
			if(entryRequired && entryRecipes.isEmpty())
				throw new IllegalArgumentException("A loop contract requires its selected entry supply.");
			if(entryValueType != valueType && !(entryRequired && dataType == DataType.SCALAR
				&& entryValueType == ValueType.INT64 && valueType == ValueType.FP64))
				throw new IllegalArgumentException("Unsupported loop entry/repeat type transition "
					+ entryValueType + " -> " + valueType + '.');
			if(dataType == DataType.SCALAR) {
				if(target != MemoPartition.LOCAL || entryRows >= 0 || entryColumns >= 0
					|| repeatRows >= 0 || repeatColumns >= 0)
					throw new IllegalArgumentException("A scalar loop contract is LOCAL and has no matrix shape.");
			}
			else if(dataType == DataType.MATRIX || dataType == DataType.FRAME) {
				if(repeatRows <= 0 || repeatColumns <= 0)
					throw new IllegalArgumentException("Matrix/frame loop contracts require a known positive repeat shape.");
				if(entryRequired && (entryRows <= 0 || entryColumns <= 0))
					throw new IllegalArgumentException("A required matrix/frame loop entry needs a known positive shape.");
				if(!entryRequired && !((entryRows == -1 && entryColumns == -1)
					|| (entryRows > 0 && entryColumns > 0)))
					throw new IllegalArgumentException("An unused loop entry shape must be positive or fully unknown.");
			}
			else
				throw new IllegalArgumentException("Unsupported loop-carried data type " + dataType + '.');
			if(target == MemoPartition.LOCAL && !localResidencyApproved)
				throw new IllegalArgumentException("LOCAL loop-carried residency requires a planner proof.");
			if(!entryRecipes.isEmpty())
				validateRecipes(target, entryRecipes, pool, dataType);
		}

		/** Compatibility accessors: the loop's steady-state shape is its repeat/backedge shape. */
		public long rows() { return repeatRows; }
		public long columns() { return repeatColumns; }
	}

	public static final class Scope implements AutoCloseable {
		private final ExecutionContext _ec;
		private final List<Contract> _contracts;
		private long _completedIterations;
		private boolean _closed;

		private Scope(ExecutionContext ec, List<Contract> contracts) {
			_ec = ec;
			_contracts = contracts;
		}

		/** Records one completed body and validates Next -> Head only when the next iteration is taken. */
		public void iterationCompleted(boolean continues) {
			if(_closed)
				throw new DMLRuntimeException("Loop contract scope is already closed.");
			_completedIterations++;
			if(continues)
				for(Contract contract : _contracts)
					validateValue(_ec, contract, "backedge");
		}

		/** Records normal loop termination; zero/one/many cardinalities are all legal. */
		public void complete() {
			if(_closed)
				throw new DMLRuntimeException("Loop contract scope is already closed.");
		}

		@Override
		public void close() {
			_closed = true;
			// The final objects stay bound under their original variable names. Exit
			// supplies, if selected, execute later through the ordinary supply model.
		}
	}

	private static final Map<StatementBlock,List<Contract>> CONTRACTS =
		Collections.synchronizedMap(new ReferenceIdentityMap<>(ReferenceStrength.WEAK,ReferenceStrength.HARD));
	private static final AtomicLong ENTRY_TRANSFERS = new AtomicLong();
	private static final AtomicLong BACKEDGE_TRANSFERS = new AtomicLong();

	private MemoLoopRuntime() {
	}

	public static void register(StatementBlock statementBlock, List<Contract> contracts) {
		Objects.requireNonNull(statementBlock, "statementBlock");
		List<Contract> immutable = validatedContracts(contracts);
		synchronized(CONTRACTS) {
			List<Contract> prior = CONTRACTS.get(statementBlock);
			if(prior != null && !prior.equals(immutable))
				throw new IllegalStateException("A different memo loop contract is already registered.");
			CONTRACTS.put(statementBlock, immutable);
		}
	}

	public static Map<StatementBlock,List<Contract>> snapshotAll() {
		synchronized(CONTRACTS) {
			return Collections.unmodifiableMap(new IdentityHashMap<>(CONTRACTS));
		}
	}

	public static Map<StatementBlock,List<Contract>> snapshot(org.apache.sysds.parser.DMLProgram owner) {
		if(owner==null) return Map.of();
		synchronized(CONTRACTS) {
			Map<StatementBlock,List<Contract>> result=new IdentityHashMap<>();
			CONTRACTS.forEach((block,contracts) -> {
				if(block!=null && block.getDMLProg()==owner) result.put(block,contracts);
			});
			return Collections.unmodifiableMap(result);
		}
	}

	/** Atomically replaces all selected loop contracts after validating the full batch. */
	public static void replaceAll(Map<StatementBlock,? extends List<Contract>> contracts) {
		Objects.requireNonNull(contracts, "contracts");
		Map<StatementBlock,List<Contract>> validated = new IdentityHashMap<>();
		for(Map.Entry<StatementBlock,? extends List<Contract>> entry : contracts.entrySet())
			validated.put(Objects.requireNonNull(entry.getKey(), "statementBlock"),
				validatedContracts(entry.getValue()));
		synchronized(CONTRACTS) {
			CONTRACTS.clear();
			CONTRACTS.putAll(validated);
		}
	}

	/** Atomically replaces only contracts owned by one exact program. */
	public static void replaceForOwner(org.apache.sysds.parser.DMLProgram owner,
		Map<StatementBlock,? extends List<Contract>> contracts) {
		Objects.requireNonNull(owner,"program owner");
		Objects.requireNonNull(contracts,"contracts");
		Map<StatementBlock,List<Contract>> validated=new IdentityHashMap<>();
		for(var entry:contracts.entrySet()) {
			StatementBlock block=Objects.requireNonNull(entry.getKey(),"statementBlock");
			if(block.getDMLProg()!=owner)
				throw new IllegalArgumentException("Loop contract statement block belongs to another program");
			validated.put(block,validatedContracts(entry.getValue()));
		}
		synchronized(CONTRACTS) {
			CONTRACTS.entrySet().removeIf(entry -> entry.getKey()!=null && entry.getKey().getDMLProg()==owner);
			CONTRACTS.putAll(validated);
		}
	}

	public static void remove(org.apache.sysds.parser.DMLProgram owner) {
		if(owner==null) return;
		synchronized(CONTRACTS) {
			CONTRACTS.entrySet().removeIf(entry -> entry.getKey()!=null && entry.getKey().getDMLProg()==owner);
		}
	}

	private static List<Contract> validatedContracts(List<Contract> contracts) {
		List<Contract> immutable = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
		Set<String> variables = new LinkedHashSet<>();
		for(Contract contract : immutable) {
			Objects.requireNonNull(contract, "contract");
			if(!variables.add(contract.variable()))
				throw new IllegalArgumentException("Duplicate loop-carried variable " + contract.variable() + '.');
		}
		return immutable;
	}

	public static void clear() {
		CONTRACTS.clear();
		ENTRY_TRANSFERS.set(0);
		BACKEDGE_TRANSFERS.set(0);
	}

	public static Scope enter(StatementBlock statementBlock, ExecutionContext ec, long tid) {
		Objects.requireNonNull(ec, "executionContext");
		List<Contract> contracts;
		boolean registered;
		synchronized(CONTRACTS) {
			registered = statementBlock != null && CONTRACTS.containsKey(statementBlock);
			contracts = registered ? CONTRACTS.get(statementBlock) : List.of();
		}
		// Resolve every guarded entry before changing any carried value. Retain the
		// exact recipe so validation is not repeated after the first mutation.
		Recipe[] selectedRecipes = new Recipe[contracts.size()];
		for(int position = 0; position < contracts.size(); position++)
			if(contracts.get(position).entryRequired())
				selectedRecipes[position] = selectEntryRecipe(ec, contracts.get(position));
		for(int position = 0; position < contracts.size(); position++) {
			Contract contract = contracts.get(position);
			if(!contract.entryRequired())
				continue;
			Recipe selected = selectedRecipes[position];
			if(selected.movement() != Movement.DIRECT) {
				FEDSupplyInstruction.supplyInPlace(ec, tid, contract.variable(), contract.dataType(),
					contract.entryValueType(), contract.target(), List.of(selected));
				ENTRY_TRANSFERS.incrementAndGet();
			}
			validateEntryValue(ec, contract);
		}
		return new Scope(ec, contracts);
	}

	/** True for an explicitly registered Memo loop, including one with no carried values. */
	public static boolean isSelected(StatementBlock statementBlock) {
		synchronized(CONTRACTS) {
			return statementBlock != null && CONTRACTS.containsKey(statementBlock);
		}
	}

	public static long entryTransfers() { return ENTRY_TRANSFERS.get(); }
	public static long backedgeTransfers() { return BACKEDGE_TRANSFERS.get(); }

	private static Recipe selectEntryRecipe(ExecutionContext ec, Contract contract) {
		Data value = ec.getVariable(contract.variable());
		if(value == null)
			throw new DMLRuntimeException("Missing loop-carried variable " + contract.variable() + '.');
		if(value.getDataType() != contract.dataType() || value.getValueType() != contract.entryValueType())
			throw new DMLRuntimeException("Loop entry type mismatch for " + contract.variable() + '.');
		if(contract.dataType() != DataType.SCALAR) {
			long[] shape = runtimeShape((CacheableData<?>) value);
			if(shape[0] != contract.entryRows() || shape[1] != contract.entryColumns())
				throw new DMLRuntimeException("Loop entry shape mismatch for " + contract.variable()
					+ ": expected " + contract.entryRows() + 'x' + contract.entryColumns() + " but found "
					+ shape[0] + 'x' + shape[1] + '.');
		}
		MemoPartition actual = actualPartition(value, contract);
		List<Recipe> matches = contract.entryRecipes().stream()
			.filter(recipe -> recipe.sourcePartition() == actual).toList();
		if(matches.size() != 1)
			throw new DMLRuntimeException("Loop entry requires exactly one selected recipe for "
				+ contract.variable() + " but found " + matches.size() + '.');
		return matches.get(0);
	}

	private static void validateEntryValue(ExecutionContext ec, Contract contract) {
		validateValue(ec, contract, "entry", contract.entryValueType(),
			contract.entryRows(), contract.entryColumns());
	}

	private static void validateValue(ExecutionContext ec, Contract contract, String boundary) {
		validateValue(ec, contract, boundary, contract.valueType(),
			contract.repeatRows(), contract.repeatColumns());
	}

	private static void validateValue(ExecutionContext ec, Contract contract, String boundary,
		ValueType expectedValueType, long expectedRows, long expectedColumns) {
		Data value = ec.getVariable(contract.variable());
		if(value == null)
			throw new DMLRuntimeException("Missing loop-carried variable at " + boundary + ": "
				+ contract.variable());
		if(value.getDataType() != contract.dataType() || value.getValueType() != expectedValueType)
			throw new DMLRuntimeException("Loop-carried type changed at " + boundary + ": "
				+ contract.variable());
		if(contract.dataType() == DataType.SCALAR)
			return;
		CacheableData<?> cacheable = (CacheableData<?>) value;
		long[] shape = runtimeShape(cacheable);
		if(shape[0] != expectedRows || shape[1] != expectedColumns)
			throw new DMLRuntimeException("Loop-carried shape changed at " + boundary + ": "
				+ contract.variable() + " expected " + expectedRows + 'x' + expectedColumns
				+ " but found " + shape[0] + 'x' + shape[1] + '.');
		if(contract.target() == MemoPartition.LOCAL) {
			if(cacheable.isFederated())
				throw new DMLRuntimeException("Loop-carried layout changed at " + boundary + ": "
					+ contract.variable() + " expected LOCAL.");
		}
		else {
			if(!cacheable.isFederated())
				throw new DMLRuntimeException("Loop-carried layout changed at " + boundary + ": "
					+ contract.variable() + " expected " + contract.target() + '.');
			contract.pool().validateIngress(cacheable.getFedMapping(), contract.dataType(),
				contract.target().runtimeType(contract.pool().size()), expectedRows, expectedColumns);
		}
	}

	private static MemoPartition actualPartition(Data value, Contract contract) {
		if(contract.dataType() == DataType.SCALAR)
			return MemoPartition.LOCAL;
		CacheableData<?> cacheable = (CacheableData<?>) value;
		if(!cacheable.isFederated()) return MemoPartition.LOCAL;
		if(contract.pool() == null)
			throw new DMLRuntimeException("A federated loop entry requires its canonical worker pool.");
		long[] shape = runtimeShape(cacheable);
		for(MemoPartition partition : List.of(MemoPartition.ROW, MemoPartition.COL, MemoPartition.REP)) {
			try {
				contract.pool().validateIngress(cacheable.getFedMapping(), contract.dataType(),
					partition.runtimeType(contract.pool().size()), shape[0], shape[1]);
				return partition.normalize(contract.pool().size());
			}
			catch(DMLRuntimeException ignored) {
				// Try the next canonical persistent layout.
			}
		}
		throw new DMLRuntimeException("Loop entry is not on the selected canonical worker pool: "
			+ contract.variable());
	}

	private static long[] runtimeShape(CacheableData<?> input) {
		long rows = input.getNumRows();
		long columns = input.getNumColumns();
		if(input.isFederated() && (rows <= 0 || columns <= 0)) {
			rows = input.getFedMapping().getMaxIndexInRange(0);
			columns = input.getFedMapping().getMaxIndexInRange(1);
		}
		else if(!input.isFederated() && (rows <= 0 || columns <= 0)) {
			CacheBlock<?> block = input.acquireRead();
			try {
				rows = block.getNumRows();
				columns = block.getNumColumns();
			}
			finally { input.release(); }
		}
		if(rows <= 0 || columns <= 0)
			throw new DMLRuntimeException("Loop-carried matrix/frame requires known positive runtime dimensions.");
		return new long[] {rows, columns};
	}

	private static void validateRecipes(MemoPartition target, List<Recipe> recipes,
		CanonicalWorkerPool pool, DataType dataType) {
		boolean requiresPool = target.federated() || recipes.stream().anyMatch(recipe ->
			recipe.sourcePartition().federated() || recipe.movement() == Movement.UPLOAD);
		if(requiresPool && pool == null)
			throw new IllegalArgumentException("A federated loop entry requires its canonical worker pool.");
		Set<MemoPartition> sources = new LinkedHashSet<>();
		for(Recipe recipe : recipes) {
			if(!sources.add(recipe.sourcePartition()))
				throw new IllegalArgumentException("Ambiguous loop entry recipe for " + recipe.sourcePartition() + '.');
			Movement expected = recipe.sourcePartition() == target ? Movement.DIRECT
				: recipe.sourcePartition() == MemoPartition.LOCAL ? Movement.UPLOAD
				: target == MemoPartition.LOCAL ? Movement.COLLECT : null;
			if(expected == null || recipe.movement() != expected)
				throw new IllegalArgumentException("Illegal loop entry movement " + recipe.sourcePartition()
					+ " -> " + target + " using " + recipe.movement() + '.');
			if(dataType == DataType.SCALAR && (recipe.sourcePartition() != MemoPartition.LOCAL
				|| recipe.movement() != Movement.DIRECT))
				throw new IllegalArgumentException("Scalar loop entry is LOCAL/DIRECT.");
			if(recipe.workerPoolDescriptor() != null
				&& (pool == null || !pool.encode().equals(recipe.workerPoolDescriptor())))
				throw new IllegalArgumentException("Loop entry recipe uses a different worker pool.");
		}
	}

	private static boolean unreleased(Privacy privacy) {
		return privacy == Privacy.PRIVATE || privacy == Privacy.PRIVATE_AGGREGATE;
	}
}

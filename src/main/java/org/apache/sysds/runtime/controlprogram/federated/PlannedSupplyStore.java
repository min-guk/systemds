/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.runtime.controlprogram.federated;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.meta.DataCharacteristics;

/**
 * Invocation-scoped storage for materializations explicitly selected by a Memo plan.
 * This class does not discover actions or choose alternate recipes. A caller registers the
 * selected static actions at region entry and invokes exactly one recipe on first actual demand.
 */
public final class PlannedSupplyStore {
	private static final AtomicLong INVOCATIONS = new AtomicLong();
	private final Map<CacheableData<?>, SharedLease> leases = new IdentityHashMap<>();

	public record Statistics(long materializations,long reuses,int liveLeases,int pinnedSources) { }
	private long materializations;
	private long reuses;
	public synchronized Statistics statistics() {
		return new Statistics(materializations,reuses,leases.size(),activePinnedSourceCount());
	}
	private synchronized void recordMaterialization() { materializations++; }
	private synchronized void recordReuse() { reuses++; }

	public record Action(long snapshot, long originValueId, MemoPartition target, Recipe recipe,
		long retentionRegion, String loweringVariant) {
		public Action {
			if(snapshot < 0 || originValueId < 0 || retentionRegion < 0)
				throw new IllegalArgumentException("Planned supply identity requires non-negative ids");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(recipe, "recipe");
			if(loweringVariant == null || loweringVariant.isBlank())
				throw new IllegalArgumentException("Planned supply identity requires a lowering variant");
			if(recipe.movement() == Movement.DIRECT)
				throw new IllegalArgumentException("DIRECT supply has no retained materialization");
			Movement expected = recipe.sourcePartition() == target ? Movement.DIRECT
				: recipe.sourcePartition() == MemoPartition.LOCAL ? Movement.UPLOAD
				: target == MemoPartition.LOCAL ? Movement.COLLECT : null;
			if(expected != recipe.movement())
				throw new IllegalArgumentException("Recipe does not implement its planned target: "
					+ recipe.sourcePartition() + " -> " + target);
		}
	}

	@FunctionalInterface
	public interface Materializer {
		CacheableData<?> materialize(CacheableData<?> source, Recipe selectedRecipe);
	}

	public Scope beginScope(ExecutionContext context, long snapshot, long retentionRegion,
		Collection<Action> selectedActions) {
		return beginScope(null, context, snapshot, retentionRegion, selectedActions);
	}

	public Scope beginChildScope(Scope parent, ExecutionContext context, long retentionRegion,
		Collection<Action> selectedActions) {
		Objects.requireNonNull(parent, "parent");
		if(parent.store != this)
			throw new IllegalArgumentException("Parent scope belongs to another planned supply store");
		return beginScope(parent, context, parent.snapshot, retentionRegion, selectedActions);
	}

	private Scope beginScope(Scope parent, ExecutionContext context, long snapshot, long retentionRegion,
		Collection<Action> selectedActions) {
		if(snapshot < 0 || retentionRegion < 0)
			throw new IllegalArgumentException("Planned supply scope requires non-negative ids");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(selectedActions, "selectedActions");
		Set<Action> selected = new LinkedHashSet<>();
		for(Action action : selectedActions) {
			Objects.requireNonNull(action, "selected action");
			if(action.snapshot() != snapshot || action.retentionRegion() != retentionRegion)
				throw new IllegalArgumentException("Selected action belongs to another snapshot or region");
			if(!selected.add(action))
				throw new IllegalArgumentException("Selected action is registered more than once: " + action);
		}
		Scope scope = new Scope(this, parent, context, snapshot, retentionRegion,
			INVOCATIONS.incrementAndGet(), Set.copyOf(selected));
		if(parent != null)
			parent.addChild(scope);
		return scope;
	}

	public CacheableData<?> getOrCreate(Scope scope, Action action, long definitionEpoch,
		CacheableData<?> source, Materializer materializer) {
		return getOrCreate(scope, scope.context, action, definitionEpoch, source, materializer);
	}

	public CacheableData<?> getOrCreate(Scope scope, ExecutionContext demandContext, Action action,
		long definitionEpoch, CacheableData<?> source, Materializer materializer) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(demandContext, "demand context");
		if(scope.store != this)
			throw new IllegalArgumentException("Scope belongs to another planned supply store");
		return scope.getOrCreate(demandContext, action, definitionEpoch, source, materializer);
	}

	synchronized int activePinnedSourceCount() {
		return (int) leases.values().stream().filter(lease -> lease.sourceReferences > 0).count();
	}

	private synchronized void pinSource(ExecutionContext context, CacheableData<?> source) {
		retain(context, source, true);
	}

	private synchronized void unpinSource(ExecutionContext context, CacheableData<?> source) {
		release(context, source, true);
	}

	private synchronized void retainOutput(ExecutionContext context, CacheableData<?> output) {
		retain(context, output, false);
	}

	private synchronized void releaseOutput(ExecutionContext context, CacheableData<?> output) {
		release(context, output, false);
	}

	private void retain(ExecutionContext context, CacheableData<?> data, boolean source) {
		SharedLease lease = leases.get(data);
		if(lease == null) {
			lease = new SharedLease(data.isCleanupEnabled());
			leases.put(data, lease);
			data.enableCleanup(false);
		}
		lease.references++;
		if(source) lease.sourceReferences++;
		lease.contexts.merge(context, 1, Integer::sum);
	}

	private void release(ExecutionContext context, CacheableData<?> data, boolean source) {
		SharedLease lease = leases.get(data);
		if(lease == null || lease.references <= 0 || source && lease.sourceReferences <= 0)
			throw new IllegalStateException("Planned supply object lease is unbalanced");
		ExecutionContext cleanupContext = lease.contexts.keySet().stream()
			.filter(candidate -> candidate.getVariables().hasReferences(data)).findFirst().orElse(context);
		int contextReferences = lease.contexts.getOrDefault(context, 0);
		if(contextReferences <= 1) lease.contexts.remove(context);
		else lease.contexts.put(context, contextReferences - 1);
		if(source) lease.sourceReferences--;
		if(--lease.references == 0) {
			leases.remove(data);
			data.enableCleanup(lease.originalCleanup);
			cleanupContext.cleanupDataObject(data);
		}
	}

	public static final class Scope implements AutoCloseable {
		private final PlannedSupplyStore store;
		private final Scope parent;
		private final ExecutionContext context;
		private final long snapshot;
		private final long retentionRegion;
		private final long invocation;
		private final Set<Action> selected;
		private final Map<Action, Slot> slots = new LinkedHashMap<>();
		private final Set<Scope> children = Collections.newSetFromMap(new IdentityHashMap<>());
		private boolean closed;

		private Scope(PlannedSupplyStore store, Scope parent, ExecutionContext context, long snapshot,
			long retentionRegion, long invocation, Set<Action> selected) {
			this.store = store;
			this.parent = parent;
			this.context = context;
			this.snapshot = snapshot;
			this.retentionRegion = retentionRegion;
			this.invocation = invocation;
			this.selected = selected;
		}

		public long invocationToken() {
			return invocation;
		}

		public long retentionRegion() {
			return retentionRegion;
		}

		private synchronized void addChild(Scope child) {
			requireOpen();
			children.add(child);
		}

		private synchronized CacheableData<?> getOrCreate(ExecutionContext demandContext,
			Action action, long definitionEpoch,
			CacheableData<?> source, Materializer materializer) {
			requireOpen();
			Objects.requireNonNull(action, "action");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(materializer, "materializer");
			if(definitionEpoch < 0)
				throw new IllegalArgumentException("Dynamic definition epoch must be non-negative");
			if(!selected.contains(action) || action.snapshot() != snapshot
				|| action.retentionRegion() != retentionRegion)
				throw new DMLRuntimeException("Demand references an unselected planned supply action");

			SourceKey sourceKey = SourceKey.capture(source);
			validatePartition(source, action.recipe().sourcePartition(), action.recipe(), sourceKey.shape());
			RuntimeKey runtimeKey = new RuntimeKey(invocation, definitionEpoch, sourceKey);
			Slot prior = slots.get(action);
			if(prior != null && prior.key().equals(runtimeKey)) {
				if(prior.failure() != null)
					throw prior.failure();
				store.recordReuse();
				return prior.output();
			}
			// Pin the replacement before releasing the prior slot. This prevents a same-object
			// definition epoch change from transiently enabling cleanup between the two values.
			store.pinSource(demandContext, source);
			if(prior != null)
				releaseSlot(prior);
			CacheableData<?> created = null;
			try {
				CacheableData<?> output = created = Objects.requireNonNull(
					materializer.materialize(source, action.recipe()), "materializer output");
				if(output == source)
					throw new DMLRuntimeException("A retained movement must create a distinct output object");
				if(slots.values().stream().anyMatch(slot -> slot.output() == output))
					throw new DMLRuntimeException("Two planned supply actions cannot own the same output object");
				SourceShape outputShape = SourceShape.capture(output);
				if(!sourceKey.shape().sameLogicalShape(outputShape))
					throw new DMLRuntimeException("Planned supply changed the logical value shape");
				validatePartition(output, action.target(), action.recipe(), outputShape);
				store.retainOutput(demandContext, output);
				slots.put(action, new Slot(runtimeKey, demandContext, source, output, null));
				store.recordMaterialization();
				return output;
			}
			catch(RuntimeException failure) {
				if(created != null && created != source) {
					try {
						demandContext.cleanupDataObject(created);
					}
					catch(RuntimeException cleanup) {
						failure.addSuppressed(cleanup);
					}
				}
				DMLRuntimeException planned = failure instanceof DMLRuntimeException
					? (DMLRuntimeException) failure
					: new DMLRuntimeException("Selected planned supply materialization failed", failure);
				slots.put(action, new Slot(runtimeKey, demandContext, source, null, planned));
				throw planned;
			}
		}

		private void releaseSlot(Slot slot) {
			try {
				releaseOutput(slot.context(), slot.output());
			}
			finally {
				store.unpinSource(slot.context(), slot.source());
			}
		}

		private void releaseOutput(ExecutionContext outputContext, CacheableData<?> output) {
			if(output == null)
				return;
			store.releaseOutput(outputContext, output);
		}

		private void requireOpen() {
			if(closed)
				throw new IllegalStateException("Planned supply scope is closed");
		}

		@Override
		public synchronized void close() {
			if(closed)
				return;
			if(!children.isEmpty())
				throw new IllegalStateException("Nested planned supply scopes must close before their parent");
			RuntimeException failure = null;
			for(Slot slot : slots.values()) {
				try {
					releaseSlot(slot);
				}
				catch(RuntimeException ex) {
					if(failure == null) failure = ex; else failure.addSuppressed(ex);
				}
			}
			slots.clear();
			closed = true;
			if(parent != null)
				parent.childClosed(this);
			if(failure != null)
				throw failure;
		}

		private synchronized void childClosed(Scope child) {
			children.remove(child);
		}
	}

	private record RuntimeKey(long invocation, long definitionEpoch, SourceKey source) {
	}

	private record Slot(RuntimeKey key, ExecutionContext context, CacheableData<?> source, CacheableData<?> output,
		DMLRuntimeException failure) {
	}

	private static final class SharedLease {
		private final boolean originalCleanup;
		private final Map<ExecutionContext, Integer> contexts = new IdentityHashMap<>();
		private int references;
		private int sourceReferences;

		private SharedLease(boolean originalCleanup) {
			this.originalCleanup = originalCleanup;
		}
	}

	private static final class SourceKey {
		private final CacheableData<?> source;
		private final long uniqueId;
		private final long mutationVersion;
		private final SourceShape shape;
		private final FederationUtils.FederatedValueIdentity federatedIdentity;

		private SourceKey(CacheableData<?> source, long uniqueId, long mutationVersion,
			SourceShape shape, FederationUtils.FederatedValueIdentity federatedIdentity) {
			this.source = source;
			this.uniqueId = uniqueId;
			this.mutationVersion = mutationVersion;
			this.shape = shape;
			this.federatedIdentity = federatedIdentity;
		}

		private static SourceKey capture(CacheableData<?> source) {
			return new SourceKey(source, source.getUniqueID(), source.getMutationVersion(),
				SourceShape.capture(source), source.isFederated()
					? FederationUtils.federatedValueIdentity(source.getFedMapping()) : null);
		}

		private SourceShape shape() {
			return shape;
		}

		@Override
		public boolean equals(Object other) {
			if(this == other) return true;
			if(!(other instanceof SourceKey that)) return false;
			return source == that.source && uniqueId == that.uniqueId
				&& mutationVersion == that.mutationVersion && shape.equals(that.shape)
				&& Objects.equals(federatedIdentity, that.federatedIdentity);
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(source);
			result = 31 * result + Long.hashCode(uniqueId);
			result = 31 * result + Long.hashCode(mutationVersion);
			result = 31 * result + shape.hashCode();
			return 31 * result + Objects.hashCode(federatedIdentity);
		}
	}

	private record SourceShape(DataType dataType, long rows, long columns, int blocksize, long nonZeros) {
		private static SourceShape capture(CacheableData<?> data) {
			DataCharacteristics characteristics = data.getDataCharacteristics();
			if(characteristics == null || characteristics.getRows() <= 0 || characteristics.getCols() <= 0)
				throw new DMLRuntimeException("Planned supply requires known positive runtime dimensions");
			return new SourceShape(data.getDataType(), characteristics.getRows(), characteristics.getCols(),
				characteristics.getBlocksize(), characteristics.getNonZeros());
		}

		private boolean sameLogicalShape(SourceShape other) {
			return dataType == other.dataType && rows == other.rows && columns == other.columns;
		}
	}

	private static void validatePartition(CacheableData<?> data, MemoPartition partition, Recipe recipe,
		SourceShape shape) {
		if(partition == MemoPartition.LOCAL) {
			if(data.isFederated())
				throw new DMLRuntimeException("Planned LOCAL value is federated at runtime");
			return;
		}
		if(!data.isFederated())
			throw new DMLRuntimeException("Planned FED value is local at runtime");
		CanonicalWorkerPool pool = CanonicalWorkerPool.parse(recipe.workerPoolDescriptor());
		pool.validateIngress(data.getFedMapping(), shape.dataType(),
			partition.runtimeType(pool.size()), shape.rows(), shape.columns());
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.runtime.controlprogram.federated;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopeKind;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopePlan;
import org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.SupplySchedule;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;

/** Explicit runtime session for a committed selected supply schedule. */
public final class PlannedSupplyRuntime {
	private PlannedSupplyRuntime() { }

	public static Execution open(DMLProgram owner, ExecutionContext context) {
		if(owner == null || !MemoPlanRegistry.hasPlan(owner)) return new Execution(context, null);
		return new Execution(context, new Session(MemoPlanRegistry.epoch(owner), MemoPlanRegistry.schedule(owner)));
	}

	static Execution open(ExecutionContext context, long snapshot, SupplySchedule schedule) {
		return new Execution(context, new Session(snapshot, schedule));
	}

	public static Region enter(ExecutionContext context, ScopeKind kind, long statementBlockId) {
		Session session = context.getPlannedSupplySession();
		return session == null ? Region.EMPTY : session.enter(context, kind, statementBlockId);
	}

	public static void markDefinition(ExecutionContext context, long originValueId, CacheableData<?> source) {
		Session session = requireSession(context);
		session.markDefinition(context, originValueId, source);
	}

	public static CacheableData<?> materialize(ExecutionContext context, PlannedSupplyStore.Action action,
		CacheableData<?> source, PlannedSupplyStore.Materializer materializer) {
		return requireSession(context).materialize(context, action, source, materializer);
	}

	/** Selects one explicit planned origin using the current definition marker and source identity. */
	public static PlannedSupplyStore.Action requireMatchingAction(ExecutionContext context,
		List<PlannedSupplyStore.Action> candidates, CacheableData<?> source) {
		return requireSession(context).requireMatchingAction(context,candidates,source);
	}

	/** Shares the exact selected-plan session with a newly created function execution context. */
	public static Attachment attachFunctionContext(ExecutionContext caller, ExecutionContext callee) {
		Session session = caller.getPlannedSupplySession();
		return session == null ? Attachment.EMPTY : new Attachment(callee, session, session.attach(caller, callee));
	}

	/** Exports only explicitly scheduled native definitions after the caller bound every return value. */
	public static void exportFunctionReturns(ExecutionContext caller, ExecutionContext callee, long snapshot,
		Map<Integer, Long> definitions, Map<Integer, CacheableData<?>> returnedValues) {
		exportFunctionReturns(caller, callee, snapshot, definitions, definitions, returnedValues);
	}

	public static void exportFunctionReturns(ExecutionContext caller, ExecutionContext callee, long snapshot,
		Map<Integer, ?> sourceDefinitions, Map<Integer, Long> targetDefinitions,
		Map<Integer, CacheableData<?>> returnedValues) {
		commitFunctionReturns(prepareFunctionReturns(caller, callee, snapshot,
			sourceDefinitions, targetDefinitions, returnedValues));
	}

	/**
	 * Validates a function-return export without changing the caller frame. The function-call
	 * instruction uses this before replacing caller variables, lineage, or cleanup ownership.
	 */
	public static PreparedFunctionReturns prepareFunctionReturns(ExecutionContext caller,
		ExecutionContext callee, long snapshot, Map<Integer, ?> sourceDefinitions,
		Map<Integer, Long> targetDefinitions, Map<Integer, CacheableData<?>> returnedValues) {
		Session session = requireSession(caller);
		if(callee.getPlannedSupplySession() != session)
			throw new DMLRuntimeException("Function return contexts do not share one selected supply session");
		return session.prepareFunctionReturns(caller, callee, snapshot,
			normalizeReturnCandidates(sourceDefinitions), targetDefinitions, returnedValues);
	}

	/** Commits one previously validated export. No candidate matching is repeated at commit. */
	public static void commitFunctionReturns(PreparedFunctionReturns prepared) {
		if(prepared == null)
			throw new DMLRuntimeException("Missing prepared function return export");
		prepared.session.commitFunctionReturns(prepared);
	}

	public static final class PreparedFunctionReturns {
		private final Session session;
		private final Session.Frame caller;
		private final Map<Long, CacheableData<?>> exports;
		private boolean committed;
		private PreparedFunctionReturns(Session session, Session.Frame caller,
			Map<Long, CacheableData<?>> exports) {
			this.session = session;
			this.caller = caller;
			this.exports = Map.copyOf(exports);
		}
	}

	private static Map<Integer, List<Long>> normalizeReturnCandidates(Map<Integer, ?> definitions) {
		if(definitions == null)
			return null;
		Map<Integer, List<Long>> candidates = new LinkedHashMap<>();
		for(Map.Entry<Integer, ?> entry : definitions.entrySet()) {
			Object value = entry.getValue();
			List<Long> origins;
			if(value instanceof Long origin)
				origins = List.of(origin);
			else if(value instanceof List<?> list) {
				origins = new ArrayList<>(list.size());
				for(Object item : list)
					if(item instanceof Long origin) origins.add(origin);
					else throw new DMLRuntimeException("Invalid planned function return origin candidate");
			}
			else throw new DMLRuntimeException("Invalid planned function return origin candidate");
			candidates.put(entry.getKey(), List.copyOf(origins));
		}
		return Map.copyOf(candidates);
	}

	private static Session requireSession(ExecutionContext context) {
		Session session = context.getPlannedSupplySession();
		if(session == null) throw new DMLRuntimeException("No active selected supply runtime session");
		return session;
	}

	public static final class Execution implements AutoCloseable {
		private final ExecutionContext context;
		private final Session prior;
		private final Session installed;
		private final Region root;
		private boolean closed;
		private Execution(ExecutionContext context, Session installed) {
			this.context = context; this.installed = installed; prior = context.getPlannedSupplySession();
			if(installed != null) {
				context.setPlannedSupplySession(installed);
				installed.installRoot(context);
				root = installed.enter(context, ScopeKind.PROGRAM_ROOT, -1);
			}
			else root = Region.EMPTY;
		}
		@Override public void close() {
			if(closed) return;
			if(installed != null) {
				root.close();
				installed.close(context);
			}
			context.setPlannedSupplySession(prior); closed = true;
		}
	}

	public static final class Attachment implements AutoCloseable {
		private static final Attachment EMPTY = new Attachment(null, null, 0);
		private final ExecutionContext context;
		private final Session session;
		private final int inheritedDepth;
		private final Session prior;
		private boolean closed;
		private Attachment(ExecutionContext context, Session session, int inheritedDepth) {
			this.context = context; this.session = session; this.inheritedDepth = inheritedDepth;
			prior = context == null ? null : context.getPlannedSupplySession();
			if(context != null) context.setPlannedSupplySession(session);
		}
		@Override public void close() {
			if(closed) return;
			if(session != null) {
				session.detach(context, inheritedDepth);
				context.setPlannedSupplySession(prior);
			}
			closed = true;
		}
	}

	public static final class Region implements AutoCloseable {
		private static final Region EMPTY = new Region(null, null, List.of());
		private final Session session;
		private final ExecutionContext context;
		private final List<PlannedSupplyStore.Scope> scopes;
		private boolean closed;
		private Region(Session session, ExecutionContext context, List<PlannedSupplyStore.Scope> scopes) {
			this.session = session; this.context = context; this.scopes = scopes;
		}
		@Override public void close() {
			if(closed) return;
			for(int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close();
			if(session != null) session.leave(context, scopes);
			closed = true;
		}
	}

	public static final class Session {
		private final long snapshot;
		private final SupplySchedule schedule;
		private final PlannedSupplyStore store = new PlannedSupplyStore();
		private final Map<ExecutionContext, Frame> frames = new IdentityHashMap<>();
		private boolean closed;
		private Session(long snapshot, SupplySchedule schedule) { this.snapshot = snapshot; this.schedule = schedule; }
		/** Completed sessions retain counters but no source/output leases. */
		public PlannedSupplyStore.Statistics statistics() { return store.statistics(); }

		private synchronized void installRoot(ExecutionContext context) {
			requireOpen();
			if(frames.put(context, new Frame()) != null)
				throw new IllegalStateException("Planned supply root context is already installed");
		}

		private synchronized int attach(ExecutionContext caller, ExecutionContext callee) {
			requireOpen();
			Frame parent = requireFrame(caller);
			if(caller == callee || frames.containsKey(callee))
				throw new IllegalStateException("Planned supply function context is already attached");
			Frame child = new Frame(parent);
			frames.put(callee, child);
			return child.active.size();
		}

		private synchronized void detach(ExecutionContext context, int inheritedDepth) {
			Frame frame = requireFrame(context);
			if(frame.active.size() != inheritedDepth)
				throw new IllegalStateException("Planned supply function scopes remain active at return");
			frames.remove(context);
		}

		private synchronized Region enter(ExecutionContext context, ScopeKind kind, long statementBlockId) {
			requireOpen();
			Frame frame = requireFrame(context);
			List<PlannedSupplyStore.Scope> opened = new ArrayList<>();
			for(ScopePlan plan : schedule.scopes()) if(plan.kind() == kind && plan.statementBlockId() == statementBlockId) {
				PlannedSupplyStore.Scope parent = frame.active.peekLast();
				PlannedSupplyStore.Scope scope = parent == null
					? store.beginScope(context, snapshot, plan.regionId(), plan.actions())
					: store.beginChildScope(parent, context, plan.regionId(), plan.actions());
				frame.active.addLast(scope); opened.add(scope);
			}
			return opened.isEmpty() ? Region.EMPTY : new Region(this, context, List.copyOf(opened));
		}

		private synchronized void leave(ExecutionContext context, List<PlannedSupplyStore.Scope> scopes) {
			Frame frame = requireFrame(context);
			for(int i = scopes.size() - 1; i >= 0; i--)
				if(frame.active.pollLast() != scopes.get(i))
					throw new IllegalStateException("Planned supply runtime scopes closed out of order");
		}

		private synchronized void markDefinition(ExecutionContext context, long origin, CacheableData<?> source) {
			requireOpen();
			Frame frame = requireFrame(context);
			frame.definitions.put(origin, source);
			frame.epochs.merge(origin, 1L, Long::sum);
			frame.localDefinitions.add(origin);
		}

		private synchronized PreparedFunctionReturns prepareFunctionReturns(ExecutionContext callerContext,
			ExecutionContext calleeContext, long expectedSnapshot, Map<Integer, List<Long>> sourceDefinitions,
			Map<Integer, Long> targetDefinitions, Map<Integer, CacheableData<?>> returnedValues) {
			requireOpen();
			if(expectedSnapshot != snapshot || sourceDefinitions == null || sourceDefinitions.isEmpty()
				|| targetDefinitions == null || returnedValues == null
				|| !targetDefinitions.keySet().equals(sourceDefinitions.keySet())
				|| !returnedValues.keySet().equals(sourceDefinitions.keySet()))
				throw new DMLRuntimeException("Function return export does not match the selected Memo plan");
			Frame caller = requireFrame(callerContext);
			Frame callee = requireFrame(calleeContext);
			Map<Long, CacheableData<?>> exports = new LinkedHashMap<>();
			for(Map.Entry<Integer, List<Long>> entry : sourceDefinitions.entrySet()) {
				Long targetOrigin = targetDefinitions.get(entry.getKey());
				CacheableData<?> value = returnedValues.get(entry.getKey());
				List<Long> matches = entry.getValue() == null ? List.of() : entry.getValue().stream()
					.filter(origin -> origin != null && origin >= 0 && callee.localDefinitions.contains(origin)
						&& callee.definitions.get(origin) == value).toList();
				if(targetOrigin == null || targetOrigin < 0 || value == null || matches.size() != 1)
					throw new DMLRuntimeException("Function return has no matching callee-native definition marker: output="
						+ entry.getKey() + " candidates=" + entry.getValue() + " matches=" + matches.size());
				CacheableData<?> prior = exports.putIfAbsent(targetOrigin, value);
				if(prior != null && prior != value)
					throw new DMLRuntimeException("One caller return origin identifies different output objects");
			}
			return new PreparedFunctionReturns(this, caller, exports);
		}

		private synchronized void commitFunctionReturns(PreparedFunctionReturns prepared) {
			if(prepared.session != this || prepared.committed)
				throw new DMLRuntimeException("Invalid or already committed function return export");
			for(Map.Entry<Long, CacheableData<?>> export : prepared.exports.entrySet()) {
				prepared.caller.definitions.put(export.getKey(), export.getValue());
				prepared.caller.epochs.merge(export.getKey(), 1L, Long::sum);
				prepared.caller.localDefinitions.add(export.getKey());
			}
			prepared.committed = true;
		}

		private synchronized CacheableData<?> materialize(ExecutionContext context, PlannedSupplyStore.Action action,
			CacheableData<?> source, PlannedSupplyStore.Materializer materializer) {
			requireOpen();
			Frame frame = requireFrame(context);
			if(action.snapshot() != snapshot || frame.definitions.get(action.originValueId()) != source)
				throw new DMLRuntimeException("Planned supply demand has no current exact native definition");
			PlannedSupplyStore.Scope scope = null;
			for(var iterator = frame.active.descendingIterator(); iterator.hasNext();) {
				PlannedSupplyStore.Scope candidate = iterator.next();
				if(candidate.retentionRegion() == action.retentionRegion()) {
					scope = candidate;
					break;
				}
			}
			if(scope == null)
				throw new DMLRuntimeException("Planned supply retention scope is not active");
			return store.getOrCreate(scope, context, action, frame.epochs.get(action.originValueId()), source, materializer);
		}

		private synchronized PlannedSupplyStore.Action requireMatchingAction(ExecutionContext context,
			List<PlannedSupplyStore.Action> candidates,CacheableData<?> source) {
			requireOpen();
			if(candidates==null || candidates.isEmpty() || source==null)
				throw new DMLRuntimeException("Planned supply requires explicit action candidates and a source");
			Frame frame=requireFrame(context);
			List<PlannedSupplyStore.Action> matches=new ArrayList<>();
			for(PlannedSupplyStore.Action candidate : candidates) {
				if(candidate==null || candidate.snapshot()!=snapshot)
					throw new DMLRuntimeException("Planned supply action belongs to a stale plan epoch");
				if(frame.definitions.get(candidate.originValueId())==source) matches.add(candidate);
			}
			if(matches.size()!=1)
				throw new DMLRuntimeException("Planned supply requires exactly one matching explicit origin but found "
					+matches.size());
			return matches.get(0);
		}

		private Frame requireFrame(ExecutionContext context) {
			Frame frame = frames.get(context);
			if(frame == null) throw new IllegalStateException("Execution context is not attached to the planned supply session");
			return frame;
		}

		private synchronized void requireOpen() {
			if(closed) throw new IllegalStateException("Planned supply runtime session is closed");
		}
		private synchronized void close(ExecutionContext root) {
			if(closed) return;
			Frame frame = requireFrame(root);
			if(!frame.active.isEmpty() || frames.size() != 1)
				throw new IllegalStateException("Planned supply regions or function contexts remain active");
			frames.clear();
			closed = true;
		}

		private static final class Frame {
			private final ArrayDeque<PlannedSupplyStore.Scope> active;
			private final Map<Long, Long> epochs;
			private final Map<Long, CacheableData<?>> definitions;
			private final Set<Long> localDefinitions;
			private Frame() {
				active = new ArrayDeque<>(); epochs = new HashMap<>(); definitions = new HashMap<>();
				localDefinitions = new LinkedHashSet<>();
			}
			private Frame(Frame parent) {
				active = new ArrayDeque<>(parent.active);
				epochs = new HashMap<>(parent.epochs);
				definitions = new HashMap<>(parent.definitions);
				localDefinitions = new LinkedHashSet<>();
			}
		}
	}
}

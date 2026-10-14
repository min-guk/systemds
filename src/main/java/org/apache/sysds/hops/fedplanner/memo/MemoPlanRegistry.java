/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.apache.commons.collections4.map.AbstractReferenceMap.ReferenceStrength;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.LopsException;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;

/** Durable, exact authority for one committed memo plan and its recompile clones. */
public final class MemoPlanRegistry {
	// Explicit planner lifecycle owns removal; an identity map avoids both equals-aliasing and the
	// key-through-value retention trap of WeakHashMap<DMLProgram, Plan(owner)>.
	private static final Map<DMLProgram, Plan> PLANS = Collections.synchronizedMap(
		new ReferenceIdentityMap<>(ReferenceStrength.WEAK,ReferenceStrength.HARD));
	private static final Map<Hop,DMLProgram> HOP_OWNERS = Collections.synchronizedMap(
		new ReferenceIdentityMap<>(ReferenceStrength.WEAK,ReferenceStrength.WEAK));
	private static final Set<DMLProgram> ACTIVE_RECOMPILES = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final Set<DMLProgram> ACTIVE_PLANNINGS = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final AtomicLong EPOCHS = new AtomicLong();
	private static final ThreadLocal<Map<Long, List<Supply>>> RECOMPILE_SUPPLIES = new ThreadLocal<>();
	private static final ThreadLocal<SupplySchedule> RECOMPILE_SCHEDULE = new ThreadLocal<>();

	private MemoPlanRegistry() {
	}

	private record Shape(DataType dataType, ValueType valueType, long rows, long columns, boolean exact) {
	}

	private record OccurrenceShape(int occurrenceId, long statementBlockId, Shape shape) { }

	private record Selection(long hopId, Set<Long> statementBlockIds, ExecType exec, MemoPartition output,
		String hopClass, String opcode, int inputCount, DataType dataType, ValueType valueType,
		List<OccurrenceShape> shapes) {
	}

	static record CompilerState(ExecType forced,ExecType execution,FederatedOutput output,
		boolean derived,boolean prefetch,boolean selected) {
		static CompilerState capture(Hop hop) {
			return new CompilerState(hop.getForcedExecType(),hop.getExecType(),hop.getFederatedOutput(),
				hop.isFederatedOutputDerived(),hop.prefetchActivated(),hop.isPlannerPlacementSelected());
		}
		void restore(Hop hop) {
			hop.setForcedExecType(forced); hop.setExecType(execution); hop.setFederatedOutput(output);
			hop.setFederatedOutputDerived(derived); hop.setPlannerPlacementSelected(selected); hop.setLops(null);
			if(prefetch) hop.activatePrefetch(); else hop.deactivatePrefetch();
		}
	}

	private record Plan(long epoch, Map<Long, Selection> selections,
		Map<Long, List<Supply>> supplies, SupplySchedule schedule,Map<Long,Long> parForIterations,
		Map<Long,CompilerState> originals) {
	}

	public enum ScopeKind { PROGRAM_ROOT, FUNCTION_INVOCATION, SAME_SB, LOOP_INVOCATION, LOOP_ITERATION }
	public record ScopePlan(ScopeKind kind, long statementBlockId, long regionId, List<Action> actions) {
		public ScopePlan {
			Objects.requireNonNull(kind); actions = List.copyOf(actions);
			boolean root = kind == ScopeKind.PROGRAM_ROOT;
			if((root ? statementBlockId != -1 : statementBlockId < 0) || regionId < 0)
				throw new IllegalArgumentException("Invalid supply scope ids");
		}
	}
	public record DefinitionSite(long statementBlockId, long producerHopId, long originValueId) { }
	public record ReturnDefinitionSite(long statementBlockId, long callHopId,
		int outputPosition, long sourceOriginValueId, long targetOriginValueId) {
		public ReturnDefinitionSite(long statementBlockId, long callHopId,
			int outputPosition, long originValueId) {
			this(statementBlockId, callHopId, outputPosition, originValueId, originValueId);
		}

		/** Compatibility alias for callers that only modeled one origin identity. */
		public long originValueId() { return sourceOriginValueId; }
	}
	public record ActionEdge(long statementBlockId, long producerHopId, long consumerHopId,
		int inputPosition, Action action) { }
	public record SupplySchedule(List<ScopePlan> scopes, List<DefinitionSite> definitions,
		List<ActionEdge> edges, List<ReturnDefinitionSite> returns) {
		public SupplySchedule(List<ScopePlan> scopes, List<DefinitionSite> definitions,
			List<ActionEdge> edges) {
			this(scopes, definitions, edges, List.of());
		}
		public SupplySchedule {
			scopes = List.copyOf(scopes); definitions = List.copyOf(definitions); edges = List.copyOf(edges);
			returns = List.copyOf(returns);
		}
		public static SupplySchedule empty() { return new SupplySchedule(List.of(), List.of(), List.of()); }
		public boolean isEmpty() {
			return scopes.isEmpty() && definitions.isEmpty() && edges.isEmpty() && returns.isEmpty();
		}
	}

	/**
	 * Atomically validates and publishes a selected memo plan. The selected array may also contain
	 * loop-boundary variables after the compiled-owner prefix; those are intentionally ignored here.
	 */
	public static long publish(DMLProgram owner, MemoProgramFacts facts, RuleMemo memo, int[] selected,
		MemoShapes shapes, Map<Long, List<Supply>> supplies) {
		return publish(owner, facts, memo, selected, shapes, supplies, ignored -> SupplySchedule.empty());
	}

	public static long publish(DMLProgram owner, MemoProgramFacts facts, RuleMemo memo, int[] selected,
		MemoShapes shapes, Map<Long, List<Supply>> supplies, LongFunction<SupplySchedule> scheduleFactory) {
		return publish(owner,facts,memo,selected,shapes,supplies,scheduleFactory,Map.of());
	}

	static long publish(DMLProgram owner, MemoProgramFacts facts, RuleMemo memo, int[] selected,
		MemoShapes shapes, Map<Long, List<Supply>> supplies, LongFunction<SupplySchedule> scheduleFactory,
		Map<Long,CompilerState> originalStates) {
		Objects.requireNonNull(owner, "program owner");
		Objects.requireNonNull(facts, "program facts");
		Objects.requireNonNull(memo, "rule memo");
		Objects.requireNonNull(selected, "selected rules");
		Objects.requireNonNull(shapes, "memo shapes");
		Objects.requireNonNull(supplies, "selected supplies");
		Objects.requireNonNull(scheduleFactory, "supply schedule factory");
		Objects.requireNonNull(originalStates,"original compiler states");
		facts.requireComplete();
		if(selected.length < facts.compiledOwnerCount() || memo.ownerCount() != facts.compiledOwnerCount())
			throw new IllegalArgumentException("Memo selection does not cover every compiled owner");

		Map<Long, Selection> selections = new LinkedHashMap<>();
		Map<Long,Hop> selectedHops=new LinkedHashMap<>();
		for(int ownerId = 0; ownerId < facts.compiledOwnerCount(); ownerId++) {
			final int compiledOwner = ownerId;
			Hop hop = facts.compiledOwnerHop(compiledOwner);
			if(!hop.isPlannerPlacementSelected())
				continue; // uncalled function template has no committed physical occurrence
			Set<Long> statementBlocks = new LinkedHashSet<>();
			facts.occurrences().stream().filter(value -> value.compiledOwnerId() == compiledOwner)
				.forEach(value -> statementBlocks.add(value.statementBlockId()));
			if(statementBlocks.isEmpty())
				throw new IllegalStateException("Missing occurrence for compiled owner " + compiledOwner);
			MemoPartition output = null;
			if(!memo.rules(compiledOwner).isEmpty()) {
				int ordinal = selected[compiledOwner];
				if(ordinal < 0 || ordinal >= memo.rules(compiledOwner).size())
					throw new IllegalArgumentException("Invalid selected rule " + ordinal + " for owner " + compiledOwner);
				output = memo.rule(compiledOwner, ordinal).output();
			}
			else if(hop.getFederatedOutput() == FederatedOutput.LOUT)
				output = MemoPartition.LOCAL;
			else if(hop.getFederatedOutput() == FederatedOutput.FOUT)
				output = MemoPartition.REP; // only federated-vs-local is encoded by a transparent Hop
			List<OccurrenceShape> occurrenceShapes = facts.occurrences().stream()
				.filter(value -> value.compiledOwnerId() == compiledOwner)
				.map(value -> {
					MemoShapes.Shape memoShape = shapes.shapeAt(value.id());
					return new OccurrenceShape(value.id(), value.statementBlockId(), new Shape(
						memoShape.dataType(), memoShape.valueType(), memoShape.rows(), memoShape.columns(),
						memoShape.exact()));
				}).toList();
			for(OccurrenceShape occurrenceShape : occurrenceShapes) {
				Shape value = occurrenceShape.shape();
				if((value.dataType().isMatrix() || value.dataType().isFrame()) && value.exact()
					&& (value.rows() < 0 || value.columns() < 0))
					throw new IllegalStateException("MemoShapes marked an unknown matrix/frame occurrence exact: owner="
						+ ownerId + " hop=" + hop.getHopID() + " op=" + hop.getOpString() + " occurrence="
						+ occurrenceShape.occurrenceId() + " sb=" + occurrenceShape.statementBlockId()
						+ " shape=" + value.rows() + 'x' + value.columns());
			}
			ExecType projectedExec = hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
			if(projectedExec == null)
				throw new IllegalStateException("Projected memo owner has no execution type: " + hop.getHopID());
			Selection selection = new Selection(hop.getHopID(), Set.copyOf(statementBlocks), projectedExec,
				output, hop.getClass().getName(), hop.getOpString(), hop.getInput().size(),
				hop.getDataType(), hop.getValueType(), occurrenceShapes);
			validateSelectedHop(selection, hop, "publish", -1);
			if(selections.put(hop.getHopID(), selection) != null)
				throw new IllegalStateException("Two memo owners share Hop id " + hop.getHopID());
			selectedHops.put(hop.getHopID(),hop);
			if(hop instanceof org.apache.sysds.hops.FunctionOp function
				&& function.getFunctionType()==org.apache.sysds.hops.FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				&& function.getOutputs()!=null && !memo.rules(compiledOwner).isEmpty()) {
				RuleMemo.Rule rule=memo.rule(compiledOwner,selected[compiledOwner]);
				if(rule.outputs().size()!=function.getOutputs().size())
					throw new IllegalStateException("Selected multi-return output arity changed: "+function.getFunctionKey());
				for(int slot=0;slot<function.getOutputs().size();slot++) {
					Hop result=function.getOutputs().get(slot);
					boolean exact=!result.getDataType().isMatrix() && !result.getDataType().isFrame()
						|| result.getDim1()>=0 && result.getDim2()>=0;
					List<OccurrenceShape> resultShapes=statementBlocks.stream().map(block ->
						new OccurrenceShape(facts.occurrenceIds(hop).get(0),block,new Shape(result.getDataType(),
							result.getValueType(),result.getDim1(),result.getDim2(),exact))).toList();
					ExecType resultExec=result.getForcedExecType()!=null ? result.getForcedExecType() : result.getExecType();
					if(resultExec==null) throw new IllegalStateException("Projected multi-return output has no execution type: "
						+result.getHopID());
					Selection resultSelection=new Selection(result.getHopID(),Set.copyOf(statementBlocks),resultExec,
						rule.outputs().get(slot),result.getClass().getName(),result.getOpString(),result.getInput().size(),
						result.getDataType(),result.getValueType(),resultShapes);
					validateSelectedHop(resultSelection,result,"publish",-1);
					if(selections.put(result.getHopID(),resultSelection)!=null)
						throw new IllegalStateException("Multi-return output shares Hop id "+result.getHopID());
					selectedHops.put(result.getHopID(),result);
				}
			}
		}
		Map<Long, List<Supply>> immutableSupplies = immutableSupplies(supplies);
		long epoch = EPOCHS.incrementAndGet();
		SupplySchedule schedule = validateSchedule(epoch,
			Objects.requireNonNull(scheduleFactory.apply(epoch), "supply schedule"), immutableSupplies, selections);
		Map<Long,CompilerState> originals=new LinkedHashMap<>();
		Map<Long,Long> parForIterations=new LinkedHashMap<>();
		for(MemoProgramFacts.LoopRecord loop:facts.loops()) {
			StatementBlock block=facts.statementBlock(loop.statementBlockId());
			if(!(block instanceof org.apache.sysds.parser.ParForStatementBlock)) continue;
			if(facts.callFramesForRegion(loop.regionId()).isEmpty()) continue;
			if(loop.executionCases().size()!=1 || loop.executionCases().get(0).exactIterations()<0
				|| loop.executionCases().get(0).exactIterations()>1)
				throw new IllegalStateException("Selected Memo PARFOR lacks an exact zero/one cardinality proof: "
					+ loop.statementBlockId());
			parForIterations.put(loop.statementBlockId(),(long) loop.executionCases().get(0).exactIterations());
		}
		Plan prior=plan(owner);
		if(prior!=null) originals.putAll(prior.originals());
		for(var entry:selectedHops.entrySet()) originals.putIfAbsent(entry.getKey(),
			originalStates.getOrDefault(entry.getKey(),CompilerState.capture(entry.getValue())));
		Plan plan = new Plan(epoch, Map.copyOf(selections), immutableSupplies, schedule,
			Map.copyOf(parForIterations),Map.copyOf(originals));
		synchronized(MemoPlanRegistry.class) {
			if(ACTIVE_RECOMPILES.contains(owner))
				throw new StaleMemoPlanException("Cannot replace a memo plan during exact recompilation");
			MemoSupplyRegistry.replaceForOwner(owner,immutableSupplies);
			PLANS.put(owner, plan);
			for(Hop hop:selectedHops.values()) HOP_OWNERS.put(hop,owner);
		}
		return epoch;
	}

	public static SupplySchedule schedule(DMLProgram owner) {
		SupplySchedule scoped = RECOMPILE_SCHEDULE.get();
		return scoped != null ? scoped : requirePlan(owner).schedule();
	}

	public static List<Action> actions(DMLProgram owner, long statementBlockId, long producerHopId,
		long consumerHopId, int inputPosition) {
		return schedule(owner).edges().stream()
			.filter(edge -> edge.statementBlockId() == statementBlockId && edge.producerHopId() == producerHopId
				&& edge.consumerHopId() == consumerHopId && edge.inputPosition() == inputPosition)
			.map(ActionEdge::action).toList();
	}

	public static List<DefinitionSite> definitions(DMLProgram owner, long statementBlockId) {
		return schedule(owner).definitions().stream()
			.filter(site -> site.statementBlockId() == statementBlockId).toList();
	}

	public static List<ReturnDefinitionSite> returnDefinitions(DMLProgram owner, long statementBlockId) {
		return schedule(owner).returns().stream()
			.filter(site -> site.statementBlockId() == statementBlockId).toList();
	}

	private static SupplySchedule validateSchedule(long epoch, SupplySchedule schedule,
		Map<Long, List<Supply>> supplies, Map<Long, Selection> selections) {
		Map<Action, ScopePlan> scopes = new LinkedHashMap<>();
		Set<Long> scopeIds = new LinkedHashSet<>();
		for(ScopePlan scope : schedule.scopes()) {
			if(!scopeIds.add(scope.regionId()))
				throw new IllegalArgumentException("Duplicate planned supply retention region " + scope.regionId());
			for(Action action : scope.actions()) {
				if(action.snapshot() != epoch || action.retentionRegion() != scope.regionId())
					throw new IllegalArgumentException("Planned action snapshot/region does not match its scope");
				if(scopes.put(action, scope) != null)
					throw new IllegalArgumentException("Planned action belongs to more than one scope");
			}
		}
		Set<String> definitions = new LinkedHashSet<>();
		for(DefinitionSite site : schedule.definitions()) {
			Selection producer = selections.get(site.producerHopId());
			if(site.statementBlockId() < 0 || site.producerHopId() < 0 || site.originValueId() < 0
				|| producer == null || !producer.statementBlockIds().contains(site.statementBlockId())
				|| !definitions.add(site.statementBlockId() + ":" + site.originValueId()))
				throw new IllegalArgumentException("Invalid or duplicate planned definition site " + site);
		}
		Set<String> returnSites = new LinkedHashSet<>();
		Map<String, Long> returnTargets = new LinkedHashMap<>();
		for(ReturnDefinitionSite site : schedule.returns()) {
			Selection call = selections.get(site.callHopId());
			String outputKey = site.statementBlockId() + ":" + site.callHopId() + ':' + site.outputPosition();
			String key = outputKey + ':' + site.sourceOriginValueId();
			Long priorTarget = returnTargets.putIfAbsent(outputKey, site.targetOriginValueId());
			if(site.statementBlockId() < 0 || site.callHopId() < 0 || site.outputPosition() < 0
				|| site.sourceOriginValueId() < 0 || site.targetOriginValueId() < 0 || call == null
				|| !call.statementBlockIds().contains(site.statementBlockId()) || !returnSites.add(key)
				|| priorTarget != null && priorTarget != site.targetOriginValueId())
				throw new IllegalArgumentException("Invalid or duplicate planned function return site " + site
					+ ": selectedCall=" + call + " scheduledReturns=" + schedule.returns());
			if(!org.apache.sysds.hops.FunctionOp.class.getName().equals(call.hopClass()))
				throw new IllegalArgumentException("Planned function return site does not reference a FunctionOp " + site);
		}
		Set<Long> groundedOrigins = schedule.definitions().stream()
			.map(DefinitionSite::originValueId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		boolean changed;
		do {
			changed = false;
			for(ReturnDefinitionSite site : schedule.returns())
				if(groundedOrigins.contains(site.sourceOriginValueId()))
					changed |= groundedOrigins.add(site.targetOriginValueId());
		}
		while(changed);
		for(ReturnDefinitionSite site : schedule.returns())
			if(!groundedOrigins.contains(site.sourceOriginValueId())
				|| !groundedOrigins.contains(site.targetOriginValueId()))
				throw new IllegalArgumentException("Planned function return is not grounded by a native definition: " + site);
		Set<String> actionEdges = new LinkedHashSet<>();
		Set<Action> usedActions = new LinkedHashSet<>();
		for(ActionEdge edge : schedule.edges()) {
			ScopePlan scope = scopes.get(edge.action());
			String edgeId = edge.statementBlockId() + ":" + edge.producerHopId() + ':'
				+ edge.consumerHopId() + ':' + edge.inputPosition() + ':' + edge.action().recipe()
				+ ':' + edge.action().originValueId();
			if(scope == null || edge.statementBlockId() < 0 || edge.inputPosition() < 0
				|| !actionEdges.add(edgeId))
				throw new IllegalArgumentException("Planned action edge has no selected scope");
			boolean supply = supplies.getOrDefault(edge.statementBlockId(), List.of()).stream().anyMatch(value ->
				value.producerHopId() == edge.producerHopId() && value.consumerHopId() == edge.consumerHopId()
					&& value.inputPosition() == edge.inputPosition() && value.targetPartition() == edge.action().target()
					&& value.recipes().contains(edge.action().recipe()));
			if(!supply) throw new IllegalArgumentException("Planned action edge has no exact selected supply " + edge);
			if(!groundedOrigins.contains(edge.action().originValueId()))
				throw new IllegalArgumentException("Planned action has no grounded exact definition site");
			usedActions.add(edge.action());
		}
		if(!usedActions.equals(scopes.keySet()))
			throw new IllegalArgumentException("Planned supply scope contains an action with no selected demand edge");
		return schedule;
	}

	/**
	 * An independently selected native owner cannot be erased by a later Lop-only
	 * fusion. This is a lowering guard, not a new execution or placement decision.
	 * The normal compiler and legacy planners have no committed Memo selection.
	 */
	public static boolean preservesNativeKernel(Hop hop) {
		if(hop==null || !hop.isPlannerPlacementSelected()) return false;
		DMLProgram owner=HOP_OWNERS.get(hop);
		Plan plan=owner==null ? null : PLANS.get(owner);
		return plan!=null && (plan.selections().containsKey(hop.getPlannerOriginHopID())
			|| plan.selections().containsKey(hop.getHopID()));
	}

	public static boolean hasPlan(DMLProgram owner) {
		return owner != null && PLANS.containsKey(owner);
	}

	public static long epoch(DMLProgram owner) {
		Plan plan = plan(owner);
		return plan != null ? plan.epoch() : -1;
	}

	/** Exact compile-time cardinality for a selected Memo PARFOR, or {@code null} when it is not selected. */
	public static Long selectedParForIterations(DMLProgram owner,long statementBlockId) {
		Plan plan=plan(owner);
		return plan==null ? null : plan.parForIterations().get(statementBlockId);
	}

	static CompilerState originalState(DMLProgram owner,Hop hop) {
		Plan plan=plan(owner);
		return plan==null ? CompilerState.capture(hop)
			: plan.originals().getOrDefault(hop.getHopID(),CompilerState.capture(hop));
	}

	public static synchronized void remove(DMLProgram owner) {
		if(owner==null) return;
		if(ACTIVE_RECOMPILES.contains(owner) || ACTIVE_PLANNINGS.contains(owner))
			throw new StaleMemoPlanException("Cannot remove a memo plan during planning or exact recompilation");
		Plan removed=PLANS.remove(owner);
		if(removed!=null) synchronized(HOP_OWNERS) {
			var iterator=HOP_OWNERS.entrySet().iterator();
			while(iterator.hasNext()) {
				var entry=iterator.next();
				if(entry.getValue()!=owner) continue;
				Hop hop=entry.getKey();
				CompilerState state=removed.originals().get(hop.getHopID());
				if(state!=null) state.restore(hop);
				iterator.remove();
			}
		}
		MemoSupplyRegistry.remove(owner);
		org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime.remove(owner);
	}

	public static synchronized void clear() {
		for(DMLProgram owner:new ArrayList<>(PLANS.keySet())) remove(owner);
		PLANS.clear(); HOP_OWNERS.clear(); ACTIVE_RECOMPILES.clear(); ACTIVE_PLANNINGS.clear();
		RECOMPILE_SUPPLIES.remove();
		RECOMPILE_SCHEDULE.remove();
		MemoSupplyRegistry.clear();
		org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime.clear();
	}

	public static List<Supply> supplies(DMLProgram owner, long statementBlockId) {
		Map<Long, List<Supply>> scoped = RECOMPILE_SUPPLIES.get();
		return (scoped != null ? scoped : requirePlan(owner).supplies())
			.getOrDefault(statementBlockId, List.of());
	}

	public static int selectedHopCount(DMLProgram owner, List<Hop> roots) {
		Plan plan = requirePlan(owner);
		int count = 0;
		for(Hop hop : hopsById(roots).values())
			if(plan.selections().containsKey(hop.getPlannerOriginHopID())
				|| plan.selections().containsKey(hop.getHopID()))
				count++;
		return count;
	}

	/** Validate native selections and exact logical identities before memo-only Lop lowering. */
	public static void verifyLowering(StatementBlock block, List<Hop> roots, List<Lop> lops) {
		if(block == null || !hasPlan(block.getDMLProg()))
			return;
		if(roots == null || roots.isEmpty())
			throw new LopsException("Memo lowering requires its exact logical Hop roots");
		Plan plan = requirePlan(block.getDMLProg());
		Map<Long, Hop> logical = hopsById(roots);
		Set<Lop> lopSet = Collections.newSetFromMap(new IdentityHashMap<>());
		lopSet.addAll(lops);
		for(Hop hop : logical.values()) {
			Selection selected = plan.selections().get(hop.getPlannerOriginHopID());
			if(selected == null)
				selected = plan.selections().get(hop.getHopID());
			if(selected == null) {
				if(hop.isPlannerPlacementSelected())
					throw new LopsException("Memo-selected Hop has no committed registry identity: " + hop.getHopID());
				continue;
			}
			validateSelectedHop(selected, hop, "lowering", block.getSBID());
			Lop lop = hop.getLops();
			// Scalar literals are embedded in their consuming instruction operand and intentionally
			// have no independent Lop/instruction to validate.
			if(lop == null && hop instanceof LiteralOp)
				continue;
			// DataGen replaces a known rows/cols metadata operand with a literal Lop. Retain the
			// selected logical metadata Hop as authority, but require exact provenance for every
			// consumer so a shared executable use can never be omitted accidentally.
			if(lop == null && isEmbeddedDataGenDimension(hop,selected,plan,lopSet))
				continue;
			if(lop == null || !lopSet.contains(lop))
				throw new LopsException("Memo-selected Hop has no exact Lop in lowering: hop=" + hop.getHopID()
					+ " origin=" + selected.hopId() + " hopType=" + hop.getClass().getSimpleName()
					+ " opcode=" + hop.getOpString() + " lop="
					+ (lop == null ? "null" : lop.getClass().getSimpleName()));
			if(lop.getDataType() != selected.dataType()
				|| lop.getValueType() != selected.valueType())
				throw new LopsException("Memo-selected Lop type drift for hop=" + hop.getHopID());
			// Some data/metadata Lops deliberately retain INVALID and let their concrete instruction
			// choose the already-validated Hop execution. A concrete Lop execution may never disagree.
			if(lop.getExecType() != ExecType.INVALID && lop.getExecType() != selected.exec())
				throw new LopsException("Memo-selected Lop execution drift for hop=" + hop.getHopID()
					+ ": expected=" + selected.exec() + " actual=" + lop.getExecType());
			FederatedOutput expected = selected.output() == null ? null
				: selected.output().federated() ? FederatedOutput.FOUT : FederatedOutput.LOUT;
			boolean implicitLocal = expected == FederatedOutput.LOUT
				&& lop.getFederatedOutput() == FederatedOutput.NONE && lop.getExecType() != ExecType.FED;
			// A multi-return call owns one execution, but its aggregate FunctionCall Lop
			// is not a value slot and therefore has no single federated-output layout.
			// Every concrete output Hop is independently registered and validated here.
			boolean aggregateMultiReturn = hop instanceof FunctionOp function
				&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN;
			if(expected != null && lop.getFederatedOutput() != expected && !implicitLocal && !aggregateMultiReturn)
				throw new LopsException("Memo-selected Lop output drift for hop=" + hop.getHopID()
					+ ": expected=" + expected + " actual=" + lop.getFederatedOutput());
		}
	}

	private static boolean isEmbeddedDataGenDimension(Hop hop,Selection selected,Plan plan,Set<Lop> lopSet) {
		return isEmbeddedDataGenDimension(hop,selected.exec(),selected.output(),lopSet,
			parent -> plan.selections().containsKey(parent.getPlannerOriginHopID())
				|| plan.selections().containsKey(parent.getHopID()));
	}

	static boolean isEmbeddedDataGenDimension(Hop hop,ExecType selectedExec,MemoPartition selectedOutput,
		Set<Lop> lopSet,Predicate<Hop> selectedParent) {
		if(!(hop instanceof UnaryOp unary) || selectedExec!=ExecType.CP
			|| selectedOutput!=MemoPartition.LOCAL || hop.getDataType()!=DataType.SCALAR
			|| hop.getValueType()!=ValueType.INT64 || hop.getParent().isEmpty())
			return false;
		String parameter;
		if(unary.getOp()==OpOp1.NROW)
			parameter=DataExpression.RAND_ROWS;
		else if(unary.getOp()==OpOp1.NCOL)
			parameter=DataExpression.RAND_COLS;
		else
			return false;
		for(Hop parent:hop.getParent()) {
			if(!(parent instanceof DataGenOp dataGen))
				return false;
			Integer position=dataGen.getParamIndexMap().get(parameter);
			boolean dimensionKnown=parameter.equals(DataExpression.RAND_ROWS)
				? dataGen.rowsKnown() : dataGen.colsKnown();
			if(position==null || position<0 || position>=dataGen.getInput().size()
				|| dataGen.getInput().get(position)!=hop || !dimensionKnown || !selectedParent.test(parent)
				|| dataGen.getLops()==null || !lopSet.contains(dataGen.getLops()))
				return false;
		}
		return true;
	}

	/** Opens a fail-closed recompile transaction for the exact owner and statement block. */
	public static synchronized RecompileScope openRecompile(DMLProgram owner, StatementBlock block,
		List<Hop> originalRoots) {
		if(ACTIVE_PLANNINGS.contains(owner))
			throw new StaleMemoPlanException("Cannot open exact recompilation while memo planning is active");
		Plan plan = requirePlan(owner);
		Map<Long, Hop> originals = hopsById(originalRoots);
		Set<Long> required = new LinkedHashSet<>();
		for(long id : originals.keySet())
			if(plan.selections().containsKey(id) && requiresPhysicalClone(plan.selections().get(id)))
				required.add(id);
		long statementBlockId = block != null ? block.getSBID() : uniqueStatementBlock(plan, required);
		for(long origin : required)
			if(!plan.selections().get(origin).statementBlockIds().contains(statementBlockId))
				throw new StaleMemoPlanException("Memo occurrence " + origin
					+ " does not belong to statement block " + statementBlockId);
		if(ACTIVE_RECOMPILES.contains(owner))
			throw new StaleMemoPlanException("A memo recompile scope is already active for this program");
		ACTIVE_RECOMPILES.add(owner);
		return new RecompileScope(owner,plan, statementBlockId, Set.copyOf(required));
	}

	static synchronized void beginPlanning(DMLProgram owner) {
		Objects.requireNonNull(owner,"program owner");
		if(ACTIVE_RECOMPILES.contains(owner) || !ACTIVE_PLANNINGS.add(owner))
			throw new StaleMemoPlanException("Memo planning overlaps planning or exact recompilation");
	}

	static synchronized void endPlanning(DMLProgram owner) {
		ACTIVE_PLANNINGS.remove(owner);
	}

	private static synchronized void validateRecompileLease(DMLProgram owner,Plan plan) {
		if(PLANS.get(owner)!=plan || !ACTIVE_RECOMPILES.contains(owner))
			throw new StaleMemoPlanException("Memo recompile scope no longer owns the current exact plan");
	}

	private static synchronized void releaseRecompileLease(DMLProgram owner) {
		ACTIVE_RECOMPILES.remove(owner);
	}

	private static boolean requiresPhysicalClone(Selection selection) {
		return !LiteralOp.class.getName().equals(selection.hopClass());
	}

	private static long uniqueStatementBlock(Plan plan, Set<Long> required) {
		long result = -1;
		for(long origin : required) {
			Set<Long> candidates = plan.selections().get(origin).statementBlockIds();
			if(candidates.size() != 1)
				throw new StaleMemoPlanException(
					"A predicate recompile has an ambiguous memo statement block: " + candidates);
			long candidate = candidates.iterator().next();
			if(result >= 0 && result != candidate)
				throw new StaleMemoPlanException(
					"A predicate recompile spans more than one memo statement block");
			result = candidate;
		}
		if(result < 0)
			throw new StaleMemoPlanException("Memo predicate recompile has no selected occurrence identity");
		return result;
	}

	private static Plan requirePlan(DMLProgram owner) {
		Plan plan = plan(owner);
		if(plan == null)
			throw new StaleMemoPlanException("No committed memo plan for the exact DMLProgram owner");
		return plan;
	}

	private static Plan plan(DMLProgram owner) {
		return owner == null ? null : PLANS.get(owner);
	}

	private static Map<Long, List<Supply>> immutableSupplies(Map<Long, List<Supply>> supplies) {
		Map<Long, List<Supply>> result = new LinkedHashMap<>();
		for(Map.Entry<Long, List<Supply>> entry : supplies.entrySet())
			result.put(Objects.requireNonNull(entry.getKey()), List.copyOf(entry.getValue()));
		return Map.copyOf(result);
	}

	private static Map<Long, Hop> hopsById(List<Hop> roots) {
		if(roots == null)
			throw new IllegalArgumentException("Hop roots must not be null");
		Map<Long, Hop> result = new LinkedHashMap<>();
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> queue = new ArrayDeque<>(roots);
		while(!queue.isEmpty()) {
			Hop hop = queue.removeFirst();
			if(hop == null || !visited.add(hop))
				continue;
			Hop prior = result.putIfAbsent(hop.getHopID(), hop);
			if(prior != null && prior != hop)
				throw new StaleMemoPlanException("Duplicate Hop id " + hop.getHopID());
			queue.addAll(hop.getInput());
		}
		return result;
	}

	private static void validateSelectedHop(Selection selected, Hop hop, String phase, long statementBlockId) {
		if(hop.getDataType() != selected.dataType() || hop.getValueType() != selected.valueType())
			throw stale(phase, selected, hop, "data/value type changed");
		boolean actualShapeKnown = hop.getDim1() >= 0 && hop.getDim2() >= 0;
		boolean shapeValidation = !"publish".equals(phase);
		boolean exactRuntimeShapeRequired = "recompile".equals(phase);
		List<Shape> exactShapes = (!selected.dataType().isMatrix() && !selected.dataType().isFrame()) ? List.of()
			: selected.shapes().stream()
			// A shared compiled owner can carry the dimensions of another valid occurrence until
			// statement-block lowering refreshes it. Recompile therefore accepts the selected
			// owner's complete occurrence domain; lowering below is the exact per-SB check.
			.filter(value -> !"lowering".equals(phase) || statementBlockId < 0
				|| value.statementBlockId() == statementBlockId)
			.map(OccurrenceShape::shape).filter(Shape::exact)
			.distinct().toList();
		if(shapeValidation && !exactShapes.isEmpty() && (actualShapeKnown || exactRuntimeShapeRequired)
			&& (!actualShapeKnown || exactShapes.stream().noneMatch(shape ->
				shape.rows() == hop.getDim1() && shape.columns() == hop.getDim2())))
			throw stale(phase, selected, hop, "shape " + hop.getDim1() + 'x' + hop.getDim2()
				+ " is outside occurrence shapes " + exactShapes.stream()
					.map(shape -> shape.rows() + "x" + shape.columns()).toList());
		boolean replacement = hop.getPlannerRewriteReplacementKind() != null;
		if(!replacement && (!Objects.equals(hop.getOpString(), selected.opcode())
			|| hop.getInput().size() != selected.inputCount()))
			throw stale(phase, selected, hop, "opcode or input arity changed");
		if(hop.getExecType() != selected.exec() && hop.getForcedExecType() != selected.exec())
			throw stale(phase, selected, hop, "execution type is not " + selected.exec());
		FederatedOutput expected = selected.output() == null ? null
			: selected.output().federated() ? FederatedOutput.FOUT : FederatedOutput.LOUT;
		if(expected != null && hop.getFederatedOutput() != expected)
			throw stale(phase, selected, hop, "federated output is not " + expected);
		if(!hop.isPlannerPlacementSelected())
			throw stale(phase, selected, hop, "planner selection marker is absent");
	}

	private static StaleMemoPlanException stale(String phase, Selection selection, Hop actual, String reason) {
		return new StaleMemoPlanException("Stale memo plan during " + phase + ": origin=" + selection.hopId()
			+ " actual=" + actual.getHopID() + " reason=" + reason);
	}

	public static final class RecompileScope implements AutoCloseable {
		private final DMLProgram owner;
		private final Plan plan;
		private final long statementBlockId;
		private final Set<Long> required;
		private boolean activated;
		private boolean closed;

		private RecompileScope(DMLProgram owner,Plan plan, long statementBlockId, Set<Long> required) {
			this.owner=owner;
			this.plan = plan;
			this.statementBlockId = statementBlockId;
			this.required = required;
		}

		/** Revalidates final rewritten clones and installs exact clone-local supply identities. */
		public void activate(List<Hop> rewrittenRoots, Map<Long, Hop> deepCopyMemo) {
			if(activated || closed)
				throw new IllegalStateException("Memo recompile scope can be activated exactly once");
			validateRecompileLease(owner,plan);
			Map<Long, Hop> finalHops = hopsById(rewrittenRoots);
			Map<Long, List<Hop>> byOrigin = new LinkedHashMap<>();
			for(Hop hop : finalHops.values())
				byOrigin.computeIfAbsent(hop.getPlannerOriginHopID(), ignored -> new ArrayList<>()).add(hop);
			Map<Long, Hop> selectedClones = new LinkedHashMap<>();
			for(long origin : required) {
				Selection selection = plan.selections().get(origin);
				List<Hop> matches = byOrigin.getOrDefault(origin, List.of()).stream()
					.filter(hop -> hop.getPlannerLoweringAuxiliaryKind() == null).toList();
				if(matches.size() != 1)
					throw new StaleMemoPlanException("Memo recompile expected one exact clone for origin " + origin
						+ " (" + selection.hopClass() + '/' + selection.opcode() + ") but found " + matches.size());
				Hop clone = matches.get(0);
				// Exact deep copies may have lost selected fields during rewrites; replay only from the
				// explicit original-to-clone/origin relation, never from an opcode/source signature.
				clone.setExecType(selection.exec());
				clone.setForcedExecType(selection.exec());
				clone.setFederatedOutput(selection.output() == null ? FederatedOutput.NONE
					: selection.output().federated() ? FederatedOutput.FOUT : FederatedOutput.LOUT);
				clone.setPlannerPlacementSelected(true);
				validateSelectedHop(selection, clone, "recompile", statementBlockId);
				if(deepCopyMemo != null && clone.getPlannerRewriteReplacementKind() == null
					&& deepCopyMemo.get(origin) != clone)
					throw new StaleMemoPlanException("Memo recompile clone is not the exact deep-copy target for " + origin);
				selectedClones.put(origin, clone);
				HOP_OWNERS.put(clone,owner);
			}

			// The thread-local lowering authority comes from this exact program plan. The global
			// registry may currently contain a different program's most recently published plan.
			Map<Long, List<Supply>> replacement = new LinkedHashMap<>(plan.supplies());
			List<Supply> translated = new ArrayList<>();
			for(Supply supply : plan.supplies().getOrDefault(statementBlockId, List.of())) {
				if(!required.contains(supply.consumerHopId()))
					continue;
				Hop producer = selectedClones.get(supply.producerHopId());
				Hop consumer = selectedClones.get(supply.consumerHopId());
				if(producer == null || consumer == null || supply.inputPosition() >= consumer.getInput().size()
					|| consumer.getInput(supply.inputPosition()) != producer)
					throw new StaleMemoPlanException("Memo supply edge did not survive exact recompilation: " + supply);
				translated.add(new Supply(producer.getHopID(), consumer.getHopID(), supply.inputPosition(),
					supply.targetPartition(), supply.recipes()));
			}
			replacement.put(statementBlockId, List.copyOf(translated));
			RECOMPILE_SUPPLIES.set(Map.copyOf(replacement));
			List<DefinitionSite> definitions = new ArrayList<>();
			for(DefinitionSite site : plan.schedule().definitions()) {
				if(site.statementBlockId() != statementBlockId) {
					definitions.add(site);
					continue;
				}
				Hop producer = selectedClones.get(site.producerHopId());
				if(producer == null)
					throw new StaleMemoPlanException("Memo definition site did not survive exact recompilation: " + site);
				definitions.add(new DefinitionSite(site.statementBlockId(), producer.getHopID(), site.originValueId()));
			}
			List<ActionEdge> edges = new ArrayList<>();
			for(ActionEdge edge : plan.schedule().edges()) {
				if(edge.statementBlockId() != statementBlockId) {
					edges.add(edge);
					continue;
				}
				Hop producer = selectedClones.get(edge.producerHopId());
				Hop consumer = selectedClones.get(edge.consumerHopId());
				if(producer == null || consumer == null || edge.inputPosition() >= consumer.getInput().size()
					|| consumer.getInput().get(edge.inputPosition()) != producer)
					throw new StaleMemoPlanException("Memo action edge did not survive exact recompilation: " + edge);
				edges.add(new ActionEdge(edge.statementBlockId(), producer.getHopID(), consumer.getHopID(),
					edge.inputPosition(), edge.action()));
			}
			List<ReturnDefinitionSite> returns = new ArrayList<>();
			for(ReturnDefinitionSite site : plan.schedule().returns()) {
				if(site.statementBlockId() != statementBlockId) {
					returns.add(site);
					continue;
				}
				Hop call = selectedClones.get(site.callHopId());
				if(call == null)
					throw new StaleMemoPlanException("Memo function return site did not survive exact recompilation: " + site);
				returns.add(new ReturnDefinitionSite(site.statementBlockId(), call.getHopID(),
					site.outputPosition(), site.sourceOriginValueId(), site.targetOriginValueId()));
			}
			RECOMPILE_SCHEDULE.set(new SupplySchedule(plan.schedule().scopes(), definitions, edges, returns));
			activated = true;
		}

		@Override
		public void close() {
			if(closed)
				return;
			RECOMPILE_SUPPLIES.remove();
			RECOMPILE_SCHEDULE.remove();
			releaseRecompileLease(owner);
			closed = true;
		}
	}

	public static final class StaleMemoPlanException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		public StaleMemoPlanException(String message) {
			super(message);
		}
	}
}

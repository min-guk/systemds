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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;

/**
 * Lazy evaluator for {@link MemoProgramFacts} binding expressions.
 *
 * <p>A joint visit carries one guard valuation and one call stack through all
 * requested roots.  Correlated {@code SELECT} nodes therefore cannot produce
 * cross-arm combinations.  Environments are delivered one at a time and are
 * never retained as a source-product table.</p>
 */
public final class MemoBindingResolver {
	public enum LoopPhase { ENTRY, BACKEDGE }
	public enum BoundaryPhase { ORDINARY, ENTRY, BACKEDGE, LAST }
	public enum OriginKind {
		COMPILED_OWNER, LOOP_HEAD_CONTRACT, ITERATOR_LOCAL, FORMAL_BOUNDARY,
		NORMAL_CONTINUATION, UNBOUND, TERMINATED_AT_RETURN, MISSING, UNRESOLVED
	}
	public enum VisitStatus { COMPLETE, UNRESOLVED, STOPPED }

	public record GuardDecision(int guardId,List<Integer> callFrame,int valueBindingId,List<Integer> valueFrame) {
		public GuardDecision {
			callFrame=List.copyOf(callFrame);
			valueFrame=List.copyOf(valueFrame);
		}
		/** Compatibility constructor for deliberately syntactic/non-canonical decisions. */
		public GuardDecision(int guardId,List<Integer> callFrame) {
			this(guardId,callFrame,-1,List.of());
		}
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			if(!(other instanceof GuardDecision that)) return false;
			if(valueBindingId>=0 && that.valueBindingId>=0)
				return valueBindingId==that.valueBindingId && valueFrame.equals(that.valueFrame);
			return valueBindingId<0 && that.valueBindingId<0
				&& guardId==that.guardId && callFrame.equals(that.callFrame);
		}
		@Override public int hashCode() {
			int id=valueBindingId>=0 ? valueBindingId : guardId;
			List<Integer> frame=valueBindingId>=0 ? valueFrame : callFrame;
			// Exactly Arrays.hashCode(new Object[] {id,frame}) without the varargs array.
			return 31*(31+id)+frame.hashCode();
		}
	}

	public record LoopDecision(int loopId, List<Integer> callFrame) {
		public LoopDecision { callFrame = List.copyOf(callFrame); }
	}
	public record LoopCardinalityDecision(int loopId,List<Integer> callFrame) {
		public LoopCardinalityDecision { callFrame=List.copyOf(callFrame); }
	}
	public record DirectLoopHead(int loopId,int slot,List<Integer> callFrame) {
		public DirectLoopHead { callFrame=List.copyOf(callFrame); }
	}

	public interface SelectionLookup {
		default int selectedRule(int compiledOwnerId) { return -1; }
		default MemoPartition selectedLayout(int compiledOwnerId) { return null; }
		default MemoPartition selectedHeadLayout(int loopId, int slot) { return null; }
		default boolean headIsBound(int loopId, int slot) { return false; }
	}

	public static final SelectionLookup NO_SELECTION = new SelectionLookup() { };

	public record EvaluationContext(Map<GuardDecision,Boolean> guards,
		Map<LoopDecision,LoopPhase> loops, SelectionLookup selections, int maxDepth,
		List<Integer> callFrame,Map<LoopDecision,IterationPhase> iterationPhases,
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities) {
		public EvaluationContext {
			guards = Map.copyOf(guards);
			loops = Map.copyOf(loops);
			iterationPhases = Map.copyOf(iterationPhases);
			cardinalities=Map.copyOf(cardinalities);
			Objects.requireNonNull(selections);
			if(maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
			callFrame = List.copyOf(callFrame);
		}

		public EvaluationContext(Map<GuardDecision,Boolean> guards,
			Map<LoopDecision,LoopPhase> loops, SelectionLookup selections, int maxDepth) {
			this(guards, loops, selections, maxDepth, List.of(),Map.of(),Map.of());
		}

		public EvaluationContext(Map<GuardDecision,Boolean> guards,
			Map<LoopDecision,LoopPhase> loops, SelectionLookup selections, int maxDepth,List<Integer> callFrame) {
			this(guards,loops,selections,maxDepth,callFrame,Map.of(),Map.of());
		}

		public EvaluationContext(Map<GuardDecision,Boolean> guards,
			Map<LoopDecision,LoopPhase> loops, SelectionLookup selections, int maxDepth,List<Integer> callFrame,
			Map<LoopDecision,IterationPhase> iterationPhases) {
			this(guards,loops,selections,maxDepth,callFrame,iterationPhases,Map.of());
		}

		public static EvaluationContext empty() {
			return new EvaluationContext(Map.of(), Map.of(), NO_SELECTION, 4096, List.of(),Map.of(),Map.of());
		}

		public EvaluationContext withCallFrame(List<Integer> frame) {
			return new EvaluationContext(guards, loops, selections, maxDepth, frame,iterationPhases,cardinalities);
		}

		public EvaluationContext withIterationPhase(int loopId,IterationPhase phase) {
			Map<LoopDecision,IterationPhase> values=new LinkedHashMap<>(iterationPhases);
			values.put(new LoopDecision(loopId,callFrame),phase);
			return new EvaluationContext(guards,loops,selections,maxDepth,callFrame,values,cardinalities);
		}

		public EvaluationContext withLoopCardinality(int loopId,LoopCardinality cardinality) {
			Map<LoopCardinalityDecision,LoopCardinality> values=new LinkedHashMap<>(cardinalities);
			values.put(new LoopCardinalityDecision(loopId,callFrame),cardinality);
			return new EvaluationContext(guards,loops,selections,maxDepth,callFrame,iterationPhases,values);
		}
	}

	public record ResolvedOrigin(OriginKind kind, int referenceId, int compiledOwnerId, int boundarySlot,
		BoundaryPhase boundaryPhase, List<Integer> callFrame, int selectedRule,
		MemoPartition selectedLayout) {
		public ResolvedOrigin {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(boundaryPhase);
			callFrame = List.copyOf(callFrame);
		}
	}
	public record ExportStep(int sourceBindingId,int targetBindingId,int callId,int outputSlot) { }
	public record OriginIdentity(OriginKind kind,int visibleBindingId,int nativeDefinitionOccurrenceId,
		int compiledOwnerId,int boundarySlot,BoundaryPhase boundaryPhase,List<Integer> callFrame,
		List<ExportStep> exportChain) {
		public OriginIdentity {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(boundaryPhase);
			callFrame=List.copyOf(callFrame);
			exportChain=List.copyOf(exportChain);
		}
	}

	public record ResolvedEnvironment(List<ResolvedOrigin> origins,
		Map<GuardDecision,Boolean> guards, Map<LoopDecision,LoopPhase> loops,
		Map<LoopDecision,IterationPhase> iterationPhases,
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities) {
		public ResolvedEnvironment {
			origins = List.copyOf(origins);
			guards = Map.copyOf(guards);
			loops = Map.copyOf(loops);
			iterationPhases=Map.copyOf(iterationPhases);
			cardinalities=Map.copyOf(cardinalities);
		}
		public ResolvedEnvironment(List<ResolvedOrigin> origins,Map<GuardDecision,Boolean> guards,
			Map<LoopDecision,LoopPhase> loops) { this(origins,guards,loops,Map.of(),Map.of()); }
		public ResolvedEnvironment(List<ResolvedOrigin> origins,Map<GuardDecision,Boolean> guards,
			Map<LoopDecision,LoopPhase> loops,Map<LoopDecision,IterationPhase> iterationPhases) {
			this(origins,guards,loops,iterationPhases,Map.of());
		}
	}

	@FunctionalInterface
	public interface EnvironmentVisitor {
		/** Return false to stop enumeration after this environment. */
		boolean visit(ResolvedEnvironment environment);
	}

	public record VisitResult(VisitStatus status, int environmentCount, String detail) {
		public VisitResult {
			Objects.requireNonNull(status);
			Objects.requireNonNull(detail);
		}
	}
	record DirectRootStatistics(long requests,long eligibleCalls,long fastPathCalls,long rootsResolved) { }
	record MapSnapshotStatistics(long requests,long hits,int entries,long clears) { }
	private record DirectDefinition(int occurrence,int owner,int slot) { }
	private static final Set<DirectLoopHead> EMPTY_DIRECT_LOOP_HEADS=
		Collections.unmodifiableSet(new LinkedHashSet<>());

	private final MemoProgramFacts facts;
	private final Map<Integer,List<Integer>> callReturnsByConcreteBinding;
	private final DirectDefinition[] directDefinitions;
	private final Map<NodeFrame,Boolean> totalAvailability = new LinkedHashMap<>();
	private final IdentityHashMap<Map<?,?>,Map<?,?>> mapSnapshots=new IdentityHashMap<>();
	private final int mapSnapshotCapacity;
	private long mapSnapshotRequests,mapSnapshotHits,mapSnapshotClears;
	private long directRootRequests;
	private long directRootEligibleCalls;
	private long directRootFastPathCalls;
	private long directRootsResolved;
	public MemoBindingResolver(MemoProgramFacts facts) { this(facts,4096); }
	MemoBindingResolver(MemoProgramFacts facts,int mapSnapshotCapacity) {
		if(mapSnapshotCapacity<0) throw new IllegalArgumentException("map snapshot capacity must be nonnegative");
		this.mapSnapshotCapacity=mapSnapshotCapacity;
		this.facts = Objects.requireNonNull(facts);
		Map<Integer,List<Integer>> concreteReturns = new LinkedHashMap<>();
		for(MemoProgramFacts.CallBinding call : facts.calls())
			for(int returnBinding : call.returnBindingIds()) {
				MemoProgramFacts.BindingNode node = facts.binding(returnBinding);
				if(node.kind() == BindingKind.CALL_RETURN && node.children().size() > 1)
					concreteReturns.computeIfAbsent(node.children().get(1),ignored -> new ArrayList<>()).add(returnBinding);
			}
		concreteReturns.replaceAll((binding,returns) -> List.copyOf(returns));
		callReturnsByConcreteBinding = Map.copyOf(concreteReturns);
		directDefinitions=new DirectDefinition[facts.bindings().size()];
		for(MemoProgramFacts.BindingNode binding : facts.bindings()) {
			int occurrence=binding.referenceId();
			if(binding.kind()==BindingKind.DEFINITION && binding.children().isEmpty()
				&& !binding.label().endsWith("#for-iterator") && occurrence>=0
				&& occurrence<facts.occurrences().size() && !callReturnsByConcreteBinding.containsKey(binding.id())) {
				int owner=facts.occurrence(occurrence).compiledOwnerId();
				directDefinitions[binding.id()]=new DirectDefinition(occurrence,owner,binding.position());
			}
		}
	}

	DirectRootStatistics directRootStatistics() {
		return new DirectRootStatistics(directRootRequests,directRootEligibleCalls,
			directRootFastPathCalls,directRootsResolved);
	}
	MapSnapshotStatistics mapSnapshotStatistics() {
		return new MapSnapshotStatistics(mapSnapshotRequests,mapSnapshotHits,mapSnapshots.size(),mapSnapshotClears);
	}

	@SuppressWarnings("unchecked")
	private <K,V> Map<K,V> immutableSnapshot(Map<K,V> values) {
		if(values.isEmpty()) return values;
		mapSnapshotRequests++;
		Map<K,V> cached=(Map<K,V>)mapSnapshots.get(values);
		if(cached!=null) { mapSnapshotHits++; return cached; }
		Map<K,V> result=Map.copyOf(Collections.unmodifiableMap(values));
		if(mapSnapshotCapacity>0) {
			if(mapSnapshots.size()>=mapSnapshotCapacity) { mapSnapshots.clear(); mapSnapshotClears++; }
			mapSnapshots.put(values,result);
		}
		return result;
	}

	private Integer uniqueSymbolicReturn(int concreteBinding) {
		List<Integer> returns=callReturnsByConcreteBinding.get(concreteBinding);
		return returns!=null && returns.size()==1 ? returns.get(0) : null;
	}
	private boolean ambiguousSymbolicReturn(int concreteBinding) {
		List<Integer> returns=callReturnsByConcreteBinding.get(concreteBinding);
		return returns!=null && returns.size()>1;
	}

	/**
	 * Proves that resolving this binding cannot terminate or become missing in the
	 * exact caller frame.  The proof is deliberately all-paths: SELECT/LAST and
	 * loop-head alternatives must all supply a value.  It is used only to skip
	 * source enumeration for control continuation; payload grounding remains in
	 * the ordinary producer/consumer checks.
	 */
	public boolean alwaysAvailable(int bindingId,List<Integer> callFrame) {
		requireBinding(bindingId);
		NodeFrame key=new NodeFrame(bindingId,List.copyOf(callFrame));
		Boolean cached=totalAvailability.get(key);
		if(cached!=null) return cached;
		boolean result=new AvailabilityProof().available(bindingId,key.frame(),Set.of(),new LinkedHashSet<>(),true);
		totalAvailability.put(key,result);
		return result;
	}

	private final class AvailabilityProof {
		private boolean available(int bindingId,List<Integer> frame,Set<NodeFrame> assumptions,
			Set<NodeFrame> path,boolean remapConcreteReturn) {
			if(remapConcreteReturn && ambiguousSymbolicReturn(bindingId)) return false;
			Integer symbolic=remapConcreteReturn ? uniqueSymbolicReturn(bindingId) : null;
			if(symbolic!=null && !path.contains(new NodeFrame(symbolic,frame)))
				return available(symbolic,frame,assumptions,path,true);
			NodeFrame node=new NodeFrame(bindingId,frame);
			if(assumptions.contains(node)) return true;
			if(!path.add(node)) return false;
			boolean result;
			try {
				MemoProgramFacts.BindingNode binding=facts.binding(bindingId);
				result=switch(binding.kind()) {
					case DEFINITION -> binding.children().isEmpty()
						|| available(binding.children().get(0),frame,assumptions,path,true);
					case SELECT, LAST -> all(binding.children(),frame,assumptions,path);
					case LOOP_HEAD -> loopHead(binding,frame,assumptions,path,node);
					case CALL_COMPLETION -> completion(binding,frame,assumptions,path);
					case CALL_ARG -> !binding.children().isEmpty()
						&& available(binding.children().get(0),frame,assumptions,path,true);
					case FORMAL -> formal(binding,frame,assumptions,path);
					case CALL_RETURN -> callReturn(binding,frame,assumptions,path);
					case UNBOUND, MISSING -> false;
				};
			}
			finally { path.remove(node); }
			return result;
		}

		private boolean loopHead(MemoProgramFacts.BindingNode binding,List<Integer> frame,
			Set<NodeFrame> assumptions,Set<NodeFrame> path,NodeFrame node) {
			if(binding.children().size()<2
				|| !available(binding.children().get(0),frame,assumptions,path,true)) return false;
			Set<NodeFrame> inductive=new LinkedHashSet<>(assumptions); inductive.add(node);
			// The backedge is a separate induction step. Ancestor expressions may
			// recur through this head and must not be mistaken for arbitrary cycles.
			return available(binding.children().get(1),frame,Set.copyOf(inductive),new LinkedHashSet<>(),true);
		}

		private boolean all(List<Integer> bindings,List<Integer> frame,Set<NodeFrame> assumptions,
			Set<NodeFrame> path) {
			for(int binding : bindings)
				if(!available(binding,frame,assumptions,path,true)) return false;
			return true;
		}

		private boolean formal(MemoProgramFacts.BindingNode binding,List<Integer> frame,
			Set<NodeFrame> assumptions,Set<NodeFrame> path) {
			for(int index=frame.size()-1;index>=0;index--) {
				MemoProgramFacts.CallBinding call=facts.call(frame.get(index));
				if(call.functionSummaryId()==binding.referenceId()
					&& binding.position()<call.actualBindingIds().size())
					return available(call.actualBindingIds().get(binding.position()),
						frame.subList(0,index),assumptions,path,true);
			}
			return false;
		}

		private boolean callReturn(MemoProgramFacts.BindingNode binding,List<Integer> frame,
			Set<NodeFrame> assumptions,Set<NodeFrame> path) {
			if(binding.children().isEmpty()) return false;
			MemoProgramFacts.CallBinding call=binding.referenceId()>=0
				&& binding.referenceId()<facts.calls().size() ? facts.call(binding.referenceId()) : null;
			if(call!=null && call.completionBindingId()>=0
				&& !available(call.completionBindingId(),frame,assumptions,path,true)) return false;
			if(binding.children().size()>1)
				return available(binding.children().get(1),frame,assumptions,path,false);
			return available(binding.children().get(0),append(frame,binding.referenceId()),assumptions,path,true);
		}

		private boolean completion(MemoProgramFacts.BindingNode binding,List<Integer> frame,
			Set<NodeFrame> assumptions,Set<NodeFrame> path) {
			if(!all(binding.children(),frame,assumptions,path)) return false;
			if(binding.referenceId()<0) return true;
			MemoProgramFacts.CallBinding call=facts.call(binding.referenceId());
			MemoProgramFacts.FunctionSummary summary=facts.functions().get(call.functionSummaryId());
			List<Integer> nested=append(frame,call.id());
			if(summary.completionBindingId()>=0
				&& !available(summary.completionBindingId(),nested,assumptions,path,true)) return false;
			for(int returnBinding : call.returnBindingIds()) {
				MemoProgramFacts.BindingNode returned=facts.binding(returnBinding);
				if(returned.kind()!=BindingKind.CALL_RETURN || returned.children().isEmpty()) return false;
				boolean value=returned.children().size()>1
					? available(returned.children().get(1),frame,assumptions,path,false)
					: available(returned.children().get(0),nested,assumptions,path,true);
				if(!value) return false;
			}
			return true;
		}
	}

	public Set<Integer> influencingCompiledOwners(int bindingId) {
		return influencingCompiledOwners(List.of(bindingId));
	}

	/** Includes producer owners and their exact ordered input dependencies. */
	public Set<Integer> influencingCompiledOwners(List<Integer> bindingIds) {
		LinkedHashSet<Integer> result = new LinkedHashSet<>();
		DependencyState state = new DependencyState(result);
		for(int binding : bindingIds)
			collectDependencies(binding, List.of(), state);
		return Collections.unmodifiableSet(result);
	}

	/** Possible immediate value producers; unlike influencingCompiledOwners this stops at kernels. */
	public Set<Integer> directSupplyOwners(List<Integer> bindingIds) {
		return directSupplyOwners(bindingIds, List.of());
	}

	/** Possible immediate producers under an exact caller frame. */
	public Set<Integer> directSupplyOwners(List<Integer> bindingIds, List<Integer> callFrame) {
		LinkedHashSet<Integer> result = new LinkedHashSet<>();
		DependencyState state = new DependencyState(result);
		for(int binding : bindingIds)
			collectDirectOwners(binding, List.copyOf(callFrame), state);
		return Collections.unmodifiableSet(result);
	}

	public Set<Integer> directSupplyOwners(int bindingId) {
		return directSupplyOwners(List.of(bindingId));
	}

	public Set<Integer> directSupplyOwners(int bindingId, List<Integer> callFrame) {
		return directSupplyOwners(List.of(bindingId), callFrame);
	}

	/** Loop-head boundary values that can directly supply the requested bindings. */
	public Set<MemoPrivacy.LoopSlot> directLoopHeads(List<Integer> bindingIds, List<Integer> callFrame) {
		return loopHeads(bindingIds, callFrame, false,null);
	}
	public Set<MemoPrivacy.LoopSlot> directLoopHeads(List<Integer> bindingIds,EvaluationContext context) {
		Objects.requireNonNull(context,"context");
		return loopHeads(bindingIds,context.callFrame(),false,context);
	}
	public Set<DirectLoopHead> directLoopHeadIdentities(List<Integer> bindingIds,EvaluationContext context) {
		Objects.requireNonNull(context,"context");
		return loopHeadIdentities(bindingIds,context.callFrame(),false,context);
	}
	public Set<DirectLoopHead> directLoopHeadIdentities(int bindingId,EvaluationContext context) {
		Objects.requireNonNull(context,"context");
		requireBinding(bindingId);
		// A precomputed direct definition is a childless native leaf with no symbolic-return
		// redirection; the direct (traverseKernels=false) walk cannot reach a loop head.
		if(directDefinitions[bindingId]!=null) return EMPTY_DIRECT_LOOP_HEADS;
		return directLoopHeadIdentities(List.of(bindingId),context);
	}
	public Set<MemoPrivacy.LoopSlot> directLoopHeads(int bindingId,EvaluationContext context) {
		return directLoopHeads(List.of(bindingId),context);
	}

	public Set<MemoPrivacy.LoopSlot> directLoopHeads(List<Integer> bindingIds) {
		return directLoopHeads(bindingIds, List.of());
	}

	/**
	 * Loop heads influencing the requested bindings, including dependencies below
	 * native producer kernels. This wider scope is intended for privacy contexts;
	 * layout selection should use {@link #directLoopHeads(List, List)}.
	 */
	public Set<MemoPrivacy.LoopSlot> relevantLoopHeads(List<Integer> bindingIds, List<Integer> callFrame) {
		return loopHeads(bindingIds, callFrame, true,null);
	}

	public Set<MemoPrivacy.LoopSlot> relevantLoopHeads(List<Integer> bindingIds) {
		return relevantLoopHeads(bindingIds, List.of());
	}

	public VisitResult visitJoint(List<Integer> bindingIds, EvaluationContext context,
		EnvironmentVisitor visitor) {
		Objects.requireNonNull(bindingIds);
		Objects.requireNonNull(context);
		Objects.requireNonNull(visitor);
		for(int binding : bindingIds) requireBinding(binding);
		for(Map.Entry<LoopCardinalityDecision,LoopCardinality> decision : context.cardinalities().entrySet()) {
			boolean reachable=facts.loopCardinalityReachable(decision.getKey().loopId(),decision.getValue());
			if(!reachable) return new VisitResult(VisitStatus.COMPLETE,0,
				"loop cardinality contradicts proven iteration count");
			IterationPhase phase=context.iterationPhases().get(
				new LoopDecision(decision.getKey().loopId(),decision.getKey().callFrame()));
			if(phase!=null && !facts.loopPhaseReachable(
				decision.getKey().loopId(),decision.getValue(),phase))
				return new VisitResult(VisitStatus.COMPLETE,0,"loop phase is unreachable for cardinality");
		}
		for(Map.Entry<GuardDecision,Boolean> decision : context.guards().entrySet()) {
			int guard=decision.getKey().guardId();
			int loop=facts.guardEnclosingLoop(guard);
			IterationPhase phase=loop<0 ? null : context.iterationPhases().get(
				new LoopDecision(loop,decision.getKey().callFrame()));
			Boolean proven=phase==null ? null : facts.guardValue(loop,guard,phase);
			boolean branchValue=decision.getKey().valueBindingId()>=0
				? facts.branchGuardValue(guard,decision.getKey().callFrame(),decision.getValue())
				: decision.getValue();
			if(proven!=null && !proven.equals(branchValue))
				return new VisitResult(VisitStatus.COMPLETE,0,"explicit guard contradicts proven loop phase");
		}
		List<Integer> roots=List.copyOf(bindingIds);
		directRootRequests++;
		VisitResult direct=visitDirectDefinitions(roots,context,visitor);
		if(direct!=null) return direct;
		MutableEvaluation state = new MutableEvaluation(context);
		return enumerate(roots, state, visitor);
	}

	private VisitResult visitDirectDefinitions(List<Integer> roots,EvaluationContext context,
		EnvironmentVisitor visitor) {
		if(roots.isEmpty()) return null;
		for(int root : roots)
			if(directDefinitions[root]==null) return null;
		directRootEligibleCalls++;
		List<ResolvedOrigin> origins=new ArrayList<>(roots.size());
		for(int root : roots) {
			DirectDefinition definition=directDefinitions[root];
			int selectedRule=context.selections().selectedRule(definition.owner());
			MemoPartition selectedLayout=context.selections().selectedLayout(definition.owner());
			origins.add(new ResolvedOrigin(OriginKind.COMPILED_OWNER,definition.occurrence(),definition.owner(),
				definition.slot(),BoundaryPhase.ORDINARY,context.callFrame(),selectedRule,selectedLayout));
		}
		directRootsResolved+=origins.size();
		directRootFastPathCalls++;
		ResolvedEnvironment environment=new ResolvedEnvironment(origins,immutableSnapshot(context.guards()),
			immutableSnapshot(context.loops()),immutableSnapshot(context.iterationPhases()),
			immutableSnapshot(context.cardinalities()));
		boolean keepGoing=visitor.visit(environment);
		return new VisitResult(keepGoing ? VisitStatus.COMPLETE : VisitStatus.STOPPED,1,"complete");
	}

	/**
	 * Resolve the source identity visible at one exact use environment.  Physical
	 * owner identity and export identity are intentionally separate: two call
	 * return slots may share one compiled body owner but remain distinct retained
	 * values and schedule targets.
	 */
	public OriginIdentity resolveOriginIdentity(int bindingId,EvaluationContext context,
		ResolvedEnvironment environment) {
		requireBinding(bindingId);
		Objects.requireNonNull(context,"context");
		Objects.requireNonNull(environment,"environment");
		EvaluationContext exact=new EvaluationContext(environment.guards(),environment.loops(),
			context.selections(),context.maxDepth(),context.callFrame(),environment.iterationPhases(),
			environment.cardinalities());
		MutableEvaluation state=new MutableEvaluation(exact);
		Resolution resolution=resolve(bindingId,exact.callFrame(),BoundaryPhase.ORDINARY,
			new LinkedHashSet<>(),state,0);
		if(resolution.decision!=null)
			return new OriginIdentity(OriginKind.UNRESOLVED,bindingId,-1,-1,-1,
				BoundaryPhase.ORDINARY,exact.callFrame(),List.of());
		VisibilityTrace visible=traceVisibility(bindingId,exact.callFrame(),state,new LinkedHashSet<>(),0,true);
		ResolvedOrigin origin=resolution.origin;
		if(!visible.exact())
			return new OriginIdentity(OriginKind.UNRESOLVED,bindingId,-1,-1,-1,
				BoundaryPhase.ORDINARY,exact.callFrame(),List.of());
		int nativeOccurrence=origin.kind()==OriginKind.COMPILED_OWNER ? origin.referenceId() : -1;
		return new OriginIdentity(origin.kind(),visible.visibleBindingId(),nativeOccurrence,
			origin.compiledOwnerId(),origin.boundarySlot(),origin.boundaryPhase(),origin.callFrame(),visible.exports());
	}

	private VisibilityTrace traceVisibility(int bindingId,List<Integer> frame,MutableEvaluation state,
		Set<NodeFrame> path,int depth,boolean remapConcreteReturn) {
		if(depth>=state.maxDepth) return new VisibilityTrace(bindingId,List.of(),frame,false);
		if(remapConcreteReturn && ambiguousSymbolicReturn(bindingId))
			return new VisibilityTrace(bindingId,List.of(),frame,false);
		Integer symbolic=remapConcreteReturn ? uniqueSymbolicReturn(bindingId) : null;
		if(symbolic!=null && !path.contains(new NodeFrame(symbolic,frame)))
			return traceVisibility(symbolic,frame,state,path,depth+1,true);
		MemoProgramFacts.BindingNode binding=facts.binding(bindingId);
		NodeFrame key=new NodeFrame(bindingId,frame);
		if(!path.add(key)) {
			if(binding.kind()==BindingKind.LOOP_HEAD)
				return new VisibilityTrace(bindingId,List.of(),frame,true);
			return new VisibilityTrace(bindingId,List.of(),frame,false);
		}
		try {
			return switch(binding.kind()) {
				case SELECT -> {
					Boolean selected=selectedGuardValue(state.guards,binding.referenceId(),frame);
					if(selected==null) {
						int loop=facts.enclosingLoop(facts.guard(binding.referenceId()).parentRegionId());
						IterationPhase phase=loop<0 ? null
							: state.iterationPhases.get(new LoopDecision(loop,frame));
						selected=phase==null ? null : facts.guardValue(loop,binding.referenceId(),phase);
					}
					yield selected==null ? new VisibilityTrace(bindingId,List.of(),frame,false)
						: traceVisibility(binding.children().get(selected ? 0 : 1),frame,state,path,depth+1,true);
				}
				case LOOP_HEAD -> {
					LoopPhase selected=state.loops.get(new LoopDecision(binding.referenceId(),frame));
					VisibilityTrace child;
					if(state.selections.headIsBound(binding.referenceId(),binding.position()) || selected==null)
						child=new VisibilityTrace(bindingId,List.of(),frame,true);
					else child=traceVisibility(binding.children().get(selected==LoopPhase.ENTRY ? 0 : 1),
						frame,state,path,depth+1,true);
					yield new VisibilityTrace(bindingId,child.exports(),frame,child.exact());
				}
				case LAST -> {
					LoopCardinality cardinality=state.cardinalities.get(
						new LoopCardinalityDecision(binding.referenceId(),frame));
					if(cardinality==null || binding.children().size()<2)
						yield new VisibilityTrace(binding.id(),List.of(),frame,false);
					LoopDecision loop=new LoopDecision(binding.referenceId(),frame);
					IterationPhase selected=cardinality==LoopCardinality.ONE
						? IterationPhase.FIRST : cardinality==LoopCardinality.MANY ? IterationPhase.LAST : null;
					Map<LoopDecision,IterationPhase> phases=state.mutableIterationPhases();
					IterationPhase prior=selected==null ? phases.remove(loop) : phases.put(loop,selected);
					try { yield traceVisibility(binding.children().get(cardinality==LoopCardinality.ZERO ? 0 : 1),
						frame,state,path,depth+1,true); }
					finally {
						if(prior==null) phases.remove(loop); else phases.put(loop,prior);
					}
				}
				case CALL_ARG -> traceVisibility(binding.children().get(0),frame,state,path,depth+1,true);
				case FORMAL -> {
					VisibilityTrace result=new VisibilityTrace(bindingId,List.of(),frame,false);
					for(int index=frame.size()-1; index>=0; index--) {
						MemoProgramFacts.CallBinding call=facts.call(frame.get(index));
						if(call.functionSummaryId()==binding.referenceId()
							&& binding.position()<call.actualBindingIds().size()) {
							result=traceVisibility(call.actualBindingIds().get(binding.position()),
								frame.subList(0,index),state,path,depth+1,true);
							break;
						}
					}
					yield result;
				}
				case CALL_RETURN -> {
					if(binding.children().size()>1)
						yield traceVisibility(binding.children().get(1),frame,state,path,depth+1,false);
					List<Integer> nested=append(frame,binding.referenceId());
					VisibilityTrace source=traceVisibility(binding.children().get(0),nested,state,path,depth+1,true);
					if(!source.exact() || source.originFrame().equals(frame)) yield source;
					List<ExportStep> exports=new ArrayList<>(source.exports());
					exports.add(new ExportStep(source.visibleBindingId(),binding.id(),binding.referenceId(),
						binding.position()));
					yield new VisibilityTrace(binding.id(),exports,frame,true);
				}
				case DEFINITION -> {
					if(!binding.children().isEmpty())
						yield traceVisibility(binding.children().get(0),frame,state,path,depth+1,true);
					int occurrence=binding.referenceId();
					int value=occurrence>=0 && occurrence<facts.occurrences().size()
						? facts.valueBinding(occurrence) : -1;
					yield new VisibilityTrace(value>=0 ? value : binding.id(),List.of(),frame,true);
				}
				case CALL_COMPLETION, UNBOUND, MISSING -> new VisibilityTrace(binding.id(),List.of(),frame,true);
			};
		}
		finally { path.remove(key); }
	}

	private VisitResult enumerate(List<Integer> roots, MutableEvaluation state,
		EnvironmentVisitor visitor) {
		List<ResolvedOrigin> origins = new ArrayList<>(roots.size());
		for(int root : roots) {
			Resolution resolution = resolve(root, state.initialFrame, BoundaryPhase.ORDINARY,
				new LinkedHashSet<>(), state, 0);
			if(resolution.decision != null)
				return branch(roots, resolution.decision, state, visitor);
			if(resolution.origin.kind() == OriginKind.UNRESOLVED
				|| resolution.origin.kind() == OriginKind.MISSING
				|| resolution.origin.kind() == OriginKind.UNBOUND
				|| resolution.origin.kind() == OriginKind.FORMAL_BOUNDARY)
				state.unresolved = true;
			origins.add(resolution.origin);
		}
		state.environments++;
		boolean keepGoing = visitor.visit(new ResolvedEnvironment(origins,state.environmentGuards(),
			state.environmentLoops(),state.environmentIterationPhases(),state.environmentCardinalities()));
		return new VisitResult(!keepGoing ? VisitStatus.STOPPED
			: state.unresolved ? VisitStatus.UNRESOLVED : VisitStatus.COMPLETE,
			state.environments, state.unresolved ? "one or more bindings were unresolved" : "complete");
	}

	private VisitResult branch(List<Integer> roots, NeededDecision decision, MutableEvaluation state,
		EnvironmentVisitor visitor) {
		VisitResult first;
		if(decision.guard != null) {
			state.mutableGuards().put(decision.guard, false);
			first = enumerate(roots, state, visitor);
			if(first.status() == VisitStatus.STOPPED) return first;
			state.mutableGuards().put(decision.guard, true);
		}
		else if(decision.loop != null) {
			state.mutableLoops().put(decision.loop, LoopPhase.ENTRY);
			first = enumerate(roots, state, visitor);
			if(first.status() == VisitStatus.STOPPED) return first;
			state.mutableLoops().put(decision.loop, LoopPhase.BACKEDGE);
		}
		else return branchCardinalities(roots,decision.cardinality,state,visitor);
		VisitResult second = enumerate(roots, state, visitor);
		if(decision.guard != null) state.mutableGuards().remove(decision.guard);
		else state.mutableLoops().remove(decision.loop);
		VisitStatus status = first.status() == VisitStatus.UNRESOLVED || second.status() == VisitStatus.UNRESOLVED
			? VisitStatus.UNRESOLVED : second.status();
		return new VisitResult(status, state.environments,
			status == VisitStatus.UNRESOLVED ? "one or more bindings were unresolved" : "complete");
	}

	private VisitResult branchCardinalities(List<Integer> roots,LoopCardinalityDecision decision,
		MutableEvaluation state,EnvironmentVisitor visitor) {
		VisitStatus status=VisitStatus.COMPLETE;
		for(MemoProgramFacts.LoopExecutionCase executionCase : facts.loopExecutionCases(decision.loopId())) {
			state.mutableCardinalities().put(decision,executionCase.cardinality());
			VisitResult result=enumerate(roots,state,visitor);
			if(result.status()==VisitStatus.STOPPED) return result;
			if(result.status()==VisitStatus.UNRESOLVED) status=VisitStatus.UNRESOLVED;
			state.mutableIterationPhases().remove(new LoopDecision(decision.loopId(),decision.callFrame()));
		}
		state.mutableCardinalities().remove(decision);
		return new VisitResult(status,state.environments,
			status==VisitStatus.UNRESOLVED ? "one or more bindings were unresolved" : "complete");
	}

	private Resolution resolve(int bindingId, List<Integer> frame, BoundaryPhase phase,
		Set<NodeFrame> path, MutableEvaluation state, int depth) {
		return resolve(bindingId,frame,phase,path,state,depth,true);
	}

	/** Resolve a binding, optionally interpreting an inlined concrete witness through its
	 * symbolic CALL_RETURN wrapper. Completion validation uses {@code false}: it is
	 * already inside that wrapper and remapping would recurse through the same completion. */
	private Resolution resolve(int bindingId,List<Integer> frame,BoundaryPhase phase,
		Set<NodeFrame> path,MutableEvaluation state,int depth,boolean remapConcreteReturn) {
		if(depth >= state.maxDepth)
			return Resolution.origin(unresolved(bindingId, phase, frame));
		if(remapConcreteReturn && ambiguousSymbolicReturn(bindingId))
			return Resolution.origin(unresolved(bindingId,phase,frame));
		Integer symbolicReturn = remapConcreteReturn ? uniqueSymbolicReturn(bindingId) : null;
		if(symbolicReturn != null && !path.contains(new NodeFrame(symbolicReturn, frame)))
			return resolve(symbolicReturn, frame, phase, path, state, depth + 1);
		MemoProgramFacts.BindingNode binding = facts.binding(bindingId);
		NodeFrame nodeFrame = new NodeFrame(bindingId, frame);
		if(!path.add(nodeFrame)) {
			if(binding.kind() == BindingKind.LOOP_HEAD)
				return Resolution.origin(headContract(binding, frame, BoundaryPhase.BACKEDGE, state.selections));
			return Resolution.origin(unresolved(bindingId, phase, frame));
		}
		try {
			return switch(binding.kind()) {
				case SELECT -> resolveSelect(binding, frame, phase, path, state, depth);
				case LOOP_HEAD -> resolveLoopHead(binding, frame, path, state, depth);
				case LAST -> resolveLast(binding,frame,path,state,depth);
				case FORMAL -> resolveFormal(binding, frame, phase, path, state, depth);
				case CALL_ARG -> resolve(binding.children().get(0), frame, phase, path, state, depth + 1);
				case CALL_RETURN -> resolveCallReturn(binding, frame, phase, path, state, depth);
				case CALL_COMPLETION -> resolveCallCompletion(binding,frame,phase,path,state,depth);
				case DEFINITION -> resolveDefinition(binding, frame, phase, path, state, depth);
				case UNBOUND -> Resolution.origin(new ResolvedOrigin(OriginKind.UNBOUND,bindingId,-1,-1,
					phase,frame,-1,null));
				case MISSING -> Resolution.origin(new ResolvedOrigin(OriginKind.MISSING, bindingId, -1, -1,
					phase, frame, -1, null));
			};
		}
		finally { path.remove(nodeFrame); }
	}

	private Resolution resolveLast(MemoProgramFacts.BindingNode binding,List<Integer> frame,
		Set<NodeFrame> path,MutableEvaluation state,int depth) {
		LoopCardinalityDecision cardinalityKey=new LoopCardinalityDecision(binding.referenceId(),frame);
		LoopCardinality cardinality=state.cardinalities.get(cardinalityKey);
		if(cardinality==null) return Resolution.decision(NeededDecision.cardinality(cardinalityKey));
		if(binding.children().size()<2) return Resolution.origin(unresolved(binding.id(),BoundaryPhase.LAST,frame));
		LoopDecision key=new LoopDecision(binding.referenceId(),frame);
		IterationPhase selected=cardinality==LoopCardinality.ONE ? IterationPhase.FIRST
			: cardinality==LoopCardinality.MANY ? IterationPhase.LAST : null;
		if(selected==null) state.mutableIterationPhases().remove(key);
		else state.mutableIterationPhases().put(key,selected);
		return resolve(binding.children().get(cardinality==LoopCardinality.ZERO ? 0 : 1),
			frame,BoundaryPhase.LAST,path,state,depth+1);
	}

	private Resolution resolveSelect(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		BoundaryPhase phase, Set<NodeFrame> path, MutableEvaluation state, int depth) {
		GuardDecision key=facts.guardDecision(binding.referenceId(),frame);
		Boolean value=selectedGuardValue(state.guards,binding.referenceId(),frame);
		if(value == null) {
			int loop=facts.enclosingLoop(facts.guard(binding.referenceId()).parentRegionId());
			IterationPhase iteration=loop < 0 ? null : state.iterationPhases.get(new LoopDecision(loop,frame));
			if(iteration != null) value=facts.guardValue(loop,binding.referenceId(),iteration);
		}
		if(value == null) return Resolution.decision(NeededDecision.guard(key));
		return resolve(binding.children().get(value ? 0 : 1), frame, phase, path, state, depth + 1);
	}

	private Resolution resolveLoopHead(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		Set<NodeFrame> path, MutableEvaluation state, int depth) {
		if(state.selections.headIsBound(binding.referenceId(), binding.position()))
			return Resolution.origin(headContract(binding, frame, BoundaryPhase.ORDINARY, state.selections));
		LoopDecision key = new LoopDecision(binding.referenceId(), frame);
		LoopPhase value = state.loops.get(key);
		if(value == null) return Resolution.decision(NeededDecision.loop(key));
		if(value == LoopPhase.ENTRY)
			return resolve(binding.children().get(0), frame, BoundaryPhase.ENTRY, path, state, depth + 1);
		Resolution result = resolve(binding.children().get(1), frame, BoundaryPhase.BACKEDGE,
			path, state, depth + 1);
		return result;
	}

	private Resolution resolveFormal(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		BoundaryPhase phase, Set<NodeFrame> path, MutableEvaluation state, int depth) {
		for(int index = frame.size() - 1; index >= 0; index--) {
			MemoProgramFacts.CallBinding call = facts.call(frame.get(index));
			if(call.functionSummaryId() == binding.referenceId()
				&& binding.position() < call.actualBindingIds().size())
				return resolve(call.actualBindingIds().get(binding.position()), frame.subList(0, index),
					phase, path, state, depth + 1);
		}
		return Resolution.origin(new ResolvedOrigin(OriginKind.FORMAL_BOUNDARY, binding.referenceId(), -1,
			-1, phase, frame, -1, null));
	}

	private Resolution resolveCallReturn(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		BoundaryPhase phase, Set<NodeFrame> path, MutableEvaluation state, int depth) {
		if(binding.children().isEmpty()) return Resolution.origin(unresolved(binding.id(), phase, frame));
		MemoProgramFacts.CallBinding call = binding.referenceId() >= 0
			&& binding.referenceId() < facts.calls().size() ? facts.call(binding.referenceId()) : null;
		if(call != null && call.completionBindingId() >= 0) {
			Resolution completion = resolve(call.completionBindingId(),frame,phase,path,state,depth + 1);
			if(completion.decision != null || completion.origin.kind() != OriginKind.NORMAL_CONTINUATION)
				return completion;
		}
		if(binding.children().size() > 1)
			return resolve(binding.children().get(1), frame, phase, path, state, depth + 1);
		List<Integer> nested = append(frame, binding.referenceId());
		// A non-inlined call executes the shared compiled summary.
		return resolve(binding.children().get(0), nested, phase, path, state, depth + 1);
	}

	private Resolution resolveCallCompletion(MemoProgramFacts.BindingNode binding,List<Integer> frame,
		BoundaryPhase phase,Set<NodeFrame> path,MutableEvaluation state,int depth) {
		for(int prerequisite : binding.children()) {
			if(alwaysAvailable(prerequisite,frame)) continue;
			Resolution resolved = resolve(prerequisite,frame,phase,path,state,depth + 1);
			if(resolved.decision != null || isTerminalOrInvalid(resolved.origin)) return resolved;
		}
		if(binding.referenceId() < 0)
			return Resolution.origin(continuation(binding.id(),phase,frame));
		MemoProgramFacts.CallBinding call = facts.call(binding.referenceId());
		MemoProgramFacts.FunctionSummary summary = facts.functions().get(call.functionSummaryId());
		List<Integer> nested = append(frame,call.id());
		if(summary.completionBindingId() >= 0
			&& !alwaysAvailable(summary.completionBindingId(),nested)) {
			Resolution body = resolve(summary.completionBindingId(),nested,phase,path,state,depth + 1);
			if(body.decision != null || isTerminalOrInvalid(body.origin)) return body;
		}
		for(int returnBinding : call.returnBindingIds()) {
			MemoProgramFacts.BindingNode returned = facts.binding(returnBinding);
			if(returned.kind() != BindingKind.CALL_RETURN || returned.children().isEmpty())
				return Resolution.origin(unresolved(returnBinding,phase,frame));
			int payload=returned.children().size()>1 ? returned.children().get(1) : returned.children().get(0);
			List<Integer> payloadFrame=returned.children().size()>1 ? frame : nested;
			if(alwaysAvailable(payload,payloadFrame)) continue;
			Resolution value = returned.children().size() > 1
				? resolve(payload,frame,phase,path,state,depth + 1,false)
				: resolve(payload,nested,phase,path,state,depth + 1);
			if(value.decision != null) return value;
			if(value.origin.kind() == OriginKind.UNBOUND)
				return Resolution.origin(new ResolvedOrigin(OriginKind.TERMINATED_AT_RETURN,call.id(),-1,-1,
					phase,frame,-1,null));
			if(isTerminalOrInvalid(value.origin)) return value;
		}
		return Resolution.origin(continuation(call.id(),phase,frame));
	}

	private static boolean isTerminalOrInvalid(ResolvedOrigin origin) {
		return origin.kind() == OriginKind.TERMINATED_AT_RETURN || origin.kind() == OriginKind.UNBOUND
			|| origin.kind() == OriginKind.MISSING || origin.kind() == OriginKind.UNRESOLVED;
	}

	private static ResolvedOrigin continuation(int reference,BoundaryPhase phase,List<Integer> frame) {
		return new ResolvedOrigin(OriginKind.NORMAL_CONTINUATION,reference,-1,-1,phase,frame,-1,null);
	}

	private Resolution resolveDefinition(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		BoundaryPhase phase, Set<NodeFrame> path, MutableEvaluation state, int depth) {
		if(binding.label().endsWith("#for-iterator"))
			return Resolution.origin(new ResolvedOrigin(OriginKind.ITERATOR_LOCAL, binding.referenceId(),
				-1, -1, phase, frame, -1, MemoPartition.LOCAL));
		if(!binding.children().isEmpty())
			return resolve(binding.children().get(0), frame, phase, path, state, depth + 1);
		int occurrence = binding.referenceId();
		if(occurrence < 0 || occurrence >= facts.occurrences().size())
			return Resolution.origin(unresolved(binding.id(), phase, frame));
		int owner = facts.occurrence(occurrence).compiledOwnerId();
		return Resolution.origin(new ResolvedOrigin(OriginKind.COMPILED_OWNER, occurrence, owner, binding.position(),
			phase, frame, state.selections.selectedRule(owner), state.selections.selectedLayout(owner)));
	}

	private ResolvedOrigin headContract(MemoProgramFacts.BindingNode binding, List<Integer> frame,
		BoundaryPhase phase, SelectionLookup selections) {
		return new ResolvedOrigin(OriginKind.LOOP_HEAD_CONTRACT, binding.referenceId(), -1, binding.position(),
			phase, frame, -1, selections.selectedHeadLayout(binding.referenceId(), binding.position()));
	}

	private ResolvedOrigin unresolved(int binding, BoundaryPhase phase, List<Integer> frame) {
		return new ResolvedOrigin(OriginKind.UNRESOLVED, binding, -1, -1, phase, frame, -1, null);
	}

	private void collectDependencies(int bindingId, List<Integer> frame, DependencyState state) {
		Integer symbolicReturn = uniqueSymbolicReturn(bindingId);
		if(symbolicReturn != null && !state.visited.contains(new DependencyKey(symbolicReturn, frame))) {
			collectDependencies(symbolicReturn, frame, state);
			return;
		}
		DependencyKey key = new DependencyKey(bindingId, frame);
		if(!state.visited.add(key)) return;
		MemoProgramFacts.BindingNode binding = facts.binding(bindingId);
		switch(binding.kind()) {
			case DEFINITION:
				if(!binding.children().isEmpty()) collectDependencies(binding.children().get(0), frame, state);
				else collectOwnerDependencies(binding.referenceId(), frame, state);
				break;
			case SELECT, LOOP_HEAD, LAST, CALL_ARG, CALL_COMPLETION:
				for(int child : binding.children()) collectDependencies(child, frame, state);
				break;
			case CALL_RETURN:
				if(binding.children().size() > 1)
					collectDependencies(binding.children().get(1), frame, state);
				else if(!binding.children().isEmpty())
					collectDependencies(binding.children().get(0), append(frame, binding.referenceId()), state);
				break;
			case FORMAL:
				for(int index = frame.size() - 1; index >= 0; index--) {
					MemoProgramFacts.CallBinding call = facts.call(frame.get(index));
					if(call.functionSummaryId() == binding.referenceId()
						&& binding.position() < call.actualBindingIds().size()) {
						collectDependencies(call.actualBindingIds().get(binding.position()),
							frame.subList(0, index), state);
						break;
					}
				}
				break;
			case UNBOUND, MISSING:
				break;
		}
	}

	private void collectDirectOwners(int bindingId, List<Integer> frame, DependencyState state) {
		Integer symbolicReturn = uniqueSymbolicReturn(bindingId);
		if(symbolicReturn != null && !state.visited.contains(new DependencyKey(symbolicReturn, frame))) {
			collectDirectOwners(symbolicReturn, frame, state);
			return;
		}
		DependencyKey key = new DependencyKey(bindingId, frame);
		if(!state.visited.add(key)) return;
		MemoProgramFacts.BindingNode binding = facts.binding(bindingId);
		switch(binding.kind()) {
			case DEFINITION:
				if(binding.label().endsWith("#for-iterator")) break;
				if(!binding.children().isEmpty()) collectDirectOwners(binding.children().get(0), frame, state);
				else if(binding.referenceId() >= 0 && binding.referenceId() < facts.occurrences().size())
					state.owners.add(facts.occurrence(binding.referenceId()).compiledOwnerId());
				break;
			case SELECT, LAST, CALL_ARG, CALL_COMPLETION:
				for(int child : binding.children()) collectDirectOwners(child, frame, state);
				break;
			case LOOP_HEAD:
				// The selected loop contract is the immediate supplier. Entry and
				// backedge producers belong to the separate boundary check.
				break;
			case CALL_RETURN:
				if(binding.children().size() > 1)
					collectDirectOwners(binding.children().get(1), frame, state);
				else if(!binding.children().isEmpty())
					collectDirectOwners(binding.children().get(0), append(frame, binding.referenceId()), state);
				break;
			case FORMAL:
				for(int index = frame.size() - 1; index >= 0; index--) {
					MemoProgramFacts.CallBinding call = facts.call(frame.get(index));
					if(call.functionSummaryId() == binding.referenceId()
						&& binding.position() < call.actualBindingIds().size()) {
						collectDirectOwners(call.actualBindingIds().get(binding.position()),
							frame.subList(0, index), state);
						break;
					}
				}
				break;
			case UNBOUND, MISSING:
				break;
		}
	}

	private Set<MemoPrivacy.LoopSlot> loopHeads(List<Integer> bindingIds,List<Integer> callFrame,
		boolean traverseKernels,EvaluationContext context) {
		LinkedHashSet<MemoPrivacy.LoopSlot> result=new LinkedHashSet<>();
		for(DirectLoopHead head : loopHeadIdentities(bindingIds,callFrame,traverseKernels,context))
			result.add(new MemoPrivacy.LoopSlot(head.loopId(),head.slot()));
		return Collections.unmodifiableSet(result);
	}

	private Set<DirectLoopHead> loopHeadIdentities(List<Integer> bindingIds,List<Integer> callFrame,
		boolean traverseKernels,EvaluationContext context) {
		Objects.requireNonNull(bindingIds, "bindingIds");
		List<Integer> initialFrame = List.copyOf(Objects.requireNonNull(callFrame, "callFrame"));
		LinkedHashSet<DirectLoopHead> result = new LinkedHashSet<>();
		LoopDependencyState state = new LoopDependencyState(result,traverseKernels,context);
		for(int binding : bindingIds) {
			requireBinding(binding);
			collectLoopHeads(binding, initialFrame, state);
		}
		return Collections.unmodifiableSet(result);
	}

	private void collectLoopHeads(int bindingId, List<Integer> frame, LoopDependencyState state) {
		Integer symbolicReturn = uniqueSymbolicReturn(bindingId);
		if(symbolicReturn != null && !state.visited.contains(new DependencyKey(symbolicReturn, frame))) {
			collectLoopHeads(symbolicReturn, frame, state);
			return;
		}
		DependencyKey key = new DependencyKey(bindingId, frame);
		if(!state.visited.add(key)) return;
		MemoProgramFacts.BindingNode binding = facts.binding(bindingId);
		switch(binding.kind()) {
			case LOOP_HEAD:
				state.heads.add(new DirectLoopHead(binding.referenceId(),binding.position(),frame));
				break;
			case DEFINITION:
				if(binding.label().endsWith("#for-iterator")) break;
				if(!binding.children().isEmpty())
					collectLoopHeads(binding.children().get(0), frame, state);
				else if(state.traverseKernels)
					collectOwnerLoopHeads(binding.referenceId(), frame, state);
				break;
			case SELECT:
				Boolean selected=phaseGuard(binding,frame,state.context);
				if(selected==null)
					for(int child : binding.children()) collectLoopHeads(child,frame,state);
				else collectLoopHeads(binding.children().get(selected ? 0 : 1),frame,state);
				break;
			case LAST, CALL_ARG, CALL_COMPLETION:
				for(int child : binding.children()) collectLoopHeads(child, frame, state);
				break;
			case CALL_RETURN:
				if(binding.children().size() > 1)
					collectLoopHeads(binding.children().get(1), frame, state);
				else if(!binding.children().isEmpty())
					collectLoopHeads(binding.children().get(0), append(frame, binding.referenceId()), state);
				break;
			case FORMAL:
				for(int index = frame.size() - 1; index >= 0; index--) {
					MemoProgramFacts.CallBinding call = facts.call(frame.get(index));
					if(call.functionSummaryId() == binding.referenceId()
						&& binding.position() < call.actualBindingIds().size()) {
						collectLoopHeads(call.actualBindingIds().get(binding.position()),
							frame.subList(0, index), state);
						break;
					}
				}
				break;
			case UNBOUND, MISSING:
				break;
		}
	}

	private Boolean phaseGuard(MemoProgramFacts.BindingNode binding,List<Integer> frame,
		EvaluationContext context) {
		if(context==null) return null;
		Boolean selected=selectedGuardValue(context.guards(),binding.referenceId(),frame);
		if(selected!=null) return selected;
		int loop=facts.enclosingLoop(facts.guard(binding.referenceId()).parentRegionId());
		IterationPhase phase=loop<0 ? null : context.iterationPhases().get(new LoopDecision(loop,frame));
		return phase==null ? null : facts.guardValue(loop,binding.referenceId(),phase);
	}

	private Boolean selectedGuardValue(Map<GuardDecision,Boolean> guards,int guardId,List<Integer> frame) {
		GuardDecision canonical=facts.guardDecision(guardId,frame);
		Boolean selected=guards.get(canonical);
		if(selected!=null) return facts.branchGuardValue(guardId,frame,selected);
		// Compatibility for explicit test/caller contexts created before provenance
		// normalization. Such a key already denotes the syntactic branch truth.
		return guards.get(new GuardDecision(guardId,frame));
	}

	private void collectOwnerLoopHeads(int occurrenceId, List<Integer> frame, LoopDependencyState state) {
		if(occurrenceId < 0 || occurrenceId >= facts.occurrences().size()) return;
		MemoProgramFacts.Occurrence occurrence = facts.occurrence(occurrenceId);
		for(int position = 0; position < occurrence.hop().getInput().size(); position++) {
			int input = facts.inputBinding(occurrenceId, position);
			if(input >= 0) collectLoopHeads(input, frame, state);
		}
	}

	private void collectOwnerDependencies(int occurrenceId, List<Integer> frame, DependencyState state) {
		if(occurrenceId < 0 || occurrenceId >= facts.occurrences().size()) return;
		MemoProgramFacts.Occurrence occurrence = facts.occurrence(occurrenceId);
		state.owners.add(occurrence.compiledOwnerId());
		Hop hop = occurrence.hop();
		for(int position = 0; position < hop.getInput().size(); position++) {
			int input = facts.inputBinding(occurrenceId, position);
			if(input >= 0) collectDependencies(input, frame, state);
		}
	}

	private void requireBinding(int binding) {
		if(binding < 0 || binding >= facts.bindings().size())
			throw new IllegalArgumentException("Unknown binding: " + binding);
	}

	private static List<Integer> append(List<Integer> frame, int call) {
		List<Integer> result = new ArrayList<>(frame.size() + 1);
		result.addAll(frame);
		result.add(call);
		return List.copyOf(result);
	}

	private record NodeFrame(int binding, List<Integer> frame) {
		private NodeFrame { frame = List.copyOf(frame); }
	}
	private record DependencyKey(int binding, List<Integer> frame) {
		private DependencyKey { frame = List.copyOf(frame); }
	}
	private record VisibilityTrace(int visibleBindingId,List<ExportStep> exports,List<Integer> originFrame,
		boolean exact) {
		private VisibilityTrace { exports=List.copyOf(exports); originFrame=List.copyOf(originFrame); }
	}
	private static final class DependencyState {
		private final Set<Integer> owners;
		private final Set<DependencyKey> visited = new LinkedHashSet<>();
		private DependencyState(Set<Integer> owners) { this.owners = owners; }
	}
	private static final class LoopDependencyState {
		private final Set<DirectLoopHead> heads;
		private final boolean traverseKernels;
		private final EvaluationContext context;
		private final Set<DependencyKey> visited = new LinkedHashSet<>();
		private LoopDependencyState(Set<DirectLoopHead> heads,boolean traverseKernels,
			EvaluationContext context) {
			this.heads = heads;
			this.traverseKernels = traverseKernels;
			this.context=context;
		}
	}
	private final class MutableEvaluation {
		private Map<GuardDecision,Boolean> guards;
		private Map<LoopDecision,LoopPhase> loops;
		private Map<LoopDecision,IterationPhase> iterationPhases;
		private Map<LoopCardinalityDecision,LoopCardinality> cardinalities;
		private boolean ownsGuards;
		private boolean ownsLoops;
		private boolean ownsIterationPhases;
		private boolean ownsCardinalities;
		private Map<GuardDecision,Boolean> environmentGuards;
		private Map<LoopDecision,LoopPhase> environmentLoops;
		private Map<LoopDecision,IterationPhase> environmentIterationPhases;
		private Map<LoopCardinalityDecision,LoopCardinality> environmentCardinalities;
		private final SelectionLookup selections;
		private final int maxDepth;
		private final List<Integer> initialFrame;
		private int environments;
		private boolean unresolved;
		private MutableEvaluation(EvaluationContext context) {
			guards = context.guards();
			loops = context.loops();
			iterationPhases=context.iterationPhases();
			cardinalities=context.cardinalities();
			selections = context.selections();
			maxDepth = context.maxDepth();
			initialFrame = context.callFrame();
		}
		private Map<GuardDecision,Boolean> mutableGuards() {
			if(!ownsGuards) { guards=new LinkedHashMap<>(guards); ownsGuards=true; }
			return guards;
		}
		private Map<LoopDecision,LoopPhase> mutableLoops() {
			if(!ownsLoops) { loops=new LinkedHashMap<>(loops); ownsLoops=true; }
			return loops;
		}
		private Map<LoopDecision,IterationPhase> mutableIterationPhases() {
			if(!ownsIterationPhases) {
				iterationPhases=new LinkedHashMap<>(iterationPhases); ownsIterationPhases=true;
			}
			return iterationPhases;
		}
		private Map<LoopCardinalityDecision,LoopCardinality> mutableCardinalities() {
			if(!ownsCardinalities) { cardinalities=new LinkedHashMap<>(cardinalities); ownsCardinalities=true; }
			return cardinalities;
		}
		// Map.copyOf(MapN) would retain the original table, whereas the former eager
		// LinkedHashMap copy was reinserted into a new MapN at every emission. Force
		// that same reinsertion for unowned maps: colliding-key iteration order
		// feeds exact binary64 probability arithmetic. The wrapper avoids node copies.
		private Map<GuardDecision,Boolean> environmentGuards() {
			if(ownsGuards) return guards;
			if(environmentGuards==null) environmentGuards=immutableSnapshot(guards);
			return environmentGuards;
		}
		private Map<LoopDecision,LoopPhase> environmentLoops() {
			if(ownsLoops) return loops;
			if(environmentLoops==null) environmentLoops=immutableSnapshot(loops);
			return environmentLoops;
		}
		private Map<LoopDecision,IterationPhase> environmentIterationPhases() {
			if(ownsIterationPhases) return iterationPhases;
			if(environmentIterationPhases==null)
				environmentIterationPhases=immutableSnapshot(iterationPhases);
			return environmentIterationPhases;
		}
		private Map<LoopCardinalityDecision,LoopCardinality> environmentCardinalities() {
			if(ownsCardinalities) return cardinalities;
			if(environmentCardinalities==null)
				environmentCardinalities=immutableSnapshot(cardinalities);
			return environmentCardinalities;
		}
	}
	private static final class NeededDecision {
		private final GuardDecision guard;
		private final LoopDecision loop;
		private final LoopCardinalityDecision cardinality;
		private NeededDecision(GuardDecision guard, LoopDecision loop,LoopCardinalityDecision cardinality) {
			this.guard = guard; this.loop = loop; this.cardinality=cardinality;
		}
		private static NeededDecision guard(GuardDecision key) { return new NeededDecision(key, null,null); }
		private static NeededDecision loop(LoopDecision key) { return new NeededDecision(null, key,null); }
		private static NeededDecision cardinality(LoopCardinalityDecision key) {
			return new NeededDecision(null,null,key);
		}
	}
	private static final class Resolution {
		private final ResolvedOrigin origin;
		private final NeededDecision decision;
		private Resolution(ResolvedOrigin origin, NeededDecision decision) {
			this.origin = origin;
			this.decision = decision;
		}
		private static Resolution origin(ResolvedOrigin value) { return new Resolution(value, null); }
		private static Resolution decision(NeededDecision value) { return new Resolution(null, value); }
	}
}

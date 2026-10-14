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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.parser.ConstIdentifier;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParForStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.util.UtilFunctions;

/**
 * Immutable, placement-independent program facts consumed by the memo planner.
 *
 * <p>The representation is intentionally symbolic.  Branches share one guard
 * identity, loops contain an entry/backedge expression, and function calls bind
 * actuals to one compiled function summary.  It never materializes reaching
 * definition or call-environment Cartesian products.</p>
 */
public final class MemoProgramFacts {
	public enum Status { COMPLETE, UNSUPPORTED, INCOMPLETE }
	public enum RegionKind { PROGRAM, FUNCTION, BASIC_BLOCK, IF_THEN, IF_ELSE, LOOP_BODY }
	public enum BindingKind {
		DEFINITION, SELECT, LOOP_HEAD, LAST, FORMAL, CALL_ARG, CALL_RETURN, CALL_COMPLETION, UNBOUND, MISSING
	}
	public enum UseKind { PAYLOAD, SCALAR, METADATA, CONTROL, FUNCTION_RETURN }
	public enum FrequencyKind { ONCE, GUARDED_THEN, GUARDED_ELSE, LOOP_ITERATIONS, FUNCTION_INVOCATIONS }
	public enum IterationPhase { FIRST, MIDDLE, LAST }
	/** Runtime iteration-count class. MANY means two or more iterations, not an optimizer choice. */
	public enum LoopCardinality { ZERO, ONE, MANY }

	public record Region(int id, int parentId, RegionKind kind, String label, int guardId, int loopId) {
		public Region {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(label);
		}
	}

	public record Occurrence(int id, int compiledOwnerId, Hop hop, long statementBlockId,
		int regionId, String context, int activationBindingId) {
		public Occurrence {
			Objects.requireNonNull(hop);
			Objects.requireNonNull(context);
		}
	}

	public record ShapeFact(int occurrenceId, DataType dataType, ValueType valueType,
		long rows, long columns, long nonZeros) {
		public ShapeFact {
			Objects.requireNonNull(dataType);
			Objects.requireNonNull(valueType);
		}
	}

	/** Children are ordered: SELECT=(then,else), LOOP_HEAD=(entry,backedge), LAST=(zero-entry,nonzero-last). */
	public record BindingNode(int id, BindingKind kind, List<Integer> children, int referenceId,
		int position, String label) {
		public BindingNode {
			Objects.requireNonNull(kind);
			children = List.copyOf(children);
			Objects.requireNonNull(label);
		}
	}

	public record Definition(int id, String variable, int version, int occurrenceId,
		int sourceBindingId, int regionId) {
		public Definition {
			Objects.requireNonNull(variable);
		}
	}

	public record InputBinding(int occurrenceId, int inputPosition, int bindingId) {}

	public record SemanticUse(int id, int occurrenceId, int inputPosition, int bindingId,
		UseKind kind, int regionId) {
		public SemanticUse { Objects.requireNonNull(kind); }
	}

	public record Guard(int id, int predicateOccurrenceId, int parentRegionId, String label) {
		public Guard { Objects.requireNonNull(label); }
	}

	/** Exact scalar value identity underlying a syntactic branch predicate. */
	public record GuardValueProvenance(int bindingId,List<Integer> callFrame,boolean inverted) {
		public GuardValueProvenance { callFrame=List.copyOf(callFrame); }
	}

	public record CarriedValue(int slot, String variable, int entryBindingId, int headBindingId,
		int backedgeBindingId, int lastBindingId) {
		public CarriedValue { Objects.requireNonNull(variable); }
	}

	public record LoopExecutionCase(LoopCardinality cardinality,double exactIterations,
		List<IterationPhase> bodyPhases) {
		public LoopExecutionCase {
			Objects.requireNonNull(cardinality);
			bodyPhases=List.copyOf(bodyPhases);
		}
	}

	public record LoopRecord(int id, long statementBlockId, int regionId, int guardId,int activationBindingId,
		int backedgeActivationBindingId,
		List<CarriedValue> carriedValues,List<LoopExecutionCase> executionCases) {
		public LoopRecord {
			carriedValues = List.copyOf(carriedValues);
			executionCases=List.copyOf(executionCases);
		}
	}

	/** Null phase values are deliberately unknown; a phase fact never guesses a branch. */
	public record GuardPhase(int loopId,int guardId,Boolean first,Boolean middle,Boolean last,String proof) {
		public GuardPhase { Objects.requireNonNull(proof); }
		public Boolean value(IterationPhase phase) {
			return switch(phase) { case FIRST -> first; case MIDDLE -> middle; case LAST -> last; };
		}
	}
	public record PhaseLoop(int loopId,List<Integer> callFrame,List<IterationPhase> reachablePhases,
		List<LoopExecutionCase> executionCases) {
		public PhaseLoop {
			callFrame=List.copyOf(callFrame);
			reachablePhases=List.copyOf(reachablePhases);
			executionCases=List.copyOf(executionCases);
		}
		public PhaseLoop(int loopId,List<Integer> callFrame) {
			this(loopId,callFrame,List.of(IterationPhase.FIRST,IterationPhase.MIDDLE,IterationPhase.LAST),List.of());
		}
		public PhaseLoop(int loopId,List<Integer> callFrame,List<IterationPhase> reachablePhases) {
			this(loopId,callFrame,reachablePhases,List.of());
		}
	}
	public record OccurrenceContext(List<Integer> callFrame,List<PhaseLoop> phaseLoops) {
		public OccurrenceContext { callFrame=List.copyOf(callFrame); phaseLoops=List.copyOf(phaseLoops); }
	}

	/** multiplier is exact when non-negative; -1 denotes a runtime-dependent symbolic count. */
	public record ExecutionFrequency(int regionId, int parentRegionId, FrequencyKind kind,
		int referenceId, double multiplier) {
		public ExecutionFrequency { Objects.requireNonNull(kind); }
	}

	public record FunctionSummary(int id, String functionKey, int regionId, long statementBlockId,
		List<String> formalNames,
		List<Integer> formalBindingIds, List<String> returnNames, List<Integer> returnBindingIds,
		List<Integer> compiledOwnerIds, int completionBindingId) {
		public FunctionSummary {
			Objects.requireNonNull(functionKey);
			formalNames = List.copyOf(formalNames);
			formalBindingIds = List.copyOf(formalBindingIds);
			returnNames = List.copyOf(returnNames);
			returnBindingIds = List.copyOf(returnBindingIds);
			compiledOwnerIds = List.copyOf(compiledOwnerIds);
		}
	}

	public record CallBinding(int id, int callOccurrenceId, long statementBlockId, int callStatementPosition,
		int regionId, int completionBindingId,
		int functionSummaryId,
		List<Integer> actualBindingIds, List<Integer> argumentBindingIds,
		List<Integer> returnBindingIds) {
		public CallBinding {
			actualBindingIds = List.copyOf(actualBindingIds);
			argumentBindingIds = List.copyOf(argumentBindingIds);
			returnBindingIds = List.copyOf(returnBindingIds);
		}
	}

	public record Diagnostic(Status status, String code, String message, int regionId) {
		public Diagnostic {
			Objects.requireNonNull(status);
			Objects.requireNonNull(code);
			Objects.requireNonNull(message);
		}
	}

	private final Status status;
	private final List<Region> regions;
	private final List<Occurrence> occurrences;
	private final List<ShapeFact> shapeFacts;
	private final List<BindingNode> bindings;
	private final List<Definition> definitions;
	private final List<InputBinding> inputBindings;
	private final List<SemanticUse> semanticUses;
	private final List<Guard> guards;
	private final List<LoopRecord> loops;
	private final List<GuardPhase> guardPhases;
	private final List<List<GuardPhase>> guardPhasesByLoop;
	private final List<GuardPhaseIndex> guardPhaseByLoopAndGuard;
	private final List<FunctionSummary> functions;
	private final List<CallBinding> calls;
	private final List<List<CallBinding>> callsByFunction;
	private final List<ExecutionFrequency> executionFrequencies;
	private final List<Diagnostic> diagnostics;
	private final Map<Hop, List<Integer>> occurrenceIdsByHop;
	private final Map<Hop, Integer> compiledOwnerByHop;
	private final int[][] inputBindingByOccurrence;
	private final Map<Integer, Integer> valueBindingByOccurrence;
	private final Map<Long, StatementBlock> statementBlocks;
	private final List<Hop> compiledOwnerHops;
	private final Map<Integer,List<List<Integer>>> callFramesByRegion;
	private final List<Map<List<Integer>,CanonicalGuard>> canonicalGuards;
	private final byte[][] loopExecutionMasks;
	private final int[] enclosingLoopByGuard;
	private final Map<Integer,List<OccurrenceContext>> occurrenceContextsByOccurrence = new ConcurrentHashMap<>();

	private MemoProgramFacts(Builder b) {
		regions = List.copyOf(b.regions);
		occurrences = List.copyOf(b.occurrences);
		shapeFacts = occurrences.stream().map(o -> new ShapeFact(o.id(), o.hop().getDataType(),
			o.hop().getValueType(), o.hop().getDim1(), o.hop().getDim2(), o.hop().getNnz())).toList();
		bindings = List.copyOf(b.bindingDrafts);
		definitions = List.copyOf(b.definitions);
		inputBindings = List.copyOf(b.inputBindings);
		semanticUses = List.copyOf(b.semanticUses);
		guards = List.copyOf(b.guards);
		loops = b.loops.stream().sorted(Comparator.comparingInt(LoopRecord::id)).toList();
		loopExecutionMasks=new byte[loops.size()][LoopCardinality.values().length];
		for(LoopRecord loop:loops)
			for(LoopExecutionCase executionCase:loop.executionCases()) {
				int cardinality=executionCase.cardinality().ordinal();
				loopExecutionMasks[loop.id()][cardinality]|=1;
				for(IterationPhase phase:executionCase.bodyPhases())
					loopExecutionMasks[loop.id()][cardinality]|=(byte)(1<<(phase.ordinal()+1));
			}
		enclosingLoopByGuard=new int[guards.size()];
		for(Guard guard:guards)
			enclosingLoopByGuard[guard.id()]=enclosingLoop(guard.parentRegionId());
		guardPhases = List.copyOf(b.guardPhases);
		List<List<GuardPhase>> phasesByLoop=new ArrayList<>(loops.size());
		for(int loop=0;loop<loops.size();loop++) phasesByLoop.add(new ArrayList<>());
		for(GuardPhase phase : guardPhases)
			phasesByLoop.get(phase.loopId()).add(phase);
		guardPhasesByLoop=phasesByLoop.stream().map(List::copyOf).toList();
		guardPhaseByLoopAndGuard=guardPhasesByLoop.stream().map(GuardPhaseIndex::new).toList();
		List<Map<List<Integer>,CanonicalGuard>> guardCache=new ArrayList<>(guards.size());
		for(int guard=0;guard<guards.size();guard++) guardCache.add(new ConcurrentHashMap<>());
		canonicalGuards=List.copyOf(guardCache);
		functions = List.copyOf(b.functionSummaries);
		calls = List.copyOf(b.calls);
		List<List<CallBinding>> indexedCalls = new ArrayList<>(functions.size());
		for(int function=0; function<functions.size(); function++) indexedCalls.add(new ArrayList<>());
		for(CallBinding call : calls)
			if(call.functionSummaryId()>=0 && call.functionSummaryId()<indexedCalls.size())
				indexedCalls.get(call.functionSummaryId()).add(call);
		callsByFunction = indexedCalls.stream().map(List::copyOf).toList();
		executionFrequencies = buildExecutionFrequencies(b);
		diagnostics = List.copyOf(b.diagnostics);
		status = diagnostics.stream().anyMatch(d -> d.status() == Status.UNSUPPORTED) ? Status.UNSUPPORTED
			: diagnostics.stream().anyMatch(d -> d.status() == Status.INCOMPLETE) ? Status.INCOMPLETE : Status.COMPLETE;
		Map<Hop,List<Integer>> occurrenceIndex = new IdentityHashMap<>();
		for(Occurrence occurrence : occurrences)
			occurrenceIndex.computeIfAbsent(occurrence.hop(), ignored -> new ArrayList<>()).add(occurrence.id());
		occurrenceIndex.replaceAll((hop, ids) -> List.copyOf(ids));
		occurrenceIdsByHop = Collections.unmodifiableMap(occurrenceIndex);
		// Occurrences and their input positions are dense, immutable fact identities.
		// Freeze the registered edges once instead of boxing a pair key on every lookup.
		int[] inputCounts=new int[occurrences.size()];
		for(InputBinding edge:inputBindings)
			inputCounts[edge.occurrenceId()]=Math.max(inputCounts[edge.occurrenceId()],edge.inputPosition()+1);
		inputBindingByOccurrence=new int[occurrences.size()][];
		for(int occurrence=0;occurrence<inputCounts.length;occurrence++) {
			inputBindingByOccurrence[occurrence]=new int[inputCounts[occurrence]];
			Arrays.fill(inputBindingByOccurrence[occurrence],-1);
		}
		for(InputBinding edge:inputBindings)
			inputBindingByOccurrence[edge.occurrenceId()][edge.inputPosition()]=edge.bindingId();
		valueBindingByOccurrence = Collections.unmodifiableMap(new LinkedHashMap<>(b.occurrenceValues));
		statementBlocks = Collections.unmodifiableMap(new LinkedHashMap<>(b.statementBlocks));
		List<Hop> owners = new ArrayList<>(Collections.nCopies(b.ownerIds.size(), null));
		for(Map.Entry<Hop,Integer> owner : b.ownerIds.entrySet()) owners.set(owner.getValue(), owner.getKey());
		compiledOwnerHops = List.copyOf(owners);
		compiledOwnerByHop = Collections.unmodifiableMap(new IdentityHashMap<>(b.ownerIds));
		Map<Integer,List<List<Integer>>> frames=new LinkedHashMap<>();
		for(Region region : regions) computeCallFrames(region.id(),frames,new LinkedHashSet<>());
		callFramesByRegion=Collections.unmodifiableMap(frames);
	}

	public static MemoProgramFacts analyze(DMLProgram program) {
		return new Builder(Objects.requireNonNull(program, "program")).build();
	}

	public Status status() { return status; }
	public List<Region> regions() { return regions; }
	public List<Occurrence> occurrences() { return occurrences; }
	public List<ShapeFact> shapeFacts() { return shapeFacts; }
	public List<BindingNode> bindings() { return bindings; }
	public List<Definition> definitions() { return definitions; }
	public List<InputBinding> inputBindings() { return inputBindings; }
	public List<SemanticUse> semanticUses() { return semanticUses; }
	public List<Guard> guards() { return guards; }
	public List<LoopRecord> loops() { return loops; }
	public List<GuardPhase> guardPhases() { return guardPhases; }
	public List<GuardPhase> guardPhases(int loopId) {
		return loopId<0 || loopId>=guardPhasesByLoop.size() ? List.of() : guardPhasesByLoop.get(loopId);
	}
	public Boolean guardValue(int loopId,int guardId,IterationPhase phase) {
		if(loopId<0 || loopId>=guardPhaseByLoopAndGuard.size() || guardId<0) return null;
		GuardPhase fact=guardPhaseByLoopAndGuard.get(loopId).get(guardId);
		return fact==null ? null : fact.value(phase);
	}
	public List<FunctionSummary> functions() { return functions; }
	public List<CallBinding> calls() { return calls; }
	/** All calls for one function in program-fact order, including inlined calls without an occurrence. */
	public List<CallBinding> callsForFunction(int functionId) {
		return functionId>=0 && functionId<callsByFunction.size() ? callsByFunction.get(functionId) : List.of();
	}
	public List<ExecutionFrequency> executionFrequencies() { return executionFrequencies; }
	public List<Diagnostic> diagnostics() { return diagnostics; }
	public Occurrence occurrence(int id) { return occurrences.get(id); }
	public BindingNode binding(int id) { return bindings.get(id); }
	public Guard guard(int id) { return guards.get(id); }
	/**
	 * Canonical value provenance for a guard in one exact invocation frame.
	 * Only semantics-preserving boolean aliases are crossed. SELECT, LOOP_HEAD,
	 * and LAST deliberately remain version/correlation barriers.
	 */
	public GuardValueProvenance guardValueProvenance(int guardId,List<Integer> callFrame) {
		return canonicalGuard(guardId,callFrame).provenance();
	}

	public MemoBindingResolver.GuardDecision guardDecision(int guardId,List<Integer> callFrame) {
		return canonicalGuard(guardId,callFrame).decision();
	}

	private CanonicalGuard canonicalGuard(int guardId,List<Integer> callFrame) {
		Map<List<Integer>,CanonicalGuard> cache=canonicalGuards.get(guardId);
		CanonicalGuard cached=cache.get(callFrame);
		if(cached!=null) return cached;
		List<Integer> frame=List.copyOf(callFrame);
		cached=cache.get(frame);
		if(cached!=null) return cached;
		int binding=valueBinding(guard(guardId).predicateOccurrenceId());
		GuardValueProvenance provenance=traceGuardValue(binding,frame,false,new LinkedHashSet<>(),0);
		MemoBindingResolver.GuardDecision decision=new MemoBindingResolver.GuardDecision(guardId,frame,
			provenance.bindingId(),provenance.callFrame());
		CanonicalGuard value=new CanonicalGuard(provenance,decision);
		CanonicalGuard prior=cache.putIfAbsent(frame,value);
		return prior==null ? value : prior;
	}

	/** True when a dependency guard reads the same exact boolean value in this frame. */
	public boolean guardDecisionMatches(MemoBindingResolver.GuardDecision decision,int syntacticGuardId,
		List<Integer> evaluationFrame) {
		return guardDecision(syntacticGuardId,evaluationFrame).equals(decision);
	}

	/** Convert a syntactic branch truth to the canonical underlying value truth. */
	public boolean canonicalGuardValue(int guardId,List<Integer> callFrame,boolean branchValue) {
		return guardValueProvenance(guardId,callFrame).inverted() ? !branchValue : branchValue;
	}

	/** Convert a canonical underlying value truth to this syntactic branch truth. */
	public boolean branchGuardValue(int guardId,List<Integer> callFrame,boolean canonicalValue) {
		return guardValueProvenance(guardId,callFrame).inverted() ? !canonicalValue : canonicalValue;
	}

	private GuardValueProvenance traceGuardValue(int bindingId,List<Integer> frame,boolean inverted,
		Set<GuardBindingFrame> path,int depth) {
		if(bindingId<0 || bindingId>=bindings.size() || depth>bindings.size()+calls.size())
			return new GuardValueProvenance(bindingId,frame,inverted);
		GuardBindingFrame visit=new GuardBindingFrame(bindingId,frame);
		if(!path.add(visit)) return new GuardValueProvenance(bindingId,frame,inverted);
		try {
			BindingNode binding=binding(bindingId);
			return switch(binding.kind()) {
				case DEFINITION -> traceGuardDefinition(binding,frame,inverted,path,depth);
				case CALL_ARG -> binding.children().isEmpty()
					? new GuardValueProvenance(bindingId,frame,inverted)
					: traceGuardValue(binding.children().get(0),frame,inverted,path,depth+1);
				case FORMAL -> traceGuardFormal(binding,frame,inverted,path,depth);
				case CALL_RETURN -> traceGuardReturn(binding,frame,inverted,path,depth);
				case SELECT, LOOP_HEAD, LAST, CALL_COMPLETION, UNBOUND, MISSING ->
					new GuardValueProvenance(bindingId,frame,inverted);
			};
		}
		finally { path.remove(visit); }
	}

	private GuardValueProvenance traceGuardDefinition(BindingNode binding,List<Integer> frame,boolean inverted,
		Set<GuardBindingFrame> path,int depth) {
		if(!binding.children().isEmpty())
			return traceGuardValue(binding.children().get(0),frame,inverted,path,depth+1);
		if(binding.referenceId()<0 || binding.referenceId()>=occurrences.size())
			return new GuardValueProvenance(binding.id(),frame,inverted);
		Occurrence occurrence=occurrence(binding.referenceId());
		Hop hop=occurrence.hop();
		if(hop instanceof DataOp data && data.getInput().size()==1
			&& (data.getOp()==OpOpData.TRANSIENTWRITE || data.getOp()==OpOpData.FUNCTIONOUTPUT)) {
			int input=inputBinding(occurrence.id(),0);
			if(input>=0) return traceGuardValue(input,frame,inverted,path,depth+1);
		}
		if(hop instanceof UnaryOp unary && unary.getInput().size()==1
			&& (unary.getOp()==OpOp1.NOT || unary.getOp()==OpOp1.CAST_AS_BOOLEAN)) {
			int input=inputBinding(occurrence.id(),0);
			if(input>=0) return traceGuardValue(input,frame,
				unary.getOp()==OpOp1.NOT ? !inverted : inverted,path,depth+1);
		}
		return new GuardValueProvenance(binding.id(),frame,inverted);
	}

	private GuardValueProvenance traceGuardFormal(BindingNode binding,List<Integer> frame,boolean inverted,
		Set<GuardBindingFrame> path,int depth) {
		for(int index=frame.size()-1;index>=0;index--) {
			CallBinding call=call(frame.get(index));
			if(call.functionSummaryId()==binding.referenceId() && binding.position()<call.actualBindingIds().size())
				return traceGuardValue(call.actualBindingIds().get(binding.position()),frame.subList(0,index),
					inverted,path,depth+1);
		}
		return new GuardValueProvenance(binding.id(),frame,inverted);
	}

	private GuardValueProvenance traceGuardReturn(BindingNode binding,List<Integer> frame,boolean inverted,
		Set<GuardBindingFrame> path,int depth) {
		if(binding.children().isEmpty()) return new GuardValueProvenance(binding.id(),frame,inverted);
		if(binding.children().size()>1)
			return traceGuardValue(binding.children().get(1),frame,inverted,path,depth+1);
		List<Integer> nested=new ArrayList<>(frame); nested.add(binding.referenceId());
		return traceGuardValue(binding.children().get(0),List.copyOf(nested),inverted,path,depth+1);
	}
	public Region region(int id) { return regions.get(id); }
	public int enclosingLoop(int regionId) {
		for(int current=regionId; current>=0; current=regions.get(current).parentId())
			if(regions.get(current).kind() == RegionKind.LOOP_BODY) return regions.get(current).loopId();
		return -1;
	}
	public LoopRecord loop(int id) { return loops.get(id); }
	public List<LoopExecutionCase> loopExecutionCases(int loopId) { return loop(loopId).executionCases(); }
	boolean loopCardinalityReachable(int loopId,LoopCardinality cardinality) {
		if(loopId<0 || loopId>=loopExecutionMasks.length || cardinality==null)
			return loopExecutionCases(loopId).stream().anyMatch(value -> value.cardinality()==cardinality);
		return (loopExecutionMasks[loopId][cardinality.ordinal()]&1)!=0;
	}
	boolean loopPhaseReachable(int loopId,LoopCardinality cardinality,IterationPhase phase) {
		if(loopId<0 || loopId>=loopExecutionMasks.length || cardinality==null || phase==null)
			return loopExecutionCases(loopId).stream().filter(value -> value.cardinality()==cardinality)
				.anyMatch(value -> value.bodyPhases().contains(phase));
		return (loopExecutionMasks[loopId][cardinality.ordinal()]&(1<<(phase.ordinal()+1)))!=0;
	}
	int guardEnclosingLoop(int guardId) {
		if(guardId<0 || guardId>=enclosingLoopByGuard.length)
			return enclosingLoop(guard(guardId).parentRegionId());
		return enclosingLoopByGuard[guardId];
	}
	public FunctionSummary functionSummary(int id) { return functions.get(id); }
	/** Exact compiled function boundary used by runtime invocation scopes; -1 denotes a fully inlined summary. */
	public long functionStatementBlockId(int functionSummaryId) {
		return functionSummary(functionSummaryId).statementBlockId();
	}
	public CallBinding call(int id) { return calls.get(id); }
	public ExecutionFrequency executionFrequency(int regionId) { return executionFrequencies.get(regionId); }
	public List<List<Integer>> callFramesForRegion(int regionId) {
		return callFramesByRegion.getOrDefault(regionId,List.of());
	}
	/** Compact reachable contexts; phase alternatives remain lazy and are never tuple-materialized here. */
	public List<OccurrenceContext> occurrenceContexts(int occurrenceId) {
		occurrence(occurrenceId);
		return occurrenceContextsByOccurrence.computeIfAbsent(occurrenceId,this::buildOccurrenceContexts);
	}

	private List<OccurrenceContext> buildOccurrenceContexts(int occurrenceId) {
		Occurrence occurrence=occurrence(occurrenceId);
		List<OccurrenceContext> result=new ArrayList<>();
		for(List<Integer> frame : callFramesForRegion(occurrence.regionId())) {
			Set<PhaseLoop> loops=new LinkedHashSet<>();
			boolean reachable=true;
			for(int depth=frame.size(); depth>=0; depth--) {
				List<Integer> prefix=frame.subList(0,depth);
				int current=depth==frame.size() ? occurrence.regionId()
					: occurrence(call(frame.get(depth)).callOccurrenceId()).regionId();
				List<Integer> ancestors=new ArrayList<>();
				for(int region=current; region>=0; region=this.region(region).parentId())
					ancestors.add(region);
				for(int region : ancestors) {
					int loop=this.region(region).loopId();
					if(loop<0) continue;
					List<LoopExecutionCase> cases=reachableExecutionCases(loop,ancestors);
					List<IterationPhase> phases=cases.stream().flatMap(value -> value.bodyPhases().stream())
						.distinct().toList();
					if(phases.isEmpty()) { reachable=false; break; }
					loops.add(new PhaseLoop(loop,prefix,phases,cases));
				}
				if(!reachable) break;
			}
			if(reachable) result.add(new OccurrenceContext(frame,List.copyOf(loops)));
		}
		return List.copyOf(result);
	}

	private List<LoopExecutionCase> reachableExecutionCases(int loopId,List<Integer> ancestorRegions) {
		List<LoopExecutionCase> cases=new ArrayList<>();
		for(LoopExecutionCase executionCase : loopExecutionCases(loopId)) {
			if(executionCase.cardinality()==LoopCardinality.ZERO) continue;
			List<IterationPhase> phases=new ArrayList<>(executionCase.bodyPhases());
		for(int regionId : ancestorRegions) {
			Region branch=region(regionId);
			if(branch.kind()!=RegionKind.IF_THEN && branch.kind()!=RegionKind.IF_ELSE) continue;
			Guard guard=guard(branch.guardId());
			boolean required=branch.kind()==RegionKind.IF_THEN;
			phases.removeIf(phase -> {
				Boolean value=guardValue(loopId,guard.id(),phase);
				return value!=null && value!=required;
			});
		}
			if(!phases.isEmpty()) cases.add(new LoopExecutionCase(executionCase.cardinality(),
				executionCase.exactIterations(),phases));
		}
		return List.copyOf(cases);
	}
	public List<Integer> occurrenceIds(Hop hop) {
		return occurrenceIdsByHop.getOrDefault(hop, List.of());
	}
	public int occurrenceId(Hop hop) {
		List<Integer> ids = occurrenceIds(hop);
		if(ids.size() > 1)
			throw new IllegalStateException("Hop " + hop.getHopID() + " has multiple occurrence identities: " + ids);
		return ids.isEmpty() ? -1 : ids.get(0);
	}
	public int compiledOwnerId(Hop hop) { return compiledOwnerByHop.getOrDefault(hop, -1); }
	public int inputBinding(int occurrenceId, int inputPosition) {
		if(occurrenceId<0 || occurrenceId>=inputBindingByOccurrence.length || inputPosition<0) return -1;
		int[] inputs=inputBindingByOccurrence[occurrenceId];
		return inputPosition<inputs.length ? inputs[inputPosition] : -1;
	}
	public int valueBinding(int occurrenceId) { return valueBindingByOccurrence.getOrDefault(occurrenceId, -1); }
	public ShapeFact shapeFact(int occurrenceId) { return shapeFacts.get(occurrenceId); }
	public StatementBlock statementBlock(long id) { return statementBlocks.get(id); }
	public int compiledOwnerCount() { return compiledOwnerHops.size(); }
	public Hop compiledOwnerHop(int ownerId) { return compiledOwnerHops.get(ownerId); }
	public void requireComplete() {
		if(status != Status.COMPLETE)
			throw new IllegalStateException("Memo program facts are " + status + ": " + diagnostics);
	}

	private List<List<Integer>> computeCallFrames(int regionId,Map<Integer,List<List<Integer>>> memo,
		Set<Integer> visitingFunctions) {
		List<List<Integer>> cached=memo.get(regionId);
		if(cached!=null) return cached;
		int function=-1;
		for(int current=regionId; current>=0; current=regions.get(current).parentId())
			if(regions.get(current).kind()==RegionKind.FUNCTION) {
				int functionRegion=current;
				function=functions.stream().filter(summary -> summary.regionId()==functionRegion)
					.mapToInt(FunctionSummary::id).findFirst().orElse(-1);
				break;
			}
		if(function<0) {
			List<List<Integer>> main=List.of(List.of()); memo.put(regionId,main); return main;
		}
		if(!visitingFunctions.add(function)) return List.of(); // recursion is diagnosed separately
		List<List<Integer>> frames=new ArrayList<>();
		for(CallBinding call : calls) if(call.functionSummaryId()==function && call.callOccurrenceId()>=0) {
			Occurrence caller=occurrences.get(call.callOccurrenceId());
			for(List<Integer> outer : computeCallFrames(caller.regionId(),memo,visitingFunctions)) {
				List<Integer> frame=new ArrayList<>(outer); frame.add(call.id()); frames.add(List.copyOf(frame));
			}
		}
		visitingFunctions.remove(function);
		List<List<Integer>> immutable=List.copyOf(new LinkedHashSet<>(frames));
		memo.put(regionId,immutable); return immutable;
	}

	private static long edgeKey(int occurrence, int position) {
		return ((long) occurrence << 32) | (position & 0xffffffffL);
	}

	private static List<ExecutionFrequency> buildExecutionFrequencies(Builder b) {
		List<ExecutionFrequency> result = new ArrayList<>(b.regions.size());
		for(Region region : b.regions) {
			FrequencyKind kind = FrequencyKind.ONCE;
			int reference = -1;
			double multiplier = 1;
			if(region.kind() == RegionKind.IF_THEN || region.kind() == RegionKind.IF_ELSE) {
				kind = region.kind() == RegionKind.IF_THEN ? FrequencyKind.GUARDED_THEN : FrequencyKind.GUARDED_ELSE;
				reference = region.guardId();
			}
			else if(region.kind() == RegionKind.LOOP_BODY) {
				kind = FrequencyKind.LOOP_ITERATIONS;
				reference = region.loopId();
				multiplier = b.staticLoopIterations.getOrDefault(region.loopId(), -1D);
			}
			else if(region.kind() == RegionKind.FUNCTION) {
				kind = FrequencyKind.FUNCTION_INVOCATIONS;
				reference = b.functionSummaries.stream().filter(Objects::nonNull)
					.filter(function -> function.regionId() == region.id()).mapToInt(FunctionSummary::id)
					.findFirst().orElse(-1);
				multiplier = -1;
			}
			result.add(new ExecutionFrequency(region.id(), region.parentId(), kind, reference, multiplier));
		}
		return List.copyOf(result);
	}

	private record CanonicalGuard(GuardValueProvenance provenance,
		MemoBindingResolver.GuardDecision decision) { }
	private static final class GuardPhaseIndex {
		private final int[] guardIds;
		private final GuardPhase[] phases;
		private GuardPhaseIndex(List<GuardPhase> registered) {
			Map<Integer,GuardPhase> first=new TreeMap<>();
			for(GuardPhase phase : registered) first.putIfAbsent(phase.guardId(),phase);
			guardIds=new int[first.size()]; phases=new GuardPhase[first.size()];
			int index=0;
			for(var entry : first.entrySet()) {
				guardIds[index]=entry.getKey(); phases[index]=entry.getValue(); index++;
			}
		}
		private GuardPhase get(int guardId) {
			int index=Arrays.binarySearch(guardIds,guardId);
			return index<0 ? null : phases[index];
		}
	}
	private record GuardBindingFrame(int bindingId,List<Integer> callFrame) {
		private GuardBindingFrame { callFrame=List.copyOf(callFrame); }
	}

	private static final class Builder {
		private static final long MAX_EXACT_DOUBLE_INTEGER=1L<<53;
		private final DMLProgram program;
		private final List<Region> regions = new ArrayList<>();
		private final List<Occurrence> occurrences = new ArrayList<>();
		private final List<BindingNode> bindingDrafts = new ArrayList<>();
		private final List<Definition> definitions = new ArrayList<>();
		private final List<InputBinding> inputBindings = new ArrayList<>();
		private final List<SemanticUse> semanticUses = new ArrayList<>();
		private final List<Guard> guards = new ArrayList<>();
		private final List<LoopRecord> loops = new ArrayList<>();
		private final List<GuardPhase> guardPhases = new ArrayList<>();
		private final List<FunctionSummary> functionSummaries = new ArrayList<>();
		private final List<CallBinding> calls = new ArrayList<>();
		private final List<Diagnostic> diagnostics = new ArrayList<>();
		private final IdentityHashMap<Hop,Integer> ownerIds = new IdentityHashMap<>();
		private final Map<OccurrenceKey,Integer> occurrenceIds = new LinkedHashMap<>();
		private final IdentityHashMap<StatementBlock,Integer> blockRegions = new IdentityHashMap<>();
		private final Map<String,Integer> versions = new TreeMap<>();
		private final Map<String,Integer> functionSummaryIds = new TreeMap<>();
		private final Map<String,FunctionStatementBlock> functionBlocks = new TreeMap<>();
		private final Map<Integer,Integer> occurrenceValues = new LinkedHashMap<>();
		private final Map<Long,Integer> inputBindingValues = new LinkedHashMap<>();
		private final Set<Long> registeredInputEdges = new LinkedHashSet<>();
		private final Set<Integer> registeredControlRoots = new LinkedHashSet<>();
		private final Map<Long,StatementBlock> statementBlocks = new LinkedHashMap<>();
		private final Map<Integer,Double> staticLoopIterations = new LinkedHashMap<>();
		private final Map<Integer,ParForProof> parForProofs = new LinkedHashMap<>();
		private final Map<Integer,Integer> callRegions = new LinkedHashMap<>();
		private final Set<String> activeFunctions = new LinkedHashSet<>();
		private int nextLoopId;
		private int nextTraversalId;

		private Builder(DMLProgram program) { this.program = program; }

		private MemoProgramFacts build() {
			regions.add(new Region(0, -1, RegionKind.PROGRAM, "main", -1, -1));
			analyzeFunctions();
			analyzeSequence(program.getStatementBlocks(), 0, "main", new LinkedHashMap<>(),new FlowState(-1));
			validateActiveLoopIterations();
			for(int i = 0; i < bindingDrafts.size(); i++)
				if(bindingDrafts.get(i) == null)
					setBinding(i, BindingKind.MISSING, List.of(), -1, -1, "unresolved-placeholder");
			return new MemoProgramFacts(this);
		}

		private void analyzeFunctions() {
			List<Map.Entry<String,FunctionStatementBlock>> entries = new ArrayList<>(
				program.getNamedNSFunctionStatementBlocks().entrySet());
			entries.sort(Map.Entry.comparingByKey());
			for(Map.Entry<String,FunctionStatementBlock> entry : entries) {
				String key = normalizeFunctionKey(entry.getKey());
				functionSummaryIds.put(key, functionSummaryIds.size());
				functionBlocks.put(key, entry.getValue());
				functionSummaries.add(null);
			}
			for(Map.Entry<String,FunctionStatementBlock> entry : entries)
				analyzeFunction(normalizeFunctionKey(entry.getKey()), entry.getValue());
		}

		private void analyzeFunction(String key, FunctionStatementBlock block) {
			Integer declaredId = functionSummaryIds.get(key);
			if(declaredId != null && functionSummaries.get(declaredId) != null) return;
			if(!activeFunctions.add(key)) {
				diagnostic(Status.UNSUPPORTED, "RECURSIVE_FUNCTION", "Recursive function: " + key, 0);
				return;
			}
			statementBlocks.put(block.getSBID(), block);
			for(String callee : collectFunctionCalls(((FunctionStatement) block.getStatement(0)).getBody())) {
				FunctionStatementBlock calleeBlock = functionBlocks.get(callee);
				if(calleeBlock != null)
					analyzeFunction(callee, calleeBlock);
			}
			FunctionStatement statement = (FunctionStatement) block.getStatement(0);
			int functionId = declaredId;
			int region = addRegion(0, RegionKind.FUNCTION, "function:" + key, -1, -1);
			Map<String,Integer> env = new LinkedHashMap<>();
			List<String> formalNames = new ArrayList<>();
			List<Integer> formalBindings = new ArrayList<>();
			for(int pos = 0; pos < statement.getInputParams().size(); pos++) {
				String name = statement.getInputParams().get(pos).getName();
				formalNames.add(name);
				int binding = addBinding(BindingKind.FORMAL, List.of(), functionId, pos, key + ':' + name);
				formalBindings.add(binding);
				env.put(name, binding);
			}
			int occurrenceStart = occurrences.size();
			FlowState functionFlow = new FlowState(-1);
			Map<String,Integer> exit = analyzeSequence(statement.getBody(), region, "function:" + key, env,
				functionFlow);
			List<String> returnNames = new ArrayList<>();
			List<Integer> returnBindings = new ArrayList<>();
			for(var output : statement.getOutputParams()) {
				returnNames.add(output.getName());
				Integer binding = exit.get(output.getName());
				if(binding == null) {
					binding = missing("MISSING_FUNCTION_RETURN", key + ':' + output.getName(), region);
				}
				returnBindings.add(binding);
				semanticUses.add(new SemanticUse(semanticUses.size(), -1, returnBindings.size() - 1,
					binding, UseKind.FUNCTION_RETURN, region));
			}
			List<Integer> owners = new ArrayList<>();
			for(int i = occurrenceStart; i < occurrences.size(); i++)
				if(occurrences.get(i).context().startsWith("function:" + key))
					owners.add(occurrences.get(i).compiledOwnerId());
			functionSummaries.set(functionId, new FunctionSummary(functionId, key, region, block.getSBID(),
				formalNames, formalBindings,
				returnNames, returnBindings, owners,functionFlow.activation));
			activeFunctions.remove(key);
		}

		private Map<String,Integer> analyzeSequence(List<StatementBlock> blocks, int parentRegion,
			String context, Map<String,Integer> input,FlowState flow) {
			Map<String,Integer> env = new LinkedHashMap<>(input);
			for(StatementBlock block : blocks)
				env = analyzeBlock(block, parentRegion, context, env,flow);
			return env;
		}

		private Map<String,Integer> analyzeBlock(StatementBlock block, int parentRegion, String context,
			Map<String,Integer> input,FlowState flow) {
			statementBlocks.put(block.getSBID(), block);
			if(block instanceof IfStatementBlock)
				return analyzeIf((IfStatementBlock) block, parentRegion, context, input,flow);
			if(block instanceof WhileStatementBlock || block instanceof ForStatementBlock)
				return analyzeLoop(block, parentRegion, context, input,flow);
			if(block instanceof FunctionStatementBlock)
				return new LinkedHashMap<>(input); // compiled separately, never executed as a main block
			int region = blockRegions.computeIfAbsent(block,
				b -> addRegion(parentRegion, RegionKind.BASIC_BLOCK, context + "/sb-" + b.getSBID(), -1, -1));
			Map<String,Integer> env = new LinkedHashMap<>(input);
			if(block.getHops() != null)
				for(Hop root : block.getHops()) {
					int occurrence = visitRoot(root, block, region, context, env, false,flow);
					applyRootDefinition(root, occurrence, region, env);
				}
			bindInlinedFunctionCalls(block, region, env);
			return env;
		}

		private Map<String,Integer> analyzeIf(IfStatementBlock block, int parentRegion, String context,
			Map<String,Integer> input,FlowState flow) {
			int predicate = visitRoot(block.getPredicateHops(), block, parentRegion,
				context + "/if-predicate", input, true,flow);
			recordControlRoot(predicate, block.getPredicateHops(), parentRegion);
			int guard = guards.size();
			guards.add(new Guard(guard, predicate, parentRegion, context + "/if-" + guard));
			IfStatement statement = (IfStatement) block.getStatement(0);
			int thenRegion = addRegion(parentRegion, RegionKind.IF_THEN, context + "/if-" + guard + "/then", guard, -1);
			int elseRegion = addRegion(parentRegion, RegionKind.IF_ELSE, context + "/if-" + guard + "/else", guard, -1);
			FlowState thenFlow = new FlowState(flow.activation), elseFlow = new FlowState(flow.activation);
			Map<String,Integer> thenEnv = analyzeSequence(statement.getIfBody(), thenRegion,
				context + "/if-" + guard + "/then", input,thenFlow);
			Map<String,Integer> elseEnv = analyzeSequence(statement.getElseBody(), elseRegion,
				context + "/if-" + guard + "/else", input,elseFlow);
			flow.activation = mergeGuardedBinding(thenFlow.activation,elseFlow.activation,guard,
				context + "/if-" + guard + "#continuation");
			Map<String,Integer> merged = new LinkedHashMap<>();
			Set<String> names = new LinkedHashSet<>(input.keySet());
			names.addAll(thenEnv.keySet()); names.addAll(elseEnv.keySet());
			for(String name : names) {
				Integer inherited = input.get(name);
				Integer leftValue = thenEnv.get(name);
				Integer rightValue = elseEnv.get(name);
				int left = leftValue != null ? leftValue : inherited != null ? inherited
					: addBinding(BindingKind.UNBOUND,List.of(),guard,0,name + "#unbound-then");
				int right = rightValue != null ? rightValue : inherited != null ? inherited
					: addBinding(BindingKind.UNBOUND,List.of(),guard,1,name + "#unbound-else");
				merged.put(name, left == right ? left
					: addBinding(BindingKind.SELECT, List.of(left, right), guard, -1, name));
			}
			return merged;
		}

		private int mergeGuardedBinding(int thenBinding,int elseBinding,int guard,String label) {
			if(thenBinding == elseBinding) return thenBinding;
			if(thenBinding < 0 && elseBinding < 0) return -1;
			int thenValue = thenBinding >= 0 ? thenBinding
				: addBinding(BindingKind.CALL_COMPLETION,List.of(),-1,-1,label + "#normal-then");
			int elseValue = elseBinding >= 0 ? elseBinding
				: addBinding(BindingKind.CALL_COMPLETION,List.of(),-1,-1,label + "#normal-else");
			return addBinding(BindingKind.SELECT,List.of(thenValue,elseValue),guard,-1,label);
		}

		private Map<String,Integer> analyzeLoop(StatementBlock block, int parentRegion, String context,
			Map<String,Integer> input,FlowState flow) {
			int loopId = nextLoopId++;
			List<StatementBlock> body;
			Hop predicate;
			ForStatementBlock forBlock = null;
			if(block instanceof WhileStatementBlock) {
				body = ((WhileStatement) block.getStatement(0)).getBody();
				predicate = ((WhileStatementBlock) block).getPredicateHops();
			}
			else {
				body = ((ForStatement) block.getStatement(0)).getBody();
				forBlock = (ForStatementBlock) block;
				predicate = forBlock.getToHops();
				staticLoopIterations.put(loopId, staticForIterations(forBlock));
			}
			if(forBlock == null) staticLoopIterations.put(loopId, -1D);
			LoopFlow loopFlow = analyzeLoopFlow(block, body);
			Set<String> updated = loopFlow.updated();
			List<String> compilerOutputs = block.getOutputNamesofSB();
			Set<String> liveOut = compilerOutputs == null
				? new LinkedHashSet<>(updated) : new LinkedHashSet<>(compilerOutputs);
			Set<String> carriedNames = new LinkedHashSet<>(loopFlow.readBeforeDefinition());
			for(String name : updated)
				if(input.containsKey(name) && liveOut.contains(name)
					&& !loopFlow.definitelyDefined().contains(name)) carriedNames.add(name);
			carriedNames.retainAll(updated);
			// A backedge cannot ground itself. Without an entry definition, analyzeSequence
			// reports the exact read site as MISSING_REACHING_DEFINITION instead of creating
			// a synthetic MISSING_LOOP_ENTRY for every compiler-conservative loop live-out.
			carriedNames.retainAll(input.keySet());
			Set<String> loopOutputs = new LinkedHashSet<>(carriedNames);
			for(String name : updated) if(liveOut.contains(name)) loopOutputs.add(name);
			int region = addRegion(parentRegion, RegionKind.LOOP_BODY, context + "/loop-" + loopId, -1, loopId);
			Map<String,Integer> headEnv = new LinkedHashMap<>(input);
			Map<String,Integer> entryByName = new LinkedHashMap<>();
			Map<String,Integer> headByName = new LinkedHashMap<>();
			String forIteratorName = null;
			int forIteratorBinding = -1;
			int fromOccurrence = -1, incrementOccurrence = -1;
			if(forBlock != null) {
				String loopVariable = forBlock.getIterPredicate().getIterVar().getName();
				forIteratorName = loopVariable;
				Hop from = forBlock.getFromHops();
				fromOccurrence = visitRoot(from, block, region, context + "/loop-" + loopId + "/from",
					input, true,flow);
				recordControlRoot(fromOccurrence, from, region);
				incrementOccurrence = visitRoot(forBlock.getIncrementHops(), block, region,
					context + "/loop-" + loopId + "/increment",
					input, true,flow);
				recordControlRoot(incrementOccurrence, forBlock.getIncrementHops(), region);
				int source = from == null ? missing("MISSING_FOR_INITIAL_VALUE", loopVariable, region)
					: valueBinding(fromOccurrence, from, input, region);
				int definition = definitions.size();
				definitions.add(new Definition(definition, loopVariable, 1, -1, source, region));
				forIteratorBinding = addBinding(BindingKind.DEFINITION, List.of(source), definition,
					-1, loopVariable + "#for-iterator");
				headEnv.put(loopVariable, forIteratorBinding);
			}
			for(String name : carriedNames) {
				Integer inputBinding = input.get(name);
				int entry = inputBinding != null ? inputBinding : missing("MISSING_LOOP_ENTRY", name, region);
				int head = reserveBinding();
				entryByName.put(name, entry); headByName.put(name, head); headEnv.put(name, head);
			}
			int loopActivation = flow.activation;
			int predicateOccurrence = visitRoot(predicate, block, region,
				context + "/loop-" + loopId + "/predicate", headEnv, true,flow);
			recordControlRoot(predicateOccurrence, predicate, region);
			int guardId = guards.size();
			guards.add(new Guard(guardId, predicateOccurrence, region, context + "/loop-" + loopId));
			FlowState bodyFlow = new FlowState(flow.activation);
			Map<String,Integer> bodyExit = analyzeSequence(body, region, context + "/loop-" + loopId + "/body",
				headEnv,bodyFlow);
			flow.activation = bodyFlow.activation;
			Map<String,Integer> output = new LinkedHashMap<>(input);
			List<CarriedValue> carried = new ArrayList<>();
			int slot = 0;
			for(String name : loopOutputs) {
				Integer bodyBinding = bodyExit.get(name);
				Integer head = headByName.get(name);
				if(bodyBinding == null && head == null) continue; // branch-local temporary, not a loop output
				int backedge = bodyBinding != null ? bodyBinding : missing("MISSING_LOOP_BACKEDGE", name, region);
				Integer incoming=input.get(name);
				int zeroEntry = incoming!=null ? incoming : addBinding(BindingKind.UNBOUND,List.of(),loopId,-1,
					name + "#zero-iteration-unbound");
				int carriedSlot = head == null ? -1 : slot;
				if(head != null)
					setBinding(head, BindingKind.LOOP_HEAD, List.of(entryByName.get(name), backedge),
						loopId, carriedSlot, name);
				int last = addBinding(BindingKind.LAST, List.of(zeroEntry,backedge), loopId, carriedSlot, name);
				output.put(name, last);
				if(head != null) {
					carried.add(new CarriedValue(carriedSlot, name, entryByName.get(name), head, backedge, last));
					slot++;
				}
			}
			if(forIteratorName != null) {
				int zeroIterator=addBinding(BindingKind.UNBOUND,List.of(),loopId,-1,
					forIteratorName + "#zero-iteration-unbound");
				output.put(forIteratorName, addBinding(BindingKind.LAST,
					List.of(zeroIterator,forIteratorBinding),loopId,-1,forIteratorName + "#for-exit"));
			}
			loops.add(new LoopRecord(loopId, block.getSBID(), region, guardId,loopActivation,
				bodyFlow.activation,carried,loopExecutionCases(staticLoopIterations.getOrDefault(loopId,-1D))));
			if(block instanceof ParForStatementBlock)
				parForProofs.put(loopId,new ParForProof(loopId,region,fromOccurrence,predicateOccurrence,
					incrementOccurrence));
			deriveGuardPhases(loopId,region,forBlock,carried);
			return output;
		}

		private static List<LoopExecutionCase> loopExecutionCases(double iterations) {
			if(iterations==0D)
				return List.of(new LoopExecutionCase(LoopCardinality.ZERO,0D,List.of()));
			if(iterations==1D)
				return List.of(new LoopExecutionCase(LoopCardinality.ONE,1D,List.of(IterationPhase.FIRST)));
			if(iterations>=2D)
				return List.of(new LoopExecutionCase(LoopCardinality.MANY,iterations,iterations==2D
					? List.of(IterationPhase.FIRST,IterationPhase.LAST)
					: List.of(IterationPhase.FIRST,IterationPhase.MIDDLE,IterationPhase.LAST)));
			return List.of(
				new LoopExecutionCase(LoopCardinality.ZERO,-1D,List.of()),
				new LoopExecutionCase(LoopCardinality.ONE,-1D,List.of(IterationPhase.FIRST)),
				new LoopExecutionCase(LoopCardinality.MANY,-1D,
					List.of(IterationPhase.FIRST,IterationPhase.MIDDLE,IterationPhase.LAST)));
		}

		private void deriveGuardPhases(int loopId,int loopRegion,ForStatementBlock forBlock,
			List<CarriedValue> carried) {
			Map<String,double[]> values = new LinkedHashMap<>();
			String proof = "";
			if(forBlock != null) {
				double[] range = staticForRange(forBlock);
				if(range != null) {
					String iterator = forBlock.getIterPredicate().getIterVar().getName();
					double iterations = staticForIterations(forBlock);
					// MIDDLE denotes every iteration between FIRST and LAST. One sample
					// cannot prove an arbitrary scalar predicate constant over that range;
					// it is exact here only when the middle phase is a singleton.
					values.put(iterator,new double[] {range[0],iterations == 3 ? range[0]+range[2] : Double.NaN,
						range[0]+(iterations-1)*range[2]});
					proof = "static-for-affine-iterator";
				}
			}
			for(Guard guard : guards) {
				if(!isWithinRegion(guard.parentRegionId(),loopRegion)) continue;
				Hop predicate = guard.predicateOccurrenceId() < 0 ? null : occurrences.get(guard.predicateOccurrenceId()).hop();
				for(Map.Entry<String,double[]> variable : values.entrySet()) {
					Boolean first = predicateValue(predicate,variable.getKey(),variable.getValue()[0]);
					Boolean middle = Double.isNaN(variable.getValue()[1]) ? null
						: predicateValue(predicate,variable.getKey(),variable.getValue()[1]);
					Boolean last = predicateValue(predicate,variable.getKey(),variable.getValue()[2]);
					if(first != null || middle != null || last != null) {
						guardPhases.add(new GuardPhase(loopId,guard.id(),first,middle,last,proof+':'+variable.getKey()));
						break;
					}
				}
			}
			for(CarriedValue value : carried) {
				Double entry=numericBinding(value.entryBindingId(),new LinkedHashSet<>());
				Double delta=affineDelta(value.backedgeBindingId(),value.variable(),new LinkedHashSet<>());
				// CP integer scalar arithmetic raises on positive overflow instead of
				// wrapping (BinaryScalarScalarCPInstruction); FP addition may plateau or
				// reach +Inf but cannot reverse this positive affine relation.
				if(entry==null || delta==null || delta<=0) continue;
				for(Guard guard : guards) {
					if(!isWithinRegion(guard.parentRegionId(),loopRegion)
						|| guardPhases.stream().anyMatch(fact -> fact.loopId()==loopId && fact.guardId()==guard.id())) continue;
					Hop predicate=guard.predicateOccurrenceId()<0 ? null : occurrences.get(guard.predicateOccurrenceId()).hop();
					Boolean[] phases=affinePredicateValues(predicate,value.variable(),entry,delta);
					if(phases!=null) guardPhases.add(new GuardPhase(loopId,guard.id(),phases[0],phases[1],phases[2],
						"carried-positive-affine-counter:"+value.variable()));
				}
			}
			deriveSymbolicGuardPhases(loopId,loopRegion);
		}

		/**
		 * Exact scalar propagation over the existing symbolic binding DAG. This
		 * resolves guards such as an unconditional first-iteration assignment
		 * followed by updates under a phase-proven outer guard. Unknown arms are
		 * joined only when they produce the same scalar value.
		 */
		private void deriveSymbolicGuardPhases(int loopId,int loopRegion) {
			boolean changed;
			int remaining=guards.size()+1;
			do {
				changed=false;
				for(Guard guard : guards) {
					if(!isWithinRegion(guard.parentRegionId(),loopRegion)
						|| guard.predicateOccurrenceId()<0) continue;
					Integer binding=occurrenceValues.get(guard.predicateOccurrenceId());
					if(binding==null) continue;
					Boolean first=phaseBooleanBinding(binding,loopId,IterationPhase.FIRST,new LinkedHashSet<>());
					Boolean middle=phaseBooleanBinding(binding,loopId,IterationPhase.MIDDLE,new LinkedHashSet<>());
					Boolean last=phaseBooleanBinding(binding,loopId,IterationPhase.LAST,new LinkedHashSet<>());
					int index=-1;
					for(int i=0;i<guardPhases.size();i++) if(guardPhases.get(i).loopId()==loopId
						&& guardPhases.get(i).guardId()==guard.id()) { index=i; break; }
					GuardPhase prior=index<0 ? null : guardPhases.get(index);
					Boolean mergedFirst=prior!=null && prior.first()!=null ? prior.first() : first;
					Boolean mergedMiddle=prior!=null && prior.middle()!=null ? prior.middle() : middle;
					Boolean mergedLast=prior!=null && prior.last()!=null ? prior.last() : last;
					if(mergedFirst==null && mergedMiddle==null && mergedLast==null) continue;
					GuardPhase next=new GuardPhase(loopId,guard.id(),mergedFirst,mergedMiddle,mergedLast,
						prior==null ? "symbolic-scalar-binding" : prior.proof()+"+symbolic-scalar-binding");
					if(prior==null) { guardPhases.add(next); changed=true; }
					else if(!Objects.equals(prior.first(),mergedFirst) || !Objects.equals(prior.middle(),mergedMiddle)
						|| !Objects.equals(prior.last(),mergedLast)) {
						guardPhases.set(index,next); changed=true;
					}
				}
			}
			while(changed && --remaining>0);
		}

		private Boolean phaseBooleanBinding(int bindingId,int loopId,IterationPhase phase,Set<Integer> seen) {
			Double value=phaseNumericBinding(bindingId,loopId,phase,seen);
			return value==null ? null : value!=0D;
		}

		private Double phaseNumericBinding(int bindingId,int loopId,IterationPhase phase,Set<Integer> seen) {
			if(bindingId<0 || !seen.add(bindingId)) return null;
			BindingNode node=bindingDrafts.get(bindingId);
			if(node==null) return null;
			try {
				if(node.kind()==BindingKind.DEFINITION && node.label().endsWith("#for-iterator")) return null;
				switch(node.kind()) {
					case SELECT: {
						Boolean arm=phaseGuardValue(loopId,node.referenceId(),phase);
						if(arm!=null) return phaseNumericBinding(node.children().get(arm ? 0 : 1),loopId,phase,seen);
						Double left=phaseNumericBinding(node.children().get(0),loopId,phase,new LinkedHashSet<>(seen));
						Double right=phaseNumericBinding(node.children().get(1),loopId,phase,new LinkedHashSet<>(seen));
						return left!=null && left.equals(right) ? left : null;
					}
					case LOOP_HEAD:
						if(node.referenceId()!=loopId) return null;
						return phaseNumericBinding(node.children().get(phase==IterationPhase.FIRST ? 0 : 1),
							loopId,phase,seen);
					case DEFINITION, CALL_ARG:
						if(!node.children().isEmpty())
							return phaseNumericBinding(node.children().get(0),loopId,phase,seen);
						break;
					default:
						return null;
				}
				if(node.referenceId()<0 || node.referenceId()>=occurrences.size()) return null;
				return phaseNumericOccurrence(node.referenceId(),loopId,phase,seen);
			}
			finally { seen.remove(bindingId); }
		}

		private Double phaseNumericOccurrence(int occurrence,int loopId,IterationPhase phase,Set<Integer> seen) {
			Hop hop=occurrences.get(occurrence).hop();
			if(hop instanceof LiteralOp literal) return switch(literal.getValueType()) {
				case BOOLEAN -> literal.getBooleanValue() ? 1D : 0D;
				case INT32 -> (double)literal.getLongValue();
				case INT64 -> exactInteger(literal.getLongValue());
				case FP32,FP64 -> literal.getDoubleValue();
				default -> null;
			};
			if(hop instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD) {
				Integer binding=occurrenceValues.get(occurrence);
				return binding==null ? null : phaseNumericBinding(binding,loopId,phase,seen);
			}
			if(hop instanceof DataOp data && hop.getInput().size()==1
				&& (data.getOp()==OpOpData.TRANSIENTWRITE || data.getOp()==OpOpData.FUNCTIONOUTPUT)) {
				Integer input=inputBindingValues.get(edgeKey(occurrence,0));
				return input==null ? null : phaseNumericBinding(input,loopId,phase,seen);
			}
			if(hop.getInput().size()==1 && hop instanceof UnaryOp unary) {
				Integer input=inputBindingValues.get(edgeKey(occurrence,0));
				Double value=input==null ? null : phaseNumericBinding(input,loopId,phase,seen);
				if(value==null) return null;
				return switch(unary.getOp()) {
					case NOT -> value==0D ? 1D : 0D;
					case CAST_AS_BOOLEAN,CAST_AS_DOUBLE,CAST_AS_INT -> value;
					default -> null;
				};
			}
			if(!(hop instanceof BinaryOp binary) || hop.getInput().size()!=2) return null;
			Integer leftBinding=inputBindingValues.get(edgeKey(occurrence,0));
			Integer rightBinding=inputBindingValues.get(edgeKey(occurrence,1));
			Double left=leftBinding==null ? null : phaseNumericBinding(leftBinding,loopId,phase,new LinkedHashSet<>(seen));
			Double right=rightBinding==null ? null : phaseNumericBinding(rightBinding,loopId,phase,new LinkedHashSet<>(seen));
			if(left==null || right==null) return null;
			return switch(binary.getOp()) {
				case PLUS -> left+right; case MINUS -> left-right; case MULT -> left*right;
				case DIV -> right==0D ? null : left/right;
				case EQUAL -> left.doubleValue()==right.doubleValue() ? 1D : 0D;
				case NOTEQUAL -> left.doubleValue()!=right.doubleValue() ? 1D : 0D;
				case GREATER -> left>right ? 1D : 0D; case GREATEREQUAL -> left>=right ? 1D : 0D;
				case LESS -> left<right ? 1D : 0D; case LESSEQUAL -> left<=right ? 1D : 0D;
				case AND -> left!=0D && right!=0D ? 1D : 0D; case OR -> left!=0D || right!=0D ? 1D : 0D;
				default -> null;
			};
		}

		private Boolean phaseGuardValue(int loopId,int guardId,IterationPhase phase) {
			for(GuardPhase fact : guardPhases)
				if(fact.loopId()==loopId && fact.guardId()==guardId) return fact.value(phase);
			return null;
		}

		private static Boolean[] affinePredicateValues(Hop hop,String variable,double entry,double delta) {
			Hop original=hop;
			if(hop instanceof UnaryOp unary && unary.getOp()==OpOp1.NOT && unary.getInput().size()==1)
				hop=unary.getInput(0);
			hop=unwrapScalar(hop);
			if(!(hop instanceof BinaryOp binary) || binary.getInput().size()!=2) return null;
			Hop left=binary.getInput(0),right=binary.getInput(1);
			boolean direct=isTransientRead(left) && variable.equals(left.getName()) && right instanceof LiteralOp;
			boolean reverse=isTransientRead(right) && variable.equals(right.getName()) && left instanceof LiteralOp;
			if(!direct && !reverse) return null;
			double threshold=((LiteralOp) (direct ? right : left)).getDoubleValue();
			if(Double.compare(threshold,entry)!=0) return null;
			Boolean first=predicateValue(original,variable,entry);
			Boolean later=predicateValue(original,variable,entry+delta);
			return first==null || later==null ? null : new Boolean[] {first,later,later};
		}

		private boolean isWithinRegion(int candidate,int ancestor) {
			for(int current=candidate; current>=0; current=regions.get(current).parentId())
				if(current == ancestor) return true;
			return false;
		}

		private Double numericBinding(int bindingId,Set<Integer> seen) {
			return numericBinding(bindingId,Map.of(),seen);
		}

		private Double numericBinding(int bindingId,Map<Integer,Double> formalValues,Set<Integer> seen) {
			if(bindingId < 0 || !seen.add(bindingId)) return null;
			BindingNode node = bindingDrafts.get(bindingId);
			if(node == null) return null;
			try {
				if(node.kind()==BindingKind.FORMAL) return formalValues.get(bindingId);
				if(!node.children().isEmpty()) {
					Double value=null;
					for(int child : node.children()) {
						Double current=numericBinding(child,formalValues,new LinkedHashSet<>(seen));
						if(current==null || value!=null && !value.equals(current)) return null;
						value=current;
					}
					return value;
				}
				return numericOccurrence(node.referenceId(),formalValues,seen);
			}
			finally { seen.remove(bindingId); }
		}

		private Double numericOccurrence(int occurrence,Map<Integer,Double> formalValues,Set<Integer> seen) {
			if(occurrence<0 || occurrence>=occurrences.size()) return null;
			Hop hop=occurrences.get(occurrence).hop();
			if(hop instanceof LiteralOp literal) return switch(literal.getValueType()) {
				case BOOLEAN -> literal.getBooleanValue() ? 1D : 0D;
				case INT32 -> (double)literal.getLongValue();
				case INT64 -> exactInteger(literal.getLongValue());
				case FP32,FP64 -> literal.getDoubleValue();
				default -> null;
			};
			if(hop instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD) {
				Integer binding=occurrenceValues.get(occurrence);
				return binding==null ? null : numericBinding(binding,formalValues,seen);
			}
			if(hop instanceof DataOp data && hop.getInput().size()==1
				&& (data.getOp()==OpOpData.TRANSIENTWRITE || data.getOp()==OpOpData.FUNCTIONOUTPUT)) {
				Integer input=inputBindingValues.get(edgeKey(occurrence,0));
				return input==null ? null : numericBinding(input,formalValues,seen);
			}
			if(hop.getInput().size()==1 && hop instanceof UnaryOp unary) {
				Integer input=inputBindingValues.get(edgeKey(occurrence,0));
				Double value=input==null ? null : numericBinding(input,formalValues,seen);
				if(value==null) return null;
				return switch(unary.getOp()) {
					case NOT -> value==0D ? 1D : 0D;
					case CAST_AS_BOOLEAN -> value!=0D ? 1D : 0D;
					case CAST_AS_DOUBLE -> value;
					case CAST_AS_INT -> exactInteger(UtilFunctions.toLong(value));
					default -> null;
				};
			}
			if(!(hop instanceof BinaryOp binary) || hop.getInput().size()!=2) return null;
			Integer leftBinding=inputBindingValues.get(edgeKey(occurrence,0));
			Integer rightBinding=inputBindingValues.get(edgeKey(occurrence,1));
			Double left=leftBinding==null ? null
				: numericBinding(leftBinding,formalValues,new LinkedHashSet<>(seen));
			Double right=rightBinding==null ? null
				: numericBinding(rightBinding,formalValues,new LinkedHashSet<>(seen));
			if(left==null || right==null) return null;
			return switch(binary.getOp()) {
				case PLUS -> binary.getValueType()==ValueType.INT64 ? exactIntegerArithmetic(left,right,binary.getOp())
					: left+right;
				case MINUS -> binary.getValueType()==ValueType.INT64 ? exactIntegerArithmetic(left,right,binary.getOp())
					: left-right;
				case MULT -> binary.getValueType()==ValueType.INT64 ? exactIntegerArithmetic(left,right,binary.getOp())
					: left*right;
				case DIV -> right==0D ? null : left/right;
				default -> null;
			};
		}

		private static Double exactIntegerArithmetic(double left,double right,OpOp2 operator) {
			Long leftInteger=exactLong(left),rightInteger=exactLong(right);
			if(leftInteger==null || rightInteger==null) return null;
			try {
				long result=switch(operator) {
					case PLUS -> Math.addExact(leftInteger,rightInteger);
					case MINUS -> Math.subtractExact(leftInteger,rightInteger);
					case MULT -> Math.multiplyExact(leftInteger,rightInteger);
					default -> throw new IllegalArgumentException("Not an integer arithmetic operator: "+operator);
				};
				return exactInteger(result);
			}
			catch(ArithmeticException overflow) { return null; }
		}

		private static Double exactInteger(long value) {
			return value < -MAX_EXACT_DOUBLE_INTEGER || value > MAX_EXACT_DOUBLE_INTEGER ? null : (double)value;
		}

		private static Long exactLong(double value) {
			if(!Double.isFinite(value) || value!=Math.rint(value)
				|| value < -MAX_EXACT_DOUBLE_INTEGER || value > MAX_EXACT_DOUBLE_INTEGER) return null;
			return (long)value;
		}

		private Double affineDelta(int bindingId,String variable,Set<Integer> seen) {
			if(bindingId < 0 || !seen.add(bindingId)) return null;
			BindingNode node = bindingDrafts.get(bindingId);
			if(node == null) return null;
			if(!node.children().isEmpty()) {
				Double first = affineDelta(node.children().get(0),variable,seen);
				if(node.kind() != BindingKind.SELECT || node.children().size() == 1) return first;
				Double second = affineDelta(node.children().get(1),variable,seen);
				return first != null && first.equals(second) ? first : null;
			}
			if(node.referenceId() < 0 || node.referenceId() >= occurrences.size()) return null;
			Hop hop = unwrapScalar(occurrences.get(node.referenceId()).hop());
			if(!(hop instanceof BinaryOp binary) || binary.getInput().size() != 2) return null;
			Hop left=binary.getInput(0), right=binary.getInput(1);
			if(isTransientRead(left) && variable.equals(left.getName()) && right instanceof LiteralOp) {
				double literal=((LiteralOp) right).getDoubleValue();
				if(binary.getOp()==OpOp2.PLUS) return literal;
				if(binary.getOp()==OpOp2.MINUS) return -literal;
				return null;
			}
			if(isTransientRead(right) && variable.equals(right.getName()) && left instanceof LiteralOp
				&& binary.getOp()==OpOp2.PLUS) return ((LiteralOp) left).getDoubleValue();
			return null;
		}

		private static Boolean predicateValue(Hop hop,String variable,double value) {
			if(hop instanceof UnaryOp unary && unary.getOp()==OpOp1.NOT && unary.getInput().size()==1) {
				Boolean nested=predicateValue(unary.getInput(0),variable,value);
				return nested==null ? null : !nested;
			}
			hop=unwrapScalar(hop);
			if(hop instanceof LiteralOp literal) return literal.getBooleanValue();
			if(!(hop instanceof BinaryOp binary) || binary.getInput().size()!=2) return null;
			Double left=numericExpression(binary.getInput(0),variable,value);
			Double right=numericExpression(binary.getInput(1),variable,value);
			if(left==null || right==null) return null;
			return switch(binary.getOp()) {
				case EQUAL -> left.doubleValue()==right.doubleValue();
				case NOTEQUAL -> left.doubleValue()!=right.doubleValue();
				case GREATER -> left>right;
				case GREATEREQUAL -> left>=right;
				case LESS -> left<right;
				case LESSEQUAL -> left<=right;
				default -> null;
			};
		}

		private static Double numericExpression(Hop hop,String variable,double value) {
			hop=unwrapScalar(hop);
			if(hop instanceof LiteralOp) return ((LiteralOp) hop).getDoubleValue();
			if(isTransientRead(hop) && variable.equals(hop.getName())) return value;
			if(!(hop instanceof BinaryOp binary) || binary.getInput().size()!=2) return null;
			Double left=numericExpression(binary.getInput(0),variable,value);
			Double right=numericExpression(binary.getInput(1),variable,value);
			if(left==null || right==null) return null;
			return switch(binary.getOp()) {
				case PLUS -> left+right; case MINUS -> left-right; case MULT -> left*right; case DIV -> right==0 ? null : left/right;
				default -> null;
			};
		}

		private static Hop unwrapScalar(Hop hop) {
			while(hop != null && hop.getInput().size()==1
				&& (hop instanceof UnaryOp unary && (unary.getOp()==OpOp1.CAST_AS_BOOLEAN
					|| unary.getOp()==OpOp1.CAST_AS_DOUBLE || unary.getOp()==OpOp1.CAST_AS_INT)
					|| hop instanceof DataOp data
					&& (data.getOp()==OpOpData.TRANSIENTWRITE || data.getOp()==OpOpData.FUNCTIONOUTPUT)))
				hop=hop.getInput(0);
			return hop;
		}

		private int visitRoot(Hop hop, StatementBlock block, int region, String context,
			Map<String,Integer> env, boolean control,FlowState flow) {
			return visitHop(hop, block, region, context, env, control,
				Collections.newSetFromMap(new IdentityHashMap<>()), nextTraversalId++,flow);
		}

		private int visitHop(Hop hop, StatementBlock block, int region, String context,
			Map<String,Integer> env, boolean control, Set<Hop> visited, int traversalId,FlowState flow) {
			if(hop == null) return -1;
			OccurrenceKey occurrenceKey = new OccurrenceKey(hop, traversalId);
			Integer existing = occurrenceIds.get(occurrenceKey);
			if(existing != null && visited.contains(hop)) return existing;
			visited.add(hop);
			for(Hop input : hop.getInput())
				visitHop(input, block, region, context, env, control, visited, traversalId,flow);
			int occurrence = occurrenceIds.computeIfAbsent(occurrenceKey, key -> {
				int owner = ownerIds.computeIfAbsent(hop, ignored -> ownerIds.size());
				int id = occurrences.size();
				occurrences.add(new Occurrence(id, owner, hop, block.getSBID(), region, context,flow.activation));
				return id;
			});
			for(int pos = 0; pos < hop.getInput().size(); pos++) {
				Hop input = hop.getInput(pos);
				Integer resolvedInput = occurrenceIds.get(new OccurrenceKey(input, traversalId));
				int inputOccurrence = resolvedInput == null ? -1 : resolvedInput;
				int binding = valueBinding(inputOccurrence, input, env, region);
				long key = edgeKey(occurrence, pos);
				if(registeredInputEdges.add(key)) {
					inputBindings.add(new InputBinding(occurrence, pos, binding));
					inputBindingValues.put(key, binding);
					UseKind kind = classifyUse(hop, input, control);
					semanticUses.add(new SemanticUse(semanticUses.size(), occurrence, pos, binding, kind, region));
				}
			}
			if(isTransientRead(hop)) {
				Integer binding = env.get(hop.getName());
				if(binding == null) binding = missing("MISSING_REACHING_DEFINITION", hop.getName(), region);
				occurrenceValues.put(occurrence, binding);
			}
			else occurrenceValues.putIfAbsent(occurrence,
				addBinding(BindingKind.DEFINITION, List.of(), occurrence, -1, hop.getName()));
			if(hop instanceof FunctionOp && ((FunctionOp) hop).getFunctionType() == FunctionType.DML)
				bindFunctionCall((FunctionOp) hop, occurrence, env, region, traversalId,flow);
			return occurrence;
		}

		private void bindFunctionCall(FunctionOp call, int occurrence, Map<String,Integer> env, int region,
			int traversalId,FlowState flow) {
			String key = call.getFunctionKey();
			Integer summaryId = functionSummaryIds.get(key);
			if(summaryId == null) {
				diagnostic(Status.UNSUPPORTED, "UNKNOWN_FUNCTION", key, region);
				return;
			}
			FunctionSummary summary = functionSummaries.get(summaryId);
			// A recursive call is discovered while its declared summary slot is
			// deliberately still empty. The function analysis has already emitted
			// RECURSIVE_FUNCTION/UNSUPPORTED; do not manufacture bindings from an
			// unavailable summary or replace that typed diagnostic with an NPE.
			if(summary == null)
				return;
			List<Integer> actuals = new ArrayList<>(), arguments = new ArrayList<>(), returns = new ArrayList<>();
			for(int pos = 0; pos < call.getInput().size(); pos++) {
				Hop input = call.getInput(pos);
				Integer resolvedInput = occurrenceIds.get(new OccurrenceKey(input, traversalId));
				int inputOccurrence = resolvedInput == null ? -1 : resolvedInput;
				int actual = valueBinding(inputOccurrence, input, env, region);
				actuals.add(actual);
				arguments.add(addBinding(BindingKind.CALL_ARG, List.of(actual), calls.size(), pos, key));
			}
			String[] outputs = call.getOutputVariableNames();
			int boundOutputs = outputs == null ? 0 : Math.min(outputs.length,summary.returnBindingIds().size());
			for(int pos = 0; pos < boundOutputs; pos++) {
				int ret = addBinding(BindingKind.CALL_RETURN, List.of(summary.returnBindingIds().get(pos)),
					calls.size(), pos, key);
				returns.add(ret);
				if(outputs != null && pos < outputs.length) env.put(outputs[pos], ret);
			}
			int callId = calls.size();
			int completion = addBinding(BindingKind.CALL_COMPLETION,
				flow.activation < 0 ? List.of() : List.of(flow.activation),callId,-1,key + "#completion");
			calls.add(new CallBinding(callId, occurrence, occurrences.get(occurrence).statementBlockId(),
				-1,region,completion,summaryId, actuals, arguments, returns));
			callRegions.put(callId, region);
			flow.activation = completion;
		}

		private void bindInlinedFunctionCalls(StatementBlock block, int region, Map<String,Integer> env) {
			for(StatementBlock.InlinedFunctionCallBoundary boundary : block.getInlinedFunctionCallBoundaries()) {
				String key = normalizeFunctionKey(boundary.functionKey());
				Integer summaryId = functionSummaryIds.get(key);
				if(summaryId == null)
					summaryId = addInlinedSummary(key,boundary,region);
				if(summaryId < 0 || functionSummaries.get(summaryId) == null) continue;
				FunctionSummary summary = functionSummaries.get(summaryId);
				int callId = calls.size();
				List<Integer> actuals = new ArrayList<>(), arguments = new ArrayList<>(), returns = new ArrayList<>();
				for(StatementBlock.InlinedFunctionInputBoundary input : boundary.inputs()) {
					Integer actual = env.get(input.boundVariable());
					if(actual == null && input.actualVariable() != null) actual = env.get(input.actualVariable());
					if(actual == null && summary.compiledOwnerIds().isEmpty())
						actual = addBinding(BindingKind.DEFINITION,List.of(),-1,input.position(),
							key + ':' + input.formalVariable() + "#optimized-away-inlined-actual");
					else if(actual == null)
						actual = missing("MISSING_INLINED_ACTUAL", key + ':' + input.position() + ':'
							+ input.boundVariable() + ':' + input.actualVariable(), region);
					actuals.add(actual);
					arguments.add(addBinding(BindingKind.CALL_ARG, List.of(actual), callId,
						input.position(), key + ':' + input.formalVariable()));
				}
				for(StatementBlock.InlinedFunctionOutputBoundary output : boundary.outputs()) {
					Integer concrete = env.get(output.boundVariable());
					if(concrete == null) concrete = env.get(output.targetVariable());
					if(concrete == null)
						concrete = missing("MISSING_INLINED_RETURN", key + ':' + output.position(), region);
					int symbolic = output.position() < summary.returnBindingIds().size()
						? summary.returnBindingIds().get(output.position())
						: missing("MISSING_FUNCTION_RETURN_SLOT", key + ':' + output.position(), region);
					returns.add(addBinding(BindingKind.CALL_RETURN, List.of(symbolic, concrete), callId,
						output.position(), key + ':' + output.formalVariable()));
				}
				calls.add(new CallBinding(callId, -1, block.getSBID(), boundary.callStatementPosition(),
					region,-1,summaryId, actuals, arguments, returns));
				callRegions.put(callId, region);
			}
		}

		/** A fully inlined helper may no longer have a compiled FunctionStatementBlock. */
		private int addInlinedSummary(String key,StatementBlock.InlinedFunctionCallBoundary boundary,int region) {
			int inputCount = boundary.inputs().stream().mapToInt(input -> input.position() + 1).max().orElse(0);
			int outputCount = boundary.outputs().stream().mapToInt(output -> output.position() + 1).max().orElse(0);
			if(inputCount != boundary.inputs().size() || outputCount != boundary.outputs().size()) {
				diagnostic(Status.UNSUPPORTED,"NONCONTIGUOUS_INLINED_SIGNATURE",key,region);
				return -1;
			}
			int id = functionSummaries.size();
			List<String> formalNames = new ArrayList<>(Collections.nCopies(inputCount,null));
			List<Integer> formalBindings = new ArrayList<>(Collections.nCopies(inputCount,-1));
			for(StatementBlock.InlinedFunctionInputBoundary input : boundary.inputs()) {
				formalNames.set(input.position(),input.formalVariable());
				formalBindings.set(input.position(),addBinding(BindingKind.FORMAL,List.of(),id,input.position(),
					key + ':' + input.formalVariable()));
			}
			List<String> returnNames = new ArrayList<>(Collections.nCopies(outputCount,null));
			List<Integer> returnBindings = new ArrayList<>(Collections.nCopies(outputCount,-1));
			for(StatementBlock.InlinedFunctionOutputBoundary output : boundary.outputs()) {
				returnNames.set(output.position(),output.formalVariable());
				// Inlined CALL_RETURN always resolves its concrete second child. This explicit
				// unresolved boundary prevents the logical summary from inventing an owner.
				returnBindings.set(output.position(),addBinding(BindingKind.DEFINITION,List.of(),-1,
					output.position(),key + ':' + output.formalVariable() + "#inlined-return-boundary"));
			}
			int summaryRegion = addRegion(0,RegionKind.FUNCTION,"inlined-summary:" + key,-1,-1);
			functionSummaryIds.put(key,id);
			functionSummaries.add(new FunctionSummary(id,key,summaryRegion,-1,formalNames,formalBindings,
				returnNames,returnBindings,List.of(),-1));
			return id;
		}

		private int valueBinding(int occurrence, Hop hop, Map<String,Integer> env, int region) {
			if(isTransientRead(hop)) {
				Integer binding = env.get(hop.getName());
				return binding != null ? binding : missing("MISSING_REACHING_DEFINITION", hop.getName(), region);
			}
			if(occurrence < 0)
				return missing("MISSING_OCCURRENCE", hop.getName(), region);
			Integer binding = occurrenceValues.get(occurrence);
			return binding != null ? binding
				: addBinding(BindingKind.DEFINITION, List.of(), occurrence, -1, hop.getName());
		}

		private void applyRootDefinition(Hop root, int occurrence, int region, Map<String,Integer> env) {
			if(root instanceof FunctionOp function
				&& function.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
				String[] names = function.getOutputVariableNames();
				List<Hop> outputs = function.getOutputs();
				int slots = Math.max(names == null ? 0 : names.length, outputs == null ? 0 : outputs.size());
				for(int slot = 0; slot < slots; slot++) {
					String name = names != null && slot < names.length ? names[slot] : null;
					if((name == null || name.isBlank()) && outputs != null && slot < outputs.size()
						&& outputs.get(slot) != null)
						name = outputs.get(slot).getName();
					if(name == null || name.isBlank()) {
						diagnostic(Status.INCOMPLETE, "MISSING_BUILTIN_OUTPUT_NAME",
							function.getFunctionKey() + " output " + slot, region);
						continue;
					}
					String versionKey = region + "\u0000" + name;
					int version = versions.merge(versionKey, 1, Integer::sum);
					int binding = addBinding(BindingKind.DEFINITION, List.of(), occurrence, slot,
						name + '#' + version + "#builtin-output");
					int definition = definitions.size();
					definitions.add(new Definition(definition, name, version, occurrence, binding, region));
					env.put(name, binding);
				}
				return;
			}
			if(root instanceof DataOp) {
				DataOp data = (DataOp) root;
				if(data.getOp() == OpOpData.TRANSIENTWRITE) {
					Integer input = data.getInput().isEmpty() ? null
						: inputBindingValues.get(edgeKey(occurrence, 0));
					int source = input != null ? input : missing(data.getInput().isEmpty()
						? "WRITE_WITHOUT_VALUE" : "MISSING_WRITE_INPUT", data.getName(), region);
					String versionKey = region + "\u0000" + data.getName();
					int version = versions.merge(versionKey, 1, Integer::sum);
					int definition = definitions.size();
					definitions.add(new Definition(definition, data.getName(), version, occurrence, source, region));
					env.put(data.getName(), addBinding(BindingKind.DEFINITION, List.of(source), definition, -1,
						data.getName() + '#' + version));
				}
			}
		}

		private Set<String> collectUpdatedVariables(List<StatementBlock> blocks) {
			Set<String> result = new LinkedHashSet<>();
			for(StatementBlock block : blocks) {
				if(block instanceof IfStatementBlock) {
					IfStatement statement = (IfStatement) block.getStatement(0);
					result.addAll(collectUpdatedVariables(statement.getIfBody()));
					result.addAll(collectUpdatedVariables(statement.getElseBody()));
				}
				else if(block instanceof WhileStatementBlock)
					result.addAll(collectUpdatedVariables(((WhileStatement) block.getStatement(0)).getBody()));
				else if(block instanceof ForStatementBlock)
					result.addAll(collectUpdatedVariables(((ForStatement) block.getStatement(0)).getBody()));
				else if(block.getHops() != null)
					for(Hop root : block.getHops()) {
						if(root instanceof DataOp && ((DataOp) root).getOp() == OpOpData.TRANSIENTWRITE)
							result.add(root.getName());
						else if(root instanceof FunctionOp && ((FunctionOp) root).getOutputVariableNames() != null)
							Collections.addAll(result,((FunctionOp) root).getOutputVariableNames());
					}
			}
			return result;
		}

		/**
		 * Structured, single-iteration definite-assignment facts for deciding which loop writes
		 * actually need an entry/backedge head. The explicit T>1 planner contract lets a value
		 * written on every body path become a LAST value without inventing an entry source.
		 */
		private LoopFlow analyzeLoopFlow(StatementBlock loopBlock,List<StatementBlock> body) {
			Set<String> readsBefore = new LinkedHashSet<>();
			Set<String> defined = new LinkedHashSet<>();
			Set<String> updated = new LinkedHashSet<>();
			if(loopBlock instanceof WhileStatementBlock)
				recordReads(((WhileStatementBlock) loopBlock).getPredicateHops(),defined,readsBefore);
			else if(loopBlock instanceof ForStatementBlock) {
				ForStatementBlock forBlock = (ForStatementBlock) loopBlock;
				recordReads(forBlock.getFromHops(),defined,readsBefore);
				recordReads(forBlock.getToHops(),defined,readsBefore);
				recordReads(forBlock.getIncrementHops(),defined,readsBefore);
				defined.add(forBlock.getIterPredicate().getIterVar().getName());
			}
			analyzeFlowSequence(body,defined,readsBefore,updated);
			return new LoopFlow(updated,readsBefore,defined);
		}

		private void analyzeFlowSequence(List<StatementBlock> blocks,Set<String> defined,
			Set<String> readsBefore,Set<String> updated) {
			for(StatementBlock block : blocks) {
				if(block instanceof IfStatementBlock) {
					IfStatementBlock ifBlock = (IfStatementBlock) block;
					recordReads(ifBlock.getPredicateHops(),defined,readsBefore);
					IfStatement statement = (IfStatement) block.getStatement(0);
					Set<String> thenDefined = new LinkedHashSet<>(defined);
					Set<String> elseDefined = new LinkedHashSet<>(defined);
					analyzeFlowSequence(statement.getIfBody(),thenDefined,readsBefore,updated);
					analyzeFlowSequence(statement.getElseBody(),elseDefined,readsBefore,updated);
					thenDefined.retainAll(elseDefined);
					defined.clear(); defined.addAll(thenDefined);
				}
				else if(block instanceof WhileStatementBlock || block instanceof ForStatementBlock) {
					List<StatementBlock> nestedBody = block instanceof WhileStatementBlock
						? ((WhileStatement) block.getStatement(0)).getBody()
						: ((ForStatement) block.getStatement(0)).getBody();
					LoopFlow nested = analyzeLoopFlow(block,nestedBody);
					for(String name : nested.readBeforeDefinition())
						if(!defined.contains(name)) readsBefore.add(name);
					for(String name : nested.updated()) {
						updated.add(name);
						if(nested.definitelyDefined().contains(name)) defined.add(name);
						else if(!defined.contains(name)) readsBefore.add(name);
					}
				}
				else if(block.getHops() != null)
					for(Hop root : block.getHops()) {
						recordReads(root,defined,readsBefore);
						if(root instanceof DataOp && ((DataOp) root).getOp() == OpOpData.TRANSIENTWRITE) {
							updated.add(root.getName());
							defined.add(root.getName());
						}
						else if(root instanceof FunctionOp && ((FunctionOp) root).getOutputVariableNames() != null)
							for(String output : ((FunctionOp) root).getOutputVariableNames()) {
								updated.add(output);
								defined.add(output);
							}
					}
			}
		}

		private void recordReads(Hop root,Set<String> defined,Set<String> readsBefore) {
			Set<String> reads = new LinkedHashSet<>();
			collectTransientReads(root,reads,Collections.newSetFromMap(new IdentityHashMap<>()));
			for(String name : reads) if(!defined.contains(name)) readsBefore.add(name);
		}

		private void collectTransientReads(Hop hop,Set<String> reads,Set<Hop> visited) {
			if(hop == null || !visited.add(hop)) return;
			if(isTransientRead(hop)) reads.add(hop.getName());
			for(Hop input : hop.getInput()) collectTransientReads(input,reads,visited);
		}

		private record LoopFlow(Set<String> updated,Set<String> readBeforeDefinition,
			Set<String> definitelyDefined) {
			private LoopFlow {
				updated = Set.copyOf(updated);
				readBeforeDefinition = Set.copyOf(readBeforeDefinition);
				definitelyDefined = Set.copyOf(definitelyDefined);
			}
		}
		private record ParForProof(int loopId,int regionId,int fromOccurrence,int toOccurrence,
			int incrementOccurrence) { }

		private static final class FlowState {
			private int activation;
			private FlowState(int activation) { this.activation = activation; }
		}

		private Set<String> collectFunctionCalls(List<StatementBlock> blocks) {
			Set<String> result = new LinkedHashSet<>();
			for(StatementBlock block : blocks) {
				for(StatementBlock.InlinedFunctionCallBoundary call : block.getInlinedFunctionCallBoundaries())
					result.add(normalizeFunctionKey(call.functionKey()));
				if(block instanceof IfStatementBlock) {
					IfStatement statement = (IfStatement) block.getStatement(0);
					result.addAll(collectFunctionCalls(statement.getIfBody()));
					result.addAll(collectFunctionCalls(statement.getElseBody()));
				}
				else if(block instanceof WhileStatementBlock)
					result.addAll(collectFunctionCalls(((WhileStatement) block.getStatement(0)).getBody()));
				else if(block instanceof ForStatementBlock)
					result.addAll(collectFunctionCalls(((ForStatement) block.getStatement(0)).getBody()));
				else if(block.getHops() != null)
					for(Hop root : block.getHops()) collectFunctionCalls(root, result,
						Collections.newSetFromMap(new IdentityHashMap<>()));
			}
			return result;
		}

		private void collectFunctionCalls(Hop hop, Set<String> result, Set<Hop> visited) {
			if(hop == null || !visited.add(hop)) return;
			if(hop instanceof FunctionOp && ((FunctionOp) hop).getFunctionType() == FunctionType.DML)
				result.add(((FunctionOp) hop).getFunctionKey());
			for(Hop input : hop.getInput()) collectFunctionCalls(input, result, visited);
		}

		private int addRegion(int parent, RegionKind kind, String label, int guard, int loop) {
			int id = regions.size();
			regions.add(new Region(id, parent, kind, label, guard, loop));
			return id;
		}

		private int reserveBinding() {
			int id = bindingDrafts.size(); bindingDrafts.add(null); return id;
		}

		private int addBinding(BindingKind kind, List<Integer> children, int reference, int position,
			String label) {
			int id = reserveBinding();
			setBinding(id, kind, children, reference, position, label == null ? "" : label);
			return id;
		}

		private void setBinding(int id, BindingKind kind, List<Integer> children, int reference,
			int position, String label) {
			bindingDrafts.set(id, new BindingNode(id, kind, children, reference, position, label));
		}

		private int missing(String code, String label, int region) {
			diagnostic(Status.INCOMPLETE, code, label, region);
			return addBinding(BindingKind.MISSING, List.of(), -1, -1, label);
		}

		private void diagnostic(Status status, String code, String message, int region) {
			Diagnostic diagnostic = new Diagnostic(status, code, message, region);
			if(!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
		}

		private void validateActiveLoopIterations() {
			if(parForProofs.isEmpty()) return;
			Map<Integer,List<Map<Integer,Double>>> invocations = reachableFunctionInvocations();
			for(ParForProof proof : parForProofs.values()) {
				int function=enclosingFunction(proof.regionId());
				List<Map<Integer,Double>> environments=function<0 ? List.of(Map.of())
					: invocations.getOrDefault(function,List.of());
				if(environments.isEmpty()) continue; // An unused function cannot execute its PARFOR.
				Double staticIterations=staticLoopIterations.get(proof.loopId());
				Double proven=staticIterations!=null && staticIterations>=0 ? staticIterations : null;
				if(proven==null) for(Map<Integer,Double> environment : environments) {
					Double from=numericOccurrence(proof.fromOccurrence(),environment,new LinkedHashSet<>());
					Double to=numericOccurrence(proof.toOccurrence(),environment,new LinkedHashSet<>());
					Double increment=proof.incrementOccurrence()<0
						? from==null || to==null ? null
							: UtilFunctions.toLong(from)<=UtilFunctions.toLong(to) ? 1D : -1D
						: numericOccurrence(proof.incrementOccurrence(),environment,new LinkedHashSet<>());
					Double iterations=exactIterations(from,to,increment);
					if(iterations==null || iterations>1 || proven!=null && !proven.equals(iterations)) {
						diagnostic(Status.UNSUPPORTED,"UNSUPPORTED_PARFOR",
							"Memo planning requires the same exact zero/one PARFOR cardinality in every reachable call frame",
							proof.regionId());
						proven=null;
						break;
					}
					proven=iterations;
				}
				if(proven!=null && proven>1) {
					diagnostic(Status.UNSUPPORTED,"UNSUPPORTED_PARFOR",
						"Memo planning supports PARFOR only when exactly zero or one iteration is proven",
						proof.regionId());
					proven=null;
				}
				if(proven!=null) {
					staticLoopIterations.put(proof.loopId(),proven);
					LoopRecord loop=loops.get(proof.loopId());
					loops.set(proof.loopId(),new LoopRecord(loop.id(),loop.statementBlockId(),loop.regionId(),
						loop.guardId(),loop.activationBindingId(),loop.backedgeActivationBindingId(),
						loop.carriedValues(),loopExecutionCases(proven)));
				}
			}
		}

		private Map<Integer,List<Map<Integer,Double>>> reachableFunctionInvocations() {
			Map<Integer,List<Map<Integer,Double>>> result=new LinkedHashMap<>();
			for(CallBinding call : calls)
				if(enclosingFunction(callRegions.getOrDefault(call.id(),-1))<0)
					collectInvocation(call,Map.of(),result,new LinkedHashSet<>());
			return result;
		}

		private void collectInvocation(CallBinding call,Map<Integer,Double> callerEnvironment,
			Map<Integer,List<Map<Integer,Double>>> result,Set<Integer> activeFunctions) {
			int function=call.functionSummaryId();
			if(!activeFunctions.add(function)) return; // recursion is diagnosed independently
			FunctionSummary summary=functionSummaries.get(function);
			Map<Integer,Double> environment=new LinkedHashMap<>();
			for(int position=0;position<summary.formalBindingIds().size();position++) {
				Double value=position<call.actualBindingIds().size()
					? numericBinding(call.actualBindingIds().get(position),callerEnvironment,new LinkedHashSet<>()) : null;
				if(value!=null) environment.put(summary.formalBindingIds().get(position),value);
			}
			result.computeIfAbsent(function,ignored -> new ArrayList<>()).add(Map.copyOf(environment));
			for(CallBinding nested : calls)
				if(enclosingFunction(callRegions.getOrDefault(nested.id(),-1))==function)
					collectInvocation(nested,environment,result,new LinkedHashSet<>(activeFunctions));
		}

		private static Double exactIterations(Double from,Double to,Double increment) {
			if(from==null || to==null || increment==null || UtilFunctions.toLong(increment)==0) return null;
			long count=UtilFunctions.getSeqLength(from,to,increment,false);
			return count>=0 ? (double)count : null;
		}

		private int enclosingFunction(int regionId) {
			for(int region = regionId; region >= 0; region = regions.get(region).parentId())
				if(regions.get(region).kind() == RegionKind.FUNCTION)
					for(FunctionSummary function : functionSummaries)
						if(function != null && function.regionId() == region) return function.id();
			return -1;
		}

		private void recordControlRoot(int occurrence, Hop hop, int region) {
			if(occurrence < 0 || hop == null || !registeredControlRoots.add(occurrence)) return;
			Integer binding = occurrenceValues.get(occurrence);
			if(binding == null)
				binding = addBinding(BindingKind.DEFINITION, List.of(), occurrence, -1, hop.getName());
			semanticUses.add(new SemanticUse(semanticUses.size(), occurrence, -1, binding,
				UseKind.CONTROL, region));
		}

		private static double staticForIterations(ForStatementBlock block) {
			double[] range=staticForRange(block);
			if(range==null) return -1;
			Double iterations=exactIterations(range[0],range[1],range[2]);
			return iterations==null ? -1 : iterations;
		}

		private static double[] staticForRange(ForStatementBlock block) {
			double from, to, increment;
			if(block.getFromHops() instanceof LiteralOp && block.getToHops() instanceof LiteralOp
				&& (block.getIncrementHops() == null || block.getIncrementHops() instanceof LiteralOp)) {
				from = ((LiteralOp) block.getFromHops()).getDoubleValue();
				to = ((LiteralOp) block.getToHops()).getDoubleValue();
				increment = block.getIncrementHops() == null
					? (UtilFunctions.toLong(from)<=UtilFunctions.toLong(to) ? 1 : -1)
					: ((LiteralOp) block.getIncrementHops()).getDoubleValue();
			}
			else if(block.getIterPredicate().getFromExpr() instanceof ConstIdentifier
				&& block.getIterPredicate().getToExpr() instanceof ConstIdentifier
				&& block.getIterPredicate().getIncrementExpr() instanceof ConstIdentifier) {
				from = ((ConstIdentifier) block.getIterPredicate().getFromExpr()).getLongValue();
				to = ((ConstIdentifier) block.getIterPredicate().getToExpr()).getLongValue();
				increment = ((ConstIdentifier) block.getIterPredicate().getIncrementExpr()).getLongValue();
			}
			else return null;
			return new double[] {from,to,increment};
		}

		private static String normalizeFunctionKey(String key) {
			return DMLProgram.splitFunctionKey(key).length == 1
				? DMLProgram.constructFunctionKey(DMLProgram.DEFAULT_NAMESPACE, key) : key;
		}

		private static final class OccurrenceKey {
			private final Hop hop;
			private final int traversalId;
			private OccurrenceKey(Hop hop, int traversalId) {
				this.hop = hop;
				this.traversalId = traversalId;
			}
			@Override public boolean equals(Object object) {
				return object instanceof OccurrenceKey && ((OccurrenceKey) object).hop == hop
					&& ((OccurrenceKey) object).traversalId == traversalId;
			}
			@Override public int hashCode() {
				return 31 * System.identityHashCode(hop) + traversalId;
			}
		}

		private static boolean isTransientRead(Hop hop) {
			return hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD;
		}

		private static UseKind classifyUse(Hop consumer, Hop input, boolean control) {
			if(control) return UseKind.CONTROL;
			if(consumer instanceof DataOp) {
				OpOpData op = ((DataOp) consumer).getOp();
				if(op != OpOpData.TRANSIENTWRITE && op != OpOpData.FUNCTIONOUTPUT)
					return UseKind.METADATA;
			}
			return input.getDataType() == DataType.SCALAR ? UseKind.SCALAR : UseKind.PAYLOAD;
		}
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.Statement;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.wink.json4j.JSONObject;

/**
 * Occurrence-scoped shape authority derived before rule enumeration.
 *
 * <p>All inference runs over private HOP clones. Exact source ranges and symbolic
 * value bindings update only these views; the compiled program remains untouched.
 * Dependencies are revisited only when an owner or binding shape changes.</p>
 */
public final class MemoShapes {
	private static final int COST_VIEW_DESCENT_DEPTH = 6;
	public enum Knowledge { EXACT, UNKNOWN }
	public enum DiagnosticKind { MISMATCH, UNRESOLVED, REFRESH_FAILURE }

	public record Shape(DataType dataType, ValueType valueType, long rows, long columns,
		long nonZeros, Knowledge knowledge) {
		public Shape {
			Objects.requireNonNull(dataType);
			Objects.requireNonNull(valueType);
			Objects.requireNonNull(knowledge);
		}

		public boolean exact() { return knowledge == Knowledge.EXACT; }
	}

	public record Diagnostic(DiagnosticKind kind, String code, int owner, int binding,
		String message) {
		public Diagnostic {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(code);
			Objects.requireNonNull(message);
		}
	}

	/** Request-local work counters; do not couple the shape and privacy fixed points. */
	public record Statistics(long bindingVisits,long ownerVisits,long relevanceVisits,long relevanceEdges) { }
	private long bindingVisits, ownerVisits, relevanceVisits, relevanceEdges;
	public Statistics statistics() {
		return new Statistics(bindingVisits,ownerVisits,relevanceVisits,relevanceEdges);
	}

	private final MemoProgramFacts facts;
	private final MemoSources sources;
	private final MemoBindingResolver resolver;
	private final Hop[] views;
	private final Map<Hop,Integer> viewOwners = new IdentityHashMap<>();
	private final List<List<MemoProgramFacts.Occurrence>> occurrencesByOwner;
	private final Shape[] initialShapes;
	private final Shape[] ownerShapes;
	private final Shape[] bindingShapes;
	private final Hop[] occurrenceViews;
	private final List<Diagnostic> diagnostics = new ArrayList<>();
	private final Set<String> diagnosticKeys = new LinkedHashSet<>();
	private final Map<String,Optional<Shape>> localMetadataShapes = new HashMap<>();
	private final Map<PhaseNodeKey,Shape> phaseShapeCache = new HashMap<>();
	private final List<List<Integer>> ownerConsumers;
	private final List<List<Integer>> ownerBindingConsumers;
	private final List<List<Integer>> bindingConsumers;
	private final List<List<Integer>> bindingOwnerConsumers;
	private final List<List<Integer>> formalArguments;
	private final ArrayDeque<Integer> ownerWork = new ArrayDeque<>();
	private final ArrayDeque<Integer> bindingWork = new ArrayDeque<>();
	private final boolean[] ownerQueued;
	private final boolean[] bindingQueued;
	private final boolean[] bindingConflicts;
	private final boolean[] invariantCostStats;
	private final List<Set<Integer>> phaseSensitiveByBinding;
	private final List<Set<Integer>> dimensionPhaseSensitiveByBinding;
	private final List<Set<Integer>> guardSensitiveByBinding;

	private MemoShapes(MemoProgramFacts facts, MemoSources sources) {
		this.facts = Objects.requireNonNull(facts, "program facts");
		this.sources = Objects.requireNonNull(sources, "sources");
		resolver = new MemoBindingResolver(facts);
		views = cloneViews(facts);
		for(int owner = 0; owner < views.length; owner++) viewOwners.put(views[owner], owner);
		initialShapes = new Shape[views.length];
		for(int owner = 0; owner < views.length; owner++) initialShapes[owner] = fromView(views[owner]);
		occurrencesByOwner = lists(views.length);
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			occurrencesByOwner.get(occurrence.compiledOwnerId()).add(occurrence);
		ownerShapes = new Shape[views.length];
		bindingShapes = new Shape[facts.bindings().size()];
		occurrenceViews = new Hop[facts.occurrences().size()];
		ownerConsumers = lists(views.length);
		ownerBindingConsumers = lists(views.length);
		bindingConsumers = lists(bindingShapes.length);
		bindingOwnerConsumers = lists(bindingShapes.length);
		formalArguments = lists(bindingShapes.length);
		ownerQueued = new boolean[views.length];
		bindingQueued = new boolean[bindingShapes.length];
		bindingConflicts = new boolean[bindingShapes.length];
		indexDependencies();
		for(int owner = 0; owner < views.length; owner++) enqueueOwner(owner);
		for(int binding = 0; binding < bindingShapes.length; binding++) enqueueBinding(binding);
		drain();
		finalizeUnresolved();
		drain();
		invariantCostStats=new boolean[bindingShapes.length];
		for(int binding=0;binding<bindingShapes.length;binding++)
			provesInvariantCostStats(binding,new LinkedHashSet<>());
		BindingDependencies dependencies=computeBindingDependencies(true);
		dimensionPhaseSensitiveByBinding=computeBindingDependencies(false).loops();
		phaseSensitiveByBinding=dependencies.loops();
		guardSensitiveByBinding=dependencies.guards();
	}

	public static MemoShapes analyze(MemoProgramFacts facts, MemoSources sources) {
		return new MemoShapes(facts, sources);
	}

	void requireRequest(MemoProgramFacts facts,MemoSources sources) {
		if(this.facts!=facts || this.sources!=sources)
			throw new IllegalArgumentException("Shape authority belongs to another planning request");
	}

	public Hop view(int owner) {
		return views[owner];
	}

	public Shape shape(int owner) {
		return ownerShapes[owner];
	}

	public Shape bindingShape(int binding) {
		return bindingShapes[binding];
	}

	public Shape shapeAt(int occurrence) {
		return occurrenceOutputShape(facts.occurrence(occurrence));
	}

	/** Shape of one semantic input edge after reaching-definition resolution. */
	public Shape inputShape(int occurrence, int position) {
		int binding = facts.inputBinding(occurrence, position);
		return binding >= 0 ? bindingShapes[binding] : unknown((Hop)null);
	}

	/** Phase- and call-context-specific binding shape without materializing environments. */
	public Shape bindingShape(int binding, EvaluationContext context) {
		Objects.requireNonNull(context,"evaluation context");
		if(binding < 0) return unknown((Hop)null);
		EvaluationContext normalized = normalizeLoopPhases(context);
		return resolvedBindingShape(binding,normalized,new PhaseEvaluation(),new LinkedHashSet<>());
	}

	public Shape inputShape(int occurrence, int position, EvaluationContext context) {
		int binding = facts.inputBinding(occurrence,position);
		return bindingShape(binding,context);
	}

	public Shape shapeAt(int occurrence, EvaluationContext context) {
		return occurrenceShape(occurrence,normalizeLoopPhases(context),new LinkedHashSet<>());
	}

	/** Loop phases that can alter native-rule dimension capability. */
	Set<Integer> phaseSensitiveLoops(int occurrence) {
		Hop hop=facts.occurrence(occurrence).hop();
		Set<Integer> result=new LinkedHashSet<>();
		for(int position=0;position<hop.getInput().size();position++) {
			int binding=facts.inputBinding(occurrence,position);
			if(binding>=0 && costRelevantInput(hop,position)) result.addAll(dimensionPhaseSensitiveByBinding.get(binding));
		}
		return result;
	}

	/** Exact dimensions alone do not imply identical sparsity or scalar shape parameters. */
	Set<MemoBindingResolver.GuardDecision> guardSensitiveDecisions(int occurrence,List<Integer> callFrame) {
		Hop hop=facts.occurrence(occurrence).hop();
		Set<MemoBindingResolver.GuardDecision> result=new LinkedHashSet<>();
		Set<PhaseVisit> visited=new LinkedHashSet<>();
		for(int position=0;position<hop.getInput().size();position++)
			if(costRelevantInput(hop,position))
				collectShapeGuards(facts.inputBinding(occurrence,position),List.copyOf(callFrame),result,visited);
		return Set.copyOf(result);
	}

	Set<MemoBindingResolver.GuardDecision> guardSensitiveDecisionsForBinding(int binding,List<Integer> callFrame) {
		Set<MemoBindingResolver.GuardDecision> result=new LinkedHashSet<>();
		collectShapeGuards(binding,List.copyOf(callFrame),result,new LinkedHashSet<>());
		return Set.copyOf(result);
	}

	/**
	 * Finds one unresolved cost-relevant guard by following the binding DAG under
	 * the supplied partial context. Unlike {@link #guardSensitiveDecisions}, this
	 * query never collects guards from an inactive SELECT arm and never resolves
	 * concrete producer origins.
	 */
	CostGuardSearch firstUnfixedCostGuard(int occurrence,EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		CostGuardAccumulator counters=new CostGuardAccumulator();
		Map<PhaseVisit,CostGuardSearch> memo=new HashMap<>();
		Set<PhaseVisit> active=new LinkedHashSet<>();
		Hop hop=facts.occurrence(occurrence).hop();
		boolean unknown=false;
		for(int position=0;position<hop.getInput().size();position++) {
			if(!costRelevantInput(hop,position)) continue;
			CostGuardSearch search=firstUnfixedCostGuard(facts.inputBinding(occurrence,position),normalized,
				memo,active,counters);
			if(search.guard()!=null) return counters.result(search.guard(),unknown || search.unresolved());
			unknown |= search.unresolved();
		}
		return counters.result(null,unknown);
	}

	CostGuardSearch firstUnfixedCostGuardForBinding(int binding,EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		CostGuardAccumulator counters=new CostGuardAccumulator();
		CostGuardSearch search=firstUnfixedCostGuard(binding,normalized,new HashMap<>(),
			new LinkedHashSet<>(),counters);
		return counters.result(search.guard(),search.unresolved());
	}

	record CostGuardSearch(MemoBindingResolver.GuardDecision guard,boolean unresolved,long bindingVisits,
		long cacheHits,long invariantProjectionStops,long noRegionsSkipped) { }

	private static final class CostGuardAccumulator {
		private long bindingVisits;
		private long cacheHits;
		private long invariantProjectionStops;
		private long noRegionsSkipped;
		private CostGuardSearch result(MemoBindingResolver.GuardDecision guard,boolean unresolved) {
			return new CostGuardSearch(guard,unresolved,bindingVisits,cacheHits,invariantProjectionStops,noRegionsSkipped);
		}
	}

	private CostGuardSearch firstUnfixedCostGuard(int binding,EvaluationContext context,
		Map<PhaseVisit,CostGuardSearch> memo,Set<PhaseVisit> active,CostGuardAccumulator counters) {
		if(binding<0 || binding>=facts.bindings().size())
			return new CostGuardSearch(null,true,0,0,0,0);
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.UNBOUND || node.kind()==BindingKind.MISSING)
			return new CostGuardSearch(null,true,0,0,0,0);
		if(guardSensitiveByBinding.get(binding).isEmpty()) {
			counters.invariantProjectionStops++;
			return new CostGuardSearch(null,false,0,0,0,0);
		}
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		CostGuardSearch cached=memo.get(key);
		if(cached!=null) { counters.cacheHits++; return cached; }
		if(!active.add(key)) {
			// Reaching definitions may close the recurrence before the LOOP_HEAD is
			// encountered a second time (definition -> head -> backedge -> definition).
			// The path to this point has already exposed every SELECT on the chosen
			// backedge. Discharge only against an active repeat head whose complete
			// first-to-middle cost statistics form an entry-grounded fixed point.
			if(provesActiveRepeatHeadCostStats(active,context))
				return new CostGuardSearch(null,false,0,0,0,0);
			return new CostGuardSearch(null,true,0,0,0,0);
		}
		counters.bindingVisits++;
		try {
			if(node.kind()==BindingKind.SELECT) {
				MemoBindingResolver.GuardDecision decision=facts.guardDecision(node.referenceId(),context.callFrame());
				Boolean selected=context.guards().get(decision);
				if(selected!=null)
					selected=facts.branchGuardValue(node.referenceId(),context.callFrame(),selected);
				int loop=facts.enclosingLoop(facts.guard(node.referenceId()).parentRegionId());
				if(selected==null && loop>=0) {
					IterationPhase phase=context.iterationPhases().get(new LoopDecision(loop,context.callFrame()));
					if(phase!=null) selected=facts.guardValue(loop,node.referenceId(),phase);
				}
				if(selected==null) return new CostGuardSearch(decision,false,0,0,0,0);
				int selectedIndex=selected ? 0 : 1;
				int inactiveIndex=selected ? 1 : 0;
				if(node.children().size()>inactiveIndex
					&& !guardSensitiveByBinding.get(node.children().get(inactiveIndex)).isEmpty())
					counters.noRegionsSkipped++;
				return remember(key,firstUnfixedCostGuard(node.children().get(selectedIndex),context,memo,active,counters),memo);
			}
			if(node.kind()==BindingKind.LOOP_HEAD) {
				IterationPhase phase=context.iterationPhases().get(new LoopDecision(node.referenceId(),context.callFrame()));
				if(phase==null) return new CostGuardSearch(null,true,0,0,0,0);
				// Follow the selected phase's value DAG. A retained-value backedge may
				// encounter this head again; only that cycle edge is discharged by the
				// entry-grounded full cost-statistics proof above.
				int child=phase==IterationPhase.FIRST ? 0 : 1;
				return node.children().size()>child
					? remember(key,firstUnfixedCostGuard(node.children().get(child),context,memo,active,counters),memo)
					: new CostGuardSearch(null,true,0,0,0,0);
			}
			if(node.kind()==BindingKind.LAST) {
				LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
				LoopCardinality cardinality=context.cardinalities().get(
					new LoopCardinalityDecision(node.referenceId(),context.callFrame()));
				if(cardinality==null) return new CostGuardSearch(null,true,0,0,0,0);
				int child=cardinality==LoopCardinality.ZERO ? 0 : 1;
				EvaluationContext selected=context;
				if(cardinality!=LoopCardinality.ZERO) {
					IterationPhase phase=cardinality==LoopCardinality.ONE
						? IterationPhase.FIRST : IterationPhase.LAST;
					selected=withPhase(context,loop,phase,LoopPhase.BACKEDGE);
				}
				return child<node.children().size()
					? remember(key,firstUnfixedCostGuard(node.children().get(child),selected,memo,active,counters),memo)
					: new CostGuardSearch(null,true,0,0,0,0);
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return remember(key,firstUnfixedCostGuard(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),memo,active,counters),memo);
				}
				return new CostGuardSearch(null,true,0,0,0,0);
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				// Cost callers must establish the call's NORMAL_CONTINUATION activation
				// before following its payload. MemoProgramModel.visitActive owns that
				// prerequisite; this traversal only projects the surviving return value.
				if(node.children().isEmpty()) return new CostGuardSearch(null,true,0,0,0,0);
				if(node.children().size()>1)
					return remember(key,firstUnfixedCostGuard(node.children().get(1),context,memo,active,counters),memo);
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return remember(key,firstUnfixedCostGuard(node.children().get(0),context.withCallFrame(frame),
					memo,active,counters),memo);
			}
			List<Integer> dependencies=new ArrayList<>();
			if(node.kind()==BindingKind.CALL_ARG || !node.children().isEmpty()) dependencies.addAll(node.children());
			else if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				MemoProgramFacts.Occurrence definition=facts.occurrence(node.referenceId());
				for(int position=0;position<definition.hop().getInput().size();position++)
					if(costRelevantInput(definition.hop(),position))
						dependencies.add(facts.inputBinding(definition.id(),position));
			}
			boolean unknown=false;
			for(int dependency:dependencies) {
				CostGuardSearch search=firstUnfixedCostGuard(dependency,context,memo,active,counters);
				if(search.guard()!=null) return remember(key,search,memo);
				unknown |= search.unresolved();
			}
			CostGuardSearch result=new CostGuardSearch(null,unknown,0,0,0,0);
			memo.put(key,result);
			return result;
		}
		finally { active.remove(key); }
	}

	private boolean provesActiveRepeatHeadCostStats(Set<PhaseVisit> active,EvaluationContext context) {
		List<PhaseVisit> path=new ArrayList<>(active);
		for(int index=path.size()-1;index>=0;index--) {
			PhaseVisit visit=path.get(index);
			if(visit.occurrence()) continue;
			MemoProgramFacts.BindingNode candidate=facts.binding(visit.id());
			if(candidate.kind()!=BindingKind.LOOP_HEAD) continue;
			// The closest active head owns this recurrence edge. An invariant outer
			// head cannot discharge a changing or unresolved nested-loop cycle.
			LoopDecision loop=new LoopDecision(candidate.referenceId(),visit.callFrame());
			IterationPhase phase=context.iterationPhases().get(loop);
			return phase!=null && phase!=IterationPhase.FIRST
				&& provesRepeatHeadCostStats(candidate,loop,context.withCallFrame(visit.callFrame()));
		}
		return false;
	}

	private boolean provesRepeatHeadCostStats(MemoProgramFacts.BindingNode node,LoopDecision loop,
		EvaluationContext context) {
		// The frozen estimator assigns one symbolic outcome to an unproved canonical
		// GuardDecision in this call frame. withPhase replaces that value only when
		// ProgramFacts proves a phase-specific iterator outcome.
		MemoProgramFacts.CarriedValue carried=facts.loop(loop.loopId()).carriedValues().stream()
			.filter(value -> value.slot()==node.position()).findFirst().orElse(null);
		if(carried==null) return false;
		PhaseEvaluation evaluation=new PhaseEvaluation();
		EvaluationContext first=withPhase(context,loop,IterationPhase.FIRST,LoopPhase.ENTRY);
		Shape seed=resolvedBindingShape(carried.backedgeBindingId(),first,evaluation,new LinkedHashSet<>());
		if(!knownCostStats(seed)) return false;
		evaluation.heads.put(new LoopSlotKey(loop,node.position()),seed);
		EvaluationContext middle=withPhase(context,loop,IterationPhase.MIDDLE,LoopPhase.BACKEDGE);
		Shape repeat=resolvedBindingShape(carried.backedgeBindingId(),middle,evaluation,new LinkedHashSet<>());
		return sameCostStats(seed,repeat);
	}

	private static boolean knownCostStats(Shape shape) {
		// NNZ=-1 is itself the frozen estimator input. It is safe to preserve after
		// the backedge walk has exposed and fixed every guard that can change it;
		// requiring a nonnegative NNZ here would reject existing exact-size loops.
		return shape!=null && shape.exact();
	}

	private static boolean sameCostStats(Shape left,Shape right) {
		return knownCostStats(left) && knownCostStats(right) && left.equals(right);
	}

	private static CostGuardSearch remember(PhaseVisit key,CostGuardSearch result,
		Map<PhaseVisit,CostGuardSearch> memo) {
		memo.put(key,result);
		return result;
	}

	private static boolean costRelevantInput(Hop hop,int position) {
		Hop input=hop.getInput(position);
		// Matrix/frame inputs contribute size and sparsity. Integer parameters can
		// control ordinary dimensions/index ranges; SEQ is the one native shape
		// operator whose FP64 from/to/increment values also control cardinality.
		return isMatrixLike(input) || input.getValueType()==ValueType.INT64
			|| costRelevantNumericScalar(hop,position);
	}

	private static boolean costRelevantNumericScalar(Hop hop,int position) {
		if(!(hop instanceof DataGenOp dataGen) || dataGen.getOp()!=OpOpDG.SEQ) return false;
		Map<String,Integer> parameters=dataGen.getParamIndexMap();
		return Objects.equals(parameters.get(Statement.SEQ_FROM),position)
			|| Objects.equals(parameters.get(Statement.SEQ_TO),position)
			|| Objects.equals(parameters.get(Statement.SEQ_INCR),position);
	}

	/** Positive-only proof over the frozen cost statistics. A cycle is not a proof. */
	private boolean provesInvariantCostStats(int binding,Set<Integer> active) {
		if(binding<0 || binding>=bindingShapes.length) return false;
		if(invariantCostStats[binding]) return true;
		Shape shape=bindingShapes[binding];
		if(shape==null || !shape.exact()) return false;
		if(shape.dataType()!=DataType.MATRIX && shape.dataType()!=DataType.FRAME)
			return invariantCostStats[binding]=scalarLong(binding,new LinkedHashSet<>())!=null;
		if(shape.nonZeros()>=0) return invariantCostStats[binding]=true;
		if(!active.add(binding)) return false;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			List<Integer> dependencies=new ArrayList<>();
			if(node.kind()==BindingKind.FORMAL) dependencies.addAll(formalArguments.get(binding));
			else if(!node.children().isEmpty()) dependencies.addAll(node.children());
			else if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				MemoProgramFacts.Occurrence definition=facts.occurrence(node.referenceId());
				for(int position=0;position<definition.hop().getInput().size();position++)
					if(costRelevantInput(definition.hop(),position))
						dependencies.add(facts.inputBinding(definition.id(),position));
			}
			else return false;
			for(int child:dependencies) if(!provesInvariantCostStats(child,active)) return false;
			if(node.kind()==BindingKind.SELECT || node.kind()==BindingKind.FORMAL
				|| node.kind()==BindingKind.LOOP_HEAD || node.kind()==BindingKind.LAST)
				for(int child:dependencies) if(!shape.equals(bindingShapes[child])) return false;
			return invariantCostStats[binding]=true;
		}
		finally { active.remove(binding); }
	}

	private void collectShapeGuards(int binding,List<Integer> frame,
		Set<MemoBindingResolver.GuardDecision> result,Set<PhaseVisit> visited) {
		if(binding<0 || binding>=facts.bindings().size() || guardSensitiveByBinding.get(binding).isEmpty()) return;
		PhaseVisit key=new PhaseVisit(false,binding,frame);
		if(!visited.add(key)) return;
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.SELECT)
			result.add(facts.guardDecision(node.referenceId(),frame));
		switch(node.kind()) {
			case SELECT, LOOP_HEAD, LAST, CALL_ARG -> node.children()
				.forEach(child -> collectShapeGuards(child,frame,result,visited));
			case FORMAL -> {
				for(int index=frame.size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(frame.get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size()) {
						collectShapeGuards(call.actualBindingIds().get(node.position()),frame.subList(0,index),result,visited);
						break;
					}
				}
			}
			case CALL_RETURN -> {
				if(node.children().size()>1) collectShapeGuards(node.children().get(1),frame,result,visited);
				else if(!node.children().isEmpty()) {
					List<Integer> nested=new ArrayList<>(frame); nested.add(node.referenceId());
					collectShapeGuards(node.children().get(0),nested,result,visited);
				}
			}
			case DEFINITION -> {
				if(!node.children().isEmpty()) collectShapeGuards(node.children().get(0),frame,result,visited);
				else if(node.referenceId()>=0 && node.referenceId()<facts.occurrences().size()) {
					MemoProgramFacts.Occurrence producer=facts.occurrence(node.referenceId());
					for(int position=0;position<producer.hop().getInput().size();position++)
						if(costRelevantInput(producer.hop(),position)) collectShapeGuards(facts.inputBinding(producer.id(),position),frame,result,visited);
				}
			}
			case CALL_COMPLETION, UNBOUND, MISSING -> { }
		}
	}

	private record BindingDependencies(List<Set<Integer>> loops,List<Set<Integer>> guards) { }

	private BindingDependencies computeBindingDependencies(boolean costStatistics) {
		int count=facts.bindings().size();
		List<Set<Integer>> loops=new ArrayList<>(count);
		List<Set<Integer>> guards=new ArrayList<>(count);
		List<List<Integer>> dependencies=lists(count);
		List<List<Integer>> consumers=lists(count);
		for(int binding=0; binding<count; binding++) {
			loops.add(new LinkedHashSet<>());
			guards.add(new LinkedHashSet<>());
		}
		for(int binding=0; binding<count; binding++) {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if((costStatistics ? invariantCostStats[binding]
				: bindingShapes[binding]!=null && bindingShapes[binding].exact())
				|| node.kind()==BindingKind.LAST) continue;
			if(node.kind()==BindingKind.SELECT) {
				guards.get(binding).add(node.referenceId());
				int loop=facts.enclosingLoop(facts.guard(node.referenceId()).parentRegionId());
				if(loop>=0 && !facts.guardPhases(loop).isEmpty()) loops.get(binding).add(loop);
			}
			if(node.kind()==BindingKind.LOOP_HEAD) loops.get(binding).add(node.referenceId());
			dependencies.get(binding).addAll(node.children());
			if(node.kind()==BindingKind.FORMAL)
				for(MemoProgramFacts.CallBinding call : facts.callsForFunction(node.referenceId()))
					if(node.position()>=0 && node.position()<call.argumentBindingIds().size())
						dependencies.get(binding).add(call.argumentBindingIds().get(node.position()));
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				MemoProgramFacts.Occurrence definition=facts.occurrence(node.referenceId());
				for(int position=0; position<definition.hop().getInput().size(); position++) {
					int input=facts.inputBinding(definition.id(),position);
					if(input>=0 && costRelevantInput(definition.hop(),position)) dependencies.get(binding).add(input);
				}
			}
			for(int dependency : dependencies.get(binding))
				if(dependency>=0 && dependency<count) consumers.get(dependency).add(binding);
		}
		ArrayDeque<Integer> work=new ArrayDeque<>();
		boolean[] queued=new boolean[count];
		for(int binding=0; binding<count; binding++) { work.add(binding); queued[binding]=true; }
		while(!work.isEmpty()) {
			int binding=work.removeFirst(); queued[binding]=false; relevanceVisits++;
			boolean changed=false;
			for(int dependency : dependencies.get(binding)) if(dependency>=0 && dependency<count) {
				relevanceEdges++;
				changed |= loops.get(binding).addAll(loops.get(dependency));
				changed |= guards.get(binding).addAll(guards.get(dependency));
			}
			if(changed) for(int consumer : consumers.get(binding)) if(!queued[consumer]) {
				queued[consumer]=true; work.addLast(consumer);
			}
		}
		return new BindingDependencies(loops.stream().map(Set::copyOf).toList(),
			guards.stream().map(Set::copyOf).toList());
	}

	public Hop viewAt(int occurrence, EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		PhaseEvaluation phaseEvaluation=new PhaseEvaluation();
		Set<PhaseVisit> active=new LinkedHashSet<>();
		Hop view = cloneView(views[facts.occurrence(occurrence).compiledOwnerId()]);
		applyShape(view,occurrenceShape(occurrence,normalized,phaseEvaluation,active));
		for(int position=0; position<view.getInput().size(); position++)
			applyShape(view.getInput(position),resolvedBindingShape(facts.inputBinding(occurrence,position),
				normalized,phaseEvaluation,active));
		applyMultiReturnOutputShapes(view);
		return view;
	}

	/** Private contextual physical output view; never exposes a live compiler HOP. */
	public Hop outputViewAt(int occurrence,int slot,EvaluationContext context) {
		Hop view=viewAt(occurrence,context);
		if(view instanceof FunctionOp function
			&& function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN) {
			if(function.getOutputs()==null || slot<0 || slot>=function.getOutputs().size()
				|| function.getOutputs().get(slot)==null)
				throw new IllegalArgumentException("Invalid multi-return output slot "+slot+" at occurrence "+occurrence);
			return function.getOutputs().get(slot);
		}
		if(slot!=0) throw new IllegalArgumentException("Invalid output slot "+slot+" at occurrence "+occurrence);
		return view;
	}

	/** Cost-only private view of one exact semantic value binding. */
	public Hop bindingViewAt(int binding,EvaluationContext context) {
		Shape shape=bindingShape(binding,context);
		Hop template=bindingTemplate(binding,shape,new LinkedHashSet<>());
		if(template==null)
			throw new IllegalStateException("No physical shape template for binding "+binding);
		Hop view=cloneView(template);
		applyShape(view,shape);
		return view;
	}

	private Hop bindingTemplate(int binding,Shape shape,Set<Integer> active) {
		if(binding<0 || binding>=facts.bindings().size() || !active.add(binding)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(node.position()>=0 && hop instanceof FunctionOp function && function.getOutputs()!=null
					&& node.position()<function.getOutputs().size() && function.getOutputs().get(node.position())!=null)
					hop=function.getOutputs().get(node.position());
				if(hop.getDataType()==shape.dataType()) return hop;
			}
			List<Integer> children=node.kind()==BindingKind.FORMAL ? formalArguments.get(binding) : node.children();
			for(int child:children) {
				Hop template=bindingTemplate(child,shape,active);
				if(template!=null) return template;
			}
			return null;
		}
		finally { active.remove(binding); }
	}

	public ShapeHint hintAt(int occurrence, EvaluationContext context) {
		Hop hop = viewAt(occurrence,context);
		Shape output = fromView(hop);
		List<Shape> payloads = new ArrayList<>(2);
		for(int position=0; position<hop.getInput().size() && payloads.size()<2; position++)
			if(isMatrixLike(hop.getInput(position))) payloads.add(fromView(hop.getInput(position)));
		Shape first=payloads.size()>0 ? payloads.get(0) : null;
		Shape second=payloads.size()>1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output,true),dimension(output,false),hop.getBlocksize(),
			Optional.empty(),dimension(first,true),dimension(first,false),
			dimension(second,true),dimension(second,false));
	}

	private record LoopSlotKey(LoopDecision loop,int slot) { }
	private record PhaseVisit(boolean occurrence,int id,List<Integer> callFrame) {
		private PhaseVisit { callFrame=List.copyOf(callFrame); }
	}
	/** Alias provenance can cross invocation boundaries; syntactic guard IDs alone are not cache keys. */
	private static boolean guardRelevant(MemoProgramFacts facts,MemoBindingResolver.GuardDecision decision,
		Set<Integer> dependencies,List<Integer> frame) {
		for(int guard:dependencies) {
			for(int length=frame.size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,frame.subList(0,length))) return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,decision.callFrame().subList(0,length))) return true;
		}
		return false;
	}
	// Completion-only guards (including other bound returns) are not captured
	// by the payload shape relevance projection. Retain the full semantic
	// context instead of mixing normal and terminating caller environments.
	private record PhaseNodeKey(boolean occurrence,int id,ShapeContextKey context) { }
	private record ShapeContextKey(Map<MemoBindingResolver.GuardDecision,Boolean> guards,
		Map<LoopDecision,LoopPhase> loops,Map<LoopDecision,IterationPhase> iterations,
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities,List<Integer> callFrame,
		int maxDepth,BitSet boundHeads) { }

	private ShapeContextKey shapeContextKey(EvaluationContext context) {
		// Layout/rule choices do not affect logical shape. Only headIsBound is
		// read by source resolution. Snapshot it, never retain a mutable lookup.
		// Include all existing heads: completion can depend on another return
		// whose head is absent from the requested payload's dependency projection.
		BitSet heads=new BitSet(); int index=0;
		if(context.selections()!=MemoBindingResolver.NO_SELECTION)
			for(MemoProgramFacts.LoopRecord loop:facts.loops())
				for(MemoProgramFacts.CarriedValue value:loop.carriedValues()) {
					if(context.selections().headIsBound(loop.id(),value.slot())) heads.set(index);
					index++;
				}
		return new ShapeContextKey(context.guards(),context.loops(),context.iterationPhases(),
			context.cardinalities(),context.callFrame(),context.maxDepth(),heads);
	}

	private EvaluationContext normalizeLoopPhases(EvaluationContext context) {
		Map<LoopDecision,LoopPhase> loops = new HashMap<>(context.loops());
		for(var entry : context.iterationPhases().entrySet())
			loops.putIfAbsent(entry.getKey(),entry.getValue()==IterationPhase.FIRST
				? LoopPhase.ENTRY : LoopPhase.BACKEDGE);
		return new EvaluationContext(context.guards(),loops,context.selections(),context.maxDepth(),
			context.callFrame(),context.iterationPhases(),context.cardinalities());
	}

	private final class PhaseEvaluation {
		private final Map<LoopSlotKey,Shape> heads = new HashMap<>();
		private final Set<LoopSlotKey> evaluating = new LinkedHashSet<>();

		private Shape head(ResolvedOrigin origin,EvaluationContext context,Set<PhaseVisit> active) {
			LoopDecision loop=new LoopDecision(origin.referenceId(),origin.callFrame());
			return head(loop,origin.boundarySlot(),context);
		}

		private Shape head(LoopDecision loop,int slot,EvaluationContext context) {
			IterationPhase requested=context.iterationPhases().get(loop);
			if(requested==null || requested==IterationPhase.FIRST) return unknown((Hop)null);
			LoopSlotKey key=new LoopSlotKey(loop,slot);
			Shape known=heads.get(key);
			if(known!=null) return known;
			if(!evaluating.add(key)) return unknown((Hop)null);
			try {
				MemoProgramFacts.CarriedValue carried=facts.loop(loop.loopId()).carriedValues().stream()
					.filter(value -> value.slot()==slot).findFirst().orElse(null);
				if(carried==null) return unknown((Hop)null);
				EvaluationContext first=withPhase(context,loop,IterationPhase.FIRST,LoopPhase.ENTRY);
				Shape seed=resolvedBindingShape(carried.backedgeBindingId(),first,this,new LinkedHashSet<>());
				if(!seed.exact()) return unknown((Hop)null);

				// A repeat phase is grounded only by the proven first iteration. The
				// contract is accepted when one further body application preserves it.
				heads.put(key,seed);
				EvaluationContext middle=withPhase(context,loop,IterationPhase.MIDDLE,LoopPhase.BACKEDGE);
				Shape repeat=resolvedBindingShape(carried.backedgeBindingId(),middle,this,new LinkedHashSet<>());
				if(!repeat.exact() || !sameDimensions(seed,repeat)) {
					heads.remove(key);
					return unknown((Hop)null);
				}
				return seed;
			}
			finally { evaluating.remove(key); }
		}

		private EvaluationContext bindDirectHeads(int binding,EvaluationContext context) {
			for(MemoBindingResolver.DirectLoopHead slot :
				resolver.directLoopHeadIdentities(binding,context))
				head(new LoopDecision(slot.loopId(),slot.callFrame()),slot.slot(),context);
			MemoBindingResolver.SelectionLookup prior=context.selections();
			MemoBindingResolver.SelectionLookup bound=new MemoBindingResolver.SelectionLookup() {
				@Override public int selectedRule(int owner) { return prior.selectedRule(owner); }
				@Override public MemoPartition selectedLayout(int owner) { return prior.selectedLayout(owner); }
				@Override public MemoPartition selectedHeadLayout(int loop,int slot) {
					return prior.selectedHeadLayout(loop,slot);
				}
				@Override public boolean headIsBound(int loop,int slot) {
					return heads.keySet().stream().anyMatch(key -> key.loop().loopId()==loop && key.slot()==slot)
						|| prior.headIsBound(loop,slot);
				}
			};
			return new EvaluationContext(context.guards(),context.loops(),bound,context.maxDepth(),
				context.callFrame(),context.iterationPhases(),context.cardinalities());
		}
	}

	private EvaluationContext withPhase(EvaluationContext context,LoopDecision loop,
		IterationPhase iteration,LoopPhase boundary) {
		Map<LoopDecision,IterationPhase> iterations=new HashMap<>(context.iterationPhases());
		iterations.put(loop,iteration);
		Map<LoopDecision,LoopPhase> loops=new HashMap<>(context.loops());
		loops.put(loop,boundary);
		Map<MemoBindingResolver.GuardDecision,Boolean> guards=new HashMap<>(context.guards());
		// Activation resolution contributes the guard value of the currently
		// visited iteration. When proving the loop contract at another phase, a
		// phase-proven guard must be recomputed rather than carried across phases.
		guards.keySet().removeIf(guard -> phaseProvesGuard(loop,iteration,guard));
		return new EvaluationContext(guards,loops,context.selections(),context.maxDepth(),
			context.callFrame(),iterations,context.cardinalities());
	}

	private boolean phaseProvesGuard(LoopDecision loop,IterationPhase iteration,
		MemoBindingResolver.GuardDecision decision) {
		for(MemoProgramFacts.Guard guard:facts.guards()) {
			if(facts.guardValue(loop.loopId(),guard.id(),iteration)==null) continue;
			for(int length=loop.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard.id(),loop.callFrame().subList(0,length)))
					return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard.id(),decision.callFrame().subList(0,length)))
					return true;
		}
		return false;
	}

	private static EvaluationContext withEnvironment(EvaluationContext context,
		MemoBindingResolver.ResolvedEnvironment environment) {
		return new EvaluationContext(environment.guards(),environment.loops(),context.selections(),
			context.maxDepth(),context.callFrame(),environment.iterationPhases(),environment.cardinalities());
	}

	private Shape resolvedBindingShape(int binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding < 0 || binding >= bindingShapes.length) return unknown((Hop)null);
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.SELECT) {
			MemoBindingResolver.GuardDecision guard=
				facts.guardDecision(node.referenceId(),context.callFrame());
			Boolean selected=context.guards().get(guard);
			if(selected!=null) selected=facts.branchGuardValue(node.referenceId(),context.callFrame(),selected);
			int loop=facts.enclosingLoop(facts.guard(node.referenceId()).parentRegionId());
			if(selected==null && loop>=0) {
				IterationPhase phase=context.iterationPhases().get(new LoopDecision(loop,context.callFrame()));
				if(phase!=null) selected=facts.guardValue(loop,node.referenceId(),phase);
			}
			if(selected!=null)
				return resolvedBindingShape(node.children().get(selected ? 0 : 1),context,phaseEvaluation,active);
			Shape left=resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			Shape right=resolvedBindingShape(node.children().get(1),context,phaseEvaluation,active);
			return commonShape(left,right);
		}
		if(node.kind()==BindingKind.LOOP_HEAD) {
			LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
			IterationPhase phase=context.iterationPhases().get(loop);
			if(phase==IterationPhase.FIRST)
				return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			if(phase!=null) return phaseEvaluation.head(loop,node.position(),context);
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.LAST) {
			LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
			LoopCardinality cardinality=context.cardinalities().get(
				new LoopCardinalityDecision(node.referenceId(),context.callFrame()));
			if(cardinality==LoopCardinality.ZERO)
				return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			if(cardinality!=null && node.children().size()>1) {
				IterationPhase phase=cardinality==LoopCardinality.ONE
					? IterationPhase.FIRST : IterationPhase.LAST;
				return resolvedBindingShape(node.children().get(1),
					withPhase(context,loop,phase,LoopPhase.BACKEDGE),phaseEvaluation,active);
			}
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.CALL_ARG)
			return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
		if(node.kind()==BindingKind.FORMAL) {
			for(int index=context.callFrame().size()-1; index>=0; index--) {
				MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
				if(call.functionSummaryId()==node.referenceId() && node.position()>=0
					&& node.position()<call.actualBindingIds().size())
					return resolvedBindingShape(call.actualBindingIds().get(node.position()),
						context.withCallFrame(context.callFrame().subList(0,index)),phaseEvaluation,active);
			}
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.CALL_RETURN)
			return returnedShape(node,context,phaseEvaluation,active);
		// A transient definition with a child is a value-version alias, not a
		// native producer. The ordinary shape worklist uses the same rule in
		// evaluateBinding. Preserve it here before invoking source resolution:
		// resolving the wrapper as an origin can revisit its loop SELECT through
		// a backedge and add a spurious UNRESOLVED arm even when the bound loop
		// head has an exact inductive shape.
		if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty())
			return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
		Set<Integer> loopDependencies=phaseSensitiveByBinding.get(binding);
		Set<Integer> guardDependencies=guardSensitiveByBinding.get(binding);
		boolean phaseRelevant=context.iterationPhases().keySet().stream()
			.anyMatch(loop -> loopDependencies.contains(loop.loopId()));
		boolean guardRelevant=context.guards().keySet().stream()
			.anyMatch(guard -> guardRelevant(facts,guard,guardDependencies,context.callFrame()));
		if(!phaseRelevant && !guardRelevant) return bindingShapes[binding];
		PhaseNodeKey cacheKey=new PhaseNodeKey(false,binding,shapeContextKey(context));
		Shape cached=phaseShapeCache.get(cacheKey);
		if(cached!=null) return cached;
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return unknown((Hop)null);
		try {
			EvaluationContext grounded=phaseEvaluation.bindDirectHeads(binding,context);
			Shape[] joined={null};
			resolver.visitJoint(List.of(binding),grounded,environment -> {
				EvaluationContext resolved=withEnvironment(grounded,environment);
				Shape current=originShape(environment.origins().get(0),resolved,phaseEvaluation,active);
				joined[0]=joined[0]==null ? current : commonShape(joined[0],current);
				return true;
			});
			Shape result=joined[0]==null ? bindingShapes[binding] : joined[0];
			phaseShapeCache.put(cacheKey,result);
			return result;
		}
		finally { active.remove(key); }
	}

	private Shape returnedShape(MemoProgramFacts.BindingNode binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding.children().isEmpty() || binding.referenceId()<0
			|| binding.referenceId()>=facts.calls().size()) return unknown((Hop)null);
		int completion=facts.call(binding.referenceId()).completionBindingId();
		if(completion<0) return unknown((Hop)null);
		// Keep the established payload/loop induction path when completion is
		// total. Resolving every source again can reopen grounded backedge cycles.
		if(resolver.alwaysAvailable(completion,context.callFrame()))
			return returnedPayloadShape(binding,context,phaseEvaluation,active);
		PhaseVisit key=new PhaseVisit(false,binding.id(),context.callFrame());
		if(!active.add(key)) return unknown((Hop)null);
		try {
			EvaluationContext grounded=phaseEvaluation.bindDirectHeads(binding.id(),context);
			Shape[] joined={null};
			var visit=resolver.visitJoint(List.of(completion),grounded,environment -> {
				OriginKind kind=environment.origins().get(0).kind();
				// The function still terminates on this path; only its caller's
				// nonexistent return value is excluded from the shape join.
				if(kind==OriginKind.TERMINATED_AT_RETURN) return true;
				Shape current=kind==OriginKind.NORMAL_CONTINUATION
					? returnedPayloadShape(binding,withEnvironment(grounded,environment),phaseEvaluation,active)
					: unknown((Hop)null);
				joined[0]=joined[0]==null ? current : commonShape(joined[0],current);
				return true;
			});
			return visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED || joined[0]==null
				? unknown((Hop)null) : joined[0];
		}
		finally { active.remove(key); }
	}

	private boolean hasNormalContinuation(int activation,EvaluationContext context) {
		if(resolver.alwaysAvailable(activation,context.callFrame())) return true;
		boolean[] normal={false};
		var visit=resolver.visitJoint(List.of(activation),context,environment -> {
			normal[0] |= environment.origins().get(0).kind()==OriginKind.NORMAL_CONTINUATION;
			return true;
		});
		return visit.status()==MemoBindingResolver.VisitStatus.COMPLETE && normal[0];
	}

	private Shape returnedPayloadShape(MemoProgramFacts.BindingNode binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding.children().size()>1)
			return resolvedBindingShape(binding.children().get(1),context,phaseEvaluation,active);
		List<Integer> frame=new ArrayList<>(context.callFrame());
		frame.add(binding.referenceId());
		return resolvedBindingShape(binding.children().get(0),context.withCallFrame(frame),phaseEvaluation,active);
	}

	private Shape originShape(ResolvedOrigin origin,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(origin.kind()==OriginKind.COMPILED_OWNER) {
			Shape slot=multiReturnOutputShape(origin,context,phaseEvaluation,active);
			if(slot!=null) return slot;
			return occurrenceShape(origin.referenceId(),context.withCallFrame(origin.callFrame()),phaseEvaluation,active);
		}
		if(origin.kind()==OriginKind.LOOP_HEAD_CONTRACT) {
			return phaseEvaluation.head(origin,context,active);
		}
		if(origin.kind()==OriginKind.ITERATOR_LOCAL)
			return new Shape(DataType.SCALAR,ValueType.INT64,-1,-1,-1,Knowledge.EXACT);
		return unknown((Hop)null);
	}

	private Shape multiReturnOutputShape(ResolvedOrigin origin,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		Shape result=multiReturnOutputShape(origin.referenceId(),origin.boundarySlot());
		if(result!=null && result.exact() || origin.boundarySlot()!=0) return result;
		Hop hop=facts.occurrence(origin.referenceId()).hop();
		if(!shapePreservingTransformEncode(hop) || hop.getInput().isEmpty()) return result;
		Shape input=resolvedBindingShape(facts.inputBinding(origin.referenceId(),0),
			context.withCallFrame(origin.callFrame()),phaseEvaluation,active);
		return input.exact() ? new Shape(DataType.MATRIX,ValueType.FP64,input.rows(),input.columns(),
			-1,Knowledge.EXACT) : result;
	}

	private Shape multiReturnOutputShape(int occurrence,int slot) {
		if(slot<0 || occurrence<0 || occurrence>=facts.occurrences().size()) return null;
		Hop hop=facts.occurrence(occurrence).hop();
		if(!(hop instanceof FunctionOp function)
			|| function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| function.getOutputs()==null || slot>=function.getOutputs().size()) return null;
		Hop output=function.getOutputs().get(slot);
		Shape result=output==null ? null : fromView(output);
		if(slot==0 && (result==null || !result.exact()) && shapePreservingTransformEncode(function)
			&& !function.getInput().isEmpty()) {
			int inputBinding=facts.inputBinding(occurrence,0);
			Shape input=inputBinding>=0 ? bindingShapes[inputBinding] : null;
			if(input!=null && input.exact())
				return new Shape(DataType.MATRIX,ValueType.FP64,input.rows(),input.columns(),-1,Knowledge.EXACT);
		}
		return result;
	}

	private static void applyMultiReturnOutputShapes(Hop hop) {
		if(!(hop instanceof FunctionOp function) || !shapePreservingTransformEncode(function)
			|| function.getInput().get(0).getDim1()<=0 || function.getInput().get(0).getDim2()<=0
			|| function.getOutputs()==null || function.getOutputs().isEmpty()) return;
		Hop input=function.getInput().get(0);
		Hop output=function.getOutputs().get(0);
		if(output!=null)
			applyShape(output,new Shape(DataType.MATRIX,ValueType.FP64,input.getDim1(),input.getDim2(),
				-1,Knowledge.EXACT));
	}

	private static boolean shapePreservingTransformEncode(Hop hop) {
		if(!(hop instanceof FunctionOp function)
			|| function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !function.getFunctionName().equalsIgnoreCase(Opcodes.TRANSFORMENCODE.toString())
			|| function.getInput().size()!=2 || !(function.getInput(1) instanceof LiteralOp literal)) return false;
		try {
			JSONObject spec=new JSONObject(literal.getStringValue());
			return spec.containsKey("recode") && !spec.containsKey("dummycode")
				&& !spec.containsKey("omit") && !spec.containsKey("impute")
				&& !spec.containsKey("bin") && !spec.containsKey("hash");
		}
		catch(Exception ignored) { return false; }
	}

	private Shape occurrenceShape(int occurrence,EvaluationContext context,Set<PhaseVisit> active) {
		return occurrenceShape(occurrence,context,new PhaseEvaluation(),active);
	}

	private Shape occurrenceShape(int occurrence,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		PhaseNodeKey cacheKey=new PhaseNodeKey(true,occurrence,shapeContextKey(context));
		Shape cached=phaseShapeCache.get(cacheKey);
		if(cached!=null) return cached;
		PhaseVisit key=new PhaseVisit(true,occurrence,context.callFrame());
		if(!active.add(key)) return unknown(facts.occurrence(occurrence).hop());
		try {
			Hop view=cloneView(views[facts.occurrence(occurrence).compiledOwnerId()]);
			for(int position=0; position<view.getInput().size(); position++) {
				Shape input=resolvedBindingShape(facts.inputBinding(occurrence,position),context,phaseEvaluation,active);
				// A terminated/missing value has no contextual type. Do not revive
				// its consumer using a frozen shape from a different normal path.
				if(input==null || input.dataType()==DataType.UNKNOWN) {
					int activation=facts.occurrence(occurrence).activationBindingId();
					if(activation>=0 && !hasNormalContinuation(activation,context)) {
						Shape result=unknown(view);
						phaseShapeCache.put(cacheKey,result);
						return result;
					}
				}
				applyShape(view.getInput(position),input);
			}
			for(int position=0;position<view.getInput().size();position++) {
				Hop input=view.getInput(position);
				if(input.getDataType().isScalar() && !(input instanceof LiteralOp)) {
					if(input.getValueType()==ValueType.INT64) {
						Long value=scalarLong(facts.inputBinding(occurrence,position),context,new LinkedHashSet<>());
						if(value!=null) view.getInput().set(position,new LiteralOp(value));
					}
					else if(costRelevantNumericScalar(view,position)) {
						Double value=scalarDouble(facts.inputBinding(occurrence,position),context,new LinkedHashSet<>());
						if(value!=null) view.getInput().set(position,new LiteralOp(value));
					}
				}
			}
			applyMultiReturnOutputShapes(view);
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1); view.setDim2(-1); view.setNnz(-1);
			}
			view.refreshSizeInformation();
			Shape derived=fromView(view);
			Shape result=derived.exact() ? derived : occurrenceOutputShape(facts.occurrence(occurrence));
			phaseShapeCache.put(cacheKey,result);
			return result;
		}
		catch(RuntimeException ignored) {
			Shape result=occurrenceOutputShape(facts.occurrence(occurrence));
			phaseShapeCache.put(cacheKey,result);
			return result;
		}
		finally { active.remove(key); }
	}

	/**
	 * Private operator view with occurrence-specific input shapes. One compiled
	 * owner can occur at different value versions, so its owner-wide input join
	 * is insufficient for native capability and cost evaluation.
	 */
	public Hop viewAt(int occurrence) {
		Hop cached = occurrenceViews[occurrence];
		if(cached == null) {
			MemoProgramFacts.Occurrence use = facts.occurrence(occurrence);
			cached = cloneView(views[use.compiledOwnerId()]);
			applyShape(cached, occurrenceOutputShape(use));
			for(int position = 0; position < cached.getInput().size(); position++)
				applyShape(cached.getInput(position), inputShape(occurrence, position));
			applyMultiReturnOutputShapes(cached);
			occurrenceViews[occurrence] = cached;
		}
		// Oracle and runtime-cost preparation operate on mutable HOP APIs. Never
		// let one consumer's refresh/selection annotations corrupt the cached fact.
		return cloneView(cached);
	}

	private Shape occurrenceOutputShape(MemoProgramFacts.Occurrence occurrence) {
		if(isTransientRead(occurrence.hop())) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding >= 0) return bindingShapes[binding];
		}
		Shape dataGen = dataGenShape(occurrence);
		if(dataGen != null) return dataGen;
		Shape derived = refreshOccurrenceShape(occurrence);
		if(derived != null && derived.exact()) return derived;
		return ownerShapes[occurrence.compiledOwnerId()];
	}

	/**
	 * Derives an output from the exact reaching definitions at this occurrence.
	 * An owner-wide join deliberately loses dimensions when one compiled HOP is
	 * reused at multiple value versions, but each semantic occurrence can still
	 * have an exact operator result.
	 */
	private Shape refreshOccurrenceShape(MemoProgramFacts.Occurrence occurrence) {
		Hop view = cloneView(views[occurrence.compiledOwnerId()]);
		for(int position = 0; position < view.getInput().size(); position++) {
			int binding = facts.inputBinding(occurrence.id(),position);
			if(binding < 0 || bindingShapes[binding] == null) return null;
			applyShape(view.getInput(position),bindingShapes[binding]);
		}
		try {
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1);
				view.setDim2(-1);
				view.setNnz(-1);
			}
			view.refreshSizeInformation();
			return fromView(view);
		}
		catch(RuntimeException ignored) {
			return null;
		}
	}

	private Shape dataGenOwnerShape(int owner) {
		Shape result = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			Shape current = dataGenShape(occurrence);
			if(current == null) return null;
			result = result == null ? current : commonShape(result,current);
		}
		return result;
	}

	private Shape dataGenShape(MemoProgramFacts.Occurrence occurrence) {
		if(!(occurrence.hop() instanceof DataGenOp dataGen)
			|| dataGen.getOp() != OpOpDG.RAND && dataGen.getOp() != OpOpDG.SINIT
				&& dataGen.getOp() != OpOpDG.FRAMEINIT)
			return null;
		Integer rowPosition = dataGen.getParamIndexMap().get(DataExpression.RAND_ROWS);
		Integer columnPosition = dataGen.getParamIndexMap().get(DataExpression.RAND_COLS);
		Long rows = scalarLongAtInput(occurrence.id(),rowPosition);
		Long columns = scalarLongAtInput(occurrence.id(),columnPosition);
		return rows != null && rows > 0 && columns != null && columns > 0
			? exact(dataGen,rows,columns,-1) : null;
	}

	private Long scalarLongAtInput(int occurrence, Integer position) {
		if(position == null || position < 0) return null;
		int binding = facts.inputBinding(occurrence, position);
		return binding >= 0 ? scalarLong(binding, new LinkedHashSet<>()) : null;
	}

	public List<Diagnostic> diagnostics() {
		return List.copyOf(diagnostics);
	}

	public ShapeHint hint(int owner) {
		Hop hop = view(owner);
		Shape output = shape(owner);
		List<Shape> payloads = payloadInputShapes(hop,null);
		Shape first = payloads.size() > 0 ? payloads.get(0) : null;
		Shape second = payloads.size() > 1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output, true), dimension(output, false), hop.getBlocksize(),
			Optional.empty(), dimension(first, true), dimension(first, false),
			dimension(second, true), dimension(second, false));
	}

	public ShapeHint hintAt(int occurrence) {
		Hop hop = viewAt(occurrence);
		Shape output = occurrenceOutputShape(facts.occurrence(occurrence));
		List<Shape> payloads = payloadInputShapes(hop,occurrence);
		Shape first = payloads.size() > 0 ? payloads.get(0) : null;
		Shape second = payloads.size() > 1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output, true), dimension(output, false), hop.getBlocksize(),
			Optional.empty(), dimension(first, true), dimension(first, false),
			dimension(second, true), dimension(second, false));
	}

	private Shape occurrenceInputShape(int occurrence, Hop hop, int position) {
		return position < hop.getInput().size() ? inputShape(occurrence, position) : null;
	}

	private List<Shape> payloadInputShapes(Hop hop, Integer occurrence) {
		List<Shape> result = new ArrayList<>(2);
		for(int position = 0; position < hop.getInput().size() && result.size() < 2; position++)
			if(isMatrixLike(hop.getInput(position)))
				result.add(occurrence == null ? inputShape(hop,position)
					: occurrenceInputShape(occurrence,hop,position));
		return result;
	}

	private void indexDependencies() {
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences()) {
			int consumer = occurrence.compiledOwnerId();
			for(Hop input : occurrence.hop().getInput()) {
				int inputOwner = facts.compiledOwnerId(input);
				if(inputOwner >= 0) add(ownerConsumers.get(inputOwner), consumer);
			}
			if(isTransientRead(occurrence.hop())) {
				int binding = facts.valueBinding(occurrence.id());
				if(binding >= 0) add(bindingOwnerConsumers.get(binding), consumer);
			}
			for(int position = 0; position < occurrence.hop().getInput().size(); position++) {
				int binding = facts.inputBinding(occurrence.id(),position);
				if(binding >= 0) add(bindingOwnerConsumers.get(binding),consumer);
			}
		}
		for(MemoProgramFacts.BindingNode binding : facts.bindings()) {
			for(int child : binding.children())
				if(child >= 0) add(bindingConsumers.get(child), binding.id());
			if(binding.kind() == BindingKind.DEFINITION && binding.children().isEmpty()
				&& binding.referenceId() >= 0 && binding.referenceId() < facts.occurrences().size()) {
				add(ownerBindingConsumers.get(
					facts.occurrence(binding.referenceId()).compiledOwnerId()), binding.id());
				MemoProgramFacts.Occurrence definition = facts.occurrence(binding.referenceId());
				for(int position = 0; position < definition.hop().getInput().size(); position++) {
					int input = facts.inputBinding(definition.id(),position);
					if(input >= 0) add(bindingConsumers.get(input),binding.id());
					if(definition.hop().getInput(position).getDataType().isScalar())
						indexScalarValueDependencies(binding.id(),
							input,new LinkedHashSet<>());
				}
			}
		}
		for(MemoProgramFacts.FunctionSummary function : facts.functions()) {
			for(int position = 0; position < function.formalBindingIds().size(); position++) {
				int formal = function.formalBindingIds().get(position);
				for(MemoProgramFacts.CallBinding call : facts.callsForFunction(function.id())) {
					if(call.callOccurrenceId() < 0 || position >= call.argumentBindingIds().size())
						continue;
					int argument = call.argumentBindingIds().get(position);
					add(formalArguments.get(formal), argument);
					add(bindingConsumers.get(argument), formal);
				}
			}
		}
	}

	private void indexScalarValueDependencies(int dependent, int binding, Set<Integer> visiting) {
		if(binding < 0 || !visiting.add(binding)) return;
		add(bindingConsumers.get(binding),dependent);
		MemoProgramFacts.BindingNode node = facts.binding(binding);
		for(int child : node.children()) indexScalarValueDependencies(dependent,child,visiting);
		if(node.referenceId() >= 0 && node.referenceId() < facts.occurrences().size()) {
			MemoProgramFacts.Occurrence occurrence = facts.occurrence(node.referenceId());
			if(occurrence.hop().getDataType().isScalar())
				for(int position = 0; position < occurrence.hop().getInput().size(); position++)
					indexScalarValueDependencies(dependent,
						facts.inputBinding(occurrence.id(),position),visiting);
		}
	}

	private void drain() {
		while(!ownerWork.isEmpty() || !bindingWork.isEmpty()) {
			while(!bindingWork.isEmpty()) {
				int binding = bindingWork.removeFirst();
				bindingVisits++;
				bindingQueued[binding] = false;
				Shape next = evaluateBinding(binding);
				if(!Objects.equals(bindingShapes[binding], next)) {
					bindingShapes[binding] = next;
					for(int dependent : bindingConsumers.get(binding)) enqueueBinding(dependent);
					for(int owner : bindingOwnerConsumers.get(binding)) enqueueOwner(owner);
				}
			}
			if(!ownerWork.isEmpty()) {
				int owner = ownerWork.removeFirst();
				ownerVisits++;
				ownerQueued[owner] = false;
				Shape next = evaluateOwner(owner);
				if(!Objects.equals(ownerShapes[owner], next)) {
					ownerShapes[owner] = next;
					applyShape(views[owner], next);
					for(int consumer : ownerConsumers.get(owner)) enqueueOwner(consumer);
					for(int binding : ownerBindingConsumers.get(owner)) enqueueBinding(binding);
				}
			}
		}
	}

	private Shape evaluateOwner(int owner) {
		Hop view = views[owner];
		MemoSources.Source source = sources.source(owner);
		if(source != null)
			return exact(view, source.rows(), source.columns(), -1);
		if(view instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			diagnostic(DiagnosticKind.UNRESOLVED, "FEDERATED_SOURCE_SHAPE_MISSING", owner, -1,
				"Federated source has no acquired exact-range shape");
			return unknown(view);
		}
		if(view instanceof DataOp data && data.getOp() == OpOpData.PERSISTENTREAD) {
			Shape metadata = persistentReadShape(data);
			if(metadata != null) return metadata;
		}
		if(isTransientRead(view))
			return transientOwnerShape(owner, view);
		if(view instanceof DataGenOp) {
			Shape dataGen = dataGenOwnerShape(owner);
			if(dataGen != null) return dataGen;
		}
		try {
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1);
				view.setDim2(-1);
				view.setNnz(-1);
			}
			view.refreshSizeInformation();
			Shape refreshed = fromView(view);
			if(refreshed.exact()) return refreshed;
			Shape retained = retainedCompilerShape(owner, view);
			return retained != null ? retained : refreshed;
		}
		catch(RuntimeException ex) {
			diagnostic(DiagnosticKind.REFRESH_FAILURE, "SHAPE_REFRESH_FAILURE", owner, -1,
				ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
			return unknown(view);
		}
	}

	private Shape persistentReadShape(DataOp data) {
		String filename = data.getFileName();
		if((filename == null || filename.isEmpty()) && data.hasParameter(DataExpression.IO_FILENAME)) {
			Hop value = data.getInput(data.getParameterIndex(DataExpression.IO_FILENAME));
			if(value instanceof LiteralOp literal) filename = literal.getStringValue();
		}
		if(filename == null || filename.isEmpty()) return null;
		Optional<Shape> cached = localMetadataShapes.get(filename);
		if(cached != null) return cached.orElse(null);
		String metadataFile = DataExpression.getMTDFileName(filename);
		Optional<Shape> result = Optional.empty();
		if(HDFSTool.existsFileOnHDFS(metadataFile)) {
			MetaDataAll metadata = new MetaDataAll(metadataFile,false,true);
			if(metadata.mtdExists() && metadata.getDim1() > 0 && metadata.getDim2() > 0)
				result = Optional.of(new Shape(data.getDataType(),data.getValueType(),
					metadata.getDim1(),metadata.getDim2(),metadata.getNnz(),Knowledge.EXACT));
		}
		localMetadataShapes.put(filename,result);
		return result.orElse(null);
	}

	/**
	 * Retains an exact value-dependent compiler shape only when the source-driven
	 * axis can still be proved against the refreshed target. REXPAND's other axis
	 * comes from a scalar value that ordinary matrix-shape propagation cannot
	 * reconstruct across transient/function bindings.
	 */
	private Shape retainedCompilerShape(int owner, Hop view) {
		Shape initial = initialShapes[owner];
		if(!(view instanceof ParameterizedBuiltinOp parameterized)
			|| parameterized.getOp() != ParamBuiltinOp.REXPAND)
			return null;
		Hop target = parameterized.getTargetHop();
		Hop direction = parameterized.getParameterHop("dir");
		if(target == null || target.getDim1() <= 0 || !(direction instanceof LiteralOp literal))
			return null;
		String value = literal.getStringValue();
		Long maximum = scalarLong(parameterized.getParameterHop("max"), new LinkedHashSet<>());
		if(maximum != null && maximum > 0) {
			long rows = "rows".equals(value) ? maximum : target.getDim1();
			long columns = "cols".equals(value) ? maximum : target.getDim1();
			if(("rows".equals(value) || "cols".equals(value)) && rows > 0 && columns > 0)
				return exact(view, rows, columns, -1);
		}
		if(!initial.exact()) return null;
		if("cols".equals(value) && initial.rows() == target.getDim1()
			|| "rows".equals(value) && initial.columns() == target.getDim1())
			return initial;
		return null;
	}

	private Long scalarLong(Hop hop, Set<Integer> visitingBindings) {
		if(hop instanceof LiteralOp literal) {
			try { return literal.getLongValue(); }
			catch(RuntimeException ignored) { return null; }
		}
		Integer owner = viewOwners.get(hop);
		if(owner == null) return null;
		Long value = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding < 0) continue;
			Long current = scalarLong(binding, visitingBindings);
			if(current == null || value != null && !value.equals(current)) return null;
			value = current;
		}
		return value;
	}

	private Long scalarLong(int binding, Set<Integer> visiting) {
		if(binding < 0 || !visiting.add(binding)) return null;
		try {
			MemoProgramFacts.BindingNode node = facts.binding(binding);
			List<Integer> children = node.kind() == BindingKind.FORMAL
				? formalArguments.get(binding) : node.children();
			Long value = null;
			for(int child : children) {
				Long current = scalarLong(child, visiting);
				if(current == null || value != null && !value.equals(current)) return null;
				value = current;
			}
			if(value != null) return value;
			if(node.referenceId() >= 0 && node.referenceId() < facts.occurrences().size()) {
				MemoProgramFacts.Occurrence occurrence = facts.occurrence(node.referenceId());
				Hop referenced = occurrence.hop();
				if(referenced instanceof LiteralOp literal) {
					try { return literal.getLongValue(); }
					catch(RuntimeException ignored) { return null; }
				}
				if(referenced instanceof UnaryOp unary
					&& (unary.getOp() == OpOp1.NROW || unary.getOp() == OpOp1.NCOL)) {
					int input = facts.inputBinding(occurrence.id(),0);
					Shape inputShape = input >= 0 ? bindingShapes[input] : null;
					if(inputShape != null) {
						long dimension = unary.getOp() == OpOp1.NROW
							? inputShape.rows() : inputShape.columns();
						return dimension > 0 ? dimension : null;
					}
				}
			}
			return null;
		}
		finally { visiting.remove(binding); }
	}

	private Long scalarLong(int binding,EvaluationContext context,Set<PhaseVisit> active) {
		if(binding<0) return null;
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.SELECT) {
				Boolean selected=context.guards().get(facts.guardDecision(node.referenceId(),context.callFrame()));
				if(selected!=null) return scalarLong(node.children().get(
					facts.branchGuardValue(node.referenceId(),context.callFrame(),selected) ? 0 : 1),context,active);
				Long left=scalarLong(node.children().get(0),context,active);
				Long right=scalarLong(node.children().get(1),context,active);
				return Objects.equals(left,right) ? left : null;
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return scalarLong(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),active);
				}
				return scalarLong(binding,new LinkedHashSet<>());
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				if(node.children().size()>1) return scalarLong(node.children().get(1),context,active);
				if(node.children().isEmpty()) return null;
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return scalarLong(node.children().get(0),context.withCallFrame(frame),active);
			}
			if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty()
				|| node.kind()==BindingKind.CALL_ARG)
				return scalarLong(node.children().get(0),context,active);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(hop instanceof LiteralOp literal && hop.getValueType()==ValueType.INT64)
					return literal.getLongValue();
				if(hop instanceof UnaryOp unary && (unary.getOp()==OpOp1.NROW || unary.getOp()==OpOp1.NCOL)) {
					Shape input=bindingShape(facts.inputBinding(node.referenceId(),0),context);
					long value=unary.getOp()==OpOp1.NROW ? input.rows() : input.columns();
					return value>0 ? value : null;
				}
			}
			return scalarLong(binding,new LinkedHashSet<>());
		}
		finally { active.remove(key); }
	}

	private Double scalarDouble(int binding,EvaluationContext context,Set<PhaseVisit> active) {
		if(binding<0) return null;
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.SELECT) {
				Boolean selected=context.guards().get(facts.guardDecision(node.referenceId(),context.callFrame()));
				if(selected!=null) return scalarDouble(node.children().get(
					facts.branchGuardValue(node.referenceId(),context.callFrame(),selected) ? 0 : 1),context,active);
				Double left=scalarDouble(node.children().get(0),context,active);
				Double right=scalarDouble(node.children().get(1),context,active);
				return Objects.equals(left,right) ? left : null;
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return scalarDouble(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),active);
				}
				return null;
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				if(node.children().size()>1) return scalarDouble(node.children().get(1),context,active);
				if(node.children().isEmpty()) return null;
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return scalarDouble(node.children().get(0),context.withCallFrame(frame),active);
			}
			if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty()
				|| node.kind()==BindingKind.CALL_ARG)
				return scalarDouble(node.children().get(0),context,active);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(hop instanceof LiteralOp literal) return literal.getDoubleValue();
				if(hop instanceof UnaryOp unary && (unary.getOp()==OpOp1.NROW || unary.getOp()==OpOp1.NCOL)) {
					Shape input=bindingShape(facts.inputBinding(node.referenceId(),0),context);
					long value=unary.getOp()==OpOp1.NROW ? input.rows() : input.columns();
					return value>0 ? (double)value : null;
				}
			}
			return null;
		}
		finally { active.remove(key); }
	}

	private Shape transientOwnerShape(int owner, Hop view) {
		Shape result = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding < 0)
				return unknown(view);
			Shape current = bindingShapes[binding];
			if(current == null)
				return null;
			if(result == null)
				result = current;
			else if(result.exact() && current.exact() && !sameDimensions(result, current)) {
				diagnostic(DiagnosticKind.MISMATCH, "SHARED_OCCURRENCE_SHAPE_MISMATCH", owner, binding,
					result + " vs " + current);
				result = commonShape(result, current);
			}
			else if(sameDimensions(result, current))
				result = mergeNonZeros(result, current);
			else
				result = commonShape(result, current);
		}
		return result == null ? unknown(view) : result;
	}

	private Shape evaluateBinding(int id) {
		// Mismatch is an absorbing result. Re-evaluating a cyclic binding after its
		// consumers lose shape information must not resurrect one incompatible arm
		// and oscillate the worklist indefinitely.
		if(bindingConflicts[id] && bindingShapes[id] != null)
			return bindingShapes[id];
		MemoProgramFacts.BindingNode node = facts.binding(id);
		return switch(node.kind()) {
			case MISSING, UNBOUND, CALL_COMPLETION -> unknown((Hop)null);
			case DEFINITION -> node.children().isEmpty() ? definitionOwnerShape(node) : child(node, 0);
			case SELECT -> unify(node, node.children(), "BRANCH_SHAPE_MISMATCH");
			case LOOP_HEAD -> loopHead(node);
			case LAST, CALL_ARG -> child(node, 0);
			case FORMAL -> unify(node, formalArguments.get(id), "FUNCTION_ACTUAL_SHAPE_MISMATCH");
			case CALL_RETURN -> callReturn(node);
		};
	}

	private Shape definitionOwnerShape(MemoProgramFacts.BindingNode node) {
		if(node.referenceId() < 0 || node.referenceId() >= facts.occurrences().size())
			return unknown((Hop)null);
		Shape slot=multiReturnOutputShape(node.referenceId(),node.position());
		if(slot!=null) return slot;
		return occurrenceOutputShape(facts.occurrence(node.referenceId()));
	}

	private Shape loopHead(MemoProgramFacts.BindingNode node) {
		Shape entry = child(node, 0);
		Shape backedge = child(node, 1);
		if(entry == null || !entry.exact())
			return entry;
		if(backedge == null)
			return entry; // grounded induction seed; backedge must later prove preservation
		if(!backedge.exact())
			return unknown(entry);
		if(sameDimensions(entry, backedge))
			return mergeNonZeros(entry, backedge);
		diagnostic(DiagnosticKind.MISMATCH, "LOOP_CARRIED_SHAPE_MISMATCH", -1, node.id(),
			entry + " vs " + backedge);
		return unknown(entry);
	}

	private Shape callReturn(MemoProgramFacts.BindingNode node) {
		MemoProgramFacts.CallBinding call = node.referenceId() >= 0
			&& node.referenceId() < facts.calls().size() ? facts.call(node.referenceId()) : null;
		if(call != null && call.callOccurrenceId() < 0 && node.children().size() > 1)
			return child(node, node.children().size() - 1); // concrete inlined clone
		return unify(node, node.children(), "FUNCTION_RETURN_SHAPE_MISMATCH");
	}

	private Shape unify(MemoProgramFacts.BindingNode node, List<Integer> children, String code) {
		if(children.isEmpty()) return unknown((Hop)null);
		Shape result = null;
		for(int child : children) {
			Shape value = child >= 0 ? bindingShapes[child] : null;
			if(value == null) return null;
			if(result == null) result = value;
			else if(result.exact() && value.exact() && !sameDimensions(result, value)) {
				diagnostic(DiagnosticKind.MISMATCH, code, -1, node.id(), result + " vs " + value);
				result = commonShape(result, value);
			}
			else if(sameDimensions(result, value)) result = mergeNonZeros(result, value);
			else result = commonShape(result, value);
		}
		return result;
	}

	private Shape child(MemoProgramFacts.BindingNode node, int position) {
		return position >= 0 && position < node.children().size()
			? bindingShapes[node.children().get(position)] : unknown((Hop)null);
	}

	private void finalizeUnresolved() {
		for(int binding = 0; binding < bindingShapes.length; binding++)
			if(bindingShapes[binding] == null) {
				bindingShapes[binding] = unknown((Hop)null);
				diagnostic(DiagnosticKind.UNRESOLVED, "UNRESOLVED_BINDING_SHAPE", -1, binding,
					facts.binding(binding).label());
				for(int dependent : bindingConsumers.get(binding)) enqueueBinding(dependent);
				for(int owner : bindingOwnerConsumers.get(binding)) enqueueOwner(owner);
			}
		for(int owner = 0; owner < ownerShapes.length; owner++)
			if(ownerShapes[owner] == null) {
				ownerShapes[owner] = unknown(views[owner]);
				applyShape(views[owner], ownerShapes[owner]);
				diagnostic(DiagnosticKind.UNRESOLVED, "UNRESOLVED_OWNER_SHAPE", owner, -1,
					views[owner].getName());
				for(int consumer : ownerConsumers.get(owner)) enqueueOwner(consumer);
				for(int binding : ownerBindingConsumers.get(owner)) enqueueBinding(binding);
			}
	}

	private Shape inputShape(Hop hop, int position) {
		if(position < 0 || position >= hop.getInput().size()) return null;
		Hop input = hop.getInput(position);
		return fromView(input);
	}

	private void enqueueOwner(int owner) {
		if(owner >= 0 && owner < ownerQueued.length && !ownerQueued[owner]) {
			ownerQueued[owner] = true;
			ownerWork.addLast(owner);
		}
	}

	private void enqueueBinding(int binding) {
		if(binding >= 0 && binding < bindingQueued.length && !bindingQueued[binding]) {
			bindingQueued[binding] = true;
			bindingWork.addLast(binding);
		}
	}

	private void diagnostic(DiagnosticKind kind, String code, int owner, int binding, String message) {
		if(kind == DiagnosticKind.MISMATCH && binding >= 0 && binding < bindingConflicts.length)
			bindingConflicts[binding] = true;
		String key = kind + "|" + code + '|' + owner + '|' + binding;
		if(diagnosticKeys.add(key)) diagnostics.add(new Diagnostic(kind, code, owner, binding, message));
	}

	// All later occurrence views clone this private snapshot, never live compiler
	// HOPs whose memory/placement annotations change during lowering/recompilation.
	private static Hop[] cloneViews(MemoProgramFacts facts) {
		List<Hop> roots = new ArrayList<>(facts.compiledOwnerCount());
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++)
			roots.add(facts.compiledOwnerHop(owner));
		Map<Long,Hop> clones = new HashMap<>();
		Recompiler.deepCopyHopsDag(roots, clones);
		// A shape/native-rule view describes operator semantics, not a choice made by
		// an earlier planner invocation. Deep copies intentionally retain physical
		// annotations for recompilation, so remove them from these private views.
		for(Hop clone : clones.values()) clearPlannerState(clone);
		Hop[] result = new Hop[roots.size()];
		for(int owner = 0; owner < roots.size(); owner++) {
			result[owner] = clones.get(roots.get(owner).getHopID());
			if(result[owner] == null)
				throw new IllegalStateException("No private shape view for owner " + owner);
		}
		return result;
	}

	private static Hop cloneView(Hop root) {
		return cloneView(root,COST_VIEW_DESCENT_DEPTH,new IdentityHashMap<>(),new IdentityHashMap<>());
	}

	private static Hop cloneView(Hop root,int depth,Map<Hop,Hop> clones,Map<Hop,Integer> depths) {
		Hop result=clones.get(root);
		if(result!=null && depths.get(root)>=depth) return result;
		try {
			if(result==null) {
				result=(Hop)root.clone();
				clearPlannerState(result);
				clones.put(root,result);
			}
			depths.put(root,depth);
			// A shared node first reached by a longer path must be expanded when
			// it is subsequently reached closer to the root. Identity-only DFS
			// caching could otherwise leave a direct input with no operands.
			if(depth>0) {
				boolean attach=result.getInput().isEmpty();
				for(Hop input:root.getInput()) {
					Hop child=cloneView(input,depth-1,clones,depths);
					if(attach) result.addInput(child);
				}
			}
			if(result instanceof FunctionOp function && root instanceof FunctionOp source
				&& source.getOutputs()!=null) {
				ArrayList<Hop> outputs=new ArrayList<>(source.getOutputs().size());
				for(Hop output:source.getOutputs())
					outputs.add(output==null ? null : cloneView(output,Math.max(0,depth-1),clones,depths));
				function.setOutputs(outputs);
			}
			return result;
		}
		catch(CloneNotSupportedException exception) {
			throw new IllegalStateException("No private occurrence view for "+root.getHopID(),exception);
		}
	}

	private static void clearPlannerState(Hop clone) {
		clone.setForcedExecType(null);
		clone.resetExecType();
		clone.setFederatedOutput(FederatedOutput.NONE);
		clone.setFederatedOutputDerived(false);
		clone.deactivatePrefetch();
		clone.setPlannerPlacementSelected(false);
		clone.setLops(null);
	}

	private static Shape fromView(Hop hop) {
		if(hop == null) return unknown((Hop)null);
		boolean matrix = isMatrixLike(hop);
		boolean exact = !matrix || hop.getDim1() > 0 && hop.getDim2() > 0;
		return new Shape(hop.getDataType(), hop.getValueType(), matrix ? hop.getDim1() : -1,
			matrix ? hop.getDim2() : -1,
			hop.getNnz(), exact ? Knowledge.EXACT : Knowledge.UNKNOWN);
	}

	private static Shape exact(Hop hop, long rows, long columns, long nonZeros) {
		return new Shape(hop.getDataType(), hop.getValueType(), rows, columns, nonZeros, Knowledge.EXACT);
	}

	private static Shape unknown(Hop hop) {
		return hop == null
			? new Shape(DataType.UNKNOWN, ValueType.UNKNOWN, -1, -1, -1, Knowledge.UNKNOWN)
			: new Shape(hop.getDataType(), hop.getValueType(), -1, -1, -1, Knowledge.UNKNOWN);
	}

	private static Shape unknown(Shape basis) {
		return basis == null ? unknown((Hop)null)
			: new Shape(basis.dataType(), basis.valueType(), -1, -1, -1, Knowledge.UNKNOWN);
	}

	private static boolean sameDimensions(Shape left, Shape right) {
		if(left == null || right == null || !left.exact() || !right.exact()
			|| left.dataType() != right.dataType() || left.valueType() != right.valueType())
			return false;
		return left.dataType() != DataType.MATRIX && left.dataType() != DataType.FRAME
			|| left.rows() == right.rows() && left.columns() == right.columns();
	}

	private static Shape mergeNonZeros(Shape left, Shape right) {
		return new Shape(left.dataType(), left.valueType(), left.rows(), left.columns(),
			left.nonZeros() == right.nonZeros() ? left.nonZeros() : -1, Knowledge.EXACT);
	}

	/** Returns only dimensions independently proved equal on every incoming path. */
	private static Shape commonShape(Shape left, Shape right) {
		if(left == null || right == null || left.dataType() != right.dataType()
			|| left.valueType() != right.valueType())
			return unknown((Hop)null);
		long rows = left.rows() > 0 && left.rows() == right.rows() ? left.rows() : -1;
		long columns = left.columns() > 0 && left.columns() == right.columns() ? left.columns() : -1;
		long nonZeros = left.nonZeros() >= 0 && left.nonZeros() == right.nonZeros()
			? left.nonZeros() : -1;
		boolean exact = left.dataType() != DataType.UNKNOWN && left.valueType() != ValueType.UNKNOWN
			&& (left.dataType() != DataType.MATRIX && left.dataType() != DataType.FRAME
				|| rows > 0 && columns > 0);
		return new Shape(left.dataType(), left.valueType(), rows, columns, nonZeros,
			exact ? Knowledge.EXACT : Knowledge.UNKNOWN);
	}

	private static void applyShape(Hop hop, Shape shape) {
		if(hop == null || shape == null || !isMatrixLike(hop)) return;
		hop.setDim1(shape.rows());
		hop.setDim2(shape.columns());
		hop.setNnz(shape.nonZeros());
		// A cloned Hop may retain a different occurrence's cached memory estimate.
		// Cost reads those caches as well as NNZ; refresh from these frozen stats.
		if(shape.rows()>0 && shape.columns()>0)
			hop.computeMemEstimate(new org.apache.sysds.hops.MemoTable());
	}

	private static boolean isMatrixLike(Hop hop) {
		return hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME;
	}

	private static boolean isTransientRead(Hop hop) {
		return hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD;
	}

	private static long dimension(Shape shape, boolean rows) {
		return shape != null ? rows ? shape.rows() : shape.columns() : -1;
	}

	private static <T> List<List<T>> lists(int size) {
		List<List<T>> result = new ArrayList<>(size);
		for(int i = 0; i < size; i++) result.add(new ArrayList<>());
		return result;
	}

	private static void add(List<Integer> values, int value) {
		values.add(value);
	}
}

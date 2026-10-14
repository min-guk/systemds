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

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopExecutionCase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.PhaseLoop;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.Context;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.LoopSlot;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.RegionKind;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;

/**
 * Native owners and exact reaching uses, with small loop-boundary domains.
 * Conditions subscribe only to their actual binding/owner dependencies.
 */
public final class MemoProgramModel {
	/**
	 * Frozen cost-estimation profile. An unproved {@link GuardDecision} has one symbolic outcome per
	 * call frame, shared across repeated loop evaluations; different guard ids are independent.
	 * Phase proofs override that estimate. This is not a reachability fact and never prunes a plan.
	 */
	public record Profile(double unknownLoopIterations,double branchThenProbability) {
		public Profile {
			if(!Double.isFinite(unknownLoopIterations) || unknownLoopIterations <= 1
				|| !Double.isFinite(branchThenProbability) || branchThenProbability <= 0 || branchThenProbability >= 1)
				throw new IllegalArgumentException("Profile needs T>1 and 0<p<1; it is not a reachability oracle");
		}
		public static Profile defaults() { return new Profile(10,0.5); }
	}
	public record Head(int variable,LoopSlot slot,MemoProgramFacts.CarriedValue value) { }
	public record CostBreakdown(BigDecimal nativeExecution,BigDecimal movement) {
		public BigDecimal total() { return nativeExecution.add(movement); }
	}
	public record Solution(MemoOptimizer.Result optimization,
		Map<Long,List<MemoSupplyRegistry.Supply>> supplies,List<String> diagnostics) {
		public Solution { supplies = Map.copyOf(supplies); diagnostics = List.copyOf(diagnostics); }
	}
	private record Use(Occurrence consumer,List<Integer> bindings,List<List<Integer>> frames) { }
	private record PrivacyDemand(int occurrence,List<Integer> localInputs,boolean localOutput) { }
	private final Map<PrivacyDemand,Truth> privacyLegality=new LinkedHashMap<>();
	private record MovementEdge(int use,int position) { }
	private record MovementGroup(MemoBindingResolver.OriginIdentity identity,MovementEdge isolated) { }
	private record RetentionScope(MemoPlanRegistry.ScopeKind kind,long statementBlockId) { }
	private record SupplyEdge(long statementBlockId,long producerHopId,long consumerHopId,int inputPosition) { }
	private record ScheduledDemand(RetentionScope scope,MemoBindingResolver.OriginIdentity identity,
		SupplyEdge edge,MemoSupplyRegistry.Recipe recipe,MemoPartition target) { }
	private static final int MOVEMENT_FACTOR_WIDTH_DIAGNOSTIC = 8;
	private static final int MOVEMENT_GUARD_STATE_LIMIT = 100_000;
	private record EntryUse(LoopSlot head,List<Integer> frame) {
		private EntryUse { frame=List.copyOf(frame); }
	}
	private record LoopBoundary(boolean entry,IterationPhase phase) { }
	private final Map<EntryUse,Boolean> entryUses=new LinkedHashMap<>();
	private final MemoProgramFacts facts;
	private final MemoSources sources;
	private final MemoRuleBuilder.Result universe;
	private final RuleMemo memo;
	private final MemoShapes shapes;
	private final MemoBindingResolver resolver;
	private final MemoPrivacy privacy;
	private final Profile profile;
	private final List<Head> heads = new ArrayList<>();
	private final Map<LoopSlot,Head> headsBySlot = new LinkedHashMap<>();
	private final List<Check> checks = new ArrayList<>();
	private final List<CostTerm> costs = new ArrayList<>();
	private final int nativeCostTermCount;
	private final List<Use> uses = new ArrayList<>();
	private final List<String> diagnostics = new ArrayList<>();
	private final Map<Integer,List<Occurrence>> occurrencesByOwner = new LinkedHashMap<>();
	private final int[] sizes;
	private final boolean[] coverage;
	private final double[][] nativeCosts;

	public MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile) {
		this.facts = facts; this.sources = sources; this.universe = universe; this.memo = universe.memo();
		this.shapes = universe.shapes();
		this.profile = profile; privacy = universe.privacy(); resolver = privacy.resolverFor(facts,sources);
		shapes.requireRequest(facts,sources);
		facts.requireComplete(); diagnostics.addAll(universe.diagnostics());
		for(Occurrence occurrence : facts.occurrences()) {
			occurrencesByOwner.computeIfAbsent(occurrence.compiledOwnerId(),ignored -> new ArrayList<>()).add(occurrence);
		}
		for(var loop : facts.loops()) {
			if(framesFor(loop.regionId()).isEmpty()) continue;
			if(loop.executionCases().stream().allMatch(value -> value.cardinality()==LoopCardinality.ZERO)) continue;
			for(var value : loop.carriedValues()) {
			Head head = new Head(facts.compiledOwnerCount()+heads.size(),new LoopSlot(loop.id(),value.slot()),value);
			heads.add(head); headsBySlot.put(head.slot(),head);
			}
		}
		sizes = new int[facts.compiledOwnerCount()+heads.size()]; coverage = new boolean[sizes.length];
		nativeCosts = new double[sizes.length][];
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			List<Occurrence> occurrences = occurrencesByOwner.get(owner);
			Hop hop = facts.compiledOwnerHop(owner);
			List<List<Integer>> ownerContexts = new ArrayList<>();
			for(Occurrence occurrence : occurrences) {
				List<List<Integer>> contexts = framesForOccurrence(occurrence);
				ownerContexts.addAll(contexts);
			}
			boolean nativeOwner = !MemoRuleBuilder.transparent(hop) && !ownerContexts.isEmpty();
			sizes[owner] = nativeOwner ? memo.rules(owner).size() : 1;
			coverage[owner] = !nativeOwner || memo.complete(owner);
			nativeCosts[owner] = new double[sizes[owner]];
			if(!nativeOwner) continue;
			boolean firstNativeTerm = true;
			for(Occurrence occurrence : occurrences) {
				List<List<Integer>> contexts = framesForOccurrence(occurrence);
				if(contexts.isEmpty()) continue;
				for(List<Integer> frame : contexts) {
					for(double[] values : nativeCostTerms(occurrence,frame)) {
						// Each reachable phase/context is one frozen binary64 primitive.
						if(firstNativeTerm) { nativeCosts[owner] = values; firstNativeTerm = false; }
						else costs.add(new CostTerm(new int[] {owner},choices -> values[choices[0]],0));
					}
				}
				List<Integer> bindings = new ArrayList<>();
				for(int i = 0; i < hop.getInput().size(); i++) bindings.add(facts.inputBinding(occurrence.id(),i));
				Use use = new Use(occurrence,List.copyOf(bindings),contexts); uses.add(use);
				// Native pattern/shape is already frozen. Privacy transfer observes semantic
				// values, not producer placement choices, so it needs only this rule choice.
				checks.add(new Check(occurrence.id(),new int[] {owner},choices ->
					privacyCompatible(use,expand(new int[] {use.consumer().compiledOwnerId()},choices))));
				for(int position=0; position<bindings.size(); position++) {
					final int input=position;
					int[] scope=scope(List.of(bindings.get(position)),List.of(owner),contexts);
					checks.add(new Check(occurrence.id(),scope,choices ->
						inputCompatible(use,input,expand(scope,choices))));
				}
			}
		}
		for(Head head : heads) {
			// Scalar iterator/control contracts never introduce a FED scalar state.
			Set<Integer> origins = new LinkedHashSet<>();
			for(List<Integer> frame : framesFor(facts.loop(head.slot().loopId()).regionId()))
				origins.addAll(resolver.directSupplyOwners(head.value().entryBindingId(),frame));
			boolean cacheable = origins.stream().anyMatch(owner -> facts.compiledOwnerHop(owner).getDataType().isMatrix()
				|| facts.compiledOwnerHop(owner).getDataType().isFrame());
			// A nested loop may enter directly from an already-bound outer head,
			// which intentionally has no compiled immediate owner.
			DataType entryType=shapes.bindingShape(head.value().entryBindingId()).dataType();
			cacheable |= entryType.isMatrix() || entryType.isFrame();
			sizes[head.variable()] = cacheable && sources.workers() > 0 ? sources.workers() == 1 ? 2 : 4 : 1;
			coverage[head.variable()] = true; nativeCosts[head.variable()] = new double[sizes[head.variable()]];
			int[] scope = scope(List.of(head.value().entryBindingId(),head.value().backedgeBindingId()),List.of(head.variable()),
				framesFor(facts.loop(head.slot().loopId()).regionId()));
			checks.add(new Check(-1-head.variable(),scope,choices -> boundaryCompatible(head,expand(scope,choices))));
		}
		nativeCostTermCount=costs.size();
		registerMovementCosts();
	}
	public RuleMemo memo() { return memo; }
	public List<Head> heads() { return List.copyOf(heads); }
	public List<Check> checks() { return List.copyOf(checks); }
	public Solution optimize(MemoOptimizer.Limits limits) {
		MemoOptimizer.Result result = new MemoOptimizer(sizes,coverage,nativeCosts,checks,costs,limits).optimize();
		if(result.status()==MemoOptimizer.Status.OPTIMAL
			&& evaluateCost(result.selectedRules()).total().compareTo(result.cost())!=0)
			throw new IllegalStateException("Selected Memo cost differs from the original cost ownership model");
		Map<Long,List<MemoSupplyRegistry.Supply>> supplies = result.status() == MemoOptimizer.Status.OPTIMAL
			? reconstructSupplies(result.selectedRules()) : Map.of();
		return new Solution(result,supplies,diagnostics);
	}

	/** Re-evaluate one complete selection from the original, ungrouped cost primitives. */
	public CostBreakdown evaluateCost(int[] selected) {
		if(selected==null || selected.length!=sizes.length)
			throw new IllegalArgumentException("Cost evaluation requires one complete Memo selection");
		BigDecimal nativeTotal=BigDecimal.ZERO;
		BigDecimal movementTotal=BigDecimal.ZERO;
		for(int owner=0;owner<sizes.length;owner++) {
			if(selected[owner]<0 || selected[owner]>=sizes[owner])
				throw new IllegalArgumentException("Invalid selected rule for owner "+owner);
			nativeTotal=nativeTotal.add(new BigDecimal(nativeCosts[owner][selected[owner]]));
		}
		for(int i=0;i<costs.size();i++) {
			CostTerm term=costs.get(i);
			int[] tuple=java.util.Arrays.stream(term.owners()).map(owner -> selected[owner]).toArray();
			BigDecimal value=term.evaluateExact(tuple);
			if(i<nativeCostTermCount) nativeTotal=nativeTotal.add(value);
			else movementTotal=movementTotal.add(value);
		}
		return new CostBreakdown(nativeTotal,movementTotal);
	}

	private Truth compatible(Use use,int[] selected) {
		Truth result=privacyCompatible(use,selected);
		if(result==Truth.NO) return result;
		for(int position=0; position<use.bindings().size(); position++) {
			Truth edge=inputCompatible(use,position,selected);
			if(edge==Truth.NO) return edge;
			if(edge==Truth.UNKNOWN) result=Truth.UNKNOWN;
		}
		return result;
	}

	/** Correlated privacy environments stay in Facts, without producer-choice products. */
	private Truth privacyCompatible(Use use,int[] selected) {
		int owner=use.consumer().compiledOwnerId(), choice=selected[owner];
		if(choice<0) return Truth.UNKNOWN;
		RuleMemo.Rule rule=memo.rule(owner,choice);
		List<Integer> localInputs=new ArrayList<>();
		for(int position=0; position<rule.inputs().size(); position++)
			if(rule.inputs().get(position)==MemoPartition.LOCAL) localInputs.add(position);
		PrivacyDemand demand=new PrivacyDemand(use.consumer().id(),List.copyOf(localInputs),
			rule.exec()==org.apache.sysds.common.Types.ExecType.CP || rule.outputs().contains(MemoPartition.LOCAL));
		Truth known=privacyLegality.get(demand);
		if(known!=null) return known;
		Truth result=privacyCompatible(use,rule);
		privacyLegality.put(demand,result);
		return result;
	}

	private Truth privacyCompatible(Use use,RuleMemo.Rule rule) {
		int owner=use.consumer().compiledOwnerId();
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		Set<Integer> privacyBindings=new LinkedHashSet<>();
		for(int i=0; i<rule.inputs().size(); i++)
			if(rule.inputs().get(i)==MemoPartition.LOCAL && !privacy.provesReleasedBinding(use.bindings().get(i)))
				privacyBindings.add(use.bindings().get(i));
		boolean outputNeedsProof=(rule.exec()==org.apache.sysds.common.Types.ExecType.CP || rule.outputs().contains(MemoPartition.LOCAL))
			&& !privacy.provesReleasedOccurrence(use.consumer().id());
		if(outputNeedsProof) privacyBindings.addAll(use.bindings());
		if(privacyBindings.isEmpty() && !outputNeedsProof) return Truth.YES;
		final List<Integer> relevantPrivacy=List.copyOf(privacyBindings);
		Truth[] result={Truth.YES};
		for(List<Integer> frame : use.frames()) {
			Context context=privacyContext(context(selected,use.consumer().regionId(),frame),relevantPrivacy);
			// Activation and privacy need no exact producer-origin product. Every
			// input edge has its own universal inputCompatible availability check
			// under the same guards; here we derive only correlated payload privacy.
			var visit=visitActive(use.consumer(),List.of(),context,environment -> {
				Context exactEnvironment=context.withEnvironment(environment).withPayloadOnly();
				var conditional=privacy.visitConditionalInputs(relevantPrivacy,exactEnvironment,exact -> {
					var legal=privacy.unconditionalRuleLegalityAt(use.consumer().id(),rule,
						universe.inputRoles().get(owner),exact);
					if(legal.legality()==MemoPrivacy.Legality.ILLEGAL) { result[0]=Truth.NO; return false; }
					if(legal.legality()==MemoPrivacy.Legality.DEFER) result[0]=Truth.UNKNOWN;
					return true;
				});
				if(result[0]==Truth.NO) return false;
				if(conditional.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
				return true;
			});
			if(result[0]==Truth.NO) return Truth.NO;
			if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
		}
		return result[0];
	}

	/** Universal conjunction distributes over exact guarded input projections. */
	private Truth inputCompatible(Use use,int position,int[] selected) {
		int owner=use.consumer().compiledOwnerId(), choice=selected[owner];
		if(choice<0) return Truth.UNKNOWN;
		RuleMemo.Rule rule=memo.rule(owner,choice);
		int binding=use.bindings().get(position);
		Truth[] result={Truth.YES};
		for(List<Integer> frame : use.frames()) {
			Context context=context(selected,use.consumer().regionId(),frame);
			var visit=visitActive(use.consumer(),List.of(binding),context,environment -> {
				ResolvedOrigin origin=environment.origins().get(0);
				MemoPartition layout=layout(origin,selected);
				Truth compatible;
				if(universe.inputRoles().get(owner).get(position)==InputRole.METADATA)
					compatible=layout==null ? Truth.UNKNOWN : layout==rule.inputs().get(position) ? Truth.YES : Truth.NO;
				else if(layout==null)
					compatible=Truth.UNKNOWN;
				else if(MemoMovement.kind(layout,rule.inputs().get(position),false)!=MemoMovement.Kind.COLLECT) {
					// DIRECT/IMPOSSIBLE do not depend on payload privacy; uploads only need
					// canonical shape/type/pool legality. Native execution privacy is a separate check.
					Hop source=use.consumer().hop().getInput(position);
					var shape=MemoMovement.kind(layout,rule.inputs().get(position),false)==MemoMovement.Kind.UPLOAD
						? shapes.inputShape(use.consumer().id(),position,context.withEnvironment(environment).evaluation()) : null;
					compatible=MemoMovement.legal(layout,rule.inputs().get(position),source.getDataType(),null,
						shape==null ? -1 : shape.rows(),shape==null ? -1 : shape.columns(),sources.workers(),false);
				}
				else {
					// COLLECT's privacy condition is exactly the consumer's LOCAL-input
					// privacy condition. The unary privacyCompatible check owns it once,
					// independently of all producer rule choices; do not repeat it per edge tuple.
					DataType type=use.consumer().hop().getInput(position).getDataType();
					compatible=type==DataType.MATRIX || type==DataType.FRAME ? Truth.YES : Truth.NO;
				}
				if(compatible==Truth.NO) { result[0]=Truth.NO; return false; }
				if(compatible==Truth.UNKNOWN) result[0]=Truth.UNKNOWN;
				return true;
			});
			if(result[0]==Truth.NO) return Truth.NO;
			if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
		}
		return result[0];
	}

	private Context withPhase(Context context,int loop,List<Integer> frame,IterationPhase phase) {
		EvaluationContext e=context.evaluation();
		Map<LoopDecision,IterationPhase> phases=new LinkedHashMap<>(e.iterationPhases());
		phases.put(new LoopDecision(loop,frame),phase);
		return context.withEvaluation(new EvaluationContext(e.guards(),e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),phases,e.cardinalities()));
	}
	private Context withCardinality(Context context,int loop,List<Integer> frame,LoopCardinality cardinality) {
		EvaluationContext e=context.evaluation();
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities=new LinkedHashMap<>(e.cardinalities());
		cardinalities.put(new LoopCardinalityDecision(loop,frame),cardinality);
		return context.withEvaluation(new EvaluationContext(e.guards(),e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),e.iterationPhases(),cardinalities));
	}
	private Context withLoopCase(Context context,int loop,List<Integer> frame,LoopCardinality cardinality,
		IterationPhase phase) {
		Context selected=withCardinality(context,loop,frame,cardinality);
		return phase==null ? selected : withPhase(selected,loop,frame,phase);
	}
	/** A overwritten initializer is not a source for a later value version. */
	private boolean entryRequired(Head head,List<Integer> frame) {
		EntryUse key=new EntryUse(head.slot(),frame);
		Boolean known=entryUses.get(key); if(known!=null) return known;
		int[] none=new int[sizes.length]; Arrays.fill(none,-1);
		var loop=facts.loop(head.slot().loopId());
		LoopCardinality cardinality=firstBodyCase(loop.id()).cardinality();
		Context first=withLoopCase(context(none,loop.regionId(),frame),loop.id(),frame,cardinality,
			IterationPhase.FIRST);
		boolean needed=resolver.directLoopHeads(List.of(head.value().backedgeBindingId()),first.evaluation()).contains(head.slot());
		for(Use use : uses) {
			if(needed) break;
			for(List<Integer> useFrame : use.frames()) {
				if(!insideLoop(use.consumer().regionId(),useFrame,loop.id(),frame)) continue;
				Context useContext=withLoopCase(context(none,use.consumer().regionId(),useFrame),loop.id(),frame,
					cardinality,IterationPhase.FIRST);
				if(!resolver.directLoopHeads(use.bindings(),useContext.evaluation()).contains(head.slot())) continue;
				boolean[] reached={false};
				var visit=visitActive(use.consumer(),use.bindings(),useContext,environment -> {
					for(ResolvedOrigin origin : environment.origins())
						if(origin.kind()==OriginKind.LOOP_HEAD_CONTRACT && origin.referenceId()==loop.id()
							&& origin.boundarySlot()==head.slot().slot() && origin.callFrame().equals(frame)) {
							reached[0]=true; return false;
						}
					return true;
				});
				// Incomplete liveness cannot justify deleting an entry obligation.
				needed |= reached[0] || visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED;
				if(needed) break;
			}
		}
		entryUses.put(key,needed); return needed;
	}
	private boolean insideLoop(int region,List<Integer> frame,int loop,List<Integer> loopFrame) {
		if(frame.size()<loopFrame.size() || !frame.subList(0,loopFrame.size()).equals(loopFrame)) return false;
		int at=frame.size()==loopFrame.size() ? region
			: facts.occurrence(facts.call(frame.get(loopFrame.size())).callOccurrenceId()).regionId();
		for(int r=at; r>=0; r=facts.region(r).parentId()) if(facts.region(r).loopId()==loop) return true;
		return false;
	}
	private List<IterationPhase> takenBackedgePhases(int loopId) {
		return facts.loopExecutionCases(loopId).stream()
			.filter(value -> value.cardinality()==LoopCardinality.MANY)
			.flatMap(value -> (value.exactIterations()==2 ? List.of(IterationPhase.FIRST)
				: List.of(IterationPhase.FIRST,IterationPhase.MIDDLE)).stream()).distinct().toList();
	}
	private LoopExecutionCase firstBodyCase(int loopId) {
		return facts.loopExecutionCases(loopId).stream().filter(value -> value.cardinality()!=LoopCardinality.ZERO)
			.findFirst().orElseThrow(() -> new IllegalStateException("Loop has no reachable body case: "+loopId));
	}
	private MemoShapes.Shape repeatShape(Head head,Context context,List<Integer> frame,IterationPhase phase) {
		return shapes.bindingShape(head.value().backedgeBindingId(),
			withPhase(context,head.slot().loopId(),frame,phase).evaluation());
	}
	private Truth boundaryCompatible(Head head,int[] selected) {
		if(selected[head.variable()]<0) return Truth.UNKNOWN;
		MemoPartition target=headLayout(head,selected);
		Truth[] result={Truth.YES};
		MemoShapes.Shape[] contractRepeat={null},contractEntry={null};
		Boolean[] contractEntryRequired={null};
		var loop=facts.loop(head.slot().loopId());
		for(List<Integer> frame : framesFor(loop.regionId())) {
			LoopExecutionCase bodyCase=firstBodyCase(loop.id());
			Context base=withCardinality(context(selected,loop.regionId(),frame),loop.id(),frame,
				bodyCase.cardinality());
			MemoShapes.Shape repeat=repeatShape(head,base,frame,IterationPhase.FIRST);
			if(!repeat.exact()) { result[0]=Truth.UNKNOWN; continue; }
			if(contractRepeat[0]==null) contractRepeat[0]=repeat;
			else if(!samePhysicalShape(contractRepeat[0],repeat)) return Truth.NO;
			List<LoopBoundary> boundaries=new ArrayList<>();
			boolean required=entryRequired(head,frame);
			if(contractEntryRequired[0]==null) contractEntryRequired[0]=required;
			else if(contractEntryRequired[0]!=required) return Truth.NO;
			if(required) boundaries.add(new LoopBoundary(true,IterationPhase.FIRST));
			for(IterationPhase phase : takenBackedgePhases(loop.id()))
				boundaries.add(new LoopBoundary(false,phase));
			for(LoopBoundary boundary : boundaries) {
				boolean entry=boundary.entry();
				IterationPhase phase=boundary.phase();
				Context context=withLoopCase(base,loop.id(),frame,
					entry ? bodyCase.cardinality() : LoopCardinality.MANY,phase);
				int binding=entry ? head.value().entryBindingId() : head.value().backedgeBindingId();
				var visit=visitActive(entry ? loop.activationBindingId() : loop.backedgeActivationBindingId(),
					List.of(binding),context,environment -> {
					Context exact=context.withEnvironment(environment);
					MemoShapes.Shape actual=shapes.bindingShape(binding,exact.evaluation());
					if(!actual.exact()) { result[0]=Truth.UNKNOWN; return true; }
					if(actual.dataType()!=repeat.dataType() || actual.valueType()!=repeat.valueType()
						|| !entry && (actual.rows()!=repeat.rows() || actual.columns()!=repeat.columns())) {
						result[0]=Truth.NO; return false;
					}
					if(entry) {
						if(contractEntry[0]==null) contractEntry[0]=actual;
						else if(!samePhysicalShape(contractEntry[0],actual)) { result[0]=Truth.NO; return false; }
					}
					ResolvedOrigin origin=environment.origins().get(0);
					MemoPartition from=layout(origin,selected);
					if(from==null) { result[0]=Truth.UNKNOWN; return true; }
					if(target==MemoPartition.LOCAL) {
						var residency=privacy.localResidencyAt(binding,privacyContext(exact,List.of(binding)));
						if(residency.legality()==MemoPrivacy.Legality.ILLEGAL) {
							result[0]=Truth.NO; return false;
						}
						if(residency.legality()==MemoPrivacy.Legality.DEFER) {
							result[0]=Truth.UNKNOWN; return true;
						}
					}
					Truth legal=entry ? MemoMovement.legal(from,target,actual.dataType(),Privacy.PUBLIC,
						actual.rows(),actual.columns(),sources.workers(),false) : from==target ? Truth.YES : Truth.NO;
					if(legal==Truth.NO) { result[0]=Truth.NO; return false; }
					if(legal==Truth.UNKNOWN) result[0]=Truth.UNKNOWN;
					return true;
				});
				if(result[0]==Truth.NO) return Truth.NO;
				if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
			}
		}
		return result[0];
	}
	private static boolean samePhysicalShape(MemoShapes.Shape left,MemoShapes.Shape right) {
		return left.dataType()==right.dataType() && left.valueType()==right.valueType()
			&& left.rows()==right.rows() && left.columns()==right.columns();
	}

	private void registerMovementCosts() {
		Map<MovementGroup,Set<MovementEdge>> groups = new LinkedHashMap<>();
		int[] none=new int[sizes.length]; Arrays.fill(none,-1);
		for(int u = 0; u < uses.size(); u++) {
			Use use = uses.get(u);
			for(int i = 0; i < use.bindings().size(); i++) {
				final int position=i;
				Hop input = use.consumer().hop().getInput(i);
				if(!input.getDataType().isMatrix() && !input.getDataType().isFrame()
					|| universe.inputRoles().get(use.consumer().compiledOwnerId()).get(i) == InputRole.METADATA) continue;
				MovementEdge edge=new MovementEdge(u,i);
				for(List<Integer> frame : use.frames()) {
					Context base=context(none,use.consumer().regionId(),frame);
					visitActive(use.consumer(),List.of(use.bindings().get(i)),base,environment -> {
						var identity=resolver.resolveOriginIdentity(use.bindings().get(position),base.evaluation(),environment);
						if(retentionScope(identity)!=null)
							groups.computeIfAbsent(new MovementGroup(identity,null),ignored -> new LinkedHashSet<>()).add(edge);
						else groups.computeIfAbsent(new MovementGroup(null,edge),ignored -> new LinkedHashSet<>()).add(edge);
						return true;
					});
				}
			}
		}
		for(var group : groups.entrySet()) {
			List<Integer> bindings=group.getValue().stream()
				.map(edge -> uses.get(edge.use()).bindings().get(edge.position())).toList();
			List<Integer> owners=group.getValue().stream()
				.map(edge -> uses.get(edge.use()).consumer().compiledOwnerId()).toList();
			List<List<Integer>> frames=group.getValue().stream().flatMap(edge -> uses.get(edge.use()).frames().stream()).toList();
			int[] scope=scope(bindings,owners,frames);
			if(scope.length>MOVEMENT_FACTOR_WIDTH_DIAGNOSTIC)
				diagnostics.add("MOVEMENT_FACTOR_WIDTH origin="+(group.getKey().identity()==null ? "unretained"
					: group.getKey().identity().visibleBindingId())+" width="+scope.length);
			List<MovementEdge> edges=List.copyOf(group.getValue());
			costs.add(new CostTerm(scope,choices ->
				movementGroupCost(group.getKey(),edges,expand(scope,choices)),0));
		}
		for(Head head : heads) {
			int[] scope = scope(List.of(head.value().entryBindingId()),List.of(head.variable()),
				framesFor(facts.loop(head.slot().loopId()).regionId()));
			costs.add(new CostTerm(scope,choices -> entryCost(head,expand(scope,choices)),0));
		}
	}
	record Activation(Map<GuardDecision,Boolean> guards,Map<LoopDecision,IterationPhase> phases,double weight) {
		Activation(Map<GuardDecision,Boolean> guards,double weight) { this(guards,Map.of(),weight); }
		Activation { guards = Map.copyOf(guards); phases=Map.copyOf(phases); }
	}
	private record MovementActivation(MemoBindingResolver.OriginIdentity identity,MemoPartition target) {
	}
	/** Exact union under {@link Profile}'s correlated-per-GuardDecision estimator, not runtime reachability. */
	static double activationUnion(List<Activation> input,double scopeWeight) {
		return activationUnion(input,scopeWeight,0.5);
	}
	static double activationUnion(List<Activation> input,double scopeWeight,double thenProbability) {
		if(!(thenProbability>0 && thenProbability<1))
			throw new IllegalArgumentException("Guard probability must be between zero and one");
		Map<Map<LoopDecision,IterationPhase>,List<Activation>> byPhase=new LinkedHashMap<>();
		for(Activation event : new LinkedHashSet<>(input)) if(event.weight()!=0)
			byPhase.computeIfAbsent(event.phases(),ignored -> new ArrayList<>()).add(event);
		double total=0;
		for(List<Activation> events : byPhase.values()) {
			Set<Map<GuardDecision,Boolean>> cubes=new LinkedHashSet<>();
			double base=Double.NaN;
			for(Activation event : events) {
				double probability=guardCubeProbability(event.guards(),thenProbability);
				if(probability==0) continue;
				double candidate=event.weight()/probability;
				if(Double.isNaN(base)) base=candidate;
				else if(Math.abs(base-candidate)>1e-9*Math.max(1,Math.max(Math.abs(base),Math.abs(candidate))))
					throw new IllegalStateException("One retained origin has inconsistent activation bases: "
						+base+" and "+candidate);
				cubes.add(event.guards());
			}
			if(!Double.isNaN(base)) total+=base*guardUnionProbability(cubes,thenProbability,
				new LinkedHashMap<>(),new int[] {0});
		}
		return Math.min(scopeWeight,total);
	}
	private static double guardCubeProbability(Map<GuardDecision,Boolean> cube,double thenProbability) {
		double result=1;
		for(boolean value : cube.values()) result*=value ? thenProbability : 1-thenProbability;
		return result;
	}
	private static double guardUnionProbability(Set<Map<GuardDecision,Boolean>> cubes,double thenProbability,
		Map<Set<Map<GuardDecision,Boolean>>,Double> memo,int[] states) {
		if(cubes.isEmpty()) return 0;
		if(cubes.stream().anyMatch(Map::isEmpty)) return 1;
		Set<Map<GuardDecision,Boolean>> key=Set.copyOf(cubes);
		Double cached=memo.get(key); if(cached!=null) return cached;
		if(++states[0]>MOVEMENT_GUARD_STATE_LIMIT)
			throw new IllegalStateException("MOVEMENT_GUARD_FACTOR_STATES exceeds exact limit="
				+MOVEMENT_GUARD_STATE_LIMIT);
		Set<GuardDecision> guards=new LinkedHashSet<>();
		for(var cube : cubes) guards.addAll(cube.keySet());
		GuardDecision split=guards.iterator().next();
		Set<Map<GuardDecision,Boolean>> whenTrue=restrict(cubes,split,true);
		Set<Map<GuardDecision,Boolean>> whenFalse=restrict(cubes,split,false);
		double result=thenProbability*guardUnionProbability(whenTrue,thenProbability,memo,states)
			+(1-thenProbability)*guardUnionProbability(whenFalse,thenProbability,memo,states);
		memo.put(key,result); return result;
	}
	private static Set<Map<GuardDecision,Boolean>> restrict(Set<Map<GuardDecision,Boolean>> cubes,
		GuardDecision decision,boolean value) {
		Set<Map<GuardDecision,Boolean>> result=new LinkedHashSet<>();
		for(var cube : cubes) {
			Boolean required=cube.get(decision);
			if(required!=null && required!=value) continue;
			Map<GuardDecision,Boolean> reduced=new LinkedHashMap<>(cube); reduced.remove(decision);
			result.add(Map.copyOf(reduced));
		}
		return result;
	}
	private double movementGroupCost(MovementGroup group,List<MovementEdge> edges,int[] selected) {
		Map<MovementActivation,List<Activation>> events = new LinkedHashMap<>();
		Map<MovementActivation,Double> units = new LinkedHashMap<>();
		double[] unretainedCost = {0};
		for(MovementEdge edge : edges) {
			Use use = uses.get(edge.use()); int position = edge.position();
			MemoPartition target = memo.rule(use.consumer().compiledOwnerId(),selected[use.consumer().compiledOwnerId()])
				.inputs().get(position);
			for(List<Integer> frame : use.frames()) {
				Context context = context(selected,use.consumer().regionId(),frame);
				var visit = visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
					ResolvedOrigin origin = environment.origins().get(0); MemoPartition from = layout(origin,selected);
					if(from == null) throw new IllegalStateException("Unresolved source in selected movement cost");
					var identity=resolver.resolveOriginIdentity(use.bindings().get(position),context.evaluation(),environment);
					if(group.identity()!=null ? !group.identity().equals(identity) : retentionScope(identity)!=null)
						return true;
					MemoMovement.Kind kind = MemoMovement.kind(from,target,false);
					if(kind == MemoMovement.Kind.DIRECT || kind == MemoMovement.Kind.IMPOSSIBLE) return true;
					MovementActivation key = new MovementActivation(identity,target);
					Context exact=context.withEnvironment(environment);
					double weight=group.identity()==null
						? frequency(use.consumer().regionId(),frame,exact.evaluation())
							*environmentProbability(environment,context.evaluation())
						: definitionActivationWeight(identity,environment,selected);
					Hop view=shapes.viewAt(use.consumer().id(),exact.evaluation());
					// The input view already carries the exact reaching output slot's
					// frozen contextual shape. Live producer HOPs are emission identities,
					// not cost authority (especially for heterogeneous builtin outputs).
					double unit = MemoCostModel.movementCost(view.getInput(position),
						from,target,Math.max(1,sources.workers()),1);
					if(group.identity()==null) {
						// This group is one unretained demand edge. It has no shared
						// materialization activation, and its exact phase/call shape may
						// legitimately change between dynamic environments.
						unretainedCost[0] += unit*weight;
						return true;
					}
					Double prior = units.putIfAbsent(key,unit);
					if(prior != null && Double.doubleToLongBits(prior) != Double.doubleToLongBits(unit))
						throw new IllegalStateException("One exact movement origin has inconsistent size costs");
					events.computeIfAbsent(key,ignored -> new ArrayList<>()).add(new Activation(
						activeGuards(environment),definitionPhases(identity,environment),weight));
					return true;
				});
				if(visit.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
					throw new IllegalStateException("UNRESOLVED_MOVEMENT_ACTIVATION: occurrence " + use.consumer().id());
			}
		}
		double total = unretainedCost[0];
		for(var entry : events.entrySet()) {
			double activations=group.identity()==null ? entry.getValue().stream().mapToDouble(Activation::weight).sum()
				: activationUnion(entry.getValue(),Double.POSITIVE_INFINITY,profile.branchThenProbability());
			total += units.get(entry.getKey())*activations;
		}
		return total;
	}
	private double definitionActivationWeight(MemoBindingResolver.OriginIdentity identity,
		ResolvedEnvironment environment,int[] selected) {
		Occurrence definition=facts.occurrence(identity.nativeDefinitionOccurrenceId());
		EvaluationContext evaluation=new EvaluationContext(environment.guards(),environment.loops(),
			context(selected,definition.regionId(),identity.callFrame()).evaluation().selections(),4096,
			identity.callFrame(),environment.iterationPhases(),environment.cardinalities());
		Map<GuardDecision,Boolean> all=activeGuards(environment);
		Map<GuardDecision,Boolean> defining=new LinkedHashMap<>(
			context(selected,definition.regionId(),identity.callFrame()).evaluation().guards());
		defining.entrySet().removeIf(entry -> phaseGuard(entry.getKey().guardId(),entry.getKey().callFrame(),
			environment.iterationPhases())!=null);
		if(!all.entrySet().containsAll(defining.entrySet()))
			throw new IllegalStateException("Demand environment contradicts its exact native definition guards");
		double relative=guardCubeProbability(all,profile.branchThenProbability())
			/guardCubeProbability(defining,profile.branchThenProbability())
			*cardinalityProbability(environment.cardinalities());
		return frequency(definition.regionId(),identity.callFrame(),evaluation)*relative;
	}
	private Map<GuardDecision,Boolean> activeGuards(ResolvedEnvironment environment) {
		Map<GuardDecision,Boolean> result=new LinkedHashMap<>(environment.guards());
		result.entrySet().removeIf(entry -> phaseGuard(entry.getKey().guardId(),entry.getKey().callFrame(),
			environment.iterationPhases())!=null);
		return Map.copyOf(result);
	}
	private Map<LoopDecision,IterationPhase> definitionPhases(MemoBindingResolver.OriginIdentity identity,
		ResolvedEnvironment environment) {
		// LOOP_HEAD/iterator/unresolved origins are costed per demand and have no
		// native definition occurrence whose execution phases could be shared.
		if(identity.nativeDefinitionOccurrenceId()<0) return Map.of();
		Set<LoopDecision> relevant=new LinkedHashSet<>();
		for(var occurrenceContext : facts.occurrenceContexts(identity.nativeDefinitionOccurrenceId()))
			if(occurrenceContext.callFrame().equals(identity.callFrame()))
				for(PhaseLoop loop : occurrenceContext.phaseLoops()) relevant.add(new LoopDecision(loop.loopId(),loop.callFrame()));
		Map<LoopDecision,IterationPhase> result=new LinkedHashMap<>();
		for(var entry : environment.iterationPhases().entrySet()) if(relevant.contains(entry.getKey()))
			result.put(entry.getKey(),entry.getValue());
		return Map.copyOf(result);
	}
	private boolean retainable(MemoBindingResolver.OriginIdentity identity) {
		if(identity.kind()!=OriginKind.COMPILED_OWNER || identity.nativeDefinitionOccurrenceId()<0) return false;
		Occurrence occurrence=facts.occurrence(identity.nativeDefinitionOccurrenceId());
		if(occurrence.hop() instanceof org.apache.sysds.hops.FunctionOp function
			&& function.getFunctionType()==org.apache.sysds.hops.FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& identity.boundarySlot()>=0 && identity.exportChain().isEmpty()) {
			int binding=identity.visibleBindingId();
			if(binding<0 || binding>=facts.bindings().size()) return false;
			var node=facts.binding(binding);
			return node.kind()==MemoProgramFacts.BindingKind.DEFINITION
				&& node.referenceId()==occurrence.id() && node.position()==identity.boundarySlot();
		}
		long nativeBinding=facts.valueBinding(identity.nativeDefinitionOccurrenceId());
		if(identity.exportChain().isEmpty()) return identity.visibleBindingId()==nativeBinding;
		return identity.exportChain().get(0).sourceBindingId()==nativeBinding
			&& identity.exportChain().get(identity.exportChain().size()-1).targetBindingId()==identity.visibleBindingId();
	}
	/** Broadest runtime scope in which the exact visible value version exists. */
	private RetentionScope retentionScope(MemoBindingResolver.OriginIdentity identity) {
		if(!retainable(identity)) return null;
		int visibleDepth=identity.callFrame().size()-identity.exportChain().size();
		if(visibleDepth<0) return null;
		if(visibleDepth==0) return new RetentionScope(MemoPlanRegistry.ScopeKind.PROGRAM_ROOT,-1);
		int callId=identity.callFrame().get(visibleDepth-1);
		if(callId<0 || callId>=facts.calls().size()) return null;
		long block=facts.functionStatementBlockId(facts.call(callId).functionSummaryId());
		return block<0 ? null : new RetentionScope(MemoPlanRegistry.ScopeKind.FUNCTION_INVOCATION,block);
	}
	private double entryCost(Head head,int[] selected) {
		double[] total = {0}; var loop = facts.loop(head.slot().loopId());
		for(List<Integer> frame : framesFor(loop.regionId())) {
			if(!entryRequired(head,frame)) continue;
			List<GuardDecision> shapeGuards=new ArrayList<>(
				shapes.guardSensitiveDecisionsForBinding(head.value().entryBindingId(),frame));
			for(LoopExecutionCase executionCase : loop.executionCases()) {
				if(executionCase.cardinality()==LoopCardinality.ZERO) continue;
				Context base=context(selected,loop.regionId(),frame);
				visitPhases(enclosingPhaseLoops(loop,frame),0,base,enclosing -> {
					Context first=withLoopCase(enclosing,loop.id(),frame,
						executionCase.cardinality(),IterationPhase.FIRST);
					return visitActive(loop.activationBindingId(),List.of(head.value().entryBindingId()),first,environment -> {
						ResolvedOrigin origin=environment.origins().get(0);
						MemoPartition from=layout(origin,selected),target=headLayout(head,selected);
						if(from!=null && from!=target
							&& MemoMovement.kind(from,target,false)!=MemoMovement.Kind.IMPOSSIBLE) {
							Context exact=first.withEnvironment(environment);
							visitNativeShapeGuards(shapeGuards,0,exact,shaped -> {
								Hop source=origin.kind()==OriginKind.COMPILED_OWNER
									? shapes.outputViewAt(origin.referenceId(),Math.max(0,origin.boundarySlot()),
										shaped.evaluation().withCallFrame(origin.callFrame()))
									: shapes.bindingViewAt(head.value().entryBindingId(),shaped.evaluation());
								ResolvedEnvironment projected=environment(shaped.evaluation());
								total[0]+=MemoCostModel.movementCost(source,from,target,
									Math.max(1,sources.workers()),frequency(
										facts.regions().get(loop.regionId()).parentId(),frame,shaped.evaluation())
										*environmentProbability(projected,first.evaluation()));
							});
						}
						return true;
					});
				});
			}
		}
		return total[0];
	}

	private List<PhaseLoop> enclosingPhaseLoops(MemoProgramFacts.LoopRecord boundary,List<Integer> frame) {
		Set<PhaseLoop> result=new LinkedHashSet<>();
		for(int depth=frame.size();depth>=0;depth--) {
			List<Integer> prefix=frame.subList(0,depth);
			int region=depth==frame.size() ? facts.region(boundary.regionId()).parentId()
				: facts.occurrence(facts.call(frame.get(depth)).callOccurrenceId()).regionId();
			for(int current=region;current>=0;current=facts.region(current).parentId()) {
				int loop=facts.region(current).loopId();
				if(loop<0 || loop==boundary.id()) continue;
				List<LoopExecutionCase> cases=facts.loopExecutionCases(loop);
				List<IterationPhase> phases=cases.stream().flatMap(value -> value.bodyPhases().stream())
					.distinct().toList();
				if(!phases.isEmpty()) result.add(new PhaseLoop(loop,prefix,phases,cases));
			}
		}
		return List.copyOf(result);
	}

	private Map<Long,List<MemoSupplyRegistry.Supply>> reconstructSupplies(int[] selected) {
		Map<Long,List<MemoSupplyRegistry.Supply>> byBlock = new LinkedHashMap<>();
		for(Use use : uses) {
			if(compatible(use,selected) != Truth.YES) throw new IllegalStateException("Selected use lost legality");
			int owner = use.consumer().compiledOwnerId(); RuleMemo.Rule rule = memo.rule(owner,selected[owner]);
			for(int position = 0; position < use.bindings().size(); position++) {
				if(universe.inputRoles().get(owner).get(position) == InputRole.METADATA) continue;
				Hop input = use.consumer().hop().getInput(position);
				if(!input.getDataType().isMatrix() && !input.getDataType().isFrame()) continue;
				Set<MemoPartition> layouts = new LinkedHashSet<>();
				for(List<Integer> frame : use.frames()) {
					Context context = context(selected,use.consumer().regionId(),frame);
					visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
						layouts.add(layout(environment.origins().get(0),selected)); return true;
					});
				}
				MemoPartition target = rule.inputs().get(position);
				if(layouts.isEmpty()) continue; // the consumer follows an always-throwing function call
				if(layouts.size() == 1 && layouts.contains(target)) continue;
				List<MemoSupplyRegistry.Recipe> recipes = new ArrayList<>();
				for(MemoPartition source : layouts) recipes.add(new MemoSupplyRegistry.Recipe(source,
					MemoSupplyRegistry.Movement.valueOf(MemoMovement.kind(source,target,false).name()),
					source.federated() || target.federated() ? sources.pool().encode() : null));
				byBlock.computeIfAbsent(use.consumer().statementBlockId(),ignored -> new ArrayList<>()).add(
					new MemoSupplyRegistry.Supply(input.getHopID(),use.consumer().hop().getHopID(),position,target,recipes));
			}
		}
		byBlock.replaceAll((block,supplies) -> List.copyOf(supplies));
		return byBlock;
	}

	/** Reconstruct the exact retained actions costed by {@link #movementGroupCost}. */
	public MemoPlanRegistry.SupplySchedule supplySchedule(long epoch,Solution solution) {
		Map<SupplyEdge,MemoSupplyRegistry.Supply> selectedSupplies=new LinkedHashMap<>();
		for(var block : solution.supplies().entrySet()) for(var supply : block.getValue()) {
			SupplyEdge edge=new SupplyEdge(block.getKey(),supply.producerHopId(),supply.consumerHopId(),
				supply.inputPosition());
			if(selectedSupplies.putIfAbsent(edge,supply)!=null)
				throw new IllegalStateException("Duplicate selected supply edge "+edge);
		}
		List<ScheduledDemand> demands=new ArrayList<>();
		int[] selected=solution.optimization().selectedRules();
		for(Use use : uses) for(int position=0;position<use.bindings().size();position++) {
			final int inputPosition=position;
			Hop input=use.consumer().hop().getInput(position);
			SupplyEdge edge=new SupplyEdge(use.consumer().statementBlockId(),input.getHopID(),
				use.consumer().hop().getHopID(),position);
			MemoSupplyRegistry.Supply supply=selectedSupplies.get(edge);
			if(supply==null) continue;
			for(List<Integer> frame : use.frames()) {
				Context context=context(selected,use.consumer().regionId(),frame);
				var visit=visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
					ResolvedOrigin origin=environment.origins().get(0);
					MemoPartition source=layout(origin,selected);
					if(source==null) throw new IllegalStateException("Unresolved source in selected supply schedule");
					MemoMovement.Kind movement=MemoMovement.kind(source,supply.targetPartition(),false);
					if(movement==MemoMovement.Kind.DIRECT) return true;
					if(movement==MemoMovement.Kind.IMPOSSIBLE)
						throw new IllegalStateException("Selected supply became impossible for "+edge);
					MemoBindingResolver.OriginIdentity identity=resolver.resolveOriginIdentity(
						use.bindings().get(inputPosition),context.evaluation(),environment);
					RetentionScope scope=retentionScope(identity);
					if(scope==null) return true; // paid per demand; no runtime retained action
					MemoSupplyRegistry.Recipe recipe=supply.recipes().stream()
						.filter(candidate -> candidate.sourcePartition()==source
							&& candidate.movement()==MemoSupplyRegistry.Movement.valueOf(movement.name()))
						.findFirst().orElseThrow(() -> new IllegalStateException(
							"Selected supply has no recipe for exact source "+source+" at "+edge));
					demands.add(new ScheduledDemand(scope,identity,edge,recipe,supply.targetPartition()));
					return true;
				});
				if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
					throw new IllegalStateException("UNRESOLVED_SUPPLY_SCHEDULE: occurrence "+use.consumer().id());
			}
		}

		Map<RetentionScope,Long> retentionRegions=new LinkedHashMap<>();
		long nextRegion=facts.regions().stream().mapToLong(MemoProgramFacts.Region::id).max().orElse(-1)+1;
		for(ScheduledDemand demand : demands)
			if(!retentionRegions.containsKey(demand.scope())) retentionRegions.put(demand.scope(),nextRegion++);
		Map<RetentionScope,Set<Action>> actions=new LinkedHashMap<>();
		Set<MemoPlanRegistry.DefinitionSite> definitions = new LinkedHashSet<>();
		Set<MemoPlanRegistry.ReturnDefinitionSite> returns=new LinkedHashSet<>();
		Set<MemoPlanRegistry.ActionEdge> edges = new LinkedHashSet<>();
		for(ScheduledDemand demand : demands) {
			long region=retentionRegions.get(demand.scope());
			Action action=new Action(epoch,demand.identity().visibleBindingId(),demand.target(),demand.recipe(),
				region,"canonical/v2");
			actions.computeIfAbsent(demand.scope(),ignored -> new LinkedHashSet<>()).add(action);
			Occurrence nativeDefinition=facts.occurrence(demand.identity().nativeDefinitionOccurrenceId());
			long nativeOrigin=demand.identity().boundarySlot()>=0
				? demand.identity().visibleBindingId() : facts.valueBinding(nativeDefinition.id());
			Hop definitionHop=nativeOutputHop(demand.identity(),nativeDefinition.hop());
			definitions.add(new MemoPlanRegistry.DefinitionSite(nativeDefinition.statementBlockId(),
				definitionHop.getHopID(),nativeOrigin));
			for(var export : demand.identity().exportChain()) {
				var call=facts.call(export.callId());
				if(call.callOccurrenceId()<0)
					throw new IllegalStateException("Retained export has no exact call occurrence: "+export.callId());
				Occurrence callOccurrence=facts.occurrence(call.callOccurrenceId());
				returns.add(new MemoPlanRegistry.ReturnDefinitionSite(callOccurrence.statementBlockId(),
					callOccurrence.hop().getHopID(),export.outputSlot(),export.sourceBindingId(),export.targetBindingId()));
			}
			edges.add(new MemoPlanRegistry.ActionEdge(demand.edge().statementBlockId(),
				demand.edge().producerHopId(),demand.edge().consumerHopId(),demand.edge().inputPosition(),action));
		}
		List<MemoPlanRegistry.ScopePlan> scopes = new ArrayList<>();
		for(var entry : actions.entrySet()) scopes.add(new MemoPlanRegistry.ScopePlan(entry.getKey().kind(),
			entry.getKey().statementBlockId(),retentionRegions.get(entry.getKey()),List.copyOf(entry.getValue())));
		return new MemoPlanRegistry.SupplySchedule(scopes,List.copyOf(definitions),List.copyOf(edges),List.copyOf(returns));
	}


	/** All runtime residencies of one transparent value; no alias creates a new layout. */
	public Set<MemoPartition> selectedLayouts(int owner,int[] selected) {
		if(!memo.rules(owner).isEmpty()) return Set.copyOf(memo.rule(owner,selected[owner]).outputs());
		Set<MemoPartition> layouts = new LinkedHashSet<>();
		for(Occurrence occurrence : occurrencesByOwner.get(owner))
		for(List<Integer> frame : framesFor(occurrence.regionId())) {
			Context context = context(selected,occurrence.regionId(),frame);
			var visit = visitActive(occurrence,List.of(facts.valueBinding(occurrence.id())),context,environment -> {
				MemoPartition partition = layout(environment.origins().get(0),selected);
				if(partition != null) layouts.add(partition);
				return true;
			});
			if(visit.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
				throw new IllegalStateException("Unresolved selected alias owner " + owner);
		}
		return Set.copyOf(layouts);
	}

	/** Resolve normal continuation before payloads: an existing function-return error supplies no value. */
	private MemoBindingResolver.VisitResult visitActive(Occurrence occurrence,List<Integer> bindings,Context context,
		MemoBindingResolver.EnvironmentVisitor visitor) {
		if(!context.reachable()) return unreachableGuardConjunction();
		List<PhaseLoop> loops = phaseLoops(occurrence,context.evaluation().callFrame());
		return visitPhases(loops,0,context,phase ->
			visitActive(occurrence.activationBindingId(),bindings,phase,visitor));
	}
	/** ProgramFacts owns reachable call/phase contexts; the model only enumerates its compact alternatives lazily. */
	private List<PhaseLoop> phaseLoops(Occurrence occurrence,List<Integer> frame) {
		return facts.occurrenceContexts(occurrence.id()).stream()
			.filter(context -> context.callFrame().equals(frame)).map(MemoProgramFacts.OccurrenceContext::phaseLoops)
			.findFirst().orElseThrow(() -> new IllegalStateException(
				"Missing occurrence context " + occurrence.id() + " frame=" + frame));
	}
	@FunctionalInterface private interface ContextVisitor {
		MemoBindingResolver.VisitResult visit(Context context);
	}
	private MemoBindingResolver.VisitResult visitPhases(List<PhaseLoop> loops,int index,Context context,
		ContextVisitor visitor) {
		if(index==loops.size()) return visitor.visit(context);
		PhaseLoop fact=loops.get(index);
		LoopDecision loop=new LoopDecision(fact.loopId(),fact.callFrame());
		LoopCardinalityDecision cardinalityDecision=new LoopCardinalityDecision(fact.loopId(),fact.callFrame());
		if(context.evaluation().iterationPhases().containsKey(loop)
			&& context.evaluation().cardinalities().containsKey(cardinalityDecision))
			return visitPhases(loops,index+1,context,visitor);
		int count=0; boolean unknown=false;
		for(LoopExecutionCase executionCase : fact.executionCases()) {
			LoopCardinality fixedCardinality=context.evaluation().cardinalities().get(cardinalityDecision);
			if(fixedCardinality!=null && fixedCardinality!=executionCase.cardinality()) continue;
			for(IterationPhase phase : executionCase.bodyPhases()) {
				IterationPhase fixedPhase=context.evaluation().iterationPhases().get(loop);
				if(fixedPhase!=null && fixedPhase!=phase) continue;
				Context next=withLoopCase(context,fact.loopId(),fact.callFrame(),executionCase.cardinality(),phase);
				var result=visitPhases(loops,index+1,next,visitor); count+=result.environmentCount();
				if(result.status()==MemoBindingResolver.VisitStatus.STOPPED) return result;
				unknown |= result.status()==MemoBindingResolver.VisitStatus.UNRESOLVED;
			}
		}
		return new MemoBindingResolver.VisitResult(unknown ? MemoBindingResolver.VisitStatus.UNRESOLVED
			: MemoBindingResolver.VisitStatus.COMPLETE,count,"reachable structured loop phases");
	}
	private List<double[]> nativeCostTerms(Occurrence occurrence,List<Integer> frame) {
		int owner=occurrence.compiledOwnerId();
		int[] unselected=new int[sizes.length]; Arrays.fill(unselected,-1);
		Context base=context(unselected,occurrence.regionId(),frame);
		// Native execution depends on control activation and native shape, never on
		// which exact producer realizes an input. Source-only branch products are
		// intentionally absent; every remaining projected context stays a separate
		// binary64 primitive and is summed exactly by the optimizer/audit.
		List<GuardDecision> shapeGuards=new ArrayList<>(shapes.guardSensitiveDecisions(occurrence.id(),frame));
		List<double[]> terms=new ArrayList<>();
		var visit=visitActive(occurrence,List.of(),base,environment -> {
			Context active=base.withEnvironment(environment);
			visitNativeShapeGuards(shapeGuards,0,active,exact -> {
				ResolvedEnvironment projected=environment(exact.evaluation());
				double weight=frequency(occurrence.regionId(),frame,exact.evaluation())
					*environmentProbability(projected,base.evaluation());
				if(weight==0) return;
				double[] values=new double[sizes[owner]];
				Hop view=shapes.viewAt(occurrence.id(),exact.evaluation());
				for(int rule=0;rule<values.length;rule++) values[rule]=MemoCostModel.nativeCost(view,
					memo.rule(owner,rule),Math.max(1,sources.workers()),weight);
				terms.add(values);
			});
			return true;
		});
		if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
			throw new IllegalStateException("UNRESOLVED_PROGRAM_ACTIVATION: occurrence "+occurrence.id());
		return terms;
	}
	@FunctionalInterface private interface NativeContextVisitor { void visit(Context context); }
	private void visitNativeShapeGuards(List<GuardDecision> guards,int index,Context context,
		NativeContextVisitor visitor) {
		if(index==guards.size()) { visitor.visit(context); return; }
		GuardDecision guard=guards.get(index);
		if(context.evaluation().guards().containsKey(guard)
			|| phaseGuard(guard.guardId(),guard.callFrame(),context.evaluation().iterationPhases())!=null) {
			visitNativeShapeGuards(guards,index+1,context,visitor); return;
		}
		visitNativeShapeGuards(guards,index+1,withGuard(context,guard,false),visitor);
		visitNativeShapeGuards(guards,index+1,withGuard(context,guard,true),visitor);
	}
	private Context withGuard(Context context,GuardDecision guard,boolean value) {
		EvaluationContext e=context.evaluation();
		Map<GuardDecision,Boolean> guards=new LinkedHashMap<>(e.guards()); guards.put(guard,value);
		return context.withEvaluation(new EvaluationContext(guards,e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),e.iterationPhases(),e.cardinalities()));
	}
	private static ResolvedEnvironment environment(EvaluationContext evaluation) {
		return new ResolvedEnvironment(List.of(),evaluation.guards(),evaluation.loops(),
			evaluation.iterationPhases(),evaluation.cardinalities());
	}
	private MemoBindingResolver.VisitResult visitActive(int activation,List<Integer> bindings,Context context,
		MemoBindingResolver.EnvironmentVisitor visitor) {
		if(!context.reachable()) return unreachableGuardConjunction();
		List<Integer> frame = context.evaluation().callFrame();
		// Caller prerequisites precede every instruction in its callee. Resolve each in its own frame.
		return visitCallerPrerequisites(activation,bindings,context,visitor,frame,0);
	}
	private static MemoBindingResolver.VisitResult unreachableGuardConjunction() {
		return new MemoBindingResolver.VisitResult(MemoBindingResolver.VisitStatus.COMPLETE,0,
			"unreachable correlated guard conjunction");
	}
	private MemoBindingResolver.VisitResult visitCallerPrerequisites(int activation,List<Integer> bindings,
		Context context,MemoBindingResolver.EnvironmentVisitor visitor,List<Integer> frame,int depth) {
		if(depth < frame.size()) {
			var call = facts.call(frame.get(depth));
			if(call.callOccurrenceId() >= 0) {
				Occurrence caller = facts.occurrence(call.callOccurrenceId());
				Context callerContext = context.withCallFrame(frame.subList(0,depth));
				return visitAfterActivation(caller.activationBindingId(),callerContext,environment ->
					visitCallerPrerequisites(activation,bindings,context.withEnvironment(environment),visitor,
						frame,depth+1));
			}
			return visitCallerPrerequisites(activation,bindings,context,visitor,frame,depth+1);
		}
		return visitAfterActivation(activation,context,environment ->
			resolver.visitJoint(bindings,context.withEnvironment(environment).evaluation(),visitor));
	}
	@FunctionalInterface private interface ActivatedVisitor {
		MemoBindingResolver.VisitResult visit(ResolvedEnvironment environment);
	}
	private MemoBindingResolver.VisitResult visitAfterActivation(int activation,Context context,ActivatedVisitor visitor) {
		if(activation < 0) return visitor.visit(new ResolvedEnvironment(List.of(),context.evaluation().guards(),
			context.evaluation().loops(),context.evaluation().iterationPhases(),context.evaluation().cardinalities()));
		if(resolver.alwaysAvailable(activation,context.evaluation().callFrame()))
			return visitor.visit(new ResolvedEnvironment(List.of(),context.evaluation().guards(),
				context.evaluation().loops(),context.evaluation().iterationPhases(),context.evaluation().cardinalities()));
		boolean[] unresolved = {false}, stopped = {false}; int[] count = {0};
		var outer = resolver.visitJoint(List.of(activation),context.evaluation(),environment -> {
			OriginKind kind = environment.origins().get(0).kind();
			if(kind == OriginKind.TERMINATED_AT_RETURN) return true;
			if(kind != OriginKind.NORMAL_CONTINUATION) { unresolved[0] = true; return true; }
			var result = visitor.visit(environment); count[0] += result.environmentCount();
			unresolved[0] |= result.status() == MemoBindingResolver.VisitStatus.UNRESOLVED;
			stopped[0] |= result.status() == MemoBindingResolver.VisitStatus.STOPPED;
			return !stopped[0];
		});
		unresolved[0] |= outer.status() == MemoBindingResolver.VisitStatus.UNRESOLVED;
		return new MemoBindingResolver.VisitResult(stopped[0] ? MemoBindingResolver.VisitStatus.STOPPED
			: unresolved[0] ? MemoBindingResolver.VisitStatus.UNRESOLVED : MemoBindingResolver.VisitStatus.COMPLETE,
			count[0],"normal-continuation activation");
	}
	private double normalProbability(Occurrence occurrence,List<Integer> frame) {
		int[] unselected = new int[sizes.length]; Arrays.fill(unselected,-1);
		Context context = context(unselected,occurrence.regionId(),frame);
		double[] probability = {0};
		var result = visitActive(occurrence,List.of(),context,environment -> {
			probability[0] += environmentProbability(environment,context.evaluation()); return true;
		});
		if(result.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
			throw new IllegalStateException("UNRESOLVED_PROGRAM_ACTIVATION: occurrence " + occurrence.id());
		return probability[0];
	}

	public Map<StatementBlock,List<MemoLoopRuntime.Contract>> loopContracts(int[] selected) {
		Map<StatementBlock,List<MemoLoopRuntime.Contract>> result=new java.util.IdentityHashMap<>();
		for(var loop : facts.loops()) if(!framesFor(loop.regionId()).isEmpty())
			result.put(facts.statementBlock(loop.statementBlockId()),new ArrayList<>());
		for(Head head : heads) {
			var loop=facts.loop(head.slot().loopId());
			List<List<Integer>> contexts=framesFor(loop.regionId());
			if(contexts.isEmpty()) continue;
			MemoPartition target=headLayout(head,selected);
			Set<MemoPartition> layouts=new LinkedHashSet<>();
			Set<MemoShapes.Shape> entries=new LinkedHashSet<>(), repeats=new LinkedHashSet<>();
			boolean[] iterator={false}; boolean required=false;
			for(List<Integer> frame : contexts) {
				LoopExecutionCase bodyCase=firstBodyCase(loop.id());
				Context base=withCardinality(context(selected,loop.regionId(),frame),loop.id(),frame,
					bodyCase.cardinality());
				Context first=withPhase(base,loop.id(),frame,IterationPhase.FIRST);
				repeats.add(repeatShape(head,base,frame,IterationPhase.FIRST));
				for(IterationPhase phase : takenBackedgePhases(loop.id())) {
					Context repeatContext=withLoopCase(base,loop.id(),frame,LoopCardinality.MANY,phase);
					repeats.add(shapes.bindingShape(head.value().backedgeBindingId(),repeatContext.evaluation()));
					if(target==MemoPartition.LOCAL) {
						var visit=visitActive(loop.backedgeActivationBindingId(),
							List.of(head.value().backedgeBindingId()),repeatContext,environment -> {
							var residency=privacy.localResidencyAt(head.value().backedgeBindingId(),privacyContext(
								repeatContext.withEnvironment(environment),List.of(head.value().backedgeBindingId())));
							if(residency.legality()!=MemoPrivacy.Legality.LEGAL)
								throw new IllegalStateException("Selected LOCAL loop residency is not proven: "
									+head.value().variable()+" "+residency.reason());
							return true;
						});
						if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
							throw new IllegalStateException("Selected LOCAL loop residency is unresolved: "
								+head.value().variable());
					}
				}
				if(!entryRequired(head,frame)) continue;
				required=true;
				entries.add(shapes.bindingShape(head.value().entryBindingId(),first.evaluation()));
				visitActive(loop.activationBindingId(),List.of(head.value().entryBindingId()),first,environment -> {
					ResolvedOrigin origin=environment.origins().get(0);
					if(origin.kind()==OriginKind.ITERATOR_LOCAL) { iterator[0]=true; return true; }
					if(target==MemoPartition.LOCAL) {
						var residency=privacy.localResidencyAt(head.value().entryBindingId(),privacyContext(
							first.withEnvironment(environment),List.of(head.value().entryBindingId())));
						if(residency.legality()!=MemoPrivacy.Legality.LEGAL)
							throw new IllegalStateException("Selected LOCAL loop entry residency is not proven: "
								+head.value().variable()+" "+residency.reason());
					}
					layouts.add(layout(origin,selected)); return true;
				});
			}
			if(iterator[0]) continue;
			if(required && layouts.isEmpty()) continue;
			if(repeats.size()!=1 || !repeats.iterator().next().exact()
				|| layouts.contains(null) || required && (entries.size()!=1 || !entries.iterator().next().exact()))
				throw new IllegalStateException("Selected loop contract has unresolved phase shape/source: "+head.value().variable());
			var repeat=repeats.iterator().next();
			var entry=required ? entries.iterator().next() : repeat;
			List<MemoSupplyRegistry.Recipe> recipes=new ArrayList<>();
			for(MemoPartition source : layouts) recipes.add(new MemoSupplyRegistry.Recipe(source,
				MemoSupplyRegistry.Movement.valueOf(MemoMovement.kind(source,target,false).name()),
				source.federated() || target.federated() ? sources.pool().encode() : null));
			boolean scalar=repeat.dataType().isScalar();
			MemoLoopRuntime.Contract contract=new MemoLoopRuntime.Contract(head.value().variable(),repeat.dataType(),
				repeat.valueType(),target,scalar ? -1 : entry.rows(),scalar ? -1 : entry.columns(),
				scalar ? -1 : repeat.rows(),scalar ? -1 : repeat.columns(),required,
				true,recipes,sources.pool());
			result.computeIfAbsent(facts.statementBlock(loop.statementBlockId()),ignored -> new ArrayList<>()).add(contract);
		}
		result.replaceAll((block,contracts) -> List.copyOf(contracts));
		return java.util.Collections.unmodifiableMap(result);
	}

	private int[] scope(List<Integer> bindings,List<Integer> required,List<List<Integer>> frames) {
		Set<Integer> scope = new LinkedHashSet<>(required);
		for(List<Integer> frame : frames) {
			scope.addAll(resolver.directSupplyOwners(bindings,frame));
			for(LoopSlot slot : resolver.directLoopHeads(bindings,frame)) {
				Head head = headsBySlot.get(slot);
				if(head != null) scope.add(head.variable());
			}
		}
		return scope.stream().sorted().mapToInt(Integer::intValue).toArray();
	}
	private int[] expand(int[] scope,int[] choices) {
		int[] result = new int[sizes.length]; Arrays.fill(result,-1);
		for(int i = 0; i < scope.length; i++) result[scope[i]] = choices[i];
		return result;
	}
	private Context context(int[] selected,int region,List<Integer> frame) {
		Map<GuardDecision,Boolean> guards = new LinkedHashMap<>();
		boolean reachable=true;
		for(int r = region; r >= 0; r = facts.regions().get(r).parentId()) {
			var current = facts.regions().get(r);
			if(current.kind() == RegionKind.IF_THEN || current.kind() == RegionKind.IF_ELSE) {
				boolean branch=current.kind()==RegionKind.IF_THEN;
				reachable &= putGuard(guards,facts.guardDecision(current.guardId(),frame),
					facts.canonicalGuardValue(current.guardId(),frame,branch));
			}
		}
		for(int index = 0; index < frame.size(); index++) {
			var call = facts.call(frame.get(index));
			if(call.callOccurrenceId() < 0) continue;
			for(int r = facts.occurrence(call.callOccurrenceId()).regionId(); r >= 0;
				r = facts.regions().get(r).parentId()) {
				var current = facts.regions().get(r);
				if(current.kind() == RegionKind.IF_THEN || current.kind() == RegionKind.IF_ELSE) {
					List<Integer> callerFrame=frame.subList(0,index);
					boolean branch=current.kind()==RegionKind.IF_THEN;
					reachable &= putGuard(guards,facts.guardDecision(current.guardId(),callerFrame),
						facts.canonicalGuardValue(current.guardId(),callerFrame,branch));
				}
			}
		}
		MemoBindingResolver.SelectionLookup lookup = new MemoBindingResolver.SelectionLookup() {
			@Override public int selectedRule(int owner) { return selected[owner]; }
			@Override public MemoPartition selectedLayout(int owner) {
				return selected[owner] < 0 || memo.rules(owner).isEmpty() ? null : memo.rule(owner,selected[owner]).output();
			}
			@Override public boolean headIsBound(int loop,int slot) { return headsBySlot.containsKey(new LoopSlot(loop,slot)); }
			@Override public MemoPartition selectedHeadLayout(int loop,int slot) {
				Head head = headsBySlot.get(new LoopSlot(loop,slot)); return head == null ? null : headLayout(head,selected);
			}
			};
			EvaluationContext evaluation = new EvaluationContext(guards,Map.of(),lookup,4096,frame);
			return new Context(evaluation,Map.of(),reachable);
		}
	private static boolean putGuard(Map<GuardDecision,Boolean> guards,GuardDecision guard,boolean value) {
		Boolean prior=guards.putIfAbsent(guard,value);
		return prior==null || prior==value;
	}

	/** Attach only loop privacy contracts transitively required by an exact privacy fallback. */
	private Context privacyContext(Context context,List<Integer> bindings) {
		if(bindings.isEmpty() || bindings.stream().allMatch(privacy::provesReleasedBinding)) return context;
		Set<Integer> relevant=new LinkedHashSet<>();
		for(LoopSlot slot : resolver.relevantLoopHeads(bindings,context.evaluation().callFrame()))
			relevant.add(slot.loopId());
		if(relevant.isEmpty()) return context;
		var evaluation=context.evaluation();
		Context invariant=context.withEvaluation(new EvaluationContext(evaluation.guards(),evaluation.loops(),
			MemoBindingResolver.NO_SELECTION,evaluation.maxDepth(),evaluation.callFrame(),
			evaluation.iterationPhases(),evaluation.cardinalities()));
		List<Integer> requestedFrame=evaluation.callFrame();
		for(var loop : facts.loops()) if(relevant.contains(loop.id())) {
			for(List<Integer> frame : facts.callFramesForRegion(loop.regionId())) {
				if(!prefix(requestedFrame,frame) && !prefix(frame,requestedFrame)) continue;
				Context invoked=invariant.withCallFrame(frame);
				Map<LoopSlot,MemoPrivacy.Result> summary=privacy.summarizeLoop(loop.id(),invoked);
				for(var entry : summary.entrySet()) if(entry.getValue().exact())
					invariant=invariant.withLoopHeadAt(frame,entry.getKey().loopId(),entry.getKey().slot(),
						entry.getValue().privacy());
			}
		}
		return invariant.withEvaluation(evaluation);
	}
	private static boolean prefix(List<Integer> prefix,List<Integer> value) {
		return prefix.size()<=value.size() && value.subList(0,prefix.size()).equals(prefix);
	}
	private MemoPartition layout(ResolvedOrigin origin,int[] selected) {
		if(origin.kind() == OriginKind.ITERATOR_LOCAL) return MemoPartition.LOCAL;
		if(origin.kind() == OriginKind.LOOP_HEAD_CONTRACT) {
			Head head = headsBySlot.get(new LoopSlot(origin.referenceId(),origin.boundarySlot()));
			return head == null ? null : headLayout(head,selected);
		}
		int owner = origin.compiledOwnerId();
		if(owner < 0 || selected[owner] < 0 || memo.rules(owner).isEmpty()) return null;
		return selectedOutput(memo.rule(owner,selected[owner]),origin.boundarySlot());
	}
	private static MemoPartition selectedOutput(RuleMemo.Rule rule,int slot) {
		if(slot<0) return rule.output();
		return slot<rule.outputs().size() ? rule.outputs().get(slot) : null;
	}
	private Hop nativeOutputHop(ResolvedOrigin origin,Hop fallback) {
		return origin.kind()==OriginKind.COMPILED_OWNER
			? nativeOutputHop(origin.referenceId(),origin.boundarySlot(),fallback) : fallback;
	}
	private Hop nativeOutputHop(MemoBindingResolver.OriginIdentity identity,Hop fallback) {
		return identity.kind()==OriginKind.COMPILED_OWNER
			? nativeOutputHop(identity.nativeDefinitionOccurrenceId(),identity.boundarySlot(),fallback) : fallback;
	}
	private Hop nativeOutputHop(int occurrence,int slot,Hop fallback) {
		if(slot<0 || occurrence<0 || occurrence>=facts.occurrences().size()) return fallback;
		Hop hop=facts.occurrence(occurrence).hop();
		if(hop instanceof org.apache.sysds.hops.FunctionOp function
			&& function.getFunctionType()==org.apache.sysds.hops.FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& function.getOutputs()!=null && slot<function.getOutputs().size()
			&& function.getOutputs().get(slot)!=null) return function.getOutputs().get(slot);
		return fallback;
	}
	private MemoPartition headLayout(Head head,int[] selected) {
		int value = selected[head.variable()];
		return value < 0 ? null : sources.workers() == 1 && value == 1 ? MemoPartition.REP : MemoPartition.values()[value];
	}
	private long dimension(ResolvedOrigin origin,Hop fallback,boolean rows) {
		MemoSources.Source source = sources.source(origin.compiledOwnerId());
		if(source != null) return rows ? source.rows() : source.columns();
		if(origin.kind() == OriginKind.LOOP_HEAD_CONTRACT) {
			Head head = headsBySlot.get(new LoopSlot(origin.referenceId(),origin.boundarySlot()));
			var shape = shapes.bindingShape(head.value().headBindingId());
			return rows ? shape.rows() : shape.columns();
		}
		Hop hop = origin.kind() == OriginKind.COMPILED_OWNER
			? nativeOutputHop(origin,shapes.viewAt(origin.referenceId())) : fallback;
		return rows ? hop.getDim1() : hop.getDim2();
	}
	private List<List<Integer>> framesFor(int region) {
		return facts.callFramesForRegion(region);
	}
	private List<List<Integer>> framesForOccurrence(Occurrence occurrence) {
		return facts.occurrenceContexts(occurrence.id()).stream()
			.map(MemoProgramFacts.OccurrenceContext::callFrame).distinct().toList();
	}
	private double frequency(int region,List<Integer> frame) {
		return frequency(region,frame,new LinkedHashMap<>());
	}
	private double frequency(int region,List<Integer> frame,Map<GuardDecision,Boolean> chargedGuards) {
		if(region < 0) return 1;
		var f = facts.executionFrequency(region);
		double own = switch(f.kind()) {
			case ONCE -> 1;
			case GUARDED_THEN, GUARDED_ELSE -> {
				int guard=facts.region(region).guardId();
				boolean branch=f.kind()==MemoProgramFacts.FrequencyKind.GUARDED_THEN;
				GuardDecision decision=facts.guardDecision(guard,frame);
				boolean required=facts.canonicalGuardValue(guard,frame,branch);
				Boolean prior=chargedGuards.putIfAbsent(decision,required);
				yield prior!=null ? prior==required ? 1 : 0
					: required ? profile.branchThenProbability() : 1-profile.branchThenProbability();
			}
			case LOOP_ITERATIONS -> f.multiplier() < 0 ? profile.unknownLoopIterations() : f.multiplier();
			case FUNCTION_INVOCATIONS -> 1;
		};
		if(f.kind() == MemoProgramFacts.FrequencyKind.FUNCTION_INVOCATIONS && !frame.isEmpty()) {
			var call = facts.call(frame.get(frame.size()-1));
			return frequency(facts.occurrence(call.callOccurrenceId()).regionId(),frame.subList(0,frame.size()-1),
				chargedGuards);
		}
		return own*frequency(f.parentRegionId(),frame,chargedGuards);
	}
	private double frequency(int region,List<Integer> frame,EvaluationContext evaluation) {
		return frequency(region,frame,evaluation,new LinkedHashMap<>());
	}
	private double frequency(int region,List<Integer> frame,EvaluationContext evaluation,
		Map<GuardDecision,Boolean> chargedGuards) {
		if(region<0) return 1;
		var f=facts.executionFrequency(region);
		double own=switch(f.kind()) {
			case ONCE,FUNCTION_INVOCATIONS -> 1;
			case GUARDED_THEN,GUARDED_ELSE -> {
				int guard=facts.region(region).guardId();
				Boolean proven=phaseGuard(guard,frame,evaluation.iterationPhases());
				boolean then=f.kind()==MemoProgramFacts.FrequencyKind.GUARDED_THEN;
				if(proven!=null) yield proven==then ? 1 : 0;
				GuardDecision decision=facts.guardDecision(guard,frame);
				boolean required=facts.canonicalGuardValue(guard,frame,then);
				Boolean prior=chargedGuards.putIfAbsent(decision,required);
				yield prior!=null ? prior==required ? 1 : 0
					: required ? profile.branchThenProbability() : 1-profile.branchThenProbability();
			}
			case LOOP_ITERATIONS -> {
				LoopCardinality cardinality=evaluation.cardinalities().get(
					new LoopCardinalityDecision(f.referenceId(),frame));
				LoopExecutionCase executionCase=cardinality==null ? null : facts.loopExecutionCases(f.referenceId())
					.stream().filter(value -> value.cardinality()==cardinality).findFirst().orElse(null);
				double count=executionCase!=null && executionCase.exactIterations()>=0
					? executionCase.exactIterations() : f.multiplier()<0
						? profile.unknownLoopIterations() : f.multiplier();
				IterationPhase phase=evaluation.iterationPhases().get(new LoopDecision(f.referenceId(),frame));
				yield cardinality==LoopCardinality.ZERO ? 0
					: cardinality==LoopCardinality.ONE ? phase==null || phase==IterationPhase.FIRST ? 1 : 0
					: phase==null ? count : phaseIterations(count,phase);
			}
		};
		if(f.kind()==MemoProgramFacts.FrequencyKind.FUNCTION_INVOCATIONS && !frame.isEmpty()) {
			var call=facts.call(frame.get(frame.size()-1));
			return frequency(facts.occurrence(call.callOccurrenceId()).regionId(),frame.subList(0,frame.size()-1),
				evaluation,chargedGuards);
		}
		return own*frequency(f.parentRegionId(),frame,evaluation,chargedGuards);
	}
	static double phaseIterations(double count,IterationPhase phase) {
		if(!Double.isFinite(count) || count < 0 || phase == null)
			throw new IllegalArgumentException("Loop phase cost requires a finite nonnegative count and an exact phase");
		return switch(phase) {
			case FIRST -> count>=1 ? 1 : 0;
			case MIDDLE -> Math.max(0,count-2);
			case LAST -> count>=2 ? 1 : 0;
		};
	}
	private Boolean phaseGuard(int guard,List<Integer> frame,Map<LoopDecision,IterationPhase> phases) {
		if(guard<0) return null;
		Boolean result=null;
		for(var entry : phases.entrySet()) {
			if(!entry.getKey().callFrame().equals(frame)) continue;
			Boolean proof=facts.guardValue(entry.getKey().loopId(),guard,entry.getValue());
			if(proof==null) continue;
			if(result!=null && !result.equals(proof))
				throw new IllegalStateException("CONFLICTING_PHASE_GUARD_PROOF guard="+guard+" frame="+frame);
			result=proof;
		}
		return result;
	}
	private double environmentProbability(ResolvedEnvironment environment,EvaluationContext fixed) {
		double probability=cardinalityProbability(environment.cardinalities());
		for(var guard : environment.guards().entrySet()) if(!fixed.guards().containsKey(guard.getKey())
			&& phaseGuard(guard.getKey().guardId(),guard.getKey().callFrame(),environment.iterationPhases())==null)
			probability *= guard.getValue() ? profile.branchThenProbability() : 1-profile.branchThenProbability();
		return probability;
	}
	private double cardinalityProbability(
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities) {
		double probability=1;
		for(var decision : cardinalities.entrySet()) {
			LoopExecutionCase executionCase=facts.loopExecutionCases(decision.getKey().loopId()).stream()
				.filter(value -> value.cardinality()==decision.getValue()).findFirst().orElseThrow();
			// Runtime-dependent counts retain the historical typical-many estimator. ZERO/ONE
			// remain mandatory feasibility cases, but carry no uncalibrated probability mass.
			if(executionCase.exactIterations()<0 && decision.getValue()!=LoopCardinality.MANY) probability=0;
		}
		return probability;
	}
}

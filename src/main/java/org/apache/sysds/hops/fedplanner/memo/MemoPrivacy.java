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
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;

/** Exact, environment-sensitive privacy transfer over memo binding facts. */
public final class MemoPrivacy {
	public enum Status { EXACT, UNKNOWN }
	public enum Legality { LEGAL, ILLEGAL, DEFER }

	public record LoopSlot(int loopId, int slot) { }

	public record Result(Status status, Privacy privacy, String detail) {
		public Result {
			Objects.requireNonNull(status);
			Objects.requireNonNull(detail);
			if((status == Status.EXACT) != (privacy != null))
				throw new IllegalArgumentException("Only an exact result has a privacy value");
		}

		public static Result exact(Privacy privacy) {
			return new Result(Status.EXACT, Objects.requireNonNull(privacy), "exact");
		}

		public static Result unknown(String detail) {
			return new Result(Status.UNKNOWN, null, detail);
		}

		public boolean exact() { return status == Status.EXACT; }
	}

	public record RuleCheck(Legality legality, String reason) {
		public RuleCheck {
			Objects.requireNonNull(legality);
			Objects.requireNonNull(reason);
		}
	}

	private record LoopInvocationSlot(LoopSlot slot,List<Integer> callFrame) {
		private LoopInvocationSlot { callFrame=List.copyOf(callFrame); }
	}

	public static final class Context {
		private final EvaluationContext evaluation;
		private final Map<LoopSlot,Privacy> loopHeadPrivacy;
		private final Map<LoopInvocationSlot,Privacy> invocationPrivacy;
		private final boolean reachable;
		private final boolean payloadOnly;

		public Context(EvaluationContext evaluation,Map<LoopSlot,Privacy> loopHeadPrivacy) {
			this(Objects.requireNonNull(evaluation),Map.copyOf(loopHeadPrivacy),
				qualify(loopHeadPrivacy,evaluation.callFrame()),true,false);
		}

		public Context(EvaluationContext evaluation,Map<LoopSlot,Privacy> loopHeadPrivacy,boolean reachable) {
			this(Objects.requireNonNull(evaluation),Map.copyOf(loopHeadPrivacy),
				qualify(loopHeadPrivacy,evaluation.callFrame()),reachable,false);
		}

		private Context(EvaluationContext evaluation,Map<LoopSlot,Privacy> loopHeadPrivacy,
			Map<LoopInvocationSlot,Privacy> invocationPrivacy,boolean reachable,boolean payloadOnly) {
			this.evaluation=Objects.requireNonNull(evaluation);
			this.loopHeadPrivacy=Map.copyOf(loopHeadPrivacy);
			this.invocationPrivacy=Map.copyOf(invocationPrivacy);
			this.reachable=reachable;
			this.payloadOnly=payloadOnly;
		}

		public EvaluationContext evaluation() { return evaluation; }
		public Map<LoopSlot,Privacy> loopHeadPrivacy() { return loopHeadPrivacy; }
		public boolean reachable() { return reachable; }
		boolean payloadOnly() { return payloadOnly; }

		public static Context empty() {
			return new Context(EvaluationContext.empty(), Map.of());
		}

		public Context withEnvironment(ResolvedEnvironment environment) {
			return new Context(new EvaluationContext(environment.guards(), environment.loops(),
				evaluation.selections(), evaluation.maxDepth(), evaluation.callFrame(), environment.iterationPhases(),
				environment.cardinalities()), loopHeadPrivacy,invocationPrivacy,reachable,payloadOnly);
		}

		public Context withCallFrame(List<Integer> callFrame) {
			return new Context(evaluation.withCallFrame(callFrame),current(invocationPrivacy,callFrame),
				invocationPrivacy,reachable,payloadOnly);
		}

		public Context withLoopHead(int loopId, int slot, Privacy privacy) {
			Map<LoopSlot,Privacy> copy = new LinkedHashMap<>(loopHeadPrivacy);
			LoopSlot key=new LoopSlot(loopId,slot);
			copy.put(key,Objects.requireNonNull(privacy));
			Map<LoopInvocationSlot,Privacy> qualified=new LinkedHashMap<>(invocationPrivacy);
			qualified.put(new LoopInvocationSlot(key,evaluation.callFrame()),privacy);
			return new Context(evaluation,copy,qualified,reachable,payloadOnly);
		}

		Context withLoopHeadAt(List<Integer> callFrame,int loopId,int slot,Privacy privacy) {
			Context target=withCallFrame(callFrame).withLoopHead(loopId,slot,privacy);
			return new Context(evaluation,current(target.invocationPrivacy,evaluation.callFrame()),
				target.invocationPrivacy,reachable,payloadOnly);
		}

		Context withEvaluation(EvaluationContext replacement) {
			return new Context(replacement,current(invocationPrivacy,replacement.callFrame()),invocationPrivacy,
				reachable,payloadOnly);
		}

		private Context withLoopHeads(Map<LoopSlot,Privacy> privacy) {
			Map<LoopInvocationSlot,Privacy> qualified=new LinkedHashMap<>(invocationPrivacy);
			qualified.keySet().removeIf(key -> key.callFrame().equals(evaluation.callFrame()));
			qualified.putAll(qualify(privacy,evaluation.callFrame()));
			return new Context(evaluation,privacy,qualified,reachable,payloadOnly);
		}

		/** Query payload privacy only; availability remains an independent model constraint. */
		Context withPayloadOnly() {
			return payloadOnly ? this : new Context(evaluation,loopHeadPrivacy,invocationPrivacy,reachable,true);
		}

		private EvaluationContext resolverContext() {
			SelectionLookup delegate = evaluation.selections();
			SelectionLookup boundHeads = new SelectionLookup() {
				@Override public int selectedRule(int owner) { return delegate.selectedRule(owner); }
				@Override public MemoPartition selectedLayout(int owner) { return delegate.selectedLayout(owner); }
				@Override public MemoPartition selectedHeadLayout(int loop, int slot) {
					return delegate.selectedHeadLayout(loop, slot);
				}
				@Override public boolean headIsBound(int loop, int slot) {
					return loopHeadPrivacy.containsKey(new LoopSlot(loop, slot));
				}
			};
			return new EvaluationContext(evaluation.guards(), evaluation.loops(), boundHeads,
				evaluation.maxDepth(), evaluation.callFrame(), evaluation.iterationPhases(),evaluation.cardinalities());
		}

		private static Map<LoopInvocationSlot,Privacy> qualify(Map<LoopSlot,Privacy> privacy,List<Integer> frame) {
			Map<LoopInvocationSlot,Privacy> result=new LinkedHashMap<>();
			privacy.forEach((slot,value) -> result.put(new LoopInvocationSlot(slot,frame),value));
			return result;
		}
		private static Map<LoopSlot,Privacy> current(Map<LoopInvocationSlot,Privacy> privacy,List<Integer> frame) {
			Map<LoopSlot,Privacy> result=new LinkedHashMap<>();
			Map<LoopSlot,Integer> depth=new LinkedHashMap<>();
			privacy.forEach((slot,value) -> {
				List<Integer> defining=slot.callFrame();
				if(defining.size()>frame.size() || !frame.subList(0,defining.size()).equals(defining)) return;
				int prior=depth.getOrDefault(slot.slot(),-1);
				if(defining.size()>=prior) {
					depth.put(slot.slot(),defining.size()); result.put(slot.slot(),value);
				}
			});
			return result;
		}
	}

	private record EnvironmentKey(Map<MemoBindingResolver.GuardDecision,Boolean> guards,
		Map<MemoBindingResolver.LoopDecision,MemoBindingResolver.LoopPhase> loops,
		List<Integer> callFrame, Map<LoopSlot,Privacy> loopHeads,
		Map<LoopInvocationSlot,Privacy> loopInvocations,int maxDepth,boolean payloadOnly,
		Map<MemoBindingResolver.LoopDecision,MemoProgramFacts.IterationPhase> iterationPhases,
		Map<MemoBindingResolver.LoopCardinalityDecision,MemoProgramFacts.LoopCardinality> cardinalities) {
		private EnvironmentKey(Context context) {
			this(context.evaluation().guards(), context.evaluation().loops(),
				context.evaluation().callFrame(), context.loopHeadPrivacy(),context.invocationPrivacy,
				context.evaluation().maxDepth(),context.payloadOnly,
				context.evaluation().iterationPhases(),context.evaluation().cardinalities());
		}
	}

	private record EvaluationKey(boolean owner, int id, EnvironmentKey environment) { }
	private record ResidencyKey(EvaluationKey value,int outputSlot) { }
	private record LoopSummaryKey(int loopId, EnvironmentKey environment) { }
	private record LoopInvocationKey(int loopId,List<Integer> callFrame) { }
	private record StructuralKey(boolean occurrence,int id,List<Integer> callFrame) {
		private StructuralKey { callFrame=List.copyOf(callFrame); }
	}

	private final MemoProgramFacts facts;
	private final MemoBindingResolver resolver;
	private final MemoSources sourceSnapshot;
	private final Map<Integer,Privacy> sourcePrivacy;
	private final Map<Integer,List<Integer>> occurrencesByOwner;
	/** Occurrences whose transform metadata slot is explicitly authorized for public release. */
	private final Set<Integer> publicTransformMetadata;
	private final MemoResidency residency;
	private final BitSet[] bindingGuards, bindingLoops;
	/** Conservative influence only; exact values remain qualified by their evaluation context. */
	private final int[][] bindingConsumers;
	private long cacheHits, evaluations, loopBackedgeChecks, loopSummaryRounds, loopSummaryCacheHits;
	private long loopCandidateRemovals, invalidationVisits, invalidationEdges, dependencyEdges, indexVisits, indexEdges;
	public record Statistics(long evaluations,long cacheHits,int cacheEntries,long loopBackedgeChecks,
		long loopSummaryRounds,long loopSummaryCacheHits,long loopCandidateRemovals,long invalidationVisits,
		long invalidationEdges,long dependencyEdges,long indexVisits,long indexEdges) { }
	public Statistics statistics() {
		return new Statistics(evaluations,cacheHits,cache.size(),loopBackedgeChecks,loopSummaryRounds,
			loopSummaryCacheHits,loopCandidateRemovals,invalidationVisits,invalidationEdges,dependencyEdges,
			indexVisits,indexEdges);
	}
	private final Map<EvaluationKey,Result> cache = new LinkedHashMap<>(64,0.75f,true) {
		@Override protected boolean removeEldestEntry(Map.Entry<EvaluationKey,Result> eldest) {
			return size()>4096;
		}
	};
	/** Positive certificates only: a recursion/resource miss is never cached as a negative fact. */
	private final Map<ResidencyKey,Boolean> releasedCache = new LinkedHashMap<>(64,0.75f,true) {
		@Override protected boolean removeEldestEntry(Map.Entry<ResidencyKey,Boolean> eldest) {
			return size()>4096;
		}
	};
	private final Map<LoopSummaryKey,Map<LoopSlot,Result>> loopSummaryCache =
		new LinkedHashMap<>(16,0.75f,true) {
			@Override protected boolean removeEldestEntry(Map.Entry<LoopSummaryKey,Map<LoopSlot,Result>> eldest) {
				return size()>4096;
			}
		};
	private final Set<LoopInvocationKey> activeLoopSummaries = new LinkedHashSet<>();

	public MemoPrivacy(MemoProgramFacts facts, MemoBindingResolver resolver, MemoSources sources) {
		this(facts, resolver, privacyMap(Objects.requireNonNull(sources)), sources);
	}

	MemoPrivacy(MemoProgramFacts facts, MemoBindingResolver resolver, Map<Integer,Privacy> sourcePrivacy) {
		this(facts, resolver, sourcePrivacy, null);
	}

	private MemoPrivacy(MemoProgramFacts facts, MemoBindingResolver resolver,
		Map<Integer,Privacy> sourcePrivacy, MemoSources sourceSnapshot) {
		this.facts = Objects.requireNonNull(facts);
		this.resolver = Objects.requireNonNull(resolver);
		this.sourceSnapshot = sourceSnapshot;
		this.sourcePrivacy = Map.copyOf(sourcePrivacy);
		residency = new MemoResidency(facts,this.sourcePrivacy);
		Map<Integer,List<Integer>> byOwner = new LinkedHashMap<>();
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			byOwner.computeIfAbsent(occurrence.compiledOwnerId(), ignored -> new ArrayList<>()).add(occurrence.id());
		Map<Integer,List<Integer>> immutable = new LinkedHashMap<>();
		byOwner.forEach((owner, occurrences) -> immutable.put(owner, List.copyOf(occurrences)));
		occurrencesByOwner = Map.copyOf(immutable);
		Set<Integer> releasedMetadata = new LinkedHashSet<>();
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			if(allowsPublicTransformMetadata(occurrence.hop()))
				releasedMetadata.add(occurrence.id());
		// Freeze the process-level opt-in for this immutable planning request. A later
		// property mutation therefore cannot invalidate privacy cache entries.
		publicTransformMetadata = Set.copyOf(releasedMetadata);
		int count=facts.bindings().size();
		bindingGuards=new BitSet[count]; bindingLoops=new BitSet[count];
		bindingConsumers=indexPrivacyDependencies();
	}

	/** Reuse only within the immutable request that captured sources and release policy. */
	MemoBindingResolver resolverFor(MemoProgramFacts requestFacts, MemoSources requestSources) {
		if(facts != requestFacts || sourceSnapshot == null || sourceSnapshot != requestSources)
			throw new IllegalArgumentException("Privacy analysis belongs to a different planning request");
		return resolver;
	}

	/** One monotone facts-only index; no rule/source assignments participate. */
	private int[][] indexPrivacyDependencies() {
		int count=bindingGuards.length;
		List<List<Integer>> dependencies=new ArrayList<>(count),consumers=new ArrayList<>(count);
		for(int id=0; id<count; id++) {
			bindingGuards[id]=new BitSet(); bindingLoops[id]=new BitSet();
			dependencies.add(new ArrayList<>()); consumers.add(new ArrayList<>());
		}
		for(var node : facts.bindings()) {
			int id=node.id(); dependencies.get(id).addAll(node.children());
			if(node.kind()==MemoProgramFacts.BindingKind.SELECT) {
				bindingGuards[id].set(node.referenceId());
				for(var phase : facts.guardPhases()) if(phase.guardId()==node.referenceId())
					bindingLoops[id].set(phase.loopId());
			}
			if(node.kind()==MemoProgramFacts.BindingKind.LOOP_HEAD || node.kind()==MemoProgramFacts.BindingKind.LAST)
				bindingLoops[id].set(node.referenceId());
			if(node.kind()==MemoProgramFacts.BindingKind.FORMAL)
				for(var call : facts.callsForFunction(node.referenceId()))
					if(node.position()>=0 && node.position()<call.actualBindingIds().size())
						dependencies.get(id).add(call.actualBindingIds().get(node.position()));
			// A requested return exists only if the whole bound call completes. These
			// resolver dependencies are implicit rather than BindingNode children.
			if(node.kind()==MemoProgramFacts.BindingKind.CALL_RETURN && node.referenceId()>=0
				&& node.referenceId()<facts.calls().size())
				dependencies.get(id).add(facts.call(node.referenceId()).completionBindingId());
			if(node.kind()==MemoProgramFacts.BindingKind.CALL_COMPLETION && node.referenceId()>=0) {
				var call=facts.call(node.referenceId());
				dependencies.get(id).add(facts.functions().get(call.functionSummaryId()).completionBindingId());
				for(int returned : call.returnBindingIds()) {
					var value=facts.binding(returned);
					if(!value.children().isEmpty()) dependencies.get(id).add(
						value.children().get(value.children().size()>1 ? 1 : 0));
				}
			}
			if(node.kind()==MemoProgramFacts.BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				var occurrence=facts.occurrence(node.referenceId());
				if(occurrence.hop() instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD) {
					int value=facts.valueBinding(occurrence.id());
					if(value>=0 && value!=id) dependencies.get(id).add(value);
				}
				for(int position=0; position<occurrence.hop().getInput().size(); position++)
					dependencies.get(id).add(facts.inputBinding(occurrence.id(),position));
			}
		}
		// The resolver interprets a concrete inlined witness through its CALL_RETURN
		// wrapper. Include all wrappers conservatively, even when ambiguity makes
		// resolution UNKNOWN. This adds influence, never a new privacy/value fact.
		for(var call : facts.calls()) for(int returned : call.returnBindingIds()) {
			var node=facts.binding(returned);
			if(node.kind()==MemoProgramFacts.BindingKind.CALL_RETURN && node.children().size()>1)
				dependencies.get(node.children().get(1)).add(returned);
		}
		// Complete dependencies before reversing: a wrapper can refer to an earlier ID.
		for(int id=0; id<count; id++)
			for(int child : dependencies.get(id)) if(child>=0 && child<count) consumers.get(child).add(id);
		int[][] reverse=new int[count][];
		for(int id=0; id<count; id++) {
			reverse[id]=consumers.get(id).stream().mapToInt(Integer::intValue).distinct().toArray();
			dependencyEdges+=reverse[id].length;
		}
		ArrayDeque<Integer> pending=new ArrayDeque<>(); BitSet queued=new BitSet();
		for(int id=0; id<count; id++) { pending.add(id); queued.set(id); }
		while(!pending.isEmpty()) {
			int id=pending.removeFirst(); queued.clear(id); indexVisits++;
			int prior=bindingGuards[id].cardinality()+bindingLoops[id].cardinality();
			for(int child : dependencies.get(id)) if(child>=0 && child<count) {
				indexEdges++;
				bindingGuards[id].or(bindingGuards[child]); bindingLoops[id].or(bindingLoops[child]);
			}
			if(prior!=bindingGuards[id].cardinality()+bindingLoops[id].cardinality())
				for(int consumer : reverse[id]) if(!queued.get(consumer)) {
					pending.add(consumer); queued.set(consumer);
				}
		}
		return reverse;
	}

	private EvaluationKey key(boolean occurrence,int id,Context context) {
		BitSet guards,loops;
		if(!occurrence) { guards=bindingGuards[id]; loops=bindingLoops[id]; }
		else {
			guards=new BitSet(); loops=new BitSet();
			var value=facts.occurrence(id);
			int binding=facts.valueBinding(id);
			if(binding>=0) { guards.or(bindingGuards[binding]); loops.or(bindingLoops[binding]); }
			for(int position=0; position<value.hop().getInput().size(); position++) {
				binding=facts.inputBinding(id,position);
				if(binding>=0) { guards.or(bindingGuards[binding]); loops.or(bindingLoops[binding]); }
			}
		}
		EnvironmentKey all=new EnvironmentKey(context);
			return new EvaluationKey(occurrence,id,new EnvironmentKey(
				project(all.guards(),g -> guardRelevant(g,guards,all.callFrame())),
				project(all.loops(),l -> loops.get(l.loopId())),all.callFrame(),
				project(all.loopHeads(),h -> loops.get(h.loopId())),
				project(all.loopInvocations(),h -> loops.get(h.slot().loopId())),all.maxDepth(),all.payloadOnly(),
			project(all.iterationPhases(),l -> loops.get(l.loopId())),
			project(all.cardinalities(),l -> loops.get(l.loopId()))));
	}
	private boolean guardRelevant(MemoBindingResolver.GuardDecision decision,BitSet dependencies,
		List<Integer> frame) {
		if(decision.valueBindingId()<0) return dependencies.get(decision.guardId());
		for(int guard=dependencies.nextSetBit(0);guard>=0;guard=dependencies.nextSetBit(guard+1)) {
			for(int length=frame.size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,frame.subList(0,length))) return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,decision.callFrame().subList(0,length))) return true;
		}
		return false;
	}
	private static <K,V> Map<K,V> project(Map<K,V> values,java.util.function.Predicate<K> relevant) {
		if(values.isEmpty()) return Map.of();
		Map<K,V> result=new LinkedHashMap<>();
		for(var value : values.entrySet()) if(relevant.test(value.getKey())) result.put(value.getKey(),value.getValue());
		return Map.copyOf(result);
	}

	public Result privacyAt(int bindingId, Context context) {
		return evaluateBinding(bindingId, Objects.requireNonNull(context), new LinkedHashSet<>());
	}

	/** A positive residency proof is not an exact privacy/provenance label. */
	public boolean provesReleasedBinding(int bindingId) { return residency.provesReleasedBinding(bindingId); }
	public boolean provesReleasedOccurrence(int occurrenceId) { return residency.provesReleasedOccurrence(occurrenceId); }
	public RuleCheck localResidencyAt(int bindingId,Context context) {
			if(releasedBindingAt(bindingId,context,new LinkedHashSet<>()))
				return new RuleCheck(Legality.LEGAL,"all reaching payloads in this environment are released");
		Result exact=privacyAt(bindingId,context);
		return !exact.exact() ? new RuleCheck(Legality.DEFER,exact.detail())
			: unreleased(exact.privacy()) ? new RuleCheck(Legality.ILLEGAL,"protected value requires federated residency")
			: new RuleCheck(Legality.LEGAL,"exact reaching value is released");
	}

		/** Reuse existing origin proofs without merging their distinct exact privacy labels. */
		private boolean releasedBindingAt(int binding,Context context,Set<ResidencyKey> active) {
			if(binding<0 || binding>=facts.bindings().size()) return false;
			if(provesReleasedBinding(binding)) return true;
			ResidencyKey key=new ResidencyKey(key(false,binding,context),-1);
			if(releasedCache.containsKey(key)) return true;
			if(active.size()>=context.evaluation().maxDepth() || !active.add(key)) return false;
			try {
				boolean[] all={true};
				var visit=resolver.visitJoint(List.of(binding),context.resolverContext(),environment -> {
					ResolvedOrigin origin=environment.origins().get(0);
					Context exact=context.withEnvironment(environment).withCallFrame(origin.callFrame());
					all[0]=switch(origin.kind()) {
						case COMPILED_OWNER -> releasedOutputAt(origin.referenceId(),origin.boundarySlot(),exact,active);
						case ITERATOR_LOCAL -> true;
						case LOOP_HEAD_CONTRACT -> {
							Privacy head=exact.loopHeadPrivacy().get(new LoopSlot(origin.referenceId(),origin.boundarySlot()));
							yield head!=null && !unreleased(head);
						}
						default -> false; // Missing/terminated/unresolved is not a released payload.
					};
					return all[0];
				});
				boolean proven=all[0] && visit.status()==MemoBindingResolver.VisitStatus.COMPLETE
					&& visit.environmentCount()>0;
				if(proven) releasedCache.put(key,true);
				return proven;
			}
			finally { active.remove(key); }
		}

		private boolean releasedOutputAt(int occurrence,int slot,Context context,Set<ResidencyKey> active) {
			if(occurrence<0 || occurrence>=facts.occurrences().size()) return false;
			if(slot==1 && publicTransformMetadata.contains(occurrence)) return true;
			if(provesReleasedOccurrence(occurrence)) return true;
			var fact=facts.occurrence(occurrence);
			// Explicit source authority wins over a syntactically public HOP body.
			if(sourcePrivacy.containsKey(fact.compiledOwnerId())) return false;
			Hop hop=fact.hop();
			if(hop instanceof FunctionOp || hop instanceof DataOp data && isExternalSource(data)) return false;
			ResidencyKey key=new ResidencyKey(key(true,occurrence,context),slot);
			if(releasedCache.containsKey(key)) return true;
			if(active.size()>=context.evaluation().maxDepth() || !active.add(key)) return false;
			try {
				boolean proven;
				if(hop instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD)
					proven=releasedBindingAt(facts.valueBinding(occurrence),context,active);
				else {
					proven=MemoResidency.preservesReleasedInputs(hop);
					for(int position=0;proven && position<hop.getInput().size();position++)
						proven=releasedBindingAt(facts.inputBinding(occurrence,position),context,active);
				}
				if(proven) releasedCache.put(key,true);
				return proven;
			}
			finally { active.remove(key); }
		}

	public Result outputPrivacy(int owner, Context context) {
		List<Integer> occurrences = occurrencesByOwner.get(owner);
		if(occurrences == null || occurrences.size() != 1)
			return Result.unknown("compiled owner has no unique occurrence");
		return outputPrivacyAt(occurrences.get(0), context);
	}

	/** Exact output privacy for one value occurrence, independent of shared owner identity. */
	public Result outputPrivacyAt(int occurrenceId, Context context) {
		return evaluateOccurrence(occurrenceId, Objects.requireNonNull(context), new LinkedHashSet<>());
	}

	/**
	 * Computes a grounded, iteration-invariant loop-head privacy contract. A
	 * different backedge privacy is not promoted to an "exact" strongest join:
	 * callers must retain the temporal cases, so this compact summary is UNKNOWN.
	 */
	public Map<LoopSlot,Result> summarizeLoop(int loopId, Context context) {
		LoopSummaryKey summaryKey=new LoopSummaryKey(loopId,new EnvironmentKey(context));
		Map<LoopSlot,Result> cached=loopSummaryCache.get(summaryKey);
		if(cached!=null) { loopSummaryCacheHits++; return cached; }
		MemoProgramFacts.LoopRecord loop = facts.loop(loopId);
		LoopInvocationKey invocationKey=new LoopInvocationKey(loopId,context.evaluation().callFrame());
		if(!activeLoopSummaries.add(invocationKey))
			return unknownLoop(loop,"loop privacy summary cycle is not grounded");
		try {
		Map<LoopSlot,Result> entry = new LinkedHashMap<>(), rejected = new LinkedHashMap<>();
		Set<LoopSlot> candidates = new LinkedHashSet<>();
		for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
			LoopSlot slot = new LoopSlot(loopId, value.slot());
			Result fact = privacyAt(value.entryBindingId(), context);
			entry.put(slot, fact);
			if(fact.exact()) candidates.add(slot);
			else rejected.put(slot, Result.unknown("loop entry privacy is unresolved"));
		}

		// A backedge may appear invariant only because it was evaluated with another
		// head's entry privacy. If that other head is rejected, reevaluate the
		// affected survivors without the invalid assumption. Keep synchronous removal
		// batches; changing the order must not change the invariant or its diagnosis.
		Set<LoopSlot> dirty=new LinkedHashSet<>(candidates);
		while(!dirty.isEmpty()) {
			loopSummaryRounds++;
			Map<LoopSlot,Privacy> current = new LinkedHashMap<>(context.loopHeadPrivacy());
			for(LoopSlot slot : candidates) current.put(slot, entry.get(slot).privacy());
			Context bound = withNestedLoopSummaries(loopId,context.withLoopHeads(current));
			Set<LoopSlot> remove = new LinkedHashSet<>();
			for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
				LoopSlot slot = new LoopSlot(loopId, value.slot());
				if(!dirty.contains(slot)) continue;
				loopBackedgeChecks++;
				Result backedge = privacyAt(value.backedgeBindingId(), bound);
				if(!backedge.exact()) {
					remove.add(slot);
					rejected.put(slot, Result.unknown("loop backedge privacy is unresolved"));
				}
				else if(backedge.privacy() != entry.get(slot).privacy()) {
					remove.add(slot);
					rejected.put(slot, Result.unknown("loop privacy changes between entry and backedge"));
				}
			}
			if(remove.isEmpty()) break;
			candidates.removeAll(remove);
			loopCandidateRemovals+=remove.size();
			dirty.clear();
			if(!candidates.isEmpty()) {
				BitSet affected=affectedBindings(loop,remove);
				for(var value : loop.carriedValues()) {
					LoopSlot slot=new LoopSlot(loopId,value.slot());
					if(candidates.contains(slot) && affected.get(value.backedgeBindingId())) dirty.add(slot);
				}
			}
		}

		Map<LoopSlot,Result> result = new LinkedHashMap<>();
		for(MemoProgramFacts.CarriedValue value : loop.carriedValues()) {
			LoopSlot slot = new LoopSlot(loopId, value.slot());
			result.put(slot, candidates.contains(slot) ? entry.get(slot) : rejected.get(slot));
		}
		Map<LoopSlot,Result> summary=Map.copyOf(result);
		loopSummaryCache.put(summaryKey,summary);
		return summary;
		}
		finally { activeLoopSummaries.remove(invocationKey); }
	}

	/** Follow removed assumptions through nested/removed heads and implicit call completion.
	 * No head is a traversal cut. Only targets, not paths, are restricted to this loop.
	 */
	private BitSet affectedBindings(MemoProgramFacts.LoopRecord loop,Set<LoopSlot> removed) {
		BitSet reached=new BitSet(bindingConsumers.length);
		ArrayDeque<Integer> pending=new ArrayDeque<>();
		for(var value : loop.carriedValues())
			if(removed.contains(new LoopSlot(loop.id(),value.slot())) && !reached.get(value.headBindingId())) {
				reached.set(value.headBindingId()); pending.add(value.headBindingId());
			}
		while(!pending.isEmpty()) {
			int binding=pending.removeFirst(); invalidationVisits++;
			for(int consumer : bindingConsumers[binding]) {
				invalidationEdges++;
				if(!reached.get(consumer)) { reached.set(consumer); pending.add(consumer); }
			}
		}
		return reached;
	}

	/** Ground direct nested loops under the candidate heads of their enclosing loop. */
	private Context withNestedLoopSummaries(int loopId,Context context) {
		Context result=context;
		for(MemoProgramFacts.LoopRecord nested : facts.loops()) {
			if(nested.id()==loopId || directEnclosingLoop(nested)!=loopId) continue;
			Map<LoopSlot,Result> summary=summarizeLoop(nested.id(),result);
			for(var entry : summary.entrySet()) if(entry.getValue().exact())
				result=result.withLoopHead(entry.getKey().loopId(),entry.getKey().slot(),entry.getValue().privacy());
		}
		return result;
	}

	private int directEnclosingLoop(MemoProgramFacts.LoopRecord loop) {
		for(int region=facts.region(loop.regionId()).parentId(); region>=0; region=facts.region(region).parentId()) {
			int enclosing=facts.region(region).loopId();
			if(enclosing>=0 && enclosing!=loop.id()) return enclosing;
		}
		return -1;
	}

	/** Early rule gate. Conditional or unresolved privacy is deliberately deferred. */
	public RuleCheck unconditionalRuleLegality(int owner, RuleMemo.Rule rule,
		List<InputRole> roles, Context context) {
		List<Integer> occurrences = occurrencesByOwner.get(owner);
		if(occurrences == null || occurrences.size() != 1)
			return new RuleCheck(Legality.DEFER, "owner occurrence is absent or context-dependent");
		return unconditionalRuleLegalityAt(occurrences.get(0), rule, roles, context);
	}

	/** Early rule gate for one exact occurrence and reaching-definition context. */
	public RuleCheck unconditionalRuleLegalityAt(int occurrence, RuleMemo.Rule rule,
		List<InputRole> roles, Context context) {
		Objects.requireNonNull(rule);
		Objects.requireNonNull(roles);
		Objects.requireNonNull(context);
		if(occurrence < 0 || occurrence >= facts.occurrences().size())
			return new RuleCheck(Legality.DEFER, "occurrence is absent");
		if(rule.inputs().size() != roles.size())
			throw new IllegalArgumentException("Input roles must match the native rule arity");
		for(int position = 0; position < roles.size(); position++) {
			// Every privacy level permits an already federated input. Unknown privacy
			// is not a reason to defer a placement-independent legal residency.
			if(rule.inputs().get(position) != MemoPartition.LOCAL) continue;
			int binding = facts.inputBinding(occurrence, position);
			if(binding < 0) return new RuleCheck(Legality.DEFER, "input binding is missing");
				RuleCheck input = localResidencyAt(binding,context);
				if(input.legality()==Legality.DEFER) return input;
				if(input.legality()==Legality.ILLEGAL)
				return new RuleCheck(Legality.ILLEGAL, roles.get(position) == InputRole.METADATA
					? "protected metadata may be inspected remotely but not collected"
					: "protected input requires federated residency");
		}
		if((rule.exec() == ExecType.CP || rule.outputs().contains(MemoPartition.LOCAL))
			&& !provesReleasedOccurrence(occurrence)) {
			for(int slot=0; slot<rule.outputs().size(); slot++) {
				if(rule.outputs().get(slot)!=MemoPartition.LOCAL) continue;
					if(releasedOutputAt(occurrence,slot,context,new LinkedHashSet<>())) continue;
				Result output=outputPrivacyAt(occurrence,slot,context,new LinkedHashSet<>());
				if(!output.exact()) return new RuleCheck(Legality.DEFER,output.detail());
				if(unreleased(output.privacy()))
					return new RuleCheck(Legality.ILLEGAL,
						"protected output slot "+slot+" may not be materialized locally");
			}
		}
			return new RuleCheck(Legality.LEGAL, "the rule preserves proven privacy residency");
	}

	/**
	 * Refines only privacy-ambiguous reaching values. A native kernel ends a supply
	 * edge, but not the semantic privacy dependency of its result. Exact summaries
	 * stop recursion; conditional summaries carry existing guard decisions lazily.
	 */
	public MemoBindingResolver.VisitResult visitConditionalInputs(List<Integer> bindings,Context context,
		java.util.function.Predicate<Context> visitor) {
		boolean[] unresolved={false}; int[] count={0}, budget={context.evaluation().maxDepth()};
		boolean complete=conditionalInputs(bindings,context,visitor,unresolved,count,budget);
		return new MemoBindingResolver.VisitResult(unresolved[0] ? MemoBindingResolver.VisitStatus.UNRESOLVED
			: !complete ? MemoBindingResolver.VisitStatus.STOPPED : MemoBindingResolver.VisitStatus.COMPLETE,
			count[0],"conditional privacy projection");
	}

	private boolean conditionalInputs(List<Integer> bindings,Context context,
		java.util.function.Predicate<Context> visitor,boolean[] unresolved,int[] count,int[] budget) {
		if(budget[0]--<=0) { unresolved[0]=true; return false; }
		MemoBindingResolver.GuardDecision guard=null;
		for(int binding : bindings) {
			guard=conditionalGuard(binding,context);
			if(guard!=null) break;
		}
		if(guard==null) { count[0]++; return visitor.test(context); }
		// Only guard valuations branch. Walking repeated kernel dependencies does
		// not compose source continuations or duplicate their Cartesian product.
		for(boolean arm : new boolean[] {false,true}) {
			Map<MemoBindingResolver.GuardDecision,Boolean> guards=new LinkedHashMap<>(context.evaluation().guards());
			guards.put(guard,arm);
			Context refined=context.withEvaluation(new EvaluationContext(guards,context.evaluation().loops(),
				context.evaluation().selections(),context.evaluation().maxDepth(),context.evaluation().callFrame(),
				context.evaluation().iterationPhases(),context.evaluation().cardinalities()));
			if(!conditionalInputs(bindings,refined,visitor,unresolved,count,budget)) return false;
		}
		return true;
	}

	private MemoBindingResolver.GuardDecision conditionalGuard(int binding,Context context) {
		if(provesReleasedBinding(binding)) return null;
		Result singleton=structuralBinding(binding,context.evaluation().callFrame(),context,
			new LinkedHashMap<>(),new LinkedHashSet<>());
		if(singleton.exact()) return null;
		// Do not ask the exact resolver to enumerate every remaining reaching
		// definition merely to discover which guard to refine next.  The binding
		// graph already records those dependencies.  Exact privacy is evaluated
		// only after all privacy-relevant symbolic guards for this binding are
		// fixed (or when the binding has no such guard).
		MemoBindingResolver.GuardDecision guard=firstUnfixedGuard(binding,
			context.evaluation().callFrame(),context,new LinkedHashSet<>());
		return guard;
	}

	/** Finds one exact symbolic guard dependency without resolving producer origins. */
	private MemoBindingResolver.GuardDecision firstUnfixedGuard(int binding,List<Integer> frame,
		Context context,Set<StructuralKey> visited) {
		if(binding<0 || binding>=facts.bindings().size() || bindingGuards[binding].isEmpty()) return null;
		StructuralKey visit=new StructuralKey(false,binding,frame);
		if(!visited.add(visit)) return null;
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==MemoProgramFacts.BindingKind.SELECT) {
			MemoBindingResolver.GuardDecision decision=facts.guardDecision(node.referenceId(),frame);
			if(!context.evaluation().guards().containsKey(decision) && phaseGuard(decision,context)==null
				&& node.children().size()>=2) {
				Map<StructuralKey,Result> memo=new LinkedHashMap<>();
				Result left=structuralBinding(node.children().get(0),frame,context,memo,new LinkedHashSet<>());
				Result right=structuralBinding(node.children().get(1),frame,context,memo,new LinkedHashSet<>());
				// Equal exact arms make this guard irrelevant to privacy. Two
				// unresolved arms do not become exact merely by selecting between
				// them; descend to a nested guard that can refine an arm instead.
				if(left.exact() && right.exact() && left.privacy()==right.privacy()) return null;
				if(left.exact() || right.exact()) return decision;
			}
		}
		List<Integer> dependencies=new ArrayList<>();
		switch(node.kind()) {
			case SELECT, LOOP_HEAD, LAST, CALL_ARG -> dependencies.addAll(node.children());
			case FORMAL -> {
				for(int index=frame.size()-1;index>=0;index--) {
					var call=facts.call(frame.get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return firstUnfixedGuard(call.actualBindingIds().get(node.position()),frame.subList(0,index),
							context,visited);
				}
			}
			case CALL_RETURN -> {
				// Consumer activation is validated by MemoProgramModel.visitActive.
				// Missing-return/completion guards decide whether the consumer runs,
				// not the privacy class of a payload on an active path.  Rewalking the
				// whole completion graph here both duplicates that proof and creates an
				// unrelated guard product.
				if(node.children().size()>1) dependencies.add(node.children().get(1));
				else if(!node.children().isEmpty()) {
					List<Integer> nested=new ArrayList<>(frame); nested.add(node.referenceId());
					return firstUnfixedGuard(node.children().get(0),nested,context,visited);
				}
			}
			case CALL_COMPLETION -> {
				dependencies.addAll(node.children());
				if(node.referenceId()>=0) {
					var call=facts.call(node.referenceId());
					List<Integer> nested=new ArrayList<>(frame); nested.add(call.id());
					int completion=facts.functions().get(call.functionSummaryId()).completionBindingId();
					MemoBindingResolver.GuardDecision guard=firstUnfixedGuard(completion,nested,context,visited);
					if(guard!=null) return guard;
					for(int returned : call.returnBindingIds()) {
						var value=facts.binding(returned);
						if(value.children().isEmpty()) continue;
						int payload=value.children().get(value.children().size()>1 ? 1 : 0);
						List<Integer> payloadFrame=value.children().size()>1 ? frame : nested;
						guard=firstUnfixedGuard(payload,payloadFrame,context,visited);
						if(guard!=null) return guard;
					}
				}
			}
			case DEFINITION -> {
				if(!node.children().isEmpty()) dependencies.add(node.children().get(0));
				else if(node.referenceId()>=0 && node.referenceId()<facts.occurrences().size()) {
					var occurrence=facts.occurrence(node.referenceId());
					for(int position=0;position<occurrence.hop().getInput().size();position++)
						dependencies.add(facts.inputBinding(occurrence.id(),position));
				}
			}
			case UNBOUND, MISSING -> { }
		}
		for(int dependency : dependencies) {
			MemoBindingResolver.GuardDecision guard=firstUnfixedGuard(dependency,frame,context,visited);
			if(guard!=null) return guard;
		}
		return null;
	}

	private Boolean phaseGuard(MemoBindingResolver.GuardDecision guard,Context context) {
		Boolean result=null;
		for(var phase : context.evaluation().iterationPhases().entrySet()) {
			if(!phase.getKey().callFrame().equals(guard.callFrame())) continue;
			Boolean value=facts.guardValue(phase.getKey().loopId(),guard.guardId(),phase.getValue());
			if(value==null) continue;
			if(guard.valueBindingId()>=0)
				value=facts.canonicalGuardValue(guard.guardId(),guard.callFrame(),value);
			if(result!=null && !result.equals(value)) return null;
			result=value;
		}
		return result;
	}

	private Result evaluateBinding(int bindingId, Context context, Set<EvaluationKey> active) {
		if(bindingId < 0 || bindingId >= facts.bindings().size())
			return Result.unknown("binding is absent");
		EvaluationKey key = key(false,bindingId,context);
		Result cached = cache.get(key);
		if(cached != null) { cacheHits++; return cached; }
		evaluations++;
		if(!active.add(key)) return Result.unknown("privacy binding cycle is not grounded");
		try {
			// Most program values have one privacy class even when control flow has
			// many reaching definitions.  Prove that singleton directly over the
			// binding DAG before asking the exact resolver to enumerate origins.
			// UNKNOWN is only a miss: the exact path below remains authoritative.
			Result structural=structuralBinding(bindingId,context.evaluation().callFrame(),context,
				new LinkedHashMap<>(),new LinkedHashSet<>());
			if(structural.exact()) {
				cache.put(key,structural);
				return structural;
			}
			List<Result> environments = new ArrayList<>();
			MemoBindingResolver.VisitResult visit = resolver.visitJoint(List.of(bindingId),
				context.resolverContext(), environment -> {
					Context exact = context.withEnvironment(environment);
					environments.add(privacyOf(environment.origins().get(0), exact, active));
					return true;
				});
			Result result = mergeExact(environments, visit.status() == MemoBindingResolver.VisitStatus.COMPLETE,
				"binding has unresolved or environment-dependent privacy");
			cache.put(key, result);
			return result;
		}
		finally { active.remove(key); }
	}

	private Result structuralBinding(int bindingId,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		if(bindingId<0 || bindingId>=facts.bindings().size()) return Result.unknown("binding is absent");
		StructuralKey key=new StructuralKey(false,bindingId,frame);
		Result cached=memo.get(key);
		if(cached!=null) return cached;
		if(!active.add(key)) return Result.unknown("structural privacy cycle is not grounded");
		Result result;
		try {
			MemoProgramFacts.BindingNode binding=facts.binding(bindingId);
			result=switch(binding.kind()) {
				case SELECT -> structuralSelect(binding,frame,context,memo,active);
				case LOOP_HEAD -> structuralLoopHead(binding,frame,context,memo,active);
				case LAST -> structuralLast(binding,frame,context,memo,active);
				case FORMAL -> structuralFormal(binding,frame,context,memo,active);
				case CALL_ARG -> binding.children().isEmpty() ? Result.unknown("call argument is absent")
					: structuralBinding(binding.children().get(0),frame,context,memo,active);
				case CALL_RETURN -> structuralCallReturn(binding,frame,context,memo,active);
				case DEFINITION -> !binding.children().isEmpty()
					? structuralBinding(binding.children().get(0),frame,context,memo,active)
					: structuralOutput(binding.referenceId(),binding.position(),frame,context,memo,active);
				case CALL_COMPLETION, UNBOUND, MISSING -> Result.unknown("binding has no exact payload privacy");
			};
		}
		finally { active.remove(key); }
		memo.put(key,result);
		return result;
	}

	private Result structuralSelect(MemoProgramFacts.BindingNode binding,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		if(binding.children().size()<2) return Result.unknown("select binding is incomplete");
		Boolean selected=selectedGuardValue(binding.referenceId(),frame,context);
		if(selected!=null)
			return structuralBinding(binding.children().get(selected ? 0 : 1),frame,context,memo,active);
		return mergeStructural(
			structuralBinding(binding.children().get(0),frame,context,memo,active),
			structuralBinding(binding.children().get(1),frame,context,memo,active));
	}

	private Boolean selectedGuardValue(int guardId,List<Integer> frame,Context context) {
		MemoBindingResolver.GuardDecision canonical=facts.guardDecision(guardId,frame);
		Boolean selected=context.evaluation().guards().get(canonical);
		if(selected!=null) return facts.branchGuardValue(guardId,frame,selected);
		// Compatibility for explicit contexts which still carry syntactic keys.
		selected=context.evaluation().guards().get(new MemoBindingResolver.GuardDecision(guardId,frame));
		if(selected!=null) return selected;
		int loop=facts.enclosingLoop(facts.guard(guardId).parentRegionId());
		MemoProgramFacts.IterationPhase phase=loop<0 ? null : context.evaluation().iterationPhases()
			.get(new MemoBindingResolver.LoopDecision(loop,frame));
		return phase==null ? null : facts.guardValue(loop,guardId,phase);
	}

	private Result structuralLoopHead(MemoProgramFacts.BindingNode binding,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		Privacy bound=context.withCallFrame(frame).loopHeadPrivacy()
			.get(new LoopSlot(binding.referenceId(),binding.position()));
		if(bound!=null) return Result.exact(bound);
		if(binding.children().size()<2) return Result.unknown("loop head is incomplete");
		MemoBindingResolver.LoopPhase phase=context.evaluation().loops()
			.get(new MemoBindingResolver.LoopDecision(binding.referenceId(),frame));
		if(phase!=null) return structuralBinding(binding.children().get(
			phase==MemoBindingResolver.LoopPhase.ENTRY ? 0 : 1),frame,context,memo,active);
		return mergeStructural(
			structuralBinding(binding.children().get(0),frame,context,memo,active),
			structuralBinding(binding.children().get(1),frame,context,memo,active));
	}

	private Result structuralLast(MemoProgramFacts.BindingNode binding,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		if(binding.children().size()<2) return Result.unknown("last binding is incomplete");
		MemoProgramFacts.LoopCardinality cardinality=context.evaluation().cardinalities()
			.get(new MemoBindingResolver.LoopCardinalityDecision(binding.referenceId(),frame));
		if(cardinality!=null) return structuralBinding(binding.children().get(
			cardinality==MemoProgramFacts.LoopCardinality.ZERO ? 0 : 1),frame,context,memo,active);
		return mergeStructural(
			structuralBinding(binding.children().get(0),frame,context,memo,active),
			structuralBinding(binding.children().get(1),frame,context,memo,active));
	}

	private Result structuralFormal(MemoProgramFacts.BindingNode binding,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		for(int index=frame.size()-1;index>=0;index--) {
			MemoProgramFacts.CallBinding call=facts.call(frame.get(index));
			if(call.functionSummaryId()==binding.referenceId() && binding.position()>=0
				&& binding.position()<call.actualBindingIds().size())
				return structuralBinding(call.actualBindingIds().get(binding.position()),frame.subList(0,index),
					context,memo,active);
		}
		return Result.unknown("formal privacy requires an exact call frame");
	}

	private Result structuralOutput(int occurrence,int slot,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		return slot==1 && publicTransformMetadata.contains(occurrence) ? Result.exact(Privacy.PUBLIC)
			: structuralOccurrence(occurrence,frame,context,memo,active);
	}

	private Result structuralCallReturn(MemoProgramFacts.BindingNode binding,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		if(binding.children().isEmpty()) return Result.unknown("call return payload is absent");
		if(!context.payloadOnly() && binding.referenceId()>=0 && binding.referenceId()<facts.calls().size()) {
			MemoProgramFacts.CallBinding call=facts.call(binding.referenceId());
			if(call.completionBindingId()<0 || !resolver.alwaysAvailable(call.completionBindingId(),frame))
				return Result.unknown("call return completion is conditional");
		}
		if(binding.children().size()>1)
			return structuralBinding(binding.children().get(1),frame,context,memo,active);
		List<Integer> nested=new ArrayList<>(frame); nested.add(binding.referenceId());
		return structuralBinding(binding.children().get(0),nested,context,memo,active);
	}

	private Result structuralOccurrence(int occurrence,List<Integer> frame,Context context,
		Map<StructuralKey,Result> memo,Set<StructuralKey> active) {
		if(occurrence<0 || occurrence>=facts.occurrences().size()) return Result.unknown("occurrence is absent");
		StructuralKey key=new StructuralKey(true,occurrence,frame);
		Result cached=memo.get(key);
		if(cached!=null) return cached;
		if(!active.add(key)) return Result.unknown("structural occurrence cycle is not grounded");
		Result result;
		try {
			MemoProgramFacts.Occurrence fact=facts.occurrence(occurrence);
			Hop hop=fact.hop();
			Privacy declared=sourcePrivacy.get(fact.compiledOwnerId());
			if(declared!=null) result=Result.exact(declared);
			else if(hop instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD)
				result=structuralBinding(facts.valueBinding(occurrence),frame,context,memo,active);
			else if(hop instanceof FunctionOp function
				&& function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
				result=Result.unknown("function output privacy requires its return binding");
			else if(hop instanceof DataOp data && isExternalSource(data)) {
				Privacy external=declaredPrivacy(data);
				result=external==null ? Result.unknown("external source has no privacy authority")
					: Result.exact(external);
			}
			else {
				List<Privacy> inputs=new ArrayList<>(hop.getInput().size());
				for(int position=0;position<hop.getInput().size();position++) {
					Result input=structuralBinding(facts.inputBinding(occurrence,position),frame,context,memo,active);
					if(!input.exact()) { result=input; memo.put(key,result); return result; }
					inputs.add(input.privacy());
				}
				Privacy derived=inputs.isEmpty() ? Privacy.PUBLIC
					: FederatedPlannerUtils.derivePrivacyConstraint(hop,inputs);
				if(derived==Privacy.PUBLIC && inputs.contains(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC))
					derived=Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
				result=Result.exact(derived);
			}
		}
		finally { active.remove(key); }
		memo.put(key,result);
		return result;
	}

	private static Result mergeStructural(Result left,Result right) {
		return left.exact() && right.exact() && left.privacy()==right.privacy() ? left
			: Result.unknown("control-flow arms do not prove one privacy class");
	}

	private Result evaluateOccurrence(int occurrence, Context context, Set<EvaluationKey> active) {
		if(occurrence < 0 || occurrence >= facts.occurrences().size())
			return Result.unknown("occurrence is absent");
		EvaluationKey key = key(true,occurrence,context);
		Result cached = cache.get(key);
		if(cached != null) { cacheHits++; return cached; }
		evaluations++;
		int owner = facts.occurrence(occurrence).compiledOwnerId();
		Privacy declared = sourcePrivacy.get(owner);
		if(declared != null) return Result.exact(declared);
		if(!active.add(key)) return Result.unknown("privacy occurrence cycle is not grounded");
		try {
			Result result = deriveOccurrence(occurrence, context, active);
			cache.put(key, result);
			return result;
		}
		finally { active.remove(key); }
	}

	private Result deriveOccurrence(int occurrence, Context context, Set<EvaluationKey> active) {
		MemoProgramFacts.Occurrence fact = facts.occurrence(occurrence);
		Hop hop = fact.hop();
		if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD)
			return evaluateBinding(facts.valueBinding(occurrence), context, active);
		if(hop instanceof FunctionOp function
			&& function.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
			return Result.unknown("function output privacy requires its return binding");
		if(hop instanceof DataOp data && isExternalSource(data)) {
			Privacy declared = declaredPrivacy(data);
			return declared == null ? Result.unknown("external source has no privacy authority")
				: Result.exact(declared);
		}

		List<Integer> inputs = new ArrayList<>(hop.getInput().size());
		for(int position = 0; position < hop.getInput().size(); position++) {
			int binding = facts.inputBinding(occurrence, position);
			if(binding < 0) return Result.unknown("operator input binding is missing");
			inputs.add(binding);
		}
		if(inputs.isEmpty()) return Result.exact(Privacy.PUBLIC);

		// Privacy is a semantic property of each input value, not of the Cartesian
		// product of their concrete producer origins. A binding is exact only when
		// all its reachable definitions agree in this guarded context; correlated
		// guards are refined separately by visitConditionalInputs.
		List<Privacy> inputPrivacy=new ArrayList<>(inputs.size());
		for(int binding : inputs) {
			Result input=evaluateBinding(binding,context,active);
			if(!input.exact()) return Result.unknown("operator has unresolved or environment-dependent privacy");
			inputPrivacy.add(input.privacy());
		}
		Privacy derived=FederatedPlannerUtils.derivePrivacyConstraint(hop,inputPrivacy);
		if(derived==Privacy.PUBLIC && inputPrivacy.contains(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC))
			derived=Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
		return Result.exact(derived);
	}

	private Result privacyOf(ResolvedOrigin origin, Context context, Set<EvaluationKey> active) {
		return switch(origin.kind()) {
			case COMPILED_OWNER -> outputPrivacyAt(origin.referenceId(),origin.boundarySlot(),
				context.withCallFrame(origin.callFrame()),active);
			case ITERATOR_LOCAL -> Result.exact(Privacy.PUBLIC);
			case LOOP_HEAD_CONTRACT -> {
				Privacy privacy = context.withCallFrame(origin.callFrame()).loopHeadPrivacy().get(
					new LoopSlot(origin.referenceId(), origin.boundarySlot()));
				yield privacy == null ? Result.unknown("loop head privacy contract is absent")
					: Result.exact(privacy);
			}
			case FORMAL_BOUNDARY -> Result.unknown("function formal has no call frame");
			case NORMAL_CONTINUATION -> Result.unknown("continuation tokens carry no payload privacy");
			case UNBOUND -> Result.unknown("guarded value is unbound");
			case TERMINATED_AT_RETURN -> Result.unknown("function terminates before producing a payload");
			case MISSING -> Result.unknown("reaching definition is missing");
			case UNRESOLVED -> Result.unknown("binding resolution is incomplete");
		};
	}

	private Result outputPrivacyAt(int occurrence,int slot,Context context,Set<EvaluationKey> active) {
		if(slot==1 && publicTransformMetadata.contains(occurrence))
			return Result.exact(Privacy.PUBLIC);
		return evaluateOccurrence(occurrence,context,active);
	}

	private static boolean allowsPublicTransformMetadata(Hop hop) {
		if(!(hop instanceof FunctionOp function)
			|| function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !"transformencode".equalsIgnoreCase(function.getFunctionName())
			|| function.getInput().size()!=2 || function.getOutputs()==null || function.getOutputs().size()!=2
			|| !(function.getInput().get(1) instanceof LiteralOp literal))
			return false;
		return TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(literal.getStringValue());
	}

	private static Result mergeExact(List<Result> results, boolean complete, String detail) {
		if(!complete || results.isEmpty() || results.stream().anyMatch(result -> !result.exact()))
			return Result.unknown(detail);
		Privacy first = results.get(0).privacy();
		return results.stream().allMatch(result -> result.privacy() == first)
			? Result.exact(first) : Result.unknown(detail);
	}

	private static Map<LoopSlot,Result> unknownLoop(MemoProgramFacts.LoopRecord loop, String detail) {
		Map<LoopSlot,Result> result = new LinkedHashMap<>();
		for(MemoProgramFacts.CarriedValue value : loop.carriedValues())
			result.put(new LoopSlot(loop.id(), value.slot()), Result.unknown(detail));
		return Map.copyOf(result);
	}

	private static boolean unreleased(Privacy privacy) {
		return privacy == Privacy.PRIVATE || privacy == Privacy.PRIVATE_AGGREGATE;
	}

	private static boolean isExternalSource(DataOp data) {
		return data.getOp() == OpOpData.PERSISTENTREAD || data.getOp() == OpOpData.SQLREAD
			|| data.getOp() == OpOpData.FEDERATED;
	}

	private static Privacy declaredPrivacy(DataOp data) {
		if(!data.hasParameter(DataExpression.PRIVACY)) return null;
		Hop value = data.getInput(data.getParameterIndex(DataExpression.PRIVACY));
		if(!(value instanceof LiteralOp)) return null;
		String privacy = ((LiteralOp) value).getStringValue().trim().toLowerCase();
		return switch(privacy) {
			case "private" -> Privacy.PRIVATE;
			case "private-aggregate", "private_aggregate" -> Privacy.PRIVATE_AGGREGATE;
			case "private-aggregate-to-public", "private_aggregate_to_public" ->
				Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			case "public" -> Privacy.PUBLIC;
			default -> null;
		};
	}

	private static Map<Integer,Privacy> privacyMap(MemoSources sources) {
		return sources.privacyFacts();
	}
}

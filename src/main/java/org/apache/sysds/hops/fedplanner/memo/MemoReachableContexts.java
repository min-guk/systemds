/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.VisitResult;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.VisitStatus;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopExecutionCase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.OccurrenceContext;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.PhaseLoop;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.RegionKind;

/** Enumerates contexts in which an occurrence reaches normal execution. */
final class MemoReachableContexts {
	@FunctionalInterface interface Visitor {
		boolean visit(EvaluationContext context);
	}
	@FunctionalInterface private interface ResultVisitor {
		VisitResult visit(EvaluationContext context);
	}

	private final MemoProgramFacts facts;
	private final MemoBindingResolver resolver;

	MemoReachableContexts(MemoProgramFacts facts, MemoBindingResolver resolver) {
		this.facts = Objects.requireNonNull(facts);
		this.resolver = Objects.requireNonNull(resolver);
	}

	VisitResult visit(Occurrence occurrence, EvaluationContext base, Visitor visitor) {
		return visitResolved(occurrence, base, context -> resolver.visitJoint(List.of(), context,
			environment -> visitor.visit(withEnvironment(context, environment))));
	}

	VisitResult visitBindings(Occurrence occurrence, EvaluationContext base, List<Integer> bindings,
		MemoBindingResolver.EnvironmentVisitor visitor) {
		return visitResolved(occurrence, base, context -> resolver.visitJoint(bindings, context, visitor));
	}

	private VisitResult visitResolved(Occurrence occurrence, EvaluationContext base, ResultVisitor visitor) {
		EvaluationContext activeBase = withRequiredRegionGuards(occurrence, base);
		if(activeBase == null)
			return new VisitResult(VisitStatus.COMPLETE, 0, "unreachable correlated guard conjunction");
		List<PhaseLoop> loops = facts.occurrenceContexts(occurrence.id()).stream()
			.filter(context -> context.callFrame().equals(activeBase.callFrame()))
			.map(OccurrenceContext::phaseLoops).findFirst().orElse(null);
		if(loops == null)
			return new VisitResult(VisitStatus.UNRESOLVED, 0,
				"missing occurrence context " + occurrence.id() + " frame=" + activeBase.callFrame());
		return visitPhases(loops, 0, activeBase, context ->
			visitActivation(occurrence.activationBindingId(), context, visitor));
	}

	private EvaluationContext withRequiredRegionGuards(Occurrence occurrence, EvaluationContext base) {
		Map<MemoBindingResolver.GuardDecision,Boolean> guards = new LinkedHashMap<>(base.guards());
		if(!putRegionGuards(guards, occurrence.regionId(), base.callFrame())) return null;
		for(int depth = 0; depth < base.callFrame().size(); depth++) {
			MemoProgramFacts.CallBinding call = facts.call(base.callFrame().get(depth));
			if(call.callOccurrenceId() < 0) continue;
			if(!putRegionGuards(guards, facts.occurrence(call.callOccurrenceId()).regionId(),
				base.callFrame().subList(0, depth))) return null;
		}
		return new EvaluationContext(guards, base.loops(), base.selections(), base.maxDepth(),
			base.callFrame(), base.iterationPhases(), base.cardinalities());
	}

	private boolean putRegionGuards(Map<MemoBindingResolver.GuardDecision,Boolean> guards,
		int region, List<Integer> frame) {
		for(int current = region; current >= 0; current = facts.region(current).parentId()) {
			MemoProgramFacts.Region fact = facts.region(current);
			if(fact.kind() != RegionKind.IF_THEN && fact.kind() != RegionKind.IF_ELSE) continue;
			boolean branch = fact.kind() == RegionKind.IF_THEN;
			MemoBindingResolver.GuardDecision decision = facts.guardDecision(fact.guardId(), frame);
			boolean value = facts.canonicalGuardValue(fact.guardId(), frame, branch);
			Boolean prior = guards.putIfAbsent(decision, value);
			if(prior != null && prior != value) return false;
		}
		return true;
	}

	private VisitResult visitPhases(List<PhaseLoop> loops, int index, EvaluationContext context,
		ResultVisitor visitor) {
		if(index == loops.size()) return visitor.visit(context);
		PhaseLoop fact = loops.get(index);
		LoopDecision loop = new LoopDecision(fact.loopId(), fact.callFrame());
		LoopCardinalityDecision cardinality = new LoopCardinalityDecision(fact.loopId(), fact.callFrame());
		if(context.iterationPhases().containsKey(loop) && context.cardinalities().containsKey(cardinality))
			return visitPhases(loops, index + 1, context, visitor);
		if(fact.executionCases().isEmpty())
			return new VisitResult(VisitStatus.UNRESOLVED, 0,
				"missing loop execution cases for loop " + fact.loopId() + " frame=" + fact.callFrame());
		int count = 0;
		boolean unresolved = false;
		for(LoopExecutionCase executionCase : fact.executionCases()) {
			MemoProgramFacts.LoopCardinality fixedCardinality = context.cardinalities().get(cardinality);
			if(fixedCardinality != null && fixedCardinality != executionCase.cardinality()) continue;
			for(IterationPhase phase : executionCase.bodyPhases()) {
				IterationPhase fixedPhase = context.iterationPhases().get(loop);
				if(fixedPhase != null && fixedPhase != phase) continue;
				Map<LoopDecision,IterationPhase> phases = new LinkedHashMap<>(context.iterationPhases());
				phases.put(loop, phase);
				Map<LoopCardinalityDecision,MemoProgramFacts.LoopCardinality> cardinalities =
					new LinkedHashMap<>(context.cardinalities());
				cardinalities.put(cardinality, executionCase.cardinality());
				EvaluationContext next = new EvaluationContext(context.guards(), context.loops(),
					context.selections(), context.maxDepth(), context.callFrame(), phases, cardinalities);
				VisitResult result = visitPhases(loops, index + 1, next, visitor);
				count += result.environmentCount();
				if(result.status() == VisitStatus.STOPPED) return result;
				unresolved |= result.status() == VisitStatus.UNRESOLVED;
			}
		}
		return new VisitResult(unresolved ? VisitStatus.UNRESOLVED : VisitStatus.COMPLETE, count,
			"reachable structured loop phases");
	}

	private VisitResult visitActivation(int activation, EvaluationContext context, ResultVisitor visitor) {
		return visitCallerPrerequisites(activation, context, visitor, context.callFrame(), 0);
	}

	private VisitResult visitCallerPrerequisites(int activation, EvaluationContext context,
		ResultVisitor visitor, List<Integer> frame, int depth) {
		if(depth < frame.size()) {
			MemoProgramFacts.CallBinding call = facts.call(frame.get(depth));
			if(call.callOccurrenceId() >= 0) {
				Occurrence caller = facts.occurrence(call.callOccurrenceId());
				EvaluationContext callerContext = context.withCallFrame(frame.subList(0, depth));
				return visitAfterActivation(caller.activationBindingId(), callerContext, environment ->
					visitCallerPrerequisites(activation, withEnvironment(context, environment), visitor,
						frame, depth + 1));
			}
			return visitCallerPrerequisites(activation, context, visitor, frame, depth + 1);
		}
		return visitAfterActivation(activation, context, environment -> {
			EvaluationContext active = withEnvironment(context, environment);
			return visitor.visit(active);
		});
	}

	@FunctionalInterface private interface ActivatedVisitor {
		VisitResult visit(ResolvedEnvironment environment);
	}

	private VisitResult visitAfterActivation(int activation, EvaluationContext context,
		ActivatedVisitor visitor) {
		if(activation < 0 || resolver.alwaysAvailable(activation, context.callFrame()))
			return visitor.visit(environment(context));
		boolean[] unresolved = {false};
		boolean[] stopped = {false};
		int[] count = {0};
		VisitResult outer = resolver.visitJoint(List.of(activation), context, environment -> {
			OriginKind kind = environment.origins().get(0).kind();
			if(kind == OriginKind.TERMINATED_AT_RETURN) return true;
			if(kind != OriginKind.NORMAL_CONTINUATION) {
				unresolved[0] = true;
				return true;
			}
			VisitResult result = visitor.visit(environment);
			count[0] += result.environmentCount();
			unresolved[0] |= result.status() == VisitStatus.UNRESOLVED;
			stopped[0] |= result.status() == VisitStatus.STOPPED;
			return !stopped[0];
		});
		unresolved[0] |= outer.status() == VisitStatus.UNRESOLVED;
		return new VisitResult(stopped[0] ? VisitStatus.STOPPED
			: unresolved[0] ? VisitStatus.UNRESOLVED : VisitStatus.COMPLETE,
			count[0], "normal-continuation activation");
	}

	private static EvaluationContext withEnvironment(EvaluationContext context,
		ResolvedEnvironment environment) {
		return new EvaluationContext(environment.guards(), environment.loops(), context.selections(),
			context.maxDepth(), context.callFrame(), environment.iterationPhases(), environment.cardinalities());
	}

	private static ResolvedEnvironment environment(EvaluationContext context) {
		return new ResolvedEnvironment(List.of(), context.guards(), context.loops(),
			context.iterationPhases(), context.cardinalities());
	}

}
